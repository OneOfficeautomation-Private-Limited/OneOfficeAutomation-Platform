import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { AgentSummary, Flow, FlowStep } from "../api/client";
import { RunAgentFields } from "./ValueSources";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

const CHILD = { id: "invoice", name: "Invoice", path: "Finance/Invoice.flow.json", variables: [
  { name: "customer", type: "String", direction: "input" }, { name: "amount", type: "Number", direction: "input" },
  { name: "total", type: "Number", direction: "output" }] } as unknown as AgentSummary;
const OTHER = { id: "welcome", name: "Welcome mail", path: "HR/Onboarding/Welcome mail.flow.json", variables: [] } as unknown as AgentSummary;

function show(step: FlowStep, variables = [{ name: "customer", type: "String" }]) {
  const state = { flow: { kind: "TaskBot", id: "main", name: "main", variables, steps: [step] } as Flow };
  const ctx = { api: {} as VarsCtx["api"], get flow() { return state.flow; }, builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [CHILD, OTHER],
    edit: (f: Flow) => { state.flow = f; }, newVariable: () => {} } as VarsCtx;
  const view = render(<VarsContext.Provider value={ctx}><RunAgentFields step={state.flow.steps[0]} /></VarsContext.Provider>);
  const again = () => view.rerender(<VarsContext.Provider value={ctx}><RunAgentFields step={state.flow.steps[0]} /></VarsContext.Provider>);
  return { state, again };
}

describe("Run Agent (14 Nov 2026, items D and E, as AA's Task Bot Run)", () => {
  afterEach(cleanup);

  it("shows the chosen agent by folder and name, never its file name", () => {
    show({ id: "s1", action: "core/run_bot", args: { automation: "invoice" } });
    expect(screen.getByLabelText("Chosen agent").textContent).toBe("Agents › Finance › Invoice");
    expect(document.body.textContent).not.toContain(".flow.json");
  });

  it("finds the agent by folder (Browse) or in every folder (Search), and takes it with Choose", () => {
    const { state } = show({ id: "s1", action: "core/run_bot", args: {} });
    fireEvent.click(screen.getByRole("radio", { name: "Agent in the workspace" }));
    const finder = screen.getByRole("dialog", { name: "Find an agent" });
    // Browse: the top folder lists nothing; open HR › Onboarding
    expect(within(finder).getByText("No agents in this folder.")).toBeTruthy();
    fireEvent.click(within(finder).getByText("HR"));
    fireEvent.click(within(finder).getByText("Onboarding"));
    expect(within(finder).getAllByRole("row").slice(1).map((r) => r.textContent)).toEqual(["Welcome mail00"]);
    // Search: every folder, with the folder shown
    fireEvent.click(within(finder).getByRole("radio", { name: "Search" }));
    fireEvent.change(within(finder).getByLabelText("Find an agent by name"), { target: { value: "inv" } });
    const rows = within(finder).getAllByRole("row").slice(1);
    expect(rows.map((r) => r.textContent)).toEqual(["InvoiceAgents › Finance21"]);
    expect(finder.textContent).not.toContain(".flow.json");
    fireEvent.click(rows[0]);
    fireEvent.click(within(finder).getByRole("button", { name: "Choose" }));
    expect(state.flow.steps[0].args!.automation).toBe("invoice");
  });

  it("Quick map shows what it will do, then maps same-name variables and makes the missing ones", () => {
    const { state, again } = show({ id: "s1", action: "core/run_bot", args: { automation: "invoice", inputs: { amount: 5 } } });
    fireEvent.click(screen.getByRole("button", { name: "Quick map" }));
    const dialog = screen.getByRole("dialog", { name: "Quick map" });
    const group = (name: string) => within(within(dialog).getByRole("group", { name })).getAllByRole("listitem").map((r) => r.textContent);
    expect(group("Ready to map")).toEqual([expect.stringMatching(/customerinput$/)]);
    expect(group("New variable needed")).toEqual([expect.stringMatching(/totaloutput$/)]);
    expect(group("Already set")).toEqual([expect.stringMatching(/amountinput ← 5$/)]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Apply" }));
    const step = state.flow.steps[0];
    expect(step.args!.inputs).toEqual({ amount: 5, customer: "$customer$" });   // a value already set stays
    expect(step.args!.output_map).toEqual({ total: "total" });
    expect(state.flow.variables!.map((v) => v.name + ":" + v.type)).toEqual(["customer:String", "total:Number"]);
    expect(screen.getByRole("status").textContent).toBe("2 mapped, 1 new variable");
    again();
    expect((screen.getByLabelText("Input customer") as HTMLInputElement).value).toBe("$customer$");
    expect((screen.getByLabelText("Give a value for customer") as HTMLInputElement).checked).toBe(true);
  });

  it("Quick map replaces values already set only when asked", () => {
    const { state } = show({ id: "s1", action: "core/run_bot", args: { automation: "invoice", inputs: { customer: "Acme" } } });
    fireEvent.click(screen.getByRole("button", { name: "Quick map" }));
    const dialog = screen.getByRole("dialog", { name: "Quick map" });
    fireEvent.click(within(dialog).getByLabelText("Also replace values already set"));
    fireEvent.click(within(dialog).getByLabelText("Make the missing variables, with the same name and type"));
    fireEvent.click(within(dialog).getByRole("button", { name: "Apply" }));
    expect(state.flow.steps[0].args!.inputs).toEqual({ customer: "$customer$" });
    expect(state.flow.variables!.map((v) => v.name)).toEqual(["customer"]);   // nothing made
  });

  it("takes the agent from a variable, or runs this agent again", () => {
    const { state } = show({ id: "s1", action: "core/run_bot", args: {} });
    fireEvent.click(screen.getByRole("radio", { name: "Variable" }));
    expect(state.flow.steps[0].args!.automation).toBe("$");
    fireEvent.click(screen.getByRole("radio", { name: "This agent" }));
    expect(state.flow.steps[0].args!.automation).toBe("main");
  });
});
