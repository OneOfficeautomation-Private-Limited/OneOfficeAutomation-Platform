import { useCallback, useEffect, useState } from "react";
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
const PERIODS: [string, string][] = [["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const folderOf = (path: string) => path.split("/").slice(0, -1).join("/");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
type Agent = ValueReport["agents"][number];
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
    { name: "Change minutes saved…", text: "Click an agent, or use its ⋯ menu, to set its own minutes saved, or Use the default again. Needs permission to "
      + "put versions in production." },
    { name: "Its runs in Agent Activity", text: "Lists the agent's successful runs, the ones counted here." },
  ] };

export function Value({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [days, setDays] = usePersistent("value.days", "30");
  const [tab, setTab] = useState<"agents" | "folders">("agents");
  const [folder, setFolder] = useState("");   // By agent, only this top folder's agents (a folder row's choice)
  const [v, setV] = useState<ValueReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Agent | null>(null);
  const can = (p: string) => user.permissions.includes(p);
  const canChange = can("repo.production");

  const load = useCallback(() => api.value({ days, tz: Intl.DateTimeFormat().resolvedOptions().timeZone }).then((r) => { setV(r); setError(null); }, (e) => setError(message(e))), [api, days]);
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
    ...(canChange ? [{ key: "minutes", label: "Change minutes saved…", onSelect: () => setEditing(a) }] : []),
    { key: "runs", label: "Its runs in Agent Activity", onSelect: () => { location.hash = "#/activity?" + new URLSearchParams({ tab: "completed", status: "success", bot: a.bot_path }).toString(); } },
    ...(can("repo.view") ? [{ key: "open", label: "Open in Automation", onSelect: () => { location.hash = "#/automation?q=" + encodeURIComponent(name(a.bot_path)); } }] : []),
  ];
  const nums = (r: ValueRow) => <><td className="cc-num">{r.hours} h</td><td className="cc-num">{money(r.money)}</td><td className="cc-num">{r.run_hours} h</td><td className="cc-num">{perRunHour(r)}</td></>;
  const numHeads = [["Hours saved", "Each successful run × its agent's minutes saved"], ["Money saved", "Hours saved × the cost of an hour (Settings)"],
    ["Run time", "Hours the agents ran"], ["Per hour run", "Hours saved for each hour the agents ran"]].map(([h, tip]) => <th key={h} scope="col" className="cc-num" title={tip}>{h}</th>);

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-val-title">
      <header className="cc-list-head">
        <h1 id="cc-val-title">Value</h1>
        <HelpButton topic={HELP} />
      </header>
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
        <div className="cc-card cc-card-flat cc-val-chart"><h2>Hours saved {series.length && v.by_day.length > 45 ? "per week" : "per day"}</h2>
          {series.length ? <ul className="cc-hbars" aria-label="Hours saved over time">{series.map((x) => <li key={x.label}><span className="cc-hbar-label">{x.label}</span>
            <span className="cc-hbar-track"><span className="cc-hbar-fill is-done" style={{ width: (100 * x.hours / max) + "%" }} /></span><span className="cc-hbar-value">{x.hours} h</span></li>)}</ul>
            : <p className="st-muted">No successful runs in the period.</p>}
        </div>
        <div className="cc-list-panel cc-ops-lists">
          <div className="ov-tabs" role="tablist" aria-label="Value by">
            <button type="button" role="tab" className="ov-tab" aria-selected={tab === "agents"} onClick={() => setTab("agents")}>By agent<span className="ov-cnt">{v.agents.length}</span></button>
            <button type="button" role="tab" className="ov-tab" aria-selected={tab === "folders"} onClick={() => setTab("folders")}>By folder<span className="ov-cnt">{v.folders.length}</span></button>
            {tab === "agents" && folder && <span className="cc-val-only">Only {folder} <button type="button" className="cc-link" onClick={() => setFolder("")}>Show every folder</button></span>}
          </div>
          <div className="cc-list-scroll">
            {tab === "agents" ? <table className="cc-list-table" aria-label="Value by agent">
              <thead><tr><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col" className="cc-num" title="Successful runs counted">Runs</th><th scope="col" className="cc-num" title="Minutes of manual work one successful run saves">Minutes a run</th>{numHeads}
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>{agents.map((a) => <tr key={a.bot_path} className={canChange ? undefined : "is-static"} title={canChange ? "Change minutes saved a run" : undefined} onClick={rowClick(() => { if (canChange) setEditing(a); })}>
                <td><span className="cc-aico" aria-hidden="true">A</span>{name(a.bot_path)}</td><td>{folderOf(a.bot_path)}</td><td className="cc-num">{a.runs}</td>
                <td className="cc-num">{a.minutes_each}{a.own_value ? "" : " (default)"}</td>{nums(a)}
                <td className="cc-list-menu"><RowMenu label={"Actions for " + name(a.bot_path)} items={agentMenu(a)} /></td></tr>)}
                {!agents.length && <tr className="is-static"><td colSpan={10} className="cc-none">No successful runs in the period.</td></tr>}</tbody>
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
      {editing && <MinutesSaved api={api} a={editing} onClose={() => setEditing(null)} onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
    </section>
  );
}

/** An agent's minutes saved a run: its own value, or the default. */
function MinutesSaved({ api, a, onClose, onSaved }: { api: CrApi; a: Agent; onClose: () => void; onSaved: (t: string) => void }) {
  const [minutes, setMinutes] = useState(String(a.minutes_each));
  const [error, setError] = useState<string | null>(null);
  const save = (m: number | null) => api.setMinutesSaved(a.bot_path, m).then(() => onSaved(name(a.bot_path) + ": " + (m == null ? "uses the default minutes saved." : m + " minutes saved a run.")), (e) => setError(message(e)));
  return (
    <Dialog open title={"Minutes saved a run by " + name(a.bot_path)} width={440} onClose={onClose} footer={<>
      {a.own_value && <Button kind="secondary" onClick={() => save(null)}>Use the default</Button>}
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={minutes.trim() === "" || Number(minutes) < 0} onClick={() => save(Number(minutes))}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Minutes of manual work one successful run saves">{(id) => <TextInput id={id} type="number" min={0} max={100000} value={minutes} onChange={(e) => setMinutes(e.target.value)} />}</Field>
      <p className="cc-list-hint">{a.own_value ? "Its own value." : "It uses the default now."} Hours saved count from each successful run in the period.</p>
    </Dialog>
  );
}
