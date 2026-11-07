import { CrApi, CrFailure } from "../../api/cr";
import type { Alert, AlertKind, AuditRecord, CrError } from "../../api/cr";

/**
 * insights's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
/** The Operations figures (GET analytics). */
export type Operations = { totals: RunStats; duration_distribution: { bucket: string; runs: number }[]; by_day: (RunStats & { day: string })[];
  most_run: (RunStats & { bot_path: string })[]; longest: (RunStats & { bot_path: string })[];
  most_failed: (RunStats & { bot_path: string; failure_rate: number; top_errors: string[] })[];
  failure_reasons?: { code: string; runs: number; agents: number; agent: string | null; last: string }[];
  by_runner: (RunStats & { runner: string })[]; by_source: (RunStats & { source: string })[]; period: { runs_counted: number; capped: boolean };
  /** Runs and failed runs per weekday (Monday first) and hour, in the time zone asked for. */
  by_hour?: { time_zone: string; runs: number[][]; failed: number[][] };
  /** The same totals for the period just before, as long as this one. */
  previous?: RunStats };
/** Runner capacity for a period (GET analytics/capacity): busy time, share in use, waits to start, a weekday-by-hour use heatmap. */
export type Capacity = { from: string; to: string; enabled_runners: number; busy_ms: number; use: number | null; full_time_people: number; avg_wait_ms: number | null;
  p95_wait_ms: number | null; waiting_now: number; runners: { device_id: string; busy_ms: number; runs: number; use: number; avg_wait_ms: number | null;
    /** AA's Devices dashboard: its name and state now, how its runs ended, its last run and most common error. */
    name?: string | null; os?: string | null; status?: string; success?: number; failed?: number; stopped?: number; last_run?: string | null; top_error?: string | null }[];
  heat: { time_zone: string; use: (number | null)[][] }; by_day: { day: string; busy_ms: number }[];
  online?: number; offline?: number; schedules?: number; next_schedule?: string | null; top_errors?: { code: string; runs: number }[] };
/** Audit figures for a period (GET analytics/audit), as AA's Audit dashboard. */
export type AuditFigures = { from: string; to: string; time_zone: string; entries: number; capped: boolean; people: number; sign_ins: number; failed_sign_ins: number;
  by_kind: { kind: string; entries: number }[]; by_actor: { actor: string; entries: number; is: "person" | "runner" | "system" }[];
  by_result: { result: string; entries: number }[]; by_day: { day: string; entries: number }[]; busiest_day: { day: string; entries: number } | null;
  not_done: AuditRecord[] };
export type ValueReport = { currency: string; hourly_cost: number; default_minutes_saved: number; successful_runs: number; hours: number; money: number; full_time_people: number;
  previous_hours: number; previous_money: number; agents: (ValueRow & { bot_path: string; minutes_each: number; own_value: boolean;
    /** Business (step 2): its own cost of an hour, and the runs a month expected (estimated against actual hours a month). */
    hourly_cost?: number; own_cost?: boolean; estimated_runs_month?: number | null; estimated_hours_month?: number | null; actual_hours_month?: number })[];
  folders: (ValueRow & { folder: string })[]; by_day: (ValueRow & { day: string })[];
  /** Work queues: an item done counts its queue's minutes saved (when set) instead of its agent's. */
  queues?: (ValueRow & { queue_id: string; name: string; minutes_each?: number | null; hourly_cost?: number | null })[];
  /** This year's goal (0: none), saved so far this year, and at this pace by its end. */
  goal?: { hours: number; money: number; year_hours: number; year_money: number; pace_hours: number; pace_money: number } };
/** A dashboard e-mailed on a schedule: to whom, each day, week (on a weekday) or month (its first day), at a time in a time zone. */
export type DashMail = { id: string; recipients: string; every: "day" | "week" | "month"; at_time: string; weekday?: string | null; time_zone: string; created_by: string;
  created: string; next_send: string; last_sent?: string | null; last_error?: string | null };
/** Business > Agents: an agent whose runs return values. */
export type BusinessAgent = { bot_path: string; values: string[]; kpis: string[]; runs: number; runs_with_values: number; last_run?: string | null };
/** A dashboard: its owner's, or shared with roles, teams, users or everyone (*). */
export type CustomDashboard = { id: string; name: string; description?: string | null; owner: string; shared_with: string[]; widgets: DashWidget[]; can_edit: boolean; updated?: string };
/** A widget with its figures: value for a number, points for the others, or why it could not be worked out. */
export type WidgetData = DashWidget & { value?: number | null; points?: { label: string; value: number | null }[]; error?: string };
/** One agent's statistics for a period (GET analytics/agent). */
export type AgentStats = { bot_path: string; totals: RunStats; by_day: (RunStats & { day: string })[]; errors: { code: string; runs: number }[]; by_runner: (RunStats & { runner: string })[];
  last_run?: { id: string; status: string; created: string } | null; last_failure?: { id: string; created: string; code: string } | null };
