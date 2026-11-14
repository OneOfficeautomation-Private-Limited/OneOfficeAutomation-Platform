import { useCallback, useEffect, useMemo, useState } from "react";
import { type CrApi, type CrUser, type FlowVariable, type HolidayCalendar, type Runner, type RunnerPool, type Schedule, type SchedulePreview } from "../api/cr";
import { AgentInputs, agentFilesBrowse, filesOf, Button, Dialog, Field, HelpButton, Select, TextArea, TextInput, type HelpTopic, reportMinor, PageError, missingInputs, vaultRefsFrom } from "../components";
import { RefreshButton, RowMenu, rowClick, when } from "./ListParts";
import { PageIntro } from "../shell/PageIntro";
import { HelpLinks } from "../shell/HelpLinks";

/**
 * Manage > Schedules, built as AA's scheduled automations (checked against AA's docs, UiPath's time triggers and Blue
 * Prism's Scheduler, 31 Oct 2026): schedules with what runs, where, how often, the next and last run; filters; for the
 * selected: enable, disable, run now, change owner, delete. Create in AA's order (agent, run-as, runner or pool, then
 * timing with a time zone) with UiPath's and Blue Prism's holiday calendars and rules for missed times and overlaps.
 * Laid out as the design plan's list pattern (applied 31 Oct 2026): a count, one toolbar, the selected's actions in a
 * bar only while rows are ticked, a ⋯ menu per row.
 * Review round 2 (31 Oct 2026): the last run says how it ended and opens in Agent Activity (as Blue Prism's Scheduler
 * shows the last result); the agent has its icon, as in Automation.
 * Gap G35 (5 Nov 2026): monthly schedules (days of the month or the last day, or an ordinal weekday such as the first
 * Monday, every N months; as AA's and Blue Prism's monthly options) and cron expressions (UiPath's advanced triggers),
 * with the next five times shown while the schedule is made.
 */
const DAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
/** How a schedule's last run ended, as a pill (the words and colours of Agent Activity). */
const LAST: Record<string, [string, string]> = { success: ["Completed", "success"], failed: ["Failed", "failed"], stopped: ["Stopped", "stopped"], running: ["Running", "active"], queued: ["Waiting", "pending"] };
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const DAY_WORDS: Record<string, string> = { MON: "Monday", TUE: "Tuesday", WED: "Wednesday", THU: "Thursday", FRI: "Friday", SAT: "Saturday", SUN: "Sunday" };
const ORDINALS = ["first", "second", "third", "fourth", "fifth", "last"];
/** A monthly rule's parts, as the dialog edits them. */
type MonthlyParts = { mode: "days" | "weekday"; days: string[]; ord: string; wday: string; every: string; missing: string };
/** Reads a stored monthly rule ("days=1,15,last;every=3;start=2026-11;missing=last" or "weekday=first MON"). */
export function parseMonthly(rule?: string | null): MonthlyParts {
  const m: MonthlyParts = { mode: "days", days: ["1"], ord: "first", wday: "MON", every: "1", missing: "skip" };
  for (const part of (rule ?? "").split(";")) {
    const [k, v = ""] = part.split("=").map((x) => x.trim());
    if (k === "days") m.days = v.split(",").filter(Boolean);
    else if (k === "weekday") { const [o, d] = v.split(",")[0].split(/\s+/); m.mode = "weekday"; m.ord = o ?? "first"; m.wday = (d ?? "MON").toUpperCase(); }
    else if (k === "every") m.every = v;
    else if (k === "missing") m.missing = v;
  }
  return m;
}
/** Writes the monthly rule the server stores (it adds the start month when it repeats every N months). */
export function monthlyRule(m: MonthlyParts) {
  const parts = [m.mode === "weekday" ? "weekday=" + m.ord + " " + m.wday : "days=" + m.days.join(",")];
  if (m.every && m.every !== "1") parts.push("every=" + m.every);
  if (m.mode === "days" && m.missing === "last") parts.push("missing=last");
  return parts.join(";");
}
/** A monthly rule in words: "Monthly on day 1, 15 and the last day", "Every 3 months on the first Monday". */
export function monthlyWords(rule?: string | null) {
  const m = parseMonthly(rule);
  const head = m.every && m.every !== "1" ? "Every " + m.every + " months" : "Monthly";
  if (m.mode === "weekday") return head + " on the " + m.ord + " " + (DAY_WORDS[m.wday] ?? m.wday);
  const nums = m.days.filter((d) => d !== "last"), last = m.days.includes("last");
  const list = [...(nums.length ? ["day " + nums.join(", ")] : []), ...(last ? ["the last day"] : [])].join(" and ");
  return head + " on " + list + (m.missing === "last" ? " (the last day when a month is shorter)" : "");
}
/** When a schedule repeats; in lists (short) the time zone only when it is not the viewer's own, so a row fits at 1366 px. */
export const repeats = (s: Schedule, short = false) => (s.kind === "every" ? "Every " + s.every_minutes + " min" : s.kind === "daily" ? "Daily at " + s.at_time
  : s.kind === "monthly" ? monthlyWords(s.rule) + " at " + s.at_time : s.kind === "cron" ? "Cron " + s.rule
  : (s.days ?? "").split(",").filter(Boolean).map((d) => d.charAt(0) + d.slice(1).toLowerCase()).join(", ") + " at " + s.at_time)
  + (short && s.time_zone === Intl.DateTimeFormat().resolvedOptions().timeZone ? "" : " (" + s.time_zone + ")");
