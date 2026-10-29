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
      { id: "ooa.excel", name: "Excel", version: "1.0.0", actions: [{ id: "open", name: "Open workbook" }, { id: "close", name: "Close workbook" },
        { id: "count_rows", name: "Count rows", outputs: { properties: { count: { "x-ooa-type": "Number", "x-ooa-label": "Row count" } } } }] },
      { id: "ooa.browser", name: "Browser", version: "1.0.0", actions: [{ id: "type_text", name: "Type text", inputs: { properties: { text: {} } } },
        { id: "element_action", name: "Element action", inputs: { properties: {
          window: { type: "object", "x-ooa-type": "Dictionary" }, object: { type: "object", "x-ooa-type": "Dictionary" },
          action: { type: "string", enum: ["click", "set_text", "get_selected_index", "select_by_text"] }, value: { "x-ooa-type": "Any" },
          click_method: { type: "string", enum: ["mouse", "javascript"], default: "mouse" }, typing: { type: "string", enum: ["fill", "keystrokes"], default: "fill" },
          timeout_ms: { type: "number", "x-ooa-type": "Number", default: 30000 }, wait_for_page_ms: { type: "number", "x-ooa-type": "Number", default: 0 },
          if_not_found: { type: "string", enum: ["error", "continue"], default: "error" } } },
          "x-ooa-panel": { main: ["window", "object", "action", "value"], more: ["typing", "click_method", "timeout_ms", "wait_for_page_ms", "if_not_found"] } }] },
      { id: "ooa.servicenow", name: "ServiceNow", version: "1.0.0", actions: ["create_record", "get_record", "update_record"].map((id) => ({ id, name: id.replace("_", " "),
        inputs: { properties: { instance: { "x-ooa-type": "String" }, user: { "x-ooa-type": "String" }, password: { "x-ooa-type": "Credential" },
          ...(id === "create_record" ? { table: { "x-ooa-type": "String" } } : {}) } } })) },
      { id: "core", name: "Core", version: "1.0.0", actions: [
        { id: "if", name: "If", inputs: { properties: { condition: { "x-ooa-label": "Condition" } } } },
        { id: "try", name: "Try", inputs: { properties: {} } },
        { id: "set", name: "Assign", inputs: { properties: { variable: {}, value: {} } } },
        { id: "run_bot", name: "Run agent", inputs: { properties: { automation: {}, inputs: {}, output_map: {} } } },
        { id: "loop", name: "Loop", inputs: { properties: { iterator: { "x-ooa-type": "String" }, condition: { "x-ooa-visible-when": { iterator: ["while"] } },
          over: { "x-ooa-visible-when": { iterator: ["list", "table_rows", "dictionary", "dictionary_keys", "dictionary_values"] } },
          count: { type: "number", "x-ooa-type": "Number", minimum: 0, "x-ooa-visible-when": { iterator: ["times"] } },
          start: { type: "number", "x-ooa-type": "Number", default: 0, "x-ooa-visible-when": { iterator: ["times"] } },
          from_index: { type: "number", "x-ooa-type": "Number", minimum: 0, "x-ooa-visible-when": { iterator: ["!", "times", "while"] } },
          as: { "x-ooa-type": "String" }, max_iterations: { type: "number", "x-ooa-type": "Number", default: 100000 } } } },
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
    "POST files/rename": (b) => { created.push({ renamed: b.path, name: b.name }); return { path: b.path.replace(/[^/]+$/, b.name + (b.path.endsWith(".flow.json") ? ".flow.json" : "")) }; },
    "POST files/move": (b) => {
      if (b.to === b.path || b.to.startsWith(b.path + "/")) throw Object.assign(new Error("into"), { api: { code: "WORKSPACE.INTO_ITSELF", message: "A folder cannot be moved into itself" } });
      created.push({ moved: b.path, to: b.to }); return { path: (b.to ? b.to + "/" : "") + b.path.split("/").pop() };
    },
    "POST files/copy": (b) => { created.push({ copied: b.path, to: b.to }); return { path: (b.to ? b.to + "/" : "") + b.path.split("/").pop() }; },
    "POST files/duplicate": (b) => { created.push({ duplicated: b.path }); return { path: b.path.replace(/(\.[a-z]+(\.json)?)$/, " - Copy$1") }; },
    "GET search": () => ({ results: [{ path: "Finance/Invoices/Invoice intake.flow.json", matches: ["description", "uses ooa.excel/open"] }] }),
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
    { path: "Finance/lookup.flow.json", id: "lookup_order", name: "Look up order", variables: [
      { name: "order_id", type: "Number", direction: "input", required: true, description: "The order to look up." },
      { name: "status", type: "String", direction: "output" }] }] });
  routes["GET windows"] = () => ({ windows: [{ title: "Orders - Acme", url: "https://acme.example/orders", browser: "edge", source: "browser" }] });
  routes["GET connections"] = () => ({ connections: [{ name: "snow-prod", package: "ooa.servicenow" }, { name: "sf", package: "ooa.salesforce" }] });
  routes["GET builtins"] = () => ({ sys: { today: "2026-10-30" }, global: { company: "One Office" } });
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
    if (init.method === "PUT" && route === "bot") created.push({ putPath: decodeURIComponent(url.replace(/^.*[?&]path=/, "")) });
    try { return new Response(JSON.stringify(handler(init.body ? JSON.parse(init.body as string) : undefined)), { status: 200 }); }
    catch (e: any) { if (e?.api) return new Response(JSON.stringify({ error: e.api }), { status: 400 }); throw e; }
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

  /** Saves and returns the agent as the server received it. */
  const saveAndGet = async (created: unknown[]) => {
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(created.some((c: any) => c.saved)).toBe(true));
    return (created.filter((c: any) => c.saved).pop() as any).saved;
  };
  const openVariables = async () => { fireEvent.mouseDown(await screen.findByRole("tab", { name: "Variables" })); return screen.findByRole("region", { name: "Inputs" }); };
  const addFromLibrary = (toolkit: RegExp, action: string) => {
    fireEvent.click(screen.getByRole("button", { name: toolkit }));
    fireEvent.click(screen.getByRole("button", { name: action }));
  };

  it("makes a variable in the editor: a bad name is refused, a List gets its items, two ticks make an input and output", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    await openIntake();
    await openVariables();
    fireEvent.click(screen.getByRole("button", { name: "New" }));
    let dialog = await screen.findByRole("dialog", { name: "New variable" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "2nd" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create variable" }));
    expect(within(dialog).getByRole("alert").textContent).toMatch(/starts with a letter/);
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "$codes" } });
    fireEvent.change(within(dialog).getByLabelText("Type"), { target: { value: "List" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Item" }));
    fireEvent.change(within(dialog).getByLabelText("Item 1"), { target: { value: "A1" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Item" }));
    fireEvent.change(within(dialog).getByLabelText("Item 2"), { target: { value: "42" } });
    fireEvent.click(within(dialog).getByLabelText(/Use as input/));
    fireEvent.click(within(dialog).getByLabelText(/Use as output/));
    fireEvent.click(within(dialog).getByRole("button", { name: "Create variable" }));
    const group = await screen.findByRole("region", { name: "Inputs and outputs" });
    expect(within(group).getByText("$codes")).toBeTruthy();
    expect(within(group).getByText(/= A1, 42/)).toBeTruthy();
    // the System and Shared values from the platform are listed too
    expect(screen.getByText("$sys.today")).toBeTruthy();
    expect(screen.getByText("$global.company")).toBeTruthy();
    const saved = await saveAndGet(created);
    expect(saved.variables.find((v: any) => v.name === "codes")).toEqual({ name: "codes", type: "List", direction: "in_out", default: ["A1", 42] });
    // a variable in use asks before it is deleted; renaming it changes every use
    fireEvent.click(screen.getByRole("button", { name: /^\$workbook/ }));
    dialog = await screen.findByRole("dialog", { name: "Variable $workbook" });
    expect(within(dialog).getByText(/updates its 1 use/)).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "book" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    const renamed = await saveAndGet(created);
    expect(renamed.steps[0].args.path).toBe("$book");
  });

  it("puts a variable into a field with {x}, offering the field's type first, and makes a new one from there", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Write to log"));
    fireEvent.click(await screen.findByRole("button", { name: "Insert a variable" }));
    const picker = screen.getByRole("dialog", { name: "Insert a variable" });
    expect(within(picker).getByText("$workbook")).toBeTruthy();
    expect(within(picker).getByText("$sys.today")).toBeTruthy();
    fireEvent.click(within(picker).getByText("$workbook"));
    let saved = await saveAndGet(created);
    expect(saved.steps[1].children[0].args.message).toMatch(/\$workbook/);
    // + New variable from the picker makes it and puts it in the field, as one change
    fireEvent.click(screen.getByRole("button", { name: "Insert a variable" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "Insert a variable" })).getByRole("button", { name: /New variable/ }));
    const dialog = await screen.findByRole("dialog", { name: "New variable" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "note" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create variable" }));
    saved = await saveAndGet(created);
    expect(saved.variables.map((v: any) => v.name)).toContain("note");
    expect(saved.steps[1].children[0].args.message).toMatch(/\$note/);
  });

  it("keeps a Number result only in Number variables, or in a new one named after the result", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    addFromLibrary(/^Excel/, "Count rows");
    const keep = await screen.findByLabelText("Keep Row count in");
    expect([...keep.querySelectorAll("option")].map((o) => o.textContent)).toEqual(["(do not keep)", "+ New Number variable"]);   // $workbook is a File
    fireEvent.change(keep, { target: { value: "\u0000new" } });
    const dialog = await screen.findByRole("dialog", { name: "New variable" });
    expect((within(dialog).getByLabelText("Name") as HTMLInputElement).value).toBe("count");
    expect((within(dialog).getByLabelText("Type") as HTMLSelectElement).value).toBe("Number");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create variable" }));
    const saved = await saveAndGet(created);
    expect(saved.steps[1]).toMatchObject({ action: "ooa.excel/count_rows", out: { count: "count" } });
    expect(saved.variables).toContainEqual({ name: "count", type: "Number" });
  });

  it("types text from a Credential variable, a variable, plain text or a calculation", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    addFromLibrary(/^Browser/, "Type text");
    const from = await screen.findByRole("radiogroup", { name: "Text comes from" });
    expect(within(from).getAllByRole("radio").map((r) => r.textContent)).toEqual(["Credential", "Variable", "Text", "Calculation"]);
    expect(within(from).getByRole("radio", { name: "Text" }).getAttribute("aria-checked")).toBe("true");
    fireEvent.click(within(from).getByRole("radio", { name: "Credential" }));
    expect([...screen.getByLabelText("Credential variable").querySelectorAll("option")].map((o) => o.textContent)).toEqual(["Choose a credential variable…"]);
    fireEvent.click(screen.getByRole("button", { name: "New" }));
    const dialog = await screen.findByRole("dialog", { name: "New variable" });
    expect((within(dialog).getByLabelText("Type") as HTMLSelectElement).value).toBe("Credential");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create variable" }));
    const saved = await saveAndGet(created);
    expect(saved.steps[1]).toMatchObject({ action: "ooa.browser/type_text", args: { text: "$password" } });
    expect(saved.variables).toContainEqual({ name: "password", type: "Credential", direction: "input", description: "From the vault: map it to a credential when running." });
  });

  it("runs another agent: lists its inputs, keeps numbers as numbers, and saves an output in a new variable", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    addFromLibrary(/^Core/, "Run agent");
    fireEvent.change(await screen.findByLabelText("Agent to run *"), { target: { value: "lookup_order" } });
    const input = await screen.findByLabelText("Input order_id");
    fireEvent.change(input, { target: { value: "1001" } });
    fireEvent.blur(input);
    fireEvent.change(screen.getByLabelText("Variable for status"), { target: { value: "\u0000new" } });
    fireEvent.click(within(await screen.findByRole("dialog", { name: "New variable" })).getByRole("button", { name: "Create variable" }));
    const saved = await saveAndGet(created);
    expect(saved.steps[1]).toMatchObject({ action: "core/run_bot", args: { automation: "lookup_order", inputs: { order_id: 1001 }, output_map: { status: "status" } } });
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

  const openMenu = async (name: string) => { fireEvent.keyDown(await screen.findByRole("button", { name }), { key: "Enter" }); return screen.findByRole("menu"); };
  const goToFolder = async (...names: string[]) => {
    const tree = await screen.findByRole("tree", { name: "Folders" });
    for (const n of names) { fireEvent.click(await within(tree).findByText(n)); fireEvent.keyDown(within(tree).getByText(n), { key: "ArrowRight" }); }
    return tree;
  };
  const row = (name: string) => screen.getAllByRole("row").find((r) => r.querySelector("td")?.textContent?.startsWith(name))!;

  it("deletes an item and a folder to the trash after asking", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    fireEvent.change(await screen.findByLabelText("Search items"), { target: { value: "clean" } });
    fireEvent.click(within(await openMenu("Actions for clean.py")).getByRole("menuitem", { name: "Delete" }));
    const ask = await screen.findByRole("dialog", { name: "Delete clean.py?" });
    expect(ask.textContent).toMatch(/workspace's .trash folder/);
    fireEvent.click(within(ask).getByRole("button", { name: "Cancel" }));
    expect(created.some((c: any) => c.deleted)).toBe(false);
    fireEvent.contextMenu(row("clean.py"));
    fireEvent.click(within(await screen.findByRole("menu")).getByRole("menuitem", { name: "Delete" }));
    await act(async () => { fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Move to trash" })); });
    expect(created).toContainEqual({ deleted: "Finance/Invoices/clean.py" });
    expect(screen.getByText("Moved clean.py to the workspace trash.")).toBeTruthy();

    // a folder: from the folder menu (⋮ next to Folders, or a right-click on the folder)
    fireEvent.change(screen.getByLabelText("Search items"), { target: { value: "" } });
    fireEvent.click(await within(await screen.findByRole("tree", { name: "Folders" })).findByText("Finance"));
    fireEvent.click(within(await openMenu("Folder actions for Finance")).getByRole("menuitem", { name: "Delete" }));
    const ask2 = await screen.findByRole("dialog", { name: "Delete folder Finance?" });
    await act(async () => { fireEvent.click(within(ask2).getByRole("button", { name: "Move to trash" })); });
    expect(created).toContainEqual({ deleted: "Finance" });
  });

  it("renames an open agent from its menu, keeping its type, and the open tab follows it", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    await goToFolder("Finance", "Invoices");
    fireEvent.click(await screen.findByRole("button", { name: "Invoice intake" }));   // opens it in a tab
    await screen.findByRole("region", { name: "Agent steps" });
    fireEvent.click(screen.getByRole("button", { name: "Expand menu" }));   // the menu folds while an agent is open
    fireEvent.click(screen.getByRole("button", { name: "Automation" }));
    await goToFolder("Finance", "Invoices");
    fireEvent.contextMenu(await waitFor(() => row("Invoice intake")));
    fireEvent.click(within(await screen.findByRole("menu")).getByRole("menuitem", { name: /^Rename/ }));
    const dialog = await screen.findByRole("dialog", { name: "Rename Invoice intake" });
    expect(within(dialog).getByText(/It stays an agent/)).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText("New name"), { target: { value: "Intake 2026" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Rename" })); });
    expect(created).toContainEqual({ renamed: "Finance/Invoices/Invoice intake.flow.json", name: "Intake 2026" });
    expect(screen.getByText("Renamed to Intake 2026.flow.json.")).toBeTruthy();
    // the open tab now saves to the new file
    fireEvent.click(screen.getByRole("tab", { name: /Invoice intake/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Save" }));
    await waitFor(() => expect(created.some((c: any) => c.putPath === "Finance/Invoices/Intake 2026.flow.json")).toBe(true));
  });

  it("selects several items, copies and pastes them into another folder, and duplicates one", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    await goToFolder("Finance", "Invoices");
    fireEvent.click((await waitFor(() => row("Invoice check"))).querySelectorAll("td")[1]);
    fireEvent.click(row("Approve").querySelectorAll("td")[1], { ctrlKey: true });
    const bar = screen.getByRole("toolbar", { name: "Selected items" });
    expect(within(bar).getByText("2 selected")).toBeTruthy();
    fireEvent.click(within(bar).getByRole("button", { name: "Copy" }));
    fireEvent.click(within(screen.getByRole("tree", { name: "Folders" })).getByText("Finance"));
    await act(async () => { fireEvent.click(within(bar).getByRole("button", { name: "Paste 2 items" })); });
    expect(created).toContainEqual({ copied: "Finance/Invoices/Invoice check.form.json", to: "Finance" });
    expect(created).toContainEqual({ copied: "Finance/Invoices/Approve.process.json", to: "Finance" });
    expect(screen.getByText("Pasted 2 items into Finance.")).toBeTruthy();
    // Shift+click selects a range; Duplicate makes a copy next to each
    await goToFolder("Invoices");
    fireEvent.click((await waitFor(() => row("Invoice intake"))).querySelectorAll("td")[1]);
    fireEvent.click(row("Approve").querySelectorAll("td")[1], { shiftKey: true });
    expect(within(bar).getByText("3 selected")).toBeTruthy();
    fireEvent.click(row("Invoice intake").querySelectorAll("td")[1]);
    await act(async () => { fireEvent.click(within(bar).getByRole("button", { name: "Duplicate" })); });
    expect(created).toContainEqual({ duplicated: "Finance/Invoices/Invoice intake.flow.json" });
  });

  it("moves an item by dragging it onto a folder, and refuses a folder into its own sub-folder", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const tree = await goToFolder("Finance", "Invoices");
    await act(async () => { drag(await waitFor(() => row("clean.py")), within(tree).getByText("Finance")); });
    expect(created).toContainEqual({ moved: "Finance/Invoices/clean.py", to: "Finance" });
    await act(async () => { drag(within(tree).getByText("Finance"), within(tree).getByText("Invoices")); });
    expect(await screen.findByText(/A folder cannot be moved into itself/)).toBeTruthy();
    expect(created.some((c: any) => c.moved === "Finance")).toBe(false);
  });

  it("finds agents by their description and the actions they use, and shows the folders again when the search is cleared", async () => {
    render(<App api={fakeServer().api} />);
    fireEvent.change(await screen.findByLabelText("Search items"), { target: { value: "payment" } });
    expect(await screen.findByText("description, uses open")).toBeTruthy();
    expect(row("Invoice intake").textContent).toContain("Finance/Invoices");
    fireEvent.change(screen.getByLabelText("Search items"), { target: { value: "" } });
    expect(await waitFor(() => row("Finance"))).toBeTruthy();   // the workspace's folders again
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

  it("sets a Loop by its kind: N times shows the count with its limit behind an ⓘ, For each what it goes through", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Loop"));
    const kind = await screen.findByRole("radiogroup", { name: "Kind of loop" });
    expect(within(kind).getAllByRole("radio").map((r) => r.textContent)).toEqual(["N times", "For each", "While"]);
    expect(within(kind).getByRole("radio", { name: "For each" }).getAttribute("aria-checked")).toBe("true");
    expect([...screen.getByLabelText("Loop type").querySelectorAll("option")].map((o) => o.getAttribute("value")))
      .toEqual(["table_rows", "list", "dictionary", "dictionary_keys", "dictionary_values"]);
    expect(screen.getByLabelText("Start at position")).toBeTruthy();   // shown unless N times or While
    fireEvent.click(within(kind).getByRole("radio", { name: "N times" }));
    expect(await screen.findByLabelText("Number of times *")).toBeTruthy();
    expect(screen.queryByLabelText("Start at position")).toBeNull();
    expect(screen.queryByLabelText("Loop type")).toBeNull();
    const about = screen.getByRole("button", { name: "About Number of times" });
    fireEvent.click(about);
    expect(screen.getByRole("note").textContent).toMatch(/Smallest value: 0/);
    const count = screen.getByLabelText("Number of times *");
    fireEvent.change(count, { target: { value: "$rows - 1" } });   // not a number: kept as a calculation
    fireEvent.blur(count);
    const saved = await saveAndGet(created);
    expect(saved.steps[1].args).toMatchObject({ iterator: "times", count: { expr: "$rows - 1" } });
  });

  it("shows an element action's own fields, its time-out box, and keeps a result in a new variable", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    addFromLibrary(/^Browser/, "Element action");
    const action = await screen.findByLabelText("Action to take on the element");
    // a click: its click options, no value field
    expect(screen.queryByText("Text to enter")).toBeNull();
    fireEvent.click(screen.getByText(/^More options/));
    expect(screen.getByLabelText("click method")).toBeTruthy();
    // the time-out has its own box: Basic shows the wait, Advanced adds the page wait and what happens when time runs out
    const box = screen.getByRole("region", { name: "System time-out" });
    expect(within(box).getByLabelText("Wait for the element (milliseconds)")).toBeTruthy();
    expect(within(box).queryByLabelText("When the wait runs out")).toBeNull();
    fireEvent.click(within(box).getByRole("radio", { name: "Advanced" }));
    expect(within(box).getByLabelText("When the wait runs out")).toBeTruthy();
    expect(within(box).getByLabelText("Wait for the page first (milliseconds)")).toBeTruthy();
    // an action that returns something keeps its result in a new variable at once
    fireEvent.change(action, { target: { value: "get_selected_index" } });
    expect((await screen.findByLabelText("Keep Result in") as HTMLSelectElement).value).toBe("selected_index");
    // Set text: the text to enter (from a credential, a variable, text or a calculation) and the typing options
    fireEvent.change(screen.getByLabelText("Action to take on the element"), { target: { value: "set_text" } });
    expect(await screen.findByText("Text to enter")).toBeTruthy();
    expect(screen.getByRole("radiogroup", { name: "Text comes from" })).toBeTruthy();
    expect(screen.getByLabelText("typing")).toBeTruthy();
    const saved = await saveAndGet(created);
    expect(saved.steps[1]).toMatchObject({ action: "ooa.browser/element_action", args: { action: "set_text" }, out: { result: "selected_index" } });
    expect(saved.variables).toContainEqual({ name: "selected_index", type: "Number" });
  });

  it("picks the window of a web action from the open windows, with a case-sensitive title", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    addFromLibrary(/^Browser/, "Element action");
    expect(await screen.findByText(/the browser's current tab/)).toBeTruthy();
    const pick = await screen.findByLabelText("Open window");
    await waitFor(() => expect(pick.querySelectorAll("option").length).toBeGreaterThan(1));
    fireEvent.change(pick, { target: { value: "0" } });
    fireEvent.click(await screen.findByLabelText("Case sensitive"));
    const saved = await saveAndGet(created);
    expect(saved.steps[1].args.window).toEqual({ source: "browser", title: "Orders - Acme", title_match: "equals", case_sensitive: true,
      link: "https://acme.example/orders", browser: "edge" });
  });

  it("lets a connector step use a saved connection instead of its address and sign-in", async () => {
    const { api, created } = fakeServer();
    render(<App api={api} />);
    const steps = await openIntake();
    fireEvent.click(stepButton(steps, "Open workbook"));
    addFromLibrary(/^ServiceNow/, "create record");
    const conn = await screen.findByLabelText("Saved connection");
    await waitFor(() => expect([...conn.querySelectorAll("option")].map((o) => o.textContent)).toContain("snow-prod"));
    expect([...conn.querySelectorAll("option")].map((o) => o.textContent)).not.toContain("sf");   // another toolkit's
    expect(screen.getByLabelText("instance")).toBeTruthy();
    fireEvent.change(conn, { target: { value: "snow-prod" } });
    await waitFor(() => expect(screen.queryByLabelText("instance")).toBeNull());
    expect(screen.getByLabelText("table")).toBeTruthy();   // not supplied by the connection
    fireEvent.click(screen.getByRole("button", { name: "Set one of them on this step instead" }));
    expect(screen.getByLabelText("instance")).toBeTruthy();
    const saved = await saveAndGet(created);
    expect(saved.steps[1]).toMatchObject({ action: "ooa.servicenow/create_record", connection: "snow-prod" });
  });
});
