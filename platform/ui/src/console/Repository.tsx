import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, FlowVariable, ProductionRequest, RepoAgent, RepoFolder, RepoVersion, Run, Runner, RunnerPool } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import { AgentAnalysis, CodeAnalysisPolicy, ExportAgents, ImportAgents, RepoFiles } from "./RepoTools";

/**
 * Build > Agent Repository, built as AA's public workspace (checked 31 Oct 2026): a folder tree beside the agents in the
 * chosen folder, each with its latest and live versions, labels and who has it checked out; folder permissions; an
 * agent's inputs, dependencies, recent runs and version history (version, comment, author, date, newest first). From
 * UiPath's packages: two versions compared line by line, the check-in comment as release notes. From Blue Prism:
 * one published (live) version per agent, set directly or through an approval by someone else. Export, promotion and
 * import, code analysis and the files kept in a folder are in RepoTools.
 */
const KINDS: [RegExp, string][] = [[/\.process\.json$/, "Workflow"], [/\.form\.json$/, "Form"], [/\.flow\.json$/, "Agent"]];
const kindOf = (p: string) => KINDS.find(([r]) => r.test(p))?.[1] ?? "File";
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process|form)\.json$/, "");
const folderOf = (p: string) => p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "";
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const ACCESS = [["view", "View: see and export"], ["run", "Run: also run and schedule"], ["edit", "Edit: also check out, check in, move, delete"], ["manage", "Manage: also live version and labels"]];

type Dlg = null | { kind: "agent"; a: RepoAgent } | { kind: "folder"; parent: string } | { kind: "rename" } | { kind: "permissions" } | { kind: "deleted" } | { kind: "approvals" }
  | { kind: "run"; a: RepoAgent } | { kind: "export" } | { kind: "import" } | { kind: "analysis" };

