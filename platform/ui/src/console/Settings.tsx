import { Fragment, useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { CrApi, ScimToken } from "../api/cr";
import { Button, HelpButton, Select, TextArea, TextInput, type HelpTopic } from "../components";
import { SETTING_HELP } from "./settingsHelp";
import { ROLE_NAMES } from "./roleNames";

/**
 * Administration > Settings, built as AA's Settings (checked against AA's docs, UiPath's tenant and organisation
 * settings and Blue Prism's System settings, 31 Oct 2026): sections down the side, as AA's and UiPath's tabs, each
 * saved on its own and only with the values changed; a search across every setting (by name, hint or key); and each
 * section's own actions (send a test e-mail or syslog message, sync Git now, back up now, remove old runs now).
 * Laid out as the design plan's step 6 (approved on the canvas 31 Oct 2026): a setting is one row (its name, a short
 * hint, the control with its unit), long sections are grouped, a changed row is marked with what it was, a save bar
 * shows only while something is changed, yes/no settings are switches, and changes in the search results save there.
 * Review round 2 (1 Nov 2026): Remove old runs now asks first, with the saved numbers it uses (not the ones typed but
 * unsaved), and has nothing to do while both are 0 (always).
 */
type Kind = "text" | "number" | "area" | "check" | "select" | "roles";
type Setting = { key: string; label: string; hint?: string; unit?: string; kind: Kind; min?: number; max?: number; placeholder?: string; options?: [string, string][] };
type Group = { title: string; settings: Setting[] };
type Section = { id: string; title: string; about: string; mark: string; colour: string; groups: Group[] };

const VAULT_REF = "A vault reference: folder/credential/attribute. The value stays in the vault.";
const SECTIONS: Section[] = [
  { id: "general", title: "General", mark: "G", colour: "#2f6fde", about: "What everyone sees, and the address of this One CommandCenter.", groups: [
    { title: "For everyone", settings: [
      { key: "announcement", label: "Announcement", hint: "Shown to everyone at the top of the page. Empty: none.", kind: "area", placeholder: "Maintenance on Saturday 10:00–12:00 UTC" },
      { key: "public_url", label: "Public address", hint: "Of this One CommandCenter, for sign-in redirects and links in e-mails.", kind: "text", placeholder: "https://ooa.example.com" }] },
    { title: "This environment", settings: [
      { key: "environment_label", label: "Environment name", hint: "Shown in the top bar and on the sign-in page. Empty: none.", kind: "text", placeholder: "Production" },
      { key: "environment_colour", label: "Environment colour", kind: "select", options: [["blue", "Blue"], ["green", "Green"], ["amber", "Amber"], ["red", "Red"], ["grey", "Grey"]] },
      { key: "default_time_zone", label: "Time zone for new schedules", hint: "Empty: each person's own.", kind: "text", placeholder: "Asia/Kolkata" }] },
    { title: "Limits", settings: [
      { key: "max_upload_mb", label: "Largest upload", hint: "1 to 1024.", unit: "MB", kind: "number", min: 1, max: 1024 },
      { key: "queue_poll_seconds", label: "Work queue check interval", hint: "3 to 3600.", unit: "seconds", kind: "number", min: 3, max: 3600 }] },
    { title: "Value of automation", settings: [
      { key: "default_minutes_saved", label: "Minutes saved per successful run", hint: "For agents without their own value.", unit: "minutes", kind: "number", min: 0, max: 100000 },
      { key: "hourly_cost", label: "Cost of an hour of the manual work", hint: "For money saved in Value.", kind: "number", min: 0, max: 100000 },
      { key: "currency", label: "Currency", hint: "A three-letter code: INR, USD, EUR.", kind: "text", placeholder: "INR" }] },
    { title: "Templates", settings: [
      { key: "template_catalog_url", label: "Template catalogue", hint: "An https address of templates.json. Empty: none.", kind: "text" }] },
  ] },
  { id: "security", title: "Sign-in and security", mark: "S", colour: "#be185d", about: "Sessions, passwords, two-step sign-in, and which addresses may connect. This computer is always allowed.", groups: [
    { title: "Sessions", settings: [
      { key: "session_hours", label: "Sign-in session length", hint: "How long a sign-in lasts. 1 to 720.", unit: "hours", kind: "number", min: 1, max: 720 },
      { key: "max_sessions_per_user", label: "Sign-ins per person at once", hint: "1 to 50.", unit: "at once", kind: "number", min: 1, max: 50 },
      { key: "inactive_user_days", label: "Disable people inactive for", hint: "0 = never.", unit: "days", kind: "number", min: 0, max: 3650 },
      { key: "idle_minutes", label: "Sign out after no activity for", hint: "In this console. 0 = never.", unit: "minutes", kind: "number", min: 0, max: 1440 }] },
    { title: "Passwords", settings: [
      { key: "password_min_length", label: "Shortest password", hint: "12 to 64.", unit: "characters", kind: "number", min: 12, max: 64 },
      { key: "password_history", label: "Passwords that cannot be used again", hint: "The last N a person used. 0 = no rule.", unit: "last passwords", kind: "number", min: 0, max: 24 },
      { key: "password_max_days", label: "Change passwords every", hint: "0 = never.", unit: "days", kind: "number", min: 0, max: 3650 },
      { key: "password_expiry_warn_days", label: "Warn before a password must change", hint: "0 = no warning.", unit: "days", kind: "number", min: 0, max: 60 },
      { key: "password_min_days", label: "Keep a new password at least", hint: "Before it can be changed again. 0 = no rule.", unit: "days", kind: "number", min: 0, max: 365 },
      { key: "password_needs_mixed_case", label: "Upper- and lower-case letters", hint: "Letters and digits are always needed.", kind: "check" },
      { key: "password_needs_symbol", label: "A symbol, such as ! or #", kind: "check" }] },
    { title: "Lockout", settings: [
      { key: "lockout_attempts", label: "Lock an account after", hint: "Wrong passwords or codes in a row. 0 = never lock.", unit: "attempts", kind: "number", min: 0, max: 20 },
      { key: "lockout_minutes", label: "Keep it locked for", hint: "1 to 1440. An administrator can unlock it sooner in Users.", unit: "minutes", kind: "number", min: 1, max: 1440 }] },
    { title: "Sign-in page", settings: [
      { key: "signin_notice", label: "Notice on the sign-in page", hint: "For example terms of use. Empty: none.", kind: "area", placeholder: "This system is for authorised use only." },
      { key: "signin_notice_accept", label: "People accept the notice to sign in", hint: "They tick it before any sign-in button works.", kind: "check" }] },
    { title: "API keys", settings: [
      { key: "api_key_max_days", label: "Longest an API key may last", hint: "0 = up to 730 days, or no end.", unit: "days", kind: "number", min: 0, max: 730 }] },
    { title: "Two-step sign-in", settings: [
      { key: "mfa_required", label: "Two-step sign-in", hint: "A code from an authenticator app as well as the password.", kind: "select",
        options: [["0", "Optional (each person decides)"], ["1", "Required for administrators"], ["2", "Required for everyone with a password"]] }] },
    { title: "Network", settings: [
      { key: "ip_allow_list", label: "IP allow list", hint: "Addresses or CIDR ranges, separated by commas. Empty: every address.", kind: "area", placeholder: "10.0.0.0/8, 203.0.113.7" },
      { key: "trusted_proxies", label: "Trusted proxies", hint: "Load balancers whose X-Forwarded-For names the client.", kind: "area", placeholder: "10.0.5.10, 10.0.6.0/24" }] },
    { title: "Built-in roles", settings: [
      { key: "switched_off_roles", label: "Built-in roles in use", hint: "Switched off, a role gives no permissions and cannot be given; people keep their other roles.", kind: "roles" }] },
  ] },
  { id: "sso", title: "Single sign-on", mark: "SO", colour: "#1d4ed8", about: "OpenID Connect and SAML 2.0 (Entra ID, Okta, Google, Keycloak, ADFS), and Active Directory or LDAP. Local accounts always sign in with their password too.", groups: [
    { title: "OpenID Connect", settings: [
      { key: "sso_issuer", label: "Issuer URL", kind: "text", placeholder: "https://login.microsoftonline.com/<tenant>/v2.0" },
      { key: "sso_client_id", label: "Client id", kind: "text" },
      { key: "sso_client_secret_ref", label: "Client secret", hint: VAULT_REF, kind: "text", placeholder: "sso/oidc/client_secret" },
      { key: "sso_label", label: "OpenID Connect button text", kind: "text", placeholder: "Sign in with your organisation account" }] },
    { title: "SAML 2.0", settings: [
      { key: "saml_idp_sso_url", label: "Identity provider sign-in address", kind: "text", placeholder: "https://login.microsoftonline.com/<tenant>/saml2" },
      { key: "saml_idp_entity_id", label: "Identity provider entity id", hint: "Its issuer.", kind: "text" },
      { key: "saml_sp_entity_id", label: "This One CommandCenter's entity id", hint: "Empty: the metadata address.", kind: "text" },
      { key: "saml_label", label: "SAML button text", kind: "text", placeholder: "Sign in with SAML" },
      { key: "saml_idp_certificate", label: "Identity provider signing certificate", hint: "PEM.", kind: "area", placeholder: "-----BEGIN CERTIFICATE-----" }] },
    { title: "Active Directory or LDAP", settings: [
      { key: "ldap_urls", label: "Directory addresses", hint: "ldaps://, more than one for failover. Empty: off.", kind: "text", placeholder: "ldaps://dc1.corp.example.com:636 ldaps://dc2.corp.example.com:636" },
      { key: "ldap_base_dn", label: "Where people are found", hint: "The base of the search.", kind: "text", placeholder: "DC=corp,DC=example,DC=com" },
      { key: "ldap_bind_dn", label: "Service account", hint: "Finds people; reads only.", kind: "text", placeholder: "svc-ooa@corp.example.com" },
      { key: "ldap_bind_password_ref", label: "Service account password", hint: VAULT_REF, kind: "text", placeholder: "directory/svc-ooa/password" },
      { key: "ldap_user_filter", label: "Search filter", hint: "{0} is the user name typed.", kind: "text", placeholder: "(|(sAMAccountName={0})(userPrincipalName={0}))" },
      { key: "ldap_name_attribute", label: "User name from", hint: "The attribute an account is named after.", kind: "text", placeholder: "sAMAccountName" },
      { key: "ldap_nested_groups", label: "Groups inside groups count", hint: "Active Directory only.", kind: "check" }] },
    { title: "New people and groups", settings: [
      { key: "sso_default_role", label: "Role for new people", hint: "Single sign-on only. Empty: only existing accounts sign in this way.", kind: "text", placeholder: "viewer" },
      { key: "sso_group_roles", label: "Groups to roles", hint: "Single sign-on, the directory and SCIM. One line per group: group = role. The first match wins, at every sign-in.", kind: "area", placeholder: "OOA Admins = admin\nFinance Automation = bot_runner" }] },
  ] },
  { id: "runners", title: "Runner Machines", mark: "M", colour: "#0e7490", about: "Updates, temporary runners, secrets, limits and screen pictures for every runner (a runner can have its own limits).", groups: [
    { title: "Updates and secrets", settings: [
      { key: "runner_auto_update", label: "Update runners automatically", hint: "To this One CommandCenter's version, when they are idle.", kind: "check" },
      { key: "runner_secret_days", label: "Give runners a new secret every", hint: "0 = never.", unit: "days", kind: "number", min: 0, max: 365 },
      { key: "temporary_runner_hours", label: "Remove temporary runners after", hint: "Hours without contact. 1 to 720.", unit: "hours", kind: "number", min: 1, max: 720 }] },
    { title: "Limits", settings: [
      { key: "runner_cpu_max", label: "Take no new work above this CPU use", hint: "0 = no limit.", unit: "%", kind: "number", min: 0, max: 100 },
      { key: "runner_mem_max", label: "Take no new work above this memory use", hint: "0 = no limit.", unit: "%", kind: "number", min: 0, max: 100 }] },
    { title: "Registration and connections", settings: [
      { key: "runner_registration_open", label: "New runners may register", hint: "Off: registration keys stop working until it is on again.", kind: "check" },
      { key: "runner_require_https", label: "Runners connect only over HTTPS", hint: "This computer's own runner always may.", kind: "check" }] },
    { title: "Runs, triggers and work queues", settings: [
      { key: "runs_refuse_offline_runner", label: "Refuse runs for a runner that is offline", hint: "Off: the run waits until the runner is back.", kind: "check" },
      { key: "trigger_max_failures", label: "Switch a trigger off after", hint: "Failed runs in a row. 0 = never.", unit: "failed runs", kind: "number", min: 0, max: 100 },
      { key: "queue_abandon_hours", label: "Mark work items abandoned after", hint: "In progress this long without an answer. 0 = never.", unit: "hours", kind: "number", min: 0, max: 720 }] },
    { title: "Screens", settings: [
      { key: "runner_screen_pictures", label: "Pictures of a runner's screen", hint: "Administrators may ask for one; each is recorded in the Audit Trail.", kind: "check" },
      { key: "runner_error_screenshots", label: "Screenshots when a step fails", hint: "Off: Catch blocks that ask for one get none.", kind: "check" }] },
  ] },
  { id: "agents", title: "Agents and repository", mark: "A", colour: "#2f6fde", about: "Rules for what goes into the agent repository and into production, and its copy in Git.", groups: [
    { title: "Rules", settings: [
      { key: "production_approval", label: "Approval before production", hint: "A second person approves every version before it goes live.", kind: "check" },
      { key: "block_executable_uploads", label: "Block programs and scripts in uploads", hint: ".exe, .dll, .ps1 and similar.", kind: "check" },
      { key: "agents_write_credentials", label: "Agents may save credentials", hint: "For example a new password after a reset; the run's user needs permission to change the credential.", kind: "check" },
      { key: "promotion_targets", label: "Promote agents only to", hint: "One CommandCenter addresses, separated by commas. Empty: any.", kind: "text", placeholder: "https://cc-prod.example.com" }] },
    { title: "Git copy", settings: [
      { key: "git_remote_url", label: "Git remote", hint: "https://…git", kind: "text", placeholder: "https://github.com/acme/agents.git" },
      { key: "git_branch", label: "Git branch", kind: "text", placeholder: "main" },
      { key: "git_username", label: "Git user name", hint: "Any, when you use a token.", kind: "text" },
      { key: "git_token_ref", label: "Git access token", hint: VAULT_REF, kind: "text", placeholder: "git/agents-repo/token" },
      { key: "git_auto_push", label: "Push to Git after every change", hint: "After every check-in, production change and deletion.", kind: "check" }] },
    { title: "Files", settings: [
      { key: "repo_files_folder", label: "Shared folder for repository files", hint: "For several nodes. Empty: the data folder.", kind: "text" }] },
  ] },
  { id: "mcp", title: "AI assistants (MCP)", mark: "AI", colour: "#6d28d9", about: "Assistants connect to /api/mcp with a person's API key, or an OAuth token from your identity provider, and run the agents listed here as tools.", groups: [
    { title: "Tools", settings: [
      { key: "mcp_agents", label: "Agents offered as tools", hint: "Paths, separated by commas; * for all.", kind: "text", placeholder: "Finance/Invoice lookup.flow.json" },
      { key: "mcp_runner", label: "Runner or runner pool id", hint: "Empty: any online runner.", kind: "text" },
      { key: "mcp_timeout_seconds", label: "Wait for a result", hint: "10 to 3600.", unit: "seconds", kind: "number", min: 10, max: 3600 }] },
    { title: "OAuth", settings: [
      { key: "mcp_oauth_audience", label: "Token audience", hint: "Empty: API keys only.", kind: "text", placeholder: "api://ooa-mcp" },
      { key: "mcp_oauth_scope", label: "Scope the token must carry", hint: "Optional.", kind: "text", placeholder: "mcp.use" }] },
  ] },
  { id: "connections", title: "E-mail, proxy and SIEM", mark: "E", colour: "#b45309", about: "One CommandCenter's own e-mail, its proxy to the internet, and audit forwarding to your SIEM.", groups: [
    { title: "E-mail", settings: [
      { key: "mail_smtp_host", label: "Outgoing mail server", kind: "text", placeholder: "smtp.office365.com" },
      { key: "mail_smtp_port", label: "Port", hint: "1 to 65535.", kind: "number", min: 1, max: 65535 },
      { key: "mail_smtp_security", label: "Security", kind: "select", options: [["starttls", "STARTTLS (port 587)"], ["ssl", "SSL/TLS (port 465)"], ["none", "None (trusted network only)"]] },
      { key: "mail_smtp_user", label: "Mail account", hint: "Empty: no sign-in.", kind: "text" },
      { key: "mail_smtp_password_ref", label: "Mail account password", hint: VAULT_REF, kind: "text", placeholder: "mail/smtp/password" },
      { key: "mail_from", label: "Sender address", kind: "text" },
      { key: "license_mail_to", label: "Also send licence reminders to", hint: "Addresses, separated by commas.", kind: "text" }] },
    { title: "Notifications", settings: [
      { key: "notify_run_failed", label: "E-mail people when a run they started fails", hint: "To the address on their account.", kind: "check" },
      { key: "notify_task_assigned", label: "E-mail people when a workflow gives them a task", hint: "Forms and approvals; not tasks for everyone.", kind: "check" }] },
    { title: "Proxy", settings: [
      { key: "outbound_proxy", label: "Proxy for outgoing connections", hint: "http://host:port. Empty: direct.", kind: "text", placeholder: "http://proxy.example.com:8080" },
      { key: "outbound_proxy_bypass", label: "Reach these directly", hint: "Host names; * allowed.", kind: "text", placeholder: "*.example.com, 10.*" }] },
    { title: "SIEM", settings: [
      { key: "syslog_target", label: "Syslog target", hint: "host:port, for every audit entry. Empty: off.", kind: "text", placeholder: "siem.example.com:514" },
      { key: "syslog_protocol", label: "Syslog protocol", kind: "select", options: [["udp", "UDP"], ["tcp", "TCP"]] },
      { key: "siem_https_url", label: "HTTPS collector", hint: "Every audit entry as JSON. Empty: off.", kind: "text", placeholder: "https://splunk.example.com:8088/services/collector/event" },
      { key: "siem_https_token_ref", label: "Collector token", hint: VAULT_REF, kind: "text", placeholder: "siem/collector/token" },
      { key: "siem_https_auth", label: "Collector sign-in", kind: "select", options: [["bearer", "Bearer token"], ["splunk", "Splunk HTTP Event Collector"]] }] },
    { title: "Event subscriptions", settings: [
      { key: "events_allow_http", label: "Allow plain http:// receivers", hint: "Test systems only; events then travel unencrypted.", kind: "check" },
      { key: "events_allow_private", label: "Allow receivers in private networks", hint: "10.x, 192.168.x, this computer and similar.", kind: "check" }] },
  ] },
  { id: "data", title: "Data retention and backups", mark: "D", colour: "#0f766e", about: "How long history is kept (removed once an hour; queued and running runs never), and the backups of everything.", groups: [
    { title: "Keep history", settings: [
      { key: "run_log_retention_days", label: "Keep step logs for", hint: "0 = always.", unit: "days", kind: "number", min: 0, max: 36500 },
      { key: "run_retention_days", label: "Keep runs for", hint: "0 = always.", unit: "days", kind: "number", min: 0, max: 36500 },
      { key: "audit_retention_days", label: "Keep audit entries for", hint: "At least 90; 0 = always. Older ones are archived first.", unit: "days", kind: "number", min: 0, max: 36500 },
      { key: "ai_call_retention_days", label: "Keep AI call records for", hint: "0 = always.", unit: "days", kind: "number", min: 0, max: 36500 },
      { key: "ai_enabled", label: "AI in agents", hint: "Off: every AI step fails with AI.DISABLED.", kind: "check" },
      { key: "ai_log_content", label: "Keep AI prompts and answers", hint: "Off: only the model, tokens and time are kept. On: also the prompt and answer, masked.", kind: "check" },
      { key: "work_item_retention_days", label: "Keep finished work-queue items for", hint: "0 = always.", unit: "days", kind: "number", min: 0, max: 36500 }] },
    { title: "Backups", settings: [
      { key: "backup_hours", label: "Back up every", hint: "0 = never.", unit: "hours", kind: "number", min: 0, max: 168 },
      { key: "backup_keep_days", label: "Keep backups for", hint: "1 to 3650.", unit: "days", kind: "number", min: 1, max: 3650 },
      { key: "backup_folder", label: "Backup folder", hint: "Empty: the data folder's backups folder.", kind: "text" }] },
  ] },
  { id: "about", title: "This One CommandCenter", mark: "i", colour: "#475569", about: "The database and the nodes serving this One CommandCenter (read-only here; the database is changed with the ooa db command).", groups: [] },
];
const ROLES = Object.entries(ROLE_NAMES).filter(([r]) => r !== "admin");   // the administrator role cannot be switched off
const settingsOf = (s: Section) => s.groups.flatMap((g) => g.settings);
/** The Help panel's text for settings: how to use each one (settingsHelp.ts), or its hint. */
const helpOf = (title: string, list: Setting[], about?: string): HelpTopic => ({ title, about, items: list.map((x) => ({ name: x.label, text: SETTING_HELP[x.key] ?? x.hint ?? "" })) });
const PAGE_HELP: HelpTopic = { title: "Settings", about: "Settings for everyone, by section. Change a value, then save its section with the bar that appears; each section saves on its own and every change is recorded in the Audit Trail. Find a setting searches every section.",
  items: SECTIONS.map((s) => ({ name: s.title, text: s.about })) };
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const str = (v: unknown) => v === null || v === undefined ? "" : String(v);
/** A value as people read it: a choice's words, On / Off, the roles in use, or "empty". */
function shown(x: Setting, v: string) {
  if (x.kind === "check") return v === "1" ? "On" : "Off";
  if (x.kind === "select") return x.options!.find(([k]) => k === (v || x.options![0][0]))?.[1] ?? v;
  if (x.kind === "roles") { const off = v.split(",").filter(Boolean); return off.length ? off.map((r) => ROLES.find(([k]) => k === r)?.[1] ?? r).join(", ") + " off" : "all on"; }
  return v === "" ? "empty" : v.length > 28 ? v.slice(0, 26) + "…" : v;
}
/** The search words marked in a name. */
function marked(text: string, q: string): ReactNode {
  const at = q ? text.toLowerCase().indexOf(q) : -1;
  return at < 0 ? text : <>{text.slice(0, at)}<mark>{text.slice(at, at + q.length)}</mark>{text.slice(at + q.length)}</>;
}

export function Settings({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [saved, setSaved] = useState<Record<string, string> | null>(null);
  const [form, setForm] = useState<Record<string, string>>({});
  const [section, setSection] = useState("general");
  const [q, setQ] = useState("");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try { const s = await api.settings(); const m = Object.fromEntries(Object.entries(s).map(([k, v]) => [k, str(v)])); setSaved(m); setForm(m); setError(null); }
    catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);

  const text = q.trim().toLowerCase();
  const found = useMemo(() => text ? SECTIONS.map((s) => ({ s, groups: s.groups.map((g) => ({ ...g, settings: g.settings.filter((x) => (x.label + " " + (x.hint ?? "") + " " + x.key).toLowerCase().includes(text)) }))
    .filter((g) => g.settings.length) })).filter((r) => r.groups.length) : [], [text]);
  const changedIn = (s: Section) => saved ? settingsOf(s).filter((x) => form[x.key] !== saved[x.key]) : [];
  const save = async (s: Section) => {
    const changes: Record<string, string | number> = {};
    for (const x of changedIn(s)) changes[x.key] = x.kind === "number" || x.kind === "check" || (x.kind === "select" && x.key === "mfa_required") ? Number(form[x.key] || 0) : form[x.key].trim();
    try { await api.saveSettings(changes); onNotice("Saved " + Object.keys(changes).length + " setting" + (Object.keys(changes).length === 1 ? "" : "s") + " in " + s.title + "."); await load(); }
    catch (e) { setError(message(e)); }
  };
  const undo = (s: Section) => saved && setForm({ ...form, ...Object.fromEntries(settingsOf(s).map((x) => [x.key, saved[x.key]])) });

  /** One setting as a row: its name and hint on the left, the control with its unit on the right, and what it was when changed. */
  const row = (x: Setting) => {
    const v = form[x.key] ?? "";
    const set = (nv: string) => setForm({ ...form, [x.key]: nv });
    const changed = !!saved && v !== saved[x.key];
    const id = "cc-set-" + x.key, hintId = x.hint ? id + "-hint" : undefined;
    let control: ReactNode;
    if (x.kind === "roles") {
      const off = v.split(",").map((r) => r.trim()).filter(Boolean);
      control = <span className="cc-set-switches" role="group" aria-label={x.label}>{ROLES.map(([r, l]) => <label key={r} className="cc-switch">
        <input type="checkbox" role="switch" aria-label={l + " in use"} checked={!off.includes(r)} onChange={(e) => set((e.target.checked ? off.filter((o) => o !== r) : [...off, r]).join(","))} />
        <span className="cc-switch-track" aria-hidden="true" />{l}</label>)}</span>;
    } else if (x.kind === "check") {
      control = <label className="cc-switch"><input id={id} type="checkbox" role="switch" aria-describedby={hintId} checked={v === "1"} onChange={(e) => set(e.target.checked ? "1" : "0")} />
        <span className="cc-switch-track" aria-hidden="true" />{v === "1" ? "On" : "Off"}</label>;
    } else if (x.kind === "area") {
      control = <TextArea id={id} aria-describedby={hintId} rows={x.key === "saml_idp_certificate" || x.key === "sso_group_roles" ? 4 : 2} value={v} placeholder={x.placeholder} onChange={(e) => set(e.target.value)} />;
    } else if (x.kind === "select") {
      control = <Select id={id} aria-describedby={hintId} value={v || x.options![0][0]} onChange={(e) => set(e.target.value)} options={x.options!.map(([value, label]) => ({ value, label }))} />;
    } else {
      control = <TextInput id={id} aria-describedby={hintId} className={x.kind === "number" ? "cc-set-num" : undefined} type={x.kind === "number" ? "number" : "text"}
        min={x.min} max={x.max} value={v} placeholder={x.placeholder} onChange={(e) => set(e.target.value)} />;
    }
    return (
      <div key={x.key} className={"cc-set-row" + (changed ? " is-changed" : "")}>
        <div className="cc-set-text">
          {x.kind === "roles" ? <span className="cc-set-name">{marked(x.label, text)}</span> : <label htmlFor={id} className="cc-set-name">{marked(x.label, text)}</label>}
          {x.hint && <div id={hintId} className="cc-set-hint">{x.hint}</div>}
        </div>
        <div className="cc-set-ctl">{control}{x.unit && <span className="cc-set-unit">{x.unit}</span>}{changed && <span className="cc-set-was">was {shown(x, saved![x.key] ?? "")}</span>}</div>
      </div>
    );
  };
  /** The bar that shows while a section has changes: how many, Undo changes, Save. */
  const saveBar = (s: Section) => {
    const n = changedIn(s).length;
    return n > 0 && <div className="cc-set-save" role="group" aria-label={"Changes in " + s.title}>
      <span><strong>{n} change{n === 1 ? "" : "s"}</strong> in {s.title}</span><span className="ov-fill" />
      <Button kind="plain" onClick={() => undo(s)}>Undo changes</Button>
      <Button kind="primary" onClick={() => save(s)}>Save {s.title}</Button>
    </div>;
  };
  const tile = (s: Section) => <span className="cc-set-tile" style={{ background: s.colour }} aria-hidden="true">{s.mark}</span>;
  const current = SECTIONS.find((s) => s.id === section)!;

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-settings-title">
      <header className="cc-list-head">
        <h1 id="cc-settings-title">Settings</h1>
        <HelpButton topic={PAGE_HELP} />
        <span className="cc-list-count">For everyone · each section saves on its own · every change goes to the Audit Trail</span>
        <span className="ov-fill" />
        <span className="cc-list-search"><TextInput type="search" aria-label="Search settings" placeholder="Find a setting" value={q} onChange={(e) => setQ(e.target.value)} /></span>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!saved && !error && <p className="cc-none" role="status">Loading…</p>}
      {saved && <div className="cc-set">
        <nav className="cc-set-nav" aria-label="Settings sections">
          {SECTIONS.map((s) => <button key={s.id} type="button" className="cc-set-tab" aria-current={!text && s.id === section ? "page" : undefined} onClick={() => { setSection(s.id); setQ(""); }}>
            {tile(s)}<span className="cc-set-tab-name">{s.title}</span>{changedIn(s).length > 0 && <span className="cc-set-badge" title="Changed, not saved yet">{changedIn(s).length}</span>}</button>)}
        </nav>
        {text ? <div className="cc-set-form" role="region" aria-label="Settings found">
          <div className="cc-set-head"><h2>{found.reduce((n, r) => n + r.groups.reduce((m, g) => m + g.settings.length, 0), 0) || "No"} setting{found.reduce((n, r) => n + r.groups.reduce((m, g) => m + g.settings.length, 0), 0) === 1 ? "" : "s"} match “{q.trim()}”</h2>
            <p>{found.length ? "In " + found.length + " section" + (found.length === 1 ? "" : "s") + ". Change them here; each section saves on its own." : "Try another word: names, hints and setting keys are searched."}</p></div>
          {found.map(({ s, groups }) => <Fragment key={s.id}>
            <div className="cc-set-group is-section">{tile(s)}<span>{s.title}</span><span className="ov-fill" />
              <button type="button" className="cc-link" aria-label={"Open " + s.title} onClick={() => { setSection(s.id); setQ(""); }}>Open the section ›</button></div>
            {groups.flatMap((g) => g.settings).map(row)}
            {saveBar(s)}
          </Fragment>)}
          {found.length > 0 && <p className="cc-set-foot">Searches names, hints and setting keys (such as <code>password_min_length</code>).</p>}
        </div>
          : <div className="cc-set-form" role="region" aria-label={current.title}>
            <div className="cc-set-head"><h2>{current.title}{current.groups.length > 0 && <HelpButton topic={helpOf(current.title, settingsOf(current), current.about)} />}</h2><p>{current.about}</p>
              {current.id === "sso" && <p>Register these with your provider. OpenID Connect redirect: <code>{(saved.public_url || location.origin) + "/api/auth/sso/callback"}</code>.
                SAML metadata: <code>{(saved.public_url || location.origin) + "/api/auth/saml/metadata"}</code>.</p>}</div>
            {current.groups.map((g) => <Fragment key={g.title}>
              <div className="cc-set-group">{g.title}<HelpButton topic={helpOf(current.title + ": " + g.title, g.settings)} /></div>
              {g.settings.map(row)}
            </Fragment>)}
            <div className="cc-set-extras"><Extras api={api} section={current.id} form={form} saved={saved ?? {}} onNotice={onNotice} onError={setError} /></div>
            {saveBar(current)}
          </div>}
      </div>}
    </section>
  );
}

