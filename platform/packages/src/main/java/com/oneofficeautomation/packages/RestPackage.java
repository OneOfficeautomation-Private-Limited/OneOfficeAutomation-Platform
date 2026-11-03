package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@OoaPackage(id = "ooa.rest", name = "REST Web Service", version = "1.0.0", description = "Call HTTP/REST APIs with JSON, headers and authentication.")
public final class RestPackage {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Action(id = "request", name = "Send request", category = "HTTP", timeoutSeconds = 120, capabilities = "network",
            description = "Send an HTTP request and return status, headers and body (parsed as JSON when possible).",
            panel = "url, method, [Use a specific proxy: proxy, proxy_user, proxy_password], (Sign in: No sign-in = / User name and password = basic_user, basic_password / Bearer token = bearer_token"
                    + " / OAuth 2.0 = oauth_token_url, oauth_client_id, oauth_client_secret, oauth_refresh_token, oauth_scope, oauth_audience, oauth_resource, oauth_client_auth"
                    + " / OAuth 1.0a = oauth1_consumer_key, oauth1_consumer_secret, oauth1_token, oauth1_token_secret, oauth1_signature_method"
                    + " / Windows user name and password, NTLM = ntlm_user, ntlm_domain, ntlm_password), headers, (Body: None = / JSON = json / Form fields = form / Multipart form = multipart / Text = body / A file = body_file), timeout_seconds, [Send a client certificate: client_certificate, client_certificate_password] | windows_auth, content_type, query, fail_on_error, insecure_tls, accept_cookies, follow_redirects")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "body", type = OoaType.STRING, description = "Response text.")
    @Output(name = "json", description = "Parsed JSON body, or empty.")
    @Output(name = "headers", type = OoaType.DICTIONARY, description = "Response headers.")
    @Output(name = "refresh_token", type = OoaType.CREDENTIAL, description = "With an OAuth 2.0 refresh token: the newest refresh token (the server may issue a new one); save it to the vault for the next run. Empty otherwise.")
    public Map<String, Object> request(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^https?://", description = "Absolute URL.") String url,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"GET\"", enumValues = {"GET", "POST", "PUT", "PATCH", "DELETE"}, description = "HTTP method.") String method,
            @Input(name = "query", type = OoaType.DICTIONARY, required = false, description = "Query parameters, URL-encoded automatically.") Map<String, Object> query,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Request headers.") Map<String, Object> headers,
            @Input(name = "json", required = false, visibleWhen = "method!=GET;form=;body=", description = "JSON body (sets Content-Type).") Object json,
            @Input(name = "form", type = OoaType.DICTIONARY, required = false, visibleWhen = "method!=GET;json=;body=", description = "Form fields (application/x-www-form-urlencoded).") Map<String, Object> form,
            @Input(name = "body", type = OoaType.STRING, required = false, visibleWhen = "method!=GET;json=;form=", description = "Raw text body.") String body,
            @Input(name = "basic_user", type = OoaType.STRING, required = false, description = "Basic auth user name.") String basicUser,
            @Input(name = "basic_password", type = OoaType.CREDENTIAL, required = false, description = "Basic auth password from the vault.") Object basicPassword,
            @Input(name = "bearer_token", type = OoaType.CREDENTIAL, required = false, description = "Bearer token from the vault.") Object bearer,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 1, description = "Response timeout.") int timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Raise REST.HTTP_ERROR on 4xx/5xx.") boolean failOnError,
            @Input(name = "content_type", type = OoaType.STRING, required = false, example = "application/xml", description = "Content-Type of a text or file body, e.g. application/xml, text/plain or application/pdf.") String contentType,
            @Input(name = "insecure_tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Accept any server certificate (self-signed or expired); for test systems only.") boolean insecure,
            @Input(name = "proxy", type = OoaType.STRING, required = false, example = "proxy.example.com:8080", description = "Proxy server as host:port; the runner's settings when empty.") String proxy,
            @Input(name = "proxy_user", type = OoaType.STRING, required = false, visibleWhen = "proxy!=", description = "Proxy user name, when the proxy needs a sign-in.") String proxyUser,
            @Input(name = "proxy_password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "proxy!=", description = "Proxy password from the vault.") Object proxyPassword,
            @Input(name = "client_certificate", type = OoaType.FILE, required = false, description = "Client certificate (.p12 or .pfx) for servers that require mutual TLS.") String clientCert,
            @Input(name = "client_certificate_password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "client_certificate!=", description = "Password of the client certificate file.") Object clientCertPassword,
            @Input(name = "accept_cookies", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep cookies the server sets and send them back on later requests of this run.") boolean cookies,
            @Input(name = "follow_redirects", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Follow redirects (3xx) automatically.") boolean redirects,
            @Input(name = "multipart", type = OoaType.DICTIONARY, required = false, visibleWhen = "method!=GET",
                    description = "Multipart form (multipart/form-data): field name to text, or to {\"file\": path, \"content_type\": type} for a file part.") Map<String, Object> multipart,
            @Input(name = "body_file", type = OoaType.FILE, required = false, visibleWhen = "method!=GET",
                    description = "File sent as the raw body, byte for byte; content_type gives its type.") String bodyFile,
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
            @Input(name = "ntlm_password", label = "Windows password", type = OoaType.CREDENTIAL, required = false, description = "Windows password of the NTLM user, from the vault.") Object ntlmPassword) {
        IntegratedAuthHttp.check(windowsAuth);
        // the new sign-in options (OAuth 2.0, OAuth 1.0a, NTLM) are each used alone
        boolean oauth2 = given(oauthTokenUrl) || given(oauthClientId) || given(oauthClientSecret) || given(oauthRefreshToken);
        boolean oauth1 = given(oauth1ConsumerKey) || given(oauth1ConsumerSecret) || given(oauth1Token) || given(oauth1TokenSecret);
        boolean ntlm = given(ntlmUser) || given(ntlmPassword);
        boolean older = given(basicUser) || given(basicPassword) || given(bearer) || given(windowsAuth);
        if ((oauth2 ? 1 : 0) + (oauth1 ? 1 : 0) + (ntlm ? 1 : 0) + (older ? 1 : 0) > 1 && (oauth2 || oauth1 || ntlm))
            throw new ActionException("REST.BAD_AUTH", "More than one sign-in was given (" + String.join(", ",
                    java.util.stream.Stream.of(older ? "user name and password, bearer token or windows_auth" : null, oauth2 ? "OAuth 2.0" : null, oauth1 ? "OAuth 1.0a" : null, ntlm ? "NTLM" : null)
                            .filter(Objects::nonNull).toList()) + "), so the request was not sent", "Fill the fields of one sign-in option only.");
        RestSignIn.OAuth2 o2 = oauth2 ? oauth2Settings(oauthTokenUrl, oauthClientId, oauthClientSecret, oauthRefreshToken, oauthScope, oauthAudience, oauthResource, oauthClientAuth) : null;
        RestSignIn.OAuth1 o1 = oauth1 ? oauth1Settings(oauth1ConsumerKey, oauth1ConsumerSecret, oauth1Token, oauth1TokenSecret, oauth1Method) : null;
        if (ntlm) {
            if (!given(ntlmUser)) throw new ActionException("REST.NO_USER", "NTLM sign-in needs ntlm_user and ntlm_password", "Fill ntlm_user (and ntlm_domain) and ntlm_password.");
            if (!(vaultSecret(ntlmPassword, "ntlm_password") instanceof SecureString))
                throw new ActionException("REST.NO_SECRET", "NTLM sign-in needs ntlm_password from the vault, so the request was not sent",
                        "Give a vault reference {\"$credential\": \"locker/credential/attribute\"} or a Credential variable.");
            String u = ntlmUser.strip();
            basicUser = !given(ntlmDomain) || u.contains("\\") || u.contains("@") ? u : ntlmDomain.strip() + "\\" + u;
            basicPassword = ntlmPassword;
            windowsAuth = "user_password";
        }
        HttpOptions opts = new HttpOptions(insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, cookies, redirects);
        long bodies = java.util.stream.Stream.of(json, form, multipart, body, bodyFile).filter(Objects::nonNull).count();
        if (bodies > 1) throw new ActionException("REST.BAD_BODY", "More than one body was given", "Give only one of json, form, multipart, body or body_file.");
        StringBuilder u = new StringBuilder(url);
        if (query != null && !query.isEmpty()) u.append(url.contains("?") ? "&" : "?").append(encode(query));
        HttpRequest.Builder b;
        try { b = HttpRequest.newBuilder(URI.create(u.toString())).timeout(Duration.ofSeconds(timeout)); }
        catch (IllegalArgumentException e) { throw new ActionException("REST.BAD_URL", "Invalid URL: " + url, "Check the address, e.g. https://api.example.com/orders."); }
        if (headers != null) headers.forEach((k, v) -> b.header(k, String.valueOf(v)));
        HttpRequest.BodyPublisher pub = HttpRequest.BodyPublishers.noBody();
        if (json != null) { pub = HttpRequest.BodyPublishers.ofString(JsonLite.write(json)); b.header("Content-Type", "application/json"); }
        else if (form != null) { pub = HttpRequest.BodyPublishers.ofString(encode(form)); b.header("Content-Type", "application/x-www-form-urlencoded"); }
        else if (multipart != null) {
            Object[] mp = HttpJson.multipart("form-data", multipartParts(ctx, multipart));
            pub = HttpRequest.BodyPublishers.ofByteArray((byte[]) mp[0]);
            b.header("Content-Type", (String) mp[1]);
        } else if (body != null || bodyFile != null) {
            if (body != null) pub = HttpRequest.BodyPublishers.ofString(body);
            else {
                java.nio.file.Path f = ctx.resolvePath(bodyFile);
                if (!java.nio.file.Files.isRegularFile(f)) throw new ActionException("REST.FILE_NOT_FOUND", "File not found: " + f, "Check the body_file path.");
                try { pub = HttpRequest.BodyPublishers.ofFile(f); }
                catch (IOException e) { throw new ActionException("REST.FILE_ERROR", "Cannot read " + f + ": " + e.getMessage(), "Check the file is not locked."); }
                if (contentType == null || contentType.isBlank()) b.header("Content-Type", "application/octet-stream");
            }
            if (contentType != null && !contentType.isBlank()) {
                if (contentType.matches(".*[\\r\\n].*")) throw new ActionException("REST.BAD_HEADER", "content_type may not contain line breaks");
                b.header("Content-Type", contentType.strip());
            }
        }
        if (windowsAuth != null && !windowsAuth.isBlank()) {
            if (bearer != null || insecure || (proxy != null && !proxy.isBlank()) || (clientCert != null && !clientCert.isBlank()) || cookies)
                throw new ActionException("REST.BAD_AUTH", "windows_auth cannot be combined with bearer_token, a proxy, a client certificate, insecure_tls or accept_cookies",
                        "Use Windows sign-in alone (the runner's proxy settings apply).");
            HttpRequest built = b.method(method, pub).build();
            Map<String, String> hs = new LinkedHashMap<>();
            built.headers().map().forEach((k, v) -> hs.put(k, String.join(", ", v)));
            byte[] bytes = bodyBytes(ctx, json, form, multipart, body, bodyFile, hs);
            ctx.info(method + " " + masked(url) + " (Windows sign-in: " + windowsAuth.replace('_', ' ') + ")");
            IntegratedAuthHttp.Response r = IntegratedAuthHttp.send(ctx, windowsAuth, method, u.toString(), hs, bytes, basicUser, basicPassword, timeout, redirects, null);
            return result(method, url, r.status(), r.text(), new LinkedHashMap<>(r.headers()), failOnError);
        }
        if (basicUser != null && vaultSecret(basicPassword, "basic_password") instanceof SecureString pw)
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((basicUser + ":" + pw.reveal()).getBytes(StandardCharsets.UTF_8)));
        if (vaultSecret(bearer, "bearer_token") instanceof SecureString t) b.header("Authorization", "Bearer " + t.reveal());
        b.method(method, pub);
        ctx.info(method + " " + url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****"));
        HttpClient client = opts.client(ctx, CLIENT);
        HttpResponse<String> resp;
        if (o2 == null && o1 == null) resp = sendHonouringRateLimit(ctx, client, b.build(), HttpResponse.BodyHandlers.ofString(), url, timeout);
        else {
            List<String[]> formFields = new ArrayList<>();
            if (form != null) form.forEach((k, v) -> formFields.add(new String[]{k, String.valueOf(v)}));
            for (int attempt = 0; ; attempt++) {
                HttpRequest.Builder rb = b.copy();
                rb.setHeader("Authorization", o2 != null ? RestSignIn.authorization(ctx, client, o2, attempt > 0, timeout)
                        : RestSignIn.oauth1Header(o1, method, u.toString(), formFields));
                resp = sendHonouringRateLimit(ctx, client, rb.build(), HttpResponse.BodyHandlers.ofString(), url, timeout);
                if (resp.statusCode() != 401 || o2 == null || attempt > 0) break;
                ctx.info("HTTP 401: getting a new OAuth token and sending once more");
            }
        }
        Map<String, Object> hdrs = new LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> hdrs.put(k, String.join(", ", v)));
        Map<String, Object> out = result(method, url, resp.statusCode(), resp.body(), hdrs, failOnError);
        if (o2 != null) out.put("refresh_token", RestSignIn.latestRefreshToken(ctx, o2));
        return out;
    }

    private static boolean given(Object v) { return v != null && !(v instanceof String s && s.isBlank()); }

    private static RestSignIn.OAuth2 oauth2Settings(String tokenUrl, String clientId, Object secret, Object refreshToken, String scope, String audience, String resource, String clientAuth) {
        if (!given(tokenUrl) || !tokenUrl.strip().matches("(?i)^https?://.+"))
            throw new ActionException("REST.OAUTH_SETTINGS", "OAuth 2.0 sign-in needs oauth_token_url (an http or https address), so the request was not sent",
                    "Give the provider's token endpoint, e.g. https://login.example.com/oauth2/token.");
        if (!given(clientId)) throw new ActionException("REST.OAUTH_SETTINGS", "OAuth 2.0 sign-in needs oauth_client_id, so the request was not sent", "Give the client (application) id.");
        SecureString s = vaultSecret(secret, "oauth_client_secret") instanceof SecureString x && !x.reveal().isEmpty() ? x : null;
        SecureString r = vaultSecret(refreshToken, "oauth_refresh_token") instanceof SecureString x && !x.reveal().isEmpty() ? x : null;
        if (s == null && r == null)
            throw new ActionException("REST.OAUTH_SETTINGS", "OAuth 2.0 client credentials sign-in needs oauth_client_secret, so the request was not sent",
                    "Give the client secret from the vault, or a refresh token in oauth_refresh_token.");
        String auth = clientAuth == null || clientAuth.isBlank() ? "basic" : clientAuth.strip();
        if (!auth.equals("basic") && !auth.equals("body"))
            throw new ActionException("REST.OAUTH_SETTINGS", "Unknown oauth_client_auth '" + clientAuth + "'", "Use basic or body.");
        return new RestSignIn.OAuth2(tokenUrl.strip(), clientId.strip(), s, r, scope, audience, resource, auth);
    }

    private static RestSignIn.OAuth1 oauth1Settings(String consumerKey, Object consumerSecret, Object token, Object tokenSecret, String method) {
        if (!given(consumerKey)) throw new ActionException("REST.OAUTH_SETTINGS", "OAuth 1.0a sign-in needs oauth1_consumer_key, so the request was not sent", "Give the consumer key.");
        if (!(vaultSecret(consumerSecret, "oauth1_consumer_secret") instanceof SecureString cs))
            throw new ActionException("REST.OAUTH_SETTINGS", "OAuth 1.0a sign-in needs oauth1_consumer_secret from the vault, so the request was not sent", "Give the consumer secret from the vault.");
        SecureString t = vaultSecret(token, "oauth1_token") instanceof SecureString x && !x.reveal().isEmpty() ? x : null;
        SecureString ts = vaultSecret(tokenSecret, "oauth1_token_secret") instanceof SecureString x ? x : null;
        if (t != null && ts == null)
            throw new ActionException("REST.OAUTH_SETTINGS", "OAuth 1.0a sign-in with oauth1_token needs oauth1_token_secret, so the request was not sent", "Give the token secret from the vault.");
        String m = method == null || method.isBlank() ? "HMAC-SHA1" : method.strip().toUpperCase(Locale.ROOT);
        if (!m.equals("HMAC-SHA1") && !m.equals("HMAC-SHA256"))
            throw new ActionException("REST.OAUTH_SETTINGS", "Unknown oauth1_signature_method '" + method + "'", "Use HMAC-SHA1 or HMAC-SHA256.");
        return new RestSignIn.OAuth1(consumerKey.strip(), cs, t, ts, m);
    }

    private static Map<String, Object> result(String method, String url, int status, String text, Map<String, Object> hdrs, boolean failOnError) {
        Object parsed = null;
        try { if (!text.isBlank()) parsed = JsonLite.parse(text); } catch (IllegalArgumentException ignored) { }
        if (failOnError && status >= 400)
            throw new ActionException("REST.HTTP_ERROR", method + " " + url + " returned HTTP " + status,
                    status == 401 || status == 403 ? "Check the credentials and permissions." : null,
                    status == 429 || status >= 500, Map.of("status", BigDecimal.valueOf(status), "body", text.length() > 500 ? text.substring(0, 500) : text));
        return Out.of("status", BigDecimal.valueOf(status), "body", text, "json", parsed, "headers", hdrs, "refresh_token", null);
    }

    /** The request body as bytes, for the Windows sign-in path (which sends it itself); sets Content-Type for a multipart body. */
    private static byte[] bodyBytes(ActionContext ctx, Object json, Map<String, Object> form, Map<String, Object> multipart, String body, String bodyFile, Map<String, String> headers) {
        if (json != null) return JsonLite.write(json).getBytes(StandardCharsets.UTF_8);
        if (form != null) return encode(form).getBytes(StandardCharsets.UTF_8);
        if (multipart != null) {
            Object[] mp = HttpJson.multipart("form-data", multipartParts(ctx, multipart));
            headers.put("Content-Type", (String) mp[1]);
            return (byte[]) mp[0];
        }
        if (body != null) return body.getBytes(StandardCharsets.UTF_8);
        if (bodyFile != null) {
            try { return java.nio.file.Files.readAllBytes(ctx.resolvePath(bodyFile)); }
            catch (IOException e) { throw new ActionException("REST.FILE_ERROR", "Cannot read " + bodyFile + ": " + e.getMessage(), "Check the file is not locked."); }
        }
        return null;
    }

    public Map<String, Object> request(ActionContext ctx, String url, String method, Map<String, Object> query, Map<String, Object> headers, Object json,
            Map<String, Object> form, String body, String basicUser, Object basicPassword, Object bearer, int timeout, boolean failOnError, String contentType,
            boolean insecure, String proxy, String proxyUser, Object proxyPassword, String clientCert, Object clientCertPassword, boolean cookies, boolean redirects) {
        return request(ctx, url, method, query, headers, json, form, body, basicUser, basicPassword, bearer, timeout, failOnError, contentType, insecure, proxy,
                proxyUser, proxyPassword, clientCert, clientCertPassword, cookies, redirects, null, null, null);
    }

    public Map<String, Object> request(ActionContext ctx, String url, String method, Map<String, Object> query, Map<String, Object> headers, Object json,
            Map<String, Object> form, String body, String basicUser, Object basicPassword, Object bearer, int timeout, boolean failOnError, String contentType,
            boolean insecure, String proxy, String proxyUser, Object proxyPassword, String clientCert, Object clientCertPassword, boolean cookies, boolean redirects,
            Map<String, Object> multipart, String bodyFile) {
        return request(ctx, url, method, query, headers, json, form, body, basicUser, basicPassword, bearer, timeout, failOnError, contentType, insecure, proxy,
                proxyUser, proxyPassword, clientCert, clientCertPassword, cookies, redirects, multipart, bodyFile, null);
    }

    public Map<String, Object> request(ActionContext ctx, String url, String method, Map<String, Object> query, Map<String, Object> headers, Object json,
            Map<String, Object> form, String body, String basicUser, Object basicPassword, Object bearer, int timeout, boolean failOnError, String contentType,
            boolean insecure, String proxy, String proxyUser, Object proxyPassword, String clientCert, Object clientCertPassword, boolean cookies, boolean redirects,
            Map<String, Object> multipart, String bodyFile, String windowsAuth) {
        return request(ctx, url, method, query, headers, json, form, body, basicUser, basicPassword, bearer, timeout, failOnError, contentType, insecure, proxy,
                proxyUser, proxyPassword, clientCert, clientCertPassword, cookies, redirects, multipart, bodyFile, windowsAuth,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Multipart parts from {field: text | {"file": path, "content_type": type}}. */
    private static List<Object[]> multipartParts(ActionContext ctx, Map<String, Object> fields) {
        List<Object[]> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String name = e.getKey().replaceAll("[\"\\r\\n]", "_");
            if (e.getValue() instanceof Map<?, ?> m && m.get("file") != null) {
                java.nio.file.Path f = ctx.resolvePath(String.valueOf(m.get("file")));
                if (!java.nio.file.Files.isRegularFile(f)) throw new ActionException("REST.FILE_NOT_FOUND", "File not found for part '" + e.getKey() + "': " + f, "Check the file path.");
                String type = m.get("content_type") == null ? null : String.valueOf(m.get("content_type")).replaceAll("[\\r\\n]", "");
                byte[] data;
                try { data = java.nio.file.Files.readAllBytes(f); if (type == null) type = java.nio.file.Files.probeContentType(f); }
                catch (IOException x) { throw new ActionException("REST.FILE_ERROR", "Cannot read " + f + ": " + x.getMessage(), "Check the file is not locked."); }
                String fileName = HttpJson.safeName(f.getFileName().toString()).replace("\"", "_");
                parts.add(new Object[]{"Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\nContent-Type: "
                        + (type == null ? "application/octet-stream" : type), data});
            } else {
                Object v = e.getValue();
                parts.add(new Object[]{"Content-Disposition: form-data; name=\"" + name + "\"",
                        (v instanceof SecureString sv ? sv.reveal() : com.oneofficeautomation.runner.Values.text(v)).getBytes(StandardCharsets.UTF_8)});
            }
        }
        return parts;
    }

    /** Builder with URL, query, headers and authentication set the same way as Send request. */
    private static HttpRequest.Builder builder(String url, Map<String, Object> query, Map<String, Object> headers, String basicUser, Object basicPassword, Object bearer, int timeout) {
        StringBuilder u = new StringBuilder(url);
        if (query != null && !query.isEmpty()) u.append(url.contains("?") ? "&" : "?").append(encode(query));
        HttpRequest.Builder b;
        try { b = HttpRequest.newBuilder(URI.create(u.toString())).timeout(Duration.ofSeconds(Math.max(1, timeout))); }
        catch (IllegalArgumentException e) { throw new ActionException("REST.BAD_URL", "Invalid URL: " + url, "Check the address, e.g. https://api.example.com/orders."); }
        if (headers != null) headers.forEach((k, v) -> b.header(k, String.valueOf(v)));
        if (basicUser != null && vaultSecret(basicPassword, "basic_password") instanceof SecureString pw)
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((basicUser + ":" + pw.reveal()).getBytes(StandardCharsets.UTF_8)));
        if (vaultSecret(bearer, "bearer_token") instanceof SecureString t) b.header("Authorization", "Bearer " + t.reveal());
        return b;
    }

    /** A sign-in secret must be a Credential; anything else used to be skipped silently, sending the request without sign-in. */
    static Object vaultSecret(Object v, String input) {
        if (v == null || v instanceof SecureString || (v instanceof String s && s.isEmpty())) return v;
        throw new ActionException("REST.NO_SECRET", "Input '" + input + "' must come from the vault (a Credential), so the request was not sent",
                "Give a vault reference {\"$credential\": \"locker/credential/attribute\"} or a Credential variable.");
    }

    private static String masked(String url) { return url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****"); }

    /** HTTP 429 with Retry-After of up to a minute: wait as asked and send again (at most 3 times), as the cloud connectors do. */
    static <T> HttpResponse<T> sendHonouringRateLimit(ActionContext ctx, HttpClient client, HttpRequest req, HttpResponse.BodyHandler<T> handler, String url, int timeout) {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<T> r = send(client, req, handler, url, timeout);
            long wait = r.statusCode() == 429 && attempt < HttpJson.MAX_RATE_RETRIES ? HttpJson.retryAfterMillis(r.headers().firstValue("Retry-After").orElse(null), attempt) : -1;
            if (wait < 0) return r;
            ctx.warn(HttpJson.host(url) + " is rate limiting (HTTP 429); trying again in " + Math.max(1, Math.round(wait / 1000.0)) + " s (" + (attempt + 1) + " of " + HttpJson.MAX_RATE_RETRIES + ")");
            HttpJson.pause(ctx, wait);
        }
    }

    private static <T> HttpResponse<T> send(HttpClient client, HttpRequest req, HttpResponse.BodyHandler<T> handler, String url, int timeout) {
        try { return client.send(req, handler); }
        catch (HttpTimeoutException e) { throw new ActionException("REST.TIMEOUT", "No response from " + url + " within " + timeout + " s", "Raise timeout_seconds or check the service.", true); }
        catch (IOException e) { throw new ActionException("REST.UNREACHABLE", "Cannot reach " + masked(url) + ": " + NetErrors.reason(e, null), "Check the URL, proxy settings and network access.", true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Request interrupted"); }
    }

    private static ActionException httpError(String method, String url, int status, String body) {
        return new ActionException("REST.HTTP_ERROR", method + " " + url + " returned HTTP " + status,
                status == 401 || status == 403 ? "Check the credentials and permissions." : null,
                status == 429 || status >= 500, Map.of("status", BigDecimal.valueOf(status), "body", body.length() > 500 ? body.substring(0, 500) : body));
    }

    @Action(id = "download_file", name = "Download file", category = "HTTP", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Download a URL straight to a file (any file type, including large binaries), with the same headers and authentication as Send request.",
            panel = "url, [Use a specific proxy: proxy, proxy_user, proxy_password], (Sign in: No sign-in = / User name and password = basic_user, basic_password / Bearer token = bearer_token), headers, path, timeout_seconds, [Send a client certificate: client_certificate, client_certificate_password] | windows_auth, query, overwrite, insecure_tls")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file.")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "size_bytes", type = OoaType.NUMBER, description = "File size in bytes.")
    @Output(name = "content_type", type = OoaType.STRING, description = "Content-Type reported by the server, or empty.")
    public Map<String, Object> downloadFile(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^https?://", description = "Absolute URL of the file.") String url,
            @Input(name = "path", type = OoaType.FILE, description = "File to save to, or a folder to keep the server's file name.") String path,
            @Input(name = "query", type = OoaType.DICTIONARY, required = false, description = "Query parameters, URL-encoded automatically.") Map<String, Object> query,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Request headers.") Map<String, Object> headers,
            @Input(name = "basic_user", type = OoaType.STRING, required = false, description = "Basic auth user name.") String basicUser,
            @Input(name = "basic_password", type = OoaType.CREDENTIAL, required = false, description = "Basic auth password from the vault.") Object basicPassword,
            @Input(name = "bearer_token", type = OoaType.CREDENTIAL, required = false, description = "Bearer token from the vault.") Object bearer,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file.") boolean overwrite,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", min = 1, description = "Time allowed for the whole download.") int timeout,
            @Input(name = "insecure_tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Accept any server certificate (self-signed or expired); for test systems only.") boolean insecure,
            @Input(name = "proxy", type = OoaType.STRING, required = false, example = "proxy.example.com:8080", description = "Proxy server as host:port; the runner's settings when empty.") String proxy,
            @Input(name = "proxy_user", type = OoaType.STRING, required = false, visibleWhen = "proxy!=", description = "Proxy user name, when the proxy needs a sign-in.") String proxyUser,
            @Input(name = "proxy_password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "proxy!=", description = "Proxy password from the vault.") Object proxyPassword,
            @Input(name = "client_certificate", type = OoaType.FILE, required = false, description = "Client certificate (.p12 or .pfx) for servers that require mutual TLS.") String clientCert,
            @Input(name = "client_certificate_password", type = OoaType.CREDENTIAL, required = false, description = "Password of the client certificate file.") Object clientCertPassword,
            @Input(name = "windows_auth", type = OoaType.STRING, required = false, enumValues = {"current_user", "user_password"},
                    description = "Windows sign-in (NTLM or Kerberos) for intranet sites, SharePoint on premises, IIS and Exchange: current_user signs in as the runner's Windows account (Windows runners); user_password uses basic_user (DOMAIN\\user) and basic_password.") String windowsAuth) {
        IntegratedAuthHttp.check(windowsAuth);
        boolean windows = windowsAuth != null && !windowsAuth.isBlank();
        HttpOptions opts = new HttpOptions(insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, false, true);
        HttpRequest req = builder(url, query, headers, windows ? null : basicUser, windows ? null : basicPassword, bearer, timeout).GET().build();
        ctx.info("GET " + masked(url) + " (download)");
        java.nio.file.Path target = ctx.resolvePath(path);
        java.nio.file.Path folder = java.nio.file.Files.isDirectory(target) ? target : target.getParent();
        java.nio.file.Path temp;
        try {
            if (folder != null) java.nio.file.Files.createDirectories(folder);
            temp = java.nio.file.Files.createTempFile(folder == null ? java.nio.file.Path.of(".") : folder, ".ooa-download-", ".part");
        } catch (IOException e) { throw new ActionException("REST.FILE_ERROR", "Cannot write into " + folder + ": " + e.getMessage(), "Check the folder exists and is writable."); }
        try {
            int status; String disposition, finalPath, type;
            if (windows) {
                if (bearer != null || insecure || (proxy != null && !proxy.isBlank()) || (clientCert != null && !clientCert.isBlank()))
                    throw new ActionException("REST.BAD_AUTH", "windows_auth cannot be combined with bearer_token, a proxy, a client certificate or insecure_tls",
                            "Use Windows sign-in alone (the runner's proxy settings apply).");
                Map<String, String> hs = new LinkedHashMap<>();
                req.headers().map().forEach((k, v) -> hs.put(k, String.join(", ", v)));
                IntegratedAuthHttp.Response r = IntegratedAuthHttp.send(ctx, windowsAuth, "GET", req.uri().toString(), hs, null, basicUser, basicPassword, timeout, true, temp);
                status = r.status();
                if (status >= 400) throw httpError("GET", url, status, r.text().length() > 2000 ? r.text().substring(0, 2000) : r.text());
                disposition = r.headers().get("content-disposition");
                finalPath = URI.create(r.finalUrl()).getPath();
                type = r.headers().getOrDefault("content-type", "");
            } else {
                HttpResponse<java.nio.file.Path> resp = sendHonouringRateLimit(ctx, opts.client(ctx, CLIENT), req, HttpResponse.BodyHandlers.ofFile(temp), url, timeout);
                status = resp.statusCode();
                if (status >= 400) {
                    String body;
                    try { byte[] b = java.nio.file.Files.readAllBytes(temp); body = new String(b, 0, Math.min(b.length, 2000), StandardCharsets.UTF_8); } catch (IOException e) { body = ""; }
                    throw httpError("GET", url, status, body);
                }
                disposition = resp.headers().firstValue("Content-Disposition").orElse(null);
                finalPath = resp.uri().getPath();
                type = resp.headers().firstValue("Content-Type").orElse("");
            }
            if (java.nio.file.Files.isDirectory(target)) {
                String name = disposition != null ? dispositionName(disposition) : finalPath == null ? "" : finalPath.substring(finalPath.lastIndexOf('/') + 1);
                target = target.resolve(HttpJson.safeName(java.net.URLDecoder.decode(name.isBlank() ? "download" : name, StandardCharsets.UTF_8)));
            }
            if (java.nio.file.Files.exists(target) && !overwrite)
                throw new ActionException("REST.FILE_EXISTS", "File already exists: " + target, "Set overwrite to true or choose another path.");
            java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Out.of("path", target.toString(), "status", BigDecimal.valueOf(status), "size_bytes", BigDecimal.valueOf(java.nio.file.Files.size(target)),
                    "content_type", type);
        } catch (IOException e) {
            throw new ActionException("REST.FILE_ERROR", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open and check the folder is writable.", true);
        } finally {
            try { java.nio.file.Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    public Map<String, Object> downloadFile(ActionContext ctx, String url, String path, Map<String, Object> query, Map<String, Object> headers, String basicUser,
            Object basicPassword, Object bearer, boolean overwrite, int timeout, boolean insecure, String proxy, String proxyUser, Object proxyPassword,
            String clientCert, Object clientCertPassword) {
        return downloadFile(ctx, url, path, query, headers, basicUser, basicPassword, bearer, overwrite, timeout, insecure, proxy, proxyUser, proxyPassword,
                clientCert, clientCertPassword, null);
    }

    static String dispositionName(String header) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)filename\\*=(?:UTF-8'')?([^;]+)|filename=\"?([^\";]+)\"?").matcher(header);
        String found = "";
        while (m.find()) { if (m.group(1) != null) return m.group(1).strip(); if (found.isEmpty()) found = m.group(2).strip(); }
        return found;
    }

    @Action(id = "upload_file", name = "Upload file", category = "HTTP", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Send a file to a URL as a multipart form upload, optionally with extra form fields.",
            panel = "url, path, field_name, form, method, [Use a specific proxy: proxy, proxy_user, proxy_password], (Sign in: No sign-in = / User name and password = basic_user, basic_password / Bearer token = bearer_token), headers, timeout_seconds, [Send a client certificate: client_certificate, client_certificate_password] | query, fail_on_error, insecure_tls")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "body", type = OoaType.STRING, description = "Response text.")
    @Output(name = "json", description = "Parsed JSON body, or empty.")
    public Map<String, Object> uploadFile(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^https?://", description = "Absolute URL that accepts the upload.") String url,
            @Input(name = "path", type = OoaType.FILE, description = "File to upload.") String path,
            @Input(name = "field_name", type = OoaType.STRING, required = false, defaultValue = "\"file\"", description = "Form field name the server expects for the file.") String field,
            @Input(name = "form", type = OoaType.DICTIONARY, required = false, description = "Other form fields sent with the file.") Map<String, Object> form,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"POST\"", enumValues = {"POST", "PUT"}, description = "HTTP method.") String method,
            @Input(name = "query", type = OoaType.DICTIONARY, required = false, description = "Query parameters, URL-encoded automatically.") Map<String, Object> query,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Request headers.") Map<String, Object> headers,
            @Input(name = "basic_user", type = OoaType.STRING, required = false, description = "Basic auth user name.") String basicUser,
            @Input(name = "basic_password", type = OoaType.CREDENTIAL, required = false, description = "Basic auth password from the vault.") Object basicPassword,
            @Input(name = "bearer_token", type = OoaType.CREDENTIAL, required = false, description = "Bearer token from the vault.") Object bearer,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", min = 1, description = "Response timeout.") int timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Raise REST.HTTP_ERROR on 4xx/5xx.") boolean failOnError,
            @Input(name = "insecure_tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Accept any server certificate (self-signed or expired); for test systems only.") boolean insecure,
            @Input(name = "proxy", type = OoaType.STRING, required = false, example = "proxy.example.com:8080", description = "Proxy server as host:port; the runner's settings when empty.") String proxy,
            @Input(name = "proxy_user", type = OoaType.STRING, required = false, visibleWhen = "proxy!=", description = "Proxy user name, when the proxy needs a sign-in.") String proxyUser,
            @Input(name = "proxy_password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "proxy!=", description = "Proxy password from the vault.") Object proxyPassword,
            @Input(name = "client_certificate", type = OoaType.FILE, required = false, description = "Client certificate (.p12 or .pfx) for servers that require mutual TLS.") String clientCert,
            @Input(name = "client_certificate_password", type = OoaType.CREDENTIAL, required = false, description = "Password of the client certificate file.") Object clientCertPassword) {
        HttpOptions opts = new HttpOptions(insecure, proxy, proxyUser, proxyPassword, clientCert, clientCertPassword, false, true);
        java.nio.file.Path f = ctx.resolvePath(path);
        if (!java.nio.file.Files.isRegularFile(f)) throw new ActionException("REST.FILE_NOT_FOUND", "File not found: " + f, "Check the path.");
        String fieldName = field == null || field.isBlank() ? "file" : field.replaceAll("[\"\\r\\n]", "_");
        List<Object[]> parts = new ArrayList<>();
        if (form != null) form.forEach((k, v) -> parts.add(new Object[]{"Content-Disposition: form-data; name=\"" + k.replaceAll("[\"\\r\\n]", "_") + "\"",
                (v instanceof SecureString s ? s.reveal() : com.oneofficeautomation.runner.Values.text(v)).getBytes(StandardCharsets.UTF_8)}));
        String type;
        byte[] data;
        try {
            type = java.nio.file.Files.probeContentType(f);
            data = java.nio.file.Files.readAllBytes(f);
        } catch (IOException e) { throw new ActionException("REST.FILE_ERROR", "Cannot read " + f + ": " + e.getMessage(), "Check the file is not locked."); }
        String fileName = HttpJson.safeName(f.getFileName().toString()).replace("\"", "_");
        parts.add(new Object[]{"Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\nContent-Type: "
                + (type == null ? "application/octet-stream" : type), data});
        Object[] mp = HttpJson.multipart("form-data", parts);
        HttpRequest.Builder b = builder(url, query, headers, basicUser, basicPassword, bearer, timeout);
        b.header("Content-Type", (String) mp[1]);
        String m = "PUT".equalsIgnoreCase(method) ? "PUT" : "POST";
        b.method(m, HttpRequest.BodyPublishers.ofByteArray((byte[]) mp[0]));
        ctx.info(m + " " + masked(url) + " (upload " + fileName + ", " + data.length + " bytes)");
        HttpResponse<String> resp = sendHonouringRateLimit(ctx, opts.client(ctx, CLIENT), b.build(), HttpResponse.BodyHandlers.ofString(), url, timeout);
        int status = resp.statusCode();
        if (failOnError && status >= 400) throw httpError(m, url, status, resp.body());
        Object parsed = null;
        try { if (!resp.body().isBlank()) parsed = JsonLite.parse(resp.body()); } catch (IllegalArgumentException ignored) { }
        return Out.of("status", BigDecimal.valueOf(status), "body", resp.body(), "json", parsed);
    }

    private static String encode(Map<String, Object> m) {
        StringJoiner j = new StringJoiner("&");
        m.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)));
        return j.toString();
    }
}
