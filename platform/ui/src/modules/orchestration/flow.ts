/**
 * A workflow's steps (Name.process.json), for the Studio editor, the run page's diagram and the dry run (review round,
 * 26 Nov 2026). The file keeps its format: "stages", each with an id, a type and its settings; a Parallel step's
 * branches hold their own steps. The checks mirror the server's (Workflows.validate), so Studio shows the same problems
 * before check-in.
 */
export type Obj = Record<string, unknown>;
export type Step = Obj & { id: string; type: string; name?: string };
export type Branch = { name?: string; stages: Step[] };
export type Problem = { step?: string; message: string };

export const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));

/** The step types, in the step list's groups. Read a document and AI step are agent tasks with a filtered agent list. */
export const STEP_TYPES: Record<string, { label: string; group: "Work" | "Paths" | "Waits" | "Finish"; color: string; shape?: "diamond" | "round" }> = {
  bot: { label: "Agent task", group: "Work", color: "#2f6fde" },
  form: { label: "Human task (form)", group: "Work", color: "#0f766e" },
  approval: { label: "Approval", group: "Work", color: "#b45309" },
  subflow: { label: "Sub-workflow", group: "Work", color: "#475569" },
  notify: { label: "Notification", group: "Work", color: "#0e7490" },
  decision: { label: "Decision", group: "Paths", color: "#7c3aed", shape: "diamond" },
  parallel: { label: "Parallel", group: "Paths", color: "#7c3aed" },
  wait: { label: "Wait", group: "Waits", color: "#64748b", shape: "round" },
  end: { label: "End", group: "Finish", color: "#15803d", shape: "round" },
};
/** Shortcuts in the step list: an agent task whose agent list shows only agents of that kind. */
export const SHORTCUTS: Record<string, { label: string; hint: string; color: string; filter: "document" | "ai" }> = {
  document: { label: "Read a document", hint: "An agent task with an agent that reads documents (Document Automation)", color: "#9d174d", filter: "document" },
  ai: { label: "AI step", hint: "An agent task with an agent that uses a Prompt Skill or an AI model", color: "#6d28d9", filter: "ai" },
};
export const typeLabel = (t: string) => STEP_TYPES[t]?.label ?? t;

export const stagesOf = (def: Obj): Step[] => (Array.isArray(def.stages) ? def.stages : []) as Step[];
export const branchesOf = (s: Step): Branch[] => (Array.isArray(s.branches) ? s.branches : []) as Branch[];

/** Every step, top to bottom, branches included, with its number (1, 2, 3...) as the diagram shows it. */
export function flatten(stages: Step[]): Step[] {
  const out: Step[] = [];
  const walk = (list: Step[]) => { for (const s of list) { out.push(s); if (s.type === "parallel") for (const b of branchesOf(s)) walk(Array.isArray(b.stages) ? b.stages : []); } };
  walk(stages);
  return out;
}
export const numbers = (stages: Step[]) => new Map(flatten(stages).map((s, i) => [s.id, i + 1]));

/** A new id not yet used: the type and a number (approval_2). */
export function newId(stages: Step[], base: string) {
  const taken = new Set(flatten(stages).map((s) => s.id));
  let n = 1, id = base;
  while (taken.has(id)) id = base + "_" + ++n;
  return id;
}

/** A new step of a type with sensible first settings. */
export function newStep(stages: Step[], type: string): Step {
  const id = newId(stages, type);
  const count = flatten(stages).filter((s) => s.type === type).length + 1;
  const name = typeLabel(type).replace(" (form)", "") + " " + count;
  if (type === "decision") return { id, type, name, branches: [{ name: "If", condition: "" }], else: "" };
  if (type === "parallel") return { id, type, name, branches: [{ name: "Branch 1", stages: [] }, { name: "Branch 2", stages: [] }], on_failure: "stop" };
  if (type === "wait") return { id, type, name, hours: 1 };
  if (type === "end") return { id, type, name: "End", status: "completed" };
  return { id, type, name };
}

/** The list that holds a step, and where (top level or inside a branch). */
export function locate(stages: Step[], id: string): { list: Step[]; index: number } | null {
  const i = stages.findIndex((s) => s.id === id);
  if (i >= 0) return { list: stages, index: i };
  for (const s of stages) if (s.type === "parallel") for (const b of branchesOf(s)) { const f = locate(Array.isArray(b.stages) ? b.stages : [], id); if (f) return f; }
  return null;
}

/** A copy of the steps with fn applied to every list of steps (the top one and every branch's), deepest first. */
export function mapLists(stages: Step[], fn: (list: Step[]) => Step[]): Step[] {
  const inner = stages.map((s) => s.type === "parallel" ? { ...s, branches: branchesOf(s).map((b) => ({ ...b, stages: mapLists(Array.isArray(b.stages) ? b.stages : [], fn) })) } : s);
  return fn(inner);
}

