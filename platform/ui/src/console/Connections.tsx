import { useCallback, useEffect, useMemo, useState } from "react";
import type { ConnectionKind, CrApi, CrUser, SavedConnection } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * Manage > Connections, after UiPath's Integration Service connections (checked against its docs; AA keeps a connector's
 * sign-in in the Credential vault, which is how secrets are held here too; 31 Oct 2026): a connector's address and
 * settings kept once, chosen by name on agent steps. Each shows its toolkit, settings and which secrets come from the
 * vault, with a filter by toolkit. Secrets are never typed here: a connection keeps only vault references.
 */
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function Connections({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<SavedConnection[] | null>(null);
  const [kinds, setKinds] = useState<ConnectionKind[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [dialog, setDialog] = useState<null | { c: SavedConnection | null }>(null);
  const manage = user.permissions.includes("globals.manage");
  const load = useCallback(async () => {
    try { const [c, k] = await Promise.all([api.savedConnections(), api.connectionKinds()]); setList(c); setKinds(k); setError(null); } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const kindName = (p: string) => kinds.find((k) => k.package === p)?.name ?? p;
  const shown = useMemo(() => (list ?? []).filter((c) => (!kind || c.package === kind)
    && (!q.trim() || (c.name + " " + (c.description ?? "") + " " + kindName(c.package)).toLowerCase().includes(q.trim().toLowerCase()))), [list, q, kind, kinds]);   // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <section className="st-page cc-home" aria-labelledby="cc-conn-title">
      <header className="st-page-head">
        <div><h1 id="cc-conn-title">Connections</h1><p>A connector's address and sign-in, kept once and chosen by name on agent steps. Secrets stay in the Credential vault; a connection keeps only their references.</p></div>
        {manage && <Button kind="primary" disabled={!kinds.length} onClick={() => setDialog({ c: null })}>Create connection</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search connections" placeholder="Search connections" value={q} onChange={(e) => setQ(e.target.value)} />
        <Select aria-label="Toolkit" value={kind} onChange={(e) => setKind(e.target.value)} options={[{ value: "", label: "Every toolkit" }, ...kinds.map((k) => ({ value: k.package, label: k.name }))]} />
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Connections list">
          <thead><tr><th scope="col">Connection</th><th scope="col">Toolkit</th><th scope="col">Settings</th><th scope="col">Secrets from the vault</th><th scope="col">Updated</th></tr></thead>
          <tbody>
            {shown.map((c) => (
              <tr key={c.name} onClick={() => manage && setDialog({ c })} className={manage ? undefined : "is-static"}>
                <td>{c.name}{c.description && <div className="st-muted">{c.description}</div>}</td>
                <td>{kindName(c.package)}</td>
                <td className="cc-clip">{Object.entries(c.values ?? {}).map(([n, v]) => n + ": " + String(v)).join("; ")}</td>
                <td className="ui-mono cc-clip">{Object.entries(c.credentials ?? {}).map(([n, v]) => n + " → " + v).join("; ")}</td>
                <td>{when(c.updated)}{c.updated_by ? " by " + c.updated_by : ""}</td>
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={5} className="st-muted">{list.length ? "No connections match." : "No connections yet. Create one so agent steps do not repeat an address and sign-in."}</td></tr>}
            {!list && !error && <tr><td colSpan={5} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
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
  const save = () => {
    const values: Record<string, string> = {}, credentials: Record<string, string> = {};
    for (const i of k?.inputs ?? []) { const x = (v[i.name] ?? "").trim(); if (x) (i.credential ? credentials : values)[i.name] = x; }
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
      <p className="st-muted">Leave a setting empty to set it on each step instead. Secrets are read from the vault when the agent runs, as the person it runs as.</p>
    </Dialog>
  );
}
