package com.oneofficeautomation.modules.insights;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.*;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * Operations analytics over the runs of a period (default: the last 30 days), optionally for one agent, runner,
 * person or source: totals and success rate, run time, how long runs take (distribution, average, 95th percentile),
 * runs per day, the agents run most, the longest and those that fail most, use per runner and per source, and each
 * agent's own statistics. Only agents the caller may see (folder permissions) are counted. Every list can be
 * downloaded as CSV.
 */
public final class Analytics {
    public static final int MAX_ROWS = 200_000;
    public static final long[] BUCKETS = {60_000, 300_000, 900_000, 3_600_000};
    public static final List<String> BUCKET_LABELS = List.of("under 1 min", "1-5 min", "5-15 min", "15-60 min", "over 1 hour");
    public static final List<String> REPORTS = List.of("most_run", "longest", "most_failed", "by_runner", "by_day", "by_source", "run_history");
    private final ModuleContext ctx;
    private final Db db;

    public Analytics(ModuleContext ctx) { this.ctx = ctx; this.db = ctx.db(); }

    record Run(String id, String bot, String device, String deviceName, String status, String created, Long ms, String source, String user, String errorCode) {}

    /** The runs of the period that match the filters and that the caller may see. */
    /** The period asked for: from and to (a date includes its whole day), or the last days (30) up to now. */
    public static Instant[] period(Map<String, String> q) {
        Instant to = q.get("to") == null || q.get("to").isBlank() ? now() : instant(q.get("to"), true);
        Instant from = q.get("from") == null || q.get("from").isBlank() ? to.minus(Duration.ofDays(intOr(q.get("days"), 30))) : instant(q.get("from"), false);
        if (from.isAfter(to)) throw new OoaException("CR.BAD_INPUT", "The start of the period is after its end");
        return new Instant[] {from, to};
    }

