import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { AgentTemplate, CrApi, CrUser, TemplateProcessResult } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Menu, Select, SidePanel, TextInput, usePersistent, type HelpTopic, reportMinor, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick, CloseView } from "./ListParts";
import { FolderIcon } from "./Repository";
import { PageIntro } from "../shell/PageIntro";
import { PageTabs, PageTitle } from "../shell/PageFrame";

/**
 * Manage > Platform > Templates (review round, 26 Nov 2026; until then Build > Agent Templates), built as AA's Bot Store
 * and UiPath's Marketplace (checked 31 Oct 2026): the administrators' template library. Ready-made agents from
 * ONEOFFICEAUTOMATION and, where the service allows, shared in your region; Install into a folder (agents already
 * there are kept); a signed catalogue file for One CommandCenter without internet access. Developers use templates from
 * Create > Start from > Template and share theirs with "Share as template..." on an agent's row menu in Build > Agents.
 * Laid out exactly as Automation (review round 2, 31 Oct 2026): a category tree on the left, the templates in one
 * table on the right (installed or not, and where), and a template's own page when you open one. Administrators make,
 * rename and delete folders (nested, as in Automation) and move templates between them; the catalogue's categories are
 * the first folders.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const parentOf = (f: string) => f.includes("/") ? f.slice(0, f.lastIndexOf("/")) : "";
const under = (f: string, p: string) => !p || f === p || f.startsWith(p + "/");
const keyOf = (t: AgentTemplate) => t.source + ":" + t.id;
/** A folder path with the folders above it, top first: "A/B" gives A, A/B. */
const withParents = (f: string) => f.split("/").map((_, i, a) => a.slice(0, i + 1).join("/"));

const HELP: HelpTopic = { title: "Templates",
  about: "The template library: ready-made agents from ONEOFFICEAUTOMATION's template catalogue and, where the service allows, shared by others in "
    + "your region. Developers start from them in Create > Start from > Template and share their own with Share as template... on an agent's menu "
    + "in Build > Agents.",
  items: [
    { name: "Folders", text: "Pick a folder to list its templates; All templates shows every one. The catalogue's categories are the first folders." },
    { name: "Status", text: "Show only templates already installed in Agents, or only those not installed yet." },
    { name: "Installed in", text: "The folders in Agents that already hold this template's agents." },
    { name: "Install…", text: "Puts the template's agents into a folder of Agents as new agents you can change. Agents already there are kept as they "
      + "are. Needs permission to import." },
    { name: "Open in Agents", text: "For an installed template: shows its agents on the Agents page." },
    { name: "Move to folder…", text: "Shows the template in another folder here. It does not change where its agents go when you install it. For "
      + "administrators." },
    { name: "Create", text: "New folder makes a folder here, inside the chosen one; the folder's ⋯ menu renames, moves or deletes it when it is empty. For "
      + "administrators." },
    { name: "Upload a catalogue file", text: "In Manage: for a One CommandCenter without internet access, upload the signed templates.json from the "
      + "releases page. Needs permission to change settings." },
  ] };

const HELP_TEMPLATE: HelpTopic = { title: "Agent template",
  about: "One template: what it does, the agents inside it and where they are already installed.",
  items: [
    { name: "What is inside", text: "The agents the template installs, and the folders in Agents where each is already." },
    { name: "Install", text: "Choose a folder; the agents go there as new agents you can change. Install again into… puts another copy in a different "
      + "folder." },
    { name: "Open in Agents", text: "Shows the installed agents on the Agents page." },
    { name: "Move to folder…", text: "Shows the template in another folder of Templates. For administrators." },
  ] };

