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
    /**
     * A value on its way to a server: a vault secret as the secret itself, anything else as text (R1, command review
     * batch 8). Turning a secret into text gives its mask, so a header taken from the vault used to be sent as ****.
     * The run still masks the secret in logs and run output.
     */
    static String sendable(Object v) {
        if (v instanceof SecureString s) return s.reveal();
        return String.valueOf(v);
    }

    /** A JSON value, map or list with every vault secret inside it replaced by the secret, for a request body. */
    static Object revealed(Object v) {
        if (v instanceof SecureString s) return s.reveal();
        if (v instanceof Map<?, ?> m) { Map<String, Object> out = new LinkedHashMap<>(); m.forEach((k, x) -> out.put(String.valueOf(k), revealed(x))); return out; }
        if (v instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(revealed(x)); return out; }
        return v;
    }

    /** Address pattern: https for real servers; plain http only for this computer (local test and mock servers). */
    static final String HTTPS_OR_LOCAL = "^(https://|http://(localhost|127\\.0\\.0\\.1)(:|/|$))";

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
        int timeout = 60; boolean binary, allowText;
        Req(String prefix, String method, String url) { this.prefix = prefix; this.method = method; this.url = url; }
        Req bearer(Object token) {
            if (!(token instanceof SecureString s)) throw new ActionException(prefix + ".NO_TOKEN", "An access token from the vault is required", "Map access_token to a Credential variable.");
            headers.put("Authorization", "Bearer " + s.reveal()); return this;
        }
        Req basic(String user, Object password) {
            if (!(password instanceof SecureString s)) throw new ActionException(prefix + ".NO_PASSWORD", "A password or API token from the vault is required");
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + s.reveal()).getBytes(StandardCharsets.UTF_8))); return this;
        }
        /** Authorization header with a custom scheme, e.g. "SSWS" for Okta API tokens. */
        Req scheme(String scheme, Object token) {
            if (!(token instanceof SecureString s)) throw new ActionException(prefix + ".NO_TOKEN", "An access token from the vault is required", "Map the token input to a Credential variable.");
            headers.put("Authorization", scheme + " " + s.reveal()); return this;
        }
        Req header(String k, String v) { headers.put(k, v); return this; }
        Req json(Object value) { body = HttpRequest.BodyPublishers.ofString(JsonLite.write(Values.toJsonable(value))); headers.put("Content-Type", "application/json"); return this; }
        Req bytes(byte[] data, String contentType) { body = HttpRequest.BodyPublishers.ofByteArray(data); headers.put("Content-Type", contentType); return this; }
        Req binaryResponse() { binary = true; return this; }
        /** K3 (batch 10): the body read from a file as it is sent, so a large upload is not held in memory. */
        Req file(java.nio.file.Path p, String contentType) {
            try { body = HttpRequest.BodyPublishers.ofFile(p); }
            catch (java.io.FileNotFoundException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
            headers.put("Content-Type", contentType); return this;
        }

        /**
         * K3 (command review, batch 10): writes the answer straight to a file (through a temporary file next to it, moved
         * into place when complete), so a download of any size does not pass through memory. Returns its size in bytes.
         * An error answer is read as text and reported as by send.
         */
        long download(ActionContext ctx, java.nio.file.Path target) {
            HttpRequest.Builder b;
            try { b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout)).method(method, body); }
            catch (IllegalArgumentException e) { throw new ActionException(prefix + ".BAD_URL", "Invalid URL: " + url, "Check the instance or base URL."); }
            headers.putIfAbsent("Accept", "*/*");
            headers.forEach(b::header);
            ctx.info(method + " " + url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****") + " (to a file)");
            java.nio.file.Path dir = target.toAbsolutePath().getParent(), tmp;
            try { java.nio.file.Files.createDirectories(dir); tmp = java.nio.file.Files.createTempFile(dir, ".ooa-download-", ".part"); }
            catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write in " + dir + ": " + e.getMessage(), "Check the folder and its permissions.", true); }
            try {
                HttpResponse<java.nio.file.Path> r;
                for (int attempt = 0; ; attempt++) {
                    try { r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofFile(tmp)); }
                    catch (HttpTimeoutException e) { throw new ActionException(prefix + ".TIMEOUT", "No response within " + timeout + " s from " + host(url), "Try again later or raise the timeout.", true); }
                    catch (IOException e) { throw new ActionException(prefix + ".UNREACHABLE", "Cannot reach " + host(url) + ": " + NetErrors.reason(e, null), "Check the URL, proxy and network access.", true); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
                    long wait = r.statusCode() == 429 && attempt < MAX_RATE_RETRIES ? retryAfterMillis(r.headers().firstValue("Retry-After").orElse(null), attempt) : -1;
                    if (wait < 0) break;
                    ctx.warn(host(url) + " is rate limiting (HTTP 429); trying again in " + Math.max(1, Math.round(wait / 1000.0)) + " s (" + (attempt + 1) + " of " + MAX_RATE_RETRIES + ")");
                    pause(ctx, wait);
                }
                if (r.statusCode() >= 400) {
                    byte[] err = java.nio.file.Files.size(tmp) > 1_000_000 ? new byte[0] : java.nio.file.Files.readAllBytes(tmp);
                    String text = new String(err, StandardCharsets.UTF_8);
                    Object parsed = null;
                    if (!text.isBlank()) try { parsed = JsonLite.parse(text); } catch (IllegalArgumentException ignored) { }
                    throw error(prefix, new Response(r.statusCode(), text, parsed, err, r.headers().map()));
                }
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return java.nio.file.Files.size(target);
            } catch (IOException e) {
                throw new ActionException("FILE.IO_ERROR", "Cannot write " + target + ": " + e.getMessage(), "Check the folder, its permissions and free space.", true);
            } finally {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
        /** The caller reads the answer as text and checks it itself (an HTML answer is then not an error here). */
        Req allowText() { allowText = true; return this; }
        Req timeout(int s) { timeout = s; return this; }

        Response send(ActionContext ctx) {
            HttpRequest.Builder b;
            try { b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout)).method(method, body); }
            catch (IllegalArgumentException e) { throw new ActionException(prefix + ".BAD_URL", "Invalid URL: " + url, "Check the instance or base URL."); }
            headers.putIfAbsent("Accept", "application/json");
            headers.forEach(b::header);
            ctx.info(method + " " + url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****"));
            HttpRequest request = b.build();
            HttpResponse<byte[]> r;
            for (int attempt = 0; ; attempt++) {
                try { r = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray()); }
                catch (HttpTimeoutException e) { throw new ActionException(prefix + ".TIMEOUT", "No response within " + timeout + " s from " + host(url), "Try again later or raise the timeout.", true); }
                catch (IOException e) { throw new ActionException(prefix + ".UNREACHABLE", "Cannot reach " + host(url) + ": " + NetErrors.reason(e, null), "Check the URL, proxy and network access.", true); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
                // HTTP 429: the request was not processed, so it is sent again after the wait the service asks for
                long wait = r.statusCode() == 429 && attempt < MAX_RATE_RETRIES ? retryAfterMillis(r.headers().firstValue("Retry-After").orElse(null), attempt) : -1;
                if (wait < 0) break;
                ctx.warn(host(url) + " is rate limiting (HTTP 429); trying again in " + Math.max(1, Math.round(wait / 1000.0)) + " s (" + (attempt + 1) + " of " + MAX_RATE_RETRIES + ")");
                pause(ctx, wait);
            }
            String text = binary && r.statusCode() < 400 ? "" : new String(r.body(), StandardCharsets.UTF_8);
            Object parsed = null;
            if (!text.isBlank()) try { parsed = JsonLite.parse(text); } catch (IllegalArgumentException ignored) { }
            Response resp = new Response(r.statusCode(), text, parsed, r.body(), r.headers().map());
            if (r.statusCode() >= 400) throw error(prefix, resp);
            if (!binary && !allowText && parsed == null && wantsJson(headers.get("Accept")) && looksLikePage(text, resp.header("Content-Type")))
                // a proxy or sign-in page answered instead of the API: it used to "succeed" with empty values
                throw new ActionException(prefix + ".BAD_RESPONSE", host(url) + " answered with a web page instead of JSON (often a proxy or sign-in page): " + snippet(text),
                        "Check the URL points at the service's API, and the proxy, network and sign-in settings.", false, Map.of("status", java.math.BigDecimal.valueOf(r.statusCode())));
            return resp;
        }
    }

    static final int MAX_RATE_RETRIES = 3;
    /** Longer waits than this are not done inside the step; the step fails with RATE_LIMITED (retryable) instead. */
    static final long MAX_RATE_WAIT_MS = 60_000;

    /**
     * Milliseconds to wait for a 429 that says when to come back (Retry-After in seconds or as an HTTP date); -1 when it
     * does not say, or asks for longer than a minute: the step then fails with RATE_LIMITED, which a retry policy can repeat.
     */
    static long retryAfterMillis(String retryAfter, int attempt) {
        if (retryAfter == null || retryAfter.isBlank()) return -1;
        String v = retryAfter.strip();
        long ms;
        try { ms = (long) (Double.parseDouble(v) * 1000); }
        catch (NumberFormatException e) {
            try { ms = java.time.Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis(); }
            catch (RuntimeException unreadable) { return -1; }
        }
        if (ms > MAX_RATE_WAIT_MS) return -1;
        return Math.max(ms, 200);
    }

    /** The request asked for JSON only (the default); XML and other answers asked for explicitly are the caller's to read. */
    static boolean wantsJson(String accept) {
        String a = accept == null ? "application/json" : accept.toLowerCase(Locale.ROOT);
        return a.contains("json") && !a.contains("xml") && !a.contains("*/*") && !a.contains("text/");
    }

    /** An HTML page (a proxy or sign-in page) rather than an API answer; XML answers of XML services are not pages. */
    static boolean looksLikePage(String text, String contentType) {
        if (text == null || text.isBlank()) return false;
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("text/html") || ct.contains("xhtml")) return true;
        String head = text.stripLeading().toLowerCase(Locale.ROOT);
        return !ct.contains("xml") && (head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") || head.startsWith("<body"));
    }

    /** The visible start of a page, without tags, for an error message. */
    static String snippet(String html) {
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
        return t.isEmpty() ? "(empty page)" : t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    static Req req(String prefix, String method, String url) { return new Req(prefix, method, plainHttpCheck(prefix, url)); }

    /**
     * K6 (command review, batch 10): toolkits whose addresses may be plain http for on-premises servers (Jira Data Center,
     * Azure DevOps Server, GitHub Enterprise, Asana's stand-ins) send tokens over http only to this computer, or to a
     * host the runner's administrator lists in OOA_ALLOW_PLAIN_HTTP (or -Dooa.allow_plain_http), comma separated.
     */
    static final Set<String> HTTPS_UNLESS_ALLOWED = Set.of("JIRA", "ASANA", "AZURE_DEVOPS", "GITHUB");

    static String plainHttpCheck(String prefix, String url) {
        if (url == null || !HTTPS_UNLESS_ALLOWED.contains(prefix) || !url.regionMatches(true, 0, "http://", 0, 7)) return url;
        String h = host(url);
        if (h == null || h.equalsIgnoreCase("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1")) return url;
        String allowed = System.getProperty("ooa.allow_plain_http", System.getenv().getOrDefault("OOA_ALLOW_PLAIN_HTTP", ""));
        for (String a : allowed.split(",")) if (!a.isBlank() && a.strip().equalsIgnoreCase(h)) return url;
        throw new ActionException(prefix + ".PLAIN_HTTP", "The address " + h + " is plain http, so the sign-in would travel unencrypted",
                "Use https://. For an on-premises server that only has http, an administrator adds " + h + " to OOA_ALLOW_PLAIN_HTTP on the runner.");
    }

    static String host(String url) { try { return URI.create(url).getHost(); } catch (Exception e) { return url; } }

    /** Builds a typed error carrying the service's own message (Graph, Google, Salesforce, ServiceNow and Jira formats). */
    static ActionException error(String prefix, Response r) {
        String msg = null;
        Object j = r.json();
        if (j instanceof Map<?, ?> m) {
            Object e = m.get("error");
            if (e instanceof Map<?, ?> em) msg = String.valueOf(em.get("message") != null ? em.get("message") : em.get("detail"));
            else if (e instanceof String s) msg = s + (m.get("error_description") != null ? ": " + m.get("error_description")
                    : m.get("description") != null ? ": " + m.get("description") : "");
            if (msg == null && m.get("errorMessages") instanceof List<?> l && !l.isEmpty()) msg = String.valueOf(l.get(0));
            if (msg == null && m.get("errors") instanceof Map<?, ?> em && !em.isEmpty()) msg = String.valueOf(em);
            String listed = m.get("errors") instanceof List<?> el && !el.isEmpty() && el.get(0) instanceof Map<?, ?> f0 && f0.get("message") != null
                    ? String.valueOf(f0.get("message")) : null;  // Asana and GitHub style: errors: [{message}]
            if (msg == null && m.get("errorSummary") != null) msg = String.valueOf(m.get("errorSummary"));  // Okta
            Object svcCode = m.get("errorCode") != null ? m.get("errorCode") : m.get("error_code");
            if (msg == null && m.get("message") != null) msg = (svcCode instanceof String sc ? sc + ": " : "") + m.get("message") + (listed != null ? ": " + listed : "");
            if (msg == null && listed != null) msg = listed;
        } else if (j instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> first && first.get("message") != null) msg = String.valueOf(first.get("message"));
        if (msg == null) msg = r.text().length() > 300 ? r.text().substring(0, 300) : r.text();
        int s = r.status();
        String code = s == 401 ? ".AUTH_FAILED" : s == 403 ? ".FORBIDDEN" : s == 404 ? ".NOT_FOUND" : s == 429 ? ".RATE_LIMITED" : s >= 500 ? ".SERVER_ERROR" : ".REQUEST_FAILED";
        String fix = s == 401 ? "The access token is missing, wrong or expired; refresh it in the vault."
                : s == 403 ? "The account or app lacks permission for this operation." : s == 404 ? "Check the id, name or path." : s == 429 ? "Too many requests; retry later." : null;
        return new ActionException(prefix + code, "HTTP " + s + ": " + msg, fix, s == 429 || s >= 500, Map.of("status", java.math.BigDecimal.valueOf(s)));
    }

    /** URL of the rel="next" entry in the Link response headers (RFC 8288), or null on the last page. */
    static String nextLink(Response r) {
        for (var e : r.headers().entrySet()) {
            if (!e.getKey().equalsIgnoreCase("Link")) continue;
            for (String v : e.getValue()) for (String part : v.split(",(?=\\s*<)")) {
                java.util.regex.Matcher mt = java.util.regex.Pattern.compile("<([^>]+)>\\s*;.*rel=\"?next\"?").matcher(part.trim());
                if (mt.find()) return mt.group(1);
            }
        }
        return null;
    }

    /** Waits between status checks; stops early if the run is cancelled. */
    static void pause(ActionContext ctx, long millis) {
        ctx.checkCancelled();
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        ctx.checkCancelled();
    }

    /** Reads a local file for upload, with a typed error when it is missing. */
    static byte[] readFile(java.nio.file.Path p) {
        try { return java.nio.file.Files.readAllBytes(p); }
        catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
    }

    /** Writes downloaded bytes, creating parent folders. */
    static void writeFile(java.nio.file.Path p, byte[] data) {
        try { if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent()); java.nio.file.Files.write(p, data); }
        catch (IOException e) { throw new ActionException("FILE.WRITE_FAILED", "Cannot write " + p + ": " + e.getMessage(), "Check the folder exists and is writable."); }
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

    /** Reads a dotted path such as "assignee.login" from nested maps; null when any part is missing. */
    static Object path(Object value, String dotted) {
        Object cur = value;
        for (String part : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
        }
        return cur;
    }

    /** Table from records. Each column is "name" or "name=dotted.path", e.g. "assignee=assignee.login". */
    static Map<String, Object> table(List<? extends Map<?, ?>> records, String... columns) {
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (String c : columns) {
            int i = c.indexOf('=');
            names.add(i < 0 ? c : c.substring(0, i));
            paths.add(i < 0 ? c : c.substring(i + 1));
        }
        List<Object> rows = new ArrayList<>();
        for (Map<?, ?> r : records) {
            List<Object> row = new ArrayList<>();
            for (String p : paths) row.add(path(r, p));
            rows.add(row);
        }
        return Values.table(names, rows);
    }

    /** Same host and scheme as the base URL, so paging links can never send a token elsewhere. */
    static boolean sameOrigin(String base, String next) {
        try {
            URI a = URI.create(base), b = URI.create(next);
            return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                    && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
        } catch (IllegalArgumentException e) { return false; }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    static byte[] readFile(ActionContext ctx, String path) {
        java.nio.file.Path p = ctx.resolvePath(path);
        try { return java.nio.file.Files.readAllBytes(p); }
        catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
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