    public List<Run> runs(User by, Map<String, String> q) {
        ctx.require(by, "analytics.view", "view operations analytics");
        Instant[] pd = period(q);
        Instant from = pd[0], to = pd[1];
        List<String> where = new ArrayList<>(List.of("r.created >= ?", "r.created <= ?"));
        List<Object> p = new ArrayList<>(List.of(from, to));
        for (String[] f : new String[][] {{"bot", "r.bot_path"}, {"device", "r.device_id"}, {"user", "r.requested_by"}})
            if (q.get(f[0]) != null && !q.get(f[0]).isBlank()) { where.add(f[1] + " = ?"); p.add(q.get(f[0])); }
        if (q.get("source") != null && !q.get("source").isBlank()) { where.add("r.source LIKE ?"); p.add(q.get("source") + "%"); }
        p.add(MAX_ROWS);
        Map<String, Map<String, String>> perms = ctx.folderEntries();
        List<String> mine = ctx.principalsOf(by.username(), by.role());
        Map<String, Boolean> visible = new HashMap<>();
        List<Run> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT r.id, r.bot_path, r.device_id, d.name AS device_name, r.status, r.created, r.duration_ms, r.source, r.requested_by, r.error " +
                "FROM runs r LEFT JOIN devices d ON d.id = r.device_id WHERE " +
                String.join(" AND ", where) + " ORDER BY r.created LIMIT ?", p.toArray())) {
            String bot = (String) r.get("bot_path");
            if (!visible.computeIfAbsent(bot, b -> !ctx.folderAccessFor(mine, by.role(), ModuleContext.folderOf(b), perms).equals("none"))) continue;
            out.add(new Run((String) r.get("id"), bot, (String) r.get("device_id"), (String) r.get("device_name"), (String) r.get("status"), String.valueOf(r.get("created")),
                    r.get("duration_ms") instanceof Number n ? n.longValue() : null, (String) r.get("source"), (String) r.get("requested_by"), errorCode(r.get("error"))));
        }
        return out;
    }

    /** A run's error code from its stored error (JSON with a code); ERROR when the error cannot be read, null when there is none. */
    public static String errorCode(Object error) {
        if (error == null) return null;
        try { Object e = JsonLite.parse(String.valueOf(error)); return e instanceof Map<?, ?> em && em.get("code") != null ? String.valueOf(em.get("code")) : null; }
        catch (IllegalArgumentException ignored) { return "ERROR"; }
    }

    public static String sourceGroup(String s) { if (s == null) return "manual"; int i = s.indexOf(':'); return i < 0 ? s : s.substring(0, i); }

    public static long percentile(List<Long> sorted, double pct) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** Totals, success rate and timing for a group of runs. */
    public static Map<String, Object> stats(List<Run> runs) {
        long ok = 0, failed = 0, stopped = 0, active = 0, total = 0;
        List<Long> ms = new ArrayList<>();
        for (Run r : runs) {
            switch (r.status()) { case "success" -> ok++; case "failed" -> failed++; case "stopped" -> stopped++; default -> active++; }
            if (r.ms() != null) { ms.add(r.ms()); total += r.ms(); }
        }
        Collections.sort(ms);
        long finished = ok + failed;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", runs.size()); m.put("success", ok); m.put("failed", failed); m.put("stopped", stopped); m.put("active", active);
        m.put("success_rate", finished == 0 ? null : Math.round(1000.0 * ok / finished) / 10.0);
        m.put("total_ms", total);
        m.put("avg_ms", ms.isEmpty() ? null : total / ms.size());
        m.put("p95_ms", ms.isEmpty() ? null : percentile(ms, 95));
        m.put("max_ms", ms.isEmpty() ? null : ms.get(ms.size() - 1));
        return m;
    }

    static <K> List<Map<String, Object>> grouped(List<Run> runs, Function<Run, K> key, String keyName) {
        Map<K, List<Run>> g = new LinkedHashMap<>();
        for (Run r : runs) g.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : g.entrySet()) { Map<String, Object> m = new LinkedHashMap<>(); m.put(keyName, e.getKey()); m.putAll(stats(e.getValue())); out.add(m); }
        return out;
    }

    public static Comparator<Map<String, Object>> by(String k) { return Comparator.comparingDouble((Map<String, Object> m) -> m.get(k) instanceof Number n ? n.doubleValue() : -1).reversed(); }

    /** The Operations page: everything for the period at once. */
    public Map<String, Object> overview(User by, Map<String, String> q) {
        List<Run> runs = runs(by, q);
        int top = Math.max(1, Math.min(100, intOr(q.get("top"), 10)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totals", stats(runs));
        long[] counts = new long[BUCKETS.length + 1];
        for (Run r : runs) if (r.ms() != null) { int i = 0; while (i < BUCKETS.length && r.ms() >= BUCKETS[i]) i++; counts[i]++; }
        List<Object> dist = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) dist.add(Map.of("bucket", BUCKET_LABELS.get(i), "runs", counts[i]));
        m.put("duration_distribution", dist);
        m.put("by_day", byDay(runs));
        List<Map<String, Object>> perBot = grouped(runs, Run::bot, "bot_path");
        m.put("most_run", perBot.stream().sorted(by("runs")).limit(top).toList());
        m.put("longest", perBot.stream().filter(x -> x.get("avg_ms") != null).sorted(by("avg_ms")).limit(top).toList());
        m.put("most_failed", mostFailed(runs, perBot).stream().limit(top).toList());
        m.put("failure_reasons", failureReasons(runs).stream().limit(top).toList());
        m.put("by_hour", byHour(runs, zone(q.get("tz"))));
        // the same figures for the period just before, as long as this one (for "compared with before")
        Instant[] pd = period(q);
        Map<String, String> before = new HashMap<>(q);
        before.remove("days");
        before.put("from", pd[0].minus(Duration.between(pd[0], pd[1])).toString());
        before.put("to", pd[0].toString());
        m.put("previous", stats(runs(by, before)));
        m.put("by_runner", grouped(runs, r -> r.deviceName() == null ? r.device() : r.deviceName(), "runner").stream().sorted(by("total_ms")).toList());
        m.put("by_source", grouped(runs, r -> sourceGroup(r.source()), "source").stream().sorted(by("runs")).toList());
        m.put("period", Map.of("runs_counted", runs.size(), "capped", runs.size() >= MAX_ROWS));
        return m;
    }

    public static ZoneId zone(String tz) {
        try { return tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz); } catch (DateTimeException e) { return ZoneOffset.UTC; }
    }

    /** When runs start (Blue Prism's activity heatmap): runs and failed runs per weekday (Monday first) and hour, in the viewer's time zone. */
    public static Map<String, Object> byHour(List<Run> runs, ZoneId zone) {
        long[][] all = new long[7][24], failed = new long[7][24];
        for (Run r : runs) {
            ZonedDateTime t;
            try { t = Instant.parse(r.created()).atZone(zone); } catch (DateTimeException e) { continue; }
            int d = t.getDayOfWeek().getValue() - 1, h = t.getHour();
            all[d][h]++;
            if ("failed".equals(r.status())) failed[d][h]++;
        }
        List<Object> a = new ArrayList<>(), f = new ArrayList<>();
        for (int d = 0; d < 7; d++) { a.add(Arrays.stream(all[d]).boxed().toList()); f.add(Arrays.stream(failed[d]).boxed().toList()); }
        return Map.of("time_zone", zone.getId(), "runs", a, "failed", f);
    }

    public static List<Map<String, Object>> byDay(List<Run> runs) {
        TreeMap<String, List<Run>> g = new TreeMap<>();
        for (Run r : runs) g.computeIfAbsent(r.created().substring(0, Math.min(10, r.created().length())), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : g.entrySet()) { Map<String, Object> m = new LinkedHashMap<>(); m.put("day", e.getKey()); m.putAll(stats(e.getValue())); out.add(m); }
        return out;
    }

    public static List<Map<String, Object>> mostFailed(List<Run> runs, List<Map<String, Object>> perBot) {
        Map<String, Map<String, Long>> codes = new HashMap<>();
        for (Run r : runs) if ("failed".equals(r.status())) codes.computeIfAbsent(r.bot(), k -> new HashMap<>()).merge(r.errorCode() == null ? "UNKNOWN" : r.errorCode(), 1L, Long::sum);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : perBot) {
            if (((Number) b.get("failed")).longValue() == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>(b);
            long finished = ((Number) b.get("success")).longValue() + ((Number) b.get("failed")).longValue();
            m.put("failure_rate", Math.round(1000.0 * ((Number) b.get("failed")).longValue() / finished) / 10.0);
            m.put("top_errors", codes.getOrDefault((String) b.get("bot_path"), Map.of()).entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3)
                    .map(e -> e.getKey() + " (" + e.getValue() + ")").toList());
            out.add(m);
        }
        out.sort(by("failed"));
        return out;
    }

    /** Why runs failed, across all agents (AA's top failure reasons): each error code with its runs, agents and last time. */
    public static List<Map<String, Object>> failureReasons(List<Run> runs) {
        Map<String, List<Run>> g = new HashMap<>();
        for (Run r : runs) if ("failed".equals(r.status())) g.computeIfAbsent(r.errorCode() == null ? "UNKNOWN" : r.errorCode(), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : g.entrySet()) {
            Map<String, Long> agents = new HashMap<>();
            for (Run r : e.getValue()) agents.merge(r.bot(), 1L, Long::sum);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", e.getKey());
            m.put("runs", e.getValue().size());
            m.put("agents", agents.size());
            m.put("agent", agents.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null));   // the one failing with it most
            m.put("last", e.getValue().get(e.getValue().size() - 1).created());   // runs come oldest first
            out.add(m);
        }
        out.sort(by("runs"));
        return out;
    }

    /**
     * Runner capacity (Blue Prism Hub's utilisation views, AA's Devices dashboard): for the period, each runner's busy
     * time (its runs' running time inside the period, a run still going counted up to now), its share of the period and
     * how long its runs waited to start; the share of all enabled runners' time in use per weekday and hour and the busy
     * hours per day (in the time zone tz); how long runs waited to start (average and 95%); and the busy time as full-time
     * people (40 hours a week). Runner figures: every run counts, and no agent is named.
     */
    /** Run states that are still going: only these count as busy until now when a run has no finish time. */
    private static final Set<String> ACTIVE = Set.of("running", "paused", "claimed", "starting");

    public Map<String, Object> capacity(User by, Map<String, String> q) {
        ctx.require(by, "analytics.view", "view runner capacity");
        Instant[] pd = period(q);
        Instant from = pd[0], to = pd[1].isAfter(now()) ? now() : pd[1];
        ZoneId zone = zone(q.get("tz"));
        long[][] busy = new long[7][24], slots = new long[7][24];
        Map<String, Long> perDay = new TreeMap<>();
        Map<String, long[]> per = new LinkedHashMap<>();   // device: busy ms, runs, wait ms total, waited runs
        List<Long> waits = new ArrayList<>();
        for (Map<String, Object> d : db.query("SELECT id FROM devices WHERE enabled = ?", true)) per.put((String) d.get("id"), new long[4]);
        int enabled = per.size();
        for (Map<String, Object> r : db.query("SELECT device_id, status, created, started, finished FROM runs WHERE started IS NOT NULL AND started < ? AND (finished IS NULL OR finished > ?) LIMIT " + MAX_ROWS, to, from)) {
            // a run that ended without a finish time (a lost runner) is not busy until now; only runs still going are (13 Nov 2026: 189.9%)
            if (r.get("finished") == null && !ACTIVE.contains(String.valueOf(r.get("status")))) continue;
            Instant s = Instant.parse(String.valueOf(r.get("started"))), e = r.get("finished") == null ? to : Instant.parse(String.valueOf(r.get("finished")));
            long[] c = per.computeIfAbsent((String) r.get("device_id"), k -> new long[4]);
            Instant created = Instant.parse(String.valueOf(r.get("created")));
            if (!created.isBefore(from)) { c[1]++; long w = Math.max(0, Duration.between(created, s).toMillis()); c[2] += w; c[3]++; waits.add(w); }
            Instant a = s.isBefore(from) ? from : s, z = e.isAfter(to) ? to : e;
            while (a.isBefore(z)) {   // split the running time into the hours it falls in
                ZonedDateTime t = a.atZone(zone);
                Instant next = t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant();
                Instant end = next.isBefore(z) ? next : z;
                long ms = Duration.between(a, end).toMillis();
                busy[t.getDayOfWeek().getValue() - 1][t.getHour()] += ms;
                perDay.merge(t.toLocalDate().toString(), ms, Long::sum);
                c[0] += ms;
                a = end;
            }
        }
        for (ZonedDateTime t = from.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS); t.toInstant().isBefore(to); t = t.plusHours(1))
            slots[t.getDayOfWeek().getValue() - 1][t.getHour()]++;
        // AA's Devices dashboard: how each runner's runs of the period ended, its last run and its most common error
        Map<String, long[]> ends = new HashMap<>();   // device: succeeded, failed, stopped
        Map<String, String> lastRun = new HashMap<>();
        Map<String, Map<String, Long>> errors = new HashMap<>();
        Map<String, Long> allErrors = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT device_id, status, created, error FROM runs WHERE device_id IS NOT NULL AND created >= ? AND created <= ? LIMIT " + MAX_ROWS, from, to)) {
            String dev = (String) r.get("device_id"), status = String.valueOf(r.get("status")), created = String.valueOf(r.get("created"));
            per.computeIfAbsent(dev, k -> new long[4]);
            long[] e = ends.computeIfAbsent(dev, k -> new long[3]);
            if (status.equals("success")) e[0]++; else if (status.equals("failed")) e[1]++; else if (status.equals("stopped")) e[2]++;
            if (created.compareTo(lastRun.getOrDefault(dev, "")) > 0) lastRun.put(dev, created);
            if (status.equals("failed")) {
                String code = Objects.requireNonNullElse(errorCode(r.get("error")), "UNKNOWN");
                errors.computeIfAbsent(dev, k -> new HashMap<>()).merge(code, 1L, Long::sum);
                allErrors.merge(code, 1L, Long::sum);
            }
        }
        Map<String, Map<String, Object>> devices = new HashMap<>();
        for (Map<String, Object> d : db.query("SELECT id, name, os, enabled, last_seen FROM devices")) devices.put((String) d.get("id"), d);
        int online = 0, offline = 0;
        for (Map<String, Object> d : devices.values()) {
            if (!Boolean.TRUE.equals(d.get("enabled"))) continue;
            if (d.get("last_seen") != null && Instant.parse(d.get("last_seen").toString()).isAfter(now().minus(ONLINE))) online++; else offline++;
        }
        long period = Math.max(1, Duration.between(from, to).toMillis()), total = 0;
        List<Map<String, Object>> runners = new ArrayList<>();
        for (var e : per.entrySet()) {
            long[] c = e.getValue();
            total += c[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device_id", e.getKey()); m.put("busy_ms", c[0]); m.put("runs", c[1]);
            m.put("use", Math.min(100.0, Math.round(1000.0 * c[0] / period) / 10.0));   // a share of the period: never above 100%
            m.put("avg_wait_ms", c[3] == 0 ? null : c[2] / c[3]);
            Map<String, Object> d = devices.get(e.getKey());
            m.put("name", d == null ? null : d.get("name"));
            m.put("os", d == null ? null : d.get("os"));
            boolean on = d != null && d.get("last_seen") != null && Instant.parse(d.get("last_seen").toString()).isAfter(now().minus(ONLINE));
            m.put("status", d == null ? "removed" : !Boolean.TRUE.equals(d.get("enabled")) ? "disabled" : on ? "online" : "offline");
            long[] end = ends.getOrDefault(e.getKey(), new long[3]);
            m.put("success", end[0]); m.put("failed", end[1]); m.put("stopped", end[2]);
            m.put("last_run", lastRun.get(e.getKey()));
            m.put("top_error", errors.getOrDefault(e.getKey(), Map.of()).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null));
            runners.add(m);
        }
        runners.sort(by("busy_ms"));
        List<Object> heat = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            List<Object> row = new ArrayList<>();
            for (int h = 0; h < 24; h++) row.add(slots[d][h] == 0 || enabled == 0 ? null : Math.min(100.0, Math.round(1000.0 * busy[d][h] / (3_600_000.0 * slots[d][h] * enabled)) / 10.0));
            heat.add(row);
        }
        Collections.sort(waits);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString()); m.put("to", to.toString());
        m.put("runners", runners);
        m.put("enabled_runners", enabled);
        m.put("busy_ms", total);
        m.put("use", enabled == 0 ? null : Math.min(100.0, Math.round(1000.0 * total / ((double) period * enabled)) / 10.0));
        m.put("full_time_people", Math.round(10.0 * total / (period * 40.0 / 168.0)) / 10.0);
        m.put("avg_wait_ms", waits.isEmpty() ? null : waits.stream().mapToLong(Long::longValue).sum() / waits.size());
        m.put("p95_wait_ms", waits.isEmpty() ? null : percentile(waits, 95));
        m.put("waiting_now", ctx.count("SELECT COUNT(*) AS n FROM runs WHERE status = 'queued'"));
        m.put("online", online);
        m.put("offline", offline);
        m.put("schedules", ctx.count("SELECT COUNT(*) AS n FROM schedules WHERE enabled = TRUE"));
        Map<String, Object> next = db.one("SELECT MIN(next_run) AS next_run FROM schedules WHERE enabled = TRUE");
        m.put("next_schedule", next == null || next.get("next_run") == null ? null : String.valueOf(next.get("next_run")));
        List<Map<String, Object>> top = new ArrayList<>();
        allErrors.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
            .forEach(x -> top.add(Map.of("code", x.getKey(), "runs", x.getValue())));
        m.put("top_errors", top);
        m.put("heat", Map.of("time_zone", zone.getId(), "use", heat));
        List<Object> days = new ArrayList<>();
        for (var e : perDay.entrySet()) days.add(Map.of("day", e.getKey(), "busy_ms", e.getValue()));
        m.put("by_day", days);
        return m;
    }

    /** Who acts for One CommandCenter itself in the Audit Trail (anything else that is not a runner is a person, known or not). */
    public static final Set<String> SYSTEM_ACTORS = Set.of("system", "scheduler", "workflow", "webhook", "mcp", "api task", "api key", "ooa vault rekey");

    /** A runner by its name: "agent:PC-1 for alice" is the runner PC-1 acting for alice. */
    public static String runnerOf(String actor) { int i = actor.indexOf(" for "); return actor.startsWith("agent:") && i > 0 ? actor.substring(0, i) : actor; }

    /**
     * Audit figures (AA's Audit dashboard): for the period, the audit entries by kind of activity (the part of the action
     * before the dot), by who, with each one's kind (a person, a runner or the system; a runner once, whoever it acted
     * for), by result and by day (in tz); the people who acted (sign-in attempts with unknown names count too), the
     * sign-ins and failed sign-ins, the busiest day; and the latest entries that failed or were denied. Filters: actor
     * (a runner with everyone it acted for), kind and result. Needs the Audit Trail's permission.
     */
    public Map<String, Object> audit(User by, Map<String, String> q) {
        ctx.require(by, "audit.view", "view audit figures");
        Instant[] pd = period(q);
        ZoneId zone = zone(q.get("tz"));
        List<String> parts = new ArrayList<>(List.of("ts >= ?", "ts <= ?"));
        List<Object> p = new ArrayList<>(List.of(pd[0], pd[1]));
        if (q.get("actor") != null && !q.get("actor").isBlank()) {
            String actor = q.get("actor");
            if (actor.startsWith("agent:")) { parts.add("(actor = ? OR actor LIKE ?)"); p.add(actor); p.add(actor.replaceAll("[%_\\[\\]\\\\]", "_") + " for %"); }
            else { parts.add("actor = ?"); p.add(actor); }
        }
        if (q.get("kind") != null && !q.get("kind").isBlank()) { parts.add("action LIKE ?"); p.add(q.get("kind").replaceAll("[%_\\\\]", "") + ".%"); }
        if (q.get("result") != null && !q.get("result").isBlank()) { parts.add("result = ?"); p.add(q.get("result")); }
        String where = String.join(" AND ", parts);
        Map<String, Long> kinds = new HashMap<>(), results = new HashMap<>(), days = new TreeMap<>();
        Map<String, Long> actors = new HashMap<>();
        Set<String> acted = new HashSet<>();
        long entries = 0, signIns = 0, failedSignIns = 0;
        for (Map<String, Object> a : db.query("SELECT ts, actor, action, result FROM audit WHERE " + where + " ORDER BY seq DESC LIMIT " + MAX_ROWS, p.toArray())) {
            entries++;
            String action = String.valueOf(a.get("action")), actor = runnerOf(String.valueOf(a.get("actor"))), result = String.valueOf(a.get("result"));
            int dot = action.indexOf('.');
            kinds.merge(dot < 0 ? action : action.substring(0, dot), 1L, Long::sum);
            results.merge(result, 1L, Long::sum);
            actors.merge(actor, 1L, Long::sum);
            if (isOf(actor).equals("person")) acted.add(actor);
            days.merge(Instant.parse(String.valueOf(a.get("ts"))).atZone(zone).toLocalDate().toString(), 1L, Long::sum);
            if (action.equals("auth.login") || action.startsWith("auth.sso") || action.startsWith("auth.saml")) { signIns++; if (!result.equals("ok")) failedSignIns++; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", pd[0].toString()); m.put("to", pd[1].toString());
        m.put("time_zone", zone.getId());
        m.put("entries", entries);
        m.put("capped", entries >= MAX_ROWS);
        m.put("people", acted.size());
        m.put("sign_ins", signIns);
        m.put("failed_sign_ins", failedSignIns);
        m.put("by_kind", counted(kinds, "kind"));
        List<Map<String, Object>> who = counted(actors, "actor");
        for (Map<String, Object> w : who) {
            String a = (String) w.get("actor");
            w.put("is", isOf(a));
        }
        m.put("by_actor", who);
        m.put("by_result", counted(results, "result"));
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (var e : days.entrySet()) perDay.add(new LinkedHashMap<>(Map.of("day", e.getKey(), "entries", e.getValue())));
        m.put("by_day", perDay);
        m.put("busiest_day", perDay.stream().max(Comparator.comparingLong(x -> (Long) x.get("entries"))).orElse(null));
        p.add(20);
        m.put("not_done", db.query("SELECT seq, ts, actor, action, object_type, object_id, details, result FROM audit WHERE " + where + " AND result <> 'ok' ORDER BY seq DESC LIMIT ?", p.toArray()));
        return m;
    }

    public static String isOf(String actor) { return actor.startsWith("agent:") ? "runner" : SYSTEM_ACTORS.contains(actor) || actor.startsWith("system") ? "system" : "person"; }

    /** Counts as a list, the largest first: [{key: name, entries: n}]. */
    private static List<Map<String, Object>> counted(Map<String, Long> counts, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> { Map<String, Object> x = new LinkedHashMap<>(); x.put(key, e.getKey()); x.put("entries", e.getValue()); out.add(x); });
        return out;
    }

    /**
     * Work queue insights (UiPath's queue monitoring and Queues SLA, Blue Prism's queue composition, AA's Workload
     * dashboard): for each queue the caller may see, the items waiting now, overdue and at risk (waiting more than three
     * quarters of the target time), the oldest waiting; for the period, items added and finished, completed and failed,
     * the share finished within the target time, items finished an hour, average handling time (the run's running time)
     * and wait to start, the failures by error code, items added and finished per day (in tz); and the runners needed to
     * clear what waits within the target time (waiting items x average handling time / target time).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> queues(User by, Map<String, String> q) {
        ctx.require(by, "queues.view", "view work queues");
        Instant[] pd = period(q);
        Instant from = pd[0], to = pd[1], now = now();
        ZoneId zone = zone(q.get("tz"));
        double hours = Math.max(1.0 / 60, Duration.between(from, to).toMinutes() / 60.0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> qu : db.query("SELECT id, name, sla_minutes, processing, bot_path, pool_id FROM queues ORDER BY name")) {
            String id = (String) qu.get("id");
            if (!ctx.queueAllows(by, id, "view")) continue;
            int sla = qu.get("sla_minutes") instanceof Number n ? n.intValue() : 0;
            Map<String, Object> m = new LinkedHashMap<>(qu);
            Map<String, Object> counts = ctx.queueCounts(id);
            m.put("counts", counts);
            long waiting = ((Number) counts.get("new")).longValue() + ((Number) counts.get("retry")).longValue();
            m.put("waiting", waiting);
            Map<String, Object> oldest = db.one("SELECT MIN(created) AS t FROM queue_items WHERE queue_id = ? AND status IN ('new', 'retry')", id);
            m.put("oldest_waiting", oldest == null || oldest.get("t") == null ? null : String.valueOf(oldest.get("t")));
            if (sla > 0) {
                m.put("overdue", ctx.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status IN ('new', 'in_progress', 'retry') AND created < ?", id, now.minus(Duration.ofMinutes(sla))));
                m.put("at_risk", ctx.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status IN ('new', 'in_progress', 'retry') AND created >= ? AND created < ?",
                        id, now.minus(Duration.ofMinutes(sla)), now.minus(Duration.ofSeconds(sla * 45L))));
            }
            m.put("added", ctx.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND created >= ? AND created < ?", id, from, to));
            long ok = 0, failed = 0, inTime = 0, handled = 0, handleMs = 0, waited = 0, waitMs = 0;
            Map<String, Long> errors = new HashMap<>();
            Map<String, long[]> days = new TreeMap<>();   // day: added, finished
            for (Map<String, Object> r : db.query("SELECT created FROM queue_items WHERE queue_id = ? AND created >= ? AND created < ?", id, from, to))
                days.computeIfAbsent(Instant.parse(String.valueOf(r.get("created"))).atZone(zone).toLocalDate().toString(), k -> new long[2])[0]++;
            for (Map<String, Object> r : db.query("SELECT i.status, i.error, i.created, i.finished, r.started, r.duration_ms FROM queue_items i LEFT JOIN runs r ON r.id = i.run_id "
                    + "WHERE i.queue_id = ? AND i.finished >= ? AND i.finished < ? AND i.status IN ('success', 'failed')", id, from, to)) {
                Instant created = Instant.parse(String.valueOf(r.get("created"))), finished = Instant.parse(String.valueOf(r.get("finished")));
                days.computeIfAbsent(finished.atZone(zone).toLocalDate().toString(), k -> new long[2])[1]++;
                if ("success".equals(r.get("status"))) ok++;
                else {
                    failed++;
                    String code = "UNKNOWN";
                    if (r.get("error") != null) { try { Object e = JsonLite.parse((String) r.get("error")); if (e instanceof Map<?, ?> em && em.get("code") != null) code = String.valueOf(em.get("code")); } catch (RuntimeException ignored) { code = "ERROR"; } }
                    errors.merge(code, 1L, Long::sum);
                }
                if (sla > 0 && !finished.isAfter(created.plus(Duration.ofMinutes(sla)))) inTime++;
                if (r.get("duration_ms") instanceof Number d) { handled++; handleMs += d.longValue(); }
                if (r.get("started") != null) { waited++; waitMs += Math.max(0, Duration.between(created, Instant.parse(String.valueOf(r.get("started")))).toMillis()); }
            }
            long done = ok + failed;
            m.put("finished", done); m.put("success", ok); m.put("failed", failed);
            m.put("success_rate", done == 0 ? null : Math.round(1000.0 * ok / done) / 10.0);
            m.put("in_time_rate", sla == 0 || done == 0 ? null : Math.round(1000.0 * inTime / done) / 10.0);
            m.put("per_hour", Math.round(10.0 * done / hours) / 10.0);
            Long aht = handled == 0 ? null : handleMs / handled;
            m.put("avg_handling_ms", aht);
            m.put("avg_wait_ms", waited == 0 ? null : waitMs / waited);
            m.put("runners_needed", sla > 0 && aht != null && waiting > 0 ? (long) Math.ceil((double) waiting * aht / (sla * 60_000.0)) : null);
            m.put("errors", errors.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).map(e -> Map.of("code", e.getKey(), "items", e.getValue())).toList());
            List<Object> byDay = new ArrayList<>();
            for (var e : days.entrySet()) byDay.add(Map.of("day", e.getKey(), "added", e.getValue()[0], "finished", e.getValue()[1]));
            m.put("by_day", byDay);
            out.add(m);
        }
        return Map.of("queues", out, "from", from.toString(), "to", to.toString(), "time_zone", zone.getId());
    }

    /**
     * Value (AA's Center of Excellence dashboard, UiPath Insights' Business ROI): for the period, the hours of manual work
     * the agents' successful runs saved (each agent's minutes saved per run, or the default in Settings) and the money
     * (hours x the hourly cost in Settings), per agent and per top folder, per day (in tz), the same totals for the period
     * before, and the hours as full-time people (40 hours a week). A run counts once, so work queues are not counted
     * again. Only agents the caller may see.
     */
    /** What each agent and work queue saves: minutes a run or item and the cost of an hour, own or the defaults (Settings). */
    private final class Savings {
        final int dfltMinutes = ctx.intSetting("default_minutes_saved"), dfltCost = ctx.intSetting("hourly_cost");
        final Map<String, Integer> minutes = new HashMap<>(), cost = new HashMap<>(), estimate = new HashMap<>();
        final Map<String, Map<String, Object>> queues = new LinkedHashMap<>();
        Savings() {
            for (Map<String, Object> b : db.query("SELECT path, minutes_saved, hourly_cost, estimated_runs_month FROM bots WHERE minutes_saved IS NOT NULL OR hourly_cost IS NOT NULL OR estimated_runs_month IS NOT NULL")) {
                String p = (String) b.get("path");
                if (b.get("minutes_saved") instanceof Number n) minutes.put(p, n.intValue());
                if (b.get("hourly_cost") instanceof Number n) cost.put(p, n.intValue());
                if (b.get("estimated_runs_month") instanceof Number n) estimate.put(p, n.intValue());
            }
            for (Map<String, Object> qu : db.query("SELECT id, name, minutes_saved, hourly_cost FROM queues ORDER BY name")) queues.put((String) qu.get("id"), qu);
        }
        int minutesOf(String bot) { return minutes.getOrDefault(bot, dfltMinutes); }
        int costOf(String bot) { return cost.getOrDefault(bot, dfltCost); }
        /** The queue whose own minutes count for this run, or null when the run's agent counts. */
        Map<String, Object> queueOf(String queueId) { Map<String, Object> qu = queueId == null ? null : queues.get(queueId); return qu != null && qu.get("minutes_saved") instanceof Number ? qu : null; }
    }

    /** Successful runs that worked a queue item: run id to queue id. */
    private Map<String, String> queueItems(Instant from, Instant to) {
        Map<String, String> m = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT r.id, i.queue_id FROM runs r JOIN queue_items i ON i.id = r.queue_item_id WHERE r.status = 'success' AND r.created >= ? AND r.created <= ?", from, to))
            m.put((String) r.get("id"), (String) r.get("queue_id"));
        return m;
    }

    public Map<String, Object> value(User by, Map<String, String> q) {
        List<Run> runs = runs(by, q);
        Savings s = new Savings();
        ZoneId zone = zone(q.get("tz"));
        Instant[] pd = period(q);
        Map<String, String> items = queueItems(pd[0], pd[1]);
        // successful runs, minutes saved, run ms, minutes x cost of an hour
        Map<String, long[]> agents = new TreeMap<>(), folders = new TreeMap<>(), days = new TreeMap<>(), queues = new TreeMap<>();
        long minutes = 0, money60 = 0, ok = 0;
        for (Run r : runs) {
            if (!"success".equals(r.status())) continue;
            Map<String, Object> qu = s.queueOf(items.get(r.id()));
            int per = qu != null ? ((Number) qu.get("minutes_saved")).intValue() : s.minutesOf(r.bot());
            int cost = qu != null && qu.get("hourly_cost") instanceof Number n ? n.intValue() : qu != null ? s.dfltCost : s.costOf(r.bot());
            ok++; minutes += per; money60 += (long) per * cost;
            List<long[]> into = new ArrayList<>(List.of(agents.computeIfAbsent(r.bot(), k -> new long[4]), folders.computeIfAbsent(r.bot().contains("/") ? r.bot().substring(0, r.bot().indexOf('/')) : "(top)", k -> new long[4]),
                    days.computeIfAbsent(dayIn(r.created(), zone), k -> new long[4])));
            if (items.get(r.id()) != null) into.add(queues.computeIfAbsent(items.get(r.id()), k -> new long[4]));
            for (long[] c : into) { c[0]++; c[1] += per; c[2] += r.ms() == null ? 0 : r.ms(); c[3] += (long) per * cost; }
        }
        double periodDays = Math.max(1.0 / 1440, Duration.between(pd[0], pd[1]).toMinutes() / 1440.0);
        List<Map<String, Object>> perAgent = new ArrayList<>();
        for (var e : agents.entrySet()) {
            Map<String, Object> m = valueRow(e.getValue());
            String bot = e.getKey();
            m.put("bot_path", bot);
            m.put("minutes_each", s.minutesOf(bot));
            m.put("own_value", s.minutes.containsKey(bot));
            m.put("hourly_cost", s.costOf(bot));
            m.put("own_cost", s.cost.containsKey(bot));
            Integer est = s.estimate.get(bot);
            m.put("estimated_runs_month", est);
            m.put("estimated_hours_month", est == null ? null : Math.round(est * s.minutesOf(bot) / 6.0) / 10.0);
            m.put("actual_hours_month", Math.round(e.getValue()[1] / 60.0 * 30 / periodDays * 10) / 10.0);
            perAgent.add(m);
        }
        perAgent.sort(by("minutes"));
        List<Map<String, Object>> perQueue = new ArrayList<>();
        for (var e : s.queues.entrySet()) {
            long[] c = queues.getOrDefault(e.getKey(), new long[4]);
            if (!ctx.queueAllows(by, e.getKey(), "view")) continue;
            Map<String, Object> m = valueRow(c);
            m.put("queue_id", e.getKey());
            m.put("name", e.getValue().get("name"));
            m.put("minutes_each", e.getValue().get("minutes_saved"));
            m.put("hourly_cost", e.getValue().get("hourly_cost"));
            perQueue.add(m);
        }
        List<Map<String, Object>> perFolder = new ArrayList<>();
        for (var e : folders.entrySet()) { Map<String, Object> m = valueRow(e.getValue()); m.put("folder", e.getKey()); perFolder.add(m); }
        perFolder.sort(by("minutes"));
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (var e : days.entrySet()) { Map<String, Object> m = valueRow(e.getValue()); m.put("day", e.getKey()); perDay.add(m); }
        // the period before, as long as this one
        Map<String, String> before = new HashMap<>(q);
        before.remove("days");
        before.put("from", pd[0].minus(Duration.between(pd[0], pd[1])).toString());
        before.put("to", pd[0].toString());
        long[] prev = saved(by, before, s);
        // this year so far (in tz), for the goal, and at this pace by its end
        ZonedDateTime nowZ = now().atZone(zone);
        Instant jan1 = nowZ.withDayOfYear(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant();
        Map<String, String> year = new HashMap<>(q);
        year.remove("days");
        year.put("from", jan1.toString());
        year.put("to", now().toString());
        long[] ytd = saved(by, year, s);
        double yearShare = Math.max(1e-6, Duration.between(jan1, now()).toMinutes() / (double) Duration.between(jan1, nowZ.plusYears(1).withDayOfYear(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant()).toMinutes());
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("hours", ctx.intSetting("value_goal_hours"));
        goal.put("money", ((Number) ctx.setting("value_goal_money")).longValue());
        goal.put("year_hours", Math.round(ytd[0] / 6.0) / 10.0);
        goal.put("year_money", Math.round(ytd[1] / 60.0));
        goal.put("pace_hours", Math.round(ytd[0] / 6.0 / yearShare) / 10.0);
        goal.put("pace_money", Math.round(ytd[1] / 60.0 / yearShare));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", String.valueOf(ctx.setting("currency")));
        m.put("hourly_cost", s.dfltCost);
        m.put("default_minutes_saved", s.dfltMinutes);
        m.put("successful_runs", ok);
        m.put("hours", Math.round(minutes / 6.0) / 10.0);
        m.put("money", Math.round(money60 / 60.0));
        m.put("full_time_people", Math.round(10.0 * (minutes / 60.0) / (periodDays * 24 * 40.0 / 168.0)) / 10.0);
        m.put("previous_hours", Math.round(prev[0] / 6.0) / 10.0);
        m.put("previous_money", Math.round(prev[1] / 60.0));
        m.put("agents", perAgent);
        m.put("queues", perQueue);
        m.put("folders", perFolder);
        m.put("by_day", perDay);
        m.put("goal", goal);
        return m;
    }

    /** Minutes saved and minutes x cost of an hour over a period, counted as value() counts them. */
    private long[] saved(User by, Map<String, String> q, Savings s) {
        Instant[] pd = period(q);
        Map<String, String> items = queueItems(pd[0], pd[1]);
        long minutes = 0, money60 = 0;
        for (Run r : runs(by, q)) {
            if (!"success".equals(r.status())) continue;
            Map<String, Object> qu = s.queueOf(items.get(r.id()));
            int per = qu != null ? ((Number) qu.get("minutes_saved")).intValue() : s.minutesOf(r.bot());
            int cost = qu != null && qu.get("hourly_cost") instanceof Number n ? n.intValue() : qu != null ? s.dfltCost : s.costOf(r.bot());
            minutes += per; money60 += (long) per * cost;
        }
        return new long[] { minutes, money60 };
    }

    private static Map<String, Object> valueRow(long[] c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", c[0]); m.put("minutes", c[1]);
        m.put("hours", Math.round(c[1] / 6.0) / 10.0);
        m.put("money", Math.round(c[3] / 60.0));
        m.put("run_hours", Math.round(c[2] / 360_000.0) / 10.0);
        return m;
    }

    public static String dayIn(String created, ZoneId zone) {
        try { return Instant.parse(created).atZone(zone).toLocalDate().toString(); } catch (DateTimeException e) { return created.substring(0, Math.min(10, created.length())); }
    }

    /** One agent: its totals and timing, runs per day, the errors it fails with, its runners and its last runs. */
    public Map<String, Object> agent(User by, String path, Map<String, String> q) {
        ctx.requirePathAccess(by, path, "view", "view agent statistics");
        Map<String, String> f = new HashMap<>(q);
        f.put("bot", path);
        List<Run> runs = runs(by, f);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bot_path", path);
        m.put("totals", stats(runs));
        m.put("by_day", byDay(runs));
        Map<String, Long> codes = new TreeMap<>();
        for (Run r : runs) if ("failed".equals(r.status())) codes.merge(r.errorCode() == null ? "UNKNOWN" : r.errorCode(), 1L, Long::sum);
        m.put("errors", codes.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(e -> Map.of("code", e.getKey(), "runs", e.getValue())).toList());
        m.put("by_runner", grouped(runs, r -> r.deviceName() == null ? r.device() : r.deviceName(), "runner"));
        Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        Run lastFail = null;
        for (Run r : runs) if ("failed".equals(r.status())) lastFail = r;
        m.put("last_run", last == null ? null : Map.of("id", last.id(), "status", last.status(), "created", last.created()));
        m.put("last_failure", lastFail == null ? null : Map.of("id", lastFail.id(), "created", lastFail.created(), "code", String.valueOf(lastFail.errorCode())));
        return m;
    }

    /**
     * Business > Agents (AA's Business dashboards): every agent the caller may see whose runs in the period returned
     * outputs, with the outputs' names (KPI-marked first), its runs, the runs that returned a value, and its last run.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> businessAgents(User by, Map<String, String> q) {
        Map<String, long[]> counts = new TreeMap<>();   // runs, runs with a value
        Map<String, String> last = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (Run r : runs(by, q)) {
            counts.computeIfAbsent(r.bot(), k -> new long[2])[0]++;
            if (r.created().compareTo(last.getOrDefault(r.bot(), "")) > 0) last.put(r.bot(), r.created());
            ids.add(r.id());
        }
        Map<String, Set<String>> names = new HashMap<>();
        Instant[] pd = period(q);
        for (Map<String, Object> r : db.query("SELECT id, bot_path, outputs FROM runs WHERE created >= ? AND created <= ? AND outputs IS NOT NULL LIMIT " + MAX_ROWS, pd[0], pd[1])) {
            if (!ids.contains((String) r.get("id"))) continue;
            Object o;
            try { o = JsonLite.parse(String.valueOf(r.get("outputs"))); } catch (IllegalArgumentException e) { continue; }
            if (!(o instanceof Map<?, ?> m) || m.isEmpty()) continue;
            String bot = (String) r.get("bot_path");
            names.computeIfAbsent(bot, k -> new TreeSet<>()).addAll(((Map<String, Object>) m).keySet());
            if (m.values().stream().anyMatch(v -> v != null && !"".equals(v))) counts.get(bot)[1]++;
        }
        Map<String, Set<String>> kpis = new HashMap<>();
        for (Map<String, Object> k : db.query("SELECT DISTINCT bot_path, name FROM kpi_values")) kpis.computeIfAbsent((String) k.get("bot_path"), x -> new HashSet<>()).add((String) k.get("name"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : names.entrySet()) {
            Set<String> marked = kpis.getOrDefault(e.getKey(), Set.of());
            List<String> values = new ArrayList<>(e.getValue());
            values.sort(Comparator.comparing((String v) -> !marked.contains(v)).thenComparing(Comparator.naturalOrder()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bot_path", e.getKey());
            m.put("values", values);
            m.put("kpis", values.stream().filter(marked::contains).toList());
            m.put("runs", counts.get(e.getKey())[0]);
            m.put("runs_with_values", counts.get(e.getKey())[1]);
            m.put("last_run", last.get(e.getKey()));
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("runs_with_values")).longValue()).thenComparing(m -> String.valueOf(m.get("bot_path"))));
        return Map.of("agents", out);
    }

    /**
     * An agent's data profile (AA's data profile): for each output it returned in the period, its kind (number, text,
     * yes/no, list or mixed), how often it had a value, its ten most common values and how many different values; for
     * numbers the smallest, largest, average and total, and the total per day (in tz). Also the latest 20 runs' values.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> variables(User by, String path, Map<String, String> q) {
        ctx.requirePathAccess(by, path, "view", "view agent statistics");
        Map<String, String> f = new HashMap<>(q);
        f.put("bot", path);
        Set<String> ids = new HashSet<>();
        for (Run r : runs(by, f)) ids.add(r.id());
        Map<String, List<Object>> values = new TreeMap<>();
        Map<String, Map<String, Double>> perDay = new HashMap<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        ZoneId zone = zone(q.get("tz"));
        int n = 0;
        for (Map<String, Object> r : db.query("SELECT id, status, created, outputs FROM runs WHERE bot_path = ? AND outputs IS NOT NULL ORDER BY created DESC LIMIT 5000", path)) {
            if (!ids.contains((String) r.get("id"))) continue;
            Object o;
            try { o = JsonLite.parse((String) r.get("outputs")); } catch (IllegalArgumentException e) { continue; }
            if (!(o instanceof Map<?, ?> m)) continue;
            n++;
            if (recent.size() < 20) recent.add(Map.of("id", r.get("id"), "status", String.valueOf(r.get("status")), "created", String.valueOf(r.get("created")), "outputs", m));
            String day = dayIn(String.valueOf(r.get("created")), zone);
            for (var e : ((Map<String, Object>) m).entrySet()) {
                values.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                if (e.getValue() instanceof Number num) perDay.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).merge(day, num.doubleValue(), Double::sum);
            }
        }
        List<Object> out = new ArrayList<>();
        for (var e : values.entrySet()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("variable", e.getKey());
            List<Object> vals = e.getValue();
            long set = vals.stream().filter(x -> x != null && !"".equals(x)).count();
            v.put("runs_with_value", set);
            v.put("runs", n);
            List<Double> nums = vals.stream().filter(x -> x instanceof Number).map(x -> ((Number) x).doubleValue()).toList();
            if (!nums.isEmpty() && nums.size() == set) {
                v.put("min", nums.stream().mapToDouble(d -> d).min().orElse(0));
                v.put("max", nums.stream().mapToDouble(d -> d).max().orElse(0));
                v.put("avg", Math.round(nums.stream().mapToDouble(d -> d).average().orElse(0) * 100) / 100.0);
                v.put("sum", Math.round(nums.stream().mapToDouble(d -> d).sum() * 100) / 100.0);
                List<Map<String, Object>> days = new ArrayList<>();
                perDay.getOrDefault(e.getKey(), Map.of()).forEach((d, x) -> days.add(Map.of("day", d, "sum", Math.round(x * 100) / 100.0)));
                v.put("by_day", days);
            }
            Set<String> kinds = new HashSet<>();
            for (Object x : vals) if (x != null && !"".equals(x)) kinds.add(x instanceof Number ? "number" : x instanceof Boolean ? "yes/no" : x instanceof List ? "list" : x instanceof Map ? "record" : "text");
            v.put("kind", kinds.isEmpty() ? "empty" : kinds.size() == 1 ? kinds.iterator().next() : "mixed");
            Map<String, Long> counts = new HashMap<>();
            for (Object x : vals) if (x != null && !(x instanceof Map) && !(x instanceof List)) { String s = String.valueOf(x); counts.merge(s.length() > 60 ? s.substring(0, 60) + "\u2026" : s, 1L, Long::sum); }
            v.put("distinct", counts.size());
            v.put("common", counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10).map(c -> Map.of("value", c.getKey(), "runs", c.getValue())).toList());
            out.add(v);
        }
        return Map.of("bot_path", path, "runs", n, "variables", out, "recent", recent);
    }

    // ---------- the analytics data API (Operational Insights step 3): pages of rows for Power BI, Tableau or a data warehouse ----------

    public static final int PAGE_MAX = 1000;

    /** A page cursor: the last row's time and id, so the next page starts after it even when times repeat. */
    public static String cursor(Object ts, Object id) { return Base64.getUrlEncoder().withoutPadding().encodeToString((ts + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public static String[] uncursor(String c) {
        if (c == null || c.isBlank()) return new String[] { Instant.EPOCH.toString(), "" };
        try { String[] p = new String(Base64.getUrlDecoder().decode(c), java.nio.charset.StandardCharsets.UTF_8).split("\\|", 2); Instant.parse(p[0]); return p; }
        catch (RuntimeException e) { throw new OoaException("CR.BAD_INPUT", "Not a page cursor: use the next value of the page before"); }
    }
    public static int limit(Map<String, String> q) { return Math.max(1, Math.min(PAGE_MAX, intOr(q.get("limit"), 500))); }

    /**
     * Runs, a page at a time, oldest first: id, agent, version, status, times, run time, runner, how started, who, the
     * error code and the outputs (business values). Only agents the caller may see. Filters: from, to (default: all).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dataRuns(User by, Map<String, String> q) {
        ctx.require(by, "analytics.view", "read run data");
        Instant from = q.get("from") == null ? Instant.EPOCH : instant(q.get("from"), false), to = q.get("to") == null ? now() : instant(q.get("to"), true);
        String[] after = uncursor(q.get("after"));
        int n = limit(q);
        List<Map<String, Object>> rows = db.query("SELECT r.id, r.bot_path, r.version, r.status, r.created, r.started, r.finished, r.duration_ms, d.name AS runner, r.source, r.requested_by, r.error, r.outputs "
            + "FROM runs r LEFT JOIN devices d ON d.id = r.device_id WHERE r.created >= ? AND r.created <= ? AND (r.created > ? OR (r.created = ? AND r.id > ?)) ORDER BY r.created, r.id LIMIT ?",
            from, to, Instant.parse(after[0]), Instant.parse(after[0]), after[1], n);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!ctx.mayView(by, (String) r.get("bot_path"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id")); m.put("agent", r.get("bot_path")); m.put("version", r.get("version")); m.put("status", r.get("status"));
            m.put("created", r.get("created")); m.put("started", r.get("started")); m.put("finished", r.get("finished")); m.put("duration_ms", r.get("duration_ms"));
            m.put("runner", r.get("runner")); m.put("source", sourceGroup((String) r.get("source"))); m.put("started_by", r.get("requested_by")); m.put("error_code", errorCode(r.get("error")));
            Object out = null;
            if (r.get("outputs") != null) try { out = JsonLite.parse(String.valueOf(r.get("outputs"))); } catch (IllegalArgumentException ignored) { }
            m.put("outputs", out instanceof Map<?, ?> ? out : Map.of());
            items.add(m);
        }
        Map<String, Object> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        return page(items, rows.size() == n && last != null ? cursor(String.valueOf(last.get("created")), last.get("id")) : null);
    }

    /** KPI values agents recorded, a page at a time, oldest first: run, agent, name, number or text, time. */
    public Map<String, Object> dataKpis(User by, Map<String, String> q) {
        ctx.require(by, "analytics.view", "read KPI data");
        Instant from = q.get("from") == null ? Instant.EPOCH : instant(q.get("from"), false), to = q.get("to") == null ? now() : instant(q.get("to"), true);
        String[] after = uncursor(q.get("after"));
        int n = limit(q);
        List<Map<String, Object>> rows = db.query("SELECT id, run_id, bot_path, name, num, text_value, ts FROM kpi_values WHERE ts >= ? AND ts <= ? AND (ts > ? OR (ts = ? AND id > ?)) ORDER BY ts, id LIMIT ?",
            from, to, Instant.parse(after[0]), Instant.parse(after[0]), after[1], n);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!ctx.mayView(by, (String) r.get("bot_path"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run_id", r.get("run_id")); m.put("agent", r.get("bot_path")); m.put("name", r.get("name")); m.put("number", r.get("num")); m.put("text", r.get("text_value")); m.put("time", r.get("ts"));
            items.add(m);
        }
        Map<String, Object> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        return page(items, rows.size() == n && last != null ? cursor(String.valueOf(last.get("ts")), last.get("id")) : null);
    }

    /** Audit entries, a page at a time, oldest first, after a sequence number. Needs the Audit Trail's permission. */
    public Map<String, Object> dataAudit(User by, Map<String, String> q) {
        ctx.require(by, "audit.view", "read audit data");
        long after = q.get("after") == null || q.get("after").isBlank() ? 0 : Long.parseLong(q.get("after").replaceAll("[^0-9]", "0"));
        int n = limit(q);
        List<Map<String, Object>> rows = db.query("SELECT seq, ts, actor, action, object_type, object_id, details, result, ip FROM audit WHERE seq > ? ORDER BY seq LIMIT ?", after, n);
        return page(rows, rows.size() == n ? String.valueOf(rows.get(rows.size() - 1).get("seq")) : null);
    }

    private static Map<String, Object> page(List<Map<String, Object>> items, String next) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("next", next);
        return m;
    }

    /** A report as CSV: most_run, longest, most_failed, by_runner, by_day, by_source or run_history. */
    @SuppressWarnings("unchecked")
    public String csv(User by, String report, Map<String, String> q) {
        if (!REPORTS.contains(report)) throw new OoaException("CR.BAD_INPUT", "Reports: " + String.join(", ", REPORTS));
        StringBuilder sb = new StringBuilder();
        if (report.equals("run_history")) {
            sb.append(ModuleContext.csvRow(List.of("run_id", "agent", "runner", "status", "created", "duration_ms", "source", "started_by", "error_code")));
            for (Run r : runs(by, q)) sb.append(ModuleContext.csvRow(Arrays.asList(r.id(), botName(r.bot()), r.deviceName() == null ? r.device() : r.deviceName(), r.status(), r.created(),
                    r.ms() == null ? "" : String.valueOf(r.ms()), r.source(), r.user(), r.errorCode() == null ? "" : r.errorCode())));
            return sb.toString();
        }
        Map<String, String> all = new HashMap<>(q);
        all.put("top", "100");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) overview(by, all).get(report);
        String key = switch (report) { case "by_runner" -> "runner"; case "by_day" -> "day"; case "by_source" -> "source"; default -> "bot_path"; };
        List<String> head = new ArrayList<>(List.of(key.equals("bot_path") ? "agent" : key, "runs", "success", "failed", "stopped", "success_rate", "total_ms", "avg_ms", "p95_ms", "max_ms"));
        if (report.equals("most_failed")) { head.add("failure_rate"); head.add("top_errors"); }
        sb.append(ModuleContext.csvRow(head));
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(key.equals("bot_path") ? botName(String.valueOf(r.get(key))) : String.valueOf(r.get(key)));
            for (String k : head.subList(1, head.size())) { Object v = r.get(k); cells.add(v == null ? "" : v instanceof List<?> l ? String.join("; ", l.stream().map(String::valueOf).toList()) : String.valueOf(v)); }
            sb.append(ModuleContext.csvRow(cells));
        }
        return sb.toString();
    }

    public static String botName(String path) { return path == null ? "" : path.replaceAll("\\.(flow|process|form)\\.json$", ""); }
}
