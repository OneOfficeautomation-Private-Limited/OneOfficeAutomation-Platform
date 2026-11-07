import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, Idea, IdeaList } from "../../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextArea, TextInput, type HelpTopic } from "../../components";
import { RefreshButton, RowMenu, rowClick, when } from "../../console/ListParts";

/**
 * Build > Automation ideas (gap G65, after UiPath's Automation Hub): anyone signed in suggests a process to automate
 * and votes for others' ideas; the automation team (Assess automation ideas) scores each one, moves it through the
 * pipeline and links the agent built, so the estimate and the hours actually saved sit side by side.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export const STATUS_LABEL: Record<string, string> = { submitted: "Submitted", assessing: "Assessing", approved: "Approved", building: "Building",
  live: "Live", rejected: "Rejected", on_hold: "On hold" };
const PILL: Record<string, string> = { submitted: "pending", assessing: "pending", approved: "active", building: "active", live: "success", rejected: "failed", on_hold: "disabled" };

const HELP: HelpTopic = { title: "Automation ideas", about: "Suggest a process to automate, and vote for the ideas you would use. "
  + "The automation team assesses each idea, gives it a score and moves it through the pipeline.",
  items: [
    { name: "Status", text: "Submitted, Assessing, Approved, Building, Live, or Rejected and On hold. You can change or delete your own idea while it is Submitted." },
    { name: "Score", text: "0 to 100, worked out from the assessment: half how automatable the process is (rule-based, structured inputs, stable: 1 to 5 each), "
      + "three tenths the hours saved a year, and a fifth how easy it is to build (low, medium or high complexity). It shows once the three marks are given." },
    { name: "Hours a year", text: "Cases a month times minutes each, for twelve months. Value a year uses the idea's cost of an hour, or the one in Settings." },
    { name: "Saved (30 days)", text: "Once the team links the agent built, the hours its successful runs saved over the last 30 days "
      + "(the agent's own minutes saved, or the idea's minutes each)." },
    { name: "Votes", text: "Vote for an idea to show it matters to you; vote again to take the vote back." },
  ] };

const num = (n?: number | null) => n == null ? "" : n.toLocaleString();

export function Ideas({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [data, setData] = useState<IdeaList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [sort, setSort] = useState<"newest" | "score" | "votes">("newest");
  const [editing, setEditing] = useState<Idea | "new" | null>(null);
  const [deleting, setDeleting] = useState<Idea | null>(null);
  const load = useCallback(async () => {
    try { setData(await api.ideas()); setError(null); } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const fail = (e: unknown) => setError(message(e));
  const list = useMemo(() => {
    const t = q.trim().toLowerCase();
    const l = (data?.ideas ?? []).filter((i) => (!status || i.status === status)
      && (!t || [i.title, i.description, i.department, i.process_owner, i.submitted_by, i.bot_path].join(" ").toLowerCase().includes(t)));
    if (sort === "score") return [...l].sort((a, b) => (b.score ?? -1) - (a.score ?? -1));
    if (sort === "votes") return [...l].sort((a, b) => b.votes - a.votes);
    return l;
  }, [data, q, status, sort]);
  const manage = !!data?.can_manage;
  const canEdit = (i: Idea) => manage || (i.mine && i.status === "submitted");
  const vote = (i: Idea) => api.voteIdea(i.id).then(() => load(), fail);
  const remove = (i: Idea) => api.deleteIdea(i.id).then(() => { onNotice("Deleted the idea " + i.title + "."); setDeleting(null); load(); }, (e) => { setDeleting(null); fail(e); });
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const i of data?.ideas ?? []) c[i.status] = (c[i.status] ?? 0) + 1;
    return c;
  }, [data]);

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-ideas-title">
      <header className="cc-list-head">
        <h1 id="cc-ideas-title">Automation ideas</h1>
        <HelpButton topic={HELP} />
        {data && <span className="cc-list-count">{data.ideas.length}</span>}
        <span className="ov-fill" />
        <Button kind="primary" onClick={() => setEditing("new")}>Suggest an idea</Button>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search ideas" placeholder="Search ideas" value={q} onChange={(e) => setQ(e.target.value)} /></span>
          <Select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}
            options={[{ value: "", label: "Any status" }, ...(data?.statuses ?? Object.keys(STATUS_LABEL)).map((s) => ({ value: s, label: STATUS_LABEL[s] + (counts[s] ? " (" + counts[s] + ")" : "") }))]} />
          <Select aria-label="Sort" value={sort} onChange={(e) => setSort(e.target.value as typeof sort)}
            options={[{ value: "newest", label: "Newest first" }, { value: "score", label: "Highest score" }, { value: "votes", label: "Most votes" }]} />
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the idea {deleting.title}? Its votes and assessment go with it.</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Automation ideas list">
            <thead><tr>{["Idea", "Department", "Status", "Score", "Hours a year", "Saved (30 days)", "Votes", "Suggested"].map((h) => <th key={h} scope="col">{h}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {list.map((i) => (
                <tr key={i.id} onClick={rowClick(() => setEditing(i))}>
                  <td className="cc-clip" title={i.description ?? undefined}>{i.title}</td>
                  <td>{i.department ?? ""}</td>
                  <td><span className={"cc-pill is-" + (PILL[i.status] ?? "pending")}><span className="cc-pill-dot" />{STATUS_LABEL[i.status] ?? i.status}</span></td>
                  <td>{i.score ?? <span title="Not assessed yet">–</span>}</td>
                  <td title={i.value_year != null ? "Value a year: " + num(i.value_year) : undefined}>{i.hours_year ? num(i.hours_year) : ""}</td>
                  <td title={i.bot_path ?? undefined}>{i.bot_path ? (i.hours_saved_30_days != null ? i.hours_saved_30_days + " h (" + i.runs_30_days + " runs)" : (i.runs_30_days ?? 0) + " runs") : ""}</td>
                  <td><Button kind={i.voted ? "secondary" : "plain"} aria-pressed={i.voted} aria-label={(i.voted ? "Take back your vote for " : "Vote for ") + i.title}
                    onClick={() => vote(i)}>▲ {i.votes}</Button></td>
                  <td className="cc-clip" title={i.submitted_by}>{i.submitted_by} {when(i.submitted)}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + i.title} items={[
                    { key: "open", label: canEdit(i) ? "Edit" : "Open", onSelect: () => setEditing(i) },
                    ...(canEdit(i) ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(i) }] : []),
                  ]} /></td>
                </tr>
              ))}
              {data && !list.length && <tr className="is-static"><td colSpan={9} className="cc-none">{data.ideas.length ? "No ideas match." : "No ideas yet. Suggest one with Suggest an idea."}</td></tr>}
              {!data && !error && <tr className="is-static"><td colSpan={9} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {editing && <IdeaDialog api={api} idea={editing === "new" ? null : editing} manage={manage} readOnly={editing !== "new" && !canEdit(editing)}
        onClose={() => setEditing(null)} onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
    </section>
  );
}

const asNum = (s: string) => s.trim() === "" ? null : Number(s);
const str = (n?: number | null) => n == null ? "" : String(n);

/** Suggesting or changing an idea; the assessment part only for the automation team. */
function IdeaDialog({ api, idea, manage, readOnly, onClose, onSaved }:
  { api: CrApi; idea: Idea | null; manage: boolean; readOnly: boolean; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ title: idea?.title ?? "", description: idea?.description ?? "", department: idea?.department ?? "", process_owner: idea?.process_owner ?? "",
    volume_month: str(idea?.volume_month), minutes_each: str(idea?.minutes_each), status: idea?.status ?? "submitted", complexity: idea?.complexity ?? "",
    rules_score: str(idea?.rules_score), structured_score: str(idea?.structured_score), stable_score: str(idea?.stable_score), hourly_cost: str(idea?.hourly_cost),
    bot_path: idea?.bot_path ?? "", notes: idea?.notes ?? "" });
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const save = () => api.saveIdea(idea?.id ?? null, {
    title: v.title.trim(), description: v.description, department: v.department, process_owner: v.process_owner,
    volume_month: asNum(v.volume_month), minutes_each: asNum(v.minutes_each),
    ...(manage ? { status: v.status, complexity: v.complexity || null, rules_score: asNum(v.rules_score), structured_score: asNum(v.structured_score),
      stable_score: asNum(v.stable_score), hourly_cost: asNum(v.hourly_cost), bot_path: v.bot_path, notes: v.notes } : {}),
  }).then(() => onSaved((idea ? "Saved " : "Thanks: suggested ") + v.title.trim() + "."), (e) => setError(message(e)));
  const marks = [{ value: "", label: "Not marked" }, ...[1, 2, 3, 4, 5].map((n) => ({ value: String(n), label: String(n) }))];
  const assess = manage && !!idea;
  return (
    <Dialog open title={idea ? (readOnly ? "Idea " : "Edit idea ") + idea.title : "Suggest an automation idea"} width={720} onClose={onClose}
      footer={readOnly ? <Button kind="plain" onClick={onClose}>Close</Button> : <><Button kind="plain" onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!v.title.trim()} onClick={save}>{idea ? "Save" : "Suggest"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <fieldset disabled={readOnly} className="cc-fieldset">
        <Field label="What should be automated">{(id) => <TextInput id={id} value={v.title} maxLength={200} placeholder="Post supplier invoices from the shared mailbox" onChange={set("title")} />}</Field>
        <Field label="How it is done today" hint="The steps, the systems used, and what goes wrong.">{(id) => <TextArea id={id} rows={4} maxLength={4000} value={v.description} onChange={set("description")} />}</Field>
        <div className="cc-grid2">
          <Field label="Department">{(id) => <TextInput id={id} value={v.department} maxLength={120} placeholder="Finance" onChange={set("department")} />}</Field>
          <Field label="Who runs the process">{(id) => <TextInput id={id} value={v.process_owner} maxLength={120} onChange={set("process_owner")} />}</Field>
          <Field label="Cases a month">{(id) => <TextInput id={id} type="number" min={0} value={v.volume_month} onChange={set("volume_month")} />}</Field>
          <Field label="Minutes each by hand">{(id) => <TextInput id={id} type="number" min={0} value={v.minutes_each} onChange={set("minutes_each")} />}</Field>
        </div>
      </fieldset>
      {idea && (assess || idea.score != null || idea.notes) && <>
        <h3 className="cc-log-title">Assessment{idea.score != null ? ": score " + idea.score : ""}</h3>
        <fieldset disabled={!assess} className="cc-fieldset">
          <div className="cc-grid2">
            <Field label="Status">{(id) => <Select id={id} value={v.status} onChange={set("status")} options={Object.entries(STATUS_LABEL).map(([value, label]) => ({ value, label }))} />}</Field>
            <Field label="Complexity to build">{(id) => <Select id={id} value={v.complexity} onChange={set("complexity")}
              options={[{ value: "", label: "Not judged (medium)" }, { value: "low", label: "Low" }, { value: "medium", label: "Medium" }, { value: "high", label: "High" }]} />}</Field>
            <Field label="Rule-based (1-5)" hint="5: clear rules, no judgement">{(id) => <Select id={id} value={v.rules_score} onChange={set("rules_score")} options={marks} />}</Field>
            <Field label="Structured inputs (1-5)" hint="5: digital forms and fields, not scans or free text">{(id) => <Select id={id} value={v.structured_score} onChange={set("structured_score")} options={marks} />}</Field>
            <Field label="Stable (1-5)" hint="5: the process and its screens rarely change">{(id) => <Select id={id} value={v.stable_score} onChange={set("stable_score")} options={marks} />}</Field>
            <Field label="Cost of an hour" hint="Empty: the one in Settings">{(id) => <TextInput id={id} type="number" min={0} value={v.hourly_cost} onChange={set("hourly_cost")} />}</Field>
          </div>
          <Field label="Agent built" hint="Its path, such as Finance/Invoices.flow.json; its runs show the hours saved.">{(id) => <TextInput id={id} value={v.bot_path} maxLength={400} onChange={set("bot_path")} />}</Field>
          <Field label="Team notes">{(id) => <TextArea id={id} rows={3} maxLength={4000} value={v.notes} onChange={set("notes")} />}</Field>
        </fieldset>
      </>}
      {idea && <p className="cc-none">Suggested by {idea.submitted_by} {when(idea.submitted)}{idea.hours_year ? " · about " + num(idea.hours_year) + " hours a year" : ""}
        {idea.value_year != null ? " (" + num(idea.value_year) + " a year)" : ""} · {idea.votes} vote{idea.votes === 1 ? "" : "s"}</p>}
    </Dialog>
  );
}
