import { describe, expect, it } from "vitest";
import { keyKind, keyWord, splitCombo, withKey } from "./KeyPicker";
import { pointArgs } from "./Pickers";

describe("key picker (K1)", () => {
  it("finds every field that takes a key", () => {
    expect(keyKind("ooa.desktop/press_keys", "keys")).toBe("combo");
    expect(keyKind("ooa.desktop/wait_for_input", "key")).toBe("single");
    expect(keyKind("ooa.desktop/type_text", "text")).toBe("bracket");
    expect(keyKind("ooa.window/type_in_background", "text")).toBe("brace");
    expect(keyKind("ooa.terminal/send_key", "key")).toBe("terminal");
    expect(keyKind("ooa.terminal/send_text", "press_key")).toBe("terminal");
    expect(keyKind("ooa.terminal/set_field", "press_key")).toBe("terminal");
    expect(keyKind("ooa.terminal/send_text", "text")).toBeNull();
    expect(keyKind("ooa.desktop/press_keys", "times")).toBeNull();
  });

  it("writes each command's own form", () => {
    expect(withKey("combo", "ALT+F4", "S", ["SHIFT", "CTRL"])).toEqual({ value: "CTRL+SHIFT+S" });
    expect(withKey("single", undefined, "F9", [])).toEqual({ value: "F9" });
    expect(withKey("terminal", "Enter", "PF3", [])).toEqual({ value: "PF3" });
    // Type text: keys in square brackets, added to the text, and Special keys turned on so they are read
    expect(withKey("bracket", "Dear Ann", "ENTER", [])).toEqual({ value: "Dear Ann[ENTER]", special_keys: true });
    expect(withKey("bracket", undefined, "S", ["CTRL"])).toEqual({ value: "[CTRL+S]", special_keys: true });
    // background typing: keys in braces
    expect(withKey("brace", "abc", "TAB", [])).toEqual({ value: "abc{TAB}" });
    // a variable or a calculation can't have a key added
    expect(withKey("brace", { expr: "$a" }, "TAB", [])).toBeNull();
  });

  it("reads a stored combination back", () => {
    expect(splitCombo("ctrl + shift+s")).toEqual({ mods: ["CTRL", "SHIFT"], key: "S" });
    expect(splitCombo("ENTER")).toEqual({ mods: [], key: "ENTER" });
    expect(splitCombo("")).toEqual({ mods: [], key: "" });
  });

  it("names keys in words", () => {
    expect(keyWord("PAGE_UP")).toBe("Page up");
    expect(keyWord("ESC")).toBe("Esc");
    expect(keyWord("F5")).toBe("F5");
    expect(keyWord("A")).toBe("A");
  });
});

describe("pick on screen (M1, M2)", () => {
  const inWindow = { x: 40, y: 12, screen_x: 140, screen_y: 112, window: "Untitled - Notepad", window_width: 800, window_height: 600 };
  it("measures the point from the window and keeps its size for mouse steps", () => {
    expect(pointArgs("ooa.desktop/mouse_click", inWindow)).toEqual({ x: 40, y: 12, relative_to: "window", window: "Untitled - Notepad", window_width: 800, window_height: 600 });
  });
  it("gives a callout the point without a size", () => {
    expect(pointArgs("ooa.desktop/show_callout", inWindow)).toEqual({ x: 40, y: 12, relative_to: "window", window: "Untitled - Notepad" });
  });
  it("uses the screen when no window is under the pointer, and clears what a window left", () => {
    expect(pointArgs("ooa.desktop/mouse_move", { x: 5, y: 6, screen_x: 5, screen_y: 6, window: "" }))
      .toEqual({ x: 5, y: 6, relative_to: "screen", window: undefined, window_width: undefined, window_height: undefined });
  });
});
