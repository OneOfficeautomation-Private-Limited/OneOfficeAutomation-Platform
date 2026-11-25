package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;

/**
 * Studio's assistant while Studio is signed in to One CommandCenter (S2, 20 Nov 2026): "Build from a description" sends
 * its instructions (the step format and Studio's command list) and the request here, and the answer comes from the
 * company's Model Connection with the Studio assistant's settings (Settings > AI assistants > Studio assistant) and
 * the person's own choices, so masking, budgets and the record of each call apply as for every model call. Studio
 * still checks the suggested steps before it shows them, and on its own (not signed in) it keeps its own connections.
 * Development only: it answers with text and changes nothing.
 */
final class StudioAssistant {
    /** The most Studio may send: its instructions with the whole command list, and the request with the agent's variables. */
    static final int MAX_SYSTEM = 1_000_000, MAX_PROMPT = 200_000;

    private final AssistantSettings settings;
    private final ChatModel model;
    private final OneChatService service;
    private final OneChatModelCheck onechat;

    StudioAssistant(AssistantSettings settings, ChatModel model, OneChatService service, OneChatModelCheck onechat) {
        this.settings = settings; this.model = model; this.service = service; this.onechat = onechat;
    }

    /**
     * What Studio's dialog needs before it asks (S6, design A): whether the Studio assistant's model is a OneChat model,
     * so the dialog offers Auto, Fast, Smart and Ultra, and the mode it starts on.
     */
    Map<String, Object> info(ModuleContext ctx, User u) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean viaService = service != null && service.chosen();
        Map<String, Object> c = viaService ? null : onechat.connection(u, settings.effective(u, "studio").connection());
        out.put("engine", viaService ? "service" : "connection");
        out.put("connection", c == null ? null : c.get("name"));
        out.put("onechat_model", c != null && OneChatModelCheck.isOneChat(c));
        out.put("mode", String.valueOf(ctx.setting("studio_onechat_mode")));
        return out;
    }

    /**
     * {text}: the answer to Studio's request, for a person who may build agents. With the OneChat service chosen (S5),
     * the service answers in the Studio area, with Studio's instructions, the open agent and the selected steps as
     * context; it reaches models and Studio's commands only through the callbacks, and Studio still checks the steps.
     */
    Map<String, Object> complete(ModuleContext ctx, User u, Map<String, Object> body, String ip) {
        ctx.require(u, "repo.checkin", "use Studio's assistant");
        String system = body.get("system") == null ? "" : String.valueOf(body.get("system"));
        String prompt = body.get("prompt") == null ? "" : String.valueOf(body.get("prompt"));
        String mode = body.get("mode") == null ? null : String.valueOf(body.get("mode"));
        if (mode != null) OneChatModelCheck.withMode(mode);
        if (prompt.isBlank()) throw new OoaException("ASSISTANT.EMPTY", "Describe what the agent should do", "Describe what the agent should do in a sentence or two.");
        if (system.length() > MAX_SYSTEM || prompt.length() > MAX_PROMPT)
            throw new OoaException("ASSISTANT.TOO_LONG", "The request to the assistant is too long", "Describe a smaller part of the agent at a time.");
        if (service != null && service.chosen()) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("instructions", system);
            if (body.get("agent") instanceof Map<?, ?> a) context.put("agent", a);
            if (body.get("selected_steps") instanceof List<?> l) context.put("selected_steps", l);
            try {
                return Map.of("text", service.studio(u, context, prompt, ip));
            } catch (OoaException e) {
                // S6: the service cannot answer now; the Studio assistant's Model Connection answers instead, with a notice
                if (!OneChatService.unavailable(e) || !service.fallsBack()) throw e;
                Map<String, Object> out = new LinkedHashMap<>(viaConnection(ctx, u, system, prompt, mode));
                out.put("engine", "builtin");
                out.put("notice", "The OneChat service is not available just now, so the Studio assistant's own model answered.");
                return out;
            }
        }
        return viaConnection(ctx, u, system, prompt, mode);
    }

    /** The Studio assistant's Model Connection, with the company's settings and the person's choices. */
    private Map<String, Object> viaConnection(ModuleContext ctx, User u, String system, String prompt, String mode) {
        AssistantSettings.Effective e = settings.effective(u, "studio");
        settings.checkAllowance(u, "studio");
        StringBuilder sys = new StringBuilder(system);
        if (!e.instructions().isEmpty()) sys.append("\n\n").append(e.instructions());
        if (e.language() != null) sys.append("\n\nWrite step names and notes in ").append(AssistantSettings.LANGUAGES.get(e.language()))
                .append("; keep command names, variable names and values as they are.");
        List<Map<String, Object>> messages = List.of(Map.of("role", "system", "content", sys.toString()), Map.of("role", "user", "content", prompt));
        // S6 (design A): a OneChat model gets the mode as its model name; when it fails, the fallback connection may answer
        Map<String, Object> c = onechat.connection(u, e.connection());
        boolean isOneChat = c != null && OneChatModelCheck.isOneChat(c);
        String modelName = isOneChat ? OneChatModelCheck.withMode(mode != null ? mode : String.valueOf(ctx.setting("studio_onechat_mode"))) : null;
        Map<String, Object> r;
        try {
            r = model.call(u, e.connection(), modelName, messages, List.of(), "studio_assistant", e.temperature(), e.maxTokens(), e.maskPii(), e.timeoutSeconds(), "Studio assistant");
        } catch (OoaException failed) {
            String fallback = String.valueOf(ctx.setting("studio_fallback_connection")).trim();
            if (!isOneChat || fallback.isEmpty() || fallback.equals("null") || fallback.equals(c.get("name")) || failed.code().equals("AI.BUDGET_REACHED")) throw failed;
            r = model.call(u, fallback, messages, List.of(), "studio_assistant", e.temperature(), e.maxTokens(), e.maskPii(), e.timeoutSeconds(), "Studio assistant");
            return Map.of("text", r.get("text") == null ? "" : String.valueOf(r.get("text")), "engine", "fallback",
                    "notice", "The OneChat model did not answer (" + failed.getMessage() + "), so the connection " + fallback + " answered.");
        }
        return Map.of("text", r.get("text") == null ? "" : String.valueOf(r.get("text")));
    }
}
