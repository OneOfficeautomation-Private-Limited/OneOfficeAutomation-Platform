import { useEffect, useLayoutEffect, useRef, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";
import "./filters.css";

/**
 * The panel under a filter button (the date filter and the multi-choice filter, item 2): drawn over the page, kept
 * inside the window (at 375 px it takes the window's width), closed by Esc or a click outside, and the focus goes back
 * to the button when it closes.
 */
export function FilterPopover({ anchor, label, onClose, children, className }: { anchor: RefObject<HTMLElement | null>; label: string; onClose: () => void;
  children: ReactNode; className?: string }) {
  const box = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useLayoutEffect(() => {
    const place = () => {
      const a = anchor.current?.getBoundingClientRect(), b = box.current;
      if (!a || !b) return;
      const w = Math.min(b.offsetWidth, window.innerWidth - 16);
      b.style.left = Math.max(8, Math.min(a.left, window.innerWidth - w - 8)) + "px";
      const below = a.bottom + 4, h = b.offsetHeight;
      b.style.top = (below + h > window.innerHeight - 8 && a.top - h - 4 > 8 ? a.top - h - 4 : below) + "px";
    };
    place();
    addEventListener("resize", place);
    addEventListener("scroll", place, true);
    return () => { removeEventListener("resize", place); removeEventListener("scroll", place, true); };
  }, [anchor]);
  useEffect(() => {
    const outside = (e: MouseEvent) => {
      const t = e.target as Node;
      if (box.current?.contains(t) || anchor.current?.contains(t)) return;
      close.current();
    };
    document.addEventListener("mousedown", outside);
    return () => document.removeEventListener("mousedown", outside);
  }, [anchor]);
  useEffect(() => () => { anchor.current?.focus(); }, [anchor]);   // eslint-disable-line react-hooks/exhaustive-deps
  return createPortal(
    <div ref={box} className={"ui-fpop" + (className ? " " + className : "")} role="dialog" aria-label={label}
      onKeyDown={(e) => { if (e.key === "Escape") { e.stopPropagation(); e.preventDefault(); close.current(); } }}>
      {children}
    </div>, document.body);
}
