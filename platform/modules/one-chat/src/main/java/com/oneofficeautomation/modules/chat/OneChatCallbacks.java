package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.conversation.ChatEngine.ChatBot;
import com.oneofficeautomation.conversation.ChatPlatform;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.*;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * The routes the OneChat service calls back on, /api/onechat-callback/v1/ (S5, 20 Nov 2026;
 * docs/integration/one-chat-contract.md, sections 2 to 4, and docs/integration/onechat-api.yaml). Every call carries
 * the service token, the tenant and the person token One CommandCenter gave the service; One CommandCenter checks all
 * three, then the area's list (the Studio area is development only), then the assistant's settings (Never is refused),
 * then the person's permissions as they are now. Only version and health answer without a person token.
 */
final class OneChatCallbacks {
    /** What each area may call: the route, or its first part for routes with an id (skills/{name}, runs/{id}). */
    static final Set<String> CHAT = Set.of("person", "settings", "skills", "agents", "runs", "tasks", "admin/tools", "admin/actions", "notices", "model", "audit");
    static final Set<String> STUDIO = Set.of("person", "settings", "skills", "model", "audit", "studio/commands", "studio/check", "studio/agents");
    /** The most text a model call may send, per area (Studio sends its whole command list). */
    static final int MAX_CHAT = 200_000, MAX_STUDIO = StudioAssistant.MAX_SYSTEM + StudioAssistant.MAX_PROMPT;

    private final ModuleContext ctx;
    private final Chat chat;
    private final AssistantSettings settings;
    private final ChatModel model;
    private final OneChatService service;
    private final PersonTokens tokens;

    OneChatCallbacks(ModuleContext ctx, Chat chat, ChatModel model, OneChatService service, PersonTokens tokens) {
        this.ctx = ctx;
        this.chat = chat;
        this.settings = chat.settings();
        this.model = model;
        this.service = service;
        this.tokens = tokens;
    }

    /** One callback; route is what follows /api/onechat-callback/. Always answers (true). */
    boolean handle(HttpExchange ex, String m, String route, String ip) throws IOException {
        if (!route.startsWith("v1/")) throw notFound("The route /api/onechat-callback/" + route);
        String r = route.substring(3);
        serviceToken(ex, ip);
        if (m.equals("GET") && r.equals("version")) {
            ctx.sendJson(ex, 200, Map.of("service", "one-commandcenter", "contract", OneChatService.COMPAT.getProperty("contract", "v1"), "module", OneChatModule.VERSION));
            return true;
        }
        if (m.equals("GET") && r.equals("health")) { ctx.sendJson(ex, 200, Map.of("status", "ok", "engine", service.chosen() ? "service" : "builtin")); return true; }
        if (!service.chosen())
            throw new OoaException("CR.FORBIDDEN", "One Chat uses its built-in engine; the OneChat service is not chosen", "An administrator chooses OneChat service in Settings > AI assistants > One Chat > Engine.");
        String tenant = ex.getRequestHeaders().getFirst("X-OOA-Tenant");
        PersonTokens.Person p = tenant == null ? null : tokens.check(ex.getRequestHeaders().getFirst("X-OOA-Person"), tenant);
        if (p == null) {
            ctx.audit("onechat", "onechat.callback_refused", "route", r, Map.of("reason", "person token"), "failed", ip);
            throw new OoaException("CR.UNAUTHENTICATED", "The person token is missing, not valid or has expired", "Pass on the X-OOA-Person token of the request you are answering, unchanged; it is good for five minutes.");
        }
        String key = family(r);
        if (!(p.area().equals("studio") ? STUDIO : p.area().equals("chat") ? CHAT : Set.<String>of()).contains(key))
            throw new OoaException("CR.FORBIDDEN", "The " + p.area() + " area may not use " + key, p.area().equals("studio")
                    ? "Studio's assistant helps with development only: the agent open in Studio, commands, checks, the agents it may call and model calls."
                    : "See the routes of the chat area in docs/integration/one-chat-contract.md.");
        // Studio's assistant is for people who build agents (as POST /api/assistants/studio/complete)
        if (p.area().equals("studio")) ctx.require(p.user(), "repo.checkin", "use Studio's assistant");
        ctx.sendJson(ex, 200, answer(ex, m, r, key, p));
        return true;
    }

