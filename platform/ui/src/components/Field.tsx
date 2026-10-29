import { useId, type ReactNode } from "react";

/**
 * A labelled field. "beside" puts the label to the left (properties panels, as in AA); "above" puts it on top
 * (dialogs). The control gets the label's id, so screen readers announce it.
 */
export function Field({ label, hint, layout = "above", children }:
  { label: string; hint?: string; layout?: "above" | "beside"; children: (id: string) => ReactNode }) {
  const id = useId();
  return (
    <div className={`ui-field ui-field-${layout}`}>
      <label htmlFor={id}>{label}</label>
      <div className="ui-field-control">
        {children(id)}
        {hint && <div className="ui-field-hint">{hint}</div>}
      </div>
    </div>
  );
}

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement> & { mono?: boolean }) {
  const { mono, className, ...rest } = props;
  return <input className={`ui-input${mono ? " ui-mono" : ""}${className ? " " + className : ""}`} {...rest} />;
}

export function TextArea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className="ui-input ui-textarea" {...props} />;
}

export function Select({ options, ...rest }: { options: { value: string; label: string }[] } & React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className="ui-input ui-select" {...rest}>
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}