/** A preview time in the schedule's own zone: "Mon, 2 Nov 2026 09:00", and its UTC offset. */
const localTime = (local: string) => {
  const [d, rest = ""] = local.split("T");
  const day = new Date(d + "T00:00:00Z").toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
  const off = rest.slice(5).replace(/^:\d\d/, "");
  return { text: day + " " + rest.slice(0, 5), offset: off === "Z" ? "UTC" : "UTC" + off };
};

const HELP: HelpTopic = { title: "Schedules",
  about: "Run agents automatically at set times. A scheduled run uses the permissions and vault access of the schedule's owner, or of the run-as person; "
    + "holiday calendars skip the dates you list.",
  items: [
    { name: "Create schedule", text: "Choose the agent (its live version runs), who it runs as, where it runs (a runner, a runner pool or the run-as "
      + "person's default runner) and when: every N minutes, daily, weekly, monthly or by a cron expression, in a time zone. The next five "
      + "times are shown as you choose. Needs permission to manage schedules." },
    { name: "Monthly", text: "On days of the month (and/or the last day), or on a weekday such as the first Monday or the last Friday, every month "
      + "or every N months. A day a month does not have (31 in April) is skipped, unless you choose to run on the month's last day instead." },
    { name: "Cron", text: "A cron expression: five fields (minute hour day month weekday, such as 0 9 * * MON-FRI) or six to seven Quartz fields "
      + "as UiPath uses (seconds first, such as 0 0 9 ? * MON#1). Times skipped when clocks go forward run as much later; a time repeated when "
      + "clocks go back runs once." },
    { name: "Holiday calendars", text: "Named lists of dates, such as public holidays, with the days of the weekend. A schedule that uses a calendar does not run on its dates; "
      + "agents count working days with it (Date and time: Add working days, Count working days, Next working day, Is working day)." },
    { name: "Times missed and overlapping runs", text: "When creating or opening a schedule: run once for times missed while One CommandCenter was down, or "
      + "skip them; and start another run or skip this time when the previous run is still going." },
    { name: "Last run", text: "How the latest run ended and when. Click it to open that run in Agent Activity." },
    { name: "Open", text: "Shows the schedule; you can change its name, holiday calendar, owner, run-as person and the two rules. "
      + "To change the agent, where it runs or when, copy it as a new schedule and delete this one." },
    { name: "Run now", text: "Starts the schedule's agent straight away, as the schedule would. Needs permission to start runs." },
    { name: "Enable and Disable", text: "A disabled schedule keeps its settings but does not run until you enable it again." },
    { name: "Change owner", text: "Gives the selected schedules to another person; their runs then use that person's permissions and vault access." },
    { name: "Export CSV", text: "Saves the schedules shown, with where and when they run and their next and last runs, as a CSV file." },
  ] };

