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
        try { return Files.readString(p); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    private static NodeList select(Document doc, String xpath) {
        try { return (NodeList) XPathFactory.newInstance().newXPath().evaluate(xpath, doc, XPathConstants.NODESET); }
        catch (XPathExpressionException e) {
            throw new ActionException("XML.BAD_XPATH", "Invalid XPath '" + xpath + "': " + e.getMessage(), "Example: /invoice/lines/line[1]/amount");
        }
    }

    private static void save(ActionContext ctx, Document doc, String path, String saveTo) {
        Path out = ctx.resolvePath(saveTo != null && !saveTo.isBlank() ? saveTo : path);
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, XmlSupport.write(doc, false));
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + out + ": " + e.getMessage(), null, true); }
    }

    private static String needFile(String path) {
        if (path == null || path.isBlank()) throw new ActionException("XML.NO_SOURCE", "This action changes a file; give its path", "Fill in 'path'.");
        return path;
    }

    @Action(id = "get_value", name = "Get single node", category = "XML", idempotent = true, description = "Return the text of the first node matching an XPath.")
    @Output(name = "value", type = OoaType.STRING, description = "Node text, empty when nothing matches.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when a node matched.")
    public Map<String, Object> getValue(ActionContext ctx,
            @Input(name = "xpath", type = OoaType.STRING, example = "/invoice/total", description = "XPath expression.") String xpath,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path) {
        NodeList n = select(XmlSupport.parse(source(ctx, xml, path), false), xpath);
        return Out.of("value", n.getLength() > 0 ? n.item(0).getTextContent() : "", "found", n.getLength() > 0);
    }

    @Action(id = "get_nodes", name = "Get multiple nodes", category = "XML", idempotent = true, description = "Return the text of every node matching an XPath.")
    @Output(name = "values", type = OoaType.LIST, description = "Texts of matching nodes.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matches.")
    public Map<String, Object> getNodes(ActionContext ctx,
            @Input(name = "xpath", type = OoaType.STRING, example = "//line/sku", description = "XPath expression.") String xpath,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path) {
        NodeList n = select(XmlSupport.parse(source(ctx, xml, path), false), xpath);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < n.getLength(); i++) out.add(n.item(i).getTextContent());
        return Out.of("values", out, "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "to_dictionary", name = "Convert to dictionary", category = "XML", idempotent = true,
            description = "Convert XML (or the part matched by an XPath) to a Dictionary; attributes become @name keys.")
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

    @Action(id = "set_value", name = "Update node", category = "XML", capabilities = "filesystem", description = "Set the text of every node matching an XPath and save the file.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Nodes changed.")
    public Map<String, Object> setValue(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "XML file.") String path,
            @Input(name = "xpath", type = OoaType.STRING, description = "XPath of the nodes to change.") String xpath,
            @Input(name = "value", type = OoaType.STRING, description = "New text.") String value,
            @Input(name = "save_to", type = OoaType.FILE, required = false, description = "Save to another file instead of overwriting.") String saveTo) {
        Document doc = XmlSupport.parse(source(ctx, null, needFile(path)), false);
        NodeList n = select(doc, xpath);
        for (int i = 0; i < n.getLength(); i++) n.item(i).setTextContent(value);
        save(ctx, doc, path, saveTo);
        return Out.of("count", BigDecimal.valueOf(n.getLength()));
    }

    @Action(id = "insert_node", name = "Insert node", category = "XML", capabilities = "filesystem", description = "Add a child element (with text and attributes) under every node matching an XPath.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Elements inserted.")
    public Map<String, Object> insertNode(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "XML file.") String path,
            @Input(name = "parent_xpath", type = OoaType.STRING, description = "XPath of the parent node(s).") String parentXpath,
            @Input(name = "name", type = OoaType.STRING, description = "Element name.") String name,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Element text.") String text,
            @Input(name = "attributes", type = OoaType.DICTIONARY, required = false, description = "Attributes to set.") Map<String, Object> attrs,
            @Input(name = "save_to", type = OoaType.FILE, required = false, description = "Save to another file instead of overwriting.") String saveTo) {
        Document doc = XmlSupport.parse(source(ctx, null, needFile(path)), false);
        NodeList n = select(doc, parentXpath);
        try {
            for (int i = 0; i < n.getLength(); i++) {
                Element el = doc.createElement(name);
                if (text != null) el.setTextContent(text);
                if (attrs != null) attrs.forEach((k, v) -> el.setAttribute(k, String.valueOf(v)));
                n.item(i).appendChild(el);
            }
        } catch (DOMException e) { throw new ActionException("XML.BAD_NAME", "Invalid element or attribute name: " + e.getMessage()); }
        save(ctx, doc, path, saveTo);
        return Out.of("count", BigDecimal.valueOf(n.getLength()));
    }

    @Action(id = "delete_nodes", name = "Delete nodes", category = "XML", capabilities = "filesystem", description = "Remove every node matching an XPath and save the file.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Nodes removed.")
    public Map<String, Object> deleteNodes(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "XML file.") String path,
            @Input(name = "xpath", type = OoaType.STRING, description = "XPath of the nodes to remove.") String xpath,
            @Input(name = "save_to", type = OoaType.FILE, required = false, description = "Save to another file instead of overwriting.") String saveTo) {
        Document doc = XmlSupport.parse(source(ctx, null, needFile(path)), false);
        NodeList n = select(doc, xpath);
        int count = n.getLength();
        for (int i = 0; i < count; i++) {
            Node node = n.item(i);
            if (node instanceof Attr a) a.getOwnerElement().removeAttributeNode(a);
            else if (node.getParentNode() != null) node.getParentNode().removeChild(node);
        }
        save(ctx, doc, path, saveTo);
        return Out.of("count", BigDecimal.valueOf(count));
    }

    @Action(id = "validate", name = "Validate against XSD", category = "XML", idempotent = true, description = "Check an XML document against an XSD schema; returns the result instead of failing.")
    @Output(name = "valid", type = OoaType.BOOLEAN, description = "True when the document is valid.")
    @Output(name = "errors", type = OoaType.LIST, description = "Validation errors with line numbers.")
    public Map<String, Object> validate(ActionContext ctx,
            @Input(name = "xsd_path", type = OoaType.FILE, description = "XSD schema file.") String xsdPath,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path) {
        String text = source(ctx, xml, path);
        XmlSupport.parse(text, true);  // refuses DTDs/XXE before schema validation
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
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Out.of("valid", errors.isEmpty(), "errors", errors);
    }

    @Action(id = "xpath_function", name = "Evaluate XPath", category = "XML", idempotent = true,
            description = "Evaluate an XPath expression such as count(//line) or sum(//amount) and return text, a number or true/false.")
    @Output(name = "result", description = "Result of the expression as String, Number or Boolean.")
    public Map<String, Object> xpathFunction(ActionContext ctx,
            @Input(name = "expression", type = OoaType.STRING, example = "sum(//line/amount)", description = "XPath expression.") String expression,
            @Input(name = "xml", type = OoaType.STRING, required = false, description = "XML text (or use path).") String xml,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "XML file (or use xml).") String path,
            @Input(name = "result_type", type = OoaType.STRING, required = false, defaultValue = "\"string\"", enumValues = {"string", "number", "boolean"},
                    description = "Type of the result.") String resultType) {
        Document doc = XmlSupport.parse(source(ctx, xml, path), false);
        String rt = resultType == null ? "string" : resultType;
        javax.xml.namespace.QName q = switch (rt) {
            case "number" -> XPathConstants.NUMBER;
            case "boolean" -> XPathConstants.BOOLEAN;
            case "string" -> XPathConstants.STRING;
            default -> throw new ActionException("XML.BAD_RESULT_TYPE", "Unknown result type '" + rt + "'", "Use string, number or boolean.");
        };
        Object r;
        try { r = XPathFactory.newInstance().newXPath().evaluate(expression, doc, q); }
        catch (XPathExpressionException e) {
            throw new ActionException("XML.BAD_XPATH", "Invalid XPath '" + expression + "': " + (e.getMessage() != null ? e.getMessage() : e.getCause()), "Example: count(//line) or string(/invoice/@id)");
        }
        if (r instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new ActionException("XML.NOT_A_NUMBER", "The expression '" + expression + "' did not produce a number", "Check the nodes contain numbers, or use result_type string.");
            BigDecimal n = new BigDecimal(Double.toString(d)).stripTrailingZeros();
            return Out.of("result", n.scale() < 0 ? n.setScale(0) : n);
        }
        return Out.of("result", r);
    }

    @Action(id = "save_file", name = "Save XML file", category = "XML", capabilities = "filesystem",
            description = "Check that XML text is well-formed and write it to a file, optionally indented.")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the written file.")
    public Map<String, Object> saveFile(ActionContext ctx,
            @Input(name = "xml", type = OoaType.STRING, description = "XML text to save.") String xml,
            @Input(name = "path", type = OoaType.FILE, description = "Target file.") String path,
            @Input(name = "indent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Re-format with indentation instead of keeping the text as given.") boolean indent) {
        if (xml == null || xml.isBlank()) throw new ActionException("XML.NO_SOURCE", "The XML text is empty", "Fill in 'xml'.");
        Document doc = XmlSupport.parse(xml, false);
        String text = indent ? XmlSupport.write(doc, true) : xml;
        Path out = ctx.resolvePath(needFile(path));
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, text);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + out + ": " + e.getMessage(), null, true); }
        return Out.of("path", out.toString());
    }
}
