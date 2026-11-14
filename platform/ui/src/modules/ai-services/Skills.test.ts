import { describe, expect, it } from "vitest";
import { found } from "./Skills";

describe("Prompt Skills: the inputs a prompt uses", () => {
  it("finds $name$, as Studio writes variables, and the older ${name}, each once", () => {
    expect(found("Summarise $text$ for $reader$ in $text$")).toEqual(["text", "reader"]);
    expect(found("Summarise ${text} for ${reader}")).toEqual(["text", "reader"]);
    expect(found("$text$ and ${tone}")).toEqual(["text", "tone"]);
  });

  it("does not take money or JSON for a name", () => {
    expect(found("Costs $5 or $9.99$; answer as {\"total\": 1}")).toEqual([]);
  });
});
