import { useRef, useState } from "react";
import type { Flow, FlowStep, FlowVariable } from "../api/client";
import { Button, is, Select, TextInput, withKey } from "../components";
import { parseLoose, setArg, setOut, showValue } from "./flowEdit";
import { useVars, VarPicker } from "./VariablesPanel";
import { holdsType, isInput, isOutput } from "./variables";

/**
 * A text field that reports its value when it loses focus or on Enter, not on every key. Callers key it on the value,
 * so undo shows. With `want` it has a {x} button (and Ctrl+Space) to put a variable at the cursor, and typing `$`
 * opens the same list (`$@` at the Shared Global values), the `$` replaced by what is picked; `apply` is what
 * "+ New variable" changes in the agent once the variable is made.
 */
export function CommitInput({ id, initial, placeholder, mono, onCommit, want, apply, nameHint, label }:
  { id?: string; initial: string; placeholder?: string; mono?: boolean; onCommit: (text: string) => void;
    want?: string; apply?: (text: string, flow: Flow) => Flow; nameHint?: string; label?: string }) {
  const ref = useRef<HTMLInputElement>(null);
  const [picking, setPicking] = useState<DOMRect | null>(null);
  // the "$" typed that opened the list: where it is, so what is picked takes its place
  const typedAt = useRef<number | null>(null);
  const vars = useVars();
  const commit = (el: HTMLInputElement) => { if (el.value !== initial) onCommit(el.value); };
  const withRef = (r: string) => {
    const el = ref.current;
    const t = el?.value ?? initial;
    if (typedAt.current !== null) { const at = typedAt.current; return t.slice(0, at) + r + t.slice(at + 1); }
    const a = el?.selectionStart ?? t.length, b = el?.selectionEnd ?? t.length;
    return t.slice(0, a) + r + t.slice(b);
  };
  const open = (el: HTMLInputElement, at: number | null) => { typedAt.current = at; setPicking(el.getBoundingClientRect()); };
  const input = <TextInput ref={ref} id={id} aria-label={label} defaultValue={initial} placeholder={placeholder} mono={mono}
    onBlur={(e) => { if (!picking) commit(e.currentTarget); }}
    onInput={(e) => {
      // "$" typed at the start or after a space or symbol (not "US$" or a price like "5$") opens the variable list
      const el = e.currentTarget, at = (el.selectionStart ?? 0) - 1;
      if (want && vars && at >= 0 && el.value[at] === "$" && (at === 0 || !/[\w$]/.test(el.value[at - 1]))) open(el, at);
    }}
    onKeyDown={(e) => {
      if (e.key === "Enter") { e.preventDefault(); commit(e.currentTarget); }
      else if (want && vars && is(e.nativeEvent, "fields.variable")) { e.preventDefault(); open(e.currentTarget, null); }
    }} />;
  if (!want || !vars) return input;
  return (
    <div className="st-var-field">
      {input}
      <button type="button" className="st-x-btn" aria-label="Insert a value" title={withKey("Insert a value", "fields.variable")} aria-expanded={!!picking}
        onMouseDown={(e) => e.preventDefault()} onClick={() => { typedAt.current = null; setPicking(picking ? null : (ref.current ?? undefined)?.getBoundingClientRect() ?? null); }}>{"{x}"}</button>
      {picking && <VarPicker want={want} nameHint={nameHint} anchor={picking} typed={typedAt.current !== null} previewOf={withRef}
        onClose={() => { setPicking(null); typedAt.current = null; ref.current?.focus(); }}
        onPick={(r) => { setPicking(null); onCommit(withRef(r)); typedAt.current = null; }}
        onNew={apply && ((r, flow) => apply(withRef(r), flow))} />}
    </div>
  );
}

const NEW = "\u0000new";
const VARIABLE = "\u0000variable";

/**
 * Where a step's result is kept: only variables that can hold its type (a Number result, a Number variable), text
 * results also in Number or Yes/No variables (converted), a current choice that does not fit stays listed and
 * marked; or a new variable of the result's type, named after the result.
 */
