package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3 (AA parity, 21 Nov 2026), Word and PowerPoint: find without replacing (the default for new steps), a bookmark as
 * the place to look, text inserted where the last insert ended, PowerPoint's current slide, a table's name and total
 * height; Excel's Find and Replace from the active cell, a given cell or the end.
 */
class OfficeParityG3Test {
    @TempDir Path dir;
    final WordPackage word = new WordPackage();
    final PowerPointPackage ppt = new PowerPointPackage();

    static int n(Object o) { return ((Number) o).intValue(); }

    @SuppressWarnings("unchecked")
    @Test void wordFindsWithoutReplacingAndInsertsAtTheCursor() {
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        word.open(ctx, "letter.docx", true, "w");
        word.insertText(ctx, "Dear customer,", "end", null, true, "w");
        word.insertText(ctx, "Thanks.", "end", null, true, "w");
        word.insertText(ctx, "Your order shipped.", "cursor", null, true, "w");   // after "Thanks.", the last insert
        assertEquals(List.of("Dear customer,", "Thanks.", "Your order shipped."),
                ((List<Object>) word.readText(ctx, "w").get("paragraphs")).stream().filter(p -> !String.valueOf(p).isBlank()).toList());
        Map<String, Object> found = word.replaceText(ctx, "CUSTOMER", "", "w", false, false, "all", null, null, null, false, null);
        assertEquals(1, n(found.get("count")), "found, case ignored");
        assertTrue(String.valueOf(word.readText(ctx, "w").get("text")).contains("Dear customer,"), "nothing changed when only finding");
        assertEquals("WORD.NO_BOOKMARK", assertThrows(ActionException.class,
                () -> word.replaceText(ctx, "x", "", "w", true, false, "all", null, null, null, false, "nowhere")).code());
    }

    @Test void powerPointCurrentSlideFindOnlyAndTableNameAndHeight() {
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        ppt.open(ctx, "deck.pptx", true, "p");
        ppt.addSlide(ctx, "title", "Agenda", "{{who}}", null, "p");
        ppt.addSlide(ctx, "title", "Close", "{{who}}", null, "p");
        ppt.addSlide(ctx, "title", "Q&A", "Questions", 1, "p");   // added at position 1: now the current slide, not the last
        Map<String, Object> t = Values.table(List.of("Region", "Sales"), List.of(List.of("EU", 10), List.of("US", 20)));
        ppt.insertTable(ctx, null, t, 10, 100, 300, 12, "p", false, null, null, 80.0, "Sales by region");
        var sl = ((PowerPointPackage.DeckSession) ctx.sessions().get("p")).ppt.getSlides();
        var table = sl.get(0).getShapes().stream().filter(x -> x instanceof org.apache.poi.xslf.usermodel.XSLFTable).map(x -> (org.apache.poi.xslf.usermodel.XSLFTable) x).findFirst().orElseThrow();
        assertEquals("Sales by region", table.getShapeName(), "on the current slide (1), named");
        assertEquals(40.0, table.getRows().get(0).getHeight(), 0.01, "80 points shared by 2 rows");
        assertEquals(2, n(ppt.findReplaceText(ctx, "{{who}}", "", null, "p", true, false, "all", false).get("count")), "counted, not replaced");
        assertEquals(2, n(ppt.findReplaceText(ctx, "{{who}}", "", null, "p", true, false, "all", false).get("count")), "still there");
    }

