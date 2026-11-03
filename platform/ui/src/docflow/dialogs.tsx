import { useEffect, useRef, useState } from "react";
import { Button, Dialog, Field, Icon, Select, TextInput } from "../components";
import type { DocFlowApi, DocType, ProcessSummary } from "./api";

const FOLDERS = ["Finance / Accounts payable", "Finance / Expenses", "Operations / Goods received", "Shared / Unsorted"];

/**
 * Add documents: the files, then which process reads them (or recognise automatically, or a new process), then which
 * folder they are filed in (the process's own by default).
 */
export function AddDocumentsDialog({ api, open, onClose, processes, initialProcess, onAdded, onNewProcess }:
  { api: DocFlowApi; open: boolean; onClose: () => void; processes: ProcessSummary[]; initialProcess?: string;
    onAdded: (processId: string) => void; onNewProcess: () => void }) {
  const picker = useRef<HTMLInputElement>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [mode, setMode] = useState<"auto" | "process">(initialProcess ? "process" : "auto");
  const real = processes.filter((p) => p.id !== "smart-inbox");
  const [processId, setProcessId] = useState(initialProcess ?? real[0]?.id ?? "");
  const [useDefault, setUseDefault] = useState(true);
  const chosen = processes.find((p) => p.id === processId);
  const [folder, setFolder] = useState(FOLDERS[0]);
  const shownFolder = mode === "process" && useDefault && chosen ? chosen.folder : folder;
  const [busy, setBusy] = useState(false);

  const start = async () => {
    setBusy(true);
    try {
      const r = await api.addDocuments(files, mode === "auto" ? "auto" : processId, shownFolder);
      setFiles([]);
      onAdded(r.processId);
    } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onClose={onClose} title="Add documents" width={640}
      footer={<>
        <span className="df-muted" style={{ marginRight: "auto" }}>{files.length ? `${files.length} file${files.length > 1 ? "s" : ""}` : "No files yet"}</span>
        <Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!files.length || busy || (mode === "process" && !processId)} onClick={start}>Start processing</Button>
      </>}>
      <div>
        <b style={{ display: "block", marginBottom: 8 }}>1. Files</b>
        <div className="df-files">
          {files.map((f) => (
            <div key={f.name}><Icon name="doc" /><b>{f.name}</b><span className="df-grow" />
              <button type="button" className="df-link" aria-label={"Remove " + f.name} onClick={() => setFiles(files.filter((x) => x !== f))}><Icon name="close" size={14} /></button></div>
          ))}
          <div>
            <input ref={picker} type="file" multiple hidden accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff" aria-label="Choose files"
              onChange={(e) => setFiles([...files, ...Array.from(e.target.files ?? []).filter((f) => !files.some((x) => x.name === f.name))])} />
            <button type="button" className="df-link" onClick={() => picker.current?.click()}>+ Choose files</button>
            <span className="df-small">PDF, PNG, JPG or TIFF, up to 500 pages each</span>
          </div>
        </div>
      </div>

      <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
        <legend style={{ fontWeight: 700, padding: 0, marginBottom: 8 }}>2. Which process should read them?</legend>
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <label className={`df-radio-card${mode === "auto" ? " is-on" : ""}`}>
            <input type="radio" name="df-proc" checked={mode === "auto"} onChange={() => setMode("auto")} />
            <span><b>Recognise automatically</b><span className="df-muted" style={{ display: "block" }}>
              DocFlow identifies each document and sends it to the matching process. Anything it cannot recognise waits in Smart inbox.</span></span>
          </label>
          <label className={`df-radio-card${mode === "process" ? " is-on" : ""}`}>
            <input type="radio" name="df-proc" checked={mode === "process"} onChange={() => setMode("process")} />
            <span style={{ flex: 1 }}><b>Use a process</b><span className="df-muted" style={{ display: "block" }}>Every file is read as this process's document type.</span>
              <select className="ui-input ui-select" aria-label="Process" style={{ marginTop: 8 }} value={processId} disabled={mode !== "process"}
                onChange={(e) => { if (e.target.value === "__new") onNewProcess(); else setProcessId(e.target.value); }}>
                {real.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.docType}</option>)}
                <option value="__new">+ Create a new process…</option>
              </select>
            </span>
          </label>
        </div>
      </fieldset>

      <div>
        <b style={{ display: "block", marginBottom: 8 }}>3. Which folder should they be filed in?</b>
        <Field label="Folder" hint="The original files and the extracted data are kept here. People with access to this folder can open them.">
          {(id) => (
            <select id={id} className="ui-input ui-select" value={shownFolder} disabled={mode === "process" && useDefault} onChange={(e) => setFolder(e.target.value)}>
              {[...new Set([shownFolder, ...FOLDERS])].map((f) => <option key={f}>{f}</option>)}
            </select>
          )}
        </Field>
        {mode === "process" && (
          <label style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 8 }}>
            <input type="checkbox" checked={useDefault} onChange={(e) => setUseDefault(e.target.checked)} />Use the process's default folder
          </label>
        )}
      </div>
    </Dialog>
  );
}

