import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { SHORTCUTS, is, keyOf, shortcutsFor, withKey } from "./shortcuts";

/**
 * The shortcut list is the only place keys are written (item 9): each shortcut's handler matches it with is(e, id), and
 * no label, tooltip or menu writes a key by hand, so a key shown is a key that works (the debug tooltips once promised
 * F5, F10 and F11 with no handler).
 */
const SRC = join(__dirname, "..");
const files = (dir: string): string[] => readdirSync(dir).flatMap((f) => {
  const p = join(dir, f);
  return statSync(p).isDirectory() ? files(p) : /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f) ? [p] : [];
});
const key = (k: string, more: Partial<KeyboardEventInit> = {}) => new KeyboardEvent("keydown", { key: k, ...more });

describe("keyboard shortcuts", () => {
  afterEach(() => localStorage.clear());

  it("has a handler for every shortcut it shows", () => {
    for (const s of SHORTCUTS)
      for (const file of s.handler.split(" ")) {
        const text = readFileSync(join(SRC, file), "utf8");
        expect(text.includes('is(e, "' + s.id + '")') || text.includes('is(ev, "' + s.id + '")') || text.includes('is(e.nativeEvent, "' + s.id + '")'),
          s.id + " is matched in " + file).toBe(true);
      }
    expect(new Set(SHORTCUTS.map((s) => s.id)).size).toBe(SHORTCUTS.length);
  });

  it("writes no key by hand in a label, tooltip or menu", () => {
    const bad: string[] = [];
    for (const f of files(SRC)) {
      if (f.endsWith("shortcuts.ts")) continue;
      readFileSync(f, "utf8").split("\n").forEach((line, i) => {
        if (/shortcut: "/.test(line) || /title="[^"]*\((?:Ctrl|Shift|Alt|F\d|Del)/.test(line) || /"[^"]*\((?:Ctrl|Shift|Alt)\+[^"]*\)"/.test(line)) bad.push(f.slice(SRC.length) + ":" + (i + 1));
      });
    }
    expect(bad).toEqual([]);
  });

  it("matches keys exactly, Shift included, but not Shift of a punctuation key", () => {
    expect(is(key("z", { ctrlKey: true }), "app.undo")).toBe(true);
    expect(is(key("Z", { ctrlKey: true, shiftKey: true }), "app.undo")).toBe(false);
    expect(is(key("Z", { ctrlKey: true, shiftKey: true }), "app.redo")).toBe(true);
    expect(is(key("s", { metaKey: true }), "app.save")).toBe(true);   // Cmd on a Mac
    expect(is(key("F5"), "run.debug")).toBe(true);
    expect(is(key("F5", { ctrlKey: true }), "run.debug")).toBe(false);
    expect(is(key("F6"), "run.stepOver")).toBe(true);
    expect(is(key("ArrowUp", { altKey: true }), "steps.up")).toBe(true);
    expect(is(key("ArrowUp", { altKey: true }), "steps.previous")).toBe(false);
    expect(is(key("?", { shiftKey: true }), "app.shortcuts")).toBe(true);
    expect(is(key("[", { ctrlKey: true }), "steps.foldAll")).toBe(true);
    expect(is(key("[", { ctrlKey: true }), "steps.fold")).toBe(false);
    expect(is(key("ArrowLeft"), "steps.fold")).toBe(true);
  });

  it("lets a person switch single-key shortcuts off, but not keys with Ctrl", () => {
    localStorage.setItem("ooa.studio.display.singleKeys", "false");
    expect(is(key("?", { shiftKey: true }), "app.shortcuts")).toBe(false);
    expect(is(key("["), "steps.fold")).toBe(false);
    expect(is(key("ArrowLeft"), "steps.fold")).toBe(true);   // arrows are not character keys
    expect(is(key("[", { ctrlKey: true }), "steps.foldAll")).toBe(true);
  });

  it("shows keys and groups them by area for each app", () => {
    expect(keyOf("run.stepOver")).toBe("F10 or F6");
    expect(keyOf("steps.menu")).toBe("Shift+F10");   // the menu key has no label of its own
    expect(withKey("Save", "app.save")).toBe("Save (Ctrl+S or Ctrl+Enter)");
    expect(shortcutsFor("console").map(([a]) => a)).toEqual(["Everywhere", "Lists"]);
    expect(shortcutsFor("studio").map(([a]) => a)).toEqual(["Everywhere", "Run and debug", "Agent steps", "Agents list"]);
    // never a key the browser keeps for itself
    for (const s of SHORTCUTS) for (const k of s.keys) expect(["Ctrl+W", "Ctrl+T", "Ctrl+N", "Ctrl+Tab", "F1", "Ctrl+L", "Ctrl+P"]).not.toContain(k);
  });
});
