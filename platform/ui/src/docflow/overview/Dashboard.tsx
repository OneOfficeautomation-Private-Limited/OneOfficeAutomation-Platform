import type { ReactNode } from "react";
import type { DayCount, DocFlowApi } from "../api";
import { Chip, Kpi, Loading, useLoad } from "../ui";

const BLUE = "#2f6fde", AMBER = "#f5b33d", RED = "#c0233f";
const READ_BY = [BLUE, "#8b6ae8", "#4b5565", "transparent"];

/** Overview, Dashboard: charts only, across every process, and what needs attention. */
export function Dashboard({ api }: { api: DocFlowApi }) {
  const [data] = useLoad(() => Promise.all([api.overview(), api.processTable()]), [api]);
  if (!data) return <Loading />;
  const [o, processes] = data;
  const month = processes.reduce((n, p) => n + p.month, 0);
  const byMonth = [...processes].sort((a, b) => b.month - a.month);

  return (<>
    <div className="df-grid df-g6">
      <Kpi label="Documents today" value={o.today} />
      <Kpi label="Straight through" value={o.straightThrough + "%"} sub={`${Math.round(o.today * o.straightThrough / 100)} with no human touch`} tone="ok" />
      <Kpi label="Waiting for review" value={o.waiting} sub={o.overdue ? `${o.overdue} overdue` : "none overdue"} tone={o.overdue ? "bad" : undefined} />
      <Kpi label="Failed" value={o.failed} tone={o.failed ? "bad" : undefined} />
      <Kpi label="Pages read this month" value={o.pagesThisMonth.toLocaleString("en")} sub={o.cloudOptIn ? undefined : "all on this server"} />
      {o.cloudOptIn
        ? <Kpi label="Cloud cost this month" value={"$" + o.cloudCost} sub="cloud opt-in is on" />
        : <Kpi label="Documents this month" value={month.toLocaleString("en")} sub="cloud opt-in is off: no cloud cost" />}
    </div>

    <div className="df-grid df-g3">
      <Card label="Documents per day" span>
        <div className="df-head-row">
          <b>Documents per day, last 14 days</b><span className="df-grow" />
          <span className="df-legend"><span className="df-swatch" style={{ background: BLUE }} />Straight through</span>
          <span className="df-legend"><span className="df-swatch" style={{ background: AMBER }} />Reviewed by a person</span>
        </div>
        <DayBars days={o.days} />
      </Card>
      <Card label="Needs attention">
        <b>Needs attention</b>
        {!o.alerts.length && <span className="df-muted">Nothing needs you now.</span>}
        {o.alerts.map((a) => (
          <a key={a.title} href={a.to} className="df-card" style={{ display: "flex", gap: 10, padding: 8, textDecoration: "none", color: "inherit" }}>
            <Chip tone={a.level === "bad" ? "bad" : a.level === "warn" ? "warn" : "info"}>{a.level === "bad" ? "Act now" : a.level === "warn" ? "Check" : "Note"}</Chip>
            <span><b>{a.title}</b><span className="df-small" style={{ display: "block" }}>{a.text}</span></span>
          </a>
        ))}
      </Card>
    </div>

    <div className="df-grid df-g3">
      <Card label="Straight-through rate">
        <b>Straight-through rate per day</b>
        <RateBars days={o.days} />
      </Card>
      <Card label="Review backlog">
        <div className="df-head-row"><b>Review backlog</b><span className="df-grow" />
          <span className="df-legend"><span className="df-swatch" style={{ background: AMBER }} />Waiting</span>
          <span className="df-legend"><span className="df-swatch" style={{ background: RED }} />Overdue</span></div>
        <HBars rows={processes.filter((p) => p.review > 0).map((p) => ({ label: p.name, value: p.review, part: p.overdue, to: "#/process/" + encodeURIComponent(p.id),
          text: p.overdue ? `${p.review} · ${p.overdue} overdue` : String(p.review) }))} color={AMBER} partColor={RED} empty="No document waits for review." />
      </Card>
      <Card label="Failures">
        <b>Failed documents</b>
        <HBars rows={processes.filter((p) => p.failed > 0).map((p) => ({ label: p.name, value: p.failed, to: "#/process/" + encodeURIComponent(p.id) }))}
          color={RED} empty="No document failed." />
      </Card>
    </div>

    <div className="df-grid df-g3">
      <Card label="Documents by process" span>
        <b>Documents by process, this month</b>
        <HBars rows={byMonth.map((p) => ({ label: p.name, value: p.month, to: "#/process/" + encodeURIComponent(p.id),
          text: `${p.month.toLocaleString("en")} · ${p.straightThrough}% straight through` }))} color={BLUE} empty="No documents this month." />
      </Card>
      <Card label="Where pages were read">
        <div className="df-head-row"><b>Which engines read the pages</b><span className="df-grow" /><a href="#/engines">Engines</a></div>
        <div style={{ display: "flex", height: 14, borderRadius: 7, overflow: "hidden", background: "var(--ui-divider)" }} aria-hidden="true">
          {o.readBy.map((r, i) => <div key={r.label} style={{ width: r.share + "%", background: READ_BY[i % READ_BY.length] }} />)}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "auto 1fr auto", gap: "6px 10px", alignItems: "center" }}>
          {o.readBy.map((r, i) => [
            <span key={r.label + "s"} className="df-swatch" style={{ background: READ_BY[i % READ_BY.length], border: i === 3 ? "1px solid var(--ui-field)" : 0 }} />,
            <span key={r.label}>{r.label}</span>,
            <b key={r.label + "v"} className="df-num">{r.share}%</b>])}
        </div>
      </Card>
    </div>
  </>);
}

