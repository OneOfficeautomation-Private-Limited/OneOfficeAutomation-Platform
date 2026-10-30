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
/** A machine running the One Runner (AA: a device), as GET devices gives it. */
export type Runner = { id: string; name: string; hostname?: string; os?: string; agent_version?: string; enabled: boolean;
  status: "online" | "offline" | "disabled"; last_seen?: string | null; registered?: string; registered_by?: string; log_level?: string;
  device_type?: string; session_state?: string | null; after_run?: string; lifespan?: string; up_to_date?: boolean; update_requested?: boolean | string | null;
  version_status?: string; default_users?: string[]; running?: number; toolkits?: string | null };
/** Runners that share work: a run sent to a pool goes to its least busy online runner. */
export type RunnerPool = { id: string; name: string; description?: string | null; devices: string[]; created_by?: string; created?: string };

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
  async updateDevices(ids: string[], changes: { log_level?: string; device_type?: string; after_run?: string }) {
    return this.call<{ updated: number }>("PATCH", "devices", { ids, ...changes });
  }
  /** Rename, or take out of service (enabled false: gets no work, cannot sign in) and back. */
  async patchDevice(id: string, b: { name?: string; enabled?: boolean }) { await this.call("PATCH", "devices/" + encodeURIComponent(id), b); }
  async deleteDevice(id: string) { await this.call("DELETE", "devices/" + encodeURIComponent(id)); }
  /** Ask runners to update their program when idle; and whether an update is available. */
  updateRunnerProgram(ids: string[]) { return this.call<{ version?: string }>("POST", "devices/update", { ids }); }
  runnerProgram() { return this.call<{ available: boolean; version?: string; auto?: boolean }>("GET", "devices/update"); }
  /** A one-time key to register a runner (shown once). */
  registrationKey(temporary: boolean) { return this.call<{ key: string; temporary?: boolean }>("POST", "devices/registration-key", { hours: 24, temporary }); }
  async pools() { return (await this.call<{ pools?: RunnerPool[] }>("GET", "pools")).pools ?? []; }
  savePool(id: string | null, b: { name: string; description: string; devices: string[] }) {
    return this.call<{ id: string }>(id ? "PUT" : "POST", "pools" + (id ? "/" + encodeURIComponent(id) : ""), b);
  }
  async deletePool(id: string) { await this.call("DELETE", "pools/" + encodeURIComponent(id)); }
  /** Home's figures; the runs-per-day chart counts days in the given time zone. */
  dashboard(timeZone?: string) { return this.call<Dashboard>("GET", "dashboard" + (timeZone ? "?tz=" + encodeURIComponent(timeZone) : "")); }
}
