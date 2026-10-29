import * as RadixDialog from "@radix-ui/react-dialog";
import { useRef, type ReactNode } from "react";
import { IconButton } from "./Button";

/**
 * A dialog that is always easy to leave: the X in the corner, Esc, and the footer's Cancel. Radix keeps focus inside
 * while it is open and returns it to the button that opened it. It opens with the cursor in the first field, so
 * people can type straight away.
 */
export function Dialog({ open, onClose, title, icon, children, footer, width = 560 }:
  { open: boolean; onClose: () => void; title: string; icon?: ReactNode; children: ReactNode; footer: ReactNode; width?: number }) {
  const body = useRef<HTMLDivElement>(null);
  const focusFirstField = (e: Event) => {
    const field = body.current?.querySelector<HTMLElement>("input:not([type=hidden]), select, textarea");
    if (field) { e.preventDefault(); field.focus(); }
  };
  return (
    <RadixDialog.Root open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="ui-overlay" />
        <RadixDialog.Content className="ui-dialog" style={{ maxWidth: width }} aria-describedby={undefined} onOpenAutoFocus={focusFirstField}
          onEscapeKeyDown={(e) => {
            // Esc in an open search list closes the list only; the next Esc closes the dialog
            const f = document.activeElement;
            if (f?.getAttribute("role") === "combobox" && f.getAttribute("aria-expanded") === "true") e.preventDefault();
          }}>
          <div className="ui-dialog-head">
            {icon}
            <RadixDialog.Title className="ui-dialog-title">{title}</RadixDialog.Title>
            <RadixDialog.Close asChild><IconButton icon="close" label="Close" /></RadixDialog.Close>
          </div>
          <div className="ui-dialog-body" ref={body}>{children}</div>
          <div className="ui-dialog-foot">{footer}</div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
