import { useEffect, useState } from "react";
import { Button } from "./Button";
import { Field, Select, TextArea, TextInput } from "./Field";
import "./pickers.css";

/**
 * Usability round (14 Nov 2026, items 9 and 10): an agent's inputs as one field each (its name, type word,
 * description and a mark when required) wherever inputs are given: Run, schedules, workflow stages, app buttons and
 * starting a workflow. Numbers, Yes/no and dates get their own fields; List, Dictionary and Table values get small
 * editors (items with Add and Remove, key and value rows, a grid with Paste CSV); a Credential is chosen from the vault
 * (folder, credential, attribute). Where the place allows references (a workflow stage's $form.field$, an app's
 * {field}) a field can take one instead of a fixed value. "Edit as JSON" shows the same values as JSON for power
 * users; text that is not valid JSON is shown as an error and nothing changes. The stored value is the same object the
 * JSON boxes held before.
 */
export type InputSpec = { name: string; type?: string; description?: string; required?: boolean; default?: unknown; enum?: unknown[] };
export type VaultRef = { locker: string; name: string; attribute: string };
type Obj = Record<string, unknown>;

const TYPE_WORD: Record<string, string> = { String: "Text", Number: "Number", Boolean: "Yes or no", DateTime: "Date and time", Date: "Date", Time: "Time",
  TimeSpan: "Length of time", List: "List", Dictionary: "Dictionary", Table: "Table", Record: "Record", Credential: "Credential", File: "File", Any: "Any value" };
export const typeWord = (t?: string) => TYPE_WORD[t ?? "String"] ?? t ?? "Text";

