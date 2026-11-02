package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/** Hardened XML parsing (no DTDs, no external entities: blocks XXE attacks) and XML-to-Dictionary conversion. */
final class XmlSupport {
    private XmlSupport() {}

    /** An entity that would be read from a file or URL (the XXE attack); internal entities and DOCTYPE lines are fine. */
    private static final java.util.regex.Pattern EXTERNAL_ENTITY = java.util.regex.Pattern.compile("(?is)<!ENTITY\\s+(?:%\\s+)?[^\\s>]+\\s+(?:SYSTEM|PUBLIC)\\b");

    /**
     * Parses XML safely. Documents with a DOCTYPE (an internal DTD, or a reference to an external one, which is never
     * downloaded) are read like any other; every DOCTYPE used to be refused. Entities that point to files or URLs are refused
     * (XML.EXTERNAL_ENTITY_NOT_ALLOWED), and entity expansion is capped by the JDK's secure processing limits.
     */
    static Document parse(String xml, boolean namespaceAware) {
        if (xml != null && EXTERNAL_ENTITY.matcher(xml).find())
            throw new ActionException("XML.EXTERNAL_ENTITY_NOT_ALLOWED", "The XML declares an entity that reads a file or URL (SYSTEM/PUBLIC), which is refused for security",
                    "Remove the external <!ENTITY ...> declaration; internal entities and DOCTYPE lines are allowed.");
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(true);
            f.setNamespaceAware(namespaceAware);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            b.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("OOA_EXTERNAL " + systemId); });
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("OOA_EXTERNAL")) throw new ActionException("XML.EXTERNAL_ENTITY_NOT_ALLOWED", "The XML refers to an external entity, which is refused for security",
                    "Remove the external entity or DTD reference.");
            if (msg.contains("JAXP0001") || msg.contains("entity expansion") || msg.contains("entityExpansionLimit") || msg.contains("totalEntitySizeLimit"))
                throw new ActionException("XML.TOO_MANY_ENTITIES", "The XML expands entities beyond the safe limit (an entity-expansion attack or a very unusual document)", "Remove the nested entity declarations.");
            throw new ActionException("XML.INVALID", "Invalid XML: " + msg, "Check the document is well-formed XML.");
        }
    }

    static String write(Node doc, boolean indent) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, indent ? "yes" : "no");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, doc instanceof Document ? "no" : "yes");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (Exception e) { throw new ActionException("XML.WRITE_FAILED", e.getMessage()); }
    }

    /** Element to Dictionary: attributes as "@name", text as "#text" (or the plain value when there is nothing else), repeated children as lists. */
    static Object toValue(Element e) {
        Map<String, Object> m = new LinkedHashMap<>();
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) m.put("@" + attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
        StringBuilder text = new StringBuilder();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k instanceof Element c) {
                String name = c.getLocalName() != null ? c.getLocalName() : c.getNodeName();
                Object v = toValue(c);
                if (m.containsKey(name)) {
                    Object old = m.get(name);
                    if (old instanceof List<?> l) { @SuppressWarnings("unchecked") List<Object> lo = (List<Object>) l; lo.add(v); }
                    else { List<Object> l = new ArrayList<>(); l.add(old); l.add(v); m.put(name, l); }
                } else m.put(name, v);
            } else if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) text.append(k.getNodeValue());
        }
        String t = text.toString().trim();
        if (m.isEmpty()) return t;
        if (!t.isEmpty()) m.put("#text", t);
        return m;
    }
}
