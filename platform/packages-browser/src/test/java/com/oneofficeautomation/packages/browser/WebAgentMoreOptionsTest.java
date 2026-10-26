package com.oneofficeautomation.packages.browser;

import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Web goal agent: the run's trajectory saved to a file, and a session started through a proxy (with sign-in) or from a
 * given browser program. Uses the scripted model and site of WebAgentTest against a real headless Edge; the browser
 * parts are skipped when no browser can be started here.
 */
class WebAgentMoreOptionsTest {
    static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), () -> List.of("S3cret-pw!", "pw1"), (l, x) -> {});
    }

    /** A forward proxy: asks for basic sign-in (407) until it gets it, then answers every address itself. Keeps "uri|auth" per request. */
    static HttpServer proxy(List<String> seen) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Proxy-Authorization");
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + "|" + auth);
            if (auth == null) {
                ex.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"office\"");
                ex.sendResponseHeaders(407, -1); ex.close(); return;
            }
            byte[] b = "<html><head><title>Via proxy</title></head><body>proxied</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    @Test void badOptionsAreRefusedBeforeTheBrowser() throws Exception {
        WebAgentPackage a = new WebAgentPackage();
        ActionContext c = ctx(Files.createTempDirectory("ooa-webagent-bad"));
        assertEquals("WEBAGENT.BAD_PROXY", assertThrows(ActionException.class, () -> a.startSession(c, "about:blank", "edge", true, "b", "not a proxy", null, null, null)).code());
        assertEquals("WEBAGENT.BAD_PROXY", assertThrows(ActionException.class, () -> a.startSession(c, "about:blank", "edge", true, "b", "ftp://proxy:21", null, null, null)).code());
        assertEquals("WEBAGENT.BAD_PROXY", assertThrows(ActionException.class, () -> a.startSession(c, "about:blank", "edge", true, "b", null, "ann", null, null)).code(),
                "a user name without a proxy server");
        assertEquals("WEBAGENT.BAD_PROXY", assertThrows(ActionException.class, () -> a.startSession(c, "about:blank", "edge", true, "b", "proxy.example.com:3128", null,
                new SecureString("pw1", "vault/proxy"), null)).code(), "a password without a user name");
        assertEquals("WEBAGENT.BAD_SECRET", assertThrows(ActionException.class, () -> a.startSession(c, "about:blank", "edge", true, "b", "proxy.example.com:3128", "ann", "pw1", null)).code(),
                "the proxy password must come from the vault");
        assertEquals("WEBAGENT.BROWSER_NOT_FOUND", assertThrows(ActionException.class, () -> a.startSession(c, "about:blank", "edge", true, "b", null, null, null,
                "C:\\no\\such\\browser.exe")).code());
        // the proxy settings handed to Playwright
        com.microsoft.playwright.options.Proxy p = WebAgentPackage.proxy(" socks5://10.0.0.5:1080 ", "ann", new SecureString("pw1", "vault/proxy"));
        assertEquals("socks5://10.0.0.5:1080", p.server);
        assertEquals("ann", p.username);
        assertEquals("pw1", p.password);
        assertNull(WebAgentPackage.proxy("proxy.example.com:3128", null, null).username);
        // the trajectory file must be JSON
        assertEquals("WEBAGENT.BAD_OPTION", assertThrows(ActionException.class, () -> a.run(c, "x", "nosession", 5, null, null, null, "openai_compatible", null, "m", null,
                "http://127.0.0.1:1/v1", false, null, null, 30, null, null, null, "logs/run.txt")).code());
    }

    @SuppressWarnings("unchecked")
    @Test void trajectoryProxyAndBrowserPath() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer site = WebAgentTest.site();
        HttpServer m = WebAgentTest.model(List.of(
                "type_text {\"id\":1,\"text\":\"ann\"}",
                "type_text {\"id\":2,\"text\":\"{{secret:password}}\"}",
                "click {\"id\":3}",
                "finish {\"success\":true,\"answer\":\"Signed in.\",\"data\":{\"open_invoices\":3}}"), seen);
        List<String> proxied = new CopyOnWriteArrayList<>();
        HttpServer px = proxy(proxied);
        Path w = Files.createTempDirectory("ooa-webagent-more");
        ActionContext c = ctx(w);
        WebAgentPackage a = new WebAgentPackage();
        String base = "http://127.0.0.1:" + site.getAddress().getPort();
        try {
            try { a.startSession(c, base + "/", "edge", true, "browser"); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }

            // ---- save_trajectory_to: every step with its action, the model's words, the address and a screenshot
            Map<String, Object> r = a.run(c, "Sign in as ann.", "browser", 10, List.of("127.0.0.1"),
                    Map.of("password", new SecureString("S3cret-pw!", "vault/portal/password", true)), null,
                    "openai_compatible", null, "test-model", null, "http://127.0.0.1:" + m.getAddress().getPort() + "/v1", false, null, null, 30,
                    null, null, null, "logs/run.json");
            assertEquals(true, r.get("success"), String.valueOf(r));
            String json = Files.readString(w.resolve("logs/run.json"));
            assertFalse(json.contains("S3cret-pw!"), "the secret value is never written");
            Map<String, Object> doc = (Map<String, Object>) JsonLite.parse(json);
            assertEquals("Sign in as ann.", doc.get("goal"));
            assertEquals(true, doc.get("success"));
            assertEquals("Signed in.", doc.get("answer"));
            assertNotNull(doc.get("started"));
            assertNotNull(doc.get("finished"));
            List<Object> steps = (List<Object>) doc.get("steps");
            assertEquals(4, steps.size());
            Map<String, Object> first = (Map<String, Object>) steps.get(0);
            assertEquals("type_text", first.get("action"));
            assertEquals(0, new java.math.BigDecimal(1).compareTo(new java.math.BigDecimal(String.valueOf(first.get("step")))));
            assertEquals("Typed", first.get("result"));
            assertTrue(first.containsKey("reasoning"));
            assertTrue(String.valueOf(first.get("url")).startsWith(base));
            assertTrue(Files.isRegularFile(Path.of(String.valueOf(first.get("screenshot")))), "a screenshot per step");
            assertTrue(Path.of(String.valueOf(first.get("screenshot"))).startsWith(w.resolve("logs/run_screenshots")));
            assertEquals("{{secret:password}}", ((Map<String, Object>) ((Map<String, Object>) steps.get(1)).get("args")).get("text"), "secrets stay placeholders");
            Map<String, Object> last = (Map<String, Object>) steps.get(3);
            assertEquals("finish", last.get("action"));
            assertTrue(String.valueOf(last.get("url")).contains("/home?user=ann"));
            a.endSession(c, "browser");

            // ---- a session through a proxy that asks for a sign-in: the page comes from the proxy
            a.startSession(c, "http://intranet.test/hello", "edge", true, "px", "http://127.0.0.1:" + px.getAddress().getPort(), "ann",
                    new SecureString("pw1", "vault/proxy"), null);
            assertEquals("Via proxy", new BrowserPackage().runJs(c, "document.title", "px", null, null).get("result"));
            String basic = "Basic " + Base64.getEncoder().encodeToString("ann:pw1".getBytes(StandardCharsets.UTF_8));
            assertTrue(proxied.stream().anyMatch(x -> x.startsWith("GET http://intranet.test/hello|" + basic)), "the address went to the proxy with the sign-in: " + proxied);
            a.endSession(c, "px");

            // ---- a given browser program (the installed Edge, when it is where Windows puts it)
            Path edge = null;
            for (String p : List.of("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe", "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"))
                if (Files.isRegularFile(Path.of(p))) { edge = Path.of(p); break; }
            if (edge != null) {
                a.startSession(c, base + "/", "edge", true, "bp", null, null, null, edge.toString());
                assertEquals("Portal", new BrowserPackage().runJs(c, "document.title", "bp", null, null).get("result"));
                a.endSession(c, "bp");
            } else System.out.println("    (no msedge.exe at the usual place; browser_path run skipped)");
        } finally {
            for (String sname : List.of("browser", "px", "bp")) try { a.endSession(c, sname); } catch (RuntimeException ignored) { }
            m.stop(0);
            site.stop(0);
            px.stop(0);
        }
    }
}
