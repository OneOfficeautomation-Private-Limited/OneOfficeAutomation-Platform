package com.oneofficeautomation.modules.integration;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.OoaException;

import java.util.Map;

/**
 * Integration, a feature module of One CommandCenter (docs/architecture/13-modules.md): Agent APIs (agents behind their
 * own URL and token: /api/apitasks, run with the task token before sign-in), the MCP server for AI assistants (/api/mcp,
 * with every module's tools and the exposed agents) and Git sync of the repository (/api/git; it follows repository
 * changes). The server-side runner that serverless Agent APIs use and SCIM provisioning stay the kernel's. Its tables are
 * in the kernel's frozen baseline.
 */
public final class IntegrationModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "integration"; }
    public String name() { return "Integration"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        // its permissions (administrators have them all; the built-in roles named here too)
        ctx.permission("apitasks.manage", "bot_creator");
        ApiTasks apiTasks = new ApiTasks(ctx);
        McpServer mcp = new McpServer(ctx);
        GitSync git = new GitSync(ctx, ctx.dataDir());
        ctx.provide(ApiTasks.class, apiTasks);
        ctx.provide(McpServer.class, mcp);
        ctx.provide(GitSync.class, git);

        // Agent APIs: run and poll with the task token (X-OOA-Task-Token, or a bearer token), before sign-in
        ctx.publicRoute("apitasks", (ex, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            if (seg.length < 2 || !(seg[1].equals("run") || seg[1].equals("runs"))) return false;   // the signed-in routes below
            String t = ex.getRequestHeaders().getFirst("X-OOA-Task-Token");
            if (t == null) t = ctx.requestToken(ex);
            if (m.equals("POST") && seg.length == 2 && seg[1].equals("run")) {
                Map<String, Object> b = ctx.jsonBody(ex);
                @SuppressWarnings("unchecked")
                Map.Entry<Boolean, Map<String, Object>> r = apiTasks.run(seg[0], t, b.get("inputs") instanceof Map<?, ?> im ? (Map<String, Object>) im : Map.of(), ip);
                ctx.sendJson(ex, r.getKey() ? 200 : 202, r.getValue());
            } else if (m.equals("GET") && seg.length == 3 && seg[1].equals("runs")) ctx.sendJson(ex, 200, apiTasks.poll(seg[0], t, seg[2], ip));
            else throw ModuleContext.notFound("The route /api/apitasks/" + route);
            return true;
        });
        ctx.route("apitasks", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("tasks", apiTasks.list(u));
            else if (m.equals("POST") && seg.length == 0) out = apiTasks.create(u, ctx.jsonBody(ex), ip);
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("token")) out = apiTasks.newToken(u, seg[0], ip);
            else if (m.equals("PATCH") && seg.length == 1) { apiTasks.setEnabled(u, seg[0], Boolean.TRUE.equals(ctx.jsonBody(ex).get("enabled")), ip); out = Map.of("ok", true); }
            else if (m.equals("DELETE") && seg.length == 1) { apiTasks.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else throw ModuleContext.notFound("The route /api/apitasks/" + route);
            ctx.sendJson(ex, 200, out);
        });

        // the MCP server (JSON-RPC over POST): a notification gets 202 and no body
        ctx.route("mcp", (ex, u, m, route, ip) -> {
            if (!m.equals("POST")) { ex.getResponseHeaders().set("Allow", "POST"); ex.sendResponseHeaders(405, -1); return; }
            Map<String, Object> res = mcp.handle(u, ctx.jsonValue(ex), ip);
            if (res == null) ex.sendResponseHeaders(202, -1); else ctx.sendJson(ex, 200, res);
        });

        // Git sync: follows every repository change; /api/git/status, /sync, /pull (Git as the source of truth, G70)
        ctx.onRepositoryChanged(git::changed);
        ctx.route("git", (ex, u, m, route, ip) -> {
            if (m.equals("GET") && route.equals("status")) { ctx.sendJson(ex, 200, git.status(u)); return; }
            try {
                if (m.equals("POST") && route.equals("sync")) ctx.sendJson(ex, 200, git.syncNow(u, ip));
                else if (m.equals("POST") && route.equals("pull")) ctx.sendJson(ex, 200, git.pullNow(u, ip));
                else throw ModuleContext.notFound("The route /api/git/" + route);
            } catch (OoaException e) { throw e; }
            catch (java.io.IOException e) { throw e; }
            catch (Exception e) { throw new OoaException("CR.GIT", (route.equals("pull") ? "Reading from Git failed: " : "Git sync failed: ") + e.getMessage()); }
        });
    }
}
