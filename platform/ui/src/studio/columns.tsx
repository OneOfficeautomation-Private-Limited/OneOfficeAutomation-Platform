import type { RepoFacts } from "../api/client";
import { Button, Dialog } from "../components";

/**
 * The columns of Automation's Private and Public lists, as AA's (Type, Name, Status, Platform, Version, Label, Code
 * analysis, Size, Last modified, Modified by; issue 10, 1 Nov 2026). Some start hidden so a row stays on one line at
 * 1366 px; Columns shows or hides them, remembered per list.
 */
export type Column = { key: string; label: string };

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
