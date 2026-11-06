package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunSuspension;
import com.oneofficeautomation.sdk.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

/**
 * Long-running agents (gap G36): an agent suspends until a time, a Task inbox task, a work item, another run or a call to
 * a one-time callback URL, and carries on from the same step in a new run, as UiPath's long-running workflows (Wait for
 * ... and Resume, Resume after delay) and the start / wait / continue pattern AA and Blue Prism builders make by hand.
 * The run ends with status Suspended while it waits, so its runner is free; One CommandCenter keeps the agent's values
 * and starts the new run when the wait is over (see RunSuspension and the control room's RunWaits).
 * <p>What is not carried: open sessions (a browser, a workbook, a file...) are refused at the wait, and Window,
 * UI element, Image, Binary and Credential variables are left out (credential inputs are read from the vault again).
 * The step must be in the agent's main steps, not inside a loop, a Parallel block, a Catch or a Finally.
 */
@OoaPackage(id = "ooa.longrunning", name = "Long-running agents", version = "1.0.0",
        description = "Suspend an agent until a time, a task, a work item, another run or a callback URL is called, and carry on from the same step; only from One CommandCenter.")
public final class LongRunningPackage {
    static final String WAIT_OUTPUTS = "resumed_by: what ended the wait (time, task, queue_item, job, callback, admin or timeout).";

    @SuppressWarnings("unchecked")
    private static Function<Map<String, Object>, Map<String, Object>> commandCenter(ActionContext ctx) {
        try { return ctx.service("command_center", Function.class); }
        catch (OoaException e) {
            throw new ActionException("LONGRUN.NOT_AVAILABLE", "An agent can only be suspended when it runs from One CommandCenter",
                    "Check the agent in and run it from One CommandCenter; in Studio or on the command line there is nothing to resume it.");
        }
    }