    /** The route's family: skills/{name} and runs/{id} belong to skills and runs. */
    static String family(String r) {
        if (r.startsWith("skills/")) return "skills";
        if (r.startsWith("runs/")) return "runs";
        return r;
    }

    private void serviceToken(HttpExchange ex, String ip) {
        String expected = service.token();
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String given = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
        if (expected.isEmpty() || given == null || !constantEquals(given, expected)) {
            ctx.audit("onechat", "onechat.callback_refused", "service", null, Map.of("reason", expected.isEmpty() ? "no service token set" : "service token"), "failed", ip);
            throw new OoaException("CR.UNAUTHENTICATED", "The OneChat service token is wrong or not set up",
                    "Use the same token as One CommandCenter (ONECHAT_API_TOKEN); an administrator sets it in Settings > AI assistants > One Chat > Engine.");
        }
    }

    private Object answer(HttpExchange ex, String m, String r, String key, PersonTokens.Person p) throws IOException {
        User u = p.user();
        ChatPlatform.Person cp = new ChatPlatform.Person(u.username(), u.role(), p.ip());
        if (m.equals("GET")) {
            switch (key) {
                case "person": return person(u, p.area());
                case "settings": return settings(u, p.area());
                case "skills": return skill(p.area(), r.substring("skills/".length()));
                case "agents": allowed(cp, "agents"); return Map.of("agents", agents(cp));
                case "runs":
                    allowed(cp, "read");
                    if (!chat.canViewRuns(cp)) throw denied("see runs");
                    if (r.equals("runs")) return Map.of("runs", chat.recentRuns(cp, Math.max(1, Math.min(50, intOr(ctx.query(ex).get("limit"), 10)))));
                    return run(u, r.substring("runs/".length()));
                case "tasks": allowed(cp, "read"); return Map.of("tasks", tasks(u));
                case "admin/tools": return Map.of("tools", adminTools(u, cp));
                default: break;
            }
        } else if (m.equals("POST")) {
            switch (key) {
                case "model": return model(u, p.area(), ctx.jsonBody(ex));
                case "audit": return audit(p, ctx.jsonBody(ex));
                default: break;
            }
        }
        throw notFound("The route " + m + " /api/onechat-callback/v1/" + r);
    }

    /** Never is refused (403); Ask first and Always answer (a look-up needs no confirmation). */
    private void allowed(ChatPlatform.Person p, String group) {
        if ("never".equals(chat.policy(p, group)))
            throw new OoaException("CR.FORBIDDEN", "The assistant's settings do not allow this (" + group + ": Never)", "An administrator changes it in Settings > AI assistants > One Chat > Tools and permissions.");
    }

    // ---- read-only callbacks ----

