package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Google Sheets, Drive, Gmail and Calendar actions against a local server that imitates Google's REST APIs. */
class GoogleGapsTest {

    /** Runs one ooa.google action with the given arguments; returns its outputs by output name. */
    static Map<String, Object> act(Fake f, Path w, String action, Map<String, Object> args, String... outs) {
        StringBuilder vars = new StringBuilder("[" + TOKEN_VAR);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String o : outs) { vars.append(",{\"name\":\"").append(o).append("\",\"type\":\"Any\",\"direction\":\"output\"}"); out.put(o, o); }
        vars.append("]");
        return run(w, vars.toString(), "[" + step(f, action, args, out) + "]", TOKEN);
    }

    static Map<String, Object> err(Fake f, String action, Map<String, Object> args) throws Exception {
        RunResult r = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[" + step(f, action, args, Map.of()) + "]");
        assertFalse(r.ok(), action + " should fail");
        return r.error();
    }

    static String step(Fake f, String action, Map<String, Object> args, Map<String, Object> out) {
        Map<String, Object> a = new LinkedHashMap<>(args);
        a.put("access_token", "$tok");
        a.put("endpoint_override", f.url());
        return JsonLite.write(Map.of("id", "s", "action", "ooa.google/" + action, "args", a, "out", out));
    }

    @SuppressWarnings("unchecked") static Map<String, Object> json(Call c) { return (Map<String, Object>) JsonLite.parse(c.text()); }
    @SuppressWarnings("unchecked") static Map<String, Object> req0(Call c) { return (Map<String, Object>) ((List<?>) json(c).get("requests")).get(0); }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<List<Object>> rows(Object table) { return (List<List<Object>>) m(table).get("rows"); }
    static String path(Call c) { return URLDecoder.decode(c.path(), StandardCharsets.UTF_8); }
    static int n(Object o) { return ((Number) o).intValue(); }
    static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    static final String SHEETS_META = "{\"sheets\":[{\"properties\":{\"sheetId\":0,\"title\":\"Data\",\"index\":0,\"gridProperties\":{\"rowCount\":1000,\"columnCount\":26}}},"
            + "{\"properties\":{\"sheetId\":77,\"title\":\"Old Tab\",\"index\":1,\"hidden\":true,\"gridProperties\":{\"rowCount\":50,\"columnCount\":5}}}]}";

    static Fake sheetsFake() throws Exception {
        Fake f = new Fake();
        f.on("POST /v4/spreadsheets", c -> ok("{\"spreadsheetId\":\"NEW1\",\"spreadsheetUrl\":\"https://docs/NEW1\"}"))
         .on("GET /v4/spreadsheets/S1", c -> q(c).contains("ranges=")
                 ? ok("{\"sheets\":[{\"data\":[{\"rowData\":[{\"values\":[{\"effectiveFormat\":{\"backgroundColor\":{\"red\":1,\"green\":0.5},\"textFormat\":{\"foregroundColor\":{\"blue\":1}}}}]}]}]}]}")
                 : ok(SHEETS_META))
         .on("POST /v4/spreadsheets/S1:batchUpdate", c -> ok("{\"replies\":[{\"addSheet\":{\"properties\":{\"sheetId\":99,\"title\":\"New\"}}}]}"))
         .on("POST /v4/spreadsheets/S1/values/.*:clear", c -> ok("{}"))
         .on("PUT /v4/spreadsheets/S1/values/.*", c -> ok("{\"updatedRange\":\"Data!A1:B3\",\"updatedCells\":6}"))
         .on("GET /v4/spreadsheets/S1/values/.*", c -> {
             String p = path(c).replaceFirst("^/v4/spreadsheets/S1/values/", "");
             if (q(c).contains("valueRenderOption=FORMULA")) return ok("{\"values\":[[\"=SUM(A1:A2)\"]]}");
             return switch (p) {
                 case "Data!B2" -> ok("{\"values\":[[42]]}");
                 case "Data!A1:B3" -> ok("{\"range\":\"Data!A1:B3\",\"values\":[[\"Name\",\"Amount\"],[\"Acme\",10],[\"Globex\"]]}");
                 case "'Data'!3:3" -> ok("{\"values\":[[\"Globex\",\"\",7]]}");
                 case "'Data'!B:B" -> ok("{\"values\":[[\"Amount\",10]]}");
                 case "'Data'" -> ok("{\"range\":\"Data!A1:C4\",\"values\":[[\"Name\",\"Amount\"],[\"Acme\",10],[],[\"acme corp\",\"x\",\"Acme\"]]}");
                 case "Data!B2:C9" -> ok("{\"range\":\"Data!B2:C9\",\"values\":[[\"x\"],[\"y\",\"acme\"]]}");
                 case "Data!Z9" -> ok("{\"range\":\"Data!Z9\"}");
                 default -> new Object[]{400, "{\"error\":{\"message\":\"Unable to parse range: " + p + "\"}}"};
             };
         });
        return f;
    }

    @Test void sheetsStructureActionsSendBatchUpdates() throws Exception {
        try (Fake f = sheetsFake()) {
            Path w = tmp();
            Map<String, Object> o = act(f, w, "sheets_create_spreadsheet", Map.of("title", "Report", "sheet_names", List.of("Data", "Summary")), "spreadsheet_id", "url");
            assertEquals("NEW1", o.get("spreadsheet_id"));
            assertEquals("https://docs/NEW1", o.get("url"));
            Map<String, Object> created = json(f.last("POST", "/v4/spreadsheets"));
            assertEquals("Report", m(created.get("properties")).get("title"));
            assertEquals(2, ((List<?>) created.get("sheets")).size());

            o = act(f, w, "sheets_list_sheets", Map.of("spreadsheet_id", "S1"), "table", "names");
            assertEquals(List.of("Data", "Old Tab"), o.get("names"));
            List<List<Object>> sheetRows = rows(o.get("table"));
            assertEquals(77, n(sheetRows.get(1).get(0)));
            assertEquals(true, sheetRows.get(1).get(3));
            assertTrue(q(f.last("GET", "/v4/spreadsheets/S1")).contains("fields=sheets.properties"));

            o = act(f, w, "sheets_add_sheet", Map.of("spreadsheet_id", "S1", "sheet", "New", "position", 2), "sheet_id");
            assertEquals(99, n(o.get("sheet_id")));
            Map<String, Object> add = m(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("addSheet")).get("properties"));
            assertEquals("New", add.get("title"));
            assertEquals(1, n(add.get("index")));

            act(f, w, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "sheet", "old tab"));
            assertEquals(77, n(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("deleteSheet")).get("sheetId")), "sheet name matched case-insensitively");

            act(f, w, "sheets_rename_sheet", Map.of("spreadsheet_id", "S1", "sheet", "Old Tab", "new_name", "Archive"));
            Map<String, Object> ren = m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("updateSheetProperties"));
            assertEquals("title", ren.get("fields"));
            assertEquals("Archive", m(ren.get("properties")).get("title"));
            assertEquals(77, n(m(ren.get("properties")).get("sheetId")));

            act(f, w, "sheets_hide_sheet", Map.of("spreadsheet_id", "S1", "sheet", "Data"));
            Map<String, Object> hide = m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("updateSheetProperties"));
            assertEquals("hidden", hide.get("fields"));
            assertEquals(true, m(hide.get("properties")).get("hidden"));
            act(f, w, "sheets_show_sheet", Map.of("spreadsheet_id", "S1", "sheet", "Old Tab"));
            assertEquals(false, m(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("updateSheetProperties")).get("properties")).get("hidden"));

            act(f, w, "sheets_insert_rows", Map.of("spreadsheet_id", "S1", "row", 3, "count", 2));
            Map<String, Object> ins = m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("insertDimension"));
            assertEquals(Map.of("sheetId", 0, "dimension", "ROWS", "startIndex", 2, "endIndex", 4), norm(m(ins.get("range"))));
            assertEquals(true, ins.get("inheritFromBefore"));
            act(f, w, "sheets_delete_rows", Map.of("spreadsheet_id", "S1", "sheet", "Old Tab", "row", 1));
            assertEquals(Map.of("sheetId", 77, "dimension", "ROWS", "startIndex", 0, "endIndex", 1),
                    norm(m(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("deleteDimension")).get("range"))));
            act(f, w, "sheets_insert_columns", Map.of("spreadsheet_id", "S1", "column", "AA", "count", 3));
            assertEquals(Map.of("sheetId", 0, "dimension", "COLUMNS", "startIndex", 26, "endIndex", 29),
                    norm(m(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("insertDimension")).get("range"))));
            act(f, w, "sheets_delete_columns", Map.of("spreadsheet_id", "S1", "column", "2"));
            assertEquals(Map.of("sheetId", 0, "dimension", "COLUMNS", "startIndex", 1, "endIndex", 2),
                    norm(m(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("deleteDimension")).get("range"))));

            act(f, w, "sheets_delete_range", Map.of("spreadsheet_id", "S1", "range", "'Old Tab'!B2:C4", "shift", "left"));
            Map<String, Object> del = m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("deleteRange"));
            assertEquals("COLUMNS", del.get("shiftDimension"));
            assertEquals(Map.of("sheetId", 77, "startRowIndex", 1, "endRowIndex", 4, "startColumnIndex", 1, "endColumnIndex", 3), norm(m(del.get("range"))));
            act(f, w, "sheets_insert_range", Map.of("spreadsheet_id", "S1", "range", "A5:A6"));
            Map<String, Object> ir = m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("insertRange"));
            assertEquals("ROWS", ir.get("shiftDimension"), "default shift is down");
            assertEquals(Map.of("sheetId", 0, "startRowIndex", 4, "endRowIndex", 6, "startColumnIndex", 0, "endColumnIndex", 1), norm(m(ir.get("range"))));

            act(f, w, "sheets_autofit_columns", Map.of("spreadsheet_id", "S1", "from_column", "A", "to_column", "D"));
            assertEquals(Map.of("sheetId", 0, "dimension", "COLUMNS", "startIndex", 0, "endIndex", 4),
                    norm(m(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("autoResizeDimensions")).get("dimensions"))));
            act(f, w, "sheets_autofit_rows", Map.of("spreadsheet_id", "S1", "sheet", "Old Tab"));
            assertEquals(Map.of("sheetId", 77, "dimension", "ROWS"),
                    norm(m(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("autoResizeDimensions")).get("dimensions"))), "no bounds means the whole sheet");

            act(f, w, "sheets_format_cells", Map.of("spreadsheet_id", "S1", "range", "Data!A1:D1", "bold", true, "background_color", "#1F4E79",
                    "font_color", "#ffffff", "horizontal_alignment", "center", "number_format", "#,##0.00"));
            Map<String, Object> rc = m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("repeatCell"));
            assertEquals("userEnteredFormat.textFormat.bold,userEnteredFormat.textFormat.foregroundColor,userEnteredFormat.backgroundColor,"
                    + "userEnteredFormat.horizontalAlignment,userEnteredFormat.numberFormat", rc.get("fields"));
            Map<String, Object> fmt = m(m(rc.get("cell")).get("userEnteredFormat"));
            assertEquals(true, m(fmt.get("textFormat")).get("bold"));
            assertEquals(1.0, ((Number) m(m(fmt.get("textFormat")).get("foregroundColor")).get("red")).doubleValue(), 1e-9);
            assertEquals(0x4E / 255.0, ((Number) m(fmt.get("backgroundColor")).get("green")).doubleValue(), 1e-9);
            assertEquals("CENTER", fmt.get("horizontalAlignment"));
            assertEquals(Map.of("type", "NUMBER", "pattern", "#,##0.00"), fmt.get("numberFormat"));
            assertEquals(Map.of("sheetId", 0, "startRowIndex", 0, "endRowIndex", 1, "startColumnIndex", 0, "endColumnIndex", 4), norm(m(rc.get("range"))));

            assertEquals("GOOGLE.NO_SHEET", err(f, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "sheet", "Nope")).get("code"));
            assertEquals("GOOGLE.NOTHING_TO_FORMAT", err(f, "sheets_format_cells", Map.of("spreadsheet_id", "S1", "range", "A1")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_format_cells", Map.of("spreadsheet_id", "S1", "range", "A1", "background_color", "red")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_insert_rows", Map.of("spreadsheet_id", "S1", "row", 0)).get("code"));
            assertEquals("FLOW.INVALID", err(f, "sheets_delete_range", Map.of("spreadsheet_id", "S1", "range", "A1:B2", "shift", "down")).get("code"));
            assertEquals("GOOGLE.NOT_FOUND", err(f, "sheets_list_sheets", Map.of("spreadsheet_id", "S404")).get("code"));
        }
    }

    /** Numbers in parsed JSON are BigDecimal; compare as ints. */
    static Map<String, Object> norm(Map<String, Object> mm) {
        Map<String, Object> out = new LinkedHashMap<>();
        mm.forEach((k, v) -> out.put(k, v instanceof Number num ? (Object) num.intValue() : v));
        return out;
    }

    @Test void sheetsValueActionsReadWriteAndSearch() throws Exception {
        try (Fake f = sheetsFake()) {
            Path w = tmp();
            assertEquals(42, n(act(f, w, "sheets_get_cell", Map.of("spreadsheet_id", "S1", "cell", "Data!B2"), "value").get("value")));
            assertTrue(q(f.last("GET", "/v4/spreadsheets/S1/values/.*")).contains("valueRenderOption=UNFORMATTED_VALUE"));
            assertNull(act(f, w, "sheets_get_cell", Map.of("spreadsheet_id", "S1", "cell", "Data!Z9"), "value").get("value"));

            act(f, w, "sheets_set_cell", Map.of("spreadsheet_id", "S1", "cell", "Data!C3", "value", "Paid"));
            Call put = f.last("PUT", "/v4/spreadsheets/S1/values/.*");
            assertEquals("/v4/spreadsheets/S1/values/Data!C3", path(put));
            assertTrue(q(put).contains("valueInputOption=USER_ENTERED"));
            assertEquals(List.of(List.of("Paid")), json(put).get("values"));

            Map<String, Object> o = act(f, w, "sheets_get_range", Map.of("spreadsheet_id", "S1", "range", "Data!A1:B3"), "values", "table");
            assertEquals(3, ((List<?>) o.get("values")).size());
            assertEquals(List.of("Name", "Amount"), m(o.get("table")).get("columns"));
            assertEquals(Arrays.asList("Globex", null), rows(o.get("table")).get(1), "short rows padded");

            act(f, w, "sheets_clear_range", Map.of("spreadsheet_id", "S1", "range", "Data!A2:D100"));
            assertEquals("/v4/spreadsheets/S1/values/Data!A2:D100:clear", path(f.last("POST", "/v4/spreadsheets/S1/values/.*")));

            assertEquals(Arrays.asList("Globex", "", new java.math.BigDecimal(7)), act(f, w, "sheets_read_row", Map.of("spreadsheet_id", "S1", "row", 3), "values").get("values"));
            List<?> col = (List<?>) act(f, w, "sheets_read_column", Map.of("spreadsheet_id", "S1", "sheet", "Data", "column", "b"), "values").get("values");
            assertEquals("Amount", col.get(0));
            assertTrue(q(f.last("GET", "/v4/spreadsheets/S1/values/.*")).contains("majorDimension=COLUMNS"));

            o = act(f, w, "sheets_row_count", Map.of("spreadsheet_id", "S1"), "rows", "columns");
            assertEquals(4, n(o.get("rows")));
            assertEquals(3, n(o.get("columns")));

            act(f, w, "sheets_set_formula", Map.of("spreadsheet_id", "S1", "cell", "Data!D2", "formula", "SUM(B2:C2)"));
            assertEquals(List.of(List.of("=SUM(B2:C2)")), json(f.last("PUT", "/v4/spreadsheets/S1/values/.*")).get("values"));
            assertEquals("=SUM(A1:A2)", act(f, w, "sheets_read_formula", Map.of("spreadsheet_id", "S1", "cell", "Data!A3"), "formula").get("formula"));

            o = act(f, w, "sheets_get_cell_color", Map.of("spreadsheet_id", "S1", "cell", "B2"), "background_color", "font_color");
            assertEquals("#FF8000", o.get("background_color"));
            assertEquals("#0000FF", o.get("font_color"));
            String colorQuery = q(f.last("GET", "/v4/spreadsheets/S1"));
            assertTrue(colorQuery.contains("ranges='Data'!B2"), colorQuery);
            assertTrue(colorQuery.contains("effectiveFormat"));

            Map<String, Object> table = Map.of("columns", List.of("Name", "Amount"), "rows", List.of(List.of("Acme", 10), List.of("Globex", 20)));
            o = act(f, w, "sheets_write_data_table", Map.of("spreadsheet_id", "S1", "sheet", "Data", "start_cell", "B2", "table", table, "clear_sheet_first", true), "updated_range", "updated_cells");
            assertEquals("Data!A1:B3", o.get("updated_range"));
            assertEquals(6, n(o.get("updated_cells")));
            assertEquals("/v4/spreadsheets/S1/values/'Data':clear", path(f.last("POST", "/v4/spreadsheets/S1/values/.*")));
            Call wput = f.last("PUT", "/v4/spreadsheets/S1/values/.*");
            assertEquals("/v4/spreadsheets/S1/values/'Data'!B2", path(wput));
            assertEquals(3, ((List<?>) json(wput).get("values")).size(), "header plus two rows");

            o = act(f, w, "sheets_find_cells", Map.of("spreadsheet_id", "S1", "text", "acme"), "cells", "count");
            assertEquals(List.of("A2", "A4", "C4"), o.get("cells"));
            assertEquals(3, n(o.get("count")));
            assertEquals(List.of("A2", "C4"), act(f, w, "sheets_find_cells", Map.of("spreadsheet_id", "S1", "range", "Data", "text", "Acme", "match_case", true), "cells").get("cells"));
            assertEquals(List.of("A2", "C4"), act(f, w, "sheets_find_cells", Map.of("spreadsheet_id", "S1", "text", "acme", "whole_cell", true), "cells").get("cells"));
            assertEquals(List.of("C3"), act(f, w, "sheets_find_cells", Map.of("spreadsheet_id", "S1", "range", "Data!B2:C9", "text", "acme"), "cells").get("cells"),
                    "addresses are offset by the range start");

            Map<String, Object> e = err(f, "sheets_get_range", Map.of("spreadsheet_id", "S1", "range", "Bad!!"));
            assertEquals("GOOGLE.REQUEST_FAILED", e.get("code"));
            assertTrue(String.valueOf(e.get("message")).contains("Unable to parse range"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_write_data_table", Map.of("spreadsheet_id", "S1", "sheet", "Data", "start_cell", "1A", "table", table)).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_find_cells", Map.of("spreadsheet_id", "S1", "text", "")).get("code"));
        }
    }

    @Test void driveFoldersFilesPermissionsAndExport() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /drive/v3/files", c -> ok("{\"id\":\"FOLD1\",\"webViewLink\":\"https://drive/FOLD1\"}"))
             .on("POST /drive/v3/files/F1/copy", c -> ok("{\"id\":\"F2\"}"))
             .on("GET /drive/v3/files/F1", c -> q(c).contains("fields=parents") ? ok("{\"parents\":[\"P0\"]}")
                     : ok("{\"id\":\"F1\",\"name\":\"Q4.pdf\",\"mimeType\":\"application/pdf\",\"size\":\"2048\",\"createdTime\":\"2026-01-01T00:00:00Z\","
                        + "\"modifiedTime\":\"2026-02-01T00:00:00Z\",\"parents\":[\"P0\"],\"webViewLink\":\"https://drive/F1\",\"owners\":[{\"emailAddress\":\"ann@acme.com\"}],\"trashed\":false}"))
             .on("PATCH /drive/v3/files/F1", c -> ok("{}"))
             .on("DELETE /drive/v3/files/F1", c -> new Object[]{204, ""})
             .on("GET /drive/v3/files", c -> q(c).contains("pageToken=pg2")
                     ? ok("{\"files\":[{\"id\":\"D2\",\"name\":\"Invoices 2026\",\"mimeType\":\"application/vnd.google-apps.folder\"}]}")
                     : ok("{\"files\":[{\"id\":\"D1\",\"name\":\"Invoices\",\"mimeType\":\"application/vnd.google-apps.folder\",\"webViewLink\":\"https://drive/D1\"}],\"nextPageToken\":\"pg2\"}"))
             .on("GET /drive/v3/files/F1/permissions", c -> ok("{\"permissions\":[{\"id\":\"p1\",\"type\":\"user\",\"role\":\"owner\",\"emailAddress\":\"ann@acme.com\",\"displayName\":\"Ann\"},"
                     + "{\"id\":\"anyoneWithLink\",\"type\":\"anyone\",\"role\":\"reader\"}]}"))
             .on("POST /drive/v3/files/F1/permissions", c -> ok("{\"id\":\"perm9\"}"))
             .on("DELETE /drive/v3/files/F1/permissions/perm9", c -> new Object[]{204, ""})
             .on("GET /drive/v3/files/DOC1/export", c -> new Object[]{200, "%PDF-1.7 exported".getBytes(StandardCharsets.UTF_8)})
             .on("GET /drive/v3/files/GONE/export", c -> new Object[]{404, "{\"error\":{\"message\":\"File not found: GONE.\"}}"});
            Path w = tmp();

            Map<String, Object> o = act(f, w, "drive_create_folder", Map.of("name", "Invoices", "parent_id", "P0"), "folder_id", "web_link");
            assertEquals("FOLD1", o.get("folder_id"));
            Map<String, Object> folder = json(f.last("POST", "/drive/v3/files"));
            assertEquals("application/vnd.google-apps.folder", folder.get("mimeType"));
            assertEquals(List.of("P0"), folder.get("parents"));

            assertEquals("F2", act(f, w, "drive_copy_file", Map.of("file_id", "F1", "name", "Q4 copy.pdf", "folder_id", "D9"), "file_id").get("file_id"));
            Map<String, Object> copy = json(f.last("POST", "/drive/v3/files/F1/copy"));
            assertEquals("Q4 copy.pdf", copy.get("name"));
            assertEquals(List.of("D9"), copy.get("parents"));

            act(f, w, "drive_move_item", Map.of("item_id", "F1", "folder_id", "D9"));
            String mq = q(f.last("PATCH", "/drive/v3/files/F1"));
            assertTrue(mq.contains("addParents=D9") && mq.contains("removeParents=P0"), mq);

            act(f, w, "drive_rename_item", Map.of("item_id", "F1", "new_name", "Q4 final.pdf"));
            assertEquals(Map.of("name", "Q4 final.pdf"), json(f.last("PATCH", "/drive/v3/files/F1")));

            act(f, w, "drive_delete_item", Map.of("item_id", "F1"));
            assertEquals(Map.of("trashed", true), json(f.last("PATCH", "/drive/v3/files/F1")), "default is the trash");
            assertNull(f.last("DELETE", "/drive/v3/files/F1"));
            act(f, w, "drive_delete_item", Map.of("item_id", "F1", "permanent", true));
            assertNotNull(f.last("DELETE", "/drive/v3/files/F1"));

            o = act(f, w, "drive_find_items", Map.of("name_contains", "Invoice's", "folder_id", "P0", "item_type", "folder"), "table", "count");
            assertEquals(2, n(o.get("count")), "follows nextPageToken");
            assertEquals(List.of("D1", "D2"), rows(o.get("table")).stream().map(r -> r.get(0)).toList());
            assertEquals(true, rows(o.get("table")).get(0).get(3));
            String fq = q(f.calls.stream().filter(c -> c.method().equals("GET") && c.path().equals("/drive/v3/files")).findFirst().orElseThrow());
            assertTrue(fq.contains("trashed = false") && fq.contains("name contains 'Invoice\\'s'") && fq.contains("'P0' in parents")
                    && fq.contains("mimeType = 'application/vnd.google-apps.folder'"), fq);

            Map<String, Object> info = m(act(f, w, "drive_get_file_info", Map.of("item_id", "F1"), "info").get("info"));
            assertEquals("Q4.pdf", info.get("name"));
            assertEquals(2048, n(info.get("size")));
            assertEquals(List.of("ann@acme.com"), info.get("owners"));
            assertEquals(false, info.get("is_folder"));

            List<List<Object>> perms = rows(act(f, w, "drive_list_permissions", Map.of("item_id", "F1"), "table").get("table"));
            assertEquals(2, perms.size());
            assertEquals(Arrays.asList("p1", "user", "owner", "ann@acme.com", null, "Ann"), perms.get(0));

            assertEquals("perm9", act(f, w, "drive_create_permission", Map.of("item_id", "F1", "email", "bob@corp.com", "role", "writer", "message", "FYI"), "permission_id").get("permission_id"));
            Call share = f.last("POST", "/drive/v3/files/F1/permissions");
            assertEquals(Map.of("type", "user", "role", "writer", "emailAddress", "bob@corp.com"), json(share));
            assertTrue(q(share).contains("sendNotificationEmail=true") && q(share).contains("emailMessage=FYI"));
            act(f, w, "drive_create_permission", Map.of("item_id", "F1", "share_with", "anyone"));
            Call anyone = f.last("POST", "/drive/v3/files/F1/permissions");
            assertEquals(Map.of("type", "anyone", "role", "reader"), json(anyone));
            assertFalse(q(anyone).contains("sendNotificationEmail"));

            act(f, w, "drive_delete_permission", Map.of("item_id", "F1", "permission_id", "perm9"));
            assertNotNull(f.last("DELETE", "/drive/v3/files/F1/permissions/perm9"));

            String saved = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "format", "docx", "local_path", "out/doc.docx"), "path").get("path");
            assertEquals("%PDF-1.7 exported", Files.readString(Path.of(saved)));
            assertTrue(q(f.last("GET", "/drive/v3/files/DOC1/export")).contains("mimeType=application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_create_permission", Map.of("item_id", "F1", "share_with", "group")).get("code"));
            assertEquals("FLOW.INVALID", err(f, "drive_download_export", Map.of("file_id", "DOC1", "format", "rtf2", "local_path", "x")).get("code"));
            Map<String, Object> e = err(f, "drive_download_export", Map.of("file_id", "GONE", "local_path", "x.pdf"));
            assertEquals("GOOGLE.NOT_FOUND", e.get("code"));
            assertTrue(String.valueOf(e.get("message")).contains("File not found: GONE."), "service message kept for binary downloads");
        }
    }

    static final String HTML = "<p>Hello team</p>";

    static String fullMessage(String id) {
        return "{\"id\":\"" + id + "\",\"threadId\":\"T1\",\"labelIds\":[\"INBOX\",\"UNREAD\"],\"snippet\":\"Hello team\",\"payload\":{\"mimeType\":\"multipart/mixed\",\"headers\":["
                + "{\"name\":\"From\",\"value\":\"Ann <ann@acme.com>\"},{\"name\":\"To\",\"value\":\"me@corp.com, \\\"Bob, B.\\\" <bob@corp.com>\"},"
                + "{\"name\":\"Cc\",\"value\":\"carol@corp.com\"},{\"name\":\"Subject\",\"value\":\"Invoice 7\"},{\"name\":\"Date\",\"value\":\"Mon, 19 Oct 2026 09:00:00 +0000\"},"
                + "{\"name\":\"Message-ID\",\"value\":\"<m1@acme.com>\"},{\"name\":\"References\",\"value\":\"<m0@acme.com>\"}],"
                + "\"parts\":[{\"mimeType\":\"multipart/alternative\",\"filename\":\"\",\"parts\":["
                + "{\"mimeType\":\"text/plain\",\"filename\":\"\",\"body\":{\"data\":\"" + b64("Hello team\nline2") + "\"}},"
                + "{\"mimeType\":\"text/html\",\"filename\":\"\",\"body\":{\"data\":\"" + b64(HTML) + "\"}}]},"
                + "{\"mimeType\":\"application/pdf\",\"filename\":\"../INV-7.pdf\",\"body\":{\"attachmentId\":\"A1\",\"size\":6}},"
                + "{\"mimeType\":\"image/png\",\"filename\":\"logo.png\",\"body\":{\"data\":\"" + b64("PNG") + "\"}}]}}";
    }

    /** Decodes the base64url "raw" message a request sent to Gmail. */
    static String sentRaw(Call c) { return new String(Base64.getUrlDecoder().decode((String) json(c).get("raw")), StandardCharsets.UTF_8); }

    /** Decodes the first base64 text part of a MIME message. */
    static String firstText(String mime) {
        int i = mime.indexOf("Content-Transfer-Encoding: base64\r\n\r\n") + "Content-Transfer-Encoding: base64\r\n\r\n".length();
        int end = mime.indexOf("\r\n--", i);
        return new String(Base64.getMimeDecoder().decode(mime.substring(i, end < 0 ? mime.length() : end).trim()), StandardCharsets.UTF_8);
    }

    static Fake gmailFake() throws Exception {
        Fake f = new Fake();
        f.on("GET /gmail/v1/users/me/messages", c -> q(c).contains("pageToken=p2") ? ok("{\"messages\":[{\"id\":\"M3\"}]}")
                        : ok("{\"messages\":[{\"id\":\"M1\"},{\"id\":\"M2\"}],\"nextPageToken\":\"p2\"}"))
         .on("GET /gmail/v1/users/me/messages/M1/attachments/A1", c -> ok("{\"data\":\"" + b64("%PDF-7") + "\"}"))
         .on("GET /gmail/v1/users/me/messages/M\\d", c -> q(c).contains("format=raw")
                 ? ok("{\"raw\":\"" + b64("From: ann@acme.com\r\nSubject: Invoice 7\r\n\r\nHello team\r\n") + "\"}")
                 : ok(fullMessage(c.path().substring(c.path().lastIndexOf('/') + 1))))
         .on("POST /gmail/v1/users/me/messages/M\\d/modify", c -> ok("{\"id\":\"M1\",\"labelIds\":[\"Label_5\"]}"))
         .on("POST /gmail/v1/users/me/messages/M\\d/trash", c -> ok("{}"))
         .on("DELETE /gmail/v1/users/me/messages/M\\d", c -> new Object[]{204, ""})
         .on("POST /gmail/v1/users/me/messages/batchDelete", c -> new Object[]{204, ""})
         .on("POST /gmail/v1/users/me/messages/send", c -> ok("{\"id\":\"SENT1\",\"threadId\":\"T1\"}"))
         .on("GET /gmail/v1/users/me/labels", c -> ok("{\"labels\":[{\"id\":\"INBOX\",\"name\":\"INBOX\"},{\"id\":\"Label_5\",\"name\":\"Processed\"}]}"))
         .on("GET /gmail/v1/users/me/profile", c -> ok("{\"emailAddress\":\"Me@corp.com\"}"));
        return f;
    }

    @Test void gmailReadChangeAndDelete() throws Exception {
        try (Fake f = gmailFake()) {
            Path w = tmp();
            Map<String, Object> o = act(f, w, "gmail_list_messages", Map.of("query", "from:ann@acme.com is:unread"), "table", "count");
            assertEquals(3, n(o.get("count")), "follows nextPageToken");
            List<Object> first = rows(o.get("table")).get(0);
            assertEquals(List.of("M1", "T1", "Ann <ann@acme.com>"), first.subList(0, 3));
            assertEquals("Invoice 7", first.get(4));
            assertEquals(true, first.get(7));
            Call listCall = f.calls.stream().filter(c -> c.path().equals("/gmail/v1/users/me/messages")).findFirst().orElseThrow();
            assertTrue(q(listCall).contains("q=from:ann@acme.com is:unread"));

            Map<String, Object> e = m(act(f, w, "gmail_get_message", Map.of("message_id", "M1", "mark_read", true), "email").get("email"));
            assertEquals("Hello team\nline2", e.get("text"));
            assertEquals(HTML, e.get("html"));
            assertEquals(List.of("../INV-7.pdf", "logo.png"), e.get("attachments"));
            assertEquals(List.of("me@corp.com", "\"Bob, B.\" <bob@corp.com>"), e.get("to"), "commas inside quotes do not split");
            assertEquals("<m1@acme.com>", e.get("message_id"));
            assertEquals(Map.of("removeLabelIds", List.of("UNREAD")), json(f.last("POST", "/gmail/v1/users/me/messages/M1/modify")));

            act(f, w, "gmail_mark_message", Map.of("message_id", "M1", "status", "starred"));
            assertEquals(Map.of("addLabelIds", List.of("STARRED")), json(f.last("POST", "/gmail/v1/users/me/messages/M1/modify")));
            act(f, w, "gmail_mark_message", Map.of("message_id", "M1", "status", "unread"));
            assertEquals(Map.of("addLabelIds", List.of("UNREAD")), json(f.last("POST", "/gmail/v1/users/me/messages/M1/modify")));

            assertEquals(List.of("Label_5"), act(f, w, "gmail_move_message", Map.of("message_id", "M1", "add_labels", "processed"), "labels").get("labels"));
            assertEquals(Map.of("addLabelIds", List.of("Label_5"), "removeLabelIds", List.of("INBOX")), json(f.last("POST", "/gmail/v1/users/me/messages/M1/modify")));

            act(f, w, "gmail_delete_message", Map.of("message_id", "M2"));
            assertNotNull(f.last("POST", "/gmail/v1/users/me/messages/M2/trash"));
            assertNull(f.last("DELETE", "/gmail/v1/users/me/messages/M2"));
            act(f, w, "gmail_delete_message", Map.of("message_id", "M2", "permanent", true));
            assertNotNull(f.last("DELETE", "/gmail/v1/users/me/messages/M2"));

            assertEquals(3, n(act(f, w, "gmail_delete_all", Map.of("query", "older_than:30d"), "count").get("count")));
            assertNotNull(f.last("POST", "/gmail/v1/users/me/messages/M3/trash"));
            assertEquals(3, n(act(f, w, "gmail_delete_all", Map.of("query", "older_than:30d", "permanent", true, "max_messages", 3), "count").get("count")));
            assertEquals(Map.of("ids", List.of("M1", "M2", "M3")), json(f.last("POST", "/gmail/v1/users/me/messages/batchDelete")));

            assertEquals("GOOGLE.BAD_INPUT", err(f, "gmail_delete_all", Map.of("query", " ")).get("code"));
            assertEquals("GOOGLE.NO_LABEL", err(f, "gmail_move_message", Map.of("message_id", "M1", "add_labels", "Nope")).get("code"));
            assertEquals("FLOW.INVALID", err(f, "gmail_mark_message", Map.of("message_id", "M1", "status", "flagged")).get("code"));
            assertEquals("GOOGLE.NOT_FOUND", err(f, "gmail_get_message", Map.of("message_id", "X1")).get("code"));
        }
    }

    @Test void gmailReplyForwardAndSave() throws Exception {
        try (Fake f = gmailFake()) {
            Path w = tmp();
            Map<String, Object> o = act(f, w, "gmail_reply_message", Map.of("message_id", "M1", "body", "Thanks, paid."), "message_id", "thread_id");
            assertEquals("SENT1", o.get("message_id"));
            Call send = f.last("POST", "/gmail/v1/users/me/messages/send");
            assertEquals("T1", json(send).get("threadId"));
            String mime = sentRaw(send);
            assertTrue(mime.contains("To: Ann <ann@acme.com>\r\n"), mime);
            assertFalse(mime.contains("Cc:"));
            assertTrue(mime.contains("Subject: Re: Invoice 7\r\n"));
            assertTrue(mime.contains("In-Reply-To: <m1@acme.com>\r\n"));
            assertTrue(mime.contains("References: <m0@acme.com> <m1@acme.com>\r\n"));
            String body = firstText(mime);
            assertTrue(body.startsWith("Thanks, paid.\r\n\r\nOn Mon, 19 Oct 2026 09:00:00 +0000, Ann <ann@acme.com> wrote:\r\n> Hello team\r\n> line2"), body);

            act(f, w, "gmail_reply_all", Map.of("message_id", "M1", "body", "<b>Done</b>", "html", true));
            String all = sentRaw(f.last("POST", "/gmail/v1/users/me/messages/send"));
            assertTrue(all.contains("To: Ann <ann@acme.com>, \"Bob, B.\" <bob@corp.com>\r\n"), all);
            assertTrue(all.contains("Cc: carol@corp.com\r\n"));
            assertFalse(all.toLowerCase(Locale.ROOT).contains("me@corp.com"), "own address left out");
            assertTrue(all.contains("Content-Type: text/html"));
            assertTrue(firstText(all).contains("<blockquote") && firstText(all).contains(HTML));

            act(f, w, "gmail_forward_message", Map.of("message_id", "M1", "to", "audit@corp.com", "note", "Please file."));
            String fwd = sentRaw(f.last("POST", "/gmail/v1/users/me/messages/send"));
            assertTrue(fwd.contains("To: audit@corp.com\r\n") && fwd.contains("Subject: Fwd: Invoice 7\r\n"), fwd);
            assertFalse(fwd.contains("In-Reply-To"));
            assertTrue(fwd.contains("multipart/mixed"));
            assertTrue(fwd.contains("filename=\"../INV-7.pdf\"") && fwd.contains("filename=\"logo.png\""), "original attachments forwarded");
            assertTrue(fwd.contains(Base64.getMimeEncoder().encodeToString("%PDF-7".getBytes(StandardCharsets.UTF_8))), "attachment fetched by id");
            String fwdText = firstText(fwd);
            assertTrue(fwdText.startsWith("Please file.") && fwdText.contains("---------- Forwarded message ---------") && fwdText.contains("From: Ann <ann@acme.com>")
                    && fwdText.contains("Hello team"), fwdText);

            act(f, w, "gmail_forward_message", Map.of("message_id", "M1", "to", List.of("a@x.com", "b@x.com"), "as_attachment", true));
            String fwd2 = sentRaw(f.last("POST", "/gmail/v1/users/me/messages/send"));
            assertTrue(fwd2.contains("To: a@x.com, b@x.com\r\n"));
            assertTrue(fwd2.contains("Content-Type: message/rfc822; name=\"Invoice 7.eml\""), fwd2);
            assertTrue(fwd2.contains("Subject: Invoice 7\r\n\r\nHello team"), "original embedded unencoded");

            @SuppressWarnings("unchecked") List<Object> files = (List<Object>) act(f, w, "gmail_save_attachments", Map.of("message_id", "M1", "target_folder", "in", "name_pattern", "*.PDF"), "files").get("files");
            assertEquals(1, files.size());
            assertEquals("%PDF-7", Files.readString(w.resolve("in/INV-7.pdf")), "name reduced to a safe file name");
            assertFalse(Files.exists(w.resolve("in/logo.png")));

            String eml = (String) act(f, w, "gmail_save_message_as_eml", Map.of("message_id", "M1", "local_path", "mail/m1.eml"), "path").get("path");
            assertTrue(Files.readString(Path.of(eml)).startsWith("From: ann@acme.com\r\nSubject: Invoice 7"));
            assertTrue(q(f.last("GET", "/gmail/v1/users/me/messages/M1")).contains("format=raw"));

            assertEquals("GOOGLE.NO_RECIPIENT", err(f, "gmail_forward_message", Map.of("message_id", "M1", "to", "")).get("code"));
            assertEquals("GOOGLE.NOT_FOUND", err(f, "gmail_reply_message", Map.of("message_id", "Z9")).get("code"));
        }
    }

    @Test void calendarListUpdateAndDeleteEvents() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /calendar/v3/calendars/primary/events", c -> q(c).contains("pageToken=n2")
                    ? ok("{\"items\":[{\"id\":\"E2\",\"summary\":\"Offsite\",\"start\":{\"date\":\"2026-10-22\"},\"end\":{\"date\":\"2026-10-23\"}}]}")
                    : ok("{\"items\":[{\"id\":\"E1\",\"summary\":\"Review\",\"location\":\"Room 4\",\"status\":\"confirmed\",\"htmlLink\":\"https://cal/E1\","
                       + "\"start\":{\"dateTime\":\"2026-10-20T08:00:00Z\"},\"end\":{\"dateTime\":\"2026-10-20T08:30:00Z\"},"
                       + "\"organizer\":{\"email\":\"ann@acme.com\"},\"attendees\":[{\"email\":\"bob@corp.com\"}]}],\"nextPageToken\":\"n2\"}"))
             .on("PATCH /calendar/v3/calendars/team%40corp.com/events/E1", c -> ok("{\"id\":\"E1\",\"htmlLink\":\"https://cal/E1\"}"))
             .on("DELETE /calendar/v3/calendars/primary/events/E1", c -> new Object[]{204, ""})
             .on("DELETE /calendar/v3/calendars/primary/events/GONE", c -> new Object[]{410, "{\"error\":{\"message\":\"Resource has been deleted\"}}"});
            Path w = tmp();
            Map<String, Object> o = act(f, w, "calendar_list_events", Map.of("start", "2026-10-20T00:00:00", "end", "2026-10-27T00:00:00", "time_zone", "Europe/Berlin"), "table", "count");
            assertEquals(2, n(o.get("count")));
            List<List<Object>> ev = rows(o.get("table"));
            assertEquals("E1", ev.get(0).get(0));
            assertEquals("2026-10-20T10:00", String.valueOf(ev.get(0).get(2)), "converted to the requested time zone");
            assertEquals(false, ev.get(0).get(4));
            assertEquals(List.of("bob@corp.com"), ev.get(0).get(7));
            assertEquals(true, ev.get(1).get(4), "all-day event");
            String lq = q(f.calls.get(0));
            assertTrue(lq.contains("timeMin=2026-10-20T00:00+02:00") && lq.contains("timeMax=2026-10-27T00:00+01:00") && lq.contains("singleEvents=true")
                    && lq.contains("orderBy=startTime"), lq);

            assertEquals("https://cal/E1", act(f, w, "calendar_update_event", Map.of("event_id", "E1", "calendar_id", "team@corp.com", "subject", "Review v2",
                    "start", "2026-10-21T09:00:00", "location", "Room 5", "notify", false), "link").get("link"));
            Call patch = f.last("PATCH", "/calendar/v3/calendars/.*/events/E1");
            Map<String, Object> body = json(patch);
            assertEquals("Review v2", body.get("summary"));
            assertEquals(Map.of("dateTime", "2026-10-21T09:00", "timeZone", "UTC"), body.get("start"));
            assertEquals("Room 5", body.get("location"));
            assertFalse(body.containsKey("end") || body.containsKey("attendees"), "only given fields change");
            assertTrue(q(patch).contains("sendUpdates=none"));

            act(f, w, "calendar_delete_event", Map.of("event_id", "E1"));
            assertTrue(q(f.last("DELETE", "/calendar/v3/calendars/primary/events/E1")).contains("sendUpdates=all"));

            assertEquals("GOOGLE.BAD_INPUT", err(f, "calendar_update_event", Map.of("event_id", "E1")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "calendar_list_events", Map.of("start", "2026-10-20T00:00:00", "end", "2026-10-21T00:00:00", "time_zone", "Mars/Base")).get("code"));
            Map<String, Object> e = err(f, "calendar_delete_event", Map.of("event_id", "GONE"));
            assertEquals("GOOGLE.REQUEST_FAILED", e.get("code"));
            assertTrue(String.valueOf(e.get("message")).contains("Resource has been deleted"));
        }
    }
}