export function Repository({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [agents, setAgents] = useState<RepoAgent[] | null>(null);
  const [folders, setFolders] = useState<RepoFolder[]>([]);
  const [requests, setRequests] = useState<{ requests: ProductionRequest[]; approval_on: boolean }>({ requests: [], approval_on: false });
  const [folder, setFolder] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [show, setShow] = useState("");
  const [dialog, setDialog] = useState<Dlg>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => {
    try {
      const [a, f, r] = await Promise.all([api.repo(folder), api.repoFolders(), api.productionRequests("pending").catch(() => ({ requests: [], approval_on: false }))]);
      setAgents(a); setFolders(f); setRequests(r); setError(null);
    } catch (e) { setError(message(e)); }
  }, [api, folder]);
  useEffect(() => { load(); }, [load]);
  const cur = folders.find((f) => f.path === folder);
  const inside = cur ? folders.filter((f) => f.path === cur.path || f.path.startsWith(cur.path + "/")).reduce((n, f) => n + f.agents, 0) : 0;
  const shown = useMemo(() => (agents ?? []).filter((a) => (!q.trim() || (a.path + " " + (a.description ?? "") + " " + (a.bot_id ?? "")).toLowerCase().includes(q.trim().toLowerCase()))
    && (!kind || kindOf(a.path) === kind)
    && (!show || (show === "live" ? !!a.production_version : show === "notlive" ? !a.production_version : !!a.locked_by))), [agents, q, kind, show]);
  const done = (m: string) => { onNotice(m); setDialog(null); load(); };
  const toDecide = requests.requests.filter((r) => r.can_decide).length;

  return (
    <section className="st-page cc-home" aria-labelledby="cc-repo-title">
      <header className="st-page-head">
        <div><h1 id="cc-repo-title">Agent Repository</h1><p>Agents checked in from One AgentStudio, with every version. The live version is the one that runs on runners, schedules and triggers.</p></div>
        <span className="cc-inline">
          {can("repo.checkin") && <Button kind="secondary" onClick={() => setDialog({ kind: "folder", parent: folder })}>New folder</Button>}
          {(requests.approval_on || requests.requests.length > 0) && <Button kind={toDecide ? "primary" : "secondary"} onClick={() => setDialog({ kind: "approvals" })}>
            Production approvals{requests.requests.length ? " (" + requests.requests.length + " waiting)" : ""}</Button>}
          {can("repo.delete") && <Button kind="secondary" onClick={() => setDialog({ kind: "deleted" })}>Deleted agents</Button>}
          {can("lifecycle.export") && <Button kind="secondary" onClick={() => setDialog({ kind: "export" })}>Export or promote</Button>}
          {can("lifecycle.import") && <Button kind="secondary" onClick={() => setDialog({ kind: "import" })}>Import</Button>}
          <Button kind="secondary" onClick={() => setDialog({ kind: "analysis" })}>Code analysis</Button>
        </span>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-repo">
        <nav className="cc-repo-tree" aria-label="Folders">
          <button type="button" className="cc-settings-tab" aria-current={folder === "" ? "page" : undefined} onClick={() => setFolder("")}>All folders</button>
          {folders.map((f) => (
            <button key={f.path} type="button" className="cc-settings-tab" style={{ paddingLeft: 10 + 14 * (f.path.split("/").length - 1) }} aria-current={folder === f.path ? "page" : undefined}
              title={f.description ?? undefined} onClick={() => setFolder(f.path)}>
              {f.path.split("/").pop()} <span className="st-muted">({f.agents})</span>{f.has_permissions && <span className="cc-tag">Own permissions</span>}
            </button>
          ))}
        </nav>
        <div>
          <div className="cc-filters" role="group" aria-label="Filters">
            <TextInput type="search" aria-label="Search agents" placeholder="Search by name, id or description" value={q} onChange={(e) => setQ(e.target.value)} />
            <Select aria-label="Kind" value={kind} onChange={(e) => setKind(e.target.value)} options={[{ value: "", label: "Every kind" }, ...["Agent", "Workflow", "Form"].map((k) => ({ value: k, label: k + "s" }))]} />
            <Select aria-label="Show" value={show} onChange={(e) => setShow(e.target.value)} options={[{ value: "", label: "All" }, { value: "live", label: "Live" }, { value: "notlive", label: "Not live yet" }, { value: "out", label: "Checked out" }]} />
            <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
          </div>
          {folder && <div className="cc-bar" role="group" aria-label="Folder">
            <span className="cc-chosen">{folder}</span>{cur?.description && <span className="st-muted">{cur.description}</span>}
            {can("repo.permissions") && <Button kind="secondary" onClick={() => setDialog({ kind: "permissions" })}>Folder permissions</Button>}
            {can("repo.checkin") && <Button kind="secondary" onClick={() => setDialog({ kind: "rename" })}>Rename or move folder</Button>}
            {can("repo.delete") && cur && inside === 0 && (confirmDelete
              ? <span className="cc-inline" role="alert">Delete the empty folder {folder}?
                <Button kind="danger" onClick={() => api.deleteFolder(folder).then(() => { setConfirmDelete(false); setFolder(""); onNotice("Deleted the folder " + folder + "."); }, (e) => setError(message(e)))}>Delete</Button>
                <Button kind="plain" onClick={() => setConfirmDelete(false)}>Cancel</Button></span>
              : <Button kind="danger" onClick={() => setConfirmDelete(true)}>Delete folder</Button>)}
          </div>}
          <div className="cc-table-wrap">
            <table className="st-table cc-table" aria-label="Agents list">
              <thead><tr><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col">Latest</th><th scope="col">Live</th><th scope="col">Status</th><th scope="col">Updated</th></tr></thead>
              <tbody>
                {shown.map((a) => (
                  <tr key={a.path} onClick={() => setDialog({ kind: "agent", a })}>
                    <td><strong>{agentName(a.path)}</strong>{kindOf(a.path) !== "Agent" && <span className="cc-tag">{kindOf(a.path)}</span>}
                      {a.description && <div className="st-muted cc-clip">{a.description}</div>}</td>
                    <td className="st-muted">{folderOf(a.path) || "(top)"}</td>
                    <td>v{a.latest_version}{(a.labels ?? []).map((l) => <span key={l.label} className="cc-tag" title={"On v" + l.version}>{l.label}</span>)}</td>
                    <td className={a.production_version ? "cc-status is-success" : "st-muted"}>{a.production_version ? "v" + a.production_version : "Not live"}</td>
                    <td className={a.locked_by ? "cc-status is-busy" : undefined}>{a.locked_by ? "Checked out by " + a.locked_by : "Checked in"}</td>
                    <td>{when(a.updated)}{a.updated_by ? " by " + a.updated_by : ""}</td>
                  </tr>
                ))}
                {agents && !shown.length && <tr className="is-static"><td colSpan={6} className="st-muted">{agents.length ? "No agents match." : "No agents here yet. In One AgentStudio, open an agent and check it in."}</td></tr>}
                {!agents && !error && <tr className="is-static"><td colSpan={6} className="st-muted" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          <RepoFiles key={folder} api={api} user={user} folder={folder} onNotice={onNotice} />
        </div>
      </div>
      {dialog?.kind === "agent" && <AgentView key={dialog.a.path} api={api} user={user} a={dialog.a} folders={folders} approvalOn={requests.approval_on} onClose={() => setDialog(null)}
        onRun={() => setDialog({ kind: "run", a: dialog.a })} onDone={done} />}
      {dialog?.kind === "run" && <RunAgent api={api} a={dialog.a} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === "folder" && <NewFolder api={api} parent={dialog.parent} onClose={() => setDialog(null)} onDone={(p) => { done("Created the folder " + p + "."); setFolder(p); }} />}
      {dialog?.kind === "rename" && <RenameFolder api={api} folder={folder} onClose={() => setDialog(null)} onDone={(p, n) => { done("Moved " + n + " agent(s) to " + p + "."); setFolder(p); }} />}
      {dialog?.kind === "permissions" && <FolderPermissions api={api} folder={folder} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === "deleted" && <Deleted api={api} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); load(); }} />}
      {dialog?.kind === "approvals" && <Approvals api={api} user={user} onClose={() => { setDialog(null); load(); }} onNotice={onNotice} />}
      {dialog?.kind === "export" && <ExportAgents api={api} onClose={() => setDialog(null)} onNotice={onNotice} />}
      {dialog?.kind === "import" && <ImportAgents api={api} folder={folder} onClose={() => { setDialog(null); load(); }} onDone={(m) => { onNotice(m); load(); }} />}
      {dialog?.kind === "analysis" && <CodeAnalysisPolicy api={api} user={user} onClose={() => setDialog(null)} onNotice={onNotice} />}
    </section>
  );
}

