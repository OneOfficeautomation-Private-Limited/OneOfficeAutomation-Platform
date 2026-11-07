package com.oneofficeautomation.modules.runners;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.time.Duration;

/**
 * Runner management, a feature module of One CommandCenter (docs/architecture/13-modules.md): auto-scaling runner pools
 * in the customer's own cloud (G52: a webhook, an AWS Auto Scaling group or an Azure scale set follows the pool's work).
 * It hears when a pool has work but no online runner, checks every pool once a minute, and serves a pool's settings at
 * /api/pools/{pool}/scaling (the kernel passes that route on). Runner care and updates stay the kernel's.
 */
public final class RunnerManagementModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "runner-management"; }
    public String name() { return "Runner management"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        PoolScaling scaling = new PoolScaling(ctx);
        ctx.provide(PoolScaling.class, scaling);
        ctx.onNoRunner(scaling::noRunner);
        ctx.every(Duration.ofSeconds(30), Duration.ofSeconds(60), scaling::tick);
        // /api/pools/{pool}/scaling, passed on by the kernel's pools routes as pool-scaling with the pool id as the route
        ctx.route("pool-scaling", (ex, u, m, pool, ip) -> {
            if (pool.isEmpty() || pool.contains("/")) throw ModuleContext.notFound("The pool");
            if (m.equals("GET")) ctx.sendJson(ex, 200, scaling.get(u, pool));
            else if (m.equals("PUT")) ctx.sendJson(ex, 200, scaling.put(u, pool, ctx.jsonBody(ex), ip));
            else throw ModuleContext.notFound("The route /api/pools/" + pool + "/scaling");
        });
    }
}
