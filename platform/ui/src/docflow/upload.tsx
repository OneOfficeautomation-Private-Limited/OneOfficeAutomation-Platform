import { useState } from "react";
import { Button, Icon } from "../components";
import type { DocFlowApi, DocumentRow } from "./api";
import { folderLabel } from "./dialogs";
import { Loading, StatusChip, reportError, useLoad } from "./ui";

const ACCEPT = ".pdf,.png,.jpg,.jpeg,.tif,.tiff";

/**
 * Upload into one process: drop files on the box or click to choose them, check the list, then Start processing.
 * Used on the process page and in the builder's step 2. The One Inbox recognises each document and sends it on.
 * inputId: the hidden file input, so a button elsewhere on the page can open the same picker.
 */
export function UploadFiles({ api, process, inbox = false, inputId, onAdded }:
  { api: DocFlowApi; process: { id: string; name: string; folder: string }; inbox?: boolean; inputId: string; onAdded: () => void }) {
  const [files, setFiles] = useState<File[]>([]);
  const [over, setOver] = useState(false);
  const [busy, setBusy] = useState(false);
  const add = (more: File[]) => setFiles([...files, ...more.filter((f) => !files.some((x) => x.name === f.name))]);
  const start = async () => {
    setBusy(true);
    try { await api.addDocuments(files, process.id, process.folder); setFiles([]); onAdded(); } catch (e) { reportError(e); } finally { setBusy(false); }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <input id={inputId} type="file" multiple hidden accept={ACCEPT} aria-label={"Choose files for " + process.name}
        onChange={(e) => { add(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
      <button type="button" className={`df-drop${over ? " is-over" : ""}`} onClick={() => document.getElementById(inputId)?.click()}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); add(Array.from(e.dataTransfer.files)); }}>
        <span><b>Drop files here</b> or click to choose. {inbox ? "Each document is recognised and sent to its process." : `They go to ${process.name}.`}</span>
      </button>
      {files.length > 0 && (
        <div className="df-files" aria-label="Files to add">
          {files.map((f) => (
            <div key={f.name}><Icon name="doc" /><b>{f.name}</b><span className="df-grow" />
              <button type="button" className="df-link" aria-label={"Remove " + f.name} onClick={() => setFiles(files.filter((x) => x !== f))}><Icon name="close" size={14} /></button></div>
          ))}
          <div>
            <span className="df-small">{process.folder ? `Filed in ${folderLabel(process.folder)}` : "Filed with the process"}</span><span className="df-grow" />
            <Button onClick={() => setFiles([])}>Cancel</Button>
            <Button kind="primary" disabled={busy} onClick={start}>Start processing</Button>
          </div>
        </div>
      )}
      <span className="df-small">PDF, PNG, JPG or TIFF, up to 500 pages each.</span>
    </div>
  );
}

/** Where a document opens: the review when it waits for one, else its process. */
const linkOf = (d: DocumentRow) => (d.status === "review" ? "#/review/" + encodeURIComponent(d.id) : "#/process/" + encodeURIComponent(d.processId));

/** The last few documents of a process, with their status, each linking to its review or the process. */
export function RecentUploads({ api, processId, n, max = 5 }: { api: DocFlowApi; processId: string; n: number; max?: number }) {
  const [docs] = useLoad(() => api.documents(processId), [api, processId, n]);
  if (!docs) return <Loading />;
  const last = docs.slice(0, max);
  return (
    <section aria-label="Last uploads" style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      <b>Last documents</b>
      {!last.length ? <span className="df-muted">No documents yet.</span> : (
        <table className="df-table">
          <tbody>
            {last.map((d) => (
              <tr key={d.id}>
                <td><a href={linkOf(d)}>{d.name}</a><div className="df-small">{d.sourceLabel}</div></td>
                <td className="df-num df-muted" style={{ whiteSpace: "nowrap" }}>{d.time}</td>
                <td className="df-right"><StatusChip status={d.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
