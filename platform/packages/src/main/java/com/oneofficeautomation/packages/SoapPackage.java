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
            panel = "url, version, soap_action, body, (Sign in: No sign-in = / User name and password = basic_user, basic_password / Bearer token = bearer_token / OAuth 2.0 = oauth_token_url, oauth_client_id, oauth_client_secret, oauth_refresh_token, oauth_scope, oauth_audience, oauth_resource, oauth_client_auth / OAuth 1.0a = oauth1_consumer_key, oauth1_consumer_secret, oauth1_token, oauth1_token_secret, oauth1_signature_method / Windows user name and password, NTLM = ntlm_user, ntlm_domain, ntlm_password), headers, timeout_seconds, [Use a client certificate: client_certificate, client_certificate_password], output_file, xpath | fail_on_error, insecure_tls, [Use a proxy server: proxy, proxy_user, proxy_password], windows_auth, base_url")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "response", type = OoaType.STRING, description = "Response XML.")
    @Output(name = "body", description = "The first element inside the SOAP Body, as a dictionary.")
    @Output(name = "values", type = OoaType.LIST, description = "Text of the nodes selected by xpath; empty without xpath.")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^(https?://|/|\\?|\\$)", description = "Service endpoint URL, or a path when base_url (or a connection) gives the address.") String url,
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
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step on a SOAP fault or HTTP error; when false the status and response are returned.") boolean failOnError,
            @Input(name = "bearer_token", type = OoaType.CREDENTIAL, required = false, description = "Bearer token from the vault.") Object bearer,
            @Input(name = "windows_auth", type = OoaType.STRING, required = false, enumValues = {"current_user", "user_password"},
                    description = "Windows sign-in (NTLM or Kerberos) for intranet sites, SharePoint on premises, IIS and Exchange: current_user signs in as the runner's Windows account (Windows runners); user_password uses basic_user (DOMAIN\\user) and basic_password.") String windowsAuth,
            @Input(name = "oauth_token_url", type = OoaType.STRING, required = false, pattern = "^https?://", example = "https://login.example.com/oauth2/token",
                    description = "OAuth 2.0 token endpoint; the request is sent with a token fetched there (kept for the run and renewed before it expires).") String oauthTokenUrl,
            @Input(name = "oauth_client_id", type = OoaType.STRING, required = false, description = "OAuth 2.0 client (application) id.") String oauthClientId,
            @Input(name = "oauth_client_secret", type = OoaType.CREDENTIAL, required = false,
                    description = "OAuth 2.0 client secret from the vault; needed for client credentials, optional with a refresh token.") Object oauthClientSecret,
            @Input(name = "oauth_refresh_token", type = OoaType.CREDENTIAL, required = false,
                    description = "Refresh token from the vault: signs in with the refresh-token grant instead of client credentials. A new refresh token from the server is used for the rest of the run and returned as refresh_token.") Object oauthRefreshToken,
            @Input(name = "oauth_scope", type = OoaType.STRING, required = false, example = "api://orders/.default", description = "Scopes to ask for, separated by spaces.") String oauthScope,
            @Input(name = "oauth_audience", type = OoaType.STRING, required = false, description = "Audience sent to the token endpoint (some providers, e.g. Auth0, need it).") String oauthAudience,
            @Input(name = "oauth_resource", type = OoaType.STRING, required = false, description = "Resource sent to the token endpoint (e.g. Microsoft Entra v1 and AD FS).") String oauthResource,
            @Input(name = "oauth_client_auth", type = OoaType.STRING, required = false, defaultValue = "\"basic\"", enumValues = {"basic", "body"},
                    description = "How the client id and secret reach the token endpoint: basic (HTTP Basic header, the OAuth standard) or body (form fields client_id and client_secret).") String oauthClientAuth,
            @Input(name = "oauth1_consumer_key", label = "OAuth 1.0a consumer key", type = OoaType.STRING, required = false, description = "OAuth 1.0a consumer key: the request is signed (RFC 5849).") String oauth1ConsumerKey,
            @Input(name = "oauth1_consumer_secret", label = "OAuth 1.0a consumer secret", type = OoaType.CREDENTIAL, required = false, description = "OAuth 1.0a consumer secret from the vault.") Object oauth1ConsumerSecret,
            @Input(name = "oauth1_token", label = "OAuth 1.0a token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 1.0a access token from the vault; empty for a consumer-only (two-legged) request.") Object oauth1Token,
            @Input(name = "oauth1_token_secret", label = "OAuth 1.0a token secret", type = OoaType.CREDENTIAL, required = false, description = "OAuth 1.0a token secret from the vault.") Object oauth1TokenSecret,
            @Input(name = "oauth1_signature_method", label = "OAuth 1.0a signature method", type = OoaType.STRING, required = false, defaultValue = "\"HMAC-SHA1\"", enumValues = {"HMAC-SHA1", "HMAC-SHA256"},
                    description = "OAuth 1.0a signature method.") String oauth1Method,
            @Input(name = "ntlm_user", label = "Windows user name", type = OoaType.STRING, required = false, description = "Windows (NTLM) user name for intranet sites, IIS, SharePoint on premises and Exchange.") String ntlmUser,
            @Input(name = "ntlm_domain", label = "Windows domain", type = OoaType.STRING, required = false, example = "CONTOSO", description = "Windows domain of the NTLM user; empty when the user name already has it (DOMAIN\\user or user@domain).") String ntlmDomain,
            @Input(name = "ntlm_password", label = "Windows password", type = OoaType.CREDENTIAL, required = false, description = "Windows password of the NTLM user, from the vault.") Object ntlmPassword,
            @Input(name = "base_url", type = OoaType.STRING, required = false, example = "https://api.example.com/v2",
                    description = "Address the URL is relative to, so the URL can be a path such as /invoices/42; a saved connection usually gives it.") String baseUrl) {
        url = WebSignIn.address("SOAP", baseUrl, url);
        WebSignIn signIn = WebSignIn.of("SOAP", user, password, bearer, windowsAuth, oauthTokenUrl, oauthClientId, oauthClientSecret, oauthRefreshToken,
                oauthScope, oauthAudience, oauthResource, oauthClientAuth, oauth1ConsumerKey, oauth1ConsumerSecret, oauth1Token, oauth1TokenSecret, oauth1Method,
                ntlmUser, ntlmDomain, ntlmPassword);
        HttpOptions opts = new HttpOptions(insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, false, true);
        boolean v12 = "1.2".equals(version);
        String ns = v12 ? "http://www.w3.org/2003/05/soap-envelope" : "http://schemas.xmlsoap.org/soap/envelope/";
        String envelope = body.contains("Envelope") ? body
                : "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\"" + ns + "\"><soap:Body>" + body + "</soap:Body></soap:Envelope>";
        XmlSupport.parse(envelope, true);  // fail early on malformed XML
        HttpRequest.Builder b;
        try { b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout)).POST(HttpRequest.BodyPublishers.ofString(envelope)); }
        catch (IllegalArgumentException e) { throw new ActionException("SOAP.BAD_URL", "Invalid URL: " + url, "Give the address starting with https:// or http://."); }
        String ct = v12 ? "application/soap+xml; charset=utf-8" + (action != null ? "; action=\"" + action + "\"" : "") : "text/xml; charset=utf-8";
        b.header("Content-Type", ct);
        if (!v12 && action != null) b.header("SOAPAction", "\"" + action + "\"");
        if (headers != null) headers.forEach((k, v) -> b.header(k, HttpJson.sendable(v)));
        signIn.apply(b);
        int status;
        String text;
        if (signIn.windows()) {
            // Windows sign-in (NTLM or Kerberos) sends the envelope itself
            Map<String, String> hs = new LinkedHashMap<>();
            b.copy().build().headers().map().forEach((k, v) -> hs.put(k, String.join(", ", v)));
            IntegratedAuthHttp.Response r = IntegratedAuthHttp.send(ctx, signIn.windowsAuth, "POST", url, hs, envelope.getBytes(StandardCharsets.UTF_8),
                    signIn.basicUser, signIn.basicPassword, timeout, true, null);
            status = r.status(); text = r.text();
        } else if (signIn.o2 != null || signIn.o1 != null) {
            HttpResponse<String> r = signIn.send(ctx, opts.client(ctx, CLIENT), b, HttpResponse.BodyHandlers.ofString(), "POST", url, null, url, timeout);
            status = r.statusCode(); text = r.body();
        } else {
            HttpResponse<String> r = plainSend(ctx, opts, b.build(), url);
            status = r.statusCode(); text = r.body();
        }
        return answer(ctx, url, status, text, failOnError, xpath, outputFile);
    }

    /** Earlier signature, kept for callers written before the other sign-ins and base_url (R2). */
    public Map<String, Object> send(ActionContext ctx, String url, String body, String action, String version, Map<String, Object> headers, String user, Object password, int timeout, boolean insecure, String proxy, String proxyUser, Object proxyPassword, String clientCert, Object clientCertPassword, String xpath, String outputFile, boolean failOnError) {
        return send(ctx, url, body, action, version, headers, user, password, timeout, insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, xpath, outputFile, failOnError, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Sends as before (honouring HTTP 429 with Retry-After), for every sign-in but Windows and OAuth. */
    private static HttpResponse<String> plainSend(ActionContext ctx, HttpOptions opts, HttpRequest request, String url) {
        HttpResponse<String> resp;
        for (int attempt = 0; ; attempt++) {
            try { resp = opts.client(ctx, CLIENT).send(request, HttpResponse.BodyHandlers.ofString()); }
            catch (IOException e) { throw new ActionException("SOAP.UNREACHABLE", "Cannot reach " + url + ": " + NetErrors.reason(e, null), "Check the URL and network access.", true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted", "The run was stopped before this step finished. Run the agent again when it should go on."); }
            long wait = resp.statusCode() == 429 && attempt < HttpJson.MAX_RATE_RETRIES ? HttpJson.retryAfterMillis(resp.headers().firstValue("Retry-After").orElse(null), attempt) : -1;
            if (wait < 0) break;
            ctx.warn(HttpJson.host(url) + " is rate limiting (HTTP 429); trying again in " + Math.max(1, Math.round(wait / 1000.0)) + " s");
            HttpJson.pause(ctx, wait);
        }
        return resp;
    }

    /** The answer, the same whichever way it was sent: errors, faults, the body, the XPath values and the saved file. */
    private static Map<String, Object> answer(ActionContext ctx, String url, int status, String text, boolean failOnError, String xpath, String outputFile) {
        if (status >= 400 && failOnError && !text.stripLeading().startsWith("<"))
            throw new ActionException("SOAP.HTTP_ERROR", "Service returned HTTP " + status + (text.isBlank() ? "" : ": " + HttpJson.snippet(text)), "Check the service's address and the sign-in in the connection; the message has the service's answer.", status >= 500 || status == 429);
        Object bodyValue = null;
        List<Object> values = new ArrayList<>();
        if (outputFile != null && !outputFile.isBlank()) {
            java.nio.file.Path out = ctx.resolvePath(outputFile);
            try {
                if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.writeString(out, text, StandardCharsets.UTF_8);
            } catch (IOException e) { throw new ActionException("SOAP.FILE_ERROR", "Cannot save the response to " + out + ": " + e.getMessage(), "Check the folder is writable.", true); }
        }
        if (!text.isBlank()) {
            Document doc;
            try { doc = XmlSupport.parse(text, true); }
            catch (ActionException e) {
                if (status >= 400 && failOnError) throw new ActionException("SOAP.HTTP_ERROR", "Service returned HTTP " + status + ": " + HttpJson.snippet(text), "Check the service's address and the sign-in in the connection; the message has the service's answer.", status >= 500 || status == 429);
                throw e;
            }
            // a SOAP answer is an Envelope; an HTML proxy or sign-in page used to "succeed" with an empty body
            if (!"Envelope".equals(doc.getDocumentElement().getLocalName()) && failOnError)
                throw new ActionException("SOAP.BAD_RESPONSE", "The answer is not a SOAP envelope (a <" + doc.getDocumentElement().getNodeName() + "> document, often a proxy or sign-in page): " + HttpJson.snippet(text),
                        "Check the endpoint URL and the proxy between the runner and the service.", false, Map.of("status", BigDecimal.valueOf(status)));
            if (xpath != null && !xpath.isBlank()) values.addAll(xpathTexts(doc, xpath));
            Element bodyEl = firstChild(doc.getDocumentElement(), "Body");
            Element first = bodyEl == null ? null : firstElement(bodyEl);
            if (first != null && "Fault".equals(first.getLocalName()) && failOnError) {
                Element fs = firstChild(first, "faultstring");
                if (fs == null) { Element reason = firstChild(first, "Reason"); fs = reason == null ? null : firstChild(reason, "Text"); }
                throw new ActionException("SOAP.FAULT", "SOAP fault: " + (fs != null ? fs.getTextContent().trim() : "unknown"),
                        "Check the request against the service's WSDL.", false, Map.of("status", BigDecimal.valueOf(status)));
            }
            if (first != null) bodyValue = XmlSupport.toValue(first);
        }
        if (status >= 400 && failOnError) throw new ActionException("SOAP.HTTP_ERROR", "Service returned HTTP " + status, "Check the service's address and the sign-in in the connection; the message has the service's answer.", status >= 500);
        return Out.of("status", BigDecimal.valueOf(status), "response", text, "body", bodyValue, "values", values);
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
        if (wsdl == null || wsdl.isBlank()) throw new ActionException("SOAP.BAD_WSDL", "Give the WSDL address or file", "Fill in either WSDL address or WSDL file.");
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
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted", "The run was stopped before this step finished. Run the agent again when it should go on."); }
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
