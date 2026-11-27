import { useCallback, useEffect, useState } from "react";
import type { CrApi, Schedule } from "../../api/cr";
import { DateRange, HelpButton, TextInput, resolveRange, type DateRangeValue, type HelpTopic, PageError } from "../../components";
import { RefreshButton } from "../../console/ListParts";
import { name, time } from "./Overview";
import { PageIntro } from "../../shell/PageIntro";
import { PageTabs, PageTitle } from "../../shell/PageFrame";

/**
 * Run > Upcoming runs (item 11, docs/research/input-help-check.md): every scheduled run coming up, soonest
 * first, on its own page instead of the five in Overview's side panel. Schedules is where they are made and changed.
 */
const HELP: HelpTopic = { title: "Upcoming runs", about: "The runs schedules will start, soonest first.",
  items: [
    { name: "Period", text: "How far ahead to look: today, tomorrow, the next 7 or 30 days, any time, or dates you choose." },
    { name: "Last run", text: "How the schedule's last run ended." },
    { name: "Schedules", text: "Where schedules are made, changed, paused and removed." },
  ] };
const HOW: Record<Schedule["kind"], string> = { every: "Every few minutes", daily: "Daily", weekly: "Weekly", monthly: "Monthly", cron: "By a rule" };
const LAST: Record<string, [string, string]> = { success: ["Completed", "success"], failed: ["Failed", "failed"], stopped: ["Stopped", "stopped"], running: ["Running", "active"], queued: ["Queued", "pending"] };

export function UpcomingRuns({ api }: { api: CrApi }) {
  const [list, setList] = useState<Schedule[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [range, setRange] = useState<DateRangeValue>({ key: "next7" });
  const load = useCallback(() => api.schedules().then((s) => { setList(s); setError(null); }, (e) => setError(e instanceof Error ? e.message : String(e))), [api]);
  useEffect(() => { load(); }, [load]);
  const { from, to } = resolveRange(range);
  const f = q.trim().toLowerCase();
  const shown = (list ?? []).filter((s) => s.enabled && s.next_run && (!from || range.key === "today" || range.key === "next7" || range.key === "next30" || new Date(s.next_run) >= from) && (!to || new Date(s.next_run) < to)
    && (!f || (s.name + " " + s.bot_path).toLowerCase().includes(f))).sort((x, y) => x.next_run!.localeCompare(y.next_run!));
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-up-title">
      <header className="cc-list-head">
        <PageTitle id="cc-up-title" name="Upcoming runs" />
        <HelpButton topic={HELP} /><PageIntro k="page:upcoming" />
        {list && <span className="cc-list-count">{shown.length} run{shown.length === 1 ? "" : "s"}</span>}
        <span className="ov-fill" />
      </header><PageTabs />
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <DateRange label="How far ahead" ahead value={range} onChange={setRange} choices={["today", "tomorrow", "next7", "next30", "any", "custom"]} />
          <span className="cc-list-search"><TextInput type="search" aria-label="Search upcoming runs" placeholder="Search schedules and agents" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {!list && !error && <p className="cc-none" role="status">Loading…</p>}
      {list && <div className="cc-list-panel"><div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Upcoming runs">
          <thead><tr><th>Start time</th><th>Schedule</th><th>Agent</th><th>Repeats</th><th>Time zone</th><th>Last run</th></tr></thead>
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
