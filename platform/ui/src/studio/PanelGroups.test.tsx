import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, FlowStep, PackageManifest } from "../api/client";
import { StepForm } from "./StepForm";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

const PACKAGES: PackageManifest[] = [{ id: "ooa.datatable", name: "Data Table", version: "1", actions: [{ id: "add_column_from_list", name: "Add column from list",
  inputs: { properties: { table: { type: "string" }, name: { type: "string", "x-ooa-label": "Name" }, position: { type: "number", "x-ooa-type": "Number", "x-ooa-label": "Position" },
    header: { type: "boolean", "x-ooa-label": "Header" }, title: { type: "string", "x-ooa-label": "Title" } } },
  "x-ooa-panel": { main: ["table", "name", { choice: "Insert at", options: [{ label: "The end", fields: [] }, { label: "A position", fields: ["position"] }] },
    { tick: "Give it a title", fields: ["title"] }], more: [] } }] }];

function show(step: FlowStep) {
  const state = { flow: { kind: "TaskBot", id: "t", name: "t", steps: [step] } as Flow };
  const ctx = { api: {} as VarsCtx["api"], get flow() { return state.flow; }, builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [],
    edit: (f: Flow) => { state.flow = f; }, newVariable: () => {}, packages: PACKAGES } as VarsCtx;
  render(<VarsContext.Provider value={ctx}><StepForm step={step} packages={PACKAGES} onArg={vi.fn()} onNote={() => {}} /></VarsContext.Provider>);
  return state;
}

describe("choices and tick boxes in a command panel (command review X2)", () => {
  afterEach(cleanup);

  it("shows a choice in the main fields, with only the chosen option's fields", () => {
    show({ id: "s", action: "ooa.datatable/add_column_from_list" });
    expect(screen.getByRole("radiogroup", { name: "Insert at" }).closest("details")).toBeNull();   // in the main fields, not under More options
    expect(screen.getByRole("radio", { name: "The end" }).getAttribute("aria-checked")).toBe("true");
    expect(screen.queryByText(/^Position/)).toBeNull();
    fireEvent.click(screen.getByRole("radio", { name: "A position" }));
    expect(screen.getByText(/^Position/)).toBeTruthy();
  });

  it("picks the option that has a value, and clears the other option's fields on a switch", () => {
    const state = show({ id: "s", action: "ooa.datatable/add_column_from_list", args: { position: 2, title: "x" } });
    expect(screen.getByRole("radio", { name: "A position" }).getAttribute("aria-checked")).toBe("true");
    expect((screen.getByRole("checkbox", { name: "Give it a title" }) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole("radio", { name: "The end" }));
    expect(state.flow.steps[0].args?.position).toBeUndefined();
    fireEvent.click(screen.getByRole("checkbox", { name: "Give it a title" }));
    expect(state.flow.steps[0].args?.title).toBeUndefined();
  });
});
