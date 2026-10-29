import type { NewItemKind, WorkspaceNode } from "../api/client";
import type { IconName } from "../components";

/** What Create offers, in plain words, with each type's mark. The AI agent type arrives with the AI agent editor (P5). */
export type ItemType = { kind: NewItemKind; name: string; description: string; icon: IconName; color: string };

export const ITEM_TYPES: ItemType[] = [
  { kind: "TaskBot", name: "Agent", description: "Steps in apps, files and websites", icon: "bolt", color: "#2f6fde" },
  { kind: "process", name: "Workflow", description: "Agents, people and approvals", icon: "process", color: "#0f766e" },
  { kind: "form", name: "Form", description: "A screen people fill in or approve", icon: "form", color: "#0f766e" },
  { kind: "document", name: "Document type", description: "Fields to read from documents", icon: "doc", color: "#7c3aed" },
  { kind: "skill", name: "Prompt Skill", description: "A reusable, tested prompt", icon: "spark", color: "#7c3aed" },
  { kind: "ApiTask", name: "API agent", description: "Runs on the server, no desktop", icon: "globe", color: "#0e7490" },
  { kind: "script-python", name: "Python script", description: "Called from agents", icon: "code", color: "#334155" },
  { kind: "script-powershell", name: "PowerShell script", description: "Called from agents", icon: "code", color: "#334155" },
];

/** An item's name without the file ending that marks its type (the Type column says it); scripts and files keep theirs. */
export function displayName(n: WorkspaceNode): string {
  const ending = { bot: /\.flow\.json$/i, form: /\.form\.json$/i, process: /\.process\.json$/i, document: /\.doc\.json$/i, skill: /\.json$/i } as Record<string, RegExp>;
  return ending[n.type] ? n.name.replace(ending[n.type], "") : n.name;
}

/** A workspace file's type in words, for the items table. */
export function typeLabel(n: WorkspaceNode): string {
  if (n.type === "bot") return n.kind === "ApiTask" ? "API agent" : "Agent";
  return { folder: "Folder", form: "Form", process: "Workflow", document: "Document type", skill: "Prompt Skill", script: "Script", file: "File", bot: "Agent" }[n.type];
}
