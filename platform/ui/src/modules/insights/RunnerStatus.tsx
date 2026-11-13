import { useCallback, useEffect, useState } from "react";
import type { CrApi, Runner } from "../../api/cr";
import { HelpButton, TextInput, type HelpTopic, PageError } from "../../components";
import { RefreshButton } from "../../console/ListParts";
import { time, trouble } from "./Overview";

/**
 * Operational Insights > Runner status (item 11, docs/research/input-help-check.md): every runner on its own page, not
 * squeezed into a side panel of Overview. Its state, what it is running, why it needs attention, its load and version;
 * Runner Machines is where runners are managed.
 */
const HELP: HelpTopic = { title: "Runner status", about: "How every runner is doing now: online or not, what it runs, and what needs a look.",
  items: [
    { name: "Status", text: "Online, Offline, Out of service (taken out for maintenance) or Switched off." },
    { name: "Needs attention", text: "Why a runner needs a look: offline, out of service, over a limit or its OneRunner out of date." },
    { name: "Only those needing attention", text: "Shows just the runners with something to look at." },
    { name: "Runner Machines", text: "Where runners are added, changed, switched off and updated." },
  ] };

const STATE = (d: Runner) => d.status === "disabled" ? ["Switched off", "disabled"] : d.maintenance ? ["Out of service", "pending"]
  : d.status === "online" ? ["Online", "active"] : ["Offline", "failed"];

export function RunnerStatus({ api }: { api: CrApi }) {
  const [runners, setRunners] = useState<Runner[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [onlyTrouble, setOnlyTrouble] = useState(false);
  const load = useCallback(() => api.devices().then((r) => { setRunners(r); setError(null); }, (e) => setError(e instanceof Error ? e.message : String(e))), [api]);
  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, [load]);
  const f = q.trim().toLowerCase();
  const all = runners ?? [];
  const shown = all.filter((d) => (!f || (d.nickname + " " + d.name + " " + (d.hostname ?? "")).toLowerCase().includes(f)) && (!onlyTrouble || trouble(d)));
  const online = all.filter((d) => d.status === "online").length, attention = all.filter((d) => trouble(d)).length;
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-rs-title">
      <header className="cc-list-head">
        <h1 id="cc-rs-title">Runner status</h1>
        <HelpButton topic={HELP} />
        {runners && <span className="cc-list-count">{online} of {all.filter((d) => d.status !== "disabled").length} online · {attention} need{attention === 1 ? "s" : ""} attention</span>}
        <span className="ov-fill" />
        <a className="ui-btn ui-btn-secondary" href="#/devices">Runner Machines</a>
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search runners" placeholder="Search runners" value={q} onChange={(e) => setQ(e.target.value)} /></span>
          <label className="st-check-line"><input type="checkbox" checked={onlyTrouble} onChange={(e) => setOnlyTrouble(e.target.checked)} /> Only those needing attention</label>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {!runners && !error && <p className="cc-none" role="status">Loading…</p>}
      {runners && <div className="cc-list-panel"><div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Runners">
          <thead><tr><th>Runner</th><th>Status</th><th>Needs attention</th><th className="n">Running</th><th className="n">CPU</th><th className="n">Memory</th><th>OneRunner</th><th>Last seen</th></tr></thead>
          <tbody>{shown.map((d) => { const [label, pill] = STATE(d), why = trouble(d);
            return <tr key={d.id}>
              <td><a href="#/devices" title={d.hostname ?? undefined}>{d.nickname || d.name}</a></td>
              <td><span className={"cc-pill is-" + pill}><span className="cc-pill-dot" />{label}</span></td>
              <td className={why ? "ov-bad" : undefined}>{why ?? "–"}</td>
              <td className="n">{d.running ?? 0}</td>
              <td className="n">{d.cpu_pct != null ? Math.round(d.cpu_pct) + "%" : ""}</td>
              <td className="n">{d.mem_pct != null ? Math.round(d.mem_pct) + "%" : ""}</td>
              <td>{d.agent_version ?? ""}{d.up_to_date === false ? " (out of date)" : ""}</td>
              <td>{time(d.last_seen)}</td>
            </tr>; })}</tbody>
        </table>
        {!shown.length && <p className="cc-none">{all.length ? "No runner matches." : "No runners yet."}</p>}
      </div></div>}
    </section>
  );
}
