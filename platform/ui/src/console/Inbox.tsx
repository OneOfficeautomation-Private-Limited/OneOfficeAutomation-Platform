import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, HumanTask, TaskFormField } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * Manage > Task inbox, built as AA's Co-Pilot tasks and UiPath's Action Center (checked against their docs and Blue
 * Prism's, 31 Oct 2026): tabs To do and Done; filters by kind, overdue and a search; administrators can see everyone's
 * tasks. A task opens with its form, approval or notice, the workflow's data, a comment, and Take it / Give back so two
 * people do not work on the same task. The list refreshes every 30 seconds.
 */
type Tab = "open" | "completed";
const KIND: Record<string, string> = { form: "Fill in", approval: "Approve", notification: "Notice" };
const OUTCOME: Record<string, string> = { submitted: "Submitted", approved: "Approved", rejected: "Rejected", done: "Read", expired: "Expired", cancelled: "Cancelled" };
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function Inbox({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("open");
  const [all, setAll] = useState(false);
  const [tasks, setTasks] = useState<HumanTask[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [open, setOpen] = useState<HumanTask | null>(null);
  const admin = user.permissions.includes("users.manage");

  const load = useCallback(async () => {
    try { setTasks(await api.tasks(tab, all)); setError(null); } catch (e) { setError(message(e)); }
  }, [api, tab, all]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(() => { if (!open) load(); }, 30_000); return () => clearInterval(t); }, [load, open]);

  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    return (tasks ?? []).filter((t) => (!text || (t.title + " " + (t.description ?? "") + " " + (t.assignee ?? "")).toLowerCase().includes(text))
      && (!f.kind || t.kind === f.kind) && (!f.overdue || t.overdue) && (!f.mine || t.claimed_by === user.username));
  }, [tasks, f, user.username]);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-inbox-title">
      <header className="st-page-head">
        <div><h1 id="cc-inbox-title">Task inbox</h1><p>Work that workflows give you or your role: forms to fill in, approvals and notices.</p></div>
      </header>
      <div className="st-page-tabs" role="tablist" aria-label="Task inbox">
        {(["open", "completed"] as const).map((t) => <button key={t} type="button" role="tab" className="st-page-tab" aria-selected={tab === t} onClick={() => { setTab(t); setTasks(null); }}>
          {t === "open" ? "To do" : "Done"}{tab === t && tasks && <span className="cc-count">{tasks.length}</span>}</button>)}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search tasks" placeholder="Search tasks" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} />
        <Select aria-label="Kind" value={f.kind ?? ""} onChange={(e) => setF({ ...f, kind: e.target.value })} options={[{ value: "", label: "Every kind" }, ...Object.entries(KIND).map(([value, label]) => ({ value, label }))]} />
        {tab === "open" && <>
          <label className="st-check-line"><input type="checkbox" checked={!!f.overdue} onChange={(e) => setF({ ...f, overdue: e.target.checked ? "1" : "" })} /> Overdue only</label>
          <label className="st-check-line"><input type="checkbox" checked={!!f.mine} onChange={(e) => setF({ ...f, mine: e.target.checked ? "1" : "" })} /> Taken by me</label>
        </>}
        {admin && <label className="st-check-line"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> Everyone's tasks</label>}
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label={tab === "open" ? "Tasks to do" : "Tasks done"}>
          <thead><tr><th scope="col">Task</th><th scope="col">Kind</th><th scope="col">Assigned to</th>
            {tab === "open" ? <><th scope="col">Due</th><th scope="col">Given</th></> : <><th scope="col">Outcome</th><th scope="col">Done by</th><th scope="col">Done</th></>}</tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} onClick={() => setOpen(t)}>
                <td>{t.title}{t.description && <div className="st-muted">{t.description}</div>}</td>
                <td>{KIND[t.kind] ?? t.kind}</td>
                <td>{t.claimed_by ? t.claimed_by + " (working on it)" : t.assignee}</td>
                {tab === "open" ? <><td className={t.overdue ? "cc-err" : undefined}>{t.due ? (t.overdue ? "Overdue: " : "") + when(t.due) : ""}</td><td>{when(t.created)}</td></>
                  : <><td>{OUTCOME[t.outcome ?? ""] ?? t.outcome ?? t.status}</td><td>{t.completed_by ?? ""}</td><td>{when(t.completed)}</td></>}
              </tr>
            ))}
            {tasks && !shown.length && <tr><td colSpan={5} className="st-muted">{tasks.length ? "No tasks match." : tab === "open" ? "Nothing waiting for you." : "No completed tasks yet."}</td></tr>}
            {!tasks && !error && <tr><td colSpan={5} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {open && <TaskDialog key={open.id} api={api} task={open} onClose={() => setOpen(null)} onDone={(t) => { onNotice(t); setOpen(null); load(); }} />}
    </section>
  );
}

