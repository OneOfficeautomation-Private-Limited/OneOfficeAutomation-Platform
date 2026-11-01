import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, GlobalValue } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextArea, TextInput, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";

/**
 * Manage > Shared Global values, built as AA's Global values (checked against AA's docs, UiPath's assets and Blue Prism's
 * environment variables, 31 Oct 2026): each value with its type (text, number, true/false, list or record, as AA's and
 * UiPath's typed values), whether people may set their own (AA's "can be changed"), and your own value. Agents read a
 * value as $global.name; a person's runs use their own value where they set one.
 * Review round 2 (31 Oct 2026): a menu per value (Edit, Set my own value, Copy the name, Delete asked first), your value on
 * one line, and rows you cannot open shown as such.
 */
type Kind = "text" | "number" | "boolean" | "json";
const KIND: Record<Kind, string> = { text: "Text", number: "Number", boolean: "True/false", json: "List or record (JSON)" };
const kindOf = (v: unknown): Kind => typeof v === "number" ? "number" : typeof v === "boolean" ? "boolean" : typeof v === "string" ? "text" : "json";
const show = (v: unknown) => v === undefined ? "" : typeof v === "string" ? v : JSON.stringify(v);
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const HELP: HelpTopic = { title: "Shared Global values", about: "Values every agent can read as $global.name, such as a folder or an address, "
  + "kept in one place. Where people may change a value, their runs use their own value.",
  items: [
    { name: "Create value", text: "Give it a name (letters, digits and _), a type and a value. The name cannot change later. "
      + "It needs the Manage shared global values permission." },
    { name: "Type", text: "Text, a number, true/false, or a list or record written as JSON, such as [1, 2] or {\"a\": 1}." },
    { name: "People may change", text: "When on, each person may set their own value and runs that run as them use it. "
      + "Turning it off removes everyone's own values." },
    { name: "Your value", text: "Your own value of a value people may change, or Uses the shared one when you have not set one." },
    { name: "Set my own value", text: "Type your own value; Use the shared one goes back to the shared value." },
    { name: "Show", text: "Every value, only those people may change, or only those you set your own value of." },
    { name: "Actions (⋯)", text: "Edit, set your own value, copy the name as $global.name to use in an agent, or delete. "
      + "Deleting removes everyone's own values too, and agents reading it get nothing." },
  ] };

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
  const [ask, setAsk] = useState<GlobalValue | null>(null);   // a value to delete, asked first
  const load = useCallback(async () => { try { setList(await api.globals()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((g) => (!q.trim() || (g.name + " " + show(g.value)).toLowerCase().includes(q.trim().toLowerCase()))
    && (!only || (only === "changeable" ? g.changeable : g.changeable && g.my_value !== undefined))), [list, q, only]);

  const openOf = (g: GlobalValue) => manage ? { kind: "edit" as const, g } : g.changeable ? { kind: "mine" as const, g } : null;
  const copy = (g: GlobalValue) => (navigator.clipboard?.writeText("$global." + g.name) ?? Promise.reject(new Error("no clipboard")))
    .then(() => onNotice("Copied $global." + g.name + "."), () => setError("Copying is not allowed here: type $global." + g.name + " in the agent."));
  const del = (g: GlobalValue) => api.deleteGlobal(g.name).then(() => { onNotice("Deleted $global." + g.name + "."); setAsk(null); load(); }, (e) => setError(message(e)));
  const rowMenu = (g: GlobalValue) => [
    ...(manage ? [{ key: "edit", label: "Edit", onSelect: () => setDialog({ kind: "edit", g }) }] : []),
    ...(g.changeable ? [{ key: "mine", label: "Set my own value", onSelect: () => setDialog({ kind: "mine", g }) }] : []),
    { key: "copy", label: "Copy the name", onSelect: () => { copy(g); } },
    ...(manage ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setAsk(g) }] : []),
  ];

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-glob-title">
      <header className="cc-list-head">
        <h1 id="cc-glob-title">Shared Global values</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length} shared value{list.length === 1 ? "" : "s"} · {list.filter((g) => g.changeable).length} people may change</span>}
        <span className="ov-fill" />
        {manage && <Button kind="primary" onClick={() => setDialog({ kind: "edit", g: null })}>Create value</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-list-search"><TextInput type="search" aria-label="Search shared values" placeholder="Search names and values" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        <span className="cc-chip"><Select aria-label="Show" value={only} onChange={(e) => setOnly(e.target.value)} options={[{ value: "", label: "Every value" }, { value: "changeable", label: "People may change" }, { value: "mine", label: "I set my own" }]} /></span>
      </div>
      <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {ask && <div className="cc-list-bulk" role="alert"><span>Delete $global.{ask.name}? Everyone's own values of it go too; agents reading it get nothing.</span>
          <Button kind="danger" onClick={() => del(ask)}>Delete</Button><Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Shared values list">
          <thead><tr><th scope="col">Name in agents</th><th scope="col">Type</th><th scope="col">Value</th><th scope="col">People may change</th><th scope="col">Your value</th><th scope="col">Updated</th>
            <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
          <tbody>
            {shown.map((g) => (
              <tr key={g.name} className={openOf(g) ? undefined : "is-static"} onClick={rowClick(() => setDialog(openOf(g)))}>
                <td className="ui-mono">$global.{g.name}</td><td>{KIND[kindOf(g.value)]}</td><td className="ui-mono cc-clip">{show(g.value)}</td>
                <td>{g.changeable ? "Yes" : "No"}</td>
                <td className="cc-clip">{!g.changeable ? <span className="cc-none">–</span> : g.my_value === undefined ? <span className="cc-none">Uses the shared one</span> : <span className="ui-mono">{show(g.my_value)}</span>}</td>
                <td>{when(g.updated)}{g.updated_by ? " by " + g.updated_by : ""}</td>
                <td className="cc-list-menu"><RowMenu label={"Actions for $global." + g.name} items={rowMenu(g)} /></td>
              </tr>
            ))}
            {list && !shown.length && <tr className="is-static"><td colSpan={7} className="cc-none">{list.length ? "No values match." : "No shared values yet."}</td></tr>}
            {!list && !error && <tr className="is-static"><td colSpan={7} className="cc-none" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}</span></div>}</div>
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
      <p className="cc-list-hint">Agents read it as <code>$global.{name || "name"}</code>.</p>
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
      <p className="cc-list-hint">Shared value: <span className="ui-mono">{show(g.value)}</span>. Runs that run as you use your value instead.</p>
      <ValueInput kind={kind} text={text} onChange={setText} label={"Your value (" + KIND[kind].toLowerCase() + ")"} />
    </Dialog>
  );
}
