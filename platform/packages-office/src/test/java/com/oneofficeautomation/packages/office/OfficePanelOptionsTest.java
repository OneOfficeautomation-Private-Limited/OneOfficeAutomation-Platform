package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Excel close/delete_cells/delete_table_column/filter options and Word add_table alternative text. */
class OfficePanelOptionsTest {
    private final ExcelPackage xl = new ExcelPackage();
    private Path dir;
    private ActionContext ctx;

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-office-options");
        ctx = OfficeTestSupport.ctx(dir);
        // a note row, then a table at B3:D7
        Object[][] data = {{"Report"}, {}, {null, "Name", "Amount", "Status"}, {null, "Acme", 300, "Open"}, {null, "Globex", 1200, "Closed"},
                {null, "Initech", 50, "Open"}, {null, "Umbrella", 800, "Open"}, {}, {null, "Other", 5, "Open"}};
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(dir.resolve("book.xlsx"))) {
            Sheet s = wb.createSheet("Data");
            for (int r = 0; r < data.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    Object v = data[r][c];
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else if (v != null) row.createCell(c).setCellValue((String) v);
                }
            }
            XSSFTable t = ((XSSFSheet) s).createTable(new AreaReference("B3:D7", SpreadsheetVersion.EXCEL2007));
            t.setName("Sales");
            t.setDisplayName("Sales");
            wb.write(out);
        }
    }

    private Object val(String address) { return xl.getCell(ctx, address, "Data", "excel").get("value"); }
    private static int n(Object o) { return ((BigDecimal) o).intValue(); }
    private static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    @Test void closeReadOnlyWorkbook() throws Exception {
        xl.open(ctx, "book.xlsx", "excel", false, null, true, null, true);
        xl.setCell(ctx, "A1", "changed", "Data", "excel");
        assertEquals("EXCEL.READ_ONLY", code(() -> xl.close(ctx, "excel", true)));
        xl.close(ctx, "excel", true, false);   // closes without saving
        assertTrue(ctx.sessions().isEmpty());
        try (InputStream in = Files.newInputStream(dir.resolve("book.xlsx")); Workbook wb = WorkbookFactory.create(in)) {
            assertEquals("Report", wb.getSheet("Data").getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test void deleteCellsOfTheSelectionOrActiveCell() {
        xl.open(ctx, "book.xlsx", "excel", false);
        xl.selectRange(ctx, "C4:C5", "Data", "excel");
        xl.deleteCells(ctx, null, "up", null, "excel");
        assertEquals(0, new BigDecimal(50).compareTo((BigDecimal) val("C4")));
        assertEquals("Acme", val("B4"));
        xl.goToCell(ctx, "B4", "Data", "excel");
        xl.deleteCells(ctx, "", "left", null, "excel");
        assertEquals(0, new BigDecimal(50).compareTo((BigDecimal) val("B4")));
    }

    @Test void deleteTableColumnByPosition() {
        xl.open(ctx, "book.xlsx", "excel", false);
        xl.deleteTableColumn(ctx, "Sales", null, "excel", 2);
        assertEquals("Status", val("C3"));
        assertEquals("Open", val("C4"));
        assertEquals("EXCEL.BAD_POSITION", code(() -> xl.deleteTableColumn(ctx, "Sales", null, "excel", 3)));
        assertEquals("EXCEL.BAD_COLUMN", code(() -> xl.deleteTableColumn(ctx, "Sales", "", "excel", null)));
        xl.deleteTableColumn(ctx, "Sales", "Status", "excel");   // old signature, by header
        assertEquals("Name", val("B3"));
        assertNull(val("C3"));
    }

    @SuppressWarnings("unchecked")
    @Test void filterATableOrARange() {
        xl.open(ctx, "book.xlsx", "excel", false);
        Map<String, Object> t = xl.filter(ctx, "Status", "equals", "Open", false, null, null, "excel", null, true, "Sales", null);
        assertEquals(3, n(t.get("count")), "the Other row below the table is not counted");
        assertEquals(List.of("Name", "Amount", "Status"), ((Map<String, Object>) t.get("table")).get("columns"));
        Sheet sh = ctx.session("excel", ExcelPackage.WorkbookSession.class).wb.getSheet("Data");
        assertTrue(sh.getRow(4).getZeroHeight(), "Globex hidden");
        assertFalse(sh.getRow(3).getZeroHeight());
        assertFalse(sh.getRow(8).getZeroHeight(), "rows outside the table untouched");
        Map<String, Object> r = xl.filter(ctx, "C", "gt", 100, false, true, "Data", "excel", null, false, null, "B3:D7");
        assertEquals(3, n(r.get("count")));
        Map<String, Object> byNumber = xl.filter(ctx, 3, "gte", 800, false, true, "Data", "excel", null, false, null, "B3:D7");
        assertEquals(2, n(byNumber.get("count")), "column 3 is sheet column C");
        assertEquals("EXCEL.NO_TABLE", code(() -> xl.filter(ctx, "Status", "equals", "Open", false, null, null, "excel", null, false, "Nope", null)));
    }

    @Test void wordTableAlternativeText() throws Exception {
        WordPackage word = new WordPackage();
        word.createDocument(ctx, "t.docx", false, "word");
        Map<String, Object> table = Map.of("columns", List.of("A", "B"), "rows", List.of(List.of("1", "2")));
        word.addTable(ctx, table, "word", true, null, null, null, "Quarterly totals", "Totals per quarter for 2026");
        word.addTable(ctx, table, "word");
        word.save(ctx, null, "word");
        try (InputStream in = Files.newInputStream(dir.resolve("t.docx")); XWPFDocument d = new XWPFDocument(in)) {
            XWPFTable first = d.getTables().get(0);
            assertEquals("Quarterly totals", first.getCTTbl().getTblPr().getTblCaption().getVal());
            assertEquals("Totals per quarter for 2026", first.getCTTbl().getTblPr().getTblDescription().getVal());
            assertFalse(d.getTables().get(1).getCTTbl().getTblPr().isSetTblCaption());
        }
    }
}
