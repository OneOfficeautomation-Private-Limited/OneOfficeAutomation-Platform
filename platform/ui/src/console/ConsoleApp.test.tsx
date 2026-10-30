import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi } from "../api/cr";
import { ConsoleApp } from "./ConsoleApp";

afterEach(() => { cleanup(); localStorage.clear(); });

const admin = { id: "1", username: "admin", display_name: "Asha Admin", role: "admin", roles: ["Administrator"], leads_team: false,
  permissions: ["repo.view", "runs.view", "devices.view", "schedules.view", "triggers.view", "queues.view", "globals.view", "toolkits.manage", "apitasks.manage",
    "analytics.view", "users.manage", "teams.manage", "audit.view", "settings.manage", "connections.manage", "skills.view"] };
const viewer = { id: "2", username: "vic", display_name: "Vic Viewer", role: "viewer", permissions: ["repo.view", "runs.view", "devices.view"] };
const dashboard = { running: 2, queued: 1, success_24h: 40, failed_24h: 3, success_rate_24h: 93, avg_duration_ms_24h: 4200, devices_online: 1, devices_total: 2,
  bots: 12, schedules: 4, queue_items_waiting: 7, hours_saved_30d: 18, open_tasks: 2,
  my_recent_agents: [{ path: "Finance/Invoice intake.flow.json", latest_version: 3, updated: new Date().toISOString() }],
  my_recent_runs: [{ id: "r1", bot_path: "Finance/Invoice intake.flow.json", status: "failed", finished: new Date().toISOString() }],
  days: [{ date: "2026-10-29", success: 5, failed: 1, stopped: 0, active: 0 }, { date: "2026-10-30", success: 3, failed: 0, stopped: 1, active: 1 }] };

/** A Command Center with one session cookie: signed out at first; asks for a code when mfa is on. */
function fakeServer({ signedIn = null as typeof admin | typeof viewer | null, mfa = false, studio = true, plugins = [] as unknown[] } = {}) {
  let user = signedIn;
  const calls: { method: string; route: string; body?: unknown; headers?: Record<string, string> }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    const route = url.replace(/^\/api\//, "").replace(/\?.*$/, "");
    const body = init.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method: init.method ?? "GET", route, body, headers: init.headers as Record<string, string> });
    const ok = (v: unknown) => new Response(JSON.stringify(v), { status: 200 });
    const no = (status: number, code: string, message: string) => new Response(JSON.stringify({ error: { code, message } }), { status });
    if (route === "health") return ok(studio ? { status: "ok", studio: "/studio/" } : { status: "ok" });
    if (route === "auth/login") {
      if (body.password !== "right") return no(401, "CR.LOGIN_FAILED", "Wrong user name or password");
      if (mfa) return ok({ mfa_required: true, mfa_token: "m1" });
      user = admin; return ok({ token: "t", user });
    }
    if (route === "auth/mfa") { if (body.code !== "123456") return no(401, "CR.MFA_WRONG_CODE", "Wrong code"); user = admin; return ok({ token: "t", user }); }
    if (!user) return no(401, "CR.UNAUTHENTICATED", "Sign in first");
    if (route === "auth/me") return ok(user);
    if (route === "auth/logout") { user = null; return ok({ ok: true }); }
    if (route === "dashboard") return ok(dashboard);
    if (route === "plugins") return ok({ plugins });
    return no(404, "CR.NOT_FOUND", "Unknown API route " + route);
  }) as unknown as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

