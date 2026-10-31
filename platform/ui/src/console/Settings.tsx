import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import type { CrApi } from "../api/cr";
import { Button, Field, Select, TextArea, TextInput } from "../components";

/**
 * Administration > Settings, built as AA's Settings (checked against AA's docs, UiPath's tenant and organisation
 * settings and Blue Prism's System settings, 31 Oct 2026): sections down the side, as AA's and UiPath's tabs, each
 * saved on its own and only with the values changed; a search across every setting (by label or key); and each
 * section's own actions (send a test e-mail or syslog message, sync Git now, back up now, remove old runs now).
 */
type Kind = "text" | "number" | "area" | "check" | "select" | "roles";
type Setting = { key: string; label: string; kind: Kind; min?: number; max?: number; placeholder?: string; options?: [string, string][] };
type Section = { id: string; title: string; about: string; settings: Setting[] };

const SECTIONS: Section[] = [
  { id: "general", title: "General", about: "What everyone sees, and the address of this Command Center.", settings: [
    { key: "announcement", label: "Announcement for all users (empty for none)", kind: "area", placeholder: "Maintenance on Saturday 10:00–12:00 UTC" },
    { key: "public_url", label: "Public address of the Command Center (for sign-in redirects)", kind: "text", placeholder: "https://ooa.example.com" },
    { key: "max_upload_mb", label: "Largest upload (MB)", kind: "number", min: 1, max: 1024 },
    { key: "queue_poll_seconds", label: "Work queue check interval (seconds)", kind: "number", min: 3, max: 3600 },
    { key: "default_minutes_saved", label: "Minutes saved per successful run (for agents without their own value)", kind: "number", min: 0, max: 100000 },
    { key: "hourly_cost", label: "Cost of an hour of the manual work (for money saved in Value)", kind: "number", min: 0, max: 100000 },
    { key: "currency", label: "Its currency (a three-letter code: INR, USD, EUR)", kind: "text", placeholder: "INR" },
    { key: "template_catalog_url", label: "Template catalogue (an https address of templates.json; off: none)", kind: "text" },
  ] },
  { id: "security", title: "Sign-in and security", about: "Sessions, passwords, two-step sign-in, and which addresses may connect. This computer is always allowed.", settings: [
    { key: "session_hours", label: "Sign-in session length (hours)", kind: "number", min: 1, max: 720 },
    { key: "max_sessions_per_user", label: "Sign-ins per person at once", kind: "number", min: 1, max: 50 },
    { key: "inactive_user_days", label: "Disable users inactive for (days, 0 = never)", kind: "number", min: 0, max: 3650 },
    { key: "password_min_length", label: "Shortest password (characters)", kind: "number", min: 12, max: 64 },
    { key: "password_history", label: "Passwords that cannot be used again (the last N, 0 = no rule)", kind: "number", min: 0, max: 24 },
    { key: "password_max_days", label: "Change passwords every (days, 0 = never)", kind: "number", min: 0, max: 3650 },
    { key: "mfa_required", label: "Two-step sign-in", kind: "select", options: [["0", "Optional (each person decides)"], ["1", "Required for administrators"], ["2", "Required for everyone with a password"]] },
    { key: "ip_allow_list", label: "IP allow list (addresses or CIDR ranges, separated by commas; empty = every address)", kind: "area", placeholder: "10.0.0.0/8, 203.0.113.7" },
    { key: "trusted_proxies", label: "Trusted proxies: load balancers whose X-Forwarded-For names the client", kind: "area", placeholder: "10.0.5.10, 10.0.6.0/24" },
    { key: "switched_off_roles", label: "Built-in roles switched off: they give no permissions and cannot be given; people keep their other roles", kind: "roles" },
  ] },
  { id: "sso", title: "Single sign-on", about: "OpenID Connect and SAML 2.0 (Entra ID, Okta, Google, Keycloak, ADFS). Local accounts always sign in with their password too.", settings: [
    { key: "sso_issuer", label: "OpenID Connect: issuer URL", kind: "text", placeholder: "https://login.microsoftonline.com/<tenant>/v2.0" },
    { key: "sso_client_id", label: "OpenID Connect: client id", kind: "text" },
    { key: "sso_client_secret_ref", label: "OpenID Connect: client secret (vault reference folder/credential/attribute)", kind: "text", placeholder: "sso/oidc/client_secret" },
    { key: "sso_label", label: "OpenID Connect: button text", kind: "text", placeholder: "Sign in with your organisation account" },
    { key: "saml_idp_sso_url", label: "SAML: identity provider sign-in address", kind: "text", placeholder: "https://login.microsoftonline.com/<tenant>/saml2" },
    { key: "saml_idp_entity_id", label: "SAML: identity provider entity id (issuer)", kind: "text" },
    { key: "saml_sp_entity_id", label: "SAML: this Command Center's entity id (empty = the metadata address)", kind: "text" },
    { key: "saml_label", label: "SAML: button text", kind: "text", placeholder: "Sign in with SAML" },
    { key: "saml_idp_certificate", label: "SAML: identity provider signing certificate (PEM)", kind: "area", placeholder: "-----BEGIN CERTIFICATE-----" },
    { key: "sso_default_role", label: "Role for new people (empty = existing accounts only)", kind: "text", placeholder: "viewer" },
    { key: "sso_group_roles", label: "Groups to roles: one line per group, group = role (first match wins, at every sign-in)", kind: "area", placeholder: "OOA Admins = admin\nFinance Automation = bot_runner" },
  ] },
  { id: "runners", title: "Runner Machines", about: "Updates, temporary runners, secrets, limits and screen pictures for every runner (a runner can have its own limits).", settings: [
    { key: "runner_auto_update", label: "Update runners automatically to this Command Center's version when they are idle", kind: "check" },
    { key: "temporary_runner_hours", label: "Remove temporary runners after this many hours without contact", kind: "number", min: 1, max: 720 },
    { key: "runner_secret_days", label: "Give runners a new secret every (days, 0 = never)", kind: "number", min: 0, max: 365 },
    { key: "runner_cpu_max", label: "A runner above this CPU use takes no new work (%, 0 = no limit)", kind: "number", min: 0, max: 100 },
    { key: "runner_mem_max", label: "A runner above this memory use takes no new work (%, 0 = no limit)", kind: "number", min: 0, max: 100 },
    { key: "runner_screen_pictures", label: "Administrators may ask a runner for a picture of its screen (each one is audited)", kind: "check" },
  ] },
  { id: "agents", title: "Agents and repository", about: "Rules for what goes into the agent repository and into production, and its copy in Git.", settings: [
    { key: "production_approval", label: "Approval before production: a second person approves every version before it goes live", kind: "check" },
    { key: "block_executable_uploads", label: "Block programs and scripts (.exe, .dll, .ps1 and similar) in repository uploads", kind: "check" },
    { key: "git_remote_url", label: "Git: remote (https://...git)", kind: "text", placeholder: "https://github.com/acme/agents.git" },
    { key: "git_branch", label: "Git: branch", kind: "text", placeholder: "main" },
    { key: "git_username", label: "Git: user name", kind: "text", placeholder: "(any, for a token)" },
    { key: "git_token_ref", label: "Git: access token (vault reference folder/credential/attribute)", kind: "text", placeholder: "git/agents-repo/token" },
    { key: "git_auto_push", label: "Git: push after every check-in, production change and deletion", kind: "check" },
    { key: "repo_files_folder", label: "Shared folder for repository files (for several nodes; empty = the data folder)", kind: "text" },
  ] },
  { id: "mcp", title: "AI assistants (MCP)", about: "Assistants connect to /api/mcp with a person's API key, or an OAuth token from your identity provider, and run the agents listed here as tools.", settings: [
    { key: "mcp_agents", label: "Agents offered as tools (paths, separated by commas; * for all)", kind: "text", placeholder: "Finance/Invoice lookup.flow.json" },
    { key: "mcp_runner", label: "Runner or runner pool id (empty = any online runner)", kind: "text" },
    { key: "mcp_timeout_seconds", label: "Wait for a result (seconds)", kind: "number", min: 10, max: 3600 },
    { key: "mcp_oauth_audience", label: "OAuth token audience (empty = API keys only)", kind: "text", placeholder: "api://ooa-mcp" },
    { key: "mcp_oauth_scope", label: "Scope the token must carry (optional)", kind: "text", placeholder: "mcp.use" },
  ] },
  { id: "connections", title: "E-mail, proxy and SIEM", about: "The Command Center's own e-mail, its proxy to the internet, and audit forwarding to your SIEM.", settings: [
    { key: "mail_smtp_host", label: "Outgoing mail server", kind: "text", placeholder: "smtp.office365.com" },
    { key: "mail_smtp_port", label: "Port", kind: "number", min: 1, max: 65535 },
    { key: "mail_smtp_security", label: "Security", kind: "select", options: [["starttls", "STARTTLS (port 587)"], ["ssl", "SSL/TLS (port 465)"], ["none", "None (trusted network only)"]] },
    { key: "mail_smtp_user", label: "Account (empty = no sign-in)", kind: "text" },
    { key: "mail_smtp_password_ref", label: "Password (vault reference folder/credential/attribute)", kind: "text", placeholder: "mail/smtp/password" },
    { key: "mail_from", label: "Sender address", kind: "text" },
    { key: "license_mail_to", label: "Also send licence reminders to (addresses, separated by commas)", kind: "text" },
    { key: "outbound_proxy", label: "Proxy for outgoing connections (http://host:port, empty = direct)", kind: "text", placeholder: "http://proxy.example.com:8080" },
    { key: "outbound_proxy_bypass", label: "Reach these directly (host names, * allowed)", kind: "text", placeholder: "*.example.com, 10.*" },
    { key: "syslog_target", label: "Syslog target for every audit entry (host:port, empty = off)", kind: "text", placeholder: "siem.example.com:514" },
    { key: "syslog_protocol", label: "Syslog protocol", kind: "select", options: [["udp", "UDP"], ["tcp", "TCP"]] },
  ] },
  { id: "data", title: "Data retention and backups", about: "How long history is kept (removed once an hour; queued and running runs never), and the backups of everything.", settings: [
    { key: "run_log_retention_days", label: "Keep step logs for (days, 0 = always)", kind: "number", min: 0, max: 36500 },
    { key: "run_retention_days", label: "Keep runs for (days, 0 = always)", kind: "number", min: 0, max: 36500 },
    { key: "audit_retention_days", label: "Keep audit entries for (days, at least 90; 0 = always; older ones are archived first)", kind: "number", min: 0, max: 36500 },
    { key: "ai_call_retention_days", label: "Keep AI call records for (days, 0 = always)", kind: "number", min: 0, max: 36500 },
    { key: "work_item_retention_days", label: "Keep finished work-queue items for (days, 0 = always)", kind: "number", min: 0, max: 36500 },
    { key: "backup_hours", label: "Back up every (hours, 0 = never)", kind: "number", min: 0, max: 168 },
    { key: "backup_keep_days", label: "Keep backups for (days)", kind: "number", min: 1, max: 3650 },
    { key: "backup_folder", label: "Backup folder (empty = the data folder's backups folder)", kind: "text" },
  ] },
  { id: "about", title: "This Command Center", about: "The database and the nodes serving this Command Center (read-only here; the database is changed with the ooa db command).", settings: [] },
];
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const str = (v: unknown) => v === null || v === undefined ? "" : String(v);

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
  const matches = useMemo(() => text ? SECTIONS.flatMap((s) => s.settings.filter((x) => (x.label + " " + x.key).toLowerCase().includes(text)).map((x) => ({ s, x }))) : [], [text]);
  const changedIn = (s: Section) => saved ? s.settings.filter((x) => form[x.key] !== saved[x.key]) : [];
  const save = async (s: Section) => {
    const changes: Record<string, string | number> = {};
    for (const x of changedIn(s)) changes[x.key] = x.kind === "number" || x.kind === "check" || (x.kind === "select" && x.key === "mfa_required") ? Number(form[x.key] || 0) : form[x.key].trim();
    try { await api.saveSettings(changes); onNotice("Saved " + Object.keys(changes).length + " setting" + (Object.keys(changes).length === 1 ? "" : "s") + " in " + s.title + "."); await load(); }
    catch (e) { setError(message(e)); }
  };
  const field = (x: Setting) => {
    const v = form[x.key] ?? "";
    const set = (nv: string) => setForm({ ...form, [x.key]: nv });
    const changed = saved && v !== saved[x.key];
    if (x.kind === "roles") {
      const off = v.split(",").map((r) => r.trim()).filter(Boolean);
      return <fieldset key={x.key} className={"cc-roles-off" + (changed ? " cc-changed" : "")}><legend>{x.label}</legend>
        {([["bot_creator", "Agent builder"], ["bot_runner", "Agent runner"], ["viewer", "Viewer"]] as const).map(([r, l]) => <label key={r} className="st-check-line">
          <input type="checkbox" checked={off.includes(r)} onChange={(e) => set((e.target.checked ? [...off, r] : off.filter((o) => o !== r)).join(","))} /> Switch off {l}</label>)}
      </fieldset>;
    }
    if (x.kind === "check") return <label key={x.key} className={"st-check-line" + (changed ? " cc-changed" : "")}>
      <input type="checkbox" checked={v === "1"} onChange={(e) => set(e.target.checked ? "1" : "0")} /> {x.label}</label>;
    return <div key={x.key} className={changed ? "cc-changed" : undefined}><Field label={x.label}>{(id) =>
      x.kind === "area" ? <TextArea id={id} rows={x.key === "saml_idp_certificate" || x.key === "sso_group_roles" ? 4 : 2} value={v} placeholder={x.placeholder} onChange={(e) => set(e.target.value)} />
        : x.kind === "select" ? <Select id={id} value={v || x.options![0][0]} onChange={(e) => set(e.target.value)} options={x.options!.map(([value, label]) => ({ value, label }))} />
          : <TextInput id={id} type={x.kind === "number" ? "number" : "text"} min={x.min} max={x.max} value={v} placeholder={x.placeholder} onChange={(e) => set(e.target.value)} />}</Field></div>;
  };
  const current = SECTIONS.find((s) => s.id === section)!;

  return (
    <section className="st-page cc-home" aria-labelledby="cc-settings-title">
      <header className="st-page-head">
        <div><h1 id="cc-settings-title">Settings</h1><p>Settings for everyone, by section. Each section is saved on its own; every change is recorded in the Audit Trail.</p></div>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Find a setting">
        <TextInput type="search" aria-label="Search settings" placeholder="Search settings" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      {!saved && !error && <p className="st-muted" role="status">Loading…</p>}
      {saved && text && <div className="cc-settings-found" role="region" aria-label="Settings found">
        {matches.length ? matches.map(({ s, x }) => <div key={x.key} className="cc-found">
          <Button kind="plain" onClick={() => { setSection(s.id); setQ(""); }}>{s.title}</Button>{field(x)}</div>)
          : <p className="st-muted">No setting matches.</p>}
        {matches.length > 0 && <p className="st-muted">Open a section to save what you change here.</p>}
      </div>}
      {saved && !text && <div className="cc-settings">
        <nav className="cc-settings-nav" aria-label="Settings sections">
          {SECTIONS.map((s) => <button key={s.id} type="button" className="cc-settings-tab" aria-current={s.id === section ? "page" : undefined} onClick={() => setSection(s.id)}>
            {s.title}{changedIn(s).length > 0 && <span className="cc-count" title="Changed, not saved yet">{changedIn(s).length}</span>}</button>)}
        </nav>
        <div className="cc-settings-body" role="region" aria-label={current.title}>
          <h2 className="cc-log-title">{current.title}</h2>
          <p className="st-muted">{current.about}</p>
          {current.id === "sso" && <p className="st-muted">Register these with your provider. OpenID Connect redirect: <code>{(saved.public_url || location.origin) + "/api/auth/sso/callback"}</code>.
            SAML metadata: <code>{(saved.public_url || location.origin) + "/api/auth/saml/metadata"}</code>.</p>}
          <div className="cc-settings-fields">{current.settings.map(field)}</div>
          <Extras api={api} section={current.id} form={form} onNotice={onNotice} onError={setError} />
          {current.settings.length > 0 && <div className="cc-bar">
            <Button kind="primary" disabled={!changedIn(current).length} onClick={() => save(current)}>Save {current.title}</Button>
            <Button kind="plain" disabled={!changedIn(current).length} onClick={() => setForm({ ...form, ...Object.fromEntries(current.settings.map((x) => [x.key, saved[x.key]])) })}>Undo changes</Button>
          </div>}
        </div>
      </div>}
    </section>
  );
}

