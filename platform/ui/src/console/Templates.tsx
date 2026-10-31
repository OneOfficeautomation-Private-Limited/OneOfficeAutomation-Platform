import { useCallback, useEffect, useMemo, useState } from "react";
import type { AgentTemplate, CrApi, CrUser } from "../api/cr";
import { Button, Dialog, Field, Menu, Select, TextArea, TextInput } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";
import { FolderIcon } from "./Repository";

/**
 * Build > Agent Templates, built as AA's Bot Store and UiPath's Marketplace (checked 31 Oct 2026): ready-made agents to
 * install into the agent repository and adapt, from ONEOFFICEAUTOMATION and, where the service allows, shared in your
 * region. Cards by category with search; Install into a folder (agents already there are kept); share an agent as a
 * template (approved by the operators); a signed catalogue file for a Command Center without internet access.
 * Laid out exactly as Automation (review round 2, 31 Oct 2026): a category tree on the left, the templates in one
 * table on the right (installed or not, and where), and a template's own page when you open one.
 */
const CATEGORIES = ["Finance", "Human resources", "Sales", "Customer service", "IT", "Operations", "Documents", "Other"];
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function Templates({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [data, setData] = useState<Awaited<ReturnType<CrApi["templates"]>> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [cat, setCat] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "install"; t: AgentTemplate } | { kind: "share" | "catalogue" }>(null);
  const [open, setOpen] = useState<AgentTemplate | null>(null);
  const [state, setState] = useState("");
  const [repo, setRepo] = useState<string[]>([]);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => {
    try { setData(await api.templates()); setError(null); } catch (e) { setError(message(e)); }
    if (can("repo.view")) api.repo().then((a) => setRepo(a.map((x) => x.path)), () => {});
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [load]);
  /** Where a template's agents are in the repository already (by the agents' file names): its folders, or none. */
  const installedIn = useCallback((t: AgentTemplate) => [...new Set(repo.filter((p) => t.agents.some((a) => p === a || p.endsWith("/" + a.split("/").pop()))).map((p) => p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "(top)"))], [repo]);
  const shown = useMemo(() => (data?.templates ?? []).filter((t) => (!cat || t.category === cat)
    && (!q.trim() || (t.name + " " + (t.description ?? "") + " " + t.publisher).toLowerCase().includes(q.trim().toLowerCase()))
    && (!state || (state === "installed") === installedIn(t).length > 0)), [data, q, cat, state, installedIn]);
  const all = data?.templates ?? [];
  const cats = [...new Set([...(data?.categories ?? []), ...all.map((t) => t.category)])];
  const installed = all.filter((t) => installedIn(t).length).length;
  const manage = [...(data?.can_share ? [{ key: "share", label: "Share an agent", onSelect: () => setDialog({ kind: "share" }) }] : []),
    ...(can("settings.manage") ? [{ key: "catalogue", label: "Upload a catalogue file", onSelect: () => setDialog({ kind: "catalogue" }) }] : [])];
  
  const openIn = (t: AgentTemplate) => "#/automation?q=" + encodeURIComponent(agentName(t.agents[0] ?? t.name));
  const rowMenu = (t: AgentTemplate) => [
    { key: "open", label: "Open", onSelect: () => setOpen(t) },
    ...(can("lifecycle.import") ? [{ key: "install", label: installedIn(t).length ? "Install again into…" : "Install…", onSelect: () => setDialog({ kind: "install", t }) }] : []),
    ...(installedIn(t).length ? [{ key: "automation", label: "Open in Automation", onSelect: () => { location.hash = openIn(t).slice(1); } }] : []),
  ];

  if (open) {   // a template's own page, in place of the panes, as an agent opens in Automation
    const where = installedIn(open);
    return (
      <section className="st-page cc-home cc-explorer" aria-label={"Agent Templates: " + open.name}>
        <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"About " + open.name}>
          <div className="cc-agent-head">
            <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={() => { setOpen(null); setCat(""); }}>Agent Templates</button> ›{" "}
              <button type="button" className="cc-link" onClick={() => { setOpen(null); setCat(open.category); }}>{open.category}</button> ›</span>
            <h1>{open.name}</h1>
            {where.length ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Installed</span> : <span className="cc-pill is-stopped"><span className="cc-pill-dot" />Not installed</span>}
            <span className="cc-none">{open.source === "region" ? "Shared by " + open.publisher : open.publisher}</span>
            <span className="ov-fill" />
            {where.length > 0 && <a className="ui-btn ui-btn-secondary cc-btn-link" href={openIn(open)}>Open in Automation</a>}
            {can("lifecycle.import") && <Button kind="primary" onClick={() => setDialog({ kind: "install", t: open })}>{where.length ? "Install again into…" : "Install"}</Button>}
            <button type="button" className="cc-list-icon" aria-label="Close" title="Back to the templates" onClick={() => setOpen(null)}>×</button>
          </div>
          <div className="cc-agent-tab">
            {open.description && <p>{open.description}</p>}
            <h3 className="cc-au-sec">What is inside</h3>
            <table className="cc-list-table" aria-label="Agents in this template">
              <thead><tr><th scope="col">Agent</th><th scope="col">In Automation</th></tr></thead>
              <tbody>{open.agents.map((a) => { const at = repo.filter((p) => p === a || p.endsWith("/" + a.split("/").pop()));
                return <tr key={a} className="is-static"><td><span className="cc-aico" aria-hidden="true">A</span><strong>{agentName(a)}</strong></td>
                  <td>{at.length ? at.map((p) => p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "(top)").join(", ") : <span className="cc-none">Not installed</span>}</td></tr>; })}</tbody>
            </table>
            <p className="cc-none">Installing puts the template's agents into the folder you choose, as new agents you can change. Agents already there are kept as they are.</p>
          </div>
        </div>
        {dialog?.kind === "install" && <Install api={api} t={dialog.t} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); load(); }} />}
      </section>
    );
  }

  return (
    <section className="st-page cc-home cc-explorer" aria-labelledby="cc-tpl-title">
      <header className="cc-list-head">
        <h1 id="cc-tpl-title">Agent Templates</h1>
        <span className="ov-info" role="img" aria-label="About this page" title={"Ready-made agents to install into Automation and adapt: from ONEOFFICEAUTOMATION" + (data?.can_share ? " and shared in your region." : ".")}>i</span>
        {data && <span className="cc-list-count">{all.length} template{all.length === 1 ? "" : "s"}{repo.length ? " · " + installed + " installed" : ""} · {cats.length} categor{cats.length === 1 ? "y" : "ies"}</span>}
        <span className="ov-fill" />
        {manage.length > 0 && <Menu label="Manage" align="end" items={manage} trigger={<button type="button" className="ui-btn ui-btn-secondary" aria-label="Manage">Manage ▾</button>} />}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {data?.problem && <p className="st-alert">{data.problem}</p>}
      <div className="cc-explorer-body">
        <nav className="cc-tree-pane" aria-label="Categories">
          <div className="cc-pane-head"><h2>Categories</h2></div>
          <ul className="cc-tree">
            <li><div className={"cc-tree-row" + (cat === "" ? " is-on" : "")} style={{ paddingLeft: 6 }}><span className="cc-tree-chev" />
              <button type="button" className="cc-tree-name" aria-current={cat === "" ? "page" : undefined} onClick={() => setCat("")}><FolderIcon open />All templates<span className="cc-tree-n">{all.length}</span></button></div>
              <ul>{cats.map((c) => { const n = all.filter((t) => t.category === c).length; return <li key={c}><div className={"cc-tree-row" + (cat === c ? " is-on" : "")} style={{ paddingLeft: 22 }}>
                <span className="cc-tree-chev" /><button type="button" className="cc-tree-name" aria-current={cat === c ? "page" : undefined} onClick={() => setCat(c)}>
                  <FolderIcon /><span className={n ? undefined : "cc-none"}>{c}</span><span className="cc-tree-n">{n}</span></button></div></li>; })}</ul></li>
          </ul>
          <p className="cc-pane-foot">From ONEOFFICEAUTOMATION's template catalogue{data?.can_share ? " and templates shared in your region" : ""}.</p>
        </nav>
        <div className="cc-files-pane">
          <div className="cc-pane-head">
            <span className="cc-crumbs-path"><FolderIcon open /><button type="button" className="cc-link" onClick={() => setCat("")}>Agent Templates</button>
              {cat && <span> › <strong>{cat}</strong></span>}</span>
          </div>
          <div className="cc-list-tools cc-pane-tools">
            <div className="cc-list-filters" role="group" aria-label="Filters">
              <span className="cc-list-search"><TextInput type="search" aria-label="Search templates" placeholder={"Search in " + (cat || "Agent Templates")} value={q} onChange={(e) => setQ(e.target.value)} /></span>
              <span className={"cc-chip" + (state ? " is-on" : "")}><Select aria-label="State" value={state} onChange={(e) => setState(e.target.value)}
                options={[{ value: "", label: "State: Any" }, { value: "installed", label: "State: Installed" }, { value: "not", label: "State: Not installed yet" }]} /></span>
              {(q.trim() || state) && <Button kind="plain" onClick={() => { setQ(""); setState(""); }}>Clear filters</Button>}
            </div>
            <div className="cc-list-right" role="toolbar" aria-label="Templates list tools"><RefreshButton onClick={load} /></div>
          </div>
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label="Templates">
              <thead><tr><th scope="col">Name</th><th scope="col">Category</th><th scope="col">Publisher</th><th scope="col" className="cc-num">Agents</th><th scope="col">State</th><th scope="col">Installed in</th>
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>
                {shown.map((t) => { const where = installedIn(t); return (
                  <tr key={t.source + t.id} onClick={rowClick(() => setOpen(t))}>
                    <td title={t.description}><span className="cc-aico is-template" aria-hidden="true">T</span><strong>{t.name}</strong></td>
                    <td>{t.category}</td><td>{t.source === "region" ? "Shared by " + t.publisher : t.publisher}</td><td className="cc-num">{t.agents.length}</td>
                    <td>{where.length ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Installed</span> : <span className="cc-none">Not installed</span>}</td>
                    <td>{where.length ? where.join(", ") : <span className="cc-none">–</span>}</td>
                    <td className="cc-list-menu"><RowMenu label={"Actions for " + t.name} items={rowMenu(t)} /></td>
                  </tr>); })}
                {data && !shown.length && <tr className="is-static"><td colSpan={7} className="cc-none">{all.length ? "No template matches." : "No templates to show." + (can("settings.manage")
                  ? " Set the address of a template catalogue in Settings (General), or use Manage > Upload a catalogue file for a Command Center without internet access." : " An administrator sets up the template catalogue in Settings.")}</td></tr>}
                {!data && !error && <tr className="is-static"><td colSpan={7} className="cc-none" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          {data && <div className="cc-list-pager"><span>{shown.length} of {all.length} template{all.length === 1 ? "" : "s"}{q.trim() || state ? " matching" : ""}</span>
            {!all.length && can("settings.manage") && <><span className="ov-fill" /><a className="ui-btn ui-btn-secondary cc-btn-link" href="#/settings">Open Settings</a></>}</div>}
        </div>
      </div>
      {dialog?.kind === "install" && <Install api={api} t={dialog.t} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); load(); }} />}
      {dialog?.kind === "share" && <Share api={api} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); }} />}
      {dialog?.kind === "catalogue" && <Catalogue api={api} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); load(); }} />}
    </section>
  );
}