/** New process: name, document type, folder, and how documents come in (upload always; watched folder, email, bot). */
export function NewProcessDialog({ api, open, onClose, onCreated }:
  { api: DocFlowApi; open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState("");
  const [types, setTypes] = useState<DocType[]>([]);
  const [docType, setDocType] = useState("");
  useEffect(() => { if (open) void api.docTypes().then((t) => { setTypes(t); setDocType((cur) => cur || t[0]?.id || ""); }); }, [api, open]);
  const [folder, setFolder] = useState(FOLDERS[0]);
  const [watch, setWatch] = useState(false);
  const [path, setPath] = useState("");
  const [every, setEvery] = useState("30");
  const [mail, setMail] = useState(false);
  const [mailbox, setMailbox] = useState("invoices@acme-trading.com");
  const [bot, setBot] = useState(false);
  const valid = name.trim() && docType && (!watch || path.trim());

  const create = async () => {
    const r = await api.createProcess({ name: name.trim(), docType, folder, watchedFolder: watch ? path.trim() : undefined,
      pollSeconds: Number(every), mailbox: mail ? mailbox : undefined, fromBot: bot });
    setName(""); setWatch(false); setMail(false); setBot(false); setPath("");
    onCreated(r.id);
  };

  return (
    <Dialog open={open} onClose={onClose} title="New process" width={720}
      footer={<>
        <span className="df-muted" style={{ marginRight: "auto" }}>Next: checks, review and delivery in the process builder</span>
        <Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!valid} onClick={create}>Create process</Button>
      </>}>
      <div className="df-grid df-g2">
        <Field label="Process name">{(id) => <TextInput id={id} value={name} placeholder="e.g. Supplier invoices KSA" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Document type" hint="The fields to read and the checks to run.">
          {(id) => <Select id={id} value={docType} onChange={(e) => setDocType(e.target.value)} options={types.map((d) => ({ value: d.id, label: d.name }))} />}
        </Field>
      </div>
      <Field label="Folder" hint="Its documents and results are filed here, and only people with access to this folder see them.">
        {(id) => <Select id={id} value={folder} onChange={(e) => setFolder(e.target.value)} options={FOLDERS.map((f) => ({ value: f, label: f }))} />}
      </Field>

      <fieldset style={{ border: 0, margin: 0, padding: 0, display: "flex", flexDirection: "column", gap: 8 }}>
        <legend style={{ fontWeight: 700, padding: 0, marginBottom: 8 }}>How documents come in</legend>
        <div className="df-way" style={{ background: "var(--ui-soft)" }}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked disabled /><span><b>Upload</b>
            <span className="df-small" style={{ display: "block" }}>Add documents button and drag and drop. Always on.</span></span></label>
        </div>
        <div className={`df-way${watch ? " is-on" : ""}`}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked={watch} onChange={(e) => setWatch(e.target.checked)} /><span><b>Watched folder</b>
            <span className="df-small" style={{ display: "block" }}>A scanner or shared drive saves files here</span></span></label>
          {watch && (
            <div className="df-grid" style={{ gridTemplateColumns: "3fr 1fr", gap: 12, margin: "10px 0 0 24px" }}>
              <Field label="Folder path">{(id) => <TextInput id={id} value={path} placeholder="\\scan01\invoices" onChange={(e) => setPath(e.target.value)} />}</Field>
              <Field label="Check every">{(id) => <Select id={id} value={every} onChange={(e) => setEvery(e.target.value)}
                options={[{ value: "30", label: "30 seconds" }, { value: "60", label: "1 minute" }, { value: "300", label: "5 minutes" }]} />}</Field>
              <span className="df-small" style={{ gridColumn: "1 / -1" }}>Files are moved to the process folder after reading, so nothing is read twice.</span>
            </div>
          )}
        </div>
        <div className={`df-way${mail ? " is-on" : ""}`}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked={mail} onChange={(e) => setMail(e.target.checked)} /><span><b>Email inbox</b>
            <span className="df-small" style={{ display: "block" }}>Suppliers email their invoices</span></span></label>
          {mail && (
            <div style={{ margin: "10px 0 0 24px" }}>
              <Field label="Mailbox" hint="The mailbox sign-in is kept in the Vault. Only new emails are read.">
                {(id) => <Select id={id} value={mailbox} onChange={(e) => setMailbox(e.target.value)}
                  options={[{ value: "invoices@acme-trading.com", label: "invoices@acme-trading.com (Vault)" }]} />}
              </Field>
            </div>
          )}
        </div>
        <div className={`df-way${bot ? " is-on" : ""}`}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked={bot} onChange={(e) => setBot(e.target.checked)} /><span><b>From a bot</b>
            <span className="df-small" style={{ display: "block" }}>Agents use the "Send document to process" action in Studio</span></span></label>
        </div>
      </fieldset>
    </Dialog>
  );
}
