package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.GoogleGapsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Google panel options: Drive destinations by path, export overwrite and file name, whole-sheet reads, sheets by position and colour names. */
class GooglePanelOptionsTest {

    @Test void driveDestinationsGivenByPath() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files", GoogleMoreOptionsTest::drive)
             .on("POST /drive/v3/files/Q4/copy", c -> ok("{\"id\":\"Q4c\"}"))
             .on("GET /drive/v3/files/Q4", c -> ok("{\"parents\":[\"R1\"]}"))
             .on("PATCH /drive/v3/files/Q4", c -> ok("{\"id\":\"Q4\"}"));
            Path w = tmp();
            assertEquals("Q4c", act(f, w, "drive_copy_file", Map.of("item_path", "Reports/q4.pdf", "destination_path", "Reports/2026"), "file_id").get("file_id"));
            assertEquals(Map.of("parents", List.of("Y26")), f.last("POST", "/drive/v3/files/Q4/copy").json());
            Call step = f.calls.stream().filter(c -> q(c).contains("name = '2026'")).findFirst().orElseThrow();
            assertTrue(q(step).contains("mimeType = 'application/vnd.google-apps.folder'"), "the destination is looked up as a folder");

            act(f, w, "drive_copy_file", Map.of("file_id", "Q4", "destination_path", "/"));
            assertEquals(Map.of("parents", List.of("root")), f.last("POST", "/drive/v3/files/Q4/copy").json(), "/ is My Drive");

            act(f, w, "drive_move_item", Map.of("item_path", "Reports/q4.pdf", "destination_path", "My Drive/Reports/2026"));
            String move = q(f.last("PATCH", "/drive/v3/files/Q4"));
            assertTrue(move.contains("addParents=Y26") && move.contains("removeParents=R1"), move);

