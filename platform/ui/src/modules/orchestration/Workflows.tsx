import "./consoleApi";
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { CrApi, CrUser, TaskFormField } from "../../api/cr";
import type { WorkflowRun, WorkflowStepInfo } from "./consoleApi";
import { AgentInputs, Button, Dialog, Field, HelpButton, PrincipalPicker, principalDirectory, Select, TextInput, type HelpTopic, reportMinor, PageError } from "../../components";
import { RefreshButton, RowMenu, rowClick, when, CloseView } from "../../console/ListParts";
import { FormField } from "../../console/Inbox";
import { PageIntro } from "../../shell/PageIntro";
import { WorkflowDiagram, type StepState } from "./WorkflowDiagram";
import type { Step } from "./flow";

/**
 * Run > Workflow runs (review round, 26 Nov 2026; until then Build > Workflow), built as AA's Requests and UiPath
 * Maestro's instances: workflows are designed in OneAgentStudio (Build), and here they are started and followed.
 * Counts (waiting on a person, late, finished today, failed) filter the list; each run shows the step it is on, who
 * or what it waits on, and when that is due (Late in red). A run opens beside the list with its steps (done, now, to
 * come; a parallel step's branches inside), the diagram coloured the same way, its history, people's tasks and data.
 * Operators remind the people a run waits on, give its task to someone else, or skip the step (with a reason), each
 * recorded in the audit trail; Cancel stops it.
 */
type Tab = "progress" | "finished";
type Quick = "" | "person" | "late" | "today" | "failed";
const STATUS: Record<string, string> = { running: "Running", waiting: "Waiting", completed: "Completed", failed: "Failed", cancelled: "Cancelled" };
const EVENT: Record<string, string> = { agent_started: "Agent started", agent_finished: "Agent finished", agent_failed: "Agent failed", agent_stopped: "Agent stopped", task_created: "Task given",
  task_completed: "Task done", approved: "Approved", approved_partly: "Approved (more needed)", rejected: "Rejected", decided: "Decided", notified: "Notified", waiting: "Waiting", waited: "Wait over",
  failed: "Failed", cancelled: "Cancelled", ended: "Ended", split: "Branches started", joined: "Branches joined", branch_failed: "A branch failed", subflow_started: "Sub-workflow started",
  subflow_finished: "Sub-workflow finished", escalated: "Escalated (late)", reminded: "Reminded", reassigned: "Reassigned", skipped: "Step skipped" };
