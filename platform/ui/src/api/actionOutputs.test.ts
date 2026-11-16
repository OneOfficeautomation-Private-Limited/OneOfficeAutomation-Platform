import { describe, expect, it } from "vitest";
import { actionOutputs } from "./client";

// The SDK's manifest (ActionSpec.manifest) lists outputs flat; Studio once read only a wrapped form and so showed no
// "Save results in" for any toolkit action (16 Nov 2026).
describe("actionOutputs", () => {
  it("reads the flat outputs the toolkits' manifest gives", () => {
    const spec = { outputs: { text: { "x-ooa-type": "String", description: "The number as text." } } };
    expect(Object.keys(actionOutputs(spec))).toEqual(["text"]);
  });
  it("keeps an output that is itself called properties", () => {
    const spec = { outputs: { properties: { "x-ooa-type": "Dictionary", description: "The element's properties." } } };
    expect(Object.keys(actionOutputs(spec))).toEqual(["properties"]);
  });
  it("still reads the older wrapped form", () => {
    const spec = { outputs: { properties: { count: { "x-ooa-type": "Number" } } } };
    expect(Object.keys(actionOutputs(spec))).toEqual(["count"]);
  });
  it("gives nothing for an action without outputs", () => {
    expect(actionOutputs({})).toEqual({});
    expect(actionOutputs(undefined)).toEqual({});
  });
});
