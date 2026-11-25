package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/**
 * One Chat, a feature module of One CommandCenter (docs/architecture/13-modules.md): AI > One Chat, /api/chat. People
 * run agents and check their runs in plain words; R3 agents wait for someone else's approval in the Task inbox (task
 * stage chat_approval). The chat engine is the conversation library. Its tables chat_sessions and chat_runs are in the
 * kernel's frozen baseline (version 45).
 *
 * <p>Since 1.1.0 (S2, 20 Nov 2026) it also keeps the AI assistants' settings (AssistantSettings) for One Chat and
 * Studio's assistant, each person's own choices (/api/assistants/me), and answers Studio's assistant while Studio is
 * signed in (/api/assistants/studio/complete).
 *
 * <p>Since 1.4.0 (S5, 20 Nov 2026) One Chat can use the OneChat service instead of its built-in engine (Settings > AI
 * assistants > One Chat > Engine; docs/integration/one-chat-contract.md): messages, chats and notes go to the service
 * with a person token (PersonTokens, OneChatService), and the service calls back on /api/onechat-callback/v1/
 * (OneChatCallbacks). The built-in engine stays the default.
 */
public final class OneChatModule implements CommandCenterModule {
    public static final String VERSION = "1.4.0";

    public String id() { return "one-chat"; }
    public String name() { return "One Chat"; }
    public String version() { return VERSION; }

    /** The module's own tables. Version 1 (S2, 20 Nov 2026): assistant_prefs, each person's own choices per assistant; version 2 (S3): its ask_first. */
    public java.util.List<java.util.List<String>> schema() {
        return java.util.List.of(java.util.List.of(
                "CREATE TABLE assistant_prefs (username VARCHAR(80) NOT NULL, assistant VARCHAR(20) NOT NULL, model VARCHAR(60), language VARCHAR(10), "
                        + "instructions VARCHAR(2000), updated TIMESTAMP NOT NULL, PRIMARY KEY (username, assistant))"),
                // version 2 (S3): the person's "always ask me first" for One Chat
                java.util.List.of("ALTER TABLE assistant_prefs ADD COLUMN ask_first BOOLEAN"),
                // version 3 (S4): the notes a person asked One Chat to remember
                java.util.List.of("CREATE TABLE assistant_memory (id VARCHAR(40) PRIMARY KEY, username VARCHAR(80) NOT NULL, assistant VARCHAR(20) NOT NULL, "
                        + "note VARCHAR(300) NOT NULL, created TIMESTAMP NOT NULL)",
                        "CREATE INDEX assistant_memory_user ON assistant_memory (username, assistant)"),
                // version 4 (S5): the OneChat service: the person-token key, confirmations, the runs it started, events to send
                java.util.List.of("CREATE TABLE onechat_keys (id VARCHAR(40) PRIMARY KEY, secret VARCHAR(400) NOT NULL, created TIMESTAMP NOT NULL)",
                        "CREATE TABLE onechat_confirmations (id VARCHAR(40) PRIMARY KEY, username VARCHAR(80) NOT NULL, session_id VARCHAR(80), kind VARCHAR(10) NOT NULL, "
                                + "target VARCHAR(400) NOT NULL, name VARCHAR(200), input_hash VARCHAR(64) NOT NULL, risk VARCHAR(4), reason VARCHAR(300), undo VARCHAR(300), "
                                + "issued_at BIGINT NOT NULL, token_id VARCHAR(40) NOT NULL, expires TIMESTAMP NOT NULL, used BOOLEAN NOT NULL)",
                        "CREATE TABLE onechat_runs (run_id VARCHAR(40) PRIMARY KEY, username VARCHAR(80) NOT NULL, session_id VARCHAR(80), created TIMESTAMP NOT NULL)",
                        "CREATE TABLE onechat_events (id VARCHAR(40) PRIMARY KEY, username VARCHAR(80) NOT NULL, session_id VARCHAR(80), payload VARCHAR(100000) NOT NULL, "
                                + "attempts INT NOT NULL, next_try TIMESTAMP NOT NULL, created TIMESTAMP NOT NULL)",
                        "CREATE INDEX onechat_events_due ON onechat_events (next_try)"));
    }

