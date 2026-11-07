package com.oneofficeautomation.modules.insights;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Insights, a feature module of One CommandCenter (docs/architecture/13-modules.md): Operational Insights
 * (/api/analytics, and its data API for Power BI or a warehouse), dashboards (/api/dashboards, KPIs from successful
 * runs, mailed on a schedule) and alerts (/api/alerts, /api/alert-rules, checked on the clock, raised by agents with
 * raise_alert). Events out, metrics and syslog stay the kernel's; so do the savings settings (analytics/goal,
 * analytics/queue-savings). Its tables are in the kernel's frozen baseline.
 */
public final class InsightsModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "insights"; }
    public String name() { return "Insights"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        Analytics analytics = new Analytics(ctx);
        Dashboards dashboards = new Dashboards(ctx);
        Alerts alerts = new Alerts(ctx);
        ctx.provide(Analytics.class, analytics);
        ctx.provide(Dashboards.class, dashboards);
        ctx.provide(Alerts.class, alerts);

        // a successful run's outputs feed the KPIs; alert rules and mailed dashboards on the clock (every 15 s, the leader)
        ctx.onRunEnded((run, status, result) -> {
            if (!status.equals("success")) return;
            try { dashboards.recordKpis(run, result.get("outputs")); }
            catch (RuntimeException e) { ctx.audit("system", "kpi.record", "run", (String) run.get("id"), String.valueOf(e.getMessage()), "failed", null); }
        });
        ctx.every(Duration.ofSeconds(5), Duration.ofSeconds(15), alerts::checkIfDue);
        ctx.every(Duration.ofSeconds(5), Duration.ofSeconds(15), dashboards::mailIfDue);
        // an agent raises an alert (the Jobs and alerts toolkit)
        ctx.runRequest("raise_alert", (run, as, args, actor) ->
                alerts.raiseFromRun(run, (String) args.get("message"), (String) args.get("severity"), (String) args.get("subject")));

        ctx.route("dashboards", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("dashboards", dashboards.list(u));
            else if (m.equals("GET") && route.equals("kpis")) out = Map.of("kpis", dashboards.kpis(u));
            // e-mailed on a schedule: dashboards/{id}/mail lists and adds; dashboards/mail/{schedule} stops; .../send sends now
            else if (m.equals("GET") && seg.length == 2 && seg[1].equals("mail")) out = Map.of("schedules", dashboards.mailList(u, seg[0]));
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("mail")) out = dashboards.mailSave(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 2 && seg[0].equals("mail")) { dashboards.mailDelete(u, seg[1], ip); out = Map.of("ok", true); }
            else if (m.equals("POST") && seg.length == 3 && seg[0].equals("mail") && seg[2].equals("send")) out = dashboards.mailNow(u, seg[1], ip);
            else if (m.equals("POST") && seg.length == 0) out = dashboards.save(u, null, ctx.jsonBody(ex), ip);
            else if (m.equals("PUT") && seg.length == 1) out = dashboards.save(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 1) { dashboards.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else if (m.equals("GET") && seg.length == 2 && seg[1].equals("data")) out = dashboards.data(u, seg[0], ctx.query(ex).get("tz"));
            else throw ModuleContext.notFound("The route /api/dashboards/" + route);
            ctx.sendJson(ex, 200, out);
        });
        ctx.route("alerts", (ex, u, m, route, ip) -> {
            Object out;
            if (m.equals("GET") && route.isEmpty()) out = alerts.list(u, ctx.query(ex));
            else if (m.equals("POST") && route.equals("acknowledge")) out = alerts.acknowledge(u, (List<?>) ctx.jsonBody(ex).getOrDefault("ids", List.of()), ip);
            else throw ModuleContext.notFound("The route /api/alerts/" + route);
            ctx.sendJson(ex, 200, out);
        });
        ctx.route("alert-rules", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("rules", alerts.rules(u));
            else if (m.equals("POST") && seg.length == 0) out = alerts.saveRule(u, null, ctx.jsonBody(ex), ip);
            else if (m.equals("PUT") && seg.length == 1) out = alerts.saveRule(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("PATCH") && seg.length == 1) { alerts.setEnabled(u, seg[0], Boolean.TRUE.equals(ctx.jsonBody(ex).get("enabled")), ip); out = Map.of("ok", true); }
            else if (m.equals("DELETE") && seg.length == 1) { alerts.deleteRule(u, seg[0], ip); out = Map.of("ok", true); }
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("test")) out = alerts.test(u, seg[0], ip);
            else throw ModuleContext.notFound("The route /api/alert-rules/" + route);
            ctx.sendJson(ex, 200, out);
        });
        // Operational Insights (the kernel keeps analytics/goal and analytics/queue-savings, its savings settings)
        ctx.route("analytics", (ex, u, m, route, ip) -> {
            Map<String, String> q = ctx.query(ex);
            if (!m.equals("GET")) throw ModuleContext.notFound("The route /api/analytics/" + route);
            if (route.equals("export")) {
                String report = q.getOrDefault("report", "");
                ctx.sendFile(ex, analytics.csv(u, report, q).getBytes(StandardCharsets.UTF_8), "text/csv; charset=utf-8", "operations-" + report + ".csv");
                return;
            }
            Object out = switch (route) {
                case "" -> analytics.overview(u, q);
                case "agent" -> analytics.agent(u, q.get("path"), q);
                case "capacity" -> analytics.capacity(u, q);
                case "audit" -> analytics.audit(u, q);
                case "business-agents" -> analytics.businessAgents(u, q);
                // the analytics data API: pages of rows ({items, next}) for Power BI, Tableau or a data warehouse
                case "data/runs" -> analytics.dataRuns(u, q);
                case "data/kpis" -> analytics.dataKpis(u, q);
                case "data/audit" -> analytics.dataAudit(u, q);
                case "queues" -> analytics.queues(u, q);
                case "value" -> analytics.value(u, q);
                case "variables" -> analytics.variables(u, q.get("path"), q);
                default -> throw ModuleContext.notFound("The route /api/analytics/" + route);
            };
            ctx.sendJson(ex, 200, out);
        });
    }
}
