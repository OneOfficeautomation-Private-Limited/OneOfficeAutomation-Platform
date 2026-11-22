package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** AA parity, files family (21 Nov 2026): CSV/TXT, JSON and XML sessions plus their new options. */
class DataSessionsParityTest {
    @TempDir Path tmp;
    final CsvPackage csv = new CsvPackage();
    final JsonPackage json = new JsonPackage();
    final XmlPackage xml = new XmlPackage();

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Object table) { return (List<List<Object>>) ((Map<String, Object>) table).get("rows"); }

    @Test void csvOpenReadLoopAndClose() throws Exception {
        // windows-1252 text without a byte order mark: auto takes windows-1252 since it is not valid UTF-8
        Files.write(tmp.resolve("people.csv"), "name;city\nAnn;Köln\nBo;Pune\n".getBytes(java.nio.charset.Charset.forName("windows-1252")));
        ActionContext c = ctx();
        Map<String, Object> opened = csv.open(c, "people.csv", "people", true, ";", "none", "auto", 0, false, false, true);
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) opened.get("row_count")));
        assertEquals(List.of("name", "city"), opened.get("columns"));
        assertTrue(((Set<?>) c.sessions().get(ActionContext.SHARED_SESSIONS)).contains("people"), "shared with agents this one runs");
        Files.delete(tmp.resolve("people.csv"));   // read once: later steps do not need the file
        Map<String, Object> read = csv.read(c, null, null, true, null, null, 0, 1, false, false, "people");
        assertEquals(List.of(List.of("Ann", "Köln")), rows(read.get("table")), "max_rows applies to a session too");
        List<Object> recs = new ArrayList<>();
        csv.rows(c, null, null, true, null, null, false, "people").forEach(recs::add);
        assertEquals(2, recs.size());
        assertEquals("Pune", ((Map<?, ?>) recs.get(1)).get("city"));
        csv.close(c, "people");
        assertEquals("SESSION.NOT_FOUND", assertThrows(OoaException.class, () -> csv.read(c, null, null, true, null, null, 0, null, false, false, "people")).code());
        assertEquals("CSV.NO_SOURCE", assertThrows(ActionException.class, () -> csv.read(c, null, null, true, null, null, 0, null, false, false)).code());
    }

    @Test void csvNewlineDelimiterAndAutoEncoding() throws Exception {
        Files.writeString(tmp.resolve("names.txt"), "﻿id\nA, B\n\nC;D\n", StandardCharsets.UTF_8);
        Map<String, Object> t = csv.read(ctx(), "names.txt", "newline", true, "auto", "none", 0, null, false, false);
        assertEquals(List.of(List.of("A, B"), List.of("C;D")), rows(t.get("table")), "each line is one value; blank lines are skipped");
    }

    @Test void jsonSessionChangesAndSaves() throws Exception {
        Files.writeString(tmp.resolve("order.json"), "{\"id\": 7, \"items\": [1]}");
        ActionContext c = ctx();
        json.startSession(c, null, "order.json", null, "order", false);
        json.insertNode(c, null, "status", "new", null, false, null, "order");
        json.updateNode(c, null, "items", List.of(new BigDecimal("2"), new BigDecimal("3")), false, null, "add", "order");
        json.updateNode(c, null, "id", "8", false, "number", null, "order");
        json.insertNode(c, null, "meta", "{\"a\": true}", null, false, "json", "order");
        assertEquals(new BigDecimal("8"), json.getNode(c, null, "id", null, false, "order").get("result"));
        assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3")), json.getNodeArray(c, null, "items", "order").get("items"));
        json.deleteNode(c, null, "status", "order");
        Map<String, Object> types = json.validate(c, null, null, null, null, true, false, null, Map.of("id", "Number", "meta", "Dictionary", "items", "String"), "order");
        assertEquals(false, types.get("valid"));
        assertEquals(List.of("'items' is List, expected String"), types.get("errors"));
        Map<String, Object> end = json.endSession(c, "order", "saved/order.json", false, null);
        assertEquals(tmp.resolve("saved/order.json").toString(), end.get("path"));
        @SuppressWarnings("unchecked") Map<String, Object> saved = (Map<String, Object>) json.readFile(c, "saved/order.json", null).get("value");
        assertEquals(Map.of("a", true), saved.get("meta"));
        assertFalse(saved.containsKey("status"));
        assertNull(c.sessions().get("order"), "closed");
        // the value path still works as before, and the new options apply to it too
        assertEquals(Map.of("n", "5"), json.updateNode(c, "{\"n\": 5}", "n", 5, false, "text", null, null).get("value"));
        assertEquals("JSON.NO_SOURCE", assertThrows(ActionException.class, () -> json.getNodes(c, null, List.of("a"))).code());
        assertEquals("JSON.CONVERT_FAILED", assertThrows(ActionException.class, () -> json.insertNode(c, "{}", "x", "abc", null, false, "number", null)).code());
    }

    @Test void jsonValidateReadsAFile() throws Exception {
        Files.writeString(tmp.resolve("bad.json"), "{\"a\": ");
        Map<String, Object> r = json.validate(ctx(), null, null, null, null, true, false, "bad.json", null, null);
        assertEquals("syntax", r.get("level"));
        Files.writeString(tmp.resolve("ok.json"), "{\"a\": 1}");
        assertEquals(true, json.validate(ctx(), null, List.of("a"), null, null, true, false, "ok.json", null, null).get("valid"));
    }

    @Test void xmlSessionTableDefaultNamespaceAndSave() throws Exception {
        Path f = tmp.resolve("inv.xml");
        Files.writeString(f, "<inv xmlns=\"urn:x\">\n  <line qty=\"2\"><sku>A</sku></line>\n  <line qty=\"5\"><sku>B</sku></line>\n</inv>");
        ActionContext c = ctx();
        Map<String, Object> started = xml.startSession(c, "inv.xml", null, "inv", true, false);
        assertFalse(((String) started.get("xml")).contains("\n  <line"), "blank text between elements is dropped");
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("sku", "d:sku"); cols.put("qty", "@qty");
        Map<String, Object> got = xml.getNodes(c, "//d:line", null, null, null, null, Map.of("d", ""), cols, "inv");
        assertEquals(List.of(List.of("A", "2"), List.of("B", "5")), rows(got.get("table")), "an empty URI stands for the document's default namespace");
        xml.setValue(c, null, "//line[1]/@qty", "9", null, null, null, null, null, "inv");
        xml.insertNode(c, null, "/inv", "total", "14", null, null, null, null, null, null, null, "inv");
        assertEquals("9", xml.getValue(c, "//line[1]/@qty", null, null, null, null, null, "inv").get("value"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) xml.xpathFunction(c, "count(//line)", null, null, "number", null, "inv").get("result")));
        assertTrue(Files.readString(f).contains("qty=\"2\""), "the file is unchanged until End session saves");
        Map<String, Object> end = xml.endSession(c, "inv", true, null, false);
        assertEquals(f.toString(), end.get("path"));
        assertTrue(Files.readString(f).contains("<total>14</total>") && Files.readString(f).contains("qty=\"9\""));
        // a session loaded from text cannot be saved over its file
        xml.startSession(c, null, "<a/>", "t", false, false);
        assertEquals("XML.NO_SOURCE", assertThrows(ActionException.class, () -> xml.endSession(c, "t", true, null, false)).code());
        // without columns the table has value and xpath
        Map<String, Object> plain = xml.getNodes(c, "//b", "<a><b>1</b><b>2</b></a>", null, null, null);
        assertEquals(List.of(List.of("1", "/a[1]/b[1]"), List.of("2", "/a[1]/b[2]")), rows(plain.get("table")));
    }

    @Test void xmlValidateSeveralSchemasAndDtd() throws Exception {
        Files.writeString(tmp.resolve("a.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"a\"><xs:complexType><xs:sequence>"
                + "<xs:element name=\"n\" type=\"xs:int\"/></xs:sequence></xs:complexType></xs:element></xs:schema>");
        Map<String, Object> bad = xml.validate(ctx(), "a.xsd", "<a><n>x</n></a>", null, List.of(), false, null, null);
        assertEquals(false, bad.get("valid"));
        assertEquals(true, xml.validate(ctx(), null, "<a><n>4</n></a>", null, List.of("a.xsd"), false, null, null).get("valid"), "more_schemas alone works");
        // DTD inside the DOCTYPE
        String internal = "<!DOCTYPE a [<!ELEMENT a (n)><!ELEMENT n (#PCDATA)>]><a><m/></a>";
        assertEquals(false, xml.validate(ctx(), null, internal, null, null, true, null, null).get("valid"));
        // DTD file added to a document without a DOCTYPE
        Files.writeString(tmp.resolve("a.dtd"), "<!ELEMENT a (n)><!ELEMENT n (#PCDATA)>");
        assertEquals(true, xml.validate(ctx(), null, "<a><n>1</n></a>", null, null, true, "a.dtd", null).get("valid"));
        assertEquals(false, xml.validate(ctx(), null, "<a><x/></a>", null, null, true, "a.dtd", null).get("valid"));
        assertEquals("XML.NO_DTD", assertThrows(ActionException.class, () -> xml.validate(ctx(), null, "<a/>", null, null, true, null, null)).code());
        // an outside DTD named by the DOCTYPE is never downloaded
        assertEquals("XML.BAD_DTD", assertThrows(ActionException.class,
                () -> xml.validate(ctx(), null, "<!DOCTYPE a SYSTEM \"http://example.invalid/a.dtd\"><a/>", null, null, true, null, null)).code());
    }

    @Test void newStepDefaultsAndPanels() {
        assertEquals(false, FileRulesParityTest.input(XmlPackage.class, "action", "save_file", "overwrite").newStepDefault());
        assertEquals("auto", FileRulesParityTest.input(CsvPackage.class, "action", "read", "encoding").newStepDefault());
        assertNotNull(PackageIntrospector.manifest(CsvPackage.class));
        assertNotNull(PackageIntrospector.manifest(JsonPackage.class));
        assertNotNull(PackageIntrospector.manifest(XmlPackage.class));
    }
}
