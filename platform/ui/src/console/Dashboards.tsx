import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { CrApi, CrUser, CustomDashboard, DashWidget, WidgetData } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * Operational Insights > Dashboards, rebuilt from today's console with AA's Bot Insight custom dashboards and UiPath
 * Insights' templates in mind (checked 31 Oct 2026): dashboards of widgets over the KPIs agents record and over the
 * runs, kept to oneself or shared; ready-made dashboards to start from; widgets made in a form (or as JSON) and shown as
 * a number, bars, a line, a table or a pie; a CSV of each widget; any dashboard one may see copied as one's own.
 */
type Kpi = { bot_path: string; name: string; n: number };
const TYPES: [NonNullable<DashWidget["type"]>, string][] = [["number", "A number"], ["bars", "Bars"], ["line", "A line over time"], ["table", "A table"], ["pie", "A pie"]];
const METRICS: [NonNullable<DashWidget["metric"]>, string][] = [["runs", "Runs"], ["success_rate", "Success rate (%)"], ["failed", "Failed runs"], ["avg_seconds", "Average run time (seconds)"], ["hours_saved", "Hours saved"]];
const AGGS: [NonNullable<DashWidget["agg"]>, string][] = [["sum", "Total"], ["avg", "Average"], ["count", "How many"], ["min", "Smallest"], ["max", "Largest"], ["last", "Latest"]];
const GROUPS: [NonNullable<DashWidget["group"]>, string][] = [["none", "Nothing"], ["day", "Day"], ["week", "Week"], ["month", "Month"], ["agent", "Agent"], ["value", "A value"]];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const titleOf = (w: DashWidget) => w.title || (w.source === "runs" ? METRICS.find(([m]) => m === (w.metric ?? "runs"))?.[1] ?? "Runs" : (AGGS.find(([a]) => a === (w.agg ?? "sum"))?.[1] ?? "") + " of " + w.kpi);
const fmt = (w: DashWidget, v?: number | null) => v == null ? "–" : Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 }) + (w.metric === "success_rate" ? "%" : "");

/** Ready-made dashboards: two over the runs, and one over the first KPI recorded (when there is one). */
function readyMade(kpis: Kpi[]): { id: string; name: string; description: string; widgets: DashWidget[] }[] {
  const out = [
    { id: "ops", name: "Operations at a glance", description: "How the agents ran this month.", widgets: [
      { title: "Success rate, last 7 days", type: "number", source: "runs", metric: "success_rate", days: 7 },
      { title: "Runs per day", type: "line", source: "runs", metric: "runs", group: "day", days: 30 },
      { title: "Failed runs per agent", type: "bars", source: "runs", metric: "failed", group: "agent", days: 30 },
      { title: "Runs per agent", type: "pie", source: "runs", metric: "runs", group: "agent", days: 30 }] as DashWidget[] },
    { id: "value", name: "Value", description: "Hours of manual work the agents saved.", widgets: [
      { title: "Hours saved, last 30 days", type: "number", source: "runs", metric: "hours_saved", days: 30 },
      { title: "Hours saved per week", type: "line", source: "runs", metric: "hours_saved", group: "week", days: 90 },
      { title: "Hours saved per agent", type: "bars", source: "runs", metric: "hours_saved", group: "agent", days: 90 }] as DashWidget[] }];
  const k = kpis[0];
  if (k) out.push({ id: "kpi", name: "Business figure: " + k.name, description: "The " + k.name + " " + name(k.bot_path) + " records.", widgets: [
    { title: "Total " + k.name + ", last 30 days", type: "number", source: "kpi", kpi: k.name, bot: k.bot_path, agg: "sum", days: 30 },
    { title: k.name + " per day", type: "line", source: "kpi", kpi: k.name, bot: k.bot_path, agg: "sum", group: "day", days: 30 },
    { title: k.name + " by value", type: "table", source: "kpi", kpi: k.name, bot: k.bot_path, agg: "count", group: "value", days: 30 }] as DashWidget[] });
  return out;
}

