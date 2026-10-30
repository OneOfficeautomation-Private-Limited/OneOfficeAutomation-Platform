import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Runner, RunnerPool, TestSet } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * Build > Agent tests, after UiPath's Test Manager test sets (AA keeps testing out of its Control Room; checked
 * 31 Oct 2026): a test set runs an agent with chosen inputs and checks what it gives back, also for AI answers
 * (contains, matches, close to a number, one of). Each run of a set is scored per case and kept. A set opens with its
 * cases and history; Run on a runner or pool shows the results as they come in.
 */
const EXAMPLE = [
  { name: "urgent mail", inputs: { text: "The server is down!" }, expect: { outputs: { label: { op: "one_of", value: ["urgent", "high"] } } } },
  { name: "thank you", inputs: { text: "Thanks a lot" }, expect: { outputs: { label: "low" } } },
  { name: "empty text fails", inputs: { text: "" }, expect: { status: "failed", error_code: "TRIAGE.*" } }];
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const score = (r?: { status: string; passed: number; total: number } | null) => !r ? "Not run" : r.status === "running" ? "Running…" : r.passed + " of " + r.total + " passed";

export function Tests({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<TestSet[] | null>(null);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [only, setOnly] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "edit"; t: TestSet | null } | { kind: "open"; t: TestSet } | { kind: "results"; id: string }>(null);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => {
    try { const [l, d, p] = await Promise.all([api.testSets(), api.devices().catch(() => []), api.pools().catch(() => [])]); setList(l); setRunners(d); setPools(p); setError(null); }
    catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((t) => (!q.trim() || (t.name + " " + t.bot_path + " " + (t.description ?? "")).toLowerCase().includes(q.trim().toLowerCase()))
    && (!only || (only === "failing" ? !!t.last_run && t.last_run.status !== "running" && t.last_run.passed < t.last_run.total : !t.last_run))), [list, q, only]);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-tests-title">
      <header className="st-page-head">
        <div><h1 id="cc-tests-title">Agent tests</h1><p>Test sets run an agent with chosen inputs and check what it gives back, also for AI answers. Each run of a set is scored and kept.</p></div>
        {can("repo.checkin") && <Button kind="primary" onClick={() => setDialog({ kind: "edit", t: null })}>Create test set</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search test sets" placeholder="Search test sets and agents" value={q} onChange={(e) => setQ(e.target.value)} />
        <Select aria-label="Show" value={only} onChange={(e) => setOnly(e.target.value)} options={[{ value: "", label: "Every test set" }, { value: "failing", label: "Failing last time" }, { value: "never", label: "Never run" }]} />
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Test sets list">
          <thead><tr><th scope="col">Test set</th><th scope="col">Agent</th><th scope="col">Cases</th><th scope="col">Last score</th><th scope="col">Last run</th></tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} onClick={() => setDialog({ kind: "open", t })}>
                <td>{t.name}{t.description && <div className="st-muted">{t.description}</div>}</td><td>{agentName(t.bot_path)}</td><td>{t.cases.length}</td>
                <td className={t.last_run && t.last_run.status !== "running" ? "cc-status is-" + (t.last_run.passed === t.last_run.total ? "success" : "failed") : undefined}>{score(t.last_run)}</td>
                <td>{when(t.last_run?.started)}</td>
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={5} className="st-muted">{list.length ? "No test sets match." : "No test sets yet."}</td></tr>}
            {!list && !error && <tr><td colSpan={5} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {dialog?.kind === "edit" && <TestSetForm api={api} t={dialog.t} onClose={() => setDialog(null)} onSaved={(m) => { onNotice(m); setDialog(null); load(); }} />}
      {dialog?.kind === "open" && <TestSetView key={dialog.t.id} api={api} t={dialog.t} runners={runners} pools={pools} can={can} onClose={() => setDialog(null)}
        onEdit={() => setDialog({ kind: "edit", t: dialog.t })} onResults={(id) => setDialog({ kind: "results", id })}
        onDeleted={(m) => { onNotice(m); setDialog(null); load(); }} />}
      {dialog?.kind === "results" && <Results key={dialog.id} api={api} id={dialog.id} onClose={() => { setDialog(null); load(); }} />}
    </section>
  );
}

/** A test set: name, agent, description and its cases (as JSON, with the rules explained). */
function TestSetForm({ api, t, onClose, onSaved }: { api: CrApi; t: TestSet | null; onClose: () => void; onSaved: (m: string) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [v, setV] = useState({ name: t?.name ?? "", description: t?.description ?? "", bot: t?.bot_path ?? "", cases: JSON.stringify(t ? t.cases : EXAMPLE, null, 2) });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveAgents().then((b) => { const a = b.map((x) => x.path); if (t && !a.includes(t.bot_path)) a.push(t.bot_path); setAgents(a); setV((o) => ({ ...o, bot: o.bot || a[0] || "" })); }, () => {}); }, [api, t]);
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const save = () => {
    let cases: unknown[];
    try { cases = JSON.parse(v.cases); if (!Array.isArray(cases)) throw new Error(); } catch { setError("The cases are not a JSON list."); return; }
    api.saveTestSet(t?.id ?? null, { name: v.name.trim(), description: v.description.trim(), bot_path: v.bot, cases }).then(() => onSaved("Saved the test set " + v.name.trim() + "."), (e) => setError(message(e)));
  };
  return (
    <Dialog open title={t ? "Test set " + t.name : "Create test set"} width={760} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim() || !v.bot} onClick={save}>{t ? "Save changes" : "Create test set"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} onChange={set("name")} />}</Field>
        <Field label="Agent">{(id) => <Select id={id} value={v.bot} onChange={set("bot")} options={agents.map((a) => ({ value: a, label: agentName(a) }))} />}</Field>
      </div>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} onChange={set("description")} />}</Field>
      <Field label="Cases (JSON)">{(id) => <TextArea id={id} rows={14} className="ui-mono" value={v.cases} onChange={set("cases")} />}</Field>
      <p className="st-muted">Each case: a name, the agent's inputs, and expect: status (success or failed), error_code (EMAIL.* allowed) and outputs. An output equals a value, or is {"{op, value}"}: contains, not_contains, matches (a regular expression), close_to (a number, with tolerance) or one_of (a list).</p>
    </Dialog>
  );
}

