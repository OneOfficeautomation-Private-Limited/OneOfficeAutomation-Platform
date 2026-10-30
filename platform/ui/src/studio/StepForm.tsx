import { useState } from "react";
import type { FieldSpec, Flow, FlowStep, PackageManifest } from "../api/client";
import { Button, Field, Select } from "../components";
import { ConditionEditor } from "./ConditionEditor";
import { fieldOrder, findAction, parseLoose, setArg, showValue } from "./flowEdit";
import { CaptureEditor } from "./CaptureEditor";
import { ConnectionField, ElementActionSelect, WindowEditor } from "./KindFields";
import {
  connectionInputs, EACH_KINDS, elementFields, helpText, isElementAction, LOOP_LABELS, loopIsMore, loopMode, outcomeOf, shownWhen, TIMEOUT_FIELDS,
  TIMEOUT_LABELS, valueLabel, type LoopMode,
} from "./stepKinds";
import { AssignValue, CommitInput, OutputChooser, RunAgentFields, TextSource } from "./ValueSources";
import { useVars } from "./VariablesPanel";

export { CommitInput };

/** Fields where text is typed into an application: a credential, a variable, plain text or a calculation. */
const typedText = (step: FlowStep, name: string) => (step.action === "ooa.browser/type_text" && name === "text")
  || (step.action === "ooa.browser/element_action" && name === "value" && /^(set_text|append_text)$/.test(String(step.args?.action ?? "")))
  || (step.action === "ooa.desktop_ui/object_action" && name === "text");

// the Basic / Advanced choice of the time-out box is kept while Studio is open, as in Classic
let timeoutAdvanced = false;
// steps whose element is found by a selector the person asked for, instead of a captured object
const bySelectorAsked = new Set<string>();

/**
 * The selected step's settings, with labels beside the fields (as in AA) and help behind an ⓘ: the fields that apply
 * to what the step does (its action, its kind of loop, a saved connection), then More options, the time-out box,
 * where each result is kept, then a comment. A field commits when it loses focus or on Enter, so typing a word is one
 * undo step, not one per letter. Empty means "not set": the toolkit's default applies.
 */
