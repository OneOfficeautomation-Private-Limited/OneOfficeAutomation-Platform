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
 */
public final class OneChatModule implements CommandCenterModule {
    public static final String VERSION = "1.0.0";

    public String id() { return "one-chat"; }
    public String name() { return "One Chat"; }
    public String version() { return VERSION; }

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
            } else if (m.equals("GET") && !route.isEmpty() && !route.contains("/")) {
                ctx.sendJson(ex, 200, Map.of("session_id", route, "messages", chat.history(u, route)));
            } else throw ModuleContext.notFound("The route /api/chat/" + route);
        });
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