/** A section's own actions and facts: test e-mail and syslog, Git sync, backups, removing old runs, nodes and database. */
function Extras({ api, section, form, saved, onNotice, onError }: { api: CrApi; section: string; form: Record<string, string>; saved: Record<string, string>;
  onNotice: (t: string) => void; onError: (e: string) => void }) {
  const [info, setInfo] = useState<ReactNode>(null);
  const [to, setTo] = useState("");
  const [who, setWho] = useState("");   // the directory test: a user name to look up
  const [asking, setAsking] = useState(false);   // removing old runs, asked first
  // removing old runs uses the saved numbers; 0 keeps them always
  const runDays = Number(saved.run_retention_days || 0), logDays = Number(saved.run_log_retention_days || 0);
  const what = [runDays ? "runs older than " + runDays + " days" : "", logDays ? "step logs older than " + logDays + " days" : ""].filter(Boolean).join(" and ");
  const fail = (e: unknown) => onError(message(e));
  useEffect(() => {
    setInfo(null);
    if (section === "agents") api.gitStatus().then((g) => setInfo(<p className="st-muted">{!g.configured ? "Git: not set up." : g.state === "never" ? "Git: not synced yet."
      : g.state === "ok" ? "Git: last sync " + new Date(g.at!).toLocaleString() + ", " + g.agents + " agent(s)" + (g.changed ? ", commit " + String(g.commit).slice(0, 8) : ", nothing changed") + "."
        : "Git: last sync failed " + new Date(g.at!).toLocaleString() + ": " + g.error}</p>), () => {});
    if (section === "data") api.backups().then((b) => setInfo(<p className="st-muted">{b.backups.length ? b.backups.length + " backup(s) in " + b.folder + ", newest " + new Date(b.backups[0].created).toLocaleString() + " (" + b.backups[0].size_mb + " MB)."
      : "No backups in " + b.folder + " yet."}{b.state === "failed" ? " Last attempt failed: " + b.error : ""} {b.key_note}</p>), () => {});
    if (section === "about") Promise.all([api.database().catch(() => null), api.cluster().catch(() => null)]).then(([d, c]) => setInfo(<>
      {d && <dl className="cc-facts">
        <dt>Database</dt><dd>{str(d.engine)} {str(d.version)}{d.edition ? " (" + d.edition + ")" : ""}</dd>
        {d.server && <><dt>Server</dt><dd>{str(d.server)}</dd></>}
        <dt>Database name</dt><dd>{str(d.database) || "—"}</dd>
        {d.account && <><dt>Signed in as</dt><dd>{str(d.account)}</dd></>}
        <dt>Encrypted connection</dt><dd>{d.encrypted ? "Yes" : "No"}</dd>
        <dt>Schema</dt><dd>version {str(d.schema_version)} of {str(d.schema_latest)}{Number(d.schema_version) < Number(d.schema_latest) ? " (run ooa db migrate)" : ""}</dd>
        {d.size_mb !== null && d.size_mb !== undefined && <><dt>Size</dt><dd>{str(d.size_mb)} MB</dd></>}
      </dl>}
      {c && <p className="st-muted">{c.nodes.length} node(s): {c.nodes.map((n) => n.node_id + (n.leader ? " (leads background work)" : "") + (n.this_node ? " (this one)" : "")).join(", ")}.
        More nodes on the same database behind a load balancer give high availability.</p>}
    </>));
  }, [api, section]);
  return <>
    {info}
    {section === "connections" && <div className="cc-inline cc-test">
      <TextInput aria-label="Send a test e-mail to" placeholder="test e-mail to (your own address)" value={to} onChange={(e) => setTo(e.target.value)} />
      <Button kind="secondary" onClick={() => api.mailTest(to.trim()).then((r) => onNotice("Test e-mail sent to " + r.to + "."), fail)}>Send a test e-mail</Button>
      <Button kind="secondary" disabled={!form.syslog_target} onClick={() => api.syslogTest().then(() => onNotice("Test message sent to " + form.syslog_target + "."), fail)}>Send a test syslog message</Button>
      <Button kind="secondary" disabled={!form.siem_https_url} onClick={() => api.siemTest().then(() => onNotice("The HTTPS collector accepted a test entry."), fail)}>Send a test SIEM entry</Button>
    </div>}
    {section === "sso" && <ScimTokens api={api} publicUrl={String(form.public_url ?? "")} onNotice={onNotice} fail={fail} />}
    {section === "sso" && <div className="cc-inline cc-test">
      <TextInput aria-label="A user name to look up in the directory" placeholder="a user name to look up (optional)" value={who} onChange={(e) => setWho(e.target.value)} />
      <Button kind="secondary" disabled={!form.ldap_urls} onClick={() => api.ldapTest(who.trim()).then((r) => onNotice(!who.trim() ? "The directory " + r.server + " answered." :
        !r.found ? "The directory answered, but has no one called " + who.trim() + "." :
        r.username + (r.display_name ? " (" + r.display_name + ")" : "") + " is in " + (r.groups?.length ? r.groups.join(", ") : "no group") + "; " + (r.role ? "they get the role " + r.role + "." : "no group gives a role, so they cannot sign in.")), fail)}>
        Test the directory</Button>
    </div>}
    {section === "agents" && <Button kind="secondary" onClick={() => api.gitSync().then((r) => onNotice(r.changed ? "Pushed to Git." : "Git is up to date."), fail)}>Sync Git now</Button>}
    {section === "data" && <span className="cc-inline">
      <Button kind="secondary" onClick={() => api.backupNow().then((r) => onNotice("Backup made: " + r.file + " (" + r.size_mb + " MB)."), fail)}>Back up now</Button>
      <Button kind="secondary" disabled={!what || asking} title={what ? undefined : "Keep runs for and Keep step logs for are 0 (always): there is nothing to remove"}
        onClick={() => setAsking(true)}>Remove old runs now…</Button>
    </span>}
    {section === "data" && asking && <div className="cc-list-bulk" role="alert"><span>Remove {what}? This cannot be undone.</span>
      <Button kind="danger" onClick={() => { setAsking(false); api.purgeRuns(runDays, logDays).then((r) => onNotice("Removed " + r.runs_removed + " run(s) and " + r.log_lines_removed + " log line(s)."), fail); }}>Remove</Button>
      <Button kind="plain" onClick={() => setAsking(false)}>Cancel</Button></div>}
    {(section === "connections" || section === "agents" || section === "data") && <p className="st-muted">The buttons use the saved settings: save first.</p>}
  </>;
}

