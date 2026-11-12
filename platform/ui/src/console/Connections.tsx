import { SignInFields, type SignInBags } from "../studio/SignInFields";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { ConnectionKind, CrApi, CrUser, SavedConnection } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextInput, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick, when } from "./ListParts";

/**
 * Manage > Connections, after UiPath's Integration Service connections (checked against its docs; AA keeps a connector's
 * sign-in in the Credential vault, which is how secrets are held here too; 31 Oct 2026): a connector's address and
 * settings kept once, chosen by name on agent steps. Each shows its toolkit, settings and which secrets come from the
 * vault, with a filter by toolkit. Secrets are never typed here: a connection keeps only vault references.
 * Review round 2 (31 Oct 2026): one line per connection (its description on hover), a menu per connection (Edit, Copy the
 * name, Delete asked first), and rows you cannot open shown as such.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

const HELP: HelpTopic = { title: "Connections", about: "A connector's address and sign-in, kept once and chosen by name on agent steps. "
  + "Secrets stay in the Credential vault; a connection keeps only their references.",
  items: [
    { name: "Create connection", text: "Pick the toolkit, give the connection a name and fill in its settings. The name and toolkit cannot change later. "
      + "It needs the Manage shared global values permission." },
    { name: "Settings and sign-in", text: "In the form: plain settings such as an address are kept as values; "
      + "secrets are written as vault references (folder/credential/attribute). Leave one empty to set it on each step instead." },
    { name: "Toolkit", text: "Show only the connections of one toolkit, such as ServiceNow." },
    { name: "Settings", text: "The values the connection keeps, such as the address." },
    { name: "Secrets from the vault", text: "Which vault credential each secret comes from. "
      + "Secrets are read from the vault when the agent runs, as the person it runs as." },
    { name: "Updated", text: "When the connection was last saved, and by whom." },
    { name: "Actions (⋯)", text: "Edit the connection, copy its name to choose it on an agent step, or delete it. "
      + "Agent steps that use a deleted connection fail until another is chosen." },
  ] };

export function Connections({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<SavedConnection[] | null>(null);
  const [kinds, setKinds] = useState<ConnectionKind[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [dialog, setDialog] = useState<null | { c: SavedConnection | null }>(null);
  const manage = user.permissions.includes("globals.manage");
  const [ask, setAsk] = useState<SavedConnection | null>(null);   // a connection to delete, asked first
  const load = useCallback(async () => {
    try { const [c, k] = await Promise.all([api.savedConnections(), api.connectionKinds()]); setList(c); setKinds(k); setError(null); } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const kindName = (p: string) => kinds.find((k) => k.package === p)?.name ?? p;
  const shown = useMemo(() => (list ?? []).filter((c) => (!kind || c.package === kind)
    && (!q.trim() || (c.name + " " + (c.description ?? "") + " " + kindName(c.package)).toLowerCase().includes(q.trim().toLowerCase()))), [list, q, kind, kinds]);   // eslint-disable-line react-hooks/exhaustive-deps
  const copy = (c: SavedConnection) => (navigator.clipboard?.writeText(c.name) ?? Promise.reject(new Error("no clipboard")))
    .then(() => onNotice("Copied " + c.name + "."), () => setError("Copying is not allowed here: choose " + c.name + " on the agent step."));
  const del = (c: SavedConnection) => api.deleteConnection(c.name).then(() => { onNotice("Deleted the connection " + c.name + "."); setAsk(null); load(); }, (e) => setError(message(e)));
  const rowMenu = (c: SavedConnection) => [
    ...(manage ? [{ key: "edit", label: "Edit", onSelect: () => setDialog({ c }) }] : []),
    { key: "copy", label: "Copy the name", onSelect: () => { copy(c); } },
    ...(manage ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setAsk(c) }] : []),
  ];

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-conn-title">
      <header className="cc-list-head">
        <h1 id="cc-conn-title">Connections</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length} connection{list.length === 1 ? "" : "s"}</span>}
        <span className="ov-fill" />
        {manage && <Button kind="primary" disabled={!kinds.length} onClick={() => setDialog({ c: null })}>Create connection</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-list-search"><TextInput type="search" aria-label="Search connections" placeholder="Search connections" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        <span className="cc-chip"><Select aria-label="Toolkit" value={kind} onChange={(e) => setKind(e.target.value)} options={[{ value: "", label: "Every toolkit" }, ...kinds.map((k) => ({ value: k.package, label: k.name }))]} /></span>
      </div>
      <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {ask && <div className="cc-list-bulk" role="alert"><span>Delete the connection {ask.name}? Agent steps that use it fail until another is chosen.</span>
          <Button kind="danger" onClick={() => del(ask)}>Delete</Button><Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Connections list">
          <thead><tr><th scope="col">Connection</th><th scope="col">Toolkit</th><th scope="col">Settings</th><th scope="col">Secrets from the vault</th><th scope="col">Updated</th>
            <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
          <tbody>
            {shown.map((c) => (
              <tr key={c.name} onClick={rowClick(() => manage && setDialog({ c }))} className={manage ? undefined : "is-static"}>
                <td className="cc-clip" title={c.description || undefined}>{c.name}</td>
                <td>{kindName(c.package)}</td>
                <td className="cc-clip">{Object.entries(c.values ?? {}).map(([n, v]) => n + ": " + String(v)).join("; ")}</td>
                <td className="ui-mono cc-clip">{Object.entries(c.credentials ?? {}).map(([n, v]) => n + " → " + v).join("; ")}</td>
                <td>{when(c.updated)}{c.updated_by ? " by " + c.updated_by : ""}</td>
                <td className="cc-list-menu"><RowMenu label={"Actions for " + c.name} items={rowMenu(c)} /></td>
              </tr>
            ))}
            {list && !shown.length && <tr className="is-static"><td colSpan={6} className="cc-none">{list.length ? "No connections match." : "No connections yet. Create one so agent steps do not repeat an address and sign-in."}</td></tr>}
            {!list && !error && <tr className="is-static"><td colSpan={6} className="cc-none" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}</span></div>}</div>
      {dialog && <ConnectionForm key={dialog.c?.name ?? "new"} api={api} c={dialog.c} kinds={kinds} onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
    </section>
  );
}

/** A connection: its toolkit's inputs, settings as values and secrets as vault references; empty ones are set on each step. */
function ConnectionForm({ api, c, kinds, onClose, onSaved }: { api: CrApi; c: SavedConnection | null; kinds: ConnectionKind[]; onClose: () => void; onSaved: (t: string) => void }) {
  const [name, setName] = useState(c?.name ?? "");
  const [description, setDescription] = useState(c?.description ?? "");
  const [pkg, setPkg] = useState(c?.package ?? kinds[0]?.package ?? "");
  const k = kinds.find((x) => x.package === pkg);
  const [v, setV] = useState<Record<string, string>>(() => Object.fromEntries([...Object.entries(c?.values ?? {}).map(([n, x]) => [n, String(x)]), ...Object.entries(c?.credentials ?? {})]));
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  // A1: the sign-in that renews the access token, kept with sign_in_ names next to the toolkit's own settings
  const [signIn, setSignIn] = useState<SignInBags>(() => ({
    values: Object.fromEntries(Object.entries(c?.values ?? {}).filter(([n]) => n.startsWith("sign_in_"))),
    credentials: Object.fromEntries(Object.entries(c?.credentials ?? {}).filter(([n]) => n.startsWith("sign_in_"))) }));
  const save = () => {
    const values: Record<string, string> = {}, credentials: Record<string, string> = {};
    for (const i of k?.inputs ?? []) { const x = (v[i.name] ?? "").trim(); if (x) (i.credential ? credentials : values)[i.name] = x; }
    if (k?.sign_in) { for (const [n, x] of Object.entries(signIn.values)) values[n] = String(x); Object.assign(credentials, signIn.credentials); }
    api.saveConnection(name.trim(), { package: pkg, description: description.trim(), values, credentials }).then(() => onSaved("Saved the connection " + name.trim() + "."), (e) => setError(message(e)));
  };
  return (
    <Dialog open title={c ? "Connection " + c.name : "Create connection"} width={620} onClose={onClose} footer={<>
      {c && <Button kind="danger" onClick={() => setConfirm(true)}>Delete</Button>}
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!name.trim() || !pkg} onClick={save}>{c ? "Save changes" : "Create connection"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirm && <div className="st-alert" role="alert"><span>Delete {c!.name}? Agent steps that use it fail until another is chosen.</span>
        <Button kind="danger" onClick={() => api.deleteConnection(c!.name).then(() => onSaved("Deleted the connection " + c!.name + "."), (e) => setError(message(e)))}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={name} disabled={!!c} placeholder="ServiceNow production" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Toolkit">{(id) => <Select id={id} value={pkg} disabled={!!c} onChange={(e) => setPkg(e.target.value)} options={kinds.map((x) => ({ value: x.package, label: x.name }))} />}</Field>
      </div>
      <Field label="Description (what it is for)">{(id) => <TextInput id={id} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <h3 className="cc-log-title">Settings and sign-in</h3>
      {k ? <div className="cc-grid2">{k.inputs.map((i) => <Field key={i.name} label={i.name + (i.credential ? " (vault reference)" : "")}>{(id) =>
        <TextInput id={id} value={v[i.name] ?? ""} placeholder={i.credential ? "vault folder/credential/attribute" : i.description ?? ""} title={i.description} onChange={(e) => setV({ ...v, [i.name]: e.target.value })} />}</Field>)}</div>
        : <p className="st-muted">No toolkit takes a connection.</p>}
      {k?.sign_in && <><h3 className="cc-log-title">Sign-in that renews itself</h3>
        <SignInFields bags={signIn} onChange={setSignIn} />
        <p className="cc-list-hint">With a sign-in, leave access_token empty: the runner gets the token when a step needs it and renews it before it runs out.</p></>}
      <p className="cc-list-hint">Leave a setting empty to set it on each step instead. Secrets are read from the vault when the agent runs, as the person it runs as.</p>
    </Dialog>
  );
}
