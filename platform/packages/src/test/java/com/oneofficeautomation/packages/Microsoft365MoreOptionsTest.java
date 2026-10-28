package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Microsoft 365 options added from the gap list, against the fake Graph server of Microsoft365GapsTest. */
class Microsoft365MoreOptionsTest {
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");
    static final String WB = "/v1.0/me/drive/root:/Fin/book.xlsx:/workbook", SH = WB + "/worksheets('Data')";
    static final String BOOK = "Fin/book.xlsx", DR = "me/drive";

    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }

    // ------------------------------------------------------------------------------- calendar

    @Test void createEventInNamedCalendarWithAttachmentsAndWeekdayRepeats() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/me/calendars", c -> ok("{\"value\":[{\"id\":\"CAL-1\",\"name\":\"Calendar\"},{\"id\":\"CAL-2\",\"name\":\"Team holidays\"}]}"))
             .at("POST", "/v1.0/me/calendars/CAL-2/events", c -> new Object[]{201, "{\"id\":\"E7\",\"webLink\":\"https://outlook/E7\"}"})
             .at("POST", "/v1.0/me/events", c -> new Object[]{201, "{\"id\":\"E8\"}"})
             .at("POST", "/v1.0/me/events/E7/attachments", c -> new Object[]{201, "{\"id\":\"AT1\"}"});
            Path w = tmp();
            Files.writeString(w.resolve("agenda.txt"), "1. Plan");
            ActionContext ctx = ctx(w);
            LocalDateTime s = LocalDateTime.parse("2026-11-03T09:00"), e = LocalDateTime.parse("2026-11-03T09:30");

            Map<String, Object> r = pkg.createEvent(ctx, tok, "Stand-up", s, e, "UTC", null, null, null, null, false, false, false, null, null, null, null, null,
                    "weekly", 1, null, 10, "me", b, "team HOLIDAYS", List.of("agenda.txt"), List.of("Monday", "thursday"), null);
            assertEquals("E7", r.get("event_id"));
            assertTrue(f.last("GET", "/v1.0/me/calendars").q().contains("$select=id,name"));
            Map<String, Object> ev = f.last("POST", "/v1.0/me/calendars/CAL-2/events").json();
            Map<String, Object> pattern = m(m(ev.get("recurrence")).get("pattern"));
            assertEquals("weekly", pattern.get("type"));
            assertEquals(List.of("monday", "thursday"), pattern.get("daysOfWeek"));
            assertEquals("sunday", pattern.get("firstDayOfWeek"));
            assertEquals(Map.of("type", "numbered", "startDate", "2026-11-03", "numberOfOccurrences", new BigDecimal(10)), m(ev.get("recurrence")).get("range"));
            Map<String, Object> att = f.last("POST", "/v1.0/me/events/E7/attachments").json();
            assertEquals("#microsoft.graph.fileAttachment", att.get("@odata.type"));
            assertEquals("agenda.txt", att.get("name"));
            assertEquals(Base64.getEncoder().encodeToString("1. Plan".getBytes()), att.get("contentBytes"));

            pkg.createEvent(ctx, tok, "Board", s, e, "UTC", null, null, null, null, false, false, false, null, null, null, null, null,
                    "monthly", 1, null, null, "me", b, null, null, null, "second");
            Map<String, Object> monthly = m(m(f.last("POST", "/v1.0/me/events").json().get("recurrence")).get("pattern"));
            assertEquals(Map.of("type", "relativeMonthly", "interval", new BigDecimal(1), "daysOfWeek", List.of("tuesday"), "index", "second"), monthly,
                    "second Tuesday of the month (the start's weekday)");

            pkg.createEvent(ctx, tok, "Old", s, e, "UTC", null, null, null, null, false, false, false, null, null, null, null, null, "weekly", 1, null, null, "me", b);
            Map<String, Object> plain = m(m(f.last("POST", "/v1.0/me/events").json().get("recurrence")).get("pattern"));
            assertEquals(List.of("tuesday"), plain.get("daysOfWeek"), "old signature keeps the start's weekday");
            assertFalse(plain.containsKey("firstDayOfWeek"));

            int posts = f.all("POST", ".*").size();
            assertEquals("M365.BAD_OPTION", code(() -> pkg.createEvent(ctx, tok, "x", s, e, "UTC", null, null, null, null, false, false, false, null, null, null, null, null,
                    "weekly", 1, null, null, "me", b, null, null, List.of("funday"), null)));
            assertEquals("M365.BAD_OPTION", code(() -> pkg.createEvent(ctx, tok, "x", s, e, "UTC", null, null, null, null, false, false, false, null, null, null, null, null,
                    "monthly", 1, null, null, "me", b, null, null, List.of("friday"), null)), "weekdays on a monthly repeat need a week of the month");
            assertEquals("FILE.NOT_FOUND", code(() -> pkg.createEvent(ctx, tok, "x", s, e, "UTC", null, null, null, null, false, false, false, null, null, null, null, null,
                    "none", 1, null, null, "me", b, null, List.of("missing.pdf"), null, null)));
            assertEquals(posts, f.all("POST", ".*").size(), "nothing created when an input is wrong");
            assertEquals("M365.NOT_FOUND", code(() -> pkg.createEvent(ctx, tok, "x", s, e, "UTC", null, null, null, null, false, false, false, null, null, null, null, null,
                    "none", 1, null, null, "me", b, "Nope", null, null, null)));
        }
    }

    @Test void updateEventAllDayAndRepeatOnSeriesMaster() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/me/events/OCC1", c -> ok("{\"type\":\"occurrence\",\"seriesMasterId\":\"MASTER\",\"isAllDay\":false,"
                    + "\"start\":{\"dateTime\":\"2026-11-04T09:00:00.0000000\",\"timeZone\":\"W. Europe Standard Time\"},\"end\":{\"dateTime\":\"2026-11-04T10:00:00.0000000\",\"timeZone\":\"W. Europe Standard Time\"}}"))
             .at("GET", "/v1.0/me/events/E1", c -> ok("{\"type\":\"singleInstance\",\"start\":{\"dateTime\":\"2026-11-04T09:00:00.0000000\",\"timeZone\":\"UTC\"},\"end\":{\"dateTime\":\"2026-11-04T10:00:00.0000000\",\"timeZone\":\"UTC\"}}"))
             .at("PATCH", "/v1.0/me/events/MASTER", c -> ok("{\"webLink\":\"https://outlook/M\"}"))
             .at("PATCH", "/v1.0/me/events/E1", c -> ok("{\"webLink\":\"https://outlook/E1\"}"));
            ActionContext ctx = ctx(tmp());

            assertEquals("https://outlook/M", pkg.updateEvent(ctx, tok, "OCC1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b,
                    null, "monthly", 2, LocalDateTime.parse("2027-06-30T00:00"), null).get("web_link"));
            Map<String, Object> rec = m(f.last("PATCH", "/v1.0/me/events/MASTER").json().get("recurrence"));
            assertEquals(Map.of("type", "absoluteMonthly", "interval", new BigDecimal(2), "dayOfMonth", new BigDecimal(4)), rec.get("pattern"));
            assertEquals(Map.of("type", "endDate", "startDate", "2026-11-04", "endDate", "2027-06-30"), rec.get("range"));

            pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b, true, null, 1, null, null);
            Map<String, Object> allDay = f.last("PATCH", "/v1.0/me/events/E1").json();
            assertEquals(true, allDay.get("isAllDay"));
            assertEquals(Map.of("dateTime", "2026-11-04T00:00", "timeZone", "UTC"), allDay.get("start"));
            assertEquals(Map.of("dateTime", "2026-11-05T00:00", "timeZone", "UTC"), allDay.get("end"));

            pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b, null, "none", 1, null, null);
            Map<String, Object> stop = f.last("PATCH", "/v1.0/me/events/E1").json();
            assertTrue(stop.containsKey("recurrence") && stop.get("recurrence") == null, "none removes the repeat");

            pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b, false, null, 1, null, null);
            assertEquals(Map.of("isAllDay", false), f.last("PATCH", "/v1.0/me/events/E1").json());

            int gets = f.all("GET", ".*").size();
            pkg.updateEvent(ctx, tok, "E1", "Renamed", null, null, "UTC", null, null, false, null, null, null, null, null, "me", b);
            assertEquals(gets, f.all("GET", ".*").size(), "old signature does not read the event first");
            assertEquals("M365.BAD_OPTION", code(() -> pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b,
                    null, "hourly", 1, null, null)));
        }
    }

    // ---------------------------------------------------------------------------------- Teams

    @Test void teamsMessageByNamesAndChatMessageToPerson() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/me/joinedTeams", c -> ok("{\"value\":[{\"id\":\"T1\",\"displayName\":\"Finance\"},{\"id\":\"T2\",\"displayName\":\"Ops\"}]}"))
             .at("GET", "/v1.0/teams/T2/channels", c -> ok("{\"value\":[{\"id\":\"19:gen@thread.tacv2\",\"displayName\":\"General\"},{\"id\":\"19:al@thread.tacv2\",\"displayName\":\"Alerts\"}]}"))
             .rx("POST", Pattern.quote("/v1.0/teams/T2/channels/") + ".*" + Pattern.quote("/messages"), c -> new Object[]{201, "{\"id\":\"MSG1\"}"})
             .at("GET", "/v1.0/me", c -> ok("{\"id\":\"ME1\"}"))
             .at("POST", "/v1.0/chats", c -> new Object[]{201, "{\"id\":\"19:pair@unq.gbl.spaces\"}"})
             .rx("POST", Pattern.quote("/v1.0/chats/") + ".*" + Pattern.quote("/messages"), c -> new Object[]{201, "{\"id\":\"CM1\"}"});
            ActionContext ctx = ctx(tmp());

            assertEquals("MSG1", pkg.teamsMessage(ctx, tok, null, null, "Job done", false, null, "normal", null, b, "ops", "ALERTS").get("message_id"));
            List<String> posts = f.all("POST", "/v1.0/teams/.*").stream().map(c -> c.path()).toList();
            assertEquals(List.of("/v1.0/teams/T2/channels/19%3Aal%40thread.tacv2/messages"), posts, "team and channel found by name");
            assertEquals("MSG1", pkg.teamsMessage(ctx, tok, "T2", null, "x", false, null, "normal", null, b, null, "general").get("message_id"));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.teamsMessage(ctx, tok, "T2", "C1", "x", false, null, "normal", null, b, "Ops", null)));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.teamsMessage(ctx, tok, null, "C1", "x", false, null, "normal", null, b, null, null)));
            assertEquals("M365.NOT_FOUND", code(() -> pkg.teamsMessage(ctx, tok, null, "C1", "x", false, null, "normal", null, b, "HR", null)));

            Map<String, Object> r = pkg.sendChatMessage(ctx, tok, null, "Hello", false, "normal", b, "ann@x.com");
            assertEquals("CM1", r.get("message_id"));
            assertEquals("19:pair@unq.gbl.spaces", r.get("chat_id"));
            Map<String, Object> chat = f.last("POST", "/v1.0/chats").json();
            assertEquals("oneOnOne", chat.get("chatType"));
            List<Object> binds = ((List<?>) chat.get("members")).stream().map(x -> m(x).get("user@odata.bind")).map(Object.class::cast).toList();
            assertEquals(List.of(b + "/users('ME1')", b + "/users('ann@x.com')"), binds);
            assertEquals(Map.of("contentType", "text", "content", "Hello"), f.last("POST", "/v1.0/chats/19%3Apair%40unq.gbl.spaces/messages").json().get("body"));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.sendChatMessage(ctx, tok, "CH1", "x", false, "normal", b, "ann@x.com")));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.sendChatMessage(ctx, tok, " ", "x", false, "normal", b, null)));
        }
    }

    // ---------------------------------------------------------------------------------- Excel

    @Test void excelDeleteAndInsertWholeRowsAndColumns() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.rx("POST", Pattern.quote(SH + "/range(address='") + "[A-Z0-9:]+'\\)/delete", c -> none())
             .rx("POST", Pattern.quote(SH + "/range(address='") + "[A-Z0-9:]+'\\)/insert", c -> ok("{\"address\":\"Data!3:4\"}"));
            ActionContext ctx = ctx(tmp());
            pkg.excelDeleteRange(ctx, tok, BOOK, "Data", "B5:D7", "entire_row", DR, b);
            assertEquals(Map.of("shift", "Up"), f.last("POST", SH + "/range(address='5:7')/delete").json());
            pkg.excelDeleteRange(ctx, tok, BOOK, "Data", "b2:c9", "entire_column", DR, b);
            assertEquals(Map.of("shift", "Left"), f.last("POST", SH + "/range(address='B:C')/delete").json());
            assertEquals("Data!3:4", pkg.excelInsertRange(ctx, tok, BOOK, "Data", "A3:A4", "entire_row", DR, b).get("address"));
            assertEquals(Map.of("shift", "Down"), f.last("POST", SH + "/range(address='3:4')/insert").json());
            pkg.excelInsertRange(ctx, tok, BOOK, "Data", "AA1", "entire_column", DR, b);
            assertEquals(Map.of("shift", "Right"), f.last("POST", SH + "/range(address='AA:AA')/insert").json());
            assertEquals("M365.BAD_OPTION", code(() -> pkg.excelDeleteRange(ctx, tok, BOOK, "Data", "A1", "entire_rows", DR, b)));
            assertEquals("M365.BAD_RANGE", code(() -> pkg.excelInsertRange(ctx, tok, BOOK, "Data", "1:2", "entire_row", DR, b)));
        }
    }

    @Test void excelGetOneTableRowByIndex() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", WB + "/tables/Sales/headerRowRange", c -> ok("{\"values\":[[\"Item\",\"Qty\"]]}"))
             .at("GET", WB + "/tables/Sales/rows/itemAt(index=2)", c -> ok("{\"index\":2,\"values\":[[\"Pens\",12]]}"));
            ActionContext ctx = ctx(tmp());
            Map<String, Object> r = pkg.excelGetTableRows(ctx, tok, BOOK, "Sales", DR, b, 3);
            assertEquals(new BigDecimal(1), r.get("count"));
            assertEquals(List.of("Item", "Qty"), tbl(r.get("table")).get("columns"));
            assertEquals(List.of("Pens", new BigDecimal(12)), rows(r.get("table")).get(0));
            assertTrue(f.all("GET", Pattern.quote(WB + "/tables/Sales/rows")).isEmpty(), "only the one row is read");
            assertEquals("M365.BAD_INPUT", code(() -> pkg.excelGetTableRows(ctx, tok, BOOK, "Sales", DR, b, 0)));
        }
    }

    @Test void excelCopyRangeValuesOrFormulasWithFormats() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", SH + "/range(address='A2:B3')", c -> ok("{\"values\":[[1,2],[3,5]],\"formulas\":[[1,\"=A2+1\"],[3,\"=A3+2\"]],\"numberFormat\":[[\"0.00\",\"General\"],[\"0.00\",\"General\"]]}"))
             .rx("PATCH", Pattern.quote(WB + "/worksheets('") + "(Data|Copy)" + Pattern.quote("')/range(address='") + "[A-Z0-9:]+'\\)", c -> ok("{}"));
            ActionContext ctx = ctx(tmp());
            assertEquals("H5:I6", pkg.excelCopyRange(ctx, tok, BOOK, "Data", "A2:B3", "h5", null, "values", true, DR, b).get("address"));
            assertTrue(f.last("GET", SH + "/range(address='A2:B3')").q().contains("$select=values,formulas,numberFormat"));
            Map<String, Object> body = f.last("PATCH", SH + "/range(address='H5:I6')").json();
            assertEquals(List.of(List.of(new BigDecimal(1), new BigDecimal(2)), List.of(new BigDecimal(3), new BigDecimal(5))), body.get("values"));
            assertEquals(List.of(List.of("0.00", "General"), List.of("0.00", "General")), body.get("numberFormat"));
            assertFalse(body.containsKey("formulas"));

            pkg.excelCopyRange(ctx, tok, BOOK, "Data", "A2:B3", "C1", "Copy", "formulas", false, DR, b);
            Map<String, Object> fb = f.last("PATCH", WB + "/worksheets('Copy')/range(address='C1:D2')").json();
            assertEquals(List.of(List.of(new BigDecimal(1), "=A2+1"), List.of(new BigDecimal(3), "=A3+2")), fb.get("formulas"));
            assertFalse(fb.containsKey("numberFormat"));

            assertEquals("M365.BAD_OPTION", code(() -> pkg.excelCopyRange(ctx, tok, BOOK, "Data", "A2:B3", "C1", null, "pictures", true, DR, b)));
            assertEquals("M365.BAD_RANGE", code(() -> pkg.excelCopyRange(ctx, tok, BOOK, "Data", "A2:B3", "C1:D2", null, "values", true, DR, b)));
        }
    }

    @Test void excelCopyWorksheetToAnotherWorkbook() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), wb2 = "/v1.0/me/drive/root:/Fin/archive.xlsx:/workbook";
            f.at("GET", SH + "/usedRange", c -> ok("{\"address\":\"Data!A1:B2\",\"rowCount\":2,\"formulas\":[[\"Item\",\"Total\"],[\"Pens\",\"=3*4\"]],\"numberFormat\":[[\"General\",\"General\"],[\"General\",\"0.00\"]]}"))
             .at("POST", wb2 + "/worksheets/add", c -> new Object[]{201, "{\"id\":\"s9\",\"name\":\"Data 2026\"}"})
             .at("PATCH", wb2 + "/worksheets('Data%202026')/range(address='A1:B2')", c -> ok("{}"));
            ActionContext ctx = ctx(tmp());
            Map<String, Object> r = pkg.excelCopySheet(ctx, tok, BOOK, "Data", "Fin/archive.xlsx", "Data 2026", DR, b);
            assertEquals(Map.of("sheet_name", "Data 2026", "address", "A1:B2"), r);
            assertEquals(Map.of("name", "Data 2026"), f.last("POST", wb2 + "/worksheets/add").json());
            Map<String, Object> body = f.last("PATCH", wb2 + "/worksheets('Data%202026')/range(address='A1:B2')").json();
            assertEquals(List.of(List.of("Item", "Total"), List.of("Pens", "=3*4")), body.get("formulas"));
            assertEquals(List.of(List.of("General", "General"), List.of("General", "0.00")), body.get("numberFormat"));

            int calls = f.calls.size();
            assertEquals("M365.BAD_INPUT", code(() -> pkg.excelCopySheet(ctx, tok, BOOK, "Data", null, "data", DR, b)), "same name in the same workbook");
            assertEquals(calls, f.calls.size(), "refused before any request");
        }
    }

    // ------------------------------------------------------------------- small options (2026-10-28)

    @Test void exportPdfOverwriteOption() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), content = "/v1.0/me/drive/root:/Docs/a.docx:/content";
            f.at("GET", content, c -> ok("%PDF-1.7"));
            Path w = tmp();
            Files.createDirectories(w.resolve("out"));
            Files.writeString(w.resolve("out/a.pdf"), "old");
            ActionContext ctx = ctx(w);

            Map<String, Object> kept = pkg.exportPdf(ctx, tok, "Docs/a.docx", "out/a.pdf", DR, b, false);
            assertEquals(w.resolve("out/a (2).pdf").toAbsolutePath().normalize(), Path.of((String) kept.get("path")).toAbsolutePath().normalize());
            assertEquals("old", Files.readString(w.resolve("out/a.pdf")), "existing PDF left alone");
            assertEquals("%PDF-1.7", Files.readString(w.resolve("out/a (2).pdf")));
            assertEquals("format=pdf", f.last("GET", content).q());

            pkg.exportPdf(ctx, tok, "Docs/a.docx", "out/a.pdf", DR, b);
            assertEquals("%PDF-1.7", Files.readString(w.resolve("out/a.pdf")), "old signature replaces the file as before");
        }
    }

    @Test void renameItemIfExists() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), it = "/v1.0/me/drive/items/I9";
            f.at("PATCH", it, c -> ok("{\"id\":\"I9\",\"webUrl\":\"https://od/I9\"}"));
            ActionContext ctx = ctx(tmp());

            pkg.renameItem(ctx, tok, "id:I9", "c.docx", DR, b, "replace");
            assertEquals("@microsoft.graph.conflictBehavior=replace", f.last("PATCH", it).q());
            assertEquals(Map.of("name", "c.docx"), f.last("PATCH", it).json());
            pkg.renameItem(ctx, tok, "id:I9", "c.docx", DR, b, "Rename");
            assertEquals("@microsoft.graph.conflictBehavior=rename", f.last("PATCH", it).q());
            pkg.renameItem(ctx, tok, "id:I9", "c.docx", DR, b, "fail");
            assertEquals("", f.last("PATCH", it).q(), "fail is Graph's default, so nothing is added");
            pkg.renameItem(ctx, tok, "id:I9", "c.docx", DR, b);
            assertEquals("", f.last("PATCH", it).q());

            int calls = f.calls.size();
            assertEquals("M365.BAD_OPTION", code(() -> pkg.renameItem(ctx, tok, "id:I9", "c.docx", DR, b, "skip")));
            assertEquals(calls, f.calls.size());
        }
    }

    @Test void findMeetingTimesOutsideWorkingHours() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), u = "/v1.0/me/findMeetingTimes";
            f.at("POST", u, c -> ok("{\"emptySuggestionsReason\":\"\",\"meetingTimeSuggestions\":[]}"));
            ActionContext ctx = ctx(tmp());
            LocalDateTime s = LocalDateTime.parse("2026-11-02T06:00"), e = LocalDateTime.parse("2026-11-02T22:00");

            pkg.findMeetingTimes(ctx, tok, "a@x.com", s, e, 30, "UTC", 5, "me", b, true);
            assertEquals("unrestricted", m(f.last("POST", u).json().get("timeConstraint")).get("activityDomain"));
            pkg.findMeetingTimes(ctx, tok, "a@x.com", s, e, 30, "UTC", 5, "me", b, false);
            assertEquals("work", m(f.last("POST", u).json().get("timeConstraint")).get("activityDomain"));
            pkg.findMeetingTimes(ctx, tok, "a@x.com", s, e, 30, "UTC", 5, "me", b);
            assertEquals("work", m(f.last("POST", u).json().get("timeConstraint")).get("activityDomain"), "old signature stays in working hours");
        }
    }

    @Test void channelByNameWithTeamName() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/me/joinedTeams", c -> ok("{\"value\":[{\"id\":\"T1\",\"displayName\":\"Finance\"},{\"id\":\"T2\",\"displayName\":\"Ops\"}]}"))
             .at("GET", "/v1.0/teams/T2/channels", c -> ok("{\"value\":[{\"id\":\"C5\",\"displayName\":\"General\"},{\"id\":\"C6\",\"displayName\":\"Shifts\"}]}"));
            ActionContext ctx = ctx(tmp());

            assertEquals("C6", pkg.channelByName(ctx, tok, null, "shifts", b, " OPS ").get("channel_id"));
            assertNotNull(f.last("GET", "/v1.0/me/joinedTeams"));
            assertNotNull(f.last("GET", "/v1.0/teams/T2/channels"));
            int joined = f.all("GET", ".*/me/joinedTeams").size();
            assertEquals("C5", pkg.channelByName(ctx, tok, "T2", "General", b).get("channel_id"));
            assertEquals(joined, f.all("GET", ".*/me/joinedTeams").size(), "team_id does not look the team up");

            int calls = f.calls.size();
            assertEquals("M365.BAD_INPUT", code(() -> pkg.channelByName(ctx, tok, "T2", "General", b, "Ops")));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.channelByName(ctx, tok, " ", "General", b, null)));
            assertEquals(calls, f.calls.size(), "refused before any request");
            assertEquals("M365.NOT_FOUND", code(() -> pkg.channelByName(ctx, tok, null, "General", b, "HR")));
        }
    }

    @Test void excelDeleteAndRenameWorksheetByPosition() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            // listed out of order; tab order is Summary (0), Data (1), Old (2)
            f.at("GET", WB + "/worksheets", c -> ok("{\"value\":[{\"id\":\"s2\",\"name\":\"Data\",\"position\":1},{\"id\":\"s3\",\"name\":\"Old\",\"position\":2},{\"id\":\"s1\",\"name\":\"Summary\",\"position\":0}]}"))
             .at("DELETE", WB + "/worksheets('Old')", c -> none())
             .at("DELETE", WB + "/worksheets('Summary')", c -> none())
             .at("PATCH", SH, c -> ok("{}"));
            ActionContext ctx = ctx(tmp());

            pkg.excelDeleteSheet(ctx, tok, BOOK, null, DR, b, 3);
            assertNotNull(f.last("DELETE", WB + "/worksheets('Old')"));
            pkg.excelDeleteSheet(ctx, tok, BOOK, "", DR, b, 1);
            assertNotNull(f.last("DELETE", WB + "/worksheets('Summary')"));
            pkg.excelRenameSheet(ctx, tok, BOOK, null, "Data 2026", DR, b, 2);
            assertEquals(Map.of("name", "Data 2026"), f.last("PATCH", SH).json());

            int lists = f.all("GET", ".*/worksheets").size();
            pkg.excelRenameSheet(ctx, tok, BOOK, "Data", "Data 2027", DR, b, null);
            assertEquals(Map.of("name", "Data 2027"), f.last("PATCH", SH).json());
            assertEquals(lists, f.all("GET", ".*/worksheets").size(), "by name does not list the sheets");

            int calls = f.calls.size();
            assertEquals("M365.BAD_INPUT", code(() -> pkg.excelDeleteSheet(ctx, tok, BOOK, "Data", DR, b, 2)));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.excelRenameSheet(ctx, tok, BOOK, null, "x", DR, b, null)));
            assertEquals(calls, f.calls.size(), "refused before any request");
            int deletes = f.all("DELETE", ".*").size();
            assertEquals("M365.NOT_FOUND", code(() -> pkg.excelDeleteSheet(ctx, tok, BOOK, null, DR, b, 4)));
            assertEquals("M365.NOT_FOUND", code(() -> pkg.excelRenameSheet(ctx, tok, BOOK, null, "x", DR, b, 0)));
            assertEquals(deletes, f.all("DELETE", ".*").size(), "nothing deleted for a position that does not exist");
        }
    }
}
