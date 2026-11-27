import { useMemo, useRef, useState, type KeyboardEvent } from "react";
import { FilterPopover } from "./FilterPopover";
import { AHEAD, BACK, RANGE_NAMES, firstWeekday, isoDay, localTime, monthWeeks, rangeLabel, resolveRange, type DateRangeValue, type RangeKey } from "./rangeRules";

/**
 * The one date filter of every page that filters by time (tables and filters, item 2b, 27 Nov 2026). The button shows
 * the choice and its days ("Last 7 days Â· 21 Nov â€“ 27 Nov â–¾"); its panel has the quick choices on the left and a month
 * calendar with From and To (times optional) on the right, then Cancel and Apply. Looking back, days still to come are
 * dimmed; looking ahead (upcoming runs), days gone are. Keys: arrows move on the calendar, Enter picks the day, Esc closes.
 */
export function DateRange({ value, onChange, choices, ahead = false, label = "Dates", now: nowAt }: {
  value: DateRangeValue; onChange: (v: DateRangeValue) => void; choices?: RangeKey[]; ahead?: boolean; label?: string; now?: Date }) {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const now = nowAt ?? new Date();
  const text = rangeLabel(value, now);
  return (
    <>
      <button ref={button} type="button" className={"ui-fbtn" + (value.key !== "any" ? " is-set" : "")} aria-haspopup="dialog" aria-expanded={open}
        aria-label={label + ": " + text} title={label + ": " + text} onClick={() => setOpen(!open)}>
        <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 10h18M8 3v4M16 3v4" /></svg>
        <span className="ui-fbtn-text">{text}</span><span className="ui-fbtn-caret" aria-hidden="true">â–¾</span>
      </button>
      {open && <FilterPopover anchor={button} label={label} onClose={() => setOpen(false)} className="ui-dr-pop">
        <RangePanel value={value} choices={choices ?? (ahead ? AHEAD : BACK)} ahead={ahead} now={now}
          onCancel={() => setOpen(false)} onApply={(v) => { setOpen(false); onChange(v); }} />
      </FilterPopover>}
    </>
  );
}

type Draft = { key: RangeKey; from: string; fromTime: string; to: string; toTime: string };
const draftOf = (v: DateRangeValue, now: Date): Draft => {
  if (v.key === "custom") {
    const [from = "", fromTime = ""] = (v.from ?? "").split("T"), [to = "", toTime = ""] = (v.to ?? "").split("T");
    return { key: v.key, from, fromTime, to, toTime };
  }
  const r = resolveRange(v, now);
  return { key: v.key, from: r.from ? isoDay(r.from) : "", fromTime: "", to: r.to ? isoDay(new Date(r.to.getTime() - 1)) : "", toTime: "" };
};

