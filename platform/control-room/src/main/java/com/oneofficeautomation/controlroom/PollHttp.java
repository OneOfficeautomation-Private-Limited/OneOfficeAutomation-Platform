package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** HTTP for server-side trigger pollers: JSON GET and form POST with a timeout and readable errors. */
final class PollHttp {
    private PollHttp() {}
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    static final int MAX_BODY = 5 * 1024 * 1024;

    static Object getJson(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().header("Accept", "application/json");
        headers.forEach(b::header);
        return send(b.build(), url);
    }

    static Object postForm(String url, Map<String, String> form) throws IOException {
        StringJoiner j = new StringJoiner("&");
        form.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(j.toString())).build(), url);
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static Object send(HttpRequest req, String url) throws IOException {
        HttpResponse<byte[]> r;
        try { r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
        if (r.body().length > MAX_BODY) throw new IOException("The response from " + host(url) + " is larger than 5 MB");
        String text = new String(r.body(), StandardCharsets.UTF_8);
        Object json;
        try { json = text.isBlank() ? null : JsonLite.parse(text); } catch (IllegalArgumentException e) { json = null; }
        if (r.statusCode() >= 400) {
            String detail = json instanceof Map<?, ?> m ? errorText(m) : text.length() > 200 ? text.substring(0, 200) : text;
            throw new IOException("HTTP " + r.statusCode() + " from " + host(url) + (detail.isBlank() ? "" : ": " + detail)
                    + (r.statusCode() == 401 ? " (check the credential in the vault)" : ""));
        }
        if (json == null && !text.isBlank()) throw new IOException(host(url) + " did not return JSON");
        return json;
    }

    private static String errorText(Map<?, ?> m) {
        Object e = m.get("error");
        if (e instanceof Map<?, ?> em) return String.valueOf(em.get("message") != null ? em.get("message") : em);
        if (e != null) return String.valueOf(m.get("error_description") != null ? m.get("error_description") : e);
        return m.get("message") == null ? "" : String.valueOf(m.get("message"));
    }

    static String host(String url) { try { return URI.create(url).getHost(); } catch (RuntimeException e) { return url; } }
}
