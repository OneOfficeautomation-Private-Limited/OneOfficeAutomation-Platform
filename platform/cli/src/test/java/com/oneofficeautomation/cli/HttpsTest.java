package com.oneofficeautomation.cli;

import com.oneofficeautomation.agent.Agent;
import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Built-in HTTPS: "ooa tls self-signed" makes a certificate, the Command Center serves HTTPS with it, the certificate
 * exported by "ooa tls export-cert" lets a runner register ("--ca"), a runner without it is refused, and this
 * machine's own runner and Studio use the plain loopback listener.
 */
class HttpsTest {

    @SuppressWarnings("unchecked")
    @Test void commandCenterServesHttpsAndRunnersTrustTheExportedCertificate() throws Exception {
        Path data = Files.createTempDirectory("ooa-https"), agentHome = Files.createTempDirectory("ooa-https-agent");
        Path tlsFile = TlsConfig.defaultFile(data);
        assertEquals(0, TlsCommand.selfSigned(tlsFile, data, Map.of("host", "ooa-test.example.com", "days", "30")));
        String props = Files.readString(tlsFile);
        assertTrue(props.contains("password=enc\\:") || props.contains("password=enc:"), "the keystore password is stored encrypted: " + props);

        Vault vault = new Vault(data);
        TlsConfig cfg = TlsConfig.load(tlsFile);
        Map<String, Object> d = cfg.describe(vault);
        assertEquals(Boolean.TRUE, d.get("self_signed"));
        assertTrue(((List<String>) d.get("names")).containsAll(List.of("ooa-test.example.com", "localhost", "127.0.0.1")), String.valueOf(d.get("names")));

        Path cer = data.resolve("ooa.cer");
        assertEquals(0, TlsCommand.exportCert(tlsFile, data, Map.of("out", cer.toString())));
        String pem = Files.readString(cer);
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----") && !pem.contains("PRIVATE"), pem);

        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));

        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0).tls(cfg.sslContext(vault));
        String internal = server.internalUrl();
        server.start();
        try {
            assertTrue(server.https());
            String base = "https://localhost:" + server.port();

            // a browser or client that trusts the exported certificate gets the console over HTTPS, with HSTS
            HttpClient trusting = HttpClient.newBuilder().sslContext(trust(cer)).build();
            HttpResponse<String> h = trusting.send(HttpRequest.newBuilder(URI.create(base + "/api/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, h.statusCode(), h.body());
            assertTrue(h.headers().firstValue("Strict-Transport-Security").isPresent(), "HSTS over HTTPS");
            HttpResponse<String> page = trusting.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());

            // plain HTTP on the HTTPS port does not work
            assertThrows(Exception.class, () -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/health")).build(), HttpResponse.BodyHandlers.ofString()));

            // a client that does not trust the certificate is refused
            assertThrows(SSLHandshakeException.class, () -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/health")).build(), HttpResponse.BodyHandlers.ofString()));

            // this machine's own runner and Studio: plain HTTP on 127.0.0.1 only
            assertTrue(internal.startsWith("http://127.0.0.1:") && !internal.endsWith(":" + server.port()), internal);
            assertEquals(200, HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(internal + "/api/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());

            // runners: without the certificate registration fails; with --ca it works and is remembered for later calls
            String key1 = (String) cr.createRegistrationKey(admin, 2, null).get("key");
            OoaException e = assertThrows(OoaException.class, () -> Agent.register(base, key1, "Runner-NoCA", agentHome.resolve("noca").resolve("agent.json")));
            assertEquals("AGENT.UNREACHABLE", e.code());
            Path conf = agentHome.resolve("ca").resolve("agent.json");
            Map<String, Object> r = Agent.register(base, key1, "Runner-CA", conf, cer);
            assertEquals("Runner-CA", r.get("name"));
            Map<String, Object> saved = (Map<String, Object>) JsonLite.parse(Files.readString(conf));
            assertEquals(conf.resolveSibling("ca.cer").toString(), saved.get("ca"));
            assertTrue(Files.exists(conf.resolveSibling("ca.cer")));
            new Agent(conf, agentHome.resolve("ws"));   // reads the CA from its config
        } finally {
            server.stop();
            db.close();
        }

        // ooa tls off keeps the settings aside
        assertEquals(0, TlsCommand.off(tlsFile));
        assertFalse(Files.exists(tlsFile));
        assertTrue(Files.exists(tlsFile.resolveSibling("ooa-tls.properties.off")));
    }

    static SSLContext trust(Path cer) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (var in = Files.newInputStream(cer)) { ks.setCertificateEntry("ooa", CertificateFactory.getInstance("X.509").generateCertificate(in)); }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }
}
