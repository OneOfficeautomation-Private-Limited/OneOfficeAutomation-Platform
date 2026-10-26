package com.oneofficeautomation.packages.browser;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extract table on a page that loads rows while it is scrolled, and Run JavaScript and Call JavaScript function inside
 * a captured iframe; against a real headless Edge, skipped when no browser can be started here.
 */
class BrowserMoreOptionsTest {
    static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    static final String PAGE = """
            <html><head><title>Lazy</title><script>function whoami(x) { return 'main ' + x; }</script></head><body>
              <iframe id="inner" srcdoc="<title>Inner</title><script>function whoami(x) { return 'frame ' + x; } window.depth = 7;</script><p>in the frame</p>"></iframe>
              <table id="grid"><tr><th>N</th><th>Name</th></tr></table>
              <script>
                const g = document.getElementById('grid');
                function add(k) { for (let i = 0; i < k; i++) { const n = g.rows.length; const r = g.insertRow(); r.style.height = '120px';
                  r.insertCell().innerText = String(n); r.insertCell().innerText = 'Row ' + n; } }
                add(10);
                let busy = false;
                // ten more rows each time the page is scrolled to its end, up to 30
                window.addEventListener('scroll', () => {
                  if (busy || g.rows.length >= 31) return;
                  if (window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 5) { busy = true; setTimeout(() => { add(10); busy = false; }, 50); }
                });
              </script>
            </body></html>""";

    static HttpServer server() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        s.start();
        return s;
    }

    static Map<String, Object> byId(String id) {
        return new LinkedHashMap<>(Map.of("technology", "web", "search_by", "properties", "index", 0,
                "properties", List.of(new LinkedHashMap<>(Map.of("name", "id", "value", id, "match", "equals", "use", true)))));
    }

    @SuppressWarnings("unchecked")
    static int rows(Map<String, Object> r) { return ((List<Object>) ((Map<String, Object>) r.get("table")).get("rows")).size(); }

    @Test void badLazyLoadOptionsAreRefusedBeforeTheBrowser() {
        BrowserPackage b = new BrowserPackage();
        ActionContext c = ctx(Path.of("."));
        assertEquals("BROWSER.BAD_INPUT", assertThrows(ActionException.class, () -> b.extractTable(c, null, "table", 1000, "none", null, true, 0, 100)).code());
        assertEquals("BROWSER.BAD_INPUT", assertThrows(ActionException.class, () -> b.extractTable(c, null, "table", 1000, "none", null, true, 5, -1)).code());
        assertEquals("BROWSER.BAD_INPUT", assertThrows(ActionException.class, () -> b.extractTable(c, null, "table", 1000, "none", null, true, 501, 100)).code());
        BrowserPackage.checkLazyLoad(false, 0, -1);   // the options are not looked at without lazy_load
        assertTrue(BrowserPackage.SCROLL_TO_END_JS.contains("$T"));
    }

    @SuppressWarnings("unchecked")
    @Test void lazyTableAndScriptsInAFrame() throws Exception {
        HttpServer s = server();
        Path w = Files.createTempDirectory("ooa-browser-more");
        ActionContext c = ctx(w);
        BrowserPackage b = new BrowserPackage();
        try {
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            try { b.open(c, base + "/", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }

            // without lazy_load only the rows already there are read (the old signature still works)
            assertEquals(10, rows(b.extractTable(c, null, "#grid", 10000, "browser", null)));
            // with lazy_load the page is scrolled until no more rows come: 10, 20, 30, then no growth
            Map<String, Object> all = b.extractTable(c, null, "#grid", 10000, "browser", null, true, 10, 400);
            assertEquals(30, rows(all));
            List<List<Object>> grid = (List<List<Object>>) ((Map<String, Object>) all.get("table")).get("rows");
            assertEquals(List.of("30", "Row 30"), grid.get(29));
            assertEquals(List.of("N", "Name"), ((Map<String, Object>) all.get("table")).get("columns"));
            // the retries are an upper bound: one scroll that brings nothing ends the loop
            long t0 = System.currentTimeMillis();
            assertEquals(30, rows(b.extractTable(c, byId("grid"), null, 10000, "browser", null, true, 50, 200)));
            assertTrue(System.currentTimeMillis() - t0 < 5000, "stops as soon as the count stops growing");

            // Run JavaScript: the main page, or inside the captured iframe
            assertEquals("Lazy", b.runJs(c, "document.title", "browser", null, null).get("result"));
            assertEquals("Inner", b.runJs(c, "document.title", "browser", null, null, byId("inner")).get("result"));
            assertEquals(0, new BigDecimal(7).compareTo(new BigDecimal(String.valueOf(b.runJs(c, "window.depth", "browser", null, null, byId("inner")).get("result")))));
            Files.writeString(w.resolve("frame.js"), "document.querySelector('p').innerText");
            assertEquals("in the frame", b.runJs(c, null, "browser", "frame.js", null, byId("inner")).get("result"));

            // Call JavaScript function: the same name resolves in the frame's own window
            assertEquals("main A", b.callJsFunction(c, "whoami", List.of("A"), "browser", "any", null).get("result"));
            assertEquals("frame A", b.callJsFunction(c, "whoami", List.of("A"), "browser", "any", null, byId("inner")).get("result"));
            assertEquals("frame B", b.callJsFunction(c, "whoami", List.of("B"), "browser", "text", null, byId("inner")).get("result"));

            // a captured element that is not an iframe
            assertEquals("BROWSER.NOT_A_FRAME", assertThrows(ActionException.class, () -> b.runJs(c, "1", "browser", null, null, byId("grid"))).code());
            assertEquals("BROWSER.NOT_A_FRAME", assertThrows(ActionException.class, () -> b.callJsFunction(c, "whoami", List.of("A"), "browser", "any", null, byId("grid"))).code());
            // the function name is still checked first
            assertEquals("BROWSER.BAD_FUNCTION", assertThrows(ActionException.class, () -> b.callJsFunction(c, "alert(1)", null, "browser", "any", null, byId("inner"))).code());
        } finally {
            try { b.close(c, "browser", "session", null); } catch (RuntimeException ignored) { }
            s.stop(0);
        }
    }
}
