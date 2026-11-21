import { useEffect, useState } from "react";
import { errorText } from "../api/client";
import { Select, TextInput } from "../components";
import { useVars } from "./VariablesPanel";

/**
 * Files chosen by name rules (Studio defects 21 Nov 2026, design "One file field"): a folder and rules written in
 * words; a regular expression is one kind of name rule, not the only way (AA's file fields offer a folder plus
 * regular-expression conditions). Stored as {"$files": {folder, rules, all, matchCase, subfolders}}; the runner reads
 * it (FileSelection) and gives a one-file command the first match, a many-files command all of them.
 */
export type FileRule = { what: "name" | "type" | "size" | "date"; op: string; value: string; value2?: string; which?: "created" | "modified"; unit?: string; inclusive?: boolean };
export type FileSelection = { folder: string; rules: FileRule[]; all?: boolean; matchCase?: boolean; subfolders?: boolean };

export const isSelection = (v: unknown): v is { $files: FileSelection } =>
  !!v && typeof v === "object" && !Array.isArray(v) && typeof (v as { $files?: unknown }).$files === "object" && (v as { $files?: unknown }).$files !== null;

const OPS: Record<FileRule["what"], [string, string][]> = {
  name: [["starts", "starts with"], ["ends", "ends with"], ["contains", "contains"], ["is", "is"], ["wildcard", "matches wildcard (*.pdf)"], ["regex", "matches pattern (regular expression)"]],
  type: [["in", "is one of"]],
  size: [["atLeast", "at least"], ["atMost", "at most"], ["exactly", "exactly"]],
  date: [["lastDays", "in the last … days"], ["on", "on"], ["before", "before"], ["after", "after"], ["between", "between"]],
};
const WHAT: [FileRule["what"], string][] = [["name", "Name"], ["type", "Type"], ["size", "Size"], ["date", "Date"]];
const blank = (what: FileRule["what"]): FileRule => ({ what, op: OPS[what][0][0], value: "", ...(what === "size" ? { unit: "KB" } : what === "date" ? { which: "modified", inclusive: true } : {}) });

/** The rules in words, for the step line: "name starts with INV_ · type is pdf, xlsx". */
export function describeSelection(s: FileSelection): string {
  const parts = s.rules.map((r) => {
    const op = OPS[r.what]?.find(([k]) => k === r.op)?.[1] ?? r.op;
    if (r.what === "date") return (r.which ?? "modified") + " " + (r.op === "lastDays" ? "in the last " + r.value + " days" : r.op === "between" ? "between " + r.value + " and " + (r.value2 ?? "") : op + " " + r.value);
    if (r.what === "size") return "size " + op + " " + r.value + " " + (r.unit ?? "KB");
    return r.what + " " + op.replace(/ \(.*\)$/, "") + " " + r.value;
  });
  return (parts.length ? parts.join(s.all === false ? " or " : " · ") : "every file") + " in " + (s.folder || "…");
}

