import { useEffect, useState, type ReactNode } from "react";
import { Icon } from "../../components";
import type { DocStatus } from "./api";

/** Small building blocks shared by the Document Automation screens (docflow.css section 2). */

export type Tone = "ok" | "warn" | "bad" | "info" | "mute" | "ai";

/** A status pill: always a dot and words, so the state never relies on colour alone (DESIGN_SYSTEM.md). */
/**
 * DA-1 (plan B, 15 Nov 2026): every section shows its basics in plain words; "Show advanced settings" opens the
 * technical part of that section only (patterns, thresholds, engine choices). Closed by default, not remembered.
 */
export function AdvancedToggle({ open, onToggle, what }: { open: boolean; onToggle: () => void; what: string }) {
  return (
    <div className="df-adv">
      <button type="button" className="df-link" aria-expanded={open} onClick={onToggle}>{open ? "Hide advanced settings" : "Show advanced settings"}</button>
      {!open && <span className="df-small"> · {what}</span>}
    </div>
  );
}

export function Chip({ tone, children, dot = true }: { tone: Tone; children: ReactNode; dot?: boolean }) {
  return <span className={`df-chip df-${tone}`}>{dot && <span className="df-dot" aria-hidden="true" />}{children}</span>;
}

const STATUS: Record<DocStatus, { tone: Tone; label: string }> = {
  done: { tone: "ok", label: "Done" }, review: { tone: "warn", label: "Needs review" }, processing: { tone: "info", label: "Processing" },
  failed: { tone: "bad", label: "Failed" }, split: { tone: "ai", label: "Split" },
};
export const statusLabel = (s: DocStatus) => STATUS[s].label;
export const StatusChip = ({ status }: { status: DocStatus }) => <Chip tone={STATUS[status].tone}>{STATUS[status].label}</Chip>;

export function Kpi({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: Tone }) {
  return (
    <div className="df-card df-kpi">
      <div className="df-kpi-label">{label}</div>
      <div className={`df-kpi-value${tone ? " df-text-" + tone : ""}`}>{value}</div>
      {sub && <div className={`df-kpi-sub${tone ? " df-text-" + tone : ""}`}>{sub}</div>}
    </div>
  );
}

export function ConfidenceBar({ value }: { value: number | null }) {
  if (value == null) return null;
  const tone = value >= 85 ? "ok" : value >= 70 ? "warn" : "bad";
  return (
    <span className="df-conf">
      <span className="df-conf-track" aria-hidden="true"><span className={`df-conf-fill df-bg-${tone}`} style={{ width: value + "%" }} /></span>
      <span className="df-num">{value}%</span>
    </span>
  );
}

/** The filter bar every table has: search, labelled dropdowns, Clear filters and a result count. */
export function FilterBar({ children, onClear, count, actions }: { children: ReactNode; onClear?: () => void; count?: string; actions?: ReactNode }) {
  return (
    <div className="df-filters" role="search">
      {children}
      <span className="df-grow" />
      {onClear && <button type="button" className="df-link" onClick={onClear}>Clear filters</button>}
      {count && <span className="df-muted" aria-live="polite">{count}</span>}
      {actions}
    </div>
  );
}

export function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <label className="df-search">
      <Icon name="search" size={14} />
      <input type="search" value={value} placeholder={placeholder} aria-label={placeholder} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}

export function FilterSelect<T extends string>({ label, value, onChange, options }:
  { label: string; value: T; onChange: (v: T) => void; options: { value: T; label: string }[] }) {
  return (
    <label className="df-filter">
      <span>{label}</span>
      <select className="ui-input ui-select" value={value} onChange={(e) => onChange(e.target.value as T)}>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </label>
  );
}

export const matches = (q: string, ...texts: string[]) => !q.trim() || texts.some((t) => t.toLowerCase().includes(q.trim().toLowerCase()));

/** A failed call: the page's banner shows it (App listens), so a screen never waits on "Loading…" for ever. */
/**
 * Where Document Automation's own addresses start (fix list 27 Nov, item 3c): "#/" on its own page, "#/docautomation/"
 * inside the console's frame, so its #/inbox never opens the console's Task inbox. Every link and hash change uses it.
 */
let hashBase = "#/";
export const dfHash = () => hashBase;
export function setDfHashBase(base: string) { hashBase = base; }

export const DF_ERROR = "df-error";
export function reportError(e: unknown) {
  window.dispatchEvent(new CustomEvent(DF_ERROR, { detail: e instanceof Error ? e.message : String(e) }));
}

