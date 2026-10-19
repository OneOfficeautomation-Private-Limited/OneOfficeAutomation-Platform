package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
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

    @Action(id = "sheets_read", name = "Read sheet range", category = "Sheets", idempotent = true, capabilities = "network", description = "Read a range such as Sheet1!A1:D100 into a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Range content.")
    public Map<String, Object> sheetsRead(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = "Id from the sheet's URL.") String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1:D100", description = "A1 range.") String range,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
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

    @Action(id = "sheets_write", name = "Write to sheet", category = "Sheets", capabilities = "network", description = "Write a table into a sheet starting at a cell, overwriting existing values.")
    @Output(name = "updated_cells", type = OoaType.NUMBER, description = "Cells written.")
    public Map<String, Object> sheetsWrite(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = "Spreadsheet id.") String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1", description = "Top-left cell or range.") String range,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to write.") Map<String, Object> table,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write column names first.") boolean header,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> r = HttpJson.req(P, "PUT", url("https://sheets.googleapis.com/v4/spreadsheets/" + HttpJson.enc(id) + "/values/" + HttpJson.enc(range)
                + "?valueInputOption=USER_ENTERED", ep)).bearer(token).json(Map.of("range", range, "values", grid(table, header))).send(ctx).map();
        return Out.of("updated_cells", r.get("updatedCells"));
    }

    @Action(id = "sheets_append", name = "Append rows to sheet", category = "Sheets", capabilities = "network", description = "Add a table's rows after the last row of a sheet.")
    @Output(name = "updated_range", type = OoaType.STRING, description = "Range that received the rows.")
    public Map<String, Object> sheetsAppend(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "spreadsheet_id", type = OoaType.STRING, description = "Spreadsheet id.") String id,
            @Input(name = "range", type = OoaType.STRING, example = "Sheet1!A1", description = "Sheet (and table start) to append to.") String range,
            @Input(name = "table", type = OoaType.TABLE, description = "Rows to append (column names are not written).") Map<String, Object> table,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://sheets.googleapis.com/v4/spreadsheets/" + HttpJson.enc(id) + "/values/" + HttpJson.enc(range)
                + ":append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS", ep)).bearer(token).json(Map.of("values", grid(table, false))).send(ctx).map();
        return Out.of("updated_range", r.get("updates") instanceof Map<?, ?> u ? u.get("updatedRange") : null);
    }

    @Action(id = "drive_list", name = "List Drive files", category = "Drive", idempotent = true, capabilities = "network", description = "List files in a Drive folder (or matching a Drive search query).")
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

    @Action(id = "drive_upload", name = "Upload to Drive", category = "Drive", timeoutSeconds = 600, capabilities = {"network", "filesystem"}, description = "Upload a local file to a Drive folder.")
    @Output(name = "file_id", type = OoaType.STRING, description = "New file id.")
    public Map<String, Object> driveUpload(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "local_path", type = OoaType.FILE, description = "File to upload.") String local,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, description = "Target folder id; My Drive root when empty.") String folder,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name in Drive; the local name when empty.") String name,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Path p = ctx.resolvePath(local);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name == null || name.isBlank() ? p.getFileName().toString() : name);
        if (folder != null && !folder.isBlank()) meta.put("parents", List.of(folder));
        Object[] mp = HttpJson.multipart("related", List.of(
                new Object[]{"Content-Type: application/json; charset=UTF-8", JsonLite.write(meta).getBytes(StandardCharsets.UTF_8)},
                new Object[]{"Content-Type: application/octet-stream", data}));
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", ep))
                .bearer(token).bytes((byte[]) mp[0], (String) mp[1]).timeout(600).send(ctx).map();
        return Out.of("file_id", r.get("id"));
    }

    @Action(id = "drive_download", name = "Download from Drive", category = "Drive", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Download a Drive file by id (Google Docs/Sheets are exported to PDF/XLSX automatically).")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> driveDownload(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "file_id", type = OoaType.STRING, description = "File id.") String id,
            @Input(name = "local_path", type = OoaType.FILE, description = "Where to save it.") String local,
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
        Path p = ctx.resolvePath(local);
        try { if (p.getParent() != null) Files.createDirectories(p.getParent()); Files.write(p, data); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    @Action(id = "calendar_create_event", name = "Create Google Calendar event", category = "Calendar", capabilities = "network", description = "Create an event, optionally inviting attendees.")
    @Output(name = "event_id", type = OoaType.STRING, description = "Event id.")
    @Output(name = "link", type = OoaType.STRING, description = "Link to the event.")
    public Map<String, Object> createEvent(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "subject", type = OoaType.STRING, description = "Title.") String subject,
            @Input(name = "start", type = OoaType.DATETIME, description = "Start.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "End.") LocalDateTime end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "IANA time zone of start and end.") String tz,
            @Input(name = "attendees", required = false, description = "Attendee address(es).") Object attendees,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "calendar_id", type = OoaType.STRING, required = false, defaultValue = "\"primary\"", description = "Calendar id.") String cal,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("summary", subject);
        ev.put("start", Map.of("dateTime", start.toString(), "timeZone", tz));
        ev.put("end", Map.of("dateTime", end.toString(), "timeZone", tz));
        if (description != null) ev.put("description", description);
        if (attendees != null) { List<Object> at = new ArrayList<>(); for (String a : HttpJson.addressList(attendees)) at.add(Map.of("email", a)); ev.put("attendees", at); }
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://www.googleapis.com/calendar/v3/calendars/" + HttpJson.enc(cal) + "/events", ep)).bearer(token).json(ev).send(ctx).map();
        return Out.of("event_id", r.get("id"), "link", r.get("htmlLink"));
    }

    @Action(id = "gmail_send", name = "Send Gmail", category = "Gmail", capabilities = "network",
            description = "Send a plain-text or HTML email from Gmail (for attachments use Email > Send with an OAuth token).")
    @Output(name = "message_id", type = OoaType.STRING, description = "Gmail message id.")
    public Map<String, Object> gmailSend(ActionContext ctx,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "endpoint_override", type = OoaType.STRING, required = false, description = D_EP) String ep) {
        List<String> rcpt = HttpJson.addressList(to);
        for (String a : rcpt) if (a.contains("\r") || a.contains("\n")) throw new ActionException(P + ".BAD_ADDRESS", "Invalid address");
        if (subject.contains("\r") || subject.contains("\n")) throw new ActionException(P + ".BAD_SUBJECT", "The subject cannot contain line breaks");
        String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
        String mime = "To: " + String.join(", ", rcpt) + "\r\nSubject: " + encodedSubject + "\r\nMIME-Version: 1.0\r\nContent-Type: text/" + (html ? "html" : "plain")
                + "; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> r = HttpJson.req(P, "POST", url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", ep)).bearer(token).json(Map.of("raw", raw)).send(ctx).map();
        return Out.of("message_id", r.get("id"));
    }

}
