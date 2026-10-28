package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The runner through a proxy: a fake Command Center at a host name only the proxy knows, behind a proxy that asks for a
 * user name and password; local addresses skip the proxy; settings survive the config file.
 */
class NetworkTest {
    HttpServer cc, proxy;
    final List<String> seen = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        cc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cc.createContext("/", ex -> {
            byte[] b = JsonLite.write(Map.of("device_id", "dev_1", "secret", "s", "name", "R1")).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        cc.start();
        // forwards http://cc.test:<port>/... to the fake Command Center once the client signs in as proxyuser
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Proxy-Authorization");
            seen.add(ex.getRequestURI() + " " + auth);
            if (!("Basic " + Base64.getEncoder().encodeToString("proxyuser:Pr0xy-pass".getBytes(StandardCharsets.UTF_8))).equals(auth)) {
                ex.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"test\"");
                ex.sendResponseHeaders(407, -1); ex.close(); return;
            }
            try {
                HttpResponse<byte[]> r = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cc.getAddress().getPort() + ex.getRequestURI().getPath()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(ex.getRequestBody().readAllBytes())).build(), HttpResponse.BodyHandlers.ofByteArray());
                ex.sendResponseHeaders(r.statusCode(), r.body().length); ex.getResponseBody().write(r.body());
            } catch (InterruptedException e) { ex.sendResponseHeaders(502, -1); }
            ex.close();
        });
        proxy.start();
    }

    @AfterEach void stop() { cc.stop(0); proxy.stop(0); }

    String ccUrl() { return "http://cc.test:" + cc.getAddress().getPort(); }
    String proxyUrl() { return "http://127.0.0.1:" + proxy.getAddress().getPort(); }

    @Test void throughAProxyThatAsksForASignIn() throws Exception {
        OoaException e = assertThrows(OoaException.class, () -> new AgentClient(ccUrl(), null, null, new Network(null, proxyUrl(), null, null)).post("register", Map.of()));
        assertTrue(e.getMessage().contains("407") || e.code().contains("407"), e.code() + " " + e.getMessage());
        Path dir = Files.createTempDirectory("ooa-proxy");
        Network net = new Network(null, proxyUrl(), "proxyuser", Network.protect("Pr0xy-pass", dir));
        assertFalse(net.proxyPassword().contains("Pr0xy-pass"), "the config never holds the password itself");
        if (Network.WINDOWS) assertTrue(net.proxyPassword().startsWith("dpapi:"), "Windows: encrypted with DPAPI");
        else {
            // Linux and macOS: in its own file that only the runner's account may read
            assertEquals("file:" + dir.resolve("proxy-password").toAbsolutePath(), net.proxyPassword());
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("proxy-password"))));
        }
        Map<String, Object> r = new AgentClient(ccUrl(), null, null, net).post("register", Map.of("key", "k"));
        assertEquals("dev_1", r.get("device_id"));
        assertTrue(seen.stream().anyMatch(s -> s.startsWith("http://cc.test:") && s.contains("/api/agent/register")), seen.toString());
    }

    @Test void aCommandCenterOnThisComputerSkipsTheProxy() {
        Network viaProxy = new Network(null, proxyUrl(), null, null);
        Map<String, Object> r = new AgentClient("http://127.0.0.1:" + cc.getAddress().getPort(), null, null, viaProxy).post("register", Map.of());
        assertEquals("dev_1", r.get("device_id"));
        assertTrue(seen.isEmpty(), "the proxy was not used: " + seen);
    }

    @Test void settingsAreCheckedAndKeptInTheConfigFile() throws Exception {
        assertEquals("AGENT.BAD_PROXY", assertThrows(OoaException.class, () -> new Network(null, "ftp://x", null, null)).code());
        assertEquals("system", new Network(null, null, null, null).proxy());
        Map<String, Object> cfg = new LinkedHashMap<>(Map.of("url", "http://x", "device_id", "d", "secret", "s"));
        new Network(Path.of("ca.cer"), "http://proxy.corp:8080", "u", "dpapi:abc").into(cfg);
        Network back = Network.from(cfg);
        assertEquals("http://proxy.corp:8080", back.proxy());
        assertEquals("u", back.proxyUser());
        assertEquals("dpapi:abc", back.proxyPassword());
        Network.DEFAULT.into(cfg);
        assertFalse(cfg.containsKey("proxy") || cfg.containsKey("ca"), "defaults are not written");

        Path file = Files.createTempDirectory("ooa-net").resolve("agent.json");
        Files.writeString(file, JsonLite.write(Map.of("url", "http://x", "device_id", "d", "secret", "s", "name", "R")));
        Map<String, Object> shown = Agent.network(file, "none", null, null, null);
        assertEquals("none", shown.get("proxy"));
        assertEquals("none", Network.from(castMap(JsonLite.parse(Files.readString(file)))).proxy());
        assertEquals("d", castMap(JsonLite.parse(Files.readString(file))).get("device_id"), "the runner's credentials stay");
        assertNotNull(Network.trust(null), "Java's and Windows' certificate authorities load");
    }

    @SuppressWarnings("unchecked") static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }
}
