import { useEffect, useRef, useState } from "react";
import { Button, Select, TextInput } from "../components";
import { parseLoose, showValue } from "./flowEdit";
import { OPS, conditionText } from "./toolkits";

/** One check: a value, a comparison, and (for most comparisons) a second value. */
type Check = { left: string; op: string; right: string };
type Model = { mode: "all" | "any"; checks: Check[] } | { mode: "advanced"; text: string };

const NO_RIGHT = new Set(["empty", "notEmpty"]);
const isCheck = (c: unknown): c is { op: string; left?: unknown; right?: unknown } =>
  typeof c === "object" && c !== null && typeof (c as { op?: unknown }).op === "string";

/** The condition as rows of checks, when it is that simple; anything else (an expression, Not, nesting) is advanced. */
function toModel(value: unknown): Model {
  const row = (c: { op: string; left?: unknown; right?: unknown }): Check => ({ left: showValue(c.left), op: c.op, right: showValue(c.right) });
  if (value === undefined || value === "") return { mode: "all", checks: [{ left: "", op: "eq", right: "" }] };
  if (isCheck(value)) return { mode: "all", checks: [row(value)] };
  const v = value as { all?: unknown[]; any?: unknown[] };
  for (const mode of ["all", "any"] as const) {
    const list = v[mode];
    if (Array.isArray(list) && list.length && list.every(isCheck)) return { mode, checks: list.map(row) };
  }
  return { mode: "advanced", text: showValue(value) };
}

function toValue(m: Model): unknown {
  if (m.mode === "advanced") return parseLoose(m.text);
  const checks = m.checks.filter((c) => c.left.trim() !== "").map((c) => {
    const out: Record<string, unknown> = { op: c.op, left: c.left.trim() };
    if (!NO_RIGHT.has(c.op)) out.right = parseLoose(c.right) ?? "";
    return out;
  });
  if (!checks.length) return undefined;
  return checks.length === 1 ? checks[0] : { [m.mode]: checks };
}

/**
 * Builds a condition in plain words, as AA and UiPath do: "All of these are true" (or any of them), then rows of
 * value · comparison · value. The sentence it makes is shown below. Conditions too rich for rows (expressions, Not,
 * groups inside groups) stay editable as text. Each change is one undo step: text when its field loses focus.
 */
export function ConditionEditor({ value, onCommit }: { value: unknown; onCommit: (v: unknown) => void }) {
  const [model, setModelState] = useState<Model>(() => toModel(value));
  // the latest model, also between a keystroke and the next redraw, so leaving a field saves what is in it
  const latest = useRef(model);
  const setModel = (m: Model) => { latest.current = m; setModelState(m); };
  // the condition changed from outside (Undo, Redo): show it; our own changes match already, so focus stays put
  useEffect(() => {
    if (JSON.stringify(toValue(latest.current)) !== JSON.stringify(value)) setModel(toModel(value));
  }, [value]);
  const commit = (m: Model) => { setModel(m); const v = toValue(m); if (JSON.stringify(v) !== JSON.stringify(value)) onCommit(v); };

  if (model.mode === "advanced") {
    return (
      <div className="st-cond">
        <TextInput aria-label="Condition as an expression" mono defaultValue={model.text} onBlur={(e) => commit({ mode: "advanced", text: e.currentTarget.value })} />
        <p className="st-cond-sentence">{value === undefined ? "No condition yet." : conditionText(value)}</p>
        <Button kind="plain" onClick={() => setModel(toModel(undefined))}>Build it with checks instead</Button>
      </div>
    );
  }
  const set = (i: number, change: Partial<Check>, now = true) => {
    const m = latest.current;
    if (m.mode === "advanced") return;
    const next = { ...m, checks: m.checks.map((c, k) => k === i ? { ...c, ...change } : c) };
    if (now) commit(next); else setModel(next);
  };
  return (
    <div className="st-cond">
      {model.checks.length > 1 && (
        <Select aria-label="Match" value={model.mode} onChange={(e) => commit({ ...model, mode: e.target.value as "all" | "any" })}
          options={[{ value: "all", label: "All of these are true" }, { value: "any", label: "Any of these is true" }]} />
      )}
      {model.checks.map((c, i) => (
        <div key={i} className="st-cond-row" role="group" aria-label={"Check " + (i + 1)}>
          <TextInput aria-label="Value" mono value={c.left} placeholder="$total" onChange={(e) => set(i, { left: e.target.value }, false)} onBlur={(e) => set(i, { left: e.currentTarget.value })} />
          <Select aria-label="Comparison" value={c.op} onChange={(e) => set(i, { op: e.target.value })}
            options={Object.entries(OPS).map(([op, words]) => ({ value: op, label: words }))} />
          {!NO_RIGHT.has(c.op) && <TextInput aria-label="Compared with" value={c.right} placeholder="1000" onChange={(e) => set(i, { right: e.target.value }, false)} onBlur={(e) => set(i, { right: e.currentTarget.value })} />}
          {model.checks.length > 1 && <Button kind="plain" aria-label={"Remove check " + (i + 1)} onClick={() => commit({ ...model, checks: model.checks.filter((_, k) => k !== i) })}>Remove</Button>}
        </div>
      ))}
      <div className="st-cond-actions">
        <Button kind="secondary" icon="plus" onClick={() => setModel({ ...model, checks: [...model.checks, { left: "", op: "eq", right: "" }] })}>Add a check</Button>
        <Button kind="plain" onClick={() => setModel({ mode: "advanced", text: showValue(value) })}>Write it as an expression</Button>
      </div>
      <p className="st-cond-sentence">{value === undefined ? "No condition yet." : "Reads: " + conditionText(value)}</p>
    </div>
  );
}
