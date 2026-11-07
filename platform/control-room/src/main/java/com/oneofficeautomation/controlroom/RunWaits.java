package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Long-running agents (gap G36): a run suspends until something happens, and a new run carries on from where it stopped.
 * <ul>
 * <li>An agent's step (toolkit ooa.longrunning) asks to suspend, through its runner, with what to wait for: a time, a
 * Task inbox task being completed, a work item reaching success or failed, another run ending, or a call to a one-time
 * callback URL. One CommandCenter keeps the wait (table run_waits) with the agent's variable values and the step it
 * stopped at; the run then ends with status "suspended", so its runner is free.</li>
 * <li>The server clock (the cluster leader, every 5 seconds) notices waits that are over or timed out, marks the wait
 * resumed or timed_out (a conditional UPDATE, so one node does it once) and starts a new run of the same agent version,
 * which starts at the suspending step with the saved values; that step returns what ended the wait. The new run keeps
 * the first run's inputs, source, run-as user, mode, priority, work item and callback address, so a work item, a
 * workflow or a caller sees one chain of runs end once. The chain is kept in run_waits (run_id -> resumed_run_id).</li>
 * <li>A callback URL holds a random token that is shown once and kept only as its SHA-256 hash. Anyone with the URL
 * may POST a JSON body (at most 64 KB) once; the body becomes the step's result. The route is rate limited per address.</li>
 * <li>Stopping a suspended run (Activity, or the waits list) cancels its wait; an administrator (runs.stop) may also
 * resume a run at once. Everything is audited; checks are made as the run's user.</li>
 * </ul>
 */
public final class RunWaits {
    static final Set<String> KINDS = Set.of("time", "task", "queue_item", "callback", "job");
    /** Longest wait or timeout: a year. Shortest time wait: 1 second (the clock looks every 5 seconds). */
    static final long MAX_SECONDS = 365L * 24 * 3600;
    static final int MAX_VALUES = 1_000_000, MAX_PAYLOAD = 100_000, MAX_CALLBACK_BODY = 64 * 1024, MAX_CALLBACKS_PER_RUN = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ControlRoom cr;
    private final Db db;

