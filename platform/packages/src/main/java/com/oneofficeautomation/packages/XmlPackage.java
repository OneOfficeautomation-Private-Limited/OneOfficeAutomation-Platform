package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.xpath.*;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

@OoaPackage(id = "ooa.xml", name = "XML", version = "1.0.0", description = "Query and change XML with XPath, convert to dictionaries and validate against XSD.")
public final class XmlPackage {

    private static String source(ActionContext ctx, String xml, String path) {
        if (xml != null && !xml.isBlank()) return xml;
        if (path == null || path.isBlank()) throw new ActionException("XML.NO_SOURCE", "Give either XML text or a file path", "Fill in 'xml' or 'path'.");
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        try { return Files.readString(p); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

    private static NodeList select(Document doc, String xpath) { return select(doc, xpath, null); }

    /** Prefixes an XPath uses (a:note, //inv:Invoice/@cbc:id), ignoring quoted text and axes such as child::. */
    static Set<String> prefixes(String xpath) {
        Set<String> out = new LinkedHashSet<>();
        if (xpath == null) return out;
        String bare = xpath.replaceAll("'[^']*'|\"[^\"]*\"", "''");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![\\w.:-])([A-Za-z_][\\w.-]*):(?!:)(?=[A-Za-z_*])").matcher(bare);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Namespace-aware parsing is used when the XPath has prefixes or namespaces are given; plain XPaths keep matching by name. */
    static boolean namespaced(String xpath, Map<String, Object> namespaces) {
        return (namespaces != null && !namespaces.isEmpty()) || !prefixes(xpath).isEmpty();
    }

    static Document parseFor(String text, String xpath, Map<String, Object> namespaces) {
        return XmlSupport.parse(text, namespaced(xpath, namespaces));
    }

    /**
     * Nodes matching an XPath. Prefixes resolve through the namespaces input and the xmlns:prefix declarations in the
     * document (so //a:note works when the document declares xmlns:a); a prefix bound nowhere is an error instead of an
     * empty result.
     */
    static NodeList select(Document doc, String xpath, Map<String, Object> namespaces) {
        XPath x = XPathFactory.newInstance().newXPath();
        Set<String> used = prefixes(xpath);
        if (!used.isEmpty() || (namespaces != null && !namespaces.isEmpty())) {
            Map<String, String> ns = declared(doc);
            if (namespaces != null) namespaces.forEach((k, v) -> ns.put(k, v == null ? "" : String.valueOf(v)));
            for (String p : used) if (!ns.containsKey(p))
                throw new ActionException("XML.UNKNOWN_PREFIX", "XPath prefix '" + p + ":' is not declared in the document or in namespaces",
                        "Add it to namespaces as {\"" + p + "\": \"namespace URI\"}" + (ns.isEmpty() ? "." : "; the document declares " + ns.keySet() + "."));
            x.setNamespaceContext(new javax.xml.namespace.NamespaceContext() {
                public String getNamespaceURI(String prefix) { return ns.getOrDefault(prefix, XMLConstants.NULL_NS_URI); }
                public String getPrefix(String uri) { return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey).findFirst().orElse(null); }
                public Iterator<String> getPrefixes(String uri) { return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey).iterator(); }
            });
        }
        try { return (NodeList) x.evaluate(xpath, doc, XPathConstants.NODESET); }
        catch (XPathExpressionException e) {
            throw new ActionException("XML.BAD_XPATH", "Invalid XPath '" + xpath + "': " + xpathError(e), "Example: /invoice/lines/line[1]/amount");
        }
    }

