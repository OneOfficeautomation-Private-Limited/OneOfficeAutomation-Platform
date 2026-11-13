import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { CrApi, CrUser, FlowVariable, ProductionRequest, RepoAgent, RepoFolder, RepoVersion, Run, Runner, RunnerPool } from "../api/cr";
import type { FlowStep } from "../api/client";
import { Button, Dialog, Field, HelpButton, Icon, IconTile, Menu, Select, TextInput, usePersistent, type HelpTopic, reportMinor, PageError } from "../components";
import { conditionText, describeAction, plainValue, stepRows, summarize } from "../studio/toolkits";
import { RefreshButton, RowMenu, rowClick, when, CloseView } from "./ListParts";
import { AgentAnalysis, ExportAgents, ImportAgents, RepoFiles } from "./RepoTools";
import { Analysis, ColumnsDialog, PUBLIC_COLUMNS, PUBLIC_HIDDEN, PUBLIC_HIDDEN_KEY, sizeText } from "../studio/columns";
import { PageIntro } from "../shell/PageIntro";
import { HelpLinks } from "../shell/HelpLinks";

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
/** A folder icon, open or closed (the colour of a paper folder, outlined for contrast). */
export function FolderIcon({ open }: { open?: boolean }) {
  return open ? <svg className="cc-fico" aria-hidden="true" width="16" height="16" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v1H8.5a2 2 0 0 0-1.9 1.4L4 19H5" fill="none" stroke="#a86b0c" strokeWidth="1.8" />
    <path d="M6.6 11.4A2 2 0 0 1 8.5 10H21l-2.6 7.6A2 2 0 0 1 16.5 19H4z" fill="#f6d58b" stroke="#a86b0c" strokeWidth="1.8" strokeLinejoin="round" /></svg>
    : <svg className="cc-fico" aria-hidden="true" width="16" height="16" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" fill="#f6d58b" stroke="#a86b0c" strokeWidth="1.8" strokeLinejoin="round" /></svg>;
}
const AgentIcon = ({ kind }: { kind: string }) => <span className={"cc-aico is-" + kind.toLowerCase()} aria-hidden="true">{kind === "Workflow" ? "W" : kind === "Form" ? "F" : "A"}</span>;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const ACCESS = [["view", "View: see and export"], ["run", "Run: also run and schedule"], ["edit", "Edit: also check out, check in, move, delete"], ["manage", "Manage: also live version and labels"]];

type Dlg = null | { kind: "agent"; a: RepoAgent } | { kind: "folder"; parent: string } | { kind: "rename" } | { kind: "permissions" } | { kind: "deleted" } | { kind: "approvals" }
  | { kind: "run"; a: RepoAgent; from?: "agent" } | { kind: "export" } | { kind: "import" };

const HELP: HelpTopic = { title: "Agents",
  about: "The shared (public) workspace: agents, workflows and forms checked in from people's Private workspaces, with every version. "
    + "The live version is the one runners, schedules and triggers use. People who build agents also have the Private tab, their own workspace.",
  items: [
    { name: "Folders", text: "Pick a folder in the tree to list what is in it; Open all unfolds every folder. A tag shows folders with permissions of their "
      + "own." },
    { name: "Include subfolders", text: "On lists the agents in the chosen folder and every folder below it; off lists only the chosen folder." },
    { name: "Type and State", text: "Show only agents, workflows or forms, and only those that are live, not live yet or checked out by someone." },
    { name: "Live and Status", text: "Live is the version that runs; Latest is the newest checked-in version. Status shows who has the agent checked out to "
      + "change it in their Private workspace." },
    { name: "Folder permissions", text: "Lets you say who sees the chosen folder's agents and what they may do: view, run, edit or manage. Roles still "
      + "apply; a folder never gives more than a role allows." },
    { name: "Manage", text: "Import agents, export or promote them to another One CommandCenter, decide production approvals, recover deleted agents and "
      + "set the code analysis rules. What you see depends on your permissions." },
    { name: "Create", text: "Makes a new folder in the chosen folder, or a new agent in your Private workspace (for people who build agents)." },
    { name: "Run…", text: "In an agent's ⋯ menu: runs its live version on a runner or pool, with its inputs. Needs permission to start runs." },
    { name: "Make it an Agent API…", text: "Lets other systems call the agent over the web. It needs a live version; Open its Agent API shows one that "
      + "exists." },
  ] };

const HELP_AGENT: HelpTopic = { title: "Agent",
  about: "One agent, workflow or form: its steps, inputs, versions, runs and dependencies. Everything here is read-only; to change the agent, "
    + "Edit checks it out to your Private workspace in OneAgentStudio, where you change it and check it in. Close (or Escape) goes back to the folder.",
  items: [
    { name: "Steps", text: "Every line of the chosen version. Pick a line to see its settings at the right; Find in the steps marks matching lines, and "
      + "Open all or Close all unfolds or folds blocks." },
    { name: "Inputs and details", text: "Where the agent is, its live and latest versions, who changed it last and your access; then the inputs and "
      + "outputs of the version you pick (the live one first)." },
    { name: "Versions", text: "Every checked-in version with its comment, author and labels. Tick two and press Compare to see what changed line by line." },
    { name: "Make live", text: "Makes a version the one that runs; runners, schedules and triggers use it from their next run. "
      + "When approval is on, Ask to make live sends it to someone else to approve." },
    { name: "Label", text: "Puts a name such as UAT on a version. A label is on one version at a time, so it moves from the version that had it." },
    { name: "Runs and Dependencies", text: "The latest runs of the agent, and the agents it calls, the agents that call it and the toolkits it uses." },
    { name: "Findings", text: "What code analysis found in the agent, measured against the rules set in Manage, Code analysis." },
    { name: "Run", text: "Runs the live version on a runner or pool with its inputs; follow it in Agent Activity." },
    { name: "Copy to Private… and Clone to Private", text: "In the ⋯ menu. Copy makes a new agent of your own from the latest version, in your "
      + "Private workspace; Clone makes a read-only copy there to look at, which follows the repository. Both open OneAgentStudio. "
      + "Copies are never made here, in the repository." },
    { name: "Move… and Delete…", text: "Move takes the agent to another folder (administrators; builders move agents in Private before checking "
      + "them in). Delete asks for an optional reason for the audit log and is refused while a schedule, trigger, queue or other agent uses "
      + "the agent. Deleted agents can be recovered." },
    { name: "Undo check-out", text: "Releases the agent when the person who checked it out will not check it in. Shown to that person and to those who may "
      + "make versions live." },
  ] };

