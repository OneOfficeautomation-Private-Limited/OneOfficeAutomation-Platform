/**
 * The typed client for One CommandCenter API (the same routes today's console uses). The browser keeps the session
 * in an HttpOnly cookie; every change carries X-OOA-Request, which the server needs against cross-site requests.
 */
export type CrUser = { id: string; username: string; display_name: string; role: string; permissions: string[]; roles?: string[];
  must_change?: boolean; mfa_enabled?: boolean; mfa_setup_required?: boolean; leads_team?: boolean; announcement?: string; license_banner?: string;
  /** Settings: sign out after this many minutes without activity; and days left before the password must be changed (within the warning). */
  idle_minutes?: number; password_expires_in_days?: number;
  /** Settings: which One CommandCenter this is (shown in the top bar), and the time zone new schedules start with. */
  environment?: Environment; default_time_zone?: string };
/** This One CommandCenter's name and colour (Settings > General). */
export type Environment = { label: string; colour: string };
/** Signing in: the person, or (two-step sign-in) a token to send with the code. */
export type SignIn = { user?: CrUser; token?: string; mfa_required?: boolean; mfa_token?: string };
/** The Home page's figures (GET dashboard). */
export type Dashboard = {
  running: number; queued: number; success_24h: number; failed_24h: number; success_rate_24h?: number | null; avg_duration_ms_24h?: number | null;
  devices_online: number; devices_total: number; bots: number; schedules: number; queue_items_waiting?: number; hours_saved_30d?: number; open_tasks?: number;
  my_recent_agents?: { path: string; latest_version: number; locked_by?: string | null; updated?: string }[];
  my_recent_runs?: { id: string; bot_path: string; status: string; created?: string; finished?: string }[];
  days?: { date: string; success: number; failed: number; stopped: number; active: number }[];
};
/** One work queue's insights for a period (GET analytics/queues). */
export type QueueInsight = { id: string; name: string; sla_minutes?: number | null; processing: boolean; bot_path?: string | null; pool_id?: string | null;
  counts: Record<string, number>; waiting: number; oldest_waiting?: string | null; overdue?: number; at_risk?: number; added: number; finished: number; success: number; failed: number;
  success_rate: number | null; in_time_rate: number | null; per_hour: number; avg_handling_ms: number | null; avg_wait_ms: number | null; runners_needed: number | null;
  errors: { code: string; items: number }[]; by_day: { day: string; added: number; finished: number }[] };
/** A SCIM token for an identity provider (the token itself is shown only when made). */
export type ScimToken = { id: string; name: string; created: string; created_by: string; last_used?: string | null };
/** An export bundle: each agent's exact version with a checksum (and the agents they call), to import in another One CommandCenter. */
export type AgentBundle = { format: string; format_version?: number; exported_at?: string; exported_by?: string; source?: string; checksum?: string; toolkits?: string[];
  agents: { path: string; bot_id?: string; name?: string; version: number; production?: boolean; included_as?: string; sha256?: string; content?: unknown }[] };
/** What happened to each agent of an import (or promotion): created, updated, skipped, failed or not imported. */
export type ImportResult = { ok: boolean; imported: number; results: { path: string; status: string; version?: number; error?: { code?: string; message: string } }[] };
/** The code analysis policy: each rule on or off, error or warning, its settings; and whether check-ins with errors are refused. */
export type CodePolicy = { enforce_at_checkin: boolean; rules: Record<string, { enabled: boolean; severity: "error" | "warning"; description: string; params: Record<string, unknown> }> };
/** A version's code analysis: errors, warnings and each finding. */
export type CodeReport = { path: string; version: number; errors: number; warnings: number; passed: boolean; analyzed?: string; analyzed_by?: string;
  violations: { rule: string; severity: "error" | "warning"; step?: string | null; message: string }[] };
/** A file kept in the repository (a template, workbook or list agents use); runners receive it before a run. */
export type RepoFile = { path: string; size: number; sha256?: string; content_type?: string; updated?: string; updated_by?: string; version: number; folder: string; access: string };
export type AlertKind = "run_failed" | "failure_rate" | "run_long" | "runner_offline" | "queue_overdue" | "ai_budget" | "agent_raised";
/** An alert a rule raised: open, acknowledged (seen) or cleared (its state ended, or its rule was deleted). */
export type Alert = { id: string; rule_id: string; rule_name?: string; kind: AlertKind; subject: string; subject_label?: string; message: string; severity: "warning" | "critical";
  status: "open" | "acknowledged" | "cleared"; raised: string; cleared?: string | null; acknowledged?: string | null; acknowledged_by?: string | null; link?: string | null;
  delivery?: { inbox?: string[]; emailed?: string[]; not_emailed?: string[]; note?: string; webhook?: string } | null };
export type CrError = { code: string; message: string; hint?: string; suggestedFix?: string };
/** A run of an agent, as the runs list gives it; a running run carries its progress. */
export type Run = { id: string; bot_path: string; version?: number; device_id?: string; device_name?: string | null; status: string;
  requested_by?: string; run_as?: string | null; run_mode?: string; source?: string; created?: string; started?: string | null; finished?: string | null;
  duration_ms?: number | null; error?: string | null; pause_requested?: boolean; cancel_requested?: boolean; steps?: number | null;
  progress?: { total: number; finished: number; action?: string; step_id?: string }; inputs?: string | Record<string, unknown> | null; outputs?: string | Record<string, unknown> | null };
/** One model call a run made: what for, which model, tokens, time, what was masked and the tools it asked for. */
export type AiCall = { ts: string; purpose?: string; provider?: string; model?: string; connection?: string; input_tokens?: number; output_tokens?: number;
  latency_ms?: number; masked?: Record<string, number> | null; tool_calls?: string[] | null };
/** One event a run reported (step started, finished or failed, a log line, a variable set). */
export type RunEvent = { seq: number; type: string; ts?: number | string; stepId?: string; data?: Record<string, unknown> };
/** A page an optional module adds (Document Automation, for one): where its own pages are. */
export type ConsolePlugin = { id: string; name: string; description: string; path: string };
/** A machine running the OneRunner (AA: a device), as GET devices gives it. */
export type Runner = { id: string; name: string; hostname?: string; os?: string; agent_version?: string; enabled: boolean;
  status: "online" | "offline" | "disabled"; last_seen?: string | null; registered?: string; registered_by?: string; log_level?: string;
  device_type?: string; session_state?: string | null; after_run?: string; lifespan?: string; up_to_date?: boolean; update_requested?: boolean | string | null;
  version_status?: string; default_users?: string[]; running?: number; toolkits?: string | null;
  nickname?: string | null; description?: string | null; maintenance?: "drain" | "stop" | null; maintenance_since?: string | null; maintenance_by?: string | null;
  cpu_pct?: number | null; mem_pct?: number | null; cpu_max?: number | null; mem_max?: number | null; cpu_limit?: number; mem_limit?: number; over_limit?: string };
/** Logs or a screen picture a runner sent when asked: waiting while asked and not answered, unanswered after 2 minutes. */
export type RunnerFile = { taken?: string; requested_by?: string | null; error?: string | null; content?: string | null; asked?: string; waiting?: boolean; unanswered?: boolean };
/** A user as the Users page lists them. */
export type CrPerson = { id: string; username: string; display_name?: string | null; first_name?: string | null; last_name?: string | null; description?: string | null;
  email?: string | null; role: string; roles?: string[]; enabled: boolean; must_change?: boolean; last_login?: string | null; created?: string | null;
  locked_until?: string | null; mfa_enabled?: boolean };
/** A role: built-in (fixed, may be switched off in Settings) or custom; members hold it as main or further role. */
export type CrRole = { name: string; builtin: boolean; description?: string | null; permissions: string[]; switched_off?: boolean;
  members?: string[]; users?: number; created_by?: string | null; created?: string | null };
/** A team: named team:NAME in folder permissions, vault folders and work queues. */
export type CrTeam = { id: string; name: string; description?: string | null; created?: string; created_by?: string;
  members: { username: string; team_role: "member" | "lead" }[]; used_in?: string[] };
