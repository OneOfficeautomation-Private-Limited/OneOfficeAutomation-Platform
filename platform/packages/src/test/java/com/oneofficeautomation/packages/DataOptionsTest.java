package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.DataGapsTest.code;
import static com.oneofficeautomation.packages.DataGapsTest.num;
import static com.oneofficeautomation.packages.DataGapsTest.table;
import static org.junit.jupiter.api.Assertions.*;

/** Options and actions added to the data toolkits after the property-by-property comparison with other RPA tools. */
class DataOptionsTest {
    @TempDir Path tmp;

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    // ---- String ----

    @Test void extractTextOccurrencesAndFallbacks() {
        StringPackage s = new StringPackage();
        String t = "a=1; b=2; c=3;";
        assertEquals("2", s.extractText(ctx(), t, ";", "=", true, 2, 1, false, "empty", null, false).get("result"));
        assertEquals("1; b=2", s.extractText(ctx(), t, ";", "=", true, 1, 2, false, "empty", null, false).get("result"));
        assertEquals("2", s.extractText(ctx(), "X B=2;", ";", "b=", true, 1, 1, true, "empty", null, false).get("result"));
        assertEquals("", s.extractText(ctx(), t, null, "z=", true, 1, 1, false, "empty", null, false).get("result"));
        assertEquals(t, s.extractText(ctx(), t, null, "z=", false, 1, 1, false, "source", null, false).get("result"));
        assertEquals("ab", s.extractText(ctx(), "[a\r\nbcd]", "]", "[", true, 1, 1, false, "empty", 2, true).get("result"));
        assertEquals("STRING.BAD_OCCURRENCE", code(() -> s.extractText(ctx(), t, null, "=", true, 0, 1, false, "empty", null, false)));
    }

    @Test void replaceCaseStartAndCount() {
        StringPackage s = new StringPackage();
        assertEquals("x-x-a", s.replace(ctx(), "a-A-a", "a", "x", false, true, 0, 2).get("result"));
        assertEquals("a-x-x", s.replace(ctx(), "a-a-a", "a", "x", false, false, 1, -1).get("result"));
        assertEquals("$1 and 2", s.replace(ctx(), "1 and 2", "1", "$1", false, true, 0, -1).get("result"), "literal replacement keeps $");
        assertEquals("<1> and 2", s.replace(ctx(), "1 and 2", "(\\d)", "<$1>", true, false, 0, 1).get("result"));
        assertEquals("STRING.BAD_POSITION", code(() -> s.replace(ctx(), "abc", "a", "b", false, false, 9, -1)));
    }

    @Test void splitPartsCaseRegexAndEmpty() {
        StringPackage s = new StringPackage();
        assertEquals(List.of("a", "b", "c,d"), s.split(ctx(), "a,b,c,d", ",", true, 3, false, false, false).get("items"));
        assertEquals(List.of("1", "2", "3"), s.split(ctx(), "1x2X3", "x", true, 0, true, false, false).get("items"));
        assertEquals(List.of("l1", "l2", "l3"), s.split(ctx(), "l1\r\nl2\nl3", "\\R", true, 0, false, true, false).get("items"));
        assertEquals(List.of("a", "b"), s.split(ctx(), "a,,b,", ",", true, 0, false, false, true).get("items"));
    }

    @Test void findOccurrenceAndOneBasedPosition() {
        StringPackage s = new StringPackage();
        Map<String, Object> r = s.find(ctx(), "ab ab ab", "ab", false, 0, false, 3);
        num(6, r.get("index"));
        num(7, r.get("position"));
        num(0, s.find(ctx(), "ab", "zz", false, 0, false, 1).get("position"));
        assertEquals(false, s.find(ctx(), "ab ab", "ab", false, 0, false, 3).get("found"));
    }

    @Test void substringOutOfRangeAndRandomOptions() {
        StringPackage s = new StringPackage();
        assertEquals("", s.substring(ctx(), "abc", 10, null, null, "empty").get("result"));
        assertEquals("STRING.BAD_POSITION", code(() -> s.substring(ctx(), "abc", 10, null, null, "error")));
        String up = (String) s.randomString(ctx(), 200, true, false, false, "upper", "AEIOU").get("result");
        assertTrue(up.matches("[B-DF-HJ-NP-TV-Z]{200}"), up);
        String low = (String) s.randomString(ctx(), 50, true, true, false, "lower", null).get("result");
        assertTrue(low.matches("[a-z0-9]{50}"), low);
    }

