package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Sort moves formulas with their rows, as Excel does (defect XL-1, 3 Nov 2026). */
class ExcelSortFormulasTest {

    @SuppressWarnings("unchecked")
    @Test void formulasFollowTheirRowsAndAbsoluteReferencesStay() throws Exception {
        Path w = Files.createTempDirectory("ooa-xls");
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"t","type":"Table","direction":"output"}],
             "steps":[
               {"id":"o","action":"ooa.excel/create_workbook","args":{"path":"s.xlsx"}},
               {"id":"w","action":"ooa.excel/write_table","args":{"table":{"columns":["Vendor","Amount","VAT","Rate"],
                  "rows":[["Zeta",250,null,null],["Globex",80,null,null],["Acme",100,null,null]]}}},
               {"id":"r","action":"ooa.excel/set_cell","args":{"cell":"F1","value":0.2}},
               {"id":"a","action":"ooa.excel/set_formula","args":{"cell":"C2","formula":"B2*$F$1"}},
               {"id":"b","action":"ooa.excel/set_formula","args":{"cell":"C3","formula":"B3*$F$1"}},
               {"id":"c","action":"ooa.excel/set_formula","args":{"cell":"C4","formula":"B4*$F$1"}},
               {"id":"s","action":"ooa.excel/sort_range","args":{"column":"Vendor"}},
               {"id":"g","action":"ooa.excel/get_range","args":{"range":"A1:C4"},"out":{"table":"t"}},
               {"id":"z","action":"ooa.excel/close","args":{"save":true}}]}""");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        List<List<Object>> rows = (List<List<Object>>) ((Map<String, Object>) r.outputs().get("t")).get("rows");
        assertEquals("Acme", rows.get(0).get(0));
        assertEquals(0, BigDecimal.valueOf(20).compareTo(new BigDecimal(String.valueOf(rows.get(0).get(2)))), "Acme 100 * 0.2, not Zeta's 50");
        assertEquals(0, BigDecimal.valueOf(16).compareTo(new BigDecimal(String.valueOf(rows.get(1).get(2)))));
        assertEquals(0, BigDecimal.valueOf(50).compareTo(new BigDecimal(String.valueOf(rows.get(2).get(2)))));
        try (InputStream in = Files.newInputStream(w.resolve("s.xlsx")); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            assertEquals("B2*$F$1", sh.getRow(1).getCell(2).getCellFormula());
            assertEquals("B4*$F$1", sh.getRow(3).getCell(2).getCellFormula());
        }
    }
}
