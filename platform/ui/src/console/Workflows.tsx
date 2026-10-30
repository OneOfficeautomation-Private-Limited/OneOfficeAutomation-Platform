import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, TaskFormField, WorkflowRun } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";
import { FormField } from "./Inbox";

/**
 * Manage > Workflow, built as AA's Process Composer requests and UiPath Maestro's instances (checked against their docs
 * and Blue Prism's, 31 Oct 2026): tabs In progress and Finished; each run with its workflow, reference, status and
 * current stage. A run opens with its timeline (agents, people's tasks, decisions) and data, and Cancel while it is
 * going. Start a workflow with its own start form, or data as JSON. Workflows are designed in One AgentStudio.
 */
type Tab = "progress" | "finished";
const STATUS: Record<string, string> = { running: "Running", waiting: "Waiting for a person", completed: "Completed", failed: "Failed", cancelled: "Cancelled" };
const EVENT: Record<string, string> = { agent_started: "Agent started", agent_finished: "Agent finished", agent_failed: "Agent failed", agent_stopped: "Agent stopped", task_created: "Task given",
  task_completed: "Task done", approved: "Approved", rejected: "Rejected", decided: "Decided", notified: "Notified", waiting: "Waiting", failed: "Failed", cancelled: "Cancelled" };
const flowName = (p: string) => p.split("/").pop()!.replace(/\.process\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const going = (r: WorkflowRun) => r.status === "running" || r.status === "waiting";

export function Workflows({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("progress");
  const [runs, setRuns] = useState<WorkflowRun[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [flow, setFlow] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "start" } | { kind: "run"; id: string }>(null);
  const load = useCallback(async () => { try { setRuns(await api.workflowRuns()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(() => { if (!dialog) load(); }, 10_000); return () => clearInterval(t); }, [load, dialog]);
  const shown = useMemo(() => (runs ?? []).filter((r) => (tab === "progress") === going(r) && (!flow || r.path === flow)
    && (!q.trim() || (r.id + " " + (r.reference ?? "") + " " + flowName(r.path) + " " + (r.stage ?? "") + " " + (r.started_by ?? "")).toLowerCase().includes(q.trim().toLowerCase()))), [runs, tab, q, flow]);
  const flows = [...new Set((runs ?? []).map((r) => r.path))].sort();
  const count = (t: Tab) => (runs ?? []).filter((r) => (t === "progress") === going(r)).length;

  return (
    <section className="st-page cc-home" aria-labelledby="cc-wf-title">
      <header className="st-page-head">
        <div><h1 id="cc-wf-title">Workflow</h1><p>Workflows chain agents, people and decisions. Design them in One AgentStudio, put them live, then start them here; people get their steps in the Task inbox.</p></div>
        {user.permissions.includes("runs.start") && <Button kind="primary" onClick={() => setDialog({ kind: "start" })}>Start a workflow</Button>}
      </header>
      <div className="st-page-tabs" role="tablist" aria-label="Workflow runs">
        {(["progress", "finished"] as const).map((t) => <button key={t} type="button" role="tab" className="st-page-tab" aria-selected={tab === t} onClick={() => setTab(t)}>
          {t === "progress" ? "In progress" : "Finished"}{runs && <span className="cc-count">{count(t)}</span>}</button>)}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search workflow runs" placeholder="Search reference, run id, stage, who started it" value={q} onChange={(e) => setQ(e.target.value)} />
        {flows.length > 1 && <Select aria-label="Workflow" value={flow} onChange={(e) => setFlow(e.target.value)} options={[{ value: "", label: "Every workflow" }, ...flows.map((p) => ({ value: p, label: flowName(p) }))]} />}
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label={tab === "progress" ? "Workflow runs in progress" : "Finished workflow runs"}>
          <thead><tr><th scope="col">Status</th><th scope="col">Workflow</th><th scope="col">Reference</th><th scope="col">{tab === "progress" ? "Current stage" : "Last stage"}</th>
            <th scope="col">Started by</th><th scope="col">Started</th><th scope="col">{tab === "progress" ? "Updated" : "Finished"}</th></tr></thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.id} onClick={() => setDialog({ kind: "run", id: r.id })}>
                <td className={"cc-status is-" + (r.status === "completed" ? "success" : r.status === "failed" ? "failed" : r.status === "cancelled" ? "disabled" : "pending")}>{STATUS[r.status] ?? r.status}</td>
                <td>{flowName(r.path)} v{r.version}</td><td>{r.reference ?? ""}</td><td>{r.stage ?? ""}</td><td>{r.started_by ?? ""}</td>
                <td>{when(r.created)}</td><td>{when(tab === "progress" ? r.updated : r.finished)}</td>
              </tr>
            ))}
            {runs && !shown.length && <tr><td colSpan={7} className="st-muted">{tab === "progress" ? "No workflow is running." : "No finished workflow runs."}</td></tr>}
            {!runs && !error && <tr><td colSpan={7} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
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
  if (!r) return <Dialog open title="Workflow run" width={760} onClose={onClose} footer={null}>{error ? <p className="st-alert" role="alert">{error}</p> : <p className="st-muted" role="status">Loading…</p>}</Dialog>;
  return (
    <Dialog open title={"Workflow run " + (r.reference || r.id)} width={760} onClose={onClose} footer={going(r) ? (confirm
      ? <><span>Cancel this run? Its open tasks are withdrawn.</span><Button kind="plain" onClick={() => setConfirm(false)}>Keep it</Button>
        <Button kind="danger" onClick={() => api.cancelWorkflow(r.id).then(() => { onNotice("Cancelled the run " + (r.reference || r.id) + "."); setConfirm(false); read(); }, (e) => setError(message(e)))}>Cancel run</Button></>
      : <Button kind="danger" onClick={() => setConfirm(true)}>Cancel run</Button>) : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <dl className="cc-facts">
        <dt>Workflow</dt><dd>{flowName(r.path)} v{r.version}</dd>
        {r.reference && <><dt>Reference</dt><dd>{r.reference}</dd></>}
        <dt>Status</dt><dd>{STATUS[r.status] ?? r.status}{r.stage ? " · " + r.stage : ""}</dd>
        <dt>Started</dt><dd>{when(r.created)}{r.started_by ? " by " + r.started_by : ""}</dd>
        {r.finished && <><dt>Finished</dt><dd>{when(r.finished)}</dd></>}
        {r.error && <><dt>Error</dt><dd className="cc-err">{(r.error.code ?? "") + " " + (r.error.message ?? "")}</dd></>}
        <dt>Run id</dt><dd className="ui-mono">{r.id}</dd>
      </dl>
      <h3 className="cc-log-title">Timeline</h3>
      <ol className="cc-log" aria-label="Timeline">
        {(r.history ?? []).map((e, i) => <li key={i} className={e.event.endsWith("failed") || e.event === "rejected" ? "is-bad" : undefined}>
          <span className="cc-log-step">{when(e.ts)}</span><strong>{EVENT[e.event] ?? e.event}</strong>{e.stage ? " · " + e.stage : ""}
          {e.detail && <div className="st-muted ui-mono cc-clip">{Object.entries(e.detail).map(([k, v]) => k + ": " + (typeof v === "object" ? JSON.stringify(v) : String(v))).join("; ")}</div>}</li>)}
        {!(r.history ?? []).length && <li className="st-muted">Nothing has happened yet.</li>}
      </ol>
      {(r.tasks ?? []).length > 0 && <>
        <h3 className="cc-log-title">People's tasks</h3>
        <table className="st-table" aria-label="People's tasks"><thead><tr><th scope="col">Task</th><th scope="col">Stage</th><th scope="col">Assigned to</th><th scope="col">Status</th><th scope="col">Done</th></tr></thead>
          <tbody>{r.tasks!.map((t) => <tr key={t.id}><td>{t.title}</td><td>{t.stage ?? ""}</td><td>{t.claimed_by ?? t.assignee ?? ""}</td><td>{t.outcome ?? t.status}</td><td>{when(t.completed)}</td></tr>)}</tbody></table>
      </>}
      <h3 className="cc-log-title">Data</h3>
      <pre className="cc-codes">{JSON.stringify(r.data ?? {}, null, 2)}</pre>
    </Dialog>
  );
}
