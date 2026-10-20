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
                case "channel_not_found" -> "Check the channel id and invite the agent to the channel.";
                case "not_in_channel" -> "Invite the agent to the channel (/invite @your-bot).";
                case "invalid_auth", "token_revoked" -> "The agent token is wrong or revoked; update it in the vault.";
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

    @Action(id = "update_message", name = "Update message", category = "Messages", capabilities = "network", description = "Edit a message the agent posted, e.g. to show progress.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "channel", type = OoaType.STRING, description = "Channel id.") String channel,
            @Input(name = "ts", type = OoaType.STRING, description = "Timestamp id of the message.") String ts,
            @Input(name = "text", type = OoaType.STRING, description = "New text.") String text,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        call(ctx, base, "chat.update", token, Map.of("channel", channel, "ts", ts, "text", text));
        return Map.of();
    }

    // ---- Direct messages and lookups ----

    /** Read methods take form-encoded arguments; Slack ignores JSON bodies for several of them. */
    private static Map<String, Object> form(ActionContext ctx, String base, String method, Object token, Map<String, Object> args) {
        Map<String, Object> r = HttpJson.req(P, "POST", base.replaceAll("/+$", "") + "/" + method).bearer(token)
                .bytes(HttpJson.query(args).getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/x-www-form-urlencoded").timeout(120).send(ctx).map();
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            String err = String.valueOf(r.get("error"));
            String fix = switch (err) {
                case "channel_not_found" -> "Check the channel id and invite the agent to the channel.";
                case "users_not_found", "user_not_found" -> "Check the user id or email address.";
                case "missing_scope" -> "Add the missing scope (" + r.get("needed") + ") to the Slack app and reinstall it.";
                case "invalid_auth", "token_revoked" -> "The agent token is wrong or revoked; update it in the vault.";
                default -> null;
            };
            throw new ActionException(P + "." + err.toUpperCase(Locale.ROOT), "Slack refused " + method + ": " + err, fix, "ratelimited".equals(err));
        }
        return r;
    }

    private static String userId(ActionContext ctx, String base, Object token, String user) {
        String u = user == null ? "" : user.trim();
        if (u.contains("@")) return String.valueOf(((Map<?, ?>) form(ctx, base, "users.lookupByEmail", token, Map.of("email", u)).get("user")).get("id"));
        if (!u.matches("^[UW][A-Z0-9]{2,20}$")) throw new ActionException(P + ".BAD_USER", "Invalid user '" + user + "'", "Give a member id such as U0123456789 or an email address.");
        return u;
    }

    private static String cursor(Map<String, Object> r) {
        Object meta = r.get("response_metadata");
        Object c = meta instanceof Map<?, ?> m ? m.get("next_cursor") : null;
        return c == null || String.valueOf(c).isEmpty() ? null : String.valueOf(c);
    }

    private static Object nested(Object map, String key) { return map instanceof Map<?, ?> m ? m.get(key) : null; }

    @Action(id = "send_direct_message", name = "Send direct message", category = "Messages", capabilities = "network", description = "Open a direct conversation with a person (by member id or email) and post a message there.")
    @Output(name = "ts", type = OoaType.STRING, description = "Message timestamp id.")
    @Output(name = "channel", type = OoaType.STRING, description = "Direct conversation id.")
    public Map<String, Object> sendDirect(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token (xoxb-...) from the vault.") Object token,
            @Input(name = "user", type = OoaType.STRING, example = "ann@example.com", description = "Member id (U...) or the person's email address.") String user,
            @Input(name = "text", type = OoaType.STRING, description = "Message text (Slack markdown).") String text,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        String id = userId(ctx, base, token, user);
        String channel = String.valueOf(nested(call(ctx, base, "conversations.open", token, Map.of("users", id)).get("channel"), "id"));
        return Out.of("ts", call(ctx, base, "chat.postMessage", token, Map.of("channel", channel, "text", text)).get("ts"), "channel", channel);
    }

    @Action(id = "get_channel_id_by_name", name = "Get channel id by name", category = "Channels", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "Find a channel's id from its name, e.g. #ops-alerts.")
    @Output(name = "channel_id", type = OoaType.STRING, description = "Channel id, e.g. C0123456789.")
    public Map<String, Object> channelId(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "name", type = OoaType.STRING, example = "ops-alerts", description = "Channel name, with or without the leading #.") String name,
            @Input(name = "include_private", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also look in private channels the agent belongs to.") boolean priv,
            @Input(name = "include_archived", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also look in archived channels.") boolean archived,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        String wanted = name.trim().replaceFirst("^#", "");
        String next = null;
        for (int page = 0; page < 200; page++) {
            Map<String, Object> args = new LinkedHashMap<>(Map.of("limit", 1000, "types", priv ? "public_channel,private_channel" : "public_channel", "exclude_archived", !archived));
            if (next != null) args.put("cursor", next);
            Map<String, Object> r = form(ctx, base, "conversations.list", token, args);
            for (Object o : (List<?>) r.getOrDefault("channels", List.of()))
                if (o instanceof Map<?, ?> c && wanted.equalsIgnoreCase(String.valueOf(c.get("name")))) return Out.of("channel_id", c.get("id"));
            if ((next = cursor(r)) == null) break;
        }
        throw new ActionException(P + ".CHANNEL_NOT_FOUND", "No channel named '" + wanted + "'", "Check the name; for a private channel, invite the agent first.");
    }

    @Action(id = "get_channel_details", name = "Get channel details", category = "Channels", idempotent = true, capabilities = "network", description = "Read a channel's name, topic, purpose, privacy and member count.")
    @Output(name = "channel", type = OoaType.DICTIONARY, description = "id, name, is_private, is_archived, topic, purpose, num_members, created.")
    public Map<String, Object> channelDetails(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "channel", type = OoaType.STRING, description = "Channel id.") String channel,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        Object c = form(ctx, base, "conversations.info", token, Map.of("channel", channel, "include_num_members", true)).get("channel");
        Map<String, Object> o = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "is_private", "is_archived", "is_im", "num_members", "created")) o.put(k, nested(c, k));
        o.put("topic", nested(nested(c, "topic"), "value"));
        o.put("purpose", nested(nested(c, "purpose"), "value"));
        return Map.of("channel", o);
    }

    @Action(id = "get_conversation_history", name = "Get conversation history", category = "Channels", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "Read recent messages of a channel or direct conversation into a table, newest first.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: ts, user, text, thread_ts, reply_count, subtype.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of messages.")
    public Map<String, Object> history(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "channel", type = OoaType.STRING, description = "Channel or conversation id.") String channel,
            @Input(name = "oldest", type = OoaType.STRING, required = false, description = "Only messages after this timestamp id (e.g. 1700000000.000100).") String oldest,
            @Input(name = "latest", type = OoaType.STRING, required = false, description = "Only messages before this timestamp id.") String latest,
            @Input(name = "max_messages", type = OoaType.NUMBER, required = false, defaultValue = "200", description = "Maximum messages to read.") int max,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        List<Object> rows = new ArrayList<>();
        String next = null;
        while (rows.size() < max) {
            Map<String, Object> args = new LinkedHashMap<>(Map.of("channel", channel, "limit", Math.min(200, max - rows.size())));
            if (oldest != null && !oldest.isBlank()) args.put("oldest", oldest);
            if (latest != null && !latest.isBlank()) args.put("latest", latest);
            if (next != null) args.put("cursor", next);
            Map<String, Object> r = form(ctx, base, "conversations.history", token, args);
            for (Object o : (List<?>) r.getOrDefault("messages", List.of())) {
                if (rows.size() >= max) break;
                rows.add(new ArrayList<>(Arrays.asList(nested(o, "ts"), nested(o, "user"), nested(o, "text"), nested(o, "thread_ts"), nested(o, "reply_count"), nested(o, "subtype"))));
            }
            if (!Boolean.TRUE.equals(r.get("has_more")) || (next = cursor(r)) == null) break;
        }
        return Map.of("table", com.oneofficeautomation.runner.Values.table(List.of("ts", "user", "text", "thread_ts", "reply_count", "subtype"), rows), "count", java.math.BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_user_details", name = "Get user details", category = "Users", idempotent = true, capabilities = "network", description = "Look up a person by member id or email and return their profile.")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "id, name, real_name, display_name, email, title, time_zone, is_bot, deleted.")
    public Map<String, Object> userDetails(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "user", type = OoaType.STRING, description = "Member id (U...) or email address.") String user,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        String u = user == null ? "" : user.trim();
        Object m = u.contains("@") ? form(ctx, base, "users.lookupByEmail", token, Map.of("email", u)).get("user")
                : form(ctx, base, "users.info", token, Map.of("user", userId(ctx, base, token, u))).get("user");
        Object p = nested(m, "profile");
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", nested(m, "id"));
        o.put("name", nested(m, "name"));
        o.put("real_name", nested(m, "real_name") != null ? nested(m, "real_name") : nested(p, "real_name"));
        o.put("display_name", nested(p, "display_name"));
        o.put("email", nested(p, "email"));
        o.put("title", nested(p, "title"));
        o.put("time_zone", nested(m, "tz"));
        o.put("is_bot", nested(m, "is_bot"));
        o.put("deleted", nested(m, "deleted"));
        return Map.of("user", o);
    }

    @Action(id = "get_workspace_details", name = "Get workspace details", category = "Workspace", idempotent = true, capabilities = "network", description = "Read the workspace's id, name and domain.")
    @Output(name = "workspace", type = OoaType.DICTIONARY, description = "id, name, domain, email_domain.")
    public Map<String, Object> workspace(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        Object t = form(ctx, base, "team.info", token, Map.of()).get("team");
        Map<String, Object> o = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "domain", "email_domain")) o.put(k, nested(t, k));
        return Map.of("workspace", o);
    }
}