/** One agent: what it is, its inputs and outputs, what it calls and who calls it, recent runs, and its versions. */
function AgentView({ api, user, a, folders, approvalOn, onClose, onRun, onDone }: { api: CrApi; user: CrUser; a: RepoAgent; folders: RepoFolder[]; approvalOn: boolean;
  onClose: () => void; onRun: () => void; onDone: (m: string) => void }) {
  const can = (p: string) => user.permissions.includes(p);
  const editable = ["edit", "manage"].includes(a.access ?? "edit");
  const [vars, setVars] = useState<FlowVariable[] | null>(null);
  const [deps, setDeps] = useState<Awaited<ReturnType<CrApi["dependencies"]>> | null>(null);
  const [runs, setRuns] = useState<Run[]>([]);
  const [versions, setVersions] = useState<RepoVersion[] | null>(null);
  const [picks, setPicks] = useState<number[]>([]);
  const [diff, setDiff] = useState<null | { a: number; b: number; lines: [string, string][]; added: number; removed: number }>(null);
  const [ask, setAsk] = useState<null | { kind: "label" | "live" | "move" | "copy" | "delete"; version?: number }>(null);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const loadVersions = useCallback(() => api.agentVersions(a.path).then(setVersions, (e) => setError(message(e))), [api, a.path]);
  useEffect(() => {
    if (a.production_version) api.agentFlow(a.path, "production").then((r) => setVars(r.flow.variables ?? []), () => setVars([]));
    else setVars([]);
    api.dependencies(a.path).then(setDeps, () => {});
    if (can("runs.view")) api.runs({ limit: "8", bot: a.path }).then((r) => setRuns(r.runs), () => {});
    loadVersions();
  }, [api, a.path]);   // eslint-disable-line react-hooks/exhaustive-deps
  const fail = (e: unknown) => setError(message(e));
  const compare = async () => {
    const [x, y] = [...picks].sort((m, n) => m - n);
    try {
      const [fa, fb] = await Promise.all([api.agentFlow(a.path, x), api.agentFlow(a.path, y)]);
      setDiff({ a: x, b: y, ...lineDiff(JSON.stringify(fa.flow, null, 2).split("\n"), JSON.stringify(fb.flow, null, 2).split("\n")) });
    } catch (e) { fail(e); }
  };
  const confirm = () => {
    if (!ask) return;
    const v = ask.version!;
    if (ask.kind === "label") api.setLabel(a.path, text.trim(), v).then(() => { setAsk(null); loadVersions(); }, fail);
    if (ask.kind === "live") api.setProduction(a.path, v, approvalOn ? text.trim() : undefined)
      .then((r) => onDone(r.production_request ? "Sent v" + v + " of " + agentName(a.path) + " for approval." : "v" + v + " of " + agentName(a.path) + " is live."), fail);
    if (ask.kind === "move") api.moveAgent(a.path, text).then((r) => onDone("Moved to " + r.path + "."), fail);
    if (ask.kind === "copy") api.copyAgent(a.path, text.trim() + a.path.slice(a.path.search(/\.(flow|process|form)\.json$/))).then((r) => onDone("Copied to " + agentName(r.path ?? text) + "."), fail);
    if (ask.kind === "delete") api.deleteAgent(a.path).then((r) => onDone("Deleted " + agentName(a.path) + "." + (r.called_by?.length ? " " + r.called_by.map(agentName).join(", ") + " still call it." : "")), fail);
  };
  const inputs = (vars ?? []).filter((v) => v.direction === "input" || v.direction === "in_out");
  const outputs = (vars ?? []).filter((v) => v.direction === "output" || v.direction === "in_out");
  const kind = kindOf(a.path);
  return (
    <Dialog open title={agentName(a.path)} width={860} onClose={onClose} footer={<>
      {can("repo.delete") && editable && <Button kind="danger" onClick={() => setAsk({ kind: "delete" })}>Delete</Button>}
      {can("repo.checkin") && editable && <Button kind="secondary" onClick={() => { setText(folderOf(a.path)); setAsk({ kind: "move" }); }}>Move</Button>}
      {can("repo.checkin") && <Button kind="secondary" onClick={() => { setText((folderOf(a.path) ? folderOf(a.path) + "/" : "") + agentName(a.path) + " (copy)"); setAsk({ kind: "copy" }); }}>Copy</Button>}
      {a.locked_by && (a.locked_by === user.username || can("repo.production")) &&
        <Button kind="secondary" onClick={() => api.undoCheckout(a.path).then(() => onDone("Undid the check-out of " + agentName(a.path) + "."), fail)}>Undo check-out</Button>}
      <span className="st-bar-fill" />
      {can("runs.start") && a.production_version && kind === "Agent" && <Button kind="primary" onClick={onRun}>Run</Button>}
      {can("runs.start") && a.production_version && kind === "Workflow" && <a className="ui-btn ui-btn-primary" href="#/workflows">Start in Workflow</a>}</>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {ask && <div className="st-alert cc-inline" role="alert">
        {ask.kind === "delete" && <span>Delete {agentName(a.path)}? It is no longer listed and cannot run; its versions are kept and it can be recovered from Deleted agents.</span>}
        {ask.kind === "label" && <Field label={"Label for v" + ask.version + " (moves from the version that has it)"}>{(id) => <TextInput id={id} value={text} placeholder="UAT" onChange={(e) => setText(e.target.value)} />}</Field>}
        {ask.kind === "live" && (approvalOn
          ? <Field label={"Ask for v" + ask.version + " to go live: what changed, for the approver"}>{(id) => <TextInput id={id} value={text} onChange={(e) => setText(e.target.value)} />}</Field>
          : <span>Make v{ask.version} the live version? Runners, schedules and triggers use it from their next run.</span>)}
        {ask.kind === "move" && <Field label="Move to folder">{(id) => <Select id={id} value={text} onChange={(e) => setText(e.target.value)}
          options={[{ value: "", label: "(top)" }, ...folders.map((f) => ({ value: f.path, label: f.path }))]} />}</Field>}
        {ask.kind === "copy" && <Field label="New name, with its folder (the latest version becomes v1 of a new agent)">{(id) => <TextInput id={id} value={text} onChange={(e) => setText(e.target.value)} />}</Field>}
        <Button kind={ask.kind === "delete" ? "danger" : "primary"} disabled={["label", "copy"].includes(ask.kind) && !text.trim()} onClick={confirm}>
          {{ delete: "Delete", label: "Save label", live: approvalOn ? "Ask" : "Make live", move: "Move", copy: "Copy" }[ask.kind]}</Button>
        <Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button>
      </div>}
      {a.description && <p>{a.description}</p>}
      <dl className="cc-facts cc-facts-wide">
        <dt>Kind</dt><dd>{kind}</dd><dt>Folder</dt><dd>{folderOf(a.path) || "(top)"}</dd>
        <dt>Live</dt><dd>{a.production_version ? "v" + a.production_version : "Not live yet"}</dd><dt>Latest</dt><dd>v{a.latest_version}</dd>
        <dt>Status</dt><dd>{a.locked_by ? "Checked out by " + a.locked_by : "Checked in"}</dd><dt>Updated</dt><dd>{when(a.updated)}{a.updated_by ? " by " + a.updated_by : ""}</dd>
        {a.bot_id && <><dt>Agent id</dt><dd className="ui-mono">{a.bot_id}</dd></>}<dt>Your access</dt><dd>{a.access ?? ""}</dd>
      </dl>
      {a.production_version ? <>
        <h3 className="cc-log-title">Inputs of the live version</h3>
        {inputs.length ? <table className="st-table" aria-label="Inputs"><tbody>{inputs.map((v) => <tr key={v.name}><td className="ui-mono">{v.name}</td><td>{v.type}</td><td>{v.required ? "Required" : ""}</td><td>{v.description ?? ""}</td></tr>)}</tbody></table>
          : <p className="st-muted">{vars ? "No inputs." : "Loading…"}</p>}
        {outputs.length > 0 && <><h3 className="cc-log-title">Outputs</h3>
          <table className="st-table" aria-label="Outputs"><tbody>{outputs.map((v) => <tr key={v.name}><td className="ui-mono">{v.name}</td><td>{v.type}</td><td>{v.description ?? ""}</td></tr>)}</tbody></table></>}
      </> : null}
      {deps && <><h3 className="cc-log-title">Dependencies</h3><dl className="cc-facts">
        <dt>Calls</dt><dd>{deps.agents.length ? deps.agents.map((d) => <span key={d.bot_id} className={d.found ? "cc-tag" : "cc-risk"} title={d.found ? undefined : "Not in this Command Center"}>{d.path ? agentName(d.path) : d.bot_id}</span>) : "No other agent"}</dd>
        <dt>Called by</dt><dd>{deps.called_by.length ? deps.called_by.map((p) => <span key={p} className="cc-tag">{agentName(p)}</span>) : "No other agent"}</dd>
        <dt>Toolkits</dt><dd>{deps.toolkits.length ? deps.toolkits.map((t) => <span key={t} className="cc-tag">{t}</span>) : "Built-in actions only"}</dd></dl></>}
      {kind !== "File" && <AgentAnalysis api={api} path={a.path} />}
      {runs.length > 0 && <><h3 className="cc-log-title">Recent runs</h3>
        <table className="st-table" aria-label="Recent runs"><tbody>{runs.map((r) => <tr key={r.id}><td><a href={"#/activity?run=" + encodeURIComponent(r.id)}>{when(r.created)}</a></td><td>v{r.version}</td>
          <td className={"cc-status is-" + r.status}>{r.status}</td><td>{r.device_name ?? ""}</td><td>{r.requested_by}</td></tr>)}</tbody></table></>}
      <h3 className="cc-log-title">Versions</h3>
      <div className="cc-bar"><span className="st-muted">Tick two versions to compare them.</span><Button kind="secondary" disabled={picks.length !== 2} onClick={compare}>Compare</Button></div>
      <table className="st-table" aria-label="Versions">
        <thead><tr><th scope="col" className="cc-check"></th><th scope="col">Version</th><th scope="col">Labels</th><th scope="col">Comment</th><th scope="col">By</th><th scope="col">When</th><th scope="col"></th></tr></thead>
        <tbody>{(versions ?? []).map((v) => (
          <tr key={v.version} className="is-static">
            <td><input type="checkbox" aria-label={"Select v" + v.version} checked={picks.includes(v.version)}
              onChange={(e) => setPicks(e.target.checked ? [...picks, v.version].slice(-2) : picks.filter((x) => x !== v.version))} /></td>
            <td>v{v.version}{v.version === a.production_version && <span className="cc-tag">Live</span>}</td>
            <td>{v.labels.map((l) => <span key={l} className="cc-tag">{l}{can("repo.checkin") && <button type="button" className="ui-btn-plain" aria-label={"Remove label " + l}
              onClick={() => api.removeLabel(a.path, l).then(loadVersions, fail)}>×</button>}</span>)}</td>
            <td>{v.comment ?? ""}</td><td>{v.author ?? ""}</td><td>{when(v.created)}</td>
            <td className="cc-inline">
              {(approvalOn ? can("repo.checkin") : can("repo.production")) && v.version !== a.production_version &&
                <Button kind="plain" onClick={() => { setText(""); setAsk({ kind: "live", version: v.version }); }}>{approvalOn ? "Ask to make live" : "Make live"}</Button>}
              {can("repo.checkin") && <Button kind="plain" onClick={() => { setText(""); setAsk({ kind: "label", version: v.version }); }}>Label</Button>}
            </td>
          </tr>))}
          {versions && !versions.length && <tr className="is-static"><td colSpan={7} className="st-muted">No versions.</td></tr>}
        </tbody>
      </table>
      {diff && <Dialog open title={"v" + diff.a + " to v" + diff.b + " of " + agentName(a.path)} width={860} onClose={() => setDiff(null)} footer={null}>
        <p className="st-muted">{diff.added} line(s) added, {diff.removed} removed.</p>
        <ol className="cc-log" aria-label="Changes">{diff.lines.length ? diff.lines.map(([k, l], i) => <li key={i} className={k === "+" ? "cc-add" : k === "-" ? "cc-del" : undefined}>{k} {l}</li>) : <li>The versions are the same.</li>}</ol>
      </Dialog>}
    </Dialog>
  );
}

/** Line by line changes (longest common subsequence), with three lines of context around each change. */
function lineDiff(x: string[], y: string[]) {
  const n = x.length, m = y.length;
  if (n * m > 4_000_000) return { lines: [["!", "These versions are too large to compare here."]] as [string, string][], added: 0, removed: 0 };
  const L = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i][j] = x[i] === y[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
  const out: [string, string][] = [];
  let i = 0, j = 0, added = 0, removed = 0;
  while (i < n || j < m) {
    if (i < n && j < m && x[i] === y[j]) { out.push([" ", x[i]]); i++; j++; }
    else if (j < m && (i >= n || L[i][j + 1] >= L[i + 1][j])) { out.push(["+", y[j]]); j++; added++; }
    else { out.push(["-", x[i]]); i++; removed++; }
  }
  const keep = out.map((_, k) => out.slice(Math.max(0, k - 3), k + 4).some((z) => z[0] !== " "));
  const lines: [string, string][] = [];
  out.forEach((l, k) => { if (keep[k]) lines.push(l); else if (keep[k - 1]) lines.push(["", "⋯"]); });
  return { lines: added + removed ? lines : [], added, removed };
}

/** Run the live version on a runner or pool, with its inputs. */
function RunAgent({ api, a, onClose, onDone }: { api: CrApi; a: RepoAgent; onClose: () => void; onDone: (m: string) => void }) {
  const [runners, setRunners] = useState<Runner[]>([]);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [where, setWhere] = useState("");
  const [vars, setVars] = useState<FlowVariable[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    Promise.all([api.devices().catch(() => []), api.pools().catch(() => [])]).then(([d, p]) => {
      setRunners(d); setPools(p); const first = d.find((r) => r.enabled && r.status === "online") ?? d.find((r) => r.enabled); setWhere(first ? "d:" + first.id : p[0] ? "p:" + p[0].id : "");
    });
    api.agentFlow(a.path, "production").then((r) => setVars((r.flow.variables ?? []).filter((v) => v.direction === "input" || v.direction === "in_out")), () => {});
  }, [api, a.path]);
  const run = () => {
    const inputs: Record<string, unknown> = {};
    try {
      for (const v of vars) {
        const t = (values[v.name] ?? "").trim();
        if (!t) continue;
        inputs[v.name] = v.type === "Credential" ? { $credential: t } : v.type === "Boolean" ? t === "true" : v.type === "Number" ? Number(t)
          : ["List", "Dictionary", "Table", "Record", "Any"].includes(v.type ?? "") ? JSON.parse(t) : t;
      }
    } catch { setError("An input meant as JSON is not valid JSON."); return; }
    api.startRun({ bot_path: a.path, ...(where.startsWith("p:") ? { pool_id: where.slice(2) } : { device_id: where.slice(2) }), inputs })
      .then((r) => onDone("Started " + agentName(a.path) + " (run " + r.run_id + "): follow it in Agent Activity."), (e) => setError(message(e)));
  };
  const missing = vars.some((v) => v.required && !(values[v.name] ?? "").trim());
  return (
    <Dialog open title={"Run " + agentName(a.path)} width={620} onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!where || missing} onClick={run}>Run</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">Runs the live version, v{a.production_version}.</p>
      <Field label="Run on">{(id) => <Select id={id} value={where} onChange={(e) => setWhere(e.target.value)} options={[...runners.filter((r) => r.enabled).map((r) => ({ value: "d:" + r.id, label: r.name + " (" + r.status + ")" })),
        ...pools.map((p) => ({ value: "p:" + p.id, label: "Pool " + p.name + " (least busy runner)" }))]} />}</Field>
      {vars.map((v) => <Field key={v.name} label={v.name + (v.required ? " *" : "") + " (" + v.type + ")"} hint={v.description}>{(id) => v.type === "Boolean"
        ? <Select id={id} value={values[v.name] ?? ""} onChange={(e) => setValues({ ...values, [v.name]: e.target.value })} options={[{ value: "", label: "(not set)" }, { value: "true", label: "True" }, { value: "false", label: "False" }]} />
        : <TextInput id={id} type={v.type === "Number" ? "number" : "text"} value={values[v.name] ?? ""} placeholder={v.type === "Credential" ? "vault folder/credential/attribute" : v.default !== undefined ? "Default " + JSON.stringify(v.default) : ""}
          onChange={(e) => setValues({ ...values, [v.name]: e.target.value })} />}</Field>)}
      {!vars.length && <p className="st-muted">This agent has no inputs.</p>}
    </Dialog>
  );
}

