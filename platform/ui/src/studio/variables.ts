import type { Flow, FlowStep, FlowVariable, RunEvent } from "../api/client";
import { innerLists } from "./flowEdit";

/**
 * Variables, as pure functions: their types and colours, how they are used (input, output, both, inside), which
 * variable fits which field or result, renaming everywhere, and the values a run left. Matches Classic Studio's
 * rules (studio.js compatible, holds, renameVariableRefs), so both Studios offer the same choices.
 */

export const TYPES = ["String", "Number", "Boolean", "DateTime", "List", "Dictionary", "Table", "Record", "File", "Window", "UIElement", "Credential", "Any"] as const;

/** A colour (white letters at 4.5:1 or more) and a plain description for each type. */
export const TYPE_STYLE: Record<string, { color: string; short: string; hint: string }> = {
  String: { color: "#1d4ed8", short: "Abc", hint: "Text" }, Number: { color: "#047857", short: "123", hint: "Whole or decimal number" },
  Boolean: { color: "#6d28d9", short: "Y/N", hint: "True or false" }, DateTime: { color: "#a14d05", short: "Date", hint: "Date and time" },
  List: { color: "#0e7490", short: "[ ]", hint: "Ordered items" }, Dictionary: { color: "#be185d", short: "{ }", hint: "Named keys and values" },
  Table: { color: "#15803d", short: "Tbl", hint: "Rows and columns" }, Record: { color: "#7e22ce", short: "Rec", hint: "One row of named values" },
  File: { color: "#475569", short: "File", hint: "File or folder path" }, Window: { color: "#c2410c", short: "Win", hint: "An application window" },
  UIElement: { color: "#9a3412", short: "UI", hint: "An element on screen or in a web page" }, Credential: { color: "#b91c1c", short: "Key", hint: "Secret from the vault" },
  Any: { color: "#475569", short: "Any", hint: "Any kind of value" },
};

export type Direction = NonNullable<FlowVariable["direction"]>;
export const isInput = (v: FlowVariable) => v.direction === "input" || v.direction === "in_out";
export const isOutput = (v: FlowVariable) => v.direction === "output" || v.direction === "in_out";
/** The direction for two ticks, Use as input and Use as output (local when neither). */
export const directionOf = (input: boolean, output: boolean): Direction | undefined => input && output ? "in_out" : input ? "input" : output ? "output" : undefined;

export const GROUPS: { dir: Direction; label: string; hint: string }[] = [
  { dir: "input", label: "Inputs", hint: "Given when the agent starts" },
  { dir: "in_out", label: "Inputs and outputs", hint: "Given at the start and returned at the end" },
  { dir: "output", label: "Outputs", hint: "Returned when the agent ends" },
  { dir: "local", label: "Inside the agent", hint: "Working values" },
];

/** Can a variable of type `have` be used where a field expects `want`? Text takes most values (they are written out). */
export function compatible(want: string | undefined, have: string | undefined): boolean {
  if (!want || want === "Any" || !have || have === "Any" || want === have) return true;
  if (want === "String") return ["Number", "Boolean", "DateTime", "File"].includes(have);
  if (want === "File") return have === "String";
  return (want === "Record" && have === "Dictionary") || (want === "Dictionary" && have === "Record");
}

/** Can a variable of type `varType` take a result of type `valueType`? Only the same kind (a Number result, a Number variable). */
export function holdsType(varType: string | undefined, valueType: string | undefined): boolean {
  if (!valueType || valueType === "Any" || !varType || varType === "Any" || varType === valueType) return true;
  const pair = (a: string, b: string) => (varType === a && valueType === b) || (varType === b && valueType === a);
  return pair("Record", "Dictionary") || pair("String", "File");
}

export function guessType(v: unknown): string {
  if (typeof v === "number") return "Number";
  if (typeof v === "boolean") return "Boolean";
  if (Array.isArray(v)) return "List";
  if (v && typeof v === "object") return Array.isArray((v as { columns?: unknown }).columns) ? "Table" : "Dictionary";
  return "String";
}

function* every(steps: FlowStep[]): Generator<FlowStep> {
  for (const s of steps) { yield s; for (const inner of innerLists(s)) yield* every(inner); }
}