export function Templates({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [data, setData] = useState<Awaited<ReturnType<CrApi["templates"]>> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [cat, setCat] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "install" | "move"; t: AgentTemplate } | { kind: "catalogue" | "rename" } | { kind: "folder"; parent: string }>(null);
  const [opened, setOpened] = useState<string[]>([]);
  const [foldersHidden, setFoldersHidden] = usePersistent("panel.templates.folders.hidden", false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [open, setOpen] = useState<AgentTemplate | null>(null);
  const [state, setState] = useState("");
  const [repo, setRepo] = useState<string[]>([]);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => {
    try { setData(await api.templates()); setError(null); } catch (e) { setError(message(e)); }
    if (can("repo.view")) api.repo().then((a) => setRepo(a.map((x) => x.path)), (e) => reportMinor("the agents in the repository", e));
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [load]);
  /** Where a template's agents are in the repository already (by the agents' file names): its folders, or none. */
  const installedIn = useCallback((t: AgentTemplate) => [...new Set(repo.filter((p) => t.agents.some((a) => p === a || p.endsWith("/" + a.split("/").pop()))).map((p) => p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "(top)"))], [repo]);
  const shown = useMemo(() => (data?.templates ?? []).filter((t) => under(t.folder ?? t.category, cat)
    && (!q.trim() || (t.name + " " + (t.description ?? "") + " " + t.publisher).toLowerCase().includes(q.trim().toLowerCase()))
    && (!state || (state === "installed") === installedIn(t).length > 0)), [data, q, cat, state, installedIn]);
  const all = data?.templates ?? [];
  const folders = data?.categories ?? [];
  const folderOfT = (t: AgentTemplate) => t.folder ?? t.category;
  const installed = all.filter((t) => installedIn(t).length).length;
  const arrangeable = !!data?.can_arrange;
  /** Saves the folders and where every template sits; moves[key] puts a template in another folder. */
  const arrange = (next: string[], moves: Record<string, string>, notice: string, then?: () => void) => {
    const placed: Record<string, string> = {};
    for (const t of all) placed[keyOf(t)] = moves[keyOf(t)] ?? folderOfT(t);
    return api.saveTemplateFolders(next, placed).then(() => { onNotice(notice); setError(null); then?.(); load(); }, (e) => { setError(message(e)); throw e; });
  };
  const addFolder = (path: string) => arrange([...folders, ...withParents(path).filter((f) => !folders.includes(f))], {}, "Made the folder " + path + ".", () => setCat(path));
  const renameFolder = (from: string, to: string) => {
    const move = (f: string) => under(f, from) ? to + f.slice(from.length) : f;
    const next = [...new Set([...withParents(parentOf(to)).filter((f) => f && !folders.includes(f)), ...folders.map(move)])];
    const moves: Record<string, string> = {};
    for (const t of all) if (under(folderOfT(t), from)) moves[keyOf(t)] = move(folderOfT(t));
    return arrange(next, moves, "Renamed " + from + " to " + to + ".", () => setCat(to));
  };
  const deleteFolder = (f: string) => arrange(folders.filter((x) => !under(x, f)), {}, "Deleted the folder " + f + ".", () => { setConfirmDelete(false); setCat(parentOf(f)); });
  const kids = (p: string) => folders.filter((f) => parentOf(f) === p);
  const total = (p: string) => all.filter((t) => under(folderOfT(t), p)).length;
  const isOpen = (p: string) => opened.includes(p) || cat.startsWith(p + "/");
  const node = (f: string, depth: number): ReactNode => {
    const children = kids(f), openNow = isOpen(f), name = f.split("/").pop(), n = total(f);
    return <li key={f}>
      <div className={"cc-tree-row" + (cat === f ? " is-on" : "")} style={{ paddingLeft: 6 + 16 * depth }}>
        {children.length ? <button type="button" className="cc-tree-chev" aria-expanded={openNow} aria-label={(openNow ? "Close " : "Open ") + name} onClick={() => setOpened(opened.includes(f) ? opened.filter((x) => x !== f) : [...opened, f])}>{openNow ? "▾" : "▸"}</button> : <span className="cc-tree-chev" />}
        <button type="button" className="cc-tree-name" aria-current={cat === f ? "page" : undefined} onClick={() => setCat(f)}>
          <FolderIcon open={openNow && children.length > 0} /><span className={n ? undefined : "cc-none"}>{name}</span><span className="cc-tree-n">{n}</span></button>
      </div>
      {openNow && children.length > 0 && <ul>{children.map((c) => node(c, depth + 1))}</ul>}
    </li>;
  };
  const crumbs = cat ? cat.split("/") : [];
  const subfolders = q.trim() || state ? [] : kids(cat);
  const create = arrangeable ? [{ key: "folder", label: "New folder" + (cat ? " in " + cat.split("/").pop() : ""), onSelect: () => setDialog({ kind: "folder", parent: cat }) }] : [];
  const folderMenu = arrangeable && cat ? [{ key: "sub", label: "New subfolder", onSelect: () => setDialog({ kind: "folder", parent: cat }) },
    { key: "rename", label: "Rename or move folder", onSelect: () => setDialog({ kind: "rename" }) },
    ...(total(cat) === 0 ? [{ key: "delete", label: "Delete folder…", separatorBefore: true, onSelect: () => setConfirmDelete(true) }] : [])] : [];
  const manage = [...(can("settings.manage") ? [{ key: "catalogue", label: "Upload a catalogue file", onSelect: () => setDialog({ kind: "catalogue" }) }] : [])];
  
  const openIn = (t: AgentTemplate) => "#/automation?q=" + encodeURIComponent(agentName(t.agents[0] ?? t.name));
  const rowMenu = (t: AgentTemplate) => [
    { key: "open", label: "Open", onSelect: () => setOpen(t) },
    ...(arrangeable ? [{ key: "move", label: "Move to folder…", onSelect: () => setDialog({ kind: "move", t }) }] : []),
    ...(can("lifecycle.import") ? [{ key: "install", label: installedIn(t).length ? "Install again into…" : "Install…", onSelect: () => setDialog({ kind: "install", t }) }] : []),
    ...(installedIn(t).length ? [{ key: "automation", label: "Open in Agents", onSelect: () => { location.hash = openIn(t).slice(1); } }] : []),
  ];

  if (open) {   // a template's own page, in place of the panes, as an agent opens in Automation
    const where = installedIn(open);
    return (
      <section className="st-page cc-home cc-explorer" aria-label={"Templates: " + open.name}>
        <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"About " + open.name}>
          <div className="cc-agent-head">
            <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={() => { setOpen(null); setCat(""); }}>Templates</button> ›{" "}
              {folderOfT(open).split("/").filter(Boolean).map((c, i, a) => <span key={i}><button type="button" className="cc-link" onClick={() => { setOpen(null); setCat(a.slice(0, i + 1).join("/")); }}>{c}</button> › </span>)}</span>
            <h1>{open.name}</h1>
            <HelpButton topic={HELP_TEMPLATE} />
            {where.length ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Installed</span> : <span className="cc-pill is-stopped"><span className="cc-pill-dot" />Not installed</span>}
            <span className="cc-none">{open.source === "region" ? "Shared by " + open.publisher : open.publisher}</span>
            <span className="ov-fill" />
            {arrangeable && <Button kind="plain" onClick={() => setDialog({ kind: "move", t: open })}>Move to folder…</Button>}
            {where.length > 0 && <a className="ui-btn ui-btn-secondary cc-btn-link" href={openIn(open)}>Open in Agents</a>}
            {can("lifecycle.import") && <Button kind="primary" onClick={() => setDialog({ kind: "install", t: open })}>{where.length ? "Install again into…" : "Install"}</Button>}
            <CloseView onClick={() => setOpen(null)} to="the templates" />
          </div>
          <div className="cc-agent-tab">
            {open.description && <p>{open.description}</p>}
            <h3 className="cc-au-sec">What is inside</h3>
            <table className="cc-list-table" aria-label="Agents in this template">
              <thead><tr><th scope="col">Agent</th><th scope="col">In Agents</th></tr></thead>
              <tbody>{open.agents.map((a) => { const at = repo.filter((p) => p === a || p.endsWith("/" + a.split("/").pop()));
                return <tr key={a} className="is-static"><td><span className="cc-aico" aria-hidden="true">A</span><strong>{agentName(a)}</strong></td>
                  <td>{at.length ? at.map((p) => p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "(top)").join(", ") : <span className="cc-none">Not installed</span>}</td></tr>; })}</tbody>
            </table>
            {!!open.processes?.length && <p>Document Automation processes: {open.processes.join(", ")} (installed as drafts, in the same folder).</p>}
            <p className="cc-none">Installing puts the template's agents into the folder you choose, as new agents you can change. Agents already there are kept as they are.</p>
          </div>
        </div>
        {dialog?.kind === "install" && <Install api={api} t={dialog.t} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); load(); }} />}
        {dialog?.kind === "move" && <MoveTemplate t={dialog.t} at={folderOfT(dialog.t)} folders={folders} onClose={() => setDialog(null)}
          onMove={(to) => arrange(folders, { [keyOf(dialog.t)]: to }, "Moved " + dialog.t.name + " to " + (to || "the top") + ".", () => { setDialog(null); setOpen({ ...dialog.t, folder: to }); })} />}
      </section>
    );
  }

  return (
    <section className="st-page cc-home cc-explorer" aria-labelledby="cc-tpl-title">
      <header className="cc-list-head">
        <PageTitle id="cc-tpl-title" name="Templates" />
        <HelpButton topic={HELP} /><PageIntro k="page:templates" />
        {data && <span className="cc-list-count">{all.length} template{all.length === 1 ? "" : "s"}{repo.length ? " · " + installed + " installed" : ""} · {folders.length} folder{folders.length === 1 ? "" : "s"}</span>}
        <span className="ov-fill" />
        {manage.length > 0 && <Menu label="Manage" align="end" items={manage} trigger={<button type="button" className="ui-btn ui-btn-secondary" aria-label="Manage">Manage ▾</button>} />}
        {create.length > 0 && <Menu label="Create" align="end" items={create} trigger={<button type="button" className="ui-btn ui-btn-primary" aria-label="Create">Create ▾</button>} />}
      </header><PageTabs />
      {error && <PageError text={error} />}
      {data?.problem && <p className="st-alert">{data.problem}</p>}
      <div className="cc-explorer-body">
        {/* fix list 27 Nov, item 4: the folder pane folds to a strip, as Studio's (remembered) */}
        <SidePanel as="nav" title="Folders" side="left" collapsed={foldersHidden} onToggle={() => setFoldersHidden(!foldersHidden)} className="cc-tree-pane" actions={<button type="button" className="cc-link" onClick={() => setOpened(folders.filter((f) => kids(f).length))}>Open all</button>}>
          <ul className="cc-tree">
            <li><div className={"cc-tree-row" + (cat === "" ? " is-on" : "")} style={{ paddingLeft: 6 }}><span className="cc-tree-chev" />
              <button type="button" className="cc-tree-name" aria-current={cat === "" ? "page" : undefined} onClick={() => setCat("")}><FolderIcon open />All templates<span className="cc-tree-n">{all.length}</span></button></div>
              <ul>{kids("").map((f) => node(f, 1))}</ul></li>
          </ul>
          <p className="cc-pane-foot">From ONEOFFICEAUTOMATION's template catalogue{data?.can_share ? " and templates shared in your region" : ""}.{arrangeable ? " Arrange them in your own folders: Create > New folder, and a template's Move to folder." : ""}</p>
        </SidePanel>
        <div className="cc-files-pane">
          <div className="cc-pane-head">
            <span className="cc-crumbs-path"><FolderIcon open /><button type="button" className="cc-link" onClick={() => setCat("")}>Templates</button>
              {crumbs.map((c, i) => <span key={i}> › {i === crumbs.length - 1 ? <strong>{c}</strong> : <button type="button" className="cc-link" onClick={() => setCat(crumbs.slice(0, i + 1).join("/"))}>{c}</button>}</span>)}</span>
            <span className="ov-fill" />
            {folderMenu.length > 0 && <RowMenu label={"Actions for the folder " + cat} items={folderMenu} />}
          </div>
          {confirmDelete && <div className="cc-list-bulk" role="alert"><span>Delete the empty folder {cat}{kids(cat).length ? " and the empty folders in it" : ""}?</span>
            <Button kind="danger" onClick={() => deleteFolder(cat).catch(() => {})}>Delete</Button>
            <Button kind="plain" onClick={() => setConfirmDelete(false)}>Cancel</Button></div>}
          <div className="cc-list-tools cc-pane-tools">
            <div className="cc-list-filters" role="group" aria-label="Filters">
              <span className="cc-list-search"><TextInput type="search" aria-label="Search templates" placeholder={"Search in " + (cat ? cat.split("/").pop() : "Templates")} value={q} onChange={(e) => setQ(e.target.value)} /></span>
              <span className={"cc-chip" + (state ? " is-on" : "")}><Select aria-label="Status" value={state} onChange={(e) => setState(e.target.value)}
                options={[{ value: "", label: "Status: Any" }, { value: "installed", label: "Status: Installed" }, { value: "not", label: "Status: Not installed yet" }]} /></span>
              {(q.trim() || state) && <Button kind="plain" onClick={() => { setQ(""); setState(""); }}>Clear filters</Button>}
            </div>
            <div className="cc-list-right" role="toolbar" aria-label="Templates list tools"><RefreshButton onClick={load} /></div>
          </div>
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label="Templates">
              <thead><tr><th scope="col">Name</th><th scope="col">Folder</th><th scope="col">Category</th><th scope="col">Published by</th><th scope="col" className="cc-num">Agents</th><th scope="col">Status</th><th scope="col">Installed in</th>
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>
                {subfolders.map((f) => (
                  <tr key={"f:" + f} className="cc-folder-row" onClick={rowClick(() => setCat(f))}>
                    <td><FolderIcon /><strong>{f.split("/").pop()}</strong></td><td>{parentOf(f) || <span className="cc-none">(top)</span>}</td><td colSpan={2}>Folder · {total(f)} template{total(f) === 1 ? "" : "s"}</td>
                    <td colSpan={3}><span className="cc-none">–</span></td>
                    <td className="cc-list-menu">{arrangeable && <RowMenu label={"Actions for the folder " + f} items={[{ key: "open", label: "Open", onSelect: () => setCat(f) },
                      { key: "rename", label: "Rename or move folder", onSelect: () => { setCat(f); setDialog({ kind: "rename" }); } }]} />}</td>
                  </tr>))}
                {shown.map((t) => { const where = installedIn(t); return (
                  <tr key={t.source + t.id} onClick={rowClick(() => setOpen(t))}>
                    <td title={t.description}><span className="cc-aico is-template" aria-hidden="true">T</span><strong>{t.name}</strong></td>
                    <td>{folderOfT(t) || <span className="cc-none">(top)</span>}</td><td>{t.category}</td><td>{t.source === "region" ? "Shared by " + t.publisher : t.publisher}</td><td className="cc-num">{t.agents.length}</td>
                    <td>{where.length ? <span className="cc-pill is-active"><span className="cc-pill-dot" />Installed</span> : <span className="cc-none">Not installed</span>}</td>
                    <td>{where.length ? where.join(", ") : <span className="cc-none">–</span>}</td>
                    <td className="cc-list-menu"><RowMenu label={"Actions for " + t.name} items={rowMenu(t)} /></td>
                  </tr>); })}
                {data && !shown.length && !subfolders.length && <tr className="is-static"><td colSpan={8} className="cc-none">{all.length ? (q.trim() || state ? "No template matches." : "No templates in this folder.") : "No templates to show." + (can("settings.manage")
                  ? " Set the address of a template catalogue in Settings (General), or use Manage > Upload a catalogue file for One CommandCenter without internet access." : " An administrator sets up the template catalogue in Settings.")}</td></tr>}
                {!data && !error && <tr className="is-static"><td colSpan={8} className="cc-none" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          {data && <div className="cc-list-pager"><span>{subfolders.length ? subfolders.length + " folder" + (subfolders.length === 1 ? "" : "s") + " · " : ""}{shown.length} of {all.length} template{all.length === 1 ? "" : "s"}{q.trim() || state ? " matching" : ""}</span>
            {!all.length && can("settings.manage") && <><span className="ov-fill" /><a className="ui-btn ui-btn-secondary cc-btn-link" href="#/settings">Open Settings</a></>}</div>}
        </div>
      </div>
      {dialog?.kind === "install" && <Install api={api} t={dialog.t} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); load(); }} />}
      {dialog?.kind === "move" && <MoveTemplate t={dialog.t} at={folderOfT(dialog.t)} folders={folders} onClose={() => setDialog(null)}
        onMove={(to) => arrange(folders, { [keyOf(dialog.t)]: to }, "Moved " + dialog.t.name + " to " + (to || "the top") + ".", () => setDialog(null))} />}
      {dialog?.kind === "folder" && <FolderName title="New folder" label="Folder (a folder inside another with /)" initial={dialog.parent ? dialog.parent + "/" : ""} taken={folders} action="Create folder"
        onClose={() => setDialog(null)} onSave={(f) => addFolder(f).then(() => setDialog(null))} />}
      {dialog?.kind === "rename" && <FolderName title={"Rename or move " + cat} label="New path" initial={cat} taken={folders} action="Rename" notInside={cat}
        hint="The folders and templates in it move too." onClose={() => setDialog(null)} onSave={(f) => renameFolder(cat, f).then(() => setDialog(null))} />}
      {dialog?.kind === "catalogue" && <Catalogue api={api} onClose={() => setDialog(null)} onDone={(m) => { onNotice(m); setDialog(null); load(); }} />}
    </section>
  );
}

