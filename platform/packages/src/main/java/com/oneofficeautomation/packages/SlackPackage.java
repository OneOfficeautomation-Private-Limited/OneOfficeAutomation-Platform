package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.util.*;

/** Slack Web API with a bot token (xoxb-...). Slack answers HTTP 200 with ok=false on errors; those become typed errors. */
@OoaPackage(id = "ooa.slack", name = "Slack", version = "1.0.0", description = "Post messages to Slack channels and users.")
public final class SlackPackage {
    static final String P = "SLACK";

    private static Map<String, Object> call(ActionContext ctx, String base, String method, Object token, Map<String, Object> body) {
        Map<String, Object> r = HttpJson.req(P, "POST", base.replaceAll("/+$", "") + "/" + method).bearer(token).json(body).send(ctx).map();
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            String err = String.valueOf(r.get("error"));
            String fix = switch (err) {
                case "channel_not_found" -> "Check the channel id and invite the bot to the channel.";
                case "not_in_channel" -> "Invite the bot to the channel (/invite @your-bot).";
                case "invalid_auth", "token_revoked" -> "The bot token is wrong or revoked; update it in the vault.";
                default -> null;
            };
            throw new ActionException(P + "." + err.toUpperCase(Locale.ROOT), "Slack refused " + method + ": " + err, fix, "ratelimited".equals(err));
        }
        return r;
    }

    @Action(id = "post_message", name = "Post message", category = "Messages", capabilities = "network", description = "Post a message to a channel (or a user's DM), optionally as a thread reply.")
    @Output(name = "ts", type = OoaType.STRING, description = "Message timestamp id (use it to reply in a thread).")
    public Map<String, Object> post(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token (xoxb-...) from the vault.") Object token,
            @Input(name = "channel", type = OoaType.STRING, example = "C0123456789", description = "Channel id, or a user id for a direct message.") String channel,
            @Input(name = "text", type = OoaType.STRING, description = "Message text (Slack markdown).") String text,
            @Input(name = "thread_ts", type = OoaType.STRING, required = false, description = "Reply in the thread of this message.") String thread,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("channel", channel, "text", text));
        if (thread != null) body.put("thread_ts", thread);
        return Out.of("ts", call(ctx, base, "chat.postMessage", token, body).get("ts"));
    }

    @Action(id = "update_message", name = "Update message", category = "Messages", capabilities = "network", description = "Edit a message the bot posted, e.g. to show progress.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "channel", type = OoaType.STRING, description = "Channel id.") String channel,
            @Input(name = "ts", type = OoaType.STRING, description = "Timestamp id of the message.") String ts,
            @Input(name = "text", type = OoaType.STRING, description = "New text.") String text,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        call(ctx, base, "chat.update", token, Map.of("channel", channel, "ts", ts, "text", text));
        return Map.of();
    }
}
