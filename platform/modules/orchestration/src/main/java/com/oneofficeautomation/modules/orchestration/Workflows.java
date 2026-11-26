package com.oneofficeautomation.modules.orchestration;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * Workflow runtime. A workflow (Name.process.json, designed in OneAgentStudio and checked in like an agent) is a
 * list of steps (stages): agent tasks, human tasks (a form), approvals, decisions, notifications, and since the review
 * round of 26 Nov 2026 End (with a status), Parallel (branches that run at once and join), Wait (a time or a period)
 * and Sub-workflow (another workflow, waited for). The engine keeps each workflow run's data, moves it from step to
 * step, starts agent runs, puts human tasks in the task inbox and waits for them. Step results are kept under the step
 * id and merged into the workflow data, so later steps can use them as $name or $stage_id.name. Its human tasks are
 * in the kernel's Task inbox, which hands them back here when done (WorkflowTasks).
 *
 * A Parallel step's branches and a Sub-workflow run as child workflow runs (parent_id): a branch runs the parent's
 * own definition, narrowed to the branch's steps (branch = "stepId#index", nested ones joined by "/"); a sub-workflow
 * runs the live version of its own workflow. The parent waits; it goes on when they end.
 */
public final class Workflows implements ModuleContext.WorkflowTasks {
    public static final Set<String> STAGES = Set.of("bot", "form", "approval", "decision", "notify", "end", "parallel", "wait", "subflow");
    /** Who a task goes to besides user:, role: and perm: entries: the person who started the run, or one random member. */
    static final String STARTER = "starter", RANDOM = "random:";
    private final ModuleContext ctx;
    private final Db db;
    private final Random random = new Random();

    public Workflows(ModuleContext ctx) { this.ctx = ctx; this.db = ctx.db(); }

    public static boolean isWorkflow(String path) { return path != null && path.endsWith(".process.json"); }
    public static boolean isForm(String path) { return path != null && path.endsWith(".form.json"); }

    // ===================== validation (at check-in) =====================

    public static List<Map<String, Object>> validate(Map<String, Object> def) {
        List<Map<String, Object>> p = new ArrayList<>();
        if (!(def.get("stages") instanceof List<?> stages) || stages.isEmpty()) { p.add(Map.of("message", "The workflow has no stages")); return p; }
        Set<String> all = new HashSet<>();
        validateSteps(stages(def), p, all, true);
        if (def.get("start_form") != null && !(def.get("start_form") instanceof String f && f.endsWith(".form.json"))) p.add(Map.of("message", "The start form must be a form (.form.json)"));
        if (def.get("title") instanceof String t && !t.isBlank()) {
            try { Expressions.references(Map.of("t", t)); } catch (RuntimeException e) { p.add(Map.of("message", "Run title: " + e.getMessage())); }
        }
        return p;
    }

    /** Checks a list of steps (the workflow's, or a branch's): their own settings and where they point (in the same list). */
    @SuppressWarnings("unchecked")
    private static void validateSteps(List<Map<String, Object>> st, List<Map<String, Object>> p, Set<String> all, boolean top) {
        Set<String> ids = new HashSet<>();
        for (Object o : st) if (o instanceof Map<?, ?> s) ids.add(String.valueOf(s.get("id")));
        for (Object o : st) {
            if (!(o instanceof Map<?, ?> raw)) { p.add(Map.of("message", "A stage is not an object")); continue; }
            Map<String, Object> s = (Map<String, Object>) raw;
            String id = String.valueOf(s.get("id")), type = String.valueOf(s.get("type")), name = String.valueOf(s.getOrDefault("name", id));
            if (!all.add(id)) p.add(Map.of("stage", id, "message", "Two steps have the id " + id));
            if (!STAGES.contains(type)) p.add(Map.of("stage", id, "message", "Stage " + name + " has an unknown type " + type));
            if (type.equals("bot") && !(s.get("bot") instanceof String b && b.endsWith(".flow.json"))) p.add(Map.of("stage", id, "message", "Agent task " + name + " needs an agent"));
            if (type.equals("form") && !(s.get("form") instanceof String f && f.endsWith(".form.json"))) p.add(Map.of("stage", id, "message", "Human task " + name + " needs a form"));
            if ((type.equals("form") || type.equals("approval")) && !(s.get("assignee") instanceof String a && !a.isBlank())) p.add(Map.of("stage", id, "message", name + " needs someone to assign it to"));
            if (type.equals("approval") && s.get("approvals_needed") != null) {
                Object n = s.get("approvals_needed");
                if (n instanceof Number num) { if (num.intValue() < 1) p.add(Map.of("stage", id, "message", name + ": approvals needed is at least 1")); }
                else if (!"all".equals(n)) p.add(Map.of("stage", id, "message", name + ": approvals needed is a number or all"));
                else if (users(String.valueOf(s.get("assignee"))).isEmpty()) p.add(Map.of("stage", id, "message", name + ": Everyone needs the approvers named as people (user:)"));
            }
            if (type.equals("decision")) {
                boolean old = s.get("condition") instanceof String c && !c.isBlank();
                List<Map<String, Object>> branches = s.get("branches") instanceof List<?> l ? (List<Map<String, Object>>) (List<?>) l : List.of();
                if (!old && branches.isEmpty()) p.add(Map.of("stage", id, "message", "Decision " + name + " needs a condition"));
                List<String> conditions = new ArrayList<>();
                if (old) conditions.add((String) s.get("condition"));
                for (Map<String, Object> b : branches) {
                    if (!(b.get("condition") instanceof String c) || c.isBlank()) p.add(Map.of("stage", id, "message", "Decision " + name + ": a branch has no condition"));
                    else conditions.add(c);
                    if (b.get("goto") instanceof String t && !t.isBlank() && !t.equals("end") && !ids.contains(t)) p.add(Map.of("stage", id, "message", name + " points to a missing stage " + t));
                }
                for (String c : conditions) {
                    try { Expressions.parse(c); } catch (RuntimeException e) { p.add(Map.of("stage", id, "message", "Decision " + name + ": " + e.getMessage())); }
                }
                if (s.get("else") instanceof String t && !t.isBlank() && !t.equals("end") && !ids.contains(t)) p.add(Map.of("stage", id, "message", name + " points to a missing stage " + t));
            }
            if (type.equals("notify") && !(s.get("to") instanceof String t && !t.isBlank())) p.add(Map.of("stage", id, "message", "Notification " + name + " needs a recipient"));
            if (type.equals("end") && s.get("status") != null && !Set.of("completed", "failed", "cancelled").contains(String.valueOf(s.get("status"))))
                p.add(Map.of("stage", id, "message", "End " + name + ": the status is completed, failed or cancelled"));
            if (type.equals("wait") && waitUntil(s, Map.of(), Instant.EPOCH) == null && !(s.get("until") instanceof String u && !u.isBlank()))
                p.add(Map.of("stage", id, "message", "Wait " + name + " needs a date and time or a period"));
            if (type.equals("subflow") && !(s.get("workflow") instanceof String w && isWorkflow(w))) p.add(Map.of("stage", id, "message", "Sub-workflow " + name + " needs a workflow"));
            if (type.equals("parallel")) {
                List<Object> branches = s.get("branches") instanceof List<?> l ? (List<Object>) l : List.of();
                if (branches.size() < 2) p.add(Map.of("stage", id, "message", "Parallel " + name + " needs at least two branches"));
                for (Object b : branches) {
                    List<Map<String, Object>> inner = b instanceof Map<?, ?> bm && bm.get("stages") instanceof List<?> l ? (List<Map<String, Object>>) (List<?>) l : List.of();
                    if (inner.isEmpty()) p.add(Map.of("stage", id, "message", "Parallel " + name + ": a branch has no steps"));
                    else validateSteps(inner, p, all, false);
                }
                if (s.get("on_failure") != null && !Set.of("stop", "finish").contains(String.valueOf(s.get("on_failure"))))
                    p.add(Map.of("stage", id, "message", "Parallel " + name + ": when a branch fails, stop or finish"));
            }
            if (s.get("escalate") instanceof Map<?, ?> esc) {
                if (!(s.get("due_hours") instanceof Number)) p.add(Map.of("stage", id, "message", name + ": escalation needs a due time"));
                if ("reassign".equals(esc.get("action")) && !(esc.get("to") instanceof String to && !to.isBlank())) p.add(Map.of("stage", id, "message", name + ": hand the task to whom?"));
            }
            for (String k : List.of("if_true", "if_false", "next")) if (s.get(k) instanceof String t && !t.isBlank() && !t.equals("end") && !ids.contains(t)) p.add(Map.of("stage", id, "message", name + " points to a missing stage " + t));
            if (s.get("on_reject") instanceof String t && !List.of("end", "continue").contains(t) && !ids.contains(t)) p.add(Map.of("stage", id, "message", name + " points to a missing stage " + t));
        }
    }

