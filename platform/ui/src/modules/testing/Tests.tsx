import "./consoleApi";
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { CrApi, CrUser, Runner, RunnerPool, TestRunSummary, TestSet } from "../../api/cr";
import { Button, Dialog, Field, HelpButton, Menu, Select, TextArea, TextInput, type HelpTopic, reportMinor, PageError } from "../../components";
import { RefreshButton, RowMenu, rowClick, when } from "../../console/ListParts";
import { FolderIcon } from "../../console/Repository";

/**
 * Build > Agent tests, after UiPath's Test Manager test sets (AA keeps testing out of its Control Room; checked
 * 31 Oct 2026): a test set runs an agent with chosen inputs and checks what it gives back, also for AI answers
 * (contains, matches, close to a number, one of). Each run of a set is scored per case and kept.
 * Laid out as Automation (review round 2, approved on the canvas 31 Oct 2026): the agents' folders on the left, the
 * test sets with their last result on the right. A test set opens as its own page: its cases, its runs (a run's cases
 * below, a case's checks beside, as UiPath opens a test case's log) and its details.
 */
const EXAMPLE = [
  { name: "urgent mail", inputs: { text: "The server is down!" }, expect: { outputs: { label: { op: "one_of", value: ["urgent", "high"] } } } },
  { name: "thank you", inputs: { text: "Thanks a lot" }, expect: { outputs: { label: "low" } } },
  { name: "empty text fails", inputs: { text: "" }, expect: { status: "failed", error_code: "TRIAGE.*" } }];
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const folderOf = (p: string) => p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "";
const under = (f: string, p: string) => !p || f === p || f.startsWith(p + "/");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const took = (r: { started: string; finished?: string | null }) => {
  if (!r.finished) return "";
  const s = Math.max(0, Math.round((new Date(r.finished).getTime() - new Date(r.started).getTime()) / 1000));
  return s < 60 ? s + " s" : Math.floor(s / 60) + " min " + (s % 60) + " s";
};
const running = (r?: TestRunSummary | null) => r?.status === "running";

/** The last result as a pill: passing, failing, running or not run. */
function ResultPill({ r }: { r?: TestRunSummary | null }) {
  if (!r) return <span className="cc-pill is-stopped">Not run</span>;
  if (running(r)) return <span className="cc-pill is-pending"><span className="cc-pill-dot" />Running</span>;
  return r.passed === r.total ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Passing</span> : <span className="cc-pill is-failed"><span className="cc-pill-dot" />Failing</span>;
}

/** A score as a bar and "n of m". */
function Score({ r }: { r?: TestRunSummary | null }) {
  if (!r) return <span className="cc-none">–</span>;
  if (running(r)) return <span className="cc-none">Running…</span>;
  return <span title={r.passed + " of " + r.total + " cases passed"}><span className="cc-meter" aria-hidden="true"><span style={{ width: (r.total ? 100 * r.passed / r.total : 0) + "%" }} /></span> {r.passed} of {r.total}</span>;
}

/** One check of a case, in words. */
type Check = { key: string; text: string; ok?: boolean | null; got?: string };

const OPS: Record<string, string> = { contains: "contains ", not_contains: "does not contain ", matches: "matches ", one_of: "is one of ", approx: "is close to " };

