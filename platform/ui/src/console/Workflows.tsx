import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { CrApi, CrUser, TaskFormField, WorkflowRun } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";
import { FormField } from "./Inbox";

/**
 * Manage > Workflow, built as AA's Process Composer requests and UiPath Maestro's instances (checked against their docs
 * and Blue Prism's, 31 Oct 2026): tabs In progress and Finished; each run with its workflow, reference, status and
 * current stage. A run opens with its timeline (agents, people's tasks, decisions) and data, and Cancel while it is
 * going. Start a workflow with its own start form, or data as JSON. Workflows are designed in One AgentStudio.
 * Laid out as the design plan's step 8 (approved on the canvas 31 Oct 2026): the list pattern with status pills, and a
 * run in a side panel beside the list: its stages as a track, the facts, then Timeline, People's tasks and Data.
 * Review round 2 (31 Oct 2026): the workflow's icon, why a finished run ended (on one line, whole on hover), and a menu
 * per run (Open, Cancel run, asked first).
 */
type Tab = "progress" | "finished";
const STATUS: Record<string, string> = { running: "Running", waiting: "Waiting for a person", completed: "Completed", failed: "Failed", cancelled: "Cancelled" };
const EVENT: Record<string, string> = { agent_started: "Agent started", agent_finished: "Agent finished", agent_failed: "Agent failed", agent_stopped: "Agent stopped", task_created: "Task given",
  task_completed: "Task done", approved: "Approved", rejected: "Rejected", decided: "Decided", notified: "Notified", waiting: "Waiting", failed: "Failed", cancelled: "Cancelled" };
