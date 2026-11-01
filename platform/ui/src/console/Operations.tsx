import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { AgentStats, CrApi, CrUser, Operations as OpsData, RunStats, VariableProfile } from "../api/cr";
import { Button, Dialog, HelpButton, Select, usePersistent, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";
import { howStarted } from "./Activity";

/**
 * Operational Insights > Operations, rebuilt from today's console with AA's Bot Insight operational dashboards, UiPath
 * Monitoring and Blue Prism Hub's utilisation heatmap in mind (checked 31 Oct 2026): how agents ran over a period, for
 * all agents or one runner, agent or way of starting; each figure compared with the period before; when runs start
 * (weekday by hour, in the viewer's time zone); why runs failed; the agents that run most, take longest and fail most;
 * each runner and way of starting; a CSV of each list; an agent's statistics with its output profile.
 * Review round 2 (31 Oct 2026): the three charts in one row and the six lists in one full-width tabbed panel, one line per
 * row (they were squeezed into narrow cards at 1366 px); an agent's row opens its statistics, with a menu per row.
 */
const PERIODS: [string, string][] = [["1", "Last 24 hours"], ["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]];
const ACTIVITY_PERIOD: Record<string, string> = { "1": "24h", "7": "7d" };
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const SOURCES = ["manual", "schedule", "trigger", "queue", "api", "rerun", "mcp"];
/** The lists, by the report each one's CSV comes from ("failed" has none). */
type ListTab = "failed" | "most_failed" | "most_run" | "longest" | "by_runner" | "by_source";
const HELP: HelpTopic = { title: "Operations", about: "How agents ran over a period: volumes, success and run time compared with the period before, "
  + "when runs start, which agents run most, take longest and fail most, and each runner's use. The page refreshes every minute.",
  items: [
    { name: "Period", text: "Pick a period from the last 24 hours to the last year; each figure is compared with the period of the same length before it." },
    { name: "All agents, All runners, Started any way", text: "Narrow every figure, chart and list to one agent, one runner or one way of starting runs." },
    { name: "Runs, Completed", text: "How many runs there were and the share that completed. Click a figure to see those runs in Agent Activity." },
    { name: "95% of runs finish within", text: "The time within which almost all runs finish, and the longest run; "
      + "a better guide than the average when a few runs are slow." },
    { name: "When runs start", text: "A heatmap by weekday and hour, in your time zone: darker means more runs start then; outlined hours had failed runs." },
    { name: "Why runs failed", text: "Each error with how many runs and agents it hit. Click an error to open those failed runs in Agent Activity." },
    { name: "Agents that fail most, Agents run most, Take longest", text: "The agents at the top of each list. Click an agent to see its statistics: "
      + "errors, runners and the values of its outputs." },
    { name: "Runners, How runs were started", text: "The same figures per runner and per way of starting. Click a way of starting to filter the page by it." },
    { name: "CSV", text: "Saves the list shown as a CSV file." },
    { name: "Run history CSV", text: "Saves every run of the period, with the filters chosen, as a CSV file." },
  ] };
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const folderOf = (path: string) => path.split("/").slice(0, -1).join("/");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const rate = (x?: number | null) => x == null ? "–" : x + "%";
function dur(ms?: number | null) {
  if (ms == null) return "–";
  const s = Math.round(ms / 1000);
  return s < 60 ? s + " s" : s < 3600 ? Math.floor(s / 60) + " min " + (s % 60) + " s" : s < 86400 ? Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min" : Math.round(s / 3600) + " h";
}
/** How a figure moved since the period before: "+12% on before", or points for a rate. */
function change(now: number | null | undefined, before: number | null | undefined, points = false) {
  if (now == null || before == null) return "";
  if (points) { const d = Math.round((now - before) * 10) / 10; return d === 0 ? "same as before" : (d > 0 ? "+" : "") + d + " points on before"; }
  if (before === 0) return now === 0 ? "" : "none before";
  const d = Math.round(100 * (now - before) / before);
  return d === 0 ? "same as before" : (d > 0 ? "+" : "") + d + "% on before";
}
function save(fileName: string, text: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
  a.download = fileName;
  document.body.appendChild(a); a.click(); a.remove();
}

export function Operations({ api, user, tabs }: { api: CrApi; user: CrUser; tabs?: ReactNode }) {
  const [days, setDays] = usePersistent("operations.days", "30");
  const [tab, setTab] = usePersistent<ListTab>("operations.list", "most_run");
  const [f, setF] = useState<Record<string, string>>({});
  const [o, setO] = useState<OpsData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [agents, setAgents] = useState<string[]>([]);
  const [runners, setRunners] = useState<{ id: string; name: string }[]>([]);
  const [stats, setStats] = useState<string | null>(null);
  const can = (p: string) => user.permissions.includes(p);
  const params = useCallback(() => ({ days, ...Object.fromEntries(Object.entries(f).filter(([, v]) => v)) }), [days, f]);

  const load = useCallback(() => api.operations({ ...params(), tz: Intl.DateTimeFormat().resolvedOptions().timeZone })
    .then((r) => { setO(r); setError(null); }, (e) => setError(message(e))), [api, params]);
  useEffect(() => { load(); const t = setInterval(load, 60000); return () => clearInterval(t); }, [load]);
  useEffect(() => {
    if (can("repo.view")) api.agents().then((b) => setAgents(b.map((x) => x.path).filter((p) => p.endsWith(".flow.json"))), () => {});
    if (can("devices.view")) api.runners().then(setRunners, () => {});
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps

  const csv = (report: string) => api.operationsCsv(report, params()).then((t) => save("operations-" + report + ".csv", t), (e) => setError(message(e)));
  const t = o?.totals, p = o?.previous;
  const label = PERIODS.find(([v]) => v === days)?.[1].toLowerCase() ?? "";
  const activity = (q: Record<string, string>) => "#/activity?" + new URLSearchParams({ tab: "completed", ...(ACTIVITY_PERIOD[days] ? { period: ACTIVITY_PERIOD[days] } : {}), ...q }).toString();
  /** An agent's row: its statistics, its runs, and the agent in Automation. */
  const agentMenu = (path: string) => [{ key: "stats", label: "Statistics", onSelect: () => setStats(path) },
    { key: "runs", label: "Its runs in Agent Activity", onSelect: () => { location.hash = activity({ bot: path }); } },
    ...(can("repo.view") ? [{ key: "open", label: "Open in Automation", onSelect: () => { location.hash = "#/automation?q=" + encodeURIComponent(name(path)); } }] : [])];
  const agentCells = (path: string) => <><td><span className="cc-aico" aria-hidden="true">A</span>{name(path)}</td><td>{folderOf(path)}</td></>;
  const lists: [ListTab, string, number, boolean][] = o ? [["failed", "Why runs failed", o.failure_reasons?.length ?? 0, true], ["most_failed", "Agents that fail most", o.most_failed.length, true],
    ["most_run", "Agents run most", o.most_run.length, false], ["longest", "Take longest", o.longest.length, false], ["by_runner", "Runners", o.by_runner.length, false],
    ["by_source", "How runs were started", o.by_source.length, false]] : [];
  const listName = lists.find(([k]) => k === tab)?.[1] ?? "";

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-op-title">
      <header className="cc-list-head">
        <h1 id="cc-op-title">Operations</h1>
        <HelpButton topic={HELP} />
        <span className="ov-fill" />
        <Button kind="secondary" onClick={() => csv("run_history")}>Run history CSV</Button>
      </header>
      {tabs}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-chip"><Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} /></span>
        {agents.length > 0 && <Select aria-label="Agent" value={f.bot ?? ""} onChange={(e) => setF({ ...f, bot: e.target.value })} options={[{ value: "", label: "All agents" }, ...agents.map((x) => ({ value: x, label: name(x) }))]} />}
        {runners.length > 0 && <Select aria-label="Runner" value={f.device ?? ""} onChange={(e) => setF({ ...f, device: e.target.value })} options={[{ value: "", label: "All runners" }, ...runners.map((d) => ({ value: d.id, label: d.name }))]} />}
        <Select aria-label="How started" value={f.source ?? ""} onChange={(e) => setF({ ...f, source: e.target.value })}
          options={[{ value: "", label: "Started any way" }, ...SOURCES.map((v) => ({ value: v, label: howStarted(v) }))]} />
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
      </div>
      <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {o?.period.capped && <p className="st-alert">Only the first {o.period.runs_counted.toLocaleString()} runs of the period are counted: choose a shorter period or one agent.</p>}
      {!o && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {o && t && <>
        <div className="cc-sums" role="group" aria-label="Figures">
          {/* AA's Bots dashboard order: all runs, then succeeded, in progress, failed and stopped */}
          <a className="cc-sum" href={activity({})}><strong>{t.runs.toLocaleString()}</strong><span>Runs · {change(t.runs, p?.runs) || label}</span></a>
          <a className="cc-sum" href={activity({ status: "success" })}><strong>{t.success.toLocaleString()}</strong>
            <span>Succeeded · {rate(t.success_rate)}{p && p.success_rate != null && t.success_rate != null ? " · " + change(t.success_rate, p.success_rate, true) : ""}</span></a>
          <a className="cc-sum" href="#/activity?tab=progress"><strong>{t.active}</strong><span>In progress or waiting</span></a>
          <a className="cc-sum" href={activity({ status: "failed" })}><strong className={t.failed ? "cc-err" : undefined}>{t.failed}</strong>
            <span>Failed{o.failure_reasons?.length ? " · " + o.failure_reasons.length + (o.failure_reasons.length === 1 ? " reason" : " reasons") : ""}</span></a>
          <a className="cc-sum" href={activity({ status: "stopped" })}><strong>{t.stopped}</strong><span>Stopped by someone</span></a>
          <div className="cc-sum is-static"><strong>{dur(t.avg_ms)}</strong><span>Average run · 95% within {dur(t.p95_ms)} · total {dur(t.total_ms)}</span></div>
        </div>
        <div className="cc-ops-charts">
          <div className="cc-card cc-card-flat"><h2>How long runs take</h2><Bars items={o.duration_distribution.map((x) => ({ label: x.bucket, value: x.runs }))} label="How long runs take" /></div>
          <div className="cc-card cc-card-flat"><h2>Runs by week</h2>
            {o.by_day.length ? <WeekBars days={o.by_day} /> : <p className="st-muted">No runs in the period.</p>}</div>
          {o.by_hour && <div className="cc-card cc-card-flat"><h2>When runs start</h2><Heatmap h={o.by_hour} /></div>}
        </div>
        <div className="cc-list-panel cc-ops-lists">
          <div className="ov-tabs" role="tablist" aria-label="Lists">
            {lists.map(([k, l, n, bad]) => <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>
              {l}<span className={"ov-cnt" + (bad && n ? " is-bad" : "")}>{n}</span></button>)}
            <span className="ov-fill" />
            {tab !== "failed" && <Button kind="plain" onClick={() => csv(tab)} aria-label={"CSV of " + listName.toLowerCase()}>CSV</Button>}
          </div>
          <div className="cc-list-scroll">
            {tab === "failed" && <table className="cc-list-table" aria-label="Why runs failed">
              <thead><tr><th scope="col">Error</th><th scope="col" className="cc-num">Runs</th><th scope="col" className="cc-num">Agents</th><th scope="col">Mostly in</th><th scope="col">Last</th>
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>{(o.failure_reasons ?? []).map((r) => <tr key={r.code} title="Open these failed runs in Agent Activity" onClick={rowClick(() => { location.hash = activity({ status: "failed", q: r.code }); })}>
                <td className="ui-mono"><a href={activity({ status: "failed", q: r.code })}>{r.code}</a></td><td className="cc-num">{r.runs}</td><td className="cc-num">{r.agents}</td>
                <td>{r.agent ? <><span className="cc-aico" aria-hidden="true">A</span>{name(r.agent)}</> : ""}</td><td>{when(r.last)}</td>
                <td className="cc-list-menu"><RowMenu label={"Actions for " + r.code} items={[{ key: "runs", label: "Its failed runs in Agent Activity", onSelect: () => { location.hash = activity({ status: "failed", q: r.code }); } },
                  ...(r.agent ? [{ key: "stats", label: "Statistics of " + name(r.agent), onSelect: () => setStats(r.agent!) }] : [])]} /></td></tr>)}
                {!o.failure_reasons?.length && <tr className="is-static"><td colSpan={6} className="cc-none">No run failed in the period.</td></tr>}</tbody>
            </table>}
            {tab === "most_failed" && <StatList rows={o.most_failed} label="Agents that fail most" head={["Agent", "Folder"]} cells={(r) => agentCells(r.bot_path)}
              open={(r) => setStats(r.bot_path)} menu={(r) => agentMenu(r.bot_path)} name={(r) => name(r.bot_path)} empty="No failures in the period."
              extra={[["Failed", (r) => String(r.failed), true], ["Of finished", (r) => rate(r.failure_rate), true], ["Errors", (r) => r.top_errors.join(", "), false]]} />}
            {tab === "most_run" && <StatList rows={o.most_run} label="Agents run most" head={["Agent", "Folder"]} cells={(r) => agentCells(r.bot_path)}
              open={(r) => setStats(r.bot_path)} menu={(r) => agentMenu(r.bot_path)} name={(r) => name(r.bot_path)} />}
            {tab === "longest" && <StatList rows={o.longest} label="Agents that take longest" head={["Agent", "Folder"]} cells={(r) => agentCells(r.bot_path)}
              open={(r) => setStats(r.bot_path)} menu={(r) => agentMenu(r.bot_path)} name={(r) => name(r.bot_path)} extra={[["Longest", (r) => dur(r.max_ms), true]]} />}
            {tab === "by_runner" && <StatList rows={o.by_runner} label="Runners" head={["Runner"]} cells={(r) => <td>{r.runner ?? ""}</td>} name={(r) => r.runner ?? ""} />}
            {tab === "by_source" && <StatList rows={o.by_source} label="How runs were started" head={["Started by"]} cells={(r) => <td>{howStarted(r.source)}</td>} name={(r) => howStarted(r.source)}
              open={(r) => setF({ ...f, source: r.source })} openTitle="Show only runs started this way" />}
          </div>
        </div>
      </>}
      {stats && <AgentStatistics api={api} path={stats} days={days} label={label} onClose={() => setStats(null)} />}
    </section>
  );
}

/** One of the lists at full width, one line per row: runs, completed, average, 95% within and total, then its own columns. */
function StatList<R extends RunStats>({ rows, label, head, cells, name: nameOf, open, openTitle, menu, extra = [], empty = "No runs in the period." }: { rows: R[]; label: string;
  head: string[]; cells: (r: R) => ReactNode; name: (r: R) => string; open?: (r: R) => void; openTitle?: string; menu?: (r: R) => { key: string; label: string; onSelect: () => void }[];
  extra?: [string, (r: R) => string, boolean][]; empty?: string }) {
  const span = head.length + 5 + extra.length + (menu ? 1 : 0);
  return <table className="cc-list-table" aria-label={label}>
    <thead><tr>{head.map((h) => <th key={h} scope="col">{h}</th>)}{["Runs", "Completed", "Average", "95% within", "Total"].map((h) => <th key={h} scope="col" className="cc-num">{h}</th>)}
      {extra.map(([h, , num]) => <th key={h} scope="col" className={num ? "cc-num" : undefined}>{h}</th>)}
      {menu && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
    <tbody>{rows.map((r, i) => <tr key={i} className={open ? undefined : "is-static"} title={open ? openTitle : undefined} onClick={open ? rowClick(() => open(r)) : undefined}>
      {cells(r)}<td className="cc-num">{r.runs}</td><td className="cc-num">{rate(r.success_rate)}</td><td className="cc-num">{dur(r.avg_ms)}</td><td className="cc-num">{dur(r.p95_ms)}</td><td className="cc-num">{dur(r.total_ms)}</td>
      {extra.map(([h, fn, num]) => <td key={h} className={num ? "cc-num" : "cc-clip"} title={num ? undefined : fn(r)}>{fn(r)}</td>)}
      {menu && <td className="cc-list-menu"><RowMenu label={"Actions for " + nameOf(r)} items={menu(r)} /></td>}</tr>)}
      {!rows.length && <tr className="is-static"><td colSpan={span} className="cc-none">{empty}</td></tr>}</tbody>
  </table>;
}

function StatTable<R extends RunStats>({ rows, first, cell, label, extra = [] }: { rows: R[]; first: string; cell: (r: R) => ReactNode; label: string; extra?: [string, (r: R) => string][] }) {
  if (!rows.length) return <p className="st-muted">No runs in the period.</p>;
  return <div className="cc-scroll"><table className="st-table" aria-label={label}>
    <thead><tr><th scope="col">{first}</th><th scope="col">Runs</th><th scope="col">Completed</th><th scope="col">Average</th><th scope="col">95% within</th><th scope="col">Total</th>
      {extra.map(([h]) => <th key={h} scope="col">{h}</th>)}</tr></thead>
    <tbody>{rows.map((r, i) => <tr key={i}><td>{cell(r)}</td><td>{r.runs}</td><td>{rate(r.success_rate)}</td><td>{dur(r.avg_ms)}</td><td>{dur(r.p95_ms)}</td><td>{dur(r.total_ms)}</td>
      {extra.map(([h, f]) => <td key={h}>{f(r)}</td>)}</tr>)}</tbody>
  </table></div>;
}

/** Runs per week (from Monday, the latest 8), each bar split by how its runs ended, as AA's weekly status chart. */
function WeekBars({ days }: { days: OpsData["by_day"] }) {
  const pad = (n: number) => String(n).padStart(2, "0");
  const weeks = new Map<string, { label: string; success: number; failed: number; stopped: number; runs: number }>();
  for (const d of days) {
    const t = new Date(d.day + "T12:00:00");
    t.setDate(t.getDate() - ((t.getDay() + 6) % 7));
    const k = t.getFullYear() + "-" + pad(t.getMonth() + 1) + "-" + pad(t.getDate());
    const w = weeks.get(k) ?? { label: "From " + t.toLocaleDateString(undefined, { day: "numeric", month: "short" }), success: 0, failed: 0, stopped: 0, runs: 0 };
    w.success += d.success; w.failed += d.failed; w.stopped += d.stopped; w.runs += d.runs;
    weeks.set(k, w);
  }
  const rows = [...weeks.entries()].sort(([a], [b]) => a.localeCompare(b)).slice(-8).map(([, w]) => w);
  const max = Math.max(1, ...rows.map((w) => w.runs));
  const seg = (n: number, cls: string, what: string) => n ? <span className={"cc-seg " + cls} style={{ width: (100 * n / max) + "%" }} title={n + " " + what} /> : null;
  return <>
    <ul className="cc-hbars cc-week" aria-label="Runs by week">{rows.map((w) => <li key={w.label} aria-label={w.label + ": " + w.runs + " runs, " + w.success + " succeeded, " + w.failed + " failed, " + w.stopped + " stopped"}>
      <span className="cc-hbar-label">{w.label}</span>
      <span className="cc-hbar-track">{seg(w.success, "is-ok", "succeeded")}{seg(w.failed, "is-bad", "failed")}{seg(w.stopped, "is-off", "stopped")}</span>
      <span className="cc-hbar-value">{w.runs}</span></li>)}</ul>
    <p className="cc-legend" aria-hidden="true"><span><i className="cc-seg is-ok" />Succeeded</span><span><i className="cc-seg is-bad" />Failed</span><span><i className="cc-seg is-off" />Stopped</span></p>
  </>;
}

/** Horizontal bars, each with its count (and failed runs, when given). */
function Bars({ items, label }: { items: { label: string; value: number; bad?: number }[]; label: string }) {
  const max = Math.max(1, ...items.map((x) => x.value));
  return <ul className="cc-hbars" aria-label={label}>{items.map((x) => <li key={x.label}>
    <span className="cc-hbar-label">{x.label}</span>
    <span className="cc-hbar-track"><span className="cc-hbar-fill" style={{ width: (100 * x.value / max) + "%" }} /></span>
    <span className="cc-hbar-value">{x.value}{x.bad ? <span className="cc-err"> ({x.bad} failed)</span> : null}</span>
  </li>)}</ul>;
}

/** Weekday by hour, darker where more runs start; a cell with failed runs is outlined (Blue Prism's activity heatmap). */
function Heatmap({ h }: { h: NonNullable<OpsData["by_hour"]> }) {
  const max = Math.max(1, ...h.runs.flat());
  const level = (n: number) => n === 0 ? 0 : Math.min(4, Math.ceil(4 * n / max));
  const total = h.runs.flat().reduce((a, b) => a + b, 0);
  if (!total) return <p className="st-muted">No runs in the period.</p>;
  return <div className="cc-scroll">
    <table className="cc-heat" aria-label={"When runs start, by weekday and hour (" + h.time_zone + ")"}>
      <thead><tr><th scope="col"><span className="st-sr">Day</span></th>{Array.from({ length: 24 }, (_, i) => <th key={i} scope="col">{i % 3 === 0 ? String(i).padStart(2, "0") : ""}</th>)}</tr></thead>
      <tbody>{h.runs.map((row, d) => <tr key={d}><th scope="row">{DAYS[d]}</th>{row.map((n, hr) => {
        const bad = h.failed[d][hr];
        return <td key={hr} className={"cc-heat-l" + level(n) + (bad ? " has-failed" : "")} title={DAYS[d] + " " + String(hr).padStart(2, "0") + ":00 · " + n + " run" + (n === 1 ? "" : "s") + (bad ? ", " + bad + " failed" : "")}>
          <span className="st-sr">{n}</span></td>;
      })}</tr>)}</tbody>
    </table>
    <p className="cc-card-foot st-muted">Darker: more runs start then. Outlined: some failed. Hours in {h.time_zone}.</p>
  </div>;
}

/** An agent's statistics for the period: totals, errors, runners and its output profile (AA's Bot Insight Profile dashboard). */
function AgentStatistics({ api, path, days, label, onClose }: { api: CrApi; path: string; days: string; label: string; onClose: () => void }) {
  const [a, setA] = useState<AgentStats | null>(null);
  const [vp, setVp] = useState<VariableProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api.agentStats(path, days).then(setA, (e) => setError(message(e)));
    api.agentVariables(path, days).then(setVp, () => setVp(null));
  }, [api, path, days]);
  const t = a?.totals;
  return (
    <Dialog open title={"Statistics of " + name(path)} width={760} onClose={onClose} footer={<a className="ui-btn ui-btn-plain" href={"#/activity?" + new URLSearchParams({ tab: "completed", bot: path }).toString()}>Its runs in Agent Activity</a>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!a && !error && <p className="st-muted" role="status">Loading…</p>}
      {a && t && <>
        <p className="st-muted">{path} · {label}</p>
        <dl className="cc-facts cc-facts-wide">
          <dt>Runs</dt><dd>{t.runs}</dd><dt>Completed</dt><dd>{rate(t.success_rate)}</dd>
          <dt>Average</dt><dd>{dur(t.avg_ms)}</dd><dt>95% within</dt><dd>{dur(t.p95_ms)}</dd>
          <dt>Last run</dt><dd>{a.last_run ? <a href={"#/activity?run=" + encodeURIComponent(a.last_run.id)}>{when(a.last_run.created)}</a> : "None"}{a.last_run ? " (" + a.last_run.status + ")" : ""}</dd>
          <dt>Last failure</dt><dd>{a.last_failure ? <a href={"#/activity?run=" + encodeURIComponent(a.last_failure.id)}>{when(a.last_failure.created)}</a> : "None"}{a.last_failure ? " (" + a.last_failure.code + ")" : ""}</dd>
        </dl>
        {a.errors.length > 0 && <><h3 className="cc-log-title">Errors</h3><table className="st-table" aria-label="Errors">
          <thead><tr><th scope="col">Error</th><th scope="col">Runs</th></tr></thead>
          <tbody>{a.errors.map((e) => <tr key={e.code}><td className="ui-mono">{e.code}</td><td>{e.runs}</td></tr>)}</tbody></table></>}
        <h3 className="cc-log-title">Runners</h3>
        <StatTable rows={a.by_runner} first="Runner" cell={(r) => r.runner ?? ""} label="Its runners" />
        {vp && vp.variables.length > 0 && <><h3 className="cc-log-title">Outputs</h3><table className="st-table" aria-label="Outputs">
          <thead><tr><th scope="col">Output</th><th scope="col">Had a value</th><th scope="col">Range</th><th scope="col">Most common</th></tr></thead>
          <tbody>{vp.variables.map((v) => <tr key={v.variable}><td className="ui-mono">{v.variable}</td><td>{v.runs_with_value} of {v.runs}</td>
            <td>{v.min != null ? v.min + " – " + v.max + " (average " + v.avg + ")" : ""}</td><td>{(v.common ?? []).map((c) => c.value + " (" + c.runs + ")").join(", ")}</td></tr>)}</tbody></table></>}
      </>}
    </Dialog>
  );
}