export function Dashboards({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<CustomDashboard[] | null>(null);
  const [current, setCurrent] = useState<string | null>(null);
  const [data, setData] = useState<WidgetData[] | null>(null);
  const [kpis, setKpis] = useState<Kpi[]>([]);
  const [agents, setAgents] = useState<string[]>([]);
  const [editing, setEditing] = useState<{ d: CustomDashboard | null; copy?: boolean } | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((pick?: string) => api.dashboards().then((l) => {
    setList(l); setError(null);
    setCurrent((c) => { const want = pick ?? c; return l.some((d) => d.id === want) ? want! : l[0]?.id ?? null; });
  }, (e) => setError(message(e))), [api]);
  useEffect(() => { load(); api.dashboardKpis().then(setKpis, () => {}); if (user.permissions.includes("repo.view")) api.agents().then((b) => setAgents(b.map((x) => x.path)), () => {}); }, [load]);   // eslint-disable-line react-hooks/exhaustive-deps
  const refresh = useCallback(() => {
    if (!current) { setData(null); return; }
    api.dashboardData(current, Intl.DateTimeFormat().resolvedOptions().timeZone).then((r) => setData(r.widgets), (e) => setError(message(e)));
  }, [api, current]);
  useEffect(() => { setData(null); refresh(); const t = setInterval(refresh, 60000); return () => clearInterval(t); }, [refresh]);

  const cur = list?.find((d) => d.id === current) ?? null;
  const remove = () => cur && api.deleteDashboard(cur.id).then(() => { onNotice("Deleted the dashboard " + cur.name + "."); setDeleting(false); load(""); }, (e) => setError(message(e)));

  return (
    <section className="st-page cc-home" aria-labelledby="cc-db-title">
      <header className="st-page-head">
        <div><h1 id="cc-db-title">Dashboards</h1><p>Business figures your agents record (outputs marked KPI in One AgentStudio) and figures about the runs, on dashboards you keep to yourself or share.</p></div>
        <Button kind="primary" onClick={() => setEditing({ d: null })}>New dashboard</Button>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {list && !list.length && <p className="st-muted cc-pad">No dashboards yet. Make one, or start from a ready-made one with New dashboard.</p>}
      {list && list.length > 0 && <div className="cc-bar" role="toolbar" aria-label="Dashboard">
        <Select aria-label="Dashboard" value={current ?? ""} onChange={(e) => { setCurrent(e.target.value); setDeleting(false); }} options={list.map((d) => ({ value: d.id, label: d.name }))} />
        {cur?.can_edit && <Button kind="secondary" onClick={() => setEditing({ d: cur })}>Edit</Button>}
        {cur && <Button kind="secondary" onClick={() => setEditing({ d: cur, copy: true })}>Copy as mine</Button>}
        {cur?.can_edit && (deleting
          ? <span className="cc-inline">Delete {cur.name}? <Button kind="danger" onClick={remove}>Delete</Button><Button kind="plain" onClick={() => setDeleting(false)}>Keep</Button></span>
          : <Button kind="plain" onClick={() => setDeleting(true)}>Delete</Button>)}
        <span className="st-muted">{cur ? (cur.owner === user.username ? "Yours" : "Made by " + cur.owner) + (cur.shared_with.length ? " · shared with " + cur.shared_with.join(", ") : " · not shared") : ""}</span>
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={refresh}>Refresh</Button>
      </div>}
      {cur?.description && <p className="st-muted cc-pad-x">{cur.description}</p>}
      {cur && !data && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {data && <div className="cc-dash">{data.map((w, i) => <WidgetCard key={i} w={w} />)}</div>}
      {editing && <DashboardEditor api={api} d={editing.d} copy={!!editing.copy} kpis={kpis} agents={agents} onClose={() => setEditing(null)}
        onSaved={(id, t) => { onNotice(t); setEditing(null); load(id); }} />}
    </section>
  );
}

function WidgetCard({ w }: { w: WidgetData }) {
  const title = titleOf(w);
  const pts = (w.points ?? []).filter((p) => p.value != null) as { label: string; value: number }[];
  const csv = () => {
    const rows = w.type === "number" || !w.type ? [["Figure"], [String(w.value ?? "")]] : [["Label", "Figure"], ...(w.points ?? []).map((p) => [p.label, String(p.value ?? "")])];
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([rows.map((r) => r.map(csvCell).join(",")).join("\n")], { type: "text/csv" }));
    a.download = title.replace(/[^\w -]+/g, "").trim().replace(/\s+/g, "-").toLowerCase() + ".csv";
    document.body.appendChild(a); a.click(); a.remove();
  };
  let body: ReactNode;
  if (w.error) body = <p className="st-alert">{w.error}</p>;
  else if (w.type === "number" || !w.type) body = <div className="cc-dash-number">{fmt(w, w.value)}</div>;
  else if (!pts.length) body = <p className="st-muted">No figures in this period.</p>;
  else if (w.type === "table") body = <table className="st-table" aria-label={title}><thead><tr><th scope="col">{w.group === "agent" ? "Agent" : w.group === "value" ? "Value" : "Period"}</th><th scope="col">Figure</th></tr></thead>
    <tbody>{(w.points ?? []).map((p) => <tr key={p.label}><td>{w.group === "agent" ? name(p.label) : p.label}</td><td>{fmt(w, p.value)}</td></tr>)}</tbody></table>;
  else if (w.type === "bars") { const max = Math.max(1, ...pts.map((p) => p.value)); body = <ul className="cc-hbars" aria-label={title}>{pts.map((p) => <li key={p.label}>
    <span className="cc-hbar-label" title={p.label}>{w.group === "agent" ? name(p.label) : p.label}</span>
    <span className="cc-hbar-track"><span className="cc-hbar-fill" style={{ width: (100 * p.value / max) + "%" }} /></span><span className="cc-hbar-value">{fmt(w, p.value)}</span></li>)}</ul>; }
  else if (w.type === "pie") body = <Pie w={w} pts={pts} title={title} />;
  else body = <Line w={w} pts={pts} title={title} />;
  return <article className="cc-card cc-card-flat cc-dash-card" aria-label={title}>
    <div className="cc-card-head"><h2>{title}</h2>{!w.error && <Button kind="plain" onClick={csv} aria-label={"CSV of " + title}>CSV</Button>}</div>
    {body}
    <p className="cc-card-foot st-muted">{w.days ?? 30} days{w.bot ? " · " + name(w.bot) : ""}</p>
  </article>;
}

function Line({ w, pts, title }: { w: DashWidget; pts: { label: string; value: number }[]; title: string }) {
  const W = 320, H = 120;
  const max = Math.max(...pts.map((p) => p.value)), min = Math.min(0, ...pts.map((p) => p.value));
  const x = (i: number) => pts.length === 1 ? W / 2 : 6 + i * (W - 12) / (pts.length - 1), y = (v: number) => H - 4 - (H - 8) * (v - min) / Math.max(1e-9, max - min);
  return <figure className="cc-dash-line">
    <svg viewBox={"0 0 " + W + " " + H} role="img" aria-label={title + ": from " + fmt(w, pts[0].value) + " (" + pts[0].label + ") to " + fmt(w, pts[pts.length - 1].value) + " (" + pts[pts.length - 1].label + ")"}>
      <polyline points={pts.map((p, i) => x(i).toFixed(1) + "," + y(p.value).toFixed(1)).join(" ")} fill="none" strokeWidth="2" />
      {pts.length <= 60 && pts.map((p, i) => <circle key={i} cx={x(i).toFixed(1)} cy={y(p.value).toFixed(1)} r="3"><title>{p.label + ": " + fmt(w, p.value)}</title></circle>)}
    </svg>
    <figcaption className="cc-dash-axis"><span>{pts[0].label}</span><span>highest {fmt(w, max)}</span><span>{pts[pts.length - 1].label}</span></figcaption>
  </figure>;
}

/** A pie of the three largest parts and the rest, with a legend of values and shares (colours as Home's chart: 3:1 or more on the card in both themes). */
function Pie({ w, pts, title }: { w: DashWidget; pts: { label: string; value: number }[]; title: string }) {
  const sorted = [...pts].filter((p) => p.value > 0).sort((a, b) => b.value - a.value);
  const parts = sorted.length > 4 ? [...sorted.slice(0, 3), { label: "Others", value: sorted.slice(3).reduce((n, p) => n + p.value, 0) }] : sorted;
  const total = parts.reduce((n, p) => n + p.value, 0);
  if (!total) return <p className="st-muted">No figures in this period.</p>;
  let a = -Math.PI / 2;
  const arcs = parts.map((p) => {
    const share = p.value / total, b = a + share * 2 * Math.PI, large = share > 0.5 ? 1 : 0;
    const d = share >= 0.9999 ? "M 50 10 A 40 40 0 1 1 49.99 10 Z" : `M 50 50 L ${50 + 40 * Math.cos(a)} ${50 + 40 * Math.sin(a)} A 40 40 0 ${large} 1 ${50 + 40 * Math.cos(b)} ${50 + 40 * Math.sin(b)} Z`;
    a = b; return d;
  });
  const label = (l: string) => w.group === "agent" && l !== "Others" ? name(l) : l;
  return <div className="cc-pie">
    <svg viewBox="0 0 100 100" role="img" aria-label={title}>{arcs.map((d, i) => <path key={i} d={d} className={"cc-pie-" + i}><title>{label(parts[i].label)}</title></path>)}</svg>
    <ul className="cc-pie-legend" aria-label={"Parts of " + title}>{parts.map((p, i) => <li key={p.label}><span className={"cc-pie-key cc-pie-" + i} />{label(p.label)}<span className="st-muted">{fmt(w, p.value)} · {Math.round(100 * p.value / total)}%</span></li>)}</ul>
  </div>;
}

/** Making, changing or copying a dashboard: name, sharing, and its widgets (in a form, or as JSON). */
function DashboardEditor({ api, d, copy, kpis, agents, onClose, onSaved }: { api: CrApi; d: CustomDashboard | null; copy: boolean; kpis: Kpi[]; agents: string[];
  onClose: () => void; onSaved: (id: string, t: string) => void }) {
  const [v, setV] = useState({ name: d ? (copy ? d.name + " (copy)" : d.name) : "", description: d?.description ?? "", shared: copy ? "" : (d?.shared_with ?? []).join(", ") });
  const [widgets, setWidgets] = useState<DashWidget[]>(d?.widgets ?? []);
  const [json, setJson] = useState<string | null>(null);
  const [widget, setWidget] = useState<{ i: number | null; w: DashWidget } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ready = readyMade(kpis);
  const move = (i: number, by: number) => { const n = [...widgets]; const [x] = n.splice(i, 1); n.splice(i + by, 0, x); setWidgets(n); };
  const save = () => {
    let ws = widgets;
    if (json != null) { try { ws = JSON.parse(json); } catch { setError("The widgets are not valid JSON."); return; } }
    api.saveDashboard(d && !copy ? d.id : null, { name: v.name.trim(), description: v.description.trim(), shared_with: v.shared.split(",").map((x) => x.trim()).filter(Boolean), widgets: ws })
      .then((r) => onSaved(r.id, (d && !copy ? "Saved " : "Made ") + v.name.trim() + "."), (e) => setError(message(e)));
  };
  return (
    <Dialog open title={d ? (copy ? "Copy " + d.name : "Edit " + d.name) : "New dashboard"} width={760} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim() || (json == null && !widgets.length)} onClick={save}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!d && <Field label="Start from" hint="A ready-made dashboard fills in its widgets; change them as you like.">{(id) => <Select id={id} defaultValue=""
        onChange={(e) => { const t = ready.find((r) => r.id === e.target.value); if (t) { setV({ ...v, name: v.name || t.name, description: v.description || t.description }); setWidgets(t.widgets); setJson(null); } }}
        options={[{ value: "", label: "Nothing: add widgets myself" }, ...ready.map((r) => ({ value: r.id, label: r.name }))]} />}</Field>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} maxLength={120} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
        <Field label="Share with" hint="role:NAME, team:NAME, user:NAME or * for everyone; empty keeps it yours">{(id) => <TextInput id={id} value={v.shared} placeholder="role:viewer, team:finance" onChange={(e) => setV({ ...v, shared: e.target.value })} />}</Field>
      </div>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} maxLength={1000} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      <div className="cc-card-head cc-gap"><h3 className="cc-log-title">Widgets</h3>
        <span className="cc-inline">{json == null && <Button kind="secondary" disabled={widgets.length >= 40} onClick={() => setWidget({ i: null, w: { type: "number", source: "runs", metric: "runs", days: 30 } })}>Add a widget</Button>}
          <Button kind="plain" onClick={() => { if (json == null) setJson(JSON.stringify(widgets, null, 2)); else { try { setWidgets(JSON.parse(json)); setJson(null); setError(null); } catch { setError("The widgets are not valid JSON."); } } }}>
            {json == null ? "Edit as JSON" : "Back to the form"}</Button></span></div>
      {json != null ? <TextArea aria-label="Widgets (JSON)" rows={14} className="ui-mono" value={json} onChange={(e) => setJson(e.target.value)} />
        : widgets.length ? <table className="st-table" aria-label="Widgets"><tbody>{widgets.map((w, i) => <tr key={i}>
          <td>{titleOf(w)}</td><td className="st-muted">{TYPES.find(([t]) => t === (w.type ?? "number"))?.[1]} · {w.days ?? 30} days</td>
          <td><span className="cc-inline">
            <Button kind="plain" onClick={() => setWidget({ i, w })} aria-label={"Change " + titleOf(w)}>Change</Button>
            <Button kind="plain" disabled={i === 0} onClick={() => move(i, -1)} aria-label={"Move " + titleOf(w) + " up"}>Up</Button>
            <Button kind="plain" disabled={i === widgets.length - 1} onClick={() => move(i, 1)} aria-label={"Move " + titleOf(w) + " down"}>Down</Button>
            <Button kind="plain" onClick={() => setWidgets(widgets.filter((_, j) => j !== i))} aria-label={"Remove " + titleOf(w)}>Remove</Button></span></td></tr>)}</tbody></table>
        : <p className="st-muted">No widgets yet: add one, or start from a ready-made dashboard.</p>}
      {kpis.length === 0 && <p className="st-muted">No KPI values recorded yet: mark an agent's outputs as KPI in One AgentStudio and run it. Run figures work now.</p>}
      {widget && <WidgetEditor w={widget.w} kpis={kpis} agents={agents} onClose={() => setWidget(null)}
        onDone={(w) => { setWidgets(widget.i == null ? [...widgets, w] : widgets.map((x, j) => j === widget.i ? w : x)); setWidget(null); }} />}
    </Dialog>
  );
}

