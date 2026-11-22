package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.Microsoft365OptionsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G5 (AA parity, 22 Nov 2026), Google Workspace against a fake Google server: several labels, the oldest first, local
 * time, moving without keeping other labels, a label condition, Drive downloads without a path or with a new name, own
 * Drive rights, an owner hand-over, and the runner's time zone for events.
 */
class GoogleParityG5Test {
    static final String META = "{\"id\":\"G1\",\"labelIds\":[\"INBOX\",\"Label_7\"],\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"a@b.com\"},{\"name\":\"Subject\",\"value\":\"S\"},"
            + "{\"name\":\"Date\",\"value\":\"Mon, 2 Nov 2026 10:00:00 +0000\"}]}}";

    @SuppressWarnings("unchecked")
    @Test void gmailOptions() throws Exception {
        assertEquals("{label:INBOX label:Invoices}", GooglePackage.gmailQuery(null, null, null, null, null, "any", "INBOX; Invoices", false));
        try (Fake f = new Fake()) {
            String gm = "/gmail/v1/users/me/";
            f.on("GET " + gm + "messages", c -> ok("{\"messages\":[{\"id\":\"G1\"},{\"id\":\"G2\"}]}"))
             .on("GET " + gm + "messages/G[12]", c -> ok(META.replace("G1", c.path().endsWith("G2") ? "G2" : "G1")))
             .on("POST " + gm + "messages/G[12]/modify", c -> ok("{}"))
             .on("GET " + gm + "labels", c -> ok("{\"labels\":[{\"id\":\"Label_9\",\"name\":\"Done\"},{\"id\":\"INBOX\",\"name\":\"INBOX\"}]}"));
            var p = new GooglePackage();
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            var tok = new com.oneofficeautomation.sdk.SecureString("tok", "t");
            Map<String, Object> table = (Map<String, Object>) p.gmailList(ctx, tok, null, 10, false, null, null, null, null, "any", "INBOX", false, f.url(), true, true).get("table");
            List<List<Object>> rows = (List<List<Object>>) table.get("rows");
            assertEquals("G2", rows.get(0).get(0), "oldest first");
            LocalDateTime local = LocalDateTime.of(2026, 11, 2, 10, 0).atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
            assertEquals(local, rows.get(0).get(9), "received in the runner's zone");

            p.gmailMoveAll(ctx, tok, "Done", "INBOX", null, null, null, null, null, "any", "INBOX", 10, f.url(), false);
            Map<String, Object> body = f.last("POST", gm + "messages/G1/modify").json();
            assertEquals(List.of("Label_9"), body.get("addLabelIds"));
            assertTrue(((List<Object>) body.get("removeLabelIds")).containsAll(List.of("INBOX", "Label_7")), "the other own label went too: " + body);

            assertTrue(p.gmailLabelExistsCondition(ctx, tok, "done", f.url()));
            assertFalse(p.gmailLabelExistsCondition(ctx, tok, "Archive", f.url()));
        }
    }

    @Test void driveAndCalendar() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files/F1", c -> q(c).contains("capabilities") ? ok("{\"capabilities\":{\"canEdit\":true,\"canComment\":true,\"canDelete\":false,\"canTrash\":true}}")
                    : q(c).contains("fields=") ? ok("{\"mimeType\":\"application/pdf\",\"name\":\"report.pdf\"}") : ok("%PDF data"))
             .on("POST /drive/v3/files/F1/permissions", c -> ok("{\"id\":\"P1\"}"))
             .on("POST /calendar/v3/calendars/primary/events", c -> ok("{\"id\":\"E1\"}"));
            var p = new GooglePackage();
            Path w = tmp();
            var ctx = PackagesTestSupport.ctx(w, Map.of());
            var tok = new com.oneofficeautomation.sdk.SecureString("tok", "t");
            String saved = String.valueOf(p.driveDownload(ctx, tok, "F1", null, false, f.url(), null).get("path"));
            assertTrue(saved.endsWith("report.pdf") && saved.contains(".ooa-temp"), saved);
            String named = String.valueOf(p.driveDownload(ctx, tok, "F1", "out", false, f.url(), "q3.pdf").get("path"));
            assertEquals(w.resolve("out").resolve("q3.pdf").toString(), named);
            assertEquals("%PDF data", Files.readString(Path.of(named)));

            assertEquals(true, p.driveCheckPermission(ctx, tok, "F1", null, "delete", f.url()).get("has_access"), "trash counts as delete");
            assertEquals("writer", p.driveCheckPermission(ctx, tok, "F1", null, "write", f.url()).get("role"));

            p.driveShare(ctx, tok, "F1", "user", "owner", "ann@acme.com", null, false, null, null, f.url());
            assertTrue(q(f.last("POST", "/drive/v3/files/F1/permissions")).contains("transferOwnership=true"));

            p.createEvent(ctx, tok, "Stand-up", LocalDateTime.of(2026, 12, 1, 9, 0), LocalDateTime.of(2026, 12, 1, 9, 15), "runner", null, null, "primary", null, false, null,
                    false, false, null, "default", "busy", null, f.url(), null);
            Map<String, Object> ev = f.last("POST", "/calendar/v3/calendars/primary/events").json();
            assertEquals(java.time.ZoneId.systemDefault().getId(), ((Map<?, ?>) ev.get("start")).get("timeZone"));
        }
    }

    @Test void aSpreadsheetSessionWithACurrentCell() throws Exception {
        try (Fake f = new Fake()) {
            String sid = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdEF";
            f.on("GET /v4/spreadsheets/" + sid, c -> ok("{\"sheets\":[{\"properties\":{\"title\":\"Sales\",\"sheetId\":0}},{\"properties\":{\"title\":\"Costs\",\"sheetId\":1}}]}"))
             .on("PUT /v4/spreadsheets/" + sid + "/values/.*", c -> ok("{}"))
             .on("GET /v4/spreadsheets/" + sid + "/values/.*", c -> ok("{\"values\":[[42]]}"));
            var p = new GooglePackage();
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            var tok = new com.oneofficeautomation.sdk.SecureString("tok", "t");
            assertEquals(List.of("Sales", "Costs"), p.sheetsOpen(ctx, tok, sid, "Costs", f.url()).get("sheets"));
            p.sheetsGoToCell(ctx, "C4", null);
            p.sheetsSetCell(ctx, null, null, null, 7, null);
            assertTrue(f.last("PUT", "/v4/spreadsheets/" + sid + "/values/.*").path().contains("Costs") && f.last("PUT", "/v4/spreadsheets/" + sid + "/values/.*").path().contains("C4"),
                    f.last("PUT", "/v4/spreadsheets/" + sid + "/values/.*").path());
            assertEquals(new java.math.BigDecimal(42), new java.math.BigDecimal(String.valueOf(p.sheetsGetCell(ctx, null, null, null, false, null).get("value"))));
            p.sheetsGoToCell(ctx, "Sales!B2", null);
            p.sheetsSetFormula(ctx, null, null, null, "SUM(A1:A3)", null);
            assertTrue(f.last("PUT", "/v4/spreadsheets/" + sid + "/values/.*").path().contains("Sales"));
            p.sheetsClose(ctx);
            assertEquals("GOOGLE.BAD_INPUT", assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> p.sheetsGoToCell(ctx, "A1", null)).code());
        }
    }
}
