import { useEffect, useMemo, useState } from "react";
import { Button, Dialog, Field, Icon, PickerDialog, Select, TextInput, type TreeNode } from "../../components";
import { ANY_TYPE, type DocFlowApi, type DocType, type MailAccountBrief, type TestRun } from "./api";
import { TEMPLATES, sampleFile, typeFor } from "./templates";
import { FolderField } from "./folderPicker";
import { HINTS } from "./help";
import { ChoiceOrOwn, Hint, duration, reportError } from "./ui";
import { POLL_SECONDS } from "./stages";

/** A platform folder path as people read it: Finance/Accounts payable is "Finance / Accounts payable". */
export const folderLabel = (f: string) => f.split("/").map((x) => x.trim()).filter(Boolean).join(" / ");
/** The same path with arrows, as the folder pickers show where something is: Finance › Accounts payable. */
const folderPath = (f: string) => f.split("/").map((x) => x.trim()).filter(Boolean).join(" › ");

/** "No folder": everyone with Document Automation access sees the process. */
const NO_FOLDER = "";
const SCRATCH = "scratch";

/** The ways documents come in (DA-5, 27 Nov 2026): added one by one from "Add a way in", each with only the inputs it needs. */
type Way = "upload" | "folder" | "email" | "bot";
const WAYS: { id: Way; label: string; about: string }[] = [
  { id: "upload", label: "Upload", about: "People drop files or choose them, on the process page and in its step 2." },
  { id: "folder", label: "Watched folder", about: "A scanner or shared drive saves files in a folder; each new file is read." },
  { id: "email", label: "Email", about: "Emails that arrive in a mail account; their PDF and picture attachments are read." },
  { id: "bot", label: "From an agent", about: "Agents send documents with the \"Send document to process\" action." },
];

/**
 * New process (DA-2 to DA-5, 27 Nov 2026): Start from is a list (the chosen one's details under it), the name starts empty,
 * the folder is chosen in a folder tree, and the ways documents come in are added one at a time, each with its own inputs.
 */
