import * as Dropdown from "@radix-ui/react-dropdown-menu";
import { useRef, type ReactNode } from "react";

export type MenuItem = { key: string; label: string; description?: string; icon?: ReactNode; disabled?: boolean; shortcut?: string; separatorBefore?: boolean;
  onSelect?: () => void;
  /** A submenu (14 Nov defects r3): the item opens these instead of acting. */
  items?: MenuItem[] };

/**
 * A drop-down menu (Create ▾ and the like). Radix handles arrow keys, Esc, focus return and outside clicks. When an
 * item was chosen, focus is left to what it opened (a dialog's first field) instead of going back to the button.
 */
export function Menu({ trigger, title, items, label, open, onOpenChange, align = "end", compact = false }:
  { trigger: ReactNode; title?: string; items: MenuItem[]; label: string; open?: boolean; onOpenChange?: (open: boolean) => void; align?: "start" | "end";
    /** One line per entry (descriptions on hover), tighter rows: for long menus such as a step's (14 Nov defects r3). */
    compact?: boolean }) {
  const chosen = useRef(false);
  const entry = (i: MenuItem) => <>
    {i.icon}
    <span className="ui-menu-text"><strong>{i.label}</strong>{i.description && !compact && <span>{i.description}</span>}</span>
    {i.shortcut && <kbd className="ui-menu-key">{i.shortcut}</kbd>}
  </>;
  const list = (all: MenuItem[]): ReactNode[] => all.map((i) => [
    i.separatorBefore && <Dropdown.Separator key={i.key + "-sep"} className="ui-menu-sep" />,
    i.items ? <Dropdown.Sub key={i.key}>
        <Dropdown.SubTrigger className="ui-menu-item ui-menu-sub" disabled={i.disabled} title={compact ? i.description : undefined}
          aria-label={i.description ? `${i.label}, ${i.description}` : i.label} textValue={i.label}>
          {entry(i)}<span className="ui-menu-arrow" aria-hidden="true">▸</span></Dropdown.SubTrigger>
        <Dropdown.Portal><Dropdown.SubContent className={"ui-menu" + (compact ? " is-compact" : "")} sideOffset={4}>{list(i.items)}</Dropdown.SubContent></Dropdown.Portal>
      </Dropdown.Sub>
      : <Dropdown.Item key={i.key} className="ui-menu-item" disabled={i.disabled} onSelect={() => { chosen.current = true; i.onSelect?.(); }}
        title={compact ? i.description : undefined} aria-label={i.description ? `${i.label}, ${i.description}` : i.label} textValue={i.label}>
        {entry(i)}
      </Dropdown.Item>,
  ]);
  return (
    <Dropdown.Root open={open} onOpenChange={(o) => { if (o) chosen.current = false; onOpenChange?.(o); }}>
      <Dropdown.Trigger asChild>{trigger}</Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content className={"ui-menu" + (compact ? " is-compact" : "")} align={align} sideOffset={6} aria-label={label} collisionPadding={8}
          onCloseAutoFocus={(e) => { if (chosen.current) e.preventDefault(); }}>
          {title && <Dropdown.Label className="ui-menu-title">{title}</Dropdown.Label>}
          {list(items)}
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
}