export function Repository({ api, user, onNotice, studio = false }: { api: CrApi; user: CrUser; onNotice: (t: string) => void; studio?: boolean }) {
  const [agents, setAgents] = useState<RepoAgent[] | null>(null);
  const [folders, setFolders] = useState<RepoFolder[]>([]);
  const [requests, setRequests] = useState<{ requests: ProductionRequest[]; approval_on: boolean }>({ requests: [], approval_on: false });
  const [folder, setFolder] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState(() => new URLSearchParams(location.hash.split("?")[1] ?? "").get("q") ?? "");   // #/automation?q=<agent>, from the top bar's search
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
  const filtered = !!(q.trim() || kind || show);
  const rowMenu = (a: RepoAgent) => [
    { key: "open", label: "Open", onSelect: () => setDialog({ kind: "agent", a }) },
    ...(can("runs.start") && a.production_version && kindOf(a.path) === "Agent" ? [{ key: "run", label: "Run…", onSelect: () => setDialog({ kind: "run", a }) }] : []),
    { key: "folder", label: "Show its folder", onSelect: () => setFolder(folderOf(a.path)) },
    ...(can("apitasks.manage") && kindOf(a.path) === "Agent" ? [apis.has(a.path)
      ? { key: "api", label: "Open its Agent API", onSelect: () => { location.hash = "#/apitasks"; } }
      : { key: "api", label: "Make it an Agent API…", disabled: !a.production_version, onSelect: () => { location.hash = "#/apitasks?new=" + encodeURIComponent(a.path); } }] : []),
  ];

  const [opened, setOpened] = usePersistent<string[]>("repo.opened", []);
  const [apis, setApis] = useState<Set<string>>(new Set());
  useEffect(() => { if (can("apitasks.manage")) api.agentApis().then((l) => setApis(new Set(l.map((t) => t.bot_path))), (e) => reportMinor("which agents have an API", e)); }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  const [withSub, setWithSub] = usePersistent<boolean>("repo.withSubfolders", true);
  // the same columns, and the same choice of them, as the Private workspace's Public tab
  const [hiddenCols, setHiddenCols] = usePersistent<string[]>(PUBLIC_HIDDEN_KEY, PUBLIC_HIDDEN);
  const [choosingCols, setChoosingCols] = useState(false);
  const kids = (p: string) => folders.filter((f) => folderOf(f.path) === p).sort((a, b) => a.path.localeCompare(b.path));
  const total = (p: string) => folders.filter((f) => !p || f.path === p || f.path.startsWith(p + "/")).reduce((n, f) => n + f.agents, 0);
  const isOpen = (p: string) => opened.includes(p) || folder === p || folder.startsWith(p + "/");
  const toggle = (p: string) => setOpened(opened.includes(p) ? opened.filter((x) => x !== p) : [...opened, p]);
  const node = (f: RepoFolder, depth: number): ReactNode => {
    const children = kids(f.path), open = isOpen(f.path), name = f.path.split("/").pop();
    return <li key={f.path}>
      <div className={"cc-tree-row" + (folder === f.path ? " is-on" : "")} style={{ paddingLeft: 6 + 16 * depth }}>
        {children.length ? <button type="button" className="cc-tree-chev" aria-expanded={open} aria-label={(open ? "Close " : "Open ") + name} onClick={() => toggle(f.path)}>{open ? "▾" : "▸"}</button> : <span className="cc-tree-chev" />}
        <button type="button" className="cc-tree-name" aria-current={folder === f.path ? "page" : undefined} title={f.description ?? undefined} onClick={() => setFolder(f.path)}>
          <FolderIcon open={open && children.length > 0} />{name}{f.has_permissions && <span className="cc-tag">Own permissions</span>}<span className="cc-tree-n">{total(f.path)}</span></button>
      </div>
      {open && children.length > 0 && <ul>{children.map((c) => node(c, depth + 1))}</ul>}
    </li>;
  };
  const here = shown.filter((a) => withSub || folderOf(a.path) === folder);
  const subfolders = filtered ? [] : kids(folder);
  const crumbs = folder ? folder.split("/") : [];
  const manage = [
    ...(can("lifecycle.import") ? [{ key: "import", label: "Import", onSelect: () => setDialog({ kind: "import" }) }] : []),
    ...(can("lifecycle.export") ? [{ key: "export", label: "Export or promote", onSelect: () => setDialog({ kind: "export" }) }] : []),
    ...(requests.approval_on || requests.requests.length > 0 ? [{ key: "approvals", label: "Production approvals" + (requests.requests.length ? " (" + requests.requests.length + " waiting)" : ""), onSelect: () => setDialog({ kind: "approvals" }) }] : []),
    ...(can("repo.delete") ? [{ key: "deleted", label: "Deleted agents", onSelect: () => setDialog({ kind: "deleted" }) }] : []),
    { key: "analysis", label: "Code analysis policies", description: "In Administration > Policies", onSelect: () => { location.hash = "#/policies"; } },
  ];
  const shownCols = PUBLIC_COLUMNS.filter((c) => !hiddenCols.includes(c.key));
  const showFolder = withSub || !!q.trim();   // agents from more than one folder
  const span = 2 + (showFolder ? 1 : 0) + shownCols.length;
  const cellOf = (key: string, a: RepoAgent): ReactNode => {
    switch (key) {
      case "type": return <>{kindOf(a.path)}{apis.has(a.path) && <span className="cc-tag" title="Other systems can call it as an Agent API">API</span>}</>;
      case "platform": return a.platform ?? "";
      case "latest": return "v" + a.latest_version;
      case "live": return a.production_version ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Live v{a.production_version}</span> : <span className="cc-none">Not live</span>;
      case "label": return (a.labels ?? []).map((l) => <span key={l.label} className="cc-tag" title={"On v" + l.version}>{l.label}</span>);
      case "analysis": return <Analysis found={a.analysis} />;
      case "size": return sizeText(a.size);
      case "out": return a.locked_by ? <span className="cc-pill is-pending"><span className="cc-pill-dot" />By {a.locked_by}</span> : "";
      case "changed": return when(a.updated);
      case "by": return a.updated_by ?? "";
      default: return "";
    }
  };
  const create = [
    ...(can("repo.checkin") ? [{ key: "folder", label: "New folder" + (folder ? " in " + folder.split("/").pop() : ""), onSelect: () => setDialog({ kind: "folder", parent: folder }) }] : []),
    ...(studio && can("repo.checkin") ? [{ key: "agent", label: "New agent", description: "In your Private workspace", onSelect: () => { location.href = "../studio/#private/new"; } }] : []),
  ];
  const folderMenu = [
    ...(can("repo.checkin") ? [{ key: "sub", label: "New subfolder", onSelect: () => setDialog({ kind: "folder", parent: folder }) },
      { key: "rename", label: "Rename or move folder", onSelect: () => setDialog({ kind: "rename" }) }] : []),
    ...(can("repo.delete") && cur && inside === 0 ? [{ key: "delete", label: "Delete folder…", separatorBefore: true, onSelect: () => setConfirmDelete(true) }] : []),
  ];

  if (dialog?.kind === "agent" || (dialog?.kind === "run" && dialog.from === "agent")) {
    const a = dialog.a;
    return (
      <section className="st-page cc-home cc-explorer" aria-label={"Agents: " + agentName(a.path)}>
        <AgentView key={a.path} api={api} user={user} a={a} folders={folders} approvalOn={requests.approval_on} studio={studio} onClose={() => setDialog(null)}
          onFolder={(f) => { setDialog(null); setFolder(f); }} onRun={() => setDialog({ kind: "run", a, from: "agent" })} onDone={done} />
        {dialog.kind === "run" && <RunAgent api={api} a={a} onClose={() => setDialog({ kind: "agent", a })} onDone={done} />}
      </section>
    );
  }

  return (
    <section className="st-page cc-home cc-explorer" aria-labelledby="cc-repo-title">
      <header className="cc-list-head">
        <h1 id="cc-repo-title">Agents</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:automation" />
        {agents && <span className="cc-list-count">{total("")} agent{total("") === 1 ? "" : "s"} · {folders.length} folder{folders.length === 1 ? "" : "s"}{toDecide ? " · " + toDecide + " to approve" : ""}</span>}
        <span className="ov-fill" />
        <Menu label="Manage" align="end" items={manage} trigger={<button type="button" className="ui-btn ui-btn-secondary" aria-label="Manage">Manage ▾</button>} />
        {create.length > 0 && <Menu label="Create" align="end" items={create} trigger={<button type="button" className="ui-btn ui-btn-primary" aria-label="Create">Create ▾</button>} />}
      </header>
      {/* AA's one Automation page: Public (agents checked in, to run) and Private (a developer's own workspace,
          for people who build agents); everyone sees the same page, only what they may open differs */}
      <div className="ov-tabs cc-ins-tabs" role="tablist" aria-label="Where">
        <button type="button" role="tab" className="ov-tab" aria-selected title="One CommandCenter agent repository: agents checked in, to run">Public</button>
        {studio && user.permissions.includes("repo.checkin")
          ? <a className="ov-tab" role="tab" aria-selected={false} href="../studio/#private" title="Your own workspace: build and test agents, then check them in">Private</a>
          : <span className="ov-tab is-off" role="tab" aria-selected={false} aria-disabled title={!user.permissions.includes("repo.checkin")
            ? "Private workspaces are for people who build agents" : "Private workspaces are not set up on this One CommandCenter"}>Private</span>}
      </div>
      {error && <PageError text={error} />}
      <div className="cc-explorer-body">
        <nav className="cc-tree-pane" aria-label="Folders">
          <div className="cc-pane-head"><h2>Folders</h2><span className="ov-fill" />
            <button type="button" className="cc-link" onClick={() => setOpened(folders.filter((f) => kids(f.path).length).map((f) => f.path))}>Open all</button></div>
          <ul className="cc-tree">
            <li><div className={"cc-tree-row" + (folder === "" ? " is-on" : "")} style={{ paddingLeft: 6 }}><span className="cc-tree-chev" />
              <button type="button" className="cc-tree-name" aria-current={folder === "" ? "page" : undefined} onClick={() => setFolder("")}><FolderIcon open />Agents<span className="cc-tree-n">{total("")}</span></button></div>
              <ul>{kids("").map((f) => node(f, 1))}</ul></li>
          </ul>
          <p className="cc-pane-foot">The shared (public) workspace. Each builder's own workspace is the Private tab.</p>
        </nav>
        <div className="cc-files-pane">
          <div className="cc-pane-head">
            <span className="cc-crumbs-path"><FolderIcon open />
              <button type="button" className="cc-link" onClick={() => setFolder("")}>Agents</button>
              {crumbs.map((c, i) => <span key={i}> › {i === crumbs.length - 1 ? <strong>{c}</strong> : <button type="button" className="cc-link" onClick={() => setFolder(crumbs.slice(0, i + 1).join("/"))}>{c}</button>}</span>)}</span>
            {cur?.description && <span className="cc-none">{cur.description}</span>}
            <span className="ov-fill" />
            {folder && can("repo.permissions") && <Button kind="plain" onClick={() => setDialog({ kind: "permissions" })}>Folder permissions</Button>}
            {folder && folderMenu.length > 0 && <RowMenu label={"Actions for the folder " + folder} items={folderMenu} />}
          </div>
          {confirmDelete && <div className="cc-list-bulk" role="alert"><span>Delete the empty folder {folder}?</span>
            <Button kind="danger" onClick={() => api.deleteFolder(folder).then(() => { setConfirmDelete(false); setFolder(""); onNotice("Deleted the folder " + folder + "."); }, (e) => setError(message(e)))}>Delete</Button>
            <Button kind="plain" onClick={() => setConfirmDelete(false)}>Cancel</Button></div>}
          <div className="cc-list-tools cc-pane-tools">
            <div className="cc-list-filters" role="group" aria-label="Filters">
              <span className="cc-list-search"><TextInput type="search" aria-label="Search agents" placeholder={"Search in " + (folder ? folder.split("/").pop() : "Agents")} value={q} onChange={(e) => setQ(e.target.value)} /></span>
              <label className={"cc-chip-check" + (withSub ? " is-on" : "")}><input type="checkbox" checked={withSub} onChange={(e) => setWithSub(e.target.checked)} /> Include subfolders</label>
              <span className={"cc-chip" + (kind ? " is-on" : "")}><Select aria-label="Kind" value={kind} onChange={(e) => setKind(e.target.value)} options={[{ value: "", label: "Type: Any" }, ...["Agent", "Workflow", "Form"].map((k) => ({ value: k, label: "Type: " + k + "s" }))]} /></span>
              <span className={"cc-chip" + (show ? " is-on" : "")}><Select aria-label="Status" value={show} onChange={(e) => setShow(e.target.value)} options={[{ value: "", label: "Status: Any" }, { value: "live", label: "Status: Live" }, { value: "notlive", label: "Status: Not live yet" }, { value: "out", label: "Status: Checked out" }]} /></span>
              {filtered && <Button kind="plain" onClick={() => { setQ(""); setKind(""); setShow(""); }}>Clear filters</Button>}
            </div>
            <div className="cc-list-right" role="toolbar" aria-label="Agents list tools"><Button kind="plain" onClick={() => setChoosingCols(true)}>Columns</Button><RefreshButton onClick={load} /></div>
            {choosingCols && <ColumnsDialog columns={PUBLIC_COLUMNS} hidden={hiddenCols} onChange={setHiddenCols} onClose={() => setChoosingCols(false)} />}
          </div>
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label="Agents list">
              <thead><tr><th scope="col">Name</th>{showFolder && <th scope="col">Folder</th>}{shownCols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>
                {subfolders.map((f) => (
                  <tr key={"f:" + f.path} className="cc-folder-row" onClick={rowClick(() => setFolder(f.path))}>
                    <td title={f.description ?? undefined}><FolderIcon /><strong>{f.path.split("/").pop()}</strong> <span className="cc-none">({total(f.path)})</span></td>
                    {showFolder && <td>{folderOf(f.path) || <span className="cc-none">(top)</span>}</td>}{shownCols.map((c) => <td key={c.key}>{c.key === "type" ? "Folder" : ""}</td>)}<td className="cc-list-menu" />
                  </tr>
                ))}
                {here.map((a) => (
                  <tr key={a.path} onClick={rowClick(() => setDialog({ kind: "agent", a }))}>
                    <td title={a.description ?? undefined}><AgentIcon kind={kindOf(a.path)} /><strong>{agentName(a.path)}</strong></td>
                    {showFolder && <td>{folderOf(a.path) || <span className="cc-none">(top)</span>}</td>}
                    {shownCols.map((c) => <td key={c.key}>{cellOf(c.key, a)}</td>)}
                    <td className="cc-list-menu"><RowMenu label={"Actions for " + agentName(a.path)} items={rowMenu(a)} /></td>
                  </tr>
                ))}
                {agents && !subfolders.length && !here.length && <tr className="is-static"><td colSpan={span} className="cc-none">{agents.length ? "Nothing matches." : "No agents here yet. Build one in Private and check it in."}</td></tr>}
                {!agents && !error && <tr className="is-static"><td colSpan={span} className="cc-none" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          {agents && <div className="cc-list-pager"><span>{subfolders.length ? subfolders.length + " folder" + (subfolders.length === 1 ? "" : "s") + " · " : ""}{here.length} agent{here.length === 1 ? "" : "s"}{withSub ? " here and in subfolders" : " in this folder"}{filtered ? " matching" : ""}</span></div>}
          <RepoFiles key={folder} api={api} user={user} folder={folder} onNotice={onNotice} />
        </div>
      </div>
      {dialog?.kind === "run" && <RunAgent api={api} a={dialog.a} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === "folder" && <NewFolder api={api} parent={dialog.parent} onClose={() => setDialog(null)} onDone={(p) => { done("Created the folder " + p + "."); setFolder(p); }} />}
      {dialog?.kind === "rename" && <RenameFolder api={api} folder={folder} onClose={() => setDialog(null)} onDone={(p, n) => { done("Moved " + n + " agent(s) to " + p + "."); setFolder(p); }} />}
      {dialog?.kind === "permissions" && <FolderPermissions api={api} folder={folder} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === "deleted" && <Deleted api={api} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); load(); }} />}
      {dialog?.kind === "approvals" && <Approvals api={api} user={user} onClose={() => { setDialog(null); load(); }} onNotice={onNotice} />}
      {dialog?.kind === "export" && <ExportAgents api={api} onClose={() => setDialog(null)} onNotice={onNotice} />}
      {dialog?.kind === "import" && <ImportAgents api={api} folder={folder} onClose={() => { setDialog(null); load(); }} onDone={(m) => { onNotice(m); load(); }} />}
    </section>
  );
}

