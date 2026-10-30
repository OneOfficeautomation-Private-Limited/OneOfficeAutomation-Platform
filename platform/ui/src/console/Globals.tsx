import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, GlobalValue } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * Manage > Shared Global values, built as AA's Global values (checked against AA's docs, UiPath's assets and Blue Prism's
 * environment variables, 31 Oct 2026): each value with its type (text, number, true/false, list or record, as AA's and
 * UiPath's typed values), whether people may set their own (AA's "can be changed"), and your own value. Agents read a
 * value as $global.name; a person's runs use their own value where they set one.
 */
type Kind = "text" | "number" | "boolean" | "json";
const KIND: Record<Kind, string> = { text: "Text", number: "Number", boolean: "True/false", json: "List or record (JSON)" };
const kindOf = (v: unknown): Kind => typeof v === "number" ? "number" : typeof v === "boolean" ? "boolean" : typeof v === "string" ? "text" : "json";
const show = (v: unknown) => v === undefined ? "" : typeof v === "string" ? v : JSON.stringify(v);
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
/** The value typed in, read by its kind; throws with a reason when it does not fit. */
function read(kind: Kind, text: string): unknown {
  if (kind === "text") return text;
  if (kind === "number") { if (text.trim() === "" || isNaN(Number(text))) throw new Error("The value is not a number."); return Number(text); }
  if (kind === "boolean") return text === "true";
  try { return JSON.parse(text); } catch { throw new Error("The value is not JSON (a list like [1, 2] or a record like {\"a\": 1})."); }
}

