import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { AiCall, CrApi, CrUser, Run, RunEvent, Schedule } from "../api/cr";
import { Button, Dialog, Select, TextInput, usePersistent } from "../components";
import { RefreshButton } from "./ListParts";
import { repeats } from "./Schedules";

/**
 * Manage > Agent Activity, built as AA's Activity (In progress, Scheduled, Historical), with UiPath Jobs' job details
 * (input and output arguments, the log, why it ended) and Blue Prism's live session log and request-stop (checked
 * against their official docs, 31 Oct 2026). A summary row; three tabs: In progress (queued and running, refreshed
 * every 5 seconds), Scheduled (each schedule's next run) and Completed (a chosen period, the last 90 days unless
 * changed); filters; a column chooser; selected runs paused, resumed, stopped, run again or exported; a run's view
 * with its progress, steps, log, variables, inputs and outputs and AI calls, followed live while it runs.
 * A run opens from a link: #/activity?run=<id>.
 * Laid out as the design plan's step 7 (approved on the canvas 31 Oct 2026): the list pattern with the summary as one
 * strip of figures, completed runs grouped by day with why each ended, and a run in a side panel beside the list
 * (Newer / Older; why it failed first, with its step and whether a retry helps; the steps as a nested list).
 */
type Tab = "progress" | "scheduled" | "completed";
type Names = Record<string, string>;
type Col = { key: string; label: string; cell: (r: Run) => string };

const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const SOURCES: [string, string][] = [["manual", "Run now"], ["schedule", "Schedule"], ["trigger", "Trigger"], ["queue", "Work queue"], ["api", "API"], ["rerun", "Run again"], ["mcp", "AI assistant (MCP)"]];
export function howStarted(src?: string) {
  const s = String(src ?? "");
  const hit = SOURCES.find(([k]) => s === k || s.startsWith(k + ":"));
  if (hit) return hit[1];
  if (s.startsWith("webhook") || s.startsWith("file")) return "Trigger";
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : "";
}
const STATUS: Record<string, string> = { queued: "Queued", running: "Running", paused: "Paused", stopping: "Stopping", success: "Completed", failed: "Failed", stopped: "Stopped" };
export const runState = (r: Run) => r.status === "running" && r.pause_requested ? "paused" : r.status === "running" && r.cancel_requested ? "stopping" : r.status;
const isActive = (r: Run) => r.status === "queued" || r.status === "running";
function duration(ms?: number | null) {
  if (ms == null) return "";
  const s = Math.round(ms / 1000);
  return s < 60 ? s + " s" : s < 3600 ? Math.floor(s / 60) + " min " + (s % 60) + " s" : Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min";
}
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const startedBy = (r: Run) => (r.requested_by ?? "") + (r.run_as && r.run_as !== r.requested_by ? " as " + r.run_as : "");
type RunError = { code?: string; message?: string; stepId?: string; suggestedFix?: string };
function parseError(e?: string | null): RunError | null {
  if (!e) return null;
  try { const o = JSON.parse(e); return typeof o === "object" && o ? o : { message: String(o) }; } catch { return { message: e }; }
}
function errorText(e?: string | null) { const o = parseError(e); return o ? (o.code ? o.code + ": " : "") + (o.message ?? "") : ""; }
const actionLabel = (names: Names, a?: unknown) => a == null ? "" : names[String(a)] ?? String(a);
const json = (v: unknown) => { if (typeof v === "string") { try { v = JSON.parse(v); } catch { return v as string; } } return JSON.stringify(v ?? {}, null, 2); };

const progressCols = (names: Names): Col[] => [
  { key: "status", label: "Status", cell: (r) => STATUS[runState(r)] ?? r.status },
  { key: "agent", label: "Agent", cell: (r) => name(r.bot_path) + (r.version ? " v" + r.version : "") },
  { key: "progress", label: "Progress", cell: (r) => r.progress ? `Step ${Math.min(r.progress.finished + 1, r.progress.total || r.progress.finished + 1)}${r.progress.total ? " of " + r.progress.total : ""}${r.progress.action ? ": " + actionLabel(names, r.progress.action) : ""}` : r.status === "queued" ? "Waiting for the runner" : "" },
  { key: "runner", label: "Runner", cell: (r) => r.device_name ?? r.device_id ?? "" },
  { key: "by", label: "Started by", cell: startedBy },
  { key: "how", label: "How started", cell: (r) => howStarted(r.source) },
  { key: "queued", label: "Queued", cell: (r) => when(r.created) },
  { key: "started", label: "Started", cell: (r) => when(r.started) },
];
const COMPLETED_COLS: Col[] = [
  { key: "status", label: "Status", cell: (r) => STATUS[r.status] ?? r.status },
  { key: "agent", label: "Agent", cell: (r) => name(r.bot_path) + (r.version ? " v" + r.version : "") },
  { key: "runner", label: "Runner", cell: (r) => r.device_name ?? r.device_id ?? "" },
  { key: "by", label: "Started by", cell: startedBy },
  { key: "how", label: "How started", cell: (r) => howStarted(r.source) },
  { key: "started", label: "Started", cell: (r) => when(r.started ?? r.created) },
  { key: "ended", label: "Ended", cell: (r) => when(r.finished) },
  { key: "duration", label: "Took", cell: (r) => duration(r.duration_ms) },
  { key: "steps", label: "Steps", cell: (r) => r.steps == null ? "" : String(r.steps) },
  { key: "error", label: "Why it ended", cell: (r) => errorText(r.error) },
];
const PERIODS: [string, string, number][] = [["24h", "Last 24 hours", 1], ["7d", "Last 7 days", 7], ["", "Last 90 days", 90], ["dates", "Chosen dates", 0]];
const PAGE = 25;
const folderOf = (path: string) => path.split("/").slice(0, -1).join("/");
const PILL: Record<string, string> = { queued: "pending", running: "active", paused: "pending", stopping: "pending", success: "success", failed: "failed", stopped: "stopped" };
const pad = (n: number) => String(n).padStart(2, "0");
const dayOf = (t?: string | null) => { if (!t) return ""; const d = new Date(t); return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()); };
const dayLabel = (t: string) => new Date(t).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
const DAY = 86_400_000;
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
function save(fileName: string, text: string, type: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type }));
  a.download = fileName;
  document.body.appendChild(a); a.click(); a.remove();
}
/** What the address asks for: #/activity?run=<id> opens a run; tab, status, period, q (search) and bot set the list. */
const linked = () => new URLSearchParams(location.hash.split("?")[1] ?? "");
const linkedRun = () => linked().get("run");
const linkedFilters = () => { const l = linked(); return Object.fromEntries(["status", "period", "q", "bot"].filter((k) => l.get(k)).map((k) => [k, l.get(k)!])); };

