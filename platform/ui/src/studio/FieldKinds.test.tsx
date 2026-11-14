import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, FlowStep, PackageManifest, StudioApi } from "../api/client";
import { StepForm } from "./StepForm";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

afterEach(() => cleanup());

/** One command with a field of every kind the input-help check covers (docs/research/input-help-check.md). */
const PKG: PackageManifest = { id: "ooa.test", name: "Test", version: "1", actions: [{ id: "all_kinds", name: "All kinds", inputs: { properties: {
  text: { type: "string", "x-ooa-type": "String", "x-ooa-label": "Text" },
  flag: { type: "boolean", "x-ooa-type": "Boolean", "x-ooa-label": "Flag" },
  due: { type: "string", "x-ooa-type": "DateTime" },
  items: { type: "array", "x-ooa-type": "List" },
  headers: { type: "object", "x-ooa-type": "Dictionary" },
  source: { type: "string", "x-ooa-type": "File" },
  folder: { type: "string", "x-ooa-type": "File" },
  password: { type: "string", "x-ooa-type": "Credential" },
  window: { type: "string", "x-ooa-type": "String", "x-ooa-label": "Window" },
} }, "x-ooa-panel": { main: ["text", "flag", "due", "items", "headers", "source", "folder", "password", "window"] } }] };

const FLOW: Flow = { kind: "TaskBot", id: "a", name: "A", steps: [],
  variables: [{ name: "count", type: "Number" }, { name: "ready", type: "Boolean" }, { name: "secret", type: "Credential", direction: "input" }] };

function setup(args: Record<string, unknown> = {}, api: Partial<StudioApi> = {}) {
  const onArg = vi.fn();
  const step: FlowStep = { id: "s1", action: "ooa.test/all_kinds", args };
  const fake = {
    fsList: vi.fn(async (path: string) => !path
      ? { places: [{ name: "Home", path: "C:\\Users\\me" }], entries: [{ name: "Home", path: "C:\\Users\\me", dir: true }] }
      : { path, parent: "C:\\", places: [{ name: "Home", path: "C:\\Users\\me" }], entries: [{ name: "Reports", path: path + "\\Reports", dir: true }, { name: "orders.xlsx", path: path + "\\orders.xlsx", dir: false }] }),
    crCredentials: vi.fn(async () => ({ connected: true, credentials: [{ locker: "Finance", name: "SAP", attribute: "password" }] })),
    windows: vi.fn(async () => [{ title: "Invoices - Notepad", source: "application" as const, process: "notepad.exe" }]),
    ...api,
  } as unknown as StudioApi;
  const ctx: VarsCtx = { api: fake, flow: FLOW, builtins: { sys: { date: "2026-11-08" }, global: { region: "EU", rate: 2 } }, agents: [], edit: vi.fn(), newVariable: vi.fn() };
  render(<VarsContext.Provider value={ctx}><StepForm step={step} packages={[PKG]} onArg={onArg} onNote={() => {}} /></VarsContext.Provider>);
  return { onArg, fake };
}

describe("typing $ in a field (item 2)", () => {
  it("opens the variable list, and what is picked takes the $'s place", () => {
    const { onArg } = setup();
    const box = screen.getByLabelText("Text") as HTMLInputElement;
    fireEvent.input(box, { target: { value: "Total $" } });
    const list = screen.getByRole("dialog", { name: "Insert a value" });
    fireEvent.click(within(list).getByRole("option", { name: /\$count/ }));
    expect(onArg).toHaveBeenCalledWith("text", "Total $count$");
  });

  it("opens Insert a value with F2: a preview of the field, your variables first, system values with what they hold", () => {
    const { onArg } = setup({ text: "Hello " });
    const box = screen.getByLabelText("Text") as HTMLInputElement;
    box.setSelectionRange(6, 6);
    fireEvent.keyDown(box, { key: "F2" });
    const panel = screen.getByRole("dialog", { name: "Insert a value" });
    expect(within(panel).getByLabelText("Preview").textContent).toContain("Hello ");
    const heads = [...panel.querySelectorAll(".st-vp-head")].map((h) => h.textContent);
    expect(heads.slice(0, 2)).toEqual(["Your variables", "System values"]);
    const date = within(panel).getByRole("option", { name: /\$sys\.date/ });
    expect(date.textContent).toContain("Today's date");
    fireEvent.mouseEnter(date);
    expect(within(panel).getByLabelText("Preview").textContent).toContain("Hello $sys.date$");
    fireEvent.click(date);
    expect(onArg).toHaveBeenCalledWith("text", "Hello $sys.date$");
  });

  it("does not open for a $ inside a word or a price", () => {
    setup();
    fireEvent.input(screen.getByLabelText("Text"), { target: { value: "US$" } });
    expect(screen.queryByRole("dialog", { name: "Insert a value" })).toBeNull();
  });

  it("shows only the Shared Global values after $@", () => {
    const { onArg } = setup();
    fireEvent.input(screen.getByLabelText("Text"), { target: { value: "$" } });
    const list = screen.getByRole("dialog", { name: "Insert a value" });
    fireEvent.change(within(list).getByLabelText("Find a variable"), { target: { value: "@" } });
    const shown = within(list).getAllByRole("option").map((o) => o.textContent);
    expect(shown.every((t) => t?.includes("$global."))).toBe(true);
    expect(shown.length).toBe(2);
    fireEvent.click(within(list).getByRole("option", { name: /\$global\.region/ }));
    expect(onArg).toHaveBeenCalledWith("text", "$global.region$");
  });
});

