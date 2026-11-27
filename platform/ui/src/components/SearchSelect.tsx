import { Fragment, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { Icon } from "./Icon";
import { usePopupPlace, type Box } from "./placePopup";
import { usePersistent } from "./usePersistent";
import "./searchselect.css";

export type SearchOption = { value: string; label: string; description?: string; group?: string };

/** A row of the list, in pixels (the list's heights are counted in rows). */
const ROW = 28;
/** The short view: eight rows. */
export const SHORT_ROWS = 8;
const shortHeight = () => SHORT_ROWS * ROW + 8;
const tallHeight = () => Math.max(shortHeight(), Math.round((typeof window === "undefined" ? 800 : window.innerHeight) * 0.8));

/**
 * A choice from a long list that people can search (item 3 of the fix list of 27 Nov 2026, round 2, artboard 3d): type to
 * filter (name, description and group), then click, or use Up, Down and Enter; Esc closes the list and keeps the choice.
 * Options with a group are listed under its heading. Under the list: how many are shown ("5 of 64 variables"), ⤢ to
 * switch between a short view (8 rows) and a tall one (most of the window), and a grip to drag its height; the height is
 * remembered per kind of list (`kind`). The list opens below the field (above when there is no room), as the menus do.
 * It follows the ARIA combobox pattern, so screen readers announce the list and the highlighted option.
 */
export function SearchSelect({ id, value, options, onChange, placeholder = "Type to search", disabled, kind = "list", noun = "choices", className,
  "aria-label": ariaLabel }:
  { id?: string; value: string; options: SearchOption[]; onChange: (value: string) => void; placeholder?: string; disabled?: boolean;
    /** Its height is remembered under this name (variables, time zones…). */
    kind?: string;
    /** What the options are, for the count under the list ("variables"). */
    noun?: string; className?: string; "aria-label"?: string }) {
  const listId = useId();
  const input = useRef<HTMLInputElement>(null);
  const field = useRef<HTMLDivElement>(null);
  const pop = useRef<HTMLDivElement>(null);
  const chosen = options.find((o) => o.value === value) ?? null;
  const [query, setQuery] = useState<string | null>(null);   // null: not searching, the field shows the choice
  const [active, setActive] = useState(0);
  const [anchor, setAnchor] = useState<Box | null>(null);
  const [saved, setSaved] = usePersistent<number | null>("list.height." + kind, null);
  const height = saved ?? shortHeight();
  const tall = height > shortHeight() + ROW;
  const open = query !== null;
  const shown = useMemo(() => {
    const q = (query ?? "").trim().toLowerCase();
    return q ? options.filter((o) => (o.label + " " + (o.description ?? "") + " " + (o.group ?? "")).toLowerCase().includes(q)) : options;
  }, [options, query]);
  const place = usePopupPlace(pop, open ? anchor : null, { side: "bottom", align: "start", gap: 2 });

  // the field's place, again when the page scrolls under it or the window changes size
  useEffect(() => {
    if (!open) return;
    const measure = () => { const r = field.current?.getBoundingClientRect(); if (r) setAnchor({ left: r.left, top: r.top, right: r.right, bottom: r.bottom }); };
    measure();
    window.addEventListener("scroll", measure, true);
    window.addEventListener("resize", measure);
    return () => { window.removeEventListener("scroll", measure, true); window.removeEventListener("resize", measure); };
  }, [open]);
  // the highlighted option stays in view
  useEffect(() => { if (open) document.getElementById(listId + "-" + active)?.scrollIntoView?.({ block: "nearest" }); }, [open, active, listId]);

  const pick = (o: SearchOption) => { onChange(o.value); setQuery(null); };
  const show = () => { if (!open && !disabled) { setQuery(""); setActive(Math.max(0, chosen ? options.indexOf(chosen) : 0)); } };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    const page = Math.max(1, Math.floor(height / ROW) - 1);
    if (e.key === "ArrowDown" || e.key === "PageDown") { if (!open) { show(); e.preventDefault(); return; } setActive((a) => Math.min(a + (e.key === "PageDown" ? page : 1), shown.length - 1)); }
    else if (e.key === "ArrowUp" || e.key === "PageUp") setActive((a) => Math.max(a - (e.key === "PageUp" ? page : 1), 0));
    else if (e.key === "Enter" && open) { if (shown[active]) pick(shown[active]); }
    else if (e.key === "Escape" && open) { e.stopPropagation(); setQuery(null); }   // closes the list, not the dialog
    else return;
    e.preventDefault();
  };
  // the grip: drag to set the height (the edge away from the field), or arrow keys on it
  const clampH = (h: number) => Math.round(Math.max(3 * ROW + 8, Math.min(h, window.innerHeight - 16)));
  const above = place.side === "top";
  const drag = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const el = e.currentTarget, from = e.clientY, start = pop.current?.querySelector<HTMLElement>(".ui-ss-list")?.offsetHeight || height;
    el.setPointerCapture?.(e.pointerId);
    const move = (ev: globalThis.PointerEvent) => setSaved(clampH(start + (above ? from - ev.clientY : ev.clientY - from)));
    const up = () => { el.removeEventListener("pointermove", move); el.removeEventListener("pointerup", up); el.removeEventListener("pointercancel", up); };
    el.addEventListener("pointermove", move); el.addEventListener("pointerup", up); el.addEventListener("pointercancel", up);
  };
  const gripKeys = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 4 * ROW : ROW;
    const grow = e.key === (above ? "ArrowUp" : "ArrowDown"), shrink = e.key === (above ? "ArrowDown" : "ArrowUp");
    if (!grow && !shrink) return;
    e.preventDefault();
    setSaved(clampH(height + (grow ? step : -step)));
  };
  const grip = <div className="ui-ss-grip" role="separator" aria-orientation="horizontal" tabIndex={-1} aria-label="List height (drag)"
    aria-valuenow={height} title="Drag to make the list taller or shorter" onPointerDown={drag} onKeyDown={gripKeys} />;

  let lastGroup: string | undefined;
  return (
    <div className={"ui-search-select" + (className ? " " + className : "")}>
      <div className={"ui-search-select-field" + (disabled ? " is-disabled" : "")} ref={field}>
        <Icon name="search" size={14} />
        <input id={id} ref={input} role="combobox" aria-expanded={open} aria-controls={listId} aria-autocomplete="list" aria-label={ariaLabel}
          aria-activedescendant={open && shown[active] ? listId + "-" + active : undefined} disabled={disabled} data-value={value}
          value={open ? query! : chosen?.label ?? ""} placeholder={placeholder} autoComplete="off" title={!open ? chosen?.label : undefined}
          onFocus={show} onClick={show}
          onChange={(e) => { setQuery(e.target.value); setActive(0); }}
          onBlur={() => setQuery(null)} onKeyDown={onKey} />
        <button type="button" tabIndex={-1} aria-label="Show all" className="ui-search-select-open" disabled={disabled}
          onMouseDown={(e) => { e.preventDefault(); input.current?.focus(); show(); }}><Icon name="chevronDown" size={14} stroke={2.4} /></button>
      </div>
      {open && (
        <div className={"ui-ss-pop" + (above ? " is-above" : "")} ref={pop} data-side={place.side}
          style={{ ...place.style, width: anchor ? Math.max(240, Math.round(anchor.right - anchor.left)) : undefined }}
          onMouseDown={(e) => e.preventDefault()}>
          {above && grip}
          <ul id={listId} role="listbox" aria-label={(ariaLabel ?? "Choices") + ": " + noun} className="ui-ss-list" style={{ height: Math.min(height, Math.max(ROW + 8, shown.length * ROW + 8 + groupCount(shown) * 24)) }}>
            {shown.map((o, i) => {
              const head = o.group && o.group !== lastGroup ? o.group : null;
              lastGroup = o.group;
              return <Fragment key={o.value}>
                {head && <li className="ui-ss-group" role="presentation">{head}</li>}
                <li id={listId + "-" + i} role="option" aria-selected={o.value === value} data-value={o.value} title={o.description ? o.label + " · " + o.description : o.label}
                  className={"ui-ss-option" + (i === active ? " is-active" : "") + (o.value === value ? " is-chosen" : "")}
                  onMouseDown={(e) => { e.preventDefault(); pick(o); }} onMouseEnter={() => setActive(i)}>
                  <span className="ui-ss-label">{o.label}</span>{o.description && <span className="ui-ss-desc">{o.description}</span>}
                </li>
              </Fragment>;
            })}
            {!shown.length && <li className="ui-ss-none" role="presentation">Nothing matches “{query}”.</li>}
          </ul>
          <div className="ui-ss-foot">
            <span className="ui-ss-count" aria-live="polite">{shown.length === options.length ? options.length + " " + noun : shown.length + " of " + options.length + " " + noun}</span>
            <button type="button" tabIndex={-1} className="ui-ss-size" aria-pressed={tall} aria-label={tall ? "Short list" : "Tall list"} title={tall ? "Short list (8 rows)" : "Tall list (most of the window)"}
              onClick={() => setSaved(tall ? null : tallHeight())}><Icon name={tall ? "shrink" : "expand"} size={13} stroke={2.4} /></button>
          </div>
          {!above && grip}
        </div>
      )}
    </div>
  );
}

const groupCount = (list: SearchOption[]) => new Set(list.map((o) => o.group).filter(Boolean)).size;
