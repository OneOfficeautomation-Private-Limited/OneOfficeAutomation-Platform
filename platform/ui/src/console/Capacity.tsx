import { useCallback, useEffect, useState } from "react";
import type { Capacity as CapacityData, CrApi, CrUser, Runner, RunnerPool } from "../api/cr";
import { Select, usePersistent } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";

/**
 * Operational Insights > Runner capacity, as Blue Prism Hub's utilisation heatmap and FTE comparison and AA's Devices
 * dashboard (checked 31 Oct 2026): how much of the runners' time agents used over a period, how long runs waited for a
 * runner, when runners are busy (weekday by hour, in the viewer's time zone), busy hours per day, each runner's share
 * and current CPU and memory, and plain advice when runners are short or idle.
 * Review round 2 (1 Nov 2026): the period as a filter with the refresh icon, busy time under an hour in minutes, and each
 * runner on one line with its state as a pill; a row opens the runner on Runner Machines, with a menu per runner.
 */
const PERIODS: [string, string][] = [["1", "Last 24 hours"], ["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"]];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
/** Busy time: minutes under an hour (so a short day never reads "0 h"), then hours. */
const hours = (ms: number) => { const h = ms / 3_600_000; return ms > 0 && h < 1 ? Math.max(1, Math.round(ms / 60_000)) + " min" : h < 10 ? (Math.round(h * 10) / 10) + " h" : Math.round(h) + " h"; };
function dur(ms?: number | null) {
  if (ms == null) return "–";
  const s = Math.round(ms / 1000);
  return s < 60 ? s + " s" : s < 3600 ? Math.floor(s / 60) + " min " + (s % 60) + " s" : Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min";
}
const stateOf = (d?: Runner) => !d ? "Removed" : d.status === "disabled" ? "Off" : d.maintenance ? "Out of service" : d.status === "offline" ? "Offline" : d.over_limit ? "Busy (limit)" : "Online";
const pillOf = (d?: Runner) => !d || d.status === "disabled" ? "disabled" : d.status === "online" && !d.over_limit && !d.maintenance ? "active" : "failed";

/** Plain advice from the figures: short of runners, or plenty of room. */
function advice(c: CapacityData): string | null {
  const wait = c.p95_wait_ms ?? 0;
  if ((c.use ?? 0) >= 80 || wait >= 10 * 60_000) return "Runners are busy most of the time" + (wait >= 10 * 60_000 ? " and runs wait " + dur(wait) + " or more to start" : "")
    + ": add a runner or a runner to a pool, or move schedules into quieter hours (lighter on the heatmap).";
  if (c.enabled_runners > 1 && (c.use ?? 0) < 10) return "Runners are idle most of the time: schedules could run more often, or a runner could be freed for other work.";
  return null;
}

