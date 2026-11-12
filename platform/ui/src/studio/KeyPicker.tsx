import { useState, type ReactElement } from "react";
import type { FlowStep } from "../api/client";
import { Select } from "../components";
import { setArg } from "./flowEdit";
import { useVars } from "./VariablesPanel";

/**
 * K1 (command review, batch 6): one key picker for every field that takes a key, which writes the form that command
 * reads. Press keys takes CTRL+S; Wait for mouse or key one key; Type text keys in square brackets ([ENTER], with
 * Special keys turned on); Type into window in background keys in braces ({ENTER}); the terminal Enter, PF3 and the
 * like. The field can still be typed in (a key mapping name, a variable); nothing stored changes form.
 */

type Opt = { value: string; label: string; group?: string };
export type KeyKind = "combo" | "single" | "bracket" | "brace" | "terminal";

const MODS = ["CTRL", "ALT", "SHIFT", "WIN"];
const nums = (from: number, to: number, f: (i: number) => string) => Array.from({ length: to - from + 1 }, (_, i) => f(from + i));
/** PAGE_UP → Page up, ESC → Esc; F5 and letters stay. */
export const keyWord = (k: string) => k.length <= 3 && /\d|^[A-Z]$/.test(k) ? k : k.charAt(0) + k.slice(1).toLowerCase().replace(/_/g, " ");
const group = (g: string, keys: string[], label = keyWord): Opt[] => keys.map((k) => ({ value: k, label: label(k), group: g }));

/** The names DesktopPackage reads (Press keys, Wait for mouse or key, and [..] in Type text). */
const DESKTOP: Opt[] = [
  ...group("Common", ["ENTER", "TAB", "ESC", "SPACE", "BACKSPACE", "DELETE", "INSERT"]),
  ...group("Arrows and pages", ["UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PAGE_UP", "PAGE_DOWN"]),
  ...group("Function keys", nums(1, 12, (i) => "F" + i)),
  ...group("Letters", nums(65, 90, (i) => String.fromCharCode(i))),
  ...group("Digits", nums(0, 9, String)),
  ...group("Other", ["PRINTSCREEN", "PAUSE", "MENU", "CAPS_LOCK", "NUM_LOCK", "SCROLL_LOCK", "WIN"]),
];
/** The names WindowPackage.VKEYS reads in braces (background typing has no modifier keys). */
const BACKGROUND: Opt[] = [
  ...group("Common", ["ENTER", "TAB", "ESC", "SPACE", "BACKSPACE", "DELETE", "INSERT"]),
  ...group("Arrows and pages", ["UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN"], (k) => k === "PGUP" ? "Page up" : k === "PGDN" ? "Page down" : keyWord(k)),
  ...group("Function keys", nums(1, 12, (i) => "F" + i)),
];
/** TerminalPackage.keyAction and Tn5250Session.keyAction, grouped by the hosts that have them. */
const TERMINAL: Opt[] = [
  ...group("Every terminal", ["Enter", "Tab", "BackTab", "Home", "Newline", "Up", "Down", "Left", "Right", "BackSpace", "Delete", "Insert", "EraseEOF", "EraseInput", "FieldEnd", "Reset"], (k) => k),
  ...group("Function keys", nums(1, 24, (i) => "PF" + i), (k) => k),
  ...group("3270 and 5250", ["Clear", "Attn", "SysReq", "Dup"], (k) => k),
  ...group("3270 only", ["PA1", "PA2", "PA3", "FieldMark"], (k) => k),
  ...group("5250 and VT", ["PageUp", "PageDown"], (k) => k),
  ...group("5250 (IBM i) only", ["FieldExit", "Help", "Print"], (k) => k),
  ...group("VT over SSH only", ["Escape", "End", "Ctrl+C"], (k) => k),
];

/** Which key picker a field has, or null. */
export function keyKind(action: string, name: string): KeyKind | null {
  if (action === "ooa.desktop/press_keys" && name === "keys") return "combo";
  if (action === "ooa.desktop/wait_for_input" && name === "key") return "single";
  if (action === "ooa.desktop/type_text" && name === "text") return "bracket";
  if (action === "ooa.window/type_in_background" && name === "text") return "brace";
  if ((action === "ooa.terminal/send_key" && name === "key") || (/^ooa\.terminal\/(send_text|set_field)$/.test(action) && name === "press_key")) return "terminal";
  return null;
}

/** The modifier keys and key of a combination as Press keys stores it, e.g. "ctrl + shift+s" → [CTRL, SHIFT], S. */
export function splitCombo(text: string): { mods: string[]; key: string } {
  const parts = text.toUpperCase().split("+").map((p) => p.trim()).filter(Boolean);
  return { mods: MODS.filter((m) => parts.includes(m)), key: parts.filter((p) => !MODS.includes(p)).join("+") };
}

/** Writes a picked key in the command's own form; insert kinds add it to the end of the text. */
export function withKey(kind: KeyKind, current: unknown, key: string, mods: string[]): Record<string, unknown> | null {
  const text = typeof current === "string" ? current : current === undefined ? "" : null;
  const held = MODS.filter((m) => mods.includes(m));
  switch (kind) {
    case "combo": return { value: [...held, key].join("+") };
    case "single": case "terminal": return { value: key };
    case "bracket": return text === null ? null : { value: text + "[" + [...held, key].join("+") + "]", special_keys: true };
    case "brace": return text === null ? null : { value: text + "{" + key + "}" };
  }
}

export function KeyPicker({ step, name, kind }: { step: FlowStep; name: string; kind: KeyKind }): ReactElement {
  const vars = useVars();
  const cur = step.args?.[name];
  const curText = typeof cur === "string" ? cur : "";
  const [mods, setMods] = useState<string[]>(kind === "combo" ? splitCombo(curText).mods : []);
  const insert = kind === "bracket" || kind === "brace";
  const variable = typeof cur === "string" && /^\s*\$/.test(cur) || (cur !== undefined && typeof cur !== "string");
  const put = (r: Record<string, unknown> | null) => {
    if (!vars || !r) return;
    let f = vars.flow;
    for (const [k, v] of Object.entries(r)) f = setArg(f, step.id, k === "value" ? name : k, v);
    vars.edit(f);
  };
  const toggle = (m: string, on: boolean) => {
    const next = on ? [...mods, m] : mods.filter((x) => x !== m);
    setMods(next);
    // Press keys: a key already chosen keeps it, with the modifier keys now ticked
    const key = splitCombo(curText).key;
    if (kind === "combo" && key && !variable) put(withKey(kind, cur, key, next));
  };
  const list = kind === "terminal" ? TERMINAL : kind === "brace" ? BACKGROUND : DESKTOP;
  const what = insert ? "Insert a key…" : kind === "combo" ? "Key…" : "Pick a key…";
  return <div className="st-key-picker" role="group" aria-label={"Keys for " + name}>
    {(kind === "combo" || kind === "bracket") && MODS.map((m) => <label key={m} className="st-check-line st-key-mod">
      <input type="checkbox" checked={mods.includes(m)} disabled={variable} onChange={(e) => toggle(m, e.target.checked)} /> {keyWord(m)}
    </label>)}
    <Select aria-label={insert ? "Insert a key into the text" : "Pick a key"} value="" disabled={!vars || variable}
      onChange={(e) => e.target.value && put(withKey(kind, cur, e.target.value, mods))}
      options={[{ value: "", label: variable ? "Keys can't be added to a variable" : what }, ...list]} />
  </div>;
}
