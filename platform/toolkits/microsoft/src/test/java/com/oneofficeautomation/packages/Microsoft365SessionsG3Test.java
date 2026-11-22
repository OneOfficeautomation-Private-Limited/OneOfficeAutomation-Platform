package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.ok;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** G3 after the merge: sign in to Microsoft 365 once, open an Excel online workbook once, and the Teams chat-messages loop. */
class Microsoft365SessionsG3Test {
    static final String GRAPH = "https://graph.microsoft.com/v1.0";
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-365", "t");

    @Test void signInOnceAndOpenAWorkbookOnce() throws Exception {
        try (Fake f = new Fake()) {
            f.rx("GET", ".*/workbook/worksheets", c -> ok("{\"value\":[{\"name\":\"Summary\"},{\"name\":\"Data\"}]}"));
            f.rx("GET", ".*/range\\(address=.*", c -> ok("{\"values\":[[42]],\"text\":[[\"42\"]]}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            pkg.connect(ctx, tok, f.base(), null, false);
            Map<String, Object> o = pkg.excelOpen(ctx, null, "Finance/budget.xlsx", "Data", null, GRAPH, false);
            assertEquals(List.of("Summary", "Data"), o.get("sheets"));
            assertEquals("Data", o.get("active_sheet"));
            assertEquals("Bearer tok-365", f.all("GET", ".*/workbook/worksheets").get(0).headers().get("authorization"), "the kept sign-in, at the kept address");

            pkg.excelGoToCell(ctx, "B4", null);
            assertEquals(Map.of("cell", "B5"), pkg.excelGoToCell(ctx, null, "down"));
            assertEquals(42, ((Number) pkg.excelGetCell(ctx, null, null, null, null, null, GRAPH).get("value")).intValue());
            String path = f.all("GET", ".*/range\\(address=.*").get(0).path();
            assertTrue(path.contains("worksheets('Data')") && path.contains("address='B5'"), path);

            pkg.excelActivateSheet(ctx, "Summary");
            pkg.excelGetCell(ctx, null, null, null, "C7", null, GRAPH);
            assertTrue(f.all("GET", ".*/range\\(address=.*").get(1).path().contains("worksheets('Summary')"));

            assertEquals(Map.of("closed", true), pkg.excelClose(ctx));
            assertThrows(ActionException.class, () -> pkg.excelGetCell(ctx, null, null, null, "A1", null, GRAPH), "no workbook named and none open");
            assertEquals(Map.of("closed", true), pkg.disconnect(ctx, null));
            ActionException e = assertThrows(ActionException.class, () -> pkg.excelGoToCell(ctx, "A1", null));
            assertEquals("M365.NO_WORKBOOK", e.code());
            assertThrows(ActionException.class, () -> pkg.excelOpen(ctx, null, "Finance/budget.xlsx", "Nope", null, f.base(), false));
        }
    }

    @Test void stepsBetweenCells() {
        assertEquals("C1", Microsoft365Package.step("B1", "right"));
        assertEquals("A1", Microsoft365Package.step("A1", "left"));
        assertEquals("Z9", Microsoft365Package.step("Z10", "up"));
        assertEquals("AA3", Microsoft365Package.step("Z3", "right"));
    }

    @Test void eachChatMessageLeavesOutSystemEventsAndStopsAtSince() throws Exception {
        try (Fake f = new Fake()) {
            f.rx("GET", "/v1.0/chats/.*/messages", c -> ok("{\"value\":["
                    + "{\"id\":\"m3\",\"messageType\":\"message\",\"createdDateTime\":\"2026-11-21T10:00:00Z\",\"from\":{\"user\":{\"displayName\":\"Ann Lee\"}},\"body\":{\"content\":\"<p>Done</p>\"},\"attachments\":[{}]},"
                    + "{\"id\":\"m2\",\"messageType\":\"systemEventMessage\",\"createdDateTime\":\"2026-11-21T09:00:00Z\"},"
                    + "{\"id\":\"m1\",\"messageType\":\"message\",\"createdDateTime\":\"2026-11-19T09:00:00Z\",\"body\":{\"content\":\"old\"}}]}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            List<Object> rows = new ArrayList<>();
            pkg.eachChatMessage(ctx, tok, "19:abc@thread.v2", null, 100, f.base()).forEach(rows::add);
            assertEquals(2, rows.size());
            @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) rows.get(0);
            assertEquals("Ann Lee", first.get("from"));
            assertEquals("Done", first.get("text"));
            rows.clear();
            pkg.eachChatMessage(ctx, tok, "19:abc@thread.v2", LocalDateTime.of(2026, 11, 20, 0, 0), 100, f.base()).forEach(rows::add);
            assertEquals(1, rows.size(), "messages before since end the loop");
        }
    }
}
