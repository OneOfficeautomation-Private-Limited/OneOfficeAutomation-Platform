package com.oneofficeautomation.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 4 B2: the runner's requests through WinHTTP, for proxies that accept only Windows sign-in. A stand-in proxy
 * first answers 407 asking for NTLM; Windows answers with the signed-in user's own NTLM message, and the request goes
 * through with its headers and body.
 */
class WinHttpTest {
    @Test @EnabledOnOs(OS.WINDOWS)
    void signsInToAProxyThatWantsWindowsSignInAndCarriesTheRequest() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Proxy-Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + (auth == null ? "none" : auth.split(" ")[0]) + " device=" + ex.getRequestHeaders().getFirst("X-OOA-Device"));
            boolean open = ex.getRequestURI().getPath().endsWith("/open");   // a path this stand-in lets through without a sign-in
            if (!open && (auth == null || !auth.startsWith("NTLM ") && !auth.startsWith("Negotiate "))) {
                ex.getResponseHeaders().add("Proxy-Authenticate", "NTLM");
                ex.sendResponseHeaders(407, -1); ex.close(); return;
            }
            byte[] b = ("{\"echo\":" + (body.isEmpty() ? "null" : body) + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        proxy.start();
        try {
            String setting = "windows:http://127.0.0.1:" + proxy.getAddress().getPort();
            assertEquals("127.0.0.1:" + proxy.getAddress().getPort(), WinHttp.namedProxy(setting));
            WinHttp.Response r = WinHttp.send(setting, "POST", "http://ooa.example.test:8720/api/agent/heartbeat",
                    Map.of("Content-Type", "application/json", "X-OOA-Device", "dev_1"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8), 20_000);
            // the sign-in: first without one, then Windows' own NTLM (or Kerberos) message for the runner's user
            assertEquals(200, r.status(), String.valueOf(seen));
            assertTrue(seen.get(0).contains("auth=none"), "first without a sign-in: " + seen);
            assertTrue(seen.stream().anyMatch((s) -> s.contains("auth=NTLM") || s.contains("auth=Negotiate")), "then with Windows' own sign-in: " + seen);
            assertTrue(seen.stream().allMatch((s) -> s.contains("http://ooa.example.test:8720/api/agent/heartbeat") && s.contains("device=dev_1")), "to the server, through the proxy: " + seen);
            // headers and body travel through the proxy (with a real proxy the body goes on the handshake's last step)
            WinHttp.Response open = WinHttp.send(setting, "POST", "http://ooa.example.test:8720/api/agent/open",
                    Map.of("Content-Type", "application/json", "X-OOA-Device", "dev_1"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8), 20_000);
            assertEquals(200, open.status());
            assertEquals("{\"echo\":{\"a\":1}}", new String(open.body(), StandardCharsets.UTF_8));
        } finally { proxy.stop(0); }
    }

    @Test
    void theRunnerAcceptsTheWindowsProxySettings() {
        assertTrue(new Network(null, "windows", null, null).viaWindows());
        assertTrue(new Network(null, "windows:http://proxy.corp:8080", null, null).viaWindows());
        assertFalse(new Network(null, "http://proxy.corp:8080", null, null).viaWindows());
        assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> new Network(null, "windows:proxy.corp", null, null));
    }
}