    // ---- Number and Boolean ----

    @Test void randomDecimalsAndTruncatingText() {
        NumberPackage n = new NumberPackage();
        for (int i = 0; i < 50; i++) {
            BigDecimal r = (BigDecimal) n.random(ctx(), new BigDecimal("1.5"), new BigDecimal("2.5"), 2).get("result");
            assertTrue(r.compareTo(new BigDecimal("1.5")) >= 0 && r.compareTo(new BigDecimal("2.5")) <= 0 && r.scale() == 2, r.toPlainString());
        }
        BigDecimal whole = (BigDecimal) n.random(ctx(), BigDecimal.ONE, BigDecimal.valueOf(6), 0).get("result");
        assertTrue(whole.scale() == 0 && whole.intValue() >= 1 && whole.intValue() <= 6);
        assertEquals("NUMBER.BAD_RANGE", code(() -> n.random(ctx(), BigDecimal.TEN, BigDecimal.ONE, 0)));
        assertEquals("123.45", n.toText(ctx(), new BigDecimal("123.4567"), 2, null, "truncate").get("text"));
        assertEquals("123.46", n.toText(ctx(), new BigDecimal("123.4567"), 2, null, "half_up").get("text"));
    }

    @Test void booleanOrderAndLenientText() {
        BooleanPackage b = new BooleanPackage();
        num(1, b.compare(ctx(), true, false, "equal").get("order"));
        num(-1, b.compare(ctx(), false, true, "equal").get("order"));
        num(0, b.compare(ctx(), true, true, "equal").get("order"));
        assertEquals(false, b.fromText(ctx(), "maybe", false).get("result"));
        assertEquals("BOOLEAN.INVALID", code(() -> b.fromText(ctx(), "maybe", true)));
    }

    // ---- List and Dictionary ----

    @Test @SuppressWarnings("unchecked") void listOptionsAndNewActions() {
        ListPackage l = new ListPackage();
        List<Object> base = List.of("a", "b", "a", "c");
        assertEquals(List.of("a", "b", "a", "c", "x", "y"), l.addItem(ctx(), base, null, null, List.of("x", "y"), false).get("list"));
        assertEquals(List.of("a", "b", "a", "c", "d"), l.addItem(ctx(), base, null, null, List.of("a", "d"), true).get("list"));
        assertEquals("c", l.getItem(ctx(), base, -1, "error", null).get("item"));
        assertEquals("none", l.getItem(ctx(), base, 9, "default", "none").get("item"));
        Map<String, Object> r = l.removeItem(ctx(), base, null, "a", false);
        assertEquals(List.of("b", "a", "c"), r.get("list"));
        num(1, r.get("count"));
        assertEquals("b", l.removeItem(ctx(), base, 1, null, true).get("removed"));
        num(2, l.indexOf(ctx(), base, "A", true, true).get("index"));
        num(-1, l.indexOf(ctx(), base, "z", false, false).get("index"));
        assertEquals(List.of("c", "a", "b", "a"), l.reverse(ctx(), base).get("list"));
        assertEquals("a\nc", l.join(ctx(), List.of("a", "", "c"), "\\n", true, true).get("text"));
    }

    @Test void dictionaryOptionsAndConditions() {
        DictionaryPackage d = new DictionaryPackage();
        Map<String, Object> m = new LinkedHashMap<>(Map.of("Name", "Ann"));
        Map<String, Object> put = d.put(ctx(), m, "Name", "Bob", false);
        assertEquals("Ann", put.get("previous"));
        assertEquals(true, put.get("existed"));
        assertEquals("Ann", ((Map<?, ?>) d.put(ctx(), m, "Name", "Bob", true).get("dictionary")).get("Name"));
        assertEquals("Ann", d.get(ctx(), m, "name", null, true, false).get("value"));
        assertEquals("DICT.KEY_NOT_FOUND", code(() -> d.get(ctx(), m, "age", null, false, true)));
        Map<String, Object> rem = d.remove(ctx(), m, "Name", false);
        assertEquals("Ann", rem.get("removed"));
        assertEquals(true, rem.get("found"));
        assertEquals("DICT.KEY_NOT_FOUND", code(() -> d.remove(ctx(), m, "x", true)));
        assertTrue(d.containsValue(ctx(), m, "Ann"));
        assertTrue(d.keyHasValue(ctx(), m, "Name", "Ann"));
        assertFalse(d.keyHasValue(ctx(), m, "Name", "Bob"));
        assertTrue(d.isEmpty(ctx(), Map.of()));
    }

