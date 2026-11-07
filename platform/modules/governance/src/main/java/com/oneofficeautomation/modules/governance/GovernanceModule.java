package com.oneofficeautomation.modules.governance;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Governance, a feature module of One CommandCenter (docs/architecture/13-modules.md): code analysis (rules checked at
 * check-in and on demand, stored reports), folder policies (Administration > Policies: code analysis rules and AI
 * guardrails per folder) and production approval (a version goes live only after someone else approves). It plugs
 * into the kernel's check-in (CheckinListener), production gate (ProductionGate) and run dispatch (Guardrails). Its
 * tables are in the kernel's frozen baseline; the setting production_approval stays the kernel's.
 */
public final class GovernanceModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "governance"; }
    public String name() { return "Governance"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        CodeAnalysis analysis = new CodeAnalysis(ctx);
        Policies policies = new Policies(ctx, analysis);
        analysis.policies = policies;
        ProductionApproval approvals = new ProductionApproval(ctx);
        ctx.provide(CodeAnalysis.class, analysis);
        ctx.provide(Policies.class, policies);
        ctx.provide(ProductionApproval.class, approvals);

        // check-in: the folder's rules may block it; the new version's report is stored against today's policy
        ctx.checkinListener(new ModuleContext.CheckinListener() {
            public void before(String path, Map<String, Object> flow) { analysis.atCheckin(path, flow); }
            public void checkedIn(User by, String path, int version, Map<String, Object> flow, String comment) {
                if (CodeAnalysis.analysable(path)) analysis.store(path, version, analysis.check(flow, analysis.policyFor(path)), by.username());
            }
        });
        // with approval before production, making a version live becomes a request someone else decides
        ctx.productionGate(new ModuleContext.ProductionGate() {
            public boolean required() { return approvals.enabled(); }
            public Map<String, Object> request(User by, String path, int version, String comment, String ip) { return approvals.request(by, path, version, comment, ip); }
        });
        // each run gets its folder's AI guardrails
        ctx.guardrails(policies::guardrailsFor);

        // Administration > Policies: /api/policies
        ctx.route("policies", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("policies", policies.list(u));
            else if (m.equals("GET") && seg.length == 1) out = policies.get(u, seg[0]);
            else if (m.equals("GET") && seg.length == 2 && seg[1].equals("stopped")) out = policies.stopped(u, seg[0], ctx.query(ex));
            else if (m.equals("POST") && seg.length == 0) out = policies.save(u, null, ctx.jsonBody(ex), ip);
            else if (m.equals("PUT") && seg.length == 1) out = policies.save(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 1) { policies.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else throw ModuleContext.notFound("The route /api/policies/" + route);
            ctx.sendJson(ex, 200, out);
        });

        // code analysis, under the repository's routes
        ctx.route("repo/analyze", (ex, u, m, route, ip) -> {
            if (!m.equals("POST") || !route.isEmpty()) throw ModuleContext.notFound("The route /api/repo/analyze/" + route);
            Map<String, Object> b = ctx.jsonBody(ex);
            @SuppressWarnings("unchecked")
            Object out = b.get("flow") instanceof Map<?, ?> fm ? analysis.analyzeFlow(u, (Map<String, Object>) fm) : analysis.analyze(u, (String) b.get("path"), b.getOrDefault("version", "latest"));
            ctx.sendJson(ex, 200, out);
        });
        ctx.route("repo/analysis", (ex, u, m, route, ip) -> {
            Map<String, String> q = ctx.query(ex);
            if (m.equals("GET") && route.isEmpty()) ctx.sendJson(ex, 200, analysis.stored(u, q.get("path"), q.getOrDefault("version", "latest")));
            else if (m.equals("GET") && route.equals("report"))
                ctx.sendFile(ex, analysis.repositoryCsv(u).getBytes(StandardCharsets.UTF_8), "text/csv; charset=utf-8", "code-analysis.csv");
            else throw ModuleContext.notFound("The route /api/repo/analysis/" + route);
        });
        ctx.route("repo/policy", (ex, u, m, route, ip) -> {
            if (m.equals("GET") && route.isEmpty()) ctx.sendJson(ex, 200, analysis.policy());
            else if (m.equals("PUT") && route.isEmpty()) ctx.sendJson(ex, 200, analysis.savePolicy(u, ctx.jsonBody(ex), ip));
            else throw ModuleContext.notFound("The route /api/repo/policy/" + route);
        });

        // production approval: /api/repo/production-requests (GET ?status, POST), /{id}/approve|reject|withdraw (POST)
        ctx.route("repo/production-requests", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("requests", approvals.list(u, ctx.query(ex).get("status")), "approval_on", approvals.enabled());
            else if (m.equals("POST") && seg.length == 0) {
                Map<String, Object> b = ctx.jsonBody(ex);
                out = approvals.request(u, (String) b.get("path"), ((Number) b.get("version")).intValue(), (String) b.get("comment"), ip);
            } else if (m.equals("POST") && seg.length == 2 && Set.of("approve", "reject", "withdraw").contains(seg[1])) {
                Map<String, Object> b = ctx.jsonBody(ex);
                out = seg[1].equals("withdraw") ? approvals.withdraw(u, seg[0], ip) : approvals.decide(u, seg[0], seg[1].equals("approve"), (String) b.get("note"), ip);
            } else throw ModuleContext.notFound("The route /api/repo/production-requests/" + route);
            ctx.sendJson(ex, 200, out);
        });
    }

    public static List<String> routes() { return List.of("policies", "repo/analyze", "repo/analysis", "repo/policy", "repo/production-requests"); }
}
