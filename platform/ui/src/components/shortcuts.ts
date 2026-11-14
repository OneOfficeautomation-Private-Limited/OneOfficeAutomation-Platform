/**
 * Every keyboard shortcut of OneAgentStudio and One CommandCenter, in one list (item 9, 1 Nov 2026). The key handlers
 * match keys with is(e, id), and menu labels, tooltips and the Keyboard shortcuts dialog show keyOf(id), so a key shown
 * is a key that works (shortcuts.test.ts checks every entry has a handler, and that no label writes a key by hand).
 * Following AA's editor and UiPath's debugger; nothing the browser keeps for itself (Ctrl+W, Ctrl+T, Ctrl+N, Ctrl+Tab, F1)
 * is taken, and text fields keep their own keys. Single-character keys ([, ], / and ?) can be switched off per person
 * (WCAG 2.1.4); remapping is not offered, as in AA, UiPath and Blue Prism.
 */
export type ShortcutApp = "studio" | "console" | "both";
export type Shortcut = { id: string; keys: string[]; label: string; area: string; app: ShortcutApp;
  /** The file whose handler calls is(e, id); checked by shortcuts.test.ts. */
  handler: string };

const S = (id: string, keys: string | string[], label: string, area: string, app: ShortcutApp, handler: string): Shortcut =>
  ({ id, keys: Array.isArray(keys) ? keys : [keys], label, area, app, handler });

