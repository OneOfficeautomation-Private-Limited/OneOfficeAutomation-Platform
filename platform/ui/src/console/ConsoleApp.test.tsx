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
const runs = [
  { id: "r1", bot_path: "Finance/Invoice intake.flow.json", version: 3, device_id: "d1", device_name: "Runner A", status: "running", requested_by: "admin", source: "schedule:s1",
    created: "2026-10-30T08:00:00Z", started: "2026-10-30T08:00:05Z", progress: { total: 8, finished: 2, action: "ooa.excel/open" } },
  { id: "r2", bot_path: "HR/Onboard.flow.json", version: 1, device_id: "d1", device_name: "Runner A", status: "queued", requested_by: "vic", source: "manual", created: "2026-10-30T08:01:00Z" },
  { id: "r3", bot_path: "Finance/Invoice intake.flow.json", version: 3, device_id: "d1", device_name: "Runner A", status: "failed", requested_by: "admin", source: "manual",
    created: "2026-10-30T07:00:00Z", started: "2026-10-30T07:00:02Z", finished: "2026-10-30T07:01:02Z", duration_ms: 60000, steps: 3,
    error: JSON.stringify({ code: "EXCEL.NOT_FOUND", message: "No such file" }) },
  { id: "r4", bot_path: "HR/Onboard.flow.json", version: 1, device_id: "d1", device_name: "Runner A", status: "success", requested_by: "vic", source: "trigger:t1",
    created: "2026-10-29T07:00:00Z", started: "2026-10-29T07:00:02Z", finished: "2026-10-29T07:00:32Z", duration_ms: 30000, steps: 5 }];

