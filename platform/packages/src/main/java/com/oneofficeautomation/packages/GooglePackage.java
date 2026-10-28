package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Google Workspace through Google's REST APIs: Sheets, Drive, Calendar and Gmail. OAuth 2.0 access token from the vault. */
@OoaPackage(id = "ooa.google", name = "Google Workspace", version = "1.0.0",
        description = "Read and write Google Sheets, upload and download Drive files, manage Calendar events and send Gmail.")
public final class GooglePackage {
    static final String P = "GOOGLE";
    private static final String D_TOKEN = "Google OAuth access token from the vault.";
    private static final String D_EP = "Replaces https://*.googleapis.com for testing or a proxy; leave empty normally.";

    private static String url(String defaultUrl, String override) {
        return override == null || override.isBlank() ? defaultUrl : defaultUrl.replaceFirst("^https://[^/]+", override.replaceAll("/+$", ""));
    }

    @Action(id = "sheets_read", name = "Read sheet range", category = "Sheets", idempotent = true, capabilities = "network", description = "Read a range such as Sheet1!A1:D100 into a table.",
            panel = "access_token, spreadsheet_id, range, header | endpoint_override")
    @Output(name = "table", type = OoaType.TABLE, description = "Range content.")
    public Map<String, Object> sheetsRead(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1:D100", description = "A1 range.") String range,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Map<String, Object> r = HttpJson.req(P, "GET", url("https://sheets.googleapis.com/v4/spreadsheets/" + HttpJson.enc(id) + "/values/" + HttpJson.enc(range)
                + "?valueRenderOption=UNFORMATTED_VALUE", ep)).bearer(token).send(ctx).map();
        List<List<Object>> grid = new ArrayList<>();
        for (Object row : (List<?>) r.getOrDefault("values", List.of())) grid.add(new ArrayList<>((List<?>) row));
        int width = grid.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        if (header && !grid.isEmpty()) for (Object h : grid.remove(0)) cols.add(Values.text(h));
        while (cols.size() < width) cols.add("Column" + (cols.size() + 1));
        List<Object> rows = new ArrayList<>();
        for (List<Object> g : grid) { while (g.size() < cols.size()) g.add(null); rows.add(g); }
        return Map.of("table", Values.table(cols, rows));
    }

    private static List<Object> grid(Map<String, Object> table, boolean header) {
        List<Object> values = new ArrayList<>();
        if (header) values.add(new ArrayList<>((List<?>) table.get("columns")));
        for (Object r : (List<?>) table.get("rows")) { List<Object> row = new ArrayList<>(); for (Object c : (List<?>) r) row.add(c == null ? "" : Values.toJsonable(c)); values.add(row); }
        return values;
    }

    @Action(id = "sheets_write", name = "Write to sheet", category = "Sheets", capabilities = "network", description = "Write a table into a sheet starting at a cell, overwriting existing values.",
            panel = "access_token, spreadsheet_id, range, table, include_header | endpoint_override")
    @Output(name = "updated_cells", type = OoaType.NUMBER, description = "Cells written.")
    public Map<String, Object> sheetsWrite(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1", description = "Top-left cell or range.") String range,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to write.") Map<String, Object> table,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write column names first.") boolean header,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Map<String, Object> r = HttpJson.req(P, "PUT", url("https://sheets.googleapis.com/v4/spreadsheets/" + HttpJson.enc(id) + "/values/" + HttpJson.enc(range)
                + "?valueInputOption=USER_ENTERED", ep)).bearer(token).json(Map.of("range", range, "values", grid(table, header))).send(ctx).map();
        return Out.of("updated_cells", r.get("updatedCells"));
    }

    @Action(id = "sheets_append", name = "Append rows to sheet", category = "Sheets", capabilities = "network", description = "Add a table's rows after the last row of a sheet.",
            panel = "access_token, spreadsheet_id, range, table | endpoint_override")
    @Output(name = "updated_range", type = OoaType.STRING, description = "Range that received the rows.")
    public Map<String, Object> sheetsAppend(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1", description = "Sheet (and table start) to append to.") String range,
            @Input(name = "table", type = OoaType.TABLE, description = "Rows to append (column names are not written).") Map<String, Object> table,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://sheets.googleapis.com/v4/spreadsheets/" + HttpJson.enc(id) + "/values/" + HttpJson.enc(range)
                + ":append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS", ep)).bearer(token).json(Map.of("values", grid(table, false))).send(ctx).map();
        return Out.of("updated_range", r.get("updates") instanceof Map<?, ?> u ? u.get("updatedRange") : null);
    }

    @Action(id = "drive_list", name = "List Drive files", category = "Drive", idempotent = true, capabilities = "network", description = "List files in a Drive folder (or matching a Drive search query).",
            panel = "access_token, folder_id, name_contains | endpoint_override")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, mime_type, size, modified.")
    public Map<String, Object> driveList(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Folder id; all files when empty.") String folder,
            @Input(name = "name_contains", type = OoaType.STRING, required = false, description = "Only names containing this text.") String nameContains,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        List<String> q = new ArrayList<>(List.of("trashed = false"));
        if (folder != null && !folder.isBlank()) q.add("'" + folder.replace("\\", "\\\\").replace("'", "\\'") + "' in parents");
        if (nameContains != null && !nameContains.isBlank()) q.add("name contains '" + nameContains.replace("\\", "\\\\").replace("'", "\\'") + "'");
        List<Object> rows = new ArrayList<>();
        String token2 = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>(Map.of("q", String.join(" and ", q), "fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)", "pageSize", 1000));
            if (token2 != null) params.put("pageToken", token2);
            Map<String, Object> r = HttpJson.req(P, "GET", url("https://www.googleapis.com/drive/v3/files?" + HttpJson.query(params), ep)).bearer(token).send(ctx).map();
            for (Object o : (List<?>) r.getOrDefault("files", List.of())) {
                Map<?, ?> f = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(f.get("id"), f.get("name"), f.get("mimeType"), f.get("size"), f.get("modifiedTime"))));
            }
            token2 = (String) r.get("nextPageToken");
        } while (token2 != null);
        return Map.of("table", Values.table(List.of("id", "name", "mime_type", "size", "modified"), rows));
    }

    @Action(id = "drive_upload", name = "Upload to Drive", category = "Drive", timeoutSeconds = 600, capabilities = {"network", "filesystem"}, description = "Upload a local file to a Drive folder.",
            panel = "access_token, local_path, folder_id, overwrite | name, description, endpoint_override")
    @Output(name = "file_id", type = OoaType.STRING, description = "New file id.")
    public Map<String, Object> driveUpload(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "local_path", type = OoaType.FILE, description = "File to upload.") String local,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Target folder id; My Drive root when empty.") String folder,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name in Drive; the local name when empty.") String name,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace the content of a file with the same name in the folder (keeping its id and links) instead of adding a second file.") boolean overwrite,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description stored with the file.") String description,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Path p = ctx.resolvePath(local);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        String fileName = name == null || name.isBlank() ? p.getFileName().toString() : name;
        if (overwrite) {
            List<String> q = new ArrayList<>(List.of("trashed = false", "name = " + quoted(fileName), quoted(blank(folder) ? "root" : folder) + " in parents"));
            List<Object> same = list(call(ctx, token, "GET", DRIVE + "?" + HttpJson.query(Map.of("q", String.join(" and ", q), "fields", "files(id)", "supportsAllDrives", true, "includeItemsFromAllDrives", true)), ep, null).get("files"));
            if (!same.isEmpty()) {
                String fid = str(obj(same.get(0)).get("id"));
                Map<String, Object> r = HttpJson.req(P, "PATCH", url("https://www.googleapis.com/upload/drive/v3/files/" + HttpJson.enc(fid) + "?uploadType=media&fields=id&supportsAllDrives=true", ep))
                        .bearer(token).bytes(data, "application/octet-stream").timeout(600).send(ctx).map();
                if (!blank(description)) call(ctx, token, "PATCH", DRIVE + "/" + HttpJson.enc(fid) + "?supportsAllDrives=true", ep, Map.of("description", description));
                return Out.of("file_id", r.get("id"));
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", fileName);
        if (!blank(description)) meta.put("description", description);
        if (folder != null && !folder.isBlank()) meta.put("parents", List.of(folder));
        Object[] mp = HttpJson.multipart("related", List.of(
                new Object[]{"Content-Type: application/json; charset=UTF-8", JsonLite.write(meta).getBytes(StandardCharsets.UTF_8)},
                new Object[]{"Content-Type: application/octet-stream", data}));
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", ep))
                .bearer(token).bytes((byte[]) mp[0], (String) mp[1]).timeout(600).send(ctx).map();
        return Out.of("file_id", r.get("id"));
    }

    @Action(id = "drive_download", name = "Download from Drive", category = "Drive", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Download a Drive file by id (Google Docs/Sheets are exported to PDF/XLSX automatically).",
            panel = "access_token, file_id, local_path, overwrite | endpoint_override")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> driveDownload(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "file_id", type = OoaType.STRING, description = "File id.") String id,
            @Input(name = "local_path", type = OoaType.FILE, description = "Where to save it.") String local,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing local file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> meta = HttpJson.req(P, "GET", url("https://www.googleapis.com/drive/v3/files/" + HttpJson.enc(id) + "?fields=mimeType,name", ep)).bearer(token).send(ctx).map();
        String mime = String.valueOf(meta.get("mimeType"));
        String export = switch (mime) {
            case "application/vnd.google-apps.document" -> "application/pdf";
            case "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "application/vnd.google-apps.presentation" -> "application/pdf";
            default -> null;
        };
        String u = export != null ? "https://www.googleapis.com/drive/v3/files/" + HttpJson.enc(id) + "/export?mimeType=" + HttpJson.enc(export)
                : "https://www.googleapis.com/drive/v3/files/" + HttpJson.enc(id) + "?alt=media";
        byte[] data = HttpJson.req(P, "GET", url(u, ep)).bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
        Path p = freeName(ctx.resolvePath(local), overwrite);
        try { if (p.getParent() != null) Files.createDirectories(p.getParent()); Files.write(p, data); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    @Action(id = "calendar_create_event", name = "Create Google Calendar event", category = "Calendar", capabilities = "network", description = "Create an event, optionally inviting attendees.",
            panel = "access_token, subject, location, attendees, start, end, all_day, time_zone, [Repeat the event: recurrence], visibility, description | optional_attendees, calendar_id, notify, add_meet_link, reminder_minutes, show_as, endpoint_override")
    @Output(name = "event_id", type = OoaType.STRING, description = "Event id.")
    @Output(name = "link", type = OoaType.STRING, description = "Link to the event.")
    @Output(name = "meet_link", type = OoaType.STRING, description = "Google Meet link, when add_meet_link is true.")
    public Map<String, Object> createEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "subject", type = OoaType.STRING, description = "Title.") String subject,
            @Input(name = "start", type = OoaType.DATETIME, description = "Start.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "End.") LocalDateTime end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "IANA time zone of start and end.") String tz,
            @Input(name = "attendees", required = false, description = "Attendee address(es).") Object attendees,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "calendar_id", type = OoaType.STRING, required = false, defaultValue = "\"primary\"", description = "Calendar id.") String cal,
            @Input(name = "location", type = OoaType.STRING, required = false, description = "Location.") String location,
            @Input(name = "all_day", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "All-day event: only the dates count; the end date may equal the start date.") boolean allDay,
            @Input(name = "optional_attendees", required = false, description = "Address(es) invited as optional.") Object optional,
            @Input(name = "notify", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Email the invitation to the attendees.") boolean notify,
            @Input(name = "add_meet_link", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Add a Google Meet video link.") boolean meet,
            @Input(name = "reminder_minutes", type = OoaType.NUMBER, required = false, description = "Popup reminder this many minutes before; the calendar's default reminders when empty.") Integer reminder,
            @Input(name = "visibility", type = OoaType.STRING, required = false, defaultValue = "\"default\"", enumValues = {"default", "public", "private"}, description = "Who can see the event's details.") String visibility,
            @Input(name = "show_as", type = OoaType.STRING, required = false, defaultValue = "\"busy\"", enumValues = {"busy", "free"}, description = "Whether the time shows as busy.") String showAs,
            @Input(name = "recurrence", type = OoaType.STRING, required = false, example = "RRULE:FREQ=WEEKLY;COUNT=10", description = "Repeat rule (RFC 5545), or simply daily, weekly, monthly or yearly.") String recurrence,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("summary", subject);
        if (allDay) {
            java.time.LocalDate d1 = start.toLocalDate(), d2 = end.toLocalDate();
            ev.put("start", Map.of("date", d1.toString()));
            ev.put("end", Map.of("date", (d2.isAfter(d1) ? d2 : d1.plusDays(1)).toString()));
        } else {
            ev.put("start", Map.of("dateTime", start.toString(), "timeZone", tz));
            ev.put("end", Map.of("dateTime", end.toString(), "timeZone", tz));
        }
        if (description != null) ev.put("description", description);
        if (!blank(location)) ev.put("location", location);
        if (attendees != null || optional != null) {
            List<Object> at = new ArrayList<>();
            for (String a : HttpJson.addressList(attendees)) at.add(Map.of("email", a));
            for (String a : HttpJson.addressList(optional)) at.add(Map.of("email", a, "optional", true));
            ev.put("attendees", at);
        }
        if (reminder != null) ev.put("reminders", Map.of("useDefault", false, "overrides", List.of(Map.of("method", "popup", "minutes", Math.max(0, reminder)))));
        String vis = blank(visibility) ? "default" : visibility.trim().toLowerCase(Locale.ROOT);
        if (!List.of("default", "public", "private").contains(vis)) throw bad("visibility must be default, public or private", null);
        if (!vis.equals("default")) ev.put("visibility", vis);
        if ("free".equalsIgnoreCase(showAs == null ? "" : showAs.trim())) ev.put("transparency", "transparent");
        String rule = rrule(recurrence);
        if (rule != null) ev.put("recurrence", List.of(rule));
        if (meet) ev.put("conferenceData", Map.of("createRequest", Map.of("requestId", UUID.randomUUID().toString(), "conferenceSolutionKey", Map.of("type", "hangoutsMeet"))));
        String q = "?sendUpdates=" + (notify ? "all" : "none") + (meet ? "&conferenceDataVersion=1" : "");
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://www.googleapis.com/calendar/v3/calendars/" + HttpJson.enc(cal) + "/events" + q, ep)).bearer(token).json(ev).send(ctx).map();
        return Out.of("event_id", r.get("id"), "link", r.get("htmlLink"), "meet_link", r.get("hangoutLink"));
    }

    /** RFC 5545 rule from a full RRULE or a simple word. Null for none. */
    static String rrule(String v) {
        if (blank(v) || v.trim().equalsIgnoreCase("none")) return null;
        String t = v.trim(), u = t.toUpperCase(Locale.ROOT);
        if (u.startsWith("RRULE:")) return t;
        if (u.startsWith("FREQ=")) return "RRULE:" + t;
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "daily" -> "RRULE:FREQ=DAILY"; case "weekly" -> "RRULE:FREQ=WEEKLY"; case "monthly" -> "RRULE:FREQ=MONTHLY"; case "yearly" -> "RRULE:FREQ=YEARLY";
            default -> throw bad("Unknown recurrence '" + v + "'", "Use daily, weekly, monthly, yearly or a rule such as RRULE:FREQ=WEEKLY;COUNT=10.");
        };
    }

    @Action(id = "gmail_send", name = "Send Gmail", category = "Gmail", capabilities = "network",
            description = "Send a plain-text or HTML email from Gmail with optional CC, BCC, attachments, reply-to and send-as alias.",
            panel = "access_token, to, cc, bcc, subject, attachments, html, body | from, reply_to, endpoint_override")
    @Output(name = "message_id", type = OoaType.STRING, description = "Gmail message id.")
    public Map<String, Object> gmailSend(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach (the whole email up to 25 MB).") List<Object> attachments,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Send from this address, which must be one of the account's Gmail send-as aliases.") String from,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        List<String> rcpt = HttpJson.addressList(to);
        for (String a : rcpt) if (a.contains("\r") || a.contains("\n")) throw new ActionException(P + ".BAD_ADDRESS", "Invalid address");
        if (subject.contains("\r") || subject.contains("\n")) throw new ActionException(P + ".BAD_SUBJECT", "The subject cannot contain line breaks");
        if (cc != null || bcc != null || (attachments != null && !attachments.isEmpty()) || !blank(from) || replyTo != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("From", from);
            headers.put("To", String.join(", ", rcpt));
            headers.put("Cc", String.join(", ", HttpJson.addressList(cc)));
            headers.put("Bcc", String.join(", ", HttpJson.addressList(bcc)));
            headers.put("Reply-To", String.join(", ", HttpJson.addressList(replyTo)));
            headers.put("Subject", subject);
            byte[] mime = buildMime(headers, body, html, localAttachments(ctx, attachments));
            return Out.of("message_id", call(ctx, token, "POST", GMAIL + "messages/send", ep, Map.of("raw", rawOf(mime))).get("id"));
        }
        String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
        String mime = "To: " + String.join(", ", rcpt) + "\r\nSubject: " + encodedSubject + "\r\nMIME-Version: 1.0\r\nContent-Type: text/" + (html ? "html" : "plain")
                + "; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", ep)).bearer(token).json(Map.of("raw", raw)).send(ctx).map();
        return Out.of("message_id", r.get("id"));
    }

    // ------------------------------------------------------------------ shared helpers for the actions below

    private static final String SHEETS = "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final String DRIVE = "https://www.googleapis.com/drive/v3/files";
    private static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me/";
    private static final String CAL = "https://www.googleapis.com/calendar/v3/calendars/";
    private static final String D_SID = "The spreadsheet: its id, its full link (https://docs.google.com/spreadsheets/d/...), or its exact name in Drive (a name made only of letters, digits, - and _ is read as an id; use the link for such names).";
    private static final String D_SHEET = "Sheet (tab) name; the first sheet when empty.";
    private static final String D_RANGE = "A1 range, optionally with the sheet name, e.g. Sheet1!A1:C10; the first sheet when no sheet is named.";
    private static final String D_CELL = "One cell in A1 notation, optionally with the sheet name, e.g. Sheet1!B2.";
    private static final String D_MSG = "Gmail message id from List Gmail messages.";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    private static Map<String, Object> call(ActionContext ctx, Object token, String method, String u, String ep, Object body) {
        HttpJson.Req r = HttpJson.req(P, method, url(u, ep)).bearer(token);
        if (body != null) r.json(body);
        return r.send(ctx).map();
    }

    private static byte[] fetchBytes(ActionContext ctx, Object token, String u, String ep) {
        return HttpJson.req(P, "GET", url(u, ep)).bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
    }

    static ActionException bad(String msg, String fix) { return new ActionException(P + ".BAD_INPUT", msg, fix); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static BigDecimal num(long n) { return BigDecimal.valueOf(n); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> obj(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    private static String quoted(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    private static void writeFile(Path p, byte[] data) {
        try { if (p.getParent() != null) Files.createDirectories(p.getParent()); Files.write(p, data); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    // ------------------------------------------------------------------ A1 notation

    /** Splits "Sheet 1!A1:B2" or "'It''s'!A1" into {sheet name or null, cell part}. */
    static String[] splitRange(String range) {
        String r = range == null ? "" : range.trim();
        int i = r.lastIndexOf('!');
        if (i < 0) return new String[]{null, r};
        String s = r.substring(0, i).trim();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) s = s.substring(1, s.length() - 1).replace("''", "'");
        return new String[]{s, r.substring(i + 1).trim()};
    }

    static String a1(String sheet, String cells) { return "'" + sheet.replace("'", "''") + "'" + (cells == null || cells.isEmpty() ? "" : "!" + cells); }

    static int colIndex(String letters) {
        String l = letters.trim().toUpperCase(Locale.ROOT);
        if (l.matches("\\d+")) { int n = Integer.parseInt(l); if (n < 1) throw bad("Column numbers start at 1", null); return n - 1; }
        if (!l.matches("[A-Z]{1,3}")) throw bad("Invalid column '" + letters + "'", "Use letters such as A, B or AA, or a number from 1.");
        int n = 0;
        for (char c : l.toCharArray()) n = n * 26 + (c - 'A' + 1);
        return n - 1;
    }

    static String colLetters(int idx) {
        StringBuilder b = new StringBuilder();
        for (int n = idx + 1; n > 0; n = (n - 1) / 26) b.insert(0, (char) ('A' + (n - 1) % 26));
        return b.toString();
    }

    /** "B12" gives {11, 1}; "B" gives {-1, 1}; "12" gives {11, -1}. */
    static int[] cellRef(String ref) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$?([A-Za-z]*)\\$?(\\d*)").matcher(ref.trim());
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty()) || (!m.group(2).isEmpty() && Integer.parseInt(m.group(2)) < 1))
            throw bad("Invalid cell reference '" + ref + "'", "Use A1 notation such as B2 or A1:C10.");
        return new int[]{m.group(2).isEmpty() ? -1 : Integer.parseInt(m.group(2)) - 1, m.group(1).isEmpty() ? -1 : colIndex(m.group(1))};
    }

    /** Sheets API GridRange (zero-based, end exclusive) for the cell part of an A1 range. */
    static Map<String, Object> gridRange(int sheetId, String cells) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("sheetId", sheetId);
        if (cells == null || cells.isEmpty()) return g;
        String[] p = cells.split(":", 2);
        int[] a = cellRef(p[0]), b = cellRef(p.length > 1 ? p[1] : p[0]);
        if (a[0] >= 0) g.put("startRowIndex", a[0]);
        if (b[0] >= 0) g.put("endRowIndex", b[0] + 1);
        if (a[1] >= 0) g.put("startColumnIndex", a[1]);
        if (b[1] >= 0) g.put("endColumnIndex", b[1] + 1);
        return g;
    }

    // ------------------------------------------------------------------ Sheets helpers

    private static final java.util.regex.Pattern SHEET_LINK = java.util.regex.Pattern.compile("^https?://docs\\.google\\.com/spreadsheets/(?:u/\\d+/)?d/([A-Za-z0-9_-]+)");
    private static final java.util.regex.Pattern SHEET_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final String SHEET_MIME = "application/vnd.google-apps.spreadsheet";

    /** The id in a Google Sheets link, the input itself when it has only id characters (letters, digits, - and _), or null for a name. */
    static String spreadsheetIdOf(String v) {
        String t = v == null ? "" : v.trim();
        java.util.regex.Matcher m = SHEET_LINK.matcher(t);
        if (m.find()) return m.group(1);
        if (t.matches("(?i)^https?://.*")) throw bad("Not a Google Sheets link: " + t, "Copy the link from the browser while the spreadsheet is open, or give its id.");
        return SHEET_ID.matcher(t).matches() ? t : null;
    }

    /** Spreadsheet id from an id, a link or a name; a name is looked up in Drive and must match exactly one spreadsheet. */
    private static String spreadsheetId(ActionContext ctx, Object token, String v, String ep) {
        if (blank(v)) throw bad("Give the spreadsheet id, link or name", null);
        String id = spreadsheetIdOf(v);
        if (id != null) return id;
        String name = v.trim();
        String q = "trashed = false and mimeType = '" + SHEET_MIME + "' and name = " + quoted(name);
        List<Object> files = list(call(ctx, token, "GET", DRIVE + "?" + HttpJson.query(Map.of("q", q, "fields", "files(id,name)", "pageSize", 10,
                "supportsAllDrives", true, "includeItemsFromAllDrives", true)), ep, null).get("files"));
        if (files.isEmpty()) throw new ActionException(P + ".NOT_FOUND", "No spreadsheet named '" + name + "' was found in Drive", "Check the name, or give the spreadsheet id or link.");
        if (files.size() > 1) throw new ActionException(P + ".AMBIGUOUS", files.size() + " spreadsheets are named '" + name + "'", "Give the spreadsheet id or link instead of the name.");
        return str(obj(files.get(0)).get("id"));
    }

    private static List<Map<String, Object>> sheetList(ActionContext ctx, Object token, String id, String ep) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object s : list(call(ctx, token, "GET", SHEETS + HttpJson.enc(id) + "?fields=" + HttpJson.enc("sheets.properties"), ep, null).get("sheets")))
            out.add(obj(obj(s).get("properties")));
        return out;
    }

    private static Map<String, Object> sheet(ActionContext ctx, Object token, String id, String name, String ep) {
        List<Map<String, Object>> all = sheetList(ctx, token, id, ep);
        if (all.isEmpty()) throw new ActionException(P + ".NO_SHEET", "The spreadsheet has no sheets", null);
        if (blank(name)) return all.get(0);
        for (Map<String, Object> m : all) if (name.equals(m.get("title"))) return m;
        for (Map<String, Object> m : all) if (name.equalsIgnoreCase(str(m.get("title")))) return m;
        throw new ActionException(P + ".NO_SHEET", "Sheet '" + name + "' not found; the sheets are " + all.stream().map(m -> str(m.get("title"))).toList(),
                "Check the sheet name or use List sheets.");
    }

    private static int sheetId(Map<String, Object> props) { return props.get("sheetId") instanceof Number n ? n.intValue() : 0; }

    private static String sheetTitle(ActionContext ctx, Object token, String id, String name, String ep) {
        return blank(name) ? str(sheet(ctx, token, id, null, ep).get("title")) : name;
    }

    private static Map<String, Object> batch(ActionContext ctx, Object token, String id, String ep, List<Map<String, Object>> requests) {
        return call(ctx, token, "POST", SHEETS + HttpJson.enc(id) + ":batchUpdate", ep, Map.of("requests", requests));
    }

    /** GridRange of a range input such as Sheet1!A1:C3 (the first sheet when no sheet is named). */
    private static Map<String, Object> grid(ActionContext ctx, Object token, String id, String range, String ep) {
        String[] s = splitRange(range);
        return gridRange(sheetId(sheet(ctx, token, id, s[0], ep)), s[1]);
    }

    /** A range with an explicit sheet name, adding the first sheet's name when the input has none. */
    private static String fullRange(ActionContext ctx, Object token, String id, String range, String ep) {
        String[] s = splitRange(range);
        return s[0] != null ? range.trim() : a1(sheetTitle(ctx, token, id, null, ep), s[1]);
    }

    private static Map<String, Object> getValues(ActionContext ctx, Object token, String id, String range, String render, String major, String ep) {
        return call(ctx, token, "GET", SHEETS + HttpJson.enc(id) + "/values/" + HttpJson.enc(range) + "?valueRenderOption=" + render
                + (major == null ? "" : "&majorDimension=" + major), ep, null);
    }

    private static Map<String, Object> putValues(ActionContext ctx, Object token, String id, String range, List<Object> rows, String ep) {
        return call(ctx, token, "PUT", SHEETS + HttpJson.enc(id) + "/values/" + HttpJson.enc(range) + "?valueInputOption=USER_ENTERED", ep, Map.of("range", range, "values", rows));
    }

    private static Object firstValue(Map<String, Object> r) {
        List<Object> rows = list(r.get("values"));
        if (rows.isEmpty()) return null;
        List<Object> row = list(rows.get(0));
        return row.isEmpty() ? null : row.get(0);
    }

    private static Map<String, Object> color(String hex, String input) {
        String h = hex.trim().replaceFirst("^#", "");
        if (!h.matches("[0-9A-Fa-f]{6}")) throw bad("Invalid colour '" + hex + "' for " + input, "Use #RRGGBB, e.g. #FF0000 for red.");
        int v = Integer.parseInt(h, 16);
        return Map.of("red", BigDecimal.valueOf(((v >> 16) & 255) / 255.0), "green", BigDecimal.valueOf(((v >> 8) & 255) / 255.0), "blue", BigDecimal.valueOf((v & 255) / 255.0));
    }

    static String hex(Map<String, Object> c, String fallback) {
        if (c == null || c.isEmpty()) return fallback;
        int[] v = new int[3];
        String[] k = {"red", "green", "blue"};
        for (int i = 0; i < 3; i++) v[i] = c.get(k[i]) instanceof Number n ? (int) Math.round(n.doubleValue() * 255) : 0;
        return String.format("#%02X%02X%02X", v[0], v[1], v[2]);
    }

    private static Map<String, Object> sheetDone(Map<String, Object> props) { return Out.of("sheet_id", num(sheetId(props))); }

    // ------------------------------------------------------------------ Sheets actions

    @Action(id = "sheets_create_spreadsheet", name = "Create spreadsheet", category = "Sheets", capabilities = "network",
            description = "Create a new Google spreadsheet, optionally with named sheets. Other spreadsheet actions accept its id, its link or its name.",
            panel = "access_token, title, sheet_names, folder_id | endpoint_override")
    @Output(name = "spreadsheet_id", type = OoaType.STRING, description = "Id of the new spreadsheet.")
    @Output(name = "url", type = OoaType.STRING, description = "Link to open the spreadsheet.")
    public Map<String, Object> sheetsCreate(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "title", type = OoaType.STRING, description = "Spreadsheet title.") String title,
            @Input(name = "sheet_names", type = OoaType.LIST, required = false, description = "Names of the sheets to create; one default sheet when empty.") List<Object> names,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Drive folder to put the spreadsheet in; My Drive when empty.") String folder,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("properties", Map.of("title", title));
        if (names != null && !names.isEmpty()) {
            List<Object> sheets = new ArrayList<>();
            for (Object n : names) sheets.add(Map.of("properties", Map.of("title", Values.text(n))));
            body.put("sheets", sheets);
        }
        Map<String, Object> r = call(ctx, token, "POST", "https://sheets.googleapis.com/v4/spreadsheets", ep, body);
        if (!blank(folder)) driveMove(ctx, token, str(r.get("spreadsheetId")), folder, ep);
        return Out.of("spreadsheet_id", r.get("spreadsheetId"), "url", r.get("spreadsheetUrl"));
    }

    @Action(id = "sheets_list_sheets", name = "List sheets", category = "Sheets", idempotent = true, capabilities = "network",
            description = "List the sheets (tabs) of a spreadsheet with their size and visibility.",
            panel = "access_token, spreadsheet_id, exclude_hidden | endpoint_override")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: sheet_id, name, index, hidden, rows, columns.")
    @Output(name = "names", type = OoaType.LIST, description = "Sheet names in tab order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of sheets listed.")
    public Map<String, Object> sheetsList(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "exclude_hidden", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave hidden sheets out.") boolean noHidden,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        List<Object> rows = new ArrayList<>(), names = new ArrayList<>();
        for (Map<String, Object> p : sheetList(ctx, token, id, ep)) {
            if (noHidden && Boolean.TRUE.equals(p.get("hidden"))) continue;
            Map<String, Object> g = obj(p.get("gridProperties"));
            rows.add(new ArrayList<>(Arrays.asList(num(sheetId(p)), p.get("title"), p.get("index") == null ? num(0) : p.get("index"),
                    Boolean.TRUE.equals(p.get("hidden")), g.get("rowCount"), g.get("columnCount"))));
            names.add(p.get("title"));
        }
        return Map.of("table", Values.table(List.of("sheet_id", "name", "index", "hidden", "rows", "columns"), rows), "names", names, "count", num(names.size()));
    }

    @Action(id = "sheets_add_sheet", name = "Add sheet", category = "Sheets", capabilities = "network", description = "Add a new empty sheet (tab) to a spreadsheet.",
            panel = "access_token, spreadsheet_id, sheet, position | endpoint_override")
    @Output(name = "sheet_id", type = OoaType.NUMBER, description = "Numeric id of the new sheet.")
    public Map<String, Object> sheetsAdd(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, description = "Name for the new sheet.") String name,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "Tab position starting at 1; added last when empty.") Integer position,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Map<String, Object> props = new LinkedHashMap<>(Map.of("title", name));
        if (position != null) props.put("index", Math.max(0, position - 1));
        Map<String, Object> r = batch(ctx, token, id, ep, List.of(Map.of("addSheet", Map.of("properties", props))));
        Map<String, Object> reply = list(r.get("replies")).isEmpty() ? Map.of() : obj(obj(list(r.get("replies")).get(0)).get("addSheet"));
        return sheetDone(obj(reply.get("properties")));
    }

    @Action(id = "sheets_delete_sheet", name = "Delete sheet", category = "Sheets", capabilities = "network", description = "Delete a sheet (tab) and all its content.",
            panel = "access_token, spreadsheet_id, (Sheet by: Name = sheet / Position = position) | endpoint_override")
    public Map<String, Object> sheetsDelete(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Name of the sheet to delete; or give position instead.") String name,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "Tab position of the sheet to delete, starting at 1; used instead of sheet.") Integer position) {
        id = spreadsheetId(ctx, token, id, ep);
        batch(ctx, token, id, ep, List.of(Map.of("deleteSheet", Map.of("sheetId", sheetId(sheetByNameOrPosition(ctx, token, id, name, position, "delete", ep))))));
        return Map.of();
    }

    /** Earlier signature, kept for callers written before position. */
    public Map<String, Object> sheetsDelete(ActionContext ctx, Object token, String id, String name, String ep) {
        return sheetsDelete(ctx, token, id, name, ep, null);
    }

    @Action(id = "sheets_rename_sheet", name = "Rename sheet", category = "Sheets", capabilities = "network", idempotent = true, description = "Give a sheet (tab) a new name.",
            panel = "access_token, spreadsheet_id, (Sheet by: Name = sheet / Position = position), new_name | endpoint_override")
    public Map<String, Object> sheetsRename(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Current sheet name; or give position instead.") String name,
            @Input(name = "new_name", type = OoaType.STRING, description = "New sheet name.") String newName,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "Tab position of the sheet to rename, starting at 1; used instead of sheet.") Integer position) {
        id = spreadsheetId(ctx, token, id, ep);
        int sid = sheetId(sheetByNameOrPosition(ctx, token, id, name, position, "rename", ep));
        batch(ctx, token, id, ep, List.of(Map.of("updateSheetProperties", Map.of("properties", Map.of("sheetId", sid, "title", newName), "fields", "title"))));
        return Map.of();
    }

    /** Earlier signature, kept for callers written before position. */
    public Map<String, Object> sheetsRename(ActionContext ctx, Object token, String id, String name, String newName, String ep) {
        return sheetsRename(ctx, token, id, name, newName, ep, null);
    }

    /** The sheet named, or the one at a tab position starting at 1 (hidden sheets count); exactly one of the two must be given. */
    private static Map<String, Object> sheetByNameOrPosition(ActionContext ctx, Object token, String id, String name, Integer position, String verb, String ep) {
        if (position == null) {
            if (blank(name)) throw bad("Name the sheet to " + verb + ", or give its position", null);
            return sheet(ctx, token, id, name, ep);
        }
        if (!blank(name)) throw bad("Give the sheet name or its position, not both", null);
        if (position < 1) throw bad("Sheet positions start at 1", null);
        List<Map<String, Object>> all = new ArrayList<>(sheetList(ctx, token, id, ep));
        all.sort(Comparator.comparingInt(m -> m.get("index") instanceof Number n ? n.intValue() : 0));
        if (position > all.size()) throw new ActionException(P + ".NO_SHEET", "There is no sheet at position " + position + "; the spreadsheet has " + all.size(),
                "Check the position or use List sheets.");
        return all.get(position - 1);
    }

    private Map<String, Object> setHidden(ActionContext ctx, Object token, String id, String name, boolean hidden, String ep) {
        int sid = sheetId(sheet(ctx, token, id, name, ep));
        batch(ctx, token, id, ep, List.of(Map.of("updateSheetProperties", Map.of("properties", Map.of("sheetId", sid, "hidden", hidden), "fields", "hidden"))));
        return Map.of();
    }

    @Action(id = "sheets_hide_sheet", name = "Hide sheet", category = "Sheets", capabilities = "network", idempotent = true, description = "Hide a sheet (tab) from view without deleting it.",
            panel = "access_token, spreadsheet_id, sheet | endpoint_override")
    public Map<String, Object> sheetsHide(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, description = "Sheet to hide.") String name,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        return setHidden(ctx, token, id, name, true, ep);
    }

    @Action(id = "sheets_show_sheet", name = "Show sheet", category = "Sheets", capabilities = "network", idempotent = true, description = "Make a hidden sheet (tab) visible again.",
            panel = "access_token, spreadsheet_id, sheet | endpoint_override")
    public Map<String, Object> sheetsShow(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, description = "Sheet to show.") String name,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        return setHidden(ctx, token, id, name, false, ep);
    }

    @Action(id = "sheets_get_cell", name = "Get cell value", category = "Sheets", idempotent = true, capabilities = "network", description = "Read the value of one cell.",
            panel = "access_token, spreadsheet_id, cell, formatted | endpoint_override")
    @Output(name = "value", description = "Cell value (number, text or boolean); empty when the cell is blank.")
    public Map<String, Object> sheetsGetCell(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "cell", type = OoaType.STRING, example = "Sheet1!B2", description = D_CELL) String cell,
            @Input(name = "formatted", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the text as displayed (e.g. 12.5%) instead of the raw value.") boolean formatted,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        return Out.of("value", firstValue(getValues(ctx, token, id, cell, formatted ? "FORMATTED_VALUE" : "UNFORMATTED_VALUE", null, ep)));
    }

    @Action(id = "sheets_set_cell", name = "Set cell value", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Write a value into one cell; text is interpreted as if typed by a user.",
            panel = "access_token, spreadsheet_id, cell, value | endpoint_override")
    public Map<String, Object> sheetsSetCell(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "cell", type = OoaType.STRING, example = "Sheet1!B2", description = D_CELL) String cell,
            @Input(name = "value", description = "Value to write; empty clears the cell.") Object value,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        putValues(ctx, token, id, cell, List.of(List.of(value == null ? "" : Values.toJsonable(value))), ep);
        return Map.of();
    }

    @Action(id = "sheets_get_range", name = "Get range values", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Read a range as a list of rows and, optionally, as a data table with the first row as column names.",
            panel = "access_token, spreadsheet_id, range, header, formatted | endpoint_override")
    @Output(name = "values", type = OoaType.LIST, description = "List of rows, each a list of cell values.")
    @Output(name = "table", type = OoaType.TABLE, description = "The same range as a data table.")
    public Map<String, Object> sheetsGetRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1:D20", description = D_RANGE) String range,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Table only: the first row holds column names.") boolean header,
            @Input(name = "formatted", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return values as displayed instead of raw values.") boolean formatted,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Map<String, Object> r = getValues(ctx, token, id, range, formatted ? "FORMATTED_VALUE" : "UNFORMATTED_VALUE", null, ep);
        List<Object> values = new ArrayList<>();
        List<List<Object>> grid = new ArrayList<>();
        for (Object row : list(r.get("values"))) { values.add(new ArrayList<>(list(row))); grid.add(new ArrayList<>(list(row))); }
        int width = grid.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        if (header && !grid.isEmpty()) for (Object h : grid.remove(0)) cols.add(Values.text(h));
        while (cols.size() < width) cols.add("Column" + (cols.size() + 1));
        List<Object> rows = new ArrayList<>();
        for (List<Object> g : grid) { while (g.size() < cols.size()) g.add(null); rows.add(g); }
        return Map.of("values", values, "table", Values.table(cols, rows));
    }

    @Action(id = "sheets_clear_range", name = "Clear range", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Remove the values in a range while keeping its formatting and the cell layout.",
            panel = "access_token, spreadsheet_id, range | endpoint_override")
    public Map<String, Object> sheetsClear(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A2:D100", description = D_RANGE) String range,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        call(ctx, token, "POST", SHEETS + HttpJson.enc(id) + "/values/" + HttpJson.enc(range) + ":clear", ep, Map.of());
        return Map.of();
    }

    @Action(id = "sheets_delete_range", name = "Delete range", category = "Sheets", capabilities = "network",
            description = "Delete the cells of a range and shift the neighbouring cells up or left to fill the gap.",
            panel = "access_token, spreadsheet_id, range, shift | endpoint_override")
    public Map<String, Object> sheetsDeleteRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!B2:C4", description = D_RANGE) String range,
            @Input(name = "shift", type = OoaType.STRING, required = false, defaultValue = "\"up\"", enumValues = {"up", "left"}, description = "Direction the remaining cells move.") String shift,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        String dim = switch (shift == null ? "up" : shift.toLowerCase(Locale.ROOT)) { case "up" -> "ROWS"; case "left" -> "COLUMNS"; default -> throw bad("shift must be up or left", null); };
        batch(ctx, token, id, ep, List.of(Map.of("deleteRange", Map.of("range", grid(ctx, token, id, range, ep), "shiftDimension", dim))));
        return Map.of();
    }

    @Action(id = "sheets_insert_range", name = "Insert range", category = "Sheets", capabilities = "network",
            description = "Insert empty cells at a range, pushing the existing cells down or right.",
            panel = "access_token, spreadsheet_id, range, shift | endpoint_override")
    public Map<String, Object> sheetsInsertRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!B2:C4", description = D_RANGE) String range,
            @Input(name = "shift", type = OoaType.STRING, required = false, defaultValue = "\"down\"", enumValues = {"down", "right"}, description = "Direction the existing cells move.") String shift,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        String dim = switch (shift == null ? "down" : shift.toLowerCase(Locale.ROOT)) { case "down" -> "ROWS"; case "right" -> "COLUMNS"; default -> throw bad("shift must be down or right", null); };
        batch(ctx, token, id, ep, List.of(Map.of("insertRange", Map.of("range", grid(ctx, token, id, range, ep), "shiftDimension", dim))));
        return Map.of();
    }

    private Map<String, Object> dimension(ActionContext ctx, Object token, String id, String sheetName, String dim, int start0, int count, boolean insert, String ep) {
        if (count < 1) throw bad("count must be 1 or more", null);
        Map<String, Object> range = Map.of("sheetId", sheetId(sheet(ctx, token, id, sheetName, ep)), "dimension", dim, "startIndex", start0, "endIndex", start0 + count);
        batch(ctx, token, id, ep, List.of(insert ? Map.of("insertDimension", Map.of("range", range, "inheritFromBefore", start0 > 0))
                : Map.of("deleteDimension", Map.of("range", range))));
        return Map.of();
    }

    private static int rowNumber(int row) { if (row < 1) throw bad("Row numbers start at 1", null); return row - 1; }

    @Action(id = "sheets_insert_rows", name = "Insert rows", category = "Sheets", capabilities = "network", description = "Insert empty rows before a row number.",
            panel = "access_token, spreadsheet_id, sheet, row, count | endpoint_override")
    public Map<String, Object> sheetsInsertRows(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "row", type = OoaType.NUMBER, description = "The new rows are inserted before this row (1 = top).") int row,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Number of rows.") int count,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        return dimension(ctx, token, id, sheet, "ROWS", rowNumber(row), count, true, ep);
    }

    @Action(id = "sheets_delete_rows", name = "Delete rows", category = "Sheets", capabilities = "network", description = "Delete rows starting at a row number; rows below move up.",
            panel = "access_token, spreadsheet_id, sheet, row, count | endpoint_override")
    public Map<String, Object> sheetsDeleteRows(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "row", type = OoaType.NUMBER, description = "First row to delete (1 = top).") int row,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Number of rows.") int count,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        return dimension(ctx, token, id, sheet, "ROWS", rowNumber(row), count, false, ep);
    }

    @Action(id = "sheets_insert_columns", name = "Insert columns", category = "Sheets", capabilities = "network", description = "Insert empty columns before a column.",
            panel = "access_token, spreadsheet_id, sheet, column, count | endpoint_override")
    public Map<String, Object> sheetsInsertColumns(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "column", type = OoaType.STRING, example = "C", description = "The new columns are inserted before this column (letter or number from 1).") String column,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Number of columns.") int count,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        return dimension(ctx, token, id, sheet, "COLUMNS", colIndex(column), count, true, ep);
    }

    @Action(id = "sheets_delete_columns", name = "Delete columns", category = "Sheets", capabilities = "network", description = "Delete columns starting at a column; columns to the right move left.",
            panel = "access_token, spreadsheet_id, sheet, column, count | endpoint_override")
    public Map<String, Object> sheetsDeleteColumns(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "column", type = OoaType.STRING, example = "C", description = "First column to delete (letter or number from 1).") String column,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Number of columns.") int count,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        return dimension(ctx, token, id, sheet, "COLUMNS", colIndex(column), count, false, ep);
    }

    @Action(id = "sheets_read_row", name = "Read row", category = "Sheets", idempotent = true, capabilities = "network", description = "Read all values of one row as a list.",
            panel = "access_token, spreadsheet_id, sheet, row | endpoint_override")
    @Output(name = "values", type = OoaType.LIST, description = "Cell values from column A to the last filled column.")
    public Map<String, Object> sheetsReadRow(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row number (1 = top).") int row,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        int r = rowNumber(row) + 1;
        Map<String, Object> v = getValues(ctx, token, id, a1(sheetTitle(ctx, token, id, sheet, ep), r + ":" + r), "UNFORMATTED_VALUE", "ROWS", ep);
        List<Object> rows = list(v.get("values"));
        return Map.of("values", rows.isEmpty() ? new ArrayList<>() : new ArrayList<>(list(rows.get(0))));
    }

    @Action(id = "sheets_read_column", name = "Read column", category = "Sheets", idempotent = true, capabilities = "network", description = "Read all values of one column as a list.",
            panel = "access_token, spreadsheet_id, sheet, column | endpoint_override")
    @Output(name = "values", type = OoaType.LIST, description = "Cell values from row 1 to the last filled row.")
    public Map<String, Object> sheetsReadColumn(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "column", type = OoaType.STRING, example = "B", description = "Column letter or number from 1.") String column,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        String c = colLetters(colIndex(column));
        Map<String, Object> v = getValues(ctx, token, id, a1(sheetTitle(ctx, token, id, sheet, ep), c + ":" + c), "UNFORMATTED_VALUE", "COLUMNS", ep);
        List<Object> cols = list(v.get("values"));
        return Map.of("values", cols.isEmpty() ? new ArrayList<>() : new ArrayList<>(list(cols.get(0))));
    }

    @Action(id = "sheets_row_count", name = "Get used row count", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Count the rows and columns in use on a sheet, up to the last filled cell.",
            panel = "access_token, spreadsheet_id, sheet, non_empty_only | endpoint_override")
    @Output(name = "rows", type = OoaType.NUMBER, description = "Number of the last row that holds a value.")
    @Output(name = "columns", type = OoaType.NUMBER, description = "Number of the last column that holds a value.")
    public Map<String, Object> sheetsRowCount(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "non_empty_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Count only rows that hold a value, instead of the number of the last filled row.") boolean nonEmpty,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        List<Object> rows = list(getValues(ctx, token, id, a1(sheetTitle(ctx, token, id, sheet, ep), ""), "UNFORMATTED_VALUE", "ROWS", ep).get("values"));
        long filled = rows.stream().filter(r -> list(r).stream().anyMatch(v -> v != null && !Values.text(v).isEmpty())).count();
        return Map.of("rows", num(nonEmpty ? filled : rows.size()), "columns", num(rows.stream().mapToInt(r -> list(r).size()).max().orElse(0)));
    }

    @Action(id = "sheets_set_formula", name = "Set formula", category = "Sheets", idempotent = true, capabilities = "network", description = "Put a formula into one cell.",
            panel = "access_token, spreadsheet_id, cell, formula | endpoint_override")
    public Map<String, Object> sheetsSetFormula(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "cell", type = OoaType.STRING, example = "Sheet1!D2", description = D_CELL) String cell,
            @Input(name = "formula", type = OoaType.STRING, example = "=SUM(B2:C2)", description = "Formula; a leading = is added when missing.") String formula,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        if (blank(formula)) throw bad("The formula is empty", null);
        String f = formula.trim().startsWith("=") ? formula.trim() : "=" + formula.trim();
        putValues(ctx, token, id, cell, List.of(List.of(f)), ep);
        return Map.of();
    }

    @Action(id = "sheets_read_formula", name = "Read formula", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Read the formula of one cell (the plain value when the cell has no formula).",
            panel = "access_token, spreadsheet_id, cell | endpoint_override")
    @Output(name = "formula", type = OoaType.STRING, description = "Formula text such as =SUM(B2:C2).")
    public Map<String, Object> sheetsReadFormula(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "cell", type = OoaType.STRING, example = "Sheet1!D2", description = D_CELL) String cell,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Object v = firstValue(getValues(ctx, token, id, cell, "FORMULA", null, ep));
        return Out.of("formula", v == null ? "" : Values.text(v));
    }

    @Action(id = "sheets_format_cells", name = "Format cells", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Apply bold, italic, font size, text and fill colours, alignment and number format to a range.",
            panel = "access_token, spreadsheet_id, range, [Change the font: font_family, font_size, bold, italic, underline, strikethrough, font_color], [Change the alignment: horizontal_alignment, vertical_alignment], [Change text wrapping: wrap], [Merge or unmerge: merge] | background_color, number_format, number_format_type, border_style, border_color, endpoint_override")
    public Map<String, Object> sheetsFormat(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1:D1", description = D_RANGE) String range,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Bold on or off; unchanged when empty.") Boolean bold,
            @Input(name = "italic", type = OoaType.BOOLEAN, required = false, description = "Italic on or off; unchanged when empty.") Boolean italic,
            @Input(name = "font_size", type = OoaType.NUMBER, required = false, description = "Font size in points; unchanged when empty.") Integer fontSize,
            @Input(name = "font_color", type = OoaType.STRING, required = false, example = "#FFFFFF", description = "Text colour as #RRGGBB; unchanged when empty.") String fontColor,
            @Input(name = "background_color", type = OoaType.STRING, required = false, example = "#1F4E79", description = "Fill colour as #RRGGBB; unchanged when empty.") String bg,
            @Input(name = "horizontal_alignment", type = OoaType.STRING, required = false, enumValues = {"left", "center", "right"}, description = "Horizontal alignment; unchanged when empty.") String align,
            @Input(name = "number_format", type = OoaType.STRING, required = false, example = "#,##0.00", description = "Number format pattern such as #,##0.00, 0% or yyyy-mm-dd; unchanged when empty.") String pattern,
            @Input(name = "number_format_type", type = OoaType.STRING, required = false, defaultValue = "\"NUMBER\"",
                    enumValues = {"NUMBER", "CURRENCY", "PERCENT", "DATE", "TIME", "DATE_TIME", "SCIENTIFIC", "TEXT"}, description = "Kind of number format the pattern describes.") String patternType,
            @Input(name = "underline", type = OoaType.BOOLEAN, required = false, description = "Underline on or off; unchanged when empty.") Boolean underline,
            @Input(name = "strikethrough", type = OoaType.BOOLEAN, required = false, description = "Strikethrough on or off; unchanged when empty.") Boolean strike,
            @Input(name = "font_family", type = OoaType.STRING, required = false, example = "Roboto", description = "Font name; unchanged when empty.") String family,
            @Input(name = "vertical_alignment", type = OoaType.STRING, required = false, enumValues = {"top", "middle", "bottom"}, description = "Vertical alignment; unchanged when empty.") String vAlign,
            @Input(name = "wrap", type = OoaType.STRING, required = false, enumValues = {"overflow", "clip", "wrap"}, description = "How long text is shown; unchanged when empty.") String wrap,
            @Input(name = "merge", type = OoaType.STRING, required = false, enumValues = {"all", "rows", "columns", "unmerge"}, description = "Merge the range (all, each row or each column) or unmerge it; unchanged when empty.") String merge,
            @Input(name = "border_style", type = OoaType.STRING, required = false, enumValues = {"none", "solid", "solid_medium", "solid_thick", "dashed", "dotted", "double"}, description = "Style of every border of the range, inside and out; unchanged when empty.") String borderStyle,
            @Input(name = "border_color", type = OoaType.STRING, required = false, example = "#000000", description = "Border colour as #RRGGBB; black when empty.") String borderColor,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Map<String, Object> fmt = new LinkedHashMap<>(), text = new LinkedHashMap<>();
        List<String> fields = new ArrayList<>();
        List<Map<String, Object>> extra = new ArrayList<>();
        if (underline != null) { text.put("underline", underline); fields.add("userEnteredFormat.textFormat.underline"); }
        if (strike != null) { text.put("strikethrough", strike); fields.add("userEnteredFormat.textFormat.strikethrough"); }
        if (!blank(family)) { text.put("fontFamily", family.trim()); fields.add("userEnteredFormat.textFormat.fontFamily"); }
        if (!blank(vAlign)) {
            String v = vAlign.trim().toUpperCase(Locale.ROOT);
            if (!List.of("TOP", "MIDDLE", "BOTTOM").contains(v)) throw bad("vertical_alignment must be top, middle or bottom", null);
            fmt.put("verticalAlignment", v); fields.add("userEnteredFormat.verticalAlignment");
        }
        if (!blank(wrap)) {
            String v = switch (wrap.trim().toLowerCase(Locale.ROOT)) { case "overflow" -> "OVERFLOW_CELL"; case "clip" -> "CLIP"; case "wrap" -> "WRAP"; default -> throw bad("wrap must be overflow, clip or wrap", null); };
            fmt.put("wrapStrategy", v); fields.add("userEnteredFormat.wrapStrategy");
        }
        if (!blank(merge) || !blank(borderStyle)) {
            Map<String, Object> g = grid(ctx, token, id, range, ep);
            if (!blank(merge)) extra.add(switch (merge.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> Map.of("mergeCells", Map.of("range", g, "mergeType", "MERGE_ALL"));
                case "rows" -> Map.of("mergeCells", Map.of("range", g, "mergeType", "MERGE_ROWS"));
                case "columns" -> Map.of("mergeCells", Map.of("range", g, "mergeType", "MERGE_COLUMNS"));
                case "unmerge" -> Map.of("unmergeCells", Map.of("range", g));
                default -> throw bad("merge must be all, rows, columns or unmerge", null);
            });
            if (!blank(borderStyle)) {
                String st = borderStyle.trim().toUpperCase(Locale.ROOT);
                if (!List.of("NONE", "SOLID", "SOLID_MEDIUM", "SOLID_THICK", "DASHED", "DOTTED", "DOUBLE").contains(st)) throw bad("Unknown border_style '" + borderStyle + "'", null);
                Map<String, Object> line = new LinkedHashMap<>(Map.of("style", st));
                line.put("color", color(blank(borderColor) ? "#000000" : borderColor, "border_color"));
                Map<String, Object> b = new LinkedHashMap<>(Map.of("range", g));
                for (String side : List.of("top", "bottom", "left", "right", "innerHorizontal", "innerVertical")) b.put(side, line);
                extra.add(Map.of("updateBorders", b));
            }
        }
        if (bold != null) { text.put("bold", bold); fields.add("userEnteredFormat.textFormat.bold"); }
        if (italic != null) { text.put("italic", italic); fields.add("userEnteredFormat.textFormat.italic"); }
        if (fontSize != null) { text.put("fontSize", fontSize); fields.add("userEnteredFormat.textFormat.fontSize"); }
        if (!blank(fontColor)) { text.put("foregroundColor", color(fontColor, "font_color")); fields.add("userEnteredFormat.textFormat.foregroundColor"); }
        if (!text.isEmpty()) fmt.put("textFormat", text);
        if (!blank(bg)) { fmt.put("backgroundColor", color(bg, "background_color")); fields.add("userEnteredFormat.backgroundColor"); }
        if (!blank(align)) {
            String a = align.trim().toUpperCase(Locale.ROOT);
            if (!List.of("LEFT", "CENTER", "RIGHT").contains(a)) throw bad("horizontal_alignment must be left, center or right", null);
            fmt.put("horizontalAlignment", a); fields.add("userEnteredFormat.horizontalAlignment");
        }
        if (!blank(pattern)) {
            fmt.put("numberFormat", Map.of("type", blank(patternType) ? "NUMBER" : patternType.toUpperCase(Locale.ROOT), "pattern", pattern));
            fields.add("userEnteredFormat.numberFormat");
        }
        if (fields.isEmpty() && extra.isEmpty()) throw new ActionException(P + ".NOTHING_TO_FORMAT", "No formatting was given", "Fill in at least one format input such as bold or background_color.");
        List<Map<String, Object>> requests = new ArrayList<>();
        if (!fields.isEmpty()) requests.add(Map.of("repeatCell", Map.of("range", grid(ctx, token, id, range, ep), "cell", Map.of("userEnteredFormat", fmt), "fields", String.join(",", fields))));
        requests.addAll(extra);
        batch(ctx, token, id, ep, requests);
        return Map.of();
    }

    @Action(id = "sheets_get_cell_color", name = "Get cell colour", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Read the fill and text colour of one cell as #RRGGBB.",
            panel = "access_token, spreadsheet_id, cell | endpoint_override")
    @Output(name = "background_color", type = OoaType.STRING, description = "Fill colour, #FFFFFF when none is set.")
    @Output(name = "font_color", type = OoaType.STRING, description = "Text colour, #000000 when none is set.")
    public Map<String, Object> sheetsCellColor(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "cell", type = OoaType.STRING, example = "Sheet1!B2", description = D_CELL) String cell,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        String r = fullRange(ctx, token, id, cell, ep);
        Map<String, Object> resp = call(ctx, token, "GET", SHEETS + HttpJson.enc(id) + "?ranges=" + HttpJson.enc(r) + "&fields="
                + HttpJson.enc("sheets.data.rowData.values.effectiveFormat(backgroundColor,textFormat.foregroundColor)"), ep, null);
        Map<String, Object> fmt = Map.of();
        for (Object s : list(resp.get("sheets"))) for (Object d : list(obj(s).get("data"))) for (Object rd : list(obj(d).get("rowData")))
            for (Object v : list(obj(rd).get("values"))) { fmt = obj(obj(v).get("effectiveFormat")); break; }
        Map<String, Object> bgc = obj(fmt.get("backgroundColor"));
        return Map.of("background_color", hex(bgc.isEmpty() && !fmt.containsKey("backgroundColor") ? null : bgc, "#FFFFFF"),
                "font_color", hex(obj(obj(fmt.get("textFormat")).get("foregroundColor")), "#000000"));
    }

    private Map<String, Object> autofit(ActionContext ctx, Object token, String id, String sheetName, String dim, Integer start0, Integer end0, String ep) {
        Map<String, Object> d = new LinkedHashMap<>(Map.of("sheetId", sheetId(sheet(ctx, token, id, sheetName, ep)), "dimension", dim));
        if (start0 != null) d.put("startIndex", start0);
        if (end0 != null) d.put("endIndex", end0 + 1);
        batch(ctx, token, id, ep, List.of(Map.of("autoResizeDimensions", Map.of("dimensions", d))));
        return Map.of();
    }

    @Action(id = "sheets_autofit_columns", name = "Autofit columns", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Resize columns to fit their content.",
            panel = "access_token, spreadsheet_id, sheet, from_column, to_column | endpoint_override")
    public Map<String, Object> sheetsAutofitColumns(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "from_column", type = OoaType.STRING, required = false, description = "First column (letter or number); all columns when empty.") String from,
            @Input(name = "to_column", type = OoaType.STRING, required = false, description = "Last column (letter or number); the first column when only that is given.") String to,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Integer s = blank(from) ? null : colIndex(from);
        Integer e = blank(to) ? s : Integer.valueOf(colIndex(to));
        return autofit(ctx, token, id, sheet, "COLUMNS", s, e, ep);
    }

    @Action(id = "sheets_autofit_rows", name = "Autofit rows", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Resize rows to fit their content.",
            panel = "access_token, spreadsheet_id, sheet, from_row, to_row | endpoint_override")
    public Map<String, Object> sheetsAutofitRows(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "from_row", type = OoaType.NUMBER, required = false, description = "First row from 1; all rows when empty.") Integer from,
            @Input(name = "to_row", type = OoaType.NUMBER, required = false, description = "Last row; the first row when only that is given.") Integer to,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        Integer s = from == null ? null : rowNumber(from);
        Integer e = to == null ? s : Integer.valueOf(rowNumber(to));
        return autofit(ctx, token, id, sheet, "ROWS", s, e, ep);
    }

    @Action(id = "sheets_write_data_table", name = "Write data table", category = "Sheets", capabilities = "network",
            description = "Write a data table to a sheet from a start cell, optionally clearing the sheet first so no old rows remain.",
            panel = "access_token, spreadsheet_id, (Worksheet: The first sheet = / A named sheet = sheet), table, start_cell, include_header | clear_sheet_first, endpoint_override")
    @Output(name = "updated_range", type = OoaType.STRING, description = "Range that received the data.")
    @Output(name = "updated_cells", type = OoaType.NUMBER, description = "Cells written.")
    public Map<String, Object> sheetsWriteTable(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "start_cell", type = OoaType.STRING, required = false, defaultValue = "\"A1\"", description = "Top-left cell of the data.") String start,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to write.") Map<String, Object> table,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write column names as the first row.") boolean header,
            @Input(name = "clear_sheet_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove all values on the sheet before writing.") boolean clear,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        String title = sheetTitle(ctx, token, id, sheet, ep);
        String cell = blank(start) ? "A1" : start.trim();
        cellRef(cell);
        if (clear) call(ctx, token, "POST", SHEETS + HttpJson.enc(id) + "/values/" + HttpJson.enc(a1(title, "")) + ":clear", ep, Map.of());
        Map<String, Object> r = putValues(ctx, token, id, a1(title, cell), grid(table, header), ep);
        return Out.of("updated_range", r.get("updatedRange"), "updated_cells", r.get("updatedCells"));
    }

    @Action(id = "sheets_copy_range", name = "Copy range", category = "Sheets", capabilities = "network",
            description = "Copy a range to another place, in the same or another sheet of the spreadsheet: values, formats, formulas or everything.",
            panel = "access_token, spreadsheet_id, source, destination, paste, transpose | endpoint_override")
    public Map<String, Object> sheetsCopyRange(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "source", type = OoaType.STRING, example = "Sheet1!A1:C10", description = "Range to copy. " + D_RANGE) String source,
            @Input(name = "destination", type = OoaType.STRING, example = "Sheet2!E1", description = "Top-left cell (or range) to paste into, optionally with the sheet name.") String destination,
            @Input(name = "paste", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "values", "formats", "formulas", "no_borders"}, description = "What to paste.") String paste,
            @Input(name = "transpose", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Turn rows into columns.") boolean transpose,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        String type = switch (blank(paste) ? "all" : paste.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> "PASTE_NORMAL"; case "values" -> "PASTE_VALUES"; case "formats" -> "PASTE_FORMAT"; case "formulas" -> "PASTE_FORMULA"; case "no_borders" -> "PASTE_NO_BORDERS";
            default -> throw bad("paste must be all, values, formats, formulas or no_borders", null);
        };
        batch(ctx, token, id, ep, List.of(Map.of("copyPaste", Map.of("source", grid(ctx, token, id, source, ep), "destination", grid(ctx, token, id, destination, ep),
                "pasteType", type, "pasteOrientation", transpose ? "TRANSPOSE" : "NORMAL"))));
        return Map.of();
    }

    @Action(id = "sheets_find_cells", name = "Find cells", category = "Sheets", idempotent = true, capabilities = "network",
            description = "Search a sheet or range for text and return the addresses of the matching cells.",
            panel = "access_token, spreadsheet_id, range, text, match_case, whole_cell | endpoint_override")
    @Output(name = "cells", type = OoaType.LIST, description = "A1 addresses of the matches, e.g. B7, in reading order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matches.")
    public Map<String, Object> sheetsFind(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = D_SID) String id,
            @Input(name = "range", type = OoaType.STRING, required = false, description = "Sheet name or range to search, e.g. Sheet1 or Sheet1!A1:F200; the first sheet when empty.") String range,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Upper and lower case must match.") boolean matchCase,
            @Input(name = "whole_cell", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The whole cell must equal the text, not just contain it.") boolean whole,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        id = spreadsheetId(ctx, token, id, ep);
        if (text == null || text.isEmpty()) throw bad("Give the text to find", null);
        String r;
        if (blank(range)) r = a1(sheetTitle(ctx, token, id, null, ep), "");
        else if (!range.contains("!") && !range.trim().matches("\\$?[A-Za-z]{1,3}\\$?\\d*(:\\$?[A-Za-z]{1,3}\\$?\\d*)?")) r = a1(range.trim(), "");
        else r = fullRange(ctx, token, id, range, ep);
        Map<String, Object> v = getValues(ctx, token, id, r, "FORMATTED_VALUE", "ROWS", ep);
        String start = splitRange(str(v.getOrDefault("range", r)))[1].split(":")[0];
        int[] origin = start.isEmpty() ? new int[]{0, 0} : cellRef(start);
        int r0 = Math.max(origin[0], 0), c0 = Math.max(origin[1], 0);
        String needle = matchCase ? text : text.toLowerCase(Locale.ROOT);
        List<Object> found = new ArrayList<>();
        List<Object> rows = list(v.get("values"));
        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = list(rows.get(i));
            for (int j = 0; j < row.size(); j++) {
                String cellText = Values.text(row.get(j));
                String hay = matchCase ? cellText : cellText.toLowerCase(Locale.ROOT);
                if (whole ? hay.equals(needle) : hay.contains(needle)) found.add(colLetters(c0 + j) + (r0 + i + 1));
            }
        }
        return Map.of("cells", found, "count", num(found.size()));
    }

    // ------------------------------------------------------------------ Drive

    private static Map<String, Object> fileRow(Map<String, Object> f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.get("id"));
        m.put("name", f.get("name"));
        m.put("mime_type", f.get("mimeType"));
        m.put("is_folder", FOLDER_MIME.equals(f.get("mimeType")));
        m.put("size", f.get("size") == null ? null : new BigDecimal(str(f.get("size"))));
        m.put("created", f.get("createdTime"));
        m.put("modified", f.get("modifiedTime"));
        m.put("parents", new ArrayList<>(list(f.get("parents"))));
        m.put("web_link", f.get("webViewLink"));
        List<Object> owners = new ArrayList<>();
        for (Object o : list(f.get("owners"))) owners.add(obj(o).get("emailAddress"));
        m.put("owners", owners);
        m.put("trashed", Boolean.TRUE.equals(f.get("trashed")));
        return m;
    }

    private static final String FILE_FIELDS = "id,name,mimeType,size,createdTime,modifiedTime,parents,webViewLink,owners(emailAddress),trashed";

    /** Names of a Drive path such as Reports/2026/q1.xlsx; empty names and a leading My Drive are left out. */
    static List<String> pathNames(String path) {
        List<String> out = new ArrayList<>();
        for (String part : (path == null ? "" : path.replace('\\', '/')).split("/")) if (!part.isBlank()) out.add(part.trim());
        if (!out.isEmpty() && out.get(0).equalsIgnoreCase("My Drive")) out.remove(0);
        return out;
    }

    /** Id of the item at a path under My Drive, looked up one name at a time; every name but the last must be a folder. */
    private static String idByPath(ActionContext ctx, Object token, String path, boolean folderOnly, String ep) {
        List<String> names = pathNames(path);
        if (names.isEmpty()) {
            if (folderOnly) return "root";
            throw bad("The path '" + path + "' names no file or folder", "Example: Reports/2026/summary.pdf");
        }
        String parent = "root";
        for (int i = 0; i < names.size(); i++) {
            boolean last = i == names.size() - 1;
            List<String> q = new ArrayList<>(List.of("trashed = false", "name = " + quoted(names.get(i)), quoted(parent) + " in parents"));
            if (!last || folderOnly) q.add("mimeType = '" + FOLDER_MIME + "'");
            List<Object> files = list(call(ctx, token, "GET", DRIVE + "?" + HttpJson.query(Map.of("q", String.join(" and ", q), "fields", "files(id,name)", "pageSize", 10,
                    "supportsAllDrives", true, "includeItemsFromAllDrives", true)), ep, null).get("files"));
            String where = i == 0 ? "My Drive" : String.join("/", names.subList(0, i));
            if (files.isEmpty()) throw new ActionException(P + ".NOT_FOUND", "No " + (!last || folderOnly ? "folder" : "file or folder") + " named '" + names.get(i) + "' in " + where,
                    "Check the path; it starts at My Drive, e.g. Reports/2026.");
            if (files.size() > 1) throw new ActionException(P + ".AMBIGUOUS", files.size() + " items are named '" + names.get(i) + "' in " + where, "Give the id instead of the path.");
            parent = str(obj(files.get(0)).get("id"));
        }
        return parent;
    }

    /** The item id given directly, or looked up from item_path; exactly one of the two must be given. */
    private static String itemId(ActionContext ctx, Object token, String id, String path, String ep) {
        if (!blank(path)) {
            if (!blank(id)) throw bad("Give the item id or item_path, not both", null);
            return idByPath(ctx, token, path, false, ep);
        }
        if (blank(id)) throw bad("Give the item id or item_path", "item_path starts at My Drive, e.g. Reports/2026/summary.pdf.");
        return id;
    }

    /** A folder id given directly, or looked up from a folder path ("/" is My Drive); null when neither is given. */
    private static String folderId(ActionContext ctx, Object token, String id, String idName, String path, String pathName, String ep) {
        if (blank(path)) return blank(id) ? null : id;
        if (!blank(id)) throw bad("Give " + idName + " or " + pathName + ", not both", null);
        return idByPath(ctx, token, path, true, ep);
    }

    @Action(id = "drive_create_folder", name = "Create Drive folder", category = "Drive", capabilities = "network", description = "Create a folder in Drive.",
            panel = "access_token, parent_id, name | endpoint_override")
    @Output(name = "folder_id", type = OoaType.STRING, description = "Id of the new folder.")
    @Output(name = "web_link", type = OoaType.STRING, description = "Link to open the folder.")
    public Map<String, Object> driveCreateFolder(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name", type = OoaType.STRING, description = "Folder name.") String name,
            @Input(name = "parent_id", type = OoaType.STRING, required = false, description = "Parent folder id; My Drive root when empty.") String parent,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        if (blank(name)) throw bad("Give the folder a name", null);
        Map<String, Object> meta = new LinkedHashMap<>(Map.of("name", name, "mimeType", FOLDER_MIME));
        if (!blank(parent)) meta.put("parents", List.of(parent));
        Map<String, Object> r = call(ctx, token, "POST", DRIVE + "?fields=id,webViewLink&supportsAllDrives=true", ep, meta);
        return Out.of("folder_id", r.get("id"), "web_link", r.get("webViewLink"));
    }

    @Action(id = "drive_copy_file", name = "Copy Drive file", category = "Drive", capabilities = "network", description = "Make a copy of a Drive file, optionally renamed or in another folder.",
            panel = "access_token, (Pick the file by: Id = file_id / Path = item_path), (Put the copy in: A folder id = folder_id / A folder path = destination_path), [Give the copy a new name: name] | endpoint_override")
    @Output(name = "file_id", type = OoaType.STRING, description = "Id of the copy.")
    public Map<String, Object> driveCopy(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "file_id", type = OoaType.STRING, required = false, description = "File to copy; or give item_path instead.") String id,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name of the copy; Drive's default when empty.") String name,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Folder for the copy; next to the original when empty.") String folder,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "item_path", type = OoaType.STRING, required = false, example = "Reports/2026/summary.pdf",
                    description = "Path of the file under My Drive, used instead of file_id.") String path,
            @Input(name = "destination_path", type = OoaType.STRING, required = false, example = "Reports/2026",
                    description = "Path of the folder for the copy under My Drive, used instead of folder_id.") String destPath) {
        folder = folderId(ctx, token, folder, "folder_id", destPath, "destination_path", ep);
        id = itemId(ctx, token, id, path, ep);
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!blank(name)) meta.put("name", name);
        if (!blank(folder)) meta.put("parents", List.of(folder));
        Map<String, Object> r = call(ctx, token, "POST", DRIVE + "/" + HttpJson.enc(id) + "/copy?fields=id&supportsAllDrives=true", ep, meta);
        return Out.of("file_id", r.get("id"));
    }

    /** Earlier signature, kept for callers written before item_path. */
    public Map<String, Object> driveCopy(ActionContext ctx, Object token, String id, String name, String folder, String ep) {
        return driveCopy(ctx, token, id, name, folder, ep, null, null);
    }

    /** Earlier signature, kept for callers written before destination_path. */
    public Map<String, Object> driveCopy(ActionContext ctx, Object token, String id, String name, String folder, String ep, String path) {
        return driveCopy(ctx, token, id, name, folder, ep, path, null);
    }

    @Action(id = "drive_move_item", name = "Move Drive item", category = "Drive", capabilities = "network", idempotent = true, description = "Move a file or folder into another folder.",
            panel = "access_token, (Pick the item by: Id = item_id / Path = item_path), (Move into: A folder id = folder_id / A folder path = destination_path) | endpoint_override")
    public Map<String, Object> driveMove(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, required = false, description = "File or folder id; or give item_path instead.") String id,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Destination folder id; or give destination_path instead.") String folder,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "item_path", type = OoaType.STRING, required = false, example = "Inbox/invoice.pdf", description = "Path of the file or folder under My Drive, used instead of item_id.") String path,
            @Input(name = "destination_path", type = OoaType.STRING, required = false, example = "Archive/2026",
                    description = "Path of the destination folder under My Drive, used instead of folder_id.") String destPath) {
        if (blank(folder) && blank(destPath)) throw bad("Give the destination folder id or destination_path", null);
        folder = folderId(ctx, token, folder, "folder_id", destPath, "destination_path", ep);
        id = itemId(ctx, token, id, path, ep);
        List<Object> parents = list(call(ctx, token, "GET", DRIVE + "/" + HttpJson.enc(id) + "?fields=parents&supportsAllDrives=true", ep, null).get("parents"));
        List<String> remove = new ArrayList<>();
        for (Object p : parents) if (!folder.equals(p)) remove.add(String.valueOf(p));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("addParents", folder);
        if (!remove.isEmpty()) q.put("removeParents", String.join(",", remove));
        q.put("supportsAllDrives", true);
        call(ctx, token, "PATCH", DRIVE + "/" + HttpJson.enc(id) + "?" + HttpJson.query(q), ep, Map.of());
        return Map.of();
    }

    /** Earlier signature, kept for callers written before item_path. */
    public Map<String, Object> driveMove(ActionContext ctx, Object token, String id, String folder, String ep) {
        return driveMove(ctx, token, id, folder, ep, null, null);
    }

    /** Earlier signature, kept for callers written before destination_path. */
    public Map<String, Object> driveMove(ActionContext ctx, Object token, String id, String folder, String ep, String path) {
        return driveMove(ctx, token, id, folder, ep, path, null);
    }

    @Action(id = "drive_rename_item", name = "Rename Drive item", category = "Drive", capabilities = "network", idempotent = true, description = "Give a file or folder a new name.",
            panel = "access_token, (Pick the item by: Id = item_id / Path = item_path), new_name | endpoint_override")
    public Map<String, Object> driveRename(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, required = false, description = "File or folder id; or give item_path instead.") String id,
            @Input(name = "new_name", type = OoaType.STRING, description = "New name.") String name,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "item_path", type = OoaType.STRING, required = false, example = "Reports/draft.docx", description = "Path of the file or folder under My Drive, used instead of item_id.") String path) {
        if (blank(name)) throw bad("The new name is empty", null);
        id = itemId(ctx, token, id, path, ep);
        call(ctx, token, "PATCH", DRIVE + "/" + HttpJson.enc(id) + "?supportsAllDrives=true", ep, Map.of("name", name));
        return Map.of();
    }

    /** Earlier signature, kept for callers written before item_path. */
    public Map<String, Object> driveRename(ActionContext ctx, Object token, String id, String name, String ep) {
        return driveRename(ctx, token, id, name, ep, null);
    }

    @Action(id = "drive_delete_item", name = "Delete Drive item", category = "Drive", capabilities = "network",
            description = "Move a file or folder to the Drive trash, or delete it permanently.",
            panel = "access_token, (Pick the item by: Id = item_id / Path = item_path) | permanent, endpoint_override")
    public Map<String, Object> driveDelete(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, required = false, description = "File or folder id; or give item_path instead.") String id,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good instead of moving to the trash.") boolean permanent,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "item_path", type = OoaType.STRING, required = false, example = "Old/report.pdf", description = "Path of the file or folder under My Drive, used instead of item_id.") String path) {
        id = itemId(ctx, token, id, path, ep);
        if (permanent) call(ctx, token, "DELETE", DRIVE + "/" + HttpJson.enc(id) + "?supportsAllDrives=true", ep, null);
        else call(ctx, token, "PATCH", DRIVE + "/" + HttpJson.enc(id) + "?supportsAllDrives=true", ep, Map.of("trashed", true));
        return Map.of();
    }

    /** Earlier signature, kept for callers written before item_path. */
    public Map<String, Object> driveDelete(ActionContext ctx, Object token, String id, boolean permanent, String ep) {
        return driveDelete(ctx, token, id, permanent, ep, null);
    }

    @Action(id = "drive_find_items", name = "Find Drive items", category = "Drive", idempotent = true, capabilities = "network",
            description = "Find files and folders by name, folder, type or a raw Drive query.",
            panel = "access_token, item_type, (Look in: Anywhere = / A folder id = folder_id / A folder path = folder_path), name_equals, name_contains | mime_type, extra_query, include_trashed, max_results, endpoint_override")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, mime_type, is_folder, size, modified, web_link.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items found.")
    public Map<String, Object> driveFind(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name_contains", type = OoaType.STRING, required = false, description = "Name contains this text.") String contains,
            @Input(name = "name_equals", type = OoaType.STRING, required = false, description = "Name is exactly this.") String equals,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Only items directly inside this folder.") String folder,
            @Input(name = "item_type", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "file", "folder"}, description = "Files, folders or both.") String type,
            @Input(name = "mime_type", type = OoaType.STRING, required = false, example = "application/pdf", description = "Only this MIME type.") String mime,
            @Input(name = "extra_query", type = OoaType.STRING, required = false, example = "modifiedTime > '2026-01-01T00:00:00'", description = "Further Drive query terms joined with and.") String extra,
            @Input(name = "include_trashed", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also return items in the trash.") boolean trashed,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Maximum items returned.") int max,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "folder_path", type = OoaType.STRING, required = false, example = "Reports/2026",
                    description = "Only items directly inside the folder at this path under My Drive; used instead of folder_id.") String folderPath) {
        if (!blank(folderPath)) {
            if (!blank(folder)) throw bad("Give folder_id or folder_path, not both", null);
            String t0 = type == null ? "any" : type.toLowerCase(Locale.ROOT);
            if (!List.of("any", "file", "folder").contains(t0)) throw bad("item_type must be any, file or folder", null);
            folder = idByPath(ctx, token, folderPath, true, ep);
        }
        List<String> q = new ArrayList<>();
        if (!trashed) q.add("trashed = false");
        if (!blank(contains)) q.add("name contains " + quoted(contains));
        if (!blank(equals)) q.add("name = " + quoted(equals));
        if (!blank(folder)) q.add(quoted(folder) + " in parents");
        String t = type == null ? "any" : type.toLowerCase(Locale.ROOT);
        switch (t) {
            case "folder" -> q.add("mimeType = '" + FOLDER_MIME + "'");
            case "file" -> q.add("mimeType != '" + FOLDER_MIME + "'");
            case "any" -> { }
            default -> throw bad("item_type must be any, file or folder", null);
        }
        if (!blank(mime)) q.add("mimeType = " + quoted(mime));
        if (!blank(extra)) q.add("(" + extra + ")");
        List<Object> rows = new ArrayList<>();
        String page = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>();
            if (!q.isEmpty()) params.put("q", String.join(" and ", q));
            params.put("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,webViewLink)");
            params.put("pageSize", Math.min(1000, Math.max(1, max - rows.size())));
            params.put("supportsAllDrives", true);
            params.put("includeItemsFromAllDrives", true);
            if (page != null) params.put("pageToken", page);
            Map<String, Object> r = call(ctx, token, "GET", DRIVE + "?" + HttpJson.query(params), ep, null);
            for (Object o : list(r.get("files"))) {
                if (rows.size() >= max) break;
                Map<String, Object> f = fileRow(obj(o));
                rows.add(new ArrayList<>(Arrays.asList(f.get("id"), f.get("name"), f.get("mime_type"), f.get("is_folder"), f.get("size"), f.get("modified"), f.get("web_link"))));
            }
            page = str(r.get("nextPageToken"));
        } while (page != null && rows.size() < max);
        return Map.of("table", Values.table(List.of("id", "name", "mime_type", "is_folder", "size", "modified", "web_link"), rows), "count", num(rows.size()));
    }

    /** Earlier signature, kept for callers written before folder_path. */
    public Map<String, Object> driveFind(ActionContext ctx, Object token, String contains, String equals, String folder, String type, String mime,
                                         String extra, boolean trashed, int max, String ep) {
        return driveFind(ctx, token, contains, equals, folder, type, mime, extra, trashed, max, ep, null);
    }

    @Action(id = "drive_get_file_info", name = "Get Drive item info", category = "Drive", idempotent = true, capabilities = "network",
            description = "Read the details of a file or folder: name, type, size, dates, owners, parents and link.",
            panel = "access_token, item_id | endpoint_override")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "id, name, mime_type, is_folder, size, created, modified, parents, web_link, owners, trashed.")
    public Map<String, Object> driveInfo(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, description = "File or folder id.") String id,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        return Map.of("info", fileRow(call(ctx, token, "GET", DRIVE + "/" + HttpJson.enc(id) + "?fields=" + HttpJson.enc(FILE_FIELDS) + "&supportsAllDrives=true", ep, null)));
    }

    @Action(id = "drive_list_permissions", name = "List Drive permissions", category = "Drive", idempotent = true, capabilities = "network",
            description = "List who has access to a file or folder and with which role.",
            panel = "access_token, item_id | endpoint_override")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: permission_id, type, role, email, domain, display_name.")
    public Map<String, Object> drivePermissions(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, description = "File or folder id.") String id,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        List<Object> rows = new ArrayList<>();
        String page = null;
        do {
            Map<String, Object> r = call(ctx, token, "GET", DRIVE + "/" + HttpJson.enc(id) + "/permissions?fields="
                    + HttpJson.enc("nextPageToken,permissions(id,type,role,emailAddress,domain,displayName)") + "&supportsAllDrives=true"
                    + (page == null ? "" : "&pageToken=" + HttpJson.enc(page)), ep, null);
            for (Object o : list(r.get("permissions"))) {
                Map<String, Object> p = obj(o);
                rows.add(new ArrayList<>(Arrays.asList(p.get("id"), p.get("type"), p.get("role"), p.get("emailAddress"), p.get("domain"), p.get("displayName"))));
            }
            page = str(r.get("nextPageToken"));
        } while (page != null);
        return Map.of("table", Values.table(List.of("permission_id", "type", "role", "email", "domain", "display_name"), rows));
    }

    @Action(id = "drive_create_permission", name = "Share Drive item", category = "Drive", capabilities = "network",
            description = "Give a user, group, domain or anyone with the link access to a file or folder.",
            panel = "access_token, item_id, role, share_with, email, domain | notify, message, expires, endpoint_override")
    @Output(name = "permission_id", type = OoaType.STRING, description = "Id of the new permission.")
    public Map<String, Object> driveShare(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, description = "File or folder id.") String id,
            @Input(name = "share_with", type = OoaType.STRING, required = false, defaultValue = "\"user\"", enumValues = {"user", "group", "domain", "anyone"}, description = "Who receives access.") String type,
            @Input(name = "role", type = OoaType.STRING, required = false, defaultValue = "\"reader\"", enumValues = {"reader", "commenter", "writer", "fileOrganizer", "organizer"}, description = "Access level.") String role,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "User or group address (for user and group).") String email,
            @Input(name = "domain", type = OoaType.STRING, required = false, description = "Domain such as example.com (for domain).") String domain,
            @Input(name = "notify", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send a notification email (user and group only).") boolean notify,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Text added to the notification email.") String message,
            @Input(name = "expires", type = OoaType.DATETIME, required = false, description = "When the access ends (UTC; users and groups only, Google Workspace accounts).") LocalDateTime expires,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        String t = blank(type) ? "user" : type.toLowerCase(Locale.ROOT);
        Map<String, Object> perm = new LinkedHashMap<>(Map.of("type", t, "role", blank(role) ? "reader" : role));
        if (expires != null) perm.put("expirationTime", expires.toString() + (expires.getSecond() == 0 && expires.getNano() == 0 ? ":00" : "") + "Z");
        switch (t) {
            case "user", "group" -> { if (blank(email)) throw bad("An email address is needed to share with a " + t, "Fill in email."); perm.put("emailAddress", email.trim()); }
            case "domain" -> { if (blank(domain)) throw bad("A domain is needed to share with a domain", "Fill in domain."); perm.put("domain", domain.trim()); }
            case "anyone" -> { }
            default -> throw bad("share_with must be user, group, domain or anyone", null);
        }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("fields", "id");
        q.put("supportsAllDrives", true);
        if (t.equals("user") || t.equals("group")) {
            q.put("sendNotificationEmail", notify);
            if (notify && !blank(message)) q.put("emailMessage", message);
        }
        Map<String, Object> r = call(ctx, token, "POST", DRIVE + "/" + HttpJson.enc(id) + "/permissions?" + HttpJson.query(q), ep, perm);
        return Out.of("permission_id", r.get("id"));
    }

    @Action(id = "drive_delete_permission", name = "Remove Drive permission", category = "Drive", capabilities = "network",
            description = "Take away a permission from a file or folder.",
            panel = "access_token, item_id, permission_id | endpoint_override")
    public Map<String, Object> driveUnshare(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, description = "File or folder id.") String id,
            @Input(name = "permission_id", type = OoaType.STRING, description = "Permission id from List Drive permissions.") String perm,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        call(ctx, token, "DELETE", DRIVE + "/" + HttpJson.enc(id) + "/permissions/" + HttpJson.enc(perm) + "?supportsAllDrives=true", ep, null);
        return Map.of();
    }

    @Action(id = "drive_copy_folder", name = "Copy Drive folder", category = "Drive", timeoutSeconds = 1800, capabilities = "network",
            description = "Copy a folder with all its files and subfolders into another folder.",
            panel = "access_token, (Pick the folder by: Id = folder_id / Path = folder_path), (Put the copy in: A folder id = destination_id / A folder path = destination_path) | name, endpoint_override")
    @Output(name = "folder_id", type = OoaType.STRING, description = "Id of the new folder.")
    @Output(name = "file_count", type = OoaType.NUMBER, description = "Number of files copied.")
    public Map<String, Object> driveCopyFolder(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Folder to copy; or give folder_path instead.") String id,
            @Input(name = "destination_id", type = OoaType.STRING, required = false, description = "Folder that receives the copy; next to the original when empty.") String dest,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name of the copy; the original name when empty.") String name,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "folder_path", type = OoaType.STRING, required = false, example = "Reports/2026",
                    description = "Path of the folder to copy under My Drive, used instead of folder_id.") String path,
            @Input(name = "destination_path", type = OoaType.STRING, required = false, example = "Archive",
                    description = "Path of the folder that receives the copy under My Drive, used instead of destination_id.") String destPath) {
        if (!blank(path) && !blank(id)) throw bad("Give folder_id or folder_path, not both", null);
        if (blank(path) && blank(id)) throw bad("Give the folder id or folder_path", "folder_path starts at My Drive, e.g. Reports/2026.");
        if (!blank(path)) {
            if (pathNames(path).isEmpty()) throw bad("The path '" + path + "' names no folder", "Example: Reports/2026");
            id = idByPath(ctx, token, path, true, ep);
        }
        dest = folderId(ctx, token, dest, "destination_id", destPath, "destination_path", ep);
        Map<String, Object> src = call(ctx, token, "GET", DRIVE + "/" + HttpJson.enc(id) + "?fields=id,name,mimeType,parents&supportsAllDrives=true", ep, null);
        if (!FOLDER_MIME.equals(src.get("mimeType"))) throw bad("The item is not a folder", "Use Copy Drive file for files.");
        String parent = !blank(dest) ? dest : list(src.get("parents")).isEmpty() ? null : str(list(src.get("parents")).get(0));
        int[] files = {0};
        String copy = copyTree(ctx, token, id, blank(name) ? str(src.get("name")) : name, parent, files, ep);
        return Map.of("folder_id", copy, "file_count", num(files[0]));
    }

    /** Earlier signature, kept for callers written before folder_path and destination_path. */
    public Map<String, Object> driveCopyFolder(ActionContext ctx, Object token, String id, String dest, String name, String ep) {
        return driveCopyFolder(ctx, token, id, dest, name, ep, null, null);
    }

    private String copyTree(ActionContext ctx, Object token, String id, String name, String parent, int[] files, String ep) {
        Map<String, Object> meta = new LinkedHashMap<>(Map.of("name", name, "mimeType", FOLDER_MIME));
        if (parent != null) meta.put("parents", List.of(parent));
        String copy = str(call(ctx, token, "POST", DRIVE + "?fields=id&supportsAllDrives=true", ep, meta).get("id"));
        String page = null;
        do {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("q", quoted(id) + " in parents and trashed = false");
            q.put("fields", "nextPageToken,files(id,name,mimeType)");
            q.put("pageSize", 1000);
            q.put("supportsAllDrives", true);
            q.put("includeItemsFromAllDrives", true);
            if (page != null) q.put("pageToken", page);
            Map<String, Object> r = call(ctx, token, "GET", DRIVE + "?" + HttpJson.query(q), ep, null);
            for (Object o : list(r.get("files"))) {
                Map<String, Object> f = obj(o);
                if (FOLDER_MIME.equals(f.get("mimeType"))) copyTree(ctx, token, str(f.get("id")), str(f.get("name")), copy, files, ep);
                else {
                    call(ctx, token, "POST", DRIVE + "/" + HttpJson.enc(str(f.get("id"))) + "/copy?fields=id&supportsAllDrives=true", ep, Map.of("name", str(f.get("name")), "parents", List.of(copy)));
                    files[0]++;
                }
            }
            page = str(r.get("nextPageToken"));
        } while (page != null);
        return copy;
    }

    private static final List<String> ROLE_ORDER = List.of("reader", "commenter", "writer", "fileOrganizer", "organizer", "owner");

    @Action(id = "drive_check_permission", name = "Check Drive permission", category = "Drive", idempotent = true, capabilities = "network",
            description = "Check whether a person (or their domain, or anyone with the link) can read, comment on or edit a file or folder.",
            panel = "access_token, item_id, access, email | endpoint_override")
    @Output(name = "has_access", type = OoaType.BOOLEAN, description = "True when the person has at least the asked access.")
    @Output(name = "role", type = OoaType.STRING, description = "The highest role found, empty when none.")
    public Map<String, Object> driveCheckPermission(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, description = "File or folder id.") String id,
            @Input(name = "email", type = OoaType.STRING, description = "The person's email address.") String email,
            @Input(name = "access", type = OoaType.STRING, required = false, defaultValue = "\"read\"", enumValues = {"read", "comment", "write"}, description = "The access to check.") String access,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        String need = switch (blank(access) ? "read" : access.trim().toLowerCase(Locale.ROOT)) {
            case "read" -> "reader"; case "comment" -> "commenter"; case "write" -> "writer";
            default -> throw bad("access must be read, comment or write", null);
        };
        String mail = email.trim().toLowerCase(Locale.ROOT), dom = mail.substring(mail.indexOf('@') + 1);
        int best = -1;
        String page = null;
        do {
            Map<String, Object> r = call(ctx, token, "GET", DRIVE + "/" + HttpJson.enc(id) + "/permissions?fields="
                    + HttpJson.enc("nextPageToken,permissions(type,role,emailAddress,domain)") + "&supportsAllDrives=true" + (page == null ? "" : "&pageToken=" + HttpJson.enc(page)), ep, null);
            for (Object o : list(r.get("permissions"))) {
                Map<String, Object> pm = obj(o);
                String t = str(pm.get("type"));
                boolean match = "anyone".equals(t) || ("domain".equals(t) && dom.equalsIgnoreCase(str(pm.get("domain"))))
                        || (("user".equals(t) || "group".equals(t)) && mail.equalsIgnoreCase(Objects.toString(pm.get("emailAddress"), "")));
                if (match) best = Math.max(best, ROLE_ORDER.indexOf(str(pm.get("role"))));
            }
            page = str(r.get("nextPageToken"));
        } while (page != null);
        return Map.of("has_access", best >= ROLE_ORDER.indexOf(need), "role", best < 0 ? "" : ROLE_ORDER.get(best));
    }

    @Action(id = "drive_update_permission", name = "Change Drive permission", category = "Drive", idempotent = true, capabilities = "network",
            description = "Change the role of an existing permission on a file or folder, e.g. from reader to writer.",
            panel = "access_token, item_id, permission_id, role | endpoint_override")
    public Map<String, Object> driveUpdatePermission(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "item_id", type = OoaType.STRING, description = "File or folder id.") String id,
            @Input(name = "permission_id", type = OoaType.STRING, description = "Permission id from List Drive permissions or Share Drive item.") String perm,
            @Input(name = "role", type = OoaType.STRING, enumValues = {"reader", "commenter", "writer", "fileOrganizer", "organizer"}, description = "New access level.") String role,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        call(ctx, token, "PATCH", DRIVE + "/" + HttpJson.enc(id) + "/permissions/" + HttpJson.enc(perm) + "?supportsAllDrives=true", ep, Map.of("role", role));
        return Map.of();
    }

    static String exportMime(String format) {
        return switch (format == null ? "" : format.trim().toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "csv" -> "text/csv";
            case "txt" -> "text/plain";
            case "html" -> "text/html";
            case "odt" -> "application/vnd.oasis.opendocument.text";
            case "ods" -> "application/x-vnd.oasis.opendocument.spreadsheet";
            default -> throw bad("Unsupported export format '" + format + "'", "Use pdf, xlsx, docx, pptx, csv, txt, html, odt or ods.");
        };
    }

    @Action(id = "drive_download_export", name = "Export Google file", category = "Drive", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save a Google Doc, Sheet or Slides file in an office format such as PDF, XLSX or DOCX.",
            panel = "access_token, file_id, local_path, format, overwrite | file_name, endpoint_override")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> driveExport(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "file_id", type = OoaType.STRING, description = "Id of the Google Doc, Sheet or Slides file.") String id,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"pdf\"", enumValues = {"pdf", "xlsx", "docx", "pptx", "csv", "txt", "html", "odt", "ods"}, description = "Output format.") String format,
            @Input(name = "local_path", type = OoaType.FILE, description = "Where to save it; the folder to save into when file_name is given.") String local,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing local file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "file_name", type = OoaType.STRING, required = false, example = "Q4 report",
                    description = "Name for the saved file inside the local_path folder; the format's extension is added when the name has none.") String fileName) {
        String fmt = blank(format) ? "pdf" : format.trim().toLowerCase(Locale.ROOT);
        String mime = exportMime(fmt);
        byte[] data = fetchBytes(ctx, token, DRIVE + "/" + HttpJson.enc(id) + "/export?mimeType=" + HttpJson.enc(mime), ep);
        Path p = ctx.resolvePath(local);
        if (!blank(fileName)) {
            String n = HttpJson.safeName(fileName.trim());
            p = p.resolve(n.matches(".*\\.[A-Za-z0-9]{1,5}") ? n : n + "." + fmt);
        }
        p = freeName(p, overwrite);
        writeFile(p, data);
        return Map.of("path", p.toString());
    }

    /** Earlier signature, kept for callers written before overwrite and file_name. */
    public Map<String, Object> driveExport(ActionContext ctx, Object token, String id, String format, String local, String ep) {
        return driveExport(ctx, token, id, format, local, ep, true, null);
    }

    // ------------------------------------------------------------------ Gmail helpers

    static byte[] b64url(Object data) { return data == null ? new byte[0] : Base64.getUrlDecoder().decode(String.valueOf(data).replace('+', '-').replace('/', '_').trim()); }

    static String hdr(Map<String, Object> payload, String name) {
        for (Object h : list(payload.get("headers"))) { Map<String, Object> m = obj(h); if (name.equalsIgnoreCase(str(m.get("name")))) return str(m.get("value")); }
        return null;
    }

    /** Collects plain text, HTML and attachment parts of a Gmail message payload. */
    static void walk(Map<String, Object> part, StringBuilder text, StringBuilder html, List<Map<String, Object>> atts) {
        String fn = str(part.get("filename"));
        String mime = String.valueOf(part.get("mimeType")).toLowerCase(Locale.ROOT);
        Map<String, Object> body = obj(part.get("body"));
        if (fn != null && !fn.isEmpty()) { atts.add(part); return; }
        if (!list(part.get("parts")).isEmpty()) { for (Object p : list(part.get("parts"))) walk(obj(p), text, html, atts); return; }
        if (mime.equals("text/plain")) text.append(new String(b64url(body.get("data")), StandardCharsets.UTF_8));
        else if (mime.equals("text/html")) html.append(new String(b64url(body.get("data")), StandardCharsets.UTF_8));
    }

    static List<String> splitAddresses(String header) {
        List<String> out = new ArrayList<>();
        if (header == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        int angle = 0;
        for (char c : header.toCharArray()) {
            if (c == '"') q = !q;
            else if (!q && c == '<') angle++;
            else if (!q && c == '>') angle--;
            if (c == ',' && !q && angle <= 0) { if (!cur.toString().isBlank()) out.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    static String addr(String a) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<([^>]*)>").matcher(a);
        return (m.find() ? m.group(1) : a).trim().toLowerCase(Locale.ROOT);
    }

    static String encWord(String s) {
        return s.chars().allMatch(c -> c >= 32 && c < 127) ? s : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String hv(String s) { return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim(); }

    /** Builds an RFC 5322 message. Each attachment is {file name, content type, bytes}; message/rfc822 parts are embedded unencoded. */
    static byte[] buildMime(Map<String, String> headers, String body, boolean html, List<Object[]> attachments) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        StringBuilder h = new StringBuilder();
        headers.forEach((k, v) -> { if (v != null && !v.isBlank()) h.append(k).append(": ").append(k.equals("Subject") ? encWord(hv(v)) : hv(v)).append("\r\n"); });
        h.append("MIME-Version: 1.0\r\n");
        String textPart = "Content-Type: text/" + (html ? "html" : "plain") + "; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "\r\n";
        try {
            if (attachments.isEmpty()) { out.write((h + textPart).getBytes(StandardCharsets.UTF_8)); return out.toByteArray(); }
            String boundary = "ooa" + UUID.randomUUID().toString().replace("-", "");
            h.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
            h.append("--").append(boundary).append("\r\n").append(textPart);
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            for (Object[] a : attachments) {
                String name = encWord(hv((String) a[0]).replace("\"", "'"));
                String type = hv((String) a[1]);
                byte[] data = (byte[]) a[2];
                boolean rfc822 = type.equalsIgnoreCase("message/rfc822");
                out.write(("--" + boundary + "\r\nContent-Type: " + type + "; name=\"" + name + "\"\r\nContent-Disposition: attachment; filename=\"" + name + "\"\r\n"
                        + "Content-Transfer-Encoding: " + (rfc822 ? "8bit" : "base64") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(rfc822 ? data : Base64.getMimeEncoder().encode(data));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(e); }
        return out.toByteArray();
    }

    private static String rawOf(byte[] mime) { return Base64.getUrlEncoder().withoutPadding().encodeToString(mime); }

    private static Map<String, Object> gmailFull(ActionContext ctx, Object token, String id, String ep) {
        return call(ctx, token, "GET", GMAIL + "messages/" + HttpJson.enc(id) + "?format=full", ep, null);
    }

    private static List<Object[]> gmailAttachmentData(ActionContext ctx, Object token, String msgId, List<Map<String, Object>> parts, String ep) {
        List<Object[]> out = new ArrayList<>();
        for (Map<String, Object> p : parts) {
            Map<String, Object> body = obj(p.get("body"));
            byte[] data = body.get("data") != null ? b64url(body.get("data"))
                    : b64url(call(ctx, token, "GET", GMAIL + "messages/" + HttpJson.enc(msgId) + "/attachments/" + HttpJson.enc(str(body.get("attachmentId"))), ep, null).get("data"));
            out.add(new Object[]{str(p.get("filename")), p.get("mimeType") == null ? "application/octet-stream" : str(p.get("mimeType")), data});
        }
        return out;
    }

    private static List<Object[]> localAttachments(ActionContext ctx, List<Object> files) {
        List<Object[]> out = new ArrayList<>();
        if (files == null) return out;
        for (Object f : files) {
            Path p = ctx.resolvePath(Values.text(f));
            try {
                String type = Files.probeContentType(p);
                out.add(new Object[]{p.getFileName().toString(), type == null ? "application/octet-stream" : type, Files.readAllBytes(p)});
            } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Attachment not found: " + p, "Check the attachment path."); }
        }
        return out;
    }

    private static String escapeHtml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** The original message as quoted text (or HTML) under the new body. */
    static String quote(String body, boolean html, String date, String from, String origText, String origHtml) {
        String intro = "On " + Objects.toString(date, "") + ", " + Objects.toString(from, "") + " wrote:";
        if (html) {
            String inner = !origHtml.isEmpty() ? origHtml : escapeHtml(origText).replace("\n", "<br>");
            return body + "<br><br><div>" + escapeHtml(intro) + "</div><blockquote style=\"margin:0 0 0 .8ex;border-left:1px solid #ccc;padding-left:1ex\">" + inner + "</blockquote>";
        }
        String src = !origText.isEmpty() ? origText : origHtml.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "");
        StringBuilder b = new StringBuilder(body).append("\r\n\r\n").append(intro).append("\r\n");
        for (String line : src.replace("\r\n", "\n").split("\n", -1)) b.append(line.isEmpty() ? ">" : "> " + line).append("\r\n");
        return b.toString();
    }

    private static List<String> labelIds(ActionContext ctx, Object token, Object labels, String ep) {
        List<String> wanted = HttpJson.addressList(labels);
        if (wanted.isEmpty()) return wanted;
        List<Object> all = list(call(ctx, token, "GET", GMAIL + "labels", ep, null).get("labels"));
        List<String> out = new ArrayList<>();
        for (String w : wanted) {
            String id = null;
            for (Object o : all) { Map<String, Object> l = obj(o); if (w.equals(l.get("id")) || w.equalsIgnoreCase(str(l.get("name")))) { id = str(l.get("id")); break; } }
            if (id == null) throw new ActionException(P + ".NO_LABEL", "Gmail label '" + w + "' not found", "Check the label name; system labels are INBOX, SPAM, TRASH, UNREAD, STARRED, IMPORTANT.");
            out.add(id);
        }
        return out;
    }

    private static Map<String, Object> modify(ActionContext ctx, Object token, String id, List<String> add, List<String> remove, String ep) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!add.isEmpty()) body.put("addLabelIds", add);
        if (!remove.isEmpty()) body.put("removeLabelIds", remove);
        return call(ctx, token, "POST", GMAIL + "messages/" + HttpJson.enc(id) + "/modify", ep, body);
    }

    private static List<String> searchIds(ActionContext ctx, Object token, String query, int max, boolean spamTrash, String ep) {
        List<String> ids = new ArrayList<>();
        String page = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>();
            if (!blank(query)) params.put("q", query);
            params.put("maxResults", Math.min(500, Math.max(1, max - ids.size())));
            if (spamTrash) params.put("includeSpamTrash", true);
            if (page != null) params.put("pageToken", page);
            Map<String, Object> r = call(ctx, token, "GET", GMAIL + "messages?" + HttpJson.query(params), ep, null);
            for (Object o : list(r.get("messages"))) if (ids.size() < max) ids.add(str(obj(o).get("id")));
            page = str(r.get("nextPageToken"));
        } while (page != null && ids.size() < max);
        return ids;
    }

    private static String term(String v) { return v.contains(" ") || v.contains("\"") ? "\"" + v.replace("\"", "") + "\"" : v; }

    /** A Gmail search from the free query plus the structured filters (dates as seconds since 1970, so times count, not just days). */
    static String gmailQuery(String query, String from, String subject, LocalDateTime since, LocalDateTime before, String status, String label, boolean withAtts) {
        List<String> q = new ArrayList<>();
        if (!blank(query)) q.add(query.trim());
        for (String[] f : new String[][]{{"from", from}, {"subject", subject}}) {
            if (blank(f[1])) continue;
            List<String> any = new ArrayList<>();
            for (String v : f[1].split(";")) if (!v.isBlank()) any.add(f[0] + ":" + term(v.trim()));
            if (!any.isEmpty()) q.add(any.size() == 1 ? any.get(0) : "{" + String.join(" ", any) + "}");
        }
        if (since != null) q.add("after:" + since.toEpochSecond(java.time.ZoneOffset.UTC));
        if (before != null) q.add("before:" + before.toEpochSecond(java.time.ZoneOffset.UTC));
        switch (blank(status) ? "any" : status.trim().toLowerCase(Locale.ROOT)) {
            case "unread" -> q.add("is:unread");
            case "read" -> q.add("is:read");
            case "any" -> { }
            default -> throw bad("status must be any, unread or read", null);
        }
        if (!blank(label)) q.add("label:" + term(label.trim().replace(' ', '-')));
        if (withAtts) q.add("has:attachment");
        return String.join(" ", q);
    }

    // ------------------------------------------------------------------ Gmail actions

    @Action(id = "gmail_list_messages", name = "List Gmail messages", category = "Gmail", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Find Gmail messages with a Gmail search such as from:billing@acme.com is:unread and return them as a table.",
            panel = "access_token, status, label, subject_contains, from_contains, since, before, has_attachments_only | query, max_results, include_spam_trash, endpoint_override")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, thread_id, from, to, subject, date, snippet, unread, labels.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of messages.")
    public Map<String, Object> gmailList(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "from:billing@acme.com has:attachment newer_than:7d", description = "Gmail search text; all messages when empty.") String query,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum messages returned.") int max,
            @Input(name = "include_spam_trash", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also search Spam and Trash.") boolean spamTrash,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains these words; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = "Only unread or only read messages.") String status,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Only messages with this label, e.g. INBOX or Invoices.") String label,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only messages with attachments.") boolean withAtts,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        List<Object> rows = new ArrayList<>();
        for (String id : searchIds(ctx, token, gmailQuery(query, from, subject, since, before, status, label, withAtts), max, spamTrash, ep)) {
            Map<String, Object> m = call(ctx, token, "GET", GMAIL + "messages/" + HttpJson.enc(id)
                    + "?format=metadata&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Subject&metadataHeaders=Date", ep, null);
            Map<String, Object> payload = obj(m.get("payload"));
            List<Object> labels = new ArrayList<>(list(m.get("labelIds")));
            rows.add(new ArrayList<>(Arrays.asList(id, m.get("threadId"), hdr(payload, "From"), hdr(payload, "To"), hdr(payload, "Subject"), hdr(payload, "Date"),
                    m.get("snippet"), labels.contains("UNREAD"), labels)));
        }
        return Map.of("table", Values.table(List.of("id", "thread_id", "from", "to", "subject", "date", "snippet", "unread", "labels"), rows), "count", num(rows.size()));
    }

    @Action(id = "gmail_get_message", name = "Read Gmail message", category = "Gmail", idempotent = true, capabilities = "network",
            description = "Read one Gmail message: headers, text and HTML body, labels and attachment names.",
            panel = "access_token, message_id, mark_read | endpoint_override")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, thread_id, from, to, cc, subject, date, message_id, text, html, attachments, labels.")
    public Map<String, Object> gmailGet(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark the message as read.") boolean markRead,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> m = gmailFull(ctx, token, id, ep);
        Map<String, Object> payload = obj(m.get("payload"));
        StringBuilder text = new StringBuilder(), html = new StringBuilder();
        List<Map<String, Object>> atts = new ArrayList<>();
        walk(payload, text, html, atts);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("thread_id", m.get("threadId"));
        e.put("from", hdr(payload, "From"));
        e.put("to", new ArrayList<Object>(splitAddresses(hdr(payload, "To"))));
        e.put("cc", new ArrayList<Object>(splitAddresses(hdr(payload, "Cc"))));
        e.put("subject", Objects.toString(hdr(payload, "Subject"), ""));
        e.put("date", hdr(payload, "Date"));
        e.put("message_id", hdr(payload, "Message-ID"));
        e.put("text", text.toString());
        e.put("html", html.toString());
        List<Object> names = new ArrayList<>();
        for (Map<String, Object> a : atts) names.add(a.get("filename"));
        e.put("attachments", names);
        e.put("labels", new ArrayList<>(list(m.get("labelIds"))));
        if (markRead) modify(ctx, token, id, List.of(), List.of("UNREAD"), ep);
        return Map.of("email", e);
    }

    @Action(id = "gmail_mark_message", name = "Change Gmail message status", category = "Gmail", idempotent = true, capabilities = "network",
            description = "Mark a Gmail message as read, unread, starred, not starred, important or not important.",
            panel = "access_token, message_id, status | endpoint_override")
    public Map<String, Object> gmailMark(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"read\"",
                    enumValues = {"read", "unread", "starred", "unstarred", "important", "not_important"}, description = "New status.") String status,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        switch (blank(status) ? "read" : status.toLowerCase(Locale.ROOT)) {
            case "read" -> modify(ctx, token, id, List.of(), List.of("UNREAD"), ep);
            case "unread" -> modify(ctx, token, id, List.of("UNREAD"), List.of(), ep);
            case "starred" -> modify(ctx, token, id, List.of("STARRED"), List.of(), ep);
            case "unstarred" -> modify(ctx, token, id, List.of(), List.of("STARRED"), ep);
            case "important" -> modify(ctx, token, id, List.of("IMPORTANT"), List.of(), ep);
            case "not_important" -> modify(ctx, token, id, List.of(), List.of("IMPORTANT"), ep);
            default -> throw bad("Unknown status '" + status + "'", "Use read, unread, starred, unstarred, important or not_important.");
        }
        return Map.of();
    }

    @Action(id = "gmail_move_message", name = "Move Gmail message", category = "Gmail", idempotent = true, capabilities = "network",
            description = "Move a Gmail message between labels, e.g. add Processed and remove INBOX.",
            panel = "access_token, message_id, remove_labels, add_labels, keep_other_labels | endpoint_override")
    @Output(name = "labels", type = OoaType.LIST, description = "Label ids the message has afterwards.")
    public Map<String, Object> gmailMove(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "add_labels", required = false, description = "Label name(s) or id(s) to add: text separated by , or a List.") Object add,
            @Input(name = "remove_labels", required = false, defaultValue = "\"INBOX\"", description = "Label name(s) or id(s) to remove; INBOX by default, which archives the message.") Object remove,
            @Input(name = "keep_other_labels", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "When false, every label except the added ones and the read, starred and important marks is removed.") boolean keep,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        List<String> a = labelIds(ctx, token, add, ep), r = new ArrayList<>(labelIds(ctx, token, remove, ep));
        if (!keep) for (Object l : list(call(ctx, token, "GET", GMAIL + "messages/" + HttpJson.enc(id) + "?format=minimal", ep, null).get("labelIds")))
            if (!a.contains(str(l)) && !STATUS_LABELS.contains(str(l)) && !r.contains(str(l))) r.add(str(l));
        if (a.isEmpty() && r.isEmpty()) throw bad("Give labels to add or remove", null);
        return Map.of("labels", new ArrayList<>(list(modify(ctx, token, id, a, r, ep).get("labelIds"))));
    }

    private static final Set<String> STATUS_LABELS = Set.of("UNREAD", "STARRED", "IMPORTANT", "SENT", "DRAFT");

    @Action(id = "gmail_move_all", name = "Move all matching Gmail messages", category = "Gmail", timeoutSeconds = 600, capabilities = "network",
            description = "Add and remove labels on every Gmail message that matches a search or the filters, e.g. move them from INBOX to Processed.",
            panel = "access_token, remove_labels, add_labels, status, label, subject_contains, from_contains, since, before | query, max_messages, endpoint_override")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of messages changed.")
    public Map<String, Object> gmailMoveAll(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "add_labels", required = false, description = "Label name(s) or id(s) to add.") Object add,
            @Input(name = "remove_labels", required = false, defaultValue = "\"INBOX\"", description = "Label name(s) or id(s) to remove; INBOX by default.") Object remove,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "from:billing@acme.com", description = "Gmail search selecting the messages.") String query,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains these words; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = "Only unread or only read messages.") String status,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Only messages with this label, e.g. INBOX or Invoices.") String label,
            @Input(name = "max_messages", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Upper limit of messages handled in one run.") int max,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        String q = gmailQuery(query, from, subject, since, before, status, label, false);
        if (blank(q)) throw bad("Give a search query or at least one filter", "This keeps a whole mailbox from being changed by accident.");
        List<String> a = labelIds(ctx, token, add, ep), r = labelIds(ctx, token, remove, ep);
        if (a.isEmpty() && r.isEmpty()) throw bad("Give labels to add or remove", null);
        List<String> ids = searchIds(ctx, token, q, max, false, ep);
        for (int i = 0; i < ids.size(); i += 1000) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", ids.subList(i, Math.min(ids.size(), i + 1000)));
            if (!a.isEmpty()) body.put("addLabelIds", a);
            if (!r.isEmpty()) body.put("removeLabelIds", r);
            call(ctx, token, "POST", GMAIL + "messages/batchModify", ep, body);
        }
        return Map.of("count", num(ids.size()));
    }

    @Action(id = "gmail_label_exists", name = "Check Gmail label exists", category = "Gmail", idempotent = true, capabilities = "network",
            description = "Check whether a Gmail label exists, by name (not case-sensitive) or id, and return its id.",
            panel = "access_token, label | endpoint_override")
    @Output(name = "exists", type = OoaType.BOOLEAN, description = "True when the label exists.")
    @Output(name = "label_id", type = OoaType.STRING, description = "Label id, empty when it does not exist.")
    public Map<String, Object> gmailLabelExists(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "label", type = OoaType.STRING, description = "Label name or id.") String label,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        for (Object o : list(call(ctx, token, "GET", GMAIL + "labels", ep, null).get("labels"))) {
            Map<String, Object> l = obj(o);
            if (label.trim().equals(l.get("id")) || label.trim().equalsIgnoreCase(str(l.get("name")))) return Map.of("exists", true, "label_id", str(l.get("id")));
        }
        return Map.of("exists", false, "label_id", "");
    }

    @Action(id = "gmail_delete_message", name = "Delete Gmail message", category = "Gmail", capabilities = "network",
            description = "Move a Gmail message to the trash, or delete it permanently.",
            panel = "access_token, message_id | permanent, endpoint_override")
    public Map<String, Object> gmailDelete(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good instead of moving to the trash (needs full mail access).") boolean permanent,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        if (permanent) call(ctx, token, "DELETE", GMAIL + "messages/" + HttpJson.enc(id), ep, null);
        else call(ctx, token, "POST", GMAIL + "messages/" + HttpJson.enc(id) + "/trash", ep, Map.of());
        return Map.of();
    }

    @Action(id = "gmail_delete_all", name = "Delete all matching Gmail messages", category = "Gmail", timeoutSeconds = 600, capabilities = "network",
            description = "Trash, or permanently delete, every Gmail message that matches a search.",
            panel = "access_token, status, label, subject_contains, from_contains, since, before | query, permanent, max_messages, endpoint_override")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of messages deleted.")
    public Map<String, Object> gmailDeleteAll(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "from:alerts@acme.com older_than:30d", description = "Gmail search selecting the messages; a search or at least one filter is required so a mailbox is never emptied by accident.") String query,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good instead of moving to the trash.") boolean permanent,
            @Input(name = "max_messages", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Upper limit of messages handled in one run.") int max,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains these words; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = "Only unread or only read messages.") String status,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Only messages with this label, e.g. INBOX or Invoices.") String label,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        String q = gmailQuery(query, from, subject, since, before, status, label, false);
        if (blank(q)) throw bad("A search query is required", "Give a Gmail search such as from:x@y.com older_than:30d, or fill in a filter.");
        List<String> ids = searchIds(ctx, token, q, max, false, ep);
        if (permanent) {
            for (int i = 0; i < ids.size(); i += 1000)
                call(ctx, token, "POST", GMAIL + "messages/batchDelete", ep, Map.of("ids", ids.subList(i, Math.min(ids.size(), i + 1000))));
        } else for (String id : ids) call(ctx, token, "POST", GMAIL + "messages/" + HttpJson.enc(id) + "/trash", ep, Map.of());
        return Map.of("count", num(ids.size()));
    }

    @Action(id = "gmail_forward_message", name = "Forward Gmail message", category = "Gmail", capabilities = "network",
            description = "Forward a Gmail message with a note, either inline with its attachments or as an attached .eml file.",
            panel = "access_token, message_id, to, cc, bcc, attachments, [Add a note above the message: note, html] | as_attachment, include_attachments, endpoint_override")
    @Output(name = "message_id", type = OoaType.STRING, description = "Gmail id of the sent message.")
    public Map<String, Object> gmailForward(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "note", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Text placed above the forwarded message.") String note,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Note is HTML.") boolean html,
            @Input(name = "as_attachment", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Attach the original as an .eml file instead of quoting it.") boolean asAttachment,
            @Input(name = "include_attachments", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Inline mode: also forward the original attachments.") boolean withAtts,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "More file paths to attach.") List<Object> files,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        List<String> rcpt = HttpJson.addressList(to);
        if (rcpt.isEmpty()) throw new ActionException(P + ".NO_RECIPIENT", "Give at least one recipient", "Fill in to.");
        Map<String, Object> m = gmailFull(ctx, token, id, ep);
        Map<String, Object> payload = obj(m.get("payload"));
        String subject = Objects.toString(hdr(payload, "Subject"), "");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("To", String.join(", ", rcpt));
        headers.put("Cc", String.join(", ", HttpJson.addressList(cc)));
        headers.put("Bcc", String.join(", ", HttpJson.addressList(bcc)));
        headers.put("Subject", subject.matches("(?i)^(fwd?|fw):.*") ? subject : "Fwd: " + subject);
        String body = note == null ? "" : note;
        List<Object[]> atts = new ArrayList<>();
        if (asAttachment) {
            byte[] raw = b64url(call(ctx, token, "GET", GMAIL + "messages/" + HttpJson.enc(id) + "?format=raw", ep, null).get("raw"));
            atts.add(new Object[]{(subject.isBlank() ? "message" : subject.replaceAll("[\\\\/:*?\"<>|]", "_")) + ".eml", "message/rfc822", raw});
        } else {
            StringBuilder text = new StringBuilder(), htm = new StringBuilder();
            List<Map<String, Object>> parts = new ArrayList<>();
            walk(payload, text, htm, parts);
            String[][] info = {{"From", hdr(payload, "From")}, {"Date", hdr(payload, "Date")}, {"Subject", subject}, {"To", hdr(payload, "To")}, {"Cc", hdr(payload, "Cc")}};
            if (html) {
                StringBuilder b = new StringBuilder(body).append("<br><br><div>---------- Forwarded message ---------<br>");
                for (String[] i : info) if (!blank(i[1])) b.append(i[0]).append(": ").append(escapeHtml(i[1])).append("<br>");
                b.append("</div><br>").append(htm.length() > 0 ? htm : escapeHtml(text.toString()).replace("\n", "<br>"));
                body = b.toString();
            } else {
                StringBuilder b = new StringBuilder(body).append("\r\n\r\n---------- Forwarded message ---------\r\n");
                for (String[] i : info) if (!blank(i[1])) b.append(i[0]).append(": ").append(i[1]).append("\r\n");
                b.append("\r\n").append(text.length() > 0 ? text : htm.toString().replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", ""));
                body = b.toString();
            }
            if (withAtts) atts.addAll(gmailAttachmentData(ctx, token, id, parts, ep));
        }
        atts.addAll(localAttachments(ctx, files));
        Map<String, Object> r = call(ctx, token, "POST", GMAIL + "messages/send", ep, Map.of("raw", rawOf(buildMime(headers, body, html, atts))));
        return Out.of("message_id", r.get("id"));
    }

    private Map<String, Object> gmailReplyImpl(ActionContext ctx, Object token, String id, String body, boolean html, boolean all, boolean includeOriginal,
                                                List<Object> attachments, Object extraCc, Object bcc, boolean excludeSender, String newSubject, String ep) {
        Map<String, Object> m = gmailFull(ctx, token, id, ep);
        Map<String, Object> payload = obj(m.get("payload"));
        String from = hdr(payload, "From"), replyTo = hdr(payload, "Reply-To"), msgId = hdr(payload, "Message-ID"), refs = hdr(payload, "References");
        String subject = Objects.toString(hdr(payload, "Subject"), "");
        Map<String, String> to = new LinkedHashMap<>(), cc = new LinkedHashMap<>();
        for (String a : splitAddresses(replyTo != null ? replyTo : from)) to.putIfAbsent(addr(a), a);
        if (all) {
            String me = addr(Objects.toString(call(ctx, token, "GET", GMAIL + "profile", ep, null).get("emailAddress"), ""));
            for (String a : splitAddresses(hdr(payload, "To"))) to.putIfAbsent(addr(a), a);
            for (String a : splitAddresses(hdr(payload, "Cc"))) if (!to.containsKey(addr(a))) cc.putIfAbsent(addr(a), a);
            if (to.size() > 1) to.remove(me);
            cc.remove(me);
            if (excludeSender) for (String a : splitAddresses(from)) { to.remove(addr(a)); cc.remove(addr(a)); }
            if (to.isEmpty() && !cc.isEmpty()) { to.putAll(cc); cc.clear(); }
        }
        for (String a : HttpJson.addressList(extraCc)) if (!to.containsKey(addr(a))) cc.putIfAbsent(addr(a), a);
        if (to.isEmpty()) throw new ActionException(P + ".NO_RECIPIENT", "The original message has no sender to reply to", null);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("To", String.join(", ", to.values()));
        headers.put("Cc", String.join(", ", cc.values()));
        headers.put("Bcc", String.join(", ", HttpJson.addressList(bcc)));
        headers.put("Subject", !blank(newSubject) ? newSubject : subject.matches("(?i)^re:.*") ? subject : "Re: " + subject);
        if (msgId != null) {
            headers.put("In-Reply-To", msgId);
            headers.put("References", blank(refs) ? msgId : refs.trim() + " " + msgId);
        }
        String text = body == null ? "" : body;
        if (includeOriginal) {
            StringBuilder t = new StringBuilder(), h = new StringBuilder();
            walk(payload, t, h, new ArrayList<>());
            text = quote(text, html, hdr(payload, "Date"), from, t.toString(), h.toString());
        }
        Map<String, Object> send = new LinkedHashMap<>();
        send.put("raw", rawOf(buildMime(headers, text, html, localAttachments(ctx, attachments))));
        if (m.get("threadId") != null) send.put("threadId", m.get("threadId"));
        Map<String, Object> r = call(ctx, token, "POST", GMAIL + "messages/send", ep, send);
        return Out.of("message_id", r.get("id"), "thread_id", r.get("threadId"));
    }

    @Action(id = "gmail_reply_message", name = "Reply to Gmail message", category = "Gmail", capabilities = "network",
            description = "Reply to the sender of a Gmail message in the same conversation thread.",
            panel = "access_token, message_id, cc, bcc, attachments, body, html | include_original, endpoint_override")
    @Output(name = "message_id", type = OoaType.STRING, description = "Gmail id of the reply.")
    @Output(name = "thread_id", type = OoaType.STRING, description = "Conversation thread id.")
    public Map<String, Object> gmailReply(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "include_original", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Quote the original message below the reply.") boolean include,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "cc", required = false, description = "More CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        return gmailReplyImpl(ctx, token, id, body, html, false, include, attachments, cc, bcc, false, null, ep);
    }

    @Action(id = "gmail_reply_all", name = "Reply all to Gmail message", category = "Gmail", capabilities = "network",
            description = "Reply to the sender and every other recipient of a Gmail message in the same thread.",
            panel = "access_token, message_id, cc, bcc, exclude_sender, subject, attachments, body, html | include_original, endpoint_override")
    @Output(name = "message_id", type = OoaType.STRING, description = "Gmail id of the reply.")
    @Output(name = "thread_id", type = OoaType.STRING, description = "Conversation thread id.")
    public Map<String, Object> gmailReplyAll(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "include_original", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Quote the original message below the reply.") boolean include,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "cc", required = false, description = "More CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "exclude_sender", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave the original sender out and reply to the other recipients.") boolean excludeSender,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = "New subject; Re: and the original subject when empty.") String newSubject,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        return gmailReplyImpl(ctx, token, id, body, html, true, include, attachments, cc, bcc, excludeSender, newSubject, ep);
    }

    @Action(id = "gmail_save_attachments", name = "Save Gmail attachments", category = "Gmail", capabilities = {"network", "filesystem"},
            description = "Save a Gmail message's attachments to a folder, optionally only names matching a pattern such as *.pdf.",
            panel = "access_token, message_id, target_folder, overwrite | name_pattern, endpoint_override")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    public Map<String, Object> gmailSaveAttachments(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        return Map.of("files", saveGmailAtts(ctx, token, id, ctx.resolvePath(target), pattern, overwrite, ep));
    }

    private static Path freeName(Path out, boolean overwrite) {
        if (overwrite) return out;
        String name = out.getFileName().toString();
        for (int i = 2; Files.exists(out); i++) out = out.resolveSibling(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
        return out;
    }

    private static List<Object> saveGmailAtts(ActionContext ctx, Object token, String id, Path dir, String pattern, boolean overwrite, String ep) {
        Map<String, Object> m = gmailFull(ctx, token, id, ep);
        List<Map<String, Object>> parts = new ArrayList<>();
        walk(obj(m.get("payload")), new StringBuilder(), new StringBuilder(), parts);
        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + (blank(pattern) ? "*" : pattern).toLowerCase(Locale.ROOT));
        List<Map<String, Object>> chosen = new ArrayList<>();
        for (Map<String, Object> p : parts) if (pm.matches(Path.of(HttpJson.safeName(str(p.get("filename"))).toLowerCase(Locale.ROOT)))) chosen.add(p);
        List<Object> saved = new ArrayList<>();
        for (Object[] a : gmailAttachmentData(ctx, token, id, chosen, ep)) {
            Path out = freeName(dir.resolve(HttpJson.safeName((String) a[0])), overwrite);
            writeFile(out, (byte[]) a[2]);
            saved.add(out.toString());
        }
        return saved;
    }

    @Action(id = "gmail_save_all_attachments", name = "Save attachments of all matching Gmail messages", category = "Gmail", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save the attachments of every Gmail message that matches a search or the filters, optionally marking them as read.",
            panel = "access_token, status, label, subject_contains, from_contains, since, before, target_folder, overwrite | name_pattern, query, mark_read, max_messages, endpoint_override")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    @Output(name = "message_count", type = OoaType.NUMBER, description = "Number of messages that matched.")
    public Map<String, Object> gmailSaveAllAttachments(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "from:billing@acme.com", description = "Gmail search selecting the messages; all messages with attachments when empty.") String query,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains these words; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = "Only unread or only read messages.") String status,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Only messages with this label, e.g. INBOX or Invoices.") String label,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark each matching message as read afterwards.") boolean markRead,
            @Input(name = "max_messages", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Upper limit of messages handled in one run.") int max,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Path dir = ctx.resolvePath(target);
        List<String> ids = searchIds(ctx, token, gmailQuery(query, from, subject, since, before, status, label, true), max, false, ep);
        List<Object> saved = new ArrayList<>();
        for (String id : ids) {
            saved.addAll(saveGmailAtts(ctx, token, id, dir, pattern, overwrite, ep));
            if (markRead) modify(ctx, token, id, List.of(), List.of("UNREAD"), ep);
        }
        return Map.of("files", saved, "message_count", num(ids.size()));
    }

    @Action(id = "gmail_save_message_as_eml", name = "Save Gmail message as .eml", category = "Gmail", capabilities = {"network", "filesystem"},
            description = "Save a complete Gmail message, with attachments, as an .eml file that mail programs can open.",
            panel = "access_token, message_id, local_path, overwrite | endpoint_override")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> gmailSaveEml(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "message_id", type = OoaType.STRING, description = D_MSG) String id,
            @Input(name = "local_path", type = OoaType.FILE, example = "mail/message.eml", description = "Where to save the .eml file; a path ending in / or an existing folder gets a file named after the subject.") String local,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> r = call(ctx, token, "GET", GMAIL + "messages/" + HttpJson.enc(id) + "?format=raw", ep, null);
        if (r.get("raw") == null) throw new ActionException(P + ".NO_CONTENT", "Gmail returned no raw content for message " + id, null);
        Path p = ctx.resolvePath(local);
        if (local.endsWith("/") || local.endsWith("\\") || Files.isDirectory(p)) {
            String subj = Objects.toString(hdr(obj(call(ctx, token, "GET", GMAIL + "messages/" + HttpJson.enc(id) + "?format=metadata&metadataHeaders=Subject", ep, null).get("payload")), "Subject"), "");
            p = p.resolve(HttpJson.safeName((subj.isBlank() ? "email" : subj.replaceAll("[/\\\\]", "_")) + ".eml"));
        }
        p = freeName(p, overwrite);
        writeFile(p, b64url(r.get("raw")));
        return Map.of("path", p.toString());
    }

    // ------------------------------------------------------------------ Calendar

    private static java.time.ZoneId zone(String tz) {
        try { return java.time.ZoneId.of(blank(tz) ? "UTC" : tz.trim()); }
        catch (java.time.DateTimeException e) { throw bad("Unknown time zone '" + tz + "'", "Use an IANA name such as Europe/Berlin or UTC."); }
    }

    private static LocalDateTime eventTime(Map<String, Object> t, java.time.ZoneId z) {
        if (t.get("dateTime") != null) return java.time.OffsetDateTime.parse(str(t.get("dateTime"))).atZoneSameInstant(z).toLocalDateTime();
        if (t.get("date") != null) return java.time.LocalDate.parse(str(t.get("date"))).atStartOfDay();
        return null;
    }

    @Action(id = "calendar_list_events", name = "List Google Calendar events", category = "Calendar", idempotent = true, capabilities = "network",
            description = "List the events of a calendar between two times, earliest first, with recurring events expanded.",
            panel = "access_token, start, end, search | time_zone, max_results, calendar_id, endpoint_override")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, subject, start, end, all_day, location, organizer, attendees, status, link.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of events.")
    public Map<String, Object> listEvents(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "start", type = OoaType.DATETIME, description = "Events ending after this time.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "Events starting before this time.") LocalDateTime end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "IANA time zone of start, end and the returned times.") String tz,
            @Input(name = "search", type = OoaType.STRING, required = false, description = "Only events containing this text.") String search,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "250", description = "Maximum events returned.") int max,
            @Input(name = "calendar_id", type = OoaType.STRING, required = false, defaultValue = "\"primary\"", description = "Calendar id.") String cal,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        java.time.ZoneId z = zone(tz);
        if (!end.isAfter(start)) throw bad("end must be after start", null);
        List<Object> rows = new ArrayList<>();
        String page = null;
        do {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("timeMin", start.atZone(z).toOffsetDateTime().toString());
            q.put("timeMax", end.atZone(z).toOffsetDateTime().toString());
            q.put("singleEvents", true);
            q.put("orderBy", "startTime");
            q.put("timeZone", z.getId());
            q.put("maxResults", Math.min(2500, Math.max(1, max - rows.size())));
            if (!blank(search)) q.put("q", search);
            if (page != null) q.put("pageToken", page);
            Map<String, Object> r = call(ctx, token, "GET", CAL + HttpJson.enc(blank(cal) ? "primary" : cal) + "/events?" + HttpJson.query(q), ep, null);
            for (Object o : list(r.get("items"))) {
                if (rows.size() >= max) break;
                Map<String, Object> e = obj(o);
                List<Object> att = new ArrayList<>();
                for (Object a : list(e.get("attendees"))) att.add(obj(a).get("email"));
                rows.add(new ArrayList<>(Arrays.asList(e.get("id"), e.get("summary"), eventTime(obj(e.get("start")), z), eventTime(obj(e.get("end")), z),
                        obj(e.get("start")).get("date") != null, e.get("location"), obj(e.get("organizer")).get("email"), att, e.get("status"), e.get("htmlLink"))));
            }
            page = str(r.get("nextPageToken"));
        } while (page != null && rows.size() < max);
        return Map.of("table", Values.table(List.of("id", "subject", "start", "end", "all_day", "location", "organizer", "attendees", "status", "link"), rows), "count", num(rows.size()));
    }

    @Action(id = "calendar_update_event", name = "Update Google Calendar event", category = "Calendar", idempotent = true, capabilities = "network",
            description = "Change the title, time, description, location or attendees of an event; empty inputs stay as they are.",
            panel = "access_token, event_id, subject, location, attendees, start, end, time_zone, description | notify, calendar_id, visibility, reminder_minutes, endpoint_override")
    @Output(name = "link", type = OoaType.STRING, description = "Link to the event.")
    public Map<String, Object> updateEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = "Event id.") String id,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = "New title.") String subject,
            @Input(name = "start", type = OoaType.DATETIME, required = false, description = "New start.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, required = false, description = "New end.") LocalDateTime end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "IANA time zone of start and end.") String tz,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "New description.") String description,
            @Input(name = "location", type = OoaType.STRING, required = false, description = "New location.") String location,
            @Input(name = "attendees", required = false, description = "Replaces the attendee list: address(es) as text or a List.") Object attendees,
            @Input(name = "notify", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Email the attendees about the change.") boolean notify,
            @Input(name = "calendar_id", type = OoaType.STRING, required = false, defaultValue = "\"primary\"", description = "Calendar id.") String cal,
            @Input(name = "visibility", type = OoaType.STRING, required = false, enumValues = {"default", "public", "private"}, description = "Who can see the event's details; unchanged when empty.") String visibility,
            @Input(name = "reminder_minutes", type = OoaType.NUMBER, required = false, description = "Popup reminder this many minutes before; unchanged when empty.") Integer reminder,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        String z = zone(tz).getId();
        Map<String, Object> ev = new LinkedHashMap<>();
        if (!blank(visibility)) ev.put("visibility", visibility.trim().toLowerCase(Locale.ROOT));
        if (reminder != null) ev.put("reminders", Map.of("useDefault", false, "overrides", List.of(Map.of("method", "popup", "minutes", Math.max(0, reminder)))));
        if (!blank(subject)) ev.put("summary", subject);
        if (start != null) ev.put("start", Map.of("dateTime", start.toString(), "timeZone", z));
        if (end != null) ev.put("end", Map.of("dateTime", end.toString(), "timeZone", z));
        if (description != null) ev.put("description", description);
        if (location != null) ev.put("location", location);
        if (attendees != null) { List<Object> at = new ArrayList<>(); for (String a : HttpJson.addressList(attendees)) at.add(Map.of("email", a)); ev.put("attendees", at); }
        if (ev.isEmpty()) throw bad("Nothing to change", "Fill in at least one of subject, start, end, description, location or attendees.");
        Map<String, Object> r = call(ctx, token, "PATCH", CAL + HttpJson.enc(blank(cal) ? "primary" : cal) + "/events/" + HttpJson.enc(id)
                + "?sendUpdates=" + (notify ? "all" : "none"), ep, ev);
        return Out.of("link", r.get("htmlLink"));
    }

    @Action(id = "calendar_delete_event", name = "Delete Google Calendar event", category = "Calendar", capabilities = "network",
            description = "Delete an event from a calendar, optionally telling the attendees.",
            panel = "access_token, event_id | notify, calendar_id, endpoint_override")
    public Map<String, Object> deleteEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "event_id", type = OoaType.STRING, description = "Event id.") String id,
            @Input(name = "notify", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Email the attendees that the event is cancelled.") boolean notify,
            @Input(name = "calendar_id", type = OoaType.STRING, required = false, defaultValue = "\"primary\"", description = "Calendar id.") String cal,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        call(ctx, token, "DELETE", CAL + HttpJson.enc(blank(cal) ? "primary" : cal) + "/events/" + HttpJson.enc(id) + "?sendUpdates=" + (notify ? "all" : "none"), ep, null);
        return Map.of();
    }

}
