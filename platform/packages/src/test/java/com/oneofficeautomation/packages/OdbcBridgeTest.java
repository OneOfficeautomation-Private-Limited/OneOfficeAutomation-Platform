package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.CommandTestFixesTest.code;
import static com.oneofficeautomation.packages.CommandTestFixesTest.flow;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ODBC through jdbc:odbc: (gap: connect via ODBC, Access/Excel via ODBC). The Windows helper is replaced by a stand-in
 * speaking the same protocol over SQLite, so the JDBC side (statements, parameters, transactions, batches, the table and
 * column lists, read-only and the 32-bit retry) is checked anywhere; the real helper is checked on Windows runners.
 */
class OdbcBridgeTest {
    static Optional<String> python() {
        for (String p : List.of("python3", "python")) {
            try { Process x = new ProcessBuilder(p, "-c", "import sqlite3").start(); if (x.waitFor() == 0) return Optional.of(p); }
            catch (Exception ignored) { }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    @Test void statementsTransactionsBatchesAndSchemaOverOdbc() throws Exception {
        Optional<String> py = python();
        if (py.isEmpty()) { System.out.println("    (no Python with sqlite3 here; skipped)"); return; }
        Path w = tmp();
        Path helper = w.resolve("helper.py");
        try (var in = OdbcBridgeTest.class.getResourceAsStream("fake_odbc_helper.py")) { Files.copy(in, helper); }
        OdbcBridge.helperCommand = List.of(py.get(), helper.toString());
        try {
            String url = "jdbc:odbc:Driver={Fake};Database=" + w.resolve("odbc.db").toString().replace('\\', '/');
            String connect = "{\"id\":\"c\",\"action\":\"ooa.database/connect\",\"args\":{\"jdbc_url\":\"" + url + "\"}}";
            String vars = "[{\"name\":\"t\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"n\",\"type\":\"Any\",\"direction\":\"output\"},"
                    + "{\"name\":\"tables\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"cols\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"after\",\"type\":\"Any\",\"direction\":\"output\"}]";
            Map<String, Object> out = PackagesTest.run(w, vars, "[" + connect + ","
                    + "{\"id\":\"e1\",\"action\":\"ooa.database/execute\",\"args\":{\"sql\":\"CREATE TABLE inv (id INTEGER, customer TEXT, amount REAL)\"}},"
                    + "{\"id\":\"e2\",\"action\":\"ooa.database/execute\",\"args\":{\"sql\":\"INSERT INTO inv VALUES (?, ?, ?)\",\"parameters\":[1, \"Acme\", 120.5]},\"out\":{\"rows_affected\":\"n\"}},"
                    + "{\"id\":\"b\",\"action\":\"ooa.database/bulk_insert\",\"args\":{\"table\":\"inv\",\"data\":{\"columns\":[\"id\",\"customer\",\"amount\"],\"rows\":[[2,\"Beta\",7],[3,\"Gamma\",9.25]]}}},"
                    + "{\"id\":\"bt\",\"action\":\"ooa.database/begin_transaction\",\"args\":{}},"
                    + "{\"id\":\"e3\",\"action\":\"ooa.database/execute\",\"args\":{\"sql\":\"DELETE FROM inv\"}},"
                    + "{\"id\":\"rb\",\"action\":\"ooa.database/rollback\",\"args\":{}},"
                    + "{\"id\":\"q\",\"action\":\"ooa.database/query\",\"args\":{\"sql\":\"SELECT id, customer, amount FROM inv WHERE amount > ? ORDER BY id\",\"parameters\":[5]},\"out\":{\"table\":\"t\"}},"
                    + "{\"id\":\"lt\",\"action\":\"ooa.database/list_tables\",\"args\":{},\"out\":{\"table\":\"tables\"}},"
                    + "{\"id\":\"dt\",\"action\":\"ooa.database/describe_table\",\"args\":{\"table\":\"inv\"},\"out\":{\"table\":\"cols\"}},"
                    + "{\"id\":\"q2\",\"action\":\"ooa.database/query\",\"args\":{\"sql\":\"SELECT COUNT(*) AS c FROM inv\"},\"out\":{\"table\":\"after\"}},"
                    + "{\"id\":\"d\",\"action\":\"ooa.database/disconnect\",\"args\":{}}]", Map.of());
            assertEquals(new BigDecimal(1), out.get("n"));
            Map<String, Object> t = (Map<String, Object>) out.get("t");
            assertEquals(List.of("id", "customer", "amount"), t.get("columns"));
            List<List<Object>> rows = (List<List<Object>>) t.get("rows");
            assertEquals(3, rows.size(), "the rolled-back DELETE removed nothing: " + rows);
            assertEquals("Acme", rows.get(0).get(1));
            assertEquals(0, new BigDecimal("120.5").compareTo(new BigDecimal(String.valueOf(rows.get(0).get(2)))));
            assertTrue(String.valueOf(out.get("tables")).contains("inv"), String.valueOf(out.get("tables")));
            assertTrue(String.valueOf(out.get("cols")).contains("customer"), String.valueOf(out.get("cols")));
            assertTrue(String.valueOf(out.get("after")).contains("3"));

            // read-only sessions refuse changes (the ODBC side does not enforce it, so OOA does)
            String ro = "{\"id\":\"c\",\"action\":\"ooa.database/connect\",\"args\":{\"jdbc_url\":\"" + url + "\",\"read_only\":true}}";
            assertEquals("DB.READ_ONLY", code(flow(w, "[]", "[" + ro + ",{\"id\":\"e\",\"action\":\"ooa.database/execute\",\"args\":{\"sql\":\"DELETE FROM inv\"}}]")));
            // ODBC errors come back as database errors; a missing driver is tried on the 32-bit side too, then reported
            RunResult bad = flow(w, "[]", "[" + connect + ",{\"id\":\"e\",\"action\":\"ooa.database/execute\",\"args\":{\"sql\":\"SELECT * FROM nope\"}}]");
            assertTrue(String.valueOf(bad.error()).contains("no such table"), String.valueOf(bad.error()));
            RunResult missing = flow(w, "[]", "[{\"id\":\"c\",\"action\":\"ooa.database/connect\",\"args\":{\"jdbc_url\":\"jdbc:odbc:Driver={Missing};Database=x\"}}]");
            assertEquals("DB.CONNECT_FAILED", code(missing));
            assertTrue(String.valueOf(missing.error()).contains("64-bit and 32-bit"), String.valueOf(missing.error()));
        } finally { OdbcBridge.helperCommand = null; }
    }

    @Test void addressesForOdbcAndExcel() {
        assertEquals("jdbc:odbc:DSN=Sales", DatabasePackage.jdbcUrl("odbc", null, null, "DSN=Sales", null));
        assertEquals("jdbc:odbc:Sales", DatabasePackage.jdbcUrl("odbc", null, null, "Sales", null));
        assertTrue(DatabasePackage.jdbcUrl("excel", null, null, null, "C:\\data\\book.xlsx").contains("Microsoft Excel Driver (*.xls, *.xlsx, *.xlsm, *.xlsb)};DBQ=C:\\data\\book.xlsx"));
        assertEquals("{pa{ss;}}word}", OdbcBridge.brace("pa{ss;}word"));
        assertEquals("{a;b}", OdbcBridge.brace("a;b"));
        assertEquals("plain", OdbcBridge.brace("plain"));
        assertEquals("IM002", OdbcBridge.sqlState("ERROR [IM002] [Microsoft][ODBC Driver Manager] Data source name not found"));
    }
}
