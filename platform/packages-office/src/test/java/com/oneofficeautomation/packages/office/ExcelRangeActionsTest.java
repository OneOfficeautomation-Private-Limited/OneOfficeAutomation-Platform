package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Copy, clear, fill, remove duplicates, autofit, column widths and row heights (gap G01). */
class ExcelRangeActionsTest {

    /** Sheet "Data": A1:C1 header, rows 2..5 with an amount and a formula =B{n}*2, A2 bold. */
    private static Path book(boolean xls) throws Exception {
        Path w = Files.createTempDirectory("ooa-xlr");
        Path f = w.resolve(xls ? "b.xls" : "b.xlsx");
        try (Workbook wb = xls ? new HSSFWorkbook() : new XSSFWorkbook(); OutputStream out = Files.newOutputStream(f)) {
            Sheet s = wb.createSheet("Data");
            wb.createSheet("Copy");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Vendor"); h.createCell(1).setCellValue("Amount"); h.createCell(2).setCellValue("Double");
            String[] vendors = {"Acme", "Zeta", "acme", "Acme"};
            double[] amounts = {10, 20, 10, 10};
            for (int i = 0; i < 4; i++) {
                Row r = s.createRow(i + 1);
                r.createCell(0).setCellValue(vendors[i]);
                r.createCell(1).setCellValue(amounts[i]);
                r.createCell(2).setCellFormula("B" + (i + 2) + "*2");
            }
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont(); font.setBold(true); bold.setFont(font);
            s.getRow(1).getCell(0).setCellStyle(bold);
            wb.write(out);
        }
        return w;
    }