export function Schedules({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Schedule[] | null>(null);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState<null | { kind: "new"; from?: Schedule } | { kind: "edit"; s: Schedule } | { kind: "calendars" } | { kind: "owner" } | { kind: "delete" }>(null);
  const can = (p: string) => user.permissions.includes(p);
  const manage = can("schedules.manage");

  const load = useCallback(async () => {
    try {
      const [s, d, p] = await Promise.all([api.schedules(), can("devices.view") ? api.devices().catch(() => []) : Promise.resolve([]), can("devices.view") ? api.pools().catch(() => []) : Promise.resolve([])]);
      setList(s); setRunners(d); setPools(p); setError(null); setSelected((x) => x.filter((id) => s.some((y) => y.id === id)));
    } catch (e) { setError(message(e)); }
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [load]);

  const runsOn = useCallback((s: Schedule) => s.pool_id ? "Pool " + (pools.find((p) => p.id === s.pool_id)?.name ?? s.pool_id)
    : s.device_id ? runners.find((r) => r.id === s.device_id)?.name ?? s.device_id : "Default runner of " + s.run_as, [pools, runners]);
  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    return (list ?? []).filter((s) => (!text || (s.name + " " + s.bot_path + " " + (s.created_by ?? "") + " " + (s.run_as ?? "")).toLowerCase().includes(text))
      && (!f.state || (f.state === "on") === s.enabled) && (!f.agent || s.bot_path === f.agent));
  }, [list, f]);
  const chosen = (list ?? []).filter((s) => selected.includes(s.id));
  const each = async (what: string, fn: (s: Schedule) => Promise<unknown>) => {
    let done = 0, failed: string | null = null;
    for (const s of chosen) { try { await fn(s); done++; } catch (e) { failed ??= s.name + ": " + message(e); } }
    onNotice(what + " " + done + " schedule" + (done === 1 ? "" : "s") + "."); setError(failed); setSelected([]); load();
  };
  const exportCsv = () => {
    const text = [["Schedule", "Agent", "Runs on", "Repeats", "Holiday calendar", "Owner", "Run as", "Next run", "Last run", "Enabled"],
      ...shown.map((s) => [s.name, s.bot_path, runsOn(s), repeats(s), s.holiday_calendar ?? "", s.created_by ?? "", s.run_as ?? "", when(s.next_run), when(s.last_run), s.enabled ? "yes" : "no"])]
      .map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "schedules.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + shown.length + " schedule" + (shown.length === 1 ? "" : "s") + ".");
  };
  const agents = [...new Set((list ?? []).map((s) => s.bot_path))].sort();

  const filtered = Object.values(f).some(Boolean);
  const enabled = (list ?? []).filter((s) => s.enabled).length;
  const setState = (s: Schedule, on: boolean) => api.bulkSchedules([s.id], on ? "enable" : "disable")
    .then(() => { onNotice((on ? "Enabled " : "Disabled ") + s.name + "."); load(); }, (e) => setError(message(e)));
  const rowMenu = (s: Schedule) => [
    { key: "open", label: "Open", onSelect: () => setDialog({ kind: "edit", s }) },
    ...(can("runs.start") ? [{ key: "run", label: "Run now", onSelect: () => { api.runSchedule(s.id).then(() => onNotice("Started " + s.name + "."), (e) => setError(message(e))); } }] : []),
    ...(manage ? [{ key: "state", label: s.enabled ? "Disable" : "Enable", onSelect: () => { setState(s, !s.enabled); } },
      { key: "copy", label: "Copy as a new schedule", onSelect: () => setDialog({ kind: "new", from: s }) },
      { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => { setSelected([s.id]); setDialog({ kind: "delete" }); } }] : []),
  ];

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-sched-title">
      <header className="cc-list-head">
        <h1 id="cc-sched-title">Schedules</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:schedules" />
        {list && <span className="cc-list-count">{list.length} schedule{list.length === 1 ? "" : "s"} · {enabled} enabled</span>}
        <span className="ov-fill" />
        <Button kind="secondary" onClick={() => setDialog({ kind: "calendars" })}>Holiday calendars</Button>
        {manage && <Button kind="primary" onClick={() => setDialog({ kind: "new" })}>Create schedule</Button>}
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search schedules" placeholder="Search schedules, agents, owners" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} /></span>
          <span className={"cc-chip" + (f.state ? " is-on" : "")}><Select aria-label="Status" value={f.state ?? ""} onChange={(e) => setF({ ...f, state: e.target.value })}
            options={[{ value: "", label: "Status: Any" }, { value: "on", label: "Status: Enabled" }, { value: "off", label: "Status: Disabled" }]} /></span>
          {agents.length > 1 && <span className={"cc-chip" + (f.agent ? " is-on" : "")}><Select aria-label="Agent" value={f.agent ?? ""} onChange={(e) => setF({ ...f, agent: e.target.value })}
            options={[{ value: "", label: "Agent: Any" }, ...agents.map((a) => ({ value: a, label: "Agent: " + agentName(a) }))]} /></span>}
          {filtered && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Schedules list tools">
          <Button kind="plain" onClick={exportCsv}>Export CSV</Button>
          <RefreshButton onClick={load} />
        </div>
      </div>
      <div className="cc-list-panel">
        {chosen.length > 0 && <div className="cc-list-bulk" role="toolbar" aria-label="Schedule actions">
          <strong>{chosen.length} selected</strong>
          {manage && <>
            {chosen.some((s) => !s.enabled) && <Button kind="secondary" onClick={() => api.bulkSchedules(chosen.map((s) => s.id), "enable").then((r) => { onNotice("Enabled " + r.changed + " schedule(s)."); setSelected([]); load(); }, (e) => setError(message(e)))}>Enable</Button>}
            {chosen.some((s) => s.enabled) && <Button kind="secondary" onClick={() => api.bulkSchedules(chosen.map((s) => s.id), "disable").then((r) => { onNotice("Disabled " + r.changed + " schedule(s)."); setSelected([]); load(); }, (e) => setError(message(e)))}>Disable</Button>}
            <Button kind="secondary" onClick={() => setDialog({ kind: "owner" })}>Change owner</Button>
          </>}
          {can("runs.start") && <Button kind="secondary" onClick={() => each("Started", (s) => api.runSchedule(s.id))}>Run now</Button>}
          {manage && <Button kind="danger" onClick={() => setDialog({ kind: "delete" })}>Delete</Button>}
          <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button>
        </div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Schedules list">
            <thead><tr><th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={shown.length > 0 && shown.every((s) => selected.includes(s.id))}
              onChange={(e) => setSelected(e.target.checked ? shown.map((s) => s.id) : [])} /></th>
              <th scope="col">Status</th><th scope="col">Schedule</th><th scope="col">Agent</th><th scope="col">Runner</th><th scope="col">Repeats</th>
              <th scope="col">Next run</th><th scope="col">Last run</th><th scope="col">Owner</th><th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((s) => (
                <tr key={s.id} className={selected.includes(s.id) ? "is-selected" : ""} onClick={rowClick(() => setDialog({ kind: "edit", s }))}>
                  <td className="cc-check"><input type="checkbox" aria-label={"Select " + s.name} checked={selected.includes(s.id)}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, s.id] : selected.filter((x) => x !== s.id))} /></td>
                  <td><span className={"cc-pill is-" + (s.enabled ? "active" : "disabled")}><span className="cc-pill-dot" />{s.enabled ? "Enabled" : "Disabled"}</span></td>
                  <td><strong>{s.name}</strong></td><td><span className="cc-aico" aria-hidden="true">A</span>{agentName(s.bot_path)}</td><td>{runsOn(s)}</td>
                  <td title={repeats(s)}>{repeats(s, true)}{s.holiday_calendar ? ", not on " + s.holiday_calendar : ""}</td>
                  <td>{s.enabled ? when(s.next_run) : <span className="cc-none">–</span>}</td><td>{s.last_run_id ? <a className="cc-last-run" href={"#/activity?run=" + encodeURIComponent(s.last_run_id)} title="Open this run in Agent Activity">
                    <span className={"cc-pill is-" + (LAST[s.last_status ?? ""]?.[1] ?? "pending")}><span className="cc-pill-dot" />{LAST[s.last_status ?? ""]?.[0] ?? s.last_status}</span> {when(s.last_run)}</a>
                    : s.last_run ? when(s.last_run) : <span className="cc-none">Never</span>}</td>
                  <td>{s.created_by}{s.run_as && s.run_as !== s.created_by && <span className="cc-none"> · runs as {s.run_as}</span>}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + s.name} items={rowMenu(s)} /></td>
                </tr>
              ))}
              {list && !shown.length && <tr className="is-static"><td colSpan={10} className="cc-none">{list.length ? "No schedules match." : "No schedules yet. Press Create schedule to run an agent at set times: every day, every week, every N minutes or on a calendar."}</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={10} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}{filtered ? " matching" : ""}</span></div>}
      </div>

      {dialog?.kind === "new" && <NewSchedule api={api} from={dialog.from} defaultTz={user.default_time_zone} runners={runners} pools={pools} onClose={() => setDialog(null)} onCreated={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "edit" && <EditSchedule key={dialog.s.id} api={api} s={dialog.s} manage={manage} runsOn={runsOn(dialog.s)} onClose={() => setDialog(null)}
        onCopy={() => setDialog({ kind: "new", from: dialog.s })} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "calendars" && <Calendars api={api} manage={manage} onClose={() => setDialog(null)} onNotice={onNotice} />}
      {dialog?.kind === "owner" && <OwnerDialog count={chosen.length} onClose={() => setDialog(null)}
        onSave={(o) => api.bulkSchedules(chosen.map((s) => s.id), "owner", o).then((r) => { onNotice("Gave " + r.changed + " schedule(s) to " + o + "."); setDialog(null); setSelected([]); load(); })} />}
      {dialog?.kind === "delete" && (
        <Dialog open title="Delete schedules" width={440} onClose={() => setDialog(null)} footer={<>
          <Button kind="plain" onClick={() => setDialog(null)}>Cancel</Button>
          <Button kind="danger" onClick={() => { setDialog(null); each("Deleted", (s) => api.deleteSchedule(s.id)); }}>Delete</Button></>}>
          <p>Delete {chosen.map((s) => s.name).join(", ")}? Runs already started go on.</p>
        </Dialog>
      )}
    </section>
  );
}

