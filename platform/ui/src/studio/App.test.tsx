import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { StudioApi } from "../api/client";
import { App } from "./App";

afterEach(() => { cleanup(); localStorage.clear(); });

/** A fake Studio server: a workspace with Finance/Invoices, one agent, the Excel toolkit; records what is created. */
function fakeServer() {
  const created: unknown[] = [];
  let lastSaved: any = null;
  const tree = {
    name: "Workspace", path: "", type: "folder", children: [
      { name: "Finance", path: "Finance", type: "folder", children: [
        { name: "Invoices", path: "Finance/Invoices", type: "folder", children: [
          { name: "Invoice intake.flow.json", path: "Finance/Invoices/Invoice intake.flow.json", type: "bot", kind: "TaskBot", modified: "2026-10-29T09:12:00Z" },
          { name: "Invoice check.form.json", path: "Finance/Invoices/Invoice check.form.json", type: "form", modified: "2026-10-28T09:12:00Z" },
          { name: "Approve.process.json", path: "Finance/Invoices/Approve.process.json", type: "process", modified: "2026-10-28T09:12:00Z" },
          { name: "clean.py", path: "Finance/Invoices/clean.py", type: "script", modified: "2026-10-28T09:12:00Z" },
          { name: "Broken.doc.json", path: "Finance/Invoices/Broken.doc.json", type: "document", modified: "2026-10-28T09:12:00Z" }] }] }],
  };
  const flow = { kind: "TaskBot", id: "invoice_intake", name: "Invoice intake",
    variables: [{ name: "workbook", type: "File", direction: "input" }],
    steps: [{ id: "open", action: "ooa.excel/open", args: { path: "$workbook" } }, { id: "loop", action: "core/loop", children: [{ id: "log", action: "core/log", args: { message: "row" } }] }] };
  const routes: Record<string, (body: any) => unknown> = {
    "GET info": () => ({ product: "Studio", edition: "preview", workspace: "C:/ws", controlRoom: "/" }),
    "GET tree": () => ({ workspace: "C:/ws", tree }),
    "GET packages": () => ({ packages: [
      { id: "ooa.excel", name: "Excel", version: "1.0.0", actions: [{ id: "open", name: "Open workbook" }, { id: "close", name: "Close workbook" }] },
      { id: "core", name: "Core", version: "1.0.0", actions: [
        { id: "if", name: "If", inputs: { properties: { condition: { "x-ooa-label": "Condition" } } } },
        { id: "try", name: "Try", inputs: { properties: {} } },
        { id: "loop", name: "Loop", inputs: { properties: { iterator: { enum: ["list", "while"] }, condition: { "x-ooa-visible-when": { iterator: ["while"] } } } } },
        { id: "log", name: "Write to log", inputs: { properties: { message: {} } } }] }] }),
    "GET bot": () => ({ path: "x", flow: lastSaved ?? flow }),
    "GET blueprints": () => ({ blueprints: [
      { id: "excel_rows", name: "Loop through Excel rows", description: "Open a workbook and handle each row.", variables: [{ name: "workbook", type: "File", direction: "input" }],
        steps: [{ id: "s1", action: "ooa.excel/open", args: { path: "$workbook" } }] },
      { id: "needs_sap", name: "SAP order", description: "Needs a toolkit that is not installed.", variables: [], steps: [{ id: "s1", action: "ooa.sap/connect" }] }] }),
    "PUT bot": (b) => {
      created.push({ saved: b }); lastSaved = b;
      const close = (b.steps ?? []).find((x: any) => x.action === "ooa.excel/close");
      return { saved: "x", problems: close ? [{ stepId: close.id, path: "args/session", message: "Close needs the workbook session", suggestedFix: "Use excel" }] : [] };
    },
    "POST files/bot": (b) => { created.push(b); return { path: b.parent + "/" + b.name + ".flow.json" }; },
    "POST files/folder": (b) => { created.push(b); return { path: b.parent + "/" + b.name }; },
    "POST files/item": (b) => { created.push(b); return { path: "Finance/Invoices/Invoice check.form.json" }; },
    "POST files/delete": (b) => { created.push({ deleted: b.path }); return { path: ".trash/x" }; },
  };
  // a run: the first poll is running (or stopped at a breakpoint when debugging), the next ones finished
  let polls = 0, debugging = false, triggers: any[] = [];
  const runs: any[] = [];
  const items: Record<string, { type: string; text: string }> = {
    "Finance/Invoices/Invoice check.form.json": { type: "form", text: JSON.stringify({ id: "check", name: "Invoice check", fields: [{ id: "number", type: "text", label: "Invoice number", required: true }], submitLabel: "Submit" }, null, 2) },
    "Finance/Invoices/Approve.process.json": { type: "process", text: JSON.stringify({ id: "approve", name: "Approve", stages: [] }, null, 2) },
    "Finance/Invoices/clean.py": { type: "script", text: "print('hello')\n" },
    "Finance/Invoices/Broken.doc.json": { type: "document", text: "{ not json" },
  };
  const savedItems: Record<string, string> = {};
  routes["GET item"] = () => { throw new Error("use itemFor"); };
  routes["POST runs"] = (b) => { runs.push(b); polls = 0; debugging = !!b.debug; return { run_id: "run_0123456789abcdef" }; };
  routes["GET runs/run_0123456789abcdef"] = () => {
    polls++;
    if (debugging && polls < 3) return { run_id: "x", state: "debugging", elapsed_ms: 10, events: [],
      debug: { stepId: "loop", action: "core/loop", reason: "breakpoint", variables: { workbook: "C:/in.xlsx", count: 2 } } };
    if (polls < 2) return { run_id: "x", state: "running", elapsed_ms: 5, events: [
      { seq: 1, type: "run.started", data: { totalSteps: 3 } }, { seq: 2, type: "step.started", stepId: "open" }] };
    const action = runs[runs.length - 1]?.flow?.steps?.[0]?.action;
    if (action === "ooa.ai_agent/run" || action === "ooa.genai/prompt") return { run_id: "x", state: "success", elapsed_ms: 20, events: [],
      result: { status: "success", outputs: action === "ooa.genai/prompt" ? { answer: "OK" }
        : { answer: "Queued for payment.", status: "completed", trace: [{ tool: "lookup_order", status: "ok" }] } } };
    return { run_id: "x", state: "success", elapsed_ms: 20, events: polls === 2 ? [
      { seq: 3, type: "step.finished", stepId: "open", data: { durationMs: 4 } },
      { seq: 4, type: "log", stepId: "log", data: { level: "info", message: "row" } },
      { seq: 5, type: "run.finished", data: { status: "success" } }] : [],
      result: { status: "success", outputs: { total: 1250 }, error: null } };
  };
  routes["POST runs/run_0123456789abcdef/debug"] = (b) => { runs.push({ command: b.command }); debugging = false; return { ok: true }; };
  routes["POST runs/run_0123456789abcdef/stop"] = () => ({ ok: true });
  routes["GET triggers"] = () => ({ triggers, log: [] });
  let connections: any = { default: "", connections: [] };
  routes["GET agents"] = () => ({ agents: [
    { path: "Finance/Invoices/Invoice intake.flow.json", id: "invoice_intake", name: "Invoice intake", variables: [] },
    { path: "Finance/lookup.flow.json", id: "lookup_order", name: "Look up order", variables: [] }] });
  routes["GET model-connections"] = () => connections;
  routes["PUT model-connections"] = (b) => { connections = b; return b; };
  routes["PUT triggers"] = (b) => { triggers = b.map((t: any) => ({ enabled: true, ...t })); return { triggers }; };
  const f = (async (url: string, init: RequestInit) => {
    const route = url.replace(/^\/api\//, "").replace(/\?.*$/, "");
    if (route === "item") {
      const path = decodeURIComponent(url.replace(/^.*[?&]path=/, ""));
      if (init.method === "GET") return new Response(JSON.stringify({ path, ...items[path] }), { status: 200 });
      savedItems[path] = JSON.parse(init.body as string).text;
      return new Response(JSON.stringify({ saved: path }), { status: 200 });
    }
    const handler = routes[`${init.method} ${route}`];
    if (!handler) return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "No route " + route } }), { status: 404 });
    return new Response(JSON.stringify(handler(init.body ? JSON.parse(init.body as string) : undefined)), { status: 200 });
  }) as unknown as typeof fetch;
  return { api: new StudioApi("/api/", f), created, runs, triggers: () => triggers, savedItems, connections: () => connections };
}