export function NewProcessDialog({ api, open, onClose, onCreated }:
  { api: DocFlowApi; open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState("");
  const [types, setTypes] = useState<DocType[]>([]);
  const [docType, setDocType] = useState("");
  // a template picks the document type; "scratch" starts empty. The name is always the person's own (DA-3).
  const [template, setTemplate] = useState(TEMPLATES[0].id);
  const [tried, setTried] = useState<TestRun | null>(null);
  const [trying, setTrying] = useState(false);
  const chosen = TEMPLATES.find((t) => t.id === template) ?? null;
  const pick = (id: string, all = types) => {
    const t = TEMPLATES.find((x) => x.id === id) ?? null;
    setTemplate(id); setTried(null);
    if (t) { const d = typeFor(t, all); if (d) setDocType(d); }
  };
  useEffect(() => { if (open) void api.docTypes().then((t) => {
    setTypes(t);
    const d = chosen ? typeFor(chosen, t) : "";
    setDocType((cur) => cur || d || ANY_TYPE);   // no type for the template: the process recognises each document
  }); }, [api, open]);   // eslint-disable-line react-hooks/exhaustive-deps
  const trySample = async () => {
    if (!chosen) return;
    setTrying(true);
    try { setTried(await api.runTest(await sampleFile(chosen), docType, "auto", null)); } catch (e) { reportError(e); } finally { setTrying(false); }
  };
  const [folders, setFolders] = useState<string[]>([]);
  const [folder, setFolder] = useState(NO_FOLDER);
  useEffect(() => { if (open) api.folders().then((f) => { setFolders(f); setFolder((cur) => cur || f[0] || NO_FOLDER); }, () => setFolders([])); }, [api, open]);
  const [ways, setWays] = useState<Way[]>(["upload"]);
  const [path, setPath] = useState("");
  const [every, setEvery] = useState("30");
  const [accounts, setAccounts] = useState<MailAccountBrief[] | null>(null);
  const [account, setAccount] = useState("");
  useEffect(() => { if (open) api.mailAccounts().then((a) => setAccounts(a.filter((x) => x.can_use)), () => setAccounts([])); }, [api, open]);
  const has = (w: Way) => ways.includes(w);
  const valid = name.trim() && docType && (!has("folder") || path.trim());

  const create = async () => {
    const r = await api.createProcess({ name: name.trim(), docType, folder, upload: has("upload"), watchedFolder: has("folder") ? path.trim() : undefined,
      pollSeconds: Number(every), email: has("email") && !account, fromBot: has("bot") });
    // a mail account chosen here: its emails come in to the new process (every email; narrow it later in step 2)
    if (has("email") && account) await api.saveProcessMailRule(account, null, { name: "Every email", conditions: { match: "all", when: [], except: [] }, target: r.id }).catch(reportError);
    setName(""); setWays(["upload"]); setPath(""); setAccount("");
    onCreated(r.id + (has("email") ? "/intake" : ""));
  };

  const templateDocType = docType === ANY_TYPE ? "Any type: each document is recognised" : types.find((t) => t.id === docType)?.name ?? docType;
  return (
    <Dialog open={open} onClose={onClose} title="New process" width={720}
      footer={<>
        <span className="df-muted">Next: checks, review and delivery in the process builder</span>
        <span style={{ marginRight: "auto" }} />
        <Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!valid} onClick={create}>Create process</Button>
      </>}>
      {/* DA-2: one list of names; the chosen one's details under it */}
      <div className="df-grid df-g2">
        <Field label="Start from">{(id) => <Select id={id} value={template || SCRATCH} onChange={(e) => pick(e.target.value === SCRATCH ? "" : e.target.value)}
          options={[...TEMPLATES.map((t) => ({ value: t.id, label: t.name })), { value: SCRATCH, label: "Start from scratch" }]} />}</Field>
        <Field label="Process name" info={HINTS.processName}>{(id) => <TextInput id={id} value={name} placeholder="e.g. Supplier invoices" required
          onChange={(e) => setName(e.target.value)} />}</Field>
      </div>
      <section className="df-start-about" aria-label="About this start">
        {chosen ? <>
          <p>{chosen.does}</p>
          <p className="df-small">Document type: <b>{templateDocType}</b></p>
          <Button disabled={trying || !docType || docType === ANY_TYPE} onClick={trySample}>{trying ? "Reading the sample…" : `Try it with a sample ${chosen.sampleName}`}</Button>
        </> : <p>An empty process: choose each step yourself.</p>}
      </section>
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
        <Field label="Document type" hint={docType === ANY_TYPE ? "Each document is recognised as one of the types; one that fits none goes to review." : "The fields to read and the checks to run. Not listed? Create the process, then make a new type in step 3."}>
          {(id) => <Select id={id} value={docType} onChange={(e) => setDocType(e.target.value)}
            options={[{ value: ANY_TYPE, label: "Any type: recognise each document" }, ...types.map((d) => ({ value: d.id, label: d.name }))]} />}
        </Field>
        {/* DA-4: the folder in a tree, so it is clear where the process is kept */}
        <Field label="Folder" hint="Only people with access to this folder see the process and its documents (view to see, run to add documents, edit to change it).">
          {(id) => <PlatformFolderField id={id} folders={folders} value={folder} onChange={setFolder} />}
        </Field>
      </div>

      {/* DA-5: add the ways documents come in, one at a time; each asks only for what it needs */}
      <fieldset className="df-ways">
        <legend>How documents come in</legend>
        {ways.map((w) => {
          const way = WAYS.find((x) => x.id === w)!;
          return (
            <div key={w} className="df-way-row" role="group" aria-label={way.label}>
              <div className="df-way-row-head">
                <b>{way.label}</b>{w === "folder" && <Hint about="Watched folder" text={HINTS.watchedFolder} />}
                <span className="df-small">{way.about}</span>
                <button type="button" className="df-way-remove" aria-label={"Remove " + way.label} title="Remove" onClick={() => setWays(ways.filter((x) => x !== w))}>
                  <Icon name="close" size={13} /></button>
              </div>
              {w === "folder" && (
                <div className="df-grid" style={{ gridTemplateColumns: "3fr 1fr", gap: 12 }}>
                  <FolderField api={api} label="Folder path" title="Choose the watched folder" value={path} placeholder="\\scan01\invoices" onChange={setPath} />
                  <Field label="Check every">{(id) => <ChoiceOrOwn id={id} value={Number(every)} onChange={(v) => setEvery(String(Math.round(v)))} options={POLL_SECONDS} label={duration} unit="seconds" min={5} />}</Field>
                  <span className="df-small" style={{ gridColumn: "1 / -1" }}>Each file read moves to the folder's processed folder, so nothing is read twice.</span>
                </div>
              )}
              {w === "email" && (accounts && accounts.length
                ? <Field label="Mail account" hint="Every email to it comes in here; narrow it by sender or subject later, in step 2.">
                    {(id) => <Select id={id} value={account} onChange={(e) => setAccount(e.target.value)}
                      options={[{ value: "", label: "Choose later, in step 2" }, ...accounts.map((a) => ({ value: a.id, label: a.name + " · " + a.address }))]} />}
                  </Field>
                : <p className="df-small" style={{ margin: 0 }}>No mail account you may use yet. An administrator adds one in Manage › Connections; then choose it in step 2.</p>)}
            </div>
          );
        })}
        {ways.length < WAYS.length && (
          <Field label="Add a way in">{(id) => <Select id={id} value="" onChange={(e) => { const w = e.target.value as Way; if (w) setWays([...ways, w]); }}
            options={[{ value: "", label: "Choose a way documents come in…" }, ...WAYS.filter((w) => !has(w.id)).map((w) => ({ value: w.id, label: w.label }))]} />}</Field>
        )}
        {!ways.length && <p className="df-small" style={{ margin: 0 }}>No way in yet: documents can only be added later, in step 2.</p>}
      </fieldset>
    </Dialog>
  );
}

