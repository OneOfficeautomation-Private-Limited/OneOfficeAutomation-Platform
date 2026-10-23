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

/** The web goal agent against a real headless Edge and a scripted model; skipped when no browser can be started here. */
class WebAgentTest {
    static final String LOGIN = """
            <html><head><title>Portal</title></head><body>
              <h1>Sign in</h1>
              <form action="/home" method="get"><input id="u" name="user" placeholder="User name"><input id="p" name="pw" type="password" placeholder="Password">
              <button type="submit">Sign in</button></form>
              <a href="https://elsewhere.test/">Partner site</a>
            </body></html>""";
    static final String HOME = "<html><head><title>Home</title></head><body><h1>Welcome %s</h1><p>Open invoices: 3</p></body></html>";

    /** Answers like an OpenAI-compatible model: the next scripted tool call per request; keeps every request body. */
    static HttpServer model(List<String> script, List<String> seen) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] i = {0};
        s.createContext("/", ex -> {
            seen.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String call = script.get(Math.min(i[0]++, script.size() - 1));
            int sp = call.indexOf(' ');
            String name = call.substring(0, sp), args = call.substring(sp + 1);
            String body = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c" + i[0] + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                    + "\",\"arguments\":" + JsonLite.write(args) + "}}]}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    static HttpServer site() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String q = ex.getRequestURI().getQuery();
            String user = q == null ? "" : java.net.URLDecoder.decode(q.replaceAll(".*user=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            byte[] b = (ex.getRequestURI().getPath().equals("/home") ? HOME.formatted(user) : LOGIN).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    @SuppressWarnings("unchecked")
    @Test void reachesAGoalWithSecretsKeptAway() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer site = site();
        HttpServer m = model(List.of(
                "go_to {\"url\":\"https://elsewhere.test/\"}",
                "type_text {\"id\":1,\"text\":\"ann\"}",
                "type_text {\"id\":2,\"text\":\"{{secret:password}}\"}",
                "click {\"id\":3}",
                "finish {\"success\":true,\"answer\":\"Signed in; 3 open invoices.\",\"data\":{\"open_invoices\":3}}"), seen);
        Path w = Files.createTempDirectory("ooa-webagent");
        ActionContext c = new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), () -> List.of("S3cret-pw!"), (l, x) -> {});
        WebAgentPackage a = new WebAgentPackage();
        String base = "http://127.0.0.1:" + site.getAddress().getPort();
        try {
            try { a.startSession(c, base + "/", "edge", true, "browser"); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }
            Map<String, Object> r = a.run(c, "Sign in as ann and tell me how many invoices are open.", "browser", 10, List.of("127.0.0.1"),
                    Map.of("password", new SecureString("S3cret-pw!", "vault/portal/password", true)), "Never press Delete.",
                    "openai_compatible", null, "test-model", null, "http://127.0.0.1:" + m.getAddress().getPort() + "/v1", false, null, null, 30);
            assertEquals(true, r.get("success"), String.valueOf(r));
            assertEquals(Map.of("open_invoices", new java.math.BigDecimal(3)), r.get("data"));
            assertTrue(String.valueOf(r.get("url")).contains("/home?user=ann"));
            List<Object> steps = (List<Object>) r.get("steps");
            assertTrue(String.valueOf(steps.get(0)).contains("Refused"), "a site outside allowed_sites is refused: " + steps.get(0));
            assertEquals(5, steps.size());
            String all = String.join("\n", seen);
            assertFalse(all.contains("S3cret-pw!"), "the secret never reaches the model");
            assertTrue(all.contains("{{secret:password}}"), "the model is told the placeholder");
            assertTrue(all.contains("Never press Delete."));
            assertTrue(seen.get(seen.size() - 1).contains("Welcome ann"), "the model sees the page after the click");
            assertTrue(seen.get(2).contains("[2] input password"), "controls are numbered with their type");

            // a password-only secret is not typed into an ordinary field
            HttpServer m2 = model(List.of("type_text {\"id\":1,\"text\":\"{{secret:password}}\"}", "finish {\"success\":false,\"answer\":\"Could not type it.\"}"), seen);
            try {
                new BrowserPackage().navigate(c, base + "/", "browser");
                Map<String, Object> r2 = a.run(c, "Type the password into the user field", "browser", 5, null,
                        Map.of("password", new SecureString("S3cret-pw!", "vault/portal/password", true)), null,
                        "openai_compatible", null, "test-model", null, "http://127.0.0.1:" + m2.getAddress().getPort() + "/v1", false, null, null, 30);
                assertEquals(false, r2.get("success"));
                assertTrue(String.valueOf(((List<Object>) r2.get("steps")).get(0)).contains("password fields"), String.valueOf(r2.get("steps")));
            } finally { m2.stop(0); }

            // the step limit ends a run that never finishes
            HttpServer m3 = model(List.of("scroll {\"direction\":\"down\"}"), seen);
            try {
                Map<String, Object> r3 = a.run(c, "Scroll forever", "browser", 2, null, null, null, "openai_compatible", null, "test-model", null,
                        "http://127.0.0.1:" + m3.getAddress().getPort() + "/v1", false, null, null, 30);
                assertEquals(false, r3.get("success"));
                assertTrue(String.valueOf(r3.get("answer")).contains("2 steps"));
            } finally { m3.stop(0); }
        } finally {
            try { a.endSession(c, "browser"); } catch (RuntimeException ignored) { }
            m.stop(0);
            site.stop(0);
        }
    }
}
