import type { PackageManifest } from "../api/client";

/**
 * Simple mode (G47, as UiPath StudioX): for business users who automate their own Excel, e-mail and browser work. The
 * Actions panel shows a short list of everyday toolkits under plain names, and only the basic building blocks of Core.
 * Agents stay ordinary agents: a developer opens the same file with Simple mode off and sees everything.
 */
export const SIMPLE_TOOLKITS: { id: string; name: string; actions?: string[] }[] = [
  { id: "core", name: "Decisions, repeats and notes", actions: ["if", "loop", "set", "comment", "log", "delay", "break", "try"] },
  { id: "ooa.excel", name: "Excel files" },
  { id: "ooa.office_attach", name: "Excel or Word already open" },
  { id: "ooa.email", name: "E-mail" },
  { id: "ooa.outlook_desktop", name: "Outlook" },
  { id: "ooa.microsoft365", name: "Microsoft 365 (Outlook, Teams, OneDrive)" },
  { id: "ooa.browser", name: "Websites" },
  { id: "ooa.file", name: "Files" },
  { id: "ooa.folder", name: "Folders" },
  { id: "ooa.pdf", name: "PDF" },
  { id: "ooa.word", name: "Word files" },
  { id: "ooa.csv", name: "CSV and text files" },
  { id: "ooa.datetime", name: "Dates and times" },
  { id: "ooa.string", name: "Text" },
  { id: "ooa.desktop", name: "Ask a person", actions: ["message_box", "prompt_value", "prompt_yes_no", "prompt_file", "prompt_folder", "show_callout"] },
  { id: "ooa.forms", name: "Forms" },
];

/** The packages as Simple mode shows them: the listed toolkits that are installed, renamed, with their chosen actions. */
export function simplePackages(packages: PackageManifest[]): PackageManifest[] {
  const out: PackageManifest[] = [];
  for (const s of SIMPLE_TOOLKITS) {
    const p = packages.find((x) => x.id === s.id);
    if (!p) continue;
    const actions = s.actions ? p.actions.filter((a) => s.actions!.includes(a.id)) : p.actions;
    if (actions.length) out.push({ ...p, name: s.name, actions });
  }
  return out;
}

/** True when every step of an agent can be shown in Simple mode (its toolkits and Core building blocks are listed). */
export function fitsSimple(actions: string[], packages: PackageManifest[]): boolean {
  const simple = simplePackages(packages);
  return actions.every((a) => {
    const [pid, aid] = a.split("/");
    return simple.some((p) => p.id === pid && p.actions.some((x) => x.id === aid));
  });
}
