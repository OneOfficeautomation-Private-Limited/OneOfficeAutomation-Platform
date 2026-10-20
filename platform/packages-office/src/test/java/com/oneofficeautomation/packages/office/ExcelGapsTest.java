package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelGapsTest {
    private final ExcelPackage xl = new ExcelPackage();
    private Path dir;
    private ActionContext ctx;

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-xl-gaps");
        ctx = OfficeTestSupport.ctx(dir);
        Object[][] data = {{"Name", "Amount", "Status"}, {"Acme", 300, "Open"}, {"Globex", 1200, "Closed"}, {"Initech", 50, "open"}, {"Umbrella", 800, "Open"}};
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(dir.resolve("book.xlsx"))) {
            fill(wb.createSheet("Data"), data);
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("note");
            wb.write(out);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(dir.resolve("other.xlsx"))) {
            fill(wb.createSheet("Other"), new Object[][]{{"x", 1}, {"y", 2}});
            fill(wb.createSheet("Data"), new Object[][]{{"dup"}});
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

    private Workbook wb() { return ctx.session("excel", ExcelPackage.WorkbookSession.class).wb; }
    private Sheet data() { return wb().getSheet("Data"); }
    private Object val(String sheet, String address) { return xl.getCell(ctx, address, sheet, "excel").get("value"); }
    private static BigDecimal n(Object o) { return (BigDecimal) o; }

    private Workbook reopen(String file) throws Exception {
        try (InputStream in = Files.newInputStream(dir.resolve(file))) { return WorkbookFactory.create(in); }
    }

    @Test void appendWorkbookWorksheetAndCopySheets() {
        assertEquals(List.of("Other", "Data (2)"), xl.appendWorkbook(ctx, "other.xlsx", "excel").get("sheets"));
        assertEquals("y", val("Other", "A2"));
        assertEquals("dup", val("Data (2)", "A1"));
        assertEquals("Imported", xl.appendWorksheet(ctx, "other.xlsx", "Other", "Imported", "excel").get("name"));
        assertEquals(0, n(val("Imported", "B2")).compareTo(BigDecimal.valueOf(2)));
        assertEquals("Data copy", xl.copySheet(ctx, "Data", "Data copy", "excel").get("name"));
        xl.renameSheet(ctx, "Data copy", "Backup", "excel");
        assertEquals("Globex", val("Backup", "A3"));
        assertEquals(0, n(xl.sheetCount(ctx, "excel").get("count")).compareTo(BigDecimal.valueOf(6)));
        assertThrows(ActionException.class, () -> xl.renameSheet(ctx, "Backup", "Notes", "excel"));
    }

    @Test void switchHideAndUnhideSheets() {
        xl.switchSheet(ctx, "Notes", "excel");
        assertEquals("Notes", xl.currentSheet(ctx, "excel").get("name"));
        assertEquals("note", val(null, "A1"));
        xl.hideSheet(ctx, "Notes", "excel");
        assertTrue(wb().isSheetHidden(1));
        assertEquals("Data", xl.currentSheet(ctx, "excel").get("name"));
        ActionException last = assertThrows(ActionException.class, () -> xl.hideSheet(ctx, "Data", "excel"));
        assertEquals("EXCEL.LAST_VISIBLE_SHEET", last.code());
        xl.unhideSheet(ctx, "Notes", "excel");
        assertFalse(wb().isSheetHidden(1));
        xl.hideSheet(ctx, "Notes", "excel");
        assertEquals(0, n(xl.unhideAllSheets(ctx, "excel").get("count")).compareTo(BigDecimal.ONE));
        assertFalse(wb().isSheetHidden(1));
    }

    @Test void insertAndDeleteRowsColumnsAndCells() {
        xl.insertRows(ctx, 2, 2, "Data", "excel");
        assertNull(val("Data", "A2"));
        assertEquals("Acme", val("Data", "A4"));
        xl.deleteRows(ctx, 2, 2, "Data", "excel");
        assertEquals("Acme", val("Data", "A2"));
        assertEquals("Umbrella", val("Data", "A5"));

        xl.insertColumns(ctx, "B", 1, "Data", "excel");
        assertNull(val("Data", "B1"));
        assertEquals("Amount", val("Data", "C1"));
        xl.deleteColumns(ctx, 2, 1, "Data", "excel");
        assertEquals("Amount", val("Data", "B1"));
        assertEquals("Status", val("Data", "C1"));

        xl.deleteCells(ctx, "A2", "up", "Data", "excel");
        assertEquals("Globex", val("Data", "A2"));
        assertEquals("Umbrella", val("Data", "A4"));
        assertNull(val("Data", "A5"));
        assertEquals(0, n(val("Data", "B2")).compareTo(BigDecimal.valueOf(300)));  // other columns untouched

        xl.deleteCells(ctx, "A1:A1", "left", "Data", "excel");
        assertEquals("Amount", val("Data", "A1"));
        assertEquals("Status", val("Data", "B1"));
        assertNull(val("Data", "C1"));
    }

    @Test void hideRowsAndColumns() {
        xl.hideRows(ctx, 2, 2, "Data", "excel");
        assertTrue(data().getRow(1).getZeroHeight() && data().getRow(2).getZeroHeight());
        xl.unhideRows(ctx, 2, 2, "Data", "excel");
        assertFalse(data().getRow(1).getZeroHeight());
        xl.hideColumns(ctx, "C", 1, "Data", "excel");
        assertTrue(data().isColumnHidden(2));
        xl.unhideColumns(ctx, 3, 1, "Data", "excel");
        assertFalse(data().isColumnHidden(2));
    }

    @Test void addressesCountsAndNavigation() {
        assertEquals("B7", xl.cellAddress(ctx, 7, "B").get("cell"));
        assertEquals("AB3", xl.cellAddress(ctx, 3, BigDecimal.valueOf(28)).get("cell"));
        assertEquals("AB", xl.columnName(ctx, 28).get("name"));
        Map<String, Object> rn = xl.rowNumber(ctx, "AB12");
        assertEquals(0, n(rn.get("row")).compareTo(BigDecimal.valueOf(12)));
        assertEquals(0, n(rn.get("column")).compareTo(BigDecimal.valueOf(28)));

        Map<String, Object> rc = xl.rowCount(ctx, "Data", "excel");
        assertEquals(0, n(rc.get("count")).compareTo(BigDecimal.valueOf(5)));
        assertEquals(0, n(rc.get("last_row")).compareTo(BigDecimal.valueOf(5)));
        assertEquals("A1:C5", xl.getTableRange(ctx, "Data", "excel").get("range"));

        assertEquals("A6", xl.findNextEmptyCell(ctx, "A1", "down", "Data", "excel").get("cell"));
        assertEquals("D2", xl.findNextEmptyCell(ctx, "A2", "right", "Data", "excel").get("cell"));
        xl.switchSheet(ctx, "Notes", "excel");
        xl.goToCell(ctx, "B3", "Data", "excel");
        assertEquals("Data", xl.currentSheet(ctx, "excel").get("name"));
        assertEquals("B3", data().getActiveCell().formatAsString());
        assertEquals("B6", xl.findNextEmptyCell(ctx, null, "down", null, "excel").get("cell"));  // starts from the remembered cell
        xl.selectRange(ctx, "C2:C4", "Data", "excel");
        assertEquals("C6", xl.findNextEmptyCell(ctx, null, "down", null, "excel").get("cell"));
    }

    @Test void readFormulaColumnAndRow() {
        xl.setFormula(ctx, "B6", "SUM(B2:B5)", "Data", "excel");
        assertEquals("=SUM(B2:B5)", xl.readFormula(ctx, "B6", "Data", "excel").get("formula"));
        assertEquals(false, xl.readFormula(ctx, "A2", "Data", "excel").get("has_formula"));
        List<?> col = (List<?>) xl.readColumn(ctx, "A", 2, "Data", "excel").get("values");
        assertEquals(List.of("Acme", "Globex", "Initech", "Umbrella"), col);
        List<?> amounts = (List<?>) xl.readColumn(ctx, BigDecimal.valueOf(2), 2, "Data", "excel").get("values");
        assertEquals(0, n(amounts.get(4)).compareTo(BigDecimal.valueOf(2350)));
        List<?> row = (List<?>) xl.readRow(ctx, 3, "B", "Data", "excel").get("values");
        assertEquals(2, row.size());
        assertEquals("Closed", row.get(1));
    }

    @Test void cellColours() throws Exception {
        xl.setCellColor(ctx, "A1:C1", "#FFC000", "fill", "Data", "excel");
        xl.setCellColor(ctx, "B2", "c00000", "font", "Data", "excel");
        assertEquals("#FFC000", xl.getCellColor(ctx, "B1", "fill", "Data", "excel").get("color"));
        assertEquals("#C00000", xl.getCellColor(ctx, "B2", "font", "Data", "excel").get("color"));
        assertEquals("", xl.getCellColor(ctx, "A3", "fill", "Data", "excel").get("color"));
        assertEquals("Name", val("Data", "A1"));  // value kept
        xl.save(ctx, null, "excel");
        try (Workbook back = reopen("book.xlsx")) {
            XSSFCellStyle st = (XSSFCellStyle) back.getSheet("Data").getRow(0).getCell(2).getCellStyle();
            assertEquals(FillPatternType.SOLID_FOREGROUND, st.getFillPattern());
            assertEquals("FFFFC000", st.getFillForegroundXSSFColor().getARGBHex());
        }
        assertThrows(ActionException.class, () -> xl.setCellColor(ctx, "A1", "orange", "fill", "Data", "excel"));
    }

    @Test void protectionOfSheetAndWorkbook() throws Exception {
        xl.protectSheet(ctx, "s3cret", "Data", "excel");
        assertTrue(data().getProtect());
        ActionException wrong = assertThrows(ActionException.class, () -> xl.unprotectSheet(ctx, "nope", "Data", "excel"));
        assertEquals("EXCEL.WRONG_PASSWORD", wrong.code());
        xl.protectWorkbook(ctx, "wbpass", "excel");
        xl.save(ctx, "locked.xlsx", "excel");
        try (Workbook back = reopen("locked.xlsx")) {
            XSSFWorkbook x = (XSSFWorkbook) back;
            assertTrue(x.isStructureLocked());
            assertTrue(x.validateWorkbookPassword("wbpass"));
            assertTrue(x.getSheet("Data").validateSheetPassword("s3cret"));
        }
        assertEquals("EXCEL.WRONG_PASSWORD", assertThrows(ActionException.class, () -> xl.unprotectWorkbook(ctx, "bad", "excel")).code());
        xl.unprotectWorkbook(ctx, "wbpass", "excel");
        xl.unprotectSheet(ctx, "s3cret", "Data", "excel");
        assertFalse(((XSSFWorkbook) wb()).isStructureLocked());
        assertFalse(data().getProtect());
    }

    @Test void replaceRemoveBlankRowsSortAndFilter() {
        assertEquals(0, n(xl.replace(ctx, "open", "Pending", null, true, false, "Data", "excel").get("count")).compareTo(BigDecimal.ONE));
        assertEquals("Pending", val("Data", "C4"));
        assertEquals("Open", val("Data", "C2"));
        assertEquals(0, n(xl.replace(ctx, "OPEN", "Active", "C1:C3", false, true, "Data", "excel").get("count")).compareTo(BigDecimal.ONE));
        assertEquals("Active", val("Data", "C2"));
        assertEquals("Open", val("Data", "C5"));  // outside range
        assertEquals(0, n(xl.replace(ctx, "o", "0", null, false, true, "Data", "excel").get("count")).signum());  // whole cell only

        xl.insertRows(ctx, 3, 2, "Data", "excel");
        assertEquals(0, n(xl.removeBlankRows(ctx, "Data", "excel").get("count")).compareTo(BigDecimal.valueOf(2)));
        assertEquals("Globex", val("Data", "A3"));
        assertEquals("A1:C5", xl.getTableRange(ctx, "Data", "excel").get("range"));

        xl.sortRange(ctx, "Amount", true, true, null, "Data", "excel");
        assertEquals("Name", val("Data", "A1"));
        assertEquals(List.of("Globex", "Umbrella", "Acme", "Initech"), xl.readColumn(ctx, "A", 2, "Data", "excel").get("values"));
        xl.sortRange(ctx, "A", false, false, "A2:C5", "Data", "excel");
        assertEquals(List.of("Acme", "Globex", "Initech", "Umbrella"), xl.readColumn(ctx, "A", 2, "Data", "excel").get("values"));
        assertEquals(0, n(val("Data", "B3")).compareTo(BigDecimal.valueOf(1200)));  // whole rows move together

        Map<String, Object> f = xl.filter(ctx, "Status", "equals", "open", false, true, "Data", "excel");
        assertEquals(0, n(f.get("count")).compareTo(BigDecimal.valueOf(1)));  // C2 became Active, C4 Pending; only C5 Open
        Map<String, Object> big = xl.filter(ctx, "B", "gt", BigDecimal.valueOf(299), false, true, "Data", "excel");
        @SuppressWarnings("unchecked") Map<String, Object> table = (Map<String, Object>) big.get("table");
        assertEquals(List.of("Name", "Amount", "Status"), table.get("columns"));
        assertEquals(3, ((List<?>) table.get("rows")).size());
        assertEquals(0, n(xl.filter(ctx, "Name", "starts_with", "g", false, true, "Data", "excel").get("count")).compareTo(BigDecimal.ONE));
    }

    @Test void excelTableColumns() throws Exception {
        XSSFSheet sh = (XSSFSheet) data();
        XSSFTable t = sh.createTable(new AreaReference("A1:C5", SpreadsheetVersion.EXCEL2007));
        t.setName("Sales");
        t.setDisplayName("Sales");
        xl.insertTableColumn(ctx, "Sales", "Region", 2, "excel");
        assertEquals("Region", val("Data", "B1"));
        assertEquals("Amount", val("Data", "C1"));
        assertEquals(0, n(val("Data", "C2")).compareTo(BigDecimal.valueOf(300)));
        assertEquals("A1:D5", t.getArea().formatAsString().replace("$", ""));
        xl.deleteTableColumn(ctx, "Sales", "Amount", "excel");
        assertEquals("Status", val("Data", "C1"));
        assertNull(val("Data", "D1"));
        xl.save(ctx, "tables.xlsx", "excel");
        try (Workbook back = reopen("tables.xlsx")) {
            XSSFTable bt = ((XSSFSheet) back.getSheet("Data")).getTables().get(0);
            List<String> names = new ArrayList<>();
            for (XSSFTableColumn c : bt.getColumns()) names.add(c.getName());
            assertEquals(List.of("Name", "Region", "Status"), names);
            assertEquals("A1:C5", bt.getArea().formatAsString().replace("$", ""));
        }
        assertEquals("EXCEL.NO_TABLE", assertThrows(ActionException.class, () -> xl.deleteTableColumn(ctx, "Nope", "x", "excel")).code());
    }

    @Test void externalLinksListAndBreak() throws Exception {
        XSSFWorkbook x = (XSSFWorkbook) wb();
        try (XSSFWorkbook src = new XSSFWorkbook()) {
            src.createSheet("Rates").createRow(0).createCell(0).setCellValue(1.25);
            x.linkExternalWorkbook("rates.xlsx", src);
            Cell c = data().getRow(1).createCell(3);
            c.setCellFormula("[rates.xlsx]Rates!A1");
            c.setCellValue(1.25);  // cached result
        }
        Map<String, Object> links = xl.getLinks(ctx, "excel");
        assertEquals(List.of("rates.xlsx"), links.get("links"));
        assertEquals(List.of("Data!D2"), links.get("cells"));
        assertEquals(0, n(xl.breakLinks(ctx, "excel").get("count")).compareTo(BigDecimal.ONE));
        Cell c = data().getRow(1).getCell(3);
        assertEquals(CellType.NUMERIC, c.getCellType());
        assertEquals(1.25, c.getNumericCellValue());
        assertEquals(List.of(), xl.getLinks(ctx, "excel").get("cells"));
        xl.save(ctx, "unlinked.xlsx", "excel");
        try (Workbook back = reopen("unlinked.xlsx")) { assertEquals(1.25, back.getSheet("Data").getRow(1).getCell(3).getNumericCellValue()); }
    }

    @Test void sensitivityLabels() throws Exception {
        assertEquals(false, xl.getSensitivityLabel(ctx, "excel").get("has_label"));
        String label = "1b2c3d4e-0000-4000-8000-0000000000aa", site = "9f8e7d6c-1111-4111-8111-1234567890ab";
        xl.setSensitivityLabel(ctx, label, site, "Confidential", "Privileged", "excel");
        xl.setSensitivityLabel(ctx, label, site, "Confidential", null, "excel");  // replaces, does not duplicate
        xl.save(ctx, null, "excel");
        xl.open(ctx, "book.xlsx", "check", false);
        Map<String, Object> got = xl.getSensitivityLabel(ctx, "check");
        assertEquals(true, got.get("has_label"));
        assertEquals(label, got.get("label_id"));
        assertEquals("Confidential", got.get("name"));
        assertEquals(site, got.get("site_id"));
        assertEquals("Standard", got.get("method"));
        try (Workbook back = reopen("book.xlsx")) {
            var props = ((XSSFWorkbook) back).getProperties().getCustomProperties();
            assertTrue(props.contains("MSIP_Label_" + label + "_Enabled"));
            long n = props.getUnderlyingProperties().getPropertyList().stream().filter(p -> p.getName().startsWith("MSIP_Label_")).count();
            assertEquals(7, n);
        }
        assertThrows(ActionException.class, () -> xl.setSensitivityLabel(ctx, "not-a-guid", site, null, null, "excel"));
    }

    @Test void saveAsCsvXlsAndXlsx() throws Exception {
        xl.saveAs(ctx, "out/data.csv", null, "Data", "excel");
        List<String> lines = Files.readAllLines(dir.resolve("out/data.csv"));
        assertEquals("Name,Amount,Status", lines.get(0));
        assertEquals("Globex,1200,Closed", lines.get(2));

        xl.saveAs(ctx, "out/legacy.xls", "xls", null, "excel");
        assertInstanceOf(HSSFWorkbook.class, wb());
        try (Workbook back = reopen("out/legacy.xls")) {
            assertInstanceOf(HSSFWorkbook.class, back);
            assertEquals("Umbrella", back.getSheet("Data").getRow(4).getCell(0).getStringCellValue());
            assertEquals("note", back.getSheet("Notes").getRow(0).getCell(0).getStringCellValue());
        }
        xl.setCell(ctx, "D1", "Added", "Data", "excel");
        xl.save(ctx, null, "excel");  // the session now points at the .xls file
        try (Workbook back = reopen("out/legacy.xls")) { assertEquals("Added", back.getSheet("Data").getRow(0).getCell(3).getStringCellValue()); }

        xl.saveAs(ctx, "out/modern.xlsx", null, null, "excel");
        try (Workbook back = reopen("out/modern.xlsx")) {
            assertInstanceOf(XSSFWorkbook.class, back);
            assertEquals("Added", back.getSheet("Data").getRow(0).getCell(3).getStringCellValue());
        }
        assertEquals("EXCEL.BAD_FORMAT", assertThrows(ActionException.class, () -> xl.saveAs(ctx, "x.ods", null, null, "excel")).code());
    }

    @Test void newActionsRegisterAndRunInAFlow() {
        var reg = com.oneofficeautomation.packages.FirstPartyPackages.registry();
        Map<String, Object> flow = castMap(com.oneofficeautomation.sdk.JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"addr","type":"String","direction":"output"},{"name":"n","type":"Number","direction":"output"}],
             "steps":[
               {"id":"o","action":"ooa.excel/open","args":{"path":"book.xlsx"}},
               {"id":"a","action":"ooa.excel/cell_address","args":{"row":7,"column":"B"},"out":{"cell":"addr"}},
               {"id":"c","action":"ooa.excel/sheet_count","args":{},"out":{"count":"n"}}
             ]}"""));
        var r = com.oneofficeautomation.runner.Runner.builder(reg).workDir(dir).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("B7", r.outputs().get("addr"));
        assertEquals(0, n(r.outputs().get("n")).compareTo(BigDecimal.valueOf(2)));
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unused") private static String ref(int r, int c) { return new CellReference(r, c).formatAsString(false); }
}
