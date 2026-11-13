import { useEffect, useState } from "react";
import type { CrApi, CrUser, Dashboard } from "../api/cr";
import { HelpButton, IconTile, type HelpTopic, PageError } from "../components";
import { PageIntro } from "../shell/PageIntro";

const HELP: HelpTopic = { title: "Home", about: "How your agents are doing at a glance, and a way back to what you were working on. Only what your role may see is shown.",
  items: [
    { name: "Figures", text: "Running now, waiting, succeeded and failed in the last 24 hours, the success rate, runners online, agents, schedules, work items and hours saved. Click one to open the page behind it." },
    { name: "Runs in the last 7 days", text: "Each day's runs, by how they ended." },
    { name: "Recent failures", text: "The latest runs that failed, with why; click one to open it in Agent Activity." },
    { name: "Most used agents", text: "The agents run most in the last 7 days." },
    { name: "Runners", text: "Each runner and whether it is online now." },
    { name: "Pick up where you left off", text: "The agents you changed and the runs you started last." },
  ] };
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

  // 13 Nov 2026 round 2, item 1 (H1): two labelled strips of equal cells, no icons; red only when something is wrong
  type Figure = { n: string; label: string; href?: string; bad?: boolean };
  const runs: Figure[] = d ? [
    { n: String(d.running), label: "Running now", href: link("activity", "runs.view") },
    { n: String(d.queued), label: "Waiting to start", href: link("activity", "runs.view") },
    { n: String(d.success_24h), label: "Succeeded", href: link("activity", "runs.view") },
    { n: String(d.failed_24h), label: "Failed", href: link("activity", "runs.view"), bad: d.failed_24h > 0 },
    { n: d.success_rate_24h == null ? "—" : d.success_rate_24h + "%", label: "Success rate", bad: d.success_rate_24h != null && d.success_rate_24h < 80 },
    { n: d.avg_duration_ms_24h ? duration(d.avg_duration_ms_24h) : "—", label: "Average run time" },
  ] : [];
  const platform: Figure[] = d ? [
    { n: d.devices_online + " / " + d.devices_total, label: "Runners online", href: link("devices", "devices.view"), bad: d.devices_total > 0 && d.devices_online === 0 },
    { n: String(d.bots), label: "Agents (Public)", href: link("automation", "repo.view") },
    { n: String(d.schedules), label: "Active schedules", href: link("schedules", "schedules.view") },
    ...(d.queue_items_waiting !== undefined ? [{ n: String(d.queue_items_waiting), label: "Work items waiting", href: link("queues") }] : []),
    { n: (d.hours_saved_30d ?? 0) + " h", label: "Hours saved (30 days)" },
    { n: String(d.open_tasks ?? 0), label: "Tasks waiting for you", href: link("inbox") },
  ] : [];
  const strip = (id: string, title: string, figures: Figure[]) => (
    <div className="cc-strip-group">
      <h2 id={id} className="cc-strip-title">{title}</h2>
      <ul className="cc-strip" aria-labelledby={id} style={{ gridTemplateColumns: `repeat(${figures.length}, minmax(0, 1fr))` }}>
        {figures.map((f) => {
          const body = <><strong className={f.bad ? "is-bad" : undefined}>{f.n}</strong><span>{f.label}</span></>;
          return <li key={f.label}>{f.href ? <a className="cc-strip-cell is-link" href={f.href}>{body}</a> : <div className="cc-strip-cell">{body}</div>}</li>;
        })}
      </ul>
    </div>
  );
  const max = d?.days ? Math.max(1, ...d.days.map((x) => x.success + x.failed + x.stopped + x.active)) : 1;

  return (
    <section className="st-page cc-home" aria-labelledby="cc-home-title">
      <header className="st-page-head"><div><div className="st-head-row"><h1 id="cc-home-title">Home</h1><HelpButton topic={HELP} /><PageIntro k="page:home" /></div><p>Welcome, {user.display_name}. Here is what your agents have been doing.</p></div></header>
      {error && <PageError text={error} />}
      {!d && !error && <p className="st-muted" role="status">Loading…</p>}
      {d && <>
        <div className="cc-strips" role="group" aria-label="Figures">
          {strip("cc-home-runs", "Runs · last 24 hours", runs)}
          {strip("cc-home-platform", "Platform", platform)}
        </div>
        {((d.my_recent_agents?.length ?? 0) > 0 || (d.my_recent_runs?.length ?? 0) > 0) && (
          <div className="cc-card">
            <h2>Pick up where you left off</h2>
            <div className="cc-two">
              <div><h3>Your agents</h3>
                {d.my_recent_agents?.length ? <ul className="cc-list">{d.my_recent_agents.map((b) => (
                  <li key={b.path}><IconTile name="bolt" color="#2f6fde" size={16} /><strong>{agentName(b.path)}</strong>
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
