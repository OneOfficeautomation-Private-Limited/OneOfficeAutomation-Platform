import type { RepoFacts } from "../api/client";
import { Button, Dialog } from "../components";

/**
 * The columns of Automation's Private and Public lists, as AA's (Type, Name, Status, Platform, Version, Label, Code
 * analysis, Size, Last modified, Modified by; issue 10, 1 Nov 2026). Some start hidden so a row stays on one line at
 * 1366 px; Columns shows or hides them, remembered per list.
 */
export type Column = { key: string; label: string };

/**
 * The Public list's columns after the name, the same in One CommandCenter and in the Private workspace (as AA's one
 * Automation page, 2 Nov 2026); a search, or subfolders included, adds the folder. Both apps keep one choice of columns.
 */
export const PUBLIC_COLUMNS: Column[] = [{ key: "type", label: "Type" }, { key: "platform", label: "Platform" }, { key: "latest", label: "Version" },
  { key: "live", label: "Live" }, { key: "label", label: "Label" }, { key: "analysis", label: "Code analysis" }, { key: "size", label: "Size" },
  { key: "out", label: "Checked out" }, { key: "changed", label: "Last modified" }, { key: "by", label: "Modified by" }];
export const PUBLIC_HIDDEN_KEY = "automation.public.columns.hidden";
export const PUBLIC_HIDDEN = ["platform", "label", "analysis", "size", "by"];
/** A time in a list, short so rows stay on one line at 1366 px: "Nov 1, 8:30 AM", with the year only when it is not this one. */
export const listTime = (t?: string | null) => {
  if (!t) return "";
  const d = new Date(t);
  return d.toLocaleString(undefined, { ...(d.getFullYear() !== new Date().getFullYear() ? { year: "numeric" as const } : {}), month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
};
/** An item of the agent repository's type, from its file name. */
export const typeOf = (path: string) => /\.process\.json$/.test(path) ? "Workflow" : /\.form\.json$/.test(path) ? "Form" : "Agent";

/** A size as people read it: bytes under 1 KB, then KB, then MB. */
export function sizeText(bytes?: number) {
  if (bytes === undefined) return "";
  return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? Math.round(bytes / 1024) + " KB" : (bytes / 1024 / 1024).toFixed(1) + " MB";
}

/** The version labels, such as "UAT v2". */
export function labelText(labels?: RepoFacts["labels"]) { return (labels ?? []).map((l) => l.label + " v" + l.version).join(", "); }

/** Code analysis of the latest version: passed, or how many errors and warnings, as a pill. */
export function Analysis({ found }: { found?: RepoFacts["analysis"] }) {
  if (!found) return null;
  if (!found.errors && !found.warnings) return <span className="st-pill st-status is-checked_out">Passed</span>;
  const words = [found.errors ? found.errors + " error" + (found.errors === 1 ? "" : "s") : "", found.warnings ? found.warnings + " warning" + (found.warnings === 1 ? "" : "s") : ""]
    .filter(Boolean).join(" · ");
  return <span className={"st-pill st-status " + (found.errors ? "is-error" : "is-edited")} title="Code analysis of the latest version, against One CommandCenter's policy">{words}</span>;
}

/** Columns: a tick per column; the first ones (the name) always show. */
export function ColumnsDialog({ columns, hidden, onChange, onClose }:
  { columns: Column[]; hidden: string[]; onChange: (hidden: string[]) => void; onClose: () => void }) {
  return (
    <Dialog open title="Columns" width={340} onClose={onClose} footer={<Button kind="primary" onClick={onClose}>Done</Button>}>
      <div className="st-cols" role="group" aria-label="Columns shown">
        {columns.map((c) => <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden.includes(c.key)}
          onChange={(e) => onChange(e.target.checked ? hidden.filter((k) => k !== c.key) : [...hidden, c.key])} /> {c.label}</label>)}
      </div>
    </Dialog>
  );
}