/** A vault folder (AA: locker) as the caller sees it: their place in it, members, credentials, an external vault link. */
export type VaultFolder = { name: string; description?: string | null; owners: string; consumers?: string | null; managers: string[]; participants: string[];
  my_role: "owner" | "manager" | "participant" | "consumer"; can_manage: boolean; created_by?: string;
  external?: { provider: string; url?: string; region?: string; [k: string]: string | undefined } | null;
  credentials: { name: string; attribute: string; updated?: string; updated_by?: string }[];
  credential_info?: ({ name: string; owner?: string | null; user_attributes?: string[]; my_values?: string[] } & CredentialExpiry)[] };
/** A credential's expiry date (yyyy-MM-dd) and how near it is; none when it does not expire. */
export type CredentialExpiry = { expires?: string | null; days_left?: number | null; expired?: boolean; expiring_soon?: boolean };
export type VaultCredential = { locker: string; name: string; owner?: string | null; description?: string | null; can_change: boolean; my_values: string[];
  attributes: { attribute: string; user_provided: boolean; description?: string | null; masked?: boolean; password_only?: boolean; updated?: string; updated_by?: string }[] } & CredentialExpiry;
/** A value the person must fill in themselves (a user-provided attribute of a credential their runs use). */
export type VaultRequest = { locker: string; name: string; attribute: string; description?: string | null; credential_description?: string | null; password_only?: boolean };
export type VaultStatus = { key_source: string; key_source_detail?: string; key_fingerprint: string; vault_folders: number; values: number; personal_values: number;
  external_links: number; can_rotate: boolean };
/** One audit record: who did what to which object, with what result, from where; hash-chained. */
export type AuditRecord = { seq: number; ts: string; actor: string; action: string; object_type?: string | null; object_id?: string | null;
  details?: string | null; result: string; ip?: string | null };
/** The licence of this One CommandCenter (unlicensed, evaluation, active, expiring, grace, expired …) and its seats. */
export type LicenseInfo = { state: string; customer?: string; edition?: string; features?: string[]; id?: string; issued?: string; expires?: string; days_left?: number; evaluation_ends?: string;
  grace_days?: number; grace_until?: string; bound_to?: string; problem?: string; install_id?: string; requires_server_database?: boolean; database_steps?: string;
  seats?: Record<string, { total: number; used: number }> };
export type LicenseUsage = { seats: Record<string, { label: string; total: number; used: number; idle: number }>;
  holders: { name: string; principal: string; license_type: string; assigned?: string; last_active?: string | null; runs_30_days: number; idle: boolean }[];
  trend: { snap_day: string; license_type: string; used: number; total: number }[] };
/** A field of a task's form (made in Studio's form designer). */
export type TaskFormField = { id: string; type: string; label?: string; text?: string; placeholder?: string; options?: string[]; required?: boolean; help?: string;
  min?: number; max?: number; default?: unknown };
/** A task a workflow gives a person or role: fill in a form, approve, or read a notice. */
export type HumanTask = { id: string; title: string; description?: string | null; kind: "form" | "approval" | "notification"; status: string; assignee?: string;
  claimed_by?: string | null; completed_by?: string | null; created?: string; due?: string | null; completed?: string | null; overdue?: boolean; can_act?: boolean; outcome?: string | null;
  form?: { fields?: TaskFormField[]; submitLabel?: string } | null; data?: Record<string, unknown>; result?: Record<string, unknown> | null; workflow_run_id?: string; stage?: string | null; stages?: { id: string; name: string }[];
  /** Where it comes from: its workflow's name and the request's reference. */
  workflow?: string | null; reference?: string | null };
/** A holiday calendar: dates schedules skip, and (gap G34) the weekend agents' working-day steps skip too. */
export type HolidayCalendar = { name: string; dates: string[]; weekend?: string[]; updated?: string; updated_by?: string };
/**
 * A schedule: an agent run on a runner, a pool or the run-as user's default runner, every N minutes, daily, weekly,
 * monthly (rule like "days=1,15,last" or "weekday=first MON;every=3;start=2026-11") or by a cron expression (rule).
 */
export type Schedule = { id: string; name: string; bot_path: string; device_id?: string | null; pool_id?: string | null; run_as?: string | null; kind: "every" | "daily" | "weekly" | "monthly" | "cron";
  every_minutes?: number | null; at_time?: string | null; days?: string | null; rule?: string | null; time_zone: string; holiday_calendar?: string | null; missed_runs?: string | null; overlap?: string | null;
  created_by?: string; next_run?: string | null; last_run?: string | null; enabled: boolean; inputs?: string | Record<string, unknown> | null;
  /** Its last run, and how that run ended (queued, running, success, failed or stopped). */
  last_run_id?: string | null; last_status?: string | null };
/** A draft schedule's next times: UTC (time), in its time zone (local, with the offset), and whether a holiday skips it. */
export type SchedulePreview = { kind: string; time_zone: string; rule?: string; next_runs: { time: string; local: string; holiday: boolean }[] };
/** A trigger: an event that starts an agent, watched on a runner or checked by One CommandCenter (evaluated_on). */
export type Trigger = { id: string; name: string; kind: string; bot_path: string; device_id?: string | null; run_as?: string | null; created_by?: string; enabled: boolean;
  status?: string | null; evaluated_on?: "runner" | "server" | null; windows_only?: boolean; fire_count?: number; last_fired?: string | null; last_error?: string | null;
  folder?: string | null; pattern?: string | null; config?: Record<string, unknown> | null };
/** A work queue: items an agent works one by one on a runner pool, with a service level and attempts per item. */
export type WorkQueue = { id: string; name: string; description?: string | null; bot_path?: string | null; pool_id?: string | null; sla_minutes?: number | null; max_attempts: number;
  model_id?: string | null; processing: boolean; run_as?: string | null; counts: Record<string, number>; overdue?: number; encrypt_data?: boolean | null };
export type WorkItem = { id: string; reference?: string | null; status: string; attempts: number; priority: number; created?: string; finished?: string | null; defer_until?: string | null;
  data: unknown; result?: unknown; error?: unknown; run_id?: string | null; tags?: string[]; error_kind?: "business" | "system" | null };
/** The fields every item of a queue has (AA's work item template). */
export type ItemModel = { id: string; name: string; description?: string | null; queues?: number; fields: { name: string; type: "text" | "number" | "boolean" | "date"; required?: boolean; display?: boolean }[] };
export type GlobalValue = { name: string; value: unknown; changeable: boolean; my_value?: unknown; updated?: string; updated_by?: string;
  /** Checked by the server: text, number, boolean, date, list, record, or any (values made before types). */
  type?: "text" | "number" | "boolean" | "date" | "list" | "record" | "any";
  /** Values per team (user group): runs of the team's members use them unless the person set their own. */
  team_values?: { team_id: string; team_name: string; value: unknown }[] };
/** A subscription to One CommandCenter's events (Administration > Event subscriptions); the secret itself is never sent back. */
export type EventSubscription = { id: string; name: string; url: string; events: string[]; format: "json" | "splunk"; filters: string[]; enabled: boolean;
  has_secret: boolean; last_status?: string | null; last_error?: string | null; last_delivery?: string | null; failures: number; created?: string; created_by?: string;
  this_node?: { delivered: number; failed: number; dropped: number } };
export type EventSubscriptionList = { subscriptions: EventSubscription[]; types: string[]; allow_http: boolean; allow_private: boolean; metrics_token: boolean; node?: string };
/** An environment lock a run holds (Manage > Environment locks); expired: its lease ran out, so another run may take it. */
/** A suspended run's wait (long-running agents, G36): what it waits for, until when, and the run that carried on. */
export type RunWait = { id: string; run_id: string; bot_path: string; version?: number; step_id?: string | null; kind: "time" | "task" | "queue_item" | "callback" | "job";
  ref?: string | null; status: "waiting" | "resumed" | "timed_out" | "cancelled"; resume_at?: string | null; timeout_at?: string | null; created?: string;
  created_by?: string | null; run_as?: string | null; resumed_run_id?: string | null; finished?: string | null; device_name?: string | null; suspended?: string | null };
