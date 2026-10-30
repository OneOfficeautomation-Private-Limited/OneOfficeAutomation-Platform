package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;
import java.util.concurrent.Semaphore;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Agent APIs: an agent behind its own address and token, for other systems to call like a web service. A call runs the
 * agent's live version on its runner (or the least busy runner of its pool) as the person who made the task, waits
 * for the result up to the task's time limit and answers with the outputs. When the limit passes, or many calls are
 * already waiting, the answer is 202 with the run id, and the caller asks again with the same token. The token is
 * shown once, stored as a hash, can be replaced, and works only for its own task.
 */
public final class ApiTasks {
    static final int MAX_WAIT_SECONDS = 300;
    /** Calls that wait for their result at the same time; more get 202 at once (the HTTP server has 16 threads). */
    private final Semaphore waiting = new Semaphore(8);
    private final ControlRoom cr;
    private final Db db;

    ApiTasks(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    public List<Map<String, Object>> list(User by) {
        cr.require(by, "apitasks.manage", "manage Agent APIs");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : db.query("SELECT id, name, bot_path, device_id, pool_id, timeout_seconds, enabled, created_by, created, last_used, calls FROM api_tasks ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("url", "/api/apitasks/" + t.get("id") + "/run");
            m.put("serverless", ServerlessRuns.DEVICE.equals(t.get("device_id")));
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> create(User by, Map<String, Object> b, String ip) {
        cr.require(by, "apitasks.manage", "manage Agent APIs");
        cr.require(by, "runs.start", "start runs");
        String name = String.valueOf(b.getOrDefault("name", "")).trim();
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "API task names have 1 to 120 characters");
        String path = (String) b.get("bot_path");
        if (path == null || !path.endsWith(".flow.json")) throw new OoaException("CR.BAD_INPUT", "Choose an agent");
        cr.content(path, "production");
        cr.folders.require(by, path, "run", "make Agent APIs for agents");
        String device = b.get("device_id") instanceof String d && !d.isBlank() ? d : null, pool = b.get("pool_id") instanceof String p && !p.isBlank() ? p : null;
        if (Boolean.TRUE.equals(b.get("serverless")) || ServerlessRuns.DEVICE.equals(device)) {
            // run inside the Command Center: only agents that keep to web services and data toolkits
            cr.serverless.check((Map<String, Object>) cr.content(path, "production").get("flow"));
            device = (String) cr.serverless.device().get("id");
            pool = null;
        }
        if (device == null && pool == null) throw new OoaException("CR.BAD_INPUT", "Choose the runner or runner pool that runs the calls");
        if (device != null && db.one("SELECT id FROM devices WHERE id = ?", device) == null) throw notFound("Runner");
        if (pool != null && db.one("SELECT id FROM device_pools WHERE id = ?", pool) == null) throw notFound("Runner pool");
        int timeout = b.get("timeout_seconds") instanceof Number n ? n.intValue() : 60;
        if (timeout < 1 || timeout > MAX_WAIT_SECONDS) throw new OoaException("CR.BAD_INPUT", "The time limit is 1 to " + MAX_WAIT_SECONDS + " seconds");
        String id = id("task"), token = "ooat_" + Security.token(32);
        db.update("INSERT INTO api_tasks (id, name, bot_path, device_id, pool_id, token_hash, timeout_seconds, enabled, created_by, created, calls) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, name, path, device, pool, Security.sha256(token), timeout, true, by.username(), now(), 0);
        cr.audit.record(by.username(), "apitask.create", "apitask", id, Map.of("name", name, "bot", path), "ok", ip);
        return Map.of("id", id, "url", "/api/apitasks/" + id + "/run", "token", token);
    }

    Map<String, Object> task(String id) {
        Map<String, Object> t = db.one("SELECT * FROM api_tasks WHERE id = ?", id);
        if (t == null) throw notFound("API task");
        return t;
    }

    /** A new token; the old one stops working at once. */
    public Map<String, Object> newToken(User by, String id, String ip) {
        cr.require(by, "apitasks.manage", "manage Agent APIs");
        task(id);
        String token = "ooat_" + Security.token(32);
        db.update("UPDATE api_tasks SET token_hash = ? WHERE id = ?", Security.sha256(token), id);
        cr.audit.record(by.username(), "apitask.new_token", "apitask", id, null, "ok", ip);
        return Map.of("id", id, "token", token);
    }

    public void setEnabled(User by, String id, boolean on, String ip) {
        cr.require(by, "apitasks.manage", "manage Agent APIs");
        task(id);
        db.update("UPDATE api_tasks SET enabled = ? WHERE id = ?", on, id);
        cr.audit.record(by.username(), on ? "apitask.enable" : "apitask.disable", "apitask", id, null, "ok", ip);
    }

    public void delete(User by, String id, String ip) {
        cr.require(by, "apitasks.manage", "manage Agent APIs");
        if (db.update("DELETE FROM api_tasks WHERE id = ?", id) == 0) throw notFound("API task");
        cr.audit.record(by.username(), "apitask.delete", "apitask", id, null, "ok", ip);
    }

    /** Checks the task's token (constant time); a wrong token and an unknown task look the same. */
    Map<String, Object> authorise(String id, String token, String ip) {
        Map<String, Object> t = db.one("SELECT * FROM api_tasks WHERE id = ?", id);
        String h = token == null ? "" : Security.sha256(token);
        if (t == null || !Security.constantEquals(h, (String) t.get("token_hash"))) {
            cr.audit.record("api task", "apitask.call", "apitask", id, null, "denied", ip);
            throw new OoaException("CR.UNAUTHENTICATED", "Unknown API task or wrong token", null);
        }
        if (!Boolean.TRUE.equals(t.get("enabled"))) throw new OoaException("CR.FORBIDDEN", "This API task is switched off");
        return t;
    }

    /**
     * Runs the task's agent with the inputs and waits for the result. Answers {status, run_id, outputs, error}; the
     * second value tells whether the run had finished (false: 202, ask again with poll()).
     */
    @SuppressWarnings("unchecked")
    public Map.Entry<Boolean, Map<String, Object>> run(String id, String token, Map<String, Object> inputs, String ip) {
        Map<String, Object> t = authorise(id, token, ip);
        Map<String, Object> owner = db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", t.get("created_by"));
        if (owner == null) throw new OoaException("CR.FORBIDDEN", "The person who made this API task is disabled", "Make the API task again as an active user.");
        Map<String, Object> r = cr.startRun((String) t.get("created_by"), (String) owner.get("role"), (String) t.get("bot_path"), (String) t.get("device_id"), (String) t.get("pool_id"),
                inputs == null ? Map.of() : inputs, "api_task:" + id, ip, 5, null);
        db.update("UPDATE api_tasks SET last_used = ?, calls = calls + 1 WHERE id = ?", now(), id);
        String runId = (String) r.get("run_id");
        if (!waiting.tryAcquire()) return Map.entry(false, result(runId));
        try {
            long end = System.currentTimeMillis() + 1000L * ((Number) t.get("timeout_seconds")).intValue();
            while (System.currentTimeMillis() < end) {
                Map<String, Object> res = result(runId);
                if (!Set.of("queued", "running").contains(String.valueOf(res.get("status")))) return Map.entry(true, res);
                try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return Map.entry(false, result(runId));
        } finally { waiting.release(); }
    }

    /** The state of a run this task started (the same token). */
    public Map<String, Object> poll(String id, String token, String runId, String ip) {
        authorise(id, token, ip);
        Map<String, Object> r = db.one("SELECT source FROM runs WHERE id = ?", runId);
        if (r == null || !("api_task:" + id).equals(r.get("source"))) throw notFound("Run");
        return result(runId);
    }

    Map<String, Object> result(String runId) {
        Map<String, Object> r = db.one("SELECT id, status, outputs, error, duration_ms FROM runs WHERE id = ?", runId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("status", r.get("status"));
        m.put("outputs", r.get("outputs") == null ? null : JsonLite.parse((String) r.get("outputs")));
        m.put("error", r.get("error") == null ? null : JsonLite.parse((String) r.get("error")));
        m.put("duration_ms", r.get("duration_ms"));
        return m;
    }
}
