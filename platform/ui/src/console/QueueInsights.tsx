import { useCallback, useEffect, useState } from "react";
import type { CrApi, CrUser, QueueInsight, RunnerPool } from "../api/cr";
import { Button, Select, usePersistent } from "../components";

/**
 * Operational Insights > Work queue insights, as UiPath's queue monitoring and Queues SLA, Blue Prism's work queue
 * composition and AA's Workload dashboard (checked 31 Oct 2026): for each queue the viewer may see, what waits now,
 * what is overdue or at risk of missing its target time, how many items finished and how fast, the failures by error,
 * and the runners needed to clear what waits within the target time, compared with the runners of its pool.
 */
const PERIODS: [string, string][] = [["1", "Last 24 hours"], ["7", "Last 7 days"], ["30", "Last 30 days"]];
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const rate = (x?: number | null) => x == null ? "–" : x + "%";
function dur(ms?: number | null) {
  if (ms == null) return "–";
  const s = Math.round(ms / 1000);
  return s < 60 ? s + " s" : s < 3600 ? Math.floor(s / 60) + " min " + (s % 60) + " s" : s < 172_800 ? Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min" : Math.round(s / 86400) + " days";
}
const age = (t?: string | null) => t ? dur(Date.now() - new Date(t).getTime()) : "–";

/** What to do about a queue, in plain words, or null when it is fine. */
function advice(q: QueueInsight, poolSize: number | null): string | null {
  if (!q.processing && q.waiting > 0) return q.name + ": processing is off and " + q.waiting + " item" + (q.waiting === 1 ? " waits" : "s wait") + ". Turn processing on in Work queues.";
  if (q.runners_needed != null && poolSize != null && q.runners_needed > poolSize)
    return q.name + ": clearing what waits within " + q.sla_minutes + " minutes needs " + q.runners_needed + " runners; its pool has " + poolSize + ". Add runners to the pool.";
  if ((q.overdue ?? 0) > 0) return q.name + ": " + q.overdue + " item" + (q.overdue === 1 ? " is" : "s are") + " overdue. Raise their priority or add runners.";
  return null;
}

