import { useCallback, useEffect, useState } from "react";
import type { Alert, CrApi, CrUser, Dashboard, ModelConnection, Operations, Runner, WorkQueue } from "../api/cr";
import { Button, Select } from "../components";

/**
 * Operational Insights > Overview: how agents, runners, queues and AI connections are doing now, on one screen, as
 * AA's Operation Manager dashboard, UiPath's Monitoring overview and Blue Prism Hub's Control Room widgets (checked
 * 31 Oct 2026). Each figure opens the page and filter behind it. Each card shows only what the viewer may see, and
 * the screen refreshes every 30 seconds.
 */
const PERIODS: [string, string, string][] = [["1", "Last 24 hours", "24h"], ["7", "Last 7 days", "7d"]];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const waiting = (q: WorkQueue) => (q.counts.new ?? 0) + (q.counts.retry ?? 0);
/** The Agent Activity address for a tab and filters. */
const activity = (p: Record<string, string>) => "#/activity?" + new URLSearchParams(p).toString();
/** Why a runner needs attention, or null when it is fine. */
function trouble(d: Runner): string | null {
  if (d.status === "disabled") return null;
  if (d.maintenance) return d.maintenance === "drain" ? "Out of service: finishing its runs" : "Out of service";
  if (d.status === "offline") return "Offline" + (d.last_seen ? " since " + when(d.last_seen) : "");
  if (d.over_limit) return "Busy: " + d.over_limit;
  if (d.up_to_date === false) return "OneRunner out of date";
  return null;
}

