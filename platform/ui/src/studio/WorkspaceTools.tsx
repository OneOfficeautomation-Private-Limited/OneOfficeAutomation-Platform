import { useEffect, useState } from "react";
import type { ConnectorDef, ConnectorOperation, ConnectorSummary, FlowStep, PackageManifest, SavedConnection, StudioApi } from "../api/client";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import { connectionInputs } from "./stepKinds";

/**
 * The workspace's saved connections: a connector's address and settings kept once under a name, with vault
 * references (folder/credential/attribute) for its secrets, never the secrets. Steps choose a connection by name;
 * local runs read them from connections.json in the workspace.
 */
export function SavedConnectionsDialog({ api, packages, pkgId, onClose, onChanged, onNotice, onError }:
  { api: StudioApi; packages: PackageManifest[]; pkgId?: string; onClose: () => void; onChanged?: () => void; onNotice: (t: string) => void; onError: (e: unknown) => void }) {
  const [list, setList] = useState<SavedConnection[] | null>(null);
  const [editing, setEditing] = useState<(SavedConnection & { isNew?: boolean }) | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const toolkits = packages.filter((p) => connectionInputs(p).size > 0).sort((a, b) => a.name.localeCompare(b.name));
  const load = () => api.connections().then(setList, (e) => { setList([]); onError(e); });
  useEffect(() => { load(); }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  const nameOf = (id: string) => toolkits.find((p) => p.id === id)?.name ?? id;

  const save = async () => {
    if (!editing) return;
    if (!editing.name.trim()) { onNotice("Give the connection a name."); return; }
    try {
      await api.saveConnection({ ...editing, name: editing.name.trim() });
      onNotice("Saved the connection " + editing.name.trim() + ".");
      setEditing(null); await load(); onChanged?.();
    } catch (e) { onError(e); }
  };
  const remove = async (name: string) => {
    try { await api.deleteConnection(name); onNotice("Deleted the connection " + name + "."); setDeleting(null); await load(); onChanged?.(); }
    catch (e) { onError(e); }
  };

  let body;
  if (editing) {
    const pkg = toolkits.find((p) => p.id === editing.package) ?? toolkits[0];
    const spec = (n: string) => pkg?.actions.map((a) => a.inputs?.properties?.[n]).find(Boolean);
    const set = (n: string, secret: boolean, v: string) => {
      const bag = { ...(secret ? editing.credentials : editing.values) } as Record<string, unknown>;
      if (v.trim()) bag[n] = v.trim(); else delete bag[n];
      setEditing(secret ? { ...editing, credentials: bag as Record<string, string> } : { ...editing, values: bag });
    };
    body = (
      <div className="st-conn-edit">
        <Field label="Name">{(id) => <TextInput id={id} value={editing.name} disabled={!editing.isNew} placeholder="e.g. ServiceNow production"
          onChange={(e) => setEditing({ ...editing, name: e.target.value })} />}</Field>
        <Field label="Toolkit">{(id) => <Select id={id} value={pkg?.id ?? ""} disabled={!editing.isNew}
          onChange={(e) => setEditing({ ...editing, package: e.target.value, values: {}, credentials: {} })}
          options={toolkits.map((p) => ({ value: p.id, label: p.name }))} />}</Field>
        {pkg && [...connectionInputs(pkg)].map((n) => {
          const s = spec(n), secret = s?.["x-ooa-type"] === "Credential";
          const cur = secret ? editing.credentials?.[n] : editing.values?.[n];
          return <Field key={n} label={n} info={s?.description}
            hint={secret ? "A reference to the secret in the vault (folder/credential/attribute), not the secret itself." : undefined}>{(id) =>
            <TextInput id={id} defaultValue={cur === undefined ? "" : String(cur)} placeholder={secret ? "vault folder/credential/attribute" : s?.default !== undefined ? String(s.default) : ""}
              onChange={(e) => set(n, secret, e.target.value)} />}</Field>;
        })}
      </div>
    );
  } else {
    body = (
      <div className="st-conn-list">
        <p className="st-muted">A connection keeps a connector's address and settings once, with vault references for its secrets. Steps choose it by name.
          Local runs read these from connections.json in this workspace.</p>
        {list === null ? <p className="st-muted">Loading…</p> : !list.length ? <p className="st-muted">No saved connections yet.</p>
          : <table className="st-table" aria-label="Saved connections"><thead><tr><th scope="col">Name</th><th scope="col">Toolkit</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>{list.map((c) => <tr key={c.name}><td>{c.name}</td><td>{nameOf(c.package)}</td>
              <td className="st-row-tight">{deleting === c.name
                ? <><span>Delete it? Steps that use it fail until another is chosen.</span><Button kind="danger" onClick={() => remove(c.name)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Keep</Button></>
                : <><Button kind="plain" onClick={() => setEditing(structuredClone(c))}>Edit</Button><Button kind="plain" aria-label={"Delete " + c.name} onClick={() => setDeleting(c.name)}>Delete</Button></>}</td></tr>)}</tbody></table>}
      </div>
    );
  }
  return (
    <Dialog open title="Saved connections" width={680} onClose={onClose}
      footer={editing ? <><Button kind="secondary" onClick={() => setEditing(null)}>Cancel</Button><Button kind="primary" onClick={save}>Save</Button></>
        : <><Button kind="secondary" onClick={onClose}>Close</Button>
          <Button kind="primary" icon="plus" disabled={!toolkits.length}
            onClick={() => setEditing({ name: "", package: pkgId && toolkits.some((p) => p.id === pkgId) ? pkgId : toolkits[0]?.id ?? "", values: {}, credentials: {}, isNew: true })}>New connection</Button></>}>
      {body}
    </Dialog>
  );
}

/** A Call operation step for one operation of a custom connector, with its required parameters and defaults filled in. */
export function connectorStep(def: ConnectorDef, op: ConnectorOperation): FlowStep {
  const args: Record<string, unknown> = { connector: def.name, operation: op.id };
  const params: Record<string, unknown> = {};
  for (const p of op.parameters ?? []) if (p.required || (p.default !== undefined && p.default !== null)) params[p.name] = p.default ?? "";
  if (Object.keys(params).length) args.parameters = params;
  if (op.body) args.body = op.body.example !== undefined ? op.body.example : {};
  if (op.result?.binary) args.output_file = op.id + ".bin";
  return { id: "new", action: "ooa.connector/call_operation", args, note: op.name };
}

/**
 * Custom connectors: import an OpenAPI 3.x or Swagger 2.0 file (JSON or YAML) or a Postman v2.1 collection, so each
 * request becomes an operation agents call with Call operation; browse a connector's operations and add a step for
 * one (when an agent is open), or delete the connector. They live in the workspace's connectors folder.
 */
export function ConnectorsDialog({ api, onInsert, onClose, onNotice, onError }:
  { api: StudioApi; onInsert?: (step: FlowStep) => void; onClose: () => void; onNotice: (t: string) => void; onError: (e: unknown) => void }) {
  const [list, setList] = useState<ConnectorSummary[] | null>(null);
  const [shown, setShown] = useState<ConnectorDef | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState("");
  const [server, setServer] = useState("");
  const [filter, setFilter] = useState("");
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);
  const load = () => api.customConnectors().then(setList, (e) => { setList([]); onError(e); });
  useEffect(() => { load(); }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps

  const doImport = async () => {
    if (!file) { onNotice("Choose a file to import."); return; }
    setBusy(true);
    try {
      const def = await api.importConnector(await file.text(), name.trim(), server.trim());
      onNotice("Imported " + def.name + " (" + def.operations.length + " operations).");
      setShown(def); setFilter(""); setFile(null); setName(""); setServer(""); load();
    } catch (e) { onError(e); }
    finally { setBusy(false); }
  };
  const remove = async (n: string) => {
    try { await api.deleteConnector(n); onNotice("Deleted the connector " + n + "."); setDeleting(null); load(); } catch (e) { onError(e); }
  };

  let body;
  if (shown) {
    const q = filter.trim().toLowerCase();
    const ops = shown.operations.filter((o) => !q || (o.id + " " + o.name + " " + o.path + " " + (o.tags ?? []).join(" ")).toLowerCase().includes(q));
    body = (
      <div className="st-conn-list">
        <div className="st-row-tight"><Button kind="plain" icon="chevronLeft" onClick={() => setShown(null)}>All connectors</Button>
          <strong>{shown.title || shown.name}</strong><span className="st-muted">{shown.server ?? ""}</span></div>
        <TextInput type="search" aria-label="Filter operations" placeholder="Filter operations" value={filter} onChange={(e) => setFilter(e.target.value)} />
        <ul className="st-op-list" aria-label="Operations">{ops.map((o) => (
          <li key={o.id} className="st-op">
            <span className="st-badge-soft">{o.method}</span>
            <div className="st-op-main"><div><strong>{o.name}</strong> <span className="st-muted">{o.path}</span></div>
              <div className="st-muted">{o.id}{(o.parameters ?? []).length ? " · " + (o.parameters ?? []).map((p) => p.name + (p.required ? "*" : "")).join(", ") : ""}</div></div>
            {onInsert && <Button kind="secondary" onClick={() => { onInsert(connectorStep(shown, o)); onClose(); }}>Add step</Button>}
          </li>))}
          {!ops.length && <li className="st-muted">No operation matches.</li>}
        </ul>
        {!onInsert && <p className="st-muted">Open an agent to add a step for an operation.</p>}
      </div>
    );
  } else {
    body = (
      <div className="st-conn-list">
        <p className="st-muted">Import an OpenAPI 3.x or Swagger 2.0 file (JSON or YAML) or a Postman v2.1 collection. Each request becomes an operation an agent
          calls with Call operation; secrets come from a Credential variable. Connectors are kept in the workspace's connectors folder.</p>
        <div className="st-conn-import" role="group" aria-label="Import a connector">
          <Field label="API description file">{(id) => <input id={id} type="file" className="ui-input" accept=".json,.yaml,.yml"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />}</Field>
          <div className="st-row-tight">
            <Field label="Connector name (optional)">{(id) => <TextInput id={id} placeholder="from the API title" value={name} onChange={(e) => setName(e.target.value)} />}</Field>
            <Field label="Server (optional)">{(id) => <TextInput id={id} placeholder="the first server in the file" value={server} onChange={(e) => setServer(e.target.value)} />}</Field>
          </div>
          <Button kind="primary" onClick={doImport} disabled={busy || !file}>{busy ? "Importing…" : "Import"}</Button>
        </div>
        {list === null ? <p className="st-muted">Loading…</p> : !list.length ? <p className="st-muted">No connectors yet.</p>
          : <table className="st-table" aria-label="Custom connectors"><thead><tr><th scope="col">Connector</th><th scope="col">Operations</th><th scope="col">Server</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>{list.map((c) => <tr key={c.name}><td><strong>{c.name}</strong>{c.title && <div className="st-muted">{c.title}</div>}</td><td>{c.operations ?? 0}</td>
              <td>{c.error ? <span className="st-check">{c.error}</span> : c.server || <span className="st-muted">No server set</span>}</td>
              <td className="st-row-tight">{deleting === c.name
                ? <><span>Delete it? Steps that use it stop working.</span><Button kind="danger" onClick={() => remove(c.name)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Keep</Button></>
                : <><Button kind="plain" onClick={async () => { try { setShown(await api.customConnector(c.name)); setFilter(""); } catch (e) { onError(e); } }}>Operations</Button>
                  <Button kind="plain" aria-label={"Delete " + c.name} onClick={() => setDeleting(c.name)}>Delete</Button></>}</td></tr>)}</tbody></table>}
      </div>
    );
  }
  return <Dialog open title="Custom connectors" width={760} onClose={onClose} footer={<Button kind="secondary" onClick={onClose}>Close</Button>}>{body}</Dialog>;
}