function NewFolder({ api, parent, onClose, onDone }: { api: CrApi; parent: string; onClose: () => void; onDone: (p: string) => void }) {
  const [v, setV] = useState({ path: parent ? parent + "/" : "", description: "" });
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title="New folder" onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!v.path.replace(/\/$/, "").trim()} onClick={() => api.createFolder(v.path.replace(/\/$/, "").trim(), v.description.trim()).then((r) => onDone(r.path), (e) => setError(message(e)))}>Create folder</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Folder (sub-folders with /)">{(id) => <TextInput id={id} value={v.path} placeholder="Finance/Invoices" onChange={(e) => setV({ ...v, path: e.target.value })} />}</Field>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
    </Dialog>
  );
}

function RenameFolder({ api, folder, onClose, onDone }: { api: CrApi; folder: string; onClose: () => void; onDone: (p: string, n: number) => void }) {
  const [to, setTo] = useState(folder);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title={"Rename or move " + folder} onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!to.trim() || to.trim() === folder} onClick={() => api.renameFolder(folder, to.trim()).then((r) => onDone(r.path, r.agents), (e) => setError(message(e)))}>Rename</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="New path">{(id) => <TextInput id={id} value={to} onChange={(e) => setTo(e.target.value)} />}</Field>
      <p className="st-muted">Everything in it moves too: agents with their versions, labels, schedules, triggers and run history.</p>
    </Dialog>
  );
}

