package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.*;

/** HTTP client for the Control Room agent API, authenticated with the device id and secret. */
final class AgentClient {
    private final HttpClient http;
    final String baseUrl; private final String deviceId, secret;

    AgentClient(String baseUrl, String deviceId, String secret) { this(baseUrl, deviceId, secret, null); }

    /** {@code ca}: a certificate file (PEM or DER) trusted in addition to the system's, for a Command Center with a self-signed or company-CA certificate. */
    AgentClient(String baseUrl, String deviceId, String secret, Path ca) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.deviceId = deviceId; this.secret = secret;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (ca != null) b.sslContext(trusting(ca));
        this.http = b.build();
    }

    /** A TLS context that trusts the JDK's certificate authorities plus the certificates in {@code ca}. */
    static SSLContext trusting(Path ca) {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            int n = 0;
            for (TrustManager tm : sys.getTrustManagers())
                if (tm instanceof X509TrustManager x) for (X509Certificate c : x.getAcceptedIssuers()) ks.setCertificateEntry("sys" + n++, c);
            try (var in = Files.newInputStream(ca)) {
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) ks.setCertificateEntry("ooa" + n++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new OoaException("AGENT.CA", "Cannot use the certificate file " + ca + ": " + e.getMessage(), "Export it on the Command Center with: ooa tls export-cert --out ooa.cer");
        }
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
