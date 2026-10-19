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
            description = "Send an email from a Microsoft 365 mailbox with optional CC, BCC and attachments (up to 3 MB each).")
    public Map<String, Object> sendMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Message body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
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
        HttpJson.req(P, "POST", root(base, mailbox) + "/sendMail").bearer(token).json(Map.of("message", msg, "saveToSentItems", true)).send(ctx);
        return Map.of();
    }

    @Action(id = "list_mail", name = "Find emails (Outlook)", category = "Mail", idempotent = true, capabilities = "network",
            description = "List emails in a mail folder, newest first, optionally only unread or matching sender/subject text.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, from, subject, received, unread, has_attachments.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails.")
    public Map<String, Object> listMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = "Folder id or well-known name: inbox, archive, sentitems, deleteditems.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text.") String subject,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Maximum emails.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("$top", Math.min(1000, Math.max(1, max * (from != null || subject != null ? 4 : 1))));
        q.put("$select", "id,subject,from,receivedDateTime,isRead,hasAttachments");
        q.put("$orderby", "receivedDateTime desc");
        if (unread) q.put("$filter", "isRead eq false");
        List<Object> rows = new ArrayList<>();
        String url = root(base, mailbox) + "/mailFolders/" + HttpJson.enc(folder) + "/messages?" + HttpJson.query(q);
        while (url != null && rows.size() < max) {
            Map<String, Object> page = HttpJson.req(P, "GET", url).bearer(token).send(ctx).map();
            for (Object o : (List<?>) page.getOrDefault("value", List.of())) {
                Map<?, ?> m = (Map<?, ?>) o;
                String sender = m.get("from") instanceof Map<?, ?> f && f.get("emailAddress") instanceof Map<?, ?> ea ? String.valueOf(ea.get("address")) : "";
                String subj = String.valueOf(m.get("subject") == null ? "" : m.get("subject"));
                if (from != null && !sender.toLowerCase(Locale.ROOT).contains(from.toLowerCase(Locale.ROOT))) continue;
                if (subject != null && !subj.toLowerCase(Locale.ROOT).contains(subject.toLowerCase(Locale.ROOT))) continue;
                rows.add(new ArrayList<>(Arrays.asList(m.get("id"), sender, subj, m.get("receivedDateTime"), !Boolean.TRUE.equals(m.get("isRead")), Boolean.TRUE.equals(m.get("hasAttachments")))));
                if (rows.size() >= max) break;
            }
            url = (String) page.get("@odata.nextLink");
        }
        return Map.of("table", Values.table(List.of("id", "from", "subject", "received", "unread", "has_attachments"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_mail", name = "Read email (Outlook)", category = "Mail", capabilities = "network", description = "Read one email's details and body; optionally mark it read.")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, from, to, subject, received, body, body_type, has_attachments.")
    public Map<String, Object> getMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id from Find emails.") String id,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Mark as read.") boolean markRead,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        String url = root(base, mailbox) + "/messages/" + HttpJson.enc(id);
        Map<String, Object> m = HttpJson.req(P, "GET", url).bearer(token).send(ctx).map();
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
        if (markRead && !Boolean.TRUE.equals(m.get("isRead"))) HttpJson.req(P, "PATCH", url).bearer(token).json(Map.of("isRead", true)).send(ctx);
        return Map.of("email", e);
    }

    @Action(id = "save_mail_attachments", name = "Save email attachments (Outlook)", category = "Mail", capabilities = {"network", "filesystem"},
            description = "Save an email's file attachments to a folder, optionally only names matching a pattern such as *.pdf.")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern.") String pattern,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Map<String, Object> page = HttpJson.req(P, "GET", root(base, mailbox) + "/messages/" + HttpJson.enc(id) + "/attachments").bearer(token).send(ctx).map();
        Path dir = ctx.resolvePath(target);
        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + pattern.toLowerCase(Locale.ROOT));
        List<Object> saved = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (Object o : (List<?>) page.getOrDefault("value", List.of())) {
                Map<?, ?> a = (Map<?, ?>) o;
                if (a.get("contentBytes") == null) continue;  // item or reference attachments are not files
                String name = HttpJson.safeName(String.valueOf(a.get("name")));
                if (!pm.matches(Path.of(name.toLowerCase(Locale.ROOT)))) continue;
                Path out = dir.resolve(name);
                for (int i = 2; Files.exists(out); i++) out = dir.resolve(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
                Files.write(out, Base64.getDecoder().decode(String.valueOf(a.get("contentBytes"))));
                saved.add(out.toString());
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("files", saved);
    }

    @Action(id = "move_mail", name = "Move email (Outlook)", category = "Mail", capabilities = "network", description = "Move an email to another folder (id or well-known name such as archive).")
    @Output(name = "new_id", type = OoaType.STRING, description = "The email's id in the destination folder.")
    public Map<String, Object> moveMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder id or well-known name.") String to,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "POST", root(base, mailbox) + "/messages/" + HttpJson.enc(id) + "/move").bearer(token).json(Map.of("destinationId", to)).send(ctx).map();
        return Out.of("new_id", r.get("id"));
    }

    @Action(id = "create_event", name = "Create calendar event", category = "Calendar", capabilities = "network", description = "Create a meeting or appointment, optionally inviting attendees.")
    @Output(name = "event_id", type = OoaType.STRING, description = "Event id.")
    @Output(name = "web_link", type = OoaType.STRING, description = "Link to open the event in Outlook.")
    public Map<String, Object> createEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "subject", type = OoaType.STRING, description = "Title.") String subject,
            @Input(name = "start", type = OoaType.DATETIME, description = "Start date and time.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "End date and time.") LocalDateTime end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone of start and end, e.g. India Standard Time or Europe/London.") String tz,
            @Input(name = "attendees", required = false, description = "Attendee address(es).") Object attendees,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Description.") String body,
            @Input(name = "location", type = OoaType.STRING, required = false, description = "Location.") String location,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        if (!end.isAfter(start)) throw new ActionException(P + ".BAD_TIMES", "The end must be after the start");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("subject", subject);
        ev.put("start", Map.of("dateTime", start.toString(), "timeZone", tz));
        ev.put("end", Map.of("dateTime", end.toString(), "timeZone", tz));
        if (body != null) ev.put("body", Map.of("contentType", "Text", "content", body));
        if (location != null) ev.put("location", Map.of("displayName", location));
        if (attendees != null) {
            List<Object> at = new ArrayList<>();
            for (String a : HttpJson.addressList(attendees)) at.add(Map.of("emailAddress", Map.of("address", a), "type", "required"));
            ev.put("attendees", at);
        }
        Map<String, Object> r = HttpJson.req(P, "POST", root(base, mailbox) + "/events").bearer(token).json(ev).send(ctx).map();
        return Out.of("event_id", r.get("id"), "web_link", r.get("webLink"));
    }

    @Action(id = "list_events", name = "Get calendar events", category = "Calendar", idempotent = true, capabilities = "network", description = "List events between two dates.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, subject, start, end, location, organizer.")
    public Map<String, Object> listEvents(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "from", type = OoaType.DATETIME, description = "Start of the period (UTC).") LocalDateTime from,
            @Input(name = "to", type = OoaType.DATETIME, description = "End of the period (UTC).") LocalDateTime to,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        String url = root(base, mailbox) + "/calendarView?" + HttpJson.query(Map.of("startDateTime", from + "Z", "endDateTime", to + "Z", "$top", 500, "$orderby", "start/dateTime"));
        List<Object> rows = new ArrayList<>();
        for (Object o : (List<?>) HttpJson.req(P, "GET", url).bearer(token).header("Prefer", "outlook.timezone=\"UTC\"").send(ctx).map().getOrDefault("value", List.of())) {
            Map<?, ?> e = (Map<?, ?>) o;
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

    private static final String D_DRIVE = "me/drive (your OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}.";

    @Action(id = "upload_file", name = "Upload file (OneDrive/SharePoint)", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Upload a local file (up to 250 MB) to a OneDrive or SharePoint folder path; creates or replaces it.")
    @Output(name = "item_id", type = OoaType.STRING, description = "Drive item id.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to the file.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "local_path", type = OoaType.FILE, description = "File to upload.") String local,
            @Input(name = "remote_path", type = OoaType.STRING, example = "Reports/2026/summary.xlsx", description = "Target path in the drive, including the file name.") String remote,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Path p = ctx.resolvePath(local);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        if (data.length > 250L * 1024 * 1024) throw new ActionException(P + ".FILE_TOO_LARGE", "Files over 250 MB need an upload session", "Split the file or contact support.");
        Map<String, Object> r = HttpJson.req(P, "PUT", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":/content").bearer(token)
                .bytes(data, "application/octet-stream").timeout(600).send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    @Action(id = "download_file", name = "Download file (OneDrive/SharePoint)", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Download a file from a OneDrive or SharePoint path to a local file.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Path of the file in the drive.") String remote,
            @Input(name = "local_path", type = OoaType.FILE, description = "Where to save it.") String local,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        byte[] data = HttpJson.req(P, "GET", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":/content").bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
        Path p = ctx.resolvePath(local);
        try { if (p.getParent() != null) Files.createDirectories(p.getParent()); Files.write(p, data); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    @Action(id = "list_files", name = "List files (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network", description = "List the files and folders in a drive folder.")
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

    @Action(id = "teams_message", name = "Post Teams message", category = "Teams", capabilities = "network", description = "Post a message to a Microsoft Teams channel.")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message id.")
    public Map<String, Object> teamsMessage(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, description = "Team id.") String team,
            @Input(name = "channel_id", type = OoaType.STRING, description = "Channel id.") String channel,
            @Input(name = "message", type = OoaType.STRING, description = "Message text (HTML allowed when html is true).") String message,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Message is HTML.") boolean html,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "POST", base.replaceAll("/+$", "") + "/teams/" + HttpJson.enc(team) + "/channels/" + HttpJson.enc(channel) + "/messages")
                .bearer(token).json(Map.of("body", Map.of("contentType", html ? "html" : "text", "content", message))).send(ctx).map();
        return Out.of("message_id", r.get("id"));
    }

    private static String sheetRange(String sheet, String range) {
        if (!range.matches("^[A-Za-z]{1,3}[0-9]{1,7}(:[A-Za-z]{1,3}[0-9]{1,7})?$")) throw new ActionException(P + ".BAD_RANGE", "Invalid range '" + range + "'", "Example: A1:D20.");
        return "/workbook/worksheets/" + HttpJson.enc("{" + sheet + "}").replace("%7B", "('").replace("%7D", "')") + "/range(address='" + range + "')";
    }

    @Action(id = "excel_read_range", name = "Read Excel range (OneDrive/SharePoint)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read a range from an Excel workbook stored in OneDrive or SharePoint, without downloading it.")
    @Output(name = "table", type = OoaType.TABLE, description = "Range values (first row as column names).")
    public Map<String, Object> excelRead(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, example = "Finance/budget.xlsx", description = "Workbook path in the drive.") String remote,
            @Input(name = "sheet", type = OoaType.STRING, example = "Sheet1", description = "Worksheet name.") String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A1:D20", description = "Range address.") String range,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = "\"https://graph.microsoft.com/v1.0\"", description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "GET", drive(drive, base) + "/root:/" + HttpJson.encPath(remote) + ":" + sheetRange(sheet, range)).bearer(token).send(ctx).map();
        List<List<Object>> grid = new ArrayList<>();
        for (Object row : (List<?>) r.getOrDefault("values", List.of())) grid.add(new ArrayList<>((List<?>) row));
        List<String> cols = new ArrayList<>();
        if (!grid.isEmpty()) for (Object h : grid.remove(0)) cols.add(Values.text(h));
        return Map.of("table", Values.table(cols, new ArrayList<>(grid)));
    }

    @Action(id = "excel_write_range", name = "Write Excel range (OneDrive/SharePoint)", category = "Excel online", capabilities = "network",
            description = "Write a table into an Excel workbook in OneDrive or SharePoint, starting at a cell (column names first).")
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
}