    // ---- Data Table ----

    static final String T = "{\"columns\":[\"id\",\"name\",\"city\"],\"rows\":[[1,\"Ann\",\"Pune\"],[2,\"bob\",\"\"],[3,\"Cid\",\"Pune\"]]}";

    @Test @SuppressWarnings("unchecked") void tableColumnIndexesAndRename() {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> tb = table(T);
        assertEquals("bob", t.getValue(ctx(), tb, 1, null, 1).get("value"));
        assertEquals("X", t.getValue(ctx(), (Map<String, Object>) t.setValue(ctx(), tb, 0, null, "X", 2).get("table"), 0, "city", null).get("value"));
        assertEquals(List.of("id", "city"), ((Map<String, Object>) t.deleteColumn(ctx(), tb, null, 1).get("table")).get("columns"));
        assertEquals("city", t.columnName(ctx(), tb, 2).get("name"));
        assertEquals("TABLE.NO_COLUMN", code(() -> t.columnName(ctx(), tb, 5)));
        assertEquals(List.of("id", "person", "city"), ((Map<String, Object>) t.renameColumn(ctx(), tb, "name", "person", null).get("table")).get("columns"));
        assertEquals("TABLE.COLUMN_EXISTS", code(() -> t.renameColumn(ctx(), tb, "name", "city", null)));
        num(3, t.columnCount(ctx(), table("{\"columns\":[\"a\",\"b\",\"c\"],\"rows\":[[1,null,\"\"]]}"), false).get("count"));
        num(1, t.columnCount(ctx(), table("{\"columns\":[\"a\",\"b\",\"c\"],\"rows\":[[1,null,\"\"]]}"), true).get("count"));
        Map<String, Object> ins = (Map<String, Object>) t.insertRow(ctx(), tb, null, Map.of("id", 4)).get("table");
        assertEquals(4, ((List<?>) ins.get("rows")).size());
    }

    @Test @SuppressWarnings("unchecked") void tableSortFilterFindOptions() {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> tb = table(T);
        Map<String, Object> sorted = (Map<String, Object>) t.sort(ctx(), tb, null, true, 2, true, List.of("name")).get("table");
        assertEquals("Cid", ((List<Object>) ((List<Object>) sorted.get("rows")).get(0)).get(1));
        Map<String, Object> caseSorted = (Map<String, Object>) t.sort(ctx(), tb, "name", false, null, false, null).get("table");
        assertEquals("bob", ((List<Object>) ((List<Object>) caseSorted.get("rows")).get(2)).get(1), "upper case sorts first when case matters");
        num(1, t.filter(ctx(), tb, "name", "ends_with", "B", false).get("count"));
        num(0, t.filter(ctx(), tb, "name", "ends_with", "B", true).get("count"));
        num(1, t.filter(ctx(), tb, "name", "matches", "^[AB]", true).get("count"));
        num(1, t.filter(ctx(), tb, "city", "not_contains", "pu", false).get("count"));
        Map<String, Object> f = t.find(ctx(), tb, "pune", null, "equals", false);
        num(2, f.get("count"));
        num(0, f.get("first_row"));
        Map<String, Object> cell = (Map<String, Object>) ((List<Object>) f.get("cells")).get(0);
        assertEquals("city", cell.get("column_name"));
        num(2, cell.get("column"));
        num(-1, t.find(ctx(), tb, "pune", null, "equals", true).get("first_row"));
    }

