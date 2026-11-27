import { useId, useState, type ChangeEvent, type ReactNode } from "react";
import { SearchSelect } from "./SearchSelect";

/**
 * A labelled field. "beside" puts the label to the left (OneAgentStudio's panels); "above" puts it on top (dialogs).
 * The control gets the label's id, so screen readers announce it. Above: help ("info") up to SHORT_HELP characters is
 * written under the field, longer help stays behind the (i) button. Beside: every help text (hint and info) is behind
 * the (i) beside the label, shown on hover and opened under the field with a click, so the panel stays compact and
 * keeps every detail (command observations, 16 Nov 2026).
 */
const SHORT_HELP = 160;
export function Field({ label, hint, info, layout = "above", children }:
  { label: string; hint?: string; info?: string; layout?: "above" | "beside"; children: (id: string) => ReactNode }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const compact = layout === "beside";
  const tucked = compact ? [hint, info].filter((t, i, all): t is string => !!t && all.indexOf(t) === i).join(" ") || undefined : undefined;
  const shown = !compact && info && info.length <= SHORT_HELP ? info : undefined;
  const behind = compact ? tucked : info && !shown ? info : undefined;
  const shownHint = compact ? undefined : hint;
  return (
    <div className={`ui-field ui-field-${layout}`}>
      <span className="ui-field-label">
        <label htmlFor={id}>{label}</label>
        {behind && <button type="button" className="ui-info" aria-label={"About " + label.replace(/ \*$/, "")} aria-expanded={open} title={behind} onClick={() => setOpen(!open)}>i</button>}
      </span>
      <div className="ui-field-control">
        {children(id)}
        {shownHint && <div className="ui-field-hint">{shownHint}</div>}
        {shown && shown !== shownHint && <div className="ui-field-hint">{shown}</div>}
        {behind && open && <div className="ui-field-hint" role="note">{behind}</div>}
      </div>
    </div>
  );
}

export function TextInput(props: React.ComponentProps<"input"> & { mono?: boolean }) {
  const { mono, className, ...rest } = props;
  return <input className={`ui-input${mono ? " ui-mono" : ""}${className ? " " + className : ""}`} {...rest} />;
}

export function TextArea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className="ui-input ui-textarea" {...props} />;
}

/** More choices than this and a drop-down becomes a list people can search (item 3 of the fix list of 27 Nov 2026, round 2, 3d). */
export const SEARCH_AFTER = 12;

/**
 * A drop-down list; options with a group are shown under that group's heading (an optgroup), in the order given. A long
 * list (more than SEARCH_AFTER choices, or `searchable`) is a SearchSelect: type to filter, group headings, its height
 * remembered per `kind`; its onChange gets an event-like object whose target.value is the choice, as a select's does.
 */
export function Select({ options, className, searchable, kind, noun, ...rest }: { options: { value: string; label: string; group?: string; description?: string }[];
  /** true: always the searchable list; false: always the browser's own. */
  searchable?: boolean;
  /** The searchable list's height is remembered under this name. */
  kind?: string;
  /** What the choices are, for its count ("variables"). */
  noun?: string } & React.SelectHTMLAttributes<HTMLSelectElement>) {
  if (searchable ?? options.length > SEARCH_AFTER) return <LongSelect options={options} className={className} kind={kind} noun={noun} {...rest} />;
  const parts: (typeof options[number] | { group: string; items: typeof options })[] = [];
  for (const o of options) {
    if (!o.group) { parts.push(o); continue; }
    const last = parts[parts.length - 1];
    if (last && "items" in last && last.group === o.group) last.items.push(o); else parts.push({ group: o.group, items: [o] });
  }
  return (
    <select className={"ui-input ui-select" + (className ? " " + className : "")} {...rest}>
      {parts.map((p) => "items" in p
        ? <optgroup key={"g:" + p.group} label={p.group}>{p.items.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</optgroup>
        : <option key={p.value} value={p.value}>{p.label}</option>)}
    </select>
  );
}

/** A long Select: the searchable list, with the select's value, label, disabled state and onChange (or defaultValue). */
function LongSelect({ options, className, kind, noun, value, defaultValue, onChange, id, disabled, "aria-label": ariaLabel, title }:
  { options: { value: string; label: string; group?: string; description?: string }[]; kind?: string; noun?: string } & React.SelectHTMLAttributes<HTMLSelectElement>) {
  const [own, setOwn] = useState(String(defaultValue ?? ""));
  const current = value === undefined ? own : String(value);
  const change = (v: string) => {
    if (value === undefined) setOwn(v);
    onChange?.({ target: { value: v }, currentTarget: { value: v } } as unknown as ChangeEvent<HTMLSelectElement>);
  };
  return <span className={"ui-select-long" + (className ? " " + className : "")} title={title}>
    <SearchSelect id={id} value={current} options={options} onChange={change} disabled={disabled} aria-label={ariaLabel} kind={kind} noun={noun}
      placeholder={options.find((o) => o.value === "")?.label ?? "Type to search"} />
  </span>;
}
