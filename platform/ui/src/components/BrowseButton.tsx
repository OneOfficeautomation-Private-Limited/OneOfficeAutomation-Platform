import type { ReactNode } from "react";
import { Button } from "./Button";
import { Menu } from "./Menu";
import "./pickers.css";

/** Where a path can be chosen from (This computer, One CommandCenter, the agent's files...). */
export type BrowseSource = { key: string; label: string; description?: string; onSelect: () => void };

/**
 * AA's Browse (pickers, 14 Nov 2026): a button on the right of a file or folder field. With one place to choose from
 * it opens that place; with more, a short menu asks where the file comes from first.
 */
export function BrowseButton({ sources, label = "Browse…", disabled, list, title }: { sources: BrowseSource[]; label?: string; disabled?: boolean;
  /** The sources are the choices themselves (files to pick): always shown as a menu, even one. */
  list?: boolean; title?: string }) {
  if (!sources.length) return null;
  if (sources.length === 1 && !list)
    return <Button kind="secondary" icon="folder" className="ui-browse" aria-label={label} title={sources[0].description ?? sources[0].label}
      disabled={disabled} onClick={sources[0].onSelect}>Browse…</Button>;
  return <Menu label={label} title={title} align="end" items={sources.map((s) => ({ key: s.key, label: s.label, description: s.description, onSelect: s.onSelect }))}
    trigger={<Button kind="secondary" icon="folder" className="ui-browse" aria-label={label} disabled={disabled}>Browse… ▾</Button>} />;
}

/** A path field with Browse… on its right: the field keeps the row's width, the button its own. */
export function PathRow({ children }: { children: ReactNode }) {
  return <div className="ui-path-row">{children}</div>;
}