export function QueueInsights({ api, user }: { api: CrApi; user: CrUser }) {
  const [days, setDays] = usePersistent("queueinsights.days", "7");
  const [list, setList] = useState<QueueInsight[] | null>(null);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.queueInsights({ days, tz: Intl.DateTimeFormat().resolvedOptions().timeZone }).then((r) => { setList(r); setError(null); }, (e) => setError(message(e)));
    if (user.permissions.includes("devices.view")) api.pools().then(setPools, () => {});
  }, [api, days, user]);
  useEffect(() => { load(); const t = setInterval(load, 60000); return () => clearInterval(t); }, [load]);

  const label = PERIODS.find(([v]) => v === days)?.[1].toLowerCase() ?? "";
  const qs = list ?? [];
  const sum = (f: (q: QueueInsight) => number | null | undefined) => qs.reduce((n, q) => n + (f(q) ?? 0), 0);
  const finished = sum((q) => q.finished), success = sum((q) => q.success);
  const poolSize = (q: QueueInsight) => q.pool_id ? pools.find((p) => p.id === q.pool_id)?.devices.length ?? null : null;
  const tips = qs.map((q) => advice(q, poolSize(q))).filter((t): t is string => !!t);
  const sel = qs.find((q) => q.id === open);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-qi-title">
      <header className="st-page-head">
        <div><h1 id="cc-qi-title">Work queue insights</h1><p>What waits in each work queue, what is late or at risk of missing its target time, how fast items finish, and the runners needed to keep up.</p></div>
        <span className="cc-inline">
          <Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} />
          <Button kind="plain" onClick={load}>Refresh</Button>
        </span>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!list && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {list && !qs.length && <p className="st-muted cc-pad">No work queues you may see yet.</p>}
      {qs.length > 0 && <>
        <div className="cc-sums" role="group" aria-label="Figures">
          <a className="cc-sum" href="#/queues"><strong>{sum((q) => q.waiting)}</strong><span>Items waiting</span></a>
          <a className="cc-sum" href="#/queues"><strong className={sum((q) => q.overdue) ? "cc-err" : undefined}>{sum((q) => q.overdue)}</strong><span>Overdue</span></a>
          <a className="cc-sum" href="#/queues"><strong className={sum((q) => q.at_risk) ? "cc-sev is-warning" : undefined}>{sum((q) => q.at_risk)}</strong><span>At risk of missing the target time</span></a>
          <div className="cc-sum is-static"><strong>{finished}</strong><span>Finished · {finished ? Math.round(100 * success / finished) + "% completed · " : ""}{label}</span></div>
        </div>
        {tips.length > 0 && <ul className="cc-advice cc-pad-x cc-advice-list" aria-label="Advice">{tips.map((t) => <li key={t}>{t}</li>)}</ul>}
        <div className="cc-card cc-card-flat cc-pad-x cc-gap">
          <h2>Queues</h2>
          <div className="cc-scroll"><table className="st-table" aria-label="Queues">
            <thead><tr>{["Queue", "Waiting", "Oldest waiting", "Overdue", "At risk", "Target", "Finished", "Completed", "In time", "An hour", "Handling", "Wait to start", "Runners needed", "Processing"]
              .map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
            <tbody>{qs.map((q) => <tr key={q.id} className={q.id === open ? "is-selected" : undefined}>
              <td><button type="button" className="cc-link" aria-expanded={q.id === open} onClick={() => setOpen(q.id === open ? null : q.id)}>{q.name}</button></td>
              <td>{q.waiting}</td><td>{age(q.oldest_waiting)}</td>
              <td className={q.overdue ? "cc-err" : undefined}>{q.sla_minutes ? q.overdue ?? 0 : "–"}</td>
              <td className={q.at_risk ? "cc-sev is-warning" : undefined}>{q.sla_minutes ? q.at_risk ?? 0 : "–"}</td>
              <td>{q.sla_minutes ? q.sla_minutes + " min" : "None"}</td>
              <td>{q.finished}</td><td>{rate(q.success_rate)}</td><td>{rate(q.in_time_rate)}</td><td>{q.per_hour}</td>
              <td>{dur(q.avg_handling_ms)}</td><td>{dur(q.avg_wait_ms)}</td>
              <td>{q.runners_needed ?? "–"}{q.runners_needed != null && poolSize(q) != null ? " of " + poolSize(q) : ""}</td>
              <td className={q.processing ? undefined : "cc-err"}>{q.processing ? "On" : "Off"}</td>
            </tr>)}</tbody>
          </table></div>
          <p className="cc-card-foot st-muted">At risk: waiting more than three quarters of the target time. Runners needed: waiting items × average handling time ÷ target time (and the runners of its pool).</p>
        </div>
        {sel && <div className="cc-two cc-pad">
          <div className="cc-card cc-card-flat"><h2>{sel.name}: added and finished per day</h2>
            {sel.by_day.length ? <ul className="cc-hbars cc-hbars-two" aria-label={"Added and finished per day in " + sel.name}>{sel.by_day.slice(-31).map((x) => {
              const max = Math.max(1, ...sel.by_day.map((y) => Math.max(y.added, y.finished)));
              return <li key={x.day}><span className="cc-hbar-label">{x.day.slice(5)}</span>
                <span className="cc-hbar-pair"><span className="cc-hbar-track"><span className="cc-hbar-fill" style={{ width: (100 * x.added / max) + "%" }} /></span>
                  <span className="cc-hbar-track"><span className="cc-hbar-fill is-done" style={{ width: (100 * x.finished / max) + "%" }} /></span></span>
                <span className="cc-hbar-value">{x.added} added · {x.finished} finished</span></li>;
            })}</ul> : <p className="st-muted">Nothing added or finished in the period.</p>}
          </div>
          <div className="cc-card cc-card-flat"><h2>{sel.name}: why items failed</h2>
            {sel.errors.length ? <table className="st-table" aria-label={"Why items failed in " + sel.name}>
              <thead><tr><th scope="col">Error</th><th scope="col">Items</th></tr></thead>
              <tbody>{sel.errors.map((e) => <tr key={e.code}><td className="ui-mono">{e.code}</td><td>{e.items}</td></tr>)}</tbody>
            </table> : <p className="st-muted">No item failed in the period.</p>}
            <dl className="cc-facts cc-gap">
              <dt>Now</dt><dd>{Object.entries(sel.counts).filter(([, n]) => n).map(([k, n]) => n + " " + k.replace("_", " ")).join(", ") || "Empty"}</dd>
              <dt>Added</dt><dd>{sel.added} · {label}</dd>
            </dl>
          </div>
        </div>}
      </>}
    </section>
  );
}
