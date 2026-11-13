import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, HumanTask, TaskFormField } from "../api/cr";
import { Button, Field, HelpButton, Select, TextArea, TextInput, type HelpTopic, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick, when, CloseView } from "./ListParts";
import { PageIntro } from "../shell/PageIntro";

/**
 * Manage > Task inbox, built as AA's Co-Pilot tasks and UiPath's Action Center (checked against their docs and Blue
 * Prism's, 31 Oct 2026): tabs To do and Done; filters by kind, overdue and a search; administrators can see everyone's
 * tasks. A task opens with its form, approval or notice, the workflow's data, a comment, and Take it / Give back so two
 * people do not work on the same task. The list refreshes every 30 seconds.
 * Laid out as the design plan's step 8 (approved on the canvas 31 Oct 2026): the list pattern with a tile per kind and
 * Overdue as a pill, and a task in a side panel beside the list (where it comes from, what to decide, the actions).
 * Review round 2 (31 Oct 2026), as AA's task list (status, request, reference): one line per task, where it comes from
 * (its workflow and the request's reference) and its state in columns of their own, and a row menu.
 */
type Tab = "open" | "completed";
const KIND: Record<string, string> = { form: "Fill in", approval: "Approve", notification: "Notice" };
/** The tile of each kind of task: a letter on its colour (white text at least 4.5:1). */
const TILE: Record<string, [string, string]> = { approval: ["A", "#1d6b38"], form: ["F", "#2f6fde"], notification: ["N", "#475569"] };
const OUTCOME: Record<string, string> = { submitted: "Submitted", approved: "Approved", rejected: "Rejected", done: "Read", expired: "Expired", cancelled: "Cancelled" };
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

const HELP: HelpTopic = { title: "Task inbox", about: "Work that workflows give you or your role: forms to fill in, approvals and notices. "
  + "Click a task to open it beside the list; the list refreshes every 30 seconds.",
  items: [
    { name: "To do", text: "Tasks waiting for you or your role, with when they are due. Overdue tasks are marked." },
    { name: "Done", text: "Tasks that were finished, with the outcome (such as Approved or Submitted), who did it and when." },
    { name: "Kind", text: "Show only forms to fill in, approvals or notices." },
    { name: "Overdue only, Taken by me", text: "On To do: show only tasks past their due time, or only the tasks you have taken." },
    { name: "Everyone's tasks", text: "Shows the tasks of every person and role, not only yours. It needs the Manage users and roles permission." },
    { name: "Comes from", text: "The workflow that gave the task and the reference of its request." },
    { name: "Status", text: "Waiting until someone takes the task, then Taken by that person, so two people do not work on the same task." },
    { name: "Actions (⋯)", text: "Open the task, take it, or give it back so others can take it." },
  ] };