/** An agent's outputs over a period: how often each had a value, its common values and, for numbers, the range. */
export type VariableProfile = { runs: number; variables: { variable: string; runs: number; runs_with_value: number; min?: number; max?: number; avg?: number; common?: { value: string; runs: number }[];
  /** The data profile (Business, step 2): kind, total, different values, the total per day. */
  kind?: string; sum?: number; distinct?: number; by_day?: { day: string; sum: number }[] }[];
  /** The latest 20 runs that returned values. */
  recent?: { id: string; status: string; created: string; outputs: Record<string, unknown> }[] };
/** An alert rule: one condition watched once a minute, and who hears of it (Operational Insights > Alerts). */
export type AlertRule = { id: string; name: string; kind: AlertKind; scope?: string | null; threshold?: number | null; window_minutes?: number | null; notify_to: string[];
  webhook_url?: string | null; severity: "warning" | "critical"; enabled: boolean; created_by?: string; updated?: string; updated_by?: string; last_checked?: string | null; open?: number };
/** Run figures of a group: counts, success rate (of finished runs) and timing. */
export type RunStats = { runs: number; success: number; failed: number; stopped: number; active: number; success_rate: number | null; total_ms: number;
  avg_ms: number | null; p95_ms: number | null; max_ms: number | null };
/** Value saved for a period (GET analytics/value): hours of manual work and money, per agent, folder and day, and the period before. */
export type ValueRow = { runs: number; minutes: number; hours: number; money: number; run_hours: number };
/** A dashboard widget: a figure from the runs or from a KPI agents record, shown as a number, bars, a line, a table or a pie. */
export type DashWidget = { title?: string; type?: "number" | "bars" | "line" | "table" | "pie"; source?: "kpi" | "runs" | "output"; kpi?: string; agg?: "sum" | "avg" | "count" | "min" | "max" | "last";
  metric?: "runs" | "success_rate" | "failed" | "avg_seconds" | "hours_saved"; bot?: string; group?: "none" | "day" | "week" | "month" | "agent" | "value"; by?: string; days?: number };

declare module "../../api/cr" {
  interface CrApi {
    acknowledgeAlerts(ids: string[]): Promise<{
      acknowledged: number;
    }>;
    alertRules(): Promise<AlertRule[]>;
    saveAlertRule(id: string | null, b: Record<string, unknown>): Promise<{
      id: string;
    }>;
    setAlertRuleEnabled(id: string, enabled: boolean): Promise<void>;
    deleteAlertRule(id: string): Promise<void>;
    testAlertRule(id: string): Promise<{
      inbox?: string[];
      emailed?: string[];
      not_emailed?: string[];
      note?: string;
      webhook?: string;
    }>;
    dashboards(): Promise<CustomDashboard[]>;
    dashboardKpis(): Promise<{
      bot_path: string;
      name: string;
      n: number;
    }[]>;
    saveDashboard(id: string | null, b: {
      name: string;
      description?: string;
      shared_with: string[];
      widgets: DashWidget[];
    }): Promise<{
      id: string;
    }>;
    deleteDashboard(id: string): Promise<void>;
    dashboardMail(id: string): Promise<DashMail[]>;
    addDashboardMail(id: string, b: {
      recipients: string;
      every: string;
      at_time: string;
      weekday?: string;
      time_zone: string;
    }): Promise<{
      id: string;
    }>;
    stopDashboardMail(schedule: string): Promise<void>;
    sendDashboardMail(schedule: string): Promise<{
      sent_to: number;
    }>;
    dashboardData(id: string, tz?: string): Promise<{
      id: string;
      name: string;
      widgets: WidgetData[];
    }>;
    value(params: Record<string, string>): Promise<ValueReport>;
    businessAgents(params: Record<string, string>): Promise<BusinessAgent[]>;
    agentStatsFor(params: Record<string, string>): Promise<AgentStats>;
    agentVariablesFor(params: Record<string, string>): Promise<VariableProfile>;
    capacity(params: Record<string, string>): Promise<Capacity>;
    auditFigures(params: Record<string, string>): Promise<AuditFigures>;
    agentStats(path: string, days: string): Promise<AgentStats>;
    agentVariables(path: string, days: string): Promise<VariableProfile>;
    /** One Operations list as CSV: most_run, longest, most_failed, by_runner, by_day, by_source or run_history. */
    operationsCsv(report: string, params: Record<string, string>): Promise<string>;
    /** Operations figures for a period (days, or from and to) and filters (bot, device, user, source): only agents the caller may see. */
    operations(params: Record<string, string>): Promise<Operations>;
  }
}

