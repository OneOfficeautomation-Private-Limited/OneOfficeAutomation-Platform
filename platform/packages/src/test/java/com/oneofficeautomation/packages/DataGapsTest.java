package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** New data-toolkit actions (string, boolean, number, list, dictionary, table, date, JSON, XML), called directly. */
class DataGapsTest {
    @TempDir Path tmp;

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    static BigDecimal n(Object o) { return (BigDecimal) o; }
    static void num(long expected, Object actual) { assertEquals(0, BigDecimal.valueOf(expected).compareTo(n(actual)), "got " + actual); }
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(OoaException.class, e).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> table(String json) { return (Map<String, Object>) JsonLite.parse(json); }

    @Test
    void packagesStillLoadWithNewActions() {
        for (Class<?> c : List.of(StringPackage.class, BooleanPackage.class, NumberPackage.class, ListPackage.class, DictionaryPackage.class,
                DataTablePackage.class, DateTimePackage.class, JsonPackage.class, XmlPackage.class)) {
            PackageIntrospector.PackageInfo info = PackageIntrospector.inspect(c);
            assertFalse(info.actions().isEmpty(), c.getSimpleName());
        }
        assertTrue(PackageIntrospector.inspect(DataTablePackage.class).actions().containsKey("change_column_type"));
        assertTrue(PackageIntrospector.inspect(DateTimePackage.class).conditions().containsKey("is_after"));
        assertTrue(PackageIntrospector.inspect(DateTimePackage.class).actions().containsKey("is_after"));
        assertNotNull(FirstPartyPackages.registry());
    }

    // ---- String ----

    @Test
    void stringCompare() {
        StringPackage s = new StringPackage();
        assertEquals(true, s.compare(ctx(), "Abc", "aBC", "ignore_case").get("equal"));
        num(-1, s.compare(ctx(), "Abc", "abc", "exact").get("result"));
        num(-1, s.compare(ctx(), "file2", "file10", "natural").get("result"));
        num(1, s.compare(ctx(), "file2", "file10", "exact").get("result"));
        assertEquals("STRING.BAD_MODE", code(() -> s.compare(ctx(), "a", "b", "fuzzy")));
    }

    @Test
    void stringFindLengthSubstring() {
        StringPackage s = new StringPackage();
        Map<String, Object> r = s.find(ctx(), "Invoice INV-204 and INV-305", "INV-\\d+", true, 10, false);
        num(20, r.get("index"));
        assertEquals("INV-305", r.get("match"));
        num(-1, s.find(ctx(), "abc", "z", false, 0, false).get("index"));
        num(0, s.find(ctx(), "Hello", "hel", false, 0, true).get("index"));
        assertEquals("STRING.BAD_REGEX", code(() -> s.find(ctx(), "abc", "(", true, 0, false)));
        assertEquals("STRING.BAD_POSITION", code(() -> s.find(ctx(), "abc", "a", false, 9, false)));
        num(5, s.length(ctx(), "hello").get("length"));
        assertEquals("ell", s.substring(ctx(), "hello", 1, 3, null).get("result"));
        assertEquals("el", s.substring(ctx(), "hello", 1, null, 3).get("result"));
        assertEquals("llo", s.substring(ctx(), "hello", 2, null, null).get("result"));
        assertEquals("lo", s.substring(ctx(), "hello", 3, 99, null).get("result"));
        assertEquals("STRING.BAD_POSITION", code(() -> s.substring(ctx(), "hello", 9, null, null)));
        assertEquals("STRING.BAD_LENGTH", code(() -> s.substring(ctx(), "hello", 0, -1, null)));
    }

    @Test
    void stringTrimReverseRandom() {
        StringPackage s = new StringPackage();
        assertEquals("a b", s.trim(ctx(), "  a b \t", "both", null).get("result"));
        assertEquals("a  ", s.trim(ctx(), "  a  ", "left", null).get("result"));
        assertEquals("00120", s.trim(ctx(), "**00120--", "both", "*-").get("result"));
        assertEquals("120", s.trim(ctx(), "00120", "left", "0").get("result"));
        assertEquals("cba", s.reverse(ctx(), "abc").get("result"));
        String r = (String) s.randomString(ctx(), 20, false, true, false).get("result");
        assertEquals(20, r.length());
        assertTrue(r.matches("\\d{20}"));
        assertEquals("STRING.NO_CHARACTER_SET", code(() -> s.randomString(ctx(), 5, false, false, false)));
        assertEquals("STRING.BAD_LENGTH", code(() -> s.randomString(ctx(), -1, true, true, false)));
    }

