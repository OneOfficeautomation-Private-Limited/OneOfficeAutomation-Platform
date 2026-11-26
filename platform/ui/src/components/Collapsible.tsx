import type { CSSProperties, ReactNode } from "react";
import { Icon } from "./Icon";

/**
 * A side panel that folds away to a thin strip and back. The button says what it does ("Hide folders",
 * "Show folders"), the strip keeps the panel's name readable, and the choice is the caller's to remember.
 */
export function SidePanel({ title, side, collapsed, onToggle, className, children, label, actions, style, panelRef, as: Tag = "aside" }:
  { title: string; side: "left" | "right"; collapsed: boolean; onToggle: () => void; className?: string; children: ReactNode; label?: string;
    actions?: ReactNode; style?: CSSProperties; panelRef?: (el: HTMLElement | null) => void;
    /** "nav" for a pane that is the page's navigation (a folder tree), so it stays a navigation landmark */
    as?: "aside" | "nav" }) {
  const toward = (side === "left") === collapsed ? "chevronRight" : "chevronLeft";
  const button = (
    <button type="button" className="ui-panel-toggle" aria-expanded={!collapsed} aria-label={(collapsed ? "Show " : "Hide ") + title.toLowerCase()}
      title={(collapsed ? "Show " : "Hide ") + title.toLowerCase()} onClick={onToggle}>
      <Icon name={toward} size={14} stroke={2.6} />
    </button>
  );
  if (collapsed) {
    return (
      <Tag className={`ui-panel-strip ui-panel-strip-${side}`} aria-label={label ?? title}>
        {button}
        <span className="ui-panel-strip-title" aria-hidden="true">{title}</span>
      </Tag>
    );
  }
  return (
    <Tag className={className} aria-label={label ?? title} style={style} ref={panelRef}>
      <div className="ui-panel-bar">
        <strong>{title}</strong>
        {actions}
        {button}
      </div>
      {children}
    </Tag>
  );
}
