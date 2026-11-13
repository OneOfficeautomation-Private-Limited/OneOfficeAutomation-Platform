import { useEffect, useMemo, useRef, useState } from "react";
import type { CrApi, CrUser, Environment } from "../api/cr";
import { BrandMark, Icon, is, keyOf, Menu } from "../components";
import { canSee, MENU_SECTIONS } from "../shell/menu";
import { PhoneNotificationsDialog } from "./PhoneNotifications";

/**
 * The console's top bar (design plan, step 1: the skeleton): the product mark and name (where you are moved above the page title in Console A), one
 * search for pages and agents (Ctrl K; a run id opens that run), the open alerts, help (with the classic console until it
 * is retired) and the account menu. As UiPath's header keeps the current location and help, and AA's breadcrumbs lead
 * back to the parent.
 */
const sectionName = (label: string) => label === "AI" ? "AI" : label.charAt(0) + label.slice(1).toLowerCase();
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process|form)\.json$/, "");

/** Where you are (Console A, 13 Nov 2026): section › page as a small line above the page title; the section leads back. */
export function Breadcrumb({ user, page, onPage }: { user: CrUser; page: string; onPage: (id: string) => void }) {
  const sec = MENU_SECTIONS.find((s) => s.pages.some((p) => p.id === page));
  const label = sec?.pages.find((p) => p.id === page)?.label;
  if (!sec || !label) return null;
  return <nav aria-label="You are here" className="cc-here">
    <button type="button" onClick={() => onPage(sec.pages.find((p) => canSee(p, user))?.id ?? "home")}>{sectionName(sec.label)}</button>
    <span aria-hidden="true">›</span><span aria-current="page">{label}</span>
  </nav>;
}

export function TopBar({ api, user, classicHref, onPage, onSignOut, onShortcuts }: { api: CrApi; user: CrUser; page: string; classicHref: string;
  onPage: (id: string) => void; onSignOut: () => void; onShortcuts: () => void }) {
  const can = (p: string) => user.permissions.includes(p);
  const [alerts, setAlerts] = useState<number | null>(null);
  const [phone, setPhone] = useState(false);
  useEffect(() => {
    if (!can("analytics.view")) return;
    const load = () => api.alerts({ status: "open", limit: "1" }).then((r) => setAlerts(r.open), () => {});
    load(); const t = setInterval(load, 60000); return () => clearInterval(t);
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <header className="st-top cc-top">
      <div className="st-brand"><span className="st-logo is-light"><BrandMark light /></span>One CommandCenter</div>
      {user.environment && <EnvironmentLabel env={user.environment} />}
      <Search api={api} user={user} onPage={onPage} />
      <div className="st-top-actions">
        {alerts != null && <a className="st-top-icon cc-bell" href="#/alerts" aria-label={"Open alerts: " + alerts} title="Open alerts">
          <Icon name="bell" size={16} />{alerts > 0 && <span className="cc-badge">{alerts}</span>}</a>}
        <Menu label="Help" align="end" items={[{ key: "keys", label: "Keyboard shortcuts", description: "Every key, and single-key shortcuts on or off", shortcut: keyOf("app.shortcuts"), onSelect: onShortcuts }, { key: "classic", label: "Classic console", description: "Today's console, until it is retired", onSelect: () => { location.href = classicHref; } }]}
          trigger={<button type="button" className="st-top-icon" aria-label="Help" title="Help"><Icon name="help" size={16} /></button>} />
        <Menu label="Your account" align="end" title={user.display_name + " · " + (user.roles?.length ? user.roles.join(", ") : user.role)}
          items={[{ key: "phone", label: "Phone notifications", description: "New tasks on your phone, with Approve and Reject", onSelect: () => setPhone(true) },
            { key: "out", label: "Sign out", onSelect: onSignOut }]} trigger={<button type="button" className="st-top-icon cc-user" aria-label={"Account: " + user.display_name} title={user.display_name}>
          <Icon name="person" size={16} /></button>} />
      </div>
      {phone && <PhoneNotificationsDialog api={api} onClose={() => setPhone(false)} />}
    </header>
  );
}

/** Which One CommandCenter this is (Settings > General), so nobody changes production thinking it is test. */
export function EnvironmentLabel({ env }: { env: Environment }) {
  return <span className={"cc-env cc-env-" + env.colour} title="This environment">{env.label}</span>;
}

/** One search for the pages a person may open and the agents they may see; a run id goes straight to that run. */
function Search({ api, user, onPage }: { api: CrApi; user: CrUser; onPage: (id: string) => void }) {
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [agents, setAgents] = useState<string[] | null>(null);
  const box = useRef<HTMLInputElement>(null);
  useEffect(() => {   // Ctrl K (or Cmd K) puts the cursor in the search
    const on = (e: KeyboardEvent) => { if (is(e, "app.search")) { e.preventDefault(); box.current?.focus(); } };
    addEventListener("keydown", on); return () => removeEventListener("keydown", on);
  }, []);
  const loadAgents = () => { if (agents == null && user.permissions.includes("repo.view")) api.agents().then((a) => setAgents(a.map((x) => x.path)), () => setAgents([])); };
  const t = q.trim().toLowerCase();
  const pages = useMemo(() => !t ? [] : MENU_SECTIONS.flatMap((s) => s.pages.filter((p) => canSee(p, user) && p.label.toLowerCase().includes(t))
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
      <kbd className="cc-kbd">{keyOf("app.search").replace("+", " ")}</kbd>
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
