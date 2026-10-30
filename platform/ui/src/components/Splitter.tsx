import type { KeyboardEvent, PointerEvent } from "react";
import { usePersistent } from "./usePersistent";

/** A panel's size as the person set it with its splitter, remembered in this browser; null until then (the layout's own size). */
export const usePanelSize = (key: string) => usePersistent<number | null>("size." + key, null);

/**
 * A panel edge that resizes the panel: drag it, or focus it and use the arrow keys (Home and End for the smallest
 * and largest size); a double-click puts the layout's own size back (null). `grow` says which way makes the panel
 * larger: 1 when the panel is left of (or above) the edge, -1 when it is right of (or below) it. `measure` reads the
 * panel's size on screen, used until the person has set one.
 */
export function Splitter({ label, axis, grow, value, min, max, measure, onChange }:
  { label: string; axis: "x" | "y"; grow: 1 | -1; value: number | null; min: number; max: number; measure: () => number; onChange: (v: number | null) => void }) {
  const clamp = (v: number) => Math.round(Math.max(min, Math.min(max, v)));
  const down = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const el = e.currentTarget, start = value ?? measure(), from = axis === "x" ? e.clientX : e.clientY;
    el.setPointerCapture?.(e.pointerId);
    document.body.classList.add("ui-resizing");
    const move = (ev: globalThis.PointerEvent) => onChange(clamp(start + grow * ((axis === "x" ? ev.clientX : ev.clientY) - from)));
    const up = () => { el.removeEventListener("pointermove", move); el.removeEventListener("pointerup", up); el.removeEventListener("pointercancel", up); document.body.classList.remove("ui-resizing"); };
    el.addEventListener("pointermove", move); el.addEventListener("pointerup", up); el.addEventListener("pointercancel", up);
  };
  const keys = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 64 : 16;
    const more = axis === "x" ? "ArrowRight" : "ArrowDown", less = axis === "x" ? "ArrowLeft" : "ArrowUp";
    const now = value ?? measure();
    const next = e.key === more ? now + grow * step : e.key === less ? now - grow * step : e.key === "Home" ? min : e.key === "End" ? max : null;
    if (next === null) return;
    e.preventDefault();
    onChange(clamp(next));
  };
  return (
    <div role="separator" tabIndex={0} aria-label={label} aria-orientation={axis === "x" ? "vertical" : "horizontal"}
      aria-valuenow={value ?? undefined} aria-valuemin={min} aria-valuemax={max} title={label + " (drag, or arrow keys; double-click to reset)"}
      className={`ui-splitter ui-splitter-${axis}`} onPointerDown={down} onKeyDown={keys} onDoubleClick={() => onChange(null)} />
  );
}
