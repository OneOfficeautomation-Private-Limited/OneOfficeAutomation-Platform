import { useMemo, useState } from "react";
import type { NewItemKind, WorkspaceNode } from "../api/client";
import { Button, IconTile, Menu, SidePanel, Tree, usePersistent, type TreeNode } from "../components";
import { CreateDialog } from "./CreateDialog";
import type { StudioView } from "./CommandMenu";
import { ITEM_TYPES, typeLabel, type ItemType } from "./itemTypes";

const VIEW_TITLE: Record<StudioView, [string, (n: WorkspaceNode) => boolean]> = {
  automation: ["Automation", (n) => n.type !== "folder"],
  workflows: ["Workflows", (n) => n.type === "process"],
  forms: ["Forms", (n) => n.type === "form"],
};

const folderTree = (n: WorkspaceNode): TreeNode => ({ id: n.path, label: n.name, children: (n.children ?? []).filter((c) => c.type === "folder").map(folderTree) });

const everything = (n: WorkspaceNode): WorkspaceNode[] => (n.children ?? []).flatMap((c) => [c, ...everything(c)]);

function find(n: WorkspaceNode, path: string): WorkspaceNode | null {
  if (n.path === path) return n;
  for (const c of n.children ?? []) { const f = find(c, path); if (f) return f; }
  return null;
}

/**
 * The Automation page, in AA's order: pick a folder, press Create, choose the type, then name it. The table lists
 * what is in the folder; opening an agent opens it in a tab.
 */
export function Automation({ view, tree, search = "", onOpen, onCreated, onNewFolder }:
  { view: StudioView; tree: WorkspaceNode; search?: string; onOpen: (n: WorkspaceNode) => void; onCreated: (kind: NewItemKind, parent: string, name: string) => Promise<void>;
    onNewFolder: (parent: string) => void }) {
  const [folder, setFolder] = useState("");
  const [expanded, setExpanded] = useState(() => new Set([""]));
  const [creating, setCreating] = useState<ItemType | null>(null);
  const [foldersHidden, setFoldersHidden] = usePersistent("panel.folders.hidden", false);
  const [title, keep] = VIEW_TITLE[view];
  const nodes = useMemo(() => [folderTree(tree)], [tree]);
  // with a search, every matching item in the workspace; otherwise what is in the chosen folder
  const q = search.trim().toLowerCase();
  const items = q ? everything(tree).filter((n) => keep(n) && n.name.toLowerCase().includes(q)) : (find(tree, folder)?.children ?? []).filter(keep);
  const toggle = (id: string) => setExpanded((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  return (
    <section className="st-page" aria-labelledby="st-page-title">
      <header className="st-page-head">
        <div>
          <h1 id="st-page-title">{title}</h1>
          <p>{q ? `Items matching “${search.trim()}” in the whole workspace` : "Pick a folder, then press Create."}</p>
        </div>
        <div className="st-page-actions">
          <Button kind="secondary" icon="plus" onClick={() => onNewFolder(folder)}>New folder</Button>
          <Menu label="Create" title={"Create in " + (find(tree, folder)?.name ?? "Workspace")}
            trigger={<Button kind="primary">Create ▾</Button>}
            items={ITEM_TYPES.map((t) => ({ key: t.kind, label: t.name, description: t.description, icon: <IconTile name={t.icon} color={t.color} size={28} />,
              onSelect: () => setCreating(t) }))} />
        </div>
      </header>
      <div className="st-page-body">
        <SidePanel title="Folders" side="left" collapsed={foldersHidden} onToggle={() => setFoldersHidden(!foldersHidden)} className="st-folders">
          <div className="st-folders-tree"><Tree label="Folders" nodes={nodes} selected={folder} expanded={expanded} onSelect={setFolder} onToggle={toggle}
            renderIcon={(_, sel) => <span style={{ color: sel ? "#ffffff" : "#a16207", display: "inline-flex" }}>
              <svg width="15" height="15" viewBox="0 0 24 24" aria-hidden="true" fill={sel ? "#ffffff" : "#f5c451"} stroke="currentColor" strokeWidth="1.8"><path d="M3 7h6l2 2h10v10H3z" /></svg></span>} /></div>
        </SidePanel>
        <div className="st-items">
          <table className="st-table">
            <thead><tr><th scope="col">Name</th><th scope="col">Type</th><th scope="col">Changed</th></tr></thead>
            <tbody>
              {items.map((n) => (
                <tr key={n.path} onDoubleClick={() => onOpen(n)}>
                  <td>{n.type === "bot"
                    ? <button type="button" className="st-link" onClick={() => onOpen(n)}>{n.name.replace(/\.flow\.json$/, "")}</button>
                    : <span>{n.name}</span>}</td>
                  <td>{typeLabel(n)}</td>
                  <td>{n.modified ? new Date(n.modified).toLocaleString() : ""}</td>
                </tr>
              ))}
              {!items.length && <tr><td colSpan={3} className="st-empty">Nothing here yet. Press Create to make the first one in this folder.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      <CreateDialog type={creating} folderPath={folder} onClose={() => setCreating(null)} onChangeFolder={() => setCreating(null)}
        onCreate={async (kind, name) => { await onCreated(kind, folder, name); setCreating(null); }} />
    </section>
  );
}
