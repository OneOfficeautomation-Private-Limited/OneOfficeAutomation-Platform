import { useLayoutEffect, useState, type CSSProperties, type RefObject } from "react";

/**
 * Where a pop-up goes beside what opened it (item 3 of the fix list of 27 Nov 2026, round 2, artboard 3b): the same rule
 * as the menus (Radix): below the field (or right of an icon), on the side with room; when there is no room below it
 * opens above (left), and it moves in from the window's edges, keeping `pad` pixels clear. For pop-ups that are not
 * menus: Insert a value and the folded menu's fly-out.
 */
export type Box = { left: number; top: number; right: number; bottom: number };
export type PopupSide = "bottom" | "right";
export type PopupPlace = { left: number; top: number; maxHeight: number; side: PopupSide | "top" | "left" };

export function placePopup(anchor: Box, size: { w: number; h: number }, opts: { side?: PopupSide; align?: "start" | "end"; gap?: number; pad?: number } = {},
  view: { w: number; h: number } = { w: window.innerWidth, h: window.innerHeight }): PopupPlace {
  const { side = "bottom", align = "start", gap = 4, pad = 8 } = opts;
  const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(v, Math.max(lo, hi)));
  if (side === "bottom") {
    const below = view.h - pad - (anchor.bottom + gap), above = anchor.top - gap - pad;
    const up = size.h > below && above > below;
    const room = up ? above : below;
    const h = Math.min(size.h, room);
    const top = up ? anchor.top - gap - h : anchor.bottom + gap;
    const left = clamp(align === "end" ? anchor.right - size.w : anchor.left, pad, view.w - pad - size.w);
    return { left: Math.round(left), top: Math.round(Math.max(pad, top)), maxHeight: Math.max(0, Math.round(room)), side: up ? "top" : "bottom" };
  }
  const right = view.w - pad - (anchor.right + gap), leftRoom = anchor.left - gap - pad;
  const flip = size.w > right && leftRoom > right;
  const left = flip ? anchor.left - gap - size.w : anchor.right + gap;
  const room = view.h - 2 * pad;
  const h = Math.min(size.h, room);
  const top = clamp(align === "end" ? anchor.bottom - h : anchor.top, pad, view.h - pad - h);
  return { left: Math.round(Math.max(pad, left)), top: Math.round(top), maxHeight: Math.round(room), side: flip ? "left" : "right" };
}

/**
 * Places a fixed pop-up (`ref`) beside `anchor` once it is drawn, and again when the window changes size. Until then it
 * is drawn hidden, so it never flashes in the wrong place. A pop-up inside a dialog (whose transform moves fixed
 * elements) is corrected by where it really landed.
 */
export function usePopupPlace(ref: RefObject<HTMLElement | null>, anchor: Box | null | undefined, opts: { side?: PopupSide; align?: "start" | "end"; gap?: number; pad?: number } = {}): { style: CSSProperties; side: PopupPlace["side"] } {
  const [place, setPlace] = useState<PopupPlace | null>(null);
  const key = anchor ? [anchor.left, anchor.top, anchor.right, anchor.bottom].join(",") : "";
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || !anchor) return;
    const run = () => {
      const p = placePopup(anchor, { w: el.offsetWidth, h: el.scrollHeight }, opts);
      el.style.left = p.left + "px"; el.style.top = p.top + "px";
      const r = el.getBoundingClientRect();
      const real = r.width > 0;   // (no layout in tests)
      setPlace((old) => {
        const next = { ...p, left: real ? p.left - (r.left - p.left) : p.left, top: real ? p.top - (r.top - p.top) : p.top };
        return old && old.left === next.left && old.top === next.top && old.maxHeight === next.maxHeight ? old : next;
      });
    };
    run();
    // its content changes (a search narrows the list): placed again, so a pop-up above its field stays against it
    const ro = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(run);
    ro?.observe(el);
    window.addEventListener("resize", run);
    return () => { ro?.disconnect(); window.removeEventListener("resize", run); };
  }, [key, opts.side, opts.align]);   // eslint-disable-line react-hooks/exhaustive-deps
  if (!anchor) return { style: {}, side: opts.side ?? "bottom" };
  return place ? { style: { position: "fixed", left: place.left, top: place.top, maxHeight: place.maxHeight }, side: place.side }
    : { style: { position: "fixed", left: 0, top: 0, visibility: "hidden" }, side: opts.side ?? "bottom" };
}
