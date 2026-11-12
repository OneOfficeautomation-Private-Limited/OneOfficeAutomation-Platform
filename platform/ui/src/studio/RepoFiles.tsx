import { errorText } from "../api/client";
import { useEffect, useMemo, useState } from "react";
import type { Flow, RepoFile } from "../api/client";
import { Button, Dialog, Icon, TextInput } from "../components";
import { useVars } from "./VariablesPanel";

/**
 * Files kept in One CommandCenter (command review F1, 11 Nov 2026, AA's Control Room files): an agent lists them in its
 * `files`, and the runner gets each at the same path in its workspace before the run, so a step names it by that path.
 */
export function withRepoFile(flow: Flow, path: string): Flow {
  const files = flow.files ?? [];
  return files.includes(path) ? flow : { ...flow, files: [...files, path].sort((a, b) => a.localeCompare(b)) };
}
export function withoutRepoFile(flow: Flow, path: string): Flow {
  const files = (flow.files ?? []).filter((f) => f !== path);
  const next = { ...flow };
  if (files.length) next.files = files; else delete next.files;
  return next;
}

const size = (n?: number) => n === undefined ? "" : n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB";

/** Choose a file kept in One CommandCenter: searchable, grouped by folder; signs in first when needed. */
export function RepoFileBrowser({ onPick, onClose }: { onPick: (path: string) => void; onClose: () => void }) {
  const vars = useVars();
  const [files, setFiles] = useState<RepoFile[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  useEffect(() => {
    vars?.api.crFiles().then(setFiles, (e) => setError((errorText(e)) + " Sign in to One CommandCenter on the Automation page's Public tab, then try again."));
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps
  const shown = useMemo(() => (files ?? []).filter((f) => !q.trim() || f.path.toLowerCase().includes(q.trim().toLowerCase())), [files, q]);
  return (
    <Dialog open title="Choose a file kept in One CommandCenter" width={640} onClose={onClose} footer={<Button kind="plain" onClick={onClose}>Cancel</Button>}>
      <p className="st-muted">The agent takes the file with it: the runner gets it at the same path before the run.</p>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <TextInput aria-label="Search the files" type="search" placeholder="Search by name or folder" value={q} onChange={(e) => setQ(e.target.value)} />
      <ul className="st-fs-list" role="listbox" aria-label="Files in One CommandCenter">
        {shown.map((f) => <li key={f.path}>
          <button type="button" role="option" aria-selected="false" className="st-fs-item" onClick={() => onPick(f.path)} title={f.path}>
            <Icon name="doc" size={14} />{f.path}<span className="st-muted st-fs-size">{size(f.size)}</span></button></li>)}
        {files && !shown.length && <li className="st-muted">{files.length ? "Nothing matches." : "No files are kept in One CommandCenter yet. Add them on its Automation page (Files)."}</li>}
        {!files && !error && <li className="st-muted">Reading the files…</li>}
      </ul>
    </Dialog>
  );
}

/** The agent's files from One CommandCenter, in its Details: each with Remove, and Add. */
export function AgentRepoFiles({ flow, onEdit }: { flow: Flow; onEdit: (next: Flow) => void }) {
  const [adding, setAdding] = useState(false);
  const files = flow.files ?? [];
  return <>
    <h3 className="st-prop-section">Files from One CommandCenter</h3>
    <p className="ui-field-hint">Sent to the runner with the agent, at the same path. Steps name them by that path.</p>
    <ul className="st-repo-files" aria-label="Files from One CommandCenter">
      {files.map((f) => <li key={f}><span className="ui-mono">{f}</span>
        <Button kind="plain" aria-label={"Remove " + f} onClick={() => onEdit(withoutRepoFile(flow, f))}>Remove</Button></li>)}
      {!files.length && <li className="st-muted">None.</li>}
    </ul>
    <Button kind="secondary" icon="plus" onClick={() => setAdding(true)}>Add a file</Button>
    {adding && <RepoFileBrowser onClose={() => setAdding(false)} onPick={(p) => { setAdding(false); onEdit(withRepoFile(flow, p)); }} />}
  </>;
}