    @SuppressWarnings("unchecked")
    private static RunResult run(Path w, String file, String steps) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"a","type":"Any","direction":"output"},{"name":"b","type":"Any","direction":"output"},
                          {"name":"c","type":"Any","direction":"output"},{"name":"d","type":"Any","direction":"output"}],
             "steps":[{"id":"o","action":"ooa.excel/open","args":{"path":"%s"}},%s,
                      {"id":"z","action":"ooa.excel/close","args":{"save":true}}]}""".formatted(file, steps));
        return Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
    }

    private static Workbook reopen(Path f) throws Exception {
        try (InputStream in = Files.newInputStream(f)) { return WorkbookFactory.create(in); }
    }

    @Test void copyAllMovesRelativeReferencesAndKeepsFormats() throws Exception {
        Path w = book(false);
        RunResult r = run(w, "b.xlsx", """
            {"id":"c","action":"ooa.excel/copy_range","args":{"range":"A2:C3","destination":"E10"},"out":{"range":"a"}},
            {"id":"d","action":"ooa.excel/copy_range","args":{"range":"A1:C5","destination":"B2","sheet":"Data","destination_sheet":"Copy"},"out":{"range":"b"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("E10:G11", r.outputs().get("a"));
        assertEquals("B2:D6", r.outputs().get("b"));
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals("Acme", s.getRow(9).getCell(4).getStringCellValue());
            assertEquals("F10*2", s.getRow(9).getCell(6).getCellFormula());
            assertEquals("F11*2", s.getRow(10).getCell(6).getCellFormula());
            assertTrue(wb.getFontAt(s.getRow(9).getCell(4).getCellStyle().getFontIndex()).getBold(), "format copied");
            Sheet c = wb.getSheet("Copy");
            assertEquals("Vendor", c.getRow(1).getCell(1).getStringCellValue());
            assertEquals("C3*2", c.getRow(2).getCell(3).getCellFormula());
        }
    }

    @Test void copyValuesFormulasAndFormats() throws Exception {
        Path w = book(false);
        RunResult r = run(w, "b.xlsx", """
            {"id":"v","action":"ooa.excel/copy_range","args":{"range":"C2:C3","destination":"H2","paste":"values"}},
            {"id":"f","action":"ooa.excel/copy_range","args":{"range":"C2","destination":"I2","paste":"formulas"}},
            {"id":"m","action":"ooa.excel/copy_range","args":{"range":"A2","destination":"J2","paste":"formats"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals(CellType.NUMERIC, s.getRow(1).getCell(7).getCellType());
            assertEquals(20, s.getRow(1).getCell(7).getNumericCellValue());
            assertEquals(40, s.getRow(2).getCell(7).getNumericCellValue());
            assertEquals("H2*2", s.getRow(1).getCell(8).getCellFormula());
            Cell fmt = s.getRow(1).getCell(9);
            assertEquals(CellType.BLANK, fmt.getCellType());
            assertTrue(wb.getFontAt(fmt.getCellStyle().getFontIndex()).getBold());
        }
    }

    @Test void copyOntoItselfOverlappingAndOffTheSheet() throws Exception {
        Path w = book(false);
        RunResult ok = run(w, "b.xlsx", """
            {"id":"c","action":"ooa.excel/copy_range","args":{"range":"B2:B5","destination":"B3","paste":"values"}}""");
        assertTrue(ok.ok(), String.valueOf(ok.error()));
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals(List.of(10.0, 10.0, 20.0, 10.0, 10.0), List.of(s.getRow(1).getCell(1).getNumericCellValue(), s.getRow(2).getCell(1).getNumericCellValue(),
                    s.getRow(3).getCell(1).getNumericCellValue(), s.getRow(4).getCell(1).getNumericCellValue(), s.getRow(5).getCell(1).getNumericCellValue()));
        }
        RunResult bad = run(w, "b.xlsx", """
            {"id":"s","action":"ooa.excel/set_formula","args":{"cell":"E3","formula":"B2"}},
            {"id":"c","action":"ooa.excel/copy_range","args":{"range":"E3","destination":"E1"}}""");
        assertFalse(bad.ok());
        assertEquals("EXCEL.BAD_RANGE", bad.error().get("code"), "=B2 two rows up would point above row 1");
    }

    @Test void clearContentsFormatsOrAll() throws Exception {
        Path w = book(false);
        RunResult r = run(w, "b.xlsx", """
            {"id":"a","action":"ooa.excel/clear_range","args":{"range":"A2:B2"},"out":{"count":"a"}},
            {"id":"b","action":"ooa.excel/clear_range","args":{"range":"A3","clear":"formats"}},
            {"id":"g","action":"ooa.excel/go_to_cell","args":{"cell":"C5"}},
            {"id":"c","action":"ooa.excel/clear_range","args":{"clear":"all"},"out":{"count":"b"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("a")));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) r.outputs().get("b")));
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            Cell a2 = s.getRow(1).getCell(0);
            assertEquals(CellType.BLANK, a2.getCellType());
            assertTrue(wb.getFontAt(a2.getCellStyle().getFontIndex()).getBold(), "contents cleared, format kept");
            assertEquals("Zeta", s.getRow(2).getCell(0).getStringCellValue());
            assertNull(s.getRow(4).getCell(2), "the active cell was removed");
            assertEquals("B4*2", s.getRow(3).getCell(2).getCellFormula(), "the cell next to it is untouched");
        }
    }

    @Test void fillDownAndRight() throws Exception {
        Path w = book(false);
        RunResult r = run(w, "b.xlsx", """
            {"id":"s","action":"ooa.excel/set_formula","args":{"cell":"D2","formula":"B2+C2"}},
            {"id":"f","action":"ooa.excel/fill_range","args":{"range":"D2:D5"},"out":{"count":"a"}},
            {"id":"t","action":"ooa.excel/set_cell","args":{"cell":"F1","value":"Q1"}},
            {"id":"g","action":"ooa.excel/fill_range","args":{"range":"F1:H1","direction":"right"},"out":{"count":"b"}},
            {"id":"v","action":"ooa.excel/get_cell","args":{"cell":"D5"},"out":{"value":"c"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.valueOf(3).compareTo((BigDecimal) r.outputs().get("a")));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("b")));
        assertEquals(0, BigDecimal.valueOf(30).compareTo((BigDecimal) r.outputs().get("c")));
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals("B5+C5", s.getRow(4).getCell(3).getCellFormula());
            assertEquals("Q1", s.getRow(0).getCell(7).getStringCellValue());
        }
    }

    @Test void removeDuplicatesByAllOrChosenColumns() throws Exception {
        Path w = book(false);
        RunResult r = run(w, "b.xlsx", """
            {"id":"d","action":"ooa.excel/remove_duplicates","args":{"range":"A1:B5","columns":["Vendor"]},"out":{"count":"a","remaining":"b"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("a")), "acme matches Acme without match_case");
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("b")));
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals("Vendor", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Acme", s.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Zeta", s.getRow(2).getCell(0).getStringCellValue());
            assertNull(s.getRow(3).getCell(0), "rows below move up and the freed cells are empty");
            assertEquals("B4*2", s.getRow(3).getCell(2).getCellFormula(), "column C is outside the range and stays");
        }
        Path w2 = book(false);
        RunResult r2 = run(w2, "b.xlsx", """
            {"id":"d","action":"ooa.excel/remove_duplicates","args":{"range":"A1:B5","match_case":true},"out":{"count":"a"}}""");
        assertTrue(r2.ok(), String.valueOf(r2.error()));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) r2.outputs().get("a")), "only the second Acme 10 row repeats");
        RunResult bad = run(w2, "b.xlsx", """
            {"id":"d","action":"ooa.excel/remove_duplicates","args":{"range":"A1:B5","columns":["Double"]}}""");
        assertEquals("EXCEL.BAD_COLUMN", bad.error().get("code"));
    }

    @Test void removeDuplicatesKeepsFormulasPointingAtTheirRows() throws Exception {
        Path w = book(false);
        RunResult r = run(w, "b.xlsx", """
            {"id":"t","action":"ooa.excel/set_formula","args":{"cell":"B7","formula":"SUM(B2:B5)"}},
            {"id":"d","action":"ooa.excel/remove_duplicates","args":{"range":"A1:C5","columns":["Vendor"]},"out":{"count":"a"}},
            {"id":"v","action":"ooa.excel/get_cell","args":{"cell":"C3"},"out":{"value":"b"}},
            {"id":"s","action":"ooa.excel/get_cell","args":{"cell":"B7"},"out":{"value":"c"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.valueOf(40).compareTo((BigDecimal) r.outputs().get("b")), "Zeta's =B3*2 moved up with its row: 20*2");
        assertEquals(0, BigDecimal.valueOf(30).compareTo((BigDecimal) r.outputs().get("c")), "the total shrinks to the rows left");
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals("B3*2", s.getRow(2).getCell(2).getCellFormula());
            assertEquals("SUM(B2:B3)", s.getRow(6).getCell(1).getCellFormula());
        }
    }

    @Test void widthsHeightsAndAutofit() throws Exception {
        Path w = book(false);
        RunResult r = run(w, "b.xlsx", """
            {"id":"w","action":"ooa.excel/set_column_width","args":{"column":"B","count":2,"width":20}},
            {"id":"h","action":"ooa.excel/set_row_height","args":{"row":1,"height":30}},
            {"id":"s","action":"ooa.excel/set_cell","args":{"cell":"E1","value":"A rather long heading that needs room"}},
            {"id":"a","action":"ooa.excel/autofit_columns","args":{"column":"E"},"out":{"count":"a"}},
            {"id":"b","action":"ooa.excel/autofit_columns","args":{"sheet":"Copy"},"out":{"count":"b"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) r.outputs().get("a")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) r.outputs().get("b")), "an empty sheet has no used columns");
        try (Workbook wb = reopen(w.resolve("b.xlsx"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals(5302, s.getColumnWidth(1), "(20 * 7 + 5) / 7 * 256: Excel shows 20");
            assertEquals(5302, s.getColumnWidth(2));
            assertEquals(30f, s.getRow(0).getHeightInPoints());
            assertTrue(s.getColumnWidth(4) > 20 * 256, "autofit widened E for its long text: " + s.getColumnWidth(4));
        }
        RunResult bad = run(w, "b.xlsx", """
            {"id":"w","action":"ooa.excel/set_column_width","args":{"column":"B","width":300}}""");
        assertFalse(bad.ok());
    }

    @Test void legacyXlsWorkbooksToo() throws Exception {
        Path w = book(true);
        RunResult r = run(w, "b.xls", """
            {"id":"c","action":"ooa.excel/copy_range","args":{"range":"C2","destination":"D3"}},
            {"id":"f","action":"ooa.excel/fill_range","args":{"range":"C2:C4"}},
            {"id":"d","action":"ooa.excel/remove_duplicates","args":{"range":"A1:A5"},"out":{"count":"a"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        try (Workbook wb = reopen(w.resolve("b.xls"))) {
            Sheet s = wb.getSheet("Data");
            assertEquals("C3*2", s.getRow(2).getCell(3).getCellFormula());
            assertEquals("B4*2", s.getRow(3).getCell(2).getCellFormula());
        }
    }
}
