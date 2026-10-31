import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { Alert, AlertRule, CrApi, CrUser, Dashboard, ModelConnection, Operations, Run, Runner, WorkQueue } from "../api/cr";

/**
 * Operational Insights > Overview: how agents, runners, queues and AI connections are doing now, on one screen, as
 * AA's Operation Manager dashboard, UiPath's Monitoring overview and Blue Prism Hub's Control Room widgets (checked
 * 31 Oct 2026). Laid out as the design plan's step 2b (dense and clean): one row of figures with an icon each, what
 * needs attention in one tabbed panel, the latest runs as a table, and short side panels; "all clear" is one line.
 * Each figure opens the page behind it. Only what the viewer may see is shown; it refreshes every 30 seconds.
 */
const PERIODS: [string, string, string][] = [["1", "24 hours", "24h"], ["7", "7 days", "7d"]];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const time = (t?: string | null) => {
  if (!t) return "";
  const d = new Date(t);
  return d.toDateString() === new Date().toDateString() ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : d.toLocaleDateString([], { day: "numeric", month: "short" }) + " " + d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
};
const took = (ms?: number | null) => ms == null ? "" : ms < 60000 ? Math.round(ms / 1000) + " s" : Math.floor(ms / 60000) + " min " + Math.round((ms % 60000) / 1000) + " s";
const short = (n: number) => n >= 1_000_000 ? Math.round(n / 100_000) / 10 + "M" : n >= 1000 ? Math.round(n / 100) / 10 + "k" : String(n);
const waiting = (q: WorkQueue) => (q.counts.new ?? 0) + (q.counts.retry ?? 0);
const errCode = (e?: string | null) => { if (!e) return ""; try { return JSON.parse(e).code ?? ""; } catch { return ""; } };
/** The Agent Activity address for a tab and filters. */
const activity = (p: Record<string, string>) => "#/activity?" + new URLSearchParams(p).toString();
/** Why a runner needs attention, or null when it is fine. */
function trouble(d: Runner): string | null {
  if (d.status === "disabled") return null;
  if (d.maintenance) return d.maintenance === "drain" ? "Out of service, finishing its runs" : "Out of service";
  if (d.status === "offline") return "Offline" + (d.last_seen ? " since " + time(d.last_seen) : "");
  if (d.over_limit) return "Busy: " + d.over_limit;
  if (d.up_to_date === false) return "OneRunner out of date";
  return null;
}

/** A small coloured tile with a white sign, one per kind of thing (AA's object icons). */
type Kind = "alert" | "run" | "ok" | "failed" | "runner" | "queue" | "ai" | "hours";
const KIND: Record<Kind, [string, ReactNode]> = {
  alert: ["#b42318", <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />],
  run: ["#2f6fde", <path d="M8 5v14l11-7z" fill="#fff" stroke="none" />],
  ok: ["#1d7a46", <path d="m5 12 5 5 9-10" />],
  failed: ["#b42318", <path d="M6 6l12 12M18 6 6 18" />],
  runner: ["#0f766e", <><rect x="3" y="4" width="18" height="12" rx="1" /><path d="M8 20h8" /></>],
  queue: ["#0e7490", <path d="M4 6h16M4 12h16M4 18h10" />],
  ai: ["#7c3aed", <path d="m12 3 2 5 5 2-5 2-2 5-2-5-5-2 5-2z" fill="#fff" stroke="none" />],
  hours: ["#1d7a46", <><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></>],
};
const Tile = ({ kind }: { kind: Kind }) => <span className="ov-ic" style={{ background: KIND[kind][0] }} aria-hidden="true">
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">{KIND[kind][1]}</svg></span>;

type Tab = "summary" | "alerts" | "failed" | "runners" | "queues";

