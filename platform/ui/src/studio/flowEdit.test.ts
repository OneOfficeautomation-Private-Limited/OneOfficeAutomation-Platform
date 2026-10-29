import { describe, expect, it } from "vitest";
import type { Flow, PackageManifest } from "../api/client";
import {
  addStep, duplicateStep, fieldOrder, locate, moveStep, newStep, nextId, parseLoose, putVariable, removeStep, removeVariable,
  setArg, setOut, toggleDisabled, insertAt, moveTo, removeSteps, toggleDisabledMany, duplicateSteps,
} from "./flowEdit";

const packages: PackageManifest[] = [
  { id: "core", name: "Core", version: "1", actions: [
    { id: "loop", name: "Loop", inputs: { properties: { iterator: {}, over: {}, as: {} } } },
    { id: "if", name: "If", inputs: { properties: { condition: {} } } },
    { id: "try", name: "Try", inputs: { properties: {} } },
    { id: "throw", name: "Raise an error", inputs: { properties: { code: {}, message: {} } } },
    { id: "log", name: "Write to log", inputs: { properties: { message: { examples: ["Hello"] } }, required: ["message"] } }] },
  { id: "ooa.excel", name: "Excel", version: "1", actions: [
    { id: "open", name: "Open workbook", inputs: { properties: { path: {}, session: {}, password: {} }, required: ["path"] },
      "x-ooa-panel": { main: ["path", "(Sign in: password)"], more: ["session"] } }] },
];

const flow = (): Flow => ({ kind: "TaskBot", id: "a", name: "A", steps: [
  { id: "s1", action: "ooa.excel/open", args: { path: "$wb" } },
  { id: "s2", action: "core/loop", args: { iterator: "list" }, children: [{ id: "s3", action: "core/log" }] },
  { id: "s4", action: "core/log" },
] });

