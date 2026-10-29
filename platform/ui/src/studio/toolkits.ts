import type { IconName } from "../components";
import type { FlowStep, PackageManifest } from "../api/client";

/** Colour and icon of a toolkit, so steps and the action library share one mark per toolkit (approved look). */
export type Mark = { color: string; icon: IconName };

// Families of toolkits, matched on the toolkit id (ooa.excel, ooa.microsoft365 ...). Every colour carries white
// icons at 3:1 or more. The first match wins, so the specific families come before the broad ones.
const BY_TOOLKIT: [RegExp, Mark][] = [
  [/excel|csv/, { color: "#1d7a46", icon: "grid" }],
  [/outlook|email|microsoft365|slack/, { color: "#0f6cbd", icon: "mail" }],
  [/ai_agent|ai_skills|genai|knowledge|web_agent|insights/, { color: "#7c3aed", icon: "spark" }],
  [/pdf|ocr|word|powerpoint|docusign|image/, { color: "#6d28d9", icon: "doc" }],
  [/browser|rest|soap|connector|network|kafka/, { color: "#c2410c", icon: "globe" }],
  [/workload|triggerloop/, { color: "#4338ca", icon: "queue" }],
  [/database|snowflake|databricks/, { color: "#334155", icon: "db" }],
  [/sap|terminal|java_apps|desktop|window|application|dll|services|printer|sound|system|credential_manager/, { color: "#475569", icon: "desk" }],
  [/forms|interrupts/, { color: "#0f766e", icon: "person" }],
  [/asana|jira|zendesk|servicenow|salesforce|workday|github|azure_devops|google|okta|active_directory|aws_ec2/, { color: "#0e7490", icon: "process" }],
  [/file|folder|box|file_transfer|log|pgp|crypto/, { color: "#a16207", icon: "folder" }],
  [/datatable|dictionary|list|json|xml|string|number|boolean|datetime|currency|fuzzy|global_values|run_context/, { color: "#1e40af", icon: "code" }],
];

const CORE: Record<string, { name: string; mark: Mark }> = {
  "core/if": { name: "If", mark: { color: "#b45309", icon: "branch" } },
  "core/loop": { name: "Loop", mark: { color: "#0e7490", icon: "loop" } },
  "core/try": { name: "Try", mark: { color: "#b91c1c", icon: "bug" } },
  "core/parallel": { name: "Run at the same time", mark: { color: "#0e7490", icon: "process" } },
  "core/step": { name: "Step group", mark: { color: "#334155", icon: "folder" } },
  "core/run_bot": { name: "Run agent", mark: { color: "#2f6fde", icon: "play" } },
  "core/set": { name: "Set value", mark: { color: "#334155", icon: "code" } },
  "core/log": { name: "Write to log", mark: { color: "#334155", icon: "doc" } },
  "core/comment": { name: "Comment", mark: { color: "#475569", icon: "doc" } },
  "core/delay": { name: "Wait", mark: { color: "#475569", icon: "pulse" } },
  "core/wait_until": { name: "Wait until", mark: { color: "#475569", icon: "pulse" } },
  "core/throw": { name: "Raise an error", mark: { color: "#b91c1c", icon: "bug" } },
  "core/break": { name: "Leave the loop", mark: { color: "#0e7490", icon: "loop" } },
  "core/continue": { name: "Next round of the loop", mark: { color: "#0e7490", icon: "loop" } },
};

const OTHER: Mark = { color: "#334155", icon: "code" };

export function toolkitMark(toolkitId: string): Mark {
  const id = toolkitId.toLowerCase();
  return BY_TOOLKIT.find(([re]) => re.test(id))?.[1] ?? OTHER;
}

/** Display name and mark of a step's action: core blocks by name, toolkit actions from the toolkit catalogue. */
export function describeAction(action: string, packages: PackageManifest[]): { name: string; toolkit: string; mark: Mark } {
  const [toolkitId, actionId] = action.split("/");
  const pkg = packages.find((p) => p.id === toolkitId);
  const a = pkg?.actions.find((x) => x.id === actionId);
  // the catalogue's name, as the action library shows it; ours only when the catalogue has not loaded
  const core = CORE[action];
  if (core) return { name: a?.name ?? core.name, toolkit: "Flow", mark: core.mark };
  return { name: a?.name ?? (actionId ?? action).replace(/_/g, " "), toolkit: pkg?.name ?? toolkitId, mark: toolkitMark(toolkitId) };
}

/** Every action a list of steps uses, blocks included. */
export function actionsUsed(steps: FlowStep[] | undefined, out = new Set<string>()): Set<string> {
  for (const s of steps ?? []) {
    out.add(s.action);
    actionsUsed(s.children, out); actionsUsed(s.else, out); actionsUsed(s.finally, out);
    for (const b of [...(s.elseIf ?? []), ...(s.catch ?? [])]) actionsUsed(b.children, out);
  }
  return out;
}

/** True when every action the steps use is installed (a blueprint whose toolkit is missing is not offered). */
export function allInstalled(steps: FlowStep[], packages: PackageManifest[]): boolean {
  return [...actionsUsed(steps)].every((a) => { const [p, id] = a.split("/"); return packages.some((x) => x.id === p && x.actions.some((y) => y.id === id)); });
}

