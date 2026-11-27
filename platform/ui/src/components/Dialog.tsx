import * as RadixDialog from "@radix-ui/react-dialog";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { IconButton } from "./Button";

/**
 * A dialog that is always easy to leave: the X in the corner, Esc, and the footer's Cancel. Radix keeps focus inside
 * while it is open and returns it to the button that opened it. It opens with the cursor in the first field, so
 * people can type straight away.
 */
/** `help`: the dialog's Docs and Video (round 3, item 6), beside its title. */
/** The size a resizable dialog was left at, by its title (kept in this browser only). */
function savedSize(key: string): { w: number; h: number } | null {
  try { const v = JSON.parse(localStorage.getItem("ooa.dialog.size." + key) ?? "null"); return v && v.w > 0 && v.h > 0 ? v : null; } catch { return null; }
}

/**
 * `resizable` (Studio defects 21 Nov 2026, item 11): a picker dialog that opens bigger, resizes from its corner,
 * maximises from its header and opens at the size it was left at.
 */
export function Dialog({ open, onClose, title, icon, children, footer, width = 560, help, resizable = false, height, className }:
  { open: boolean; onClose: () => void; title: string; icon?: ReactNode; children: ReactNode; footer: ReactNode; width?: number; help?: ReactNode;
    resizable?: boolean; height?: number; className?: string }) {
  const body = useRef<HTMLDivElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const [max, setMax] = useState(false);
  const start = resizable ? savedSize(title) : null;
  // remember the size people drag the corner to
  useEffect(() => {
    if (!resizable || !open || !box.current || typeof ResizeObserver === "undefined") return;
    const el = box.current;
    const ro = new ResizeObserver(() => {
      if (el.classList.contains("is-max")) return;
      try { localStorage.setItem("ooa.dialog.size." + title, JSON.stringify({ w: Math.round(el.offsetWidth), h: Math.round(el.offsetHeight) })); } catch { /* storage off */ }
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [resizable, open, title]);
  const focusFirstField = (e: Event) => {
    // a field marked data-autofocus first (the Create dialog's Name sits right of its other panes), else the first field
    const field = body.current?.querySelector<HTMLElement>("[data-autofocus]") ?? body.current?.querySelector<HTMLElement>("input:not([type=hidden]), select, textarea");
    if (field) { e.preventDefault(); field.focus(); }
  };
  return (
    <RadixDialog.Root open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="ui-overlay" />
        <RadixDialog.Content ref={box} className={"ui-dialog" + (resizable ? " is-resizable" : "") + (max ? " is-max" : "") + (className ? " " + className : "")}
          style={resizable ? { width: start?.w ?? width, height: start?.h ?? height, maxWidth: "calc(100vw - 32px)" } : { maxWidth: width }}
          aria-describedby={undefined} onOpenAutoFocus={focusFirstField}
          onEscapeKeyDown={(e) => {
            // Esc in an open search list closes the list only; the next Esc closes the dialog
            const f = document.activeElement;
            if (f?.getAttribute("role") === "combobox" && f.getAttribute("aria-expanded") === "true") e.preventDefault();
          }}>
          <div className="ui-dialog-head">
            {icon}
            <RadixDialog.Title className="ui-dialog-title">{title}</RadixDialog.Title>
            {help && <span className="ui-dialog-help">{help}</span>}
            {resizable && <IconButton icon={max ? "shrink" : "expand"} label={max ? "Back to its size" : "Maximise"} onClick={() => setMax(!max)} />}
            <RadixDialog.Close asChild><IconButton icon="close" label="Close" /></RadixDialog.Close>
          </div>
          <div className="ui-dialog-body" ref={body}>{children}</div>
          <div className="ui-dialog-foot">{footer}</div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