type Summary = { running: number; queued: number; failed: number; ended: number; succeeded: number; next: Schedule | null; schedulesOn: number | null };

export function Activity({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [stored, setTabState] = usePersistent<Tab>("activity.tab", "progress");
  const tab: Tab = stored === "scheduled" && !user.permissions.includes("schedules.view") ? "progress" : stored;
  const [hidden, setHidden] = usePersistent<Record<string, string[]>>("activity.hidden", { progress: [], completed: ["steps"] });
  const [f, setF] = useState<Record<string, string>>(linkedFilters);
  const [offset, setOffset] = useState(0);
  const [data, setData] = useState<{ runs: Run[]; total: number } | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [agents, setAgents] = useState<string[]>([]);
  const [runners, setRunners] = useState<{ id: string; name: string }[]>([]);
  const [names, setNames] = useState<Names>({});
  const [choosing, setChoosing] = useState(false);
  const [more, setMore] = useState(false);
  const [details, setDetails] = useState<string | null>(linkedRun);
  const can = (p: string) => user.permissions.includes(p);
  const allCols = useMemo(() => tab === "progress" ? progressCols(names) : COMPLETED_COLS, [tab, names]);
  const cols = allCols.filter((c) => !(hidden[tab] ?? []).includes(c.key));

  useEffect(() => {   // a tab named in the address wins over the one remembered; the address then keeps only a run
    const t = linked().get("tab");
    if (t === "progress" || t === "completed" || t === "scheduled") setTabState(t);
    const run = linkedRun();
    if (location.hash.includes("?")) history.replaceState(null, "", "#/activity" + (run ? "?run=" + encodeURIComponent(run) : ""));
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (can("repo.view")) api.agents().then((b) => setAgents(b.map((x) => x.path)), () => {});
    if (can("devices.view")) api.runners().then(setRunners, () => {});
    api.actionNames().then(setNames, () => {});
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps

  /** Opens a tab, with the filters given (a tab's own status filter does not carry over to another tab). */
  const go = (t: Tab, filters?: Record<string, string>) => {
    setTabState(t); setOffset(0); setSelected([]);
    if (t !== tab) setData(null);
    setF((o) => { const { status: _s, period: _p, ...rest } = o; return filters ?? (t === tab ? o : rest); });
  };

  /** The query for the tab: Completed covers the chosen period, the last 90 days unless changed. */
  const params = useCallback((limit: number, off: number) => {
    const p: Record<string, string> = { filter: tab === "progress" ? "active" : "history", limit: String(limit), offset: String(off) };
    for (const [k, v] of Object.entries(f)) if (v && !["from", "to", "period", "state"].includes(k)) p[k] = v;
    if (tab === "completed") {
      const day = (v: string, next: boolean) => { const d = new Date(v + "T00:00:00"); if (next) d.setDate(d.getDate() + 1); return d.toISOString(); };
      if (f.period === "dates") { if (f.from) p.from = day(f.from, false); if (f.to) p.to = day(f.to, true); }
      else p.from = new Date(Date.now() - (PERIODS.find(([k]) => k === (f.period ?? ""))?.[2] ?? 90) * DAY).toISOString();
    }
    return p;
  }, [tab, f]);

  const load = useCallback(async () => {
    if (tab === "scheduled") return;
    try {
      const r = await api.runs(params(tab === "progress" ? 200 : PAGE, tab === "progress" ? 0 : offset));
      setData(r); setError(null);
      setSelected((s) => s.filter((id) => r.runs.some((x) => x.id === id)));
    } catch (e) { setError(message(e)); }
  }, [api, params, tab, offset]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (tab !== "progress") return;
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [tab, load]);

  /** The figures on top: running and waiting now, the last 24 hours, and the next scheduled run. */
  const loadSummary = useCallback(async () => {
    const since = new Date(Date.now() - DAY).toISOString();
    const n = (p: Record<string, string>) => api.runs({ ...p, limit: "1" }).then((r) => r.total, () => 0);
    const [running, queued, failed, ended, succeeded, schedules] = await Promise.all([n({ filter: "active", status: "running" }), n({ filter: "active", status: "queued" }),
      n({ filter: "history", status: "failed", from: since }), n({ filter: "history", from: since }), n({ filter: "history", status: "success", from: since }),
      can("schedules.view") ? api.schedules().catch(() => null) : Promise.resolve(null)]);
    const on = (schedules ?? []).filter((s) => s.enabled && s.next_run).sort((a, b) => Date.parse(a.next_run!) - Date.parse(b.next_run!));
    setSummary({ running, queued, failed, ended, succeeded, next: on[0] ?? null, schedulesOn: schedules ? schedules.filter((s) => s.enabled).length : null });
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { loadSummary(); const t = setInterval(loadSummary, 15000); return () => clearInterval(t); }, [loadSummary]);

  const runs = data?.runs ?? [];
  const chosen = runs.filter((r) => selected.includes(r.id));
  const setFilter = (k: string, v: string) => { setF({ ...f, [k]: v }); setOffset(0); };
  const act = async (what: "pause" | "resume" | "stop" | "rerun") => {
    let done = 0;
    for (const r of chosen) { try { if (what === "rerun") await api.rerun(r.id); else await api.control(r.id, what); done++; } catch (e) { setError(message(e)); } }
    const verb = { pause: "Pause asked for", resume: "Resumed", stop: "Stop asked for", rerun: "Started again" }[what];
    onNotice(verb + " " + done + " run" + (done === 1 ? "" : "s") + (what === "pause" || what === "stop" ? " (after the current step)" : "") + ".");
    setSelected([]); load(); loadSummary();
  };
  /** CSV of the chosen runs, or of every run the filters find (read 500 at a time). */
  const exportCsv = async () => {
    let rows: Run[] = chosen;
    if (!rows.length) {
      rows = [];
      for (let off = 0; ; off += 500) {
        const r = await api.runs(params(500, off));
        rows.push(...r.runs);
        if (rows.length >= r.total || !r.runs.length || rows.length >= 100_000) break;
      }
    }
    save(tab === "progress" ? "runs-in-progress.csv" : "runs-completed.csv",
      [["Run id", ...allCols.map((c) => c.label)], ...rows.map((r) => [r.id, ...allCols.map((c) => c.cell(r))])].map((l) => l.map(csvCell).join(",")).join("\n"), "text/csv");
    onNotice("Exported " + rows.length + " run" + (rows.length === 1 ? "" : "s") + ".");
  };
  const openRun = (id: string | null) => {
    setDetails(id);
    const base = location.hash.split("?")[0] || "#/activity";
    history.replaceState(null, "", id ? base + "?run=" + encodeURIComponent(id) : base);
  };
  const counts: Record<Tab, number | null> = { progress: summary ? summary.running + summary.queued : null, scheduled: summary?.schedulesOn ?? null, completed: null };
  const tabs: [Tab, string][] = [["progress", "In progress"], ...(can("schedules.view") ? [["scheduled", "Scheduled"] as [Tab, string]] : []), ["completed", "Completed"]];

  const filtered = Object.values(f).some(Boolean);
  const periodWords = (PERIODS.find(([k]) => k === (f.period ?? ""))?.[1] ?? "").toLowerCase();
  const byOutcome = data && data.runs.length === data.total && tab === "completed"
    ? ([["success", "completed"], ["failed", "failed"], ["stopped", "stopped"]] as const).map(([k, l]) => [runs.filter((r) => r.status === k).length, l] as const).filter(([n]) => n > 0) : [];
  const cell = (c: Col, r: Run) => {
    if (c.key === "status") { const st = runState(r); return <span className={"cc-pill is-" + (PILL[st] ?? "pending")}><span className="cc-pill-dot" />{STATUS[st] ?? r.status}</span>; }
    if (c.key === "agent") return <><strong>{c.cell(r)}</strong>{folderOf(r.bot_path) && <span className="cc-list-sub">{folderOf(r.bot_path)}</span>}</>;
    if (c.key === "error") { const e = parseError(r.error); return e ? <span className="cc-run-why" title={errorText(r.error)}>{errorText(r.error)}</span> : <span className="cc-none">–</span>; }
    const v = c.cell(r);
    return v || <span className="cc-none">–</span>;
  };
  const rows: ReactNode[] = [];
  runs.forEach((r, i) => {
    const t = r.started ?? r.created;
    if (tab === "completed" && t && (i === 0 || dayOf(t) !== dayOf(runs[i - 1].started ?? runs[i - 1].created)))
      rows.push(<tr key={"d" + r.id} className="cc-au-day is-static"><td colSpan={cols.length + 1}>{dayLabel(t)}</td></tr>);
    rows.push(
      <tr key={r.id} className={(selected.includes(r.id) ? "is-selected" : "") + (details === r.id ? " is-open" : "")} onClick={(e) => { if (!(e.target as HTMLElement).closest("input")) openRun(r.id); }}>
        <td className="cc-check"><input type="checkbox" aria-label={"Select the run of " + name(r.bot_path)} checked={selected.includes(r.id)}
          onChange={(e) => setSelected(e.target.checked ? [...selected, r.id] : selected.filter((x) => x !== r.id))} /></td>
        {cols.map((c) => <td key={c.key} className={c.key === "duration" || c.key === "steps" ? "cc-num" : c.key === "error" ? "cc-clip" : undefined}>{cell(c, r)}</td>)}
      </tr>);
  });
  const at = details ? runs.findIndex((r) => r.id === details) : -1;

  return (
    <section className={"st-page cc-home cc-list" + (details ? " has-run" : "")} aria-labelledby="cc-act-title">
      <header className="cc-list-head">
        <h1 id="cc-act-title">Agent Activity</h1>
        <span className="ov-info" role="img" aria-label="About this page" title="What is running now, what runs next on a schedule, and every run that has ended.">i</span>
        {data && tab === "completed" && <span className="cc-list-count">{data.total} run{data.total === 1 ? "" : "s"} {f.period === "dates" ? "between the chosen dates" : "in the " + periodWords}
          {byOutcome.map(([n, l]) => " · " + n + " " + l).join("")}</span>}
        {summary && tab === "progress" && <span className="cc-list-count">{summary.running} running · {summary.queued} waiting for a runner</span>}
        <span className="ov-fill" />
      </header>
      {summary && <div className="cc-sums" role="group" aria-label="Summary">
        <button type="button" className="cc-sum" onClick={() => go("progress", { status: "running" })}><strong>{summary.running}</strong><span>Running now</span></button>
        <button type="button" className="cc-sum" onClick={() => go("progress", { status: "queued" })}><strong>{summary.queued}</strong><span>Waiting for a runner</span></button>
        <button type="button" className="cc-sum" onClick={() => go("completed", { period: "24h", status: "failed" })}>
          <strong className={summary.failed ? "cc-err" : undefined}>{summary.failed}</strong><span>Failed in the last 24 hours</span></button>
        <button type="button" className="cc-sum" onClick={() => go("completed", { period: "24h" })}><strong>{summary.ended}</strong>
          <span>Ended in the last 24 hours{summary.ended ? " · " + Math.round(100 * summary.succeeded / summary.ended) + "% completed" : ""}</span></button>
        {summary.schedulesOn != null && <button type="button" className="cc-sum" onClick={() => go("scheduled")}>
          <strong className="cc-sum-text">{summary.next ? new Date(summary.next.next_run!).toLocaleString() : "None"}</strong>
          <span>{summary.next ? "Next scheduled: " + name(summary.next.bot_path) : "No schedule is on"}</span></button>}
      </div>}
      <div className="ov-tabs cc-list-tabs" role="tablist" aria-label="Agent Activity">
        {tabs.map(([t, label]) => <button key={t} type="button" role="tab" className="ov-tab" aria-selected={tab === t} onClick={() => go(t)}>
          {label}{counts[t] != null && <span className="ov-cnt">{counts[t]}</span>}</button>)}
      </div>
      {tab === "scheduled" ? <Upcoming api={api} user={user} agents={agents} onNotice={onNotice} onStarted={(id) => { loadSummary(); openRun(id); }} /> : <>
        <div className="cc-list-tools">
          <div className="cc-list-filters" role="group" aria-label="Filters">
            <span className="cc-list-search"><TextInput type="search" aria-label="Search runs" placeholder="Search agent, run id, user or error" value={f.q ?? ""} onChange={(e) => setFilter("q", e.target.value)} /></span>
            {tab === "completed" && <span className={"cc-chip" + (f.period ? " is-on" : "")}><Select aria-label="Period" value={f.period ?? ""} onChange={(e) => setFilter("period", e.target.value)}
              options={PERIODS.map(([v, l]) => ({ value: v, label: "Period: " + l }))} /></span>}
            <span className={"cc-chip" + (f.status ? " is-on" : "")}><Select aria-label="Status" value={f.status ?? ""} onChange={(e) => setFilter("status", e.target.value)}
              options={[{ value: "", label: "Status: Any" }, ...(tab === "progress" ? [["queued", "Queued"], ["running", "Running"]] : [["success", "Completed"], ["failed", "Failed"], ["stopped", "Stopped"]]).map(([v, l]) => ({ value: v, label: "Status: " + l }))]} /></span>
            {agents.length > 0 && <span className={"cc-chip" + (f.bot ? " is-on" : "")}><Select aria-label="Agent" value={f.bot ?? ""} onChange={(e) => setFilter("bot", e.target.value)}
              options={[{ value: "", label: "Agent: Any" }, ...agents.map((p) => ({ value: p, label: "Agent: " + name(p) }))]} /></span>}
            {runners.length > 0 && <span className={"cc-chip" + (f.device ? " is-on" : "")}><Select aria-label="Runner" value={f.device ?? ""} onChange={(e) => setFilter("device", e.target.value)}
              options={[{ value: "", label: "Runner: Any" }, ...runners.map((d) => ({ value: d.id, label: "Runner: " + d.name }))]} /></span>}
            <span className={"cc-chip" + (f.source ? " is-on" : "")}><Select aria-label="How started" value={f.source ?? ""} onChange={(e) => setFilter("source", e.target.value)}
              options={[{ value: "", label: "How started: Any" }, ...SOURCES.map(([v, l]) => ({ value: v, label: "How started: " + l }))]} /></span>
            <Button kind="plain" aria-expanded={more || !!f.user || f.period === "dates"} onClick={() => setMore(!more)}>{more ? "Fewer filters" : "More filters"}</Button>
            {filtered && <Button kind="plain" onClick={() => { setF({}); setOffset(0); }}>Clear filters</Button>}
          </div>
          <div className="cc-list-right" role="toolbar" aria-label="Runs list tools">
            <Button kind="plain" onClick={() => setChoosing(true)}>Columns</Button>
            <Button kind="plain" onClick={() => exportCsv().catch((e) => setError(message(e)))}>{chosen.length ? "Export selected" : "Export CSV"}</Button>
            <RefreshButton onClick={() => { load(); loadSummary(); }} />
          </div>
        </div>
        {(more || !!f.user || f.period === "dates") && <div className="cc-list-filters cc-au-more" role="group" aria-label="More filters">
          <TextInput aria-label="Started by" placeholder="Started by (user name)" value={f.user ?? ""} onChange={(e) => setFilter("user", e.target.value)} />
          {tab === "completed" && f.period === "dates" && <>
            <label className="cc-date">From <TextInput type="date" aria-label="From" value={f.from ?? ""} onChange={(e) => setFilter("from", e.target.value)} /></label>
            <label className="cc-date">To <TextInput type="date" aria-label="To" value={f.to ?? ""} onChange={(e) => setFilter("to", e.target.value)} /></label>
          </>}
        </div>}
        {error && <p className="st-alert" role="alert">{error}</p>}
        <div className="cc-list-panel">
          {chosen.length > 0 && <div className="cc-list-bulk" role="toolbar" aria-label="Runs actions">
            <strong>{chosen.length} selected</strong>
            {tab === "progress" && can("runs.stop") && <>
              <Button kind="secondary" onClick={() => act("pause")}>Pause</Button>
              <Button kind="secondary" onClick={() => act("resume")}>Resume</Button>
              <Button kind="danger" onClick={() => act("stop")}>Stop</Button>
            </>}
            {tab === "completed" && can("runs.start") && <Button kind="secondary" onClick={() => act("rerun")}>Run again</Button>}
            <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button>
          </div>}
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label={tab === "progress" ? "Runs in progress" : "Completed runs"}>
              <thead><tr>
                <th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={runs.length > 0 && chosen.length === runs.length}
                  onChange={(e) => setSelected(e.target.checked ? runs.map((r) => r.id) : [])} /></th>
                {cols.map((c) => <th key={c.key} scope="col" className={c.key === "duration" || c.key === "steps" ? "cc-num" : undefined}>{c.label}</th>)}
              </tr></thead>
              <tbody>
                {rows}
                {data && !runs.length && <tr className="is-static"><td colSpan={cols.length + 1} className="cc-none">{tab === "progress" ? "Nothing is running or waiting." : "No runs match these filters."}</td></tr>}
                {!data && !error && <tr className="is-static"><td colSpan={cols.length + 1} className="cc-none" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          {data && <div className="cc-list-pager">
            <span>{tab === "progress" ? runs.length + " running or waiting · refreshes every 5 seconds"
              : (data.total ? (offset + 1) + "–" + Math.min(offset + PAGE, data.total) + " of " + data.total : "0 of 0") + (filtered ? " matching" : "")}</span>
            {tab === "completed" && <><span className="ov-fill" />
              <button type="button" className="cc-list-icon" aria-label="Previous page" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>‹</button>
              <button type="button" className="cc-list-icon" aria-label="Next page" disabled={offset + PAGE >= data.total} onClick={() => setOffset(offset + PAGE)}>›</button></>}
          </div>}
        </div>
      </>}
      {choosing && (
        <Dialog open title="Columns" width={360} onClose={() => setChoosing(false)} footer={<Button kind="primary" onClick={() => setChoosing(false)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {allCols.map((c) => (
              <label key={c.key} className="st-check-line"><input type="checkbox" checked={!(hidden[tab] ?? []).includes(c.key)} disabled={c.key === "agent"}
                onChange={(e) => setHidden({ ...hidden, [tab]: e.target.checked ? (hidden[tab] ?? []).filter((k) => k !== c.key) : [...(hidden[tab] ?? []), c.key] })} /> {c.label}</label>
            ))}
          </div>
        </Dialog>
      )}
      {details && <RunView api={api} user={user} id={details} names={names} onClose={() => openRun(null)} onOpen={openRun} onNotice={onNotice}
        onChanged={() => { load(); loadSummary(); }} newer={at > 0 ? runs[at - 1].id : null} older={at >= 0 && at < runs.length - 1 ? runs[at + 1].id : null}
        onSearch={(q) => { openRun(null); go("completed", { q }); }} />}
    </section>
  );
}

/**
 * Scheduled, as AA's Activity > Scheduled: each schedule's next run, soonest first (schedules that are off last).
 * Selected schedules turned on or off, or run now; they are made and changed on the Schedules page.
 */
function Upcoming({ api, user, agents, onNotice, onStarted }: { api: CrApi; user: CrUser; agents: string[]; onNotice: (t: string) => void; onStarted: (runId: string) => void }) {
  const [list, setList] = useState<Schedule[] | null>(null);
  const [targets, setTargets] = useState<Record<string, string>>({});
  const [q, setQ] = useState("");
  const [bot, setBot] = useState("");
  const [state, setState] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(() => api.schedules().then((s) => { setList(s); setError(null); setSelected((o) => o.filter((id) => s.some((x) => x.id === id))); }, (e) => setError(message(e))), [api]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    Promise.all([api.runners().catch(() => []), api.pools().catch(() => [])]).then(([d, p]) =>
      setTargets(Object.fromEntries([...d.map((x) => ["d:" + x.id, x.name]), ...p.map((x) => ["p:" + x.id, x.name + " (pool)"])])));
  }, [api]);
  const shown = useMemo(() => (list ?? []).filter((s) => (!bot || s.bot_path === bot) && (!state || (state === "on") === s.enabled)
    && (!q.trim() || (s.name + " " + s.bot_path).toLowerCase().includes(q.trim().toLowerCase())))
    .sort((a, b) => Number(b.enabled) - Number(a.enabled) || (a.next_run ? Date.parse(a.next_run) : Infinity) - (b.next_run ? Date.parse(b.next_run) : Infinity)), [list, bot, state, q]);
  const chosen = shown.filter((s) => selected.includes(s.id));
  const runsOn = (s: Schedule) => s.pool_id ? targets["p:" + s.pool_id] ?? s.pool_id : s.device_id ? targets["d:" + s.device_id] ?? s.device_id : "";
  const turn = (on: boolean) => api.bulkSchedules(chosen.map((s) => s.id), on ? "enable" : "disable")
    .then((r) => { onNotice("Turned " + (on ? "on " : "off ") + r.changed + " schedule" + (r.changed === 1 ? "" : "s") + "."); setSelected([]); load(); }, (e) => setError(message(e)));
  const runNow = async () => {
    let last = "";
    for (const s of chosen) { try { last = (await api.runSchedule(s.id)).run_id; } catch (e) { setError(message(e)); } }
    setSelected([]);
    if (last) { onNotice("Started " + chosen.length + " run" + (chosen.length === 1 ? "" : "s") + "."); if (chosen.length === 1) onStarted(last); }
  };
  return <>
    <div className="cc-filters" role="group" aria-label="Filters">
      <TextInput type="search" aria-label="Search schedules" placeholder="Search schedule or agent" value={q} onChange={(e) => setQ(e.target.value)} />
      {agents.length > 0 && <Select aria-label="Agent" value={bot} onChange={(e) => setBot(e.target.value)} options={[{ value: "", label: "Any agent" }, ...agents.map((p) => ({ value: p, label: name(p) }))]} />}
      <Select aria-label="State" value={state} onChange={(e) => setState(e.target.value)} options={[{ value: "", label: "On and off" }, { value: "on", label: "On" }, { value: "off", label: "Off" }]} />
    </div>
    <div className="cc-bar" role="toolbar" aria-label="Schedules actions">
      {chosen.length > 0 && <span className="cc-chosen">{chosen.length} selected</span>}
      {can("runs.start") && <Button kind="secondary" disabled={!chosen.length} onClick={runNow}>Run now</Button>}
      {can("schedules.manage") && <>
        <Button kind="secondary" disabled={!chosen.length} onClick={() => turn(true)}>Turn on</Button>
        <Button kind="secondary" disabled={!chosen.length} onClick={() => turn(false)}>Turn off</Button>
      </>}
      <span className="st-bar-fill" />
      <Button kind="plain" onClick={load}>Refresh</Button>
      <a className="ui-btn ui-btn-plain" href="#/schedules">Make or change schedules</a>
    </div>
    {error && <p className="st-alert" role="alert">{error}</p>}
    <div className="cc-list-panel"><div className="cc-list-scroll">
      <table className="cc-list-table" aria-label="Scheduled runs">
        <thead><tr>
          <th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={shown.length > 0 && chosen.length === shown.length}
            onChange={(e) => setSelected(e.target.checked ? shown.map((s) => s.id) : [])} /></th>
          {["Next run", "Agent", "Schedule", "Repeats", "Runs on", "Run as", "Holidays", "State"].map((h) => <th key={h} scope="col">{h}</th>)}
        </tr></thead>
        <tbody>
          {shown.map((s) => (
            <tr key={s.id} className={"is-static" + (selected.includes(s.id) ? " is-selected" : "")}>
              <td className="cc-check"><input type="checkbox" aria-label={"Select the schedule " + s.name} checked={selected.includes(s.id)}
                onChange={(e) => setSelected(e.target.checked ? [...selected, s.id] : selected.filter((x) => x !== s.id))} /></td>
              <td>{s.enabled && s.next_run ? when(s.next_run) : "Not planned"}</td>
              <td>{name(s.bot_path)}</td><td>{s.name}</td><td>{repeats(s)}</td><td>{runsOn(s)}</td><td>{s.run_as ?? s.created_by ?? ""}</td>
              <td>{s.holiday_calendar ? "Skips " + s.holiday_calendar : ""}</td>
              <td className={"cc-status is-" + (s.enabled ? "success" : "stopped")}>{s.enabled ? "On" : "Off"}</td>
            </tr>
          ))}
          {list && !shown.length && <tr className="is-static"><td colSpan={9} className="cc-none">{list.length ? "No schedule matches." : "No schedules yet: make one on the Schedules page."}</td></tr>}
          {!list && !error && <tr className="is-static"><td colSpan={9} className="cc-none" role="status">Loading…</td></tr>}
        </tbody>
      </table>
    </div></div>
  </>;
}

type RunTab = "steps" | "log" | "vars" | "io" | "ai";
type Step = { id: string; action?: unknown; status: "running" | "done" | "failed"; ms?: number; retries: number; error?: string; depth?: number };
const seconds = (t?: number | string) => typeof t === "number" ? t : t ? Date.parse(t) / 1000 : 0;

/**
 * A run's view, as UiPath's job details and Blue Prism's session log: how it stands (progress, or why it ended and the
 * fix to try), then its steps, log, variables, inputs and outputs and AI calls. While the run goes on, new events are
 * read every 2 seconds and added (only those after the last one seen).
 */
function RunView({ api, user, id, names, onClose, onOpen, onNotice, onChanged, newer, older, onSearch }: { api: CrApi; user: CrUser; id: string; names: Names; onClose: () => void;
  onOpen: (id: string) => void; onNotice: (t: string) => void; onChanged: () => void; newer: string | null; older: string | null; onSearch: (q: string) => void }) {
  const [run, setRun] = useState<Run | null>(null);
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [ai, setAi] = useState<AiCall[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = usePersistent<RunTab>("activity.runTab", "steps");
  const [show, setShow] = useState("all");
  const [nonce, setNonce] = useState(0);
  const after = useRef(0);
  const can = (p: string) => user.permissions.includes(p);

  useEffect(() => { after.current = 0; setEvents([]); setRun(null); setAi(null); }, [id]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, [onClose]);
  useEffect(() => {
    let stop = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const r = await api.run(id, after.current);
        if (stop) return;
        const fresh = (r.events ?? []).filter((e) => e.seq > after.current);
        if (fresh.length) { after.current = Math.max(...fresh.map((e) => e.seq)); setEvents((o) => [...o, ...fresh]); }
        setRun(r); setError(null);
        if (isActive(r)) timer = setTimeout(tick, 2000);
        else api.runAiCalls(id).then((c) => { if (!stop) setAi(c); }, () => {});
      } catch (e) { if (!stop) setError(message(e)); }
    };
    tick();
    return () => { stop = true; clearTimeout(timer); };
  }, [api, id, nonce]);

  const x = useMemo(() => {
    const steps = new Map<string, Step>(), vars = new Map<string, string>();
    let total = 0, finished = 0, current: Step | null = null;
    const open: string[] = [];
    for (const e of events) {
      const d = (e.data ?? {}) as Record<string, unknown>;
      if (e.type === "run.started" && !total && typeof d.totalSteps === "number" && !d.depth) total = d.totalSteps;
      if (e.stepId && e.type.startsWith("step.")) {
        const s = steps.get(e.stepId) ?? { id: e.stepId, action: d.action, status: "running", retries: 0 };
        if (d.action) s.action = d.action;
        if (e.type === "step.started") { s.status = "running"; current = s; if (s.depth === undefined) s.depth = open.length; if (!open.includes(s.id)) open.push(s.id); }
        if (e.type === "step.finished" || e.type === "step.failed") { const at = open.lastIndexOf(e.stepId); if (at >= 0) open.splice(at, 1); }
        if (e.type === "step.finished") { s.status = "done"; s.ms = Number(d.durationMs ?? 0); finished++; }
        if (e.type === "step.failed") { s.status = "failed"; s.error = (d.code ? d.code + ": " : "") + String(d.message ?? ""); }
        if (e.type === "step.retry") s.retries++;
        steps.set(e.stepId, s);
      }
      if (e.type === "variable.set") vars.set(String(d.name), String(d.value));
    }
    return { steps: [...steps.values()], vars, total, finished, current: current as Step | null, t0: seconds(events[0]?.ts) };
  }, [events]);
  const lines = useMemo(() => events.map((e) => {
    const d = (e.data ?? {}) as Record<string, unknown>;
    const a = actionLabel(names, d.action);
    const text = e.type === "step.started" ? "Started " + a
      : e.type === "step.finished" ? "Finished " + a + (d.durationMs != null ? " (" + duration(Number(d.durationMs)) + ")" : "")
      : e.type === "step.failed" ? "Failed " + a + ": " + (d.code ? d.code + " " : "") + String(d.message ?? "")
      : e.type === "step.retry" ? "Retry " + d.attempt + " after " + d.code
      : e.type === "log" ? String(d.message ?? "")
      : e.type === "variable.set" ? d.name + " = " + d.value
      : e.type === "run.started" ? "Run started" + (d.automation ? " " + d.automation : "")
      : e.type === "run.finished" ? "Run " + d.status + (d.durationMs != null ? " (" + duration(Number(d.durationMs)) + ")" : "")
      : e.type;
    const kind = e.type === "step.failed" || e.type === "step.retry" ? "errors" : e.type === "log" ? "logs" : e.type === "variable.set" ? "vars" : "other";
    return { seq: e.seq, t: e.ts != null ? "+" + (seconds(e.ts) - x.t0).toFixed(2) + "s" : "", step: e.stepId ?? "", text, kind };
  }), [events, names, x.t0]);

  const control = (what: "pause" | "resume" | "stop") => api.control(id, what).then(() => {
    onNotice({ pause: "Pause asked for: the agent pauses after the current step.", resume: "Resumed.", stop: "Stop asked for: the agent stops after the current step." }[what]);
    setNonce((n) => n + 1); onChanged();
  }, (e) => setError(message(e)));
  const again = () => api.rerun(id).then((r) => { onNotice("Started again as run " + r.run_id + "."); onChanged(); onOpen(r.run_id); }, (e) => setError(message(e)));
  const download = () => save(id + ".log", lines.map((l) => l.t + "  " + (l.step ? "[" + l.step + "] " : "") + l.text).join("\r\n") + "\r\n", "text/plain");

  const active = run ? isActive(run) : false;
  const state = run ? runState(run) : "";
  const err = parseError(run?.error);
  const elapsed = run ? run.duration_ms ?? (run.started ? Date.now() - Date.parse(run.started) : null) : null;
  const shown = lines.filter((l) => show === "all" || l.kind === show);
  const tabs: [RunTab, string][] = [["steps", "Steps (" + x.steps.length + ")"], ["log", "Log (" + events.length + ")"], ["vars", "Variables (" + x.vars.size + ")"], ["io", "Inputs and outputs"],
    ...(ai?.length ? [["ai", "AI calls (" + ai.length + ")"] as [RunTab, string]] : [])];
  const open = tabs.some(([t]) => t === tab) ? tab : "steps";

  const errStep = err?.stepId ? x.steps.find((st) => st.id === err.stepId) : undefined;
  const retry = (run?.error ? (() => { try { return (JSON.parse(run.error) as { retryable?: boolean }).retryable; } catch { return undefined; } })() : undefined);
  const title = run ? "Run of " + name(run.bot_path) : "Run";

  return (
    <aside className="cc-run-panel" role="dialog" aria-modal="false" aria-label={title}>
      <div className="cc-run-head">
        {run && <span className={"cc-pill is-" + (PILL[state] ?? "pending")}><span className="cc-pill-dot" />{STATUS[state] ?? run.status}</span>}
        <h2>{run ? name(run.bot_path) : "Run"}</h2>
        <span className="ov-fill" />
        <span className="cc-none ui-mono" title={id}>{id.length > 16 ? id.slice(0, 14) + "…" : id}</span>
        <Button kind="plain" disabled={!newer} onClick={() => newer && onOpen(newer)}>‹ Newer</Button>
        <Button kind="plain" disabled={!older} onClick={() => older && onOpen(older)}>Older ›</Button>
        <button type="button" className="cc-list-icon" aria-label="Close" title="Close" onClick={onClose}>×</button>
      </div>
      <div className="cc-run-body">
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!run && !error && <p className="cc-none" role="status">Loading…</p>}
      {run && <>
        {err && <div className="cc-errbox" role="note" aria-label="Why it ended">
          <div><strong className="cc-run-code">{err.code ?? "Error"}</strong> {err.message}</div>
          {err.stepId && <div className="cc-run-at">At step <strong>{err.stepId}{errStep ? " · " + actionLabel(names, errStep.action) : ""}</strong>.
            {retry === false ? " Not retryable: the same input fails again until the agent changes." : retry ? " Retryable: running it again may work." : ""}</div>}
          {err.suggestedFix && <div>Fix: {err.suggestedFix}</div>}
          {err.code && <span><Button kind="secondary" onClick={() => onSearch(err.code!)}>Every run that failed this way</Button></span>}
        </div>}
        <p className="cc-run-state"><span className="cc-none">{run.status === "queued" ? "Waiting for " + (run.device_name ?? run.device_id ?? "a runner")
            : active ? "Running for " + duration(elapsed) : (run.status === "success" ? "Finished in " : "Ended after ") + duration(elapsed)}</span></p>
        {(active || x.total > 0) && <div className="cc-run-progress">
          <progress max={x.total || 1} value={x.total ? x.finished : run.status === "success" ? 1 : 0} aria-label="Steps done" />
          <span className="cc-none">{x.finished}{x.total ? " of " + x.total : ""} steps{active && x.current ? " · now: " + actionLabel(names, x.current.action) + " [" + x.current.id + "]" : ""}</span>
        </div>}
        <dl className="cc-facts cc-facts-wide">
          <dt>Agent</dt><dd>{run.bot_path}{run.version ? " (version " + run.version + ")" : ""}</dd>
          <dt>Runner</dt><dd>{run.device_name ?? run.device_id ?? ""}</dd>
          <dt>Started by</dt><dd>{startedBy(run)} · {howStarted(run.source)}</dd>
          <dt>Queued</dt><dd>{when(run.created)}</dd>
          <dt>Started</dt><dd>{when(run.started)}</dd>
          <dt>Ended</dt><dd>{when(run.finished)}</dd>
          <dt>Steps run</dt><dd>{run.steps ?? x.finished}</dd>
          <dt>Run id</dt><dd className="ui-mono">{run.id}</dd>
        </dl>
        <div className="ov-tabs cc-list-tabs cc-run-tabs" role="tablist" aria-label="Run details">
          {tabs.map(([t, l]) => <button key={t} type="button" role="tab" className="ov-tab" aria-selected={open === t} onClick={() => setTab(t)}>{l}</button>)}
        </div>
        {open === "steps" && (x.steps.length ? <ol className="cc-run-steps" aria-label="Steps">
          {x.steps.map((s) => <li key={s.id} className={"is-" + s.status} style={{ paddingLeft: 10 + 22 * (s.depth ?? 0) }}>
            <span className={"cc-run-ic is-" + s.status} role="img" aria-label={s.status === "done" ? "Done" : s.status === "failed" ? "Failed" : "Running"}>{s.status === "done" ? "✓" : s.status === "failed" ? "!" : "…"}</span>
            <span className="ui-mono cc-none">{s.id}</span>
            <span className="cc-run-step">{actionLabel(names, s.action)}{s.retries ? <span className="cc-none"> · retried {s.retries}×</span> : null}
              {s.error && <span className="cc-run-err">{s.error}</span>}</span>
            <span className="cc-num cc-none">{s.status === "running" ? "" : duration(s.ms)}</span>
          </li>)}
        </ol> : <p className="cc-none">{active ? "Waiting for the runner to start the agent…" : "No steps were recorded."}</p>)}
        {open === "log" && <>
          <div className="cc-bar cc-bar-tight"><Select aria-label="Show in the log" value={show} onChange={(e) => setShow(e.target.value)}
            options={[{ value: "all", label: "Everything" }, { value: "errors", label: "Errors and retries" }, { value: "logs", label: "Log messages" }, { value: "vars", label: "Variables set" }]} /></div>
          {shown.length ? <ol className="cc-log" aria-label="Steps and log">{shown.map((l) =>
            <li key={l.seq} className={l.kind === "errors" ? "is-bad" : undefined}><span className="cc-log-t">{l.t}</span><span className="cc-log-step">{l.step}</span>{l.text}</li>)}</ol>
            : <p className="st-muted">Nothing to show.</p>}
        </>}
        {open === "vars" && (x.vars.size ? <table className="st-table" aria-label="Variables">
          <thead><tr><th scope="col">Variable</th><th scope="col">Last value</th></tr></thead>
          <tbody>{[...x.vars.entries()].map(([k, v]) => <tr key={k}><td className="ui-mono">{k}</td><td className="ui-mono">{v}</td></tr>)}</tbody>
        </table> : <p className="st-muted">No variables were set{active ? " yet" : ""}. Credentials always show as ****.</p>)}
        {open === "io" && <div className="cc-grid2">
          <div><h3 className="cc-log-title">Inputs</h3><pre className="cc-log" aria-label="Inputs">{json(run.inputs)}</pre></div>
          <div><h3 className="cc-log-title">Outputs</h3><pre className="cc-log" aria-label="Outputs">{run.outputs ? json(run.outputs) : active ? "Available when the run ends." : "{}"}</pre></div>
        </div>}
        {open === "ai" && ai && <table className="st-table" aria-label="AI calls">
          <thead><tr>{["When", "Purpose", "Model", "Tokens in + out", "Time", "Masked", "Tools"].map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
          <tbody>{ai.map((c, i) => <tr key={i}><td>+{((Date.parse(c.ts) - Date.parse(run.started ?? c.ts)) / 1000).toFixed(1)}s</td><td>{c.purpose ?? ""}</td>
            <td>{(c.model ?? "") + (c.connection ? " (" + c.connection + ")" : "")}</td><td>{(c.input_tokens ?? 0) + " + " + (c.output_tokens ?? 0)}</td>
            <td>{c.latency_ms ? duration(c.latency_ms) : ""}</td><td>{c.masked ? Object.entries(c.masked).map(([k, n]) => k + " " + n).join(", ") : ""}</td>
            <td>{(c.tool_calls ?? []).join(", ")}</td></tr>)}</tbody>
        </table>}
      </>}
      </div>
      {run && <div className="cc-run-foot">
        <Button kind="plain" onClick={download} disabled={!events.length}>Download log</Button>
        <span className="ov-fill" />
        {active && run.status === "running" && can("runs.stop") && (run.pause_requested
          ? <Button kind="secondary" onClick={() => control("resume")}>Resume</Button> : <Button kind="secondary" onClick={() => control("pause")}>Pause</Button>)}
        {active && can("runs.stop") && !run.cancel_requested && <Button kind="danger" onClick={() => control("stop")}>Stop</Button>}
        {!active && can("runs.start") && <Button kind="primary" onClick={again}>Run again</Button>}
      </div>}
    </aside>
  );
}
