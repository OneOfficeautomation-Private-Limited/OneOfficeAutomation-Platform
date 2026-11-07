package com.oneofficeautomation.modules.lowcode;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.util.Map;

/**
 * Low-code, a feature module of One CommandCenter (docs/architecture/13-modules.md): apps (Build > Apps, gap G67),
 * apps embedded in other sites (G68: embed links, /api/embed/... with the embed key, the page /embed/{link}) and the
 * business data store (Manage > Data store, G66, with /api/agent/data for runners). Its tables are in the kernel's
 * frozen baseline (versions 42-44); the permissions data.* and apps.manage are still listed by the kernel.
 */
public final class LowCodeModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "low-code"; }
    public String name() { return "Low-code apps and data store"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        // its permissions (administrators have them all; the built-in roles named here too)
        ctx.permission("data.view", "viewer", "bot_runner", "bot_creator");
        ctx.permission("data.edit", "bot_creator");
        ctx.permission("apps.manage", "bot_creator");
        ctx.permission("data.manage");
        DataStore data = new DataStore(ctx);
        Apps apps = new Apps(ctx, data);
        AppEmbeds embeds = new AppEmbeds(ctx, apps);
        ctx.provide(DataStore.class, data);
        ctx.provide(Apps.class, apps);
        ctx.provide(AppEmbeds.class, embeds);

        // /api/apps: made by apps.manage; used by their audience, acting with the publisher's rights
        ctx.route("apps", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Map<String, String> q = ctx.query(ex);
            if (m.equals("POST") && seg.length == 4 && seg[1].equals("embeds") && seg[3].equals("teams")) {
                Map<String, Object> b = ctx.jsonBody(ex);
                ctx.sendFile(ex, embeds.teamsPackage(u, seg[0], seg[2], (String) b.get("key"), (String) b.get("base_url")), "application/zip", "teams-app.zip");
                return;
            }
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("apps", apps.list(u));
            else if (m.equals("POST") && seg.length == 0) out = apps.save(u, null, ctx.jsonBody(ex), ip);
            else if (m.equals("GET") && seg.length == 1) out = apps.get(u, seg[0]);
            else if (m.equals("PUT") && seg.length == 1) out = apps.save(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 1) { apps.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("act")) out = apps.act(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("GET") && seg.length == 3 && seg[1].equals("runs")) out = apps.runStatus(u, seg[0], seg[2]);
            else if (m.equals("GET") && seg.length == 3 && seg[1].equals("records")) out = apps.records(u, seg[0], seg[2], q.get("q"));
            // embed links (G68)
            else if (m.equals("GET") && seg.length == 2 && seg[1].equals("embeds")) out = Map.of("embeds", embeds.list(u, seg[0]));
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("embeds")) out = embeds.make(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 3 && seg[1].equals("embeds")) { embeds.delete(u, seg[0], seg[2], ip); out = Map.of("ok", true); }
            else throw ModuleContext.notFound("The route /api/apps/" + route);
            ctx.sendJson(ex, 200, out);
        });

        // /api/data: entities and their records, storage buckets and their files
        ctx.route("data", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Map<String, String> q = ctx.query(ex);
            Object out;
            if (seg.length >= 1 && seg[0].equals("entities")) {
                if (m.equals("GET") && seg.length == 1) out = Map.of("entities", data.entities(u));
                else if (m.equals("POST") && seg.length == 1) out = data.saveEntity(u, null, ctx.jsonBody(ex), ip);
                else if (m.equals("PUT") && seg.length == 2) out = data.saveEntity(u, seg[1], ctx.jsonBody(ex), ip);
                else if (m.equals("DELETE") && seg.length == 2) { data.deleteEntity(u, seg[1], ip); out = Map.of("ok", true); }
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("records")) out = data.records(u, seg[1], q);
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("records")) out = data.saveRecord(u, seg[1], null, ctx.jsonBody(ex), ip);
                else if (m.equals("PUT") && seg.length == 4 && seg[2].equals("records")) out = data.saveRecord(u, seg[1], seg[3], ctx.jsonBody(ex), ip);
                else if (m.equals("DELETE") && seg.length == 4 && seg[2].equals("records")) { data.deleteRecord(u, seg[1], seg[3], ip); out = Map.of("ok", true); }
                else throw ModuleContext.notFound("The route /api/data/" + route);
            } else if (seg.length >= 1 && seg[0].equals("buckets")) {
                if (m.equals("GET") && seg.length == 1) out = Map.of("buckets", data.buckets(u));
                else if (m.equals("POST") && seg.length == 1) out = data.saveBucket(u, null, ctx.jsonBody(ex), ip);
                else if (m.equals("PUT") && seg.length == 2) out = data.saveBucket(u, seg[1], ctx.jsonBody(ex), ip);
                else if (m.equals("DELETE") && seg.length == 2) { data.deleteBucket(u, seg[1], ip); out = Map.of("ok", true); }
                // files: the path is a query parameter, as it holds slashes; the upload's body is the file itself
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("files")) out = Map.of("files", data.files(u, seg[1], q.get("prefix")));
                else if (m.equals("PUT") && seg.length == 3 && seg[2].equals("files"))
                    out = data.upload(u, seg[1], q.get("path"), ex.getRequestBody().readNBytes(DataStore.MAX_MB * 1024 * 1024 + 1), ip);
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("file")) {
                    String p0 = DataStore.filePath(q.get("path"));
                    ctx.sendFile(ex, data.download(u, seg[1], p0), "application/octet-stream", p0.substring(p0.lastIndexOf('/') + 1));
                    return;
                }
                else if (m.equals("DELETE") && seg.length == 3 && seg[2].equals("files")) { data.removeFile(u, seg[1], q.get("path"), ip); out = Map.of("ok", true); }
                else throw ModuleContext.notFound("The route /api/data/" + route);
            } else throw ModuleContext.notFound("The route /api/data/" + route);
            ctx.sendJson(ex, 200, out);
        });
        // a runner's run reads and writes the data store
        ctx.agentRoute("data", (ex, device, q) -> data.forRun(device, ctx.jsonBody(ex)));

        // apps embedded in other sites: the embed key, never a session, and only for that link's app
        ctx.publicRoute("embed", (ex, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            String key = ex.getRequestHeaders().getFirst("X-OOA-Embed-Key");
            Object out;
            if (m.equals("GET") && seg.length == 2 && seg[1].equals("app")) out = embeds.app(seg[0], key);
            else if (m.equals("POST") && seg.length == 2 && seg[1].equals("act")) out = embeds.act(seg[0], key, ctx.jsonBody(ex), ip);
            else if (m.equals("GET") && seg.length == 3 && seg[1].equals("runs")) out = embeds.runStatus(seg[0], key, seg[2]);
            else if (m.equals("GET") && seg.length == 3 && seg[1].equals("records")) out = embeds.records(seg[0], key, seg[2], ctx.query(ex).get("q"));
            else throw ModuleContext.notFound("The route /api/embed/" + route);
            ctx.sendJson(ex, 200, out);
            return true;
        });
        // /embed/{link}: only the sites the link names may frame the page
        ctx.embeddablePage("embed", embeds::frameAncestors);
    }
}
