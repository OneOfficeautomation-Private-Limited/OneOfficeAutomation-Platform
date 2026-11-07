package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * The Task inbox (kernel, docs/architecture/13-modules.md): people's tasks (human_tasks) from every source: workflow
 * forms, approvals and notifications (the orchestration module's engine), Document Automation's reviews, One Chat's
 * approvals, AI agents' requests. Assigned to people, roles or everyone with a permission (perm:); claimed, released and
 * completed here. Feature modules decide who sees their kinds (TaskKind) and act on their stages (TaskStage); the
 * workflow engine describes its tasks and continues when one is done (WorkflowTasks).
 */
public final class TaskInbox {
    private final ControlRoom cr;
    private final Db db;

    TaskInbox(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** The workflow engine's view of its tasks, when the orchestration module is installed. */
    private ModuleContext.WorkflowTasks workflowTasks() { return cr.modules == null ? null : cr.modules.workflowTasks; }


    public boolean assignedTo(String assignee, User u) {
        List<String> roles = cr.rolesOf(u.username(), u.role());
        for (String a : String.valueOf(assignee).split(",")) {
            String t = a.trim();
            if (t.equals("user:" + u.username()) || t.equals("role:*") || (t.startsWith("role:") && roles.contains(t.substring(5)))) return true;
            if (t.startsWith("perm:") && cr.can(u, t.substring(5))) return true;   // everyone with a permission (e.g. perm:docs.review)
        }
        return false;
    }

    private boolean mayAct(User u, Map<String, Object> t) {
        if (cr.can(u, "users.manage")) return true;
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
        boolean admin = cr.can(u, "users.manage");
        Map<String, Map<String, String>> folders = cr.folders.entries();
        Map<Object, Map<String, Object>> runs = new HashMap<>();   // each task's workflow, read once per run
        for (Map<String, Object> t : rows) {
            boolean mine = assignedTo((String) t.get("assignee"), u) || u.username().equals(t.get("claimed_by")) || u.username().equals(t.get("completed_by"));
            if (!mine && !(admin && all)) continue;
            if (!reviewVisible(u, t, folders)) continue;
            Map<String, Object> m = new LinkedHashMap<>(t);
            // where the task comes from (the workflow engine, a feature module: its name, reference and stage bar)
            ModuleContext.WorkflowTasks source = workflowTasks();
            if (t.get("workflow_run_id") != null && source != null) m.putAll(source.describe(t, runs));
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
        Map<String, Map<String, String>> folders = cr.folders.entries();
        for (Map<String, Object> t : db.query("SELECT kind, data, assignee, claimed_by, status FROM human_tasks WHERE status IN ('open', 'claimed')"))
            if (("claimed".equals(t.get("status")) ? u.username().equals(t.get("claimed_by")) : assignedTo((String) t.get("assignee"), u)) && reviewVisible(u, t, folders)) n++;
        return n;
    }

    /** A module's task (Document Automation's reviews) shows only to whom its module says, such as people who may see its folder. */
    @SuppressWarnings("unchecked")
    private boolean reviewVisible(User u, Map<String, Object> t, Map<String, Map<String, String>> folders) {
        ModuleContext.TaskKind rules = cr.modules == null ? null : cr.modules.taskKind(String.valueOf(t.get("kind")));
        if (rules == null || t.get("data") == null) return true;
        Object data = JsonLite.parse((String) t.get("data"));
        return rules.visible(u, data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), folders);
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
        ModuleContext.TaskKind moduleKind = cr.modules == null ? null : cr.modules.taskKind(kind);
        if (moduleKind != null && moduleKind.completeElsewhere() != null)
            throw new OoaException("CR.BAD_INPUT", moduleKind.completeElsewhere(), "The task then closes by itself.");
        // a stage a feature module owns (One Chat's R3 approvals: never your own request, administrators included)
        ModuleContext.TaskStage moduleStage = cr.modules == null ? null : cr.modules.taskStage(t.get("stage"));
        if (moduleStage != null) moduleStage.checkComplete(u, t);
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
                if (out.equals("rejected") && t.get("workflow_run_id") != null && workflowTasks() != null) {
                    String onReject = workflowTasks().onReject(t);   // the workflow's stage says where a rejection goes
                    forcedNext = onReject.equals("continue") ? null : onReject;
                }
            }
            default -> out = "done";
        }
        db.update("UPDATE human_tasks SET status = 'completed', outcome = ?, result = ?, completed = ?, completed_by = ?, claimed_by = COALESCE(claimed_by, ?) WHERE id = ?",
                out, JsonLite.write(result), now(), u.username(), u.username(), taskId);
        cr.audit.record(u.username(), "task.complete", "task", taskId, Map.of("outcome", out), "ok", ip);
        if (t.get("workflow_run_id") != null && !kind.equals("notification") && workflowTasks() != null)
            workflowTasks().completed(t, kind.equals("approval") ? out : "task_completed", result, u.username(), forcedNext);
        if (moduleStage != null) {
            String runId = moduleStage.decided(t, u, out, comment, ip);
            if (runId != null) return Map.of("ok", true, "outcome", out, "run_id", runId);
        }
        return Map.of("ok", true, "outcome", out);
    }

    /** Checks answers against the form: required fields, numbers and their range, choices from the options. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> checkAnswers(Map<String, Object> form, Map<String, Object> answers) {
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
