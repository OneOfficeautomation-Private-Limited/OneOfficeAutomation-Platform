import * as Dropdown from "@radix-ui/react-dropdown-menu";
import { useRef, type ReactNode } from "react";

export type MenuItem = { key: string; label: string; description?: string; icon?: ReactNode; disabled?: boolean; shortcut?: string; separatorBefore?: boolean; onSelect: () => void };

/**
 * A drop-down menu (Create ▾ and the like). Radix handles arrow keys, Esc, focus return and outside clicks. When an
 * item was chosen, focus is left to what it opened (a dialog's first field) instead of going back to the button.
 */
export function Menu({ trigger, title, items, label, open, onOpenChange, align = "end" }:
  { trigger: ReactNode; title?: string; items: MenuItem[]; label: string; open?: boolean; onOpenChange?: (open: boolean) => void; align?: "start" | "end" }) {
  const chosen = useRef(false);
  return (
    <Dropdown.Root open={open} onOpenChange={(o) => { if (o) chosen.current = false; onOpenChange?.(o); }}>
      <Dropdown.Trigger asChild>{trigger}</Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content className="ui-menu" align={align} sideOffset={6} aria-label={label}
          onCloseAutoFocus={(e) => { if (chosen.current) e.preventDefault(); }}>
          {title && <Dropdown.Label className="ui-menu-title">{title}</Dropdown.Label>}
          {items.map((i) => [
            i.separatorBefore && <Dropdown.Separator key={i.key + "-sep"} className="ui-menu-sep" />,
            <Dropdown.Item key={i.key} className="ui-menu-item" disabled={i.disabled} onSelect={() => { chosen.current = true; i.onSelect(); }}
              aria-label={i.description ? `${i.label}, ${i.description}` : i.label} textValue={i.label}>
              {i.icon}
              <span className="ui-menu-text"><strong>{i.label}</strong>{i.description && <span>{i.description}</span>}</span>
              {i.shortcut && <kbd className="ui-menu-key">{i.shortcut}</kbd>}
            </Dropdown.Item>,
          ])}
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
}
