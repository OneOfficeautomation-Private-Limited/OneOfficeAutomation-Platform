import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, PackageManifest } from "../api/client";
import { ConditionEditor } from "./ConditionEditor";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

const PACKAGES: PackageManifest[] = [
  { id: "ooa.file", name: "File", version: "1.0.0", actions: [], conditions: [
    { id: "exists", name: "File exists", inputs: { properties: { path: { type: "string", "x-ooa-type": "File", "x-ooa-label": "File" } }, required: ["path"] } }] },
  { id: "ooa.window", name: "Window", version: "1.0.0", actions: [], conditions: [
    { id: "exists", name: "Window exists", inputs: { properties: { title: { type: "string" }, match: { type: "string", enum: ["exact", "contains"] } } } }] },
];
const FLOW: Flow = { kind: "TaskBot", id: "t", name: "t", variables: [{ name: "path", type: "String" }], steps: [] };

function show(value: unknown, onCommit = vi.fn()) {
  const ctx = { api: {} as VarsCtx["api"], flow: FLOW, builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [], edit: () => {}, newVariable: () => {}, packages: PACKAGES } as VarsCtx;
  render(<VarsContext.Provider value={ctx}><ConditionEditor value={value} onCommit={onCommit} /></VarsContext.Provider>);
  return onCommit;
}

describe("the condition builder (command review C1, C7)", () => {
  afterEach(cleanup);

  it("offers the toolkits' conditions by toolkit and keeps their fields", () => {
    const onCommit = show(undefined);
    const what = screen.getByRole("combobox", { name: "What to check" }) as HTMLSelectElement;
    expect([...what.querySelectorAll("optgroup")].map((g) => g.label)).toEqual(["File", "Window"]);
    fireEvent.change(what, { target: { value: "ooa.file/exists" } });
    expect(onCommit).toHaveBeenLastCalledWith({ condition: "ooa.file/exists" });   // chosen: saved at once, its fields follow
    const file = screen.getByRole("textbox", { name: "File *" });
    fireEvent.change(file, { target: { value: "$path" } });
    fireEvent.blur(file);
    expect(onCommit).toHaveBeenLastCalledWith({ condition: "ooa.file/exists", args: { path: "$path" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Is or is not" }), { target: { value: "not" } });
    expect(onCommit).toHaveBeenLastCalledWith({ not: { condition: "ooa.file/exists", args: { path: "$path" } } });
  });

  it("reads a saved condition back, groups inside groups included", () => {
    show({ all: [{ condition: "ooa.window/exists", args: { title: "Invoice", match: "contains" } },
      { any: [{ op: "gt", left: "$total", right: 1000 }, { not: { condition: "ooa.file/exists", args: { path: "C:/x.pdf" } } }] }] });
    expect((screen.getByRole("combobox", { name: "Combine checks" }) as HTMLSelectElement).value).toBe("all");
    const inner = screen.getByRole("group", { name: "Group 2" });
    expect((within(inner).getByRole("combobox", { name: "Combine checks in group 2" }) as HTMLSelectElement).value).toBe("any");
    expect((within(inner).getAllByRole("combobox", { name: "Is or is not" })[0] as HTMLSelectElement).value).toBe("not");
    expect(screen.getByText(/^Reads: Window exists \(Invoice, contains\) and \$total/)).toBeTruthy();
  });

  it("adds a group of the other kind", () => {
    const onCommit = show({ op: "eq", left: "$a", right: 1 });
    fireEvent.click(screen.getByRole("button", { name: "Add a group" }));
    const inner = screen.getByRole("group", { name: "Group 2" });
    const value = within(inner).getByRole("textbox", { name: "Value" });
    fireEvent.change(value, { target: { value: "$b" } });
    fireEvent.blur(value);
    expect(onCommit).toHaveBeenLastCalledWith({ all: [{ op: "eq", left: "$a", right: 1 }, { op: "eq", left: "$b", right: "" }] });
  });
});