/** Text to a value as the JSON boxes read it: JSON when it parses (numbers, true, lists), otherwise the text. */
export function looseValue(text: string): unknown {
  if (/^\s*[[{"]|^\s*-?\d|^\s*(true|false|null)\s*$/.test(text)) { try { return JSON.parse(text); } catch { /* the text itself */ } }
  return text;
}
const show = (v: unknown) => v === undefined || v === null ? "" : typeof v === "string" ? v : JSON.stringify(v);
const isSet = (v: unknown) => v !== undefined && v !== null && v !== "";
const credOf = (v: unknown) => v && typeof v === "object" && typeof (v as { $credential?: unknown }).$credential === "string" ? (v as { $credential: string }).$credential : null;

/** The required inputs that have no value (and no default). */
export function missingInputs(inputs: InputSpec[] | null | undefined, value: Obj): string[] {
  return (inputs ?? []).filter((i) => i.required && !isSet(value[i.name]) && i.default === undefined).map((i) => i.name);
}

/** A vault list loader from One CommandCenter's vault folders (what the person may see). */
export function vaultRefsFrom(api: { vaultFolders: () => Promise<{ name: string; credentials: { name: string; attribute: string }[] }[]> }) {
  return () => api.vaultFolders().then((fs) => fs.flatMap((f) => f.credentials.map((c) => ({ locker: f.name, name: c.name, attribute: c.attribute }))));
}

export function AgentInputs({ inputs, value, onChange, refs, credentials, onJsonError, empty = "This agent has no inputs.", label = "Inputs" }: {
  /** The agent's inputs; null when not known (then free name and value rows). */
  inputs: InputSpec[] | null;
  value: Obj; onChange: (next: Obj) => void;
  /** An example reference ("$form.field$", "{field}") when fields may take one. */
  refs?: string;
  /** Where Credential inputs list the vault from; none: typed folder, credential and attribute. */
  credentials?: (() => Promise<VaultRef[]>) | null;
  /** Told when the JSON text is not valid (null when it is again), so a dialog can hold its Save. */
  onJsonError?: (error: string | null) => void;
  empty?: string; label?: string;
}) {
  const [json, setJson] = useState<string | null>(null);
  const [bad, setBad] = useState<string | null>(null);
  const [round, setRound] = useState(0);   // a new round of editors after JSON was edited
  const [vault, setVault] = useState<VaultRef[] | null | undefined>(undefined);
  const needVault = !!inputs?.some((i) => i.type === "Credential");
  useEffect(() => {
    if (!needVault || vault !== undefined) return;
    if (!credentials) { setVault(null); return; }
    credentials().then(setVault, () => setVault(null));
  }, [needVault, credentials, vault]);

  const put = (k: string, v: unknown) => { const n = { ...value }; if (v === undefined) delete n[k]; else n[k] = v; onChange(n); };
  const fail = (e: string | null) => { setBad(e); onJsonError?.(e); };
  const typeJson = (t: string) => {
    setJson(t);
    if (!t.trim()) { fail(null); onChange({}); return; }
    let o: unknown;
    try { o = JSON.parse(t); } catch (e) { fail("Not valid JSON, so nothing is kept yet: " + (e instanceof Error ? e.message : String(e))); return; }
    if (!o || typeof o !== "object" || Array.isArray(o)) { fail("Write one JSON object, such as {\"name\": \"value\"}."); return; }
    fail(null); onChange(o as Obj);
  };
  const known = new Set((inputs ?? []).map((i) => i.name));
  const others = inputs ? Object.keys(value).filter((k) => !known.has(k)) : [];

  return (
    <div className="ui-inputs" role="group" aria-label={label}>
      <div className="ui-inputs-bar">
        <span className="ui-inputs-title">{label}</span>
        <Button kind="plain" disabled={json !== null && !!bad} title={json !== null && bad ? "Make the JSON valid first" : undefined}
          onClick={() => { if (json === null) setJson(Object.keys(value).length ? JSON.stringify(value, null, 2) : ""); else { setJson(null); setRound(round + 1); } }}>
          {json === null ? "Edit as JSON" : "Back to the fields"}</Button>
      </div>
      {json !== null ? <>
        <TextArea aria-label={label + " as JSON"} aria-invalid={!!bad} rows={6} className="ui-mono" value={json} placeholder='{"name": "value"}' spellCheck={false}
          onChange={(e) => typeJson(e.target.value)} />
        {bad && <p className="ui-inputs-error" role="alert">{bad}</p>}
      </> : inputs === null
        ? <PairRows key={round} value={value} onChange={onChange} refs={refs} />
        : <>
          {inputs.map((i) => <InputField key={i.name + ":" + round} spec={i} value={value[i.name]} refs={refs} vault={vault} onChange={(v) => put(i.name, v)} />)}
          {!inputs.length && <p className="ui-field-hint">{empty}</p>}
          {others.length > 0 && <p className="ui-field-hint">Also set, but not an input of this agent: {others.map((k) =>
            <span key={k} className="ui-inputs-other">{k} <Button kind="plain" aria-label={"Remove " + k} onClick={() => put(k, undefined)}>Remove</Button></span>)}</p>}
        </>}
    </div>
  );
}

/** One input: the field for its type, or (where references are allowed) a reference instead of a fixed value. */
function InputField({ spec, value, refs, vault, onChange }: { spec: InputSpec; value: unknown; refs?: string; vault: VaultRef[] | null | undefined; onChange: (v: unknown) => void }) {
  const t = spec.type ?? "String";
  const plain = t === "String" || t === "Any";
  const [byRef, setByRef] = useState(!!refs && !plain && typeof value === "string" && value !== "" && !(t === "DateTime" || t === "Date" || t === "Time"));
  const hint = [typeWord(t), spec.description, refs && (plain || byRef) ? "may use " + refs : ""].filter(Boolean).join(" · ");
  const lbl = spec.name + (spec.required ? " *" : "");
  const ph = spec.default !== undefined ? "Default: " + show(spec.default) : "";
  return <Field label={lbl} hint={hint}>{(id) => <div className="ui-inputs-field">
    {refs && !plain && <Select aria-label={"How " + spec.name + " is given"} className="ui-inputs-how" value={byRef ? "ref" : "fixed"}
      onChange={(e) => { setByRef(e.target.value === "ref"); onChange(undefined); }}
      options={[{ value: "fixed", label: "A fixed value" }, { value: "ref", label: "A reference" }]} />}
    {byRef || plain ? (spec.enum?.length && !byRef
      ? <EnumSelect id={id} spec={spec} value={value} onChange={onChange} />
      : <TextInput id={id} value={show(value)} placeholder={byRef ? refs : ph} onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)} />)
      : <TypedValue id={id} spec={spec} value={value} placeholder={ph} vault={vault} onChange={onChange} />}
  </div>}</Field>;
}

function EnumSelect({ id, spec, value, onChange }: { id: string; spec: InputSpec; value: unknown; onChange: (v: unknown) => void }) {
  const opts = (spec.enum ?? []).map((x) => ({ value: show(x), label: show(x) }));
  return <Select id={id} value={show(value)} onChange={(e) => onChange(e.target.value === "" ? undefined : spec.enum!.find((x) => show(x) === e.target.value))}
    options={[{ value: "", label: spec.default !== undefined ? "Default (" + show(spec.default) + ")" : "(not set)" }, ...opts]} />;
}

/** A fixed value in the shape of its type. */
export function TypedValue({ id, spec, value, placeholder, vault, onChange }:
  { id?: string; spec: InputSpec; value: unknown; placeholder?: string; vault?: VaultRef[] | null; onChange: (v: unknown) => void }) {
  const t = spec.type ?? "String";
  if (spec.enum?.length) return <EnumSelect id={id ?? ""} spec={spec} value={value} onChange={onChange} />;
  switch (t) {
    case "Number":
      return <TextInput id={id} type="number" value={typeof value === "number" ? String(value) : show(value)} placeholder={placeholder}
        onChange={(e) => onChange(e.target.value === "" ? undefined : Number.isNaN(Number(e.target.value)) ? e.target.value : Number(e.target.value))} />;
    case "Boolean":
      return <Select id={id} value={value === true ? "true" : value === false ? "false" : ""} onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value === "true")}
        options={[{ value: "", label: spec.default !== undefined ? "Default (" + (spec.default ? "Yes" : "No") + ")" : "(not set)" }, { value: "true", label: "Yes" }, { value: "false", label: "No" }]} />;
    case "Date": case "DateTime": case "Time": {
      const kind = t === "Date" ? "date" : t === "Time" ? "time" : "datetime-local";
      const fits = t === "Date" ? /^\d{4}-\d{2}-\d{2}$/ : t === "Time" ? /^\d{2}:\d{2}(:\d{2})?$/ : /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;
      const s = show(value);
      return <TextInput id={id} type={!s || fits.test(s) ? kind : "text"} value={s} placeholder={placeholder} onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)} />;
    }
    case "List": return <ListValue id={id} value={Array.isArray(value) ? value : []} onChange={(v) => onChange(v.length ? v : undefined)} />;
    case "Dictionary": case "Record":
      return <PairRows id={id} value={value && typeof value === "object" && !Array.isArray(value) ? value as Obj : {}} onChange={(v) => onChange(Object.keys(v).length ? v : undefined)} />;
    case "Table": return <TableValue id={id} value={value} onChange={onChange} />;
    case "Credential": return <VaultPicker id={id} value={credOf(value)} list={vault} onChange={(r) => onChange(r ? { $credential: r } : undefined)} />;
    default:
      return <TextInput id={id} value={show(value)} placeholder={placeholder} onChange={(e) => onChange(e.target.value === "" ? undefined : t === "Any" ? looseValue(e.target.value) : e.target.value)} />;
  }
}

