package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** HTTP client for the Control Room agent API, authenticated with the device id and secret. */
final class AgentClient {
    private final HttpClient http;
    /** Round 4 B2: the proxy setting windows[:http://host:port] sends every request through WinHttp (Windows sign-in to the proxy). */
    private final String windowsProxy;
    final String baseUrl; private final String deviceId;
    /** Changes when the runner rotates its secret. */
    private volatile String secret;

    AgentClient(String baseUrl, String deviceId, String secret) { this(baseUrl, deviceId, secret, Network.DEFAULT); }

    /** {@code network}: the proxy and the certificates to trust (see {@link Network}). */
    AgentClient(String baseUrl, String deviceId, String secret, Network network) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.deviceId = deviceId; this.secret = secret;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        network.apply(b, this.baseUrl);
        this.http = b.build();
        URI target = URI.create(this.baseUrl);
        boolean local = target.getHost() != null && (target.getHost().equals("localhost") || target.getHost().startsWith("127.") || target.getHost().equals("[::1]"));
        this.windowsProxy = network.viaWindows() && !local ? network.proxy() : null;
    }

    /** One request: through Java's client, or through WinHttp when the proxy wants Windows sign-in. */
    private HttpResponse<byte[]> exchange(HttpRequest req, byte[] body) throws IOException, InterruptedException {
        if (windowsProxy == null) return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        Map<String, String> h = new java.util.LinkedHashMap<>();
        req.headers().map().forEach((k, v) -> { if (!v.isEmpty()) h.put(k, v.get(0)); });
        int timeout = (int) req.timeout().orElse(Duration.ofSeconds(60)).toMillis();
        WinHttp.Response r = WinHttp.send(windowsProxy, req.method(), req.uri().toString(), h, body, timeout);
        return new HttpResponse<>() {
            public int statusCode() { return r.status(); }
            public HttpRequest request() { return req; }
            public java.util.Optional<HttpResponse<byte[]>> previousResponse() { return java.util.Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public byte[] body() { return r.body(); }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public URI uri() { return req.uri(); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    void secret(String s) { this.secret = s; }

    /** A GET on the runner API: the JSON answer, or the raw bytes (a program file for an update). */
    @SuppressWarnings("unchecked")
    Map<String, Object> getJson(String path) { return (Map<String, Object>) com.oneofficeautomation.sdk.JsonLite.parse(new String(getBytes(path), java.nio.charset.StandardCharsets.UTF_8)); }

    byte[] getBytes(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/agent/" + path)).timeout(Duration.ofMinutes(5)).GET();
        if (deviceId != null) b.header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", secret);
        try {
            HttpResponse<byte[]> r = exchange(b.build(), null);
            if (r.statusCode() >= 400) throw new OoaException("AGENT.HTTP_" + r.statusCode(), "One CommandCenter answered HTTP " + r.statusCode() + " for " + path, "Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall).");
            return r.body();
        } catch (IOException e) { throw new OoaException("AGENT.UNREACHABLE", "Cannot reach One CommandCenter at " + baseUrl + ": " + e.getMessage(), "Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall).", true, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("AGENT.INTERRUPTED", "Interrupted", "Try again; if it keeps happening, an administrator can look in the server log."); }
    }

    /** A file to the runner API as multipart form data (field "files"), with a JSON answer; up to 15 minutes. */
    @SuppressWarnings("unchecked")
    Map<String, Object> postFile(String path, String fileName, byte[] data) {
        String boundary = "ooa" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + fileName.replaceAll("[\\r\\n\"]", "_")
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + data.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(data, 0, body, head.length, data.length);
        System.arraycopy(tail, 0, body, head.length + data.length, tail.length);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/agent/" + path)).timeout(Duration.ofMinutes(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (deviceId != null) b.header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", secret);
        Text r;
        try { r = Text.of(exchange(b.build(), body)); }
        catch (IOException e) { throw new OoaException("AGENT.UNREACHABLE", "Cannot reach Command Center at " + baseUrl + ": " + e.getMessage(), "Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall).", true, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("AGENT.INTERRUPTED", "Interrupted", "Try again; if it keeps happening, an administrator can look in the server log."); }
        Object parsed;
        try { parsed = JsonLite.parse(r.body()); } catch (IllegalArgumentException e) { throw new OoaException("AGENT.BAD_RESPONSE", "Command Center returned HTTP " + r.statusCode(), "Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall)."); }
        Map<String, Object> m = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        if (r.statusCode() >= 400) {
            Map<String, Object> e = m.get("error") instanceof Map ? (Map<String, Object>) m.get("error") : Map.of();
            throw new OoaException(String.valueOf(e.getOrDefault("code", "AGENT.HTTP_" + r.statusCode())), String.valueOf(e.getOrDefault("message", "HTTP " + r.statusCode())),
                    (String) e.get("suggestedFix"));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> post(String path, Object body) {
        byte[] json = JsonLite.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/agent/" + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json));
        if (deviceId != null) b.header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", secret);
        Text r;
        try { r = Text.of(exchange(b.build(), json)); }
        catch (IOException e) { throw new OoaException("AGENT.UNREACHABLE", "Cannot reach One CommandCenter at " + baseUrl + ": " + e.getMessage(), "Check the URL and network; the agent keeps retrying.", true, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("AGENT.INTERRUPTED", "Interrupted", "Try again; if it keeps happening, an administrator can look in the server log."); }
        Object parsed;
        try { parsed = JsonLite.parse(r.body()); } catch (IllegalArgumentException e) { throw new OoaException("AGENT.BAD_RESPONSE", "One CommandCenter returned HTTP " + r.statusCode(), "Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall)."); }
        Map<String, Object> m = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        if (r.statusCode() >= 400) {
            Map<String, Object> e = m.get("error") instanceof Map ? (Map<String, Object>) m.get("error") : Map.of();
            throw new OoaException(String.valueOf(e.getOrDefault("code", "AGENT.HTTP_" + r.statusCode())), String.valueOf(e.getOrDefault("message", "HTTP " + r.statusCode())),
                    (String) e.get("suggestedFix"));
        }
        return m;
    }

    /** A response's status and its body as text (UTF-8). */
    private record Text(int statusCode, String body) {
        static Text of(HttpResponse<byte[]> r) { return new Text(r.statusCode(), new String(r.body(), java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
