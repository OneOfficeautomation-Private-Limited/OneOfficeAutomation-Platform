package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.Microsoft365OptionsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Options added to the Google Workspace toolkit after comparing it action by action with other RPA products, against a fake Google server. */
class GoogleOptionsTest {

    static Map<String, Object> g(Fake f, Path w, String action, Map<String, Object> args, String... outs) {
        return act("ooa.google", "access_token", "endpoint_override", f.url(), w, action, args, outs);
    }

    static String raw(Call c) { return new String(Base64.getUrlDecoder().decode(String.valueOf(c.json().get("raw"))), StandardCharsets.UTF_8); }
    static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    static final String MSG = "{\"id\":\"G1\",\"threadId\":\"T1\",\"payload\":{\"mimeType\":\"multipart/mixed\",\"headers\":["
            + "{\"name\":\"From\",\"value\":\"Ann <ann@acme.com>\"},{\"name\":\"To\",\"value\":\"me@corp.com, bob@corp.com\"},{\"name\":\"Cc\",\"value\":\"carol@corp.com\"},"
            + "{\"name\":\"Subject\",\"value\":\"Invoice 7\"},{\"name\":\"Message-ID\",\"value\":\"<m1@acme.com>\"}],\"parts\":["
            + "{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"" + Base64.getUrlEncoder().encodeToString("Hello".getBytes()) + "\"}},"
            + "{\"mimeType\":\"application/pdf\",\"filename\":\"INV-7.pdf\",\"body\":{\"data\":\"" + Base64.getUrlEncoder().encodeToString("%PDF".getBytes()) + "\"}}]}}";

    @Test void gmailQueryCombinesFreeTextAndFilters() {
        assertEquals("", GooglePackage.gmailQuery(null, null, " ", null, null, "any", null, false));
        LocalDateTime d = LocalDateTime.of(2026, 10, 1, 0, 0);
        assertEquals("older_than:1y {from:acme.com from:globex.com} subject:\"Invoice 7\" after:" + d.toEpochSecond(java.time.ZoneOffset.UTC) + " is:unread label:My-Label has:attachment",
                GooglePackage.gmailQuery("older_than:1y", "acme.com;globex.com", "Invoice 7", d, null, "unread", "My Label", true));
        assertEquals("GOOGLE.BAD_INPUT", assertThrows(ActionException.class, () -> GooglePackage.gmailQuery(null, null, null, null, null, "starred", null, false)).code());
        assertEquals("RRULE:FREQ=WEEKLY", GooglePackage.rrule("weekly"));
        assertEquals("RRULE:FREQ=DAILY;COUNT=3", GooglePackage.rrule("FREQ=DAILY;COUNT=3"));
        assertNull(GooglePackage.rrule("none"));
        assertEquals("GOOGLE.BAD_INPUT", assertThrows(ActionException.class, () -> GooglePackage.rrule("fortnightly")).code());
    }

