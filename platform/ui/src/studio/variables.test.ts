import { describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import { compatible, directionOf, holdsType, lastValues, renameRefs, saveVariable, uniqueName, usageCount } from "./variables";

const flow = (): Flow => ({ kind: "TaskBot", id: "a", name: "A",
  variables: [{ name: "total", type: "Number" }, { name: "totals", type: "List" }, { name: "row", type: "Record" }],
  steps: [
    { id: "s1", action: "core/set", args: { variable: "total", value: { expr: "$total + 1" } } },
    { id: "s2", action: "core/loop", args: { over: "$totals", as: "row" }, children: [
      { id: "s3", action: "core/log", args: { message: "Sum ${total} of ${ totals }" }, out: { result: "total" } }] },
    { id: "s4", action: "core/if", elseIf: [{ condition: { op: "gt", left: "$total", right: 5 }, children: [] }] },
    { id: "s5", action: "core/try", children: [], catch: [{ codes: [], as: "row", children: [] }] },
  ] });

describe("variables", () => {
  it("counts every use: settings, ${...}, Else if conditions, outputs and names", () => {
    expect(usageCount(flow(), "total")).toBe(5);   // variable name, $total in the calculation, ${total}, the output, the Else if
    expect(usageCount(flow(), "totals")).toBe(2);  // $totals, ${ totals }; not counted inside $total
    expect(usageCount(flow(), "row")).toBe(2);     // the loop's as, the catch's as
  });

  it("renames everywhere and leaves longer names alone", () => {
    const f = renameRefs(flow(), "total", "sum");
    expect(f.steps[0].args).toEqual({ variable: "sum", value: { expr: "$sum + 1" } });
    expect(f.steps[1].children![0]).toMatchObject({ args: { message: "Sum ${sum} of ${ totals }" }, out: { result: "sum" } });
    expect(f.steps[2].elseIf![0].condition).toMatchObject({ left: "$sum" });
    expect(renameRefs(flow(), "row", "line").steps[3].catch![0].as).toBe("line");
  });

  it("saves a variable: refuses bad and taken names, renames its uses, drops what does not apply", () => {
    expect(() => saveVariable(flow(), { name: "2x", type: "String" })).toThrow(/starts with a letter/);
    expect(() => saveVariable(flow(), { name: "row", type: "String" })).toThrow(/already a variable/);
    const f = saveVariable(flow(), { name: "sum", type: "Number", direction: "local", required: true, kpi: true }, "total");
    expect(f.variables![0]).toEqual({ name: "sum", type: "Number" });
    expect(f.steps[0].args!.variable).toBe("sum");
    const cred = saveVariable(flow(), { name: "pw", type: "Credential", default: "x", direction: "input", required: true });
    expect(cred.variables!.at(-1)).toEqual({ name: "pw", type: "Credential", direction: "input", required: true });
  });

  it("matches types as Classic does", () => {
    expect(compatible("String", "Number")).toBe(true);
    expect(compatible("Number", "String")).toBe(false);
    expect(holdsType("Number", "Number")).toBe(true);
    expect(holdsType("String", "Number")).toBe(false);
    expect(holdsType("Dictionary", "Record")).toBe(true);
    expect(directionOf(true, true)).toBe("in_out");
    expect(directionOf(false, false)).toBeUndefined();
    expect(uniqueName(flow(), "total")).toBe("total_2");
    expect(uniqueName(flow(), "Window title")).toBe("Window_title");
  });

  it("keeps the last value of each variable from a run", () => {
    const last = lastValues([{ seq: 1, type: "variable.set", data: { name: "total", value: 1 } }, { seq: 2, type: "log" },
      { seq: 3, type: "variable.set", data: { name: "total", value: 2 } }]);
    expect([...last]).toEqual([["total", 2]]);
  });
});