export function StepForm({ step, packages, onArg, onNote }:
  { step: FlowStep; packages: PackageManifest[]; onArg: (name: string, value: unknown) => void; onNote: (note: string) => void }) {
  const vars = useVars();
  const spec = findAction(step.action, packages);
  const pkg = packages.find((p) => p.id === step.action.split("/")[0]);
  const props = spec?.inputs?.properties ?? {};
  const required = spec?.inputs?.required ?? [];
  const [showConn, setShowConn] = useState(false);
  const [advanced, setAdvanced] = useState(timeoutAdvanced);
  const [, redraw] = useState(0);
  // one element field: the captured object, or the selector when the step uses one (or the person asks for it)
  const capturable = "object" in props && step.action.startsWith("ooa.desktop_ui/") || ("object" in props && "selector" in props && step.action.startsWith("ooa.browser/"));
  const bySelector = capturable && step.action.startsWith("ooa.browser/") && step.args?.object === undefined && (step.args?.selector !== undefined || bySelectorAsked.has(step.id));
  const conn = connectionInputs(pkg);
  const connNames = Object.keys(props).filter((n) => conn.has(n));
  const only = elementFields(step);
  const isLoop = step.action === "core/loop";
  const mode: LoopMode = isLoop ? loopMode(step) : "each";
  // an input that applies only in some cases (Loop's count only for "N times") is shown only then; an element action
  // shows the inputs of its chosen action; a saved connection hides the inputs it supplies
  const applies = (name: string) => shownWhen(props[name]?.["x-ooa-visible-when"], (o) => step.args?.[o] ?? props[o]?.default)
    && (!only || only.has(name)) && !(step.connection && conn.has(name) && !showConn)
    && !(isLoop && (name === "iterator" || (mode === "times" && name === "index")))
    && !(capturable && ((name === "selector" && !bySelector) || (name === "object" && bySelector)));
  const order = fieldOrder(spec);
  const timeouts = TIMEOUT_FIELDS.filter((n) => n in props && applies(n));
  const boxed = timeouts.length >= 2 ? new Set(timeouts) : new Set<string>();
  let main = order.main.filter((n) => applies(n) && !boxed.has(n)), more = order.more.filter((n) => applies(n) && !boxed.has(n));
  if (isLoop) { const all = [...main, ...more]; main = all.filter((n) => !loopIsMore(n)); more = all.filter(loopIsMore); }
  // web actions: the window first, then the element, as when choosing a window and then capturing in it
  if (step.action.startsWith("ooa.browser/") && "window" in props) main = [...main.filter((n) => n === "window"), ...main.filter((n) => n !== "window")];
  if (capturable) { const el = bySelector ? "selector" : "object"; if (more.includes(el)) { more = more.filter((n) => n !== el); main = [...main.filter((n) => n === "window"), el, ...main.filter((n) => n !== "window")]; } }
  const elementSwitch = capturable && step.action.startsWith("ooa.browser/") ? <button type="button" className="st-link" onClick={() => {
    if (bySelector) { bySelectorAsked.delete(step.id); if (vars && step.args?.selector !== undefined) vars.edit(setArg(vars.flow, step.id, "selector", undefined)); }
    else bySelectorAsked.add(step.id);
    redraw((n) => n + 1);
  }}>{bySelector ? "Capture the element instead" : "Use a selector instead"}</button> : null;
  const outputs: [string, FieldSpec][] = Object.entries(spec?.outputs?.properties ?? {});
  const outcome = isElementAction(step.action) ? outcomeOf(String(step.args?.action ?? "click")) : null;
  if (outcome && !outputs.some(([n]) => n === "result")) outputs.push(["result", { "x-ooa-type": outcome.type, "x-ooa-label": "Result" }]);

  const each = EACH_KINDS.find((k) => k.value === step.args?.iterator);
  const labelOf = (name: string) => valueLabel(step) && name === "value" ? valueLabel(step)!
    : isLoop && name === "over" && each ? each.over : isLoop && name === "as" ? (mode === "times" ? "Counter (optional)" : each?.as ?? LOOP_LABELS.as)
    : isLoop && LOOP_LABELS[name] ? LOOP_LABELS[name] : boxed.has(name) ? TIMEOUT_LABELS[name] : props[name]?.["x-ooa-label"] ?? name.replace(/_/g, " ");
  const req = (name: string) => required.includes(name) || (isLoop && ((mode === "times" && name === "count") || (mode === "each" && name === "over") || (mode === "while" && name === "condition")));
  const field = (name: string) => {
    if (step.action.startsWith("core/") && name === "condition") return <Field key={step.id + ":condition"} label={isLoop ? "Repeat while" : "Condition"} info={props[name]?.description}>{() =>
      <ConditionEditor key={step.id} value={step.args?.condition} onCommit={(v) => onArg(name, v)} />}</Field>;
    if (step.action === "core/set" && name === "value") return <Field key={step.id + ":value"} label={"Value" + (req(name) ? " *" : "")} info={props[name]?.description}>{() =>
      <AssignValue step={step} />}</Field>;
    if (step.action === "core/run_bot" && name === "automation") return <RunAgentFields key={step.id + ":agent"} step={step} />;
    if (step.action === "core/run_bot" && (name === "inputs" || name === "output_map")) return null;   // with the agent to run
    if (isElementAction(step.action) && name === "action") return <Field key={step.id + ":action"} layout="beside"
      label="Action" info="The actions that suit the captured control come first; the rest are under Other actions.">{(id) =>
      <ElementActionSelect id={id} step={step} all={(props.action?.enum ?? []).map(String)} />}</Field>;
    if (capturable && name === "object") return <Field key={step.id + ":object"} label={step.action.startsWith("ooa.desktop_ui/") ? "Captured control" : "Captured element"}
      info="Tick the properties to search by and choose how each one must match. Values can use $variables. Test opens the page and outlines what matches.">{() => <>
        <CaptureEditor step={step} value={step.args?.object} onChange={(o) => onArg("object", o)} />{elementSwitch}</>}</Field>;
    if (capturable && name === "selector") return <Field key={step.id + ":selector"} label="Selector" layout="beside" info={props.selector?.description}>{(id) => <>
      <div className="st-row-tight"><CommitInput key={String(step.args?.selector ?? "")} id={id} mono want="String" initial={String(step.args?.selector ?? "")} placeholder="#submit or //button[text()='Save']"
        onCommit={(t) => onArg("selector", t || undefined)} />
        <SelectorCapture onSelector={(sel) => onArg("selector", sel)} /></div>{elementSwitch}</>}</Field>;
    if (name === "window" && step.action.startsWith("ooa.browser/") && props.window?.["x-ooa-type"] === "Dictionary") return <Field key={step.id + ":window"}
      label="Window" info="The browser tab or application window the action works in.">{() => <WindowEditor step={step} />}</Field>;
    if (typedText(step, name)) return <Field key={step.id + ":" + name} label={labelOf(name) + (req(name) ? " *" : "")}
      info="A credential from the vault for passwords (never shown or logged), a variable, plain text or a calculation.">{() => <TextSource step={step} name={name} />}</Field>;
    return <InputField key={step.id + ":" + name} name={name} label={labelOf(name)} spec={props[name] ?? {}} required={req(name)}
      value={step.args?.[name]} onCommit={(v) => onArg(name, v)} apply={(t, f) => setArg(f, step.id, name, parseValue(t, props[name]))} />;
  };
  // inputs the agent sets that the catalogue does not list (an older toolkit, a hand-edited file) stay editable
  const extra = Object.keys(step.args ?? {}).filter((n) => !(n in props));
  const setMode = (m: LoopMode) => {
    if (!vars || m === mode) return;
    let f: Flow = vars.flow;
    for (const k of ["over", "count", "condition", "iterator_args", "from_index", "to_index", "reverse", "check_at_end", "start"]) f = setArg(f, step.id, k, undefined);
    vars.edit(setArg(f, step.id, "iterator", m === "each" ? "table_rows" : m));
  };

  return (
    <div className="st-step-form">
      <h3 className="st-prop-section">Settings</h3>
      <div className="st-prop-fields">
        {connNames.length > 0 && pkg && <ConnectionField step={step} pkgName={pkg.name} pkgId={pkg.id} names={connNames} showing={showConn} onShow={setShowConn} packages={packages} />}
        {isLoop && <>
          <Field label="Kind of loop" layout="beside">{() => <div className="st-seg" role="radiogroup" aria-label="Kind of loop">
            {([["times", "N times"], ["each", "For each"], ["while", "While"]] as const).map(([m, t]) =>
              <button key={m} type="button" role="radio" aria-checked={mode === m} className="st-seg-btn" onClick={() => setMode(m)}>{t}</button>)}
          </div>}</Field>
          {mode === "each" && <Field label="Go through" layout="beside">{(id) =>
            <Select id={id} aria-label="Loop type" value={String(step.args?.iterator ?? "list")} onChange={(e) => onArg("iterator", e.target.value)}
              options={[...EACH_KINDS.map((k) => ({ value: k.value, label: k.label })),
                ...(each || !step.args?.iterator ? [] : [{ value: String(step.args.iterator), label: String(step.args.iterator) + " (a toolkit's iterator)" }])]} />}</Field>}
        </>}
        {main.map(field)}
        {extra.map(field)}
        {!main.length && !extra.length && !isLoop && <p className="st-muted">This step has no main settings.</p>}
      </div>
      {more.length > 0 && (
        <details className="st-more">
          <summary>More options ({more.length})</summary>
          <div className="st-prop-fields">{more.map(field)}</div>
        </details>
      )}
      {boxed.size > 0 && (
        <section className="st-timeout" aria-label="System time-out">
          <div className="st-timeout-head"><strong>System time-out</strong>
            <div className="st-seg" role="radiogroup" aria-label="Time-out options">
              {(["Basic", "Advanced"] as const).map((t) => <button key={t} type="button" role="radio" aria-checked={(t === "Advanced") === advanced} className="st-seg-btn"
                onClick={() => { timeoutAdvanced = t === "Advanced"; setAdvanced(timeoutAdvanced); }}>{t}</button>)}
            </div></div>
          <div className="st-prop-fields">{timeouts.filter((n) => advanced || n === "timeout_ms" || n === "timeout_seconds").map(field)}</div>
        </section>
      )}
      {outputs.length > 0 && <>
        <h3 className="st-prop-section">Save results in</h3>
        <div className="st-prop-fields">
          {outputs.map(([name, o]) => (
            <Field key={step.id + ":out:" + name} label={o["x-ooa-label"] ?? name.replace(/_/g, " ")} layout="beside" info={o.description}>{() =>
              <OutputChooser step={step} output={name} type={o["x-ooa-type"]} label={"Keep " + (o["x-ooa-label"] ?? name.replace(/_/g, " ")) + " in"} />}</Field>
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

const isNumber = (spec?: FieldSpec) => spec?.["x-ooa-type"] === "Number" || spec?.type === "number";
/** A Number field as typed: a number, a variable, or anything else as a calculation ({expr}), so "$n - 1" needs no JSON. */
function parseValue(text: string, spec?: FieldSpec): unknown {
  const v = parseLoose(text);
  if (!isNumber(spec) || typeof v !== "string" || /^\$[A-Za-z_][\w.]*$/.test(v.trim())) return v;
  return { expr: v.trim().replace(/^=\s*/, "") };
}
const showNumber = (v: unknown) => v && typeof v === "object" && typeof (v as { expr?: unknown }).expr === "string" ? (v as { expr: string }).expr : showValue(v);

function InputField({ name, label, spec, required, value, onCommit, apply }:
  { name: string; label: string; spec: FieldSpec; required: boolean; value: unknown; onCommit: (v: unknown) => void; apply: (text: string, flow: Flow) => Flow }) {
  const fallback = spec.default !== undefined ? "Default: " + showValue(spec.default) : required ? "" : "Not set";
  const info = helpText(spec, required) || undefined;
  return (
    <Field label={label + (required ? " *" : "")} layout="beside" info={info}>{(id) => {
      if (spec.enum?.length) {
        return <Select id={id} value={value === undefined ? "" : String(value)} onChange={(e) => onCommit(e.target.value === "" ? undefined : e.target.value)}
          options={[{ value: "", label: fallback || "Choose…" }, ...spec.enum.map((v) => ({ value: String(v), label: String(v).replace(/_/g, " ") }))]} />;
      }
      if (spec.type === "boolean") {
        return <Select id={id} value={value === undefined ? "" : String(value)} onChange={(e) => onCommit(e.target.value === "" ? undefined : e.target.value === "true")}
          options={[{ value: "", label: fallback || "Choose…" }, { value: "true", label: "Yes" }, { value: "false", label: "No" }]} />;
      }
      const text = isNumber(spec) ? showNumber(value) : showValue(value);
      return <CommitInput key={text} id={id} initial={text} placeholder={fallback} mono={typeof value === "string" && value.startsWith("$")}
        onCommit={(t) => onCommit(parseValue(t, spec))} want={spec["x-ooa-type"] ?? "Any"} apply={apply} nameHint={name} />;
    }}</Field>
  );
}

/** Capture beside a selector: click the element in a browser and keep its selector. */
function SelectorCapture({ onSelector }: { onSelector: (selector: string) => void }) {
  const vars = useVars();
  const [busy, setBusy] = useState(false);
  if (!vars?.where) return null;
  return <Button kind="secondary" disabled={busy} onClick={async () => {
    const where = await vars.where!("capture");
    if (!where) return;
    setBusy(true);
    try { vars.notice?.("Click the element in the browser window (Esc cancels)."); const r = await vars.api.capture(where); if (r.selector) onSelector(r.selector); }
    catch (e) { vars.fail?.(e); }
    finally { setBusy(false); }
  }}>{busy ? "Capturing…" : "Capture"}</Button>;
}