    @Test void gmailSendListMoveDeleteForwardReplyAndAttachments() throws Exception {
        try (Fake f = new Fake()) {
            String gm = "/gmail/v1/users/me/";
            f.on("POST " + gm + "messages/send", c -> ok("{\"id\":\"S1\",\"threadId\":\"T1\"}"))
             .on("GET " + gm + "messages", c -> ok("{\"messages\":[{\"id\":\"G1\"},{\"id\":\"G2\"}]}"))
             .on("GET " + gm + "messages/G[12]/attachments/.*", c -> ok("{}"))
             .on("GET " + gm + "messages/G[12]", c -> ok(MSG))
             .on("POST " + gm + "messages/batchModify", c -> new Object[]{204, ""})
             .on("POST " + gm + "messages/.*/modify", c -> ok("{\"labelIds\":[]}"))
             .on("POST " + gm + "messages/.*/trash", c -> ok("{}"))
             .on("GET " + gm + "labels", c -> ok("{\"labels\":[{\"id\":\"INBOX\",\"name\":\"INBOX\"},{\"id\":\"Label_9\",\"name\":\"Processed\"}]}"))
             .on("GET " + gm + "profile", c -> ok("{\"emailAddress\":\"me@corp.com\"}"));
            Path w = tmp();
            Files.writeString(w.resolve("report.csv"), "a,b");

            g(f, w, "gmail_send", Map.of("to", "a@x.com", "subject", "Report", "body", "See file", "cc", "c@x.com", "bcc", "b@x.com",
                    "attachments", List.of("report.csv"), "from", "ap@corp.com", "reply_to", "desk@corp.com"), "message_id");
            String mime = raw(f.last("POST", gm + "messages/send"));
            assertTrue(mime.contains("From: ap@corp.com\r\n") && mime.contains("Cc: c@x.com\r\n") && mime.contains("Bcc: b@x.com\r\n") && mime.contains("Reply-To: desk@corp.com\r\n"), mime);
            assertTrue(mime.contains("multipart/mixed") && mime.contains("filename=\"report.csv\""), mime);
            g(f, w, "gmail_send", Map.of("to", "a@x.com", "subject", "Hi", "body", "x"));
            assertFalse(raw(f.last("POST", gm + "messages/send")).contains("multipart"), "plain email unchanged");

            g(f, w, "gmail_list_messages", Map.of("from_contains", "acme", "status", "unread", "has_attachments_only", true), "count");
            assertEquals("from:acme is:unread has:attachment", q(f.last("GET", gm + "messages")).replaceAll(".*q=([^&]*).*", "$1"));

            Map<String, Object> o = g(f, w, "gmail_move_all", Map.of("add_labels", "Processed", "subject_contains", "invoice"), "count");
            assertEquals(BigDecimal.valueOf(2), o.get("count"));
            Map<String, Object> bm = f.last("POST", gm + "messages/batchModify").json();
            assertEquals(List.of("G1", "G2"), bm.get("ids"));
            assertEquals(List.of("Label_9"), bm.get("addLabelIds"));
            assertEquals(List.of("INBOX"), bm.get("removeLabelIds"));
            assertEquals("GOOGLE.BAD_INPUT", err("ooa.google", "access_token", "endpoint_override", f.url(), "gmail_move_all", Map.of("add_labels", "Processed")).get("code"), "no search, no filter");

            o = g(f, w, "gmail_label_exists", Map.of("label", "processed"), "exists", "label_id");
            assertEquals(true, o.get("exists"));
            assertEquals("Label_9", o.get("label_id"));

            o = g(f, w, "gmail_delete_all", Map.of("from_contains", "alerts@acme.com", "before", "2026-01-01T00:00:00"), "count");
            assertEquals(BigDecimal.valueOf(2), o.get("count"), "filters instead of a query");
            assertEquals("GOOGLE.BAD_INPUT", err("ooa.google", "access_token", "endpoint_override", f.url(), "gmail_delete_all", Map.of()).get("code"));

            g(f, w, "gmail_forward_message", Map.of("message_id", "G1", "to", "audit@corp.com", "bcc", "boss@corp.com", "attachments", List.of("report.csv")), "message_id");
            String fw = raw(f.last("POST", gm + "messages/send"));
            assertTrue(fw.contains("Bcc: boss@corp.com") && fw.contains("filename=\"INV-7.pdf\"") && fw.contains("filename=\"report.csv\""), fw);

            g(f, w, "gmail_reply_all", Map.of("message_id", "G1", "body", "Done", "exclude_sender", true, "cc", "lead@corp.com", "subject", "Paid", "include_original", false), "message_id");
            String ra = raw(f.last("POST", gm + "messages/send"));
            assertTrue(ra.contains("To: bob@corp.com\r\n"), ra);
            assertTrue(ra.contains("Cc: carol@corp.com, lead@corp.com\r\n"), ra);
            assertTrue(ra.contains("Subject: Paid\r\n"), ra);

            Files.createDirectories(w.resolve("att"));
            Files.writeString(w.resolve("att/INV-7.pdf"), "old");
            o = g(f, w, "gmail_save_all_attachments", Map.of("target_folder", "att", "label", "INBOX", "mark_read", true), "files", "message_count");
            assertEquals(BigDecimal.valueOf(2), o.get("message_count"));
            assertEquals(List.of(w.resolve("att/INV-7 (2).pdf").toString(), w.resolve("att/INV-7 (3).pdf").toString()), o.get("files"));
            assertTrue(q(f.last("GET", gm + "messages")).contains("label:INBOX has:attachment"));
            assertEquals(List.of("UNREAD"), f.last("POST", gm + "messages/G2/modify").json().get("removeLabelIds"));
            o = g(f, w, "gmail_save_attachments", Map.of("message_id", "G1", "target_folder", "att", "overwrite", true), "files");
            assertEquals(List.of(w.resolve("att/INV-7.pdf").toString()), o.get("files"));
            assertEquals("%PDF", Files.readString(w.resolve("att/INV-7.pdf")));
        }
    }

