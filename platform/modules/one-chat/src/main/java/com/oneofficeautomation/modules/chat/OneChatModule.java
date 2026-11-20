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
 */
public final class OneChatModule implements CommandCenterModule {
    public static final String VERSION = "1.3.0";

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
                        "CREATE INDEX assistant_memory_user ON assistant_memory (username, assistant)"));
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
        // POST /api/chat {session_id?, message, lang} sends a message; GET /api/chat/{session} reads that chat's messages
        ctx.route("chat", (ex, u, m, route, ip) -> {
            if (m.equals("POST") && route.isEmpty()) {
                Map<String, Object> b = ctx.jsonBody(ex);
                ctx.sendJson(ex, 200, chat.message(u, (String) b.get("session_id"), (String) b.get("message"), (String) b.get("lang"), ip));
            } else if (m.equals("GET") && route.isEmpty()) {
                // S4: the person's chats, newest first, for One Chat's Your chats
                ctx.sendJson(ex, 200, Map.of("chats", chat.chats(u)));
            } else if (m.equals("GET") && !route.isEmpty() && !route.contains("/")) {
                ctx.sendJson(ex, 200, Map.of("session_id", route, "messages", chat.history(u, route)));
            } else throw ModuleContext.notFound("The route /api/chat/" + route);
        });

        // the AI assistants' settings (S2): Settings > AI assistants, My account > AI assistants, Studio's assistant
        AssistantSettings.define(ctx);
        StudioAssistant studio = new StudioAssistant(chat.settings(), new ChatModel(ctx, ctx.dataDir()));
        // GET me: the person's own choices and what is allowed; PUT me/{chat|studio}: save them; GET options: what an
        // administrator may choose from; POST studio/complete: Studio's assistant while Studio is signed in
        ctx.route("assistants", (ex, u, m, route, ip) -> {
            if (m.equals("GET") && route.equals("me")) ctx.sendJson(ex, 200, chat.settings().mine(u));
            else if (m.equals("PUT") && route.startsWith("me/")) ctx.sendJson(ex, 200, chat.settings().savePrefs(u, route.substring(3), ctx.jsonBody(ex), ip));
            else if (m.equals("DELETE") && route.equals("me/memory")) ctx.sendJson(ex, 200, chat.settings().forget(u, null, ip));
            else if (m.equals("DELETE") && route.startsWith("me/memory/")) ctx.sendJson(ex, 200, chat.settings().forget(u, route.substring("me/memory/".length()), ip));
            else if (m.equals("GET") && route.equals("options")) {
                ctx.require(u, "settings.manage", "see the AI assistants' choices");
                ctx.sendJson(ex, 200, chat.settings().options());
            } else if (m.equals("POST") && route.equals("studio/complete")) ctx.sendJson(ex, 200, studio.complete(ctx, u, ctx.jsonBody(ex)));
            else throw ModuleContext.notFound("The route /api/assistants/" + route);
        });
        // people deleted since: their own choices go too
        ctx.every(Duration.ofMinutes(5), Duration.ofHours(1), chat.settings()::forgetDeletedPeople);
        // a run the chat started finished: tell the chat at once; the clock catches anything missed
        ctx.onRunEnded((run, status, result) -> { if ("chat".equals(run.get("source"))) chat.deliverRunUpdates(); });
        ctx.every(Duration.ofSeconds(5), Duration.ofSeconds(15), chat::deliverRunUpdates);
        // R3 approvals in the Task inbox: never your own request, administrators included
        ctx.taskStage(Chat.APPROVAL_STAGE, new ModuleContext.TaskStage() {
            public void checkComplete(User u, Map<String, Object> task) {
                if (u.username().equals(Chat.requester(task)))
                    throw new OoaException("CR.FORBIDDEN", "You cannot approve your own request", "Someone else among the approvers decides it.");
            }
            public String decided(Map<String, Object> task, User u, String outcome, String comment, String ip) {
                return chat.decided(task, u, outcome, comment, ip);
            }
        });
    }
}
