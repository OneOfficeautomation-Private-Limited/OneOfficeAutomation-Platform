import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Run, RunEvent } from "../api/cr";
import { Button, Dialog, Select, TextInput, usePersistent } from "../components";

/**
 * Manage > Agent Runs, built as AA's Activity (checked against AA, UiPath Jobs and Blue Prism session management,
 * official docs, 30 Oct 2026): two tabs, In progress (queued and running) and Completed (succeeded, failed, stopped);
 * filters; a column chooser; selected runs paused, resumed, stopped, run again or exported; a run's details with its
 * steps and log. Completed shows the last 90 days unless a range is chosen. In progress refreshes every 5 seconds.
 */
type Tab = "progress" | "completed";
type Col = { key: string; label: string; cell: (r: Run) => string };

const name = (path: string) => path.split("/").pop()!.replace(/\.flow\.json$/, "");
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
function duration(ms?: number | null) {
  if (ms == null) return "";
  const s = Math.round(ms / 1000);
  return s < 60 ? s + " s" : s < 3600 ? Math.floor(s / 60) + " min " + (s % 60) + " s" : Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min";
}
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const startedBy = (r: Run) => (r.requested_by ?? "") + (r.run_as && r.run_as !== r.requested_by ? " as " + r.run_as : "");
function errorText(e?: string | null) {
  if (!e) return "";
  try { const o = JSON.parse(e); return (o.code ? o.code + ": " : "") + (o.message ?? ""); } catch { return e; }
}

const PROGRESS_COLS: Col[] = [
  { key: "status", label: "Status", cell: (r) => STATUS[runState(r)] ?? r.status },
  { key: "agent", label: "Agent", cell: (r) => name(r.bot_path) + (r.version ? " v" + r.version : "") },
  { key: "progress", label: "Progress", cell: (r) => r.progress ? `Step ${Math.min(r.progress.finished + 1, r.progress.total || r.progress.finished + 1)}${r.progress.total ? " of " + r.progress.total : ""}${r.progress.action ? ": " + r.progress.action : ""}` : r.status === "queued" ? "Waiting for the runner" : "" },
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
  { key: "duration", label: "Duration", cell: (r) => duration(r.duration_ms) },
  { key: "steps", label: "Steps", cell: (r) => r.steps == null ? "" : String(r.steps) },
  { key: "error", label: "Error", cell: (r) => errorText(r.error) },
];
const PAGE = 25;
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;

