import type { ActionManifest, Flow, FlowStep, FlowVariable, PackageManifest } from "../api/client";

/**
 * Editing an agent, as pure functions: each takes a flow and returns a changed copy, so undo is keeping the old one.
 * New steps are built the way Classic Studio builds them (studio.js addStep), so both Studios write the same files.
 */

type Lists = { list: FlowStep[]; index: number; step: FlowStep };

/** The lists of steps inside a step: Then/Repeat/Try (children), Else if, Else, Catch, Finally. */
export function innerLists(s: FlowStep): FlowStep[][] {
  const out: FlowStep[][] = [];
  if (s.children) out.push(s.children);
  for (const b of s.elseIf ?? []) if (b.children) out.push(b.children);
  if (s.else) out.push(s.else);
  for (const c of s.catch ?? []) if (c.children) out.push(c.children);
  if (s.finally) out.push(s.finally);
  return out;
}

/** Where a step is: the list that holds it and its place there. */
export function locate(steps: FlowStep[], id: string): Lists | null {
  for (let i = 0; i < steps.length; i++) {
    if (steps[i].id === id) return { list: steps, index: i, step: steps[i] };
    for (const inner of innerLists(steps[i])) { const r = locate(inner, id); if (r) return r; }
  }
  return null;
}

function* every(steps: FlowStep[]): Generator<FlowStep> {
  for (const s of steps) { yield s; for (const inner of innerLists(s)) yield* every(inner); }
}

/** The next free step id (s1, s2 ...), as Classic Studio numbers them. */
export function nextId(flow: Flow, taken = new Set<string>()): string {
  let n = 0;
  for (const s of every(flow.steps)) { const m = /^s(\d+)$/.exec(s.id); if (m) n = Math.max(n, +m[1]); }
  for (const t of taken) { const m = /^s(\d+)$/.exec(t); if (m) n = Math.max(n, +m[1]); }
  return "s" + (n + 1);
}

export function findAction(action: string, packages: PackageManifest[]): ActionManifest | undefined {
  const [p, id] = action.split("/");
  return packages.find((x) => x.id === p)?.actions.find((a) => a.id === id);
}

const BLOCKS = new Set(["core/if", "core/loop", "core/try", "core/step", "core/parallel"]);
export const isBlock = (action: string) => BLOCKS.has(action);

/** A new step for an action, with the starting values Classic Studio gives it. */
export function newStep(action: string, flow: Flow, packages: PackageManifest[]): FlowStep {
  const spec = findAction(action, packages);
  const step: FlowStep = { id: nextId(flow), action };
  const args: Record<string, unknown> = {};
  const props = spec?.inputs?.properties ?? {};
  for (const [name, prop] of Object.entries(props)) {
    if (action === "core/loop" && name === "iterator") args.iterator = "list";
    else if ((spec?.inputs?.required ?? []).includes(name) && prop.examples?.length) args[name] = prop.examples[0];
  }
  if (action === "core/throw" && args.code === undefined) args.code = "BUSINESS.ERROR";   // a business error unless another code is chosen
  if (Object.keys(args).length) step.args = args;
  if (action === "core/if") { step.children = []; step.else = []; }
  else if (action === "core/try") { step.children = []; step.catch = [{ codes: [], children: [] }]; }   // Finally only when wanted
  else if (isBlock(action)) step.children = [];
  return step;
}

const copy = (f: Flow): Flow => structuredClone(f);

/**
 * Adds a step: inside the selected block (at its end), after the selected step, or at the end of the agent. Returns
 * the new flow and the step's id, so the editor can select it.
 */
export function addStep(flow: Flow, selectedId: string | null, step: FlowStep): Flow {
  const f = copy(flow);
  const at = selectedId ? locate(f.steps, selectedId) : null;
  if (!at) f.steps.push(step);
  else if (isBlock(at.step.action) && at.step.children) at.step.children.push(step);
  else at.list.splice(at.index + 1, 0, step);
  return f;
}

/** True when a step is the one with this id or holds it somewhere inside. */
export function holds(step: FlowStep, id: string): boolean {
  return step.id === id || innerLists(step).some((list) => list.some((s) => holds(s, id)));
}

export type Place = "before" | "after" | "inside";

/** Puts a step at a place: before or after a target step, or at the end of a target block's steps. */
function put(f: Flow, step: FlowStep, targetId: string | null, where: Place): boolean {
  if (!targetId) { f.steps.push(step); return true; }
  const at = locate(f.steps, targetId);
  if (!at) return false;
  if (where === "inside") {
    if (!isBlock(at.step.action)) return false;
    (at.step.children ??= []).push(step);
  } else at.list.splice(at.index + (where === "after" ? 1 : 0), 0, step);
  return true;
}

/** Inserts a new step (one dragged from the library) at a place; no target means the end of the agent. */
export function insertAt(flow: Flow, step: FlowStep, targetId: string | null, where: Place): Flow {
  const f = copy(flow);
  return put(f, step, targetId, where) ? f : flow;
}

/**
 * Moves a step (with what is inside it) to a place, by drag and drop. A step cannot go inside itself or next to a
 * step inside itself; then, or when nothing would change, the flow comes back unchanged.
 */
export function moveTo(flow: Flow, id: string, targetId: string, where: Place): Flow {
  const moving = locate(flow.steps, id)?.step;
  if (!moving || holds(moving, targetId)) return flow;
  const f = copy(flow);
  const from = locate(f.steps, id)!;
  from.list.splice(from.index, 1);
  return put(f, from.step, targetId, where) ? f : flow;
}

/** Removes several steps; a step inside another removed step goes with it. */
export function removeSteps(flow: Flow, ids: string[]): Flow {
  let f = flow;
  for (const id of ids) f = removeStep(f, id);
  return f;
}

