import { useCallback, useEffect, useState } from "react";
import type { CrApi, CrUser, ValueReport, ValueRow } from "../api/cr";
import { Button, Select, TextInput, usePersistent } from "../components";

/**
 * Operational Insights > Value, as AA's Center of Excellence dashboard and UiPath Insights' Business ROI (checked
 * 31 Oct 2026): the manual work the agents' successful runs saved over a period, in hours and money (the hourly cost and
 * currency in Settings), compared with the period before; per day, per top folder and per agent, with each agent's
 * minutes saved per run (its own, or the default) changed in place by people who may put versions in production.
 * A run counts once, so items of a work queue are not counted twice.
 */
const PERIODS: [string, string][] = [["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
function change(now: number, before: number) {
  if (before === 0) return now === 0 ? "" : "none before";
  const d = Math.round(100 * (now - before) / before);
  return d === 0 ? "same as before" : (d > 0 ? "+" : "") + d + "% on before";
}

export function Value({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [days, setDays] = usePersistent("value.days", "30");
  const [v, setV] = useState<ValueReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ path: string; minutes: string } | null>(null);
  const can = (p: string) => user.permissions.includes(p);

  const load = useCallback(() => api.value({ days, tz: Intl.DateTimeFormat().resolvedOptions().timeZone }).then((r) => { setV(r); setError(null); }, (e) => setError(message(e))), [api, days]);
  useEffect(() => { load(); }, [load]);

  const money = (n: number) => {
    if (!v?.hourly_cost) return "–";
    try { return v.currency ? new Intl.NumberFormat(undefined, { style: "currency", currency: v.currency, maximumFractionDigits: 0 }).format(n) : n.toLocaleString(); }
    catch { return n.toLocaleString() + " " + v.currency; }
  };
  const save = (path: string, minutes: number | null) => api.setMinutesSaved(path, minutes).then(() => {
    onNotice(name(path) + ": " + (minutes == null ? "uses the default minutes saved." : minutes + " minutes saved a run.")); setEditing(null); load();
  }, (e) => setError(message(e)));
  const label = PERIODS.find(([d]) => d === days)?.[1].toLowerCase() ?? "";
  const unvalued = v ? v.agents.filter((a) => !a.own_value).length : 0;
  // a long period shows weeks (Monday first) rather than days
  const series = !v ? [] : v.by_day.length <= 45 ? v.by_day.map((d) => ({ label: d.day.slice(5), hours: d.hours })) : Object.entries(v.by_day.reduce<Record<string, number>>((acc, d) => {
    const t = new Date(d.day + "T00:00:00"); t.setDate(t.getDate() - ((t.getDay() + 6) % 7));
    const k = t.toISOString().slice(0, 10); acc[k] = (acc[k] ?? 0) + d.hours; return acc; }, {})).map(([k, h]) => ({ label: "Week of " + k.slice(5), hours: Math.round(h * 10) / 10 }));
  const max = Math.max(0.1, ...series.map((x) => x.hours));
  const perRunHour = (r: ValueRow) => r.run_hours > 0 ? Math.round(10 * r.hours / r.run_hours) / 10 + "×" : "–";

  return (
    <section className="st-page cc-home" aria-labelledby="cc-val-title">
      <header className="st-page-head">
        <div><h1 id="cc-val-title">Value</h1><p>The manual work your agents' successful runs saved, in hours and money, per agent and folder.</p></div>
        <Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([d, l]) => ({ value: d, label: l }))} />
      </header>
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
        <div className="cc-two cc-pad">
          <div className="cc-card cc-card-flat"><h2>Hours saved {series.length && v.by_day.length > 45 ? "per week" : "per day"}</h2>
            {series.length ? <ul className="cc-hbars" aria-label="Hours saved over time">{series.map((x) => <li key={x.label}><span className="cc-hbar-label">{x.label}</span>
              <span className="cc-hbar-track"><span className="cc-hbar-fill is-done" style={{ width: (100 * x.hours / max) + "%" }} /></span><span className="cc-hbar-value">{x.hours} h</span></li>)}</ul>
              : <p className="st-muted">No successful runs in the period.</p>}
          </div>
          <div className="cc-card cc-card-flat"><h2>By folder</h2>
            {v.folders.length ? <table className="st-table" aria-label="Value by folder">
              <thead><tr>{["Folder", "Runs", "Hours saved", "Money saved", "Run time", "Saved per hour run"].map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
              <tbody>{v.folders.map((f) => <tr key={f.folder}><td>{f.folder}</td><td>{f.runs}</td><td>{f.hours} h</td><td>{money(f.money)}</td><td>{f.run_hours} h</td><td>{perRunHour(f)}</td></tr>)}</tbody>
            </table> : <p className="st-muted">No successful runs in the period.</p>}
          </div>
        </div>
        <div className="cc-card cc-card-flat cc-pad-x">
          <h2>By agent</h2>
          {v.agents.length ? <div className="cc-scroll"><table className="st-table" aria-label="Value by agent">
            <thead><tr>{["Agent", "Successful runs", "Minutes saved a run", "Hours saved", "Money saved", "Run time", "Saved per hour run"].map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
            <tbody>{v.agents.map((a) => <tr key={a.bot_path}>
              <td title={a.bot_path}>{name(a.bot_path)}</td><td>{a.runs}</td>
              <td>{editing?.path === a.bot_path
                ? <span className="cc-inline"><TextInput type="number" min={0} max={100000} aria-label={"Minutes saved a run by " + name(a.bot_path)} value={editing.minutes}
                    onChange={(e) => setEditing({ ...editing, minutes: e.target.value })} />
                  <Button kind="primary" disabled={editing.minutes.trim() === ""} onClick={() => save(a.bot_path, Number(editing.minutes))}>Save</Button>
                  {a.own_value && <Button kind="plain" onClick={() => save(a.bot_path, null)}>Use the default</Button>}
                  <Button kind="plain" onClick={() => setEditing(null)}>Cancel</Button></span>
                : <>{a.minutes_each}{a.own_value ? "" : " (default)"}{can("repo.production") && <> <button type="button" className="cc-link" aria-label={"Change minutes saved by " + name(a.bot_path)}
                    onClick={() => setEditing({ path: a.bot_path, minutes: String(a.minutes_each) })}>Change</button></>}</>}</td>
              <td>{a.hours} h</td><td>{money(a.money)}</td><td>{a.run_hours} h</td><td>{perRunHour(a)}</td>
            </tr>)}</tbody>
          </table></div> : <p className="st-muted">No successful runs in the period.</p>}
          <p className="cc-card-foot st-muted">Hours saved: each successful run × its agent's minutes saved. Money: hours × {v.hourly_cost ? money(v.hourly_cost) : "the cost of an hour"}. Saved per hour run: hours saved for each hour the agent ran.</p>
        </div>
      </>}
    </section>
  );
}
