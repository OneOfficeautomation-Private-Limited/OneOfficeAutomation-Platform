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
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("$top", 100);
        q.put("$select", "id,subject,from,receivedDateTime,isRead");
        if (unread) q.put("$filter", "isRead eq false");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : pages(ctx, token, mbRoot + "/mailFolders/" + HttpJson.enc(folder) + "/messages?" + HttpJson.query(q), 0, null)) {
            String sender = s(at(m, "from", "emailAddress", "address")).toLowerCase(Locale.ROOT);
            String subj = s(m.get("subject")).toLowerCase(Locale.ROOT);
            if (from != null && !from.isEmpty() && !sender.contains(from.toLowerCase(Locale.ROOT))) continue;
            if (subject != null && !subject.isEmpty() && !subj.contains(subject.toLowerCase(Locale.ROOT))) continue;
            out.add(m);
            if (out.size() >= max) break;
        }
        return out;
    }

    @Action(id = "mark_mail", name = "Change email status (Outlook)", category = "Mail", idempotent = true, capabilities = "network",
            description = "Mark an email as read or unread, or set, clear or complete its follow-up flag.")
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
            description = "Delete an email by moving it to Deleted Items, or remove it permanently.")
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
            description = "Delete every email in a folder that matches the optional filters, to Deleted Items or permanently.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails deleted.")
    public Map<String, Object> deleteAllMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = D_FOLDER_FILTERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text.") String subject,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove permanently instead of moving to Deleted Items.") boolean permanent,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most emails to delete in one run.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = root(base, mailbox);
        List<Map<String, Object>> found = findMail(ctx, token, r, folder, unread, from, subject, Math.max(1, max));
        for (Map<String, Object> m : found) removeMail(ctx, token, r, s(m.get("id")), permanent);
        return Map.of("count", num(found.size()));
    }

    @Action(id = "move_all_mail", name = "Move all emails (Outlook)", category = "Mail", timeoutSeconds = 600, capabilities = "network",
            description = "Move every email in a folder that matches the optional filters to another folder.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails moved.")
    public Map<String, Object> moveAllMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder id or well-known name.") String to,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = "Source " + D_FOLDER_FILTERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text.") String subject,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most emails to move in one run.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = root(base, mailbox);
        List<Map<String, Object>> found = findMail(ctx, token, r, folder, unread, from, subject, Math.max(1, max));
        for (Map<String, Object> m : found)
            HttpJson.req(P, "POST", r + "/messages/" + HttpJson.enc(s(m.get("id"))) + "/move").bearer(token).json(Map.of("destinationId", to)).send(ctx);
        return Map.of("count", num(found.size()));
    }

    @Action(id = "forward_mail", name = "Forward email (Outlook)", category = "Mail", capabilities = "network",
            description = "Forward an email to new recipients with an optional note above the original message.")
    public Map<String, Object> forwardMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "comment", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Note added above the forwarded message.") String comment,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (HttpJson.addressList(to).isEmpty()) throw new ActionException(P + ".NO_RECIPIENTS", "No recipients to forward to", "Give at least one address.");
        HttpJson.req(P, "POST", root(base, mailbox) + "/messages/" + HttpJson.enc(id) + "/forward").bearer(token)
                .json(Map.of("toRecipients", recipients(to), "comment", comment == null ? "" : comment)).send(ctx);
        return Map.of();
    }

    @Action(id = "reply_mail", name = "Reply to email (Outlook)", category = "Mail", capabilities = "network",
            description = "Reply to the sender of an email, or to everyone on it, with a note above the original message.")
    public Map<String, Object> replyMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "comment", type = OoaType.STRING, description = "Reply text.") String comment,
            @Input(name = "reply_all", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Reply to the sender and every other recipient.") boolean all,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "POST", root(base, mailbox) + "/messages/" + HttpJson.enc(id) + (all ? "/replyAll" : "/reply")).bearer(token)
                .json(Map.of("comment", comment == null ? "" : comment)).send(ctx);
        return Map.of();
    }

    @Action(id = "reply_all_mail", name = "Reply all to email (Outlook)", category = "Mail", capabilities = "network",
            description = "Reply to the sender and every other recipient of an email with a note above the original message.")
    public Map<String, Object> replyAllMail(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "comment", type = OoaType.STRING, description = "Reply text.") String comment,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return replyMail(ctx, token, id, comment, true, mailbox, base);
    }

    @Action(id = "mail_folder_exists", name = "Check mail folder exists (Outlook)", category = "Mail", idempotent = true, capabilities = "network",
            description = "Check whether a top-level mail folder with the given display name exists and return its id.")
    @Output(name = "exists", type = OoaType.BOOLEAN, description = "True when the folder exists.")
    @Output(name = "folder_id", type = OoaType.STRING, description = "Folder id, empty when it does not exist.")
    public Map<String, Object> folderExists(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name", type = OoaType.STRING, description = "Folder display name, e.g. Invoices.") String name,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String url = root(base, mailbox) + "/mailFolders?" + HttpJson.query(Map.of("$filter", "displayName eq " + odataText(name), "$select", "id,displayName"));
        List<Map<String, Object>> found = pages(ctx, token, url, 1, null);
        return Map.of("exists", !found.isEmpty(), "folder_id", found.isEmpty() ? "" : s(found.get(0).get("id")));
    }

    @Action(id = "save_mail_as_eml", name = "Save email as EML (Outlook)", category = "Mail", capabilities = {"network", "filesystem"},
            description = "Save a complete email, including attachments, as a standard .eml file.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> saveEml(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "id", type = OoaType.STRING, description = "Email id.") String id,
            @Input(name = "local_path", type = OoaType.FILE, example = "mail/order-123.eml", description = "Where to save the .eml file.") String local,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        byte[] data = HttpJson.req(P, "GET", root(base, mailbox) + "/messages/" + HttpJson.enc(id) + "/$value").bearer(token).binaryResponse().timeout(300).send(ctx).bytes();
        return Map.of("path", writeLocal(ctx, local, data).toString());
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
            description = "Copy a file or folder into another folder, optionally under a new name, and wait for the copy to finish.")
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
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String[] dest = folderRef(ctx, token, toDrive == null || toDrive.isBlank() ? drive : toDrive, base, to);
        Map<String, Object> parent = new LinkedHashMap<>();
        if (!dest[0].isEmpty()) parent.put("driveId", dest[0]);
        parent.put("id", dest[1]);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentReference", parent);
        if (newName != null && !newName.isBlank()) body.put("name", newName);
        HttpJson.Response r = HttpJson.req(P, "POST", item(drive, base, ref, "/copy")).bearer(token).json(body).send(ctx);
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
            description = "Move a file or folder into another folder in the same drive, optionally renaming it.")
    @Output(name = "item_id", type = OoaType.STRING, description = "Item id.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to the item in its new place.")
    public Map<String, Object> moveItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder path (empty for the root), or id:{item-id}.") String to,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "New name; unchanged when empty.") String newName,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String[] dest = folderRef(ctx, token, drive, base, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentReference", Map.of("id", dest[1]));
        if (newName != null && !newName.isBlank()) body.put("name", newName);
        Map<String, Object> r = HttpJson.req(P, "PATCH", item(drive, base, ref, "")).bearer(token).json(body).send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    @Action(id = "rename_item", name = "Rename file or folder (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network",
            description = "Give a file or folder a new name in the same folder.")
    @Output(name = "item_id", type = OoaType.STRING, description = "Item id.")
    @Output(name = "web_url", type = OoaType.STRING, description = "Link to the renamed item.")
    public Map<String, Object> renameItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "new_name", type = OoaType.STRING, description = "New name, including the extension for files.") String newName,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "PATCH", item(drive, base, ref, "")).bearer(token).json(Map.of("name", newName)).send(ctx).map();
        return Out.of("item_id", r.get("id"), "web_url", r.get("webUrl"));
    }

    @Action(id = "delete_item", name = "Delete file or folder (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Delete a file or folder; it goes to the drive's recycle bin.")
    public Map<String, Object> deleteItem(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (ref == null || ref.isBlank()) throw new ActionException(P + ".BAD_ITEM", "No item given", "Give a path or id:{item-id}; the drive root cannot be deleted.");
        HttpJson.req(P, "DELETE", item(drive, base, ref, "")).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "create_folder", name = "Create folder (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Create a folder inside a drive folder.")
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
            description = "Search a drive for files and folders whose name or content matches the text.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, type, size, modified, web_url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items found.")
    public Map<String, Object> findItems(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum items.") int max,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String url = drive(drive, base) + "/root/search(q=" + HttpJson.enc(odataText(text)) + ")?" + HttpJson.query(Map.of("$top", Math.min(200, Math.max(1, max))));
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> i : pages(ctx, token, url, Math.max(1, max), null))
            rows.add(new ArrayList<>(Arrays.asList(i.get("id"), i.get("name"), i.containsKey("folder") ? "folder" : "file", i.get("size"), i.get("lastModifiedDateTime"), i.get("webUrl"))));
        return Map.of("table", Values.table(List.of("id", "name", "type", "size", "modified", "web_url"), rows), "count", num(rows.size()));
    }

    @Action(id = "get_item_info", name = "Get file information (OneDrive/SharePoint)", category = "Files", idempotent = true, capabilities = "network",
            description = "Read a file's or folder's id, size, dates, last editor, parent path and link.")
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
            description = "List who can open or edit a file or folder, including sharing links.")
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
            description = "Check whether a person has been given direct access to a file or folder and with which roles.")
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

    @Action(id = "export_to_pdf", name = "Export file to PDF (OneDrive/SharePoint)", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Convert a Word, Excel, PowerPoint or other supported file in the drive to PDF and save it locally.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved PDF path.")
    public Map<String, Object> exportPdf(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item", type = OoaType.STRING, description = D_ITEM) String ref,
            @Input(name = "local_path", type = OoaType.FILE, example = "out/report.pdf", description = "Where to save the PDF.") String local,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        byte[] data = HttpJson.req(P, "GET", item(drive, base, ref, "/content") + "?format=pdf").bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
        return Map.of("path", writeLocal(ctx, local, data).toString());
    }

    @Action(id = "restore_version", name = "Restore file version (OneDrive/SharePoint)", category = "Files", capabilities = "network",
            description = "Make an earlier version of a file the current one; by default the version before the current one.")
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
            description = "List the worksheets of a workbook in OneDrive or SharePoint, in tab order.")
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
            description = "Add a worksheet to a workbook in OneDrive or SharePoint.")
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

    @Action(id = "excel_delete_worksheet", name = "Delete worksheet (Excel online)", category = "Excel online", capabilities = "network",
            description = "Delete a worksheet and everything on it from a workbook in OneDrive or SharePoint.")
    public Map<String, Object> excelDeleteSheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "DELETE", book(drive, base, remote, ws(sheet))).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_rename_worksheet", name = "Rename worksheet (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Give a worksheet in a OneDrive or SharePoint workbook a new name.")
    public Map<String, Object> excelRenameSheet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = "Current worksheet name.") String sheet,
            @Input(name = "new_name", type = OoaType.STRING, description = "New worksheet name.") String newName,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "PATCH", book(drive, base, remote, ws(sheet))).bearer(token).json(Map.of("name", newName)).send(ctx);
        return Map.of();
    }

    @Action(id = "excel_get_range", name = "Get range values (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read the cell values of a range as a list of rows, without treating the first row as headers.")
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
            description = "Write a list of rows into a sheet; a single cell address is grown to fit the values.")
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
            description = "Read one cell's value and the text Excel shows for it.")
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
            description = "Write one value into a cell.")
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
            description = "Put a formula into a cell and return the value Excel calculates.")
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
            description = "Read the formula in a cell together with its current value.")
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
            description = "Clear the values, the formatting, or both from a range without shifting other cells.")
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
        Map<String, Object> r = HttpJson.req(P, "POST", book(drive, base, remote, ws(sheet) + rangePath(range) + "/" + op)).bearer(token)
                .json(Map.of("shift", Character.toUpperCase(sh.charAt(0)) + sh.substring(1))).send(ctx).map();
        return r;
    }

    @Action(id = "excel_delete_range", name = "Delete range (Excel online)", category = "Excel online", capabilities = "network",
            description = "Delete the cells of a range and close the gap by moving cells up or left.")
    public Map<String, Object> excelDeleteRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A5:F5", description = "Range address.") String range,
            @Input(name = "shift", type = OoaType.STRING, required = false, defaultValue = "\"up\"", enumValues = {"up", "left"}, description = "Move the remaining cells up or left.") String shift,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        shiftRange(ctx, token, remote, sheet, range, "delete", shift, new LinkedHashSet<>(List.of("up", "left")), drive, base);
        return Map.of();
    }

    @Action(id = "excel_insert_range", name = "Insert range (Excel online)", category = "Excel online", capabilities = "network",
            description = "Insert empty cells at a range, pushing the existing cells down or right.")
    @Output(name = "address", type = OoaType.STRING, description = "Address of the inserted cells.")
    public Map<String, Object> excelInsertRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A5:F5", description = "Range address.") String range,
            @Input(name = "shift", type = OoaType.STRING, required = false, defaultValue = "\"down\"", enumValues = {"down", "right"}, description = "Push the existing cells down or right.") String shift,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> r = shiftRange(ctx, token, remote, sheet, range, "insert", shift, new LinkedHashSet<>(List.of("down", "right")), drive, base);
        return Map.of("address", s(r.get("address")));
    }

    @Action(id = "excel_autofit_columns", name = "Autofit columns (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Resize the columns of a range so their contents fit.")
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
            description = "Resize the rows of a range so their contents fit.")
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
            description = "Set the fill colour, bold text, font colour and number format of a range; empty settings are left as they are.")
    public Map<String, Object> excelFormat(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "sheet", type = OoaType.STRING, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, example = "A1:F1", description = "Range address.") String range,
            @Input(name = "fill_color", type = OoaType.STRING, required = false, example = "#FFF2CC", description = "Background colour as #RRGGBB or a colour name.") String fill,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Make the text bold (true) or normal (false).") Boolean bold,
            @Input(name = "font_color", type = OoaType.STRING, required = false, example = "#C00000", description = "Text colour as #RRGGBB or a colour name.") String fontColor,
            @Input(name = "number_format", type = OoaType.STRING, required = false, example = "#,##0.00", description = "Excel number format code for every cell.") String numberFormat,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        String r = book(drive, base, remote, ws(sheet) + rangePath(range));
        boolean any = false;
        if (fill != null && !fill.isBlank()) { HttpJson.req(P, "PATCH", r + "/format/fill").bearer(token).json(Map.of("color", fill)).send(ctx); any = true; }
        Map<String, Object> font = new LinkedHashMap<>();
        if (bold != null) font.put("bold", bold);
        if (fontColor != null && !fontColor.isBlank()) font.put("color", fontColor);
        if (!font.isEmpty()) { HttpJson.req(P, "PATCH", r + "/format/font").bearer(token).json(font).send(ctx); any = true; }
        if (numberFormat != null && !numberFormat.isBlank()) {
            int[][] b = bounds(range);
            List<Object> grid = new ArrayList<>();
            for (int i = b[0][1]; i <= b[1][1]; i++) grid.add(Collections.nCopies(b[1][0] - b[0][0] + 1, numberFormat));
            HttpJson.req(P, "PATCH", r).bearer(token).json(Map.of("numberFormat", grid)).send(ctx);
            any = true;
        }
        if (!any) throw new ActionException(P + ".NOTHING_TO_DO", "No formatting was given", "Set at least one of fill_color, bold, font_color or number_format.");
        return Map.of();
    }

    /** Address of the used range, e.g. Sheet1!A1:F42, or null when the sheet is empty. */
    private static String used(ActionContext ctx, Object token, String drive, String base, String remote, String sheet) {
        Map<String, Object> r = HttpJson.req(P, "GET", book(drive, base, remote, ws(sheet) + "/usedRange(valuesOnly=true)") + "?$select=address,rowCount,columnCount").bearer(token).send(ctx).map();
        String a = s(r.get("address"));
        String cells = a.substring(a.lastIndexOf('!') + 1);
        return cells.isEmpty() || (r.get("rowCount") instanceof Number n && n.intValue() == 0) ? null : cells;
    }

    @Action(id = "excel_used_range", name = "Get used range (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Find the block of cells on a sheet that holds values, with its last row and column.")
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
            description = "Read one sheet row from column A to the last used column.")
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
            description = "Read one sheet column from row 1 to the last used row.")
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

    @Action(id = "excel_list_tables", name = "List tables (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "List the Excel tables in a workbook, or on one worksheet.")
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
            description = "Turn a range into an Excel table, optionally naming it.")
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
            description = "Append rows to the end of an Excel table.")
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

    @Action(id = "excel_get_table_rows", name = "Get table rows (Excel online)", category = "Excel online", idempotent = true, capabilities = "network",
            description = "Read all rows of an Excel table as a Data Table, using the table's header names as columns.")
    @Output(name = "table", type = OoaType.TABLE, description = "The table's rows.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows.")
    public Map<String, Object> excelGetTableRows(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "remote_path", type = OoaType.STRING, description = D_BOOK) String remote,
            @Input(name = "table", type = OoaType.STRING, description = "Table name or id.") String table,
            @Input(name = "drive", type = OoaType.STRING, required = false, defaultValue = "\"me/drive\"", description = D_DRIVE) String drive,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> h = HttpJson.req(P, "GET", book(drive, base, remote, table(table) + "/headerRowRange")).bearer(token).send(ctx).map();
        List<String> cols = new ArrayList<>();
        List<List<Object>> hv = matrix(h.get("values"));
        if (!hv.isEmpty()) for (Object c : hv.get(0)) cols.add(Values.text(c));
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> r : pages(ctx, token, book(drive, base, remote, table(table) + "/rows"), 0, null)) {
            List<List<Object>> v = matrix(r.get("values"));
            rows.add(v.isEmpty() ? new ArrayList<>() : v.get(0));
        }
        return Map.of("table", Values.table(cols, rows), "count", num(rows.size()));
    }

    @Action(id = "excel_delete_table", name = "Delete table (Excel online)", category = "Excel online", capabilities = "network",
            description = "Delete an Excel table together with its data.")
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
            description = "Write a Data Table to a sheet starting at a cell, with or without its column names.")
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

    @Action(id = "update_event", name = "Update calendar event", category = "Calendar", idempotent = true, capabilities = "network",
            description = "Change the title, times, description or location of an event; empty inputs are left unchanged.")
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
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (start != null && end != null && !end.isAfter(start)) throw new ActionException(P + ".BAD_TIMES", "The end must be after the start");
        Map<String, Object> ev = new LinkedHashMap<>();
        if (subject != null) ev.put("subject", subject);
        if (start != null) ev.put("start", Map.of("dateTime", start.toString(), "timeZone", tz));
        if (end != null) ev.put("end", Map.of("dateTime", end.toString(), "timeZone", tz));
        if (body != null) ev.put("body", Map.of("contentType", "Text", "content", body));
        if (location != null) ev.put("location", Map.of("displayName", location));
        if (ev.isEmpty()) throw new ActionException(P + ".NOTHING_TO_DO", "No changes were given", "Set at least one of subject, start, end, body or location.");
        Map<String, Object> r = HttpJson.req(P, "PATCH", event(base, mailbox, id)).bearer(token).json(ev).send(ctx).map();
        return Out.of("web_link", r.get("webLink"));
    }

    @Action(id = "cancel_event", name = "Cancel calendar event", category = "Calendar", capabilities = "network",
            description = "Cancel a meeting you organise and send attendees a cancellation note.")
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
            description = "Remove an event from the calendar without sending a cancellation note.")
    public Map<String, Object> deleteEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = D_EVENT) String id,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_MAILBOX) String mailbox,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        HttpJson.req(P, "DELETE", event(base, mailbox, id)).bearer(token).send(ctx);
        return Map.of();
    }

    @Action(id = "respond_to_event", name = "Respond to meeting invitation", category = "Calendar", capabilities = "network",
            description = "Accept, tentatively accept or decline a meeting invitation, with an optional note to the organiser.")
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
            description = "Invite more people to an existing event; people already invited are not added twice.")
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
            description = "Take people off an existing event's attendee list.")
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

    @Action(id = "add_event_attachment", name = "Add event attachment", category = "Calendar", capabilities = {"network", "filesystem"},
            description = "Attach a local file of up to 3 MB to a calendar event.")
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

    @Action(id = "find_meeting_times", name = "Find free meeting times", category = "Calendar", idempotent = true, capabilities = "network",
            description = "Suggest time slots in a period when the attendees are free for a meeting of the given length.")
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
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        if (!to.isAfter(from)) throw new ActionException(P + ".BAD_TIMES", "The end of the period must be after its start");
        List<Object> at = new ArrayList<>();
        for (String a : HttpJson.addressList(attendees)) at.add(Map.of("emailAddress", Map.of("address", a), "type", "required"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attendees", at);
        body.put("timeConstraint", Map.of("activityDomain", "work", "timeSlots", List.of(Map.of(
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
            description = "List the Microsoft Teams teams the user belongs to.")
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
            description = "Find the id of a team the user belongs to from its display name.")
    @Output(name = "team_id", type = OoaType.STRING, description = "Team id.")
    public Map<String, Object> teamByName(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name", type = OoaType.STRING, description = "Team display name (not case-sensitive).") String name,
            @Input(name = "user", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = "me, or a user's id or email address.") String user,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return Map.of("team_id", s(byName(joinedTeams(ctx, token, base, user), name, "team").get("id")));
    }

    @Action(id = "list_channels", name = "List channels (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "List the channels of a team.")
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

    @Action(id = "get_channel_by_name", name = "Get channel by name (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Find the id of a team's channel from its display name.")
    @Output(name = "channel_id", type = OoaType.STRING, description = "Channel id.")
    public Map<String, Object> channelByName(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, description = "Team id.") String team,
            @Input(name = "name", type = OoaType.STRING, description = "Channel display name (not case-sensitive), e.g. General.") String name,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        return Map.of("channel_id", s(byName(channels(ctx, token, base, team), name, "channel").get("id")));
    }

    @Action(id = "get_team_details", name = "Get team details (Teams)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Read a team's name, description, visibility, archive state and link.")
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

    @Action(id = "get_user", name = "Get user (Microsoft 365)", category = "Teams", idempotent = true, capabilities = "network",
            description = "Read a person's directory profile: name, email, job title, department, office and phone.")
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

    @Action(id = "send_chat_message", name = "Send chat message (Teams)", category = "Teams", capabilities = "network",
            description = "Send a message into an existing Teams chat.")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message id.")
    public Map<String, Object> sendChatMessage(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "chat_id", type = OoaType.STRING, description = "Chat id, e.g. from Create chat.") String chat,
            @Input(name = "message", type = OoaType.STRING, description = "Message text (HTML allowed when html is true).") String message,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Message is HTML.") boolean html,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = GRAPH, description = D_BASE) String base) {
        Map<String, Object> r = HttpJson.req(P, "POST", g(base) + "/chats/" + HttpJson.enc(chat) + "/messages").bearer(token)
                .json(Map.of("body", Map.of("contentType", html ? "html" : "text", "content", message))).send(ctx).map();
        return Out.of("message_id", r.get("id"));
    }

    @Action(id = "create_chat", name = "Create one-on-one chat (Teams)", category = "Teams", capabilities = "network",
            description = "Start, or reuse, a one-on-one Teams chat between the signed-in user and another person.")
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
            description = "List the lists and libraries of a SharePoint site.")
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
            description = "Read the items of a SharePoint list as a Data Table, optionally filtered and limited to some columns.")
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
            description = "Add an item to a SharePoint list from a dictionary of field names and values.")
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
            description = "Change some fields of a SharePoint list item; other fields keep their values.")
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
            description = "Delete an item from a SharePoint list; it goes to the site's recycle bin.")
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
