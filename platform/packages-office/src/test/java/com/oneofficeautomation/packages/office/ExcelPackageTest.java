package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelPackageTest {

    @SuppressWarnings("unchecked")
    @Test void invoiceSummaryBot() throws Exception {
        Path w = Files.createTempDirectory("ooa-xl");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(w.resolve("invoices.xlsx"))) {
            Sheet s = wb.createSheet("Invoices");
            Object[][] data = {{"Invoice", "Vendor", "Amount"}, {"INV-1", "Acme", 1200.5}, {"INV-2", "Globex", 99}, {"INV-3", "Acme", 5000}};
            for (int r = 0; r < data.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < 3; c++) {
                    Object v = data[r][c];
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue()); else row.createCell(c).setCellValue((String) v);
                }
            }
            s.createRow(4).createCell(2).setCellFormula("SUM(C2:C4)");
            wb.write(out);
        }
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"xl","name":"xl",
             "variables":[{"name":"t","type":"Table"},{"name":"big","type":"Table"},
               {"name":"total","type":"Number","direction":"output"},{"name":"formula","type":"Number","direction":"output"},
               {"name":"n","type":"Number","direction":"output"},{"name":"found","type":"List","direction":"output"}],
             "steps":[
               {"id":"o","action":"ooa.excel/open","args":{"path":"invoices.xlsx"}},
               {"id":"f","action":"ooa.excel/get_cell","args":{"cell":"C5"},"out":{"value":"formula"}},
               {"id":"r","action":"ooa.excel/get_range","args":{"range":"A1:C4"},"out":{"table":"t"}},
               {"id":"s","action":"ooa.datatable/sum_column","args":{"table":"$t","column":"Amount"},"out":{"total":"total"}},
               {"id":"fi","action":"ooa.datatable/filter","args":{"table":"$t","column":"Amount","operator":"gt","value":1000},"out":{"table":"big","count":"n"}},
               {"id":"so","action":"ooa.datatable/sort","args":{"table":"$big","column":"Amount","descending":true},"out":{"table":"big"}},
               {"id":"a","action":"ooa.excel/add_sheet","args":{"name":"Review"}},
               {"id":"wt","action":"ooa.excel/write_table","args":{"table":"$big","sheet":"Review"}},
               {"id":"fd","action":"ooa.excel/find","args":{"text":"acme","sheet":"Invoices"},"out":{"cells":"found"}},
               {"id":"sv","action":"ooa.excel/save","args":{}}
             ]}""");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, new BigDecimal("6299.5").compareTo((BigDecimal) r.outputs().get("total")));
        assertEquals(0, new BigDecimal("6299.5").compareTo((BigDecimal) r.outputs().get("formula")));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("n")));
        assertEquals(List.of("B2", "B4"), r.outputs().get("found"));
        try (InputStream in = Files.newInputStream(w.resolve("invoices.xlsx")); Workbook wb = WorkbookFactory.create(in)) {
            assertEquals("INV-3", wb.getSheet("Review").getRow(1).getCell(0).getStringCellValue());
        }
    }
}