/** The rule editor shown in a File field's "Name rule" mode, with the files that match now. */
export function FileRuleEditor({ id, value, onCommit, browse }: { id: string; value: FileSelection; onCommit: (v: FileSelection) => void;
  browse: (start: string, pick: (path: string) => void) => React.ReactNode }) {
  const vars = useVars();
  const [sel, setSel] = useState<FileSelection>(value);
  useEffect(() => setSel(value), [JSON.stringify(value)]);   // eslint-disable-line react-hooks/exhaustive-deps
  const put = (next: FileSelection) => { setSel(next); onCommit(next); };
  const setRule = (i: number, r: FileRule) => put({ ...sel, rules: sel.rules.map((x, j) => j === i ? r : x) });
  const [preview, setPreview] = useState<{ text: string; ok: boolean } | null>(null);
  // the files that match now (on this computer, where OneAgentStudio runs), a little after the last change
  useEffect(() => {
    if (!vars?.api || !sel.folder.trim() || sel.folder.trim().startsWith("$")) { setPreview(null); return; }
    const t = setTimeout(() => {
      vars.api.fsMatch(sel).then((r) => setPreview({ ok: true, text: r.count === 0 ? "No file matches now." : r.count + (r.count === 1 ? " file matches" : " files match") + " now: " + r.files.slice(0, 3).join(", ") + (r.count > 3 ? " …" : "") }),
        (e) => setPreview({ ok: false, text: errorText(e) }));
    }, 400);
    return () => clearTimeout(t);
  }, [vars?.api, JSON.stringify(sel)]);   // eslint-disable-line react-hooks/exhaustive-deps
  return <div className="st-rules" role="group" aria-label="Name rules">
    <div className="st-rules-row">
      <span className="st-rules-lab">In folder</span>
      <TextInput id={id} className="st-rules-grow" value={sel.folder} placeholder="C:\Invoices or $folder$" onChange={(e) => setSel({ ...sel, folder: e.target.value })}
        onBlur={() => onCommit(sel)} aria-label="Folder of the files" />
      {browse(sel.folder, (p) => put({ ...sel, folder: p }))}
    </div>
    {sel.rules.map((r, i) => <div key={i} className="st-rules-rule">
      <Select aria-label="What the rule checks" value={r.what} options={WHAT.map(([v, l]) => ({ value: v, label: l }))} onChange={(e) => setRule(i, blank(e.target.value as FileRule["what"]))} />
      {r.what === "date" && <Select aria-label="Which date" value={r.which ?? "modified"} options={[{ value: "modified", label: "modified" }, { value: "created", label: "created" }]}
        onChange={(e) => setRule(i, { ...r, which: e.target.value as "created" | "modified" })} />}
      <Select aria-label="How it compares" value={r.op} options={OPS[r.what].map(([v, l]) => ({ value: v, label: l }))} onChange={(e) => setRule(i, { ...r, op: e.target.value })} />
      <TextInput aria-label="Value" className={"st-rules-grow" + (r.op === "regex" ? " ui-mono" : "")} value={r.value}
        placeholder={r.what === "type" ? "pdf, xlsx" : r.what === "size" ? "100" : r.what === "date" ? (r.op === "lastDays" ? "7" : "2026-11-21") : r.op === "regex" ? "^INV_\\d{6}" : "INV_"}
        onChange={(e) => setRule(i, { ...r, value: e.target.value })} />
      {r.what === "date" && r.op === "between" && <TextInput aria-label="Until" className="st-rules-grow" value={r.value2 ?? ""} placeholder="2026-11-30" onChange={(e) => setRule(i, { ...r, value2: e.target.value })} />}
      {r.what === "size" && <Select aria-label="Unit" value={r.unit ?? "KB"} options={["B", "KB", "MB", "GB"].map((u) => ({ value: u, label: u }))} onChange={(e) => setRule(i, { ...r, unit: e.target.value })} />}
      <button type="button" className="st-rules-x" aria-label="Remove this rule" title="Remove this rule" onClick={() => put({ ...sel, rules: sel.rules.filter((_, j) => j !== i) })}>×</button>
    </div>)}
    <div className="st-rules-rule">
      <button type="button" className="st-link-btn" onClick={() => put({ ...sel, rules: [...sel.rules, blank("name")] })}>+ Add rule</button>
      {sel.rules.length > 1 && <Select aria-label="All rules or any" value={sel.all === false ? "any" : "all"} options={[{ value: "all", label: "All rules" }, { value: "any", label: "Any rule" }]}
        onChange={(e) => put({ ...sel, all: e.target.value === "all" })} />}
    </div>
    <div className="st-rules-rule">
      <label className="st-check-row"><input type="checkbox" checked={!!sel.matchCase} onChange={(e) => put({ ...sel, matchCase: e.target.checked })} /> Match case</label>
      <label className="st-check-row"><input type="checkbox" checked={!!sel.subfolders} onChange={(e) => put({ ...sel, subfolders: e.target.checked })} /> Include sub-folders</label>
    </div>
    {preview && <div className={"st-rules-preview" + (preview.ok ? "" : " is-error")} role="status">{preview.text}</div>}
  </div>;
}