/** Who may see the folder's agents, and what they may do. */
function FolderPermissions({ api, folder, onClose, onDone }: { api: CrApi; folder: string; onClose: () => void; onDone: (m: string) => void }) {
  const [rows, setRows] = useState<{ principal: string; access: string }[] | null>(null);
  const [roles, setRoles] = useState<string[]>([]);
  const [add, setAdd] = useState({ who: "role:*", user: "", access: "view" });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api.folderPermissions(folder).then(setRows, (e) => setError(message(e)));
    api.roles().then((r) => setRoles(r.map((x) => x.name)), () => {});
  }, [api, folder]);
  const label = (p: string) => p === "role:*" ? "Everyone" : p.startsWith("role:") ? "Role " + p.slice(5) : p.startsWith("team:") ? "Team " + p.slice(5) : "User " + p.slice(5);
  const plus = () => { const pr = add.user.trim() ? "user:" + add.user.trim() : add.who; if (!rows!.some((r) => r.principal === pr)) setRows([...rows!, { principal: pr, access: add.access }]); setAdd({ ...add, user: "" }); };
  return (
    <Dialog open title={"Permissions of " + folder} width={680} onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!rows} onClick={() => api.saveFolderPermissions(folder, rows!).then(() => onDone("Saved the permissions of " + folder + "."), (e) => setError(message(e)))}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">With permissions of its own, only those listed see the folder's agents (and its sub-folders', unless one has its own). Roles still apply: a folder never grants what a role does not allow.</p>
      {rows && (rows.length ? <table className="st-table" aria-label="Permissions"><tbody>{rows.map((r, i) => <tr key={r.principal} className="is-static"><td>{label(r.principal)}</td>
        <td><Select aria-label={"Access of " + label(r.principal)} value={r.access} onChange={(e) => setRows(rows.map((x, k) => k === i ? { ...x, access: e.target.value } : x))} options={ACCESS.map(([value, l]) => ({ value, label: l }))} /></td>
        <td><Button kind="plain" onClick={() => setRows(rows.filter((_, k) => k !== i))}>Remove</Button></td></tr>)}</tbody></table>
        : <p className="st-muted">No permissions of its own: it follows its parent folder, or at the top, the roles alone.</p>)}
      <div className="cc-grid2">
        <Field label="Add a role">{(id) => <Select id={id} value={add.who} onChange={(e) => setAdd({ ...add, who: e.target.value })} options={[{ value: "role:*", label: "Everyone" }, ...roles.map((r) => ({ value: "role:" + r, label: r }))]} />}</Field>
        <Field label="Or a user name">{(id) => <TextInput id={id} value={add.user} onChange={(e) => setAdd({ ...add, user: e.target.value })} />}</Field>
        <Field label="Access">{(id) => <Select id={id} value={add.access} onChange={(e) => setAdd({ ...add, access: e.target.value })} options={ACCESS.map(([value, l]) => ({ value, label: l }))} />}</Field>
      </div>
      <Button kind="secondary" disabled={!rows} onClick={plus}>Add</Button>
    </Dialog>
  );
}