/** Missed times and overlapping runs: the two rules a schedule follows (UiPath's and Blue Prism's options). */
function RuleFields({ missed, overlap, onMissed, onOverlap }: { missed: string; overlap: string; onMissed: (v: string) => void; onOverlap: (v: string) => void }) {
  return <div className="cc-grid2">
    <Field label="Times missed while One CommandCenter was down">{(id) => <Select id={id} value={missed} onChange={(e) => onMissed(e.target.value)} options={[{ value: "once", label: "Run once for them" }, { value: "skip", label: "Skip them" }]} />}</Field>
    <Field label="When the previous run is still going">{(id) => <Select id={id} value={overlap} onChange={(e) => onOverlap(e.target.value)} options={[{ value: "allow", label: "Start another run" }, { value: "skip", label: "Skip this time" }]} />}</Field>
  </div>;
}

/** A copied schedule's inputs as an object (older ones kept them as JSON text). */
function startInputs(x: unknown): Record<string, unknown> {
  if (typeof x === "string") { try { x = JSON.parse(x); } catch { return {}; } }
  return x && typeof x === "object" && !Array.isArray(x) ? x as Record<string, unknown> : {};
}

/** Create schedule in AA's order: name and agent, run-as, where it runs, then when (with a time zone and holiday calendar). */
function NewSchedule({ api, from, defaultTz, runners, pools, onClose, onCreated }: { api: CrApi; from?: Schedule; defaultTz?: string; runners: Runner[]; pools: RunnerPool[];
  onClose: () => void; onCreated: (t: string) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [people, setPeople] = useState<{ username: string; device_name?: string | null }[]>([]);
  const [cals, setCals] = useState<string[]>([]);
  const [v, setV] = useState({ name: from ? from.name + " (copy)" : "", bot: from?.bot_path ?? "", runAs: from?.run_as ?? "",
    where: from ? (from.pool_id ? "pool" : from.device_id ? "runner" : "default") : "runner", device: from?.device_id ?? "", pool: from?.pool_id ?? "",
    kind: from?.kind ?? "daily", every: String(from?.every_minutes ?? 60), at: from?.at_time ?? "08:30", days: (from?.days ?? "MON,TUE,WED,THU,FRI").split(",").filter(Boolean),
    monthly: parseMonthly(from?.kind === "monthly" ? from.rule : null), cron: from?.kind === "cron" ? from.rule ?? "" : "0 9 * * MON-FRI",
    tz: from?.time_zone ?? (defaultTz || Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"), cal: from?.holiday_calendar ?? "", missed: from?.missed_runs ?? "once", overlap: from?.overlap ?? "allow" });
  const [inputs, setInputs] = useState<Record<string, unknown>>(() => startInputs(from?.inputs));
  const [vars, setVars] = useState<FlowVariable[] | null>(null);
  const [files, setFiles] = useState<string[]>([]);   // pickers (14 Nov 2026): the agent's files, for Browse… on File inputs
  const [bad, setBad] = useState<string | null>(null);
  const vault = useMemo(() => vaultRefsFrom(api), [api]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {   // the chosen agent's inputs, one field each (usability round, 14 Nov 2026)
    setVars(null);
    setFiles([]);
    if (v.bot) api.agentFlow(v.bot, "production").then((r) => { setVars((r.flow.variables ?? []).filter((x) => x.direction === "input" || x.direction === "in_out")); setFiles(filesOf(r.flow)); },
      (e) => { setVars(null); reportMinor("the agent's inputs", e); });
  }, [api, v.bot]);
  useEffect(() => {
    api.liveAgents().then((b) => { setAgents(b.map((x) => x.path)); if (!v.bot && b.length) setV((o) => ({ ...o, bot: o.bot || b[0].path })); }, (e) => reportMinor("the list of agents", e));
    api.defaultRunners().then(setPeople, (e) => reportMinor("the default runners", e));
    api.calendars().then((c) => setCals(c.map((x) => x.name)), (e) => reportMinor("the business calendars", e));
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  // functional updates: the agent list fills in the agent when it arrives, and a change made before React re-renders
  // must not put back the empty agent of an older render (that left Create disabled)
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => { const x = e.target.value; setV((o) => ({ ...o, [k]: x })); };
  const setM = (m: Partial<MonthlyParts>) => setV((o) => ({ ...o, monthly: { ...o.monthly, ...m } }));
  /** The timing part of the schedule, as the server and its preview read it. */
  const timing = useMemo(() => {
    const t: Record<string, unknown> = { kind: v.kind, time_zone: v.tz.trim(), holiday_calendar: v.cal || null };
    if (v.kind === "every") t.every_minutes = parseInt(v.every, 10); else if (v.kind !== "cron") t.at_time = v.at;
    if (v.kind === "weekly") t.days = v.days.join(",");
    if (v.kind === "monthly") t.rule = monthlyRule(v.monthly);
    if (v.kind === "cron") t.rule = v.cron.trim();
    return t;
  }, [v.kind, v.tz, v.cal, v.every, v.at, v.days, v.monthly, v.cron]);
  const create = async () => {
    if (bad) { setError(bad); return; }
    const b: Record<string, unknown> = { name: v.name.trim() || "Schedule", bot_path: v.bot, run_as: v.runAs || null, ...timing,
      missed_runs: v.missed, overlap: v.overlap, inputs, device_id: v.where === "runner" ? v.device : null, pool_id: v.where === "pool" ? v.pool : null };
    try { const r = await api.createSchedule(b); onCreated("Created " + b.name + "." + (r.next_run ? " Next run " + when(r.next_run) + "." : "")); } catch (e) { setError(message(e)); }
  };
  const ok = !bad && !missingInputs(vars, inputs).length && v.bot && (v.where !== "runner" || v.device) && (v.where !== "pool" || v.pool) && (v.where !== "default" || v.runAs) && (v.kind !== "weekly" || v.days.length > 0)
    && (v.kind !== "monthly" || v.monthly.mode === "weekday" || v.monthly.days.length > 0) && (v.kind !== "cron" || v.cron.trim() !== "");
  return (
    <Dialog open title={from ? "Copy of " + from.name : "Create schedule"} width={680} onClose={onClose} help={<HelpLinks k="dialog:schedule" label="Schedules" />} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!ok} onClick={create}>Create schedule</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} placeholder="Nightly invoice run" onChange={set("name")} />}</Field>
        <Field label="Agent (its live version)">{(id) => <Select id={id} value={v.bot} onChange={set("bot")} options={agents.length ? agents.map((a) => ({ value: a, label: agentName(a) })) : [{ value: "", label: "No agent is live yet" }]} />}</Field>
      </div>
      <Field label="Run as (credentials are checked against this person)">{(id) => <Select id={id} value={v.runAs} onChange={set("runAs")}
        options={[{ value: "", label: "The owner (you)" }, ...people.map((p) => ({ value: p.username, label: p.username + (p.device_name ? " (default runner " + p.device_name + ")" : "") }))]} />}</Field>
      <h3 className="cc-log-title">Where</h3>
      <div role="radiogroup" aria-label="Where it runs" className="cc-inline">
        {([["runner", "A runner"], ["pool", "A runner pool"], ["default", "The run-as user's default runner"]] as const).map(([k, l]) =>
          <label key={k} className="st-check-line"><input type="radio" name="where" checked={v.where === k} disabled={k === "default" && !v.runAs} onChange={() => setV((o) => ({ ...o, where: k }))} /> {l}</label>)}
      </div>
      {v.where === "runner" && <Field label="Runner">{(id) => <Select id={id} value={v.device} onChange={set("device")} options={[{ value: "", label: "Choose a runner" }, ...runners.map((r) => ({ value: r.id, label: r.name + " (" + r.status + ")" }))]} />}</Field>}
      {v.where === "pool" && <Field label="Runner pool">{(id) => <Select id={id} value={v.pool} onChange={set("pool")} options={[{ value: "", label: "Choose a pool" }, ...pools.map((p) => ({ value: p.id, label: p.name }))]} />}</Field>}
      <h3 className="cc-log-title">When</h3>
      <div className="cc-grid2">
        <Field label="Repeat">{(id) => <Select id={id} value={v.kind} onChange={set("kind")} options={[{ value: "every", label: "Every N minutes" }, { value: "daily", label: "Daily" },
          { value: "weekly", label: "Weekly" }, { value: "monthly", label: "Monthly" }, { value: "cron", label: "Cron expression" }]} />}</Field>
        {v.kind === "every" ? <Field label="Minutes">{(id) => <TextInput id={id} type="number" min={1} value={v.every} onChange={set("every")} />}</Field>
          : v.kind === "cron" ? <Field label="Cron expression" hint="minute hour day month weekday; Quartz: second first, ? and L, W, #">{(id) =>
            <TextInput id={id} mono value={v.cron} placeholder="0 9 * * MON-FRI" spellCheck={false} onChange={set("cron")} />}</Field>
          : <Field label="Time (HH:MM)">{(id) => <TextInput id={id} value={v.at} placeholder="08:30" onChange={set("at")} />}</Field>}
      </div>
      {v.kind === "weekly" && <div className="cc-inline" role="group" aria-label="Days">{DAYS.map((d) =>
        <label key={d} className="st-check-line"><input type="checkbox" checked={v.days.includes(d)} onChange={(e) => { const on = e.target.checked; setV((o) => ({ ...o, days: on ? [...o.days, d] : o.days.filter((x) => x !== d) })); }} /> {d.charAt(0) + d.slice(1).toLowerCase()}</label>)}</div>}
      {v.kind === "monthly" && <MonthlyFields m={v.monthly} onChange={setM} />}
      <div className="cc-grid2">
        <Field label="Time zone">{(id) => <TextInput id={id} value={v.tz} onChange={set("tz")} />}</Field>
        <Field label="Skip the dates of a holiday calendar">{(id) => <Select id={id} value={v.cal} onChange={set("cal")} options={[{ value: "", label: "None" }, ...cals.map((c) => ({ value: c, label: c }))]} />}</Field>
      </div>
      <NextRuns api={api} timing={timing} />
      <RuleFields missed={v.missed} overlap={v.overlap} onMissed={(x) => setV((o) => ({ ...o, missed: x }))} onOverlap={(x) => setV((o) => ({ ...o, overlap: x }))} />
      <AgentInputs label="Inputs of the agent" inputs={vars} value={inputs} onChange={setInputs} credentials={vault} onJsonError={setBad} browse={agentFilesBrowse(files)} />
    </Dialog>
  );
}

/** A monthly schedule's days: days of the month (and the last day), or an ordinal weekday; every N months; a missing day. */
function MonthlyFields({ m, onChange }: { m: MonthlyParts; onChange: (m: Partial<MonthlyParts>) => void }) {
  const toggle = (d: string) => onChange({ days: m.days.includes(d) ? m.days.filter((x) => x !== d)
    : [...m.days, d].sort((a, b) => (a === "last" ? 99 : +a) - (b === "last" ? 99 : +b)) });
  return <div className="cc-monthly">
    <div role="radiogroup" aria-label="Monthly on" className="cc-inline">
      <label className="st-check-line"><input type="radio" name="mmode" checked={m.mode === "days"} onChange={() => onChange({ mode: "days" })} /> Days of the month</label>
      <label className="st-check-line"><input type="radio" name="mmode" checked={m.mode === "weekday"} onChange={() => onChange({ mode: "weekday" })} /> A weekday of the month</label>
    </div>
    {m.mode === "days" ? <>
      <div className="cc-mdays" role="group" aria-label="Days of the month">
        {[...Array.from({ length: 31 }, (_, i) => String(i + 1)), "last"].map((d) =>
          <button key={d} type="button" className={d === "last" ? "is-last" : ""} aria-pressed={m.days.includes(d)} aria-label={d === "last" ? "Last day" : "Day " + d}
            onClick={() => toggle(d)}>{d === "last" ? "Last day" : d}</button>)}
      </div>
    </> : <div className="cc-grid2">
      <Field label="Which">{(id) => <Select id={id} value={m.ord} onChange={(e) => onChange({ ord: e.target.value })}
        options={ORDINALS.map((o) => ({ value: o, label: o.charAt(0).toUpperCase() + o.slice(1) }))} />}</Field>
      <Field label="Weekday">{(id) => <Select id={id} value={m.wday} onChange={(e) => onChange({ wday: e.target.value })}
        options={DAYS.map((d) => ({ value: d, label: DAY_WORDS[d] }))} />}</Field>
    </div>}
    <div className="cc-grid2">
      <Field label="Every (months)">{(id) => <TextInput id={id} type="number" min={1} max={120} value={m.every} onChange={(e) => onChange({ every: e.target.value })} />}</Field>
      {m.mode === "days" && <Field label="A day the month does not have">{(id) => <Select id={id} value={m.missing} onChange={(e) => onChange({ missing: e.target.value })}
        options={[{ value: "skip", label: "Skip that month" }, { value: "last", label: "Run on the month's last day" }]} />}</Field>}
    </div>
  </div>;
}

/** The next five times a draft schedule runs, asked of One CommandCenter as the timing changes (holidays marked). */
function NextRuns({ api, timing }: { api: CrApi; timing: Record<string, unknown> }) {
  const [p, setP] = useState<SchedulePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const key = JSON.stringify(timing);
  useEffect(() => {
    let live = true;
    const t = setTimeout(() => api.previewSchedule(JSON.parse(key)).then((r) => { if (live) { setP(r); setError(null); } }, (e) => { if (live) { setP(null); setError(message(e)); /* the message carries the fix */ } }), 300);
    return () => { live = false; clearTimeout(t); };
  }, [api, key]);
  return <div className="cc-nextruns" aria-live="polite">
    <h3 className="cc-log-title">Next runs</h3>
    {error ? <p className="cc-nextruns-error" role="alert">{error}</p>
      : !p ? <p className="cc-none">Working them out…</p>
      : !p.next_runs.length ? <p className="cc-none">This schedule does not run again.</p>
      : <ol aria-label="Next runs">{p.next_runs.map((r) => { const l = localTime(r.local); return <li key={r.time} className={r.holiday ? "is-holiday" : ""}>
        <span>{l.text}</span> <span className="cc-none">{l.offset}</span>{r.holiday && <span className="cc-tag">holiday, skipped</span>}</li>; })}</ol>}
  </div>;
}

/** A schedule: what can change in place (name, calendar, owner, run-as, the two rules); its timing is changed by a copy. */
function EditSchedule({ api, s, manage, runsOn, onClose, onCopy, onSaved }: { api: CrApi; s: Schedule; manage: boolean; runsOn: string; onClose: () => void; onCopy: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: s.name, cal: s.holiday_calendar ?? "", owner: s.created_by ?? "", runAs: s.run_as ?? "", missed: s.missed_runs ?? "once", overlap: s.overlap ?? "allow" });
  const [cals, setCals] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.calendars().then((c) => setCals(c.map((x) => x.name)), (e) => reportMinor("the business calendars", e)); }, [api]);
  const save = () => {
    const b: Record<string, unknown> = { name: v.name.trim(), holiday_calendar: v.cal || null, run_as: v.runAs.trim() || null, missed_runs: v.missed, overlap: v.overlap };
    if (v.owner.trim() && v.owner.trim() !== s.created_by) b.owner = v.owner.trim();
    api.updateSchedule(s.id, b).then(() => onSaved("Saved " + v.name.trim() + "."), (e) => setError(message(e)));
  };
  return (
    <Dialog open title={"Schedule " + s.name} width={640} onClose={onClose} help={<HelpLinks k="dialog:schedule" label="Schedules" />} footer={manage ? <>
      <Button kind="secondary" onClick={() => api.setScheduleEnabled(s.id, !s.enabled).then(() => onSaved((s.enabled ? "Disabled " : "Enabled ") + s.name + "."), (e) => setError(message(e)))}>{s.enabled ? "Disable" : "Enable"}</Button>
      <Button kind="secondary" onClick={onCopy}>Copy as a new schedule</Button>
      <span className="st-bar-fill" /><Button kind="primary" onClick={save}>Save changes</Button></> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <dl className="cc-facts">
        <dt>Agent</dt><dd>{s.bot_path}</dd>
        <dt>Runs on</dt><dd>{runsOn}</dd>
        <dt>Repeats</dt><dd>{repeats(s)}</dd>
        <dt>Next run</dt><dd>{s.enabled ? when(s.next_run) : "Disabled"}</dd>
        <dt>Last run</dt><dd>{s.last_run ? when(s.last_run) : "Never"}</dd>
      </dl>
      <p className="st-muted">To change the agent, where it runs or when, copy it as a new schedule and delete this one.</p>
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} disabled={!manage} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
        <Field label="Skip the dates of a holiday calendar">{(id) => <Select id={id} value={v.cal} disabled={!manage} onChange={(e) => setV({ ...v, cal: e.target.value })} options={[{ value: "", label: "None" }, ...cals.map((c) => ({ value: c, label: c }))]} />}</Field>
        <Field label="Owner (runs use the owner's permissions)">{(id) => <TextInput id={id} value={v.owner} disabled={!manage} onChange={(e) => setV({ ...v, owner: e.target.value })} />}</Field>
        <Field label="Run as (empty: the owner)">{(id) => <TextInput id={id} value={v.runAs} disabled={!manage} onChange={(e) => setV({ ...v, runAs: e.target.value })} />}</Field>
      </div>
      <RuleFields missed={v.missed} overlap={v.overlap} onMissed={(x) => setV({ ...v, missed: x })} onOverlap={(x) => setV({ ...v, overlap: x })} />
    </Dialog>
  );
}