    @Test void driveUploadReplaceCopyFolderPermissions() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files", c -> q(c).contains("name = 'r.csv'") ? ok("{\"files\":[{\"id\":\"OLD\"}]}")
                    : q(c).contains("'SRC' in parents") ? ok("{\"files\":[{\"id\":\"f1\",\"name\":\"a.txt\",\"mimeType\":\"text/plain\"},{\"id\":\"SUB\",\"name\":\"Sub\",\"mimeType\":\"application/vnd.google-apps.folder\"}]}")
                    : q(c).contains("'SUB' in parents") ? ok("{\"files\":[{\"id\":\"f2\",\"name\":\"b.txt\",\"mimeType\":\"text/plain\"}]}") : ok("{\"files\":[]}"))
             .on("PATCH /upload/drive/v3/files/OLD", c -> ok("{\"id\":\"OLD\"}"))
             .on("POST /upload/drive/v3/files", c -> ok("{\"id\":\"NEW\"}"))
             .on("PATCH /drive/v3/files/.*", c -> ok("{}"))
             .on("GET /drive/v3/files/SRC", c -> ok("{\"id\":\"SRC\",\"name\":\"Reports\",\"mimeType\":\"application/vnd.google-apps.folder\",\"parents\":[\"P\"]}"))
             .on("POST /drive/v3/files", c -> ok("{\"id\":\"C" + c.json().get("name") + "\"}"))
             .on("POST /drive/v3/files/f[12]/copy", c -> ok("{\"id\":\"x\"}"))
             .on("GET /drive/v3/files/DOC/permissions", c -> ok("{\"permissions\":[{\"type\":\"domain\",\"role\":\"reader\",\"domain\":\"corp.com\"},{\"type\":\"user\",\"role\":\"writer\",\"emailAddress\":\"Ann@corp.com\"}]}"));
            Path w = tmp();
            Files.writeString(w.resolve("r.csv"), "a,b");
            Map<String, Object> o = g(f, w, "drive_upload", Map.of("local_path", "r.csv", "folder_id", "F1", "overwrite", true, "description", "Daily"), "file_id");
            assertEquals("OLD", o.get("file_id"), "content replaced, id kept");
            assertEquals("a,b", f.last("PATCH", "/upload/drive/v3/files/OLD").text());
            assertEquals(Map.of("description", "Daily"), f.last("PATCH", "/drive/v3/files/OLD").json());
            o = g(f, w, "drive_upload", Map.of("local_path", "r.csv", "name", "other.csv", "overwrite", true), "file_id");
            assertEquals("NEW", o.get("file_id"), "no file of that name: a new one");

            o = g(f, w, "drive_copy_folder", Map.of("folder_id", "SRC", "destination_id", "DEST"), "folder_id", "file_count");
            assertEquals("CReports", o.get("folder_id"));
            assertEquals(BigDecimal.valueOf(2), o.get("file_count"));
            assertEquals(List.of("CReports"), f.last("POST", "/drive/v3/files/f1/copy").json().get("parents"));
            assertEquals(List.of("DEST"), f.calls.stream().filter(c -> c.method().equals("POST") && c.path().equals("/drive/v3/files")).findFirst().orElseThrow().json().get("parents"));
            assertEquals(List.of("CSub"), f.last("POST", "/drive/v3/files/f2/copy").json().get("parents"), "files of a subfolder go into the copied subfolder");

            o = g(f, w, "drive_check_permission", Map.of("item_id", "DOC", "email", "ann@corp.com", "access", "write"), "has_access", "role");
            assertEquals(true, o.get("has_access"));
            assertEquals("writer", o.get("role"));
            o = g(f, w, "drive_check_permission", Map.of("item_id", "DOC", "email", "zed@corp.com", "access", "write"), "has_access", "role");
            assertEquals(false, o.get("has_access"));
            assertEquals("reader", o.get("role"), "the domain grant counts");

            g(f, w, "drive_update_permission", Map.of("item_id", "DOC", "permission_id", "P7", "role", "commenter"));
            assertEquals(Map.of("role", "commenter"), f.last("PATCH", "/drive/v3/files/DOC/permissions/P7").json());
        }
    }

    @Test void sheetsFormatCopyCountAndCalendarEvent() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /v4/spreadsheets/S1", c -> ok("{\"sheets\":[{\"properties\":{\"sheetId\":0,\"title\":\"Data\"}},{\"properties\":{\"sheetId\":5,\"title\":\"Old\",\"hidden\":true}}]}"))
             .on("POST /v4/spreadsheets/S1:batchUpdate", c -> ok("{}"))
             .on("GET /v4/spreadsheets/S1/values/.*", c -> ok("{\"values\":[[\"a\"],[],[\"\"],[\"b\"]]}"))
             .on("POST /calendar/v3/calendars/primary/events", c -> ok("{\"id\":\"E1\",\"hangoutLink\":\"https://meet/x\"}"));
            Path w = tmp();
            g(f, w, "sheets_format_cells", Map.of("spreadsheet_id", "S1", "range", "Data!A1:B2", "underline", true, "font_family", "Roboto", "vertical_alignment", "middle",
                    "wrap", "wrap", "merge", "all", "border_style", "dashed"));
            List<Object> reqs = l(f.last("POST", "/v4/spreadsheets/S1:batchUpdate").json().get("requests"));
            assertEquals(3, reqs.size());
            Map<String, Object> repeat = m(m(reqs.get(0)).get("repeatCell"));
            Map<String, Object> fmt = m(m(repeat.get("cell")).get("userEnteredFormat"));
            assertEquals("MIDDLE", fmt.get("verticalAlignment"));
            assertEquals("WRAP", fmt.get("wrapStrategy"));
            assertEquals("Roboto", m(fmt.get("textFormat")).get("fontFamily"));
            assertEquals("MERGE_ALL", m(m(reqs.get(1)).get("mergeCells")).get("mergeType"));
            assertEquals("DASHED", m(m(m(reqs.get(2)).get("updateBorders")).get("innerVertical")).get("style"));

            g(f, w, "sheets_copy_range", Map.of("spreadsheet_id", "S1", "source", "Data!A1:B2", "destination", "Old!C3", "paste", "values", "transpose", true));
            Map<String, Object> cp = m(m(l(f.last("POST", "/v4/spreadsheets/S1:batchUpdate").json().get("requests")).get(0)).get("copyPaste"));
            assertEquals("PASTE_VALUES", cp.get("pasteType"));
            assertEquals("TRANSPOSE", cp.get("pasteOrientation"));
            assertEquals(5, ((Number) m(cp.get("destination")).get("sheetId")).intValue());
            assertEquals(2, ((Number) m(cp.get("destination")).get("startColumnIndex")).intValue());

            Map<String, Object> o = g(f, w, "sheets_list_sheets", Map.of("spreadsheet_id", "S1", "exclude_hidden", true), "names", "count");
            assertEquals(List.of("Data"), o.get("names"));
            assertEquals(BigDecimal.ONE, o.get("count"));
            o = g(f, w, "sheets_row_count", Map.of("spreadsheet_id", "S1", "non_empty_only", true), "rows");
            assertEquals(BigDecimal.valueOf(2), o.get("rows"));
            o = g(f, w, "sheets_row_count", Map.of("spreadsheet_id", "S1"), "rows");
            assertEquals(BigDecimal.valueOf(4), o.get("rows"), "default unchanged: number of the last filled row");

            o = g(f, w, "calendar_create_event", Map.ofEntries(Map.entry("subject", "Offsite"), Map.entry("start", "2026-11-02T09:00:00"), Map.entry("end", "2026-11-02T10:00:00"),
                    Map.entry("all_day", true), Map.entry("location", "Berlin"), Map.entry("optional_attendees", "o@x.com"), Map.entry("notify", false),
                    Map.entry("add_meet_link", true), Map.entry("reminder_minutes", 30), Map.entry("visibility", "private"), Map.entry("show_as", "free"),
                    Map.entry("recurrence", "monthly")), "event_id", "meet_link");
            assertEquals("https://meet/x", o.get("meet_link"));
            Call ev = f.last("POST", "/calendar/v3/calendars/primary/events");
            assertTrue(q(ev).contains("sendUpdates=none") && q(ev).contains("conferenceDataVersion=1"), q(ev));
            Map<String, Object> body = ev.json();
            assertEquals(Map.of("date", "2026-11-02"), body.get("start"));
            assertEquals(Map.of("date", "2026-11-03"), body.get("end"));
            assertEquals("Berlin", body.get("location"));
            assertEquals(true, m(l(body.get("attendees")).get(0)).get("optional"));
            assertEquals("private", body.get("visibility"));
            assertEquals("transparent", body.get("transparency"));
            assertEquals(List.of("RRULE:FREQ=MONTHLY"), body.get("recurrence"));
            assertEquals(false, m(body.get("reminders")).get("useDefault"));
            assertEquals("hangoutsMeet", m(m(m(body.get("conferenceData")).get("createRequest")).get("conferenceSolutionKey")).get("type"));
        }
    }
}