export function Activity({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = usePersistent<Tab>("activity.tab", "progress");
  const [hidden, setHidden] = usePersistent<Record<Tab, string[]>>("activity.hidden", { progress: [], completed: ["steps"] });
  const [f, setF] = useState<Record<string, string>>({});
  const [offset, setOffset] = useState(0);
  const [data, setData] = useState<{ runs: Run[]; total: number } | null>(null);
  const [counts, setCounts] = useState<Record<Tab, number | null>>({ progress: null, completed: null });
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [agents, setAgents] = useState<string[]>([]);
  const [runners, setRunners] = useState<{ id: string; name: string }[]>([]);
  const [choosing, setChoosing] = useState(false);
  const [details, setDetails] = useState<string | null>(null);
  const can = (p: string) => user.permissions.includes(p);
  const cols = (tab === "progress" ? PROGRESS_COLS : COMPLETED_COLS).filter((c) => !hidden[tab].includes(c.key));

  useEffect(() => {
    if (can("repo.view")) api.agents().then((b) => setAgents(b.map((x) => x.path)), () => {});
    if (can("devices.view")) api.runners().then(setRunners, () => {});
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps

  /** The query for the tab: Completed covers the last 90 days unless a range is chosen. */
  const params = useCallback((limit: number, off: number) => {
    const p: Record<string, string> = { filter: tab === "progress" ? "active" : "history", limit: String(limit), offset: String(off) };
    for (const [k, v] of Object.entries(f)) if (v && (tab === "completed" || !["from", "to"].includes(k))) p[k] = v;
    const day = (v: string, next: boolean) => { const d = new Date(v + "T00:00:00"); if (next) d.setDate(d.getDate() + 1); return d.toISOString(); };
    if (p.from) p.from = day(p.from, false);
    if (p.to) p.to = day(p.to, true);
    if (tab === "completed" && !p.from && !p.to) p.from = new Date(Date.now() - 90 * 86_400_000).toISOString();
    return p;
  }, [tab, f]);

  const load = useCallback(async () => {
    try {
      const [r, other] = await Promise.all([api.runs(params(tab === "progress" ? 200 : PAGE, tab === "progress" ? 0 : offset)),
        api.runs({ filter: tab === "progress" ? "history" : "active", limit: "1" })]);
      setData(r); setError(null);
      setCounts(tab === "progress" ? { progress: r.total, completed: other.total } : { progress: other.total, completed: r.total });
      setSelected((s) => s.filter((id) => r.runs.some((x) => x.id === id)));
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, [api, params, tab, offset]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (tab !== "progress") return;
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [tab, load]);

  const runs = data?.runs ?? [];
  const chosen = runs.filter((r) => selected.includes(r.id));
  const setFilter = (k: string, v: string) => { setF({ ...f, [k]: v }); setOffset(0); };
  const act = async (what: "pause" | "resume" | "stop" | "rerun") => {
    let done = 0;
    for (const r of chosen) { try { if (what === "rerun") await api.rerun(r.id); else await api.control(r.id, what); done++; } catch (e) { setError(e instanceof Error ? e.message : String(e)); } }
    const verb = { pause: "Pause asked for", resume: "Resumed", stop: "Stop asked for", rerun: "Started again" }[what];
    onNotice(verb + " " + done + " run" + (done === 1 ? "" : "s") + (what === "pause" || what === "stop" ? " (after the current step)" : "") + ".");
    setSelected([]); load();
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
    const all = (tab === "progress" ? PROGRESS_COLS : COMPLETED_COLS);
    const text = [["Run id", ...all.map((c) => c.label)], ...rows.map((r) => [r.id, ...all.map((c) => c.cell(r))])].map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = (tab === "progress" ? "runs-in-progress" : "runs-completed") + ".csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + rows.length + " run" + (rows.length === 1 ? "" : "s") + ".");
  };

  return (
    <section className="st-page cc-home" aria-labelledby="cc-act-title">
      <header className="st-page-head"><div><h1 id="cc-act-title">Agent Runs</h1><p>Runs going on now, and runs that have ended.</p></div></header>
      <div className="st-page-tabs" role="tablist" aria-label="Runs">
        {(["progress", "completed"] as const).map((t) => <button key={t} type="button" role="tab" className="st-page-tab" aria-selected={tab === t}
          onClick={() => { setTab(t); setOffset(0); setSelected([]); setData(null); }}>
          {t === "progress" ? "In progress" : "Completed"}{counts[t] != null && <span className="cc-count">{counts[t]}</span>}</button>)}
      </div>
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search runs" placeholder="Search agent, run id, user or error" value={f.q ?? ""} onChange={(e) => setFilter("q", e.target.value)} />
        <Select aria-label="Status" value={f.status ?? ""} onChange={(e) => setFilter("status", e.target.value)}
          options={[{ value: "", label: "Any status" }, ...(tab === "progress" ? [["queued", "Queued"], ["running", "Running"]] : [["success", "Completed"], ["failed", "Failed"], ["stopped", "Stopped"]]).map(([v, l]) => ({ value: v, label: l }))]} />
        {agents.length > 0 && <Select aria-label="Agent" value={f.bot ?? ""} onChange={(e) => setFilter("bot", e.target.value)}
          options={[{ value: "", label: "Any agent" }, ...agents.map((p) => ({ value: p, label: name(p) }))]} />}
        {runners.length > 0 && <Select aria-label="Runner" value={f.device ?? ""} onChange={(e) => setFilter("device", e.target.value)}
          options={[{ value: "", label: "Any runner" }, ...runners.map((d) => ({ value: d.id, label: d.name }))]} />}
        <Select aria-label="How started" value={f.source ?? ""} onChange={(e) => setFilter("source", e.target.value)}
          options={[{ value: "", label: "Started any way" }, ...SOURCES.map(([v, l]) => ({ value: v, label: l }))]} />
        <TextInput aria-label="Started by" placeholder="Started by (user name)" value={f.user ?? ""} onChange={(e) => setFilter("user", e.target.value)} />
        {tab === "completed" && <>
          <label className="cc-date">From <TextInput type="date" aria-label="From" value={f.from ?? ""} onChange={(e) => setFilter("from", e.target.value)} /></label>
          <label className="cc-date">To <TextInput type="date" aria-label="To" value={f.to ?? ""} onChange={(e) => setFilter("to", e.target.value)} /></label>
        </>}
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => { setF({}); setOffset(0); }}>Clear filters</Button>}
      </div>
      <div className="cc-bar" role="toolbar" aria-label="Runs actions">
        {chosen.length > 0 && <span className="cc-chosen">{chosen.length} selected</span>}
        {tab === "progress" && can("runs.stop") && <>
          <Button kind="secondary" disabled={!chosen.length} onClick={() => act("pause")}>Pause</Button>
          <Button kind="secondary" disabled={!chosen.length} onClick={() => act("resume")}>Resume</Button>
          <Button kind="danger" disabled={!chosen.length} onClick={() => act("stop")}>Stop</Button>
        </>}
        {tab === "completed" && can("runs.start") && <Button kind="secondary" disabled={!chosen.length} onClick={() => act("rerun")}>Run again</Button>}
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={load}>Refresh</Button>
        <Button kind="plain" onClick={() => setChoosing(true)}>Columns</Button>
        <Button kind="secondary" onClick={() => exportCsv().catch((e) => setError(e instanceof Error ? e.message : String(e)))}>
          {chosen.length ? "Export selected" : "Export CSV"}</Button>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label={tab === "progress" ? "Runs in progress" : "Completed runs"}>
          <thead><tr>
            <th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={runs.length > 0 && chosen.length === runs.length}
              onChange={(e) => setSelected(e.target.checked ? runs.map((r) => r.id) : [])} /></th>
            {cols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}
          </tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id} className={selected.includes(r.id) ? "is-selected" : ""} onClick={(e) => { if (!(e.target as HTMLElement).closest("input")) setDetails(r.id); }}>
                <td className="cc-check"><input type="checkbox" aria-label={"Select the run of " + name(r.bot_path)} checked={selected.includes(r.id)}
                  onChange={(e) => setSelected(e.target.checked ? [...selected, r.id] : selected.filter((x) => x !== r.id))} /></td>
                {cols.map((c) => <td key={c.key} className={c.key === "status" ? "cc-status is-" + runState(r) : c.key === "error" ? "cc-err" : undefined}>{c.cell(r)}</td>)}
              </tr>
            ))}
            {data && !runs.length && <tr><td colSpan={cols.length + 1} className="st-muted">{tab === "progress" ? "Nothing is running or waiting." : "No runs match these filters."}</td></tr>}
            {!data && !error && <tr><td colSpan={cols.length + 1} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {tab === "completed" && data && data.total > PAGE && (
        <div className="cc-pager">
          <Button kind="plain" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</Button>
          <span>{offset + 1}–{Math.min(offset + PAGE, data.total)} of {data.total}</span>
          <Button kind="plain" disabled={offset + PAGE >= data.total} onClick={() => setOffset(offset + PAGE)}>Next</Button>
        </div>
      )}
      {choosing && (
        <Dialog open title="Columns" width={360} onClose={() => setChoosing(false)} footer={<Button kind="primary" onClick={() => setChoosing(false)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {(tab === "progress" ? PROGRESS_COLS : COMPLETED_COLS).map((c) => (
              <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden[tab].includes(c.key)} disabled={c.key === "agent"}
                onChange={(e) => setHidden({ ...hidden, [tab]: e.target.checked ? hidden[tab].filter((k) => k !== c.key) : [...hidden[tab], c.key] })} /> {c.label}</label>
            ))}
          </div>
        </Dialog>
      )}
      {details && <RunDetails api={api} id={details} onClose={() => setDetails(null)} />}
    </section>
  );
}

/** A run's details: what ran, where, when and how it ended, then its steps and log as the runner reported them. */
function RunDetails({ api, id, onClose }: { api: CrApi; id: string; onClose: () => void }) {
  const [run, setRun] = useState<(Run & { events: RunEvent[] }) | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.run(id).then(setRun, (e) => setError(e instanceof Error ? e.message : String(e))); }, [api, id]);
  const lines = useMemo(() => (run?.events ?? []).map((e) => {
    const d = (e.data ?? {}) as Record<string, string | number | undefined>;
    const text = e.type === "step.started" ? "Started " + d.action
      : e.type === "step.finished" ? "Finished " + d.action + (d.durationMs != null ? " (" + duration(Number(d.durationMs)) + ")" : "")
      : e.type === "step.failed" ? "Failed " + d.action + ": " + (d.code ? d.code + " " : "") + (d.message ?? "")
      : e.type === "step.retry" ? "Retry " + d.attempt + " after " + d.code
      : e.type === "log" ? String(d.message ?? "")
      : e.type === "variable.set" ? d.name + " = " + d.value
      : e.type;
    return { seq: e.seq, step: e.stepId ?? "", text, bad: e.type === "step.failed" };
  }), [run]);
  return (
    <Dialog open title={run ? "Run of " + name(run.bot_path) : "Run"} width={760} onClose={onClose} footer={null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!run && !error && <p className="st-muted" role="status">Loading…</p>}
      {run && <>
        <dl className="cc-facts">
          <dt>Status</dt><dd>{STATUS[runState(run)] ?? run.status}</dd>
          <dt>Agent</dt><dd>{run.bot_path}{run.version ? " (version " + run.version + ")" : ""}</dd>
          <dt>Runner</dt><dd>{run.device_name ?? run.device_id ?? ""}</dd>
          <dt>Started by</dt><dd>{startedBy(run)} · {howStarted(run.source)}</dd>
          <dt>Queued</dt><dd>{when(run.created)}</dd>
          <dt>Started</dt><dd>{when(run.started)}</dd>
          <dt>Ended</dt><dd>{when(run.finished)}{run.duration_ms != null ? " · " + duration(run.duration_ms) : ""}</dd>
          {run.error && <><dt>Error</dt><dd className="cc-err">{errorText(run.error)}</dd></>}
          <dt>Run id</dt><dd className="ui-mono">{run.id}</dd>
        </dl>
        <h3 className="cc-log-title">Steps and log</h3>
        {lines.length ? <ol className="cc-log" aria-label="Steps and log">{lines.map((l) =>
          <li key={l.seq} className={l.bad ? "is-bad" : undefined}><span className="cc-log-step">{l.step}</span>{l.text}</li>)}</ol>
          : <p className="st-muted">The runner has not reported any steps yet.</p>}
      </>}
    </Dialog>
  );
}
