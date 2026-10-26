package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.PackageRegistry;

import java.time.*;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * One Command Center platform services added in Part 4: settings, custom roles, API keys, runner pools, work queues,
 * central model connections, holiday calendars and the toolkit manager. Every method checks a permission and writes
 * the audit trail, like ControlRoom.
 */
public final class Platform {
    private final ControlRoom cr;
    private final Db db;
    private final PackageRegistry registry;

    Platform(ControlRoom cr, PackageRegistry registry) { this.cr = cr; this.db = cr.db; this.registry = registry; }

    // ===================== settings =====================

    /** Defaults for settings an administrator can change. */
    static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("announcement", "");
        DEFAULTS.put("inactive_user_days", 0);
        DEFAULTS.put("session_hours", 8);
        DEFAULTS.put("max_upload_mb", 5);
        DEFAULTS.put("queue_poll_seconds", 15);
        DEFAULTS.put("default_minutes_saved", 0);
        DEFAULTS.put("public_url", "");
        DEFAULTS.put("sso_issuer", "");
        DEFAULTS.put("sso_client_id", "");
        DEFAULTS.put("sso_client_secret_ref", "");
        DEFAULTS.put("sso_default_role", "");
        DEFAULTS.put("sso_label", "");
        DEFAULTS.put("mcp_agents", "");
        DEFAULTS.put("mcp_runner", "");
        DEFAULTS.put("mcp_timeout_seconds", 120);
        DEFAULTS.put("run_retention_days", 0);
        DEFAULTS.put("run_log_retention_days", 0);
        DEFAULTS.put("syslog_target", "");
        DEFAULTS.put("syslog_protocol", "udp");
        DEFAULTS.put("ip_allow_list", "");
        DEFAULTS.put("max_sessions_per_user", 5);
        DEFAULTS.put("password_min_length", 12);
        DEFAULTS.put("block_executable_uploads", 0);
        DEFAULTS.put("mfa_required", 0);
        DEFAULTS.put("production_approval", 0);
        DEFAULTS.put("runner_auto_update", 0);
        DEFAULTS.put("git_remote_url", "");
        DEFAULTS.put("git_branch", "main");
        DEFAULTS.put("git_username", "");
        DEFAULTS.put("git_token_ref", "");
        DEFAULTS.put("git_auto_push", 0);
        DEFAULTS.put("temporary_runner_hours", 8);
        DEFAULTS.put("audit_retention_days", 0);
        DEFAULTS.put("backup_hours", 0);
        DEFAULTS.put("repo_files_folder", "");
        DEFAULTS.put("password_history", 0);
        DEFAULTS.put("password_max_days", 0);
        DEFAULTS.put("ai_call_retention_days", 0);
        DEFAULTS.put("work_item_retention_days", 0);
        DEFAULTS.put("backup_keep_days", 7);
        DEFAULTS.put("backup_folder", "");
        DEFAULTS.put("runner_secret_days", 30);
        DEFAULTS.put("saml_idp_sso_url", "");
        DEFAULTS.put("saml_idp_entity_id", "");
        DEFAULTS.put("saml_idp_certificate", "");
        DEFAULTS.put("saml_sp_entity_id", "");
        DEFAULTS.put("saml_label", "");
        DEFAULTS.put("mail_smtp_host", "");
        DEFAULTS.put("mail_smtp_port", 587);
        DEFAULTS.put("mail_smtp_security", "starttls");
        DEFAULTS.put("mail_smtp_user", "");
        DEFAULTS.put("mail_smtp_password_ref", "");
        DEFAULTS.put("mail_from", "");
        DEFAULTS.put("license_mail_to", "");
        DEFAULTS.put("switched_off_roles", "");
        DEFAULTS.put("mcp_oauth_audience", "");
        DEFAULTS.put("mcp_oauth_scope", "");
        DEFAULTS.put("outbound_proxy", "");
        DEFAULTS.put("outbound_proxy_bypass", "");
    }

    /** The allowed range of each number setting (the console uses the same limits). */
    static final Map<String, long[]> RANGES = Map.ofEntries(
        Map.entry("inactive_user_days", new long[] { 0, 3650 }),
        Map.entry("session_hours", new long[] { 1, 720 }),
        Map.entry("max_upload_mb", new long[] { 1, 1024 }),
        Map.entry("queue_poll_seconds", new long[] { 3, 3600 }),
        Map.entry("default_minutes_saved", new long[] { 0, 100000 }),
        Map.entry("mcp_timeout_seconds", new long[] { 10, 3600 }),
        Map.entry("run_retention_days", new long[] { 0, 36500 }),
        Map.entry("run_log_retention_days", new long[] { 0, 36500 }),
        Map.entry("max_sessions_per_user", new long[] { 1, 50 }),
        Map.entry("password_min_length", new long[] { 12, 64 }),
        Map.entry("block_executable_uploads", new long[] { 0, 1 }),
        Map.entry("mfa_required", new long[] { 0, 2 }),
        Map.entry("production_approval", new long[] { 0, 1 }),
        Map.entry("runner_auto_update", new long[] { 0, 1 }),
        Map.entry("git_auto_push", new long[] { 0, 1 }),
        Map.entry("temporary_runner_hours", new long[] { 1, 720 }),
        Map.entry("audit_retention_days", new long[] { 0, 36500 }),
        Map.entry("backup_hours", new long[] { 0, 168 }),
        Map.entry("password_history", new long[] { 0, 24 }),
        Map.entry("password_max_days", new long[] { 0, 3650 }),
        Map.entry("ai_call_retention_days", new long[] { 0, 36500 }),
        Map.entry("work_item_retention_days", new long[] { 0, 36500 }),
        Map.entry("backup_keep_days", new long[] { 1, 3650 }),
        Map.entry("runner_secret_days", new long[] { 0, 365 }),
        Map.entry("mail_smtp_port", new long[] { 1, 65535 }));

    /** Settings are read on every request (session length, upload size), so they are kept for a few seconds; a change clears the copy. */
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public Map<String, Object> settings() {
        Map<String, Object> c = cached;
        if (c != null && System.currentTimeMillis() - cachedAt < 5000) return new LinkedHashMap<>(c);
        Map<String, Object> out = new LinkedHashMap<>(DEFAULTS);
        // only the settings people may change; internal rows (the evaluation start) stay out of view
        for (Map<String, Object> r : db.query("SELECT name, json_value FROM settings")) if (DEFAULTS.containsKey((String) r.get("name"))) out.put((String) r.get("name"), JsonLite.parse((String) r.get("json_value")));
        cached = out; cachedAt = System.currentTimeMillis();
        return new LinkedHashMap<>(out);
    }

    public Object setting(String name) { return settings().get(name); }

    /** The built-in roles switched off in Settings (read on every permission check, so kept with the settings copy). */
    Set<String> switchedOffRoles() {
        String s = String.valueOf(setting("switched_off_roles"));
        if (s.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(s.split(",")));
    }

    /**
     * The Command Center's own outgoing connections (single sign-on, SAML metadata, Git, model connections, external
     * vaults, webhooks it calls) go through this proxy when set: http://host:port, with host names to reach directly.
     * Applied at start and when the setting changes, through Java's proxy settings.
     */
    private volatile boolean proxyApplied;

    void applyOutboundProxy() {
        String p = String.valueOf(setting("outbound_proxy")).trim(), bypass = String.valueOf(setting("outbound_proxy_bypass")).trim();
        if (p.isEmpty()) {
            // only undo what this setting did: proxy properties given to Java at start (JAVA_OPTS) stay
            if (proxyApplied) for (String k : List.of("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts")) System.clearProperty(k);
            proxyApplied = false;
            return;
        }
        proxyApplied = true;
        java.net.URI u = java.net.URI.create(p);
        for (String scheme : List.of("http", "https")) { System.setProperty(scheme + ".proxyHost", u.getHost()); System.setProperty(scheme + ".proxyPort", String.valueOf(u.getPort())); }
        // this computer is always reached directly (the built-in runner and Studio listener)
        String direct = "localhost|127.*|[::1]" + (bypass.isEmpty() ? "" : "|" + String.join("|", bypass.split("[,;\\s|]+")));
        System.setProperty("http.nonProxyHosts", direct);
    }

    int intSetting(String name) { Object v = setting(name); return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v)); }

    /** Checks every change first (known name, whole number in its range, address format), then saves them all in one transaction. */
    public Map<String, Object> updateSettings(User by, Map<String, Object> changes, String ip) {
        cr.require(by, "settings.manage", "change settings");
        Map<String, Object> clean = new LinkedHashMap<>();
        for (var e : changes.entrySet()) {
            String k = e.getKey();
            if (!DEFAULTS.containsKey(k)) throw new OoaException("CR.BAD_INPUT", "Unknown setting '" + k + "'", "Settings: " + DEFAULTS.keySet());
            Object v = e.getValue();
            if (DEFAULTS.get(k) instanceof Number) {
                long n;
                try { n = v instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(v).trim()); }
                catch (NumberFormatException x) { throw new OoaException("CR.BAD_INPUT", "Setting '" + k + "' needs a whole number"); }
                long[] r = RANGES.getOrDefault(k, new long[] { 0, 100000 });
                if (k.equals("audit_retention_days") && n > 0 && n < 90) throw new OoaException("CR.BAD_INPUT", "Keep audit entries for at least 90 days (0 keeps them always)");
                if (n < r[0] || n > r[1]) throw new OoaException("CR.BAD_INPUT", "Setting '" + k + "' must be from " + r[0] + " to " + r[1]);
                v = n;
            } else {
                String s = String.valueOf(v == null ? "" : v).trim();
                if (s.length() > (k.equals("saml_idp_certificate") ? 10000 : 2000)) throw new OoaException("CR.BAD_INPUT", "Setting '" + k + "' is too long");
                if (k.equals("saml_idp_sso_url") && !s.isEmpty() && !s.matches("(?i)https?://[^\\s]+"))
                    throw new OoaException("CR.BAD_INPUT", "The identity provider's sign-in address starts with https://");
                if (k.equals("saml_idp_certificate") && !s.isEmpty()) {
                    try { java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(
                            Base64.getDecoder().decode(s.replaceAll("-----(BEGIN|END) CERTIFICATE-----", "").replaceAll("\\s", "")))); }
                    catch (Exception x) { throw new OoaException("CR.BAD_INPUT", "The identity provider certificate is not a PEM certificate", "Paste the signing certificate from the identity provider's SAML set-up or metadata."); }
                }
                if ((k.equals("public_url") || k.equals("sso_issuer")) && !s.isEmpty() && !s.matches("(?i)https?://[^\\s/?#]+[^\\s]*"))
                    throw new OoaException("CR.BAD_INPUT", "Setting '" + k + "' must be a web address starting with https:// (or http:// on a test system)");
                if (k.equals("git_remote_url") && !s.isEmpty() && !s.matches("(?i)(https?://[^\\s]+|file:[^\\s]+)"))
                    throw new OoaException("CR.BAD_INPUT", "The Git remote is an https:// address, for example https://github.com/acme/agents.git");
                if (k.equals("git_branch") && !s.isEmpty() && !s.matches("[A-Za-z0-9._/-]{1,100}") ) throw new OoaException("CR.BAD_INPUT", "Not a branch name: " + s);
                if (k.equals("git_token_ref") && !s.isEmpty() && s.split("/").length != 3) throw new OoaException("CR.BAD_INPUT", "The token is a vault reference: vault folder/credential/attribute");
                if (k.equals("syslog_target") && !s.isEmpty() && !s.matches("[A-Za-z0-9.\\-\\[\\]:]+:\\d{1,5}"))
                    throw new OoaException("CR.BAD_INPUT", "The syslog target is host:port, for example siem.example.com:514");
                if (k.equals("switched_off_roles") && !s.isEmpty()) {
                    List<String> off = new ArrayList<>();
                    for (String r : s.split("[,;\\s]+")) {
                        if (r.isEmpty()) continue;
                        if (r.equals("admin")) throw new OoaException("CR.BAD_INPUT", "The administrator role cannot be switched off");
                        if (!Rbac.ROLES.contains(r)) throw new OoaException("CR.BAD_INPUT", "Only built-in roles are switched off here: " + r, "Delete a custom role on the Roles page instead.");
                        if (!off.contains(r)) off.add(r);
                    }
                    s = String.join(",", off);
                }
                if (k.equals("outbound_proxy") && !s.isEmpty() && !s.matches("(?i)http://[A-Za-z0-9.\\-]+:\\d{1,5}/?"))
                    throw new OoaException("CR.BAD_INPUT", "The proxy is http://host:port, for example http://proxy.example.com:8080");
                if (k.equals("outbound_proxy_bypass") && !s.isEmpty() && !s.matches("[A-Za-z0-9.*\\-|,; ]+"))
                    throw new OoaException("CR.BAD_INPUT", "List host names to reach directly, for example *.example.com, 10.*");
                if (k.equals("mail_smtp_security") && !List.of("starttls", "ssl", "none").contains(s)) throw new OoaException("CR.BAD_INPUT", "The mail security is starttls, ssl or none");
                if (k.equals("mail_smtp_password_ref") && !s.isEmpty() && s.split("/").length != 3) throw new OoaException("CR.BAD_INPUT", "The mail password is a vault reference: vault folder/credential/attribute");
                if (k.equals("mail_smtp_host") && !s.isEmpty() && !s.matches("[A-Za-z0-9.-]+")) throw new OoaException("CR.BAD_INPUT", "The mail server is a host name, for example smtp.office365.com");
                if (k.equals("mail_from") && !s.isEmpty()) Mailer.address(s);
                if (k.equals("license_mail_to") && !s.isEmpty()) for (String a : s.split("[,;\\s]+")) if (!a.isEmpty()) Mailer.address(a);
                if (k.equals("syslog_protocol") && !s.equals("udp") && !s.equals("tcp")) throw new OoaException("CR.BAD_INPUT", "The syslog protocol is udp or tcp");
                if (k.equals("ip_allow_list")) {
                    IpAllowList l = IpAllowList.parse(s);
                    // never lock out the person saving the list (this computer is always allowed)
                    boolean local = ip == null || ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1");
                    if (!l.isEmpty() && !local && !l.allows(ip)) throw new OoaException("CR.BAD_INPUT", "Your own address " + ip + " is not on the list; saving it would lock you out", "Add your address or its range to the list");
                }
                if (k.equals("sso_default_role") && !s.isEmpty() && !Rbac.ROLES.contains(s) && db.one("SELECT name FROM roles WHERE name = ?", s) == null)
                    throw new OoaException("CR.BAD_INPUT", "Setting 'sso_default_role' names a role that does not exist: " + s);
                v = s;
            }
            clean.put(k, v);
        }
        db.tx(() -> {
            for (var e : clean.entrySet()) {
                String json = JsonLite.write(e.getValue());
                if (db.update("UPDATE settings SET json_value = ?, updated = ?, updated_by = ? WHERE name = ?", json, now(), by.username(), e.getKey()) == 0)
                    db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", e.getKey(), json, now(), by.username());
            }
            cached = null;
            cr.audit.record(by.username(), "settings.update", "settings", null, clean, "ok", ip);
            return null;
        });
        cached = null;
        if (clean.containsKey("outbound_proxy") || clean.containsKey("outbound_proxy_bypass")) applyOutboundProxy();
        return settings();
    }

    /** How long a sign-in lasts (Settings: session_hours, 1 to 720). */
    public java.time.Duration sessionLength() { return java.time.Duration.ofHours(Math.max(1, Math.min(720, intSetting("session_hours")))); }

    /** The largest request body: Settings max_upload_mb, plus room for Base64 (files travel inside JSON). */
    public int maxBodyBytes() { long mb = Math.max(1, Math.min(1024, intSetting("max_upload_mb"))); return (int) Math.min(Integer.MAX_VALUE - 8, mb * 1024 * 1024 * 4 / 3 + 1024 * 1024); }

    /** Disables users who have not signed in for the configured number of days (0 = never). Called by the server clock. */
    public int disableInactiveUsers() {
        int days = intSetting("inactive_user_days");
        if (days <= 0) return 0;
        Instant cutoff = now().minus(Duration.ofDays(days));
        int n = 0;
        for (Map<String, Object> u : db.query("SELECT id, username, role, last_login, created FROM users WHERE enabled = TRUE")) {
            Object seen = u.get("last_login") != null ? u.get("last_login") : u.get("created");
            if (Instant.parse(String.valueOf(seen)).isAfter(cutoff) || "admin".equals(u.get("role"))) continue;
            db.update("UPDATE users SET enabled = FALSE WHERE id = ?", u.get("id"));
            db.update("DELETE FROM sessions WHERE user_id = ?", u.get("id"));
            cr.audit.record("system", "user.auto_disable", "user", (String) u.get("username"), Map.of("inactive_days", days), "ok", null);
            n++;
        }
        return n;
    }

    // ===================== custom roles =====================

    public List<Map<String, Object>> roles(User by) {
        cr.require(by, "users.manage", "manage roles");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String r : Rbac.ROLES) out.add(Map.of("name", r, "builtin", true, "permissions", Rbac.permissions(r), "description", "Built-in role", "switched_off", cr.roleSwitchedOff(r)));
        for (Map<String, Object> r : db.query("SELECT * FROM roles ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.get("name")); m.put("builtin", false); m.put("permissions", csv(r.get("permissions")));
            m.put("description", r.get("description")); m.put("created_by", r.get("created_by"));
            m.put("users", ((Number) db.one("SELECT COUNT(*) AS n FROM users WHERE role = ?", r.get("name")).get("n")).intValue());
            out.add(m);
        }
        return out;
    }

    public void saveRole(User by, String name, String description, Object permissions, String ip) {
        cr.require(by, "users.manage", "manage roles");
        if (name == null || !name.matches("^[a-z][a-z0-9_]{2,39}$")) throw new OoaException("CR.BAD_INPUT", "Role names use 3-40 lower-case letters, digits and _");
        if (Rbac.ROLES.contains(name)) throw new OoaException("CR.BAD_INPUT", "Built-in roles cannot be changed", "Create a new role instead.");
        List<String> perms = csv(permissions);
        for (String p : perms) if (!Rbac.all().contains(p)) throw new OoaException("CR.BAD_INPUT", "Unknown permission '" + p + "'");
        String joined = String.join(",", perms);
        if (db.update("UPDATE roles SET description = ?, permissions = ? WHERE name = ?", description, joined, name) == 0)
            db.update("INSERT INTO roles (name, description, permissions, created_by, created) VALUES (?,?,?,?,?)", name, description, joined, by.username(), now());
        cr.loadRoles();
        cr.audit.record(by.username(), "role.save", "role", name, Map.of("permissions", perms), "ok", ip);
    }

    public void deleteRole(User by, String name, String ip) {
        cr.require(by, "users.manage", "manage roles");
        long users = ((Number) db.one("SELECT COUNT(*) AS n FROM users WHERE role = ?", name).get("n")).longValue();
        if (users > 0) throw new OoaException("CR.CONFLICT", users + " user(s) still have the role '" + name + "'", "Give them another role first.");
        if (db.update("DELETE FROM roles WHERE name = ?", name) == 0) throw notFound("Role");
        cr.loadRoles();
        cr.audit.record(by.username(), "role.delete", "role", name, null, "ok", ip);
    }

    // ===================== API keys =====================

    public List<Map<String, Object>> apiKeys(User by) {
        return db.query("SELECT id, name, created, expires, last_used FROM api_keys WHERE user_id = ? ORDER BY created DESC", by.id());
    }

    /** Creates a key for the signed-in user; the key is shown once and only its hash is stored. */
    public Map<String, Object> createApiKey(User by, String name, int days, String ip) {
        if (name == null || name.isBlank() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Give the key a name of up to 120 characters");
        String key = "ooak_" + Security.token(30);
        String kid = id("key");
        Instant expires = days <= 0 ? null : now().plus(Duration.ofDays(Math.min(days, 730)));
        db.update("INSERT INTO api_keys (id, user_id, name, key_hash, created, expires) VALUES (?,?,?,?,?,?)", kid, by.id(), name.trim(), Security.sha256(key), now(), expires);
        cr.audit.record(by.username(), "apikey.create", "apikey", kid, Map.of("name", name, "days", days), "ok", ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", kid); out.put("key", key); out.put("expires", expires == null ? null : expires.toString());
        return out;
    }

    public void deleteApiKey(User by, String kid, String ip) {
        if (db.update("DELETE FROM api_keys WHERE id = ? AND user_id = ?", kid, by.id()) == 0) throw notFound("API key");
        cr.audit.record(by.username(), "apikey.delete", "apikey", kid, null, "ok", ip);
    }

    /** The user behind an API key, or null. */
    public User apiKeyUser(String key) {
        if (key == null || !key.startsWith("ooak_")) return null;
        Map<String, Object> k = db.one("SELECT k.id AS kid, k.expires, u.* FROM api_keys k JOIN users u ON u.id = k.user_id WHERE k.key_hash = ?", Security.sha256(key));
        if (k == null || !Boolean.TRUE.equals(k.get("enabled"))) return null;
        if (k.get("expires") != null && Instant.parse(String.valueOf(k.get("expires"))).isBefore(now())) return null;
        db.update("UPDATE api_keys SET last_used = ? WHERE id = ?", now(), k.get("kid"));
        User u = new User((String) k.get("id"), (String) k.get("username"), (String) k.get("role"), (String) k.get("display_name"), false);
        cr.licensing.checkSignIn(u);
        return u;
    }

    // ===================== runner pools =====================

    public List<Map<String, Object>> pools(User by) {
        cr.require(by, "devices.view", "view runners");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : db.query("SELECT * FROM device_pools ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(p);
            m.put("devices", csv(p.get("devices")));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> savePool(User by, String poolId, String name, String description, Object devices, String ip) {
        cr.require(by, "pools.manage", "manage runner pools");
        if (name == null || name.isBlank() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Pool names have 1 to 120 characters");
        List<String> ids = csv(devices);
        for (String d : ids) if (db.one("SELECT id FROM devices WHERE id = ?", d) == null) throw notFound("Runner " + d);
        String pid = poolId == null || poolId.isBlank() ? id("pool") : poolId;
        if (poolId == null || poolId.isBlank())
            db.update("INSERT INTO device_pools (id, name, description, devices, created_by, created) VALUES (?,?,?,?,?,?)", pid, name.trim(), description, String.join(",", ids), by.username(), now());
        else if (db.update("UPDATE device_pools SET name = ?, description = ?, devices = ? WHERE id = ?", name.trim(), description, String.join(",", ids), pid) == 0) throw notFound("Pool");
        cr.audit.record(by.username(), "pool.save", "pool", pid, Map.of("name", name, "devices", ids), "ok", ip);
        return Map.of("id", pid);
    }

    public void deletePool(User by, String poolId, String ip) {
        cr.require(by, "pools.manage", "manage runner pools");
        if (db.one("SELECT id FROM queues WHERE pool_id = ?", poolId) != null) throw new OoaException("CR.CONFLICT", "A work queue still uses this pool", "Change the queue first.");
        if (db.update("DELETE FROM device_pools WHERE id = ?", poolId) == 0) throw notFound("Pool");
        cr.audit.record(by.username(), "pool.delete", "pool", poolId, null, "ok", ip);
    }

    /** The online, enabled runner of a pool with the fewest active runs; null if none is online. */
    /** The least busy online runner, or null. */
    public String onlineRunner() {
        String best = null; long bestLoad = Long.MAX_VALUE;
        for (Map<String, Object> d : db.query("SELECT id, last_seen FROM devices WHERE enabled = TRUE")) {
            if (d.get("last_seen") == null || Instant.parse(String.valueOf(d.get("last_seen"))).isBefore(now().minus(ONLINE))) continue;
            long load = cr.count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status IN ('queued', 'running')", d.get("id"));
            if (load < bestLoad) { best = (String) d.get("id"); bestLoad = load; }
        }
        return best;
    }

    public String pickDevice(String poolId) {
        Map<String, Object> p = db.one("SELECT devices FROM device_pools WHERE id = ?", poolId);
        if (p == null) throw notFound("Runner pool");
        String best = null;
        long bestLoad = Long.MAX_VALUE;
        for (String d : csv(p.get("devices"))) {
            Map<String, Object> dev = db.one("SELECT enabled, last_seen FROM devices WHERE id = ?", d);
            if (dev == null || !Boolean.TRUE.equals(dev.get("enabled")) || dev.get("last_seen") == null
                    || Instant.parse(String.valueOf(dev.get("last_seen"))).isBefore(now().minus(ONLINE))) continue;
            long load = cr.count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status IN ('queued', 'running')", d);
            if (load < bestLoad) { best = d; bestLoad = load; }
        }
        return best;
    }

    // ===================== work queues =====================

    /** Minutes of manual work one successful run of the agent saves; null falls back to the default in Settings. */
    public void setMinutesSaved(User by, String path, Object minutes, String ip) {
        cr.require(by, "repo.production", "set the value of agents");
        Integer m = minutes instanceof Number n ? n.intValue() : null;
        if (m != null && (m < 0 || m > 100000)) throw new OoaException("CR.BAD_INPUT", "Minutes saved must be between 0 and 100000");
        if (db.update("UPDATE bots SET minutes_saved = ? WHERE path = ?", m, path) == 0) throw notFound("Agent");
        cr.audit.record(by.username(), "bot.minutes_saved", "bot", path, minutes, "ok", ip);
    }

    // ===================== AI governance =====================

    /** Stores the AI calls a run reported (model, tokens, masking, tool calls and a hash of the prompt; never the prompt itself). */
    void recordAiCalls(Map<String, Object> run, List<?> calls) {
        for (Object o : calls) {
            if (!(o instanceof Map<?, ?> c)) continue;
            Instant ts;
            try { ts = Instant.parse(String.valueOf(c.get("ts"))); } catch (RuntimeException e) { ts = now(); }
            Object masked = c.get("masked"), tools = c.get("tool_calls");
            db.update("INSERT INTO ai_calls (id, run_id, bot_path, requested_by, ts, purpose, provider, model, input_tokens, output_tokens, latency_ms, masked, tool_calls, prompt_sha256, connection) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id("ai"), run.get("id"), run.get("bot_path"), run.get("requested_by"), ts,
                    cut(c.get("purpose"), 80), cut(c.get("provider"), 40), cut(c.get("model"), 200), num(c.get("input_tokens")), num(c.get("output_tokens")),
                    c.get("latency_ms") instanceof Number n ? n.longValue() : null,
                    masked == null ? null : cut(masked instanceof String ms ? ms : JsonLite.write(masked), 1000), tools == null ? null : cut(JsonLite.write(tools), 2000), cut(c.get("prompt_sha256"), 64),
                    cut(c.get("connection"), 60));
        }
    }

    private static String cut(Object v, int max) { if (v == null) return null; String s = String.valueOf(v); return s.length() > max ? s.substring(0, max) : s; }
    private static Integer num(Object v) { return v instanceof Number n ? n.intValue() : null; }

    /** The AI calls of one run in order (A3 trace): purpose, model, connection, tokens, time, masking and tools used. Prompts are never kept. */
    public List<Map<String, Object>> aiCallsOfRun(User by, String runId) {
        cr.require(by, "runs.view", "view runs");
        Map<String, Object> r = db.one("SELECT bot_path FROM runs WHERE id = ?", runId);
        if (r == null) throw notFound("Run");
        cr.folders.require(by, (String) r.get("bot_path"), "view", "view this agent's runs");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : db.query("SELECT ts, purpose, provider, model, connection, input_tokens, output_tokens, latency_ms, masked, tool_calls FROM ai_calls WHERE run_id = ? ORDER BY ts", runId)) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            for (String k : List.of("masked", "tool_calls")) if (c.get(k) instanceof String sv && !sv.isBlank()) { try { m.put(k, JsonLite.parse(sv)); } catch (RuntimeException ignored) { } }
            out.add(m);
        }
        return out;
    }

    /** Totals by model, agent and day, masking and tool use, and the latest calls, for the last N days. */
    public Map<String, Object> aiGovernance(User by, int days) {
        cr.require(by, "audit.view", "view AI governance");
        Instant since = now().minus(Duration.ofDays(Math.max(1, Math.min(365, days))));
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> t = db.one("SELECT COUNT(*) AS calls, SUM(input_tokens) AS input_tokens, SUM(output_tokens) AS output_tokens, AVG(latency_ms) AS avg_latency_ms, " +
                "SUM(CASE WHEN masked IS NOT NULL AND masked <> '[]' AND masked <> '' THEN 1 ELSE 0 END) AS masked_calls, " +
                "SUM(CASE WHEN tool_calls IS NOT NULL AND tool_calls <> '[]' THEN 1 ELSE 0 END) AS tool_calls FROM ai_calls WHERE ts >= ?", since);
        m.put("totals", t);
        m.put("by_model", db.query("SELECT provider, model, COUNT(*) AS calls, SUM(input_tokens) AS input_tokens, SUM(output_tokens) AS output_tokens, AVG(latency_ms) AS avg_latency_ms " +
                "FROM ai_calls WHERE ts >= ? GROUP BY provider, model ORDER BY calls DESC", since));
        m.put("by_agent", db.query("SELECT bot_path, COUNT(*) AS calls, SUM(input_tokens) + SUM(output_tokens) AS tokens FROM ai_calls WHERE ts >= ? GROUP BY bot_path ORDER BY calls DESC LIMIT 20", since));
        m.put("by_user", db.query("SELECT requested_by, COUNT(*) AS calls FROM ai_calls WHERE ts >= ? GROUP BY requested_by ORDER BY calls DESC LIMIT 20", since));
        m.put("recent", db.query("SELECT id, run_id, bot_path, requested_by, ts, purpose, provider, model, input_tokens, output_tokens, latency_ms, masked, tool_calls, prompt_sha256 " +
                "FROM ai_calls WHERE ts >= ? ORDER BY ts DESC LIMIT 200", since));
        m.put("days", days);
        return m;
    }

    public List<Map<String, Object>> queues(User by) {
        cr.require(by, "queues.view", "view work queues");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> q : db.query("SELECT * FROM queues ORDER BY name")) {
            if (!queueAllows(by, (String) q.get("id"), "view")) continue;
            Map<String, Object> m = new LinkedHashMap<>(q);
            m.put("my_roles", memberRoles(by, (String) q.get("id")));
            m.put("counts", counts((String) q.get("id")));
            if (q.get("sla_minutes") instanceof Number sla && sla.intValue() > 0)
                m.put("overdue", cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status IN ('new', 'in_progress', 'retry') AND created < ?",
                        q.get("id"), now().minus(Duration.ofMinutes(sla.intValue()))));
            out.add(m);
        }
        return out;
    }

    Map<String, Object> counts(String queueId) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (String s : List.of("new", "in_progress", "review", "retry", "success", "failed", "deferred")) c.put(s, 0L);
        for (Map<String, Object> r : db.query("SELECT status, COUNT(*) AS n FROM queue_items WHERE queue_id = ? GROUP BY status", queueId)) c.put((String) r.get("status"), r.get("n"));
        return c;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveQueue(User by, String queueId, Map<String, Object> q, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
        if (queueId != null && !queueId.isBlank()) requireQueue(by, queueId, "manage", "change this work queue");
        String model = q.get("model_id") instanceof String ms && !ms.isBlank() ? ms : null;
        if (model != null && db.one("SELECT id FROM work_item_models WHERE id = ?", model) == null) throw notFound("Work item model");
        String name = String.valueOf(q.getOrDefault("name", "")).trim();
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Queue names have 1 to 120 characters");
        String bot = (String) q.get("bot_path");
        if (bot != null && !bot.isBlank()) cr.content(bot, "production");
        String pool = (String) q.get("pool_id");
        if (pool != null && !pool.isBlank() && db.one("SELECT id FROM device_pools WHERE id = ?", pool) == null) throw notFound("Runner pool");
        int attempts = q.get("max_attempts") instanceof Number n ? n.intValue() : 1;
        if (attempts < 1 || attempts > 10) throw new OoaException("CR.BAD_INPUT", "Attempts must be between 1 and 10");
        Integer sla = q.get("sla_minutes") instanceof Number n && n.intValue() > 0 ? n.intValue() : null;
        String qid = queueId == null || queueId.isBlank() ? id("queue") : queueId;
        if (queueId == null || queueId.isBlank())
            db.update("INSERT INTO queues (id, name, description, bot_path, pool_id, sla_minutes, max_attempts, processing, run_as, created_by, created) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    qid, name, q.get("description"), blankToNull(bot), blankToNull(pool), sla, attempts, false, by.username(), by.username(), now());
        else if (db.update("UPDATE queues SET name = ?, description = ?, bot_path = ?, pool_id = ?, sla_minutes = ?, max_attempts = ? WHERE id = ?",
                name, q.get("description"), blankToNull(bot), blankToNull(pool), sla, attempts, qid) == 0) throw notFound("Queue");
        if (q.containsKey("model_id")) {
            if (queueId != null && !queueId.isBlank() && model != null && !model.equals(db.one("SELECT model_id FROM queues WHERE id = ?", qid).get("model_id"))
                    && cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ?", qid) > 0)
                throw new OoaException("CR.CONFLICT", "The queue already has work items", "The work item model can only change while the queue is empty.");
            db.update("UPDATE queues SET model_id = ? WHERE id = ?", model, qid);
        }
        cr.audit.record(by.username(), "queue.save", "queue", qid, Map.of("name", name), "ok", ip);
        return Map.of("id", qid);
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    public void deleteQueue(User by, String qid, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
        requireQueue(by, qid, "manage", "delete this work queue");
        if (cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status = 'in_progress'", qid) > 0)
            throw new OoaException("CR.CONFLICT", "Items are being worked on", "Stop processing and wait for them to finish.");
        db.tx(() -> { db.update("DELETE FROM queue_items WHERE queue_id = ?", qid); db.update("DELETE FROM queue_members WHERE queue_id = ?", qid);
            if (db.update("DELETE FROM queues WHERE id = ?", qid) == 0) throw notFound("Queue"); return null; });
        cr.audit.record(by.username(), "queue.delete", "queue", qid, null, "ok", ip);
    }

    /** Starts or stops automatic processing; processing needs an agent and a runner pool. The person who starts it is the run-as user. */
    public void setProcessing(User by, String qid, boolean on, String ip) {
        cr.require(by, "queues.work", "process work queues");
        requireQueue(by, qid, "process", "process this work queue");
        Map<String, Object> q = db.one("SELECT * FROM queues WHERE id = ?", qid);
        if (q == null) throw notFound("Queue");
        if (on && (q.get("bot_path") == null || q.get("pool_id") == null))
            throw new OoaException("CR.BAD_INPUT", "Choose the agent and the runner pool that process this queue first");
        db.update("UPDATE queues SET processing = ?, run_as = ? WHERE id = ?", on, by.username(), qid);
        cr.audit.record(by.username(), on ? "queue.start" : "queue.stop", "queue", qid, null, "ok", ip);
    }

    /** Adds work items: a list of dictionaries (data), each optionally with "reference" and "priority". */
    @SuppressWarnings("unchecked")
    public Map<String, Object> addItems(User by, String qid, List<Object> items, String ip) {
        cr.require(by, "queues.work", "add work items");
        Map<String, Object> queue = db.one("SELECT id, model_id FROM queues WHERE id = ?", qid);
        if (queue == null) throw notFound("Queue");
        requireQueue(by, qid, "add", "add work items to this queue");
        if (items == null || items.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Send at least one item");
        if (items.size() > 10000) throw new OoaException("CR.TOO_LARGE", "Add at most 10,000 items at a time");
        List<Map<String, Object>> fields = modelFields((String) queue.get("model_id"));
        List<String> ids = new ArrayList<>();
        db.tx(() -> {
            int row = 0;
            for (Object o : items) {
                row++;
                if (!(o instanceof Map<?, ?> m)) throw new OoaException("CR.BAD_INPUT", "Each item is a JSON object");
                Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) m);
                Object ref = item.remove("_reference");
                Object pr = item.remove("_priority");
                if (fields != null) item = checkItem(fields, item, "Item " + row);
                String iid = id("item");
                db.update("INSERT INTO queue_items (id, queue_id, reference, data, status, priority, attempts, created, updated) VALUES (?,?,?,?,?,?,?,?,?)",
                        iid, qid, ref == null ? null : String.valueOf(ref), JsonLite.write(item), "new", pr instanceof Number n ? n.intValue() : 0, 0, now(), now());
                ids.add(iid);
            }
            return null;
        });
        cr.audit.record(by.username(), "queue.add_items", "queue", qid, Map.of("count", ids.size()), "ok", ip);
        return Map.of("added", ids.size(), "ids", ids);
    }

    public Map<String, Object> items(User by, String qid, String status, String q, int limit, int offset) {
        cr.require(by, "queues.view", "view work queues");
        requireQueue(by, qid, "view", "view this work queue");
        List<Object> p = new ArrayList<>(List.of(qid));
        String where = " WHERE queue_id = ?";
        if (status != null && !status.isBlank()) { where += " AND status = ?"; p.add(status); }
        if (q != null && !q.isBlank()) { where += " AND (LOWER(COALESCE(reference, '')) LIKE ? OR LOWER(data) LIKE ?)"; String like = "%" + q.toLowerCase(Locale.ROOT) + "%"; p.add(like); p.add(like); }
        long total = cr.count("SELECT COUNT(*) AS n FROM queue_items" + where, p.toArray());
        List<Object> page = new ArrayList<>(p);
        page.add(Math.max(1, Math.min(500, limit)));
        page.add(Math.max(0, offset));
        List<Map<String, Object>> rows = db.query("SELECT * FROM queue_items" +
                where + " ORDER BY created DESC LIMIT ? OFFSET ?", page.toArray());
        return Map.of("items", rows, "total", total);
    }

    /** Puts failed or deferred items back to new (attempts reset) or marks items deferred. */
    public void updateItems(User by, String qid, List<Object> ids, String action, String ip) {
        cr.require(by, "queues.work", "change work items");
        if (action.equals("delete")) { deleteItems(by, qid, ids, ip); return; }
        requireQueue(by, qid, "process", "change work items in this queue");
        String to = switch (action) { case "retry" -> "new"; case "defer" -> "deferred"; default -> throw new OoaException("CR.BAD_INPUT", "Use retry, defer or delete"); };
        int n = 0;
        for (Object o : ids == null ? List.of() : ids)
            n += db.update("UPDATE queue_items SET status = ?, attempts = CASE WHEN ? = 'new' THEN 0 ELSE attempts END, updated = ? WHERE id = ? AND queue_id = ? AND status IN ('failed', 'deferred', 'new', 'retry')",
                    to, to, now(), String.valueOf(o), qid);
        cr.audit.record(by.username(), "queue.items_" + action, "queue", qid, Map.of("count", n), "ok", ip);
    }

    // ----- work item models, queue members, single items, CSV -----

    static final Set<String> FIELD_TYPES = Set.of("text", "number", "boolean", "date");
    static final java.util.regex.Pattern FIELD = java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    public List<Map<String, Object>> workItemModels(User by) {
        cr.require(by, "queues.view", "view work queues");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : db.query("SELECT * FROM work_item_models ORDER BY name")) {
            Map<String, Object> r = new LinkedHashMap<>(m);
            r.put("fields", JsonLite.parse((String) m.get("fields")));
            r.put("queues", cr.count("SELECT COUNT(*) AS n FROM queues WHERE model_id = ?", m.get("id")));
            out.add(r);
        }
        return out;
    }

    /**
     * A work item model: the fields every item of a queue has, each {name, type: text|number|boolean|date, required,
     * display (shown in the item list)}. Items are checked against it when they are added or changed.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveWorkItemModel(User by, String modelId, Map<String, Object> m, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
        String name = String.valueOf(m.getOrDefault("name", "")).trim();
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Model names have 1 to 120 characters");
        if (!(m.get("fields") instanceof List<?> list) || list.isEmpty()) throw new OoaException("CR.BAD_INPUT", "A model has at least one field");
        if (list.size() > 100) throw new OoaException("CR.TOO_LARGE", "A model has at most 100 fields");
        List<Map<String, Object>> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> f)) throw new OoaException("CR.BAD_INPUT", "Each field is {name, type, required}");
            String fn = String.valueOf(f.get("name")), type = String.valueOf(f.containsKey("type") ? f.get("type") : "text");
            if (!FIELD.matcher(fn).matches()) throw new OoaException("CR.BAD_INPUT", "Field '" + fn + "': names start with a letter or _ and use letters, digits and _");
            if (!seen.add(fn.toLowerCase(Locale.ROOT))) throw new OoaException("CR.BAD_INPUT", "Field '" + fn + "' is there twice");
            if (!FIELD_TYPES.contains(type)) throw new OoaException("CR.BAD_INPUT", "Field '" + fn + "': the type is text, number, boolean or date");
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("name", fn); clean.put("type", type);
            clean.put("required", Boolean.TRUE.equals(f.get("required")));
            clean.put("display", !Boolean.FALSE.equals(f.get("display")));
            fields.add(clean);
        }
        String id = modelId == null || modelId.isBlank() ? id("model") : modelId;
        if (modelId == null || modelId.isBlank())
            db.update("INSERT INTO work_item_models (id, name, description, fields, created, created_by) VALUES (?,?,?,?,?,?)", id, name, m.get("description"), JsonLite.write(fields), now(), by.username());
        else {
            Map<String, Object> old = db.one("SELECT fields FROM work_item_models WHERE id = ?", id);
            if (old == null) throw notFound("Work item model");
            // a model in use can gain optional fields and change labels, but not lose fields, change types or add required ones
            if (cr.count("SELECT COUNT(*) AS n FROM queues q JOIN queue_items i ON i.queue_id = q.id WHERE q.model_id = ?", id) > 0) {
                Map<String, Map<String, Object>> before = new HashMap<>();
                for (Object o : (List<Object>) JsonLite.parse((String) old.get("fields"))) before.put(String.valueOf(((Map<String, Object>) o).get("name")), (Map<String, Object>) o);
                for (Map<String, Object> f : fields) {
                    Map<String, Object> b = before.remove(String.valueOf(f.get("name")));
                    if (b == null ? Boolean.TRUE.equals(f.get("required")) : !Objects.equals(b.get("type"), f.get("type")) || (!Boolean.TRUE.equals(b.get("required")) && Boolean.TRUE.equals(f.get("required"))))
                        throw new OoaException("CR.CONFLICT", "Queues with work items use this model: field '" + f.get("name") + "' cannot become required or change type");
                }
                if (!before.isEmpty()) throw new OoaException("CR.CONFLICT", "Queues with work items use this model: fields " + before.keySet() + " cannot be removed");
            }
            db.update("UPDATE work_item_models SET name = ?, description = ?, fields = ? WHERE id = ?", name, m.get("description"), JsonLite.write(fields), id);
        }
        cr.audit.record(by.username(), "queue.model_save", "work_item_model", id, Map.of("name", name, "fields", fields.size()), "ok", ip);
        return Map.of("id", id);
    }

    public void deleteWorkItemModel(User by, String modelId, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
        if (cr.count("SELECT COUNT(*) AS n FROM queues WHERE model_id = ?", modelId) > 0) throw new OoaException("CR.IN_USE", "Queues use this model", "Change those queues first.");
        if (db.update("DELETE FROM work_item_models WHERE id = ?", modelId) == 0) throw notFound("Work item model");
        cr.audit.record(by.username(), "queue.model_delete", "work_item_model", modelId, null, "ok", ip);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modelFields(String modelId) {
        if (modelId == null) return null;
        Map<String, Object> m = db.one("SELECT fields FROM work_item_models WHERE id = ?", modelId);
        return m == null ? null : (List<Map<String, Object>>) JsonLite.parse((String) m.get("fields"));
    }

    /** An item's data checked against the model: known fields only, required ones present, values of the field's type. */
    static Map<String, Object> checkItem(List<Map<String, Object>> fields, Map<String, Object> data, String where) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        for (Map<String, Object> f : fields) {
            String n = String.valueOf(f.get("name"));
            known.add(n);
            Object v = data.get(n);
            if (v == null || (v instanceof String sv && sv.isEmpty())) {
                if (Boolean.TRUE.equals(f.get("required"))) throw new OoaException("CR.BAD_ITEM", where + ": the field '" + n + "' is required");
                continue;
            }
            out.put(n, switch (String.valueOf(f.get("type"))) {
                case "number" -> {
                    if (v instanceof Number) yield v;
                    try { yield new java.math.BigDecimal(String.valueOf(v).trim()); }
                    catch (NumberFormatException e) { throw new OoaException("CR.BAD_ITEM", where + ": the field '" + n + "' must be a number, not '" + v + "'"); }
                }
                case "boolean" -> {
                    if (v instanceof Boolean) yield v;
                    String t = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                    if (Set.of("true", "yes", "1").contains(t)) yield true;
                    if (Set.of("false", "no", "0").contains(t)) yield false;
                    throw new OoaException("CR.BAD_ITEM", where + ": the field '" + n + "' must be true or false, not '" + v + "'");
                }
                case "date" -> {
                    String t = String.valueOf(v).trim();
                    try { LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t); yield t; }
                    catch (DateTimeException e) { throw new OoaException("CR.BAD_ITEM", where + ": the field '" + n + "' must be a date like 2026-10-23, not '" + v + "'"); }
                }
                default -> String.valueOf(v);
            });
        }
        for (String k : data.keySet()) if (!known.contains(k)) throw new OoaException("CR.BAD_ITEM", where + ": '" + k + "' is not a field of the work item model");
        return out;
    }

    // members: owners manage the queue, participants add and change items, consumers process them (their runs work it)
    static final Set<String> MEMBER_ROLES = Set.of("owner", "participant", "consumer");

    List<String> memberRoles(User u, String qid) {
        List<String> out = new ArrayList<>();
        List<String> mine = null;
        for (Map<String, Object> r : db.query("SELECT principal, member_role FROM queue_members WHERE queue_id = ?", qid)) {
            if (mine == null) mine = cr.teams.principalsOf(u.username(), u.role());
            if (mine.contains((String) r.get("principal")) && !out.contains(r.get("member_role"))) out.add((String) r.get("member_role"));
        }
        return out;
    }

    /**
     * Whether the user may view, add, process or manage a queue. A queue without members follows the roles alone; one
     * with members admits only them (owners everything, participants view and add, consumers view and process), except
     * people with queues.all.
     */
    boolean queueAllows(User u, String qid, String need) {
        if (cr.can(u, "queues.all")) return true;
        if (cr.count("SELECT COUNT(*) AS n FROM queue_members WHERE queue_id = ?", qid) == 0) return true;
        List<String> mine = memberRoles(u, qid);
        if (mine.contains("owner")) return true;
        return switch (need) {
            case "view" -> !mine.isEmpty();
            case "add" -> mine.contains("participant");
            case "process" -> mine.contains("consumer");
            default -> false;
        };
    }

    void requireQueue(User u, String qid, String need, String what) {
        if (db.one("SELECT id FROM queues WHERE id = ?", qid) == null) throw notFound("Queue");
        if (!queueAllows(u, qid, need)) throw new OoaException("CR.FORBIDDEN", "You do not have permission to " + what,
                "Ask an owner of the queue to add you as " + (need.equals("add") ? "a participant" : need.equals("process") ? "a consumer" : "an owner") + ".");
    }

    /** One queue with its counts, model and members. */
    public Map<String, Object> queue(User by, String qid) {
        cr.require(by, "queues.view", "view work queues");
        requireQueue(by, qid, "view", "view this work queue");
        Map<String, Object> m = new LinkedHashMap<>(db.one("SELECT * FROM queues WHERE id = ?", qid));
        m.put("counts", counts(qid));
        m.put("model", m.get("model_id") == null ? null : Map.of("id", m.get("model_id"), "fields", modelFields((String) m.get("model_id"))));
        m.put("members", db.query("SELECT principal, member_role, updated, updated_by FROM queue_members WHERE queue_id = ? ORDER BY member_role, principal", qid));
        m.put("my_roles", memberRoles(by, qid));
        return m;
    }

    /** Replaces the queue's members [{principal: user:x | role:y, role: owner | participant | consumer}]; the caller must stay an owner. */
    public void setQueueMembers(User by, String qid, List<Object> members, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
        requireQueue(by, qid, "manage", "change this queue's members");
        List<String[]> clean = new ArrayList<>();
        for (Object o : members == null ? List.of() : members) {
            if (!(o instanceof Map<?, ?> mm)) throw new OoaException("CR.BAD_INPUT", "Each member is {principal, role}");
            String principal = String.valueOf(mm.get("principal")), role = String.valueOf(mm.get("role"));
            if (!MEMBER_ROLES.contains(role)) throw new OoaException("CR.BAD_INPUT", "Member roles are owner, participant and consumer");
            cr.teams.checkPrincipal(principal);
            clean.add(new String[]{principal, role});
        }
        boolean hasOwner = clean.stream().anyMatch(c -> c[1].equals("owner"));
        if (!clean.isEmpty() && !hasOwner) throw new OoaException("CR.BAD_INPUT", "A queue with members needs at least one owner");
        List<String> mine = cr.teams.principalsOf(by.username(), by.role());
        if (!clean.isEmpty() && !cr.can(by, "queues.all") && clean.stream().noneMatch(c -> c[1].equals("owner") && mine.contains(c[0])))
            throw new OoaException("CR.BAD_INPUT", "Keep yourself (or your role) as an owner, or you could not change the queue any more");
        db.tx(() -> {
            db.update("DELETE FROM queue_members WHERE queue_id = ?", qid);
            for (String[] c : clean) if (db.one("SELECT queue_id FROM queue_members WHERE queue_id = ? AND principal = ? AND member_role = ?", qid, c[0], c[1]) == null)
                db.update("INSERT INTO queue_members (queue_id, principal, member_role, updated, updated_by) VALUES (?,?,?,?,?)", qid, c[0], c[1], now(), by.username());
            return null;
        });
        cr.audit.record(by.username(), "queue.members", "queue", qid, Map.of("members", clean.stream().map(c -> c[0] + "=" + c[1]).toList()), "ok", ip);
    }

    public Map<String, Object> item(User by, String qid, String itemId) {
        cr.require(by, "queues.view", "view work queues");
        requireQueue(by, qid, "view", "view this work queue");
        Map<String, Object> i = db.one("SELECT * FROM queue_items WHERE id = ? AND queue_id = ?", itemId, qid);
        if (i == null) throw notFound("Work item");
        Map<String, Object> m = new LinkedHashMap<>(i);
        m.put("data", JsonLite.parse((String) i.get("data")));
        if (i.get("result") != null) m.put("result", JsonLite.parse((String) i.get("result")));
        if (i.get("error") != null) m.put("error", JsonLite.parse((String) i.get("error")));
        return m;
    }

    /** Changes an item's data, reference or priority while it is not being worked on. */
    @SuppressWarnings("unchecked")
    public void updateItem(User by, String qid, String itemId, Map<String, Object> changes, String ip) {
        cr.require(by, "queues.work", "change work items");
        requireQueue(by, qid, "add", "change work items in this queue");
        Map<String, Object> i = db.one("SELECT i.status, q.model_id FROM queue_items i JOIN queues q ON q.id = i.queue_id WHERE i.id = ? AND i.queue_id = ?", itemId, qid);
        if (i == null) throw notFound("Work item");
        if ("in_progress".equals(i.get("status"))) throw new OoaException("CR.CONFLICT", "The item is being worked on", "Change it after its run ends.");
        if (changes.get("data") instanceof Map<?, ?> d) {
            Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) d);
            List<Map<String, Object>> fields = modelFields((String) i.get("model_id"));
            if (fields != null) data = checkItem(fields, data, "The item");
            db.update("UPDATE queue_items SET data = ?, updated = ? WHERE id = ?", JsonLite.write(data), now(), itemId);
        }
        if (changes.containsKey("reference")) db.update("UPDATE queue_items SET reference = ?, updated = ? WHERE id = ?", changes.get("reference") == null ? null : String.valueOf(changes.get("reference")), now(), itemId);
        if (changes.get("priority") instanceof Number n) db.update("UPDATE queue_items SET priority = ?, updated = ? WHERE id = ?", n.intValue(), now(), itemId);
        cr.audit.record(by.username(), "queue.item_update", "work_item", itemId, Map.of("queue", qid, "fields", new ArrayList<>(changes.keySet())), "ok", ip);
    }

    /** Deletes items that are not being worked on; returns how many went. */
    public int deleteItems(User by, String qid, List<Object> ids, String ip) {
        cr.require(by, "queues.work", "delete work items");
        requireQueue(by, qid, "add", "delete work items in this queue");
        int n = 0;
        for (Object o : ids == null ? List.of() : ids) n += db.update("DELETE FROM queue_items WHERE id = ? AND queue_id = ? AND status <> 'in_progress'", String.valueOf(o), qid);
        cr.audit.record(by.username(), "queue.items_delete", "queue", qid, Map.of("count", n), "ok", ip);
        return n;
    }

    /** Adds items from CSV text: the first row names the fields (reference and priority are kept apart), each other row is an item. */
    public Map<String, Object> importCsv(User by, String qid, String csv, String ip) {
        return importRows(by, qid, Csv.parse(csv == null ? "" : csv), "CSV", ip);
    }

    /**
     * Adds items from an Excel workbook (.xlsx, sent as Base64): a sheet by name (or the first one), its first row
     * naming the fields like a CSV file. Cells are read as shown in Excel (dates as yyyy-mm-dd), then checked against
     * the queue's work item model.
     */
    public Map<String, Object> importXlsx(User by, String qid, String base64, String sheet, String ip) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64 == null ? "" : base64.replaceFirst("^data:[^,]*,", "")); }
        catch (IllegalArgumentException e) { throw new OoaException("CR.BAD_INPUT", "The workbook is not valid Base64"); }
        List<List<String>> rows = new ArrayList<>();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            org.apache.poi.ss.usermodel.Sheet sh = sheet == null || sheet.isBlank() ? wb.getSheetAt(0) : wb.getSheet(sheet);
            if (sh == null) throw new OoaException("CR.BAD_INPUT", "The workbook has no sheet '" + sheet + "'");
            org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            org.apache.poi.ss.usermodel.FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            int width = 0;
            for (org.apache.poi.ss.usermodel.Row r : sh) {
                if (rows.size() > 10_001) break;
                List<String> cells = new ArrayList<>();
                int last = Math.max(r.getLastCellNum(), rows.isEmpty() ? 0 : width);
                for (int c = 0; c < last; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = r.getCell(c);
                    if (cell == null) { cells.add(""); continue; }
                    org.apache.poi.ss.usermodel.CellType type = cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA ? ev.evaluateFormulaCell(cell) : cell.getCellType();
                    if (type == org.apache.poi.ss.usermodel.CellType.NUMERIC && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell))
                        cells.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                    else if (type == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                        double d = cell.getNumericCellValue();
                        cells.add(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : java.math.BigDecimal.valueOf(d).toPlainString());
                    } else cells.add(fmt.formatCellValue(cell, ev));
                }
                if (rows.isEmpty()) width = cells.size();
                rows.add(cells);
            }
        } catch (java.io.IOException | org.apache.poi.EmptyFileException | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new OoaException("CR.BAD_INPUT", "This is not an Excel workbook (.xlsx): " + e.getMessage());
        }
        return importRows(by, qid, rows, "workbook", ip);
    }

    Map<String, Object> importRows(User by, String qid, List<List<String>> rows, String what, String ip) {
        if (rows.size() < 2) throw new OoaException("CR.BAD_INPUT", "The " + what + " needs a header row and at least one item");
        List<String> head = rows.get(0).stream().map(String::trim).toList();
        List<Object> items = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() == 1 && row.get(0).isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            for (int c = 0; c < head.size(); c++) {
                String v = c < row.size() ? row.get(c) : "";
                String h = head.get(c);
                if (h.equalsIgnoreCase("reference") || h.equals("_reference")) { if (!v.isEmpty()) item.put("_reference", v); }
                else if (h.equalsIgnoreCase("priority") || h.equals("_priority")) { if (!v.isBlank()) try { item.put("_priority", Integer.parseInt(v.trim())); } catch (NumberFormatException e) { throw new OoaException("CR.BAD_ITEM", "Row " + (r + 1) + ": priority must be a whole number"); } }
                else if (!h.isEmpty()) item.put(h, v);
            }
            items.add(item);
        }
        return addItems(by, qid, items, ip);
    }

    /** The queue's items as CSV: reference, priority, status, attempts, dates, then the data fields. */
    @SuppressWarnings("unchecked")
    public String exportCsv(User by, String qid, String status) {
        cr.require(by, "queues.view", "view work queues");
        requireQueue(by, qid, "view", "view this work queue");
        List<Object> p = new ArrayList<>(List.of(qid));
        String where = " WHERE queue_id = ?";
        if (status != null && !status.isBlank()) { where += " AND status = ?"; p.add(status); }
        List<Map<String, Object>> rows = db.query("SELECT * FROM queue_items" +
                where + " ORDER BY created LIMIT 100000", p.toArray());
        List<String> fields = new ArrayList<>();
        List<Map<String, Object>> model = modelFields((String) db.one("SELECT model_id FROM queues WHERE id = ?", qid).get("model_id"));
        if (model != null) for (Map<String, Object> f : model) fields.add(String.valueOf(f.get("name")));
        List<Map<String, Object>> datas = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> d = (Map<String, Object>) JsonLite.parse((String) r.get("data"));
            datas.add(d);
            if (model == null) for (String k : d.keySet()) if (!fields.contains(k)) fields.add(k);
        }
        StringBuilder sb = new StringBuilder();
        List<String> head = new ArrayList<>(List.of("id", "reference", "priority", "status", "attempts", "created", "finished"));
        head.addAll(fields);
        sb.append(Csv.row(head));
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            List<String> cells = new ArrayList<>();
            for (String h : List.of("id", "reference", "priority", "status", "attempts", "created", "finished")) cells.add(r.get(h) == null ? "" : String.valueOf(r.get(h)));
            for (String f : fields) { Object v = datas.get(i).get(f); cells.add(v == null ? "" : v instanceof Map || v instanceof List ? JsonLite.write(v) : String.valueOf(v)); }
            sb.append(Csv.row(cells));
        }
        return sb.toString();
    }

    /**
     * A queue's definition as a file: its settings, work item model and members, and optionally its waiting items
     * (new, retry and deferred, which become new). The runner pool is named, not linked, since pools differ between
     * Command Centers.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exportQueue(User by, String qid, boolean withItems) {
        Map<String, Object> q = queue(by, qid);
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("format", "ooa.queue"); def.put("format_version", 1); def.put("exported_at", now().toString()); def.put("exported_by", by.username());
        Map<String, Object> settings = new LinkedHashMap<>();
        for (String k : List.of("name", "description", "bot_path", "sla_minutes", "max_attempts")) settings.put(k, q.get(k));
        Map<String, Object> pool = q.get("pool_id") == null ? null : db.one("SELECT name FROM device_pools WHERE id = ?", q.get("pool_id"));
        settings.put("pool_name", pool == null ? null : pool.get("name"));
        def.put("queue", settings);
        if (q.get("model_id") != null) {
            Map<String, Object> m = db.one("SELECT name, description, fields FROM work_item_models WHERE id = ?", q.get("model_id"));
            def.put("model", Map.of("name", m.get("name"), "description", m.get("description") == null ? "" : m.get("description"), "fields", JsonLite.parse((String) m.get("fields"))));
        }
        List<Object> members = new ArrayList<>();
        for (Object o : (List<Object>) q.get("members")) { Map<String, Object> mm = (Map<String, Object>) o; members.add(Map.of("principal", mm.get("principal"), "role", mm.get("member_role"))); }
        def.put("members", members);
        if (withItems) {
            List<Object> items = new ArrayList<>();
            for (Map<String, Object> i : db.query("SELECT reference, priority, data FROM queue_items WHERE queue_id = ? AND status IN ('new', 'retry', 'deferred') ORDER BY created LIMIT 10000", qid)) {
                Map<String, Object> it = new LinkedHashMap<>((Map<String, Object>) JsonLite.parse((String) i.get("data")));
                if (i.get("reference") != null) it.put("_reference", i.get("reference"));
                if (i.get("priority") instanceof Number n && n.intValue() != 0) it.put("_priority", n.intValue());
                items.add(it);
            }
            def.put("items", items);
        }
        cr.audit.record(by.username(), "queue.export", "queue", qid, Map.of("items", withItems), "ok", null);
        return def;
    }

    /**
     * Makes a queue from an exported definition (optionally under another name). Its work item model is reused when one
     * with the same name and fields exists, else made. The agent and runner pool are linked when they exist here; members
     * whose users, roles or teams do not exist here are left out and reported.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importQueue(User by, Map<String, Object> def, String newName, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
        if (def == null || !"ooa.queue".equals(def.get("format")) || !(def.get("queue") instanceof Map<?, ?> qm)) throw new OoaException("CR.BAD_INPUT", "This is not a queue definition");
        Map<String, Object> q = new LinkedHashMap<>((Map<String, Object>) qm);
        String name = newName != null && !newName.isBlank() ? newName.trim() : String.valueOf(q.get("name"));
        if (db.one("SELECT id FROM queues WHERE name = ?", name) != null) throw new OoaException("CR.CONFLICT", "There is already a queue called " + name, "Import it under another name.");
        List<String> notes = new ArrayList<>();
        String modelId = null;
        if (def.get("model") instanceof Map<?, ?> mm) {
            Map<String, Object> model = (Map<String, Object>) mm;
            String fieldsJson = JsonLite.write(model.get("fields"));
            Map<String, Object> same = db.one("SELECT id, fields FROM work_item_models WHERE name = ?", model.get("name"));
            if (same != null && JsonLite.write(JsonLite.parse((String) same.get("fields"))).equals(fieldsJson)) modelId = (String) same.get("id");
            else {
                String mName = String.valueOf(model.get("name"));
                for (int k = 2; db.one("SELECT id FROM work_item_models WHERE name = ?", mName) != null; k++) mName = model.get("name") + " (" + k + ")";
                modelId = (String) saveWorkItemModel(by, null, Map.of("name", mName, "description", String.valueOf(model.getOrDefault("description", "")), "fields", model.get("fields")), ip).get("id");
                if (!mName.equals(model.get("name"))) notes.add("The work item model was made as " + mName);
            }
        }
        String bot = q.get("bot_path") instanceof String b && db.one("SELECT path FROM bots WHERE path = ? AND deleted_at IS NULL AND production_version IS NOT NULL", b) != null ? b : null;
        if (q.get("bot_path") != null && bot == null) notes.add("The agent " + q.get("bot_path") + " is not here (or has no live version); choose one in the queue settings");
        Map<String, Object> pool = q.get("pool_name") == null ? null : db.one("SELECT id FROM device_pools WHERE name = ?", q.get("pool_name"));
        if (q.get("pool_name") != null && pool == null) notes.add("There is no runner pool called " + q.get("pool_name") + " here");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name); body.put("description", q.get("description")); body.put("bot_path", bot); body.put("pool_id", pool == null ? null : pool.get("id"));
        body.put("sla_minutes", q.get("sla_minutes")); body.put("max_attempts", q.get("max_attempts") instanceof Number n ? n : 1); body.put("model_id", modelId);
        String qid = (String) saveQueue(by, null, body, ip).get("id");
        List<Object> members = new ArrayList<>();
        for (Object o : def.get("members") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> mm = (Map<String, Object>) o;
            try { cr.teams.checkPrincipal(String.valueOf(mm.get("principal"))); members.add(mm); }
            catch (OoaException e) { notes.add("Member " + mm.get("principal") + " is not here and was left out"); }
        }
        if (!members.isEmpty()) {
            List<String> mine = cr.teams.principalsOf(by.username(), by.role());
            boolean iOwn = members.stream().anyMatch(x -> "owner".equals(((Map<String, Object>) x).get("role")) && mine.contains(String.valueOf(((Map<String, Object>) x).get("principal"))));
            if (!iOwn && !cr.can(by, "queues.all")) { members.add(Map.of("principal", "user:" + by.username(), "role", "owner")); notes.add("You were added as an owner"); }
            setQueueMembers(by, qid, members, ip);
        }
        int items = 0;
        if (def.get("items") instanceof List<?> il && !il.isEmpty()) items = ((Number) addItems(by, qid, new ArrayList<>((List<Object>) il), ip).get("added")).intValue();
        cr.audit.record(by.username(), "queue.import", "queue", qid, Map.of("name", name, "items", items), "ok", ip);
        return Map.of("id", qid, "name", name, "items", items, "notes", notes);
    }

    // ----- toolkit versions -----

    /** Which agents use a toolkit, at which required version range, and whether the installed version satisfies it. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toolkitVersions(User by, String pid) {
        cr.require(by, "repo.view", "view toolkits");
        Map<String, Object> man = registry.allManifests().stream().filter(m -> pid.equals(m.get("id"))).findFirst().orElseThrow(() -> notFound("Toolkit " + pid));
        String installed = String.valueOf(man.get("version"));
        List<Object> agents = new ArrayList<>();
        Map<String, Integer> byRange = new TreeMap<>();
        for (Map<String, Object> b : cr.repoList(by, null)) {
            String path = (String) b.get("path");
            if (!path.endsWith(".flow.json")) continue;
            Map<String, Object> flow = (Map<String, Object>) cr.content(path, "latest").get("flow");
            Map<String, Object> req = flow.get("requires") instanceof Map<?, ?> r && r.get("packages") instanceof Map<?, ?> pk ? (Map<String, Object>) pk : Map.of();
            boolean uses = com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of())).stream()
                    .anyMatch(st -> String.valueOf(st.get("action")).startsWith(pid + "/"));
            Object range = req.get(pid);
            if (!uses && range == null) continue;
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("path", path); a.put("version", b.get("latest_version")); a.put("required", range);
            a.put("satisfied", range == null || com.oneofficeautomation.sdk.PackageRegistry.satisfies(installed, String.valueOf(range)));
            agents.add(a);
            byRange.merge(range == null ? "(any)" : String.valueOf(range), 1, Integer::sum);
        }
        return Map.of("toolkit", pid, "installed", installed, "agents", agents, "by_range", byRange);
    }

    /**
     * Sets the version range of a toolkit that agents require (for example ^2.0.0 after an upgrade) by checking in a new
     * version of each agent that uses it (latest version, not made live). Agents checked out by others or that fail
     * validation are reported and left as they are.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateToolkitRange(User by, String pid, String range, List<Object> only, String ip) {
        cr.require(by, "repo.checkin", "update agents");
        Map<String, Object> v = toolkitVersions(by, pid);
        if (range == null || range.isBlank()) throw new OoaException("CR.BAD_INPUT", "Give the version range, e.g. ^2.0.0");
        if (!com.oneofficeautomation.sdk.PackageRegistry.satisfies(String.valueOf(v.get("installed")), range))
            throw new OoaException("CR.BAD_INPUT", "The installed version " + v.get("installed") + " does not satisfy " + range, "Agents would not run; choose a range the installed version satisfies.");
        Set<String> filter = only == null ? null : new HashSet<>(csv(only));
        List<Object> results = new ArrayList<>();
        for (Object o : (List<Object>) v.get("agents")) {
            Map<String, Object> a = (Map<String, Object>) o;
            String path = (String) a.get("path");
            if (filter != null && !filter.contains(path)) continue;
            Map<String, Object> r = new LinkedHashMap<>(Map.of("path", path));
            if (range.equals(a.get("required"))) { r.put("status", "unchanged"); results.add(r); continue; }
            try {
                Map<String, Object> flow = new LinkedHashMap<>((Map<String, Object>) cr.content(path, "latest").get("flow"));
                Map<String, Object> req = new LinkedHashMap<>(flow.get("requires") instanceof Map<?, ?> rq ? (Map<String, Object>) rq : Map.of());
                Map<String, Object> pk = new LinkedHashMap<>(req.get("packages") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of());
                pk.put(pid, range); req.put("packages", pk); flow.put("requires", req);
                Map<String, Object> s = cr.storeVersion(by, path, flow, "Toolkit " + pid + " required as " + range, false, false, ip);
                r.put("status", "updated"); r.put("version", s.get("version"));
            } catch (OoaException e) { r.put("status", "failed"); r.put("error", e.toMap()); }
            results.add(r);
        }
        cr.audit.record(by.username(), "toolkit.update_agents", "toolkit", pid, Map.of("range", range, "agents", results.size()), "ok", ip);
        return Map.of("toolkit", pid, "range", range, "results", results);
    }

    // ----- run history retention -----

    private volatile Instant lastPurge = Instant.EPOCH;

    /** Called by the server clock: applies the retention settings at most once an hour. */
    void purgeIfDue() {
        if (Duration.between(lastPurge, now()).toMinutes() < 60) return;
        lastPurge = now();
        int runDays = ((Number) setting("run_retention_days")).intValue(), logDays = ((Number) setting("run_log_retention_days")).intValue();
        if (runDays > 0 || logDays > 0) purge(runDays, logDays, "system", null);
        // AI call records and finished work-queue items (D2)
        int aiDays = intSetting("ai_call_retention_days"), itemDays = intSetting("work_item_retention_days");
        if (aiDays > 0) { int n = db.update("DELETE FROM ai_calls WHERE ts < ?", now().minus(Duration.ofDays(aiDays))); if (n > 0) cr.audit.record("system", "ai.calls_purged", "ai", null, Map.of("removed", n, "days", aiDays), "ok", null); }
        if (itemDays > 0) { int n = db.update("DELETE FROM queue_items WHERE status IN ('success', 'failed') AND finished < ?", now().minus(Duration.ofDays(itemDays))); if (n > 0) cr.audit.record("system", "queue.items_purged", "queue", null, Map.of("removed", n, "days", itemDays), "ok", null); }
        int auditDays = intSetting("audit_retention_days");
        if (auditDays > 0) cr.audit.purgeOlderThan(auditDays);
    }

    /**
     * Removes the step logs of runs that finished more than logDays ago, and whole runs (with their logs) that finished
     * more than runDays ago; 0 keeps them. Queued and running runs are never touched; work items keep their results.
     */
    public Map<String, Object> purge(int runDays, int logDays, String by, String ip) {
        long events = 0, runs = 0;
        if (logDays > 0) events += db.update("DELETE FROM run_events WHERE run_id IN (SELECT id FROM runs WHERE finished < ? AND status IN ('success', 'failed', 'stopped'))", now().minus(Duration.ofDays(logDays)));
        if (runDays > 0) {
            Instant cut = now().minus(Duration.ofDays(runDays));
            events += db.update("DELETE FROM run_events WHERE run_id IN (SELECT id FROM runs WHERE finished < ? AND status IN ('success', 'failed', 'stopped'))", cut);
            runs = db.update("DELETE FROM runs WHERE finished < ? AND status IN ('success', 'failed', 'stopped')", cut);
        }
        cr.audit.record(by, "runs.purge", "runs", null, Map.of("run_days", runDays, "log_days", logDays, "runs", runs, "log_lines", events), "ok", ip);
        return Map.of("runs_removed", runs, "log_lines_removed", events);
    }

    public Map<String, Object> purgeNow(User by, Map<String, Object> b, String ip) {
        cr.require(by, "settings.manage", "remove run history");
        int runDays = b.get("run_days") instanceof Number n ? n.intValue() : 0, logDays = b.get("log_days") instanceof Number n2 ? n2.intValue() : 0;
        if (runDays <= 0 && logDays <= 0) throw new OoaException("CR.BAD_INPUT", "Give run_days and/or log_days (more than 0)");
        return purge(runDays, logDays, by.username(), ip);
    }

    /** What this Command Center is and holds: the edition from the license (or the evaluation) and what it has in use. */
    public Map<String, Object> license(User by) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", "ONEOFFICEAUTOMATION One Command Center");
        Map<String, Object> lic = cr.licensing.status(by);
        String state = String.valueOf(lic.get("state"));
        boolean licensed = List.of("active", "expiring", "grace", "expired").contains(state);
        m.put("edition", licensed ? lic.getOrDefault("edition", "Enterprise") + " (licensed to " + lic.getOrDefault("customer", "?") + ")"
            : state.startsWith("evaluation") ? "Evaluation (30 days, until " + lic.get("evaluation_ends") + ")" : "Unlicensed (no license installed)");
        m.put("limits", licensed || state.startsWith("evaluation") ? "seats per license type; see Administration > Licenses" : "none until a license is installed");
        m.put("license", lic);
        m.put("users", cr.count("SELECT COUNT(*) AS n FROM users WHERE enabled = TRUE"));
        m.put("runners", cr.count("SELECT COUNT(*) AS n FROM devices WHERE enabled = TRUE"));
        m.put("agents", cr.count("SELECT COUNT(*) AS n FROM bots WHERE deleted_at IS NULL"));
        m.put("toolkits", registry.allManifests().size());
        m.put("toolkits_switched_off", cr.count("SELECT COUNT(*) AS n FROM toolkit_settings WHERE enabled = FALSE"));
        return m;
    }

    /** Small RFC 4180 CSV reader and writer (quotes, doubled quotes, line breaks inside quotes). */
    static final class Csv {
        static List<List<String>> parse(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < t.length() && t.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else if (c == '"') quoted = false;
                    else cell.append(c);
                } else if (c == '"' && cell.isEmpty()) quoted = true;
                else if (c == ',') { row.add(cell.toString()); cell.setLength(0); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString()); cell.setLength(0);
                    rows.add(row); row = new ArrayList<>();
                } else cell.append(c);
            }
            if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
            return rows;
        }

        static String row(List<String> cells) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) sb.append(',');
                String v = cells.get(i);
                // a leading = + - @ would be read as a formula by spreadsheet programs
                if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("^-?\\d+(\\.\\d+)?$")) v = "'" + v;
                if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = "\"" + v.replace("\"", "\"\"") + "\"";
                sb.append(v);
            }
            return sb.append("\r\n").toString();
        }
    }

    /**
     * Gives each idle runner of a processing queue's pool the next item (priority, then age). The agent receives the item
     * as an input named work_item (and work_item_id) when it declares them. Called by the server clock.
     */
    public synchronized int dispatch() {
        int started = 0;
        for (Map<String, Object> q : db.query("SELECT * FROM queues WHERE processing = TRUE")) {
            for (int guard = 0; guard < 50; guard++) {
                Map<String, Object> next = db.one("SELECT id, data FROM queue_items WHERE queue_id = ? AND status IN ('new', 'retry') ORDER BY priority DESC, created LIMIT 1", q.get("id"));
                if (next == null) break;
                String device = pickIdle((String) q.get("pool_id"));
                if (device == null) break;
                if (db.update("UPDATE queue_items SET status = 'in_progress', attempts = attempts + 1, device_id = ?, updated = ? WHERE id = ? AND status IN ('new', 'retry')",
                        device, now(), next.get("id")) != 1) continue;
                try {
                    Map<String, Object> inputs = new LinkedHashMap<>();
                    Map<String, Object> declared = declaredInputs((String) q.get("bot_path"));
                    if (declared.containsKey("work_item")) inputs.put("work_item", JsonLite.parse((String) next.get("data")));
                    if (declared.containsKey("work_item_id")) inputs.put("work_item_id", next.get("id"));
                    Map<String, Object> creator = db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", q.get("run_as"));
                    // the run carries its work item from the start: a quick agent may finish before this method goes on
                    Map<String, Object> run = cr.startRun((String) q.get("run_as"), creator == null ? "viewer" : (String) creator.get("role"), (String) q.get("bot_path"), device,
                            null, inputs, "queue:" + q.get("id"), null, 0, null, null, null, (String) next.get("id"));
                    db.update("UPDATE queue_items SET run_id = ? WHERE id = ?", run.get("run_id"), next.get("id"));
                    started++;
                } catch (OoaException e) {
                    db.update("UPDATE queue_items SET status = 'failed', error = ?, updated = ? WHERE id = ?", JsonLite.write(e.toMap()), now(), next.get("id"));
                    cr.audit.record("scheduler", "queue.dispatch", "queue", (String) q.get("id"), e.getMessage(), "failed", null);
                    break;
                }
            }
        }
        return started;
    }

    /** An online runner of the pool with no queued or running work. */
    private String pickIdle(String poolId) {
        String d = pickDevice(poolId);
        if (d == null) return null;
        return cr.count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status IN ('queued', 'running')", d) == 0 ? d : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> declaredInputs(String botPath) {
        Map<String, Object> flow = (Map<String, Object>) cr.content(botPath, "production").get("flow");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object v : (List<Object>) flow.getOrDefault("variables", List.of()))
            if (v instanceof Map<?, ?> m && java.util.Arrays.asList("input", "in_out").contains(m.get("direction"))) out.put(String.valueOf(m.get("name")), m);
        return out;
    }

    /** After a run of a queue item ends: success, or retry while attempts remain, or failed. */
    void itemFinished(String runId, String status, Object error, Object outputs) {
        Map<String, Object> r = db.one("SELECT queue_item_id FROM runs WHERE id = ?", runId);
        if (r == null || r.get("queue_item_id") == null) return;
        Map<String, Object> item = db.one("SELECT i.*, q.max_attempts FROM queue_items i JOIN queues q ON q.id = i.queue_id WHERE i.id = ?", r.get("queue_item_id"));
        if (item == null) return;
        String next = "success".equals(status) ? "success"
                : ((Number) item.get("attempts")).intValue() < ((Number) item.get("max_attempts")).intValue() && !"stopped".equals(status) ? "retry" : "failed";
        // the agent asked a person to check what it read (A1): the item waits in "review" until validated
        if (next.equals("success") && outputs instanceof Map<?, ?> o && o.get("validation") instanceof Map<?, ?> v && v.get("fields") instanceof Map<?, ?>) next = "review";
        db.update("UPDATE queue_items SET status = ?, error = ?, result = ?, updated = ?, finished = ? WHERE id = ?", next,
                error == null ? null : JsonLite.write(error), outputs == null ? null : JsonLite.write(outputs), now(),
                next.equals("retry") || next.equals("review") ? null : now(), item.get("id"));
        if (next.equals("review")) cr.audit.record("system", "queue.item_review", "work_item", (String) item.get("id"), Map.of("queue", item.get("queue_id")), "ok", null);
    }

    /**
     * The validation queue (A1): a person checks the fields an agent read with too little confidence. Approve puts the
     * item back in its queue with the checked values (data.validated_fields, validated, validated_by), so the agent's
     * next run uses them; reject fails it with the reason.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validateItem(User by, String qid, String itemId, Map<String, Object> b, String ip) {
        cr.require(by, "queues.work", "validate work items");
        requireQueue(by, qid, "process", "validate work items in this queue");
        Map<String, Object> i = db.one("SELECT * FROM queue_items WHERE id = ? AND queue_id = ?", itemId, qid);
        if (i == null) throw notFound("Work item");
        if (!"review".equals(i.get("status"))) throw new OoaException("CR.CONFLICT", "The item is not waiting for validation");
        Map<String, Object> result = i.get("result") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) i.get("result"));
        Map<String, Object> asked = result.get("validation") instanceof Map<?, ?> v && v.get("fields") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        if (Boolean.TRUE.equals(b.get("reject"))) {
            String reason = b.get("reason") == null ? "" : String.valueOf(b.get("reason")).trim();
            if (reason.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Say why the document is rejected");
            db.update("UPDATE queue_items SET status = 'failed', error = ?, updated = ?, finished = ? WHERE id = ?",
                JsonLite.write(Map.of("code", "VALIDATION.REJECTED", "message", reason, "suggestedFix", "Rejected by " + by.username())), now(), now(), itemId);
            cr.audit.record(by.username(), "queue.item_reject", "work_item", itemId, Map.of("queue", qid, "reason", reason), "ok", ip);
            return Map.of("status", "failed");
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        Map<String, Object> given = b.get("fields") instanceof Map<?, ?> g ? (Map<String, Object>) g : Map.of();
        for (String k : asked.keySet()) checked.put(k, given.containsKey(k) ? given.get(k) : asked.get(k));   // only the fields the agent asked about
        List<String> changed = new ArrayList<>();
        for (String k : asked.keySet()) if (!Objects.equals(String.valueOf(asked.get(k)), String.valueOf(checked.get(k)))) changed.add(k);
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) JsonLite.parse((String) i.get("data")));
        data.put("validated_fields", checked);
        data.put("validated", true);
        data.put("validated_by", by.username());
        db.update("UPDATE queue_items SET status = 'new', data = ?, updated = ?, finished = NULL WHERE id = ?", JsonLite.write(data), now(), itemId);
        cr.audit.record(by.username(), "queue.item_validate", "work_item", itemId, Map.of("queue", qid, "changed", changed), "ok", ip);
        return Map.of("status", "new", "changed", changed);
    }

    // ===================== model connections =====================

    static final Set<String> PROVIDERS = Set.of("openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker",
            "aws_bedrock", "google_vertex");

    public List<Map<String, Object>> modelConnections(User by) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : db.query("SELECT name, provider, model, base_url, key_ref, description, allowed_roles, model_type, monthly_tokens, calls_per_minute, calls_per_run, pii_analyzer_url, content_check, updated, updated_by FROM model_connections ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            m.put("allowed_roles", csv(c.get("allowed_roles")));
            if (m.get("model_type") == null) m.put("model_type", "standard");
            for (String k : List.of("monthly_tokens", "calls_per_minute", "calls_per_run")) if (m.get(k) == null) m.put(k, 0);
            m.put("tokens_this_month", tokensThisMonth((String) c.get("name")));
            out.add(m);
        }
        return out;
    }

    public void saveModelConnection(User by, Map<String, Object> c, String ip) {
        cr.require(by, "connections.manage", "manage model connections");
        String name = String.valueOf(c.getOrDefault("name", "")).trim();
        if (!name.matches("^[A-Za-z0-9_.-]{1,60}$")) throw new OoaException("CR.BAD_INPUT", "Connection names use letters, digits, . _ - (up to 60)");
        String provider = String.valueOf(c.get("provider"));
        if (!PROVIDERS.contains(provider)) throw new OoaException("CR.BAD_INPUT", "Provider must be one of " + PROVIDERS);
        String keyRef = (String) c.get("key_ref");
        if (keyRef != null && !keyRef.isBlank() && keyRef.split("/").length != 3) throw new OoaException("CR.BAD_INPUT", "The key is a vault reference: vault folder/credential/attribute");
        String type = c.get("model_type") == null ? "standard" : String.valueOf(c.get("model_type"));
        if (!type.equals("standard") && !type.equals("fine_tuned")) throw new OoaException("CR.BAD_INPUT", "The model type is standard or fine_tuned");
        // roles that may use the connection; none listed means every role
        List<String> roles = new ArrayList<>();
        if (c.get("allowed_roles") instanceof List<?> l) for (Object o : l) {
            String r = String.valueOf(o).trim();
            if (r.isEmpty()) continue;
            if (!cr.roleExists(r)) throw new OoaException("CR.BAD_INPUT", "There is no role named '" + r + "'");
            roles.add(r);
        }
        String rolesCsv = roles.isEmpty() ? null : String.join(",", roles);
        long monthly = limit(c.get("monthly_tokens"), 1_000_000_000_000L, "Tokens a month");
        int perMinute = (int) limit(c.get("calls_per_minute"), 100_000, "Calls a minute"), perRun = (int) limit(c.get("calls_per_run"), 1_000_000, "Calls a run");
        String analyzer = c.get("pii_analyzer_url") == null ? null : blankToNull(String.valueOf(c.get("pii_analyzer_url")).trim());
        if (analyzer != null && !analyzer.matches("(?i)(https://|http://(localhost|127\\.0\\.0\\.1)(:|/|$))\\S*"))
            throw new OoaException("CR.BAD_INPUT", "The PII analyzer address starts with https:// (http:// only on this computer)", "For example https://presidio.internal.example");
        String check = c.get("content_check") == null || String.valueOf(c.get("content_check")).isBlank() ? "none" : String.valueOf(c.get("content_check"));
        if (!check.equals("none") && !check.equals("moderation")) throw new OoaException("CR.BAD_INPUT", "The content check is none or moderation");
        if (check.equals("moderation") && !List.of("openai", "azure_openai", "openai_compatible").contains(provider))
            throw new OoaException("CR.BAD_INPUT", "The content check uses an OpenAI-style moderation endpoint, which " + provider + " does not have");
        if (db.update("UPDATE model_connections SET provider = ?, model = ?, base_url = ?, key_ref = ?, description = ?, allowed_roles = ?, model_type = ?, monthly_tokens = ?, calls_per_minute = ?, calls_per_run = ?, pii_analyzer_url = ?, content_check = ?, updated = ?, updated_by = ? WHERE name = ?",
                provider, c.get("model"), c.get("base_url"), blankToNull(keyRef), c.get("description"), rolesCsv, type, monthly, perMinute, perRun, analyzer, check, now(), by.username(), name) == 0)
            db.update("INSERT INTO model_connections (name, provider, model, base_url, key_ref, description, allowed_roles, model_type, monthly_tokens, calls_per_minute, calls_per_run, pii_analyzer_url, content_check, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    name, provider, c.get("model"), c.get("base_url"), blankToNull(keyRef), c.get("description"), rolesCsv, type, monthly, perMinute, perRun, analyzer, check, now(), by.username());
        cr.audit.record(by.username(), "connection.save", "connection", name, Map.of("provider", provider, "allowed_roles", roles), "ok", ip);
    }

    /**
     * Tries a connection (saved or as typed in the dialog) with one very small request. The key is read from the vault
     * for this request only.
     */
    public Map<String, Object> testModelConnection(User by, Map<String, Object> c, String ip) {
        cr.require(by, "connections.manage", "manage model connections");
        String provider = String.valueOf(c.get("provider"));
        if (!PROVIDERS.contains(provider)) throw new OoaException("CR.BAD_INPUT", "Provider must be one of " + PROVIDERS);
        String keyRef = c.get("key_ref") instanceof String k && !k.isBlank() ? k.trim() : null;
        String key = keyRef == null ? null : cr.credentialForUser(by, keyRef, "connection_test");
        Map<String, Object> r = com.oneofficeautomation.packages.ModelProbe.test(provider, (String) c.get("model"), (String) c.get("base_url"), key);
        cr.audit.record(by.username(), "connection.test", "connection", String.valueOf(c.getOrDefault("name", "")), Map.of("provider", provider, "ok", r.get("ok")), Boolean.TRUE.equals(r.get("ok")) ? "ok" : "failed", ip);
        return r;
    }

    public void deleteModelConnection(User by, String name, String ip) {
        cr.require(by, "connections.manage", "manage model connections");
        if (db.update("DELETE FROM model_connections WHERE name = ?", name) == 0) throw notFound("Model connection");
        cr.audit.record(by.username(), "connection.delete", "connection", name, null, "ok", ip);
    }

    /** A budget or limit from the dialog: a whole number from 0 (none) to max. */
    private static long limit(Object v, long max, String what) {
        if (v == null || String.valueOf(v).isBlank()) return 0;
        long n;
        try { n = v instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", what + " needs a whole number"); }
        if (n < 0 || n > max) throw new OoaException("CR.BAD_INPUT", what + " must be from 0 (no limit) to " + max);
        return n;
    }

    /** Input and output tokens used through a connection since the start of this month (UTC). */
    long tokensThisMonth(String connection) {
        Instant month = java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Map<String, Object> r = db.one("SELECT SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)) AS n FROM ai_calls WHERE connection = ? AND ts >= ?", connection, month);
        return r == null || r.get("n") == null ? 0 : ((Number) r.get("n")).longValue();
    }

    /**
     * The connections handed to runners with each job: keys stay vault references ({"$credential": ref}). Only the
     * connections the run's role may use are sent. A connection whose monthly token budget is used up goes as blocked
     * (its AI steps fail with AI.BUDGET_REACHED); its call limits go along for the runner to keep.
     */
    Map<String, Object> connectionsForJob(List<String> runRoles) {
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> c : db.query("SELECT * FROM model_connections ORDER BY name")) {
            List<String> roles = csv(c.get("allowed_roles"));
            if (!roles.isEmpty() && !runRoles.contains("admin") && runRoles.stream().noneMatch(roles::contains)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.get("name")); m.put("provider", c.get("provider"));
            if (c.get("model") != null) m.put("model", c.get("model"));
            if (c.get("base_url") != null) m.put("base_url", c.get("base_url"));
            if (c.get("key_ref") != null) m.put("api_key", Map.of("$credential", c.get("key_ref")));
            long monthly = c.get("monthly_tokens") instanceof Number n ? n.longValue() : 0;
            if (monthly > 0 && tokensThisMonth((String) c.get("name")) >= monthly)
                m.put("blocked", "The connection's budget of " + monthly + " tokens this month is used up");
            if (c.get("calls_per_minute") instanceof Number n && n.intValue() > 0) m.put("calls_per_minute", n.intValue());
            if (c.get("calls_per_run") instanceof Number n && n.intValue() > 0) m.put("calls_per_run", n.intValue());
            if (c.get("pii_analyzer_url") instanceof String pa && !pa.isBlank()) m.put("pii_analyzer_url", pa);
            if ("moderation".equals(c.get("content_check"))) m.put("content_check", "moderation");
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default", list.isEmpty() ? "" : ((Map<String, Object>) list.get(0)).get("name"));
        out.put("connections", list);
        return out;
    }

    // ===================== holiday calendars =====================

    public List<Map<String, Object>> calendars(User by) {
        cr.require(by, "schedules.view", "view schedules");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : db.query("SELECT * FROM holiday_calendars ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            m.put("dates", csv(c.get("dates")));
            out.add(m);
        }
        return out;
    }

    public void saveCalendar(User by, String name, Object dates, String ip) {
        cr.require(by, "schedules.manage", "manage holiday calendars");
        if (name == null || !name.matches("^[A-Za-z0-9 _.-]{1,80}$")) throw new OoaException("CR.BAD_INPUT", "Calendar names use letters, digits, spaces and . _ -");
        List<String> ds = new ArrayList<>(new TreeSet<>(csv(dates)));
        for (String d : ds) try { LocalDate.parse(d); } catch (RuntimeException e) { throw new OoaException("CR.BAD_INPUT", "Not a date: " + d, "Use YYYY-MM-DD."); }
        if (db.update("UPDATE holiday_calendars SET dates = ?, updated = ?, updated_by = ? WHERE name = ?", String.join(",", ds), now(), by.username(), name) == 0)
            db.update("INSERT INTO holiday_calendars (name, dates, updated, updated_by) VALUES (?,?,?,?)", name, String.join(",", ds), now(), by.username());
        cr.audit.record(by.username(), "calendar.save", "calendar", name, Map.of("dates", ds.size()), "ok", ip);
    }

    public void deleteCalendar(User by, String name, String ip) {
        cr.require(by, "schedules.manage", "manage holiday calendars");
        if (db.update("DELETE FROM holiday_calendars WHERE name = ?", name) == 0) throw notFound("Calendar");
        cr.audit.record(by.username(), "calendar.delete", "calendar", name, null, "ok", ip);
    }

    /** True when the schedule's holiday calendar contains this date (in the schedule's time zone). */
    boolean isHoliday(Map<String, Object> schedule, Instant when) {
        Object cal = schedule.get("holiday_calendar");
        if (cal == null) return false;
        Map<String, Object> c = db.one("SELECT dates FROM holiday_calendars WHERE name = ?", cal);
        if (c == null) return false;
        String day = when.atZone(ZoneId.of(String.valueOf(schedule.getOrDefault("time_zone", "UTC")))).toLocalDate().toString();
        return csv(c.get("dates")).contains(day);
    }

    // ===================== toolkits =====================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> toolkits(User by) {
        cr.require(by, "repo.view", "view toolkits");
        Map<String, Boolean> enabled = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT id, enabled FROM toolkit_settings")) enabled.put((String) r.get("id"), Boolean.TRUE.equals(r.get("enabled")));
        Map<String, Integer> used = new HashMap<>();
        for (Map<String, Object> b : db.query("SELECT path, production_version, latest_version FROM bots")) {
            Map<String, Object> flow;
            try { flow = (Map<String, Object>) cr.content((String) b.get("path"), "latest").get("flow"); } catch (OoaException e) { continue; }
            Set<String> pkgs = new HashSet<>();
            for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of())))
                pkgs.add(String.valueOf(s.get("action")).split("/")[0]);
            for (String p : pkgs) used.merge(p, 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : registry.allManifests()) {
            String pid = (String) m.get("id");
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", pid); t.put("name", m.get("name")); t.put("version", m.get("version")); t.put("description", m.get("description"));
            t.put("publisher", m.get("publisher") instanceof Map<?, ?> pub ? pub.get("name") : m.get("publisher"));
            t.put("actions", ((List<Object>) m.getOrDefault("actions", List.of())).size());
            t.put("conditions", ((List<Object>) m.getOrDefault("conditions", List.of())).size());
            t.put("enabled", enabled.getOrDefault(pid, true));
            t.put("used_by", used.getOrDefault(pid, 0));
            out.add(t);
        }
        out.sort(Comparator.comparing(t -> String.valueOf(t.get("name"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<String> toolkitUsers(User by, String pid) {
        cr.require(by, "repo.view", "view toolkits");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> b : db.query("SELECT path FROM bots ORDER BY path")) {
            Map<String, Object> flow;
            try { flow = (Map<String, Object>) cr.content((String) b.get("path"), "latest").get("flow"); } catch (OoaException e) { continue; }
            for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of())))
                if (String.valueOf(s.get("action")).startsWith(pid + "/")) { out.add((String) b.get("path")); break; }
        }
        return out;
    }

    public void setToolkitEnabled(User by, String pid, boolean on, String ip) {
        cr.require(by, "toolkits.manage", "manage toolkits");
        if ("core".equals(pid)) throw new OoaException("CR.BAD_INPUT", "The core toolkit cannot be switched off");
        if (registry.allManifests().stream().noneMatch(m -> pid.equals(m.get("id")))) throw notFound("Toolkit");
        if (db.update("UPDATE toolkit_settings SET enabled = ?, updated = ?, updated_by = ? WHERE id = ?", on, now(), by.username(), pid) == 0)
            db.update("INSERT INTO toolkit_settings (id, enabled, updated, updated_by) VALUES (?,?,?,?)", pid, on, now(), by.username());
        cr.audit.record(by.username(), on ? "toolkit.enable" : "toolkit.disable", "toolkit", pid, null, "ok", ip);
    }

    /** Refuses an agent that uses a switched-off toolkit (at check-in and before runs). */
    @SuppressWarnings("unchecked")
    void checkToolkits(Map<String, Object> flow) {
        Set<String> off = new HashSet<>();
        for (Map<String, Object> r : db.query("SELECT id FROM toolkit_settings WHERE enabled = FALSE")) off.add((String) r.get("id"));
        if (off.isEmpty()) return;
        for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of()))) {
            String pid = String.valueOf(s.get("action")).split("/")[0];
            if (off.contains(pid)) throw new OoaException("CR.TOOLKIT_DISABLED", "The toolkit '" + pid + "' is switched off in this Command Center (step " + s.get("id") + ")",
                    "Ask an administrator to switch it on under Toolkits, or remove the step.");
        }
    }
}