function Install({ api, t, onClose, onDone }: { api: CrApi; t: AgentTemplate; onClose: () => void; onDone: (m: string) => void }) {
  const [folder, setFolder] = useState("Templates");
  const [results, setResults] = useState<Awaited<ReturnType<CrApi["installTemplate"]>>["results"] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [processes, setProcesses] = useState<TemplateProcessResult[] | null>(null);
  const install = () => api.installTemplate(t.source, t.id, folder.trim() || "Templates").then((r) => {
    if (r.ok && r.processes_ok !== false && !r.processes?.some((p) => p.note)) onDone("Installed " + t.name + " in " + r.folder + ": open it in Agents."
      + (r.processes?.length ? " Its document processes are drafts in Document Automation." : ""));
    else if (r.ok) setProcesses(r.processes ?? []);
    else setResults(r.results);
  }, (e) => setError(message(e)));
  return (
    <Dialog open title={"Install " + t.name} width={600} onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" onClick={install}>Install</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">The template's agents go into the agent repository under this folder, as new agents you can change. Agents already there are kept as they are.</p>
      <Field label="Folder">{(id) => <TextInput id={id} value={folder} onChange={(e) => setFolder(e.target.value)} />}</Field>
      {!!t.processes?.length && <p className="st-muted">It also creates {t.processes.length === 1 ? "a Document Automation process" : t.processes.length + " Document Automation processes"} ({t.processes.join(", ")}) as drafts in the same folder; one already there is kept. You need permission to change Document Automation.</p>}
      {processes && <><p className="st-alert">The agents were installed. Document processes:</p>
        <table className="st-table" aria-label="Document processes"><tbody>{processes.map((x) => <tr key={x.name}><td>{x.name}</td><td>{x.status}</td><td>{x.error ?? x.note ?? ""}</td></tr>)}</tbody></table></>}
      {results && <><p className="st-alert">Nothing was installed: fix the problems listed.</p>
        <table className="st-table" aria-label="Install results"><tbody>{results.map((x) => <tr key={x.path}><td>{agentName(x.path)}</td><td>{x.status}</td><td>{x.error?.message ?? ""}</td></tr>)}</tbody></table></>}
    </Dialog>
  );
}

