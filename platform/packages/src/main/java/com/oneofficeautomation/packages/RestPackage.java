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
            description = "Send an HTTP request and return status, headers and body (parsed as JSON when possible).")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "body", type = OoaType.STRING, description = "Response text.")
    @Output(name = "json", description = "Parsed JSON body, or empty.")
    @Output(name = "headers", type = OoaType.DICTIONARY, description = "Response headers.")
    public Map<String, Object> request(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^https?://", description = "Absolute URL.") String url,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"GET\"", enumValues = {"GET", "POST", "PUT", "PATCH", "DELETE"}, description = "HTTP method.") String method,
            @Input(name = "query", type = OoaType.DICTIONARY, required = false, description = "Query parameters, URL-encoded automatically.") Map<String, Object> query,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Request headers.") Map<String, Object> headers,
            @Input(name = "json", required = false, description = "JSON body (sets Content-Type).") Object json,
            @Input(name = "form", type = OoaType.DICTIONARY, required = false, description = "Form fields (application/x-www-form-urlencoded).") Map<String, Object> form,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Raw text body.") String body,
            @Input(name = "basic_user", type = OoaType.STRING, required = false, description = "Basic auth user name.") String basicUser,
            @Input(name = "basic_password", type = OoaType.CREDENTIAL, required = false, description = "Basic auth password from the vault.") Object basicPassword,
            @Input(name = "bearer_token", type = OoaType.CREDENTIAL, required = false, description = "Bearer token from the vault.") Object bearer,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "Response timeout.") int timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Raise REST.HTTP_ERROR on 4xx/5xx.") boolean failOnError) {
        StringBuilder u = new StringBuilder(url);
        if (query != null && !query.isEmpty()) u.append(url.contains("?") ? "&" : "?").append(encode(query));
        HttpRequest.Builder b;
        try { b = HttpRequest.newBuilder(URI.create(u.toString())).timeout(Duration.ofSeconds(timeout)); }
        catch (IllegalArgumentException e) { throw new ActionException("REST.BAD_URL", "Invalid URL: " + url, "Check the address, e.g. https://api.example.com/orders."); }
        if (headers != null) headers.forEach((k, v) -> b.header(k, String.valueOf(v)));
        HttpRequest.BodyPublisher pub = HttpRequest.BodyPublishers.noBody();
        if (json != null) { pub = HttpRequest.BodyPublishers.ofString(JsonLite.write(json)); b.header("Content-Type", "application/json"); }
        else if (form != null) { pub = HttpRequest.BodyPublishers.ofString(encode(form)); b.header("Content-Type", "application/x-www-form-urlencoded"); }
        else if (body != null) pub = HttpRequest.BodyPublishers.ofString(body);
        if (basicUser != null && basicPassword instanceof SecureString pw)
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((basicUser + ":" + pw.reveal()).getBytes(StandardCharsets.UTF_8)));
        if (bearer instanceof SecureString t) b.header("Authorization", "Bearer " + t.reveal());
        b.method(method, pub);
        ctx.info(method + " " + url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****"));
        HttpResponse<String> resp;
        try { resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (HttpTimeoutException e) { throw new ActionException("REST.TIMEOUT", "No response from " + url + " within " + timeout + " s", "Raise timeout_seconds or check the service.", true); }
        catch (IOException e) { throw new ActionException("REST.UNREACHABLE", "Cannot reach " + url + ": " + e.getMessage(), "Check the URL, proxy settings and network access.", true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Request interrupted"); }
        int status = resp.statusCode();
        Object parsed = null;
        try { if (!resp.body().isBlank()) parsed = JsonLite.parse(resp.body()); } catch (IllegalArgumentException ignored) { }
        if (failOnError && status >= 400)
            throw new ActionException("REST.HTTP_ERROR", method + " " + url + " returned HTTP " + status,
                    status == 401 || status == 403 ? "Check the credentials and permissions." : null,
                    status == 429 || status >= 500, Map.of("status", BigDecimal.valueOf(status), "body", resp.body().length() > 500 ? resp.body().substring(0, 500) : resp.body()));
        Map<String, Object> hdrs = new LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> hdrs.put(k, String.join(", ", v)));
        return Out.of("status", BigDecimal.valueOf(status), "body", resp.body(), "json", parsed, "headers", hdrs);
    }

    private static String encode(Map<String, Object> m) {
        StringJoiner j = new StringJoiner("&");
        m.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)));
        return j.toString();
    }
}
