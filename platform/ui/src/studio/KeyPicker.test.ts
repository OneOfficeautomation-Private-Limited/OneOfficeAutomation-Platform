import { describe, expect, it } from "vitest";
import { keyKind, keyWord, splitCombo, withKey } from "./KeyPicker";

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
