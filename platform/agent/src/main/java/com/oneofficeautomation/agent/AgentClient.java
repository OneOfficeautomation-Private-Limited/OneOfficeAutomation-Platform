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
    final String baseUrl; private final String deviceId, secret;

    AgentClient(String baseUrl, String deviceId, String secret) { this(baseUrl, deviceId, secret, Network.DEFAULT); }

    /** {@code network}: the proxy and the certificates to trust (see {@link Network}). */
    AgentClient(String baseUrl, String deviceId, String secret, Network network) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.deviceId = deviceId; this.secret = secret;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        network.apply(b, this.baseUrl);
        this.http = b.build();
    }

    /** A GET on the runner API: the JSON answer, or the raw bytes (a program file for an update). */
    @SuppressWarnings("unchecked")
    Map<String, Object> getJson(String path) { return (Map<String, Object>) com.oneofficeautomation.sdk.JsonLite.parse(new String(getBytes(path), java.nio.charset.StandardCharsets.UTF_8)); }

    byte[] getBytes(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/agent/" + path)).timeout(Duration.ofMinutes(5)).GET();
        if (deviceId != null) b.header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", secret);
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() >= 400) throw new OoaException("AGENT.HTTP_" + r.statusCode(), "Command Center answered HTTP " + r.statusCode() + " for " + path);
            return r.body();
        } catch (IOException e) { throw new OoaException("AGENT.UNREACHABLE", "Cannot reach Command Center at " + baseUrl + ": " + e.getMessage(), null, true, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("AGENT.INTERRUPTED", "Interrupted"); }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> post(String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/agent/" + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(body)));
        if (deviceId != null) b.header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", secret);
        HttpResponse<String> r;
        try { r = http.send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException e) { throw new OoaException("AGENT.UNREACHABLE", "Cannot reach Command Center at " + baseUrl + ": " + e.getMessage(), "Check the URL and network; the agent keeps retrying.", true, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("AGENT.INTERRUPTED", "Interrupted"); }
        Object parsed;
        try { parsed = JsonLite.parse(r.body()); } catch (IllegalArgumentException e) { throw new OoaException("AGENT.BAD_RESPONSE", "Command Center returned HTTP " + r.statusCode()); }
        Map<String, Object> m = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        if (r.statusCode() >= 400) {
            Map<String, Object> e = m.get("error") instanceof Map ? (Map<String, Object>) m.get("error") : Map.of();
            throw new OoaException(String.valueOf(e.getOrDefault("code", "AGENT.HTTP_" + r.statusCode())), String.valueOf(e.getOrDefault("message", "HTTP " + r.statusCode())),
                    (String) e.get("suggestedFix"));
        }
        return m;
    }
}
