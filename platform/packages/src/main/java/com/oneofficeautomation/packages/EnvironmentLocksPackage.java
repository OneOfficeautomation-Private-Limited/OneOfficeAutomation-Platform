package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Environment locks (gap G33, after Blue Prism's environment locks): a named lock that only one run holds at a time,
 * so two agents never use the same single-user system together, such as one SAP login or one shared mailbox session.
 * <p>
 * With One CommandCenter the locks are kept there and shared by every runner; the Bot Agent relays each request and
 * One CommandCenter checks which run asks. In One AgentStudio without One CommandCenter, locks work on this computer
 * only: they are kept in memory and shared by the runs in this program, with the same rules.
 * <p>
 * A lock belongs to the run that took it. It is given back by Release lock, when the agent that took it ends (however
 * it ends), and with One CommandCenter also when the run is stopped or its runner is lost. Each lock has a lease: a
 * lock held past its lease may be taken by another run. Taking the lock again from the run that holds it renews the
 * lease. Releasing a lock this run does not hold changes nothing (released is false, with a warning in the log).
 */
@OoaPackage(id = "ooa.locks", name = "Environment locks", version = "1.0.0",
        description = "Named locks that one agent run holds at a time, so two runners never use the same single-user system together.")
public final class EnvironmentLocksPackage {
    /** The run's locks, kept as a session so they are given back when the agent that took them ends. */
    static final String SESSION = "ooa.locks";
    static final int MAX_WAIT = 24 * 3600;

    /** Where locks are kept: One CommandCenter (through the runner) or this computer. */
    interface Store { Map<String, Object> call(String op, String name, int leaseSeconds, String comment); }

    @SuppressWarnings("unchecked")
    static Store store(ActionContext ctx) {
        Function<Map<String, Object>, Map<String, Object>> remote;
        try { remote = ctx.service("environment_locks", Function.class); }
        catch (OoaException e) { remote = null; }
        if (remote != null) {
            Function<Map<String, Object>, Map<String, Object>> r = remote;
            return (op, name, lease, comment) -> {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("op", op); req.put("name", name);
                if (op.equals("acquire")) { req.put("lease_seconds", lease); if (comment != null && !comment.isBlank()) req.put("comment", comment); }
                return r.apply(req);
            };
        }
        String run = rootRun(ctx.runId());
        return (op, name, lease, comment) -> Local.call(op, name, run, lease, comment);
    }

    /** A child agent's run id is its parent's id with ".step" added: the lock belongs to the run at the top. */
    static String rootRun(String runId) { return runId == null ? "" : runId.contains(".") ? runId.substring(0, runId.indexOf('.')) : runId; }

    static String checkedName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new ActionException("LOCK.BAD_NAME", "Give the lock a name", "Use the name of the system it guards, e.g. SAP production login.");
        if (n.length() > 200) throw new ActionException("LOCK.BAD_NAME", "A lock's name has at most 200 characters", "Shorten the lock's name to 200 characters or fewer.");
        return n;
    }

    /** The locks this agent took; closing it (the agent ends) gives them back. */
    static final class Held implements AutoCloseable {
        final Set<String> names = ConcurrentHashMap.newKeySet();
        final Store store;
        Held(Store store) { this.store = store; }
        @Override public void close() {
            for (String n : names) try { store.call("release", n, 0, null); } catch (RuntimeException ignored) { /* One CommandCenter gives them back at run end */ }
            names.clear();
        }
    }

    static Held held(ActionContext ctx, Store store) {
        Object o = ctx.sessions().get(SESSION);
        if (o instanceof Held h) return h;
        Held h = new Held(store);
        ctx.sessions().put(SESSION, h);
        return h;
    }

    static String holder(Map<String, Object> r) {
        if (r.get("run_id") == null) return "";
        StringBuilder b = new StringBuilder("run " + r.get("run_id"));
        if (r.get("runner") != null) b.append(" on ").append(r.get("runner"));
        if (r.get("held_by") != null) b.append(" for ").append(r.get("held_by"));
        if (r.get("since") != null) b.append(" since ").append(r.get("since"));
        return b.toString();
    }

    @Action(id = "acquire", name = "Acquire lock", category = "Environment locks", timeoutSeconds = MAX_WAIT + 120,
            description = "Take a named lock so no other agent run uses the same system at the same time. Optionally wait until the run holding it gives it back. "
                + "The lock is given back by Release lock, or when this agent ends. With One CommandCenter the lock is shared by every runner; "
                + "in One AgentStudio on its own it works on this computer only.",
            panel = "name, wait_seconds, [Fail when the lock is not acquired: fail_if_not_acquired] | lease_seconds, comment")
    @Output(name = "acquired", type = OoaType.BOOLEAN, description = "True when this run now holds the lock.")
    @Output(name = "held_by", type = OoaType.STRING, description = "When not acquired: the run, runner and person holding it, and since when.")
    public Map<String, Object> acquire(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "The lock's name, e.g. SAP production login. Names are compared without case.") String name,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = MAX_WAIT,
                    description = "How long to wait for the lock when another run holds it; 0 tries once.") int waitSeconds,
            @Input(name = "lease_seconds", type = OoaType.NUMBER, required = false, defaultValue = "3600", min = 5, max = 604800,
                    description = "How long the lock is held at most. After it, another run may take the lock. Acquire it again to renew the lease.") int leaseSeconds,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Why the lock is held, shown in One CommandCenter.") String comment,
            @Input(name = "fail_if_not_acquired", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Fail with LOCK.TIMEOUT when the lock is not acquired, instead of returning acquired false.") boolean failIfNot) {
        String n = checkedName(name);
        if (leaseSeconds < 5 || leaseSeconds > 604800) throw new ActionException("LOCK.BAD_LEASE", "The lease is between 5 seconds and 7 days (604800 seconds)", "Give a lease between 5 seconds and 7 days.");
        int wait = Math.max(0, Math.min(MAX_WAIT, waitSeconds));
        Store store = store(ctx);
        long deadline = System.nanoTime() + wait * 1_000_000_000L;
        Map<String, Object> r;
        while (true) {
            r = store.call("acquire", n, leaseSeconds, comment);
            if (Boolean.TRUE.equals(r.get("acquired"))) {
                held(ctx, store).names.add(n);
                ctx.info("Acquired the lock " + n + " for " + leaseSeconds + " seconds");
                return Out.of("acquired", true, "held_by", "");
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            sleep(ctx, Math.min(1000, left / 1_000_000 + 1));
        }
        String who = holder(r);
        if (failIfNot) throw new ActionException("LOCK.TIMEOUT", "The lock " + n + " is held by another run" + (who.isEmpty() ? "" : " (" + who + ")")
                + (wait > 0 ? " and was not given back within " + wait + " seconds" : ""), "Wait longer, or see who holds it in One CommandCenter (Manage > Environment locks).");
        ctx.info("The lock " + n + " is held by another run" + (who.isEmpty() ? "" : ": " + who));
        return Out.of("acquired", false, "held_by", who);
    }

    private static void sleep(ActionContext ctx, long ms) {
        ctx.checkCancelled();
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run was stopped while waiting for a lock", "The run was stopped before this step finished. Run the agent again when it should go on."); }
        ctx.checkCancelled();
    }

    @Action(id = "release", name = "Release lock", category = "Environment locks",
            description = "Give back a lock this run holds, so another run can take it. A lock this run does not hold is left alone: released is false and the log says why.",
            panel = "name")
    @Output(name = "released", type = OoaType.BOOLEAN, description = "True when this run held the lock and gave it back.")
    public Map<String, Object> release(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "The lock's name.") String name) {
        String n = checkedName(name);
        Store store = store(ctx);
        Map<String, Object> r = store.call("release", n, 0, null);
        Object h = ctx.sessions().get(SESSION);
        if (h instanceof Held held) held.names.remove(n);
        boolean done = Boolean.TRUE.equals(r.get("released"));
        if (done) ctx.info("Released the lock " + n);
        else ctx.warn(String.valueOf(r.getOrDefault("message", "This run does not hold the lock " + n)));
        return Out.of("released", done);
    }

    @Action(id = "check", name = "Check lock", category = "Environment locks", idempotent = true,
            description = "See whether a lock is held, by which run, runner and person, and since when. A lock past its lease counts as free.",
            panel = "name")
    @Output(name = "held", type = OoaType.BOOLEAN, description = "True when a run holds the lock.")
    @Output(name = "mine", type = OoaType.BOOLEAN, description = "True when this run holds it.")
    @Output(name = "held_by", type = OoaType.STRING, description = "The run, runner and person holding it, and since when; empty when free.")
    @Output(name = "since", type = OoaType.STRING, description = "When it was taken (ISO 8601), or empty.")
    @Output(name = "expires", type = OoaType.STRING, description = "When its lease runs out (ISO 8601), or empty.")
    public Map<String, Object> check(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "The lock's name.") String name) {
        Map<String, Object> r = store(ctx).call("check", checkedName(name), 0, null);
        boolean held = Boolean.TRUE.equals(r.get("held"));
        return Out.of("held", held, "mine", held && Boolean.TRUE.equals(r.get("mine")), "held_by", held ? holder(r) : "",
                "since", held && r.get("since") != null ? String.valueOf(r.get("since")) : "", "expires", held && r.get("expires") != null ? String.valueOf(r.get("expires")) : "");
    }

    @Condition(id = "is_held", name = "Lock is held", description = "True when a run (this one or another) holds the lock now.")
    public boolean isHeld(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "The lock's name.") String name) {
        return Boolean.TRUE.equals(store(ctx).call("check", checkedName(name), 0, null).get("held"));
    }

    /** Locks on this computer only (One AgentStudio without One CommandCenter): one map in this program, same rules. */
    static final class Local {
        record Lock(String name, String runId, Instant acquired, Instant expires, String comment) { }
        static final ConcurrentHashMap<String, Lock> LOCKS = new ConcurrentHashMap<>();

        static Map<String, Object> call(String op, String name, String runId, int leaseSeconds, String comment) {
            String key = name.toLowerCase(Locale.ROOT);
            Instant now = Instant.now();
            return switch (op) {
                case "acquire" -> {
                    // compute() runs atomically for the key: free, past its lease, or this run's own (a renewal)
                    Lock l = LOCKS.compute(key, (k, cur) -> cur == null || cur.expires().isBefore(now) || cur.runId().equals(runId)
                            ? new Lock(name, runId, cur != null && cur.runId().equals(runId) ? cur.acquired() : now, now.plusSeconds(leaseSeconds), comment) : cur);
                    yield answer(l, runId, true);
                }
                case "release" -> {
                    boolean[] done = { false };
                    LOCKS.computeIfPresent(key, (k, cur) -> { if (cur.runId().equals(runId)) { done[0] = true; return null; } return cur; });
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("released", done[0]);
                    if (!done[0]) out.put("message", LOCKS.containsKey(key) ? "The lock " + name + " is held by another run, so it was left alone" : "The lock " + name + " was not held");
                    yield out;
                }
                case "check" -> {
                    Lock l = LOCKS.get(key);
                    yield answer(l != null && l.expires().isAfter(now) ? l : null, runId, false);
                }
                default -> throw new OoaException("CR.BAD_INPUT", "Unknown lock operation " + op, "Choose acquire, release or renew.");
            };
        }

        static Map<String, Object> answer(Lock l, String runId, boolean acquire) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("held", l != null);
            out.put("mine", l != null && l.runId().equals(runId));
            if (acquire) out.put("acquired", l != null && l.runId().equals(runId));
            if (l != null) {
                out.put("name", l.name()); out.put("run_id", l.runId()); out.put("runner", "this computer");
                out.put("since", l.acquired().toString()); out.put("expires", l.expires().toString());
                if (l.comment() != null) out.put("comment", l.comment());
            }
            return out;
        }
    }
}