    /** Every xmlns:prefix declared anywhere in the document. */
    static Map<String, String> declared(Document doc) {
        Map<String, String> ns = new LinkedHashMap<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            NamedNodeMap at = all.item(i).getAttributes();
            for (int j = 0; at != null && j < at.getLength(); j++) {
                String n = at.item(j).getNodeName();
                if (n.startsWith("xmlns:")) ns.putIfAbsent(n.substring(6), at.item(j).getNodeValue());
            }
        }
        return ns;
    }

    /** The XPath engine's explanation without Java class names ("javax.xml.transform.TransformerException: ..."). */
    static String xpathError(Exception e) {
        String m = e.getMessage();
        if ((m == null || m.isBlank()) && e.getCause() != null) m = e.getCause().getMessage();
        if (m == null) m = "the expression could not be read";
        return m.replaceAll("(?:[a-z_][\\w]*\\.)+[A-Z]\\w*(?:Exception|Error):\\s*", "").strip();
    }

    private static final String D_NS = "Prefix to namespace URI for prefixes in the XPath, e.g. {\"inv\": \"urn:example:invoice\"}; prefixes declared in the document work without this. Map a default namespace (xmlns=\"...\") to a prefix of your choice to address it.";

    private static void save(ActionContext ctx, Document doc, String path, String saveTo) {
        Path out = ctx.resolvePath(saveTo != null && !saveTo.isBlank() ? saveTo : path);
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, XmlSupport.write(doc, false));
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + out + ": " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

    private static String needFile(String path) {
        if (path == null || path.isBlank()) throw new ActionException("XML.NO_SOURCE", "This action changes a file; give its path", "Fill in 'path'.");
        return path;
    }

    /** Edits XML text (returned, and saved only to save_to) or a file (saved in place or to save_to); returns the new XML text. */
    private static String edit(ActionContext ctx, String xml, String path, String saveTo, java.util.function.Consumer<Document> change) {
        return edit(ctx, xml, path, saveTo, false, change);
    }

    private static String edit(ActionContext ctx, String xml, String path, String saveTo, boolean namespaceAware, java.util.function.Consumer<Document> change) {
        boolean text = xml != null && !xml.isBlank();
        Document doc = XmlSupport.parse(text ? xml : source(ctx, null, needFile(path)), namespaceAware);
        change.accept(doc);
        if (!text) save(ctx, doc, path, saveTo);
        else if (saveTo != null && !saveTo.isBlank()) save(ctx, doc, null, saveTo);
        return XmlSupport.write(doc, false);
    }

    /** Text, outer XML, an attribute or the XPath of a matched node. */
    private static String describe(Node n, String attribute, String returnValue) {
        if (attribute != null && !attribute.isBlank()) return n instanceof Element e && e.hasAttribute(attribute) ? e.getAttribute(attribute) : "";
        return switch (returnValue == null ? "text" : returnValue) {
            case "xml" -> XmlSupport.write(n, false);
            case "xpath" -> xpathOf(n);
            case "text" -> n.getTextContent();
            default -> throw new ActionException("XML.BAD_OPTION", "Unknown return_value '" + returnValue + "'", "Use text, xml or xpath.");
        };
    }

    static String xpathOf(Node n) {
        if (n instanceof Attr a) return xpathOf(a.getOwnerElement()) + "/@" + a.getName();
        StringBuilder sb = new StringBuilder();
        for (Node cur = n; cur != null && cur.getNodeType() == Node.ELEMENT_NODE; cur = cur.getParentNode()) {
            int i = 1;
            for (Node s = cur.getPreviousSibling(); s != null; s = s.getPreviousSibling()) if (s.getNodeType() == Node.ELEMENT_NODE && s.getNodeName().equals(cur.getNodeName())) i++;
            sb.insert(0, "/" + cur.getNodeName() + "[" + i + "]");
        }
        return sb.toString();
    }

    public Map<String, Object> getValue(ActionContext ctx, String xpath, String xml, String path, String attribute, String returnValue) {
        return getValue(ctx, xpath, xml, path, attribute, returnValue, null);
    }

    @Action(id = "get_value", name = "Get single node", category = "XML", idempotent = true, description = "Read the text of the first element a path such as //order/id finds.",
            panel = "(Source: An XML file = path / XML text = xml), xpath, attribute | return_value, namespaces")
    @Output(name = "value", type = OoaType.STRING, description = "Node text, empty when nothing matches.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when a node matched.")
    public Map<String, Object> getValue(ActionContext ctx,
            @Input(name = "xpath", type = OoaType.STRING, example = "/invoice/total", description = "XPath expression.") String xpath,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "attribute", type = OoaType.STRING, required = false, description = "Return this attribute of the node instead of its text.") String attribute,
            @Input(name = "return_value", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "xml", "xpath"},
                    description = "What to return: the node's text, its XML, or its exact XPath.") String returnValue,
            @Input(name = "namespaces", type = OoaType.DICTIONARY, required = false, example = "{\"inv\": \"urn:example:invoice\"}", description = D_NS) Map<String, Object> namespaces) {
        NodeList n = select(parseFor(source(ctx, xml, path), xpath, namespaces), xpath, namespaces);
        return Out.of("value", n.getLength() > 0 ? describe(n.item(0), attribute, returnValue) : "", "found", n.getLength() > 0);
    }

    @Action(id = "get_nodes", name = "Get multiple nodes", category = "XML", idempotent = true, description = "Read the text of every element a path such as //order/id finds.",
            panel = "(Source: An XML file = path / XML text = xml), xpath, return_value, attribute | namespaces")
    @Output(name = "values", type = OoaType.LIST, description = "Texts of matching nodes.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matches.")
    public Map<String, Object> getNodes(ActionContext ctx,
            @Input(name = "xpath", type = OoaType.STRING, example = "//line/sku", description = "XPath expression.") String xpath,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "attribute", type = OoaType.STRING, required = false, description = "Return this attribute of each node instead of its text.") String attribute,
            @Input(name = "return_value", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "xml", "xpath"},
                    description = "What to return for each node: its text, its XML, or its exact XPath.") String returnValue,
            @Input(name = "namespaces", type = OoaType.DICTIONARY, required = false, example = "{\"inv\": \"urn:example:invoice\"}", description = D_NS) Map<String, Object> namespaces) {
        NodeList n = select(parseFor(source(ctx, xml, path), xpath, namespaces), xpath, namespaces);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < n.getLength(); i++) out.add(describe(n.item(i), attribute, returnValue));
        return Out.of("values", out, "count", BigDecimal.valueOf(out.size()));
    }

    public Map<String, Object> getNodes(ActionContext ctx, String xpath, String xml, String path, String attribute, String returnValue) {
        return getNodes(ctx, xpath, xml, path, attribute, returnValue, null);
    }

    public Iterable<Object> nodes(ActionContext ctx, String xpath, String xml, String path, String attribute, String returnValue) {
        return nodes(ctx, xpath, xml, path, attribute, returnValue, null);
    }

    @LoopIterator(id = "nodes", name = "Each node in XML", description = "Yield each node matching an XPath: its text, XML, XPath or an attribute.")
    public Iterable<Object> nodes(ActionContext ctx,
            @Input(name = "xpath", type = OoaType.STRING, example = "//line", description = "XPath expression.") String xpath,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "attribute", type = OoaType.STRING, required = false, description = "Yield this attribute of each node instead of its text.") String attribute,
            @Input(name = "return_value", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "xml", "xpath"},
                    description = "What to yield for each node: its text, its XML, or its exact XPath.") String returnValue,
            @Input(name = "namespaces", type = OoaType.DICTIONARY, required = false, example = "{\"inv\": \"urn:example:invoice\"}", description = D_NS) Map<String, Object> namespaces) {
        @SuppressWarnings("unchecked") List<Object> values = (List<Object>) getNodes(ctx, xpath, xml, path, attribute, returnValue, namespaces).get("values");
        return values;
    }

    @Action(id = "to_dictionary", name = "Convert to dictionary", category = "XML", idempotent = true,
            description = "Turn XML (or the part a path finds) into a Dictionary; attributes become keys starting with @.",
            panel = "(Source: An XML file = path / XML text = xml), xpath")
    @Output(name = "value", description = "Dictionary or text.")
    public Map<String, Object> toDictionary(ActionContext ctx,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "xpath", type = OoaType.STRING, required = false, description = "Convert only the first node matching this XPath.") String xpath) {
        Document doc = XmlSupport.parse(source(ctx, xml, path), true);
        Element e = doc.getDocumentElement();
        if (xpath != null && !xpath.isBlank()) {
            NodeList n = select(XmlSupport.parse(source(ctx, xml, path), false), xpath);
            if (n.getLength() == 0 || !(n.item(0) instanceof Element el)) return Out.of("value", null);
            e = el;
        }
        return Out.of("value", XmlSupport.toValue(e));
    }

    @Action(id = "set_value", name = "Update node", category = "XML", capabilities = "filesystem", description = "Change the text (or an attribute) of every element a path finds, in a file or in XML text.",
            panel = "(Source: An XML file = path, save_to / XML text = xml), xpath, value, attribute | attributes, namespaces")
    @Output(name = "count", type = OoaType.NUMBER, description = "Nodes changed.")
    @Output(name = "xml", type = OoaType.STRING, description = "The XML after the change.")
    public Map<String, Object> setValue(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "xpath", type = OoaType.STRING, description = "XPath of the nodes to change.") String xpath,
            @Input(name = "value", type = OoaType.STRING, required = false, description = "New text (or the attribute's new value).") String value,
            @Input(name = "save_to", type = OoaType.FILE, required = false, description = "Save to another file instead of overwriting.") String saveTo,
            @Input(name = "attribute", type = OoaType.STRING, required = false, description = "Set this attribute (created when missing) instead of the node text.") String attribute,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text to change instead of a file; the result is in the xml output.") String xml,
            @Input(name = "attributes", type = OoaType.DICTIONARY, required = false, example = "{\"status\": \"paid\", \"currency\": \"EUR\"}",
                    description = "Several attributes to set at once, as name to value (created when missing). With these, the node text changes only when value is filled in.") Map<String, Object> attributes,
            @Input(name = "namespaces", type = OoaType.DICTIONARY, required = false, example = "{\"inv\": \"urn:example:invoice\"}", description = D_NS) Map<String, Object> namespaces) {
        boolean many = attributes != null && !attributes.isEmpty();
        boolean one = attribute != null && !attribute.isBlank();
        boolean setText = !one && (!many || (value != null && !value.isEmpty()));
        if (value == null && !many) throw new ActionException("XML.NO_VALUE", "Give the new value", "Fill in 'value', or 'attributes' to set several attributes.");
        int[] count = {0};
        String out = edit(ctx, xml, path, saveTo, namespaced(xpath, namespaces), doc -> {
            NodeList n = select(doc, xpath, namespaces);
            count[0] = n.getLength();
            try {
                for (int i = 0; i < n.getLength(); i++) {
                    if (one) { if (n.item(i) instanceof Element e) e.setAttribute(attribute, value == null ? "" : value); }
                    else if (setText) n.item(i).setTextContent(value);
                    if (many && n.item(i) instanceof Element e)
                        for (Map.Entry<String, Object> a : attributes.entrySet()) e.setAttribute(a.getKey(), a.getValue() == null ? "" : String.valueOf(a.getValue()));
                }
            } catch (DOMException e) { throw new ActionException("XML.BAD_NAME", "Invalid attribute name: " + e.getMessage(), "Use an XML name: letters, digits, - _ and ., starting with a letter."); }
        });
        return Out.of("count", BigDecimal.valueOf(count[0]), "xml", out);
    }

    public Map<String, Object> setValue(ActionContext ctx, String path, String xpath, String value, String saveTo, String attribute, String xml) {
        return setValue(ctx, path, xpath, value, saveTo, attribute, xml, null, null);
    }

    public Map<String, Object> setValue(ActionContext ctx, String path, String xpath, String value, String saveTo, String attribute, String xml, Map<String, Object> attributes) {
        return setValue(ctx, path, xpath, value, saveTo, attribute, xml, attributes, null);
    }

    @Action(id = "insert_node", name = "Insert node", category = "XML", capabilities = "filesystem", description = "Add an element, with its text and attributes, under, before or after every element a path finds.",
            panel = "(Source: An XML file = path, save_to / XML text = xml), parent_xpath, name, text, if_exists, position, attributes | reference_child, namespaces")
    @Output(name = "count", type = OoaType.NUMBER, description = "Elements inserted.")
    @Output(name = "xml", type = OoaType.STRING, description = "The XML after the change.")
    public Map<String, Object> insertNode(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "parent_xpath", type = OoaType.STRING, description = "XPath of the parent node(s).") String parentXpath,
            @Input(name = "name", type = OoaType.STRING, description = "Element name.") String name,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Element text.") String text,
            @Input(name = "attributes", type = OoaType.DICTIONARY, required = false, description = "Attributes to set.") Map<String, Object> attrs,
            @Input(name = "save_to", type = OoaType.FILE, required = false, description = "Save to another file instead of overwriting.") String saveTo,
            @Input(name = "position", type = OoaType.STRING, required = false, defaultValue = "\"end\"", enumValues = {"end", "start", "before", "after"},
                    description = "end or start: last or first child of the matched node; before or after: next to the matched node, as its sibling.") String position,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"insert\"", enumValues = {"insert", "skip", "overwrite"},
                    description = "When the parent already has a child with this name (end/start only): add another, skip, or replace the first one.") String ifExists,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text to change instead of a file; the result is in the xml output.") String xml,
            @Input(name = "reference_child", type = OoaType.STRING, required = false, visibleWhen = "position=before|after", example = "total",
                    description = "With position before or after: insert next to the first child of the matched node with this element name, instead of next to the matched node itself.") String referenceChild,
            @Input(name = "namespaces", type = OoaType.DICTIONARY, required = false, example = "{\"inv\": \"urn:example:invoice\"}",
                    description = "Namespace declarations put on the new element, as prefix to URI (an empty prefix is the default namespace); name may then use the prefix, e.g. inv:note.") Map<String, Object> namespaces) {
        String pos = position == null ? "end" : position;
        if (!List.of("end", "start", "before", "after").contains(pos)) throw new ActionException("XML.BAD_OPTION", "Unknown position '" + pos + "'", "Use end, start, before or after.");
        String ref = referenceChild == null || referenceChild.isBlank() ? null : referenceChild.strip();
        if (ref != null && !(pos.equals("before") || pos.equals("after")))
            throw new ActionException("XML.BAD_OPTION", "reference_child needs position before or after", "Set position to before or after, or leave reference_child empty.");
        Map<String, String> ns = new LinkedHashMap<>();
        if (namespaces != null) for (Map.Entry<String, Object> e : namespaces.entrySet()) {
            String prefix = e.getKey() == null ? "" : e.getKey().strip(), uri = e.getValue() == null ? "" : String.valueOf(e.getValue()).strip();
            if (!prefix.isEmpty() && (!prefix.matches("[A-Za-z_][A-Za-z0-9_.-]*") || prefix.toLowerCase(Locale.ROOT).startsWith("xml")))
                throw new ActionException("XML.BAD_NAMESPACE", "Invalid namespace prefix '" + prefix + "'", "Use a prefix of letters, digits, _ . or -, not starting with xml.");
            if (uri.isEmpty()) throw new ActionException("XML.BAD_NAMESPACE", "The namespace prefix '" + prefix + "' has no URI", "Give each prefix its namespace URI.");
            ns.put(prefix, uri);
        }
        int[] count = {0};
        // a prefixed parent_xpath (//inv:lines) resolves through the document's own declarations, as in Get single node
        String out = edit(ctx, xml, path, saveTo, !prefixes(parentXpath).isEmpty(), doc -> {
            NodeList n = select(doc, parentXpath, null);
            try {
                for (int i = 0; i < n.getLength(); i++) {
                    Node target = n.item(i);
                    Element el = doc.createElement(name);
                    ns.forEach((prefix, uri) -> el.setAttribute(prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix, uri));
                    if (text != null) el.setTextContent(text);
                    if (attrs != null) attrs.forEach((k, v) -> el.setAttribute(k, String.valueOf(v)));
                    if (ref != null) {
                        Node anchor = null;
                        for (Node c = target.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e && e.getNodeName().equals(ref)) { anchor = c; break; }
                        if (anchor == null)
                            throw new ActionException("XML.NO_REFERENCE", "The node " + xpathOf(target) + " has no child element '" + ref + "'", "Check reference_child, or leave it empty to insert next to the matched node.");
                        target.insertBefore(el, pos.equals("before") ? anchor : anchor.getNextSibling());
                        count[0]++;
                        continue;
                    }
                    if (pos.equals("before") || pos.equals("after")) {
                        Node parent = target.getParentNode();
                        if (parent == null || parent.getNodeType() == Node.DOCUMENT_NODE)
                            throw new ActionException("XML.BAD_POSITION", "Cannot insert next to the root element", "Use position end or start, or point the XPath at a child node.");
                        parent.insertBefore(el, pos.equals("before") ? target : target.getNextSibling());
                        count[0]++;
                        continue;
                    }
                    Element existing = null;
                    for (Node c = target.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e && e.getNodeName().equals(name)) { existing = e; break; }
                    if (existing != null && "skip".equals(ifExists)) continue;
                    if (existing != null && "overwrite".equals(ifExists)) { target.replaceChild(el, existing); count[0]++; continue; }
                    if (pos.equals("start")) target.insertBefore(el, target.getFirstChild()); else target.appendChild(el);
                    count[0]++;
                }
            } catch (DOMException e) { throw new ActionException("XML.BAD_NAME", "Invalid element or attribute name: " + e.getMessage(), "Use an XML name: letters, digits, - _ and ., starting with a letter."); }
        });
        return Out.of("count", BigDecimal.valueOf(count[0]), "xml", out);
    }

    public Map<String, Object> insertNode(ActionContext ctx, String path, String parentXpath, String name, String text, Map<String, Object> attrs, String saveTo,
            String position, String ifExists, String xml) {
        return insertNode(ctx, path, parentXpath, name, text, attrs, saveTo, position, ifExists, xml, null, null);
    }

    @Action(id = "delete_nodes", name = "Delete nodes", category = "XML", capabilities = "filesystem", description = "Remove every element (or one attribute of each) that a path such as //order/note finds, in a file or in XML text.",
            panel = "(Source: An XML file = path, save_to / XML text = xml), xpath, attribute | namespaces")
    @Output(name = "count", type = OoaType.NUMBER, description = "Nodes (or attributes) removed.")
    @Output(name = "xml", type = OoaType.STRING, description = "The XML after the change.")
    public Map<String, Object> deleteNodes(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "xpath", type = OoaType.STRING, description = "XPath of the nodes to remove.") String xpath,
            @Input(name = "save_to", type = OoaType.FILE, required = false, description = "Save to another file instead of overwriting.") String saveTo,
            @Input(name = "attribute", type = OoaType.STRING, required = false, description = "Remove only this attribute from the matched elements, keeping the elements.") String attribute,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text to change instead of a file; the result is in the xml output.") String xml,
            @Input(name = "namespaces", type = OoaType.DICTIONARY, required = false, example = "{\"inv\": \"urn:example:invoice\"}", description = D_NS) Map<String, Object> namespaces) {
        int[] count = {0};
        String out = edit(ctx, xml, path, saveTo, namespaced(xpath, namespaces), doc -> {
            NodeList n = select(doc, xpath, namespaces);
            for (int i = 0; i < n.getLength(); i++) {
                Node node = n.item(i);
                if (attribute != null && !attribute.isBlank()) {
                    if (node instanceof Element e && e.hasAttribute(attribute)) { e.removeAttribute(attribute); count[0]++; }
                } else if (node instanceof Attr a) { a.getOwnerElement().removeAttributeNode(a); count[0]++; }
                else if (node.getParentNode() != null) { node.getParentNode().removeChild(node); count[0]++; }
            }
        });
        return Out.of("count", BigDecimal.valueOf(count[0]), "xml", out);
    }

    @Action(id = "validate", name = "Validate XML", category = "XML", idempotent = true,
            description = "Check that XML is well written and, if you give an XSD schema, that it follows it; gives the result instead of failing.",
            panel = "(Source: An XML file = path / XML text = xml), [Check against a schema: xsd_path]")
    @Output(name = "valid", type = OoaType.BOOLEAN, description = "True when the document is valid.")
    @Output(name = "errors", type = OoaType.LIST, description = "Validation errors with line numbers.")
    public Map<String, Object> validate(ActionContext ctx,
            @Input(name = "xsd_path", type = OoaType.FILE, required = false, description = "XSD schema file; without it only well-formedness is checked.") String xsdPath,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path) {
        String text = source(ctx, xml, path);
        if (xsdPath == null || xsdPath.isBlank()) {
            try { XmlSupport.parse(text, true); return Out.of("valid", true, "errors", new ArrayList<>()); }
            catch (ActionException e) {
                if ("XML.EXTERNAL_ENTITY_NOT_ALLOWED".equals(e.code()) || "XML.TOO_MANY_ENTITIES".equals(e.code())) throw e;
                return Out.of("valid", false, "errors", new ArrayList<Object>(List.of(e.getMessage())));
            }
        }
        XmlSupport.parse(text, true);  // refuses external entities (XXE) before schema validation
        List<Object> errors = new ArrayList<>();
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            Validator v = sf.newSchema(ctx.resolvePath(xsdPath).toFile()).newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(SAXParseException e) { errors.add("line " + e.getLineNumber() + ": " + e.getMessage()); }
                @Override public void fatalError(SAXParseException e) { errors.add("line " + e.getLineNumber() + ": " + e.getMessage()); }
            });
            v.validate(new StreamSource(new StringReader(text)));
        } catch (SAXException e) {
            throw new ActionException("XML.BAD_SCHEMA", "Cannot use schema " + xsdPath + ": " + e.getMessage(), "Check the XSD file.");
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Out.of("valid", errors.isEmpty(), "errors", errors);
    }

    public Map<String, Object> deleteNodes(ActionContext ctx, String path, String xpath, String saveTo, String attribute, String xml) {
        return deleteNodes(ctx, path, xpath, saveTo, attribute, xml, null);
    }

    @Action(id = "xpath_function", name = "Evaluate XPath", category = "XML", idempotent = true,
            description = "Work something out from XML with an XPath function, such as count(//line) or sum(//amount): text, a number or true/false.",
            panel = "expression, (Source: An XML file = path / XML text = xml), result_type | namespaces")
    @Output(name = "result", description = "Result of the expression as String, Number or Boolean.")
    public Map<String, Object> xpathFunction(ActionContext ctx,
            @Input(name = "expression", type = OoaType.STRING, example = "sum(//line/amount)", description = "XPath expression.") String expression,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "result_type", type = OoaType.STRING, required = false, defaultValue = "\"string\"", enumValues = {"string", "number", "boolean"},
                    description = "Type of the result.") String resultType,
            @Input(name = "namespaces", type = OoaType.DICTIONARY, required = false, example = "{\"inv\": \"urn:example:invoice\"}", description = D_NS) Map<String, Object> namespaces) {
        Document doc = parseFor(source(ctx, xml, path), expression, namespaces);
        String rt = resultType == null ? "string" : resultType;
        javax.xml.namespace.QName q = switch (rt) {
            case "number" -> XPathConstants.NUMBER;
            case "boolean" -> XPathConstants.BOOLEAN;
            case "string" -> XPathConstants.STRING;
            default -> throw new ActionException("XML.BAD_RESULT_TYPE", "Unknown result type '" + rt + "'", "Use string, number or boolean.");
        };
        Object r;
        XPath xp = XPathFactory.newInstance().newXPath();
        if (namespaced(expression, namespaces)) {
            // reuse select's prefix checks and namespace context through a throwaway evaluation of the same context
            Map<String, String> ns = declared(doc);
            if (namespaces != null) namespaces.forEach((k, v) -> ns.put(k, v == null ? "" : String.valueOf(v)));
            for (String p : prefixes(expression)) if (!ns.containsKey(p))
                throw new ActionException("XML.UNKNOWN_PREFIX", "XPath prefix '" + p + ":' is not declared in the document or in namespaces", "Add it to namespaces as {\"" + p + "\": \"namespace URI\"}.");
            xp.setNamespaceContext(new javax.xml.namespace.NamespaceContext() {
                public String getNamespaceURI(String prefix) { return ns.getOrDefault(prefix, XMLConstants.NULL_NS_URI); }
                public String getPrefix(String uri) { return null; }
                public Iterator<String> getPrefixes(String uri) { return Collections.emptyIterator(); }
            });
        }
        try { r = xp.evaluate(expression, doc, q); }
        catch (XPathExpressionException e) {
            throw new ActionException("XML.BAD_XPATH", "Invalid XPath '" + expression + "': " + xpathError(e), "Example: count(//line) or string(/invoice/@id)");
        }
        if (r instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new ActionException("XML.NOT_A_NUMBER", "The expression '" + expression + "' did not produce a number", "Check the nodes contain numbers, or use result_type string.");
            BigDecimal n = new BigDecimal(Double.toString(d)).stripTrailingZeros();
            return Out.of("result", n.scale() < 0 ? n.setScale(0) : n);
        }
        return Out.of("result", r);
    }

    public Map<String, Object> xpathFunction(ActionContext ctx, String expression, String xml, String path, String resultType) {
        return xpathFunction(ctx, expression, xml, path, resultType, null);
    }

    @Action(id = "save_file", name = "Save XML file", category = "XML", capabilities = "filesystem",
            description = "Check that XML text is well written and save it to a file, indented if you want.",
            panel = "xml, path, indent, overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the written file.")
    public Map<String, Object> saveFile(ActionContext ctx,
            @Input(name = "xml", type = OoaType.STRING, description = "XML text to save.") String xml,
            @Input(name = "path", type = OoaType.FILE, description = "Target file.") String path,
            @Input(name = "indent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Re-format with indentation instead of keeping the text as given.") boolean indent,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file that is already there; when off, the step fails instead.") boolean overwrite) {
        if (xml == null || xml.isBlank()) throw new ActionException("XML.NO_SOURCE", "The XML text is empty", "Fill in 'xml'.");
        Document doc = XmlSupport.parse(xml, false);
        String text = indent ? XmlSupport.write(doc, true) : xml;
        Path out = ctx.resolvePath(needFile(path));
        if (!overwrite && Files.exists(out)) throw new ActionException("FILE.EXISTS", "A file already exists at " + out, "Choose another path or turn on overwrite.");
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, text);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + out + ": " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Out.of("path", out.toString());
    }

    public Map<String, Object> saveFile(ActionContext ctx, String xml, String path, boolean indent) {
        return saveFile(ctx, xml, path, indent, true);
    }
}