function Card({ label, span, children }: { label: string; span?: boolean; children: ReactNode }) {
  return <section className={`df-card df-pad${span ? " df-span2" : ""}`} aria-label={label} style={{ display: "flex", flexDirection: "column", gap: 10 }}>{children}</section>;
}

/** Documents per day: straight through and reviewed, stacked. */
function DayBars({ days }: { days: DayCount[] }) {
  const max = Math.max(...days.map((d) => d.straight + d.reviewed), 1) * 1.15;
  return (<>
    <div className="df-bars" role="img" aria-label={`Documents per day: ${days.map((d) => `${d.label}: ${d.straight + d.reviewed}`).join(", ")}`}>
      {days.map((d) => (
        <div key={d.label} className="df-bar" title={`${d.straight + d.reviewed} documents, ${d.reviewed} reviewed`}>
          <div style={{ height: (d.reviewed / max * 100) + "%", background: AMBER, borderRadius: "3px 3px 0 0" }} />
          <div style={{ height: (d.straight / max * 100) + "%", background: BLUE }} />
        </div>
      ))}
    </div>
    <div className="df-bar-labels" aria-hidden="true">{days.map((d) => <span key={d.label}>{d.label}</span>)}</div>
  </>);
}

/** The share of each day's documents that needed nobody; days without documents stay empty. */
function RateBars({ days }: { days: DayCount[] }) {
  const rate = (d: DayCount) => (d.straight + d.reviewed ? Math.round(100 * d.straight / (d.straight + d.reviewed)) : null);
  return (<>
    <div className="df-bars df-bars-small" role="img" aria-label={`Straight through per day: ${days.map((d) => `${d.label}: ${rate(d) ?? "none"}${rate(d) === null ? "" : "%"}`).join(", ")}`}>
      {days.map((d) => {
        const r = rate(d);
        return <div key={d.label} className="df-bar" title={r === null ? "No documents" : r + "% straight through"}>
          <div style={{ height: (r ?? 0) + "%", background: BLUE, borderRadius: "3px 3px 0 0" }} /></div>;
      })}
    </div>
    <div className="df-bar-labels" aria-hidden="true">{days.map((d) => <span key={d.label}>{d.label}</span>)}</div>
  </>);
}

/** One bar per row, longest first; part: a share of the bar in another colour (overdue of waiting). */
function HBars({ rows, color, partColor, empty }:
  { rows: { label: string; value: number; part?: number; text?: string; to?: string }[]; color: string; partColor?: string; empty: string }) {
  if (!rows.length) return <span className="df-muted">{empty}</span>;
  const max = Math.max(...rows.map((r) => r.value), 1);
  return (
    <ul className="df-hbars">
      {rows.map((r) => (
        <li key={r.label}>
          <span className="df-hbar-label">{r.to ? <a href={r.to}>{r.label}</a> : r.label}</span>
          <span className="df-hbar-track" aria-hidden="true">
            <span style={{ width: (r.value / max * 100) + "%", background: color }}>
              {r.part ? <span style={{ width: (r.part / r.value * 100) + "%", background: partColor }} /> : null}
            </span>
          </span>
          <b className="df-num">{r.text ?? r.value.toLocaleString("en")}</b>
        </li>
      ))}
    </ul>
  );
}
