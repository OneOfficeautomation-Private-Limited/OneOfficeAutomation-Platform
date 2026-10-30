/**
 * The typed client for the Command Center API (the same routes today's console uses). The browser keeps the session
 * in an HttpOnly cookie; every change carries X-OOA-Request, which the server needs against cross-site requests.
 */
export type CrUser = { id: string; username: string; display_name: string; role: string; permissions: string[]; roles?: string[];
  must_change?: boolean; mfa_enabled?: boolean; mfa_setup_required?: boolean; leads_team?: boolean; announcement?: string; license_banner?: string };
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
export type CrError = { code: string; message: string; hint?: string; suggestedFix?: string };
/** A run of an agent, as the runs list gives it; a running run carries its progress. */
export type Run = { id: string; bot_path: string; version?: number; device_id?: string; device_name?: string | null; status: string;
  requested_by?: string; run_as?: string | null; run_mode?: string; source?: string; created?: string; started?: string | null; finished?: string | null;
  duration_ms?: number | null; error?: string | null; pause_requested?: boolean; cancel_requested?: boolean; steps?: number | null;
  progress?: { total: number; finished: number; action?: string; step_id?: string } };
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
  credential_info?: { name: string; owner?: string | null; user_attributes?: string[]; my_values?: string[] }[] };
export type VaultCredential = { locker: string; name: string; owner?: string | null; description?: string | null; can_change: boolean; my_values: string[];
  attributes: { attribute: string; user_provided: boolean; description?: string | null; masked?: boolean; password_only?: boolean; updated?: string; updated_by?: string }[] };
/** A value the person must fill in themselves (a user-provided attribute of a credential their runs use). */
export type VaultRequest = { locker: string; name: string; attribute: string; description?: string | null; credential_description?: string | null; password_only?: boolean };
export type VaultStatus = { key_source: string; key_source_detail?: string; key_fingerprint: string; vault_folders: number; values: number; personal_values: number;
  external_links: number; can_rotate: boolean };
/** One audit record: who did what to which object, with what result, from where; hash-chained. */
export type AuditRecord = { seq: number; ts: string; actor: string; action: string; object_type?: string | null; object_id?: string | null;
  details?: string | null; result: string; ip?: string | null };
/** The licence of this Command Center (unlicensed, evaluation, active, expiring, grace, expired …) and its seats. */
export type LicenseInfo = { state: string; customer?: string; edition?: string; id?: string; issued?: string; expires?: string; days_left?: number; evaluation_ends?: string;
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
  form?: { fields?: TaskFormField[]; submitLabel?: string } | null; data?: Record<string, unknown>; result?: Record<string, unknown> | null; workflow_run_id?: string };
/** A schedule: an agent run on a runner, a pool or the run-as user's default runner, every N minutes, daily or weekly. */
export type Schedule = { id: string; name: string; bot_path: string; device_id?: string | null; pool_id?: string | null; run_as?: string | null; kind: "every" | "daily" | "weekly";
  every_minutes?: number | null; at_time?: string | null; days?: string | null; time_zone: string; holiday_calendar?: string | null; missed_runs?: string | null; overlap?: string | null;
  created_by?: string; next_run?: string | null; last_run?: string | null; enabled: boolean; inputs?: string | Record<string, unknown> | null };
/** A trigger: an event that starts an agent, watched on a runner or checked by the Command Center (evaluated_on). */
export type Trigger = { id: string; name: string; kind: string; bot_path: string; device_id?: string | null; run_as?: string | null; created_by?: string; enabled: boolean;
  status?: string | null; evaluated_on?: "runner" | "server" | null; windows_only?: boolean; fire_count?: number; last_fired?: string | null; last_error?: string | null;
  folder?: string | null; pattern?: string | null; config?: Record<string, unknown> | null };
/** A work queue: items an agent works one by one on a runner pool, with a service level and attempts per item. */
export type WorkQueue = { id: string; name: string; description?: string | null; bot_path?: string | null; pool_id?: string | null; sla_minutes?: number | null; max_attempts: number;
  model_id?: string | null; processing: boolean; run_as?: string | null; counts: Record<string, number>; overdue?: number };
export type WorkItem = { id: string; reference?: string | null; status: string; attempts: number; priority: number; created?: string; finished?: string | null; defer_until?: string | null;
  data: unknown; result?: unknown; error?: unknown; run_id?: string | null };
