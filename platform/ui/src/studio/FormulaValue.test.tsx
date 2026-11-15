import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, FlowStep, PackageManifest } from "../api/client";
import { StepForm } from "./StepForm";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

// Calculate as the toolkit's manifest gives it: outputs flat, a choice between an operation and a formula
const PACKAGES: PackageManifest[] = [{ id: "ooa.number", name: "Number", version: "1.1.0", actions: [
  { id: "calculate", name: "Calculate", description: "Work out a number.",
    inputs: { properties: { operation: { type: "string", enum: ["add", "divide"] }, a: { type: "number", "x-ooa-type": "Number" }, b: { type: "number", "x-ooa-type": "Number" },
      values: { "x-ooa-type": "List" }, formula: { "x-ooa-type": "Any", description: "A calculation with variables." }, decimals: { type: "number", "x-ooa-type": "Number" } } },
    outputs: { result: { "x-ooa-type": "Number", description: "The result." } },
    "x-ooa-panel": { main: [{ choice: "How", options: [{ label: "Pick an operation", fields: ["operation", "a", "b", "values"] }, { label: "Write a formula", fields: ["formula"] }] }, "decimals"] } }] }];

function show(step: FlowStep, preview = vi.fn()) {
  const flow: Flow = { kind: "TaskBot", id: "t", name: "t", steps: [step],
    variables: [{ name: "price", type: "Number", default: 12.5 }, { name: "qty", type: "Number", default: 3 }, { name: "total", type: "Number" }] };
  const edit = vi.fn();
  const ctx = { api: { formulaPreview: preview } as unknown as VarsCtx["api"], flow, builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [],
    edit, newVariable: () => {}, packages: PACKAGES } as VarsCtx;
  render(<VarsContext.Provider value={ctx}><StepForm step={step} packages={PACKAGES} onArg={() => {}} onNote={() => {}} /></VarsContext.Provider>);
  return { edit, preview };
}

describe("Calculate: a formula with its result while typing (16 Nov 2026)", () => {
  afterEach(cleanup);

  it("shows the formula with its result for the variables' default values", async () => {
    const preview = vi.fn().mockResolvedValue({ text: "37.5", type: "Number", missing: [] });
    show({ id: "c", action: "ooa.number/calculate", args: { formula: { expr: "$price$ * $qty$" } } }, preview);
    expect((screen.getByLabelText("Formula") as HTMLInputElement).value).toBe("$price$ * $qty$");
    await waitFor(() => expect(screen.getByText("37.5")).toBeTruthy());
    expect(preview).toHaveBeenCalledWith("$price$ * $qty$", { price: 12.5, qty: 3 });
  });

  it("saves what is typed as a formula, not as text", () => {
    const { edit } = show({ id: "c", action: "ooa.number/calculate", args: { formula: { expr: "1" } } }, vi.fn().mockResolvedValue({ missing: [] }));
    const box = screen.getByLabelText("Formula");
    fireEvent.change(box, { target: { value: "= round($price$ * 1.18, 2)" } });
    fireEvent.blur(box);
    const saved = edit.mock.calls.at(-1)?.[0] as Flow;
    expect(saved.steps[0].args?.formula).toEqual({ expr: "round($price$ * 1.18, 2)" });
  });

  it("says which variables need a default before it can show a result, and shows a formula's mistake", async () => {
    show({ id: "c", action: "ooa.number/calculate", args: { formula: { expr: "$total$ * 2" } } }, vi.fn().mockResolvedValue({ missing: ["total"] }));
    await waitFor(() => expect(screen.getByText(/Give \$total\$ a default value/)).toBeTruthy());
    cleanup();
    show({ id: "c", action: "ooa.number/calculate", args: { formula: { expr: "(1 +" } } }, vi.fn().mockResolvedValue({ missing: [], error: { code: "EXPR.INVALID", message: "Unexpected end of the formula" } }));
    await waitFor(() => expect(screen.getByText("Unexpected end of the formula")).toBeTruthy());
  });

  it("keeps the result: Save results in shows the Result output", () => {
    show({ id: "c", action: "ooa.number/calculate", args: { operation: "add", a: 1, b: 2 } }, vi.fn().mockResolvedValue({ missing: [] }));
    expect(screen.getByText("Save results in")).toBeTruthy();
    expect(screen.getByLabelText("Keep Result in")).toBeTruthy();
  });
});