export function Overview({ api, user, pageHref }: { api: CrApi; user: CrUser; pageHref: (page: string) => string | undefined }) {
  const [days, setDays] = useState("1");
  const [d, setD] = useState<Dashboard | null>(null);
  const [ops, setOps] = useState<Operations | null>(null);
  const [runners, setRunners] = useState<Runner[] | null>(null);
  const [queues, setQueues] = useState<WorkQueue[] | null>(null);
  const [ai, setAi] = useState<ModelConnection[] | null>(null);
  const [alerts, setAlerts] = useState<{ alerts: Alert[]; open: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [at, setAt] = useState<Date | null>(null);
  const can = (p: string) => user.permissions.includes(p);
  const period = PERIODS.find(([v]) => v === days)!;

  const load = useCallback(async () => {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const [a, b, c, q, m, al] = await Promise.allSettled([api.dashboard(tz), api.operations({ days, top: "6" }),
      can("devices.view") ? api.devices() : Promise.reject(), can("queues.view") ? api.queues() : Promise.reject(),
      can("connections.manage") ? api.modelConnections() : Promise.reject(), api.alerts({ status: "open", limit: "6" })]);
    if (a.status === "fulfilled") setD(a.value);
    if (b.status === "fulfilled") setOps(b.value);
    setError(b.status === "rejected" ? (b.reason instanceof Error ? b.reason.message : String(b.reason)) : null);
    setRunners(c.status === "fulfilled" ? c.value : null);
    setQueues(q.status === "fulfilled" ? q.value : null);
    setAi(m.status === "fulfilled" ? m.value : null);
    setAlerts(al.status === "fulfilled" ? al.value : null);
    setAt(new Date());
  }, [api, days]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, [load]);

  const t = ops?.totals;
  const finished = t ? t.success + t.failed : 0;
  const live = (runners ?? []).filter((x) => x.status !== "disabled");
  const online = live.filter((x) => x.status === "online").length;
  const attention = live.map((x) => ({ x, why: trouble(x) })).filter((r) => r.why);
  const overdue = (queues ?? []).reduce((n, q) => n + (q.overdue ?? 0), 0);
  const busyQueues = (queues ?? []).filter((q) => waiting(q) > 0 || (q.overdue ?? 0) > 0).sort((a, b) => (b.overdue ?? 0) - (a.overdue ?? 0) || waiting(b) - waiting(a));
  const budgets = (ai ?? []).filter((c) => (c.tokens_this_month ?? 0) > 0 || c.monthly_tokens);
  const share = (c: ModelConnection) => c.monthly_tokens ? Math.round(100 * (c.tokens_this_month ?? 0) / c.monthly_tokens) : null;

  return (
    <section className="st-page cc-home" aria-labelledby="cc-ov-title">
      <header className="st-page-head">
        <div><h1 id="cc-ov-title">Overview</h1><p>How your agents, runners, work queues and AI connections are doing now, and what needs attention.</p></div>
        <span className="cc-inline">
          <Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} />
          <Button kind="plain" onClick={load}>Refresh</Button>
        </span>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-sums" role="group" aria-label="Figures">
        {alerts && <a className="cc-sum" href="#/alerts"><strong className={alerts.open ? "cc-err" : undefined}>{alerts.open}</strong><span>Open alerts</span></a>}
        {d && can("runs.view") && <>
          <a className="cc-sum" href="#/activity?tab=progress&status=running"><strong>{d.running}</strong><span>Running now</span></a>
          <a className="cc-sum" href="#/activity?tab=progress&status=queued"><strong>{d.queued}</strong><span>Waiting for a runner</span></a>
        </>}
        {t && <>
          <a className="cc-sum" href={activity({ tab: "completed", period: period[2] })}><strong>{t.success_rate == null ? "–" : t.success_rate + "%"}</strong>
            <span>{finished ? "Completed, of " + finished + " finished" : "No run finished"} · {period[1].toLowerCase()}</span></a>
          <a className="cc-sum" href={activity({ tab: "completed", status: "failed", period: period[2] })}><strong className={t.failed ? "cc-err" : undefined}>{t.failed}</strong>
            <span>Failed · {period[1].toLowerCase()}</span></a>
        </>}
        {runners && <a className="cc-sum" href="#/devices"><strong>{online} of {live.length}</strong>
          <span>Runners online{attention.length ? " · " + attention.length + " need attention" : ""}</span></a>}
        {queues && <a className="cc-sum" href="#/queues"><strong className={overdue ? "cc-err" : undefined}>{overdue}</strong>
          <span>Queue items overdue · {queues.reduce((n, q) => n + waiting(q), 0)} waiting</span></a>}
        {d?.hours_saved_30d != null && <a className="cc-sum" href={pageHref("operations")}><strong>{Math.round(d.hours_saved_30d)}</strong><span>Hours saved · last 30 days</span></a>}
      </div>
      <div className="cc-two cc-pad">
        {alerts && alerts.open > 0 && <div className="cc-card cc-card-flat">
          <h2>Open alerts</h2>
          <ul className="cc-alerts-list" aria-label="Open alerts">{alerts.alerts.map((x) => <li key={x.id}>
            <span className={"cc-status cc-sev is-" + x.severity}>{x.severity === "critical" ? "Critical" : "Warning"}</span>
            {x.link ? <a href={x.link}>{x.message}</a> : <span>{x.message}</span>}<span className="st-muted">{when(x.raised)}</span></li>)}</ul>
          <p className="cc-card-foot"><a href="#/alerts">{alerts.open > alerts.alerts.length ? "All " + alerts.open + " open alerts" : "Acknowledge them in Alerts"}</a></p>
        </div>}
        {ops && <div className="cc-card cc-card-flat">
          <h2>Why runs failed</h2>
          {ops.failure_reasons?.length ? <table className="st-table" aria-label="Why runs failed">
            <thead><tr><th scope="col">Error</th><th scope="col">Runs</th><th scope="col">Agents</th><th scope="col">Mostly in</th><th scope="col">Last</th></tr></thead>
            <tbody>{ops.failure_reasons.map((r) => <tr key={r.code}>
              <td><a href={activity({ tab: "completed", status: "failed", q: r.code, period: period[2] })}>{r.code}</a></td>
              <td>{r.runs}</td><td>{r.agents}</td><td>{r.agent ? name(r.agent) : ""}</td><td>{when(r.last)}</td></tr>)}</tbody>
          </table> : <p className="st-muted">No run failed in the {period[1].toLowerCase()}.</p>}
        </div>}
        {ops && <div className="cc-card cc-card-flat">
          <h2>Agents failing most</h2>
          {ops.most_failed.length ? <table className="st-table" aria-label="Agents failing most">
            <thead><tr><th scope="col">Agent</th><th scope="col">Failed</th><th scope="col">Of finished</th><th scope="col">Common errors</th></tr></thead>
            <tbody>{ops.most_failed.map((r) => <tr key={r.bot_path}>
              <td><a href={activity({ tab: "completed", status: "failed", bot: r.bot_path, period: period[2] })}>{name(r.bot_path)}</a></td>
              <td>{r.failed}</td><td>{r.failure_rate}%</td><td>{r.top_errors.join(", ")}</td></tr>)}</tbody>
          </table> : <p className="st-muted">No agent failed in the {period[1].toLowerCase()}.</p>}
        </div>}
        {runners && <div className="cc-card cc-card-flat">
          <h2>Runners needing attention</h2>
          {attention.length ? <table className="st-table" aria-label="Runners needing attention">
            <thead><tr><th scope="col">Runner</th><th scope="col">Why</th><th scope="col">CPU</th><th scope="col">Memory</th></tr></thead>
            <tbody>{attention.map(({ x, why }) => <tr key={x.id}><td><a href="#/devices">{x.nickname || x.name}</a></td><td className="cc-err">{why}</td>
              <td>{x.cpu_pct != null ? Math.round(x.cpu_pct) + "%" : ""}</td><td>{x.mem_pct != null ? Math.round(x.mem_pct) + "%" : ""}</td></tr>)}</tbody>
          </table> : <p className="st-muted">{live.length === 1 ? "Your runner is online, up to date and within its limits." : live.length ? "All " + live.length + " runners are online, up to date and within their limits." : "No runners yet."}</p>}
        </div>}
        {queues && <div className="cc-card cc-card-flat">
          <h2>Work queues with items waiting</h2>
          {busyQueues.length ? <table className="st-table" aria-label="Work queues with items waiting">
            <thead><tr><th scope="col">Queue</th><th scope="col">Waiting</th><th scope="col">Overdue</th><th scope="col">In progress</th><th scope="col">Processing</th></tr></thead>
            <tbody>{busyQueues.map((q) => <tr key={q.id}><td><a href="#/queues">{q.name}</a></td><td>{waiting(q)}</td>
              <td className={q.overdue ? "cc-err" : undefined}>{q.overdue ?? 0}{q.sla_minutes ? " (target " + q.sla_minutes + " min)" : ""}</td>
              <td>{q.counts.in_progress ?? 0}</td><td className={q.processing ? undefined : "cc-err"}>{q.processing ? "On" : "Off"}</td></tr>)}</tbody>
          </table> : <p className="st-muted">{queues.length ? "No item is waiting in any queue." : "No work queues yet."}</p>}
        </div>}
        {ai && <div className="cc-card cc-card-flat">
          <h2>AI use this month</h2>
          {budgets.length ? <table className="st-table" aria-label="AI use this month">
            <thead><tr><th scope="col">Model connection</th><th scope="col">Tokens used</th><th scope="col">Monthly budget</th><th scope="col">Used</th></tr></thead>
            <tbody>{budgets.map((c) => { const pct = share(c); return <tr key={c.name}><td><a href="#/connections">{c.name}</a></td>
              <td>{(c.tokens_this_month ?? 0).toLocaleString()}</td><td>{c.monthly_tokens ? c.monthly_tokens.toLocaleString() : "No limit"}</td>
              <td className={pct != null && pct >= 80 ? "cc-err" : undefined}>{pct == null ? "" : <span className="cc-meter"><progress max={100} value={Math.min(pct, 100)} aria-label={"Budget used by " + c.name} /> {pct}%</span>}</td></tr>; })}</tbody>
          </table> : <p className="st-muted">{ai.length ? "No tokens used through any connection this month." : "No model connections yet."}</p>}
          {can("audit.view") && <p className="cc-card-foot"><a href="#/governance">Every AI call in AI governance</a></p>}
        </div>}
      </div>
      {at && <p className="st-muted cc-pad cc-updated" role="status">Updated {at.toLocaleTimeString()}; refreshes every 30 seconds.</p>}
    </section>
  );
}
