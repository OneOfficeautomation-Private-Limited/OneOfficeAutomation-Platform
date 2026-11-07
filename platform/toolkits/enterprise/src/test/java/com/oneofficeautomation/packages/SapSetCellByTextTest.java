package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** sap set_table_cell with find_text: the cell is found like get_table_cell_index and then written. Uses the script test hook. */
class SapSetCellByTextTest {
    record Script(String body, List<String> args, String stdin) {}

    private static final String GRID = "wnd[0]/usr/cntlGRID1/shellcont/shell";

    private static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-sap-cell"), Map.of()); }

    @Test void findsTheCellByTextThenWritesIt() throws Exception {
        List<Script> seen = new ArrayList<>();
        SapPackage.testRunner = (b, a, in) -> {
            seen.add(new Script(b, a, in));
            Map<String, Object> reply = new HashMap<>();
            if (b.contains("Search in ")) { reply.put("row", new BigDecimal(7)); reply.put("column", "MATNR"); }
            return reply;
        };
        try {
            SapPackage sap = new SapPackage();
            Map<String, Object> out = sap.setCell(ctx(), GRID, null, null, new SecureString("4712", "t"), 0, 1, "4711");
            assertEquals(2, seen.size());
            Script search = seen.get(0), write = seen.get(1);
            assertTrue(search.body().contains("Search in "), "first the search script");
            assertEquals(List.of("0", "1", GRID, "0", "0"), search.args(), "case-insensitive, first match only");
            assertEquals("\r\n4711\r\n", search.stdin(), "all columns, then the text");
            assertTrue(write.body().contains("e.modifyCell CLng(a(3)), GCol(e, col), v"));
            assertEquals(List.of("0", "1", GRID, "7"), write.args(), "row of the match");
            assertEquals("MATNR\r\n4712\r\n", write.stdin(), "column of the match, then the value");
            assertEquals(new BigDecimal(7), out.get("row"));
            assertEquals("MATNR", out.get("column"));

            // Limited to one column.
            seen.clear();
            sap.setCell(ctx(), GRID, 99, "Material", "X", 0, 0, "4711");
            assertEquals("Material\r\n4711\r\n", seen.get(0).stdin());
            assertEquals("7", seen.get(1).args().get(3), "row input is not used with find_text");
        } finally { SapPackage.testRunner = null; }
    }

    @Test void positionModeStillWorks() throws Exception {
        List<Script> seen = new ArrayList<>();
        SapPackage.testRunner = (b, a, in) -> { seen.add(new Script(b, a, in)); return new HashMap<>(); };
        try {
            SapPackage sap = new SapPackage();
            Map<String, Object> out = sap.setCell(ctx(), GRID, 2, "3", "v", 0, 0);   // earlier signature
            assertEquals(1, seen.size());
            assertEquals(List.of("0", "0", GRID, "2"), seen.get(0).args());
            assertEquals("3\r\nv\r\n", seen.get(0).stdin());
            assertEquals(new BigDecimal(2), out.get("row"));
        } finally { SapPackage.testRunner = null; }
    }

    @Test void errors() throws Exception {
        List<Script> seen = new ArrayList<>();
        SapPackage.testRunner = (b, a, in) -> {
            seen.add(new Script(b, a, in));
            Map<String, Object> reply = new HashMap<>();
            if (b.contains("Search in ")) { reply.put("row", new BigDecimal(-1)); reply.put("column", ""); }
            return reply;
        };
        try {
            SapPackage sap = new SapPackage();
            ActionContext ctx = ctx();
            assertEquals("SAP.TEXT_NOT_FOUND", assertThrows(ActionException.class, () -> sap.setCell(ctx, GRID, null, null, "v", 0, 0, "nothing")).code());
            assertEquals(1, seen.size(), "nothing is written when the text is not found");
            assertEquals("SAP.BAD_ROW", assertThrows(ActionException.class, () -> sap.setCell(ctx, GRID, null, "A", "v", 0, 0, null)).code());
            assertEquals("SAP.BAD_ROW", assertThrows(ActionException.class, () -> sap.setCell(ctx, GRID, -1, "A", "v", 0, 0, "")).code());
            assertEquals("SAP.BAD_COLUMN", assertThrows(ActionException.class, () -> sap.setCell(ctx, GRID, 1, " ", "v", 0, 0, null)).code());
            assertEquals(1, seen.size(), "bad input stops before any script runs");
        } finally { SapPackage.testRunner = null; }
    }
}
