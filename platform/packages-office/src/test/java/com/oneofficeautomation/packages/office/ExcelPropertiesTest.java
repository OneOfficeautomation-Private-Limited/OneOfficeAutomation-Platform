package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Options added to the Excel actions after comparing them with the equivalent AA actions. */
class ExcelPropertiesTest {
    private final ExcelPackage xl = new ExcelPackage();
    private Path dir;
    private ActionContext ctx;
    private static final SecureString PW = new SecureString("Open-Sesame-7", "vault:test");

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-xl-props");
        ctx = OfficeTestSupport.ctx(dir);
        Object[][] data = {{"Name", "Amount", "Status"}, {"Acme", 300, "Open"}, {"Globex", 1200, "Closed"}, {"Initech", 50, "open"}, {"Umbrella", 800, "Open"}};
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(dir.resolve("book.xlsx"))) {
            Sheet s = wb.createSheet("Data");
            for (int r = 0; r < data.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    Object v = data[r][c];
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue()); else row.createCell(c).setCellValue((String) v);
                }
            }
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("note");
            wb.write(out);
        }
        xl.open(ctx, "book.xlsx", "excel", false);
    }

    private ExcelPackage.WorkbookSession session() { return ctx.session("excel", ExcelPackage.WorkbookSession.class); }
    private Sheet data() { return session().wb.getSheet("Data"); }
    private Object val(String address) { return xl.getCell(ctx, address, "Data", "excel").get("value"); }
    private static int n(Object o) { return ((BigDecimal) o).intValue(); }
    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }
    @SuppressWarnings("unchecked") private static List<Object> rows(Map<String, Object> res) { return (List<Object>) ((Map<String, Object>) res.get("table")).get("rows"); }
    @SuppressWarnings("unchecked") private static List<Object> cols(Map<String, Object> res) { return (List<Object>) ((Map<String, Object>) res.get("table")).get("columns"); }

    @Test void openWithPasswordReadOnlySheetAndHeader() {
        xl.saveAs(ctx, "secret.xlsx", null, null, "excel", true, PW);
        xl.close(ctx, "excel");
        assertEquals("EXCEL.PASSWORD_REQUIRED", code(() -> xl.open(ctx, "secret.xlsx", "s", false)));
        assertEquals("EXCEL.WRONG_PASSWORD", code(() -> xl.open(ctx, "secret.xlsx", "s", false, null, false, new SecureString("nope", "x"), true)));
        xl.open(ctx, "secret.xlsx", "s", false, "Notes", true, PW, false);
        assertEquals("Notes", xl.currentSheet(ctx, "s").get("name"));
        // header=false at open: get_all_cells without its own header input treats row 1 as data
        assertEquals(List.of("Column1"), cols(xl.getAllCells(ctx, "Notes", (Boolean) null, "s", "value", true, false)));
        assertEquals(List.of("Name", "Amount", "Status"), cols(xl.getAllCells(ctx, "Data", Boolean.TRUE, "s", "value", true, false)));
        assertEquals("EXCEL.READ_ONLY", code(() -> xl.save(ctx, null, "s")));
        assertEquals("EXCEL.READ_ONLY", code(() -> xl.close(ctx, "s", true)));
        xl.saveAs(ctx, "copy.xlsx", null, null, "s", true, null);  // a read-only session may save elsewhere and keeps the password
        xl.close(ctx, "s");
        assertEquals("EXCEL.PASSWORD_REQUIRED", code(() -> xl.open(ctx, "copy.xlsx", "c", false)));
        xl.open(ctx, "copy.xlsx", "c", false, null, false, PW, true);
        assertEquals("Acme", xl.getCell(ctx, "A2", "Data", "c").get("value"));
        xl.save(ctx, null, "c");  // re-encrypted with the open password
        xl.close(ctx, "c");
        assertEquals("EXCEL.PASSWORD_REQUIRED", code(() -> xl.open(ctx, "copy.xlsx", "c", false)));
    }

    @Test void createWorkbookSaveAsOverwriteAndCloseWithSave() {
        xl.createWorkbook(ctx, "new.xlsx", "Report", false, "n");
        assertTrue(Files.exists(dir.resolve("new.xlsx")));
        assertEquals(List.of("Report"), xl.getSheetNames(ctx, "n").get("names"));
        assertEquals("EXCEL.EXISTS", code(() -> xl.createWorkbook(ctx, "new.xlsx", "X", false, "m")));
        xl.setCell(ctx, "A1", "hello", null, "n");
        xl.close(ctx, "n", true);
        xl.open(ctx, "new.xlsx", "n", false);
        assertEquals("hello", xl.getCell(ctx, "A1", null, "n").get("value"));
        assertEquals("EXCEL.EXISTS", code(() -> xl.saveAs(ctx, "new.xlsx", null, null, "excel", false, null)));
    }

    @Test void activeCellReadAsTextAndNavigation() {
        CellStyle money = session().wb.createCellStyle();
        money.setDataFormat(session().wb.createDataFormat().getFormat("#,##0.00"));
        data().getRow(2).getCell(1).setCellStyle(money);
        assertEquals("1,200.00", xl.getCell(ctx, "B3", "Data", "excel", "text").get("value"));
        assertEquals(0, ((BigDecimal) xl.getCell(ctx, "B3", "Data", "excel", "value").get("value")).compareTo(BigDecimal.valueOf(1200)));
        xl.goToCell(ctx, "B2", "Data", "excel");
        assertEquals(300, n(xl.getCell(ctx, null, "Data", "excel", "value").get("value")));
        xl.setCell(ctx, null, 301, "Data", "excel");
        assertEquals(301, n(val("B2")));
        assertEquals("C2", xl.goToCell(ctx, null, "Data", "excel", "right").get("cell"));
        assertEquals("C5", xl.goToCell(ctx, null, "Data", "excel", "column_end").get("cell"));
        assertEquals("A5", xl.goToCell(ctx, null, "Data", "excel", "row_start").get("cell"));
        assertEquals("C5", xl.goToCell(ctx, null, "Data", "excel", "row_end").get("cell"));
        assertEquals("C1", xl.goToCell(ctx, null, "Data", "excel", "column_start").get("cell"));
        assertEquals("EXCEL.BAD_MOVE", code(() -> xl.goToCell(ctx, null, "Data", "excel", "up")));
        assertEquals("A6", xl.goToNextEmptyCell(ctx, "A1", "down", "Data", "excel").get("cell"));
        assertEquals("A6", xl.goToCell(ctx, null, "Data", "excel", "none").get("cell"));  // go_to_next_empty_cell made it active
        assertEquals("D3", xl.findNextEmptyCell(ctx, "A3", "right", "Data", "excel").get("cell"));
        assertEquals("EXCEL.NO_EMPTY_CELL", code(() -> xl.findNextEmptyCell(ctx, "A3", "up", "Data", "excel")));
        assertEquals("EXCEL.NO_EMPTY_CELL", code(() -> xl.findNextEmptyCell(ctx, "C3", "left", "Data", "excel")));
    }

    @Test void readOptionsForTablesRowsAndColumns() {
        data().getRow(2).setZeroHeight(true);   // hide Globex
        data().setColumnHidden(2, true);         // hide Status
        List<Object> visible = rows(xl.getAllCells(ctx, "Data", Boolean.TRUE, "excel", "value", false, false));
        assertEquals(3, visible.size());
        assertEquals(2, ((List<?>) visible.get(0)).size());
        data().setColumnHidden(2, false);
        data().getRow(2).setZeroHeight(false);
        data().removeRow(data().getRow(3));      // a blank row inside the data
        assertEquals(3, rows(xl.getAllCells(ctx, "Data", Boolean.TRUE, "excel", "value", true, false)).size());
        assertEquals(4, rows(xl.getAllCells(ctx, "Data", Boolean.TRUE, "excel", "value", true, true)).size());
        assertEquals(List.of("Name", "Amount"), ((List<?>) rows(xl.getRange(ctx, "A1:B2", Boolean.FALSE, "Data", "excel", "text")).get(0)));
        assertEquals(Arrays.asList("Acme", "Globex"), xl.readColumn(ctx, "A", 2, "Data", "excel", 3, "value").get("values"));
        assertEquals(Arrays.asList("Acme", "Globex", null), xl.readColumn(ctx, "A", 2, "Data", "excel", 4, "value").get("values"));
        assertEquals(Arrays.asList("Acme", "300", "Open", null), xl.readRow(ctx, 2, "A", "Data", "excel", "D", "text").get("values"));
        assertEquals(4, n(xl.rowCount(ctx, "Data", "excel", "non_empty").get("count")));
        assertEquals(5, n(xl.rowCount(ctx, "Data", "excel", "all").get("count")));
    }

    @Test void findAndReplaceOptions() {
        data().getRow(1).createCell(3).setCellFormula("B2*2");
        data().getRow(2).createCell(3).setCellFormula("B3*2");
        Map<String, Object> r = xl.find(ctx, "open", false, "Data", "excel", null, true, "values", "rows");
        assertEquals(List.of("C2", "C4", "C5"), r.get("cells"));
        assertEquals("C2", r.get("first_cell"));
        assertEquals(List.of("C2", "C5"), xl.find(ctx, "Open", true, "Data", "excel", null, true, "values", "rows").get("cells"));
        assertEquals(List.of("C4", "C5"), xl.find(ctx, "open", false, "Data", "excel", "A4:D5", false, "values", "rows").get("cells"));
        assertEquals(List.of("D2", "D3"), xl.find(ctx, "*2", false, "Data", "excel", null, false, "formulas", "rows").get("cells"));
        assertEquals(List.of("A1", "A2", "B1"), xl.find(ctx, "a", false, "Data", "excel", "A1:B2", false, "values", "columns").get("cells"));
        assertEquals(0, n(xl.find(ctx, "nothing", false, "Data", "excel", null, false, "values", "rows").get("count")));

        Map<String, Object> rep = xl.replace(ctx, "Open", "Active", null, true, false, "Data", "excel", "values", "columns", true);
        assertEquals(List.of("C2"), rep.get("cells"));
        assertEquals("Open", val("C5"));
        rep = xl.replace(ctx, "B", "A", "D2:D3", true, false, "Data", "excel", "formulas", "rows", false);
        assertEquals(2, n(rep.get("count")));
        assertEquals("A2*2", data().getRow(1).getCell(3).getCellFormula());
    }

    @Test void sheetsByIndexPositionAndHiddenCounts() {
        xl.addSheet(ctx, "First", true, "excel", 1, true);
        assertEquals(List.of("First", "Data", "Notes"), xl.getSheetNames(ctx, "excel").get("names"));
        assertEquals("First", xl.currentSheet(ctx, "excel").get("name"));
        xl.switchSheet(ctx, null, "excel", 2);
        assertEquals("Data", xl.currentSheet(ctx, "excel").get("name"));
        xl.renameSheet(ctx, null, "Memo", "excel", 3);
        xl.hideSheet(ctx, null, "excel", 3);
        assertEquals(List.of("First", "Data"), xl.getSheetNames(ctx, "excel", false).get("names"));
        assertEquals(2, n(xl.sheetCount(ctx, "excel", false).get("count")));
        assertEquals(3, n(xl.sheetCount(ctx, "excel", true).get("count")));
        xl.unhideSheet(ctx, null, "excel", 3);
        xl.deleteSheet(ctx, null, "excel", 1);
        assertEquals(List.of("Data", "Memo"), xl.getSheetNames(ctx, "excel").get("names"));
        assertEquals("EXCEL.NO_SHEET", code(() -> xl.switchSheet(ctx, null, "excel", 9)));
        assertEquals("EXCEL.NO_SHEET", code(() -> xl.deleteSheet(ctx, null, "excel", null)));
    }

    @Test void deleteEntireRowsAndColumnsAndTableRange() {
        xl.deleteCells(ctx, "B3", "entire_row", "Data", "excel");
        assertEquals("Initech", val("A3"));
        xl.deleteCells(ctx, "B1:B2", "entire_column", "Data", "excel");
        assertEquals("Status", val("B1"));
        XSSFTable t = ((XSSFSheet) data()).createTable(new AreaReference("A1:B4", SpreadsheetVersion.EXCEL2007));
        t.setName("Orders");
        t.setDisplayName("Orders");
        assertEquals("A1:B4", xl.getTableRange(ctx, null, "excel", "Orders", true).get("range"));
        assertEquals("A2:B4", xl.getTableRange(ctx, null, "excel", "Orders", false).get("range"));
    }

    @Test void colourProtectionSortFilter() {
        xl.setCellColor(ctx, "A1", "#FFC000", "fill", "Data", "excel");
        assertEquals("255,192,0", xl.getCellColor(ctx, "A1", "fill", "Data", "excel", "rgb").get("color"));
        xl.goToCell(ctx, "A1", "Data", "excel");
        assertEquals("#FFC000", xl.getCellColor(ctx, null, "fill", "Data", "excel", "hex").get("color"));

        xl.protectWorkbook(ctx, "pw", "excel", false, true);
        XSSFWorkbook wb = (XSSFWorkbook) session().wb;
        assertTrue(wb.isWindowsLocked());
        assertFalse(wb.isStructureLocked());
        assertEquals("EXCEL.NOTHING_TO_LOCK", code(() -> xl.protectWorkbook(ctx, "pw", "excel", false, false)));
        xl.unprotectWorkbook(ctx, "pw", "excel");
        assertFalse(wb.isWindowsLocked());

        xl.protectSheet(ctx, "pw", "Data", "excel", true, true, true, false, false, false, false, true, false, true, true);
        XSSFSheet s = (XSSFSheet) data();
        assertTrue(s.isSheetLocked());
        assertFalse(s.isFormatCellsLocked());
        assertTrue(s.isFormatColumnsLocked());
        assertFalse(s.isDeleteRowsLocked());
        assertFalse(s.isSortLocked());
        assertFalse(s.isAutoFilterLocked());
        assertTrue(s.isInsertRowsLocked());
        xl.unprotectSheet(ctx, "pw", "Data", "excel");

        data().getRow(4).getCell(2).setCellValue("Closed");  // Umbrella closed: two Closed rows to order by name
        xl.sortRange(ctx, "Status", false, null, null, "Data", "excel", "Name", true);
        assertEquals(List.of("Umbrella", "Globex", "Initech", "Acme"), xl.readColumn(ctx, "A", 2, "Data", "excel").get("values"));

        Map<String, Object> f = xl.filter(ctx, "Amount", "between", 100, false, null, "Data", "excel", 900, true);
        assertEquals(2, n(f.get("count")));
        assertTrue(data().getRow(2).getZeroHeight() || data().getRow(4).getZeroHeight());
        int hidden = 0;
        for (int r = 1; r <= 4; r++) if (data().getRow(r).getZeroHeight()) hidden++;
        assertEquals(2, hidden);
        assertEquals(3, n(xl.filter(ctx, "Name", "not_contains", "BREL", false, null, "Data", "excel", null, false).get("count")));
        assertEquals("EXCEL.BAD_FILTER", code(() -> xl.filter(ctx, "Amount", "between", 1, false, null, "Data", "excel", null, false)));
    }

    @Test void breakOneLinkAndChangeLink() throws Exception {
        try (XSSFWorkbook other = new XSSFWorkbook(); OutputStream o = Files.newOutputStream(dir.resolve("prices.xlsx"))) {
            other.createSheet("P").createRow(0).createCell(0).setCellValue(5);
            other.write(o);
        }
        XSSFWorkbook wb = (XSSFWorkbook) session().wb;
        try (XSSFWorkbook other = new XSSFWorkbook(Files.newInputStream(dir.resolve("prices.xlsx")))) {
            wb.linkExternalWorkbook("prices.xlsx", other);
            Cell c = data().getRow(1).createCell(5);
            c.setCellFormula("[prices.xlsx]P!A1");
            wb.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(c);
        }
        assertEquals(List.of("prices.xlsx"), xl.getLinks(ctx, "excel").get("links"));
        assertEquals(1, n(xl.changeLink(ctx, "prices.xlsx", "C:/data/prices-2026.xlsx", "excel").get("count")));
        assertEquals(List.of("C:/data/prices-2026.xlsx"), xl.getLinks(ctx, "excel").get("links"));
        assertEquals("EXCEL.NO_LINK", code(() -> xl.changeLink(ctx, "missing.xlsx", "x.xlsx", "excel")));
        assertEquals(0, n(xl.breakLinks(ctx, "excel", "other.xlsx").get("count")));
        assertEquals(1, n(xl.breakLinks(ctx, "excel", "prices-2026.xlsx").get("count")));
        assertEquals(CellType.NUMERIC, data().getRow(1).getCell(5).getCellType());
    }
}
