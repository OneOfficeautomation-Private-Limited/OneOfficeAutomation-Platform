import { describe, expect, it } from "vitest";
import { assignmentText, assignmentValue } from "./ValueSources";

describe("Assign several variables", () => {
  it("shows and reads a row's value: =expr is a calculation, the rest as for Assign", () => {
    expect(assignmentValue("=$price * $qty")).toEqual({ expr: "$price * $qty" });
    expect(assignmentText({ expr: "$price * $qty" })).toBe("=$price * $qty");
    expect(assignmentValue("42")).toBe(42);
    expect(assignmentValue("Invoice ${n}")).toBe("Invoice ${n}");
    expect(assignmentValue("$other")).toBe("$other");
    expect(assignmentValue("")).toBeUndefined();
    expect(assignmentValue("=")).toBe("=");   // a lone = is text
  });
});
