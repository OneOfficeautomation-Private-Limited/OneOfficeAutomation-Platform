package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.runner.FlowSchemaValidator;
import com.oneofficeautomation.runner.FlowValidator;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.PackageRegistry;

import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Control Room services: users and sessions, public repository with check-in/check-out and versions, devices and
 * agents, runs, schedules, triggers, credential lockers and global values. Every change is written to the audit log.
 */
public final class ControlRoom {
    /** A signed-in person. mfaEnabled: two-step sign-in is on; sso: the account signs in through the identity provider. */
    public record User(String id, String username, String role, String displayName, boolean mustChange, boolean mfaEnabled, boolean sso) {
        public User(String id, String username, String role, String displayName, boolean mustChange) { this(id, username, role, displayName, mustChange, false, false); }
    }

    public final Db db;
    public final Audit audit;
    public final Vault vault;
    public final Platform platform;
    final ExternalVaults externalVaults = new ExternalVaults();
    public final Workflows workflows;
    /** Repository folders and folder permissions. */
    final Folders folders;
    /** Export, import and promotion of agents between Command Centers. */
    public final Lifecycle lifecycle;
    /** Teams of people, named team:x in access rules. */
    public final Teams teams;
    /** Vault folder members, credential owners, people's own values and the master key. */
    public final VaultAdmin vaultAdmin;
    /** Run analytics for Operations. */
    public final Analytics analytics;
    /** Code analysis against the policy (on demand and at check-in). */
    public final CodeAnalysis codeAnalysis;
    /** Agents behind their own URL and token. */
    public final ApiTasks apiTasks;
    /** Files in the repository that are not agents. */
    public final RepoFiles repoFiles;
    /** Prompt Skills kept in the Command Center. */
    public final PromptSkills promptSkills;
    /** Server-side triggers (email, Outlook, ServiceNow, universal poller); the server clock calls tick(). */
    public final TriggerPollers triggerPollers = new TriggerPollers(this);
    private final FlowValidator validator;
    private final PackageRegistry registry;
    static final Duration ONLINE = Duration.ofSeconds(60);
    static final Pattern PATH = Pattern.compile("^[A-Za-z0-9 _.()-]+(/[A-Za-z0-9 _.()-]+){0,8}\\.(flow|process|form)\\.json$");
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,80}$");

    final Path dataDir;
    final SyslogForwarder syslog;
    public final Licensing licensing;
    public final Mfa mfa;
    public final Cluster cluster;
    public final ProductionApproval approvals;
    public final RunnerUpdates runnerUpdates;
    public final GitSync gitSync;
    final Saml saml;
    public final Backups backups;
    final Mailer mailer;

    /**
     * Readiness for load balancers and monitoring: the database answers and holds the schema this release needs, the
     * vault key encrypts and decrypts, the data folder has room, and how many runners are online. Only ok/warn/fail per
     * check is returned, never paths, names or secrets.
     */
    public Map<String, Object> readiness() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean ok = true;
        try {
            int v = db.schemaVersion();
            checks.put("database", "ok");
            checks.put("schema", v == Db.LATEST ? "ok" : "fail");
            ok &= v == Db.LATEST;
        } catch (RuntimeException e) { checks.put("database", "fail"); ok = false; }
        try { checks.put("vault", "ready".equals(vault.decrypt(vault.encrypt("ready", "health"), "health")) ? "ok" : "fail"); }
        catch (RuntimeException e) { checks.put("vault", "fail"); }
        ok &= "ok".equals(checks.get("vault"));
        long free = dataDir.toFile().getUsableSpace();
        checks.put("disk", free < 200L * 1024 * 1024 ? "fail" : free < 2L * 1024 * 1024 * 1024 ? "warn" : "ok");
        ok &= !"fail".equals(checks.get("disk"));
        try { checks.put("runners_online", count("SELECT COUNT(*) AS n FROM devices WHERE enabled = TRUE AND last_seen > ?", now().minus(ONLINE))); }
        catch (RuntimeException e) { checks.put("runners_online", null); }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", ok ? "ready" : "not_ready");
        m.put("checks", checks);
        return m;
    }

    public ControlRoom(Db db, Path dataDir, PackageRegistry registry, FlowSchemaValidator schema) {
        this.dataDir = dataDir;
        this.db = db;
        this.audit = new Audit(db);
        this.vault = new Vault(dataDir);
        this.validator = new FlowValidator(registry, schema);
        this.registry = registry;
        db.migrate();
        this.platform = new Platform(this, registry);
        this.syslog = new SyslogForwarder(platform);
        this.licensing = new Licensing(this);
        this.cluster = new Cluster(db);
        this.mfa = new Mfa(this);
        this.approvals = new ProductionApproval(this);
        this.runnerUpdates = new RunnerUpdates(this);
        this.gitSync = new GitSync(this, dataDir);
        this.saml = new Saml(this);
        this.backups = new Backups(this);
        this.mailer = new Mailer(this);
        audit.onRecord(syslog::forward);
        audit.protect(this::auditKey, dataDir);
        this.workflows = new Workflows(this);
        this.teams = new Teams(this);
        this.folders = new Folders(this);
        this.lifecycle = new Lifecycle(this);
        this.vaultAdmin = new VaultAdmin(this);
        this.analytics = new Analytics(this);
        this.codeAnalysis = new CodeAnalysis(this);
        this.apiTasks = new ApiTasks(this);
        this.repoFiles = new RepoFiles(this, dataDir);
        this.promptSkills = new PromptSkills(this);
        loadRoles();
        loadUserRoles();
    }

    static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    static OoaException denied(String what) { return new OoaException("CR.FORBIDDEN", "You do not have permission to " + what, "Ask a Command Center administrator for the right role."); }
    static OoaException notFound(String what) { return new OoaException("CR.NOT_FOUND", what + " not found"); }

    public void require(User u, String permission, String what) { if (!can(u, permission)) throw denied(what); }

    /** Whether any of the person's roles (their main role and any further roles) grants the permission. */
    public boolean can(User u, String permission) {
        for (String r : rolesOf(u.username(), u.role())) if (allowed(r, permission)) return true;
        return false;
    }

    /** Further roles of people beyond their main role (user name -> roles), loaded from the database. */
    final Map<String, List<String>> extraRoles = new java.util.concurrent.ConcurrentHashMap<>();

    /** The main role first, then the further roles. */
    public List<String> rolesOf(String username, String role) {
        List<String> out = new ArrayList<>();
        if (role != null) out.add(role);
        for (String r : extraRoles.getOrDefault(username, List.of())) if (!out.contains(r)) out.add(r);
        return out;
    }

    void loadUserRoles() {
        Map<String, List<String>> m = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT username, role FROM user_roles ORDER BY role")) m.computeIfAbsent((String) r.get("username"), k -> new ArrayList<>()).add((String) r.get("role"));
        extraRoles.clear();
        extraRoles.putAll(m);
    }

    /** Custom roles (name -> permissions), loaded from the database; built-in roles live in Rbac. */
    final Map<String, Set<String>> customRoles = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean allowed(String role, String permission) {
        Set<String> custom = customRoles.get(role);
        return custom != null ? custom.contains(permission) : Rbac.allowed(role, permission);
    }

    /** Every permission any of the person's roles grants. */
    public List<String> permissionsOf(User u) {
        Set<String> all = new TreeSet<>();
        for (String r : rolesOf(u.username(), u.role())) all.addAll(permissionsOf(r));
        return new ArrayList<>(all);
    }

    public List<String> permissionsOf(String role) {
        Set<String> custom = customRoles.get(role);
        return custom != null ? new ArrayList<>(new TreeSet<>(custom)) : Rbac.permissions(role);
    }

    public boolean roleExists(String role) { return Rbac.ROLES.contains(role) || customRoles.containsKey(role); }

    void loadRoles() {
        customRoles.clear();
        for (Map<String, Object> r : db.query("SELECT name, permissions FROM roles"))
            customRoles.put((String) r.get("name"), new HashSet<>(csv(r.get("permissions"))));
    }

    // ===================== users and sessions =====================

    /** Creates the first administrator on an empty database; returns its one-time password, or null if users exist. */
    public String bootstrap() {
        if (db.one("SELECT id FROM users LIMIT 1") != null) return null;
        String pw = Security.token(12);
        db.update("INSERT INTO users (id, username, display_name, password_hash, role, enabled, must_change, failed, created) VALUES (?,?,?,?,?,?,?,?,?)",
                id("usr"), "admin", "Administrator", Security.hashPassword(pw.toCharArray()), "admin", true, true, 0, now());
        audit.record("system", "user.bootstrap", "user", "admin", null, "ok", null);
        return pw;
    }

    static User user(Map<String, Object> r) {
        return new User((String) r.get("id"), (String) r.get("username"), (String) r.get("role"), (String) r.get("display_name"), Boolean.TRUE.equals(r.get("must_change")),
                Boolean.TRUE.equals(r.get("mfa_enabled")), r.get("sso_subject") != null);
    }

    public Map<String, Object> login(String username, String password, String ip) { return login(username, password, null, ip); }

    /**
     * Signs in with a password. With two-step sign-in on, the answer is {mfa_required, mfa_token} and the session comes
     * from {@link #loginMfa} with a code; a code sent along here (Studio, scripts) completes the sign-in in one step.
     */
    public Map<String, Object> login(String username, String password, String code, String ip) {
        Map<String, Object> r = db.one("SELECT * FROM users WHERE username = ?", username == null ? "" : username.trim());
        OoaException bad = new OoaException("CR.LOGIN_FAILED", "Wrong user name or password", null);
        if (r == null) { Security.hashPassword("timing".toCharArray()); audit.record(String.valueOf(username), "auth.login", "user", String.valueOf(username), null, "failed", ip); throw bad; }
        if (!Boolean.TRUE.equals(r.get("enabled"))) { audit.record(username, "auth.login", "user", username, "disabled", "failed", ip); throw new OoaException("CR.USER_DISABLED", "This account is disabled", "Contact your administrator."); }
        Object lu = r.get("locked_until");
        if (lu != null && Instant.parse(lu.toString()).isAfter(now()))
            throw new OoaException("CR.LOCKED_OUT", "Too many failed sign-ins; try again after " + lu, "Wait 15 minutes or ask an administrator to reset your password.");
        if (!Security.verifyPassword(password == null ? new char[0] : password.toCharArray(), (String) r.get("password_hash"))) {
            countFailedSignIn(r, ip);
            throw bad;
        }
        int maxDays = platform.intSetting("password_max_days");
        if (maxDays > 0 && r.get("sso_subject") == null && !Boolean.TRUE.equals(r.get("must_change"))) {
            Object changed = r.get("password_changed") != null ? r.get("password_changed") : r.get("created");
            if (changed != null && Instant.parse(changed.toString()).isBefore(now().minus(Duration.ofDays(maxDays)))) {
                db.update("UPDATE users SET must_change = TRUE WHERE id = ?", r.get("id"));
                r = db.one("SELECT * FROM users WHERE id = ?", r.get("id"));
                audit.record(username, "user.password_expired", "user", username, Map.of("days", maxDays), "ok", ip);
            }
        }
        String challenge = mfa.challenge(r);
        if (challenge != null) {
            if (code != null && !code.isBlank()) { r = mfa.complete(challenge, code, ip); audit.record(username, "auth.login", "user", username, Map.of("mfa", true), "ok", ip); return newSession(r, ip); }
            audit.record(username, "auth.password", "user", username, Map.of("next", "code"), "ok", ip);
            return Map.of("mfa_required", true, "mfa_token", challenge);
        }
        audit.record(username, "auth.login", "user", username, null, "ok", ip);
        return newSession(r, ip);
    }

    /** The second step: the code for a sign-in that passed the password. */
    public Map<String, Object> loginMfa(String mfaToken, String code, String ip) {
        Map<String, Object> r = mfa.complete(mfaToken, code, ip);
        audit.record((String) r.get("username"), "auth.login", "user", (String) r.get("username"), Map.of("mfa", true), "ok", ip);
        return newSession(r, ip);
    }

    /** A wrong password or too many wrong codes: every fifth failure locks the account for 15 minutes. */
    void countFailedSignIn(Map<String, Object> r, String ip) {
        int failed = ((Number) db.one("SELECT failed FROM users WHERE id = ?", r.get("id")).get("failed")).intValue() + 1;
        db.update("UPDATE users SET failed = ?, locked_until = ? WHERE id = ?", failed % 5 == 0 ? 0 : failed, failed % 5 == 0 ? now().plus(Duration.ofMinutes(15)) : null, r.get("id"));
        audit.record((String) r.get("username"), "auth.login", "user", (String) r.get("username"), failed % 5 == 0 ? "account locked for 15 minutes" : null, "failed", ip);
    }

    private Map<String, Object> newSession(Map<String, Object> r, String ip) {
        licensing.checkSignIn(user(r));   // after the license's grace period only administrators sign in
        String token = Security.token(32);
        Instant expires = now().plus(platform.sessionLength());
        db.update("UPDATE users SET failed = 0, locked_until = NULL, last_login = ? WHERE id = ?", now(), r.get("id"));
        db.update("DELETE FROM sessions WHERE expires < ?", now());
        db.update("INSERT INTO sessions (token_hash, user_id, created, expires, ip) VALUES (?,?,?,?,?)", Security.sha256(token), r.get("id"), now(), expires, ip);
        // at most max_sessions_per_user sign-ins at once: the oldest ones end
        int keep = Math.max(1, platform.intSetting("max_sessions_per_user"));
        List<Map<String, Object>> open = db.query("SELECT token_hash FROM sessions WHERE user_id = ? ORDER BY created DESC", r.get("id"));
        for (int i = keep; i < open.size(); i++) db.update("DELETE FROM sessions WHERE token_hash = ?", open.get(i).get("token_hash"));
        return Map.of("token", token, "user", me(user(r)), "expires", expires.toString());
    }

    /**
     * Exchanges an API key for a session token (8 hours, extended while used), so systems that sign in once and then
     * send a token can use the key. The key itself keeps working as a Bearer token.
     */
    public Map<String, Object> tokenFromApiKey(String apiKey, String ip) {
        User u = apiKey == null || !apiKey.startsWith("ooak_") ? null : platform.apiKeyUser(apiKey);
        if (u == null) { audit.record("api key", "auth.token", "user", null, null, "failed", ip); throw new OoaException("CR.LOGIN_FAILED", "Unknown, expired or revoked API key", null); }
        Map<String, Object> r = db.one("SELECT * FROM users WHERE username = ?", u.username());
        audit.record(u.username(), "auth.token", "user", u.username(), Map.of("via", "api key"), "ok", ip);
        return newSession(r, ip);
    }

    /** A new session token for the current one, which stops working. */
    public Map<String, Object> refreshToken(String token, User u, String ip) {
        if (token == null || token.startsWith("ooak_")) throw new OoaException("CR.BAD_INPUT", "Refresh a session token (an API key does not expire this way)");
        Map<String, Object> r = db.one("SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?", Security.sha256(token));
        if (r == null) throw new OoaException("CR.UNAUTHENTICATED", "Please sign in", null);
        db.update("DELETE FROM sessions WHERE token_hash = ?", Security.sha256(token));
        audit.record(u.username(), "auth.refresh", "user", u.username(), null, "ok", ip);
        return newSession(r, ip);
    }

    /** Whether a token is valid now, for whom and until when, without extending it. */
    public Map<String, Object> verifyToken(String token) {
        if (token != null && token.startsWith("ooak_")) {
            User u = platform.apiKeyUser(token);
            return u == null ? Map.of("valid", false) : Map.of("valid", true, "username", u.username(), "kind", "api_key");
        }
        Map<String, Object> r = token == null || token.isBlank() ? null
                : db.one("SELECT u.username, u.enabled, s.expires FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?", Security.sha256(token));
        if (r == null || !Boolean.TRUE.equals(r.get("enabled")) || Instant.parse(r.get("expires").toString()).isBefore(now())) return Map.of("valid", false);
        return Map.of("valid", true, "username", r.get("username"), "kind", "session", "expires", r.get("expires"));
    }

    /**
     * Signs in a person the identity provider vouched for. Accounts are linked by the provider's subject; on the first
     * sign-in an existing account with the same (verified) e-mail or user name is linked, or a new one is created with the
     * default role. Without a default role only existing accounts may sign in.
     */
    Map<String, Object> ssoLogin(Map<String, Object> claims, String defaultRole, String ip) {
        String subject = claims.get("iss") + "|" + claims.get("sub");
        String email = claims.get("email") instanceof String e && !Boolean.FALSE.equals(claims.get("email_verified")) ? e.trim().toLowerCase(Locale.ROOT) : null;
        String preferred = claims.get("preferred_username") instanceof String pu ? pu.trim().toLowerCase(Locale.ROOT) : null;
        Map<String, Object> r = db.one("SELECT * FROM users WHERE sso_subject = ?", subject);
        if (r == null) {
            for (String candidate : new String[]{email, preferred}) {
                if (candidate == null || r != null) continue;
                r = db.one("SELECT * FROM users WHERE LOWER(username) = ? AND sso_subject IS NULL", candidate);
            }
            if (r == null) {
                if (defaultRole.isEmpty() || !roleExists(defaultRole)) {
                    audit.record(String.valueOf(email != null ? email : preferred), "auth.sso", "user", null, "no account", "failed", ip);
                    throw new OoaException("SSO.NO_ACCOUNT", "There is no Command Center account for " + (email != null ? email : preferred), "Ask an administrator to create your account.");
                }
                String username = (email != null ? email : preferred != null ? preferred : String.valueOf(claims.get("sub"))).replaceAll("[^A-Za-z0-9_.@-]", "_");
                if (username.length() < 3) username = "sso_" + username;
                if (username.length() > 80) username = username.substring(0, 80);
                String display = claims.get("name") instanceof String n && !n.isBlank() ? n : username;
                db.update("INSERT INTO users (id, username, display_name, password_hash, role, enabled, must_change, failed, created) VALUES (?,?,?,?,?,?,?,?,?)",
                        id("usr"), username, display, Security.hashPassword(Security.token(24).toCharArray()), defaultRole, true, false, 0, now());
                audit.record(username, "user.create", "user", username, Map.of("role", defaultRole, "via", "sso"), "ok", ip);
                r = db.one("SELECT * FROM users WHERE username = ?", username);
            }
            db.update("UPDATE users SET sso_subject = ? WHERE id = ?", subject, r.get("id"));
            r = db.one("SELECT * FROM users WHERE id = ?", r.get("id"));
        }
        if (!Boolean.TRUE.equals(r.get("enabled"))) { audit.record((String) r.get("username"), "auth.sso", "user", (String) r.get("username"), "disabled", "failed", ip); throw new OoaException("CR.USER_DISABLED", "This account is disabled", "Contact your administrator."); }
        // A person signing in through the identity provider never needs the local first-time password change.
        db.update("UPDATE users SET must_change = FALSE WHERE id = ?", r.get("id"));
        r = db.one("SELECT * FROM users WHERE id = ?", r.get("id"));
        audit.record((String) r.get("username"), "auth.login", "user", (String) r.get("username"), Map.of("via", "sso"), "ok", ip);
        return newSession(r, ip);
    }

    /** A credential the Command Center itself needs (for example the SSO client secret), from a vault folder or its external vault. */
    String systemCredential(String ref) {
        String[] p = ref.split("/");
        if (p.length != 3) throw new OoaException("CR.BAD_INPUT", "Use a vault reference: vault folder/credential/attribute");
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        if (l == null) throw new OoaException("VAULT.NOT_FOUND", "Vault folder " + p[0] + " not found");
        if (l.get("external") != null) return readExternal(l, p[1], p[2]);
        Map<String, Object> c = db.one("SELECT ciphertext FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", p[0], p[1], p[2]);
        if (c == null) throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found");
        audit.record("system", "credential.read", "credential", ref, Map.of("for", "command center"), "ok", null);
        return vault.decrypt((String) c.get("ciphertext"), ref);
    }

    /**
     * Local recovery for a forgotten password, run from the console of the Command Center machine (`ooa reset-admin`):
     * gives the user a new one-time password, re-enables and unlocks the account, ends its sessions and records it in
     * the One Audit Trail. Only someone who can open the data folder can run it.
     */
    public String recoverUser(String username) {
        Map<String, Object> r = db.one("SELECT id, role FROM users WHERE username = ?", username);
        if (r == null) throw new OoaException("CR.NOT_FOUND", "There is no user called " + username);
        String temp = Security.token(12);
        db.update("UPDATE users SET password_hash = ?, must_change = TRUE, enabled = TRUE, failed = 0, locked_until = NULL WHERE id = ?",
                Security.hashPassword(temp.toCharArray()), r.get("id"));
        mfa.clear((String) r.get("id"));   // a lost authenticator is a common reason for recovery; it is set up again
        db.update("DELETE FROM sessions WHERE user_id = ?", r.get("id"));
        audit.record("system (local console)", "user.recover", "user", username, Map.of("role", r.get("role")), "ok", null);
        return temp;
    }

    /** User names with their roles, for the recovery command. */
    public List<Map<String, Object>> userNames() { return db.query("SELECT username, role, enabled FROM users ORDER BY username"); }

    public static Map<String, Object> publicUser(User u) {
        return Map.of("id", u.id(), "username", u.username(), "role", u.role(), "display_name", u.displayName() == null ? u.username() : u.displayName(),
                "must_change", u.mustChange(), "permissions", Rbac.permissions(u.role()), "mfa_enabled", u.mfaEnabled());
    }

    /** The signed-in user with permissions from custom roles too. */
    public Map<String, Object> me(User u) {
        Map<String, Object> m = new LinkedHashMap<>(publicUser(u));
        m.put("permissions", permissionsOf(u));
        m.put("roles", rolesOf(u.username(), u.role()));
        if (mfa.setupNeeded(u)) m.put("mfa_setup_required", true);
        Object note = platform.setting("announcement");
        if (note instanceof String n && !n.isBlank()) m.put("announcement", n);
        Map<String, Object> lic = licensing.status(u);
        if (lic.get("banner") != null) m.put("license_banner", lic.get("banner"));
        return m;
    }

    /** Resolves a session token (sliding expiry); null when missing, expired or the user is disabled. */
    public User session(String token) {
        if (token == null || token.isBlank()) return null;
        Map<String, Object> r = db.one("SELECT u.*, s.expires AS s_expires FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?", Security.sha256(token));
        if (r == null || !Boolean.TRUE.equals(r.get("enabled")) || Instant.parse(r.get("s_expires").toString()).isBefore(now())) return null;
        db.update("UPDATE sessions SET expires = ? WHERE token_hash = ?", now().plus(platform.sessionLength()), Security.sha256(token));
        User u = user(r);
        licensing.checkSignIn(u);
        return u;
    }

    public void logout(String token, User u, String ip) {
        db.update("DELETE FROM sessions WHERE token_hash = ?", Security.sha256(token));
        audit.record(u.username(), "auth.logout", "user", u.username(), null, "ok", ip);
    }

    public void changePassword(User u, String current, String next, String token, String ip) {
        Map<String, Object> r = db.one("SELECT password_hash, password_history FROM users WHERE id = ?", u.id());
        if (!Security.verifyPassword(current == null ? new char[0] : current.toCharArray(), (String) r.get("password_hash"))) {
            audit.record(u.username(), "user.change_password", "user", u.username(), null, "failed", ip);
            throw new OoaException("CR.LOGIN_FAILED", "The current password is wrong");
        }
        String problem = Security.passwordProblem(next, u.username(), platform.intSetting("password_min_length"));
        if (problem != null) throw new OoaException("CR.WEAK_PASSWORD", problem);
        if (current.equals(next)) throw new OoaException("CR.WEAK_PASSWORD", "Choose a different password from the current one.");
        // password history: none of the last N passwords again
        int keep = platform.intSetting("password_history");
        List<String> history = new ArrayList<>(r.get("password_history") == null || ((String) r.get("password_history")).isBlank() ? List.of() : List.of(((String) r.get("password_history")).split(",")));
        if (keep > 0) for (String old : history.subList(0, Math.min(keep, history.size())))
            if (Security.verifyPassword(next.toCharArray(), old)) throw new OoaException("CR.WEAK_PASSWORD", "Choose a password you have not used for your last " + keep + " passwords.");
        history.add(0, (String) r.get("password_hash"));
        String kept = keep > 0 ? String.join(",", history.subList(0, Math.min(keep, history.size()))) : null;
        db.tx(() -> {
            db.update("UPDATE users SET password_hash = ?, must_change = FALSE, password_history = ?, password_changed = ? WHERE id = ?", Security.hashPassword(next.toCharArray()), kept, now(), u.id());
            db.update("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?", u.id(), Security.sha256(token));
            return null;
        });
        audit.record(u.username(), "user.change_password", "user", u.username(), null, "ok", ip);
    }

    public List<Map<String, Object>> users(User by) {
        require(by, "users.manage", "manage users");
        return db.query("SELECT id, username, display_name, first_name, last_name, description, email, role, enabled, must_change, last_login, created, locked_until, mfa_enabled FROM users ORDER BY username");
    }

    /** One user with their teams and default runner (by id or user name). */
    public Map<String, Object> userDetail(User by, String idOrName) {
        require(by, "users.manage", "manage users");
        Map<String, Object> u = db.one("SELECT id, username, display_name, first_name, last_name, description, email, role, enabled, must_change, last_login, created, locked_until, mfa_enabled FROM users WHERE id = ? OR username = ?", idOrName, idOrName);
        if (u == null) throw notFound("User");
        Map<String, Object> m = new LinkedHashMap<>(u);
        m.put("roles", rolesOf((String) u.get("username"), (String) u.get("role")));
        m.put("further_roles", extraRoles.getOrDefault((String) u.get("username"), List.of()));
        m.put("teams", teams.teamsOf((String) u.get("username")));
        Map<String, Object> d = db.one("SELECT device_id FROM user_devices WHERE username = ?", u.get("username"));
        m.put("default_device", d == null ? null : d.get("device_id"));
        return m;
    }

    /**
     * Deletes a user: their sign-ins, API keys, default runner, team places, personal vault values and every rule that
     * names them go. Refused while they own schedules, triggers, API tasks, vault folders or running queues, so nothing
     * stops working unseen. The audit trail keeps their name.
     */
    public void deleteUser(User by, String idOrName, String ip) {
        require(by, "users.manage", "manage users");
        Map<String, Object> u = db.one("SELECT id, username FROM users WHERE id = ? OR username = ?", idOrName, idOrName);
        if (u == null) throw notFound("User");
        String name = (String) u.get("username");
        if (name.equals(by.username())) throw new OoaException("CR.BAD_INPUT", "You cannot delete yourself");
        List<String> owned = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT name FROM schedules WHERE created_by = ? OR run_as = ?", name, name)) owned.add("schedule " + r.get("name"));
        for (Map<String, Object> r : db.query("SELECT name FROM triggers WHERE created_by = ? OR run_as = ?", name, name)) owned.add("trigger " + r.get("name"));
        for (Map<String, Object> r : db.query("SELECT name FROM api_tasks WHERE created_by = ?", name)) owned.add("API task " + r.get("name"));
        for (Map<String, Object> r : db.query("SELECT name FROM queues WHERE processing = TRUE AND run_as = ?", name)) owned.add("work queue " + r.get("name"));
        for (Map<String, Object> l : db.query("SELECT name, owners FROM lockers")) if (csv(l.get("owners")).equals(List.of(name))) owned.add("vault folder " + l.get("name") + " (only owner)");
        if (!owned.isEmpty()) throw new OoaException("CR.IN_USE", name + " still owns " + String.join(", ", owned), "Hand those to someone else first, or disable the user instead.", false, Map.of("owns", owned));
        String p = "user:" + name;
        db.tx(() -> {
            db.update("DELETE FROM sessions WHERE user_id = ?", u.get("id"));
            db.update("DELETE FROM api_keys WHERE user_id = ?", u.get("id"));
            db.update("DELETE FROM user_devices WHERE username = ?", name);
            db.update("DELETE FROM team_members WHERE username = ?", name);
            db.update("DELETE FROM credential_user_values WHERE username = ?", name);
            db.update("DELETE FROM user_global_values WHERE username = ?", name);
            db.update("DELETE FROM user_roles WHERE username = ?", name);
            db.update("DELETE FROM folder_permissions WHERE principal = ?", p);
            db.update("DELETE FROM queue_members WHERE principal = ?", p);
            for (Map<String, Object> l : db.query("SELECT name, owners, managers, participants, consumers FROM lockers")) {
                List<String> o = new ArrayList<>(csv(l.get("owners"))), m = new ArrayList<>(csv(l.get("managers"))), pa = new ArrayList<>(csv(l.get("participants"))), c = new ArrayList<>(csv(l.get("consumers")));
                if (o.remove(name) | m.remove(p) | pa.remove(p) | c.remove(p))
                    db.update("UPDATE lockers SET owners = ?, managers = ?, participants = ?, consumers = ? WHERE name = ?", String.join(",", o), String.join(",", m), String.join(",", pa), String.join(",", c), l.get("name"));
            }
            db.update("UPDATE credential_meta SET owner = NULL WHERE owner = ?", name);
            db.update("DELETE FROM users WHERE id = ?", u.get("id"));
            return null;
        });
        loadUserRoles();
        audit.record(by.username(), "user.delete", "user", name, null, "ok", ip);
    }

    /** Users filtered by text, role and enabled, with paging and the total. */
    public Map<String, Object> usersPage(User by, Map<String, String> q) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> u : users(by)) { Map<String, Object> m = new LinkedHashMap<>(u); m.put("roles", rolesOf((String) u.get("username"), (String) u.get("role"))); all.add(m); }
        String text = q.getOrDefault("q", "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> f = all.stream().filter(u -> (text.isEmpty() || (u.get("username") + " " + u.get("display_name") + " " + u.get("first_name") + " " + u.get("last_name")).toLowerCase(Locale.ROOT).contains(text))
                && (q.get("role") == null || q.get("role").isBlank() || ((List<?>) u.get("roles")).contains(q.get("role")))
                && (q.get("enabled") == null || q.get("enabled").isBlank() || String.valueOf(u.get("enabled")).equals(q.get("enabled")))).toList();
        return page("users", f, q);
    }

    static Map<String, Object> page(String key, List<Map<String, Object>> rows, Map<String, String> q) {
        int offset = Math.max(0, intOr(q.get("offset"), 0)), limit = Math.max(1, Math.min(1000, intOr(q.get("limit"), 1000)));
        return Map.of(key, rows.subList(Math.min(offset, rows.size()), Math.min(rows.size(), offset + limit)), "total", rows.size());
    }

    public Map<String, Object> createUser(User by, String username, String display, String role, String ip) {
        require(by, "users.manage", "manage users");
        if (username == null || !username.matches("^[A-Za-z0-9_.@-]{3,80}$")) throw new OoaException("CR.BAD_INPUT", "User names use 3-80 letters, digits, . _ @ -");
        if (!roleExists(role)) throw new OoaException("CR.BAD_INPUT", "Unknown role '" + role + "'", "Use a built-in role or create the role first.");
        String temp = Security.token(12);
        db.update("INSERT INTO users (id, username, display_name, password_hash, role, enabled, must_change, failed, created) VALUES (?,?,?,?,?,?,?,?,?)",
                id("usr"), username, display == null || display.isBlank() ? username : display, Security.hashPassword(temp.toCharArray()), role, true, true, 0, now());
        audit.record(by.username(), "user.create", "user", username, Map.of("role", role), "ok", ip);
        return Map.of("username", username, "temporary_password", temp);
    }

    private long enabledAdmins() { return ((Number) db.one("SELECT COUNT(*) AS n FROM users WHERE role = 'admin' AND enabled = TRUE").get("n")).longValue(); }

    public void updateUser(User by, String userId, Map<String, Object> changes, String ip) {
        require(by, "users.manage", "manage users");
        Map<String, Object> r = db.one("SELECT * FROM users WHERE id = ?", userId);
        if (r == null) throw notFound("User");
        boolean self = userId.equals(by.id());
        db.tx(() -> {
            if (changes.containsKey("role")) {
                String role = String.valueOf(changes.get("role"));
                if (!roleExists(role)) throw new OoaException("CR.BAD_INPUT", "Unknown role '" + role + "'", "Use a built-in role or create the role first.");
                if (self && !role.equals("admin")) throw new OoaException("CR.BAD_INPUT", "You cannot remove your own administrator role");
                if ("admin".equals(r.get("role")) && !role.equals("admin") && enabledAdmins() <= 1) throw new OoaException("CR.BAD_INPUT", "At least one enabled administrator is required");
                db.update("UPDATE users SET role = ? WHERE id = ?", role, userId);
            }
            if (changes.containsKey("enabled")) {
                boolean en = Boolean.TRUE.equals(changes.get("enabled"));
                if (self && !en) throw new OoaException("CR.BAD_INPUT", "You cannot disable your own account");
                if (!en && "admin".equals(r.get("role")) && enabledAdmins() <= 1) throw new OoaException("CR.BAD_INPUT", "At least one enabled administrator is required");
                db.update("UPDATE users SET enabled = ? WHERE id = ?", en, userId);
                if (!en) db.update("DELETE FROM sessions WHERE user_id = ?", userId);
            }
            if (changes.get("display_name") instanceof String d) db.update("UPDATE users SET display_name = ? WHERE id = ?", d, userId);
            for (String f : List.of("first_name", "last_name", "description")) {
                if (!changes.containsKey(f)) continue;
                String v = changes.get(f) == null ? null : String.valueOf(changes.get(f)).strip();
                if (v != null && v.length() > (f.equals("description") ? 500 : 100)) throw new OoaException("CR.BAD_INPUT", f.replace('_', ' ') + " is too long");
                switch (f) {
                    case "first_name" -> db.update("UPDATE users SET first_name = ? WHERE id = ?", v, userId);
                    case "last_name" -> db.update("UPDATE users SET last_name = ? WHERE id = ?", v, userId);
                    default -> db.update("UPDATE users SET description = ? WHERE id = ?", v, userId);
                }
            }
            if (changes.containsKey("further_roles")) {
                // roles beyond the main one; administrator stays a main role, so admin counts stay exact
                List<String> extra = new ArrayList<>();
                for (String x : csv(changes.get("further_roles"))) {
                    if (!roleExists(x)) throw new OoaException("CR.BAD_INPUT", "Unknown role '" + x + "'", "Use a built-in role or create the role first.");
                    if (x.equals("admin")) throw new OoaException("CR.BAD_INPUT", "Administrator can only be a main role");
                    if (!extra.contains(x)) extra.add(x);
                }
                db.update("DELETE FROM user_roles WHERE username = ?", r.get("username"));
                for (String x : extra) db.update("INSERT INTO user_roles (username, role) VALUES (?,?)", r.get("username"), x);
            }
            return null;
        });
        loadUserRoles();
        audit.record(by.username(), "user.update", "user", (String) r.get("username"), changes, "ok", ip);
    }

    public Map<String, Object> resetPassword(User by, String userId, String ip) {
        require(by, "users.manage", "manage users");
        Map<String, Object> r = db.one("SELECT username FROM users WHERE id = ?", userId);
        if (r == null) throw notFound("User");
        String temp = Security.token(12);
        db.update("UPDATE users SET password_hash = ?, must_change = TRUE, failed = 0, locked_until = NULL WHERE id = ?", Security.hashPassword(temp.toCharArray()), userId);
        db.update("DELETE FROM sessions WHERE user_id = ?", userId);
        audit.record(by.username(), "user.reset_password", "user", (String) r.get("username"), null, "ok", ip);
        return Map.of("temporary_password", temp);
    }

    // ===================== repository =====================

    public List<Map<String, Object>> repoList(User by, String q) { return repoList(by, q, null, false); }

    /**
     * Agents the caller may see (folder permissions), optionally in one folder and below, with the caller's access and
     * the version labels. deleted lists deleted agents instead, for recovery.
     */
    public List<Map<String, Object>> repoList(User by, String q, String folder, boolean deleted) {
        require(by, "repo.view", "view the agent repository");
        if (deleted) require(by, "repo.delete", "see deleted agents");
        String like = "%" + (q == null ? "" : q.toLowerCase(Locale.ROOT)) + "%";
        String in = folder == null || folder.isBlank() ? null : Folders.normalize(folder) + "/";
        Map<String, Map<String, String>> perms = folders.entries();
        List<String> mine = teams.principalsOf(by.username(), by.role());
        Map<String, List<Object>> labels = new HashMap<>();
        for (Map<String, Object> l : db.query("SELECT path, label, version FROM bot_labels ORDER BY label"))
            labels.computeIfAbsent((String) l.get("path"), k -> new ArrayList<>()).add(Map.of("label", l.get("label"), "version", l.get("version")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : db.query("SELECT path, bot_id, name, description, latest_version, production_version, locked_by, locked_at, updated, updated_by, minutes_saved, " +
                "deleted_at, deleted_by FROM bots WHERE (LOWER(path) LIKE ? OR LOWER(COALESCE(description, '')) LIKE ? OR LOWER(bot_id) LIKE ?) AND deleted_at IS " + (deleted ? "NOT NULL" : "NULL") +
                " ORDER BY path", like, like, like)) {
            String path = (String) b.get("path");
            if (in != null && !path.startsWith(in)) continue;
            String access = folders.accessToFolder(mine, by.role(), Folders.folderOf(path), perms);
            if (access.equals("none")) continue;
            Map<String, Object> m = new LinkedHashMap<>(b);
            if (!deleted) { m.remove("deleted_at"); m.remove("deleted_by"); }
            m.put("folder", Folders.folderOf(path));
            m.put("access", access);
            m.put("labels", labels.getOrDefault(path, List.of()));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> versions(User by, String path) {
        require(by, "repo.view", "view the agent repository");
        folders.require(by, path, "view", "view agents");
        Map<String, List<String>> byVersion = new HashMap<>();
        for (Map<String, Object> l : db.query("SELECT label, version FROM bot_labels WHERE path = ?", path))
            byVersion.computeIfAbsent(String.valueOf(l.get("version")), k -> new ArrayList<>()).add((String) l.get("label"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : db.query("SELECT version, sha256, comment, author, created FROM bot_versions WHERE path = ? ORDER BY version DESC", path)) {
            Map<String, Object> m = new LinkedHashMap<>(v);
            m.put("labels", byVersion.getOrDefault(String.valueOf(v.get("version")), List.of()));
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> content(String path, Object version) {
        Map<String, Object> b = db.one("SELECT latest_version, production_version, deleted_at FROM bots WHERE path = ?", path);
        if (b == null) throw notFound("Agent " + path);
        if (b.get("deleted_at") != null) throw new OoaException("CR.DELETED", "Agent " + path + " was deleted", "Recover it in the agent repository (Deleted agents) to use it again.");
        Object v = version == null || "latest".equals(version) ? b.get("latest_version") : "production".equals(version) ? b.get("production_version") : version;
        if (v instanceof String label && !label.matches("^\\d+$")) {
            // a version label (for example UAT), set in the agent repository
            Map<String, Object> l = db.one("SELECT version FROM bot_labels WHERE path = ? AND label = ?", path, label);
            if (l == null) throw new OoaException("CR.NOT_FOUND", "Agent " + path + " has no version labelled '" + label + "'");
            v = l.get("version");
        }
        if (v == null) throw new OoaException("CR.NO_PRODUCTION", "Agent " + path + " has no live production version", "Set a live production version in the agent repository.");
        Map<String, Object> row = db.one("SELECT content, version, sha256 FROM bot_versions WHERE path = ? AND version = ?", path, Integer.parseInt(String.valueOf(v)));
        if (row == null) throw notFound("Version " + v + " of " + path);
        // the checksum taken at check-in must still match: a version changed in the database is never sent to a runner or editor
        if (row.get("sha256") != null && !Security.sha256((String) row.get("content")).equals(row.get("sha256")))
            throw new OoaException("CR.AGENT_TAMPERED", "Version " + v + " of " + path + " was changed after check-in (checksum mismatch)", "Check the audit trail, then check the agent in again from Studio.");
        return Map.of("version", row.get("version"), "flow", JsonLite.parse((String) row.get("content")));
    }

    public Map<String, Object> getBot(User by, String path, Object version) {
        require(by, "repo.view", "view the agent repository");
        folders.require(by, path, "view", "view agents");
        return content(path, version);
    }

    public Map<String, Object> checkout(User by, String path, String ip) {
        require(by, "repo.checkin", "check out agents");
        folders.require(by, path, "edit", "check out agents");
        return db.tx(() -> {
            Map<String, Object> b = db.one("SELECT locked_by, locked_at FROM bots WHERE path = ?", path);
            if (b == null) throw notFound("Agent " + path);
            if (b.get("locked_by") != null && !by.username().equals(b.get("locked_by")))
                throw new OoaException("CR.LOCKED", "Checked out by " + b.get("locked_by") + " since " + b.get("locked_at"), "Ask them to check it in, or an administrator to undo the check-out.");
            db.update("UPDATE bots SET locked_by = ?, locked_at = ? WHERE path = ?", by.username(), now(), path);
            audit.record(by.username(), "repo.checkout", "bot", path, null, "ok", ip);
            return content(path, "latest");
        });
    }

    public void undoCheckout(User by, String path, String ip) {
        require(by, "repo.checkin", "check out agents");
        folders.require(by, path, "edit", "undo check-outs");
        Map<String, Object> b = db.one("SELECT locked_by FROM bots WHERE path = ?", path);
        if (b == null) throw notFound("Agent " + path);
        if (b.get("locked_by") == null) return;
        if (!by.username().equals(b.get("locked_by")) && !can(by, "repo.production"))
            throw new OoaException("CR.LOCKED", "Only " + b.get("locked_by") + " or an administrator can undo this check-out");
        db.update("UPDATE bots SET locked_by = NULL, locked_at = NULL WHERE path = ?", path);
        audit.record(by.username(), "repo.undo_checkout", "bot", path, Map.of("was_locked_by", b.get("locked_by")), "ok", ip);
    }

    /** Validates and stores a new version. Existing bots must be checked out by the same user. */
    public Map<String, Object> checkin(User by, String path, Map<String, Object> flow, String comment, boolean makeProduction, String ip) {
        licensing.requireDeveloper(by);
        return storeVersion(by, path, flow, comment, makeProduction, true, ip);
    }

    /** Validates a flow for the path as check-in does (toolkits, schema, workflow or form rules); returns the problems. */
    List<Map<String, Object>> problemsOf(String path, Map<String, Object> flow) {
        boolean workflow = Workflows.isWorkflow(path), form = Workflows.isForm(path);
        if (!workflow && !form) platform.checkToolkits(flow);
        return workflow ? Workflows.validate(flow) : form ? Workflows.validateForm(flow) : validator.validate(flow);
    }

    /**
     * Stores a new version. With checkedOut, an existing agent must be checked out by the same user (check-in); without
     * it (import), the agent must not be checked out by someone else.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> storeVersion(User by, String path, Map<String, Object> flow, String comment, boolean asProduction, boolean checkedOut, String ip) {
        // with approval before production, "as production" becomes a request that someone else approves
        boolean askApproval = asProduction && approvals.enabled(), makeProduction = asProduction && !askApproval;
        Map<String, Object> stored = storeVersionNow(by, path, flow, comment, makeProduction, checkedOut, ip);
        if (!askApproval) return stored;
        Map<String, Object> out = new LinkedHashMap<>(stored);
        out.put("production_request", approvals.request(by, path, ((Number) stored.get("version")).intValue(), comment, ip));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> storeVersionNow(User by, String path, Map<String, Object> flow, String comment, boolean makeProduction, boolean checkedOut, String ip) {
        require(by, "repo.checkin", "check in agents");
        if (path == null || !PATH.matcher(path).matches() || path.contains(".."))
            throw new OoaException("CR.BAD_PATH", "Invalid agent repository path '" + path + "'", "Use folders and a name ending in .flow.json, e.g. Finance/Invoice approval.flow.json");
        folders.require(by, path, "edit", "check in agents");
        if (makeProduction) folders.require(by, path, "manage", "set live production versions");
        // Workflows and forms are checked in like agents (versions, check-out, live production version) but validated differently.
        boolean workflow = Workflows.isWorkflow(path), form = Workflows.isForm(path);
        List<Map<String, Object>> problems = problemsOf(path, flow);
        if (!problems.isEmpty()) throw new OoaException("CR.INVALID_BOT", problems.size() + " validation problem(s): " + problems.get(0).get("message"),
                "Fix the problems in Agent Studio (Validate) before checking in.", false, Map.of("problems", problems));
        codeAnalysis.atCheckin(path, flow);
        String botId = workflow || form ? (workflow ? "workflow:" : "form:") + path.replaceAll("\\.(process|form)\\.json$", "") : String.valueOf(flow.get("id"));
        return db.tx(() -> {
            Map<String, Object> other = db.one("SELECT path FROM bots WHERE bot_id = ? AND path <> ?", botId, path);
            if (other != null) throw new OoaException("CR.DUPLICATE_ID", "Agent id '" + botId + "' is already used by " + other.get("path"), "Change the agent id in Agent settings.");
            Map<String, Object> b = db.one("SELECT latest_version, locked_by, production_version, deleted_at FROM bots WHERE path = ?", path);
            if (b != null && b.get("deleted_at") != null)
                throw new OoaException("CR.DELETED", "A deleted agent is at " + path, "Recover it first (Deleted agents), or use another name.");
            String json = JsonLite.write(flow);
            int version;
            if (b == null) {
                version = 1;
                db.update("INSERT INTO bots (path, bot_id, name, description, latest_version, production_version, created, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?)",
                        path, botId, String.valueOf(flow.getOrDefault("name", botId)), String.valueOf(flow.getOrDefault("description", "")), 1, makeProduction ? 1 : null, now(), now(), by.username());
            } else {
                if (checkedOut && !by.username().equals(b.get("locked_by")))
                    throw new OoaException("CR.NOT_CHECKED_OUT", b.get("locked_by") == null ? "Check the agent out before checking in a new version" : "Checked out by " + b.get("locked_by"),
                            "Use Agent check-out first, so two people never overwrite each other's work.");
                if (!checkedOut && b.get("locked_by") != null && !by.username().equals(b.get("locked_by")))
                    throw new OoaException("CR.LOCKED", "Checked out by " + b.get("locked_by"), "Ask them to check it in first.");
                version = ((Number) b.get("latest_version")).intValue() + 1;
                db.update("UPDATE bots SET bot_id = ?, name = ?, description = ?, latest_version = ?, production_version = ?, locked_by = NULL, locked_at = NULL, updated = ?, updated_by = ? WHERE path = ?",
                        botId, String.valueOf(flow.getOrDefault("name", botId)), String.valueOf(flow.getOrDefault("description", "")), version,
                        makeProduction ? version : b.get("production_version"), now(), by.username(), path);
            }
            db.update("INSERT INTO bot_versions (path, version, content, sha256, comment, author, created) VALUES (?,?,?,?,?,?,?)",
                    path, version, json, Security.sha256(json), comment == null ? "" : comment, by.username(), now());
            audit.record(by.username(), checkedOut ? "repo.checkin" : "repo.import", "bot", path, Map.of("version", version, "production", makeProduction, "comment", comment == null ? "" : comment), "ok", ip);
            gitSync.changed(by.username(), path + " v" + version + (comment == null || comment.isBlank() ? "" : ": " + comment.strip()));
            // the code analysis report of the new version, against today's policy
            if (CodeAnalysis.analysable(path)) codeAnalysis.store(path, version, codeAnalysis.check(flow), by.username());
            return Map.of("path", path, "version", version, "created", b == null);
        });
    }

    /** Makes a version live, or with approval before production asks for it; returns {ok} or {production_request}. */
    public Map<String, Object> setOrRequestProduction(User by, String path, int version, String comment, String ip) {
        if (approvals.enabled()) return Map.of("production_request", approvals.request(by, path, version, comment, ip));
        setProduction(by, path, version, ip);
        return Map.of("ok", true);
    }

    public void setProduction(User by, String path, int version, String ip) {
        if (approvals.enabled()) throw new OoaException("CR.APPROVAL_REQUIRED", "Production versions need approval", "Ask for approval; another person with the approval right decides.");
        require(by, "repo.production", "set live production versions");
        folders.require(by, path, "manage", "set live production versions");
        if (db.one("SELECT version FROM bot_versions WHERE path = ? AND version = ?", path, version) == null) throw notFound("Version " + version + " of " + path);
        db.update("UPDATE bots SET production_version = ?, updated = ?, updated_by = ? WHERE path = ?", version, now(), by.username(), path);
        audit.record(by.username(), "repo.set_production", "bot", path, Map.of("version", version), "ok", ip);
        gitSync.changed(by.username(), path + ": v" + version + " is live in production");
    }

    /**
     * Deletes an agent: it disappears from the repository and can no longer run, but its versions are kept and it can be
     * recovered. Agents still used by schedules, triggers or work queues are not deleted (those are listed).
     */
    public Map<String, Object> deleteBot(User by, String path, String ip) {
        require(by, "repo.delete", "delete agents");
        folders.require(by, path, "edit", "delete agents");
        Map<String, Object> b = db.one("SELECT locked_by, deleted_at, bot_id FROM bots WHERE path = ?", path);
        if (b == null || b.get("deleted_at") != null) throw notFound("Agent " + path);
        if (b.get("locked_by") != null && !by.username().equals(b.get("locked_by"))) throw new OoaException("CR.LOCKED", "Checked out by " + b.get("locked_by"));
        List<String> users = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT name FROM schedules WHERE bot_path = ?", path)) users.add("schedule " + r.get("name"));
        for (Map<String, Object> r : db.query("SELECT name FROM triggers WHERE bot_path = ?", path)) users.add("trigger " + r.get("name"));
        for (Map<String, Object> r : db.query("SELECT name FROM queues WHERE bot_path = ?", path)) users.add("work queue " + r.get("name"));
        if (!users.isEmpty()) throw new OoaException("CR.IN_USE", "The agent is used by " + String.join(", ", users), "Delete or change those first.", false, Map.of("used_by", users));
        db.update("UPDATE bots SET deleted_at = ?, deleted_by = ?, locked_by = NULL, locked_at = NULL WHERE path = ?", now(), by.username(), path);
        audit.record(by.username(), "repo.delete", "bot", path, null, "ok", ip);
        gitSync.changed(by.username(), path + " deleted");
        return Map.of("deleted", path, "called_by", parentsOf((String) b.get("bot_id")));
    }

    /**
     * Moves an agent to another folder (keeping its name), with its versions, labels, reports, run history and what
     * runs it. Refused while someone else has it checked out, or a workflow names it by path.
     */
    public Map<String, Object> moveBot(User by, String path, String toFolder, String ip) {
        require(by, "repo.checkin", "move agents");
        Map<String, Object> b = db.one("SELECT locked_by FROM bots WHERE path = ?", path);
        if (b == null) throw notFound("Agent " + path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        String to = (toFolder == null || toFolder.isBlank() ? "" : Folders.normalize(toFolder) + "/") + name;
        if (to.equals(path)) return Map.of("path", to);
        folders.require(by, path, "edit", "move this agent");
        folders.require(by, to, "edit", "move agents into that folder");
        if (b.get("locked_by") != null && !by.username().equals(b.get("locked_by"))) throw new OoaException("CR.LOCKED", "Checked out by " + b.get("locked_by"));
        if (db.one("SELECT path FROM bots WHERE path = ?", to) != null) throw new OoaException("CR.CONFLICT", "There is already an agent at " + to);
        List<String> refs = new ArrayList<>();
        for (Map<String, Object> w : db.query("SELECT b.path, v.content FROM bots b JOIN bot_versions v ON v.path = b.path AND v.version = b.latest_version WHERE b.path LIKE ? AND b.deleted_at IS NULL", "%.process.json"))
            if (String.valueOf(w.get("content")).contains("\"" + path + "\"")) refs.add((String) w.get("path"));
        if (!refs.isEmpty()) throw new OoaException("CR.IN_USE", "Workflows name this agent by path: " + String.join(", ", refs), "Change those workflows after moving.", false, Map.of("used_by", refs));
        db.tx(() -> {
            db.update("UPDATE bots SET path = ? WHERE path = ?", to, path);
            db.update("UPDATE bot_versions SET path = ? WHERE path = ?", to, path);
            db.update("UPDATE bot_labels SET path = ? WHERE path = ?", to, path);
            db.update("UPDATE code_analysis SET path = ? WHERE path = ?", to, path);
            db.update("UPDATE schedules SET bot_path = ? WHERE bot_path = ?", to, path);
            db.update("UPDATE triggers SET bot_path = ? WHERE bot_path = ?", to, path);
            db.update("UPDATE queues SET bot_path = ? WHERE bot_path = ?", to, path);
            db.update("UPDATE api_tasks SET bot_path = ? WHERE bot_path = ?", to, path);
            db.update("UPDATE runs SET bot_path = ? WHERE bot_path = ?", to, path);
            db.update("UPDATE workflow_runs SET path = ? WHERE path = ?", to, path);
            return null;
        });
        audit.record(by.username(), "repo.move", "bot", path, Map.of("to", to), "ok", ip);
        return Map.of("path", to);
    }

    /**
     * Copies an agent's latest version to a new agent (version 1, not live), with a new agent id. The copy is the
     * caller's to change; its schedules and history are not copied.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> copyBot(User by, String path, String toPath, String ip) {
        Map<String, Object> flow = new LinkedHashMap<>((Map<String, Object>) getBot(by, path, "latest").get("flow"));
        String to = toPath == null || toPath.isBlank() ? path.replaceAll("\\.(flow|process|form)\\.json$", "") + " (copy)" + path.substring(path.lastIndexOf(".", path.length() - 6)) : toPath.trim();
        if (!to.matches(".*\\.(flow|process|form)\\.json$") || !to.substring(to.indexOf('.', to.lastIndexOf('/') + 1)).equals(path.substring(path.indexOf('.', path.lastIndexOf('/') + 1))))
            throw new OoaException("CR.BAD_PATH", "The copy keeps the kind of file: " + path.substring(path.indexOf('.', path.lastIndexOf('/') + 1)));
        if (db.one("SELECT path FROM bots WHERE path = ?", to) != null) throw new OoaException("CR.CONFLICT", "There is already an agent at " + to);
        if (!Workflows.isWorkflow(path) && !Workflows.isForm(path)) {
            String base = String.valueOf(flow.get("id")) + "_copy", id = base;
            for (int k = 2; db.one("SELECT path FROM bots WHERE bot_id = ?", id) != null; k++) id = base + k;
            flow.put("id", id);
            flow.put("name", "Copy of " + flow.getOrDefault("name", flow.get("id")));
        }
        Map<String, Object> r = storeVersion(by, to, flow, "Copied from " + path, false, false, ip);
        audit.record(by.username(), "repo.copy", "bot", path, Map.of("to", to), "ok", ip);
        return r;
    }

    public void recoverBot(User by, String path, String ip) {
        require(by, "repo.delete", "recover agents");
        folders.require(by, path, "edit", "recover agents");
        if (db.update("UPDATE bots SET deleted_at = NULL, deleted_by = NULL WHERE path = ? AND deleted_at IS NOT NULL", path) == 0) throw notFound("Deleted agent " + path);
        audit.record(by.username(), "repo.recover", "bot", path, null, "ok", ip);
    }

    static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9_.-]{1,40}$");

    /** Puts a label (for example UAT or Release-3) on a version; a label is on one version of an agent at a time. */
    public void setLabel(User by, String path, String label, int version, String ip) {
        require(by, "repo.checkin", "label versions");
        folders.require(by, path, "manage", "label versions");
        if (label == null || !LABEL.matcher(label).matches() || Set.of("latest", "production").contains(label.toLowerCase(Locale.ROOT)))
            throw new OoaException("CR.BAD_INPUT", "Labels use letters, digits, . _ - (up to 40); latest and production are reserved");
        if (db.one("SELECT version FROM bot_versions WHERE path = ? AND version = ?", path, version) == null) throw notFound("Version " + version + " of " + path);
        db.tx(() -> {
            db.update("DELETE FROM bot_labels WHERE path = ? AND label = ?", path, label);
            db.update("INSERT INTO bot_labels (path, label, version, created, created_by) VALUES (?,?,?,?,?)", path, label, version, now(), by.username());
            return null;
        });
        audit.record(by.username(), "repo.label", "bot", path, Map.of("label", label, "version", version), "ok", ip);
    }

    public void removeLabel(User by, String path, String label, String ip) {
        require(by, "repo.checkin", "label versions");
        folders.require(by, path, "manage", "label versions");
        if (db.update("DELETE FROM bot_labels WHERE path = ? AND label = ?", path, label) == 0) throw notFound("Label " + label);
        audit.record(by.username(), "repo.unlabel", "bot", path, Map.of("label", label), "ok", ip);
    }

    /** Agent ids an agent calls: Run Agent steps and the tools of AI agent steps. */
    @SuppressWarnings("unchecked")
    static List<String> childBotIds(Map<String, Object> flow) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of()))) {
            Map<String, Object> a = s.get("args") instanceof Map ? (Map<String, Object>) s.get("args") : Map.of();
            if ("core/run_bot".equals(s.get("action")) && a.get("automation") instanceof String b && !ids.contains(b)) ids.add(b);
            if ("core/run_bot".equals(s.get("action")) && a.get("bot") instanceof String b && !ids.contains(b)) ids.add(b);
            if ("ooa.ai_agent/run".equals(s.get("action")))
                for (String k : List.of("tools", "sensitive_tools")) if (a.get(k) instanceof List<?> l) for (Object x : l) if (x instanceof String xs && !ids.contains(xs)) ids.add(xs);
        }
        return ids;
    }

    /** Toolkits (packages) an agent's steps use. */
    @SuppressWarnings("unchecked")
    static List<String> toolkitsOf(Map<String, Object> flow) {
        TreeSet<String> ids = new TreeSet<>();
        for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of()))) {
            String a = String.valueOf(s.get("action"));
            int i = a.indexOf('/');
            if (i > 0 && !a.startsWith("core/")) ids.add(a.substring(0, i));
        }
        return new ArrayList<>(ids);
    }

    /** Agents whose latest version calls this agent id. */
    List<String> parentsOf(String botId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT b.path, v.content FROM bots b JOIN bot_versions v ON v.path = b.path AND v.version = b.latest_version " +
                "WHERE b.deleted_at IS NULL AND v.content LIKE ?", "%\"" + botId.replace("%", "\\%").replace("_", "\\_") + "\"%")) {
            @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) JsonLite.parse((String) r.get("content"));
            if (childBotIds(f).contains(botId)) out.add((String) r.get("path"));
        }
        return out;
    }

    /** What an agent depends on (child agents, toolkits) and which agents call it. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dependencies(User by, String path, Object version) {
        Map<String, Object> flow = (Map<String, Object>) getBot(by, path, version == null ? "latest" : version).get("flow");
        List<Object> children = new ArrayList<>();
        for (String id : childBotIds(flow)) {
            Map<String, Object> b = db.one("SELECT path, production_version FROM bots WHERE bot_id = ? AND deleted_at IS NULL", id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bot_id", id); m.put("path", b == null ? null : b.get("path")); m.put("found", b != null);
            m.put("production_version", b == null ? null : b.get("production_version"));
            children.add(m);
        }
        Map<String, Object> self = db.one("SELECT bot_id FROM bots WHERE path = ?", path);
        return Map.of("path", path, "agents", children, "toolkits", toolkitsOf(flow), "called_by", self == null ? List.of() : parentsOf((String) self.get("bot_id")));
    }

    // ===================== devices and agents =====================

    public Map<String, Object> createRegistrationKey(User by, int hours, String ip) { return createRegistrationKey(by, hours, false, ip); }

    /**
     * A one-time registration key. {@code temporary}: runners registered with it (VDI, pooled desktops) are removed by
     * themselves after {@code temporary_runner_hours} without contact, freeing their license seat.
     */
    public Map<String, Object> createRegistrationKey(User by, int hours, boolean temporary, String ip) {
        require(by, "devices.manage", "register runners");
        String key = "ooareg_" + Security.token(24);
        db.update("INSERT INTO registration_keys (key_hash, created_by, created, expires, temporary) VALUES (?,?,?,?,?)", Security.sha256(key), by.username(), now(),
            now().plus(Duration.ofHours(Math.max(1, Math.min(168, hours)))), temporary);
        audit.record(by.username(), "device.registration_key", "device", null, Map.of("hours", hours, "temporary", temporary), "ok", ip);
        return Map.of("key", key, "expires_in_hours", hours, "temporary", temporary);
    }

    /** One-hour key for the Bot Agent that `ooa server` starts on this machine; no signed-in user is involved. */
    public String localRegistrationKey() {
        String key = "ooareg_" + Security.token(24);
        db.update("INSERT INTO registration_keys (key_hash, created_by, created, expires) VALUES (?,?,?,?)", Security.sha256(key), "system", now(), now().plus(Duration.ofHours(1)));
        audit.record("system", "device.registration_key", "device", null, Map.of("local_agent", true), "ok", null);
        return key;
    }

    public Map<String, Object> registerDevice(String key, String name, String hostname, String os, String agentVersion, String ip) {
        return db.tx(() -> {
            Map<String, Object> k = key == null ? null : db.one("SELECT * FROM registration_keys WHERE key_hash = ?", Security.sha256(key));
            if (k == null || k.get("used_by") != null || Instant.parse(k.get("expires").toString()).isBefore(now())) {
                audit.record("agent:" + hostname, "device.register", "device", name, null, "failed", ip);
                throw new OoaException("CR.BAD_REGISTRATION_KEY", "The registration key is wrong, expired or already used", "Create a new key in Control Room > Devices.");
            }
            String deviceId = id("dev"), secret = Security.token(32);
            String nm = name == null || name.isBlank() ? hostname : name;
            db.update("INSERT INTO devices (id, name, hostname, os, agent_version, secret_hash, enabled, last_seen, registered, registered_by, lifespan, secret_set) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    deviceId, nm, hostname, os, agentVersion, Security.sha256(secret), true, now(), now(), k.get("created_by"), Boolean.TRUE.equals(k.get("temporary")) ? "temporary" : "persistent", now());
            db.update("UPDATE registration_keys SET used_by = ? WHERE key_hash = ?", deviceId, k.get("key_hash"));
            audit.record("agent:" + hostname, "device.register", "device", deviceId, Map.of("name", nm), "ok", ip);
            return Map.of("device_id", deviceId, "secret", secret, "name", nm);
        });
    }

    public Map<String, Object> device(String deviceId, String secret) {
        if (deviceId == null || secret == null) return null;
        Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", deviceId);
        if (d == null || !Boolean.TRUE.equals(d.get("enabled"))) return null;
        String h = Security.sha256(secret);
        if (Security.constantEquals(h, (String) d.get("secret_hash"))) return d;
        // just after a rotation the previous secret still works for a few minutes (requests already on their way)
        boolean previous = d.get("secret_prev_hash") != null && d.get("secret_prev_until") != null && Instant.parse(d.get("secret_prev_until").toString()).isAfter(now())
            && Security.constantEquals(h, (String) d.get("secret_prev_hash"));
        return previous ? d : null;
    }

    /**
     * Gives a runner a new secret (it asked, after its heartbeat said rotate); the old one keeps working for 10 minutes.
     * Returns the new secret once; only its hash is stored.
     */
    public Map<String, Object> rotateSecret(Map<String, Object> d, String ip) {
        String secret = Security.token(32);
        db.update("UPDATE devices SET secret_prev_hash = secret_hash, secret_prev_until = ?, secret_hash = ?, secret_set = ? WHERE id = ?",
            now().plus(Duration.ofMinutes(10)), Security.sha256(secret), now(), d.get("id"));
        audit.record("agent:" + d.get("name"), "device.rotate_secret", "device", (String) d.get("id"), null, "ok", ip);
        return Map.of("secret", secret);
    }

    /** True when the runner's secret is older than runner_secret_days (0: never). */
    boolean secretDue(Map<String, Object> d) {
        int days = platform.intSetting("runner_secret_days");
        if (days <= 0) return false;
        Object set = d.get("secret_set") != null ? d.get("secret_set") : d.get("registered");
        return set != null && Instant.parse(set.toString()).isBefore(now().minus(Duration.ofDays(days)));
    }

    public List<Map<String, Object>> devices(User by) {
        require(by, "devices.view", "view runners");
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<String>> defaults = new HashMap<>();
        for (Map<String, Object> u : db.query("SELECT username, device_id FROM user_devices ORDER BY username")) defaults.computeIfAbsent((String) u.get("device_id"), k -> new ArrayList<>()).add((String) u.get("username"));
        for (Map<String, Object> d : db.query("SELECT id, name, hostname, os, agent_version, enabled, last_seen, registered, registered_by, log_level, device_type, session_state, after_run, build, update_requested, lifespan, toolkits FROM devices ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(d);
            if (m.get("log_level") == null) m.put("log_level", "info");
            if (m.get("after_run") == null) m.put("after_run", "nothing");
            Map<String, Object> mf = runnerUpdates.manifest();
            if (mf != null && m.get("build") != null) m.put("up_to_date", mf.get("build").equals(m.get("build")));
            if (m.get("device_type") == null) m.put("device_type", "multi_user");
            m.put("default_users", defaults.getOrDefault((String) d.get("id"), List.of()));
            boolean online = d.get("last_seen") != null && Instant.parse(d.get("last_seen").toString()).isAfter(now().minus(ONLINE));
            m.put("status", !Boolean.TRUE.equals(d.get("enabled")) ? "disabled" : online ? "online" : "offline");
            m.put("version_status", versionStatus(d.get("agent_version")));
            m.put("running", ((Number) db.one("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status = 'running'", d.get("id")).get("n")).intValue());
            out.add(m);
        }
        return out;
    }

    /** Runners filtered by status (online, offline, disabled) and text, with paging and the total. */
    public Map<String, Object> devicesPage(User by, Map<String, String> q) {
        String text = q.getOrDefault("q", "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> f = devices(by).stream().filter(d -> (text.isEmpty() || (d.get("name") + " " + d.get("hostname") + " " + d.get("os")).toLowerCase(Locale.ROOT).contains(text))
                && (q.get("status") == null || q.get("status").isBlank() || q.get("status").equals(d.get("status")))).toList();
        return page("devices", f, q);
    }

    public Map<String, Object> deviceDetail(User by, String deviceId) {
        return devices(by).stream().filter(d -> deviceId.equals(d.get("id"))).findFirst().orElseThrow(() -> notFound("Runner"));
    }

    public void renameDevice(User by, String deviceId, String name, String ip) {
        require(by, "devices.manage", "manage runners");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) throw new OoaException("CR.BAD_INPUT", "Runner names have 1 to 120 characters");
        if (db.update("UPDATE devices SET name = ? WHERE id = ?", n, deviceId) == 0) throw notFound("Runner");
        audit.record(by.username(), "device.rename", "device", deviceId, Map.of("name", n), "ok", ip);
    }

    static final List<String> LOG_LEVELS = List.of("error", "warning", "info", "debug");
    /** What a runner reports about its screen: unlocked (a signed-in, usable desktop), locked, or no_desktop (a service). */
    static final List<String> SESSION_STATES = List.of("unlocked", "locked", "no_desktop");
    /** What a runner does after an unattended run: nothing, lock the screen, or sign the user out. */
    static final List<String> AFTER_RUN = List.of("nothing", "lock", "sign_out");

    /**
     * Sets runner settings for one or more runners (Update checked runners): log_level (error, warning, info, debug; the
     * runner picks it up with its next heartbeat) and device_type (single_user: one person's default runner;
     * multi_user, the default for runners nobody set: the default runner of several people) and after_run (nothing,
     * lock or sign_out: what the runner does with its screen after an unattended run).
     */
    public Map<String, Object> updateDevices(User by, List<String> ids, Map<String, Object> b, String ip) {
        require(by, "devices.manage", "manage runners");
        if (ids == null || ids.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Choose at least one runner");
        String level = b.get("log_level") == null ? null : String.valueOf(b.get("log_level"));
        String type = b.get("device_type") == null ? null : String.valueOf(b.get("device_type"));
        if (level != null && !LOG_LEVELS.contains(level)) throw new OoaException("CR.BAD_INPUT", "The log level is one of " + LOG_LEVELS);
        if (type != null && !type.equals("single_user") && !type.equals("multi_user")) throw new OoaException("CR.BAD_INPUT", "The runner type is single_user or multi_user");
        String after = b.get("after_run") == null ? null : String.valueOf(b.get("after_run"));
        if (after != null && !AFTER_RUN.contains(after)) throw new OoaException("CR.BAD_INPUT", "After a run the runner does one of " + AFTER_RUN);
        int n = db.tx(() -> {
            int k = 0;
            for (String id : ids) {
                if (db.one("SELECT id FROM devices WHERE id = ?", id) == null) throw notFound("Runner " + id);
                if ("single_user".equals(type) && count("SELECT COUNT(*) AS n FROM user_devices WHERE device_id = ?", id) > 1)
                    throw new OoaException("CR.CONFLICT", "Runner " + id + " is the default runner of several people", "Give them other default runners first, or keep it multi-user.");
                if (level != null) k += db.update("UPDATE devices SET log_level = ? WHERE id = ?", level, id);
                if (type != null) k += db.update("UPDATE devices SET device_type = ? WHERE id = ?", type, id);
                if (after != null) k += db.update("UPDATE devices SET after_run = ? WHERE id = ?", after, id);
            }
            return k;
        });
        Map<String, Object> changes = new LinkedHashMap<>();
        if (level != null) changes.put("log_level", level);
        if (type != null) changes.put("device_type", type);
        if (after != null) changes.put("after_run", after);
        audit.record(by.username(), "device.update", "device", String.join(",", ids), changes, "ok", ip);
        return Map.of("updated", ids.size(), "changes", n);
    }

    public void setDeviceEnabled(User by, String deviceId, boolean enabled, String ip) {
        require(by, "devices.manage", "manage runners");
        if (db.update("UPDATE devices SET enabled = ? WHERE id = ?", enabled, deviceId) == 0) throw notFound("Runner");
        audit.record(by.username(), enabled ? "device.enable" : "device.disable", "device", deviceId, null, "ok", ip);
    }

    public Map<String, Object> heartbeat(Map<String, Object> d, Map<String, Object> info) {
        String did = (String) d.get("id");
        String session = SESSION_STATES.contains(String.valueOf(info.get("session"))) ? (String) info.get("session") : null;
        String build = info.get("build") instanceof String b && b.matches("[0-9a-f]{16}") ? b : null;
        if (info.get("toolkits") instanceof List<?> tk) {
            String list = String.join(",", tk.stream().map(String::valueOf).filter(x -> x.matches("[A-Za-z0-9._-]{1,80}@[A-Za-z0-9._-]{1,40}")).sorted().toList());
            if (list.length() <= 20000) db.update("UPDATE devices SET toolkits = ? WHERE id = ?", list, did);
        }
        db.update("UPDATE devices SET last_seen = ?, agent_version = ?, hostname = COALESCE(?, hostname), os = COALESCE(?, os), session_state = COALESCE(?, session_state), build = COALESCE(?, build) WHERE id = ?",
                now(), String.valueOf(info.getOrDefault("agent_version", d.get("agent_version"))), (String) info.get("hostname"), (String) info.get("os"), session, build, did);
        List<Object> cancel = new ArrayList<>(), pause = new ArrayList<>(), resume = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, cancel_requested, pause_requested FROM runs WHERE device_id = ? AND status = 'running'", did)) {
            if (Boolean.TRUE.equals(r.get("cancel_requested"))) cancel.add(r.get("id"));
            else if (Boolean.TRUE.equals(r.get("pause_requested"))) pause.add(r.get("id")); else resume.add(r.get("id"));
        }
        List<Object> triggers = new ArrayList<>(), local = new ArrayList<>();
        for (Map<String, Object> t : db.query("SELECT id, kind, folder, pattern, config FROM triggers WHERE device_id = ? AND enabled = TRUE", did)) {
            TriggerKinds.Kind k = TriggerKinds.KINDS.get(String.valueOf(t.get("kind")));
            if (k == null || k.where() != TriggerKinds.Where.RUNNER) continue;
            if ("file".equals(t.get("kind"))) triggers.add(Map.of("id", t.get("id"), "folder", t.get("folder"), "pattern", t.get("pattern")));   // older runners
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.get("id")); m.put("kind", t.get("kind")); m.put("folder", t.get("folder")); m.put("pattern", t.get("pattern"));
            m.put("config", TriggerKinds.publicConfig(TriggerPollers.config(t)));
            local.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>(Map.of("cancel", cancel, "pause", pause, "resume", resume, "file_triggers", triggers, "local_triggers", local,
                "log_level", d.get("log_level") == null ? "info" : d.get("log_level"), "after_run", d.get("after_run") == null ? "nothing" : d.get("after_run")));
        Map<String, Object> update = runnerUpdates.forHeartbeat(d, build, !cancel.isEmpty() || !pause.isEmpty() || !resume.isEmpty());
        if (update != null) out.put("update", update);
        if (secretDue(d)) out.put("rotate", true);
        if (d.get("toolkits") == null && !(info.get("toolkits") instanceof List)) out.put("send_toolkits", true);   // runners send their toolkit list when asked
        return out;
    }

    // ===================== runs =====================

    public Map<String, Object> startRun(String requestedBy, String role, String path, String deviceId, Map<String, Object> inputs, String source, String ip) {
        return startRun(requestedBy, role, path, deviceId, null, inputs, source, ip, 0, null);
    }

    /** Starts a run on a runner, or on the least busy online runner of a pool; priority orders the runner's queue; the callback URL gets the result. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> startRun(String requestedBy, String role, String path, String deviceId, String poolId, Map<String, Object> inputs, String source, String ip,
                                        int priority, String callbackUrl) {
        return startRun(requestedBy, role, path, deviceId, poolId, inputs, source, ip, priority, callbackUrl, null, null);
    }

    static final Set<String> RUN_MODES = Set.of("unattended", "attended", "headless");

    /**
     * As above, as a run-as user: the run goes to that user's default runner (unless a runner or pool is given), and the
     * agent's credentials are checked against that user. The mode is unattended (default), attended (someone is at the
     * runner) or headless (no desktop: the agent must not need one).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> startRun(String requestedBy, String role, String path, String deviceId, String poolId, Map<String, Object> inputs, String source, String ip,
                                        int priority, String callbackUrl, String runAs, String mode) {
        if (rolesOf(requestedBy, role).stream().noneMatch(r -> allowed(r, "runs.start"))) throw denied("start runs");
        if (path == null || !path.endsWith(".flow.json")) throw new OoaException("CR.NOT_AN_AGENT", path + " is not an agent", "Start workflows from One Workflow; forms run inside workflows.");
        folders.require(requestedBy, role, path, "run", "run agents");
        String runMode = mode == null || mode.isBlank() ? "unattended" : mode;
        if (!RUN_MODES.contains(runMode)) throw new OoaException("CR.BAD_INPUT", "The run mode is unattended, attended or headless");
        if (runAs != null && !runAs.isBlank()) {
            if (db.one("SELECT id FROM users WHERE username = ? AND enabled = TRUE", runAs) == null) throw notFound("Run-as user " + runAs);
            if ((deviceId == null || deviceId.isBlank()) && (poolId == null || poolId.isBlank())) {
                Map<String, Object> ud = db.one("SELECT device_id FROM user_devices WHERE username = ?", runAs);
                if (ud == null) throw new OoaException("CR.NO_DEFAULT_RUNNER", runAs + " has no default runner", "Set one in Users (Default runner), or choose a runner.");
                deviceId = (String) ud.get("device_id");
            }
        }
        if ((deviceId == null || deviceId.isBlank()) && poolId != null && !poolId.isBlank()) {
            deviceId = platform.pickDevice(poolId);
            if (deviceId == null) throw new OoaException("CR.NO_RUNNER", "No runner of the pool is online", "Start a runner of the pool, or pick another pool.", true, null);
        }
        if (deviceId == null || deviceId.isBlank()) throw new OoaException("CR.BAD_INPUT", "Choose a runner or a runner pool");
        if (callbackUrl != null && !callbackUrl.isBlank() && !callbackUrl.matches("^https?://\\S+$")) throw new OoaException("CR.BAD_INPUT", "The callback must be an http(s) URL");
        Map<String, Object> c = content(path, "production");
        Map<String, Object> flow = (Map<String, Object>) c.get("flow");
        platform.checkToolkits(flow);
        if (runMode.equals("headless") && flow.get("requires") instanceof Map<?, ?> req && req.get("capabilities") instanceof List<?> caps && caps.contains("desktop"))
            throw new OoaException("CR.BAD_INPUT", "This agent needs a desktop, so it cannot run headless", "Run it unattended or attended.");
        Map<String, Object> dev = db.one("SELECT enabled FROM devices WHERE id = ?", deviceId);
        if (dev == null) throw notFound("Runner " + deviceId);
        if (!Boolean.TRUE.equals(dev.get("enabled"))) throw new OoaException("CR.DEVICE_DISABLED", "That runner is disabled");
        licensing.requireRunnable(deviceId, runAs != null && !runAs.isBlank() ? runAs : requestedBy, source);
        Map<String, Object> clean = new LinkedHashMap<>();
        Map<String, Map<String, Object>> declared = new HashMap<>();
        for (Object v : (List<Object>) flow.getOrDefault("variables", List.of())) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (java.util.Arrays.asList("input", "in_out").contains(m.get("direction"))) declared.put(String.valueOf(m.get("name")), m);
        }
        for (var e : (inputs == null ? Map.<String, Object>of() : inputs).entrySet()) {
            Map<String, Object> var = declared.get(e.getKey());
            if (var == null) throw new OoaException("CR.BAD_INPUT", "The agent has no input named '" + e.getKey() + "'", "Inputs: " + declared.keySet());
            if ("Credential".equals(var.get("type")) && !(e.getValue() instanceof Map<?, ?> m && m.get("$credential") instanceof String))
                throw new OoaException("CR.BAD_INPUT", "Input '" + e.getKey() + "' is a Credential: give a vault reference {\"$credential\": \"locker/credential/attribute\"}, never the secret itself");
            clean.put(e.getKey(), e.getValue());
        }
        String runId = id("run");
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested, priority, callback_url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, path, c.get("version"), deviceId, "queued", JsonLite.write(clean), requestedBy, source, now(), false, false, priority,
                callbackUrl == null || callbackUrl.isBlank() ? null : callbackUrl);
        String asUser = runAs == null || runAs.isBlank() ? null : runAs;
        if (asUser != null || !runMode.equals("unattended")) db.update("UPDATE runs SET run_as = ?, run_mode = ? WHERE id = ?", asUser, runMode, runId);
        Map<String, Object> details = new LinkedHashMap<>(Map.of("bot", path, "version", c.get("version"), "device", deviceId, "source", source, "mode", runMode));
        if (asUser != null) details.put("run_as", asUser);
        audit.record(requestedBy, "run.start", "run", runId, details, "ok", ip);
        Map<String, Object> out = new LinkedHashMap<>(Map.of("run_id", runId, "version", c.get("version"), "device_id", deviceId));
        if (asUser != null) out.put("run_as", asUser);
        wake(deviceId, false);
        return out;
    }

    /**
     * Deploy to several targets at once: each run-as user (on their default runner) and each runner. Every target gets
     * its own run; a target that cannot run is reported with its error and the others still start.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deploy(User by, Map<String, Object> b, String ip) {
        require(by, "runs.start", "start runs");
        String path = (String) b.get("bot_path");
        Map<String, Object> inputs = (Map<String, Object>) b.getOrDefault("inputs", Map.of());
        String mode = (String) b.get("mode"), callback = (String) b.get("callback_url");
        int priority = b.get("priority") instanceof Number pr ? pr.intValue() : 0;
        String source = b.get("source") instanceof String src && src.equals("api") ? "api" : "manual";
        List<Object> runs = new ArrayList<>();
        int started = 0;
        for (Object u : b.get("run_as_users") instanceof List<?> l ? l : List.of()) {
            try { runs.add(startRun(by.username(), by.role(), path, null, null, inputs, source, ip, priority, callback, String.valueOf(u), mode)); started++; }
            catch (OoaException e) { runs.add(Map.of("run_as", String.valueOf(u), "error", e.toMap())); }
        }
        for (Object d : b.get("device_ids") instanceof List<?> l ? l : List.of()) {
            try { runs.add(startRun(by.username(), by.role(), path, String.valueOf(d), null, inputs, source, ip, priority, callback, null, mode)); started++; }
            catch (OoaException e) { runs.add(Map.of("device_id", String.valueOf(d), "error", e.toMap())); }
        }
        if (runs.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Give run_as_users or device_ids");
        return Map.of("runs", runs, "started", started, "failed", runs.size() - started);
    }

    /** Users who can be run-as users, with their default runner. */
    public List<Map<String, Object>> runAsUsers(User by) {
        require(by, "devices.view", "view runners");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT u.username, u.display_name, u.role, ud.device_id, d.name AS device_name, d.last_seen, d.enabled AS device_enabled " +
                "FROM users u LEFT JOIN user_devices ud ON ud.username = u.username LEFT JOIN devices d ON d.id = ud.device_id WHERE u.enabled = TRUE ORDER BY u.username")) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            boolean online = r.get("last_seen") != null && Instant.parse(r.get("last_seen").toString()).isAfter(now().minus(ONLINE));
            m.remove("last_seen"); m.remove("device_enabled");
            m.put("device_status", r.get("device_id") == null ? null : !Boolean.TRUE.equals(r.get("device_enabled")) ? "disabled" : online ? "online" : "offline");
            out.add(m);
        }
        return out;
    }

    /** Sets (or with null clears) the runner a user's runs go to by default. */
    public void setDefaultDevice(User by, String userId, String deviceId, String ip) {
        require(by, "users.manage", "manage users");
        Map<String, Object> u = db.one("SELECT username FROM users WHERE id = ? OR username = ?", userId, userId);
        if (u == null) throw notFound("User");
        String name = (String) u.get("username");
        if (deviceId == null || deviceId.isBlank()) db.update("DELETE FROM user_devices WHERE username = ?", name);
        else {
            Map<String, Object> dv = db.one("SELECT id, device_type FROM devices WHERE id = ?", deviceId);
            if (dv == null) throw notFound("Runner");
            if ("single_user".equals(dv.get("device_type")) && count("SELECT COUNT(*) AS n FROM user_devices WHERE device_id = ? AND username <> ?", deviceId, name) > 0)
                throw new OoaException("CR.CONFLICT", "This runner is single-user and already someone's default runner", "Make it a multi-user runner first, or choose another runner.");
            db.tx(() -> {
                db.update("DELETE FROM user_devices WHERE username = ?", name);
                db.update("INSERT INTO user_devices (username, device_id, updated, updated_by) VALUES (?,?,?,?)", name, deviceId, now(), by.username());
                return null;
            });
        }
        audit.record(by.username(), "user.default_runner", "user", name, Map.of("device", deviceId == null ? "" : deviceId), "ok", ip);
    }

    /** Removes a runner: it must not be running anything and is taken off pools, schedules, triggers and default runners. */
    public void deleteDevice(User by, String deviceId, String ip) {
        require(by, "devices.manage", "manage runners");
        if (db.one("SELECT id FROM devices WHERE id = ?", deviceId) == null) throw notFound("Runner");
        if (count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status IN ('queued', 'running')", deviceId) > 0)
            throw new OoaException("CR.CONFLICT", "The runner has queued or running work", "Stop it or wait for it to finish.");
        List<String> used = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT name FROM schedules WHERE device_id = ?", deviceId)) used.add("schedule " + r.get("name"));
        for (Map<String, Object> r : db.query("SELECT name FROM triggers WHERE device_id = ?", deviceId)) used.add("trigger " + r.get("name"));
        if (!used.isEmpty()) throw new OoaException("CR.IN_USE", "The runner is used by " + String.join(", ", used), "Change those first.", false, Map.of("used_by", used));
        db.tx(() -> {
            db.update("DELETE FROM user_devices WHERE device_id = ?", deviceId);
            for (Map<String, Object> p : db.query("SELECT id, devices FROM device_pools")) {
                List<String> devs = new ArrayList<>(csv(p.get("devices")));
                if (devs.remove(deviceId)) db.update("UPDATE device_pools SET devices = ? WHERE id = ?", String.join(",", devs), p.get("id"));
            }
            db.update("DELETE FROM license_assignments WHERE principal_type = 'device' AND principal = ?", deviceId);   // its seat is free again
            db.update("DELETE FROM devices WHERE id = ?", deviceId);
            return null;
        });
        audit.record(by.username(), "device.delete", "device", deviceId, null, "ok", ip);
    }

    /**
     * Removes temporary runners (VDI) not heard from for temporary_runner_hours, with their seat and default-runner and
     * pool entries. One with queued or running work, or used by a schedule or trigger, stays (and is retried later).
     */
    void expireTemporaryRunners() {
        Instant cutoff = now().minus(Duration.ofHours(Math.max(1, platform.intSetting("temporary_runner_hours"))));
        for (Map<String, Object> d : db.query("SELECT id, name FROM devices WHERE lifespan = 'temporary' AND last_seen < ?", cutoff)) {
            String id = (String) d.get("id");
            if (count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status IN ('queued', 'running')", id) > 0) continue;
            if (count("SELECT COUNT(*) AS n FROM schedules WHERE device_id = ?", id) > 0 || count("SELECT COUNT(*) AS n FROM triggers WHERE device_id = ?", id) > 0) continue;
            db.tx(() -> {
                db.update("DELETE FROM user_devices WHERE device_id = ?", id);
                for (Map<String, Object> p : db.query("SELECT id, devices FROM device_pools")) {
                    List<String> devs = new ArrayList<>(csv(p.get("devices")));
                    if (devs.remove(id)) db.update("UPDATE device_pools SET devices = ? WHERE id = ?", String.join(",", devs), p.get("id"));
                }
                db.update("DELETE FROM license_assignments WHERE principal_type = 'device' AND principal = ?", id);
                db.update("DELETE FROM devices WHERE id = ?", id);
                return null;
            });
            audit.record("system", "device.expired", "device", id, Map.of("name", d.get("name"), "offline_since_before", cutoff.toString()), "ok", null);
        }
    }

    /** Atomically assigns the oldest queued run of this device and returns its job (bot bundle, inputs, global values). */
    @SuppressWarnings("unchecked")
    /** The release of this Command Center; runners may be this release or older, never newer (they may use what it does not know). */
    public static final String VERSION = "0.2.0";

    /** Compares dotted versions numerically (0.10.0 is newer than 0.9.2); missing parts count as 0. */
    static int compareVersions(String a, String b) {
        String[] x = String.valueOf(a).split("[.-]"), y = String.valueOf(b).split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && x[i].matches("\\d+") ? Integer.parseInt(x[i]) : 0, q = i < y.length && y[i].matches("\\d+") ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    /** "ok", or why the runner's version may not take work (a runner newer than the Command Center). */
    static String versionStatus(Object runnerVersion) {
        if (runnerVersion == null || String.valueOf(runnerVersion).isBlank() || "null".equals(String.valueOf(runnerVersion))) return "ok";
        return compareVersions(String.valueOf(runnerVersion), VERSION) > 0 ? "newer_than_command_center" : "ok";
    }

    // ---------- waiting for work (the runner's long poll) ----------
    private final Object wakeUp = new Object();
    /** Runners told to send a heartbeat now (a run of theirs was stopped, paused or resumed). */
    private final Set<String> checkNow = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile byte[] auditKey;

    /** The audit chain key: 32 random bytes made once, kept in settings encrypted with the vault key. */
    byte[] auditKey() {
        byte[] k = auditKey;
        if (k != null) return k;
        Map<String, Object> r = db.one("SELECT json_value FROM settings WHERE name = 'audit_chain_key'");
        if (r == null) {
            byte[] fresh = new byte[32];
            Security.RANDOM.nextBytes(fresh);
            try {
                db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", "audit_chain_key",
                    JsonLite.write(vault.encrypt(Base64.getEncoder().encodeToString(fresh), "audit-chain")), now(), "system");
            } catch (OoaException e) { return auditKey(); }   // made by another request at the same moment
            return auditKey = fresh;
        }
        return auditKey = Base64.getDecoder().decode(vault.decrypt(String.valueOf(JsonLite.parse((String) r.get("json_value"))), "audit-chain"));
    }

    /** Wakes runners waiting for work: a run was queued, or a run of theirs needs controlling (deviceId null: all). */
    void wake(String deviceId, boolean control) {
        if (control && deviceId != null) checkNow.add(deviceId);
        synchronized (wakeUp) { wakeUp.notifyAll(); }
    }

    /**
     * The next job for a runner, waiting up to {@code waitMs} for one (a long poll: the runner gets work the moment it is
     * queued, without asking every few seconds). Answers {job}, {job: false}, or {job: false, check: true} when the runner
     * should send a heartbeat at once (a run of its own was stopped or paused). Checks again every 2 seconds in any case,
     * for work queued without a wake-up (runner pools, other Command Center nodes).
     */
    public Map<String, Object> nextJob(Map<String, Object> d, long waitMs) {
        String did = (String) d.get("id");
        long end = System.currentTimeMillis() + Math.max(0, Math.min(waitMs, 25_000));
        while (true) {
            Map<String, Object> job = claimNext(d);
            if (job != null) return Map.of("job", job);
            if (checkNow.remove(did)) return Map.of("job", false, "check", true);
            long left = end - System.currentTimeMillis();
            if (left <= 0) return Map.of("job", false);
            synchronized (wakeUp) {
                try { wakeUp.wait(Math.min(left, 2000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return Map.of("job", false); }
            }
        }
    }

    /**
     * The toolkits an agent and the agents it calls use (from their steps), and which of them a runner lacks. A runner
     * that has not reported its toolkits yet (an older one) is not checked.
     */
    @SuppressWarnings("unchecked")
    static Set<String> missingToolkits(Object runnerToolkits, Map<String, Object> main, Map<String, Object> bundle) {
        if (runnerToolkits == null || String.valueOf(runnerToolkits).isBlank()) return Set.of();
        Set<String> has = new HashSet<>();
        for (String t : String.valueOf(runnerToolkits).split(",")) has.add(t.substring(0, Math.max(0, t.indexOf('@'))));
        Set<String> missing = new TreeSet<>();
        List<Map<String, Object>> flows = new ArrayList<>(List.of(main));
        for (Object b : bundle.values()) if (b instanceof Map<?, ?> m) flows.add((Map<String, Object>) m);
        for (Map<String, Object> f : flows)
            for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) f.getOrDefault("steps", List.of()))) {
                String a = String.valueOf(s.get("action"));
                if (!a.contains("/")) continue;
                String pid = a.substring(0, a.indexOf('/'));
                if (!has.contains(pid)) missing.add(pid);
            }
        return missing;
    }

    private void requireToolkits(Map<String, Object> d, Map<String, Object> main, Map<String, Object> bundle) {
        Set<String> missing = missingToolkits(d.get("toolkits"), main, bundle);
        if (!missing.isEmpty()) throw new OoaException("RUN.TOOLKIT_MISSING", "The runner " + d.get("name") + " does not have the toolkit(s) " + String.join(", ", missing),
            "Update the runner (Runners > Update), or send the run to a runner that has them.", false, Map.of("missing", new ArrayList<>(missing)));
    }

    public Map<String, Object> claimNext(Map<String, Object> d) {
        String did = (String) d.get("id");
        // a runner newer than this Command Center gets no work until the Command Center is updated
        Map<String, Object> ver = db.one("SELECT agent_version FROM devices WHERE id = ?", did);
        if (ver != null && !"ok".equals(versionStatus(ver.get("agent_version")))) return null;
        for (Map<String, Object> r : db.query("SELECT id FROM runs WHERE device_id = ? AND status = 'queued' ORDER BY COALESCE(priority, 0) DESC, created LIMIT 5", did)) {
            if (db.update("UPDATE runs SET status = 'running', started = ? WHERE id = ? AND status = 'queued'", now(), r.get("id")) != 1) continue;
            Map<String, Object> run = db.one("SELECT * FROM runs WHERE id = ?", r.get("id"));
            // the license may have changed since the run was queued (expired, or the runner's seat taken back)
            String licenseProblem = licensing.runProblem(did, String.valueOf(run.get("run_as") != null ? run.get("run_as") : run.get("requested_by")), String.valueOf(run.get("source")));
            if (licenseProblem != null) {
                db.update("UPDATE runs SET status = 'failed', finished = ?, error = ? WHERE id = ?", now(),
                    JsonLite.write(new OoaException(licensing.expired() ? "CR.LICENSE_EXPIRED" : "CR.LICENSE_REQUIRED", licenseProblem).toMap()), run.get("id"));
                audit.record("agent:" + d.get("name"), "run.finish", "run", (String) run.get("id"), Map.of("status", "failed", "code", "CR.LICENSE_REQUIRED"), "failed", null);
                continue;
            }
            Map<String, Object> main = (Map<String, Object>) content((String) run.get("bot_path"), run.get("version")).get("flow");
            Map<String, Object> bundle = new LinkedHashMap<>();
            collect(main, bundle, 0);
            Map<String, Object> globals = new LinkedHashMap<>();
            for (Map<String, Object> g : db.query("SELECT name, json_value FROM global_values")) globals.put((String) g.get("name"), JsonLite.parse((String) g.get("json_value")));
            // a run-as user's runs use that user's identity (credentials, audit of the agent's own actions)
            Object principal = run.get("run_as") != null ? run.get("run_as") : run.get("requested_by");
            // shared values a person may change for themselves: theirs when they set one
            for (Map<String, Object> g : db.query("SELECT u.name, u.json_value FROM user_global_values u JOIN global_values g ON g.name = u.name WHERE g.changeable = TRUE AND u.username = ?", principal))
                globals.put((String) g.get("name"), JsonLite.parse((String) g.get("json_value")));
            Map<String, Object> user = db.one("SELECT role FROM users WHERE username = ?", principal);
            String role = user == null ? "viewer" : (String) user.get("role");
            Map<String, Object> files, skills;
            try {
                requireToolkits(d, main, bundle);
                files = repoFiles.forJob(main, String.valueOf(principal), role);
                skills = promptSkills.forJob(main, bundle, String.valueOf(principal));
            } catch (OoaException e) {
                // a missing or forbidden file or skill fails the run before it starts, with the reason
                db.update("UPDATE runs SET status = 'failed', finished = ?, error = ? WHERE id = ?", now(), JsonLite.write(e.toMap()), run.get("id"));
                audit.record("agent:" + d.get("name"), "run.finish", "run", (String) run.get("id"), Map.of("status", "failed", "code", e.code()), "failed", null);
                continue;
            }
            Map<String, Object> job = new LinkedHashMap<>(Map.of("run_id", run.get("id"), "bot_path", run.get("bot_path"), "version", run.get("version"), "flow", main, "bots", bundle,
                    "inputs", JsonLite.parse((String) run.get("inputs")), "principal", principal, "global_values", globals));
            job.put("mode", run.get("run_mode") == null ? "unattended" : run.get("run_mode"));
            job.put("model_connections", platform.connectionsForJob(rolesOf(String.valueOf(principal), role)));
            Map<String, Object> saved = connectionsForJob();
            if (!saved.isEmpty()) job.put("connections", saved);
            if (!files.isEmpty()) job.put("files", files);
            if (!skills.isEmpty()) job.put("skills", skills);
            return job;
        }
        return null;
    }

    /** Adds production versions of child bots (Run bot) and AI agent tools, transitively. */
    @SuppressWarnings("unchecked")
    void collect(Map<String, Object> flow, Map<String, Object> bundle, int depth) {
        if (depth > 10 || bundle.size() > 100) return;
        for (String botId : childBotIds(flow)) {
            if (bundle.containsKey(botId)) continue;
            Map<String, Object> b = db.one("SELECT path FROM bots WHERE bot_id = ? AND deleted_at IS NULL", botId);
            if (b == null) continue;  // the Runner reports a clear error if the bot is really needed
            try {
                Map<String, Object> child = (Map<String, Object>) content((String) b.get("path"), "production").get("flow");
                bundle.put(botId, child);
                collect(child, bundle, depth + 1);
            } catch (OoaException ignored) { }
        }
    }

    Map<String, Object> runOnDevice(Map<String, Object> d, String runId) {
        Map<String, Object> r = db.one("SELECT * FROM runs WHERE id = ?", runId);
        if (r == null || !d.get("id").equals(r.get("device_id"))) throw notFound("Run");
        return r;
    }

    @SuppressWarnings("unchecked")
    public void appendEvents(Map<String, Object> d, String runId, List<Object> events) {
        Map<String, Object> r = runOnDevice(d, runId);
        if (!"running".equals(r.get("status"))) return;
        db.tx(() -> {
            for (Object o : events) {
                Map<String, Object> e = (Map<String, Object>) o;
                Object seq = e.get("seq");
                if (!(seq instanceof Number)) continue;
                if (db.one("SELECT seq FROM run_events WHERE run_id = ? AND seq = ?", runId, ((Number) seq).longValue()) != null) continue;
                String json = JsonLite.write(e);
                db.update("INSERT INTO run_events (run_id, seq, event) VALUES (?,?,?)", runId, ((Number) seq).longValue(), json.length() > 19000 ? json.substring(0, 19000) : json);
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    public void finish(Map<String, Object> d, String runId, Map<String, Object> result) {
        Map<String, Object> r = runOnDevice(d, runId);
        if (!"running".equals(r.get("status"))) return;
        String status = String.valueOf(result.getOrDefault("status", "failed"));
        if (!Set.of("success", "failed", "stopped").contains(status)) status = "failed";
        Instant started = r.get("started") == null ? now() : Instant.parse(r.get("started").toString());
        // the run's AI calls are recorded before it shows as finished, so whoever waits for the status sees them
        if (result.get("ai_calls") instanceof List<?> calls) platform.recordAiCalls(r, calls);
        db.update("UPDATE runs SET status = ?, finished = ?, duration_ms = ?, error = ?, outputs = ?, steps = ? WHERE id = ?",
                status, now(), Duration.between(started, now()).toMillis(), result.get("error") == null ? null : JsonLite.write(result.get("error")),
                JsonLite.write(result.getOrDefault("outputs", Map.of())), result.get("stepsExecuted") instanceof Number n ? n.intValue() : null, runId);
        audit.record("agent:" + d.get("name"), "run.finish", "run", runId, Map.of("status", status), "ok", null);
        platform.itemFinished(runId, status, result.get("error"), result.get("outputs"));
        try { workflows.onRunFinished(r, status, result); } catch (RuntimeException e) { audit.record("workflow", "workflow.resume", "run", runId, String.valueOf(e.getMessage()), "failed", null); }
        if (r.get("callback_url") != null) callback((String) r.get("callback_url"), runId, status, result);
    }

    /** Posts the run result to the caller's callback URL in the background (3 attempts); failures are audited, never raised. */
    void callback(String url, String runId, String status, Map<String, Object> result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId); body.put("status", status);
        body.put("outputs", result.getOrDefault("outputs", Map.of())); body.put("error", result.get("error"));
        Thread t = new Thread(() -> {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    var res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(JsonLite.write(body))).build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
                    if (res.statusCode() < 300) { audit.record("system", "run.callback", "run", runId, Map.of("status", res.statusCode()), "ok", null); return; }
                } catch (Exception ignored) { /* retry */ }
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException e) { return; }
            }
            audit.record("system", "run.callback", "run", runId, Map.of("url", url), "failed", null);
        }, "ooa-callback");
        t.setDaemon(true);
        t.start();
    }

    public void control(User by, String runId, String action, String ip) {
        require(by, "runs.stop", "control runs");
        Map<String, Object> r = db.one("SELECT status FROM runs WHERE id = ?", runId);
        if (r == null) throw notFound("Run");
        switch (action) {
            case "stop" -> {
                if ("queued".equals(r.get("status"))) db.update("UPDATE runs SET status = 'stopped', finished = ?, error = ? WHERE id = ? AND status = 'queued'",
                        now(), JsonLite.write(Map.of("code", "RUN.CANCELLED", "message", "Stopped by " + by.username() + " before it started")), runId);
                else db.update("UPDATE runs SET cancel_requested = TRUE WHERE id = ?", runId);
            }
            case "pause" -> db.update("UPDATE runs SET pause_requested = TRUE WHERE id = ?", runId);
            case "resume" -> db.update("UPDATE runs SET pause_requested = FALSE WHERE id = ?", runId);
            default -> throw new OoaException("CR.BAD_INPUT", "Unknown action " + action);
        }
        Map<String, Object> dev = db.one("SELECT device_id FROM runs WHERE id = ?", runId);
        if (dev != null && dev.get("device_id") != null) wake((String) dev.get("device_id"), true);
        audit.record(by.username(), "run." + action, "run", runId, null, "ok", ip);
    }

    public List<Map<String, Object>> runs(User by, String filter, int limit) {
        require(by, "runs.view", "view runs");
        String where = "active".equals(filter) ? "WHERE r.status IN ('queued', 'running')" : "history".equals(filter) ? "WHERE r.status IN ('success', 'failed', 'stopped')" : "";
        return db.query("SELECT r.id, r.bot_path, r.version, r.device_id, d.name AS device_name, r.status, r.requested_by, r.run_as, r.run_mode, r.source, r.created, r.started, r.finished, r.duration_ms, " +
                "r.error, r.pause_requested, r.cancel_requested, r.steps FROM runs r LEFT JOIN devices d ON d.id = r.device_id " + where + " ORDER BY r.created DESC LIMIT " + Math.max(1, Math.min(500, limit)));
    }

    /**
     * Runs with filters (filter=active|history, status, bot, device, user, source, q, from, to) and paging (offset, limit).
     * Running runs carry their progress: steps finished, total steps and the step running now.
     */
    public Map<String, Object> runsPage(User by, Map<String, String> q) {
        require(by, "runs.view", "view runs");
        List<String> where = new ArrayList<>();
        List<Object> p = new ArrayList<>();
        String filter = q.getOrDefault("filter", "all");
        if ("active".equals(filter)) where.add("r.status IN ('queued', 'running')");
        else if ("history".equals(filter)) where.add("r.status IN ('success', 'failed', 'stopped')");
        String status = q.get("status");
        if (status != null && Set.of("queued", "running", "success", "failed", "stopped").contains(status)) { where.add("r.status = ?"); p.add(status); }
        for (String[] f : new String[][] {{"bot", "r.bot_path"}, {"device", "r.device_id"}, {"user", "r.requested_by"}}) {
            String v = q.get(f[0]);
            if (v != null && !v.isBlank()) { where.add(f[1] + " = ?"); p.add(v); }
        }
        String source = q.get("source");
        if (source != null && !source.isBlank()) { where.add("(r.source = ? OR r.source LIKE ?)"); p.add(source); p.add(source + ":%"); }
        String text = q.get("q");
        if (text != null && !text.isBlank()) {
            String like = "%" + text.toLowerCase(Locale.ROOT) + "%";
            where.add("(LOWER(r.bot_path) LIKE ? OR LOWER(r.id) LIKE ? OR LOWER(COALESCE(r.error, '')) LIKE ? OR LOWER(r.requested_by) LIKE ?)");
            p.add(like); p.add(like); p.add(like); p.add(like);
        }
        Instant from = instant(q.get("from"), false), to = instant(q.get("to"), true);
        if (from != null) { where.add("r.created >= ?"); p.add(from); }
        if (to != null) { where.add("r.created < ?"); p.add(to); }
        String w = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        int limit = Math.max(1, Math.min(500, intOr(q.get("limit"), 100))), offset = Math.max(0, intOr(q.get("offset"), 0));
        long total = count("SELECT COUNT(*) AS n FROM runs r" + w, p.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT r.id, r.bot_path, r.version, r.device_id, d.name AS device_name, r.status, r.requested_by, r.run_as, r.run_mode, r.source, r.created, r.started, r.finished, " +
                "r.duration_ms, r.error, r.pause_requested, r.cancel_requested, r.steps FROM runs r LEFT JOIN devices d ON d.id = r.device_id" + w +
                " ORDER BY r.created DESC LIMIT " + limit + " OFFSET " + offset, p.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            if ("running".equals(r.get("status"))) m.put("progress", progress((String) r.get("id")));
            out.add(m);
        }
        return Map.of("runs", out, "total", total, "offset", offset, "limit", limit);
    }

    static int intOr(String v, int dflt) { try { return v == null ? dflt : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; } }

    /** An instant, or a date (YYYY-MM-DD, UTC); for an end date the day is included. */
    static Instant instant(String v, boolean endOfDay) {
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v.trim()); } catch (RuntimeException ignored) { }
        try { LocalDate d = LocalDate.parse(v.trim()); return (endOfDay ? d.plusDays(1) : d).atStartOfDay(ZoneOffset.UTC).toInstant(); }
        catch (RuntimeException e) { throw new OoaException("CR.BAD_INPUT", "Not a date: " + v, "Use YYYY-MM-DD."); }
    }

    /** Steps finished, total steps (top-level bot) and the step running now, from the run's live events. */
    @SuppressWarnings("unchecked")
    Map<String, Object> progress(String runId) {
        int total = 0, finished = 0;
        String stepId = null, action = null;
        for (Map<String, Object> row : db.query("SELECT event FROM run_events WHERE run_id = ? ORDER BY seq", runId)) {
            Map<String, Object> e;
            try { e = (Map<String, Object>) JsonLite.parse((String) row.get("event")); } catch (RuntimeException bad) { continue; }
            Map<String, Object> d = e.get("data") instanceof Map ? (Map<String, Object>) e.get("data") : Map.of();
            String type = String.valueOf(e.get("type"));
            if (type.equals("run.started") && total == 0 && d.get("totalSteps") instanceof Number n && (!(d.get("depth") instanceof Number dp) || dp.intValue() == 0)) total = n.intValue();
            else if (type.equals("step.started")) { stepId = (String) e.get("stepId"); action = String.valueOf(d.get("action")); }
            else if (type.equals("step.finished")) finished++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("finished", finished);
        m.put("step_id", stepId);
        m.put("action", action);
        return m;
    }

    /** Runs the bot again on the same device with the same inputs (the current production version). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rerun(User by, String runId, String ip) {
        Map<String, Object> r = db.one("SELECT bot_path, device_id, inputs FROM runs WHERE id = ?", runId);
        if (r == null) throw notFound("Run");
        Map<String, Object> inputs = r.get("inputs") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) r.get("inputs"));
        return startRun(by.username(), by.role(), (String) r.get("bot_path"), (String) r.get("device_id"), inputs, "rerun", ip);
    }

    /** Command names for the console's run log, e.g. "ooa.excel/open" -> "Open workbook". */
    @SuppressWarnings("unchecked")
    public Map<String, Object> actionNames() {
        Map<String, Object> names = new TreeMap<>();
        for (Map<String, Object> m : registry.allManifests())
            for (Object a : (List<Object>) m.getOrDefault("actions", List.of())) {
                Map<String, Object> am = (Map<String, Object>) a;
                names.put(m.get("id") + "/" + am.get("id"), am.get("name"));
            }
        return names;
    }

    public Map<String, Object> run(User by, String runId, long afterSeq) {
        require(by, "runs.view", "view runs");
        Map<String, Object> r = db.one("SELECT r.*, d.name AS device_name FROM runs r LEFT JOIN devices d ON d.id = r.device_id WHERE r.id = ?", runId);
        if (r == null) throw notFound("Run");
        List<Object> events = new ArrayList<>();
        for (Map<String, Object> e : db.query("SELECT event FROM run_events WHERE run_id = ? AND seq > ? ORDER BY seq LIMIT 2000", runId, afterSeq)) events.add(JsonLite.parse((String) e.get("event")));
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("events", events);
        return out;
    }

    /** Fails running jobs whose device stopped reporting (2 minutes), and marks schedules due. Called by the server clock. */
    public void reap() {
        try { platform.purgeIfDue(); } catch (RuntimeException ignored) { /* retention never stops the clock */ }
        Instant cutoff = now().minus(Duration.ofMinutes(2));
        for (Map<String, Object> r : db.query("SELECT r.id FROM runs r JOIN devices d ON d.id = r.device_id WHERE r.status = 'running' AND (d.last_seen IS NULL OR d.last_seen < ?)", cutoff)) {
            db.update("UPDATE runs SET status = 'failed', finished = ?, error = ? WHERE id = ? AND status = 'running'", now(),
                    JsonLite.write(Map.of("code", "AGENT.LOST", "message", "The runner stopped reporting while the agent was running", "suggestedFix", "Check the runner is on and the One Runner is running.")), r.get("id"));
            audit.record("system", "run.lost", "run", (String) r.get("id"), null, "failed", null);
            platform.itemFinished((String) r.get("id"), "failed", Map.of("code", "AGENT.LOST"), null);
        }
        platform.disableInactiveUsers();
        try { expireTemporaryRunners(); } catch (RuntimeException ignored) { /* retried at the next tick */ }
        try { audit.checkpointIfDue(); } catch (RuntimeException ignored) { /* retried at the next tick */ }
        try { backups.tickIfDue(); } catch (RuntimeException ignored) { /* retried at the next tick */ }
        try { licensing.mailIfDue(); } catch (RuntimeException ignored) { /* retried at the next tick */ }
    }

    // ===================== schedules =====================

    static Instant nextRun(Map<String, Object> s, Instant after) {
        ZoneId zone = ZoneId.of(String.valueOf(s.getOrDefault("time_zone", "UTC")));
        String kind = String.valueOf(s.get("kind"));
        if (kind.equals("every")) return after.plus(Duration.ofMinutes(((Number) s.get("every_minutes")).longValue()));
        LocalTime at = LocalTime.parse(String.valueOf(s.get("at_time")));
        Set<DayOfWeek> days = new HashSet<>();
        if (kind.equals("weekly")) for (String d : String.valueOf(s.get("days")).split(",")) days.add(DayOfWeek.valueOf(switch (d.trim().toUpperCase(Locale.ROOT)) {
            case "MON" -> "MONDAY"; case "TUE" -> "TUESDAY"; case "WED" -> "WEDNESDAY"; case "THU" -> "THURSDAY"; case "FRI" -> "FRIDAY"; case "SAT" -> "SATURDAY"; default -> "SUNDAY"; }));
        ZonedDateTime t = after.atZone(zone).with(at).withSecond(0).withNano(0);
        for (int i = 0; i < 15; i++) {
            if (t.toInstant().isAfter(after) && (days.isEmpty() || days.contains(t.getDayOfWeek()))) return t.toInstant();
            t = t.plusDays(1).with(at);
        }
        throw new OoaException("CR.BAD_SCHEDULE", "No next run could be computed");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createSchedule(User by, Map<String, Object> s, String ip) {
        require(by, "schedules.manage", "manage schedules");
        String kind = String.valueOf(s.get("kind"));
        if (!Set.of("every", "daily", "weekly").contains(kind)) throw new OoaException("CR.BAD_SCHEDULE", "kind must be every, daily or weekly");
        if (kind.equals("every") && (!(s.get("every_minutes") instanceof Number n) || n.intValue() < 1)) throw new OoaException("CR.BAD_SCHEDULE", "every_minutes must be at least 1");
        if (!kind.equals("every") && !String.valueOf(s.get("at_time")).matches("^([01]\\d|2[0-3]):[0-5]\\d$")) throw new OoaException("CR.BAD_SCHEDULE", "at_time must look like 08:30");
        if (kind.equals("weekly") && !String.valueOf(s.get("days")).matches("^(MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*$"))
            throw new OoaException("CR.BAD_SCHEDULE", "days must be like MON,WED,FRI");
        String tz = String.valueOf(s.getOrDefault("time_zone", "UTC"));
        try { ZoneId.of(tz); } catch (DateTimeException e) { throw new OoaException("CR.BAD_SCHEDULE", "Unknown time zone " + tz, "Example: Asia/Kolkata, Europe/London, UTC"); }
        content(String.valueOf(s.get("bot_path")), "production");
        folders.require(by, String.valueOf(s.get("bot_path")), "run", "schedule agents");
        String pool = s.get("pool_id") instanceof String ps && !ps.isBlank() ? ps : null;
        String runAs = s.get("run_as") instanceof String ra && !ra.isBlank() ? ra : null;
        if (runAs != null) checkRunAs(runAs, pool == null && !(s.get("device_id") instanceof String dd && !dd.isBlank()));
        if (pool != null) { if (db.one("SELECT id FROM device_pools WHERE id = ?", pool) == null) throw notFound("Runner pool"); }
        else if (runAs == null || s.get("device_id") instanceof String dd2 && !dd2.isBlank()) { if (db.one("SELECT id FROM devices WHERE id = ?", s.get("device_id")) == null) throw notFound("Runner"); }
        String cal = s.get("holiday_calendar") instanceof String cs && !cs.isBlank() ? cs : null;
        if (cal != null && db.one("SELECT name FROM holiday_calendars WHERE name = ?", cal) == null) throw notFound("Holiday calendar");
        String sid = id("sch");
        Map<String, Object> row = new HashMap<>(s);
        row.put("time_zone", tz);
        Instant next = nextRun(row, now());
        db.update("INSERT INTO schedules (id, name, bot_path, device_id, kind, every_minutes, at_time, days, time_zone, inputs, enabled, next_run, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sid, String.valueOf(s.getOrDefault("name", "Schedule")), s.get("bot_path"), s.get("device_id"), kind, s.get("every_minutes") instanceof Number n ? n.intValue() : null,
                kind.equals("every") ? null : s.get("at_time"), kind.equals("weekly") ? s.get("days") : null, tz, JsonLite.write(s.getOrDefault("inputs", Map.of())), true, next, by.username());
        db.update("UPDATE schedules SET pool_id = ?, holiday_calendar = ?, run_as = ? WHERE id = ?", pool, cal, runAs, sid);
        audit.record(by.username(), "schedule.create", "schedule", sid, Map.of("bot", s.get("bot_path"), "kind", kind), "ok", ip);
        return Map.of("id", sid, "next_run", next.toString());
    }

    /** Rename a schedule, change its holiday calendar, or hand it to another owner (whose permissions its runs then use). */
    public void updateSchedule(User by, String sid, Map<String, Object> changes, String ip) {
        require(by, "schedules.manage", "manage schedules");
        if (db.one("SELECT id FROM schedules WHERE id = ?", sid) == null) throw notFound("Schedule");
        if (changes.get("name") instanceof String n) {
            if (n.isBlank() || n.length() > 200) throw new OoaException("CR.BAD_INPUT", "Schedule names have 1 to 200 characters");
            db.update("UPDATE schedules SET name = ? WHERE id = ?", n.trim(), sid);
        }
        if (changes.containsKey("holiday_calendar")) {
            String cal = changes.get("holiday_calendar") instanceof String c && !c.isBlank() ? c : null;
            if (cal != null && db.one("SELECT name FROM holiday_calendars WHERE name = ?", cal) == null) throw notFound("Holiday calendar");
            db.update("UPDATE schedules SET holiday_calendar = ? WHERE id = ?", cal, sid);
        }
        if (changes.get("owner") instanceof String o) {
            if (db.one("SELECT id FROM users WHERE username = ? AND enabled = TRUE", o) == null) throw notFound("User " + o);
            db.update("UPDATE schedules SET created_by = ? WHERE id = ?", o, sid);
        }
        if (changes.containsKey("run_as")) {
            String ra = changes.get("run_as") instanceof String r && !r.isBlank() ? r : null;
            Map<String, Object> cur = db.one("SELECT device_id, pool_id FROM schedules WHERE id = ?", sid);
            if (ra != null) checkRunAs(ra, cur.get("device_id") == null && cur.get("pool_id") == null);
            else if (cur.get("device_id") == null && cur.get("pool_id") == null) throw new OoaException("CR.BAD_INPUT", "The schedule has no runner of its own; keep a run-as user or set a runner");
            db.update("UPDATE schedules SET run_as = ? WHERE id = ?", ra, sid);
        }
        if (changes.containsKey("enabled")) setScheduleEnabled(by, sid, Boolean.TRUE.equals(changes.get("enabled")), ip);
        audit.record(by.username(), "schedule.update", "schedule", sid, changes, "ok", ip);
    }

    public List<Map<String, Object>> schedules(User by) { require(by, "schedules.view", "view schedules"); return db.query("SELECT * FROM schedules ORDER BY name"); }

    public Map<String, Object> schedule(User by, String sid) {
        require(by, "schedules.view", "view schedules");
        Map<String, Object> s = db.one("SELECT * FROM schedules WHERE id = ?", sid);
        if (s == null) throw notFound("Schedule");
        return s;
    }

    /** A run-as user must exist and be enabled; with no runner given, they need a default runner. */
    void checkRunAs(String user, boolean needsDefault) {
        if (db.one("SELECT id FROM users WHERE username = ? AND enabled = TRUE", user) == null) throw notFound("Run-as user " + user);
        if (needsDefault && db.one("SELECT device_id FROM user_devices WHERE username = ?", user) == null)
            throw new OoaException("CR.NO_DEFAULT_RUNNER", user + " has no default runner", "Set one in Users, or choose a runner.");
    }

    /** Several schedules at once: enable, disable or hand to another owner. Returns how many changed. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> bulkSchedules(User by, Map<String, Object> b, String ip) {
        require(by, "schedules.manage", "manage schedules");
        List<String> ids = csv(b.get("ids"));
        if (ids.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Give the schedule ids");
        String action = String.valueOf(b.get("action"));
        String owner = b.get("owner") instanceof String o ? o : null;
        if (action.equals("owner") && (owner == null || db.one("SELECT id FROM users WHERE username = ? AND enabled = TRUE", owner) == null)) throw notFound("User " + owner);
        int n = 0;
        for (String id : ids) {
            Map<String, Object> s = db.one("SELECT * FROM schedules WHERE id = ?", id);
            if (s == null) continue;
            switch (action) {
                case "enable" -> n += db.update("UPDATE schedules SET enabled = TRUE, next_run = ? WHERE id = ?", nextRun(s, now()), id);
                case "disable" -> n += db.update("UPDATE schedules SET enabled = FALSE, next_run = NULL WHERE id = ?", id);
                case "owner" -> n += db.update("UPDATE schedules SET created_by = ? WHERE id = ?", owner, id);
                default -> throw new OoaException("CR.BAD_INPUT", "The action is enable, disable or owner");
            }
        }
        audit.record(by.username(), "schedule.bulk_" + action, "schedule", null, Map.of("ids", ids, "changed", n, "owner", owner == null ? "" : owner), "ok", ip);
        return Map.of("changed", n);
    }

    /** Starts a schedule's bot now, with its device and inputs, as the person who asked. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runScheduleNow(User by, String sid, String ip) {
        Map<String, Object> s = db.one("SELECT * FROM schedules WHERE id = ?", sid);
        if (s == null) throw notFound("Schedule");
        Map<String, Object> inputs = s.get("inputs") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) s.get("inputs"));
        return startRun(by.username(), by.role(), (String) s.get("bot_path"), (String) s.get("device_id"), (String) s.get("pool_id"), inputs, "schedule:" + sid, ip, 0, null, (String) s.get("run_as"), null);
    }

    public void setScheduleEnabled(User by, String sid, boolean en, String ip) {
        require(by, "schedules.manage", "manage schedules");
        Map<String, Object> s = db.one("SELECT * FROM schedules WHERE id = ?", sid);
        if (s == null) throw notFound("Schedule");
        db.update("UPDATE schedules SET enabled = ?, next_run = ? WHERE id = ?", en, en ? nextRun(s, now()) : null, sid);
        audit.record(by.username(), en ? "schedule.enable" : "schedule.disable", "schedule", sid, null, "ok", ip);
    }

    public void deleteSchedule(User by, String sid, String ip) {
        require(by, "schedules.manage", "manage schedules");
        if (db.update("DELETE FROM schedules WHERE id = ?", sid) == 0) throw notFound("Schedule");
        audit.record(by.username(), "schedule.delete", "schedule", sid, null, "ok", ip);
    }

    /** Starts due schedules; runs carry the schedule creator as requester (so vault permissions follow that person). */
    @SuppressWarnings("unchecked")
    public int tickSchedules() {
        int started = 0;
        for (Map<String, Object> s : db.query("SELECT * FROM schedules WHERE enabled = TRUE AND next_run <= ?", now())) {
            Instant next = nextRun(s, now());
            if (db.update("UPDATE schedules SET last_run = ?, next_run = ? WHERE id = ? AND next_run = ?", now(), next, s.get("id"), Instant.parse(s.get("next_run").toString())) != 1) continue;
            if (platform.isHoliday(s, now())) { audit.record("scheduler", "schedule.skip_holiday", "schedule", (String) s.get("id"), s.get("holiday_calendar"), "ok", null); continue; }
            Map<String, Object> creator = db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", s.get("created_by"));
            if (creator == null) { audit.record("scheduler", "schedule.fire", "schedule", (String) s.get("id"), "owner " + s.get("created_by") + " is disabled or deleted", "failed", null); continue; }
            try {
                startRun((String) s.get("created_by"), (String) creator.get("role"), (String) s.get("bot_path"), (String) s.get("device_id"), (String) s.get("pool_id"),
                        (Map<String, Object>) JsonLite.parse((String) s.get("inputs")), "schedule:" + s.get("id"), null, 0, null, (String) s.get("run_as"), null);
                started++;
            } catch (OoaException e) { audit.record("scheduler", "schedule.fire", "schedule", (String) s.get("id"), e.getMessage(), "failed", null); }
        }
        return started;
    }

    // ===================== triggers =====================

    public Map<String, Object> createTrigger(User by, Map<String, Object> t, String ip) {
        require(by, "triggers.manage", "manage triggers");
        String kind = String.valueOf(t.get("kind"));
        TriggerKinds.kind(kind);
        Map<String, Object> config = TriggerKinds.validate(kind, t);
        content(String.valueOf(t.get("bot_path")), "production");
        folders.require(by, String.valueOf(t.get("bot_path")), "run", "set triggers for agents");
        if (db.one("SELECT id FROM devices WHERE id = ?", t.get("device_id")) == null) throw notFound("Runner");
        String name = String.valueOf(t.getOrDefault("name", "Trigger"));
        if (name.isBlank() || name.length() > 200) throw new OoaException("CR.BAD_INPUT", "Trigger names have 1 to 200 characters");
        boolean folderKind = TriggerKinds.FOLDER_KINDS.contains(kind);
        String pattern = t.get("pattern") == null || String.valueOf(t.get("pattern")).isBlank() ? "*" : String.valueOf(t.get("pattern"));
        String tid = id("trg");
        String secret = kind.equals("webhook") ? Security.token(24) : null;
        db.update("INSERT INTO triggers (id, name, bot_path, device_id, kind, folder, pattern, secret_hash, enabled, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                tid, name, t.get("bot_path"), t.get("device_id"), kind, folderKind ? String.valueOf(t.get("folder")).trim() : null, folderKind ? pattern : null,
                secret == null ? null : Security.sha256(secret), true, by.username());
        String runAs = t.get("run_as") instanceof String ra && !ra.isBlank() ? ra : null;
        if (runAs != null) checkRunAs(runAs, false);
        db.update("UPDATE triggers SET config = ?, fire_count = 0, run_as = ? WHERE id = ?", JsonLite.write(config), runAs, tid);
        audit.record(by.username(), "trigger.create", "trigger", tid, Map.of("kind", kind, "bot", t.get("bot_path")), "ok", ip);
        Map<String, Object> out = new LinkedHashMap<>(Map.of("id", tid));
        if (secret != null) { out.put("secret", secret); out.put("url", "/api/hooks/" + tid); }
        return out;
    }

    /** Triggers with their settings (vault references only, never secrets) and monitoring: last fired, fire count, last error. */
    public List<Map<String, Object>> triggers(User by) {
        require(by, "triggers.view", "view triggers");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : db.query("SELECT id, name, bot_path, device_id, kind, folder, pattern, enabled, created_by, run_as, config, last_fired, fire_count, last_error FROM triggers ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("config", TriggerKinds.publicConfig(TriggerPollers.config(t)));
            m.put("fire_count", t.get("fire_count") == null ? 0 : t.get("fire_count"));
            TriggerKinds.Kind k = TriggerKinds.KINDS.get(String.valueOf(t.get("kind")));
            m.put("evaluated_on", k == null ? "unknown" : k.where().name().toLowerCase(Locale.ROOT));
            m.put("windows_only", k != null && k.windowsOnly());
            m.put("status", !Boolean.TRUE.equals(t.get("enabled")) ? "paused" : t.get("last_error") != null ? "error" : "active");
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> trigger(User by, String tid) {
        return triggers(by).stream().filter(t -> tid.equals(t.get("id"))).findFirst().orElseThrow(() -> notFound("Trigger"));
    }

    /** Rename a trigger, hand it to another owner (whose permissions it fires with), or set the user its runs run as. */
    public void updateTrigger(User by, String tid, Map<String, Object> changes, String ip) {
        require(by, "triggers.manage", "manage triggers");
        if (db.one("SELECT id FROM triggers WHERE id = ?", tid) == null) throw notFound("Trigger");
        if (changes.get("name") instanceof String n) {
            if (n.isBlank() || n.length() > 200) throw new OoaException("CR.BAD_INPUT", "Trigger names have 1 to 200 characters");
            db.update("UPDATE triggers SET name = ? WHERE id = ?", n.trim(), tid);
        }
        if (changes.get("owner") instanceof String o) {
            if (db.one("SELECT id FROM users WHERE username = ? AND enabled = TRUE", o) == null) throw notFound("User " + o);
            db.update("UPDATE triggers SET created_by = ? WHERE id = ?", o, tid);
        }
        if (changes.containsKey("run_as")) {
            String ra = changes.get("run_as") instanceof String r && !r.isBlank() ? r : null;
            if (ra != null) checkRunAs(ra, false);
            db.update("UPDATE triggers SET run_as = ? WHERE id = ?", ra, tid);
        }
        audit.record(by.username(), "trigger.update", "trigger", tid, changes, "ok", ip);
    }

    /** Pause (false) or resume (true) a trigger; resuming clears the last error and polls again soon. */
    public void setTriggerEnabled(User by, String tid, boolean en, String ip) {
        require(by, "triggers.manage", "manage triggers");
        if (db.update("UPDATE triggers SET enabled = ? WHERE id = ?", en, tid) == 0) throw notFound("Trigger");
        if (en) { db.update("UPDATE triggers SET last_error = NULL WHERE id = ?", tid); triggerPollers.reset(tid); }
        audit.record(by.username(), en ? "trigger.enable" : "trigger.disable", "trigger", tid, null, "ok", ip);
    }

    public void deleteTrigger(User by, String tid, String ip) {
        require(by, "triggers.manage", "manage triggers");
        if (db.update("DELETE FROM triggers WHERE id = ?", tid) == 0) throw notFound("Trigger");
        audit.record(by.username(), "trigger.delete", "trigger", tid, null, "ok", ip);
    }

    /** Starts the trigger's agent with the event values it declares as inputs, and records the firing (or why it failed). */
    @SuppressWarnings("unchecked")
    Map<String, Object> fireTrigger(Map<String, Object> t, Map<String, Object> event, String source, String ip) {
        try {
            Map<String, Object> flow = (Map<String, Object>) content((String) t.get("bot_path"), "production").get("flow");
            Set<String> declared = new HashSet<>();
            for (Object v : (List<Object>) flow.getOrDefault("variables", List.of())) if (java.util.Arrays.asList("input", "in_out").contains(((Map<String, Object>) v).get("direction"))) declared.add(String.valueOf(((Map<String, Object>) v).get("name")));
            Map<String, Object> in = new LinkedHashMap<>();
            event.forEach((k, v) -> { if (declared.contains(k)) in.put(k, v); });
            Map<String, Object> creator = db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", t.get("created_by"));
            Map<String, Object> r = startRun((String) t.get("created_by"), creator == null ? "viewer" : (String) creator.get("role"), (String) t.get("bot_path"), (String) t.get("device_id"), null,
                    in, source, ip, 0, null, (String) t.get("run_as"), null);
            db.update("UPDATE triggers SET last_fired = ?, fire_count = COALESCE(fire_count, 0) + 1, last_error = NULL WHERE id = ?", now(), t.get("id"));
            return r;
        } catch (OoaException e) {
            String msg = e.getMessage() == null ? e.code() : e.getMessage();
            db.update("UPDATE triggers SET last_error = ? WHERE id = ?", msg.length() > 1900 ? msg.substring(0, 1900) : msg, t.get("id"));
            throw e;
        }
    }

    public Map<String, Object> webhook(String tid, String secret, Object payload, String ip) {
        Map<String, Object> t = db.one("SELECT * FROM triggers WHERE id = ? AND kind = 'webhook'", tid);
        if (t == null || !Security.constantEquals(secret == null ? "" : Security.sha256(secret), (String) t.get("secret_hash"))) {
            audit.record("webhook", "trigger.fire", "trigger", tid, null, "denied", ip);
            throw new OoaException("CR.FORBIDDEN", "Unknown webhook or wrong secret");
        }
        if (!Boolean.TRUE.equals(t.get("enabled"))) throw new OoaException("CR.TRIGGER_DISABLED", "This webhook is disabled");
        return fireTrigger(t, payload == null ? Map.of() : Map.of("payload", payload), "webhook:" + tid, ip);
    }

    public Map<String, Object> fileFired(Map<String, Object> d, String tid, String file) {
        return localFired(d, tid, Map.of("path", String.valueOf(file)));
    }

    /**
     * A runner reports a local event (file or folder, window, process, service, hotkey) for one of its triggers. The
     * agent receives trigger_event (all details) and, by kind, trigger_file, trigger_window, trigger_process,
     * trigger_service or trigger_hotkey.
     */
    public Map<String, Object> localFired(Map<String, Object> d, String tid, Map<String, Object> event) {
        Map<String, Object> t = db.one("SELECT * FROM triggers WHERE id = ? AND device_id = ? AND enabled = TRUE", tid, d.get("id"));
        if (t == null) throw notFound("Trigger");
        String kind = String.valueOf(t.get("kind"));
        TriggerKinds.Kind k = TriggerKinds.KINDS.get(kind);
        if (k == null || k.where() != TriggerKinds.Where.RUNNER) throw new OoaException("CR.BAD_TRIGGER", "Trigger " + tid + " is not a runner trigger");
        Map<String, Object> ev = new LinkedHashMap<>(event == null ? Map.of() : event);
        ev.put("kind", kind);
        ev.put("trigger_id", tid);
        ev.put("trigger_name", t.get("name"));
        ev.putIfAbsent("time", now().toString());
        String input = kind.startsWith("window") ? "trigger_window" : kind.startsWith("process") ? "trigger_process" : kind.startsWith("service") ? "trigger_service"
                : kind.equals("hotkey") ? "trigger_hotkey" : "trigger_file";
        Object main = ev.get(switch (input) { case "trigger_window" -> "title"; case "trigger_process" -> "process"; case "trigger_service" -> "service"; case "trigger_hotkey" -> "hotkey"; default -> "path"; });
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("trigger_event", ev);
        if (main != null) in.put(input, String.valueOf(main));
        return fireTrigger(t, in, kind.equals("file") ? "file:" + tid : "trigger:" + tid, null);
    }

    /** A runner reports that it cannot watch a trigger (folder missing, hotkey taken, not supported), or that it can again (null). */
    public void localTriggerStatus(Map<String, Object> d, String tid, String error) {
        String e = error == null || error.isBlank() ? null : error.length() > 1900 ? error.substring(0, 1900) : error;
        db.update("UPDATE triggers SET last_error = ? WHERE id = ? AND device_id = ?", e, tid, d.get("id"));
    }

    // ===================== lockers, credentials, global values =====================

    static List<String> csv(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o).trim());
        else if (v != null) for (String s : String.valueOf(v).split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    boolean ownsLocker(User u, Map<String, Object> locker) {
        return can(u, "lockers.manage") || csv(locker.get("owners")).contains(u.username());
    }

    /**
     * The caller's place in a vault folder: owner (everything, members included), manager (every credential),
     * participant (sees the folder; adds credentials and changes their own), consumer (agents use its credentials),
     * or null. Managers, participants and consumers can be users, roles or teams.
     */
    String lockerRole(User u, Map<String, Object> locker) {
        if (ownsLocker(u, locker)) return "owner";
        List<String> mine = teams.principalsOf(u.username(), u.role());
        if (csv(locker.get("managers")).stream().anyMatch(mine::contains)) return "manager";
        if (csv(locker.get("participants")).stream().anyMatch(mine::contains)) return "participant";
        if (csv(locker.get("consumers")).stream().anyMatch(mine::contains)) return "consumer";
        return null;
    }

    /** Owners and managers change every credential; a participant creates credentials and changes the ones they own. */
    boolean canChangeCredential(User u, Map<String, Object> locker, String credential) {
        String r = lockerRole(u, locker);
        if ("owner".equals(r) || "manager".equals(r)) return true;
        if (!"participant".equals(r)) return false;
        Map<String, Object> meta = db.one("SELECT owner FROM credential_meta WHERE locker = ? AND name = ?", locker.get("name"), credential);
        boolean exists = meta != null || db.one("SELECT name FROM credentials WHERE locker = ? AND name = ? LIMIT 1", locker.get("name"), credential) != null;
        return !exists || (meta != null && u.username().equals(meta.get("owner")));
    }

    public void createLocker(User by, String name, String description, Object consumers, String ip) {
        if (!can(by, "lockers.manage") && !can(by, "lockers.own")) throw denied("create lockers");
        if (name == null || !NAME.matcher(name).matches()) throw new OoaException("CR.BAD_INPUT", "Vault folder names use letters, digits, . _ -");
        List<String> cons = csv(consumers);
        for (String c : cons) teams.checkPrincipal(c);
        db.update("INSERT INTO lockers (name, description, owners, consumers, created_by) VALUES (?,?,?,?,?)", name, description, by.username(), String.join(",", cons), by.username());
        audit.record(by.username(), "locker.create", "locker", name, Map.of("consumers", cons), "ok", ip);
    }

    public void updateLocker(User by, String name, Object consumers, Object owners, String ip) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", name);
        if (l == null) throw notFound("Vault folder");
        if (!ownsLocker(by, l)) throw denied("change this vault folder");
        if (consumers != null) db.update("UPDATE lockers SET consumers = ? WHERE name = ?", String.join(",", csv(consumers)), name);
        if (owners != null) { List<String> o = csv(owners); if (o.isEmpty()) throw new OoaException("CR.BAD_INPUT", "A vault folder needs at least one owner"); db.update("UPDATE lockers SET owners = ? WHERE name = ?", String.join(",", o), name); }
        audit.record(by.username(), "locker.update", "locker", name, Map.of("consumers", String.valueOf(consumers), "owners", String.valueOf(owners)), "ok", ip);
    }

    public List<Map<String, Object>> lockers(User by) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : db.query("SELECT * FROM lockers ORDER BY name")) {
            String role = lockerRole(by, l);
            if (role == null) continue;
            Map<String, Object> m = new LinkedHashMap<>(l);
            m.remove("external_secret");
            m.put("external", l.get("external") == null ? null : JsonLite.parse((String) l.get("external")));
            m.put("can_manage", role.equals("owner"));
            m.put("my_role", role);
            for (String k : List.of("managers", "participants")) m.put(k, csv(l.get(k)));
            List<Object> creds = new ArrayList<>();
            for (Map<String, Object> c : db.query("SELECT name, attribute, updated, updated_by FROM credentials WHERE locker = ? ORDER BY name, attribute", l.get("name"))) creds.add(c);
            m.put("credentials", creds);
            m.put("credential_info", vaultAdmin.credentialInfo(by, (String) l.get("name")));
            out.add(m);
        }
        return out;
    }

    /** Whether a user (directly, by role, or through a team) may use a vault folder's credentials in agents. */
    boolean consumes(String username, String role, Map<String, Object> locker) {
        List<String> c = csv(locker.get("consumers"));
        return teams.principalsOf(username, role).stream().anyMatch(c::contains);
    }

    public void setCredential(User by, String locker, String name, String attribute, String value, String ip) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", locker);
        if (l == null) throw notFound("Vault folder");
        if (!canChangeCredential(by, l, name)) throw denied("change this credential");
        if (!NAME.matcher(name == null ? "" : name).matches() || !NAME.matcher(attribute == null ? "" : attribute).matches()) throw new OoaException("CR.BAD_INPUT", "Credential and attribute names use letters, digits, . _ -");
        if (value == null || value.isEmpty()) throw new OoaException("CR.BAD_INPUT", "The value is empty");
        String ref = locker + "/" + name + "/" + attribute;
        if (vaultAdmin.userProvided(locker, name, attribute)) throw new OoaException("CR.BAD_INPUT", "'" + attribute + "' is user-provided: each person stores their own value (My value)");
        // the vault lock is held until the value is stored, so a key rotation never sees it half-way
        synchronized (vault) {
            String ct = vault.encrypt(value, ref);
            db.tx(() -> {
                if (db.update("UPDATE credentials SET ciphertext = ?, updated = ?, updated_by = ? WHERE locker = ? AND name = ? AND attribute = ?", ct, now(), by.username(), locker, name, attribute) == 0)
                    db.update("INSERT INTO credentials (locker, name, attribute, ciphertext, updated, updated_by) VALUES (?,?,?,?,?,?)", locker, name, attribute, ct, now(), by.username());
                // the person who creates a credential owns it
                if (db.one("SELECT name FROM credential_meta WHERE locker = ? AND name = ?", locker, name) == null)
                    db.update("INSERT INTO credential_meta (locker, name, owner, created) VALUES (?,?,?,?)", locker, name, by.username(), now());
                return null;
            });
        }
        audit.record(by.username(), "credential.set", "credential", ref, null, "ok", ip);
    }

    public void deleteCredential(User by, String locker, String name, String attribute, String ip) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", locker);
        if (l == null) throw notFound("Vault folder");
        if (!canChangeCredential(by, l, name)) throw denied("change this credential");
        db.update("DELETE FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", locker, name, attribute);
        audit.record(by.username(), "credential.delete", "credential", locker + "/" + name + "/" + attribute, null, "ok", ip);
    }

    /** Just-in-time secret for a running bot: the run must be running on this device and its requester must consume the locker. */
    /**
     * A credential read by a signed-in person for a check of their own (testing a model connection): only from vault
     * folders they consume, recorded in the audit trail with the purpose.
     */
    String credentialForUser(User by, String ref, String purpose) {
        String[] p = ref == null ? new String[0] : ref.split("/");
        if (p.length != 3) throw new OoaException("CR.BAD_INPUT", "The key is a vault reference: vault folder/credential/attribute");
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        if (l == null || !consumes(by.username(), by.role(), l)) {
            audit.record(by.username(), "credential.read", "credential", ref, Map.of("purpose", purpose), "denied", null);
            throw new OoaException("VAULT.DENIED", "You may not use vault folder '" + p[0] + "'", "Ask the vault folder owner to add you as a consumer.");
        }
        String v;
        if (l.get("external") != null) v = readExternal(l, p[1], p[2]);
        else if (vaultAdmin.userProvided(p[0], p[1], p[2])) {
            Map<String, Object> uv = db.one("SELECT ciphertext FROM credential_user_values WHERE locker = ? AND name = ? AND attribute = ? AND username = ?", p[0], p[1], p[2], by.username());
            if (uv == null) throw new OoaException("VAULT.NOT_FOUND", "You have not stored your own value for '" + ref + "'");
            v = vault.decrypt((String) uv.get("ciphertext"), VaultAdmin.userBound(ref, by.username()));
        } else {
            Map<String, Object> c = db.one("SELECT ciphertext FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", p[0], p[1], p[2]);
            if (c == null) throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found");
            v = vault.decrypt((String) c.get("ciphertext"), ref);
        }
        audit.record(by.username(), "credential.read", "credential", ref, Map.of("purpose", purpose), "ok", null);
        return v;
    }

    public String credentialForRun(Map<String, Object> d, String runId, String ref) {
        Map<String, Object> r0 = runOnDevice(d, runId);
        // a run-as user's run reads credentials as that user
        Map<String, Object> r = new HashMap<>(r0);
        if (r0.get("run_as") != null) r.put("requested_by", r0.get("run_as"));
        String[] p = ref == null ? new String[0] : ref.split("/");
        String actor = "agent:" + d.get("name") + " for " + r.get("requested_by");
        if (!"running".equals(r.get("status")) || p.length != 3) { audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "denied", null); throw new OoaException("VAULT.DENIED", "Credentials are only available to running agents"); }
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        Map<String, Object> u = db.one("SELECT role FROM users WHERE username = ?", r.get("requested_by"));
        if (l == null || u == null || !consumes((String) r.get("requested_by"), (String) u.get("role"), l)) {
            audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "denied", null);
            throw new OoaException("VAULT.DENIED", "'" + r.get("requested_by") + "' may not use vault folder '" + p[0] + "'", "Ask the vault folder owner to add the user or role as a consumer.");
        }
        if (l.get("external") != null) {
            // Linked to an external vault: same permission check and audit, value read live from the external vault.
            try {
                String v = readExternal(l, p[1], p[2]);
                audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId, "external", true), "ok", null);
                return v;
            } catch (OoaException e) {
                audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId, "external", true, "error", e.code()), "failed", null);
                throw e;
            }
        }
        if (vaultAdmin.userProvided(p[0], p[1], p[2])) {
            // a user-provided attribute: the value the run's user stored for themselves
            String who = (String) r.get("requested_by");
            Map<String, Object> uv = db.one("SELECT ciphertext FROM credential_user_values WHERE locker = ? AND name = ? AND attribute = ? AND username = ?", p[0], p[1], p[2], who);
            if (uv == null) {
                audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId, "user_value", true), "not_found", null);
                throw new OoaException("VAULT.NOT_FOUND", who + " has not stored their own value for '" + ref + "'", "Ask " + who + " to enter it in One Agent Vault (My value).");
            }
            audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId, "user_value", true), "ok", null);
            return vault.decrypt((String) uv.get("ciphertext"), VaultAdmin.userBound(ref, who));
        }
        Map<String, Object> c = db.one("SELECT ciphertext FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", p[0], p[1], p[2]);
        if (c == null) { audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "not_found", null); throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found"); }
        audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "ok", null);
        return vault.decrypt((String) c.get("ciphertext"), ref);
    }

    @SuppressWarnings("unchecked")
    String readExternal(Map<String, Object> locker, String name, String attribute) {
        Map<String, Object> cfg = (Map<String, Object>) JsonLite.parse((String) locker.get("external"));
        String secret = locker.get("external_secret") == null ? null : vault.decrypt((String) locker.get("external_secret"), "external:" + locker.get("name"));
        return externalVaults.read(cfg, secret, name, attribute);
    }

    /** Links a vault folder to an external vault (or unlinks it with cfg == null). The sign-in secret is kept encrypted here. */
    public void linkExternalVault(User by, String locker, Map<String, Object> cfg, String secret, String ip) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", locker);
        if (l == null) throw notFound("Vault folder");
        if (!ownsLocker(by, l)) throw denied("change this vault folder");
        if (cfg == null) {
            db.update("UPDATE lockers SET external = NULL, external_secret = NULL WHERE name = ?", locker);
            audit.record(by.username(), "locker.unlink_external", "locker", locker, null, "ok", ip);
            return;
        }
        Map<String, Object> clean = ExternalVaults.validate(cfg);
        synchronized (vault) {
            String ct = secret == null || secret.isEmpty() ? (String) l.get("external_secret") : vault.encrypt(secret, "external:" + locker);
            db.update("UPDATE lockers SET external = ?, external_secret = ? WHERE name = ?", JsonLite.write(clean), ct, locker);
        }
        audit.record(by.username(), "locker.link_external", "locker", locker, Map.of("provider", clean.get("provider")), "ok", ip);
    }

    /** Checks that a credential can be read from the linked external vault, without returning the value. */
    public Map<String, Object> testExternalVault(User by, String locker, String name, String attribute, String ip) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", locker);
        if (l == null) throw notFound("Vault folder");
        if (!ownsLocker(by, l)) throw denied("test this vault folder");
        if (l.get("external") == null) throw new OoaException("CR.BAD_INPUT", "This vault folder is not linked to an external vault");
        try {
            String v = readExternal(l, name, attribute == null || attribute.isBlank() ? "value" : attribute);
            audit.record(by.username(), "locker.test_external", "locker", locker, Map.of("credential", name), "ok", ip);
            return Map.of("ok", true, "length", v.length());
        } catch (OoaException e) {
            audit.record(by.username(), "locker.test_external", "locker", locker, Map.of("credential", name, "error", e.code()), "failed", ip);
            return Map.of("ok", false, "code", e.code(), "message", e.getMessage());
        }
    }

    public List<Map<String, Object>> globals(User by) {
        require(by, "globals.view", "view shared values");
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> mine = new HashMap<>();
        for (Map<String, Object> v : db.query("SELECT name, json_value FROM user_global_values WHERE username = ?", by.username())) mine.put((String) v.get("name"), JsonLite.parse((String) v.get("json_value")));
        for (Map<String, Object> g : db.query("SELECT * FROM global_values ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(g);
            m.remove("json_value");
            m.put("value", JsonLite.parse((String) g.get("json_value")));
            m.put("changeable", Boolean.TRUE.equals(g.get("changeable")));
            if (Boolean.TRUE.equals(g.get("changeable")) && mine.containsKey((String) g.get("name"))) m.put("my_value", mine.get((String) g.get("name")));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- saved connections

    /** Saved connections: a connector's address and other settings plus vault references for its secrets (never the secrets). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> connections(User by) {
        require(by, "globals.view", "view saved connections");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : db.query("SELECT * FROM connections ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.get("name")); m.put("package", c.get("package")); m.put("description", c.get("description"));
            m.put("values", JsonLite.parse((String) c.get("json_values"))); m.put("credentials", JsonLite.parse((String) c.get("json_credentials")));
            m.put("updated", c.get("updated")); m.put("updated_by", c.get("updated_by"));
            out.add(m);
        }
        return out;
    }

    /**
     * Toolkits a connection can be saved for, with the inputs it supplies: those a connector repeats on (nearly) every
     * action and that include a secret, such as its address and sign-in (Studio applies the same rule).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> connectionKinds(User by) {
        require(by, "globals.view", "view saved connections");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : registry.allManifests()) {
            String id = String.valueOf(m.get("id"));
            List<Map<String, Object>> actions = m.get("actions") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            if (actions.size() < 3 || Set.of("ooa.excel_desktop", "ooa.pdf", "ooa.rest", "ooa.genai").contains(id)) continue;
            Map<String, Integer> count = new LinkedHashMap<>();
            Map<String, Map<String, Object>> prop = new HashMap<>();
            for (Map<String, Object> a : actions) {
                Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) a.getOrDefault("inputs", Map.of())).getOrDefault("properties", Map.of());
                for (var e : props.entrySet()) { count.merge(e.getKey(), 1, Integer::sum); prop.putIfAbsent(e.getKey(), (Map<String, Object>) e.getValue()); }
            }
            List<Map<String, Object>> inputs = new ArrayList<>();
            boolean secret = false;
            for (var e : count.entrySet()) {
                if (e.getValue() < 0.8 * actions.size()) continue;
                Map<String, Object> p = prop.get(e.getKey());
                boolean cred = "Credential".equals(p.get("x-ooa-type"));
                secret |= cred;
                inputs.add(Map.of("name", e.getKey(), "type", String.valueOf(p.getOrDefault("x-ooa-type", "String")), "credential", cred,
                        "description", String.valueOf(p.getOrDefault("description", ""))));
            }
            if (secret) out.add(Map.of("package", id, "name", String.valueOf(m.getOrDefault("name", id)), "inputs", inputs));
        }
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("name"))));
        return out;
    }

    /** Creates or replaces a saved connection. Credentials are vault references (locker/credential/attribute). */
    @SuppressWarnings("unchecked")
    public void saveConnection(User by, String name, Map<String, Object> body, String ip) {
        require(by, "globals.manage", "manage saved connections");
        if (name == null || !name.matches("^[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}$"))
            throw new OoaException("CR.BAD_INPUT", "Connection names use letters, digits, spaces, _ . and - (up to 80)", "Example: ServiceNow production.");
        String pkg = body.get("package") instanceof String s ? s.strip() : "";
        if (!pkg.matches("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")) throw new OoaException("CR.BAD_INPUT", "Choose the toolkit the connection is for", "Example: ooa.servicenow.");
        Map<String, Object> values = body.get("values") instanceof Map<?, ?> v ? (Map<String, Object>) v : Map.of();
        Map<String, Object> creds = body.get("credentials") instanceof Map<?, ?> v ? (Map<String, Object>) v : Map.of();
        for (var e : values.entrySet())
            if (e.getValue() instanceof Map || e.getValue() instanceof List) throw new OoaException("CR.BAD_INPUT", "Setting '" + e.getKey() + "' must be text, a number or true/false");
        for (var e : creds.entrySet())
            if (!(e.getValue() instanceof String ref) || !ref.matches("^[^/]{1,120}/[^/]{1,120}/[^/]{1,120}$"))
                throw new OoaException("CR.BAD_INPUT", "Credential '" + e.getKey() + "' must be a vault reference folder/credential/attribute", "Pick it from One Agent Vault.");
        String desc = body.get("description") instanceof String d ? d.strip() : null;
        String jv = JsonLite.write(values), jc = JsonLite.write(creds);
        db.tx(() -> {
            if (db.update("UPDATE connections SET package = ?, description = ?, json_values = ?, json_credentials = ?, updated = ?, updated_by = ? WHERE name = ?",
                    pkg, desc, jv, jc, now(), by.username(), name) == 0)
                db.update("INSERT INTO connections (name, package, description, json_values, json_credentials, updated, updated_by) VALUES (?,?,?,?,?,?,?)",
                        name, pkg, desc, jv, jc, now(), by.username());
            return null;
        });
        audit.record(by.username(), "connection.save", "connection", name, Map.of("package", pkg), "ok", ip);
    }

    public void deleteConnection(User by, String name, String ip) {
        require(by, "globals.manage", "manage saved connections");
        if (db.update("DELETE FROM connections WHERE name = ?", name) == 0) throw new OoaException("CR.NOT_FOUND", "No saved connection '" + name + "'");
        audit.record(by.username(), "connection.delete", "connection", name, null, "ok", ip);
    }

    /** What a job carries: every connection by name (vault references only; the runner fetches secrets as the run's user). */
    private Map<String, Object> connectionsForJob() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> c : db.query("SELECT name, package, json_values, json_credentials FROM connections"))
            out.put((String) c.get("name"), Map.of("package", c.get("package"), "values", JsonLite.parse((String) c.get("json_values")),
                    "credentials", JsonLite.parse((String) c.get("json_credentials"))));
        return out;
    }

    public void setGlobal(User by, String name, Object value, String ip) { setGlobal(by, name, value, null, ip); }

    /**
     * Sets a shared value. changeable (when given): each person may set their own value, which their runs use instead
     * of this one; turning it off removes everyone's own values.
     */
    public void setGlobal(User by, String name, Object value, Boolean changeable, String ip) {
        require(by, "globals.manage", "manage shared values");
        if (name == null || !name.matches("^[A-Za-z_][A-Za-z0-9_]{0,79}$")) throw new OoaException("CR.BAD_INPUT", "Shared value names use letters, digits and _ (not starting with a digit)");
        String json = JsonLite.write(value);
        db.tx(() -> {
            if (db.update("UPDATE global_values SET json_value = ?, updated = ?, updated_by = ? WHERE name = ?", json, now(), by.username(), name) == 0)
                db.update("INSERT INTO global_values (name, json_value, updated, updated_by) VALUES (?,?,?,?)", name, json, now(), by.username());
            if (changeable != null) {
                db.update("UPDATE global_values SET changeable = ? WHERE name = ?", changeable, name);
                if (!changeable) db.update("DELETE FROM user_global_values WHERE name = ?", name);
            }
            return null;
        });
        audit.record(by.username(), "global.set", "global", name, changeable == null ? null : Map.of("changeable", changeable), "ok", ip);
    }

    public void deleteGlobal(User by, String name, String ip) {
        require(by, "globals.manage", "manage shared values");
        db.tx(() -> {
            db.update("DELETE FROM user_global_values WHERE name = ?", name);
            db.update("DELETE FROM global_values WHERE name = ?", name);
            return null;
        });
        audit.record(by.username(), "global.delete", "global", name, null, "ok", ip);
    }

    /** A person's own value of a changeable shared value; their runs use it. */
    public void setMyGlobal(User by, String name, Object value, String ip) {
        require(by, "globals.view", "view shared values");
        Map<String, Object> g = db.one("SELECT changeable FROM global_values WHERE name = ?", name);
        if (g == null) throw notFound("Shared value " + name);
        if (!Boolean.TRUE.equals(g.get("changeable"))) throw new OoaException("CR.FORBIDDEN", "Everyone uses the same value of " + name, "An administrator can let people change it for themselves.");
        String json = JsonLite.write(value);
        if (json.length() > 100_000) throw new OoaException("CR.TOO_LARGE", "A shared value is at most 100000 characters");
        db.tx(() -> {
            if (db.update("UPDATE user_global_values SET json_value = ?, updated = ? WHERE name = ? AND username = ?", json, now(), name, by.username()) == 0)
                db.update("INSERT INTO user_global_values (name, username, json_value, updated) VALUES (?,?,?,?)", name, by.username(), json, now());
            return null;
        });
        audit.record(by.username(), "global.my_value", "global", name, null, "ok", ip);
    }

    public void deleteMyGlobal(User by, String name, String ip) {
        if (db.update("DELETE FROM user_global_values WHERE name = ? AND username = ?", name, by.username()) == 0) throw notFound("Your value of " + name);
        audit.record(by.username(), "global.my_value_delete", "global", name, null, "ok", ip);
    }

    /**
     * Audit search: free text (q), who (actor), action (a prefix such as repo. or the exact action), object type and id,
     * result, a date range (from, to: dates or times), sort (newest or oldest first), paging (limit up to 1000, offset)
     * and the total. The hash chain is unchanged by searching.
     */
    public Map<String, Object> auditSearch(User by, Map<String, String> f) {
        require(by, "audit.view", "view the audit trail");
        List<String> where = new ArrayList<>();
        List<Object> p = new ArrayList<>();
        String q = f.get("q");
        if (q != null && !q.isBlank()) {
            String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
            where.add("(LOWER(actor) LIKE ? OR LOWER(action) LIKE ? OR LOWER(COALESCE(object_id, '')) LIKE ? OR LOWER(COALESCE(details, '')) LIKE ?)");
            p.add(like); p.add(like); p.add(like); p.add(like);
        }
        if (f.get("actor") != null && !f.get("actor").isBlank()) { where.add("LOWER(actor) = ?"); p.add(f.get("actor").toLowerCase(Locale.ROOT)); }
        if (f.get("action") != null && !f.get("action").isBlank()) {
            String a = f.get("action").trim();
            if (a.endsWith(".") || a.endsWith("*")) { where.add("action LIKE ?"); p.add(a.replace("*", "") + "%"); } else { where.add("action = ?"); p.add(a); }
        }
        for (String[] k : new String[][] {{"object_type", "object_type"}, {"object_id", "object_id"}, {"result", "result"}})
            if (f.get(k[0]) != null && !f.get(k[0]).isBlank()) { where.add(k[1] + " = ?"); p.add(f.get(k[0])); }
        if (f.get("from") != null && !f.get("from").isBlank()) { where.add("ts >= ?"); p.add(instant(f.get("from"), false)); }
        if (f.get("to") != null && !f.get("to").isBlank()) { where.add("ts <= ?"); p.add(instant(f.get("to"), true)); }
        String w = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = count("SELECT COUNT(*) AS n FROM audit" + w, p.toArray());
        String order = "oldest".equals(f.get("sort")) ? "ASC" : "DESC";
        List<Object> page = new ArrayList<>(p);
        page.add(Math.max(1, Math.min(1000, intOr(f.get("limit"), 100))));
        page.add(Math.max(0, intOr(f.get("offset"), 0)));
        List<Map<String, Object>> rows = db.query("SELECT seq, ts, actor, action, object_type, object_id, details, result, ip FROM audit" +
                w + " ORDER BY seq " + order + " LIMIT ? OFFSET ?", page.toArray());
        return Map.of("records", rows, "total", total);
    }

    /** The search as CSV (up to 50,000 records), for compliance exports. Exporting is itself recorded. */
    @SuppressWarnings("unchecked")
    public String auditCsv(User by, Map<String, String> f, String ip) {
        Map<String, String> all = new HashMap<>(f);
        StringBuilder sb = new StringBuilder(Platform.Csv.row(List.of("seq", "time", "actor", "action", "object_type", "object_id", "result", "ip", "details")));
        int got = 0;
        for (int off = 0; off < 50000; off += 1000) {
            all.put("limit", "1000"); all.put("offset", String.valueOf(off));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) auditSearch(by, all).get("records");
            for (Map<String, Object> r : rows) {
                List<String> cells = new ArrayList<>();
                for (String k : List.of("seq", "ts", "actor", "action", "object_type", "object_id", "result", "ip", "details")) cells.add(r.get(k) == null ? "" : String.valueOf(r.get(k)));
                sb.append(Platform.Csv.row(cells));
            }
            got += rows.size();
            if (rows.size() < 1000) break;
        }
        audit.record(by.username(), "audit.export", "audit", null, Map.of("records", got, "filters", f), "ok", ip);
        return sb.toString();
    }

    public List<Map<String, Object>> auditLog(User by, String q, int limit) {
        require(by, "audit.view", "view the audit trail");
        String like = "%" + (q == null ? "" : q.toLowerCase(Locale.ROOT)) + "%";
        return db.query("SELECT seq, ts, actor, action, object_type, object_id, details, result, ip FROM audit WHERE LOWER(actor) LIKE ? OR LOWER(action) LIKE ? OR LOWER(COALESCE(object_id, '')) LIKE ? " +
                "ORDER BY seq DESC LIMIT " + Math.max(1, Math.min(1000, limit)), like, like, like);
    }

    public Map<String, Object> dashboard(User by) { return dashboard(by, null); }

    /** The Home page figures; the runs-per-day chart counts days in the viewer's time zone (an IANA name, UTC when absent or unknown). */
    public Map<String, Object> dashboard(User by, String timeZone) {
        Map<String, Object> m = new LinkedHashMap<>();
        Instant day = now().minus(Duration.ofDays(1));
        ZoneId zone;
        try { zone = timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone); } catch (DateTimeException e) { zone = ZoneOffset.UTC; }
        m.put("bots", count("SELECT COUNT(*) AS n FROM bots WHERE deleted_at IS NULL"));
        m.put("devices_online", count("SELECT COUNT(*) AS n FROM devices WHERE enabled = TRUE AND last_seen > ?", now().minus(ONLINE)));
        m.put("devices_total", count("SELECT COUNT(*) AS n FROM devices"));
        m.put("running", count("SELECT COUNT(*) AS n FROM runs WHERE status = 'running'"));
        m.put("queued", count("SELECT COUNT(*) AS n FROM runs WHERE status = 'queued'"));
        m.put("success_24h", count("SELECT COUNT(*) AS n FROM runs WHERE status = 'success' AND finished > ?", day));
        m.put("failed_24h", count("SELECT COUNT(*) AS n FROM runs WHERE status = 'failed' AND finished > ?", day));
        m.put("schedules", count("SELECT COUNT(*) AS n FROM schedules WHERE enabled = TRUE"));
        m.put("stopped_24h", count("SELECT COUNT(*) AS n FROM runs WHERE status = 'stopped' AND finished > ?", day));
        long ok = (Long) m.get("success_24h"), bad = (Long) m.get("failed_24h");
        m.put("success_rate_24h", ok + bad == 0 ? null : Math.round(100.0 * ok / (ok + bad)));
        Map<String, Object> avg = db.one("SELECT AVG(duration_ms) AS a FROM runs WHERE status = 'success' AND finished > ?", day);
        m.put("avg_duration_ms_24h", avg == null || avg.get("a") == null ? null : ((Number) avg.get("a")).longValue());
        if (can(by, "runs.view")) {
            // Runs per day for the last 7 days in the viewer's time zone, by outcome.
            LocalDate today = LocalDate.ofInstant(now(), zone);
            Map<LocalDate, long[]> perDay = new TreeMap<>();
            for (int i = 6; i >= 0; i--) perDay.put(today.minusDays(i), new long[4]);
            for (Map<String, Object> r : db.query("SELECT status, created FROM runs WHERE created >= ?", today.minusDays(6).atStartOfDay(zone).toInstant())) {
                long[] c = perDay.get(Instant.parse(String.valueOf(r.get("created"))).atZone(zone).toLocalDate());
                if (c == null) continue;
                switch (String.valueOf(r.get("status"))) { case "success" -> c[0]++; case "failed" -> c[1]++; case "stopped" -> c[2]++; default -> c[3]++; }
            }
            List<Map<String, Object>> days = new ArrayList<>();
            perDay.forEach((d, c) -> days.add(Map.of("date", d.toString(), "success", c[0], "failed", c[1], "stopped", c[2], "active", c[3])));
            m.put("days", days);
            Instant week = now().minus(Duration.ofDays(7));
            m.put("top_bots", db.query("SELECT bot_path, COUNT(*) AS runs, SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) AS failed, AVG(duration_ms) AS avg_ms " +
                    "FROM runs WHERE created >= ? GROUP BY bot_path ORDER BY runs DESC LIMIT 6", week));
            m.put("recent_failures", db.query("SELECT r.id, r.bot_path, r.version, r.error, r.finished, d.name AS device_name FROM runs r LEFT JOIN devices d ON d.id = r.device_id " +
                    "WHERE r.status = 'failed' ORDER BY r.finished DESC LIMIT 6"));
        }
        // Value delivered: each successful run saves the agent's minutes (or the default from Settings).
        int dflt = platform.intSetting("default_minutes_saved");
        Map<String, Object> saved = db.one("SELECT SUM(COALESCE(b.minutes_saved, ?)) AS n, COUNT(*) AS c FROM runs r LEFT JOIN bots b ON b.path = r.bot_path " +
                "WHERE r.status = 'success' AND r.finished > ?", dflt, now().minus(Duration.ofDays(30)));
        m.put("hours_saved_30d", saved == null || saved.get("n") == null ? 0 : Math.round(((Number) saved.get("n")).doubleValue() / 6.0) / 10.0);
        if (can(by, "queues.view"))
            m.put("queue_items_waiting", count("SELECT COUNT(*) AS n FROM queue_items WHERE status IN ('new', 'retry')"));
        m.put("open_tasks", workflows.openTaskCount(by));
        // the person's own recent work, for "Pick up where you left off"
        if (can(by, "repo.view")) {
            Map<String, Map<String, String>> perms = folders.entries();
            List<String> mine = teams.principalsOf(by.username(), by.role());
            List<Map<String, Object>> agents = new ArrayList<>();
            for (Map<String, Object> b : db.query("SELECT path, latest_version, production_version, locked_by, updated FROM bots WHERE deleted_at IS NULL AND (updated_by = ? OR locked_by = ?) ORDER BY updated DESC LIMIT 20", by.username(), by.username())) {
                if (folders.accessToFolder(mine, by.role(), Folders.folderOf((String) b.get("path")), perms).equals("none")) continue;
                agents.add(b);
                if (agents.size() == 6) break;
            }
            m.put("my_recent_agents", agents);
        }
        if (can(by, "runs.view"))
            m.put("my_recent_runs", db.query("SELECT id, bot_path, status, created, finished FROM runs WHERE requested_by = ? ORDER BY created DESC LIMIT 6", by.username()));
        m.put("user", me(by));
        return m;
    }

    long count(String sql, Object... p) { return ((Number) db.one(sql, p).get("n")).longValue(); }
}
