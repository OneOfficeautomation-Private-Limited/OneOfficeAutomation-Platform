package com.oneofficeautomation.modules.orchestration;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.time.Duration;
import java.util.Map;

/**
 * Orchestration, a feature module of One CommandCenter (docs/architecture/13-modules.md): the workflow engine.
 * Workflows (Name.process.json) and their forms (Name.form.json) are checked in like agents; the engine runs them stage
 * by stage (/api/workflows), starts agent runs, and puts human tasks and approvals in the kernel's Task inbox, which
 * hands them back when done. Schedules, triggers, queues, environment locks and long-running agents stay the kernel's
 * (run dispatch). Its tables (workflow_runs; human_tasks is the Task inbox's) are in the kernel's frozen baseline.
 */
public final class OrchestrationModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "orchestration"; }
    public String name() { return "Orchestration"; }
    public String version() { return VERSION; }

    @SuppressWarnings("unchecked")
    public void start(ModuleContext ctx) {
        Workflows workflows = new Workflows(ctx);
        ctx.provide(Workflows.class, workflows);
        ctx.workflowTasks(workflows);
        ctx.definitionCheck(".process.json", Workflows::validate);
        ctx.definitionCheck(".form.json", Workflows::validateForm);
        // an agent task finished: its workflow moves on; runs waiting for a runner are retried on the clock (the leader)
        ctx.onRunEnded((run, status, result) -> {
            try { workflows.onRunFinished(run, status, result); }
            catch (RuntimeException e) { ctx.audit("workflow", "workflow.resume", "run", String.valueOf(run.get("id")), String.valueOf(e.getMessage()), "failed", null); }
        });
        ctx.every(Duration.ofSeconds(4), Duration.ofSeconds(5), workflows::tick);

        ctx.route("workflows", (ex, u, m, route, ip) -> {
            Map<String, String> q = ctx.query(ex);
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && route.equals("runs") && q.get("reference") != null) out = workflows.run(u, null, q.get("reference"));
            else if (m.equals("GET") && route.equals("runs")) out = Map.of("runs", workflows.runs(u, q.get("status")));
            else if (m.equals("GET") && seg.length == 2 && seg[0].equals("runs")) out = workflows.run(u, seg[1], null);
            else if (m.equals("GET") && route.equals("form")) out = workflows.startForm(u, q.get("path"));
            else if (m.equals("POST") && route.equals("start")) {
                Map<String, Object> b = ctx.jsonBody(ex);
                out = workflows.start(u, (String) b.get("path"), b.get("data") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : Map.of(), (String) b.get("reference"), ip);
            } else if (m.equals("POST") && seg.length == 3 && seg[0].equals("runs") && seg[2].equals("cancel")) out = workflows.cancel(u, seg[1], ip);
            else throw ModuleContext.notFound("The route /api/workflows/" + route);
            ctx.sendJson(ex, 200, out);
        });
    }
}