/** The fields every item of a queue has (AA's work item template). */
export type ItemModel = { id: string; name: string; description?: string | null; queues?: number; fields: { name: string; type: "text" | "number" | "boolean" | "date"; required?: boolean; display?: boolean }[] };
/** A run of a workflow: its stage, data and the timeline of what happened. */
export type WorkflowRun = { id: string; path: string; version: number; status: string; stage?: string | null; reference?: string | null; started_by?: string; created: string;
  updated?: string; finished?: string | null; error?: { code?: string; message?: string } | null; data?: Record<string, unknown>;
  history?: { event: string; stage?: string; ts: string; detail?: Record<string, unknown> | null }[];
  tasks?: { id: string; stage?: string; kind: string; title: string; assignee?: string; status: string; claimed_by?: string | null; outcome?: string | null; created?: string; completed?: string | null }[] };
export type GlobalValue = { name: string; value: unknown; changeable: boolean; my_value?: unknown; updated?: string; updated_by?: string };
export type SavedConnection = { name: string; package: string; description?: string | null; values?: Record<string, unknown>; credentials?: Record<string, string>; updated?: string; updated_by?: string };
/** A toolkit that takes a connection, and the inputs one holds (credential: a vault reference). */
export type ConnectionKind = { package: string; name: string; inputs: { name: string; description?: string; credential?: boolean }[] };
export type Toolkit = { id: string; name: string; version?: string; versions?: string[]; description?: string | null; publisher?: string | null; actions: number; conditions?: number;
  enabled: boolean; used_by: number; custom?: boolean };
export type ModelConnection = { name: string; provider: string; model?: string | null; model_type?: string | null; base_url?: string | null; key_ref?: string | null;
  description?: string | null; allowed_roles?: string[]; monthly_tokens?: number | null; tokens_this_month?: number; calls_per_minute?: number | null; calls_per_run?: number | null;
  pii_analyzer_url?: string | null; content_check?: string | null; updated?: string; updated_by?: string };
export type PromptSkill = { name: string; description?: string | null; owner: string; shared: boolean; template?: boolean; version: number; updated?: string; updated_by?: string };
export type SkillDefinition = { prompt: string; system?: string; output: "text" | "json"; temperature: number; connection?: string; inputs: { name: string; description?: string }[] };
export type AiGovernance = { totals?: { calls?: number; input_tokens?: number; output_tokens?: number; masked_calls?: number; tool_calls?: number; avg_latency_ms?: number | null };
  by_model?: { provider: string; model?: string; calls: number; input_tokens: number; output_tokens: number }[]; by_agent?: { bot_path: string; calls: number; tokens: number }[];
  recent?: { ts: string; bot_path: string; requested_by?: string; purpose?: string; provider?: string; model?: string; input_tokens?: number; output_tokens?: number; masked?: string; tool_calls?: string; prompt_sha256?: string; run_id?: string }[] };
export type AgentApi = { id: string; name: string; bot_path: string; device_id?: string | null; pool_id?: string | null; serverless?: boolean; timeout_seconds: number; enabled: boolean;
  created_by?: string; created?: string; last_used?: string | null; calls: number; url: string };
export type TestRunSummary = { id: string; started: string; finished?: string | null; total: number; passed: number; status: string; version?: number };
export type TestSet = { id: string; name: string; description?: string | null; bot_path: string; cases: { name: string; inputs?: Record<string, unknown>; expect?: Record<string, unknown> }[]; last_run?: TestRunSummary | null };
export type RepoAgent = { path: string; bot_id?: string; name?: string; description?: string | null; latest_version: number; production_version?: number | null; locked_by?: string | null;
  updated?: string; updated_by?: string; folder?: string; access?: string; labels?: { label: string; version: number }[]; deleted_at?: string; deleted_by?: string };
export type RepoFolder = { path: string; description?: string | null; agents: number; has_permissions?: boolean; access?: string };
export type RepoVersion = { version: number; comment?: string | null; author?: string; created?: string; labels: string[] };
export type ProductionRequest = { id: string; path: string; version: number; requested_by: string; requested?: string; comment?: string | null; status: string;
  can_decide?: boolean; decided_by?: string | null; decided?: string | null; note?: string | null };
export type FlowVariable = { name: string; type?: string; direction?: string; required?: boolean; description?: string; default?: unknown; enum?: unknown[] };
export type AgentTemplate = { id: string; source: string; name: string; description?: string; category: string; publisher: string; agents: string[] };
/** Runners that share work: a run sent to a pool goes to its least busy online runner. */
export type RunnerPool = { id: string; name: string; description?: string | null; devices: string[]; created_by?: string; created?: string;
  owners?: string[]; consumers?: string[]; can_manage?: boolean };

