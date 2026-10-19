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

@OoaPackage(id = "ooa.soap", name = "SOAP Web Service", version = "1.0.0", description = "Call SOAP 1.1/1.2 web services and read the response as a dictionary.")
public final class SoapPackage {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Action(id = "send", name = "Send SOAP request", category = "SOAP", timeoutSeconds = 120, capabilities = "network",
            description = "Send a SOAP envelope (or just the body, which is wrapped for you) and return the response.")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "response", type = OoaType.STRING, description = "Response XML.")
    @Output(name = "body", description = "The first element inside the SOAP Body, as a dictionary.")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^https?://", description = "Service endpoint URL.") String url,
            @Input(name = "body", type = OoaType.STRING, description = "Full SOAP envelope, or only the body XML (it is wrapped automatically).") String body,
            @Input(name = "soap_action", type = OoaType.STRING, required = false, description = "SOAPAction header (from the WSDL).") String action,
            @Input(name = "version", type = OoaType.STRING, required = false, defaultValue = "\"1.1\"", enumValues = {"1.1", "1.2"}, description = "SOAP version.") String version,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Extra HTTP headers.") Map<String, Object> headers,
            @Input(name = "basic_user", type = OoaType.STRING, required = false, description = "Basic auth user name.") String user,
            @Input(name = "basic_password", type = OoaType.CREDENTIAL, required = false, description = "Basic auth password from the vault.") Object password,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "Response timeout.") int timeout) {
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
        try { resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException e) { throw new ActionException("SOAP.UNREACHABLE", "Cannot reach " + url + ": " + e.getMessage(), "Check the URL and network access.", true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        Object bodyValue = null;
        if (!resp.body().isBlank()) {
            Document doc = XmlSupport.parse(resp.body(), true);
            Element bodyEl = firstChild(doc.getDocumentElement(), "Body");
            Element first = bodyEl == null ? null : firstElement(bodyEl);
            if (first != null && "Fault".equals(first.getLocalName())) {
                Element fs = firstChild(first, "faultstring");
                if (fs == null) { Element reason = firstChild(first, "Reason"); fs = reason == null ? null : firstChild(reason, "Text"); }
                throw new ActionException("SOAP.FAULT", "SOAP fault: " + (fs != null ? fs.getTextContent().trim() : "unknown"),
                        "Check the request against the service's WSDL.", false, Map.of("status", BigDecimal.valueOf(resp.statusCode())));
            }
            if (first != null) bodyValue = XmlSupport.toValue(first);
        }
        if (resp.statusCode() >= 400) throw new ActionException("SOAP.HTTP_ERROR", "Service returned HTTP " + resp.statusCode(), null, resp.statusCode() >= 500);
        return Out.of("status", BigDecimal.valueOf(resp.statusCode()), "response", resp.body(), "body", bodyValue);
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
