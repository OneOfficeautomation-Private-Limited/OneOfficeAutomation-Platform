import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Toolkit } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * Manage > Agent ToolKits, built as AA's Packages (checked against AA's docs, UiPath's libraries and feeds, and Blue
 * Prism's objects, 31 Oct 2026): each toolkit with its versions (default first), actions, how many agents use it and
 * whether it is on. Switching off warns how many agents stop (AA warns the same). A toolkit opens with the agents that
 * use it and the version range each requires, and updates them to a new range (AA's "update bots to the default
 * version"). Your own toolkits are signed jars from trusted publishers.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function Toolkits({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Toolkit[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [dialog, setDialog] = useState<null | { kind: "open"; t: Toolkit } | { kind: "off"; t: Toolkit } | { kind: "upload" | "publishers" | "versions" }>(null);
  const manage = user.permissions.includes("toolkits.manage");
  const load = useCallback(async () => { try { setList(await api.toolkits()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((t) => (!f.q?.trim() || (t.name + " " + t.id + " " + (t.description ?? "")).toLowerCase().includes(f.q.trim().toLowerCase()))
    && (!f.state || (f.state === "on") === t.enabled) && (!f.source || (f.source === "own") === !!t.custom) && (!f.used || t.used_by > 0)), [list, f]);
  const toggle = (t: Toolkit) => api.setToolkitEnabled(t.id, !t.enabled).then(() => { onNotice((t.enabled ? "Switched off " : "Switched on ") + t.name + "."); setDialog(null); load(); }, (e) => setError(message(e)));

  return (
    <section className="st-page cc-home" aria-labelledby="cc-tk-title">
      <header className="st-page-head">
        <div><h1 id="cc-tk-title">Agent ToolKits</h1><p>Every toolkit of actions, how many agents use it, and switching it on or off. Several versions of your own toolkits stay installed side by side.</p></div>
        {manage && <span className="cc-inline">
          <Button kind="secondary" onClick={() => setDialog({ kind: "publishers" })}>Trusted publishers</Button>
          <Button kind="secondary" onClick={() => setDialog({ kind: "versions" })}>Your toolkit versions</Button>
          <Button kind="primary" onClick={() => setDialog({ kind: "upload" })}>Upload toolkit</Button>
        </span>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search toolkits" placeholder="Search toolkits" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} />
        <Select aria-label="State" value={f.state ?? ""} onChange={(e) => setF({ ...f, state: e.target.value })} options={[{ value: "", label: "On and off" }, { value: "on", label: "On" }, { value: "off", label: "Off" }]} />
        <Select aria-label="Source" value={f.source ?? ""} onChange={(e) => setF({ ...f, source: e.target.value })} options={[{ value: "", label: "Built-in and your own" }, { value: "builtin", label: "Built-in" }, { value: "own", label: "Your own" }]} />
        <label className="st-check-line"><input type="checkbox" checked={!!f.used} onChange={(e) => setF({ ...f, used: e.target.checked ? "1" : "" })} /> Used by agents</label>
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
      </div>
      <div className="cc-bar"><span className="cc-chosen">{shown.length} of {list?.length ?? 0} toolkits</span></div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Toolkits list">
          <thead><tr><th scope="col">State</th><th scope="col">Toolkit</th><th scope="col">Versions</th><th scope="col">Actions</th><th scope="col">Used by</th><th scope="col">Publisher</th>
            {manage && <th scope="col"><span className="st-sr">Switch</span></th>}</tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} onClick={(e) => { if (!(e.target as HTMLElement).closest("button")) setDialog({ kind: "open", t }); }}>
                <td className={"cc-status is-" + (t.enabled ? "success" : "disabled")}>{t.enabled ? "On" : "Off"}</td>
                <td>{t.name}{t.custom && <span className="cc-tag">your own</span>}<div className="st-muted ui-mono">{t.id}</div></td>
                <td>{t.versions ? t.versions.join(", ") + " (default " + t.version + ")" : t.version ?? ""}</td>
                <td>{t.actions}{t.conditions ? " + " + t.conditions + " conditions" : ""}</td>
                <td>{t.used_by ? t.used_by + " agent" + (t.used_by === 1 ? "" : "s") : "None"}</td>
                <td>{t.publisher ?? ""}</td>
                {manage && <td>{t.id !== "core" && <Button kind="plain" onClick={() => t.enabled && t.used_by ? setDialog({ kind: "off", t }) : toggle(t)}>{t.enabled ? "Switch off" : "Switch on"}</Button>}</td>}
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={7} className="st-muted">No toolkits match.</td></tr>}
            {!list && !error && <tr><td colSpan={7} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="st-muted">Switching a toolkit off stops agents that use it from being checked in or run. The core toolkit is always on.</p>
      {dialog?.kind === "off" && <Dialog open title={"Switch off " + dialog.t.name} width={460} onClose={() => setDialog(null)} footer={<>
        <Button kind="plain" onClick={() => setDialog(null)}>Cancel</Button><Button kind="danger" onClick={() => toggle(dialog.t)}>Switch off</Button></>}>
        <p>{dialog.t.used_by} agent{dialog.t.used_by === 1 ? " uses" : "s use"} {dialog.t.name}. They will stop running until it is switched on again.</p></Dialog>}
      {dialog?.kind === "open" && <ToolkitView api={api} t={dialog.t} canUpdate={user.permissions.includes("repo.checkin")} onClose={() => setDialog(null)} onNotice={onNotice} />}
      {dialog?.kind === "upload" && <Upload api={api} onClose={() => setDialog(null)} onDone={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "publishers" && <Publishers api={api} onClose={() => setDialog(null)} onNotice={onNotice} />}
      {dialog?.kind === "versions" && <CustomVersions api={api} onClose={() => { setDialog(null); load(); }} onNotice={onNotice} />}
    </section>
  );
}

/** A toolkit: the agents that use it, the version range each requires, and updating them to a new range. */
function ToolkitView({ api, t, canUpdate, onClose, onNotice }: { api: CrApi; t: Toolkit; canUpdate: boolean; onClose: () => void; onNotice: (t: string) => void }) {
  const [v, setV] = useState<Awaited<ReturnType<CrApi["toolkitVersions"]>> | null>(null);
  const [range, setRange] = useState("");
  const [results, setResults] = useState<{ path: string; status: string; version?: number; error?: { message?: string } }[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => {
    if (!t.used_by) return;
    api.toolkitVersions(t.id).then((x) => { setV(x); setRange((r) => r || "^" + String(x.installed).split(".")[0] + ".0.0"); }, (e) => setError(message(e)));
  }, [api, t.id, t.used_by]);
  useEffect(() => { read(); }, [read]);
  return (
    <Dialog open title={"Toolkit " + t.name} width={720} onClose={onClose} footer={canUpdate && v ? <Button kind="primary" disabled={!range.trim()}
      onClick={() => api.updateToolkitRange(t.id, range.trim()).then((r) => { setResults(r.results); onNotice("Updated " + r.results.filter((x) => x.status === "updated").length + " agent(s) to " + range.trim() + "."); read(); }, (e) => setError(message(e)))}>Update agents</Button> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <dl className="cc-facts">
        <dt>Id</dt><dd className="ui-mono">{t.id}</dd>
        <dt>Versions</dt><dd>{t.versions ? t.versions.join(", ") + " (default " + t.version + ")" : t.version}</dd>
        <dt>Actions</dt><dd>{t.actions}{t.conditions ? " and " + t.conditions + " conditions" : ""}</dd>
        {t.publisher && <><dt>Publisher</dt><dd>{t.publisher}</dd></>}
        <dt>State</dt><dd>{t.enabled ? "On" : "Off"}</dd>
      </dl>
      {t.description && <p className="st-muted">{t.description}</p>}
      <h3 className="cc-log-title">Agents that use it</h3>
      {!t.used_by ? <p className="st-muted">No agent uses it.</p> : !v ? <p className="st-muted" role="status">Loading…</p> : <>
        <table className="st-table" aria-label="Agents using the toolkit">
          <thead><tr><th scope="col">Agent</th><th scope="col">Latest</th><th scope="col">Requires</th><th scope="col">Installed {v.installed} fits</th></tr></thead>
          <tbody>{v.agents.map((a) => <tr key={a.path}><td>{agentName(a.path)}</td><td>v{a.version}</td><td>{a.required || "Any version"}</td>
            <td className={"cc-status is-" + (a.satisfied ? "success" : "failed")}>{a.satisfied ? "Yes" : "No"}</td></tr>)}</tbody>
        </table>
        {canUpdate && <>
          <Field label="Require this version range in every agent that uses it">{(id) => <TextInput id={id} value={range} onChange={(e) => setRange(e.target.value)} />}</Field>
          <p className="st-muted">Each agent gets a new version with the range; its live version stays until you choose the new one. Agents checked out by others are left as they are.</p>
        </>}
        {results && <table className="st-table" aria-label="Update results"><tbody>{results.map((x) => <tr key={x.path}><td>{agentName(x.path)}</td>
          <td>{x.status}{x.version ? " (v" + x.version + ")" : ""}{x.error?.message ? ": " + x.error.message : ""}</td></tr>)}</tbody></table>}
      </>}
    </Dialog>
  );
}

/** Upload a signed toolkit jar; it must be signed by a trusted publisher. */
function Upload({ api, onClose, onDone }: { api: CrApi; onClose: () => void; onDone: (t: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title="Upload toolkit" width={520} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!file} onClick={async () => { try { const r = await api.uploadToolkit(await file!.arrayBuffer()); onDone("Installed " + (r.name ?? r.toolkit_id ?? file!.name) + (r.version ? " " + r.version : "") + "."); } catch (e) { setError(message(e)); } }}>Upload</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">A toolkit jar built with the toolkit SDK and signed by a trusted publisher. Older versions stay installed for the agents that need them.</p>
      <input type="file" accept=".jar" aria-label="Toolkit jar" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
    </Dialog>
  );
}

/** Publishers whose signatures are trusted for uploaded toolkits. */
function Publishers({ api, onClose, onNotice }: { api: CrApi; onClose: () => void; onNotice: (t: string) => void }) {
  const [list, setList] = useState<{ publisher: string; public_key: string; added_by?: string; added?: string }[] | null>(null);
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => api.publishers().then(setList, (e) => setError(message(e))), [api]);
  useEffect(() => { read(); }, [read]);
  return (
    <Dialog open title="Trusted publishers" width={640} onClose={onClose} footer={<Button kind="primary" disabled={!name.trim() || !key.trim()}
      onClick={() => api.trustPublisher(name.trim(), key.trim()).then(() => { onNotice("Trusted " + name.trim() + "."); setName(""); setKey(""); read(); }, (e) => setError(message(e)))}>Trust publisher</Button>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <table className="st-table" aria-label="Publishers"><tbody>
        {(list ?? []).map((p) => <tr key={p.publisher}><td>{p.publisher}</td><td className="ui-mono cc-clip">{p.public_key}</td><td>{when(p.added)}{p.added_by ? " by " + p.added_by : ""}</td>
          <td><Button kind="plain" onClick={() => api.untrustPublisher(p.publisher).then(() => { onNotice("Stopped trusting " + p.publisher + "."); read(); }, (e) => setError(message(e)))}>Stop trusting</Button></td></tr>)}
        {list && !list.length && <tr><td className="st-muted">No trusted publishers yet.</td></tr>}
      </tbody></table>
      <Field label="Publisher">{(id) => <TextInput id={id} value={name} placeholder="acme" onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Public key">{(id) => <TextArea id={id} rows={3} value={key} onChange={(e) => setKey(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** The versions of your own toolkits installed, with their publisher and SHA-256; a version can be deleted. */
function CustomVersions({ api, onClose, onNotice }: { api: CrApi; onClose: () => void; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Awaited<ReturnType<CrApi["customToolkits"]>> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => api.customToolkits().then(setList, (e) => setError(message(e))), [api]);
  useEffect(() => { read(); }, [read]);
  return (
    <Dialog open title="Your toolkit versions" width={760} onClose={onClose} footer={null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <table className="st-table" aria-label="Your toolkit versions">
        <thead><tr><th scope="col">Toolkit</th><th scope="col">Version</th><th scope="col">Publisher</th><th scope="col">Uploaded</th><th scope="col">SHA-256</th><th scope="col"><span className="st-sr">Delete</span></th></tr></thead>
        <tbody>
          {(list ?? []).map((v) => <tr key={v.id}><td>{v.name || v.toolkit_id}</td><td>{v.version}</td><td>{v.publisher}</td><td>{when(v.uploaded)}{v.uploaded_by ? " by " + v.uploaded_by : ""}</td>
            <td className="ui-mono">{v.sha256.slice(0, 12)}</td>
            <td><Button kind="plain" onClick={() => api.deleteCustomToolkit(v.id).then(() => { onNotice("Deleted " + v.toolkit_id + " " + v.version + "."); read(); }, (e) => setError(message(e)))}>Delete</Button></td></tr>)}
          {list && !list.length && <tr><td colSpan={6} className="st-muted">No toolkits uploaded yet.</td></tr>}
        </tbody>
      </table>
    </Dialog>
  );
}
