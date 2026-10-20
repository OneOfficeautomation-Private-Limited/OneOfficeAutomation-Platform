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

    /** Builder with URL, query, headers and authentication set the same way as Send request. */
    private static HttpRequest.Builder builder(String url, Map<String, Object> query, Map<String, Object> headers, String basicUser, Object basicPassword, Object bearer, int timeout) {
        StringBuilder u = new StringBuilder(url);
        if (query != null && !query.isEmpty()) u.append(url.contains("?") ? "&" : "?").append(encode(query));
        HttpRequest.Builder b;
        try { b = HttpRequest.newBuilder(URI.create(u.toString())).timeout(Duration.ofSeconds(Math.max(1, timeout))); }
        catch (IllegalArgumentException e) { throw new ActionException("REST.BAD_URL", "Invalid URL: " + url, "Check the address, e.g. https://api.example.com/orders."); }
        if (headers != null) headers.forEach((k, v) -> b.header(k, String.valueOf(v)));
        if (basicUser != null && basicPassword instanceof SecureString pw)
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((basicUser + ":" + pw.reveal()).getBytes(StandardCharsets.UTF_8)));
        if (bearer instanceof SecureString t) b.header("Authorization", "Bearer " + t.reveal());
        return b;
    }

    private static String masked(String url) { return url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****"); }

    private static <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler, String url, int timeout) {
        try { return CLIENT.send(req, handler); }
        catch (HttpTimeoutException e) { throw new ActionException("REST.TIMEOUT", "No response from " + url + " within " + timeout + " s", "Raise timeout_seconds or check the service.", true); }
        catch (IOException e) { throw new ActionException("REST.UNREACHABLE", "Cannot reach " + url + ": " + e.getMessage(), "Check the URL, proxy settings and network access.", true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Request interrupted"); }
    }

    private static ActionException httpError(String method, String url, int status, String body) {
        return new ActionException("REST.HTTP_ERROR", method + " " + url + " returned HTTP " + status,
                status == 401 || status == 403 ? "Check the credentials and permissions." : null,
                status == 429 || status >= 500, Map.of("status", BigDecimal.valueOf(status), "body", body.length() > 500 ? body.substring(0, 500) : body));
    }

    @Action(id = "download_file", name = "Download file", category = "HTTP", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Download a URL straight to a file (any file type, including large binaries), with the same headers and authentication as Send request.")
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
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Time allowed for the whole download.") int timeout) {
        HttpRequest req = builder(url, query, headers, basicUser, basicPassword, bearer, timeout).GET().build();
        ctx.info("GET " + masked(url) + " (download)");
        java.nio.file.Path target = ctx.resolvePath(path);
        java.nio.file.Path folder = java.nio.file.Files.isDirectory(target) ? target : target.getParent();
        java.nio.file.Path temp;
        try {
            if (folder != null) java.nio.file.Files.createDirectories(folder);
            temp = java.nio.file.Files.createTempFile(folder == null ? java.nio.file.Path.of(".") : folder, ".ooa-download-", ".part");
        } catch (IOException e) { throw new ActionException("REST.FILE_ERROR", "Cannot write into " + folder + ": " + e.getMessage(), "Check the folder exists and is writable."); }
        try {
            HttpResponse<java.nio.file.Path> resp = send(req, HttpResponse.BodyHandlers.ofFile(temp), url, timeout);
            int status = resp.statusCode();
            if (status >= 400) {
                String body;
                try { byte[] b = java.nio.file.Files.readAllBytes(temp); body = new String(b, 0, Math.min(b.length, 2000), StandardCharsets.UTF_8); } catch (IOException e) { body = ""; }
                throw httpError("GET", url, status, body);
            }
            if (java.nio.file.Files.isDirectory(target)) {
                String name = resp.headers().firstValue("Content-Disposition").map(RestPackage::dispositionName)
                        .orElseGet(() -> { String p = resp.uri().getPath(); return p == null ? "" : p.substring(p.lastIndexOf('/') + 1); });
                target = target.resolve(HttpJson.safeName(java.net.URLDecoder.decode(name.isBlank() ? "download" : name, StandardCharsets.UTF_8)));
            }
            if (java.nio.file.Files.exists(target) && !overwrite)
                throw new ActionException("REST.FILE_EXISTS", "File already exists: " + target, "Set overwrite to true or choose another path.");
            java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Out.of("path", target.toString(), "status", BigDecimal.valueOf(status), "size_bytes", BigDecimal.valueOf(java.nio.file.Files.size(target)),
                    "content_type", resp.headers().firstValue("Content-Type").orElse(""));
        } catch (IOException e) {
            throw new ActionException("REST.FILE_ERROR", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open and check the folder is writable.", true);
        } finally {
            try { java.nio.file.Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    static String dispositionName(String header) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)filename\\*=(?:UTF-8'')?([^;]+)|filename=\"?([^\";]+)\"?").matcher(header);
        String found = "";
        while (m.find()) { if (m.group(1) != null) return m.group(1).strip(); if (found.isEmpty()) found = m.group(2).strip(); }
        return found;
    }

    @Action(id = "upload_file", name = "Upload file", category = "HTTP", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Send a file to a URL as a multipart form upload, optionally with extra form fields.")
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
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Response timeout.") int timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Raise REST.HTTP_ERROR on 4xx/5xx.") boolean failOnError) {
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
        HttpResponse<String> resp = send(b.build(), HttpResponse.BodyHandlers.ofString(), url, timeout);
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
