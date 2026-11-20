package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Administration tools for AI assistants (S3, 20 Nov 2026; docs/integration/one-chat-contract.md, section 3): what One
 * Chat (and later the OneChat service, through admin/tools and admin/actions) may offer as administration. Each tool is
 * done by the kernel's own method, so it checks exactly the permission the console page checks and writes the same
 * Audit Trail entries; a tool is offered only to people who have its permission. Look-ups (group admin_read, risk R0)
 * answer with a sentence and a small table; changes (admin_change, R2) and high-impact changes (admin_high, R3) say what
 * was done. Whether a group is offered at all, and whether it waits for a yes, is the assistant's settings' business.
 * The choices an input offers (people, schedules, runners, runs) are filled in for the person, so even a rules-only
 * chat finds "jsmith" in "disable jsmith"; an example such as "disable {person}" names the input it expects.
 */
final class AdminTools {
    /** The most choices an input lists (beyond that the person types the name), and the most rows an answer shows. */
    static final int MAX_CHOICES = 200, MAX_ROWS = 20;

    private final ControlRoom cr;

    AdminTools(ControlRoom cr) { this.cr = cr; }

    /** One tool: its id, group, risk, name, description, the console permission it needs, examples, what undoing it takes, its page. */
    record Def(String id, String group, String risk, String name, String description, String permission, List<String> examples, String undo, String page) {}

    static final List<Def> DEFS = List.of(
            new Def("people", "admin_read", "R0", "People", "The people who can sign in: their role, whether they are enabled, and their last sign-in.",
                    "users.manage", List.of("list the people", "who is disabled", "show the users", "list the admins", "who are the administrators"), null, "#/users"),
            new Def("runners", "admin_read", "R0", "Runners", "The runner machines: online, offline or switched off, and how many runs each is running.",
                    "devices.view", List.of("which runners are offline", "runner status", "show the runners", "list the runners"), null, "#/devices"),
            new Def("failed_runs", "admin_read", "R0", "Failed runs", "Runs that failed in the last 24 hours, newest first, with the reason.",
                    "runs.view", List.of("what failed today", "failed runs", "which runs failed", "show the failures"), null, "#/activity"),
            new Def("schedules", "admin_read", "R0", "Schedules", "The schedules, whether each is on, and when it runs next.",
                    "schedules.view", List.of("what runs tonight", "list the schedules", "show the schedules", "which schedules are paused"), null, "#/schedules"),
            new Def("queues", "admin_read", "R0", "Work queues", "The work queues and how many items wait, are being worked on and failed.",
                    "queues.view", List.of("how many items are waiting", "work queue status", "show the work queues", "list the queues"), null, "#/queues"),
            new Def("disable_person", "admin_change", "R2", "Disable a person", "Disables a person: they cannot sign in, and schedules they own stop running.",
                    "users.manage", List.of("disable {person}", "deactivate {person}", "lock out {person}", "disable a person"),
                    "Yes: enable them again in Users, or ask One Chat to enable them.", "#/users"),
            new Def("enable_person", "admin_change", "R2", "Enable a person", "Enables a disabled person again, so they can sign in.",
                    "users.manage", List.of("enable {person}", "reactivate {person}", "enable a person"), "Yes: disable them again.", "#/users"),
            new Def("pause_schedule", "admin_change", "R2", "Pause a schedule", "Pauses a schedule: it starts no runs until it is resumed.",
                    "schedules.manage", List.of("pause {schedule}", "pause the schedule {schedule}", "switch off the schedule {schedule}", "pause a schedule"),
                    "Yes: resume it in Schedules, or ask One Chat to resume it.", "#/schedules"),
            new Def("resume_schedule", "admin_change", "R2", "Resume a schedule", "Resumes a paused schedule.",
                    "schedules.manage", List.of("resume {schedule}", "resume the schedule {schedule}", "switch on the schedule {schedule}", "resume a schedule"),
                    "Yes: pause it again.", "#/schedules"),
            new Def("stop_run", "admin_change", "R2", "Stop a run", "Stops a queued or running run.",
                    "runs.stop", List.of("stop run {run}", "stop the run {run}", "cancel run {run}", "stop a run"), "No: start the agent again if it is needed.", "#/activity"),
            new Def("runner_off", "admin_change", "R2", "Switch a runner off", "Switches a runner off: it takes no new work.",
                    "devices.manage", List.of("switch off runner {runner}", "disable runner {runner}", "take {runner} out of service", "switch a runner off"),
                    "Yes: switch it on again in Runner Machines, or ask One Chat.", "#/devices"),
            new Def("runner_on", "admin_change", "R2", "Switch a runner on", "Switches a runner on again, so it takes work.",
                    "devices.manage", List.of("switch on runner {runner}", "enable runner {runner}", "put {runner} back in service", "switch a runner on"),
                    "Yes: switch it off again.", "#/devices"),
            new Def("delete_person", "admin_high", "R3", "Delete a person",
                    "Deletes a person's account: their sign-ins, API keys and team places go; the Audit Trail keeps their name. Refused while they still own schedules, triggers or other things.",
                    "users.manage", List.of("delete {person}", "remove {person}", "delete the account of {person}", "delete a person"), "No: it cannot be undone.", "#/users"));