    @Test
    void stringNumbersAndLocales() {
        StringPackage s = new StringPackage();
        assertEquals(0, new BigDecimal("1234.56").compareTo(n(s.toNumber(ctx(), "1,234.56", "en-US", null, null).get("result"))));
        assertEquals(0, new BigDecimal("1234.56").compareTo(n(s.toNumber(ctx(), "1.234,56", "de-DE", null, null).get("result"))));
        assertEquals(0, new BigDecimal("-1234.5").compareTo(n(s.toNumber(ctx(), "-1'234.5", null, ".", "'").get("result"))));
        assertEquals(0, new BigDecimal("1234.5").compareTo(n(s.toNumber(ctx(), "1 234,5", "fr-FR", null, null).get("result"))));
        assertEquals("STRING.NOT_A_NUMBER", code(() -> s.toNumber(ctx(), "12abc", "en-US", null, null)));
        assertEquals("STRING.BAD_SEPARATORS", code(() -> s.toNumber(ctx(), "1", "en-US", ",", ",")));
        assertEquals("1.234,5", s.toLocaleNumber(ctx(), new BigDecimal("1234.5"), "de-DE", null, true).get("result"));
        assertEquals("1,234.50", s.toLocaleNumber(ctx(), new BigDecimal("1234.5"), "en-US", 2, true).get("result"));
        assertEquals("1234", s.toLocaleNumber(ctx(), new BigDecimal("1234"), "en-US", null, false).get("result"));
        assertEquals("STRING.BAD_DECIMALS", code(() -> s.toLocaleNumber(ctx(), BigDecimal.ONE, "en-US", -2, true)));
    }

    // ---- Boolean, Number ----

    @Test
    void booleanAndNumber() {
        BooleanPackage b = new BooleanPackage();
        assertEquals(true, b.compare(ctx(), true, true, "equal").get("result"));
        assertEquals(true, b.compare(ctx(), true, false, "not_equal").get("result"));
        assertEquals("BOOLEAN.BAD_OPERATOR", code(() -> b.compare(ctx(), true, false, "gt")));
        num(1, b.toNumber(ctx(), true).get("result"));
        num(0, b.toNumber(ctx(), false).get("result"));

        NumberPackage p = new NumberPackage();
        num(11, p.increment(ctx(), BigDecimal.TEN, null).get("result"));
        assertEquals(0, new BigDecimal("12.5").compareTo(n(p.increment(ctx(), BigDecimal.TEN, new BigDecimal("2.5")).get("result"))));
        num(7, p.decrement(ctx(), BigDecimal.TEN, new BigDecimal("3")).get("result"));
        assertEquals("00042", p.toText(ctx(), new BigDecimal("42"), null, "00000").get("text"));
        assertEquals("1,234.57", p.toText(ctx(), new BigDecimal("1234.567"), null, "#,##0.00").get("text"));
        assertEquals("3.10", p.toText(ctx(), new BigDecimal("3.1"), 2, null).get("text"));
        assertEquals("7", p.toText(ctx(), new BigDecimal("7.000"), null, null).get("text"));
        assertEquals("NUMBER.BAD_PATTERN", code(() -> p.toText(ctx(), BigDecimal.ONE, null, "#.#.#")));
        assertEquals("NUMBER.BAD_DECIMALS", code(() -> p.toText(ctx(), BigDecimal.ONE, -1, null)));
    }

    // ---- List, Dictionary ----

    @Test
    void listAndDictionary() {
        ListPackage l = new ListPackage();
        List<Object> src = new ArrayList<>(List.of("a", "b", "c"));
        assertEquals(List.of(), l.clear(ctx(), src).get("list"));
        assertEquals(List.of("a", "X", "c"), l.setItem(ctx(), src, 1, "X").get("list"));
        assertEquals(List.of("a", "b", "c"), src, "source list must not change");
        assertEquals("LIST.INDEX", code(() -> l.setItem(ctx(), src, 3, "X")));
        assertEquals(List.of("a", "b", "c", "d", "e"), l.appendList(ctx(), src, List.of("d", "e")).get("list"));
        assertTrue(l.contains(ctx(), src, "b"));

        DictionaryPackage d = new DictionaryPackage();
        Map<String, Object> a = new LinkedHashMap<>(Map.of("x", 1));
        a.put("y", 2);
        num(2, d.size(ctx(), a).get("count"));
        assertEquals(Map.of("x", 1, "y", 20, "z", 30), d.merge(ctx(), a, Map.of("y", 20, "z", 30), true).get("dictionary"));
        assertEquals(Map.of("x", 1, "y", 2, "z", 30), d.merge(ctx(), a, Map.of("y", 20, "z", 30), false).get("dictionary"));
        assertTrue(d.containsKey(ctx(), a, "x"));
    }