export function OutputChooser({ step, output, type, label }: { step: FlowStep; output: string; type?: string; label: string }) {
  const vars = useVars();
  if (!vars) return null;
  const cur = step.out?.[output] ?? "";
  const all = vars.flow.variables ?? [];
  const converts = (v: FlowVariable) => type === "String" && (v.type === "Number" || v.type === "Boolean");
  const fit = all.filter((v) => holdsType(v.type, type) || converts(v));
  const opts = [{ value: "", label: "(do not keep)" },
    ...fit.map((v) => ({ value: v.name, label: "$" + v.name + " (" + v.type + (converts(v) ? ", converted from text" : "") + ")" }))];
  const wrong = cur && !fit.some((v) => v.name === cur) ? all.find((v) => v.name === cur) : undefined;
  if (wrong) opts.push({ value: wrong.name, label: "$" + wrong.name + " (" + wrong.type + ": does not match " + type + ")" });
  else if (cur && !all.some((v) => v.name === cur)) opts.push({ value: cur, label: "$" + cur + " (not a variable of this agent)" });
  opts.push({ value: NEW, label: "+ New " + (type && type !== "Any" ? type + " " : "") + "variable" });
  return <Select aria-label={label} value={cur} onChange={(e) => {
    const v = e.target.value;
    if (v === NEW) vars.newVariable({ type: type && type !== "Any" ? type : "String", name: output, then: (n, f) => setOut(f, step.id, output, n) });
    else applyNow(vars, (f) => setOut(f, step.id, output, v));
  }} options={opts} />;
}

/**
 * The variable a value goes into, for the Catch part and On error (usage review items 19 and 20, as AA's "Assign
 * exception message to"): the agent's variables that hold the type, or a new one of that type. A Dictionary also goes
 * into an Any variable; text also into an Any variable.
 */
export function VariableChooser({ value, type, label, nameHint, onPick }: { value?: string; type: string; label: string; nameHint: string;
  onPick: (f: Flow, name: string | undefined) => Flow }) {
  const vars = useVars();
  if (!vars) return null;
  const cur = value ?? "";
  const all = vars.flow.variables ?? [];
  const fit = all.filter((v) => holdsType(v.type, type) || v.type === "Any");
  const opts = [{ value: "", label: "(not kept)" }, ...fit.map((v) => ({ value: v.name, label: "$" + v.name + " (" + v.type + ")" }))];
  const other = cur && !fit.some((v) => v.name === cur) ? all.find((v) => v.name === cur) : undefined;
  if (other) opts.push({ value: other.name, label: "$" + other.name + " (" + other.type + ": does not hold " + type + ")" });
  else if (cur && !all.some((v) => v.name === cur)) opts.push({ value: cur, label: "$" + cur + " (not a variable of this agent)" });
  opts.push({ value: NEW, label: "+ New " + type + " variable" });
  return <Select aria-label={label} value={cur} onChange={(e) => {
    const v = e.target.value;
    if (v === NEW) vars.newVariable({ type, name: nameHint, then: (n, f) => onPick(f, n) });
    else applyNow(vars, (f) => onPick(f, v || undefined));
  }} options={opts} />;
}

/** Steps' fields change the agent through the context's editor, from the agent as it is now. */
function applyNow(vars: NonNullable<ReturnType<typeof useVars>>, change: (f: Flow) => Flow) { vars.edit(change(vars.flow)); }

const isVarRef = (v: unknown) => typeof v === "string" && /^\$[A-Za-z_][A-Za-z0-9_]*$/.test(v.trim());
const isCalc = (v: unknown): v is { expr: string } => !!v && typeof v === "object" && !Array.isArray(v) && typeof (v as { expr?: unknown }).expr === "string";

/**
 * Text typed into a field (Set text and the like): from a Credential variable (for passwords: never typed into the
 * agent), another variable, plain text with $name and ${name} in it, or a calculation worked out when the step runs.
 * `onlyCredential` (assigning to a Credential variable) offers credentials only.
 */