export type EnvironmentLock = { name: string; run_id: string; device_id?: string | null; device_name?: string | null; bot_path?: string | null; held_by?: string | null;
  acquired: string; expires: string; comment?: string | null; run_status?: string | null; expired?: boolean };
export type SavedConnection = { name: string; package: string; description?: string | null; values?: Record<string, unknown>; credentials?: Record<string, string>; updated?: string; updated_by?: string };
/** A toolkit that takes a connection, and the inputs one holds (credential: a vault reference). */
export type ConnectionKind = { package: string; name: string; inputs: { name: string; description?: string; credential?: boolean }[] };
export type Toolkit = { id: string; name: string; version?: string; versions?: string[]; description?: string | null; publisher?: string | null; actions: number; conditions?: number;
  enabled: boolean; used_by: number; custom?: boolean };
export type ModelConnection = { name: string; provider: string; model?: string | null; model_type?: string | null; base_url?: string | null; key_ref?: string | null;
  description?: string | null; allowed_roles?: string[]; monthly_tokens?: number | null; tokens_this_month?: number; calls_per_minute?: number | null; calls_per_run?: number | null;
  pii_analyzer_url?: string | null; content_check?: string | null; updated?: string; updated_by?: string };
export type AgentApi = { id: string; name: string; bot_path: string; device_id?: string | null; pool_id?: string | null; serverless?: boolean; timeout_seconds: number; enabled: boolean;
  created_by?: string; created?: string; last_used?: string | null; calls: number; url: string };
export type TestRunSummary = { id: string; started: string; finished?: string | null; total: number; passed: number; status: string; version?: number; started_by?: string | null };
export type TestSet = { id: string; name: string; description?: string | null; bot_path: string; cases: { name: string; inputs?: Record<string, unknown>; expect?: Record<string, unknown> }[]; last_run?: TestRunSummary | null };
export type RepoAgent = { path: string; bot_id?: string; name?: string; description?: string | null; latest_version: number; production_version?: number | null; locked_by?: string | null;
  updated?: string; updated_by?: string; folder?: string; access?: string; labels?: { label: string; version: number }[]; deleted_at?: string; deleted_by?: string;
  platform?: "Windows" | "Any"; size?: number; analysis?: { errors: number; warnings: number } };
export type RepoFolder = { path: string; description?: string | null; agents: number; has_permissions?: boolean; access?: string };
export type RepoVersion = { version: number; comment?: string | null; author?: string; created?: string; labels: string[] };
export type ProductionRequest = { id: string; path: string; version: number; requested_by: string; requested?: string; comment?: string | null; status: string;
  can_decide?: boolean; decided_by?: string | null; decided?: string | null; note?: string | null };
export type FlowVariable = { name: string; type?: string; direction?: string; required?: boolean; description?: string; default?: unknown; enum?: unknown[] };
export type AgentTemplate = { id: string; source: string; name: string; description?: string; category: string; publisher: string; agents: string[]; folder?: string;
  /** Document Automation processes that install with it (as drafts). */
  processes?: string[] };
/** How installing one of a template's Document Automation processes went. */
export type TemplateProcessResult = { name: string; folder?: string; status: "created" | "skipped" | "failed"; error?: string; note?: string };
/** Runners that share work: a run sent to a pool goes to its least busy online runner. */
export type RunnerPool = { id: string; name: string; description?: string | null; devices: string[]; created_by?: string; created?: string;
  owners?: string[]; consumers?: string[]; can_manage?: boolean };

export class CrFailure extends Error {
  constructor(readonly status: number, readonly error: CrError) { super(error.message); }
  get code() { return this.error.code; }
}

export class CrApi {
  /** base: where the API is, "/api/" when the console is served by One CommandCenter. */
  constructor(readonly base = "/api/", readonly fetcher: typeof fetch = (...a) => fetch(...a)) {}

  /** A request to One CommandCenter's API, for the modules' parts of this client (modules/<id>/api.ts). */
  request<T>(method: string, route: string, body?: unknown): Promise<T> { return this.call<T>(method, route, body); }

