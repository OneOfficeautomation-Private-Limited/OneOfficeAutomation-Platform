import "./consoleApi";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { AuditRecord, CrApi, CrUser } from "../../api/cr";
import type { AuditFigures } from "./consoleApi";
import { Button, HelpButton, Select, usePersistent, type HelpTopic, PageError } from "../../components";
import { AREAS, wordsOf } from "../../console/Audit";
import { BusinessAgents } from "./BusinessAgents";
import { Capacity } from "./Capacity";
import { Dashboards } from "./Dashboards";
import { RefreshButton, rowClick, when } from "../../console/ListParts";
import { Operations } from "./Operations";
import { QueueInsights } from "../../console/QueueInsights";
import { Value } from "./Value";

/**
 * Operational Insights, item 7 step 1 (1 Nov 2026, design A on the canvas). AA splits Bot Insight into Operations and
 * Business dashboards, so the menu is Overview · Alerts · Operations · Business. Operations holds Agents (the runs),
 * Runners (capacity, with AA's Devices dashboard figures), Work queues and Audit (AA's Audit dashboard) as tabs, and AI
 * usage opens AI governance; Business holds Value and Dashboards. The tab is in the address (#/operations?tab=runners),
 * so a tab can be linked to and Back works; the old pages' addresses open their tab (ConsoleApp's MOVED).
 */
type Tab = { id: string; label: string; perm: string };
const OPERATIONS: Tab[] = [{ id: "agents", label: "Agents", perm: "analytics.view" }, { id: "trends", label: "Trends", perm: "analytics.view" }, { id: "runners", label: "Runners", perm: "analytics.view" },
  { id: "queues", label: "Work queues", perm: "queues.view" }, { id: "audit", label: "Audit", perm: "audit.view" }];
const BUSINESS: Tab[] = [{ id: "agents", label: "Agents", perm: "analytics.view" }, { id: "value", label: "Value", perm: "analytics.view" }, { id: "dashboards", label: "Dashboards", perm: "analytics.view" }];
const tabInAddress = () => new URLSearchParams(location.hash.split("?")[1] ?? "").get("tab");

/** The tab in the address, among those the person may see (the first when none or another is named). */
function useTab(page: string, tabs: Tab[]) {
  const [asked, setAsked] = useState(tabInAddress);
  useEffect(() => { const on = () => setAsked(tabInAddress()); addEventListener("hashchange", on); return () => removeEventListener("hashchange", on); }, []);
  const tab = (tabs.find((t) => t.id === asked) ?? tabs[0])?.id;
  return [tab, (id: string) => { location.hash = "/" + page + "?tab=" + id; }] as const;
}

function TabStrip({ label, tabs, current, onTab, extra }: { label: string; tabs: Tab[]; current?: string; onTab: (id: string) => void; extra?: ReactNode }) {
  return <div className="ov-tabs cc-ins-tabs" role="tablist" aria-label={label}>
    {tabs.map((t) => <button key={t.id} type="button" role="tab" className="ov-tab" aria-selected={t.id === current} onClick={() => onTab(t.id)}>{t.label}</button>)}
    {extra}
  </div>;
}

/** Operational Insights > Operations: Agents, Runners, Work queues and Audit as tabs, and AI usage (AI governance). */
export function OperationsPage({ api, user }: { api: CrApi; user: CrUser }) {
  const can = (p: string) => user.permissions.includes(p);
  const tabs = OPERATIONS.filter((t) => can(t.perm));
  const [tab, go] = useTab("operations", tabs);
  const strip = <TabStrip label="Operations" tabs={tabs} current={tab} onTab={go}
    extra={can("audit.view") ? <a className="ov-tab cc-ins-out" href="#/governance" title="Opens AI governance: AI calls, tokens and cost">AI usage ↗</a> : null} />;
  if (tab === "runners") return <Capacity api={api} user={user} title="Operations" tabs={strip} />;
  if (tab === "queues") return <QueueInsights api={api} user={user} title="Operations" tabs={strip} />;
  if (tab === "audit") return <AuditFiguresTab api={api} title="Operations" tabs={strip} />;
  if (tab === "agents") return <Operations api={api} user={user} tabs={strip} />;
  if (tab === "trends") return <Operations key="trends" api={api} user={user} tabs={strip} part="trends" />;
  return <section className="st-page cc-list"><header className="cc-list-head"><h1>Operations</h1></header><p className="cc-none cc-pad">You may not see operations figures.</p></section>;
}

