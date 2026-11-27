import * as Dropdown from "@radix-ui/react-dropdown-menu";
import { useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import "./menu.css";

export type MenuItem = { key: string; label: string; description?: string; icon?: ReactNode; disabled?: boolean; shortcut?: string; separatorBefore?: boolean;
  /** Shown in the error colour (Delete and the like). Labels starting with Delete or Remove are danger items anyway. */
  danger?: boolean;
  onSelect?: () => void;
  /** A submenu (14 Nov defects r3): the item opens these instead of acting. */
  items?: MenuItem[] };

/** Where the pointer was when a menu was asked for with a right-click (clientX and clientY). */
export type MenuPoint = { x: number; y: number };

/** The pointer of a right-click, for `Menu`'s `at`; null for the keyboard's menu key (the menu then opens at its button). */
export function pointerOf(e: { clientX?: number; clientY?: number; type?: string; detail?: number }): MenuPoint | null {
  if (e.type !== "contextmenu" || typeof e.clientX !== "number" || typeof e.clientY !== "number") return null;
  // the keyboard's menu key fires a contextmenu without a pointer (0, 0 in most browsers)
  if (e.clientX === 0 && e.clientY === 0) return null;
  return { x: e.clientX, y: e.clientY };
}

const isDanger = (i: MenuItem) => i.danger ?? /^(delete|remove)\b/i.test(i.label);

/**
 * Every pop-up menu (item 3 of the fix list of 27 Nov 2026, round 2, artboards 3a and 3b): one size (12.5 px text, 28 px
 * rows, 200 to 320 px wide), a line between groups, shortcut keys and submenu arrows on the right, Delete in the error
 * colour, and a scroll bar when it is taller than the window. One opening rule: from its button (or ⋮) it opens just
 * below with its right edge on the button's right edge; opened with a right-click (`at`), it opens at the pointer. Where
 * there is no room below it opens above, and it moves in from the window's edge. Radix handles arrow keys, Esc, focus
 * return and outside clicks. When an item was chosen, focus is left to what it opened (a dialog's first field).
 * An item's description shows on hover (and is read out with its name), so every row stays one line.
 */
export function Menu({ trigger, title, items, label, open, onOpenChange, align = "end", at }:
  { trigger: ReactNode; title?: string; items: MenuItem[]; label: string; open?: boolean; onOpenChange?: (open: boolean) => void; align?: "start" | "end";
    /** Opened with a right-click: the pointer, where the menu opens (see pointerOf). */
    at?: MenuPoint | null }) {
  const chosen = useRef(false);
  const atPointer = !!at && open !== false;
  const wasAtPointer = useRef(false);   // how the menu that is closing was opened
  if (open) wasAtPointer.current = atPointer;
  const entry = (i: MenuItem) => <>
    {i.icon}
    <span className="ui-menu-text">{i.label}</span>
    {i.shortcut && <kbd className="ui-menu-key">{i.shortcut}</kbd>}
  </>;
  const cls = (i: MenuItem, extra = "") => "ui-menu-item" + extra + (isDanger(i) ? " is-danger" : "");
  const list = (all: MenuItem[]): ReactNode[] => all.map((i) => [
    i.separatorBefore && <Dropdown.Separator key={i.key + "-sep"} className="ui-menu-sep" />,
    i.items ? <Dropdown.Sub key={i.key}>
        <Dropdown.SubTrigger className={cls(i, " ui-menu-sub")} disabled={i.disabled} title={i.description}
          aria-label={i.description ? `${i.label}, ${i.description}` : i.label} textValue={i.label}>
          {entry(i)}<span className="ui-menu-arrow" aria-hidden="true">▸</span></Dropdown.SubTrigger>
        <Dropdown.Portal><Dropdown.SubContent className="ui-menu" sideOffset={4} collisionPadding={8}>{list(i.items)}</Dropdown.SubContent></Dropdown.Portal>
      </Dropdown.Sub>
      : <Dropdown.Item key={i.key} className={cls(i)} disabled={i.disabled} onSelect={() => { chosen.current = true; i.onSelect?.(); }}
        title={i.description} aria-label={i.description ? `${i.label}, ${i.description}` : i.label} textValue={i.label}>
        {entry(i)}
      </Dropdown.Item>,
  ]);
  return (
    <Dropdown.Root open={open} onOpenChange={(o) => { if (o) chosen.current = false; onOpenChange?.(o); }}>
      {/* a right-click: a point at the pointer is the menu's anchor while it is open; its button stays where it is and
          takes the focus back when the menu closes */}
      {atPointer ? <>
        {trigger}
        {createPortal(<Dropdown.Trigger asChild><span className="ui-menu-at" aria-hidden="true" style={{ left: at!.x, top: at!.y }} /></Dropdown.Trigger>, document.body)}
      </> : <Dropdown.Trigger asChild>{trigger}</Dropdown.Trigger>}
      <Dropdown.Portal>
        <Dropdown.Content className="ui-menu" align={atPointer ? "start" : align} side="bottom" sideOffset={atPointer ? 2 : 4} aria-label={label}
          collisionPadding={8} data-at={atPointer ? "pointer" : "button"}
          onCloseAutoFocus={(e) => {
            if (chosen.current) { e.preventDefault(); return; }
            if (!wasAtPointer.current) return;
            // opened at the pointer: the focus goes back to the menu's own button (the trigger again once closed)
            e.preventDefault();
            const id = (e.target as HTMLElement | null)?.getAttribute?.("aria-labelledby");
            if (id) { document.getElementById(id)?.focus(); setTimeout(() => { if (document.activeElement === document.body) document.getElementById(id)?.focus(); }, 0); }
          }}>
          {title && <Dropdown.Label className="ui-menu-title">{title}</Dropdown.Label>}
          {list(items)}
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
}