function fakeServer({ signedIn = null as typeof admin | typeof viewer | null, mfa = false, studio = true, plugins = [] as unknown[], sso = false } = {}) {
  let user = signedIn;
  const calls: { method: string; route: string; body?: unknown; headers?: Record<string, string> }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    const route = url.replace(/^\/api\//, "").replace(/\?.*$/, "");
    const body = init.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method: init.method ?? "GET", route, body, headers: init.headers as Record<string, string> });
    const ok = (v: unknown) => new Response(JSON.stringify(v), { status: 200 });
    const no = (status: number, code: string, message: string) => new Response(JSON.stringify({ error: { code, message } }), { status });
    if (route === "health") return ok(studio ? { status: "ok", studio: "/studio/" } : { status: "ok" });
    if (route === "auth/sso") return ok(sso ? { enabled: true, label: "Sign in with Contoso", saml: { enabled: true, label: "Sign in with SAML" } } : { enabled: false, saml: { enabled: false } });
    if (route === "auth/login") {
      if (body.password !== "right") return no(401, "CR.LOGIN_FAILED", "Wrong user name or password");
      if (mfa) return ok({ mfa_required: true, mfa_token: "m1" });
      user = admin; return ok({ token: "t", user });
    }
    if (route === "auth/mfa") { if (body.code !== "123456") return no(401, "CR.MFA_WRONG_CODE", "Wrong code"); user = admin; return ok({ token: "t", user }); }
    if (!user) return no(401, "CR.UNAUTHENTICATED", "Sign in first");
    if (route === "auth/me") return ok(user);
    if (route === "auth/logout") { user = null; return ok({ ok: true }); }
    if (route === "auth/password") {
      if (body.current !== "temp-1") return no(400, "CR.BAD_PASSWORD", "The current password is wrong");
      user = { ...user, must_change: false } as typeof user; return ok({ ok: true });
    }
    if (route === "auth/mfa/setup") return ok({ secret: "JBSWY3DPEHPK3PXP", otpauth_uri: "otpauth://totp/OOA:admin?secret=JBSWY3DPEHPK3PXP" });
    if (route === "auth/mfa/enable") {
      if (body.code !== "123456") return no(400, "CR.MFA_WRONG_CODE", "Wrong code");
      user = { ...user, mfa_setup_required: false } as typeof user; return ok({ recovery_codes: ["aaaa-1111", "bbbb-2222"] });
    }
    if (route === "dashboard") return ok(dashboard);
    if (route === "plugins") return ok({ plugins });
    if (route === "repo") return ok({ bots: [{ path: "Finance/Invoice intake.flow.json" }, { path: "HR/Onboard.flow.json" }] });
    if (route === "devices") return ok({ devices: [{ id: "d1", name: "Runner A" }] });
    if (route === "runs") {
      const q = new URLSearchParams(url.split("?")[1]);
      calls.push({ method: "GET", route: "runs?" + q.toString() });
      const list = runs.filter((r) => (q.get("filter") === "active" ? ["queued", "running"] : ["success", "failed", "stopped"]).includes(r.status)
        && (!q.get("status") || r.status === q.get("status")));
      return ok({ runs: list.slice(0, Number(q.get("limit") ?? 100)), total: list.length });
    }
    const m = /^runs\/([^/]+)(?:\/(\w+))?$/.exec(route);
    if (m && !m[2]) return ok({ ...runs.find((r) => r.id === m[1]), events: [
      { seq: 1, type: "step.started", stepId: "s1", data: { action: "ooa.excel/open" } },
      { seq: 2, type: "step.failed", stepId: "s1", data: { action: "ooa.excel/open", code: "EXCEL.NOT_FOUND", message: "No such file" } }] });
    if (m && m[2]) return ok({ ok: true });
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

  it("shows Agent Runs as AA's Activity: In progress and Completed, filters, selected runs stopped or run again, columns, details, CSV", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "runs.stop", "runs.start"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Agent Runs" }));
    const live = await screen.findByRole("table", { name: "Runs in progress" });
    expect(await within(live).findByText("Step 3 of 8: ooa.excel/open")).toBeTruthy();
    expect(within(live).getByText("Schedule")).toBeTruthy();
    expect(within(live).getByText("Waiting for the runner")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /In progress/ }).textContent).toContain("2");
    fireEvent.click(within(live).getByLabelText("Select the run of Onboard"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Stop" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "runs/r2/stop" }));
    expect(await screen.findByText("Stop asked for 1 run (after the current step).")).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: /Completed/ }));
    const done = await screen.findByRole("table", { name: "Completed runs" });
    expect(await within(done).findByText("EXCEL.NOT_FOUND: No such file")).toBeTruthy();
    expect(calls.some((c) => c.route.startsWith("runs?") && c.route.includes("filter=history") && c.route.includes("from="))).toBe(true);   // the last 90 days
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "failed" } });
    await waitFor(() => expect(within(screen.getByRole("table", { name: "Completed runs" })).queryByText("Onboard v1")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Columns" }));
    fireEvent.click(within(await screen.findByRole("dialog", { name: "Columns" })).getByLabelText("Duration"));
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(within(screen.getByRole("table", { name: "Completed runs" })).queryByText("Duration")).toBeNull();
    fireEvent.click(within(screen.getByRole("table", { name: "Completed runs" })).getByText("EXCEL.NOT_FOUND: No such file"));
    const details = await screen.findByRole("dialog", { name: "Run of Invoice intake" });
    expect(await within(details).findByText("Failed ooa.excel/open: EXCEL.NOT_FOUND No such file")).toBeTruthy();
    fireEvent.click(within(details).getByRole("button", { name: "Close" }));
    const made: string[] = [];
    URL.createObjectURL = (() => { made.push("csv"); return "blob:x"; }) as typeof URL.createObjectURL;
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Export CSV" })); });
    expect(made).toEqual(["csv"]);
    expect(await screen.findByText("Exported 1 run.")).toBeTruthy();
  });

  it("asks for a new password at the first sign-in, then two-step setup when required, before anything else", async () => {
    const { calls, api } = fakeServer({ signedIn: { ...admin, must_change: true, mfa_setup_required: true } as typeof admin });
    render(<ConsoleApp api={api} />);
    const form = await screen.findByRole("form", { name: "Choose your password" });
    expect(screen.queryByRole("navigation", { name: "Command Center" })).toBeNull();
    fireEvent.change(within(form).getByLabelText("Current password"), { target: { value: "temp-1" } });
    fireEvent.change(within(form).getByLabelText("New password"), { target: { value: "Own-Passw0rd-1" } });
    fireEvent.change(within(form).getByLabelText("New password again"), { target: { value: "Own-Passw0rd-2" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save and continue" }));
    expect(await within(form).findByText("The new passwords do not match.")).toBeTruthy();
    fireEvent.change(within(form).getByLabelText("New password again"), { target: { value: "Own-Passw0rd-1" } });
    await act(async () => { fireEvent.click(within(form).getByRole("button", { name: "Save and continue" })); });
    expect(calls).toContainEqual(expect.objectContaining({ route: "auth/password", body: { current: "temp-1", new: "Own-Passw0rd-1" } }));
    // then two-step sign-in, required by the administrator
    const setup = await screen.findByRole("form", { name: "Set up two-step sign-in" });
    expect(await within(setup).findByText("JBSWY3DPEHPK3PXP")).toBeTruthy();
    fireEvent.change(within(setup).getByLabelText("2. The 6-digit code the app shows"), { target: { value: "123456" } });
    await act(async () => { fireEvent.click(within(setup).getByRole("button", { name: "Turn on" })); });
    const codes = await screen.findByRole("region", { name: "Recovery codes" });
    expect(within(codes).getByText(/aaaa-1111/)).toBeTruthy();
    await act(async () => { fireEvent.click(within(codes).getByRole("button", { name: "I have saved them" })); });
    expect(await screen.findByRole("navigation", { name: "Command Center" })).toBeTruthy();
  });

  it("offers single sign-on on the sign-in page, coming back to the page the person was on", async () => {
    render(<ConsoleApp api={fakeServer({ sso: true }).api} />);
    const form = await screen.findByRole("form", { name: "Sign in" });
    const oidc = await within(form).findByRole("link", { name: "Sign in with Contoso" });
    expect(oidc.getAttribute("href")).toBe("/api/auth/sso/start?return=" + encodeURIComponent(location.pathname + location.hash));
    expect(within(form).getByRole("link", { name: "Sign in with SAML" }).getAttribute("href")).toContain("/api/auth/saml/start?return=");
    expect(within(form).getByLabelText("Password")).toBeTruthy();   // local accounts still sign in with a password
  });
});