describe("new Studio", () => {
  it("shows the Command Center menu in four sections, with links to Command Center pages", async () => {
    render(<App api={fakeServer().api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    for (const s of ["BUILD", "MANAGE", "ADMINISTRATION", "AI"]) expect(within(menu).getByText(s)).toBeTruthy();
    await waitFor(() => expect(within(menu).getByRole("link", { name: "Work queues" }).getAttribute("href")).toBe("/?page=queues"));
    expect(within(menu).getByRole("button", { name: "Automation" }).getAttribute("aria-current")).toBe("page");
    for (const gone of ["Workflows", "Forms", "Templates"]) expect(within(menu).queryByText(gone)).toBeNull();   // made from Create, called from agents
  });

  it("creates in AA's order: folder, then Create, then the type, then the name", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const tree = await screen.findByRole("tree", { name: "Folders" });
    fireEvent.click(await within(tree).findByText("Finance"));
    expect(screen.getByRole("heading", { name: "Automation" })).toBeTruthy();

    // the Create menu (opened from the keyboard here) offers the types, then the dialog asks only the name
    const create = screen.getByRole("button", { name: "Create ▾" });
    await act(async () => { create.focus(); fireEvent.keyDown(create, { key: "ArrowDown" }); });
    fireEvent.click(await screen.findByRole("menuitem", { name: /^Agent, Steps in apps/ }));
    const dialog = await screen.findByRole("dialog", { name: "New agent" });
    expect(within(dialog).getByText("Finance")).toBeTruthy();
    expect(document.activeElement).toBe(within(dialog).getByLabelText("Name"));
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Invoice intake Germany" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Create and edit" })); });

    expect(created).toEqual([{ parent: "Finance", name: "Invoice intake Germany", kind: "TaskBot" }]);
    // the new agent opens in a tab, with the actions library beside it
    expect(await screen.findByRole("tab", { name: "Invoice intake" })).toBeTruthy();
    expect(screen.getByRole("complementary", { name: "Actions" })).toBeTruthy();
  });

  it("asks for a name before creating, and Cancel closes the dialog", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const create = await screen.findByRole("button", { name: "Create ▾" });
    await act(async () => { create.focus(); fireEvent.keyDown(create, { key: "ArrowDown" }); });
    fireEvent.click(await screen.findByRole("menuitem", { name: /^Agent, Steps in apps/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create and edit" }));
    expect((await within(dialog).findByRole("alert")).textContent).toBe("Give it a name.");
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(created).toEqual([]);
  });

  it("opens an agent in a tab with numbered steps, properties and variables, and Close returns to Automation", async () => {
    render(<App api={fakeServer().api} />);
    const search = await screen.findByLabelText("Search items");
    fireEvent.change(search, { target: { value: "intake" } });
    fireEvent.click(await screen.findByRole("button", { name: "Invoice intake" }));

    const steps = await screen.findByRole("region", { name: "Agent steps" });
    expect(within(steps).getAllByRole("button").filter((b) => b.classList.contains("st-step")).map((b) => b.querySelector(".st-step-n")?.textContent + " " + b.querySelector("strong")?.textContent))
      .toEqual(["1 Open workbook", "2 Loop", "3 Write to log"]);
    expect((screen.getByLabelText("path") as HTMLInputElement).value).toBe("$workbook");
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Variables" }));
    expect(await screen.findByText("$workbook")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(await screen.findByRole("heading", { name: "Automation" })).toBeTruthy();
  });

  it("loads its data once when it makes its own API client (no reload loop)", async () => {
    const seen: string[] = [];
    const real = globalThis.fetch;
    globalThis.fetch = (async (url: string) => {
      seen.push(String(url));
      const body = String(url).endsWith("tree") ? { tree: { name: "Workspace", path: "", type: "folder", children: [] } } : String(url).endsWith("packages") ? { packages: [] } : { controlRoom: null };
      return new Response(JSON.stringify(body), { status: 200 });
    }) as unknown as typeof fetch;
    try {
      render(<App />);
      await screen.findByRole("heading", { name: "Automation" });
      await new Promise((r) => setTimeout(r, 200));
      expect(seen.filter((u) => u.endsWith("/info")).length).toBe(1);
      expect(seen.filter((u) => u.endsWith("/packages")).length).toBe(1);
    } finally { globalThis.fetch = real; }
  });

  it("folds the menu to icons and back, opens and closes sections, and remembers it", async () => {
    const { api } = fakeServer();
    const { unmount } = render(<App api={api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    fireEvent.click(within(menu).getByRole("button", { name: "MANAGE" }));
    expect(within(menu).queryByRole("link", { name: "Work queues" })).toBeNull();
    fireEvent.click(within(menu).getByRole("button", { name: "Collapse menu" }));
    expect(within(menu).queryByRole("button", { name: "Automation" })).toBeNull();
    expect(within(menu).getByRole("button", { name: "BUILD" })).toBeTruthy();

    unmount();
    render(<App api={api} />);
    const again = await screen.findByRole("navigation", { name: "Command Center" });
    expect(within(again).getByRole("button", { name: "Expand menu" }).getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(within(again).getByRole("button", { name: "MANAGE" }));   // an icon opens the menu at its section
    expect(await within(again).findByRole("link", { name: "Work queues" })).toBeTruthy();
  });

  it("hides and shows the folders, actions and details panels", async () => {
    render(<App api={fakeServer().api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Hide folders" }));
    expect(screen.queryByRole("tree", { name: "Folders" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show folders" }));
    expect(screen.getByRole("tree", { name: "Folders" })).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Search items"), { target: { value: "intake" } });
    fireEvent.click(await screen.findByRole("button", { name: "Invoice intake" }));
    fireEvent.click(await screen.findByRole("button", { name: "Hide actions" }));
    expect(screen.queryByLabelText("Search actions")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Hide details" }));
    expect(screen.queryByRole("tab", { name: "Variables" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show details" }));
    expect(screen.getByRole("tab", { name: "Variables" })).toBeTruthy();
  });

  it("makes a folder from the + next to Folders, inside the chosen folder", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const tree = await screen.findByRole("tree", { name: "Folders" });
    fireEvent.click(await within(tree).findByText("Finance"));
    fireEvent.click(screen.getByRole("button", { name: "New folder in Finance" }));
    const dialog = await screen.findByRole("dialog", { name: "New folder" });
    expect(document.activeElement).toBe(within(dialog).getByLabelText("Name"));
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Germany" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Create folder" })); });
    expect(created).toEqual([{ parent: "Finance", name: "Germany" }]);
  });

  it("starts a new agent from a blueprint chosen in Create, offering only blueprints whose toolkits are installed", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const create = await screen.findByRole("button", { name: "Create ▾" });
    await act(async () => { create.focus(); fireEvent.keyDown(create, { key: "ArrowDown" }); });
    fireEvent.click(await screen.findByRole("menuitem", { name: /^Agent, Steps in apps/ }));
    const dialog = await screen.findByRole("dialog", { name: "New agent" });
    const start = within(dialog).getByRole("combobox", { name: "Start from" });
    act(() => start.focus());
    expect(within(dialog).getAllByRole("option").map((o) => o.querySelector("strong")!.textContent)).toEqual(["Blank agent", "Loop through Excel rows"]);
    // type to search (name or description), nothing matches shows a message, then pick with the keyboard
    fireEvent.keyDown(start, { key: "Escape" });   // Esc closes the list, not the dialog
    expect(within(dialog).queryByRole("listbox")).toBeNull();
    expect(screen.getByRole("dialog", { name: "New agent" })).toBeTruthy();
    act(() => { start.blur(); start.focus(); });
    fireEvent.change(start, { target: { value: "zzz" } });
    expect(within(dialog).getByText("Nothing matches “zzz”.")).toBeTruthy();
    fireEvent.change(start, { target: { value: "workbook" } });
    expect(within(dialog).getAllByRole("option").map((o) => o.querySelector("strong")!.textContent)).toEqual(["Loop through Excel rows"]);
    fireEvent.keyDown(start, { key: "Enter" });
    expect(within(dialog).queryByRole("listbox")).toBeNull();
    expect((start as HTMLInputElement).value).toBe("Loop through Excel rows");
    expect((within(dialog).getByLabelText("Name") as HTMLInputElement).value).toBe("Loop through Excel rows");
    expect(within(dialog).getByText(/^Open a workbook and handle each row./)).toBeTruthy();
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Create and edit" })); });

    const saved = (created.find((c: any) => c.saved) as any).saved;
    expect(saved.name).toBe("Invoice intake");   // the new agent keeps its own id and name
    expect(saved.description).toBe("Open a workbook and handle each row.");
    expect(saved.steps).toEqual([{ id: "s1", action: "ooa.excel/open", args: { path: "$workbook" } }]);
  });

  it("edits an agent: add from Actions, undo and redo, delete, change a setting, save with problems, ask before closing unsaved", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    fireEvent.change(await screen.findByLabelText("Search items"), { target: { value: "intake" } });
    fireEvent.click(await screen.findByRole("button", { name: "Invoice intake" }));
    const steps = await screen.findByRole("region", { name: "Agent steps" });
    const names = () => within(steps).getAllByRole("button", { pressed: undefined }).filter((b) => b.classList.contains("st-step"))
      .map((b) => b.querySelector("strong")!.textContent);
    expect(names()).toEqual(["Open workbook", "Loop", "Write to log"]);

    // add: after the selected step (the first one is selected when an agent opens)
    fireEvent.click(screen.getByRole("button", { name: /^Excel/ }));
    fireEvent.click(screen.getByRole("button", { name: "Close workbook" }));
    expect(names()).toEqual(["Open workbook", "Close workbook", "Loop", "Write to log"]);
    expect(screen.getByText("Unsaved")).toBeTruthy();
    fireEvent.click(within(steps).getByRole("button", { name: "Undo" }));
    expect(names()).toEqual(["Open workbook", "Loop", "Write to log"]);
    fireEvent.click(within(steps).getByRole("button", { name: "Redo" }));
    expect(names()).toEqual(["Open workbook", "Close workbook", "Loop", "Write to log"]);

    // change a setting of the selected step (Close workbook): it commits when the field loses focus
    fireEvent.click(within(steps).getAllByRole("button").find((b) => b.textContent?.includes("Open workbook"))!);
    const path = screen.getByLabelText("path") as HTMLInputElement;
    fireEvent.change(path, { target: { value: "C:/data/in.xlsx" } });
    fireEvent.blur(path);

    // save: the server's check finds a problem on the new step, shown on the step and in Problems
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); });
    const saved = (created.filter((c: any) => c.saved).pop() as any).saved;
    expect(saved.steps.map((x: any) => x.action)).toEqual(["ooa.excel/open", "ooa.excel/close", "core/loop"]);
    expect(saved.steps[0].args.path).toBe("C:/data/in.xlsx");
    const problems = screen.getByRole("region", { name: "Problems" });
    expect(within(problems).getByText(/Line 2: Close needs the workbook session/)).toBeTruthy();
    expect(screen.queryByText("Unsaved")).toBeNull();

    // delete with the Delete key, then closing asks because of unsaved changes
    fireEvent.click(within(steps).getAllByRole("button").find((b) => b.textContent?.includes("Close workbook"))!);
    fireEvent.keyDown(steps, { key: "Delete" });
    expect(names()).toEqual(["Open workbook", "Loop", "Write to log"]);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    const ask = await screen.findByRole("dialog", { name: "Save changes to Invoice intake?" });
    fireEvent.click(within(ask).getByRole("button", { name: "Don't save" }));
    expect(await screen.findByRole("heading", { name: "Automation" })).toBeTruthy();
  });

  it("adds a variable and refuses a bad name", async () => {
    render(<App api={fakeServer().api} />);
    fireEvent.change(await screen.findByLabelText("Search items"), { target: { value: "intake" } });
    fireEvent.click(await screen.findByRole("button", { name: "Invoice intake" }));
    fireEvent.mouseDown(await screen.findByRole("tab", { name: "Variables" }));
    const form = (await screen.findByRole("button", { name: "Add variable" })).closest("form")!;
    fireEvent.change(within(form).getByLabelText("Name"), { target: { value: "2nd" } });
    fireEvent.click(within(form).getByRole("button", { name: "Add variable" }));
    expect(within(form).getByRole("alert").textContent).toMatch(/starts with a letter/);
    fireEvent.change(within(form).getByLabelText("Name"), { target: { value: "$total" } });
    fireEvent.change(within(form).getByLabelText("Type"), { target: { value: "Number" } });
    fireEvent.click(within(form).getByRole("button", { name: "Add variable" }));
    expect(screen.getByText("$total")).toBeTruthy();
  });

  const openIntake = async () => {
    fireEvent.change(await screen.findByLabelText("Search items"), { target: { value: "intake" } });
    fireEvent.click(await screen.findByRole("button", { name: "Invoice intake" }));
    return screen.findByRole("region", { name: "Agent steps" });
  };
  const stepButton = (steps: HTMLElement, name: string) => within(steps).getAllByRole("button").find((b) => b.classList.contains("st-step") && b.textContent?.includes(name))!;
  const stepNames = (steps: HTMLElement) => within(steps).getAllByRole("button").filter((b) => b.classList.contains("st-step")).map((b) => b.querySelector("strong")!.textContent);
  /** A drag between elements, with the data the drag carries. */
  const drag = (from: HTMLElement, to: HTMLElement) => {
    const store: Record<string, string> = {};
    const dataTransfer = { setData: (k: string, v: string) => { store[k] = v; }, getData: (k: string) => store[k] ?? "", effectAllowed: "" };
    fireEvent.dragStart(from, { dataTransfer });
    fireEvent.dragOver(to, { dataTransfer });
    fireEvent.drop(to, { dataTransfer });
  };

  it("drags a step to a new place and an action from the library into a loop", async () => {
    render(<App api={fakeServer().api} />);
    const steps = await openIntake();
    drag(stepButton(steps, "Open workbook"), stepButton(steps, "Write to log"));   // after it (the drop lands in its lower part)
    expect(stepNames(steps)).toEqual(["Loop", "Write to log", "Open workbook"]);
    fireEvent.click(screen.getByRole("button", { name: /^Excel/ }));
    drag(screen.getByRole("button", { name: "Close workbook" }), within(steps).getByText("END"));
    expect(stepNames(steps)).toEqual(["Loop", "Write to log", "Open workbook", "Close workbook"]);
  });

  it("selects several steps with Ctrl and Shift, then switches them off and deletes them together", async () => {
    render(<App api={fakeServer().api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    fireEvent.click(stepButton(steps, "Write to log"), { shiftKey: true });
    expect(within(steps).getByText(/^3 selected of 3 steps/)).toBeTruthy();
    expect(screen.getByText(/3 steps selected/)).toBeTruthy();
    fireEvent.click(stepButton(steps, "Loop"), { ctrlKey: true });   // Ctrl+click takes one out again
    expect(within(steps).getByText(/^2 selected of 3 steps/)).toBeTruthy();
    fireEvent.click(within(steps).getByRole("button", { name: "Switch off" }));
    expect(within(steps).getAllByText("OFF")).toHaveLength(2);
    fireEvent.click(within(steps).getByRole("button", { name: "Delete" }));
    expect(stepNames(steps)).toEqual(["Loop"]);
  });

  it("builds an If condition from checks in plain words, and hides inputs that do not apply", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    // the loop's condition applies only to "while" loops, so it is hidden for this list loop
    fireEvent.click(stepButton(steps, "Loop"));
    expect(screen.queryByRole("group", { name: "Check 1" })).toBeNull();

    fireEvent.click(stepButton(steps, "Write to log"));
    fireEvent.click(screen.getByRole("button", { name: /^Core/ }));
    fireEvent.click(screen.getByRole("button", { name: "If" }));
    const check1 = await screen.findByRole("group", { name: "Check 1" });
    fireEvent.change(within(check1).getByLabelText("Value"), { target: { value: "$total" } });
    fireEvent.blur(within(check1).getByLabelText("Value"));
    fireEvent.change(within(check1).getByLabelText("Comparison"), { target: { value: "gt" } });
    fireEvent.change(within(screen.getByRole("group", { name: "Check 1" })).getByLabelText("Compared with"), { target: { value: "1000" } });
    fireEvent.blur(within(screen.getByRole("group", { name: "Check 1" })).getByLabelText("Compared with"));
    expect(screen.getByText("Reads: $total is greater than 1000")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Add a check" }));
    const check2 = screen.getByRole("group", { name: "Check 2" });
    fireEvent.change(within(check2).getByLabelText("Value"), { target: { value: "$vendor" } });
    fireEvent.change(within(check2).getByLabelText("Comparison"), { target: { value: "empty" } });
    expect(within(screen.getByRole("group", { name: "Check 2" })).queryByLabelText("Compared with")).toBeNull();
    fireEvent.change(screen.getByLabelText("Match"), { target: { value: "any" } });
    expect(screen.getByText("Reads: $total is greater than 1000 or $vendor is empty")).toBeTruthy();

    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); });
    const saved = (created.filter((c: any) => c.saved).pop() as any).saved;
    const all = (list: any[]): any[] => list.flatMap((x) => [x, ...all(x.children ?? [])]);
    const iff = all(saved.steps).find((x: any) => x.action === "core/if");
    expect(saved.steps[1].children.map((x: any) => x.action)).toEqual(["core/log", "core/if"]);   // inside the loop, after the log
    expect(iff.args.condition).toEqual({ any: [{ op: "gt", left: "$total", right: 1000 }, { op: "empty", left: "$vendor" }] });
    expect(iff.children).toEqual([]);
    expect(iff.else).toEqual([]);
  });;

  it("saves what is typed in a field even when Ctrl+S is pressed without leaving it", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    const path = screen.getByLabelText("path") as HTMLInputElement;
    act(() => path.focus());
    fireEvent.change(path, { target: { value: "D:/typed.xlsx" } });
    await act(async () => { fireEvent.keyDown(path, { key: "s", ctrlKey: true }); await new Promise((r) => setTimeout(r, 20)); });
    const saved = (created.filter((c: any) => c.saved).pop() as any).saved;
    expect(saved.steps[0].args.path).toBe("D:/typed.xlsx");
  });

  it("runs the agent with its inputs and shows the output and outputs", async () => {
    const { api, runs } = fakeServer();
    render(<App api={api} />);
    await openIntake();
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    const dialog = await screen.findByRole("dialog", { name: "Run Invoice intake" });
    fireEvent.change(within(dialog).getByLabelText("$workbook (File)"), { target: { value: "C:/in.xlsx" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Run" })); });
    expect(runs[0].inputs).toEqual({ workbook: "C:/in.xlsx" });
    expect(runs[0].flow.steps.map((x: any) => x.id)).toEqual(["open", "loop"]);   // the flow as it is in the editor
    expect(await screen.findByText("Run finished", {}, { timeout: 3000 })).toBeTruthy();
    expect(screen.getByText(/Line 1 Open workbook · done in 4 ms/)).toBeTruthy();
    expect(screen.getByText("Finished")).toBeTruthy();
    expect(screen.getByText("$total").closest("tr")!.textContent).toContain("1250");
  });

  it("debugs: stops at a breakpoint, shows the values there, and continues", async () => {
    const { api, runs } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Loop"));
    fireEvent.keyDown(stepButton(steps, "Loop"), { key: "F9" });
    expect(stepButton(steps, "Loop").querySelector(".st-bp")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Debug" }));
    const dialog = await screen.findByRole("dialog", { name: "Debug Invoice intake" });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Debug" })); });
    expect(runs[0]).toMatchObject({ debug: true, breakpoints: ["loop"], stopAtStart: false });
    expect(await screen.findByText("Stopped at a step", {}, { timeout: 3000 })).toBeTruthy();
    expect(stepButton(steps, "Loop").classList.contains("is-running")).toBe(true);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Debug values" }));
    expect((await screen.findByText("$workbook")).closest("tr")!.textContent).toContain("C:/in.xlsx");
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Run output" }));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Continue" })); });
    expect(runs[1]).toEqual({ command: "continue" });
    expect(await screen.findByText("Run finished", {}, { timeout: 3000 })).toBeTruthy();
  });

  it("adds a schedule trigger for this agent only", async () => {
    const { api, triggers } = fakeServer();
    render(<App api={api} />);
    await openIntake();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Triggers" }));
    expect(await screen.findByText("No triggers yet.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Every (minutes)"), { target: { value: "15" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Add trigger" })); });
    expect(triggers()).toHaveLength(1);
    expect(triggers()[0]).toMatchObject({ bot: "Finance/Invoices/Invoice intake.flow.json", type: "schedule", every_minutes: 15 });
    expect(screen.getByText("Every 15 minutes")).toBeTruthy();
  });

  const openItem = async (name: string) => {
    fireEvent.change(await screen.findByLabelText("Search items"), { target: { value: name } });
    fireEvent.click(await screen.findByRole("button", { name }));
  };

  it("edits a form: add a drop-down, rename it, give it options, save the form file", async () => {
    const { api, savedItems } = fakeServer();
    render(<App api={api} />);
    await openItem("Invoice check");
    const fields = await screen.findByRole("region", { name: "Form fields" });
    expect(within(fields).getByText("Invoice number")).toBeTruthy();
    expect(screen.queryByRole("complementary", { name: "Actions" })).toBeNull();   // the action library is for agents only
    fireEvent.change(screen.getByLabelText("Add a field"), { target: { value: "dropdown" } });
    fireEvent.click(within(fields).getByRole("button", { name: "Add" }));
    const label = screen.getByLabelText("Label") as HTMLInputElement;
    fireEvent.change(label, { target: { value: "Currency" } });
    fireEvent.blur(label);
    const options = screen.getByLabelText("Options, one per line");
    fireEvent.change(options, { target: { value: "EUR\nUSD\n" } });
    fireEvent.blur(options);
    expect(screen.getByText("Unsaved")).toBeTruthy();
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); });
    const form = JSON.parse(savedItems["Finance/Invoices/Invoice check.form.json"]);
    expect(form.fields[1]).toEqual({ id: "dropdown_field", type: "dropdown", label: "Currency", options: ["EUR", "USD"] });
    expect(form.submitLabel).toBe("Submit");
  });

  it("edits a workflow: an agent task chosen from the workspace's agents", async () => {
    const { api, savedItems } = fakeServer();
    render(<App api={api} />);
    await openItem("Approve");
    const stages = await screen.findByRole("region", { name: "Workflow stages" });
    fireEvent.click(within(stages).getByRole("button", { name: "Add" }));
    fireEvent.change(screen.getByLabelText("Agent"), { target: { value: "Finance/Invoices/Invoice intake.flow.json" } });
    expect(within(stages).getByText(/Agent task · Finance\/Invoices\/Invoice intake.flow.json/)).toBeTruthy();
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); });
    expect(JSON.parse(savedItems["Finance/Invoices/Approve.process.json"]).stages).toEqual([
      { id: "bot", type: "bot", name: "Agent task 1", bot: "Finance/Invoices/Invoice intake.flow.json" }]);
  });

  it("edits a script as text with undo, and opens a broken JSON file as text with a warning", async () => {
    const { api, savedItems } = fakeServer();
    render(<App api={api} />);
    await openItem("clean.py");
    const code = await screen.findByLabelText("Python script") as HTMLTextAreaElement;
    fireEvent.change(code, { target: { value: "print('changed')\n" } });
    fireEvent.blur(code);
    fireEvent.click(screen.getByRole("button", { name: "Undo" }));
    expect((screen.getByLabelText("Python script") as HTMLTextAreaElement).value).toBe("print('hello')\n");
    fireEvent.click(screen.getByRole("button", { name: "Redo" }));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); });
    expect(savedItems["Finance/Invoices/clean.py"]).toBe("print('changed')\n");

    await openItem("Broken");
    expect((await screen.findByRole("alert")).textContent).toMatch(/not valid JSON/);
    expect((screen.getByLabelText("Text") as HTMLTextAreaElement).value).toBe("{ not json");
  });

  it("opens a newly created form in its editor", async () => {
    const { api } = fakeServer();
    render(<App api={api} />);
    const create = await screen.findByRole("button", { name: "Create ▾" });
    await act(async () => { create.focus(); fireEvent.keyDown(create, { key: "ArrowDown" }); });
    fireEvent.click(await screen.findByRole("menuitem", { name: /^Form,/ }));
    const dialog = await screen.findByRole("dialog", { name: "New form" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Invoice check" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Create and edit" })); });
    expect(await screen.findByRole("region", { name: "Form fields" })).toBeTruthy();
  });

  it("deletes an item and a folder to the trash after asking", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    fireEvent.change(await screen.findByLabelText("Search items"), { target: { value: "clean" } });
    fireEvent.click(await screen.findByRole("button", { name: "Delete clean.py" }));
    const ask = await screen.findByRole("dialog", { name: "Delete clean.py?" });
    expect(ask.textContent).toMatch(/workspace's .trash folder/);
    fireEvent.click(within(ask).getByRole("button", { name: "Cancel" }));
    expect(created.some((c: any) => c.deleted)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Delete clean.py" }));
    await act(async () => { fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Move to trash" })); });
    expect(created).toContainEqual({ deleted: "Finance/Invoices/clean.py" });
    expect(screen.getByText("Moved clean.py to the workspace trash.")).toBeTruthy();

    // a folder: the trash button next to Folders, for the chosen folder
    fireEvent.change(screen.getByLabelText("Search items"), { target: { value: "" } });
    fireEvent.click(await within(await screen.findByRole("tree", { name: "Folders" })).findByText("Finance"));
    fireEvent.click(screen.getByRole("button", { name: "Delete folder Finance" }));
    const ask2 = await screen.findByRole("dialog", { name: "Delete folder Finance?" });
    await act(async () => { fireEvent.click(within(ask2).getByRole("button", { name: "Move to trash" })); });
    expect(created).toContainEqual({ deleted: "Finance" });
  });

  it("creates an AI agent and edits it in the AI agent editor: tools, approval, role, then tries it", async () => {
    const { api, created, runs } = fakeServer();
    render(<App api={api} />);
    const create = await screen.findByRole("button", { name: "Create ▾" });
    await act(async () => { create.focus(); fireEvent.keyDown(create, { key: "ArrowDown" }); });
    fireEvent.click(await screen.findByRole("menuitem", { name: /^AI agent,/ }));
    const dialog = await screen.findByRole("dialog", { name: "New ai agent" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Invoice analyst" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Create and edit" })); });
    expect(await screen.findByRole("region", { name: "Describe the agent" })).toBeTruthy();
    expect(screen.queryByRole("complementary", { name: "Actions" })).toBeNull();

    const role = screen.getByLabelText("Role");
    fireEvent.change(role, { target: { value: "Accounts payable assistant" } });
    fireEvent.blur(role);
    fireEvent.mouseDown(screen.getByRole("tab", { name: /^Tools/ }));
    const pick = await screen.findByRole("combobox", { name: "Add an agent as a tool" });
    act(() => pick.focus());
    fireEvent.change(pick, { target: { value: "look" } });
    fireEvent.keyDown(pick, { key: "Enter" });
    fireEvent.click(screen.getByLabelText("Ask a person before each use of Look up order"));
    // Save first finishes the field that has focus, then saves: give it a moment
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); await new Promise((r) => setTimeout(r, 30)); });
    const saved = (created.filter((c: any) => c.saved).pop() as any).saved;
    expect(saved.tags).toContain("ai-agent");
    expect(saved.steps[0]).toMatchObject({ action: "ooa.ai_agent/run", args: { goal: "${request}", tools: ["lookup_order"], sensitive_tools: ["lookup_order"], role: "Accounts payable assistant" } });

    const tryIt = screen.getByRole("complementary", { name: "Try it" });
    fireEvent.change(within(tryIt).getByLabelText("Request"), { target: { value: "Invoice 42 from Acme" } });
    await act(async () => { fireEvent.click(within(tryIt).getByRole("button", { name: "Run a test" })); });
    expect(runs[runs.length - 1].inputs).toEqual({ request: "Invoice 42 from Acme" });
    expect(await within(tryIt).findByText("Queued for payment.", {}, { timeout: 3000 })).toBeTruthy();
    expect(within(tryIt).getByText("Used lookup_order · ok")).toBeTruthy();
  });

  it("adds a model connection in four steps, with the key only as a vault reference, and tests it", async () => {
    const { api, connections } = fakeServer();
    render(<App api={api} />);
    const create = await screen.findByRole("button", { name: "Create ▾" });
    await act(async () => { create.focus(); fireEvent.keyDown(create, { key: "ArrowDown" }); });
    fireEvent.click(await screen.findByRole("menuitem", { name: /^AI agent,/ }));
    const d = await screen.findByRole("dialog", { name: "New ai agent" });
    fireEvent.change(within(d).getByLabelText("Name"), { target: { value: "Helper" } });
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Create and edit" })); });
    fireEvent.mouseDown(await screen.findByRole("tab", { name: "Model" }));
    fireEvent.click(await screen.findByRole("button", { name: "New connection" }));
    const w = await screen.findByRole("dialog", { name: "New model connection" });
    fireEvent.click(within(w).getByRole("button", { name: "Next" }));
    expect(within(w).getByRole("alert").textContent).toMatch(/Give it a name/);
    fireEvent.change(within(w).getByLabelText("AI service"), { target: { value: "anthropic" } });
    fireEvent.change(within(w).getByLabelText("Name"), { target: { value: "company-claude" } });
    fireEvent.click(within(w).getByRole("button", { name: "Next" }));
    expect((within(w).getByLabelText("Model") as HTMLInputElement).value).toBe("claude-sonnet-4-5");
    fireEvent.click(within(w).getByRole("button", { name: "Next" }));
    fireEvent.change(within(w).getByLabelText("Vault reference"), { target: { value: "ai/anthropic/api_key" } });
    fireEvent.click(within(w).getByRole("button", { name: "Next" }));
    await act(async () => { fireEvent.click(within(w).getByRole("button", { name: "Save connection" })); });
    expect(connections()).toEqual({ default: "company-claude", connections: [
      { name: "company-claude", provider: "anthropic", model: "claude-sonnet-4-5", api_key: { $credential: "ai/anthropic/api_key" } }] });
    await act(async () => { fireEvent.click(within(w).getByRole("button", { name: "Test it" })); await new Promise((r) => setTimeout(r, 500)); });
    expect(await within(w).findByText("Connected. The model answered: OK", {}, { timeout: 3000 })).toBeTruthy();
  });

  const key = (el: HTMLElement, k: string, extra: object = {}) => fireEvent.keyDown(el, { key: k, ...extra });
  const noClip = { clipboardData: { getData: () => "" } };

  it("copies, cuts and pastes steps from the keyboard and the step menu, and takes steps copied in Classic with their variables", async () => {
    const written: string[] = [];
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async (t: string) => { written.push(t); } } });
    render(<App api={fakeServer().api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    key(stepButton(steps, "Open workbook"), "c", { ctrlKey: true });
    fireEvent.click(stepButton(steps, "Write to log"));
    fireEvent.paste(stepButton(steps, "Write to log"), noClip);   // Ctrl+V arrives as a paste event: after the log, in the loop
    expect(stepNames(steps)).toEqual(["Open workbook", "Loop", "Write to log", "Open workbook"]);
    expect(within(steps).getByText("4 steps")).toBeTruthy();
    // the right-click menu acts on the step clicked
    fireEvent.contextMenu(stepButton(steps, "Loop"));
    const menu = await screen.findByRole("menu");
    const names = within(menu).getAllByRole("menuitem").map((i) => i.getAttribute("aria-label"));
    expect(names).toEqual(expect.arrayContaining(["Cut", "Copy", "Paste after, 1 step copied", "Duplicate", "Switch off", "Delete", "Add note", "Fold"]));
    fireEvent.click(within(menu).getByRole("menuitem", { name: "Cut" }));   // the loop goes with both steps in it
    expect(stepNames(steps)).toEqual(["Open workbook"]);
    // the loop (with its step) is now on the clipboard; paste it at the start
    fireEvent.click(within(steps).getByRole("button", { name: "START" }));
    expect(screen.getByText(/actions you click in the library go first/)).toBeTruthy();
    fireEvent.paste(within(steps).getByRole("button", { name: "START" }), noClip);
    expect(stepNames(steps)).toEqual(["Loop", "Write to log", "Open workbook", "Open workbook"]);
    // steps copied in Classic Studio come through the computer's clipboard, with the variables they use
    await act(async () => {});   // the computer's clipboard took the copy, in Classic's shape
    expect(JSON.parse(written[written.length - 1]).ooaSteps.steps[0].action).toBe("core/loop");
    const classic = JSON.stringify({ ooaSteps: { steps: [{ id: "s9", action: "core/log", args: { message: "${total}" } }], variables: [{ name: "total", type: "Number" }] } });
    fireEvent.click(stepButton(steps, "Loop"));   // a block: pasted steps go after it, not into it
    fireEvent.paste(stepButton(steps, "Loop"), { clipboardData: { getData: () => classic } });
    expect(stepNames(steps)).toEqual(["Loop", "Write to log", "Open workbook", "Write to log", "Open workbook"]);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Variables" }));
    expect(await screen.findByText("$total")).toBeTruthy();
    Reflect.deleteProperty(navigator, "clipboard");
  });

  it("extends the selection with Shift and the arrow keys, switches it off together, and clears it with Esc", async () => {
    render(<App api={fakeServer().api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    key(stepButton(steps, "Open workbook"), "ArrowDown", { shiftKey: true });
    key(stepButton(steps, "Open workbook"), "ArrowDown", { shiftKey: true });
    expect(within(steps).getByText(/^3 selected of 3 steps/)).toBeTruthy();
    key(stepButton(steps, "Open workbook"), "ArrowUp", { shiftKey: true });   // gives one back
    expect(within(steps).getByText(/^2 selected of 3 steps/)).toBeTruthy();
    key(stepButton(steps, "Open workbook"), "/", { ctrlKey: true });
    expect(within(steps).getAllByText("OFF")).toHaveLength(2);
    key(stepButton(steps, "Open workbook"), "Escape");
    expect(within(steps).getByText(/^3 steps/)).toBeTruthy();
  });

  it("adds Catch and Finally parts, puts clicked actions in the selected part, and folds blocks", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(within(steps).getByRole("button", { name: "START" }));
    fireEvent.click(screen.getByRole("button", { name: /^Core/ }));
    fireEvent.click(screen.getByRole("button", { name: "Try" }));
    expect(stepNames(steps)[0]).toBe("Try");   // Start selected: the action went first
    expect(within(steps).getByRole("button", { name: "Catch (any error)" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Finally" }));   // in the Try's Parts
    expect(within(steps).getByRole("button", { name: "Finally (always)", pressed: true })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Write to log" }));   // goes into Finally
    fireEvent.click(within(steps).getByRole("button", { name: "Catch (any error)" }));
    fireEvent.change(screen.getByLabelText("Errors it catches"), { target: { value: "FILE.NOT_FOUND, BUSINESS.ERROR" } });
    fireEvent.blur(screen.getByLabelText("Errors it catches"));
    expect(within(steps).getByRole("button", { name: "Catch FILE.NOT_FOUND, BUSINESS.ERROR" })).toBeTruthy();
    // fold the Try: its line shows how many steps it hides
    fireEvent.click(within(steps).getByRole("button", { name: "Fold line 1" }));
    expect(within(steps).getByText("1 step folded")).toBeTruthy();
    expect(within(steps).queryByRole("button", { name: "Finally (always)" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(created.some((c: any) => c.saved?.ui?.collapsed?.length === 1)).toBe(true));
    const saved = (created.filter((c: any) => c.saved).pop() as any).saved;
    expect(saved.steps[0]).toMatchObject({ action: "core/try", catch: [{ codes: ["FILE.NOT_FOUND", "BUSINESS.ERROR"], children: [] }], finally: [{ action: "core/log" }] });
    fireEvent.keyDown(within(steps).getByRole("button", { name: "More" }), { key: "Enter" });
    fireEvent.click(await screen.findByRole("menuitem", { name: "Unfold all" }));
    expect(within(steps).getByRole("button", { name: "Finally (always)" })).toBeTruthy();
  });

  it("adds the best matching action when Enter is pressed in the action search", async () => {
    render(<App api={fakeServer().api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    const search = screen.getByLabelText("Search actions");
    fireEvent.change(search, { target: { value: "close" } });
    key(search, "Enter");
    expect(stepNames(steps)).toEqual(["Open workbook", "Close workbook", "Loop", "Write to log"]);
  });

  it("pastes with Ctrl+V when the browser sends no paste event (Chrome on a step)", async () => {
    render(<App api={fakeServer().api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    key(stepButton(steps, "Open workbook"), "c", { ctrlKey: true });
    key(stepButton(steps, "Open workbook"), "v", { ctrlKey: true });
    await act(async () => { await new Promise((r) => setTimeout(r, 10)); });
    expect(stepNames(steps)).toEqual(["Open workbook", "Open workbook", "Loop", "Write to log"]);
  });
});