describe("editing an agent", () => {
  it("builds new steps as Classic Studio does", () => {
    expect(newStep("core/log", flow(), packages)).toEqual({ id: "s5", action: "core/log", args: { message: "Hello" } });
    expect(newStep("core/loop", flow(), packages)).toEqual({ id: "s5", action: "core/loop", args: { iterator: "list" }, children: [] });
    expect(newStep("core/if", flow(), packages)).toMatchObject({ children: [], else: [] });
    expect(newStep("core/try", flow(), packages)).toMatchObject({ children: [], catch: [{ codes: [], children: [] }] });
    expect(newStep("core/throw", flow(), packages).args).toEqual({ code: "BUSINESS.ERROR" });
    expect(nextId({ ...flow(), steps: [] })).toBe("s1");
  });

  it("adds after the selected step, inside a selected block, or at the end; never changes the original", () => {
    const f = flow();
    const step = { id: "s9", action: "core/log" };
    expect(addStep(f, "s1", step).steps.map((s) => s.id)).toEqual(["s1", "s9", "s2", "s4"]);
    expect(addStep(f, "s2", step).steps[1].children!.map((s) => s.id)).toEqual(["s3", "s9"]);
    expect(addStep(f, "s3", step).steps[1].children!.map((s) => s.id)).toEqual(["s3", "s9"]);
    expect(addStep(f, null, step).steps.map((s) => s.id)).toEqual(["s1", "s2", "s4", "s9"]);
    expect(f.steps.map((s) => s.id)).toEqual(["s1", "s2", "s4"]);
  });

  it("removes, moves within its list, duplicates with new ids, and switches steps off", () => {
    const f = flow();
    expect(removeStep(f, "s3").steps[1].children).toEqual([]);
    expect(moveStep(f, "s4", -1).steps.map((s) => s.id)).toEqual(["s1", "s4", "s2"]);
    expect(moveStep(f, "s1", -1)).toBe(f);   // already first
    const d = duplicateStep(f, "s2");
    expect(d.id).toBe("s5");
    expect(d.flow.steps.map((s) => s.id)).toEqual(["s1", "s2", "s5", "s4"]);
    expect(d.flow.steps[2].children![0].id).toBe("s6");
    expect(locate(toggleDisabled(f, "s4").steps, "s4")!.step.disabled).toBe(true);
    expect(locate(toggleDisabled(toggleDisabled(f, "s4"), "s4").steps, "s4")!.step.disabled).toBeUndefined();
  });

  it("sets and clears inputs and outputs", () => {
    let f = setArg(flow(), "s1", "session", "xl");
    expect(f.steps[0].args).toEqual({ path: "$wb", session: "xl" });
    f = setArg(setArg(f, "s1", "path", undefined), "s1", "session", undefined);
    expect(f.steps[0].args).toBeUndefined();
    f = setOut(flow(), "s1", "table", "$rows");
    expect(f.steps[0].out).toEqual({ table: "rows" });
    expect(setOut(f, "s1", "table", " ").steps[0].out).toBeUndefined();
  });

  it("adds, renames and removes variables, refusing bad or taken names", () => {
    let f = putVariable(flow(), { name: "total", type: "Number", direction: "output" });
    expect(f.variables).toEqual([{ name: "total", type: "Number", direction: "output" }]);
    f = putVariable(f, { name: "sum", type: "Number" }, "total");
    expect(f.variables!.map((v) => v.name)).toEqual(["sum"]);
    expect(() => putVariable(f, { name: "2nd", type: "String" })).toThrow(/starts with a letter/);
    expect(() => putVariable(f, { name: "sum", type: "String" })).toThrow(/already/);
    expect(removeVariable(f, "sum").variables).toEqual([]);
  });

  it("reads field text as Classic Studio does, and orders fields by the panel layout", () => {
    expect(parseLoose("")).toBeUndefined();
    expect(parseLoose("42")).toBe(42);
    expect(parseLoose("true")).toBe(true);
    expect(parseLoose('{"a":1}')).toEqual({ a: 1 });
    expect(parseLoose("$total")).toBe("$total");
    expect(parseLoose("12 apples")).toBe("12 apples");
    expect(fieldOrder(packages[1].actions[0])).toEqual({ main: ["path", "password"], more: ["session"] });
  });

  it("moves by drag and drop: before, after, inside a block; never into itself", () => {
    const f = flow();
    expect(moveTo(f, "s4", "s1", "before").steps.map((s) => s.id)).toEqual(["s4", "s1", "s2"]);
    expect(moveTo(f, "s1", "s3", "after").steps[0].children!.map((s) => s.id)).toEqual(["s3", "s1"]);
    expect(moveTo(f, "s4", "s2", "inside").steps[1].children!.map((s) => s.id)).toEqual(["s3", "s4"]);
    expect(moveTo(f, "s3", "s4", "after").steps.map((s) => s.id)).toEqual(["s1", "s2", "s4", "s3"]);   // out of the loop
    expect(moveTo(f, "s2", "s3", "after")).toBe(f);   // a loop cannot go inside itself
    expect(moveTo(f, "s1", "s4", "inside")).toBe(f);  // a log is not a block
    expect(insertAt(f, { id: "s9", action: "core/log" }, "s2", "inside").steps[1].children!.map((s) => s.id)).toEqual(["s3", "s9"]);
    expect(insertAt(f, { id: "s9", action: "core/log" }, null, "after").steps.map((s) => s.id)).toEqual(["s1", "s2", "s4", "s9"]);
  });

  it("deletes, switches off and duplicates several steps at once", () => {
    const f = flow();
    expect(removeSteps(f, ["s1", "s3"]).steps.map((s) => s.id)).toEqual(["s2", "s4"]);
    expect(removeSteps(f, ["s2", "s3"]).steps.map((s) => s.id)).toEqual(["s1", "s4"]);   // s3 went with its loop
    const off = toggleDisabledMany(f, ["s1", "s4"]);
    expect([off.steps[0].disabled, off.steps[2].disabled]).toEqual([true, true]);
    expect(toggleDisabledMany(off, ["s1", "s4"]).steps[0].disabled).toBeUndefined();
    const mixed = toggleDisabledMany(toggleDisabled(f, "s1"), ["s1", "s4"]);   // not all off: all go off
    expect([mixed.steps[0].disabled, mixed.steps[2].disabled]).toEqual([true, true]);
    const d = duplicateSteps(f, ["s1", "s4"]);
    expect(d.flow.steps.map((s) => s.id)).toEqual(["s1", "s5", "s2", "s4", "s6"]);
    expect(d.ids).toEqual(["s5", "s6"]);
  });
});
