import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Toolkit } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextArea, TextInput, type HelpTopic } from "../components";
import { RowMenu, rowClick, when, CloseView } from "./ListParts";

/**
 * Manage > Agent ToolKits, built as AA's Packages (checked against AA's docs, UiPath's libraries and feeds, and Blue
 * Prism's objects, 31 Oct 2026): each toolkit with its versions (default first), actions, how many agents use it and
 * whether it is on. Switching off warns how many agents stop (AA warns the same). A toolkit opens with the agents that
 * use it and the version range each requires, and updates them to a new range (AA's "update bots to the default
 * version"). Your own toolkits are signed jars from trusted publishers.
 * Review round 2 (31 Oct 2026): one line per toolkit (its id on hover), switching only from the menu, a toolkit opening as
 * its own page (as AA's package View: Agents that use it, Details), and Stop trusting and deleting a version asked first.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const folderOf = (p: string) => p.split("/").slice(0, -1).join("/");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const versionsOf = (t: Toolkit) => t.versions ? t.versions.join(", ") + " (default " + t.version + ")" : t.version ?? "";
type MenuItem = { key: string; label: string; onSelect: () => void };

const HELP: HelpTopic = { title: "Agent ToolKits",
  about: "Every toolkit of actions agents can use, how many agents use it, and whether it is on. Several versions of your own toolkits stay installed side "
    + "by side.",
  items: [
    { name: "State, Source and Used by agents", text: "Filter the list to toolkits that are on or off, built-in or your own, and those at least one agent "
      + "uses." },
    { name: "Versions", text: "The versions installed, with the default one new agents get." },
    { name: "Open", text: "Opens the toolkit's own page with the agents that use it and its details. Clicking the row does the same." },
    { name: "Switch off… and Switch on", text: "In the row's ⋯ menu. While a toolkit is off, agents that use it cannot be checked in or run; you are asked "
      + "first when agents use it. The core toolkit is always on. Needs permission to manage toolkits." },
    { name: "Upload toolkit", text: "Installs a toolkit jar built with the toolkit SDK and signed by a trusted publisher. Older versions stay installed for "
      + "agents that need them." },
    { name: "Trusted publishers", text: "The publishers whose signatures are accepted on uploaded toolkits, with their public keys. Stop trusting one to "
      + "refuse its new uploads." },
    { name: "Your toolkit versions", text: "Every version of your own toolkits with its publisher, upload date and SHA-256. Delete a version only when no "
      + "agent needs it." },
  ] };

const HELP_TOOLKIT: HelpTopic = { title: "Agent toolkit",
  about: "One toolkit: the agents that use it with the versions they need, and its details.",
  items: [
    { name: "Agents that use it", text: "Each agent with the version range it requires and whether the default version fits. Click an agent to find it in "
      + "Automation." },
    { name: "Require in every agent that uses it", text: "Type a version range, such as ^2.0.0, and press Update agents. Each agent gets a new version with "
      + "that range; its live version stays until you choose the new one. Needs permission to check in agents." },
    { name: "Default fits", text: "No means the agent asks for versions the default does not meet; update its range or keep the version it needs "
      + "installed." },
    { name: "Details", text: "The toolkit's id, the versions installed, its actions and conditions, publisher and state." },
    { name: "Switch off… or Switch on", text: "Stops or allows checking in and running the agents that use it. Needs permission to manage toolkits." },
  ] };

export function Toolkits({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Toolkit[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [openId, setOpenId] = useState<string | null>(null);   // a toolkit's own page, in place of the list
  const [dialog, setDialog] = useState<null | { kind: "off"; t: Toolkit } | { kind: "upload" | "publishers" | "versions" }>(null);
  const manage = user.permissions.includes("toolkits.manage");
  const load = useCallback(async () => { try { setList(await api.toolkits()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((t) => (!f.q?.trim() || (t.name + " " + t.id + " " + (t.description ?? "")).toLowerCase().includes(f.q.trim().toLowerCase()))
    && (!f.state || (f.state === "on") === t.enabled) && (!f.source || (f.source === "own") === !!t.custom) && (!f.used || t.used_by > 0)), [list, f]);
  const toggle = (t: Toolkit) => api.setToolkitEnabled(t.id, !t.enabled).then(() => { onNotice((t.enabled ? "Switched off " : "Switched on ") + t.name + "."); setDialog(null); load(); }, (e) => setError(message(e)));
  /** Switching on happens at once; switching off a toolkit agents use asks first. */
  const switchIt = (t: Toolkit) => { if (t.enabled && t.used_by) setDialog({ kind: "off", t }); else toggle(t); };
  const canSwitch = (t: Toolkit) => manage && t.id !== "core";
  const switchItem = (t: Toolkit): MenuItem[] => canSwitch(t) ? [{ key: "switch", label: t.enabled ? "Switch off…" : "Switch on", onSelect: () => switchIt(t) }] : [];
  const offDialog = dialog?.kind === "off" && <Dialog open title={"Switch off " + dialog.t.name} width={460} onClose={() => setDialog(null)} footer={<>
    <Button kind="plain" onClick={() => setDialog(null)}>Cancel</Button><Button kind="danger" onClick={() => toggle(dialog.t)}>Switch off</Button></>}>
    <p>{dialog.t.used_by} agent{dialog.t.used_by === 1 ? " uses" : "s use"} {dialog.t.name}. They will stop running until it is switched on again.</p></Dialog>;

  const opened = openId ? list?.find((t) => t.id === openId) : undefined;
  if (opened) return (
    <section className="st-page cc-home cc-explorer" aria-label="Agent toolkit">
      <ToolkitView key={opened.id} api={api} t={opened} canUpdate={user.permissions.includes("repo.checkin")}
        onSwitch={canSwitch(opened) ? () => switchIt(opened) : undefined} onClose={() => { setOpenId(null); load(); }} onNotice={onNotice} />
      {offDialog}
    </section>
  );
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-tk-title">
      <header className="cc-list-head">
        <h1 id="cc-tk-title">Agent ToolKits</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length} toolkit{list.length === 1 ? "" : "s"} · {list.filter((t) => t.enabled).length} on · {list.filter((t) => t.custom).length} your own</span>}
        <span className="ov-fill" />
        {manage && <>          <Button kind="secondary" onClick={() => setDialog({ kind: "publishers" })}>Trusted publishers</Button>
          <Button kind="secondary" onClick={() => setDialog({ kind: "versions" })}>Your toolkit versions</Button>
          <Button kind="primary" onClick={() => setDialog({ kind: "upload" })}>Upload toolkit</Button></>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-list-search"><TextInput type="search" aria-label="Search toolkits" placeholder="Search toolkits" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} /></span>
        <span className="cc-chip"><Select aria-label="State" value={f.state ?? ""} onChange={(e) => setF({ ...f, state: e.target.value })} options={[{ value: "", label: "On and off" }, { value: "on", label: "On" }, { value: "off", label: "Off" }]} /></span>
        <span className="cc-chip"><Select aria-label="Source" value={f.source ?? ""} onChange={(e) => setF({ ...f, source: e.target.value })} options={[{ value: "", label: "Built-in and your own" }, { value: "builtin", label: "Built-in" }, { value: "own", label: "Your own" }]} /></span>
        <label className="cc-chip-check"><input type="checkbox" checked={!!f.used} onChange={(e) => setF({ ...f, used: e.target.checked ? "1" : "" })} /> Used by agents</label>
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
      </div>
      </div>
      <div className="cc-list-panel"><div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Toolkits list">
          <thead><tr><th scope="col">State</th><th scope="col">Toolkit</th><th scope="col">Versions</th><th scope="col">Actions</th><th scope="col">Used by</th><th scope="col">Publisher</th>
            <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} onClick={rowClick(() => setOpenId(t.id))}>
                <td><span className={"cc-pill is-" + (t.enabled ? "active" : "disabled")}><span className="cc-pill-dot" />{t.enabled ? "On" : "Off"}</span></td>
                <td title={t.id + (t.description ? " · " + t.description : "")}><strong>{t.name}</strong>{t.custom && <span className="cc-tag">your own</span>}</td>
                <td>{versionsOf(t)}</td>
                <td>{t.actions}{t.conditions ? " + " + t.conditions + " conditions" : ""}</td>
                <td>{t.used_by ? t.used_by + " agent" + (t.used_by === 1 ? "" : "s") : "None"}</td>
                <td>{t.publisher ?? ""}</td>
                <td className="cc-list-menu"><RowMenu label={"Actions for " + t.name} items={[{ key: "open", label: "Open", onSelect: () => setOpenId(t.id) }, ...switchItem(t)]} /></td>
              </tr>
            ))}
            {list && !shown.length && <tr className="is-static"><td colSpan={7} className="cc-none">No toolkits match.</td></tr>}
            {!list && !error && <tr className="is-static"><td colSpan={7} className="cc-none" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}</span></div>}</div>
      <p className="cc-list-hint">Switching a toolkit off stops agents that use it from being checked in or run. The core toolkit is always on.</p>
      {offDialog}
      {dialog?.kind === "upload" && <Upload api={api} onClose={() => setDialog(null)} onDone={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "publishers" && <Publishers api={api} onClose={() => setDialog(null)} onNotice={onNotice} />}
      {dialog?.kind === "versions" && <CustomVersions api={api} onClose={() => { setDialog(null); load(); }} onNotice={onNotice} />}
    </section>
  );
}