export const replaceStep = (stages: Step[], id: string, next: Step) => mapLists(stages, (l) => l.map((s) => s.id === id ? next : s));
export const removeStep = (stages: Step[], id: string) => mapLists(stages, (l) => l.filter((s) => s.id !== id));
/** Puts a step after another one (in that one's list), or at the end of the top list. */
export function insertAfter(stages: Step[], afterId: string | null, step: Step): Step[] {
  if (!afterId || !locate(stages, afterId)) return [...stages, step];
  return mapLists(stages, (l) => { const i = l.findIndex((s) => s.id === afterId); return i < 0 ? l : [...l.slice(0, i + 1), step, ...l.slice(i + 1)]; });
}
/** Puts a step at the end of a Parallel step's branch. */
export const appendToBranch = (stages: Step[], parId: string, b: number, step: Step) =>
  mapLists(stages, (l) => l.map((s) => s.id === parId ? { ...s, branches: branchesOf(s).map((x, i) => i === b ? { ...x, stages: [...(x.stages ?? []), step] } : x) } : s));
export function move(stages: Step[], id: string, by: number): Step[] {
  return mapLists(stages, (l) => {
    const i = l.findIndex((s) => s.id === id), j = i + by;
    if (i < 0 || j < 0 || j >= l.length) return l;
    const n = [...l]; [n[i], n[j]] = [n[j], n[i]]; return n;
  });
}
/** A copy of a step with new ids (its branches' steps too), to paste. */
export function withNewIds(stages: Step[], s: Step): Step {
  const taken = new Set(flatten(stages).map((x) => x.id));
  const fresh = (base: string) => { let n = 2, id = base + "_copy"; while (taken.has(id)) id = base + "_copy" + n++; taken.add(id); return id; };
  const copy = (x: Step): Step => ({ ...x, id: fresh(x.id), ...(x.type === "parallel" ? { branches: branchesOf(x).map((b) => ({ ...b, stages: (b.stages ?? []).map(copy) })) } : {}) });
  return copy(s);
}

/** Where a step goes when it is done, for the diagram: decisions' branches, an approval's rejection, a jump. */
export function jumps(s: Step): { label: string; to: string }[] {
  const out: { label: string; to: string }[] = [];
  if (s.type === "decision") {
    const bs = (Array.isArray(s.branches) ? s.branches : []) as Obj[];
    if (str(s.condition)) { out.push({ label: "Yes", to: str(s.if_true) }); out.push({ label: "No", to: str(s.if_false) }); }
    bs.forEach((b, i) => out.push({ label: str(b.name) || (i === 0 ? "If" : "Else if"), to: str(b.goto) }));
    if (bs.length) out.push({ label: "Else", to: str(s.else) });
  }
  if (s.type === "approval" && s.on_reject && s.on_reject !== "end") out.push({ label: "If rejected", to: str(s.on_reject) === "continue" ? "" : str(s.on_reject) });
  if (str(s.next)) out.push({ label: "Then", to: str(s.next) });
  return out;
}

/** The people, roles and teams a step's assignee text names, with what kind each is. */
export function whoOf(text: string): { who: string; kind: string }[] {
  return str(text).split(",").map((x) => x.trim()).filter(Boolean).map((t) =>
    t === "starter" ? { who: "The person who started it", kind: "Starter" }
      : t.startsWith("random:team:") ? { who: t.slice(12), kind: "Random member of a team" }
      : t.startsWith("random:role:") ? { who: t.slice(12), kind: "Random member of a role" }
      : t.startsWith("user:") ? { who: t.slice(5), kind: "Person" }
      : t.startsWith("role:") ? { who: t.slice(5), kind: "Role" }
      : t.startsWith("perm:") ? { who: t.slice(5), kind: "Permission" }
      : t.includes("@") ? { who: t, kind: "E-mail" } : { who: t, kind: "Person" });
}

/** One line under a step's name on the diagram. */
export function meta(s: Step, nameOf: (id: string) => string): string {
  switch (s.type) {
    case "bot": return str(s.bot).split("/").pop()?.replace(/\.flow\.json$/, "") || "No agent chosen";
    case "form": return (str(s.form).split("/").pop()?.replace(/\.form\.json$/, "") || "No form chosen") + (s.assignee ? " · " + whoOf(str(s.assignee)).map((w) => w.who).join(", ") : "");
    case "approval": {
      const n = s.approvals_needed === "all" ? "everyone" : typeof s.approvals_needed === "number" && s.approvals_needed > 1 ? s.approvals_needed + " approvals" : "";
      return [whoOf(str(s.assignee)).map((w) => w.who).join(", ") || "No approver", n, s.due_hours ? "due " + s.due_hours + " h" : "", s.escalate ? "escalates" : ""].filter(Boolean).join(" · ");
    }
    case "decision": return (Array.isArray(s.branches) ? s.branches.length + 1 : 2) + " ways";
    case "notify": return str(s.to) || "No recipient";
    case "parallel": return branchesOf(s).length + " branches at once";
    case "wait": return s.until ? "until " + str(s.until) : [s.days ? s.days + " d" : "", s.hours ? s.hours + " h" : "", s.minutes ? s.minutes + " min" : ""].filter(Boolean).join(" ") || "No time set";
    case "subflow": return str(s.workflow).split("/").pop()?.replace(/\.process\.json$/, "") || "No workflow chosen";
    case "end": return ({ completed: "Completed", failed: "Failed", cancelled: "Cancelled" } as Record<string, string>)[str(s.status) || "completed"] + (s.message ? " · " + str(s.message) : "");
    default: return nameOf(s.id);
  }
}

