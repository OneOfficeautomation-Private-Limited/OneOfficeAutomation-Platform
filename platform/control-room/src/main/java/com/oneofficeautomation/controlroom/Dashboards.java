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

    /** Only a dashboard's owner, or an administrator (settings.manage), sets up, sends or changes its e-mails. */
    private boolean mayManageMail(User by, Map<String, Object> d) {
        return cr.can(by, "settings.manage") || (by.username().equals(d.get("owner")) && cr.can(by, "insights.design"));
    }

    /** Whether a person may open the dashboard: may view dashboards at all, and this one is theirs or shared with them. */
    private boolean mayRead(User u, Map<String, Object> d) { return cr.can(u, "analytics.view") && mayView(u, d); }

    /** Settings > E-mail: whether a dashboard may go to addresses that are not people here (off by default). */
    private boolean outsideAllowed() { return cr.platform.intSetting("dashboard_mail_outside") == 1; }

    /** An active person, as a user, or null. */
    private User activeUser(String username) {
        Map<String, Object> u = db.one("SELECT * FROM users WHERE username = ? AND enabled = TRUE", username);
        return u == null ? null : ControlRoom.user(u);
    }

    /** The active person with this e-mail address, or null. */
    private String userWithAddress(String address) {
        Map<String, Object> u = db.one("SELECT username FROM users WHERE LOWER(email) = ? AND enabled = TRUE", address.toLowerCase(Locale.ROOT));
        return u == null ? null : (String) u.get("username");
    }

    /**
     * Checks one recipient against the dashboard: a person must be able to see it, a role or team must be one it is
     * shared with (or it is shared with everyone), and an address must be a person's who may see it, unless addresses
     * outside One CommandCenter are allowed in Settings > E-mail. Returns why not, or null when it may go.
     */
    private String whyNot(String r, Map<String, Object> d) {
        List<String> shared = csv(d.get("shared_with"));
        if (r.startsWith("user:")) {
            User u = activeUser(r.substring(5));
            if (u == null) return r.substring(5) + " is not an active person";
            return mayRead(u, d) ? null : r.substring(5) + " may not see this dashboard: share it with them first";
        }
        if (r.startsWith("role:") || r.startsWith("team:")) {
            if (r.startsWith("team:") ? db.one("SELECT id FROM teams WHERE name = ?", r.substring(5)) == null : !cr.roleExists(r.substring(5))) return "There is no " + r;
            return shared.contains("*") || shared.contains(r) ? null : "The dashboard is not shared with " + r + ": share it with them first";
        }
        String person = userWithAddress(r);
        if (person != null) return whyNot("user:" + person, d);
        return outsideAllowed() ? null : r + " is not a person here, and e-mailing dashboards to other addresses is off (Administration > Settings > E-mail)";
    }

    /**
     * E-mails a dashboard on a schedule: recipients are user:NAME, role:NAME, team:NAME or e-mail addresses (at most 50);
     * every is day, week (with weekday MON..SUN) or month (its first day); at_time HH:MM in time_zone. Only the
     * dashboard's owner or an administrator sets one up; each recipient must be someone the dashboard is shared with
     * (addresses of other people only when Settings allows them). Each person gets the figures they may see.
     */
    public Map<String, Object> mailSave(User by, String dashboardId, Map<String, Object> b, String ip) {
        cr.require(by, "analytics.view", "view dashboards");
        Map<String, Object> d = db.one("SELECT * FROM dashboards WHERE id = ?", dashboardId);
        if (d == null) throw notFound("Dashboard");
        if (!mayView(by, d)) throw cr.denied("view this dashboard");
        if (!mayManageMail(by, d)) throw cr.denied("e-mail this dashboard (only its owner or an administrator may)");
        List<String> to = new ArrayList<>();
        for (String r : String.valueOf(b.getOrDefault("recipients", "")).split("[,;\\s]+")) {
            if (r.isBlank()) continue;
            if (r.startsWith("user:") || r.startsWith("role:") || r.startsWith("team:")) { if (!r.matches("(user|role|team):[A-Za-z0-9_.@-]{1,80}") || r.equals("role:*")) throw new OoaException("CR.BAD_INPUT", "Not a person, role or team: " + r); }
            else Mailer.address(r);
            if (!to.contains(r)) to.add(r);
        }
        if (to.isEmpty() || to.size() > 50) throw new OoaException("CR.BAD_INPUT", "Send it to 1 to 50 people, roles, teams or addresses");
        for (String r : to) { String why = whyNot(r, d); if (why != null) throw new OoaException("CR.BAD_INPUT", why); }
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

    /** Stops an e-mail schedule: the dashboard's owner, the person who set it up, or an administrator. */
    public void mailDelete(User by, String id, String ip) {
        Map<String, Object> s = db.one("SELECT dashboard_id, created_by FROM dashboard_mail WHERE id = ?", id);
        if (s == null) throw notFound("E-mail schedule");
        Map<String, Object> d = db.one("SELECT * FROM dashboards WHERE id = ?", s.get("dashboard_id"));
        if (!by.username().equals(s.get("created_by")) && !cr.can(by, "settings.manage") && !(d != null && by.username().equals(d.get("owner"))))
            throw cr.denied("stop this e-mail");
        db.update("DELETE FROM dashboard_mail WHERE id = ?", id);
        cr.audit.record(by.username(), "dashboard.mail_stop", "dashboard", (String) s.get("dashboard_id"), Map.of("schedule", id), "ok", ip);
    }

    /** Sends one schedule's e-mail now (to check it): the dashboard's owner or an administrator; the schedule keeps its next time. */
    public Map<String, Object> mailNow(User by, String id, String ip) {
        Map<String, Object> s = db.one("SELECT * FROM dashboard_mail WHERE id = ?", id);
        if (s == null) throw notFound("E-mail schedule");
        Map<String, Object> d = db.one("SELECT * FROM dashboards WHERE id = ?", s.get("dashboard_id"));
        if (d == null) throw notFound("Dashboard");
        if (!mayManageMail(by, d)) throw cr.denied("send this e-mail (only the dashboard's owner or an administrator may)");
        Sent r = send(s);
        db.update("UPDATE dashboard_mail SET last_sent = ?, last_error = NULL WHERE id = ?", now(), id);
        cr.audit.record(by.username(), "dashboard.mailed", "dashboard", (String) s.get("dashboard_id"), Map.of("schedule", id, "to", r.to(), "left_out", r.leftOut()), "ok", ip);
        return Map.of("sent_to", r.to(), "left_out", r.leftOut());
    }

    /** Once a minute (the leader): sends the schedules that are due, and moves each to its next time. */
    void mailIfDue() {
        if (!cr.mailer.configured()) return;
        for (Map<String, Object> s : db.query("SELECT * FROM dashboard_mail WHERE next_send <= ? ORDER BY next_send", now())) {
            String error = null;
            Sent r = null;
            try { r = send(s); } catch (RuntimeException e) { error = String.valueOf(e.getMessage()); }
            db.update("UPDATE dashboard_mail SET next_send = ?, last_sent = ?, last_error = ? WHERE id = ?", nextSend(s, now()), error == null ? now() : s.get("last_sent"),
                error == null ? null : error.substring(0, Math.min(1000, error.length())), s.get("id"));
            cr.audit.record("system", "dashboard.mailed", "dashboard", (String) s.get("dashboard_id"),
                r != null ? Map.of("schedule", s.get("id"), "to", r.to(), "left_out", r.leftOut()) : error, r != null ? "ok" : "failed", null);
        }
    }

    /** How many addresses an e-mail went to, and how many named recipients were left out (no longer allowed, or no address). */
    record Sent(int to, int leftOut) {}

    /**
     * The e-mail, checked again when it is sent: each person named, or in a role or team named, who may still see the
     * dashboard gets the figures they may see; outside addresses (only while Settings allows them) get the figures as the
     * person who set it up sees them. Recipients who may see the same figures share one message.
     */
    private Sent send(Map<String, Object> s) {
        String dashboardId = (String) s.get("dashboard_id");
        Map<String, Object> d = db.one("SELECT * FROM dashboards WHERE id = ?", dashboardId);
        if (d == null) throw notFound("Dashboard");
        User setter = activeUser(String.valueOf(s.get("created_by")));
        if (setter == null) throw new OoaException("CR.MAIL_OWNER_GONE", "The person who set up this e-mail is no longer active", "Set it up again as someone active.");
        if (!mayManageMail(setter, d)) throw new OoaException("CR.FORBIDDEN", "Only the dashboard's owner or an administrator may e-mail it", "Ask its owner to set it up again.");
        String tz = String.valueOf(s.get("time_zone"));
        List<String> shared = csv(d.get("shared_with"));
        // who it goes to: people (each checked), and outside addresses
        Set<String> people = new LinkedHashSet<>();
        List<String> outside = new ArrayList<>();
        int leftOut = 0;
        for (String r : String.valueOf(s.get("recipients")).split(",")) {
            if (r.isBlank()) continue;
            if (r.startsWith("user:")) people.add(r.substring(5));
            else if (r.startsWith("role:") || r.startsWith("team:")) {
                if (!shared.contains("*") && !shared.contains(r)) { leftOut++; continue; }   // no longer shared with them
                List<Map<String, Object>> members = r.startsWith("role:")
                    ? db.query("SELECT username FROM users WHERE role = ? UNION SELECT username FROM user_roles WHERE role = ?", r.substring(5), r.substring(5))
                    : db.query("SELECT m.username FROM team_members m JOIN teams t ON t.id = m.team_id WHERE t.name = ?", r.substring(5));
                for (Map<String, Object> u : members) people.add((String) u.get("username"));
            } else {
                String person = userWithAddress(r);
                if (person != null) people.add(person);
                else if (outsideAllowed()) { if (!outside.contains(r)) outside.add(r); }
                else leftOut++;
            }
        }
        // one message per set of figures: people who see the same figures share it
        Map<String, Mail> mails = new LinkedHashMap<>();
        Set<String> addresses = new HashSet<>();
        for (String name : people) {
            User u = activeUser(name);
            Map<String, Object> row = u == null ? null : db.one("SELECT email FROM users WHERE username = ?", name);
            String email = row != null && row.get("email") instanceof String e && !e.isBlank() ? e.trim() : null;
            if (u == null || email == null || !mayRead(u, d)) { leftOut++; continue; }
            if (addresses.size() == 50 || !addresses.add(email.toLowerCase(Locale.ROOT))) continue;
            Mail m = render(data(u, dashboardId, tz), dashboardId, tz);
            mails.computeIfAbsent(m.text(), k -> m).to().add(email);
        }
        if (!outside.isEmpty()) {
            Mail m = render(data(setter, dashboardId, tz), dashboardId, tz);
            for (String a : outside) if (addresses.size() < 50 && addresses.add(a.toLowerCase(Locale.ROOT))) mails.computeIfAbsent(m.text(), k -> m).to().add(a);
        }
        if (addresses.isEmpty()) throw new OoaException("MAIL.NO_RECIPIENT", "None of the recipients may see this dashboard and has an e-mail address", "Share the dashboard with them, or add their e-mail address to their user.");
        int n = 0;
        for (Mail m : mails.values()) {
            cr.mailer.send(m.to(), "Dashboard: " + d.get("name"), m.text(), m.html(), List.of(new Mailer.Attachment(m.file() + ".pdf", "application/pdf", m.pdf())));
            n += m.to().size();
        }
        return new Sent(n, leftOut);
    }

    /** One message: its text and HTML bodies, the same figures as a PDF, and who gets it. */
    record Mail(String text, String html, byte[] pdf, String file, List<String> to) {}

    static final int MAIL_ROWS = 25;

    /** The figures as text, as HTML (a number, or a table of the first 25 parts per widget) and as a PDF; and a link. */
    @SuppressWarnings("unchecked")
    Mail render(Map<String, Object> d, String dashboardId, String tz) {
        String name = String.valueOf(d.get("name"));
        String date = LocalDate.now(ZoneId.of(tz)).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH));
        String dated = name + ", " + date;
        StringBuilder text = new StringBuilder(dated).append("\n\n");
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(esc(name)).append("</title></head>")
            .append("<body style=\"margin:0;padding:16px;background:#ffffff;color:#1f2328;font-family:'Segoe UI',Arial,sans-serif;font-size:14px\">")
            .append("<h1 style=\"font-size:18px;margin:0 0 2px\">").append(esc(name)).append("</h1>")
            .append("<p style=\"margin:0 0 16px;color:#4b535c\">").append(esc(date)).append("</p>");
        String th = "padding:4px 10px;border:1px solid #d0d7de;background:#f6f8fa;color:#1f2328;text-align:left;font-weight:600";
        String td = "padding:4px 10px;border:1px solid #d0d7de;color:#1f2328";
        for (Object o : (List<Object>) d.get("widgets")) {
            Map<String, Object> w = (Map<String, Object>) o;
            String title = String.valueOf(w.getOrDefault("title", w.getOrDefault("kpi", w.getOrDefault("metric", "Figure"))));
            html.append("<h2 style=\"font-size:15px;margin:16px 0 6px\">").append(esc(title)).append("</h2>");
            if (w.get("error") != null) {
                text.append(title).append(": ").append(w.get("error")).append("\n");
                html.append("<p style=\"margin:0;color:#b42318\">").append(esc(String.valueOf(w.get("error")))).append("</p>");
            } else if (w.containsKey("value")) {
                text.append(title).append(": ").append(figure(w.get("value"))).append("\n");
                html.append("<p style=\"margin:0;font-size:22px;font-weight:600\">").append(esc(figure(w.get("value")))).append("</p>");
            } else {
                text.append(title).append(":\n");
                List<Map<String, Object>> pts = (List<Map<String, Object>>) w.getOrDefault("points", List.of());
                html.append("<table style=\"border-collapse:collapse;font-size:13px\"><thead><tr><th style=\"").append(th).append("\">").append(esc(partHeading(w)))
                    .append("</th><th style=\"").append(th).append(";text-align:right\">").append(esc(figureHeading(w))).append("</th></tr></thead><tbody>");
                for (Map<String, Object> p : pts.subList(0, Math.min(MAIL_ROWS, pts.size()))) {
                    text.append("  ").append(p.get("label")).append(": ").append(figure(p.get("value"))).append("\n");
                    html.append("<tr><td style=\"").append(td).append("\">").append(esc(String.valueOf(p.get("label")))).append("</td><td style=\"").append(td)
                        .append(";text-align:right\">").append(esc(figure(p.get("value")))).append("</td></tr>");
                }
                if (pts.isEmpty()) html.append("<tr><td colspan=\"2\" style=\"").append(td).append("\">No figures for this period.</td></tr>");
                html.append("</tbody></table>");
                if (pts.size() > MAIL_ROWS) {
                    text.append("  and ").append(pts.size() - MAIL_ROWS).append(" more\n");
                    html.append("<p style=\"margin:4px 0 0;color:#4b535c\">And ").append(pts.size() - MAIL_ROWS).append(" more in One CommandCenter.</p>");
                }
            }
        }
        String link = String.valueOf(cr.platform.setting("public_url")).trim().replaceAll("/+$", "");
        String url = link.isEmpty() ? null : link + "/next/#/business?tab=dashboards&dashboard=" + dashboardId;
        text.append("\n").append(url == null ? "Open it in One CommandCenter: Operational Insights > Business > Dashboards." : "Open it: " + url).append("\n");
        html.append("<p style=\"margin:20px 0 0\">").append(url == null ? "Open it in One CommandCenter: Operational Insights &gt; Business &gt; Dashboards."
            : "<a href=\"" + esc(url) + "\" style=\"color:#0550ae\">Open it in One CommandCenter</a>").append("</p></body></html>");
        // the same figures as a PDF too, to keep or forward
        byte[] pdf = TextPdf.of(dated, Arrays.asList(text.substring(dated.length() + 2).split("\n", -1)));
        String file = name.replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        return new Mail(text.toString(), html.toString(), pdf, file.isEmpty() ? "dashboard" : file, new ArrayList<>());
    }

    /** The first column's heading: what the parts are (day, week, month, agent, or the KPI they are grouped by). */
    private static String partHeading(Map<String, Object> w) {
        String g = String.valueOf(w.getOrDefault("group", "none"));
        return switch (g) {
            case "day" -> "Day"; case "week" -> "Week"; case "month" -> "Month"; case "agent" -> "Agent";
            case "value" -> w.get("by") instanceof String by && !by.isBlank() ? by : "Value";
            default -> "Part";
        };
    }

    /** The second column's heading: the KPI, or the run figure. */
    private static String figureHeading(Map<String, Object> w) {
        if ("runs".equals(w.get("source"))) return switch (String.valueOf(w.getOrDefault("metric", "runs"))) {
            case "success_rate" -> "Success rate"; case "failed" -> "Failed runs"; case "avg_seconds" -> "Average seconds"; case "hours_saved" -> "Hours saved"; default -> "Runs";
        };
        return String.valueOf(w.getOrDefault("kpi", "Figure"));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
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
