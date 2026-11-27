import { describe, expect, it } from "vitest";
import { columnKind, columnUnit, parseCellDate, parseDuration, parseNumber, passes } from "./columnFilters";

const NOW = new Date(2026, 10, 27, 15, 30);
const ms = (y: number, m: number, d: number, h = 0, min = 0) => new Date(y, m, d, h, min).getTime();

describe("what a column holds", () => {
  it("reads dates as each language writes them", () => {
    expect(parseCellDate("Nov 1, 8:30 AM", NOW, "en-US")).toBe(ms(2026, 10, 1, 8, 30));
    expect(parseCellDate("Nov 1, 2025, 8:30 PM", NOW, "en-US")).toBe(ms(2025, 10, 1, 20, 30));
    expect(parseCellDate("1 Nov, 20:30", NOW, "en-GB")).toBe(ms(2026, 10, 1, 20, 30));
    expect(parseCellDate("27/11/2026, 14:05:22", NOW, "en-GB")).toBe(new Date(2026, 10, 27, 14, 5, 22).getTime());
    expect(parseCellDate("11/27/2026, 2:05 PM", NOW, "en-US")).toBe(ms(2026, 10, 27, 14, 5));
    expect(parseCellDate("27.11.2026, 14:05", NOW, "de-DE")).toBe(ms(2026, 10, 27, 14, 5));
    expect(parseCellDate("2026-11-27", NOW)).toBe(ms(2026, 10, 27));
    expect(parseCellDate("Thu, 27 Nov 2026", NOW, "en-GB")).toBe(ms(2026, 10, 27));
    for (const not of ["3 min 4 s", "v2", "14:05", "12", "Item 1", "45 %", "1.5"]) expect(parseCellDate(not, NOW, "en-US"), not).toBeNull();
  });

  it("reads durations and numbers with their unit", () => {
    expect(parseDuration("45 s")).toBe(45);
    expect(parseDuration("3 min 4 s")).toBe(184);
    expect(parseDuration("1 h 2 min")).toBe(3720);
    expect(parseDuration("2 days")).toBe(172800);
    expect(parseDuration("350 ms")).toBeCloseTo(0.35);
    expect(parseDuration("Failed")).toBeNull();
    expect(parseDuration("3 apples")).toBeNull();
    expect(parseNumber("1,234", "en-US")).toEqual({ n: 1234, unit: "" });
    expect(parseNumber("1.234,5", "de-DE")).toEqual({ n: 1234.5, unit: "" });
    expect(parseNumber("12.5 %", "en-US")).toEqual({ n: 12.5, unit: "%" });
    expect(parseNumber("3 KB")).toEqual({ n: 3, unit: "KB" });
    expect(parseNumber("v2")).toBeNull();
  });

  it("chooses the funnel: dates, durations, numbers, a few values, or text", () => {
    expect(columnKind(["Nov 1, 8:30 AM", "Nov 2, 9:00 AM", ""], NOW, "en-US")).toBe("date");
    expect(columnKind(["45 s", "3 min 4 s", "1 h 2 min"], NOW)).toBe("duration");
    expect(columnKind(["12", "7", "1,024"], NOW, "en-US")).toBe("number");
    expect(columnKind(["12 %", "40 %"], NOW)).toBe("number");
    expect(columnKind(["12 %", "40 KB"], NOW)).toBe("values");   // two units: not one number column
    expect(columnKind(["Failed", "Completed", "Failed"], NOW)).toBe("values");
    expect(columnKind(Array.from({ length: 16 }, (_, i) => "Error " + String.fromCharCode(65 + i)), NOW)).toBe("text");
    expect(columnUnit("duration", [])).toBe("min");
    expect(columnUnit("number", ["", "12 %"])).toBe("%");
  });

  it("tests a cell against each kind of filter", () => {
    expect(passes({ kind: "values", allowed: new Set(["Failed"]) }, "Failed")).toBe(true);
    expect(passes({ kind: "text", op: "contains", text: "time" }, "Timeout at step 3")).toBe(true);
    expect(passes({ kind: "text", op: "starts", text: "out" }, "Timeout")).toBe(false);
    expect(passes({ kind: "text", op: "is", text: "timeout" }, "Timeout")).toBe(true);
    expect(passes({ kind: "range", type: "duration", from: 60, to: 300 }, "3 min 4 s")).toBe(true);
    expect(passes({ kind: "range", type: "duration", from: null, to: 60 }, "3 min 4 s")).toBe(false);
    expect(passes({ kind: "range", type: "number", from: 10, to: 10 }, "10")).toBe(true);   // To includes its number
    expect(passes({ kind: "range", type: "date", from: ms(2026, 10, 1), to: ms(2026, 10, 2) }, "2026-11-01", NOW)).toBe(true);
    expect(passes({ kind: "range", type: "date", from: ms(2026, 10, 1), to: ms(2026, 10, 2) }, "2026-11-02", NOW)).toBe(false);   // To is the first day not included
    expect(passes({ kind: "range", type: "number", from: 1, to: null }, "")).toBe(false);   // an empty cell is not in a range
  });
});
