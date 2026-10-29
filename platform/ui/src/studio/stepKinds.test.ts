import { describe, expect, it } from "vitest";
import type { FlowStep, PackageManifest } from "../api/client";
import { actionGroups, connectionInputs, elementFields, helpText, outcomeOf, shownWhen, valueLabel } from "./stepKinds";

const web = (action: string, controlType?: string): FlowStep => ({ id: "s1", action: "ooa.browser/element_action",
  args: { action, object: { properties: controlType ? [{ name: "control_type", value: controlType }] : [] } } });

describe("settings by kind", () => {
  it("shows a field for some values, or with ! for all but some", () => {
    const at = (v: string) => (name: string) => (name === "iterator" ? v : undefined);
    expect(shownWhen({ iterator: ["times"] }, at("times"))).toBe(true);
    expect(shownWhen({ iterator: ["times"] }, at("list"))).toBe(false);
    expect(shownWhen({ iterator: ["!", "times", "while"] }, at("list"))).toBe(true);
    expect(shownWhen({ iterator: ["!", "times", "while"] }, at("while"))).toBe(false);
    expect(shownWhen(undefined, at("x"))).toBe(true);
  });

  it("puts a number's limits in its help", () => {
    expect(helpText({ minimum: 0 }, false)).toBe("Smallest value: 0.");
    expect(helpText({ minimum: 1, maximum: 9, description: "Pages." }, true)).toBe("Required. Pages. From 1 to 9.");
  });

  it("offers the actions of the captured control first", () => {
    const all = ["click", "set_text", "select_by_text", "select_by_value", "select_by_index", "get_selected", "check"];
    const g = actionGroups(web("click", "Drop-down list"), all);
    expect(g[0]).toEqual({ label: "For this drop-down list", actions: ["select_by_text", "select_by_value", "select_by_index", "get_selected", "click"] });
    expect(g[1].actions).toEqual(["set_text", "check"]);
    const desk: FlowStep = { id: "d", action: "ooa.desktop_ui/object_action", args: { object: { properties: [{ name: "ControlType", value: "CheckBox" }] } } };
    expect(actionGroups(desk, ["click", "check", "uncheck", "toggle", "get_text"])[0].actions.slice(0, 3)).toEqual(["check", "uncheck", "toggle"]);
    expect(actionGroups(web("click"), all)).toEqual([{ label: "", actions: all }]);
  });

  it("shows only the fields the chosen action uses, with the value named for it", () => {
    expect(elementFields(web("click"))!.has("value")).toBe(false);
    expect(elementFields(web("click"))!.has("click_method")).toBe(true);
    expect(elementFields(web("set_text"))!.has("typing")).toBe(true);
    expect(valueLabel(web("set_text"))).toBe("Text to enter");
    expect(valueLabel(web("select_by_text"))).toBe("Item text");
    const desk: FlowStep = { id: "d", action: "ooa.desktop_ui/object_action", args: { action: "click", run_in_background: true } };
    expect([...elementFields(desk)!].filter((x) => x.startsWith("click"))).toEqual(["click_type"]);   // no mouse options in the background
    expect(elementFields({ id: "x", action: "core/log" })).toBeNull();
  });

  it("names the result of an action and gives it the right type", () => {
    expect(outcomeOf("get_selected_index")).toEqual({ name: "selected_index", type: "Number" });
    expect(outcomeOf("is_checked")).toEqual({ name: "is_checked", type: "Boolean" });
    expect(outcomeOf("get_table")).toEqual({ name: "table_data", type: "Table" });
    expect(outcomeOf("click")).toBeNull();
  });

  it("finds what a saved connection supplies: inputs on nearly every action, with a credential among them", () => {
    const pkg = (id: string, inputs: Record<string, object>): PackageManifest => ({ id, name: id, version: "1",
      actions: ["a", "b", "c"].map((a) => ({ id: a, name: a, inputs: { properties: { ...inputs, [a + "_only"]: {} } } })) });
    expect([...connectionInputs(pkg("ooa.snow", { instance: {}, password: { "x-ooa-type": "Credential" } }))]).toEqual(["instance", "password"]);
    expect(connectionInputs(pkg("ooa.files", { path: {} })).size).toBe(0);
    expect(connectionInputs(pkg("ooa.rest", { url: {}, token: { "x-ooa-type": "Credential" } })).size).toBe(0);
  });
});