describe("fields by kind (items 3 to 9)", () => {
  it("yes/no takes Yes, No or a variable (item 3)", () => {
    const { onArg } = setup();
    const choice = screen.getByRole("radiogroup", { name: "Yes, no or a variable" });
    fireEvent.click(within(choice).getByRole("radio", { name: "Yes" }));
    expect(onArg).toHaveBeenCalledWith("flag", true);
    fireEvent.click(within(choice).getByRole("radio", { name: "Variable" }));
    const box = screen.getByLabelText("Flag");
    fireEvent.change(box, { target: { value: "$ready" } }); fireEvent.blur(box);
    expect(onArg).toHaveBeenLastCalledWith("flag", "$ready");
  });

  it("a yes/no field holding a variable shows it, not a blank choice", () => {
    setup({ flag: "$ready" });
    expect(within(screen.getByRole("radiogroup", { name: "Yes, no or a variable" })).getByRole("radio", { name: "Variable" }).getAttribute("aria-checked")).toBe("true");
    expect((screen.getByLabelText("Flag") as HTMLInputElement).value).toBe("$ready");
  });

  it("a date comes from a calendar (item 7)", () => {
    const { onArg } = setup();
    const date = screen.getByLabelText("Date and time");
    expect(date).toHaveProperty("type", "datetime-local");
    fireEvent.change(date, { target: { value: "2026-11-08T09:30" } });
    expect(onArg).toHaveBeenCalledWith("due", "2026-11-08T09:30:00");
  });

  it("a list is typed item by item and kept when the editor is left (item 8)", () => {
    const { onArg } = setup();
    const lists = screen.getAllByRole("button", { name: "Item" });
    fireEvent.click(lists[0]);
    const item = screen.getByLabelText("Item 1");
    fireEvent.change(item, { target: { value: "north" } });
    fireEvent.blur(item, { relatedTarget: null });
    expect(onArg).toHaveBeenCalledWith("items", ["north"]);
  });

  it("a dictionary is typed key by key (item 8)", () => {
    const { onArg } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Key" }));
    fireEvent.change(screen.getByLabelText("Key 1"), { target: { value: "Accept" } });
    const value = screen.getByLabelText("Value 1");
    fireEvent.change(value, { target: { value: "json" } });
    fireEvent.blur(value, { relatedTarget: null });
    expect(onArg).toHaveBeenCalledWith("headers", { Accept: "json" });
  });

  it("Browse… chooses a file on this computer (item 4)", async () => {
    const { onArg, fake } = setup();
    // pickers (14 Nov 2026): a file field's Browse… asks where first (this computer or One CommandCenter), as AA
    const browse = screen.getAllByRole("button", { name: "Browse…" })[0];
    expect(browse.closest(".ui-path-row")).not.toBeNull();
    fireEvent.keyDown(browse, { key: "Enter" });
    const menu = await screen.findByRole("menu");
    expect(within(menu).getAllByRole("menuitem").map((m) => m.textContent)).toEqual([expect.stringMatching(/^This computer/), expect.stringMatching(/^One CommandCenter/)]);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /^This computer/ }));
    const dialog = await screen.findByRole("dialog", { name: "Choose a file" });
    fireEvent.click(await within(dialog).findByRole("option", { name: "Home" }));
    fireEvent.click(await within(dialog).findByRole("option", { name: "orders.xlsx" }));
    expect(fake.fsList).toHaveBeenCalledWith("C:\\Users\\me");
    expect(onArg).toHaveBeenCalledWith("source", "C:\\Users\\me\\orders.xlsx");
  });

  it("Browse… on a folder field chooses the folder, not a file (item 5)", async () => {
    const { onArg } = setup();
    fireEvent.click(screen.getAllByRole("button", { name: "Browse…" })[1]);
    const dialog = await screen.findByRole("dialog", { name: "Choose a folder" });
    fireEvent.click(await within(dialog).findByRole("option", { name: "Home" }));
    expect((await within(dialog).findByRole("option", { name: "orders.xlsx" })).hasAttribute("disabled")).toBe(true);
    fireEvent.click(within(dialog).getByRole("button", { name: "Use this folder" }));
    expect(onArg).toHaveBeenCalledWith("folder", "C:\\Users\\me");
  });

  it("a credential comes straight from the vault (item 6)", async () => {
    const { onArg } = setup();
    const pick = await screen.findByLabelText("Credential from the vault");
    fireEvent.change(pick, { target: { value: "Finance/SAP/password" } });
    expect(onArg).toHaveBeenCalledWith("password", { $credential: "Finance/SAP/password" });
  });

  it("without One CommandCenter the vault reference is typed in three parts (item 6)", async () => {
    const { onArg } = setup({}, { crCredentials: vi.fn(async () => ({ connected: false, credentials: [] })) } as Partial<StudioApi>);
    const folder = await screen.findByLabelText("Vault folder");
    fireEvent.change(folder, { target: { value: "HR" } }); fireEvent.blur(folder);
    const cred = screen.getByLabelText("Credential");
    fireEvent.change(cred, { target: { value: "Portal" } }); fireEvent.blur(cred);
    const attr = screen.getByLabelText("Attribute");
    fireEvent.change(attr, { target: { value: "password" } }); fireEvent.blur(attr);
    expect(onArg).toHaveBeenLastCalledWith("password", { $credential: "HR/Portal/password" });
  });

  it("a window is chosen from the windows open on this computer (item 9)", async () => {
    const { onArg } = setup();
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Open windows" })); });
    fireEvent.change(await screen.findByLabelText("Open windows"), { target: { value: "Invoices - Notepad" } });
    expect(onArg).toHaveBeenCalledWith("window", "Invoices - Notepad");
  });
});
