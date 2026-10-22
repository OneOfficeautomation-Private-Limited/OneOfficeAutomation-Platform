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
            @Input(name = "reply_broadcast", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Thread replies only: also show the reply in the channel.") boolean broadcast,
            @Input(name = "blocks", required = false, description = "Block Kit layout: a List of blocks or its JSON text; the text is then the notification fallback.") Object blocks,
            @Input(name = "unfurl_links", type = OoaType.BOOLEAN, required = false, description = "Show previews of linked pages; Slack decides when empty.") Boolean unfurlLinks,
            @Input(name = "unfurl_media", type = OoaType.BOOLEAN, required = false, description = "Show previews of linked images and videos; Slack decides when empty.") Boolean unfurlMedia,
            @Input(name = "mrkdwn", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Format the text as Slack markdown; false sends it as typed.") boolean mrkdwn,
            @Input(name = "username", type = OoaType.STRING, required = false, description = "Name shown as the sender (needs the chat:write.customize scope).") String username,
            @Input(name = "icon_emoji", type = OoaType.STRING, required = false, example = ":robot_face:", description = "Emoji shown as the sender's picture (needs chat:write.customize).") String icon,
            @Input(name = "files", type = OoaType.LIST, required = false, description = "Local files to share in the same conversation, right after the message.") List<Object> files,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("channel", channel, "text", text));
        if (thread != null) body.put("thread_ts", thread);
        if (broadcast && thread != null) body.put("reply_broadcast", true);
        messageOptions(body, blocks, unfurlLinks, unfurlMedia, mrkdwn);
        if (username != null && !username.isBlank()) body.put("username", username);
        if (icon != null && !icon.isBlank()) body.put("icon_emoji", icon);
        Map<String, Object> r = call(ctx, base, "chat.postMessage", token, body);
        if (files != null && !files.isEmpty()) upload(ctx, base, token, String.valueOf(r.getOrDefault("channel", channel)), files, null, thread);
        return Out.of("ts", r.get("ts"));
    }

    /** Blocks, link previews and markdown flag of a message; unset values are left to Slack. */
    static void messageOptions(Map<String, Object> body, Object blocks, Boolean unfurlLinks, Boolean unfurlMedia, boolean mrkdwn) {
        if (blocks != null && !(blocks instanceof String b && b.isBlank())) {
            Object parsed = blocks instanceof String b ? parseJson(b) : blocks;
            if (!(parsed instanceof List<?>)) throw new ActionException(P + ".BAD_BLOCKS", "blocks must be a list of Block Kit blocks", "Build the layout in Slack's Block Kit Builder and paste its blocks array.");
            body.put("blocks", parsed);
        }
        if (unfurlLinks != null) body.put("unfurl_links", unfurlLinks);
        if (unfurlMedia != null) body.put("unfurl_media", unfurlMedia);
        if (!mrkdwn) body.put("mrkdwn", false);
    }

    private static Object parseJson(String text) {
        try { return JsonLite.parse(text); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_BLOCKS", "blocks is not valid JSON: " + e.getMessage(), null); }
    }

    /** Shares local files in a conversation with Slack's external upload flow: get an upload address, send the bytes, then complete. */
    static List<Object> upload(ActionContext ctx, String base, Object token, String channel, List<Object> files, String comment, String thread) {
        List<Object> done = new ArrayList<>();
        for (Object f : files) {
            java.nio.file.Path p = ctx.resolvePath(com.oneofficeautomation.runner.Values.text(f));
            byte[] data = HttpJson.readFile(p);
            Map<String, Object> u = form(ctx, base, "files.getUploadURLExternal", token, Map.of("filename", p.getFileName().toString(), "length", data.length));
            HttpJson.req(P, "POST", String.valueOf(u.get("upload_url"))).bytes(data, "application/octet-stream").timeout(600).send(ctx);
            done.add(Map.of("id", u.get("file_id"), "title", p.getFileName().toString()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", done);
        body.put("channel_id", channel);
        if (comment != null && !comment.isBlank()) body.put("initial_comment", comment);
        if (thread != null && !thread.isBlank()) body.put("thread_ts", thread);
        call(ctx, base, "files.completeUploadExternal", token, body);
        List<Object> ids = new ArrayList<>();
        for (Object d : done) ids.add(((Map<?, ?>) d).get("id"));
        return ids;
    }

    @Action(id = "upload_file", name = "Upload file", category = "Messages", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Share one or more local files in a channel or direct conversation, with an optional comment or as a thread reply.")
    @Output(name = "file_ids", type = OoaType.LIST, description = "Slack file ids.")
    public Map<String, Object> uploadFile(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token (xoxb-...) from the vault; needs the files:write scope.") Object token,
            @Input(name = "channel", type = OoaType.STRING, example = "C0123456789", description = "Channel or conversation id.") String channel,
            @Input(name = "files", type = OoaType.LIST, description = "Local file paths.") List<Object> files,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Message posted with the files.") String comment,
            @Input(name = "thread_ts", type = OoaType.STRING, required = false, description = "Share in the thread of this message.") String thread,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        if (files == null || files.isEmpty()) throw new ActionException(P + ".NO_FILES", "Give at least one file to upload", null);
        return Map.of("file_ids", upload(ctx, base, token, channel, files, comment, thread));
    }

    @Action(id = "update_message", name = "Update message", category = "Messages", capabilities = "network", description = "Edit a message the agent posted, e.g. to show progress.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "channel", type = OoaType.STRING, description = "Channel id.") String channel,
            @Input(name = "ts", type = OoaType.STRING, description = "Timestamp id of the message.") String ts,
            @Input(name = "text", type = OoaType.STRING, description = "New text.") String text,
            @Input(name = "blocks", required = false, description = "New Block Kit layout: a List of blocks or its JSON text.") Object blocks,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("channel", channel, "ts", ts, "text", text));
        messageOptions(body, blocks, null, null, true);
        call(ctx, base, "chat.update", token, body);
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
            @Input(name = "user", type = OoaType.STRING, example = "ann@example.com", description = "Member id (U...) or the person's email address; several separated by , or ; open a group conversation.") String user,
            @Input(name = "text", type = OoaType.STRING, description = "Message text (Slack markdown).") String text,
            @Input(name = "thread_ts", type = OoaType.STRING, required = false, description = "Reply in the thread of this message.") String thread,
            @Input(name = "blocks", required = false, description = "Block Kit layout: a List of blocks or its JSON text; the text is then the notification fallback.") Object blocks,
            @Input(name = "unfurl_links", type = OoaType.BOOLEAN, required = false, description = "Show previews of linked pages; Slack decides when empty.") Boolean unfurlLinks,
            @Input(name = "unfurl_media", type = OoaType.BOOLEAN, required = false, description = "Show previews of linked images and videos; Slack decides when empty.") Boolean unfurlMedia,
            @Input(name = "mrkdwn", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Format the text as Slack markdown; false sends it as typed.") boolean mrkdwn,
            @Input(name = "files", type = OoaType.LIST, required = false, description = "Local files to share in the conversation, right after the message.") List<Object> files,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        List<String> ids = new ArrayList<>();
        for (String u : HttpJson.addressList(user)) ids.add(userId(ctx, base, token, u));
        if (ids.isEmpty()) throw new ActionException(P + ".BAD_USER", "No user given", "Give a member id or an email address.");
        String channel = String.valueOf(nested(call(ctx, base, "conversations.open", token, Map.of("users", String.join(",", ids))).get("channel"), "id"));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("channel", channel, "text", text));
        if (thread != null && !thread.isBlank()) body.put("thread_ts", thread);
        messageOptions(body, blocks, unfurlLinks, unfurlMedia, mrkdwn);
        Object ts = call(ctx, base, "chat.postMessage", token, body).get("ts");
        if (files != null && !files.isEmpty()) upload(ctx, base, token, channel, files, null, thread);
        return Out.of("ts", ts, "channel", channel);
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
            @Input(name = "thread_ts", type = OoaType.STRING, required = false, description = "Read the replies of this thread instead of the channel.") String thread,
            @Input(name = "inclusive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also include the messages exactly at oldest and latest.") boolean inclusive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        List<Object> rows = new ArrayList<>();
        String next = null;
        boolean replies = thread != null && !thread.isBlank();
        while (rows.size() < max) {
            Map<String, Object> args = new LinkedHashMap<>(Map.of("channel", channel, "limit", Math.min(200, max - rows.size())));
            if (replies) args.put("ts", thread.trim());
            if (inclusive) args.put("inclusive", true);
            if (oldest != null && !oldest.isBlank()) args.put("oldest", oldest);
            if (latest != null && !latest.isBlank()) args.put("latest", latest);
            if (next != null) args.put("cursor", next);
            Map<String, Object> r = form(ctx, base, replies ? "conversations.replies" : "conversations.history", token, args);
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

    @Action(id = "get_workspace_id_by_name", name = "Get workspace id by name", category = "Workspace", idempotent = true, capabilities = "network",
            description = "Find a workspace's id from its name or domain, among the workspaces the agent's app is installed in.")
    @Output(name = "workspace_id", type = OoaType.STRING, description = "Workspace (team) id, e.g. T0123456789.")
    public Map<String, Object> workspaceId(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "name", type = OoaType.STRING, description = "Workspace name or domain (not case-sensitive).") String name,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        String wanted = name.trim();
        List<String> seen = new ArrayList<>();
        try {
            String next = null;
            for (int page = 0; page < 50; page++) {
                Map<String, Object> args = new LinkedHashMap<>(Map.of("limit", 100));
                if (next != null) args.put("cursor", next);
                Map<String, Object> r = form(ctx, base, "auth.teams.list", token, args);
                for (Object t : (List<?>) r.getOrDefault("teams", List.of())) {
                    seen.add(String.valueOf(nested(t, "name")));
                    if (wanted.equalsIgnoreCase(String.valueOf(nested(t, "name"))) || wanted.equalsIgnoreCase(String.valueOf(nested(t, "domain")))) return Out.of("workspace_id", nested(t, "id"));
                }
                if ((next = cursor(r)) == null) break;
            }
        } catch (ActionException e) { if (!e.code().endsWith("NOT_ALLOWED_TOKEN_TYPE") && !e.code().endsWith("MISSING_SCOPE") && !e.code().endsWith("UNKNOWN_METHOD")) throw e; }
        Object t = form(ctx, base, "team.info", token, Map.of()).get("team");
        if (wanted.equalsIgnoreCase(String.valueOf(nested(t, "name"))) || wanted.equalsIgnoreCase(String.valueOf(nested(t, "domain")))) return Out.of("workspace_id", nested(t, "id"));
        seen.add(String.valueOf(nested(t, "name")));
        throw new ActionException(P + ".WORKSPACE_NOT_FOUND", "No workspace named '" + wanted + "'", "The agent's app is installed in: " + String.join(", ", new LinkedHashSet<>(seen)) + ".");
    }

    @Action(id = "get_workspace_details", name = "Get workspace details", category = "Workspace", idempotent = true, capabilities = "network", description = "Read the workspace's id, name and domain.")
    @Output(name = "workspace", type = OoaType.DICTIONARY, description = "id, name, domain, email_domain.")
    public Map<String, Object> workspace(ActionContext ctx,
            @Input(name = "bot_token", type = OoaType.CREDENTIAL, description = "Slack bot token from the vault.") Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, required = false, description = "Workspace (team) id, for apps installed in several workspaces; the token's own workspace when empty.") String team,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://slack.com/api\"", description = "API endpoint; change only for testing.") String base) {
        Object t = form(ctx, base, "team.info", token, team == null || team.isBlank() ? Map.of() : Map.of("team", team.trim())).get("team");
        Map<String, Object> o = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "domain", "email_domain")) o.put(k, nested(t, k));
        return Map.of("workspace", o);
    }
}