/** A folder's name or path: a new folder, or a folder renamed or moved (never into itself). */
function FolderName({ title, label, initial, taken, action, notInside, hint, onClose, onSave }: { title: string; label: string; initial: string; taken: string[]; action: string;
  notInside?: string; hint?: string; onClose: () => void; onSave: (f: string) => Promise<unknown> }) {
  const [v, setV] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const f = v.trim().replace(/\s*\/\s*/g, "/").replace(/^\/+|\/+$/g, "");
  const problem = !f ? "" : /[\\<>:"|?*]/.test(f) || f.includes("//") ? "A folder name has no \\ < > : \" | ? *" : f.length > 120 ? "At most 120 characters"
    : f !== notInside && taken.includes(f) ? "There is a folder " + f + " already" : notInside && under(f, notInside) && f !== notInside ? "A folder cannot go inside itself" : "";
  return (
    <Dialog open title={title} onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!f || !!problem || f === notInside} onClick={() => onSave(f).catch((e) => setError(message(e)))}>{action}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label={label}>{(id) => <TextInput id={id} value={v} placeholder="Finance/Invoices" onChange={(e) => setV(e.target.value)} />}</Field>
      {problem && <p className="st-alert">{problem}</p>}
      {hint && <p className="cc-list-hint">{hint}</p>}
    </Dialog>
  );
}