/** $name and the start of ${name ...}; group 1 keeps the "{" so a rename puts it back. */
const refRegex = (name: string) => new RegExp("\\$(\\{\\s*)?" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "(?![A-Za-z0-9_])", "g");
const NAME_ARGS = ["variable", "as", "index"];

/** How many times a variable is used: in settings, Else if conditions, outputs, and as a loop's or catch's name. */
export function usageCount(flow: Flow, name: string): number {
  const re = refRegex(name);
  let n = 0;
  for (const s of every(flow.steps)) {
    n += (JSON.stringify([s.args ?? {}, (s.elseIf ?? []).map((b) => b.condition)]).match(re) ?? []).length;
    n += Object.values(s.out ?? {}).filter((v) => v === name).length;
    n += NAME_ARGS.filter((k) => s.args?.[k] === name).length;
    n += (s.catch ?? []).filter((c) => c.as === name).length;
  }
  return n;
}

/** After a rename, every $old, ${old}, output and loop or catch name in the agent points to the new name. */
export function renameRefs(flow: Flow, oldName: string, newName: string): Flow {
  const f = structuredClone(flow);
  const re = refRegex(oldName);
  const map = (v: unknown): unknown => typeof v === "string" ? v.replace(re, (_m, brace) => "$" + (brace ?? "") + newName)
    : Array.isArray(v) ? v.map(map) : v && typeof v === "object" ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, map(x)])) : v;
  for (const s of every(f.steps)) {
    if (s.args) { s.args = map(s.args) as Record<string, unknown>; for (const k of NAME_ARGS) if (s.args[k] === oldName) s.args[k] = newName; }
    for (const b of s.elseIf ?? []) b.condition = map(b.condition);
    for (const [k, v] of Object.entries(s.out ?? {})) if (v === oldName) s.out![k] = newName;
    for (const c of s.catch ?? []) if (c.as === oldName) c.as = newName;
  }
  return f;
}

/** A free variable name from a hint ("Window title" becomes Window_title, then Window_title_2 ...). */
export function uniqueName(flow: Flow, base: string): string {
  const b = (base || "variable").replace(/[^A-Za-z0-9_]+/g, "_").replace(/^([0-9])/, "_$1").slice(0, 56) || "variable";
  const taken = new Set((flow.variables ?? []).map((v) => v.name));
  let n = b, k = 2;
  while (taken.has(n)) n = b + "_" + k++;
  return n;
}

/**
 * Saves a variable from the editor: a new one is added, an existing one replaced, and a renamed one renamed
 * everywhere it is used. Throws a message a person can act on when the name is not allowed.
 */
export function saveVariable(flow: Flow, v: FlowVariable, previousName?: string): Flow {
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(v.name)) throw new Error("A variable name starts with a letter or _ and has only letters, digits and _.");
  if (v.name !== previousName && (flow.variables ?? []).some((x) => x.name === v.name)) throw new Error("There is already a variable called " + v.name + ".");
  let f = previousName && previousName !== v.name ? renameRefs(flow, previousName, v.name) : structuredClone(flow);
  const clean: FlowVariable = { ...v };
  if (!clean.direction || clean.direction === "local") delete clean.direction;
  if (!isInput(clean)) { delete clean.required; delete clean.enum; }
  if (!clean.required) delete clean.required;
  if (!isOutput(clean)) delete clean.kpi;
  if (!clean.description?.trim()) delete clean.description;
  if (clean.type === "Credential") delete clean.default;
  if (clean.default === undefined) delete clean.default;
  const vars = [...(f.variables ?? [])];
  const i = previousName ? vars.findIndex((x) => x.name === previousName) : -1;
  if (i >= 0) vars[i] = clean; else vars.push(clean);
  f = { ...f, variables: vars };
  return f;
}

/** The last value of each variable in a run, from its variable.set events. */
export function lastValues(events: RunEvent[] | undefined): Map<string, unknown> {
  const last = new Map<string, unknown>();
  for (const e of events ?? []) if (e.type === "variable.set" && e.data && typeof e.data.name === "string") last.set(e.data.name, e.data.value);
  return last;
}