/** Loads data for a screen; `reload` fetches it again after a change. Null while loading. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]): [T | null, () => void] {
  const [data, setData] = useState<T | null>(null);
  const [n, setN] = useState(0);
  useEffect(() => {
    let live = true;
    load().then((d) => { if (live) setData(d); }, (e) => { if (live) reportError(e); });
    return () => { live = false; };
  }, [...deps, n]);   // `load` is a new function each render; the caller's deps say when it really changes
  return [data, () => setN((x) => x + 1)];
}

export const Loading = () => <div className="df-empty" role="status">Loading…</div>;

export function Empty({ children }: { children: ReactNode }) {
  return <div className="df-empty">{children}</div>;
}

/** The header button that shows or hides "How to use this page". */
export function HelpButton({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <button type="button" className="df-help-btn" aria-expanded={open} aria-controls="df-help" onClick={onToggle}>
      <span className="df-help-q" aria-hidden="true">?</span>Help
    </button>
  );
}

/** "How to use this page": what the page is for, then numbered steps, one action each. */
export function HelpPanel({ summary, steps, onHide }: { summary: string; steps: string[]; onHide: () => void }) {
  return (
    <section id="df-help" className="df-help" aria-label="How to use this page">
      <div className="df-help-head"><b>How to use this page.</b><span>{summary}</span><span className="df-grow" />
        <button type="button" className="df-link" onClick={onHide}>Hide</button></div>
      <ol className="df-help-steps">{steps.map((s, i) => <li key={i}><span className="df-help-n" aria-hidden="true">{i + 1}</span>{s}</li>)}</ol>
    </section>
  );
}

/** A one-line explanation of an option, behind a small "i" (hover, focus or click). */
export function Hint({ about, text }: { about: string; text: string }) {
  const [open, setOpen] = useState(false);
  const [toLeft, setToLeft] = useState(false);   // near the right edge the bubble opens leftwards, so it stays on screen
  return (
    <span className="df-hint">
      <button type="button" className="ui-info" aria-label={"About " + about} aria-expanded={open} title={text}
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); setToLeft(e.currentTarget.getBoundingClientRect().right + 280 > window.innerWidth); setOpen(!open); }}
        onBlur={() => setOpen(false)}>i</button>
      {open && <span className={`df-hint-bubble${toLeft ? " is-left" : ""}`} role="note">{text}</span>}
    </span>
  );
}

/**
 * A choice from common values, or any own value: the saved value is always shown (even when it is not one of the
 * common ones), and "Other…" opens a box to type one. scale turns the typed number into the saved one (100 for
 * percentages: 87 typed is 0.87 saved). Used for times, thresholds and limits, so none of them is fixed to a list.
 */
export function ChoiceOrOwn({ id, value, onChange, options, label, unit, scale = 1, min = 0, max, ariaLabel }:
  { id: string; ariaLabel?: string; value: number; onChange: (v: number) => void; options: number[]; label: (v: number) => string; unit: string; scale?: number; min?: number; max?: number }) {
  const known = options.some((o) => Math.abs(o - value) < 1e-9);
  const [own, setOwn] = useState(false);
  const typed = Math.round(value * scale * 100) / 100;
  const list = [...new Set([...options, value])].sort((a, b) => a - b);
  return (
    <span style={{ display: "flex", gap: 8, alignItems: "center" }}>
      <select id={id} aria-label={ariaLabel} className="ui-input ui-select" value={own ? "own" : String(value)} onChange={(e) => {
        if (e.target.value === "own") { setOwn(true); return; }
        setOwn(false); onChange(Number(e.target.value));
      }}>
        {list.map((v) => <option key={v} value={String(v)}>{label(v)}{!known && v === value ? " (saved)" : ""}</option>)}
        <option value="own">Other…</option>
      </select>
      {own && <input className="ui-input" style={{ width: 96 }} type="number" aria-label={`Own value in ${unit}`} min={min} max={max} defaultValue={typed}
        onChange={(e) => { const n = Number(e.target.value); if (e.target.value !== "" && Number.isFinite(n) && n >= min && (max === undefined || n <= max)) onChange(n / scale); }} />}
      {own && <span className="df-small">{unit}</span>}
    </span>
  );
}

/** Seconds, minutes, hours or days in words: 30 seconds, 2 minutes, 12 hours, 3 days. */
export function duration(seconds: number): string {
  const n = (v: number, w: string) => `${Math.round(v * 10) / 10} ${w}${Math.round(v * 10) / 10 === 1 ? "" : "s"}`;
  if (seconds < 60) return n(seconds, "second");
  if (seconds < 3600) return n(seconds / 60, "minute");
  if (seconds < 86400 * 2 && seconds % 86400 !== 0) return n(seconds / 3600, "hour");
  return n(seconds / 86400, "day");
}
