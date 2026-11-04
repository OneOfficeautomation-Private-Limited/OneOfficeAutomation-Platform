import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { CrApi, CrUser, ValueReport, ValueRow } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextInput, usePersistent, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";

/**
 * Operational Insights > Value, as AA's Center of Excellence dashboard and UiPath Insights' Business ROI (checked
 * 31 Oct 2026): the manual work the agents' successful runs saved over a period, in hours and money (the hourly cost and
 * currency in Settings), compared with the period before; per day, per top folder and per agent, with each agent's
 * minutes saved per run (its own, or the default) changed in place by people who may put versions in production.
 * A run counts once, so items of a work queue are not counted twice.
 * Review round 2 (1 Nov 2026): the period as a filter with the refresh icon; By agent and By folder in one full-width
 * tabbed panel, one line per row; minutes saved changed in a small dialog from the row or its menu; a folder row shows
 * that folder's agents.
 */
const PERIODS: [string, string][] = [["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"], ["year", "This year"]];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const folderOf = (path: string) => path.split("/").slice(0, -1).join("/");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
type Agent = ValueReport["agents"][number];
type Queue = NonNullable<ValueReport["queues"]>[number];
/** A whole number, or null when the box is empty (use the default). */
const numOrNull = (t: string) => t.trim() === "" ? null : Number(t);
function change(now: number, before: number) {
  if (before === 0) return now === 0 ? "" : "none before";
  const d = Math.round(100 * (now - before) / before);
  return d === 0 ? "same as before" : (d > 0 ? "+" : "") + d + "% on before";
}

const HELP: HelpTopic = { title: "Value",
  about: "The manual work your agents' successful runs saved, in hours and money, per agent and per folder, compared with the period before. A run counts "
    + "once, so work queue items are not counted twice.",
  items: [
    { name: "Period", text: "The time the figures cover: the last 7, 30 or 90 days, or the last year. The change is measured against the same length of "
      + "time before it." },
    { name: "Hours saved", text: "Each successful run times its agent's minutes saved a run." },
    { name: "Money saved", text: "Hours saved times the cost of an hour of the manual work, set in Settings with its currency. It shows a dash until that "
      + "cost is set." },
    { name: "Full-time people", text: "The hours saved as the number of people working 40 hours a week over the period." },
    { name: "Run time and Per hour run", text: "The hours the agents ran, and the hours of manual work saved for each hour they ran." },
    { name: "By agent and By folder", text: "The same figures per agent or per top folder. Click a folder to see only its agents; Show every folder lists "
      + "them all again." },
    { name: "Minutes a run", text: "The manual minutes one successful run of the agent saves; (default) means it uses the default from Settings." },
    { name: "Change savings…", text: "Click an agent, or use its ⋯ menu, to set its own minutes saved, its own cost of an hour and the runs a month "
      + "expected; leave a box empty to use the default. Needs permission to set savings (or to put versions in production)." },
    { name: "Its runs in Agent Activity", text: "Lists the agent's successful runs, the ones counted here." },
    { name: "This year's goal", text: "A goal in hours or money for the year, with what is saved so far and where this pace ends by 31 December "
      + "(UiPath's Business ROI). Set it with Set the goal…; needs permission to set savings." },
    { name: "By work queue", text: "Work done through a queue counts each item at the queue's minutes saved and cost of an hour, when they are set, instead of "
      + "its agent's; so an agent working a queue is not counted twice." },
    { name: "Estimated and Actual a month", text: "Estimated: the runs a month expected when the agent was approved, times its minutes saved. Actual: the hours "
      + "its runs saved, as a month. Set both in Change savings…" },
  ] };