    @Test void excelFindsFromTheActiveCellAGivenCellOrTheEnd() throws Exception {
        ExcelPackage excel = new ExcelPackage();
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sh = wb.createSheet("S");
            for (int r = 0; r < 4; r++) sh.createRow(r).createCell(0).setCellValue("x");
            sh.setActiveCell(new org.apache.poi.ss.util.CellAddress("A3"));
            try (var out = Files.newOutputStream(dir.resolve("f.xlsx"))) { wb.write(out); }
        }
        excel.open(ctx, "f.xlsx", "excel", false);
        assertEquals(List.of("A3", "A4", "A1", "A2"), excel.find(ctx, "x", false, null, "excel", null, false, "values", "rows", "active_cell", null).get("cells"));
        assertEquals(List.of("A2", "A3", "A4", "A1"), excel.find(ctx, "x", false, null, "excel", null, false, "values", "rows", "cell", "A2").get("cells"));
        assertEquals(List.of("A4", "A3", "A2", "A1"), excel.find(ctx, "x", false, null, "excel", null, false, "values", "rows", "end", null).get("cells"));
        Map<String, Object> r = excel.replace(ctx, "x", "y", null, false, false, null, "excel", "values", "rows", true, "cell", "A3");
        assertEquals(List.of("A3"), r.get("cells"), "the first one from A3");
    }

    @Test void excelOpensACsvFileAndSavesItBackAsCsv() throws Exception {
        ExcelPackage excel = new ExcelPackage();
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        Files.writeString(dir.resolve("rates.csv"), "\uFEFFName;Note\r\nAcme;\"says \"\"hi\"\"; twice\"\r\nZeta;\"two\nlines\"\r\n");
        excel.open(ctx, "rates.csv", "excel", false);
        assertEquals("says \"hi\"; twice", excel.getCell(ctx, "B2", null, "excel").get("value"), "quotes, semicolon delimiter, BOM dropped");
        assertEquals("two\nlines", excel.getCell(ctx, "B3", null, "excel").get("value"));
        assertEquals("Name", excel.getCell(ctx, "A1", "rates", "excel").get("value"), "the sheet is named after the file");
        excel.setCell(ctx, "C1", "Total", null, "excel");
        excel.save(ctx, null, "excel");
        assertEquals("Name;Note;Total\r\nAcme;\"says \"\"hi\"\"; twice\";\r\nZeta;\"two\nlines\";\r\n", Files.readString(dir.resolve("rates.csv")),
                "saved back as CSV with the same delimiter");
        excel.close(ctx, "excel");
        excel.open(ctx, "new.csv", "n", true);
        excel.setCell(ctx, "A1", "x,y", null, "n");
        excel.save(ctx, null, "n");
        assertEquals("\"x,y\"\r\n", Files.readString(dir.resolve("new.csv")), "a new CSV file is written as CSV");
    }

    @Test void theExcelSessionIsAnOpenWorkbookForTheDesktopSteps() throws Exception {
        ExcelPackage excel = new ExcelPackage();
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        excel.open(ctx, "book.xlsx", "excel", true);
        excel.setCell(ctx, "A1", "unsaved", null, "excel");
        OpenWorkbook w = (OpenWorkbook) ctx.sessions().get("excel");
        assertEquals("book.xlsx", w.file().getFileName().toString());
        assertEquals("Sheet1", w.activeSheet());
        Path copy = dir.resolve("copy").resolve("book.xlsx");
        Files.createDirectories(copy.getParent());
        w.writeCopy(copy);
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(copy.toFile())) {
            assertEquals("unsaved", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue(), "the copy has the unsaved change");
            wb.getSheetAt(0).getRow(0).getCell(0).setCellValue("by the macro");
            try (var out = Files.newOutputStream(dir.resolve("copy").resolve("after.xlsx"))) { wb.write(out); }
        }
        w.replaceWith(dir.resolve("copy").resolve("after.xlsx"));
        assertEquals("by the macro", excel.getCell(ctx, "A1", null, "excel").get("value"), "the session holds what the macro made");
        w.save();
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(dir.resolve("book.xlsx").toFile())) {
            assertEquals("by the macro", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue(), "saved to its own file");
        }
    }

    @Test void powerPointChecksThePasswordToEditWhenOpening() {
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        ppt.createPresentation(ctx, "locked.pptx", true, "p", null, null, new SecureString("Edit-1", "e"));
        ppt.save(ctx, null, "p");
        ppt.close(ctx, "p");
        ppt.open(ctx, "locked.pptx", false, "a", false, null, new SecureString("Edit-1", "e"));
        ppt.open(ctx, "locked.pptx", false, "b", false, null, null);   // without it the file still opens
        assertEquals("PPT.WRONG_EDIT_PASSWORD", assertThrows(ActionException.class,
                () -> ppt.open(ctx, "locked.pptx", false, "c", false, null, new SecureString("wrong", "e"))).code());
        ppt.open(ctx, "plain.pptx", true, "d", false, null, new SecureString("any", "e"));   // no password to edit: nothing to check
    }
}
