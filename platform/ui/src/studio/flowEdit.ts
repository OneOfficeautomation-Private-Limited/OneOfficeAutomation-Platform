import type { ActionManifest, Flow, FlowStep, FlowVariable, PackageManifest } from "../api/client";

/**
 * Editing an agent, as pure functions: each takes a flow and returns a changed copy, so undo is keeping the old one.
 * New steps are built the way Classic Studio built them, so agents made there and here are alike.
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
 * Adds a step where the selection says (see spotFor): inside the selected block (at its end), after the selected step,
 * at the start, in a block part, or at the end of the agent.
 */
export function addStep(flow: Flow, selected: string | string[] | null, step: FlowStep): Flow {
  const f = copy(flow);
  const sel = selected === null ? [] : Array.isArray(selected) ? selected : [selected];
  if (!put(f, [step], spotFor(flow, sel))) f.steps.push(step);
  return f;
}

/** True when a step is the one with this id or holds it somewhere inside. */
export function holds(step: FlowStep, id: string): boolean {
  return step.id === id || innerLists(step).some((list) => list.some((s) => holds(s, id)));
}

export type Place = "before" | "after" | "inside";

/** A block's part: its first steps (Then, Repeat, Try), an Else if or Catch by number, Else or Finally. */
export type Part = "children" | "else" | "finally" | `elseIf:${number}` | `catch:${number}`;

/** The list of steps of a block's part, or undefined when the block has no such part. */
export function partList(step: FlowStep, part: Part = "children"): FlowStep[] | undefined {
  if (part === "children") return isBlock(step.action) ? (step.children ??= []) : undefined;
  if (part === "else") return step.else;
  if (part === "finally") return step.finally;
  const [kind, k] = part.split(":");
  const b = kind === "elseIf" ? step.elseIf?.[+k] : step.catch?.[+k];
  return b ? (b.children ??= []) : undefined;
}

/**
 * Where steps go: before or after a step, at the end of a block's part, or with no step at the start ("before") or
 * the end ("after") of the agent.
 */
export type Spot = { id: string | null; where: Place; part?: Part };

/** Puts steps at a spot; false when the spot is not there (any more). */
function put(f: Flow, steps: FlowStep[], spot: Spot): boolean {
  if (!spot.id) { if (spot.where === "before") f.steps.unshift(...steps); else f.steps.push(...steps); return true; }
  const at = locate(f.steps, spot.id);
  if (!at) return false;
  if (spot.where === "inside") {
    const list = partList(at.step, spot.part);
    if (!list) return false;
    list.push(...steps);
  } else at.list.splice(at.index + (spot.where === "after" ? 1 : 0), 0, ...steps);
  return true;
}

/** Inserts a new step (one dragged from the library) at a place; no target means the end of the agent. */
export function insertAt(flow: Flow, step: FlowStep, targetId: string | null, where: Place, part?: Part): Flow {
  const f = copy(flow);
  return put(f, [step], { id: targetId, where, part }) ? f : flow;
}

/**
 * Moves a step (with what is inside it) to a place, by drag and drop. A step cannot go inside itself or next to a
 * step inside itself; then, or when nothing would change, the flow comes back unchanged.
 */
export function moveTo(flow: Flow, id: string, targetId: string, where: Place, part?: Part): Flow {
  return moveManyTo(flow, [id], targetId, where, part);
}

/** Moves several steps, in the agent's order, to one place (dragging one of several selected steps moves them all). */
export function moveManyTo(flow: Flow, ids: string[], targetId: string | null, where: Place, part?: Part): Flow {
  const chosen = topSteps(flow, ids);
  if (!chosen.length || (targetId && chosen.some((s) => holds(s, targetId)))) return flow;
  const f = copy(flow);
  const moving: FlowStep[] = [];
  for (const s of chosen) { const at = locate(f.steps, s.id)!; at.list.splice(at.index, 1); moving.push(at.step); }
  return put(f, moving, { id: targetId, where, part }) ? f : flow;
}

