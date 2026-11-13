import { useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import type { Blueprint, NewItemKind, RepoState, StudioApi, WorkspaceNode } from "../api/client";
import { Button, Dialog, Field, HelpButton, Icon, IconTile, is, keyOf, Menu, Select, SidePanel, Splitter, TextInput, Tree, usePanelSize, usePersistent, type MenuItem, type TreeNode } from "../components";
import { AUTOMATION_HELP, PUBLIC_HELP } from "./studioHelp";
import { Analysis, ColumnsDialog, labelText, sizeText, type Column } from "./columns";
import { CreateDialog } from "./CreateDialog";
import type { RepoRequest } from "./ControlRoom";
import type { StudioView } from "./CommandMenu";
import { ITEM_TYPES, displayName, typeLabel, type ItemType } from "./itemTypes";

const VIEW_TITLE: Record<StudioView, [string, (n: WorkspaceNode) => boolean]> = {
  automation: ["Automation", (n) => n.type !== "folder"],
};

/** The top folder's name on both tabs (improvement list 13 Nov 2026, item 2: one name, as Public's). */
export const ROOT = "Automation";
const folderTree = (n: WorkspaceNode): TreeNode => ({ id: n.path, label: n.name, children: (n.children ?? []).filter((c) => c.type === "folder").map(folderTree) });

/** What opens in an editor here: agents and the other items; data files stay as they are. */
const OPENS: WorkspaceNode["type"][] = ["bot", "form", "process", "document", "objects", "skill", "script"];

const everything = (n: WorkspaceNode): WorkspaceNode[] => (n.children ?? []).flatMap((c) => [c, ...everything(c)]);

export function find(n: WorkspaceNode, path: string): WorkspaceNode | null {
  if (n.path === path) return n;
  for (const c of n.children ?? []) { const f = find(c, path); if (f) return f; }
  return null;
}

const parentOf = (path: string) => path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
const PATHS_DRAG = "application/x-ooa-paths";

/** What a found agent matched, in words: "description", "uses Open workbook"... */
const foundBy = (m: string[]) => m.map((x) => x.replace(/^uses [^/]+\/(.+)$/, (_s, a: string) => "uses " + a.replace(/_/g, " "))).join(", ");

/**
 * The Automation page, in AA's order: pick a folder, press Create, choose the type, then name it. The table lists the
 * folder's sub-folders and items; they can be selected (Ctrl+click, Shift+click), opened, renamed, copied, cut and
 * pasted, duplicated, dragged onto a folder to move them, and moved to the trash, from the ⋮ menu, a right-click or
 * the keyboard. A search finds items by name, and agents by their description, id, actions and variables too.
 * As in AA, Private is the workspace and Public is One CommandCenter agent repository (publicView).
 * Review round 2 (1 Nov 2026), in the console's look: the title with what the page is for on hover and a count, a Type
 * filter, one line per item with its kind's mark, a tick box per item, and Copy, Cut, Duplicate, Rename, Delete and
 * Paste in a bar only while items are ticked or copied (no greyed-out buttons).
 */
/** Each kind's mark, as on Create. */
const markOf = (n: WorkspaceNode) => ITEM_TYPES.find((t) => n.type === "bot" ? t.kind === (n.kind ?? "TaskBot") : t.kind === (n.type === "script" ? (/\.ps1$/i.test(n.name) ? "script-powershell" : "script-python") : n.type));
/** Check-in and check-out from the Private list (1 Nov 2026, as AA's Private workspace): each item's status and its actions. */
export type RepoHooks = { states: Record<string, RepoState>; onCheckIn: (paths: string[]) => void; onUndo: (local: string, repoPath: string) => void;
  onRevert: (local: string) => void };
/** What can be checked in: agents, workflows and forms. */
const CHECKS_IN = ["bot", "process", "form"];
const STATUS: Record<RepoState["status"], [string, string]> = {
  new: ["New", "Not in the repository yet: Check in puts it there, in the same folder."],
  linked: ["In the repository", "A copy of an agent in the repository. Sign in to One CommandCenter to see whether it is checked out."],
  checked_out: ["Checked out", "Checked out to you, unchanged since."],
  checked_out_edited: ["Checked out · edited", "Checked out to you, with changes not checked in yet."],
  checked_in: ["Checked in", "The same as in the repository; not checked out. Check it out on the Public tab to change it."],
  edited: ["Edited, not checked out", "Changed, but not checked out: check it out on the Public tab (your changes are kept), then check it in."],
  cloned: ["Cloned", "A read-only copy of an agent in the repository; it follows the repository. Check it out on the Public tab to change it, or copy it to Private."],
};
/** The Private list's columns after the name, as AA's; the repository's ones show while signed in to One CommandCenter. */
const COLUMNS: Column[] = [{ key: "type", label: "Type" }, { key: "status", label: "Status" }, { key: "platform", label: "Platform" }, { key: "version", label: "Version" },
  { key: "label", label: "Label" }, { key: "analysis", label: "Code analysis" }, { key: "size", label: "Size" }, { key: "changed", label: "Last modified" },
  { key: "by", label: "Modified by" }];
const FROM_REPO = ["status", "version", "label", "analysis", "by"];
const CLASS: Record<string, string> = { type: "st-col-type", changed: "st-col-changed" };
const KINDS: [string, string][] = [["", "Type: Any"], ["folder", "Folders"], ["bot", "Agents"], ["process", "Workflows"], ["form", "Forms"], ["document", "Document types"], ["skill", "Prompt Skills"], ["script", "Scripts"], ["file", "Other files"]];
export function Automation({ api, view, tree, search = "", blueprints = [], publicView, repo, onRepoLink, onOpen, onCreated, onNewFolder, onDelete, onMoved, onChanged, onNotice, onError }:
  { api: StudioApi; view: StudioView; tree: WorkspaceNode; search?: string; blueprints?: Blueprint[]; publicView?: ReactNode; repo?: RepoHooks;
    onRepoLink?: (r: RepoRequest) => void; onOpen: (n: WorkspaceNode) => void;
    onDelete: (nodes: WorkspaceNode[]) => void; onCreated: (kind: NewItemKind, parent: string, name: string, from: Blueprint | null) => Promise<void>;
    onNewFolder: (parent: string) => void; onMoved: (from: string, to: string) => void; onChanged: () => Promise<unknown>;
    onNotice: (text: string) => void; onError: (e: unknown) => void }) {
  const [folder, setFolder] = useState("");
  const [expanded, setExpanded] = useState(() => new Set([""]));
  const [creating, setCreating] = useState<ItemType | null>(null);
  const [createIn, setCreateIn] = useState("");   // the folder a new item goes in: the open folder, or one chosen in the dialog
  const [foldersHidden, setFoldersHidden] = usePersistent("panel.folders.hidden", false);
  const [hiddenCols, setHiddenCols] = usePersistent<string[]>("automation.private.columns.hidden", ["platform", "label", "analysis", "size", "by"]);
  const [choosingCols, setChoosingCols] = useState(false);
  const [foldersWidth, setFoldersWidth] = usePanelSize("folders");
  const foldersPanel = useRef<HTMLElement | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [anchor, setAnchor] = useState<string | null>(null);
  const [clip, setClip] = useState<{ paths: string[]; cut: boolean } | null>(null);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [folderMenu, setFolderMenu] = useState(false);
  const [renaming, setRenaming] = useState<WorkspaceNode | null>(null);
  const uploadInput = useRef<HTMLInputElement | null>(null);
  const uploadTo = useRef("");
  const [found, setFound] = useState<{ path: string; matches: string[] }[]>([]);
  const [kind, setKind] = useState("");   // Type filter
  const [place, setPlace] = usePersistent<"private" | "public">("automation.place", "private");
  // the console's Automation page opens this page on its Private tab (../studio/#private; #private/new: Create > Agent), as AA's one Automation page
  // #public/checkout/<agent path>, #public/clone/<path> and #public/copy/<path>: the console agent page's Edit, Clone and Copy to Private
  useEffect(() => {
    const [h, act, ...rest] = location.hash.replace("#", "").split("/");
    if (h !== "private" && h !== "public") return;
    setPlace(h);
    if (h === "private" && act === "new") { setCreateIn(folder); setCreating(ITEM_TYPES.find((t) => t.kind === "TaskBot") ?? null); }
    if (h === "public" && (act === "checkout" || act === "clone" || act === "copy") && rest.length) onRepoLink?.({ act, path: decodeURIComponent(rest.join("/")) });
    history.replaceState(null, "", location.pathname);
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps
  const isPublic = place === "public" && !!publicView && !search.trim();   // a search looks in the workspace
  const [title, keep] = VIEW_TITLE[view];
  const folderNode = find(tree, folder) ?? tree;
  const folderName = folderNode.path ? folderNode.name : ROOT;
  const nodes = useMemo(() => [{ ...folderTree(tree), label: ROOT }], [tree]);
  const q = search.trim().toLowerCase();
  // a search also asks the server, which reads the agents' descriptions, actions and variables
  useEffect(() => {
    if (q.length < 2) { setFound([]); return; }
    const t = setTimeout(() => { api.search(q).then(setFound, () => setFound([])); }, 250);
    return () => clearTimeout(t);
  }, [api, q]);
  // with a search, every matching item in the workspace; otherwise the chosen folder's items. Improvement list 13 Nov 2026,
  // item 2 (option A): the tree chooses the folder, so its sub-folders show once, in the tree, not again as rows
  const rows: { node: WorkspaceNode; why?: string }[] = q
    ? [...everything(tree).filter((n) => keep(n) && n.name.toLowerCase().includes(q) && !found.some((f) => f.path === n.path)).map((node) => ({ node, why: "name" })),
       ...found.flatMap((f) => { const node = find(tree, f.path); return node ? [{ node, why: foundBy(f.matches) }] : []; })]
    : (folderNode.children ?? []).filter((c) => c.type !== "folder" && keep(c)).map((node) => ({ node }));
  const listed = kind ? rows.filter((r) => r.node.type === kind) : rows;
  const paths = listed.map((r) => r.node.path);
  const chosen = paths.filter((p) => selected.includes(p));
  const chosenNodes = chosen.map((p) => find(tree, p)!).filter(Boolean);
  useEffect(() => { setSelected([]); setAnchor(null); }, [folder, q, kind]);
  const toggle = (id: string) => setExpanded((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const openFolder = (path: string) => { setFolder(path); setExpanded((s) => { const n = new Set(s); let p = path; while (p) { n.add(p); p = parentOf(p); } n.add(""); return n; }); };
  const open = (n: WorkspaceNode) => { if (n.type === "folder") openFolder(n.path); else if (OPENS.includes(n.type)) onOpen(n); };

  /** Runs one file operation per path, in order; stops at the first refusal and says why. Then the tree is read again. */
  const each = async (list: string[], op: (path: string) => Promise<string>, done: (made: string[]) => string) => {
    const made: string[] = [];
    try { for (const p of list) made.push(await op(p)); onNotice(done(made)); }
    catch (e) { onError(e); }
    await onChanged();
    return made;
  };
  const count = (n: number) => n + (n === 1 ? " item" : " items");
  const act = {
    copy: (list = chosen) => { if (list.length) { setClip({ paths: list, cut: false }); onNotice("Copied " + count(list.length) + ". Paste them into a folder."); } },
    cut: (list = chosen) => { if (list.length) { setClip({ paths: list, cut: true }); onNotice("Cut " + count(list.length) + ". Paste them into a folder to move them."); } },
    paste: async (to = folder) => {
      if (!clip) return;
      const made = clip.cut
        ? await each(clip.paths, async (p) => { const n = await api.move(p, to); onMoved(p, n); return n; }, (m) => "Moved " + count(m.length) + " to " + (to || "the workspace") + ".")
        : await each(clip.paths, (p) => api.copy(p, to), (m) => "Pasted " + count(m.length) + " into " + (to || "the workspace") + ".");
      if (clip.cut) setClip(null);
      if (to === folder) setSelected(made);
    },
    duplicate: async (list = chosen) => { const made = await each(list, (p) => api.duplicate(p), (m) => "Duplicated " + count(m.length) + "."); setSelected(made); },
    move: async (list: string[], to: string) => {
      const moving = list.filter((p) => parentOf(p) !== to);
      if (moving.length) await each(moving, async (p) => { const n = await api.move(p, to); onMoved(p, n); return n; }, (m) => "Moved " + count(m.length) + " to " + (to || "the workspace") + ".");
    },
    remove: (list = chosenNodes) => { if (list.length) onDelete(list); },
    /** Files from this computer into a folder, as they are (a taken name gets " (2)"). */
    upload: (to: string) => { uploadTo.current = to; uploadInput.current?.click(); },
    /** A file, or a folder as a zip, saved by the browser. */
    download: (path: string) => { const a = document.createElement("a"); a.href = api.downloadUrl(path); a.download = ""; document.body.appendChild(a); a.click(); a.remove(); },
    reveal: async (path: string) => { try { await api.reveal(path); } catch (e) { onError(e); } },
  };
  const uploaded = async (files: FileList | null) => {
    const to = uploadTo.current;
    if (!files?.length) return;
    const made: string[] = [];
    try { for (const f of [...files]) made.push(await api.upload(to, f.name, f)); }
    catch (e) { onError(e); }
    if (made.length) { onNotice("Uploaded " + count(made.length) + " to " + (to || "the workspace") + "."); await onChanged(); setSelected(made); }
    if (uploadInput.current) uploadInput.current.value = "";
  };
  const click = (e: MouseEvent, path: string) => {
    if (e.ctrlKey || e.metaKey) { setSelected(selected.includes(path) ? selected.filter((x) => x !== path) : [...selected, path]); setAnchor(path); }
    else if (e.shiftKey && anchor && paths.includes(anchor)) { const a = paths.indexOf(anchor), b = paths.indexOf(path); setSelected(paths.slice(Math.min(a, b), Math.max(a, b) + 1)); }
    else { setSelected([path]); setAnchor(path); }
  };
  const keys = (e: KeyboardEvent) => {
    if (isPublic || (e.target as HTMLElement).closest("input, textarea, select, [role=menu]")) return;   // the workspace keys do nothing on Public
    const ev = e.nativeEvent;
    if (is(ev, "files.copy")) act.copy();
    else if (is(ev, "files.cut")) act.cut();
    else if (is(ev, "files.paste")) act.paste();
    else if (is(ev, "files.all")) setSelected(paths);
    else if (is(ev, "files.delete")) act.remove();
    else if (is(ev, "files.rename") && chosenNodes.length === 1) setRenaming(chosenNodes[0]);
    else if (is(ev, "files.clear")) setSelected([]);
    else return;
    e.preventDefault();
  };
  const dragPaths = (path: string) => selected.includes(path) ? chosen : [path];
  const dropOn = (to: string, e: DragEvent) => {
    const raw = e.dataTransfer.getData(PATHS_DRAG);
    if (raw) act.move(JSON.parse(raw) as string[], to);
  };
  const statusOf = (n: WorkspaceNode) => repo?.states[n.path]?.status ?? (CHECKS_IN.includes(n.type) ? "new" : null);
  const cols = COLUMNS.filter((c) => !hiddenCols.includes(c.key) && (!!repo || !FROM_REPO.includes(c.key)));
  /** A column's value for an item; the repository's facts come from its linked copy (empty for new items). */
  const cell = (key: string, n: WorkspaceNode, why?: string): ReactNode => {
    const st = repo?.states[n.path], s = statusOf(n);
    switch (key) {
      case "type": return n.type === "folder" ? "Folder" : typeLabel(n);
      case "status": return s && <span className={"st-pill st-status is-" + s} title={STATUS[s][1]}>{STATUS[s][0]}</span>;
      case "platform": return n.platform ?? "";
      case "version": return st?.latest_version ? "v" + st.latest_version + (st.production_version === st.latest_version ? " · live" : "") : "";
      case "label": return labelText(st?.labels);
      case "analysis": return <Analysis found={st?.analysis} />;
      case "size": return n.type === "folder" ? "" : sizeText(n.size);
      case "changed": return q ? why : n.modified ? new Date(n.modified).toLocaleString() : "";
      case "by": return s === "new" || s === "edited" || s === "checked_out_edited" ? "You" : st?.updated_by ?? "";
      default: return "";
    }
  };
  const canCheckIn = (n: WorkspaceNode | null) => !!n && !!repo && CHECKS_IN.includes(n.type) && ["new", "checked_out", "checked_out_edited"].includes(statusOf(n) ?? "");
  const rowMenu = (n: WorkspaceNode): MenuItem[] => {
    const many = selected.includes(n.path) && chosen.length > 1;
    const list = many ? chosen : [n.path];
    const items: MenuItem[] = [];
    if (!many && (n.type === "folder" || OPENS.includes(n.type))) items.push({ key: "open", label: "Open", onSelect: () => open(n) });
    if (!many) items.push({ key: "rename", label: "Rename…", shortcut: keyOf("files.rename"), onSelect: () => setRenaming(n) });
    // the repository: Check in (several at once, up to 10), Undo check-out and Revert edits, as AA's Private workspace
    const st = repo?.states[n.path];
    if (repo && list.length <= 10 && list.every((p) => canCheckIn(find(tree, p))))
      items.push({ key: "checkin", label: many ? "Check in " + list.length + " items…" : "Check in…", separatorBefore: true, onSelect: () => repo.onCheckIn(list) });
    if (repo && !many && st?.status.startsWith("checked_out")) items.push({ key: "undo", label: "Undo check-out…", onSelect: () => repo.onUndo(n.path, st.path) });
    if (repo && !many && st?.status === "checked_out_edited") items.push({ key: "revert", label: "Revert edits…", onSelect: () => repo.onRevert(n.path) });
    items.push(
      { key: "dup", label: "Duplicate", onSelect: () => act.duplicate(list) },
      { key: "copy", label: "Copy", shortcut: keyOf("files.copy"), separatorBefore: true, onSelect: () => act.copy(list) },
      { key: "cut", label: "Cut", shortcut: keyOf("files.cut"), onSelect: () => act.cut(list) },
    );
    if (!many && n.type === "folder") {
      if (clip) items.push({ key: "paste", label: "Paste into this folder", description: count(clip.paths.length) + (clip.cut ? " cut" : " copied"), onSelect: () => act.paste(n.path) });
      items.push({ key: "folder", label: "New folder in it", separatorBefore: true, onSelect: () => onNewFolder(n.path) });
      items.push({ key: "upload", label: "Upload files…", description: "From this computer into this folder", onSelect: () => act.upload(n.path) });
    }
    if (!many) items.push(
      { key: "download", label: n.type === "folder" ? "Download as zip" : "Download", separatorBefore: n.type !== "folder", onSelect: () => act.download(n.path) },
      { key: "reveal", label: "Show in File Explorer", onSelect: () => act.reveal(n.path) });
    items.push({ key: "delete", label: many ? "Delete " + list.length + " items" : "Delete", shortcut: keyOf("files.delete"), separatorBefore: true,
      onSelect: () => act.remove(list.map((p) => find(tree, p)!).filter(Boolean)) });
    return items;
  };
  /** A folder's menu: in the pane's header (the chosen folder), and on each folder in the tree (that folder). */
  const folderItemsFor = (path: string): MenuItem[] => {
    const node = find(tree, path) ?? tree;
    return [
      { key: "new", label: "New folder in it", onSelect: () => onNewFolder(path) },
      { key: "upload", label: "Upload files…", description: "From this computer into this folder", onSelect: () => act.upload(path) },
      { key: "download", label: "Download as zip", onSelect: () => act.download(path) },
      { key: "reveal", label: "Show in File Explorer", onSelect: () => act.reveal(path) },
      ...(clip ? [{ key: "paste", label: "Paste here", description: count(clip.paths.length) + (clip.cut ? " cut" : " copied"), onSelect: () => { act.paste(path); } }] : []),
      ...(path ? [
        { key: "rename", label: "Rename…", shortcut: keyOf("files.rename"), onSelect: () => setRenaming(node) },
        { key: "copy", label: "Copy", separatorBefore: true, onSelect: () => act.copy([path]) },
        { key: "cut", label: "Cut", onSelect: () => act.cut([path]) },
        { key: "dup", label: "Duplicate", onSelect: () => act.duplicate([path]) },
        { key: "delete", label: "Delete", separatorBefore: true, onSelect: () => onDelete([node]) },
      ] : []),
    ];
  };
  const folderItems = folderItemsFor(folder);
  const [treeMenu, setTreeMenu] = useState<string | null>(null);   // the folder whose ⋮ in the tree is open

  return (
    <section className="st-page" aria-labelledby="st-page-title" onKeyDown={keys}>
      <input ref={uploadInput} type="file" multiple hidden aria-label="Files to upload" onChange={(e) => uploaded(e.target.files)} />
      <header className="st-page-head">
        <div className="st-head-row">
          <h1 id="st-page-title">{title}</h1>
          <HelpButton topic={isPublic ? PUBLIC_HELP : AUTOMATION_HELP} />
          {!isPublic && <span className="st-head-count">{q ? count(rows.length) + " matching “" + search.trim() + "” in the whole workspace"
            : count(everything(tree).filter((n) => n.type !== "folder").length) + " · " + everything(tree).filter((n) => n.type === "bot").length + " agents"}</span>}
        </div>
        {!isPublic && <div className="st-page-actions">
          <Button kind="secondary" icon="plus" onClick={() => onNewFolder(folder)}>New folder</Button>
          <Menu label="Create" title={"Create in " + folderName}
            trigger={<Button kind="primary">Create ▾</Button>}
            items={ITEM_TYPES.map((t) => ({ key: t.kind, label: t.name, description: t.description, icon: <IconTile name={t.icon} color={t.color} size={28} />,
              onSelect: () => { setCreateIn(folder); setCreating(t); } }))} />
        </div>}
      </header>
      {publicView && <div className="st-page-tabs" role="tablist" aria-label="Where">
        {([["public", "Public", "One CommandCenter agent repository: agents checked in, to run"], ["private", "Private", "Your workspace on this computer: build and test"]] as const).map(([k, t, tip]) =>
          <button key={k} type="button" role="tab" className="st-page-tab" aria-selected={(k === "public") === isPublic} title={tip} onClick={() => setPlace(k)}>{t}</button>)}
      </div>}
      {isPublic ? publicView : <div className="st-page-body">
        <SidePanel title="Folders" side="left" collapsed={foldersHidden} onToggle={() => setFoldersHidden(!foldersHidden)} className="st-folders"
          style={foldersWidth ? { flex: `0 0 ${foldersWidth}px` } : undefined} panelRef={(el) => { foldersPanel.current = el; }}
          actions={<>
            <button type="button" className="ui-panel-toggle" aria-label={"New folder in " + folderName} title={"New folder in " + folderName}
              onClick={() => onNewFolder(folder)}><Icon name="plus" size={14} stroke={2.6} /></button>
            <Menu label={"Folder " + folderName + " menu"} title={folderName} items={folderItems} open={folderMenu} onOpenChange={setFolderMenu}
              trigger={<button type="button" className="ui-panel-toggle" aria-label={"Folder actions for " + folderName} title="Folder actions (right-click a folder)">
                <Icon name="more" size={14} stroke={3} /></button>} />
          </>}>
          <div className="st-folders-tree"><Tree label="Folders" nodes={nodes} selected={folder} expanded={expanded} onSelect={openFolder} onToggle={toggle}
            onMenu={(id) => setTreeMenu(id)}
            renderActions={(n) => <Menu label={"Folder " + (n.id ? n.label : ROOT) + " menu"} title={n.id ? n.label : ROOT} items={folderItemsFor(n.id)}
              open={treeMenu === n.id} onOpenChange={(o) => setTreeMenu(o ? n.id : null)}
              trigger={<button type="button" className="ui-tree-more" aria-label={"Actions for folder " + (n.id ? n.label : ROOT)} title="Folder actions (right-click)">
                <Icon name="more" size={14} stroke={3} /></button>} />}
            canDrag={(id) => id !== ""} onDragStart={(id, e) => { e.dataTransfer.setData(PATHS_DRAG, JSON.stringify([id])); e.dataTransfer.effectAllowed = "move"; }}
            onDrop={dropOn}
            renderIcon={(_, sel) => <span style={{ color: sel ? "#ffffff" : "#a16207", display: "inline-flex" }}>
              <svg width="15" height="15" viewBox="0 0 24 24" aria-hidden="true" fill={sel ? "#ffffff" : "#f5c451"} stroke="currentColor" strokeWidth="1.8"><path d="M3 7h6l2 2h10v10H3z" /></svg></span>} /></div>
        </SidePanel>
        {!foldersHidden && <Splitter label="Resize the folders panel" axis="x" grow={1} value={foldersWidth} min={180} max={520}
          measure={() => foldersPanel.current?.getBoundingClientRect().width ?? 260} onChange={setFoldersWidth} />}
        <div className="st-items">
          <div className="st-items-tools" role="group" aria-label="List tools">
            <span className="st-items-count" aria-live="polite">{listed.length === rows.length ? count(rows.length) : listed.length + " of " + count(rows.length)}</span>
            <Select aria-label="Type" value={kind} onChange={(e) => setKind(e.target.value)} options={KINDS.map(([value, label]) => ({ value, label }))} />
            <span className="st-items-fill" />
            <Button kind="plain" onClick={() => setChoosingCols(true)}>Columns</Button>
            <button type="button" className="ui-icon-btn" aria-label="Refresh" title="Refresh" onClick={() => { onChanged(); }}><Icon name="loop" size={15} /></button>
          </div>
          {(chosen.length > 0 || (clip && !q)) && <div role="toolbar" aria-label="Selected items" className="st-items-bar is-chosen">
            <strong className="st-items-count">{chosen.length ? chosen.length + " selected" : count(clip!.paths.length) + (clip!.cut ? " cut" : " copied")}</strong>
            {chosen.length > 0 && <>
              <Button kind="plain" onClick={() => act.copy()} title={keyOf("files.copy")}>Copy</Button>
              <Button kind="plain" onClick={() => act.cut()} title={keyOf("files.cut")}>Cut</Button>
              <Button kind="plain" onClick={() => act.duplicate()}>Duplicate</Button>
              {repo && chosenNodes.length <= 10 && chosenNodes.every(canCheckIn) && <Button kind="plain" onClick={() => repo.onCheckIn(chosen)}>Check in…</Button>}
              {chosenNodes.length === 1 && <Button kind="plain" onClick={() => setRenaming(chosenNodes[0])} title="F2">Rename</Button>}
              <Button kind="plain" icon="trash" onClick={() => act.remove()} title="Delete">Delete…</Button>
            </>}
            {clip && !q && <Button kind="plain" onClick={() => act.paste()} title={keyOf("files.paste")}>{"Paste " + count(clip.paths.length) + " here"}</Button>}
            <span className="st-items-fill" />
            {chosen.length > 0 ? <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button> : <Button kind="plain" onClick={() => setClip(null)}>Cancel</Button>}
          </div>}
          <table className="st-table">
            <thead><tr><th scope="col" className="st-tick"><input type="checkbox" aria-label="Select all" checked={paths.length > 0 && paths.every((p) => selected.includes(p))}
              onChange={(e) => setSelected(e.target.checked ? paths : [])} /></th><th scope="col">Name</th>{cols.map((c) => <th key={c.key} scope="col" className={CLASS[c.key]}>{c.key === "changed" && q ? "Found by" : c.label}</th>)}<th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {listed.map(({ node: n, why }) => {
                const isSel = selected.includes(n.path);
                return (
                  <tr key={n.path} className={isSel ? "is-selected" : ""} aria-selected={isSel} onClick={(e) => click(e, n.path)} onDoubleClick={() => open(n)}
                    onContextMenu={(e) => { e.preventDefault(); if (!isSel) { setSelected([n.path]); setAnchor(n.path); } setMenuFor(n.path); }}
                    draggable onDragStart={(e) => { e.dataTransfer.setData(PATHS_DRAG, JSON.stringify(dragPaths(n.path))); e.dataTransfer.effectAllowed = "move"; }}
                    onDragOver={n.type === "folder" ? (e) => e.preventDefault() : undefined} onDrop={n.type === "folder" ? (e) => { e.preventDefault(); dropOn(n.path, e); } : undefined}>
                    <td className="st-tick" onClick={(e) => e.stopPropagation()}><input type="checkbox" aria-label={"Select " + displayName(n)} checked={isSel}
                      onChange={(e) => { setSelected(e.target.checked ? [...selected, n.path] : selected.filter((x) => x !== n.path)); setAnchor(n.path); }} /></td>
                    <td><span className="st-name">{n.type === "folder"
                      ? <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true" fill="#f5c451" stroke="#a16207" strokeWidth="1.8"><path d="M3 7h6l2 2h10v10H3z" /></svg>
                      : markOf(n) ? <IconTile name={markOf(n)!.icon} color={markOf(n)!.color} size={18} /> : <Icon name="doc" size={16} />}
                      {n.type === "folder"
                      ? <button type="button" className="st-link st-folder-link" onClick={(e) => { e.stopPropagation(); openFolder(n.path); }}>{n.name}</button>
                      : OPENS.includes(n.type)
                      ? <button type="button" className="st-link" onClick={(e) => { if (!e.ctrlKey && !e.metaKey && !e.shiftKey) { e.stopPropagation(); onOpen(n); } }}>{displayName(n)}</button>
                      : <span>{displayName(n)}</span>}</span>
                      {q && <div className="st-item-path">{parentOf(n.path) || ROOT}</div>}</td>
                    {cols.map((c) => <td key={c.key} className={CLASS[c.key]}>{cell(c.key, n, why)}</td>)}
                    <td className="st-row-actions" onClick={(e) => e.stopPropagation()}>
                      <Menu label={displayName(n) + " menu"} items={rowMenu(n)} open={menuFor === n.path}
                        onOpenChange={(o) => { if (o && !isSel) { setSelected([n.path]); setAnchor(n.path); } setMenuFor(o ? n.path : null); }}
                        trigger={<button type="button" className="ui-icon-btn st-row-more" aria-label={"Actions for " + displayName(n)} title="Actions (right-click)"><Icon name="more" size={16} stroke={3} /></button>} />
                    </td>
                  </tr>
                );
              })}
              {!listed.length && <tr><td colSpan={cols.length + 3} className="st-empty">{q || kind ? "Nothing matches." : "Nothing here yet. Press Create to make the first one in this folder."}</td></tr>}
            </tbody>
          </table>
        </div>
      </div>}
      {choosingCols && <ColumnsDialog columns={COLUMNS.filter((c) => !!repo || !FROM_REPO.includes(c.key))} hidden={hiddenCols} onChange={setHiddenCols}
        onClose={() => setChoosingCols(false)} />}
      <CreateDialog type={creating} folderPath={createIn} folders={everything(tree).filter((n) => n.type === "folder").map((n) => n.path)} blueprints={blueprints}
        onClose={() => setCreating(null)} onChangeFolder={setCreateIn}
        onCreate={async (kind, name, from) => { await onCreated(kind, createIn, name, from); setCreating(null); }} />
      {renaming && <RenameDialog node={renaming} onClose={() => setRenaming(null)} onRename={async (name) => {
        try {
          const to = await api.rename(renaming.path, name);
          onMoved(renaming.path, to);
          if (folder === renaming.path || folder.startsWith(renaming.path + "/")) setFolder(to + folder.slice(renaming.path.length));
          setSelected([to]); setRenaming(null);
          onNotice("Renamed to " + to.split("/").pop() + ".");
          await onChanged();
        } catch (e) { onError(e); }
      }} />}
    </section>
  );
}

/** Rename a file or folder: the name without its type ending, which is kept (Invoice stays an agent, form or workflow). */
function RenameDialog({ node, onClose, onRename }: { node: WorkspaceNode; onClose: () => void; onRename: (name: string) => Promise<void> }) {
  const [name, setName] = useState(node.type === "folder" ? node.name : displayName(node));
  const what = node.type === "folder" ? "folder" : typeLabel(node).toLowerCase();
  return (
    <Dialog open onClose={onClose} title={"Rename " + (node.type === "folder" ? node.name : displayName(node))} width={480}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button><span className="st-bar-fill" />
        <Button kind="primary" onClick={() => onRename(name.trim())} disabled={!name.trim()}>Rename</Button></>}>
      <form className="st-form" onSubmit={(e) => { e.preventDefault(); if (name.trim()) onRename(name.trim()); }}>
        <Field label="New name" hint={node.type === "folder" ? "Open agents in it follow the folder." : "It stays " + (/^[aeiou]/.test(what) ? "an " : "a ") + what + "; an open tab follows it."}>{(id) =>
          <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}