function Deleted({ api, onClose, onDone }: { api: CrApi; onClose: () => void; onDone: (m: string) => void }) {
  const [list, setList] = useState<RepoAgent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => api.deletedAgents().then(setList, (e) => setError(message(e))), [api]);
  useEffect(() => { load(); }, [load]);
  return (
    <Dialog open title="Deleted agents" width={680} onClose={onClose} footer={null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <table className="st-table" aria-label="Deleted agents list"><tbody>
        {(list ?? []).map((b) => <tr key={b.path} className="is-static"><td>{agentName(b.path)}<div className="st-muted">{folderOf(b.path)}</div></td><td>{when(b.deleted_at)}</td><td>{b.deleted_by ?? ""}</td>
          <td><Button kind="secondary" onClick={() => api.recoverAgent(b.path).then(() => { onDone("Recovered " + agentName(b.path) + "."); load(); }, (e) => setError(message(e)))}>Recover</Button></td></tr>)}
        {list && !list.length && <tr className="is-static"><td className="st-muted">No deleted agents.</td></tr>}
      </tbody></table>
    </Dialog>
  );
}

/** Versions waiting to go live: approve or reject someone else's, withdraw your own; recent decisions below. */
function Approvals({ api, user, onClose, onNotice }: { api: CrApi; user: CrUser; onClose: () => void; onNotice: (m: string) => void }) {
  const [data, setData] = useState<{ pending: ProductionRequest[]; done: ProductionRequest[]; on: boolean } | null>(null);
  const [note, setNote] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => Promise.all([api.productionRequests("pending"), api.productionRequests()])
    .then(([p, a]) => setData({ pending: p.requests, done: a.requests.filter((r) => r.status !== "pending").slice(0, 20), on: p.approval_on }), (e) => setError(message(e))), [api]);
  useEffect(() => { load(); }, [load]);
  const decide = (r: ProductionRequest, how: "approve" | "reject" | "withdraw") => api.decideRequest(r.id, how, note[r.id] ?? "")
    .then(() => { onNotice(how === "approve" ? "v" + r.version + " of " + agentName(r.path) + " is live." : how === "reject" ? "Rejected." : "Withdrawn."); load(); }, (e) => setError(message(e)));
  return (
    <Dialog open title="Production approvals" width={860} onClose={onClose} footer={null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {data && <p className="st-muted">{data.on ? "A version goes live only when someone with the approval right, other than the person who asked, approves it."
        : "Approval before going live is off (Administration > Settings); requests made before still wait here."}</p>}
      <table className="st-table" aria-label="Waiting"><thead><tr><th scope="col">Agent</th><th scope="col">Version</th><th scope="col">Asked by</th><th scope="col">What changed</th><th scope="col">Note</th><th scope="col"></th></tr></thead>
        <tbody>{(data?.pending ?? []).map((r) => <tr key={r.id} className="is-static"><td>{agentName(r.path)}</td><td>v{r.version}</td><td>{r.requested_by}<div className="st-muted">{when(r.requested)}</div></td><td>{r.comment ?? ""}</td>
          <td>{r.can_decide && <TextInput aria-label={"Note on v" + r.version + " of " + agentName(r.path)} value={note[r.id] ?? ""} onChange={(e) => setNote({ ...note, [r.id]: e.target.value })} />}</td>
          <td className="cc-inline">{r.can_decide && <><Button kind="primary" onClick={() => decide(r, "approve")}>Approve</Button><Button kind="danger" onClick={() => decide(r, "reject")}>Reject</Button></>}
            {r.requested_by === user.username && <Button kind="plain" onClick={() => decide(r, "withdraw")}>Withdraw</Button>}</td></tr>)}
          {data && !data.pending.length && <tr className="is-static"><td colSpan={6} className="st-muted">Nothing is waiting.</td></tr>}</tbody></table>
      {data && data.done.length > 0 && <><h3 className="cc-log-title">Decided</h3>
        <table className="st-table" aria-label="Decided"><tbody>{data.done.map((r) => <tr key={r.id} className="is-static"><td>{agentName(r.path)}</td><td>v{r.version}</td><td>{r.requested_by}</td>
          <td className={"cc-status is-" + (r.status === "approved" ? "success" : "failed")}>{r.status}</td><td>{r.decided_by ?? ""}</td><td>{when(r.decided)}</td><td>{r.note ?? ""}</td></tr>)}</tbody></table></>}
    </Dialog>
  );
}