/** The chosen steps in the agent's order, leaving out those inside another chosen block (they go with it). */
export function topSteps(flow: Flow, ids: string[]): FlowStep[] {
  const want = new Set(ids);
  const out: FlowStep[] = [];
  const walk = (list: FlowStep[]) => { for (const s of list) { if (want.has(s.id)) out.push(s); else innerLists(s).forEach(walk); } };
  walk(flow.steps);
  return out;
}

/** Selection keys that are not steps: the Start row, and a block part's heading (its steps go to the end of it). */
export const START = "@start";
export const sectionKey = (id: string, part: Part) => `@part:${id}:${part}`;
export function parseSection(key: string): { id: string; part: Part } | null {
  const m = /^@part:([^:]+):(.+)$/.exec(key);
  return m ? { id: m[1], part: m[2] as Part } : null;
}

/**
 * Where an action clicked in the library, or pasted steps, go for the current selection: after the last selected step
 * (inside it at the end, for a new step on one selected block), at the start when Start is selected, at the end of a
 * selected block part, or at the end of the agent.
 */
export function spotFor(flow: Flow, selected: string[], intoBlock = true): Spot {
  const last = selected[selected.length - 1];
  if (last === START) return { id: null, where: "before" };
  const sec = last ? parseSection(last) : null;
  if (sec) return { id: sec.id, where: "inside", part: sec.part };
  const real = topSteps(flow, selected.filter((x) => !x.startsWith("@")));
  const at = real.length ? real[real.length - 1] : null;
  if (!at) return { id: null, where: "after" };
  return intoBlock && real.length === 1 && isBlock(at.action) ? { id: at.id, where: "inside", part: "children" } : { id: at.id, where: "after" };
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

/** Moves every selected step one place up (-1) or down (+1) in its own list; selected neighbours move together. */
export function moveSelection(flow: Flow, ids: string[], by: -1 | 1): Flow {
  const f = copy(flow);
  const chosen = topSteps(f, ids);
  const lists = new Map<FlowStep[], number[]>();
  for (const s of chosen) { const at = locate(f.steps, s.id)!; lists.set(at.list, [...(lists.get(at.list) ?? []), at.index]); }
  let moved = 0;
  for (const [list, idx] of lists) {
    const set = new Set(idx.map((i) => list[i]));
    idx.sort((a, b) => by < 0 ? a - b : b - a);
    for (const i of idx) {
      const j = i + by;
      if (j < 0 || j >= list.length || set.has(list[j])) continue;
      [list[i], list[j]] = [list[j], list[i]];
      moved++;
    }
  }
  return moved ? f : flow;
}

/** What Copy keeps: the steps and the variables they use, the shape Classic Studio puts on the clipboard. */
export type Clip = { steps: FlowStep[]; variables: FlowVariable[] };
export const CLIP_KEY = "ooaSteps";

/** The variables of this agent that steps use: $name or ${name} in their settings, or outputs saved in them. */
export function referencedVariables(flow: Flow, steps: FlowStep[]): FlowVariable[] {
  const names = new Set<string>();
  for (const m of JSON.stringify(steps).matchAll(/\$\{?([A-Za-z_][A-Za-z0-9_]*)/g)) names.add(m[1]);
  for (const s of every(steps)) for (const v of Object.values(s.out ?? {})) names.add(v);
  return (flow.variables ?? []).filter((v) => names.has(v.name));
}

export function copySteps(flow: Flow, ids: string[]): Clip | null {
  const steps = topSteps(flow, ids);
  return steps.length ? structuredClone({ steps, variables: referencedVariables(flow, steps) }) : null;
}

/** Reads a clipboard text Copy wrote, in either Studio; null when it is something else. */
export function readClip(text: string): Clip | null {
  try {
    const v = JSON.parse(text)?.[CLIP_KEY];
    return v && Array.isArray(v.steps) ? { steps: v.steps, variables: Array.isArray(v.variables) ? v.variables : [] } : null;
  } catch { return null; }
}

/** Pastes copies (new ids throughout) at a spot, adding the variables they use that this agent does not have yet. */
export function pasteSteps(flow: Flow, clip: Clip, spot: Spot): { flow: Flow; ids: string[] } {
  const f = copy(flow);
  const steps = structuredClone(clip.steps);
  const taken = new Set<string>();
  for (const s of every(steps)) { s.id = nextId(f, taken); taken.add(s.id); }
  if (!put(f, steps, spot)) f.steps.push(...steps);
  const have = new Set((f.variables ?? []).map((v) => v.name));
  const add = clip.variables.filter((v) => !have.has(v.name));
  if (add.length) f.variables = [...(f.variables ?? []), ...structuredClone(add)];
  return { flow: f, ids: steps.map((s) => s.id) };
}

/** Adds a part to an If (Else if, Else) or a Try (Catch, Finally); returns the part, so the editor can select it. */
export function addBranch(flow: Flow, id: string, kind: "elseIf" | "else" | "catch" | "finally"): { flow: Flow; part: Part | null } {
  const f = copy(flow);
  const s = locate(f.steps, id)?.step;
  if (!s) return { flow, part: null };
  if (kind === "elseIf") { (s.elseIf ??= []).push({ condition: undefined, children: [] }); return { flow: f, part: `elseIf:${s.elseIf.length - 1}` }; }
  if (kind === "catch") { (s.catch ??= []).push({ codes: [], children: [] }); return { flow: f, part: `catch:${s.catch.length - 1}` }; }
  if (s[kind]) return { flow, part: null };
  s[kind] = [];
  return { flow: f, part: kind };
}

/** Removes an Else if, Else, Catch or Finally with the steps in it (a Try keeps at least one Catch or a Finally). */
export function removeBranch(flow: Flow, id: string, part: Part): Flow {
  const f = copy(flow);
  const s = locate(f.steps, id)?.step;
  if (!s || part === "children") return flow;
  if (part === "else" || part === "finally") delete s[part];
  else {
    const [kind, k] = part.split(":") as ["elseIf" | "catch", string];
    const list = s[kind];
    list?.splice(+k, 1);
    if (list && !list.length) delete s[kind];
  }
  if (s.action === "core/try" && !s.catch?.length && !s.finally) return flow;
  return f;
}

/** Sets the error codes a Catch takes (none = every error). */
export function setCatchCodes(flow: Flow, id: string, k: number, codes: string[]): Flow {
  const f = copy(flow);
  const c = locate(f.steps, id)?.step.catch?.[k];
  if (!c) return flow;
  c.codes = codes;
  return f;
}

/** Sets the condition of an Else if. */
export function setElseIfCondition(flow: Flow, id: string, k: number, condition: unknown): Flow {
  const f = copy(flow);
  const b = locate(f.steps, id)?.step.elseIf?.[k];
  if (!b) return flow;
  b.condition = condition;
  return f;
}

/** Folded blocks are kept in the agent's ui section, apart from what it does. */
export function foldedSet(flow: Flow): Set<string> { return new Set(flow.ui?.collapsed ?? []); }

export function setFolded(flow: Flow, ids: string[], folded: boolean): Flow {
  const f = copy(flow);
  const c = foldedSet(f);
  for (const id of ids) { if (folded) c.add(id); else c.delete(id); }
  const ui = { ...(f.ui ?? {}) };
  if (c.size) ui.collapsed = [...c]; else delete ui.collapsed;
  if (Object.keys(ui).length) f.ui = ui; else delete f.ui;
  return f;
}

/** Every block in the agent (for Fold all). */
export function blockIds(flow: Flow): string[] { return [...every(flow.steps)].filter((s) => isBlock(s.action)).map((s) => s.id); }