    // ---- Data table ----

    static final String PEOPLE = "{\"columns\":[\"id\",\"name\",\"amount\"],\"rows\":[[1,\"Ann\",\"10.5\"],[2,\"Bob\",\"7\"],[3,\"Cy\",\"\"]]}";

    @Test
    @SuppressWarnings("unchecked")
    void tableRowsAndColumns() {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> p = table(PEOPLE);

        Map<String, Object> ins = (Map<String, Object>) t.insertRow(ctx(), p, 1, Map.of("id", 9, "name", "Zed")).get("table");
        assertEquals(Arrays.asList(9, "Zed", null), DataTablePackage.rows(ins).get(1));
        assertEquals(3, DataTablePackage.rows(p).size(), "source table must not change");
        assertEquals("TABLE.NO_ROW", code(() -> t.insertRow(ctx(), p, 7, Map.of())));
        assertEquals("TABLE.NO_COLUMN", code(() -> t.insertRow(ctx(), p, 0, Map.of("nope", 1))));

        Map<String, Object> del = t.deleteRow(ctx(), p, 0, null, null);
        num(1, del.get("count"));
        Map<String, Object> delBy = t.deleteRow(ctx(), p, null, "name", "Bob");
        num(1, delBy.get("count"));
        assertEquals(2, DataTablePackage.rows((Map<String, Object>) delBy.get("table")).size());
        num(1, t.deleteRow(ctx(), p, null, "id", new BigDecimal("2.0")).get("count"));
        assertEquals("TABLE.NOTHING_TO_DELETE", code(() -> t.deleteRow(ctx(), p, null, null, null)));

        Map<String, Object> ic = (Map<String, Object>) t.insertColumn(ctx(), p, "status", "new", 1).get("table");
        assertEquals(List.of("id", "status", "name", "amount"), DataTablePackage.cols(ic));
        assertEquals("new", DataTablePackage.rows(ic).get(2).get(1));
        assertEquals("TABLE.COLUMN_EXISTS", code(() -> t.insertColumn(ctx(), p, "name", null, null)));
        assertEquals("TABLE.BAD_POSITION", code(() -> t.insertColumn(ctx(), p, "q", null, 9)));

        Map<String, Object> dc = (Map<String, Object>) t.deleteColumn(ctx(), p, "name").get("table");
        assertEquals(List.of("id", "amount"), DataTablePackage.cols(dc));
        assertEquals(Arrays.asList(new BigDecimal("2"), "7"), DataTablePackage.rows(dc).get(1));
        num(3, t.columnCount(ctx(), p).get("count"));
        assertEquals(List.of("id", "name", "amount"), t.columnNames(ctx(), p).get("names"));

        Map<String, Object> cl = (Map<String, Object>) t.clear(ctx(), p).get("table");
        assertEquals(List.of("id", "name", "amount"), DataTablePackage.cols(cl));
        assertTrue(DataTablePackage.rows(cl).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tableChangeColumnType() {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> p = table(PEOPLE);
        Map<String, Object> asNum = (Map<String, Object>) t.changeColumnType(ctx(), p, "amount", "Number").get("table");
        assertEquals(0, new BigDecimal("10.5").compareTo(n(DataTablePackage.rows(asNum).get(0).get(2))));
        assertNull(DataTablePackage.rows(asNum).get(2).get(2), "blank cells become empty");
        Map<String, Object> asText = (Map<String, Object>) t.changeColumnType(ctx(), p, "id", "String").get("table");
        assertEquals("1", DataTablePackage.rows(asText).get(0).get(0));

        Map<String, Object> dates = table("{\"columns\":[\"d\",\"ok\"],\"rows\":[[\"2026-10-18\",\"yes\"],[\"oops\",\"maybe\"]]}");
        assertEquals("TABLE.CONVERT_FAILED", code(() -> t.changeColumnType(ctx(), dates, "d", "DateTime")));
        assertEquals("TABLE.CONVERT_FAILED", code(() -> t.changeColumnType(ctx(), dates, "ok", "Boolean")));
        assertEquals("TABLE.CONVERT_FAILED", code(() -> t.changeColumnType(ctx(), p, "name", "Number")));
        assertEquals("TABLE.BAD_TYPE", code(() -> t.changeColumnType(ctx(), p, "name", "Money")));
        Map<String, Object> one = table("{\"columns\":[\"d\"],\"rows\":[[\"2026-10-18\"]]}");
        Map<String, Object> conv = (Map<String, Object>) t.changeColumnType(ctx(), one, "d", "DateTime").get("table");
        assertEquals(LocalDateTime.of(2026, 10, 18, 0, 0), DataTablePackage.rows(conv).get(0).get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tableJoinMergeFind() {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> people = table(PEOPLE);
        Map<String, Object> orders = table("{\"columns\":[\"id\",\"item\",\"name\"],\"rows\":[[1,\"pen\",\"x\"],[1,\"ink\",\"y\"],[3.0,\"cup\",\"z\"],[8,\"hat\",\"w\"]]}");

        Map<String, Object> inner = t.join(ctx(), people, orders, List.of("id"), "inner");
        Map<String, Object> it = (Map<String, Object>) inner.get("table");
        assertEquals(List.of("id", "name", "amount", "item", "name_2"), DataTablePackage.cols(it));
        num(3, inner.get("count"));
        assertEquals("ink", DataTablePackage.rows(it).get(1).get(3));
        assertEquals("cup", DataTablePackage.rows(it).get(2).get(3), "3 and 3.0 match");

        Map<String, Object> left = (Map<String, Object>) t.join(ctx(), people, orders, List.of("id"), "left").get("table");
        assertEquals(4, DataTablePackage.rows(left).size());
        assertEquals(Arrays.asList(new BigDecimal("2"), "Bob", "7", null, null), DataTablePackage.rows(left).get(2));
        assertEquals("TABLE.NO_COLUMN", code(() -> t.join(ctx(), people, orders, List.of("amount"), "inner")));
        assertEquals("TABLE.BAD_JOIN_TYPE", code(() -> t.join(ctx(), people, orders, List.of("id"), "outer")));
        assertEquals("TABLE.NO_KEYS", code(() -> t.join(ctx(), people, orders, List.of(), "inner")));

        Map<String, Object> extra = table("{\"columns\":[\"name\",\"city\"],\"rows\":[[\"Dee\",\"Pune\"]]}");
        Map<String, Object> merged = (Map<String, Object>) t.merge(ctx(), List.of(people, extra), "union").get("table");
        assertEquals(List.of("id", "name", "amount", "city"), DataTablePackage.cols(merged));
        assertEquals(Arrays.asList(null, "Dee", null, "Pune"), DataTablePackage.rows(merged).get(3));
        assertEquals("TABLE.COLUMNS_DIFFER", code(() -> t.merge(ctx(), List.of(people, extra), "same")));
        assertEquals(6, DataTablePackage.rows((Map<String, Object>) t.merge(ctx(), List.of(people, people), "same").get("table")).size());
        assertEquals("TABLE.NOT_A_TABLE", code(() -> t.merge(ctx(), List.of(people, "nope"), "union")));

        Map<String, Object> f = t.find(ctx(), people, "bob", null, "contains");
        assertEquals(List.of(BigDecimal.ONE), f.get("rows"));
        assertEquals(true, f.get("found"));
        Map<String, Object> f2 = t.find(ctx(), people, new BigDecimal("3"), "id", "equals");
        assertEquals(List.of(BigDecimal.valueOf(2)), f2.get("rows"));
        assertEquals(false, t.find(ctx(), people, "Nobody", "name", "equals").get("found"));
    }

    @Test
    void tableWriteToFile() throws Exception {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> p = table("{\"columns\":[\"id\",\"note\"],\"rows\":[[1,\"a,b\"],[2,null]]}");
        t.writeToFile(ctx(), p, "out/t.csv", "csv", ",", true);
        assertEquals("id,note\r\n1,\"a,b\"\r\n2,\r\n", Files.readString(tmp.resolve("out/t.csv")));
        t.writeToFile(ctx(), p, "t.json", "json", null, true);
        assertEquals("[{\"id\":1,\"note\":\"a,b\"},{\"id\":2,\"note\":null}]", Files.readString(tmp.resolve("t.json")));
        assertEquals("TABLE.BAD_FORMAT", code(() -> t.writeToFile(ctx(), p, "t.xlsx", "xlsx", null, true)));
        assertEquals("TABLE.BAD_DELIMITER", code(() -> t.writeToFile(ctx(), p, "t.csv", "csv", "::", true)));
    }

    // ---- Date and time ----

    @Test
    void dateTime() {
        DateTimePackage d = new DateTimePackage();
        LocalDateTime a = LocalDateTime.of(2026, 10, 18, 14, 30), b = LocalDateTime.of(2026, 10, 19, 9, 0);
        assertTrue(d.isBefore(ctx(), a, b));
        assertFalse(d.isAfter(ctx(), a, b));
        assertTrue(d.isEqual(ctx(), a, LocalDateTime.of(2026, 10, 18, 14, 30)));
        assertEquals(true, d.isAfterAction(ctx(), b, a).get("result"));
        assertEquals(true, d.isBeforeAction(ctx(), a, b).get("result"));
        assertEquals(false, d.isEqualAction(ctx(), a, b).get("result"));
        assertEquals("DATETIME.MISSING", code(() -> d.isAfter(ctx(), null, b)));

        assertEquals(true, d.isLeapYear(ctx(), LocalDateTime.of(2024, 3, 1, 0, 0), null).get("result"));
        assertEquals(false, d.isLeapYear(ctx(), null, 1900).get("result"));
        assertEquals(true, d.isLeapYear(ctx(), null, 2000).get("result"));
        assertEquals("DATETIME.MISSING", code(() -> d.isLeapYear(ctx(), null, null)));

        assertEquals(LocalDateTime.of(2026, 9, 18, 14, 30), d.subtract(ctx(), a, 1, "months").get("result"));
        assertEquals(LocalDateTime.of(2026, 10, 18, 12, 30), d.subtract(ctx(), a, 2, "hours").get("result"));
        assertEquals("DATETIME.BAD_UNIT", code(() -> d.subtract(ctx(), a, 2, "fortnights")));

        assertEquals(LocalDateTime.of(2026, 10, 18, 0, 0), d.startOf(ctx(), a, "day").get("result"));
        assertEquals(LocalDateTime.of(2026, 10, 12, 0, 0), d.startOf(ctx(), a, "week").get("result"));
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), d.startOf(ctx(), a, "month").get("result"));
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), d.startOf(ctx(), a, "year").get("result"));
        assertEquals(LocalDateTime.of(2026, 10, 18, 23, 59, 59), d.endOf(ctx(), a, "week").get("result"));
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59), d.endOf(ctx(), LocalDateTime.of(2024, 2, 3, 1, 0), "month").get("result"));
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59, 59), d.endOf(ctx(), a, "year").get("result"));
        assertEquals("DATETIME.BAD_UNIT", code(() -> d.startOf(ctx(), a, "decade")));
    }

    // ---- JSON ----

    static final String ORDER = "{\"order\":{\"id\":7,\"items\":[{\"sku\":\"A\",\"qty\":1},{\"sku\":\"B\",\"qty\":2}]}}";

    @Test
    @SuppressWarnings("unchecked")
    void jsonEditNodes() {
        JsonPackage j = new JsonPackage();
        Object doc = JsonLite.parse(ORDER);

        Object withNote = j.insertNode(ctx(), doc, "order.note", "rush").get("value");
        assertEquals("rush", j.getNode(ctx(), withNote, "order.note").get("result"));
        assertNull(j.getNode(ctx(), doc, "order.note").get("result"), "input document must not change");
        Object appended = j.insertNode(ctx(), ORDER, "/order/items/-", Map.of("sku", "C")).get("value");
        assertEquals("C", j.getNode(ctx(), appended, "order.items[2].sku").get("result"));
        Object first = j.insertNode(ctx(), doc, "$.order.items[0]", Map.of("sku", "Z")).get("value");
        assertEquals("Z", j.getNode(ctx(), first, "order.items[0].sku").get("result"));
        assertEquals("JSON.NODE_EXISTS", code(() -> j.insertNode(ctx(), doc, "order.id", 1)));
        assertEquals("JSON.PATH_NOT_FOUND", code(() -> j.insertNode(ctx(), doc, "order.missing.x", 1)));
        assertEquals("JSON.INVALID", code(() -> j.insertNode(ctx(), "{bad", "a", 1)));

        Map<String, Object> up = j.updateNode(ctx(), doc, "order.items[1].qty", new BigDecimal("5"));
        num(2, up.get("previous"));
        num(5, j.getNode(ctx(), up.get("value"), "order.items[1].qty").get("result"));
        assertEquals("JSON.PATH_NOT_FOUND", code(() -> j.updateNode(ctx(), doc, "order.nope", 1)));
        assertEquals("JSON.PATH_NOT_FOUND", code(() -> j.updateNode(ctx(), doc, "order.items[9]", 1)));

        Map<String, Object> del = j.deleteNode(ctx(), doc, "order.items[0]");
        assertEquals("A", ((Map<String, Object>) del.get("removed")).get("sku"));
        num(1, j.getNodeArray(ctx(), del.get("value"), "order.items").get("count"));
        assertEquals("JSON.BAD_PATH", code(() -> j.deleteNode(ctx(), doc, "")));
        assertEquals("JSON.NOT_AN_ARRAY", code(() -> j.getNodeArray(ctx(), doc, "order.id")));
        num(2, j.getNodeArray(ctx(), ORDER, "order.items").get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonTableAndValidate() {
        JsonPackage j = new JsonPackage();
        Map<String, Object> t = (Map<String, Object>) j.toTable(ctx(), ORDER, "order.items").get("table");
        assertEquals(List.of("sku", "qty"), DataTablePackage.cols(t));
        assertEquals(2, DataTablePackage.rows(t).size());
        assertEquals("JSON.NOT_OBJECTS", code(() -> j.toTable(ctx(), "[1,2]", null)));
        assertEquals("JSON.NOT_AN_ARRAY", code(() -> j.toTable(ctx(), ORDER, "order")));

        assertEquals(true, j.validate(ctx(), ORDER, List.of("order.id", "/order/items/0/sku")).get("valid"));
        Map<String, Object> bad = j.validate(ctx(), ORDER, List.of("order.customer"));
        assertEquals(false, bad.get("valid"));
        assertEquals(1, ((List<Object>) bad.get("errors")).size());
        Map<String, Object> broken = j.validate(ctx(), "{\"a\":", null);
        assertEquals(false, broken.get("valid"));
        assertTrue(Values.text(broken.get("errors")).contains("Not valid JSON"));
    }

    // ---- XML ----

    static final String INVOICE = "<invoice id=\"9\"><line><amount>10.5</amount></line><line><amount>4.5</amount></line></invoice>";

    @Test
    void xmlXpathFunctionAndSave() throws Exception {
        XmlPackage x = new XmlPackage();
        num(2, x.xpathFunction(ctx(), "count(//line)", INVOICE, null, "number").get("result"));
        num(15, x.xpathFunction(ctx(), "sum(//amount)", INVOICE, null, "number").get("result"));
        assertEquals("9", x.xpathFunction(ctx(), "string(/invoice/@id)", INVOICE, null, "string").get("result"));
        assertEquals(true, x.xpathFunction(ctx(), "count(//line) > 1", INVOICE, null, "boolean").get("result"));
        assertEquals("XML.BAD_XPATH", code(() -> x.xpathFunction(ctx(), "count(//line", INVOICE, null, "number")));
        assertEquals("XML.NOT_A_NUMBER", code(() -> x.xpathFunction(ctx(), "number(/invoice/@missing)", INVOICE, null, "number")));
        assertEquals("XML.BAD_RESULT_TYPE", code(() -> x.xpathFunction(ctx(), "1", INVOICE, null, "date")));

        x.saveFile(ctx(), INVOICE, "out/inv.xml", false);
        assertEquals(INVOICE, Files.readString(tmp.resolve("out/inv.xml")));
        num(2, x.xpathFunction(ctx(), "count(//line)", null, "out/inv.xml", "number").get("result"));
        x.saveFile(ctx(), INVOICE, "pretty.xml", true);
        assertTrue(Files.readString(tmp.resolve("pretty.xml")).contains("<amount>10.5</amount>"));
        assertEquals("XML.INVALID", code(() -> x.saveFile(ctx(), "<a><b></a>", "bad.xml", false)));
        assertFalse(Files.exists(tmp.resolve("bad.xml")));
    }
}
