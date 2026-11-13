package com.oneofficeautomation.controlroom.runs;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Environment locks (gap G33, after Blue Prism's environment locks): a named lock that only one run holds at a time, so
 * two runners never use the same single-user system together (one SAP login, one shared mailbox session). Agents take,
 * check and give back locks with the Environment locks toolkit; the runner asks One CommandCenter through the Bot Agent.
 * <ul>
 * <li>A lock belongs to a run. When the run ends (succeeded, failed, stopped, or its runner is lost) every lock it held
 * is given back; the server clock also gives back any lock whose run is no longer running.</li>
 * <li>Each lock has a lease (an hour unless the step says otherwise). A lock held past its lease may be taken by
 * another run; the audit trail says whose it was. Taking it again from the run that holds it renews the lease.</li>
 * <li>Taking a lock is atomic on every engine: one INSERT that fails on the primary key when the lock is held, then one
 * conditional UPDATE that changes the holder only when the lock is free to take. Nothing is read first and then
 * written, so two runs (on any node of a cluster) never both get it.</li>
 * <li>A run gives back only its own locks. Giving back a lock it does not hold changes nothing and says so
 * (released false), since another run may have taken it after the lease ran out.</li>
 * <li>Administrators (permission locks.manage) can give back any lock from the console; that is audited.</li>
 * </ul>
 */
public final class EnvironmentLocks {
    /** Lease limits, in seconds: at least 5 seconds, at most a week. */
    static final int MIN_LEASE = 5, MAX_LEASE = 7 * 24 * 3600, DEFAULT_LEASE = 3600;
    static final int MAX_NAME = 200;

    private final ControlRoom cr;
    private final Db db;

    public EnvironmentLocks(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** A lock's name as typed (trimmed), checked. */
    static String name(Object raw) {
        String n = raw == null ? "" : String.valueOf(raw).trim();
        if (n.isEmpty()) throw new OoaException("LOCK.BAD_NAME", "Give the lock a name", "Use the name of the system it guards, e.g. SAP production login.");
        if (n.length() > MAX_NAME) throw new OoaException("LOCK.BAD_NAME", "A lock's name has at most " + MAX_NAME + " characters", "Shorten the lock's name.");
        return n;
    }

    /** The key a lock is stored under: names are compared without case, the same on every database engine. */
    static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    static int lease(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return DEFAULT_LEASE;
        long s;
        try { s = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim()); }
        catch (NumberFormatException e) { throw new OoaException("LOCK.BAD_LEASE", "The lease is a number of seconds, not " + raw, "Give the lease as a number of seconds."); }
        if (s < MIN_LEASE || s > MAX_LEASE) throw new OoaException("LOCK.BAD_LEASE", "The lease is between " + MIN_LEASE + " seconds and 7 days (" + MAX_LEASE + " seconds)", "Give a lease within the limits the message names.");
        return (int) s;
    }

    // ===================== for runs (through the Bot Agent) =====================

    /**
     * A running run asks about a lock: {op: acquire | release | check, name, lease_seconds, comment}. The run must be
     * on this runner and running; the lock's holder is always the run itself, never what the runner says.
     */
    public Map<String, Object> forRun(Map<String, Object> d, String runId, Map<String, Object> body) {
        Map<String, Object> run = cr.runOnDevice(d, runId);
        if (!"running".equals(run.get("status"))) throw new OoaException("LOCK.RUN_NOT_RUNNING", "Locks are only for running agents; run " + runId + " is " + run.get("status"), "Take locks only from a step of a running agent.");
        String op = String.valueOf(body.get("op"));
        String name = name(body.get("name"));
        return switch (op) {
            case "acquire" -> acquire(run, d, name, lease(body.get("lease_seconds")), body.get("comment") == null ? null : String.valueOf(body.get("comment")));
            case "release" -> release(run, d, name);
            case "check" -> check(name, runId);
            default -> throw new OoaException("CR.BAD_INPUT", "Unknown lock operation " + op);
        };
    }

    /** Takes the lock for the run when it is free, renews it when the run holds it already; never waits. */
    Map<String, Object> acquire(Map<String, Object> run, Map<String, Object> d, String name, int leaseSeconds, String comment) {
        String runId = (String) run.get("id"), key = key(name);
        String note = comment == null || comment.isBlank() ? null : comment.length() > 1000 ? comment.substring(0, 1000) : comment;
        Object heldBy = run.get("run_as") != null ? run.get("run_as") : run.get("requested_by");
        String actor = "agent:" + d.get("name");
        for (int attempt = 0; attempt < 3; attempt++) {
            Instant now = now(), expires = now.plusSeconds(leaseSeconds);
            // 1) nobody holds it: the INSERT succeeds; somebody does: it fails on the primary key, and nothing changed
            try {
                db.update("INSERT INTO environment_locks (lock_key, name, run_id, device_id, device_name, bot_path, held_by, acquired, expires, note) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        key, name, runId, d.get("id"), d.get("name"), run.get("bot_path"), heldBy, now, expires, note);
                cr.audit.record(actor, "lock.acquire", "lock", name, Map.of("run", runId, "lease_seconds", leaseSeconds), "ok", null);
                return held(true, db.one("SELECT * FROM environment_locks WHERE lock_key = ?", key), runId);
            } catch (OoaException e) {
                if (!"CR.CONFLICT".equals(e.code())) throw e;
            }
            // 2) held: take it over only when this run holds it (a renewal), its lease ran out, or its run is over. The
            // condition is part of the UPDATE, so whichever run's UPDATE the database applies first wins.
            Map<String, Object> before = db.one("SELECT run_id, expires FROM environment_locks WHERE lock_key = ?", key);   // for the audit note only
            int n = db.update("UPDATE environment_locks SET acquired = CASE WHEN run_id = ? THEN acquired ELSE ? END, name = ?, run_id = ?, device_id = ?, device_name = ?, "
                    + "bot_path = ?, held_by = ?, expires = ?, note = ? WHERE lock_key = ? AND (run_id = ? OR expires < ? "
                    + "OR NOT EXISTS (SELECT 1 FROM runs r WHERE r.id = environment_locks.run_id AND r.status = 'running'))",
                    runId, now, name, runId, d.get("id"), d.get("name"), run.get("bot_path"), heldBy, expires, note, key, runId, now);
            if (n == 1) {
                Map<String, Object> row = db.one("SELECT * FROM environment_locks WHERE lock_key = ?", key);
                if (before != null && !runId.equals(before.get("run_id"))) {
                    boolean expired = before.get("expires") != null && Instant.parse(before.get("expires").toString()).isBefore(now);
                    cr.audit.record(actor, "lock.take_over", "lock", name, Map.of("run", runId, "from_run", String.valueOf(before.get("run_id")),
                            "why", expired ? "its lease ran out" : "its run had ended"), "ok", null);
                } else if (before != null) cr.audit.record(actor, "lock.renew", "lock", name, Map.of("run", runId, "lease_seconds", leaseSeconds), "ok", null);
                else cr.audit.record(actor, "lock.acquire", "lock", name, Map.of("run", runId, "lease_seconds", leaseSeconds), "ok", null);
                return held(true, row, runId);
            }
            Map<String, Object> holder = db.one("SELECT * FROM environment_locks WHERE lock_key = ?", key);
            if (holder != null) return held(true, holder, runId);   // held by another run: not acquired
            // given back between the INSERT and the UPDATE: try again
        }
        Map<String, Object> last = db.one("SELECT * FROM environment_locks WHERE lock_key = ?", key);
        return held(last != null, last, runId);
    }

    /** Gives back the run's own lock; a lock it does not hold is left alone (released false). */
    Map<String, Object> release(Map<String, Object> run, Map<String, Object> d, String name) {
        String runId = (String) run.get("id");
        int n = db.update("DELETE FROM environment_locks WHERE lock_key = ? AND run_id = ?", key(name), runId);
        if (n > 0) cr.audit.record("agent:" + d.get("name"), "lock.release", "lock", name, Map.of("run", runId), "ok", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("released", n > 0);
        if (n == 0) {
            Map<String, Object> other = db.one("SELECT run_id FROM environment_locks WHERE lock_key = ?", key(name));
            out.put("message", other == null ? "The lock " + name + " was not held" : "The lock " + name + " is held by another run (" + other.get("run_id") + "), so it was left alone");
        }
        return out;
    }

    /** Whether the lock is held now, by whom and since when; a lock past its lease or whose run ended counts as free. */
    public Map<String, Object> check(String name, String askingRun) {
        Map<String, Object> row = db.one("SELECT l.*, r.status AS run_status FROM environment_locks l LEFT JOIN runs r ON r.id = l.run_id WHERE l.lock_key = ?", key(name));
        boolean live = row != null && "running".equals(row.get("run_status")) && Instant.parse(row.get("expires").toString()).isAfter(now());
        return held(live, live ? row : null, askingRun);
    }

    /** The answer to a run: held or acquired, and the holder's details. */
    private static Map<String, Object> held(boolean flag, Map<String, Object> row, String askingRun) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("held", flag);
        out.put("acquired", flag && row != null && askingRun.equals(row.get("run_id")));
        out.put("mine", row != null && askingRun.equals(row.get("run_id")));
        if (row != null) {
            out.put("name", row.get("name"));
            out.put("run_id", row.get("run_id"));
            out.put("runner", row.get("device_name"));
            out.put("held_by", row.get("held_by"));
            out.put("bot_path", row.get("bot_path"));
            out.put("since", row.get("acquired"));
            out.put("expires", row.get("expires"));
            out.put("comment", row.get("note"));
        }
        return out;
    }

    // ===================== run end =====================

    /** Gives back every lock the run held (called when it ends, whatever the reason). */
    public int releaseForRun(String runId, String why) {
        int n = 0;
        for (Map<String, Object> l : db.query("SELECT lock_key, name FROM environment_locks WHERE run_id = ?", runId))
            if (db.update("DELETE FROM environment_locks WHERE lock_key = ? AND run_id = ?", l.get("lock_key"), runId) > 0) {
                n++;
                cr.audit.record("system", "lock.release", "lock", (String) l.get("name"), Map.of("run", runId, "why", why), "ok", null);
            }
        return n;
    }

    /** The server clock: gives back locks whose run is no longer running (or no longer there), whatever ended it. */
    public int sweep() {
        int n = 0;
        for (Map<String, Object> l : db.query("SELECT l.run_id, r.status FROM environment_locks l LEFT JOIN runs r ON r.id = l.run_id WHERE r.id IS NULL OR r.status <> 'running'"))
            n += releaseForRun((String) l.get("run_id"), l.get("status") == null ? "its run is gone" : "its run ended (" + l.get("status") + ")");
        return n;
    }

    // ===================== console =====================

    /** Every lock held, oldest first, with whether its lease ran out. */
    public List<Map<String, Object>> list(User by) {
        cr.require(by, "devices.view", "see environment locks");
        Instant now = now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : db.query("SELECT l.name, l.run_id, l.device_id, l.device_name, l.bot_path, l.held_by, l.acquired, l.expires, l.note, r.status AS run_status "
                + "FROM environment_locks l LEFT JOIN runs r ON r.id = l.run_id ORDER BY l.acquired")) {
            Map<String, Object> m = new LinkedHashMap<>(l);
            m.put("comment", m.remove("note"));
            m.put("expired", Instant.parse(l.get("expires").toString()).isBefore(now));
            out.add(m);
        }
        return out;
    }

    /** An administrator gives back a lock, whoever holds it (audited with the run that held it). */
    public Map<String, Object> adminRelease(User by, String name, String ip) {
        cr.require(by, "locks.manage", "release environment locks");
        String n = name(name);
        Map<String, Object> row = db.one("SELECT run_id, name FROM environment_locks WHERE lock_key = ?", key(n));
        if (row == null) throw notFound("Lock " + n);
        int done = db.update("DELETE FROM environment_locks WHERE lock_key = ? AND run_id = ?", key(n), row.get("run_id"));
        cr.audit.record(by.username(), "lock.release", "lock", (String) row.get("name"), Map.of("run", String.valueOf(row.get("run_id")), "by_person", true), done > 0 ? "ok" : "not_found", ip);
        return Map.of("released", done > 0);
    }
}