export function TextSource({ step, name, onlyCredential }: { step: FlowStep; name: string; onlyCredential?: boolean }) {
  const vars = useVars();
  const value = step.args?.[name];
  const vname = isVarRef(value) ? String(value).trim().slice(1) : "";
  const vdef = vars?.flow.variables?.find((v) => v.name === vname);
  const [mode, setMode] = useState<"credential" | "variable" | "plain" | "calc">(onlyCredential ? "credential"
    : vname ? (vdef?.type === "Credential" ? "credential" : "variable") : isCalc(value) ? "calc" : "plain");
  if (!vars) return null;
  const set = (v: unknown) => applyNow(vars, (f) => setArg(f, step.id, name, v));
  const modes = [["credential", "Credential"], ["variable", "Variable"], ["plain", "Text"], ["calc", "Calculation"]] as const;
  const list = (vars.flow.variables ?? []).filter((v) => mode === "credential" ? v.type === "Credential" : v.type !== "Credential");
  return (
    <div className="st-source">
      {!onlyCredential && <div className="st-seg" role="radiogroup" aria-label="Text comes from">
        {modes.map(([k, t]) => <button key={k} type="button" role="radio" aria-checked={mode === k} className="st-seg-btn" onClick={() => setMode(k)}>{t}</button>)}
      </div>}
      {mode === "calc" ? <CommitInput key={"calc" + (isCalc(value) ? value.expr : "")} label="Calculation" mono want="Any" initial={isCalc(value) ? value.expr : ""}
          placeholder="upper($row.Name) or $qty * 2" onCommit={(t) => set(t.trim() ? { expr: t.trim() } : undefined)}
          apply={(t, f) => setArg(f, step.id, name, { expr: t.trim() })} />
        : mode === "plain" ? <CommitInput key={"plain" + showValue(value)} label="Text" want="String" initial={vname || isCalc(value) || value == null ? "" : String(value)}
          onCommit={(t) => set(t === "" ? undefined : t)} apply={(t, f) => setArg(f, step.id, name, t)} />
        : <div className="st-row-tight">
            <Select aria-label={mode === "credential" ? "Credential variable" : "Variable"} value={list.some((v) => v.name === vname) ? vname : ""}
              onChange={(e) => set(e.target.value ? "$" + e.target.value : undefined)}
              options={[{ value: "", label: mode === "credential" ? "Choose a credential variable…" : "Choose a variable…" },
                ...list.map((v) => ({ value: v.name, label: "$" + v.name + " (" + v.type + ")" }))]} />
            <Button kind="secondary" icon="plus" title={mode === "credential" ? "A Credential variable: its value comes from the vault when the agent runs" : "A new variable"}
              onClick={() => vars.newVariable(mode === "credential"
                ? { type: "Credential", name: "password", direction: "input", description: "From the vault: map it to a credential when running.", then: (n, f) => setArg(f, step.id, name, "$" + n) }
                : { type: "String", name: "text_value", then: (n, f) => setArg(f, step.id, name, "$" + n) })}>New</Button>
          </div>}
      <p className="ui-field-hint">{onlyCredential ? "A credential comes from another Credential variable, mapped to the vault when the agent runs; it is never typed into the agent."
        : mode === "plain" ? "Typed as written; $row.Name or ${amount} put a variable's value in the text. Use a credential for passwords."
        : mode === "calc" ? "Worked out when the step runs, then typed: upper(), round($x, 2), $a + $b."
        : mode === "credential" ? "From the vault when the agent runs; never shown or logged." : "The variable's value when the step runs."}</p>
    </div>
  );
}

/**
 * The value of Assign: text or a value (saved as typed; $name copies a variable with its type) or a calculation
 * worked out when the step runs. Assigning to a Credential variable takes a credential only.
 */