/** What a case checks, and (with a result) which checks failed: from its expect and the result's reasons. */
function checksOf(expect: Record<string, unknown> | undefined, result?: { status: string; passed: boolean; detail?: string | null }): Check[] {
  const e = expect ?? {};
  const why = result?.detail ? result.detail.split("; ") : [];
  const judged = !!result && result.status !== "running";
  const statusWrong = why.some((w) => w.startsWith("status "));
  const verdict = (miss: string | undefined, after = true): Pick<Check, "ok" | "got"> =>
    !judged ? {} : after && statusWrong ? { ok: null } : miss ? { ok: false, got: miss } : { ok: true };
  const out: Check[] = [{ key: "status", text: "finishes with " + String(e.status ?? "success"), ...verdict(why.find((w) => w.startsWith("status ")), false) }];
  if (typeof e.error_code === "string") out.push({ key: "error", text: "error code is " + e.error_code, ...verdict(why.find((w) => w.startsWith("error "))) });
  const rule = (v: unknown) => {
    if (!v || typeof v !== "object" || Array.isArray(v) || !("op" in v)) return "equals " + JSON.stringify(v);
    const r = v as { op: string; value?: unknown; tolerance?: number };
    if (r.op === "not_empty") return "is not empty";
    const value = r.op === "one_of" && Array.isArray(r.value) ? r.value.map((x) => JSON.stringify(x)).join(", ") : JSON.stringify(r.value);
    return (OPS[r.op] ?? r.op + " ") + value + (r.op === "approx" ? " (± " + (r.tolerance ?? 0) + ")" : "");
  };
  const outs = e.outputs && typeof e.outputs === "object" ? e.outputs as Record<string, unknown> : {};
  for (const [k, v] of Object.entries(outs)) out.push({ key: "out:" + k, text: k + " " + rule(v), ...verdict(why.find((w) => w.startsWith(k + ": "))?.slice(k.length + 2)) });
  return out;
}

function Checks({ list }: { list: Check[] }) {
  return <ul className="cc-checks" aria-label="Checks">{list.map((c) => <li key={c.key} className={c.ok === false ? "is-bad" : c.ok ? "is-ok" : undefined}>
    <span className="cc-check-mark" aria-hidden="true">{c.ok === false ? "✗" : c.ok ? "✓" : "•"}</span>
    <span><span className="st-sr">{c.ok === false ? "Failed: " : c.ok ? "Passed: " : c.ok === null ? "Not checked: " : ""}</span>{c.text}
      {c.got && <span className="cc-check-got">{c.got}</span>}{c.ok === null && <span className="cc-none"> (not checked: the run did not finish as expected)</span>}</span></li>)}</ul>;
}

function Inputs({ inputs }: { inputs?: Record<string, unknown> }) {
  const e = Object.entries(inputs ?? {});
  return e.length ? <table className="cc-au-kv" aria-label="Inputs"><tbody>{e.map(([k, v]) => <tr key={k}><th scope="row">{k}</th><td className="ui-mono">{typeof v === "string" ? v || "(empty)" : JSON.stringify(v)}</td></tr>)}</tbody></table>
    : <p className="cc-none">No inputs.</p>;
}

const HELP: HelpTopic = { title: "Agent tests",
  about: "Test sets run an agent with chosen inputs and check what it gives back, also for AI answers. Each run of a set is scored and kept, so you see "
    + "when an agent starts to fail.",
  items: [
    { name: "Folders", text: "A test set sits in its agent's folder, as in Automation. Pick a folder to list its test sets; All test sets shows every one." },
    { name: "Last result", text: "Passing when every case passed in the latest run, Failing when one or more failed, or Not run. The filter shows only one "
      + "of these." },
    { name: "Last score and Version tested", text: "How many cases passed in the latest run, and which version of the agent it tested." },
    { name: "New test set", text: "In Create: choose the agent, then write its cases as JSON. Each case has a name, the agent's inputs and what to expect. "
      + "Needs permission to check in agents." },
    { name: "Cases (JSON)", text: "In expect, give status (success or failed), error_code (EMAIL.* allowed) and outputs. An output equals a value, or uses "
      + "a rule: contains, not_contains, matches, approx (with tolerance), one_of or not_empty." },
    { name: "Open and Runs", text: "Open shows the test set's own page; Runs opens it on its runs. Clicking the row opens it too." },
    { name: "Edit and Delete…", text: "Change the name, agent, description and cases, or delete the set with all its results. Needs permission to check in "
      + "agents." },
  ] };

