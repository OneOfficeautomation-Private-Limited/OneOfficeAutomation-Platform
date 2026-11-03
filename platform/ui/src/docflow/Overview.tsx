import { useState } from "react";
import type { DocFlowApi } from "./api";
import { Chip, FilterBar, FilterSelect, Kpi, Loading, SearchBox, matches, useLoad } from "./ui";

/** Overview: today's numbers across every process, the last 14 days, what needs attention and where pages were read. */
export function OverviewPage({ api }: { api: DocFlowApi }) {
  const [data] = useLoad(() => Promise.all([api.overview(), api.processes()]), [api]);
  const [q, setQ] = useState("");
  const [state, setState] = useState<"all" | "live" | "draft">("all");
  const [show, setShow] = useState<"all" | "waiting" | "low">("all");
  if (!data) return <Loading />;
  const [o, processes] = data;
  const max = Math.max(...o.days.map((d) => d.straight + d.reviewed), 1) * 1.15;
  const rows = processes.filter((p) => matches(q, p.name, p.folder) && (state === "all" || p.state === state)
    && (show === "all" || (show === "waiting" ? p.review > 0 : p.straightThrough < 85)));
  const colors = ["#2f6fde", "#8b6ae8", "#4b5565", "transparent"];

  return (
    <div className="df-page">
      <div className="df-page-head"><div><h2>Overview</h2><p>All processes for this tenant, today.</p></div></div>

      <div className="df-grid df-g6">
        <Kpi label="Documents today" value={o.today} />
        <Kpi label="Straight through" value={o.straightThrough + "%"} sub={`${Math.round(o.today * o.straightThrough / 100)} with no human touch`} tone="ok" />
        <Kpi label="Waiting for review" value={o.waiting} sub={o.overdue ? `${o.overdue} overdue` : "none overdue"} tone={o.overdue ? "bad" : undefined} />
        <Kpi label="Failed" value={o.failed} tone={o.failed ? "bad" : undefined} />
        <Kpi label="Pages read this month" value={o.pagesThisMonth.toLocaleString("en")} sub="all on this server" />
        <Kpi label="Cloud cost this month" value={"$" + o.cloudCost} sub="cloud opt-in is off" />
      </div>

      <div className="df-grid df-g3">
        <section className="df-card df-pad df-span2" aria-label="Documents per day">
          <div className="df-head-row">
            <b>Documents per day, last 14 days</b><span className="df-grow" />
            <span className="df-legend"><span className="df-swatch" style={{ background: "#2f6fde" }} />Straight through</span>
            <span className="df-legend"><span className="df-swatch" style={{ background: "#f5b33d" }} />Reviewed by a person</span>
          </div>
          <div className="df-bars" role="img" aria-label={`Documents per day: ${o.days.map((d) => `${d.label}: ${d.straight + d.reviewed}`).join(", ")}`}>
            {o.days.map((d) => (
              <div key={d.label} className="df-bar" title={`${d.straight + d.reviewed} documents, ${d.reviewed} reviewed`}>
                <div style={{ height: (d.reviewed / max * 100) + "%", background: "#f5b33d", borderRadius: "3px 3px 0 0" }} />
                <div style={{ height: (d.straight / max * 100) + "%", background: "#2f6fde" }} />
              </div>
            ))}
          </div>
          <div className="df-bar-labels" aria-hidden="true">{o.days.map((d) => <span key={d.label}>{d.label}</span>)}</div>
        </section>

        <section className="df-card df-pad" aria-label="Needs attention" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <b>Needs attention</b>
          {o.alerts.map((a) => (
            <a key={a.title} href={a.to} className="df-card" style={{ display: "flex", gap: 10, padding: 8, textDecoration: "none", color: "inherit" }}>
              <Chip tone={a.level === "bad" ? "bad" : a.level === "warn" ? "warn" : "info"}>{a.level === "bad" ? "Act now" : a.level === "warn" ? "Check" : "Note"}</Chip>
              <span><b>{a.title}</b><span className="df-small" style={{ display: "block" }}>{a.text}</span></span>
            </a>
          ))}
        </section>
      </div>

      <div className="df-grid df-g3">
        <section className="df-card df-span2" aria-label="Processes" style={{ overflow: "hidden" }}>
          <div className="df-head-row df-pad" style={{ paddingBottom: 0 }}><b>Processes</b><span className="df-grow" /><a href="#/inbox">Open Inbox</a></div>
          <FilterBar onClear={() => { setQ(""); setState("all"); setShow("all"); }} count={`${rows.length} of ${processes.length} processes`}>
            <SearchBox value={q} onChange={setQ} placeholder="Search processes" />
            <FilterSelect label="State" value={state} onChange={setState}
              options={[{ value: "all", label: "All" }, { value: "live", label: "Live" }, { value: "draft", label: "Draft" }]} />
            <FilterSelect label="Show" value={show} onChange={setShow}
              options={[{ value: "all", label: "All processes" }, { value: "waiting", label: "With waiting reviews" }, { value: "low", label: "Straight through under 85%" }]} />
          </FilterBar>
          <div className="df-table-wrap">
            <table className="df-table">
              <thead><tr><th>Process</th><th className="df-right">Today</th><th>Straight through</th><th className="df-right">Waiting</th><th>State</th></tr></thead>
              <tbody>
                {rows.map((p) => (
                  <tr key={p.id}>
                    <td><a href={"#/process/" + p.id}>{p.name}</a></td>
                    <td className="df-right df-num">{p.today}</td>
                    <td><span className="df-conf"><span className="df-conf-track" style={{ width: 90 }}><span className="df-conf-fill" style={{ width: p.straightThrough + "%", background: "var(--ui-accent)" }} /></span><span className="df-num">{p.straightThrough}%</span></span></td>
                    <td className="df-right df-num">{p.review}</td>
                    <td><Chip tone={p.state === "live" ? "ok" : "mute"}>{p.state === "live" ? "Live" : "Draft"}</Chip></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="df-card df-pad" aria-label="Where pages were read" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <div className="df-head-row"><b>Where pages were read</b><span className="df-grow" /><a href="#/engines">Engines</a></div>
          <div style={{ display: "flex", height: 14, borderRadius: 7, overflow: "hidden", background: "var(--ui-divider)" }} aria-hidden="true">
            {o.readBy.map((r, i) => <div key={r.label} style={{ width: r.share + "%", background: colors[i] }} />)}
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "auto 1fr auto", gap: "6px 10px", alignItems: "center" }}>
            {o.readBy.map((r, i) => [
              <span key={r.label + "s"} className="df-swatch" style={{ background: colors[i], border: i === 3 ? "1px solid var(--ui-field)" : 0 }} />,
              <span key={r.label}>{r.label}</span>,
              <b key={r.label + "v"} className="df-num">{r.share}%</b>])}
          </div>
        </section>
      </div>
    </div>
  );
}
