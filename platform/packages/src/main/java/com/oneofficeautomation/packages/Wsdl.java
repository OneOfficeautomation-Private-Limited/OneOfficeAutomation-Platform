package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import org.w3c.dom.*;

import java.util.*;
import java.util.function.Function;

/**
 * Reads a WSDL 1.1 description (X8): its SOAP 1.1 and 1.2 operations, their SOAPAction, endpoint and style, and a
 * request body to fill in, built from the XML schema (inline, imported or included) with ? where a value goes.
 */
final class Wsdl {
    static final String WSDL = "http://schemas.xmlsoap.org/wsdl/", XSD = "http://www.w3.org/2001/XMLSchema",
        SOAP11 = "http://schemas.xmlsoap.org/wsdl/soap/", SOAP12 = "http://schemas.xmlsoap.org/wsdl/soap12/";

    record Operation(String name, String soapAction, String endpoint, String version, String style, String bodyTemplate) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); m.put("soap_action", soapAction); m.put("endpoint", endpoint); m.put("version", version);
            m.put("style", style); m.put("body_template", bodyTemplate);
            return m;
        }
    }

    private record Schema(String ns, boolean qualified) {}
    private final Map<String, Element> elements = new HashMap<>(), types = new HashMap<>();
    private final Map<Element, Schema> schemaOf = new HashMap<>();
    private final Set<String> loaded = new HashSet<>();
    private final Function<String, Document> loader;

    private Wsdl(Function<String, Document> loader) { this.loader = loader; }

    /** The operations of the WSDL; loader fetches imported schemas and WSDLs by their location (relative to base). */
    static List<Operation> read(Document doc, String base, Function<String, Document> loader) {
        Element defs = doc.getDocumentElement();
        if (!WSDL.equals(defs.getNamespaceURI()) || !"definitions".equals(defs.getLocalName()))
            throw new ActionException("SOAP.BAD_WSDL", "This is not a WSDL 1.1 document", "Give the service's ?wsdl address or file.");
        Wsdl w = new Wsdl(loader);
        List<Element> all = new ArrayList<>(List.of(defs));
        // wsdl:import brings in more definitions
        for (Element imp : children(defs, WSDL, "import")) {
            String loc = imp.getAttribute("location");
            if (!loc.isEmpty() && w.loaded.add(resolve(base, loc))) all.add(loader.apply(resolve(base, loc)).getDocumentElement());
        }
        for (Element d : all) for (Element t : children(d, WSDL, "types")) for (Element s : children(t, XSD, "schema")) w.schema(s, base, 0);
        Map<String, Element> messages = new HashMap<>(), portTypes = new HashMap<>(), bindings = new LinkedHashMap<>();
        List<Element> ports = new ArrayList<>();
        for (Element d : all) {
            String tns = d.getAttribute("targetNamespace");
            for (Element m : children(d, WSDL, "message")) messages.put(tns + "|" + m.getAttribute("name"), m);
            for (Element p : children(d, WSDL, "portType")) portTypes.put(tns + "|" + p.getAttribute("name"), p);
            for (Element b : children(d, WSDL, "binding")) bindings.put(tns + "|" + b.getAttribute("name"), b);
            for (Element s : children(d, WSDL, "service")) ports.addAll(children(s, WSDL, "port"));
        }
        List<Operation> out = new ArrayList<>();
        for (Element port : ports) {
            Element binding = bindings.get(qname(port, port.getAttribute("binding")));
            if (binding == null) continue;
            String version = !children(binding, SOAP11, "binding").isEmpty() ? "1.1" : !children(binding, SOAP12, "binding").isEmpty() ? "1.2" : null;
            if (version == null) continue;   // HTTP bindings are not SOAP
            String sns = version.equals("1.1") ? SOAP11 : SOAP12;
            String endpoint = children(port, sns, "address").stream().map(a -> a.getAttribute("location")).findFirst().orElse("");
            String bindingStyle = children(binding, sns, "binding").get(0).getAttribute("style");
            Element portType = portTypes.get(qname(binding, binding.getAttribute("type")));
            for (Element op : children(binding, WSDL, "operation")) {
                String name = op.getAttribute("name");
                Element so = children(op, sns, "operation").stream().findFirst().orElse(null);
                String action = so == null ? "" : so.getAttribute("soapAction");
                String style = so != null && !so.getAttribute("style").isEmpty() ? so.getAttribute("style") : bindingStyle.isEmpty() ? "document" : bindingStyle;
                Element input = portType == null ? null : children(portType, WSDL, "operation").stream().filter(o -> o.getAttribute("name").equals(name))
                    .flatMap(o -> children(o, WSDL, "input").stream()).findFirst().orElse(null);
                Element message = input == null ? null : messages.get(qname(input, input.getAttribute("message")));
                String rpcNs = children(op, WSDL, "input").stream().flatMap(i -> children(i, sns, "body").stream()).map(b -> b.getAttribute("namespace")).findFirst().orElse("");
                out.add(new Operation(name, action, endpoint, version, style, w.body(message, style, name, rpcNs.isEmpty() ? binding.getOwnerDocument().getDocumentElement().getAttribute("targetNamespace") : rpcNs)));
            }
        }
        if (out.isEmpty()) throw new ActionException("SOAP.BAD_WSDL", "The WSDL has no SOAP operations", "Check it describes a SOAP service with a service and port.");
        return out;
    }

    private void schema(Element s, String base, int depth) {
        Schema sc = new Schema(s.getAttribute("targetNamespace"), "qualified".equals(s.getAttribute("elementFormDefault")));
        for (Element e : children(s, XSD, "element")) { elements.put(sc.ns() + "|" + e.getAttribute("name"), e); schemaOf.put(e, sc); }
        for (Element t : children(s, XSD, "complexType")) { types.put(sc.ns() + "|" + t.getAttribute("name"), t); schemaOf.put(t, sc); }
        for (Element t : children(s, XSD, "simpleType")) { types.put(sc.ns() + "|" + t.getAttribute("name"), t); schemaOf.put(t, sc); }
        if (depth >= 4) return;
        List<Element> more = new ArrayList<>(children(s, XSD, "import"));
        more.addAll(children(s, XSD, "include"));
        for (Element imp : more) {
            String loc = imp.getAttribute("schemaLocation");
            if (loc.isEmpty()) continue;
            String at = resolve(base, loc);
            if (!loaded.add(at)) continue;
            Element root = loader.apply(at).getDocumentElement();
            if (XSD.equals(root.getNamespaceURI()) && "schema".equals(root.getLocalName())) schema(root, at, depth + 1);
        }
    }

    // ---------- the request body ----------

    private String body(Element message, String style, String opName, String rpcNs) {
        StringBuilder sb = new StringBuilder();
        List<Element> parts = message == null ? List.of() : children(message, WSDL, "part");
        if ("rpc".equals(style)) {
            sb.append("<ns:").append(opName).append(" xmlns:ns=\"").append(rpcNs).append("\">");
            for (Element p : parts) sb.append('<').append(p.getAttribute("name")).append(">?</").append(p.getAttribute("name")).append('>');
            return sb.append("</ns:").append(opName).append('>').toString();
        }
        for (Element p : parts) {
            Element decl = p.hasAttribute("element") ? elements.get(qname(p, p.getAttribute("element"))) : null;
            if (decl == null) { sb.append('<').append(p.getAttribute("name")).append(">?</").append(p.getAttribute("name")).append('>'); continue; }
            element(sb, decl, true, null, new HashSet<>(), 0);
        }
        return sb.toString();
    }

    /** One element: qualified elements take the ns prefix of their schema, declared where the namespace changes. */
    private void element(StringBuilder sb, Element decl, boolean top, String parentNs, Set<Element> path, int depth) {
        if (decl.hasAttribute("ref")) {
            Element target = elements.get(qname(decl, decl.getAttribute("ref")));
            if (target != null) element(sb, target, true, parentNs, path, depth);
            return;
        }
        Schema sc = schemaOf.getOrDefault(decl, schemaOfAncestor(decl));
        boolean qualified = top || "qualified".equals(decl.getAttribute("form")) || (!"unqualified".equals(decl.getAttribute("form")) && sc.qualified());
        String name = decl.getAttribute("name");
        String tag = qualified ? "ns:" + name : name;
        boolean declare = qualified && !sc.ns().equals(parentNs);
        String childNs = qualified ? sc.ns() : parentNs;
        String repeat = decl.getAttribute("maxOccurs");
        if (!repeat.isEmpty() && !repeat.equals("1")) sb.append("<!-- repeats -->");
        if ("0".equals(decl.getAttribute("minOccurs"))) sb.append("<!-- optional -->");
        sb.append('<').append(tag);
        if (declare) sb.append(" xmlns:ns=\"").append(sc.ns()).append('"');
        Element type = null;
        for (Element c : children(decl, XSD, "complexType")) type = c;
        if (type == null && decl.hasAttribute("type")) type = types.get(qname(decl, decl.getAttribute("type")));
        if (type == null || "simpleType".equals(type.getLocalName()) || depth > 8 || !path.add(decl)) { sb.append(">?</").append(tag).append('>'); return; }
        sb.append('>');
        content(sb, type, childNs, path, depth);
        path.remove(decl);
        sb.append("</").append(tag).append('>');
    }

    private void content(StringBuilder sb, Element type, String ns, Set<Element> path, int depth) {
        for (Element cc : children(type, XSD, "complexContent"))
            for (Element ext : children(cc, XSD, "extension")) {
                Element baseType = types.get(qname(ext, ext.getAttribute("base")));
                if (baseType != null && !"simpleType".equals(baseType.getLocalName())) content(sb, baseType, ns, path, depth + 1);
                content(sb, ext, ns, path, depth + 1);
            }
        for (Node n = type.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element g && XSD.equals(g.getNamespaceURI()) && Set.of("sequence", "all", "choice").contains(g.getLocalName())) particles(sb, g, ns, path, depth);
    }

    /** The elements of a sequence, all or choice, and of the groups inside it. */
    private void particles(StringBuilder sb, Element group, String ns, Set<Element> path, int depth) {
        if ("choice".equals(group.getLocalName())) sb.append("<!-- one of -->");
        for (Node n = group.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !XSD.equals(e.getNamespaceURI())) continue;
            if (e.getLocalName().equals("element")) element(sb, e, false, ns, path, depth + 1);
            else if (Set.of("sequence", "choice", "all").contains(e.getLocalName())) particles(sb, e, ns, path, depth + 1);
        }
    }

    private Schema schemaOfAncestor(Node n) {
        for (Node p = n; p != null; p = p.getParentNode())
            if (p instanceof Element e && XSD.equals(e.getNamespaceURI()) && "schema".equals(e.getLocalName()))
                return new Schema(e.getAttribute("targetNamespace"), "qualified".equals(e.getAttribute("elementFormDefault")));
        return new Schema("", false);
    }

    // ---------- helpers ----------

    static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) out.add(e);
        return out;
    }

    /** "prefix:name" in an attribute, as namespace|name. */
    static String qname(Element ctx, String value) {
        int c = value.indexOf(':');
        String prefix = c < 0 ? null : value.substring(0, c), local = c < 0 ? value : value.substring(c + 1);
        String ns = ctx.lookupNamespaceURI(prefix);
        return (ns == null ? "" : ns) + "|" + local;
    }

    static String resolve(String base, String loc) {
        if (loc.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return loc;
        if (base == null) return loc;
        if (base.matches("^https?://.*")) return java.net.URI.create(base).resolve(loc).toString();
        java.nio.file.Path b = java.nio.file.Path.of(base);
        return (b.getParent() == null ? java.nio.file.Path.of(loc) : b.getParent().resolve(loc)).normalize().toString();
    }
}