function WidgetEditor({ w, kpis, agents, onClose, onDone }: { w: DashWidget; kpis: Kpi[]; agents: string[]; onClose: () => void; onDone: (w: DashWidget) => void }) {
  const [x, setX] = useState<DashWidget>(w);
  const kpiNames = [...new Set(kpis.filter((k) => !x.bot || k.bot_path === x.bot).map((k) => k.name))];
  const set = (p: Partial<DashWidget>) => setX({ ...x, ...p });
  const done = () => {
    const out: DashWidget = { title: x.title?.trim() || undefined, type: x.type ?? "number", source: x.source ?? "runs", days: x.days ?? 30 };
    if (out.source === "runs") out.metric = x.metric ?? "runs"; else { out.kpi = x.kpi; out.agg = x.agg ?? "sum"; }
    if (x.bot) out.bot = x.bot;
    if (out.type !== "number" && x.group && x.group !== "none") { out.group = x.group; if (x.group === "value" && x.by) out.by = x.by; }
    onDone(out);
  };
  const valid = (x.source !== "kpi" || !!x.kpi) && (x.days ?? 30) >= 1 && (x.days ?? 30) <= 365;
  return (
    <Dialog open title={w.title ? "Widget " + w.title : "Widget"} width={600} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!valid} onClick={done}>Done</Button></>}>
      <Field label="Title" hint="Empty: named after its figure">{(id) => <TextInput id={id} value={x.title ?? ""} maxLength={120} onChange={(e) => set({ title: e.target.value })} />}</Field>
      <div className="cc-grid2">
        <Field label="Show as">{(id) => <Select id={id} value={x.type ?? "number"} onChange={(e) => set({ type: e.target.value as DashWidget["type"] })} options={TYPES.map(([v, l]) => ({ value: v, label: l }))} />}</Field>
        <Field label="Figure from">{(id) => <Select id={id} value={x.source ?? "runs"} onChange={(e) => set({ source: e.target.value as DashWidget["source"] })}
          options={[{ value: "runs", label: "The runs" }, { value: "kpi", label: "A KPI agents record" }]} />}</Field>
        {(x.source ?? "runs") === "runs"
          ? <Field label="Figure">{(id) => <Select id={id} value={x.metric ?? "runs"} onChange={(e) => set({ metric: e.target.value as DashWidget["metric"] })} options={METRICS.map(([v, l]) => ({ value: v, label: l }))} />}</Field>
          : <>
            <Field label="KPI">{(id) => <Select id={id} value={x.kpi ?? ""} onChange={(e) => set({ kpi: e.target.value })} options={[{ value: "", label: "Choose a KPI" }, ...kpiNames.map((k) => ({ value: k, label: k }))]} />}</Field>
            <Field label="Work out">{(id) => <Select id={id} value={x.agg ?? "sum"} onChange={(e) => set({ agg: e.target.value as DashWidget["agg"] })} options={AGGS.map(([v, l]) => ({ value: v, label: l }))} />}</Field>
          </>}
        <Field label="Agent">{(id) => <Select id={id} value={x.bot ?? ""} onChange={(e) => set({ bot: e.target.value || undefined })}
          options={[{ value: "", label: "All agents" }, ...agents.map((a) => ({ value: a, label: name(a) }))]} />}</Field>
        {(x.type ?? "number") !== "number" && <Field label="Split by">{(id) => <Select id={id} value={x.group ?? "none"} onChange={(e) => set({ group: e.target.value as DashWidget["group"] })}
          options={GROUPS.filter(([g]) => g !== "value" || x.source === "kpi").map(([v, l]) => ({ value: v, label: l }))} />}</Field>}
        {x.group === "value" && x.source === "kpi" && <Field label="The value of" hint="Empty: the KPI's own value">{(id) => <Select id={id} value={x.by ?? ""} onChange={(e) => set({ by: e.target.value || undefined })}
          options={[{ value: "", label: x.kpi ?? "The KPI" }, ...kpiNames.filter((k) => k !== x.kpi).map((k) => ({ value: k, label: k }))]} />}</Field>}
        <Field label="Days looked at" hint="1 to 365">{(id) => <TextInput id={id} type="number" min={1} max={365} value={String(x.days ?? 30)} onChange={(e) => set({ days: Number(e.target.value) || undefined })} />}</Field>
      </div>
    </Dialog>
  );
}
