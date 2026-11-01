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
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Business KPIs and dashboards (K5). An agent marks output variables as KPIs ("kpi": true); each successful run records
 * their values. People build dashboards of widgets over those values or over the runs themselves, keep them to
 * themselves or share them with roles, teams or everyone. A widget:
 * <pre>
 *   {"title": "Invoices paid", "type": "number" | "bars" | "line" | "table" | "pie",
 *    "source": "kpi", "bot": "Finance/Pay.flow.json", "kpi": "amount", "agg": "sum" | "avg" | "count" | "min" | "max" | "last",
 *    "group": "none" | "day" | "week" | "month" | "value" | "agent", "by": "category", "days": 30}   (by: group by another KPI of the same run)
 *   {"source": "runs", "metric": "runs" | "success_rate" | "failed" | "avg_seconds" | "hours_saved", "bot": optional, "group": ..., "days": 30}
 * </pre>
 * Only agents the viewer may see are counted.
 */
public final class Dashboards {
    static final Set<String> TYPES = Set.of("number", "bars", "line", "table", "pie"), AGGS = Set.of("sum", "avg", "count", "min", "max", "last"),
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
        cr.require(by, "insights.design", "make and share dashboards");
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
        } else if (src.equals("output")) {
            // a value one agent returns, KPI or not (Business: an agent's own dashboard)
            if (!(w.get("bot") instanceof String b) || b.isBlank()) throw new OoaException("CR.BAD_INPUT", "A widget over an output names its agent");
            if (!(w.get("kpi") instanceof String k) || k.isBlank()) throw new OoaException("CR.BAD_INPUT", "A widget over an output names the output");
            if (w.get("agg") != null && !AGGS.contains(String.valueOf(w.get("agg")))) throw new OoaException("CR.BAD_INPUT", "Aggregations: " + AGGS);
        } else throw new OoaException("CR.BAD_INPUT", "A widget's source is kpi, output or runs");
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

    // ---------- e-mailed on a schedule (Operational Insights step 3; UiPath's scheduled dashboard e-mails) ----------

    static final Map<String, DayOfWeek> WEEKDAYS = Map.of("MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY, "WED", DayOfWeek.WEDNESDAY, "THU", DayOfWeek.THURSDAY,
        "FRI", DayOfWeek.FRIDAY, "SAT", DayOfWeek.SATURDAY, "SUN", DayOfWeek.SUNDAY);

    /** The next time after a moment: each day at the time, each week on the weekday, or each month on its first day. */
    static Instant nextSend(Map<String, Object> s, Instant after) {
        ZoneId zone = ZoneId.of(String.valueOf(s.get("time_zone")));
        ZonedDateTime base = after.atZone(zone);
        ZonedDateTime c = base.toLocalDate().atTime(LocalTime.parse(String.valueOf(s.get("at_time")))).atZone(zone);
        switch (String.valueOf(s.get("every"))) {
            case "week" -> { c = c.with(TemporalAdjusters.nextOrSame(WEEKDAYS.get(String.valueOf(s.get("weekday"))))); if (!c.isAfter(base)) c = c.plusWeeks(1); }
            case "month" -> { c = c.withDayOfMonth(1); if (!c.isAfter(base)) c = c.plusMonths(1).withDayOfMonth(1); }
            default -> { if (!c.isAfter(base)) c = c.plusDays(1); }
        }
        return c.toInstant();
    }

    /** A dashboard's e-mail schedules, for people who may see the dashboard. */
    public List<Map<String, Object>> mailList(User by, String dashboardId) {
        cr.require(by, "analytics.view", "view dashboards");
        Map<String, Object> d = db.one("SELECT * FROM dashboards WHERE id = ?", dashboardId);
        if (d == null) throw notFound("Dashboard");
        if (!mayView(by, d)) throw cr.denied("view this dashboard");
        return db.query("SELECT id, recipients, every, at_time, weekday, time_zone, created_by, created, next_send, last_sent, last_error FROM dashboard_mail WHERE dashboard_id = ? ORDER BY created", dashboardId);
    }

    /**
     * E-mails a dashboard on a schedule: recipients are user:NAME, role:NAME or e-mail addresses (at most 50); every is
     * day, week (with weekday MON..SUN) or month (its first day); at_time HH:MM in time_zone. The figures are those the
     * person setting it up sees. Needs insights.design.
     */
    public Map<String, Object> mailSave(User by, String dashboardId, Map<String, Object> b, String ip) {
        cr.require(by, "insights.design", "e-mail dashboards");
        Map<String, Object> d = db.one("SELECT * FROM dashboards WHERE id = ?", dashboardId);
        if (d == null) throw notFound("Dashboard");
        if (!mayView(by, d)) throw cr.denied("view this dashboard");
        List<String> to = new ArrayList<>();
        for (String r : String.valueOf(b.getOrDefault("recipients", "")).split("[,;\\s]+")) {
            if (r.isBlank()) continue;
            if (r.startsWith("user:") || r.startsWith("role:")) { if (!r.matches("(user|role):[A-Za-z0-9_.@-]{1,80}") || r.equals("role:*")) throw new OoaException("CR.BAD_INPUT", "Not a person or role: " + r); }
            else Mailer.address(r);
            if (!to.contains(r)) to.add(r);
        }
        if (to.isEmpty() || to.size() > 50) throw new OoaException("CR.BAD_INPUT", "Send it to 1 to 50 people, roles or addresses");
        String every = String.valueOf(b.getOrDefault("every", "week"));
        if (!List.of("day", "week", "month").contains(every)) throw new OoaException("CR.BAD_INPUT", "Send it each day, week or month");
        String at = String.valueOf(b.getOrDefault("at_time", "08:00"));
        if (!at.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) throw new OoaException("CR.BAD_INPUT", "The time looks like 08:30");
        String weekday = every.equals("week") ? String.valueOf(b.getOrDefault("weekday", "MON")) : null;
        if (weekday != null && !WEEKDAYS.containsKey(weekday)) throw new OoaException("CR.BAD_INPUT", "The weekday is MON to SUN");
        String tz = b.get("time_zone") instanceof String z && !z.isBlank() ? z.trim() : cr.defaultTimeZone();
        try { ZoneId.of(tz); } catch (DateTimeException e) { throw new OoaException("CR.BAD_INPUT", "Unknown time zone " + tz); }
        Map<String, Object> s = new HashMap<>(Map.of("every", every, "at_time", at, "time_zone", tz));
        if (weekday != null) s.put("weekday", weekday);
        String id = id("dmail");
        db.update("INSERT INTO dashboard_mail (id, dashboard_id, recipients, every, at_time, weekday, time_zone, created_by, created, next_send) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, dashboardId, String.join(",", to), every, at, weekday, tz, by.username(), now(), nextSend(s, now()));
        cr.audit.record(by.username(), "dashboard.mail_schedule", "dashboard", dashboardId, Map.of("to", to, "every", every, "at", at), "ok", ip);
        return Map.of("id", id);
    }

    /** Stops an e-mail schedule: the person who set it up, or an administrator. */
    public void mailDelete(User by, String id, String ip) {
        Map<String, Object> s = db.one("SELECT dashboard_id, created_by FROM dashboard_mail WHERE id = ?", id);
        if (s == null) throw notFound("E-mail schedule");
        if (!by.username().equals(s.get("created_by")) && !cr.can(by, "settings.manage")) throw cr.denied("stop this e-mail");
        db.update("DELETE FROM dashboard_mail WHERE id = ?", id);
        cr.audit.record(by.username(), "dashboard.mail_stop", "dashboard", (String) s.get("dashboard_id"), Map.of("schedule", id), "ok", ip);
    }

    /** Sends one schedule's e-mail now (to check it); the schedule keeps its next time. */
    public Map<String, Object> mailNow(User by, String id, String ip) {
        Map<String, Object> s = db.one("SELECT * FROM dashboard_mail WHERE id = ?", id);
        if (s == null) throw notFound("E-mail schedule");
        if (!by.username().equals(s.get("created_by")) && !cr.can(by, "settings.manage")) throw cr.denied("send this e-mail");
        int n = send(s);
        db.update("UPDATE dashboard_mail SET last_sent = ?, last_error = NULL WHERE id = ?", now(), id);
        cr.audit.record(by.username(), "dashboard.mailed", "dashboard", (String) s.get("dashboard_id"), Map.of("schedule", id, "to", n), "ok", ip);
        return Map.of("sent_to", n);
    }

    /** Once a minute (the leader): sends the schedules that are due, and moves each to its next time. */
    void mailIfDue() {
        if (!cr.mailer.configured()) return;
        for (Map<String, Object> s : db.query("SELECT * FROM dashboard_mail WHERE next_send <= ? ORDER BY next_send", now())) {
            String error = null;
            int n = 0;
            try { n = send(s); } catch (RuntimeException e) { error = String.valueOf(e.getMessage()); }
            db.update("UPDATE dashboard_mail SET next_send = ?, last_sent = ?, last_error = ? WHERE id = ?", nextSend(s, now()), error == null ? now() : s.get("last_sent"),
                error == null ? null : error.substring(0, Math.min(1000, error.length())), s.get("id"));
            cr.audit.record("system", "dashboard.mailed", "dashboard", (String) s.get("dashboard_id"), error == null ? Map.of("schedule", s.get("id"), "to", n) : error, error == null ? "ok" : "failed", null);
        }
    }

    /** The e-mail: each widget's figure (a number, or its first ten parts) and a link; worked out as its maker sees it. */
    @SuppressWarnings("unchecked")
    private int send(Map<String, Object> s) {
        Map<String, Object> u = db.one("SELECT * FROM users WHERE username = ? AND enabled = TRUE", s.get("created_by"));
        if (u == null) throw new OoaException("CR.MAIL_OWNER_GONE", "The person who set up this e-mail is no longer active", "Set it up again as someone active.");
        String dashboardId = (String) s.get("dashboard_id");
        Map<String, Object> d = data(ControlRoom.user(u), dashboardId, String.valueOf(s.get("time_zone")));
        List<String> people = new ArrayList<>(), to = new ArrayList<>();
        for (String r : String.valueOf(s.get("recipients")).split(",")) if (r.contains("@") && !r.startsWith("user:") && !r.startsWith("role:")) to.add(r); else people.add(r);
        for (String a : cr.addressesOf(people)) if (!to.contains(a)) to.add(a);
        if (to.isEmpty()) throw new OoaException("MAIL.NO_RECIPIENT", "None of the people or roles has an e-mail address");
        StringBuilder body = new StringBuilder(String.valueOf(d.get("name"))).append(", ")
            .append(LocalDate.now(ZoneId.of(String.valueOf(s.get("time_zone")))).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH))).append("\n\n");
        for (Object o : (List<Object>) d.get("widgets")) {
            Map<String, Object> w = (Map<String, Object>) o;
            String title = String.valueOf(w.getOrDefault("title", w.getOrDefault("kpi", w.getOrDefault("metric", "Figure"))));
            if (w.get("error") != null) body.append(title).append(": ").append(w.get("error")).append("\n");
            else if (w.containsKey("value")) body.append(title).append(": ").append(figure(w.get("value"))).append("\n");
            else {
                body.append(title).append(":\n");
                List<Map<String, Object>> pts = (List<Map<String, Object>>) w.getOrDefault("points", List.of());
                for (Map<String, Object> p : pts.subList(0, Math.min(10, pts.size()))) body.append("  ").append(p.get("label")).append(": ").append(figure(p.get("value"))).append("\n");
                if (pts.size() > 10) body.append("  and ").append(pts.size() - 10).append(" more\n");
            }
        }
        String link = String.valueOf(cr.platform.setting("public_url")).trim().replaceAll("/+$", "");
        body.append("\n").append(link.isEmpty() ? "Open it in One CommandCenter: Operational Insights > Business > Dashboards."
            : "Open it: " + link + "/next/#/business?tab=dashboards&dashboard=" + dashboardId).append("\n");
        cr.mailer.send(to, "Dashboard: " + d.get("name"), body.toString());
        return to.size();
    }

    private static String figure(Object v) { return v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : v == null ? "-" : String.valueOf(v); }

    /** Each widget's figures: {value} for number, {points: [{label, value}]} for bars, line, table and pie. */
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
        } else if ("output".equals(w.get("source"))) {
            // the agent's run outputs (its successful and failed runs), grouped like a KPI
            agg = String.valueOf(w.getOrDefault("agg", "sum"));
            String out = String.valueOf(w.get("kpi"));
            if (bot != null && cr.folders.mayView(by, bot))
                for (Map<String, Object> r : db.query("SELECT created, outputs FROM runs WHERE bot_path = ? AND created >= ? AND outputs IS NOT NULL ORDER BY created LIMIT 20000", bot, since)) {
                    Object o;
                    try { o = JsonLite.parse(String.valueOf(r.get("outputs"))); } catch (IllegalArgumentException e) { continue; }
                    if (!(o instanceof Map<?, ?> m) || !m.containsKey(out) || m.get(out) == null) continue;
                    Object val = m.get(out);
                    BigDecimal num = val instanceof Number n ? new BigDecimal(String.valueOf(n)) : null;
                    if (num == null && !agg.equals("count") && !group.equals("value")) continue;
                    rows.add(new Object[]{ key(group, toInstant(r.get("created")), zone, bot, Values.text(val)), num == null ? BigDecimal.ONE : num });
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