    public void start(ModuleContext ctx) {
        ctx.defineSetting("chat_runner", "", null);
        ctx.defineSetting("chat_mode", "deterministic", (v, saas) -> {
            if (!Chat.MODES.contains(v)) throw new OoaException("CR.BAD_INPUT", "One Chat's mode is one of " + String.join(", ", Chat.MODES));
        });
        ctx.defineSetting("chat_connection", "", null);
        ctx.defineSetting("chat_approvers", "role:admin", (v, saas) -> {
            if (!v.isEmpty() && !Arrays.stream(v.split(",")).map(String::trim).allMatch(a -> a.matches("(user|role):[^\\s,]+")))
                throw new OoaException("CR.BAD_INPUT", "Approvers are user:name or role:name entries, comma separated", "For example role:admin, user:fin.lead");
        });
        // lines role=mode (deterministic, llm, hybrid) that override chat_mode for that main role
        ctx.defineSetting("chat_mode_by_role", "", (v, saas) -> {
            if (v.isEmpty()) return;
            for (String line : v.split("[\\n,;]")) {
                if (line.isBlank()) continue;
                String[] kv = line.split("=", 2);
                if (kv.length != 2 || !ctx.roleExists(kv[0].trim()) || !Chat.MODES.contains(kv[1].trim()))
                    throw new OoaException("CR.BAD_INPUT", "Each line is role=mode, with an existing role and a mode of " + String.join(", ", Chat.MODES),
                            "For example: bot_runner=deterministic, admin=hybrid");
            }
        });

        Chat chat = new Chat(ctx, ctx.dataDir());
        ctx.provide(Chat.class, chat);
        // S5: the OneChat service, when Settings > AI assistants > One Chat > Engine chooses it
        OneChatService.define(ctx);
        OneChatService.defineFallback(ctx);
        PersonTokens tokens = new PersonTokens(ctx);
        OneChatService service = new OneChatService(ctx, tokens);
        ChatModel models = new ChatModel(ctx, ctx.dataDir());
        OneChatCallbacks callbacks = new OneChatCallbacks(ctx, chat, models, service, tokens);
        ctx.publicRoute("onechat-callback", callbacks::handle);
        // POST /api/chat {session_id?, message, lang} sends a message; GET /api/chat/{session} reads that chat's messages;
        // both go to the engine chosen in Settings (the built-in one, or the OneChat service)
        ctx.route("chat", (ex, u, m, route, ip) -> {
            boolean viaService = service.chosen();
            if (m.equals("POST") && route.isEmpty()) {
                Map<String, Object> b = ctx.jsonBody(ex);
                if (!viaService) {
                    ctx.sendJson(ex, 200, chat.message(u, (String) b.get("session_id"), (String) b.get("message"), (String) b.get("lang"), ip));
                    return;
                }
                String text = b.get("message") == null ? "" : String.valueOf(b.get("message")).trim();
                if (text.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Type a message", "Type what you want in a sentence.");
                if (text.length() > 4000) throw new OoaException("CR.BAD_INPUT", "The message is too long (at most 4,000 characters)", "Shorten the message.");
                String answerIn = chat.settings().language(u, "chat");
                try {
                    ctx.sendJson(ex, 200, service.message(u, (String) b.get("session_id"), text, answerIn != null ? answerIn : (String) b.get("lang"), ip));
                } catch (OoaException e) {
                    // S6: the service cannot answer now; the built-in engine answers, with a notice (never silently)
                    if (!OneChatService.unavailable(e)) throw e;
                    Map<String, Object> r = new java.util.LinkedHashMap<>(chat.message(u, null, text, (String) b.get("lang"), ip));
                    r.put("engine", "builtin");
                    r.put("notice", service.fallbackNotice());
                    ctx.sendJson(ex, 200, r);
                }
            } else if (m.equals("GET") && route.isEmpty()) {
                // S4: the person's chats, newest first, for One Chat's Your chats
                ctx.sendJson(ex, 200, Map.of("chats", viaService ? orBuiltin(service, () -> service.chats(u), () -> chat.chats(u)) : chat.chats(u)));
            } else if (m.equals("GET") && !route.isEmpty() && !route.contains("/")) {
                ctx.sendJson(ex, 200, Map.of("session_id", route, "messages",
                        viaService ? orBuiltin(service, () -> service.history(u, route), () -> chat.history(u, route)) : chat.history(u, route)));
            } else throw ModuleContext.notFound("The route /api/chat/" + route);
        });
        // GET /api/onechat/version: the OneChat service's version and whether it works with this release (Settings)
        // GET /api/onechat/model-check: whether OneChat model servers behind Model Connections fit this release (S6)
        OneChatModelCheck.define(ctx);
        OneChatModelCheck modelCheck = new OneChatModelCheck(ctx);
        ctx.route("onechat", (ex, u, m, route, ip) -> {
            if (m.equals("GET") && route.equals("model-check")) {
                ctx.require(u, "settings.manage", "check the OneChat model servers");
                ctx.sendJson(ex, 200, modelCheck.check(u));
                return;
            }
            if (!(m.equals("GET") && route.equals("version"))) throw ModuleContext.notFound("The route /api/onechat/" + route);
            ctx.require(u, "settings.manage", "see the OneChat service's version");
            if ("1".equals(ctx.query(ex).get("fresh"))) service.resume();
            Map<String, Object> v = new java.util.LinkedHashMap<>(service.version());
            v.put("engine", service.chosen() ? "service" : "builtin");
            v.put("url", service.baseUrl());
            // S6: the circuit breaker and the fallback, and which engine answers right now
            v.putAll(service.health());
            boolean serviceAnswers = service.chosen() && !service.paused() && Boolean.TRUE.equals(v.get("compatible"));
            v.put("answering", !service.chosen() || serviceAnswers ? (service.chosen() ? "service" : "builtin") : service.fallsBack() ? "builtin" : "none");
            ctx.sendJson(ex, 200, v);
        });

        // the AI assistants' settings (S2): Settings > AI assistants, My account > AI assistants, Studio's assistant
        AssistantSettings.define(ctx);
        StudioAssistant studio = new StudioAssistant(chat.settings(), models, service, modelCheck);
        // GET me: the person's own choices and what is allowed; PUT me/{chat|studio}: save them; GET options: what an
        // administrator may choose from; POST studio/complete: Studio's assistant while Studio is signed in
        ctx.route("assistants", (ex, u, m, route, ip) -> {
            if (m.equals("GET") && route.equals("me")) ctx.sendJson(ex, 200, service.chosen() ? withServiceNotes(chat.settings().mine(u), service, u) : chat.settings().mine(u));
            else if (m.equals("PUT") && route.startsWith("me/")) ctx.sendJson(ex, 200, chat.settings().savePrefs(u, route.substring(3), ctx.jsonBody(ex), ip));
            else if (m.equals("DELETE") && (route.equals("me/memory") || route.startsWith("me/memory/")) && service.chosen()) {
                // S5: with the OneChat service, it keeps the notes; forgetting them goes to it
                String id = route.equals("me/memory") ? null : route.substring("me/memory/".length());
                service.forget(u, id);
                ctx.audit(u.username(), id == null ? "assistant.memory_cleared" : "assistant.memory_forgot", "user", u.username(), Map.of("engine", "service"), "ok", ip);
                ctx.sendJson(ex, 200, withServiceNotes(chat.settings().mine(u), service, u));
            }
            else if (m.equals("DELETE") && route.equals("me/memory")) ctx.sendJson(ex, 200, chat.settings().forget(u, null, ip));
            else if (m.equals("DELETE") && route.startsWith("me/memory/")) ctx.sendJson(ex, 200, chat.settings().forget(u, route.substring("me/memory/".length()), ip));
            else if (m.equals("GET") && route.equals("options")) {
                ctx.require(u, "settings.manage", "see the AI assistants' choices");
                ctx.sendJson(ex, 200, chat.settings().options());
            } else if (m.equals("POST") && route.equals("studio/complete")) ctx.sendJson(ex, 200, studio.complete(ctx, u, ctx.jsonBody(ex), ip));
            else if (m.equals("GET") && route.equals("studio/info")) {
                // S6 (design A): whether Studio's dialog offers OneChat's modes, and the one it starts on
                ctx.require(u, "repo.checkin", "use Studio's assistant");
                ctx.sendJson(ex, 200, studio.info(ctx, u));
            }
            else if (m.equals("POST") && route.equals("studio/feedback")) {
                // S6: what the person did with the suggestion, to the OneChat model that made it (only when Settings allow it)
                ctx.require(u, "repo.checkin", "use Studio's assistant");
                ctx.sendJson(ex, 200, modelCheck.feedback(u, chat.settings().effective(u, "studio").connection(), ctx.jsonBody(ex)));
            }
            else throw ModuleContext.notFound("The route /api/assistants/" + route);
        });
        // people deleted since: their own choices go too
        ctx.every(Duration.ofMinutes(5), Duration.ofHours(1), chat.settings()::forgetDeletedPeople);
        // a run the chat started finished: tell the chat at once; the clock catches anything missed
        ctx.onRunEnded((run, status, result) -> {
            if ("chat".equals(run.get("source"))) chat.deliverRunUpdates();
            // S5: a run the OneChat service started: a run_ended event for it
            if ("onechat".equals(run.get("source"))) callbacks.runEnded(String.valueOf(run.get("id")));
        });
        ctx.every(Duration.ofSeconds(5), Duration.ofSeconds(15), chat::deliverRunUpdates);
        // S5: run ends the listener missed, and the events due to the OneChat service (retried for up to an hour)
        ctx.every(Duration.ofSeconds(7), Duration.ofSeconds(5), callbacks::tick);
        // R3 approvals in the Task inbox: never your own request, administrators included
        ctx.taskStage(Chat.APPROVAL_STAGE, new ModuleContext.TaskStage() {
            public void checkComplete(User u, Map<String, Object> task) {
                if (u.username().equals(Chat.requester(task)))
                    throw new OoaException("CR.FORBIDDEN", "You cannot approve your own request", "Someone else among the approvers decides it.");
            }
            public String decided(Map<String, Object> task, User u, String outcome, String comment, String ip) {
                // S5: a request the OneChat service made is done here, and the service hears the decision as an event
                Object data;
                try { data = task.get("data") instanceof String d ? com.oneofficeautomation.sdk.JsonLite.parse(d) : null; } catch (RuntimeException e) { data = null; }
                if (data instanceof Map<?, ?> dm && "onechat".equals(dm.get("source"))) return callbacks.decided(task, u, outcome, comment, ip);
                return chat.decided(task, u, outcome, comment, ip);
            }
        });
    }

    /**
     * The service's answer, or the built-in engine's when the service cannot answer now and fallback is on (S6). A
     * refusal of the request itself (ONECHAT.ERROR) is shown as it is.
     */
    static <T> T orBuiltin(OneChatService service, java.util.function.Supplier<T> viaService, java.util.function.Supplier<T> builtin) {
        try { return viaService.get(); }
        catch (OoaException e) {
            if (OneChatService.unavailable(e) && service.fallsBack()) return builtin.get();
            throw e;
        }
    }

    /** My account's notes when the OneChat service keeps them: the service's list (or none, with why, when it does not answer). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withServiceNotes(Map<String, Object> mine, OneChatService service, User u) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(mine);
        Map<String, Object> memory = new java.util.LinkedHashMap<>((Map<String, Object>) mine.get("memory"));
        try { memory.put("notes", service.notes(u)); }
        catch (OoaException e) { memory.put("notes", java.util.List.of()); memory.put("notes_error", e.getMessage()); }
        memory.put("kept_by", "service");
        out.put("memory", memory);
        return out;
    }
}
