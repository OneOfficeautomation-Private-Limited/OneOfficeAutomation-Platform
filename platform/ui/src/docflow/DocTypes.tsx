import { useRef, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import type { DocFlowApi, DocType, DocTypeField, FieldType } from "./api";
import { Chip, FilterBar, FilterSelect, Loading, SearchBox, matches, reportError, useLoad } from "./ui";

const TYPES: FieldType[] = ["Text", "Date", "Amount", "Number", "Choice", "Table"];
const LANGS: [string, string][] = [["en", "English"], ["ar", "Arabic"]];
const list = (s: string) => s.split(",").map((x) => x.trim()).filter(Boolean);
const keyOf = (label: string) => label.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").replace(/^(\d)/, "f_$1") || "field";
const same = (a: DocType, b: DocType) => JSON.stringify(a) === JSON.stringify(b);

/** Document types: what is read from each kind of document and how it is checked; learned labels come from reviews. */
export function DocTypes({ api }: { api: DocFlowApi }) {
  const [types, reload] = useLoad(() => api.docTypes(), [api]);
  const [chosen, setChosen] = useState("");
  // a type not saved yet: a new one (its first field is added straight away) or one proposed from samples
  const [pending, setPending] = useState<{ type: DocType; addField: boolean; n: number } | null>(null);
  const [listQ, setListQ] = useState("");
  const [origin, setOrigin] = useState<"all" | "built-in" | "made-here">("all");
  const [teaching, setTeaching] = useState(false);
  const [naming, setNaming] = useState(false);
  if (!types) return <Loading />;

  const current = pending?.type ?? types.find((x) => x.id === chosen) ?? types[0];
  const shown = types.filter((x) => matches(listQ, x.name, x.meta) && (origin === "all" || x.origin === origin));
  const propose = (type: DocType, addField: boolean) => setPending({ type, addField, n: (pending?.n ?? 0) + 1 });

  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Document types</h2><p>What DocFlow reads from each kind of document, and how it checks it. Used by processes.</p></div>
        <span className="df-grow" /><Button onClick={() => setTeaching(true)}>Teach from samples</Button>
        <Button kind="primary" icon="plus" onClick={() => setNaming(true)}>New document type</Button>
      </div>
      <div className="df-split">
        <nav className="df-card df-list" aria-label="Document types">
          <SearchBox value={listQ} onChange={setListQ} placeholder="Search types" />
          <select className="ui-input ui-select" aria-label="Origin" value={origin} onChange={(e) => setOrigin(e.target.value as typeof origin)}>
            <option value="all">All types ({types.length})</option>
            <option value="built-in">Built in ({types.filter((x) => x.origin === "built-in").length})</option>
            <option value="made-here">Made here ({types.filter((x) => x.origin === "made-here").length})</option>
          </select>
          {pending && <button type="button" className="df-list-item" aria-current><span><b>{pending.type.name}</b></span><span className="df-small">Not saved yet</span></button>}
          {shown.map((x) => (
            <button key={x.id} type="button" className="df-list-item" aria-current={!pending && x.id === current?.id} onClick={() => { setPending(null); setChosen(x.id); }}>
              <span style={{ display: "flex", gap: 8 }}><b>{x.name}</b><span className="df-grow" /><span className="df-small">{x.fields.length ? x.fields.length + " fields" : ""}</span></span>
              <span className="df-small">{x.meta}</span>
            </button>
          ))}
        </nav>

        <div style={{ display: "flex", flexDirection: "column", gap: 16, minWidth: 0 }}>
          {current
            ? <DocTypeEditor key={pending ? "new-" + pending.n : current.id} api={api} value={current} addField={pending?.addField}
                onSaved={(t) => { setPending(null); setChosen(t.id); reload(); }} onDiscard={() => setPending(null)} onDeleted={() => { setChosen(""); reload(); }} />
            : <div className="df-card df-empty">No document types yet. Click Teach from samples or New document type.</div>}
        </div>
      </div>

      {teaching && <TeachDialog api={api} onClose={() => setTeaching(false)} onProposal={(d) => { setTeaching(false); propose(d, false); }} />}
      {naming && <NameDialog onClose={() => setNaming(false)} onDone={(name) => {
        setNaming(false);
        propose({ id: "", name, origin: "made-here", meta: "", keywords: [], languages: ["en", "ar"], usedBy: [], fields: [] }, true);
      }} />}
    </div>
  );
}

