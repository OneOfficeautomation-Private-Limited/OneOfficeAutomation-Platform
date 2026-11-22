import { describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import { bapiConnect, mergeRequired } from "./Pickers";

/** G6: the BAPI browser signs in like the agent's Connect step and fills in what a function needs. */
describe("BAPI browser", () => {
  const flow = (steps: Flow["steps"]): Flow => ({ kind: "TaskBot", id: "a", name: "a", variables: [], steps });

  it("takes the Connect step's sign-in but opens its own session", () => {
    const f = flow([{ id: "1", action: "ooa.sap_bapi/connect", args: { host: "sap1", client: "100", session: "erp" } }]);
    expect(bapiConnect(f)).toEqual({ host: "sap1", client: "100" });
    expect(bapiConnect(flow([]))).toBeNull();
  });

  it("adds the needed values and keeps what the step already has", () => {
    expect(mergeRequired({ ORDER: "42" }, { ORDER: "", PLANT: "" })).toEqual({ ORDER: "42", PLANT: "" });
    expect(mergeRequired(undefined, { PLANT: "" })).toEqual({ PLANT: "" });
    expect(mergeRequired("$imports$", { PLANT: "" })).toBeUndefined();
    expect(mergeRequired(undefined, {})).toBeUndefined();
  });
});

describe("model pick list (G6)", () => {
  it("lists the provider's known models, none for a variable provider", async () => {
    const { modelsFor } = await import("./Pickers");
    expect(modelsFor({ id: "1", action: "ooa.genai/prompt", args: {} })).toContain("gpt-4o-mini");
    expect(modelsFor({ id: "1", action: "ooa.genai/prompt", args: { provider: "anthropic" } })).toContain("claude-sonnet-5-5");
    expect(modelsFor({ id: "1", action: "ooa.genai/prompt", args: { provider: "$p$" } })).toEqual([]);
  });
});