/** A weekend as short day names, e.g. "Sat, Sun"; "none" when every day is a working day. */
export const weekendText = (w?: string[]) => { const d = w ?? ["SAT", "SUN"]; return d.length ? d.map((x) => x.charAt(0) + x.slice(1).toLowerCase()).join(", ") : "none"; };

/**
 * Holiday calendars: named lists of dates schedules skip (UiPath's non-working days, Blue Prism's calendars). Since gap
 * G34 a calendar also has a weekend (Blue Prism's working week): agents' working-day steps in Date and time skip both.
 */
function Calendars({ api, manage, onClose, onNotice }: { api: CrApi; manage: boolean; onClose: () => void; onNotice: (t: string) => void }) {
  const [cals, setCals] = useState<HolidayCalendar[] | null>(null);
  const [name, setName] = useState("");
  const [dates, setDates] = useState("");
  const [weekend, setWeekend] = useState<string[]>(["SAT", "SUN"]);
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => api.calendars().then(setCals, (e) => setError(message(e))), [api]);
  useEffect(() => { read(); }, [read]);
  const today = new Date().toISOString().slice(0, 10);
  const allWeekend = weekend.length === DAYS.length;
  return (
    <Dialog open title="Holiday calendars" width={680} onClose={onClose} help={<HelpLinks k="dialog:holiday-calendars" label="Holiday calendars" />} footer={manage ? <Button kind="primary" disabled={!name.trim() || allWeekend}
      onClick={() => api.saveCalendar(name.trim(), dates.split(/[\s,;]+/).filter(Boolean), DAYS.filter((d) => weekend.includes(d)))
        .then(() => { onNotice("Saved the calendar " + name.trim() + "."); setName(""); setDates(""); setWeekend(["SAT", "SUN"]); read(); }, (e) => setError(message(e)))}>Save calendar</Button> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <table className="st-table" aria-label="Calendars">
        <thead><tr><th scope="col">Calendar</th><th scope="col">Dates</th><th scope="col">Weekend</th><th scope="col">Last modified</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
        <tbody>
          {(cals ?? []).map((c) => <tr key={c.name}><td>{c.name}</td><td>{c.dates.length} dates{c.dates.length ? " (next " + (c.dates.find((d) => d >= today) ?? "none") + ")" : ""}</td>
            <td>{weekendText(c.weekend)}</td>
            <td>{when(c.updated)}{c.updated_by ? " by " + c.updated_by : ""}</td>
            <td>{manage && <span className="cc-inline"><Button kind="plain" onClick={() => { setName(c.name); setDates(c.dates.join("\n")); setWeekend(c.weekend ?? ["SAT", "SUN"]); }}>Edit</Button>
              <Button kind="plain" onClick={() => api.deleteCalendar(c.name).then(() => { onNotice("Deleted the calendar " + c.name + "."); read(); }, (e) => setError(message(e)))}>Delete</Button></span>}</td></tr>)}
          {cals && !cals.length && <tr><td colSpan={5} className="st-muted">No holiday calendars yet.</td></tr>}
        </tbody>
      </table>
      {manage && <>
        <h3 className="cc-log-title">Add or change a calendar</h3>
        <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="India 2026" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Dates (YYYY-MM-DD, one per line or separated by commas)">{(id) => <TextArea id={id} rows={4} value={dates} placeholder={"2026-01-26\n2026-08-15"} onChange={(e) => setDates(e.target.value)} />}</Field>
        <div className="cc-inline" role="group" aria-label="Weekend days">
          <span className="st-muted">Weekend (not working days):</span>
          {DAYS.map((d) => <label key={d} className="st-check-line"><input type="checkbox" checked={weekend.includes(d)}
            onChange={(e) => setWeekend(e.target.checked ? [...weekend, d] : weekend.filter((x) => x !== d))} /> {d.charAt(0) + d.slice(1).toLowerCase()}</label>)}
        </div>
        <p className="st-muted">{allWeekend ? "Leave at least one working day in the week." : "Schedules skip the dates. Agents' working-day steps (Date and time: Add working days, "
          + "Count working days, Next working day, Is working day) skip the dates and the weekend."}</p>
      </>}
    </Dialog>
  );
}

function OwnerDialog({ count, onClose, onSave }: { count: number; onClose: () => void; onSave: (owner: string) => Promise<void> }) {
  const [owner, setOwner] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title="Change owner" width={420} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!owner.trim()} onClick={() => onSave(owner.trim()).catch((e) => setError(message(e)))}>Change owner</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label={"New owner (user name) for " + count + " schedule" + (count === 1 ? "" : "s")}>{(id) => <TextInput id={id} value={owner} onChange={(e) => setOwner(e.target.value)} />}</Field>
      <p className="st-muted">Scheduled runs use the owner's permissions and vault access.</p>
    </Dialog>
  );
}