function Install({ api, t, onClose, onDone }: { api: CrApi; t: AgentTemplate; onClose: () => void; onDone: (m: string) => void }) {
  const [folder, setFolder] = useState("Templates");
  const [results, setResults] = useState<Awaited<ReturnType<CrApi["installTemplate"]>>["results"] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const install = () => api.installTemplate(t.source, t.id, folder.trim() || "Templates").then((r) => {
    if (r.ok) onDone("Installed " + t.name + " in " + r.folder + ": open it in Automation.");
    else setResults(r.results);
  }, (e) => setError(message(e)));
  return (
    <Dialog open title={"Install " + t.name} width={600} onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" onClick={install}>Install</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">The template's agents go into the agent repository under this folder, as new agents you can change. Agents already there are kept as they are.</p>
      <Field label="Folder">{(id) => <TextInput id={id} value={folder} onChange={(e) => setFolder(e.target.value)} />}</Field>
      {results && <><p className="st-alert">Nothing was installed: fix the problems listed.</p>
        <table className="st-table" aria-label="Install results"><tbody>{results.map((x) => <tr key={x.path}><td>{agentName(x.path)}</td><td>{x.status}</td><td>{x.error?.message ?? ""}</td></tr>)}</tbody></table></>}
    </Dialog>
  );
}

