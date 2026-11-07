import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi, type CrUser } from "../../api/cr";
import { HelpProvider } from "../../components";
import { MENU_SECTIONS } from "../../shell/menu";
import { DataStore } from "./DataStore";

afterEach(cleanup);

const entity = { id: "ent_1", name: "Suppliers", folder: "Finance", fields: [{ name: "name", type: "text", required: true, unique: true },
  { name: "country", type: "choice", choices: ["IN", "US"] }, { name: "active", type: "boolean" }], records: 1, updated: "2026-11-06T08:00:00Z" };
const record = { id: "rec_1", data: { name: "ACME", country: "IN", active: true }, updated: "2026-11-06T08:00:00Z", updated_by: "agent:Finance/Post.flow.json for ann" };

function server() {
  const calls: { method: string; url: string; body?: unknown }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    const method = init.method ?? "GET";
    const body = init.body && typeof init.body === "string" ? JSON.parse(init.body) : undefined;
    calls.push({ method, url, body });
    const ok = (d: unknown) => new Response(JSON.stringify(d), { status: 200 });
    if (url === "/api/data/entities" && method === "GET") return ok({ entities: [entity] });
    if (url.startsWith("/api/data/entities/ent_1/records") && method === "GET") return ok({ records: [record], total: 1 });
    if (url === "/api/data/entities/ent_1/records" && method === "POST") return ok({ id: "rec_2", data: body.data });
    if (url === "/api/data/buckets" && method === "GET") return ok({ buckets: [{ id: "bkt_1", name: "Invoices", folder: "", max_mb: 25, files: 1, bytes: 2048 }] });
    if (url === "/api/data/buckets/bkt_1/files") return ok({ files: [{ path: "2026/acme.pdf", bytes: 2048, modified: "2026-11-06T08:00:00Z" }] });
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

const person = (permissions: string[]) => ({ id: "1", username: "a", display_name: "A", role: "x", permissions }) as unknown as CrUser;

describe("Data store page", () => {
  it("is in the Manage menu for people who may see it", () => {
    const manage = MENU_SECTIONS.find((s) => s.id === "manage")!;
    expect(manage.pages).toContainEqual({ id: "data", label: "Data store", perm: "data.view" });
  });

  it("lists entities, opens one's records, and adds a record with its fields checked by type", async () => {
    const { api, calls } = server();
    render(<HelpProvider><DataStore api={api} user={person(["data.view", "data.edit"])} onNotice={() => {}} /></HelpProvider>);
    const row = (await screen.findByText("Suppliers")).closest("tr")!;
    expect(within(row).getByText("Finance")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "New entity" })).toBeNull();
    fireEvent.click(within(row).getByText("Suppliers"));
    const rec = (await screen.findByText("ACME")).closest("tr")!;
    expect(within(rec).getByText("Yes")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Add record" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("name (required)"), { target: { value: "Globex" } });
    fireEvent.change(within(dialog).getByLabelText("country"), { target: { value: "US" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Add" })); });
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.body).toEqual({ data: { name: "Globex", country: "US", active: false } });
  });

  it("shows buckets and their files with a download link; no upload without the right to change", async () => {
    const { api } = server();
    render(<HelpProvider><DataStore api={api} user={person(["data.view"])} onNotice={() => {}} /></HelpProvider>);
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Storage buckets" }));
    fireEvent.click(screen.getByRole("tab", { name: "Storage buckets" }));
    const b = (await screen.findByText("Invoices")).closest("tr")!;
    expect(within(b).getByText("2.0 KB")).toBeTruthy();
    fireEvent.click(within(b).getByText("Invoices"));
    const f = (await screen.findByText("2026/acme.pdf")).closest("tr")!;
    expect(within(f).getByText("Download").getAttribute("href")).toBe("/api/data/buckets/bkt_1/file?path=2026%2Facme.pdf");
    expect(screen.queryByRole("button", { name: "Upload" })).toBeNull();
  });
});