/** A list: one item per row, with Add an item and Remove. */
export function ListValue({ id, value, onChange }: { id?: string; value: unknown[]; onChange: (v: unknown[]) => void }) {
  const [items, setItems] = useState<string[]>(value.map(show));
  const write = (next: string[]) => { setItems(next); onChange(next.filter((x) => x !== "").map(looseValue)); };
  return <div className="ui-rows" id={id}>
    {items.map((x, i) => <div key={i} className="ui-rows-line"><span className="ui-rows-n">{i + 1}</span>
      <TextInput aria-label={"Item " + (i + 1)} value={x} onChange={(e) => write(items.map((y, j) => j === i ? e.target.value : y))} />
      <Button kind="plain" aria-label={"Remove item " + (i + 1)} onClick={() => write(items.filter((_, j) => j !== i))}>Remove</Button></div>)}
    <Button kind="secondary" icon="plus" onClick={() => setItems([...items, ""])}>Add an item</Button>
  </div>;
}

/** Names and values, one pair per row (a Dictionary, or starting data whose names are not known). */
export function PairRows({ id, value, onChange, refs }: { id?: string; value: Obj; onChange: (v: Obj) => void; refs?: string }) {
  const [pairs, setPairs] = useState<[string, string][]>(Object.entries(value).map(([k, v]) => [k, show(v)]));
  const write = (next: [string, string][]) => { setPairs(next); onChange(Object.fromEntries(next.filter(([k]) => k.trim()).map(([k, v]) => [k.trim(), looseValue(v)]))); };
  return <div className="ui-rows" id={id}>
    {pairs.map(([k, v], i) => <div key={i} className="ui-rows-line">
      <TextInput aria-label={"Name " + (i + 1)} placeholder="name" value={k} onChange={(e) => write(pairs.map((p, j) => j === i ? [e.target.value, p[1]] : p))} />
      <TextInput aria-label={"Value " + (i + 1)} placeholder={refs ? "value or " + refs : "value"} value={v} onChange={(e) => write(pairs.map((p, j) => j === i ? [p[0], e.target.value] : p))} />
      <Button kind="plain" aria-label={"Remove " + (k || "row " + (i + 1))} onClick={() => write(pairs.filter((_, j) => j !== i))}>Remove</Button></div>)}
    {!pairs.length && <p className="ui-field-hint">Nothing yet.</p>}
    <Button kind="secondary" icon="plus" onClick={() => setPairs([...pairs, ["", ""]])}>Add a name and value</Button>
  </div>;
}