/** A task: its form (or approval, or notice), the workflow's data, a comment, and the actions open to the person. */
function TaskDialog({ api, task: t, onClose, onDone }: { api: CrApi; task: HumanTask; onClose: () => void; onDone: (t: string) => void }) {
  const fields = (t.kind === "form" ? t.form?.fields ?? [] : []);
  const [answers, setAnswers] = useState<Record<string, unknown>>(() => Object.fromEntries(fields.filter((x) => x.id).map((x) => [x.id, (t.result ?? {})[x.id] ?? x.default ?? (x.type === "checkbox" ? false : "")])));
  const [comment, setComment] = useState("");
  const [error, setError] = useState<string | null>(null);
  const act = !!t.can_act;
  const missing = fields.filter((x) => x.required && (answers[x.id] === "" || answers[x.id] === undefined)).map((x) => x.label ?? x.id);
  const submit = (outcome: string) => {
    if (outcome !== "rejected" && missing.length) { setError("Fill in: " + missing.join(", ")); return; }
    api.completeTask(t.id, outcome, answers, comment).then(() => onDone((OUTCOME[outcome] ?? "Done") + ": " + t.title + "."), (e) => setError(message(e)));
  };
  const claim = (release: boolean) => api.claimTask(t.id, release).then(() => onDone(release ? "Gave back " + t.title + "." : "Took " + t.title + ": others no longer see it as open."), (e) => setError(message(e)));
  const data = Object.entries(t.data ?? {});
  return (
    <Dialog open title={t.title} width={640} onClose={onClose} footer={act ? <>
      {t.status === "open" && t.kind !== "notification" && <Button kind="secondary" onClick={() => claim(false)}>Take it</Button>}
      {t.status === "claimed" && <Button kind="secondary" onClick={() => claim(true)}>Give back</Button>}
      <span className="st-bar-fill" />
      {t.kind === "approval" ? <><Button kind="danger" onClick={() => submit("rejected")}>Reject</Button><Button kind="primary" onClick={() => submit("approved")}>Approve</Button></>
        : t.kind === "form" ? <Button kind="primary" onClick={() => submit("submitted")}>{t.form?.submitLabel || "Submit"}</Button>
          : <Button kind="primary" onClick={() => submit("done")}>Mark as read</Button>}</> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {t.description && <p>{t.description}</p>}
      <dl className="cc-facts">
        <dt>Kind</dt><dd>{KIND[t.kind] ?? t.kind}</dd>
        <dt>Assigned to</dt><dd>{t.assignee}{t.claimed_by ? " · taken by " + t.claimed_by : ""}</dd>
        {t.due && <><dt>Due</dt><dd className={t.overdue ? "cc-err" : undefined}>{when(t.due)}{t.overdue ? " (overdue)" : ""}</dd></>}
        {t.completed && <><dt>Done</dt><dd>{when(t.completed)}{t.completed_by ? " by " + t.completed_by : ""}{t.outcome ? " · " + (OUTCOME[t.outcome] ?? t.outcome) : ""}</dd></>}
      </dl>
      {fields.length > 0 && <div className="cc-taskform" role="group" aria-label="Form">
        {fields.map((x) => <FormField key={x.id || x.label} field={x} value={answers[x.id]} disabled={!act} onChange={(v) => setAnswers({ ...answers, [x.id]: v })} />)}
      </div>}
      {act && t.kind !== "notification" && <Field label="Comment (optional)">{(id) => <TextArea id={id} rows={2} value={comment} onChange={(e) => setComment(e.target.value)} />}</Field>}
      {data.length > 0 && <details open={t.kind === "approval"}><summary>Workflow data</summary>
        <dl className="cc-facts">{data.map(([k, v]) => [<dt key={k + "t"}>{k}</dt>, <dd key={k + "d"}>{typeof v === "object" ? JSON.stringify(v) : String(v)}</dd>])}</dl></details>}
    </Dialog>
  );
}

/** One field of a task form: text, multi-line, number, date, e-mail, drop-down, choice, tick box, heading or paragraph. */
export function FormField({ field: x, value, disabled, onChange }: { field: TaskFormField; value: unknown; disabled: boolean; onChange: (v: unknown) => void }) {
  if (x.type === "heading") return <h3 className="cc-log-title">{x.label}</h3>;
  if (x.type === "paragraph") return <p className="st-muted">{x.text}</p>;
  const label = (x.label || x.id) + (x.required ? " *" : "");
  if (x.type === "checkbox") return <label className="st-check-line"><input type="checkbox" checked={value === true || value === "true"} disabled={disabled} onChange={(e) => onChange(e.target.checked)} /> {label}</label>;
  if (x.type === "radio") return <fieldset className="cc-radio" disabled={disabled}><legend>{label}</legend>
    {(x.options ?? []).map((o) => <label key={o} className="st-check-line"><input type="radio" name={"tf-" + x.id} checked={value === o} onChange={() => onChange(o)} /> {o}</label>)}</fieldset>;
  return <Field label={label}>{(id) => x.type === "multiline" ? <TextArea id={id} rows={3} value={String(value ?? "")} placeholder={x.placeholder} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
    : x.type === "dropdown" ? <Select id={id} value={String(value ?? "")} disabled={disabled} onChange={(e) => onChange(e.target.value)} options={[{ value: "", label: "(choose)" }, ...(x.options ?? []).map((o) => ({ value: o, label: o }))]} />
      : <TextInput id={id} type={x.type === "number" ? "number" : x.type === "date" ? "date" : x.type === "email" ? "email" : "text"} min={x.min} max={x.max} placeholder={x.placeholder}
        value={String(value ?? "")} disabled={disabled} onChange={(e) => onChange(x.type === "number" && e.target.value !== "" ? Number(e.target.value) : e.target.value)} />}</Field>;
}
