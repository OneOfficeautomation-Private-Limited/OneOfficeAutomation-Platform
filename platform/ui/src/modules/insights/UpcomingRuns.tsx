import { useCallback, useEffect, useState } from "react";
import type { CrApi, Schedule } from "../../api/cr";
import { HelpButton, TextInput, type HelpTopic, PageError } from "../../components";
import { RefreshButton } from "../../console/ListParts";
import { name, time } from "./Overview";

/**
 * Operational Insights > Upcoming runs (item 11, docs/research/input-help-check.md): every scheduled run coming up, soonest
 * first, on its own page instead of the five in Overview's side panel. Schedules is where they are made and changed.
 */
const HELP: HelpTopic = { title: "Upcoming runs", about: "The runs schedules will start, soonest first.",
  items: [
    { name: "Today, Next 7 days, All", text: "How far ahead to look." },
    { name: "Last run", text: "How the schedule's last run ended." },
    { name: "Schedules", text: "Where schedules are made, changed, paused and removed." },
  ] };
const HOW: Record<Schedule["kind"], string> = { every: "Every few minutes", daily: "Daily", weekly: "Weekly", monthly: "Monthly", cron: "By a rule" };
const LAST: Record<string, [string, string]> = { success: ["Completed", "success"], failed: ["Failed", "failed"], stopped: ["Stopped", "stopped"], running: ["Running", "active"], queued: ["Queued", "pending"] };
const RANGES: [string, string, number | null][] = [["today", "Today", 1], ["week", "Next 7 days", 7], ["all", "All", null]];

export function UpcomingRuns({ api }: { api: CrApi }) {
  const [list, setList] = useState<Schedule[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [range, setRange] = useState("week");
  const load = useCallback(() => api.schedules().then((s) => { setList(s); setError(null); }, (e) => setError(e instanceof Error ? e.message : String(e))), [api]);
  useEffect(() => { load(); }, [load]);
  const days = RANGES.find(([k]) => k === range)![2];
  const until = days == null ? null : (() => { const d = new Date(); if (days === 1) d.setHours(23, 59, 59, 999); else d.setDate(d.getDate() + days); return d; })();
  const f = q.trim().toLowerCase();
  const shown = (list ?? []).filter((s) => s.enabled && s.next_run && (!until || new Date(s.next_run) <= until)
    && (!f || (s.name + " " + s.bot_path).toLowerCase().includes(f))).sort((x, y) => x.next_run!.localeCompare(y.next_run!));
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-up-title">
      <header className="cc-list-head">
        <h1 id="cc-up-title">Upcoming runs</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{shown.length} run{shown.length === 1 ? "" : "s"}</span>}
        <span className="ov-fill" />
        <a className="ui-btn ui-btn-secondary" href="#/schedules">Schedules</a>
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="ov-seg" role="group" aria-label="How far ahead">{RANGES.map(([k, l]) =>
            <button key={k} type="button" aria-pressed={range === k} onClick={() => setRange(k)}>{l}</button>)}</span>
          <span className="cc-list-search"><TextInput type="search" aria-label="Search upcoming runs" placeholder="Search schedules and agents" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {!list && !error && <p className="cc-none" role="status">Loading…</p>}
      {list && <div className="cc-list-panel"><div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Upcoming runs">
          <thead><tr><th>When</th><th>Schedule</th><th>Agent</th><th>Repeats</th><th>Time zone</th><th>Last run</th></tr></thead>
          <tbody>{shown.map((s) => { const last = s.last_status ? LAST[s.last_status] : null;
            return <tr key={s.id}>
              <td>{time(s.next_run)}</td>
              <td><a href="#/schedules">{s.name}</a></td>
              <td title={s.bot_path}>{name(s.bot_path)}</td>
              <td>{HOW[s.kind] ?? s.kind}</td>
              <td>{s.time_zone}</td>
              <td>{last ? <span className={"cc-pill is-" + last[1]}><span className="cc-pill-dot" />{last[0]}</span> : s.last_run ? "" : "Not run yet"}</td>
            </tr>; })}</tbody>
        </table>
        {!shown.length && <p className="cc-none">{(list ?? []).some((s) => s.enabled && s.next_run) ? "No runs in this period." : "Nothing is scheduled."}</p>}
      </div></div>}
    </section>
  );
}