/** A section's own actions and facts: test e-mail and syslog, Git sync, backups, removing old runs, nodes and database. */
function Extras({ api, section, form, onNotice, onError }: { api: CrApi; section: string; form: Record<string, string>; onNotice: (t: string) => void; onError: (e: string) => void }) {
  const [info, setInfo] = useState<ReactNode>(null);
  const [to, setTo] = useState("");
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
    </div>}
    {section === "agents" && <Button kind="secondary" onClick={() => api.gitSync().then((r) => onNotice(r.changed ? "Pushed to Git." : "Git is up to date."), fail)}>Sync Git now</Button>}
    {section === "data" && <span className="cc-inline">
      <Button kind="secondary" onClick={() => api.backupNow().then((r) => onNotice("Backup made: " + r.file + " (" + r.size_mb + " MB)."), fail)}>Back up now</Button>
      <Button kind="secondary" onClick={() => api.purgeRuns(Number(form.run_retention_days || 0), Number(form.run_log_retention_days || 0))
        .then((r) => onNotice("Removed " + r.runs_removed + " run(s) and " + r.log_lines_removed + " log line(s)."), fail)}>Remove old runs now</Button>
    </span>}
    {(section === "connections" || section === "agents" || section === "data") && <p className="st-muted">The buttons use the saved settings: save first.</p>}
  </>;
}
