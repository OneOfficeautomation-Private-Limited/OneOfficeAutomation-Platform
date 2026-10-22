package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * One Workflow runtime. A workflow (Name.process.json, designed in One Agent Studio and checked in like an agent) is a
 * list of stages: agent tasks, human tasks (a form), approvals, decisions and notifications. The engine keeps each
 * workflow run's data, moves it from stage to stage, starts agent runs, puts human tasks in the task inbox and waits
 * for them. Stage results are kept under the stage id and merged into the workflow data, so later stages can use
 * them as $name or $stage_id.name.
 */
public final class Workflows {
    static final Set<String> STAGES = Set.of("bot", "form", "approval", "decision", "notify");
    private final ControlRoom cr;
    private final Db db;

    Workflows(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    static boolean isWorkflow(String path) { return path != null && path.endsWith(".process.json"); }
    static boolean isForm(String path) { return path != null && path.endsWith(".form.json"); }

    // ===================== validation (at check-in) =====================

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> validate(Map<String, Object> def) {
        List<Map<String, Object>> p = new ArrayList<>();
        if (!(def.get("stages") instanceof List<?> stages) || stages.isEmpty()) { p.add(Map.of("message", "The workflow has no stages")); return p; }
        Set<String> ids = new HashSet<>();
        for (Object o : stages) if (o instanceof Map<?, ?> s) ids.add(String.valueOf(s.get("id")));
        for (Object o : stages) {
            if (!(o instanceof Map<?, ?> raw)) { p.add(Map.of("message", "A stage is not an object")); continue; }
            Map<String, Object> s = (Map<String, Object>) raw;
            String id = String.valueOf(s.get("id")), type = String.valueOf(s.get("type")), name = String.valueOf(s.getOrDefault("name", id));
            if (!STAGES.contains(type)) p.add(Map.of("stage", id, "message", "Stage " + name + " has an unknown type " + type));
            if (type.equals("bot") && !(s.get("bot") instanceof String b && b.endsWith(".flow.json"))) p.add(Map.of("stage", id, "message", "Agent task " + name + " needs an agent"));
            if (type.equals("form") && !(s.get("form") instanceof String f && f.endsWith(".form.json"))) p.add(Map.of("stage", id, "message", "Human task " + name + " needs a form"));
            if ((type.equals("form") || type.equals("approval")) && !(s.get("assignee") instanceof String a && !a.isBlank())) p.add(Map.of("stage", id, "message", name + " needs someone to assign it to"));
            if (type.equals("decision") && !(s.get("condition") instanceof String c && !c.isBlank())) p.add(Map.of("stage", id, "message", "Decision " + name + " needs a condition"));
            if (type.equals("decision") && s.get("condition") instanceof String c && !c.isBlank()) {
                try { Expressions.parse(c); } catch (RuntimeException e) { p.add(Map.of("stage", id, "message", "Decision " + name + ": " + e.getMessage())); }
            }
            if (type.equals("notify") && !(s.get("to") instanceof String t && !t.isBlank())) p.add(Map.of("stage", id, "message", "Notification " + name + " needs a recipient"));
            for (String k : List.of("if_true", "if_false", "next")) if (s.get(k) instanceof String t && !t.isBlank() && !t.equals("end") && !ids.contains(t)) p.add(Map.of("stage", id, "message", name + " points to a missing stage " + t));
            if (s.get("on_reject") instanceof String t && !List.of("end", "continue").contains(t) && !ids.contains(t)) p.add(Map.of("stage", id, "message", name + " points to a missing stage " + t));
        }
        if (def.get("start_form") != null && !(def.get("start_form") instanceof String f && f.endsWith(".form.json"))) p.add(Map.of("message", "The start form must be a form (.form.json)"));
        return p;
    }

    static List<Map<String, Object>> validateForm(Map<String, Object> def) {
        if (!(def.get("fields") instanceof List<?>)) return List.of(Map.of("message", "The form has no fields list"));
        return List.of();
    }

    // ===================== running =====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> definition(Map<String, Object> wf) {
        return (Map<String, Object>) JsonLite.parse((String) db.one("SELECT content FROM bot_versions WHERE path = ? AND version = ?", wf.get("path"), wf.get("version")).get("content"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stages(Map<String, Object> def) { return (List<Map<String, Object>>) (List<?>) def.getOrDefault("stages", List.of()); }

    /** Starts the live production version of a workflow with the given data. */
    public Map<String, Object> start(User by, String path, Map<String, Object> data, String ip) { return start(by, path, data, null, ip); }

    /**
     * As above with the request's reference (for example a ticket number), to find the run again. When the workflow has
     * a start form, the data is checked against it like a submitted form.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> start(User by, String path, Map<String, Object> data, String reference, String ip) {
        cr.require(by, "runs.start", "start workflows");
        if (!isWorkflow(path)) throw new OoaException("CR.BAD_INPUT", path + " is not a workflow");
        Map<String, Object> c = cr.content(path, "production");
        Map<String, Object> def = (Map<String, Object>) c.get("flow");
        List<Map<String, Object>> st = stages(def);
        if (st.isEmpty()) throw new OoaException("WF.EMPTY", "The workflow has no stages");
        Map<String, Object> d = new LinkedHashMap<>(data == null ? Map.of() : data);
        if (def.get("start_form") instanceof String sf) d.putAll(checkAnswers((Map<String, Object>) cr.content(sf, "production").get("flow"), d));
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (ref != null && ref.length() > 200) throw new OoaException("CR.BAD_INPUT", "References have up to 200 characters");
        String id = id("wf");
        db.update("INSERT INTO workflow_runs (id, path, version, status, stage, data, history, started_by, created, updated, reference) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, path, c.get("version"), "running", st.get(0).get("id"), JsonLite.write(d), "[]", by.username(), now(), now(), ref);
        cr.audit.record(by.username(), "workflow.start", "workflow", path, ref == null ? Map.of("run", id) : Map.of("run", id, "reference", ref), "ok", ip);
        advance(id);
        return ref == null ? Map.of("id", id) : Map.of("id", id, "reference", ref);
    }

    /** The form a workflow starts with (its fields), or none: then any starting data is taken as it is. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> startForm(User by, String path) {
        cr.require(by, "runs.start", "start workflows");
        if (!isWorkflow(path)) throw new OoaException("CR.BAD_INPUT", path + " is not a workflow");
        Map<String, Object> def = (Map<String, Object>) cr.content(path, "production").get("flow");
        if (!(def.get("start_form") instanceof String sf)) return Map.of("path", path, "form", Map.of());
        Map<String, Object> form = (Map<String, Object>) cr.content(sf, "production").get("flow");
        return Map.of("path", path, "form_path", sf, "form", form);
    }

    /** One workflow run, by id or by its reference (the newest with that reference). */
    public Map<String, Object> run(User by, String id, String reference) {
        cr.require(by, "runs.view", "view workflow runs");
        Map<String, Object> r = id != null ? db.one("SELECT * FROM workflow_runs WHERE id = ?", id)
                : db.one("SELECT * FROM workflow_runs WHERE reference = ? ORDER BY created DESC LIMIT 1", reference);
        if (r == null) throw notFound("Workflow run");
        Map<String, Object> m = new LinkedHashMap<>(r);
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) JsonLite.parse((String) r.get("data"));
        m.put("data", visible(d));
        m.put("history", JsonLite.parse((String) r.get("history")));
        m.put("error", r.get("error") == null ? null : JsonLite.parse((String) r.get("error")));
        m.put("tasks", db.query("SELECT id, stage, kind, title, assignee, status, claimed_by, outcome, created, completed FROM human_tasks WHERE workflow_run_id = ? ORDER BY created", r.get("id")));
        return m;
    }

    private void history(Map<String, Object> wf, List<Object> h, String stage, String event, Object detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", now().toString()); e.put("stage", stage); e.put("event", event);
        if (detail != null) e.put("detail", detail);
        h.add(e);
    }

    private static Map<String, Object> find(List<Map<String, Object>> st, String id) {
        for (Map<String, Object> s : st) if (String.valueOf(s.get("id")).equals(id)) return s;
        return null;
    }

    /** The stage after s: its explicit "next", else the following one, else the end (null). */
    private static String after(List<Map<String, Object>> st, Map<String, Object> s) {
        if (s.get("next") instanceof String n && !n.isBlank()) return n.equals("end") ? null : n;
        int i = st.indexOf(s);
        return i + 1 < st.size() ? String.valueOf(st.get(i + 1).get("id")) : null;
    }

    private Object lookup(Map<String, Object> data, String name) {
        return data.get(name);
    }

    /** Moves a workflow run forward until it waits for a person or an agent, or ends. Safe to call repeatedly. */
    @SuppressWarnings("unchecked")
    public synchronized void advance(String wfId) {
        for (int guard = 0; guard < 200; guard++) {
            Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
            if (wf == null || !"running".equals(wf.get("status"))) return;
            Map<String, Object> def = definition(wf);
            List<Map<String, Object>> st = stages(def);
            Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
            List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
            Map<String, Object> s = find(st, String.valueOf(wf.get("stage")));
            if (s == null) { finish(wfId, "completed", data, h, null); return; }
            String sid = String.valueOf(s.get("id")), type = String.valueOf(s.get("type"));
            String next;
            try {
                switch (type) {
                    case "bot" -> {
                        Map<String, Object> inputs = s.get("inputs") instanceof Map<?, ?> in ? (Map<String, Object>) Expressions.resolve(in, n -> lookup(data, n)) : new LinkedHashMap<>();
                        Map<String, Object> starter = db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", wf.get("started_by"));
                        if (starter == null) throw new OoaException("WF.STARTER_DISABLED", "The person who started the workflow is disabled");
                        String pool = s.get("pool_id") instanceof String p && !p.isBlank() ? p : null;
                        String device = pool == null ? cr.platform.onlineRunner() : null;
                        if (device == null && pool == null) {
                            if (!"runner".equals(data.get("_waiting"))) { data.put("_waiting", "runner"); history(wf, h, sid, "waiting", "No runner is online; the agent starts when one is."); save(wfId, "running", sid, data, h); }
                            return; // the clock tries again
                        }
                        data.remove("_waiting");
                        Map<String, Object> run = cr.startRun((String) wf.get("started_by"), (String) starter.get("role"), (String) s.get("bot"), device, pool, inputs, "workflow:" + wfId, null, 3, null);
                        data.put("_run", run.get("run_id"));
                        history(wf, h, sid, "agent_started", Map.of("run_id", run.get("run_id"), "agent", s.get("bot")));
                        save(wfId, "waiting", sid, data, h);
                        return;
                    }
                    case "form", "approval" -> {
                        Map<String, Object> form = null;
                        if (type.equals("form")) form = (Map<String, Object>) cr.content((String) s.get("form"), "production").get("flow");
                        String tid = id("task");
                        Instant due = s.get("due_hours") instanceof Number dh ? now().plus(Duration.ofMinutes((long) (dh.doubleValue() * 60))) : null;
                        String title = String.valueOf(s.getOrDefault("name", sid)) + " — " + String.valueOf(def.getOrDefault("name", wf.get("path"))).replace(".process.json", "");
                        db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, form, data, assignee, status, created, due) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                                tid, wfId, sid, type, title, s.get("description"), form == null ? null : JsonLite.write(form), JsonLite.write(visible(data)),
                                String.valueOf(s.get("assignee")), "open", now(), due);
                        data.put("_task", tid);
                        history(wf, h, sid, "task_created", Map.of("task_id", tid, "assignee", s.get("assignee")));
                        save(wfId, "waiting", sid, data, h);
                        return;
                    }
                    case "decision" -> {
                        boolean yes = Expressions.truthy(Expressions.evaluate(String.valueOf(s.get("condition")), n -> lookup(data, n)));
                        Object target = yes ? s.get("if_true") : s.get("if_false");
                        history(wf, h, sid, "decided", Map.of("result", yes));
                        next = target instanceof String t && !t.isBlank() ? (t.equals("end") ? null : t) : after(st, s);
                    }
                    case "notify" -> {
                        String msg = String.valueOf(Expressions.resolve(String.valueOf(s.getOrDefault("message", "")), n -> lookup(data, n)));
                        List<String> inbox = new ArrayList<>(), other = new ArrayList<>();
                        for (String to : String.valueOf(s.get("to")).split(",")) { String t = to.trim(); if (t.startsWith("user:") || t.startsWith("role:")) inbox.add(t); else if (!t.isEmpty()) other.add(t); }
                        if (!inbox.isEmpty())
                            db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, data, assignee, status, created) VALUES (?,?,?,?,?,?,?,?,?,?)",
                                    id("task"), wfId, sid, "notification", String.valueOf(s.getOrDefault("name", "Notification")), msg, JsonLite.write(visible(data)), String.join(",", inbox), "open", now());
                        history(wf, h, sid, "notified", other.isEmpty() ? Map.of("to", inbox) : Map.of("to", inbox, "not_delivered", other,
                                "note", "E-mail addresses need an agent task with the Email toolkit; the inbox notification was sent."));
                        next = after(st, s);
                    }
                    default -> throw new OoaException("WF.BAD_STAGE", "Unknown stage type " + type);
                }
            } catch (OoaException e) {
                history(wf, h, sid, "failed", Map.of("code", e.code(), "message", e.getMessage()));
                finish(wfId, "failed", data, h, Map.of("code", e.code(), "message", e.getMessage(), "stage", sid));
                return;
            } catch (RuntimeException e) {
                history(wf, h, sid, "failed", Map.of("message", String.valueOf(e.getMessage())));
                finish(wfId, "failed", data, h, Map.of("code", "WF.STAGE_FAILED", "message", String.valueOf(e.getMessage()), "stage", sid));
                return;
            }
            if (next == null) { finish(wfId, "completed", data, h, null); return; }
            save(wfId, "running", next, data, h);
        }
    }

    /** Workflow data without the engine's own bookkeeping, for showing to people. */
    private static Map<String, Object> visible(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>(data);
        m.keySet().removeIf(k -> k.startsWith("_"));
        return m;
    }

    private void save(String id, String status, String stage, Map<String, Object> data, List<Object> h) {
        db.update("UPDATE workflow_runs SET status = ?, stage = ?, data = ?, history = ?, updated = ? WHERE id = ?", status, stage, JsonLite.write(data), JsonLite.write(h), now(), id);
    }

    private void finish(String id, String status, Map<String, Object> data, List<Object> h, Map<String, Object> error) {
        data.remove("_run"); data.remove("_task"); data.remove("_waiting");
        db.update("UPDATE workflow_runs SET status = ?, stage = NULL, data = ?, history = ?, updated = ?, finished = ?, error = ? WHERE id = ?",
                status, JsonLite.write(data), JsonLite.write(h), now(), now(), error == null ? null : JsonLite.write(error), id);
        db.update("UPDATE human_tasks SET status = 'cancelled' WHERE workflow_run_id = ? AND status IN ('open', 'claimed') AND kind <> 'notification'", id);
        cr.audit.record("workflow", "workflow." + status, "workflow", id, error, status.equals("completed") ? "ok" : "failed", null);
    }

    /** Merges a stage result into the data (under the stage id, and its fields at the top level), then moves on. */
    @SuppressWarnings("unchecked")
    private void resume(String wfId, String stageId, Map<String, Object> result, String event, Object detail, String forcedNext) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
        if (wf == null || !"waiting".equals(wf.get("status")) || !stageId.equals(wf.get("stage"))) return;
        List<Map<String, Object>> st = stages(definition(wf));
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
        data.remove("_run"); data.remove("_task");
        if (result != null) { data.put(stageId, result); data.putAll(result); }
        if ("task_completed".equals(event) && result != null) data.put("form", result); // the designer's $form.field
        history(wf, h, stageId, event, detail);
        Map<String, Object> s = find(st, stageId);
        String next = forcedNext != null ? (forcedNext.equals("end") ? null : forcedNext) : s == null ? null : after(st, s);
        if (next == null) { finish(wfId, "completed", data, h, null); return; }
        save(wfId, "running", next, data, h);
        advance(wfId);
    }

    /** Called when an agent run finishes; continues the workflow that started it. */
    @SuppressWarnings("unchecked")
    void onRunFinished(Map<String, Object> run, String status, Map<String, Object> result) {
        String src = String.valueOf(run.get("source"));
        if (!src.startsWith("workflow:")) return;
        String wfId = src.substring("workflow:".length());
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
        if (wf == null) return;
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        if (!String.valueOf(run.get("id")).equals(String.valueOf(data.get("_run")))) return;
        String stage = String.valueOf(wf.get("stage"));
        if (status.equals("success")) {
            Map<String, Object> outputs = result.get("outputs") instanceof Map<?, ?> o ? new LinkedHashMap<>((Map<String, Object>) o) : new LinkedHashMap<>();
            resume(wfId, stage, outputs, "agent_finished", Map.of("run_id", run.get("id"), "status", status), null);
        } else {
            List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
            history(wf, h, stage, "agent_" + status, Map.of("run_id", run.get("id"), "error", result.getOrDefault("error", Map.of())));
            finish(wfId, "failed", data, h, Map.of("code", "WF.AGENT_" + status.toUpperCase(Locale.ROOT), "message", "The agent task ended " + status, "stage", stage, "run_id", run.get("id")));
        }
    }

    /** Retries workflow runs waiting for a runner; called by the server clock. */
    public void tick() {
        for (Map<String, Object> wf : db.query("SELECT id FROM workflow_runs WHERE status = 'running'")) {
            try { advance((String) wf.get("id")); } catch (RuntimeException ignored) { }
        }
    }

    public Map<String, Object> cancel(User by, String wfId, String ip) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
        if (wf == null) throw notFound("Workflow run");
        if (!by.username().equals(wf.get("started_by"))) cr.require(by, "runs.stop", "cancel workflows");
        if (!Set.of("running", "waiting").contains(String.valueOf(wf.get("status")))) throw new OoaException("CR.CONFLICT", "The workflow run has already ended");
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        if (data.get("_run") instanceof String rid) db.update("UPDATE runs SET cancel_requested = TRUE WHERE id = ? AND status IN ('queued', 'running')", rid);
        db.update("UPDATE runs SET status = 'stopped', finished = ? WHERE source = ? AND status = 'queued'", now(), "workflow:" + wfId);
        @SuppressWarnings("unchecked") List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
        history(wf, h, String.valueOf(wf.get("stage")), "cancelled", Map.of("by", by.username()));
        finish(wfId, "cancelled", data, h, Map.of("code", "WF.CANCELLED", "message", "Cancelled by " + by.username()));
        cr.audit.record(by.username(), "workflow.cancel", "workflow", wfId, null, "ok", ip);
        return Map.of("ok", true);
    }

    public List<Map<String, Object>> runs(User by, String status) {
        cr.require(by, "runs.view", "view workflow runs");
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT * FROM workflow_runs" + (status == null || status.isBlank() ? "" : " WHERE status = ?") + " ORDER BY created DESC LIMIT 300";
        for (Map<String, Object> r : status == null || status.isBlank() ? db.query(sql) : db.query(sql, status)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) JsonLite.parse((String) r.get("data"));
            m.put("data", visible(d));
            m.put("history", JsonLite.parse((String) r.get("history")));
            m.put("error", r.get("error") == null ? null : JsonLite.parse((String) r.get("error")));
            out.add(m);
        }
        return out;
    }

    // ===================== task inbox =====================

    static boolean assignedTo(String assignee, User u) {
        for (String a : String.valueOf(assignee).split(",")) {
            String t = a.trim();
            if (t.equals("user:" + u.username()) || t.equals("role:" + u.role()) || t.equals("role:*")) return true;
        }
        return false;
    }

    private boolean mayAct(User u, Map<String, Object> t) {
        if (cr.allowed(u.role(), "users.manage")) return true;
        if ("claimed".equals(t.get("status"))) return u.username().equals(t.get("claimed_by"));
        return assignedTo((String) t.get("assignee"), u);
    }

    /** Tasks for the signed-in person: assigned to them or their role (administrators see all with all=true). */
    public List<Map<String, Object>> tasks(User u, String status, boolean all) {
        String st = status == null || status.isBlank() ? "open" : status;
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> rows = st.equals("open")
                ? db.query("SELECT * FROM human_tasks WHERE status IN ('open', 'claimed') ORDER BY COALESCE(due, created), created LIMIT 500")
                : db.query("SELECT * FROM human_tasks WHERE status = ? ORDER BY completed DESC LIMIT 300", st);
        boolean admin = cr.allowed(u.role(), "users.manage");
        for (Map<String, Object> t : rows) {
            boolean mine = assignedTo((String) t.get("assignee"), u) || u.username().equals(t.get("claimed_by")) || u.username().equals(t.get("completed_by"));
            if (!mine && !(admin && all)) continue;
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("form", t.get("form") == null ? null : JsonLite.parse((String) t.get("form")));
            m.put("data", t.get("data") == null ? Map.of() : JsonLite.parse((String) t.get("data")));
            m.put("result", t.get("result") == null ? null : JsonLite.parse((String) t.get("result")));
            m.put("overdue", t.get("due") != null && "open,claimed".contains(String.valueOf(t.get("status"))) && Instant.parse(String.valueOf(t.get("due"))).isBefore(now()));
            m.put("can_act", mayAct(u, t) && Set.of("open", "claimed").contains(String.valueOf(t.get("status"))));
            out.add(m);
        }
        return out;
    }

    public long openTaskCount(User u) {
        long n = 0;
        for (Map<String, Object> t : db.query("SELECT assignee, claimed_by, status FROM human_tasks WHERE status IN ('open', 'claimed')"))
            if ("claimed".equals(t.get("status")) ? u.username().equals(t.get("claimed_by")) : assignedTo((String) t.get("assignee"), u)) n++;
        return n;
    }

    public void claim(User u, String taskId, boolean release, String ip) {
        Map<String, Object> t = db.one("SELECT * FROM human_tasks WHERE id = ?", taskId);
        if (t == null) throw notFound("Task");
        if (!mayAct(u, t)) throw denied("work on this task");
        if (release) {
            db.update("UPDATE human_tasks SET status = 'open', claimed_by = NULL WHERE id = ? AND status = 'claimed'", taskId);
        } else {
            if (db.update("UPDATE human_tasks SET status = 'claimed', claimed_by = ? WHERE id = ? AND status = 'open'", u.username(), taskId) == 0)
                throw new OoaException("CR.CONFLICT", "Someone else took this task", "Refresh the inbox.");
        }
        cr.audit.record(u.username(), release ? "task.release" : "task.claim", "task", taskId, null, "ok", ip);
    }

    /** Completes a task: form answers (checked against the form), an approval outcome, or dismissing a notification. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> complete(User u, String taskId, String outcome, Map<String, Object> answers, String comment, String ip) {
        Map<String, Object> t = db.one("SELECT * FROM human_tasks WHERE id = ?", taskId);
        if (t == null) throw notFound("Task");
        if (!Set.of("open", "claimed").contains(String.valueOf(t.get("status")))) throw new OoaException("CR.CONFLICT", "This task is already " + t.get("status"));
        if (!mayAct(u, t)) throw denied("complete this task");
        String kind = String.valueOf(t.get("kind"));
        Map<String, Object> result = new LinkedHashMap<>();
        String forcedNext = null;
        String out;
        switch (kind) {
            case "form" -> {
                result.putAll(checkAnswers(t.get("form") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) t.get("form")), answers == null ? Map.of() : answers));
                out = "submitted";
            }
            case "approval" -> {
                if (!Set.of("approved", "rejected").contains(String.valueOf(outcome))) throw new OoaException("CR.BAD_INPUT", "Choose approved or rejected");
                out = outcome;
                result.put("approved", out.equals("approved"));
                result.put("approver", u.username());
                if (comment != null && !comment.isBlank()) result.put("comment", comment);
                if (out.equals("rejected")) {
                    Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", t.get("workflow_run_id"));
                    Map<String, Object> s = wf == null ? null : find(stages(definition(wf)), String.valueOf(t.get("stage")));
                    String onReject = s == null ? "end" : String.valueOf(s.getOrDefault("on_reject", "end"));
                    forcedNext = onReject.equals("continue") ? null : onReject;
                }
            }
            default -> out = "done";
        }
        db.update("UPDATE human_tasks SET status = 'completed', outcome = ?, result = ?, completed = ?, completed_by = ?, claimed_by = COALESCE(claimed_by, ?) WHERE id = ?",
                out, JsonLite.write(result), now(), u.username(), u.username(), taskId);
        cr.audit.record(u.username(), "task.complete", "task", taskId, Map.of("outcome", out), "ok", ip);
        if (t.get("workflow_run_id") != null && !kind.equals("notification"))
            resume((String) t.get("workflow_run_id"), String.valueOf(t.get("stage")), result, kind.equals("approval") ? out : "task_completed",
                    Map.of("task_id", taskId, "by", u.username()), forcedNext);
        return Map.of("ok", true, "outcome", out);
    }

    /** Checks answers against the form: required fields, numbers and their range, choices from the options. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> checkAnswers(Map<String, Object> form, Map<String, Object> answers) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object o : (List<Object>) form.getOrDefault("fields", List.of())) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> f = (Map<String, Object>) raw;
            String type = String.valueOf(f.get("type"));
            if (type.equals("heading") || type.equals("paragraph") || !(f.get("id") instanceof String id)) continue;
            String label = String.valueOf(f.getOrDefault("label", id));
            Object v = answers.get(id);
            boolean empty = v == null || (v instanceof String s && s.isBlank());
            if (empty) {
                if (Boolean.TRUE.equals(f.get("required")) && !type.equals("checkbox")) throw new OoaException("CR.BAD_INPUT", label + " is required");
                if (type.equals("checkbox")) out.put(id, false);
                continue;
            }
            switch (type) {
                case "number" -> {
                    double d;
                    try { d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim()); }
                    catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", label + " needs a number"); }
                    if (f.get("min") instanceof Number mn && d < mn.doubleValue()) throw new OoaException("CR.BAD_INPUT", label + " must be at least " + mn);
                    if (f.get("max") instanceof Number mx && d > mx.doubleValue()) throw new OoaException("CR.BAD_INPUT", label + " must be at most " + mx);
                    out.put(id, new java.math.BigDecimal(String.valueOf(v).trim()));
                }
                case "checkbox" -> out.put(id, Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v)));
                case "dropdown", "radio" -> {
                    if (f.get("options") instanceof List<?> opts && !opts.contains(String.valueOf(v))) throw new OoaException("CR.BAD_INPUT", label + ": choose one of " + opts);
                    out.put(id, String.valueOf(v));
                }
                case "email" -> {
                    if (!String.valueOf(v).matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new OoaException("CR.BAD_INPUT", label + " needs an e-mail address");
                    out.put(id, String.valueOf(v).trim());
                }
                case "date" -> {
                    try { java.time.LocalDate.parse(String.valueOf(v)); } catch (RuntimeException e) { throw new OoaException("CR.BAD_INPUT", label + " needs a date (YYYY-MM-DD)"); }
                    out.put(id, String.valueOf(v));
                }
                default -> out.put(id, String.valueOf(v));
            }
        }
        return out;
    }
}
