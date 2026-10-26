package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Database options added after the command comparison: bulk insert from a delimited file, export of a procedure's rows with a row limit. */
class DatabaseMoreOptionsTest {
    @TempDir Path tmp;
    final DatabasePackage db = new DatabasePackage();

    ActionContext connect(String url, String user) {
        ActionContext ctx = PackagesTestSupport.ctx(tmp, Map.of());
        db.connect(ctx, url, null, null, null, null, null, user, null, "db", null, 0, null, false, true);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> r) { return (List<List<Object>>) ((Map<String, Object>) r.get("table")).get("rows"); }

    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(OoaException.class, e).code(); }

    @Test void bulkInsertStreamsADelimitedFile() throws Exception {
        ActionContext ctx = connect("jdbc:h2:mem:bulkfile;DB_CLOSE_DELAY=-1", null);
        db.execute(ctx, "CREATE TABLE inv (id INT, vendor VARCHAR(50), amount DECIMAL(10,2), note VARCHAR(50))", null, "db", 0);
        Path f = tmp.resolve("inv.csv");
        Files.writeString(f, "﻿Export of 2026-10-25\r\nid;vendor;amount;note\r\n1;Acme;10.50;\r\n2;\"Bolt; Nuts\";20;\"two\r\nlines\"\r\n\r\n3;Cogs;30.25;x\r\n", StandardCharsets.UTF_8);
        Map<String, Object> r = db.bulkInsert(ctx, "inv", null, null, 2, "db", f.toString(), ";", 2, true, "utf-8");
        assertEquals(0, BigDecimal.valueOf(3).compareTo((BigDecimal) r.get("rows_inserted")));
        List<List<Object>> got = rows(db.query(ctx, "SELECT id, vendor, amount, note FROM inv ORDER BY id", null, 100, "db", 0));
        assertEquals(3, got.size());
        assertEquals("Bolt; Nuts", got.get(1).get(1), "quoted fields keep their delimiter");
        assertEquals("two\r\nlines", got.get(1).get(3), "and their line breaks");
        assertNull(got.get(0).get(3), "an empty field is inserted as NULL");
        assertEquals(0, new BigDecimal("30.25").compareTo((BigDecimal) got.get(2).get(2)));

        // no header: the columns name the database columns; a tab delimiter
        Path g = tmp.resolve("inv.tsv");
        Files.writeString(g, "4\tDyna\n5\tEcho\n", StandardCharsets.UTF_8);
        db.bulkInsert(ctx, "inv", null, List.of("id", "vendor"), 500, "db", g.toString(), "tab", 1, false, "utf-8");
        assertEquals(5, rows(db.query(ctx, "SELECT id FROM inv", null, 100, "db", 0)).size());

        // a Data Table still works through the old signature
        Map<String, Object> t = Values.table(new ArrayList<>(List.of("id", "vendor")), new ArrayList<>(List.of(new ArrayList<Object>(List.of(new BigDecimal("6"), "Flux")))));
        db.bulkInsert(ctx, "inv", t, null, 500, "db");
        assertEquals(6, rows(db.query(ctx, "SELECT id FROM inv", null, 100, "db", 0)).size());
    }

    @Test void bulkInsertFromFileRejectsBadInputAndRollsBack() throws Exception {
        ActionContext ctx = connect("jdbc:h2:mem:bulkbad;DB_CLOSE_DELAY=-1", null);
        db.execute(ctx, "CREATE TABLE t (a INT, b VARCHAR(10))", null, "db", 0);
        Path f = tmp.resolve("t.csv");
        Files.writeString(f, "a,b\n1,x\n2,y,extra\n", StandardCharsets.UTF_8);
        assertEquals("DB.COLUMN_MISMATCH", code(() -> db.bulkInsert(ctx, "t", null, null, 500, "db", f.toString(), ",", 1, true, "utf-8")));
        assertEquals(0, rows(db.query(ctx, "SELECT * FROM t", null, 100, "db", 0)).size(), "nothing is kept when a line is wrong");
        Map<String, Object> table = Values.table(new ArrayList<>(List.of("a")), new ArrayList<>());
        assertEquals("DB.BAD_INPUT", code(() -> db.bulkInsert(ctx, "t", table, null, 500, "db", f.toString(), ",", 1, true, "utf-8")));
        assertEquals("DB.BAD_INPUT", code(() -> db.bulkInsert(ctx, "t", null, null, 500, "db", null, ",", 1, true, "utf-8")));
        assertEquals("DB.BAD_DELIMITER", code(() -> db.bulkInsert(ctx, "t", null, null, 500, "db", f.toString(), ";;", 1, true, "utf-8")));
        assertEquals("FILE.NOT_FOUND", code(() -> db.bulkInsert(ctx, "t", null, null, 500, "db", tmp.resolve("none.csv").toString(), ",", 1, true, "utf-8")));
        assertEquals("DB.NO_COLUMNS", code(() -> db.bulkInsert(ctx, "t", null, null, 500, "db", f.toString(), ",", 1, false, "utf-8")));
        assertEquals("DB.BAD_NAME", code(() -> db.bulkInsert(ctx, "t", null, List.of("a; DROP TABLE t"), 500, "db", f.toString(), ",", 1, true, "utf-8")));
    }

    @Test void exportWritesAProcedureResultAndStopsAtMaxRows() throws Exception {
        ActionContext ctx = connect("jdbc:hsqldb:mem:export_proc", "SA");
        db.execute(ctx, "CREATE TABLE sales (id INT, region VARCHAR(10), amount DECIMAL(10,2))", null, "db", 0);
        for (int i = 1; i <= 5; i++) db.execute(ctx, "INSERT INTO sales VALUES (?, ?, ?)", List.of(i, i % 2 == 0 ? "north" : "south", new BigDecimal(i * 10)), "db", 0);
        db.execute(ctx, "CREATE PROCEDURE region_sales(IN r VARCHAR(10)) READS SQL DATA DYNAMIC RESULT SETS 1 BEGIN ATOMIC "
                + "DECLARE result_rows CURSOR WITH RETURN FOR SELECT id, amount FROM sales WHERE region = r ORDER BY id; OPEN result_rows; END", null, "db", 0);
        Path out = tmp.resolve("south.csv");
        Map<String, Object> r = db.exportToFile(ctx, null, out.toString(), List.of("south"), ",", true, "utf-8", "db", 0, true, "region_sales", 0);
        assertEquals(0, BigDecimal.valueOf(3).compareTo((BigDecimal) r.get("row_count")));
        List<String> lines = Files.readAllLines(out);
        assertEquals("ID,AMOUNT", lines.get(0));
        assertEquals(4, lines.size());
        assertTrue(lines.get(1).startsWith("1,"), lines.get(1));

        Map<String, Object> limited = db.exportToFile(ctx, "SELECT id FROM sales ORDER BY id", tmp.resolve("two.csv").toString(), null, ",", false, "utf-8", "db", 0, true, null, 2);
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) limited.get("row_count")));
        assertEquals(List.of("1", "2"), Files.readAllLines(tmp.resolve("two.csv")));
        // the old signature exports everything
        db.exportToFile(ctx, "SELECT id FROM sales", tmp.resolve("all.csv").toString(), null, ",", false, "utf-8", "db", 0, true);
        assertEquals(5, Files.readAllLines(tmp.resolve("all.csv")).size());

        assertEquals("DB.BAD_INPUT", code(() -> db.exportToFile(ctx, "SELECT 1", out.toString(), null, ",", true, "utf-8", "db", 0, true, "region_sales", 0)));
        assertEquals("DB.BAD_INPUT", code(() -> db.exportToFile(ctx, null, out.toString(), null, ",", true, "utf-8", "db", 0, true, null, 0)));
        assertEquals("DB.BAD_INPUT", code(() -> db.exportToFile(ctx, "SELECT 1", out.toString(), null, ",", true, "utf-8", "db", 0, true, null, -1)));
        assertEquals("DB.BAD_NAME", code(() -> db.exportToFile(ctx, null, out.toString(), null, ",", true, "utf-8", "db", 0, true, "x; DROP TABLE sales", 0)));
        db.disconnect(ctx, "db");
    }
}