export class CrFailure extends Error {
  constructor(readonly status: number, readonly error: CrError) { super(error.message); }
  get code() { return this.error.code; }
}

export class CrApi {
  /** base: where the API is, "/api/" when the console is served by the Command Center. */
  constructor(readonly base = "/api/", private readonly fetcher: typeof fetch = (...a) => fetch(...a)) {}

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
  login(username: string, password: string) { return this.call<SignIn>("POST", "auth/login", { username, password }); }
  loginCode(mfaToken: string, code: string) { return this.call<SignIn>("POST", "auth/mfa", { mfa_token: mfaToken, code }); }
  async logout() { await this.call("POST", "auth/logout"); }
  /** Single sign-on offered on the sign-in page: OpenID Connect and SAML, each with its button text. */
  async ssoInfo() {
    const r = await this.call<{ enabled?: boolean; label?: string; saml?: { enabled?: boolean; label?: string } }>("GET", "auth/sso");
    return { oidc: r.enabled ? r.label || "Sign in with your organisation account" : null, saml: r.saml?.enabled ? r.saml.label || "Sign in with SAML" : null };
  }
  /** Where a single sign-on starts; the person comes back to `back`, a page of this Command Center. */
  ssoStart(kind: "oidc" | "saml", back: string) { return this.base + (kind === "oidc" ? "auth/sso/start" : "auth/saml/start") + "?return=" + encodeURIComponent(back); }
  async changePassword(current: string, next: string) { await this.call("POST", "auth/password", { current, new: next }); }
  /** Two-step sign-in: a new key for an authenticator app, then turning it on with a code (gives recovery codes once). */
  mfaSetup() { return this.call<{ secret: string; otpauth_uri: string }>("POST", "auth/mfa/setup"); }
  mfaEnable(code: string) { return this.call<{ recovery_codes: string[] }>("POST", "auth/mfa/enable", { code }); }
  /** Whether Agent Studio is offered on this computer (it lives beside the Command Center under ooa server). */
  async studio() { return !!(await this.call<{ studio?: string }>("GET", "health")).studio; }
  /** The optional modules installed, each filling a page of the menu. */
  async plugins() { return (await this.call<{ plugins?: ConsolePlugin[] }>("GET", "plugins")).plugins ?? []; }
  /** Runs: filter=active (queued, running) or history (finished), with status, bot, device, user, source, q, from, to, limit, offset. */
  runs(params: Record<string, string>) { return this.call<{ runs: Run[]; total: number }>("GET", "runs?" + new URLSearchParams(params).toString()); }
  /** One run with its events. */
  run(id: string) { return this.call<Run & { events: RunEvent[] }>("GET", "runs/" + encodeURIComponent(id)); }
  /** Pause, resume or stop a run (stop ends a queued run at once, a running one after its current step). */
  async control(id: string, action: "pause" | "resume" | "stop") { await this.call("POST", "runs/" + encodeURIComponent(id) + "/" + action); }
  async rerun(id: string) { await this.call("POST", "runs/" + encodeURIComponent(id) + "/rerun"); }
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
  async saveCredentialDetails(folder: string, name: string, b: { description?: string; owner?: string; user_attributes?: string[];
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
  mailTest(to: string) { return this.call<{ to: string }>("POST", "settings/mail-test", { to }); }
  gitStatus() { return this.call<{ configured: boolean; state?: string; at?: string; agents?: number; changed?: boolean; commit?: string; error?: string }>("GET", "git/status"); }
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
  bulkSchedules(ids: string[], action: "enable" | "disable" | "owner", owner?: string) { return this.call<{ changed: number }>("POST", "schedules/bulk", { ids, action, ...(owner ? { owner } : {}) }); }
  async calendars() { return (await this.call<{ calendars?: { name: string; dates: string[]; updated?: string; updated_by?: string }[] }>("GET", "calendars")).calendars ?? []; }
  async saveCalendar(name: string, dates: string[]) { await this.call("PUT", "calendars/" + encodeURIComponent(name), { dates }); }
  async deleteCalendar(name: string) { await this.call("DELETE", "calendars/" + encodeURIComponent(name)); }
  /** Agents with their live (production) version, for choosing what a schedule, trigger or queue runs. */
  async liveAgents() { return ((await this.call<{ bots?: { path: string; production_version?: number | null }[] }>("GET", "repo")).bots ?? []).filter((b) => b.production_version && b.path.endsWith(".flow.json")); }
  // ---- Triggers: events on a runner (files, windows, processes, services, hotkeys) or checked by the Command Center ----
  async triggers() { return (await this.call<{ triggers?: Trigger[] }>("GET", "triggers")).triggers ?? []; }
  /** A webhook trigger answers with its address and secret, shown once. */
  createTrigger(b: Record<string, unknown>) { return this.call<{ id: string; url?: string; secret?: string }>("POST", "triggers", b); }
  async updateTrigger(id: string, b: Record<string, unknown>) { await this.call("PUT", "triggers/" + encodeURIComponent(id), b); }
  async pauseTrigger(id: string, pause: boolean) { await this.call("POST", "triggers/" + encodeURIComponent(id) + (pause ? "/pause" : "/resume")); }
  /** Checks a Command Center trigger now instead of waiting for its interval. */
  pollTrigger(id: string) { return this.call<{ fired?: number; error?: string }>("POST", "triggers/" + encodeURIComponent(id) + "/poll"); }
  async deleteTrigger(id: string) { await this.call("DELETE", "triggers/" + encodeURIComponent(id)); }
  // ---- Work queues: queues, their items, item models (AA's work item templates), members ----
  async queues() { return (await this.call<{ queues?: WorkQueue[] }>("GET", "queues")).queues ?? []; }
  queue(id: string) { return this.call<WorkQueue & { members: { principal: string; member_role: string }[]; model?: ItemModel | null }>("GET", "queues/" + encodeURIComponent(id)); }
  saveQueue(id: string | null, b: Record<string, unknown>) { return this.call<{ id: string }>(id ? "PUT" : "POST", "queues" + (id ? "/" + encodeURIComponent(id) : ""), b); }
  async deleteQueue(id: string) { await this.call("DELETE", "queues/" + encodeURIComponent(id)); }
  async setProcessing(id: string, on: boolean) { await this.call("POST", "queues/" + encodeURIComponent(id) + (on ? "/start" : "/stop")); }
  async setQueueMembers(id: string, members: { principal: string; role: string }[]) { await this.call("PUT", "queues/" + encodeURIComponent(id) + "/members", { members }); }
  queueItems(id: string, status: string, q: string, limit = 200) { return this.call<{ items: WorkItem[]; total: number }>("GET", "queues/" + encodeURIComponent(id) + "/items?" + new URLSearchParams({ status, q, limit: String(limit) })); }
  queueItem(id: string, itemId: string) { return this.call<WorkItem>("GET", "queues/" + encodeURIComponent(id) + "/items/" + encodeURIComponent(itemId)); }
  async updateQueueItem(id: string, itemId: string, b: { data: unknown; reference: string | null; priority: number }) { await this.call("PUT", "queues/" + encodeURIComponent(id) + "/items/" + encodeURIComponent(itemId), b); }
  /** retry, defer (until a time, or until someone presses Retry) or delete several items. */
  async itemsAction(id: string, action: "retry" | "defer" | "delete", ids: string[], until?: string) { await this.call("POST", "queues/" + encodeURIComponent(id) + "/items/" + action, { ids, ...(until ? { until } : {}) }); }
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
  async workflowRuns(status?: string) { return (await this.call<{ runs?: WorkflowRun[] }>("GET", "workflows/runs" + (status ? "?status=" + encodeURIComponent(status) : ""))).runs ?? []; }
  workflowRun(id: string) { return this.call<WorkflowRun>("GET", "workflows/runs/" + encodeURIComponent(id)); }
  workflowForm(path: string) { return this.call<{ form?: { fields?: TaskFormField[] } | null }>("GET", "workflows/form?path=" + encodeURIComponent(path)); }
  startWorkflow(path: string, data: Record<string, unknown>, reference: string | null) { return this.call<{ id: string; reference?: string }>("POST", "workflows/start", { path, data, reference }); }
  async cancelWorkflow(id: string) { await this.call("POST", "workflows/runs/" + encodeURIComponent(id) + "/cancel"); }
  // ---- Shared Global values ($global.name), and each person's own value where people may change them ----
  async globals() { return (await this.call<{ globals?: GlobalValue[] }>("GET", "globals")).globals ?? []; }
  async saveGlobal(name: string, value: unknown, changeable: boolean) { await this.call("PUT", "globals/" + encodeURIComponent(name), { value, changeable }); }
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
  // ---- Prompt Skills (reviewed prompt templates, versioned) and AI governance (every AI call) ----
  async skills() { return (await this.call<{ skills?: PromptSkill[] }>("GET", "skills")).skills ?? []; }
  skill(name: string) { return this.call<PromptSkill & { definition: SkillDefinition; versions?: { version: number; updated_by?: string; updated?: string; comment?: string | null }[] }>("GET", "skills/" + encodeURIComponent(name)); }
  saveSkill(name: string, b: { description: string; definition: SkillDefinition; shared: boolean; template: boolean; comment: string | null }) { return this.call<{ name: string; version: number }>("PUT", "skills/" + encodeURIComponent(name), b); }
  async deleteSkill(name: string) { await this.call("DELETE", "skills/" + encodeURIComponent(name)); }
  async skillTemplates() { return (await this.call<{ templates?: { name: string; description?: string }[] }>("GET", "skills/templates")).templates ?? []; }
  skillFromTemplate(template: string, name: string) { return this.call<{ name: string }>("POST", "skills/from-template", { template, name }); }
  governance(days: number) { return this.call<AiGovernance>("GET", "governance?days=" + days); }
  // ---- Agent APIs: an agent behind its own address and token ----
  async agentApis() { return (await this.call<{ tasks?: AgentApi[] }>("GET", "apitasks")).tasks ?? []; }
  createAgentApi(b: Record<string, unknown>) { return this.call<{ id: string; name?: string; url: string; token: string }>("POST", "apitasks", b); }
  newAgentApiToken(id: string) { return this.call<{ token: string }>("POST", "apitasks/" + encodeURIComponent(id) + "/token"); }
  async setAgentApiEnabled(id: string, enabled: boolean) { await this.call("PATCH", "apitasks/" + encodeURIComponent(id), { enabled }); }
  async deleteAgentApi(id: string) { await this.call("DELETE", "apitasks/" + encodeURIComponent(id)); }
  // ---- Agent tests: test sets of cases, runs scored per case ----
  async testSets() { return (await this.call<{ sets?: TestSet[] }>("GET", "test-sets")).sets ?? []; }
  saveTestSet(id: string | null, b: { name: string; description: string; bot_path: string; cases: unknown[] }) { return this.call<{ id: string }>(id ? "PUT" : "POST", "test-sets" + (id ? "/" + encodeURIComponent(id) : ""), b); }
  async deleteTestSet(id: string) { await this.call("DELETE", "test-sets/" + encodeURIComponent(id)); }
  runTestSet(id: string, where: { device_id?: string; pool_id?: string }) { return this.call<{ test_run_id: string; started: number; cases: number }>("POST", "test-sets/" + encodeURIComponent(id) + "/run", where); }
  async testHistory(id: string) { return (await this.call<{ runs?: TestRunSummary[] }>("GET", "test-sets/" + encodeURIComponent(id) + "/history")).runs ?? []; }
  testRun(id: string) { return this.call<TestRunSummary & { bot_path: string; version: number; started_by?: string; results: { case_name: string; status: string; passed: boolean; detail?: string; run_id?: string }[] }>("GET", "test-sets/runs/" + encodeURIComponent(id)); }
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
  templates() { return this.call<{ templates: AgentTemplate[]; categories: string[]; can_share?: boolean; problem?: string | null }>("GET", "templates"); }
  installTemplate(source: string, id: string, folder: string) {
    return this.call<{ ok: boolean; folder: string; results: { path: string; status: string; error?: { message?: string } }[] }>("POST", "templates/install", { source, id, folder });
  }
  async shareTemplate(b: { path: string; name: string; description: string; category: string }) { await this.call("POST", "templates/share", b); }
  uploadCatalogue(text: string) { return this.call<{ templates: number }>("POST", "templates/catalogue", { text }); }
  async pools() { return (await this.call<{ pools?: RunnerPool[] }>("GET", "pools")).pools ?? []; }
  savePool(id: string | null, b: { name: string; description: string; devices: string[]; owners?: string[]; consumers?: string[] }) {
    return this.call<{ id: string }>(id ? "PUT" : "POST", "pools" + (id ? "/" + encodeURIComponent(id) : ""), b);
  }
  async deletePool(id: string) { await this.call("DELETE", "pools/" + encodeURIComponent(id)); }
  /** Home's figures; the runs-per-day chart counts days in the given time zone. */
  dashboard(timeZone?: string) { return this.call<Dashboard>("GET", "dashboard" + (timeZone ? "?tz=" + encodeURIComponent(timeZone) : "")); }
}
