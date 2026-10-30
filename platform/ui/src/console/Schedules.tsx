import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Runner, RunnerPool, Schedule } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * Manage > Schedules, built as AA's scheduled automations (checked against AA's docs, UiPath's time triggers and Blue
 * Prism's Scheduler, 31 Oct 2026): schedules with what runs, where, how often, the next and last run; filters; for the
 * selected: enable, disable, run now, change owner, delete. Create in AA's order (agent, run-as, runner or pool, then
 * timing with a time zone) with UiPath's and Blue Prism's holiday calendars and rules for missed times and overlaps.
 */
const DAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
export const repeats = (s: Schedule) => (s.kind === "every" ? "Every " + s.every_minutes + " min" : s.kind === "daily" ? "Daily at " + s.at_time
  : (s.days ?? "").split(",").filter(Boolean).map((d) => d.charAt(0) + d.slice(1).toLowerCase()).join(", ") + " at " + s.at_time) + " (" + s.time_zone + ")";

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

  return (
    <section className="st-page cc-home" aria-labelledby="cc-sched-title">
      <header className="st-page-head">
        <div><h1 id="cc-sched-title">Schedules</h1><p>Run agents automatically. Scheduled runs use the vault permissions of the schedule's owner, or of the run-as user; holiday calendars skip the dates you list.</p></div>
        <span className="cc-inline">
          <Button kind="secondary" onClick={() => setDialog({ kind: "calendars" })}>Holiday calendars</Button>
          {manage && <Button kind="primary" onClick={() => setDialog({ kind: "new" })}>Create schedule</Button>}
        </span>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search schedules" placeholder="Search schedules, agents, owners" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} />
        <Select aria-label="State" value={f.state ?? ""} onChange={(e) => setF({ ...f, state: e.target.value })} options={[{ value: "", label: "Enabled and disabled" }, { value: "on", label: "Enabled" }, { value: "off", label: "Disabled" }]} />
        {agents.length > 1 && <Select aria-label="Agent" value={f.agent ?? ""} onChange={(e) => setF({ ...f, agent: e.target.value })} options={[{ value: "", label: "Any agent" }, ...agents.map((a) => ({ value: a, label: agentName(a) }))]} />}
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
      </div>
      <div className="cc-bar" role="toolbar" aria-label="Schedule actions">
        {chosen.length > 0 && <span className="cc-chosen">{chosen.length} selected</span>}
        {manage && <>
          <Button kind="secondary" disabled={!chosen.some((s) => !s.enabled)} onClick={() => api.bulkSchedules(chosen.map((s) => s.id), "enable").then((r) => { onNotice("Enabled " + r.changed + " schedule(s)."); setSelected([]); load(); }, (e) => setError(message(e)))}>Enable</Button>
          <Button kind="secondary" disabled={!chosen.some((s) => s.enabled)} onClick={() => api.bulkSchedules(chosen.map((s) => s.id), "disable").then((r) => { onNotice("Disabled " + r.changed + " schedule(s)."); setSelected([]); load(); }, (e) => setError(message(e)))}>Disable</Button>
          <Button kind="secondary" disabled={!chosen.length} onClick={() => setDialog({ kind: "owner" })}>Change owner</Button>
        </>}
        {can("runs.start") && <Button kind="secondary" disabled={!chosen.length} onClick={() => each("Started", (s) => api.runSchedule(s.id))}>Run now</Button>}
        {manage && <Button kind="danger" disabled={!chosen.length} onClick={() => setDialog({ kind: "delete" })}>Delete</Button>}
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={load}>Refresh</Button>
        <Button kind="secondary" onClick={exportCsv}>Export CSV</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Schedules list">
          <thead><tr><th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={shown.length > 0 && shown.every((s) => selected.includes(s.id))}
            onChange={(e) => setSelected(e.target.checked ? shown.map((s) => s.id) : [])} /></th>
            <th scope="col">State</th><th scope="col">Schedule</th><th scope="col">Agent</th><th scope="col">Runs on</th><th scope="col">Repeats</th>
            <th scope="col">Next run</th><th scope="col">Last run</th><th scope="col">Owner</th></tr></thead>
          <tbody>
            {shown.map((s) => (
              <tr key={s.id} className={selected.includes(s.id) ? "is-selected" : ""} onClick={(e) => { if (!(e.target as HTMLElement).closest("input")) setDialog({ kind: "edit", s }); }}>
                <td className="cc-check"><input type="checkbox" aria-label={"Select " + s.name} checked={selected.includes(s.id)}
                  onChange={(e) => setSelected(e.target.checked ? [...selected, s.id] : selected.filter((x) => x !== s.id))} /></td>
                <td className={"cc-status is-" + (s.enabled ? "success" : "disabled")}>{s.enabled ? "Enabled" : "Disabled"}</td>
                <td>{s.name}</td><td>{agentName(s.bot_path)}</td><td>{runsOn(s)}</td>
                <td>{repeats(s)}{s.holiday_calendar ? ", not on " + s.holiday_calendar : ""}</td>
                <td>{s.enabled ? when(s.next_run) : ""}</td><td>{s.last_run ? when(s.last_run) : "Never"}</td>
                <td>{s.created_by}{s.run_as && s.run_as !== s.created_by && <div className="st-muted">runs as {s.run_as}</div>}</td>
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={9} className="st-muted">{list.length ? "No schedules match." : "No schedules yet."}</td></tr>}
            {!list && !error && <tr><td colSpan={9} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>

      {dialog?.kind === "new" && <NewSchedule api={api} from={dialog.from} runners={runners} pools={pools} onClose={() => setDialog(null)} onCreated={(t) => { onNotice(t); setDialog(null); load(); }} />}
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
    <Field label="Times missed while the Command Center was down">{(id) => <Select id={id} value={missed} onChange={(e) => onMissed(e.target.value)} options={[{ value: "once", label: "Run once for them" }, { value: "skip", label: "Skip them" }]} />}</Field>
    <Field label="When the previous run is still going">{(id) => <Select id={id} value={overlap} onChange={(e) => onOverlap(e.target.value)} options={[{ value: "allow", label: "Start another run" }, { value: "skip", label: "Skip this time" }]} />}</Field>
  </div>;
}

/** Create schedule in AA's order: name and agent, run-as, where it runs, then when (with a time zone and holiday calendar). */
function NewSchedule({ api, from, runners, pools, onClose, onCreated }: { api: CrApi; from?: Schedule; runners: Runner[]; pools: RunnerPool[]; onClose: () => void; onCreated: (t: string) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [people, setPeople] = useState<{ username: string; device_name?: string | null }[]>([]);
  const [cals, setCals] = useState<string[]>([]);
  const [v, setV] = useState({ name: from ? from.name + " (copy)" : "", bot: from?.bot_path ?? "", runAs: from?.run_as ?? "",
    where: from ? (from.pool_id ? "pool" : from.device_id ? "runner" : "default") : "runner", device: from?.device_id ?? "", pool: from?.pool_id ?? "",
    kind: from?.kind ?? "daily", every: String(from?.every_minutes ?? 60), at: from?.at_time ?? "08:30", days: (from?.days ?? "MON,TUE,WED,THU,FRI").split(",").filter(Boolean),
    tz: from?.time_zone ?? (Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"), cal: from?.holiday_calendar ?? "", missed: from?.missed_runs ?? "once", overlap: from?.overlap ?? "allow",
    inputs: from?.inputs ? (typeof from.inputs === "string" ? from.inputs : JSON.stringify(from.inputs)) : "" });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api.liveAgents().then((b) => { setAgents(b.map((x) => x.path)); if (!v.bot && b.length) setV((o) => ({ ...o, bot: o.bot || b[0].path })); }, () => {});
    api.defaultRunners().then(setPeople, () => {});
    api.calendars().then((c) => setCals(c.map((x) => x.name)), () => {});
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const create = async () => {
    let inputs: unknown = {};
    if (v.inputs.trim()) { try { inputs = JSON.parse(v.inputs); } catch { setError("Inputs are not JSON."); return; } }
    const b: Record<string, unknown> = { name: v.name.trim() || "Schedule", bot_path: v.bot, run_as: v.runAs || null, kind: v.kind, time_zone: v.tz.trim(), holiday_calendar: v.cal || null,
      missed_runs: v.missed, overlap: v.overlap, inputs, device_id: v.where === "runner" ? v.device : null, pool_id: v.where === "pool" ? v.pool : null };
    if (v.kind === "every") b.every_minutes = parseInt(v.every, 10); else b.at_time = v.at;
    if (v.kind === "weekly") b.days = v.days.join(",");
    try { const r = await api.createSchedule(b); onCreated("Created " + b.name + "." + (r.next_run ? " Next run " + when(r.next_run) + "." : "")); } catch (e) { setError(message(e)); }
  };
  const ok = v.bot && (v.where !== "runner" || v.device) && (v.where !== "pool" || v.pool) && (v.where !== "default" || v.runAs) && (v.kind !== "weekly" || v.days.length > 0);
  return (
    <Dialog open title={from ? "Copy of " + from.name : "Create schedule"} width={680} onClose={onClose} footer={<>
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
          <label key={k} className="st-check-line"><input type="radio" name="where" checked={v.where === k} disabled={k === "default" && !v.runAs} onChange={() => setV({ ...v, where: k })} /> {l}</label>)}
      </div>
      {v.where === "runner" && <Field label="Runner">{(id) => <Select id={id} value={v.device} onChange={set("device")} options={[{ value: "", label: "Choose a runner" }, ...runners.map((r) => ({ value: r.id, label: r.name + " (" + r.status + ")" }))]} />}</Field>}
      {v.where === "pool" && <Field label="Runner pool">{(id) => <Select id={id} value={v.pool} onChange={set("pool")} options={[{ value: "", label: "Choose a pool" }, ...pools.map((p) => ({ value: p.id, label: p.name }))]} />}</Field>}
      <h3 className="cc-log-title">When</h3>
      <div className="cc-grid2">
        <Field label="Repeat">{(id) => <Select id={id} value={v.kind} onChange={set("kind")} options={[{ value: "every", label: "Every N minutes" }, { value: "daily", label: "Daily" }, { value: "weekly", label: "Weekly" }]} />}</Field>
        {v.kind === "every" ? <Field label="Minutes">{(id) => <TextInput id={id} type="number" min={1} value={v.every} onChange={set("every")} />}</Field>
          : <Field label="Time (HH:MM)">{(id) => <TextInput id={id} value={v.at} placeholder="08:30" onChange={set("at")} />}</Field>}
      </div>
      {v.kind === "weekly" && <div className="cc-inline" role="group" aria-label="Days">{DAYS.map((d) =>
        <label key={d} className="st-check-line"><input type="checkbox" checked={v.days.includes(d)} onChange={(e) => setV({ ...v, days: e.target.checked ? [...v.days, d] : v.days.filter((x) => x !== d) })} /> {d.charAt(0) + d.slice(1).toLowerCase()}</label>)}</div>}
      <div className="cc-grid2">
        <Field label="Time zone">{(id) => <TextInput id={id} value={v.tz} onChange={set("tz")} />}</Field>
        <Field label="Skip the dates of a holiday calendar">{(id) => <Select id={id} value={v.cal} onChange={set("cal")} options={[{ value: "", label: "None" }, ...cals.map((c) => ({ value: c, label: c }))]} />}</Field>
      </div>
      <RuleFields missed={v.missed} overlap={v.overlap} onMissed={(x) => setV({ ...v, missed: x })} onOverlap={(x) => setV({ ...v, overlap: x })} />
      <Field label="Inputs (JSON, optional)">{(id) => <TextArea id={id} rows={2} value={v.inputs} placeholder="{}" onChange={set("inputs")} />}</Field>
    </Dialog>
  );
}

/** A schedule: what can change in place (name, calendar, owner, run-as, the two rules); its timing is changed by a copy. */
function EditSchedule({ api, s, manage, runsOn, onClose, onCopy, onSaved }: { api: CrApi; s: Schedule; manage: boolean; runsOn: string; onClose: () => void; onCopy: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: s.name, cal: s.holiday_calendar ?? "", owner: s.created_by ?? "", runAs: s.run_as ?? "", missed: s.missed_runs ?? "once", overlap: s.overlap ?? "allow" });
  const [cals, setCals] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.calendars().then((c) => setCals(c.map((x) => x.name)), () => {}); }, [api]);
  const save = () => {
    const b: Record<string, unknown> = { name: v.name.trim(), holiday_calendar: v.cal || null, run_as: v.runAs.trim() || null, missed_runs: v.missed, overlap: v.overlap };
    if (v.owner.trim() && v.owner.trim() !== s.created_by) b.owner = v.owner.trim();
    api.updateSchedule(s.id, b).then(() => onSaved("Saved " + v.name.trim() + "."), (e) => setError(message(e)));
  };
  return (
    <Dialog open title={"Schedule " + s.name} width={640} onClose={onClose} footer={manage ? <>
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

/** Holiday calendars: named lists of dates schedules skip (UiPath's non-working days, Blue Prism's calendars). */
function Calendars({ api, manage, onClose, onNotice }: { api: CrApi; manage: boolean; onClose: () => void; onNotice: (t: string) => void }) {
  const [cals, setCals] = useState<{ name: string; dates: string[]; updated?: string; updated_by?: string }[] | null>(null);
  const [name, setName] = useState("");
  const [dates, setDates] = useState("");
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => api.calendars().then(setCals, (e) => setError(message(e))), [api]);
  useEffect(() => { read(); }, [read]);
  const today = new Date().toISOString().slice(0, 10);
  return (
    <Dialog open title="Holiday calendars" width={640} onClose={onClose} footer={manage ? <Button kind="primary" disabled={!name.trim()}
      onClick={() => api.saveCalendar(name.trim(), dates.split(/[\s,;]+/).filter(Boolean)).then(() => { onNotice("Saved the calendar " + name.trim() + "."); setName(""); setDates(""); read(); }, (e) => setError(message(e)))}>Save calendar</Button> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <table className="st-table" aria-label="Calendars">
        <thead><tr><th scope="col">Calendar</th><th scope="col">Dates</th><th scope="col">Updated</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
        <tbody>
          {(cals ?? []).map((c) => <tr key={c.name}><td>{c.name}</td><td>{c.dates.length} dates{c.dates.length ? " (next " + (c.dates.find((d) => d >= today) ?? "none") + ")" : ""}</td>
            <td>{when(c.updated)}{c.updated_by ? " by " + c.updated_by : ""}</td>
            <td>{manage && <span className="cc-inline"><Button kind="plain" onClick={() => { setName(c.name); setDates(c.dates.join("\n")); }}>Edit</Button>
              <Button kind="plain" onClick={() => api.deleteCalendar(c.name).then(() => { onNotice("Deleted the calendar " + c.name + "."); read(); }, (e) => setError(message(e)))}>Delete</Button></span>}</td></tr>)}
          {cals && !cals.length && <tr><td colSpan={4} className="st-muted">No holiday calendars yet.</td></tr>}
        </tbody>
      </table>
      {manage && <>
        <h3 className="cc-log-title">Add or change a calendar</h3>
        <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="India 2026" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Dates (YYYY-MM-DD, one per line or separated by commas)">{(id) => <TextArea id={id} rows={4} value={dates} placeholder={"2026-01-26\n2026-08-15"} onChange={(e) => setDates(e.target.value)} />}</Field>
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
