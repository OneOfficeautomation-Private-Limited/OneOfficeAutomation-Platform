package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.conversation.BotTools;
import com.oneofficeautomation.conversation.ChatEngine.ChatBot;
import com.oneofficeautomation.conversation.ChatPlatform;
import com.oneofficeautomation.conversation.Conversation;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

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
    /** How long a person has to say yes to a summary (as with the built-in engine). */
    static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);
    /** The most notices the service may put in one person's Task inbox an hour. */
    static final int NOTICES_PER_HOUR = 30;

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
                case "studio/commands": return Map.of("toolkits", ctx.toolkitManifests());
                case "studio/agents": return Map.of("agents", studioAgents(u));
                default: break;
            }
        } else if (m.equals("POST")) {
            switch (key) {
                case "model": return model(u, p.area(), ctx.jsonBody(ex));
                case "audit": return audit(p, ctx.jsonBody(ex));
                case "runs": if (r.equals("runs")) return startRun(p, cp, ctx.jsonBody(ex)); break;
                case "admin/actions": return adminAction(p, cp, ctx.jsonBody(ex));
                case "notices": return notice(u, ctx.jsonBody(ex));
                case "studio/check": return check(ctx.jsonBody(ex));
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
        for (String k : List.of("created", "started", "finished")) out.put(k, r.get(k) == null ? null : String.valueOf(r.get(k)));
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
            x.put("created", t.get("created") == null ? null : String.valueOf(t.get("created")));
            x.put("due", t.get("due") == null ? null : String.valueOf(t.get("due")));
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

    // ---- S5b: runs and administration, with One CommandCenter's own confirmation ----

    /**
     * Starts an agent as the person. The agent's risk and the settings decide, not the service: R0 under Always starts
     * at once; anything else answers a confirmation first; R3 becomes an Approve task for someone else.
     */
    Map<String, Object> startRun(PersonTokens.Person p, ChatPlatform.Person cp, Map<String, Object> b) {
        allowed(cp, "agents");
        String path = text(b.get("agent")), session = text(b.get("session_id"));
        if (path.isEmpty() || session.isEmpty()) throw new OoaException("CR.BAD_INPUT", "agent and session_id are needed", "Send the agent's path from GET agents and the chat's id.");
        ChatBot bot = chat.agents(cp).stream().filter(x -> x.path().equals(path)).findFirst().orElse(null);
        if (bot == null) return refused("refused", "You may not run " + path + ", or it has no production version.");
        Map<String, Object> inputs = inputs(bot.inputs(), b.get("inputs"));
        String rule = chat.policy(cp, "agents");
        return act(p, cp, "agent", path, bot.name(), bot.risk(), rule, null, inputs, session, text(b.get("confirmation_id")),
                () -> {
                    String runId = chat.startRun(cp, path, inputs, "onechat");
                    ctx.db().update("INSERT INTO onechat_runs (run_id, username, session_id, created) VALUES (?,?,?,?)", runId, cp.username(), session, now());
                    return Map.of("run_id", runId);
                });
    }

    /** Uses an administration tool as the person, with the same rules as an agent (S3's catalogue, the kernel's own checks). */
    @SuppressWarnings("unchecked")
    Map<String, Object> adminAction(PersonTokens.Person p, ChatPlatform.Person cp, Map<String, Object> b) {
        String id = text(b.get("tool")), session = text(b.get("session_id"));
        if (id.isEmpty() || session.isEmpty()) throw new OoaException("CR.BAD_INPUT", "tool and session_id are needed", "Send the tool's id from GET admin/tools and the chat's id.");
        Map<String, Object> t = ctx.adminTools(p.user()).stream().filter(x -> id.equals(x.get("id"))).findFirst().orElse(null);
        if (t == null) return refused("refused", "The administration tool " + id + " is not one you may use.");
        String group = String.valueOf(t.get("group"));
        allowed(cp, group);
        List<Map<String, Object>> declared = t.get("inputs") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        Map<String, Object> inputs = inputs(declared, b.get("inputs"));
        return act(p, cp, "tool", Conversation.TOOL_PREFIX + id, String.valueOf(t.get("name")), String.valueOf(t.getOrDefault("risk", "R2")), chat.policy(cp, group),
                (String) t.get("undo"), inputs, session, text(b.get("confirmation_id")), () -> Map.of("result", ctx.runAdminTool(p.user(), id, inputs, p.ip())));
    }

    /**
     * The one rule for both: without a confirmation id, act at once (R0 under Always) or answer a confirmation; with one,
     * check it is this person's and chat's, for these inputs, unused, within ten minutes and confirmed by a later
     * message's token, then act (R3: an Approve task for someone else).
     */
    private Map<String, Object> act(PersonTokens.Person p, ChatPlatform.Person cp, String kind, String target, String name, String risk, String rule, String undo,
                                    Map<String, Object> inputs, String session, String confirmationId, Supplier<Map<String, Object>> doIt) {
        String user = p.user().username(), hash = hash(inputs);
        boolean ask = !risk.equals("R0") || rule.startsWith("ask");
        if (confirmationId.isEmpty()) {
            if (!ask) return done(cp, kind, target, doIt, null);
            String id = ModuleContext.id("cnf");
            Instant expires = now().plus(CONFIRMATION_TTL);
            String reason = !risk.equals("R0") ? null : rule.equals("ask_person") ? "You asked to be asked before anything runs or changes."
                    : "Your company asks first for this kind of request.";
            ctx.db().update("INSERT INTO onechat_confirmations (id, username, session_id, kind, target, name, input_hash, risk, reason, undo, issued_at, token_id, expires, used) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, user, session, kind, target, cut(name, 200), hash, risk, reason, undo == null ? null : cut(undo, 300),
                    p.issued(), p.id(), expires, false);
            ctx.audit(user, "onechat.propose", kind.equals("tool") ? "tool" : "bot", target, Map.of("confirmation", id, "inputs", new ArrayList<>(inputs.keySet()), "input_hash", hash), "ok", p.ip());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("confirmation_id", id);
            c.put("summary", summary(kind, name, inputs) + (risk.equals("R3") ? " Someone else approves it before it happens." : ""));
            c.put("expires", expires.toString());
            c.put("risk", risk);
            if (reason != null) c.put("reason", reason);
            if (undo != null) c.put("undo", undo);
            return Map.of("confirm", c);
        }
        Map<String, Object> c = ctx.db().one("SELECT username, session_id, kind, target, input_hash, risk, issued_at, token_id, expires, used FROM onechat_confirmations WHERE id = ?", confirmationId);
        if (c == null) throw new OoaException("CR.CONFLICT", "This confirmation is not known", "Show the person a new summary and ask again.");
        if (!user.equals(c.get("username")) || !session.equals(c.get("session_id")) || !kind.equals(c.get("kind")) || !target.equals(c.get("target")) || !hash.equals(c.get("input_hash")))
            throw new OoaException("CR.FORBIDDEN", "This confirmation is for another person, chat, request or set of inputs", "Show the person a new summary and ask again.");
        if (Boolean.TRUE.equals(c.get("used"))) throw new OoaException("CR.CONFLICT", "This confirmation was already used", "Each yes is used once; ask again for another run.");
        Instant expires = OneChatService.instant(c.get("expires"), Instant.EPOCH);
        if (expires.isBefore(now())) throw new OoaException("CR.CONFLICT", "This confirmation has expired (10 minutes)", "Show the person a new summary and ask again.");
        if (p.id().equals(c.get("token_id")) || p.issued() < ((Number) c.get("issued_at")).longValue())
            throw new OoaException("CR.FORBIDDEN", "The yes must come from a later message of the person", "Show the summary, and call again with the person token of the message where they said yes.");
        if (ctx.db().update("UPDATE onechat_confirmations SET used = ? WHERE id = ? AND used = ?", true, confirmationId, false) == 0)
            throw new OoaException("CR.CONFLICT", "This confirmation was already used", "Each yes is used once; ask again for another run.");
        if (risk.equals("R3")) {
            String taskId = chat.requestApproval(cp, new ChatPlatform.ApprovalRequest(session, target, name, inputs, summary(kind, name, inputs), confirmationId, hash), "onechat");
            ctx.audit(user, "onechat.approval_requested", "task", taskId, Map.of("target", target, "confirmation", confirmationId, "input_hash", hash), "ok", p.ip());
            return Map.of("approval", Map.of("task_id", taskId));
        }
        return done(cp, kind, target, doIt, confirmationId);
    }

    private Map<String, Object> done(ChatPlatform.Person cp, String kind, String target, Supplier<Map<String, Object>> doIt, String confirmationId) {
        try {
            Map<String, Object> r = doIt.get();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("target", target);
            if (confirmationId != null) details.put("confirmation", confirmationId);
            ctx.audit(cp.username(), kind.equals("tool") ? "onechat.tool" : "onechat.run", kind.equals("tool") ? "tool" : "run",
                    kind.equals("tool") ? target : String.valueOf(r.get("run_id")), details, "ok", cp.ip());
            return r;
        } catch (ChatPlatform.Refused e) {
            return refused(e.kind(), e.getMessage());
        } catch (OoaException e) {
            return refused("refused", e.getMessage() + (e.suggestedFix() == null ? "" : " " + e.suggestedFix()));
        }
    }

    private static Map<String, Object> refused(String kind, String message) { return Map.of("refused", Map.of("kind", kind, "message", message)); }

    /** The declared inputs only, the required ones present and each value from its list where it has one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> inputs(List<Map<String, Object>> declared, Object given) {
        Map<String, Object> in = given instanceof Map<?, ?> g ? (Map<String, Object>) g : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>(), wrong = new ArrayList<>();
        for (Map<String, Object> v : declared) {
            String name = String.valueOf(v.get("name"));
            Object value = in.get(name);
            if (value == null || (value instanceof String sv && sv.isBlank())) { if (BotTools.required(v)) missing.add(name); continue; }
            if (v.get("enum") instanceof List<?> allowed && !allowed.isEmpty() && allowed.stream().noneMatch(a -> String.valueOf(a).equals(String.valueOf(value)))) wrong.add(name);
            out.put(name, value);
        }
        if (!missing.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Missing inputs: " + String.join(", ", missing), "Ask the person for them, then call again.");
        if (!wrong.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Not one of the allowed values: " + String.join(", ", wrong), "Use a value from the input's list.");
        return out;
    }

    static String summary(String kind, String name, Map<String, Object> inputs) {
        String with = inputs.isEmpty() ? "" : " with " + String.join(", ", inputs.entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).toList());
        return (kind.equals("tool") ? "Do: " : "Run ") + name + with + ".";
    }

    static String hash(Map<String, Object> inputs) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonLite.write(new TreeMap<>(inputs)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String text(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    /** A notice in the person's own Task inbox (only the person in the token; at most 30 an hour). */
    Map<String, Object> notice(User u, Map<String, Object> b) {
        String title = text(b.get("title")), message = text(b.get("message"));
        if (title.isEmpty() || message.isEmpty()) throw new OoaException("CR.BAD_INPUT", "title and message are needed");
        if (title.length() > 200 || message.length() > 2000) throw new OoaException("CR.BAD_INPUT", "The title is at most 200 characters and the message 2,000");
        Map<String, Object> n = ctx.db().one("SELECT COUNT(*) AS n FROM human_tasks WHERE stage = ? AND kind = ? AND assignee = ? AND created > ? AND data = ?",
                Chat.APPROVAL_STAGE, "notification", "user:" + u.username(), now().minus(Duration.ofHours(1)), NOTICE_DATA);
        if (n != null && ((Number) n.get("n")).intValue() >= NOTICES_PER_HOUR)
            throw new OoaException("CR.RATE_LIMITED", "OneChat has sent this person " + NOTICES_PER_HOUR + " notices in the last hour, the most allowed", "Put several things in one notice.");
        String taskId = ModuleContext.id("task");
        ctx.db().update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, data, assignee, status, created) VALUES (?,?,?,?,?,?,?,?,?,?)",
                taskId, null, Chat.APPROVAL_STAGE, "notification", title, message, NOTICE_DATA, "user:" + u.username(), "open", now());
        ctx.audit(u.username(), "onechat.notice", "task", taskId, Map.of("title", title), "ok", null);
        return Map.of("task_id", taskId);
    }

    /** What marks a notice the service sent. */
    static final String NOTICE_DATA = "{\"source\":\"onechat\"}";

    // ---- S5b: the Studio area (development only) ----

    /** The agents the person may call from theirs: name, inputs and outputs (read-only; at most 200). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> studioAgents(User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : ctx.listAgents(u)) {
            String path = String.valueOf(a.get("path"));
            if (!path.endsWith(".flow.json")) continue;
            try {
                Map<String, Object> flow = (Map<String, Object>) ctx.agentContent(path, a.get("production_version") != null ? "production" : "latest").get("flow");
                List<Map<String, Object>> ins = new ArrayList<>(), outs = new ArrayList<>();
                if (flow != null && flow.get("variables") instanceof List<?> vars) for (Object o : vars) if (o instanceof Map<?, ?> v) {
                    String dir = String.valueOf(v.get("direction"));
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("name", v.get("name"));
                    x.put("type", v.get("type"));
                    if (v.get("description") != null) x.put("description", v.get("description"));
                    if (dir.equals("input") || dir.equals("in_out")) ins.add(x);
                    if (dir.equals("output") || dir.equals("in_out")) outs.add(x);
                }
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("path", path);
                x.put("name", flow != null && flow.get("name") != null ? flow.get("name") : a.get("name"));
                x.put("description", a.get("description"));
                x.put("inputs", ins);
                x.put("outputs", outs);
                out.add(x);
            } catch (RuntimeException e) { /* an unreadable or deleted agent: left out */ }
            if (out.size() == 200) break;
        }
        return out;
    }

    /** Checks a whole agent, or steps inside an empty one, the way Studio and ooa validate do. */
    @SuppressWarnings("unchecked")
    Map<String, Object> check(Map<String, Object> b) {
        Map<String, Object> flow;
        if (b.get("flow") instanceof Map<?, ?> f) flow = (Map<String, Object>) f;
        else if (b.get("steps") instanceof List<?> steps) {
            flow = new LinkedHashMap<>();
            flow.put("kind", "TaskBot");
            flow.put("id", "assistant_check");
            flow.put("name", "Assistant check");
            flow.put("variables", b.get("variables") instanceof List<?> v ? v : List.of());
            flow.put("steps", steps);
        } else throw new OoaException("CR.BAD_INPUT", "Send flow (a whole agent) or steps", "For example {\"steps\": [...]}.");
        List<Map<String, Object>> problems;
        try { problems = ctx.checkAgent(flow); }
        catch (OoaException e) { problems = List.of(Map.of("code", e.code(), "message", e.getMessage())); }
        return Map.of("problems", problems, "ok", problems.isEmpty());
    }

    // ---- S5b: what happens later, told to the service as events (section 6) ----

    /** A run the service started has ended: one run_ended event, whichever node hears it first. */
    @SuppressWarnings("unchecked")
    void runEnded(String runId) {
        Map<String, Object> w = ctx.db().one("SELECT username, session_id FROM onechat_runs WHERE run_id = ?", runId);
        Map<String, Object> r = ctx.db().one("SELECT status, outputs, error FROM runs WHERE id = ?", runId);
        if (w == null || r == null || !List.of("success", "failed", "stopped").contains(String.valueOf(r.get("status")))) return;
        if (ctx.db().update("DELETE FROM onechat_runs WHERE run_id = ?", runId) == 0) return;   // told already
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "run_ended");
        e.put("run_id", runId);
        e.put("status", r.get("status"));
        try { e.put("results", r.get("outputs") instanceof String o ? JsonLite.parse(o) : Map.of()); } catch (RuntimeException x) { e.put("results", Map.of()); }
        try { if (r.get("error") instanceof String er) e.put("error", JsonLite.parse(er)); } catch (RuntimeException x) { e.put("error", Map.of("message", String.valueOf(r.get("error")))); }
        service.enqueue(String.valueOf(w.get("username")), (String) w.get("session_id"), e);
    }

    /** On the clock: runs whose end was missed, then the events that are due; old confirmations go. */
    void tick() {
        for (Map<String, Object> r : ctx.db().query("SELECT o.run_id FROM onechat_runs o JOIN runs r ON r.id = o.run_id WHERE r.status IN ('success', 'failed', 'stopped')"))
            runEnded(String.valueOf(r.get("run_id")));
        ctx.db().update("DELETE FROM onechat_confirmations WHERE expires < ?", now().minus(Duration.ofDays(1)));
        ctx.db().update("DELETE FROM onechat_runs WHERE created < ?", now().minus(Duration.ofDays(7)));
        service.deliverEvents();
    }

    /**
     * An approver decided a high-impact request the service made (Task inbox, chat_approval): done now as the person who
     * asked, if they still may; they get a notice, and the service an approval_decided event. Returns the run id.
     */
    @SuppressWarnings("unchecked")
    String decided(Map<String, Object> task, User approver, String outcome, String comment, String ip) {
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) task.get("data"));
        String taskId = String.valueOf(task.get("id")), requester = String.valueOf(data.get("requested_by")), target = String.valueOf(data.get("agent"));
        String session = data.get("session_id") == null ? null : String.valueOf(data.get("session_id"));
        Map<String, Object> inputs = data.get("inputs") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of();
        boolean approved = "approved".equals(outcome);
        String name = target.replaceAll("^.*[/:]", "").replaceAll("\\.flow\\.json$", ""), runId = null, message;
        if (!approved) message = "Rejected by " + approver.username() + (comment == null || comment.isBlank() ? "." : ": " + comment);
        else {
            User asker = ctx.activeUser(requester);
            ChatPlatform.Person as = asker == null ? null : new ChatPlatform.Person(asker.username(), asker.role(), ip);
            if (asker == null) message = "Approved by " + approver.username() + ", but " + requester + " no longer has an active account, so nothing was done.";
            else if (target.startsWith(Conversation.TOOL_PREFIX)) {
                String id = target.substring(Conversation.TOOL_PREFIX.length());
                Map<String, Object> t = "never".equals(chat.policy(as, "admin_high")) ? null
                        : ctx.adminTools(asker).stream().filter(x -> id.equals(x.get("id"))).findFirst().orElse(null);
                if (t == null) message = "Approved by " + approver.username() + ", but " + requester + " may no longer do this, so nothing was done.";
                else {
                    name = String.valueOf(t.get("name"));
                    try { message = "Approved by " + approver.username() + " and done: " + ctx.runAdminTool(asker, id, inputs, ip).getOrDefault("text", ""); }
                    catch (OoaException e) { message = "Approved by " + approver.username() + ", but it could not be done: " + e.getMessage(); }
                }
            } else {
                ChatBot bot = "never".equals(chat.policy(as, "agents")) ? null : chat.agents(as).stream().filter(x -> x.path().equals(target)).findFirst().orElse(null);
                if (bot == null) message = "Approved by " + approver.username() + ", but " + requester + " may no longer run it, so nothing was started.";
                else {
                    name = bot.name();
                    try {
                        runId = chat.startRun(as, target, inputs, "onechat");
                        ctx.db().update("INSERT INTO onechat_runs (run_id, username, session_id, created) VALUES (?,?,?,?)", runId, requester, session, now());
                        message = "Approved by " + approver.username() + "; run " + runId + " started.";
                    } catch (ChatPlatform.Refused e) { message = "Approved by " + approver.username() + ", but the run did not start: " + e.getMessage(); }
                }
            }
        }
        Map<String, Object> details = new LinkedHashMap<>(Map.of("target", target, "requested_by", requester));
        if (runId != null) details.put("run", runId);
        ctx.audit(approver.username(), approved ? "onechat.approved" : "onechat.rejected", "task", taskId, details, "ok", ip);
        chat.notify(requester, (approved ? "Approved: " : "Rejected: ") + name, message, runId == null ? Map.of("agent", target) : Map.of("agent", target, "run_id", runId));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "approval_decided");
        e.put("task_id", taskId);
        e.put("decision", approved ? "approved" : "rejected");
        e.put("approver", approver.username());
        if (comment != null && !comment.isBlank()) e.put("comment", comment);
        e.put("message", message);
        if (runId != null) e.put("run_id", runId);
        service.enqueue(requester, session, e);
        return runId;
    }
}
