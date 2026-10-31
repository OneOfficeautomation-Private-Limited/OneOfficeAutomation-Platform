import { useCallback, useEffect, useMemo, useState } from "react";
import type { AgentTemplate, CrApi, CrUser } from "../api/cr";
import { Button, Dialog, Field, Menu, Select, TextArea, TextInput } from "../components";

/**
 * Build > Agent Templates, built as AA's Bot Store and UiPath's Marketplace (checked 31 Oct 2026): ready-made agents to
 * install into the agent repository and adapt, from ONEOFFICEAUTOMATION and, where the service allows, shared in your
 * region. Cards by category with search; Install into a folder (agents already there are kept); share an agent as a
 * template (approved by the operators); a signed catalogue file for a Command Center without internet access.
 * Laid out as AA's Bot Store (review round 2, approved on the canvas 31 Oct 2026), edge to edge like Automation:
 * categories with counts and Installed / Not installed on the left, the templates as cards, and a side panel with
 * what a template holds, where it is installed, and Install.
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
  const [show, setShow] = useState({ installed: true, notInstalled: true });
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
    && (installedIn(t).length ? show.installed : show.notInstalled)), [data, q, cat, show, installedIn]);
  const all = data?.templates ?? [];
  const cats = [...new Set([...(data?.categories ?? []), ...all.map((t) => t.category)])];
  const installed = all.filter((t) => installedIn(t).length).length;
  const manage = [...(data?.can_share ? [{ key: "share", label: "Share an agent", onSelect: () => setDialog({ kind: "share" }) }] : []),
    ...(can("settings.manage") ? [{ key: "catalogue", label: "Upload a catalogue file", onSelect: () => setDialog({ kind: "catalogue" }) }] : [])];
  const logo = (t: AgentTemplate) => t.name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join("").toUpperCase();

  return (
    <section className={"st-page cc-home cc-explorer" + (open ? " has-tpl" : "")} aria-labelledby="cc-tpl-title">
      <header className="cc-list-head">
        <h1 id="cc-tpl-title">Agent Templates</h1>
        <span className="ov-info" role="img" aria-label="About this page" title={"Ready-made agents to install into Automation and adapt: from ONEOFFICEAUTOMATION" + (data?.can_share ? " and shared in your region." : ".")}>i</span>
        {data && <span className="cc-list-count">{all.length} template{all.length === 1 ? "" : "s"}{repo.length ? " · " + installed + " installed" : ""}</span>}
        <span className="ov-fill" />
        <span className="cc-list-search"><TextInput type="search" aria-label="Search templates" placeholder="Search templates" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        {manage.length > 0 && <Menu label="Manage" align="end" items={manage} trigger={<button type="button" className="ui-btn ui-btn-secondary" aria-label="Manage">Manage ▾</button>} />}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {data?.problem && <p className="st-alert">{data.problem}</p>}
      <div className="cc-explorer-body cc-tpl-body">
        <nav className="cc-tree-pane" aria-label="Categories">
          <div className="cc-pane-head"><h2>Categories</h2></div>
          <ul className="cc-tree">
            {[["", "All templates", all.length] as const, ...cats.map((c) => [c, c, all.filter((t) => t.category === c).length] as const)].map(([v, l, n]) =>
              <li key={v}><div className={"cc-tree-row" + (cat === v ? " is-on" : "")}><button type="button" className="cc-tree-name" aria-current={cat === v ? "page" : undefined} onClick={() => setCat(v)}>
                <span className={n ? undefined : "cc-none"}>{l}</span><span className="cc-tree-n">{n}</span></button></div></li>)}
          </ul>
          {repo.length > 0 && <div className="cc-tpl-show" role="group" aria-label="Show">
            <h3 className="cc-au-sec">Show</h3>
            <label className="st-check-line"><input type="checkbox" checked={show.installed} onChange={(e) => setShow({ ...show, installed: e.target.checked })} /> Installed</label>
            <label className="st-check-line"><input type="checkbox" checked={show.notInstalled} onChange={(e) => setShow({ ...show, notInstalled: e.target.checked })} /> Not installed yet</label>
          </div>}
        </nav>
        <div className="cc-tpl-main">
          <div className="cc-tpl-bar"><strong>{cat || "All templates"}</strong> · {shown.length}</div>
          {!data && !error && <p className="cc-none" role="status">Loading…</p>}
          <div className="cc-tpl-grid" role="list" aria-label="Templates">
            {shown.map((t) => { const where = installedIn(t); return (
              <article key={t.source + t.id} className={"cc-tpl-card" + (open?.id === t.id && open.source === t.source ? " is-on" : "")} role="listitem" aria-label={t.name}
                onClick={(e) => { if (!(e.target as HTMLElement).closest("button, a")) setOpen(t); }}>
                <div className="cc-tpl-top"><span className="cc-tpl-logo" aria-hidden="true">{logo(t)}</span>
                  <div><h3>{t.name}</h3><div className="cc-tpl-pub">{t.category} · {t.source === "region" ? "shared by " + t.publisher : t.publisher}</div></div></div>
                <p className="cc-tpl-desc">{t.description}</p>
                <div className="cc-tpl-foot">
                  {where.length ? <><span className="cc-pill is-active"><span className="cc-pill-dot" />Installed</span><span className="cc-none cc-tpl-where">in {where.join(", ")}</span>
                    <a className="ui-btn ui-btn-secondary cc-btn-link" href={"#/automation?q=" + encodeURIComponent(t.agents[0]?.split("/").pop()?.replace(/\.(flow|process)\.json$/, "") ?? t.name)}>Open</a></>
                    : <><span className="cc-none cc-tpl-where">{t.agents.length === 1 ? "1 agent: " + agentName(t.agents[0]) : t.agents.length + " agents"}</span>
                      {can("lifecycle.import") && <Button kind="primary" onClick={() => setDialog({ kind: "install", t })}>Install</Button>}</>}
                </div>
              </article>); })}
          </div>
          {data && !shown.length && <div className="cc-tpl-empty">{all.length ? <p>No template matches.</p> : <>
            <p><strong>No templates to show.</strong> {can("settings.manage") ? "Set the address of a template catalogue in Settings, or upload a signed catalogue file (for a Command Center without internet access)."
              : "An administrator sets up the template catalogue in Settings."}</p>
            {can("settings.manage") && <span className="cc-inline"><a className="ui-btn ui-btn-primary cc-btn-link" href="#/settings">Open Settings</a>
              <Button kind="secondary" onClick={() => setDialog({ kind: "catalogue" })}>Upload a catalogue file</Button></span>}</>}</div>}
        </div>
        {open && <aside className="cc-tpl-side" role="dialog" aria-modal="false" aria-label={"About " + open.name}>
          <div className="cc-pane-head"><span className="cc-tpl-logo is-small" aria-hidden="true">{logo(open)}</span><h2>{open.name}</h2><span className="ov-fill" />
            <button type="button" className="cc-list-icon" aria-label="Close" title="Close" onClick={() => setOpen(null)}>×</button></div>
          <div className="cc-agent-pad">
            <p className="cc-none">{open.source === "region" ? "Shared by " + open.publisher : open.publisher} · {open.category}</p>
            {open.description && <p>{open.description}</p>}
            <h3 className="cc-au-sec">What is inside</h3>
            <table className="cc-au-kv"><tbody>{open.agents.map((a) => <tr key={a}><th scope="row">Agent</th><td>{agentName(a)}</td></tr>)}</tbody></table>
            {installedIn(open).length ? <p><span className="cc-pill is-active"><span className="cc-pill-dot" />Installed</span> in {installedIn(open).join(", ")}</p>
              : <p className="cc-none">Not installed yet. Its agents go into the folder you choose, as new agents you can change.</p>}
          </div>
          <div className="cc-run-foot">
            {can("lifecycle.import") && <Button kind={installedIn(open).length ? "secondary" : "primary"} onClick={() => setDialog({ kind: "install", t: open })}>{installedIn(open).length ? "Install again into…" : "Install"}</Button>}
            <span className="ov-fill" />
            {installedIn(open).length > 0 && <a className="ui-btn ui-btn-primary cc-btn-link" href={"#/automation?q=" + encodeURIComponent(agentName(open.agents[0] ?? open.name))}>Open in Automation</a>}
          </div>
        </aside>}
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
