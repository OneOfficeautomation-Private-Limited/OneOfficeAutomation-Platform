import { useMemo, useRef, useState, type ReactNode } from "react";
import { Button } from "./Button";
import { Dialog } from "./Dialog";
import { Icon } from "./Icon";
import { Splitter, usePanelSize } from "./Splitter";
import { Tree, type TreeNode } from "./Tree";
import "./picker-layout.css";

/** Every folder id of a tree (to open them all). */
export function allIds(nodes: TreeNode[], out = new Set<string>()): Set<string> {
  for (const n of nodes) { if (n.children?.length) { out.add(n.id); allIds(n.children, out); } }
  return out;
}

/** The folders whose name has `q` in it, with the folders above them (opened, so they show). */
export function filterTree(nodes: TreeNode[], q: string): { nodes: TreeNode[]; open: Set<string> } {
  const open = new Set<string>();
  const want = q.trim().toLowerCase();
  const walk = (list: TreeNode[]): TreeNode[] => list.flatMap((n) => {
    const kids = walk(n.children ?? []);
    if (kids.length) open.add(n.id);
    return n.label.toLowerCase().includes(want) || kids.length ? [{ ...n, children: kids.length ? kids : n.children?.length ? [] : undefined }] : [];
  });
  return { nodes: want ? walk(nodes) : nodes, open };
}

/**
 * Expand all and Collapse all, for a folder tree or a list of groups (item 3 of the fix list of 27 Nov 2026, round 2,
 * 3e). `onExpandAll` and `onCollapseAll` do it; the buttons are small, beside the tree's find box or panel title.
 */
export function TreeTools({ onExpandAll, onCollapseAll, what = "folders" }: { onExpandAll: () => void; onCollapseAll: () => void; what?: string }) {
  return (
    <span className="ui-tree-tools" role="group" aria-label={"Open or close all " + what}>
      <button type="button" className="ui-tree-tool" aria-label={"Expand all " + what} title={"Expand all " + what} onClick={onExpandAll}>
        <Icon name="chevronDown" size={12} stroke={2.6} /><span>Expand all</span></button>
      <button type="button" className="ui-tree-tool" aria-label={"Collapse all " + what} title={"Collapse all " + what} onClick={onCollapseAll}>
        <Icon name="chevronRight" size={12} stroke={2.6} /><span>Collapse all</span></button>
    </span>
  );
}

export type PickerTreeProps = {
  nodes: TreeNode[]; selected: string; onSelect: (id: string) => void; expanded: Set<string>; onExpanded: (open: Set<string>) => void; label: string;
  /** What a folder shows on hover: its full path. */
  pathOf?: (n: TreeNode) => string; renderIcon?: (n: TreeNode, isSelected: boolean) => ReactNode;
};

