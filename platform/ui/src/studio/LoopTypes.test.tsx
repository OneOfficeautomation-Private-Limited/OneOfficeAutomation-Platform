import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, FlowStep, PackageManifest } from "../api/client";
import { StepForm } from "./StepForm";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

const PACKAGES: PackageManifest[] = [
  { id: "core", name: "Core", version: "1.0.0", actions: [{ id: "loop", name: "Loop", inputs: { properties: {
    iterator: { type: "string" }, over: { type: "string" }, as: { type: "string", "x-ooa-type": "Variable" }, iterator_args: { type: "object" } } } }] },
  { id: "ooa.file", name: "File", version: "1.0.0", actions: [], iterators: [
    { id: "files_in_folder", name: "Each file in folder", description: "Yield each file path in a folder.", inputs: { properties: {
      folder: { type: "string", "x-ooa-type": "File", "x-ooa-label": "Folder" }, pattern: { type: "string", default: "*", "x-ooa-label": "Pattern" } }, required: ["folder"] } }] },
  { id: "ooa.excel", name: "Excel", version: "1.0.0", actions: [], iterators: [{ id: "rows", name: "Each row in a worksheet", inputs: { properties: {} } }] },
];

function show(step: FlowStep) {
  let flow: Flow = { kind: "TaskBot", id: "t", name: "t", variables: [], steps: [step] };
  const edits: Flow[] = [];
  const ctx = { api: {} as VarsCtx["api"], get flow() { return flow; }, builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [],
    edit: (f: Flow) => { flow = f; edits.push(f); }, newVariable: () => {}, packages: PACKAGES } as VarsCtx;
  const onArg = vi.fn();
  render(<VarsContext.Provider value={ctx}><StepForm step={step} packages={PACKAGES} onArg={onArg} onNote={() => {}} /></VarsContext.Provider>);
  return { onArg, edits };
}

describe("For each offers the toolkits' loop types (command review C2)", () => {
  afterEach(cleanup);

  it("lists them by toolkit after the core kinds, and switching clears the old settings", () => {
    const { edits } = show({ id: "s1", action: "core/loop", args: { iterator: "list", over: "$items", as: "item" } });
    const kind = screen.getByRole("combobox", { name: "Loop type" }) as HTMLSelectElement;
    expect([...kind.querySelectorAll("optgroup")].map((g) => g.label)).toEqual(["Excel", "File"]);
    fireEvent.change(kind, { target: { value: "ooa.file/files_in_folder" } });
    const args = edits[edits.length - 1].steps[0].args!;
    expect(args.iterator).toBe("ooa.file/files_in_folder");
    expect(args.over).toBeUndefined();
  });

  it("shows a loop type's own settings instead of JSON", () => {
    const { onArg } = show({ id: "s1", action: "core/loop", args: { iterator: "ooa.file/files_in_folder", iterator_args: { pattern: "*.pdf" }, as: "file" } });
    expect(screen.getByText("Yield each file path in a folder.")).toBeTruthy();
    expect(screen.queryByText("Iterator settings")).toBeNull();
    const folder = screen.getByRole("textbox", { name: "Folder *" });
    fireEvent.change(folder, { target: { value: "C:/In" } });
    fireEvent.blur(folder);
    expect(onArg).toHaveBeenLastCalledWith("iterator_args", { pattern: "*.pdf", folder: "C:/In" });
  });
});
