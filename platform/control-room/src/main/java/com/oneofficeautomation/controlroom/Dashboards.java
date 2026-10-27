package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Business KPIs and dashboards (K5). An agent marks output variables as KPIs ("kpi": true); each successful run records
 * their values. People build dashboards of widgets over those values or over the runs themselves, keep them to
 * themselves or share them with roles, teams or everyone. A widget:
 * <pre>
 *   {"title": "Invoices paid", "type": "number" | "bars" | "line" | "table",
 *    "source": "kpi", "bot": "Finance/Pay.flow.json", "kpi": "amount", "agg": "sum" | "avg" | "count" | "min" | "max" | "last",
 *    "group": "none" | "day" | "week" | "month" | "value" | "agent", "by": "category", "days": 30}   (by: group by another KPI of the same run)
 *   {"source": "runs", "metric": "runs" | "success_rate" | "failed" | "avg_seconds" | "hours_saved", "bot": optional, "group": ..., "days": 30}
 * </pre>
 * Only agents the viewer may see are counted.
 */
public final class Dashboards {
    static final Set<String> TYPES = Set.of("number", "bars", "line", "table"), AGGS = Set.of("sum", "avg", "count", "min", "max", "last"),
        GROUPS = Set.of("none", "day", "week", "month", "value", "agent"), METRICS = Set.of("runs", "success_rate", "failed", "avg_seconds", "hours_saved");
    private final ControlRoom cr;
    private final Db db;
    /** The KPI outputs of each agent version (path@version), read once. */
    private final Map<String, Set<String>> kpiNames = new ConcurrentHashMap<>();