/** A test set: its cases, its history, and Run on a runner or pool. */
function TestSetView({ api, t, runners, pools, can, onClose, onEdit, onResults, onDeleted }: { api: CrApi; t: TestSet; runners: Runner[]; pools: RunnerPool[]; can: (p: string) => boolean;
  onClose: () => void; onEdit: () => void; onResults: (id: string) => void; onDeleted: (m: string) => void }) {
  const [history, setHistory] = useState<Awaited<ReturnType<CrApi["testHistory"]>> | null>(null);
  const [where, setWhere] = useState(runners.find((r) => r.enabled) ? "d:" + runners.find((r) => r.enabled)!.id : pools[0] ? "p:" + pools[0].id : "");
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  useEffect(() => { api.testHistory(t.id).then(setHistory, (e) => setError(message(e))); }, [api, t.id]);
  const run = () => api.runTestSet(t.id, where.startsWith("p:") ? { pool_id: where.slice(2) } : { device_id: where.slice(2) }).then((r) => onResults(r.test_run_id), (e) => setError(message(e)));
  return (
    <Dialog open title={"Test set " + t.name} width={760} onClose={onClose} footer={<>
      {can("repo.checkin") && <Button kind="danger" onClick={() => setConfirm(true)}>Delete</Button>}
      {can("repo.checkin") && <Button kind="secondary" onClick={onEdit}>Edit</Button>}
      <span className="st-bar-fill" />
      {can("runs.start") && <><Select aria-label="Run on" value={where} onChange={(e) => setWhere(e.target.value)} options={[...runners.filter((r) => r.enabled).map((r) => ({ value: "d:" + r.id, label: r.name + " (" + r.status + ")" })),
        ...pools.map((p) => ({ value: "p:" + p.id, label: "Pool " + p.name }))]} />
        <Button kind="primary" disabled={!where} onClick={run}>Run</Button></>}</>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirm && <div className="st-alert" role="alert"><span>Delete {t.name} and its results?</span>
        <Button kind="danger" onClick={() => api.deleteTestSet(t.id).then(() => onDeleted("Deleted the test set " + t.name + "."), (e) => setError(message(e)))}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      <p className="st-muted">{t.cases.length} case(s) run the live version of {agentName(t.bot_path)}, one run each.</p>
      <h3 className="cc-log-title">Cases</h3>
      <table className="st-table" aria-label="Cases"><thead><tr><th scope="col">Case</th><th scope="col">Inputs</th><th scope="col">Expect</th></tr></thead>
        <tbody>{t.cases.map((c) => <tr key={c.name}><td>{c.name}</td><td className="ui-mono cc-clip">{JSON.stringify(c.inputs ?? {})}</td><td className="ui-mono cc-clip">{JSON.stringify(c.expect ?? {})}</td></tr>)}</tbody></table>
      <h3 className="cc-log-title">History</h3>
      <table className="st-table" aria-label="History"><tbody>
        {(history ?? []).map((x) => <tr key={x.id} onClick={() => onResults(x.id)}><td>{when(x.started)}</td><td>v{x.version}</td>
          <td className={x.status === "running" ? undefined : "cc-status is-" + (x.passed === x.total ? "success" : "failed")}>{score(x)}</td></tr>)}
        {history && !history.length && <tr><td className="st-muted">Not run yet.</td></tr>}
      </tbody></table>
    </Dialog>
  );
}

/** A test run: the score and each case's result and reason, refreshed every 2 seconds while it runs. */
function Results({ api, id, onClose }: { api: CrApi; id: string; onClose: () => void }) {
  const [r, setR] = useState<Awaited<ReturnType<CrApi["testRun"]>> | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let stop = false;
    const read = () => api.testRun(id).then((x) => { if (stop) return; setR(x); if (x.status === "running") setTimeout(read, 2000); }, (e) => setError(message(e)));
    read();
    return () => { stop = true; };
  }, [api, id]);
  return (
    <Dialog open title="Test run" width={760} onClose={onClose} footer={null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!r ? !error && <p className="st-muted" role="status">Loading…</p> : <>
        <dl className="cc-facts"><dt>Agent</dt><dd>{agentName(r.bot_path)} v{r.version}</dd><dt>Started</dt><dd>{when(r.started)}{r.started_by ? " by " + r.started_by : ""}</dd><dt>Score</dt><dd>{score(r)}</dd></dl>
        <table className="st-table" aria-label="Case results"><thead><tr><th scope="col">Case</th><th scope="col">Result</th><th scope="col">Why</th></tr></thead>
          <tbody>{r.results.map((x) => <tr key={x.case_name}><td>{x.case_name}</td>
            <td className={x.status === "running" ? undefined : "cc-status is-" + (x.passed ? "success" : "failed")}>{x.status === "running" ? "Running" : x.passed ? "Passed" : "Failed"}</td><td>{x.detail ?? ""}</td></tr>)}</tbody></table>
      </>}
    </Dialog>
  );
}