const HELP_SET: HelpTopic = { title: "Test set",
  about: "One test set: its cases, its runs and its details. Each case runs the agent's live version once with its inputs and checks what it gives back.",
  items: [
    { name: "Run on and Run", text: "Choose a runner or a runner pool, then Run to run every case. The run's results fill in as cases finish. Needs "
      + "permission to start runs." },
    { name: "Cases", text: "Every case with its inputs and what is checked. Click a case to see its inputs and checks at the right." },
    { name: "Runs", text: "Every run of the set with its score, who started it and how long it took. Click a run to list its cases below; click a case to "
      + "see which checks failed." },
    { name: "Show", text: "In a run's cases: show all of them, only the failed ones or only the passed ones." },
    { name: "Open run", text: "Opens the agent's own run for a case in Agent Activity, to see its log." },
    { name: "Details", text: "The agent tested, its folder, how many cases there are and how many runs are kept." },
    { name: "Edit", text: "Changes the name, agent, description and cases. Needs permission to check in agents." },
  ] };

export function Tests({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<TestSet[] | null>(null);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [only, setOnly] = useState("");
  const [folder, setFolder] = useState("");
  const [opened, setOpened] = useState<string[]>([]);
  const [open, setOpen] = useState<{ id: string; tab?: string; run?: string } | null>(null);
  const [dialog, setDialog] = useState<null | { kind: "edit"; t: TestSet | null }>(null);
  const [ask, setAsk] = useState<TestSet | null>(null);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => {
    try { const [l, d, p] = await Promise.all([api.testSets(), api.devices().catch(() => []), api.pools().catch(() => [])]); setList(l); setRunners(d); setPools(p); setError(null); }
    catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const all = list ?? [];
  const folders = useMemo(() => [...new Set(all.flatMap((t) => { const f = folderOf(t.bot_path); return f ? f.split("/").map((_, i, a) => a.slice(0, i + 1).join("/")) : []; }))].sort(), [all]);
  const filtered = !!q.trim() || !!only;
  const shown = useMemo(() => all.filter((t) => under(folderOf(t.bot_path), folder)
    && (!q.trim() || (t.name + " " + t.bot_path + " " + (t.description ?? "")).toLowerCase().includes(q.trim().toLowerCase()))
    && (!only || (only === "failing" ? !!t.last_run && !running(t.last_run) && t.last_run.passed < t.last_run.total : only === "passing" ? !!t.last_run && !running(t.last_run) && t.last_run.passed === t.last_run.total : !t.last_run))), [all, q, only, folder]);
  const kids = (p: string) => folders.filter((f) => folderOf(f) === p);
  const total = (p: string) => all.filter((t) => under(folderOf(t.bot_path), p)).length;
  const isOpen = (p: string) => opened.includes(p) || folder.startsWith(p + "/");
  const node = (f: string, depth: number): ReactNode => {
    const children = kids(f), o = isOpen(f), name = f.split("/").pop();
    return <li key={f}>
      <div className={"cc-tree-row" + (folder === f ? " is-on" : "")} style={{ paddingLeft: 6 + 16 * depth }}>
        {children.length ? <button type="button" className="cc-tree-chev" aria-expanded={o} aria-label={(o ? "Close " : "Open ") + name} onClick={() => setOpened(opened.includes(f) ? opened.filter((x) => x !== f) : [...opened, f])}>{o ? "▾" : "▸"}</button> : <span className="cc-tree-chev" />}
        <button type="button" className="cc-tree-name" aria-current={folder === f ? "page" : undefined} onClick={() => setFolder(f)}><FolderIcon open={o && children.length > 0} />{name}<span className="cc-tree-n">{total(f)}</span></button>
      </div>
      {o && children.length > 0 && <ul>{children.map((c) => node(c, depth + 1))}</ul>}
    </li>;
  };
  const crumbs = folder ? folder.split("/") : [];
  const subfolders = filtered ? [] : kids(folder);
  const failing = all.filter((t) => t.last_run && !running(t.last_run) && t.last_run.passed < t.last_run.total).length;
  const never = all.filter((t) => !t.last_run).length;
  const rowMenu = (t: TestSet) => [
    { key: "open", label: "Open", onSelect: () => setOpen({ id: t.id }) },
    { key: "runs", label: "Runs", onSelect: () => setOpen({ id: t.id, tab: "runs" }) },
    ...(can("repo.checkin") ? [{ key: "edit", label: "Edit", onSelect: () => setDialog({ kind: "edit", t }) },
      { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setAsk(t) }] : []),
  ];
  const del = (t: TestSet, then?: () => void) => api.deleteTestSet(t.id).then(() => { onNotice("Deleted the test set " + t.name + "."); setAsk(null); then?.(); load(); }, (e) => setError(message(e)));
  const form = dialog?.kind === "edit" && <TestSetForm api={api} t={dialog.t} onClose={() => setDialog(null)} onSaved={(m, id) => { onNotice(m); setDialog(null); load(); if (!dialog.t) setOpen({ id }); }} />;

  const current = open && all.find((t) => t.id === open.id);
  if (open && current) return (
    <section className="st-page cc-home cc-explorer" aria-label={"Agent tests: " + current.name}>
      <TestSetPage key={current.id} api={api} t={current} runners={runners} pools={pools} can={can} start={open}
        onClose={() => { setOpen(null); load(); }} onFolder={(f) => { setOpen(null); setFolder(f); load(); }}
        onEdit={() => setDialog({ kind: "edit", t: current })} onDelete={() => del(current, () => setOpen(null))} onRan={load} />
      {form}
    </section>
  );

  return (
    <section className="st-page cc-home cc-explorer" aria-labelledby="cc-tests-title">
      <header className="cc-list-head">
        <h1 id="cc-tests-title">Agent tests</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{all.length} test set{all.length === 1 ? "" : "s"}{failing ? " · " + failing + " failing" : ""}{never ? " · " + never + " not run yet" : ""}</span>}
        <span className="ov-fill" />
        {can("repo.checkin") && <Menu label="Create" align="end" items={[{ key: "set", label: "New test set", onSelect: () => setDialog({ kind: "edit", t: null }) }]}
          trigger={<button type="button" className="ui-btn ui-btn-primary" aria-label="Create">Create ▾</button>} />}
      </header>
      {error && <PageError text={error} />}
      <div className="cc-explorer-body">
        <nav className="cc-tree-pane" aria-label="Folders">
          <div className="cc-pane-head"><h2>Folders</h2><span className="ov-fill" />
            <button type="button" className="cc-link" onClick={() => setOpened(folders.filter((f) => kids(f).length))}>Open all</button></div>
          <ul className="cc-tree">
            <li><div className={"cc-tree-row" + (folder === "" ? " is-on" : "")} style={{ paddingLeft: 6 }}><span className="cc-tree-chev" />
              <button type="button" className="cc-tree-name" aria-current={folder === "" ? "page" : undefined} onClick={() => setFolder("")}><FolderIcon open />All test sets<span className="cc-tree-n">{all.length}</span></button></div>
              <ul>{kids("").map((f) => node(f, 1))}</ul></li>
          </ul>
          <p className="cc-pane-foot">A test set sits in its agent's folder, as in Automation.</p>
        </nav>
        <div className="cc-files-pane">
          <div className="cc-pane-head">
            <span className="cc-crumbs-path"><FolderIcon open /><button type="button" className="cc-link" onClick={() => setFolder("")}>Agent tests</button>
              {crumbs.map((c, i) => <span key={i}> › {i === crumbs.length - 1 ? <strong>{c}</strong> : <button type="button" className="cc-link" onClick={() => setFolder(crumbs.slice(0, i + 1).join("/"))}>{c}</button>}</span>)}</span>
          </div>
          {ask && <div className="cc-list-bulk" role="alert"><span>Delete the test set {ask.name} and all its results?</span>
            <Button kind="danger" onClick={() => del(ask)}>Delete</Button><Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
          <div className="cc-list-tools cc-pane-tools">
            <div className="cc-list-filters" role="group" aria-label="Filters">
              <span className="cc-list-search"><TextInput type="search" aria-label="Search test sets" placeholder={"Search in " + (folder ? folder.split("/").pop() : "Agent tests")} value={q} onChange={(e) => setQ(e.target.value)} /></span>
              <span className={"cc-chip" + (only ? " is-on" : "")}><Select aria-label="Last result" value={only} onChange={(e) => setOnly(e.target.value)}
                options={[{ value: "", label: "Last result: Any" }, { value: "failing", label: "Last result: Failing" }, { value: "passing", label: "Last result: Passing" }, { value: "never", label: "Last result: Not run yet" }]} /></span>
              {filtered && <Button kind="plain" onClick={() => { setQ(""); setOnly(""); }}>Clear filters</Button>}
            </div>
            <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
          </div>
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label="Test sets list">
              <thead><tr><th scope="col">Last result</th><th scope="col">Test set</th><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col" className="cc-num">Cases</th>
                <th scope="col">Last score</th><th scope="col">Version tested</th><th scope="col">Last run</th><th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>
                {subfolders.map((f) => (
                  <tr key={"f:" + f} className="cc-folder-row" onClick={rowClick(() => setFolder(f))}>
                    <td colSpan={2}><FolderIcon /><strong>{f.split("/").pop()}</strong></td><td>Folder · {total(f)} test set{total(f) === 1 ? "" : "s"}</td>
                    <td>{folderOf(f) || <span className="cc-none">(top)</span>}</td><td colSpan={4}><span className="cc-none">–</span></td><td className="cc-list-menu" />
                  </tr>))}
                {shown.map((t) => (
                  <tr key={t.id} onClick={rowClick(() => setOpen({ id: t.id }))}>
                    <td><ResultPill r={t.last_run} /></td>
                    <td title={t.description ?? undefined}><span className="cc-aico is-test" aria-hidden="true">✓</span><strong>{t.name}</strong></td>
                    <td><span className="cc-aico" aria-hidden="true">A</span>{agentName(t.bot_path)}</td>
                    <td>{folderOf(t.bot_path) || <span className="cc-none">(top)</span>}</td>
                    <td className="cc-num">{t.cases.length}</td>
                    <td><Score r={t.last_run} /></td>
                    <td>{t.last_run?.version ? "v" + t.last_run.version : <span className="cc-none">–</span>}</td>
                    <td>{when(t.last_run?.started) || <span className="cc-none">Never</span>}</td>
                    <td className="cc-list-menu"><RowMenu label={"Actions for " + t.name} items={rowMenu(t)} /></td>
                  </tr>))}
                {list && !shown.length && !subfolders.length && <tr className="is-static"><td colSpan={9} className="cc-none">{all.length ? (filtered ? "No test set matches." : "No test sets in this folder.")
                  : "No test sets yet." + (can("repo.checkin") ? " Create > New test set picks an agent and the cases to check." : "")}</td></tr>}
                {!list && !error && <tr className="is-static"><td colSpan={9} className="cc-none" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          {list && <div className="cc-list-pager"><span>{subfolders.length ? subfolders.length + " folder" + (subfolders.length === 1 ? "" : "s") + " · " : ""}{shown.length} of {all.length} test set{all.length === 1 ? "" : "s"}{filtered ? " matching" : ""}</span></div>}
        </div>
      </div>
      {form}
    </section>
  );
}

/** A test set: name, agent, description and its cases (as JSON, with the rules explained). */
function TestSetForm({ api, t, onClose, onSaved }: { api: CrApi; t: TestSet | null; onClose: () => void; onSaved: (m: string, id: string) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [v, setV] = useState({ name: t?.name ?? "", description: t?.description ?? "", bot: t?.bot_path ?? "", cases: JSON.stringify(t ? t.cases : EXAMPLE, null, 2) });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveAgents().then((b) => { const a = b.map((x) => x.path); if (t && !a.includes(t.bot_path)) a.push(t.bot_path); setAgents(a); setV((o) => ({ ...o, bot: o.bot || a[0] || "" })); }, (e) => reportMinor("the list of agents", e)); }, [api, t]);
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const save = () => {
    let cases: unknown[];
    try { cases = JSON.parse(v.cases); if (!Array.isArray(cases)) throw new Error(); } catch { setError("The cases are not a JSON list."); return; }
    api.saveTestSet(t?.id ?? null, { name: v.name.trim(), description: v.description.trim(), bot_path: v.bot, cases }).then((r) => onSaved("Saved the test set " + v.name.trim() + ".", t?.id ?? r.id), (e) => setError(message(e)));
  };
  return (
    <Dialog open title={t ? "Test set " + t.name : "New test set"} width={760} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim() || !v.bot} onClick={save}>{t ? "Save changes" : "Create test set"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} onChange={set("name")} />}</Field>
        <Field label="Agent">{(id) => <Select id={id} value={v.bot} onChange={set("bot")} options={agents.map((a) => ({ value: a, label: agentName(a) }))} />}</Field>
      </div>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} onChange={set("description")} />}</Field>
      <Field label="Cases (JSON)">{(id) => <TextArea id={id} rows={14} className="ui-mono" value={v.cases} onChange={set("cases")} />}</Field>
      <p className="cc-list-hint">Each case: a name, the agent's inputs, and expect: status (success or failed), error_code (EMAIL.* allowed) and outputs. An output equals a value, or is {"{op, value}"}: contains, not_contains, matches (a regular expression), approx (a number, with tolerance), one_of (a list) or not_empty.</p>
    </Dialog>
  );
}