/** Switches several steps off, or on again when every one of them is off already. */
export function toggleDisabledMany(flow: Flow, ids: string[]): Flow {
  const f = copy(flow);
  const steps = ids.map((id) => locate(f.steps, id)?.step).filter((s): s is FlowStep => !!s);
  const allOff = steps.every((s) => s.disabled);
  for (const s of steps) { if (allOff) delete s.disabled; else s.disabled = true; }
  return f;
}

/** Copies of several steps, each right after its original; returns the copies' ids. */
export function duplicateSteps(flow: Flow, ids: string[]): { flow: Flow; ids: string[] } {
  let f = flow;
  const out: string[] = [];
  for (const id of ids) { const d = duplicateStep(f, id); f = d.flow; if (d.id) out.push(d.id); }
  return { flow: f, ids: out };
}

export function removeStep(flow: Flow, id: string): Flow {
  const f = copy(flow);
  const at = locate(f.steps, id);
  if (at) at.list.splice(at.index, 1);
  return f;
}

/** Moves a step one place up (-1) or down (+1) within its own list; at either end nothing changes. */
export function moveStep(flow: Flow, id: string, by: -1 | 1): Flow {
  const f = copy(flow);
  const at = locate(f.steps, id);
  if (!at) return flow;
  const to = at.index + by;
  if (to < 0 || to >= at.list.length) return flow;
  at.list.splice(at.index, 1);
  at.list.splice(to, 0, at.step);
  return f;
}

/** A copy of a step (and the steps inside it, with new ids) right after it. Returns the copy's id with the flow. */
export function duplicateStep(flow: Flow, id: string): { flow: Flow; id: string | null } {
  const f = copy(flow);
  const at = locate(f.steps, id);
  if (!at) return { flow, id: null };
  const dup = structuredClone(at.step);
  const taken = new Set<string>();
  for (const s of every([dup])) { s.id = nextId(f, taken); taken.add(s.id); }
  at.list.splice(at.index + 1, 0, dup);
  return { flow: f, id: dup.id };
}

export function toggleDisabled(flow: Flow, id: string): Flow {
  const f = copy(flow);
  const at = locate(f.steps, id);
  if (at) { if (at.step.disabled) delete at.step.disabled; else at.step.disabled = true; }
  return f;
}

/** Sets one input of a step; undefined clears it (the toolkit's default applies). */
export function setArg(flow: Flow, id: string, name: string, value: unknown): Flow {
  const f = copy(flow);
  const at = locate(f.steps, id);
  if (!at) return flow;
  const args = { ...(at.step.args ?? {}) };
  if (value === undefined) delete args[name]; else args[name] = value;
  if (Object.keys(args).length) at.step.args = args; else delete at.step.args;
  return f;
}

/** Saves one output of a step in a variable (name without $); empty stops saving it. */
export function setOut(flow: Flow, id: string, output: string, variable: string): Flow {
  const f = copy(flow);
  const at = locate(f.steps, id);
  if (!at) return flow;
  const out = { ...(at.step.out ?? {}) };
  const v = variable.trim().replace(/^\$/, "");
  if (v) out[output] = v; else delete out[output];
  if (Object.keys(out).length) at.step.out = out; else delete at.step.out;
  return f;
}

export function setNote(flow: Flow, id: string, note: string): Flow {
  const f = copy(flow);
  const at = locate(f.steps, id);
  if (at) { if (note.trim()) at.step.note = note; else delete at.step.note; }
  return f;
}

export const VARIABLE_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** Adds or replaces a variable (by name); a new name must be a valid identifier and not already used. */
export function putVariable(flow: Flow, v: FlowVariable, previousName?: string): Flow {
  if (!VARIABLE_NAME.test(v.name)) throw new Error("A variable name starts with a letter or _ and has only letters, digits and _.");
  const f = copy(flow);
  const vars = f.variables ?? [];
  if (v.name !== previousName && vars.some((x) => x.name === v.name)) throw new Error("There is already a variable called " + v.name + ".");
  const i = previousName ? vars.findIndex((x) => x.name === previousName) : -1;
  if (i >= 0) vars[i] = v; else vars.push(v);
  f.variables = vars;
  return f;
}

export function removeVariable(flow: Flow, name: string): Flow {
  const f = copy(flow);
  f.variables = (f.variables ?? []).filter((v) => v.name !== name);
  return f;
}

/** Text from a field to a flow value, as Classic Studio reads it: JSON when it parses, otherwise the text; empty = not set. */
export function parseLoose(text: string): unknown {
  if (text.trim() === "") return undefined;
  if (/^\s*[[{"]|^\s*-?\d|^\s*(true|false|null)\s*$/.test(text)) {
    try { return JSON.parse(text); } catch { /* keep as text */ }
  }
  return text;
}

export const showValue = (v: unknown) => v === undefined ? "" : typeof v === "string" ? v : JSON.stringify(v);

/**
 * The order of an action's fields: the main ones first, then those under More options, as its panel layout lists
 * them; any input the layout does not mention goes under More options.
 */
export function fieldOrder(spec: ActionManifest | undefined): { main: string[]; more: string[] } {
  const names = Object.keys(spec?.inputs?.properties ?? {});
  const pick = (entries: unknown) => {
    const text = Array.isArray(entries) ? entries.join(" ") : "";
    return [...new Set(text.match(/[A-Za-z_][A-Za-z0-9_]*/g) ?? [])].filter((n) => names.includes(n));
  };
  const panel = spec?.["x-ooa-panel"];
  if (!panel) return { main: names, more: [] };
  const main = pick(panel.main);
  const more = pick(panel.more).filter((n) => !main.includes(n));
  return { main, more: [...more, ...names.filter((n) => !main.includes(n) && !more.includes(n))] };
}