/**
 * One document type, to read and change: its name, the words that recognise it, its languages, and its fields with
 * the labels DocFlow looks for. Used on the Document types page and in a process's classification step. A type is
 * shared, so saving it changes every process that uses it. id "" is a type not saved yet (Create type saves it).
 */
export function DocTypeEditor({ api, value, onSaved, onDiscard, onDeleted, addField = false, showTest = true }:
  { api: DocFlowApi; value: DocType; onSaved: (t: DocType) => void; onDiscard?: () => void; onDeleted?: () => void; addField?: boolean; showTest?: boolean }) {
  const [t, setDraft] = useState<DocType>(() => structuredClone(value));
  const [q, setQ] = useState("");
  const [type, setType] = useState("all");
  const [req, setReq] = useState<"all" | "required" | "optional">("all");
  const [editing, setEditing] = useState<DocTypeField | "new" | null>(addField ? "new" : null);
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);

  const dirty = !t.id || !same(t, value);
  const set = (patch: Partial<DocType>) => setDraft({ ...t, ...patch });
  const fields = t.fields.filter((f) => matches(q, f.label, f.key, ...f.aliases) && (type === "all" || f.type === type) && (req === "all" || f.required === (req === "required")));
  const save = async () => {
    setBusy(true);
    try { const s = await api.saveDocType(t); setDraft(structuredClone(s)); onSaved(s); } catch (e) { reportError(e); } finally { setBusy(false); }
  };

  return (
    <>
      <section className="df-card df-pad" aria-label={t.name}>
        <div className="df-head-row">
          <input className="ui-input df-title-select" aria-label="Type name" value={t.name} onChange={(e) => set({ name: e.target.value })} />
          <Chip tone="mute" dot={false}>{!t.id ? "New" : t.origin === "built-in" ? "Built in" : "Made here"}</Chip>
          <span className="df-grow" />
          {dirty && <span className="df-text-warn" role="status">Unsaved changes</span>}
          {showTest && t.id && <a className="ui-btn ui-btn-secondary" href="#/testlab">Test on a document</a>}
          {t.id && t.origin === "made-here" && onDeleted && <Button kind="plain" disabled={t.usedBy.length > 0} title={t.usedBy.length ? "Used by a process" : undefined} onClick={() => setDeleting(true)}>Delete</Button>}
          {!t.id && onDiscard && <Button onClick={onDiscard}>Discard</Button>}
          <Button kind="primary" disabled={!dirty || busy || !t.name.trim() || !t.fields.length} onClick={save}>{t.id ? "Save" : "Create type"}</Button>
        </div>
        <div className="df-grid df-g3" style={{ marginTop: 12 }}>
          <Field label="Recognised by" hint="Words on the page that identify this type, comma separated.">
            {(id) => <TextInput id={id} value={t.keywords.join(", ")} onChange={(e) => set({ keywords: list(e.target.value) })} />}
          </Field>
          <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
            <legend className="df-small" style={{ padding: 0, marginBottom: 6 }}>Languages</legend>
            {LANGS.map(([code, label]) => (
              <label key={code} style={{ display: "inline-flex", gap: 6, marginRight: 14 }}>
                <input type="checkbox" checked={t.languages.includes(code)}
                  onChange={(e) => set({ languages: e.target.checked ? [...t.languages, code] : t.languages.filter((l) => l !== code) })} />{label}
              </label>
            ))}
          </fieldset>
          <div><div className="df-small">Used by</div>{t.usedBy.length ? t.usedBy.join(", ") : "No process yet"}</div>
        </div>
      </section>

      <section className="df-card" aria-label="Fields to read" style={{ overflow: "hidden" }}>
        <div className="df-head-row df-pad" style={{ paddingBottom: 0 }}><b>Fields to read</b><span className="df-grow" /><Button icon="plus" onClick={() => setEditing("new")}>Add field</Button></div>
        <FilterBar onClear={() => { setQ(""); setType("all"); setReq("all"); }} count={`${fields.length} of ${t.fields.length} fields`}>
          <SearchBox value={q} onChange={setQ} placeholder="Search fields or labels" />
          <FilterSelect label="Type" value={type} onChange={setType} options={[{ value: "all", label: "All types" }, ...TYPES.map((v) => ({ value: v, label: v }))]} />
          <FilterSelect label="Required" value={req} onChange={setReq} options={[{ value: "all", label: "All" }, { value: "required", label: "Required" }, { value: "optional", label: "Optional" }]} />
        </FilterBar>
        <div className="df-table-wrap">
          <table className="df-table" style={{ minWidth: 780 }}>
            <thead><tr><th>Field</th><th>Labels on the page</th><th>Type</th><th>Required</th><th>Format check</th><th>Learned from reviews</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {fields.map((f) => (
                <tr key={f.key}>
                  <td><b>{f.label}</b><div className="df-small ui-mono">{f.key}</div></td>
                  <td className="df-muted">{[...new Set([f.label, ...f.aliases])].join(", ")}</td><td>{f.type}</td><td>{f.required ? "Required" : "Optional"}</td>
                  <td className="ui-mono">{f.regex || "—"}</td>
                  <td className="df-muted">{f.learned.length ? f.learned.map((l) => (
                    <span key={l} className="df-learned">"{l}"<button type="button" className="df-link" aria-label={`Forget "${l}"`}
                      onClick={() => set({ fields: t.fields.map((x) => x.key === f.key ? { ...x, learned: x.learned.filter((y) => y !== l) } : x) })}>×</button></span>)) : "—"}</td>
                  <td className="df-right" style={{ whiteSpace: "nowrap" }}>
                    <Button kind="plain" onClick={() => setEditing(f)}>Edit</Button>
                    <Button kind="plain" aria-label={"Remove " + f.label} onClick={() => set({ fields: t.fields.filter((x) => x.key !== f.key) })}>Remove</Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!t.fields.length && <div className="df-empty">No fields yet. Add them, or use Teach from samples.</div>}
          {t.fields.length > 0 && !fields.length && <div className="df-empty">No field matches these filters.</div>}
        </div>
      </section>

      {editing && <FieldDialog key={editing === "new" ? "new" : editing.key} value={editing === "new" ? null : editing} taken={t.fields.map((f) => f.key)}
        onClose={() => setEditing(null)}
        onSave={(f) => { set({ fields: t.fields.some((x) => x.key === f.key) ? t.fields.map((x) => x.key === f.key ? f : x) : [...t.fields, f] }); setEditing(null); }} />}
      <Dialog open={deleting} onClose={() => setDeleting(false)} title={`Delete ${t.name}?`} width={460}
        footer={<><Button onClick={() => setDeleting(false)}>Cancel</Button>
          <Button kind="danger" onClick={() => { setDeleting(false); api.deleteDocType(t.id).then(() => onDeleted?.(), reportError); }}>Delete</Button></>}>
        <p style={{ margin: 0 }}>Its fields and what reviewers taught it are deleted. Documents already read keep their values.</p>
      </Dialog>
    </>
  );
}

function FieldDialog({ value, taken, onClose, onSave }: { value: DocTypeField | null; taken: string[]; onClose: () => void; onSave: (f: DocTypeField) => void }) {
  const [f, setF] = useState<DocTypeField>(value ?? { key: "", label: "", aliases: [], type: "Text", required: false, regex: "", learned: [] });
  const [aliases, setAliases] = useState(f.aliases.join(", "));
  const key = value ? f.key : keyOf(f.label);
  const clash = !value && taken.includes(key);
  let badRegex = false;
  try { if (f.regex) new RegExp(f.regex); } catch { badRegex = true; }
  return (
    <Dialog open onClose={onClose} title={value ? "Edit field" : "Add field"} width={560}
      footer={<><Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!f.label.trim() || clash || badRegex} onClick={() => onSave({ ...f, key, label: f.label.trim(), aliases: list(aliases) })}>Save</Button></>}>
      <div className="df-grid df-g2">
        <Field label="Name" hint={clash ? "A field with this name already exists." : `Saved as ${key}`}>{(id) => <TextInput id={id} value={f.label} placeholder="Invoice number" onChange={(e) => setF({ ...f, label: e.target.value })} />}</Field>
        <Field label="Type">{(id) => <Select id={id} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value as FieldType })} options={TYPES.map((v) => ({ value: v, label: v }))} />}</Field>
      </div>
      <Field label="Other labels on the page" hint="Comma separated, e.g. Invoice No, Bill No, رقم الفاتورة">
        {(id) => <TextInput id={id} value={aliases} onChange={(e) => setAliases(e.target.value)} />}
      </Field>
      <Field label="Format check (optional)" hint={badRegex ? "This is not a valid pattern." : "A pattern the value must match, e.g. 3\\d{13}3 for a KSA VAT number."}>
        {(id) => <TextInput id={id} mono value={f.regex} onChange={(e) => setF({ ...f, regex: e.target.value })} />}
      </Field>
      <label style={{ display: "flex", gap: 8, alignItems: "center" }}><input type="checkbox" checked={f.required} onChange={(e) => setF({ ...f, required: e.target.checked })} />Required: a document without it goes to review</label>
    </Dialog>
  );
}

