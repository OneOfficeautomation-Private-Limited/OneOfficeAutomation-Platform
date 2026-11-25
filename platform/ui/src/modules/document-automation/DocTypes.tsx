import { useRef, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import type { CheckStrictness, DocFlowApi, DocType, DocTypeField, FieldMethod, FieldType } from "./api";
import { FORMATS, READABLE_FILES, formatOf } from "./formats";
import { LanguagePicker } from "./languages";

/** A field's check in words: a known check by its name, the field's kind otherwise, or "Own pattern" (shown under Advanced). */
export function checkText(f: Pick<DocTypeField, "regex" | "type">): string {
  if (f.regex) return formatOf(f.regex)?.label ?? "Own pattern (see Advanced)";
  return ({ Date: "A date", "Date and time": "A date and time", Amount: "An amount", Number: "A number", Percentage: "A percentage", Choice: "One of the choices",
    "Several choices": "Some of the choices", Email: "An email address", Phone: "A phone number", "Yes/No": "Yes or no", Ticked: "Ticked or not", Signed: "Signed or not" } as Record<string, string>)[f.type] ?? "None";
}
import { AdvancedToggle, Chip, FilterBar, FilterSelect, Loading, SearchBox, matches, reportError, useLoad } from "./ui";

export const TYPES: FieldType[] = ["Text", "Date", "Date and time", "Amount", "Number", "Percentage", "Yes/No", "Ticked", "Signed", "Choice",
  "Several choices", "Email", "Phone", "Address", "Table", "Group"];
/** What each kind holds, in one line (the Type select's hint). */
const TYPE_HINT: Partial<Record<FieldType, string>> = {
  Ticked: "A check box on the page: ticked or not. Read by engines that see check boxes (cloud and AI reading).",
  Signed: "Whether the page is signed there. Read by engines that see signatures (cloud and AI reading).",
  "Several choices": "One or more of the choices, such as the services ticked on a form.",
  Group: "Several values that belong together, such as an address in parts. Kept as DocFlow has it.",
};
/** A comma list: commas in any script (Latin, Arabic ،, full-width ，) and semicolons separate. */
export const list = (s: string) => s.split(/[,،，;]/).map((x) => x.trim()).filter(Boolean);
/** A field's name in results: its label in lower case, any script, words joined by _ ("Invoice no" -> invoice_no, "رقم الفاتورة" -> رقم_الفاتورة). */
export const keyOf = (label: string) => label.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "_").replace(/^_+|_+$/g, "").replace(/^(\p{N})/u, "f_$1") || "field";
/** The key for a new field: its own, or with _2, _3... when another field has it already. */
export const freeKey = (key: string, taken: string[]) => { let k = key, n = 2; while (taken.includes(k)) k = `${key}_${n++}`; return k; };
const METHODS: { value: FieldMethod; label: string; short: string; hint: string }[] = [
  { value: "copy", label: "Copy it as printed", short: "Copied", hint: "The value is on the page, such as an invoice number." },
  { value: "work-out", label: "Work it out from the document", short: "Worked out", hint: "AI reading answers it from the whole document, such as \"Is it overdue?\" or a one-line summary. Write the question in What it is." },
  { value: "pick", label: "Pick one of the choices", short: "Picked", hint: "AI reading chooses the choice that fits best, such as the kind of expense." },
];
const STRICTNESS: { value: CheckStrictness; label: string }[] = [
  { value: "error", label: "Send to review" }, { value: "warn", label: "Only note it" }, { value: "off", label: "Ignore" },
];
/** "Write as" choices by kind; any other text is kept as typed (Own). */
const WRITE_AS: Partial<Record<FieldType, string[]>> = {
  Date: ["YYYY-MM-DD", "DD/MM/YYYY", "MM/DD/YYYY", "DD.MM.YYYY", "D MMM YYYY"],
  "Date and time": ["YYYY-MM-DD HH:mm", "DD/MM/YYYY HH:mm", "MM/DD/YYYY hh:mm A"],
  Amount: ["2 decimals", "0 decimals", "3 decimals"], Number: ["2 decimals", "0 decimals", "3 decimals"], Percentage: ["2 decimals", "0 decimals"],
  Text: ["UPPER", "lower", "Title Case", "Trimmed"], Address: ["UPPER", "Title Case", "Trimmed"], Phone: ["Digits only", "International (+)"],
};
const same = (a: DocType, b: DocType) => JSON.stringify(a) === JSON.stringify(b);
/** The format check's "Own pattern…" choice: the pattern is typed by hand. */
const OWN = "own";

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
        propose({ id: "", name, origin: "made-here", meta: "", keywords: [], languages: [], usedBy: [], fields: [] }, true);
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
  const [advanced, setAdvanced] = useState(false);
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
          <Button kind="primary" disabled={!dirty || busy || !t.name.trim()} title={!t.fields.length ? "Saved with no fields: add them before a process uses it" : undefined} onClick={save}>{t.id ? "Save" : "Create type"}</Button>
        </div>
        <div className="df-grid df-g3" style={{ marginTop: 12 }}>
          <Field label="Recognised by" hint="Words on the page that identify this type, comma separated.">
            {(id) => <TextInput id={id} value={t.keywords.join(", ")} onChange={(e) => set({ keywords: list(e.target.value) })} />}
          </Field>
          <LanguagePicker api={api} value={t.languages} onChange={(languages) => set({ languages })} />
          <div><div className="df-small">Used by</div>{t.usedBy.length ? t.usedBy.join(", ") : "No process yet"}</div>
        </div>
        <div className="df-grid" style={{ gridTemplateColumns: "2fr 1fr 1fr", gap: 12 }}>
          <Field label="What it is (optional)" hint="A sentence about this kind of document. AI reading uses it to recognise the type and read it.">
            {(id) => <TextInput id={id} value={t.description ?? ""} placeholder="Bills from suppliers asking us to pay for goods or services" onChange={(e) => set({ description: e.target.value })} />}
          </Field>
          <Field label="Dates written as" hint="For dates with numbers only, such as 03/04/2026.">
            {(id) => <Select id={id} value={t.dateOrder ?? ""} onChange={(e) => set({ dateOrder: e.target.value })}
              options={[{ value: "", label: "Server setting" }, { value: "DMY", label: "Day first (14/11/2026)" }, { value: "MDY", label: "Month first (11/14/2026)" }, { value: "YMD", label: "Year first (2026/11/14)" }]} />}
          </Field>
          <Field label="Decimal mark" hint="Whether 1.234 means one thousand two hundred and thirty-four.">
            {(id) => <Select id={id} value={t.decimalMark ?? ""} onChange={(e) => set({ decimalMark: e.target.value })}
              options={[{ value: "", label: "Server setting" }, { value: ".", label: "Point (1,234.50)" }, { value: ",", label: "Comma (1.234,50)" }]} />}
          </Field>
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
          <table className="df-table">
            <thead><tr><th>What to read</th><th>Words next to it on the page</th><th>Kind</th><th>How it is read</th><th>Must be there</th><th>Check</th>
              {advanced && <><th>Pattern</th><th title="Labels learned from reviews">Learned labels</th></>}<th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {fields.map((f) => (
                <tr key={f.key}>
                  <td><b>{f.label}</b>{advanced && <div className="df-small ui-mono">{f.key}</div>}</td>
                  <td className="df-muted">{[...new Set([f.label, ...f.aliases])].join(", ")}</td><td>{f.type}</td>
                  <td>{METHODS.find((m) => m.value === (f.method ?? "copy"))?.short}</td><td>{f.required ? "Yes" : "No"}</td>
                  <td title={formatOf(f.regex)?.accepts}>{checkText(f)}{f.regex && f.strictness && f.strictness !== "error" ? ` (${STRICTNESS.find((x) => x.value === f.strictness)?.label.toLowerCase()})` : ""}</td>
                  {advanced && <><td className="ui-mono">{f.regex || "—"}</td>
                  <td className="df-muted">{f.learned.length ? f.learned.map((l) => (
                    <span key={l} className="df-learned">"{l}"<button type="button" className="df-link" aria-label={`Forget "${l}"`}
                      onClick={() => set({ fields: t.fields.map((x) => x.key === f.key ? { ...x, learned: x.learned.filter((y) => y !== l) } : x) })}>×</button></span>)) : "—"}</td></>}
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
        {t.fields.length > 25 && <div className="df-note df-info" style={{ margin: "8px 16px 0" }}>
          {t.fields.length} fields: AI reading is most accurate with up to about 25 fields at a time. Consider two document types, or a table field for repeating values.</div>}
        {t.id && (t.examples?.length ?? 0) > 0 && <div className="df-pad" style={{ paddingTop: 8, paddingBottom: 0 }}>
          <span className="df-small">Teaching examples (approved documents AI reading learns from): </span>
          {(t.examples ?? []).map((d) => <span key={d} className="df-learned">{d.slice(0, 8)}<button type="button" className="df-link" aria-label={`Stop using ${d} as an example`}
            onClick={() => api.removeTeachingExample(t.id, d).then(() => setDraft({ ...t, examples: (t.examples ?? []).filter((x) => x !== d) }), reportError)}>×</button></span>)}
        </div>}
        <div className="df-pad" style={{ paddingTop: 8, paddingBottom: 10 }}>
          <AdvancedToggle open={advanced} onToggle={() => setAdvanced(!advanced)} what="the pattern behind each check, field names and labels learned from reviews" />
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
  const [f, setF] = useState<DocTypeField>(value ?? { key: "", label: "", aliases: [], type: "Text", required: false, regex: "", learned: [], method: "copy", strictness: "error", choices: [], choiceNotes: {} });
  const [aliases, setAliases] = useState(f.aliases.join(", "));
  // a new field whose name another field has already gets _2, _3...: names in any script work
  const key = value ? f.key : freeKey(keyOf(f.label), taken);
  // the format check: one of the common formats, or an own pattern (any pattern not in the list opens as one)
  const [format, setFormat] = useState(() => (f.regex ? formatOf(f.regex)?.id ?? OWN : ""));
  const known = FORMATS.find((x) => x.id === format);
  const chooseFormat = (id: string) => {
    setFormat(id);
    if (id !== OWN) setF({ ...f, regex: FORMATS.find((x) => x.id === id)?.pattern ?? "" });
  };
  const [choices, setChoices] = useState((f.choices ?? []).join(", "));
  const choiceList = list(choices);
  const hasChoices = f.type === "Choice" || f.type === "Several choices" || f.method === "pick";
  const writeAs = WRITE_AS[f.type] ?? [];
  const [ownWrite, setOwnWrite] = useState(!!f.writeAs && !writeAs.includes(f.writeAs));
  const method = METHODS.find((m) => m.value === (f.method ?? "copy")) ?? METHODS[0];
  let badRegex = false;
  try { if (f.regex) new RegExp(f.regex); } catch { badRegex = true; }
  const done = () => onSave({
    ...f, key, label: f.label.trim(), aliases: list(aliases), description: (f.description ?? "").trim(),
    choices: hasChoices ? choiceList : f.choices,
    choiceNotes: hasChoices ? Object.fromEntries(Object.entries(f.choiceNotes ?? {}).filter(([k, v]) => choiceList.includes(k) && v.trim())) : f.choiceNotes,
  });
  return (
    <Dialog open onClose={onClose} title={value ? "Edit field" : "Add field"} width={620}
      footer={<><Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!f.label.trim() || badRegex || (f.method === "pick" && !choiceList.length)} onClick={done}>Save</Button></>}>
      <div className="df-grid df-g2">
        <Field label="Name" hint={f.label.trim() ? `Saved as ${key}` : "In any language, such as Invoice number"}>{(id) => <TextInput id={id} value={f.label} placeholder="Invoice number" onChange={(e) => setF({ ...f, label: e.target.value })} />}</Field>
        <Field label="Type" hint={TYPE_HINT[f.type]}>{(id) => <Select id={id} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value as FieldType, writeAs: "" })}
          options={TYPES.map((v) => ({ value: v, label: v }))} />}</Field>
      </div>
      <Field label="What it is (optional)" hint="In plain words; AI reading follows it. For Work it out, write the question here.">
        {(id) => <TextInput id={id} value={f.description ?? ""} placeholder="The amount the customer must pay, after discounts and tax" onChange={(e) => setF({ ...f, description: e.target.value })} />}
      </Field>
      <Field label="How it is read" hint={method.hint}>
        {(id) => <Select id={id} value={f.method ?? "copy"} onChange={(e) => setF({ ...f, method: e.target.value as FieldMethod })} options={METHODS.map((m) => ({ value: m.value, label: m.label }))} />}
      </Field>
      {(f.method ?? "copy") === "copy" && <Field label="Other labels on the page" hint="Comma separated, in any language, e.g. Invoice No, Bill No, Rechnungsnummer">
        {(id) => <TextInput id={id} value={aliases} onChange={(e) => setAliases(e.target.value)} />}
      </Field>}
      {hasChoices && <>
        <Field label="Choices" hint="Comma separated.">{(id) => <TextInput id={id} value={choices} placeholder="Travel, Meals, Office supplies" onChange={(e) => setChoices(e.target.value)} />}</Field>
        {choiceList.length > 0 && <details><summary className="df-small">What each choice means (optional; helps AI reading pick)</summary>
          <div className="df-grid df-g2" style={{ marginTop: 8 }}>{choiceList.map((c) => (
            <Field key={c} label={c}>{(id) => <TextInput id={id} value={f.choiceNotes?.[c] ?? ""} onChange={(e) => setF({ ...f, choiceNotes: { ...f.choiceNotes, [c]: e.target.value } })} />}</Field>))}
          </div></details>}
        <label style={{ display: "flex", gap: 8, alignItems: "center" }}><input type="checkbox" checked={!!f.allowOther} onChange={(e) => setF({ ...f, allowOther: e.target.checked })} />
          Keep other values too: a value not in the list is kept and noted, not sent to review</label>
      </>}
      <div className="df-grid df-g2">
        <Field label="Format check (optional)" hint={format === OWN ? undefined : known ? "Accepts: " + known.accepts : "Any value is accepted."}>
          {(id) => <Select id={id} value={format} onChange={(e) => chooseFormat(e.target.value)}
            options={[{ value: "", label: "No check" }, ...FORMATS.map((x) => ({ value: x.id, label: x.label, group: x.group })), { value: OWN, label: "Own pattern…", group: "Other" }]} />}
        </Field>
        <Field label="When the check fails" hint="Only note it: the value is kept and the note shows with the results.">
          {(id) => <Select id={id} value={f.strictness ?? "error"} disabled={!f.regex} onChange={(e) => setF({ ...f, strictness: e.target.value as CheckStrictness })} options={STRICTNESS} />}
        </Field>
      </div>
      {format === OWN && (
        <Field label="Own pattern" hint={badRegex ? "This is not a valid pattern." : "A regular expression the whole value must match, such as ^[A-Z]{3}\\d{6}$ for 3 capital letters and 6 digits."}>
          {(id) => <TextInput id={id} mono value={f.regex} onChange={(e) => setF({ ...f, regex: e.target.value })} />}
        </Field>
      )}
      {(writeAs.length > 0 || ownWrite) && <div className="df-grid df-g2">
        <Field label="Write it as (optional)" hint="How the value is written in results, Excel and files. Checks use the value as read.">
          {(id) => <Select id={id} value={ownWrite ? OWN : f.writeAs ?? ""} onChange={(e) => { const v = e.target.value; setOwnWrite(v === OWN); setF({ ...f, writeAs: v === OWN ? f.writeAs : v }); }}
            options={[{ value: "", label: "As read" }, ...writeAs.map((v) => ({ value: v, label: v })), { value: OWN, label: "Own format…" }]} />}
        </Field>
        {ownWrite && <Field label="Own format" hint="Dates: YYYY, MM, DD, MMM, HH, mm. Numbers: 2 decimals.">
          {(id) => <TextInput id={id} mono value={f.writeAs ?? ""} onChange={(e) => setF({ ...f, writeAs: e.target.value })} />}
        </Field>}
      </div>}
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

/** Most example files for Teach from samples: DocFlow reads up to its DOCAUTO_TEACH_MAX_SAMPLES (5 unless changed). */
const MAX_SAMPLES = 5;

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
      <p style={{ margin: 0 }}>Add 1 to {MAX_SAMPLES} example documents of the same kind (more examples find more fields). DocFlow reads them and proposes the fields; you check them before anything is saved.</p>
      <div className="df-files">
        {files.map((f) => <div key={f.name}><b>{f.name}</b><span className="df-grow" />
          <button type="button" className="df-link" aria-label={"Remove " + f.name} onClick={() => setFiles(files.filter((x) => x !== f))}>Remove</button></div>)}
        {files.length < MAX_SAMPLES && <div>
          <input ref={picker} type="file" multiple hidden accept={READABLE_FILES} aria-label="Choose samples"
            onChange={(e) => setFiles([...files, ...Array.from(e.target.files ?? [])].slice(0, MAX_SAMPLES))} />
          <button type="button" className="df-link" onClick={() => picker.current?.click()}>+ Choose samples</button>
        </div>}
      </div>
    </Dialog>
  );
}
