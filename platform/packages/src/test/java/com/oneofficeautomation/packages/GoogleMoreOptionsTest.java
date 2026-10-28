package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.GoogleGapsTest.act;
import static com.oneofficeautomation.packages.GoogleGapsTest.err;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Google options: Drive folder and item paths, and spreadsheets given by link or name, against a fake Google server. */
class GoogleMoreOptionsTest {
    static final String SID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd";

    /** Drive lookups by name: My Drive has Reports (R1) with 2026 (Y26) and q4.pdf (Q4), and two folders named Twins. */
    static Object[] drive(Call c) {
        String q = q(c);
        if (q.contains("name = 'Reports'") && q.contains("'root' in parents")) return ok("{\"files\":[{\"id\":\"R1\",\"name\":\"Reports\"}]}");
        if (q.contains("name = '2026'") && q.contains("'R1' in parents")) return ok("{\"files\":[{\"id\":\"Y26\",\"name\":\"2026\"}]}");
        if (q.contains("name = 'q4.pdf'") && q.contains("'R1' in parents")) return ok("{\"files\":[{\"id\":\"Q4\",\"name\":\"q4.pdf\"}]}");
        if (q.contains("name = 'Twins'")) return ok("{\"files\":[{\"id\":\"T1\"},{\"id\":\"T2\"}]}");
        if (q.contains("name = 'Budget 2026'") && q.contains("spreadsheet")) return ok("{\"files\":[{\"id\":\"" + SID + "\",\"name\":\"Budget 2026\"}]}");
        if (q.contains("name = 'Copy Sheet'")) return ok("{\"files\":[{\"id\":\"A1x\"},{\"id\":\"B2x\"}]}");
        if (q.contains("fields=nextPageToken")) return ok("{\"files\":[{\"id\":\"X1\",\"name\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"size\":\"10\"}]}");
        return ok("{\"files\":[]}");
    }

    @Test void findItemsInAFolderGivenByPath() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files", GoogleMoreOptionsTest::drive);
            Path w = tmp();
            Map<String, Object> o = act(f, w, "drive_find_items", Map.of("folder_path", "My Drive/Reports/2026/", "item_type", "file"), "table", "count");
            assertEquals(BigDecimal.ONE, o.get("count"));
            String find = q(f.last("GET", "/drive/v3/files"));
            assertTrue(find.contains("'Y26' in parents"), find);
            assertTrue(find.contains("mimeType != 'application/vnd.google-apps.folder'"), find);
            Call step = f.calls.stream().filter(c -> q(c).contains("name = '2026'")).findFirst().orElseThrow();
            assertTrue(q(step).contains("mimeType = 'application/vnd.google-apps.folder'"), "path parts are looked up as folders");

            assertEquals("GOOGLE.NOT_FOUND", err(f, "drive_find_items", Map.of("folder_path", "Reports/2027")).get("code"));
            assertEquals("GOOGLE.AMBIGUOUS", err(f, "drive_find_items", Map.of("folder_path", "Twins")).get("code"));
            int calls = f.calls.size();
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_find_items", Map.of("folder_path", "Reports", "folder_id", "P0")).get("code"));
            assertEquals(calls, f.calls.size(), "both folder inputs are rejected before any request");