export function Value({ api, user, onNotice , title, tabs }: { api: CrApi; user: CrUser; onNotice: (t: string) => void; title?: string; tabs?: ReactNode }) {
  const [days, setDays] = usePersistent("value.days", "30");
  const [tab, setTab] = useState<"agents" | "queues" | "folders">("agents");
  const [folder, setFolder] = useState("");   // By agent, only this top folder's agents (a folder row's choice)
  const [v, setV] = useState<ValueReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Agent | null>(null);
  const [editingQueue, setEditingQueue] = useState<Queue | null>(null);
  const [goal, setGoal] = useState(false);
  const can = (p: string) => user.permissions.includes(p);
  const canAdmin = can("insights.admin");   // savings and the goal (AA's Insight admin)
  const canChange = canAdmin || can("repo.production");

  const load = useCallback(() => {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const jan1 = new Date(new Date().getFullYear(), 0, 1).toISOString();
    return api.value(days === "year" ? { from: jan1, tz } : { days, tz }).then((r) => { setV(r); setError(null); }, (e) => setError(message(e)));
  }, [api, days]);
  useEffect(() => { load(); }, [load]);

  const money = (n: number) => {
    if (!v?.hourly_cost) return "–";
    try { return v.currency ? new Intl.NumberFormat(undefined, { style: "currency", currency: v.currency, maximumFractionDigits: 0 }).format(n) : n.toLocaleString(); }
    catch { return n.toLocaleString() + " " + v.currency; }
  };
  const label = PERIODS.find(([d]) => d === days)?.[1].toLowerCase() ?? "";
  const unvalued = v ? v.agents.filter((a) => !a.own_value).length : 0;
  // a long period shows weeks (Monday first) rather than days
  const series = !v ? [] : v.by_day.length <= 45 ? v.by_day.map((d) => ({ label: d.day.slice(5), hours: d.hours })) : Object.entries(v.by_day.reduce<Record<string, number>>((acc, d) => {
    const t = new Date(d.day + "T00:00:00"); t.setDate(t.getDate() - ((t.getDay() + 6) % 7));
    const k = t.toISOString().slice(0, 10); acc[k] = (acc[k] ?? 0) + d.hours; return acc; }, {})).map(([k, h]) => ({ label: "Week of " + k.slice(5), hours: Math.round(h * 10) / 10 }));
  const max = Math.max(0.1, ...series.map((x) => x.hours));
  const perRunHour = (r: ValueRow) => r.run_hours > 0 ? Math.round(10 * r.hours / r.run_hours) / 10 + "×" : "–";
  const agents = (v?.agents ?? []).filter((a) => !folder || a.bot_path.startsWith(folder + "/"));
  const agentMenu = (a: Agent) => [
    ...(canChange ? [{ key: "minutes", label: "Change savings…", onSelect: () => setEditing(a) }] : []),
    { key: "runs", label: "Its runs in Agent Activity", onSelect: () => { location.hash = "#/activity?" + new URLSearchParams({ tab: "completed", status: "success", bot: a.bot_path }).toString(); } },
    ...(can("repo.view") ? [{ key: "open", label: "Open in Automation", onSelect: () => { location.hash = "#/automation?q=" + encodeURIComponent(name(a.bot_path)); } }] : []),
  ];
  const nums = (r: ValueRow) => <><td className="cc-num">{r.hours} h</td><td className="cc-num">{money(r.money)}</td><td className="cc-num">{r.run_hours} h</td><td className="cc-num">{perRunHour(r)}</td></>;
  const numHeads = [["Hours saved", "Each successful run × its agent's minutes saved"], ["Money saved", "Hours saved × the cost of an hour (Settings)"],
    ["Run time", "Hours the agents ran"], ["Per hour run", "Hours saved for each hour the agents ran"]].map(([h, tip]) => <th key={h} scope="col" className="cc-num" title={tip}>{h}</th>);

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-val-title">
      <header className="cc-list-head">
        <h1 id="cc-val-title">{title ?? "Value"}</h1>
        <HelpButton topic={HELP} />
      </header>
      {tabs}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-chip"><Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([d, l]) => ({ value: d, label: l }))} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!v && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {v && <>
        {(!v.hourly_cost || (unvalued > 0 && !v.default_minutes_saved)) && <ul className="cc-advice cc-pad-x cc-advice-list" aria-label="Advice">
          {unvalued > 0 && !v.default_minutes_saved && <li>{unvalued} agent{unvalued === 1 ? " has" : "s have"} no minutes saved of {unvalued === 1 ? "its" : "their"} own and the default is 0, so {unvalued === 1 ? "it counts" : "they count"} nothing. Set minutes per agent below{can("settings.manage") ? <>, or the default in <a href="#/settings">Settings</a></> : ""}.</li>}
          {!v.hourly_cost && <li>Set the cost of an hour of the manual work{can("settings.manage") ? <> in <a href="#/settings">Settings</a></> : " (an administrator does this in Settings)"} to see money saved.</li>}
        </ul>}
        <div className="cc-sums" role="group" aria-label="Figures">
          <div className="cc-sum is-static"><strong>{v.hours.toLocaleString()} h</strong><span>Hours saved · {change(v.hours, v.previous_hours) || label}</span></div>
          <div className="cc-sum is-static"><strong>{money(v.money)}</strong><span>Money saved{v.hourly_cost ? " · " + (change(v.money, v.previous_money) || label) : ""}</span></div>
          <div className="cc-sum is-static"><strong>{v.full_time_people}</strong><span>Full-time people (40 h a week) · {label}</span></div>
          <a className="cc-sum" href="#/activity?tab=completed&status=success"><strong>{v.successful_runs.toLocaleString()}</strong><span>Successful runs counted</span></a>
        </div>
        {v.goal && <Goal v={v} money={money} canAdmin={canAdmin} onSet={() => setGoal(true)} />}
        <div className="cc-card cc-card-flat cc-val-chart"><h2>Hours saved {series.length && v.by_day.length > 45 ? "per week" : "per day"}</h2>
          {series.length ? <ul className="cc-hbars" aria-label="Hours saved over time">{series.map((x) => <li key={x.label}><span className="cc-hbar-label">{x.label}</span>
            <span className="cc-hbar-track"><span className="cc-hbar-fill is-done" style={{ width: (100 * x.hours / max) + "%" }} /></span><span className="cc-hbar-value">{x.hours} h</span></li>)}</ul>
            : <p className="st-muted">No successful runs in the period.</p>}
        </div>
        <div className="cc-list-panel cc-ops-lists">
          <div className="ov-tabs" role="tablist" aria-label="Value by">
            <button type="button" role="tab" className="ov-tab" aria-selected={tab === "agents"} onClick={() => setTab("agents")}>By agent<span className="ov-cnt">{v.agents.length}</span></button>
            {!!v.queues?.length && <button type="button" role="tab" className="ov-tab" aria-selected={tab === "queues"} onClick={() => setTab("queues")}>By work queue<span className="ov-cnt">{v.queues.length}</span></button>}
            <button type="button" role="tab" className="ov-tab" aria-selected={tab === "folders"} onClick={() => setTab("folders")}>By folder<span className="ov-cnt">{v.folders.length}</span></button>
            {tab === "agents" && folder && <span className="cc-val-only">Only {folder} <button type="button" className="cc-link" onClick={() => setFolder("")}>Show every folder</button></span>}
          </div>
          <div className="cc-list-scroll">
            {tab === "agents" ? <table className="cc-list-table" aria-label="Value by agent">
              <thead><tr><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col" className="cc-num" title="Successful runs counted">Runs</th><th scope="col" className="cc-num" title="Minutes of manual work one successful run saves">Minutes a run</th>
                <th scope="col" className="cc-num" title="What an hour of the manual work costs: its own, or the default">Cost of an hour</th>
                <th scope="col" className="cc-num" title="Runs a month expected when the agent was approved, times its minutes saved">Estimated a month</th>
                <th scope="col" className="cc-num" title="The hours its runs saved, as a month">Actual a month</th>{numHeads}
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>{agents.map((a) => <tr key={a.bot_path} className={canChange ? undefined : "is-static"} title={canChange ? "Change its savings" : undefined} onClick={rowClick(() => { if (canChange) setEditing(a); })}>
                <td><span className="cc-aico" aria-hidden="true">A</span>{name(a.bot_path)}</td><td>{folderOf(a.bot_path)}</td><td className="cc-num">{a.runs}</td>
                <td className="cc-num">{a.minutes_each}{a.own_value ? "" : " (default)"}</td>
                <td className="cc-num">{a.hourly_cost ? money(a.hourly_cost) + (a.own_cost ? "" : " (default)") : "not set"}</td>
                <td className="cc-num">{a.estimated_hours_month != null ? a.estimated_hours_month + " h" : ""}</td>
                <td className={"cc-num" + (a.estimated_hours_month != null && (a.actual_hours_month ?? 0) < a.estimated_hours_month ? " cc-err" : "")}>{a.actual_hours_month != null ? a.actual_hours_month + " h" : ""}</td>{nums(a)}
                <td className="cc-list-menu"><RowMenu label={"Actions for " + name(a.bot_path)} items={agentMenu(a)} /></td></tr>)}
                {!agents.length && <tr className="is-static"><td colSpan={13} className="cc-none">No successful runs in the period.</td></tr>}</tbody>
            </table> : tab === "queues" ? <table className="cc-list-table" aria-label="Value by work queue">
              <thead><tr><th scope="col">Work queue</th><th scope="col" className="cc-num" title="Items done by successful runs">Items done</th>
                <th scope="col" className="cc-num">Minutes an item</th><th scope="col" className="cc-num">Cost of an hour</th>{numHeads}
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>{(v.queues ?? []).map((q) => <tr key={q.queue_id} className={canAdmin ? undefined : "is-static"} title={canAdmin ? "Change its savings" : undefined} onClick={rowClick(() => { if (canAdmin) setEditingQueue(q); })}>
                <td>{q.name}</td><td className="cc-num">{q.runs}</td><td className="cc-num">{q.minutes_each ?? "its agent's"}</td>
                <td className="cc-num">{q.hourly_cost != null ? money(q.hourly_cost) : "default"}</td>{nums(q)}
                <td className="cc-list-menu"><RowMenu label={"Actions for " + q.name} items={[...(canAdmin ? [{ key: "edit", label: "Change savings…", onSelect: () => setEditingQueue(q) }] : []),
                  { key: "open", label: "Open the queue", onSelect: () => { location.hash = "#/queues?queue=" + encodeURIComponent(q.queue_id); } }]} /></td></tr>)}</tbody>
            </table> : <table className="cc-list-table" aria-label="Value by folder">
              <thead><tr><th scope="col">Folder</th><th scope="col" className="cc-num">Runs</th>{numHeads}</tr></thead>
              <tbody>{v.folders.map((f) => <tr key={f.folder} title="Show this folder's agents" onClick={rowClick(() => { setFolder(f.folder); setTab("agents"); })}>
                <td>{f.folder}</td><td className="cc-num">{f.runs}</td>{nums(f)}</tr>)}
                {!v.folders.length && <tr className="is-static"><td colSpan={6} className="cc-none">No successful runs in the period.</td></tr>}</tbody>
            </table>}
          </div>
          <p className="cc-list-hint cc-qi-foot">Hours saved: each successful run × its agent's minutes saved. Money: hours × {v.hourly_cost ? money(v.hourly_cost) : "the cost of an hour"}. Per hour run: hours saved for each hour the agent ran.</p>
        </div>
      </>}
      {editing && <AgentSavings api={api} a={editing} costLabel={money} onClose={() => setEditing(null)} onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
      {editingQueue && <QueueSavings api={api} q={editingQueue} onClose={() => setEditingQueue(null)} onSaved={(t) => { onNotice(t); setEditingQueue(null); load(); }} />}
      {goal && v?.goal && <GoalDialog api={api} goal={v.goal} currency={v.currency} onClose={() => setGoal(false)} onSaved={(t) => { onNotice(t); setGoal(false); load(); }} />}
    </section>
  );
}

