import { useCallback, useEffect, useMemo, useState } from "react";
import type { AgentTemplate, CrApi, CrUser } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * Build > Agent Templates, built as AA's Bot Store and UiPath's Marketplace (checked 31 Oct 2026): ready-made agents to
 * install into the agent repository and adapt, from ONEOFFICEAUTOMATION and, where the service allows, shared in your
 * region. Cards by category with search; Install into a folder (agents already there are kept); share an agent as a
 * template (approved by the operators); a signed catalogue file for a Command Center without internet access.
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
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => { try { setData(await api.templates()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (data?.templates ?? []).filter((t) => (!cat || t.category === cat)
    && (!q.trim() || (t.name + " " + (t.description ?? "") + " " + t.publisher).toLowerCase().includes(q.trim().toLowerCase()))), [data, q, cat]);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-tpl-title">
      <header className="st-page-head">
        <div><h1 id="cc-tpl-title">Agent Templates</h1><p>Ready-made agents to install into your agent repository and adapt: from ONEOFFICEAUTOMATION{data?.can_share ? " and shared in your region" : ""}.</p></div>
        <span className="cc-inline">
          {data?.can_share && <Button kind="secondary" onClick={() => setDialog({ kind: "share" })}>Share an agent</Button>}
          {can("settings.manage") && <Button kind="secondary" onClick={() => setDialog({ kind: "catalogue" })}>Upload a catalogue file</Button>}
        </span>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {data?.problem && <p className="st-alert">{data.problem}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search templates" placeholder="Search templates" value={q} onChange={(e) => setQ(e.target.value)} />
        <Select aria-label="Category" value={cat} onChange={(e) => setCat(e.target.value)} options={[{ value: "", label: "All categories" }, ...(data?.categories ?? []).map((c) => ({ value: c, label: c }))]} />
        <span className="cc-chosen">{shown.length} template{shown.length === 1 ? "" : "s"}</span>
      </div>
      {!data && !error && <p className="st-muted" role="status">Loading…</p>}
      <div className="cc-tpls" role="list" aria-label="Templates">
        {shown.map((t) => (
          <article key={t.source + t.id} className="cc-tpl" role="listitem" aria-label={t.name}>
            <div className="st-muted">{t.category} · {t.source === "region" ? "shared by " + t.publisher : t.publisher}</div>
            <h3>{t.name}</h3>
            <p>{t.description}</p>
            <div className="st-muted">{t.agents.length === 1 ? "1 agent: " + agentName(t.agents[0]) : t.agents.length + " agents"}</div>
            {can("lifecycle.import") && <Button kind="primary" onClick={() => setDialog({ kind: "install", t })}>Install</Button>}
          </article>
        ))}
        {data && !shown.length && <p className="st-muted">{data.templates.length ? "No template matches." : "No templates yet: ONEOFFICEAUTOMATION's catalogue has none to offer."
          + (can("settings.manage") ? " Upload a catalogue file, or set the template catalogue address in Settings." : "")}</p>}
      </div>
      {dialog?.kind === "install" && <Install api={api} t={dialog.t} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); }} />}
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
    if (r.ok) onDone("Installed " + t.name + " in " + r.folder + ": open it in the Agent Repository.");
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