            int calls = f.calls.size();
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_file", Map.of("file_id", "Q4", "folder_id", "D9", "destination_path", "Reports")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_move_item", Map.of("item_id", "Q4", "folder_id", "D9", "destination_path", "Reports")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_move_item", Map.of("item_id", "Q4")).get("code"), "no destination");
            assertEquals(calls, f.calls.size(), "input mistakes are caught before any request");
            assertEquals("GOOGLE.NOT_FOUND", err(f, "drive_move_item", Map.of("item_id", "Q4", "destination_path", "Reports/2027")).get("code"));
        }
    }

    @Test void copyFolderGivenByPaths() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files", GoogleMoreOptionsTest::drive)
             .on("GET /drive/v3/files/Y26", c -> ok("{\"id\":\"Y26\",\"name\":\"2026\",\"mimeType\":\"application/vnd.google-apps.folder\",\"parents\":[\"R1\"]}"))
             .on("POST /drive/v3/files", c -> ok("{\"id\":\"C26\"}"))
             .on("POST /drive/v3/files/X1/copy", c -> ok("{\"id\":\"X1c\"}"));
            Path w = tmp();
            Map<String, Object> o = act(f, w, "drive_copy_folder", Map.of("folder_path", "Reports/2026", "destination_path", "Reports", "name", "2026 copy"), "folder_id", "file_count");
            assertEquals("C26", o.get("folder_id"));
            assertEquals(BigDecimal.ONE, o.get("file_count"));
            Map<String, Object> created = f.last("POST", "/drive/v3/files").json();
            assertEquals(List.of("R1"), created.get("parents"));
            assertEquals("2026 copy", created.get("name"));
            assertTrue(q(f.last("GET", "/drive/v3/files")).contains("'Y26' in parents"), "the folder found by path is the one copied");
            assertEquals(List.of("C26"), f.last("POST", "/drive/v3/files/X1/copy").json().get("parents"));

            int calls = f.calls.size();
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_folder", Map.of()).get("code"), "no source");
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_folder", Map.of("folder_id", "Y26", "folder_path", "Reports/2026")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_folder", Map.of("folder_id", "Y26", "destination_id", "R1", "destination_path", "Reports")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "drive_copy_folder", Map.of("folder_path", "/")).get("code"), "My Drive itself is not copied");
            assertEquals(calls, f.calls.size(), "input mistakes are caught before any request");
        }
    }

    @Test void exportKeepsOrRenamesTheLocalFile() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /drive/v3/files/DOC1/export", c -> ok("exported"));
            Path w = tmp();
            Path dir = w.resolve("out");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("doc.pdf"), "old");

            String saved = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "local_path", dir.resolve("doc.pdf").toString(), "overwrite", false), "path").get("path");
            assertEquals(dir.resolve("doc (2).pdf").toString(), saved, "an existing file is kept");
            assertEquals("old", Files.readString(dir.resolve("doc.pdf")));
            assertEquals("exported", Files.readString(Path.of(saved)));

            saved = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "local_path", dir.resolve("doc.pdf").toString()), "path").get("path");
            assertEquals(dir.resolve("doc.pdf").toString(), saved, "overwrite is the default");
            assertEquals("exported", Files.readString(dir.resolve("doc.pdf")));

            saved = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "local_path", dir.toString(), "format", "xlsx", "file_name", "Monthly report"), "path").get("path");
            assertEquals(dir.resolve("Monthly report.xlsx").toString(), saved, "file_name goes into a folder given as local_path, with the format's extension");
            assertTrue(q(f.last("GET", "/drive/v3/files/DOC1/export")).contains("spreadsheetml.sheet"));

            saved = (String) act(f, w, "drive_download_export", Map.of("file_id", "DOC1", "local_path", dir.toString(), "file_name", "final.PDF"), "path").get("path");
            assertEquals(dir.resolve("final.PDF").toString(), saved, "a name with an extension is kept as it is");
        }
    }

    @Test void readAllCellsOfASheet() throws Exception {
        try (Fake f = sheetsFake()) {
            Path w = tmp();
            Map<String, Object> o = act(f, w, "sheets_read", Map.of("spreadsheet_id", "S1"), "table");
            assertEquals("'Data'", path(f.last("GET", "/v4/spreadsheets/S1/values/.*")).replaceFirst("^/v4/spreadsheets/S1/values/", ""), "no range reads the whole first sheet");
            assertEquals(List.of("Name", "Amount", "Column3"), m(o.get("table")).get("columns"));
            assertEquals(3, rows(o.get("table")).size());

            act(f, w, "sheets_read", Map.of("spreadsheet_id", "S1", "sheet", "Data", "header", false), "table");
            assertEquals("'Data'", path(f.last("GET", "/v4/spreadsheets/S1/values/.*")).replaceFirst("^/v4/spreadsheets/S1/values/", ""));

            o = act(f, w, "sheets_get_range", Map.of("spreadsheet_id", "S1", "sheet", "Data"), "values", "table");
            assertEquals(4, ((List<?>) o.get("values")).size());

            act(f, w, "sheets_read", Map.of("spreadsheet_id", "S1", "range", "Data!A1:B3"), "table");
            assertEquals("Data!A1:B3", path(f.last("GET", "/v4/spreadsheets/S1/values/.*")).replaceFirst("^/v4/spreadsheets/S1/values/", ""), "a range is used as before");

            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_read", Map.of("spreadsheet_id", "S1", "range", "A1", "sheet", "Data")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_get_range", Map.of("spreadsheet_id", "S1", "range", "A1", "sheet", "Data")).get("code"));
        }
    }

    @Test void sheetsPickedByPosition() throws Exception {
        try (Fake f = sheetsFake()) {
            Path w = tmp();
            act(f, w, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "position", 2));
            assertEquals(77, n(m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("deleteSheet")).get("sheetId")));

            act(f, w, "sheets_rename_sheet", Map.of("spreadsheet_id", "S1", "position", 1, "new_name", "Main"));
            Map<String, Object> ren = m(req0(f.last("POST", "/v4/spreadsheets/S1:batchUpdate")).get("updateSheetProperties"));
            assertEquals(0, n(m(ren.get("properties")).get("sheetId")));
            assertEquals("Main", m(ren.get("properties")).get("title"));

            assertEquals("GOOGLE.NO_SHEET", err(f, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "position", 3)).get("code"));
            int calls = f.calls.size();
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1", "sheet", "Data", "position", 1)).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_rename_sheet", Map.of("spreadsheet_id", "S1", "position", 0, "new_name", "X")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "sheets_delete_sheet", Map.of("spreadsheet_id", "S1")).get("code"));
            assertEquals(calls, f.calls.size(), "input mistakes are caught before any request");
        }
    }

    @Test void cellColourAsName() throws Exception {
        try (Fake f = sheetsFake()) {
            Path w = tmp();
            Map<String, Object> o = act(f, w, "sheets_get_cell_color", Map.of("spreadsheet_id", "S1", "cell", "B2", "format", "name"), "background_color", "font_color");
            assertEquals("orange", o.get("background_color"), "#FF8000 is nearest to orange");
            assertEquals("blue", o.get("font_color"));
            o = act(f, w, "sheets_get_cell_color", Map.of("spreadsheet_id", "S1", "cell", "B2"), "background_color");
            assertEquals("#FF8000", o.get("background_color"), "hex stays the default");
        }
        assertEquals("white", GooglePackage.colorName("#FFFFFF"));
        assertEquals("black", GooglePackage.colorName("#101010"));
        assertEquals("grey", GooglePackage.colorName("#A0A0A0"));
        assertEquals("red", GooglePackage.colorName("#E01010"));
        assertEquals("green", GooglePackage.colorName("#00A000"));
    }
}
