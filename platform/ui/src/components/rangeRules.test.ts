import { describe, expect, it } from "vitest";
import { firstWeekday, localTime, monthWeeks, rangeDays, rangeFrom, rangeLabel, rangeParams, rangeWords, resolveRange } from "./rangeRules";

// Thursday 27 Nov 2026, 15:30 in the test's own time zone
const NOW = new Date(2026, 10, 27, 15, 30);
const at = (y: number, m: number, d: number, h = 0, min = 0) => new Date(y, m, d, h, min);

describe("the date filter's choices", () => {
  it("looking back: each quick choice covers the right days, up to now", () => {
    expect(resolveRange({ key: "today" }, NOW)).toEqual({ from: at(2026, 10, 27), to: at(2026, 10, 28) });
    expect(resolveRange({ key: "yesterday" }, NOW)).toEqual({ from: at(2026, 10, 26), to: at(2026, 10, 27) });
    expect(resolveRange({ key: "7d" }, NOW)).toEqual({ from: at(2026, 10, 21), to: NOW });   // today and the six days before
    expect(resolveRange({ key: "30d" }, NOW).from).toEqual(at(2026, 9, 29));
    expect(resolveRange({ key: "90d" }, NOW).from).toEqual(at(2026, 7, 30));
    expect(resolveRange({ key: "24h" }, NOW)).toEqual({ from: new Date(NOW.getTime() - 86_400_000), to: NOW });
    expect(resolveRange({ key: "month" }, NOW)).toEqual({ from: at(2026, 10, 1), to: NOW });
    expect(resolveRange({ key: "last-month" }, NOW)).toEqual({ from: at(2026, 9, 1), to: at(2026, 10, 1) });
    expect(resolveRange({ key: "year" }, NOW).from).toEqual(at(2026, 0, 1));
    expect(resolveRange({ key: "any" }, NOW)).toEqual({ from: null, to: null });
  });

  it("month and year edges: last month in January is December; 7 days across a month start", () => {
    const jan3 = new Date(2027, 0, 3, 9);
    expect(resolveRange({ key: "last-month" }, jan3)).toEqual({ from: at(2026, 11, 1), to: at(2027, 0, 1) });
    expect(resolveRange({ key: "7d" }, jan3).from).toEqual(at(2026, 11, 28));
    expect(resolveRange({ key: "yesterday" }, new Date(2026, 2, 1, 8))).toEqual({ from: at(2026, 1, 28), to: at(2026, 2, 1) });   // 28 Feb
    expect(resolveRange({ key: "month" }, new Date(2028, 1, 29, 8)).from).toEqual(at(2028, 1, 1));   // a leap day
  });

  it("looking ahead: tomorrow, the next 7 and 30 days", () => {
    expect(resolveRange({ key: "tomorrow" }, NOW)).toEqual({ from: at(2026, 10, 28), to: at(2026, 10, 29) });
    expect(resolveRange({ key: "next7" }, NOW)).toEqual({ from: NOW, to: at(2026, 11, 4) });
    expect(resolveRange({ key: "next30" }, NOW).to).toEqual(at(2026, 11, 27));
  });

  it("custom: a date includes its whole day; a time is exact; one end may be open", () => {
    expect(resolveRange({ key: "custom", from: "2026-11-02", to: "2026-11-05" }, NOW)).toEqual({ from: at(2026, 10, 2), to: at(2026, 10, 6) });
    expect(resolveRange({ key: "custom", from: "2026-11-02T09:30", to: "2026-11-02T17:00" }, NOW)).toEqual({ from: at(2026, 10, 2, 9, 30), to: at(2026, 10, 2, 17, 0) });
    expect(resolveRange({ key: "custom", from: "2026-11-02" }, NOW)).toEqual({ from: at(2026, 10, 2), to: null });
    expect(localTime("2026-02-30")).toBeNull();
    expect(rangeParams({ key: "custom", from: "2026-11-02", to: "2026-11-02" }, NOW)).toEqual({ from: at(2026, 10, 2).toISOString(), to: at(2026, 10, 3).toISOString() });
    expect(rangeParams({ key: "any" }, NOW)).toEqual({});
  });

  it("words on the button and in sentences, in the browser's language", () => {
    expect(rangeLabel({ key: "7d" }, NOW, "en-GB")).toBe("Last 7 days · 21 Nov – 27 Nov");
    expect(rangeLabel({ key: "7d" }, NOW, "en-US")).toBe("Last 7 days · Nov 21 – Nov 27");
    expect(rangeLabel({ key: "7d" }, NOW, "de-DE")).toBe("Last 7 days · 21. Nov. – 27. Nov.");
    expect(rangeLabel({ key: "today" }, NOW, "en-GB")).toBe("Today · 27 Nov");
    expect(rangeLabel({ key: "last-month" }, NOW, "en-GB")).toBe("Last month · 1 Oct – 31 Oct");
    expect(rangeLabel({ key: "any" }, NOW, "en-GB")).toBe("Any time");
    expect(rangeLabel({ key: "custom", from: "2025-12-30", to: "2026-01-02" }, NOW, "en-GB")).toBe("30 Dec 2025 – 2 Jan");
    expect(rangeWords({ key: "30d" }, NOW)).toBe("in the last 30 days");
    expect(rangeWords({ key: "custom", from: "2026-11-02", to: "2026-11-05" }, NOW, "en-GB")).toBe("between 2 Nov and 5 Nov");
    expect(rangeDays({ key: "30d" }, NOW)).toBe(30);
  });

  it("older saved choices and addresses still work", () => {
    expect(rangeFrom("1")).toEqual({ key: "24h" });
    expect(rangeFrom("30")).toEqual({ key: "30d" });
    expect(rangeFrom("all")).toEqual({ key: "any" });
    expect(rangeFrom("dates", "2026-11-01", "2026-11-03")).toEqual({ key: "custom", from: "2026-11-01", to: "2026-11-03" });
    expect(rangeFrom("nonsense", null, null, { key: "90d" })).toEqual({ key: "90d" });
  });

  it("calendar weeks start on the browser's first weekday", () => {
    const mon = monthWeeks(2026, 10, 1);   // November 2026 starts on a Sunday
    expect(mon[0][0]).toEqual(at(2026, 9, 26));
    expect(mon[0][6]).toEqual(at(2026, 10, 1));
    const sun = monthWeeks(2026, 10, 7);
    expect(sun[0][0]).toEqual(at(2026, 10, 1));
    expect(firstWeekday("en-GB")).toBe(1);
    expect([1, 7]).toContain(firstWeekday("en-US"));   // Sunday where the browser knows it
  });
});
