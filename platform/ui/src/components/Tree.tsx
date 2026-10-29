import { useRef, type KeyboardEvent, type ReactNode } from "react";
import { Icon } from "./Icon";

export type TreeNode = { id: string; label: string; children?: TreeNode[] };

/**
 * A folder tree with the keyboard behaviour people expect: Up and Down move, Right opens or goes in, Left closes or
 * goes to the parent, Enter or Space selects. Only open folders' children are shown.
 */
export function Tree({ nodes, selected, expanded, onSelect, onToggle, label, renderIcon }:
  { nodes: TreeNode[]; selected: string; expanded: Set<string>; onSelect: (id: string) => void; onToggle: (id: string) => void; label: string;
    renderIcon?: (n: TreeNode, isSelected: boolean) => ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const visible: { node: TreeNode; depth: number; parent: string | null }[] = [];
  const walk = (list: TreeNode[], depth: number, parent: string | null) => {
    for (const n of list) {
      visible.push({ node: n, depth, parent });
      if (n.children && expanded.has(n.id)) walk(n.children, depth + 1, n.id);
    }
  };
  walk(nodes, 0, null);

  const focus = (id: string) => ref.current?.querySelector<HTMLElement>(`[data-id="${CSS.escape(id)}"]`)?.focus();
  const onKey = (e: KeyboardEvent, i: number) => {
    const { node, parent } = visible[i];
    const open = expanded.has(node.id), hasKids = !!node.children?.length;
    if (e.key === "ArrowDown" && i + 1 < visible.length) focus(visible[i + 1].node.id);
    else if (e.key === "ArrowUp" && i > 0) focus(visible[i - 1].node.id);
    else if (e.key === "ArrowRight" && hasKids) { if (!open) onToggle(node.id); else focus(node.children![0].id); }
    else if (e.key === "ArrowLeft") { if (open) onToggle(node.id); else if (parent) focus(parent); }
    else if (e.key === "Enter" || e.key === " ") onSelect(node.id);
    else return;
    e.preventDefault();
  };

  return (
    <div role="tree" aria-label={label} className="ui-tree" ref={ref}>
      {visible.map(({ node, depth }, i) => {
        const isSel = node.id === selected, hasKids = !!node.children?.length, open = expanded.has(node.id);
        return (
          <div key={node.id} role="treeitem" data-id={node.id} aria-level={depth + 1} aria-selected={isSel}
            aria-expanded={hasKids ? open : undefined} tabIndex={isSel || (!selected && i === 0) ? 0 : -1}
            className={`ui-tree-item${isSel ? " is-selected" : ""}`} style={{ paddingLeft: 6 + depth * 18 }}
            onClick={() => onSelect(node.id)} onKeyDown={(e) => onKey(e, i)}>
            <span className="ui-tree-caret" onClick={(e) => { e.stopPropagation(); if (hasKids) onToggle(node.id); }}>
              {hasKids && <Icon name={open ? "chevronDown" : "chevronRight"} size={12} stroke={2.6} />}
            </span>
            {renderIcon?.(node, isSel)}
            <span className="ui-tree-label">{node.label}</span>
          </div>
        );
      })}
    </div>
  );
}