/**
 * SCIM provisioning (Settings item 4f): the address to give the identity provider (Entra ID, Okta), its tokens with
 * when each was last used, a new token shown this once (kept only as a hash), and Revoke.
 */
function ScimTokens({ api, publicUrl, onNotice, fail }: { api: CrApi; publicUrl: string; onNotice: (t: string) => void; fail: (e: unknown) => void }) {
  const [list, setList] = useState<ScimToken[] | null>(null);
  const [name, setName] = useState("");
  const [made, setMade] = useState<{ name: string; token: string } | null>(null);
  const load = useCallback(() => api.scimTokens().then(setList, fail), [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [load]);
  const address = (publicUrl.trim().replace(/\/+$/, "") || location.origin) + "/scim/v2";
  const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "never";
  return <section className="cc-scim" aria-label="SCIM provisioning">
    <h3>SCIM provisioning</h3>
    <p className="st-muted">Entra ID, Okta and other identity providers create, change and switch off people here, and push groups (the groups-to-roles list gives the role).
      Give them this address and a token: <code>{address}</code></p>
    {made && <div className="st-notice" role="status"><span>The token for {made.name}, shown this once: copy it into the identity provider now.</span>
      <input className="ui-input ui-mono cc-scim-token" readOnly value={made.token} aria-label="The new SCIM token" onFocus={(e) => e.currentTarget.select()} />
      <Button kind="secondary" onClick={() => setMade(null)}>Done</Button></div>}
    {list && list.length > 0 && <table className="st-table" aria-label="SCIM tokens"><thead><tr><th scope="col">For</th><th scope="col">Made</th><th scope="col">Last used</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
      <tbody>{list.map((t) => <tr key={t.id}><td>{t.name}</td><td>{when(t.created)} by {t.created_by}</td><td>{when(t.last_used)}</td>
        <td><Button kind="plain" onClick={() => api.revokeScimToken(t.id).then(() => { onNotice("Revoked the SCIM token for " + t.name + "; it no longer works."); load(); }, fail)}>Revoke</Button></td></tr>)}</tbody></table>}
    <div className="cc-inline">
      <TextInput aria-label="Name of the new SCIM token" placeholder="the identity provider, e.g. Entra ID" value={name} onChange={(e) => setName(e.target.value)} />
      <Button kind="secondary" disabled={!name.trim()} onClick={() => api.makeScimToken(name.trim()).then((r) => { setMade({ name: r.name, token: r.token }); setName(""); load(); }, fail)}>Make a SCIM token</Button>
    </div>
  </section>;
}