export function AssignValue({ step }: { step: FlowStep }) {
  const vars = useVars();
  const value = step.args?.value;
  const target = vars?.flow.variables?.find((v) => v.name === String(step.args?.variable ?? "").replace(/^\$/, ""));
  const [calc, setCalc] = useState(isCalc(value));
  if (!vars) return null;
  if (target?.type === "Credential") return <TextSource step={step} name="value" onlyCredential />;
  const set = (v: unknown) => applyNow(vars, (f) => setArg(f, step.id, "value", v));
  return (
    <div className="st-source">
      <div className="st-seg" role="radiogroup" aria-label="Value is">
        <button type="button" role="radio" aria-checked={!calc} className="st-seg-btn" onClick={() => { if (calc) { setCalc(false); if (isCalc(value)) set(value.expr); } }}>Text or value</button>
        <button type="button" role="radio" aria-checked={calc} className="st-seg-btn" onClick={() => { if (!calc) { setCalc(true); if (typeof value === "string" && value.trim()) set({ expr: value.trim() }); } }}>Calculation</button>
      </div>
      {calc ? <CommitInput key={"c" + (isCalc(value) ? value.expr : "")} label="Calculation" mono want={target?.type ?? "Any"} initial={isCalc(value) ? value.expr : ""}
          placeholder="$count + 1" onCommit={(t) => { const x = t.trim().replace(/^=\s*/, ""); set(x ? { expr: x } : undefined); }}
          apply={(t, f) => setArg(f, step.id, "value", { expr: t.trim() })} />
        : <CommitInput key={"v" + showValue(value)} label="Value" want={target?.type ?? "Any"} initial={isCalc(value) ? "" : showValue(value)}
          placeholder={target?.type === "Number" ? "42 or $other" : "text, $variable or JSON"} onCommit={(t) => set(parseLoose(t))}
          apply={(t, f) => setArg(f, step.id, "value", parseLoose(t))} />}
      <p className="ui-field-hint">{calc ? "Worked out when the step runs: + - * / %, comparisons, && and ||, and functions such as round($x, 2) or upper($name)."
        : "Saved as typed. $name copies a variable (keeping its type); ${name} puts a value inside text: Invoice ${number}."}</p>
    </div>
  );
}

type Assignment = { variable?: string; value?: unknown };

/** A row's value as typed: a calculation shows as =expr, anything else as for Assign. */
export const assignmentText = (v: unknown) => isCalc(v) ? "=" + v.expr : showValue(v);
/** Typed text back to a value: =expr is a calculation worked out when the step runs. */
export const assignmentValue = (t: string): unknown => {
  const s = t.trim();
  if (s.startsWith("=") && s.length > 1) return { expr: s.slice(1).trim() };
  return t === "" ? undefined : parseLoose(t);
};

/**
 * Assign several variables: one row per variable, set in order from the top, so a row can use a variable set above
 * it. A value starting with = is a calculation; a Credential variable takes another Credential variable.
 */
export function AssignmentsEditor({ step }: { step: FlowStep }) {
  const vars = useVars();
  if (!vars) return null;
  const rows: Assignment[] = Array.isArray(step.args?.assignments) ? step.args!.assignments as Assignment[] : [];
  const all = vars.flow.variables ?? [];
  const put = (next: Assignment[], f: Flow) => setArg(f, step.id, "assignments", next);
  const change = (i: number, row: Assignment) => applyNow(vars, (f) => put(rows.map((r, k) => k === i ? row : r), f));
  const move = (i: number, by: number) => {
    const next = [...rows]; const [r] = next.splice(i, 1); next.splice(i + by, 0, r);
    applyNow(vars, (f) => put(next, f));
  };
  return (
    <div className="st-assignments">
      {rows.map((r, i) => {
        const target = all.find((v) => v.name === r.variable);
        const known = !r.variable || !!target;
        return <div key={i} className="st-assignment" role="group" aria-label={"Assignment " + (i + 1)}>
          <Select aria-label={"Variable " + (i + 1)} value={r.variable ?? ""} onChange={(e) => change(i, { ...r, variable: e.target.value || undefined })}
            options={[{ value: "", label: "Variable…" }, ...all.map((v) => ({ value: v.name, label: "$" + v.name + " (" + v.type + ")" })),
              ...(known ? [] : [{ value: r.variable!, label: "$" + r.variable + " (not a variable of this agent)" }])]} />
          {target?.type === "Credential"
            ? <Select aria-label={"Value " + (i + 1)} value={typeof r.value === "string" ? r.value.replace(/^\$/, "") : ""}
                onChange={(e) => change(i, { ...r, value: e.target.value ? "$" + e.target.value : undefined })}
                options={[{ value: "", label: "Credential variable…" }, ...all.filter((v) => v.type === "Credential" && v.name !== r.variable)
                  .map((v) => ({ value: v.name, label: "$" + v.name }))]} />
            : <CommitInput key={i + ":" + assignmentText(r.value)} label={"Value " + (i + 1)} want={target?.type ?? "Any"} initial={assignmentText(r.value)}
                placeholder={target?.type === "Number" ? "42, $other or =$count + 1" : "text, $variable or =calculation"}
                onCommit={(t) => change(i, { ...r, value: assignmentValue(t) })}
                apply={(t, f) => put(rows.map((x, k) => k === i ? { ...r, value: assignmentValue(t) } : x), f)} />}
          <div className="st-assignment-tools">
            <button type="button" className="st-x-btn" aria-label={"Move assignment " + (i + 1) + " up"} disabled={i === 0} onClick={() => move(i, -1)}>↑</button>
            <button type="button" className="st-x-btn" aria-label={"Move assignment " + (i + 1) + " down"} disabled={i === rows.length - 1} onClick={() => move(i, 1)}>↓</button>
            <button type="button" className="st-x-btn" aria-label={"Remove assignment " + (i + 1)} onClick={() => applyNow(vars, (f) => put(rows.filter((_, k) => k !== i), f))}>×</button>
          </div>
        </div>;
      })}
      <div className="st-row-tight">
        <Button kind="secondary" icon="plus" onClick={() => applyNow(vars, (f) => put([...rows, {}], f))}>Add assignment</Button>
      </div>
      <p className="ui-field-hint">Set in order from the top, so a row can use a variable set above it. Values are saved as typed ($name copies a variable);
        start with = for a calculation: =$price * $qty.</p>
    </div>
  );
}

