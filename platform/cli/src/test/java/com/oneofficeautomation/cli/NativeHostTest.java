package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The native-messaging host: framing, registration, the token check, request/response routing and events. */
class NativeHostTest {
    @SuppressWarnings("unchecked")
    @Test void relaysBetweenExtensionAndAgents() throws Exception {
        Path dir = Files.createTempDirectory("ooa-bridge");
        PipedOutputStream toHost = new PipedOutputStream();
        PipedInputStream hostIn = new PipedInputStream(toHost, 1 << 20);
        PipedInputStream fromHost = new PipedInputStream(1 << 20);
        PipedOutputStream hostOut = new PipedOutputStream(fromHost);
        NativeHost host = new NativeHost(hostIn, hostOut, dir);
        Thread t = new Thread(() -> { try { host.run(); } catch (IOException e) { throw new UncheckedIOException(e); } });
        t.start();

        // the extension says hello: the host registers itself
        NativeHost.writeMessage(toHost, "{\"event\":\"hello\",\"browser\":\"edge\",\"version\":\"1.0.0\"}");
        Path reg = null;
        for (int i = 0; i < 50 && reg == null; i++) { try (var s = Files.list(dir)) { reg = s.filter(p -> p.toString().endsWith(".json")).findFirst().orElse(null); } Thread.sleep(50); }
        assertNotNull(reg, "registration file written");
        Map<String, Object> r = (Map<String, Object>) JsonLite.parse(Files.readString(reg));
        assertEquals("edge", r.get("browser"));
        int port = ((Number) r.get("port")).intValue();

        // a wrong token is turned away
        try (Socket bad = new Socket(InetAddress.getLoopbackAddress(), port)) {
            bad.getOutputStream().write("{\"token\":\"nope\"}\n".getBytes(StandardCharsets.UTF_8));
            bad.setSoTimeout(2000);
            assertEquals(-1, bad.getInputStream().read(), "connection closed");
        }

        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            w.write("{\"token\":\"" + r.get("token") + "\"}\n"); w.flush();
            assertTrue(in.readLine().contains("\"connected\":true"));

            // a request reaches the extension with the host's own id, and the answer comes back with the agent's id
            w.write("{\"id\":\"a1\",\"op\":\"list_tabs\",\"args\":{}}\n"); w.flush();
            Map<String, Object> req = (Map<String, Object>) JsonLite.parse(NativeHost.readMessage(fromHost));
            assertEquals("list_tabs", req.get("op"));
            NativeHost.writeMessage(toHost, "{\"id\":" + req.get("id") + ",\"ok\":true,\"result\":[{\"title\":\"Web form\"}]}");
            Map<String, Object> resp = (Map<String, Object>) JsonLite.parse(in.readLine());
            assertEquals("a1", resp.get("id"));
            assertEquals(true, resp.get("ok"));

            // events from the extension go to every connected agent
            NativeHost.writeMessage(toHost, "{\"event\":\"picked\",\"token\":\"p1\",\"tag\":\"input\"}");
            assertTrue(in.readLine().contains("\"picked\""));
        }

        // the browser closing ends the host and removes the registration
        toHost.close();
        t.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(t.isAlive());
        assertFalse(Files.exists(reg), "registration removed");
        assertTrue(BrowserConnector.hostManifest(Path.of("C:/x/host.bat")).contains("chrome-extension://" + BrowserConnector.EXTENSION_ID + "/"));
        // Firefox allows the extension by its id
        String ff = BrowserConnector.firefoxHostManifest(Path.of("C:/x/host.bat"));
        assertTrue(ff.contains("\"allowed_extensions\":[\"" + BrowserConnector.FIREFOX_EXTENSION_ID + "\"]") && !ff.contains("allowed_origins"), ff);
    }
}