    Map<String, Object> person(User u, String area) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", u.username());
        out.put("display_name", u.displayName() == null ? u.username() : u.displayName());
        out.put("role", u.role());
        out.put("tenant", tokens.tenant());
        out.put("area", area);
        out.put("language", settings.language(u, area));
        Map<String, Object> may = new LinkedHashMap<>();
        may.put("view_runs", ctx.can(u, "runs.view"));
        may.put("start_runs", ctx.can(u, "runs.start"));
        may.put("build_agents", ctx.can(u, "repo.checkin"));
        out.put("may", may);
        return out;
    }

    /** The person's settings for the area, company and person merged (AssistantSettings.effective); never a secret. */
    Map<String, Object> settings(User u, String area) {
        AssistantSettings.Effective e = settings.effective(u, area);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("area", area);
        out.put("model", e.connection());
        out.put("models", settings.modelsFor(u, area));
        out.put("temperature", e.temperature());
        out.put("max_tokens", e.maxTokens());
        out.put("language", e.language());
        out.put("instructions", e.instructions());
        out.put("skills", AssistantSettings.names(String.valueOf(Objects.requireNonNullElse(ctx.setting(area + "_skills"), ""))));
        out.put("timeout_seconds", e.timeoutSeconds());
        out.put("mask_pii", e.maskPii());
        out.put("ktokens_per_person", ctx.intSetting(area + "_ktokens_per_person"));
        if (area.equals("chat")) {
            ChatPlatform.Person cp = new ChatPlatform.Person(u.username(), u.role(), null);
            Map<String, Object> tools = new LinkedHashMap<>();
            for (String g : ChatPlatform.GROUPS) tools.put(g, chat.policy(cp, g));
            out.put("tools", tools);
            out.put("memory", e.memory());
            out.put("keep_hours", (int) settings.keepChats().toHours());
            out.put("mode", String.valueOf(Objects.requireNonNullElse(ctx.setting("chat_mode"), "deterministic")));
            out.put("runner", String.valueOf(Objects.requireNonNullElse(ctx.setting("chat_runner"), "")));
        } else out.put("memory", "off");
        return out;
    }

    /** A Prompt Skill the area may use: its text. */
    Map<String, Object> skill(String area, String name) {
        if (!AssistantSettings.names(String.valueOf(Objects.requireNonNullElse(ctx.setting(area + "_skills"), ""))).contains(name))
            throw notFound("The Prompt Skill " + name + " for this assistant");
        Map<String, Object> s = ctx.db().one("SELECT name, description, definition FROM prompt_skills WHERE name = ? AND shared = ? AND template = ?", name, true, false);
        if (s == null) throw notFound("The Prompt Skill " + name);
        Map<?, ?> d;
        try { d = JsonLite.parse(String.valueOf(s.get("definition"))) instanceof Map<?, ?> dm ? dm : Map.of(); } catch (RuntimeException e) { d = Map.of(); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("description", s.get("description") == null ? "" : String.valueOf(s.get("description")));
        out.put("system", d.get("system") == null ? "" : String.valueOf(d.get("system")));
        out.put("prompt", d.get("prompt") == null ? "" : String.valueOf(d.get("prompt")));
        return out;
    }

    /** The agents the person may run now, with what OneChat needs to understand a request for one. */
    List<Map<String, Object>> agents(ChatPlatform.Person p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatBot b : chat.agents(p)) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("path", b.path());
            a.put("name", b.name());
            a.put("description", b.description());
            a.put("risk", b.risk());
            a.put("inputs", b.inputs());
            a.put("chat", Map.of("examples", b.examples(), "aliases", b.aliases(), "not_for", b.notFor(), "prompts", b.prompts()));
            out.add(a);
        }
        return out;
    }

    /** One of the person's own runs, with its status and results. */
    @SuppressWarnings("unchecked")
    Map<String, Object> run(User u, String id) {
        Map<String, Object> r = ctx.db().one("SELECT id, bot_path, status, created, started, finished, outputs, error FROM runs WHERE id = ? AND requested_by = ?", id, u.username());
        if (r == null) throw notFound("Your run " + id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", r.get("id"));
        out.put("agent", r.get("bot_path"));
        out.put("status", r.get("status"));
        for (String k : List.of("created", "started", "finished")) out.put(k, r.get(k) instanceof java.sql.Timestamp t ? t.toInstant().toString() : null);
        try { out.put("results", r.get("outputs") instanceof String o ? JsonLite.parse(o) : Map.of()); } catch (RuntimeException e) { out.put("results", Map.of()); }
        try { out.put("error", r.get("error") instanceof String e ? JsonLite.parse(e) : null); } catch (RuntimeException e) { out.put("error", Map.of("message", String.valueOf(r.get("error")))); }
        return out;
    }

    /** The person's open Task inbox items (assigned to them, one of their roles, or claimed by them): title, kind, due. */
    List<Map<String, Object>> tasks(User u) {
        List<String> roles = ctx.rolesOf(u.username(), u.role());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : ctx.db().query("SELECT id, kind, title, assignee, status, claimed_by, created, due FROM human_tasks WHERE status IN ('open', 'claimed') ORDER BY created DESC LIMIT ?", 500)) {
            boolean mine = "claimed".equals(t.get("status")) ? u.username().equals(t.get("claimed_by")) : assigned(String.valueOf(t.get("assignee")), u, roles);
            if (!mine) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("task_id", t.get("id"));
            x.put("kind", t.get("kind"));
            x.put("title", t.get("title"));
            x.put("status", t.get("status"));
            x.put("created", t.get("created") instanceof java.sql.Timestamp ts ? ts.toInstant().toString() : null);
            x.put("due", t.get("due") instanceof java.sql.Timestamp ts ? ts.toInstant().toString() : null);
            out.add(x);
            if (out.size() == 50) break;
        }
        return out;
    }

    private boolean assigned(String assignee, User u, List<String> roles) {
        for (String a : assignee.split(",")) {
            String t = a.trim();
            if (t.equals("user:" + u.username()) || t.equals("role:*") || (t.startsWith("role:") && roles.contains(t.substring(5)))) return true;
            if (t.startsWith("perm:") && ctx.can(u, t.substring(5))) return true;
        }
        return false;
    }

    /** The administration tools the person's permissions allow and the assistant's settings do not set to Never. */
    List<Map<String, Object>> adminTools(User u, ChatPlatform.Person p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : ctx.adminTools(u)) {
            String rule = chat.policy(p, String.valueOf(t.get("group")));
            if (rule.equals("never")) continue;
            Map<String, Object> x = new LinkedHashMap<>(t);
            x.put("rule", rule);
            out.add(x);
        }
        return out;
    }

    // ---- model calls and the Audit Trail ----

    /**
     * One model call through One Model Connections with the area's settings for the person: the person's limit (429),
     * a connection they may use (the area's model by default), masking, the wait time and the record of the call.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> model(User u, String area, Map<String, Object> b) {
        AssistantSettings.Effective e = settings.effective(u, area);
        settings.checkAllowance(u, area);
        String connection = b.get("connection") == null ? "" : String.valueOf(b.get("connection")).trim();
        if (!connection.isEmpty() && !connection.equals(e.connection()) && !settings.modelsFor(u, area).contains(connection))
            throw new OoaException("CR.FORBIDDEN", "The model " + connection + " is not one this assistant may use for you", "Leave connection out to use the assistant's model, or ask an administrator to allow it in Settings > AI assistants.");
        if (!(b.get("messages") instanceof List<?> ml) || ml.isEmpty()) throw new OoaException("CR.BAD_INPUT", "messages is a list of {role, content}");
        List<Map<String, Object>> messages = new ArrayList<>();
        long size = 0;
        for (Object o : ml) {
            if (!(o instanceof Map<?, ?> mm) || !List.of("system", "user", "assistant", "tool").contains(String.valueOf(mm.get("role"))))
                throw new OoaException("CR.BAD_INPUT", "Each message is {role: system, user, assistant or tool, content}");
            size += String.valueOf(mm.get("content")).length();
            messages.add((Map<String, Object>) mm);
        }
        if (size > (area.equals("studio") ? MAX_STUDIO : MAX_CHAT))
            throw new OoaException("ASSISTANT.TOO_LONG", "The request to the model is too long", "Send less of the chat, or a smaller part of the agent.");
        List<Map<String, Object>> tools = b.get("tools") instanceof List<?> tl ? (List<Map<String, Object>>) tl : List.of();
        String purpose = b.get("purpose") == null ? "onechat" : String.valueOf(b.get("purpose")).replaceAll("[^A-Za-z0-9_.-]", "");
        if (purpose.isEmpty()) purpose = "onechat";
        if (purpose.length() > 60) purpose = purpose.substring(0, 60);
        Map<String, Object> r = model.call(u, connection.isEmpty() ? e.connection() : connection, messages, tools, purpose,
                e.temperature(), e.maxTokens(), e.maskPii(), e.timeoutSeconds(), area.equals("studio") ? "Studio assistant" : "One Chat");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", r.get("text") == null ? "" : String.valueOf(r.get("text")));
        out.put("tool_calls", r.get("tool_calls") == null ? List.of() : r.get("tool_calls"));
        for (String k : List.of("input_tokens", "output_tokens")) if (r.get(k) != null) out.put(k, r.get(k));
        return out;
    }

    /** An Audit Trail entry as the person; the action starts with the area (chat. or studio.). */
    Map<String, Object> audit(PersonTokens.Person p, Map<String, Object> b) {
        String action = b.get("action") == null ? "" : String.valueOf(b.get("action")).trim();
        if (!action.matches((p.area().equals("studio") ? "studio" : "chat") + "\\.[a-z0-9_.]{1,60}"))
            throw new OoaException("CR.BAD_INPUT", "The action starts with " + p.area() + ". and uses lower-case letters, digits, dots and underscores", "For example " + p.area() + ".answered");
        String type = b.get("object_type") == null ? "chat" : String.valueOf(b.get("object_type"));
        String id = b.get("object_id") == null ? null : String.valueOf(b.get("object_id"));
        Object details = b.get("details");
        if (details != null && JsonLite.write(details).length() > 4000) throw new OoaException("CR.BAD_INPUT", "The details are too long (at most 4,000 characters)");
        ctx.audit(p.user().username(), action, type.length() > 40 ? type.substring(0, 40) : type, id, details == null ? Map.of() : details, "ok", p.ip());
        return Map.of("ok", true);
    }
}