/** CSV text to columns and rows: the first line names the columns; quotes as spreadsheets write them. */
export function parseCsv(text: string): { columns: string[]; rows: string[][] } {
  const lines: string[][] = [];
  let row: string[] = [], cell = "", quoted = false;
  const sep = (text.split("\n")[0].match(/\t/g)?.length ?? 0) > 0 ? "\t" : text.split("\n")[0].includes(";") && !text.split("\n")[0].includes(",") ? ";" : ",";
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) { if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') quoted = false; else cell += c; }
    else if (c === '"') quoted = true;
    else if (c === sep) { row.push(cell); cell = ""; }
    else if (c === "\n" || c === "\r") { if (c === "\r" && text[i + 1] === "\n") i++; row.push(cell); cell = ""; if (row.some((x) => x !== "")) lines.push(row); row = []; }
    else cell += c;
  }
  row.push(cell); if (row.some((x) => x !== "")) lines.push(row);
  const [head = [], ...rows] = lines;
  return { columns: head.map((h) => h.trim()), rows: rows.map((r) => head.map((_, j) => r[j] ?? "")) };
}

/** A small table: column names, rows of cells, Add a column, Add a row, and Paste CSV. Stored as {columns, rows}. */
export function TableValue({ id, value, onChange }: { id?: string; value: unknown; onChange: (v: unknown) => void }) {
  const v0 = value && typeof value === "object" && Array.isArray((value as { columns?: unknown }).columns) ? value as { columns: unknown[]; rows?: unknown[][] } : null;
  const [cols, setCols] = useState<string[]>(v0 ? v0.columns.map(show) : []);
  const [rows, setRows] = useState<string[][]>(v0 ? (v0.rows ?? []).map((r) => (Array.isArray(r) ? r : []).map(show)) : []);
  const [paste, setPaste] = useState<string | null>(null);
  const write = (c: string[], r: string[][]) => { setCols(c); setRows(r); onChange(c.length ? { columns: c, rows: r.map((x) => c.map((_, j) => looseValue(x[j] ?? ""))) } : undefined); };
  return <div className="ui-rows" id={id}>
    {paste !== null ? <>
      <TextArea aria-label="Table as CSV" rows={5} className="ui-mono" value={paste} placeholder={"Name,Amount\nAcme,1200"} onChange={(e) => setPaste(e.target.value)} />
      <span className="ui-rows-line">
        <Button kind="secondary" disabled={!paste.trim()} onClick={() => { const t = parseCsv(paste); write(t.columns, t.rows); setPaste(null); }}>Use this table</Button>
        <Button kind="plain" onClick={() => setPaste(null)}>Cancel</Button></span>
      <p className="ui-field-hint">The first line names the columns. Copy cells from a spreadsheet, or write values separated by commas.</p>
    </> : <>
      {cols.length > 0 && <div className="ui-table-edit"><table aria-label="Table value">
        <thead><tr><th scope="col"><span className="ui-sr">Row</span></th>{cols.map((c, j) => <th key={j} scope="col">
          <TextInput aria-label={"Column " + (j + 1) + " name"} value={c} onChange={(e) => write(cols.map((x, k) => k === j ? e.target.value : x), rows)} />
          <Button kind="plain" aria-label={"Remove column " + (c || j + 1)} onClick={() => write(cols.filter((_, k) => k !== j), rows.map((r) => r.filter((_, k) => k !== j)))}>Remove</Button></th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i}><td><Button kind="plain" aria-label={"Remove row " + (i + 1)} onClick={() => write(cols, rows.filter((_, k) => k !== i))}>Remove</Button></td>
          {cols.map((c, j) => <td key={j}><TextInput aria-label={(c || "Column " + (j + 1)) + ", row " + (i + 1)} value={r[j] ?? ""}
            onChange={(e) => write(cols, rows.map((x, k) => k === i ? cols.map((_, m) => m === j ? e.target.value : x[m] ?? "") : x))} /></td>)}</tr>)}</tbody>
      </table></div>}
      <span className="ui-rows-line">
        <Button kind="secondary" icon="plus" onClick={() => write([...cols, "Column " + (cols.length + 1)], rows.map((r) => [...r, ""]))}>Add a column</Button>
        <Button kind="secondary" icon="plus" disabled={!cols.length} onClick={() => write(cols, [...rows, cols.map(() => "")])}>Add a row</Button>
        <Button kind="plain" onClick={() => setPaste("")}>Paste CSV</Button></span>
    </>}
  </div>;
}

