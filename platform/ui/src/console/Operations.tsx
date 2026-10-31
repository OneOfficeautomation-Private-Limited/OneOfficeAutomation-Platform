import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { AgentStats, CrApi, CrUser, Operations as OpsData, RunStats, VariableProfile } from "../api/cr";
import { Button, Dialog, Select, usePersistent } from "../components";
import { howStarted } from "./Activity";

/**
 * Operational Insights > Operations, rebuilt from today's console with AA's Bot Insight operational dashboards, UiPath
 * Monitoring and Blue Prism Hub's utilisation heatmap in mind (checked 31 Oct 2026): how agents ran over a period, for
 * all agents or one runner, agent or way of starting; each figure compared with the period before; when runs start
 * (weekday by hour, in the viewer's time zone); why runs failed; the agents that run most, take longest and fail most;
 * each runner and way of starting; a CSV of each list; an agent's statistics with its output profile.
 */
const PERIODS: [string, string][] = [["1", "Last 24 hours"], ["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]];
const ACTIVITY_PERIOD: Record<string, string> = { "1": "24h", "7": "7d" };
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
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

export function Operations({ api, user }: { api: CrApi; user: CrUser }) {
  const [days, setDays] = usePersistent("operations.days", "30");
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
  const agentCell = (path: string) => <button type="button" className="cc-link" onClick={() => setStats(path)}>{name(path)}</button>;

  return (
    <section className="st-page cc-home" aria-labelledby="cc-op-title">
      <header className="st-page-head">
        <div><h1 id="cc-op-title">Operations</h1><p>How agents ran over a period: volumes, success and run time compared with the period before, when runs start, which agents run most, take longest and fail most, and each runner's use.</p></div>
        <Button kind="secondary" onClick={() => csv("run_history")}>Run history CSV</Button>
      </header>
      <div className="cc-filters" role="group" aria-label="Filters">
        <Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} />
        {agents.length > 0 && <Select aria-label="Agent" value={f.bot ?? ""} onChange={(e) => setF({ ...f, bot: e.target.value })} options={[{ value: "", label: "All agents" }, ...agents.map((x) => ({ value: x, label: name(x) }))]} />}
        {runners.length > 0 && <Select aria-label="Runner" value={f.device ?? ""} onChange={(e) => setF({ ...f, device: e.target.value })} options={[{ value: "", label: "All runners" }, ...runners.map((d) => ({ value: d.id, label: d.name }))]} />}
        <Select aria-label="How started" value={f.source ?? ""} onChange={(e) => setF({ ...f, source: e.target.value })}
          options={[{ value: "", label: "Started any way" }, ...["manual", "schedule", "trigger", "queue", "api", "rerun", "mcp"].map((v) => ({ value: v, label: howStarted(v) }))]} />
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {o?.period.capped && <p className="st-alert">Only the first {o.period.runs_counted.toLocaleString()} runs of the period are counted: choose a shorter period or one agent.</p>}
      {!o && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {o && t && <>
        <div className="cc-sums" role="group" aria-label="Figures">
          <a className="cc-sum" href={activity({})}><strong>{t.runs.toLocaleString()}</strong><span>Runs · {[t.active ? t.active + " still active" : "", change(t.runs, p?.runs)].filter(Boolean).join(" · ") || label}</span></a>
          <a className="cc-sum" href={activity({ status: "failed" })}><strong>{rate(t.success_rate)}</strong>
            <span>Completed: {t.success} completed, {t.failed} failed, {t.stopped} stopped{p && p.success_rate != null && t.success_rate != null ? " · " + change(t.success_rate, p.success_rate, true) : ""}</span></a>
          <div className="cc-sum is-static"><strong>{dur(t.total_ms)}</strong><span>Run time · average {dur(t.avg_ms)}{p?.avg_ms != null && t.avg_ms != null ? " · average " + change(t.avg_ms, p.avg_ms) : ""}</span></div>
          <div className="cc-sum is-static"><strong>{dur(t.p95_ms)}</strong><span>95% of runs finish within · longest {dur(t.max_ms)}</span></div>
        </div>
        <div className="cc-two cc-pad">
          <div className="cc-card cc-card-flat"><h2>How long runs take</h2><Bars items={o.duration_distribution.map((x) => ({ label: x.bucket, value: x.runs }))} label="How long runs take" /></div>
          <div className="cc-card cc-card-flat"><h2>Runs per day</h2>
            {o.by_day.length ? <Bars items={o.by_day.slice(-31).map((x) => ({ label: x.day.slice(5), value: x.runs, bad: x.failed }))} label="Runs per day" /> : <p className="st-muted">No runs in the period.</p>}</div>
        </div>
        {o.by_hour && <div className="cc-card cc-card-flat cc-pad-x"><h2>When runs start</h2><Heatmap h={o.by_hour} /></div>}
        <div className="cc-two cc-pad">
          <Card title="Why runs failed" onCsv={null}>
            {o.failure_reasons?.length ? <table className="st-table" aria-label="Why runs failed">
              <thead><tr><th scope="col">Error</th><th scope="col">Runs</th><th scope="col">Agents</th><th scope="col">Mostly in</th><th scope="col">Last</th></tr></thead>
              <tbody>{o.failure_reasons.map((r) => <tr key={r.code}><td><a href={activity({ status: "failed", q: r.code })}>{r.code}</a></td><td>{r.runs}</td><td>{r.agents}</td>
                <td>{r.agent ? agentCell(r.agent) : ""}</td><td>{when(r.last)}</td></tr>)}</tbody>
            </table> : <p className="st-muted">No run failed in the period.</p>}
          </Card>
          <Card title="Agents that fail most" onCsv={() => csv("most_failed")}>
            {o.most_failed.length ? <StatTable rows={o.most_failed} first="Agent" cell={(r) => agentCell(r.bot_path)} label="Agents that fail most"
              extra={[["Failed", (r) => String(r.failed)], ["Of finished", (r) => rate(r.failure_rate)], ["Errors", (r) => r.top_errors.join(", ")]]} /> : <p className="st-muted">No failures in the period.</p>}
          </Card>
          <Card title="Agents run most" onCsv={() => csv("most_run")}>
            <StatTable rows={o.most_run} first="Agent" cell={(r) => agentCell(r.bot_path)} label="Agents run most" />
          </Card>
          <Card title="Agents that take longest (average)" onCsv={() => csv("longest")}>
            <StatTable rows={o.longest} first="Agent" cell={(r) => agentCell(r.bot_path)} label="Agents that take longest" extra={[["Longest", (r) => dur(r.max_ms)]]} />
          </Card>
          <Card title="Runners" onCsv={() => csv("by_runner")}>
            <StatTable rows={o.by_runner} first="Runner" cell={(r) => r.runner ?? ""} label="Runners" />
          </Card>
          <Card title="How runs were started" onCsv={() => csv("by_source")}>
            <StatTable rows={o.by_source} first="Started by" cell={(r) => howStarted(r.source)} label="How runs were started" />
          </Card>
        </div>
      </>}
      {stats && <AgentStatistics api={api} path={stats} days={days} label={label} onClose={() => setStats(null)} />}
    </section>
  );
}

function Card({ title, onCsv, children }: { title: string; onCsv: (() => void) | null; children: ReactNode }) {
  return <div className="cc-card cc-card-flat">
    <div className="cc-card-head"><h2>{title}</h2>{onCsv && <Button kind="plain" onClick={onCsv} aria-label={"CSV of " + title.toLowerCase()}>CSV</Button>}</div>
    {children}
  </div>;
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
