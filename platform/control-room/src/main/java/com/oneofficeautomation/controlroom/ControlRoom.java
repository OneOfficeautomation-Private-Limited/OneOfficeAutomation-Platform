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
    public record User(String id, String username, String role, String displayName, boolean mustChange) {}

    public final Db db;
    public final Audit audit;
    public final Vault vault;
    private final FlowValidator validator;
    private final PackageRegistry registry;
    static final Duration SESSION = Duration.ofHours(8);
    static final Duration ONLINE = Duration.ofSeconds(60);
    static final Pattern PATH = Pattern.compile("^[A-Za-z0-9 _.()-]+(/[A-Za-z0-9 _.()-]+){0,8}\\.flow\\.json$");
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,80}$");

    public ControlRoom(Db db, Path dataDir, PackageRegistry registry, FlowSchemaValidator schema) {
        this.db = db;
        this.audit = new Audit(db);
        this.vault = new Vault(dataDir);
        this.validator = new FlowValidator(registry, schema);
        this.registry = registry;
        db.migrate();
    }

    static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    static OoaException denied(String what) { return new OoaException("CR.FORBIDDEN", "You do not have permission to " + what, "Ask a Command Center administrator for the right role."); }
    static OoaException notFound(String what) { return new OoaException("CR.NOT_FOUND", what + " not found"); }

    public void require(User u, String permission, String what) { if (!Rbac.allowed(u.role(), permission)) throw denied(what); }

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
        return new User((String) r.get("id"), (String) r.get("username"), (String) r.get("role"), (String) r.get("display_name"), Boolean.TRUE.equals(r.get("must_change")));
    }

    public Map<String, Object> login(String username, String password, String ip) {
        Map<String, Object> r = db.one("SELECT * FROM users WHERE username = ?", username == null ? "" : username.trim());
        OoaException bad = new OoaException("CR.LOGIN_FAILED", "Wrong user name or password", null);
        if (r == null) { Security.hashPassword("timing".toCharArray()); audit.record(String.valueOf(username), "auth.login", "user", String.valueOf(username), null, "failed", ip); throw bad; }
        if (!Boolean.TRUE.equals(r.get("enabled"))) { audit.record(username, "auth.login", "user", username, "disabled", "failed", ip); throw new OoaException("CR.USER_DISABLED", "This account is disabled", "Contact your administrator."); }
        Object lu = r.get("locked_until");
        if (lu != null && Instant.parse(lu.toString()).isAfter(now()))
            throw new OoaException("CR.LOCKED_OUT", "Too many failed sign-ins; try again after " + lu, "Wait 15 minutes or ask an administrator to reset your password.");
        if (!Security.verifyPassword(password == null ? new char[0] : password.toCharArray(), (String) r.get("password_hash"))) {
            int failed = ((Number) r.get("failed")).intValue() + 1;
            db.update("UPDATE users SET failed = ?, locked_until = ? WHERE id = ?", failed % 5 == 0 ? 0 : failed, failed % 5 == 0 ? now().plus(Duration.ofMinutes(15)) : null, r.get("id"));
            audit.record(username, "auth.login", "user", username, failed % 5 == 0 ? "account locked for 15 minutes" : null, "failed", ip);
            throw bad;
        }
        String token = Security.token(32);
        db.update("UPDATE users SET failed = 0, locked_until = NULL, last_login = ? WHERE id = ?", now(), r.get("id"));
        db.update("DELETE FROM sessions WHERE expires < ?", now());
        db.update("INSERT INTO sessions (token_hash, user_id, created, expires, ip) VALUES (?,?,?,?,?)", Security.sha256(token), r.get("id"), now(), now().plus(SESSION), ip);
        audit.record(username, "auth.login", "user", username, null, "ok", ip);
        User u = user(r);
        return Map.of("token", token, "user", publicUser(u));
    }

    public static Map<String, Object> publicUser(User u) {
        return Map.of("id", u.id(), "username", u.username(), "role", u.role(), "display_name", u.displayName() == null ? u.username() : u.displayName(),
                "must_change", u.mustChange(), "permissions", Rbac.permissions(u.role()));
    }

    /** Resolves a session token (sliding expiry); null when missing, expired or the user is disabled. */
    public User session(String token) {
        if (token == null || token.isBlank()) return null;
        Map<String, Object> r = db.one("SELECT u.*, s.expires AS s_expires FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?", Security.sha256(token));
        if (r == null || !Boolean.TRUE.equals(r.get("enabled")) || Instant.parse(r.get("s_expires").toString()).isBefore(now())) return null;
        db.update("UPDATE sessions SET expires = ? WHERE token_hash = ?", now().plus(SESSION), Security.sha256(token));
        return user(r);
    }

    public void logout(String token, User u, String ip) {
        db.update("DELETE FROM sessions WHERE token_hash = ?", Security.sha256(token));
        audit.record(u.username(), "auth.logout", "user", u.username(), null, "ok", ip);
    }

    public void changePassword(User u, String current, String next, String token, String ip) {
        Map<String, Object> r = db.one("SELECT password_hash FROM users WHERE id = ?", u.id());
        if (!Security.verifyPassword(current == null ? new char[0] : current.toCharArray(), (String) r.get("password_hash"))) {
            audit.record(u.username(), "user.change_password", "user", u.username(), null, "failed", ip);
            throw new OoaException("CR.LOGIN_FAILED", "The current password is wrong");
        }
        String problem = Security.passwordProblem(next, u.username());
        if (problem != null) throw new OoaException("CR.WEAK_PASSWORD", problem);
        if (current.equals(next)) throw new OoaException("CR.WEAK_PASSWORD", "Choose a different password from the current one.");
        db.tx(() -> {
            db.update("UPDATE users SET password_hash = ?, must_change = FALSE WHERE id = ?", Security.hashPassword(next.toCharArray()), u.id());
            db.update("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?", u.id(), Security.sha256(token));
            return null;
        });
        audit.record(u.username(), "user.change_password", "user", u.username(), null, "ok", ip);
    }

    public List<Map<String, Object>> users(User by) {
        require(by, "users.manage", "manage users");
        return db.query("SELECT id, username, display_name, role, enabled, must_change, last_login, created, locked_until FROM users ORDER BY username");
    }

    public Map<String, Object> createUser(User by, String username, String display, String role, String ip) {
        require(by, "users.manage", "manage users");
        if (username == null || !username.matches("^[A-Za-z0-9_.@-]{3,80}$")) throw new OoaException("CR.BAD_INPUT", "User names use 3-80 letters, digits, . _ @ -");
        if (!Rbac.ROLES.contains(role)) throw new OoaException("CR.BAD_INPUT", "Role must be one of " + Rbac.ROLES);
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
                if (!Rbac.ROLES.contains(role)) throw new OoaException("CR.BAD_INPUT", "Role must be one of " + Rbac.ROLES);
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
            return null;
        });
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

    public List<Map<String, Object>> repoList(User by, String q) {
        require(by, "repo.view", "view the agent repository");
        String like = "%" + (q == null ? "" : q.toLowerCase(Locale.ROOT)) + "%";
        return db.query("SELECT path, bot_id, name, description, latest_version, production_version, locked_by, locked_at, updated, updated_by FROM bots " +
                "WHERE LOWER(path) LIKE ? OR LOWER(COALESCE(description, '')) LIKE ? OR LOWER(bot_id) LIKE ? ORDER BY path", like, like, like);
    }

    public List<Map<String, Object>> versions(User by, String path) {
        require(by, "repo.view", "view the agent repository");
        return db.query("SELECT version, sha256, comment, author, created FROM bot_versions WHERE path = ? ORDER BY version DESC", path);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> content(String path, Object version) {
        Map<String, Object> b = db.one("SELECT latest_version, production_version FROM bots WHERE path = ?", path);
        if (b == null) throw notFound("Agent " + path);
        Object v = version == null || "latest".equals(version) ? b.get("latest_version") : "production".equals(version) ? b.get("production_version") : version;
        if (v == null) throw new OoaException("CR.NO_PRODUCTION", "Agent " + path + " has no live production version", "Set a live production version in the agent repository.");
        Map<String, Object> row = db.one("SELECT content, version FROM bot_versions WHERE path = ? AND version = ?", path, Integer.parseInt(String.valueOf(v)));
        if (row == null) throw notFound("Version " + v + " of " + path);
        return Map.of("version", row.get("version"), "flow", JsonLite.parse((String) row.get("content")));
    }

    public Map<String, Object> getBot(User by, String path, Object version) { require(by, "repo.view", "view the agent repository"); return content(path, version); }

    public Map<String, Object> checkout(User by, String path, String ip) {
        require(by, "repo.checkin", "check out agents");
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
        Map<String, Object> b = db.one("SELECT locked_by FROM bots WHERE path = ?", path);
        if (b == null) throw notFound("Agent " + path);
        if (b.get("locked_by") == null) return;
        if (!by.username().equals(b.get("locked_by")) && !Rbac.allowed(by.role(), "repo.production"))
            throw new OoaException("CR.LOCKED", "Only " + b.get("locked_by") + " or an administrator can undo this check-out");
        db.update("UPDATE bots SET locked_by = NULL, locked_at = NULL WHERE path = ?", path);
        audit.record(by.username(), "repo.undo_checkout", "bot", path, Map.of("was_locked_by", b.get("locked_by")), "ok", ip);
    }

    /** Validates and stores a new version. Existing bots must be checked out by the same user. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkin(User by, String path, Map<String, Object> flow, String comment, boolean makeProduction, String ip) {
        require(by, "repo.checkin", "check in agents");
        if (path == null || !PATH.matcher(path).matches() || path.contains(".."))
            throw new OoaException("CR.BAD_PATH", "Invalid agent repository path '" + path + "'", "Use folders and a name ending in .flow.json, e.g. Finance/Invoice approval.flow.json");
        List<Map<String, Object>> problems = validator.validate(flow);
        if (!problems.isEmpty()) throw new OoaException("CR.INVALID_BOT", problems.size() + " validation problem(s): " + problems.get(0).get("message"),
                "Fix the problems in Agent Studio (Validate) before checking in.", false, Map.of("problems", problems));
        String botId = String.valueOf(flow.get("id"));
        return db.tx(() -> {
            Map<String, Object> other = db.one("SELECT path FROM bots WHERE bot_id = ? AND path <> ?", botId, path);
            if (other != null) throw new OoaException("CR.DUPLICATE_ID", "Agent id '" + botId + "' is already used by " + other.get("path"), "Change the agent id in Agent settings.");
            Map<String, Object> b = db.one("SELECT latest_version, locked_by, production_version FROM bots WHERE path = ?", path);
            String json = JsonLite.write(flow);
            int version;
            if (b == null) {
                version = 1;
                db.update("INSERT INTO bots (path, bot_id, name, description, latest_version, production_version, created, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?)",
                        path, botId, String.valueOf(flow.getOrDefault("name", botId)), String.valueOf(flow.getOrDefault("description", "")), 1, makeProduction ? 1 : null, now(), now(), by.username());
            } else {
                if (!by.username().equals(b.get("locked_by")))
                    throw new OoaException("CR.NOT_CHECKED_OUT", b.get("locked_by") == null ? "Check the agent out before checking in a new version" : "Checked out by " + b.get("locked_by"),
                            "Use Agent check-out first, so two people never overwrite each other's work.");
                version = ((Number) b.get("latest_version")).intValue() + 1;
                db.update("UPDATE bots SET bot_id = ?, name = ?, description = ?, latest_version = ?, production_version = ?, locked_by = NULL, locked_at = NULL, updated = ?, updated_by = ? WHERE path = ?",
                        botId, String.valueOf(flow.getOrDefault("name", botId)), String.valueOf(flow.getOrDefault("description", "")), version,
                        makeProduction ? version : b.get("production_version"), now(), by.username(), path);
            }
            db.update("INSERT INTO bot_versions (path, version, content, sha256, comment, author, created) VALUES (?,?,?,?,?,?,?)",
                    path, version, json, Security.sha256(json), comment == null ? "" : comment, by.username(), now());
            audit.record(by.username(), "repo.checkin", "bot", path, Map.of("version", version, "production", makeProduction, "comment", comment == null ? "" : comment), "ok", ip);
            return Map.of("path", path, "version", version);
        });
    }

    public void setProduction(User by, String path, int version, String ip) {
        require(by, "repo.production", "set live production versions");
        if (db.one("SELECT version FROM bot_versions WHERE path = ? AND version = ?", path, version) == null) throw notFound("Version " + version + " of " + path);
        db.update("UPDATE bots SET production_version = ?, updated = ?, updated_by = ? WHERE path = ?", version, now(), by.username(), path);
        audit.record(by.username(), "repo.set_production", "bot", path, Map.of("version", version), "ok", ip);
    }

    // ===================== devices and agents =====================

    public Map<String, Object> createRegistrationKey(User by, int hours, String ip) {
        require(by, "devices.manage", "register runners");
        String key = "ooareg_" + Security.token(24);
        db.update("INSERT INTO registration_keys (key_hash, created_by, created, expires) VALUES (?,?,?,?)", Security.sha256(key), by.username(), now(), now().plus(Duration.ofHours(Math.max(1, Math.min(168, hours)))));
        audit.record(by.username(), "device.registration_key", "device", null, Map.of("hours", hours), "ok", ip);
        return Map.of("key", key, "expires_in_hours", hours);
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
            db.update("INSERT INTO devices (id, name, hostname, os, agent_version, secret_hash, enabled, last_seen, registered, registered_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    deviceId, nm, hostname, os, agentVersion, Security.sha256(secret), true, now(), now(), k.get("created_by"));
            db.update("UPDATE registration_keys SET used_by = ? WHERE key_hash = ?", deviceId, k.get("key_hash"));
            audit.record("agent:" + hostname, "device.register", "device", deviceId, Map.of("name", nm), "ok", ip);
            return Map.of("device_id", deviceId, "secret", secret, "name", nm);
        });
    }

    public Map<String, Object> device(String deviceId, String secret) {
        if (deviceId == null || secret == null) return null;
        Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", deviceId);
        if (d == null || !Boolean.TRUE.equals(d.get("enabled")) || !Security.constantEquals(Security.sha256(secret), (String) d.get("secret_hash"))) return null;
        return d;
    }

    public List<Map<String, Object>> devices(User by) {
        require(by, "devices.view", "view runners");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : db.query("SELECT id, name, hostname, os, agent_version, enabled, last_seen, registered, registered_by FROM devices ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(d);
            boolean online = d.get("last_seen") != null && Instant.parse(d.get("last_seen").toString()).isAfter(now().minus(ONLINE));
            m.put("status", !Boolean.TRUE.equals(d.get("enabled")) ? "disabled" : online ? "online" : "offline");
            m.put("running", ((Number) db.one("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status = 'running'", d.get("id")).get("n")).intValue());
            out.add(m);
        }
        return out;
    }

    public void renameDevice(User by, String deviceId, String name, String ip) {
        require(by, "devices.manage", "manage runners");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) throw new OoaException("CR.BAD_INPUT", "Runner names have 1 to 120 characters");
        if (db.update("UPDATE devices SET name = ? WHERE id = ?", n, deviceId) == 0) throw notFound("Runner");
        audit.record(by.username(), "device.rename", "device", deviceId, Map.of("name", n), "ok", ip);
    }

    public void setDeviceEnabled(User by, String deviceId, boolean enabled, String ip) {
        require(by, "devices.manage", "manage runners");
        if (db.update("UPDATE devices SET enabled = ? WHERE id = ?", enabled, deviceId) == 0) throw notFound("Runner");
        audit.record(by.username(), enabled ? "device.enable" : "device.disable", "device", deviceId, null, "ok", ip);
    }

    public Map<String, Object> heartbeat(Map<String, Object> d, Map<String, Object> info) {
        String did = (String) d.get("id");
        db.update("UPDATE devices SET last_seen = ?, agent_version = ?, hostname = COALESCE(?, hostname), os = COALESCE(?, os) WHERE id = ?",
                now(), String.valueOf(info.getOrDefault("agent_version", d.get("agent_version"))), (String) info.get("hostname"), (String) info.get("os"), did);
        List<Object> cancel = new ArrayList<>(), pause = new ArrayList<>(), resume = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, cancel_requested, pause_requested FROM runs WHERE device_id = ? AND status = 'running'", did)) {
            if (Boolean.TRUE.equals(r.get("cancel_requested"))) cancel.add(r.get("id"));
            else if (Boolean.TRUE.equals(r.get("pause_requested"))) pause.add(r.get("id")); else resume.add(r.get("id"));
        }
        List<Object> triggers = new ArrayList<>();
        for (Map<String, Object> t : db.query("SELECT id, folder, pattern FROM triggers WHERE device_id = ? AND kind = 'file' AND enabled = TRUE", did)) triggers.add(t);
        return Map.of("cancel", cancel, "pause", pause, "resume", resume, "file_triggers", triggers);
    }

    // ===================== runs =====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> startRun(String requestedBy, String role, String path, String deviceId, Map<String, Object> inputs, String source, String ip) {
        if (!Rbac.allowed(role, "runs.start")) throw denied("start runs");
        Map<String, Object> c = content(path, "production");
        Map<String, Object> flow = (Map<String, Object>) c.get("flow");
        Map<String, Object> dev = db.one("SELECT enabled FROM devices WHERE id = ?", deviceId);
        if (dev == null) throw notFound("Runner " + deviceId);
        if (!Boolean.TRUE.equals(dev.get("enabled"))) throw new OoaException("CR.DEVICE_DISABLED", "That runner is disabled");
        Map<String, Object> clean = new LinkedHashMap<>();
        Map<String, Map<String, Object>> declared = new HashMap<>();
        for (Object v : (List<Object>) flow.getOrDefault("variables", List.of())) {
            Map<String, Object> m = (Map<String, Object>) v;
            if ("input".equals(m.get("direction"))) declared.put(String.valueOf(m.get("name")), m);
        }
        for (var e : (inputs == null ? Map.<String, Object>of() : inputs).entrySet()) {
            Map<String, Object> var = declared.get(e.getKey());
            if (var == null) throw new OoaException("CR.BAD_INPUT", "The agent has no input named '" + e.getKey() + "'", "Inputs: " + declared.keySet());
            if ("Credential".equals(var.get("type")) && !(e.getValue() instanceof Map<?, ?> m && m.get("$credential") instanceof String))
                throw new OoaException("CR.BAD_INPUT", "Input '" + e.getKey() + "' is a Credential: give a vault reference {\"$credential\": \"locker/credential/attribute\"}, never the secret itself");
            clean.put(e.getKey(), e.getValue());
        }
        String runId = id("run");
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                runId, path, c.get("version"), deviceId, "queued", JsonLite.write(clean), requestedBy, source, now(), false, false);
        audit.record(requestedBy, "run.start", "run", runId, Map.of("bot", path, "version", c.get("version"), "device", deviceId, "source", source), "ok", ip);
        return Map.of("run_id", runId, "version", c.get("version"));
    }

    /** Atomically assigns the oldest queued run of this device and returns its job (bot bundle, inputs, global values). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> claimNext(Map<String, Object> d) {
        String did = (String) d.get("id");
        for (Map<String, Object> r : db.query("SELECT id FROM runs WHERE device_id = ? AND status = 'queued' ORDER BY created LIMIT 5", did)) {
            if (db.update("UPDATE runs SET status = 'running', started = ? WHERE id = ? AND status = 'queued'", now(), r.get("id")) != 1) continue;
            Map<String, Object> run = db.one("SELECT * FROM runs WHERE id = ?", r.get("id"));
            Map<String, Object> main = (Map<String, Object>) content((String) run.get("bot_path"), run.get("version")).get("flow");
            Map<String, Object> bundle = new LinkedHashMap<>();
            collect(main, bundle, 0);
            Map<String, Object> globals = new LinkedHashMap<>();
            for (Map<String, Object> g : db.query("SELECT name, json_value FROM global_values")) globals.put((String) g.get("name"), JsonLite.parse((String) g.get("json_value")));
            return Map.of("run_id", run.get("id"), "bot_path", run.get("bot_path"), "version", run.get("version"), "flow", main, "bots", bundle,
                    "inputs", JsonLite.parse((String) run.get("inputs")), "principal", run.get("requested_by"), "global_values", globals);
        }
        return null;
    }

    /** Adds production versions of child bots (Run bot) and AI agent tools, transitively. */
    @SuppressWarnings("unchecked")
    void collect(Map<String, Object> flow, Map<String, Object> bundle, int depth) {
        if (depth > 10 || bundle.size() > 100) return;
        for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of()))) {
            Map<String, Object> a = s.get("args") instanceof Map ? (Map<String, Object>) s.get("args") : Map.of();
            List<String> ids = new ArrayList<>();
            if ("core/run_bot".equals(s.get("action")) && a.get("bot") instanceof String b) ids.add(b);
            if ("ooa.ai_agent/run".equals(s.get("action")))
                for (String k : List.of("tools", "sensitive_tools")) if (a.get(k) instanceof List<?> l) for (Object x : l) if (x instanceof String xs) ids.add(xs);
            for (String botId : ids) {
                if (bundle.containsKey(botId)) continue;
                Map<String, Object> b = db.one("SELECT path FROM bots WHERE bot_id = ?", botId);
                if (b == null) continue;  // the Runner reports a clear error if the bot is really needed
                try {
                    Map<String, Object> child = (Map<String, Object>) content((String) b.get("path"), "production").get("flow");
                    bundle.put(botId, child);
                    collect(child, bundle, depth + 1);
                } catch (OoaException ignored) { }
            }
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
        db.update("UPDATE runs SET status = ?, finished = ?, duration_ms = ?, error = ?, outputs = ?, steps = ? WHERE id = ?",
                status, now(), Duration.between(started, now()).toMillis(), result.get("error") == null ? null : JsonLite.write(result.get("error")),
                JsonLite.write(result.getOrDefault("outputs", Map.of())), result.get("stepsExecuted") instanceof Number n ? n.intValue() : null, runId);
        audit.record("agent:" + d.get("name"), "run.finish", "run", runId, Map.of("status", status), "ok", null);
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
        audit.record(by.username(), "run." + action, "run", runId, null, "ok", ip);
    }

    public List<Map<String, Object>> runs(User by, String filter, int limit) {
        require(by, "runs.view", "view runs");
        String where = "active".equals(filter) ? "WHERE r.status IN ('queued', 'running')" : "history".equals(filter) ? "WHERE r.status IN ('success', 'failed', 'stopped')" : "";
        return db.query("SELECT r.id, r.bot_path, r.version, r.device_id, d.name AS device_name, r.status, r.requested_by, r.source, r.created, r.started, r.finished, r.duration_ms, " +
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
        for (Map<String, Object> r : db.query("SELECT r.id, r.bot_path, r.version, r.device_id, d.name AS device_name, r.status, r.requested_by, r.source, r.created, r.started, r.finished, " +
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
        Instant cutoff = now().minus(Duration.ofMinutes(2));
        for (Map<String, Object> r : db.query("SELECT r.id FROM runs r JOIN devices d ON d.id = r.device_id WHERE r.status = 'running' AND (d.last_seen IS NULL OR d.last_seen < ?)", cutoff)) {
            db.update("UPDATE runs SET status = 'failed', finished = ?, error = ? WHERE id = ? AND status = 'running'", now(),
                    JsonLite.write(Map.of("code", "AGENT.LOST", "message", "The runner stopped reporting while the agent was running", "suggestedFix", "Check the runner is on and the One Runner is running.")), r.get("id"));
            audit.record("system", "run.lost", "run", (String) r.get("id"), null, "failed", null);
        }
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
        if (db.one("SELECT id FROM devices WHERE id = ?", s.get("device_id")) == null) throw notFound("Runner");
        String sid = id("sch");
        Map<String, Object> row = new HashMap<>(s);
        row.put("time_zone", tz);
        Instant next = nextRun(row, now());
        db.update("INSERT INTO schedules (id, name, bot_path, device_id, kind, every_minutes, at_time, days, time_zone, inputs, enabled, next_run, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sid, String.valueOf(s.getOrDefault("name", "Schedule")), s.get("bot_path"), s.get("device_id"), kind, s.get("every_minutes") instanceof Number n ? n.intValue() : null,
                kind.equals("every") ? null : s.get("at_time"), kind.equals("weekly") ? s.get("days") : null, tz, JsonLite.write(s.getOrDefault("inputs", Map.of())), true, next, by.username());
        audit.record(by.username(), "schedule.create", "schedule", sid, Map.of("bot", s.get("bot_path"), "kind", kind), "ok", ip);
        return Map.of("id", sid, "next_run", next.toString());
    }

    public List<Map<String, Object>> schedules(User by) { require(by, "schedules.view", "view schedules"); return db.query("SELECT * FROM schedules ORDER BY name"); }

    /** Starts a schedule's bot now, with its device and inputs, as the person who asked. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runScheduleNow(User by, String sid, String ip) {
        Map<String, Object> s = db.one("SELECT * FROM schedules WHERE id = ?", sid);
        if (s == null) throw notFound("Schedule");
        Map<String, Object> inputs = s.get("inputs") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) s.get("inputs"));
        return startRun(by.username(), by.role(), (String) s.get("bot_path"), (String) s.get("device_id"), inputs, "schedule:" + sid, ip);
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
            Map<String, Object> creator = db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", s.get("created_by"));
            try {
                startRun((String) s.get("created_by"), creator == null ? "viewer" : (String) creator.get("role"), (String) s.get("bot_path"), (String) s.get("device_id"),
                        (Map<String, Object>) JsonLite.parse((String) s.get("inputs")), "schedule:" + s.get("id"), null);
                started++;
            } catch (OoaException e) { audit.record("scheduler", "schedule.fire", "schedule", (String) s.get("id"), e.getMessage(), "failed", null); }
        }
        return started;
    }

    // ===================== triggers =====================

    public Map<String, Object> createTrigger(User by, Map<String, Object> t, String ip) {
        require(by, "triggers.manage", "manage triggers");
        String kind = String.valueOf(t.get("kind"));
        if (!Set.of("file", "webhook").contains(kind)) throw new OoaException("CR.BAD_TRIGGER", "kind must be file or webhook");
        content(String.valueOf(t.get("bot_path")), "production");
        if (db.one("SELECT id FROM devices WHERE id = ?", t.get("device_id")) == null) throw notFound("Runner");
        if (kind.equals("file") && (t.get("folder") == null || String.valueOf(t.get("folder")).isBlank())) throw new OoaException("CR.BAD_TRIGGER", "A file trigger needs a folder on the runner");
        String tid = id("trg");
        String secret = kind.equals("webhook") ? Security.token(24) : null;
        db.update("INSERT INTO triggers (id, name, bot_path, device_id, kind, folder, pattern, secret_hash, enabled, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                tid, String.valueOf(t.getOrDefault("name", "Trigger")), t.get("bot_path"), t.get("device_id"), kind, t.get("folder"), String.valueOf(t.getOrDefault("pattern", "*")),
                secret == null ? null : Security.sha256(secret), true, by.username());
        audit.record(by.username(), "trigger.create", "trigger", tid, Map.of("kind", kind, "bot", t.get("bot_path")), "ok", ip);
        Map<String, Object> out = new LinkedHashMap<>(Map.of("id", tid));
        if (secret != null) { out.put("secret", secret); out.put("url", "/api/hooks/" + tid); }
        return out;
    }

    public List<Map<String, Object>> triggers(User by) {
        require(by, "triggers.view", "view triggers");
        return db.query("SELECT id, name, bot_path, device_id, kind, folder, pattern, enabled, created_by FROM triggers ORDER BY name");
    }

    public void setTriggerEnabled(User by, String tid, boolean en, String ip) {
        require(by, "triggers.manage", "manage triggers");
        if (db.update("UPDATE triggers SET enabled = ? WHERE id = ?", en, tid) == 0) throw notFound("Trigger");
        audit.record(by.username(), en ? "trigger.enable" : "trigger.disable", "trigger", tid, null, "ok", ip);
    }

    public void deleteTrigger(User by, String tid, String ip) {
        require(by, "triggers.manage", "manage triggers");
        if (db.update("DELETE FROM triggers WHERE id = ?", tid) == 0) throw notFound("Trigger");
        audit.record(by.username(), "trigger.delete", "trigger", tid, null, "ok", ip);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fireTrigger(Map<String, Object> t, Map<String, Object> event, String source, String ip) {
        Map<String, Object> flow = (Map<String, Object>) content((String) t.get("bot_path"), "production").get("flow");
        Set<String> declared = new HashSet<>();
        for (Object v : (List<Object>) flow.getOrDefault("variables", List.of())) if ("input".equals(((Map<String, Object>) v).get("direction"))) declared.add(String.valueOf(((Map<String, Object>) v).get("name")));
        Map<String, Object> in = new LinkedHashMap<>();
        event.forEach((k, v) -> { if (declared.contains(k)) in.put(k, v); });
        Map<String, Object> creator = db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", t.get("created_by"));
        return startRun((String) t.get("created_by"), creator == null ? "viewer" : (String) creator.get("role"), (String) t.get("bot_path"), (String) t.get("device_id"), in, source, ip);
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
        Map<String, Object> t = db.one("SELECT * FROM triggers WHERE id = ? AND device_id = ? AND kind = 'file' AND enabled = TRUE", tid, d.get("id"));
        if (t == null) throw notFound("Trigger");
        return fireTrigger(t, Map.of("trigger_file", file), "file:" + tid, null);
    }

    // ===================== lockers, credentials, global values =====================

    static List<String> csv(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o).trim());
        else if (v != null) for (String s : String.valueOf(v).split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    boolean ownsLocker(User u, Map<String, Object> locker) {
        return Rbac.allowed(u.role(), "lockers.manage") || csv(locker.get("owners")).contains(u.username());
    }

    public void createLocker(User by, String name, String description, Object consumers, String ip) {
        if (!Rbac.allowed(by.role(), "lockers.manage") && !Rbac.allowed(by.role(), "lockers.own")) throw denied("create lockers");
        if (name == null || !NAME.matcher(name).matches()) throw new OoaException("CR.BAD_INPUT", "Vault folder names use letters, digits, . _ -");
        List<String> cons = csv(consumers);
        for (String c : cons) if (!c.startsWith("role:") && !c.startsWith("user:")) throw new OoaException("CR.BAD_INPUT", "Consumers look like role:bot_runner or user:jane");
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
            boolean owner = ownsLocker(by, l);
            if (!owner && !consumes(by.username(), by.role(), l)) continue;
            Map<String, Object> m = new LinkedHashMap<>(l);
            m.put("can_manage", owner);
            List<Object> creds = new ArrayList<>();
            for (Map<String, Object> c : db.query("SELECT name, attribute, updated, updated_by FROM credentials WHERE locker = ? ORDER BY name, attribute", l.get("name"))) creds.add(c);
            m.put("credentials", creds);
            out.add(m);
        }
        return out;
    }

    static boolean consumes(String username, String role, Map<String, Object> locker) {
        List<String> c = csv(locker.get("consumers"));
        return c.contains("user:" + username) || c.contains("role:" + role) || c.contains("role:*");
    }

    public void setCredential(User by, String locker, String name, String attribute, String value, String ip) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", locker);
        if (l == null) throw notFound("Vault folder");
        if (!ownsLocker(by, l)) throw denied("change credentials in this vault folder");
        if (!NAME.matcher(name == null ? "" : name).matches() || !NAME.matcher(attribute == null ? "" : attribute).matches()) throw new OoaException("CR.BAD_INPUT", "Credential and attribute names use letters, digits, . _ -");
        if (value == null || value.isEmpty()) throw new OoaException("CR.BAD_INPUT", "The value is empty");
        String ref = locker + "/" + name + "/" + attribute;
        String ct = vault.encrypt(value, ref);
        db.tx(() -> {
            if (db.update("UPDATE credentials SET ciphertext = ?, updated = ?, updated_by = ? WHERE locker = ? AND name = ? AND attribute = ?", ct, now(), by.username(), locker, name, attribute) == 0)
                db.update("INSERT INTO credentials (locker, name, attribute, ciphertext, updated, updated_by) VALUES (?,?,?,?,?,?)", locker, name, attribute, ct, now(), by.username());
            return null;
        });
        audit.record(by.username(), "credential.set", "credential", ref, null, "ok", ip);
    }

    public void deleteCredential(User by, String locker, String name, String attribute, String ip) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", locker);
        if (l == null) throw notFound("Vault folder");
        if (!ownsLocker(by, l)) throw denied("change credentials in this vault folder");
        db.update("DELETE FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", locker, name, attribute);
        audit.record(by.username(), "credential.delete", "credential", locker + "/" + name + "/" + attribute, null, "ok", ip);
    }

    /** Just-in-time secret for a running bot: the run must be running on this device and its requester must consume the locker. */
    public String credentialForRun(Map<String, Object> d, String runId, String ref) {
        Map<String, Object> r = runOnDevice(d, runId);
        String[] p = ref == null ? new String[0] : ref.split("/");
        String actor = "agent:" + d.get("name") + " for " + r.get("requested_by");
        if (!"running".equals(r.get("status")) || p.length != 3) { audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "denied", null); throw new OoaException("VAULT.DENIED", "Credentials are only available to running agents"); }
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        Map<String, Object> u = db.one("SELECT role FROM users WHERE username = ?", r.get("requested_by"));
        if (l == null || u == null || !consumes((String) r.get("requested_by"), (String) u.get("role"), l)) {
            audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "denied", null);
            throw new OoaException("VAULT.DENIED", "'" + r.get("requested_by") + "' may not use vault folder '" + p[0] + "'", "Ask the vault folder owner to add the user or role as a consumer.");
        }
        Map<String, Object> c = db.one("SELECT ciphertext FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", p[0], p[1], p[2]);
        if (c == null) { audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "not_found", null); throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found"); }
        audit.record(actor, "credential.read", "credential", ref, Map.of("run", runId), "ok", null);
        return vault.decrypt((String) c.get("ciphertext"), ref);
    }

    public List<Map<String, Object>> globals(User by) {
        require(by, "globals.view", "view shared values");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : db.query("SELECT * FROM global_values ORDER BY name")) { Map<String, Object> m = new LinkedHashMap<>(g); m.remove("json_value"); m.put("value", JsonLite.parse((String) g.get("json_value"))); out.add(m); }
        return out;
    }

    public void setGlobal(User by, String name, Object value, String ip) {
        require(by, "globals.manage", "manage shared values");
        if (name == null || !name.matches("^[A-Za-z_][A-Za-z0-9_]{0,79}$")) throw new OoaException("CR.BAD_INPUT", "Shared value names use letters, digits and _ (not starting with a digit)");
        String json = JsonLite.write(value);
        db.tx(() -> {
            if (db.update("UPDATE global_values SET json_value = ?, updated = ?, updated_by = ? WHERE name = ?", json, now(), by.username(), name) == 0)
                db.update("INSERT INTO global_values (name, json_value, updated, updated_by) VALUES (?,?,?,?)", name, json, now(), by.username());
            return null;
        });
        audit.record(by.username(), "global.set", "global", name, null, "ok", ip);
    }

    public void deleteGlobal(User by, String name, String ip) {
        require(by, "globals.manage", "manage shared values");
        db.update("DELETE FROM global_values WHERE name = ?", name);
        audit.record(by.username(), "global.delete", "global", name, null, "ok", ip);
    }

    public List<Map<String, Object>> auditLog(User by, String q, int limit) {
        require(by, "audit.view", "view the audit trail");
        String like = "%" + (q == null ? "" : q.toLowerCase(Locale.ROOT)) + "%";
        return db.query("SELECT seq, ts, actor, action, object_type, object_id, details, result, ip FROM audit WHERE LOWER(actor) LIKE ? OR LOWER(action) LIKE ? OR LOWER(COALESCE(object_id, '')) LIKE ? " +
                "ORDER BY seq DESC LIMIT " + Math.max(1, Math.min(1000, limit)), like, like, like);
    }

    public Map<String, Object> dashboard(User by) {
        Map<String, Object> m = new LinkedHashMap<>();
        Instant day = now().minus(Duration.ofDays(1));
        m.put("bots", count("SELECT COUNT(*) AS n FROM bots"));
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
        if (Rbac.allowed(by.role(), "runs.view")) {
            // Runs per day for the last 7 days (UTC), by outcome.
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            Map<LocalDate, long[]> perDay = new TreeMap<>();
            for (int i = 6; i >= 0; i--) perDay.put(today.minusDays(i), new long[4]);
            for (Map<String, Object> r : db.query("SELECT status, created FROM runs WHERE created >= ?", today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant())) {
                long[] c = perDay.get(Instant.parse(String.valueOf(r.get("created"))).atZone(ZoneOffset.UTC).toLocalDate());
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
        m.put("user", publicUser(by));
        return m;
    }

    long count(String sql, Object... p) { return ((Number) db.one(sql, p).get("n")).longValue(); }
}
