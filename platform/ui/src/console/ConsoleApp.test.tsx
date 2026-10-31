import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi } from "../api/cr";
import { ConsoleApp } from "./ConsoleApp";
import { MENU_SECTIONS } from "../shell/menu";

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
    error: JSON.stringify({ code: "EXCEL.NOT_FOUND", message: "No such file", stepId: "s1", suggestedFix: "Check the file is in the folder." }) },
  { id: "r4", bot_path: "HR/Onboard.flow.json", version: 1, device_id: "d1", device_name: "Runner A", status: "success", requested_by: "vic", source: "trigger:t1",
    created: "2026-10-29T07:00:00Z", started: "2026-10-29T07:00:02Z", finished: "2026-10-29T07:00:32Z", duration_ms: 30000, steps: 5 }];

function fakeServer({ signedIn = null as typeof admin | typeof viewer | null, mfa = false, studio = true, plugins = [] as unknown[], sso = false } = {}) {
  let user = signedIn;
  let logsAsked = false;
  const calls: { method: string; route: string; body?: unknown; headers?: Record<string, string> }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    const route = url.replace(/^\/api\//, "").replace(/\?.*$/, "");
    const body = typeof init.body === "string" ? JSON.parse(init.body) : init.body ? { file: (init.body as File).name } : undefined;   // an upload sends the file itself
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
    if (route === "alerts") return ok({ open: 1, alerts: url.includes("status=cleared") ? [] : [{ id: "al1", rule_id: "ar1", rule_name: "Any failure", kind: "run_failed", subject: "r3",
      message: "Invoice intake failed: EXCEL.NOT_FOUND No such file", severity: "critical", status: "open", raised: "2026-10-31T08:00:00Z", link: "#/activity?run=r3",
      delivery: { inbox: ["role:admin"], not_emailed: ["ops@example.com"], note: "E-mail is not set up" } }] });
    if (route === "alerts/acknowledge") return ok({ acknowledged: (body.ids as string[]).length });
    if (route === "alert-rules" && init.method === "POST") return ok({ id: "ar9" });
    if (route === "alert-rules") return ok({ rules: [
      { id: "ar1", name: "Any failure", kind: "run_failed", notify_to: ["role:admin", "ops@example.com"], severity: "critical", enabled: true, open: 1, last_checked: "2026-10-31T08:01:00Z" },
      { id: "ar2", name: "Backup failing", kind: "failure_rate", scope: "Finance/Invoice intake.flow.json", threshold: 50, window_minutes: 60, notify_to: ["user:admin"], severity: "warning", enabled: false, open: 0 }] });
    if (route === "alert-rules/ar1/test") return ok({ inbox: ["role:admin"], not_emailed: ["ops@example.com"], note: "E-mail is not set up (Administration > Settings > E-mail)" });
    if (/^alert-rules\/ar\d$/.test(route)) return ok({ ok: true });
    if (route === "analytics") {
      calls.push({ method: "GET", route: "analytics?" + (url.split("?")[1] ?? "") });
      const st = { runs: 5, success: 3, failed: 1, stopped: 1, active: 0, success_rate: 75, total_ms: 9000, avg_ms: 1800, p95_ms: 3000, max_ms: 3000 };
      return ok({ totals: st, duration_distribution: [], by_day: [], most_run: [], longest: [], by_runner: [], by_source: [], period: { runs_counted: 5, capped: false },
        most_failed: [{ ...st, bot_path: "Finance/Invoice intake.flow.json", failure_rate: 25, top_errors: ["EXCEL.NOT_FOUND (1)"] }],
        failure_reasons: [{ code: "EXCEL.NOT_FOUND", runs: 1, agents: 1, agent: "Finance/Invoice intake.flow.json", last: "2026-10-30T07:00:00Z" }],
        by_hour: { time_zone: "UTC", runs: Array.from({ length: 7 }, (_, d) => Array.from({ length: 24 }, (_, h) => d === 0 && h === 9 ? 4 : d === 2 && h === 14 ? 1 : 0)),
          failed: Array.from({ length: 7 }, (_, d) => Array.from({ length: 24 }, (_, h) => d === 0 && h === 9 ? 1 : 0)) },
        previous: { ...st, runs: 2, success: 1, failed: 1, success_rate: 50 } });
    }
    if (route === "analytics/capacity") {
      calls.push({ method: "GET", route: "analytics/capacity?" + (url.split("?")[1] ?? "") });
      return ok({ from: "2026-10-24T00:00:00Z", to: "2026-10-31T00:00:00Z", enabled_runners: 2, busy_ms: 36_000_000, use: 85, full_time_people: 1.8, avg_wait_ms: 120_000,
        p95_wait_ms: 900_000, waiting_now: 1, runners: [{ device_id: "d1", busy_ms: 30_000_000, runs: 12, use: 92, avg_wait_ms: 60_000 }, { device_id: "d2", busy_ms: 6_000_000, runs: 3, use: 15, avg_wait_ms: null }],
        heat: { time_zone: "UTC", use: Array.from({ length: 7 }, (_, d) => Array.from({ length: 24 }, (_, h) => d === 0 && h === 9 ? 95 : d === 6 ? null : 0)) },
        by_day: [{ day: "2026-10-30", busy_ms: 18_000_000 }] });
    }
    if (route === "analytics/queues") {
      const base = { counts: { new: 3, retry: 1, in_progress: 0, review: 0, success: 10, failed: 2, deferred: 0 }, added: 6, finished: 5, success: 3, failed: 2, success_rate: 60,
        in_time_rate: 80, per_hour: 0.2, avg_handling_ms: 600_000, avg_wait_ms: 120_000, errors: [{ code: "FILE.NOT_FOUND", items: 2 }],
        by_day: [{ day: "2026-10-30", added: 3, finished: 2 }, { day: "2026-10-31", added: 3, finished: 3 }] };
      return ok({ queues: [
        { ...base, id: "q1", name: "Vendor invoices", sla_minutes: 240, processing: false, pool_id: "p1", waiting: 4, oldest_waiting: new Date(Date.now() - 3 * 3_600_000).toISOString(), overdue: 1, at_risk: 1, runners_needed: 1 },
        { ...base, id: "q2", name: "Claims", sla_minutes: 60, processing: true, pool_id: "p1", waiting: 10, oldest_waiting: null, overdue: 2, at_risk: 0, runners_needed: 3, errors: [] }] });
    }
    if (route === "analytics/value") return ok({ currency: "INR", hourly_cost: 600, default_minutes_saved: 0, successful_runs: 40, hours: 12.5, money: 7500, full_time_people: 0.3,
      previous_hours: 10, previous_money: 6000,
      agents: [{ bot_path: "Finance/Invoice intake.flow.json", runs: 30, minutes: 750, hours: 12.5, money: 7500, run_hours: 2.5, minutes_each: 25, own_value: true },
        { bot_path: "HR/Onboard.flow.json", runs: 10, minutes: 0, hours: 0, money: 0, run_hours: 0.5, minutes_each: 0, own_value: false }],
      folders: [{ folder: "Finance", runs: 30, minutes: 750, hours: 12.5, money: 7500, run_hours: 2.5 }, { folder: "HR", runs: 10, minutes: 0, hours: 0, money: 0, run_hours: 0.5 }],
      by_day: [{ day: "2026-10-30", runs: 40, minutes: 750, hours: 12.5, money: 7500, run_hours: 3 }] });
    if (route === "repo/roi") return ok({ ok: true });
    if (route === "dashboards" && init.method === "POST") return ok({ id: "db9" });
    if (route === "dashboards") return ok({ dashboards: [
      { id: "db1", name: "Finance", description: "Money in and out", owner: "admin", shared_with: ["role:viewer"], can_edit: true, widgets: [{ title: "Amount paid", type: "number", kpi: "amount", agg: "sum" }] },
      { id: "db2", name: "Ops board", owner: "vic", shared_with: ["*"], can_edit: false, widgets: [{ type: "line", source: "runs", metric: "runs", group: "day", days: 7 }] }] });
    if (route === "dashboards/kpis") return ok({ kpis: [{ bot_path: "Finance/Invoice intake.flow.json", name: "amount", n: 12 }, { bot_path: "Finance/Invoice intake.flow.json", name: "category", n: 12 }] });
    if (route === "dashboards/db1/data") return ok({ id: "db1", name: "Finance", widgets: [
      { title: "Amount paid", type: "number", kpi: "amount", agg: "sum", value: 1200 },
      { title: "Runs per day", type: "line", source: "runs", metric: "runs", group: "day", days: 7, points: [{ label: "2026-10-29", value: 3 }, { label: "2026-10-30", value: 5 }] },
      { title: "By category", type: "pie", kpi: "amount", agg: "sum", group: "value", by: "category", points: ["rent", "food", "travel", "tax", "misc"].map((l, i) => ({ label: l, value: 50 - 10 * i })) },
      { title: "Broken", type: "bars", kpi: "nope", error: "No KPI nope" }] });
    if (/^dashboards\/db\d(\/data)?$/.test(route)) return route.endsWith("/data") ? ok({ id: "db2", name: "Ops board", widgets: [] }) : ok({ ok: true });
    if (route === "analytics/agent") return ok({ bot_path: "Finance/Invoice intake.flow.json", totals: { runs: 5, success: 3, failed: 1, stopped: 1, active: 0, success_rate: 75, total_ms: 9000,
      avg_ms: 1800, p95_ms: 3000, max_ms: 3000 }, by_day: [], errors: [{ code: "EXCEL.NOT_FOUND", runs: 1 }], by_runner: [{ runner: "Runner A", runs: 5, success: 3, failed: 1, stopped: 1,
      active: 0, success_rate: 75, total_ms: 9000, avg_ms: 1800, p95_ms: 3000, max_ms: 3000 }], last_run: { id: "r4", status: "success", created: "2026-10-30T08:00:00Z" },
      last_failure: { id: "r3", created: "2026-10-30T07:00:00Z", code: "EXCEL.NOT_FOUND" } });
    if (route === "analytics/variables") return ok({ bot_path: "Finance/Invoice intake.flow.json", runs: 3, variables: [{ variable: "total", runs: 3, runs_with_value: 3, min: 10, max: 90, avg: 50,
      common: [{ value: "10", runs: 1 }] }] });
    if (route === "analytics/export") { calls.push({ method: "GET", route: "analytics/export?" + (url.split("?")[1] ?? "") }); return new Response("agent,runs\r\n", { status: 200 }); }
    if (route === "plugins") return ok({ plugins });
    if (route === "repo/policy") return ok({ enforce_at_checkin: false, rules: {
      require_description: { enabled: true, severity: "warning", description: "Every agent says what it does", params: {} },
      max_steps: { enabled: true, severity: "error", description: "Agents stay small enough to read", params: { max: 200 } },
      blocked_actions: { enabled: false, severity: "error", description: "Commands nobody may use", params: { actions: ["ooa.shell/run"] } } } });
    if (route === "repo/analysis/report") return new Response("agent,version,rule\r\n", { status: 200 });
    if (route === "repo/analysis") return ok({ path: "Finance/Invoice intake.flow.json", version: 3, errors: 1, warnings: 1, passed: false, analyzed: "2026-10-30T08:00:00Z",
      violations: [{ rule: "max_steps", severity: "error", step: "s9", message: "212 steps; the most allowed is 200" }, { rule: "require_description", severity: "warning", message: "The agent has no description" }] });
    if (route === "repo/analyze") return ok({ path: "Finance/Invoice intake.flow.json", version: 3, errors: 0, warnings: 0, passed: true, violations: [] });
    if (route === "repo/files" && (init.method ?? "GET") === "GET") return ok({ files: [
      { path: "Finance/rates.xlsx", size: 2048, version: 2, updated: "2026-10-30T08:00:00Z", updated_by: "admin", folder: "Finance", access: "manage" },
      { path: "Finance/Sub/codes.csv", size: 10, version: 1, folder: "Finance/Sub", access: "manage" }] });
    if (route === "repo/files" && init.method === "PUT") return ok({ version: 3 });
    if (route === "repo/files") return ok({ ok: true });
    if (route === "lifecycle/export") return ok({ format: "ooa.bundle", exported_by: "admin", agents: [{ path: "Finance/Invoice intake.flow.json", version: 3, production: true }] });
    if (route === "lifecycle/import") return ok({ ok: true, imported: 2, results: [{ path: "Imported/Finance/Invoice intake.flow.json", status: "created", version: 1 },
      { path: "Imported/Shared/Read PDF.flow.json", status: "created", version: 1 }] });
    if (route === "lifecycle/promote") return ok({ ok: true, status: 200, target: "https://cc-prod.example.com", result: { ok: true, imported: 1,
      results: [{ path: "Finance/Invoice intake.flow.json", status: "updated", version: 4 }] } });
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
    if (route === "catalog") return ok({ actions: { "ooa.excel/open": "Open workbook" } });
    if (route === "runs/r3/ai-calls") return ok({ calls: [{ ts: "2026-10-30T07:00:12Z", purpose: "Read the invoice", model: "gpt-4o-mini", connection: "OpenAI", input_tokens: 120, output_tokens: 8, latency_ms: 900 }] });
    if (route === "runs/r3/rerun") return ok({ run_id: "r9", version: 3 });
    const m = /^runs\/([^/]+)(?:\/(\w+))?$/.exec(route);
    if (m && !m[2]) return ok({ ...(runs.find((r) => r.id === m[1]) ?? { id: m[1], bot_path: "Finance/Invoice intake.flow.json", version: 3, device_id: "d1", device_name: "Runner A",
      status: "queued", requested_by: "admin", source: "rerun", created: "2026-10-31T09:00:00Z" }),
      inputs: JSON.stringify({ file: "march.xlsx" }), events: m[1] !== "r3" ? [] : [
      { seq: 1, type: "step.started", stepId: "s1", ts: 100, data: { action: "ooa.excel/open" } },
      { seq: 2, type: "variable.set", ts: 100.5, data: { name: "rows", value: "0" } },
      { seq: 3, type: "step.failed", stepId: "s1", ts: 101.25, data: { action: "ooa.excel/open", code: "EXCEL.NOT_FOUND", message: "No such file" } }] });
    if (m && m[2]) return ok({ ok: true });
    return no(404, "CR.NOT_FOUND", "Unknown API route " + route);
  }) as unknown as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

