package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G2 (AA parity, 21 Nov 2026), Database: {{name}} places from the vault, a SQL Server instance and Oracle's three ways
 * in, a connection child agents may use, export that skips an empty result or adds to a file, and bulk insert by
 * column name with a time limit.
 */
class DatabaseParityG2Test {
    @TempDir Path tmp;
    final DatabasePackage db = new DatabasePackage();

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> r) { return (List<List<Object>>) ((Map<String, Object>) r.get("table")).get("rows"); }

    @Test void addressesAndVaultPlaces() {
        assertEquals("jdbc:sqlserver://db01;instanceName=SQLEXPRESS;databaseName=erp", DatabasePackage.jdbcUrl("sqlserver", "db01", null, "erp", null, "SQLEXPRESS", null));
        assertEquals("jdbc:oracle:thin:@//ora:1521/ORCLPDB1", DatabasePackage.jdbcUrl("oracle", "ora", null, "ORCLPDB1", null, null, "service"));
        assertEquals("jdbc:oracle:thin:@ora:1522:ORCL", DatabasePackage.jdbcUrl("oracle", "ora", 1522, "ORCL", null, null, "sid"));
        assertEquals("jdbc:oracle:thin:@PRODDB", DatabasePackage.jdbcUrl("oracle", "ora", null, "PRODDB", null, null, "tns"));
        assertEquals("jdbc:sqlserver://db;user=etl;password=s3cr3t",
                DatabasePackage.fillSecrets("jdbc:sqlserver://db;user={{ user }};password={{pwd}}", Map.of("user", "etl", "pwd", new SecureString("s3cr3t", "vault"))));
        assertEquals("DB.NO_SECRET", assertThrows(ActionException.class, () -> DatabasePackage.fillSecrets("jdbc:x://{{pwd}}", Map.of())).code());
    }

    @SuppressWarnings("unchecked")
    @Test void aSharedConnectionAndExportThatSkipsOrAdds() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(tmp, Map.of());
        db.connect(ctx, "jdbc:h2:mem:g2exp;DB_CLOSE_DELAY=-1", null, null, null, null, null, null, null, "db", null, 0, null, false, true, null, null, true, null);
        assertTrue(((Set<String>) ctx.sessions().get(ActionContext.SHARED_SESSIONS)).contains("db"), "child agents may use it");
        db.execute(ctx, "CREATE TABLE t (id INT, name VARCHAR(20))", null, "db", 0);
        Path out = tmp.resolve("out.csv");
        Map<String, Object> none = db.exportToFile(ctx, "SELECT id, name FROM t", out.toString(), null, ",", true, "utf-8", "db", 0, true, null, 0, false, true);
        assertEquals("", none.get("path"));
        assertFalse(Files.exists(out), "no rows: no file");
        db.execute(ctx, "INSERT INTO t VALUES (1, 'a')", null, "db", 0);
        db.exportToFile(ctx, "SELECT id, name FROM t", out.toString(), null, ",", true, "utf-8", "db", 0, true, null, 0, false, true);
        db.exportToFile(ctx, "SELECT id, name FROM t", out.toString(), null, ",", true, "utf-8", "db", 0, false, null, 0, true, false);
        assertEquals(List.of("ID,NAME", "1,a", "1,a"), Files.readAllLines(out), "added without a second header");
    }

    @Test void bulkInsertByColumnName() {
        ActionContext ctx = PackagesTestSupport.ctx(tmp, Map.of());
        db.connect(ctx, "jdbc:h2:mem:g2bulk;DB_CLOSE_DELAY=-1", null, null, null, null, null, null, null, "db", null, 0, null, false, true);
        db.execute(ctx, "CREATE TABLE inv (invoice_no VARCHAR(20), amount DECIMAL(10,2))", null, "db", 0);
        Map<String, Object> data = Values.table(List.of("Invoice No", "Vendor", "Amount"),
                List.of(new ArrayList<>(List.of("INV-1", "Acme", new BigDecimal("10.50"))), new ArrayList<>(List.of("INV-2", "Bolt", new BigDecimal("20")))));
        Map<String, Object> r = db.bulkInsert(ctx, "inv", data, null, 500, "db", null, ",", 1, true, "utf-8",
                new LinkedHashMap<>(Map.of("Invoice No", "invoice_no", "Amount", "amount")), 1800);
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.get("rows_inserted")));
        assertEquals(List.of(List.of("INV-1", new BigDecimal("10.50")), List.of("INV-2", new BigDecimal("20.00"))),
                rows(db.query(ctx, "SELECT invoice_no, amount FROM inv ORDER BY invoice_no", null, 100, "db", 0)));
        assertEquals("DB.COLUMN_MISMATCH", assertThrows(ActionException.class,
                () -> db.bulkInsert(ctx, "inv", data, null, 500, "db", null, ",", 1, true, "utf-8", Map.of("Nope", "x"), 0)).code());
    }
}