    Dashboards(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    // ---------- recording ----------

    /** Called when a run succeeds: records the values of the outputs its agent marks as KPIs. */
    @SuppressWarnings("unchecked")
    void recordKpis(Map<String, Object> run, Object outputs) {
        if (!(outputs instanceof Map<?, ?> outs) || outs.isEmpty()) return;
        String path = (String) run.get("bot_path");
        Object version = run.get("version");
        Set<String> names = kpiNames.computeIfAbsent(path + "@" + version, k -> {
            Map<String, Object> v = db.one("SELECT content FROM bot_versions WHERE path = ? AND version = ?", path, version);
            Set<String> s = new LinkedHashSet<>();
            if (v == null) return s;
            Object flow = JsonLite.parse((String) v.get("content"));
            if (flow instanceof Map<?, ?> f && f.get("variables") instanceof List<?> vars)
                for (Object o : vars) if (o instanceof Map<?, ?> var && Boolean.TRUE.equals(var.get("kpi"))) s.add(String.valueOf(var.get("name")));
            return s;
        });
        for (String n : names) {
            if (!outs.containsKey(n)) continue;
            Object val = outs.get(n);
            BigDecimal num = null;
            if (val instanceof Number || (val instanceof String sv && sv.matches("-?\\d+(\\.\\d+)?"))) { try { num = Values.toNumber(val); } catch (RuntimeException ignored) { } }
            String text = val == null ? null : Values.text(val);
            if (text != null && text.length() > 200) text = text.substring(0, 200);
            db.update("INSERT INTO kpi_values (id, run_id, bot_path, name, num, text_value, ts) VALUES (?,?,?,?,?,?,?)", id("kpi"), run.get("id"), path, n, num, text, now());
        }
    }

    // ---------- dashboards ----------

    private boolean mayView(User by, Map<String, Object> d) {
        if (by.username().equals(d.get("owner")) || cr.can(by, "settings.manage")) return true;
        List<String> mine = cr.teams.principalsOf(by.username(), by.role());
        for (String s : csv(d.get("shared_with"))) if (s.equals("*") || mine.contains(s)) return true;
        return false;
    }

    private static List<String> csv(Object v) {
        List<String> out = new ArrayList<>();
        if (v != null) for (String s : String.valueOf(v).split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public List<Map<String, Object>> list(User by) {
        cr.require(by, "analytics.view", "view dashboards");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : db.query("SELECT * FROM dashboards ORDER BY name")) {
            if (!mayView(by, d)) continue;
            Map<String, Object> m = new LinkedHashMap<>(d);
            m.put("widgets", JsonLite.parse((String) d.get("widgets")));
            m.put("shared_with", csv(d.get("shared_with")));
            m.put("can_edit", by.username().equals(d.get("owner")) || cr.can(by, "settings.manage"));
            out.add(m);
        }
        return out;
    }

    /** The KPI names recorded so far per agent (for the widget editor). */
    public List<Map<String, Object>> kpis(User by) {
        cr.require(by, "analytics.view", "view dashboards");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT bot_path, name, COUNT(*) AS n FROM kpi_values GROUP BY bot_path, name ORDER BY bot_path, name"))
            if (cr.folders.mayView(by, (String) r.get("bot_path"))) out.add(r);
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(User by, String id, Map<String, Object> b, String ip) {
        cr.require(by, "analytics.view", "make dashboards");
        String name = String.valueOf(b.getOrDefault("name", "")).trim();
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Dashboard names have 1 to 120 characters");
        if (!(b.get("widgets") instanceof List<?> ws) || ws.isEmpty() || ws.size() > 40) throw new OoaException("CR.BAD_INPUT", "A dashboard has 1 to 40 widgets");
        for (Object o : ws) check(o);
        List<String> shared = new ArrayList<>();
        if (b.get("shared_with") instanceof List<?> l) for (Object o : l) {
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) continue;
            if (!s.equals("*") && !s.matches("(role|team|user):[A-Za-z0-9_.@-]{1,80}")) throw new OoaException("CR.BAD_INPUT", "Share with role:NAME, team:NAME, user:NAME or * (everyone)");
            shared.add(s);
        }
        String json = JsonLite.write(ws);
        if (id == null || id.isBlank()) {
            id = id("dash");
            db.update("INSERT INTO dashboards (id, name, description, owner, shared_with, widgets, updated) VALUES (?,?,?,?,?,?,?)",
                id, name, b.get("description"), by.username(), String.join(",", shared), json, now());
        } else {
            Map<String, Object> d = db.one("SELECT owner FROM dashboards WHERE id = ?", id);
            if (d == null) throw notFound("Dashboard");
            if (!by.username().equals(d.get("owner")) && !cr.can(by, "settings.manage")) throw cr.denied("change this dashboard");
            db.update("UPDATE dashboards SET name = ?, description = ?, shared_with = ?, widgets = ?, updated = ? WHERE id = ?", name, b.get("description"), String.join(",", shared), json, now(), id);
        }
        cr.audit.record(by.username(), "dashboard.save", "dashboard", id, Map.of("name", name, "widgets", ws.size(), "shared_with", shared), "ok", ip);
        return Map.of("id", id);
    }

    static void check(Object o) {
        if (!(o instanceof Map<?, ?> w)) throw new OoaException("CR.BAD_INPUT", "Each widget is a dictionary");
        String t = String.valueOf(w.get("type") == null ? "number" : w.get("type"));
        if (!TYPES.contains(t)) throw new OoaException("CR.BAD_INPUT", "Widget types: " + TYPES);
        String src = String.valueOf(w.get("source") == null ? "kpi" : w.get("source"));
        if (src.equals("kpi")) {
            if (!(w.get("kpi") instanceof String k) || k.isBlank()) throw new OoaException("CR.BAD_INPUT", "A KPI widget names its KPI");
            if (w.get("agg") != null && !AGGS.contains(String.valueOf(w.get("agg")))) throw new OoaException("CR.BAD_INPUT", "Aggregations: " + AGGS);
        } else if (src.equals("runs")) {
            if (w.get("metric") != null && !METRICS.contains(String.valueOf(w.get("metric")))) throw new OoaException("CR.BAD_INPUT", "Run figures: " + METRICS);
        } else throw new OoaException("CR.BAD_INPUT", "A widget's source is kpi or runs");
        if (w.get("group") != null && !GROUPS.contains(String.valueOf(w.get("group")))) throw new OoaException("CR.BAD_INPUT", "Groups: " + GROUPS);
        if (w.get("days") != null && !(w.get("days") instanceof Number n && n.intValue() >= 1 && n.intValue() <= 365)) throw new OoaException("CR.BAD_INPUT", "days is 1 to 365");
    }

    public void delete(User by, String id, String ip) {
        Map<String, Object> d = db.one("SELECT owner FROM dashboards WHERE id = ?", id);
        if (d == null) throw notFound("Dashboard");
        if (!by.username().equals(d.get("owner")) && !cr.can(by, "settings.manage")) throw cr.denied("delete this dashboard");
        db.update("DELETE FROM dashboards WHERE id = ?", id);
        cr.audit.record(by.username(), "dashboard.delete", "dashboard", id, null, "ok", ip);
    }

    // ---------- figures ----------

    /** Each widget's figures: {value} for number, {points: [{label, value}]} for bars, line and table. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> data(User by, String id, String timeZone) {
        cr.require(by, "analytics.view", "view dashboards");
        Map<String, Object> d = db.one("SELECT * FROM dashboards WHERE id = ?", id);
        if (d == null) throw notFound("Dashboard");
        if (!mayView(by, d)) throw cr.denied("view this dashboard");
        ZoneId zone;
        try { zone = timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone); } catch (DateTimeException e) { zone = ZoneOffset.UTC; }
        List<Object> out = new ArrayList<>();
        for (Object o : (List<Object>) JsonLite.parse((String) d.get("widgets"))) {
            Map<String, Object> w = (Map<String, Object>) o;
            Map<String, Object> r = new LinkedHashMap<>(w);
            try { r.putAll(figures(by, w, zone)); } catch (OoaException e) { r.put("error", e.getMessage()); }
            out.add(r);
        }
        return Map.of("id", id, "name", d.get("name"), "widgets", out);
    }

    Map<String, Object> figures(User by, Map<String, Object> w, ZoneId zone) {
        int days = w.get("days") instanceof Number n ? n.intValue() : 30;
        Instant since = Instant.now().minus(Duration.ofDays(days));
        String bot = w.get("bot") instanceof String b && !b.isBlank() ? b : null;
        String group = String.valueOf(w.getOrDefault("group", "none"));
        String type = String.valueOf(w.getOrDefault("type", "number"));
        if (type.equals("number")) group = "none";
        // rows of (group key, number) for the chosen figure
        List<Object[]> rows = new ArrayList<>();
        String agg;
        if ("runs".equals(w.get("source"))) {
            String metric = String.valueOf(w.getOrDefault("metric", "runs"));
            agg = switch (metric) { case "success_rate" -> "rate"; case "avg_seconds" -> "avg"; default -> "sum"; };
            int dflt = cr.platform.intSetting("default_minutes_saved");
            List<Map<String, Object>> runs = bot == null
                ? db.query("SELECT r.bot_path, r.status, r.created, r.duration_ms, b.minutes_saved FROM runs r LEFT JOIN bots b ON b.path = r.bot_path WHERE r.created >= ? AND r.status IN ('success', 'failed', 'stopped')", since)
                : db.query("SELECT r.bot_path, r.status, r.created, r.duration_ms, b.minutes_saved FROM runs r LEFT JOIN bots b ON b.path = r.bot_path WHERE r.created >= ? AND r.bot_path = ? AND r.status IN ('success', 'failed', 'stopped')", since, bot);
            for (Map<String, Object> r : runs) {
                if (!cr.folders.mayView(by, (String) r.get("bot_path"))) continue;
                boolean ok = "success".equals(r.get("status"));
                BigDecimal v = switch (metric) {
                    case "failed" -> "failed".equals(r.get("status")) ? BigDecimal.ONE : BigDecimal.ZERO;
                    case "success_rate" -> ok ? BigDecimal.ONE : BigDecimal.ZERO;
                    case "avg_seconds" -> r.get("duration_ms") instanceof Number n ? BigDecimal.valueOf(n.longValue()).divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP) : null;
                    case "hours_saved" -> ok ? BigDecimal.valueOf(r.get("minutes_saved") instanceof Number n ? n.intValue() : dflt).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    default -> BigDecimal.ONE;
                };
                if (v != null) rows.add(new Object[]{ key(group, (Instant) toInstant(r.get("created")), zone, r.get("bot_path"), null), v });
            }
        } else {
            agg = String.valueOf(w.getOrDefault("agg", "sum"));
            String kpi = String.valueOf(w.get("kpi"));
            List<Map<String, Object>> vals = bot == null
                ? db.query("SELECT run_id, bot_path, num, text_value, ts FROM kpi_values WHERE name = ? AND ts >= ? ORDER BY ts", kpi, since)
                : db.query("SELECT run_id, bot_path, num, text_value, ts FROM kpi_values WHERE name = ? AND ts >= ? AND bot_path = ? ORDER BY ts", kpi, since, bot);
            // group by the value of another KPI of the same run (amount by category), or by this KPI's own value
            Map<Object, String> byRun = new HashMap<>();
            if (group.equals("value") && w.get("by") instanceof String byKpi && !byKpi.isBlank())
                for (Map<String, Object> o : db.query("SELECT run_id, text_value FROM kpi_values WHERE name = ? AND ts >= ?", byKpi, since)) byRun.put(o.get("run_id"), (String) o.get("text_value"));
            for (Map<String, Object> v0 : vals) {
                Map<String, Object> v = v0;
                if (!byRun.isEmpty()) { v = new HashMap<>(v0); v.put("text_value", byRun.getOrDefault(v0.get("run_id"), "(none)")); }
                if (!cr.folders.mayView(by, (String) v.get("bot_path"))) continue;
                BigDecimal num = v.get("num") == null ? null : new BigDecimal(String.valueOf(v.get("num")));
                if (num == null && !agg.equals("count") && !group.equals("value")) continue;
                rows.add(new Object[]{ key(group, toInstant(v.get("ts")), zone, v.get("bot_path"), v.get("text_value")), num == null ? BigDecimal.ONE : num });
            }
            if (group.equals("value")) agg = agg.equals("count") ? "count" : agg;
        }
        Map<String, List<BigDecimal>> grouped = new TreeMap<>();
        for (Object[] r : rows) grouped.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add((BigDecimal) r[1]);
        String how = agg;
        if (type.equals("number")) {
            List<BigDecimal> all = grouped.getOrDefault("", List.of());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("value", aggregate(how, all));   // null when there is nothing to average
            one.put("count", all.size());
            return one;
        }
        List<Map<String, Object>> points = new ArrayList<>();
        grouped.forEach((k, v) -> { Map<String, Object> p = new LinkedHashMap<>(); p.put("label", k); p.put("value", aggregate(how, v)); points.add(p); });
        if (group.equals("value") || group.equals("agent"))
            points.sort((x, y) -> x.get("value") == null ? 1 : y.get("value") == null ? -1 : ((BigDecimal) y.get("value")).compareTo((BigDecimal) x.get("value")));
        return Map.of("points", points.size() > 100 ? points.subList(0, 100) : points);
    }

    private static Instant toInstant(Object ts) { return ts instanceof Instant i ? i : Instant.parse(String.valueOf(ts)); }

    static String key(String group, Instant ts, ZoneId zone, Object bot, Object text) {
        LocalDate d = ts.atZone(zone).toLocalDate();
        return switch (group) {
            case "day" -> d.toString();
            case "week" -> d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "month" -> d.withDayOfMonth(1).toString().substring(0, 7);
            case "value" -> text == null ? "(empty)" : String.valueOf(text);
            case "agent" -> String.valueOf(bot).replaceAll("\\.flow\\.json$", "");
            default -> "";
        };
    }

    static BigDecimal aggregate(String agg, List<BigDecimal> v) {
        if (v.isEmpty()) return agg.equals("count") || agg.equals("sum") ? BigDecimal.ZERO : null;
        return switch (agg) {
            case "count" -> BigDecimal.valueOf(v.size());
            case "avg" -> v.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(v.size()), 2, RoundingMode.HALF_UP);
            case "rate" -> v.stream().reduce(BigDecimal.ZERO, BigDecimal::add).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(v.size()), 1, RoundingMode.HALF_UP);
            case "min" -> v.stream().min(BigDecimal::compareTo).orElse(null);
            case "max" -> v.stream().max(BigDecimal::compareTo).orElse(null);
            case "last" -> v.get(v.size() - 1);
            default -> v.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(Math.min(2, Math.max(0, v.stream().mapToInt(BigDecimal::scale).max().orElse(0))), RoundingMode.HALF_UP);
        };
    }
}
