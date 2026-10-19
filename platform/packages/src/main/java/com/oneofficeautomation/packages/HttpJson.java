package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Shared client for cloud connectors (Microsoft 365, Google, Salesforce, ServiceNow, Jira, Slack, Workday).
 * One place for authentication headers, JSON bodies, typed errors with the service's own message,
 * retryable flags (429, 5xx), and secret masking in logs.
 */
final class HttpJson {
    private HttpJson() {}
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    record Response(int status, String text, Object json, byte[] bytes, Map<String, List<String>> headers) {
        @SuppressWarnings("unchecked") Map<String, Object> map() { return json instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }
        String header(String n) { return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(n)).flatMap(e -> e.getValue().stream()).findFirst().orElse(null); }
    }

    static final class Req {
        final String prefix, method, url;
        final Map<String, String> headers = new LinkedHashMap<>();
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        int timeout = 60; boolean binary;
        Req(String prefix, String method, String url) { this.prefix = prefix; this.method = method; this.url = url; }
        Req bearer(Object token) {
            if (!(token instanceof SecureString s)) throw new ActionException(prefix + ".NO_TOKEN", "An access token from the vault is required", "Map access_token to a Credential variable.");
            headers.put("Authorization", "Bearer " + s.reveal()); return this;
        }
        Req basic(String user, Object password) {
            if (!(password instanceof SecureString s)) throw new ActionException(prefix + ".NO_PASSWORD", "A password or API token from the vault is required");
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + s.reveal()).getBytes(StandardCharsets.UTF_8))); return this;
        }
        Req header(String k, String v) { headers.put(k, v); return this; }
        Req json(Object value) { body = HttpRequest.BodyPublishers.ofString(JsonLite.write(Values.toJsonable(value))); headers.put("Content-Type", "application/json"); return this; }
        Req bytes(byte[] data, String contentType) { body = HttpRequest.BodyPublishers.ofByteArray(data); headers.put("Content-Type", contentType); return this; }
        Req binaryResponse() { binary = true; return this; }
        Req timeout(int s) { timeout = s; return this; }

        Response send(ActionContext ctx) {
            HttpRequest.Builder b;
            try { b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout)).method(method, body); }
            catch (IllegalArgumentException e) { throw new ActionException(prefix + ".BAD_URL", "Invalid URL: " + url, "Check the instance or base URL."); }
            headers.putIfAbsent("Accept", "application/json");
            headers.forEach(b::header);
            ctx.info(method + " " + url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****"));
            HttpResponse<byte[]> r;
            try { r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()); }
            catch (HttpTimeoutException e) { throw new ActionException(prefix + ".TIMEOUT", "No response within " + timeout + " s from " + host(url), "Try again later or raise the timeout.", true); }
            catch (IOException e) { throw new ActionException(prefix + ".UNREACHABLE", "Cannot reach " + host(url) + ": " + e.getMessage(), "Check the URL, proxy and network access.", true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
            String text = binary ? "" : new String(r.body(), StandardCharsets.UTF_8);
            Object parsed = null;
            if (!binary && !text.isBlank()) try { parsed = JsonLite.parse(text); } catch (IllegalArgumentException ignored) { }
            Response resp = new Response(r.statusCode(), text, parsed, r.body(), r.headers().map());
            if (r.statusCode() >= 400) throw error(prefix, resp);
            return resp;
        }
    }

    static Req req(String prefix, String method, String url) { return new Req(prefix, method, url); }

    static String host(String url) { try { return URI.create(url).getHost(); } catch (Exception e) { return url; } }

    /** Builds a typed error carrying the service's own message (Graph, Google, Salesforce, ServiceNow and Jira formats). */
    static ActionException error(String prefix, Response r) {
        String msg = null;
        Object j = r.json();
        if (j instanceof Map<?, ?> m) {
            Object e = m.get("error");
            if (e instanceof Map<?, ?> em) msg = String.valueOf(em.get("message") != null ? em.get("message") : em.get("detail"));
            else if (e instanceof String s) msg = s + (m.get("error_description") != null ? ": " + m.get("error_description") : "");
            if (msg == null && m.get("errorMessages") instanceof List<?> l && !l.isEmpty()) msg = String.valueOf(l.get(0));
            if (msg == null && m.get("errors") instanceof Map<?, ?> em && !em.isEmpty()) msg = String.valueOf(em);
            if (msg == null && m.get("message") != null) msg = String.valueOf(m.get("message"));
        } else if (j instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> first && first.get("message") != null) msg = String.valueOf(first.get("message"));
        if (msg == null) msg = r.text().length() > 300 ? r.text().substring(0, 300) : r.text();
        int s = r.status();
        String code = s == 401 ? ".AUTH_FAILED" : s == 403 ? ".FORBIDDEN" : s == 404 ? ".NOT_FOUND" : s == 429 ? ".RATE_LIMITED" : s >= 500 ? ".SERVER_ERROR" : ".REQUEST_FAILED";
        String fix = s == 401 ? "The access token is missing, wrong or expired; refresh it in the vault."
                : s == 403 ? "The account or app lacks permission for this operation." : s == 404 ? "Check the id, name or path." : s == 429 ? "Too many requests; retry later." : null;
        return new ActionException(prefix + code, "HTTP " + s + ": " + msg, fix, s == 429 || s >= 500, Map.of("status", java.math.BigDecimal.valueOf(s)));
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    /** Encodes each segment of a path like "Reports/2026/Q4.xlsx" for use in a URL. */
    static String encPath(String path) {
        StringJoiner j = new StringJoiner("/");
        for (String part : path.replace('\\', '/').split("/")) if (!part.isEmpty()) j.add(enc(part));
        return j.toString();
    }

    static String query(Map<String, Object> q) {
        StringJoiner j = new StringJoiner("&");
        q.forEach((k, v) -> { if (v != null) j.add(enc(k) + "=" + enc(Values.text(v))); });
        return j.toString();
    }

    /** multipart/form-data or multipart/related body. Returns [body bytes, content type]. */
    static Object[] multipart(String subtype, List<Object[]> parts) {
        String boundary = "ooa" + UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            for (Object[] p : parts) {  // {headers string, byte[] content}
                out.write(("--" + boundary + "\r\n" + p[0] + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((byte[]) p[1]);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(e); }
        return new Object[]{out.toByteArray(), "multipart/" + subtype + "; boundary=" + boundary};
    }

    static List<String> addressList(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof List<?> l) for (Object o : l) out.add(Values.text(o).trim());
        else for (String s : Values.text(v).split("[,;]")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    static String safeName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").strip();
        return n.isEmpty() || n.equals(".") || n.equals("..") ? "file" : n;
    }
}
