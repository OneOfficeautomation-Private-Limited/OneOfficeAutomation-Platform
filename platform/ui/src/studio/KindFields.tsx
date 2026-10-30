import { useEffect, useState } from "react";
import type { FlowStep, OpenWindow, PackageManifest, SavedConnection } from "../api/client";
import { Button, Field, Select } from "../components";
import { locate } from "./flowEdit";
import { CommitInput } from "./ValueSources";
import { useVars } from "./VariablesPanel";
import { SavedConnectionsDialog } from "./WorkspaceTools";
import { actionGroups, DESKTOP_ACTION, DESKTOP_NAMES, outcomeOf, WEB_NAMES } from "./stepKinds";
import { uniqueName } from "./variables";

type Win = { source?: string; variable?: string; title?: string; title_match?: string; case_sensitive?: boolean; url?: string; url_match?: string; link?: string; browser?: string };

const shortUrl = (u: string) => u.replace(/^https?:\/\//, "").slice(0, 48);

/**
 * Which window a web action works in: the browser (a tab picked from the open windows, or a title with how it must
 * match and whether case matters, the browser, part of the address), an application window, or a window variable.
 * The address of a picked page is kept to show, not to match, as the page may move on while the agent works.
 */
export function WindowEditor({ step }: { step: FlowStep }) {
  const vars = useVars();
  const api = vars?.api;
  const raw = step.args?.window;
  const v: Win = raw && typeof raw === "object" && !Array.isArray(raw) ? { ...(raw as Win) }
    : typeof raw === "string" && raw.trim() ? (raw.trim().startsWith("$") ? { source: "variable", variable: raw.trim() } : { title: raw }) : {};
  if (!["browser", "application", "variable"].includes(v.source ?? "")) v.source = "browser";
  const [open, setOpen] = useState<OpenWindow[] | null>(null);
  const [note, setNote] = useState("");
  const load = () => api?.windows().then((l) => { setOpen(l); setNote(""); }, () => { setOpen([]); setNote("Open windows are not available"); });
  useEffect(() => { load(); }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  if (!vars) return null;
  const commit = (next: Win) => {
    const out: Win = { source: next.source };
    if (next.source === "variable") { if (next.variable) out.variable = next.variable; }
    else {
      if (next.title) { out.title = next.title; out.title_match = next.title_match || "wildcard"; if (next.case_sensitive) out.case_sensitive = true; }
      if (next.source === "browser") { if (next.url) { out.url = next.url; if (next.url_match) out.url_match = next.url_match; } if (next.link) out.link = next.link; if (next.browser) out.browser = next.browser; }
    }
    const value = Object.keys(out).length > 1 ? out : undefined;
    vars.edit(withArg(vars.flow, step.id, "window", value));
  };
  const put = (patch: Partial<Win>) => commit({ ...v, ...patch });
  const want = v.source === "application" ? "application" : "browser";
  const list = (open ?? []).filter((w) => (w.source ?? "browser") === want && (w.title || w.url));
  const chosen = v.title ? list.findIndex((w) => w.title === v.title) : -1;
  const working = v.source === "variable" ? (v.variable ? "the window in " + v.variable : "")
    : v.title ? "“" + v.title + "”" + (v.title_match && v.title_match !== "equals" ? " (" + v.title_match + ")" : "") : "";
  return (
    <div className="st-win">
      <div className="st-seg" role="radiogroup" aria-label="Window source">
        {[["browser", "Browser"], ["application", "Application"], ["variable", "Variable"]].map(([k, t]) =>
          <button key={k} type="button" role="radio" aria-checked={v.source === k} className="st-seg-btn" onClick={() => put({ source: k })}>{t}</button>)}
      </div>
      <p className="st-win-now">Working in <strong>{working || "the browser's current tab (no window chosen)"}</strong></p>
      {v.source === "variable" ? (
        <Field label="Variable" layout="beside">{(id) => <CommitInput id={id} mono want="Window" initial={v.variable ?? ""} placeholder="$myBrowser" onCommit={(t) => put({ variable: t.trim() || undefined })} />}</Field>
      ) : <>
        <Field label="Open window" layout="beside">{(id) => <div className="st-row-tight">
          <Select id={id} value={chosen >= 0 ? String(chosen) : ""} onChange={(e) => {
            if (e.target.value === "none") { put({ title: undefined, link: undefined, url: undefined }); return; }
            const w = list[Number(e.target.value)];
            if (w) put({ title: w.title ?? "", title_match: v.title_match || "equals", ...(v.source === "browser" ? { link: w.url, browser: w.browser || v.browser } : {}) });
          }} options={[{ value: "", label: v.title && chosen < 0 ? "“" + v.title + "” (not open now)" : note || (open && !list.length ? "No open " + want + " windows" : "Pick from open windows…") },
            ...list.map((w, i) => ({ value: String(i), label: (w.title || "(no title)") + (w.url ? " — " + shortUrl(w.url) : "") })),
            ...(v.title ? [{ value: "none", label: "(none: use the browser's current tab)" }] : [])]} />
          <Button kind="secondary" onClick={load} title="Read the open windows again">Refresh</Button>
        </div>}</Field>
        <Field label={v.source === "browser" ? "Browser title" : "Title"} layout="beside">{(id) =>
          <CommitInput key={v.title ?? ""} id={id} want="String" initial={v.title ?? ""} placeholder="or type it; * and ? are wildcards" onCommit={(t) => put({ title: t.trim() || undefined })} />}</Field>
        <Field label="Title must match" layout="beside">{(id) =>
          <Select id={id} aria-label="Title match" value={v.title_match || "wildcard"} onChange={(e) => put({ title_match: e.target.value })}
            options={[["wildcard", "as a wildcard (* ?)"], ["contains", "contain it"], ["equals", "equal it"], ["regex", "a regular expression"]].map(([m, t]) => ({ value: m, label: t }))} />}</Field>
        {v.title && <label className="st-check-line st-win-case"><input type="checkbox" aria-label="Case sensitive" checked={!!v.case_sensitive}
          onChange={(e) => put({ case_sensitive: e.target.checked || undefined })} /> Case sensitive</label>}
        {v.source === "browser" && <>
          {v.link && <p className="st-win-link" title={v.link}>Page: {v.link}</p>}
          <Field label="Browser" layout="beside">{(id) => <Select id={id} value={v.browser ?? ""} onChange={(e) => put({ browser: e.target.value || undefined })}
            options={[{ value: "", label: "any browser" }, { value: "edge", label: "Edge" }, { value: "chrome", label: "Chrome" }]} />}</Field>
          <Field label="Address must contain" layout="beside">{(id) => <CommitInput id={id} want="String" initial={v.url ?? ""} placeholder="example.com/invoices"
            onCommit={(t) => put({ url: t.trim() || undefined })} />}</Field>
        </>}
      </>}
    </div>
  );
}

/** A step's settings as they would be with one input changed (undefined clears it). */
function withArg<T extends { steps: FlowStep[] }>(flow: T, id: string, name: string, value: unknown): T {
  const f = structuredClone(flow);
  const at = locate(f.steps, id);
  if (!at) return flow;
  const args = { ...(at.step.args ?? {}) };
  if (value === undefined) delete args[name]; else args[name] = value;
  if (Object.keys(args).length) at.step.args = args; else delete at.step.args;
  return f;
}

/**
 * The action list of an element action: actions that suit the captured control first, the rest after. Choosing an
 * action that returns something keeps its result in a new variable at once (as the reference product does).
 */
export function ElementActionSelect({ step, all, id }: { step: FlowStep; all: string[]; id: string }) {
  const vars = useVars();
  if (!vars) return null;
  const desktop = step.action === DESKTOP_ACTION;
  const names = desktop ? DESKTOP_NAMES : WEB_NAMES;
  const groups = actionGroups(step, all);
  const cur = String(step.args?.action ?? "click");
  return (
    <select id={id} className="ui-input ui-select" aria-label={desktop ? "Action to take on the control" : "Action to take on the element"} value={cur}
      onChange={(e) => {
        const a = e.target.value;
        let f = withArg(vars.flow, step.id, "action", a);
        const o = outcomeOf(a);
        if (o && !step.out?.result) {
          const n = uniqueName(f, o.name);
          f = { ...f, variables: [...(f.variables ?? []), { name: n, type: o.type }] };
          const at = locate(f.steps, step.id);
          if (at) at.step.out = { ...(at.step.out ?? {}), result: n };
        }
        vars.edit(f);
      }}>
      {groups.map((g) => g.label
        ? <optgroup key={g.label} label={g.label}>{g.actions.map((a) => <option key={a} value={a}>{names[a] ?? a}</option>)}</optgroup>
        : g.actions.map((a) => <option key={a} value={a}>{names[a] ?? a}</option>))}
    </select>
  );
}

/**
 * Connection at the top of a connector step: a saved connection supplies the address and sign-in, so they are kept
 * in one place; its inputs are then hidden here unless one is set on this step on purpose.
 */
export function ConnectionField({ step, pkgName, pkgId, names, showing, onShow, packages }:
  { step: FlowStep; pkgName: string; pkgId: string; names: string[]; showing: boolean; onShow: (show: boolean) => void; packages: PackageManifest[] }) {
  const vars = useVars();
  const api = vars?.api;
  const [list, setList] = useState<SavedConnection[] | null>(null);
  const [managing, setManaging] = useState(false);
  const load = () => { api?.connections().then(setList, () => setList([])); };
  useEffect(load, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  if (!vars) return null;
  const mine = (list ?? []).filter((c) => c.package === pkgId);
  const set = (name: string) => {
    const f = structuredClone(vars.flow);
    const at = locate(f.steps, step.id);
    if (!at) return;
    if (name) at.step.connection = name; else delete at.step.connection;
    vars.edit(f);
  };
  return (
    <Field label="Connection" layout="beside" info={"A saved connection supplies " + names.join(", ") + ". Secrets stay in the vault; the connection keeps only their references."}>{(id) => <>
      <Select id={id} aria-label="Saved connection" value={step.connection ?? ""} onChange={(e) => set(e.target.value)}
        options={[{ value: "", label: list === null ? "Loading connections…" : "None: fill in the address and sign-in below" },
          ...mine.map((c) => ({ value: c.name, label: c.name })),
          ...(step.connection && !mine.some((c) => c.name === step.connection) ? [{ value: step.connection, label: step.connection + " (not in this workspace)" }] : [])]} />
      <div className="ui-field-hint">{step.connection
        ? <>Comes from the connection: {names.join(", ")}. <button type="button" className="st-link" onClick={() => onShow(!showing)}>{showing ? "Hide these settings" : "Set one of them on this step instead"}</button></>
        : mine.length ? "Or choose a saved connection, so the address and sign-in are kept in one place."
        : "Save the address and sign-in once as a connection and choose it on every " + pkgName + " step."}
        {" "}<button type="button" className="st-link" onClick={() => setManaging(true)}>Manage connections…</button></div>
      {managing && <SavedConnectionsDialog api={vars.api} packages={packages} pkgId={pkgId} onClose={() => setManaging(false)} onChanged={load}
        onNotice={(t) => vars.notice?.(t)} onError={(e) => vars.fail?.(e)} />}
    </>}</Field>
  );
}
