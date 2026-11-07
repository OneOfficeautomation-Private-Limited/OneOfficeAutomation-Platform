package com.oneofficeautomation.modules.coe;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.util.Map;

/**
 * Automation ideas (gap G65), a feature module of One CommandCenter (docs/architecture/13-modules.md): Build >
 * Automation ideas, /api/ideas. Its table ideas is in the kernel's frozen baseline (version 41); the permission
 * ideas.manage is still listed by the kernel (Rbac) until modules register their own (phase 8).
 */
public final class CoeModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "coe"; }
    public String name() { return "Automation ideas"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        // its permissions (administrators have them all; the built-in roles named here too)
        ctx.permission("ideas.manage");
        Ideas ideas = new Ideas(ctx);
        ctx.provide(Ideas.class, ideas);
        // /api/ideas: GET list, POST new; /api/ideas/{id}: PUT change, DELETE; /api/ideas/{id}/vote: POST
        ctx.route("ideas", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = ideas.list(u);
            else if (m.equals("POST") && seg.length == 0) out = ideas.save(u, null, ctx.jsonBody(ex), ip);
            else if (m.equals("PUT") && seg.length == 1) out = ideas.save(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("vote")) out = ideas.vote(u, seg[0]);
            else if (m.equals("DELETE") && seg.length == 1) { ideas.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else throw ModuleContext.notFound("The route /api/ideas/" + route);
            ctx.sendJson(ex, 200, out);
        });
    }
}
