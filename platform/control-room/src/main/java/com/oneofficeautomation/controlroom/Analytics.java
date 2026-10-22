package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.time.Instant;
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
    List<Run> runs(User by, Map<String, String> q) {
        cr.require(by, "analytics.view", "view operations analytics");
        Instant to = q.get("to") == null || q.get("to").isBlank() ? now() : instant(q.get("to"), true);
        Instant from = q.get("from") == null || q.get("from").isBlank() ? to.minus(Duration.ofDays(intOr(q.get("days"), 30))) : instant(q.get("from"), false);
        if (from.isAfter(to)) throw new OoaException("CR.BAD_INPUT", "The start of the period is after its end");
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
        m.put("by_runner", grouped(runs, r -> r.deviceName() == null ? r.device() : r.deviceName(), "runner").stream().sorted(by("total_ms")).toList());
        m.put("by_source", grouped(runs, r -> sourceGroup(r.source()), "source").stream().sorted(by("runs")).toList());
        m.put("period", Map.of("runs_counted", runs.size(), "capped", runs.size() >= MAX_ROWS));
        return m;
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
