import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import { FileField } from "./FieldKinds";
import { setArg } from "./flowEdit";
import { AgentRepoFiles, withoutRepoFile, withRepoFile } from "./RepoFiles";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

const files = [{ path: "Templates/invoice.xlsx", size: 20480 }, { path: "Mappings/vendors.csv", size: 512 }];

function ctxFor(state: { flow: Flow }) {
  return { api: { crFiles: async () => files } as unknown as VarsCtx["api"], get flow() { return state.flow; }, builtins: { variables: [] } as unknown as VarsCtx["builtins"],
    agents: [], edit: (f: Flow) => { state.flow = f; }, newVariable: () => {} } as VarsCtx;
}

describe("files kept in One CommandCenter (command review F1)", () => {
  afterEach(cleanup);

  it("keeps the agent's list of files tidy", () => {
    const f: Flow = { kind: "TaskBot", id: "t", name: "t", steps: [] };
    const g = withRepoFile(withRepoFile(withRepoFile(f, "b.csv"), "a.xlsx"), "b.csv");
    expect(g.files).toEqual(["a.xlsx", "b.csv"]);
    expect(withoutRepoFile(withoutRepoFile(g, "a.xlsx"), "b.csv").files).toBeUndefined();
  });

  it("a file field picks a file from One CommandCenter and adds it to the agent's files", async () => {
    const state = { flow: { kind: "TaskBot", id: "t", name: "t", steps: [{ id: "s1", action: "ooa.excel/open" }] } as Flow };
    render(<VarsContext.Provider value={ctxFor(state)}>
      <FileField id="p" name="path" value={undefined} fallback="" folder={false} onCommit={() => {}} apply={(t, f) => setArg(f, "s1", "path", t)} />
    </VarsContext.Provider>);
    fireEvent.keyDown(screen.getByRole("button", { name: "Browse…" }), { key: "Enter" });
    fireEvent.click(within(await screen.findByRole("menu")).getByRole("menuitem", { name: /^One CommandCenter/ }));
    fireEvent.click(await screen.findByRole("option", { name: /Templates\/invoice\.xlsx/ }));
    expect(state.flow.steps[0].args).toEqual({ path: "Templates/invoice.xlsx" });
    expect(state.flow.files).toEqual(["Templates/invoice.xlsx"]);
  });

  it("the agent's Details list the files, with Remove and Add", async () => {
    const state = { flow: { kind: "TaskBot", id: "t", name: "t", steps: [], files: ["Templates/invoice.xlsx"] } as Flow };
    const ctx = ctxFor(state);
    render(<VarsContext.Provider value={ctx}><AgentRepoFiles flow={state.flow} onEdit={(f) => { state.flow = f; }} /></VarsContext.Provider>);
    fireEvent.click(screen.getByRole("button", { name: "Add a file" }));
    fireEvent.click(await screen.findByRole("option", { name: /vendors\.csv/ }));
    await waitFor(() => expect(state.flow.files).toEqual(["Mappings/vendors.csv", "Templates/invoice.xlsx"]));
  });
});
