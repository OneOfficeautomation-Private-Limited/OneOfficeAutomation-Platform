package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Options added to the Microsoft 365 toolkit after comparing it action by action with other RPA products, against a fake Graph server. */
class Microsoft365OptionsTest {

    /** Runs one action of a package through the Runner; returns its outputs by name. */
    static Map<String, Object> act(String pkg, String tokenArg, String baseArg, String base, Path w, String action, Map<String, Object> args, String... outs) {
        StringBuilder vars = new StringBuilder("[" + TOKEN_VAR);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String o : outs) { vars.append(",{\"name\":\"").append(o).append("\",\"type\":\"Any\",\"direction\":\"output\"}"); out.put(o, o); }
        vars.append("]");
        Map<String, Object> a = new LinkedHashMap<>(args);
        a.put(tokenArg, "$tok");
        if (baseArg != null) a.put(baseArg, base);
        return run(w, vars.toString(), "[" + JsonLite.write(Map.of("id", "s", "action", pkg + "/" + action, "args", a, "out", out)) + "]", TOKEN);
    }

    static Map<String, Object> err(String pkg, String tokenArg, String baseArg, String base, String action, Map<String, Object> args) throws Exception {
        Map<String, Object> a = new LinkedHashMap<>(args);
        a.put(tokenArg, "$tok");
        a.put(baseArg, base);
        RunResult r = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[" + JsonLite.write(Map.of("id", "s", "action", pkg + "/" + action, "args", a)) + "]");
        assertFalse(r.ok(), action + " should fail");
        return r.error();
    }

    static Map<String, Object> m365(Fake f, Path w, String action, Map<String, Object> args, String... outs) {
        return act("ooa.microsoft365", "access_token", "base_url", f.url() + "/v1.0", w, action, args, outs);
    }

    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> l(Object o) { return (List<Object>) o; }
    static String q(Call c) { return ConnectorsTest.q(c); }
    static List<Object> addrs(Object recipients) { return l(recipients).stream().map(r -> m(m(r).get("emailAddress")).get("address")).toList(); }

    static final String MSGS = "{\"value\":[{\"id\":\"m1\",\"subject\":\"Invoice 1\",\"isRead\":false,\"from\":{\"emailAddress\":{\"address\":\"ap@acme.com\"}}},"
            + "{\"id\":\"m2\",\"subject\":\"Receipt 2\",\"isRead\":true,\"from\":{\"emailAddress\":{\"address\":\"shop@globex.com\"}}},"
            + "{\"id\":\"m3\",\"subject\":\"Lunch\",\"isRead\":false,\"from\":{\"emailAddress\":{\"address\":\"bob@corp.com\"}}}]}";

    @Test void outlookSendListAndFolderPaths() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1.0/me/sendMail", c -> new Object[]{202, ""})
             .on("GET /v1.0/me/mailFolders/inbox/childFolders", c -> q(c).contains("'Invoices'") ? ok("{\"value\":[{\"id\":\"F-INV\"}]}") : ok("{\"value\":[]}"))
             .on("GET /v1.0/me/mailFolders/F-INV/childFolders", c -> ok("{\"value\":[{\"id\":\"F-2026\"}]}"))
             .on("GET /v1.0/me/mailFolders/F-INV/messages", c -> ok(MSGS))
             .on("GET /v1.0/me/mailFolders/inbox/messages", c -> ok(MSGS))
             .on("POST /v1.0/me/messages/m1/move", c -> ok("{\"id\":\"m1b\"}"));
            Path w = tmp();
            m365(f, w, "send_mail", Map.of("to", "a@x.com", "subject", "Hi", "from", "invoices@acme.com", "importance", "high", "reply_to", "desk@acme.com",
                    "save_to_sent_items", false, "read_receipt", true));
            Map<String, Object> sent = f.last("POST", "/v1.0/me/sendMail").json();
            Map<String, Object> msg = m(sent.get("message"));
            assertEquals(false, sent.get("saveToSentItems"));
            assertEquals("high", msg.get("importance"));
            assertEquals("invoices@acme.com", m(m(msg.get("from")).get("emailAddress")).get("address"), "send as a shared mailbox");
            assertEquals(List.of("desk@acme.com"), addrs(msg.get("replyTo")));
            assertEquals(true, msg.get("isReadReceiptRequested"));
            m365(f, w, "send_mail", Map.of("to", "a@x.com", "subject", "Hi"));
            Map<String, Object> plain = f.last("POST", "/v1.0/me/sendMail").json();
            assertEquals(true, plain.get("saveToSentItems"), "defaults unchanged");
            assertFalse(m(plain.get("message")).containsKey("importance"));
            assertEquals("FLOW.INVALID", err("ooa.microsoft365", "access_token", "base_url", f.url() + "/v1.0", "send_mail", Map.of("to", "a@x.com", "subject", "x", "importance", "urgent")).get("code"));
            assertEquals("M365.BAD_OPTION", assertThrows(com.oneofficeautomation.sdk.ActionException.class,
                    () -> Microsoft365Package.mailOptions(new HashMap<>(), "urgent", null, null)).code(), "checked in the action too, for values from variables");

            Map<String, Object> o = m365(f, w, "list_mail", Map.of("folder", "Inbox/Invoices", "since", "2026-10-01T00:00:00", "before", "2026-11-01T08:30:00",
                    "has_attachments_only", true, "oldest_first", true, "subject_contains", "invoice; receipt"), "table", "count");
            Call list = f.last("GET", "/v1.0/me/mailFolders/F-INV/messages");
            assertTrue(q(list).contains("$filter=receivedDateTime ge 2026-10-01T00:00:00Z and receivedDateTime lt 2026-11-01T08:30:00Z and hasAttachments eq true"), q(list));
            assertTrue(q(list).contains("$orderby=receivedDateTime asc"));
            assertEquals(BigDecimal.valueOf(2), o.get("count"), "subject matches either value");
            m365(f, w, "list_mail", Map.of("read_only", true, "has_attachments_only", true), "count");
            assertTrue(q(f.last("GET", "/v1.0/me/mailFolders/inbox/messages")).contains("receivedDateTime ge 1900-01-01T00:00:00Z and isRead eq true and hasAttachments eq true"),
                    "a date comes first so Graph can sort");
            assertEquals("M365.NO_FOLDER", err("ooa.microsoft365", "access_token", "base_url", f.url() + "/v1.0", "list_mail", Map.of("folder", "Inbox/Nope")).get("code"));

            m365(f, w, "move_mail", Map.of("id", "m1", "to_folder", "inbox\\Invoices\\2026"));
            assertEquals("F-2026", f.last("POST", "/v1.0/me/messages/m1/move").json().get("destinationId"), "backslash path resolved level by level");
            o = m365(f, w, "mail_folder_exists", Map.of("name", "Inbox/Invoices"), "exists", "folder_id");
            assertEquals(true, o.get("exists"));
            assertEquals("F-INV", o.get("folder_id"));
            o = m365(f, w, "mail_folder_exists", Map.of("name", "Inbox/Other"), "exists", "folder_id");
            assertEquals(false, o.get("exists"));
        }
    }

    @Test void outlookBulkFiltersForwardReplyAndAttachments() throws Exception {
        try (Fake f = new Fake()) {
            String att = "{\"value\":[{\"name\":\"INV-1.pdf\",\"contentBytes\":\"" + Base64.getEncoder().encodeToString("pdf".getBytes()) + "\"},"
                    + "{\"name\":\"logo.png\",\"isInline\":true,\"contentBytes\":\"" + Base64.getEncoder().encodeToString("png".getBytes()) + "\"}]}";
            f.on("GET /v1.0/me/mailFolders/inbox/messages", c -> q(c).contains("isRead eq true")
                    ? ok("{\"value\":[{\"id\":\"m2\",\"subject\":\"Receipt 2\",\"isRead\":true,\"from\":{\"emailAddress\":{\"address\":\"shop@globex.com\"}}}]}") : ok(MSGS))
             .on("DELETE /v1.0/me/messages/.*", c -> new Object[]{204, ""})
             .on("GET /v1.0/me/messages/m[0-9]/attachments", c -> ok(att))
             .on("PATCH /v1.0/me/messages/.*", c -> ok("{}"))
             .on("POST /v1.0/me/messages/m1/(forward|reply|replyAll)", c -> new Object[]{202, ""})
             .on("POST /v1.0/me/messages/m1/createForward", c -> new Object[]{201, "{\"id\":\"D1\"}"})
             .on("POST /v1.0/me/messages/D1/attachments", c -> new Object[]{201, "{\"id\":\"A1\"}"})
             .on("POST /v1.0/me/messages/D1/send", c -> new Object[]{202, ""})
             .on("GET /v1.0/me/messages/m1", c -> ok("{\"from\":{\"emailAddress\":{\"address\":\"ann@acme.com\"}},\"toRecipients\":[{\"emailAddress\":{\"address\":\"me@corp.com\"}},"
                     + "{\"emailAddress\":{\"address\":\"bob@corp.com\"}}],\"ccRecipients\":[{\"emailAddress\":{\"address\":\"ann@acme.com\"}},{\"emailAddress\":{\"address\":\"carol@corp.com\"}}]}"))
             .on("GET /v1.0/me", c -> ok("{\"mail\":\"me@corp.com\"}"));
            Path w = tmp();
            Files.writeString(w.resolve("note.txt"), "n");

            Map<String, Object> o = m365(f, w, "delete_all_mail", Map.of("read_only", true, "since", "2026-01-01T00:00:00", "permanent", true), "count");
            assertEquals(BigDecimal.ONE, o.get("count"));
            assertTrue(q(f.last("GET", "/v1.0/me/mailFolders/inbox/messages")).contains("$filter=receivedDateTime ge 2026-01-01T00:00:00Z and isRead eq true"));
            assertNotNull(f.last("DELETE", "/v1.0/me/messages/m2"));

            Files.createDirectories(w.resolve("in"));
            Files.writeString(w.resolve("in/INV-1.pdf"), "old");
            o = m365(f, w, "save_mail_attachments", Map.of("id", "m1", "target_folder", "in", "overwrite", true, "include_inline", false), "files");
            assertEquals(1, l(o.get("files")).size(), "inline logo skipped");
            assertEquals("pdf", Files.readString(w.resolve("in/INV-1.pdf")), "replaced");

            o = m365(f, w, "save_all_mail_attachments", Map.of("target_folder", "all", "from_contains", "acme;globex", "mark_read", true), "files", "email_count");
            assertEquals(BigDecimal.valueOf(2), o.get("email_count"));
            assertEquals(4, l(o.get("files")).size(), "two files per email, numbered when the name repeats");
            assertTrue(Files.exists(w.resolve("all/INV-1 (2).pdf")));
            assertEquals(Map.of("isRead", true), f.last("PATCH", "/v1.0/me/messages/m1").json(), "only the unread email is marked");

            m365(f, w, "forward_mail", Map.of("id", "m1", "to", "x@y.com", "cc", "c@y.com", "bcc", "b@y.com", "importance", "low", "from", "ap@corp.com"));
            Map<String, Object> fw = f.last("POST", "/v1.0/me/messages/m1/forward").json();
            assertEquals(List.of("c@y.com"), addrs(m(fw.get("message")).get("ccRecipients")));
            assertEquals(List.of("b@y.com"), addrs(m(fw.get("message")).get("bccRecipients")));
            assertEquals("low", m(fw.get("message")).get("importance"));
            m365(f, w, "forward_mail", Map.of("id", "m1", "to", "x@y.com", "comment", "see file", "attachments", List.of("note.txt")));
            assertEquals("see file", f.last("POST", "/v1.0/me/messages/m1/createForward").json().get("comment"), "attachments go through a draft");
            assertEquals("note.txt", f.last("POST", "/v1.0/me/messages/D1/attachments").json().get("name"));
            assertNotNull(f.last("POST", "/v1.0/me/messages/D1/send"));

            m365(f, w, "reply_all_mail", Map.of("id", "m1", "comment", "Done", "exclude_sender", true, "cc", "lead@corp.com"));
            Map<String, Object> ra = m(f.last("POST", "/v1.0/me/messages/m1/replyAll").json().get("message"));
            assertEquals(List.of("bob@corp.com"), addrs(ra.get("toRecipients")), "sender and own address left out");
            assertEquals(List.of("lead@corp.com", "carol@corp.com"), addrs(ra.get("ccRecipients")));
            m365(f, w, "reply_mail", Map.of("id", "m1", "comment", "Ok"));
            assertEquals(Map.of("comment", "Ok"), f.last("POST", "/v1.0/me/messages/m1/reply").json(), "no options, same request as before");
        }
    }

    @Test void calendarEventOptions() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1.0/me/events", c -> new Object[]{201, "{\"id\":\"E1\",\"onlineMeeting\":{\"joinUrl\":\"https://teams/join\"}}"})
             .on("PATCH /v1.0/me/events/E1", c -> ok("{\"webLink\":\"https://outlook/E1\"}"))
             .on("GET /v1.0/me/calendars/CAL2/calendarView", c -> ok("{\"value\":[{\"id\":\"1\",\"subject\":\"Budget\"},{\"id\":\"2\",\"subject\":\"Lunch\"}]}"));
            Path w = tmp();
            Map<String, Object> o = m365(f, w, "create_event", Map.ofEntries(Map.entry("subject", "Offsite"), Map.entry("start", "2026-11-02T09:30:00"), Map.entry("end", "2026-11-02T10:00:00"),
                    Map.entry("all_day", true), Map.entry("optional_attendees", "o@x.com"), Map.entry("attendees", "r@x.com"), Map.entry("online_meeting", true),
                    Map.entry("show_as", "oof"), Map.entry("sensitivity", "private"), Map.entry("reminder_minutes", 0), Map.entry("categories", List.of("Team")),
                    Map.entry("recurrence", "weekly"), Map.entry("recurrence_count", 4), Map.entry("body", "<b>Agenda</b>"), Map.entry("html", true)), "event_id", "online_meeting_url");
            assertEquals("https://teams/join", o.get("online_meeting_url"));
            Map<String, Object> ev = f.last("POST", "/v1.0/me/events").json();
            assertEquals(true, ev.get("isAllDay"));
            assertEquals("2026-11-02T00:00", m(ev.get("start")).get("dateTime"));
            assertEquals("2026-11-03T00:00", m(ev.get("end")).get("dateTime"), "an all-day event on one date ends the next midnight");
            assertEquals(List.of("required", "optional"), l(ev.get("attendees")).stream().map(a -> m(a).get("type")).toList());
            assertEquals("teamsForBusiness", ev.get("onlineMeetingProvider"));
            assertEquals("oof", ev.get("showAs"));
            assertEquals("private", ev.get("sensitivity"));
            assertEquals(false, ev.get("isReminderOn"));
            assertEquals(List.of("Team"), ev.get("categories"));
            assertEquals("HTML", m(ev.get("body")).get("contentType"));
            Map<String, Object> rec = m(ev.get("recurrence"));
            assertEquals("weekly", m(rec.get("pattern")).get("type"));
            assertEquals(List.of("monday"), m(rec.get("pattern")).get("daysOfWeek"));
            assertEquals("numbered", m(rec.get("range")).get("type"));
            assertEquals(4, ((Number) m(rec.get("range")).get("numberOfOccurrences")).intValue());

            m365(f, w, "update_event", Map.of("event_id", "E1", "importance", "high", "reminder_minutes", 15), "web_link");
            Map<String, Object> up = f.last("PATCH", "/v1.0/me/events/E1").json();
            assertEquals("high", up.get("importance"));
            assertEquals(15, ((Number) up.get("reminderMinutesBeforeStart")).intValue());

            o = m365(f, w, "list_events", Map.of("from", "2026-11-01T00:00:00", "to", "2026-11-30T00:00:00", "calendar_id", "CAL2", "subject_contains", "budget", "time_zone", "Europe/London"), "table");
            assertEquals(1, l(m(o.get("table")).get("rows")).size());
            assertEquals("outlook.timezone=\"Europe/London\"", f.last("GET", "/v1.0/me/calendars/CAL2/calendarView").header("Prefer"));
            assertEquals("FLOW.INVALID", err("ooa.microsoft365", "access_token", "base_url", f.url() + "/v1.0", "create_event",
                    Map.of("subject", "x", "start", "2026-11-02T09:00:00", "end", "2026-11-02T10:00:00", "show_as", "away")).get("code"));
        }
    }

    @Test void oneDriveConflictsPermanentDeleteSearchAndSharing() throws Exception {
        try (Fake f = new Fake()) {
            String d = "/v1.0/me/drive";
            f.on("PUT " + d + "/root:/.*", c -> ok("{\"id\":\"I1\",\"webUrl\":\"https://od/I1\"}"))
             .on("POST " + d + "/root:/Old/x.txt:/permanentDelete", c -> new Object[]{204, ""})
             .on("GET " + d + "/root:/Docs:/search.*", c -> ok("{\"value\":[{\"id\":\"1\",\"name\":\"inv\",\"folder\":{}},{\"id\":\"2\",\"name\":\"INV\"},{\"id\":\"3\",\"name\":\"inv-2.pdf\"}]}"))
             .on("POST " + d + "/root:/Docs/a.pdf:/invite", c -> ok("{\"value\":[{\"id\":\"P1\"},{\"id\":\"P2\"}]}"))
             .on("POST " + d + "/root:/Docs/a.pdf:/createLink", c -> ok("{\"id\":\"L1\",\"link\":{\"webUrl\":\"https://od/s/abc\"}}"));
            Path w = tmp();
            Files.writeString(w.resolve("a.txt"), "x");
            m365(f, w, "upload_file", Map.of("local_path", "a.txt", "remote_path", "Docs/a.txt", "if_exists", "rename"));
            assertEquals("@microsoft.graph.conflictBehavior=rename", f.last("PUT", d + "/root:/Docs/a.txt:/content").query());
            m365(f, w, "upload_file", Map.of("local_path", "a.txt", "remote_path", "Docs/b.txt"));
            assertNull(f.last("PUT", d + "/root:/Docs/b.txt:/content").query(), "replace stays the default");

            m365(f, w, "delete_item", Map.of("item", "Old/x.txt", "permanent", true));
            assertNotNull(f.last("POST", d + "/root:/Old/x.txt:/permanentDelete"));

            Map<String, Object> o = m365(f, w, "find_items", Map.of("text", "inv", "in_folder", "Docs", "item_type", "file", "exact_name", true), "table", "count");
            assertEquals(BigDecimal.ONE, o.get("count"), "only the file named exactly inv (any case)");
            assertEquals("2", l(l(m(o.get("table")).get("rows")).get(0)).get(0));

            o = m365(f, w, "share_item", Map.of("item", "Docs/a.pdf", "recipients", "a@x.com; b@x.com", "role", "write", "send_invitation", false, "message", "FYI"), "permission_ids");
            assertEquals(List.of("P1", "P2"), o.get("permission_ids"));
            Map<String, Object> inv = f.last("POST", d + "/root:/Docs/a.pdf:/invite").json();
            assertEquals(List.of("write"), inv.get("roles"));
            assertEquals(false, inv.get("sendInvitation"));
            assertEquals(2, l(inv.get("recipients")).size());

            o = m365(f, w, "create_sharing_link", Map.of("item", "Docs/a.pdf", "type", "edit", "scope", "anonymous", "expires", "2026-12-31T00:00:00"), "link", "permission_id");
            assertEquals("https://od/s/abc", o.get("link"));
            Map<String, Object> link = f.last("POST", d + "/root:/Docs/a.pdf:/createLink").json();
            assertEquals("anonymous", link.get("scope"));
            assertEquals("2026-12-31T00:00:00Z", link.get("expirationDateTime"));
        }
    }

    @Test void excelFormatVisibilityFindCellFormatTablesAndNewWorkbook() throws Exception {
        try (Fake f = new Fake()) {
            String ws = "/v1.0/me/drive/root:/b.xlsx:/workbook/worksheets('Data')";
            f.on("PATCH .*", c -> ok("{}"))
             .on("POST .*/merge", c -> ok("{}"))
             .on("GET .*/usedRange.*", c -> ok("{\"address\":\"Data!A1:B2\",\"rowCount\":2,\"columnCount\":2}"))
             .on("GET .*/range\\(address='A1:B2'\\)", c -> ok("{\"text\":[[\"Name\",\"Total\"],[\"acme corp\",\"ACME\"]]}"))
             .on("GET .*/range\\(address='C7'\\)/format/font", c -> ok("{\"name\":\"Calibri\",\"size\":11,\"bold\":true,\"color\":\"#000000\"}"))
             .on("GET .*/range\\(address='C7'\\)/format/fill", c -> ok("{\"color\":\"#FFFF00\"}"))
             .on("GET .*/range\\(address='C7'\\)/format", c -> ok("{\"horizontalAlignment\":\"Center\",\"wrapText\":true}"))
             .on("GET .*/range\\(address='C7'\\)", c -> ok("{\"numberFormat\":[[\"0.00\"]]}"))
             .on("PUT /v1.0/me/drive/root:/new.xlsx:/content", c -> ok("{\"id\":\"WB\",\"webUrl\":\"https://od/WB\"}"));
            Path w = tmp();
            String rng = ws + "/range(address='A1:B2')";
            m365(f, w, "excel_format_range", Map.ofEntries(Map.entry("remote_path", "b.xlsx"), Map.entry("sheet", "Data"), Map.entry("range", "A1:B2"),
                    Map.entry("italic", true), Map.entry("underline", true), Map.entry("font_name", "Arial"), Map.entry("font_size", 12),
                    Map.entry("horizontal_alignment", "Center"), Map.entry("wrap_text", true), Map.entry("column_width", 80),
                    Map.entry("border_style", "Continuous"), Map.entry("border_color", "#FF0000"), Map.entry("merge", true)));
            Map<String, Object> font = f.last("PATCH", java.util.regex.Pattern.quote(rng + "/format/font")).json();
            assertEquals(Map.of("italic", true, "underline", "Single", "name", "Arial", "size", new BigDecimal(12)), font);
            Map<String, Object> fmt = f.last("PATCH", java.util.regex.Pattern.quote(rng + "/format")).json();
            assertEquals("Center", fmt.get("horizontalAlignment"));
            assertEquals(true, fmt.get("wrapText"));
            assertEquals(6, f.calls.stream().filter(c -> c.method().equals("PATCH") && c.path().contains("/format/borders/")).count(), "four edges and two inside lines");
            assertNotNull(f.last("POST", java.util.regex.Pattern.quote(rng + "/merge")));

            m365(f, w, "excel_hide_worksheet", Map.of("remote_path", "b.xlsx", "sheet", "Data", "very_hidden", true));
            assertEquals(Map.of("visibility", "VeryHidden"), f.last("PATCH", java.util.regex.Pattern.quote(ws)).json());
            m365(f, w, "excel_show_worksheet", Map.of("remote_path", "b.xlsx", "sheet", "Data"));
            assertEquals(Map.of("visibility", "Visible"), f.last("PATCH", java.util.regex.Pattern.quote(ws)).json());

            Map<String, Object> o = m365(f, w, "excel_find", Map.of("remote_path", "b.xlsx", "sheet", "Data", "text", "acme"), "cells", "count");
            assertEquals(List.of("A2", "B2"), o.get("cells"));
            o = m365(f, w, "excel_find", Map.of("remote_path", "b.xlsx", "sheet", "Data", "text", "ACME", "match_case", true, "whole_cell", true), "cells");
            assertEquals(List.of("B2"), o.get("cells"));

            o = m365(f, w, "excel_get_cell_format", Map.of("remote_path", "b.xlsx", "sheet", "Data", "cell", "C7"), "format");
            Map<String, Object> cf = m(o.get("format"));
            assertEquals("#FFFF00", cf.get("fill_color"));
            assertEquals("Calibri", cf.get("font_name"));
            assertEquals(true, cf.get("bold"));
            assertEquals("Center", cf.get("horizontal_alignment"));
            assertEquals("0.00", cf.get("number_format"));

            m365(f, w, "excel_rename_table", Map.of("remote_path", "b.xlsx", "table", "Table1", "new_name", "Sales"));
            assertEquals(Map.of("name", "Sales"), f.last("PATCH", ".*/workbook/tables/Table1").json());

            o = m365(f, w, "excel_create_workbook", Map.of("remote_path", "new.xlsx", "sheet_names", List.of("Data", "R&D")), "item_id");
            assertEquals("WB", o.get("item_id"));
            Call put = f.last("PUT", "/v1.0/me/drive/root:/new.xlsx:/content");
            assertEquals("@microsoft.graph.conflictBehavior=fail", put.query(), "an existing workbook is never replaced by default");
            Map<String, String> parts = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(put.body()))) {
                for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(parts.get("xl/workbook.xml").contains("name=\"Data\"") && parts.get("xl/workbook.xml").contains("name=\"R&amp;D\""));
            assertTrue(parts.containsKey("xl/worksheets/sheet2.xml") && parts.containsKey("[Content_Types].xml"));
        }
    }

    @Test void teamsSubjectImportanceRepliesAndChannelDetails() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1.0/teams/T1/channels/C1/messages.*", c -> new Object[]{201, "{\"id\":\"M1\"}"})
             .on("POST /v1.0/chats/CH1/messages", c -> new Object[]{201, "{\"id\":\"M2\"}"})
             .on("GET /v1.0/teams/T1/channels/C1", c -> ok("{\"id\":\"C1\",\"displayName\":\"General\",\"membershipType\":\"standard\",\"email\":\"g@x.com\",\"webUrl\":\"https://teams/C1\"}"));
            Path w = tmp();
            m365(f, w, "teams_message", Map.of("team_id", "T1", "channel_id", "C1", "message", "Build failed", "subject", "CI", "importance", "urgent"), "message_id");
            Map<String, Object> msg = f.last("POST", "/v1.0/teams/T1/channels/C1/messages").json();
            assertEquals("CI", msg.get("subject"));
            assertEquals("urgent", msg.get("importance"));
            m365(f, w, "teams_message", Map.of("team_id", "T1", "channel_id", "C1", "message", "Fixed", "reply_to_message_id", "M0"));
            assertEquals("Fixed", m(f.last("POST", "/v1.0/teams/T1/channels/C1/messages/M0/replies").json().get("body")).get("content"));
            m365(f, w, "send_chat_message", Map.of("chat_id", "CH1", "message", "Hi", "importance", "high"));
            assertEquals("high", f.last("POST", "/v1.0/chats/CH1/messages").json().get("importance"));
            Map<String, Object> o = m365(f, w, "get_channel_details", Map.of("team_id", "T1", "channel_id", "C1"), "channel");
            assertEquals("General", m(o.get("channel")).get("name"));
            assertEquals("g@x.com", m(o.get("channel")).get("email"));
        }
    }
}
