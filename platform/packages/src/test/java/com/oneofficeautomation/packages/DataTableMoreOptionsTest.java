package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Data Table options added after the command comparison: row_count, insert_column, add_column_from_list, write_to_file. */
class DataTableMoreOptionsTest {
    @TempDir Path tmp;
    final DataTablePackage dt = new DataTablePackage();

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    static Map<String, Object> table(List<String> cols, List<?>... rows) {
        List<Object> rs = new ArrayList<>();
        for (List<?> r : rows) rs.add(new ArrayList<Object>(r));
        return Values.table(new ArrayList<>(cols), rs);
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> result) { return (List<List<Object>>) ((Map<String, Object>) result.get("table")).get("rows"); }

    @SuppressWarnings("unchecked")
    static List<Object> columns(Map<String, Object> result) { return (List<Object>) ((Map<String, Object>) result.get("table")).get("columns"); }

    static void num(long expected, Object actual) { assertEquals(0, BigDecimal.valueOf(expected).compareTo((BigDecimal) actual), "got " + actual); }

    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(OoaException.class, e).code(); }

    @Test void rowCountCanSkipEmptyRows() {
        Map<String, Object> t = table(List.of("a", "b"), List.of("1", "x"), Arrays.asList(null, ""), Arrays.asList("", "y"), Arrays.asList(null, null));
        num(4, dt.rowCount(ctx(), t, false).get("count"));
        num(2, dt.rowCount(ctx(), t, true).get("count"));
        num(4, dt.rowCount(ctx(), t).get("count"), "old signature counts every row");
    }

    static void num(long expected, Object actual, String msg) { assertEquals(0, BigDecimal.valueOf(expected).compareTo((BigDecimal) actual), msg); }

    @Test void insertColumnCopiesAColumnOfAnotherTableAndConvertsIt() {
        Map<String, Object> t = table(List.of("id", "name"), List.of("1", "Ann"), List.of("2", "Bo"), List.of("3", "Cy"));
        Map<String, Object> src = table(List.of("code", "amount"), List.of("A", "10.5"), List.of("B", "20"));
        Map<String, Object> r = dt.insertColumn(ctx(), t, "amount", "0", 1, src, "Amount", null, "Number");
        assertEquals(List.of("id", "amount", "name"), columns(r));
        assertEquals(0, new BigDecimal("10.5").compareTo((BigDecimal) rows(r).get(0).get(1)), "copied row by row and converted to Number");
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) rows(r).get(1).get(1)));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) rows(r).get(2).get(1)), "a row the source does not have gets the default");
        // by index, at the end, keeping the values as they are
        Map<String, Object> r2 = dt.insertColumn(ctx(), t, "code", null, null, src, null, 0, null);
        assertEquals(List.of("id", "name", "code"), columns(r2));
        assertEquals("B", rows(r2).get(1).get(2));
        assertNull(rows(r2).get(2).get(2));
        // a longer source adds rows
        Map<String, Object> small = table(List.of("id"), List.of("1"));
        assertEquals(2, rows(dt.insertColumn(ctx(), small, "code", null, null, src, "code", null, null)).size());
        // the old signature still works
        assertEquals("x", rows(dt.insertColumn(ctx(), t, "flag", "x", null)).get(0).get(2));
    }

    @Test void insertColumnRejectsBadInput() {
        Map<String, Object> t = table(List.of("id"), List.of("1"));
        Map<String, Object> src = table(List.of("code"), List.of("A"));
        assertEquals("TABLE.BAD_TYPE", code(() -> dt.insertColumn(ctx(), t, "n", null, null, null, null, null, "Money")));
        assertEquals("TABLE.NO_COLUMN", code(() -> dt.insertColumn(ctx(), t, "n", null, null, src, null, null, null)));
        assertEquals("TABLE.NO_COLUMN", code(() -> dt.insertColumn(ctx(), t, "n", null, null, src, "nope", null, null)));
        assertEquals("TABLE.CONVERT_FAILED", code(() -> dt.insertColumn(ctx(), t, "n", null, null, src, "code", null, "Number")));
    }

    @Test void addColumnFromListFillsRowsAtAPosition() {
        Map<String, Object> t = table(List.of("a", "b"), List.of("1", "2"), List.of("3", "4"));
        Map<String, Object> first = dt.addColumnFromList(ctx(), t, List.of("x", "y"), "c", 0);
        assertEquals(List.of("c", "a", "b"), columns(first));
        assertEquals(List.of("x", "1", "2"), rows(first).get(0));
        assertEquals(List.of("y", "3", "4"), rows(first).get(1));
        Map<String, Object> last = dt.addColumnFromList(ctx(), t, List.of("x"), "c", null);
        assertEquals(Arrays.asList("3", "4", null), rows(last).get(1), "rows beyond the list stay empty");
        Map<String, Object> longer = dt.addColumnFromList(ctx(), t, List.of("x", "y", "z"), "c", 1);
        assertEquals(Arrays.asList(null, "z", null), rows(longer).get(2), "a longer list adds rows");
        Map<String, Object> empty = table(List.of());
        assertEquals(List.of(List.of("p"), List.of("q")), rows(dt.addColumnFromList(ctx(), empty, List.of("p", "q"), "only", null)));
        assertEquals("TABLE.COLUMN_EXISTS", code(() -> dt.addColumnFromList(ctx(), t, List.of("x"), "a", null)));
        assertEquals("TABLE.BAD_POSITION", code(() -> dt.addColumnFromList(ctx(), t, List.of("x"), "c", 5)));
        assertEquals("TABLE.NO_LIST", code(() -> dt.addColumnFromList(ctx(), t, null, "c", null)));
    }

    @Test void writeToFileCanRefuseToCreateFolders() throws Exception {
        Map<String, Object> t = table(List.of("a"), List.of("1"));
        Path p = tmp.resolve("new/sub/out.csv");
        assertEquals("TABLE.NO_FOLDER", code(() -> dt.writeToFile(ctx(), t, p.toString(), "csv", ",", true, "utf-8", false, "lf", null, false)));
        assertFalse(Files.exists(p.getParent()), "no folder was created");
        dt.writeToFile(ctx(), t, p.toString(), "csv", ",", true, "utf-8", false, "lf", null, true);
        assertEquals("a\n1\n", Files.readString(p));
        Path q = tmp.resolve("there.csv");
        dt.writeToFile(ctx(), t, q.toString(), "csv", ",", true, "utf-8", false, "lf", null, false);
        assertEquals("a\n1\n", Files.readString(q), "an existing folder is fine with create_folders off");
        dt.writeToFile(ctx(), t, tmp.resolve("old/x.csv").toString(), "csv", ",", true, "utf-8", false, "lf");
        assertTrue(Files.exists(tmp.resolve("old/x.csv")), "the old signature still creates folders");
    }
}
