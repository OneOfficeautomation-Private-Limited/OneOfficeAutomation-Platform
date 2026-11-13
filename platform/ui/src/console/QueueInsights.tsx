import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { CrApi, CrUser, QueueInsight, RunnerPool } from "../api/cr";
import { HelpButton, Select, usePersistent, type HelpTopic, reportMinor, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";

/**
 * Operational Insights > Work queue insights, as UiPath's queue monitoring and Queues SLA, Blue Prism's work queue
 * composition and AA's Workload dashboard (checked 31 Oct 2026): for each queue the viewer may see, what waits now,
 * what is overdue or at risk of missing its target time, how many items finished and how fast, the failures by error,
 * and the runners needed to clear what waits within the target time, compared with the runners of its pool.
 * Review round 2 (1 Nov 2026): the period as a filter with the refresh icon; one line per queue that fits at 1366 px
 * (processing as a pill first, the target time on hover, short headings explained on hover); a row shows the queue's
 * details below, and a menu per queue opens it in Work queues.
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
/** The number columns, short, with what each means on hover. */
const HEADS: [string, string][] = [["Waiting", "Items waiting now"], ["Oldest", "How long the oldest item has waited"], ["Overdue", "Waiting longer than the target time"],
  ["At risk", "Waiting more than three quarters of the target time"], ["Finished", "Items finished in the period"], ["Completed", "Share of finished items that completed"],
  ["In time", "Share of finished items done within the target time"], ["An hour", "Items finished per hour of processing"], ["Handling", "Average time to handle an item"],
  ["Wait", "Average wait before an item starts"], ["Runners", "Runners needed to clear what waits within the target time, of the runners in its pool"]];

/** What to do about a queue, in plain words, or null when it is fine. */
function advice(q: QueueInsight, poolSize: number | null): string | null {
  if (!q.processing && q.waiting > 0) return q.name + ": processing is off and " + q.waiting + " item" + (q.waiting === 1 ? " waits" : "s wait") + ". Turn processing on in Work queues.";
  if (q.runners_needed != null && poolSize != null && q.runners_needed > poolSize)
    return q.name + ": clearing what waits within " + q.sla_minutes + " minutes needs " + q.runners_needed + " runners; its pool has " + poolSize + ". Add runners to the pool.";
  if ((q.overdue ?? 0) > 0) return q.name + ": " + q.overdue + " item" + (q.overdue === 1 ? " is" : "s are") + " overdue. Raise their priority or add runners.";
  return null;
}

const HELP: HelpTopic = { title: "Work queue insights",
  about: "How each work queue you may see is keeping up: what waits now, what is late or close to its target time, how fast items finish and how many "
    + "runners it needs. The page refreshes every minute.",
  items: [
    { name: "Period", text: "The time the finished, completed and in-time figures cover: the last 24 hours, 7 days or 30 days. What waits now is always "
      + "shown as it is now." },
    { name: "State", text: "Whether processing is on for the queue. When it is off, waiting items are not handed to runners; turn it on in Work queues." },
    { name: "Overdue and At risk", text: "Items waiting longer than the queue's target time, and items waiting more than three quarters of it. They show a "
      + "dash when the queue has no target time." },
    { name: "Completed and In time", text: "Of the items finished in the period, the share that completed, and the share done within the target time." },
    { name: "An hour, Handling and Wait", text: "Items finished per hour of processing, the average time to handle one item, and the average wait before an "
      + "item starts." },
    { name: "Runners", text: "The runners needed to clear what waits within the target time, next to the runners in the queue's pool. If it needs more than "
      + "the pool has, add runners to the pool." },
    { name: "Advice", text: "Short notes under the figures for queues that need attention, for example processing switched off while items wait, or too few "
      + "runners." },
    { name: "Show its details", text: "Click a row, or use this in its ⋯ menu, to see what was added and finished per day and why items failed in the "
      + "period. Click again to hide it." },
    { name: "Open the queue", text: "Opens the queue in Work queues, where you can see its items and change it." },
  ] };

export function QueueInsights({ api, user , title, tabs }: { api: CrApi; user: CrUser; title?: string; tabs?: ReactNode }) {
  const [days, setDays] = usePersistent("queueinsights.days", "7");
  const [list, setList] = useState<QueueInsight[] | null>(null);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.queueInsights({ days, tz: Intl.DateTimeFormat().resolvedOptions().timeZone }).then((r) => { setList(r); setError(null); }, (e) => setError(message(e)));
    if (user.permissions.includes("devices.view")) api.pools().then(setPools, (e) => reportMinor("the runner pools", e));
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
    <section className="st-page cc-home cc-list" aria-labelledby="cc-qi-title">
      <header className="cc-list-head">
        <h1 id="cc-qi-title">{title ?? "Work queue insights"}</h1>
        <HelpButton topic={HELP} />
      </header>
      {tabs}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-chip"><Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {error && <PageError text={error} />}
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
        <div className="cc-list-panel cc-cap-runners">
          <div className="cc-list-scroll"><table className="cc-list-table" aria-label="Queues">
            <thead><tr><th scope="col" title="Processing on or off">State</th><th scope="col">Queue</th>
              {HEADS.map(([h, tip]) => <th key={h} scope="col" className="cc-num" title={tip}>{h}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>{qs.map((q) => { const target = q.sla_minutes ? "Target time " + q.sla_minutes + " min" : "No target time";
              const show = () => setOpen(q.id === open ? null : q.id);
              return <tr key={q.id} className={q.id === open ? "is-selected" : undefined} aria-selected={q.id === open} onClick={rowClick(show)}>
              <td><span className={"cc-pill is-" + (q.processing ? "active" : "disabled")}><span className="cc-pill-dot" />{q.processing ? "On" : "Off"}</span></td>
              <td title={target}><strong>{q.name}</strong></td>
              <td className="cc-num">{q.waiting}</td><td className="cc-num">{age(q.oldest_waiting)}</td>
              <td className={"cc-num" + (q.overdue ? " cc-err" : "")} title={target}>{q.sla_minutes ? q.overdue ?? 0 : "–"}</td>
              <td className={"cc-num" + (q.at_risk ? " cc-sev is-warning" : "")} title={target}>{q.sla_minutes ? q.at_risk ?? 0 : "–"}</td>
              <td className="cc-num">{q.finished}</td><td className="cc-num">{rate(q.success_rate)}</td><td className="cc-num">{rate(q.in_time_rate)}</td><td className="cc-num">{q.per_hour}</td>
              <td className="cc-num">{dur(q.avg_handling_ms)}</td><td className="cc-num">{dur(q.avg_wait_ms)}</td>
              <td className="cc-num">{q.runners_needed ?? "–"}{q.runners_needed != null && poolSize(q) != null ? " of " + poolSize(q) : ""}</td>
              <td className="cc-list-menu"><RowMenu label={"Actions for " + q.name} items={[{ key: "show", label: q.id === open ? "Hide its details" : "Show its details", onSelect: show },
                { key: "open", label: "Open the queue", onSelect: () => { location.hash = "#/queues?queue=" + encodeURIComponent(q.id); } }]} /></td>
            </tr>; })}</tbody>
          </table></div>
          <p className="cc-list-hint cc-qi-foot">At risk: waiting more than three quarters of the target time. Runners: waiting items × average handling time ÷ target time, and the runners of its pool.</p>
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