    RunWaits(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ===================== from a running agent (ControlRoom.runRequest) =====================

    /** A one-time callback URL for this run; the token is returned here only and kept as its hash. */
    Map<String, Object> createCallback(Map<String, Object> run, String actor) {
        String runId = (String) run.get("id");
        if (cr.count("SELECT COUNT(*) AS n FROM run_waits WHERE run_id = ? AND kind = 'callback'", runId) >= MAX_CALLBACKS_PER_RUN)
            throw new OoaException("LONGRUN.TOO_MANY", "A run makes at most " + MAX_CALLBACKS_PER_RUN + " callback URLs");
        String base = String.valueOf(cr.platform.setting("public_url")).trim().replaceAll("/+$", "");
        if (base.isEmpty() || base.equals("null"))
            throw new OoaException("LONGRUN.NO_PUBLIC_URL", "One CommandCenter does not know its own address, so it cannot make a callback URL",
                "An administrator sets it in Administration > Settings (Public address).");
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String id = id("wait");
        db.update("INSERT INTO run_waits (id, run_id, bot_path, version, kind, ref, token_hash, status, created, created_by, run_as, device_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            id, runId, run.get("bot_path"), run.get("version"), "callback", id, sha256(token), "waiting", now(), run.get("requested_by"), run.get("run_as"), run.get("device_id"));
        cr.audit.record(actor, "run.callback_create", "run", runId, Map.of("callback", id), "ok", null);
        return Map.of("callback_id", id, "url", base + "/api/callbacks/" + token);
    }

    /** A task in the Task inbox made by an agent (an approval, or a form to fill); a wait for it may follow. */
    @SuppressWarnings("unchecked")
    Map<String, Object> createTask(Map<String, Object> run, Map<String, Object> a, String actor) {
        String kind = a.get("kind") instanceof String k && !k.isBlank() ? k : "approval";
        if (!Set.of("approval", "form").contains(kind)) throw new OoaException("CR.BAD_INPUT", "A task is an approval or a form");
        String title = text(a.get("title"), 300);
        if (title == null) throw new OoaException("CR.BAD_INPUT", "Give the task a title");
        String assignee = text(a.get("assignee"), 300);
        if (assignee == null) throw new OoaException("CR.BAD_INPUT", "Say who does the task", "user:NAME or role:NAME, several separated by commas.");
        List<String> people = new ArrayList<>();
        for (String p : assignee.split(",")) {
            String t = p.trim();
            if (!t.startsWith("user:") && !t.startsWith("role:")) throw new OoaException("CR.BAD_INPUT", "Assign the task to user:NAME or role:NAME, not " + t);
            people.add(t);
        }
        Map<String, Object> form = a.get("form") instanceof Map<?, ?> f ? (Map<String, Object>) f : null;
        if (kind.equals("form") && (form == null || !(form.get("fields") instanceof List<?>)))
            throw new OoaException("CR.BAD_INPUT", "A form task needs a form with fields", "Give {\"fields\": [{\"id\": \"amount\", \"label\": \"Amount\", \"type\": \"number\"}]}.");
        Map<String, Object> data = new LinkedHashMap<>(a.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of());
        data.put("_run", rootRun((String) run.get("id")));
        data.put("_agent", run.get("bot_path"));
        String dataJson = JsonLite.write(data), formJson = form == null ? null : JsonLite.write(form);
        if (dataJson.length() > MAX_PAYLOAD || (formJson != null && formJson.length() > MAX_PAYLOAD)) throw new OoaException("CR.BAD_INPUT", "The task's data or form is larger than 100,000 characters");
        Instant due = null;
        if (a.get("due_hours") instanceof Number n && n.doubleValue() > 0) due = now().plus(Duration.ofMinutes(Math.round(Math.min(n.doubleValue(), 24 * 365) * 60)));
        String id = id("task");
        db.update("INSERT INTO human_tasks (id, kind, title, description, form, data, assignee, status, created, due) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, kind, title, text(a.get("description"), 4000), formJson, dataJson, String.join(",", people), "open", now(), due);
        cr.audit.record(actor, "task.create", "task", id, Map.of("kind", kind, "run", run.get("id")), "ok", null);
        cr.mailPeople(people, "New task: " + title, "An agent asks you to " + (kind.equals("approval") ? "approve: " : "fill in: ") + title
            + "\n\nOpen the Task inbox in One CommandCenter to do it.", "task", id);
        return Map.of("task_id", id);
    }

    /**
     * The run asks to be suspended: {kind, ref, timeout_seconds, step_id, values, pool}. Checks are made as the run's
     * user; the wait is kept and the run is expected to end with status "suspended" right after.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> suspend(Map<String, Object> run, User as, Map<String, Object> a, String actor) {
        String runId = (String) run.get("id");
        if (ServerlessRuns.DEVICE.equals(run.get("device_id"))) throw new OoaException("LONGRUN.NOT_AVAILABLE", "Serverless Agent APIs cannot be suspended");
        String kind = String.valueOf(a.get("kind"));
        if (!KINDS.contains(kind)) throw new OoaException("CR.BAD_INPUT", "Wait for one of: " + String.join(", ", new TreeSet<>(KINDS)));
        String step = text(a.get("step_id"), 200);
        if (step == null) throw new OoaException("CR.BAD_INPUT", "The step to resume at is missing");
        if (db.one("SELECT id FROM run_waits WHERE run_id = ? AND status = 'waiting' AND step_id IS NOT NULL", runId) != null)
            throw new OoaException("CR.CONFLICT", "This run is already suspended");
        String values = JsonLite.write(a.get("values") instanceof Map<?, ?> v ? v : Map.of());
        if (values.length() > MAX_VALUES) throw new OoaException("LONGRUN.TOO_LARGE", "The agent's values take " + values.length() + " characters; at most " + MAX_VALUES + " can be kept while it waits",
            "Keep big tables and texts in a file or a work item, and only their name in a variable.");
        Instant timeoutAt = null;
        if (a.get("timeout_seconds") != null && !String.valueOf(a.get("timeout_seconds")).isBlank()) timeoutAt = now().plusSeconds(seconds(a.get("timeout_seconds"), "The timeout"));
        String poolId = null;
        if (a.get("pool") instanceof String p && !p.isBlank()) {
            Map<String, Object> pool = db.one("SELECT id FROM device_pools WHERE id = ? OR name = ?", p.strip(), p.strip());
            if (pool == null) throw new OoaException("CR.NOT_FOUND", "There is no runner pool '" + p + "'");
            poolId = (String) pool.get("id");
            cr.platform.requirePoolUse(as.username(), as.role(), poolId);
        }
        String ref = a.get("ref") == null ? null : String.valueOf(a.get("ref")).strip();
        Instant resumeAt = null;
        switch (kind) {
            case "time" -> { resumeAt = when(ref); ref = resumeAt.toString(); }
            case "task" -> {
                Map<String, Object> t = ref == null ? null : db.one("SELECT * FROM human_tasks WHERE id = ?", ref);
                if (t == null || !mayWaitForTask(t, as, runId)) throw new OoaException("CR.NOT_FOUND", "There is no task '" + ref + "' that " + as.username() + " may wait for");
            }
            case "queue_item" -> {
                Map<String, Object> i = ref == null ? null : db.one("SELECT queue_id FROM queue_items WHERE id = ?", ref);
                if (i == null) throw new OoaException("CR.NOT_FOUND", "There is no work item '" + ref + "'");
                cr.require(as, "queues.view", "see work queues");
                cr.platform.requireQueue(as, (String) i.get("queue_id"), "view", "see this work queue");
            }
            case "job" -> {
                cr.require(as, "runs.view", "see other runs");
                Map<String, Object> r = ref == null ? null : db.one("SELECT bot_path FROM runs WHERE id = ?", ref);
                if (r == null || !cr.folders.mayView(as, (String) r.get("bot_path"))) throw new OoaException("CR.NOT_FOUND", "There is no run '" + ref + "' that " + as.username() + " may see");
                if (rootRun(ref).equals(rootRun(runId))) throw new OoaException("CR.BAD_INPUT", "A run cannot wait for itself");
            }
            case "callback" -> {
                Map<String, Object> w = ref == null ? null : db.one("SELECT * FROM run_waits WHERE id = ? AND kind = 'callback'", ref);
                if (w == null || !runId.equals(w.get("run_id"))) throw new OoaException("CR.NOT_FOUND", "There is no callback '" + ref + "' of this run", "Make one with Create callback URL in this run.");
                if (!"waiting".equals(w.get("status"))) throw new OoaException("CR.CONFLICT", "That callback is " + w.get("status"));
                db.update("UPDATE run_waits SET step_id = ?, saved_values = ?, timeout_at = ?, pool_id = ?, device_id = ? WHERE id = ? AND status = 'waiting'",
                    step, values, timeoutAt, poolId, run.get("device_id"), ref);
                cr.audit.record(actor, "run.suspend", "run", runId, Map.of("wait", ref, "kind", kind), "ok", null);
                Map<String, Object> out = new LinkedHashMap<>(Map.of("wait_id", ref, "kind", kind));
                if (w.get("resume_at") != null) out.put("already", true);   // it was called before the run got here
                return out;
            }
            default -> throw new IllegalStateException(kind);
        }
        String id = id("wait");
        db.update("INSERT INTO run_waits (id, run_id, bot_path, version, step_id, saved_values, kind, ref, resume_at, timeout_at, status, created, created_by, run_as, device_id, pool_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, runId, run.get("bot_path"), run.get("version"), step, values, kind, ref, resumeAt, timeoutAt, "waiting", now(), run.get("requested_by"), run.get("run_as"),
            run.get("device_id"), poolId);
        cr.audit.record(actor, "run.suspend", "run", runId, Map.of("wait", id, "kind", kind, "ref", ref == null ? "" : ref), "ok", null);
        satisfyFinished();   // a task, item or run that has already ended is noticed at the next tick
        Map<String, Object> out = new LinkedHashMap<>(Map.of("wait_id", id, "kind", kind));
        if (resumeAt != null) out.put("resume_at", resumeAt.toString());
        return out;
    }

    boolean mayWaitForTask(Map<String, Object> t, User as, String runId) {
        if (cr.can(as, "users.manage")) return true;
        if (t.get("data") instanceof String d && JsonLite.parse(d) instanceof Map<?, ?> m && rootRun(runId).equals(m.get("_run"))) return true;   // made by this chain of runs
        return cr.workflows.assignedTo((String) t.get("assignee"), as);
    }

    static long seconds(Object raw, String what) {
        double s;
        try { s = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim()); }
        catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", what + " is a number of seconds, not " + raw); }
        if (s < 1 || s > MAX_SECONDS) throw new OoaException("CR.BAD_INPUT", what + " is between 1 second and 365 days");
        return Math.round(s);
    }

    /** A time wait's end: seconds from now, or a date and time with its offset (ISO 8601). */
    static Instant when(String ref) {
        if (ref == null || ref.isBlank()) throw new OoaException("CR.BAD_INPUT", "Say when to resume: a number of seconds, or a date and time such as 2026-11-07T09:00:00Z");
        Instant t;
        if (ref.matches("^\\d+(\\.\\d+)?$")) t = now().plusSeconds(seconds(ref, "The wait"));
        else {
            try { t = OffsetDateTime.parse(ref).toInstant(); }
            catch (RuntimeException e) {
                try { t = Instant.parse(ref); }
                catch (RuntimeException e2) { throw new OoaException("CR.BAD_INPUT", "'" + ref + "' is not a date and time with its offset, such as 2026-11-07T09:00:00+05:30"); }
            }
        }
        if (t.isAfter(now().plusSeconds(MAX_SECONDS))) throw new OoaException("CR.BAD_INPUT", "A run waits at most 365 days");
        return t;   // a time already past resumes at the next tick
    }

    // ===================== run end =====================

    /**
     * The run ended with this status. "suspended" stays only when the run registered its wait (otherwise "failed");
     * any other end cancels the run's unused callbacks and waits.
     */
    String onRunEnd(String runId, String status) {
        if (status.equals("suspended"))
            return db.one("SELECT id FROM run_waits WHERE run_id = ? AND status = 'waiting' AND step_id IS NOT NULL", runId) != null ? "suspended" : "failed";
        db.update("UPDATE run_waits SET status = 'cancelled', finished = ? WHERE run_id = ? AND status = 'waiting'", now(), runId);
        return status;
    }

    /** The first run of a chain of resumed runs (the run itself when it was not resumed). */
    String rootRun(String runId) {
        String id = runId;
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> w = db.one("SELECT run_id FROM run_waits WHERE resumed_run_id = ?", id);
            if (w == null) return id;
            id = (String) w.get("run_id");
        }
        return id;
    }

    /** For a resumed run's job: the step to start at, the saved values and what ended the wait; null for other runs. */
    @SuppressWarnings("unchecked")
    Map<String, Object> forJob(String runId) {
        Map<String, Object> w = db.one("SELECT step_id, saved_values, payload FROM run_waits WHERE resumed_run_id = ?", runId);
        if (w == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step_id", w.get("step_id"));
        out.put("values", w.get("saved_values") == null ? Map.of() : JsonLite.parse((String) w.get("saved_values")));
        out.put("answer", w.get("payload") == null ? Map.of() : JsonLite.parse((String) w.get("payload")));
        return out;
    }

    // ===================== the callback route (public, token-checked) =====================

    /** POST /api/callbacks/{token}: the body (a JSON object, at most 64 KB) ends the wait. Once only. */
    public Map<String, Object> callback(String token, byte[] body, String ip) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{20,100}$")) throw notFound("Callback");
        String hash = sha256(token);
        Map<String, Object> w = db.one("SELECT id, run_id, token_hash, status, resume_at FROM run_waits WHERE token_hash = ?", hash);
        // the lookup is by hash; the hashes are compared again in constant time
        if (w == null || !MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), String.valueOf(w.get("token_hash")).getBytes(StandardCharsets.US_ASCII)))
            throw notFound("Callback");
        if (!"waiting".equals(w.get("status")) || w.get("resume_at") != null) throw new OoaException("CR.CONFLICT", "This callback was already used, or its run no longer waits");
        if (body.length > MAX_CALLBACK_BODY) throw new OoaException("CR.TOO_LARGE", "A callback body is at most 64 KB");
        String text = new String(body, StandardCharsets.UTF_8);
        Object parsed;
        try { parsed = text.isBlank() ? new LinkedHashMap<>() : JsonLite.parse(text); }
        catch (IllegalArgumentException e) { throw new OoaException("CR.BAD_JSON", "The callback body is not valid JSON"); }
        Map<String, Object> result = parsed instanceof Map<?, ?> m ? new LinkedHashMap<>(cast(m)) : new LinkedHashMap<>(Map.of("value", parsed == null ? "" : parsed));
        if (db.update("UPDATE run_waits SET payload = ?, resume_at = ? WHERE id = ? AND status = 'waiting' AND resume_at IS NULL", JsonLite.write(result), now(), w.get("id")) != 1)
            throw new OoaException("CR.CONFLICT", "This callback was already used");
        cr.audit.record("callback", "run.callback", "run", (String) w.get("run_id"), Map.of("callback", w.get("id"), "bytes", body.length), "ok", ip);
        if (cr.cluster.isLeader()) Thread.ofVirtual().start(() -> { try { tick(); } catch (RuntimeException ignored) { /* the clock tries again */ } });
        return Map.of("accepted", true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    // ===================== the server clock =====================

    /**
     * Notices ended tasks, work items and runs; resumes the suspended runs whose wait is over or timed out; cancels
     * waits whose run ended some other way. Called by the cluster leader every few seconds. Returns runs resumed.
     */
    public synchronized int tick() {
        satisfyFinished();
        Instant t = now();
        db.update("UPDATE run_waits SET status = 'cancelled', finished = ? WHERE status = 'waiting' AND EXISTS (SELECT 1 FROM runs r WHERE r.id = run_waits.run_id "
            + "AND r.status IN ('success', 'failed', 'stopped'))", t);
        int n = 0;
        for (Map<String, Object> w : db.query("SELECT w.* FROM run_waits w JOIN runs r ON r.id = w.run_id WHERE w.status = 'waiting' AND w.step_id IS NOT NULL "
                + "AND w.resume_at <= ? AND r.status = 'suspended' ORDER BY w.resume_at LIMIT 100", t))
            if (resume(w, answer(w, false, null), "system", null) != null) n++;
        for (Map<String, Object> w : db.query("SELECT w.* FROM run_waits w JOIN runs r ON r.id = w.run_id WHERE w.status = 'waiting' AND w.step_id IS NOT NULL "
                + "AND w.timeout_at <= ? AND (w.resume_at IS NULL OR w.resume_at > ?) AND r.status = 'suspended' ORDER BY w.timeout_at LIMIT 100", t, t))
            if (resume(w, answer(w, true, null), "system", null) != null) n++;
        return n;
    }

    /** Marks waits whose task, work item or run has ended as due now, with what it ended with. */
    @SuppressWarnings("unchecked")
    void satisfyFinished() {
        for (Map<String, Object> r : db.query("SELECT w.id, w.ref, t.status, t.outcome, t.result, t.completed_by FROM run_waits w JOIN human_tasks t ON t.id = w.ref "
                + "WHERE w.kind = 'task' AND w.status = 'waiting' AND w.resume_at IS NULL AND t.status IN ('completed', 'cancelled')")) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("task_id", r.get("ref")); p.put("status", r.get("status")); p.put("outcome", r.get("outcome"));
            p.put("result", r.get("result") == null ? Map.of() : JsonLite.parse((String) r.get("result"))); p.put("completed_by", r.get("completed_by"));
            satisfy((String) r.get("id"), p);
        }
        for (Map<String, Object> r : db.query("SELECT w.id, w.ref, i.status, i.result, i.error FROM run_waits w JOIN queue_items i ON i.id = w.ref "
                + "WHERE w.kind = 'queue_item' AND w.status = 'waiting' AND w.resume_at IS NULL AND i.status IN ('success', 'failed')")) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("item_id", r.get("ref")); p.put("status", r.get("status"));
            p.put("result", r.get("result") == null ? Map.of() : JsonLite.parse((String) r.get("result")));
            p.put("error", r.get("error") == null ? null : JsonLite.parse((String) r.get("error")));
            satisfy((String) r.get("id"), p);
        }
        for (Map<String, Object> r : db.query("SELECT w.id, w.ref, j.status, j.outputs, j.error FROM run_waits w JOIN runs j ON j.id = w.ref "
                + "WHERE w.kind = 'job' AND w.status = 'waiting' AND w.resume_at IS NULL AND j.status IN ('success', 'failed', 'stopped', 'suspended')")) {
            if ("suspended".equals(r.get("status"))) {   // the run it waits for was itself suspended: follow it to the run that resumed it
                Map<String, Object> next = db.one("SELECT resumed_run_id FROM run_waits WHERE run_id = ? AND resumed_run_id IS NOT NULL", r.get("ref"));
                if (next != null) db.update("UPDATE run_waits SET ref = ? WHERE id = ? AND status = 'waiting'", next.get("resumed_run_id"), r.get("id"));
                continue;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("job_id", r.get("ref")); p.put("status", r.get("status"));
            p.put("outputs", r.get("outputs") == null ? Map.of() : JsonLite.parse((String) r.get("outputs")));
            p.put("error", r.get("error") == null ? null : JsonLite.parse((String) r.get("error")));
            satisfy((String) r.get("id"), p);
        }
    }

    private void satisfy(String waitId, Map<String, Object> payload) {
        String json = JsonLite.write(payload);
        if (json.length() > MAX_PAYLOAD) {   // too big to keep: what ended, without the data
            payload.remove("result"); payload.remove("outputs");
            payload.put("note", "The result was larger than 100,000 characters and was left out");
            json = JsonLite.write(payload);
        }
        db.update("UPDATE run_waits SET resume_at = ?, payload = ? WHERE id = ? AND status = 'waiting' AND resume_at IS NULL", now(), json, waitId);
    }

    /** What the resumed step gets: who or what ended the wait, whether it timed out, and the result (callback body, task answer...). */
    static Map<String, Object> answer(Map<String, Object> w, boolean timedOut, Map<String, Object> result) {
        Map<String, Object> a = new LinkedHashMap<>();
        Object stored = w.get("payload") instanceof String p && !p.isBlank() ? JsonLite.parse(p) : null;
        a.put("resumed_by", timedOut ? "timeout" : result != null ? "admin" : w.get("kind"));
        a.put("timed_out", timedOut);
        a.put("result", result != null ? result : !timedOut && stored instanceof Map<?, ?> m ? m : Map.of());
        return a;
    }

    /**
     * Ends the wait and starts the run that carries on. Returns the new run's id, or null when another node or call
     * already did it. When the new run cannot start, the suspended run fails with the reason.
     */
    String resume(Map<String, Object> w, Map<String, Object> answer, String actor, String ip) {
        boolean timedOut = Boolean.TRUE.equals(answer.get("timed_out"));
        String waitId = (String) w.get("id"), runId = (String) w.get("run_id");
        if (db.update("UPDATE run_waits SET status = ?, payload = ?, finished = ? WHERE id = ? AND status = 'waiting'",
                timedOut ? "timed_out" : "resumed", JsonLite.write(answer), now(), waitId) != 1) return null;
        Map<String, Object> run = db.one("SELECT * FROM runs WHERE id = ?", runId);
        if (run == null || !"suspended".equals(run.get("status"))) return null;
        try {
            String next = startNext(run, w);
            db.update("UPDATE run_waits SET resumed_run_id = ? WHERE id = ?", next, waitId);
            Map<String, Object> d = new LinkedHashMap<>(Map.of("from_run", runId, "wait", waitId, "kind", w.get("kind"), "resumed_by", answer.get("resumed_by")));
            cr.audit.record(actor, "run.resume", "run", next, d, "ok", ip);
            return next;
        } catch (RuntimeException e) {   // the wait is spent: the run must not stay suspended for ever
            Map<String, Object> err = new OoaException("LONGRUN.RESUME_FAILED", "The run could not be resumed: " + (e instanceof OoaException ? e.getMessage() : e.toString()),
                e instanceof OoaException o ? o.suggestedFix() : null).toMap();
            if (db.update("UPDATE runs SET status = 'failed', finished = ?, error = ? WHERE id = ? AND status = 'suspended'", now(), JsonLite.write(err), runId) == 1) {
                cr.audit.record(actor, "run.resume", "run", runId, Map.of("wait", waitId, "error", String.valueOf(e.getMessage())), "failed", ip);
                cr.afterRunEnded(run, "failed", Map.of("error", err));
            }
            return null;
        }
    }

    /** The new run: same agent version, inputs (with their latest values), source, people, mode, priority, work item and callback. */
    @SuppressWarnings("unchecked")
    private String startNext(Map<String, Object> run, Map<String, Object> w) {
        String path = (String) run.get("bot_path"), requestedBy = (String) run.get("requested_by");
        String principal = (String) (run.get("run_as") != null ? run.get("run_as") : requestedBy);
        if (db.one("SELECT id FROM users WHERE username = ? AND enabled = TRUE", principal) == null)
            throw new OoaException("LONGRUN.NO_USER", "The run's user " + principal + " is no longer an active user");
        Map<String, Object> starter = db.one("SELECT role FROM users WHERE username = ?", requestedBy);
        if (starter != null) {   // a person started it: they must still be allowed to run the agent
            String role = (String) starter.get("role");
            if (cr.rolesOf(requestedBy, role).stream().noneMatch(r -> cr.allowed(r, "runs.start"))) throw denied("start runs (" + requestedBy + ")");
            cr.folders.require(requestedBy, role, path, "run", "run agents");
        }
        String deviceId = (String) w.get("device_id");
        if (w.get("pool_id") != null) { String picked = cr.platform.pickDevice((String) w.get("pool_id")); if (picked != null) deviceId = picked; }
        Map<String, Object> dev = deviceId == null ? null : db.one("SELECT enabled FROM devices WHERE id = ?", deviceId);
        if (dev == null) throw new OoaException("CR.NOT_FOUND", "The runner of the run is gone", "Wait with a runner pool (the Pool field) so any runner of the pool can carry on.");
        if (!Boolean.TRUE.equals(dev.get("enabled"))) throw new OoaException("CR.DEVICE_DISABLED", "The runner of the run is disabled");
        cr.licensing.requireRunnable(deviceId, principal, String.valueOf(run.get("source")));
        Map<String, Object> flow = (Map<String, Object>) cr.content(path, run.get("version")).get("flow");
        Map<String, Object> inputs = run.get("inputs") == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) JsonLite.parse((String) run.get("inputs")));
        Map<String, Object> saved = w.get("saved_values") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) w.get("saved_values"));
        for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {   // an input changed before the wait keeps its new value
            Map<String, Object> v = (Map<String, Object>) o;
            String n = String.valueOf(v.get("name"));
            if (("input".equals(v.get("direction")) || "in_out".equals(v.get("direction"))) && !"Credential".equals(v.get("type")) && saved.containsKey(n) && saved.get(n) != null) inputs.put(n, saved.get(n));
        }
        String id = id("run");
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested, priority, callback_url, run_as, run_mode, queue_item_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, path, run.get("version"), deviceId, "queued", JsonLite.write(inputs), requestedBy, run.get("source"), now(), false, false, run.get("priority"),
            run.get("callback_url"), run.get("run_as"), run.get("run_mode"), run.get("queue_item_id"));
        cr.wake(deviceId, false);
        return id;
    }

    // ===================== console =====================

    /** Suspended runs and what they wait for (status waiting, or all); no values, payloads or token hashes. */
    public List<Map<String, Object>> list(User by, String status, int limit) {
        cr.require(by, "runs.view", "view runs");
        boolean all = "all".equals(status);
        String where = all ? "" : " AND w.status = ?";
        List<Object> p = new ArrayList<>();
        if (!all) p.add(status == null || status.isBlank() ? "waiting" : status);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> w : db.query("SELECT w.id, w.run_id, w.bot_path, w.version, w.step_id, w.kind, w.ref, w.status, w.resume_at, w.timeout_at, w.created, "
                + "w.created_by, w.run_as, w.resumed_run_id, w.finished, w.device_id, d.name AS device_name, r.status AS run_status, r.finished AS suspended "
                + "FROM run_waits w LEFT JOIN runs r ON r.id = w.run_id LEFT JOIN devices d ON d.id = w.device_id WHERE w.step_id IS NOT NULL" + where
                + " ORDER BY w.created DESC LIMIT " + Math.max(1, Math.min(500, limit)), p.toArray())) {
            if (!cr.folders.mayView(by, (String) w.get("bot_path"))) continue;
            Map<String, Object> m = new LinkedHashMap<>(w);
            if ("callback".equals(m.get("kind"))) m.put("ref", null);   // the callback's own id says nothing to people
            out.add(m);
        }
        return out;
    }

    /** What a run waits for and the runs before and after it in its chain (for the run's page). */
    public Map<String, Object> chain(String runId) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> before = db.one("SELECT run_id FROM run_waits WHERE resumed_run_id = ?", runId);
        if (before != null) m.put("resumed_from", before.get("run_id"));
        Map<String, Object> w = db.one("SELECT id, kind, ref, status, step_id, resume_at, timeout_at, resumed_run_id FROM run_waits WHERE run_id = ? AND step_id IS NOT NULL ORDER BY created DESC LIMIT 1", runId);
        if (w != null) {
            Map<String, Object> wait = new LinkedHashMap<>(w);
            if ("callback".equals(wait.get("kind"))) wait.put("ref", null);
            m.put("wait", wait);
            if (w.get("resumed_run_id") != null) m.put("resumed_as", w.get("resumed_run_id"));
        }
        return m;
    }

    /** An administrator resumes a suspended run now; the step gets {resumed_by: admin, result: the given result}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resumeNow(User by, String waitId, Map<String, Object> result, String ip) {
        cr.require(by, "runs.stop", "control runs");
        Map<String, Object> w = waitFor(by, waitId);
        Map<String, Object> r = new LinkedHashMap<>(result == null ? Map.of() : result);
        r.putIfAbsent("resumed_by_user", by.username());
        if (JsonLite.write(r).length() > MAX_PAYLOAD) throw new OoaException("CR.BAD_INPUT", "The result is larger than 100,000 characters");
        String next = resume(w, answer(w, false, r), by.username(), ip);
        if (next == null) throw new OoaException("CR.CONFLICT", "The run no longer waits");
        return Map.of("run_id", next);
    }

    /** Cancels the wait and stops the suspended run. */
    public void cancel(User by, String waitId, String ip) {
        cr.require(by, "runs.stop", "control runs");
        Map<String, Object> w = waitFor(by, waitId);
        stopSuspended((String) w.get("run_id"), by.username(), ip);
    }

    /** Stops a suspended run and cancels its wait (also from Stop in Activity). False when the run was not suspended. */
    boolean stopSuspended(String runId, String by, String ip) {
        Map<String, Object> run = db.one("SELECT * FROM runs WHERE id = ?", runId);
        Map<String, Object> err = Map.of("code", "RUN.CANCELLED", "message", "Stopped by " + by + " while it was suspended");
        if (run == null || db.update("UPDATE runs SET status = 'stopped', finished = ?, error = ? WHERE id = ? AND status = 'suspended'", now(), JsonLite.write(err), runId) != 1) return false;
        db.update("UPDATE run_waits SET status = 'cancelled', finished = ? WHERE run_id = ? AND status = 'waiting'", now(), runId);
        cr.audit.record(by, "run.wait_cancel", "run", runId, null, "ok", ip);
        cr.afterRunEnded(run, "stopped", Map.of("error", err));
        return true;
    }

    private Map<String, Object> waitFor(User by, String waitId) {
        Map<String, Object> w = db.one("SELECT * FROM run_waits WHERE id = ? AND step_id IS NOT NULL", waitId);
        if (w == null || !cr.folders.mayView(by, (String) w.get("bot_path"))) throw notFound("Wait");
        if (!"waiting".equals(w.get("status"))) throw new OoaException("CR.CONFLICT", "The run no longer waits (" + w.get("status") + ")");
        return w;
    }

    private static String text(Object o, int max) {
        if (o == null) return null;
        String s = String.valueOf(o).strip();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
