import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi, type CrUser } from "../../api/cr";
import { HelpProvider } from "../../components";
import { MENU_SECTIONS } from "../../shell/menu";
import { Apps } from "./Apps";

afterEach(cleanup);

const definition = { pages: [{ id: "main", title: "Refunds", components: [
  { id: "h", kind: "heading", text: "Ask for a refund" },
  { id: "c", kind: "input", name: "customer", label: "Customer", type: "text", required: true },
  { id: "run", kind: "button", label: "Refund now", requires: ["customer"], action: { kind: "run_agent" } },
  { id: "list", kind: "records", entity: "Requests", title: "Requests" },
  { id: "r", kind: "result" }] }] };
const app = { id: "app_1", name: "Refunds", description: "Ask for refunds", audience: [], published_by: "cara", updated: "2026-11-06T08:00:00Z" };

function server() {
  const calls: { method: string; url: string; body?: unknown }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    const method = init.method ?? "GET";
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, url, body });
    const ok = (d: unknown) => new Response(JSON.stringify(d), { status: 200 });
    if (url === "/api/apps" && method === "GET") return ok({ apps: [app] });
    if (url === "/api/apps" && method === "POST") return ok({ ...app, id: "app_2", name: body.name });
    if (url === "/api/apps/app_1") return ok({ ...app, definition });
    if (url === "/api/apps/app_1/act") return ok({ run_id: "run_9", message: "Refund started for ACME." });
    if (url === "/api/apps/app_1/runs/run_9") return ok({ status: "success", outputs: { refund_id: "RF-77" } });
    if (url.startsWith("/api/apps/app_1/records/list")) return ok({ records: [{ id: "rec_1", data: { customer: "Globex" } }], total: 1, fields: [{ name: "customer" }] });
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

const person = (permissions: string[]) => ({ id: "1", username: "vic", display_name: "Vic", role: "viewer", permissions }) as unknown as CrUser;

describe("Apps", () => {
  it("is in the Build menu for everyone signed in", () => {
    expect(MENU_SECTIONS.find((s) => s.id === "build")!.pages).toContainEqual({ id: "apps", label: "Apps" });
  });

  it("opens an app, waits for its required field, runs its button and shows the agent's outputs", async () => {
    const { api, calls } = server();
    render(<HelpProvider><Apps api={api} user={person([])} onNotice={() => {}} /></HelpProvider>);
    expect(screen.queryByRole("button", { name: "New app" })).toBeNull();
    fireEvent.click(await screen.findByText("Refunds"));
    expect(await screen.findByText("Ask for a refund")).toBeTruthy();
    expect(await screen.findByText("Globex")).toBeTruthy();
    const go = screen.getByRole("button", { name: "Refund now" }) as HTMLButtonElement;
    expect(go.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Customer (required)"), { target: { value: "ACME" } });
    await act(async () => { fireEvent.click(go); });
    expect(await screen.findByText("Refund started for ACME.")).toBeTruthy();
    expect(screen.getByText("RF-77")).toBeTruthy();
    expect(calls.find((c) => c.url === "/api/apps/app_1/act")!.body).toEqual({ component: "run", fields: { customer: "ACME" } });
  });

  it("lets app makers make an app from components", async () => {
    const { api, calls } = server();
    render(<HelpProvider><Apps api={api} user={person(["apps.manage"])} onNotice={() => {}} /></HelpProvider>);
    fireEvent.click(await screen.findByRole("button", { name: "New app" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Requests" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Text" }));
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "Agent runners" }));
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Publish" })); });
    const post = calls.find((c) => c.method === "POST" && c.url === "/api/apps")!.body as { name: string; audience: string[]; definition: { pages: { components: { kind: string }[] }[] } };
    expect(post.name).toBe("Requests");
    expect(post.audience).toEqual(["bot_runner"]);
    expect(post.definition.pages[0].components.map((c) => c.kind)).toEqual(["heading", "input", "button", "result", "text"]);
  });
});