const flowName = (p: string) => p.split("/").pop()!.replace(/\.process\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const going = (r: WorkflowRun) => r.status === "running" || r.status === "waiting";
const PILL: Record<string, string> = { running: "active", waiting: "pending", completed: "success", failed: "failed", cancelled: "stopped" };

export function Workflows({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("progress");
  const [runs, setRuns] = useState<WorkflowRun[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [flow, setFlow] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "start" } | { kind: "run"; id: string }>(null);
  const [ask, setAsk] = useState<WorkflowRun | null>(null);   // a run to cancel, asked first
  const load = useCallback(async () => { try { setRuns(await api.workflowRuns()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(() => { if (!dialog) load(); }, 10_000); return () => clearInterval(t); }, [load, dialog]);
  const shown = useMemo(() => (runs ?? []).filter((r) => (tab === "progress") === going(r) && (!flow || r.path === flow)
    && (!q.trim() || (r.id + " " + (r.reference ?? "") + " " + flowName(r.path) + " " + (r.stage ?? "") + " " + (r.started_by ?? "")).toLowerCase().includes(q.trim().toLowerCase()))), [runs, tab, q, flow]);
  const flows = [...new Set((runs ?? []).map((r) => r.path))].sort();
  const count = (t: Tab) => (runs ?? []).filter((r) => (t === "progress") === going(r)).length;
  const label = (r: WorkflowRun) => r.reference || flowName(r.path);
  const rowMenu = (r: WorkflowRun) => [{ key: "open", label: "Open", onSelect: () => setDialog({ kind: "run", id: r.id }) },
    ...(going(r) ? [{ key: "cancel", label: "Cancel run…", separatorBefore: true, onSelect: () => setAsk(r) }] : [])];
  const cancel = (r: WorkflowRun) => api.cancelWorkflow(r.id).then(() => { onNotice("Cancelled the run " + (r.reference || r.id) + "."); setAsk(null); load(); }, (e) => setError(message(e)));
  const why = (r: WorkflowRun) => r.error ? (r.error.code ? r.error.code + ": " : "") + (r.error.message ?? "") : "";

  return (
    <section className={"st-page cc-home cc-list" + (dialog?.kind === "run" ? " has-run" : "")} aria-labelledby="cc-wf-title">
      <header className="cc-list-head">
        <h1 id="cc-wf-title">Workflow</h1>
        <span className="ov-info" role="img" aria-label="About this page" title="Workflows chain agents, people and decisions. Design them in One AgentStudio, put them live, then start them here; people get their steps in the Task inbox.">i</span>
        {runs && <span className="cc-list-count">{count("progress")} in progress · {(runs ?? []).filter((r) => r.status === "waiting").length} waiting for a person</span>}
        <span className="ov-fill" />
        {user.permissions.includes("runs.start") && <Button kind="primary" onClick={() => setDialog({ kind: "start" })}>Start a workflow</Button>}
      </header>
      <div className="ov-tabs cc-list-tabs" role="tablist" aria-label="Workflow runs">
        {(["progress", "finished"] as const).map((t) => <button key={t} type="button" role="tab" className="ov-tab" aria-selected={tab === t} onClick={() => setTab(t)}>
          {t === "progress" ? "In progress" : "Finished"}{runs && <span className="ov-cnt">{count(t)}</span>}</button>)}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-list-search"><TextInput type="search" aria-label="Search workflow runs" placeholder="Search reference, run id, stage, who started it" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        {flows.length > 1 && <span className={"cc-chip" + (flow ? " is-on" : "")}><Select aria-label="Workflow" value={flow} onChange={(e) => setFlow(e.target.value)} options={[{ value: "", label: "Workflow: Any" }, ...flows.map((p) => ({ value: p, label: "Workflow: " + flowName(p) }))]} /></span>}
      </div>
      <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
      {ask && <div className="cc-list-bulk" role="alert"><span>Cancel the run {label(ask)}? Its open tasks are withdrawn.</span>
        <Button kind="danger" onClick={() => cancel(ask)}>Cancel run</Button><Button kind="plain" onClick={() => setAsk(null)}>Keep it</Button></div>}
      <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label={tab === "progress" ? "Workflow runs in progress" : "Finished workflow runs"}>
          <thead><tr><th scope="col">Status</th><th scope="col">Workflow</th><th scope="col">Reference</th><th scope="col">{tab === "progress" ? "Current stage" : "Last stage"}</th>
            <th scope="col">Started by</th><th scope="col">Started</th><th scope="col">{tab === "progress" ? "Updated" : "Finished"}</th>{tab === "finished" && <th scope="col">Why it ended</th>}
            <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.id} className={dialog?.kind === "run" && dialog.id === r.id ? "is-open" : undefined} onClick={rowClick(() => setDialog({ kind: "run", id: r.id }))}>
                <td><span className={"cc-pill is-" + (PILL[r.status] ?? "pending")}><span className="cc-pill-dot" />{STATUS[r.status] ?? r.status}</span></td>
                <td><span className="cc-aico is-workflow" aria-hidden="true">W</span><strong>{flowName(r.path)}</strong> <span className="cc-none">v{r.version}</span></td><td className="ui-mono">{r.reference ?? ""}</td><td>{r.stage ?? ""}</td><td>{r.started_by ?? ""}</td>
                <td>{when(r.created)}</td><td>{when(tab === "progress" ? r.updated : r.finished)}</td>
                {tab === "finished" && <td>{why(r) ? <span className="cc-run-why" title={why(r)}>{why(r)}</span> : <span className="cc-none">–</span>}</td>}
                <td className="cc-list-menu"><RowMenu label={"Actions for " + label(r)} items={rowMenu(r)} /></td>
              </tr>
            ))}
            {runs && !shown.length && <tr className="is-static"><td colSpan={9} className="cc-none">{tab === "progress" ? "No workflow is running." : "No finished workflow runs."}</td></tr>}
            {!runs && !error && <tr className="is-static"><td colSpan={9} className="cc-none" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {runs && <div className="cc-list-pager"><span>{shown.length} of {count(tab)}{q.trim() || flow ? " matching" : ""} · refreshes every 10 seconds</span></div>}
      </div>
      {dialog?.kind === "start" && <StartWorkflow api={api} onClose={() => setDialog(null)} onStarted={(t, id) => { onNotice(t); load(); setDialog({ kind: "run", id }); }} />}
      {dialog?.kind === "run" && <RunView key={dialog.id} api={api} id={dialog.id} onClose={() => { setDialog(null); load(); }} onNotice={onNotice} />}
    </section>
  );
}

/** Start a workflow: its start form when it has one, else data as JSON; a reference to find the request again. */
function StartWorkflow({ api, onClose, onStarted }: { api: CrApi; onClose: () => void; onStarted: (t: string, id: string) => void }) {
  const [flows, setFlows] = useState<string[] | null>(null);
  const [path, setPath] = useState("");
  const [fields, setFields] = useState<TaskFormField[]>([]);
  const [answers, setAnswers] = useState<Record<string, unknown>>({});
  const [json, setJson] = useState("");
  const [reference, setReference] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveWorkflows().then((b) => { setFlows(b.map((x) => x.path)); setPath(b[0]?.path ?? ""); }, (e) => setError(message(e))); }, [api]);
  useEffect(() => {
    setFields([]); setAnswers({});
    if (path) api.workflowForm(path).then((f) => setFields((f.form?.fields ?? []).filter((x) => x.id && x.type !== "heading" && x.type !== "paragraph")), () => {});
  }, [api, path]);
  const start = () => {
    let data: Record<string, unknown> = answers;
    if (!fields.length && json.trim()) { try { data = JSON.parse(json); } catch { setError("The data is not JSON."); return; } }
    const missing = fields.filter((x) => x.required && (answers[x.id] === undefined || answers[x.id] === "")).map((x) => x.label ?? x.id);
    if (missing.length) { setError("Fill in: " + missing.join(", ")); return; }
    api.startWorkflow(path, data, reference.trim() || null).then((r) => onStarted("Started " + flowName(path) + (r.reference ? " (" + r.reference + ")" : "") + ".", r.id), (e) => setError(message(e)));
  };
  return (
    <Dialog open title="Start a workflow" width={620} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!path} onClick={start}>Start</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {flows && !flows.length ? <p className="st-muted">No workflow is live yet. In One AgentStudio create a Workflow, check it in and put it in production.</p> : <>
        <Field label="Workflow (its live version)">{(id) => <Select id={id} value={path} onChange={(e) => setPath(e.target.value)} options={(flows ?? []).map((p) => ({ value: p, label: flowName(p) }))} />}</Field>
        {fields.length ? <div className="cc-taskform" role="group" aria-label="Start form">{fields.map((x) => <FormField key={x.id} field={x} value={answers[x.id]} disabled={false} onChange={(v) => setAnswers({ ...answers, [x.id]: v })} />)}</div>
          : <Field label="Starting data (JSON, optional)">{(id) => <TextArea id={id} rows={4} value={json} placeholder='{"amount": 1200, "vendor": "Acme"}' onChange={(e) => setJson(e.target.value)} />}</Field>}
        <Field label="Reference (to find this request again)">{(id) => <TextInput id={id} value={reference} placeholder="PO-2026-0042" onChange={(e) => setReference(e.target.value)} />}</Field>
        <p className="st-muted">Stages use this data as $name. Agent steps run as you on any online runner; people's steps and approvals go to the Task inbox.</p>
      </>}
    </Dialog>
  );
}

