package com.oneofficeautomation.modules.testing;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.util.Map;

/**
 * Agent testing, a feature module of One CommandCenter (docs/architecture/13-modules.md): test sets of an agent (Build
 * > Agent tests, /api/test-sets, also used by the CI plug-ins with an API key), run on a runner and judged when their
 * runs end. Its tables are in the kernel's frozen baseline.
 */
public final class TestingModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "testing"; }
    public String name() { return "Agent testing"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        TestSets sets = new TestSets(ctx);
        ctx.provide(TestSets.class, sets);
        // /api/test-sets: GET (?bot), POST; /{id}: PUT, DELETE; /{id}/run: POST; /{id}/history: GET; /runs/{run}: GET
        ctx.route("test-sets", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("sets", sets.list(u, ctx.query(ex).get("bot")));
            else if (m.equals("POST") && seg.length == 0) out = sets.save(u, null, ctx.jsonBody(ex), ip);
            else if (m.equals("PUT") && seg.length == 1) out = sets.save(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 1) { sets.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("run")) {
                Map<String, Object> b = ctx.jsonBody(ex);
                out = sets.run(u, seg[0], (String) b.get("device_id"), (String) b.get("pool_id"), b.get("version") == null ? null : String.valueOf(b.get("version")), ip);
            }
            else if (m.equals("GET") && seg.length == 2 && seg[1].equals("history")) out = Map.of("runs", sets.history(u, seg[0]));
            else if (m.equals("GET") && seg.length == 2 && seg[0].equals("runs")) out = sets.testRun(u, seg[1]);
            else throw ModuleContext.notFound("The route /api/test-sets/" + route);
            ctx.sendJson(ex, 200, out);
        });
        // a test case's run ended: judge it against its expectations
        ctx.onRunEnded((run, status, result) -> {
            try { sets.onRunFinished(run, status, result); }
            catch (RuntimeException e) { ctx.audit("system", "testset.judge", "run", (String) run.get("id"), String.valueOf(e.getMessage()), "failed", null); }
        });
    }
}