    @Test @SuppressWarnings("unchecked") void tableJoinTypesAndDifferentKeyNames() {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> a = table("{\"columns\":[\"id\",\"x\"],\"rows\":[[1,\"a\"],[2,\"b\"]]}");
        Map<String, Object> b = table("{\"columns\":[\"ref\",\"y\"],\"rows\":[[2,\"B\"],[3,\"C\"]]}");
        Map<String, Object> right = t.join(ctx(), a, b, List.of("id"), "right", List.of("ref"));
        num(2, right.get("count"));
        List<Object> rows = (List<Object>) ((Map<String, Object>) right.get("table")).get("rows");
        assertEquals(Arrays.asList(BigDecimal.valueOf(3), null, "C"), ((List<Object>) rows.get(1)).stream().map(v -> v instanceof BigDecimal d ? BigDecimal.valueOf(d.intValue()) : v).toList());
        num(3, t.join(ctx(), a, b, List.of("id"), "full", List.of("ref")).get("count"));
        num(1, t.join(ctx(), a, b, List.of("id"), "inner", List.of("ref")).get("count"));
    }

    @Test @SuppressWarnings("unchecked") void tableDateFormatCountConditionsAndFileOptions() throws Exception {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> d = table("{\"columns\":[\"when\"],\"rows\":[[\"18/10/2026\"]]}");
        Map<String, Object> conv = (Map<String, Object>) t.changeColumnType(ctx(), d, null, "DateTime", 0, "dd/MM/yyyy").get("table");
        assertEquals(LocalDateTime.of(2026, 10, 18, 0, 0), ((List<Object>) ((List<Object>) conv.get("rows")).get(0)).get(0));
        assertTrue(t.rowCountIs(ctx(), table(T), "gt", 2));
        assertFalse(t.rowCountIs(ctx(), table(T), "eq", 2));
        assertTrue(t.columnCountIs(ctx(), table(T), "eq", 3));
        t.writeToFile(ctx(), table(T), "out.csv", "csv", ",", true, "utf-8", false, "lf");
        t.writeToFile(ctx(), table(T), "out.csv", "csv", ",", true, "utf-8", true, "lf");
        List<String> lines = Files.readAllLines(tmp.resolve("out.csv"));
        assertEquals(7, lines.size(), "header written once, rows appended");
        assertFalse(Files.readString(tmp.resolve("out.csv")).contains("\r"));
        t.writeToFile(ctx(), table(T), "ansi.csv", "csv", ";", false, "ansi", false, "crlf");
        assertEquals("1;Ann;Pune", Files.readAllLines(tmp.resolve("ansi.csv"), java.nio.charset.Charset.forName("windows-1252")).get(0));
    }

    // ---- Date and time ----

