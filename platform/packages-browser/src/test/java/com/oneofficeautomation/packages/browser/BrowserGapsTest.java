package com.oneofficeautomation.packages.browser;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class BrowserGapsTest {
    static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    static final String PAGE = """
            <html><head><title>Links</title><script>
              window.app = { total: function (items, tax) { return { sum: items.reduce((a, b) => a + b, 0) * (1 + tax), n: items.length }; },
                             later: async function (x) { return 'done ' + x; } };
              function greet(name) { return 'Hello ' + name; }
            </script></head><body>
              <a href="/ok">ok</a> <a href="/ok#section">same</a> <a href="/missing">missing</a>
              <a href="/head-refused">head refused</a> <a href="mailto:x@y.test">mail</a> <a href="http://127.0.0.1:%d/">closed port</a>
              <iframe id="inner" srcdoc="<p id='in'>Inside the frame</p>"></iframe>
            </body></html>""";

    static HttpServer server(int closedPort) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            int status = switch (p) {
                case "/", "/ok" -> 200;
                case "/head-refused" -> ex.getRequestMethod().equals("HEAD") ? 405 : 200;
                default -> 404;
            };
            if (p.equals("/report.csv")) {
                // basic sign-in: user ann, password pw1
                String a = ex.getRequestHeaders().getFirst("Authorization");
                if (!("Basic " + java.util.Base64.getEncoder().encodeToString("ann:pw1".getBytes())).equals(a)) {
                    ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"t\""); ex.sendResponseHeaders(401, -1); ex.close(); return; }
                byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, csv.length); try (OutputStream os = ex.getResponseBody()) { os.write(csv); } return;
            }
            byte[] body = p.equals("/") ? PAGE.formatted(closedPort).getBytes(StandardCharsets.UTF_8) : "x".getBytes();
            ex.getResponseHeaders().add("Content-Type", p.equals("/") ? "text/html" : "text/plain");
            if (ex.getRequestMethod().equals("HEAD")) { ex.sendResponseHeaders(status, -1); ex.close(); return; }
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        s.start();
        return s;
    }

    static int closedPort() throws Exception { try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); } }

    @SuppressWarnings("unchecked")
    @Test void linkCheckerAndArgumentConversion() throws Exception {
        int closed = closedPort();
        HttpServer s = server(closed);
        try {
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            List<Object> rows = BrowserPackage.checkLinks(null, List.of(base + "/ok", base + "/missing", base + "/head-refused", "http://127.0.0.1:" + closed + "/"), 3000, false);
            assertEquals(List.of(base + "/ok", new BigDecimal(200), true, ""), rows.get(0));
            assertEquals(new BigDecimal(404), ((List<Object>) rows.get(1)).get(1));
            assertEquals(true, ((List<Object>) rows.get(2)).get(2), "HEAD refused, GET works");
            assertEquals(new BigDecimal(0), ((List<Object>) rows.get(3)).get(1));
            assertFalse(((String) ((List<Object>) rows.get(3)).get(3)).isEmpty());
            assertEquals(2, BrowserPackage.checkLinks(null, List.of(base + "/ok", base + "/missing", "http://127.0.0.1:" + closed + "/"), 3000, true).size());
        } finally { s.stop(0); }
        assertEquals(5, BrowserPackage.jsValue(new BigDecimal("5.00")));
        assertEquals(0.1, BrowserPackage.jsValue(new BigDecimal("0.1")));
        assertEquals(1.0E10, BrowserPackage.jsValue(new BigDecimal("10000000000")));
        assertEquals(List.of(1, Map.of("a", 2.5)), BrowserPackage.jsValue(List.of(BigDecimal.ONE, Map.of("a", new BigDecimal("2.5")))));
    }

    /** End-to-end with the installed Edge (headless); skipped when no browser can be started here. */
    @SuppressWarnings("unchecked")
    @Test void pageSourceFunctionsAndBrokenLinks() throws Exception {
        int closed = closedPort();
        HttpServer s = server(closed);
        Path w = Files.createTempDirectory("ooa-browser");
        ActionContext c = ctx(w);
        BrowserPackage b = new BrowserPackage();
        try {
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            try { b.open(c, base + "/", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }
            Map<String, Object> src = b.getPageSource(c, "out/page.html", "browser", null, null);
            assertTrue(((String) src.get("source")).contains("function greet"));
            assertTrue(Files.readString(w.resolve("out/page.html")).contains("<title>Links</title>"));
            Map<String, Object> iframe = new LinkedHashMap<>(Map.of("technology", "web", "search_by", "properties", "index", 0,
                    "properties", List.of(new LinkedHashMap<>(Map.of("name", "id", "value", "inner", "match", "equals", "use", true)))));
            assertTrue(((String) b.getPageSource(c, null, "browser", iframe, null).get("source")).contains("Inside the frame"), "the source of a captured iframe");

            // Run JavaScript from a file
            Files.writeString(w.resolve("count.js"), "document.querySelectorAll('a').length");
            assertEquals(0, new BigDecimal(6).compareTo(new BigDecimal(String.valueOf(b.runJs(c, null, "browser", "count.js", null).get("result")))));

            assertEquals("Hello Ann", b.callJsFunction(c, "greet", List.of("Ann"), "browser", "any", null).get("result"));
            Map<String, Object> total = (Map<String, Object>) b.callJsFunction(c, "app.total", List.of(List.of(new BigDecimal(10), new BigDecimal(20)), new BigDecimal("0.5")), "browser", "any", null).get("result");
            assertEquals(0, new BigDecimal(45).compareTo(new BigDecimal(String.valueOf(total.get("sum")))));
            assertEquals("done 7", b.callJsFunction(c, "app.later", List.of(new BigDecimal(7)), "browser", "any", null).get("result"));
            // return types: none, text and a refused conversion
            assertNull(b.callJsFunction(c, "greet", List.of("Ann"), "browser", "none", null).get("result"));
            assertEquals("{\"sum\":45,\"n\":2}".length() > 0, ((String) b.callJsFunction(c, "app.total", List.of(List.of(new BigDecimal(10), new BigDecimal(20)), new BigDecimal("0.5")), "browser", "text", null).get("result")).contains("sum"));
            assertEquals("BROWSER.BAD_RETURN", assertThrows(ActionException.class, () -> b.callJsFunction(c, "greet", List.of("Ann"), "browser", "number", null)).code());
            assertEquals("BROWSER.ERROR", assertThrows(ActionException.class, () -> b.callJsFunction(c, "app.nothing", null, "browser", "any", null)).code());
            assertEquals("BROWSER.BAD_FUNCTION", assertThrows(ActionException.class, () -> b.callJsFunction(c, "alert(1)", null, "browser", "any", null)).code());

            Map<String, Object> links = b.findBrokenLinks(c, true, false, 50, 3000, "browser", null, "page", 50, null, false, "UTF-8", 4);
            assertEquals(0, new BigDecimal(4).compareTo((BigDecimal) links.get("checked_count")), "fragments de-duplicated, mailto ignored");
            assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) links.get("broken_count")));
            List<List<Object>> rows = (List<List<Object>>) ((Map<String, Object>) links.get("table")).get("rows");
            assertTrue(rows.stream().anyMatch(r -> String.valueOf(r.get(0)).endsWith("/missing")));
            Map<String, Object> same = b.findBrokenLinks(c, false, true, 50, 3000, "browser", null, "page", 50, null, false, "UTF-8", 1);
            assertEquals(0, new BigDecimal(4).compareTo((BigDecimal) same.get("checked_count")), "127.0.0.1 on another port is the same host");

            // by address, without the browser, to a CSV file; append adds rows under the same header
            Map<String, Object> byUrl = b.findBrokenLinks(c, true, false, 50, 3000, "nobrowser", base + "/", "site", 10, "out/links.csv", false, "UTF-8", 4);
            assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) byUrl.get("broken_count")));
            b.findBrokenLinks(c, true, false, 50, 3000, "nobrowser", base + "/", "page", 10, "out/links.csv", true, "UTF-8", 2);
            List<String> csv = Files.readAllLines(w.resolve("out/links.csv"));
            assertEquals("url,status,ok,error", csv.get(0));
            assertEquals(5, csv.size(), "a header and two broken links, twice");

            // download by address with a basic sign-in; no overwrite unless asked
            Map<String, Object> dl = b.downloadUrl(c, base + "/report.csv", "dl/", false, "ann", new com.oneofficeautomation.sdk.SecureString("pw1", "pw"), 10000);
            assertEquals("a,b\n1,2\n", Files.readString(Path.of(String.valueOf(dl.get("path")))));
            assertEquals("BROWSER.FILE_EXISTS", assertThrows(ActionException.class, () -> b.downloadUrl(c, base + "/report.csv", "dl/report.csv", false, "ann", new com.oneofficeautomation.sdk.SecureString("pw1", "pw"), 10000)).code());
            b.downloadUrl(c, base + "/report.csv", "dl/report.csv", true, "ann", new com.oneofficeautomation.sdk.SecureString("pw1", "pw"), 10000);
            assertEquals("BROWSER.SIGN_IN_FAILED", assertThrows(ActionException.class, () -> b.downloadUrl(c, base + "/report.csv", "dl/other.csv", true, "ann", new com.oneofficeautomation.sdk.SecureString("wrong", "pw"), 10000)).code());
        } finally {
            b.close(c, "browser", "session", null);
            s.stop(0);
        }
    }
}
