package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** XML insert_node next to a named child, and namespace declarations on the new element. */
class XmlMoreOptionsTest {
    @TempDir Path tmp;
    final XmlPackage xp = new XmlPackage();

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    static final String DOC = "<invoice><id>7</id><vendor>Acme</vendor><total>10</total></invoice>";

    @Test void insertBeforeOrAfterANamedChild() throws Exception {
        String after = (String) xp.insertNode(ctx(), null, "/invoice", "currency", "EUR", null, null, "after", "insert", DOC, "vendor", null).get("xml");
        assertTrue(after.contains("<vendor>Acme</vendor><currency>EUR</currency><total>"), after);
        String before = (String) xp.insertNode(ctx(), null, "/invoice", "date", "2026-10-25", null, null, "before", "insert", DOC, "id", null).get("xml");
        assertTrue(before.contains("<invoice><date>2026-10-25</date><id>7</id>"), before);
        // in a file, saved in place
        Path f = tmp.resolve("inv.xml");
        Files.writeString(f, DOC);
        xp.insertNode(ctx(), f.toString(), "/invoice", "tax", "2", null, null, "before", "insert", null, "total", null);
        assertTrue(Files.readString(f).contains("<tax>2</tax><total>10</total>"));
        // the old signature still inserts next to the matched node
        String old = (String) xp.insertNode(ctx(), null, "/invoice/id", "ref", "x", null, null, "after", "insert", DOC).get("xml");
        assertTrue(old.contains("<id>7</id><ref>x</ref>"), old);
    }

    @Test void namespacesAreDeclaredOnTheNewElement() {
        Map<String, Object> ns = new LinkedHashMap<>();
        ns.put("inv", "urn:example:invoice");
        ns.put("", "urn:example:default");
        String xml = (String) xp.insertNode(ctx(), null, "/invoice", "inv:note", "Paid", Map.of("inv:by", "Ann"), null, "end", "insert", DOC, null, ns).get("xml");
        assertTrue(xml.contains("<inv:note"), xml);
        assertTrue(xml.contains("xmlns:inv=\"urn:example:invoice\""), xml);
        assertTrue(xml.contains("xmlns=\"urn:example:default\""), xml);
        assertTrue(xml.contains("inv:by=\"Ann\""), xml);
        // the result is namespace-correct XML
        org.w3c.dom.Document d = XmlSupport.parse(xml, true);
        org.w3c.dom.Node note = d.getDocumentElement().getLastChild();
        assertEquals("urn:example:invoice", note.getNamespaceURI());
        assertEquals("note", note.getLocalName());
    }

    @Test void badInputIsRefused() {
        assertEquals("XML.NO_REFERENCE", assertThrows(ActionException.class,
                () -> xp.insertNode(ctx(), null, "/invoice", "x", null, null, null, "after", "insert", DOC, "missing", null)).code());
        assertEquals("XML.BAD_OPTION", assertThrows(ActionException.class,
                () -> xp.insertNode(ctx(), null, "/invoice", "x", null, null, null, "end", "insert", DOC, "vendor", null)).code());
        assertEquals("XML.BAD_NAMESPACE", assertThrows(ActionException.class,
                () -> xp.insertNode(ctx(), null, "/invoice", "x", null, null, null, "end", "insert", DOC, null, Map.of("a b", "urn:x"))).code());
        assertEquals("XML.BAD_NAMESPACE", assertThrows(ActionException.class,
                () -> xp.insertNode(ctx(), null, "/invoice", "x", null, null, null, "end", "insert", DOC, null, Map.of("p", ""))).code());
        assertEquals("XML.BAD_NAMESPACE", assertThrows(ActionException.class,
                () -> xp.insertNode(ctx(), null, "/invoice", "x", null, null, null, "end", "insert", DOC, null, Map.of("xmlns", "urn:x"))).code());
    }

    @Test void setValueSetsSeveralAttributesAtOnce() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("status", "paid");
        attrs.put("lines", 3);
        String xml = (String) xp.setValue(ctx(), null, "/invoice", null, null, null, DOC, attrs).get("xml");
        assertTrue(xml.contains("status=\"paid\""), xml);
        assertTrue(xml.contains("lines=\"3\""), xml);
        assertTrue(xml.contains("<id>7</id>"), "text left alone: " + xml);
        // together with the single attribute
        String both = (String) xp.setValue(ctx(), null, "/invoice/id", "8", null, "kind", DOC, Map.of("src", "erp")).get("xml");
        assertTrue(both.contains("<id kind=\"8\" src=\"erp\">7</id>") || both.contains("<id src=\"erp\" kind=\"8\">7</id>"), both);
        // with text
        String text = (String) xp.setValue(ctx(), null, "/invoice/id", "9", null, null, DOC, Map.of("src", "erp")).get("xml");
        assertTrue(text.contains("<id src=\"erp\">9</id>"), text);
        // the old signature still sets the text
        assertTrue(((String) xp.setValue(ctx(), null, "/invoice/id", "5", null, null, DOC).get("xml")).contains("<id>5</id>"));
        assertEquals("XML.NO_VALUE", assertThrows(ActionException.class,
                () -> xp.setValue(ctx(), null, "/invoice/id", null, null, null, DOC, null)).code());
    }

    @Test void saveFileCanRefuseToOverwrite() throws Exception {
        Path f = tmp.resolve("out.xml");
        xp.saveFile(ctx(), DOC, f.toString(), false, false);
        assertEquals(DOC, Files.readString(f));
        assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> xp.saveFile(ctx(), "<a/>", f.toString(), false, false)).code());
        assertEquals(DOC, Files.readString(f));
        xp.saveFile(ctx(), "<a/>", f.toString(), false);
        assertEquals("<a/>", Files.readString(f));
    }
}