            act(f, w, "drive_find_items", Map.of("folder_id", "P0"), "count");
            assertTrue(q(f.last("GET", "/drive/v3/files")).contains("'P0' in parents"), "folder_id still works");
        }
    }

    @Test void driveItemsPickedByPath() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files", GoogleMoreOptionsTest::drive)
             .on("POST /drive/v3/files/Q4/copy", c -> ok("{\"id\":\"Q4c\"}"))
             .on("GET /drive/v3/files/Q4", c -> ok("{\"parents\":[\"R1\"]}"))
             .on("PATCH /drive/v3/files/Q4", c -> ok("{\"id\":\"Q4\"}"))
             .on("DELETE /drive/v3/files/Q4", c -> new Object[]{204, ""});
            Path w = tmp();
            assertEquals("Q4c", act(f, w, "drive_copy_file", Map.of("item_path", "Reports/q4.pdf", "folder_id", "D9"), "file_id").get("file_id"));
            assertEquals(Map.of("parents", List.of("D9")), f.last("POST", "/drive/v3/files/Q4/copy").json());
            Call last = f.calls.stream().filter(c -> q(c).contains("name = 'q4.pdf'")).findFirst().orElseThrow();
            assertFalse(q(last).contains("mimeType"), "the last part may be a file");

            act(f, w, "drive_move_item", Map.of("item_path", "Reports/q4.pdf", "folder_id", "D9"));
            assertTrue(q(f.last("PATCH", "/drive/v3/files/Q4")).contains("addParents=D9"));
            act(f, w, "drive_rename_item", Map.of("item_path", "Reports/q4.pdf", "new_name", "Q4 final.pdf"));
            assertEquals(Map.of("name", "Q4 final.pdf"), f.last("PATCH", "/drive/v3/files/Q4").json());
            act(f, w, "drive_delete_item", Map.of("item_path", "/Reports/q4.pdf", "permanent", true));
            assertNotNull(f.last("DELETE", "/drive/v3/files/Q4"));

            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_file", Map.of("folder_id", "D9")).get("code"), "neither id nor path");
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_file", Map.of("file_id", "Q4", "item_path", "Reports/q4.pdf")).get("code"), "both id and path");
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_delete_item", Map.of("item_path", "///")).get("code"), "a path without names");
            assertEquals("GOOGLE.NOT_FOUND", err(f, "drive_copy_file", Map.of("item_path", "Reports/missing.pdf")).get("code"));
        }
    }

    @Test void destinationFoldersGivenByPath() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files", GoogleMoreOptionsTest::drive)
             .on("POST /drive/v3/files/Q4/copy", c -> ok("{\"id\":\"Q4c\"}"))
             .on("POST /drive/v3/files/X1/copy", c -> ok("{\"id\":\"X1c\"}"))
             .on("GET /drive/v3/files/Q4", c -> ok("{\"parents\":[\"R1\"]}"))
             .on("GET /drive/v3/files/Y26", c -> ok("{\"id\":\"Y26\",\"name\":\"2026\",\"mimeType\":\"application/vnd.google-apps.folder\",\"parents\":[\"R1\"]}"))
             .on("PATCH /drive/v3/files/Q4", c -> ok("{\"id\":\"Q4\"}"))
             .on("POST /drive/v3/files", c -> ok("{\"id\":\"NEWF\"}"));
            Path w = tmp();
            act(f, w, "drive_copy_file", Map.of("item_path", "Reports/q4.pdf", "destination_path", "Reports/2026"), "file_id");
            assertEquals(Map.of("parents", List.of("Y26")), f.last("POST", "/drive/v3/files/Q4/copy").json());
            act(f, w, "drive_copy_file", Map.of("file_id", "Q4", "destination_path", "/"), "file_id");
            assertEquals(Map.of("parents", List.of("root")), f.last("POST", "/drive/v3/files/Q4/copy").json(), "/ is My Drive");

            act(f, w, "drive_move_item", Map.of("item_path", "Reports/q4.pdf", "destination_path", "Reports/2026"));
            String move = q(f.last("PATCH", "/drive/v3/files/Q4"));
            assertTrue(move.contains("addParents=Y26") && move.contains("removeParents=R1"), move);

            Map<String, Object> o = act(f, w, "drive_copy_folder", Map.of("folder_path", "Reports/2026", "destination_path", "Reports"), "folder_id", "file_count");
            assertEquals("NEWF", o.get("folder_id"));
            assertEquals(BigDecimal.ONE, o.get("file_count"));
            assertEquals(Map.of("name", "2026", "mimeType", "application/vnd.google-apps.folder", "parents", List.of("R1")), f.last("POST", "/drive/v3/files").json());
            assertEquals(List.of("NEWF"), f.last("POST", "/drive/v3/files/X1/copy").json().get("parents"));

            int calls = f.calls.size();
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_move_item", Map.of("item_id", "Q4", "folder_id", "D9", "destination_path", "Reports")).get("code"), "both destinations");
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_move_item", Map.of("item_id", "Q4")).get("code"), "no destination");
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_folder", Map.of("destination_id", "D9")).get("code"), "no source folder");
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_folder", Map.of("folder_id", "Y26", "folder_path", "Reports/2026")).get("code"), "both sources");
            assertEquals(calls, f.calls.size(), "rejected before any request");
            assertEquals("GOOGLE.NOT_FOUND", err(f, "drive_copy_file", Map.of("file_id", "Q4", "destination_path", "Reports/2027")).get("code"));
        }
    }

    @Test void exportOverwriteAndFileName() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files/DOC1/export", c -> new Object[]{200, "exported"});
            Path w = tmp();
            String p1 = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "local_path", "out", "file_name", "Q4 report"), "path").get("path");
            assertEquals(w.resolve("out/Q4 report.pdf"), Path.of(p1), "the format's extension is added");
            assertEquals("exported", java.nio.file.Files.readString(Path.of(p1)));
            String p2 = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "local_path", "out", "file_name", "Q4 report.pdf", "overwrite", false), "path").get("path");
            assertEquals(w.resolve("out/Q4 report (2).pdf"), Path.of(p2), "a number is added instead of replacing");
            String p3 = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "format", "docx", "local_path", "out", "file_name", "notes"), "path").get("path");
            assertEquals(w.resolve("out/notes.docx"), Path.of(p3));
            String p4 = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "local_path", "out/Q4 report.pdf"), "path").get("path");
            assertEquals(w.resolve("out/Q4 report.pdf"), Path.of(p4), "replaced by default, as before");
        }
    }

    @Test void sheetPickedByPosition() throws Exception {
        try (Fake f = GoogleGapsTest.sheetsFake()) {
            Path w = tmp();
            act(f, w, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "position", 2));
            assertEquals(77, GoogleGapsTest.n(GoogleGapsTest.m(GoogleGapsTest.req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("deleteSheet")).get("sheetId")), "position 2 is Old Tab");
            act(f, w, "sheets_rename_sheet", Map.of("spreadsheet_id", "S1", "position", 1, "new_name", "Main"));
            Map<String, Object> ren = GoogleGapsTest.m(GoogleGapsTest.req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("updateSheetProperties"));
            assertEquals(0, GoogleGapsTest.n(GoogleGapsTest.m(ren.get("properties")).get("sheetId")));
            assertEquals("Main", GoogleGapsTest.m(ren.get("properties")).get("title"));

            assertEquals("GOOGLE.NO_SHEET", err(f, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "position", 3)).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "position", 0)).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_rename_sheet", Map.of("spreadsheet_id", "S1", "sheet", "Data", "position", 1, "new_name", "x")).get("code"), "both name and position");
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1")).get("code"), "neither name nor position");
        }
    }

    @Test void spreadsheetGivenByLinkOrName() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files", GoogleMoreOptionsTest::drive)
             .on("GET /v4/spreadsheets/[^/]+/values/.*", c -> ok("{\"values\":[[\"a\",\"b\"],[1,2]]}"))
             .on("POST /v4/spreadsheets", c -> ok("{\"spreadsheetId\":\"" + SID + "\",\"spreadsheetUrl\":\"https://docs.google.com/spreadsheets/d/" + SID + "/edit\"}"));
            Path w = tmp();
            act(f, w, "sheets_read", Map.of("spreadsheet_id", "https://docs.google.com/spreadsheets/d/" + SID + "/edit#gid=0", "range", "A1:B2"), "table");
            assertNotNull(f.last("GET", "/v4/spreadsheets/" + SID + "/values/.*"), "the id is taken from the link");
            int drive = (int) f.calls.stream().filter(c -> c.path().equals("/drive/v3/files")).count();
            assertEquals(0, drive, "a link needs no Drive lookup");

            act(f, w, "sheets_get_cell", Map.of("spreadsheet_id", "Budget 2026", "cell", "Sheet1!A1"), "value");
            Call lookup = f.last("GET", "/drive/v3/files");
            assertTrue(q(lookup).contains("mimeType = 'application/vnd.google-apps.spreadsheet'") && q(lookup).contains("trashed = false"), q(lookup));
            assertTrue(f.last("GET", "/v4/spreadsheets/[^/]+/values/.*").path().startsWith("/v4/spreadsheets/" + SID + "/"), "the name is resolved to the id");

            act(f, w, "sheets_read", Map.of("spreadsheet_id", "S1", "range", "A1:B2"), "table");
            assertTrue(f.last("GET", "/v4/spreadsheets/[^/]+/values/.*").path().startsWith("/v4/spreadsheets/S1/"), "an id is used as it is");

            assertEquals("GOOGLE.NOT_FOUND", err(f, "sheets_read", Map.of("spreadsheet_id", "No Such Sheet", "range", "A1")).get("code"));
            assertEquals("GOOGLE.AMBIGUOUS", err(f, "sheets_read", Map.of("spreadsheet_id", "Copy Sheet", "range", "A1")).get("code"));
            int calls = f.calls.size();
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_read", Map.of("spreadsheet_id", "https://example.com/spreadsheets/d/x", "range", "A1")).get("code"));
            assertEquals(calls, f.calls.size(), "a foreign link is rejected before any request");

            Map<String, Object> created = act(f, w, "sheets_create_spreadsheet", Map.of("title", "Budget 2026"), "spreadsheet_id", "url");
            assertEquals(SID, created.get("spreadsheet_id"));
            assertEquals(SID, GooglePackage.spreadsheetIdOf((String) created.get("url")), "the returned link can be passed back as spreadsheet_id");
        }
    }

    @Test void spreadsheetIdOfLinksIdsAndNames() {
        assertEquals(SID, GooglePackage.spreadsheetIdOf("https://docs.google.com/spreadsheets/u/1/d/" + SID + "/edit?usp=sharing"));
        assertEquals(SID, GooglePackage.spreadsheetIdOf("  " + SID + " "));
        assertNull(GooglePackage.spreadsheetIdOf("Budget 2026"), "a name is looked up");
        assertEquals("GOOGLE.BAD_INPUT", assertThrows(ActionException.class, () -> GooglePackage.spreadsheetIdOf("http://docs.google.com/document/d/abc")).code());
        assertEquals(List.of("Reports", "2026"), GooglePackage.pathNames("My Drive/Reports//2026/"));
        assertEquals(List.of("a", "b.pdf"), GooglePackage.pathNames("\\a\\b.pdf"));
    }
}