export function NameDialog({ onClose, onDone }: { onClose: () => void; onDone: (name: string) => void }) {
  const [name, setName] = useState("");
  return (
    <Dialog open onClose={onClose} title="New document type" width={460}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!name.trim()} onClick={() => onDone(name.trim())}>Next: add fields</Button></>}>
      <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="Customs declaration" onChange={(e) => setName(e.target.value)} />}</Field>
      <p className="df-small" style={{ margin: 0 }}>Easier with examples: Teach from samples proposes the fields for you.</p>
    </Dialog>
  );
}

export function TeachDialog({ api, onClose, onProposal }: { api: DocFlowApi; onClose: () => void; onProposal: (d: DocType) => void }) {
  const picker = useRef<HTMLInputElement>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const read = async () => {
    setBusy(true);
    try { onProposal(await api.suggestDocType(files)); } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title="Teach from samples" width={560}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!files.length || busy} onClick={read}>{busy ? "Reading the samples…" : "Read samples"}</Button></>}>
      <p style={{ margin: 0 }}>Add 1 to 3 example documents of the same kind. DocFlow reads them and proposes the fields; you check them before anything is saved.</p>
      <div className="df-files">
        {files.map((f) => <div key={f.name}><b>{f.name}</b><span className="df-grow" />
          <button type="button" className="df-link" aria-label={"Remove " + f.name} onClick={() => setFiles(files.filter((x) => x !== f))}>Remove</button></div>)}
        {files.length < 3 && <div>
          <input ref={picker} type="file" multiple hidden accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff" aria-label="Choose samples"
            onChange={(e) => setFiles([...files, ...Array.from(e.target.files ?? [])].slice(0, 3))} />
          <button type="button" className="df-link" onClick={() => picker.current?.click()}>+ Choose samples</button>
        </div>}
      </div>
    </Dialog>
  );
}
