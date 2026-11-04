import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { AgentStats, BusinessAgent, CrApi, CrUser, DashWidget, VariableProfile } from "../api/cr";
import { Button, HelpButton, Select, TextInput, usePersistent, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";

/**
 * Operational Insights > Business > Agents (item 7 step 2, 1 Nov 2026), as AA's Business dashboards: every agent whose
 * runs return values, each with a page of its own: a dashboard made from its values, its data profile (AA's data
 * profile) and Compare (two agents, or one agent in two periods). "Make my own copy" turns the made dashboard into one
 * of the person's dashboards (needs insights.design). Only agents in folders the person may see.
 */
const PERIODS: [string, string][] = [["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]];
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const folderOf = (path: string) => path.split("/").slice(0, -1).join("/");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const when = (t?: string | null) => t ? new Date(t).toLocaleString(undefined, { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
const day = (d: string) => new Date(d + "T12:00:00").toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
const num = (n?: number | null) => n == null ? "–" : n.toLocaleString(undefined, { maximumFractionDigits: 2 });
const rate = (x?: number | null) => x == null ? "–" : x + "%";
const ENDED: Record<string, string> = { success: "Succeeded", failed: "Failed", stopped: "Stopped" };
function dur(ms?: number | null) {
  if (ms == null) return "–";
  const s = Math.round(ms / 1000);
  return s < 60 ? s + " s" : s < 3600 ? Math.floor(s / 60) + " min " + (s % 60) + " s" : Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min";
}
const shown = (v: unknown) => v == null || v === "" ? "" : typeof v === "object" ? JSON.stringify(v) : String(v);

const HELP: HelpTopic = { title: "Business: Agents", about: "Every agent whose runs return values (its outputs), with a dashboard made for it from those values, "
  + "as AA's Business dashboards. Values marked KPI in One AgentStudio come first.",
  items: [
    { name: "Values it records", text: "The outputs the agent's runs returned in the period; KPI-marked ones are shown first and bold." },
    { name: "With values", text: "How many of its runs returned at least one value." },
    { name: "Open an agent", text: "Click a row to see its dashboard, its data profile and Compare." },
  ] };
const HELP_PAGE: HelpTopic = { title: "An agent's business figures", about: "A dashboard made from the values the agent's runs return, kept up to date as new values appear.",
  items: [
    { name: "Dashboard", text: "Totals of its number values, the first one per day, how its runs ended, and each run's values (the latest 20)." },
    { name: "Data profile", text: "For each value: its kind, how many runs returned it, the smallest, largest, average and total, how many different values, "
      + "and how often each value occurs (the ten most common)." },
    { name: "Compare", text: "This agent against another agent, or against itself in the period before, side by side." },
    { name: "Make my own copy", text: "Saves the dashboard as one of yours under Business > Dashboards, to change and share. Needs permission to make dashboards." },
  ] };

export function BusinessAgents({ api, user, onNotice, title, tabs }: { api: CrApi; user: CrUser; onNotice: (t: string) => void; title: string; tabs: ReactNode }) {
  const [days, setDays] = usePersistent("business.agents.days", "30");
  const [q, setQ] = useState("");
  const [list, setList] = useState<BusinessAgent[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => api.businessAgents({ days }).then((r) => { setList(r); setError(null); }, (e) => setError(message(e))), [api, days]);
  useEffect(() => { load(); }, [load]);
  const t = q.trim().toLowerCase();
  const rows = (list ?? []).filter((a) => !t || a.bot_path.toLowerCase().includes(t) || a.values.some((v) => v.toLowerCase().includes(t)));

  if (open) return <AgentBusiness api={api} user={user} path={open} agents={(list ?? []).map((a) => a.bot_path)} days={days} onClose={() => setOpen(null)} onNotice={onNotice} />;
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-ba-title">
      <header className="cc-list-head">
        <h1 id="cc-ba-title">{title}</h1>
        <HelpButton topic={HELP} />
      </header>
      {tabs}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput aria-label="Search agents" placeholder="Search agents or values" value={q} onChange={(e) => setQ(e.target.value)} /></span>
          <span className="cc-chip"><Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!list && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {list && <div className="cc-list-panel"><div className="cc-list-scroll"><table className="cc-list-table" aria-label="Agents that record values">
        <thead><tr><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col">Values it records</th><th scope="col" className="cc-num">Runs</th>
          <th scope="col" className="cc-num">With values</th><th scope="col">Last run</th><th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
        <tbody>{rows.map((a) => <tr key={a.bot_path} title="Open its dashboard" onClick={rowClick(() => setOpen(a.bot_path))}>
          <td><span className="cc-aico" aria-hidden="true">A</span>{name(a.bot_path)}</td><td>{folderOf(a.bot_path)}</td>
          <td className="cc-clip" title={a.values.join(", ")}>{a.values.map((v) => <span key={v} className={"cc-chip-s" + (a.kpis.includes(v) ? " is-kpi" : "")}>{v}</span>)}</td>
          <td className="cc-num">{a.runs}</td><td className="cc-num">{a.runs_with_values}</td><td>{when(a.last_run)}</td>
          <td className="cc-list-menu"><RowMenu label={"Actions for " + name(a.bot_path)} items={[{ key: "open", label: "Dashboard, data profile and Compare", onSelect: () => setOpen(a.bot_path) },
            { key: "runs", label: "Its runs in Agent Activity", onSelect: () => { location.hash = "#/activity?" + new URLSearchParams({ tab: "completed", bot: a.bot_path }).toString(); } }]} /></td></tr>)}
          {!rows.length && <tr className="is-static"><td colSpan={7} className="cc-none">{list.length ? "No agent matches." : "No agent's runs returned values in the period."}</td></tr>}</tbody>
      </table></div></div>}
    </section>
  );
}

type Tab = "dashboard" | "profile" | "compare";

/** One agent's own page: Dashboard, Data profile and Compare. */
function AgentBusiness({ api, user, path, agents, days: initialDays, onClose, onNotice }: { api: CrApi; user: CrUser; path: string; agents: string[]; days: string;
  onClose: () => void; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("dashboard");
  const [days, setDays] = useState(initialDays);
  const [stats, setStats] = useState<AgentStats | null>(null);
  const [prof, setProf] = useState<VariableProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const load = useCallback(() => {
    api.agentStatsFor({ path, days, tz }).then(setStats, (e) => setError(message(e)));
    api.agentVariablesFor({ path, days, tz }).then(setProf, (e) => setError(message(e)));
  }, [api, path, days, tz]);
  useEffect(() => { load(); }, [load]);
  const numbers = (prof?.variables ?? []).filter((v) => v.sum != null);
  const copy = async () => {
    const widgets: DashWidget[] = [
      ...numbers.slice(0, 4).map((v) => ({ title: v.variable + ", total", type: "number" as const, source: "output" as const, kpi: v.variable, bot: path, agg: "sum" as const, days: Number(days) })),
      ...(numbers[0] ? [{ title: numbers[0].variable + " per day", type: "line" as const, source: "output" as const, kpi: numbers[0].variable, bot: path, agg: "sum" as const, group: "day" as const, days: Number(days) }] : []),
      { title: "Success rate", type: "number", source: "runs", metric: "success_rate", bot: path, days: Number(days) }];
    try {
      await api.saveDashboard(null, { name: name(path) + " (my copy)", shared_with: [], widgets });
      onNotice("Saved as your dashboard " + name(path) + " (my copy), under Business > Dashboards.");
    } catch (e) { setError(message(e)); }
  };
  const tabs: [Tab, string][] = [["dashboard", "Dashboard"], ["profile", "Data profile"], ["compare", "Compare"]];
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"Business figures of " + name(path)}>
      <div className="cc-agent-head">
        <Button kind="plain" onClick={onClose}>‹ Business</Button>
        <h1><span className="cc-aico" aria-hidden="true">A</span>{name(path)}</h1><HelpButton topic={HELP_PAGE} />
        <span className="st-muted">{folderOf(path)}</span>
        <span className="ov-fill" />
        <span className="cc-chip"><Select aria-label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIODS.map(([v, l]) => ({ value: v, label: l }))} /></span>
        <RefreshButton onClick={load} />
        {user.permissions.includes("repo.view") && <Button kind="secondary" onClick={() => { location.hash = "#/automation?q=" + encodeURIComponent(name(path)); }}>Open in Automation</Button>}
        {user.permissions.includes("insights.design") && numbers.length > 0 && <Button kind="primary" onClick={copy}>Make my own copy</Button>}
      </div>
      <div className="ov-tabs cc-agent-tabs" role="tablist" aria-label="Business figures">
        {tabs.map(([k, l]) => <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {(!stats || !prof) && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {stats && prof && tab === "dashboard" && <Dashboard stats={stats} prof={prof} />}
      {stats && prof && tab === "profile" && <Profile prof={prof} />}
      {stats && prof && tab === "compare" && <Compare api={api} path={path} agents={agents} days={days} stats={stats} prof={prof} />}
    </div>
  );
}

function Bars({ label, items, tone }: { label: string; items: { label: string; value: number; text?: string }[]; tone?: (l: string) => string | undefined }) {
  const max = Math.max(1e-9, ...items.map((x) => Math.abs(x.value)));
  if (!items.length) return <p className="st-muted">Nothing in the period.</p>;
  return <ul className="cc-hbars cc-ins-bars" aria-label={label}>{items.map((x) => <li key={x.label}><span className="cc-hbar-label" title={x.label}>{x.label}</span>
    <span className="cc-hbar-track"><span className={"cc-hbar-fill" + (tone?.(x.label) ? " " + tone(x.label) : "")} style={{ width: (100 * Math.abs(x.value) / max) + "%" }} /></span>
    <span className="cc-hbar-value">{x.text ?? num(x.value)}</span></li>)}</ul>;
}

function Dashboard({ stats, prof }: { stats: AgentStats; prof: VariableProfile }) {
  const t = stats.totals;
  const numbers = prof.variables.filter((v) => v.sum != null);
  const cols = prof.variables.slice(0, 6).map((v) => v.variable);
  const first = numbers[0];
  return <div className="cc-agent-tab">
    <div className="cc-sums cc-sums-flush" role="group" aria-label="Figures">
      <div className="cc-sum is-static"><strong>{prof.runs}</strong><span>Runs with outputs · of {t.runs}</span></div>
      {numbers.slice(0, 3).map((v) => <div key={v.variable} className="cc-sum is-static"><strong>{num(v.sum)}</strong><span>{v.variable} · total</span></div>)}
      <div className="cc-sum is-static"><strong>{dur(t.avg_ms)}</strong><span>Average run</span></div>
    </div>
    <div className="cc-ops-charts cc-ins-charts cc-charts-flush">
      <div className="cc-card cc-card-flat"><h2>Totals</h2><Bars label="Totals of its values" items={numbers.map((v) => ({ label: v.variable, value: v.sum ?? 0 }))} /></div>
      <div className="cc-card cc-card-flat"><h2>{first ? first.variable + " per day" : "Per day"}</h2>
        {first ? <Bars label={first.variable + " per day"} items={(first.by_day ?? []).slice(-14).map((d) => ({ label: day(d.day), value: d.sum }))} /> : <p className="st-muted">No number values.</p>}</div>
      <div className="cc-card cc-card-flat"><h2>Runs by how they ended</h2>
        <Bars label="Runs by how they ended" items={[["Succeeded", t.success], ["Failed", t.failed], ["Stopped", t.stopped]].map(([l, v]) => ({ label: l as string, value: v as number }))}
          tone={(l) => l === "Failed" ? "is-bad" : l === "Stopped" ? "is-warn" : undefined} /></div>
    </div>
    <div className="cc-list-panel"><div className="cc-list-scroll"><table className="cc-list-table" aria-label="Each run's values">
      <thead><tr><th scope="col">Run</th><th scope="col">Ended</th>{cols.map((c) => <th key={c} scope="col">{c}</th>)}</tr></thead>
      <tbody>{(prof.recent ?? []).map((r) => <tr key={r.id} title="Open the run in Agent Activity" onClick={rowClick(() => { location.hash = "#/activity?run=" + encodeURIComponent(r.id); })}>
        <td className="ui-mono"><a href={"#/activity?run=" + encodeURIComponent(r.id)}>{r.id.slice(0, 12)}…</a></td><td>{(ENDED[r.status] ?? r.status) + " · " + when(r.created)}</td>
        {cols.map((c) => <td key={c} className="cc-clip" title={shown(r.outputs[c])}>{shown(r.outputs[c])}</td>)}</tr>)}
        {!prof.recent?.length && <tr className="is-static"><td colSpan={2 + cols.length} className="cc-none">No run returned values in the period.</td></tr>}</tbody>
    </table></div></div>
  </div>;
}

function Profile({ prof }: { prof: VariableProfile }) {
  return <div className="cc-agent-tab">
    <div className="cc-list-panel"><div className="cc-list-scroll"><table className="cc-list-table" aria-label="Data profile">
      <thead><tr>{["Value", "Kind"].map((h) => <th key={h} scope="col">{h}</th>)}{["Runs with it", "Smallest", "Largest", "Average", "Total", "Different values"].map((h) => <th key={h} scope="col" className="cc-num">{h}</th>)}
        <th scope="col">Most common</th></tr></thead>
      <tbody>{prof.variables.map((v) => <tr key={v.variable} className="is-static">
        <td><strong>{v.variable}</strong></td><td>{v.kind ?? ""}</td><td className="cc-num">{v.runs_with_value} of {v.runs}</td>
        <td className="cc-num">{num(v.min)}</td><td className="cc-num">{num(v.max)}</td><td className="cc-num">{num(v.avg)}</td><td className="cc-num">{num(v.sum)}</td><td className="cc-num">{v.distinct ?? ""}</td>
        <td className="cc-clip">{(v.common ?? []).slice(0, 3).map((c) => c.value + " (" + c.runs + ")").join(", ")}</td></tr>)}
        {!prof.variables.length && <tr className="is-static"><td colSpan={9} className="cc-none">No values in the period.</td></tr>}</tbody>
    </table></div></div>
    <div className="cc-ops-charts cc-ins-charts cc-charts-flush">
      {prof.variables.filter((v) => v.common?.length).slice(0, 3).map((v) => <div key={v.variable} className="cc-card cc-card-flat"><h2>{v.variable}: how often each value</h2>
        <Bars label={v.variable + ": how often each value"} items={(v.common ?? []).map((c) => ({ label: c.value, value: c.runs }))} /></div>)}
    </div>
  </div>;
}

/** This agent against another agent, or against itself in the period before (UiPath's and AA's Compare). */
function Compare({ api, path, agents, days, stats, prof }: { api: CrApi; path: string; agents: string[]; days: string; stats: AgentStats; prof: VariableProfile }) {
  const [other, setOther] = useState(agents.find((a) => a !== path) ?? path);
  const [when2, setWhen2] = useState<"same" | "before">(agents.some((a) => a !== path) ? "same" : "before");
  const [b, setB] = useState<{ stats: AgentStats; prof: VariableProfile } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const d = Number(days) * 86_400_000, now = Date.now();
    const p: Record<string, string> = when2 === "same" ? { path: other, days, tz } : { path: other, from: new Date(now - 2 * d).toISOString(), to: new Date(now - d).toISOString(), tz };
    setB(null);
    Promise.all([api.agentStatsFor(p), api.agentVariablesFor(p)]).then(([s, v]) => { setB({ stats: s, prof: v }); setError(null); }, (e) => setError(message(e)));
  }, [api, other, when2, days]);
  const label = (p: string, w: "same" | "before") => name(p) + (w === "before" ? " (the period before)" : "");
  const sideA = name(path), sideB = label(other, when2);
  const shared = useMemo(() => !b ? [] : prof.variables.filter((v) => v.sum != null).map((v) => [v, b.prof.variables.find((x) => x.variable === v.variable && x.sum != null)] as const).filter(([, x]) => x), [b, prof]);
  const diff = (x: number | null | undefined, y: number | null | undefined, unit = "") => x == null || y == null ? "" : x === y ? "same" : (x > y ? "+" : "") + num(x - y) + unit;
  const rows: [string, string, string, string][] = !b ? [] : [
    ["Runs", String(stats.totals.runs), String(b.stats.totals.runs), diff(stats.totals.runs, b.stats.totals.runs)],
    ["Success rate (of finished)", rate(stats.totals.success_rate), rate(b.stats.totals.success_rate), diff(stats.totals.success_rate, b.stats.totals.success_rate, " points")],
    ["Failed runs", String(stats.totals.failed), String(b.stats.totals.failed), diff(stats.totals.failed, b.stats.totals.failed)],
    ["Average run", dur(stats.totals.avg_ms), dur(b.stats.totals.avg_ms), stats.totals.avg_ms != null && b.stats.totals.avg_ms != null ? (stats.totals.avg_ms <= b.stats.totals.avg_ms ? dur(b.stats.totals.avg_ms - stats.totals.avg_ms) + " faster" : dur(stats.totals.avg_ms - b.stats.totals.avg_ms) + " slower") : ""],
    ["Runs with outputs", String(prof.runs), String(b.prof.runs), diff(prof.runs, b.prof.runs)],
    ["Most common error", stats.errors[0]?.code ?? "", b.stats.errors[0]?.code ?? "", ""],
    ...shared.map(([v, x]) => [v.variable + " · total", num(v.sum), num(x!.sum), diff(v.sum, x!.sum)] as [string, string, string, string])];
  return <div className="cc-agent-tab">
    <div className="cc-list-filters" role="group" aria-label="Compare with">
      <span>Compare {sideA} with</span>
      <Select aria-label="Other agent" value={other} onChange={(e) => setOther(e.target.value)} options={[...new Set([path, ...agents])].map((a) => ({ value: a, label: a === path ? "This agent" : name(a) }))} />
      <Select aria-label="Its period" value={when2} onChange={(e) => setWhen2(e.target.value as "same" | "before")} options={[{ value: "same", label: "The same period" }, { value: "before", label: "The period before" }]} />
    </div>
    {error && <p className="st-alert" role="alert">{error}</p>}
    {!b && !error && <p className="st-muted" role="status">Loading…</p>}
    {b && <div className="cc-list-panel"><div className="cc-list-scroll"><table className="cc-list-table" aria-label="Compare">
      <thead><tr><th scope="col">Figure</th><th scope="col" className="cc-num">{sideA}</th><th scope="col" className="cc-num">{sideB}</th><th scope="col" className="cc-num">Difference</th></tr></thead>
      <tbody>{rows.map(([f, x, y, d]) => <tr key={f} className="is-static"><td>{f}</td><td className="cc-num">{x}</td><td className="cc-num">{y}</td><td className="cc-num">{d}</td></tr>)}</tbody>
    </table></div></div>}
  </div>;
}