/** The folder tree of a picker: a Find a folder box, Expand all and Collapse all, and full names (two lines when long). */
export function PickerTree({ nodes, selected, onSelect, expanded, onExpanded, label, pathOf, renderIcon }: PickerTreeProps) {
  const [find, setFind] = useState("");
  const found = useMemo(() => filterTree(nodes, find), [nodes, find]);
  const searching = !!find.trim();
  const toggle = (id: string) => { const n = new Set(expanded); if (n.has(id)) n.delete(id); else n.add(id); onExpanded(n); };
  return (
    <div className="ui-picker-tree">
      <div className="ui-picker-tree-tools">
        <input type="search" className="ui-input" aria-label="Find a folder" placeholder="Find a folder" value={find} onChange={(e) => setFind(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Escape" && find) { e.preventDefault(); e.stopPropagation(); setFind(""); } }} />
        <TreeTools onExpandAll={() => { setFind(""); onExpanded(allIds(nodes)); }} onCollapseAll={() => { setFind(""); onExpanded(new Set(nodes.map((n) => n.id))); }} />
      </div>
      <div className="ui-picker-tree-scroll">
        <Tree label={label} nodes={found.nodes} selected={selected} expanded={searching ? found.open : expanded} indent={16} wrap
          titleOf={pathOf} renderIcon={renderIcon} onToggle={toggle}
          onSelect={(id) => { onSelect(id); if (!expanded.has(id)) onExpanded(new Set(expanded).add(id)); }} />
        {searching && !found.nodes.length && <p className="ui-picker-empty">No folder matches “{find.trim()}”.</p>}
      </div>
    </div>
  );
}

/**
 * Every picker dialog (item 3 of the fix list of 27 Nov 2026, round 2, artboard 3c): the title with Maximise and Close;
 * a tool bar (Browse or Search all folders, a search box named for where it looks, how many items); the folder tree on
 * the left, widened with its drag bar (remembered per picker, `id`), and the list on the right; at the foot what is
 * chosen, Cancel and Choose. Resizable and maximisable; it opens at the size it was left at.
 */
export function PickerDialog({ id, title, width = 860, height = 540, onClose, chosen, chosenExtra, chooseLabel = "Choose", canChoose, onChoose,
  footerStart, mode, onMode, search, count, tree, side, sideLabel = "Folders", top, children }: {
  /** Remembers the tree's width under this name. */
  id: string; title: string; width?: number; height?: number; onClose: () => void;
  /** Where the chosen item is, as people read it (Agents › Finance › Invoice). */
  chosen?: string; chosenExtra?: ReactNode; chooseLabel?: string; canChoose: boolean; onChoose: () => void; footerStart?: ReactNode;
  /** Browse a folder or Search every folder; without onMode there is no switch. */
  mode?: "browse" | "search"; onMode?: (m: "browse" | "search") => void;
  search?: { value: string; onChange: (v: string) => void; label: string; placeholder?: string };
  /** "12 agents" and the like. */
  count?: string;
  tree?: PickerTreeProps; side?: ReactNode; sideLabel?: string;
  /** A line above the tool bar (a hint, a problem). */
  top?: ReactNode; children: ReactNode }) {
  const [w, setW] = usePanelSize("picker." + id);
  const sideRef = useRef<HTMLDivElement>(null);
  const showSide = (tree || side) && mode !== "search";
  return (
    <Dialog open title={title} width={width} height={height} resizable className="ui-picker-dialog" onClose={onClose} footer={<>
      {footerStart}
      <span className="ui-picker-chosen" aria-live="polite">
        {chosen ? <span className="ui-picker-chosen-path" title={chosen}><span className="ui-picker-chosen-label">Chosen:</span> <b>{chosen}</b></span>
          : <span className="ui-picker-chosen-label">Nothing chosen yet</span>}
        {chosenExtra}
      </span>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!canChoose} onClick={onChoose}>{chooseLabel}</Button></>}>
      <div className="ui-picker">
        {top}
        {(onMode || search || count) && <div className="ui-picker-bar">
          {onMode && <div className="ui-picker-seg" role="radiogroup" aria-label="Find by">
            <button type="button" role="radio" aria-checked={mode !== "search"} onClick={() => onMode("browse")}>Browse</button>
            <button type="button" role="radio" aria-checked={mode === "search"} onClick={() => onMode("search")}>Search all folders</button>
          </div>}
          {search && <span className="ui-picker-search"><Icon name="search" size={13} />
            <input type="search" className="ui-input" aria-label={search.label} placeholder={search.placeholder ?? search.label} value={search.value}
              onChange={(e) => search.onChange(e.target.value)} /></span>}
          {count && <span className="ui-picker-count" aria-live="polite">{count}</span>}
        </div>}
        <div className={"ui-picker-body" + (showSide ? "" : " is-list-only")}>
          {showSide && <>
            <div className="ui-picker-side" ref={sideRef} style={w ? { width: w } : undefined} role="group" aria-label={sideLabel}>
              {tree && <PickerTree {...tree} />}
              {side}
            </div>
            <Splitter label={"Resize the " + sideLabel.toLowerCase() + " pane"} axis="x" grow={1} value={w} min={160} max={560}
              measure={() => sideRef.current?.getBoundingClientRect().width ?? 240} onChange={setW} />
          </>}
          <div className="ui-picker-list">{children}</div>
        </div>
      </div>
    </Dialog>
  );
}
