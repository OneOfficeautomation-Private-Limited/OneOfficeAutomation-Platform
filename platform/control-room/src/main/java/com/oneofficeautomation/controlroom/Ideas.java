package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Automation ideas (gap G65, after UiPath's Automation Hub and AA's Process Discovery pipeline): anyone signed in
 * suggests a process to automate and votes for others' ideas; the automation team (permission ideas.manage) assesses
 * each one, moves it through the pipeline and links the agent that was built.
 * <ul>
 * <li>Status: submitted, assessing, approved, building, live, or rejected and on_hold. The person who suggested an
 * idea may change and delete it while it is submitted; after that only the team can.</li>
 * <li>Score (0-100), worked out, never typed: half how automatable it is (rules, structured inputs, stability, each
 * 1-5), three tenths the benefit (hours saved a year, on a log scale that reaches 100 at 10,000 hours) and a fifth how
 * easy it is to build (low, medium or high complexity). Without the three automatable marks there is no score.</li>
 * <li>Estimated savings: cases a month times minutes each, as hours a year and, with the cost of an hour (the idea's
 * own, or Settings' hourly cost), as money a year. Once an agent is linked, its successful runs over the last 30 days
 * give the hours actually saved a month, so estimate and result sit side by side.</li>
 * </ul>
 */
public final class Ideas {
    static final List<String> STATUSES = List.of("submitted", "assessing", "approved", "building", "live", "rejected", "on_hold");
    static final Map<String, Integer> EASE = Map.of("low", 100, "medium", 60, "high", 25);
    private static final String COLS = "id, title, description, department, process_owner, status, volume_month, minutes_each, hourly_cost, complexity, "
        + "rules_score, structured_score, stable_score, bot_path, notes, voters, submitted_by, submitted, updated, updated_by";

    private final ControlRoom cr;
    private final Db db;

    Ideas(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** Hours saved a year: cases a month times minutes each. */
    static double hoursYear(Integer volumeMonth, Integer minutesEach) {
        return volumeMonth == null || minutesEach == null ? 0 : volumeMonth * 12.0 * minutesEach / 60.0;
    }

    /** The score 0-100, or null while the three automatable marks are not all given. */
    static Integer score(Integer rules, Integer structured, Integer stable, String complexity, double hoursYear) {
        if (rules == null || structured == null || stable == null) return null;
        double potential = (rules + structured + stable) / 15.0 * 100;
        double benefit = Math.min(100, 25 * Math.log10(1 + hoursYear));
        int ease = EASE.getOrDefault(complexity == null ? "medium" : complexity, 60);
        return (int) Math.round(0.5 * potential + 0.3 * benefit + 0.2 * ease);
    }

    private static Integer num(Object o) { return o instanceof Number n ? n.intValue() : null; }

    private static Set<String> voters(Object raw) {
        Set<String> s = new LinkedHashSet<>();
        if (raw != null) for (String v : String.valueOf(raw).split(",")) if (!v.isBlank()) s.add(v);
        return s;
    }

    private Map<String, Object> row(User by, Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        Set<String> v = voters(r.remove("voters"));
        m.remove("voters");
        m.put("votes", v.size());
        m.put("voted", v.contains(by.username()));
        Integer vol = num(r.get("volume_month")), min = num(r.get("minutes_each"));
        double hours = hoursYear(vol, min);
        m.put("hours_year", Math.round(hours));
        Integer cost = num(r.get("hourly_cost"));
        int c = cost != null ? cost : cr.platform.intSetting("hourly_cost");
        m.put("value_year", c > 0 ? Math.round(hours * c) : null);
        m.put("score", score(num(r.get("rules_score")), num(r.get("structured_score")), num(r.get("stable_score")), (String) r.get("complexity"), hours));
        String path = (String) r.get("bot_path");
        if (path != null && !path.isBlank()) {
            long runs = cr.count("SELECT COUNT(*) AS n FROM runs WHERE bot_path = ? AND status = 'success' AND created >= ?", path, now().minus(Duration.ofDays(30)));
            Map<String, Object> bot = db.one("SELECT minutes_saved FROM bots WHERE path = ?", path);
            Integer per = bot != null && bot.get("minutes_saved") instanceof Number n ? n.intValue() : min;
            m.put("runs_30_days", runs);
            m.put("hours_saved_30_days", per == null ? null : Math.round(runs * per / 6.0) / 10.0);
        }
        m.put("mine", by.username().equals(r.get("submitted_by")));
        return m;
    }

    public Map<String, Object> list(User by) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT " + COLS + " FROM ideas ORDER BY submitted DESC")) out.add(row(by, r));
        return Map.of("ideas", out, "can_manage", cr.can(by, "ideas.manage"), "statuses", STATUSES);
    }

    Map<String, Object> get(User by, String id) {
        Map<String, Object> r = db.one("SELECT " + COLS + " FROM ideas WHERE id = ?", id);
        if (r == null) throw notFound("Idea " + id);
        return row(by, r);
    }

    private static String text(Map<String, Object> b, String k, int max, boolean required) {
        Object o = b.get(k);
        String s = o == null ? "" : String.valueOf(o).trim();
        if (required && s.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Give the idea a " + k.replace('_', ' '));
        if (s.length() > max) throw new OoaException("CR.BAD_INPUT", "The " + k.replace('_', ' ') + " is up to " + max + " characters");
        return s.isEmpty() ? null : s;
    }

    private static Integer whole(Map<String, Object> b, String k, int min, int max) {
        Object o = b.get(k);
        if (o == null || String.valueOf(o).isBlank()) return null;
        long v;
        try { v = o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", k.replace('_', ' ') + " is a whole number"); }
        if (v < min || v > max) throw new OoaException("CR.BAD_INPUT", k.replace('_', ' ') + " is between " + min + " and " + max);
        return (int) v;
    }

    /** Suggests an idea (anyone signed in), or changes one: the team any field; the person who suggested it, the description while it is submitted. */
    public Map<String, Object> save(User by, String id, Map<String, Object> b, String ip) {
        boolean manage = cr.can(by, "ideas.manage");
        Map<String, Object> old = id == null ? null : db.one("SELECT " + COLS + " FROM ideas WHERE id = ?", id);
        if (id != null && old == null) throw notFound("Idea " + id);
        if (old != null && !manage && !(by.username().equals(old.get("submitted_by")) && "submitted".equals(old.get("status"))))
            throw new OoaException("CR.FORBIDDEN", "You may change your own ideas while they are submitted; after that the automation team changes them");
        String title = text(b, "title", 200, true), description = text(b, "description", 4000, false), department = text(b, "department", 120, false),
            owner = text(b, "process_owner", 120, false);
        Integer vol = whole(b, "volume_month", 0, 10_000_000), min = whole(b, "minutes_each", 0, 100_000);
        // what only the team sets: kept as it was when someone else saves
        String status = old == null ? "submitted" : (String) old.get("status");
        Integer cost = old == null ? null : num(old.get("hourly_cost")), rules = old == null ? null : num(old.get("rules_score")),
            structured = old == null ? null : num(old.get("structured_score")), stable = old == null ? null : num(old.get("stable_score"));
        String complexity = old == null ? null : (String) old.get("complexity"), path = old == null ? null : (String) old.get("bot_path"),
            notes = old == null ? null : (String) old.get("notes");
        if (manage) {
            if (b.containsKey("status")) {
                status = String.valueOf(b.get("status"));
                if (!STATUSES.contains(status)) throw new OoaException("CR.BAD_INPUT", "The status is one of " + String.join(", ", STATUSES));
            }
            cost = whole(b, "hourly_cost", 0, 100_000);
            rules = whole(b, "rules_score", 1, 5); structured = whole(b, "structured_score", 1, 5); stable = whole(b, "stable_score", 1, 5);
            complexity = text(b, "complexity", 10, false);
            if (complexity != null && !EASE.containsKey(complexity)) throw new OoaException("CR.BAD_INPUT", "The complexity is low, medium or high");
            path = text(b, "bot_path", 400, false);
            notes = text(b, "notes", 4000, false);
        }
        String iid = id == null ? ControlRoom.id("idea") : id;
        if (id == null) db.update("INSERT INTO ideas (id, title, description, department, process_owner, status, volume_month, minutes_each, hourly_cost, complexity, "
                + "rules_score, structured_score, stable_score, bot_path, notes, voters, submitted_by, submitted, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            iid, title, description, department, owner, status, vol, min, cost, complexity, rules, structured, stable, path, notes, "", by.username(), now(), now(), by.username());
        else db.update("UPDATE ideas SET title = ?, description = ?, department = ?, process_owner = ?, status = ?, volume_month = ?, minutes_each = ?, hourly_cost = ?, "
                + "complexity = ?, rules_score = ?, structured_score = ?, stable_score = ?, bot_path = ?, notes = ?, updated = ?, updated_by = ? WHERE id = ?",
            title, description, department, owner, status, vol, min, cost, complexity, rules, structured, stable, path, notes, now(), by.username(), iid);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("title", title);
        detail.put("status", status);
        if (old != null && !Objects.equals(old.get("status"), status)) detail.put("from_status", old.get("status"));
        cr.audit.record(by.username(), id == null ? "idea.create" : "idea.update", "idea", iid, detail, "ok", ip);
        return get(by, iid);
    }

    /** Adds the person's vote, or takes it back. */
    public Map<String, Object> vote(User by, String id) {
        Map<String, Object> r = db.one("SELECT voters FROM ideas WHERE id = ?", id);
        if (r == null) throw notFound("Idea " + id);
        Set<String> v = voters(r.get("voters"));
        if (!v.remove(by.username())) v.add(by.username());
        String joined = String.join(",", v);
        if (joined.length() > 20000) throw new OoaException("CR.BAD_INPUT", "This idea has too many votes to count more");
        db.update("UPDATE ideas SET voters = ? WHERE id = ?", joined, id);
        return get(by, id);
    }

    public void delete(User by, String id, String ip) {
        Map<String, Object> r = db.one("SELECT title, status, submitted_by FROM ideas WHERE id = ?", id);
        if (r == null) throw notFound("Idea " + id);
        if (!cr.can(by, "ideas.manage") && !(by.username().equals(r.get("submitted_by")) && "submitted".equals(r.get("status"))))
            throw new OoaException("CR.FORBIDDEN", "You may delete your own ideas while they are submitted");
        db.update("DELETE FROM ideas WHERE id = ?", id);
        cr.audit.record(by.username(), "idea.delete", "idea", id, Map.of("title", r.get("title")), "ok", ip);
    }
}
