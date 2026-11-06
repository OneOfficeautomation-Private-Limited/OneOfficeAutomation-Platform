import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CrApi } from "../api/cr";
import { HelpProvider } from "../components";
import { MENU_SECTIONS } from "../shell/menu";
import { EventSubscriptions } from "./EventSubscriptions";

afterEach(cleanup);

const subs = [
  { id: "s1", name: "Runs to ServiceNow", url: "https://hooks.example.com/ooa", events: ["run.finished", "run.failed"], format: "json", filters: ["Finance/"], enabled: true,
    has_secret: true, last_status: "200", last_delivery: "2026-11-05T08:00:00Z", failures: 0 },
  { id: "s2", name: "Logs to Splunk", url: "https://splunk.example.com:8088/services/collector/event", events: ["run.log"], format: "splunk", filters: [], enabled: false,
    has_secret: true, last_status: "503", last_error: "The receiver answered HTTP 503", last_delivery: "2026-11-05T09:00:00Z", failures: 3 }];

function server() {
  const calls: { method: string; url: string; body?: unknown }[] = [];
  let token = false;
  const f = (async (url: string, init: RequestInit) => {
    const method = init.method ?? "GET";
    calls.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined });
    const ok = (b: unknown) => new Response(JSON.stringify(b), { status: 200 });
    if (url === "/api/event-subscriptions" && method === "GET")
      return ok({ subscriptions: subs, types: [], allow_http: false, allow_private: true, metrics_token: token });
    if (url === "/api/event-subscriptions" && method === "POST") return ok({ ...subs[0], id: "s3", secret: "whsec_shown_once" });
    if (url === "/api/event-subscriptions/s1/test") return ok({ ok: true, status: 200, event_id: "evt_1" });
    if (url === "/api/event-subscriptions/s2/test") return ok({ ok: false, status: 503, error: "The receiver answered HTTP 503", event_id: "evt_2" });
    if (url === "/api/metrics-token" && method === "POST") { token = true; return ok({ token: "ooam_secret" }); }
    if (url.startsWith("/api/event-subscriptions/")) return ok({ ok: true });
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

describe("Event subscriptions page", () => {
  it("is in the Administration menu for those who manage events", () => {
    const admin = MENU_SECTIONS.find((s) => s.id === "administration")!;
    expect(admin.pages).toContainEqual({ id: "events", label: "Event subscriptions", perm: "events.manage" });
  });

  it("lists subscriptions with their events, filters and how delivery goes", async () => {
    const { api } = server();
    render(<HelpProvider><EventSubscriptions api={api} onNotice={() => {}} /></HelpProvider>);
    const table = await screen.findByRole("table", { name: "Event subscriptions list" });
    expect(await within(table).findByText("Runs to ServiceNow")).toBeTruthy();
    expect(within(table).getByText("Run finished (any result), Run failed · only Finance/")).toBeTruthy();
    expect(within(table).getByText("Splunk")).toBeTruthy();
    expect(within(table).getByText("3").getAttribute("title")).toBe("The receiver answered HTTP 503");
    expect(within(table).getByText(/^Failed \(503\)/)).toBeTruthy();
    expect(screen.getByText("Settings allow private addresses")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Search subscriptions"), { target: { value: "splunk" } });
    expect(within(table).queryByText("Runs to ServiceNow")).toBeNull();
  });

  it("makes a subscription and shows its secret once", async () => {
    const { api, calls } = server();
    render(<HelpProvider><EventSubscriptions api={api} onNotice={() => {}} /></HelpProvider>);
    await screen.findByText("Runs to ServiceNow");
    fireEvent.click(screen.getByRole("button", { name: "New subscription" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Queue events" } });
    fireEvent.change(within(dialog).getByLabelText("Receiver address"), { target: { value: "https://ipaas.example.com/in" } });
    fireEvent.click(within(within(dialog).getByRole("group", { name: "Work items" })).getByLabelText("Added"));
    fireEvent.click(within(within(dialog).getByRole("group", { name: "Runs" })).getByLabelText("Finished (any result)"));
    fireEvent.change(within(dialog).getByLabelText("Only for these folders or agents"), { target: { value: "Finance/, HR/" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Make the subscription" })); });
    const post = calls.find((c) => c.method === "POST" && c.url === "/api/event-subscriptions")!;
    expect(post.body).toEqual({ name: "Queue events", url: "https://ipaas.example.com/in", format: "json", events: ["queue.item_added"], filters: ["Finance/", "HR/"], enabled: true });
    expect((screen.getByLabelText("Signing secret") as HTMLInputElement).value).toBe("whsec_shown_once");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByLabelText("Signing secret")).toBeNull();
  });

  it("sends a test event and says what the receiver answered; makes the metrics token", async () => {
    const { api } = server();
    let notice = "";
    render(<HelpProvider><EventSubscriptions api={api} onNotice={(t) => { notice = t; }} /></HelpProvider>);
    const table = await screen.findByRole("table", { name: "Event subscriptions list" });
    await within(table).findByText("Runs to ServiceNow");
    fireEvent.keyDown(within(table).getByRole("button", { name: "Actions for Logs to Splunk" }), { key: "Enter" });
    await act(async () => { fireEvent.click(await screen.findByRole("menuitem", { name: "Send a test event" })); });
    expect((await screen.findByRole("alert")).textContent).toContain("was not accepted: The receiver answered HTTP 503");
    fireEvent.keyDown(within(table).getByRole("button", { name: "Actions for Runs to ServiceNow" }), { key: "Enter" });
    await act(async () => { fireEvent.click(await screen.findByRole("menuitem", { name: "Send a test event" })); });
    await vi.waitFor(() => expect(notice).toBe("The receiver of Runs to ServiceNow accepted the test event (HTTP 200)."));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Make a metrics token" })); });
    expect((screen.getByLabelText("Metrics token") as HTMLInputElement).value).toBe("ooam_secret");
    expect(await screen.findByRole("button", { name: "Remove the token" })).toBeTruthy();
  });
});
