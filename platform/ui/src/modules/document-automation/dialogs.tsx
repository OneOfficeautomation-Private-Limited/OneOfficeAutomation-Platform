import { useEffect, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import type { DocFlowApi, DocType } from "./api";
import { HINTS } from "./help";
import { Hint } from "./ui";

/** A platform folder path as people read it: Finance/Accounts payable is "Finance / Accounts payable". */
export const folderLabel = (f: string) => f.split("/").map((x) => x.trim()).filter(Boolean).join(" / ");

/** New process: name, document type, folder, and how documents come in (upload always; watched folder, email, bot). */
export function NewProcessDialog({ api, open, onClose, onCreated }:
  { api: DocFlowApi; open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState("");
  const [types, setTypes] = useState<DocType[]>([]);
  const [docType, setDocType] = useState("");
  useEffect(() => { if (open) void api.docTypes().then((t) => { setTypes(t); setDocType((cur) => cur || t[0]?.id || ""); }); }, [api, open]);
  const [folders, setFolders] = useState<string[]>([]);
  const [folder, setFolder] = useState("");
  useEffect(() => { if (open) api.folders().then((f) => { setFolders(f); setFolder((cur) => cur || f[0] || ""); }, () => setFolders([])); }, [api, open]);
  const [watch, setWatch] = useState(false);
  const [path, setPath] = useState("");
  const [every, setEvery] = useState("30");
  const [mail, setMail] = useState(false);
  const [bot, setBot] = useState(false);
  const valid = name.trim() && docType && (!watch || path.trim());

  const create = async () => {
    const r = await api.createProcess({ name: name.trim(), docType, folder, watchedFolder: watch ? path.trim() : undefined,
      pollSeconds: Number(every), email: mail, fromBot: bot });
    setName(""); setWatch(false); setMail(false); setBot(false); setPath("");
    onCreated(r.id + (mail ? "/intake" : ""));
  };

  return (
    <Dialog open={open} onClose={onClose} title="New process" width={720}
      footer={<>
        <span className="df-muted" style={{ marginRight: "auto" }}>Next: checks, review and delivery in the process builder</span>
        <Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!valid} onClick={create}>Create process</Button>
      </>}>
      <div className="df-grid df-g2">
        <Field label="Process name" info={HINTS.processName}>{(id) => <TextInput id={id} value={name} placeholder="e.g. Supplier invoices KSA" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Document type" hint="The fields to read and the checks to run.">
          {(id) => <Select id={id} value={docType} onChange={(e) => setDocType(e.target.value)} options={types.map((d) => ({ value: d.id, label: d.name }))} />}
        </Field>
      </div>
      <Field label="Folder" hint="A platform folder: only people with access to it see this process and its documents (view to see, run to add documents, edit to change the process).">
        {(id) => <Select id={id} value={folder} onChange={(e) => setFolder(e.target.value)}
          options={[...folders.map((f) => ({ value: f, label: folderLabel(f) })), { value: "", label: "No folder: everyone with Document Automation access" }]} />}
      </Field>

      <fieldset style={{ border: 0, margin: 0, padding: 0, display: "flex", flexDirection: "column", gap: 8 }}>
        <legend style={{ fontWeight: 700, padding: 0, marginBottom: 8 }}>How documents come in</legend>
        <div className="df-way" style={{ background: "var(--ui-soft)" }}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked disabled /><span><b>Upload</b>
            <span className="df-small" style={{ display: "block" }}>On the process page and in its step 2: drop files or choose them. Always on.</span></span></label>
        </div>
        <div className={`df-way${watch ? " is-on" : ""}`}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked={watch} onChange={(e) => setWatch(e.target.checked)} /><span><b>Watched folder</b><Hint about="Watched folder" text={HINTS.watchedFolder} />
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
          {mail && <p className="df-small" style={{ margin: "8px 0 0 24px" }}>Next, in the process's step 2, Document ingestion: the mailbox (Microsoft 365 or IMAP) and its password from the Vault. Only new emails are read.</p>}
        </div>
        <div className={`df-way${bot ? " is-on" : ""}`}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked={bot} onChange={(e) => setBot(e.target.checked)} /><span><b>From a bot</b>
            <span className="df-small" style={{ display: "block" }}>Agents use the "Send document to process" action (Document Automation) in Studio</span></span></label>
        </div>
      </fieldset>
    </Dialog>
  );
}