/** This year's goal: saved so far against it, and where this pace ends by 31 December. */
function Goal({ v, money, canAdmin, onSet }: { v: ValueReport; money: (n: number) => string; canAdmin: boolean; onSet: () => void }) {
  const g = v.goal!;
  const set = g.hours > 0 || g.money > 0;
  const byMoney = g.money > 0 && !!v.hourly_cost;
  const done = byMoney ? g.year_money : g.year_hours, target = byMoney ? g.money : g.hours, pace = byMoney ? g.pace_money : g.pace_hours;
  const fmt = (n: number) => byMoney ? money(n) : n.toLocaleString() + " h";
  const share = target > 0 ? Math.min(100, Math.round(100 * done / target)) : 0;
  if (!set) return <div className="cc-card cc-card-flat cc-goal"><h2>This year's goal</h2>
    <p>No goal set. Saved so far this year: {g.year_hours.toLocaleString()} h{v.hourly_cost ? ", " + money(g.year_money) : ""}.{canAdmin ? " " : ""}
      {canAdmin && <button type="button" className="cc-link" onClick={onSet}>Set the goal…</button>}</p></div>;
  return <div className="cc-card cc-card-flat cc-goal"><h2>This year's goal: {fmt(target)}</h2>
    <span className="cc-goal-bar" role="progressbar" aria-label="Of this year's goal" aria-valuemin={0} aria-valuemax={100} aria-valuenow={share}><span style={{ width: share + "%" }} /></span>
    <p>{fmt(done)} saved so far ({share}%) · at this pace {fmt(pace)} by 31 December{pace < target ? ", short of the goal" : ", the goal is met"}.
      {canAdmin && <> <button type="button" className="cc-link" onClick={onSet}>Change the goal…</button></>}</p></div>;
}