  private async call<T>(method: string, route: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = {};
    if (method !== "GET") headers["X-OOA-Request"] = "1";
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const res = await this.fetcher(this.base + route, { method, credentials: "same-origin", headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    const data = text ? JSON.parse(text) : {};
    if (!res.ok) throw new CrFailure(res.status, data.error ?? { code: "HTTP." + res.status, message: res.statusText || "Request failed" });
    return data as T;
  }

  /** The signed-in person (CR.UNAUTHENTICATED when there is no session). */
  me() { return this.call<CrUser>("GET", "auth/me"); }
  login(username: string, password: string, accepted = false) { return this.call<SignIn>("POST", "auth/login", accepted ? { username, password, accepted } : { username, password }); }
  loginCode(mfaToken: string, code: string) { return this.call<SignIn>("POST", "auth/mfa", { mfa_token: mfaToken, code }); }
  async logout() { await this.call("POST", "auth/logout"); }
  /** Single sign-on offered on the sign-in page: OpenID Connect and SAML, each with its button text. */
  async ssoInfo() {
    const r = await this.call<{ enabled?: boolean; label?: string; saml?: { enabled?: boolean; label?: string }; notice?: { text: string; accept: boolean }; environment?: Environment; directory?: boolean }>("GET", "auth/sso");
    return { oidc: r.enabled ? r.label || "Sign in with your organisation account" : null, saml: r.saml?.enabled ? r.saml.label || "Sign in with SAML" : null,
      notice: r.notice ?? null, environment: r.environment ?? null, directory: !!r.directory };
  }
  /** Where a single sign-on starts; the person comes back to `back`, a page of this One CommandCenter. */
  ssoStart(kind: "oidc" | "saml", back: string) { return this.base + (kind === "oidc" ? "auth/sso/start" : "auth/saml/start") + "?return=" + encodeURIComponent(back); }
  async changePassword(current: string, next: string) { await this.call("POST", "auth/password", { current, new: next }); }
  /** Two-step sign-in: a new key for an authenticator app, then turning it on with a code (gives recovery codes once). */
  mfaSetup() { return this.call<{ secret: string; otpauth_uri: string }>("POST", "auth/mfa/setup"); }
  mfaEnable(code: string) { return this.call<{ recovery_codes: string[] }>("POST", "auth/mfa/enable", { code }); }
  /** Whether One AgentStudio is offered on this computer (it lives beside One CommandCenter under ooa server). */
  async studio() { return !!(await this.call<{ studio?: string }>("GET", "health")).studio; }
  /** The optional modules installed, each filling a page of the menu. */
  async plugins() { return (await this.call<{ plugins?: ConsolePlugin[] }>("GET", "plugins")).plugins ?? []; }
  /** Runs: filter=active (queued, running) or history (finished), with status, bot, device, user, source, q, from, to, limit, offset. */
  runs(params: Record<string, string>) { return this.call<{ runs: Run[]; total: number }>("GET", "runs?" + new URLSearchParams(params).toString()); }
  /** One run with its events (only those after event number `after`, to follow a run as it goes). */
  run(id: string, after = 0) { return this.call<Run & { events: RunEvent[] }>("GET", "runs/" + encodeURIComponent(id) + (after ? "?after=" + after : "")); }
  /** The model calls a run made (recorded when it ends; prompts are never kept). */
  async runAiCalls(id: string) { return (await this.call<{ calls?: AiCall[] }>("GET", "runs/" + encodeURIComponent(id) + "/ai-calls")).calls ?? []; }
  /** Readable names of the commands, e.g. "ooa.excel/open" to "Open workbook", for run logs. */
  async actionNames() { return (await this.call<{ actions?: Record<string, string> }>("GET", "catalog")).actions ?? {}; }
  /** Pause, resume or stop a run (stop ends a queued run at once, a running one after its current step). */
  async control(id: string, action: "pause" | "resume" | "stop") { await this.call("POST", "runs/" + encodeURIComponent(id) + "/" + action); }
  /** Runs the agent again on the same runner with the same inputs (its live version); returns the new run. */
  rerun(id: string) { return this.call<{ run_id: string; version?: number }>("POST", "runs/" + encodeURIComponent(id) + "/rerun"); }
  /** The agents in the repository and the runners, for the filters. */
  async agents() { return (await this.call<{ bots?: { path: string }[] }>("GET", "repo")).bots ?? []; }
  async runners() { return (await this.call<{ devices?: { id: string; name: string }[] }>("GET", "devices")).devices ?? []; }
  /** Runners with everything the Runners page shows. */
  async devices() { return (await this.call<{ devices?: Runner[] }>("GET", "devices")).devices ?? []; }
  /** Settings for several runners at once: log_level, device_type, after_run (only those given change). */
  async updateDevices(ids: string[], changes: { log_level?: string; device_type?: string; after_run?: string; cpu_max?: number; mem_max?: number }) {
    return this.call<{ updated: number }>("PATCH", "devices", { ids, ...changes });
  }
  /** Rename, or take out of service (enabled false: gets no work, cannot sign in) and back. */
  async patchDevice(id: string, b: { name?: string; enabled?: boolean; nickname?: string; description?: string }) { await this.call("PATCH", "devices/" + encodeURIComponent(id), b); }
  /** Maintenance: drain (runs finish) or stop (runs stopped); null ends it. The runners take no new work meanwhile. */
  maintenance(ids: string[], mode: "drain" | "stop" | null) { return this.call<{ updated: number; runs_stopped: number }>("POST", "devices/maintenance", { ids, mode }); }
  /** Ask a runner for its logs or a screen picture (sent with its next check-in), and read the latest it sent. */
  async askRunner(id: string, kind: "logs" | "screen") { await this.call("POST", "devices/" + encodeURIComponent(id) + "/" + kind); }
  runnerFile(id: string, kind: "logs" | "screen") { return this.call<RunnerFile>("GET", "devices/" + encodeURIComponent(id) + "/" + kind); }
  async deleteDevice(id: string) { await this.call("DELETE", "devices/" + encodeURIComponent(id)); }
  /** Ask runners to update their program when idle; and whether an update is available. */
  updateRunnerProgram(ids: string[]) { return this.call<{ version?: string }>("POST", "devices/update", { ids }); }
  runnerProgram() { return this.call<{ available: boolean; version?: string; auto?: boolean }>("GET", "devices/update"); }
  /** A one-time key to register a runner (shown once). */
  registrationKey(temporary: boolean) { return this.call<{ key: string; temporary?: boolean }>("POST", "devices/registration-key", { hours: 24, temporary }); }
  /** People who can sign in (users.manage), with their roles (main role first). */
  async users() { return (await this.call<{ users?: CrPerson[] }>("GET", "users")).users ?? []; }
  userDetail(id: string) { return this.call<CrPerson & { further_roles: string[]; teams?: { name: string; team_role?: string }[]; default_device?: string | null }>("GET", "users/" + encodeURIComponent(id)); }
  /** Creates a user; the temporary password is shown once, and they choose their own at the first sign-in. */
  createUser(username: string, displayName: string, role: string) { return this.call<{ username: string; temporary_password: string }>("POST", "users", { username, display_name: displayName, role }); }
  async updateUser(id: string, changes: Partial<{ role: string; enabled: boolean; display_name: string; first_name: string; last_name: string; description: string; email: string; further_roles: string[] }>) {
    await this.call("PATCH", "users/" + encodeURIComponent(id), changes);
  }
  async deleteUser(id: string) { await this.call("DELETE", "users/" + encodeURIComponent(id)); }
  resetPassword(id: string) { return this.call<{ temporary_password: string }>("POST", "users/" + encodeURIComponent(id) + "/reset-password"); }
  async resetTwoStep(id: string) { await this.call("POST", "users/" + encodeURIComponent(id) + "/reset-mfa"); }
  async unlockUser(id: string) { await this.call("POST", "users/" + encodeURIComponent(id) + "/unlock"); }
  async setDefaultRunner(id: string, deviceId: string | null) { await this.call("PUT", "users/" + encodeURIComponent(id) + "/default-device", { device_id: deviceId }); }
  /** Each person's default runner (where their runs go as a run-as user). */
  async defaultRunners() { return (await this.call<{ users?: { username: string; device_id?: string | null; device_name?: string | null }[] }>("GET", "devices/runasusers")).users ?? []; }
  async roles() { return (await this.call<{ roles?: { name: string; builtin: boolean; description?: string; switched_off?: boolean }[] }>("GET", "roles")).roles ?? []; }
  /** Every role (built-in first) with its permissions and holders, and every permission there is. */
  rolesPage() { return this.call<{ roles: CrRole[]; permissions: string[] }>("GET", "roles"); }
  async saveRole(name: string, description: string, permissions: string[]) { await this.call("PUT", "roles/" + encodeURIComponent(name), { description, permissions }); }
  async deleteRole(name: string) { await this.call("DELETE", "roles/" + encodeURIComponent(name)); }
  /** Teams with members (leads first) and where rules name them; people without teams.manage see their own teams. */
  async teams() { return (await this.call<{ teams?: CrTeam[] }>("GET", "teams")).teams ?? []; }
  saveTeam(id: string | null, name: string, description: string) { return this.call<{ id: string; name: string }>(id ? "PUT" : "POST", "teams" + (id ? "/" + encodeURIComponent(id) : ""), { name, description }); }
  async deleteTeam(id: string) { await this.call("DELETE", "teams/" + encodeURIComponent(id)); }
  async setTeamMembers(id: string, members: { username: string; role: "member" | "lead" }[]) { await this.call("PUT", "teams/" + encodeURIComponent(id) + "/members", { members }); }
  // ---- Credential vault: folders (AA's lockers), credentials, people's own values, external vaults, the master key ----
  async vaultFolders() { return (await this.call<{ lockers?: VaultFolder[] }>("GET", "lockers")).lockers ?? []; }
  async vaultRequests() { return (await this.call<{ requests?: VaultRequest[] }>("GET", "vault/requests")).requests ?? []; }
  credential(folder: string, name: string) { return this.call<VaultCredential>("GET", "vault/credential?" + new URLSearchParams({ locker: folder, name })); }
  async saveCredentialDetails(folder: string, name: string, b: { description?: string; owner?: string; user_attributes?: string[]; expires?: string;
    attributes_info?: Record<string, { description: string; masked: boolean; password_only: boolean }> }) { await this.call("PUT", "vault/credential?" + new URLSearchParams({ locker: folder, name }), b); }
  async deleteCredential(folder: string, name: string) { await this.call("DELETE", "vault/credential?" + new URLSearchParams({ locker: folder, name })); }
  /** Stores one attribute's value, encrypted; it is never shown again (masked values). */
  async setCredentialValue(folder: string, name: string, attribute: string, value: string) {
    await this.call("PUT", "lockers/" + encodeURIComponent(folder) + "/" + encodeURIComponent(name) + "/" + encodeURIComponent(attribute), { value });
  }
  /** An unmasked (not secret) value, for owners and managers; audited. */
  credentialValue(folder: string, name: string, attribute: string) { return this.call<{ value: string }>("GET", "vault/value?" + new URLSearchParams({ locker: folder, name, attribute })); }
  async setMyValue(folder: string, name: string, attribute: string, value: string) { await this.call("PUT", "vault/my-value", { locker: folder, name, attribute, value }); }
  async clearMyValue(folder: string, name: string, attribute: string) { await this.call("DELETE", "vault/my-value?" + new URLSearchParams({ locker: folder, name, attribute })); }
  async createVaultFolder(name: string, description: string, consumers: string) { await this.call("POST", "lockers", { name, description, consumers }); }
  async deleteVaultFolder(name: string) { await this.call("DELETE", "lockers/" + encodeURIComponent(name)); }
  async setVaultMembers(name: string, b: { owners: string; managers: string; participants: string; consumers: string }) { await this.call("PUT", "lockers/" + encodeURIComponent(name) + "/members", b); }
  async linkExternalVault(name: string, b: Record<string, string>) { await this.call("PUT", "lockers/" + encodeURIComponent(name) + "/external", b); }
  async unlinkExternalVault(name: string) { await this.call("DELETE", "lockers/" + encodeURIComponent(name) + "/external"); }
  testExternalVault(name: string, credential: string, attribute: string) {
    return this.call<{ ok: boolean; length?: number; code?: string; message?: string }>("POST", "lockers/" + encodeURIComponent(name) + "/test", { credential, attribute });
  }
  vaultStatus() { return this.call<VaultStatus>("GET", "vault/status"); }
  rotateVaultKey() { return this.call<{ re_encrypted: number; key_fingerprint: string }>("POST", "vault/rotate"); }
  /** The audit trail: q, actor, action (a trailing "." matches a prefix), object_type, object_id, result, from, to, sort, limit, offset. */
  audit(params: Record<string, string>) { return this.call<{ records: AuditRecord[]; total: number }>("GET", "audit?" + new URLSearchParams(params).toString()); }
  /** The same search as CSV (up to 50,000 records); the export is itself audited. */
  async auditCsv(params: Record<string, string>) {
    const res = await this.fetcher(this.base + "audit/export?" + new URLSearchParams(params).toString(), { method: "GET", credentials: "same-origin" });
    const text = await res.text();
    if (!res.ok) { let e: CrError = { code: "HTTP." + res.status, message: res.statusText || "Export failed" }; try { e = JSON.parse(text).error ?? e; } catch { /* not JSON */ } throw new CrFailure(res.status, e); }
    return text;
  }
  /** Checks the hash chain: valid, how many records, and where it breaks when it does. */
  auditVerify() { return this.call<{ valid: boolean; records: number; broken_at?: number }>("GET", "audit/verify"); }
  // ---- Licenses: the signed licence, seats given to people and runners, usage over time ----
  async license() { return (await this.call<{ license?: LicenseInfo }>("GET", "license")).license ?? { state: "unlicensed" }; }
  async licenseAssignments() { return (await this.call<{ assignments?: { principal_type: "user" | "device"; principal: string; license_type: string }[] }>("GET", "license/assignments")).assignments ?? []; }
  licenseUsage(days = 90) { return this.call<LicenseUsage>("GET", "license/usage?days=" + days); }
  async installLicense(content: string) { await this.call("POST", "license", { content }); }
  /** Gives a seat to a person (user name) or a runner (device id), or takes it back. */
  async assignSeat(licenseType: string, principal: string, assigned: boolean) { await this.call("PUT", "license/assignments", { license_type: licenseType, principal, assigned }); }
  // ---- Settings: every setting by key; saving sends only the ones changed ----
  settings() { return this.call<Record<string, string | number | null>>("GET", "settings"); }
  async saveSettings(changes: Record<string, string | number>) { await this.call("PUT", "settings", changes); }
  async syslogTest() { await this.call("POST", "settings/syslog-test"); }
  async siemTest() { await this.call("POST", "settings/siem-test"); }
  /** G43: a test Adaptive Card to the Teams channel; phone notifications for the signed-in person. */
  async teamsTest() { await this.call("POST", "settings/teams-test"); }
  pushStatus() { return this.call<PushStatus>("GET", "me/push"); }
  pushEnable() { return this.call<PushStatus>("POST", "me/push"); }
  async pushDisable() { await this.call("DELETE", "me/push"); }
  async pushTest() { await this.call("POST", "me/push/test"); }
  /** SCIM provisioning tokens (Settings > Single sign-on): listed without the token; a new one returns it this once. */
  async scimTokens() { return (await this.call<{ tokens?: ScimToken[] }>("GET", "settings/scim-tokens")).tokens ?? []; }
  makeScimToken(name: string) { return this.call<{ id: string; name: string; token: string }>("POST", "settings/scim-tokens", { name }); }
  async revokeScimToken(id: string) { await this.call("DELETE", "settings/scim-tokens/" + encodeURIComponent(id)); }
  /** Settings' directory test: the service account connects; with a name, who it finds, their groups and the role they get. */
  ldapTest(username: string) {
    return this.call<{ ok: boolean; server: string; found?: boolean; username?: string; display_name?: string; email?: string; groups?: string[]; role?: string | null }>("POST", "settings/ldap-test", { username });
  }
  mailTest(to: string) { return this.call<{ to: string }>("POST", "settings/mail-test", { to }); }
  gitStatus() { return this.call<{ configured: boolean; state?: string; at?: string; agents?: number; changed?: boolean; commit?: string; error?: string }>("GET", "git/status"); }
  /** Reads agents changed in Git back as new versions (G70). */
  gitPull() { return this.call<{ imported: { path: string; version: number; created: boolean }[]; conflicts: { path: string; why: string }[];
    problems: { path: string; error: string }[]; deleted_in_git: string[] }>("POST", "git/pull"); }
  gitSync() { return this.call<{ state?: string; at?: string; agents?: number; changed?: boolean; commit?: string; error?: string }>("POST", "git/sync"); }
  backups() { return this.call<{ folder: string; backups: { file?: string; created: string; size_mb: number }[]; state?: string; error?: string; key_note?: string }>("GET", "backups"); }
  backupNow() { return this.call<{ file: string; size_mb: number }>("POST", "backups"); }
  cluster() { return this.call<{ nodes: { node_id: string; leader?: boolean; this_node?: boolean }[] }>("GET", "cluster"); }
  purgeRuns(runDays: number, logDays: number) { return this.call<{ runs_removed: number; log_lines_removed: number }>("POST", "runs/purge", { run_days: runDays, log_days: logDays }); }
  database() { return this.call<Record<string, string | number | boolean | null>>("GET", "database"); }
  /** Human tasks from workflows: status open (open and claimed) or completed; all: everyone's (users.manage). */
  async tasks(status: "open" | "completed", all = false) { return (await this.call<{ tasks?: HumanTask[] }>("GET", "tasks?status=" + status + (all ? "&all=true" : ""))).tasks ?? []; }
  async claimTask(id: string, release = false) { await this.call("POST", "tasks/" + encodeURIComponent(id) + (release ? "/release" : "/claim")); }
  async completeTask(id: string, outcome: string, answers: Record<string, unknown>, comment: string) { await this.call("POST", "tasks/" + encodeURIComponent(id) + "/complete", { outcome, answers, comment }); }
  // ---- Schedules and holiday calendars ----
  async schedules() { return (await this.call<{ schedules?: Schedule[] }>("GET", "schedules")).schedules ?? []; }
  createSchedule(b: Record<string, unknown>) { return this.call<{ id: string; next_run?: string }>("POST", "schedules", b); }
  async updateSchedule(id: string, b: Record<string, unknown>) { await this.call("PUT", "schedules/" + encodeURIComponent(id), b); }
  async setScheduleEnabled(id: string, enabled: boolean) { await this.call("PATCH", "schedules/" + encodeURIComponent(id), { enabled }); }
  async deleteSchedule(id: string) { await this.call("DELETE", "schedules/" + encodeURIComponent(id)); }
  runSchedule(id: string) { return this.call<{ run_id: string }>("POST", "schedules/" + encodeURIComponent(id) + "/run"); }
  /** The next times a draft schedule would run (nothing is saved); each says whether its holiday calendar skips it. */
  previewSchedule(b: Record<string, unknown>) { return this.call<SchedulePreview>("POST", "schedules/preview", b); }
  bulkSchedules(ids: string[], action: "enable" | "disable" | "owner", owner?: string) { return this.call<{ changed: number }>("POST", "schedules/bulk", { ids, action, ...(owner ? { owner } : {}) }); }
  async calendars() { return (await this.call<{ calendars?: HolidayCalendar[] }>("GET", "calendars")).calendars ?? []; }
  /** Saves a calendar's dates; its weekend too when given (days MON..SUN; an empty list means every day is a working day). */
  async saveCalendar(name: string, dates: string[], weekend?: string[]) {
    await this.call("PUT", "calendars/" + encodeURIComponent(name), weekend === undefined ? { dates } : { dates, weekend: weekend.length ? weekend : "NONE" });
  }
  async deleteCalendar(name: string) { await this.call("DELETE", "calendars/" + encodeURIComponent(name)); }
  /** Agents with their live (production) version, for choosing what a schedule, trigger or queue runs. */
  async liveAgents() { return ((await this.call<{ bots?: { path: string; production_version?: number | null }[] }>("GET", "repo")).bots ?? []).filter((b) => b.production_version && b.path.endsWith(".flow.json")); }
  // ---- Triggers: events on a runner (files, windows, processes, services, hotkeys) or checked by One CommandCenter ----
  async triggers() { return (await this.call<{ triggers?: Trigger[] }>("GET", "triggers")).triggers ?? []; }
  /** A webhook trigger answers with its address and secret, shown once. */
  createTrigger(b: Record<string, unknown>) { return this.call<{ id: string; url?: string; secret?: string }>("POST", "triggers", b); }
  async updateTrigger(id: string, b: Record<string, unknown>) { await this.call("PUT", "triggers/" + encodeURIComponent(id), b); }
  async pauseTrigger(id: string, pause: boolean) { await this.call("POST", "triggers/" + encodeURIComponent(id) + (pause ? "/pause" : "/resume")); }
  /** Checks One CommandCenter trigger now instead of waiting for its interval. */
  pollTrigger(id: string) { return this.call<{ fired?: number; error?: string }>("POST", "triggers/" + encodeURIComponent(id) + "/poll"); }
  async deleteTrigger(id: string) { await this.call("DELETE", "triggers/" + encodeURIComponent(id)); }
  // ---- Work queues: queues, their items, item models (AA's work item templates), members ----
  async queues() { return (await this.call<{ queues?: WorkQueue[] }>("GET", "queues")).queues ?? []; }
  queue(id: string) { return this.call<WorkQueue & { members: { principal: string; member_role: string }[]; model?: ItemModel | null }>("GET", "queues/" + encodeURIComponent(id)); }
  saveQueue(id: string | null, b: Record<string, unknown>) { return this.call<{ id: string }>(id ? "PUT" : "POST", "queues" + (id ? "/" + encodeURIComponent(id) : ""), b); }
  async deleteQueue(id: string) { await this.call("DELETE", "queues/" + encodeURIComponent(id)); }
  async setProcessing(id: string, on: boolean) { await this.call("POST", "queues/" + encodeURIComponent(id) + (on ? "/start" : "/stop")); }
  async setQueueMembers(id: string, members: { principal: string; role: string }[]) { await this.call("PUT", "queues/" + encodeURIComponent(id) + "/members", { members }); }
  queueItems(id: string, status: string, q: string, limit = 200, tag = "") { return this.call<{ items: WorkItem[]; total: number }>("GET", "queues/" + encodeURIComponent(id) + "/items?" + new URLSearchParams({ status, q, limit: String(limit), ...(tag ? { tag } : {}) })); }
  queueItem(id: string, itemId: string) { return this.call<WorkItem>("GET", "queues/" + encodeURIComponent(id) + "/items/" + encodeURIComponent(itemId)); }
  async updateQueueItem(id: string, itemId: string, b: { data: unknown; reference: string | null; priority: number }) { await this.call("PUT", "queues/" + encodeURIComponent(id) + "/items/" + encodeURIComponent(itemId), b); }
  /** retry, defer (until a time, or until someone presses Retry) or delete several items. */
  async itemsAction(id: string, action: "retry" | "defer" | "delete", ids: string[], until?: string) { await this.call("POST", "queues/" + encodeURIComponent(id) + "/items/" + action, { ids, ...(until ? { until } : {}) }); }
  /** Adds and removes tags on items (G31). */
  tagItems(id: string, ids: string[], add: string[], remove: string[]) { return this.call<{ changed: number }>("POST", "queues/" + encodeURIComponent(id) + "/items/tag", { ids, add, remove }); }
  /** Releases items stuck in progress whose run has ended; attempts are kept (G31). */
  unlockItems(id: string, ids: string[]) { return this.call<{ unlocked: number }>("POST", "queues/" + encodeURIComponent(id) + "/items/unlock", { ids }); }
  /** Deletes items by status and age; only succeeded and failed ones unless force (G31). */
  purgeItems(id: string, b: { status: string; older_than_hours?: number; tags?: string[]; force?: boolean }) { return this.call<{ deleted: number }>("POST", "queues/" + encodeURIComponent(id) + "/items/purge", b); }
  /** Copies or moves items to another queue as new items (G31). */
  transferItems(id: string, how: "copy" | "move", toQueue: string, ids: string[]) { return this.call<{ count: number; skipped: number }>("POST", "queues/" + encodeURIComponent(id) + "/items/" + how, { to_queue: toQueue, ids }); }
  addItems(id: string, items: unknown[]) { return this.call<{ added: number }>("POST", "queues/" + encodeURIComponent(id) + "/items", { items }); }
  addItemsCsv(id: string, csv: string) { return this.call<{ added: number }>("POST", "queues/" + encodeURIComponent(id) + "/items/csv", { csv }); }
  addItemsXlsx(id: string, file: string, sheet: string | null) { return this.call<{ added: number }>("POST", "queues/" + encodeURIComponent(id) + "/items/xlsx", { file, sheet }); }
  validateItem(id: string, itemId: string, b: { fields?: Record<string, unknown>; reject?: boolean; reason?: string }) { return this.call<{ changed?: string[] }>("POST", "queues/" + encodeURIComponent(id) + "/items/" + encodeURIComponent(itemId) + "/validate", b); }
  async itemsCsv(id: string, status: string) {
    const res = await this.fetcher(this.base + "queues/" + encodeURIComponent(id) + "/items/export" + (status ? "?status=" + encodeURIComponent(status) : ""), { method: "GET", credentials: "same-origin" });
    const text = await res.text();
    if (!res.ok) throw new CrFailure(res.status, { code: "HTTP." + res.status, message: res.statusText || "Export failed" });
    return text;
  }
  exportQueue(id: string, items: boolean) { return this.call<Record<string, unknown>>("GET", "queues/" + encodeURIComponent(id) + "/export" + (items ? "?items=true" : "")); }
  importQueue(definition: unknown, name: string | null) { return this.call<{ name: string; items: number; notes?: string[] }>("POST", "queues/import", { definition, name }); }
  async itemModels() { return (await this.call<{ models?: ItemModel[] }>("GET", "queue-models")).models ?? []; }
  async saveItemModel(id: string | null, b: { name: string; description: string; fields: ItemModel["fields"] }) { await this.call(id ? "PUT" : "POST", "queue-models" + (id ? "/" + encodeURIComponent(id) : ""), b); }
  async deleteItemModel(id: string) { await this.call("DELETE", "queue-models/" + encodeURIComponent(id)); }
  // ---- Workflows: processes chaining agents, people and decisions; their runs ----
  async liveWorkflows() { return ((await this.call<{ bots?: { path: string; production_version?: number | null }[] }>("GET", "repo")).bots ?? []).filter((b) => b.production_version && b.path.endsWith(".process.json")); }
  // ---- Shared Global values ($global.name), and each person's own value where people may change them ----
  async globals() { return (await this.call<{ globals?: GlobalValue[] }>("GET", "globals")).globals ?? []; }
  async saveGlobal(name: string, value: unknown, changeable: boolean, type?: string) { await this.call("PUT", "globals/" + encodeURIComponent(name), { value, changeable, ...(type ? { type } : {}) }); }
  async setTeamGlobal(name: string, teamId: string, value: unknown) { await this.call("PUT", "globals/" + encodeURIComponent(name) + "/teams/" + encodeURIComponent(teamId), { value }); }
  async clearTeamGlobal(name: string, teamId: string) { await this.call("DELETE", "globals/" + encodeURIComponent(name) + "/teams/" + encodeURIComponent(teamId)); }
  async deleteGlobal(name: string) { await this.call("DELETE", "globals/" + encodeURIComponent(name)); }
  async setMyGlobal(name: string, value: unknown) { await this.call("PUT", "globals/" + encodeURIComponent(name) + "/mine", { value }); }
  async clearMyGlobal(name: string) { await this.call("DELETE", "globals/" + encodeURIComponent(name) + "/mine"); }
  // ---- Saved connections: a connector's address and sign-in kept once; secrets are vault references ----
  async savedConnections() { return (await this.call<{ connections?: SavedConnection[] }>("GET", "saved-connections")).connections ?? []; }
  async connectionKinds() { return (await this.call<{ kinds?: ConnectionKind[] }>("GET", "connection-kinds")).kinds ?? []; }
  async saveConnection(name: string, b: { package: string; description: string; values: Record<string, string>; credentials: Record<string, string> }) {
    await this.call("PUT", "saved-connections/" + encodeURIComponent(name), b);
  }
  async deleteConnection(name: string) { await this.call("DELETE", "saved-connections/" + encodeURIComponent(name)); }
  // ---- Event subscriptions (G38): events posted to receivers; the metrics token for GET /metrics ----
  eventSubscriptions() { return this.call<EventSubscriptionList>("GET", "event-subscriptions"); }
  saveEventSubscription(id: string | null, b: Record<string, unknown>) {
    return this.call<EventSubscription & { secret?: string }>(id ? "PUT" : "POST", "event-subscriptions" + (id ? "/" + encodeURIComponent(id) : ""), b);
  }
  async setEventSubscriptionEnabled(id: string, enabled: boolean) { await this.call("PATCH", "event-subscriptions/" + encodeURIComponent(id), { enabled }); }
  async deleteEventSubscription(id: string) { await this.call("DELETE", "event-subscriptions/" + encodeURIComponent(id)); }
  testEventSubscription(id: string) { return this.call<{ ok: boolean; status?: number; error?: string; event_id: string }>("POST", "event-subscriptions/" + encodeURIComponent(id) + "/test"); }
  makeMetricsToken() { return this.call<{ token: string }>("POST", "metrics-token"); }
  async deleteMetricsToken() { await this.call("DELETE", "metrics-token"); }
  // ---- Environment locks: named locks one run holds at a time; administrators give one back ----
  async runWaits(status = "waiting") { return (await this.call<{ waits?: RunWait[] }>("GET", "run-waits?" + new URLSearchParams({ status }))).waits ?? []; }
  async resumeRunWait(id: string) { return this.call<{ run_id: string }>("POST", "run-waits/" + encodeURIComponent(id) + "/resume", {}); }
  async cancelRunWait(id: string) { await this.call("POST", "run-waits/" + encodeURIComponent(id) + "/cancel", {}); }
  async environmentLocks() { return (await this.call<{ locks?: EnvironmentLock[] }>("GET", "environment-locks")).locks ?? []; }
  async releaseEnvironmentLock(name: string) { await this.call("DELETE", "environment-locks?" + new URLSearchParams({ name })); }
  // ---- Agent ToolKits: packages of actions; usage, switching off, versions, signed custom toolkits ----
  async toolkits() { return (await this.call<{ toolkits?: Toolkit[] }>("GET", "toolkits")).toolkits ?? []; }
  async setToolkitEnabled(id: string, enabled: boolean) { await this.call("PATCH", "toolkits/" + encodeURIComponent(id), { enabled }); }
  async toolkitUsers(id: string) { return (await this.call<{ agents?: string[] }>("GET", "toolkits/" + encodeURIComponent(id) + "/used-by")).agents ?? []; }
  toolkitVersions(id: string) { return this.call<{ installed: string; agents: { path: string; version: number; required?: string | null; satisfied: boolean }[] }>("GET", "toolkits/" + encodeURIComponent(id) + "/versions"); }
  updateToolkitRange(id: string, range: string) {
    return this.call<{ results: { path: string; status: string; version?: number; error?: { message?: string } }[] }>("POST", "toolkits/" + encodeURIComponent(id) + "/update-agents", { range });
  }
  async customToolkits() { return (await this.call<{ versions?: { id: string; toolkit_id: string; version: string; name?: string; publisher: string; sha256: string; uploaded?: string; uploaded_by?: string }[] }>("GET", "toolkits/custom")).versions ?? []; }
  async deleteCustomToolkit(id: string) { await this.call("DELETE", "toolkits/custom/" + encodeURIComponent(id)); }
  /** Uploads a signed toolkit jar; its publisher must be trusted. */
  async uploadToolkit(jar: ArrayBuffer) {
    const res = await this.fetcher(this.base + "toolkits/custom", { method: "POST", credentials: "same-origin", headers: { "X-OOA-Request": "1", "Content-Type": "application/java-archive" }, body: jar });
    const text = await res.text();
    const data = text ? JSON.parse(text) : {};
    if (!res.ok) throw new CrFailure(res.status, data.error ?? { code: "HTTP." + res.status, message: res.statusText || "Upload failed" });
    return data as { toolkit_id?: string; version?: string; name?: string };
  }
  async publishers() { return (await this.call<{ publishers?: { publisher: string; public_key: string; added_by?: string; added?: string }[] }>("GET", "toolkits/publishers")).publishers ?? []; }
  async trustPublisher(publisher: string, publicKey: string) { await this.call("POST", "toolkits/publishers", { publisher, public_key: publicKey }); }
  async untrustPublisher(publisher: string) { await this.call("DELETE", "toolkits/publishers/" + encodeURIComponent(publisher)); }
  // ---- AI: model connections (provider, model, key from the vault, roles, budget, privacy) ----
  async modelConnections() { return (await this.call<{ connections?: ModelConnection[] }>("GET", "connections")).connections ?? []; }
  async saveModelConnection(name: string, b: Record<string, unknown>) { await this.call("PUT", "connections/" + encodeURIComponent(name), b); }
  async deleteModelConnection(name: string) { await this.call("DELETE", "connections/" + encodeURIComponent(name)); }
  testModelConnection(b: Record<string, unknown>) { return this.call<{ ok: boolean; ms?: number; answer?: string; message?: string }>("POST", "connections/test", b); }
  // ---- Agent APIs: an agent behind its own address and token ----
  async agentApis() { return (await this.call<{ tasks?: AgentApi[] }>("GET", "apitasks")).tasks ?? []; }
  // ---- Agent tests: test sets of cases, runs scored per case ----
  async testSets() { return (await this.call<{ sets?: TestSet[] }>("GET", "test-sets")).sets ?? []; }
  async deleteTestSet(id: string) { await this.call("DELETE", "test-sets/" + encodeURIComponent(id)); }
  // ---- Agent Repository: folders and their permissions, agents with versions, labels, live production and approvals ----
  async repo(folder?: string) { return (await this.call<{ bots?: RepoAgent[] }>("GET", "repo" + (folder ? "?folder=" + encodeURIComponent(folder) : ""))).bots ?? []; }
  async deletedAgents() { return (await this.call<{ bots?: RepoAgent[] }>("GET", "repo?deleted=true")).bots ?? []; }
  async repoFolders() { return (await this.call<{ folders?: RepoFolder[] }>("GET", "repo/folders")).folders ?? []; }
  createFolder(path: string, description: string) { return this.call<{ path: string }>("POST", "repo/folders", { path, description }); }
  renameFolder(from: string, to: string) { return this.call<{ path: string; agents: number }>("POST", "repo/folders/rename", { from, to }); }
  async deleteFolder(path: string) { await this.call("DELETE", "repo/folders?path=" + encodeURIComponent(path)); }
  async folderPermissions(folder: string) { return (await this.call<{ permissions?: { principal: string; access: string }[] }>("GET", "repo/permissions?folder=" + encodeURIComponent(folder))).permissions ?? []; }
  async saveFolderPermissions(folder: string, permissions: { principal: string; access: string }[]) { await this.call("PUT", "repo/permissions", { folder, permissions }); }
  moveAgent(path: string, folder: string) { return this.call<{ path: string }>("POST", "repo/move", { path, folder }); }
  copyAgent(path: string, to: string) { return this.call<{ path?: string }>("POST", "repo/copy", { path, to }); }
  deleteAgent(path: string) { return this.call<{ deleted: string; called_by?: string[] }>("POST", "repo/delete", { path }); }
  async recoverAgent(path: string) { await this.call("POST", "repo/recover", { path }); }
  async undoCheckout(path: string) { await this.call("POST", "repo/undo-checkout", { path }); }
  async agentVersions(path: string) { return (await this.call<{ versions?: RepoVersion[] }>("GET", "repo/versions?path=" + encodeURIComponent(path))).versions ?? []; }
  agentFlow(path: string, version: number | "production" | "latest") {
    return this.call<{ flow: { id?: string; kind?: string; steps?: unknown[]; variables?: FlowVariable[] } & Record<string, unknown> }>("GET", "repo/bot?path=" + encodeURIComponent(path) + "&version=" + version);
  }
  dependencies(path: string) {
    return this.call<{ agents: { bot_id: string; path?: string | null; found: boolean }[]; called_by: string[]; toolkits: string[] }>("GET", "repo/dependencies?path=" + encodeURIComponent(path));
  }
  async setLabel(path: string, label: string, version: number) { await this.call("PUT", "repo/labels", { path, label, version }); }
  async removeLabel(path: string, label: string) { await this.call("DELETE", "repo/labels?path=" + encodeURIComponent(path) + "&label=" + encodeURIComponent(label)); }
  setProduction(path: string, version: number, comment?: string) { return this.call<{ production_request?: unknown }>("POST", "repo/production", { path, version, comment }); }
  productionRequests(status?: string) {
    return this.call<{ requests: ProductionRequest[]; approval_on: boolean }>("GET", "repo/production-requests" + (status ? "?status=" + status : ""));
  }
  async decideRequest(id: string, how: "approve" | "reject" | "withdraw", note: string) { await this.call("POST", "repo/production-requests/" + encodeURIComponent(id) + "/" + how, { note }); }
  startRun(b: { bot_path: string; device_id?: string; pool_id?: string; inputs: Record<string, unknown> }) { return this.call<{ run_id: string }>("POST", "runs", b); }
  // ---- Agent Templates: ready-made agents to install, share to the region, or load from a signed catalogue file ----
  templates() { return this.call<{ templates: AgentTemplate[]; categories: string[]; can_share?: boolean; can_arrange?: boolean; problem?: string | null }>("GET", "templates"); }
  /** The template folders (nested with /) and the folder of each template, by "source:id" ("" is the top). Administrators. */
  async saveTemplateFolders(folders: string[], placed: Record<string, string>) { await this.call("PUT", "templates/folders", { folders, placed }); }
  installTemplate(source: string, id: string, folder: string) {
    return this.call<{ ok: boolean; folder: string; results: { path: string; status: string; error?: { message?: string } }[]; processes?: TemplateProcessResult[]; processes_ok?: boolean }>(
      "POST", "templates/install", { source, id, folder });
  }
  async shareTemplate(b: { path: string; name: string; description: string; category: string; processes?: string[] }) { await this.call("POST", "templates/share", b); }
  /** Document Automation's processes the person may see (none when it is not set up or not allowed). */
  async documentProcesses() {
    try { const f = await this.call<{ id: string; name: string; folder?: string; isInbox?: boolean }[]>("GET", "docflow/df/flows"); return Array.isArray(f) ? f : []; } catch { return []; }
  }
  uploadCatalogue(text: string) { return this.call<{ templates: number }>("POST", "templates/catalogue", { text }); }
  async pools() { return (await this.call<{ pools?: RunnerPool[] }>("GET", "pools")).pools ?? []; }
  savePool(id: string | null, b: { name: string; description: string; devices: string[]; owners?: string[]; consumers?: string[] }) {
    return this.call<{ id: string }>(id ? "PUT" : "POST", "pools" + (id ? "/" + encodeURIComponent(id) : ""), b);
  }
  async deletePool(id: string) { await this.call("DELETE", "pools/" + encodeURIComponent(id)); }
  /** Home's figures; the runs-per-day chart counts days in the given time zone. */
  // ---- Alerts: rules checked once a minute, and the alerts they raised ----
  alerts(params: Record<string, string>) { return this.call<{ alerts: Alert[]; open: number }>("GET", "alerts?" + new URLSearchParams(params).toString()); }
  async queueInsights(params: Record<string, string>) { return (await this.call<{ queues?: QueueInsight[] }>("GET", "analytics/queues?" + new URLSearchParams(params).toString())).queues ?? []; }
  /** Minutes of manual work one successful run of the agent saves; null uses the default in Settings. */
  async setMinutesSaved(path: string, minutes: number | null) { await this.call("POST", "repo/roi", { path, minutes_saved: minutes }); }
  /** An agent's savings: minutes a run, its own cost of an hour, runs a month expected; null uses the default (or none). */
  async setAgentSavings(path: string, b: { minutes_saved: number | null; hourly_cost: number | null; estimated_runs_month: number | null }) { await this.call("POST", "repo/roi", { path, ...b }); }
  async setQueueSavings(queueId: string, b: { minutes_saved: number | null; hourly_cost: number | null }) { await this.call("POST", "analytics/queue-savings", { queue_id: queueId, ...b }); }
  async setSavingsGoal(hours: number | null, money: number | null) { await this.call("POST", "analytics/goal", { hours, money }); }
  // ---- Agent Repository: export and import, promotion, code analysis, repository files ----
  exportAgents(b: { paths: string[]; version: string; include_dependencies: boolean }) { return this.call<AgentBundle>("POST", "lifecycle/export", b); }
  importAgents(b: { bundle: AgentBundle; conflict: "skip" | "overwrite"; production: boolean; target_folder: string | null }) { return this.call<ImportResult>("POST", "lifecycle/import", b); }
  promoteAgents(b: { paths: string[]; version: string; include_dependencies: boolean; target_url: string; api_key_ref: string; conflict: "skip" | "overwrite"; production: boolean }) {
    return this.call<{ ok: boolean; status: number; target: string; result?: Partial<ImportResult> & Record<string, unknown> }>("POST", "lifecycle/promote", b); }
  codeAnalysis(path: string) { return this.call<CodeReport>("GET", "repo/analysis?path=" + encodeURIComponent(path)); }
  analyzeAgent(path: string) { return this.call<CodeReport>("POST", "repo/analyze", { path }); }
  async codeAnalysisCsv() {
    const res = await this.fetcher(this.base + "repo/analysis/report", { method: "GET", credentials: "same-origin" });
    const text = await res.text();
    if (!res.ok) { let e: CrError = { code: "HTTP." + res.status, message: res.statusText || "Report failed" }; try { e = JSON.parse(text).error ?? e; } catch { /* not JSON */ } throw new CrFailure(res.status, e); }
    return text;
  }
  async repoFiles(folder?: string) { return (await this.call<{ files?: RepoFile[] }>("GET", "repo/files" + (folder ? "?folder=" + encodeURIComponent(folder) : ""))).files ?? []; }
  /** Where a repository file downloads from (the browser follows the link with the session). */
  repoFileUrl(path: string) { return this.base + "repo/files/content?path=" + encodeURIComponent(path); }
  async uploadRepoFile(path: string, file: Blob) {
    const res = await this.fetcher(this.base + "repo/files?path=" + encodeURIComponent(path), { method: "PUT", credentials: "same-origin",
      headers: { "X-OOA-Request": "1", "Content-Type": file.type || "application/octet-stream" }, body: file });
    const text = await res.text();
    let d: { version?: number; error?: CrError } = {};
    try { d = JSON.parse(text); } catch { /* not JSON */ }
    if (!res.ok) throw new CrFailure(res.status, d.error ?? { code: "HTTP." + res.status, message: res.statusText || "Upload failed" });
    return { version: d.version ?? 1 };
  }
  async deleteRepoFile(path: string) { await this.call("DELETE", "repo/files?path=" + encodeURIComponent(path)); }
  dashboard(timeZone?: string) { return this.call<Dashboard>("GET", "dashboard" + (timeZone ? "?tz=" + encodeURIComponent(timeZone) : "")); }
}

/** Phone notifications (G43): whether the server is set, whether this person turned them on, and their private topic. */
export type PushStatus = { available: boolean; enabled: boolean; server: string; topic?: string; subscribe_url?: string };