/** One agent: what it is, its inputs and outputs, what it calls and who calls it, recent runs, and its versions. */
function AgentView({ api, user, a, folders, approvalOn, studio, onClose, onFolder, onRun, onDone }: { api: CrApi; user: CrUser; a: RepoAgent; folders: RepoFolder[]; approvalOn: boolean; studio: boolean;
  onClose: () => void; onFolder: (folder: string) => void; onRun: () => void; onDone: (m: string) => void }) {
  const [tab, setTab] = useState<"steps" | "about" | "versions" | "runs" | "deps" | "findings">("steps");
  const [shownVersion, setShownVersion] = useState<string>("latest");
  const [flow, setFlow] = useState<FlowStep[] | null>(null);
  const [names, setNames] = useState<Record<string, string>>({});
  const [folded, setFolded] = useState<Set<string>>(new Set());
  const [picked, setPicked] = useState<string | null>(null);
  const [find, setFind] = useState("");
  useEffect(() => { api.actionNames().then(setNames, (e) => reportMinor("the command names", e)); }, [api]);
  useEffect(() => {
    setFlow(null);
    api.agentFlow(a.path, shownVersion === "latest" || shownVersion === "production" ? shownVersion : Number(shownVersion))
      .then((r) => setFlow((r.flow.steps ?? []) as FlowStep[]), () => setFlow([]));
  }, [api, a.path, shownVersion]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === "Escape" && !(e.target as HTMLElement).closest?.("[role=dialog] [role=dialog]")) onClose(); };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, [onClose]);
  const can = (p: string) => user.permissions.includes(p);
  const editable = ["edit", "manage"].includes(a.access ?? "edit");
  const [vars, setVars] = useState<FlowVariable[] | null>(null);
  const [deps, setDeps] = useState<Awaited<ReturnType<CrApi["dependencies"]>> | null>(null);
  const [runs, setRuns] = useState<Run[]>([]);
  const [versions, setVersions] = useState<RepoVersion[] | null>(null);
  const [picks, setPicks] = useState<number[]>([]);
  const [diff, setDiff] = useState<null | { a: number; b: number; lines: [string, string][]; added: number; removed: number }>(null);
  const [ask, setAsk] = useState<null | { kind: "label" | "live" | "move" | "delete"; version?: number }>(null);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const loadVersions = useCallback(() => api.agentVersions(a.path).then(setVersions, (e) => setError(message(e))), [api, a.path]);
  // Inputs and details show the inputs of any version (item 23): the live one first, else the latest
  const [ioVersion, setIoVersion] = useState<number>(a.production_version ?? a.latest_version);
  useEffect(() => {
    setVars(null);
    api.agentFlow(a.path, ioVersion).then((r) => setVars(r.flow.variables ?? []), () => setVars([]));
  }, [api, a.path, ioVersion]);
  useEffect(() => {
    api.dependencies(a.path).then(setDeps, (e) => reportMinor("the agent's dependencies", e));
    if (can("runs.view")) api.runs({ limit: "8", bot: a.path }).then((r) => setRuns(r.runs), (e) => reportMinor("the agent's recent runs", e));
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
    if (ask.kind === "delete") api.deleteAgent(a.path, text.trim()).then(() => onDone("Deleted " + agentName(a.path) + ". It can be recovered from Deleted agents."), fail);
  };
  const inputs = (vars ?? []).filter((v) => v.direction === "input" || v.direction === "in_out");
  const outputs = (vars ?? []).filter((v) => v.direction === "output" || v.direction === "in_out");
  const kind = kindOf(a.path);
  const rows = flow ? stepRows(flow, folded) : [];
  const nameOf = (action: string) => names[action] ?? describeAction(action, []).name;
  const needle = find.trim().toLowerCase();
  const hit = (st: FlowStep) => !!needle && (nameOf(st.action ?? "") + " " + summarize(st) + " " + st.id).toLowerCase().includes(needle);
  const pick = picked ? rows.find((r) => r.kind === "step" && r.step.id === picked) : undefined;
  const sel = pick?.kind === "step" ? pick.step : null;
  const crumbs = folderOf(a.path) ? folderOf(a.path).split("/") : [];
  // as AA (item 21, 9 Nov 2026): the repository is for running and versions. Copies and clones are made in the
  // builder's Private workspace (OneAgentStudio); moving a single agent here is for administrators.
  const toStudio = (act: "checkout" | "clone" | "copy") => "../studio/#public/" + act + "/" + encodeURIComponent(a.path);
  const more = [
    ...(studio && can("repo.checkin") ? [
      { key: "copy", label: "Copy to Private…", description: "A new agent of your own, in OneAgentStudio", onSelect: () => { location.href = toStudio("copy"); } },
      { key: "clone", label: "Clone to Private", description: "A read-only copy to look at; it follows the repository", onSelect: () => { location.href = toStudio("clone"); } }] : []),
    ...(can("repo.production") && editable ? [{ key: "move", label: "Move…", description: "Administrators: to another folder of the repository", onSelect: () => { setText(folderOf(a.path)); setAsk({ kind: "move" }); } }] : []),
    ...(a.locked_by && (a.locked_by === user.username || can("repo.production")) ? [{ key: "undo", label: "Undo check-out", onSelect: () => { api.undoCheckout(a.path).then(() => onDone("Undid the check-out of " + agentName(a.path) + "."), fail); } }] : []),
    ...(can("repo.delete") && editable ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => { setText(""); setAsk({ kind: "delete" }); } }] : []),
  ];
  const TABS: [typeof tab, string][] = [["steps", "Steps" + (flow ? " (" + rows.filter((r) => r.kind === "step").length + ")" : "")], ["about", "Inputs and details"],
    ["versions", "Versions" + (versions ? " (" + versions.length + ")" : "")], ["runs", "Runs" + (runs.length ? " (" + runs.length + ")" : "")], ["deps", "Dependencies"],
    ...(kind !== "File" ? [["findings", "Findings"] as [typeof tab, string]] : [])];
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={agentName(a.path)}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link cc-back" onClick={onClose} title="Back to the folder">‹ Back</button>
          <button type="button" className="cc-link" onClick={() => onFolder("")}>Agents</button>
          {crumbs.map((c, i) => <span key={i}> › <button type="button" className="cc-link" onClick={() => onFolder(crumbs.slice(0, i + 1).join("/"))}>{c}</button></span>)} ›</span>
        <h1>{agentName(a.path)}</h1>
        <HelpButton topic={HELP_AGENT} />
        {a.production_version ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Live v{a.production_version}</span> : <span className="cc-pill is-stopped"><span className="cc-pill-dot" />Not live</span>}
        <span className="cc-none">{a.locked_by ? "Checked out by " + a.locked_by : "Checked in"} · latest v{a.latest_version}</span>
        <span className="ov-fill" />
        {can("runs.start") && a.production_version && kind === "Workflow" && <a className="ui-btn ui-btn-secondary cc-btn-link" href="#/workflows">Start in Workflow</a>}
        {studio && can("repo.checkin") && <a className="ui-btn ui-btn-secondary cc-btn-link" href={toStudio("checkout")}
          title={a.locked_by && a.locked_by !== user.username ? "Checked out by " + a.locked_by + ": OneAgentStudio offers Clone or Copy to Private instead" : "Check this agent out to your Private workspace in OneAgentStudio, change it there, then check it in"}>Edit</a>}
        {can("runs.start") && a.production_version && kind === "Agent" && <Button kind="primary" onClick={onRun}>Run</Button>}
        {more.length > 0 && <RowMenu label={"More for " + agentName(a.path)} items={more} />}
        <CloseView onClick={onClose} to="the folder" />
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {ask && <div className="st-alert cc-inline" role="alert">
        {ask.kind === "delete" && <><span>Delete {agentName(a.path)}? It is no longer listed and cannot run; its versions are kept and it can be recovered from Deleted agents.</span>
          <Field label="Reason (optional, kept in the audit log)">{(id) => <TextInput id={id} value={text} onChange={(e) => setText(e.target.value)} />}</Field></>}
        {ask.kind === "label" && <Field label={"Label for v" + ask.version + " (moves from the version that has it)"}>{(id) => <TextInput id={id} value={text} placeholder="UAT" onChange={(e) => setText(e.target.value)} />}</Field>}
        {ask.kind === "live" && (approvalOn
          ? <Field label={"Ask for v" + ask.version + " to go live: what changed, for the approver"}>{(id) => <TextInput id={id} value={text} onChange={(e) => setText(e.target.value)} />}</Field>
          : <span>Make v{ask.version} the live version? Runners, schedules and triggers use it from their next run.</span>)}
        {ask.kind === "move" && <Field label="Move to folder">{(id) => <Select id={id} value={text} onChange={(e) => setText(e.target.value)}
          options={[{ value: "", label: "(top)" }, ...folders.map((f) => ({ value: f.path, label: f.path }))]} />}</Field>}
        <Button kind={ask.kind === "delete" ? "danger" : "primary"} disabled={ask.kind === "label" && !text.trim()} onClick={confirm}>
          {{ delete: "Delete", label: "Save label", live: approvalOn ? "Ask" : "Make live", move: "Move" }[ask.kind]}</Button>
        <Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button>
      </div>}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this agent">
        {TABS.map(([k, l]) => <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "steps" && <div className="cc-agent-steps">
        <div className="cc-agent-code">
          <div className="cc-pane-tools cc-agent-tools">
            <span className="cc-list-search"><TextInput type="search" aria-label="Find in the steps" placeholder="Find in the steps" value={find} onChange={(e) => setFind(e.target.value)} /></span>
            <Button kind="plain" onClick={() => setFolded(new Set())}>Open all</Button>
            <Button kind="plain" onClick={() => setFolded(new Set(rows.filter((r) => r.kind === "step" && (r.step.children || r.step.else || r.step.elseIf || r.step.catch || r.step.cases || r.step.default || r.step.states)).map((r) => r.kind === "step" ? r.step.id : "")))}>Close all</Button>
            <span className="ov-fill" />
            <span className="cc-chip"><Select aria-label="Version shown" value={shownVersion} onChange={(e) => { setShownVersion(e.target.value); setPicked(null); }}
              options={[{ value: "latest", label: "Latest (v" + a.latest_version + ")" }, ...(a.production_version ? [{ value: "production", label: "Live (v" + a.production_version + ")" }] : []),
                ...(versions ?? []).map((v) => ({ value: String(v.version), label: "v" + v.version }))]} /></span>
            <span className="cc-none">Read-only</span>
          </div>
          {flow === null ? <p className="cc-none cc-agent-pad" role="status">Loading the steps…</p> : !rows.length ? <p className="cc-none cc-agent-pad">This agent has no steps.</p>
            : <ol className="st-step-list cc-agent-list" aria-label="Steps">
              <li aria-hidden="true"><span className="st-marker-row"><span className="st-marker st-start">START</span></span></li>
              {rows.map((r, i) => {
              if (r.kind === "section") return <li key={"s" + i}><div className="st-section cc-agent-section" style={{ paddingLeft: 46 + r.depth * 22 }}>{r.label}</div></li>;
              const st = r.step, d = describeAction(st.action ?? "", []), hasParts = !!(st.children || st.else || st.elseIf || st.catch || st.finally || st.cases || st.default || st.states);
              return <li key={st.id} className="st-step-row">
                <button type="button" className={"st-step" + (picked === st.id ? " is-selected" : "") + (st.disabled ? " is-disabled" : "") + (hit(st) ? " is-hit" : "")}
                  aria-pressed={picked === st.id} onClick={() => setPicked(st.id)}>
                  <span className="st-step-n">{r.n}</span>
                  <span className="st-guides" style={{ width: r.depth * 22 }} />
                  <IconTile name={d.mark.icon} color={d.mark.color} size={24} />
                  <strong>{nameOf(st.action ?? "")}</strong>
                  <span className="st-step-sum">{summarize(st)}</span>
                  {r.folded && <span className="st-pill">{r.hidden} {r.hidden === 1 ? "step" : "steps"} folded</span>}
                  {st.disabled && <span className="st-badge">OFF</span>}
                  <span className="cc-code-id">{st.id}</span>
                </button>
                {hasParts && <span className="st-step-tools">
                  <button type="button" className="ui-icon-btn" aria-label={(r.folded ? "Unfold line " : "Fold line ") + r.n} title={r.folded ? "Unfold" : "Fold"} aria-expanded={!r.folded}
                    onClick={() => { const n = new Set(folded); if (n.has(st.id)) n.delete(st.id); else n.add(st.id); setFolded(n); }}><Icon name={r.folded ? "chevronRight" : "chevronDown"} size={14} stroke={2.4} /></button>
                </span>}
              </li>;
            })}
              <li aria-hidden="true"><span className="st-marker-row"><span className="st-marker st-end">END</span></span></li>
            </ol>}
        </div>
        <aside className="cc-agent-side" aria-label="The selected line">
          {sel ? <>
            <div className="cc-pane-head"><IconTile name={describeAction(sel.action ?? "", []).mark.icon} color={describeAction(sel.action ?? "", []).mark.color} size={20} />
              <h2>Line {pick?.kind === "step" ? pick.n : ""} · {nameOf(sel.action ?? "")}</h2></div>
            <div className="cc-agent-pad">
              {Object.keys(sel.args ?? {}).length || Object.keys(sel.out ?? {}).length ? <table className="cc-au-kv"><tbody>
                {Object.entries(sel.args ?? {}).map(([k, v]) => <tr key={k}><th scope="row">{k.replace(/_/g, " ")}</th><td className="ui-mono">{k === "condition" ? conditionText(v) : plainValue(v, 400)}</td></tr>)}
                {Object.values(sel.out ?? {}).length > 0 && <tr><th scope="row">Result into</th><td className="ui-mono">{Object.values(sel.out ?? {}).map((x) => "$" + x).join(", ")}</td></tr>}
              </tbody></table> : <p className="cc-none">No settings.</p>}
              <p className="cc-none">Step <span className="ui-mono">{sel.id}</span> · {(sel.action ?? "").split("/")[0]}{sel.note ? " · note: " + sel.note : ""}</p>
              {can("runs.view") && <a href={"#/activity?tab=completed&status=failed&q=" + encodeURIComponent(a.path.split("/").pop()!.replace(/\.(flow|process|form)\.json$/, ""))}>Failed runs of this agent</a>}
            </div>
          </> : <p className="cc-none cc-agent-pad">Choose a line to see its settings.</p>}
        </aside>
      </div>}
      <div className="cc-agent-tab" hidden={tab === "steps"}>
      {tab === "about" && <>
      {a.description && <p>{a.description}</p>}
      <section className="cc-card cc-details" aria-label="Details">
        <h3 className="cc-log-title">Details</h3>
        <dl className="cc-facts cc-facts-wide">
          <dt>Kind</dt><dd>{kind}</dd><dt>Folder</dt><dd>{folderOf(a.path) || "(top)"}</dd>
          <dt>Status</dt><dd>{a.locked_by ? "Checked out by " + a.locked_by : "Checked in"}</dd><dt>Live version</dt><dd>{a.production_version ? "v" + a.production_version : "Not live yet"}</dd>
          <dt>Latest version</dt><dd>v{a.latest_version}</dd><dt>Updated</dt><dd>{when(a.updated)}{a.updated_by ? " by " + a.updated_by : ""}</dd>
          {a.bot_id && <><dt>Agent id</dt><dd className="ui-mono">{a.bot_id}</dd></>}<dt>Your access</dt><dd>{a.access ?? ""}</dd>
        </dl>
      </section>
      <div className="cc-bar">
        <h3 className="cc-log-title">Inputs and outputs</h3>
        <label className="cc-inline-field">Version <select className="ui-input" value={ioVersion} onChange={(e) => setIoVersion(Number(e.target.value))}>
          {Array.from({ length: a.latest_version }, (_, i) => a.latest_version - i).map((v) => <option key={v} value={v}>v{v}{v === a.production_version ? " (live)" : v === a.latest_version ? " (latest)" : ""}</option>)}
        </select></label>
      </div>
      <table className="st-table" aria-label="Inputs">
        <thead><tr><th scope="col">Input</th><th scope="col">Type</th><th scope="col">Required</th><th scope="col">Default</th><th scope="col">Description</th></tr></thead>
        <tbody>{vars === null ? <tr className="is-static"><td colSpan={5} className="cc-none">Loading…</td></tr>
          : inputs.length ? inputs.map((v) => <tr key={v.name} className="is-static"><td className="ui-mono">{v.name}</td><td>{v.type}</td><td>{v.required ? "Yes" : "No"}</td>
            <td className="ui-mono">{v.default === undefined || v.default === null || v.default === "" ? "" : typeof v.default === "object" ? JSON.stringify(v.default) : String(v.default)}</td><td>{v.description ?? ""}</td></tr>)
          : <tr className="is-static"><td colSpan={5} className="cc-none">This version takes no inputs.</td></tr>}</tbody>
      </table>
      <table className="st-table" aria-label="Outputs">
        <thead><tr><th scope="col">Output</th><th scope="col">Type</th><th scope="col">Description</th></tr></thead>
        <tbody>{vars !== null && outputs.length ? outputs.map((v) => <tr key={v.name} className="is-static"><td className="ui-mono">{v.name}</td><td>{v.type}</td><td>{v.description ?? ""}</td></tr>)
          : <tr className="is-static"><td colSpan={3} className="cc-none">{vars === null ? "Loading…" : "This version gives back no outputs."}</td></tr>}</tbody>
      </table>
      </>}
      {tab === "deps" && (deps ? <dl className="cc-facts">
        <dt>Calls</dt><dd>{deps.agents.length ? deps.agents.map((d) => <span key={d.bot_id} className={d.found ? "cc-tag" : "cc-risk"} title={d.found ? undefined : "Not in this One CommandCenter"}>{d.path ? agentName(d.path) : d.bot_id}</span>) : "No other agent"}</dd>
        <dt>Called by</dt><dd>{deps.called_by.length ? deps.called_by.map((p) => <span key={p} className="cc-tag">{agentName(p)}</span>) : "No other agent"}</dd>
        <dt>Toolkits</dt><dd>{deps.toolkits.length ? deps.toolkits.map((t) => <span key={t} className="cc-tag">{t}</span>) : "Built-in actions only"}</dd></dl> : <p className="cc-none" role="status">Loading…</p>)}
      {tab === "findings" && kind !== "File" && <AgentAnalysis api={api} path={a.path} />}
      {tab === "runs" && (runs.length > 0 ? <table className="st-table" aria-label="Recent runs">
        <thead><tr><th scope="col">Start time</th><th scope="col">Version</th><th scope="col">Status</th><th scope="col">Runner</th><th scope="col">Started by</th></tr></thead><tbody>{runs.map((r) => <tr key={r.id}><td><a href={"#/activity?run=" + encodeURIComponent(r.id)}>{when(r.created)}</a></td><td>v{r.version}</td>
          <td className={"cc-status is-" + r.status}>{r.status}</td><td>{r.device_name ?? ""}</td><td>{r.requested_by}</td></tr>)}</tbody></table> : <p className="cc-none">No runs yet.</p>)}
      {tab === "versions" && <>
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
              <Button kind="plain" onClick={() => { setShownVersion(String(v.version)); setTab("steps"); }}>View steps</Button>
              {(approvalOn ? can("repo.checkin") : can("repo.production")) && v.version !== a.production_version &&
                <Button kind="plain" onClick={() => { setText(""); setAsk({ kind: "live", version: v.version }); }}>{approvalOn ? "Ask to make live" : "Make live"}</Button>}
              {can("repo.checkin") && <Button kind="plain" onClick={() => { setText(""); setAsk({ kind: "label", version: v.version }); }}>Label</Button>}
            </td>
          </tr>))}
          {versions && !versions.length && <tr className="is-static"><td colSpan={7} className="st-muted">No versions.</td></tr>}
        </tbody>
      </table>
      </>}
      </div>
      {diff && <Dialog open title={"v" + diff.a + " to v" + diff.b + " of " + agentName(a.path)} width={860} onClose={() => setDiff(null)} footer={null}>
        <p className="st-muted">{diff.added} line(s) added, {diff.removed} removed.</p>
        <ol className="cc-log" aria-label="Changes">{diff.lines.length ? diff.lines.map(([k, l], i) => <li key={i} className={k === "+" ? "cc-add" : k === "-" ? "cc-del" : undefined}>{k} {l}</li>) : <li>The versions are the same.</li>}</ol>
      </Dialog>}
    </div>
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
  const [people, setPeople] = useState<{ username: string; device_id?: string | null; device_name?: string | null }[] | null>(null);
  const [picked, setPicked] = useState<string[]>([]);
  const [opts, setOpts] = useState({ more: false, mode: "unattended", priority: "0", callback: "" });
  const [vars, setVars] = useState<FlowVariable[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    Promise.all([api.devices().catch(() => []), api.pools().catch(() => [])]).then(([d, p]) => {
      setRunners(d); setPools(p); const first = d.find((r) => r.enabled && r.status === "online") ?? d.find((r) => r.enabled); setWhere(first ? "d:" + first.id : p[0] ? "p:" + p[0].id : "");
    });
    api.agentFlow(a.path, "production").then((r) => setVars((r.flow.variables ?? []).filter((v) => v.direction === "input" || v.direction === "in_out")), (e) => reportMinor("the agent's inputs", e));
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
    const more = { mode: opts.mode, priority: Number(opts.priority), callback_url: opts.callback.trim() || null };
    if (where === "users") {
      if (!picked.length) { setError("Tick at least one run-as user."); return; }
      api.startRunsAs({ bot_path: a.path, run_as_users: picked, inputs, ...more }).then((r) => {
        const bad = r.runs.filter((x) => x.error);
        onDone("Started " + r.started + " of " + r.runs.length + " runs of " + agentName(a.path) + (bad.length ? ": " + bad.map((x) => x.run_as + " (" + x.error!.message + ")").join("; ") : "") + ". Follow them in Agent Activity.");
      }, (e) => setError(message(e)));
      return;
    }
    api.startRun({ bot_path: a.path, ...(where.startsWith("p:") ? { pool_id: where.slice(2) } : { device_id: where.slice(2) }), inputs, ...more })
      .then((r) => onDone("Started " + agentName(a.path) + " (run " + r.run_id + "): follow it in Agent Activity."), (e) => setError(message(e)));
  };
  useEffect(() => { if (where === "users" && people === null) api.defaultRunners().then(setPeople, (e) => { setPeople([]); reportMinor("the run-as users", e); }); }, [where]);   // eslint-disable-line react-hooks/exhaustive-deps
  const missing = vars.some((v) => v.required && !(values[v.name] ?? "").trim());
  return (
    <Dialog open title={"Run " + agentName(a.path)} width={620} onClose={onClose} help={<HelpLinks k="dialog:run" label="Run" />} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!where || missing || (where === "users" && !picked.length)} onClick={run}>Run</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-list-hint">Runs the live version, v{a.production_version}.</p>
      <Field label="Run on">{(id) => <Select id={id} value={where} onChange={(e) => setWhere(e.target.value)} options={[...runners.filter((r) => r.enabled).map((r) => ({ value: "d:" + r.id, label: r.name + " (" + r.status + ")" })),
        ...pools.map((p) => ({ value: "p:" + p.id, label: "Pool " + p.name + " (least busy runner)" })),
        { value: "users", label: "Run-as users (each on their default runner)" }]} />}</Field>
      {where === "users" && <fieldset className="cc-run-as"><legend>Run-as users</legend>
        {people === null ? <p className="st-muted">Loading users…</p> : !people.length ? <p className="st-muted">No run-as users.</p>
          : people.map((p) => <label key={p.username} className="cc-check"><input type="checkbox" disabled={!p.device_id} checked={picked.includes(p.username)}
              onChange={(e) => setPicked(e.target.checked ? [...picked, p.username] : picked.filter((x) => x !== p.username))} />
            <strong>{p.username}</strong> <span className="st-muted">{p.device_id ? "on " + (p.device_name || p.device_id) : "no default runner"}</span></label>)}
      </fieldset>}
      {vars.map((v) => <Field key={v.name} label={v.name + (v.required ? " *" : "") + " (" + v.type + ")"} hint={v.description}>{(id) => v.type === "Boolean"
        ? <Select id={id} value={values[v.name] ?? ""} onChange={(e) => setValues({ ...values, [v.name]: e.target.value })} options={[{ value: "", label: "(not set)" }, { value: "true", label: "True" }, { value: "false", label: "False" }]} />
        : <TextInput id={id} type={v.type === "Number" ? "number" : "text"} value={values[v.name] ?? ""} placeholder={v.type === "Credential" ? "vault folder/credential/attribute" : v.default !== undefined ? "Default " + JSON.stringify(v.default) : ""}
          onChange={(e) => setValues({ ...values, [v.name]: e.target.value })} />}</Field>)}
      {!vars.length && <p className="st-muted">This agent has no inputs.</p>}
      <details className="cc-run-more" open={opts.more} onToggle={(e) => setOpts({ ...opts, more: (e.target as HTMLDetailsElement).open })}>
        <summary>Run mode, priority and result callback (optional)</summary>
        <Field label="Run mode">{(id) => <Select id={id} value={opts.mode} onChange={(e) => setOpts({ ...opts, mode: e.target.value })}
          options={[{ value: "unattended", label: "Unattended (nobody at the runner)" }, { value: "attended", label: "Attended (someone is at the runner)" }, { value: "headless", label: "Headless (no desktop)" }]} />}</Field>
        <Field label="Priority on the runner">{(id) => <Select id={id} value={opts.priority} onChange={(e) => setOpts({ ...opts, priority: e.target.value })}
          options={[{ value: "-5", label: "Low" }, { value: "0", label: "Normal" }, { value: "5", label: "High" }, { value: "9", label: "Urgent" }]} />}</Field>
        <Field label="Callback URL" hint="Gets the result as JSON when the run ends.">{(id) => <TextInput id={id} type="url" value={opts.callback} placeholder="https://example.com/hooks/agent-finished"
          onChange={(e) => setOpts({ ...opts, callback: e.target.value })} />}</Field>
      </details>
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
      <p className="cc-list-hint">Everything in it moves too: agents with their versions, labels, schedules, triggers and run history.</p>
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
    api.roles().then((r) => setRoles(r.map((x) => x.name)), (e) => reportMinor("the list of roles", e));
  }, [api, folder]);
  const label = (p: string) => p === "role:*" ? "Everyone" : p.startsWith("role:") ? "Role " + p.slice(5) : p.startsWith("team:") ? "Team " + p.slice(5) : "User " + p.slice(5);
  const plus = () => { const pr = add.user.trim() ? "user:" + add.user.trim() : add.who; if (!rows!.some((r) => r.principal === pr)) setRows([...rows!, { principal: pr, access: add.access }]); setAdd({ ...add, user: "" }); };
  return (
    <Dialog open title={"Permissions of " + folder} width={680} onClose={onClose} help={<HelpLinks k="dialog:folder-permissions" label="Folder permissions" />} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!rows} onClick={() => api.saveFolderPermissions(folder, rows!).then(() => onDone("Saved the permissions of " + folder + "."), (e) => setError(message(e)))}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-list-hint">With permissions of its own, only those listed see the folder's agents (and its sub-folders', unless one has its own). Roles still apply: a folder never grants what a role does not allow.</p>
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
    <Dialog open title="Production approvals" width={860} onClose={onClose} help={<HelpLinks k="dialog:production-approvals" label="Production approvals" />} footer={null}>
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