/** An agent's savings: minutes a run, its own cost of an hour, and the runs a month expected; empty uses the default. */
function AgentSavings({ api, a, costLabel, onClose, onSaved }: { api: CrApi; a: Agent; costLabel: (n: number) => string; onClose: () => void; onSaved: (t: string) => void }) {
  const [minutes, setMinutes] = useState(a.own_value ? String(a.minutes_each) : "");
  const [cost, setCost] = useState(a.own_cost && a.hourly_cost != null ? String(a.hourly_cost) : "");
  const [runs, setRuns] = useState(a.estimated_runs_month != null ? String(a.estimated_runs_month) : "");
  const [error, setError] = useState<string | null>(null);
  const bad = [minutes, cost, runs].some((t) => t.trim() !== "" && !(Number(t) >= 0));
  const save = () => api.setAgentSavings(a.bot_path, { minutes_saved: numOrNull(minutes), hourly_cost: numOrNull(cost), estimated_runs_month: numOrNull(runs) })
    .then(() => onSaved("Saved the savings of " + name(a.bot_path) + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={"Savings of " + name(a.bot_path)} width={480} onClose={onClose} footer={<>
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={bad} onClick={save}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Minutes of manual work one successful run saves" hint={a.own_value ? "Empty: the default" : "Empty: the default (" + a.minutes_each + ")"}>{(id) =>
        <TextInput id={id} type="number" min={0} max={100000} value={minutes} onChange={(e) => setMinutes(e.target.value)} />}</Field>
      <Field label="Cost of an hour of that work" hint={"Empty: the default" + (!a.own_cost && a.hourly_cost ? " (" + costLabel(a.hourly_cost) + ")" : "")}>{(id) =>
        <TextInput id={id} type="number" min={0} max={100000} value={cost} onChange={(e) => setCost(e.target.value)} />}</Field>
      <Field label="Runs a month expected" hint="When the agent was approved; for estimated against actual. Empty: none">{(id) =>
        <TextInput id={id} type="number" min={0} value={runs} onChange={(e) => setRuns(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** A work queue's savings: each item done counts these instead of its agent's; empty uses its agent's. */
function QueueSavings({ api, q, onClose, onSaved }: { api: CrApi; q: Queue; onClose: () => void; onSaved: (t: string) => void }) {
  const [minutes, setMinutes] = useState(q.minutes_each != null ? String(q.minutes_each) : "");
  const [cost, setCost] = useState(q.hourly_cost != null ? String(q.hourly_cost) : "");
  const [error, setError] = useState<string | null>(null);
  const bad = [minutes, cost].some((t) => t.trim() !== "" && !(Number(t) >= 0));
  const save = () => api.setQueueSavings(q.queue_id, { minutes_saved: numOrNull(minutes), hourly_cost: numOrNull(cost) })
    .then(() => onSaved("Saved the savings of the work queue " + q.name + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={"Savings of the work queue " + q.name} width={480} onClose={onClose} footer={<>
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={bad} onClick={save}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Minutes of manual work one item saves" hint="Empty: its agent's minutes a run count">{(id) => <TextInput id={id} type="number" min={0} max={100000} value={minutes} onChange={(e) => setMinutes(e.target.value)} />}</Field>
      <Field label="Cost of an hour of that work" hint="Empty: the default">{(id) => <TextInput id={id} type="number" min={0} max={100000} value={cost} onChange={(e) => setCost(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** This year's goal, in hours or money (or both); empty: none. */
function GoalDialog({ api, goal, currency, onClose, onSaved }: { api: CrApi; goal: NonNullable<ValueReport["goal"]>; currency: string; onClose: () => void; onSaved: (t: string) => void }) {
  const [hours, setHours] = useState(goal.hours ? String(goal.hours) : "");
  const [money, setMoney] = useState(goal.money ? String(goal.money) : "");
  const [error, setError] = useState<string | null>(null);
  const bad = [hours, money].some((t) => t.trim() !== "" && !(Number(t) >= 0));
  const save = () => api.setSavingsGoal(numOrNull(hours), numOrNull(money)).then(() => onSaved("Saved this year's goal."), (e) => setError(message(e)));
  return (
    <Dialog open title="This year's goal" width={440} onClose={onClose} footer={<>
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={bad} onClick={save}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Hours of manual work to save this year" hint="Empty: none">{(id) => <TextInput id={id} type="number" min={0} value={hours} onChange={(e) => setHours(e.target.value)} />}</Field>
      <Field label={"Money to save this year" + (currency ? " (" + currency + ")" : "")} hint="Empty: none. Shown when the cost of an hour is set">{(id) => <TextInput id={id} type="number" min={0} value={money} onChange={(e) => setMoney(e.target.value)} />}</Field>
    </Dialog>
  );
}
