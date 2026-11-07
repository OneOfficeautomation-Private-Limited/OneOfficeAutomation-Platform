package com.oneofficeautomation.modules.ai;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import java.util.Map;

/**
 * AI services, a feature module of One CommandCenter (docs/architecture/13-modules.md): Prompt Skills (AI > Prompt
 * Skills, /api/skills, and the skills a run's agent names, sent with its job) and knowledge bases (AI > Knowledge
 * bases, /api/knowledge-bases, and /api/agent/knowledge for runners). Model connections, the AI gateway, stay in the
 * kernel. The tables are in the kernel's frozen baseline; the permission knowledge.manage is still listed by the kernel.
 */
public final class AiServicesModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "ai-services"; }
    public String name() { return "AI services"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        PromptSkills skills = new PromptSkills(ctx);
        KnowledgeBases knowledge = new KnowledgeBases(ctx);
        ctx.provide(PromptSkills.class, skills);
        ctx.provide(KnowledgeBases.class, knowledge);
        ctx.jobPart("skills", skills::forJob);

        // /api/skills: GET list; /templates: GET; /from-template: POST; /{name}: GET (?version), PUT, DELETE
        ctx.route("skills", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("skills", skills.list(u));
            else if (m.equals("GET") && route.equals("templates")) out = Map.of("templates", skills.templates(u));
            else if (m.equals("POST") && route.equals("from-template")) {
                Map<String, Object> b = ctx.jsonBody(ex);
                out = skills.fromTemplate(u, (String) b.get("template"), (String) b.get("name"), ip);
            }
            else if (m.equals("GET") && seg.length == 1) {
                String v = ctx.query(ex).get("version");
                out = skills.get(u, seg[0], v == null ? null : Integer.valueOf(v));
            }
            else if (m.equals("PUT") && seg.length == 1) out = skills.save(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 1) { skills.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else throw ModuleContext.notFound("The route /api/skills/" + route);
            ctx.sendJson(ex, 200, out);
        });

        // /api/knowledge-bases: GET list, POST new; /{id}: PUT, DELETE
        ctx.route("knowledge-bases", (ex, u, m, route, ip) -> {
            String[] seg = route.isEmpty() ? new String[0] : route.split("/");
            Object out;
            if (m.equals("GET") && seg.length == 0) out = Map.of("knowledge_bases", knowledge.list(u));
            else if (m.equals("POST") && seg.length == 0) out = knowledge.save(u, null, ctx.jsonBody(ex), ip);
            else if (m.equals("PUT") && seg.length == 1) out = knowledge.save(u, seg[0], ctx.jsonBody(ex), ip);
            else if (m.equals("DELETE") && seg.length == 1) { knowledge.delete(u, seg[0], ip); out = Map.of("ok", true); }
            else throw ModuleContext.notFound("The route /api/knowledge-bases/" + route);
            ctx.sendJson(ex, 200, out);
        });
        // a runner's run asks for a knowledge base (Knowledge Q&A with Knowledge base set)
        ctx.agentRoute("knowledge", (ex, device, q) -> knowledge.forRun(device, ctx.jsonBody(ex)));
    }
}