type RunDetail = Awaited<ReturnType<CrApi["testRun"]>>;
const TABS = [["cases", "Cases"], ["runs", "Runs"], ["details", "Details"]] as const;

/** A test set's own page, in place of the panes: its cases, its runs (with each run's cases and checks) and details. */
function TestSetPage({ api, t, runners, pools, can, start, onClose, onFolder, onEdit, onDelete, onRan }: { api: CrApi; t: TestSet; runners: Runner[]; pools: RunnerPool[];
  can: (p: string) => boolean; start: { tab?: string; run?: string }; onClose: () => void; onFolder: (f: string) => void; onEdit: () => void; onDelete: () => void; onRan: () => void }) {
  const [tab, setTab] = useState(start.tab ?? "cases");
  const [history, setHistory] = useState<TestRunSummary[] | null>(null);
  const [runId, setRunId] = useState<string | null>(start.run ?? null);
  const [run, setRun] = useState<RunDetail | null>(null);
  const [pick, setPick] = useState<string | null>(null);
  const [show, setShow] = useState("");
  const [where, setWhere] = useState(runners.find((r) => r.enabled) ? "d:" + runners.find((r) => r.enabled)!.id : pools[0] ? "p:" + pools[0].id : "");
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const loadHistory = useCallback(() => api.testHistory(t.id).then((h) => { setHistory(h); setRunId((r) => r ?? h[0]?.id ?? null); }, (e) => setError(message(e))), [api, t.id]);
  useEffect(() => { loadHistory(); }, [loadHistory]);
  useEffect(() => {   // the chosen run, read again every 2 seconds while it runs
    if (!runId) { setRun(null); return; }
    let stop = false, timer: ReturnType<typeof setTimeout> | undefined;
    const read = () => api.testRun(runId).then((x) => {
      if (stop) return;
      setRun(x);
      if (x.status === "running") timer = setTimeout(read, 2000); else { loadHistory(); onRan(); }
    }, (e) => setError(message(e)));
    read();
    return () => { stop = true; if (timer) clearTimeout(timer); };
  }, [api, runId]);   // eslint-disable-line react-hooks/exhaustive-deps
  const go = () => api.runTestSet(t.id, where.startsWith("p:") ? { pool_id: where.slice(2) } : { device_id: where.slice(2) })
    .then((r) => { setTab("runs"); setPick(null); setRunId(r.test_run_id); loadHistory(); onRan(); }, (e) => setError(message(e)));
  const crumbs = folderOf(t.bot_path) ? folderOf(t.bot_path).split("/") : [];
  const caseOf = (name: string) => t.cases.find((c) => c.name === name);
  const results = (run?.results ?? []).filter((x) => !show || (show === "failed" ? x.status !== "running" && !x.passed : x.passed));
  const picked = tab === "runs" ? run?.results.find((x) => x.case_name === pick) : undefined;
  const pickedCase = pick ? caseOf(pick) : undefined;
  const more = can("repo.checkin") ? [{ key: "edit", label: "Edit the cases", onSelect: onEdit }, { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setConfirm(true) }] : [];
  const side = (
    <aside className="cc-agent-side" aria-label="The selected case">
      {pick && pickedCase ? <>
        <div className="cc-pane-head"><h2>{pick}</h2>{picked && (picked.status === "running" ? <span className="cc-pill is-pending">Running</span>
          : picked.passed ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Passed</span> : <span className="cc-pill is-failed"><span className="cc-pill-dot" />Failed</span>)}</div>
        <div className="cc-agent-pad">
          <h3 className="cc-au-sec">Inputs</h3><Inputs inputs={pickedCase.inputs} />
          <h3 className="cc-au-sec">Checks</h3><Checks list={checksOf(pickedCase.expect, picked)} />
          {picked?.run_id && can("runs.view") && <a className="ui-btn ui-btn-secondary cc-btn-link" href={"#/activity?run=" + encodeURIComponent(picked.run_id)}>Open the agent's run</a>}
        </div>
      </> : <p className="cc-none cc-agent-pad">Choose a case to see its inputs and checks.</p>}
    </aside>);
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"Test set " + t.name}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={() => onFolder("")}>Agent tests</button>
          {crumbs.map((c, i) => <span key={i}> › <button type="button" className="cc-link" onClick={() => onFolder(crumbs.slice(0, i + 1).join("/"))}>{c}</button></span>)} ›</span>
        <h1>{t.name}</h1>
        <HelpButton topic={HELP_SET} />
        <ResultPill r={history ? history[0] ?? null : t.last_run} />
        <span className="cc-none">tests {agentName(t.bot_path)} (its live version)</span>
        <span className="ov-fill" />
        {can("runs.start") && <><span className="cc-chip"><Select aria-label="Run on" value={where} onChange={(e) => setWhere(e.target.value)}
          options={[...runners.filter((r) => r.enabled).map((r) => ({ value: "d:" + r.id, label: "Run on: " + r.name + " (" + r.status + ")" })), ...pools.map((p) => ({ value: "p:" + p.id, label: "Run on: pool " + p.name }))]} /></span>
          <Button kind="primary" disabled={!where} onClick={go}>Run</Button></>}
        {can("repo.checkin") && <Button kind="secondary" onClick={onEdit}>Edit</Button>}
        {more.length > 0 && <RowMenu label={"More for " + t.name} items={more} />}
        <button type="button" className="cc-list-icon" aria-label="Close" title="Back to the test sets" onClick={onClose}>×</button>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirm && <div className="st-alert cc-inline" role="alert"><span>Delete {t.name} and all its results?</span>
        <Button kind="danger" onClick={onDelete}>Delete</Button><Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this test set">
        {TABS.map(([k, l]) => <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => { setTab(k); setPick(null); }}>
          {l}{k === "cases" ? " (" + t.cases.length + ")" : k === "runs" && history ? " (" + history.length + ")" : ""}</button>)}
      </div>
      {tab === "cases" && <div className="cc-agent-steps">
        <div className="cc-agent-code">
          <table className="cc-list-table" aria-label="Cases">
            <thead><tr><th scope="col">Case</th><th scope="col">Inputs</th><th scope="col">What is checked</th></tr></thead>
            <tbody>{t.cases.map((c) => <tr key={c.name} className={pick === c.name ? "is-selected" : undefined} onClick={rowClick(() => setPick(c.name))}>
              <td><strong>{c.name}</strong></td><td className="ui-mono">{Object.keys(c.inputs ?? {}).length ? Object.entries(c.inputs ?? {}).map(([k, v]) => k + " = " + JSON.stringify(v)).join(", ") : <span className="cc-none">none</span>}</td>
              <td>{checksOf(c.expect).map((x) => x.text).join("; ")}</td></tr>)}
              {!t.cases.length && <tr className="is-static"><td colSpan={3} className="cc-none">No cases yet: Edit adds them.</td></tr>}</tbody>
          </table>
          <p className="cc-pane-foot">Each case runs the live version of {agentName(t.bot_path)} once, with its inputs, and checks what it gives back.</p>
        </div>
        {side}
      </div>}
      {tab === "runs" && <div className="cc-agent-steps">
        <div className="cc-agent-code">
          <table className="cc-list-table" aria-label="Runs">
            <thead><tr><th scope="col">Start time</th><th scope="col">Version</th><th scope="col">Score</th><th scope="col">Started by</th><th scope="col">Duration</th></tr></thead>
            <tbody>{(history ?? []).map((x) => <tr key={x.id} className={runId === x.id ? "is-selected" : undefined} onClick={rowClick(() => { setRunId(x.id); setPick(null); })}>
              <td><strong>{when(x.started)}</strong></td><td>{x.version ? "v" + x.version : ""}</td><td><Score r={x} /></td>
              <td>{x.started_by ?? ""}</td><td>{took(x)}</td></tr>)}
              {history && !history.length && <tr className="is-static"><td colSpan={5} className="cc-none">Not run yet{can("runs.start") ? ": choose where it runs and Run." : "."}</td></tr>}
              {!history && <tr className="is-static"><td colSpan={5} className="cc-none" role="status">Loading…</td></tr>}</tbody>
          </table>
          {run && <>
            <div className="cc-pane-head cc-tests-sub"><h2>Cases in the run of {when(run.started)}</h2><span className="cc-none" role="status">{running(run) ? "Running…" : run.passed + " of " + run.total + " passed"}</span>
              <span className="ov-fill" /><span className={"cc-chip" + (show ? " is-on" : "")}><Select aria-label="Show" value={show} onChange={(e) => setShow(e.target.value)}
                options={[{ value: "", label: "Show: All" }, { value: "failed", label: "Show: Failed" }, { value: "passed", label: "Show: Passed" }]} /></span></div>
            <table className="cc-list-table" aria-label="Case results">
              <thead><tr><th scope="col">Result</th><th scope="col">Case</th><th scope="col">Why it failed</th><th scope="col">Agent run</th></tr></thead>
              <tbody>{results.map((x) => <tr key={x.case_name} className={pick === x.case_name ? "is-selected" : undefined} onClick={rowClick(() => setPick(x.case_name))}>
                <td>{x.status === "running" ? <span className="cc-pill is-pending">Running</span> : x.passed ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Passed</span> : <span className="cc-pill is-failed"><span className="cc-pill-dot" />Failed</span>}</td>
                <td><strong>{x.case_name}</strong></td><td>{x.detail || <span className="cc-none">–</span>}</td>
                <td>{x.run_id && can("runs.view") ? <a href={"#/activity?run=" + encodeURIComponent(x.run_id)}>Open run</a> : <span className="cc-none">–</span>}</td></tr>)}
                {!results.length && <tr className="is-static"><td colSpan={4} className="cc-none">No case to show.</td></tr>}</tbody>
            </table>
          </>}
          <p className="cc-pane-foot">A run opens its cases below; a case opens its inputs and checks beside.</p>
        </div>
        {side}
      </div>}
      {tab === "details" && <div className="cc-agent-tab">
        {t.description && <p>{t.description}</p>}
        <dl className="cc-facts cc-facts-wide">
          <dt>Agent</dt><dd><a href={"#/automation?q=" + encodeURIComponent(agentName(t.bot_path))}>{agentName(t.bot_path)}</a> (runs its live version)</dd>
          <dt>Folder</dt><dd>{folderOf(t.bot_path) || "(top)"}</dd>
          <dt>Cases</dt><dd>{t.cases.length}</dd>
          <dt>Runs kept</dt><dd>{history ? history.length + (history.length >= 50 ? " (the last 50 shown)" : "") : ""}</dd>
        </dl>
      </div>}
    </div>
  );
}