describe("rebuilt Command Center console", () => {
  it("signs in with the two-step code, then shows Home with its figures and the person's recent work", async () => {
    const { api, calls } = fakeServer({ mfa: true });
    render(<ConsoleApp api={api} />);
    const form = await screen.findByRole("form", { name: "Sign in" });
    fireEvent.change(within(form).getByLabelText("User name"), { target: { value: "admin" } });
    fireEvent.change(within(form).getByLabelText("Password"), { target: { value: "wrong" } });
    await act(async () => { fireEvent.click(within(form).getByRole("button", { name: "Sign in" })); });
    expect(await screen.findByText("Wrong user name or password")).toBeTruthy();
    fireEvent.change(within(form).getByLabelText("Password"), { target: { value: "right" } });
    await act(async () => { fireEvent.click(within(form).getByRole("button", { name: "Sign in" })); });
    fireEvent.change(await screen.findByLabelText("Code from your authenticator app"), { target: { value: "123456" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Continue" })); });
    expect(await screen.findByRole("heading", { name: "Home" })).toBeTruthy();
    expect(screen.getByText("Welcome, Asha Admin. Here is what your agents have been doing.")).toBeTruthy();
    const figures = await screen.findByRole("list", { name: "Figures" });
    expect(within(figures).getByText("93%")).toBeTruthy();
    expect(within(figures).getByText("Work items waiting").closest("a")!.getAttribute("href")).toBe("../?page=queues");
    expect(within(figures).getByText("Succeeded (24 h)").closest("a")).toBeNull();   // no page for it
    expect(screen.getAllByText("Invoice intake", { selector: "li strong" })).toHaveLength(2);   // an agent and a run
    expect(screen.getByRole("img", { name: "Runs per day for the last 7 days" })).toBeTruthy();
    // every change carries the header the server needs against cross-site requests
    expect(calls.filter((c) => c.method === "POST").every((c) => c.headers?.["X-OOA-Request"] === "1")).toBe(true);
  });

  it("shows the menu by task; pages not rebuilt yet open in today's console, Studio in its own tab", async () => {
    render(<ConsoleApp api={fakeServer({ signedIn: admin }).api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    for (const s of ["BUILD", "MANAGE", "INSIGHTS", "ADMINISTRATION", "AI"]) expect(within(menu).getByText(s)).toBeTruthy();
    expect(within(menu).getByRole("link", { name: "Work queues" }).getAttribute("href")).toBe("../?page=queues");
    expect(within(menu).getByRole("link", { name: "Agent credential vault" }).getAttribute("href")).toBe("../?page=credentials");
    await waitFor(() => expect(within(menu).getByRole("link", { name: /One Agent Studio/ }).getAttribute("href")).toBe("../studio/"));
    expect(within(menu).getByRole("button", { name: "Home" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: "Today's console" }).getAttribute("href")).toBe("../");
  });

  it("shows only the pages a person may open, and signs out", async () => {
    const { api } = fakeServer({ signedIn: viewer });
    render(<ConsoleApp api={api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    expect(within(menu).getByRole("link", { name: "Runners" })).toBeTruthy();
    expect(within(menu).queryByText("ADMINISTRATION")).not.toBeNull();   // the vault is for everyone
    expect(within(menu).queryByRole("link", { name: "Users" })).toBeNull();
    expect(within(menu).queryByText("INSIGHTS")).toBeNull();   // no page in it is open to a viewer
    expect(within(menu).getByRole("button", { name: "Document Automation" })).toBeTruthy();   // AI shows only what a viewer may open
    expect(within(menu).queryByRole("link", { name: "One Model Connections" })).toBeNull();
    fireEvent.keyDown(screen.getByRole("button", { name: "Account: Vic Viewer" }), { key: "Enter" });
    await act(async () => { fireEvent.click(within(await screen.findByRole("menu")).getByRole("menuitem", { name: "Sign out" })); });
    expect(await screen.findByRole("form", { name: "Sign in" })).toBeTruthy();
  });

  it("shows AI > Document Automation as a plug-in page: what it does when not installed, the module's page when it is", async () => {
    render(<ConsoleApp api={fakeServer({ signedIn: admin }).api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    fireEvent.click(within(menu).getByRole("button", { name: "Document Automation" }));
    expect(await screen.findByRole("heading", { name: "Not installed on this Command Center" })).toBeTruthy();
    expect(within(menu).getByRole("button", { name: "Document Automation" }).getAttribute("aria-current")).toBe("page");
    cleanup();
    render(<ConsoleApp api={fakeServer({ signedIn: admin, plugins: [{ id: "document-automation", name: "Document Automation", description: "Learning instances and validation.", path: "/plugins/document-automation/" }] }).api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Document Automation" }));
    expect((await screen.findByRole("link", { name: "Open Document Automation" })).getAttribute("href")).toBe("/plugins/document-automation/");
  });

  it("names a step sign-in still needs, done in today's console", async () => {
    render(<ConsoleApp api={fakeServer({ signedIn: { ...admin, must_change: true } as typeof admin }).api} />);
    expect(await screen.findByText("Change your password before you go on.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open today's console" }).getAttribute("href")).toBe("../");
  });
});
