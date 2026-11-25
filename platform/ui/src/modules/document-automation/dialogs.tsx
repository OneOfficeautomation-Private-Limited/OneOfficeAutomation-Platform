import { useEffect, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import { ANY_TYPE, type DocFlowApi, type DocType, type TestRun } from "./api";
import { TEMPLATES, sampleFile, typeFor } from "./templates";
import { FolderField } from "./folderPicker";
import { HINTS } from "./help";
import { ChoiceOrOwn, Hint, duration, reportError } from "./ui";
import { POLL_SECONDS } from "./stages";

/** A platform folder path as people read it: Finance/Accounts payable is "Finance / Accounts payable". */
export const folderLabel = (f: string) => f.split("/").map((x) => x.trim()).filter(Boolean).join(" / ");

/** New process: name, document type, folder, and how documents come in (upload always; watched folder, email, bot). */
export function NewProcessDialog({ api, open, onClose, onCreated }:
  { api: DocFlowApi; open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState("");
  const [types, setTypes] = useState<DocType[]>([]);
  const [docType, setDocType] = useState("");
  // DA-1: a template picks the document type and a name; "" starts from scratch
  const [template, setTemplate] = useState(TEMPLATES[0].id);
  const [tried, setTried] = useState<TestRun | null>(null);
  const [trying, setTrying] = useState(false);
  const chosen = TEMPLATES.find((t) => t.id === template) ?? null;
  const pick = (id: string, all = types) => {
    const t = TEMPLATES.find((x) => x.id === id) ?? null;
    setTemplate(id); setTried(null);
    if (t) { const d = typeFor(t, all); if (d) setDocType(d); setName((cur) => !cur || TEMPLATES.some((x) => x.suggestedName === cur) ? t.suggestedName : cur); }
  };
  useEffect(() => { if (open) void api.docTypes().then((t) => {
    setTypes(t);
    const d = chosen ? typeFor(chosen, t) : "";
    setDocType((cur) => cur || d || ANY_TYPE);   // no type for the template: the process recognises each document
    if (chosen) setName((cur) => cur || chosen.suggestedName);
  }); }, [api, open]);   // eslint-disable-line react-hooks/exhaustive-deps
  const trySample = async () => {
    if (!chosen) return;
    setTrying(true);
    try { setTried(await api.runTest(await sampleFile(chosen), docType, "auto", null)); } catch (e) { reportError(e); } finally { setTrying(false); }
  };
  const [folders, setFolders] = useState<string[]>([]);
  const [folder, setFolder] = useState("");
  useEffect(() => { if (open) api.folders().then((f) => { setFolders(f); setFolder((cur) => cur || f[0] || ""); }, () => setFolders([])); }, [api, open]);
  const [watch, setWatch] = useState(false);
  const [path, setPath] = useState("");
  const [every, setEvery] = useState("30");
  const [mail, setMail] = useState(false);
  const [upload, setUpload] = useState(true);
  const [bot, setBot] = useState(false);
  const valid = name.trim() && docType && (!watch || path.trim());

  const create = async () => {
    const r = await api.createProcess({ name: name.trim(), docType, folder, watchedFolder: watch ? path.trim() : undefined,
      pollSeconds: Number(every), email: mail, fromBot: bot, upload });
    setName(""); setWatch(false); setMail(false); setBot(false); setPath("");
    onCreated(r.id + (mail ? "/intake" : ""));
  };

  return (
    <Dialog open={open} onClose={onClose} title="New process" width={720}
      footer={<>
        {chosen ? <Button disabled={trying || !docType || docType === ANY_TYPE} onClick={trySample}>{trying ? "Reading the sample…" : `Try it with a sample ${chosen.sampleName}`}</Button>
          : <span className="df-muted">Next: checks, review and delivery in the process builder</span>}
        <span style={{ marginRight: "auto" }} />
        <Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!valid} onClick={create}>Create process</Button>
      </>}>
      <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
        <legend style={{ fontWeight: 700, padding: 0, marginBottom: 8 }}>Start from</legend>
        <div className="df-grid df-g3" style={{ gap: 10 }}>
          {[...TEMPLATES.map((t) => ({ id: t.id, name: t.name, does: t.does })), { id: "", name: "Start from scratch", does: "An empty process: choose each step yourself." }].map((t) => (
            <button key={t.id || "scratch"} type="button" className={`df-way${template === t.id ? " is-on" : ""}`} aria-pressed={template === t.id}
              style={{ textAlign: "left", font: "inherit", color: "inherit", cursor: "pointer" }} onClick={() => pick(t.id)}>
              <b>{t.name}</b><span className="df-small" style={{ display: "block" }}>{t.does}</span>
            </button>))}
        </div>
      </fieldset>
      {tried && (
        <section className="df-card df-pad" aria-label="What the sample gave" style={{ background: "var(--ui-soft)" }}>
          <b>What the sample gave</b> <span className="df-small">(a made-up document, read now; nothing was saved)</span>
          {tried.runs[0]?.error ? <p className="df-text-warn" style={{ margin: "6px 0 0" }}>{tried.runs[0].error}</p> : (
            <table className="df-table" style={{ marginTop: 6 }}><tbody>
              {tried.rows.filter((r) => r.a !== "—").slice(0, 8).map((r) => <tr key={r.field}><td>{r.field}</td><td><b>{r.a}</b></td><td className="df-small">{r.aConf}</td></tr>)}
            </tbody></table>)}
        </section>
      )}
      <div className="df-grid df-g2">
        <Field label="Process name" info={HINTS.processName}>{(id) => <TextInput id={id} value={name} placeholder="e.g. Supplier invoices" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Document type" hint={docType === ANY_TYPE ? "Each document is recognised as one of the types; one that fits none goes to review." : "The fields to read and the checks to run. Not listed? Create the process, then make a new type in step 3."}>
          {(id) => <Select id={id} value={docType} onChange={(e) => setDocType(e.target.value)}
            options={[{ value: ANY_TYPE, label: "Any type: recognise each document" }, ...types.map((d) => ({ value: d.id, label: d.name }))]} />}
        </Field>
      </div>
      <Field label="Folder" hint="A platform folder: only people with access to it see this process and its documents (view to see, run to add documents, edit to change the process).">
        {(id) => <Select id={id} value={folder} onChange={(e) => setFolder(e.target.value)}
          options={[...folders.map((f) => ({ value: f, label: folderLabel(f) })), { value: "", label: "No folder: everyone with Document Automation access" }]} />}
      </Field>

      <fieldset style={{ border: 0, margin: 0, padding: 0, display: "flex", flexDirection: "column", gap: 8 }}>
        <legend style={{ fontWeight: 700, padding: 0, marginBottom: 8 }}>How documents come in</legend>
        <div className="df-way" style={{ background: "var(--ui-soft)" }}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked={upload} onChange={(e) => setUpload(e.target.checked)} /><span><b>Upload</b>
            <span className="df-small" style={{ display: "block" }}>On the process page and in its step 2: drop files or choose them.</span></span></label>
        </div>
        <div className={`df-way${watch ? " is-on" : ""}`}>
          <label style={{ display: "flex", gap: 10 }}><input type="checkbox" checked={watch} onChange={(e) => setWatch(e.target.checked)} /><span><b>Watched folder</b><Hint about="Watched folder" text={HINTS.watchedFolder} />
            <span className="df-small" style={{ display: "block" }}>A scanner or shared drive saves files here</span></span></label>
          {watch && (
            <div className="df-grid" style={{ gridTemplateColumns: "3fr 1fr", gap: 12, margin: "10px 0 0 24px" }}>
              <FolderField api={api} label="Folder path" title="Choose the watched folder" value={path} placeholder="\\scan01\invoices" onChange={setPath} />
              <Field label="Check every">{(id) => <ChoiceOrOwn id={id} value={Number(every)} onChange={(v) => setEvery(String(Math.round(v)))} options={POLL_SECONDS} label={duration} unit="seconds" min={5} />}</Field>
              <span className="df-small" style={{ gridColumn: "1 / -1" }}>Each file read moves to the folder's processed folder, so nothing is read twice.</span>
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