export function Capacity({ api, user }: { api: CrApi; user: CrUser }) {
  const [days, setDays] = usePersistent("capacity.days", "7");
  const [c, setC] = useState<CapacityData | null>(null);
  const [devices, setDevices] = useState<Runner[]>([]);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const can = (p: string) => user.permissions.includes(p);

  const load = useCallback(() => {
    api.capacity({ days, tz: Intl.DateTimeFormat().resolvedOptions().timeZone }).then((r) => { setC(r); setError(null); }, (e) => setError(message(e)));
    if (can("devices.view")) { api.devices().then(setDevices, () => {}); api.pools().then(setPools, () => {}); }
  }, [api, days]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); const t = setInterval(load, 60000); return () => clearInterval(t); }, [load]);

  const label = PERIODS.find(([v]) => v === days)?.[1].toLowerCase() ?? "";
  const device = (id: string) => devices.find((d) => d.id === id);
  const online = devices.filter((d) => d.status === "online").length;
  const tip = c ? advice(c) : null;
  const maxDay = Math.max(1, ...(c?.by_day ?? []).map((x) => x.busy_ms));

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-cap-title">
      <header className="cc-list-head">
        <h1 id="cc-cap-title">Runner capacity</h1>
        <span className="ov-info" role="img" aria-label="About this page" title="How much of your runners' time agents use, how long runs wait for a runner, and when runners are busy or free.">i</span>
      </header>
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-chip"><Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!c && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {c && <>
        <div className="cc-sums" role="group" aria-label="Figures">
          <a className="cc-sum" href="#/devices"><strong>{devices.length ? online + " of " + devices.filter((d) => d.status !== "disabled").length : c.enabled_runners}</strong>
            <span>{devices.length ? "Runners online" : "Runners switched on"}</span></a>
          <div className="cc-sum is-static"><strong className={(c.use ?? 0) >= 80 ? "cc-err" : undefined}>{c.use == null ? "–" : c.use + "%"}</strong><span>Of runner time in use · {label}</span></div>
          <div className="cc-sum is-static"><strong>{hours(c.busy_ms)}</strong><span>Busy · as {c.full_time_people} full-time {c.full_time_people === 1 ? "person" : "people"} (40 h a week)</span></div>
          <a className="cc-sum" href="#/activity?tab=progress&status=queued"><strong className={c.waiting_now ? "cc-err" : undefined}>{c.waiting_now}</strong><span>Runs waiting for a runner now</span></a>
          <div className="cc-sum is-static"><strong>{dur(c.avg_wait_ms)}</strong><span>Average wait to start · 95% within {dur(c.p95_wait_ms)}</span></div>
        </div>
        {tip && <p className="cc-advice cc-pad-x" role="note">{tip}</p>}
        <div className="cc-list-panel cc-cap-runners">
          <h2 className="cc-cap-title">Each runner</h2>
          <div className="cc-list-scroll"><table className="cc-list-table" aria-label="Each runner">
            <thead><tr>{["Runner", "State", "Pools"].map((h) => <th key={h} scope="col">{h}</th>)}{["Runs", "Busy"].map((h) => <th key={h} scope="col" className="cc-num">{h}</th>)}
              <th scope="col">Time in use</th>{["Average wait", "CPU now", "Memory now"].map((h) => <th key={h} scope="col" className="cc-num">{h}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>{c.runners.map((r) => { const d = device(r.device_id); const nm = d ? d.nickname || d.name : "Removed runner"; const inPools = pools.filter((p) => p.devices.includes(r.device_id)).map((p) => p.name).join(", ");
              const open = () => { location.hash = "#/devices?runner=" + encodeURIComponent(r.device_id); };
              return <tr key={r.device_id} className={d ? undefined : "is-static"} title={d ? "Open the runner on Runner Machines" : undefined} onClick={rowClick(() => { if (d) open(); })}>
              <td title={d ? undefined : r.device_id}>{nm}</td>
              <td><span className={"cc-pill is-" + pillOf(d)}><span className="cc-pill-dot" />{stateOf(d)}</span></td>
              <td className="cc-clip" title={inPools || undefined}>{inPools}</td>
              <td className="cc-num">{r.runs}</td><td className="cc-num">{hours(r.busy_ms)}</td>
              <td className={r.use >= 80 ? "cc-err" : undefined}><span className="cc-meter"><progress max={100} value={Math.min(100, r.use)} aria-label={"Time in use of " + nm} /> {r.use}%</span></td>
              <td className="cc-num">{dur(r.avg_wait_ms)}</td>
              <td className="cc-num">{d?.cpu_pct != null ? Math.round(d.cpu_pct) + "%" : ""}</td><td className="cc-num">{d?.mem_pct != null ? Math.round(d.mem_pct) + "%" : ""}</td>
              <td className="cc-list-menu"><RowMenu label={"Actions for " + nm} items={[...(d ? [{ key: "open", label: "Open the runner", onSelect: open }] : []),
                { key: "runs", label: "Its runs in Agent Activity", onSelect: () => { location.hash = "#/activity?" + new URLSearchParams({ tab: "completed", device: r.device_id }).toString(); } }]} /></td>
            </tr>; })}
              {!c.runners.length && <tr className="is-static"><td colSpan={10} className="cc-none">No runners yet.</td></tr>}</tbody>
          </table></div>
        </div>
        <div className="cc-two cc-pad">
          <div className="cc-card cc-card-flat"><h2>When runners are busy</h2><UseHeatmap h={c.heat} /></div>
          <div className="cc-card cc-card-flat"><h2>Busy hours per day</h2>
            {c.by_day.length ? <ul className="cc-hbars" aria-label="Busy hours per day">{c.by_day.slice(-31).map((x) => <li key={x.day}>
              <span className="cc-hbar-label">{x.day.slice(5)}</span>
              <span className="cc-hbar-track"><span className="cc-hbar-fill" style={{ width: (100 * x.busy_ms / maxDay) + "%" }} /></span>
              <span className="cc-hbar-value">{hours(x.busy_ms)}</span></li>)}</ul> : <p className="st-muted">No runs in the period.</p>}
          </div>
        </div>
      </>}
    </section>
  );
}

/** The share of the switched-on runners' time in use, per weekday and hour; empty where the hour is not in the period. */
function UseHeatmap({ h }: { h: CapacityData["heat"] }) {
  const level = (p: number | null) => p == null || p === 0 ? 0 : p < 10 ? 1 : p < 35 ? 2 : p < 70 ? 3 : 4;
  if (!h.use.flat().some((p) => p)) return <p className="st-muted">No runner was busy in the period.</p>;
  return <div className="cc-scroll">
    <table className="cc-heat" aria-label={"Runner time in use, by weekday and hour (" + h.time_zone + ")"}>
      <thead><tr><th scope="col"><span className="st-sr">Day</span></th>{Array.from({ length: 24 }, (_, i) => <th key={i} scope="col">{i % 3 === 0 ? String(i).padStart(2, "0") : ""}</th>)}</tr></thead>
      <tbody>{h.use.map((row, d) => <tr key={d}><th scope="row">{DAYS[d]}</th>{row.map((p, hr) =>
        <td key={hr} className={"cc-heat-l" + level(p)} title={DAYS[d] + " " + String(hr).padStart(2, "0") + ":00 · " + (p == null ? "not in the period" : p + "% in use")}><span className="st-sr">{p ?? ""}</span></td>)}</tr>)}</tbody>
    </table>
    <p className="cc-card-foot st-muted">Darker: more of the runners' time in use (under 10%, 35%, 70%, or more). Hours in {h.time_zone}.</p>
  </div>;
}
