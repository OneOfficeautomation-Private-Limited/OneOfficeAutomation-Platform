package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.w3c.dom.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@OoaPackage(id = "ooa.soap", name = "SOAP Web Service", version = "1.0.0", description = "Read a service's WSDL, call SOAP 1.1/1.2 web services and read the response as a dictionary.")
public final class SoapPackage {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Action(id = "send", name = "Send SOAP request", category = "SOAP", timeoutSeconds = 120, capabilities = "network",
            description = "Send a SOAP envelope (or just the body, which is wrapped for you) and return the response.",
            panel = "url, version, soap_action, body, [Sign in with a user name: basic_user, basic_password], headers, timeout_seconds, [Use a client certificate: client_certificate, client_certificate_password], output_file, xpath | fail_on_error, insecure_tls, [Use a proxy server: proxy, proxy_user, proxy_password]")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "response", type = OoaType.STRING, description = "Response XML.")
    @Output(name = "body", description = "The first element inside the SOAP Body, as a dictionary.")
    @Output(name = "values", type = OoaType.LIST, description = "Text of the nodes selected by xpath; empty without xpath.")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^https?://", description = "Service endpoint URL.") String url,
            @Input(name = "body", type = OoaType.STRING, description = "Full SOAP envelope, or only the body XML (it is wrapped automatically).") String body,
            @Input(name = "soap_action", type = OoaType.STRING, required = false, description = "SOAPAction header (from the WSDL).") String action,
            @Input(name = "version", type = OoaType.STRING, required = false, defaultValue = "\"1.1\"", enumValues = {"1.1", "1.2"}, description = "SOAP version.") String version,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Extra HTTP headers.") Map<String, Object> headers,
            @Input(name = "basic_user", type = OoaType.STRING, required = false, description = "Basic auth user name.") String user,
            @Input(name = "basic_password", type = OoaType.CREDENTIAL, required = false, description = "Basic auth password from the vault.") Object password,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 1, description = "Response timeout.") int timeout,
            @Input(name = "insecure_tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Accept any server certificate (self-signed or expired); for test systems only.") boolean insecure,
            @Input(name = "proxy", type = OoaType.STRING, required = false, example = "proxy.example.com:8080", description = "Proxy server as host:port; the runner's settings when empty.") String proxy,
            @Input(name = "proxy_user", type = OoaType.STRING, required = false, description = "Proxy user name, when the proxy needs a sign-in.") String proxyUser,
            @Input(name = "proxy_password", type = OoaType.CREDENTIAL, required = false, description = "Proxy password from the vault.") Object proxyPassword,
            @Input(name = "client_certificate", type = OoaType.FILE, required = false, description = "Client certificate (.p12 or .pfx) for servers that require mutual TLS.") String clientCert,
            @Input(name = "client_certificate_password", type = OoaType.CREDENTIAL, required = false, description = "Password of the client certificate file.") Object clientCertPassword,
            @Input(name = "xpath", type = OoaType.STRING, required = false, example = "//*[local-name()='Price']", description = "Also return the text of the response nodes this XPath selects, in values.") String xpath,
            @Input(name = "output_file", type = OoaType.FILE, required = false, description = "Also save the response XML to this file.") String outputFile,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step on a SOAP fault or HTTP error; when false the status and response are returned.") boolean failOnError) {
        HttpOptions opts = new HttpOptions(insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, false, true);
        boolean v12 = "1.2".equals(version);
        String ns = v12 ? "http://www.w3.org/2003/05/soap-envelope" : "http://schemas.xmlsoap.org/soap/envelope/";
        String envelope = body.contains("Envelope") ? body
                : "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\"" + ns + "\"><soap:Body>" + body + "</soap:Body></soap:Envelope>";
        XmlSupport.parse(envelope, true);  // fail early on malformed XML
        HttpRequest.Builder b;
        try { b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout)).POST(HttpRequest.BodyPublishers.ofString(envelope)); }
        catch (IllegalArgumentException e) { throw new ActionException("SOAP.BAD_URL", "Invalid URL: " + url); }
        String ct = v12 ? "application/soap+xml; charset=utf-8" + (action != null ? "; action=\"" + action + "\"" : "") : "text/xml; charset=utf-8";
        b.header("Content-Type", ct);
        if (!v12 && action != null) b.header("SOAPAction", "\"" + action + "\"");
        if (headers != null) headers.forEach((k, v) -> b.header(k, String.valueOf(v)));
        if (user != null && password instanceof SecureString pw)
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw.reveal()).getBytes(StandardCharsets.UTF_8)));
        HttpResponse<String> resp;
        try { resp = opts.client(ctx, CLIENT).send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException e) { throw new ActionException("SOAP.UNREACHABLE", "Cannot reach " + url + ": " + e.getMessage(), "Check the URL and network access.", true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        Object bodyValue = null;
        List<Object> values = new ArrayList<>();
        if (outputFile != null && !outputFile.isBlank()) {
            java.nio.file.Path out = ctx.resolvePath(outputFile);
            try {
                if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.writeString(out, resp.body(), StandardCharsets.UTF_8);
            } catch (IOException e) { throw new ActionException("SOAP.FILE_ERROR", "Cannot save the response to " + out + ": " + e.getMessage(), "Check the folder is writable.", true); }
        }
        if (!resp.body().isBlank()) {
            Document doc = XmlSupport.parse(resp.body(), true);
            if (xpath != null && !xpath.isBlank()) values.addAll(xpathTexts(doc, xpath));
            Element bodyEl = firstChild(doc.getDocumentElement(), "Body");
            Element first = bodyEl == null ? null : firstElement(bodyEl);
            if (first != null && "Fault".equals(first.getLocalName()) && failOnError) {
                Element fs = firstChild(first, "faultstring");
                if (fs == null) { Element reason = firstChild(first, "Reason"); fs = reason == null ? null : firstChild(reason, "Text"); }
                throw new ActionException("SOAP.FAULT", "SOAP fault: " + (fs != null ? fs.getTextContent().trim() : "unknown"),
                        "Check the request against the service's WSDL.", false, Map.of("status", BigDecimal.valueOf(resp.statusCode())));
            }
            if (first != null) bodyValue = XmlSupport.toValue(first);
        }
        if (resp.statusCode() >= 400 && failOnError) throw new ActionException("SOAP.HTTP_ERROR", "Service returned HTTP " + resp.statusCode(), null, resp.statusCode() >= 500);
        return Out.of("status", BigDecimal.valueOf(resp.statusCode()), "response", resp.body(), "body", bodyValue, "values", values);
    }

    @Action(id = "read_wsdl", name = "Read WSDL", category = "SOAP", idempotent = true, timeoutSeconds = 120, capabilities = "network",
            description = "Read a service's WSDL (address or file) and list its operations, each with its SOAPAction, endpoint, SOAP version and a request body to fill in for Send SOAP request.",
            panel = "wsdl, [Sign in with a user name: basic_user, basic_password] | timeout_seconds, insecure_tls")
    @Output(name = "operations", type = OoaType.LIST, description = "Operations: name, soap_action, endpoint, version (1.1 or 1.2), style, body_template (? where a value goes).")
    @Output(name = "names", type = OoaType.LIST, description = "The operation names.")
    public Map<String, Object> readWsdl(ActionContext ctx,
            @Input(name = "wsdl", type = OoaType.STRING, example = "https://erp.example.com/OrderService?wsdl", description = "The WSDL address, or a .wsdl file.") String wsdl,
            @Input(name = "basic_user", type = OoaType.STRING, required = false, description = "Basic auth user name, when the WSDL needs a sign-in.") String user,
            @Input(name = "basic_password", type = OoaType.CREDENTIAL, required = false, description = "Basic auth password from the vault.") Object password,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 1, description = "Time to wait for each download.") int timeout,
            @Input(name = "insecure_tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Accept any server certificate; for test systems only.") boolean insecure) {
        if (wsdl == null || wsdl.isBlank()) throw new ActionException("SOAP.BAD_WSDL", "Give the WSDL address or file");
        boolean web = wsdl.strip().matches("^https?://.*");
        String base = web ? wsdl.strip() : ctx.resolvePath(wsdl.strip()).toAbsolutePath().toString();
        HttpOptions opts = new HttpOptions(insecure, null, null, null, null, null, false, true);
        java.util.function.Function<String, Document> load = at -> {
            String text;
            if (at.matches("^https?://.*")) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(at)).timeout(Duration.ofSeconds(timeout)).GET();
                if (user != null && password instanceof SecureString pw)
                    b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw.reveal()).getBytes(StandardCharsets.UTF_8)));
                try {
                    HttpResponse<String> r = opts.client(ctx, CLIENT).send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() >= 400) throw new ActionException("SOAP.HTTP_ERROR", at + " answered HTTP " + r.statusCode(), r.statusCode() == 401 ? "Give basic_user and basic_password." : null, r.statusCode() >= 500);
                    text = r.body();
                } catch (IOException e) { throw new ActionException("SOAP.UNREACHABLE", "Cannot reach " + at + ": " + e.getMessage(), "Check the address and network access.", true);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
            } else {
                try { text = java.nio.file.Files.readString(java.nio.file.Path.of(at), StandardCharsets.UTF_8); }
                catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + at + ": " + e.getMessage(), "Check the path."); }
            }
            return XmlSupport.parse(text, true);
        };
        List<Object> ops = new ArrayList<>(), names = new ArrayList<>();
        for (Wsdl.Operation o : Wsdl.read(load.apply(base), base, load)) { ops.add(o.toMap()); if (!names.contains(o.name())) names.add(o.name()); }
        return Map.of("operations", ops, "names", names);
    }

    /** Text of every node the XPath selects (namespaces are ignored by using local-name() in the expression). */
    static List<Object> xpathTexts(Document doc, String expression) {
        try {
            javax.xml.xpath.XPath xp = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xp.evaluate(expression, doc, javax.xml.xpath.XPathConstants.NODESET);
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) out.add(nodes.item(i).getTextContent().strip());
            return out;
        } catch (javax.xml.xpath.XPathExpressionException e) {
            throw new ActionException("SOAP.BAD_XPATH", "Invalid XPath '" + expression + "': " + e.getMessage(), "Use e.g. //*[local-name()='Price'].");
        }
    }

    private static Element firstChild(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && localName.equals(e.getLocalName())) return e;
        return null;
    }

    private static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) return e;
        return null;
    }
}
