import { useRef, useState } from "react";
import { FilterPopover } from "./FilterPopover";

/**
 * The one multi-choice filter above a table (tables and filters, item 2d, 27 Nov 2026): Status, Agent, Runner, User,
 * Type... Tick several; a long list gets a search box; "3 of 195 chosen" and Clear. The button reads "Status: Any",
 * "Status: Failed, Stopped" or "Agent: 3 chosen", and has a × to clear it. Same height and look as the date filter.
 *
 * Pages keep the chosen values as one text (choicesOf / choiceText), so a filter stays a plain value in the page's
 * filters and its address.
 */
export type ChoiceOption = { value: string; label: string };
const SEP = "\n";
/** The chosen values kept in one text. */
export const choicesOf = (s?: string | null) => s ? s.split(SEP).filter(Boolean) : [];
export const choiceText = (values: string[]) => values.join(SEP);
/** For a server that takes a comma list. */
export const choiceParam = (s?: string | null) => choicesOf(s).join(",");
/** True when nothing is chosen or the value is one of those chosen. */
export const choiceHas = (s: string | undefined | null, v: string | undefined | null) => !s || choicesOf(s).includes(v ?? "");

export function ChoiceFilter({ label, options, value, onChange, any = "Any", search }: { label: string; options: ChoiceOption[]; value: string[];
  onChange: (v: string[]) => void; any?: string; search?: boolean }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const button = useRef<HTMLButtonElement>(null);
  const names = value.map((v) => options.find((o) => o.value === v)?.label ?? v);
  const text = !value.length ? any : value.length <= 2 && names.join(", ").length <= 28 ? names.join(", ") : value.length + " chosen";
  const find = search ?? options.length > 8;
  const shown = q.trim() ? options.filter((o) => o.label.toLowerCase().includes(q.trim().toLowerCase())) : options;
  const toggle = (v: string, on: boolean) => onChange(on ? options.map((o) => o.value).filter((x) => x === v || value.includes(x)) : value.filter((x) => x !== v));
  return (
    <span className={"ui-fgroup" + (value.length ? " is-set" : "")}>
      <button ref={button} type="button" className={"ui-fbtn" + (value.length ? " is-set" : "")} aria-haspopup="dialog" aria-expanded={open}
        aria-label={label + ": " + text} title={label + ": " + (value.length ? names.join(", ") : any)} onClick={() => setOpen(!open)}>
        <span className="ui-fbtn-text">{label}: {text}</span><span className="ui-fbtn-caret" aria-hidden="true">▾</span>
      </button>
      {value.length > 0 && <button type="button" className="ui-fclear" aria-label={"Clear " + label} title={"Clear " + label} onClick={() => onChange([])}>×</button>}
      {open && <FilterPopover anchor={button} label={label} onClose={() => { setOpen(false); setQ(""); }} className="ui-cf-pop">
        {find && <input className="ui-input ui-cf-search" type="search" placeholder={"Find " + label.toLowerCase()} aria-label={"Find " + label.toLowerCase()}
          value={q} onChange={(e) => setQ(e.target.value)} autoFocus />}
        <div className="ui-cf-count" role="status">{value.length} of {options.length} chosen</div>
        <div className="ui-cf-list" role="group" aria-label={label + " choices"}>
          {shown.map((o) => <label key={o.value} className="ui-cf-row"><input type="checkbox" checked={value.includes(o.value)} onChange={(e) => toggle(o.value, e.target.checked)}
            autoFocus={!find && o === shown[0]} /> <span>{o.label}</span></label>)}
          {!shown.length && <p className="ui-cf-none">Nothing matches.</p>}
        </div>
        <div className="ui-fpop-foot">
          <button type="button" className="ui-btn ui-btn-plain" disabled={!value.length} onClick={() => onChange([])}>Clear</button>
          <button type="button" className="ui-btn ui-btn-primary" onClick={() => { setOpen(false); setQ(""); }}>Done</button>
        </div>
      </FilterPopover>}
    </span>
  );
}
