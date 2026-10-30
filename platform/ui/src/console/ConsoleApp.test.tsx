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
  let logsAsked = false;
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
    if (route === "repo" && url.includes("deleted=true")) return ok({ bots: [{ path: "HR/Old offboard.flow.json", latest_version: 2, deleted_at: "2026-10-20T08:00:00Z", deleted_by: "admin" }] });
    if (route === "repo") {
      const folder = new URLSearchParams(url.split("?")[1]).get("folder");
      const bots = [
        { path: "Finance/Invoice intake.flow.json", bot_id: "inv-1", description: "Reads invoices", latest_version: 3, production_version: 3, access: "manage",
          labels: [{ label: "UAT", version: 3 }], updated: "2026-10-30T08:00:00Z", updated_by: "admin" },
        { path: "HR/Onboard.flow.json", latest_version: 2, production_version: 1, locked_by: "vic", access: "edit", updated: "2026-10-29T08:00:00Z" },
        { path: "Finance/Purchase approval.process.json", latest_version: 2, production_version: 2, access: "manage", updated: "2026-10-28T08:00:00Z" }];
      return ok({ bots: folder ? bots.filter((b) => b.path.startsWith(folder + "/")) : bots });
    }
    if (route === "repo/folders") return ok({ folders: [{ path: "Finance", agents: 2, has_permissions: true, access: "manage" }, { path: "HR", agents: 1, access: "edit" }] });
    if (route === "repo/permissions" && (init.method ?? "GET") === "GET") return ok({ folder: "Finance", permissions: [{ principal: "role:bot_runner", access: "run" }] });
    if (route === "repo/production-requests") return ok({ approval_on: true, requests: url.includes("status=pending") ? [
      { id: "q1", path: "HR/Onboard.flow.json", version: 2, requested_by: "vic", requested: "2026-10-31T07:00:00Z", comment: "New laptop step", status: "pending", can_decide: true }] : [
      { id: "q0", path: "Finance/Invoice intake.flow.json", version: 3, requested_by: "vic", status: "approved", decided_by: "admin", decided: "2026-10-30T08:00:00Z" }] });
    if (route === "repo/versions") return ok({ versions: [{ version: 3, labels: ["UAT"], comment: "Faster", author: "admin", created: "2026-10-30T08:00:00Z" },
      { version: 2, labels: [], comment: "First fix", author: "vic", created: "2026-10-20T08:00:00Z" }] });
    if (route === "repo/bot") return ok({ flow: { id: "inv-1", kind: "agent", steps: url.includes("version=2") ? [{ id: "s1" }] : [{ id: "s1" }, { id: "s2" }],
      variables: [{ name: "vendor", type: "String", direction: "input", required: true, description: "Who sent it" }, { name: "total", type: "Number", direction: "output" }] } });
    if (route === "repo/dependencies") return ok({ path: "Finance/Invoice intake.flow.json", agents: [{ bot_id: "pdf-1", path: "Shared/Read PDF.flow.json", found: true }], called_by: [], toolkits: ["ooa.excel"] });
    if (route === "repo/production") return ok({ production_request: { id: "q2" } });
    if (route === "runs" && init.method === "POST") return ok({ run_id: "r9", version: 3 });
    if (/^repo\/(permissions|recover|delete|move|copy|labels|undo-checkout|folders|folders\/rename)$/.test(route) || /^repo\/production-requests\/q\d\/\w+$/.test(route)) return ok({ ok: true, path: "Finance", agents: 0 });
    if (route === "devices" && init.method === "PATCH") return ok({ updated: (body.ids as string[]).length, changes: 1 });
    if (route === "devices") return ok({ devices: [
      { id: "d1", name: "Runner A", hostname: "PC-A", os: "Windows 11", enabled: true, status: "online", device_type: "single_user", lifespan: "persistent", log_level: "info",
        default_users: ["admin"], running: 1, agent_version: "1.4.0", up_to_date: true, last_seen: new Date().toISOString(), registered: "2026-10-01T08:00:00Z", registered_by: "admin",
        cpu_pct: 93, mem_pct: 40, cpu_limit: 80, mem_limit: 0, over_limit: "CPU use 93% is above its limit of 80%" },
      { id: "d2", name: "Runner B", hostname: "VDI-7", os: "Windows Server 2022", enabled: true, status: "offline", device_type: "multi_user", lifespan: "temporary", log_level: "debug",
        default_users: [], running: 0, agent_version: "1.3.0", up_to_date: false, registered: "2026-10-02T08:00:00Z" }] });
    if (/^devices\/d\d$/.test(route)) return ok({ ok: true });
    if (route === "devices/maintenance") return ok({ updated: (body.ids as string[]).length, runs_stopped: body.mode === "stop" ? 1 : 0 });
    if (/^devices\/d\d\/logs$/.test(route) && init.method === "POST") { logsAsked = true; return ok({ requested: true }); }
    if (/^devices\/d\d\/(logs|screen)$/.test(route)) return ok(logsAsked && route.endsWith("logs") ? { taken: "2026-10-30T09:00:00Z", requested_by: "admin", content: "== Runner log ==\nstarted" } : {});
    if (route === "pools" && init.method === "POST") return ok({ id: "p2" });
    if (route === "users" && init.method === "POST") return ok({ username: body.username, temporary_password: "Temp-9x7" });
    if (route === "users") return ok({ users: [
      { id: "u1", username: "admin", display_name: "Asha Admin", role: "admin", roles: ["admin"], enabled: true, mfa_enabled: true, last_login: new Date().toISOString(), email: "asha@example.com" },
      { id: "u2", username: "vic", display_name: "Vic Viewer", first_name: "Vic", last_name: "Rao", role: "viewer", roles: ["viewer", "bot_runner"], enabled: true,
        locked_until: new Date(Date.now() + 600_000).toISOString() },
      { id: "u3", username: "old", display_name: "Old Hand", role: "bot_runner", roles: ["bot_runner"], enabled: false }] });
    if (route === "users/u2" && (init.method ?? "GET") === "GET") return ok({ id: "u2", username: "vic", display_name: "Vic Viewer", first_name: "Vic", last_name: "Rao", role: "viewer",
      enabled: true, locked_until: new Date(Date.now() + 600_000).toISOString(), further_roles: ["bot_runner"], teams: [{ name: "finance", team_role: "member" }], default_device: null });
    if (/^users\/u\d$/.test(route)) return ok({ ok: true });
    if (/^users\/u\d\/reset-password$/.test(route)) return ok({ temporary_password: "Reset-4k2" });
    if (/^users\/u\d\/(unlock|reset-mfa|default-device)$/.test(route)) return ok({ ok: true });
    if (route === "devices/runasusers") return ok({ users: [{ username: "admin", device_id: "d1", device_name: "Runner A" }] });
    if (/^roles\/[a-z_]+$/.test(route)) return ok({ ok: true });
    if (route === "license" && (init.method ?? "GET") === "GET") return ok({ license: { state: "active", customer: "Acme", edition: "Enterprise", id: "LIC-1", issued: "2026-01-01", expires: "2027-01-01",
      days_left: 62, install_id: "inst-9", seats: { developer: { total: 5, used: 4 }, citizen_developer: { total: 0, used: 0 }, attended_runner: { total: 10, used: 2 }, unattended_runner: { total: 2, used: 1 } } } });
    if (route === "license" && init.method === "POST") return ok({ ok: true });
    if (route === "license/assignments" && init.method === "PUT") return ok({ ok: true });
    if (route === "license/assignments") return ok({ assignments: [{ principal_type: "user", principal: "admin", license_type: "developer" }, { principal_type: "device", principal: "d1", license_type: "unattended_runner" }] });
    if (route === "license/usage") return ok({ seats: { developer: { label: "Developer", total: 5, used: 4, idle: 1 }, unattended_runner: { label: "Unattended Runner", total: 2, used: 1, idle: 0 } },
      holders: [{ name: "Old Hand", principal: "old", license_type: "developer", assigned: "2026-05-01T00:00:00Z", last_active: null, runs_30_days: 0, idle: true },
        { name: "Asha Admin", principal: "admin", license_type: "developer", assigned: "2026-05-01T00:00:00Z", last_active: "2026-10-31T00:00:00Z", runs_30_days: 9, idle: false }],
      trend: [{ snap_day: "2026-10-30", license_type: "developer", used: 4, total: 5 }] });
    if (route === "connections" && (init.method ?? "GET") === "GET") return ok({ connections: [{ name: "company-gpt", provider: "azure_openai", model: "gpt-x", key_ref: "ai/azure/key", allowed_roles: ["bot_creator"],
      monthly_tokens: 1000000, tokens_this_month: 900000, pii_analyzer_url: "https://presidio.internal", content_check: "moderation" }] });
    if (route === "connections/test") return ok({ ok: true, ms: 120, answer: "pong" });
    if (/^connections\//.test(route)) return ok({ ok: true });
    if (route === "skills") return ok({ skills: [{ name: "summarise", owner: "admin", shared: true, version: 2, description: "Short summary" }] });
    if (route === "skills/summarise" && (init.method ?? "GET") === "GET") return ok({ name: "summarise", owner: "admin", shared: true, version: 2,
      definition: { prompt: "Summarise ${text}", output: "text", temperature: 0.2, inputs: [{ name: "text" }] }, versions: [{ version: 2, updated_by: "admin", comment: "shorter" }, { version: 1 }] });
    if (route === "skills/summarise") return ok({ name: "summarise", version: 3 });
    if (route === "governance") return ok({ totals: { calls: 12, input_tokens: 1000, output_tokens: 500, masked_calls: 3, tool_calls: 1, avg_latency_ms: 800 },
      by_model: [{ provider: "azure_openai", model: "gpt-x", calls: 12, input_tokens: 1000, output_tokens: 500 }], by_agent: [{ bot_path: "Finance/Invoice intake.flow.json", calls: 12, tokens: 1500 }],
      recent: [{ ts: "2026-10-31T08:00:00Z", bot_path: "Finance/Invoice intake.flow.json", requested_by: "admin", purpose: "summarise", provider: "azure_openai", model: "gpt-x", input_tokens: 80, output_tokens: 40, masked: '["PERSON"]', prompt_sha256: "abcdef1234567890" }] });
    if (route === "toolkits") return ok({ toolkits: [
      { id: "core", name: "Core", version: "1.0.0", actions: 40, enabled: true, used_by: 9 },
      { id: "excel", name: "Excel", version: "2.1.0", versions: ["2.1.0", "1.4.0"], actions: 30, enabled: true, used_by: 3, publisher: "ONEOFFICEAUTOMATION" },
      { id: "acme.sap", name: "Acme SAP", version: "0.3.0", actions: 5, enabled: false, used_by: 0, custom: true, publisher: "acme" }] });
    if (route === "toolkits/excel/versions") return ok({ installed: "2.1.0", agents: [{ path: "Finance/Invoice intake.flow.json", version: 3, required: "^1.0.0", satisfied: false }] });
    if (route === "toolkits/excel/update-agents") return ok({ results: [{ path: "Finance/Invoice intake.flow.json", status: "updated", version: 4 }] });
    if (/^toolkits\/[\w.]+$/.test(route)) return ok({ ok: true });
    if (route === "saved-connections") return ok({ connections: [{ name: "SNOW prod", package: "servicenow", description: "Incidents", values: { instance_url: "https://acme.service-now.com" },
      credentials: { password: "snow/api/password" }, updated: "2026-10-30T08:00:00Z", updated_by: "admin" }] });
    if (route === "connection-kinds") return ok({ kinds: [{ package: "servicenow", name: "ServiceNow", inputs: [{ name: "instance_url" }, { name: "username" }, { name: "password", credential: true }] },
      { package: "salesforce", name: "Salesforce", inputs: [{ name: "domain" }, { name: "token", credential: true }] }] });
    if (/^saved-connections\//.test(route)) return ok({ ok: true });
    if (route === "globals") return ok({ globals: [
      { name: "reports_folder", value: "D:/Reports", changeable: true, my_value: "C:/Mine", updated: "2026-10-30T08:00:00Z", updated_by: "admin" },
      { name: "max_amount", value: 1000, changeable: false, updated: "2026-10-30T08:00:00Z", updated_by: "admin" }] });
    if (/^globals\/\w+(\/mine)?$/.test(route)) return ok({ ok: true });
    if (route === "workflows/runs") return ok({ runs: [
      { id: "w1", path: "Finance/Purchase approval.process.json", version: 2, status: "waiting", stage: "Manager approval", reference: "PO-42", started_by: "vic", created: "2026-10-31T08:00:00Z", updated: "2026-10-31T08:01:00Z" },
      { id: "w2", path: "Finance/Purchase approval.process.json", version: 2, status: "completed", stage: "Pay", reference: "PO-41", started_by: "vic", created: "2026-10-30T08:00:00Z", finished: "2026-10-30T09:00:00Z" }] });
    if (route === "workflows/runs/w1") return ok({ id: "w1", path: "Finance/Purchase approval.process.json", version: 2, status: "waiting", stage: "Manager approval", reference: "PO-42",
      created: "2026-10-31T08:00:00Z", data: { amount: 1200 }, history: [{ event: "agent_finished", stage: "Check budget", ts: "2026-10-31T08:00:30Z" }, { event: "task_created", stage: "Manager approval", ts: "2026-10-31T08:01:00Z" }],
      tasks: [{ id: "k1", stage: "Manager approval", kind: "approval", title: "Approve PO-42", assignee: "role:admin", status: "open" }] });
    if (route === "workflows/runs/w1/cancel") return ok({ ok: true });
    if (route === "workflows/form") return ok({ form: { fields: [{ id: "amount", type: "number", label: "Amount", required: true }] } });
    if (route === "workflows/start") return ok({ id: "w1", reference: body.reference });
    if (route === "queues") return ok({ queues: [{ id: "q1", name: "Vendor invoices", bot_path: "Finance/Invoice intake.flow.json", pool_id: "p1", max_attempts: 2, sla_minutes: 240,
      processing: false, counts: { new: 3, retry: 1, in_progress: 0, success: 10, failed: 2, review: 1 }, overdue: 1 }] });
    if (route === "queues/q1") return ok({ id: "q1", name: "Vendor invoices", bot_path: "Finance/Invoice intake.flow.json", pool_id: "p1", max_attempts: 2, sla_minutes: 240, processing: false,
      counts: { new: 3, retry: 1, success: 10, failed: 2, review: 1 }, overdue: 1, members: [], model: null });
    if (route === "queues/q1/items") return ok({ total: 2, items: [
      { id: "i1", reference: "INV-1", status: "failed", attempts: 2, priority: 0, created: "2026-10-31T08:00:00Z", data: JSON.stringify({ vendor: "Acme" }), error: JSON.stringify({ code: "PDF.UNREADABLE" }) },
      { id: "i2", reference: "INV-2", status: "review", attempts: 1, priority: 0, created: "2026-10-31T08:05:00Z", data: "{}",
        result: JSON.stringify({ validation: { fields: { total: "120" }, confidence: { total: 0.4 }, min_confidence: 0.8 } }) }] });
    if (/^queues\/q1\/(start|stop|items\/(retry|defer|delete)|items\/i\d\/validate)$/.test(route)) return ok({ ok: true, changed: ["total"] });
    if (route === "triggers" && init.method === "POST") return ok(body.kind === "webhook" ? { id: "t9", url: "/api/hooks/t9", secret: "whsec-1" } : { id: "t8" });
    if (route === "triggers") return ok({ triggers: [
      { id: "g1", name: "New invoices", kind: "file", bot_path: "Finance/Invoice intake.flow.json", device_id: "d1", enabled: true, evaluated_on: "runner", folder: "C:\\In", pattern: "*.pdf", fire_count: 3, last_fired: "2026-10-31T08:00:00Z", created_by: "admin" },
      { id: "g2", name: "Support mail", kind: "email", bot_path: "HR/Onboard.flow.json", device_id: "d1", enabled: true, evaluated_on: "server", status: "error", last_error: "Sign-in failed",
        config: { host: "imap.example.com", username: "support", mail_folder: "INBOX", interval_seconds: 60 }, created_by: "admin" }] });
    if (/^triggers\/g\d\/poll$/.test(route)) return ok({ fired: 2 });
    if (/^triggers\/g\d(\/(pause|resume))?$/.test(route)) return ok({ ok: true });
    if (route === "schedules" && init.method === "POST") return ok({ id: "s9", next_run: "2026-11-01T08:30:00Z" });
    if (route === "schedules") return ok({ schedules: [
      { id: "s1", name: "Nightly invoices", bot_path: "Finance/Invoice intake.flow.json", device_id: "d1", kind: "daily", at_time: "02:00", time_zone: "Asia/Kolkata", enabled: true,
        next_run: "2026-11-01T20:30:00Z", last_run: "2026-10-31T20:30:00Z", created_by: "admin", holiday_calendar: "India 2026" },
      { id: "s2", name: "Hourly onboarding", bot_path: "HR/Onboard.flow.json", pool_id: "p1", kind: "every", every_minutes: 60, time_zone: "UTC", enabled: false, created_by: "vic" }] });
    if (route === "schedules/bulk") return ok({ changed: (body.ids as string[]).length });
    if (/^schedules\/s\d(\/run)?$/.test(route)) return ok(route.endsWith("/run") ? { run_id: "r9" } : { ok: true });
    if (route === "calendars") return ok({ calendars: [{ name: "India 2026", dates: ["2026-08-15", "2026-12-25"] }] });
    if (route === "tasks") return ok({ tasks: url.includes("status=completed") ? [
      { id: "k9", title: "Old approval", kind: "approval", status: "completed", assignee: "role:admin", outcome: "approved", completed_by: "admin", completed: "2026-10-30T10:00:00Z" }] : [
      { id: "k1", title: "Approve invoice 42", kind: "approval", status: "open", assignee: "role:admin", due: "2026-10-01T00:00:00Z", overdue: true, can_act: true, data: { amount: 1200, vendor: "Acme" } },
      { id: "k2", title: "Vendor details", kind: "form", status: "open", assignee: "user:admin", can_act: true,
        form: { fields: [{ id: "iban", type: "text", label: "IBAN", required: true }, { id: "urgent", type: "checkbox", label: "Urgent" }], submitLabel: "Send" } }] });
    if (/^tasks\/k\d\/(claim|release|complete)$/.test(route)) return ok({ ok: true });
    if (route === "settings" && init.method === "PUT") return ok({ ok: true });
    if (route === "settings") return ok({ announcement: "", session_hours: 8, mfa_required: 0, runner_auto_update: 0, switched_off_roles: "", syslog_target: "", mail_smtp_port: 587, public_url: "" });
    if (route === "settings/mail-test") return ok({ to: body.to });
    if (route === "audit") {
      const q = new URLSearchParams(url.split("?")[1]);
      calls.push({ method: "GET", route: "audit?" + q.toString() });
      const all = [
        { seq: 12, ts: "2026-10-31T09:00:00Z", actor: "admin", action: "user.create", object_type: "user", object_id: "ravi", details: JSON.stringify({ role: "bot_creator" }), result: "ok", ip: "10.0.0.5" },
        { seq: 11, ts: "2026-10-31T08:00:00Z", actor: "vic", action: "auth.login", object_type: "user", object_id: "vic", details: null, result: "failed", ip: "10.0.0.9" }];
      const list = all.filter((a) => (!q.get("result") || a.result === q.get("result")) && (!q.get("object_id") || a.object_id === q.get("object_id")));
      return ok({ records: list, total: list.length });
    }
    if (route === "audit/verify") return ok({ valid: true, records: 12 });
    if (route === "audit/export") return new Response("seq,actor\n12,admin\n", { status: 200 });
    if (route === "lockers" && (init.method ?? "GET") === "GET") return ok({ lockers: [
      { name: "finance", description: "Bank and ERP", owners: "admin", consumers: "role:bot_runner", managers: [], participants: ["team:finance"], my_role: "owner", can_manage: true,
        external: null, credentials: [{ name: "erp", attribute: "username", updated: "2026-10-30T08:00:00Z", updated_by: "admin" }, { name: "erp", attribute: "password", updated: "2026-10-30T08:00:00Z", updated_by: "admin" }],
        credential_info: [{ name: "erp", owner: "admin", user_attributes: [], my_values: [] }, { name: "bank", owner: "admin", user_attributes: ["pin"], my_values: [] }] }] });
    if (route === "vault/requests") return ok({ requests: [{ locker: "finance", name: "bank", attribute: "pin", description: "Your bank PIN" }] });
    if (route === "vault/credential" && (init.method ?? "GET") === "GET") return ok({ locker: "finance", name: "erp", owner: "admin", description: "", can_change: true, my_values: [],
      attributes: [{ attribute: "password", user_provided: false, masked: true }, { attribute: "username", user_provided: false, masked: true }] });
    if (route === "vault/credential" || route === "vault/my-value" || /^lockers\//.test(route)) return ok({ ok: true });
    if (route === "teams" && init.method === "POST") return ok({ id: "t2", name: body.name });
    if (route === "teams") return ok({ teams: [
      { id: "t1", name: "finance", description: "Finance people", members: [{ username: "vic", team_role: "lead" }, { username: "old", team_role: "member" }], used_in: ["agent folder Finance", "vault folder Bank"] },
      { id: "t2", name: "ops", description: "", members: [], used_in: [] }] });
    if (/^teams\/t\d(\/members)?$/.test(route)) return ok({ ok: true });
    if (route === "roles") return ok({ permissions: ["repo.view", "repo.checkin", "runs.view", "runs.start", "users.manage", "audit.view"], roles: [
      { name: "admin", builtin: true, permissions: ["repo.view", "repo.checkin", "runs.view", "runs.start", "users.manage", "audit.view"], members: ["admin"], users: 1 },
      { name: "bot_creator", builtin: true, permissions: ["repo.view", "repo.checkin", "runs.view", "runs.start"], members: [], users: 0 },
      { name: "bot_runner", builtin: true, permissions: ["repo.view", "runs.view", "runs.start"], members: ["vic", "old"], users: 2 },
      { name: "viewer", builtin: true, permissions: ["repo.view", "runs.view"], members: ["vic"], users: 1, switched_off: false },
      { name: "auditor", builtin: false, description: "Reads the audit trail", permissions: ["audit.view", "runs.view"], members: [], users: 0, created_by: "admin" }] });
    if (route === "apitasks" && init.method === "POST") return ok({ id: "a2", url: "/api/apitasks/a2/run", token: "tok-new" });
    if (route === "apitasks") return ok({ tasks: [{ id: "a1", name: "Invoice lookup", bot_path: "Finance/Invoice intake.flow.json", pool_id: "p1", timeout_seconds: 60, enabled: true,
      calls: 7, last_used: "2026-10-31T08:00:00Z", url: "/api/apitasks/a1/run", created_by: "admin", created: "2026-10-01T08:00:00Z" }] });
    if (route === "apitasks/a1/token") return ok({ token: "tok-again" });
    if (/^apitasks\/a\d$/.test(route)) return ok({ ok: true });
    if (route === "test-sets" && init.method === "POST") return ok({ id: "x2" });
    if (route === "test-sets") return ok({ sets: [
      { id: "x1", name: "Triage checks", bot_path: "Finance/Invoice intake.flow.json", cases: [{ name: "urgent", inputs: { text: "Down!" }, expect: { status: "success" } }, { name: "calm" }],
        last_run: { id: "tr0", started: "2026-10-30T08:00:00Z", total: 2, passed: 1, status: "done", version: 3 } },
      { id: "x3", name: "Onboarding checks", bot_path: "HR/Onboard.flow.json", cases: [{ name: "new hire" }], last_run: null }] });
    if (route === "test-sets/x1/history") return ok({ runs: [{ id: "tr0", started: "2026-10-30T08:00:00Z", total: 2, passed: 1, status: "done", version: 3 }] });
    if (route === "test-sets/x1/run") return ok({ test_run_id: "tr1", started: 2, cases: 2 });
    if (route === "test-sets/runs/tr1") return ok({ id: "tr1", bot_path: "Finance/Invoice intake.flow.json", version: 3, started: "2026-10-31T09:00:00Z", started_by: "admin", total: 2, passed: 1, status: "done",
      results: [{ case_name: "urgent", status: "done", passed: true }, { case_name: "calm", status: "done", passed: false, detail: "label was high, expected low" }] });
    if (route === "templates") return ok({ can_share: true, categories: ["Finance", "IT"], problem: null, templates: [
      { id: "inv", source: "vendor", name: "Invoice intake", description: "Reads invoices into the ERP", category: "Finance", publisher: "ONEOFFICEAUTOMATION", agents: ["Invoice intake.flow.json", "Read PDF.flow.json"] },
      { id: "pw", source: "region", name: "Password reset", description: "Resets a user's password", category: "IT", publisher: "Contoso", agents: ["Password reset.flow.json"] }] });
    if (route === "templates/install") return ok({ ok: true, folder: body.folder, results: [{ path: body.folder + "/Invoice intake.flow.json", status: "installed" }] });
    if (route === "templates/share") return ok({ ok: true });
    if (route === "pools") return ok({ pools: [{ id: "p1", name: "Finance pool", description: "Month end", devices: ["d1"], created_by: "admin" }] });
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
    expect(within(figures).getByText("Work items waiting").closest("a")!.getAttribute("href")).toBe("#/queues");
    expect(within(figures).getByText("Succeeded (24 h)").closest("a")).toBeNull();   // no page for it
    expect(screen.getAllByText("Invoice intake", { selector: "li strong" })).toHaveLength(2);   // an agent and a run
    expect(screen.getByRole("img", { name: "Runs per day for the last 7 days" })).toBeTruthy();
    // every change carries the header the server needs against cross-site requests
    expect(calls.filter((c) => c.method === "POST").every((c) => c.headers?.["X-OOA-Request"] === "1")).toBe(true);
  });

  it("shows the menu by task; pages not rebuilt yet open in today's console, Studio in its own tab", async () => {
    render(<ConsoleApp api={fakeServer({ signedIn: admin }).api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    for (const s of ["BUILD", "MANAGE", "OPERATIONAL INSIGHTS", "ADMINISTRATION", "AI"]) expect(within(menu).getByText(s)).toBeTruthy();
    expect(within(menu).getByRole("link", { name: "Operations" }).getAttribute("href")).toBe("../?page=operations");
    await waitFor(() => expect(within(menu).getByRole("link", { name: /One AgentStudio/ }).getAttribute("href")).toBe("../studio/"));
    expect(within(menu).getByRole("link", { name: /One AgentStudio/ }).getAttribute("target")).toBe("_blank");   // always a new tab
    expect(within(menu).getByRole("button", { name: "Home" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: "Today's console" }).getAttribute("href")).toBe("../");
  });

  it("shows only the pages a person may open, and signs out", async () => {
    const { api } = fakeServer({ signedIn: viewer });
    render(<ConsoleApp api={api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    expect(within(menu).getByRole("button", { name: "Runner Machines" })).toBeTruthy();
    expect(within(menu).queryByText("ADMINISTRATION")).not.toBeNull();   // the vault is for everyone
    expect(within(menu).queryByRole("link", { name: "Users" })).toBeNull();
    expect(within(menu).queryByText("OPERATIONAL INSIGHTS")).toBeNull();   // no page in it is open to a viewer
    expect(within(menu).getByRole("button", { name: "Document Automation" })).toBeTruthy();   // AI shows only what a viewer may open
    expect(within(menu).queryByRole("link", { name: "Model Connections" })).toBeNull();
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

  it("shows Runners as AA's Devices: search in a chosen field, filters, settings for selected runners, out of service, details, pools", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "devices.manage", "pools.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Runner Machines" }));
    const list = await screen.findByRole("table", { name: "Runners list" });
    expect(await within(list).findByText("Runner B")).toBeTruthy();
    expect(within(list).getByText("Finance pool")).toBeTruthy();
    expect(screen.getByText(/1 of 2 online/)).toBeTruthy();
    // search in a chosen field (AA's search chooser), then a status filter
    fireEvent.change(screen.getByLabelText("Search in"), { target: { value: "os" } });
    fireEvent.change(screen.getByLabelText("Search runners"), { target: { value: "server" } });
    await waitFor(() => expect(within(list).queryByText("Runner A")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "online" } });
    await waitFor(() => expect(within(list).queryByText("Runner B")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    // settings for the selected runners: only what is chosen changes
    fireEvent.click(await within(list).findByLabelText("Select Runner B"));
    fireEvent.click(screen.getByRole("button", { name: "Update settings" }));
    const settings = await screen.findByRole("dialog", { name: "Update 1 runner" });
    fireEvent.change(within(settings).getByLabelText("Log level"), { target: { value: "warning" } });
    await act(async () => { fireEvent.click(within(settings).getByRole("button", { name: "Save" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "devices", body: { ids: ["d2"], log_level: "warning" } }));
    // above its CPU limit: busy, taking no new work
    expect(within(list).getByText("Busy")).toBeTruthy();
    // maintenance: no new work; the runs going on now are stopped (as UiPath's), or finish
    fireEvent.click(await within(list).findByLabelText("Select Runner B"));
    fireEvent.click(screen.getByRole("button", { name: "Maintenance" }));
    const mt = await screen.findByRole("dialog", { name: "Maintenance" });
    fireEvent.click(within(mt).getByLabelText(/Stop the runs going on now/));
    await act(async () => { fireEvent.click(within(mt).getByRole("button", { name: "Start maintenance" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "devices/maintenance", body: { ids: ["d2"], mode: "stop" } }));
    expect(await screen.findByText("Maintenance on 1 runner; stopping 1 run.")).toBeTruthy();
    fireEvent.click(await within(list).findByLabelText("Select Runner B"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Disable" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "devices/d2", body: { enabled: false } }));
    expect(await screen.findByText("Disabled 1 runner.")).toBeTruthy();
    // a runner's details by topic
    fireEvent.click(within(list).getByText("Runner B"));
    const details = await screen.findByRole("dialog", { name: "Runner Runner B" });
    expect(within(details).getByText(/Temporary: removed by itself/)).toBeTruthy();
    expect(within(details).getByText("Update available")).toBeTruthy();
    // its logs on request
    const logs = within(details).getByRole("region", { name: "Logs" });
    await act(async () => { fireEvent.click(within(logs).getByRole("button", { name: "Collect logs" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "devices/d2/logs" }));
    expect(await within(logs).findByRole("button", { name: "Download logs" })).toBeTruthy();
    expect(within(details).getByRole("region", { name: "Screen" })).toBeTruthy();
    fireEvent.change(within(details).getByLabelText("Nickname"), { target: { value: "Night shift" } });
    await act(async () => { fireEvent.click(within(details).getByRole("button", { name: "Save details" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "devices/d2", body: { nickname: "Night shift", description: "" } }));
    fireEvent.click(within(details).getByRole("button", { name: "Close" }));
    // a pool made from the selected runners
    fireEvent.click(await within(list).findByLabelText("Select Runner A"));
    fireEvent.click(screen.getByRole("button", { name: "Make a pool" }));
    const pool = await screen.findByRole("dialog", { name: "New runner pool" });
    expect((within(pool).getByLabelText(/Runner A/) as HTMLInputElement).checked).toBe(true);
    fireEvent.change(within(pool).getByLabelText("Name"), { target: { value: "HR pool" } });
    fireEvent.change(within(pool).getByLabelText(/^Users/), { target: { value: "bot_runner" } });
    await act(async () => { fireEvent.click(within(pool).getByRole("button", { name: "Save" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "pools", body: { name: "HR pool", description: "", devices: ["d1"], owners: [], consumers: ["bot_runner"] } }));
    fireEvent.click(screen.getByRole("tab", { name: /Runner pools/ }));
    expect(within(await screen.findByRole("table", { name: "Runner pools list" })).getByText("1 of 1")).toBeTruthy();
  });

  it("asks the server once at start, with its own client: no request loop", async () => {
    const { calls, api } = fakeServer({ signedIn: admin });
    const real = globalThis.fetch;
    // the console's own client (no api given) uses the page's fetch
    globalThis.fetch = ((url: string, init?: RequestInit) => (api as unknown as { fetcher: typeof fetch }).fetcher(url, init ?? {})) as typeof fetch;
    try {
      render(<ConsoleApp />);
      expect(await screen.findByRole("navigation", { name: "Command Center" })).toBeTruthy();
      await new Promise((r) => setTimeout(r, 300));
      expect(calls.filter((c) => c.route === "auth/me").length).toBe(1);
      expect(calls.filter((c) => c.route === "dashboard").length).toBeLessThanOrEqual(1);
    } finally { globalThis.fetch = real; }
  });

  it("shows Users as AA's Users page: filters, enable or disable selected, details in AA's order with Unlock, create with a one-time password", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Users" }));
    const list = await screen.findByRole("table", { name: "Users list" });
    expect(await within(list).findByText("Locked")).toBeTruthy();
    expect(within(list).getByText("Agent runner")).toBeTruthy();          // roles by their names
    expect(within(list).getByText("Runner A")).toBeTruthy();              // the default runner
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "disabled" } });
    await waitFor(() => expect(within(list).queryByText("vic")).toBeNull());
    fireEvent.click(within(list).getByLabelText("Select old"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Enable" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "users/u3", body: { enabled: true } }));
    expect(await screen.findByText("Enabled 1 user.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    // details: general details, roles, then sign-in and security with Unlock
    fireEvent.click(await within(list).findByText("vic"));
    const d = await screen.findByRole("dialog", { name: "User vic" });
    expect(await within(d).findByText(/finance/)).toBeTruthy();
    fireEvent.change(within(d).getByLabelText("E-mail"), { target: { value: "vic@example.com" } });
    fireEvent.click(within(d).getByLabelText("Agent builder"));
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Save changes" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "users/u2", body: { email: "vic@example.com", further_roles: ["bot_runner", "bot_creator"] } }));
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Unlock" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "users/u2/unlock" }));
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Reset password" })); });
    expect(within(await screen.findByRole("dialog", { name: "Temporary password for vic" })).getByText("Reset-4k2")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    // create a user: the temporary password is shown once
    fireEvent.click(screen.getByRole("button", { name: "Create user" }));
    const c = await screen.findByRole("dialog", { name: "Create user" });
    fireEvent.change(within(c).getByLabelText(/User name/), { target: { value: "ravi" } });
    fireEvent.change(within(c).getByLabelText("Main role"), { target: { value: "bot_creator" } });
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create user" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "users", body: { username: "ravi", display_name: "", role: "bot_creator" } }));
    expect(within(await screen.findByRole("dialog", { name: "Temporary password for ravi" })).getByText("Temp-9x7")).toBeTruthy();
  });

  it("shows Roles as AA's Roles page: kinds, a permission matrix by area, built-in roles read-only and copied, custom roles saved and deleted", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Roles" }));
    const list = await screen.findByRole("table", { name: "Roles list" });
    expect(await within(list).findByText("Agent runner")).toBeTruthy();
    expect(within(list).getAllByText("Built-in")).toHaveLength(4);
    fireEvent.change(screen.getByLabelText("Grants"), { target: { value: "audit.view" } });
    await waitFor(() => expect(within(list).queryByText("Agent runner")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    // a built-in role is read-only; copying it starts a new role with its permissions
    fireEvent.click(await within(list).findByText("Agent runner"));
    const b = await screen.findByRole("dialog", { name: "Built-in role Agent runner" });
    expect((within(b).getByLabelText("Start runs") as HTMLInputElement).disabled).toBe(true);
    expect(within(b).getByText("vic, old")).toBeTruthy();
    fireEvent.click(within(b).getByRole("button", { name: "Copy as a new role" }));
    const c = await screen.findByRole("dialog", { name: "Create role" });
    expect((within(c).getByLabelText("Start runs") as HTMLInputElement).checked).toBe(true);
    fireEvent.change(within(c).getByLabelText(/^Name/), { target: { value: "night_runner" } });
    fireEvent.click(within(c).getByLabelText("Check in agents"));
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create role" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "roles/night_runner",
      body: { description: "Copy of Agent runner", permissions: ["repo.view", "runs.view", "runs.start", "repo.checkin"] } }));
    // custom roles can be selected and deleted
    fireEvent.click(await within(list).findByLabelText("Select auditor"));
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    await act(async () => { fireEvent.click(within(await screen.findByRole("dialog", { name: "Delete roles" })).getByRole("button", { name: "Delete" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "DELETE", route: "roles/auditor" }));
  });

  it("shows Teams as UiPath's groups: leads, members and where each team is used; members changed, a team created", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Teams" }));
    const list = await screen.findByRole("table", { name: "Teams list" });
    expect(await within(list).findByText("agent folder Finance, vault folder Bank")).toBeTruthy();
    expect(within(list).getByText("team:finance")).toBeTruthy();
    fireEvent.click(within(list).getByText("finance"));
    const d = await screen.findByRole("dialog", { name: "Team finance" });
    fireEvent.change(within(d).getByLabelText("Person to add"), { target: { value: "admin" } });
    fireEvent.click(within(d).getByRole("button", { name: "Add" }));
    fireEvent.click(within(within(d).getByRole("table", { name: "Members" }).querySelectorAll("tr")[2] as HTMLElement).getByRole("button", { name: "Remove" }));
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Save changes" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "teams/t1/members", body: { members: [{ username: "vic", role: "lead" }, { username: "admin", role: "member" }] } }));
    fireEvent.click(screen.getByRole("button", { name: "Create team" }));
    const c = await screen.findByRole("dialog", { name: "Create team" });
    fireEvent.change(within(c).getByLabelText(/^Name/), { target: { value: "hr" } });
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create team" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "teams", body: { name: "hr", description: "" } }));
  });

  it("shows the Credential vault as AA's: credentials, vault folders, my requests; flags saved, a credential created, members changed", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "lockers.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Credential vault" }));
    const list = await screen.findByRole("table", { name: "Credentials list" });
    expect(await within(list).findByText("pin (yours missing)")).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Needs my own value"));
    await waitFor(() => expect(within(list).queryByText("erp")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    // a credential: clear Masked on the user name, so owners can look it up
    fireEvent.click(await within(list).findByText("erp"));
    const d = await screen.findByRole("dialog", { name: "Credential finance/erp" });
    fireEvent.click(await within(d).findByLabelText("Masked: username"));
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Save details" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "vault/credential", body: expect.objectContaining({
      attributes_info: { password: { description: "", masked: true, password_only: false }, username: { description: "", masked: false, password_only: false } } }) }));
    fireEvent.click(within(d).getByRole("button", { name: "Close" }));
    // create a credential: a shared user name and each person's own password
    fireEvent.click(screen.getByRole("button", { name: "Create credential" }));
    const c = await screen.findByRole("dialog", { name: "Create credential" });
    fireEvent.change(within(c).getByLabelText("Credential name"), { target: { value: "crm" } });
    fireEvent.change(within(c).getByLabelText("Value of attribute 1"), { target: { value: "svc-crm" } });
    fireEvent.click(within(c).getByLabelText("Attribute 2 is each person's own"));
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create credential" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "lockers/finance/crm/username", body: { value: "svc-crm" } }));
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "vault/credential", body: { description: "", user_attributes: ["password"] } }));
    // vault folders: members by place
    fireEvent.click(screen.getByRole("tab", { name: /Vault folders/ }));
    fireEvent.click(within(await screen.findByRole("table", { name: "Vault folders list" })).getByText("finance"));
    const f = await screen.findByRole("dialog", { name: "Vault folder finance" });
    fireEvent.change(within(f).getByLabelText(/^Managers/), { target: { value: "user:vic" } });
    await act(async () => { fireEvent.click(within(f).getByRole("button", { name: "Save members" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "lockers/finance/members",
      body: { owners: "admin", managers: "user:vic", participants: "team:finance", consumers: "role:bot_runner" } }));
    fireEvent.click(within(f).getByRole("button", { name: "Close" }));
    // my requests: a person's own value
    fireEvent.click(screen.getByRole("tab", { name: /My requests/ }));
    fireEvent.change(await screen.findByLabelText("Your pin for finance/bank"), { target: { value: "1234" } });
    await act(async () => { fireEvent.click(within(screen.getByRole("table", { name: "My requests list" })).getByRole("button", { name: "Save" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "vault/my-value", body: { locker: "finance", name: "bank", attribute: "pin", value: "1234" } }));
  });

  it("shows the Audit Trail as AA's audit log: last 24 hours first, filters, a record in full, related records, export, history check", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Audit Trail" }));
    const list = await screen.findByRole("table", { name: "Audit records" });
    expect(await within(list).findByText("role: bot_creator")).toBeTruthy();
    expect(calls.some((c) => c.route.startsWith("audit?") && c.route.includes("from="))).toBe(true);   // the last 24 hours
    fireEvent.change(screen.getByLabelText("Result"), { target: { value: "failed" } });
    await waitFor(() => expect(within(list).queryByText("user.create")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    fireEvent.click(await within(list).findByText("user.create"));
    const d = await screen.findByRole("dialog", { name: "Audit record 12" });
    expect(within(d).getByText("10.0.0.5")).toBeTruthy();
    fireEvent.click(within(d).getByRole("button", { name: "Every record about this user" }));
    await waitFor(() => expect(calls.some((c) => c.route.startsWith("audit?") && c.route.includes("object_id=ravi"))).toBe(true));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Check history" })); });
    expect(await screen.findByText("History intact: 12 records checked.")).toBeTruthy();
    URL.createObjectURL = (() => "blob:x") as typeof URL.createObjectURL;
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Export CSV" })); });
    expect(calls.some((c) => c.route === "audit/export")).toBe(true);
  });

  it("shows Licenses as AA's: the licence and seats with a nearly-full alert, allocation to people and runners, idle seats taken back", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Licenses" }));
    expect(await screen.findByText("Acme")).toBeTruthy();
    expect(screen.getByText(/Nearly full: Developer \(4 of 5\)/)).toBeTruthy();
    // allocation: a seat for a person; the runner already holds one
    fireEvent.click(screen.getByRole("tab", { name: "Allocation" }));
    const people = await screen.findByRole("table", { name: "People seats" });
    await act(async () => { fireEvent.click(within(people).getByLabelText("Attended Runner seat for vic")); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "license/assignments", body: { license_type: "attended_runner", principal: "vic", assigned: true } }));
    expect((within(screen.getByRole("table", { name: "Runner seats" })).getByLabelText("Unattended Runner seat for Runner A") as HTMLInputElement).checked).toBe(true);
    // usage: idle seats only, and take one back
    fireEvent.click(screen.getByRole("tab", { name: "Usage" }));
    fireEvent.click(await screen.findByLabelText("Idle seats only"));
    const holders = screen.getByRole("table", { name: "Seat holders" });
    await waitFor(() => expect(within(holders).queryByText("Asha Admin")).toBeNull());
    await act(async () => { fireEvent.click(within(holders).getByRole("button", { name: "Take back" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "license/assignments", body: { license_type: "developer", principal: "old", assigned: false } }));
  });

  it("shows Settings as AA's: sections, only the changed values saved, a search across settings, a section's own test", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Settings" }));
    const nav = await screen.findByRole("navigation", { name: "Settings sections" });
    fireEvent.click(within(nav).getByRole("button", { name: "Sign-in and security" }));
    const sec = screen.getByRole("region", { name: "Sign-in and security" });
    fireEvent.change(within(sec).getByLabelText("Sign-in session length (hours)"), { target: { value: "10" } });
    fireEvent.change(within(sec).getByLabelText("Two-step sign-in"), { target: { value: "1" } });
    fireEvent.click(within(sec).getByLabelText("Switch off Viewer"));
    expect(within(nav).getByTitle("Changed, not saved yet").textContent).toBe("3");
    await act(async () => { fireEvent.click(within(sec).getByRole("button", { name: "Save Sign-in and security" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "settings", body: { session_hours: 10, mfa_required: 1, switched_off_roles: "viewer" } }));
    // search across every section
    fireEvent.change(screen.getByLabelText("Search settings"), { target: { value: "syslog" } });
    const found = await screen.findByRole("region", { name: "Settings found" });
    expect(within(found).getByLabelText(/Syslog target/)).toBeTruthy();
    fireEvent.click(within(found).getAllByRole("button", { name: "E-mail, proxy and SIEM" })[0]);
    const mail = await screen.findByRole("region", { name: "E-mail, proxy and SIEM" });
    fireEvent.change(within(mail).getByLabelText("Send a test e-mail to"), { target: { value: "me@example.com" } });
    await act(async () => { fireEvent.click(within(mail).getByRole("button", { name: "Send a test e-mail" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "settings/mail-test", body: { to: "me@example.com" } }));
  });

  it("shows the Task inbox as UiPath's Action Center: to do and done, overdue filter, approve with a comment, a form with a required field", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Task inbox" }));
    const list = await screen.findByRole("table", { name: "Tasks to do" });
    expect(await within(list).findByText(/Overdue:/)).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Overdue only"));
    await waitFor(() => expect(within(list).queryByText("Vendor details")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    // an approval: the workflow's data, a comment, Approve
    fireEvent.click(within(list).getByText("Approve invoice 42"));
    const a = await screen.findByRole("dialog", { name: "Approve invoice 42" });
    expect(within(a).getByText("Acme")).toBeTruthy();
    fireEvent.change(within(a).getByLabelText("Comment (optional)"), { target: { value: "ok by me" } });
    await act(async () => { fireEvent.click(within(a).getByRole("button", { name: "Approve" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "tasks/k1/complete", body: { outcome: "approved", answers: {}, comment: "ok by me" } }));
    // a form: the required field first
    fireEvent.click(await within(list).findByText("Vendor details"));
    const fm = await screen.findByRole("dialog", { name: "Vendor details" });
    fireEvent.click(within(fm).getByRole("button", { name: "Send" }));
    expect(within(fm).getByText("Fill in: IBAN")).toBeTruthy();
    fireEvent.change(within(fm).getByLabelText("IBAN *"), { target: { value: "DE89" } });
    await act(async () => { fireEvent.click(within(fm).getByRole("button", { name: "Send" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "tasks/k2/complete", body: { outcome: "submitted", answers: { iban: "DE89", urgent: false }, comment: "" } }));
    fireEvent.click(screen.getByRole("tab", { name: /Done/ }));
    expect(await within(await screen.findByRole("table", { name: "Tasks done" })).findByText("Approved")).toBeTruthy();
  });

  it("shows Schedules as AA's: what runs where and when, enable selected, a schedule copied for new timing, a weekly one created in AA's order", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "schedules.manage", "runs.start"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Schedules" }));
    const list = await screen.findByRole("table", { name: "Schedules list" });
    expect(await within(list).findByText("Daily at 02:00 (Asia/Kolkata), not on India 2026")).toBeTruthy();
    expect(within(list).getByText("Pool Finance pool")).toBeTruthy();
    fireEvent.click(within(list).getByLabelText("Select Hourly onboarding"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Enable" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "schedules/bulk", body: { ids: ["s2"], action: "enable" } }));
    // a schedule: its timing changes by a copy
    fireEvent.click(within(list).getByText("Nightly invoices"));
    const e = await screen.findByRole("dialog", { name: "Schedule Nightly invoices" });
    fireEvent.click(within(e).getByRole("button", { name: "Copy as a new schedule" }));
    const c = await screen.findByRole("dialog", { name: "Copy of Nightly invoices" });
    fireEvent.change(within(c).getByLabelText("Repeat"), { target: { value: "weekly" } });
    fireEvent.click(within(c).getByLabelText("Fri"));
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create schedule" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "schedules", body: expect.objectContaining({
      name: "Nightly invoices (copy)", bot_path: "Finance/Invoice intake.flow.json", kind: "weekly", at_time: "02:00", days: "MON,TUE,WED,THU", device_id: "d1", pool_id: null, holiday_calendar: "India 2026" }) }));
  });

  it("shows Triggers as AA's event triggers: where each listens, its health, Check now; a webhook created shows its secret once", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "triggers.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Triggers" }));
    const list = await screen.findByRole("table", { name: "Triggers list" });
    expect(await within(list).findByText("The Command Center")).toBeTruthy();
    expect(within(list).getByText("Error")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Health"), { target: { value: "error" } });
    await waitFor(() => expect(within(list).queryByText("New invoices")).toBeNull());
    await act(async () => { fireEvent.click(within(list).getByRole("button", { name: "Check now" })); });
    expect(await screen.findByText("Checked Support mail: 2 new.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    fireEvent.click(screen.getByRole("button", { name: "Create trigger" }));
    const c = await screen.findByRole("dialog", { name: "Create trigger" });
    fireEvent.change(within(c).getByLabelText("Event"), { target: { value: "webhook" } });
    fireEvent.change(within(c).getByLabelText("Name"), { target: { value: "Orders hook" } });
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create trigger" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "triggers", body: { name: "Orders hook", bot_path: "Finance/Invoice intake.flow.json", device_id: "d1", kind: "webhook" } }));
    const hook = await screen.findByRole("dialog", { name: "Webhook created" });
    expect(within(hook).getByText("whsec-1")).toBeTruthy();
  });

  it("shows Work queues as AA's: counts, items past the service level, retry failed items, validate an unsure item, start processing", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "queues.work", "queues.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Work queues" }));
    const list = await screen.findByRole("table", { name: "Work queues list" });
    expect(await within(list).findByText("1 past it")).toBeTruthy();
    expect(within(list).getByText("4")).toBeTruthy();   // waiting: new and retry
    fireEvent.click(within(list).getByText("Vendor invoices"));
    const view = await screen.findByRole("dialog", { name: "Work queue Vendor invoices" });
    const items = await within(view).findByRole("table", { name: "Work items" });
    expect(await within(items).findByText(/PDF.UNREADABLE/)).toBeTruthy();
    fireEvent.click(within(items).getByLabelText("Select INV-1"));
    await act(async () => { fireEvent.click(within(view).getByRole("button", { name: "Retry" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "queues/q1/items/retry", body: { ids: ["i1"] } }));
    // an item the agent was unsure of: correct and approve
    fireEvent.click(within(items).getByText("INV-2"));
    const v = await screen.findByRole("dialog", { name: "Validate INV-2" });
    fireEvent.change(within(v).getByLabelText(/total \(40 %, unsure\)/), { target: { value: "125" } });
    await act(async () => { fireEvent.click(within(v).getByRole("button", { name: "Approve" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "queues/q1/items/i2/validate", body: { fields: { total: "125" } } }));
    await act(async () => { fireEvent.click(within(view).getByRole("button", { name: "Start processing" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "queues/q1/start" }));
  });

  it("shows Workflow as UiPath Maestro's instances: in progress and finished, a run's timeline and tasks, cancel, start with the start form", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "runs.start"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Workflow" }));
    const list = await screen.findByRole("table", { name: "Workflow runs in progress" });
    expect(await within(list).findByText("Waiting for a person")).toBeTruthy();
    expect(within(list).queryByText("PO-41")).toBeNull();
    fireEvent.click(within(list).getByText("PO-42"));
    const run = await screen.findByRole("dialog", { name: "Workflow run PO-42" });
    expect(await within(run).findByText("Approve PO-42")).toBeTruthy();
    expect(within(run).getByText("Task given")).toBeTruthy();
    fireEvent.click(within(run).getByRole("button", { name: "Cancel run" }));
    await act(async () => { fireEvent.click(within(run).getAllByRole("button", { name: "Cancel run" }).at(-1)!); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "workflows/runs/w1/cancel" }));
    fireEvent.click(within(run).getByRole("button", { name: "Close" }));
    fireEvent.click(screen.getByRole("button", { name: "Start a workflow" }));
    const st = await screen.findByRole("dialog", { name: "Start a workflow" });
    fireEvent.change(await within(st).findByLabelText("Amount *"), { target: { value: "500" } });
    fireEvent.change(within(st).getByLabelText(/^Reference/), { target: { value: "PO-43" } });
    await act(async () => { fireEvent.click(within(st).getByRole("button", { name: "Start" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "workflows/start", body: { path: "Finance/Purchase approval.process.json", data: { amount: 500 }, reference: "PO-43" } }));
  });

  it("shows Shared Global values with types, your own value, and a typed value created", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "globals.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Shared Global values" }));
    const list = await screen.findByRole("table", { name: "Shared values list" });
    expect(await within(list).findByText("$global.max_amount")).toBeTruthy();
    expect(within(list).getByText("Number")).toBeTruthy();
    fireEvent.click(within(list).getByRole("button", { name: "My value" }));
    const mine = await screen.findByRole("dialog", { name: "Your value of reports_folder" });
    await act(async () => { fireEvent.click(within(mine).getByRole("button", { name: "Use the shared one" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "DELETE", route: "globals/reports_folder/mine" }));
    fireEvent.click(screen.getByRole("button", { name: "Create value" }));
    const c = await screen.findByRole("dialog", { name: "Create shared value" });
    fireEvent.change(within(c).getByLabelText(/^Name/), { target: { value: "retries" } });
    fireEvent.change(within(c).getByLabelText("Type"), { target: { value: "number" } });
    fireEvent.change(within(c).getByLabelText("Value"), { target: { value: "x" } });
    fireEvent.click(within(c).getByRole("button", { name: "Create value" }));
    expect(within(c).getByText("The value is not a number.")).toBeTruthy();
    fireEvent.change(within(c).getByLabelText("Value"), { target: { value: "3" } });
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create value" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "globals/retries", body: { value: 3, changeable: false } }));
  });

  it("shows Connections with settings and vault references, and creates one by its toolkit's inputs", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "globals.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Connections" }));
    const list = await screen.findByRole("table", { name: "Connections list" });
    expect(await within(list).findByText("password → snow/api/password")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Create connection" }));
    const c = await screen.findByRole("dialog", { name: "Create connection" });
    fireEvent.change(within(c).getByLabelText("Name"), { target: { value: "SF" } });
    fireEvent.change(within(c).getByLabelText("Toolkit"), { target: { value: "salesforce" } });
    fireEvent.change(within(c).getByLabelText("domain"), { target: { value: "acme.my.salesforce.com" } });
    fireEvent.change(within(c).getByLabelText("token (vault reference)"), { target: { value: "sf/api/token" } });
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Create connection" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "saved-connections/SF",
      body: { package: "salesforce", description: "", values: { domain: "acme.my.salesforce.com" }, credentials: { token: "sf/api/token" } } }));
  });

  it("shows Agent ToolKits as AA's Packages: versions and usage, a warning before switching off, agents updated to a new version range", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "repo.checkin"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Agent ToolKits" }));
    const list = await screen.findByRole("table", { name: "Toolkits list" });
    expect(await within(list).findByText("2.1.0, 1.4.0 (default 2.1.0)")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Source"), { target: { value: "own" } });
    await waitFor(() => expect(within(list).queryByText("Excel")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    const excel = (await within(list).findByText("Excel")).closest("tr")!;
    fireEvent.click(within(excel as HTMLElement).getByRole("button", { name: "Switch off" }));
    const off = await screen.findByRole("dialog", { name: "Switch off Excel" });
    expect(within(off).getByText(/3 agents use Excel/)).toBeTruthy();
    await act(async () => { fireEvent.click(within(off).getByRole("button", { name: "Switch off" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "toolkits/excel", body: { enabled: false } }));
    fireEvent.click(within(list).getByText("Excel"));
    const v = await screen.findByRole("dialog", { name: "Toolkit Excel" });
    expect(await within(v).findByText("^1.0.0")).toBeTruthy();
    await act(async () => { fireEvent.click(within(v).getByRole("button", { name: "Update agents" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "toolkits/excel/update-agents", body: { range: "^2.0.0" } }));
  });

  it("shows the AI pages: model connections with budget and test, a prompt skill saved as a new version, AI governance by model and agent", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "skills.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    const nav = await screen.findByRole("navigation", { name: "Command Center" });
    fireEvent.click(within(nav).getByRole("button", { name: "Model Connections" }));
    const mc = await screen.findByRole("table", { name: "Model connections list" });
    expect(await within(mc).findByText("Masks personal data, Checks answers")).toBeTruthy();
    expect(within(mc).getByRole("img", { name: "90% of the budget" })).toBeTruthy();
    fireEvent.click(within(mc).getByText("company-gpt"));
    const d = await screen.findByRole("dialog", { name: "Model connection company-gpt" });
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Test connection" })); });
    expect(await within(d).findByText("Connected in 120 ms: pong")).toBeTruthy();
    fireEvent.click(within(d).getByRole("button", { name: "Cancel" }));
    fireEvent.click(within(nav).getByRole("button", { name: "Prompt Skills" }));
    fireEvent.click(await within(await screen.findByRole("table", { name: "Skills list" })).findByText("summarise"));
    const sk = await screen.findByRole("dialog", { name: "summarise (v2)" });
    expect(within(sk).getByText("The prompt uses: text.")).toBeTruthy();
    fireEvent.change(within(sk).getByLabelText(/^Prompt/), { target: { value: "Summarise ${text} for ${reader}" } });
    await act(async () => { fireEvent.click(within(sk).getByRole("button", { name: "Save as a new version" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "skills/summarise", body: expect.objectContaining({
      definition: { prompt: "Summarise ${text} for ${reader}", output: "text", temperature: 0.2, inputs: [{ name: "text" }, { name: "reader" }] } }) }));
    fireEvent.click(within(nav).getByRole("button", { name: "AI governance" }));
    expect(await screen.findByText("Calls with personal data masked")).toBeTruthy();
    expect(within(screen.getByRole("table", { name: "Recent AI calls" })).getByText("PERSON")).toBeTruthy();
  });

  it("shows Agent APIs as AA's API tasks: where each runs and its calls; a new one shows its token once with an example call", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Agent APIs" }));
    const list = await screen.findByRole("table", { name: "Agent APIs list" });
    expect(await within(list).findByText("Pool Finance pool")).toBeTruthy();
    fireEvent.click(within(list).getByText("Invoice lookup"));
    const d = await screen.findByRole("dialog", { name: "Agent API Invoice lookup" });
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Switch off" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "apitasks/a1", body: { enabled: false } }));
    fireEvent.click(screen.getByRole("button", { name: "Create Agent API" }));
    const n = await screen.findByRole("dialog", { name: "Create Agent API" });
    fireEvent.change(within(n).getByLabelText("Name"), { target: { value: "Lookup" } });
    await waitFor(() => expect((within(n).getByLabelText("Agent (its live version)") as HTMLSelectElement).value).toBe("Finance/Invoice intake.flow.json"));
    fireEvent.click(within(n).getByLabelText("The Command Center (serverless)"));
    await act(async () => { fireEvent.click(within(n).getByRole("button", { name: "Create Agent API" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "apitasks", body: expect.objectContaining({ name: "Lookup", serverless: true, timeout_seconds: 60 }) }));
    const t = await screen.findByRole("dialog", { name: "Agent API Lookup: its token" });
    expect(within(t).getByLabelText("Token").textContent).toBe("tok-new");
    expect(within(t).getByText(/curl -X POST/).textContent).not.toContain("tok-new");   // the example never repeats the token
  });

  it("shows Agent tests as test sets: last score, failing ones, a run on a pool scored case by case", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "runs.start", "repo.checkin"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Agent tests" }));
    const list = await screen.findByRole("table", { name: "Test sets list" });
    expect(await within(list).findByText("1 of 2 passed")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Show"), { target: { value: "never" } });
    await waitFor(() => expect(within(list).queryByText("Triage checks")).toBeNull());
    fireEvent.change(screen.getByLabelText("Show"), { target: { value: "failing" } });
    fireEvent.click(await within(list).findByText("Triage checks"));
    const d = await screen.findByRole("dialog", { name: "Test set Triage checks" });
    expect(await within(within(d).getByRole("table", { name: "History" })).findByText("v3")).toBeTruthy();
    fireEvent.change(within(d).getByLabelText("Run on"), { target: { value: "p:p1" } });
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Run" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "test-sets/x1/run", body: { pool_id: "p1" } }));
    const r = await screen.findByRole("dialog", { name: "Test run" });
    expect(await within(r).findByText("label was high, expected low")).toBeTruthy();
  });

  it("shows Agent Templates as AA's Bot Store: cards by category, install into a folder, an agent shared for approval", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "lifecycle.import"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Agent Templates" }));
    const cards = await screen.findByRole("list", { name: "Templates" });
    expect(await within(cards).findByText("IT · shared by Contoso")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Category"), { target: { value: "Finance" } });
    await waitFor(() => expect(within(cards).queryByText("Password reset")).toBeNull());
    fireEvent.click(within(within(cards).getByRole("listitem", { name: "Invoice intake" })).getByRole("button", { name: "Install" }));
    const i = await screen.findByRole("dialog", { name: "Install Invoice intake" });
    fireEvent.change(within(i).getByLabelText("Folder"), { target: { value: "Finance/From templates" } });
    await act(async () => { fireEvent.click(within(i).getByRole("button", { name: "Install" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "templates/install", body: { source: "vendor", id: "inv", folder: "Finance/From templates" } }));
    fireEvent.click(screen.getByRole("button", { name: "Share an agent" }));
    const sh = await screen.findByRole("dialog", { name: "Share an agent as a template" });
    await waitFor(() => expect((within(sh).getByLabelText("Agent") as HTMLSelectElement).value).toBe("Finance/Invoice intake.flow.json"));
    await act(async () => { fireEvent.click(within(sh).getByRole("button", { name: "Share" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "templates/share", body: { path: "Finance/Invoice intake.flow.json", name: "Invoice intake", description: "", category: "Finance" } }));
  });

  it("shows the Agent Repository as AA's public workspace: a folder tree, folder permissions, an agent's versions compared, a version asked to go live, a run, approvals, recovery", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "repo.checkin", "repo.delete", "repo.permissions", "runs.start"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    fireEvent.click(within(await screen.findByRole("navigation", { name: "Command Center" })).getByRole("button", { name: "Agent Repository" }));
    const list = await screen.findByRole("table", { name: "Agents list" });
    expect(await within(list).findByText("Checked out by vic")).toBeTruthy();
    expect(within(list).getByText("Workflow")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("navigation", { name: "Folders" })).getByRole("button", { name: /^Finance/ }));
    await waitFor(() => expect(within(list).queryByText("Onboard")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Folder permissions" }));
    const perm = await screen.findByRole("dialog", { name: "Permissions of Finance" });
    expect(await within(perm).findByText("Role bot_runner")).toBeTruthy();
    fireEvent.change(within(perm).getByLabelText("Or a user name"), { target: { value: "vic" } });
    fireEvent.change(within(perm).getByLabelText("Access"), { target: { value: "edit" } });
    fireEvent.click(within(perm).getByRole("button", { name: "Add" }));
    await act(async () => { fireEvent.click(within(perm).getByRole("button", { name: "Save" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "repo/permissions", body: { folder: "Finance",
      permissions: [{ principal: "role:bot_runner", access: "run" }, { principal: "user:vic", access: "edit" }] } }));

    fireEvent.click(within(list).getByText("Invoice intake"));
    const d = await screen.findByRole("dialog", { name: "Invoice intake" });
    expect(await within(d).findByText("Who sent it")).toBeTruthy();
    expect(await within(d).findByText("ooa.excel")).toBeTruthy();
    const versions = within(d).getByRole("table", { name: "Versions" });
    fireEvent.click(await within(versions).findByLabelText("Select v2"));
    fireEvent.click(within(versions).getByLabelText("Select v3"));
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Compare" })); });
    expect(await screen.findByText(/line\(s\) added, 0 removed/)).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog", { name: "v2 to v3 of Invoice intake" })).getByRole("button", { name: /close/i }));
    fireEvent.click(within(versions).getByRole("button", { name: "Ask to make live" }));
    fireEvent.change(within(d).getByLabelText(/Ask for v2 to go live/), { target: { value: "Roll back" } });
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Ask" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "repo/production", body: { path: "Finance/Invoice intake.flow.json", version: 2, comment: "Roll back" } }));

    fireEvent.click(within(list).getByText("Invoice intake"));
    fireEvent.click(within(await screen.findByRole("dialog", { name: "Invoice intake" })).getByRole("button", { name: "Run" }));
    const run = await screen.findByRole("dialog", { name: "Run Invoice intake" });
    fireEvent.change(await within(run).findByLabelText(/^vendor/), { target: { value: "Acme" } });
    await waitFor(() => expect((within(run).getByLabelText("Run on") as HTMLSelectElement).value).toBe("d:d1"));
    await act(async () => { fireEvent.click(within(run).getByRole("button", { name: "Run" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "runs", body: { bot_path: "Finance/Invoice intake.flow.json", device_id: "d1", inputs: { vendor: "Acme" } } }));

    fireEvent.click(screen.getByRole("button", { name: "Production approvals (1 waiting)" }));
    const ap = await screen.findByRole("dialog", { name: "Production approvals" });
    await act(async () => { fireEvent.click(await within(ap).findByRole("button", { name: "Approve" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "repo/production-requests/q1/approve" }));
    fireEvent.click(within(ap).getByRole("button", { name: /close/i }));
    fireEvent.click(screen.getByRole("button", { name: "Deleted agents" }));
    const del = await screen.findByRole("dialog", { name: "Deleted agents" });
    await act(async () => { fireEvent.click(await within(del).findByRole("button", { name: "Recover" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "repo/recover", body: { path: "HR/Old offboard.flow.json" } }));
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