const flowName = (p: string) => p.split("/").pop()!.replace(/\.process\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const going = (r: WorkflowRun) => r.status === "running" || r.status === "waiting";
const PILL: Record<string, string> = { running: "active", waiting: "pending", completed: "success", failed: "failed", cancelled: "stopped" };
const sameDay = (iso?: string | null) => !!iso && new Date(iso).toDateString() === new Date().toDateString();
const who = (w?: string | null) => (w ?? "").split(",").map((x) => x.trim().replace(/^(user|role|perm):/, "")).filter(Boolean).join(", ");

const HELP: HelpTopic = { title: "Workflow runs",
  about: "Workflows chain agents, people and decisions. Design them in OneAgentStudio (Build) and put them live, then start and follow them here; "
    + "people get their steps in the Task inbox. The list refreshes every 10 seconds.",
  items: [
    { name: "Start a workflow", text: "Choose a live workflow, fill in its start form or give starting data as JSON, and add a reference to find the "
      + "request again (the workflow's run title gives one when you leave it empty). Needs permission to start runs." },
    { name: "Counts", text: "Waiting on a person, Late, Finished today and Failed: click one to show only those runs; click it again to show all." },
    { name: "Now at and Waiting on", text: "The step the run is on, and the people its open task is with (or the runner, agent, time or branches it waits for)." },
    { name: "Due", text: "When the open task is due; Late in red once that has passed. A step can remind people or hand the task on by itself when late." },
    { name: "Open", text: "Opens the run beside the list: its steps, diagram, history, people's tasks and data. Clicking the row does the same." },
    { name: "Remind, Reassign and Skip step", text: "For operators (permission to stop runs): tell the people again, give the open task to someone else, or "
      + "leave the step and go on (with a reason). Each is kept in the run's history and the audit trail." },
    { name: "Cancel run…", text: "Stops a run that is still going, after you confirm. Its open tasks are withdrawn from the Task inbox." },
  ] };

const HELP_RUN: HelpTopic = { title: "Workflow run",
  about: "One run of a workflow: its steps, what happened at each, the tasks given to people and its data. It refreshes every 5 seconds.",
  items: [
    { name: "Steps", text: "Every step of the workflow: done, the one it is on now (with who has its task), failed, skipped or still to come. A parallel step lists its branches." },
    { name: "Diagram", text: "The same steps drawn as in OneAgentStudio, coloured by what happened." },
    { name: "History", text: "Everything that happened in order: agents started and finished, tasks given and done, approvals, decisions, escalations." },
    { name: "People's tasks", text: "The tasks given to people in this run, who has each, when each is due, and how and when it was done." },
    { name: "Data", text: "The run's data as it is now. Steps use it as $name." },
  ] };

export function Workflows({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("progress");
  const [quick, setQuick] = useState<Quick>("");
  const [runs, setRuns] = useState<WorkflowRun[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [flow, setFlow] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "start" } | { kind: "run"; id: string }>(null);
  const [ask, setAsk] = useState<WorkflowRun | null>(null);   // a run to cancel, asked first
  const load = useCallback(async () => { try { setRuns(await api.workflowRuns()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(() => { if (!dialog) load(); }, 10_000); return () => clearInterval(t); }, [load, dialog]);
  const shown = useMemo(() => (runs ?? []).filter((r) => (quick ? matches(r, quick) : (tab === "progress") === going(r)) && (!flow || r.path === flow)
    && (!q.trim() || (r.id + " " + (r.reference ?? "") + " " + flowName(r.path) + " " + (r.stage_name ?? r.stage ?? "") + " " + (r.started_by ?? "") + " " + (r.waiting_on ?? "")).toLowerCase().includes(q.trim().toLowerCase()))),
    [runs, tab, quick, q, flow]);
  const flows = [...new Set((runs ?? []).map((r) => r.path))].sort();
  const count = (t: Tab) => (runs ?? []).filter((r) => (t === "progress") === going(r)).length;
  const counted = (k: Quick) => (runs ?? []).filter((r) => matches(r, k)).length;
  const label = (r: WorkflowRun) => r.reference || flowName(r.path);
  const rowMenu = (r: WorkflowRun) => [{ key: "open", label: "Open", onSelect: () => setDialog({ kind: "run", id: r.id }) },
    ...(going(r) ? [{ key: "cancel", label: "Cancel run…", separatorBefore: true, onSelect: () => setAsk(r) }] : [])];
  const cancel = (r: WorkflowRun) => api.cancelWorkflow(r.id).then(() => { onNotice("Cancelled the run " + (r.reference || r.id) + "."); setAsk(null); load(); }, (e) => setError(message(e)));
  const why = (r: WorkflowRun) => r.error ? (r.error.code ? r.error.code + ": " : "") + (r.error.message ?? "") : "";
  const progress = quick ? quick === "person" || quick === "late" : tab === "progress";
  const chips: [Quick, string][] = [["person", "Waiting on a person"], ["late", "Late"], ["today", "Finished today"], ["failed", "Failed"]];

  return (
    <section className={"st-page cc-home cc-list" + (dialog?.kind === "run" ? " has-run" : "")} aria-labelledby="cc-wf-title">
      <header className="cc-list-head">
        <h1 id="cc-wf-title">Workflow runs</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:workflows" />
        {runs && <span className="cc-list-count">{count("progress")} in progress</span>}
        <span className="ov-fill" />
        {user.permissions.includes("runs.start") && <Button kind="primary" onClick={() => setDialog({ kind: "start" })}>Start a workflow</Button>}
      </header>
      <div className="ov-tabs cc-list-tabs" role="tablist" aria-label="Workflow runs">
        {(["progress", "finished"] as const).map((t) => <button key={t} type="button" role="tab" className="ov-tab" aria-selected={!quick && tab === t} onClick={() => { setTab(t); setQuick(""); }}>
          {t === "progress" ? "In progress" : "Finished"}{runs && <span className="ov-cnt">{count(t)}</span>}</button>)}
      </div>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-list-search"><TextInput type="search" aria-label="Search workflow runs" placeholder="Search reference, run id, step, who started it, who it waits on" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        {flows.length > 1 && <span className={"cc-chip" + (flow ? " is-on" : "")}><Select aria-label="Workflow" value={flow} onChange={(e) => setFlow(e.target.value)} options={[{ value: "", label: "Workflow: Any" }, ...flows.map((p) => ({ value: p, label: "Workflow: " + flowName(p) }))]} /></span>}
        <span className="cc-wf-counts" role="group" aria-label="Counts">
          {chips.map(([k, l]) => <button key={k} type="button" className={"cc-wf-count" + (quick === k ? " is-on" : "") + (k === "late" || k === "failed" ? " is-bad" : "")} aria-pressed={quick === k}
            onClick={() => setQuick(quick === k ? "" : k)}>{l} <strong>{runs ? counted(k) : "–"}</strong></button>)}
        </span>
      </div>
      <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
      {ask && <div className="cc-list-bulk" role="alert"><span>Cancel the run {label(ask)}? Its open tasks are withdrawn.</span>
        <Button kind="danger" onClick={() => cancel(ask)}>Cancel run</Button><Button kind="plain" onClick={() => setAsk(null)}>Keep it</Button></div>}
      <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label={progress ? "Workflow runs in progress" : "Finished workflow runs"}>
          <thead><tr><th scope="col">Status</th><th scope="col">Reference</th><th scope="col">Workflow</th><th scope="col">{progress ? "Now at" : "Last step"}</th>
            {progress ? <><th scope="col">Waiting on</th><th scope="col">Start time</th><th scope="col">Due</th></>
              : <><th scope="col">Started by</th><th scope="col">Start time</th><th scope="col">End time</th><th scope="col">Error message</th></>}
            <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.id} className={dialog?.kind === "run" && dialog.id === r.id ? "is-open" : undefined} onClick={rowClick(() => setDialog({ kind: "run", id: r.id }))}>
                <td><span className={"cc-pill is-" + (PILL[r.status] ?? "pending")}><span className="cc-pill-dot" />{STATUS[r.status] ?? r.status}</span></td>
                <td className="ui-mono">{r.reference ?? <span className="cc-none">–</span>}</td>
                <td><span className="cc-aico is-workflow" aria-hidden="true">W</span><strong>{flowName(r.path)}</strong> <span className="cc-none">v{r.version}</span></td>
                <td>{r.stage_name ?? r.stage ?? <span className="cc-none">–</span>}</td>
                {progress ? <><td>{r.waiting_on ? who(r.waiting_on) : <span className="cc-none">–</span>}</td><td>{when(r.created)}</td>
                  <td>{r.due ? (r.late ? <span className="cc-pill is-failed" title={"Due " + when(r.due)}><span className="cc-pill-dot" />Late</span> : when(r.due)) : <span className="cc-none">–</span>}</td></>
                  : <><td>{r.started_by ?? ""}</td><td>{when(r.created)}</td><td>{when(r.finished)}</td>
                    <td>{why(r) ? <span className="cc-run-why" title={why(r)}>{why(r)}</span> : <span className="cc-none">–</span>}</td></>}
                <td className="cc-list-menu"><RowMenu label={"Actions for " + label(r)} items={rowMenu(r)} /></td>
              </tr>
            ))}
            {runs && !shown.length && <tr className="is-static"><td colSpan={9} className="cc-none">{quick ? "No runs match." : tab === "progress" ? "No workflow is running." : "No finished workflow runs."}</td></tr>}
            {!runs && !error && <tr className="is-static"><td colSpan={9} className="cc-none" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {runs && <div className="cc-list-pager"><span>{shown.length} shown · refreshes every 10 seconds</span></div>}
      </div>
      {dialog?.kind === "start" && <StartWorkflow api={api} onClose={() => setDialog(null)} onStarted={(t, id) => { onNotice(t); load(); setDialog({ kind: "run", id }); }} />}
      {dialog?.kind === "run" && <RunView key={dialog.id} api={api} user={user} id={dialog.id} onClose={() => { setDialog(null); load(); }} onNotice={onNotice} />}
    </section>
  );
}

/** Whether a run is one of a count's: waiting on a person, late, finished today, failed. */
function matches(r: WorkflowRun, k: Quick) {
  return k === "person" ? going(r) && r.waiting_kind === "person" : k === "late" ? going(r) && !!r.late
    : k === "today" ? !going(r) && sameDay(r.finished) : k === "failed" ? r.status === "failed" : true;
}

/** Start a workflow: its start form when it has one, else data as JSON; a reference to find the request again. */
function StartWorkflow({ api, onClose, onStarted }: { api: CrApi; onClose: () => void; onStarted: (t: string, id: string) => void }) {
  const [flows, setFlows] = useState<string[] | null>(null);
  const [path, setPath] = useState("");
  const [fields, setFields] = useState<TaskFormField[]>([]);
  const [answers, setAnswers] = useState<Record<string, unknown>>({});
  const [data0, setData0] = useState<Record<string, unknown>>({});
  const [bad, setBad] = useState<string | null>(null);
  const [reference, setReference] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveWorkflows().then((b) => { setFlows(b.map((x) => x.path)); setPath(b[0]?.path ?? ""); }, (e) => setError(message(e))); }, [api]);
  useEffect(() => {
    setFields([]); setAnswers({});
    if (path) api.workflowForm(path).then((f) => setFields((f.form?.fields ?? []).filter((x) => x.id && x.type !== "heading" && x.type !== "paragraph")), (e) => reportMinor("the workflow's form", e));
  }, [api, path]);
  const start = () => {
    if (!fields.length && bad) { setError(bad); return; }
    const data: Record<string, unknown> = fields.length ? answers : data0;
    const missing = fields.filter((x) => x.required && (answers[x.id] === undefined || answers[x.id] === "")).map((x) => x.label ?? x.id);
    if (missing.length) { setError("Fill in: " + missing.join(", ")); return; }
    api.startWorkflow(path, data, reference.trim() || null).then((r) => onStarted("Started " + flowName(path) + (r.reference ? " (" + r.reference + ")" : "") + ".", r.id), (e) => setError(message(e)));
  };
  return (
    <Dialog open title="Start a workflow" width={620} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!path || (!fields.length && !!bad)} onClick={start}>Start</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {flows && !flows.length ? <p className="st-muted">No workflow is live yet. In OneAgentStudio create a Workflow, check it in and put it in production.</p> : <>
        <Field label="Workflow (its live version)">{(id) => <Select id={id} value={path} onChange={(e) => setPath(e.target.value)} options={(flows ?? []).map((p) => ({ value: p, label: flowName(p) }))} />}</Field>
        {fields.length ? <div className="cc-taskform" role="group" aria-label="Start form">{fields.map((x) => <FormField key={x.id} field={x} value={answers[x.id]} disabled={false} onChange={(v) => setAnswers({ ...answers, [x.id]: v })} />)}</div>
          : <AgentInputs label="Starting data (optional)" inputs={null} value={data0} onChange={setData0} onJsonError={setBad} />}
        <Field label="Reference (to find this request again)">{(id) => <TextInput id={id} value={reference} placeholder="PO-2026-0042" onChange={(e) => setReference(e.target.value)} />}</Field>
        <p className="st-muted">Steps use this data as $name. Agent steps run as you on any online runner; people's steps and approvals go to the Task inbox.</p>
      </>}
    </Dialog>
  );
}

