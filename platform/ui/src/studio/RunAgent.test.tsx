import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { AgentSummary, Flow, FlowStep } from "../api/client";
import { RunAgentFields } from "./ValueSources";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

const CHILD = { id: "invoice", name: "Invoice", path: "Finance/Invoice.flow.json", variables: [
  { name: "customer", type: "String", direction: "input" }, { name: "amount", type: "Number", direction: "input" },
  { name: "total", type: "Number", direction: "output" }] } as unknown as AgentSummary;

function show(step: FlowStep, variables = [{ name: "customer", type: "String" }]) {
  const state = { flow: { kind: "TaskBot", id: "main", name: "main", variables, steps: [step] } as Flow };
  const ctx = { api: {} as VarsCtx["api"], get flow() { return state.flow; }, builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [CHILD],
    edit: (f: Flow) => { state.flow = f; }, newVariable: () => {} } as VarsCtx;
  render(<VarsContext.Provider value={ctx}><RunAgentFields step={step} /></VarsContext.Provider>);
  return state;
}

describe("Run Agent (command review C4)", () => {
  afterEach(cleanup);

  it("Quick map maps same-name variables and makes the missing ones", () => {
    const state = show({ id: "s1", action: "core/run_bot", args: { automation: "invoice", inputs: { amount: 5 } } });
    fireEvent.click(screen.getByRole("button", { name: "Quick map" }));
    const step = state.flow.steps[0];
    expect(step.args!.inputs).toEqual({ amount: 5, customer: "$customer" });   // a value already set stays
    expect(step.args!.output_map).toEqual({ total: "total" });
    expect(state.flow.variables!.map((v) => v.name + ":" + v.type)).toEqual(["customer:String", "total:Number"]);
  });

  it("takes the agent from a variable", () => {
    const state = show({ id: "s1", action: "core/run_bot", args: {} });
    fireEvent.change(screen.getByLabelText("Agent to run *"), { target: { value: "\u0000variable" } });
    expect(state.flow.steps[0].args!.automation).toBe("$");
  });
});
