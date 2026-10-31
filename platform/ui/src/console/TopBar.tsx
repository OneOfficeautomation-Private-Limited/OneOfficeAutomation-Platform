import { useEffect, useMemo, useRef, useState } from "react";
import type { CrApi, CrUser } from "../api/cr";
import { Icon, Menu } from "../components";
import { canSee, MENU_SECTIONS } from "../shell/menu";

/**
 * The console's top bar (design plan, step 1: the skeleton): the product name, where you are (section › page), one
 * search for pages and agents (Ctrl K; a run id opens that run), the open alerts, help (with the classic console until it
 * is retired) and the account menu. As UiPath's header keeps the current location and help, and AA's breadcrumbs lead
 * back to the parent.
 */
const sectionName = (label: string) => label === "AI" ? "AI" : label.charAt(0) + label.slice(1).toLowerCase();
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process|form)\.json$/, "");

export function TopBar({ api, user, page, classicHref, onPage, onSignOut }: { api: CrApi; user: CrUser; page: string; classicHref: string;
  onPage: (id: string) => void; onSignOut: () => void }) {
  const can = (p: string) => user.permissions.includes(p);
  const [alerts, setAlerts] = useState<number | null>(null);
  useEffect(() => {
    if (!can("analytics.view")) return;
    const load = () => api.alerts({ status: "open", limit: "1" }).then((r) => setAlerts(r.open), () => {});
    load(); const t = setInterval(load, 60000); return () => clearInterval(t);
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  const sec = MENU_SECTIONS.find((s) => s.pages.some((p) => p.id === page));
  const label = sec?.pages.find((p) => p.id === page)?.label;

  return (
    <header className="st-top cc-top">
      <div className="st-brand"><span className="st-logo" />One CommandCenter</div>
      <nav aria-label="You are here" className="cc-crumbs">
        {sec && label ? <><button type="button" className="cc-crumb" onClick={() => onPage(sec.pages.find((p) => canSee(p, user) && p.id !== "studio")?.id ?? "home")}>{sectionName(sec.label)}</button>
          <span aria-hidden="true">›</span><span className="cc-crumb-here" aria-current="page">{label}</span></>
          : <span className="cc-crumb-here" aria-current="page">Home</span>}
      </nav>
      <Search api={api} user={user} onPage={onPage} />
      <div className="st-top-actions">
        {alerts != null && <a className="st-top-icon cc-bell" href="#/alerts" aria-label={"Open alerts: " + alerts} title="Open alerts">
          <Icon name="bell" size={16} />{alerts > 0 && <span className="cc-badge">{alerts}</span>}</a>}
        <Menu label="Help" align="end" items={[{ key: "classic", label: "Classic console", description: "Today's console, until it is retired", onSelect: () => { location.href = classicHref; } }]}
          trigger={<button type="button" className="st-top-icon" aria-label="Help" title="Help"><Icon name="help" size={16} /></button>} />
        <Menu label="Your account" align="end" title={user.display_name + " · " + (user.roles?.length ? user.roles.join(", ") : user.role)}
          items={[{ key: "out", label: "Sign out", onSelect: onSignOut }]} trigger={<button type="button" className="st-top-icon cc-user" aria-label={"Account: " + user.display_name} title={user.display_name}>
          <Icon name="person" size={16} /></button>} />
      </div>
    </header>
  );
}

/** One search for the pages a person may open and the agents they may see; a run id goes straight to that run. */
function Search({ api, user, onPage }: { api: CrApi; user: CrUser; onPage: (id: string) => void }) {
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [agents, setAgents] = useState<string[] | null>(null);
  const box = useRef<HTMLInputElement>(null);
  useEffect(() => {   // Ctrl K (or Cmd K) puts the cursor in the search
    const on = (e: KeyboardEvent) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); box.current?.focus(); } };
    addEventListener("keydown", on); return () => removeEventListener("keydown", on);
  }, []);
  const loadAgents = () => { if (agents == null && user.permissions.includes("repo.view")) api.agents().then((a) => setAgents(a.map((x) => x.path)), () => setAgents([])); };
  const t = q.trim().toLowerCase();
  const pages = useMemo(() => !t ? [] : MENU_SECTIONS.flatMap((s) => s.pages.filter((p) => p.id !== "studio" && canSee(p, user) && p.label.toLowerCase().includes(t))
    .map((p) => ({ id: p.id, label: p.label, where: sectionName(s.label) }))).slice(0, 8), [t, user]);
  const found = !t ? [] : (agents ?? []).filter((p) => p.toLowerCase().includes(t)).slice(0, 6);
  const run = /^run_[a-z0-9]+$/i.test(q.trim()) ? q.trim() : null;
  const go = (hash: string) => { setQ(""); setOpen(false); location.hash = hash; };
  return (
    <div className="cc-search" onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setOpen(false); }}>
      <Icon name="search" size={15} />
      <input ref={box} type="search" aria-label="Search pages and agents" placeholder="Search pages and agents" value={q}
        onFocus={() => { setOpen(true); loadAgents(); }} onChange={(e) => { setQ(e.target.value); setOpen(true); }}
        onKeyDown={(e) => { if (e.key === "Escape") { setQ(""); setOpen(false); } if (e.key === "Enter" && run) go("#/activity?run=" + encodeURIComponent(run)); }} />
      <kbd className="cc-kbd">Ctrl K</kbd>
      {open && t && <div className="cc-search-results" role="group" aria-label="Search results">
        {run && <a href={"#/activity?run=" + encodeURIComponent(run)} onClick={() => { setQ(""); setOpen(false); }}>Open the run {run}</a>}
        {pages.length > 0 && <><div className="cc-search-head">Pages</div>{pages.map((p) =>
          <button key={p.id} type="button" onClick={() => { setQ(""); setOpen(false); onPage(p.id); }}>{p.label}<span>{p.where}</span></button>)}</>}
        {found.length > 0 && <><div className="cc-search-head">Agents</div>{found.map((p) =>
          <a key={p} href={"#/automation?q=" + encodeURIComponent(agentName(p))} onClick={() => go("#/automation?q=" + encodeURIComponent(agentName(p)))}>{agentName(p)}<span>{p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : ""}</span></a>)}</>}
        {!run && !pages.length && !found.length && <p>Nothing matches “{q.trim()}”.</p>}
      </div>}
    </div>
  );
}
