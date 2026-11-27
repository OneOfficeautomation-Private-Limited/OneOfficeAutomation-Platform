import { useEffect, useMemo, useState } from "react";
import { Button, Dialog, Field, Icon, PickerDialog, Select, TextInput, type TreeNode } from "../../components";
import { ANY_TYPE, type DocFlowApi, type DocType, type TestRun } from "./api";
import { TEMPLATES, sampleFile, typeFor } from "./templates";
import { HINTS } from "./help";
import { reportError } from "./ui";

/** A platform folder path as people read it: Finance/Accounts payable is "Finance / Accounts payable". */
export const folderLabel = (f: string) => f.split("/").map((x) => x.trim()).filter(Boolean).join(" / ");
/** The same path with arrows, as the folder pickers show where something is: Finance › Accounts payable. */
const folderPath = (f: string) => f.split("/").map((x) => x.trim()).filter(Boolean).join(" › ");

/** "No folder": everyone with Document Automation access sees the process. */
const NO_FOLDER = "";
const SCRATCH = "scratch";

/**
 * New process (DA-2, DA-3 and the redesign of 27 Nov 2026, artboard "DA step 1"): only Start from, the name and the
 * document type, labels beside the fields. Where the process is kept and how documents come in are step 2's, which opens
 * next. The name starts empty and has to be typed; a template picks only the document type.
 */
export function NewProcessDialog({ api, open, onClose, onCreated }:
  { api: DocFlowApi; open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState("");
  const [types, setTypes] = useState<DocType[]>([]);
  const [docType, setDocType] = useState("");
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
  // the first folder the person may put a process in; changed in step 2, "Where this process is kept"
  const [folder, setFolder] = useState(NO_FOLDER);
  useEffect(() => { if (open) api.folders().then((f) => setFolder((cur) => cur || f[0] || NO_FOLDER), () => undefined); }, [api, open]);
  const valid = name.trim() && docType;

  const create = async () => {
    const r = await api.createProcess({ name: name.trim(), docType, folder, upload: true, fromBot: false });
    setName("");
    onCreated(r.id + "/intake");   // step 2 next: where it is kept and how documents come in
  };

  return (
    <Dialog open={open} onClose={onClose} title="New process" width={620}
      footer={<>
        <span className="df-muted">Next, step 2: where it is kept and how documents come in.</span>
        <span style={{ marginRight: "auto" }} />
        <Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!valid} onClick={create}>Create process</Button>
      </>}>
      <div className="df-new-process">
        <Field label="Start from" layout="beside">{(id) => <>
          <Select id={id} value={template || SCRATCH} onChange={(e) => pick(e.target.value === SCRATCH ? "" : e.target.value)}
            options={[...TEMPLATES.map((t) => ({ value: t.id, label: t.name })), { value: SCRATCH, label: "Start from scratch" }]} />
          <p className="df-start-about" aria-label="About this start">{chosen ? <>{chosen.does}{" "}
            <button type="button" className="df-link" disabled={trying || !docType || docType === ANY_TYPE} onClick={trySample}>
              {trying ? "Reading the sample…" : `Try it with a sample ${chosen.sampleName}`}</button></> : "An empty process: choose each step yourself."}</p>
        </>}</Field>
        <Field label="Process name" layout="beside">{(id) => <TextInput id={id} value={name} placeholder="e.g. Supplier invoices" required
          title={HINTS.processName} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Document type" layout="beside">{(id) => <Select id={id} value={docType} onChange={(e) => setDocType(e.target.value)}
          options={[{ value: ANY_TYPE, label: "Any type: recognise each document" }, ...types.map((d) => ({ value: d.id, label: d.name }))]} />}</Field>
      </div>
      {tried && (
        <section className="df-card df-pad" aria-label="What the sample gave" style={{ background: "var(--ui-soft)" }}>
          <b>What the sample gave</b> <span className="df-small">(a made-up document, read now; nothing was saved)</span>
          {tried.runs[0]?.error ? <p className="df-text-warn" style={{ margin: "6px 0 0" }}>{tried.runs[0].error}</p> : (
            <table className="df-table" style={{ marginTop: 6 }}><tbody>
              {tried.rows.filter((r) => r.a !== "—").slice(0, 8).map((r) => <tr key={r.field}><td>{r.field}</td><td><b>{r.a}</b></td><td className="df-small">{r.aConf}</td></tr>)}
            </tbody></table>)}
        </section>
      )}
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
  const shown = value ? folderPath(value) : "No folder: everyone with Document Automation access";
  return (
    <span className="df-proc-folder">
      <Icon name="folder" size={14} />
      <span id={id} className="df-proc-folder-path" title={shown}>{shown}</span>
      <button type="button" className="df-proc-folder-change" onClick={() => setOpen(true)}>Change…</button>
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