/** The workflow's problems: the server's checks at check-in (Workflows.validate), and agents whose inputs changed. */
export function problems(def: Obj, agents?: { path: string; variables: { name: string; direction?: string; required?: boolean; default?: unknown }[] }[] | null): Problem[] {
  const out: Problem[] = [];
  const stages = stagesOf(def);
  if (!stages.length) return [{ message: "The workflow has no steps" }];
  const seen = new Set<string>();
  const check = (list: Step[]) => {
    const ids = new Set(list.map((s) => s.id));
    const points = (s: Step, t: unknown) => { const v = str(t); if (v && v !== "end" && v !== "continue" && !ids.has(v)) out.push({ step: s.id, message: (s.name || s.id) + " points to a missing step " + v }); };
    for (const s of list) {
      const name = str(s.name) || s.id;
      if (seen.has(s.id)) out.push({ step: s.id, message: "Two steps have the id " + s.id });
      seen.add(s.id);
      if (!STEP_TYPES[s.type]) out.push({ step: s.id, message: name + " has an unknown type " + s.type });
      if (s.type === "bot" && !str(s.bot).endsWith(".flow.json")) out.push({ step: s.id, message: "Agent task " + name + " needs an agent" });
      if (s.type === "form" && !str(s.form).endsWith(".form.json")) out.push({ step: s.id, message: "Human task " + name + " needs a form" });
      if ((s.type === "form" || s.type === "approval") && !str(s.assignee).trim()) out.push({ step: s.id, message: name + " needs someone to assign it to" });
      if (s.type === "approval" && s.approvals_needed === "all" && !whoOf(str(s.assignee)).some((w) => w.kind === "Person")) out.push({ step: s.id, message: name + ": Everyone needs the approvers named as people" });
      if (s.type === "decision") {
        const bs = (Array.isArray(s.branches) ? s.branches : []) as Obj[];
        if (!str(s.condition) && !bs.length) out.push({ step: s.id, message: "Decision " + name + " needs a condition" });
        bs.forEach((b) => { if (!str(b.condition).trim()) out.push({ step: s.id, message: "Decision " + name + ": a branch has no condition" }); points(s, b.goto); });
        points(s, s.else); points(s, s.if_true); points(s, s.if_false);
      }
      if (s.type === "notify" && !str(s.to).trim()) out.push({ step: s.id, message: "Notification " + name + " needs a recipient" });
      if (s.type === "wait" && !str(s.until) && !(Number(s.days) > 0 || Number(s.hours) > 0 || Number(s.minutes) > 0)) out.push({ step: s.id, message: "Wait " + name + " needs a date and time or a period" });
      if (s.type === "subflow" && !str(s.workflow).endsWith(".process.json")) out.push({ step: s.id, message: "Sub-workflow " + name + " needs a workflow" });
      if (s.type === "parallel") {
        const bs = branchesOf(s);
        if (bs.length < 2) out.push({ step: s.id, message: "Parallel " + name + " needs at least two branches" });
        for (const b of bs) { if (!(b.stages ?? []).length) out.push({ step: s.id, message: "Parallel " + name + ": " + (b.name || "a branch") + " has no steps" }); else check(b.stages); }
      }
      if (s.escalate && !s.due_hours) out.push({ step: s.id, message: name + ": escalation needs a due time" });
      if (s.escalate && (s.escalate as Obj).action === "reassign" && !str((s.escalate as Obj).to)) out.push({ step: s.id, message: name + ": hand the late task to whom?" });
      points(s, s.next); points(s, s.on_reject);
      // an agent that has an input the step does not give (and that has no default): it was added after the step was set up
      if (s.type === "bot" && agents) {
        const a = agents.find((x) => x.path === s.bot);
        const given = (s.inputs && typeof s.inputs === "object" ? s.inputs : {}) as Obj;
        for (const v of a?.variables ?? []) if ((v.direction === "input" || v.direction === "in_out") && v.required && v.default === undefined && !(v.name in given))
          out.push({ step: s.id, message: name + ": the agent has a new input " + v.name });
      }
    }
  };
  check(stages);
  if (def.start_form !== undefined && def.start_form !== "" && !str(def.start_form).endsWith(".form.json")) out.push({ message: "The start form must be a form" });
  return out;
}
