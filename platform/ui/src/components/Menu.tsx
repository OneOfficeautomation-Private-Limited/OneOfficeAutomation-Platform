import * as Dropdown from "@radix-ui/react-dropdown-menu";
import { useRef, type ReactNode } from "react";

export type MenuItem = { key: string; label: string; description?: string; icon?: ReactNode; disabled?: boolean; onSelect: () => void };

/**
 * A drop-down menu (Create ▾ and the like). Radix handles arrow keys, Esc, focus return and outside clicks. When an
 * item was chosen, focus is left to what it opened (a dialog's first field) instead of going back to the button.
 */
export function Menu({ trigger, title, items, label }: { trigger: ReactNode; title?: string; items: MenuItem[]; label: string }) {
  const chosen = useRef(false);
  return (
    <Dropdown.Root onOpenChange={(open) => { if (open) chosen.current = false; }}>
      <Dropdown.Trigger asChild>{trigger}</Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content className="ui-menu" align="end" sideOffset={6} aria-label={label}
          onCloseAutoFocus={(e) => { if (chosen.current) e.preventDefault(); }}>
          {title && <Dropdown.Label className="ui-menu-title">{title}</Dropdown.Label>}
          {items.map((i) => (
            <Dropdown.Item key={i.key} className="ui-menu-item" disabled={i.disabled} onSelect={() => { chosen.current = true; i.onSelect(); }}
              aria-label={i.description ? `${i.label}, ${i.description}` : i.label} textValue={i.label}>
              {i.icon}
              <span className="ui-menu-text"><strong>{i.label}</strong>{i.description && <span>{i.description}</span>}</span>
            </Dropdown.Item>
          ))}
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
}
