import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { StudioApi } from "../api/client";
import { App } from "./App";

afterEach(() => { cleanup(); localStorage.clear(); });

/** A fake Studio server: a workspace with Finance/Invoices, one agent, the Excel toolkit; records what is created. */
function fakeServer() {
  const created: unknown[] = [];
  const tree = {
    name: "Workspace", path: "", type: "folder", children: [
      { name: "Finance", path: "Finance", type: "folder", children: [
        { name: "Invoices", path: "Finance/Invoices", type: "folder", children: [
          { name: "Invoice intake.flow.json", path: "Finance/Invoices/Invoice intake.flow.json", type: "bot", kind: "TaskBot", modified: "2026-10-29T09:12:00Z" },
          { name: "Invoice check.form.json", path: "Finance/Invoices/Invoice check.form.json", type: "form", modified: "2026-10-28T09:12:00Z" }] }] }],
  };
  const flow = { kind: "TaskBot", id: "invoice_intake", name: "Invoice intake",
    variables: [{ name: "workbook", type: "File", direction: "input" }],
    steps: [{ id: "open", action: "ooa.excel/open", args: { path: "$workbook" } }, { id: "loop", action: "core/loop", children: [{ id: "log", action: "core/log", args: { message: "row" } }] }] };
  const routes: Record<string, (body: any) => unknown> = {
    "GET info": () => ({ product: "Studio", edition: "preview", workspace: "C:/ws", controlRoom: "/" }),
    "GET tree": () => ({ workspace: "C:/ws", tree }),
    "GET packages": () => ({ packages: [{ id: "ooa.excel", name: "Excel", version: "1.0.0", actions: [{ id: "open", name: "Open workbook" }, { id: "close", name: "Close workbook" }] }] }),
    "GET bot": () => ({ path: "x", flow }),
    "GET blueprints": () => ({ blueprints: [
      { id: "excel_rows", name: "Loop through Excel rows", description: "Open a workbook and handle each row.", variables: [{ name: "workbook", type: "File", direction: "input" }],
        steps: [{ id: "s1", action: "ooa.excel/open", args: { path: "$workbook" } }] },
      { id: "needs_sap", name: "SAP order", description: "Needs a toolkit that is not installed.", variables: [], steps: [{ id: "s1", action: "ooa.sap/connect" }] }] }),
    "PUT bot": (b) => { created.push({ saved: b }); return { saved: "x" }; },
    "POST files/bot": (b) => { created.push(b); return { path: b.parent + "/" + b.name + ".flow.json" }; },
    "POST files/folder": (b) => { created.push(b); return { path: b.parent + "/" + b.name }; },
  };
  const f = (async (url: string, init: RequestInit) => {
    const route = url.replace(/^\/api\//, "").replace(/\?.*$/, "");
    const handler = routes[`${init.method} ${route}`];
    if (!handler) return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "No route " + route } }), { status: 404 });
    return new Response(JSON.stringify(handler(init.body ? JSON.parse(init.body as string) : undefined)), { status: 200 });
  }) as unknown as typeof fetch;
  return { api: new StudioApi("/api/", f), created };
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
    expect(within(steps).getAllByRole("button").map((b) => b.querySelector(".st-step-n")?.textContent + " " + b.querySelector("strong")?.textContent))
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
    const start = within(dialog).getByLabelText("Start from") as HTMLSelectElement;
    expect([...start.options].map((o) => o.text)).toEqual(["Blank agent", "Loop through Excel rows"]);
    fireEvent.change(start, { target: { value: "excel_rows" } });
    expect((within(dialog).getByLabelText("Name") as HTMLInputElement).value).toBe("Loop through Excel rows");
    expect(within(dialog).getByText("Open a workbook and handle each row.")).toBeTruthy();
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Create and edit" })); });

    const saved = (created.find((c: any) => c.saved) as any).saved;
    expect(saved.name).toBe("Invoice intake");   // the new agent keeps its own id and name
    expect(saved.description).toBe("Open a workbook and handle each row.");
    expect(saved.steps).toEqual([{ id: "s1", action: "ooa.excel/open", args: { path: "$workbook" } }]);
  });
});
