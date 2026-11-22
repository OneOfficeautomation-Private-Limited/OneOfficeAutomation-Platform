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
            description = "Send a SOAP envelope (or just the body, which is wrapped for you), or build the request from the service's WSDL, and return the response.",
            panel = "url, version, (Body: Write the XML = body, replace_in_body / Build from WSDL = wsdl, service, port, operation, parameters), (Sign in:No sign-in = / User name and password = basic_user, basic_password / Bearer token = bearer_token / OAuth 2.0 = oauth_token_url, oauth_client_id, oauth_client_secret, oauth_refresh_token, oauth_scope, oauth_audience, oauth_resource, oauth_client_auth / OAuth 1.0a = oauth1_consumer_key, oauth1_consumer_secret, oauth1_token, oauth1_token_secret, oauth1_signature_method / Windows user name and password, NTLM = ntlm_user, ntlm_domain, ntlm_password), headers, timeout_seconds, [Use a client certificate: client_certificate, client_certificate_password], output_file, xpath, xpath_output, delimiter, custom_delimiter | soap_action, namespace, prefix, qualify_elements, fail_on_error, insecure_tls, trust_certificate, [Use a proxy server: proxy, proxy_user, proxy_password], windows_auth, base_url")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "response", type = OoaType.STRING, description = "Response XML.")
    @Output(name = "body", description = "The first element inside the SOAP Body, as a dictionary.")
    @Output(name = "values", type = OoaType.LIST, description = "Text of the nodes selected by xpath (their inner or outer XML with those xpath_output choices); empty without xpath.")
    @Output(name = "text", type = OoaType.STRING, description = "With xpath_output joined, inner_xml or outer_xml: the selected nodes as one text; empty otherwise (G6).")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, required = false, pattern = "^(https?://|/|\\?|\\$)", description = "Service endpoint URL, or a path when base_url (or a connection) gives the address. When the body is built from a WSDL, empty uses the address the WSDL gives.") String url,
            @Input(name = "body", type = OoaType.STRING, required = false, acceptsCredential = true, description = "Full SOAP envelope, or only the body XML (it is wrapped automatically); it may also come from the vault as a whole (G6).") String body,
            @Input(name = "soap_action", type = OoaType.STRING, required = false, description = "SOAPAction header (from the WSDL); when the body is built from a WSDL, empty uses the operation's own.") String action,
            @Input(name = "version", type = OoaType.STRING, required = false, defaultValue = "\"1.1\"", enumValues = {"1.1", "1.2"}, description = "SOAP version; when the body is built from a WSDL, the port's own version is used.") String version,
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
                    description = "Address the URL is relative to, so the URL can be a path such as /invoices/42; a saved connection usually gives it.") String baseUrl,
            @Input(name = "wsdl", type = OoaType.STRING, required = false, example = "https://erp.example.com/OrderService?wsdl",
                    description = "Build the request from this WSDL (address or .wsdl file) instead of writing the XML (G6).") String wsdl,
            @Input(name = "service", type = OoaType.STRING, required = false, visibleWhen = "wsdl!=", description = "Service in the WSDL; empty when it has only one or any will do (G6).") String service,
            @Input(name = "port", type = OoaType.STRING, required = false, visibleWhen = "wsdl!=", description = "Port of the service (it fixes the address and SOAP version); empty for the first that offers the operation (G6).") String port,
            @Input(name = "operation", type = OoaType.STRING, required = false, visibleWhen = "wsdl!=", example = "GetOrder", description = "Operation to call, as Read WSDL lists it (G6).") String operation,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, visibleWhen = "wsdl!=",
                    description = "Values of the request's fields: field name (or a path such as Customer/Name) to value; a value may be a Credential, and a list repeats the field. Optional fields left out are dropped (G6).") Map<String, Object> parameters,
            @Input(name = "namespace", type = OoaType.STRING, required = false, visibleWhen = "wsdl!=", description = "Namespace for the operation's element in place of the WSDL's (G6).") String namespace,
            @Input(name = "prefix", type = OoaType.STRING, required = false, defaultValue = "\"ns\"", visibleWhen = "wsdl!=", description = "Prefix written for that namespace (G6).") String prefix,
            @Input(name = "qualify_elements", type = OoaType.BOOLEAN, required = false, visibleWhen = "wsdl!=",
                    description = "Whether the fields inside the operation carry the namespace too; empty follows the WSDL's schema (G6).") Boolean qualifyElements,
            @Input(name = "replace_in_body", type = OoaType.DICTIONARY, required = false,
                    description = "Placeholders in the body filled just before sending: name to a Credential (or text); every {{name}} in the body becomes the value, which stays masked in the log (G6).") Map<String, Object> replaceInBody,
            @Input(name = "xpath_output", type = OoaType.STRING, required = false, defaultValue = "\"values\"", enumValues = {"values", "joined", "inner_xml", "outer_xml"}, visibleWhen = "xpath!=",
                    description = "What xpath returns: values (a list of the nodes' text), joined (their text as one text, separated by delimiter), inner_xml (the XML inside the nodes) or outer_xml (the nodes with their own tags) (G6).") String xpathOutput,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\"pipe\"", enumValues = {"none", "pipe", "semicolon", "custom"}, visibleWhen = "xpath!=;xpath_output=joined",
                    description = "What separates the joined values: none, pipe (|), semicolon (;) or custom (G6).") String delimiter,
            @Input(name = "custom_delimiter", type = OoaType.STRING, required = false, visibleWhen = "xpath!=;xpath_output=joined;delimiter=custom", description = "Text that separates the joined values (G6).") String customDelimiter,
            @Input(name = "trust_certificate", type = OoaType.FILE, required = false,
                    description = "Certificate of a private certificate authority (.cer, .crt or .pem) to trust as well as the usual ones, so a company server's certificate is still checked without insecure_tls (G6).") String trustCert) {
        WebSignIn signIn = WebSignIn.of("SOAP", user, password, bearer, windowsAuth, oauthTokenUrl, oauthClientId, oauthClientSecret, oauthRefreshToken,
                oauthScope, oauthAudience, oauthResource, oauthClientAuth, oauth1ConsumerKey, oauth1ConsumerSecret, oauth1Token, oauth1TokenSecret, oauth1Method,
                ntlmUser, ntlmDomain, ntlmPassword);
        HttpOptions opts = new HttpOptions(insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, false, true);
        if (signIn.windows() && given(trustCert))
            throw new ActionException("SOAP.BAD_AUTH", "windows_auth cannot be combined with trust_certificate", "Use Windows sign-in alone (the runner's certificate settings apply).");
        HttpClient client = HttpJson.clientTrusting(ctx, opts, CLIENT, trustCert, "SOAP");
        if (given(wsdl)) {
            // G6: the body is written from the WSDL's description of the operation
            if (given(body)) throw new ActionException("SOAP.BAD_BODY", "Both body and wsdl were given", "Write the XML in body, or build it from the WSDL; not both.");
            if (!given(operation)) throw new ActionException("SOAP.BAD_WSDL", "Building the body from a WSDL needs the operation", "Give the operation's name, as Read WSDL lists it.");
            Built built = buildFromWsdl(ctx, client, wsdl, service, port, operation, parameters, namespace, prefix, qualifyElements, version,
                    signIn.windows() ? null : signIn.basicUser, signIn.windows() ? null : signIn.basicPassword, timeout);
            body = built.body();
            if (!given(action)) action = built.soapAction();
            version = built.version();
            if (given(url) || given(baseUrl)) url = WebSignIn.address("SOAP", baseUrl, url);
            else if (built.endpoint().isBlank()) throw new ActionException("SOAP.BAD_URL", "The WSDL gives no address for operation '" + operation.strip() + "'", "Give the service's address in url.");
            else url = built.endpoint();
        } else {
            if (!given(body)) throw new ActionException("SOAP.BAD_BODY", "There is no body to send", "Write the SOAP body (or the whole envelope), or build it from the service's WSDL.");
            url = WebSignIn.address("SOAP", baseUrl, url);
        }
        if (replaceInBody != null && !replaceInBody.isEmpty()) body = HttpJson.replacePlaceholders(ctx, body, replaceInBody);
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
            HttpResponse<String> r = signIn.send(ctx, client, b, HttpResponse.BodyHandlers.ofString(), "POST", url, null, url, timeout);
            status = r.statusCode(); text = r.body();
        } else {
            HttpResponse<String> r = plainSend(ctx, client, b.build(), url);
            status = r.statusCode(); text = r.body();
        }
        return answer(ctx, url, status, text, failOnError, xpath, outputFile, xpathOutput, delimiter, customDelimiter);
    }

    /** Signature before the G6 rows (WSDL body, replace_in_body, xpath_output, trust_certificate), kept for callers written before them. */
    public Map<String, Object> send(ActionContext ctx, String url, String body, String action, String version, Map<String, Object> headers, String user, Object password, int timeout, boolean insecure, String proxy, String proxyUser, Object proxyPassword, String clientCert, Object clientCertPassword, String xpath, String outputFile, boolean failOnError, Object bearer, String windowsAuth, String oauthTokenUrl, String oauthClientId, Object oauthClientSecret, Object oauthRefreshToken, String oauthScope, String oauthAudience, String oauthResource, String oauthClientAuth, String oauth1ConsumerKey, Object oauth1ConsumerSecret, Object oauth1Token, Object oauth1TokenSecret, String oauth1Method, String ntlmUser, String ntlmDomain, Object ntlmPassword, String baseUrl) {
        return send(ctx, url, body, action, version, headers, user, password, timeout, insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, xpath, outputFile, failOnError, bearer, windowsAuth, oauthTokenUrl, oauthClientId, oauthClientSecret, oauthRefreshToken, oauthScope, oauthAudience, oauthResource, oauthClientAuth, oauth1ConsumerKey, oauth1ConsumerSecret, oauth1Token, oauth1TokenSecret, oauth1Method, ntlmUser, ntlmDomain, ntlmPassword, baseUrl,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Earlier signature, kept for callers written before the other sign-ins and base_url (R2). */
    public Map<String, Object> send(ActionContext ctx, String url, String body, String action, String version, Map<String, Object> headers, String user, Object password, int timeout, boolean insecure, String proxy, String proxyUser, Object proxyPassword, String clientCert, Object clientCertPassword, String xpath, String outputFile, boolean failOnError) {
        return send(ctx, url, body, action, version, headers, user, password, timeout, insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, xpath, outputFile, failOnError, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static boolean given(Object v) { return v != null && !(v instanceof String s && s.isBlank()) && !(v instanceof Map<?, ?> m && m.isEmpty()); }

    /** Sends as before (honouring HTTP 429 with Retry-After), for every sign-in but Windows and OAuth. */
    private static HttpResponse<String> plainSend(ActionContext ctx, HttpClient client, HttpRequest request, String url) {
        HttpResponse<String> resp;
        for (int attempt = 0; ; attempt++) {
            try { resp = client.send(request, HttpResponse.BodyHandlers.ofString()); }
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
    private static Map<String, Object> answer(ActionContext ctx, String url, int status, String text, boolean failOnError, String xpath, String outputFile,
                                              String xpathOutput, String delimiter, String customDelimiter) {
        String mode = given(xpathOutput) ? xpathOutput.strip() : "values";
        if (!List.of("values", "joined", "inner_xml", "outer_xml").contains(mode))
            throw new ActionException("SOAP.BAD_OUTPUT", "Unknown xpath_output '" + xpathOutput + "'", "Use values, joined, inner_xml or outer_xml.");
        String sep = switch (given(delimiter) ? delimiter.strip() : "pipe") {
            case "none" -> "";
            case "pipe" -> "|";
            case "semicolon" -> ";";
            case "custom" -> customDelimiter == null ? "" : customDelimiter;
            default -> throw new ActionException("SOAP.BAD_OUTPUT", "Unknown delimiter '" + delimiter + "'", "Use none, pipe, semicolon or custom.");
        };
        Map<String, Object> out = new LinkedHashMap<>(answer(ctx, url, status, text, failOnError, xpath, outputFile, mode));
        List<?> values = (List<?>) out.get("values");
        out.put("text", mode.equals("values") ? "" : String.join(mode.equals("joined") ? sep : "", values.stream().map(String::valueOf).toList()));
        return out;
    }

    private static Map<String, Object> answer(ActionContext ctx, String url, int status, String text, boolean failOnError, String xpath, String outputFile, String mode) {
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
            if (xpath != null && !xpath.isBlank()) values.addAll(mode.endsWith("_xml") ? xpathXml(doc, xpath, mode.equals("outer_xml")) : xpathTexts(doc, xpath));
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
        HttpOptions opts = new HttpOptions(insecure, null, null, null, null, null, false, true);
        String base = wsdlBase(ctx, wsdl);
        java.util.function.Function<String, Document> load = wsdlLoader(ctx, opts.client(ctx, CLIENT), user, password, timeout);
        List<Object> ops = new ArrayList<>(), names = new ArrayList<>();
        for (Wsdl.Operation o : Wsdl.read(load.apply(base), base, load)) { ops.add(o.toMap()); if (!names.contains(o.name())) names.add(o.name()); }
        return Map.of("operations", ops, "names", names);
    }

    /** Where a WSDL is read from: its address, or the full path of a .wsdl file. */
    private static String wsdlBase(ActionContext ctx, String wsdl) {
        if (wsdl == null || wsdl.isBlank()) throw new ActionException("SOAP.BAD_WSDL", "Give the WSDL address or file", "Fill in either WSDL address or WSDL file.");
        boolean web = wsdl.strip().matches("^https?://.*");
        return web ? wsdl.strip() : ctx.resolvePath(wsdl.strip()).toAbsolutePath().toString();
    }

    /** Reads a WSDL or schema by its address (with a basic sign-in when given) or file path. */
    private static java.util.function.Function<String, Document> wsdlLoader(ActionContext ctx, HttpClient client, String user, Object password, int timeout) {
        return at -> {
            String text;
            if (at.matches("^https?://.*")) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(at)).timeout(Duration.ofSeconds(timeout)).GET();
                if (user != null && password instanceof SecureString pw)
                    b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw.reveal()).getBytes(StandardCharsets.UTF_8)));
                try {
                    HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
    }

    // ---------- G6: the request body built from the WSDL ----------

    /** What the WSDL gives for one operation: its address, SOAPAction, SOAP version and the filled-in body. */
    record Built(String endpoint, String soapAction, String version, String body) {}

    static Built buildFromWsdl(ActionContext ctx, HttpClient client, String wsdl, String service, String port, String operation, Map<String, Object> parameters,
                               String namespace, String prefix, Boolean qualify, String preferVersion, String user, Object password, int timeout) {
        String base = wsdlBase(ctx, wsdl);
        java.util.function.Function<String, Document> load = wsdlLoader(ctx, client, user, password, timeout);
        Document doc = load.apply(base);
        List<Wsdl.Operation> ops = Wsdl.read(doc, base, load);
        String name = operation.strip();
        List<Wsdl.Operation> named = ops.stream().filter(o -> o.name().equals(name)).toList();
        if (named.isEmpty())
            throw new ActionException("SOAP.UNKNOWN_OPERATION", "The WSDL has no operation '" + name + "'",
                    "Use one of: " + String.join(", ", new LinkedHashSet<>(ops.stream().map(Wsdl.Operation::name).toList())) + ".");
        // service and port narrow the choice to that port's address and SOAP version
        Set<String> allowed = portKeys(doc, service, port);
        List<Wsdl.Operation> found = allowed == null ? named : named.stream().filter(o -> allowed.contains(o.endpoint() + "|" + o.version())).toList();
        if (found.isEmpty())
            throw new ActionException("SOAP.UNKNOWN_PORT", "Operation '" + name + "' is not offered by the given service and port",
                    "Check service and port against the WSDL (Read WSDL lists each operation's address), or leave them empty.");
        Wsdl.Operation op = found.stream().filter(o -> o.version().equals(preferVersion)).findFirst().orElse(found.get(0));
        ctx.info("Building the " + name + " request from the WSDL (SOAP " + op.version() + ")");
        return new Built(op.endpoint(), op.soapAction(), op.version(), fillTemplate(op.bodyTemplate(), parameters, namespace, prefix, qualify));
    }

    /** "address|version" of the ports the service and port names pick; null when neither is given. */
    private static Set<String> portKeys(Document doc, String service, String port) {
        if (!given(service) && !given(port)) return null;
        Element defs = doc.getDocumentElement();
        Set<String> keys = new HashSet<>();
        List<String> services = new ArrayList<>(), ports = new ArrayList<>();
        for (Element s : Wsdl.children(defs, Wsdl.WSDL, "service")) {
            services.add(s.getAttribute("name"));
            if (given(service) && !s.getAttribute("name").equals(service.strip())) continue;
            for (Element p : Wsdl.children(s, Wsdl.WSDL, "port")) {
                ports.add(p.getAttribute("name"));
                if (given(port) && !p.getAttribute("name").equals(port.strip())) continue;
                for (Element a : Wsdl.children(p, Wsdl.SOAP11, "address")) keys.add(a.getAttribute("location") + "|1.1");
                for (Element a : Wsdl.children(p, Wsdl.SOAP12, "address")) keys.add(a.getAttribute("location") + "|1.2");
            }
        }
        if (given(service) && !services.contains(service.strip()))
            throw new ActionException("SOAP.UNKNOWN_SERVICE", "The WSDL has no service '" + service.strip() + "'", "Use one of: " + String.join(", ", services) + ", or leave service empty.");
        if (given(port) && !ports.contains(port.strip()))
            throw new ActionException("SOAP.UNKNOWN_PORT", "The WSDL has no port '" + port.strip() + "'" + (given(service) ? " in service '" + service.strip() + "'" : ""),
                    "Use one of: " + String.join(", ", ports) + ", or leave port empty.");
        return keys;
    }

    /**
     * The operation's body template (Read WSDL's body_template, ? where a value goes) with the parameters filled in.
     * A field is named by its own name or by a path ending in it (Customer/Name); a list value repeats the field.
     * Optional fields and optional groups with nothing filled in are left out; other empty fields are sent empty.
     * namespace and prefix replace the operation's namespace and its prefix; qualify_elements decides whether the
     * fields inside carry it (empty: as the schema says).
     */
    static String fillTemplate(String template, Map<String, Object> parameters, String namespace, String prefix, Boolean qualify) {
        String p = given(prefix) ? prefix.strip() : "ns";
        if (!p.matches("[A-Za-z_][A-Za-z0-9_.-]*") || p.toLowerCase(Locale.ROOT).startsWith("xml"))
            throw new ActionException("SOAP.BAD_PREFIX", "'" + prefix + "' cannot be a namespace prefix", "Use letters and digits, starting with a letter, e.g. ns or tem.");
        Document d = XmlSupport.parse("<w>" + template + "</w>", true);
        BodyWriter w = new BodyWriter(parameters == null ? Map.of() : parameters, p, qualify);
        List<Element> tops = new ArrayList<>();
        for (Node n = d.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) tops.add(e);
        w.topNs = tops.isEmpty() || tops.get(0).getNamespaceURI() == null ? "" : tops.get(0).getNamespaceURI();
        w.targetNs = given(namespace) ? namespace.strip() : w.topNs;
        StringBuilder sb = new StringBuilder();
        for (Element top : tops) { String s = w.write(top, true, "", Set.of(), false); if (s != null) sb.append(s); }
        List<String> unknown = new ArrayList<>();
        for (String k : w.params.keySet()) if (!w.used.contains(k)) unknown.add(k);
        if (!unknown.isEmpty())
            throw new ActionException("SOAP.UNKNOWN_PARAMETER", "The operation has no field " + String.join(", ", unknown),
                    "Its fields are: " + String.join(", ", w.leaves) + " (a path such as Customer/Name picks one of several fields with the same name).");
        return sb.toString();
    }

    private static final class BodyWriter {
        final Map<String, Object> params;
        final String prefix;
        final Boolean qualify;
        final Set<String> used = new HashSet<>();
        final Set<String> leaves = new LinkedHashSet<>();
        final Map<String, String> prefixes = new HashMap<>();
        String topNs = "", targetNs = "";

        BodyWriter(Map<String, Object> params, String prefix, Boolean qualify) {
            this.params = new LinkedHashMap<>();
            params.forEach((k, v) -> this.params.put(k.strip(), v));
            this.prefix = prefix; this.qualify = qualify;
        }

        private String mapped(String ns) { return ns == null || ns.isEmpty() ? null : ns.equals(topNs) ? targetNs : ns; }

        private String prefixFor(String ns) {
            if (ns.equals(targetNs)) return prefix;
            return prefixes.computeIfAbsent(ns, k -> prefix + (prefixes.size() + 1));
        }

        /** The parameter that fills the field at this path: the longest name or path that ends the path. */
        private String key(String path) {
            String best = null;
            for (String k : params.keySet())
                if ((path.equals(k) || path.endsWith("/" + k)) && (best == null || k.length() > best.length())) best = k;
            return best;
        }

        String write(Element el, boolean top, String path, Set<String> declared, boolean optional) {
            String local = el.getLocalName();
            String ns = top ? mapped(el.getNamespaceURI())
                    : qualify == null ? mapped(el.getNamespaceURI())
                    : qualify ? (el.getNamespaceURI() == null || el.getNamespaceURI().isEmpty() ? (targetNs.isEmpty() ? null : targetNs) : mapped(el.getNamespaceURI()))
                    : null;
            String tag = ns == null ? local : prefixFor(ns) + ":" + local;
            String decl = "";
            Set<String> scope = declared;
            if (ns != null && !declared.contains(ns)) {
                decl = " xmlns:" + prefixFor(ns) + "=\"" + esc(ns) + "\"";
                scope = new HashSet<>(declared);
                scope.add(ns);
            }
            String here = path.isEmpty() ? local : path + "/" + local;
            List<Element> kids = new ArrayList<>();
            for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) kids.add(e);
            if (kids.isEmpty()) {
                leaves.add(here);
                String k = key(here);
                if (k == null) return optional ? null : "<" + tag + decl + "/>";
                used.add(k);
                Object v = params.get(k);
                List<?> items = v instanceof List<?> l ? l : Collections.singletonList(v);
                StringBuilder sb = new StringBuilder();
                for (Object item : items)
                    sb.append('<').append(tag).append(decl).append('>').append(esc(item == null ? "" : HttpJson.sendable(item))).append("</").append(tag).append('>');
                return sb.toString();
            }
            int before = used.size();
            StringBuilder inner = new StringBuilder();
            boolean nextOptional = false, choice = false;
            for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Comment c) {
                    if (c.getData().contains("optional")) nextOptional = true;
                    if (c.getData().contains("one of")) choice = true;   // of a choice, only what is filled in is sent
                    continue;
                }
                if (n instanceof Element k) {
                    String s = write(k, false, here, scope, nextOptional || choice);
                    nextOptional = false;
                    if (s != null) inner.append(s);
                }
            }
            if (optional && used.size() == before) return null;
            return "<" + tag + decl + ">" + inner + "</" + tag + ">";
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Text of every node the XPath selects (namespaces are ignored by using local-name() in the expression). */
    static List<Object> xpathTexts(Document doc, String expression) {
        NodeList nodes = xpathNodes(doc, expression);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) out.add(nodes.item(i).getTextContent().strip());
        return out;
    }

    /** G6: the XML of every node the XPath selects, with its own tags (outer) or only what is inside them (inner). */
    static List<Object> xpathXml(Document doc, String expression, boolean outer) {
        NodeList nodes = xpathNodes(doc, expression);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element e)) { out.add(n.getTextContent()); continue; }
            if (outer) { out.add(XmlSupport.write(e, false)); continue; }
            StringBuilder sb = new StringBuilder();
            for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof Element ce) sb.append(XmlSupport.write(ce, false));
                else if (c instanceof Comment cm) sb.append("<!--").append(cm.getData()).append("-->");
                else if (c instanceof Text t) sb.append(t.getData().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static NodeList xpathNodes(Document doc, String expression) {
        try {
            javax.xml.xpath.XPath xp = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            return (NodeList) xp.evaluate(expression, doc, javax.xml.xpath.XPathConstants.NODESET);
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
