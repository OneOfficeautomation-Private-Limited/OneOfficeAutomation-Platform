import { useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import type { Blueprint, NewItemKind, StudioApi, WorkspaceNode } from "../api/client";
import { Button, Dialog, Field, Icon, IconTile, Menu, SidePanel, Splitter, TextInput, Tree, usePanelSize, usePersistent, type MenuItem, type TreeNode } from "../components";
import { CreateDialog } from "./CreateDialog";
import type { StudioView } from "./CommandMenu";
import { ITEM_TYPES, displayName, typeLabel, type ItemType } from "./itemTypes";

const VIEW_TITLE: Record<StudioView, [string, (n: WorkspaceNode) => boolean]> = {
  automation: ["Automation", (n) => n.type !== "folder"],
};

const folderTree = (n: WorkspaceNode): TreeNode => ({ id: n.path, label: n.name, children: (n.children ?? []).filter((c) => c.type === "folder").map(folderTree) });

/** What opens in an editor here: agents and the other items; data files stay as they are. */
const OPENS: WorkspaceNode["type"][] = ["bot", "form", "process", "document", "skill", "script"];

const everything = (n: WorkspaceNode): WorkspaceNode[] => (n.children ?? []).flatMap((c) => [c, ...everything(c)]);

function find(n: WorkspaceNode, path: string): WorkspaceNode | null {
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
 * As in AA, Private is the workspace and Public is the Command Center agent repository (publicView).
 */
export function Automation({ api, view, tree, search = "", blueprints = [], publicView, onOpen, onCreated, onNewFolder, onDelete, onMoved, onChanged, onNotice, onError }:
  { api: StudioApi; view: StudioView; tree: WorkspaceNode; search?: string; blueprints?: Blueprint[]; publicView?: ReactNode; onOpen: (n: WorkspaceNode) => void;
    onDelete: (nodes: WorkspaceNode[]) => void; onCreated: (kind: NewItemKind, parent: string, name: string, from: Blueprint | null) => Promise<void>;
    onNewFolder: (parent: string) => void; onMoved: (from: string, to: string) => void; onChanged: () => Promise<unknown>;
    onNotice: (text: string) => void; onError: (e: unknown) => void }) {
  const [folder, setFolder] = useState("");
  const [expanded, setExpanded] = useState(() => new Set([""]));
  const [creating, setCreating] = useState<ItemType | null>(null);
  const [foldersHidden, setFoldersHidden] = usePersistent("panel.folders.hidden", false);
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
  const [place, setPlace] = usePersistent<"private" | "public">("automation.place", "private");
  const isPublic = place === "public" && !!publicView && !search.trim();   // a search looks in the workspace
  const [title, keep] = VIEW_TITLE[view];
  const folderNode = find(tree, folder) ?? tree;
  const folderName = folderNode.path ? folderNode.name : "Workspace";
  const nodes = useMemo(() => [folderTree(tree)], [tree]);
  const q = search.trim().toLowerCase();
  // a search also asks the server, which reads the agents' descriptions, actions and variables
  useEffect(() => {
    if (q.length < 2) { setFound([]); return; }
    const t = setTimeout(() => { api.search(q).then(setFound, () => setFound([])); }, 250);
    return () => clearTimeout(t);
  }, [api, q]);
  // with a search, every matching item in the workspace; otherwise the chosen folder's sub-folders, then its items
  const rows: { node: WorkspaceNode; why?: string }[] = q
    ? [...everything(tree).filter((n) => keep(n) && n.name.toLowerCase().includes(q) && !found.some((f) => f.path === n.path)).map((node) => ({ node, why: "name" })),
       ...found.flatMap((f) => { const node = find(tree, f.path); return node ? [{ node, why: foundBy(f.matches) }] : []; })]
    : [...(folderNode.children ?? []).filter((c) => c.type === "folder"), ...(folderNode.children ?? []).filter(keep)].map((node) => ({ node }));
  const paths = rows.map((r) => r.node.path);
  const chosen = paths.filter((p) => selected.includes(p));
  const chosenNodes = chosen.map((p) => find(tree, p)!).filter(Boolean);
  useEffect(() => { setSelected([]); setAnchor(null); }, [folder, q]);
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
    const ctrl = e.ctrlKey || e.metaKey, k = e.key.toLowerCase();
    if (ctrl && k === "c") act.copy();
    else if (ctrl && k === "x") act.cut();
    else if (ctrl && k === "v") act.paste();
    else if (ctrl && k === "a") setSelected(paths);
    else if (e.key === "Delete") act.remove();
    else if (e.key === "F2" && chosenNodes.length === 1) setRenaming(chosenNodes[0]);
    else if (e.key === "Escape") setSelected([]);
    else return;
    e.preventDefault();
  };
  const dragPaths = (path: string) => selected.includes(path) ? chosen : [path];
  const dropOn = (to: string, e: DragEvent) => {
    const raw = e.dataTransfer.getData(PATHS_DRAG);
    if (raw) act.move(JSON.parse(raw) as string[], to);
  };
  const rowMenu = (n: WorkspaceNode): MenuItem[] => {
    const many = selected.includes(n.path) && chosen.length > 1;
    const list = many ? chosen : [n.path];
    const items: MenuItem[] = [];
    if (!many && (n.type === "folder" || OPENS.includes(n.type))) items.push({ key: "open", label: "Open", onSelect: () => open(n) });
    if (!many) items.push({ key: "rename", label: "Rename…", shortcut: "F2", onSelect: () => setRenaming(n) });
    items.push(
      { key: "dup", label: "Duplicate", onSelect: () => act.duplicate(list) },
      { key: "copy", label: "Copy", shortcut: "Ctrl+C", separatorBefore: true, onSelect: () => act.copy(list) },
      { key: "cut", label: "Cut", shortcut: "Ctrl+X", onSelect: () => act.cut(list) },
    );
    if (!many && n.type === "folder") {
      if (clip) items.push({ key: "paste", label: "Paste into this folder", description: count(clip.paths.length) + (clip.cut ? " cut" : " copied"), onSelect: () => act.paste(n.path) });
      items.push({ key: "folder", label: "New folder in it", separatorBefore: true, onSelect: () => onNewFolder(n.path) });
      items.push({ key: "upload", label: "Upload files…", description: "From this computer into this folder", onSelect: () => act.upload(n.path) });
    }
    if (!many) items.push(
      { key: "download", label: n.type === "folder" ? "Download as zip" : "Download", separatorBefore: n.type !== "folder", onSelect: () => act.download(n.path) },
      { key: "reveal", label: "Show in File Explorer", onSelect: () => act.reveal(n.path) });
    items.push({ key: "delete", label: many ? "Delete " + list.length + " items" : "Delete", shortcut: "Del", separatorBefore: true,
      onSelect: () => act.remove(list.map((p) => find(tree, p)!).filter(Boolean)) });
    return items;
  };
  const folderItems: MenuItem[] = [
    { key: "new", label: "New folder in it", onSelect: () => onNewFolder(folder) },
    { key: "upload", label: "Upload files…", description: "From this computer into this folder", onSelect: () => act.upload(folder) },
    { key: "download", label: "Download as zip", onSelect: () => act.download(folder) },
    { key: "reveal", label: "Show in File Explorer", onSelect: () => act.reveal(folder) },
    ...(clip ? [{ key: "paste", label: "Paste here", description: count(clip.paths.length) + (clip.cut ? " cut" : " copied"), onSelect: () => { act.paste(folder); } }] : []),
    ...(folder ? [
      { key: "rename", label: "Rename…", onSelect: () => setRenaming(folderNode) },
      { key: "copy", label: "Copy", separatorBefore: true, onSelect: () => act.copy([folder]) },
      { key: "cut", label: "Cut", onSelect: () => act.cut([folder]) },
      { key: "dup", label: "Duplicate", onSelect: () => act.duplicate([folder]) },
      { key: "delete", label: "Delete", separatorBefore: true, onSelect: () => onDelete([folderNode]) },
    ] : []),
  ];

  return (
    <section className="st-page" aria-labelledby="st-page-title" onKeyDown={keys}>
      <input ref={uploadInput} type="file" multiple hidden aria-label="Files to upload" onChange={(e) => uploaded(e.target.files)} />
      <header className="st-page-head">
        <div>
          <h1 id="st-page-title">{title}</h1>
          <p>{isPublic ? "Agents shared through Command Center. Check one out to work on it here." : q ? `Items matching “${search.trim()}” in the whole workspace` : "Pick a folder, then press Create."}</p>
        </div>
        {!isPublic && <div className="st-page-actions">
          <Button kind="secondary" icon="plus" onClick={() => onNewFolder(folder)}>New folder</Button>
          <Menu label="Create" title={"Create in " + folderName}
            trigger={<Button kind="primary">Create ▾</Button>}
            items={ITEM_TYPES.map((t) => ({ key: t.kind, label: t.name, description: t.description, icon: <IconTile name={t.icon} color={t.color} size={28} />,
              onSelect: () => setCreating(t) }))} />
        </div>}
      </header>
      {publicView && <div className="st-page-tabs" role="tablist" aria-label="Where">
        {([["private", "Private", "Your workspace on this computer"], ["public", "Public", "The Command Center agent repository"]] as const).map(([k, t, tip]) =>
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
          <div className="st-folders-tree"><Tree label="Folders" nodes={nodes} selected={folder} expanded={expanded} onSelect={setFolder} onToggle={toggle}
            onMenu={(id) => { setFolder(id); setFolderMenu(true); }}
            canDrag={(id) => id !== ""} onDragStart={(id, e) => { e.dataTransfer.setData(PATHS_DRAG, JSON.stringify([id])); e.dataTransfer.effectAllowed = "move"; }}
            onDrop={dropOn}
            renderIcon={(_, sel) => <span style={{ color: sel ? "#ffffff" : "#a16207", display: "inline-flex" }}>
              <svg width="15" height="15" viewBox="0 0 24 24" aria-hidden="true" fill={sel ? "#ffffff" : "#f5c451"} stroke="currentColor" strokeWidth="1.8"><path d="M3 7h6l2 2h10v10H3z" /></svg></span>} /></div>
        </SidePanel>
        {!foldersHidden && <Splitter label="Resize the folders panel" axis="x" grow={1} value={foldersWidth} min={180} max={520}
          measure={() => foldersPanel.current?.getBoundingClientRect().width ?? 260} onChange={setFoldersWidth} />}
        <div className="st-items">
          <div role="toolbar" aria-label="Selected items" className="st-items-bar">
            <span className="st-items-count" aria-live="polite">{chosen.length ? chosen.length + " selected" : count(rows.length)}</span>
            <Button kind="plain" onClick={() => act.copy()} disabled={!chosen.length} title="Ctrl+C">Copy</Button>
            <Button kind="plain" onClick={() => act.cut()} disabled={!chosen.length} title="Ctrl+X">Cut</Button>
            <Button kind="plain" onClick={() => act.paste()} disabled={!clip || !!q} title="Ctrl+V">{clip ? "Paste " + count(clip.paths.length) : "Paste"}</Button>
            <Button kind="plain" onClick={() => act.duplicate()} disabled={!chosen.length}>Duplicate</Button>
            <Button kind="plain" onClick={() => chosenNodes.length === 1 && setRenaming(chosenNodes[0])} disabled={chosenNodes.length !== 1} title="F2">Rename</Button>
            <Button kind="plain" icon="trash" onClick={() => act.remove()} disabled={!chosen.length} title="Delete">Delete</Button>
          </div>
          <table className="st-table">
            <thead><tr><th scope="col">Name</th><th scope="col">Type</th><th scope="col">{q ? "Found by" : "Changed"}</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {rows.map(({ node: n, why }) => {
                const isSel = selected.includes(n.path);
                return (
                  <tr key={n.path} className={isSel ? "is-selected" : ""} aria-selected={isSel} onClick={(e) => click(e, n.path)} onDoubleClick={() => open(n)}
                    onContextMenu={(e) => { e.preventDefault(); if (!isSel) { setSelected([n.path]); setAnchor(n.path); } setMenuFor(n.path); }}
                    draggable onDragStart={(e) => { e.dataTransfer.setData(PATHS_DRAG, JSON.stringify(dragPaths(n.path))); e.dataTransfer.effectAllowed = "move"; }}
                    onDragOver={n.type === "folder" ? (e) => e.preventDefault() : undefined} onDrop={n.type === "folder" ? (e) => { e.preventDefault(); dropOn(n.path, e); } : undefined}>
                    <td>{n.type === "folder"
                      ? <button type="button" className="st-link st-folder-link" onClick={(e) => { e.stopPropagation(); openFolder(n.path); }}>
                          <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" fill="#f5c451" stroke="#a16207" strokeWidth="1.8"><path d="M3 7h6l2 2h10v10H3z" /></svg>{n.name}</button>
                      : OPENS.includes(n.type)
                      ? <button type="button" className="st-link" onClick={(e) => { if (!e.ctrlKey && !e.metaKey && !e.shiftKey) { e.stopPropagation(); onOpen(n); } }}>{displayName(n)}</button>
                      : <span>{displayName(n)}</span>}
                      {q && <div className="st-item-path">{parentOf(n.path) || "Workspace"}</div>}</td>
                    <td>{n.type === "folder" ? "Folder" : typeLabel(n)}</td>
                    <td>{q ? why : n.modified ? new Date(n.modified).toLocaleString() : ""}</td>
                    <td className="st-row-actions" onClick={(e) => e.stopPropagation()}>
                      <Menu label={displayName(n) + " menu"} items={rowMenu(n)} open={menuFor === n.path}
                        onOpenChange={(o) => { if (o && !isSel) { setSelected([n.path]); setAnchor(n.path); } setMenuFor(o ? n.path : null); }}
                        trigger={<button type="button" className="ui-icon-btn st-row-more" aria-label={"Actions for " + displayName(n)} title="Actions (right-click)"><Icon name="more" size={16} stroke={3} /></button>} />
                    </td>
                  </tr>
                );
              })}
              {!rows.length && <tr><td colSpan={4} className="st-empty">{q ? "Nothing matches." : "Nothing here yet. Press Create to make the first one in this folder."}</td></tr>}
            </tbody>
          </table>
        </div>
      </div>}
      <CreateDialog type={creating} folderPath={folder} blueprints={blueprints} onClose={() => setCreating(null)} onChangeFolder={() => setCreating(null)}
        onCreate={async (kind, name, from) => { await onCreated(kind, folder, name, from); setCreating(null); }} />
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
