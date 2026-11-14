import { describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import { isRef, refName, renameRefs, upgradeRefs, varRef } from "./variables";

describe("variables written $name$ (14 Nov 2026, item F, as AA)", () => {
  it("writes and reads every form", () => {
    expect(varRef("total")).toBe("$total$");
    expect(varRef("sys.date")).toBe("$sys.date$");
    for (const r of ["$total$", "$total", "${total}", " $total$ "]) { expect(isRef(r)).toBe(true); expect(refName(r)).toBe("total"); }
    expect(refName("$order.lines[1]$")).toBe("order.lines[1]");
    expect(isRef("Total $count$")).toBe(false);
    expect(refName("plain")).toBe("");
  });

  it("rewrites an agent's references as $name$ when it is saved, leaving text, notes and literals as they are", () => {
    const flow: Flow = { kind: "TaskBot", id: "a", name: "A", variables: [{ name: "count", type: "Number" }], steps: [
      { id: "s1", action: "core/set", args: { variable: "count", value: "$count" }, note: "uses $count" },
      { id: "s2", action: "core/log", args: { message: "Total $count", other: "${count}", calc: { expr: "$count + ${count} * 2" }, keep: { literal: "$count" } } },
      { id: "s3", action: "core/if", args: { condition: { op: "gt", left: "$count", right: 1 } }, children: [{ id: "s4", action: "core/log", args: { message: "$count$" } }] },
    ], subflows: [{ name: "Tidy", steps: [{ id: "t1", action: "core/log", args: { message: "$sys.date" } }] }] };
    const g = upgradeRefs(flow);
    expect(g.steps[0]).toEqual({ id: "s1", action: "core/set", args: { variable: "count", value: "$count$" }, note: "uses $count" });
    expect(g.steps[1].args).toEqual({ message: "Total $count", other: "$count$", calc: { expr: "$count$ + $count$ * 2" }, keep: { literal: "$count" } });
    expect(g.steps[2].args).toEqual({ condition: { op: "gt", left: "$count$", right: 1 } });
    expect(g.steps[2].children![0].args).toEqual({ message: "$count$" });
    expect(g.subflows![0].steps[0].args).toEqual({ message: "$sys.date$" });
    expect(upgradeRefs(g)).toEqual(g);   // saving again changes nothing
  });

  it("renames a variable in either form, keeping the form", () => {
    const flow: Flow = { kind: "TaskBot", id: "a", name: "A", variables: [{ name: "n", type: "Number" }],
      steps: [{ id: "s1", action: "core/log", args: { a: "$n$", b: "$n", c: "Total $n$ and ${n}" } }] };
    expect(renameRefs(flow, "n", "rows").steps[0].args).toEqual({ a: "$rows$", b: "$rows", c: "Total $rows$ and ${rows}" });
  });
});
