import { useState, type ReactNode } from "react";
import type { FieldSpec, Flow, FlowStep, PackageManifest } from "../api/client";
import { Button, Field, Select } from "../components";
import { ConditionEditor, SpecFields } from "./ConditionEditor";
import { StepErrorFields } from "./ErrorPolicy";
import { enumWord } from "./enumWords";
import { ColumnField, knownColumns, knownSessions, MoreConditions, SessionField } from "./TableFields";
import { DatePatternField, DelimiterField, EncodingField, LocaleField, SizeField, TimeZoneField } from "./PickerFields";
import { fieldOrder, findAction, groupFields, labelsInScope, loopLabelsAround, parseLoose, setArg, setStepPolicy, showValue, type PanelGroup } from "./flowEdit";
import { CaptureEditor } from "./CaptureEditor";
import { ConnectionField, ElementActionSelect, WindowEditor } from "./KindFields";
import {
  connectionInputs, EACH_KINDS, elementFields, helpText, isElementAction, LOOP_LABELS, loopIsMore, loopMode, outcomeOf, shownWhen, TIMEOUT_FIELDS,
  TIMEOUT_LABELS, valueLabel, type LoopMode,
} from "./stepKinds";
import { AssignmentsEditor, AssignValue, CommitInput, OutputChooser, RunAgentFields, TextSource } from "./ValueSources";
import { useVars } from "./VariablesPanel";
import { BoolField, CollectionField, CredentialField, DateField, FileField, WindowField } from "./FieldKinds";
import { pickerFor } from "./Pickers";

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
  // a toolkit's loop type (C2: each row in a worksheet, each file in a folder...): its own settings instead of "Go through"
  const iterator = String(step.args?.iterator ?? "");
  const itSpec = isLoop && mode === "each" && iterator.includes("/")
    ? packages.find((p) => p.id === iterator.split("/")[0])?.iterators?.find((x) => x.id === iterator.split("/")[1]) : undefined;
  // an input that applies only in some cases (Loop's count only for "N times") is shown only then; an element action
  // shows the inputs of its chosen action; a saved connection hides the inputs it supplies
  const applies = (name: string) => shownWhen(props[name]?.["x-ooa-visible-when"], (o) => step.args?.[o] ?? props[o]?.default)
    && (!only || only.has(name)) && !(step.connection && conn.has(name) && !showConn)
    && !(isLoop && (name === "iterator" || (mode === "times" && name === "index") || (itSpec && (name === "over" || name === "iterator_args"))))
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
    : isLoop && name === "over" && each ? each.over : isLoop && name === "as" ? (mode === "times" ? "Counter (optional)" : each?.as ?? (itSpec ? "Store each one in" : LOOP_LABELS.as))
    : isLoop && LOOP_LABELS[name] ? LOOP_LABELS[name] : boxed.has(name) ? TIMEOUT_LABELS[name] : props[name]?.["x-ooa-label"] ?? name.replace(/_/g, " ");
  const req = (name: string) => required.includes(name) || (isLoop && ((mode === "times" && name === "count") || (mode === "each" && name === "over") || (mode === "while" && name === "condition")));
  /** A field shown as the catalogue describes it, under a label of our own (Delay's From and To). */
  const plainInput = (name: string, label: string) => <InputField key={step.id + ":" + name} action={step.action} name={name} label={label} spec={props[name] ?? {}}
    required={req(name)} value={step.args?.[name]} onCommit={(v) => onArg(name, v)} apply={(t, f) => setArg(f, step.id, name, parseValue(t, props[name]))} />;
  const field = (name: string) => {
    if (step.action.startsWith("core/") && name === "condition") return <Field key={step.id + ":condition"} label={isLoop ? "Repeat while" : "Condition"} info={props[name]?.description}>{() =>
      <ConditionEditor key={step.id} value={step.args?.condition} onCommit={(v) => onArg(name, v)} />}</Field>;
    if (step.action === "core/set" && name === "value") return <Field key={step.id + ":value"} label={"Value" + (req(name) ? " *" : "")} info={props[name]?.description}>{() =>
      <AssignValue step={step} />}</Field>;
    if (step.action === "core/set_many" && name === "assignments") return <Field key={step.id + ":assignments"} label={"Assignments" + (req(name) ? " *" : "")}
      info={props[name]?.description}>{() => <AssignmentsEditor step={step} />}</Field>;
    if (step.action === "core/run_bot" && name === "automation") return <RunAgentFields key={step.id + ":agent"} step={step} />;
    // C5: Exit loop and Continue loop name a loop around them, picked from a list (AA's "Label of loop to break from")
    if ((step.action === "core/break" || step.action === "core/continue") && name === "label") {
      const loops = loopLabelsAround(vars?.flow.steps ?? [], step.id);
      const value = typeof step.args?.label === "string" ? String(step.args.label) : "";
      return <Field key={step.id + ":label"} label={step.action === "core/break" ? "Loop to leave" : "Loop to continue"} layout="beside"
        hint={loops.length ? "The nearest loop when none is chosen." : "Name a loop (its More options) to pick it here; without a name, the nearest loop."}>{(id) =>
        <Select id={id} value={value} onChange={(e) => onArg("label", e.target.value || undefined)}
          options={[{ value: "", label: "The nearest loop" }, ...loops.map((l) => ({ value: l, label: l })),
            ...(value && !loops.includes(value) ? [{ value, label: value + " (not a loop around this step)" }] : [])]} />}</Field>;
    }
    // D3 and D4: Filter rows' further conditions, and column names picked from the table's known columns
    if (step.action === "ooa.datatable/filter" && name === "more_conditions") return <Field key={step.id + ":more"} label="More conditions" layout="beside"
      hint="Each row is one more check on a column; Rows must meet says whether all of them or any one must hold.">{() =>
      <MoreConditions value={step.args?.more_conditions} columns={vars ? knownColumns(vars.flow, step.args?.table) : []} onCommit={(v) => onArg("more_conditions", v)} />}</Field>;
    if (step.action.startsWith("ooa.datatable/") && (name === "column" || name === "return_column") && props[name]?.["x-ooa-type"] !== "Number")
      return <Field key={step.id + ":" + name} label={labelOf(name) + (req(name) ? " *" : "")} layout="beside" hint={props[name]?.description}>{(id) =>
        <ColumnField id={id} value={step.args?.[name]} columns={vars ? knownColumns(vars.flow, step.args?.table) : []} onCommit={(v) => onArg(name, v)} />}</Field>;
    // X3: a session field offers the sessions the agent's own steps of this toolkit open, and warns about one none opens
    if (name === "session" && !props[name]?.enum && (props[name]?.["x-ooa-type"] ?? "String") === "String" && vars) {
      const [pid, aid] = step.action.split("/");
      return <Field key={step.id + ":session"} label={labelOf(name) + (req(name) ? " *" : "")} layout="beside"
        hint="Which open workbook, document, connection or window this step works on; steps that work on the same one use the same name.">{(id) =>
        <SessionField id={id} value={step.args?.session} fallback={props[name]?.default as string | undefined} opens={/^(open|connect|create|start|login|log_in|launch|attach|begin)(_|$)/.test(aid ?? "")}
          sessions={knownSessions(vars.flow, pid)} onCommit={(v) => onArg("session", v)} />}</Field>;
    }
    // C6: Throw error's code from a list of the usual ones, or a code of your own (AA picks an exception from a list)
    if (step.action === "core/throw" && name === "code") return <ThrowCode key={step.id + ":code"} value={typeof step.args?.code === "string" ? String(step.args.code) : ""} onCommit={(v) => onArg("code", v)} />;
    // C9: Delay is Regular (a fixed time) or Random (between two times), as AA's Delay type
    if (step.action === "core/delay" && name === "seconds") {
      const random = step.args?.random_max !== undefined && step.args.random_max !== "";
      return <div key={step.id + ":delay"} className="st-delay">
        <Field label="Delay type" layout="beside">{() => <div className="st-seg" role="radiogroup" aria-label="Delay type">
          {([["regular", "Regular"], ["random", "Random"]] as const).map(([k, t]) => <button key={k} type="button" role="radio" aria-checked={(k === "random") === random} className="st-seg-btn"
            onClick={() => {
              if ((k === "random") === random) return;
              const from = Number(step.args?.seconds ?? 1);
              onArg("random_max", k === "random" ? (Number.isFinite(from) ? from + 5 : 5) : undefined);
            }}>{t}</button>)}</div>}</Field>
        {plainInput("seconds", random ? "From" : "Wait")}
        {random && plainInput("random_max", "To")}
      </div>;
    }
    if (step.action === "core/delay" && name === "random_max") return null;   // with Delay type, above
    // Run subflow: one of this agent's subflows; Go to: a label it can reach (its own list or one around it)
    if ((step.action === "core/run_subflow" && name === "subflow") || (step.action === "core/goto" && name === "label")) {
      const choices = step.action === "core/goto" ? labelsInScope(vars?.flow.steps ?? [], step.id) : (vars?.flow.subflows ?? []).map((s) => s.name);
      const value = typeof step.args?.[name] === "string" ? String(step.args[name]) : "";
      const none = step.action === "core/goto" ? "No label it can reach: add a Label step first" : "No subflows: add one from More (⋯)";
      return <Field key={step.id + ":" + name} label={labelOf(name) + " *"} layout="beside" info={props[name]?.description}>{(id) =>
        <Select id={id} value={value} onChange={(e) => onArg(name, e.target.value || undefined)}
          options={[{ value: "", label: choices.length ? "Choose…" : none }, ...choices.map((c) => ({ value: c, label: c })),
            ...(value && !choices.includes(value) ? [{ value, label: value + " (not found)" }] : [])]} />}</Field>;
    }
    // with the agent to run; an agent from a variable (C4) is not known here, so its inputs are a Dictionary of their own
    if (step.action === "core/run_bot" && (name === "inputs" || name === "output_map") && !String(step.args?.automation ?? "").startsWith("$")) return null;
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
    const input = <InputField key={step.id + ":" + name} action={step.action} name={name} label={labelOf(name)} spec={props[name] ?? {}} required={req(name)}
      value={step.args?.[name]} onCommit={(v) => onArg(name, v)} apply={(t, f) => setArg(f, step.id, name, parseValue(t, props[name]))} />;
    // a picker beside the field: SOAP operation from the WSDL, directory browser, PDF area, formatted email body
    const picker = pickerFor(step, name, props as Record<string, unknown>);
    return picker ? <div key={step.id + ":" + name + ":with-picker"} className="st-field-with-picker">{input}{picker}</div> : input;
  };
  /**
   * The fields in order, a choice or tick box drawn where its first field stands (X2): a choice shows its options as
   * buttons and only the chosen option's fields; a tick box shows its fields when ticked. Switching clears the fields
   * left behind, so the agent never keeps a value the panel hides.
   */
  const grouped = (names: string[]) => {
    const done = new Set<string>();
    const out: ReactNode[] = [];
    for (const n of names) {
      if (done.has(n)) continue;
      const g = order.groups.find((x) => groupFields(x).includes(n));
      if (!g) { out.push(field(n)); continue; }
      const inside = groupFields(g).filter((f) => names.includes(f));
      inside.forEach((f) => done.add(f));
      out.push(<PanelGroupView key={step.id + ":g:" + ("choice" in g ? g.choice : g.tick)} group={g} step={step} props={props} shown={inside} field={field}
        clear={(fields) => { if (vars) { let f = vars.flow; for (const k of fields) f = setArg(f, step.id, k, undefined); vars.edit(f); } }} />);
    }
    return out;
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
          {mode === "each" && <Field label="Go through" layout="beside" hint={itSpec?.description}>{(id) =>
            <Select id={id} aria-label="Loop type" value={String(step.args?.iterator ?? "list")} onChange={(e) => {
              if (!vars) { onArg("iterator", e.target.value); return; }
              let f = setArg(vars.flow, step.id, "iterator_args", undefined);
              if (e.target.value.includes("/")) f = setArg(f, step.id, "over", undefined);
              vars.edit(setArg(f, step.id, "iterator", e.target.value));
            }}
              options={[...EACH_KINDS.map((k) => ({ value: k.value, label: k.label })),
                // the toolkits' loop types by toolkit, as AA's iterator list (C2)
                ...[...packages].filter((p) => p.iterators?.length).sort((a, b) => a.name.localeCompare(b.name))
                  .flatMap((p) => p.iterators!.map((x) => ({ value: p.id + "/" + x.id, label: x.name, group: p.name }))),
                ...(each || itSpec || !step.args?.iterator ? [] : [{ value: iterator, label: iterator + " (a toolkit's loop type not installed)" }])]} />}</Field>}
          {itSpec && <Field label="Settings" layout="beside">{() =>
            <SpecFields args={(step.args?.iterator_args && typeof step.args.iterator_args === "object" ? step.args.iterator_args : {}) as Record<string, unknown>}
              spec={itSpec} keyPrefix={step.id + iterator} onChange={(a) => {
                const kept = Object.fromEntries(Object.entries(a).filter(([, v]) => v !== undefined && v !== ""));
                onArg("iterator_args", Object.keys(kept).length ? kept : undefined);
              }} />}</Field>}
        </>}
        {grouped(main)}
        {extra.map(field)}
        {!main.length && !extra.length && !isLoop && <p className="st-muted">This step has no main settings.</p>}
      </div>
      {more.length > 0 && (
        <details className="st-more">
          <summary>More options ({more.length})</summary>
          <div className="st-prop-fields">{grouped(more)}</div>
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
      {/* C3: what happens when this step fails, as UiPath's ContinueOnError and Retry Scope */}
      {vars && step.action !== "core/comment" && step.action !== "core/label" && step.action !== "core/step" && <>
        <h3 className="st-prop-section">If this step fails</h3>
        <div className="st-prop-fields">
          <StepErrorFields stepId={step.id} continueOnError={step.continueOnError} retry={step.retry} canRetry={!step.action.startsWith("core/")}
            onChange={(patch) => vars.edit(setStepPolicy(vars.flow, step.id, patch))} />
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

/** Which option of a choice is on: the one with a value set, else one with a default, else the first. */
function chosenOption(g: Extract<PanelGroup, { choice: string }>, step: FlowStep, props: Record<string, FieldSpec>): number {
  const set = (f: string) => step.args?.[f] !== undefined && step.args[f] !== "";
  let i = g.options.findIndex((o) => o.fields.some(set));
  if (i < 0) i = g.options.findIndex((o) => o.fields.some((f) => props[f]?.default !== undefined));
  if (i < 0) i = g.options.findIndex((o) => !o.fields.length);
  return Math.max(0, i);
}

/** A choice (buttons, then the chosen option's fields) or a tick box (its fields when ticked), as the Classic Studio drew them. */
function PanelGroupView({ group, step, props, shown, field, clear }: { group: PanelGroup; step: FlowStep; props: Record<string, FieldSpec>; shown: string[];
  field: (name: string) => ReactNode; clear: (fields: string[]) => void }) {
  const has = (f: string) => step.args?.[f] !== undefined && step.args[f] !== "";
  const [picked, setPicked] = useState<number | null>(null);
  const [ticked, setTicked] = useState<boolean | null>(null);
  if ("choice" in group) {
    const on = picked ?? chosenOption(group, step, props);
    return <div className="st-group-choice">
      <Field label={group.choice} layout="beside">{() => <div className="st-seg" role="radiogroup" aria-label={group.choice}>
        {group.options.map((o, i) => <button key={o.label} type="button" role="radio" aria-checked={i === on} className="st-seg-btn"
          onClick={() => { if (i === on) return; setPicked(i); clear(group.options.filter((_, k) => k !== i).flatMap((x) => x.fields).filter(has)); }}>{o.label}</button>)}
      </div>}</Field>
      {group.options[on]?.fields.filter((f) => shown.includes(f)).map(field)}
    </div>;
  }
  const on = ticked ?? group.fields.some(has);
  return <div className="st-group-tick">
    <label className="st-check-line"><input type="checkbox" checked={on} onChange={(e) => { setTicked(e.target.checked); if (!e.target.checked) clear(group.fields.filter(has)); }} /> {group.tick}</label>
    {on && group.fields.filter((f) => shown.includes(f)).map(field)}
  </div>;
}

/** The usual codes for Throw error (C6), in plain words; a Catch can name them, or a kind such as NOT_FOUND. */
const THROW_CODES: [string, string][] = [
  ["BUSINESS.ERROR", "A business rule was not met"], ["VALIDATION.FAILED", "The data is not valid"], ["DATA.NOT_FOUND", "Something needed was not found"],
  ["DATA.DUPLICATE", "It was done or exists already"], ["SYSTEM.UNAVAILABLE", "A system is down or not answering"], ["PROCESS.TIMEOUT", "It took too long"],
  ["ACCESS.DENIED", "Not allowed (sign-in or permission)"]];
const OWN = "\u0000own";

/** Throw error's code: one of the usual ones, or your own (letters, digits, _ and dots, e.g. BUSINESS.INVOICE_DUPLICATE). */
function ThrowCode({ value, onCommit }: { value: string; onCommit: (v: string | undefined) => void }) {
  const known = THROW_CODES.some(([c]) => c === value);
  const [own, setOwn] = useState(!known && value !== "");
  return <Field label="Error code *" layout="beside" hint="A Catch takes errors by code, by a prefix such as BUSINESS.* or by a kind such as NOT_FOUND.">{(id) => <>
    <Select id={id} value={own ? OWN : value} onChange={(e) => { if (e.target.value === OWN) setOwn(true); else { setOwn(false); onCommit(e.target.value || undefined); } }}
      options={[{ value: "", label: "Choose…" }, ...THROW_CODES.map(([c, words]) => ({ value: c, label: words + " (" + c + ")" })), { value: OWN, label: "A code of your own…" }]} />
    {own && <CommitInput key={"own" + value} label="Your own error code" mono want="String" initial={known ? "" : value} placeholder="BUSINESS.INVOICE_DUPLICATE"
      onCommit={(t) => onCommit(t.trim() || undefined)} />}
  </>}</Field>;
}

/** File fields that take a folder (Browse… then chooses a folder). */
const FOLDER_FIELD = /(^|_)(folder|directory|dir)$/;
const isNumber = (spec?: FieldSpec) => spec?.["x-ooa-type"] === "Number" || spec?.type === "number";
/** A Number field as typed: a number, a variable, or anything else as a calculation ({expr}), so "$n - 1" needs no JSON. */
function parseValue(text: string, spec?: FieldSpec): unknown {
  const v = parseLoose(text);
  if (!isNumber(spec) || typeof v !== "string" || /^\$[A-Za-z_][\w.]*$/.test(v.trim())) return v;
  return { expr: v.trim().replace(/^=\s*/, "") };
}
const showNumber = (v: unknown) => v && typeof v === "object" && typeof (v as { expr?: unknown }).expr === "string" ? (v as { expr: string }).expr : showValue(v);

function InputField({ action, name, label, spec, required, value, onCommit, apply }:
  { action: string; name: string; label: string; spec: FieldSpec; required: boolean; value: unknown; onCommit: (v: unknown) => void; apply: (text: string, flow: Flow) => Flow }) {
  const folderAction = action.startsWith("ooa.folder/") && name !== "shortcut";
  const fallback = spec.default !== undefined ? "Default: " + showValue(spec.default) : required ? "" : "Not set";
  const info = helpText(spec, required) || undefined;
  return (
    <Field label={(name.endsWith("_bytes") ? label.replace(/ \(bytes\)$/, "") : label) + (required ? " *" : "")} layout="beside" info={info}>{(id) => {
      if (spec.enum?.length) {
        // a Number field with choices (e.g. key length 128/256) keeps the number type when a choice is picked
        return <Select id={id} value={value === undefined ? "" : String(value)} onChange={(e) => onCommit(e.target.value === "" ? undefined : isNumber(spec) ? Number(e.target.value) : e.target.value)}
          options={[{ value: "", label: spec.default !== undefined ? "Default: " + enumWord(spec.default) : fallback || "Choose…" }, ...spec.enum.map((v) => ({ value: String(v), label: enumWord(v) }))]} />;
      }
      // pickers for what people typed and got wrong (D1, D2, D8): time zone, date pattern, language and region
      const asText = (v: string | undefined) => onCommit(v);
      if (name === "time_zone" && spec["x-ooa-type"] !== "Dictionary") return <TimeZoneField id={id} value={value} local={action.startsWith("ooa.datetime/")} onCommit={asText} />;
      if (name === "pattern" && /^ooa\.datetime\/(format|parse)$/.test(action)) return <DatePatternField id={id} value={value} fallback={spec.default as string | undefined} onCommit={asText} />;
      if (name === "locale" && (spec["x-ooa-type"] === "String" || spec["x-ooa-type"] === undefined)) return <LocaleField id={id} value={value} fallback={spec.default as string | undefined} onCommit={asText} />;
      // F2, F3, F4: delimiter and encoding from the lists every toolkit reads, sizes with a unit
      if (name === "delimiter" && !spec.enum) return <DelimiterField id={id} value={value} fallback={spec.default as string | undefined} onCommit={asText} />;
      if (name === "encoding" && !spec.enum) return <EncodingField id={id} value={value} fallback={spec.default as string | undefined} detect={action.startsWith("ooa.file/read")} onCommit={asText} />;
      if (name.endsWith("_bytes") && isNumber(spec)) return <SizeField id={id} value={value} onCommit={(v) => onCommit(v)} />;
      // by kind (docs/research/input-help-check.md): a value or a variable, Browse, the vault, the open windows
      const kind = { id, name, value, fallback, onCommit, apply };
      const ooaType = spec["x-ooa-type"];
      if (spec.type === "boolean") return <BoolField {...kind} />;
      if (ooaType === "DateTime") return <DateField {...kind} />;
      if (ooaType === "List" || ooaType === "Dictionary" || ooaType === "Table") return <CollectionField {...kind} type={ooaType} />;
      if (ooaType === "Credential") return <CredentialField {...kind} />;
      if (ooaType === "File") return <FileField {...kind} folder={FOLDER_FIELD.test(name) || folderAction} />;
      if ((name === "window" || spec["x-ooa-label"] === "Window") && (ooaType === "String" || ooaType === undefined)) return <WindowField {...kind} />;
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
