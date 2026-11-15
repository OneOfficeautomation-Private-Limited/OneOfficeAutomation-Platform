import { describe, expect, it } from "vitest";
import { checkText } from "./DocTypes";

/** DA-1: a field's check reads in words; the pattern itself is only under "Show advanced settings". */
describe("checkText", () => {
  it("names the built-in types' checks", () => {
    expect(checkText({ regex: "SA\\d{2}[A-Z0-9]{18}", type: "Text" })).toBe("Saudi IBAN");
    expect(checkText({ regex: "3\\d{14}", type: "Text" })).toBe("Saudi VAT number (15 digits, starts with 3)");
    expect(checkText({ regex: "^\\d+$", type: "Text" })).toBe("Digits only");
  });

  it("says what the kind checks when there is no pattern, and points to Advanced for an own pattern", () => {
    expect(checkText({ regex: "", type: "Date" })).toBe("A date");
    expect(checkText({ regex: "", type: "Amount" })).toBe("An amount");
    expect(checkText({ regex: "", type: "Text" })).toBe("None");
    expect(checkText({ regex: "^X-\\d{3}$", type: "Text" })).toBe("Own pattern (see Advanced)");
  });
});