/** One line of the steps view: a step, or the heading of a block's second part (Else, Catch, Finally). */
export type StepRow = { kind: "step"; n: number; depth: number; step: FlowStep } | { kind: "section"; depth: number; label: string };

/** Flattens the steps for the numbered view, in the order they run, with block parts as headings. */
export function stepRows(steps: FlowStep[]): StepRow[] {
  const rows: StepRow[] = [];
  let n = 0;
  const walk = (list: FlowStep[] | undefined, depth: number) => {
    for (const s of list ?? []) {
      rows.push({ kind: "step", n: ++n, depth, step: s });
      walk(s.children, depth + 1);
      (s.elseIf ?? []).forEach((b, k) => { rows.push({ kind: "section", depth: depth + 1, label: "Else if #" + (k + 1) }); walk(b.children, depth + 1); });
      if (s.else?.length) { rows.push({ kind: "section", depth: depth + 1, label: "Else" }); walk(s.else, depth + 1); }
      (s.catch ?? []).forEach((c) => { rows.push({ kind: "section", depth: depth + 1, label: "Catch (on error)" }); walk(c.children, depth + 1); });
      if (s.finally?.length) { rows.push({ kind: "section", depth: depth + 1, label: "Finally (always)" }); walk(s.finally, depth + 1); }
    }
  };
  walk(steps, 0);
  return rows;
}

// Comparisons in words, as Classic Studio writes them (studio.js OPS), so both Studios describe a step the same way.
const OPS: Record<string, string> = {
  eq: "equals", ne: "does not equal", gt: "is greater than", ge: "is greater than or equal to", lt: "is less than", le: "is less than or equal to",
  contains: "contains", notContains: "does not contain", startsWith: "starts with", notStartsWith: "does not start with", endsWith: "ends with",
  notEndsWith: "does not end with", matches: "matches pattern", notMatches: "does not match pattern", empty: "is empty", notEmpty: "is not empty",
  in: "is one of", notIn: "is not one of", between: "is between", notBetween: "is not between",
};

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);

/** A value as a person reads it: expressions as their text, lists joined, credentials by name, other objects shortened. */
export function plainValue(v: unknown, max = 60): string {
  let t: string;
  if (v === undefined || v === null) t = "";
  else if (typeof v === "string") t = v;
  else if (typeof v === "number" || typeof v === "boolean") t = String(v);
  else if (Array.isArray(v)) t = v.map((x) => plainValue(x, max)).join(", ");
  else if (isObj(v) && typeof v.expr === "string") t = v.expr.replace(/^toString\((.*)\)$/s, "$1");
  else if (isObj(v) && (v.source === "credential" || v.credential)) t = "credential " + String(v.name ?? v.credential ?? "");
  else if (isObj(v) && v.source === "variable" && typeof v.variable === "string") t = v.variable;
  else { const keys = Object.keys(v as Obj); t = keys.length ? "{" + keys.slice(0, 3).join(", ") + (keys.length > 3 ? ", …" : "") + "}" : "{}"; }
  return t.length > max ? t.slice(0, max - 1) + "…" : t;
}

const quoteIfText = (v: unknown) => typeof v === "string" && !v.startsWith("$") && !/^-?\d/.test(v) ? "“" + v + "”" : plainValue(v);

/** A condition as a sentence: "$total is greater than 1000", "Element exists (#next)", joined with and/or. */
export function conditionText(c: unknown, packages: PackageManifest[] = []): string {
  if (c === undefined || c === null || c === "") return "(no condition)";
  if (typeof c === "string") return c;
  if (!isObj(c)) return plainValue(c);
  if (typeof c.expr === "string") return c.expr;
  if (Array.isArray(c.all)) return c.all.map((x) => conditionText(x, packages)).join(" and ");
  if (Array.isArray(c.any)) return c.any.map((x) => conditionText(x, packages)).join(" or ");
  if (c.not !== undefined) return "not (" + conditionText(c.not, packages) + ")";
  if (typeof c.condition === "string") {
    const [pid, cid] = c.condition.split("/");
    const name = packages.find((p) => p.id === pid)?.conditions?.find((x) => x.id === cid)?.name ?? c.condition;
    const args = Object.values(isObj(c.args) ? c.args : {}).filter((v) => v !== undefined && v !== "").map((v) => plainValue(v, 40));
    return name + (args.length ? " (" + args.join(", ") + ")" : "");
  }
  if (typeof c.op === "string") {
    if (c.op === "eq" && typeof c.right === "boolean") return plainValue(c.left) + " is " + c.right;
    return plainValue(c.left) + " " + (OPS[c.op] ?? c.op) + (c.right !== undefined ? " " + quoteIfText(c.right) : "");
  }
  return plainValue(c);
}

/** A short, readable summary of a step's settings: conditions as sentences, then the first values, then the outputs. */
export function summarize(step: FlowStep, packages: PackageManifest[] = []): string {
  if (step.note) return step.note;
  const parts = Object.entries(step.args ?? {}).filter(([, v]) => v !== "" && v != null).slice(0, 3)
    .map(([k, v]) => k === "condition" ? conditionText(v, packages) : `${k.replace(/_/g, " ")}: ${plainValue(v)}`);
  const out = Object.values(step.out ?? {});
  if (out.length) parts.push("→ $" + out.join(", $"));
  return parts.join(" · ");
}