/** Run agent: which agent of the workspace, a value for each of its inputs, and where each of its outputs is kept. */
export function RunAgentFields({ step }: { step: FlowStep }) {
  const vars = useVars();
  if (!vars) return null;
  const id = String(step.args?.automation ?? "");
  const child = vars.agents.find((a) => a.id === id);
  const inputs = (step.args?.inputs && typeof step.args.inputs === "object" ? step.args.inputs : {}) as Record<string, unknown>;
  const outMap = (step.args?.output_map && typeof step.args.output_map === "object" ? step.args.output_map : {}) as Record<string, string>;
  const set = (name: string, v: unknown) => applyNow(vars, (f) => setArg(f, step.id, name, v));
  const putInput = (v: FlowVariable, text: string, f: Flow): Flow => {
    const next = { ...inputs };
    if (text === "") delete next[v.name];
    else if (v.type === "Number" && Number.isFinite(Number(text))) next[v.name] = Number(text);
    else if (v.type === "Boolean" && /^(true|false)$/.test(text.trim())) next[v.name] = text.trim() === "true";
    else next[v.name] = text;
    const value = Object.keys(next).length ? next : undefined;
    return setArg(f, step.id, "inputs", value);
  };
  const putOut = (out: string, local: string, f: Flow) => {
    const next = { ...outMap };
    if (local) next[out] = local; else delete next[out];
    return setArg(f, step.id, "output_map", Object.keys(next).length ? next : undefined);
  };
  const others = vars.agents.filter((a) => a.id && a.id !== vars.flow.id);
  // the agent can also come from a variable (C4, as AA's Task Bot to run > Variable): its id is read when the step runs
  const fromVariable = id.startsWith("$");
  /**
   * Quick map (C4, as AA): each input and output of the child goes to the agent's variable of the same name; a missing
   * one is made with the child's type. Values already set stay as they are.
   */
  const quickMap = (f: Flow): Flow => {
    if (!child) return f;
    const mine = [...(f.variables ?? [])];
    const ensure = (v: FlowVariable) => { if (!mine.some((x) => x.name === v.name)) mine.push({ name: v.name, type: v.type || "Any", description: v.description }); };
    const ins = { ...inputs }, outs = { ...outMap };
    for (const v of (child.variables ?? []).filter(isInput)) if (ins[v.name] === undefined) { ensure(v); ins[v.name] = "$" + v.name; }
    for (const v of (child.variables ?? []).filter(isOutput)) if (!outs[v.name]) { ensure(v); outs[v.name] = v.name; }
    let g: Flow = { ...f, variables: mine };
    g = setArg(g, step.id, "inputs", Object.keys(ins).length ? ins : undefined);
    return setArg(g, step.id, "output_map", Object.keys(outs).length ? outs : undefined);
  };
  return (
    <div className="st-run-agent">
      <label className="st-label" htmlFor={step.id + "-agent"}>Agent to run *</label>
      <Select id={step.id + "-agent"} value={fromVariable ? VARIABLE : id} onChange={(e) => set("automation", e.target.value === VARIABLE ? "$" : e.target.value || undefined)}
        options={[{ value: "", label: others.length ? "Choose an agent…" : "No other agents in this workspace" },
          ...others.map((a) => ({ value: a.id, label: (a.name || a.id) + " — " + a.path })),
          { value: VARIABLE, label: "From a variable (its agent id)…" },
          ...(id && !child && !fromVariable ? [{ value: id, label: id + " (not in this workspace)" }] : [])]} />
      {fromVariable && <CommitInput key={id} label="Variable with the agent id" mono want="String" initial={id === "$" ? "" : id} placeholder="$agent_id"
        onCommit={(t) => set("automation", t.trim() ? (t.trim().startsWith("$") ? t.trim() : "$" + t.trim()) : "$")} />}
      {child && vars.openChild && <button type="button" className="st-link" onClick={() => vars.openChild!(step.id)}>Open this agent</button>}
      {!child ? <p className="ui-field-hint">{fromVariable ? "The agent is chosen when the step runs: give its inputs as a Dictionary (Inputs, below); all its outputs come back in Save results in › Outputs."
        : id ? "This agent is not in the workspace, so its inputs are not known." : "Choose the agent first."}</p> : <>
        <div className="st-row-tight"><Button kind="secondary" onClick={() => applyNow(vars, quickMap)}
          title="Each input and output goes to this agent's variable of the same name; missing ones are made">Quick map</Button>
          <span className="ui-field-hint">Same-name variables; missing ones are made.</span></div>
        <h4 className="st-sub">Input values</h4>
        {(child.variables ?? []).filter(isInput).map((v) => {
          const val = inputs[v.name];
          return <div key={v.name} className="ui-field ui-field-beside">
            <span className="st-label">{v.name}{v.required ? " *" : ""} <span className="st-type-chip">{v.type}</span></span>
            <div className="ui-field-control">
              <CommitInput key={v.name + showValue(val)} label={"Input " + v.name} initial={val === undefined ? "" : showValue(val)} want={v.type}
                placeholder={v.type === "Credential" ? "$credential variable" : v.default !== undefined ? "default: " + showValue(v.default) : v.required ? "required" : "(not set)"}
                onCommit={(t) => applyNow(vars, (f) => putInput(v, t, f))} apply={(t, f) => putInput(v, t, f)} nameHint={v.name} />
              {v.description && <div className="ui-field-hint">{v.description}</div>}
            </div>
          </div>;
        })}
        {!(child.variables ?? []).some(isInput) && <p className="ui-field-hint">The agent has no input variables.</p>}
        <h4 className="st-sub">Save outputs to</h4>
        {(child.variables ?? []).filter(isOutput).map((v) => {
          const fit = (vars.flow.variables ?? []).filter((x) => holdsType(x.type, v.type) || x.name === outMap[v.name]);
          return <div key={v.name} className="ui-field ui-field-beside">
            <span className="st-label">{v.name} <span className="st-type-chip">{v.type}</span></span>
            <div className="ui-field-control">
              <Select aria-label={"Variable for " + v.name} value={outMap[v.name] ?? ""} onChange={(e) => {
                const x = e.target.value;
                if (x === NEW) vars.newVariable({ type: v.type || "Any", name: v.name, description: "Output " + v.name + " of " + (child.name || child.id) + ".", then: (n, f) => putOut(v.name, n, f) });
                else applyNow(vars, (f) => putOut(v.name, x, f));
              }} options={[{ value: "", label: "(do not keep)" }, ...fit.map((x) => ({ value: x.name, label: "$" + x.name })), { value: NEW, label: "+ New variable “" + v.name + "”" }]} />
            </div>
          </div>;
        })}
        {!(child.variables ?? []).some(isOutput) && <p className="ui-field-hint">The agent has no output variables.</p>}
        {(child.variables ?? []).some(isOutput) && <p className="ui-field-hint">Or keep them all in one Dictionary: Save results in › Outputs, below (each key is an output's name).</p>}
      </>}
    </div>
  );
}