/** Operational Insights > Business: Agents (each agent's own dashboard, data profile and Compare), Value and Dashboards as tabs. */
export function BusinessPage({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const tabs = BUSINESS.filter((t) => user.permissions.includes(t.perm));
  const [tab, go] = useTab("business", tabs);
  const strip = <TabStrip label="Business" tabs={tabs} current={tab} onTab={go} />;
  if (tab === "agents") return <BusinessAgents api={api} user={user} onNotice={onNotice} title="Business" tabs={strip} />;
  if (tab === "dashboards") return <Dashboards api={api} user={user} onNotice={onNotice} title="Business" tabs={strip} />;
  if (tab === "value") return <Value api={api} user={user} onNotice={onNotice} title="Business" tabs={strip} />;
  return <section className="st-page cc-list"><header className="cc-list-head"><h1>Business</h1></header><p className="cc-none cc-pad">You may not see business figures.</p></section>;
}

const PERIODS: [string, string][] = [["1", "Last 24 hours"], ["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]];
const RESULTS: [string, string][] = [["ok", "Done"], ["failed", "Failed"], ["denied", "Denied"]];
const HELP_AUDIT: HelpTopic = { title: "Audit", about: "Who did what over a period, from the Audit Trail: what kind of activity, by whom, with what result, "
  + "and on which days; and the latest actions that failed or were denied. It follows AA's Audit dashboard. You need the Audit Trail's permission.",
  items: [
    { name: "Period, Who, Activity, Result", text: "Narrow every figure and list. Clicking a bar sets the matching filter; Clear filters removes them." },
    { name: "People", text: "How many people did something in the period; the system and runners are counted apart." },
    { name: "Sign-ins", text: "Sign-ins with a password or single sign-on, and how many failed. Many failed sign-ins for one person can mean a guessed password." },
    { name: "Not done", text: "Actions that failed or were refused for lack of permission. The list below shows the latest 20." },
    { name: "Open in Audit Trail", text: "Opens the Audit Trail with the same filters, where you can read each record, its changes, and export them." },
  ] };
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const kindName = (k: string) => AREAS.find(([p]) => p === k + ".")?.[1] ?? (k === "mail" ? "E-mail" : k.charAt(0).toUpperCase() + k.slice(1));
/** Who did it, in plain words: a runner by its name, System, or the person's user name. */
const whoName = (actor: string, is?: string) => actor.startsWith("agent:") ? "Runner " + actor.slice(6).split(" for ")[0] : is === "system" || actor === "system" ? (actor === "system" ? "System" : actor) : actor;
const day = (d: string) => new Date(d + "T12:00:00").toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
const detailText = (d?: string | null) => { if (!d) return ""; try { const v = JSON.parse(d); return typeof v === "string" ? v : Object.entries(v).map(([k, x]) => k + ": " + String(x)).join(", "); } catch { return d; } };

/** Bars with counts; a bar is a button when picking it sets a filter. */
function CountBars({ items, label, onPick, tone }: { items: { key: string; label: string; value: number }[]; label: string; onPick?: (key: string) => void; tone?: (key: string) => string | undefined }) {
  const max = Math.max(1, ...items.map((x) => x.value));
  if (!items.length) return <p className="st-muted">Nothing in the period.</p>;
  return <ul className="cc-hbars cc-ins-bars" aria-label={label}>{items.map((x) => <li key={x.key}>
    {onPick ? <button type="button" className="cc-hbar-label cc-ins-pick" title={"Show only " + x.label} onClick={() => onPick(x.key)}>{x.label}</button> : <span className="cc-hbar-label">{x.label}</span>}
    <span className="cc-hbar-track"><span className={"cc-hbar-fill" + (tone?.(x.key) ? " " + tone(x.key) : "")} style={{ width: (100 * x.value / max) + "%" }} /></span>
    <span className="cc-hbar-value">{x.value}</span></li>)}</ul>;
}

/** Operations > Audit: AA's Audit dashboard over our Audit Trail. */
function AuditFiguresTab({ api, title, tabs }: { api: CrApi; title: string; tabs: ReactNode }) {
  const [days, setDays] = usePersistent("insights.audit.days", "30");
  const [f, setF] = useState<Record<string, string>>({});
  const [a, setA] = useState<AuditFigures | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => api.auditFigures({ days, tz: Intl.DateTimeFormat().resolvedOptions().timeZone, ...Object.fromEntries(Object.entries(f).filter(([, v]) => v)) })
    .then((r) => { setA(r); setError(null); }, (e) => setError(message(e))), [api, days, f]);
  useEffect(() => { load(); const t = setInterval(load, 60000); return () => clearInterval(t); }, [load]);

  const label = PERIODS.find(([v]) => v === days)?.[1].toLowerCase() ?? "";
  /** The Audit Trail with the same filters (and a record's own object, when given). */
  const trail = (extra: Record<string, string> = {}) => "#/audit?" + new URLSearchParams({ range: ["1", "7", "30"].includes(days) ? days : "all",
    ...(f.actor ? { actor: f.actor } : {}), ...(f.kind ? { area: f.kind + "." } : {}), ...(f.result ? { result: f.result } : {}), ...extra }).toString();
  const count = (r: string) => a?.by_result.find((x) => x.result === r)?.entries ?? 0;
  const actorsShown = a ? a.by_actor.slice(0, 7) : [];
  const days14 = a ? a.by_day.slice(-14).reverse() : [];

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-ins-title">
      <header className="cc-list-head">
        <h1 id="cc-ins-title">{title}</h1>
        <HelpButton topic={HELP_AUDIT} />
        <span className="ov-fill" />
        <Button kind="secondary" onClick={() => { location.hash = trail(); }}>Open in Audit Trail</Button>
      </header>
      {tabs}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-chip"><Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} /></span>
          <Select aria-label="Who" value={f.actor ?? ""} onChange={(e) => setF({ ...f, actor: e.target.value })}
            options={[{ value: "", label: "Everyone" }, ...[...new Set([...(f.actor ? [f.actor] : []), ...(a?.by_actor ?? []).map((x) => x.actor)])].map((x) => ({ value: x, label: whoName(x) }))]} />
          <Select aria-label="Activity" value={f.kind ?? ""} onChange={(e) => setF({ ...f, kind: e.target.value })}
            options={[{ value: "", label: "Any activity" }, ...[...new Set([...(f.kind ? [f.kind] : []), ...(a?.by_kind ?? []).map((x) => x.kind)])].map((x) => ({ value: x, label: kindName(x) }))]} />
          <Select aria-label="Result" value={f.result ?? ""} onChange={(e) => setF({ ...f, result: e.target.value })}
            options={[{ value: "", label: "Any result" }, ...RESULTS.map(([v, l]) => ({ value: v, label: l }))]} />
          {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {error && <PageError text={error} />}
      {!a && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {a?.capped && <p className="st-alert">Only the latest {a.entries.toLocaleString()} entries of the period are counted: choose a shorter period or a filter.</p>}
      {a && <>
        <div className="cc-sums" role="group" aria-label="Figures">
          <a className="cc-sum" href={trail()}><strong>{a.entries.toLocaleString()}</strong><span>Audit entries · {label}</span></a>
          <div className="cc-sum is-static"><strong>{a.people}</strong><span>{a.people === 1 ? "Person" : "People"} · plus the system and runners</span></div>
          <button type="button" className="cc-sum" onClick={() => setF({ ...f, kind: "auth" })} title="Show only sign-ins"><strong>{a.sign_ins}</strong>
            <span>Sign-ins · {a.failed_sign_ins} failed</span></button>
          <a className="cc-sum" href="#not-done" onClick={(e) => { e.preventDefault(); document.getElementById("cc-ins-notdone")?.scrollIntoView({ block: "nearest" }); }}>
            <strong className={count("failed") + count("denied") ? "cc-err" : undefined}>{count("failed") + count("denied")}</strong><span>Not done · {count("failed")} failed, {count("denied")} denied</span></a>
          <div className="cc-sum is-static"><strong>{a.busiest_day ? a.busiest_day.entries : 0}</strong><span>Busiest day{a.busiest_day ? " · " + day(a.busiest_day.day) : ""}</span></div>
        </div>
        <div className="cc-ops-charts cc-ins-charts">
          <div className="cc-card cc-card-flat"><h2>By activity</h2>
            <CountBars label="Audit entries by activity" items={a.by_kind.slice(0, 9).map((x) => ({ key: x.kind, label: kindName(x.kind), value: x.entries }))} onPick={(k) => setF({ ...f, kind: k })} /></div>
          <div className="cc-card cc-card-flat"><h2>By who</h2>
            <CountBars label="Audit entries by who" items={actorsShown.map((x) => ({ key: x.actor, label: whoName(x.actor, x.is), value: x.entries }))} onPick={(k) => setF({ ...f, actor: k })} />
            <h2 className="cc-ins-h2">By result</h2>
            <CountBars label="Audit entries by result" items={a.by_result.map((x) => ({ key: x.result, label: RESULTS.find(([v]) => v === x.result)?.[1] ?? x.result, value: x.entries }))}
              onPick={(k) => setF({ ...f, result: k })} tone={(k) => k === "failed" ? "is-bad" : k === "denied" ? "is-warn" : undefined} /></div>
          <div className="cc-card cc-card-flat"><h2>Entries per day</h2>
            <CountBars label="Audit entries per day" items={days14.map((x) => ({ key: x.day, label: day(x.day), value: x.entries }))} />
            {a.by_day.length > 14 && <p className="cc-card-foot st-muted">The latest 14 days with entries; times in {a.time_zone}.</p>}</div>
        </div>
        <div className="cc-list-panel cc-ops-lists" id="cc-ins-notdone">
          <div className="ov-tabs" role="presentation"><span className="ov-tab cc-ins-head">Failed and denied<span className={"ov-cnt" + (a.not_done.length ? " is-bad" : "")}>{a.not_done.length}</span></span>
            <span className="ov-fill" /><a className="cc-ins-more" href={trail({ result: f.result || "failed" })}>In the Audit Trail</a></div>
          <div className="cc-list-scroll"><table className="cc-list-table" aria-label="Failed and denied">
            <thead><tr><th scope="col">When</th><th scope="col">Who</th><th scope="col">What</th><th scope="col">On</th><th scope="col">Result</th><th scope="col">Details</th></tr></thead>
            <tbody>{a.not_done.map((r: AuditRecord) => <tr key={r.seq} title="Open it in the Audit Trail"
              onClick={rowClick(() => { location.hash = trail({ actor: r.actor, result: r.result, ...(r.object_id ? { object_id: r.object_id } : {}) }); })}>
              <td>{when(r.ts)}</td><td className="cc-clip cc-clip-sm" title={whoName(r.actor)}>{whoName(r.actor)}</td><td>{wordsOf(r.action)}</td><td className="cc-clip cc-clip-sm" title={r.object_id ?? undefined}>{r.object_id ?? ""}</td>
              <td><span className={"cc-pill is-" + (r.result === "denied" ? "pending" : "failed")}><span className="cc-pill-dot" />{r.result === "denied" ? "Denied" : "Failed"}</span></td>
              <td className="cc-clip cc-clip-sm" title={detailText(r.details) || undefined}>{detailText(r.details)}</td></tr>)}
              {!a.not_done.length && <tr className="is-static"><td colSpan={6} className="cc-none">Nothing failed or was denied in the period.</td></tr>}</tbody>
          </table></div>
        </div>
      </>}
    </section>
  );
}
