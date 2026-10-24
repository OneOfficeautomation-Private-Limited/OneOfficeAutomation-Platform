package com.oneofficeautomation.packages.browser;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Commands added after comparing the web use cases with AA: click method, waits on conditions, element text, any-of, lists, pages. */
class BrowserCommandsTest {
    static final String PAGE = """
        <html><head><title>Shop</title></head><body>
        <div id="cover" style="position:fixed;left:0;top:0;width:100%;height:100%;z-index:9"></div>
        <button id="buy" onclick="document.getElementById('status').textContent='Bought'">Buy</button>
        <b id="status">Waiting</b>
        <span id="late"></span>
        <div class="card"><h3><a href="/p/1">Lamp</a></h3><span class="price">24.99</span></div>
        <div class="card"><h3><a href="/p/2">Chair</a></h3><span class="price">149.00</span></div>
        <div class="card"><h3><a href="/p/3">Hub</a></h3><span class="price">39.90</span></div>
        <a id="next" href="/page/NEXT">Next</a>
        <button id="later" onclick="setTimeout(() => { document.getElementById('late').textContent = 'Ready'; }, 700)">Later</button>
        </body></html>""";

    static HttpServer server() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            int page = p.startsWith("/page/") ? Integer.parseInt(p.substring(6)) : 1;
            // three pages: the last one has no Next link
            String html = PAGE.replace("NEXT", String.valueOf(page + 1)).replace("<b id=\"status\">Waiting</b>", "<b id=\"status\">Waiting</b><i id=\"pg\">Page " + page + "</i>");
            if (page >= 3) html = html.replaceAll("<a id=\"next\"[^>]*>Next</a>", "");
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    /** End to end with the installed Edge (headless); skipped when no browser can be started here. */
    @Test @SuppressWarnings("unchecked") void newWebCommands() throws Exception {
        HttpServer s = server();
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        ActionContext c = BrowserWindowTest.ctx(Files.createTempDirectory("ooa-cmds"));
        BrowserPackage b = new BrowserPackage();
        try {
            try { b.open(c, base + "/", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }

            // a covered button: the mouse click cannot reach it, the JavaScript click can
            assertThrows(ActionException.class, () -> b.click(c, null, "#buy", false, 800, "browser", null, null, "mouse", false));
            b.click(c, null, "#buy", false, 3000, "browser", null, null, "javascript", false);
            assertTrue(b.elementText(c, null, "#status", "bought", "equals", true, 0, "browser", null));
            assertFalse(b.elementText(c, null, "#status", "bought", "equals", false, 0, "browser", null), "case counts when asked");

            // conditions can wait: the text arrives 0.7 s after Later is clicked
            b.click(c, null, "#later", false, 3000, "browser", null, null, "javascript", false);
            assertFalse(b.exists(c, null, "#late:not(:empty)", "browser", null, 0));
            assertTrue(b.exists(c, null, "#late:not(:empty)", "browser", null, 3));
            assertTrue(b.elementText(c, null, "#late", "Rea", "starts_with", true, 2, "browser", null));

            // wait for any of several elements: which one appeared
            Map<String, Object> w = b.waitFor(c, null, "#never", "visible", 3000, "browser", null, List.of("#also-never", "#status"));
            assertEquals(BigDecimal.valueOf(2), w.get("which"));

            // repeated items into a table
            Map<String, Object> list = b.extractList(c, null, ".card", new LinkedHashMap<>(Map.of("Name", "h3 a", "Price", ".price", "Link", "h3 a@href")), null, 3000, "browser", null);
            assertEquals(BigDecimal.valueOf(3), list.get("count"));
            Map<String, Object> t = (Map<String, Object>) list.get("table");
            List<List<Object>> rows = (List<List<Object>>) t.get("rows");
            List<String> cols = (List<String>) t.get("columns");
            assertEquals("Chair", rows.get(1).get(cols.indexOf("Name")));
            assertEquals("149.00", rows.get(1).get(cols.indexOf("Price")));
            assertTrue(String.valueOf(rows.get(2).get(cols.indexOf("Link"))).endsWith("/p/3"));

            // each page until Next is gone: pages 1, 2, 3
            List<Object> pages = new ArrayList<>();
            for (Object p : b.pages(c, null, "#next", 10, 5000, "browser")) pages.add(p);
            assertEquals(List.of(BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3)), pages);
            assertTrue(b.elementText(c, null, "#pg", "Page 3", "equals", false, 0, "browser", null));
        } finally {
            try { b.close(c, "browser", "session", null); } catch (RuntimeException ignored) { }
            s.stop(0);
        }
    }

    @Test void textMatching() {
        assertTrue(BrowserPackage.textMatches("Status: Ready", "ready", "contains", true));
        assertFalse(BrowserPackage.textMatches("Status: Ready", "ready", "contains", false));
        assertTrue(BrowserPackage.textMatches("INV-2041", "^INV-\\d+$", "matches", false));
        assertTrue(BrowserPackage.textMatches(" x ".strip(), "x", "equals", false));
    }
}