    @Test void dateTimeOptions() {
        DateTimePackage d = new DateTimePackage();
        LocalDateTime a = LocalDateTime.of(2026, 10, 19, 8, 0), b = LocalDateTime.of(2026, 10, 19, 22, 30);
        assertFalse(d.isEqual(ctx(), a, b, true, "UTC"));
        assertTrue(d.isEqual(ctx(), a, b, false, "UTC"));
        assertFalse(d.isEqual(ctx(), a, b, false, "Asia/Kolkata"), "22:30 UTC is the next day in India");
        assertTrue(d.isBetween(ctx(), a, a.minusDays(1), b, true, "UTC"));
        assertEquals(false, d.isAfterAction(ctx(), b, a, false, "UTC").get("result"));
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 0, 0, 250_000_000), d.add(ctx(), a, 250, "milliseconds").get("result"));
        num(-1500, d.difference(ctx(), a.plusSeconds(1).plusNanos(500_000_000), a, "milliseconds", false).get("result"));
        num(1500, d.difference(ctx(), a.plusSeconds(1).plusNanos(500_000_000), a, "milliseconds", true).get("result"));
        assertEquals("Montag, 19 Oktober 2026", d.format(ctx(), a, "EEEE, d MMMM yyyy", "UTC", "de-DE", null).get("text"));
        assertEquals("2026-10-19T08:00:00Z", d.format(ctx(), a, null, "UTC", "en", "ISO_INSTANT").get("text"));
        assertEquals(LocalDateTime.of(2026, 10, 19, 2, 30), d.parse(ctx(), "2026-10-19T08:00:00+05:30", null, "UTC", "en", "ISO_OFFSET_DATE_TIME").get("value"));
        assertEquals(LocalDateTime.of(2026, 3, 5, 0, 0), d.parse(ctx(), "5 März 2026", "d MMMM yyyy", "UTC", "de-DE", null).get("value"));
        num(43, d.getPart(ctx(), a, "week_of_year", "UTC", "en").get("result"));
        num(4, d.getPart(ctx(), a, "quarter", "UTC", "en").get("result"));
        num(1, d.getPart(ctx(), a, "day_of_week", "UTC", "en").get("result"));
        assertEquals("Monday", d.getPart(ctx(), a, "day_name", "UTC", "en").get("result"));
        num(31, d.getPart(ctx(), a, "days_in_month", "UTC", "en").get("result"));
        num(13, d.getPart(ctx(), a, "hour", "Asia/Kolkata", "en").get("result"));
        assertEquals("DATETIME.BAD_PART", code(() -> d.getPart(ctx(), a, "fortnight", "UTC", "en")));
    }

    // ---- JSON ----

    @Test @SuppressWarnings("unchecked") void jsonOptions() throws Exception {
        JsonPackage j = new JsonPackage();
        Map<String, Object> doc = (Map<String, Object>) JsonLite.parse("{\"order\":{\"id\":7,\"lines\":[{\"sku\":\"A\"}]},\"customer\":{\"name\":\"Ann\",\"address\":{\"city\":\"Pune\"}}}");
        assertEquals("{\n  \"a\": [\n    1,\n    2\n  ]\n}", j.stringify(ctx(), Map.of("a", List.of(1, 2)), true, 2).get("text"));
        Map<String, Object> missing = j.getNode(ctx(), doc, "order.total", BigDecimal.ZERO, false);
        assertEquals(false, missing.get("found"));
        num(0, missing.get("result"));
        assertEquals("JSON.PATH_NOT_FOUND", code(() -> j.getNode(ctx(), doc, "order.total", null, true)));
        Map<String, Object> many = j.getNodes(ctx(), doc, Map.of("id", "order.id", "sku", "order.lines[0].sku", "x", "nope"));
        assertEquals("A", ((Map<String, Object>) many.get("values")).get("sku"));
        assertEquals(List.of("x"), many.get("missing"));
        Map<String, Object> ow = (Map<String, Object>) j.insertNode(ctx(), doc, "order.id", BigDecimal.valueOf(8), "overwrite", false).get("value");
        num(8, ((Map<String, Object>) ow.get("order")).get("id"));
        Map<String, Object> skip = (Map<String, Object>) j.insertNode(ctx(), doc, "order.id", 8, "skip", false).get("value");
        num(7, ((Map<String, Object>) skip.get("order")).get("id"));
        Map<String, Object> deep = (Map<String, Object>) j.insertNode(ctx(), doc, "meta.source.system", "ERP", "error", true).get("value");
        assertEquals("ERP", ((Map<String, Object>) ((Map<String, Object>) deep.get("meta")).get("source")).get("system"));
        Map<String, Object> up = (Map<String, Object>) j.updateNode(ctx(), doc, "order.status", "new", true).get("value");
        assertEquals("new", ((Map<String, Object>) up.get("order")).get("status"));
        Map<String, Object> flat = (Map<String, Object>) j.toTable(ctx(), List.of(doc.get("customer")), null, true).get("table");
        assertEquals(List.of("name", "address.city"), flat.get("columns"));
        j.writeFile(ctx(), "p.json", Map.of("k", "v"), true, "utf-8-bom");
        byte[] bytes = Files.readAllBytes(tmp.resolve("p.json"));
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals("v", ((Map<String, Object>) j.readFile(ctx(), "p.json", "utf-8").get("value")).get("k"));
    }

    @Test @SuppressWarnings("unchecked") void jsonSchemaValidation() throws Exception {
        JsonPackage j = new JsonPackage();
        String schema = """
            {"type":"object","required":["id","email","lines"],"additionalProperties":false,
             "properties":{"id":{"type":"integer","minimum":1},"email":{"type":"string","pattern":"@"},
               "status":{"enum":["open","closed"]},
               "lines":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/line"}}},
             "$defs":{"line":{"type":"object","required":["qty"],"properties":{"qty":{"type":"number","exclusiveMinimum":0}}}}}""";
        Map<String, Object> ok = j.validate(ctx(), "{\"id\":1,\"email\":\"a@b\",\"lines\":[{\"qty\":2}]}", null, schema, null);
        assertEquals(true, ok.get("valid"), String.valueOf(ok.get("errors")));
        assertEquals("schema", ok.get("level"));
        Map<String, Object> bad = j.validate(ctx(), "{\"id\":0,\"email\":\"ab\",\"status\":\"x\",\"lines\":[{\"qty\":0}],\"extra\":1}", null, schema, null);
        assertEquals(false, bad.get("valid"));
        List<Object> errors = (List<Object>) bad.get("errors");
        assertEquals(5, errors.size(), String.valueOf(errors));
        assertTrue(errors.stream().anyMatch(e -> String.valueOf(e).startsWith("$.lines[0].qty")), String.valueOf(errors));
        Files.writeString(tmp.resolve("s.json"), "{\"type\":\"array\"}");
        assertEquals(false, j.validate(ctx(), "{}", null, null, "s.json").get("valid"));
        assertEquals("syntax", j.validate(ctx(), "{oops", null, schema, null).get("level"));
    }

    // ---- XML ----

    static final String X = "<inv id=\"9\"><line sku=\"A\">1</line><line sku=\"B\">2</line></inv>";

    @Test @SuppressWarnings("unchecked") void xmlAttributesTextEditsAndPositions() throws Exception {
        XmlPackage x = new XmlPackage();
        assertEquals("9", x.getValue(ctx(), "/inv", X, null, "id", "text").get("value"));
        assertEquals("/inv[1]/line[2]", x.getValue(ctx(), "//line[@sku='B']", X, null, null, "xpath").get("value"));
        assertEquals(List.of("A", "B"), x.getNodes(ctx(), "//line", X, null, "sku", "text").get("values"));
        assertEquals("<line sku=\"A\">1</line>", ((List<Object>) x.getNodes(ctx(), "//line", X, null, null, "xml").get("values")).get(0));
        String up = (String) x.setValue(ctx(), null, "//line", "Z", null, "sku", X).get("xml");
        assertEquals(List.of("Z", "Z"), x.getNodes(ctx(), "//line", up, null, "sku", "text").get("values"));
        String first = (String) x.insertNode(ctx(), null, "/inv", "head", "h", null, null, "start", "insert", X).get("xml");
        assertEquals("head", x.xpathFunction(ctx(), "name(/inv/*[1])", first, null, "string").get("result"));
        String after = (String) x.insertNode(ctx(), null, "//line[@sku='A']", "note", "n", null, null, "after", "insert", X).get("xml");
        assertEquals("note", x.xpathFunction(ctx(), "name(/inv/*[2])", after, null, "string").get("result"));
        Map<String, Object> skip = x.insertNode(ctx(), null, "/inv", "line", "3", null, null, "end", "skip", X);
        num(0, skip.get("count"));
        String over = (String) x.insertNode(ctx(), null, "/inv", "line", "new", null, null, "end", "overwrite", X).get("xml");
        assertEquals(List.of("new", "2"), x.getNodes(ctx(), "//line", over, null, null, "text").get("values"));
        assertEquals("XML.BAD_POSITION", code(() -> x.insertNode(ctx(), null, "/inv", "n", null, null, null, "before", "insert", X)));
        Map<String, Object> del = x.deleteNodes(ctx(), null, "//line", null, "sku", X);
        num(2, del.get("count"));
        assertFalse(((String) del.get("xml")).contains("sku"));
        Files.writeString(tmp.resolve("in.xml"), X);
        x.setValue(ctx(), "in.xml", "/inv", "10", "out.xml", "id", null);
        assertTrue(Files.readString(tmp.resolve("out.xml")).contains("id=\"10\""));
        assertEquals(X, Files.readString(tmp.resolve("in.xml")), "save_to leaves the source alone");
    }

    @Test void xmlWellFormedCheckAndNodeIterator() {
        XmlPackage x = new XmlPackage();
        assertEquals(true, x.validate(ctx(), null, X, null).get("valid"));
        Map<String, Object> bad = x.validate(ctx(), null, "<a><b></a>", null);
        assertEquals(false, bad.get("valid"));
        assertEquals(1, ((List<?>) bad.get("errors")).size());
        List<Object> it = new ArrayList<>();
        x.nodes(ctx(), "//line", X, null, "sku", "text").forEach(it::add);
        assertEquals(List.of("A", "B"), it);
        assertTrue(PackageIntrospector.inspect(XmlPackage.class).iterators().containsKey("nodes"));
    }

    // ---- CSV ----

    @Test @SuppressWarnings("unchecked") void csvReadOptionsIteratorAndWriteOptions() throws Exception {
        CsvPackage c = new CsvPackage();
        Files.write(tmp.resolve("in.csv"), "report v1\nname ; city \n Ann ; Pune \n;\nBob;Goa\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> r = c.read(ctx(), "in.csv", ";", true, "utf-8", "both", 1, null, true, false);
        Map<String, Object> t = (Map<String, Object>) r.get("table");
        assertEquals(List.of("name", "city"), t.get("columns"));
        num(2, r.get("row_count"));
        assertEquals(List.of("Ann", "Pune"), ((List<Object>) t.get("rows")).get(0));
        num(1, c.read(ctx(), "in.csv", ";", true, "utf-8", "both", 1, 1, true, false).get("row_count"));
        num(3, c.read(ctx(), "in.csv", ";", true, "utf-8", "none", 1, null, false, false).get("row_count"));
        List<Object> recs = new ArrayList<>();
        c.rows(ctx(), "in.csv", ";", true, "utf-8", "both", false).forEach(recs::add);
        assertEquals(4, recs.size(), "the iterator reads from the first line");
        Files.write(tmp.resolve("u16.csv"), "a,b\r\n1,2\r\n".getBytes(StandardCharsets.UTF_16LE));
        assertEquals(List.of("a", "b"), ((Map<String, Object>) c.read(ctx(), "u16.csv", ",", true, "unicode", "none", 0, null, false, false).get("table")).get("columns"));
        c.write(ctx(), table("{\"columns\":[\"a\"],\"rows\":[[\"x\"]]}"), "q.csv", ",", true, false, "utf-8-bom", true, "lf");
        byte[] q = Files.readAllBytes(tmp.resolve("q.csv"));
        assertEquals((byte) 0xEF, q[0]);
        assertEquals("﻿\"a\"\n\"x\"\n", new String(q, StandardCharsets.UTF_8));
        assertEquals("FILE.BAD_ENCODING", code(() -> c.read(ctx(), "in.csv", ",", true, "klingon", "none", 0, null, false, false)));
    }

    // ---- Shared Global values and run context ----

    @Test void sharedValuesTypesConditionAndService() throws Exception {
        GlobalValuesPackage g = new GlobalValuesPackage();
        Files.writeString(tmp.resolve("global-values.json"), "{\"limit\": \"1000\", \"fin_team\": \"A\", \"fin_head\": \"B\", \"other\": 1}");
        num(1000, g.get(ctx(), "limit", null, "Number").get("value"));
        assertEquals("GLOBALS.WRONG_TYPE", code(() -> g.get(ctx(), "fin_team", null, "Number")));
        assertTrue(g.isDefined(ctx(), "limit"));
        assertFalse(g.isDefined(ctx(), "nope"));
        assertEquals(Set.of("fin_team", "fin_head"), ((Map<?, ?>) g.getAll(ctx(), "fin_").get("values")).keySet());
        ActionContext withService = PackagesTestSupport.ctx(tmp, Map.of("global_values", (java.util.function.Supplier<Object>) () -> Map.of("limit", BigDecimal.valueOf(5))));
        num(5, g.get(withService, "limit", null, "Any").get("value"));
    }

    @Test void runContextValues() {
        RunContextPackage r = new RunContextPackage();
        assertEquals("s1", r.getValue(ctx(), "step_id").get("value"));
        assertEquals(System.getProperty("java.version"), r.getValue(ctx(), "java_version").get("value"));
        assertEquals("RUN_CONTEXT.UNKNOWN", code(() -> r.getValue(ctx(), "nope")));
    }
}