/** A toolkit's own page: the agents that use it with the version range each requires, updating them to a new range, and its details. */
function ToolkitView({ api, t, canUpdate, onSwitch, onClose, onNotice }: { api: CrApi; t: Toolkit; canUpdate: boolean;
  onSwitch?: () => void; onClose: () => void; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<"agents" | "details">(t.used_by ? "agents" : "details");
  const [v, setV] = useState<Awaited<ReturnType<CrApi["toolkitVersions"]>> | null>(null);
  const [range, setRange] = useState("");
  const [results, setResults] = useState<{ path: string; status: string; version?: number; error?: { message?: string } }[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => {
    if (!t.used_by) return;
    api.toolkitVersions(t.id).then((x) => { setV(x); setRange((r) => r || "^" + String(x.installed).split(".")[0] + ".0.0"); }, (e) => setError(message(e)));
  }, [api, t.id, t.used_by]);
  useEffect(() => { read(); }, [read]);
  const update = () => api.updateToolkitRange(t.id, range.trim()).then((r) => { setResults(r.results); onNotice("Updated " + r.results.filter((x) => x.status === "updated").length + " agent(s) to " + range.trim() + "."); read(); }, (e) => setError(message(e)));
  const misfit = v?.agents.filter((a) => !a.satisfied).length ?? 0;
  const close = <CloseView onClick={onClose} to="the toolkits" />;
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"Toolkit " + t.name}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Agent ToolKits</button> ›</span>
        <h1>{t.name}</h1>
        <HelpButton topic={HELP_TOOLKIT} />
        <span className={"cc-pill is-" + (t.enabled ? "active" : "disabled")}><span className="cc-pill-dot" />{t.enabled ? "On" : "Off"}</span>
        <span className="cc-none"><span className="ui-mono">{t.id}</span> · default {t.version} · {t.actions} actions{t.conditions ? " + " + t.conditions + " conditions" : ""} · {t.used_by ? t.used_by + " agent" + (t.used_by === 1 ? " uses" : "s use") + " it" : "no agent uses it"}
          {misfit ? <> · <span className="cc-risk">{misfit} need{misfit === 1 ? "s" : ""} another version</span></> : null}</span>
        <span className="ov-fill" />
        {onSwitch && <Button kind="secondary" onClick={onSwitch}>{t.enabled ? "Switch off…" : "Switch on"}</Button>}
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this toolkit">
        {([["agents", "Agents that use it (" + t.used_by + ")"], ["details", "Details"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "agents" && <>
        {canUpdate && v && <div className="cc-list-tools cc-pane-tools"><div className="cc-list-filters cc-tk-update" role="group" aria-label="Update agents">
          <label htmlFor="cc-tk-range">Require in every agent that uses it</label>
          <TextInput id="cc-tk-range" className="ui-mono" value={range} onChange={(e) => setRange(e.target.value)} />
          <Button kind="primary" disabled={!range.trim()} onClick={update}>Update agents</Button>
          <span className="cc-none" title="Agents checked out by others are left as they are.">Each agent gets a new version with the range; its live version stays until you choose the new one.</span>
        </div></div>}
        {results && <div className="cc-list-bulk" role="status"><span>{results.map((x) => agentName(x.path) + ": " + x.status + (x.version ? " (v" + x.version + ")" : "") + (x.error?.message ? " – " + x.error.message : "")).join(" · ")}</span>
          <Button kind="plain" onClick={() => setResults(null)}>Close</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Agents using the toolkit">
            <thead><tr><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col" className="cc-num">Latest</th><th scope="col">Requires</th><th scope="col">Default {v?.installed ?? t.version} fits</th></tr></thead>
            <tbody>
              {(v?.agents ?? []).map((a) => <tr key={a.path} title="Open the agent in Automation" onClick={() => { location.hash = "#/automation?q=" + encodeURIComponent(agentName(a.path)); }}>
                <td><span className="cc-aico" aria-hidden="true">A</span>{agentName(a.path)}</td><td>{folderOf(a.path)}</td><td className="cc-num">v{a.version}</td>
                <td className="ui-mono">{a.required || "Any version"}</td>
                <td><span className={"cc-pill is-" + (a.satisfied ? "success" : "failed")}><span className="cc-pill-dot" />{a.satisfied ? "Yes" : "No"}</span></td></tr>)}
              {!t.used_by && <tr className="is-static"><td colSpan={5} className="cc-none">No agent uses it.</td></tr>}
              {!!t.used_by && !v && !error && <tr className="is-static"><td colSpan={5} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </>}
      {tab === "details" && <div className="cc-agent-tab">
        {t.description && <p>{t.description}</p>}
        <dl className="cc-facts cc-facts-wide">
          <dt>Id</dt><dd className="ui-mono">{t.id}</dd>
          <dt>Versions installed</dt><dd>{versionsOf(t)}</dd>
          <dt>Actions</dt><dd>{t.actions}{t.conditions ? " and " + t.conditions + " conditions" : ""}</dd>
          <dt>Publisher</dt><dd>{t.publisher ?? "–"}{t.custom ? " (your own toolkit)" : ""}</dd>
          <dt>State</dt><dd>{t.enabled ? "On" : "Off"}{t.id === "core" ? " (always on)" : ""}</dd>
        </dl>
      </div>}
    </div>
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
      <p className="cc-list-hint">A toolkit jar built with the toolkit SDK and signed by a trusted publisher. Older versions stay installed for the agents that need them.</p>
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
  const [ask, setAsk] = useState<string | null>(null);   // a publisher to stop trusting, asked first
  useEffect(() => { read(); }, [read]);
  return (
    <Dialog open title="Trusted publishers" width={640} onClose={onClose} footer={<Button kind="primary" disabled={!name.trim() || !key.trim()}
      onClick={() => api.trustPublisher(name.trim(), key.trim()).then(() => { onNotice("Trusted " + name.trim() + "."); setName(""); setKey(""); read(); }, (e) => setError(message(e)))}>Trust publisher</Button>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {ask && <div className="st-alert cc-inline" role="alert"><span>Stop trusting {ask}? Toolkits it signed stay installed; new uploads signed by it are refused.</span>
        <Button kind="danger" onClick={() => api.untrustPublisher(ask).then(() => { onNotice("Stopped trusting " + ask + "."); setAsk(null); read(); }, (e) => setError(message(e)))}>Stop trusting</Button>
        <Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
      <table className="st-table" aria-label="Publishers"><tbody>
        {(list ?? []).map((p) => <tr key={p.publisher}><td>{p.publisher}</td><td className="ui-mono cc-clip">{p.public_key}</td><td>{when(p.added)}{p.added_by ? " by " + p.added_by : ""}</td>
          <td><Button kind="plain" onClick={() => setAsk(p.publisher)}>Stop trusting…</Button></td></tr>)}
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
  const [ask, setAsk] = useState<{ id: string; toolkit_id: string; version: string } | null>(null);   // a version to delete, asked first
  useEffect(() => { read(); }, [read]);
  return (
    <Dialog open title="Your toolkit versions" width={760} onClose={onClose} footer={null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {ask && <div className="st-alert cc-inline" role="alert"><span>Delete {ask.toolkit_id} {ask.version}? Agents that need this version stop running until it is uploaded again.</span>
        <Button kind="danger" onClick={() => api.deleteCustomToolkit(ask.id).then(() => { onNotice("Deleted " + ask.toolkit_id + " " + ask.version + "."); setAsk(null); read(); }, (e) => setError(message(e)))}>Delete</Button>
        <Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
      <table className="st-table" aria-label="Your toolkit versions">
        <thead><tr><th scope="col">Toolkit</th><th scope="col">Version</th><th scope="col">Publisher</th><th scope="col">Uploaded</th><th scope="col">SHA-256</th><th scope="col"><span className="st-sr">Delete</span></th></tr></thead>
        <tbody>
          {(list ?? []).map((v) => <tr key={v.id}><td>{v.name || v.toolkit_id}</td><td>{v.version}</td><td>{v.publisher}</td><td>{when(v.uploaded)}{v.uploaded_by ? " by " + v.uploaded_by : ""}</td>
            <td className="ui-mono">{v.sha256.slice(0, 12)}</td>
            <td><Button kind="plain" onClick={() => setAsk(v)}>Delete…</Button></td></tr>)}
          {list && !list.length && <tr className="is-static"><td colSpan={6} className="cc-none">No toolkits uploaded yet.</td></tr>}
        </tbody>
      </table>
    </Dialog>
  );
}