export const SHORTCUTS: Shortcut[] = [
  S("app.search", "Ctrl+K", "Go to the search", "Everywhere", "both", "studio/App.tsx console/TopBar.tsx"),
  S("app.shortcuts", "?", "Show these keyboard shortcuts", "Everywhere", "both", "studio/App.tsx console/ConsoleApp.tsx"),
  S("app.save", ["Ctrl+S", "Ctrl+Enter"], "Save the open item", "Everywhere", "studio", "studio/App.tsx"),
  S("app.undo", "Ctrl+Z", "Undo (outside text fields)", "Everywhere", "studio", "studio/App.tsx"),
  S("app.redo", ["Ctrl+Y", "Ctrl+Shift+Z"], "Redo (outside text fields)", "Everywhere", "studio", "studio/App.tsx"),

  S("run.debug", "F5", "Debug, or continue from a breakpoint", "Run and debug", "studio", "studio/App.tsx"),
  S("run.run", "Ctrl+F5", "Run without stopping at breakpoints", "Run and debug", "studio", "studio/App.tsx"),
  S("run.stop", "Shift+F5", "Stop the run", "Run and debug", "studio", "studio/App.tsx"),
  S("run.stepOver", ["F10", "F6"], "Step over: run this step and stop at the next", "Run and debug", "studio", "studio/App.tsx"),
  S("run.stepInto", "F11", "Step into a block or child agent", "Run and debug", "studio", "studio/App.tsx"),
  S("run.stepOut", "Shift+F11", "Step out of the block or child agent", "Run and debug", "studio", "studio/App.tsx"),
  S("run.breakpoint", "F9", "Breakpoint on the selected steps", "Run and debug", "studio", "studio/Editor.tsx"),
  S("run.clearBreakpoints", "Shift+F9", "Remove every breakpoint", "Run and debug", "studio", "studio/Editor.tsx"),

  S("steps.next", "↓", "Select the next step", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.previous", "↑", "Select the step before", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.extendDown", "Shift+↓", "Add the next step to the selection", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.extendUp", "Shift+↑", "Add the step before to the selection", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.all", "Ctrl+A", "Select every step", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.clear", "Esc", "Clear the selection", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.settings", "Enter", "Go to the selected step's settings", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.details", "Space", "Show or hide the Details panel", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.copy", "Ctrl+C", "Copy", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.cut", "Ctrl+X", "Cut", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.paste", "Ctrl+V", "Paste after the selected step", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.duplicate", "Ctrl+D", "Duplicate", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.disable", "Ctrl+/", "Switch the steps off or on", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.delete", "Del", "Delete", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.up", "Alt+↑", "Move up", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.down", "Alt+↓", "Move down", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.menu", ["Shift+F10", "ContextMenu"], "Open the step's menu", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.fold", ["[", "←"], "Fold the selected block", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.unfold", ["]", "→"], "Unfold the selected block", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.foldAll", "Ctrl+[", "Fold every block", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.unfoldAll", "Ctrl+]", "Unfold every block", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.find", "Ctrl+Shift+F", "Find in this agent", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.replace", "Ctrl+H", "Find and replace in the steps' settings", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.rename", "F2", "Rename the step (Shown on the step)", "Agent steps", "studio", "studio/Editor.tsx"),
  S("steps.record", "Ctrl+Shift+R", "Record", "Agent steps", "studio", "studio/Editor.tsx"),
  S("fields.variable", ["F2", "Ctrl+Space"], "Insert a value (a variable) in a field", "Agent steps", "studio", "studio/ValueSources.tsx"),

  S("files.copy", "Ctrl+C", "Copy", "Agents list", "studio", "studio/Automation.tsx"),
  S("files.cut", "Ctrl+X", "Cut", "Agents list", "studio", "studio/Automation.tsx"),
  S("files.paste", "Ctrl+V", "Paste into this folder", "Agents list", "studio", "studio/Automation.tsx"),
  S("files.all", "Ctrl+A", "Select every item", "Agents list", "studio", "studio/Automation.tsx"),
  S("files.delete", "Del", "Delete", "Agents list", "studio", "studio/Automation.tsx"),
  S("files.rename", "F2", "Rename", "Agents list", "studio", "studio/Automation.tsx"),
  S("files.clear", "Esc", "Clear the selection", "Agents list", "studio", "studio/Automation.tsx"),

  S("list.next", "↓", "Go to the next row", "Lists", "console", "console/listKeys.ts"),
  S("list.previous", "↑", "Go to the row before", "Lists", "console", "console/listKeys.ts"),
  S("list.open", "Enter", "Open the row", "Lists", "console", "console/listKeys.ts"),
  S("list.menu", ["Shift+F10", "ContextMenu"], "Open the row's ⋯ menu", "Lists", "console", "console/listKeys.ts"),
  S("list.search", "/", "Go to the list's search", "Lists", "console", "console/listKeys.ts"),
];

const KEY_NAMES: Record<string, string> = { "↑": "ArrowUp", "↓": "ArrowDown", "←": "ArrowLeft", "→": "ArrowRight", Del: "Delete", Esc: "Escape", Space: " " };
const byId = new Map(SHORTCUTS.map((s) => [s.id, s]));

/** The keys of a shortcut as shown to people: "F10 or F6". */
export function keyOf(id: string): string {
  const s = byId.get(id);
  if (!s) throw new Error("No shortcut " + id);
  return s.keys.filter((k) => k !== "ContextMenu").join(" or ");
}

/** A label with its keys: "Save (Ctrl+S or Ctrl+Enter)". */
export const withKey = (label: string, id: string) => label + " (" + keyOf(id) + ")";

/** A punctuation key ([, ], / or ?): Shift is part of the character, and on its own a person may switch it off. */
const single = (k: string) => /^[^A-Za-z0-9\s↑↓←→]$/.test(k);

/** Single-character shortcuts are on unless the person switched them off (kept in this browser). */
export function singleKeysOn(): boolean {
  try { return localStorage.getItem("ooa.studio.display.singleKeys") !== "false"; } catch { return true; }
}

function matchesOne(e: KeyboardEvent, spec: string): boolean {
  const parts = spec === "+" ? ["+"] : spec.split("+");
  const key = parts[parts.length - 1], mods = new Set(parts.slice(0, -1));
  const ctrl = e.ctrlKey || e.metaKey;
  if (mods.has("Ctrl") !== ctrl || mods.has("Alt") !== e.altKey) return false;
  if (single(key)) {
    if (!mods.has("Ctrl") && !singleKeysOn()) return false;
    return e.key === key;
  }
  if (mods.has("Shift") !== e.shiftKey) return false;
  const want = KEY_NAMES[key] ?? key;
  return want.length === 1 ? e.key.toLowerCase() === want.toLowerCase() : e.key === want;
}

/** Whether a key event is one of the shortcut's keys. */
export function is(e: KeyboardEvent, id: string): boolean {
  const s = byId.get(id);
  return !!s && s.keys.some((k) => matchesOne(e, k));
}

/** The shortcuts of an app, by area, for the Keyboard shortcuts dialog. */
export function shortcutsFor(app: "studio" | "console"): [string, Shortcut[]][] {
  const groups = new Map<string, Shortcut[]>();
  for (const s of SHORTCUTS) if (s.app === app || s.app === "both") groups.set(s.area, [...(groups.get(s.area) ?? []), s]);
  return [...groups.entries()];
}

/** Whether focus is in a text field, where the field keeps its own keys. */
export const inTextField = (e: KeyboardEvent) => !!(e.target as HTMLElement | null)?.closest?.("input, textarea, select, [contenteditable=true]");
