package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Microsoft 365 panel options found missing in the action-by-action panel review (Q8) that phase-2 did not already add, against the fake Graph server of Microsoft365GapsTest. */
class Microsoft365PanelOptionsTest {
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");
    static final String WB = "/v1.0/me/drive/root:/Fin/book.xlsx:/workbook", SH = WB + "/worksheets('Data')";
    static final String BOOK = "Fin/book.xlsx", DR = "me/drive";

    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    // ---------------------------------------------------------------------------------- Excel

    @Test void excelFindSearchesByRowsOrByColumns() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", SH + "/range(address='A1:B2')", c -> ok("{\"address\":\"Data!A1:B2\",\"text\":[[\"x\",\"acme\"],[\"Acme Ltd\",\"y\"]]}"));
            Path w = tmp();
            ActionContext ctx = ctx(w);
            String b = f.base();
            assertEquals(List.of("B1", "A2"), pkg.excelFind(ctx, tok, BOOK, "Data", "acme", "A1:B2", false, false, DR, b).get("cells"), "old signature reads across rows");
            assertEquals(List.of("A2", "B1"), pkg.excelFind(ctx, tok, BOOK, "Data", "acme", "A1:B2", false, false, DR, b, "Columns").get("cells"));
            Map<String, Object> o = Microsoft365OptionsTest.act("ooa.microsoft365", "access_token", "base_url", b, w, "excel_find",
                    Map.of("remote_path", BOOK, "sheet", "Data", "range", "A1:B2", "text", "acme", "search_by", "columns"), "cells");
            assertEquals(List.of("A2", "B1"), o.get("cells"), "search_by through the Runner");
            assertEquals("M365.BAD_OPTION", code(() -> pkg.excelFind(ctx, tok, BOOK, "Data", "acme", "A1:B2", false, false, DR, b, "diagonal")));
        }
    }

    @Test void excelColumnNameFromAnAddress() throws Exception {
        ActionContext ctx = ctx(tmp());
        assertEquals(Map.of("column_name", "AB", "column", new BigDecimal(28), "row", new BigDecimal(7)), pkg.excelColumnName(ctx, "ab7"));
        assertEquals("C", pkg.excelColumnName(ctx, "Sheet 1!$C$12").get("column_name"));
        assertEquals("M365.BAD_RANGE", code(() -> pkg.excelColumnName(ctx, "7B")));
        assertEquals("M365.BAD_RANGE", code(() -> pkg.excelColumnName(ctx, " ")));
    }

    // ------------------------------------------------------------------------------- Calendar

    @Test void clearEventDetailsBlanksOnlyTheTickedOnes() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), ev = "/v1.0/me/events/E1";
            f.at("GET", "/v1.0/me/events/OCC1", c -> ok("{\"type\":\"occurrence\",\"seriesMasterId\":\"MASTER\"}"))
             .at("GET", ev, c -> ok("{\"type\":\"seriesMaster\"}"))
             .rx("PATCH", Pattern.quote("/v1.0/me/events/") + "(E1|MASTER)", c -> ok("{\"webLink\":\"https://outlook/x\"}"));
            ActionContext ctx = ctx(tmp());

            assertEquals("https://outlook/x", pkg.clearEventDetails(ctx, tok, "E1", true, true, true, true, false, true, "me", b).get("web_link"));
            Map<String, Object> body = f.last("PATCH", ev).json();
            assertEquals("", body.get("subject"));
            assertEquals(Map.of("displayName", ""), body.get("location"));
            assertEquals(Map.of("contentType", "Text", "content", ""), body.get("body"));
            assertEquals(false, body.get("isReminderOn"));
            assertEquals(false, body.get("isAllDay"));
            assertFalse(body.containsKey("recurrence"), "unticked details are not sent");
            assertTrue(f.all("GET", ".*").isEmpty(), "no read needed without the repeat");

            pkg.clearEventDetails(ctx, tok, "OCC1", false, false, false, false, true, false, "me", b);
            Map<String, Object> rec = f.last("PATCH", "/v1.0/me/events/MASTER").json();
            assertTrue(rec.containsKey("recurrence") && rec.get("recurrence") == null, "repeat removed on the series master");
            assertEquals(1, rec.size());
            assertTrue(f.last("GET", "/v1.0/me/events/OCC1").q().contains("$select=type,seriesMasterId"));

            int patches = f.all("PATCH", ".*").size();
            assertEquals("M365.NOTHING_TO_DO", code(() -> pkg.clearEventDetails(ctx, tok, "E1", false, false, false, false, false, false, "me", b)));
            assertEquals(patches, f.all("PATCH", ".*").size());
        }
    }

    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
}
