package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Shared plumbing of the cloud AI toolkits (AWS AI services, Azure AI services, Google Cloud AI): one HTTP exchange
 * with the 429 wait, typed network errors and the service's own error message; input files read by path with a size
 * limit; text checked for length; and small helpers that turn JSON answers into Data Tables.
 */
final class CloudAi {
    private CloudAi() {}

    /** One answer: status, JSON (an empty map when the body is not a JSON object), raw text and headers. */
    record Answer(int status, Map<String, Object> json, String text, Map<String, List<String>> headers) {
        String header(String n) {
            for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(n) && !e.getValue().isEmpty()) return e.getValue().get(0);
            return null;
        }
    }

    /**
     * Sends one request. Network failures become PREFIX.TIMEOUT / PREFIX.UNREACHABLE (retryable); an HTTP 429 that says
     * when to come back is waited for (up to three times); any status of 400 or more goes to {@code errors}; an HTML
     * page where JSON was expected becomes PREFIX.BAD_RESPONSE.
     */
    static Answer send(ActionContext ctx, String prefix, String service, String method, URI uri, Map<String, String> headers, byte[] body,
                       int timeoutSeconds, Function<Answer, ActionException> errors) {
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach((k, v) -> { if (!k.equalsIgnoreCase("host")) b.header(k, v); });
        } catch (IllegalArgumentException e) {
            throw new ActionException(prefix + ".BAD_URL", "Invalid address: " + uri, "Check the endpoint given to Connect.");
        }
        ctx.info(service + ": " + method + " " + uri.getHost() + uri.getRawPath());
        HttpRequest request = b.build();
        HttpResponse<byte[]> r;
        for (int attempt = 0; ; attempt++) {
            try { r = HttpJson.CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray()); }
            catch (HttpTimeoutException e) { throw new ActionException(prefix + ".TIMEOUT", "No answer from " + service + " within " + timeoutSeconds + " s", "Try again later.", true); }
            catch (IOException e) { throw new ActionException(prefix + ".UNREACHABLE", "Cannot reach " + uri.getHost() + ": " + NetErrors.reason(e, null), "Check the endpoint, region, proxy and network access.", true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
            long wait = r.statusCode() == 429 && attempt < HttpJson.MAX_RATE_RETRIES ? HttpJson.retryAfterMillis(r.headers().firstValue("Retry-After").orElse(null), attempt) : -1;
            if (wait < 0) break;
            ctx.warn(service + " is rate limiting (HTTP 429); trying again in " + Math.max(1, Math.round(wait / 1000.0)) + " s");
            HttpJson.pause(ctx, wait);
        }
        String text = new String(r.body(), StandardCharsets.UTF_8);
        Object parsed = null;
        if (!text.isBlank()) try { parsed = JsonLite.parse(text); } catch (IllegalArgumentException ignored) { }
        @SuppressWarnings("unchecked") Map<String, Object> json = parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        Answer a = new Answer(r.statusCode(), json, text, r.headers().map());
        if (r.statusCode() >= 400) throw errors.apply(a);
        if (parsed == null && HttpJson.looksLikePage(text, a.header("Content-Type")))
            throw new ActionException(prefix + ".BAD_RESPONSE", uri.getHost() + " answered with a web page instead of JSON (often a proxy or sign-in page): " + HttpJson.snippet(text),
                    "Check the endpoint points at the service's API, and the proxy and network settings.", false, Map.of("status", BigDecimal.valueOf(r.statusCode())));
        return a;
    }

    /**
     * The usual typed error for an HTTP status: 401 AUTH_FAILED and 403 FORBIDDEN (with how to sign in properly),
     * 404 NOT_FOUND, 429 RATE_LIMITED and 5xx SERVER_ERROR (both retryable), anything else REQUEST_FAILED.
     */
    static ActionException statusError(String prefix, String service, int status, String serviceCode, String message, String signIn) {
        String code = status == 401 ? ".AUTH_FAILED" : status == 403 ? ".FORBIDDEN" : status == 404 ? ".NOT_FOUND"
                : status == 429 ? ".RATE_LIMITED" : status >= 500 ? ".SERVER_ERROR" : ".REQUEST_FAILED";
        String fix = status == 401 ? signIn
                : status == 403 ? "The key or account is not allowed to use this " + service + " feature, or the resource does not offer it. " + signIn
                : status == 404 ? "Check the endpoint, region, model or processor id."
                : status == 429 ? "Too many requests; try again later or raise the service's quota."
                : status >= 500 ? service + " had a problem; try again later." : null;
        return error(prefix + code, service, status, serviceCode, message, fix, status == 429 || status >= 500);
    }

    static ActionException error(String code, String service, int status, String serviceCode, String message, String fix, boolean retryable) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", BigDecimal.valueOf(status));
        if (serviceCode != null && !serviceCode.isBlank()) details.put("service_code", serviceCode);
        String msg = message == null || message.isBlank() ? "HTTP " + status : message.length() > 400 ? message.substring(0, 400) + "..." : message;
        return new ActionException(code, service + " (HTTP " + status + (serviceCode == null || serviceCode.isBlank() ? "" : ", " + serviceCode) + "): " + msg, fix, retryable, details);
    }

    /** First 300 characters of a body that is not the expected JSON error. */
    static String rawMessage(Answer a) {
        String t = a.text() == null ? "" : a.text().strip();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    // ----- inputs -----

    /** Reads an input file by path (relative to the workspace), with clear errors for no path, no file, an empty file or one too large. */
    static byte[] readFile(ActionContext ctx, String prefix, String path, long maxBytes, String what) {
        if (path == null || path.isBlank()) throw new ActionException(prefix + ".NO_FILE", "A file path is required", "Give the path of the " + what + " to read.");
        Path p = ctx.resolvePath(path.strip());
        if (!Files.isRegularFile(p)) throw new ActionException(prefix + ".FILE_NOT_FOUND", "File not found: " + p, "Check the path; relative paths start in the run's workspace.");
        try {
            long size = Files.size(p);
            if (size == 0) throw new ActionException(prefix + ".EMPTY_FILE", "The file is empty: " + p.getFileName(), "Check that the file was saved completely.");
            if (size > maxBytes)
                throw new ActionException(prefix + ".FILE_TOO_LARGE", p.getFileName() + " is " + megabytes(size) + "; this step sends at most " + megabytes(maxBytes),
                        "Make the " + what + " smaller (fewer pages, lower resolution), or split it.", false, Map.of("bytes", BigDecimal.valueOf(size)));
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ActionException(prefix + ".FILE_NOT_FOUND", "Cannot read " + p + ": " + e.getMessage(), "Check the path and that the file is not locked.");
        }
    }

    static String megabytes(long bytes) {
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1024L * 1024), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " MB";
    }

    /** The text to analyse: not empty and at most {@code maxBytes} UTF-8 bytes (or characters, when {@code chars}). */
    static String text(String prefix, String text, int max, boolean chars, String service) {
        if (text == null || text.isBlank()) throw new ActionException(prefix + ".NO_TEXT", "No text to analyse", "Give the text, e.g. from a variable.");
        int size = chars ? text.codePointCount(0, text.length()) : text.getBytes(StandardCharsets.UTF_8).length;
        if (size > max)
            throw new ActionException(prefix + ".TEXT_TOO_LONG", "The text is " + size + (chars ? " characters" : " bytes") + "; " + service + " takes at most " + max + " in one call",
                    "Split the text, e.g. by paragraph, and analyse each part.", false, Map.of("size", BigDecimal.valueOf(size)));
        return text;
    }

    /** Content type of an image or document file from its name. */
    static String mimeType(String path) {
        String n = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "image/tiff";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".heic") || n.endsWith(".heif")) return "image/heif";
        return "application/octet-stream";
    }

    // ----- answers -----

    static SecureString secret(Object v) { return v instanceof SecureString s && !s.reveal().isBlank() ? s : null; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) { return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }

    static List<Map<String, Object>> maps(Object v) { return HttpJson.maps(v); }

    static String str(Object v) { return v == null ? null : Values.text(v); }

    /** A number from JSON (numbers are BigDecimal; Google sends 64-bit numbers as strings), or null. */
    static BigDecimal num(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s && !s.isBlank()) try { return new BigDecimal(s.strip()); } catch (NumberFormatException ignored) { }
        return null;
    }

    static int intOf(Object v, int dflt) { BigDecimal d = num(v); return d == null ? dflt : d.intValue(); }

    static Map<String, Object> table(List<String> cols, List<Object> rows) { return Values.table(cols, rows); }

    static List<Object> row(Object... cells) { return new ArrayList<>(Arrays.asList(cells)); }

    /**
     * A Data Table from a grid of cells given as {row, column, rowSpan, columnSpan, text}. The first row becomes the
     * column names when {@code header} (blank or repeated names get "Column N"); a spanned cell fills every cell it covers.
     */
    static Map<String, Object> grid(List<Object[]> cells, int rowCount, int colCount, boolean header) {
        for (Object[] c : cells) { rowCount = Math.max(rowCount, (int) c[0] + (int) c[2]); colCount = Math.max(colCount, (int) c[1] + (int) c[3]); }
        String[][] g = new String[Math.max(rowCount, 0)][Math.max(colCount, 0)];
        for (Object[] c : cells) {
            int r0 = (int) c[0], c0 = (int) c[1];
            for (int r = r0; r < r0 + Math.max(1, (int) c[2]); r++)
                for (int k = c0; k < c0 + Math.max(1, (int) c[3]); k++)
                    if (r >= 0 && k >= 0 && r < g.length && k < g[r].length && g[r][k] == null) g[r][k] = c[4] == null ? "" : String.valueOf(c[4]).strip();
        }
        List<String> cols = new ArrayList<>();
        int start = 0;
        if (header && g.length > 0) {
            for (int k = 0; k < colCount; k++) {
                String n = g[0][k] == null ? "" : g[0][k].replaceAll("\\s+", " ").strip();
                if (n.isEmpty() || cols.contains(n)) n = "Column " + (k + 1);
                cols.add(n);
            }
            start = 1;
        } else for (int k = 0; k < colCount; k++) cols.add("Column " + (k + 1));
        List<Object> rows = new ArrayList<>();
        for (int r = start; r < g.length; r++) {
            List<Object> row = new ArrayList<>();
            for (int k = 0; k < colCount; k++) row.add(g[r][k] == null ? "" : g[r][k]);
            rows.add(row);
        }
        return Values.table(cols, rows);
    }

    /** Endpoint for one service: the override with {service} replaced, or the default address. */
    static String endpoint(String override, String service, String dflt) {
        if (override == null || override.isBlank()) return dflt;
        return override.strip().replace("{service}", service).replaceAll("/+$", "");
    }

    static void sessionName(String session, String dflt, Map<String, Object> sessions, Object value) {
        sessions.put(session == null || session.isBlank() ? dflt : session.strip(), value);
    }
}
