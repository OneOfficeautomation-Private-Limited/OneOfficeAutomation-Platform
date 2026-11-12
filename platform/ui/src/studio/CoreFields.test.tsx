import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, FlowStep, PackageManifest } from "../api/client";
import { StepForm } from "./StepForm";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

const PACKAGES: PackageManifest[] = [{ id: "core", name: "Core", version: "1.0.0", actions: [
  { id: "break", name: "Exit loop", inputs: { properties: { label: { type: "string" } } } },
  { id: "throw", name: "Throw error", inputs: { properties: { code: { type: "string" }, message: { type: "string" } }, required: ["code"] } },
  { id: "delay", name: "Delay", inputs: { properties: { seconds: { type: "number", "x-ooa-type": "Number" }, unit: { type: "string" }, random_max: { type: "number", "x-ooa-type": "Number" } }, required: ["seconds"] } },
  { id: "loop", name: "Loop", inputs: { properties: { iterator: { type: "string" } } } }] }];

function show(step: FlowStep, steps: FlowStep[] = [step]) {
  const flow: Flow = { kind: "TaskBot", id: "t", name: "t", variables: [], steps };
  const ctx = { api: {} as VarsCtx["api"], flow, builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [], edit: () => {}, newVariable: () => {}, packages: PACKAGES } as VarsCtx;
  const onArg = vi.fn();
  render(<VarsContext.Provider value={ctx}><StepForm step={step} packages={PACKAGES} onArg={onArg} onNote={() => {}} /></VarsContext.Provider>);
  return onArg;
}

describe("core commands' fields (command review C5, C6, C9)", () => {
  afterEach(cleanup);

  it("Exit loop picks a loop around it by its name", () => {
    const brk: FlowStep = { id: "b", action: "core/break" };
    const onArg = show(brk, [{ id: "o", action: "core/loop", args: { label: "outer" }, children: [{ id: "i", action: "core/loop", args: { label: "inner" }, children: [brk] }] }]);
    const pick = screen.getByLabelText("Loop to leave") as HTMLSelectElement;
    expect([...pick.options].map((o) => o.text)).toEqual(["The nearest loop", "inner", "outer"]);
    fireEvent.change(pick, { target: { value: "outer" } });
    expect(onArg).toHaveBeenLastCalledWith("label", "outer");
  });

  it("Throw error takes a usual code from a list, or one of your own", () => {
    const onArg = show({ id: "t", action: "core/throw" });
    fireEvent.change(screen.getByLabelText("Error code *"), { target: { value: "VALIDATION.FAILED" } });
    expect(onArg).toHaveBeenLastCalledWith("code", "VALIDATION.FAILED");
    fireEvent.change(screen.getByLabelText("Error code *"), { target: { value: "\u0000own" } });
    const own = screen.getByLabelText("Your own error code");
    fireEvent.change(own, { target: { value: "BUSINESS.INVOICE_DUPLICATE" } });
    fireEvent.blur(own);
    expect(onArg).toHaveBeenLastCalledWith("code", "BUSINESS.INVOICE_DUPLICATE");
  });

  it("Delay is Regular or Random, with From and To for Random", () => {
    const onArg = show({ id: "d", action: "core/delay", args: { seconds: 2 } });
    expect(screen.queryByText(/^To( \*)?$/)).toBeNull();
    fireEvent.click(screen.getByRole("radio", { name: "Random" }));
    expect(onArg).toHaveBeenLastCalledWith("random_max", 7);
    cleanup();
    show({ id: "d", action: "core/delay", args: { seconds: 2, random_max: 7 } });
    expect(screen.getByRole("radio", { name: "Random" }).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByText(/^From/)).toBeTruthy();
    expect(screen.getByText(/^To/)).toBeTruthy();
  });
});