/** Puts a template in another folder (or at the top). */
function MoveTemplate({ t, at, folders, onClose, onMove }: { t: AgentTemplate; at: string; folders: string[]; onClose: () => void; onMove: (to: string) => Promise<unknown> }) {
  const [to, setTo] = useState(at);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title={"Move " + t.name} onClose={onClose} footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={to === at} onClick={() => onMove(to).catch((e) => setError(message(e)))}>Move</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="To the folder">{(id) => <Select id={id} value={to} onChange={(e) => setTo(e.target.value)}
        options={[{ value: "", label: "(top: Templates)" }, ...folders.map((f) => ({ value: f, label: "\u00a0\u00a0\u00a0".repeat(f.split("/").length - 1) + f.split("/").pop() }))]} />}</Field>
      <p className="cc-list-hint">Only where it shows here changes: installing still puts its agents in the folder you choose in Agents.</p>
    </Dialog>
  );
}

function Catalogue({ api, onClose, onDone }: { api: CrApi; onClose: () => void; onDone: (m: string) => void }) {
  const [text, setText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title="Upload a template catalogue" width={540} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!text} onClick={() => api.uploadCatalogue(text!).then((r) => onDone(r.templates + " templates available."), (e) => setError(message(e)))}>Upload</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">For One CommandCenter without internet access: download templates.json from the releases page and upload it here. It must carry ONEOFFICEAUTOMATION's signature.</p>
      <input type="file" accept=".json,application/json" aria-label="Catalogue file" onChange={async (e) => { const f = e.target.files?.[0]; setText(f ? await f.text() : null); }} />
    </Dialog>
  );
}
