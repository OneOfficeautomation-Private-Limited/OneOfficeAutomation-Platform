package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Microsoft 365 through Microsoft Graph: Outlook mail, calendar, OneDrive/SharePoint files and Teams messages.
 * Authentication: an OAuth 2.0 access token (Entra ID app registration) kept in the vault.
 */
@OoaPackage(id = "ooa.microsoft365", name = "Microsoft 365", version = "1.0.0",
        description = "Outlook mail, calendar, OneDrive and SharePoint files and Teams messages through Microsoft Graph.")
public final class Microsoft365Package {
    static final String P = "M365";
    private static final String D_TOKEN = "Microsoft Graph access token from the vault.";
    private static final String D_BASE = "Graph endpoint; change only for national clouds or testing.";
    private static final String D_MAILBOX = "Mailbox: me, or a user's email address (needs application permission).";

    private static String root(String base, String mailbox) {
        String b = base.replaceAll("/+$", "");
        return mailbox == null || mailbox.isBlank() || mailbox.equals("me") ? b + "/me" : b + "/users/" + HttpJson.enc(mailbox);
    }

    private static List<Object> recipients(Object v) {
        List<Object> out = new ArrayList<>();
        for (String a : HttpJson.addressList(v)) out.add(Map.of("emailAddress", Map.of("address", a)));
        return out;
    }