    /** The tools this person may use now (their permissions decide), with each input's choices filled in for them. */
    List<Map<String, Object>> tools(User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Def d : DEFS) {
            if (!cr.can(u, d.permission())) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", d.id());
            t.put("group", d.group());
            t.put("risk", d.risk());
            t.put("name", d.name());
            t.put("description", d.description());
            t.put("examples", d.examples());
            t.put("inputs", inputs(u, d.id()));
            if (d.undo() != null) t.put("undo", d.undo());
            t.put("page", d.page());
            out.add(t);
        }
        return out;
    }

    /** The tool's inputs, as an agent's input variables (name, type, description, required, default, enum). */
    private List<Map<String, Object>> inputs(User u, String id) {
        return switch (id) {
            case "people" -> List.of(choice("which", "Which people", List.of("everyone", "disabled", "administrators"), "everyone"));
            case "runners" -> List.of(choice("which", "Which runners", List.of("all", "offline", "online", "switched off"), "all"));
            case "schedules" -> List.of(choice("which", "Which schedules", List.of("all", "on", "paused"), "all"));
            case "disable_person" -> List.of(required("person", "The person to disable", names("SELECT username FROM users WHERE enabled = TRUE AND username <> ? ORDER BY username", u.username())));
            case "enable_person" -> List.of(required("person", "The person to enable", names("SELECT username FROM users WHERE enabled = FALSE ORDER BY username")));
            case "delete_person" -> List.of(required("person", "The person to delete", names("SELECT username FROM users WHERE username <> ? ORDER BY username", u.username())));
            case "pause_schedule" -> List.of(required("schedule", "The schedule to pause", names("SELECT name FROM schedules WHERE enabled = TRUE ORDER BY name")));
            case "resume_schedule" -> List.of(required("schedule", "The schedule to resume", names("SELECT name FROM schedules WHERE enabled = FALSE ORDER BY name")));
            case "stop_run" -> List.of(required("run", "The run to stop (its id)", names("SELECT id FROM runs WHERE status IN ('queued', 'running') ORDER BY created DESC")));
            case "runner_off" -> List.of(required("runner", "The runner to switch off", names("SELECT name FROM devices WHERE enabled = TRUE ORDER BY name")));
            case "runner_on" -> List.of(required("runner", "The runner to switch on", names("SELECT name FROM devices WHERE enabled = FALSE ORDER BY name")));
            default -> List.of();
        };
    }

    private List<String> names(String sql, Object... params) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : cr.db.query(sql, params)) {
            out.add(String.valueOf(r.values().iterator().next()));
            if (out.size() > MAX_CHOICES) return null;   // too many to list: the person types it
        }
        return out;
    }

    private static Map<String, Object> choice(String name, String description, List<String> values, String dflt) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name); v.put("type", "String"); v.put("direction", "input"); v.put("description", description);
        v.put("enum", values); v.put("default", dflt);
        return v;
    }

    private static Map<String, Object> required(String name, String description, List<String> values) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name); v.put("type", "String"); v.put("direction", "input"); v.put("description", description); v.put("required", true);
        if (values != null) v.put("enum", values);
        return v;
    }

    /** Uses a tool as this person: {text, rows?, columns?, page}. The kernel's own method checks the permission again. */
    Map<String, Object> run(User u, String id, Map<String, Object> inputs, String ip) {
        Def d = DEFS.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ControlRoom.notFoundError("The administration tool " + id));
        cr.require(u, d.permission(), "use the administration tool " + d.name());
        Map<String, Object> in = inputs == null ? Map.of() : inputs;
        Map<String, Object> out = switch (id) {
            case "people" -> people(u, text(in, "which", "everyone"));
            case "runners" -> runners(u, text(in, "which", "all"));
            case "failed_runs" -> failedRuns(u);
            case "schedules" -> schedules(u, text(in, "which", "all"));
            case "queues" -> queues(u);
            case "disable_person", "enable_person" -> {
                String who = text(in, "person", null);
                Map<String, Object> row = cr.db.one("SELECT id FROM users WHERE username = ?", who);
                if (row == null) throw ControlRoom.notFoundError("The person " + who);
                boolean enable = id.equals("enable_person");
                if (!enable && who.equals(u.username())) throw new OoaException("CR.BAD_INPUT", "You cannot disable yourself", "Ask another administrator.");
                cr.updateUser(u, (String) row.get("id"), Map.of("enabled", enable), ip);
                yield said(enable ? who + " is enabled again and can sign in." : who + " is disabled: they cannot sign in, and schedules they own stop running.");
            }
            case "delete_person" -> {
                String who = text(in, "person", null);
                cr.deleteUser(u, who, ip);
                yield said(who + " is deleted. The Audit Trail keeps their name.");
            }
            case "pause_schedule", "resume_schedule" -> {
                String name = text(in, "schedule", null);
                Map<String, Object> row = cr.db.one("SELECT id FROM schedules WHERE name = ?", name);
                if (row == null) throw ControlRoom.notFoundError("The schedule " + name);
                boolean resume = id.equals("resume_schedule");
                cr.updateSchedule(u, (String) row.get("id"), Map.of("enabled", resume), ip);
                yield said(resume ? "The schedule " + name + " is on again." : "The schedule " + name + " is paused: it starts no runs until it is resumed.");
            }
            case "stop_run" -> {
                String run = text(in, "run", null);
                cr.control(u, run, "stop", ip);
                yield said("Run " + run + " is being stopped.");
            }
            case "runner_off", "runner_on" -> {
                String name = text(in, "runner", null);
                Map<String, Object> row = cr.db.one("SELECT id FROM devices WHERE name = ?", name);
                if (row == null) throw ControlRoom.notFoundError("The runner " + name);
                boolean on = id.equals("runner_on");
                cr.setDeviceEnabled(u, (String) row.get("id"), on, ip);
                yield said(on ? "The runner " + name + " is on and takes work again." : "The runner " + name + " is switched off: it takes no new work.");
            }
            default -> throw ControlRoom.notFoundError("The administration tool " + id);
        };
        out.putIfAbsent("page", d.page());
        return out;
    }

    private static String text(Map<String, Object> in, String name, String dflt) {
        Object v = in.get(name);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (!s.isEmpty()) return s;
        if (dflt == null) throw new OoaException("CR.BAD_INPUT", "Say which " + name + " you mean");
        return dflt;
    }

    private static Map<String, Object> said(String text) { Map<String, Object> m = new LinkedHashMap<>(); m.put("text", text); return m; }

    private static Map<String, Object> table(String text, List<String> columns, List<Map<String, Object>> rows) {
        Map<String, Object> m = said(text);
        m.put("columns", columns);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(MAX_ROWS, rows.size()))) {
            Map<String, Object> k = new LinkedHashMap<>();
            for (String c : columns) k.put(c, r.get(c) == null ? null : r.get(c) instanceof Boolean || r.get(c) instanceof Number ? r.get(c) : String.valueOf(r.get(c)));
            kept.add(k);
        }
        m.put("rows", kept);
        return m;
    }

    private static String more(int shown, int all) { return all > shown ? " The first " + shown + " are below; the page shows them all." : ""; }

    private Map<String, Object> people(User u, String which) {
        List<Map<String, Object>> all = cr.users(u);
        List<Map<String, Object>> rows = all.stream().filter(r -> switch (which) {
            case "disabled" -> !Boolean.TRUE.equals(r.get("enabled"));
            case "administrators" -> "admin".equals(r.get("role"));
            default -> true;
        }).toList();
        long disabled = all.stream().filter(r -> !Boolean.TRUE.equals(r.get("enabled"))).count();
        String text = switch (which) {
            case "disabled" -> rows.isEmpty() ? "Nobody is disabled." : rows.size() + (rows.size() == 1 ? " person is" : " people are") + " disabled.";
            case "administrators" -> rows.size() + (rows.size() == 1 ? " administrator." : " administrators.");
            default -> all.size() + (all.size() == 1 ? " person, " : " people, ") + disabled + " disabled.";
        };
        return table(text + more(Math.min(MAX_ROWS, rows.size()), rows.size()), List.of("username", "display_name", "role", "enabled", "last_login"), rows);
    }

    private Map<String, Object> runners(User u, String which) {
        List<Map<String, Object>> all = cr.devices(u);
        String want = which.equals("switched off") ? "disabled" : which;
        List<Map<String, Object>> rows = all.stream().filter(r -> want.equals("all") || want.equals(r.get("status"))).toList();
        Map<String, Long> by = new LinkedHashMap<>();
        for (Map<String, Object> r : all) by.merge(String.valueOf(r.get("status")), 1L, Long::sum);
        String text = all.size() + (all.size() == 1 ? " runner: " : " runners: ") + by.getOrDefault("online", 0L) + " online, " + by.getOrDefault("offline", 0L)
                + " offline, " + by.getOrDefault("disabled", 0L) + " switched off.";
        return table(text + more(Math.min(MAX_ROWS, rows.size()), rows.size()), List.of("name", "status", "running", "hostname"), rows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> failedRuns(User u) {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : cr.runs(u, "history", 500)) {
            if (!"failed".equals(r.get("status"))) continue;
            Object at = r.get("finished") != null ? r.get("finished") : r.get("created");
            Instant when;
            try { when = at instanceof java.sql.Timestamp ts ? ts.toInstant() : Instant.parse(String.valueOf(at)); } catch (RuntimeException e) { when = Instant.now(); }
            if (when.isBefore(since)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("agent", String.valueOf(r.get("bot_path")).replaceAll("\\.flow\\.json$", ""));
            m.put("finished", String.valueOf(at));
            String reason = "";
            try { if (r.get("error") instanceof String e && JsonLite.parse(e) instanceof Map<?, ?> em && em.get("message") != null) reason = String.valueOf(em.get("message")); }
            catch (RuntimeException ignored) { /* an error that is not JSON: no reason shown */ }
            m.put("reason", reason.length() > 120 ? reason.substring(0, 120) + "..." : reason);
            rows.add(m);
        }
        String text = rows.isEmpty() ? "No runs failed in the last 24 hours." : rows.size() + (rows.size() == 1 ? " run" : " runs") + " failed in the last 24 hours.";
        return table(text + more(Math.min(MAX_ROWS, rows.size()), rows.size()), List.of("id", "agent", "finished", "reason"), rows);
    }

    private Map<String, Object> schedules(User u, String which) {
        List<Map<String, Object>> all = cr.schedules(u);
        List<Map<String, Object>> rows = all.stream().filter(r -> which.equals("all") || (which.equals("on") == Boolean.TRUE.equals(r.get("enabled")))).toList();
        long on = all.stream().filter(r -> Boolean.TRUE.equals(r.get("enabled"))).count();
        String text = all.size() + (all.size() == 1 ? " schedule: " : " schedules: ") + on + " on, " + (all.size() - on) + " paused.";
        return table(text + more(Math.min(MAX_ROWS, rows.size()), rows.size()), List.of("name", "enabled", "next_run", "bot_path"), rows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queues(User u) {
        List<Map<String, Object>> rows = new ArrayList<>();
        long waiting = 0;
        for (Map<String, Object> q : cr.platform.queues(u)) {
            Map<String, Object> c = q.get("counts") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            long w = n(c.get("new")) + n(c.get("retry")), working = n(c.get("in_progress")), failed = n(c.get("failed"));
            waiting += w;
            rows.add(new LinkedHashMap<>(Map.of("name", String.valueOf(q.get("name")), "waiting", w, "in_progress", working, "failed", failed)));
        }
        String text = rows.isEmpty() ? "There are no work queues you may see." : waiting + (waiting == 1 ? " item waits" : " items wait") + " in " + rows.size() + (rows.size() == 1 ? " work queue." : " work queues.");
        return table(text + more(Math.min(MAX_ROWS, rows.size()), rows.size()), List.of("name", "waiting", "in_progress", "failed"), rows);
    }

    private static long n(Object v) { return v instanceof Number x ? x.longValue() : 0; }
}