/** A run: facts, the timeline of what happened, its data, and Cancel while it is going (open tasks are withdrawn). */
function RunView({ api, id, onClose, onNotice }: { api: CrApi; id: string; onClose: () => void; onNotice: (t: string) => void }) {
  const [r, setR] = useState<WorkflowRun | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const read = useCallback(() => api.workflowRun(id).then(setR, (e) => setError(message(e))), [api, id]);
  useEffect(() => { read(); const t = setInterval(read, 5000); return () => clearInterval(t); }, [read]);
  const [tab, setTab] = useState<"timeline" | "tasks" | "data">("timeline");
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, [onClose]);
  const title = r ? "Workflow run " + (r.reference || r.id) : "Workflow run";
  /** The stages reached so far, in order: done, or the one it is on now (failed when the run failed there). */
  const stages = useMemo(() => {
    const seen: string[] = [];
    for (const e of r?.history ?? []) if (e.stage && !seen.includes(e.stage)) seen.push(e.stage);
    if (r?.stage && !seen.includes(r.stage)) seen.push(r.stage);
    return seen.map((name) => {
      const tasks = (r?.tasks ?? []).filter((t) => t.stage === name);
      const now = !!r && going(r) && name === r.stage;
      const bad = !!r && r.status === "failed" && name === r.stage;
      const who = tasks.length ? tasks.map((t) => t.claimed_by ?? t.assignee).filter(Boolean).join(", ") : "";
      return { name, now, bad, who };
    });
  }, [r]);
  const openTask = (r?.tasks ?? []).find((t) => t.status === "open" || t.status === "claimed");
  const panel = (body: ReactNode, foot: ReactNode) => (
    <aside className="cc-run-panel" role="dialog" aria-modal="false" aria-label={title}>
      <div className="cc-run-head">
        {r && <span className={"cc-pill is-" + (PILL[r.status] ?? "pending")}><span className="cc-pill-dot" />{STATUS[r.status] ?? r.status}</span>}
        <h2>{r ? flowName(r.path) + (r.reference ? " · " + r.reference : "") : "Workflow run"}</h2>
        <span className="ov-fill" />
        <button type="button" className="cc-list-icon" aria-label="Close" title="Close" onClick={onClose}>×</button>
      </div>
      <div className="cc-run-body">{error && <p className="st-alert" role="alert">{error}</p>}{body}</div>
      {foot && <div className="cc-run-foot">{foot}</div>}
    </aside>
  );
  if (!r) return panel(error ? null : <p className="cc-none" role="status">Loading…</p>, null);
  return panel(<>
      {stages.length > 0 && <ol className="cc-wf-track" aria-label="Stages">
        {stages.map((st, i) => <li key={st.name} className={st.bad ? "is-bad" : st.now ? "is-now" : "is-done"}>
          <span className="cc-wf-dot" aria-hidden="true">{st.bad ? "!" : st.now ? i + 1 : "✓"}</span>
          <span className="cc-wf-name">{st.name}</span>
          <span className="cc-none">{st.bad ? "failed here" : st.now ? (r.status === "waiting" ? "waiting" : "now") : "done"}{st.who ? " · " + st.who : ""}</span>
        </li>)}
      </ol>}
      <dl className="cc-facts cc-facts-wide">
        <dt>Workflow</dt><dd>{flowName(r.path)} v{r.version}</dd>
        {r.reference && <><dt>Reference</dt><dd>{r.reference}</dd></>}
        <dt>Status</dt><dd>{STATUS[r.status] ?? r.status}{r.stage ? " · " + r.stage : ""}</dd>
        <dt>Started</dt><dd>{when(r.created)}{r.started_by ? " by " + r.started_by : ""}</dd>
        {r.finished && <><dt>Finished</dt><dd>{when(r.finished)}</dd></>}
        {r.error && <><dt>Error</dt><dd className="cc-err">{(r.error.code ?? "") + " " + (r.error.message ?? "")}</dd></>}
        <dt>Run id</dt><dd className="ui-mono">{r.id}</dd>
      </dl>
      <div className="ov-tabs cc-list-tabs cc-run-tabs" role="tablist" aria-label="Run details">
        {([["timeline", "Timeline (" + (r.history ?? []).length + ")"], ["tasks", "People's tasks (" + (r.tasks ?? []).length + ")"], ["data", "Data"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "timeline" && <ol className="cc-run-steps" aria-label="Timeline">
        {(r.history ?? []).map((e, i) => { const bad = e.event.endsWith("failed") || e.event === "rejected"; const wait = e.event === "task_created" || e.event === "waiting";
          return <li key={i} className={bad ? "is-failed" : undefined}>
            <span className={"cc-run-ic is-" + (bad ? "failed" : wait ? "running" : "done")} aria-hidden="true">{bad ? "!" : wait ? "…" : "✓"}</span>
            <span className="cc-none">{new Date(e.ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
            <span className="cc-run-step"><strong>{EVENT[e.event] ?? e.event}</strong>{e.stage ? " · " + e.stage : ""}
              {e.detail && <span className="cc-run-err cc-wf-detail">{Object.entries(e.detail).map(([k, v]) => k + ": " + (typeof v === "object" ? JSON.stringify(v) : String(v))).join("; ")}</span>}</span>
            <span />
          </li>; })}
        {!(r.history ?? []).length && <li className="cc-none">Nothing has happened yet.</li>}
      </ol>}
      {tab === "tasks" && ((r.tasks ?? []).length ? <table className="cc-list-table" aria-label="People's tasks"><thead><tr><th scope="col">Task</th><th scope="col">Stage</th><th scope="col">Assigned to</th><th scope="col">Status</th><th scope="col">Done</th></tr></thead>
        <tbody>{r.tasks!.map((t) => <tr key={t.id} className="is-static"><td>{t.title}</td><td>{t.stage ?? ""}</td><td>{t.claimed_by ?? t.assignee ?? ""}</td><td>{t.outcome ?? t.status}</td><td>{when(t.completed)}</td></tr>)}</tbody></table>
        : <p className="cc-none">No one has been given a task in this run.</p>)}
      {tab === "data" && (Object.keys(r.data ?? {}).length ? <table className="cc-au-kv" aria-label="Data"><tbody>{Object.entries(r.data ?? {}).map(([k, v]) =>
        <tr key={k}><th scope="row">{k}</th><td>{typeof v === "object" ? JSON.stringify(v) : String(v)}</td></tr>)}</tbody></table> : <p className="cc-none">No data.</p>)}
    </>, <>
      {openTask && <a className="ui-btn ui-btn-secondary cc-btn-link" href="#/inbox">Open its task in the Task inbox</a>}
      <span className="ov-fill" />
      {going(r) && (confirm
        ? <><span>Cancel this run? Its open tasks are withdrawn.</span><Button kind="plain" onClick={() => setConfirm(false)}>Keep it</Button>
          <Button kind="danger" onClick={() => api.cancelWorkflow(r.id).then(() => { onNotice("Cancelled the run " + (r.reference || r.id) + "."); setConfirm(false); read(); }, (e) => setError(message(e)))}>Cancel run</Button></>
        : <Button kind="danger" onClick={() => setConfirm(true)}>Cancel run</Button>)}
    </>);
}
