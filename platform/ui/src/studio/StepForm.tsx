import type { FieldSpec, FlowStep, PackageManifest } from "../api/client";
import { Field, Select, TextInput } from "../components";
import { ConditionEditor } from "./ConditionEditor";
import { fieldOrder, findAction, parseLoose, showValue } from "./flowEdit";

/**
 * The selected step's settings, with labels beside the fields (as in AA): the main inputs, then More options, then
 * where each output is saved, then a comment. A field commits when it loses focus or on Enter, so typing a word is
 * one undo step, not one per letter. Empty means "not set": the toolkit's default applies.
 */
export function StepForm({ step, packages, onArg, onOut, onNote }:
  { step: FlowStep; packages: PackageManifest[]; onArg: (name: string, value: unknown) => void; onOut: (output: string, variable: string) => void;
    onNote: (note: string) => void }) {
  const spec = findAction(step.action, packages);
  const props = spec?.inputs?.properties ?? {};
  const required = spec?.inputs?.required ?? [];
  // an input that applies only in some cases (Loop's condition only for "while") is shown only then
  const applies = (name: string) => Object.entries(props[name]?.["x-ooa-visible-when"] ?? {})
    .every(([other, values]) => values.some((v) => String(v) === String(step.args?.[other] ?? props[other]?.default ?? "")));
  const order = fieldOrder(spec);
  const main = order.main.filter(applies), more = order.more.filter(applies);
  const outputs = Object.entries(spec?.outputs?.properties ?? {});
  const field = (name: string) => step.action.startsWith("core/") && name === "condition"
    ? <Field key={step.id + ":condition"} label="Condition" hint={props[name]?.description}>{() =>
        <ConditionEditor key={step.id} value={step.args?.condition} onCommit={(v) => onArg(name, v)} />}</Field>
    : <InputField key={step.id + ":" + name} name={name} spec={props[name] ?? {}} required={required.includes(name)}
        value={step.args?.[name]} onCommit={(v) => onArg(name, v)} />;
  // inputs the agent sets that the catalogue does not list (an older toolkit, a hand-edited file) stay editable
  const extra = Object.keys(step.args ?? {}).filter((n) => !(n in props));

  return (
    <div className="st-step-form">
      <h3 className="st-prop-section">Settings</h3>
      <div className="st-prop-fields">
        {main.map(field)}
        {extra.map(field)}
        {!main.length && !extra.length && <p className="st-muted">This step has no main settings.</p>}
      </div>
      {more.length > 0 && (
        <details className="st-more">
          <summary>More options ({more.length})</summary>
          <div className="st-prop-fields">{more.map(field)}</div>
        </details>
      )}
      {outputs.length > 0 && <>
        <h3 className="st-prop-section">Save results in</h3>
        <div className="st-prop-fields">
          {outputs.map(([name, o]) => (
            <Field key={step.id + ":out:" + name} label={o["x-ooa-label"] ?? name.replace(/_/g, " ")} layout="beside" hint={o.description}>{(id) =>
              <CommitInput key={step.out?.[name] ?? ""} id={id} mono initial={step.out?.[name] ? "$" + step.out[name] : ""} placeholder="$variable" onCommit={(t) => onOut(name, t)} />}</Field>
          ))}
        </div>
      </>}
      <h3 className="st-prop-section">Comment</h3>
      <div className="st-prop-fields">
        <Field label="Shown on the step" layout="beside">{(id) =>
          <CommitInput key={step.id + ":note:" + (step.note ?? "")} id={id} initial={step.note ?? ""} placeholder="Optional" onCommit={onNote} />}</Field>
      </div>
    </div>
  );
}

function InputField({ name, spec, required, value, onCommit }: { name: string; spec: FieldSpec; required: boolean; value: unknown; onCommit: (v: unknown) => void }) {
  const label = spec["x-ooa-label"] ?? name.replace(/_/g, " ");
  const hint = [required ? "Required." : "", spec.description ?? ""].filter(Boolean).join(" ");
  const fallback = spec.default !== undefined ? "Default: " + showValue(spec.default) : required ? "" : "Not set";
  return (
    <Field label={label + (required ? " *" : "")} layout="beside" hint={hint}>{(id) => {
      if (spec.enum?.length) {
        return <Select id={id} value={value === undefined ? "" : String(value)} onChange={(e) => onCommit(e.target.value === "" ? undefined : e.target.value)}
          options={[{ value: "", label: fallback || "Choose…" }, ...spec.enum.map((v) => ({ value: String(v), label: String(v).replace(/_/g, " ") }))]} />;
      }
      if (spec.type === "boolean") {
        return <Select id={id} value={value === undefined ? "" : String(value)} onChange={(e) => onCommit(e.target.value === "" ? undefined : e.target.value === "true")}
          options={[{ value: "", label: fallback || "Choose…" }, { value: "true", label: "Yes" }, { value: "false", label: "No" }]} />;
      }
      return <CommitInput key={showValue(value)} id={id} initial={showValue(value)} placeholder={fallback} mono={typeof value === "string" && value.startsWith("$")}
        onCommit={(t) => onCommit(parseLoose(t))} />;
    }}</Field>
  );
}

/** A text field that reports its value when it loses focus or on Enter, not on every key. Callers key it on the value, so undo shows. */
function CommitInput({ id, initial, placeholder, mono, onCommit }: { id: string; initial: string; placeholder?: string; mono?: boolean; onCommit: (text: string) => void }) {
  const commit = (el: HTMLInputElement) => { if (el.value !== initial) onCommit(el.value); };
  return <TextInput id={id} defaultValue={initial} placeholder={placeholder} mono={mono}
    onBlur={(e) => commit(e.currentTarget)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); commit(e.currentTarget); } }} />;
}