/** The panel's inside: quick choices, calendar, From and To, Cancel and Apply (exported for its tests). */
export function RangePanel({ value, choices, ahead, now, onApply, onCancel }: { value: DateRangeValue; choices: RangeKey[]; ahead: boolean; now: Date;
  onApply: (v: DateRangeValue) => void; onCancel: () => void }) {
  const [d, setD] = useState<Draft>(() => draftOf(value, now));
  const [error, setError] = useState<string | null>(null);
  const shown0 = localTime(ahead ? d.from || d.to : d.to || d.from) ?? now;
  const [month, setMonth] = useState({ y: shown0.getFullYear(), m: shown0.getMonth() });
  const [focus, setFocus] = useState<string>(isoDay(localTime(ahead ? d.from || d.to : d.to || d.from) ?? now));
  const grid = useRef<HTMLTableElement>(null);
  const weekStart = useMemo(() => firstWeekday(), []);
  const weeks = monthWeeks(month.y, month.m, weekStart);
  const today = isoDay(now);
  const blocked = (day: string) => ahead ? day < today : day > today;

  const pick = (k: RangeKey) => {
    setError(null);
    if (k === "custom") { setD({ ...d, key: "custom" }); return; }
    const next = draftOf({ key: k }, now);
    setD(next);
    const show = localTime(ahead ? next.from || next.to : next.to || next.from);
    if (show) { setMonth({ y: show.getFullYear(), m: show.getMonth() }); setFocus(isoDay(show)); }
  };
  /** A day clicked: the first starts a new range, the second ends it (an earlier day starts it again). */
  const clickDay = (day: string) => {
    if (blocked(day)) return;
    setError(null);
    setFocus(day);
    if (d.key !== "custom" || !d.from || (d.from && d.to)) setD({ key: "custom", from: day, fromTime: "", to: "", toTime: "" });
    else if (day < d.from) setD({ ...d, from: day, to: d.from });
    else setD({ ...d, to: day });
  };
  const moveFocus = (to: Date) => {
    const day = isoDay(to);
    setFocus(day);
    if (to.getMonth() !== month.m || to.getFullYear() !== month.y) setMonth({ y: to.getFullYear(), m: to.getMonth() });
    requestAnimationFrame(() => grid.current?.querySelector<HTMLButtonElement>('[data-day="' + day + '"]')?.focus());
  };
  const onGridKey = (e: KeyboardEvent) => {
    const at = localTime(focus);
    if (!at) return;
    const step: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 };
    if (e.key in step) { e.preventDefault(); moveFocus(new Date(at.getFullYear(), at.getMonth(), at.getDate() + step[e.key])); }
    else if (e.key === "PageUp" || e.key === "PageDown") { e.preventDefault(); moveFocus(new Date(at.getFullYear(), at.getMonth() + (e.key === "PageUp" ? -1 : 1), at.getDate())); }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); clickDay(focus); }
  };
  const apply = () => {
    if (d.key !== "custom") { onApply({ key: d.key }); return; }
    if (!d.from && !d.to) { setError("Choose a day, or type a From or To date."); return; }
    const from = d.from ? d.from + (d.fromTime ? "T" + d.fromTime : "") : undefined, to = d.to ? d.to + (d.toTime ? "T" + d.toTime : "") : undefined;
    if ((d.from && !localTime(from)) || (d.to && !localTime(to))) { setError("That is not a date."); return; }
    const r = resolveRange({ key: "custom", from, to });
    if (r.from && r.to && r.from >= r.to) { setError("From must be before To."); return; }
    onApply({ key: "custom", ...(from ? { from } : {}), ...(to ? { to } : {}) });
  };
  const edit = (k: keyof Draft, v: string) => { setError(null); setD({ ...d, [k]: v, key: "custom" }); const t = localTime(v); if (t && (k === "from" || k === "to")) setMonth({ y: t.getFullYear(), m: t.getMonth() }); };
  const monthName = new Date(month.y, month.m, 1).toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const weekdays = weeks[0].map((w) => w.toLocaleDateString(undefined, { weekday: "short" }));
  const inRange = (day: string) => !!d.from && !!d.to && day >= d.from && day <= d.to;

  return (
    <div className="ui-dr">
      <div className="ui-dr-choices" role="radiogroup" aria-label="Quick choices">
        {choices.map((k) => <button key={k} type="button" role="radio" aria-checked={d.key === k} className={"ui-dr-choice" + (d.key === k ? " is-on" : "")}
          onClick={() => pick(k)}>{RANGE_NAMES[k]}</button>)}
      </div>
      <div className="ui-dr-side">
        <div className="ui-dr-month">
          <button type="button" className="ui-dr-nav" aria-label="Previous month" onClick={() => setMonth(month.m ? { y: month.y, m: month.m - 1 } : { y: month.y - 1, m: 11 })}>â€¹</button>
          <strong aria-live="polite">{monthName}</strong>
          <button type="button" className="ui-dr-nav" aria-label="Next month" onClick={() => setMonth(month.m === 11 ? { y: month.y + 1, m: 0 } : { y: month.y, m: month.m + 1 })}>â€º</button>
        </div>
        <table ref={grid} className="ui-dr-cal cc-bare" role="grid" aria-label={monthName} onKeyDown={onGridKey}>
          <thead><tr>{weekdays.map((w) => <th key={w} scope="col" abbr={w}>{w.slice(0, 2)}</th>)}</tr></thead>
          <tbody>{weeks.map((w) => <tr key={isoDay(w[0])}>{w.map((day) => {
            const k = isoDay(day), other = day.getMonth() !== month.m;
            const cls = ["ui-dr-day", other ? "is-other" : "", blocked(k) ? "is-blocked" : "", inRange(k) ? "is-in" : "", k === d.from || k === d.to ? "is-end" : "", k === today ? "is-today" : ""].filter(Boolean).join(" ");
            return <td key={k} role="gridcell" aria-selected={inRange(k) || k === d.from || k === d.to}>
              <button type="button" className={cls} data-day={k} tabIndex={k === focus ? 0 : -1} aria-disabled={blocked(k) || undefined}
                aria-label={day.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric" })} onClick={() => clickDay(k)}>{day.getDate()}</button>
            </td>;
          })}</tr>)}</tbody>
        </table>
        <div className="ui-dr-fields">
          <label>From <input className="ui-input" type="date" aria-label="From date" value={d.from} onChange={(e) => edit("from", e.target.value)} /></label>
          <input className="ui-input ui-dr-time" type="time" aria-label="From time (optional)" value={d.fromTime} onChange={(e) => edit("fromTime", e.target.value)} />
          <label>To <input className="ui-input" type="date" aria-label="To date" value={d.to} onChange={(e) => edit("to", e.target.value)} /></label>
          <input className="ui-input ui-dr-time" type="time" aria-label="To time (optional)" value={d.toTime} onChange={(e) => edit("toTime", e.target.value)} />
        </div>
        {error && <p className="ui-dr-error" role="alert">{error}</p>}
        <div className="ui-fpop-foot">
          <button type="button" className="ui-btn ui-btn-plain" onClick={onCancel}>Cancel</button>
          <button type="button" className="ui-btn ui-btn-primary" onClick={apply}>Apply</button>
        </div>
      </div>
    </div>
  );
}
