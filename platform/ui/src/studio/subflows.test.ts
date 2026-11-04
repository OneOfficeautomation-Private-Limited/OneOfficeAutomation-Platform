import { describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import { addSubflow, labelsInScope, listOf, locateAny, nextId, removeSubflow, renameSubflow, setArg, swapSubflow } from "./flowEdit";
import { renameRefs, usageCount } from "./variables";

const agent = (): Flow => ({ kind: "TaskBot", id: "a", name: "A", variables: [{ name: "n", type: "Number" }],
  steps: [{ id: "s1", action: "core/run_subflow", args: { subflow: "Login" } }, { id: "s2", action: "core/log", args: { message: "$n" } }],
  subflows: [{ name: "Login", steps: [{ id: "s7", action: "core/set", args: { variable: "n", value: 1 } }] }, { name: "Other", steps: [] }] });

describe("subflows (G27)", () => {
  it("shows a subflow as the steps and swaps back to the same agent", () => {
    const f = agent();
    const view = swapSubflow(f, "Login");
    expect(view.steps.map((s) => s.id)).toEqual(["s7"]);
    expect(view.subflows!.map((s) => s.name)).toEqual(["Login", "Other"]);   // names stay, for the Run subflow choices
    expect(swapSubflow(view, "Login")).toEqual(f);
    expect(swapSubflow(f, null)).toBe(f);
    expect(swapSubflow(f, "Nope")).toBe(f);
  });

  it("edits in a subflow's view land in the subflow", () => {
    const view = swapSubflow(agent(), "Login");
    const back = swapSubflow(setArg(view, "s7", "value", 2), "Login");
    expect(back.subflows![0].steps[0].args!.value).toBe(2);
    expect(back.steps.map((s) => s.id)).toEqual(["s1", "s2"]);
  });

  it("new step ids are unique across the main steps and every subflow, also in a view", () => {
    expect(nextId(agent())).toBe("s8");
    expect(nextId(swapSubflow(agent(), "Other"))).toBe("s8");
  });

  it("variable uses and renames cover subflows", () => {
    expect(usageCount(agent(), "n")).toBe(2);
    const r = renameRefs(swapSubflow(agent(), "Login"), "n", "count");
    const back = swapSubflow(r, "Login");
    expect(back.subflows![0].steps[0].args!.variable).toBe("count");
    expect(back.steps[1].args!.message).toBe("$count");
  });

  it("adds, renames (with the Run subflow steps) and removes subflows", () => {
    expect(() => addSubflow(agent(), "Login")).toThrow(/already/);
    expect(() => addSubflow(agent(), "  ")).toThrow();
    expect(addSubflow(agent(), "Close app").subflows!.map((s) => s.name)).toEqual(["Login", "Other", "Close app"]);
    const r = renameSubflow(agent(), "Login", "Sign in");
    expect(r.subflows![0].name).toBe("Sign in");
    expect(r.steps[0].args!.subflow).toBe("Sign in");
    expect(removeSubflow(removeSubflow(agent(), "Login"), "Other").subflows).toBeUndefined();
  });

  it("finds a step and the list it is in", () => {
    expect(listOf(agent(), "s2")).toBeNull();
    expect(listOf(agent(), "s7")).toBe("Login");
    expect(listOf(agent(), "zz")).toBeUndefined();
    expect(locateAny(agent(), "s7")?.step.action).toBe("core/set");
  });

  it("offers the labels a Go to can reach: its list and the lists around it, not inside blocks or across Parallel", () => {
    const steps: Flow["steps"] = [
      { id: "l1", action: "core/label", args: { name: "top" } },
      { id: "b", action: "core/if", children: [
        { id: "l2", action: "core/label", args: { name: "inner" } },
        { id: "g1", action: "core/goto", args: {} }] },
      { id: "g2", action: "core/goto", args: {} },
      { id: "p", action: "core/parallel", children: [{ id: "br", action: "core/step", children: [{ id: "g3", action: "core/goto" }] }] },
    ];
    expect(labelsInScope(steps, "g1")).toEqual(["inner", "top"]);
    expect(labelsInScope(steps, "g2")).toEqual(["top"]);
    expect(labelsInScope(steps, "g3")).toEqual([]);
  });
});