export function Overview({ api, user, pageHref }: { api: CrApi; user: CrUser; pageHref: (page: string) => string | undefined }) {
  const [days, setDays] = useState("1");
  const [d, setD] = useState<Dashboard | null>(null);
  const [ops, setOps] = useState<Operations | null>(null);
  const [runners, setRunners] = useState<Runner[] | null>(null);
  const [queues, setQueues] = useState<WorkQueue[] | null>(null);
  const [ai, setAi] = useState<ModelConnection[] | null>(null);
  const [alerts, setAlerts] = useState<{ alerts: Alert[]; open: number } | null>(null);
  const [rules, setRules] = useState<AlertRule[] | null>(null);
  const [latest, setLatest] = useState<Run[] | null>(null);
  const [tab, setTab] = useState<Tab>("summary");
  const [error, setError] = useState<string | null>(null);
  const [at, setAt] = useState<Date | null>(null);
  const can = (p: string) => user.permissions.includes(p);
  const period = PERIODS.find(([v]) => v === days)!;

  const load = useCallback(async () => {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const no = Promise.reject();
    no.catch(() => {});
    const [a, b, c, q, m, al, ru, la] = await Promise.allSettled([api.dashboard(tz), api.operations({ days, top: "6" }),
      can("devices.view") ? api.devices() : no, can("queues.view") ? api.queues() : no,
      can("connections.manage") ? api.modelConnections() : no, api.alerts({ status: "open", limit: "50" }), api.alertRules(),
      can("runs.view") ? api.runs({ limit: "6" }) : no]);
    if (a.status === "fulfilled") setD(a.value);
    if (b.status === "fulfilled") setOps(b.value);
    setError(b.status === "rejected" ? (b.reason instanceof Error ? b.reason.message : String(b.reason)) : null);
    setRunners(c.status === "fulfilled" ? c.value : null);
    setQueues(q.status === "fulfilled" ? q.value : null);
    setAi(m.status === "fulfilled" ? m.value : null);
    setAlerts(al.status === "fulfilled" ? al.value : null);
    setRules(ru.status === "fulfilled" ? ru.value : null);
    setLatest(la.status === "fulfilled" ? la.value.runs : null);
    setAt(new Date());
  }, [api, days]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, [load]);

  const t = ops?.totals;
  const finished = t ? t.success + t.failed : 0;
  const live = (runners ?? []).filter((x) => x.status !== "disabled");
  const online = live.filter((x) => x.status === "online").length;
  const attention = live.map((x) => ({ x, why: trouble(x)! })).filter((r) => r.why);
  const overdue = (queues ?? []).reduce((n, q) => n + (q.overdue ?? 0), 0);
  const lateQueues = (queues ?? []).filter((q) => (q.overdue ?? 0) > 0 || (!q.processing && waiting(q) > 0));
  const tokens = (ai ?? []).reduce((n, c) => n + (c.tokens_this_month ?? 0), 0);
  const budget = (ai ?? []).reduce((n, c) => n + (c.monthly_tokens ?? 0), 0);
  const critical = (alerts?.alerts ?? []).filter((a) => a.severity === "critical").length;
  const reasons = ops?.failure_reasons ?? [];
  const counts: Record<Exclude<Tab, "summary">, number | null> = { alerts: alerts ? alerts.open : null, failed: t ? t.failed : null,
    runners: runners ? attention.length : null, queues: queues ? lateQueues.length : null };
  const problems = Object.values(counts).reduce<number>((n, v) => n + (v ?? 0), 0);
  const setUp = [
    queues && !queues.length && can("queues.manage") && { kind: "queue" as Kind, text: "No work queues yet", link: "Create one", href: "#/queues" },
    ai && !ai.length && { kind: "ai" as Kind, text: "No model connections yet", link: "Add one", href: "#/connections" },
    rules && !rules.length && can("alerts.manage") && { kind: "alert" as Kind, text: "No alert rules yet", link: "Make one", href: "#/alerts" },
    d && d.hours_saved_30d === 0 && { kind: "hours" as Kind, text: "No hours saved counted yet", link: "Set minutes saved", href: "#/value" },
  ].filter(Boolean) as { kind: Kind; text: string; link: string; href: string }[];
  const figs: { kind: Kind; label: string; value: string; meta: string; href?: string; bad?: boolean }[] = [
    ...(alerts ? [{ kind: "alert" as Kind, label: "Open alerts", value: String(alerts.open), meta: critical ? critical + " critical" : "none critical", href: "#/alerts", bad: alerts.open > 0 }] : []),
    ...(d && can("runs.view") ? [{ kind: "run" as Kind, label: "Running", value: String(d.running), meta: d.queued + " waiting", href: activity({ tab: "progress" }) }] : []),
    ...(t ? [{ kind: "ok" as Kind, label: "Completed", value: t.success_rate == null ? "–" : t.success_rate + "%", meta: finished ? "of " + finished + " finished" : "no run finished", href: activity({ tab: "completed", period: period[2] }) },
      { kind: "failed" as Kind, label: "Failed", value: String(t.failed), meta: "last " + period[1], href: activity({ tab: "completed", status: "failed", period: period[2] }), bad: t.failed > 0 }] : []),
    ...(runners ? [{ kind: "runner" as Kind, label: "Runners", value: online + " / " + live.length, meta: attention.length ? attention.length + " need attention" : "all fine", href: "#/devices", bad: attention.length > 0 }] : []),
    ...(queues ? [{ kind: "queue" as Kind, label: "Queue items", value: String(queues.reduce((n, q) => n + waiting(q), 0)), meta: overdue + " overdue", href: "#/queueinsights", bad: overdue > 0 }] : []),
    ...(ai ? [{ kind: "ai" as Kind, label: "AI tokens", value: short(tokens), meta: budget ? Math.round(100 * tokens / budget) + "% of budget" : "this month", href: "#/connections", bad: budget > 0 && tokens >= 0.8 * budget }] : []),
    ...(d?.hours_saved_30d != null ? [{ kind: "hours" as Kind, label: "Hours saved", value: String(Math.round(d.hours_saved_30d)), meta: "last 30 days", href: pageHref("value") }] : []),
  ];
  const tabs: [Tab, string][] = [["summary", "Needs attention"], ["alerts", "Alerts"], ["failed", "Failed runs"], ["runners", "Runners"], ["queues", "Queues"]];

  return (
    <section className="st-page cc-home ov" aria-labelledby="cc-ov-title">
      <header className="ov-head">
        <h1 id="cc-ov-title">Overview</h1>
        <span className="ov-info" title="How your agents, runners, work queues and AI connections are doing now, and what needs attention" aria-label="About this page" role="img">i</span>
        <span className="ov-fill" />
        {at && <span className="ov-meta" role="status">Updated {at.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>}
        <span className="ov-seg" role="group" aria-label="Period">{PERIODS.map(([v, l]) =>
          <button key={v} type="button" aria-pressed={days === v} onClick={() => setDays(v)}>{l}</button>)}</span>
        <button type="button" className="ov-icon-btn" aria-label="Refresh" title="Refresh" onClick={load}>
          <svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><path d="M21 12a9 9 0 1 1-3-6.7L21 8" /><path d="M21 3v5h-5" /></svg></button>
        {can("alerts.manage") && <a className="ui-btn ui-btn-primary" href="#/alerts?new=1">New alert rule</a>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}

      {figs.length > 0 && <div className="ov-panel ov-figs" role="group" aria-label="Figures" style={{ gridTemplateColumns: `repeat(${figs.length}, minmax(0, 1fr))` }}>
        {figs.map((f) => <a key={f.label} className="ov-fig" href={f.href}>
          <span className="ov-fig-l"><Tile kind={f.kind} />{f.label}</span>
          <span className={"ov-fig-v" + (f.bad ? " is-bad" : "")}>{f.value}</span>
          <span className="ov-fig-m">{f.meta}</span></a>)}
      </div>}

      <div className="ov-grid">
        <div className="ov-main">
          <div className="ov-panel">
            <div className="ov-tabs" role="tablist" aria-label="Needs attention">{tabs.map(([k, l]) => {
              const n = k === "summary" ? null : counts[k];
              if (k !== "summary" && n == null) return null;
              return <button key={k} type="button" role="tab" aria-selected={tab === k} className="ov-tab" onClick={() => setTab(k)}>
                {l}{n != null && <span className={"ov-cnt" + (n ? " is-bad" : "")}>{n}</span>}</button>;
            })}</div>
            {tab === "summary" && (problems ? <ul className="ov-rows" aria-label="What needs attention">
              {(alerts?.alerts ?? []).slice(0, 3).map((a) => <li key={a.id}><Tile kind="alert" />{a.link ? <a href={a.link}>{a.message}</a> : a.message}<span className="ov-r">{time(a.raised)}</span></li>)}
              {t && t.failed > 0 && <li><Tile kind="failed" /><a href={activity({ tab: "completed", status: "failed", period: period[2] })}>{t.failed} run{t.failed === 1 ? "" : "s"} failed in the last {period[1]}</a>
                <span className="ov-r">{reasons[0] ? "mostly " + reasons[0].code : ""}</span></li>}
              {attention.map(({ x, why }) => <li key={x.id}><Tile kind="runner" /><a href="#/devices">{x.nickname || x.name}</a><span className="ov-r ov-bad">{why}</span></li>)}
              {lateQueues.map((q) => <li key={q.id}><Tile kind="queue" /><a href="#/queueinsights">{q.name}</a>
                <span className="ov-r ov-bad">{q.overdue ? q.overdue + " overdue" : "processing is off, " + waiting(q) + " waiting"}</span></li>)}
            </ul> : <p className="ov-ok"><svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6"><path d="m5 12 5 5 9-10" /></svg>Nothing needs attention in the last {period[1]}.</p>)}
            {tab === "alerts" && ((alerts?.alerts.length ?? 0) ? <table className="ov-t" aria-label="Open alerts">
              <thead><tr><th>Severity</th><th>Alert</th><th>Rule</th><th className="n">Raised</th></tr></thead>
              <tbody>{alerts!.alerts.slice(0, 8).map((a) => <tr key={a.id}><td><span className={"ov-pill is-" + a.severity}>{a.severity === "critical" ? "Critical" : "Warning"}</span></td>
                <td>{a.link ? <a href={a.link}>{a.message}</a> : a.message}</td><td>{a.rule_name}</td><td className="n">{time(a.raised)}</td></tr>)}</tbody>
            </table> : <p className="ov-ok">No open alerts.</p>)}
            {tab === "failed" && (reasons.length ? <table className="ov-t" aria-label="Why runs failed">
              <thead><tr><th>Error</th><th className="n">Runs</th><th>Mostly in</th><th className="n">Last</th></tr></thead>
              <tbody>{reasons.map((r) => <tr key={r.code}><td><a href={activity({ tab: "completed", status: "failed", q: r.code, period: period[2] })}>{r.code}</a></td>
                <td className="n">{r.runs}</td><td>{r.agent ? name(r.agent) : ""}</td><td className="n">{time(r.last)}</td></tr>)}</tbody>
            </table> : <p className="ov-ok">No run failed in the last {period[1]}.</p>)}
            {tab === "runners" && (attention.length ? <table className="ov-t" aria-label="Runners needing attention">
              <thead><tr><th>Runner</th><th>Why</th><th className="n">CPU</th><th className="n">Memory</th></tr></thead>
              <tbody>{attention.map(({ x, why }) => <tr key={x.id}><td><a href="#/devices">{x.nickname || x.name}</a></td><td className="ov-bad">{why}</td>
                <td className="n">{x.cpu_pct != null ? Math.round(x.cpu_pct) + "%" : ""}</td><td className="n">{x.mem_pct != null ? Math.round(x.mem_pct) + "%" : ""}</td></tr>)}</tbody>
            </table> : <p className="ov-ok">{live.length === 1 ? "Your runner is online, up to date and within its limits." : "All " + live.length + " runners are online, up to date and within their limits."}</p>)}
            {tab === "queues" && (lateQueues.length ? <table className="ov-t" aria-label="Work queues behind">
              <thead><tr><th>Queue</th><th className="n">Waiting</th><th className="n">Overdue</th><th>Processing</th></tr></thead>
              <tbody>{lateQueues.map((q) => <tr key={q.id}><td><a href="#/queueinsights">{q.name}</a></td><td className="n">{waiting(q)}</td>
                <td className={"n" + (q.overdue ? " ov-bad" : "")}>{q.overdue ?? 0}</td><td className={q.processing ? undefined : "ov-bad"}>{q.processing ? "On" : "Off"}</td></tr>)}</tbody>
            </table> : <p className="ov-ok">{queues?.length ? "Every queue is within its target time." : "No work queues yet."}</p>)}
          </div>

          {latest && <div className="ov-panel">
            <div className="ov-ph"><h2>Latest runs</h2><a href="#/activity?tab=completed" className="ov-more">Agent Activity ›</a></div>
            {latest.length ? <table className="ov-t" aria-label="Latest runs">
              <thead><tr><th>Status</th><th>Agent</th><th>Runner</th><th>Started</th><th className="n">Took</th><th>Error</th></tr></thead>
              <tbody>{latest.map((r) => <tr key={r.id}>
                <td><span className={"ov-pill is-" + r.status}>{{ success: "Completed", failed: "Failed", stopped: "Stopped", running: "Running", queued: "Waiting" }[r.status] ?? r.status}</span></td>
                <td><a href={"#/activity?run=" + encodeURIComponent(r.id)}>{name(r.bot_path)}</a></td><td>{r.device_name ?? r.device_id ?? ""}</td>
                <td>{time(r.started ?? r.created)}</td><td className="n">{took(r.duration_ms)}</td><td className="ov-bad">{errCode(r.error)}</td></tr>)}</tbody>
            </table> : <p className="ov-ok">No runs yet.</p>}
          </div>}
        </div>

        <div className="ov-side">
          {runners && <div className="ov-panel">
            <div className="ov-ph"><h2>Runners</h2><span className="ov-meta">{online} of {live.length} online</span><a href="#/devices" className="ov-more">Runner Machines ›</a></div>
            <ul className="ov-rows">
              {attention.slice(0, 4).map(({ x, why }) => <li key={x.id}><span className="ov-dot is-bad" />{x.nickname || x.name}<span className="ov-r ov-bad">{why}</span></li>)}
              {live.filter((x) => !trouble(x)).slice(0, attention.length ? 1 : 3).map((x) => <li key={x.id}><span className="ov-dot" />{x.nickname || x.name}<span className="ov-r ov-good">Online</span></li>)}
              {live.length - attention.length > (attention.length ? 1 : 3) && <li><span className="ov-dot" />{live.length - attention.length - (attention.length ? 1 : 3)} more<span className="ov-r ov-good">Online</span></li>}
              {!live.length && <li>No runners yet.</li>}
            </ul>
          </div>}
          {queues && queues.length > 0 && <div className="ov-panel">
            <div className="ov-ph"><h2>Work queues</h2><a href="#/queueinsights" className="ov-more">Work queue insights ›</a></div>
            <ul className="ov-rows">{queues.slice(0, 5).map((q) => <li key={q.id}>{q.name}<span className="ov-r">{waiting(q)} waiting{q.overdue ? <> · <span className="ov-bad">{q.overdue} overdue</span></> : ""}</span></li>)}</ul>
          </div>}
          {ai && ai.length > 0 && <div className="ov-panel">
            <div className="ov-ph"><h2>AI use this month</h2>{can("audit.view") && <a href="#/governance" className="ov-more">AI governance ›</a>}</div>
            <ul className="ov-rows">{ai.map((c) => { const pct = c.monthly_tokens ? Math.round(100 * (c.tokens_this_month ?? 0) / c.monthly_tokens) : null; return <li key={c.name}>{c.name}
              <span className={"ov-r" + (pct != null && pct >= 80 ? " ov-bad" : "")}>{pct == null ? short(c.tokens_this_month ?? 0) + " tokens" : <span className="cc-meter"><progress max={100} value={Math.min(pct, 100)} aria-label={"Budget used by " + c.name} /> {pct}%</span>}</span></li>; })}</ul>
          </div>}
          {setUp.length > 0 && <div className="ov-panel">
            <div className="ov-ph"><h2>Set up next</h2></div>
            <ul className="ov-rows" aria-label="Set up next">{setUp.map((s) => <li key={s.text}><Tile kind={s.kind} />{s.text}<a className="ov-r" href={s.href}>{s.link}</a></li>)}</ul>
          </div>}
        </div>
      </div>
    </section>
  );
}
