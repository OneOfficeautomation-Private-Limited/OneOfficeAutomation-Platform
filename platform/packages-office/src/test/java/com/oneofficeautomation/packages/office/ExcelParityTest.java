package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** AA-parity options of the Excel toolkit (21 Nov 2026): safer Create worksheet, active-cell choices, rows N to M, edit password, hidden sheets, labels, links, Excel filters, sort, protect and select options. */
class ExcelParityTest {
    private final ExcelPackage xl = new ExcelPackage();
    private Path dir;
    private ActionContext ctx;

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-xl-parity");
        ctx = OfficeTestSupport.ctx(dir);
        Object[][] data = {{"Name", "Amount", "Status"}, {"Acme", 300, "Open"}, {"Globex", 1200, "Closed"}, {"Initech", 50, "open"}, {"Umbrella", 800, "Open"}};
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(dir.resolve("book.xlsx"))) {
            fill(wb.createSheet("Data"), data);
            wb.createSheet("Secret").createRow(0).createCell(0).setCellValue("hidden");
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("note");
            wb.setSheetHidden(1, true);
            wb.write(out);
        }
        xl.open(ctx, "book.xlsx", "excel", false);
    }

    private static void fill(Sheet s, Object[][] data) {
        for (int r = 0; r < data.length; r++) {
            Row row = s.createRow(r);
            for (int c = 0; c < data[r].length; c++) {
                Object v = data[r][c];
                if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue()); else row.createCell(c).setCellValue((String) v);
            }
        }
    }

    private ExcelPackage.WorkbookSession session() { return (ExcelPackage.WorkbookSession) ctx.sessions().get("excel"); }

    private static ActionSpec spec(String id) { return PackageIntrospector.inspect(ExcelPackage.class).actions().get(id); }

    @Test void createWorksheetStopsOnATakenNameForNewStepsAndSaysSo() {
        ActionSpec add = spec("add_sheet");
        Map<String, Object> replace = add.input("replace").orElseThrow().schema();
        assertEquals(true, replace.get("default"), "saved steps keep replacing");
        assertEquals(false, replace.get("x-ooa-new-step-default"), "new steps stop instead");
        assertEquals("danger", replace.get("x-ooa-tone"));
        assertTrue(add.description().contains("stops"), add.description());
        assertTrue(String.valueOf(replace.get("description")).contains("delete"));

        xl.setCell(ctx, "A1", "keep me", "Notes", "excel");
        ActionException e = assertThrows(ActionException.class, () -> xl.addSheet(ctx, "Notes", false, "excel", null, false));
        assertEquals("EXCEL.SHEET_EXISTS", e.code());
        assertEquals("keep me", xl.getCell(ctx, "A1", "Notes", "excel").get("value"), "nothing was cleared");

        assertEquals("Sheet4", xl.addSheet(ctx, null, false, "excel", null, false).get("name"), "name optional: the next free SheetN");
        assertEquals("Sheet5", xl.addSheet(ctx, " ", false, "excel", null, false).get("name"));
    }

    @Test void activeCellChoicesForAddressesAndReads() {
        xl.goToCell(ctx, "B3", "Data", "excel");
        assertEquals("B3", xl.cellAddress(ctx, null, null, null, null, null, "excel").get("cell"));
        assertEquals("B", xl.columnName(ctx, null, null, null, "excel").get("name"));
        assertEquals("C", xl.columnName(ctx, null, "C9", null, "excel").get("name"));
        assertEquals(BigDecimal.valueOf(3), xl.rowNumber(ctx, null, null, "excel").get("row"));
        assertEquals("C4", xl.cellAddress(ctx, null, null, "status", 3, null, "excel").get("cell"), "three cells below the Status title");
        assertEquals("C2", xl.cellAddress(ctx, null, null, "Status", null, null, "excel").get("cell"));
        assertEquals("EXCEL.BAD_COLUMN", assertThrows(ActionException.class, () -> xl.cellAddress(ctx, null, null, "Nope", 1, null, "excel")).code());
        assertEquals("EXCEL.BAD_CELL", assertThrows(ActionException.class, () -> xl.cellAddress(ctx, 4, null, null, null, null, "excel")).code());
        assertEquals("B7", xl.cellAddress(ctx, 7, "B").get("cell"), "the old way still works");

        List<?> down = (List<?>) xl.readColumn(ctx, null, 1, "Data", "excel", null, "value", null).get("values");
        assertEquals(List.of(new BigDecimal("1200"), new BigDecimal("50"), new BigDecimal("800")), down, "from the active cell down its column");
        List<?> along = (List<?>) xl.readRow(ctx, (Integer) null, "A", "Data", "excel", null, "value").get("values");
        assertEquals(List.of(new BigDecimal("1200"), "Closed"), along, "from the active cell along its row");
    }

    @Test void rowsFromToKeepTheHeaderAndTheLoopToo() {
        Map<String, Object> t = xl.getAllCells(ctx, "Data", true, "excel", "value", true, false, 3, 4);
        assertEquals(List.of("Name", "Amount", "Status"), ((Map<?, ?>) t.get("table")).get("columns"));
        List<?> rows = (List<?>) ((Map<?, ?>) t.get("table")).get("rows");
        assertEquals(2, rows.size());
        assertEquals("Globex", ((List<?>) rows.get(0)).get(0));
        assertEquals("Initech", ((List<?>) rows.get(1)).get(0));
        int n = 0;
        for (Object r : xl.rows(ctx, "Data", null, true, "excel", "value", true, 4, null)) { n++; assertNotEquals("Acme", ((Map<?, ?>) r).get("Name")); }
        assertEquals(2, n, "rows 4 and 5");
        assertEquals("EXCEL.BAD_ROW", assertThrows(ActionException.class, () -> xl.getAllCells(ctx, "Data", true, "excel", "value", true, false, 5, 2)).code());
    }

    @Test void editPasswordIsCheckedAndCreateSetsIt() throws Exception {
        SecureString edit = new SecureString("Modify-9", "vault:t");
        xl.createWorkbook(ctx, "locked.xlsx", "Main", false, "c", null, edit, null, null, false, false);
        xl.close(ctx, "c");
        try (InputStream in = Files.newInputStream(dir.resolve("locked.xlsx")); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            assertTrue(ExcelPackage.hasEditPassword(wb));
            assertTrue(ExcelPackage.editPasswordMatches(wb, "Modify-9"));
            assertFalse(ExcelPackage.editPasswordMatches(wb, "nope"));
        }
        ActionException e = assertThrows(ActionException.class, () -> xl.open(ctx, "locked.xlsx", "l", false, null, false, null, true, null, null, null,
                new SecureString("wrong", "v"), false, null, null, false, false));
        assertEquals("EXCEL.WRONG_EDIT_PASSWORD", e.code());
        xl.open(ctx, "locked.xlsx", "l", false, null, false, null, true, null, null, null, edit, false, null, null, false, false);
        xl.open(ctx, "locked.xlsx", "l2", false);   // without it the file still opens, as before
    }

    @Test void skipHiddenSheetsLeavesThemOutEverywhere() {
        xl.open(ctx, "book.xlsx", "v", false, null, false, null, true, null, null, null, null, true, null, null, false, false);
        assertEquals(List.of("Data", "Notes"), xl.getSheetNames(ctx, "v", true).get("names"));
        assertEquals(BigDecimal.valueOf(2), xl.sheetCount(ctx, "v", true).get("count"));
        assertEquals("EXCEL.NO_SHEET", assertThrows(ActionException.class, () -> xl.getCell(ctx, "A1", "Secret", "v")).code());
        xl.switchSheet(ctx, null, "v", 2);
        assertEquals("Notes", xl.currentSheet(ctx, "v").get("name"), "position 2 counts visible sheets");
        assertEquals(List.of("Data", "Secret", "Notes"), xl.getSheetNames(ctx, "excel", true).get("names"), "a normal session still sees all");
        assertEquals(false, spec("sheet_count").input("include_hidden").orElseThrow().schema().get("x-ooa-new-step-default"));
    }

    @Test void sensitivityLabelOnOpenAndCreateWithCarryOn() {
        String label = "11111111-2222-3333-4444-555555555555", site = "66666666-7777-8888-9999-000000000000";
        xl.createWorkbook(ctx, "lab.xlsx", null, false, "lab", null, null, label, site, false, false);
        assertEquals(label, xl.getSensitivityLabel(ctx, "lab").get("label_id"));
        xl.open(ctx, "book.xlsx", "o", false, null, false, null, true, null, null, null, null, false, label, site, false, true);
        assertEquals(label, xl.getSensitivityLabel(ctx, "o").get("label_id"));
        assertEquals("EXCEL.BAD_LABEL", assertThrows(ActionException.class,
                () -> xl.open(ctx, "book.xlsx", "x", false, null, false, null, true, null, null, null, null, false, "not-a-guid", site, false, false)).code());
        xl.open(ctx, "book.xlsx", "y", false, null, false, null, true, null, null, null, null, false, "not-a-guid", site, true, false);
        assertNotNull(ctx.sessions().get("y"), "carry on: opened with a warning");
        assertTrue(((Set<?>) ctx.sessions().get(ActionContext.SHARED_SESSIONS)).contains("o"), "share lends the session to child agents");
        assertEquals(true, spec("set_sensitivity_label").input("label_id").orElseThrow().schema().get("x-ooa-accepts-credential"));
    }

    @Test void aPastedSharePointLinkOpensThroughGraphShares() throws Exception {
        Path src = dir.resolve("book.xlsx");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());
            byte[] body;
            String path = ex.getRequestURI().getRawPath();
            if (path.startsWith("/v1.0/shares/u!")) body = "{\"id\":\"I1\",\"name\":\"Q4 plan.xlsx\",\"parentReference\":{\"driveId\":\"D9\",\"path\":\"/drives/D9/root:/Shared%20Documents/Plans\"}}".getBytes(StandardCharsets.UTF_8);
            else if (path.endsWith(":/content")) body = Files.readAllBytes(src);
            else body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1.0";
            String link = "https://contoso.sharepoint.com/:x:/r/sites/Fin/Shared%20Documents/Plans/Q4%20plan.xlsx?d=w1&csf=1";
            xl.open(ctx, link, "sp", false, null, false, null, true, new SecureString("tok", "t"), null, base, null, false, null, null, false, false);
            assertEquals("Acme", xl.getCell(ctx, "A2", "Data", "sp").get("value"));
            String expect = "u!" + Base64.getUrlEncoder().withoutPadding().encodeToString(link.getBytes(StandardCharsets.UTF_8));
            assertTrue(seen.get(0).contains(expect), seen.get(0));
            assertTrue(seen.stream().anyMatch(s -> s.startsWith("GET /v1.0/drives/D9/root:/Shared%20Documents/Plans/Q4%20plan.xlsx:/content")), seen.toString());
            assertTrue(ExcelPackage.shareUrl(link));
            assertFalse(ExcelPackage.shareUrl("C:/files/book.xlsx"));
        } finally { srv.stop(0); }
    }

    @Test void filterCanWriteARealExcelFilterAndClearIt() {
        Map<String, Object> r = xl.filter(ctx, "Status", "equals", "Open", false, true, "Data", "excel", null, false, null, null, true, false);
        assertEquals(BigDecimal.valueOf(3), r.get("count"), "case is ignored: open matches too");
        XSSFSheet sh = (XSSFSheet) session().wb.getSheet("Data");
        assertEquals("A1:C5", sh.getCTWorksheet().getAutoFilter().getRef());
        assertEquals(List.of("2 filters Open"), ExcelPackage.filterColumns(sh));
        assertTrue(sh.getRow(2).getZeroHeight(), "Globex (Closed) is hidden");
        assertFalse(sh.getRow(1).getZeroHeight());

        xl.filter(ctx, "Amount", "between", 100, false, true, "Data", "excel", 900, false, null, null, true, true);
        assertEquals(List.of("1 customFilters greaterThanOrEqual:100 lessThanOrEqual:900"), ExcelPackage.filterColumns(sh), "clear_existing replaced the old filter");
        assertFalse(sh.getRow(1).getZeroHeight());
        assertTrue(sh.getRow(2).getZeroHeight());
        xl.filter(ctx, "Name", "contains", "rell", false, true, "Data", "excel", null, false, null, null, true, true);
        assertEquals(List.of("0 customFilters equal:*rell*"), ExcelPackage.filterColumns(sh));

        assertEquals(BigDecimal.valueOf(3), xl.clearFilter(ctx, "Data", true, "excel").get("rows_shown"));
        assertTrue(sh.getCTWorksheet().isSetAutoFilter(), "the buttons stay");
        assertEquals(List.of(), ExcelPackage.filterColumns(sh));
        xl.clearFilter(ctx, "Data", false, "excel");
        assertFalse(sh.getCTWorksheet().isSetAutoFilter());
        assertEquals("EXCEL.BAD_FILTER", assertThrows(ActionException.class,
                () -> xl.filter(ctx, "A", "equals", "x", false, false, "Data", "excel", null, false, null, null, true, false)).code());
    }

    @Test void sortOptionsTableOnlyColumnAndNumbersAsText() throws Exception {
        Sheet sh = session().wb.getSheet("Notes");
        String[] vals = {"10", "9", "100"};
        for (int i = 0; i < 3; i++) { Row row = sh.createRow(i + 1); row.createCell(0).setCellValue(vals[i]); row.createCell(1).setCellValue("r" + i); }
        sh.getRow(0).createCell(1).setCellValue("tag");
        xl.sortRange(ctx, "A", false, true, "A1:B4", "Notes", "excel", null, false, null, false, "number");
        assertEquals(List.of("9", "10", "100"), List.of(sh.getRow(1).getCell(0).getStringCellValue(), sh.getRow(2).getCell(0).getStringCellValue(), sh.getRow(3).getCell(0).getStringCellValue()));
        assertEquals("r1", sh.getRow(1).getCell(1).getStringCellValue(), "rows moved together");
        xl.sortRange(ctx, "A", false, true, "A1:B4", "Notes", "excel", null, false, null, true, "text");
        assertEquals("10", sh.getRow(1).getCell(0).getStringCellValue(), "as text 10 < 100 < 9");
        assertEquals("r1", sh.getRow(1).getCell(1).getStringCellValue(), "only the column moved");

        XSSFSheet data = (XSSFSheet) session().wb.getSheet("Data");
        XSSFTable t = data.createTable(new org.apache.poi.ss.util.AreaReference("A1:C5", org.apache.poi.ss.SpreadsheetVersion.EXCEL2007));
        t.setName("Sales"); t.setDisplayName("Sales");
        xl.sortRange(ctx, "Amount", true, null, null, null, "excel", null, false, "Sales", false, "auto");
        assertEquals("Globex", data.getRow(1).getCell(0).getStringCellValue());
        assertEquals("Name", data.getRow(0).getCell(0).getStringCellValue(), "the table header stays");
    }

    @Test void protectSheetHasTheFourMoreSwitches() {
        xl.protectSheet(ctx, new SecureString("pw", "v"), "Data", "excel", true, true, false, false, false, false, false, false, false, false, false, true, false, true, false);
        XSSFSheet sh = (XSSFSheet) session().wb.getSheet("Data");
        assertFalse(sh.isInsertHyperlinksLocked(), "links allowed");
        assertTrue(sh.isPivotTablesLocked());
        assertFalse(sh.isObjectsLocked(), "objects allowed");
        assertTrue(sh.isScenariosLocked());
        xl.protectSheet(ctx, "pw", "Notes", "excel");   // the short form still works
    }

    @Test void selectRowsColumnsAndTheWholeSheet() {
        xl.goToCell(ctx, "B3", "Data", "excel");
        assertEquals("A3:XFD3", xl.selectRange(ctx, null, "Data", "excel", "rows", null, null).get("range"), "the active cell's row");
        assertEquals("A3:XFD5", xl.selectRange(ctx, null, "Data", "excel", "rows", "5:3", null).get("range"));
        assertEquals("C1:E1048576", xl.selectRange(ctx, null, "Data", "excel", "columns", null, "C:E").get("range"));
        assertEquals("C1:C1048576", xl.selectRange(ctx, null, "Data", "excel", "columns", null, null).get("range"), "the active cell (now C1) column");
        assertEquals("A1:XFD1048576", xl.selectRange(ctx, null, "Data", "excel", "whole_sheet", null, null).get("range"));
        assertEquals("EXCEL.BAD_ROW", assertThrows(ActionException.class, () -> xl.selectRange(ctx, null, "Data", "excel", "rows", "x", null)).code());
        assertEquals("EXCEL.BAD_RANGE", assertThrows(ActionException.class, () -> xl.selectRange(ctx, null, "Data", "excel", "range", null, null)).code());
    }

    @Test void smallOnesRowCountByPositionLinksTableAndTableColumnName() {
        assertEquals(BigDecimal.valueOf(1), xl.rowCount(ctx, null, "excel", "non_empty", 3).get("count"), "the third sheet, Notes");
        Map<String, Object> links = xl.getLinks(ctx, "excel");
        assertEquals(List.of("sheet", "cell", "formula"), ((Map<?, ?>) links.get("table")).get("columns"));
        XSSFSheet data = (XSSFSheet) session().wb.getSheet("Data");
        XSSFTable t = data.createTable(new org.apache.poi.ss.util.AreaReference("A1:C5", org.apache.poi.ss.SpreadsheetVersion.EXCEL2007));
        t.setName("T1"); t.setDisplayName("T1");
        assertEquals("Column4", xl.insertTableColumn(ctx, "T1", null, null, "excel").get("column_name"));
    }

    @Test void everyPanelStillPlacesEveryInput() {
        PackageIntrospector.manifest(ExcelPackage.class);
    }
}