const HELP_TASK: HelpTopic = { title: "A task", about: "One task beside the list: where it comes from, what to decide or fill in, and the actions open to you.",
  items: [
    { name: "What to decide, Workflow data", text: "The data the workflow passed with the task, to help you decide or fill in the form." },
    { name: "Take it", text: "Marks the task as yours, so others who share it see it is being worked on. Do it first when the task goes to a role." },
    { name: "Give back", text: "Puts the task back as waiting, for someone else to take." },
    { name: "Comment (optional)", text: "A note saved with your answer, for example why you rejected it." },
    { name: "Approve, Reject", text: "For an approval: your decision goes back to the workflow, which then goes on." },
    { name: "Submit", text: "For a form: sends your answers to the workflow. Fields marked * must be filled in first." },
    { name: "Mark as read", text: "For a notice: says you have seen it and moves it to Done." },
  ] };

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
    return (tasks ?? []).filter((t) => (!text || (t.title + " " + (t.description ?? "") + " " + (t.assignee ?? "") + " " + (t.workflow ?? "") + " " + (t.reference ?? "")).toLowerCase().includes(text))
      && (!f.kind || t.kind === f.kind) && (!f.overdue || t.overdue) && (!f.mine || t.claimed_by === user.username));
  }, [tasks, f, user.username]);

  const overdue = (tasks ?? []).filter((t) => t.overdue).length;
  const filtered = Object.values(f).some(Boolean);
  const claim = (t: HumanTask, release: boolean) => api.claimTask(t.id, release).then(() => { onNotice(release ? "Gave back " + t.title + "." : "Took " + t.title + ": others no longer see it as open."); load(); }, (e) => setError(message(e)));
  const rowMenu = (t: HumanTask) => [
    { key: "open", label: "Open", onSelect: () => setOpen(t) },
    ...(t.can_act && t.status === "open" && t.kind !== "notification" ? [{ key: "take", label: "Take it", onSelect: () => claim(t, false) }] : []),
    ...(t.can_act && t.status === "claimed" ? [{ key: "back", label: "Give back", onSelect: () => claim(t, true) }] : []),
  ];
  const comesFrom = (t: HumanTask) => [t.workflow, t.reference].filter(Boolean).join(" · ");
  const tile = (k: string) => { const [m, c] = TILE[k] ?? ["?", "#475569"]; return <span className="cc-task-tile" style={{ background: c }} title={KIND[k] ?? k} aria-hidden="true">{m}</span>; };

  return (
    <section className={"st-page cc-home cc-list" + (open ? " has-run" : "")} aria-labelledby="cc-inbox-title">
      <header className="cc-list-head">
        <h1 id="cc-inbox-title">Task inbox</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:inbox" />
        {tasks && <span className="cc-list-count">{tab === "open" ? tasks.length + " to do" + (overdue ? " · " + overdue + " overdue" : "") : tasks.length + " done"}</span>}
        <span className="ov-fill" />
      </header>
      <div className="ov-tabs cc-list-tabs" role="tablist" aria-label="Task inbox">
        {(["open", "completed"] as const).map((t) => <button key={t} type="button" role="tab" className="ov-tab" aria-selected={tab === t} onClick={() => { setTab(t); setTasks(null); setOpen(null); }}>
          {t === "open" ? "To do" : "Done"}{tab === t && tasks && <span className="ov-cnt">{tasks.length}</span>}</button>)}
      </div>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search tasks" placeholder="Search tasks" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} /></span>
          <span className={"cc-chip" + (f.kind ? " is-on" : "")}><Select aria-label="Kind" value={f.kind ?? ""} onChange={(e) => setF({ ...f, kind: e.target.value })}
            options={[{ value: "", label: "Kind: Any" }, ...Object.entries(KIND).map(([value, label]) => ({ value, label: "Kind: " + label }))]} /></span>
          {tab === "open" && <>
            <label className={"cc-chip-check" + (f.overdue ? " is-on" : "")}><input type="checkbox" checked={!!f.overdue} onChange={(e) => setF({ ...f, overdue: e.target.checked ? "1" : "" })} /> Overdue only</label>
            <label className={"cc-chip-check" + (f.mine ? " is-on" : "")}><input type="checkbox" checked={!!f.mine} onChange={(e) => setF({ ...f, mine: e.target.checked ? "1" : "" })} /> Taken by me</label>
          </>}
          {admin && <label className={"cc-chip-check" + (all ? " is-on" : "")}><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> Everyone's tasks</label>}
          {filtered && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Task inbox list tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label={tab === "open" ? "Tasks to do" : "Tasks done"}>
            <thead><tr><th scope="col">Task</th><th scope="col">Comes from</th>{tab === "open" && <th scope="col">Status</th>}<th scope="col">Assigned to</th>
              {tab === "open" ? <><th scope="col">Due</th><th scope="col">Created</th></> : <><th scope="col">Outcome</th><th scope="col">Done by</th><th scope="col">Done</th></>}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((t) => (
                <tr key={t.id} className={open?.id === t.id ? "is-open" : undefined} onClick={rowClick(() => setOpen(t))}>
                  <td title={t.description ?? undefined}>{tile(t.kind)}<strong>{t.title}</strong><span className="st-sr"> ({KIND[t.kind] ?? t.kind})</span></td>
                  <td>{comesFrom(t) || <span className="cc-none">–</span>}</td>
                  {tab === "open" && <td>{t.claimed_by ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Taken by {t.claimed_by === user.username ? "you" : t.claimed_by}</span> : <span className="cc-pill is-stopped">Waiting</span>}</td>}
                  <td>{t.assignee}</td>
                  {tab === "open" ? <><td>{t.overdue && <span className="cc-pill is-failed"><span className="cc-pill-dot" />Overdue</span>} {t.due ? when(t.due) : <span className="cc-none">–</span>}</td><td>{when(t.created)}</td></>
                    : <><td>{OUTCOME[t.outcome ?? ""] ?? t.outcome ?? t.status}</td><td>{t.completed_by ?? ""}</td><td>{when(t.completed)}</td></>}
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + t.title} items={rowMenu(t)} /></td>
                </tr>
              ))}
              {tasks && !shown.length && <tr className="is-static"><td colSpan={8} className="cc-none">{tasks.length ? "No tasks match." : tab === "open" ? "Nothing waiting for you." : "No completed tasks yet."}</td></tr>}
              {!tasks && !error && <tr className="is-static"><td colSpan={8} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {tasks && <div className="cc-list-pager"><span>{shown.length} of {tasks.length}{filtered ? " matching" : ""} · refreshes every 30 seconds</span></div>}
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
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, [onClose]);
  const [m, c] = TILE[t.kind] ?? ["?", "#475569"];
  return (
    <aside className="cc-run-panel cc-task-panel" role="dialog" aria-modal="false" aria-label={t.title}>
      <div className="cc-run-head">
        <span className="cc-task-tile" style={{ background: c }} aria-hidden="true">{m}</span>
        <h2>{t.title}</h2>
        <HelpButton topic={HELP_TASK} />
        {t.overdue && <span className="cc-pill is-failed"><span className="cc-pill-dot" />Overdue</span>}
        <span className="ov-fill" />
        <CloseView onClick={onClose} />
      </div>
      <div className="cc-run-body">
        {t.stages && t.stages.length > 1 && <StageBar stages={t.stages} current={t.stage ?? null} done={!!t.completed} />}
        {error && <p className="st-alert" role="alert">{error}</p>}
        <p className="cc-task-from">{KIND[t.kind] ?? t.kind}{t.workflow_run_id ? <>, from the workflow run <span className="ui-mono">{t.workflow_run_id}</span></> : null}.
          Given to {t.assignee}{t.created ? " on " + when(t.created) : ""}; {t.claimed_by ? "taken by " + t.claimed_by : "nobody has taken it yet"}.
          {t.due && <> Due {when(t.due)}{t.overdue ? " (overdue)" : ""}.</>}
          {t.completed && <> Done {when(t.completed)}{t.completed_by ? " by " + t.completed_by : ""}{t.outcome ? " · " + (OUTCOME[t.outcome] ?? t.outcome) : ""}.</>}</p>
        {t.description && <p>{t.description}</p>}
        {data.length > 0 && <section aria-label={t.kind === "approval" ? "What to decide" : "Workflow data"}>
          <h3 className="cc-au-sec">{t.kind === "approval" ? "What to decide" : "Workflow data"}</h3>
          <table className="cc-au-kv"><tbody>{data.map(([k, v]) => <tr key={k}><th scope="row">{k}</th><td>{typeof v === "object" ? JSON.stringify(v) : String(v)}</td></tr>)}</tbody></table>
        </section>}
        {fields.length > 0 && <div className="cc-taskform" role="group" aria-label="Form">
          {fields.map((x) => <FormField key={x.id || x.label} field={x} value={answers[x.id]} disabled={!act} onChange={(v) => setAnswers({ ...answers, [x.id]: v })} />)}
        </div>}
        {act && t.kind !== "notification" && <Field label="Comment (optional)">{(id) => <TextArea id={id} rows={2} value={comment} onChange={(e) => setComment(e.target.value)} />}</Field>}
        {act && t.status === "open" && t.kind !== "notification" && <p className="cc-none">Take it first if others share this task: they then see it as being worked on.</p>}
      </div>
      {act && <div className="cc-run-foot">
        {t.status === "open" && t.kind !== "notification" && <Button kind="secondary" onClick={() => claim(false)}>Take it</Button>}
        {t.status === "claimed" && <Button kind="secondary" onClick={() => claim(true)}>Give back</Button>}
        <span className="ov-fill" />
        {t.kind === "approval" ? <><Button kind="danger" onClick={() => submit("rejected")}>Reject</Button><Button kind="primary" onClick={() => submit("approved")}>Approve</Button></>
          : t.kind === "form" ? <Button kind="primary" onClick={() => submit("submitted")}>{t.form?.submitLabel || "Submit"}</Button>
            : <Button kind="primary" onClick={() => submit("done")}>Mark as read</Button>}
      </div>}
    </aside>
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

/**
 * Where the request is (G48, as a business process flow's stage bar): the workflow's stages in order, the ones before
 * this task done, this one current. Read-only: the workflow moves on when the task is completed.
 */
export function StageBar({ stages, current, done }: { stages: { id: string; name: string }[]; current: string | null; done: boolean }) {
  const at = stages.findIndex((s) => s.id === current);
  return (
    <ol className="cc-stagebar" aria-label="Stages of this request">
      {stages.map((s, i) => {
        const state = at < 0 ? "next" : i < at || (done && i === at) ? "done" : i === at ? "current" : "next";
        return <li key={s.id} className={"is-" + state} aria-current={state === "current" ? "step" : undefined}>
          <span className="cc-stagebar-mark" aria-hidden="true">{state === "done" ? "✓" : i + 1}</span>
          <span>{s.name}</span><span className="st-sr">{state === "done" ? " (done)" : state === "current" ? " (this task)" : " (to come)"}</span></li>;
      })}
    </ol>
  );
}