    public static List<Map<String, Object>> validateForm(Map<String, Object> def) {
        if (!(def.get("fields") instanceof List<?>)) return List.of(Map.of("message", "The form has no fields list"));
        return List.of();
    }

    /** The people named in an assignee list (user: entries). */
    static List<String> users(String assignee) {
        List<String> out = new ArrayList<>();
        for (String a : String.valueOf(assignee).split(",")) if (a.trim().startsWith("user:")) out.add(a.trim().substring(5));
        return out;
    }

    // ===================== definitions =====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> definition(Map<String, Object> wf) {
        Map<String, Object> def = (Map<String, Object>) JsonLite.parse((String) db.one("SELECT content FROM bot_versions WHERE path = ? AND version = ?", wf.get("path"), wf.get("version")).get("content"));
        return wf.get("branch") instanceof String b && !b.isBlank() ? branchOf(def, b) : def;
    }

    /** A parallel branch as a workflow of its own: the steps of branch "stepId#index" (nested: joined by "/"). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> branchOf(Map<String, Object> def, String branch) {
        Map<String, Object> cur = def;
        for (String part : branch.split("/")) {
            int hash = part.lastIndexOf('#');
            Map<String, Object> par = find(stages(cur), part.substring(0, hash));
            if (par == null) throw new OoaException("WF.BAD_STAGE", "The workflow has no parallel step " + part, "Start the workflow again; its definition changed.");
            List<Object> branches = (List<Object>) par.getOrDefault("branches", List.of());
            Map<String, Object> b = (Map<String, Object>) branches.get(Integer.parseInt(part.substring(hash + 1)));
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("name", def.get("name"));
            next.put("stages", b.getOrDefault("stages", List.of()));
            cur = next;
        }
        return cur;
    }

    /** The name of the step a run is on now, or null. */
    private String stageName(Map<String, Object> run) {
        if (run.get("stage") == null) return null;
        for (Map<String, Object> s : stepNames(run)) if (s.get("id").equals(run.get("stage"))) return String.valueOf(s.get("name"));
        return String.valueOf(run.get("stage"));
    }

    private final Map<String, List<Map<String, Object>>> stepLists = new java.util.concurrent.ConcurrentHashMap<>();
    private List<Map<String, Object>> stepNames(Map<String, Object> wf) {
        return stepLists.computeIfAbsent(wf.get("path") + "@" + wf.get("version") + "@" + wf.get("branch"), k -> { try { return steps(definition(wf)); } catch (RuntimeException e) { return List.of(); } });
    }

    /** The stages people see in the stage bar (G48): the workflow's stages in order, without its decisions, as id and name. */
    private final Map<String, List<Map<String, Object>>> stageBars = new java.util.concurrent.ConcurrentHashMap<>();
    public List<Map<String, Object>> stageBar(Map<String, Object> wf) {
        return stageBars.computeIfAbsent(wf.get("path") + "@" + wf.get("version") + "@" + wf.get("branch"), k -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                for (Map<String, Object> s : stages(definition(wf)))
                    if (!Set.of("decision", "end").contains(String.valueOf(s.get("type")))) out.add(Map.of("id", String.valueOf(s.get("id")), "name", String.valueOf(s.getOrDefault("name", s.get("id")))));
            } catch (RuntimeException e) { return List.of(); }
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stages(Map<String, Object> def) { return (List<Map<String, Object>>) (List<?>) def.getOrDefault("stages", List.of()); }

    /** Every step of a workflow, in order, as the run page lists them: id, name and type (a parallel step's branches too). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> steps(Map<String, Object> def) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : stages(def)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(s.get("id"))); m.put("name", String.valueOf(s.getOrDefault("name", s.get("id")))); m.put("type", String.valueOf(s.get("type")));
            if (s.get("branches") instanceof List<?> bl && "parallel".equals(s.get("type"))) {
                List<Object> branches = new ArrayList<>();
                int i = 0;
                for (Object b : bl) {
                    Map<String, Object> bm = (Map<String, Object>) b;
                    branches.add(Map.of("name", String.valueOf(bm.getOrDefault("name", "Branch " + (i + 1))), "steps", steps(Map.of("stages", bm.getOrDefault("stages", List.of())))));
                    i++;
                }
                m.put("branches", branches);
            }
            out.add(m);
        }
        return out;
    }

    // ===================== starting and reading =====================

    /** Starts the live production version of a workflow with the given data. */
    public Map<String, Object> start(User by, String path, Map<String, Object> data, String ip) { return start(by, path, data, null, ip); }

    /**
     * As above with the request's reference (for example a ticket number), to find the run again. When the workflow has
     * a start form, the data is checked against it like a submitted form. Without a reference, the workflow's run title
     * ("Invoice $number$") gives one.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> start(User by, String path, Map<String, Object> data, String reference, String ip) {
        ctx.requireFeature("workflows", "Workflows");
        ctx.require(by, "runs.start", "start workflows");
        if (!isWorkflow(path)) throw new OoaException("CR.BAD_INPUT", path + " is not a workflow");
        Map<String, Object> c = ctx.agentContent(path, "production");
        Map<String, Object> def = (Map<String, Object>) c.get("flow");
        List<Map<String, Object>> st = stages(def);
        if (st.isEmpty()) throw new OoaException("WF.EMPTY", "The workflow has no stages", "Add at least one stage to the workflow.");
        Map<String, Object> d = new LinkedHashMap<>(data == null ? Map.of() : data);
        if (def.get("start_form") instanceof String sf) d.putAll(ModuleContext.checkAnswers((Map<String, Object>) ctx.agentContent(sf, "production").get("flow"), d));
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (ref == null && def.get("title") instanceof String t && !t.isBlank()) {
            try { ref = String.valueOf(Expressions.resolve(t, d::get)).trim(); } catch (RuntimeException e) { ref = null; }
            if (ref != null && (ref.isEmpty() || ref.length() > 200)) ref = ref.isEmpty() ? null : ref.substring(0, 200);
        }
        if (ref != null && ref.length() > 200) throw new OoaException("CR.BAD_INPUT", "References have up to 200 characters");
        String id = id("wf");
        db.update("INSERT INTO workflow_runs (id, path, version, status, stage, data, history, started_by, created, updated, reference) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, path, c.get("version"), "running", st.get(0).get("id"), JsonLite.write(d), "[]", by.username(), now(), now(), ref);
        ctx.audit(by.username(), "workflow.start", "workflow", path, ref == null ? Map.of("run", id) : Map.of("run", id, "reference", ref), "ok", ip);
        advance(id);
        return ref == null ? Map.of("id", id) : Map.of("id", id, "reference", ref);
    }

    /** The form a workflow starts with (its fields), or none: then any starting data is taken as it is. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> startForm(User by, String path) {
        ctx.require(by, "runs.start", "start workflows");
        if (!isWorkflow(path)) throw new OoaException("CR.BAD_INPUT", path + " is not a workflow");
        Map<String, Object> def = (Map<String, Object>) ctx.agentContent(path, "production").get("flow");
        if (!(def.get("start_form") instanceof String sf)) return Map.of("path", path, "form", Map.of());
        Map<String, Object> form = (Map<String, Object>) ctx.agentContent(sf, "production").get("flow");
        return Map.of("path", path, "form_path", sf, "form", form);
    }

    /** One workflow run, by id or by its reference (the newest with that reference). */
    public Map<String, Object> run(User by, String id, String reference) {
        ctx.require(by, "runs.view", "view workflow runs");
        Map<String, Object> r = id != null ? db.one("SELECT * FROM workflow_runs WHERE id = ?", id)
                : db.one("SELECT * FROM workflow_runs WHERE reference = ? AND parent_id IS NULL ORDER BY created DESC LIMIT 1", reference);
        if (r == null) throw notFound("Workflow run");
        Map<String, Object> m = shown(r, openTasks(String.valueOf(r.get("id"))));
        m.put("tasks", db.query("SELECT id, stage, kind, title, assignee, status, claimed_by, outcome, created, completed, due FROM human_tasks WHERE workflow_run_id = ? ORDER BY created", r.get("id")));
        try { m.put("steps", steps(definition(r))); } catch (RuntimeException e) { m.put("steps", List.of()); }
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, Object> ch : db.query("SELECT * FROM workflow_runs WHERE parent_id = ? ORDER BY created", r.get("id"))) {
            Map<String, Object> cm = shown(ch, openTasks(String.valueOf(ch.get("id"))));
            cm.remove("history");
            children.add(cm);
        }
        m.put("children", children);
        return m;
    }

    /** A run as the pages show it: data without the engine's own keys, its history and error, and what it waits on. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> shown(Map<String, Object> r, Map<String, List<Map<String, Object>>> open) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        Map<String, Object> d = (Map<String, Object>) JsonLite.parse((String) r.get("data"));
        m.put("data", visible(d));
        m.put("history", JsonLite.parse((String) r.get("history")));
        m.put("error", r.get("error") == null ? null : JsonLite.parse((String) r.get("error")));
        m.put("stage_name", stageName(r));
        waitingOn(m, d, open);
        return m;
    }

    /** Open human tasks of one run (and of its branches and sub-workflow), or of every run (null), by workflow run id. */
    private Map<String, List<Map<String, Object>>> openTasks(String runId) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        String sql = "SELECT id, workflow_run_id, assignee, claimed_by, due FROM human_tasks WHERE status IN ('open', 'claimed') AND kind <> 'notification' AND "
                + (runId == null ? "workflow_run_id IS NOT NULL" : "(workflow_run_id = ? OR workflow_run_id IN (SELECT id FROM workflow_runs WHERE parent_id = ?))");
        for (Map<String, Object> t : runId == null ? db.query(sql) : db.query(sql, runId, runId))
            out.computeIfAbsent(String.valueOf(t.get("workflow_run_id")), k -> new ArrayList<>()).add(t);
        return out;
    }

    /**
     * What a run waits on now (Run > Workflow runs, 26 Nov 2026): the people its open tasks are with, the runner it
     * waits for, its agent, a time, a sub-workflow or its branches; with the earliest due time of those tasks and
     * whether that has passed (late). A parallel step waits on its branches' people too.
     */
    private void waitingOn(Map<String, Object> m, Map<String, Object> data, Map<String, List<Map<String, Object>>> open) {
        if (!Set.of("running", "waiting").contains(String.valueOf(m.get("status")))) return;
        List<Map<String, Object>> tasks = new ArrayList<>(open.getOrDefault(String.valueOf(m.get("id")), List.of()));
        if (data.get("_children") instanceof List<?> kids) for (Object k : kids) tasks.addAll(open.getOrDefault(String.valueOf(k), List.of()));
        Instant due = null;
        List<String> who = new ArrayList<>();
        for (Map<String, Object> t : tasks) {
            String w = t.get("claimed_by") != null ? String.valueOf(t.get("claimed_by")) : String.valueOf(t.get("assignee"));
            if (!who.contains(w)) who.add(w);
            if (t.get("due") != null) { Instant d = Instant.parse(String.valueOf(t.get("due"))); if (due == null || d.isBefore(due)) due = d; }
        }
        if (!who.isEmpty()) { m.put("waiting_on", String.join(", ", who)); m.put("waiting_kind", "person"); }
        else if ("runner".equals(data.get("_waiting"))) { m.put("waiting_on", "a runner to come online"); m.put("waiting_kind", "runner"); }
        else if (data.get("_run") != null) { m.put("waiting_on", "agent run " + data.get("_run")); m.put("waiting_kind", "agent"); }
        else if (data.get("_until") != null) { m.put("waiting_on", "until " + data.get("_until")); m.put("waiting_kind", "time"); }
        else if (data.get("_children") instanceof List<?> kids && !kids.isEmpty()) { m.put("waiting_on", kids.size() == 1 ? "a sub-workflow" : kids.size() + " branches"); m.put("waiting_kind", "children"); }
        if (due != null) { m.put("due", due.toString()); m.put("late", due.isBefore(now())); }
    }

    public List<Map<String, Object>> runs(User by, String status) {
        ctx.require(by, "runs.view", "view workflow runs");
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT * FROM workflow_runs WHERE parent_id IS NULL" + (status == null || status.isBlank() ? "" : " AND status = ?") + " ORDER BY created DESC LIMIT 300";
        Map<String, List<Map<String, Object>>> open = openTasks(null);
        for (Map<String, Object> r : status == null || status.isBlank() ? db.query(sql) : db.query(sql, status)) out.add(shown(r, open));
        return out;
    }

    // ===================== running =====================

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

    private static Object lookup(Map<String, Object> data, String name) { return data.get(name); }

    /** When a Wait step ends: its date and time ("until", an ISO time or an expression), or now plus its period. */
    static Instant waitUntil(Map<String, Object> s, Map<String, Object> data, Instant from) {
        if (s.get("until") instanceof String u && !u.isBlank()) {
            Object v;
            try { v = u.contains("$") ? Expressions.resolve(u, n -> lookup(data, n)) : u; } catch (RuntimeException e) { return null; }
            String text = String.valueOf(v).trim();
            try { return Instant.parse(text); } catch (RuntimeException e) { /* a date without a zone */ }
            try { return java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant(); } catch (RuntimeException e) { /* a date only */ }
            try { return java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(); } catch (RuntimeException e) { return null; }
        }
        long minutes = 0;
        if (s.get("days") instanceof Number d) minutes += Math.round(d.doubleValue() * 1440);
        if (s.get("hours") instanceof Number h) minutes += Math.round(h.doubleValue() * 60);
        if (s.get("minutes") instanceof Number mi) minutes += Math.round(mi.doubleValue());
        return minutes > 0 ? from.plus(Duration.ofMinutes(minutes)) : null;
    }

    /**
     * Who a task goes to: the step's assignee list, where "starter" is the person who started the workflow and
     * "random:team:NAME" or "random:role:NAME" one member picked at random (spreading the work), each as user:.
     */
    String resolveAssignee(String assignee, Map<String, Object> wf) {
        List<String> out = new ArrayList<>();
        for (String a : String.valueOf(assignee).split(",")) {
            String t = a.trim();
            if (t.isEmpty()) continue;
            if (t.equals(STARTER)) { out.add("user:" + wf.get("started_by")); continue; }
            if (t.startsWith(RANDOM)) {
                String what = t.substring(RANDOM.length());
                List<Map<String, Object>> people = what.startsWith("team:")
                        ? db.query("SELECT m.username FROM team_members m JOIN teams t ON t.id = m.team_id JOIN users u ON u.username = m.username WHERE t.name = ? AND u.enabled = TRUE", what.substring(5))
                        : what.startsWith("role:") ? db.query("SELECT username FROM users WHERE role = ? AND enabled = TRUE", what.substring(5)) : List.of();
                if (people.isEmpty()) throw new OoaException("WF.NO_ASSIGNEE", "No one is in " + what + " to give the task to", "Add people to " + what + ", or change who the step goes to.");
                out.add("user:" + people.get(random.nextInt(people.size())).get("username"));
                continue;
            }
            out.add(t);
        }
        return String.join(",", out);
    }

    /** Moves a workflow run forward until it waits for a person, an agent, a time or other runs, or ends. Safe to call repeatedly. */
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
                        if (starter == null) throw new OoaException("WF.STARTER_DISABLED", "The person who started the workflow is disabled", "Ask an administrator to enable the person who started it, or start the workflow again.");
                        String pool = s.get("pool_id") instanceof String p && !p.isBlank() ? p : null;
                        String device = pool == null ? ctx.onlineRunner() : null;
                        if (device == null && pool == null) {
                            if (!"runner".equals(data.get("_waiting"))) { data.put("_waiting", "runner"); history(wf, h, sid, "waiting", "No runner is online; the agent starts when one is."); save(wfId, "running", sid, data, h); }
                            return; // the clock tries again
                        }
                        data.remove("_waiting");
                        Map<String, Object> run = ctx.startRun((String) wf.get("started_by"), (String) starter.get("role"), (String) s.get("bot"), device, pool, inputs, "workflow:" + wfId, null, 3, null, null, null);
                        data.put("_run", run.get("run_id"));
                        history(wf, h, sid, "agent_started", Map.of("run_id", run.get("run_id"), "agent", s.get("bot")));
                        save(wfId, "waiting", sid, data, h);
                        return;
                    }
                    case "form", "approval" -> {
                        createTask(wf, def, s, data, h, null);
                        save(wfId, "waiting", sid, data, h);
                        return;
                    }
                    case "decision" -> {
                        next = decide(wf, st, s, data, h);
                    }
                    case "notify" -> {
                        notifyStep(wf, def, s, data, h);
                        next = after(st, s);
                    }
                    case "end" -> {
                        String status = String.valueOf(s.getOrDefault("status", "completed"));
                        String msg = s.get("message") instanceof String mm && !mm.isBlank() ? String.valueOf(Expressions.resolve(mm, n -> lookup(data, n))) : null;
                        history(wf, h, sid, "ended", msg == null ? Map.of("status", status) : Map.of("status", status, "message", msg));
                        if (msg != null) data.put("end_message", msg);
                        finish(wfId, status, data, h, status.equals("completed") ? null
                                : Map.of("code", "WF.ENDED_" + status.toUpperCase(Locale.ROOT), "message", msg != null ? msg : "The workflow ended " + status + " at " + s.getOrDefault("name", sid), "stage", sid));
                        return;
                    }
                    case "wait" -> {
                        Instant until = waitUntil(s, data, now());
                        if (until == null) throw new OoaException("WF.BAD_WAIT", "Wait " + s.getOrDefault("name", sid) + " has no time to wait until", "Give the Wait step a date and time or a period.");
                        if (!until.isAfter(now())) { history(wf, h, sid, "waited", Map.of("until", until.toString(), "note", "The time had passed")); next = after(st, s); break; }
                        data.put("_until", until.toString());
                        history(wf, h, sid, "waiting", Map.of("until", until.toString()));
                        save(wfId, "waiting", sid, data, h);
                        db.update("UPDATE workflow_runs SET wake_at = ? WHERE id = ?", until, wfId);
                        return;
                    }
                    case "subflow" -> {
                        String path = String.valueOf(s.get("workflow"));
                        Map<String, Object> c = ctx.agentContent(path, "production");
                        Map<String, Object> childDef = (Map<String, Object>) c.get("flow");
                        if (stages(childDef).isEmpty()) throw new OoaException("WF.EMPTY", "The sub-workflow " + path + " has no stages", "Add stages to it, or choose another workflow.");
                        Map<String, Object> in = s.get("inputs") instanceof Map<?, ?> im ? new LinkedHashMap<>((Map<String, Object>) Expressions.resolve(im, n -> lookup(data, n))) : visible(data);
                        String child = startChild(wf, path, c.get("version"), null, String.valueOf(stages(childDef).get(0).get("id")), in, sid);
                        data.put("_children", List.of(child));
                        history(wf, h, sid, "subflow_started", Map.of("run", child, "workflow", path));
                        save(wfId, "waiting", sid, data, h);
                        advance(child);
                        return;
                    }
                    case "parallel" -> {
                        List<Object> branches = (List<Object>) s.getOrDefault("branches", List.of());
                        String prefix = wf.get("branch") instanceof String b && !b.isBlank() ? b + "/" : "";
                        List<String> kids = new ArrayList<>();
                        for (int i = 0; i < branches.size(); i++) {
                            List<Map<String, Object>> inner = (List<Map<String, Object>>) ((Map<String, Object>) branches.get(i)).getOrDefault("stages", List.of());
                            if (inner.isEmpty()) continue;
                            kids.add(startChild(wf, String.valueOf(wf.get("path")), wf.get("version"), prefix + sid + "#" + i, String.valueOf(inner.get(0).get("id")), visible(data), sid));
                        }
                        data.put("_children", kids);
                        history(wf, h, sid, "split", Map.of("branches", kids.size()));
                        save(wfId, "waiting", sid, data, h);
                        for (String k : kids) advance(k);
                        joinIfDone(wfId);
                        return;
                    }
                    default -> throw new OoaException("WF.BAD_STAGE", "Unknown stage type " + type, "Update One CommandCenter; the workflow uses a stage it does not know.");
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

    /** A decision: its branches in order (if, else if...), else its else; the old form is one condition with yes and no. */
    @SuppressWarnings("unchecked")
    private String decide(Map<String, Object> wf, List<Map<String, Object>> st, Map<String, Object> s, Map<String, Object> data, List<Object> h) {
        String sid = String.valueOf(s.get("id"));
        if (s.get("branches") instanceof List<?> bl && !bl.isEmpty()) {
            int i = 0;
            for (Object o : bl) {
                Map<String, Object> b = (Map<String, Object>) o;
                if (Expressions.truthy(Expressions.evaluate(String.valueOf(b.get("condition")), n -> lookup(data, n)))) {
                    history(wf, h, sid, "decided", Map.of("branch", String.valueOf(b.getOrDefault("name", "Branch " + (i + 1)))));
                    return target(st, s, b.get("goto"));
                }
                i++;
            }
            history(wf, h, sid, "decided", Map.of("branch", "Else"));
            return target(st, s, s.get("else"));
        }
        boolean yes = Expressions.truthy(Expressions.evaluate(String.valueOf(s.get("condition")), n -> lookup(data, n)));
        history(wf, h, sid, "decided", Map.of("result", yes));
        return target(st, s, yes ? s.get("if_true") : s.get("if_false"));
    }

    private static String target(List<Map<String, Object>> st, Map<String, Object> s, Object t) {
        return t instanceof String g && !g.isBlank() ? (g.equals("end") ? null : g) : after(st, s);
    }

    /** A human task or approval for a step, with its assignee resolved; tells the people it goes to. */
    @SuppressWarnings("unchecked")
    private void createTask(Map<String, Object> wf, Map<String, Object> def, Map<String, Object> s, Map<String, Object> data, List<Object> h, String assigneeOverride) {
        String sid = String.valueOf(s.get("id")), type = String.valueOf(s.get("type"));
        Map<String, Object> form = null;
        if (type.equals("form")) form = (Map<String, Object>) ctx.agentContent((String) s.get("form"), "production").get("flow");
        else if (s.get("form") instanceof String af && isForm(af)) form = (Map<String, Object>) ctx.agentContent(af, "production").get("flow");   // what the approver sees
        String assignee = assigneeOverride != null ? assigneeOverride : resolveAssignee(String.valueOf(s.get("assignee")), wf);
        String tid = id("task");
        Instant due = s.get("due_hours") instanceof Number dh ? now().plus(Duration.ofMinutes((long) (dh.doubleValue() * 60))) : null;
        String title = String.valueOf(s.getOrDefault("name", sid)) + " — " + String.valueOf(def.getOrDefault("name", wf.get("path"))).replace(".process.json", "");
        db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, form, data, assignee, status, created, due) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                tid, wf.get("id"), sid, type, title, s.get("description"), form == null ? null : JsonLite.write(form), JsonLite.write(visible(data)),
                assignee, "open", now(), due);
        data.put("_task", tid);
        history(wf, h, sid, "task_created", Map.of("task_id", tid, "assignee", assignee));
        // Settings: the people the task is given to hear about it by e-mail, on their phone and in the Teams
        // channel; approvals carry each person's own Approve and Reject links when those are on (G43)
        ctx.notifyTaskCreated(tid, type, title, assignee, due, visible(data));
    }

    private void notifyStep(Map<String, Object> wf, Map<String, Object> def, Map<String, Object> s, Map<String, Object> data, List<Object> h) {
        String sid = String.valueOf(s.get("id"));
        String msg = String.valueOf(Expressions.resolve(String.valueOf(s.getOrDefault("message", "")), n -> lookup(data, n)));
        List<String> inbox = new ArrayList<>(), other = new ArrayList<>();
        for (String to : resolveAssignee(String.valueOf(s.get("to")), wf).split(",")) { String t = to.trim(); if (t.startsWith("user:") || t.startsWith("role:")) inbox.add(t); else if (!t.isEmpty()) other.add(t); }
        if (!inbox.isEmpty())
            db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, data, assignee, status, created) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id("task"), wf.get("id"), sid, "notification", String.valueOf(s.getOrDefault("name", "Notification")), msg, JsonLite.write(visible(data)), String.join(",", inbox), "open", now());
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("to", inbox);
        if (!other.isEmpty()) {
            // e-mail addresses go out through One CommandCenter's own mail server (Settings > E-mail)
            if (!ctx.mailConfigured()) { sent.put("not_delivered", other); sent.put("note", "E-mail is not set up (Manage > Settings > E-mail); the inbox notification was sent."); }
            else {
                String subject = String.valueOf(s.getOrDefault("subject", s.getOrDefault("name", "Notification") + " — " + String.valueOf(def.getOrDefault("name", wf.get("path"))).replace(".process.json", "")));
                try { ctx.sendMail(other, String.valueOf(Expressions.resolve(subject, n -> lookup(data, n))), msg); sent.put("emailed", other); }
                catch (OoaException e) { sent.put("not_delivered", other); sent.put("note", e.getMessage()); }
            }
        }
        history(wf, h, sid, "notified", sent);
    }

    /** A branch or sub-workflow run under a parent: its own data (a copy), started by the same person. */
    private String startChild(Map<String, Object> parent, String path, Object version, String branch, String firstStage, Map<String, Object> data, String parentStage) {
        String id = id("wf");
        db.update("INSERT INTO workflow_runs (id, path, version, status, stage, data, history, started_by, created, updated, reference, parent_id, branch) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, path, version, "running", firstStage, JsonLite.write(data), "[]", parent.get("started_by"), now(), now(),
                parent.get("reference") == null ? null : parent.get("reference") + " · " + parentStage, parent.get("id"), branch);
        return id;
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
        data.remove("_run"); data.remove("_task"); data.remove("_waiting"); data.remove("_until");
        Object kids = data.remove("_children");
        db.update("UPDATE workflow_runs SET status = ?, stage = NULL, data = ?, history = ?, updated = ?, finished = ?, error = ?, wake_at = NULL WHERE id = ?",
                status, JsonLite.write(data), JsonLite.write(h), now(), now(), error == null ? null : JsonLite.write(error), id);
        db.update("UPDATE human_tasks SET status = 'cancelled' WHERE workflow_run_id = ? AND status IN ('open', 'claimed') AND kind <> 'notification'", id);
        if (!status.equals("completed") && kids instanceof List<?> l) for (Object k : l) stopChild(String.valueOf(k), "the workflow it belongs to ended " + status);
        ctx.audit("workflow", "workflow." + status, "workflow", id, error, status.equals("completed") ? "ok" : "failed", null);
        Map<String, Object> wf = db.one("SELECT parent_id FROM workflow_runs WHERE id = ?", id);
        if (wf != null && wf.get("parent_id") != null) childEnded(String.valueOf(wf.get("parent_id")));
    }

    /** Stops a branch or sub-workflow that is still going (its agent run and open tasks too). */
    @SuppressWarnings("unchecked")
    private void stopChild(String id, String why) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", id);
        if (wf == null || !Set.of("running", "waiting").contains(String.valueOf(wf.get("status")))) return;
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        if (data.get("_run") instanceof String rid) db.update("UPDATE runs SET cancel_requested = TRUE WHERE id = ? AND status IN ('queued', 'running')", rid);
        db.update("UPDATE runs SET status = 'stopped', finished = ? WHERE source = ? AND status = 'queued'", now(), "workflow:" + id);
        List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
        history(wf, h, String.valueOf(wf.get("stage")), "cancelled", Map.of("why", why));
        data.remove("_run"); data.remove("_task"); data.remove("_waiting"); data.remove("_until");
        Object kids = data.remove("_children");
        db.update("UPDATE workflow_runs SET status = 'cancelled', stage = NULL, data = ?, history = ?, updated = ?, finished = ?, error = ?, wake_at = NULL WHERE id = ?",
                JsonLite.write(data), JsonLite.write(h), now(), now(), JsonLite.write(Map.of("code", "WF.CANCELLED", "message", "Cancelled: " + why)), id);
        db.update("UPDATE human_tasks SET status = 'cancelled' WHERE workflow_run_id = ? AND status IN ('open', 'claimed') AND kind <> 'notification'", id);
        if (kids instanceof List<?> l) for (Object k : l) stopChild(String.valueOf(k), why);
    }

    /** A branch or sub-workflow of this run ended: join when they all have (or fail at once, as the step says). */
    private void childEnded(String parentId) { joinIfDone(parentId); }

    @SuppressWarnings("unchecked")
    private synchronized void joinIfDone(String parentId) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", parentId);
        if (wf == null || !"waiting".equals(wf.get("status"))) return;
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        if (!(data.get("_children") instanceof List<?> kids)) return;
        Map<String, Object> s = find(stages(definition(wf)), String.valueOf(wf.get("stage")));
        if (s == null) return;
        boolean stopAtOnce = !"finish".equals(s.get("on_failure"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object k : kids) { Map<String, Object> r = db.one("SELECT * FROM workflow_runs WHERE id = ?", String.valueOf(k)); if (r != null) rows.add(r); }
        Map<String, Object> failed = rows.stream().filter(r -> Set.of("failed", "cancelled").contains(String.valueOf(r.get("status")))).findFirst().orElse(null);
        boolean allDone = rows.stream().noneMatch(r -> Set.of("running", "waiting").contains(String.valueOf(r.get("status"))));
        String sid = String.valueOf(s.get("id"));
        if (failed != null && (stopAtOnce || allDone)) {
            for (Map<String, Object> r : rows) stopChild(String.valueOf(r.get("id")), "another branch failed");
            List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
            Object err = failed.get("error") == null ? Map.of() : JsonLite.parse((String) failed.get("error"));
            history(wf, h, sid, "branch_failed", Map.of("run", failed.get("id"), "error", err));
            String label = "subflow".equals(s.get("type")) ? "The sub-workflow" : "A branch";
            finish(parentId, "failed", data, h, Map.of("code", "WF.BRANCH_FAILED", "message", label + " of " + s.getOrDefault("name", sid) + " ended " + failed.get("status"), "stage", sid, "run", failed.get("id")));
            return;
        }
        if (!allDone) return;
        // join: each branch's data under the step id (by branch name), and its new values at the top level
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> branches = s.get("branches") instanceof List<?> bl ? (List<Object>) bl : List.of();
        for (Map<String, Object> r : rows) {
            Map<String, Object> d = visible((Map<String, Object>) JsonLite.parse((String) r.get("data")));
            String name = "result";
            if (r.get("branch") instanceof String b && !b.isBlank()) {
                int idx = Integer.parseInt(b.substring(b.lastIndexOf('#') + 1));
                name = idx < branches.size() ? String.valueOf(((Map<String, Object>) branches.get(idx)).getOrDefault("name", "Branch " + (idx + 1))) : "Branch " + (idx + 1);
            }
            result.put(name, d);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Object v : result.values()) merged.putAll((Map<String, Object>) v);
        data.remove("_children");
        save(parentId, "waiting", sid, data, new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history"))));
        resume(parentId, sid, "subflow".equals(s.get("type")) ? merged : withResult(merged, result), "subflow".equals(s.get("type")) ? "subflow_finished" : "joined", Map.of("runs", rows.size()), null);
    }

    private static Map<String, Object> withResult(Map<String, Object> merged, Map<String, Object> byBranch) {
        Map<String, Object> m = new LinkedHashMap<>(merged);
        m.put("branches", byBranch);
        return m;
    }

    /** Merges a stage result into the data (under the stage id, and its fields at the top level), then moves on. */
    @SuppressWarnings("unchecked")
    private void resume(String wfId, String stageId, Map<String, Object> result, String event, Object detail, String forcedNext) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
        if (wf == null || !"waiting".equals(wf.get("status")) || !stageId.equals(wf.get("stage"))) return;
        List<Map<String, Object>> st = stages(definition(wf));
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
        data.remove("_run"); data.remove("_task"); data.remove("_until"); data.remove("_children");
        if (result != null) { data.put(stageId, result); data.putAll(result); }
        if ("task_completed".equals(event) && result != null) data.put("form", result); // the designer's $form.field
        history(wf, h, stageId, event, detail);
        Map<String, Object> s = find(st, stageId);
        String next = forcedNext != null ? (forcedNext.equals("end") ? null : forcedNext) : s == null ? null : after(st, s);
        db.update("UPDATE workflow_runs SET wake_at = NULL WHERE id = ?", wfId);
        if (next == null) { finish(wfId, "completed", data, h, null); return; }
        save(wfId, "running", next, data, h);
        advance(wfId);
    }

    /** Called when an agent run finishes; continues the workflow that started it. */
    @SuppressWarnings("unchecked")
    public void onRunFinished(Map<String, Object> run, String status, Map<String, Object> result) {
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

    /** The server clock: runs waiting for a runner are retried, Wait steps whose time has come go on, late tasks escalate. */
    public void tick() {
        for (Map<String, Object> wf : db.query("SELECT id FROM workflow_runs WHERE status = 'running'")) {
            try { advance((String) wf.get("id")); } catch (RuntimeException ignored) { }
        }
        for (Map<String, Object> wf : db.query("SELECT id, stage FROM workflow_runs WHERE status = 'waiting' AND wake_at IS NOT NULL AND wake_at <= ?", now())) {
            try { resume((String) wf.get("id"), String.valueOf(wf.get("stage")), null, "waited", null, null); } catch (RuntimeException ignored) { }
        }
        try { escalate(); } catch (RuntimeException ignored) { }
    }

    /**
     * Late tasks (review round, 26 Nov 2026): a human task or approval past its due time whose step says what then.
     * Remind tells the people again; Reassign hands the task to someone else. Each task escalates once.
     */
    @SuppressWarnings("unchecked")
    void escalate() {
        for (Map<String, Object> t : db.query("SELECT * FROM human_tasks WHERE status IN ('open', 'claimed') AND workflow_run_id IS NOT NULL AND due IS NOT NULL AND due < ? AND escalated IS NULL", now())) {
            Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", t.get("workflow_run_id"));
            if (wf == null) continue;
            Map<String, Object> s = find(stages(definition(wf)), String.valueOf(t.get("stage")));
            db.update("UPDATE human_tasks SET escalated = ? WHERE id = ?", now(), t.get("id"));
            if (s == null || !(s.get("escalate") instanceof Map<?, ?> raw)) continue;
            Map<String, Object> esc = (Map<String, Object>) raw;
            List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
            Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
            if ("reassign".equals(esc.get("action")) && esc.get("to") instanceof String to && !to.isBlank()) {
                String who = resolveAssignee(to, wf);
                db.update("UPDATE human_tasks SET assignee = ?, status = 'open', claimed_by = NULL WHERE id = ?", who, t.get("id"));
                history(wf, h, String.valueOf(t.get("stage")), "escalated", Map.of("task_id", t.get("id"), "action", "reassign", "to", who));
                ctx.notifyTaskCreated((String) t.get("id"), (String) t.get("kind"), "Late: " + t.get("title"), who, null, visible(data));
            } else {
                history(wf, h, String.valueOf(t.get("stage")), "escalated", Map.of("task_id", t.get("id"), "action", "remind"));
                ctx.notifyTaskCreated((String) t.get("id"), (String) t.get("kind"), "Reminder: " + t.get("title"), (String) t.get("assignee"), null, visible(data));
            }
            db.update("UPDATE workflow_runs SET history = ?, updated = ? WHERE id = ?", JsonLite.write(h), now(), wf.get("id"));
        }
    }

    // ===================== people acting on a run =====================

    public Map<String, Object> cancel(User by, String wfId, String ip) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
        if (wf == null) throw notFound("Workflow run");
        if (!by.username().equals(wf.get("started_by"))) ctx.require(by, "runs.stop", "cancel workflows");
        if (!Set.of("running", "waiting").contains(String.valueOf(wf.get("status")))) throw new OoaException("CR.CONFLICT", "The workflow run has already ended", "Reload the page; the workflow run has ended.");
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        if (data.get("_run") instanceof String rid) db.update("UPDATE runs SET cancel_requested = TRUE WHERE id = ? AND status IN ('queued', 'running')", rid);
        db.update("UPDATE runs SET status = 'stopped', finished = ? WHERE source = ? AND status = 'queued'", now(), "workflow:" + wfId);
        @SuppressWarnings("unchecked") List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
        history(wf, h, String.valueOf(wf.get("stage")), "cancelled", Map.of("by", by.username()));
        finish(wfId, "cancelled", data, h, Map.of("code", "WF.CANCELLED", "message", "Cancelled by " + by.username()));
        ctx.audit(by.username(), "workflow.cancel", "workflow", wfId, null, "ok", ip);
        return Map.of("ok", true);
    }

    /** The run and its open tasks (its own and its branches'), for Remind, Reassign and Skip; operators only. */
    private Map<String, Object> going(User by, String wfId, String what) {
        ctx.require(by, "runs.stop", what);
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
        if (wf == null) throw notFound("Workflow run");
        if (!Set.of("running", "waiting").contains(String.valueOf(wf.get("status")))) throw new OoaException("CR.CONFLICT", "The workflow run has already ended", "Reload the page; the workflow run has ended.");
        return wf;
    }

    private List<Map<String, Object>> openTasksOf(String wfId) {
        return db.query("SELECT * FROM human_tasks WHERE status IN ('open', 'claimed') AND kind <> 'notification' AND (workflow_run_id = ? OR workflow_run_id IN (SELECT id FROM workflow_runs WHERE parent_id = ?))", wfId, wfId);
    }

    /** Remind (Run > Workflow runs, 26 Nov 2026): tells the people with the run's open tasks again. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> remind(User by, String wfId, String ip) {
        going(by, wfId, "remind people of workflow tasks");
        List<Map<String, Object>> tasks = openTasksOf(wfId);
        if (tasks.isEmpty()) throw new OoaException("CR.CONFLICT", "No one has an open task in this run", "Remind works while the run waits for a person.");
        for (Map<String, Object> t : tasks)
            ctx.notifyTaskCreated((String) t.get("id"), (String) t.get("kind"), "Reminder: " + t.get("title"), t.get("claimed_by") != null ? "user:" + t.get("claimed_by") : (String) t.get("assignee"), null,
                    t.get("data") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) t.get("data")));
        record(wfId, "reminded", Map.of("by", by.username(), "tasks", tasks.size()));
        ctx.audit(by.username(), "workflow.remind", "workflow", wfId, Map.of("tasks", tasks.size()), "ok", ip);
        return Map.of("ok", true, "tasks", tasks.size());
    }

    /** Reassign: the run's open tasks go to someone else (user:, role:, a list), as if newly given. */
    public Map<String, Object> reassign(User by, String wfId, String to, String ip) {
        Map<String, Object> wf = going(by, wfId, "reassign workflow tasks");
        if (to == null || to.isBlank()) throw new OoaException("CR.BAD_INPUT", "Choose who gets the task");
        String who = resolveAssignee(to.trim(), wf);
        List<Map<String, Object>> tasks = openTasksOf(wfId);
        if (tasks.isEmpty()) throw new OoaException("CR.CONFLICT", "No one has an open task in this run", "Reassign works while the run waits for a person.");
        for (Map<String, Object> t : tasks) {
            db.update("UPDATE human_tasks SET assignee = ?, status = 'open', claimed_by = NULL WHERE id = ?", who, t.get("id"));
            ctx.notifyTaskCreated((String) t.get("id"), (String) t.get("kind"), String.valueOf(t.get("title")), who, null, Map.of());
        }
        record(wfId, "reassigned", Map.of("by", by.username(), "to", who));
        ctx.audit(by.username(), "workflow.reassign", "workflow", wfId, Map.of("to", who), "ok", ip);
        return Map.of("ok", true, "tasks", tasks.size());
    }

    /**
     * Skip step: the run leaves the step it waits on (its open tasks are withdrawn, its agent run stopped, its
     * branches cancelled) and goes on to the next one, with the reason in its history and the audit trail.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> skip(User by, String wfId, String reason, String ip) {
        Map<String, Object> wf = going(by, wfId, "skip workflow steps");
        if (reason == null || reason.isBlank()) throw new OoaException("CR.BAD_INPUT", "Say why the step is skipped");
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        if (data.get("_run") instanceof String rid) db.update("UPDATE runs SET cancel_requested = TRUE WHERE id = ? AND status IN ('queued', 'running')", rid);
        db.update("UPDATE human_tasks SET status = 'cancelled' WHERE workflow_run_id = ? AND status IN ('open', 'claimed') AND kind <> 'notification'", wfId);
        if (data.get("_children") instanceof List<?> kids) for (Object k : kids) stopChild(String.valueOf(k), "the step was skipped");
        String stage = String.valueOf(wf.get("stage"));
        db.update("UPDATE workflow_runs SET status = 'waiting' WHERE id = ?", wfId);
        resume(wfId, stage, null, "skipped", Map.of("by", by.username(), "reason", reason.trim()), null);
        ctx.audit(by.username(), "workflow.skip", "workflow", wfId, Map.of("stage", stage, "reason", reason.trim()), "ok", ip);
        return Map.of("ok", true);
    }

    @SuppressWarnings("unchecked")
    private void record(String wfId, String event, Map<String, Object> detail) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
        List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
        history(wf, h, String.valueOf(wf.get("stage")), event, detail);
        db.update("UPDATE workflow_runs SET history = ?, updated = ? WHERE id = ?", JsonLite.write(h), now(), wfId);
    }

    // ===================== its tasks, in the kernel's Task inbox =====================

    /**
     * A workflow task's workflow (its name), the run's reference and the stage bar, as AA's task list shows them; for
     * an approval its own button labels and whether a rejection needs a reason.
     */
    public Map<String, Object> describe(Map<String, Object> task, Map<Object, Map<String, Object>> runs) {
        Map<String, Object> wf = runs.computeIfAbsent(task.get("workflow_run_id"), k -> db.one("SELECT path, version, reference, branch FROM workflow_runs WHERE id = ?", k));
        if (wf == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        String path = String.valueOf(wf.get("path"));
        m.put("workflow", path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.[a-z]+\\.json$", ""));
        m.put("reference", wf.get("reference"));
        m.put("stages", stageBar(wf));   // G48: the workflow's stages, for the stage bar above the task
        if ("approval".equals(task.get("kind"))) {
            Map<String, Object> s = stepOf(task);
            if (s != null) {
                if (s.get("approve_label") instanceof String a && !a.isBlank()) m.put("approve_label", a);
                if (s.get("reject_label") instanceof String r && !r.isBlank()) m.put("reject_label", r);
                if (Boolean.TRUE.equals(s.get("reason_required"))) m.put("reason_required", true);
            }
        }
        return m;
    }

    private Map<String, Object> stepOf(Map<String, Object> task) {
        Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", task.get("workflow_run_id"));
        try { return wf == null ? null : find(stages(definition(wf)), String.valueOf(task.get("stage"))); } catch (RuntimeException e) { return null; }
    }

    /** The approval stage's on_reject: end (the default), continue, or a stage id. */
    public String onReject(Map<String, Object> task) {
        Map<String, Object> s = stepOf(task);
        return s == null ? "end" : String.valueOf(s.getOrDefault("on_reject", "end"));
    }

    /** Before a workflow task is done: a rejection that needs a reason has one; no one approves the same step twice. */
    @SuppressWarnings("unchecked")
    public void checkComplete(User u, Map<String, Object> task, String outcome, String comment) {
        if (!"approval".equals(task.get("kind"))) return;
        Map<String, Object> s = stepOf(task);
        if (s == null) return;
        if ("rejected".equals(outcome) && Boolean.TRUE.equals(s.get("reason_required")) && (comment == null || comment.isBlank()))
            throw new OoaException("CR.BAD_INPUT", "Say why you reject it", "This approval asks for a reason when rejecting.");
        Map<String, Object> wf = db.one("SELECT data FROM workflow_runs WHERE id = ?", task.get("workflow_run_id"));
        Map<String, Object> data = wf == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
        if (data.get("_approvals") instanceof Map<?, ?> a && a.get(task.get("stage")) instanceof List<?> who && who.contains(u.username()))
            throw new OoaException("CR.CONFLICT", "You already approved this step", "Another approver is needed.");
    }

    /**
     * The task is done. An approval that needs more than one approver (a number, or all the people named) waits for
     * the next: each approval is kept, and the task is given again to the others until enough have approved. A
     * rejection ends it at once.
     */
    @SuppressWarnings("unchecked")
    public void completed(Map<String, Object> task, String event, Map<String, Object> result, String by, String forcedNext) {
        String wfId = (String) task.get("workflow_run_id"), stage = String.valueOf(task.get("stage"));
        if ("approved".equals(event)) {
            Map<String, Object> s = stepOf(task);
            int needed = s == null ? 1 : s.get("approvals_needed") instanceof Number n ? Math.max(1, n.intValue()) : "all".equals(s.get("approvals_needed")) ? users(String.valueOf(s.get("assignee"))).size() : 1;
            if (needed > 1) {
                Map<String, Object> wf = db.one("SELECT * FROM workflow_runs WHERE id = ?", wfId);
                Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) wf.get("data"));
                Map<String, Object> approvals = data.get("_approvals") instanceof Map<?, ?> a ? new LinkedHashMap<>((Map<String, Object>) a) : new LinkedHashMap<>();
                List<Object> who = approvals.get(stage) instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
                if (!who.contains(by)) who.add(by);
                approvals.put(stage, who);
                data.put("_approvals", approvals);
                if (who.size() < needed) {
                    List<Object> h = new ArrayList<>((List<Object>) JsonLite.parse((String) wf.get("history")));
                    history(wf, h, stage, "approved_partly", Map.of("by", by, "approvals", who.size(), "needed", needed));
                    String assignee = String.valueOf(task.get("assignee"));
                    if ("all".equals(s.get("approvals_needed"))) {   // the ones still to approve
                        List<String> left = new ArrayList<>();
                        for (String u : users(String.valueOf(s.get("assignee")))) if (!who.contains(u)) left.add("user:" + u);
                        assignee = String.join(",", left);
                    }
                    createTask(wf, definition(wf), s, data, h, assignee);
                    save(wfId, "waiting", stage, data, h);
                    return;
                }
                Map<String, Object> full = new LinkedHashMap<>(result);
                full.put("approvers", who);
                approvals.remove(stage);
                data.put("_approvals", approvals);
                db.update("UPDATE workflow_runs SET data = ? WHERE id = ?", JsonLite.write(data), wfId);
                resume(wfId, stage, full, event, Map.of("task_id", String.valueOf(task.get("id")), "by", by, "approvers", who), forcedNext);
                return;
            }
        }
        resume(wfId, stage, result, event, Map.of("task_id", String.valueOf(task.get("id")), "by", by), forcedNext);
    }
}
