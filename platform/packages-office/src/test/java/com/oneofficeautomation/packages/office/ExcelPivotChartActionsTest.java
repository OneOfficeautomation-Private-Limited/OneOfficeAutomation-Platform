package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pivot tables, charts, Excel tables, cell formats and text to columns (gap G02). */
class ExcelPivotChartActionsTest {

    private static final String SALES = """
            {"id":"w","action":"ooa.excel/write_table","args":{"sheet":"Sales","table":{"columns":["Region","Quarter","Amount","Year"],
               "rows":[["North","Q1",100,2026],["South","Q1",80,2026],["North","Q2",120,2026],["South","Q2",60,2026],["North","Q1",30,2025]]}}}""";

    @SuppressWarnings("unchecked")
    private static RunResult run(Path w, String steps) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"a","type":"Any","direction":"output"},{"name":"b","type":"Any","direction":"output"},
                          {"name":"c","type":"Any","direction":"output"}],
             "steps":[{"id":"o","action":"ooa.excel/create_workbook","args":{"path":"b.xlsx","sheet":"Sales"}},%s,
                      {"id":"z","action":"ooa.excel/close","args":{"save":true}}]}""".formatted(steps));
        return Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
    }

    private static XSSFWorkbook reopen(Path w) throws Exception {
        try (InputStream in = Files.newInputStream(w.resolve("b.xlsx"))) { return new XSSFWorkbook(in); }
    }

    @Test void pivotTableWithRowsColumnsValuesAndFilter() throws Exception {
        Path w = Files.createTempDirectory("ooa-xlp");
        RunResult r = run(w, SALES + """
            ,{"id":"p","action":"ooa.excel/create_pivot_table","args":{"sheet":"Sales","rows":["Region"],"columns":["Quarter"],
               "values":["Amount","Amount:count"],"filters":["Year"],"name":"ByRegion"},"out":{"name":"a","sheet":"b"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("ByRegion", r.outputs().get("a"));
        assertEquals("Pivot", r.outputs().get("b"));
        try (XSSFWorkbook wb = reopen(w)) {
            List<XSSFPivotTable> pts = wb.getSheet("Pivot").getPivotTables();
            assertEquals(1, pts.size());
            var def = pts.get(0).getCTPivotTableDefinition();
            assertEquals("ByRegion", def.getName());
            assertEquals(1, def.getRowFields().sizeOfFieldArray());
            assertEquals(0, def.getRowFields().getFieldArray(0).getX(), "Region is source column 0");
            assertEquals(2, def.getDataFields().sizeOfDataFieldArray());
            assertEquals("Sum of Amount", def.getDataFields().getDataFieldArray(0).getName());
            assertEquals("Count of Amount", def.getDataFields().getDataFieldArray(1).getName());
            assertEquals(1, def.getPageFields().sizeOfPageFieldArray());
            assertTrue(pts.get(0).getPivotCacheDefinition().getCTPivotCacheDefinition().getRefreshOnLoad());
            boolean quarterIsColumn = false;
            for (var f : def.getColFields().getFieldArray()) quarterIsColumn |= f.getX() == 1;
            assertTrue(quarterIsColumn, "Quarter (source column 1) is a column field");
        }
    }

    @Test void pivotTableRefusesUnknownFieldsAndFunctions() throws Exception {
        Path w = Files.createTempDirectory("ooa-xlp");
        RunResult r = run(w, SALES + """
            ,{"id":"p","action":"ooa.excel/create_pivot_table","args":{"sheet":"Sales","rows":["Country"],"values":["Amount"]}}""");
        assertEquals("EXCEL.BAD_COLUMN", r.error().get("code"));
        RunResult f = run(Files.createTempDirectory("ooa-xlp"), SALES + """
            ,{"id":"p","action":"ooa.excel/create_pivot_table","args":{"sheet":"Sales","rows":["Region"],"values":["Amount:median"]}}""");
        assertEquals("EXCEL.BAD_FUNCTION", f.error().get("code"));
    }

    @Test void columnLineAndPieCharts() throws Exception {
        Path w = Files.createTempDirectory("ooa-xlc");
        RunResult r = run(w, """
            {"id":"w","action":"ooa.excel/write_table","args":{"sheet":"Sales","table":{"columns":["Month","North","South"],
               "rows":[["Jan",10,7],["Feb",12,9],["Mar",15,8]]}}},
            {"id":"c","action":"ooa.excel/create_chart","args":{"range":"A1:C4","title":"Sales by month"},"out":{"range":"a"}},
            {"id":"l","action":"ooa.excel/create_chart","args":{"range":"A1:C4","chart_type":"line","destination_sheet":"Charts","position":"A1"}},
            {"id":"p","action":"ooa.excel/create_chart","args":{"range":"A1:B4","chart_type":"pie","destination_sheet":"Charts","position":"J1","legend":"bottom"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("E1:L15", r.outputs().get("a"), "two columns right of the data, 8 x 15 cells");
        try (XSSFWorkbook wb = reopen(w)) {
            List<XSSFChart> sales = wb.getSheet("Sales").getDrawingPatriarch().getCharts();
            assertEquals(1, sales.size());
            assertEquals("Sales by month", sales.get(0).getTitleText().getString());
            assertEquals(2, sales.get(0).getChartSeries().get(0).getSeriesCount());
            List<XSSFChart> charts = wb.getSheet("Charts").getDrawingPatriarch().getCharts();
            assertEquals(2, charts.size());
            assertEquals(1, charts.get(1).getChartSeries().get(0).getSeriesCount(), "a pie shows the first series");
        }
    }

    @Test void excelTableWithStyle() throws Exception {
        Path w = Files.createTempDirectory("ooa-xlt");
        RunResult r = run(w, SALES + """
            ,{"id":"t","action":"ooa.excel/create_table","args":{"sheet":"Sales","name":"SalesData","style":"TableStyleLight9"},"out":{"name":"a"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("SalesData", r.outputs().get("a"));
        try (XSSFWorkbook wb = reopen(w)) {
            XSSFTable t = wb.getTable("SalesData");
            assertNotNull(t);
            assertEquals("A1:D6", t.getArea().formatAsString().replace("$", "").replaceAll("^.*!", ""));
            assertEquals("TableStyleLight9", t.getCTTable().getTableStyleInfo().getName());
            assertEquals(4, t.getColumnCount());
        }
        RunResult again = run(Files.createTempDirectory("ooa-xlt"), SALES + """
            ,{"id":"t","action":"ooa.excel/create_table","args":{"sheet":"Sales","name":"A1"}}""");
        assertEquals("EXCEL.BAD_NAME", again.error().get("code"));
        RunResult style = run(Files.createTempDirectory("ooa-xlt"), SALES + """
            ,{"id":"t","action":"ooa.excel/create_table","args":{"sheet":"Sales","style":"Fancy"}}""");
        assertEquals("EXCEL.BAD_STYLE", style.error().get("code"));
    }

    @Test void formatCellsChangesOnlyWhatIsGiven() throws Exception {
        Path w = Files.createTempDirectory("ooa-xlf");
        RunResult r = run(w, SALES + """
            ,{"id":"h","action":"ooa.excel/format_cells","args":{"range":"A1:D1","bold":true,"fill_color":"light blue","borders":"all","horizontal":"center"},"out":{"count":"a"}},
            {"id":"n","action":"ooa.excel/format_cells","args":{"range":"C2:C6","number_format":"#,##0.00","font_color":"dark red"}},
            {"id":"u","action":"ooa.excel/format_cells","args":{"range":"A2:D6","borders":"outline"}},
            {"id":"i","action":"ooa.excel/format_cells","args":{"range":"A1","italic":true}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.valueOf(4).compareTo((BigDecimal) r.outputs().get("a")));
        try (XSSFWorkbook wb = reopen(w)) {
            XSSFSheet s = wb.getSheet("Sales");
            XSSFCellStyle a1 = s.getRow(0).getCell(0).getCellStyle();
            assertTrue(a1.getFont().getBold(), "bold kept when italic was added later");
            assertTrue(a1.getFont().getItalic());
            assertEquals(FillPatternType.SOLID_FOREGROUND, a1.getFillPattern());
            assertEquals(HorizontalAlignment.CENTER, a1.getAlignment());
            assertEquals(BorderStyle.THIN, a1.getBorderBottom());
            XSSFCellStyle c3 = s.getRow(2).getCell(2).getCellStyle();
            assertEquals("#,##0.00", c3.getDataFormatString());
            assertNotNull(c3.getFont().getXSSFColor());
            XSSFCellStyle b4 = s.getRow(3).getCell(1).getCellStyle();
            assertEquals(BorderStyle.NONE, b4.getBorderTop(), "inside the outline: no lines");
            assertEquals(BorderStyle.THIN, s.getRow(1).getCell(1).getCellStyle().getBorderTop(), "top edge of the outline");
            assertEquals(BorderStyle.THIN, s.getRow(5).getCell(3).getCellStyle().getBorderRight());
        }
    }

    @Test void textToColumnsWithQuotesNumbersAndCodes() throws Exception {
        Path w = Files.createTempDirectory("ooa-xlx");
        RunResult r = run(w, """
            {"id":"a","action":"ooa.excel/set_cell","args":{"cell":"A1","value":"Acme, \\"Main St, 4\\", 12.5, 0123"}},
            {"id":"b","action":"ooa.excel/set_cell","args":{"cell":"A2","value":"Zeta,Elm St"}},
            {"id":"t","action":"ooa.excel/text_to_columns","args":{"column":"A","destination":"C"},"out":{"rows":"a","columns":"b"}},
            {"id":"g","action":"ooa.excel/get_cell","args":{"cell":"E1"},"out":{"value":"c"}}""");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("a")));
        assertEquals(0, BigDecimal.valueOf(4).compareTo((BigDecimal) r.outputs().get("b")));
        assertEquals(0, new BigDecimal("12.5").compareTo((BigDecimal) r.outputs().get("c")));
        try (XSSFWorkbook wb = reopen(w)) {
            XSSFSheet s = wb.getSheetAt(0);
            assertEquals("Main St, 4", s.getRow(0).getCell(3).getStringCellValue());
            assertEquals("0123", s.getRow(0).getCell(5).getStringCellValue(), "a leading zero keeps it as text");
            assertEquals("Elm St", s.getRow(1).getCell(3).getStringCellValue());
            assertTrue(s.getRow(0).getCell(0).getStringCellValue().startsWith("Acme"), "the source column stays when another destination is given");
        }
    }
}