function Share({ api, onClose, onDone }: { api: CrApi; onClose: () => void; onDone: (m: string) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [v, setV] = useState({ path: "", name: "", description: "", category: "Finance" });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveAgents().then((b) => { setAgents(b.map((x) => x.path)); setV((o) => ({ ...o, path: o.path || b[0]?.path || "" })); }, () => {}); }, [api]);
  return (
    <Dialog open title="Share an agent as a template" width={600} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!v.path} onClick={() => api.shareTemplate({ ...v, name: v.name.trim() || agentName(v.path), description: v.description.trim() })
        .then(() => onDone("Sent for approval: it shows in Agent Templates once approved."), (e) => setError(message(e)))}>Share</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">Its live version and the agents it calls go to your region's template catalogue. Once the service's operators approve it, every company in the region can install it.</p>
      <Field label="Agent">{(id) => <Select id={id} value={v.path} onChange={(e) => setV({ ...v, path: e.target.value })} options={agents.map((a) => ({ value: a, label: agentName(a) }))} />}</Field>
      <Field label="Template name">{(id) => <TextInput id={id} value={v.name} maxLength={120} placeholder={v.path ? agentName(v.path) : ""} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
      <Field label="What it does">{(id) => <TextArea id={id} rows={3} maxLength={2000} value={v.description} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      <Field label="Category">{(id) => <Select id={id} value={v.category} onChange={(e) => setV({ ...v, category: e.target.value })} options={CATEGORIES.map((c) => ({ value: c, label: c }))} />}</Field>
    </Dialog>
  );
}

function Catalogue({ api, onClose, onDone }: { api: CrApi; onClose: () => void; onDone: (m: string) => void }) {
  const [text, setText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title="Upload a template catalogue" width={540} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!text} onClick={() => api.uploadCatalogue(text!).then((r) => onDone(r.templates + " templates available."), (e) => setError(message(e)))}>Upload</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">For a Command Center without internet access: download templates.json from the releases page and upload it here. It must carry ONEOFFICEAUTOMATION's signature.</p>
      <input type="file" accept=".json,application/json" aria-label="Catalogue file" onChange={async (e) => { const f = e.target.files?.[0]; setText(f ? await f.text() : null); }} />
    </Dialog>
  );
}
