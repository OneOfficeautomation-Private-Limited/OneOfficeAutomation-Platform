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
}
