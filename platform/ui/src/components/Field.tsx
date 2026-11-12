import { useId, useState, type ReactNode } from "react";

/**
 * A labelled field. "beside" puts the label to the left (properties panels, as in AA); "above" puts it on top
 * (dialogs). The control gets the label's id, so screen readers announce it. Help ("info") up to SHORT_HELP characters
 * is written under the field, as AA writes its hints (usage review item 24); longer help stays behind the (i) button.
 */
const SHORT_HELP = 160;
export function Field({ label, hint, info, layout = "above", children }:
  { label: string; hint?: string; info?: string; layout?: "above" | "beside"; children: (id: string) => ReactNode }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const shown = info && info.length <= SHORT_HELP ? info : undefined;
  const behind = info && !shown ? info : undefined;
  return (
    <div className={`ui-field ui-field-${layout}`}>
      <span className="ui-field-label">
        <label htmlFor={id}>{label}</label>
        {behind && <button type="button" className="ui-info" aria-label={"About " + label.replace(/ \*$/, "")} aria-expanded={open} title={behind} onClick={() => setOpen(!open)}>i</button>}
      </span>
      <div className="ui-field-control">
        {children(id)}
        {hint && <div className="ui-field-hint">{hint}</div>}
        {shown && shown !== hint && <div className="ui-field-hint">{shown}</div>}
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

/** A drop-down list; options with a group are shown under that group's heading (an optgroup), in the order given. */
export function Select({ options, className, ...rest }: { options: { value: string; label: string; group?: string }[] } & React.SelectHTMLAttributes<HTMLSelectElement>) {
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
