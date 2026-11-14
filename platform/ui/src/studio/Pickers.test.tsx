import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ActionManifest, Flow, StudioApi } from "../api/client";
import { AgentInputs, agentFilesBrowse } from "../components";
import { SpecFields } from "./ConditionEditor";
import { PathBrowse, isFolderField } from "./FieldKinds";
import { TriggersPanel } from "./TriggersPanel";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

/**
 * Pickers (14 Nov 2026): wherever a file or folder location is asked for, Browse… sits on the field's right, as in AA,
 * not only on a command's File fields: condition and loop-type fields, the file trigger's folder, a File variable's
 * default, and File inputs when an agent is run or scheduled.
 */
afterEach(() => cleanup());

const fsList = vi.fn(async (path: string) => !path
  ? { places: [{ name: "Home", path: "C:\Users\me" }], entries: [{ name: "Home", path: "C:\Users\me", dir: true }] }
  : { path, parent: "C:\\", places: [{ name: "Home", path: "C:\Users\me" }], entries: [{ name: "Inbox", path: path + "\Inbox", dir: true }, { name: "orders.xlsx", path: path + "\orders.xlsx", dir: false }] });
const api = { fsList, triggers: vi.fn(async () => []) } as unknown as StudioApi;
const FLOW: Flow = { kind: "TaskBot", id: "a", name: "A", steps: [] };
const ctx: VarsCtx = { api, flow: FLOW, builtins: { sys: {}, global: {} }, agents: [], edit: vi.fn(), newVariable: vi.fn() };

const LOOP: ActionManifest = { id: "files_in_folder", name: "Each file in folder", inputs: { properties: {
  folder: { type: "string", "x-ooa-type": "File", "x-ooa-label": "Folder" }, pattern: { type: "string", "x-ooa-type": "String" } }, required: ["folder"] } };
const COND: ActionManifest = { id: "exists", name: "File exists", inputs: { properties: { path: { type: "string", "x-ooa-type": "File", "x-ooa-label": "Path" } } } };

async function pickHome(dialogName: string) {
  const dialog = await screen.findByRole("dialog", { name: dialogName });
  fireEvent.click(await within(dialog).findByRole("option", { name: "Home" }));
  return dialog;
}

describe("Browse… beyond a command's File fields", () => {
  it("a folder field is known by its name or by being a Folder command's", () => {
    expect(isFolderField("ooa.file/files_in_folder", "folder")).toBe(true);
    expect(isFolderField("s1ooa.folder/exists", "path")).toBe(true);
    expect(isFolderField("ooa.folder/create_shortcut", "shortcut")).toBe(false);
    expect(isFolderField("ooa.file/exists", "path")).toBe(false);
    expect(isFolderField("ooa.genai/generate_image", "output_folder")).toBe(true);
  });

  it("a loop type's folder field chooses a folder", async () => {
    const onChange = vi.fn();
    render(<VarsContext.Provider value={ctx}><SpecFields args={{}} spec={LOOP} keyPrefix="s1ooa.file/files_in_folder" onChange={onChange} /></VarsContext.Provider>);
    fireEvent.click(screen.getByRole("button", { name: "Browse…" }));   // one place to browse: it opens straight away
    const dialog = await pickHome("Choose a folder");
    fireEvent.click(await within(dialog).findByRole("button", { name: "Use this folder" }));
    expect(onChange).toHaveBeenCalledWith({ folder: "C:\Users\me" });
  });

  it("a condition's file field chooses a file", async () => {
    const onChange = vi.fn();
    render(<VarsContext.Provider value={ctx}><SpecFields args={{}} spec={COND} keyPrefix="ooa.file/exists" onChange={onChange} /></VarsContext.Provider>);
    fireEvent.click(screen.getByRole("button", { name: "Browse…" }));
    const dialog = await pickHome("Choose a file");
    fireEvent.click(await within(dialog).findByRole("option", { name: "orders.xlsx" }));
    expect(onChange).toHaveBeenCalledWith({ path: "C:\Users\me\orders.xlsx" });
  });

  it("the file trigger's folder is chosen with Browse…", async () => {
    render(<VarsContext.Provider value={ctx}><TriggersPanel api={api} path="a.flow.json" kind="file" /></VarsContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Browse…" }));
    const dialog = await pickHome("Choose a folder");
    fireEvent.click(await within(dialog).findByRole("button", { name: "Use this folder" }));
    expect((screen.getByLabelText("Folder") as HTMLInputElement).value).toBe("C:\Users\me");
  });

  it("PathBrowse works with an api given, outside an agent", async () => {
    const onPick = vi.fn();
    render(<PathBrowse api={api} folder={false} repo={false} start="" onPick={onPick} />);
    fireEvent.click(screen.getByRole("button", { name: "Browse…" }));
    const dialog = await pickHome("Choose a file");
    fireEvent.click(await within(dialog).findByRole("option", { name: "orders.xlsx" }));
    expect(onPick).toHaveBeenCalledWith("C:\Users\me\orders.xlsx", false);
  });
});

describe("File inputs when an agent is run or scheduled", () => {
  const inputs = [{ name: "invoice", type: "File" }, { name: "note", type: "String" }];

  it("OneAgentStudio's Run browses this computer", async () => {
    const onChange = vi.fn();
    render(<AgentInputs inputs={inputs} value={{}} onChange={onChange}
      browse={(i, cur, use) => <PathBrowse api={api} folder={isFolderField("", i.name)} repo={false} start={cur} onPick={(p) => use(p)} />} />);
    expect(screen.getAllByRole("button", { name: "Browse…" })).toHaveLength(1);   // only the File input
    fireEvent.click(screen.getByRole("button", { name: "Browse…" }));
    const dialog = await pickHome("Choose a file");
    fireEvent.click(await within(dialog).findByRole("option", { name: "orders.xlsx" }));
    expect(onChange).toHaveBeenCalledWith({ invoice: "C:\Users\me\orders.xlsx" });
  });

  it("One CommandCenter offers the files the runner gets with the agent, even when there is one", async () => {
    const onChange = vi.fn();
    render(<AgentInputs inputs={inputs} value={{}} onChange={onChange} browse={agentFilesBrowse(["Templates/invoice.xlsx"])} />);
    fireEvent.keyDown(screen.getByRole("button", { name: "Browse for invoice" }), { key: "Enter" });
    fireEvent.click(within(await screen.findByRole("menu")).getByRole("menuitem", { name: "Templates/invoice.xlsx" }));
    expect(onChange).toHaveBeenCalledWith({ invoice: "Templates/invoice.xlsx" });
  });

  it("an agent without files: the path is typed, no Browse…", () => {
    render(<AgentInputs inputs={inputs} value={{}} onChange={() => {}} browse={agentFilesBrowse([])} />);
    expect(screen.queryByRole("button", { name: /Browse/ })).toBeNull();
  });
});
