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
    }

    public Map<String, Object> settings() {
        Map<String, Object> out = new LinkedHashMap<>(DEFAULTS);
        for (Map<String, Object> r : db.query("SELECT name, json_value FROM settings")) out.put((String) r.get("name"), JsonLite.parse((String) r.get("json_value")));
        return out;
    }

    public Object setting(String name) { return settings().get(name); }

    int intSetting(String name) { Object v = setting(name); return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v)); }

    public Map<String, Object> updateSettings(User by, Map<String, Object> changes, String ip) {
        cr.require(by, "settings.manage", "change settings");
        for (var e : changes.entrySet()) {
            if (!DEFAULTS.containsKey(e.getKey())) throw new OoaException("CR.BAD_INPUT", "Unknown setting '" + e.getKey() + "'", "Settings: " + DEFAULTS.keySet());
            Object v = e.getValue();
            if (DEFAULTS.get(e.getKey()) instanceof Number) {
                long n;
                try { n = v instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(v).trim()); }
                catch (NumberFormatException x) { throw new OoaException("CR.BAD_INPUT", "Setting '" + e.getKey() + "' needs a whole number"); }
                if (n < 0 || n > 100000) throw new OoaException("CR.BAD_INPUT", "Setting '" + e.getKey() + "' is out of range");
                v = n;
            } else v = String.valueOf(v == null ? "" : v);
            String json = JsonLite.write(v);
            if (db.update("UPDATE settings SET json_value = ?, updated = ?, updated_by = ? WHERE name = ?", json, now(), by.username(), e.getKey()) == 0)
                db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", e.getKey(), json, now(), by.username());
        }
        cr.audit.record(by.username(), "settings.update", "settings", null, changes, "ok", ip);
        return settings();
    }

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
        for (String r : Rbac.ROLES) out.add(Map.of("name", r, "builtin", true, "permissions", Rbac.permissions(r), "description", "Built-in role"));
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
        return new User((String) k.get("id"), (String) k.get("username"), (String) k.get("role"), (String) k.get("display_name"), false);
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

    public List<Map<String, Object>> queues(User by) {
        cr.require(by, "queues.view", "view work queues");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> q : db.query("SELECT * FROM queues ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(q);
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
        for (String s : List.of("new", "in_progress", "retry", "success", "failed", "deferred")) c.put(s, 0L);
        for (Map<String, Object> r : db.query("SELECT status, COUNT(*) AS n FROM queue_items WHERE queue_id = ? GROUP BY status", queueId)) c.put((String) r.get("status"), r.get("n"));
        return c;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveQueue(User by, String queueId, Map<String, Object> q, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
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
        cr.audit.record(by.username(), "queue.save", "queue", qid, Map.of("name", name), "ok", ip);
        return Map.of("id", qid);
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    public void deleteQueue(User by, String qid, String ip) {
        cr.require(by, "queues.manage", "manage work queues");
        if (cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status = 'in_progress'", qid) > 0)
            throw new OoaException("CR.CONFLICT", "Items are being worked on", "Stop processing and wait for them to finish.");
        db.tx(() -> { db.update("DELETE FROM queue_items WHERE queue_id = ?", qid); if (db.update("DELETE FROM queues WHERE id = ?", qid) == 0) throw notFound("Queue"); return null; });
        cr.audit.record(by.username(), "queue.delete", "queue", qid, null, "ok", ip);
    }

    /** Starts or stops automatic processing; processing needs an agent and a runner pool. The person who starts it is the run-as user. */
    public void setProcessing(User by, String qid, boolean on, String ip) {
        cr.require(by, "queues.work", "process work queues");
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
        if (db.one("SELECT id FROM queues WHERE id = ?", qid) == null) throw notFound("Queue");
        if (items == null || items.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Send at least one item");
        if (items.size() > 10000) throw new OoaException("CR.TOO_LARGE", "Add at most 10,000 items at a time");
        List<String> ids = new ArrayList<>();
        db.tx(() -> {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) throw new OoaException("CR.BAD_INPUT", "Each item is a JSON object");
                Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) m);
                Object ref = item.remove("_reference");
                Object pr = item.remove("_priority");
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
        List<Object> p = new ArrayList<>(List.of(qid));
        String where = " WHERE queue_id = ?";
        if (status != null && !status.isBlank()) { where += " AND status = ?"; p.add(status); }
        if (q != null && !q.isBlank()) { where += " AND (LOWER(COALESCE(reference, '')) LIKE ? OR LOWER(data) LIKE ?)"; String like = "%" + q.toLowerCase(Locale.ROOT) + "%"; p.add(like); p.add(like); }
        long total = cr.count("SELECT COUNT(*) AS n FROM queue_items" + where, p.toArray());
        List<Map<String, Object>> rows = db.query("SELECT * FROM queue_items" + where + " ORDER BY created DESC LIMIT " + Math.max(1, Math.min(500, limit)) + " OFFSET " + Math.max(0, offset), p.toArray());
        return Map.of("items", rows, "total", total);
    }

    /** Puts failed or deferred items back to new (attempts reset) or marks items deferred. */
    public void updateItems(User by, String qid, List<Object> ids, String action, String ip) {
        cr.require(by, "queues.work", "change work items");
        String to = switch (action) { case "retry" -> "new"; case "defer" -> "deferred"; default -> throw new OoaException("CR.BAD_INPUT", "Use retry or defer"); };
        int n = 0;
        for (Object o : ids == null ? List.of() : ids)
            n += db.update("UPDATE queue_items SET status = ?, attempts = CASE WHEN ? = 'new' THEN 0 ELSE attempts END, updated = ? WHERE id = ? AND queue_id = ? AND status IN ('failed', 'deferred', 'new', 'retry')",
                    to, to, now(), String.valueOf(o), qid);
        cr.audit.record(by.username(), "queue.items_" + action, "queue", qid, Map.of("count", n), "ok", ip);
    }

    /**
     * Gives each idle runner of a processing queue's pool the next item (priority, then age). The agent receives the item
     * as an input named work_item (and work_item_id) when it declares them. Called by the server clock.
     */
    public int dispatch() {
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
                    Map<String, Object> run = cr.startRun((String) q.get("run_as"), creator == null ? "viewer" : (String) creator.get("role"), (String) q.get("bot_path"), device,
                            inputs, "queue:" + q.get("id"), null);
                    db.update("UPDATE queue_items SET run_id = ? WHERE id = ?", run.get("run_id"), next.get("id"));
                    db.update("UPDATE runs SET queue_item_id = ? WHERE id = ?", next.get("id"), run.get("run_id"));
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
            if (v instanceof Map<?, ?> m && "input".equals(m.get("direction"))) out.put(String.valueOf(m.get("name")), m);
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
        db.update("UPDATE queue_items SET status = ?, error = ?, result = ?, updated = ?, finished = ? WHERE id = ?", next,
                error == null ? null : JsonLite.write(error), outputs == null ? null : JsonLite.write(outputs), now(),
                next.equals("retry") ? null : now(), item.get("id"));
    }

    // ===================== model connections =====================

    static final Set<String> PROVIDERS = Set.of("openai", "azure_openai", "anthropic", "openai_compatible");

    public List<Map<String, Object>> modelConnections(User by) {
        return db.query("SELECT name, provider, model, base_url, key_ref, description, updated, updated_by FROM model_connections ORDER BY name");
    }

    public void saveModelConnection(User by, Map<String, Object> c, String ip) {
        cr.require(by, "connections.manage", "manage model connections");
        String name = String.valueOf(c.getOrDefault("name", "")).trim();
        if (!name.matches("^[A-Za-z0-9_.-]{1,60}$")) throw new OoaException("CR.BAD_INPUT", "Connection names use letters, digits, . _ - (up to 60)");
        String provider = String.valueOf(c.get("provider"));
        if (!PROVIDERS.contains(provider)) throw new OoaException("CR.BAD_INPUT", "Provider must be one of " + PROVIDERS);
        String keyRef = (String) c.get("key_ref");
        if (keyRef != null && !keyRef.isBlank() && keyRef.split("/").length != 3) throw new OoaException("CR.BAD_INPUT", "The key is a vault reference: vault folder/credential/attribute");
        if (db.update("UPDATE model_connections SET provider = ?, model = ?, base_url = ?, key_ref = ?, description = ?, updated = ?, updated_by = ? WHERE name = ?",
                provider, c.get("model"), c.get("base_url"), blankToNull(keyRef), c.get("description"), now(), by.username(), name) == 0)
            db.update("INSERT INTO model_connections (name, provider, model, base_url, key_ref, description, updated, updated_by) VALUES (?,?,?,?,?,?,?,?)",
                    name, provider, c.get("model"), c.get("base_url"), blankToNull(keyRef), c.get("description"), now(), by.username());
        cr.audit.record(by.username(), "connection.save", "connection", name, Map.of("provider", provider), "ok", ip);
    }

    public void deleteModelConnection(User by, String name, String ip) {
        cr.require(by, "connections.manage", "manage model connections");
        if (db.update("DELETE FROM model_connections WHERE name = ?", name) == 0) throw notFound("Model connection");
        cr.audit.record(by.username(), "connection.delete", "connection", name, null, "ok", ip);
    }

    /** The connections handed to runners with each job: keys stay vault references ({"$credential": ref}). */
    Map<String, Object> connectionsForJob() {
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> c : db.query("SELECT * FROM model_connections ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.get("name")); m.put("provider", c.get("provider"));
            if (c.get("model") != null) m.put("model", c.get("model"));
            if (c.get("base_url") != null) m.put("base_url", c.get("base_url"));
            if (c.get("key_ref") != null) m.put("api_key", Map.of("$credential", c.get("key_ref")));
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
