package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * Jobs and alerts (gap G32): an agent asks One CommandCenter about other runs, stops one, or raises an alert to the
 * people an alert rule names, as other RPA tools' Get job, Stop job and Raise alert. One CommandCenter answers as the
 * run's user (the run-as user, else who started it) with that person's permissions, and audits each request, so these
 * steps work only when the agent runs from One CommandCenter.
 */
@OoaPackage(id = "ooa.jobs", name = "Jobs and alerts", version = "1.0.0",
        description = "See and stop other agent runs, and raise alerts to people, from an agent run by One CommandCenter.")
public final class JobsPackage {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ask(ActionContext ctx, String call, Map<String, Object> args) {
        Function<Map<String, Object>, Map<String, Object>> cc;
        try { cc = ctx.service("command_center", Function.class); }
        catch (OoaException e) {
            throw new ActionException("JOBS.NOT_AVAILABLE", "This step works only when the agent runs from One CommandCenter",
                    "Check the agent in and run it from One CommandCenter; in Studio it has no other runs or alert rules to use.");
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("call", call);
        req.put("args", args);
        return cc.apply(req);
    }

    private static String job(String id) {
        if (id == null || id.isBlank()) throw new ActionException("JOBS.NO_ID", "Give the run's id", "Take it from List jobs, from a Run agent step's run id, or from the run's page.");
        return id.strip();
    }

    @Action(id = "get_job", name = "Get job", category = "Jobs", idempotent = true,
            description = "Read the state of another run: running, success, failed or stopped, when it started and ended, and its error. The run's user must be allowed to see runs of that agent.")
    @Output(name = "job", type = OoaType.DICTIONARY, description = "id, bot_path (the agent), status, requested_by, run_as, device_name, created, started, finished, duration_ms, error_code, error_message.")
    @Output(name = "status", type = OoaType.STRING, description = "queued, running, success, failed or stopped.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getJob(ActionContext ctx,
            @Input(name = "job_id", type = OoaType.STRING, description = "The run's id.") String jobId) {
        Map<String, Object> j = ask(ctx, "get_job", Map.of("job_id", job(jobId)));
        return Map.of("job", j, "status", String.valueOf(j.get("status")));
    }

    @Action(id = "list_jobs", name = "List jobs", category = "Jobs", idempotent = true,
            description = "List runs the run's user may see: the active ones (queued or running), the finished ones, or all; of one agent or of every agent; newest first.",
            panel = "status, agent | limit")
    @Output(name = "jobs", type = OoaType.LIST, description = "One record per run, as Get job returns it.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many runs are listed.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> listJobs(ActionContext ctx,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"active\"", enumValues = {"active", "history", "all"},
                    description = "active: queued or running; history: finished; all.") String status,
            @Input(name = "agent", type = OoaType.STRING, required = false, example = "Finance/Invoice intake.flow.json",
                    description = "Only runs of this agent (its path in the repository); every agent when empty.") String agent,
            @Input(name = "limit", type = OoaType.NUMBER, required = false, defaultValue = "50", min = 1, max = 200, description = "At most this many runs.") int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("status", status == null || status.isBlank() ? "active" : status);
        if (agent != null && !agent.isBlank()) args.put("agent", agent.strip());
        args.put("limit", BigDecimal.valueOf(limit));
        List<Object> jobs = (List<Object>) ask(ctx, "list_jobs", args).getOrDefault("jobs", List.of());
        return Map.of("jobs", jobs, "count", BigDecimal.valueOf(jobs.size()));
    }

    @Action(id = "stop_job", name = "Stop job", category = "Jobs",
            description = "Stop another run: a queued run is removed, a running one stops at its next step. Needs the run's user to be allowed to stop runs; an agent stops itself with Stop agent instead.")
    @Output(name = "stopped", type = OoaType.BOOLEAN, description = "True when the run was queued or running and is being stopped; false when it had already ended.")
    @Output(name = "status", type = OoaType.STRING, description = "stopping, or the state it had already ended in.")
    public Map<String, Object> stopJob(ActionContext ctx,
            @Input(name = "job_id", type = OoaType.STRING, description = "The run's id.") String jobId) {
        String id = job(jobId);
        if (id.equals(ctx.runId())) throw new ActionException("JOBS.SELF", "An agent cannot stop its own run this way", "Use the Stop agent step.");
        Map<String, Object> r = ask(ctx, "stop_job", Map.of("job_id", id));
        return Map.of("stopped", Boolean.TRUE.equals(r.get("stopped")), "status", String.valueOf(r.get("status")));
    }

    @Action(id = "raise_alert", name = "Raise alert", category = "Alerts",
            description = "Raise an alert in Operational Insights > Alerts. It goes to the people, e-mail addresses and webhook of the alert rules of kind Raised by an agent (for every agent or this one); "
                    + "with no such rule it is only listed there. It stays open until someone acknowledges it. A run raises at most 20 alerts, and the same subject once.",
            panel = "message, severity | subject")
    @Output(name = "alerts", type = OoaType.NUMBER, description = "How many alerts were raised (one per rule that takes them; 0 when this subject was already raised by this run).")
    @SuppressWarnings("unchecked")
    public Map<String, Object> raiseAlert(ActionContext ctx,
            @Input(name = "message", type = OoaType.STRING, example = "Invoice 4711 from Acme is over the approval limit", description = "What happened and what a person should do.") String message,
            @Input(name = "severity", type = OoaType.STRING, required = false, defaultValue = "\"warning\"", enumValues = {"warning", "critical"},
                    description = "How serious it is; the alert's title says Critical for critical.") String severity,
            @Input(name = "subject", type = OoaType.STRING, required = false, example = "invoice 4711",
                    description = "What the alert is about; the same subject is raised once per run. Every alert is new when empty.") String subject) {
        if (message == null || message.isBlank()) throw new ActionException("ALERT.NO_MESSAGE", "The alert has no message", "Say what happened and what a person should do.");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("message", message);
        args.put("severity", severity == null || severity.isBlank() ? "warning" : severity);
        if (subject != null && !subject.isBlank()) args.put("subject", subject);
        List<Object> raised = (List<Object>) ask(ctx, "raise_alert", args).getOrDefault("alerts", List.of());
        return Map.of("alerts", BigDecimal.valueOf(raised.size()));
    }
}