/** A vault credential: folder, then credential, then attribute, from the vault's list; typed when there is no list. */
export function VaultPicker({ id, value, list, onChange }: { id?: string; value: string | null; list: VaultRef[] | null | undefined; onChange: (ref: string | null) => void }) {
  const [parts, setParts] = useState<[string, string, string]>(() => { const [f = "", c = "", a = ""] = (value ?? "").split("/"); return [f, c, a]; });
  const [f, c, a] = parts;
  const set = (p: [string, string, string]) => { setParts(p); onChange(p.every((x) => x.trim()) ? p.map((x) => x.trim()).join("/") : null); };
  const uniq = (xs: string[]) => [...new Set(xs)].sort();
  if (list === undefined) return <p className="ui-field-hint" role="status">Reading the vault…</p>;
  if (!list || !list.length) return <div className="ui-vault" role="group" aria-label="Vault reference">
    <TextInput id={id} aria-label="Vault folder" placeholder="Vault folder" value={f} onChange={(e) => set([e.target.value, c, a])} />
    <TextInput aria-label="Credential" placeholder="Credential" value={c} onChange={(e) => set([f, e.target.value, a])} />
    <TextInput aria-label="Attribute" placeholder="Attribute, e.g. password" value={a} onChange={(e) => set([f, c, e.target.value])} />
    <span className="ui-field-hint">{list ? "No credentials you may use were found" : "The vault could not be read here"}: type the folder, credential and attribute.</span>
  </div>;
  const folders = uniq(list.map((r) => r.locker));
  const creds = uniq(list.filter((r) => r.locker === f).map((r) => r.name));
  const attrs = uniq(list.filter((r) => r.locker === f && r.name === c).map((r) => r.attribute));
  const keep = (xs: string[], x: string) => x && !xs.includes(x) ? [...xs, x] : xs;
  return <div className="ui-vault" role="group" aria-label="Vault reference">
    <Select id={id} aria-label="Vault folder" value={f} onChange={(e) => set([e.target.value, "", ""])}
      options={[{ value: "", label: "Vault folder…" }, ...keep(folders, f).map((x) => ({ value: x, label: x }))]} />
    <Select aria-label="Credential" value={c} disabled={!f} onChange={(e) => { const n = e.target.value; const as = list.filter((r) => r.locker === f && r.name === n).map((r) => r.attribute); set([f, n, as.length === 1 ? as[0] : ""]); }}
      options={[{ value: "", label: "Credential…" }, ...keep(creds, c).map((x) => ({ value: x, label: x }))]} />
    <Select aria-label="Attribute" value={a} disabled={!c} onChange={(e) => set([f, c, e.target.value])}
      options={[{ value: "", label: "Attribute…" }, ...keep(attrs, a).map((x) => ({ value: x, label: x }))]} />
  </div>;
}
