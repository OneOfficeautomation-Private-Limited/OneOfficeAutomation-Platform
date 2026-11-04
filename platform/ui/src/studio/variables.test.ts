import { describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import {
  binarySummary, compatible, directionOf, guessType, holdsType, isoSpan, lastValues, parseTime, parseTimeSpan, renameRefs, saveVariable, TYPE_STYLE, TYPES,
  uniqueName, usageCount,
} from "./variables";

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

  it("has the Time, TimeSpan, Image and Binary types with readable colours", () => {
    for (const t of ["Time", "TimeSpan", "Image", "Binary"]) {
      expect(TYPES).toContain(t);
      expect(TYPE_STYLE[t].hint).toBeTruthy();
      // white letters on the badge at 4.5:1 or more
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(TYPE_STYLE[t].color.slice(i, i + 2), 16) / 255).map((c) => c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
      expect(1.05 / (0.2126 * r + 0.7152 * g + 0.0722 * b + 0.05)).toBeGreaterThanOrEqual(4.5);
    }
    expect(compatible("String", "Time")).toBe(true);
    expect(compatible("String", "TimeSpan")).toBe(true);
    expect(compatible("File", "Image")).toBe(false);
    expect(compatible("Number", "TimeSpan")).toBe(false);
  });

  it("reads times of day and lengths of time as the runner does", () => {
    expect(parseTime("14:30")).toBe("14:30:00");
    expect(parseTime("9:05:07")).toBe("09:05:07");
    expect(parseTime("2:30 PM")).toBe("14:30:00");
    expect(parseTime("12:00 am")).toBe("00:00:00");
    expect(parseTime("25:00")).toBeUndefined();
    expect(parseTime("13:00 PM")).toBeUndefined();
    expect(parseTimeSpan("2h 15m")).toBe("PT2H15M");
    expect(parseTimeSpan("1d 3h")).toBe("PT27H");
    expect(parseTimeSpan("PT2H15M")).toBe("PT2H15M");
    expect(parseTimeSpan("01:30:00")).toBe("PT1H30M");
    expect(parseTimeSpan("90")).toBe("PT1M30S");
    expect(parseTimeSpan("-30m")).toBe("-PT30M");
    expect(parseTimeSpan("1h, 30 minutes and 5s")).toBe("PT1H30M5S");
    expect(parseTimeSpan("0s")).toBe("PT0S");
    expect(parseTimeSpan("2 parsecs")).toBeUndefined();
    expect(parseTimeSpan("10:75")).toBeUndefined();
    expect(isoSpan(1.5)).toBe("PT1.5S");
  });

  it("shows Image and Binary values by size, never their bytes", () => {
    expect(binarySummary({ $binary: "aGk=", mime: "text/plain" })).toBe("Binary 2 bytes");
    expect(binarySummary({ $binary: "A".repeat(4096) })).toBe("Binary 3.0 KB");
    expect(binarySummary({ $binary: "iVBO", mime: "image/png", width: 640, height: 480 })).toBe("Image 640x480");
    expect(binarySummary({ value: 1 })).toBeUndefined();
    expect(guessType({ $binary: "aGk=" })).toBe("Binary");
    expect(guessType({ $binary: "aGk=", width: 1, height: 1 })).toBe("Image");
  });

  it("keeps the last value of each variable from a run", () => {
    const last = lastValues([{ seq: 1, type: "variable.set", data: { name: "total", value: 1 } }, { seq: 2, type: "log" },
      { seq: 3, type: "variable.set", data: { name: "total", value: 2 } }]);
    expect([...last]).toEqual([["total", 2]]);
  });
});