    @Action(id = "send_mail", name = "Send email (Outlook)", category = "Mail", timeoutSeconds = 120, capabilities = "network",
            description = "Send an email from a Microsoft 365 mailbox with optional CC, BCC and attachments (up to 3 MB each).",
            panel = "access_token, from, to, cc, bcc, subject, attachments, body, html | importance, reply_to, save_to_sent_items, read_receipt, mailbox, base_url")
    public Map<String, Object> sendMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Message body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "from", type = OoaType.STRING, required = false, example = "invoices@acme.com", description = "Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty.") String sendAs,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"low", "normal", "high"}, description = "Importance shown to the recipients.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "save_to_sent_items", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Keep a copy in Sent Items.") boolean saveSent,
            @Input(name = "read_receipt", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Ask for a receipt when the email is read.") boolean receipt,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("subject", subject);
        msg.put("body", Map.of("contentType", html ? "HTML" : "Text", "content", body));
        msg.put("toRecipients", recipients(to));
        if (cc != null) msg.put("ccRecipients", recipients(cc));
        if (bcc != null) msg.put("bccRecipients", recipients(bcc));
        if (attachments != null && !attachments.isEmpty()) {
            List<Object> atts = new ArrayList<>();
            for (Object a : attachments) {
                Path p = ctx.resolvePath(Values.text(a));
                try {
                    if (Files.size(p) > 3 * 1024 * 1024) throw new ActionException(P + ".ATTACHMENT_TOO_LARGE", p.getFileName() + " is larger than 3 MB", "Upload it to OneDrive and send a link instead.");
                    atts.add(Map.of("@odata.type", "#microsoft.graph.fileAttachment", "name", p.getFileName().toString(),
                            "contentBytes", Base64.getEncoder().encodeToString(Files.readAllBytes(p))));
                } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "Attachment not found: " + p, "Check the path.");
                } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
            }
            msg.put("attachments", atts);
        }
        mailOptions(msg, importance, replyTo, sendAs);
        if (receipt) msg.put("isReadReceiptRequested", true);
        HttpJson.req(P, "POST", root(base, mailbox) + "/sendMail").bearer(token).json(Map.of("message", msg, "saveToSentItems", saveSent)).send(ctx);
        return Map.of();
    }

    @Action(id = "list_mail", name = "Find emails (Outlook)", category = "Mail", idempotent = true, capabilities = "network",
            description = "List emails in a mail folder, newest first, optionally only unread or matching sender/subject text.",
            panel = "access_token, folder, unread_only, read_only, subject_contains, from_contains, since, before | has_attachments_only, oldest_first, max_results, mailbox, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, from, subject, received, unread, has_attachments.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails.")
    public Map<String, Object> listMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = "Folder id or well-known name: inbox, archive, sentitems, deleteditems.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = D_FROM) String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Maximum emails.") int max,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails that have attachments.") boolean withAtts,
            @Input(name = "oldest_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the oldest emails first instead of the newest.") boolean oldestFirst,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("$top", Math.min(1000, Math.max(1, max * (from != null || subject != null ? 4 : 1))));
        q.put("$select", "id,subject,from,receivedDateTime,isRead,hasAttachments");
        q.put("$orderby", "receivedDateTime " + (oldestFirst ? "asc" : "desc"));
        String filter = mailFilter(unread, readOnly, since, before, withAtts);
        if (filter != null) q.put("$filter", filter);
        List<Object> rows = new ArrayList<>();
        String url = root(base, mailbox) + "/mailFolders/" + HttpJson.enc(mailFolder(ctx, token, root(base, mailbox), folder)) + "/messages?" + HttpJson.query(q);
        while (url != null && rows.size() < max) {
            Map<String, Object> page = HttpJson.req(P, "GET", url).bearer(token).send(ctx).map();
            for (Object o : (List<?>) page.getOrDefault("value", List.of())) {
                Map<?, ?> m = (Map<?, ?>) o;
                String sender = m.get("from") instanceof Map<?, ?> f && f.get("emailAddress") instanceof Map<?, ?> ea ? String.valueOf(ea.get("address")) : "";
                String subj = String.valueOf(m.get("subject") == null ? "" : m.get("subject"));
                if (!anyContains(sender, from) || !anyContains(subj, subject)) continue;
                rows.add(new ArrayList<>(Arrays.asList(m.get("id"), sender, subj, m.get("receivedDateTime"), !Boolean.TRUE.equals(m.get("isRead")), Boolean.TRUE.equals(m.get("hasAttachments")))));
                if (rows.size() >= max) break;
            }
            url = (String) page.get("@odata.nextLink");
        }
        return Map.of("table", Values.table(List.of("id", "from", "subject", "received", "unread", "has_attachments"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_mail", name = "Read email (Outlook)", category = "Mail", capabilities = "network", description = "Read one email's details and body; optionally mark it read.",
            panel = "access_token, id, mark_read, body_as_text | mailbox, base_url")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, from, to, subject, received, body, body_type, has_attachments, cc, bcc, reply_to, sent, importance, flag_status, categories, conversation_id, internet_message_id.")
    public Map<String, Object> getMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id from Find emails.") String id,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Mark as read.") boolean markRead,
            @Input(name = "body_as_text", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the body as plain text even when the email is HTML.") boolean asText,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        String url = root(base, mailbox) + "/messages/" + HttpJson.enc(id);
        HttpJson.Req rq = HttpJson.req(P, "GET", url).bearer(token);
        if (asText) rq.header("Prefer", "outlook.body-content-type=\"text\"");
        Map<String, Object> m = rq.send(ctx).map();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", m.get("id"));
        e.put("from", m.get("from") instanceof Map<?, ?> f && f.get("emailAddress") instanceof Map<?, ?> ea ? ea.get("address") : "");
        List<Object> to = new ArrayList<>();
        if (m.get("toRecipients") instanceof List<?> l) for (Object r : l) if (r instanceof Map<?, ?> rm && rm.get("emailAddress") instanceof Map<?, ?> ea) to.add(ea.get("address"));
        e.put("to", to);
        e.put("subject", m.get("subject"));
        e.put("received", m.get("receivedDateTime"));
        Map<?, ?> body = m.get("body") instanceof Map<?, ?> b ? b : Map.of();
        e.put("body", body.get("content"));
        e.put("body_type", body.get("contentType"));
        e.put("has_attachments", m.get("hasAttachments"));
        e.put("cc", addressesOf(m.get("ccRecipients")));
        e.put("bcc", addressesOf(m.get("bccRecipients")));
        e.put("reply_to", addressesOf(m.get("replyTo")));
        e.put("sent", m.get("sentDateTime"));
        e.put("importance", m.get("importance"));
        e.put("flag_status", at(m, "flag", "flagStatus"));
        e.put("categories", new ArrayList<>(list(m.get("categories"))));
        e.put("conversation_id", m.get("conversationId"));
        e.put("internet_message_id", m.get("internetMessageId"));
        if (markRead && !Boolean.TRUE.equals(m.get("isRead"))) HttpJson.req(P, "PATCH", url).bearer(token).json(Map.of("isRead", true)).send(ctx);
        return Map.of("email", e);
    }

    @Action(id = "save_mail_attachments", name = "Save email attachments (Outlook)", category = "Mail", capabilities = {"network", "filesystem"},
            description = "Save an email's file attachments to a folder, optionally only names matching a pattern such as *.pdf.",
            panel = "access_token, id, target_folder, overwrite | name_pattern, include_inline, mailbox, base_url")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern.") String pattern,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "include_inline", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also save images embedded in the body, such as signature logos.") boolean inline,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        return Map.of("files", saveAtts(ctx, token, root(base, mailbox), id, ctx.resolvePath(target), pattern, overwrite, inline));
    }

    /** Saves the file attachments of one email whose names match the pattern. */
    private static List<Object> saveAtts(ActionContext ctx, Object token, String mbRoot, String id, Path dir, String pattern, boolean overwrite, boolean inline) {
        Map<String, Object> page = HttpJson.req(P, "GET", mbRoot + "/messages/" + HttpJson.enc(id) + "/attachments").bearer(token).send(ctx).map();
        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern).toLowerCase(Locale.ROOT));
        List<Object> saved = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (Object o : (List<?>) page.getOrDefault("value", List.of())) {
                Map<?, ?> a = (Map<?, ?>) o;
                if (a.get("contentBytes") == null) continue;  // item or reference attachments are not files
                if (!inline && Boolean.TRUE.equals(a.get("isInline"))) continue;
                String name = HttpJson.safeName(String.valueOf(a.get("name")));
                if (!pm.matches(Path.of(name.toLowerCase(Locale.ROOT)))) continue;
                Path out = dir.resolve(name);
                if (!overwrite) for (int i = 2; Files.exists(out); i++) out = dir.resolve(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
                Files.write(out, Base64.getDecoder().decode(String.valueOf(a.get("contentBytes"))));
                saved.add(out.toString());
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return saved;
    }

    @Action(id = "save_all_mail_attachments", name = "Save attachments of all matching emails (Outlook)", category = "Mail", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save the attachments of every email in a folder that matches the filters, optionally marking those emails as read.",
            panel = "access_token, unread_only, read_only, folder, subject_contains, from_contains, since, before, target_folder, overwrite | name_pattern, mark_read, max_results, mailbox, base_url")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    @Output(name = "email_count", type = OoaType.NUMBER, description = "Number of emails that matched.")
    public Map<String, Object> saveAllAttachments(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = D_FOLDER_FILTERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = D_FROM) String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark each matching email as read afterwards.") boolean markRead,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most emails handled in one run.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = root(base, mailbox);
        Path dir = ctx.resolvePath(target);
        List<Map<String, Object>> found = findMail(ctx, token, r, folder, unread, readOnly, from, subject, since, before, true, Math.max(1, max));
        List<Object> saved = new ArrayList<>();
        for (Map<String, Object> m : found) {
            saved.addAll(saveAtts(ctx, token, r, s(m.get("id")), dir, pattern, overwrite, true));
            if (markRead && !Boolean.TRUE.equals(m.get("isRead")))
                HttpJson.req(P, "PATCH", r + "/messages/" + HttpJson.enc(s(m.get("id")))).bearer(token).json(Map.of("isRead", true)).send(ctx);
        }
        return Map.of("files", saved, "email_count", num(found.size()));
    }

    @Action(id = "move_mail", name = "Move email (Outlook)", category = "Mail", capabilities = "network", description = "Move an email to another folder (id or well-known name such as archive).",
            panel = "access_token, id, to_folder | mailbox, base_url")
    @Output(name = "new_id", type = OoaType.STRING, description = "The email's id in the destination folder.")
    public Map<String, Object> moveMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder id, well-known name, or a path such as Inbox/Invoices/2026.") String to,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        String dest = mailFolder(ctx, token, root(base, mailbox), to);
        Map<String, Object> r = HttpJson.req(P, "POST", root(base, mailbox) + "/messages/" + HttpJson.enc(id) + "/move").bearer(token).json(Map.of("destinationId", dest)).send(ctx).map();
        return Out.of("new_id", r.get("id"));
    }

    public Map<String, Object> createEvent(ActionContext ctx, Object token, String subject, LocalDateTime start, LocalDateTime end, String tz, Object attendees, String body,
            String location, Object optional, boolean allDay, boolean online, boolean html, String importance, String showAs, String sensitivity, Integer reminder,
            List<Object> categories, String recurrence, int interval, LocalDateTime recurrenceEnd, Integer count, String mailbox, String base) {
        return createEvent(ctx, token, subject, start, end, tz, attendees, body, location, optional, allDay, online, html, importance, showAs, sensitivity, reminder,
                categories, recurrence, interval, recurrenceEnd, count, mailbox, base, null, null, null, null);
    }

    @Action(id = "create_event", name = "Create calendar event", category = "Calendar", capabilities = "network", description = "Create a meeting or appointment, optionally inviting attendees.",
            panel = "access_token, start, end, time_zone, all_day, attendees, optional_attendees, subject, location, body, attachments, [Remind me: reminder_minutes], recurrence, recurrence_interval, recurrence_end, recurrence_count, [Repeat on chosen days: recurrence_days_of_week, recurrence_week_of_month] | online_meeting, html, importance, show_as, sensitivity, categories, calendar, mailbox, base_url")
    @Output(name = "event_id", type = OoaType.STRING, description = "Event id.")
    @Output(name = "web_link", type = OoaType.STRING, description = "Link to open the event in Outlook.")
    @Output(name = "online_meeting_url", type = OoaType.STRING, description = "Teams join link, when online_meeting is true.")
    public Map<String, Object> createEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "subject", type = OoaType.STRING, description = "Title.") String subject,
            @Input(name = "start", type = OoaType.DATETIME, description = "Start date and time.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "End date and time.") LocalDateTime end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone of start and end, e.g. India Standard Time or Europe/London.") String tz,
            @Input(name = "attendees", required = false, description = "Attendee address(es).") Object attendees,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Description.") String body,
            @Input(name = "location", type = OoaType.STRING, required = false, description = "Location.") String location,
            @Input(name = "optional_attendees", required = false, description = "Address(es) invited as optional.") Object optional,
            @Input(name = "all_day", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "All-day event: only the dates of start and end count; the end date may equal the start date.") boolean allDay,
            @Input(name = "online_meeting", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Add a Teams meeting link.") boolean online,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The description is HTML.") boolean html,
            @Input(name = "importance", type = OoaType.STRING, required = false, enumValues = {"low", "normal", "high"}, description = "Importance; Outlook's default when empty.") String importance,
            @Input(name = "show_as", type = OoaType.STRING, required = false, enumValues = {"free", "tentative", "busy", "oof", "workingElsewhere"}, description = "How the time shows in the calendar (oof = out of office); busy when empty.") String showAs,
            @Input(name = "sensitivity", type = OoaType.STRING, required = false, enumValues = {"normal", "personal", "private", "confidential"}, description = "Privacy of the event; normal when empty.") String sensitivity,
            @Input(name = "reminder_minutes", type = OoaType.NUMBER, required = false, description = "Reminder this many minutes before the start; 0 turns the reminder off; Outlook's default when empty.") Integer reminder,
            @Input(name = "categories", type = OoaType.LIST, required = false, description = "Outlook category names, e.g. [\"Finance\"].") List<Object> categories,
            @Input(name = "recurrence", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "daily", "weekly", "monthly", "yearly"},
                    description = "Repeat the event; weekly repeats on the start's weekday, monthly on its day of the month.") String recurrence,
            @Input(name = "recurrence_interval", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Repeat every this many days, weeks, months or years.") int interval,
            @Input(name = "recurrence_end", type = OoaType.DATETIME, required = false, description = "Last date of the series; it has no end when empty and no count is given.") LocalDateTime recurrenceEnd,
            @Input(name = "recurrence_count", type = OoaType.NUMBER, required = false, description = "Number of occurrences, instead of an end date.") Integer count,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base,
            @Input(name = "calendar", type = OoaType.STRING, required = false, example = "Team holidays", description = "Calendar name or id to put the event in; the default calendar when empty.") String calendar,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "Local files (up to 3 MB each) attached to the event after it is created.") List<Object> attachments,
            @Input(name = "recurrence_days_of_week", type = OoaType.LIST, required = false, example = "[\"monday\", \"thursday\"]",
                    description = "Weekdays the event repeats on, e.g. [\"monday\", \"thursday\"]. For weekly repeats, instead of the start's weekday; for monthly or yearly repeats, used with recurrence_week_of_month.") List<Object> days,
            @Input(name = "recurrence_week_of_month", type = OoaType.STRING, required = false, enumValues = {"first", "second", "third", "fourth", "last"},
                    description = "Monthly or yearly repeats only: repeat on this week of the month (e.g. the second Tuesday) instead of on a fixed day of the month.") String weekOfMonth) {
        Map<String, Object> rec = recurrence(recurrence, interval, start, recurrenceEnd, count, days, weekOfMonth);
        List<Map<String, Object>> files = new ArrayList<>();
        if (attachments != null) for (Object a : attachments) files.add(fileAttachment(ctx, Values.text(a)));
        if (allDay) {
            start = start.toLocalDate().atStartOfDay();
            end = end.toLocalDate().atStartOfDay();
            if (!end.isAfter(start)) end = start.plusDays(1);
        }
        if (!end.isAfter(start)) throw new ActionException(P + ".BAD_TIMES", "The end must be after the start");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("subject", subject);
        ev.put("start", Map.of("dateTime", start.toString(), "timeZone", tz));
        ev.put("end", Map.of("dateTime", end.toString(), "timeZone", tz));
        if (body != null) ev.put("body", Map.of("contentType", html ? "HTML" : "Text", "content", body));
        if (location != null) ev.put("location", Map.of("displayName", location));
        if (attendees != null || optional != null) {
            List<Object> at = new ArrayList<>();
            for (String a : HttpJson.addressList(attendees)) at.add(Map.of("emailAddress", Map.of("address", a), "type", "required"));
            for (String a : HttpJson.addressList(optional)) at.add(Map.of("emailAddress", Map.of("address", a), "type", "optional"));
            ev.put("attendees", at);
        }
        if (allDay) ev.put("isAllDay", true);
        if (online) { ev.put("isOnlineMeeting", true); ev.put("onlineMeetingProvider", "teamsForBusiness"); }
        eventOptions(ev, importance, showAs, sensitivity, reminder, categories);
        if (rec != null) ev.put("recurrence", rec);
        String where = calendar == null || calendar.isBlank() ? root(base, mailbox) + "/events"
                : root(base, mailbox) + "/calendars/" + HttpJson.enc(calendarId(ctx, token, root(base, mailbox), calendar)) + "/events";
        Map<String, Object> r = HttpJson.req(P, "POST", where).bearer(token).json(ev).send(ctx).map();
        for (Map<String, Object> f : files)
            HttpJson.req(P, "POST", root(base, mailbox) + "/events/" + HttpJson.enc(s(r.get("id"))) + "/attachments").bearer(token).json(f).send(ctx);
        return Out.of("event_id", r.get("id"), "web_link", r.get("webLink"), "online_meeting_url", at(r, "onlineMeeting", "joinUrl"));
    }

    /** Id of a calendar given by name (not case-sensitive) or id. */
    private static String calendarId(ActionContext ctx, Object token, String mbRoot, String calendar) {
        String c = calendar.trim();
        List<Map<String, Object>> cals = pages(ctx, token, mbRoot + "/calendars?$select=id,name", 0, null);
        for (Map<String, Object> m : cals) if (s(m.get("id")).equals(c)) return c;
        for (Map<String, Object> m : cals) if (s(m.get("name")).equalsIgnoreCase(c)) return s(m.get("id"));
        List<String> names = cals.stream().map(m -> s(m.get("name"))).limit(20).toList();
        throw new ActionException(P + ".NOT_FOUND", "No calendar named '" + c + "'", names.isEmpty() ? "Check the name and the account's access." : "Available: " + String.join(", ", names) + ".");
    }

    private static String choice(String v, String input, Set<String> allowed) {
        if (v == null || v.isBlank()) return null;
        for (String a : allowed) if (a.equalsIgnoreCase(v.trim())) return a;
        throw new ActionException(P + ".BAD_OPTION", "Unknown " + input + " '" + v + "'", "Use " + String.join(", ", allowed) + ".");
    }

    /** Importance, free/busy, privacy, reminder and categories of an event; empty values are left out. */
    static void eventOptions(Map<String, Object> ev, String importance, String showAs, String sensitivity, Integer reminder, List<Object> categories) {
        String imp = choice(importance, "importance", new LinkedHashSet<>(List.of("low", "normal", "high")));
        if (imp != null) ev.put("importance", imp);
        String sa = choice(showAs, "show_as", new LinkedHashSet<>(List.of("free", "tentative", "busy", "oof", "workingElsewhere")));
        if (sa != null) ev.put("showAs", sa);
        String se = choice(sensitivity, "sensitivity", new LinkedHashSet<>(List.of("normal", "personal", "private", "confidential")));
        if (se != null) ev.put("sensitivity", se);
        if (reminder != null) {
            ev.put("isReminderOn", reminder > 0);
            if (reminder > 0) ev.put("reminderMinutesBeforeStart", reminder);
        }
        if (categories != null && !categories.isEmpty()) ev.put("categories", categories.stream().map(Values::text).toList());
    }

    /** Graph recurrence for a simple repeat, or null for none. */
    static Map<String, Object> recurrence(String kind, int interval, LocalDateTime start, LocalDateTime end, Integer count) {
        return recurrence(kind, interval, start, end, count, null, null);
    }

    private static final List<String> WEEKDAYS = List.of("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday");

    /** Graph recurrence, optionally on chosen weekdays (weekly) or on a week of the month (relative monthly or yearly); null for none. */
    static Map<String, Object> recurrence(String kind, int interval, LocalDateTime start, LocalDateTime end, Integer count, List<Object> days, String weekOfMonth) {
        String k = kind == null || kind.isBlank() ? "none" : kind.trim().toLowerCase(Locale.ROOT);
        List<String> dow = new ArrayList<>();
        if (days != null) for (Object d : days) {
            String v = Values.text(d).trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) continue;
            if (!WEEKDAYS.contains(v)) throw new ActionException(P + ".BAD_OPTION", "Unknown weekday '" + d + "' in recurrence_days_of_week", "Use full English day names such as monday or friday.");
            if (!dow.contains(v)) dow.add(v);
        }
        String week = weekOfMonth == null || weekOfMonth.isBlank() ? null : weekOfMonth.trim().toLowerCase(Locale.ROOT);
        if (week != null && !List.of("first", "second", "third", "fourth", "last").contains(week))
            throw new ActionException(P + ".BAD_OPTION", "Unknown recurrence_week_of_month '" + weekOfMonth + "'", "Use first, second, third, fourth or last.");
        if (k.equals("none")) {
            if (!dow.isEmpty() || week != null) throw new ActionException(P + ".BAD_OPTION", "Repeat days were given but recurrence is none", "Set recurrence to weekly, monthly or yearly, or clear the repeat days.");
            return null;
        }
        if (k.equals("daily") && (!dow.isEmpty() || week != null)) throw new ActionException(P + ".BAD_OPTION", "Daily repeats cannot have repeat days", "Use weekly with recurrence_days_of_week instead.");
        if (k.equals("weekly") && week != null) throw new ActionException(P + ".BAD_OPTION", "recurrence_week_of_month applies to monthly or yearly repeats only", "Clear it, or set recurrence to monthly or yearly.");
        if ((k.equals("monthly") || k.equals("yearly")) && !dow.isEmpty() && week == null)
            throw new ActionException(P + ".BAD_OPTION", "Monthly and yearly repeats on weekdays need recurrence_week_of_month", "Set it to first, second, third, fourth or last.");
        String startDay = start.getDayOfWeek().name().toLowerCase(Locale.ROOT);
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("interval", Math.max(1, interval));
        switch (k) {
            case "daily" -> pattern.put("type", "daily");
            case "weekly" -> {
                pattern.put("type", "weekly");
                pattern.put("daysOfWeek", dow.isEmpty() ? List.of(startDay) : dow);
                if (!dow.isEmpty()) pattern.put("firstDayOfWeek", "sunday");
            }
            case "monthly" -> {
                if (week == null) { pattern.put("type", "absoluteMonthly"); pattern.put("dayOfMonth", start.getDayOfMonth()); }
                else { pattern.put("type", "relativeMonthly"); pattern.put("daysOfWeek", dow.isEmpty() ? List.of(startDay) : dow); pattern.put("index", week); }
            }
            case "yearly" -> {
                if (week == null) { pattern.put("type", "absoluteYearly"); pattern.put("dayOfMonth", start.getDayOfMonth()); }
                else { pattern.put("type", "relativeYearly"); pattern.put("daysOfWeek", dow.isEmpty() ? List.of(startDay) : dow); pattern.put("index", week); }
                pattern.put("month", start.getMonthValue());
            }
            default -> throw new ActionException(P + ".BAD_OPTION", "Unknown recurrence '" + kind + "'", "Use none, daily, weekly, monthly or yearly.");
        }
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("startDate", start.toLocalDate().toString());
        if (count != null && count > 0) { range.put("type", "numbered"); range.put("numberOfOccurrences", count); }
        else if (end != null) { range.put("type", "endDate"); range.put("endDate", end.toLocalDate().toString()); }
        else range.put("type", "noEnd");
        return Map.of("pattern", pattern, "range", range);
    }

    @Action(id = "list_events", name = "Get calendar events", category = "Calendar", idempotent = true, capabilities = "network", description = "List events between two dates.",
            panel = "access_token, from, to, subject_contains | time_zone, calendar_id, mailbox, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, subject, start, end, location, organizer.")
    public Map<String, Object> listEvents(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "from", type = OoaType.DATETIME, description = "Start of the period (UTC).") LocalDateTime from,
            @Input(name = "to", type = OoaType.DATETIME, description = "End of the period (UTC).") LocalDateTime to,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Only events whose title contains this text.") String subject,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone of the returned start and end times, e.g. Europe/London.") String tz,
            @Input(name = "calendar_id", type = OoaType.STRING, required = false, description = "Read this calendar instead of the default one.") String calendar,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        String cal = calendar == null || calendar.isBlank() ? "" : "/calendars/" + HttpJson.enc(calendar);
        String url = root(base, mailbox) + cal + "/calendarView?" + HttpJson.query(Map.of("startDateTime", from + "Z", "endDateTime", to + "Z", "$top", 500, "$orderby", "start/dateTime"));
        String zone = tz == null || tz.isBlank() ? "UTC" : tz.replace("\"", "");
        List<Object> rows = new ArrayList<>();
        for (Object o : (List<?>) HttpJson.req(P, "GET", url).bearer(token).header("Prefer", "outlook.timezone=\"" + zone + "\"").send(ctx).map().getOrDefault("value", List.of())) {
            Map<?, ?> e = (Map<?, ?>) o;
            if (!anyContains(s(e.get("subject")), subject)) continue;
            rows.add(new ArrayList<>(Arrays.asList(e.get("id"), e.get("subject"),
                    e.get("start") instanceof Map<?, ?> s ? s.get("dateTime") : null, e.get("end") instanceof Map<?, ?> en ? en.get("dateTime") : null,
                    e.get("location") instanceof Map<?, ?> l ? l.get("displayName") : null,
                    e.get("organizer") instanceof Map<?, ?> og && og.get("emailAddress") instanceof Map<?, ?> ea ? ea.get("address") : null)));
        }
        return Map.of("table", Values.table(List.of("id", "subject", "start", "end", "location", "organizer"), rows));
    }

    private static String drive(String drive, String base) {
        String d = drive == null || drive.isBlank() ? "me/drive" : drive.replaceAll("^/+|/+$", "");
        return base.replaceAll("/+$", "") + "/" + d;
    }

    /** Query part setting Graph's conflict behaviour, empty for the given default. */
    static String conflict(String ifExists, String dflt) {
        String v = ifExists == null || ifExists.isBlank() ? dflt : ifExists.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("fail", "rename", "replace").contains(v)) throw new ActionException(P + ".BAD_OPTION", "Unknown if_exists '" + ifExists + "'", "Use fail, rename or replace.");
        return v.equals(dflt) ? "" : "?@microsoft.graph.conflictBehavior=" + v;
    }

    private static final String D_DRIVE = "me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}.";

    @Action(id = "upload_file", name = "Upload file (OneDrive/SharePoint)", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Upload a local file (up to 250 MB) to a OneDrive or SharePoint folder path; creates or replaces it.",
            panel = "access_token, local_path, remote_path, if_exists | drive, base_url")
    @Output(name = "item_id", type = OoaType.STRING, description = "Drive item id.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to the file.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "local_path", type = OoaType.FILE, description = "File to upload.") String local,
            @Input(name = "remote_path", type = OoaType.STRING, example = "Reports/2026/summary.xlsx", description = "Target path in the drive, including the file name.") String remote,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"replace\"", enumValues = {"fail", "rename", "replace"}, description = "When the name is taken at the destination: fail, rename (add a number) or replace.") String ifExists,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Path p = ctx.resolvePath(local);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        if (data.length > 250L * 1024 * 1024) throw new ActionException(P + ".FILE_TOO_LARGE", "Files over 250 MB need an upload session", "Split the file or contact support.");
        Map<String, Object> r = HttpJson.req(P, "PUT", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":/content" + conflict(ifExists, "replace")).bearer(token)
                .bytes(data, "application/octet-stream").timeout(600).send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    @Action(id = "download_file", name = "Download file (OneDrive/SharePoint)", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Download a file from a OneDrive or SharePoint path to a local file.",
            panel = "access_token, remote_path, local_path, overwrite | drive, base_url")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Path of the file in the drive.") String remote,
            @Input(name = "local_path", type = OoaType.FILE, description = "Where to save it.") String local,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing local file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        byte[] data = HttpJson.req(P, "GET", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":/content").bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
        Path p = ctx.resolvePath(local);
        if (!overwrite) { String n = p.getFileName().toString(); for (int i = 2; Files.exists(p); i++) p = p.resolveSibling(n.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1")); }
        try { if (p.getParent() != null) Files.createDirectories(p.getParent()); Files.write(p, data); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    @Action(id = "list_files", name = "List files (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network", description = "List the files and folders in a drive folder.",
            panel = "access_token, folder | drive, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, type, size, modified, web_url.")
    public Map<String, Object> listFiles(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Folder path; the root when empty.") String folder,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        String url = drive(drive, base) + (folder == null || folder.isBlank() ? "/root/children" : "/root:/" + HttpJson.encPath(folder) + ":/children");
        List<Object> rows = new ArrayList<>();
        while (url != null) {
            Map<String, Object> page = HttpJson.req(P, "GET", url).bearer(token).send(ctx).map();
            for (Object o : (List<?>) page.getOrDefault("value", List.of())) {
                Map<?, ?> i = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(i.get("id"), i.get("name"), i.containsKey("folder") ? "folder" : "file", i.get("size"), i.get("lastModifiedDateTime"), i.get("webUrl"))));
            }
            url = (String) page.get("@odata.nextLink");
        }
        return Map.of("table", Values.table(List.of("id", "name", "type", "size", "modified", "web_url"), rows));
    }

    public Map<String, Object> teamsMessage(ActionContext ctx, Object token, String team, String channel, String message, boolean html, String subject,
            String importance, String replyTo, String base) {
        return teamsMessage(ctx, token, team, channel, message, html, subject, importance, replyTo, base, null, null);
    }

    @Action(id = "teams_message", name = "Post Teams message", category = "Teams", capabilities = "network", description = "Post a message to a Microsoft Teams channel, chosen by id or by name.",
            panel = "access_token, (Team: By id = team_id / By name = team_name), (Channel: By id = channel_id / By name = channel_name), message | html, subject, importance, reply_to_message_id, base_url")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message id.")
    public Map<String, Object> teamsMessage(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, required = false, description = "Team id; or give team_name instead.") String team,
            @Input(name = "channel_id", type = OoaType.STRING, required = false, description = "Channel id; or give channel_name instead.") String channel,
            @Input(name = "message", type = OoaType.STRING, description = "Message text (HTML allowed when html is true).") String message,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Message is HTML.") boolean html,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = "Title shown above the message.") String subject,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "urgent"}, description = "Importance of the message.") String importance,
            @Input(name = "reply_to_message_id", type = OoaType.STRING, required = false, description = "Post as a reply in the thread of this message instead of a new post.") String replyTo,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base,
            @Input(name = "team_name", type = OoaType.STRING, required = false, example = "Finance", description = "Team display name (not case-sensitive), among the teams the signed-in user belongs to; used instead of team_id.") String teamName,
            @Input(name = "channel_name", type = OoaType.STRING, required = false, example = "General", description = "Channel display name (not case-sensitive); used instead of channel_id.") String channelName) {
        boolean byTeamId = team != null && !team.isBlank(), byTeamName = teamName != null && !teamName.isBlank();
        boolean byChannelId = channel != null && !channel.isBlank(), byChannelName = channelName != null && !channelName.isBlank();
        if (byTeamId == byTeamName) throw new ActionException(P + ".BAD_INPUT", byTeamId ? "Give team_id or team_name, not both" : "A team is required", "Set team_id or team_name.");
        if (byChannelId == byChannelName) throw new ActionException(P + ".BAD_INPUT", byChannelId ? "Give channel_id or channel_name, not both" : "A channel is required", "Set channel_id or channel_name.");
        Map<String, Object> msg = chatBody(message, html, importance);
        if (byTeamName) team = s(byName(joinedTeams(ctx, token, base, "me"), teamName, "team").get("id"));
        if (byChannelName) channel = s(byName(channels(ctx, token, base, team.trim()), channelName, "channel").get("id"));
        if (subject != null && !subject.isBlank() && (replyTo == null || replyTo.isBlank())) msg.put("subject", subject);
        String url = base.replaceAll("/+$", "") + "/teams/" + HttpJson.enc(team) + "/channels/" + HttpJson.enc(channel) + "/messages"
                + (replyTo == null || replyTo.isBlank() ? "" : "/" + HttpJson.enc(replyTo.trim()) + "/replies");
        Map<String, Object> r = HttpJson.req(P, "POST", url).bearer(token).json(msg).send(ctx).map();
        return Out.of("message_id", r.get("id"));
    }

    /** Body of a Teams message with an importance other than normal. */
    static Map<String, Object> chatBody(String message, boolean html, String importance) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("body", Map.of("contentType", html ? "html" : "text", "content", message));
        String imp = importance == null || importance.isBlank() ? "normal" : importance.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("normal", "high", "urgent").contains(imp)) throw new ActionException(P + ".BAD_OPTION", "Unknown importance '" + importance + "'", "Use normal, high or urgent.");
        if (!imp.equals("normal")) msg.put("importance", imp);
        return msg;
    }

    private static String sheetRange(String sheet, String range) {
        if (!range.matches("^[A-Za-z]{1,3}[0-9]{1,7}(:[A-Za-z]{1,3}[0-9]{1,7})?$")) throw new ActionException(P + ".BAD_RANGE", "Invalid range '" + range + "'", "Example: A1:D20.");
        return ws(sheet) + "/range(address='" + range + "')";
    }

    public Map<String, Object> excelRead(ActionContext ctx, Object token, String remote, String sheet, String range, String drive, String base) {
        return excelRead(ctx, token, remote, sheet, range, drive, base, true, "value");
    }

    @Action(id = "excel_read_range", name = "Read Excel range (OneDrive/SharePoint)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read a range, or all cells in use, from an Excel workbook stored in OneDrive or SharePoint, without downloading it.",
            panel = "access_token, remote_path, sheet, (Cells: All cells in use = / A range = range), header, read_as | drive, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Range values; the first row gives the column names when header is on, else Column1, Column2 ...")
    public Map<String, Object> excelRead(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, example = "Finance/budget.xlsx", description = "Workbook path in the drive.") String remote,
            @Input(name = "sheet", type = OoaType.STRING, example = "Sheet1", description = "Worksheet name.") String sheet,
            @Input(name = "range", type = OoaType.STRING, required = false, example = "A1:D20", description = "Range address; all cells in use on the sheet when empty.") String range,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "The first row holds the column names.") boolean header,
            @Input(name = "read_as", type = OoaType.STRING, required = false, defaultValue = "\"value\"", enumValues = {"value", "text"},
                    description = "value returns numbers and booleans as such; text returns what Excel displays, with number formats applied.") String readAs) {
        String as = readAs == null || readAs.isBlank() ? "value" : readAs.strip().toLowerCase(Locale.ROOT);
        if (!as.equals("value") && !as.equals("text")) throw new ActionException(P + ".BAD_INPUT", "read_as must be value or text, not '" + readAs + "'", "Pick value or text.");
        String where = range == null || range.isBlank()
                ? ws(sheet) + "/usedRange"
                : sheetRange(sheet, range);
        Map<String, Object> r = HttpJson.req(P, "GET", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":" + where).bearer(token).send(ctx).map();
        List<List<Object>> grid = new ArrayList<>();
        for (Object row : (List<?>) r.getOrDefault(as.equals("text") ? "text" : "values", List.of())) grid.add(new ArrayList<>((List<?>) row));
        int width = grid.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        if (header && !grid.isEmpty()) for (Object h : grid.remove(0)) cols.add(Values.text(h));
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).isBlank()) cols.set(i, "Column" + (i + 1));
        while (cols.size() < width) cols.add("Column" + (cols.size() + 1));
        return Map.of("table", Values.table(cols, new ArrayList<>(grid)));
    }

    @Action(id = "excel_write_range", name = "Write Excel range (OneDrive/SharePoint)", category = "Excel online", capabilities = "network",
            description = "Write a table into an Excel workbook in OneDrive or SharePoint, starting at a cell (column names first).",
            panel = "access_token, remote_path, sheet, start_cell, table | drive, base_url")
    @Output(name = "address", type = OoaType.STRING, description = "The range that was written.")
    public Map<String, Object> excelWrite(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Workbook path in the drive.") String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = "Worksheet name.") String sheet,
            @Input(name = "start_cell", type = OoaType.STRING, required = false, defaultValue = "\"A1\"", description = "Top-left cell.") String start,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to write.") Map<String, Object> table,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        List<Object> values = new ArrayList<>();
        List<?> cols = (List<?>) table.get("columns");
        values.add(new ArrayList<>(cols));
        for (Object r : (List<?>) table.get("rows")) { List<Object> row = new ArrayList<>(); for (int i = 0; i < cols.size(); i++) { List<?> rr = (List<?>) r; Object v = i < rr.size() ? rr.get(i) : null; row.add(v == null ? "" : Values.toJsonable(v)); } values.add(row); }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z]{1,3})([0-9]{1,7})$").matcher(start);
        if (!m.matches()) throw new ActionException(P + ".BAD_RANGE", "Invalid start cell '" + start + "'", "Example: A1.");
        int col0 = 0; for (char ch : m.group(1).toUpperCase(Locale.ROOT).toCharArray()) col0 = col0 * 26 + (ch - 'A' + 1);
        int row0 = Integer.parseInt(m.group(2)), lastCol = col0 + cols.size() - 1, lastRow = row0 + values.size() - 1;
        StringBuilder colName = new StringBuilder();
        for (int c = lastCol; c > 0; c = (c - 1) / 26) colName.insert(0, (char) ('A' + (c - 1) % 26));
        String address = start.toUpperCase(Locale.ROOT) + ":" + colName + lastRow;
        HttpJson.req(P, "PATCH", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":" + sheetRange(sheet, address)).bearer(token).json(Map.of("values", values)).send(ctx);
        return Map.of("address", address);
    }

    @Action(id = "excel_copy_range", name = "Copy Excel range (OneDrive/SharePoint)", category = "Excel online", capabilities = "network",
            description = "Copy a cell or range to another place in the same workbook, as values or with formulas, and optionally with number formats.",
            panel = "access_token, remote_path, sheet, range, destination, [To another sheet: destination_sheet] | copy, include_number_formats, drive, base_url")
    @Output(name = "address", type = OoaType.STRING, description = "The range that was written, e.g. H2:K10.")
    public Map<String, Object> excelCopyRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = "Worksheet the range is on.") String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A2:D10", description = "Cell or range to copy.") String range,
            @Input(name = "destination", type = OoaType.STRING, example = "H2", description = "Top-left cell to copy to.") String destination,
            @Input(name = "destination_sheet", type = OoaType.STRING, required = false, description = "Worksheet to copy to; the same sheet when empty.") String destSheet,
            @Input(name = "copy", type = OoaType.STRING, required = false, defaultValue = "\"values\"", enumValues = {"values", "formulas"},
                    description = "values copies the results; formulas copies formulas exactly as written (cell references are not moved).") String copy,
            @Input(name = "include_number_formats", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also copy number formats such as dates and currency.") boolean formats,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String what = copy == null || copy.isBlank() ? "values" : copy.trim().toLowerCase(Locale.ROOT);
        if (!what.equals("values") && !what.equals("formulas")) throw new ActionException(P + ".BAD_OPTION", "Unknown copy option '" + copy + "'", "Use values or formulas.");
        String from = rangePath(range);
        cell(destination);
        String toSheet = destSheet == null || destSheet.isBlank() ? sheet : destSheet;
        Map<String, Object> src = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + from) + "?$select=values,formulas,numberFormat").bearer(token).send(ctx).map();
        List<Object> data = new ArrayList<>(matrix(src.get(what)));
        if (data.isEmpty()) throw new ActionException(P + ".NO_VALUES", "The range returned no cells", "Check the sheet and range.");
        String address = spanFrom(destination, data);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(what, data);
        if (formats && !matrix(src.get("numberFormat")).isEmpty()) body.put("numberFormat", matrix(src.get("numberFormat")));
        HttpJson.req(P, "PATCH", book(drive, base, remote, ws(toSheet) + rangePath(address))).bearer(token).json(body).send(ctx);
        return Map.of("address", address);
    }

    // ============================================================================================
    // Extended toolkit: mail management, file management, Excel online, calendar, Teams and
    // SharePoint lists. Same auth, paging and error handling as the actions above.
    // ============================================================================================

    private static final String GRAPH = "\"https://graph.microsoft.com/v1.0\"";
    private static final String D_ITEM = "Path in the drive (e.g. Reports/q4.xlsx), or id:{item-id}.";
    private static final String D_BOOK = "Workbook path in the drive (e.g. Finance/budget.xlsx), or id:{item-id}.";
    private static final String D_SHEET = "Worksheet name.";
    private static final String D_SITE = "SharePoint site: site id, or hostname:/sites/path such as contoso.sharepoint.com:/sites/Finance.";
    private static final String D_LIST = "List id or display name.";
    private static final String D_EVENT = "Event id from Get calendar events or Create calendar event.";
    private static final String D_FOLDER_FILTERS = "Folder id or well-known name: inbox, archive, sentitems, deleteditems.";

    private static String g(String base) { return base.replaceAll("/+$", ""); }

    /** URL of a drive item addressed by path or by id:{item-id}, with an optional sub-resource suffix such as /copy. */
    static String item(String drive, String base, String ref, String suffix) {
        String r = ref == null ? "" : ref.trim();
        if (r.startsWith("id:")) return drive(drive, base) + "/items/" + HttpJson.enc(r.substring(3)) + suffix;
        String p = HttpJson.encPath(r);
        if (p.isEmpty()) return drive(drive, base) + "/root" + suffix;
        return drive(drive, base) + "/root:/" + p + (suffix.isEmpty() ? "" : ":" + suffix);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }
    private static List<?> list(Object o) { return o instanceof List<?> l ? l : List.of(); }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private static Object at(Object o, String... keys) {
        Object c = o;
        for (String k : keys) { if (!(c instanceof Map<?, ?> m)) return null; c = m.get(k); }
        return c;
    }
    private static BigDecimal num(long n) { return BigDecimal.valueOf(n); }

    /** Reads a Graph collection page by page (following @odata.nextLink) up to max items; max 0 means all. */
    private static List<Map<String, Object>> pages(ActionContext ctx, Object token, String url, int max, String prefer) {
        List<Map<String, Object>> out = new ArrayList<>();
        while (url != null && (max <= 0 || out.size() < max)) {
            HttpJson.Req rq = HttpJson.req(P, "GET", url).bearer(token);
            if (prefer != null) rq.header("Prefer", prefer);
            Map<String, Object> page = rq.send(ctx).map();
            for (Object o : list(page.get("value"))) { out.add(map(o)); if (max > 0 && out.size() >= max) break; }
            url = (String) page.get("@odata.nextLink");
        }
        return out;
    }

    private static String odataText(String v) { return "'" + v.replace("'", "''") + "'"; }

    private static Map<String, Object> fileAttachment(ActionContext ctx, String file) {
        Path p = ctx.resolvePath(file);
        try {
            if (Files.size(p) > 3 * 1024 * 1024) throw new ActionException(P + ".ATTACHMENT_TOO_LARGE", p.getFileName() + " is larger than 3 MB", "Upload it to OneDrive and share a link instead.");
            return Map.of("@odata.type", "#microsoft.graph.fileAttachment", "name", p.getFileName().toString(),
                    "contentBytes", Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    private static Path writeLocal(ActionContext ctx, String local, byte[] data) {
        Path p = ctx.resolvePath(local);
        try { if (p.getParent() != null) Files.createDirectories(p.getParent()); Files.write(p, data); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return p;
    }

    // ----------------------------------------- Mail ---------------------------------------------

    /** Emails in a folder that match the filters; ids are collected before any change so paging is not disturbed. */
    private static List<Map<String, Object>> findMail(ActionContext ctx, Object token, String mbRoot, String folder, boolean unread, String from, String subject, int max) {
        return findMail(ctx, token, mbRoot, folder, unread, false, from, subject, null, null, false, max);
    }

    private static List<Map<String, Object>> findMail(ActionContext ctx, Object token, String mbRoot, String folder, boolean unread, boolean read, String from, String subject,
                                                      LocalDateTime since, LocalDateTime before, boolean withAtts, int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("$top", 100);
        q.put("$select", "id,subject,from,receivedDateTime,isRead");
        String filter = mailFilter(unread, read, since, before, withAtts);
        if (filter != null) q.put("$filter", filter);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : pages(ctx, token, mbRoot + "/mailFolders/" + HttpJson.enc(mailFolder(ctx, token, mbRoot, folder)) + "/messages?" + HttpJson.query(q), 0, null)) {
            if (!anyContains(s(at(m, "from", "emailAddress", "address")), from) || !anyContains(s(m.get("subject")), subject)) continue;
            out.add(m);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static final String D_FROM = "Sender contains this text; separate several with ; to match any of them.";
    private static final String D_SUBJECT = "Subject contains this text; separate several with ; to match any of them.";
    private static final java.time.format.DateTimeFormatter UTC = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    static final Set<String> WELL_KNOWN = Set.of("inbox", "archive", "sentitems", "deleteditems", "drafts", "junkemail", "outbox", "clutter",
            "conversationhistory", "msgfolderroot", "recoverableitemsdeletions", "scheduled", "searchfolders", "syncissues");

    /** True when the text contains any of the ;-separated values (not case-sensitive), or when there are none. */
    static boolean anyContains(String text, String values) {
        if (values == null || values.isBlank()) return true;
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String v : values.split(";")) if (!v.isBlank() && t.contains(v.trim().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** OData filter for read status, received dates and attachments; the date comes first so Graph can sort by it. Null when there is no filter. */
    static String mailFilter(boolean unread, boolean read, LocalDateTime since, LocalDateTime before, boolean withAtts) {
        if (unread && read) throw new ActionException(P + ".BAD_OPTION", "unread_only and read_only cannot both be true", "Choose one of them.");
        List<String> f = new ArrayList<>();
        if (since != null) f.add("receivedDateTime ge " + UTC.format(since));
        if (before != null) f.add("receivedDateTime lt " + UTC.format(before));
        if (f.isEmpty() && withAtts) f.add("receivedDateTime ge 1900-01-01T00:00:00Z");
        if (unread) f.add("isRead eq false");
        if (read) f.add("isRead eq true");
        if (withAtts) f.add("hasAttachments eq true");
        return f.isEmpty() ? null : String.join(" and ", f);
    }

    /** Folder id for a path such as Inbox/Invoices/2026 (or with \\); ids and well-known names pass unchanged. Null when a part is missing. */
    static String folderPath(ActionContext ctx, Object token, String mbRoot, String folder) {
        if (folder == null || !(folder.contains("/") || folder.contains("\\"))) return folder;
        String id = null;
        for (String part : folder.trim().split("[/\\\\]+")) {
            if (part.isBlank()) continue;
            if (id == null && WELL_KNOWN.contains(part.trim().toLowerCase(Locale.ROOT))) { id = part.trim().toLowerCase(Locale.ROOT); continue; }
            String url = (id == null ? mbRoot + "/mailFolders" : mbRoot + "/mailFolders/" + HttpJson.enc(id) + "/childFolders") + "?"
                    + HttpJson.query(Map.of("$filter", "displayName eq " + odataText(part.trim()), "$select", "id,displayName"));
            List<Map<String, Object>> found = pages(ctx, token, url, 1, null);
            if (found.isEmpty()) return null;
            id = s(found.get(0).get("id"));
        }
        return id;
    }

    private static String mailFolder(ActionContext ctx, Object token, String mbRoot, String folder) {
        String id = folderPath(ctx, token, mbRoot, folder);
        if (id == null) throw new ActionException(P + ".NO_FOLDER", "Mail folder '" + folder + "' not found", "Check the path, e.g. Inbox/Invoices; names are not case-sensitive.");
        return id;
    }

    private static List<Object> addressesOf(Object recipients) {
        List<Object> out = new ArrayList<>();
        for (Object r : list(recipients)) { Object a = at(r, "emailAddress", "address"); if (a != null) out.add(a); }
        return out;
    }

    /** Importance, reply-to and send-as address of a Graph message. */
    static void mailOptions(Map<String, Object> msg, String importance, Object replyTo, String sendAs) {
        String imp = importance == null || importance.isBlank() ? "normal" : importance.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("low", "normal", "high").contains(imp)) throw new ActionException(P + ".BAD_OPTION", "Unknown importance '" + importance + "'", "Use low, normal or high.");
        if (!imp.equals("normal")) msg.put("importance", imp);
        if (replyTo != null && !HttpJson.addressList(replyTo).isEmpty()) msg.put("replyTo", recipients(replyTo));
        if (sendAs != null && !sendAs.isBlank()) msg.put("from", Map.of("emailAddress", Map.of("address", sendAs.trim())));
    }

    /** Message properties for forward and reply: CC, BCC, send-as, importance. Empty when nothing is set. */
    private static Map<String, Object> extra(Object cc, Object bcc, String sendAs, String importance) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (cc != null && !HttpJson.addressList(cc).isEmpty()) m.put("ccRecipients", recipients(cc));
        if (bcc != null && !HttpJson.addressList(bcc).isEmpty()) m.put("bccRecipients", recipients(bcc));
        mailOptions(m, importance, null, sendAs);
        return m;
    }

    /** Forward or reply. With attachments it goes through a draft (create, attach, send), since the direct call cannot carry files. */
    private static void respond(ActionContext ctx, Object token, String mbRoot, String id, String op, Map<String, Object> body, List<Object> attachments) {
        String url = mbRoot + "/messages/" + HttpJson.enc(id);
        if (attachments == null || attachments.isEmpty()) { HttpJson.req(P, "POST", url + "/" + op).bearer(token).json(body).send(ctx); return; }
        String create = "create" + Character.toUpperCase(op.charAt(0)) + op.substring(1);
        String draft = s(HttpJson.req(P, "POST", url + "/" + create).bearer(token).json(body).send(ctx).map().get("id"));
        for (Object a : attachments)
            HttpJson.req(P, "POST", mbRoot + "/messages/" + HttpJson.enc(draft) + "/attachments").bearer(token).json(fileAttachment(ctx, Values.text(a))).send(ctx);
        HttpJson.req(P, "POST", mbRoot + "/messages/" + HttpJson.enc(draft) + "/send").bearer(token).send(ctx);
    }

    @Action(id = "mark_mail", name = "Change email status (Outlook)", category = "Mail", idempotent = true, capabilities = "network",
            description = "Mark an email as read or unread, or set, clear or complete its follow-up flag.",
            panel = "access_token, id, status | mailbox, base_url")
    public Map<String, Object> markMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "status", type = OoaType.STRING, enumValues = {"read", "unread", "flagged", "unflagged", "complete"}, description = "New status: read, unread, flagged, unflagged or complete.") String status,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> patch = switch (status.toLowerCase(Locale.ROOT)) {
            case "read" -> Map.of("isRead", true);
            case "unread" -> Map.of("isRead", false);
            case "flagged" -> Map.of("flag", Map.of("flagStatus", "flagged"));
            case "unflagged" -> Map.of("flag", Map.of("flagStatus", "notFlagged"));
            case "complete" -> Map.of("flag", Map.of("flagStatus", "complete"));
            default -> throw new ActionException(P + ".BAD_STATUS", "Unknown status '" + status + "'", "Use read, unread, flagged, unflagged or complete.");
        };
        HttpJson.req(P, "PATCH", root(base, mailbox) + "/messages/" + HttpJson.enc(id)).bearer(token).json(patch).send(ctx);
        return Map.of();
    }

    @Action(id = "delete_mail", name = "Delete email (Outlook)", category = "Mail", capabilities = "network",
            description = "Delete an email by moving it to Deleted Items, or remove it permanently.",
            panel = "access_token, id | permanent, mailbox, base_url")
    public Map<String, Object> deleteMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove permanently instead of moving to Deleted Items.") boolean permanent,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        removeMail(ctx, token, root(base, mailbox), id, permanent);
        return Map.of();
    }

    private static void removeMail(ActionContext ctx, Object token, String mbRoot, String id, boolean permanent) {
        String url = mbRoot + "/messages/" + HttpJson.enc(id);
        if (permanent) HttpJson.req(P, "DELETE", url).bearer(token).send(ctx);
        else HttpJson.req(P, "POST", url + "/move").bearer(token).json(Map.of("destinationId", "deleteditems")).send(ctx);
    }

    @Action(id = "delete_all_mail", name = "Delete all emails (Outlook)", category = "Mail", timeoutSeconds = 600, capabilities = "network",
            description = "Delete every email in a folder that matches the optional filters, to Deleted Items or permanently.",
            panel = "access_token, folder, unread_only, read_only, subject_contains, from_contains, since, before | permanent, max_results, mailbox, base_url")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails deleted.")
    public Map<String, Object> deleteAllMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = D_FOLDER_FILTERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = D_FROM) String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove permanently instead of moving to Deleted Items.") boolean permanent,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most emails to delete in one run.") int max,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = root(base, mailbox);
        List<Map<String, Object>> found = findMail(ctx, token, r, folder, unread, readOnly, from, subject, since, before, false, Math.max(1, max));
        for (Map<String, Object> m : found) removeMail(ctx, token, r, s(m.get("id")), permanent);
        return Map.of("count", num(found.size()));
    }

    @Action(id = "move_all_mail", name = "Move all emails (Outlook)", category = "Mail", timeoutSeconds = 600, capabilities = "network",
            description = "Move every email in a folder that matches the optional filters to another folder.",
            panel = "access_token, to_folder, unread_only, read_only, folder, subject_contains, from_contains, since, before | max_results, mailbox, base_url")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails moved.")
    public Map<String, Object> moveAllMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder id, well-known name, or a path such as Inbox/Invoices/2026.") String to,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = "Source " + D_FOLDER_FILTERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = D_FROM) String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most emails to move in one run.") int max,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = root(base, mailbox);
        String dest = mailFolder(ctx, token, r, to);
        List<Map<String, Object>> found = findMail(ctx, token, r, folder, unread, readOnly, from, subject, since, before, false, Math.max(1, max));
        for (Map<String, Object> m : found)
            HttpJson.req(P, "POST", r + "/messages/" + HttpJson.enc(s(m.get("id"))) + "/move").bearer(token).json(Map.of("destinationId", dest)).send(ctx);
        return Map.of("count", num(found.size()));
    }

    @Action(id = "forward_mail", name = "Forward email (Outlook)", category = "Mail", capabilities = "network",
            description = "Forward an email to new recipients with an optional note above the original message.",
            panel = "access_token, id, from, to, cc, bcc, attachments, comment | importance, mailbox, base_url")
    public Map<String, Object> forwardMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "comment", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Note added above the forwarded message.") String comment,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach (up to 3 MB each).") List<Object> attachments,
            @Input(name = "from", type = OoaType.STRING, required = false, example = "invoices@acme.com", description = "Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty.") String sendAs,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"low", "normal", "high"}, description = "Importance shown to the recipients.") String importance,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (HttpJson.addressList(to).isEmpty()) throw new ActionException(P + ".NO_RECIPIENTS", "No recipients to forward to", "Give at least one address.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toRecipients", recipients(to));
        body.put("comment", comment == null ? "" : comment);
        Map<String, Object> more = extra(cc, bcc, sendAs, importance);
        if (!more.isEmpty()) body.put("message", more);
        respond(ctx, token, root(base, mailbox), id, "forward", body, attachments);
        return Map.of();
    }

    @Action(id = "reply_mail", name = "Reply to email (Outlook)", category = "Mail", capabilities = "network",
            description = "Reply to the sender of an email, or to everyone on it, with a note above the original message.",
            panel = "access_token, id, reply_all, from, cc, bcc, attachments, comment | exclude_sender, importance, mailbox, base_url")
    public Map<String, Object> replyMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "comment", type = OoaType.STRING, description = "Reply text.") String comment,
            @Input(name = "reply_all", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Reply to the sender and every other recipient.") boolean all,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach (up to 3 MB each).") List<Object> attachments,
            @Input(name = "from", type = OoaType.STRING, required = false, example = "invoices@acme.com", description = "Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty.") String sendAs,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"low", "normal", "high"}, description = "Importance shown to the recipients.") String importance,
            @Input(name = "exclude_sender", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Reply all only: leave the original sender out and reply to the other recipients.") boolean excludeSender,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = root(base, mailbox);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", comment == null ? "" : comment);
        Map<String, Object> more = extra(cc, bcc, sendAs, importance);
        if (all && excludeSender) {
            Map<String, Object> orig = HttpJson.req(P, "GET", r + "/messages/" + HttpJson.enc(id) + "?$select=from,toRecipients,ccRecipients").bearer(token).send(ctx).map();
            String sender = s(at(orig, "from", "emailAddress", "address"));
            String me = mailbox == null || mailbox.isBlank() || mailbox.equals("me")
                    ? s(HttpJson.req(P, "GET", g(base) + "/me?$select=mail").bearer(token).send(ctx).map().get("mail")) : mailbox;
            List<Object> toList = new ArrayList<>(), ccList = new ArrayList<>(list(more.get("ccRecipients")));
            for (Object a : addressesOf(orig.get("toRecipients"))) if (!s(a).equalsIgnoreCase(sender) && !s(a).equalsIgnoreCase(me)) toList.add(a);
            for (Object a : addressesOf(orig.get("ccRecipients"))) if (!s(a).equalsIgnoreCase(sender) && !s(a).equalsIgnoreCase(me)) ccList.add(Map.of("emailAddress", Map.of("address", a)));
            if (toList.isEmpty() && ccList.isEmpty()) throw new ActionException(P + ".NO_RECIPIENTS", "Without the sender there is nobody left to reply to", "Turn off exclude_sender.");
            more.put("toRecipients", recipients(toList));
            more.put("ccRecipients", ccList);
        }
        if (!more.isEmpty()) body.put("message", more);
        respond(ctx, token, r, id, all ? "replyAll" : "reply", body, attachments);
        return Map.of();
    }

    @Action(id = "reply_all_mail", name = "Reply all to email (Outlook)", category = "Mail", capabilities = "network",
            description = "Reply to the sender and every other recipient of an email with a note above the original message.",
            panel = "access_token, id, from, cc, bcc, exclude_sender, attachments, comment | importance, mailbox, base_url")
    public Map<String, Object> replyAllMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "comment", type = OoaType.STRING, description = "Reply text.") String comment,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach (up to 3 MB each).") List<Object> attachments,
            @Input(name = "from", type = OoaType.STRING, required = false, example = "invoices@acme.com", description = "Send as this address, e.g. a shared mailbox; the account needs Send As rights. The mailbox itself when empty.") String sendAs,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"low", "normal", "high"}, description = "Importance shown to the recipients.") String importance,
            @Input(name = "exclude_sender", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave the original sender out and reply to the other recipients.") boolean excludeSender,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return replyMail(ctx, token, id, comment, true, cc, bcc, attachments, sendAs, importance, excludeSender, mailbox, base);
    }

    @Action(id = "mail_folder_exists", name = "Check mail folder exists (Outlook)", category = "Mail", idempotent = true, capabilities = "network",
            description = "Check whether a mail folder exists, by top-level display name or by a path such as Inbox/Invoices, and return its id.",
            panel = "access_token, name | mailbox, base_url")
    @Output(name = "exists", type = OoaType.BOOLEAN, description = "True when the folder exists.")
    @Output(name = "folder_id", type = OoaType.STRING, description = "Folder id, empty when it does not exist.")
    public Map<String, Object> folderExists(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name", type = OoaType.STRING, description = "Folder display name, e.g. Invoices, or a path such as Inbox/Invoices/2026 (/ or \\ between levels).") String name,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (name.contains("/") || name.contains("\\")) {
            String id = folderPath(ctx, token, root(base, mailbox), name);
            return Map.of("exists", id != null, "folder_id", id == null ? "" : id);
        }
        String url = root(base, mailbox) + "/mailFolders?" + HttpJson.query(Map.of("$filter", "displayName eq " + odataText(name), "$select", "id,displayName"));
        List<Map<String, Object>> found = pages(ctx, token, url, 1, null);
        return Map.of("exists", !found.isEmpty(), "folder_id", found.isEmpty() ? "" : s(found.get(0).get("id")));
    }

    @Action(id = "save_mail_as_eml", name = "Save email as EML (Outlook)", category = "Mail", capabilities = {"network", "filesystem"},
            description = "Save a complete email, including attachments, as a standard .eml file.",
            panel = "access_token, id, local_path, overwrite | mailbox, base_url")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> saveEml(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "local_path", type = OoaType.FILE, example = "mail/order-123.eml", description = "Where to save the .eml file; a path ending in / or an existing folder gets a file named after the subject.") String local,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String url = root(base, mailbox) + "/messages/" + HttpJson.enc(id);
        Path p = ctx.resolvePath(local);
        if (local.endsWith("/") || local.endsWith("\\") || Files.isDirectory(p)) {
            String subj = s(HttpJson.req(P, "GET", url + "?$select=subject").bearer(token).send(ctx).map().get("subject"));
            p = p.resolve(HttpJson.safeName((subj.isBlank() ? "email" : subj.replaceAll("[/\\\\]", "_")) + ".eml"));
        }
        if (!overwrite) { String n = p.getFileName().toString(); for (int i = 2; Files.exists(p); i++) p = p.resolveSibling(n.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1")); }
        byte[] data = HttpJson.req(P, "GET", url + "/$value").bearer(token).binaryResponse().timeout(300).send(ctx).bytes();
        HttpJson.writeFile(p, data);
        return Map.of("path", p.toString());
    }

    // ------------------------------------ OneDrive / SharePoint files ----------------------------

    /** Drive id and item id of a destination folder (the drive root when the path is empty). */
    private static String[] folderRef(ActionContext ctx, Object token, String drive, String base, String folder) {
        Map<String, Object> f = HttpJson.req(P, "GET", item(drive, base, folder, "") + "?$select=id,parentReference,folder,root").bearer(token).send(ctx).map();
        return new String[]{s(at(f, "parentReference", "driveId")), s(f.get("id"))};
    }

    private static Map<String, Object> itemInfo(Map<String, Object> i) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", i.get("id"));
        e.put("name", i.get("name"));
        e.put("type", i.containsKey("folder") ? "folder" : "file");
        e.put("size", i.get("size"));
        e.put("created", i.get("createdDateTime"));
        e.put("modified", i.get("lastModifiedDateTime"));
        e.put("modified_by", at(i, "lastModifiedBy", "user", "displayName"));
        e.put("parent_path", at(i, "parentReference", "path"));
        e.put("web_url", i.get("webUrl"));
        return e;
    }

    @Action(id = "copy_item", name = "Copy file or folder (OneDrive/SharePoint)", category = "Files", timeoutSeconds = 600, capabilities = "network",
            description = "Copy a file or folder into another folder, optionally under a new name, and wait for the copy to finish.",
            panel = "access_token, item, to_folder, if_exists | new_name, to_drive, wait_seconds, drive, base_url")
    @Output(name = "item_id", type = OoaType.STRING, description = "Id of the copy, once it has finished.")
    @Output(name = "status", type = OoaType.STRING, description = "completed, or inProgress when the wait ran out.")
    @Output(name = "monitor_url", type = OoaType.STRING, description = "Address to check the copy's progress.")
    public Map<String, Object> copyItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder path (empty for the root), or id:{item-id}.") String to,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "Name for the copy; the original name when empty.") String newName,
            @Input(name = "to_drive", type = OoaType.STRING, required = false, description = "Destination drive when different from the source drive.") String toDrive,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", description = "How long to wait for the copy; 0 returns at once.") int wait,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"fail\"", enumValues = {"fail", "rename", "replace"}, description = "When the name is taken at the destination: fail, rename (add a number) or replace.") String ifExists,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String[] dest = folderRef(ctx, token, toDrive == null || toDrive.isBlank() ? drive : toDrive, base, to);
        Map<String, Object> parent = new LinkedHashMap<>();
        if (!dest[0].isEmpty()) parent.put("driveId", dest[0]);
        parent.put("id", dest[1]);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentReference", parent);
        if (newName != null && !newName.isBlank()) body.put("name", newName);
        HttpJson.Response r = HttpJson.req(P, "POST", item(drive, base, ref, "/copy") + conflict(ifExists, "fail")).bearer(token).json(body).send(ctx);
        String monitor = r.header("Location");
        if (monitor == null || wait <= 0) return Out.of("item_id", null, "status", "inProgress", "monitor_url", monitor);
        long deadline = System.currentTimeMillis() + wait * 1000L;
        while (true) {
            // the monitor address is pre-authorised; it must not receive the access token
            Map<String, Object> st = HttpJson.req(P, "GET", monitor).send(ctx).map();
            String status = s(st.get("status"));
            if (status.equals("completed")) return Out.of("item_id", st.get("resourceId"), "status", status, "monitor_url", monitor);
            if (status.equals("failed")) throw new ActionException(P + ".COPY_FAILED", "The copy failed: " + s(at(st, "error", "message")), "Check the destination and that the name is free.");
            if (System.currentTimeMillis() > deadline) return Out.of("item_id", null, "status", status.isEmpty() ? "inProgress" : status, "monitor_url", monitor);
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        }
    }

    @Action(id = "move_item", name = "Move file or folder (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Move a file or folder into another folder in the same drive, optionally renaming it.",
            panel = "access_token, item, to_folder, if_exists | new_name, drive, base_url")
    @Output(name = "item_id", type = OoaType.STRING, description = "Item id.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to the item in its new place.")
    public Map<String, Object> moveItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder path (empty for the root), or id:{item-id}.") String to,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "New name; unchanged when empty.") String newName,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"fail\"", enumValues = {"fail", "rename", "replace"}, description = "When the name is taken at the destination: fail, rename (add a number) or replace.") String ifExists,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String[] dest = folderRef(ctx, token, drive, base, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentReference", Map.of("id", dest[1]));
        if (newName != null && !newName.isBlank()) body.put("name", newName);
        Map<String, Object> r = HttpJson.req(P, "PATCH", item(drive, base, ref, "") + conflict(ifExists, "fail")).bearer(token).json(body).send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    public Map<String, Object> renameItem(ActionContext ctx, Object token, String ref, String newName, String drive, String base) {
        return renameItem(ctx, token, ref, newName, drive, base, null);
    }

    @Action(id = "rename_item", name = "Rename file or folder (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network",
            description = "Give a file or folder a new name in the same folder.",
            panel = "access_token, item, new_name | if_exists, drive, base_url")
    @Output(name = "item_id", type = OoaType.STRING, description = "Item id.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to the renamed item.")
    public Map<String, Object> renameItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "new_name", type = OoaType.STRING, description = "New name, including the extension for files.") String newName,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"fail\"", enumValues = {"fail", "rename", "replace"}, description = "When the new name is taken in the folder: fail, rename (add a number) or replace.") String ifExists) {
        Map<String, Object> r = HttpJson.req(P, "PATCH", item(drive, base, ref, "") + conflict(ifExists, "fail")).bearer(token).json(Map.of("name", newName)).send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    @Action(id = "delete_item", name = "Delete file or folder (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Delete a file or folder; it goes to the drive's recycle bin unless deleted permanently.",
            panel = "access_token, item | permanent, drive, base_url")
    public Map<String, Object> deleteItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good, skipping the recycle bin (OneDrive for work or school and SharePoint).") boolean permanent,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (ref == null || ref.isBlank()) throw new ActionException(P + ".BAD_ITEM", "No item given", "Give a path or id:{item-id}; the drive root cannot be deleted.");
        if (permanent) HttpJson.req(P, "POST", item(drive, base, ref, "/permanentDelete")).bearer(token).send(ctx);
        else HttpJson.req(P, "DELETE", item(drive, base, ref, "")).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "create_folder", name = "Create folder (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Create a folder inside a drive folder.",
            panel = "access_token, parent_folder, name | if_exists, drive, base_url")
    @Output(name = "item_id", type = OoaType.STRING, description = "Folder id.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to the folder.")
    public Map<String, Object> createFolder(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name", type = OoaType.STRING, description = "New folder name.") String name,
            @Input(name = "parent_folder", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Parent folder path (empty for the root), or id:{item-id}.") String parent,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"fail\"", enumValues = {"fail", "rename", "replace"}, description = "When the name is taken: fail, rename (add a number) or replace.") String ifExists,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("folder", Map.of());
        body.put("@microsoft.graph.conflictBehavior", ifExists == null ? "fail" : ifExists);
        Map<String, Object> r = HttpJson.req(P, "POST", item(drive, base, parent, "/children")).bearer(token).json(body).send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    @Action(id = "find_items", name = "Find files (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network",
            description = "Search a drive for files and folders whose name or content matches the text.",
            panel = "access_token, text, item_type, [Search one folder only: in_folder] | exact_name, max_results, drive, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, type, size, modified, web_url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items found.")
    public Map<String, Object> findItems(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum items.") int max,
            @Input(name = "item_type", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "file", "folder"}, description = "Files, folders or both.") String type,
            @Input(name = "in_folder", type = OoaType.STRING, required = false, description = "Search only inside this folder (path or id:{item-id}); the whole drive when empty.") String inFolder,
            @Input(name = "exact_name", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep only items whose name equals the text (not case-sensitive).") boolean exact,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String t = choice(type, "item_type", new LinkedHashSet<>(List.of("any", "file", "folder")));
        String scope = inFolder == null || inFolder.isBlank() ? drive(drive, base) + "/root" : item(drive, base, inFolder, "");
        if (scope.endsWith(":")) scope = scope.substring(0, scope.length() - 1);
        String url = scope + (scope.contains("/root:/") ? ":" : "") + "/search(q=" + HttpJson.enc(odataText(text)) + ")?" + HttpJson.query(Map.of("$top", Math.min(200, Math.max(1, max))));
        boolean filtered = !"any".equals(t) || exact;
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> i : pages(ctx, token, url, filtered ? 0 : Math.max(1, max), null)) {
            if (rows.size() >= Math.max(1, max)) break;
            boolean folder = i.containsKey("folder");
            if ("file".equals(t) && folder || "folder".equals(t) && !folder) continue;
            if (exact && !s(i.get("name")).equalsIgnoreCase(text.trim())) continue;
            rows.add(new ArrayList<>(Arrays.asList(i.get("id"), i.get("name"), i.containsKey("folder") ? "folder" : "file", i.get("size"), i.get("lastModifiedDateTime"), i.get("webUrl"))));
        }
        return Map.of("table", Values.table(List.of("id", "name", "type", "size", "modified", "web_url"), rows), "count", num(rows.size()));
    }

    @Action(id = "get_item_info", name = "Get file information (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network",
            description = "Read a file's or folder's id, size, dates, last editor, parent path and link.",
            panel = "access_token, item | drive, base_url")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "id, name, type, size, created, modified, modified_by, parent_path, web_url.")
    public Map<String, Object> itemInfo(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return Map.of("info", itemInfo(HttpJson.req(P, "GET", item(drive, base, ref, "")).bearer(token).send(ctx).map()));
    }

    private static List<String> permissionEmails(Map<String, Object> p) {
        List<String> out = new ArrayList<>();
        for (String k : List.of("grantedToV2", "grantedTo")) { Object e = at(p, k, "user", "email"); if (e != null) out.add(s(e)); }
        for (String k : List.of("grantedToIdentitiesV2", "grantedToIdentities"))
            for (Object id : list(p.get(k))) { Object e = at(id, "user", "email"); if (e != null) out.add(s(e)); }
        Object inv = at(p, "invitation", "email");
        if (inv != null) out.add(s(inv));
        return out;
    }

    @Action(id = "list_permissions", name = "List file permissions (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network",
            description = "List who can open or edit a file or folder, including sharing links.",
            panel = "access_token, item | drive, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, roles, granted_to, link_type, link_scope, link_url.")
    public Map<String, Object> listPermissions(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> p : pages(ctx, token, item(drive, base, ref, "/permissions"), 0, null)) {
            List<String> who = permissionEmails(p);
            if (who.isEmpty() && at(p, "grantedToV2", "siteGroup", "displayName") != null) who.add(s(at(p, "grantedToV2", "siteGroup", "displayName")));
            rows.add(new ArrayList<>(Arrays.asList(p.get("id"), String.join(",", list(p.get("roles")).stream().map(o -> String.valueOf(o)).toList()),
                    String.join(";", who), at(p, "link", "type"), at(p, "link", "scope"), at(p, "link", "webUrl"))));
        }
        return Map.of("table", Values.table(List.of("id", "roles", "granted_to", "link_type", "link_scope", "link_url"), rows));
    }

    @Action(id = "check_permissions", name = "Check file permission (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network",
            description = "Check whether a person has been given direct access to a file or folder and with which roles.",
            panel = "access_token, item, email | drive, base_url")
    @Output(name = "has_access", type = OoaType.BOOLEAN, description = "True when the person has a direct permission.")
    @Output(name = "roles", type = OoaType.LIST, description = "Roles such as read, write or owner.")
    public Map<String, Object> checkPermissions(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "email", type = OoaType.STRING, description = "Email address of the person.") String email,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Set<Object> roles = new LinkedHashSet<>();
        boolean found = false;
        for (Map<String, Object> p : pages(ctx, token, item(drive, base, ref, "/permissions"), 0, null))
            if (permissionEmails(p).stream().anyMatch(e -> e.equalsIgnoreCase(email.trim()))) { found = true; roles.addAll(list(p.get("roles"))); }
        return Map.of("has_access", found, "roles", new ArrayList<>(roles));
    }

    @Action(id = "share_item", name = "Share file or folder (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Give people read or edit access to a file or folder, optionally emailing them an invitation.",
            panel = "access_token, item, recipients, role, send_invitation, [Add a note to the invitation: message] | require_sign_in, drive, base_url")
    @Output(name = "permission_ids", type = OoaType.LIST, description = "Ids of the new permissions.")
    public Map<String, Object> shareItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "recipients", description = "Email address(es) of the people to share with.") Object recipients,
            @Input(name = "role", type = OoaType.STRING, required = false, defaultValue = "\"read\"", enumValues = {"read", "write"}, description = "read to view, write to edit.") String role,
            @Input(name = "send_invitation", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Email the people a sharing invitation.") boolean invite,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Text added to the invitation.") String message,
            @Input(name = "require_sign_in", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "People must sign in to open the item.") boolean signIn,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> who = new ArrayList<>();
        for (String a : HttpJson.addressList(recipients)) who.add(Map.of("email", a));
        if (who.isEmpty()) throw new ActionException(P + ".NO_RECIPIENTS", "Nobody to share with", "Give at least one email address.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", who);
        body.put("roles", List.of(choice(role == null || role.isBlank() ? "read" : role, "role", new LinkedHashSet<>(List.of("read", "write")))));
        body.put("requireSignIn", signIn);
        body.put("sendInvitation", invite);
        if (message != null && !message.isBlank()) body.put("message", message);
        List<Object> ids = new ArrayList<>();
        for (Object o : list(HttpJson.req(P, "POST", item(drive, base, ref, "/invite")).bearer(token).json(body).send(ctx).map().get("value"))) ids.add(map(o).get("id"));
        return Map.of("permission_ids", ids);
    }

    @Action(id = "create_sharing_link", name = "Create sharing link (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Create a link that opens a file or folder for viewing or editing, for anyone or only people in the organisation.",
            panel = "access_token, item, type, scope, [Link expires: expires] | drive, base_url")
    @Output(name = "link", type = OoaType.STRING, description = "The sharing link.")
    @Output(name = "permission_id", type = OoaType.STRING, description = "Id of the link's permission.")
    public Map<String, Object> sharingLink(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "type", type = OoaType.STRING, required = false, defaultValue = "\"view\"", enumValues = {"view", "edit"}, description = "view or edit.") String type,
            @Input(name = "scope", type = OoaType.STRING, required = false, defaultValue = "\"organization\"", enumValues = {"organization", "anonymous"}, description = "organization: people in your organisation; anonymous: anyone with the link.") String scope,
            @Input(name = "expires", type = OoaType.DATETIME, required = false, description = "When the link stops working (UTC); never when empty.") LocalDateTime expires,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", choice(type == null || type.isBlank() ? "view" : type, "type", new LinkedHashSet<>(List.of("view", "edit"))));
        body.put("scope", choice(scope == null || scope.isBlank() ? "organization" : scope, "scope", new LinkedHashSet<>(List.of("organization", "anonymous"))));
        if (expires != null) body.put("expirationDateTime", UTC.format(expires));
        Map<String, Object> r = HttpJson.req(P, "POST", item(drive, base, ref, "/createLink")).bearer(token).json(body).send(ctx).map();
        return Out.of("link", at(r, "link", "webUrl"), "permission_id", r.get("id"));
    }

    public Map<String, Object> exportPdf(ActionContext ctx, Object token, String ref, String local, String drive, String base) {
        return exportPdf(ctx, token, ref, local, drive, base, true);
    }

    @Action(id = "export_to_pdf", name = "Export file to PDF (OneDrive/SharePoint)", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Convert a Word, Excel, PowerPoint or other supported file in the drive to PDF and save it locally.",
            panel = "access_token, item, local_path, overwrite | drive, base_url")
    @Output(name = "path", type = OoaType.FILE, description = "Saved PDF path.")
    public Map<String, Object> exportPdf(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "local_path", type = OoaType.FILE, example = "out/report.pdf", description = "Where to save the PDF.") String local,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing local file; when false a number such as (2) is added to the name.") boolean overwrite) {
        byte[] data = HttpJson.req(P, "GET", item(drive, base, ref, "/content") + "?format=pdf").bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
        Path p = ctx.resolvePath(local);
        if (!overwrite) { String n = p.getFileName().toString(); for (int i = 2; Files.exists(p); i++) p = p.resolveSibling(n.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1")); }
        return Map.of("path", writeLocal(ctx, p.toString(), data).toString());
    }

    @Action(id = "restore_version", name = "Restore file version (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Make an earlier version of a file the current one; by default the version before the current one.",
            panel = "access_token, item, (Version: The previous version = / A specific version = version_id) | drive, base_url")
    @Output(name = "restored_version", type = OoaType.STRING, description = "Id of the version that was restored.")
    public Map<String, Object> restoreVersion(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "version_id", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Version to restore, e.g. 3.0; the previous version when empty.") String version,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String v = version;
        if (v == null || v.isBlank()) {
            List<Map<String, Object>> versions = pages(ctx, token, item(drive, base, ref, "/versions"), 0, null);
            if (versions.size() < 2) throw new ActionException(P + ".NO_OLDER_VERSION", "The file has no earlier version", "Version history starts after the file is changed.");
            v = s(versions.get(1).get("id"));  // newest first; index 0 is the current version
        }
        HttpJson.req(P, "POST", item(drive, base, ref, "/versions/" + HttpJson.enc(v) + "/restoreVersion")).bearer(token).send(ctx);
        return Map.of("restored_version", v);
    }

    // ------------------------------------------ Excel online -------------------------------------

    private static final java.util.regex.Pattern CELL = java.util.regex.Pattern.compile("^([A-Za-z]{1,3})([0-9]{1,7})$");

    /** {column number, row number} of a cell such as C7. */
    static int[] cell(String a) {
        java.util.regex.Matcher m = CELL.matcher(a.trim().replace("$", ""));
        if (!m.matches()) throw new ActionException(P + ".BAD_RANGE", "Invalid cell '" + a + "'", "Example: B2.");
        int c = 0;
        for (char ch : m.group(1).toUpperCase(Locale.ROOT).toCharArray()) c = c * 26 + (ch - 'A' + 1);
        return new int[]{c, Integer.parseInt(m.group(2))};
    }

    static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        for (; c > 0; c = (c - 1) / 26) sb.insert(0, (char) ('A' + (c - 1) % 26));
        return sb.toString();
    }

    /** Top-left and bottom-right cells of an address such as Sheet1!B2:D9. */
    static int[][] bounds(String address) {
        String a = address.substring(address.lastIndexOf('!') + 1);
        String[] p = a.split(":");
        int[] s = cell(p[0]);
        return new int[][]{s, p.length > 1 ? cell(p[1]) : s};
    }

    private static String ws(String sheet) { return "/workbook/worksheets('" + HttpJson.enc(sheet.replace("'", "''")) + "')"; }

    private static String rangePath(String range) {
        String r = range.trim().replace("$", "");
        if (!r.matches("^[A-Za-z]{1,3}[0-9]{1,7}(:[A-Za-z]{1,3}[0-9]{1,7})?$")) throw new ActionException(P + ".BAD_RANGE", "Invalid range '" + range + "'", "Example: A1:D20.");
        return "/range(address='" + r.toUpperCase(Locale.ROOT) + "')";
    }

    private static String book(String drive, String base, String remote, String suffix) { return item(drive, base, remote, suffix); }

    private static String table(String t) { return "/workbook/tables/" + HttpJson.enc(t); }

    private static List<Object> tableMatrix(Map<String, Object> table, boolean headers) {
        List<?> cols = list(table.get("columns"));
        List<Object> values = new ArrayList<>();
        if (headers) values.add(new ArrayList<>(cols));
        for (Object r : list(table.get("rows"))) {
            List<?> rr = list(r);
            List<Object> row = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) { Object v = i < rr.size() ? rr.get(i) : null; row.add(v == null ? "" : Values.toJsonable(v)); }
            values.add(row);
        }
        return values;
    }

    /** Rows of cell values from a list of lists, a flat list (one row) or a Data Table (rows only); short rows are padded. */
    @SuppressWarnings("unchecked")
    private static List<Object> grid(Object v) {
        List<Object> out = new ArrayList<>();
        if (v instanceof Map<?, ?> m && m.containsKey("rows")) out = tableMatrix(map(m), false);
        else {
            List<?> l = list(v);
            boolean nested = l.stream().anyMatch(o -> o instanceof List<?>);
            for (Object r : nested ? l : List.of(l)) {
                List<Object> row = new ArrayList<>();
                for (Object c : r instanceof List<?> rl ? rl : List.of(r)) row.add(c == null ? "" : Values.toJsonable(c));
                out.add(row);
            }
        }
        int w = 0;
        for (Object r : out) w = Math.max(w, ((List<Object>) r).size());
        if (out.isEmpty() || w == 0) throw new ActionException(P + ".NO_VALUES", "There are no values to write", "Give a list of rows, each a list of cell values.");
        for (Object r : out) { List<Object> row = (List<Object>) r; while (row.size() < w) row.add(""); }
        return out;
    }

    /** Address covering values written from a start cell (the first cell of a range). */
    private static String spanFrom(String startOrRange, List<Object> values) {
        int[] s = cell(startOrRange.split(":")[0]);
        int w = list(values.get(0)).size();
        return colName(s[0]) + s[1] + ":" + colName(s[0] + w - 1) + (s[1] + values.size() - 1);
    }

    private static List<List<Object>> matrix(Object v) {
        List<List<Object>> g = new ArrayList<>();
        for (Object r : list(v)) g.add(new ArrayList<>(list(r)));
        return g;
    }

    private static Object first(Object matrix) {
        List<?> r = list(matrix);
        return r.isEmpty() || list(r.get(0)).isEmpty() ? null : list(r.get(0)).get(0);
    }

    @Action(id = "excel_get_worksheets", name = "Get worksheets (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "List the worksheets of a workbook in OneDrive or SharePoint, in tab order.",
            panel = "access_token, remote_path | drive, base_url")
    @Output(name = "sheets", type = OoaType.LIST, description = "Worksheet names.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, position, visibility.")
    public Map<String, Object> excelSheets(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Map<String, Object>> sheets = new ArrayList<>(pages(ctx, token, book(drive, base, remote, "/workbook/worksheets"), 0, null));
        sheets.sort(Comparator.comparingInt(m -> m.get("position") instanceof Number n ? n.intValue() : 0));
        List<Object> names = new ArrayList<>(), rows = new ArrayList<>();
        for (Map<String, Object> m : sheets) { names.add(m.get("name")); rows.add(new ArrayList<>(Arrays.asList(m.get("id"), m.get("name"), m.get("position"), m.get("visibility")))); }
        return Map.of("sheets", names, "table", Values.table(List.of("id", "name", "position", "visibility"), rows));
    }

    @Action(id = "excel_add_worksheet", name = "Add worksheet (Excel online)", category = "Excel online", capabilities = "network",
            description = "Add a worksheet to a workbook in OneDrive or SharePoint.",
            panel = "access_token, remote_path, name | drive, base_url")
    @Output(name = "sheet_name", type = OoaType.STRING, description = "Name of the new worksheet.")
    @Output(name = "sheet_id", type = OoaType.STRING, description = "Id of the new worksheet.")
    public Map<String, Object> excelAddSheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name for the worksheet; Excel picks one when empty.") String name,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) body.put("name", name);
        Map<String, Object> r = HttpJson.req(P, "POST", book(drive, base, remote, "/workbook/worksheets/add")).bearer(token).json(body).send(ctx).map();
        return Out.of("sheet_name", r.get("name"), "sheet_id", r.get("id"));
    }

    /** Name of the worksheet given by name, or by its 1-based position in the workbook's tab order. */
    private static String sheetByNameOrPosition(ActionContext ctx, Object token, String drive, String base, String remote, String sheet, Integer position) {
        boolean byName = sheet != null && !sheet.isBlank(), byPos = position != null;
        if (byName == byPos) throw new ActionException(P + ".BAD_INPUT", byName ? "Give sheet or sheet_position, not both" : "A worksheet is required", "Set sheet or sheet_position.");
        if (byName) return sheet;
        List<Map<String, Object>> sheets = new ArrayList<>(pages(ctx, token, book(drive, base, remote, "/workbook/worksheets"), 0, null));
        sheets.sort(Comparator.comparingInt(m -> m.get("position") instanceof Number n ? n.intValue() : 0));
        if (position < 1 || position > sheets.size())
            throw new ActionException(P + ".NOT_FOUND", "No worksheet at position " + position, "The workbook has " + sheets.size() + " worksheet(s); the first is 1.");
        return s(sheets.get(position - 1).get("name"));
    }

    public Map<String, Object> excelDeleteSheet(ActionContext ctx, Object token, String remote, String sheet, String drive, String base) {
        return excelDeleteSheet(ctx, token, remote, sheet, drive, base, null);
    }

    @Action(id = "excel_delete_worksheet", name = "Delete worksheet (Excel online)", category = "Excel online", capabilities = "network",
            description = "Delete a worksheet and everything on it from a workbook in OneDrive or SharePoint.",
            panel = "access_token, remote_path, (Sheet by: Name = sheet / Position = sheet_position) | drive, base_url")
    public Map<String, Object> excelDeleteSheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Worksheet name; or give sheet_position instead.") String sheet,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "sheet_position", type = OoaType.NUMBER, required = false, example = "2", description = "Position of the worksheet among the tabs, the first being 1; used instead of sheet.") Integer position) {
        String name = sheetByNameOrPosition(ctx, token, drive, base, remote, sheet, position);
        HttpJson.req(P, "DELETE", book(drive, base, remote, ws(name))).bearer(token).send(ctx);
        return Map.of();
    }

    public Map<String, Object> excelRenameSheet(ActionContext ctx, Object token, String remote, String sheet, String newName, String drive, String base) {
        return excelRenameSheet(ctx, token, remote, sheet, newName, drive, base, null);
    }

    @Action(id = "excel_rename_worksheet", name = "Rename worksheet (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Give a worksheet in a OneDrive or SharePoint workbook a new name.",
            panel = "access_token, remote_path, (Sheet by: Name = sheet / Position = sheet_position), new_name | drive, base_url")
    public Map<String, Object> excelRenameSheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Current worksheet name; or give sheet_position instead.") String sheet,
            @Input(name = "new_name", type = OoaType.STRING, description = "New worksheet name.") String newName,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "sheet_position", type = OoaType.NUMBER, required = false, example = "2", description = "Position of the worksheet among the tabs, the first being 1; used instead of sheet.") Integer position) {
        String name = sheetByNameOrPosition(ctx, token, drive, base, remote, sheet, position);
        HttpJson.req(P, "PATCH", book(drive, base, remote, ws(name))).bearer(token).json(Map.of("name", newName)).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_get_range", name = "Get range values (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read the cell values of a range as a list of rows, without treating the first row as headers.",
            panel = "access_token, remote_path, sheet, range | drive, base_url")
    @Output(name = "values", type = OoaType.LIST, description = "List of rows, each a list of cell values.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows.")
    @Output(name = "column_count", type = OoaType.NUMBER, description = "Number of columns.")
    public Map<String, Object> excelGetRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A1:D20", description = "Range address.") String range,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + rangePath(range))).bearer(token).send(ctx).map();
        List<List<Object>> v = matrix(r.get("values"));
        return Map.of("values", v, "row_count", num(v.size()), "column_count", num(v.isEmpty() ? 0 : v.get(0).size()));
    }

    @Action(id = "excel_set_range", name = "Set range values (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Write a list of rows into a sheet; a single cell address is grown to fit the values.",
            panel = "access_token, remote_path, sheet, range, values | drive, base_url")
    @Output(name = "address", type = OoaType.STRING, description = "The range that was written.")
    public Map<String, Object> excelSetRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "B2", description = "Top-left cell, or a full range matching the values' size.") String range,
            @Input(name = "values", description = "List of rows (each a list of values), one flat list for a single row, or a Data Table (rows only).") Object values,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> g = grid(values);
        String address = spanFrom(range, g);
        HttpJson.req(P, "PATCH", book(drive, base, remote, ws(sheet) + rangePath(address))).bearer(token).json(Map.of("values", g)).send(ctx);
        return Map.of("address", address);
    }

    @Action(id = "excel_get_cell", name = "Get cell (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read one cell's value and the text Excel shows for it.",
            panel = "access_token, remote_path, sheet, cell | drive, base_url")
    @Output(name = "value", description = "Cell value (number, text or boolean).")
    @Output(name = "text", type = OoaType.STRING, description = "Displayed text with number formatting applied.")
    public Map<String, Object> excelGetCell(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "cell", type = OoaType.STRING, example = "C7", description = "Cell address.") String cell,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        cell(cell);
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + rangePath(cell))).bearer(token).send(ctx).map();
        return Out.of("value", first(r.get("values")), "text", s(first(r.get("text"))));
    }

    @Action(id = "excel_set_cell", name = "Set cell (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Write one value into a cell.",
            panel = "access_token, remote_path, sheet, cell, value | drive, base_url")
    public Map<String, Object> excelSetCell(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "cell", type = OoaType.STRING, example = "C7", description = "Cell address.") String cell,
            @Input(name = "value", description = "Value to write; empty clears the value.") Object value,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        cell(cell);
        HttpJson.req(P, "PATCH", book(drive, base, remote, ws(sheet) + rangePath(cell))).bearer(token)
                .json(Map.of("values", List.of(List.of(value == null ? "" : Values.toJsonable(value))))).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_set_formula", name = "Set formula (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Put a formula into a cell and return the value Excel calculates.",
            panel = "access_token, remote_path, sheet, cell, formula | drive, base_url")
    @Output(name = "value", description = "Calculated value of the formula.")
    public Map<String, Object> excelSetFormula(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "cell", type = OoaType.STRING, example = "D10", description = "Cell address.") String cell,
            @Input(name = "formula", type = OoaType.STRING, example = "=SUM(D2:D9)", description = "Formula; the leading = is added when missing.") String formula,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        cell(cell);
        String f = formula.startsWith("=") ? formula : "=" + formula;
        Map<String, Object> r = HttpJson.req(P, "PATCH", book(drive, base, remote, ws(sheet) + rangePath(cell))).bearer(token)
                .json(Map.of("formulas", List.of(List.of(f)))).send(ctx).map();
        return Out.of("value", first(r.get("values")));
    }

    @Action(id = "excel_read_formula", name = "Read formula (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read the formula in a cell together with its current value.",
            panel = "access_token, remote_path, sheet, cell | drive, base_url")
    @Output(name = "formula", type = OoaType.STRING, description = "Formula, or the plain value when the cell has none.")
    @Output(name = "value", description = "Current value of the cell.")
    public Map<String, Object> excelReadFormula(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "cell", type = OoaType.STRING, example = "D10", description = "Cell address.") String cell,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        cell(cell);
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + rangePath(cell))).bearer(token).send(ctx).map();
        return Out.of("formula", s(first(r.get("formulas"))), "value", first(r.get("values")));
    }

    @Action(id = "excel_clear_range", name = "Clear range (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Clear the values, the formatting, or both from a range without shifting other cells.",
            panel = "access_token, remote_path, sheet, range, clear | drive, base_url")
    public Map<String, Object> excelClear(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A2:F100", description = "Range address.") String range,
            @Input(name = "clear", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "contents", "formats"}, description = "What to clear: all, contents or formats.") String what,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String applyTo = switch (what == null ? "all" : what.toLowerCase(Locale.ROOT)) {
            case "contents" -> "Contents"; case "formats" -> "Formats"; case "all" -> "All";
            default -> throw new ActionException(P + ".BAD_OPTION", "Unknown clear option '" + what + "'", "Use all, contents or formats.");
        };
        HttpJson.req(P, "POST", book(drive, base, remote, ws(sheet) + rangePath(range) + "/clear")).bearer(token).json(Map.of("applyTo", applyTo)).send(ctx);
        return Map.of();
    }

    private Map<String, Object> shiftRange(ActionContext ctx, Object token, String remote, String sheet, String range, String op, String shift, Set<String> allowed, String drive, String base) {
        String sh = shift == null ? "" : shift.toLowerCase(Locale.ROOT);
        if (!allowed.contains(sh)) throw new ActionException(P + ".BAD_OPTION", "Unknown shift '" + shift + "'", "Use " + String.join(" or ", allowed) + ".");
        String where = rangePath(range);
        if (sh.equals("entire_row") || sh.equals("entire_column")) {
            int[][] b = bounds(range.trim().replace("$", ""));
            boolean rows = sh.equals("entire_row");
            where = "/range(address='" + (rows ? b[0][1] + ":" + b[1][1] : colName(b[0][0]) + ":" + colName(b[1][0])) + "')";
            sh = op.equals("delete") ? (rows ? "up" : "left") : (rows ? "down" : "right");
        }
        Map<String, Object> r = HttpJson.req(P, "POST", book(drive, base, remote, ws(sheet) + where + "/" + op)).bearer(token)
                .json(Map.of("shift", Character.toUpperCase(sh.charAt(0)) + sh.substring(1))).send(ctx).map();
        return r;
    }

    @Action(id = "excel_delete_range", name = "Delete range (Excel online)", category = "Excel online", capabilities = "network",
            description = "Delete the cells of a range and close the gap by moving cells up or left, or delete the whole rows or columns the range covers.",
            panel = "access_token, remote_path, sheet, range, shift | drive, base_url")
    public Map<String, Object> excelDeleteRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A5:F5", description = "Range address.") String range,
            @Input(name = "shift", type = OoaType.STRING, required = false, defaultValue = "\"up\"", enumValues = {"up", "left", "entire_row", "entire_column"},
                    description = "Move the remaining cells up or left; entire_row or entire_column deletes the whole rows or columns of the range.") String shift,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        shiftRange(ctx, token, remote, sheet, range, "delete", shift, new LinkedHashSet<>(List.of("up", "left", "entire_row", "entire_column")), drive, base);
        return Map.of();
    }

    @Action(id = "excel_insert_range", name = "Insert range (Excel online)", category = "Excel online", capabilities = "network",
            description = "Insert empty cells at a range, pushing the existing cells down or right, or insert whole rows or columns where the range is.",
            panel = "access_token, remote_path, sheet, range, shift | drive, base_url")
    @Output(name = "address", type = OoaType.STRING, description = "Address of the inserted cells.")
    public Map<String, Object> excelInsertRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A5:F5", description = "Range address.") String range,
            @Input(name = "shift", type = OoaType.STRING, required = false, defaultValue = "\"down\"", enumValues = {"down", "right", "entire_row", "entire_column"},
                    description = "Push the existing cells down or right; entire_row or entire_column inserts as many whole rows or columns as the range covers.") String shift,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> r = shiftRange(ctx, token, remote, sheet, range, "insert", shift, new LinkedHashSet<>(List.of("down", "right", "entire_row", "entire_column")), drive, base);
        return Map.of("address", s(r.get("address")));
    }

    @Action(id = "excel_autofit_columns", name = "Autofit columns (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Resize the columns of a range so their contents fit.",
            panel = "access_token, remote_path, sheet, range | drive, base_url")
    public Map<String, Object> excelAutofitColumns(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A1:F1", description = "Range whose columns are resized.") String range,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "POST", book(drive, base, remote, ws(sheet) + rangePath(range) + "/format/autofitColumns")).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_autofit_rows", name = "Autofit rows (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Resize the rows of a range so their contents fit.",
            panel = "access_token, remote_path, sheet, range | drive, base_url")
    public Map<String, Object> excelAutofitRows(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A1:A50", description = "Range whose rows are resized.") String range,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "POST", book(drive, base, remote, ws(sheet) + rangePath(range) + "/format/autofitRows")).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_format_range", name = "Format range (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Set fill, font, alignment, wrapping, size, borders, merging and number format of a range; empty settings are left as they are.",
            panel = "access_token, remote_path, sheet, range, fill_color, font_color, bold, number_format | italic, underline, strikethrough, font_name, font_size, horizontal_alignment, vertical_alignment, wrap_text, column_width, row_height, border_style, border_color, merge, drive, base_url")
    public Map<String, Object> excelFormat(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A1:F1", description = "Range address.") String range,
            @Input(name = "fill_color", type = OoaType.STRING, required = false, example = "#FFF2CC", description = "Background colour as #RRGGBB or a colour name.") String fill,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Make the text bold (true) or normal (false).") Boolean bold,
            @Input(name = "font_color", type = OoaType.STRING, required = false, example = "#C00000", description = "Text colour as #RRGGBB or a colour name.") String fontColor,
            @Input(name = "number_format", type = OoaType.STRING, required = false, example = "#,##0.00", description = "Excel number format code for every cell.") String numberFormat,
            @Input(name = "italic", type = OoaType.BOOLEAN, required = false, description = "Italic on or off; unchanged when empty.") Boolean italic,
            @Input(name = "underline", type = OoaType.BOOLEAN, required = false, description = "Single underline on or off; unchanged when empty.") Boolean underline,
            @Input(name = "strikethrough", type = OoaType.BOOLEAN, required = false, description = "Strikethrough on or off; unchanged when empty.") Boolean strike,
            @Input(name = "font_name", type = OoaType.STRING, required = false, example = "Calibri", description = "Font name; unchanged when empty.") String fontName,
            @Input(name = "font_size", type = OoaType.NUMBER, required = false, description = "Font size in points; unchanged when empty.") Double fontSize,
            @Input(name = "horizontal_alignment", type = OoaType.STRING, required = false, enumValues = {"General", "Left", "Center", "Right", "Fill", "Justify", "CenterAcrossSelection", "Distributed"}, description = "Horizontal alignment; unchanged when empty.") String hAlign,
            @Input(name = "vertical_alignment", type = OoaType.STRING, required = false, enumValues = {"Top", "Center", "Bottom", "Justify", "Distributed"}, description = "Vertical alignment; unchanged when empty.") String vAlign,
            @Input(name = "wrap_text", type = OoaType.BOOLEAN, required = false, description = "Wrap text in the cells on or off; unchanged when empty.") Boolean wrap,
            @Input(name = "column_width", type = OoaType.NUMBER, required = false, description = "Column width in points; unchanged when empty.") Double colWidth,
            @Input(name = "row_height", type = OoaType.NUMBER, required = false, description = "Row height in points; unchanged when empty.") Double rowHeight,
            @Input(name = "border_style", type = OoaType.STRING, required = false, enumValues = {"None", "Continuous", "Dash", "DashDot", "DashDotDot", "Dot", "Double", "SlantDashDot"}, description = "Line style of every border of the range, inside and out; unchanged when empty.") String borderStyle,
            @Input(name = "border_color", type = OoaType.STRING, required = false, example = "#000000", description = "Colour of those borders.") String borderColor,
            @Input(name = "merge", type = OoaType.BOOLEAN, required = false, description = "Merge the cells (true) or unmerge them (false); unchanged when empty.") Boolean merge,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = book(drive, base, remote, ws(sheet) + rangePath(range));
        boolean any = false;
        if (fill != null && !fill.isBlank()) { HttpJson.req(P, "PATCH", r + "/format/fill").bearer(token).json(Map.of("color", fill)).send(ctx); any = true; }
        Map<String, Object> font = new LinkedHashMap<>();
        if (bold != null) font.put("bold", bold);
        if (fontColor != null && !fontColor.isBlank()) font.put("color", fontColor);
        if (italic != null) font.put("italic", italic);
        if (underline != null) font.put("underline", underline ? "Single" : "None");
        if (strike != null) font.put("strikethrough", strike);
        if (fontName != null && !fontName.isBlank()) font.put("name", fontName);
        if (fontSize != null) font.put("size", fontSize);
        Map<String, Object> fmt = new LinkedHashMap<>();
        if (hAlign != null && !hAlign.isBlank()) fmt.put("horizontalAlignment", hAlign);
        if (vAlign != null && !vAlign.isBlank()) fmt.put("verticalAlignment", vAlign);
        if (wrap != null) fmt.put("wrapText", wrap);
        if (colWidth != null) fmt.put("columnWidth", colWidth);
        if (rowHeight != null) fmt.put("rowHeight", rowHeight);
        if (!fmt.isEmpty()) { HttpJson.req(P, "PATCH", r + "/format").bearer(token).json(fmt).send(ctx); any = true; }
        if (borderStyle != null && !borderStyle.isBlank()) {
            Map<String, Object> b = new LinkedHashMap<>(Map.of("style", borderStyle));
            if (borderColor != null && !borderColor.isBlank()) b.put("color", borderColor);
            for (String side : List.of("EdgeTop", "EdgeBottom", "EdgeLeft", "EdgeRight", "InsideVertical", "InsideHorizontal"))
                HttpJson.req(P, "PATCH", r + "/format/borders/" + side).bearer(token).json(b).send(ctx);
            any = true;
        }
        if (merge != null) { HttpJson.req(P, "POST", r + (merge ? "/merge" : "/unmerge")).bearer(token).json(merge ? Map.of("across", false) : Map.of()).send(ctx); any = true; }
        if (!font.isEmpty()) { HttpJson.req(P, "PATCH", r + "/format/font").bearer(token).json(font).send(ctx); any = true; }
        if (numberFormat != null && !numberFormat.isBlank()) {
            int[][] b = bounds(range);
            List<Object> grid = new ArrayList<>();
            for (int i = b[0][1]; i <= b[1][1]; i++) grid.add(Collections.nCopies(b[1][0] - b[0][0] + 1, numberFormat));
            HttpJson.req(P, "PATCH", r).bearer(token).json(Map.of("numberFormat", grid)).send(ctx);
            any = true;
        }
        if (!any) throw new ActionException(P + ".NOTHING_TO_DO", "No formatting was given", "Set at least one formatting input, such as fill_color, bold or font_name.");
        return Map.of();
    }

    private Map<String, Object> visibility(ActionContext ctx, Object token, String remote, String sheet, String v, String drive, String base) {
        HttpJson.req(P, "PATCH", book(drive, base, remote, ws(sheet))).bearer(token).json(Map.of("visibility", v)).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_hide_worksheet", name = "Hide worksheet (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Hide a worksheet tab without deleting it; very hidden sheets can only be shown again by an agent or code.",
            panel = "access_token, remote_path, sheet | very_hidden, drive, base_url")
    public Map<String, Object> excelHideSheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "very_hidden", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Hide it so that it cannot be shown from Excel's menu.") boolean very,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return visibility(ctx, token, remote, sheet, very ? "VeryHidden" : "Hidden", drive, base);
    }

    @Action(id = "excel_show_worksheet", name = "Show worksheet (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Make a hidden worksheet tab visible again.",
            panel = "access_token, remote_path, sheet | drive, base_url")
    public Map<String, Object> excelShowSheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return visibility(ctx, token, remote, sheet, "Visible", drive, base);
    }

    @Action(id = "excel_copy_worksheet", name = "Copy worksheet (Excel online)", category = "Excel online", capabilities = "network",
            description = "Copy a worksheet's cells (values, formulas and number formats) into a new worksheet, in the same workbook or another one. Colours, fonts and column widths are not copied.",
            panel = "access_token, remote_path, sheet, [Into another workbook: target_remote_path], new_name | drive, base_url")
    @Output(name = "sheet_name", type = OoaType.STRING, description = "Name of the new worksheet.")
    @Output(name = "address", type = OoaType.STRING, description = "Cells written on the new worksheet, e.g. A1:F42; empty when the source sheet is empty.")
    public Map<String, Object> excelCopySheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = "Worksheet to copy.") String sheet,
            @Input(name = "target_remote_path", type = OoaType.STRING, required = false, description = "Workbook to copy into (path in the same drive, or id:{item-id}); the same workbook when empty.") String target,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "Name of the new worksheet; the source sheet's name when empty, which must then be free in the target workbook.") String newName,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (sheet == null || sheet.isBlank()) throw new ActionException(P + ".BAD_INPUT", "A worksheet to copy is required", "Set sheet.");
        String into = target == null || target.isBlank() ? remote : target;
        String name = newName == null || newName.isBlank() ? sheet : newName.trim();
        if (into.trim().equals(remote.trim()) && name.equalsIgnoreCase(sheet.trim()))
            throw new ActionException(P + ".BAD_INPUT", "The copy needs another name in the same workbook", "Set new_name, or give target_remote_path.");
        Map<String, Object> src = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + "/usedRange") + "?$select=address,formulas,numberFormat,rowCount").bearer(token).send(ctx).map();
        String a = s(src.get("address"));
        String cells = a.substring(a.lastIndexOf('!') + 1);
        Map<String, Object> created = HttpJson.req(P, "POST", book(drive, base, into, "/workbook/worksheets/add")).bearer(token).json(Map.of("name", name)).send(ctx).map();
        String made = created.get("name") == null ? name : s(created.get("name"));
        List<List<Object>> formulas = matrix(src.get("formulas"));
        boolean empty = cells.isEmpty() || formulas.isEmpty() || (src.get("rowCount") instanceof Number n && n.intValue() == 0)
                || formulas.stream().allMatch(r -> r.stream().allMatch(v -> v == null || s(v).isEmpty()));
        if (empty) return Map.of("sheet_name", made, "address", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("formulas", formulas);
        List<List<Object>> formats = matrix(src.get("numberFormat"));
        if (!formats.isEmpty()) body.put("numberFormat", formats);
        HttpJson.req(P, "PATCH", book(drive, base, into, ws(made) + rangePath(cells))).bearer(token).json(body).send(ctx);
        return Map.of("sheet_name", made, "address", cells.toUpperCase(Locale.ROOT));
    }

    /** The signature before search_by was added. */
    public Map<String, Object> excelFind(ActionContext ctx, Object token, String remote, String sheet, String text, String range, boolean matchCase, boolean whole,
            String drive, String base) {
        return excelFind(ctx, token, remote, sheet, text, range, matchCase, whole, drive, base, "rows");
    }

    @Action(id = "excel_find", name = "Find in sheet (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Search the used cells of a sheet, or a range, for text and return the addresses of the matching cells.",
            panel = "access_token, remote_path, sheet, [Search only a range: range], text, search_by, match_case, whole_cell | drive, base_url")
    @Output(name = "cells", type = OoaType.LIST, description = "Addresses of the matches, e.g. B7, in search order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matches.")
    public Map<String, Object> excelFind(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for in the displayed cell text.") String text,
            @Input(name = "range", type = OoaType.STRING, required = false, example = "A1:F200", description = "Range to search; the used cells when empty.") String range,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Upper and lower case must match.") boolean matchCase,
            @Input(name = "whole_cell", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The whole cell must equal the text.") boolean whole,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "search_by", type = OoaType.STRING, required = false, defaultValue = "\"rows\"", enumValues = {"rows", "columns"},
                    description = "Order of the results: rows goes across each row first, columns goes down each column first.") String searchBy) {
        if (text == null || text.isEmpty()) throw new ActionException(P + ".BAD_INPUT", "Give the text to find");
        boolean byColumns = "columns".equals(choice(searchBy, "search_by", new LinkedHashSet<>(List.of("rows", "columns"))));
        String addr = range == null || range.isBlank() ? used(ctx, token, drive, base, remote, sheet) : range.trim();
        if (addr == null) return Map.of("cells", List.of(), "count", num(0));
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + rangePath(addr)) + "?$select=address,text").bearer(token).send(ctx).map();
        int[] origin = bounds(addr)[0];
        String needle = matchCase ? text : text.toLowerCase(Locale.ROOT);
        List<Object> found = new ArrayList<>();
        List<List<Object>> grid = matrix(r.get("text"));
        int width = 0;
        for (List<Object> row : grid) width = Math.max(width, row.size());
        // k walks the cells row by row, or column by column with search_by columns
        for (int k = 0; k < grid.size() * width; k++) {
            int i = byColumns ? k % grid.size() : k / width, j = byColumns ? k / grid.size() : k % width;
            if (j >= grid.get(i).size()) continue;
            String v = matchCase ? s(grid.get(i).get(j)) : s(grid.get(i).get(j)).toLowerCase(Locale.ROOT);
            if (whole ? v.equals(needle) : v.contains(needle)) found.add(colName(origin[0] + j) + (origin[1] + i));
        }
        return Map.of("cells", found, "count", num(found.size()));
    }

    @Action(id = "excel_get_cell_format", name = "Read cell format (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read one cell's fill colour, font (name, size, colour, bold, italic, underline), alignment, wrapping and number format.",
            panel = "access_token, remote_path, sheet, cell | drive, base_url")
    @Output(name = "format", type = OoaType.DICTIONARY, description = "fill_color, font_name, font_size, font_color, bold, italic, underline, horizontal_alignment, vertical_alignment, wrap_text, number_format.")
    public Map<String, Object> excelCellFormat(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "cell", type = OoaType.STRING, example = "C7", description = "Cell address.") String cell,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        cell(cell);
        String r = book(drive, base, remote, ws(sheet) + rangePath(cell));
        Map<String, Object> f = HttpJson.req(P, "GET", r + "/format").bearer(token).send(ctx).map();
        Map<String, Object> font = HttpJson.req(P, "GET", r + "/format/font").bearer(token).send(ctx).map();
        Map<String, Object> fill = HttpJson.req(P, "GET", r + "/format/fill").bearer(token).send(ctx).map();
        Map<String, Object> nf = HttpJson.req(P, "GET", r + "?$select=numberFormat").bearer(token).send(ctx).map();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fill_color", fill.get("color"));
        e.put("font_name", font.get("name"));
        e.put("font_size", font.get("size"));
        e.put("font_color", font.get("color"));
        e.put("bold", font.get("bold"));
        e.put("italic", font.get("italic"));
        e.put("underline", font.get("underline"));
        e.put("horizontal_alignment", f.get("horizontalAlignment"));
        e.put("vertical_alignment", f.get("verticalAlignment"));
        e.put("wrap_text", f.get("wrapText"));
        e.put("number_format", first(nf.get("numberFormat")));
        return Map.of("format", e);
    }

    @Action(id = "excel_rename_table", name = "Rename table (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Give an Excel table a new name.",
            panel = "access_token, remote_path, table, new_name | drive, base_url")
    public Map<String, Object> excelRenameTable(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "table", type = OoaType.STRING, description = "Table name or id.") String table,
            @Input(name = "new_name", type = OoaType.STRING, description = "New table name (no spaces).") String newName,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "PATCH", book(drive, base, remote, table(table))).bearer(token).json(Map.of("name", newName)).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_create_workbook", name = "Create workbook (Excel online)", category = "Excel online", capabilities = "network",
            description = "Create a new, empty Excel workbook in OneDrive or SharePoint, optionally with named sheets.",
            panel = "access_token, remote_path | sheet_names, if_exists, drive, base_url")
    @Output(name = "item_id", type = OoaType.STRING, description = "Drive item id of the workbook.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to open the workbook.")
    public Map<String, Object> excelCreateWorkbook(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, example = "Reports/2026/new.xlsx", description = "Path of the new workbook, ending in .xlsx.") String remote,
            @Input(name = "sheet_names", type = OoaType.LIST, required = false, description = "Names of the sheets; one sheet called Sheet1 when empty.") List<Object> names,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"fail\"", enumValues = {"fail", "rename", "replace"}, description = "When a file of that name exists: fail, rename (add a number) or replace it.") String ifExists,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (!remote.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new ActionException(P + ".BAD_INPUT", "The workbook name must end in .xlsx", null);
        List<String> sheets = new ArrayList<>();
        if (names != null) for (Object n : names) if (!Values.text(n).isBlank()) sheets.add(Values.text(n).trim());
        if (sheets.isEmpty()) sheets.add("Sheet1");
        String q = conflict(ifExists == null || ifExists.isBlank() ? "fail" : ifExists, "replace");
        Map<String, Object> r = HttpJson.req(P, "PUT", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":/content" + q).bearer(token)
                .bytes(emptyXlsx(sheets), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    private static String xmlEsc(String v) { return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    /** A minimal valid .xlsx with empty sheets of the given names. */
    static byte[] emptyXlsx(List<String> sheets) {
        Map<String, String> parts = new LinkedHashMap<>();
        StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets.size(); i++) {
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            wb.append("<sheet name=\"").append(xmlEsc(sheets.get(i - 1))).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
            parts.put("xl/worksheets/sheet" + i + ".xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>");
        }
        parts.put("[Content_Types].xml", types.append("</Types>").toString());
        parts.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("xl/workbook.xml", wb.append("</sheets></workbook>").toString());
        parts.put("xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
            for (var e : parts.entrySet()) { z.putNextEntry(new java.util.zip.ZipEntry(e.getKey())); z.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)); z.closeEntry(); }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return out.toByteArray();
    }

    /** Address of the used range, e.g. Sheet1!A1:F42, or null when the sheet is empty. */
    private static String used(ActionContext ctx, Object token, String drive, String base, String remote, String sheet) {
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + "/usedRange(valuesOnly=true)") + "?$select=address,rowCount,columnCount").bearer(token).send(ctx).map();
        String a = s(r.get("address"));
        String cells = a.substring(a.lastIndexOf('!') + 1);
        return cells.isEmpty() || (r.get("rowCount") instanceof Number n && n.intValue() == 0) ? null : cells;
    }

    @Action(id = "excel_find_next_empty_cell", name = "Find next empty cell (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Find the first empty cell after the last value in a column (going down) or in a row (going right), e.g. where to append the next record.",
            panel = "access_token, remote_path, sheet, direction, column, row | drive, base_url")
    @Output(name = "cell", type = OoaType.STRING, description = "Address of the empty cell, e.g. A43.")
    @Output(name = "row", type = OoaType.NUMBER, description = "Its row number.")
    @Output(name = "column", type = OoaType.STRING, description = "Its column letter.")
    public Map<String, Object> excelFindNextEmptyCell(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "direction", type = OoaType.STRING, required = false, defaultValue = "\"down\"", enumValues = {"down", "right"},
                    description = "down: after the last value of a column; right: after the last value of a row.") String direction,
            @Input(name = "column", type = OoaType.STRING, required = false, defaultValue = "\"A\"", pattern = "^[A-Za-z]{1,3}$", visibleWhen = "direction=down", description = "Column letter to look in.") String column,
            @Input(name = "row", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, visibleWhen = "direction=right", description = "Row number to look in.") int row,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        boolean right = "right".equals(direction);
        String col = (column == null || column.isBlank() ? "A" : column.strip()).toUpperCase(Locale.ROOT);
        String line = right ? row + ":" + row : col + ":" + col;
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + "/range(address='" + line + "')/usedRange(valuesOnly=true)") + "?$select=address,rowCount")
                .bearer(token).send(ctx).map();
        String a = s(r.get("address"));
        String cells = a.substring(a.lastIndexOf('!') + 1);
        boolean empty = cells.isEmpty() || (r.get("rowCount") instanceof Number n && n.intValue() == 0);
        if (right) {
            int next = empty ? 1 : bounds(cells)[1][0] + 1;   // column numbers start at 1 (A)
            return Map.of("cell", colName(next) + row, "row", num(row), "column", colName(next));
        }
        int next = empty ? 1 : bounds(cells)[1][1] + 1;
        return Map.of("cell", col + next, "row", num(next), "column", col);
    }

    @Action(id = "excel_used_range", name = "Get used range (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Find the block of cells on a sheet that holds values, with its last row and column.",
            panel = "access_token, remote_path, sheet | drive, base_url")
    @Output(name = "address", type = OoaType.STRING, description = "Used range, e.g. A1:F42; empty for an empty sheet.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Rows in the used range.")
    @Output(name = "column_count", type = OoaType.NUMBER, description = "Columns in the used range.")
    @Output(name = "last_row", type = OoaType.NUMBER, description = "Sheet row number of the last used row (0 when empty).")
    @Output(name = "last_column", type = OoaType.STRING, description = "Letter of the last used column.")
    public Map<String, Object> excelUsedRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String a = used(ctx, token, drive, base, remote, sheet);
        if (a == null) return Map.of("address", "", "row_count", num(0), "column_count", num(0), "last_row", num(0), "last_column", "");
        int[][] b = bounds(a);
        return Map.of("address", a, "row_count", num(b[1][1] - b[0][1] + 1), "column_count", num(b[1][0] - b[0][0] + 1), "last_row", num(b[1][1]), "last_column", colName(b[1][0]));
    }

    @Action(id = "excel_read_row", name = "Read row (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read one sheet row from column A to the last used column.",
            panel = "access_token, remote_path, sheet, row | drive, base_url")
    @Output(name = "values", type = OoaType.LIST, description = "Cell values; the first item is column A.")
    public Map<String, Object> excelReadRow(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row number, starting at 1.") int row,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (row < 1) throw new ActionException(P + ".BAD_RANGE", "Row numbers start at 1", null);
        String a = used(ctx, token, drive, base, remote, sheet);
        if (a == null) return Map.of("values", List.of());
        String addr = "A" + row + ":" + colName(bounds(a)[1][0]) + row;
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + rangePath(addr))).bearer(token).send(ctx).map();
        List<List<Object>> v = matrix(r.get("values"));
        return Map.of("values", v.isEmpty() ? List.of() : v.get(0));
    }

    @Action(id = "excel_read_column", name = "Read column (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read one sheet column from row 1 to the last used row.",
            panel = "access_token, remote_path, sheet, column | drive, base_url")
    @Output(name = "values", type = OoaType.LIST, description = "Cell values; the first item is row 1.")
    public Map<String, Object> excelReadColumn(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "column", type = OoaType.STRING, example = "C", description = "Column letter, or its number starting at 1.") String column,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String col = column.trim().matches("\\d+") ? colName(Integer.parseInt(column.trim())) : column.trim().toUpperCase(Locale.ROOT);
        cell(col + "1");
        String a = used(ctx, token, drive, base, remote, sheet);
        if (a == null) return Map.of("values", List.of());
        String addr = col + "1:" + col + bounds(a)[1][1];
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + rangePath(addr))).bearer(token).send(ctx).map();
        List<Object> out = new ArrayList<>();
        for (List<Object> rr : matrix(r.get("values"))) out.add(rr.isEmpty() ? null : rr.get(0));
        return Map.of("values", out);
    }

    @Action(id = "excel_column_name", name = "Get column name (Excel online)", category = "Excel online", idempotent = true,
            description = "Split a cell address such as AB7 into its column letters, column number and row number; nothing is read from the workbook.",
            panel = "cell")
    @Output(name = "column_name", type = OoaType.STRING, description = "Column letters, e.g. AB.")
    @Output(name = "column", type = OoaType.NUMBER, description = "Column number, starting at 1 for A.")
    @Output(name = "row", type = OoaType.NUMBER, description = "Row number.")
    public Map<String, Object> excelColumnName(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, example = "AB7", description = "Cell address; a sheet name before ! and $ signs are ignored.") String address) {
        if (address == null || address.isBlank()) throw new ActionException(P + ".BAD_RANGE", "Give a cell address", "Example: B2.");
        int[] c = cell(address.substring(address.lastIndexOf('!') + 1));
        return Map.of("column_name", colName(c[0]), "column", num(c[0]), "row", num(c[1]));
    }

    @Action(id = "excel_list_tables", name = "List tables (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "List the Excel tables in a workbook, or on one worksheet.",
            panel = "access_token, remote_path, [Only one worksheet: sheet] | drive, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, style, show_headers.")
    public Map<String, Object> excelListTables(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Only tables on this worksheet; all when empty.") String sheet,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String path = sheet == null || sheet.isBlank() ? "/workbook/tables" : ws(sheet) + "/tables";
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> t : pages(ctx, token, book(drive, base, remote, path), 0, null))
            rows.add(new ArrayList<>(Arrays.asList(t.get("id"), t.get("name"), t.get("style"), t.get("showHeaders"))));
        return Map.of("table", Values.table(List.of("id", "name", "style", "show_headers"), rows));
    }

    @Action(id = "excel_create_table", name = "Create table (Excel online)", category = "Excel online", capabilities = "network",
            description = "Turn a range into an Excel table, optionally naming it.",
            panel = "access_token, remote_path, sheet, name, range, has_headers | drive, base_url")
    @Output(name = "table_id", type = OoaType.STRING, description = "Table id.")
    @Output(name = "table_name", type = OoaType.STRING, description = "Table name.")
    public Map<String, Object> excelCreateTable(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A1:D1", description = "Range the table covers.") String range,
            @Input(name = "has_headers", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "The first row holds column names.") boolean headers,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Table name; Excel picks one when empty.") String name,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        rangePath(range);
        Map<String, Object> t = HttpJson.req(P, "POST", book(drive, base, remote, ws(sheet) + "/tables/add")).bearer(token)
                .json(Map.of("address", range.toUpperCase(Locale.ROOT), "hasHeaders", headers)).send(ctx).map();
        Object tableName = t.get("name");
        if (name != null && !name.isBlank()) {
            HttpJson.req(P, "PATCH", book(drive, base, remote, table(s(t.get("id"))))).bearer(token).json(Map.of("name", name)).send(ctx);
            tableName = name;
        }
        return Out.of("table_id", t.get("id"), "table_name", tableName);
    }

    @Action(id = "excel_add_table_rows", name = "Add table rows (Excel online)", category = "Excel online", capabilities = "network",
            description = "Append rows to the end of an Excel table.",
            panel = "access_token, remote_path, table, rows | drive, base_url")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows added.")
    public Map<String, Object> excelAddTableRows(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "table", type = OoaType.STRING, description = "Table name or id.") String table,
            @Input(name = "rows", description = "List of rows (each a list of values), one flat list for a single row, or a Data Table (rows only).") Object rows,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> g = grid(rows);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("index", null);
        body.put("values", g);
        HttpJson.req(P, "POST", book(drive, base, remote, table(table) + "/rows/add")).bearer(token).json(body).send(ctx);
        return Map.of("count", num(g.size()));
    }

    public Map<String, Object> excelGetTableRows(ActionContext ctx, Object token, String remote, String table, String drive, String base) {
        return excelGetTableRows(ctx, token, remote, table, drive, base, null);
    }

    @Action(id = "excel_get_table_rows", name = "Get table rows (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read all rows of an Excel table, or one row by its position, as a Data Table, using the table's header names as columns.",
            panel = "access_token, remote_path, table, [Only one row: row_index] | drive, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "The table's rows.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows.")
    public Map<String, Object> excelGetTableRows(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "table", type = OoaType.STRING, description = "Table name or id.") String table,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "row_index", type = OoaType.NUMBER, required = false, min = 1, description = "Return only this data row; 1 is the first row under the headers. All rows when empty.") Integer rowIndex) {
        if (rowIndex != null && rowIndex < 1) throw new ActionException(P + ".BAD_INPUT", "row_index starts at 1, not " + rowIndex, "Use 1 for the first row under the headers.");
        Map<String, Object> h = HttpJson.req(P, "GET", book(drive, base, remote, table(table) + "/headerRowRange")).bearer(token).send(ctx).map();
        List<String> cols = new ArrayList<>();
        List<List<Object>> hv = matrix(h.get("values"));
        if (!hv.isEmpty()) for (Object c : hv.get(0)) cols.add(Values.text(c));
        List<Object> rows = new ArrayList<>();
        List<Map<String, Object>> found = rowIndex == null ? pages(ctx, token, book(drive, base, remote, table(table) + "/rows"), 0, null)
                : List.of(HttpJson.req(P, "GET", book(drive, base, remote, table(table) + "/rows/itemAt(index=" + (rowIndex - 1) + ")")).bearer(token).send(ctx).map());
        for (Map<String, Object> r : found) {
            List<List<Object>> v = matrix(r.get("values"));
            rows.add(v.isEmpty() ? new ArrayList<>() : v.get(0));
        }
        return Map.of("table", Values.table(cols, rows), "count", num(rows.size()));
    }

    @Action(id = "excel_delete_table", name = "Delete table (Excel online)", category = "Excel online", capabilities = "network",
            description = "Delete an Excel table together with its data.",
            panel = "access_token, remote_path, table | drive, base_url")
    public Map<String, Object> excelDeleteTable(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "table", type = OoaType.STRING, description = "Table name or id.") String table,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "DELETE", book(drive, base, remote, table(table))).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_write_data_table", name = "Write Data Table (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Write a Data Table to a sheet starting at a cell, with or without its column names.",
            panel = "access_token, remote_path, table, sheet, start_cell | include_headers, drive, base_url")
    @Output(name = "address", type = OoaType.STRING, description = "The range that was written.")
    public Map<String, Object> excelWriteDataTable(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to write.") Map<String, Object> table,
            @Input(name = "start_cell", type = OoaType.STRING, required = false, defaultValue = "\"A1\"", description = "Top-left cell.") String start,
            @Input(name = "include_headers", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write the column names as the first row.") boolean headers,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> g = tableMatrix(table, headers);
        if (g.isEmpty() || list(g.get(0)).isEmpty()) throw new ActionException(P + ".NO_VALUES", "The Data Table is empty", "Check that the table has columns and rows.");
        String address = spanFrom(start, g);
        HttpJson.req(P, "PATCH", book(drive, base, remote, ws(sheet) + rangePath(address))).bearer(token).json(Map.of("values", g)).send(ctx);
        return Map.of("address", address);
    }

    // ------------------------------------------ Calendar -----------------------------------------

    private static String event(String base, String mailbox, String id) { return root(base, mailbox) + "/events/" + HttpJson.enc(id); }

    public Map<String, Object> updateEvent(ActionContext ctx, Object token, String id, String subject, LocalDateTime start, LocalDateTime end, String tz, String body,
            String location, boolean html, String importance, String showAs, String sensitivity, Integer reminder, List<Object> categories, String mailbox, String base) {
        return updateEvent(ctx, token, id, subject, start, end, tz, body, location, html, importance, showAs, sensitivity, reminder, categories, mailbox, base,
                null, null, 1, null, null);
    }

    @Action(id = "update_event", name = "Update calendar event", category = "Calendar", idempotent = true, capabilities = "network",
            description = "Change the title, times, description, location, all-day setting or repeat pattern of an event; empty inputs are left unchanged.",
            panel = "access_token, event_id, start, end, time_zone, subject, body, location, [Change the reminder: reminder_minutes], [Change all day: all_day], [Change the repeat: recurrence, recurrence_interval, recurrence_end, recurrence_count] | html, importance, show_as, sensitivity, categories, mailbox, base_url")
    @Output(name = "web_link", type = OoaType.STRING, description = "Link to open the event in Outlook.")
    public Map<String, Object> updateEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = "New title.") String subject,
            @Input(name = "start", type = OoaType.DATETIME, required = false, description = "New start date and time.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, required = false, description = "New end date and time.") LocalDateTime end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone of start and end.") String tz,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "New description.") String body,
            @Input(name = "location", type = OoaType.STRING, required = false, description = "New location.") String location,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The description is HTML.") boolean html,
            @Input(name = "importance", type = OoaType.STRING, required = false, enumValues = {"low", "normal", "high"}, description = "Importance; Outlook's default when empty.") String importance,
            @Input(name = "show_as", type = OoaType.STRING, required = false, enumValues = {"free", "tentative", "busy", "oof", "workingElsewhere"}, description = "How the time shows in the calendar (oof = out of office); busy when empty.") String showAs,
            @Input(name = "sensitivity", type = OoaType.STRING, required = false, enumValues = {"normal", "personal", "private", "confidential"}, description = "Privacy of the event; normal when empty.") String sensitivity,
            @Input(name = "reminder_minutes", type = OoaType.NUMBER, required = false, description = "Reminder this many minutes before the start; 0 turns the reminder off; Outlook's default when empty.") Integer reminder,
            @Input(name = "categories", type = OoaType.LIST, required = false, description = "Outlook category names, e.g. [\"Finance\"].") List<Object> categories,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "all_day", type = OoaType.BOOLEAN, required = false, description = "true makes it an all-day event (only the dates count), false a timed one; unchanged when empty.") Boolean allDay,
            @Input(name = "recurrence", type = OoaType.STRING, required = false, enumValues = {"none", "daily", "weekly", "monthly", "yearly"},
                    description = "New repeat pattern for the whole series; none stops repeating; unchanged when empty. Weekly repeats on the start's weekday, monthly on its day of the month.") String recurrence,
            @Input(name = "recurrence_interval", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Repeat every this many days, weeks, months or years.") int interval,
            @Input(name = "recurrence_end", type = OoaType.DATETIME, required = false, description = "Last date of the series; it has no end when empty and no count is given.") LocalDateTime recurrenceEnd,
            @Input(name = "recurrence_count", type = OoaType.NUMBER, required = false, description = "Number of occurrences, instead of an end date.") Integer count) {
        if (start != null && end != null && !end.isAfter(start) && !Boolean.TRUE.equals(allDay)) throw new ActionException(P + ".BAD_TIMES", "The end must be after the start");
        boolean repeat = recurrence != null && !recurrence.isBlank();
        if (repeat) choice(recurrence, "recurrence", new LinkedHashSet<>(List.of("none", "daily", "weekly", "monthly", "yearly")));
        String url = event(base, mailbox, id);
        Map<String, Object> current = Map.of();
        String currentTz = tz;
        if (allDay != null || repeat) {
            current = HttpJson.req(P, "GET", url + "?$select=start,end,isAllDay,type,seriesMasterId").bearer(token).send(ctx).map();
            if ((start == null || end == null) && at(current, "start", "timeZone") != null) currentTz = s(at(current, "start", "timeZone"));
        }
        LocalDateTime s0 = start != null ? start : eventTime(at(current, "start", "dateTime"));
        LocalDateTime e0 = end != null ? end : eventTime(at(current, "end", "dateTime"));
        String zone = start != null || end != null ? tz : currentTz;
        Map<String, Object> ev = new LinkedHashMap<>();
        if (subject != null) ev.put("subject", subject);
        if (Boolean.TRUE.equals(allDay)) {
            if (s0 == null || e0 == null) throw new ActionException(P + ".BAD_TIMES", "The event's start and end could not be read", "Give start and end.");
            s0 = s0.toLocalDate().atStartOfDay();
            e0 = e0.toLocalDate().atStartOfDay();
            if (!e0.isAfter(s0)) e0 = s0.plusDays(1);
            ev.put("isAllDay", true);
            ev.put("start", Map.of("dateTime", s0.toString(), "timeZone", zone));
            ev.put("end", Map.of("dateTime", e0.toString(), "timeZone", zone));
        } else {
            if (Boolean.FALSE.equals(allDay)) ev.put("isAllDay", false);
            if (start != null) ev.put("start", Map.of("dateTime", start.toString(), "timeZone", tz));
            if (end != null) ev.put("end", Map.of("dateTime", end.toString(), "timeZone", tz));
        }
        if (body != null) ev.put("body", Map.of("contentType", html ? "HTML" : "Text", "content", body));
        if (location != null) ev.put("location", Map.of("displayName", location));
        eventOptions(ev, importance, showAs, sensitivity, reminder, categories);
        if (repeat) {
            if (s0 == null && !recurrence.trim().equalsIgnoreCase("none")) throw new ActionException(P + ".BAD_TIMES", "The event's start could not be read", "Give start.");
            ev.put("recurrence", recurrence.trim().equalsIgnoreCase("none") ? null : recurrence(recurrence, interval, s0, recurrenceEnd, count));
            String type = s(current.get("type"));
            if ((type.equals("occurrence") || type.equals("exception")) && !s(current.get("seriesMasterId")).isEmpty())
                url = event(base, mailbox, s(current.get("seriesMasterId")));
        }
        if (ev.isEmpty()) throw new ActionException(P + ".NOTHING_TO_DO", "No changes were given", "Set at least one of subject, start, end, body or location.");
        Map<String, Object> r = HttpJson.req(P, "PATCH", url).bearer(token).json(ev).send(ctx).map();
        return Out.of("web_link", r.get("webLink"));
    }

    /** Date and time of a Graph dateTimeTimeZone value, e.g. 2026-10-20T09:00:00.0000000; null when missing. */
    private static LocalDateTime eventTime(Object v) {
        if (v == null || s(v).isBlank()) return null;
        try { return LocalDateTime.parse(s(v)); }
        catch (java.time.format.DateTimeParseException e) { return null; }
    }

    @Action(id = "cancel_event", name = "Cancel calendar event", category = "Calendar", capabilities = "network",
            description = "Cancel a meeting you organise and send attendees a cancellation note.",
            panel = "access_token, event_id, message | mailbox, base_url")
    public Map<String, Object> cancelEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "message", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Note sent to attendees.") String message,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "POST", event(base, mailbox, id) + "/cancel").bearer(token).json(Map.of("comment", message == null ? "" : message)).send(ctx);
        return Map.of();
    }

    @Action(id = "delete_event", name = "Delete calendar event", category = "Calendar", capabilities = "network",
            description = "Remove an event from the calendar without sending a cancellation note.",
            panel = "access_token, event_id | mailbox, base_url")
    public Map<String, Object> deleteEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "DELETE", event(base, mailbox, id)).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "respond_to_event", name = "Respond to meeting invitation", category = "Calendar", capabilities = "network",
            description = "Accept, tentatively accept or decline a meeting invitation, with an optional note to the organiser.",
            panel = "access_token, event_id, response, [Add a note: comment] | send_response, mailbox, base_url")
    public Map<String, Object> respondEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "response", type = OoaType.STRING, enumValues = {"accept", "tentative", "decline"}, description = "accept, tentative or decline.") String response,
            @Input(name = "comment", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Note to the organiser.") String comment,
            @Input(name = "send_response", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Let the organiser know.") boolean send,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String op = switch (response.toLowerCase(Locale.ROOT)) {
            case "accept" -> "accept"; case "tentative" -> "tentativelyAccept"; case "decline" -> "decline";
            default -> throw new ActionException(P + ".BAD_OPTION", "Unknown response '" + response + "'", "Use accept, tentative or decline.");
        };
        HttpJson.req(P, "POST", event(base, mailbox, id) + "/" + op).bearer(token).json(Map.of("comment", comment == null ? "" : comment, "sendResponse", send)).send(ctx);
        return Map.of();
    }

    private List<Object> currentAttendees(ActionContext ctx, Object token, String url) {
        return new ArrayList<>(list(HttpJson.req(P, "GET", url + "?$select=attendees").bearer(token).send(ctx).map().get("attendees")));
    }

    private static List<Object> addresses(List<Object> attendees) {
        List<Object> out = new ArrayList<>();
        for (Object a : attendees) out.add(s(at(a, "emailAddress", "address")));
        return out;
    }

    @Action(id = "add_attendees", name = "Add event attendees", category = "Calendar", capabilities = "network",
            description = "Invite more people to an existing event; people already invited are not added twice.",
            panel = "access_token, event_id, attendees, type | mailbox, base_url")
    @Output(name = "attendees", type = OoaType.LIST, description = "Attendee addresses after the change.")
    public Map<String, Object> addAttendees(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "attendees", description = "Address(es) to invite.") Object add,
            @Input(name = "type", type = OoaType.STRING, required = false, defaultValue = "\"required\"", enumValues = {"required", "optional", "resource"}, description = "required, optional or resource.") String type,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String url = event(base, mailbox, id);
        List<Object> at = currentAttendees(ctx, token, url);
        Set<String> have = new HashSet<>();
        for (Object a : addresses(at)) have.add(s(a).toLowerCase(Locale.ROOT));
        for (String a : HttpJson.addressList(add))
            if (have.add(a.toLowerCase(Locale.ROOT))) at.add(Map.of("emailAddress", Map.of("address", a), "type", type == null ? "required" : type));
        HttpJson.req(P, "PATCH", url).bearer(token).json(Map.of("attendees", at)).send(ctx);
        return Map.of("attendees", addresses(at));
    }

    @Action(id = "remove_attendees", name = "Remove event attendees", category = "Calendar", capabilities = "network",
            description = "Take people off an existing event's attendee list.",
            panel = "access_token, event_id, attendees | mailbox, base_url")
    @Output(name = "attendees", type = OoaType.LIST, description = "Attendee addresses after the change.")
    public Map<String, Object> removeAttendees(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "attendees", description = "Address(es) to remove.") Object remove,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String url = event(base, mailbox, id);
        Set<String> drop = new HashSet<>();
        for (String a : HttpJson.addressList(remove)) drop.add(a.toLowerCase(Locale.ROOT));
        List<Object> at = currentAttendees(ctx, token, url);
        at.removeIf(a -> drop.contains(s(at(a, "emailAddress", "address")).toLowerCase(Locale.ROOT)));
        HttpJson.req(P, "PATCH", url).bearer(token).json(Map.of("attendees", at)).send(ctx);
        return Map.of("attendees", addresses(at));
    }

    @Action(id = "clear_event_details", name = "Clear event details", category = "Calendar", idempotent = true, capabilities = "network",
            description = "Blank the ticked details of an event: title, location, description, reminder, repeat pattern or all-day setting.",
            panel = "access_token, event_id, clear_subject, clear_location, clear_body, clear_reminder, clear_recurrence, clear_all_day | mailbox, base_url")
    @Output(name = "web_link", type = OoaType.STRING, description = "Link to open the event in Outlook.")
    public Map<String, Object> clearEventDetails(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "clear_subject", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Empty the title.") boolean subject,
            @Input(name = "clear_location", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Empty the location.") boolean location,
            @Input(name = "clear_body", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Empty the description.") boolean body,
            @Input(name = "clear_reminder", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Turn the reminder off.") boolean reminder,
            @Input(name = "clear_recurrence", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop the series repeating; for one occurrence the whole series is changed.") boolean recurrence,
            @Input(name = "clear_all_day", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Make an all-day event a timed one, keeping its dates.") boolean allDay,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> ev = new LinkedHashMap<>();
        if (subject) ev.put("subject", "");
        if (location) ev.put("location", Map.of("displayName", ""));
        if (body) ev.put("body", Map.of("contentType", "Text", "content", ""));
        if (reminder) ev.put("isReminderOn", false);
        if (allDay) ev.put("isAllDay", false);
        if (recurrence) ev.put("recurrence", null);
        if (ev.isEmpty()) throw new ActionException(P + ".NOTHING_TO_DO", "Nothing was ticked to clear", "Tick at least one detail to clear.");
        String url = event(base, mailbox, id);
        if (recurrence) {
            // the repeat pattern lives on the series master, as in update_event
            Map<String, Object> current = HttpJson.req(P, "GET", url + "?$select=type,seriesMasterId").bearer(token).send(ctx).map();
            String type = s(current.get("type"));
            if ((type.equals("occurrence") || type.equals("exception")) && !s(current.get("seriesMasterId")).isEmpty())
                url = event(base, mailbox, s(current.get("seriesMasterId")));
        }
        Map<String, Object> r = HttpJson.req(P, "PATCH", url).bearer(token).json(ev).send(ctx).map();
        return Out.of("web_link", r.get("webLink"));
    }

    @Action(id = "add_event_attachment", name = "Add event attachment", category = "Calendar", capabilities = {"network", "filesystem"},
            description = "Attach a local file of up to 3 MB to a calendar event.",
            panel = "access_token, event_id, file | mailbox, base_url")
    @Output(name = "attachment_id", type = OoaType.STRING, description = "Attachment id.")
    public Map<String, Object> addEventAttachment(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "file", type = OoaType.FILE, description = "File to attach.") String file,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "POST", event(base, mailbox, id) + "/attachments").bearer(token).json(fileAttachment(ctx, file)).send(ctx).map();
        return Out.of("attachment_id", r.get("id"));
    }

    public Map<String, Object> findMeetingTimes(ActionContext ctx, Object token, Object attendees, LocalDateTime from, LocalDateTime to, int minutes, String tz, int max,
            String mailbox, String base) {
        return findMeetingTimes(ctx, token, attendees, from, to, minutes, tz, max, mailbox, base, false);
    }

    @Action(id = "find_meeting_times", name = "Find free meeting times", category = "Calendar", idempotent = true, capabilities = "network",
            description = "Suggest time slots in a period when the attendees are free for a meeting of the given length.",
            panel = "access_token, attendees, duration_minutes, from, to | include_outside_working_hours, time_zone, max_results, mailbox, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: start, end, confidence, organizer_availability.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of suggestions.")
    @Output(name = "reason", type = OoaType.STRING, description = "Why there are no suggestions, when there are none.")
    public Map<String, Object> findMeetingTimes(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "attendees", description = "Attendee address(es).") Object attendees,
            @Input(name = "from", type = OoaType.DATETIME, description = "Start of the search period.") LocalDateTime from,
            @Input(name = "to", type = OoaType.DATETIME, description = "End of the search period.") LocalDateTime to,
            @Input(name = "duration_minutes", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Meeting length in minutes.") int minutes,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone of from, to and the results.") String tz,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "Most suggestions.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "include_outside_working_hours", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also suggest times outside the attendees' working hours.") boolean anyTime) {
        if (!to.isAfter(from)) throw new ActionException(P + ".BAD_TIMES", "The end of the period must be after its start");
        List<Object> at = new ArrayList<>();
        for (String a : HttpJson.addressList(attendees)) at.add(Map.of("emailAddress", Map.of("address", a), "type", "required"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attendees", at);
        body.put("timeConstraint", Map.of("activityDomain", anyTime ? "unrestricted" : "work", "timeSlots", List.of(Map.of(
                "start", Map.of("dateTime", from.toString(), "timeZone", tz), "end", Map.of("dateTime", to.toString(), "timeZone", tz)))));
        body.put("meetingDuration", "PT" + Math.max(1, minutes) + "M");
        body.put("maxCandidates", Math.max(1, max));
        body.put("returnSuggestionReasons", true);
        Map<String, Object> r = HttpJson.req(P, "POST", root(base, mailbox) + "/findMeetingTimes").bearer(token)
                .header("Prefer", "outlook.timezone=\"" + tz.replace("\"", "") + "\"").json(body).send(ctx).map();
        List<Object> rows = new ArrayList<>();
        for (Object o : list(r.get("meetingTimeSuggestions")))
            rows.add(new ArrayList<>(Arrays.asList(at(o, "meetingTimeSlot", "start", "dateTime"), at(o, "meetingTimeSlot", "end", "dateTime"),
                    map(o).get("confidence"), map(o).get("organizerAvailability"))));
        return Map.of("table", Values.table(List.of("start", "end", "confidence", "organizer_availability"), rows), "count", num(rows.size()),
                "reason", s(r.get("emptySuggestionsReason")));
    }

    // -------------------------------------------- Teams ------------------------------------------

    private static List<Map<String, Object>> joinedTeams(ActionContext ctx, Object token, String base, String user) {
        return pages(ctx, token, root(base, user) + "/joinedTeams", 0, null);
    }

    private static List<Map<String, Object>> channels(ActionContext ctx, Object token, String base, String team) {
        return pages(ctx, token, g(base) + "/teams/" + HttpJson.enc(team) + "/channels", 0, null);
    }

    private static Map<String, Object> byName(List<Map<String, Object>> items, String name, String what) {
        for (Map<String, Object> m : items) if (s(m.get("displayName")).equalsIgnoreCase(name.trim())) return m;
        List<String> names = items.stream().map(m -> s(m.get("displayName"))).limit(20).toList();
        throw new ActionException(P + ".NOT_FOUND", "No " + what + " named '" + name + "'", names.isEmpty() ? "Check the name and the account's access." : "Available: " + String.join(", ", names) + ".");
    }

    @Action(id = "list_teams", name = "List teams (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "List the Microsoft Teams teams the user belongs to.",
            panel = "access_token, user | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, description.")
    public Map<String, Object> listTeams(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "user", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = "me, or a user's id or email address.") String user,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> t : joinedTeams(ctx, token, base, user)) rows.add(new ArrayList<>(Arrays.asList(t.get("id"), t.get("displayName"), t.get("description"))));
        return Map.of("table", Values.table(List.of("id", "name", "description"), rows));
    }

    @Action(id = "get_team_by_name", name = "Get team by name (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Find the id of a team the user belongs to from its display name.",
            panel = "access_token, name | user, base_url")
    @Output(name = "team_id", type = OoaType.STRING, description = "Team id.")
    public Map<String, Object> teamByName(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name", type = OoaType.STRING, description = "Team display name (not case-sensitive).") String name,
            @Input(name = "user", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = "me, or a user's id or email address.") String user,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return Map.of("team_id", s(byName(joinedTeams(ctx, token, base, user), name, "team").get("id")));
    }

    @Action(id = "list_channels", name = "List channels (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "List the channels of a team.",
            panel = "access_token, team_id | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, description, membership_type.")
    public Map<String, Object> listChannels(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, description = "Team id.") String team,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> c : channels(ctx, token, base, team))
            rows.add(new ArrayList<>(Arrays.asList(c.get("id"), c.get("displayName"), c.get("description"), c.get("membershipType"))));
        return Map.of("table", Values.table(List.of("id", "name", "description", "membership_type"), rows));
    }

    public Map<String, Object> channelByName(ActionContext ctx, Object token, String team, String name, String base) {
        return channelByName(ctx, token, team, name, base, null);
    }

    @Action(id = "get_channel_by_name", name = "Get channel by name (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Find the id of a team's channel from its display name.",
            panel = "access_token, (Team: By id = team_id / By name = team_name), name | base_url")
    @Output(name = "channel_id", type = OoaType.STRING, description = "Channel id.")
    public Map<String, Object> channelByName(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, required = false, description = "Team id; or give team_name instead.") String team,
            @Input(name = "name", type = OoaType.STRING, description = "Channel display name (not case-sensitive), e.g. General.") String name,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "team_name", type = OoaType.STRING, required = false, example = "Finance", description = "Team display name (not case-sensitive), among the teams the signed-in user belongs to; used instead of team_id.") String teamName) {
        boolean byId = team != null && !team.isBlank(), byTeamName = teamName != null && !teamName.isBlank();
        if (byId == byTeamName) throw new ActionException(P + ".BAD_INPUT", byId ? "Give team_id or team_name, not both" : "A team is required", "Set team_id or team_name.");
        String t = byId ? team.trim() : s(byName(joinedTeams(ctx, token, base, "me"), teamName, "team").get("id"));
        return Map.of("channel_id", s(byName(channels(ctx, token, base, t), name, "channel").get("id")));
    }

    @Action(id = "get_team_details", name = "Get team details (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Read a team's name, description, visibility, archive state and link.",
            panel = "access_token, team_id | base_url")
    @Output(name = "team", type = OoaType.DICTIONARY, description = "id, name, description, visibility, archived, web_url.")
    public Map<String, Object> teamDetails(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, description = "Team id.") String team,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> t = HttpJson.req(P, "GET", g(base) + "/teams/" + HttpJson.enc(team)).bearer(token).send(ctx).map();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", t.get("id"));
        e.put("name", t.get("displayName"));
        e.put("description", t.get("description"));
        e.put("visibility", t.get("visibility"));
        e.put("archived", Boolean.TRUE.equals(t.get("isArchived")));
        e.put("web_url", t.get("webUrl"));
        return Map.of("team", e);
    }

    @Action(id = "get_channel_details", name = "Get channel details (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Read a channel's name, description, membership type, email address, creation date and link.",
            panel = "access_token, team_id, channel_id | base_url")
    @Output(name = "channel", type = OoaType.DICTIONARY, description = "id, name, description, membership_type, email, created, web_url.")
    public Map<String, Object> channelDetails(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, description = "Team id.") String team,
            @Input(name = "channel_id", type = OoaType.STRING, description = "Channel id.") String channel,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> c = HttpJson.req(P, "GET", g(base) + "/teams/" + HttpJson.enc(team) + "/channels/" + HttpJson.enc(channel)).bearer(token).send(ctx).map();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", c.get("id"));
        e.put("name", c.get("displayName"));
        e.put("description", c.get("description"));
        e.put("membership_type", c.get("membershipType"));
        e.put("email", c.get("email"));
        e.put("created", c.get("createdDateTime"));
        e.put("web_url", c.get("webUrl"));
        return Map.of("channel", e);
    }

    @Action(id = "get_user", name = "Get user (Microsoft 365)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Read a person's directory profile: name, email, job title, department, office and phone.",
            panel = "access_token, user | base_url")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "id, name, email, user_principal_name, job_title, department, office, mobile_phone.")
    public Map<String, Object> getUser(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "user", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = "me, or a user's id or email address.") String user,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> u = HttpJson.req(P, "GET", root(base, user) + "?$select=id,displayName,mail,userPrincipalName,jobTitle,department,officeLocation,mobilePhone")
                .bearer(token).send(ctx).map();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", u.get("id"));
        e.put("name", u.get("displayName"));
        e.put("email", u.get("mail"));
        e.put("user_principal_name", u.get("userPrincipalName"));
        e.put("job_title", u.get("jobTitle"));
        e.put("department", u.get("department"));
        e.put("office", u.get("officeLocation"));
        e.put("mobile_phone", u.get("mobilePhone"));
        return Map.of("user", e);
    }

    public Map<String, Object> sendChatMessage(ActionContext ctx, Object token, String chat, String message, boolean html, String importance, String base) {
        return sendChatMessage(ctx, token, chat, message, html, importance, base, null);
    }

    @Action(id = "send_chat_message", name = "Send chat message (Teams)", category = "Teams", capabilities = "network",
            description = "Send a message into an existing Teams chat, or straight to one person, starting a one-on-one chat with them when needed.",
            panel = "access_token, (Send to: A chat = chat_id / A person = to), message | html, importance, base_url")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message id.")
    @Output(name = "chat_id", type = OoaType.STRING, description = "Chat the message went to.")
    public Map<String, Object> sendChatMessage(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "chat_id", type = OoaType.STRING, required = false, description = "Chat id, e.g. from Create chat; or give to instead.") String chat,
            @Input(name = "message", type = OoaType.STRING, description = "Message text (HTML allowed when html is true).") String message,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Message is HTML.") boolean html,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "urgent"}, description = "Importance of the message.") String importance,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base,
            @Input(name = "to", type = OoaType.STRING, required = false, example = "ann@contoso.com", description = "Person's email address or user id; their one-on-one chat with the signed-in user is used, or started. Used instead of chat_id.") String to) {
        boolean byChat = chat != null && !chat.isBlank(), byPerson = to != null && !to.isBlank();
        if (byChat == byPerson) throw new ActionException(P + ".BAD_INPUT", byChat ? "Give chat_id or to, not both" : "A chat or a person is required", "Set chat_id, or to with an email address.");
        Map<String, Object> body = chatBody(message, html, importance);
        String id = byChat ? chat.trim() : s(createChat(ctx, token, to, base).get("chat_id"));
        Map<String, Object> r = HttpJson.req(P, "POST", g(base) + "/chats/" + HttpJson.enc(id) + "/messages").bearer(token).json(body).send(ctx).map();
        return Out.of("message_id", r.get("id"), "chat_id", id);
    }

    @Action(id = "create_chat", name = "Create one-on-one chat (Teams)", category = "Teams", capabilities = "network",
            description = "Start, or reuse, a one-on-one Teams chat between the signed-in user and another person.",
            panel = "access_token, user | base_url")
    @Output(name = "chat_id", type = OoaType.STRING, description = "Chat id for Send chat message.")
    public Map<String, Object> createChat(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "user", type = OoaType.STRING, description = "Other person's id or email address.") String user,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String me = s(HttpJson.req(P, "GET", g(base) + "/me?$select=id").bearer(token).send(ctx).map().get("id"));
        List<Object> members = new ArrayList<>();
        for (String u : List.of(me, user.trim()))
            members.add(Map.of("@odata.type", "#microsoft.graph.aadUserConversationMember", "roles", List.of("owner"),
                    "user@odata.bind", g(base) + "/users('" + u.replace("'", "''") + "')"));
        Map<String, Object> r = HttpJson.req(P, "POST", g(base) + "/chats").bearer(token).json(Map.of("chatType", "oneOnOne", "members", members)).send(ctx).map();
        return Out.of("chat_id", r.get("id"));
    }

    // ---------------------------------------- SharePoint lists -----------------------------------

    static String site(String base, String site) {
        String st = site.trim().replaceAll("^/+|/+$", "");
        if (st.startsWith("sites/")) st = st.substring(6);
        int i = st.indexOf(":/");
        if (i > 0) return g(base) + "/sites/" + HttpJson.enc(st.substring(0, i)) + ":/" + HttpJson.encPath(st.substring(i + 2).replaceAll(":$", "")) + ":";
        return g(base) + "/sites/" + HttpJson.enc(st).replace("%2C", ",");
    }

    private static String spList(String base, String site, String list) { return site(base, site) + "/lists/" + HttpJson.enc(list); }

    @Action(id = "list_lists", name = "List SharePoint lists", category = "SharePoint", idempotent = true, capabilities = "network",
            description = "List the lists and libraries of a SharePoint site.",
            panel = "access_token, site | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, display_name, template, web_url.")
    public Map<String, Object> listLists(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> l : pages(ctx, token, site(base, site) + "/lists", 0, null))
            rows.add(new ArrayList<>(Arrays.asList(l.get("id"), l.get("name"), l.get("displayName"), at(l, "list", "template"), l.get("webUrl"))));
        return Map.of("table", Values.table(List.of("id", "name", "display_name", "template", "web_url"), rows));
    }

    @Action(id = "get_list_items", name = "Get SharePoint list items", category = "SharePoint", idempotent = true, capabilities = "network",
            description = "Read the items of a SharePoint list as a Data Table, optionally filtered and limited to some columns.",
            panel = "access_token, site, list, [Filter the items: filter], columns | max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Column id, then one column per list field.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items.")
    public Map<String, Object> getListItems(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "list", type = OoaType.STRING, description = D_LIST) String list,
            @Input(name = "filter", type = OoaType.STRING, required = false, example = "fields/Status eq 'Open'", description = "OData filter on fields, e.g. fields/Status eq 'Open'.") String filter,
            @Input(name = "columns", type = OoaType.LIST, required = false, description = "Field names to return; all when empty.") List<Object> columns,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Maximum items.") int max,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> q = new LinkedHashMap<>();
        boolean pick = columns != null && !columns.isEmpty();
        q.put("$expand", pick ? "fields($select=" + String.join(",", columns.stream().map(Values::text).toList()) + ")" : "fields");
        q.put("$top", Math.min(999, Math.max(1, max)));
        if (filter != null && !filter.isBlank()) q.put("$filter", filter);
        List<Map<String, Object>> items = pages(ctx, token, spList(base, site, list) + "/items?" + HttpJson.query(q), Math.max(1, max),
                filter != null && !filter.isBlank() ? "HonorNonIndexedQueriesWarningMayFailRandomly" : null);
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        cols.add("id");
        if (pick) for (Object c : columns) cols.add(Values.text(c));
        else for (Map<String, Object> it : items) for (String k : map(it.get("fields")).keySet()) if (!k.startsWith("@odata") && !k.equals("id")) cols.add(k);
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> f = map(it.get("fields"));
            List<Object> row = new ArrayList<>();
            for (String c : cols) row.add(c.equals("id") ? it.get("id") : f.get(c));
            rows.add(row);
        }
        return Map.of("table", Values.table(new ArrayList<>(cols), rows), "count", num(rows.size()));
    }

    @Action(id = "create_list_item", name = "Create SharePoint list item", category = "SharePoint", capabilities = "network",
            description = "Add an item to a SharePoint list from a dictionary of field names and values.",
            panel = "access_token, site, list, fields | base_url")
    @Output(name = "item_id", type = OoaType.STRING, description = "New item id.")
    public Map<String, Object> createListItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "list", type = OoaType.STRING, description = D_LIST) String list,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field internal names and values, e.g. {\"Title\":\"Laptop\",\"Status\":\"Open\"}.") Map<String, Object> fields,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "POST", spList(base, site, list) + "/items").bearer(token).json(Map.of("fields", fields)).send(ctx).map();
        return Out.of("item_id", r.get("id"));
    }

    @Action(id = "update_list_item", name = "Update SharePoint list item", category = "SharePoint", idempotent = true, capabilities = "network",
            description = "Change some fields of a SharePoint list item; other fields keep their values.",
            panel = "access_token, site, list, item_id, fields | base_url")
    public Map<String, Object> updateListItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "list", type = OoaType.STRING, description = D_LIST) String list,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field internal names and their new values.") Map<String, Object> fields,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "PATCH", spList(base, site, list) + "/items/" + HttpJson.enc(id) + "/fields").bearer(token).json(fields).send(ctx);
        return Map.of();
    }

    @Action(id = "delete_list_item", name = "Delete SharePoint list item", category = "SharePoint", capabilities = "network",
            description = "Delete an item from a SharePoint list; it goes to the site's recycle bin.",
            panel = "access_token, site, list, item_id | base_url")
    public Map<String, Object> deleteListItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "list", type = OoaType.STRING, description = D_LIST) String list,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "DELETE", spList(base, site, list) + "/items/" + HttpJson.enc(id)).bearer(token).send(ctx);
        return Map.of();
    }
}