/** The folders as a tree: each path gives a folder and the folders above it. */
function folderTree(paths: string[]): TreeNode[] {
  const root: TreeNode[] = [];
  for (const p of paths) {
    const parts = p.split("/").map((x) => x.trim()).filter(Boolean);
    let level = root, at = "";
    for (const part of parts) {
      at = at ? at + "/" + part : part;
      let n = level.find((x) => x.id === at);
      if (!n) { n = { id: at, label: part, children: [] }; level.push(n); }
      level = n.children!;
    }
  }
  const tidy = (ns: TreeNode[]): TreeNode[] => ns.map((n) => ({ ...n, children: n.children?.length ? tidy(n.children) : undefined }));
  return tidy(root);
}

/**
 * The platform folder of a process (DA-4, 27 Nov 2026): the chosen path in full (Finance › Accounts payable) and Choose…,
 * which opens the folder tree in the shared picker, so people see the parent folders and sub-folders around it.
 */
export function PlatformFolderField({ id, folders, value, onChange }: { id?: string; folders: string[]; value: string; onChange: (f: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <span className="df-folder-field">
      <span id={id} className="df-folder-path" title={value ? folderPath(value) : undefined}>
        <Icon name="folder" size={14} />{value ? folderPath(value) : "No folder: everyone with Document Automation access"}</span>
      <Button onClick={() => setOpen(true)}>Choose…</Button>
      {open && <PlatformFolderPicker folders={folders} value={value} onClose={() => setOpen(false)} onChoose={(f) => { onChange(f); setOpen(false); }} />}
    </span>
  );
}

function PlatformFolderPicker({ folders, value, onClose, onChoose }: { folders: string[]; value: string; onClose: () => void; onChoose: (f: string) => void }) {
  const nodes = useMemo(() => folderTree(folders), [folders]);
  const usable = useMemo(() => new Set(folders), [folders]);
  const [selected, setSelected] = useState(value);
  // the chosen folder's parents open, so it shows where it is
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    const s = new Set(nodes.map((n) => n.id));
    const parts = value.split("/").filter(Boolean);
    parts.forEach((_, i) => s.add(parts.slice(0, i + 1).join("/")));
    return s;
  });
  const inside = selected ? folders.filter((f) => f.startsWith(selected + "/") && !f.slice(selected.length + 1).includes("/")) : [];
  const canChoose = selected === NO_FOLDER || usable.has(selected);
  return (
    <PickerDialog id="da-process-folder" title="Choose the process's folder" width={720} height={480} onClose={onClose}
      chosen={selected ? folderPath(selected) : "No folder"} canChoose={canChoose} onChoose={() => onChoose(selected)}
      tree={{ nodes, selected, onSelect: setSelected, expanded, onExpanded: setExpanded, label: "Folders", pathOf: (n) => folderPath(n.id) }}>
      <div className="df-folder-pick-list">
        <button type="button" className={"df-folder-none" + (selected === NO_FOLDER ? " is-on" : "")} aria-pressed={selected === NO_FOLDER} onClick={() => setSelected(NO_FOLDER)}>
          No folder: everyone with Document Automation access sees the process</button>
        {selected ? <>
          <p><b>{folderPath(selected)}</b>{!usable.has(selected) && <span className="df-small"> · a folder above yours: choose one inside it</span>}</p>
          {inside.length ? <table className="df-table" aria-label={"Folders in " + folderPath(selected)}>
            <thead><tr><th scope="col">Folders inside</th></tr></thead>
            <tbody>{inside.map((f) => <tr key={f}><td><button type="button" className="df-link" onClick={() => {
              setSelected(f); setExpanded(new Set(expanded).add(selected)); }}>{f.slice(selected.length + 1)}</button></td></tr>)}</tbody>
          </table> : <p className="df-small">No folders inside.</p>}
        </> : <p className="df-small">Pick a folder on the left. Only people with access to it will see the process.</p>}
      </div>
    </PickerDialog>
  );
}