export function Globals({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<GlobalValue[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [only, setOnly] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "edit"; g: GlobalValue | null } | { kind: "mine"; g: GlobalValue }>(null);
  const manage = user.permissions.includes("globals.manage");
  const load = useCallback(async () => { try { setList(await api.globals()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((g) => (!q.trim() || (g.name + " " + show(g.value)).toLowerCase().includes(q.trim().toLowerCase()))
    && (!only || (only === "changeable" ? g.changeable : g.changeable && g.my_value !== undefined))), [list, q, only]);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-glob-title">
      <header className="st-page-head">
        <div><h1 id="cc-glob-title">Shared Global values</h1><p>Values every agent can read as $global.name. Where people may change a value, their runs use their own value.</p></div>
        {manage && <Button kind="primary" onClick={() => setDialog({ kind: "edit", g: null })}>Create value</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search shared values" placeholder="Search names and values" value={q} onChange={(e) => setQ(e.target.value)} />
        <Select aria-label="Show" value={only} onChange={(e) => setOnly(e.target.value)} options={[{ value: "", label: "Every value" }, { value: "changeable", label: "People may change" }, { value: "mine", label: "I set my own" }]} />
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Shared values list">
          <thead><tr><th scope="col">Name in agents</th><th scope="col">Type</th><th scope="col">Value</th><th scope="col">People may change</th><th scope="col">Your value</th><th scope="col">Updated</th></tr></thead>
          <tbody>
            {shown.map((g) => (
              <tr key={g.name} onClick={() => setDialog(manage ? { kind: "edit", g } : g.changeable ? { kind: "mine", g } : null)}>
                <td className="ui-mono">$global.{g.name}</td><td>{KIND[kindOf(g.value)]}</td><td className="ui-mono cc-clip">{show(g.value)}</td>
                <td>{g.changeable ? "Yes" : "No"}</td>
                <td>{!g.changeable ? "" : g.my_value === undefined ? <span className="st-muted">Uses the shared one</span> : <span className="ui-mono">{show(g.my_value)}</span>}
                  {g.changeable && <Button kind="plain" onClick={(e) => { e.stopPropagation(); setDialog({ kind: "mine", g }); }}>My value</Button>}</td>
                <td>{when(g.updated)}{g.updated_by ? " by " + g.updated_by : ""}</td>
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={6} className="st-muted">{list.length ? "No values match." : "No shared values yet."}</td></tr>}
            {!list && !error && <tr><td colSpan={6} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {dialog?.kind === "edit" && <EditGlobal key={dialog.g?.name ?? "new"} api={api} g={dialog.g} onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "mine" && <MyValue api={api} g={dialog.g} onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
    </section>
  );
}

/** One value's input by its kind: text, number, true/false or JSON. */
function ValueInput({ kind, text, onChange, label }: { kind: Kind; text: string; onChange: (t: string) => void; label: string }) {
  return <Field label={label}>{(id) => kind === "boolean" ? <Select id={id} value={text === "true" ? "true" : "false"} onChange={(e) => onChange(e.target.value)} options={[{ value: "true", label: "True" }, { value: "false", label: "False" }]} />
    : kind === "json" ? <TextArea id={id} rows={4} value={text} placeholder='["a", "b"]  or  {"a": 1}' onChange={(e) => onChange(e.target.value)} />
      : <TextInput id={id} type={kind === "number" ? "number" : "text"} value={text} onChange={(e) => onChange(e.target.value)} />}</Field>;
}

function EditGlobal({ api, g, onClose, onSaved }: { api: CrApi; g: GlobalValue | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [name, setName] = useState(g?.name ?? "");
  const [kind, setKind] = useState<Kind>(g ? kindOf(g.value) : "text");
  const [text, setText] = useState(g ? (kindOf(g.value) === "json" ? JSON.stringify(g.value, null, 2) : show(g.value)) : "");
  const [changeable, setChangeable] = useState(g?.changeable ?? false);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const save = () => { try { api.saveGlobal(name.trim(), read(kind, text), changeable).then(() => onSaved("Saved $global." + name.trim() + "."), (e) => setError(message(e))); } catch (e) { setError(message(e)); } };
  return (
    <Dialog open title={g ? "Shared value " + g.name : "Create shared value"} width={560} onClose={onClose} footer={<>
      {g && <Button kind="danger" onClick={() => setConfirm(true)}>Delete</Button>}
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!/^[A-Za-z_][A-Za-z0-9_]{0,79}$/.test(name.trim())} onClick={save}>{g ? "Save changes" : "Create value"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirm && <div className="st-alert" role="alert"><span>Delete {g!.name}? Everyone's own values of it go too; agents reading it get nothing.</span>
        <Button kind="danger" onClick={() => api.deleteGlobal(g!.name).then(() => onSaved("Deleted $global." + g!.name + "."), (e) => setError(message(e)))}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      <div className="cc-grid2">
        <Field label="Name (letters, digits and _)">{(id) => <TextInput id={id} value={name} disabled={!!g} placeholder="reports_folder" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Type">{(id) => <Select id={id} value={kind} onChange={(e) => { setKind(e.target.value as Kind); setText(e.target.value === "boolean" ? "false" : ""); }} options={Object.entries(KIND).map(([value, label]) => ({ value, label }))} />}</Field>
      </div>
      <ValueInput kind={kind} text={text} onChange={setText} label="Value" />
      <label className="st-check-line"><input type="checkbox" checked={changeable} onChange={(e) => setChangeable(e.target.checked)} /> People may set their own value (their runs use it instead)</label>
      {g?.changeable && !changeable && <p className="st-muted">Turning this off removes everyone's own values.</p>}
      <p className="st-muted">Agents read it as <code>$global.{name || "name"}</code>.</p>
    </Dialog>
  );
}

/** A person's own value of a value people may change; their runs use it instead of the shared one. */
function MyValue({ api, g, onClose, onSaved }: { api: CrApi; g: GlobalValue; onClose: () => void; onSaved: (t: string) => void }) {
  const kind = kindOf(g.value);
  const start = g.my_value !== undefined ? g.my_value : g.value;
  const [text, setText] = useState(kind === "json" ? JSON.stringify(start, null, 2) : show(start));
  const [error, setError] = useState<string | null>(null);
  const save = () => { try { api.setMyGlobal(g.name, read(kind, text)).then(() => onSaved("Saved your value of $global." + g.name + "."), (e) => setError(message(e))); } catch (e) { setError(message(e)); } };
  return (
    <Dialog open title={"Your value of " + g.name} width={520} onClose={onClose} footer={<>
      {g.my_value !== undefined && <Button kind="secondary" onClick={() => api.clearMyGlobal(g.name).then(() => onSaved("Your runs use the shared $global." + g.name + " again."), (e) => setError(message(e)))}>Use the shared one</Button>}
      <span className="st-bar-fill" /><Button kind="primary" onClick={save}>Save my value</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">Shared value: <span className="ui-mono">{show(g.value)}</span>. Runs that run as you use your value instead.</p>
      <ValueInput kind={kind} text={text} onChange={setText} label={"Your value (" + KIND[kind].toLowerCase() + ")"} />
    </Dialog>
  );
}
