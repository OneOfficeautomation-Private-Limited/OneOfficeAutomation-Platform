import { useEffect, useState } from "react";
import type { CrApi, CrUser, Dashboard } from "../api/cr";
import { Icon, IconTile, type IconName } from "../components";

const duration = (ms: number) => ms < 1000 ? ms + " ms" : ms < 60_000 ? Math.round(ms / 100) / 10 + " s" : Math.round(ms / 6000) / 10 + " min";
const agentName = (path: string) => path.split("/").pop()!.replace(/\.flow\.json$/, "");
function ago(t?: string) {
  if (!t) return "";
  const s = (Date.now() - new Date(t).getTime()) / 1000;
  if (isNaN(s)) return "";
  return s < 90 ? "just now" : s < 5400 ? Math.round(s / 60) + " min ago" : s < 129_600 ? Math.round(s / 3600) + " h ago" : Math.round(s / 86_400) + " days ago";
}

/**
 * Home: what the agents have been doing (the same figures as today's console), the person's own recent agents and
 * runs, and runs per day for the last week. A figure that has a page links to it, if the person may open that page.
 */
export function Home({ api, user, pageHref }: { api: CrApi; user: CrUser; pageHref: (page: string) => string | undefined }) {
  const [d, setD] = useState<Dashboard | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let tz = "";
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || ""; } catch { /* the server counts days in UTC */ }
    api.dashboard(tz).then(setD, (e) => setError(e instanceof Error ? e.message : String(e)));
  }, [api]);
  const can = (p: string) => user.permissions.includes(p);
  const link = (page: string, perm?: string) => !perm || can(perm) ? pageHref(page) : undefined;

  const tiles: [string, string, IconName, string, string | undefined][] = d ? [
    [String(d.running), "Running now", "pulse", "#c2410c", link("activity", "runs.view")],
    [String(d.queued), "Waiting to start", "queue", "#b45309", link("activity", "runs.view")],
    [String(d.success_24h), "Succeeded (24 h)", "play", "#15803d", undefined],
    [String(d.failed_24h), "Failed (24 h)", "close", "#b91c1c", undefined],
    [d.success_rate_24h == null ? "—" : d.success_rate_24h + "%", "Success rate (24 h)", "pulse", "#047857", undefined],
    [d.avg_duration_ms_24h ? duration(d.avg_duration_ms_24h) : "—", "Average run time", "loop", "#0e7490", undefined],
    [d.devices_online + " / " + d.devices_total, "Runners online", "desk", "#0369a1", link("devices", "devices.view")],
    [String(d.bots), "Agents in the agent repository", "folder", "#6d28d9", link("automation", "repo.view")],
    [String(d.schedules), "Active schedules", "loop", "#7c3aed", link("schedules", "schedules.view")],
    ...(d.queue_items_waiting !== undefined ? [[String(d.queue_items_waiting), "Work items waiting", "queue", "#9333ea", link("queues")] as [string, string, IconName, string, string | undefined]] : []),
    [(d.hours_saved_30d ?? 0) + " h", "Hours saved (30 days)", "loop", "#15803d", undefined],
    [String(d.open_tasks ?? 0), "Tasks waiting for you", "form", "#0f766e", link("inbox")],
  ] : [];
  const max = d?.days ? Math.max(1, ...d.days.map((x) => x.success + x.failed + x.stopped + x.active)) : 1;

  return (
    <section className="st-page cc-home" aria-labelledby="cc-home-title">
      <header className="st-page-head"><div><h1 id="cc-home-title">Home</h1><p>Welcome, {user.display_name}. Here is what your agents have been doing.</p></div></header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!d && !error && <p className="st-muted" role="status">Loading…</p>}
      {d && <>
        <ul className="cc-tiles" aria-label="Figures">
          {tiles.map(([n, label, icon, color, href]) => {
            const body = <><IconTile name={icon} color={color} size={30} /><span className="cc-tile-text"><strong>{n}</strong><span>{label}</span></span></>;
            return <li key={label}>{href ? <a className="cc-tile is-link" href={href}>{body}</a> : <div className="cc-tile">{body}</div>}</li>;
          })}
        </ul>
        {((d.my_recent_agents?.length ?? 0) > 0 || (d.my_recent_runs?.length ?? 0) > 0) && (
          <div className="cc-card">
            <h2>Pick up where you left off</h2>
            <div className="cc-two">
              <div><h3>Your agents</h3>
                {d.my_recent_agents?.length ? <ul className="cc-list">{d.my_recent_agents.map((b) => (
                  <li key={b.path}><Icon name="folder" size={14} /><strong>{agentName(b.path)}</strong>
                    <span className="st-muted">v{b.latest_version}{b.locked_by ? " · checked out by " + b.locked_by : ""}</span><span className="st-muted cc-when">{ago(b.updated)}</span></li>))}</ul>
                  : <p className="st-muted">Agents you check in or check out show here.</p>}
              </div>
              <div><h3>Your runs</h3>
                {d.my_recent_runs?.length ? <ul className="cc-list">{d.my_recent_runs.map((r) => (
                  <li key={r.id}><span className={"cc-dot is-" + (r.status === "success" ? "ok" : r.status === "failed" ? "bad" : "busy")} aria-hidden="true" />
                    <strong>{agentName(r.bot_path)}</strong><span className="st-muted">{r.status}</span><span className="st-muted cc-when">{ago(r.finished || r.created)}</span></li>))}</ul>
                  : <p className="st-muted">Agents you run show here.</p>}
              </div>
            </div>
          </div>
        )}
        {d.days && (
          <div className="cc-card">
            <h2>Runs per day</h2>
            <div className="cc-chart" role="img" aria-label="Runs per day for the last 7 days">
              {d.days.map((x) => {
                const total = x.success + x.failed + x.stopped + x.active;
                return (
                  <div key={x.date} className="cc-chart-col" title={`${x.date}: ${x.success} succeeded, ${x.failed} failed, ${x.stopped} stopped${x.active ? ", " + x.active + " active" : ""}`}>
                    <div className="cc-chart-bar">
                      {(["success", "failed", "stopped", "active"] as const).map((k) => x[k] > 0 &&
                        <span key={k} className={"cc-seg is-" + k} style={{ height: (x[k] / max) * 100 + "%" }} />)}
                    </div>
                    <span className="cc-chart-n">{total}</span>
                    <span className="cc-chart-day">{x.date.slice(5)}</span>
                  </div>
                );
              })}
            </div>
            <p className="cc-legend"><span className="cc-seg is-success" /> succeeded <span className="cc-seg is-failed" /> failed <span className="cc-seg is-stopped" /> stopped <span className="cc-seg is-active" /> active</p>
          </div>
        )}
      </>}
    </section>
  );
}