CrApi.prototype.acknowledgeAlerts = function (ids: string[]) { return this.request<{ acknowledged: number }>("POST", "alerts/acknowledge", { ids }); };
CrApi.prototype.alertRules = async function () { return (await this.request<{ rules?: AlertRule[] }>("GET", "alert-rules")).rules ?? []; };
CrApi.prototype.saveAlertRule = function (id: string | null, b: Record<string, unknown>) { return this.request<{ id: string }>(id ? "PUT" : "POST", "alert-rules" + (id ? "/" + encodeURIComponent(id) : ""), b); };
CrApi.prototype.setAlertRuleEnabled = async function (id: string, enabled: boolean) { await this.request("PATCH", "alert-rules/" + encodeURIComponent(id), { enabled }); };
CrApi.prototype.deleteAlertRule = async function (id: string) { await this.request("DELETE", "alert-rules/" + encodeURIComponent(id)); };
CrApi.prototype.testAlertRule = function (id: string) { return this.request<NonNullable<Alert["delivery"]>>("POST", "alert-rules/" + encodeURIComponent(id) + "/test"); };
// ---- Dashboards of business KPIs and run figures ----

CrApi.prototype.dashboards = async function () { return (await this.request<{ dashboards?: CustomDashboard[] }>("GET", "dashboards")).dashboards ?? []; };
CrApi.prototype.dashboardKpis = async function () { return (await this.request<{ kpis?: { bot_path: string; name: string; n: number }[] }>("GET", "dashboards/kpis")).kpis ?? []; };
CrApi.prototype.saveDashboard = function (id: string | null, b: { name: string; description?: string; shared_with: string[]; widgets: DashWidget[] }) {
  return this.request<{ id: string }>(id ? "PUT" : "POST", "dashboards" + (id ? "/" + encodeURIComponent(id) : ""), b); };
CrApi.prototype.deleteDashboard = async function (id: string) { await this.request("DELETE", "dashboards/" + encodeURIComponent(id)); };
CrApi.prototype.dashboardMail = async function (id: string) { return (await this.request<{ schedules?: DashMail[] }>("GET", "dashboards/" + encodeURIComponent(id) + "/mail")).schedules ?? []; };
CrApi.prototype.addDashboardMail = function (id: string, b: { recipients: string; every: string; at_time: string; weekday?: string; time_zone: string }) {
  return this.request<{ id: string }>("POST", "dashboards/" + encodeURIComponent(id) + "/mail", b); };
CrApi.prototype.stopDashboardMail = async function (schedule: string) { await this.request("DELETE", "dashboards/mail/" + encodeURIComponent(schedule)); };
CrApi.prototype.sendDashboardMail = function (schedule: string) { return this.request<{ sent_to: number }>("POST", "dashboards/mail/" + encodeURIComponent(schedule) + "/send"); };
CrApi.prototype.dashboardData = function (id: string, tz?: string) { return this.request<{ id: string; name: string; widgets: WidgetData[] }>("GET", "dashboards/" + encodeURIComponent(id) + "/data" + (tz ? "?tz=" + encodeURIComponent(tz) : "")); };
CrApi.prototype.value = function (params: Record<string, string>) { return this.request<ValueReport>("GET", "analytics/value?" + new URLSearchParams(params).toString()); };
CrApi.prototype.businessAgents = async function (params: Record<string, string>) { return (await this.request<{ agents?: BusinessAgent[] }>("GET", "analytics/business-agents?" + new URLSearchParams(params).toString())).agents ?? []; };
CrApi.prototype.agentStatsFor = function (params: Record<string, string>) { return this.request<AgentStats>("GET", "analytics/agent?" + new URLSearchParams(params).toString()); };
CrApi.prototype.agentVariablesFor = function (params: Record<string, string>) { return this.request<VariableProfile>("GET", "analytics/variables?" + new URLSearchParams(params).toString()); };
CrApi.prototype.capacity = function (params: Record<string, string>) { return this.request<Capacity>("GET", "analytics/capacity?" + new URLSearchParams(params).toString()); };
CrApi.prototype.auditFigures = function (params: Record<string, string>) { return this.request<AuditFigures>("GET", "analytics/audit?" + new URLSearchParams(params).toString()); };
CrApi.prototype.agentStats = function (path: string, days: string) { return this.request<AgentStats>("GET", "analytics/agent?" + new URLSearchParams({ path, days }).toString()); };
CrApi.prototype.agentVariables = function (path: string, days: string) { return this.request<VariableProfile>("GET", "analytics/variables?" + new URLSearchParams({ path, days }).toString()); };
/** One Operations list as CSV: most_run, longest, most_failed, by_runner, by_day, by_source or run_history. */

CrApi.prototype.operationsCsv = async function (report: string, params: Record<string, string>) {
  const res = await this.fetcher(this.base + "analytics/export?" + new URLSearchParams({ ...params, report }).toString(), { method: "GET", credentials: "same-origin" });
  const text = await res.text();
  if (!res.ok) { let e: CrError = { code: "HTTP." + res.status, message: res.statusText || "Export failed" }; try { e = JSON.parse(text).error ?? e; } catch { /* not JSON */ } throw new CrFailure(res.status, e); }
  return text;
};
/** Operations figures for a period (days, or from and to) and filters (bot, device, user, source): only agents the caller may see. */

CrApi.prototype.operations = function (params: Record<string, string>) { return this.request<Operations>("GET", "analytics?" + new URLSearchParams(params).toString()); };
