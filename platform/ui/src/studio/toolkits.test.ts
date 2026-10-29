import { describe, expect, it } from "vitest";
import { conditionText, describeAction, plainValue, stepRows, summarize, toolkitMark } from "./toolkits";

const packages = [{ id: "ooa.excel", name: "Excel", version: "1.0.0", actions: [{ id: "open", name: "Open workbook" }] }];

describe("steps view", () => {
  it("numbers steps in the order they run and heads each block part", () => {
    const rows = stepRows([
      { id: "a", action: "ooa.excel/open" },
      { id: "t", action: "core/try", children: [{ id: "b", action: "core/log" }], catch: [{ children: [{ id: "c", action: "core/log" }] }], finally: [{ id: "d", action: "core/log" }] },
      { id: "i", action: "core/if", children: [{ id: "e", action: "core/log" }], else: [{ id: "f", action: "core/log" }] },
    ]);
    expect(rows.map((r) => r.kind === "step" ? `${r.n}:${r.step.id}@${r.depth}` : `[${r.label}]@${r.depth}`)).toEqual([
      "1:a@0", "2:t@0", "3:b@1", "[Catch (on error)]@1", "4:c@1", "[Finally (always)]@1", "5:d@1", "6:i@0", "7:e@1", "[Else]@1", "8:f@1",
    ]);
  });

  it("names actions in plain words and marks them by toolkit", () => {
    expect(describeAction("ooa.excel/open", packages)).toMatchObject({ name: "Open workbook", toolkit: "Excel", mark: { icon: "grid" } });
    expect(describeAction("core/if", packages)).toMatchObject({ name: "If", toolkit: "Flow" });
    expect(describeAction("ooa.unknown/do_thing", packages).name).toBe("do thing");
    expect(toolkitMark("ooa.outlook").icon).toBe("mail");
    expect(toolkitMark("ooa.genai").icon).toBe("spark");
    expect(toolkitMark("ooa.datatable")).toEqual({ color: "#1e40af", icon: "code" });
    expect(toolkitMark("ooa.something_new").color).toBe("#334155");
  });

  it("summarises a step's settings and outputs", () => {
    expect(summarize({ id: "x", action: "ooa.excel/open", args: { path: "$workbook", sheet: "" }, out: { table: "invoices" } })).toBe("path: $workbook · → $invoices");
    expect(summarize({ id: "x", action: "core/log", note: "Say hello" })).toBe("Say hello");
  });

  it("writes conditions and values in plain words, as Classic Studio does", () => {
    expect(conditionText({ op: "empty", left: "$order" })).toBe("$order is empty");
    expect(conditionText({ all: [{ op: "le", left: "$days", right: 30 }, { op: "eq", left: "$status", right: "open" }] }))
      .toBe("$days is less than or equal to 30 and $status equals “open”");
    expect(conditionText({ op: "eq", left: "$ok", right: true })).toBe("$ok is true");
    expect(plainValue({ expr: "toString(round($total, 2))" })).toBe("round($total, 2)");
    expect(plainValue({ source: "credential", name: "sap/password" })).toBe("credential sap/password");
    expect(summarize({ id: "i", action: "core/if", args: { condition: { op: "gt", left: "$days", right: "$policy.window_days" } } }))
      .toBe("$days is greater than $policy.window_days");
  });
});