    private static Map<String, Object> ask(ActionContext ctx, String call, Map<String, Object> args) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("call", call);
        req.put("args", args);
        return commandCenter(ctx).apply(req);
    }

    private static String id(String v, String what, String where) {
        if (v == null || v.isBlank()) throw new ActionException("LONGRUN.NO_ID", "Give the " + what, where);
        return v.strip();
    }

    /**
     * The heart of every wait. In a resumed run, at its start step: returns what ended the wait. Otherwise: checks the run
     * can stop here, asks One CommandCenter to keep the wait and the values, and ends the run as suspended.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> suspend(ActionContext ctx, String kind, String ref, Double timeoutSeconds, String onTimeout, String pool, String what) {
        RunSuspension rs = ctx.service(RunSuspension.SERVICE, RunSuspension.class);
        Map<String, Object> answer = rs.resumed(ctx.stepId());
        if (answer != null) {
            boolean timedOut = Boolean.TRUE.equals(answer.get("timed_out"));
            if (timedOut && !"continue".equals(onTimeout))
                throw new ActionException("LONGRUN.TIMEOUT", "Waited for " + what + " until the timeout",
                        "Catch LONGRUN.TIMEOUT in a Try around this step, or set On timeout to continue and check Timed out.");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("resumed_by", String.valueOf(answer.getOrDefault("resumed_by", kind)));
            out.put("timed_out", timedOut);
            out.put("result", answer.get("result") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>());
            ctx.info(timedOut ? "Resumed: the wait for " + what + " timed out" : "Resumed: " + what + " (" + out.get("resumed_by") + ")");
            return out;
        }
        Function<Map<String, Object>, Map<String, Object>> cc = commandCenter(ctx);
        Map<String, Object> saved = rs.prepare(ctx.stepId());
        if (saved.get("left_out") instanceof List<?> l && !l.isEmpty())
            ctx.warn("Not carried to the resumed run (they keep their default values): " + String.join(", ", (List<String>) l));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("kind", kind);
        if (ref != null) args.put("ref", ref);
        if (timeoutSeconds != null && timeoutSeconds > 0) args.put("timeout_seconds", timeoutSeconds);
        if (pool != null && !pool.isBlank()) args.put("pool", pool.strip());
        args.put("step_id", ctx.stepId());
        args.put("values", saved.get("values"));
        Map<String, Object> r = cc.apply(Map.of("call", "suspend", "args", args));
        ctx.info("Suspended until " + what + (timeoutSeconds != null && timeoutSeconds > 0 ? " (at most " + Math.round(timeoutSeconds) + " s)" : "")
                + "; wait " + r.get("wait_id") + ". The runner is free; a new run carries on from this step.");
        throw new OoaException(RunSuspension.CODE, "Suspended: waiting for " + what + " (wait " + r.get("wait_id") + ")");
    }

    // ---------- making things to wait for ----------

    @Action(id = "create_callback", name = "Create callback URL", category = "Long-running",
            description = "Make a one-time web address that another system calls (HTTP POST with a JSON body) when its work is done. Give the address to that system, then use Wait for callback. "
                    + "The address is shown here only once and works once; One CommandCenter keeps only a fingerprint of it. Needs the Public address in Settings.")
    @Output(name = "url", type = OoaType.STRING, description = "The address to POST to: https://ONE-COMMANDCENTER/api/callbacks/TOKEN. Treat it as a secret: whoever has it can resume the run.")
    @Output(name = "callback_id", type = OoaType.STRING, description = "Give it to Wait for callback.")
    public Map<String, Object> createCallback(ActionContext ctx) {
        Map<String, Object> r = ask(ctx, "create_callback", Map.of());
        return Map.of("url", String.valueOf(r.get("url")), "callback_id", String.valueOf(r.get("callback_id")));
    }

    @Action(id = "create_task", name = "Create task", category = "Long-running",
            description = "Put an approval or a form in the Task inbox for people to do, then use Wait for task to suspend until it is done. Assign it to user:NAME or role:NAME (several separated by commas).",
            panel = "kind, title, assignee | description, form, data, due_hours")
    @Output(name = "task_id", type = OoaType.STRING, description = "Give it to Wait for task.")
    public Map<String, Object> createTask(ActionContext ctx,
            @Input(name = "kind", type = OoaType.STRING, required = false, defaultValue = "\"approval\"", enumValues = {"approval", "form"},
                    description = "approval: approve or reject, with a comment; form: fill in the form's fields.") String kind,
            @Input(name = "title", type = OoaType.STRING, example = "Approve invoice 4711 (12,400 EUR)", description = "What the task is, as the inbox lists it.") String title,
            @Input(name = "assignee", type = OoaType.STRING, example = "role:Finance approvers", description = "Who may do it: user:NAME or role:NAME, several separated by commas.") String assignee,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "More about the task.") String description,
            @Input(name = "form", type = OoaType.DICTIONARY, required = false, visibleWhen = "kind == 'form'",
                    description = "For a form: {\"fields\": [{\"id\": \"amount\", \"label\": \"Amount\", \"type\": \"number\", \"required\": true}]}, as in workflow forms.") Map<String, Object> form,
            @Input(name = "data", type = OoaType.DICTIONARY, required = false, description = "Details shown with the task.") Map<String, Object> data,
            @Input(name = "due_hours", type = OoaType.NUMBER, required = false, min = 0, description = "Overdue after this many hours; never when empty.") Double dueHours) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("kind", kind == null || kind.isBlank() ? "approval" : kind);
        args.put("title", title);
        args.put("assignee", assignee);
        if (description != null) args.put("description", description);
        if (form != null) args.put("form", form);
        if (data != null) args.put("data", data);
        if (dueHours != null && dueHours > 0) args.put("due_hours", dueHours);
        return Map.of("task_id", String.valueOf(ask(ctx, "create_task", args).get("task_id")));
    }

    // ---------- waits ----------

    @Action(id = "suspend_until", name = "Suspend until", category = "Long-running", timeoutSeconds = 120,
            description = "Suspend the agent until something happens, then carry on from this step in a new run with the same values. The run shows as Suspended and its runner is free while it waits. "
                    + "Not inside a loop, a Parallel block, a Catch or a Finally; close browsers, workbooks and files first.",
            panel = "kind, ref | timeout_seconds, on_timeout, pool")
    @Output(name = "resumed_by", type = OoaType.STRING, description = WAIT_OUTPUTS)
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when the timeout ended the wait (with On timeout set to continue).")
    @Output(name = "result", type = OoaType.DICTIONARY, description = "What ended the wait: the callback's JSON body; a task's status, outcome, result and completed_by; a work item's status, result and error; a run's status, outputs and error. Empty for a time or a timeout.")
    public Map<String, Object> suspendUntil(ActionContext ctx,
            @Input(name = "kind", type = OoaType.STRING, enumValues = {"time", "task", "queue_item", "job", "callback"},
                    description = "What to wait for: time, a Task inbox task, a work item, another run (job), or a callback URL being called.") String kind,
            @Input(name = "ref", type = OoaType.STRING, example = "task_4b1d...",
                    description = "time: seconds from now or a date and time with its offset (2026-11-07T09:00:00+05:30); task: the task id; queue_item: the work item id; job: the run id; callback: the callback id.") String ref,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, min = 1, max = 31536000, description = "Stop waiting after this many seconds; wait as long as it takes when empty.") Double timeout,
            @Input(name = "on_timeout", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"},
                    description = "error: the step fails with LONGRUN.TIMEOUT (catch it in a Try); continue: carry on with Timed out true.") String onTimeout,
            @Input(name = "pool", type = OoaType.STRING, required = false, description = "Carry on on any runner of this pool (name or id); on the same runner when empty.") String pool) {
        String k = id(kind, "kind of wait", "time, task, queue_item, job or callback.");
        return suspend(ctx, k, k.equals("time") ? id(ref, "time", "Seconds from now, or a date and time.") : id(ref, k + " id", "Take it from the step that made it."), timeout, onTimeout, pool, k + " " + ref);
    }

    @Action(id = "resume_after", name = "Resume after delay", category = "Long-running", timeoutSeconds = 120,
            description = "Suspend the agent for a while, or until a date and time, without holding the runner; then carry on from this step. For short pauses use Delay instead.",
            panel = "delay_seconds, until | pool")
    @Output(name = "resumed_by", type = OoaType.STRING, description = WAIT_OUTPUTS)
    public Map<String, Object> resumeAfter(ActionContext ctx,
            @Input(name = "delay_seconds", type = OoaType.NUMBER, required = false, min = 1, max = 31536000, description = "How long to wait, in seconds.") Double delay,
            @Input(name = "until", type = OoaType.STRING, required = false, example = "2026-11-09T08:00",
                    description = "Or: when to carry on, a date and time (in the runner's time zone unless it has an offset).") String until,
            @Input(name = "pool", type = OoaType.STRING, required = false, description = "Carry on on any runner of this pool; on the same runner when empty.") String pool) {
        String ref;
        if (until != null && !until.isBlank()) ref = instant(until.strip()).toString();
        else if (delay != null && delay >= 1) ref = String.valueOf(Math.round(delay));
        else throw new ActionException("LONGRUN.NO_TIME", "Say how long to wait, or until when");
        Map<String, Object> r = suspend(ctx, "time", ref, null, "error", pool, until != null && !until.isBlank() ? until.strip() : Math.round(delay) + " s");
        return Map.of("resumed_by", r.get("resumed_by"));
    }

    static Instant instant(String s) {
        try { return OffsetDateTime.parse(s).toInstant(); } catch (RuntimeException ignored) { }
        try { return Instant.parse(s); } catch (RuntimeException ignored) { }
        try { return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant(); }
        catch (RuntimeException e) { throw new ActionException("LONGRUN.BAD_TIME", "'" + s + "' is not a date and time", "Write it as 2026-11-09T08:00 or 2026-11-09T08:00:00+05:30."); }
    }

    @Action(id = "wait_for_callback", name = "Wait for callback", category = "Long-running", timeoutSeconds = 120,
            description = "Suspend until the callback URL made by Create callback URL is called; its JSON body comes back as Result. A call that came before this step is not lost.",
            panel = "callback_id | timeout_seconds, on_timeout, pool")
    @Output(name = "resumed_by", type = OoaType.STRING, description = WAIT_OUTPUTS)
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when the timeout ended the wait.")
    @Output(name = "result", type = OoaType.DICTIONARY, description = "The JSON body the caller posted (a value that is not an object comes as {\"value\": ...}).")
    public Map<String, Object> waitForCallback(ActionContext ctx,
            @Input(name = "callback_id", type = OoaType.STRING, description = "From Create callback URL.") String callbackId,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, min = 1, max = 31536000, description = "Stop waiting after this many seconds; wait as long as it takes when empty.") Double timeout,
            @Input(name = "on_timeout", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"}, description = "error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true.") String onTimeout,
            @Input(name = "pool", type = OoaType.STRING, required = false, description = "Carry on on any runner of this pool; on the same runner when empty.") String pool) {
        return suspend(ctx, "callback", id(callbackId, "callback id", "Take it from Create callback URL."), timeout, onTimeout, pool, "the callback");
    }

    @Action(id = "wait_for_task", name = "Wait for task", category = "Long-running", timeoutSeconds = 120,
            description = "Suspend until a Task inbox task (an approval or a form, from Create task or a workflow) is completed or cancelled. The run's user must be allowed to see the task, or the task was made by this run.",
            panel = "task_id | timeout_seconds, on_timeout, pool")
    @Output(name = "resumed_by", type = OoaType.STRING, description = WAIT_OUTPUTS)
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when the timeout ended the wait.")
    @Output(name = "result", type = OoaType.DICTIONARY, description = "task_id, status (completed or cancelled), outcome (approved, rejected, submitted), result (the answers, or approved/approver/comment) and completed_by.")
    public Map<String, Object> waitForTask(ActionContext ctx,
            @Input(name = "task_id", type = OoaType.STRING, description = "From Create task.") String taskId,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, min = 1, max = 31536000, description = "Stop waiting after this many seconds; wait as long as it takes when empty.") Double timeout,
            @Input(name = "on_timeout", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"}, description = "error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true.") String onTimeout,
            @Input(name = "pool", type = OoaType.STRING, required = false, description = "Carry on on any runner of this pool; on the same runner when empty.") String pool) {
        String id = id(taskId, "task id", "Take it from Create task.");
        return suspend(ctx, "task", id, timeout, onTimeout, pool, "task " + id);
    }

    @Action(id = "wait_for_item", name = "Wait for work item", category = "Long-running", timeoutSeconds = 120,
            description = "Suspend until a work item in a work queue ends as success or failed (for example one this agent added for another agent to process).",
            panel = "item_id | timeout_seconds, on_timeout, pool")
    @Output(name = "resumed_by", type = OoaType.STRING, description = WAIT_OUTPUTS)
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when the timeout ended the wait.")
    @Output(name = "result", type = OoaType.DICTIONARY, description = "item_id, status (success or failed), result and error.")
    public Map<String, Object> waitForItem(ActionContext ctx,
            @Input(name = "item_id", type = OoaType.STRING, description = "The work item's id, from Add work item.") String itemId,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, min = 1, max = 31536000, description = "Stop waiting after this many seconds; wait as long as it takes when empty.") Double timeout,
            @Input(name = "on_timeout", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"}, description = "error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true.") String onTimeout,
            @Input(name = "pool", type = OoaType.STRING, required = false, description = "Carry on on any runner of this pool; on the same runner when empty.") String pool) {
        String id = id(itemId, "work item id", "Take it from the step that added the item.");
        return suspend(ctx, "queue_item", id, timeout, onTimeout, pool, "work item " + id);
    }

    @Action(id = "wait_for_job", name = "Wait for job", category = "Long-running", timeoutSeconds = 120,
            description = "Suspend until another run ends (success, failed or stopped). A run that is itself suspended is followed to the run that carries it on.",
            panel = "job_id | timeout_seconds, on_timeout, pool")
    @Output(name = "resumed_by", type = OoaType.STRING, description = WAIT_OUTPUTS)
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when the timeout ended the wait.")
    @Output(name = "result", type = OoaType.DICTIONARY, description = "job_id, status (success, failed or stopped), outputs and error.")
    public Map<String, Object> waitForJob(ActionContext ctx,
            @Input(name = "job_id", type = OoaType.STRING, description = "The run's id.") String jobId,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, min = 1, max = 31536000, description = "Stop waiting after this many seconds; wait as long as it takes when empty.") Double timeout,
            @Input(name = "on_timeout", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"}, description = "error: fail with LONGRUN.TIMEOUT; continue: carry on with Timed out true.") String onTimeout,
            @Input(name = "pool", type = OoaType.STRING, required = false, description = "Carry on on any runner of this pool; on the same runner when empty.") String pool) {
        String id = id(jobId, "run id", "Take it from List jobs or a Run agent step.");
        if (id.equals(ctx.runId())) throw new ActionException("LONGRUN.SELF", "A run cannot wait for itself");
        return suspend(ctx, "job", id, timeout, onTimeout, pool, "run " + id);
    }
}
