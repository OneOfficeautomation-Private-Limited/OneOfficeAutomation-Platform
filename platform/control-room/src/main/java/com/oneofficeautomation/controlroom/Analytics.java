package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.*;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Operations analytics over the runs of a period (default: the last 30 days), optionally for one agent, runner,
 * person or source: totals and success rate, run time, how long runs take (distribution, average, 95th percentile),
 * runs per day, the agents run most, the longest and those that fail most, use per runner and per source, and each
 * agent's own statistics. Only agents the caller may see (folder permissions) are counted. Every list can be
 * downloaded as CSV.
 */
public final class Analytics {
    static final int MAX_ROWS = 200_000;
    static final long[] BUCKETS = {60_000, 300_000, 900_000, 3_600_000};
    static final List<String> BUCKET_LABELS = List.of("under 1 min", "1-5 min", "5-15 min", "15-60 min", "over 1 hour");
    static final List<String> REPORTS = List.of("most_run", "longest", "most_failed", "by_runner", "by_day", "by_source", "run_history");
    private final ControlRoom cr;
    private final Db db;

    Analytics(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    record Run(String id, String bot, String device, String deviceName, String status, String created, Long ms, String source, String user, String errorCode) {}

    /** The runs of the period that match the filters and that the caller may see. */
    /** The period asked for: from and to (a date includes its whole day), or the last days (30) up to now. */
    static Instant[] period(Map<String, String> q) {
        Instant to = q.get("to") == null || q.get("to").isBlank() ? now() : instant(q.get("to"), true);
        Instant from = q.get("from") == null || q.get("from").isBlank() ? to.minus(Duration.ofDays(intOr(q.get("days"), 30))) : instant(q.get("from"), false);
        if (from.isAfter(to)) throw new OoaException("CR.BAD_INPUT", "The start of the period is after its end");
        return new Instant[] {from, to};
    }

    List<Run> runs(User by, Map<String, String> q) {
        cr.require(by, "analytics.view", "view operations analytics");
        Instant[] pd = period(q);
        Instant from = pd[0], to = pd[1];
        List<String> where = new ArrayList<>(List.of("r.created >= ?", "r.created <= ?"));
        List<Object> p = new ArrayList<>(List.of(from, to));
        for (String[] f : new String[][] {{"bot", "r.bot_path"}, {"device", "r.device_id"}, {"user", "r.requested_by"}})
            if (q.get(f[0]) != null && !q.get(f[0]).isBlank()) { where.add(f[1] + " = ?"); p.add(q.get(f[0])); }
        if (q.get("source") != null && !q.get("source").isBlank()) { where.add("r.source LIKE ?"); p.add(q.get("source") + "%"); }
        p.add(MAX_ROWS);
        Map<String, Map<String, String>> perms = cr.folders.entries();
        List<String> mine = cr.teams.principalsOf(by.username(), by.role());
        Map<String, Boolean> visible = new HashMap<>();
        List<Run> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT r.id, r.bot_path, r.device_id, d.name AS device_name, r.status, r.created, r.duration_ms, r.source, r.requested_by, r.error " +
                "FROM runs r LEFT JOIN devices d ON d.id = r.device_id WHERE " +
                String.join(" AND ", where) + " ORDER BY r.created LIMIT ?", p.toArray())) {
            String bot = (String) r.get("bot_path");
            if (!visible.computeIfAbsent(bot, b -> !cr.folders.accessToFolder(mine, by.role(), Folders.folderOf(b), perms).equals("none"))) continue;
            String code = null;
            if (r.get("error") != null) {
                try { Object e = JsonLite.parse((String) r.get("error")); if (e instanceof Map<?, ?> em && em.get("code") != null) code = String.valueOf(em.get("code")); }
                catch (IllegalArgumentException ignored) { code = "ERROR"; }
            }
            out.add(new Run((String) r.get("id"), bot, (String) r.get("device_id"), (String) r.get("device_name"), (String) r.get("status"), String.valueOf(r.get("created")),
                    r.get("duration_ms") instanceof Number n ? n.longValue() : null, (String) r.get("source"), (String) r.get("requested_by"), code));
        }
        return out;
    }

    static String sourceGroup(String s) { if (s == null) return "manual"; int i = s.indexOf(':'); return i < 0 ? s : s.substring(0, i); }

    static long percentile(List<Long> sorted, double pct) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** Totals, success rate and timing for a group of runs. */
    static Map<String, Object> stats(List<Run> runs) {
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

    static Comparator<Map<String, Object>> by(String k) { return Comparator.comparingDouble((Map<String, Object> m) -> m.get(k) instanceof Number n ? n.doubleValue() : -1).reversed(); }

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

    static ZoneId zone(String tz) {
        try { return tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz); } catch (DateTimeException e) { return ZoneOffset.UTC; }
    }

    /** When runs start (Blue Prism's activity heatmap): runs and failed runs per weekday (Monday first) and hour, in the viewer's time zone. */
    static Map<String, Object> byHour(List<Run> runs, ZoneId zone) {
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

    static List<Map<String, Object>> byDay(List<Run> runs) {
        TreeMap<String, List<Run>> g = new TreeMap<>();
        for (Run r : runs) g.computeIfAbsent(r.created().substring(0, Math.min(10, r.created().length())), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : g.entrySet()) { Map<String, Object> m = new LinkedHashMap<>(); m.put("day", e.getKey()); m.putAll(stats(e.getValue())); out.add(m); }
        return out;
    }

    static List<Map<String, Object>> mostFailed(List<Run> runs, List<Map<String, Object>> perBot) {
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
    static List<Map<String, Object>> failureReasons(List<Run> runs) {
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
    public Map<String, Object> capacity(User by, Map<String, String> q) {
        cr.require(by, "analytics.view", "view runner capacity");
        Instant[] pd = period(q);
        Instant from = pd[0], to = pd[1].isAfter(now()) ? now() : pd[1];
        ZoneId zone = zone(q.get("tz"));
        long[][] busy = new long[7][24], slots = new long[7][24];
        Map<String, Long> perDay = new TreeMap<>();
        Map<String, long[]> per = new LinkedHashMap<>();   // device: busy ms, runs, wait ms total, waited runs
        List<Long> waits = new ArrayList<>();
        for (Map<String, Object> d : db.query("SELECT id FROM devices WHERE enabled = ?", true)) per.put((String) d.get("id"), new long[4]);
        int enabled = per.size();
        for (Map<String, Object> r : db.query("SELECT device_id, created, started, finished FROM runs WHERE started IS NOT NULL AND started < ? AND (finished IS NULL OR finished > ?) LIMIT " + MAX_ROWS, to, from)) {
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
        long period = Math.max(1, Duration.between(from, to).toMillis()), total = 0;
        List<Map<String, Object>> runners = new ArrayList<>();
        for (var e : per.entrySet()) {
            long[] c = e.getValue();
            total += c[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device_id", e.getKey()); m.put("busy_ms", c[0]); m.put("runs", c[1]);
            m.put("use", Math.round(1000.0 * c[0] / period) / 10.0);
            m.put("avg_wait_ms", c[3] == 0 ? null : c[2] / c[3]);
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
        m.put("waiting_now", cr.count("SELECT COUNT(*) AS n FROM runs WHERE status = 'queued'"));
        m.put("heat", Map.of("time_zone", zone.getId(), "use", heat));
        List<Object> days = new ArrayList<>();
        for (var e : perDay.entrySet()) days.add(Map.of("day", e.getKey(), "busy_ms", e.getValue()));
        m.put("by_day", days);
        return m;
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
        cr.require(by, "queues.view", "view work queues");
        Instant[] pd = period(q);
        Instant from = pd[0], to = pd[1], now = now();
        ZoneId zone = zone(q.get("tz"));
        double hours = Math.max(1.0 / 60, Duration.between(from, to).toMinutes() / 60.0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> qu : db.query("SELECT id, name, sla_minutes, processing, bot_path, pool_id FROM queues ORDER BY name")) {
            String id = (String) qu.get("id");
            if (!cr.platform.queueAllows(by, id, "view")) continue;
            int sla = qu.get("sla_minutes") instanceof Number n ? n.intValue() : 0;
            Map<String, Object> m = new LinkedHashMap<>(qu);
            Map<String, Object> counts = cr.platform.counts(id);
            m.put("counts", counts);
            long waiting = ((Number) counts.get("new")).longValue() + ((Number) counts.get("retry")).longValue();
            m.put("waiting", waiting);
            Map<String, Object> oldest = db.one("SELECT MIN(created) AS t FROM queue_items WHERE queue_id = ? AND status IN ('new', 'retry')", id);
            m.put("oldest_waiting", oldest == null || oldest.get("t") == null ? null : String.valueOf(oldest.get("t")));
            if (sla > 0) {
                m.put("overdue", cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status IN ('new', 'in_progress', 'retry') AND created < ?", id, now.minus(Duration.ofMinutes(sla))));
                m.put("at_risk", cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status IN ('new', 'in_progress', 'retry') AND created >= ? AND created < ?",
                        id, now.minus(Duration.ofMinutes(sla)), now.minus(Duration.ofSeconds(sla * 45L))));
            }
            m.put("added", cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND created >= ? AND created < ?", id, from, to));
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

    /** One agent: its totals and timing, runs per day, the errors it fails with, its runners and its last runs. */
    public Map<String, Object> agent(User by, String path, Map<String, String> q) {
        cr.folders.require(by, path, "view", "view agent statistics");
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
     * An agent's variable profile: for each output it returned in the period, how often it had a value, its most common
     * values and, for numbers, the smallest, largest and average.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> variables(User by, String path, Map<String, String> q) {
        cr.folders.require(by, path, "view", "view agent statistics");
        Map<String, String> f = new HashMap<>(q);
        f.put("bot", path);
        Set<String> ids = new HashSet<>();
        for (Run r : runs(by, f)) ids.add(r.id());
        Map<String, List<Object>> values = new TreeMap<>();
        int n = 0;
        for (Map<String, Object> r : db.query("SELECT id, outputs FROM runs WHERE bot_path = ? AND outputs IS NOT NULL ORDER BY created DESC LIMIT 5000", path)) {
            if (!ids.contains((String) r.get("id"))) continue;
            Object o;
            try { o = JsonLite.parse((String) r.get("outputs")); } catch (IllegalArgumentException e) { continue; }
            if (!(o instanceof Map<?, ?> m)) continue;
            n++;
            for (var e : ((Map<String, Object>) m).entrySet()) values.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
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
            }
            Map<String, Long> counts = new HashMap<>();
            for (Object x : vals) if (x != null && !(x instanceof Map) && !(x instanceof List)) { String s = String.valueOf(x); counts.merge(s.length() > 60 ? s.substring(0, 60) + "\u2026" : s, 1L, Long::sum); }
            v.put("common", counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).map(c -> Map.of("value", c.getKey(), "runs", c.getValue())).toList());
            out.add(v);
        }
        return Map.of("bot_path", path, "runs", n, "variables", out);
    }

    /** A report as CSV: most_run, longest, most_failed, by_runner, by_day, by_source or run_history. */
    @SuppressWarnings("unchecked")
    public String csv(User by, String report, Map<String, String> q) {
        if (!REPORTS.contains(report)) throw new OoaException("CR.BAD_INPUT", "Reports: " + String.join(", ", REPORTS));
        StringBuilder sb = new StringBuilder();
        if (report.equals("run_history")) {
            sb.append(Platform.Csv.row(List.of("run_id", "agent", "runner", "status", "created", "duration_ms", "source", "started_by", "error_code")));
            for (Run r : runs(by, q)) sb.append(Platform.Csv.row(Arrays.asList(r.id(), botName(r.bot()), r.deviceName() == null ? r.device() : r.deviceName(), r.status(), r.created(),
                    r.ms() == null ? "" : String.valueOf(r.ms()), r.source(), r.user(), r.errorCode() == null ? "" : r.errorCode())));
            return sb.toString();
        }
        Map<String, String> all = new HashMap<>(q);
        all.put("top", "100");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) overview(by, all).get(report);
        String key = switch (report) { case "by_runner" -> "runner"; case "by_day" -> "day"; case "by_source" -> "source"; default -> "bot_path"; };
        List<String> head = new ArrayList<>(List.of(key.equals("bot_path") ? "agent" : key, "runs", "success", "failed", "stopped", "success_rate", "total_ms", "avg_ms", "p95_ms", "max_ms"));
        if (report.equals("most_failed")) { head.add("failure_rate"); head.add("top_errors"); }
        sb.append(Platform.Csv.row(head));
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(key.equals("bot_path") ? botName(String.valueOf(r.get(key))) : String.valueOf(r.get(key)));
            for (String k : head.subList(1, head.size())) { Object v = r.get(k); cells.add(v == null ? "" : v instanceof List<?> l ? String.join("; ", l.stream().map(String::valueOf).toList()) : String.valueOf(v)); }
            sb.append(Platform.Csv.row(cells));
        }
        return sb.toString();
    }

    static String botName(String path) { return path == null ? "" : path.replaceAll("\\.(flow|process|form)\\.json$", ""); }
}