/** The state of every step in a run, from its history: done, now, failed, skipped; the rest is still to come. */
export function stepStates(r: WorkflowRun, steps: WorkflowStepInfo[]): Record<string, StepState> {
  const out: Record<string, StepState> = {};
  const walk = (l: WorkflowStepInfo[]) => { for (const s of l) { out[s.id] = "todo"; for (const b of s.branches ?? []) walk(b.steps); } };
  walk(steps);
  const runs = [r, ...(r.children ?? [])];
  for (const x of runs) for (const e of x.history ?? []) if (e.stage && out[e.stage] !== undefined) out[e.stage] = e.event === "skipped" ? "skipped" : "done";
  for (const x of runs) {
    if (x.stage && out[x.stage] !== undefined && going(x)) out[x.stage] = "now";
    const at = (x.error as { stage?: string } | null | undefined)?.stage;
    if (x.status === "failed" && at && out[at] !== undefined) out[at] = "failed";
  }
  return out;
}

/** A run: its steps, diagram, history, tasks and data; Remind, Reassign, Skip step and Cancel while it is going. */
function RunView({ api, user, id, onClose, onNotice }: { api: CrApi; user: CrUser; id: string; onClose: () => void; onNotice: (t: string) => void }) {
  const [r, setR] = useState<WorkflowRun | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [act, setAct] = useState<null | "reassign" | "skip">(null);
  const [to, setTo] = useState("");
  const [reason, setReason] = useState("");
  const read = useCallback(() => api.workflowRun(id).then(setR, (e) => setError(message(e))), [api, id]);
  useEffect(() => { read(); const t = setInterval(read, 5000); return () => clearInterval(t); }, [read]);
  const [tab, setTab] = useState<"steps" | "diagram" | "timeline" | "tasks" | "data">("steps");
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, [onClose]);
  const states = useMemo(() => r ? stepStates(r, r.steps ?? []) : {}, [r]);
  const title = r ? "Workflow run " + (r.reference || r.id) : "Workflow run";
  const panel = (body: ReactNode, foot: ReactNode) => (
    <aside className="cc-run-panel" role="dialog" aria-modal="false" aria-label={title}>
      <div className="cc-run-head">
        {r && <span className={"cc-pill is-" + (PILL[r.status] ?? "pending")}><span className="cc-pill-dot" />{STATUS[r.status] ?? r.status}</span>}
        <h2>{r ? flowName(r.path) + (r.reference ? " · " + r.reference : "") : "Workflow run"}</h2>
        <HelpButton topic={HELP_RUN} />
        <span className="ov-fill" />
        <CloseView onClick={onClose} />
      </div>
      <div className="cc-run-body">{error && <p className="st-alert" role="alert">{error}</p>}{body}</div>
      {foot && <div className="cc-run-foot">{foot}</div>}
    </aside>
  );
  if (!r) return panel(error ? null : <p className="cc-none" role="status">Loading…</p>, null);
  const operator = user.permissions.includes("runs.stop");
  const openTasks = (r.tasks ?? []).filter((t) => t.status === "open" || t.status === "claimed");
  const forPeople = r.waiting_kind === "person" || r.waiting_kind === "children";
  const done = (m: string) => { onNotice(m); setAct(null); setTo(""); setReason(""); setError(null); read(); };
  const stepRow = (s: WorkflowStepInfo, n: number): ReactNode => {
    const st = states[s.id] ?? "todo";
    const tasks = (r.tasks ?? []).filter((t) => t.stage === s.id && (t.status === "open" || t.status === "claimed"));
    const branchRun = (r.children ?? []).find((c) => c.stage === s.id && going(c));
    const whoHas = st === "now" ? who(tasks.map((t) => t.claimed_by ?? t.assignee).join(",")) || who(branchRun?.waiting_on) || (s.id === r.stage ? who(r.waiting_on) : "") : "";
    const state = ({ done: "done", now: r.late && s.id === r.stage ? "now · late" : "now", failed: "failed here", todo: "to come", skipped: "skipped" } as Record<StepState, string>)[st];
    return <li key={s.id} className={"cc-wf-step is-" + st}>
      <span className="cc-wf-dot" aria-hidden="true">{st === "done" ? "✓" : st === "failed" ? "!" : st === "skipped" ? "–" : n}</span>
      <span className="cc-wf-name">{s.name}</span>
      <span className="cc-none">{state}{whoHas ? " · " + whoHas : ""}</span>
      {s.branches && <ol className="cc-wf-branches">{s.branches.map((b, i) => <li key={i}><span className="cc-wf-branch">{b.name}</span><ol className="cc-wf-steps">{b.steps.map((x, j) => stepRow(x, j + 1))}</ol></li>)}</ol>}
    </li>;
  };
  return panel(<>
      <dl className="cc-facts cc-facts-wide">
        <dt>Workflow</dt><dd>{flowName(r.path)} v{r.version}</dd>
        {r.reference && <><dt>Reference</dt><dd>{r.reference}</dd></>}
        <dt>Status</dt><dd>{STATUS[r.status] ?? r.status}{r.stage_name ? " · " + r.stage_name : ""}{r.waiting_on ? " · waiting on " + who(r.waiting_on) : ""}</dd>
        {r.due && <><dt>Due</dt><dd className={r.late ? "cc-err" : undefined}>{when(r.due)}{r.late ? " (late)" : ""}</dd></>}
        <dt>Started</dt><dd>{when(r.created)}{r.started_by ? " by " + r.started_by : ""}</dd>
        {r.finished && <><dt>Finished</dt><dd>{when(r.finished)}</dd></>}
        {r.error && <><dt>Error</dt><dd className="cc-err">{(r.error.code ?? "") + " " + (r.error.message ?? "")}</dd></>}
        <dt>Run id</dt><dd className="ui-mono">{r.id}</dd>
      </dl>
      <div className="ov-tabs cc-list-tabs cc-run-tabs" role="tablist" aria-label="Run details">
        {([["steps", "Steps"], ["diagram", "Diagram"], ["timeline", "History (" + (r.history ?? []).length + ")"], ["tasks", "People's tasks (" + (r.tasks ?? []).length + ")"], ["data", "Data"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "steps" && <ol className="cc-wf-steps" aria-label="Steps">{(r.steps ?? []).map((s, i) => stepRow(s, i + 1))}</ol>}
      {tab === "diagram" && <div className="cc-wf-diagram"><WorkflowDiagram stages={toSteps(r.steps ?? [])} states={states} label="This run's steps" /></div>}
      {tab === "timeline" && <ol className="cc-run-steps" aria-label="History">
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
      {tab === "tasks" && ((r.tasks ?? []).length ? <table className="cc-list-table" aria-label="People's tasks"><thead><tr><th scope="col">Task</th><th scope="col">Step</th><th scope="col">Assigned to</th><th scope="col">Status</th><th scope="col">Due</th><th scope="col">Done</th></tr></thead>
        <tbody>{r.tasks!.map((t) => <tr key={t.id} className="is-static"><td>{t.title}</td><td>{t.stage ?? ""}</td><td>{who(t.claimed_by ?? t.assignee)}</td><td>{t.outcome ?? t.status}</td><td>{when(t.due)}</td><td>{when(t.completed)}</td></tr>)}</tbody></table>
        : <p className="cc-none">No one has been given a task in this run.</p>)}
      {tab === "data" && (Object.keys(r.data ?? {}).length ? <table className="cc-au-kv" aria-label="Data"><tbody>{Object.entries(r.data ?? {}).map(([k, v]) =>
        <tr key={k}><th scope="row">{k}</th><td>{typeof v === "object" ? JSON.stringify(v) : String(v)}</td></tr>)}</tbody></table> : <p className="cc-none">No data.</p>)}
      {act === "reassign" && <div className="cc-wf-act" role="group" aria-label="Reassign">
        <Field label="Give the open task to">{(fid) => <PrincipalPicker id={fid} label="Give the open task to" kinds={["user", "role"]} source={() => principalDirectory(api)} value={to} onChange={setTo} />}</Field>
        <div className="cc-wf-act-buttons"><Button kind="plain" onClick={() => setAct(null)}>Cancel</Button>
          <Button kind="primary" disabled={!to.trim()} onClick={() => api.reassignWorkflow(r.id, to).then(() => done("Gave the task to " + who(to) + "."), (e) => setError(message(e)))}>Reassign</Button></div>
      </div>}
      {act === "skip" && <div className="cc-wf-act" role="group" aria-label="Skip step">
        <Field label={"Why skip " + (r.stage_name ?? "this step") + "?"}>{(fid) => <TextInput id={fid} value={reason} placeholder="Approved by phone" onChange={(e) => setReason(e.target.value)} />}</Field>
        <p className="st-muted">Its open task is withdrawn and the run goes on to the next step. The reason is kept in the history and the audit trail.</p>
        <div className="cc-wf-act-buttons"><Button kind="plain" onClick={() => setAct(null)}>Cancel</Button>
          <Button kind="danger" disabled={!reason.trim()} onClick={() => api.skipWorkflowStep(r.id, reason.trim()).then(() => done("Skipped " + (r.stage_name ?? "the step") + "."), (e) => setError(message(e)))}>Skip step</Button></div>
      </div>}
    </>, <>
      {openTasks.length > 0 && <a className="ui-btn ui-btn-secondary cc-btn-link" href="#/inbox">Open its task in the Task inbox</a>}
      {operator && going(r) && !act && <>
        {forPeople && <Button kind="secondary" onClick={() => api.remindWorkflow(r.id).then((x) => done("Reminded the people with " + x.tasks + " open task(s)."), (e) => setError(message(e)))}>Remind</Button>}
        {forPeople && <Button kind="secondary" onClick={() => setAct("reassign")}>Reassign…</Button>}
        <Button kind="secondary" onClick={() => setAct("skip")}>Skip step…</Button>
      </>}
      <span className="ov-fill" />
      {going(r) && (confirm
        ? <><span>Cancel this run? Its open tasks are withdrawn.</span><Button kind="plain" onClick={() => setConfirm(false)}>Keep it</Button>
          <Button kind="danger" onClick={() => api.cancelWorkflow(r.id).then(() => { onNotice("Cancelled the run " + (r.reference || r.id) + "."); setConfirm(false); read(); }, (e) => setError(message(e)))}>Cancel run</Button></>
        : <Button kind="danger" onClick={() => setConfirm(true)}>Cancel run</Button>)}
    </>);
}

/** The run's step list as the diagram takes it. */
function toSteps(steps: WorkflowStepInfo[]): Step[] {
  return steps.map((s) => ({ id: s.id, name: s.name, type: s.type, ...(s.branches ? { branches: s.branches.map((b) => ({ name: b.name, stages: toSteps(b.steps) })) } : {}) }));
}
