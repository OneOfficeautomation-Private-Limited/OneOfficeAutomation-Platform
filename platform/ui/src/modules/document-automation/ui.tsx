import { useEffect, useState, type ReactNode } from "react";
import { Icon } from "../../components";
import type { DocStatus } from "./api";

/** Small building blocks shared by the Document Automation screens (docflow.css section 2). */

export type Tone = "ok" | "warn" | "bad" | "info" | "mute" | "ai";

/** A status pill: always a dot and words, so the state never relies on colour alone (DESIGN_SYSTEM.md). */
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
      <span className="df-help-q" aria-hidden="true">?</span>How to use this page
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