/** Opens a page from the menu: the console keeps one section open, so its section is opened first when needed. */
async function go(name: string) {
  const nav = await screen.findByRole("navigation", { name: "Command Center" });
  if (!within(nav).queryByRole("button", { name })) {
    const sec = MENU_SECTIONS.find((x) => x.pages.some((pg) => pg.label === name));
    if (sec) fireEvent.click(within(nav).getByRole("button", { name: sec.label }));
  }
  fireEvent.click(within(nav).getByRole("button", { name }));
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

  it("shows the menu by task; every page opens here, Studio in its own tab", async () => {
    render(<ConsoleApp api={fakeServer({ signedIn: admin }).api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    for (const s of ["BUILD", "MANAGE", "OPERATIONAL INSIGHTS", "ADMINISTRATION", "AI"]) expect(within(menu).getByText(s)).toBeTruthy();
    fireEvent.click(within(menu).getByRole("button", { name: "BUILD" }));
    expect(within(menu).getAllByRole("link").map((l) => l.getAttribute("href"))).toEqual(["../studio/"]);   // every other page is rebuilt here
    await waitFor(() => expect(within(menu).getByRole("link", { name: /One AgentStudio/ }).getAttribute("href")).toBe("../studio/"));
    expect(within(menu).getByRole("link", { name: /One AgentStudio/ }).getAttribute("target")).toBe("_blank");   // always a new tab
    expect(within(menu).getByRole("button", { name: "Home" }).getAttribute("aria-current")).toBe("page");
    fireEvent.keyDown(screen.getByRole("button", { name: "Help" }), { key: "Enter" });
    expect(within(await screen.findByRole("menu")).getByRole("menuitem", { name: /^Classic console/ })).toBeTruthy();   // today's console, until it is retired
  });

  it("shows only the pages a person may open, and signs out", async () => {
    const { api } = fakeServer({ signedIn: viewer });
    render(<ConsoleApp api={api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    fireEvent.click(within(menu).getByRole("button", { name: "MANAGE" }));
    expect(within(menu).getByRole("button", { name: "Runner Machines" })).toBeTruthy();
    expect(within(menu).queryByText("ADMINISTRATION")).not.toBeNull();   // the vault is for everyone
    expect(within(menu).queryByRole("link", { name: "Users" })).toBeNull();
    expect(within(menu).queryByText("OPERATIONAL INSIGHTS")).toBeNull();   // no page in it is open to a viewer
    fireEvent.click(within(menu).getByRole("button", { name: "AI" }));
    expect(within(menu).getByRole("button", { name: "Document Automation" })).toBeTruthy();   // AI shows only what a viewer may open
    expect(within(menu).queryByRole("link", { name: "Model Connections" })).toBeNull();
    fireEvent.keyDown(screen.getByRole("button", { name: "Account: Vic Viewer" }), { key: "Enter" });
    await act(async () => { fireEvent.click(within(await screen.findByRole("menu")).getByRole("menuitem", { name: "Sign out" })); });
    expect(await screen.findByRole("form", { name: "Sign in" })).toBeTruthy();
  });

  it("shows AI > Document Automation as a plug-in page: what it does when not installed, the module's page when it is", async () => {
    render(<ConsoleApp api={fakeServer({ signedIn: admin }).api} />);
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    await go("Document Automation");
    expect(await screen.findByRole("heading", { name: "Not installed on this Command Center" })).toBeTruthy();
    expect(within(menu).getByRole("button", { name: "Document Automation" }).getAttribute("aria-current")).toBe("page");
    cleanup();
    render(<ConsoleApp api={fakeServer({ signedIn: admin, plugins: [{ id: "document-automation", name: "Document Automation", description: "Learning instances and validation.", path: "/plugins/document-automation/" }] }).api} />);
    await go("Document Automation");
    expect((await screen.findByRole("link", { name: "Open Document Automation" })).getAttribute("href")).toBe("/plugins/document-automation/");
  });

  it("shows Agent Activity as AA's Activity: a summary, In progress and Completed, filters, selected runs stopped or run again, columns, CSV", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "runs.stop", "runs.start"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    await go("Agent Activity");
    const live = await screen.findByRole("table", { name: "Runs in progress" });
    expect(await within(live).findByText("Step 3 of 8: Open workbook")).toBeTruthy();   // the command's own name
    const sums = await screen.findByRole("group", { name: "Summary" });
    expect(within(sums).getByText("Ended in the last 24 hours · 50% completed")).toBeTruthy();
    expect(within(sums).getByText("Next scheduled: Invoice intake")).toBeTruthy();
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
    const made: string[] = [];
    URL.createObjectURL = (() => { made.push("csv"); return "blob:x"; }) as typeof URL.createObjectURL;
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Export CSV" })); });
    expect(made).toEqual(["csv"]);
    expect(await screen.findByText("Exported 1 run.")).toBeTruthy();
  });

  it("opens Scheduled from the summary: each schedule's next run, soonest first; selected ones turned off or run now", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "runs.start", "schedules.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    await go("Agent Activity");
    fireEvent.click(within(await screen.findByRole("group", { name: "Summary" })).getByText("Next scheduled: Invoice intake"));
    const list = await screen.findByRole("table", { name: "Scheduled runs" });
    await within(list).findByText("Nightly invoices");
    const rows = within(list).getAllByRole("row").slice(1).map((r) => r.textContent ?? "");
    expect(rows[0]).toContain("Nightly invoices");   // on, and soonest first
    expect(rows[1]).toContain("Not planned");         // off: last
    expect(within(list).getByText("Skips India 2026")).toBeTruthy();
    fireEvent.click(within(list).getByLabelText("Select the schedule Nightly invoices"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Turn off" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "schedules/bulk", body: { ids: ["s1"], action: "disable" } }));
    fireEvent.click(within(list).getByLabelText("Select the schedule Nightly invoices"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Run now" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "schedules/s1/run" }));
    expect(await screen.findByRole("dialog", { name: "Run of Invoice intake" })).toBeTruthy();   // the run it started opens
    location.hash = "";
  });

  it("shows a run as UiPath's job details: why it ended and the fix, steps, the log with times, variables, inputs, AI calls; Run again opens the new run", async () => {
    location.hash = "#/activity?run=r3";
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "runs.start"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    const d = await screen.findByRole("dialog", { name: "Run of Invoice intake" });
    const why = await within(d).findByRole("note", { name: "Why it ended" });
    expect(why.textContent).toContain("Fix: Check the file is in the folder.");
    expect(within(within(d).getByRole("table", { name: "Steps" })).getByText("EXCEL.NOT_FOUND: No such file")).toBeTruthy();
    fireEvent.click(within(d).getByRole("tab", { name: /^Log/ }));
    expect(within(d).getByText("Failed Open workbook: EXCEL.NOT_FOUND No such file")).toBeTruthy();
    expect(within(d).getByText("+1.25s")).toBeTruthy();
    fireEvent.change(within(d).getByLabelText("Show in the log"), { target: { value: "vars" } });
    expect(within(d).getByText("rows = 0")).toBeTruthy();
    fireEvent.click(within(d).getByRole("tab", { name: "Inputs and outputs" }));
    expect(within(d).getByLabelText("Inputs").textContent).toContain('"file": "march.xlsx"');
    fireEvent.click(await within(d).findByRole("tab", { name: "AI calls (1)" }));
    expect(within(d).getByText("gpt-4o-mini (OpenAI)")).toBeTruthy();
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Run again" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "runs/r3/rerun" }));
    expect(await screen.findByText("Started again as run r9.")).toBeTruthy();
    expect(location.hash).toBe("#/activity?run=r9");
    fireEvent.click(within(screen.getByRole("dialog", { name: "Run of Invoice intake" })).getByRole("button", { name: "Close" }));
    expect(location.hash).toBe("#/activity");
    location.hash = "";
  });

  it("shows Operational Insights > Overview: figures that open their page, why runs failed across agents, runners and queues needing attention, AI budget used", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    await go("Overview");
    const figs = await screen.findByRole("group", { name: "Figures" });
    expect(await within(figs).findByText("1 of 2")).toBeTruthy();
    expect(within(figs).getByText("Runners online · 2 need attention")).toBeTruthy();
    expect(within(figs).getByText("75%")).toBeTruthy();
    expect(within(figs).getByText("Queue items overdue · 4 waiting")).toBeTruthy();
    expect(within(figs).getByText("Open alerts")).toBeTruthy();
    expect(within(screen.getByRole("list", { name: "Open alerts" })).getByRole("link", { name: /^Invoice intake failed/ })).toBeTruthy();
    const runners = screen.getByRole("table", { name: "Runners needing attention" });
    expect(within(runners).getByText("Busy: CPU use 93% is above its limit of 80%")).toBeTruthy();
    expect(within(runners).getByText("Offline")).toBeTruthy();
    const queues = screen.getByRole("table", { name: "Work queues with items waiting" });
    expect(within(queues).getByText("1 (target 240 min)")).toBeTruthy();
    expect(within(screen.getByRole("table", { name: "AI use this month" })).getByText("90%", { exact: false }).closest("td")!.className).toContain("cc-err");   // 80% or more used
    fireEvent.change(screen.getByLabelText("Period"), { target: { value: "7" } });
    await waitFor(() => expect(calls.some((c) => c.route.startsWith("analytics?") && c.route.includes("days=7"))).toBe(true));
    // a failure reason opens Agent Activity's Completed runs, filtered to it
    const link = within(screen.getByRole("table", { name: "Why runs failed" })).getByRole("link", { name: "EXCEL.NOT_FOUND" });
    expect(link.getAttribute("href")).toBe("#/activity?tab=completed&status=failed&q=EXCEL.NOT_FOUND&period=7d");
    await act(async () => { location.hash = link.getAttribute("href")!; });
    expect(await screen.findByRole("table", { name: "Completed runs" })).toBeTruthy();
    expect((screen.getByLabelText("Status") as HTMLSelectElement).value).toBe("failed");
    expect((screen.getByLabelText("Search runs") as HTMLInputElement).value).toBe("EXCEL.NOT_FOUND");
    await waitFor(() => expect(calls.some((c) => c.route.startsWith("runs?") && c.route.includes("status=failed") && c.route.includes("q=EXCEL.NOT_FOUND"))).toBe(true));
    expect(location.hash).toBe("#/activity");
    location.hash = "";
  });

  it("shows Alerts as UiPath's monitoring alerts: open alerts acknowledged, rules turned off, tested, made for a work queue and deleted", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "alerts.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    await go("Alerts");
    const list = await screen.findByRole("table", { name: "Alerts list" });
    const alert = await within(list).findByRole("link", { name: "Invoice intake failed: EXCEL.NOT_FOUND No such file" });
    expect(alert.getAttribute("href")).toBe("#/activity?run=r3");
    expect(within(list).getByText("Inbox: role:admin · Not e-mailed: ops@example.com")).toBeTruthy();
    fireEvent.click(within(list).getByLabelText(/^Select the alert/));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Acknowledge" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "alerts/acknowledge", body: { ids: ["al1"] } }));
    expect(await screen.findByText("Acknowledged 1 alert.")).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: /^Rules/ }));
    const rules = await screen.findByRole("table", { name: "Alert rules" });
    expect(within(rules).getByText("Too many runs fail · Invoice intake")).toBeTruthy();
    expect(within(rules).getByText("Failed share of finished runs: 50% · Over the last 60 min")).toBeTruthy();
    const first = within(rules).getByRole("row", { name: /Any failure/ });
    await act(async () => { fireEvent.click(within(first).getByRole("button", { name: "Send a test" })); });
    expect(await screen.findByText(/^Test sent\. Inbox: role:admin · Not e-mailed: ops@example\.com E-mail is not set up/)).toBeTruthy();
    await act(async () => { fireEvent.click(within(first).getByRole("button", { name: "Turn off" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PATCH", route: "alert-rules/ar1", body: { enabled: false } }));

    fireEvent.click(screen.getByRole("button", { name: "New alert rule" }));
    const d = await screen.findByRole("dialog", { name: "New alert rule" });
    fireEvent.change(within(d).getByLabelText("Name"), { target: { value: "Queue late" } });
    fireEvent.change(within(d).getByLabelText("Watch for"), { target: { value: "queue_overdue" } });
    await waitFor(() => expect(within(within(d).getByLabelText("In")).getByRole("option", { name: "Vendor invoices" })).toBeTruthy());
    fireEvent.change(within(d).getByLabelText("In"), { target: { value: "q1" } });
    fireEvent.change(within(d).getByLabelText("Overdue items, at least"), { target: { value: "3" } });
    fireEvent.change(within(d).getByLabelText("E-mail addresses"), { target: { value: "ops@example.com" } });
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Make the rule" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "alert-rules", body: { name: "Queue late", kind: "queue_overdue", scope: "q1", severity: "warning",
      enabled: true, threshold: 3, window_minutes: null, notify_to: ["role:admin", "ops@example.com"], webhook_url: null } }));
    const second = within(screen.getByRole("table", { name: "Alert rules" })).getByRole("row", { name: /Backup failing/ });
    fireEvent.click(within(second).getByRole("button", { name: "Delete" }));
    await act(async () => { fireEvent.click(within(second).getByRole("button", { name: "Delete" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "DELETE", route: "alert-rules/ar2" }));
  });

  it("shows Operations as AA's operational dashboards: figures against the period before, when runs start, failure reasons, CSVs and an agent's statistics", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    await go("Operations");
    const figs = await screen.findByRole("group", { name: "Figures" });
    expect(await within(figs).findByText("Runs · +150% on before")).toBeTruthy();
    expect(within(figs).getByText("Completed: 3 completed, 1 failed, 1 stopped · +25 points on before")).toBeTruthy();
    const heat = screen.getByRole("table", { name: "When runs start, by weekday and hour (UTC)" });
    const busy = within(heat).getByTitle("Mon 09:00 · 4 runs, 1 failed");
    expect(busy.className).toBe("cc-heat-l4 has-failed");
    expect(within(heat).getByTitle("Wed 14:00 · 1 run").className).toBe("cc-heat-l1");
    expect(within(screen.getByRole("table", { name: "Why runs failed" })).getByRole("link", { name: "EXCEL.NOT_FOUND" }).getAttribute("href"))
      .toBe("#/activity?tab=completed&status=failed&q=EXCEL.NOT_FOUND");
    fireEvent.change(screen.getByLabelText("Period"), { target: { value: "7" } });
    await waitFor(() => expect(calls.some((c) => c.route.startsWith("analytics?") && c.route.includes("days=7") && c.route.includes("tz="))).toBe(true));
    URL.createObjectURL = (() => "blob:x") as typeof URL.createObjectURL;
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "CSV of agents run most" })); });
    expect(calls.some((c) => c.route.startsWith("analytics/export?") && c.route.includes("report=most_run") && c.route.includes("days=7"))).toBe(true);
    fireEvent.click(within(screen.getByRole("table", { name: "Agents that fail most" })).getByRole("button", { name: "Invoice intake" }));
    const d = await screen.findByRole("dialog", { name: "Statistics of Invoice intake" });
    expect(await within(d).findByText("10 – 90 (average 50)")).toBeTruthy();
    expect(within(d).getAllByRole("link").map((l) => l.getAttribute("href"))).toEqual(["#/activity?run=r4", "#/activity?run=r3",   // last run, last failure
      "#/activity?tab=completed&bot=Finance%2FInvoice+intake.flow.json"]);
  });

  it("shows Runner capacity as Blue Prism's utilisation views: time in use, waits, each runner with its pools, when runners are busy, advice when short", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    await go("Runner capacity");
    const figs = await screen.findByRole("group", { name: "Figures" });
    expect(await within(figs).findByText("1 of 2")).toBeTruthy();
    expect(within(figs).getByText("85%").className).toBe("cc-err");
    expect(within(figs).getByText("Busy · as 1.8 full-time people (40 h a week)")).toBeTruthy();
    expect(within(figs).getByText("Average wait to start · 95% within 15 min 0 s")).toBeTruthy();
    expect(screen.getByRole("note").textContent).toContain("Runners are busy most of the time and runs wait 15 min 0 s or more to start");
    const each = screen.getByRole("table", { name: "Each runner" });
    const a = within(each).getByRole("row", { name: /Runner A/ });
    expect(within(a).getByText("Busy (limit)")).toBeTruthy();
    expect(within(a).getByText("Finance pool")).toBeTruthy();
    expect(within(a).getByText("92%", { exact: false }).closest("td")!.className).toBe("cc-err");
    expect(within(within(each).getByRole("row", { name: /Runner B/ })).getByText("Offline")).toBeTruthy();
    const heat = screen.getByRole("table", { name: "Runner time in use, by weekday and hour (UTC)" });
    expect(within(heat).getByTitle("Mon 09:00 · 95% in use").className).toBe("cc-heat-l4");
    expect(within(heat).getByTitle("Sun 09:00 · not in the period").className).toBe("cc-heat-l0");
    fireEvent.change(screen.getByLabelText("Period"), { target: { value: "30" } });
    await waitFor(() => expect(calls.some((c) => c.route.startsWith("analytics/capacity?") && c.route.includes("days=30"))).toBe(true));
  });

  it("shows Work queue insights as UiPath's queue monitoring: waiting, overdue and at risk, speed, runners needed against the pool, and advice", async () => {
    const { api } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    await go("Work queue insights");
    const figs = await screen.findByRole("group", { name: "Figures" });
    expect(within(figs).getByText("14")).toBeTruthy();
    expect(within(figs).getByText("3").className).toBe("cc-err");
    expect(within(figs).getByText("Finished · 60% completed · last 7 days")).toBeTruthy();
    const advice = await screen.findByRole("list", { name: "Advice" });
    expect(within(advice).getByText("Vendor invoices: processing is off and 4 items wait. Turn processing on in Work queues.")).toBeTruthy();
    expect(within(advice).getByText("Claims: clearing what waits within 60 minutes needs 3 runners; its pool has 1. Add runners to the pool.")).toBeTruthy();
    const table = screen.getByRole("table", { name: "Queues" });
    expect(within(within(table).getByRole("row", { name: /Claims/ })).getByText("3 of 1")).toBeTruthy();
    expect(within(within(table).getByRole("row", { name: /Vendor invoices/ })).getByText("3 h 0 min")).toBeTruthy();   // oldest waiting
    fireEvent.click(within(table).getByRole("button", { name: "Vendor invoices" }));
    expect(within(screen.getByRole("table", { name: "Why items failed in Vendor invoices" })).getByText("FILE.NOT_FOUND")).toBeTruthy();
    expect(within(screen.getByRole("list", { name: "Added and finished per day in Vendor invoices" })).getByText("3 added · 2 finished")).toBeTruthy();
  });

  it("shows Value as AA's CoE dashboard: hours and money saved against the period before, by folder and agent, minutes saved changed in place", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "repo.production"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    await go("Value");
    const figs = await screen.findByRole("group", { name: "Figures" });
    expect(within(figs).getByText("Hours saved · +25% on before")).toBeTruthy();
    expect(within(figs).getByText(/7,500/)).toBeTruthy();
    expect(within(screen.getByRole("list", { name: "Advice" })).getByText(/1 agent has no minutes saved of its own and the default is 0/)).toBeTruthy();
    expect(within(screen.getByRole("table", { name: "Value by folder" })).getByText("5×")).toBeTruthy();   // 12.5 hours saved for 2.5 hours run
    const agents = screen.getByRole("table", { name: "Value by agent" });
    expect(within(agents).getByText("0 (default)", { exact: false })).toBeTruthy();
    fireEvent.click(within(agents).getByRole("button", { name: "Change minutes saved by Onboard" }));
    fireEvent.change(within(agents).getByLabelText("Minutes saved a run by Onboard"), { target: { value: "15" } });
    await act(async () => { fireEvent.click(within(agents).getByRole("button", { name: "Save" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "repo/roi", body: { path: "HR/Onboard.flow.json", minutes_saved: 15 } }));
    expect(await screen.findByText("Onboard: 15 minutes saved a run.")).toBeTruthy();
  });

  it("shows Dashboards with widgets made in a form: ready-made ones, a pie of the largest parts, a CSV per widget, copies and deletes", async () => {
    const { api, calls } = fakeServer({ signedIn: admin });
    render(<ConsoleApp api={api} />);
    await go("Dashboards");
    expect(await screen.findByText("Yours · shared with role:viewer")).toBeTruthy();
    expect(within(await screen.findByRole("article", { name: "Amount paid" })).getByText("1,200")).toBeTruthy();
    expect(screen.getByRole("img", { name: "Runs per day: from 3 (2026-10-29) to 5 (2026-10-30)" })).toBeTruthy();
    const parts = screen.getByRole("list", { name: "Parts of By category" });
    expect(within(parts).getAllByRole("listitem").map((li) => li.textContent)).toEqual(["rent50 · 33%", "food40 · 27%", "travel30 · 20%", "Others30 · 20%"]);
    expect(within(screen.getByRole("article", { name: "Broken" })).getByText("No KPI nope")).toBeTruthy();
    const made: string[] = [];
    URL.createObjectURL = (() => { made.push("csv"); return "blob:x"; }) as typeof URL.createObjectURL;
    fireEvent.click(screen.getByRole("button", { name: "CSV of Amount paid" }));
    expect(made).toEqual(["csv"]);

    fireEvent.click(screen.getByRole("button", { name: "New dashboard" }));
    const d = await screen.findByRole("dialog", { name: "New dashboard" });
    fireEvent.change(within(d).getByLabelText("Start from"), { target: { value: "ops" } });
    expect(within(within(d).getByRole("table", { name: "Widgets" })).getAllByRole("row")).toHaveLength(4);
    fireEvent.click(within(d).getByRole("button", { name: "Add a widget" }));
    const w = await screen.findByRole("dialog", { name: "Widget" });
    fireEvent.change(within(w).getByLabelText("Show as"), { target: { value: "pie" } });
    fireEvent.change(within(w).getByLabelText("Figure from"), { target: { value: "kpi" } });
    fireEvent.change(within(w).getByLabelText("KPI"), { target: { value: "amount" } });
    fireEvent.change(within(w).getByLabelText("Split by"), { target: { value: "value" } });
    fireEvent.change(within(w).getByLabelText("The value of"), { target: { value: "category" } });
    fireEvent.click(within(w).getByRole("button", { name: "Done" }));
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Save" })); });
    const saved = calls.find((c) => c.method === "POST" && c.route === "dashboards");
    expect(saved?.body).toEqual(expect.objectContaining({ name: "Operations at a glance", shared_with: [] }));
    expect((saved?.body as { widgets: unknown[] }).widgets[4]).toEqual({ type: "pie", source: "kpi", days: 30, kpi: "amount", agg: "sum", group: "value", by: "category" });

    fireEvent.change(await screen.findByRole("combobox", { name: "Dashboard" }), { target: { value: "db2" } });
    expect(await screen.findByText("Made by vic · shared with *")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();   // only its maker changes it
    fireEvent.click(screen.getByRole("button", { name: "Copy as mine" }));
    const c = await screen.findByRole("dialog", { name: "Copy Ops board" });
    expect((within(c).getByLabelText("Name") as HTMLInputElement).value).toBe("Ops board (copy)");
    await act(async () => { fireEvent.click(within(c).getByRole("button", { name: "Save" })); });
    expect(calls.filter((x) => x.method === "POST" && x.route === "dashboards")).toHaveLength(2);

    fireEvent.change(screen.getByRole("combobox", { name: "Dashboard" }), { target: { value: "db1" } });
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
    await act(async () => { fireEvent.click(within(screen.getByRole("toolbar", { name: "Dashboard" })).getByRole("button", { name: "Delete" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "DELETE", route: "dashboards/db1" }));
  });

  it("exports, promotes and imports agents, keeps the code analysis policy and an agent's findings, and the files of a folder (AA's Bot Lifecycle Management)", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "repo.checkin", "repo.delete", "lifecycle.export", "lifecycle.import"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    await go("Agent Repository");
    await screen.findByRole("table", { name: "Agents list" });
    const made: string[] = [];
    URL.createObjectURL = (() => { made.push("file"); return "blob:x"; }) as typeof URL.createObjectURL;

    // export two agents, then promote them to another Command Center
    fireEvent.click(screen.getByRole("button", { name: "Export or promote" }));
    const ex = await screen.findByRole("dialog", { name: "Export or promote agents" });
    const pick = await within(ex).findByRole("group", { name: "Agents to export" });
    fireEvent.click(within(pick).getByLabelText(/^Invoice intake/));
    fireEvent.click(within(ex).getByRole("button", { name: "Promote to another Command Center" }));
    fireEvent.change(within(ex).getByLabelText("Its address"), { target: { value: "https://cc-prod.example.com" } });
    fireEvent.change(within(ex).getByLabelText("Its API key (vault reference)"), { target: { value: "Prod/cc-key/token" } });
    fireEvent.change(within(ex).getByLabelText("If an agent is already there"), { target: { value: "overwrite" } });
    await act(async () => { fireEvent.click(within(ex).getByRole("button", { name: "Promote" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "lifecycle/promote", body: { paths: ["Finance/Invoice intake.flow.json"], version: "production",
      include_dependencies: true, target_url: "https://cc-prod.example.com", api_key_ref: "Prod/cc-key/token", conflict: "overwrite", production: false } }));
    expect(within(within(ex).getByRole("table", { name: "Promotion results" })).getByText("New version")).toBeTruthy();
    await act(async () => { fireEvent.click(within(ex).getByRole("button", { name: "Download bundle" })); });
    expect(made).toEqual(["file"]);
    expect(await screen.findByText("Exported 1 agent to a bundle file.")).toBeTruthy();

    // import: what the bundle holds and where each agent goes, before anything happens
    fireEvent.click(screen.getByRole("button", { name: "Import" }));
    const im = await screen.findByRole("dialog", { name: "Import agents" });
    const bundle = { format: "ooa.bundle", exported_by: "vic", agents: [{ path: "Finance/Invoice intake.flow.json", version: 3, production: true }, { path: "Shared/Read PDF.flow.json", version: 1, included_as: "dependency" }] };
    const file = new File([JSON.stringify(bundle)], "agents.ooabundle.json", { type: "application/json" });
    Object.defineProperty(file, "text", { value: () => Promise.resolve(JSON.stringify(bundle)) });
    await act(async () => { fireEvent.change(within(im).getByLabelText("Bundle file"), { target: { files: [file] } }); });
    const inside = await within(im).findByRole("table", { name: "In the bundle" });
    expect(within(inside).getByText("Yes: skipped")).toBeTruthy();   // Invoice intake is here already
    expect(within(inside).getByText("Called by another")).toBeTruthy();
    fireEvent.change(within(im).getByLabelText("Put them under folder"), { target: { value: "Imported" } });
    expect(within(inside).getByText("Imported/Shared/Read PDF.flow.json")).toBeTruthy();
    await act(async () => { fireEvent.click(within(im).getByRole("button", { name: "Import" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "POST", route: "lifecycle/import", body: { bundle, conflict: "skip", production: false, target_folder: "Imported" } }));
    expect(await screen.findByText("Imported 2 agents.")).toBeTruthy();
    fireEvent.click(within(im).getByRole("button", { name: "Done" }));

    // the code analysis policy: a rule's severity and setting changed, and the report for all agents
    fireEvent.click(screen.getByRole("button", { name: "Code analysis" }));
    const ca = await screen.findByRole("dialog", { name: "Code analysis" });
    fireEvent.change(await within(ca).findByLabelText("Severity of Require description"), { target: { value: "error" } });
    fireEvent.change(within(ca).getByLabelText("Max steps: max"), { target: { value: "150" } });
    fireEvent.click(within(ca).getByLabelText("Refuse check-ins that have errors"));
    await act(async () => { fireEvent.click(within(ca).getByRole("button", { name: "Report for all agents (CSV)" })); });
    expect(made).toEqual(["file", "file"]);
    await act(async () => { fireEvent.click(within(ca).getByRole("button", { name: "Save" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "repo/policy", body: { enforce_at_checkin: true, rules: {
      require_description: { enabled: true, severity: "error", params: {} }, max_steps: { enabled: true, severity: "error", params: { max: 150 } },
      blocked_actions: { enabled: false, severity: "error", params: { actions: ["ooa.shell/run"] } } } } }));

    // an agent's own findings, analysed again
    fireEvent.click(within(screen.getByRole("table", { name: "Agents list" })).getByText("Invoice intake"));
    const d = await screen.findByRole("dialog", { name: "Invoice intake" });
    expect(within(await within(d).findByRole("table", { name: "Findings" })).getByText("212 steps; the most allowed is 200")).toBeTruthy();
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Analyse again" })); });
    expect(within(d).getByText("No errors")).toBeTruthy();
    fireEvent.click(within(d).getByRole("button", { name: /close/i }));

    // the files of the Finance folder (not those of its subfolders): upload, download, delete
    fireEvent.click(within(screen.getByRole("navigation", { name: "Folders" })).getByRole("button", { name: /^Finance/ }));
    const files = await screen.findByRole("table", { name: "Files" });
    expect(within(files).getByText("rates.xlsx")).toBeTruthy();
    expect(within(files).queryByText("codes.csv")).toBeNull();
    expect(within(files).getByRole("link", { name: "Download" }).getAttribute("href")).toBe("/api/repo/files/content?path=Finance%2Frates.xlsx");
    await act(async () => { fireEvent.change(screen.getByLabelText("File to upload"), { target: { files: [new File(["x"], "rates.xlsx")] } }); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "PUT", route: "repo/files" }));
    expect(await screen.findByText("Uploaded Finance/rates.xlsx (version 3).")).toBeTruthy();
    fireEvent.click(within(files).getByRole("button", { name: "Delete" }));
    await act(async () => { fireEvent.click(within(files).getByRole("button", { name: "Delete" })); });
    expect(calls).toContainEqual(expect.objectContaining({ method: "DELETE", route: "repo/files" }));
  });

  it("shows the frame of the design plan's step 1: where you are, one search, open alerts, help; a menu that finds a page, keeps one section open and collapses from the bottom", async () => {
    location.hash = "";
    render(<ConsoleApp api={fakeServer({ signedIn: admin }).api} />);
    const here = await screen.findByRole("navigation", { name: "You are here" });
    expect(here.textContent).toBe("Home");
    expect((await screen.findByRole("link", { name: "Open alerts: 1" })).getAttribute("href")).toBe("#/alerts");
    await go("Users");
    await waitFor(() => expect(screen.getByRole("navigation", { name: "You are here" }).textContent).toBe("Administration›Users"));
    const menu = screen.getByRole("navigation", { name: "Command Center" });
    expect(within(menu).getByRole("button", { name: "ADMINISTRATION" }).getAttribute("aria-expanded")).toBe("true");
    expect(within(menu).getByRole("button", { name: "MANAGE" }).getAttribute("aria-expanded")).toBe("false");   // one section open
    fireEvent.click(within(menu).getByRole("button", { name: "MANAGE" }));
    expect(within(menu).queryByRole("button", { name: "Roles" })).toBeNull();   // opening another closes it
    expect(within(menu).getByText("Pinned pages show here.")).toBeTruthy();
    const buttons = within(menu).getAllByRole("button");
    expect(buttons[buttons.length - 1].getAttribute("aria-label")).toBe("Collapse menu");   // at the bottom
    fireEvent.change(within(menu).getByLabelText("Find a page"), { target: { value: "queue" } });
    const found = within(menu).getByRole("list", { name: "Pages found" });
    expect(within(found).getAllByRole("button").map((b) => b.textContent)).toEqual(["Work queuesMANAGE", "Work queue insightsOPERATIONAL INSIGHTS"]);
    fireEvent.click(within(found).getByRole("button", { name: /^Work queues/ }));
    await waitFor(() => expect(screen.getByRole("navigation", { name: "You are here" }).textContent).toBe("Manage›Work queues"));

    // one search: pages and agents; an agent opens the repository searched for it
    const search = screen.getByLabelText("Search pages and agents");
    fireEvent.focus(search);
    fireEvent.change(search, { target: { value: "invoice" } });
    const results = await screen.findByRole("group", { name: "Search results" });
    const agent = await within(results).findByRole("link", { name: /^Invoice intake/ });
    expect(agent.getAttribute("href")).toBe("#/automation?q=Invoice%20intake");
    fireEvent.change(search, { target: { value: "audit" } });
    fireEvent.click(within(screen.getByRole("group", { name: "Search results" })).getByRole("button", { name: /^Audit Trail/ }));
    await waitFor(() => expect(screen.getByRole("navigation", { name: "You are here" }).textContent).toBe("Administration›Audit Trail"));
    location.hash = "";
  });

  it("shows Runners as AA's Devices: search in a chosen field, filters, settings for selected runners, out of service, details, pools", async () => {
    const { api, calls } = fakeServer({ signedIn: { ...admin, permissions: [...admin.permissions, "devices.manage", "pools.manage"] } as typeof admin });
    render(<ConsoleApp api={api} />);
    await go("Runner Machines");
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
    await go("Users");
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
    await go("Roles");
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
    await go("Teams");
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
    await go("Credential vault");
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
    await go("Audit Trail");
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
    await go("Licenses");
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
    await go("Settings");
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
    await go("Task inbox");
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
    await go("Schedules");
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
    await go("Triggers");
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
    await go("Work queues");
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
    await go("Workflow");
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
    await go("Shared Global values");
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
    await go("Connections");
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
    await go("Agent ToolKits");
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
    await go("Model Connections");
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
    await go("Agent APIs");
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
    await go("Agent tests");
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
    await go("Agent Templates");
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
    await go("Agent Repository");
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
