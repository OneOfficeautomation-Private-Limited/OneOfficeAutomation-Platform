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

/** The window choice of web element actions, attaching to a browser with a debugging port, and listing windows. */
class BrowserWindowTest {
    static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    static HttpServer server() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            String title = p.startsWith("/b") ? "Orders - Shop" : "Invoice 42 - Shop", text = p.startsWith("/b") ? "Page B" : "Page A";
            byte[] b = ("<html><head><title>" + title + "</title></head><body><h1 id=\"h\">" + text + "</h1></body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    static Map<String, Object> win(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    String text(BrowserPackage b, ActionContext c, Map<String, Object> window) {
        return (String) b.getText(c, null, "#h", 3000, "browser", null, window).get("text");
    }

    @Test void matchingRulesAndBrowserSuffix() {
        assertTrue(BrowserWindows.matches("Invoice 42 - Shop", "invoice * - SHOP", "wildcard"));
        assertFalse(BrowserWindows.matches("Invoice 42 - Shop", "Invoice *", "equals"));
        assertTrue(BrowserWindows.matches("http://x.test/a?id=1", "/a?id", "contains"));
        assertTrue(BrowserWindows.matches("Invoice 42", "^Invoice \\d+$", "regex"));
        assertEquals(List.of("Orders - Shop", "Orders"), BrowserWindows.tabTitlesOf("Orders - Shop - Microsoft​ Edge"));
        assertTrue(BrowserWindows.tabTitlesOf("Orders - Shop and 3 more pages - Personal - Microsoft​ Edge").contains("Orders - Shop"));
        assertEquals(List.of("Orders"), BrowserWindows.tabTitlesOf("Orders - Google Chrome"));
        assertEquals(List.of("Untitled - Notepad"), BrowserWindows.tabTitlesOf("Untitled - Notepad"));
        assertNull(BrowserWindows.window(Map.of("source", "browser")), "a window choice without title, url or variable means the current tab");
        assertEquals("BROWSER.BAD_WINDOW", assertThrows(ActionException.class, () -> BrowserWindows.use(ctx(Path.of(".")), "browser", win("title", "x", "title_match", "sounds_like"), 0)).code());
        assertEquals("http://127.0.0.1:9333", BrowserWindows.endpoint("9333"));
        assertEquals("http://127.0.0.1:9222", BrowserWindows.endpoint("127.0.0.1:9222/"));
    }

    /** End to end with the installed Edge (headless); skipped when no browser can be started here. */
    @Test void windowChoiceSwitchesTabsAndSessions() throws Exception {
        HttpServer s = server();
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        ActionContext c = ctx(Files.createTempDirectory("ooa-window"));
        BrowserPackage b = new BrowserPackage();
        try {
            try { b.open(c, base + "/a", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }
            BrowserPackage.BrowserSession bs = (BrowserPackage.BrowserSession) c.sessions().get("browser");
            bs.context.newPage().navigate(base + "/b");
            bs.page = bs.context.pages().get(0);

            // wildcard title (ignoring case); the matching tab becomes the session's current page
            assertEquals("Page B", text(b, c, win("source", "browser", "title", "orders * shop")));
            assertTrue(bs.page.url().endsWith("/b"));
            assertEquals("Page A", text(b, c, win("source", "browser", "title", "Invoice * - Shop", "title_match", "wildcard")));
            // by address, and by both
            assertEquals("Page B", text(b, c, win("source", "browser", "url", "/b")));
            assertEquals("Page A", text(b, c, win("title", "Invoice 42 - Shop", "title_match", "equals", "url", base + "/a", "url_match", "equals")));
            // a desktop window title of a browser window: the browser suffix is ignored
            assertEquals("Page B", text(b, c, win("source", "application", "title", "Orders - Shop - Microsoft​ Edge")));
            // the element action and conditions take the same choice
            assertEquals("Page A", b.elementAction(c, null, "#h", "get_text", null, 3000, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null,
                    win("title", "Invoice*"), 0, "error", null, null, false).get("result"));
            assertTrue(b.exists(c, null, "#h", "browser", win("url", "/b")));
            assertFalse(b.elementVisible(c, null, "#h", "browser", win("title", "No such tab")), "a condition is false when the window is missing");

            // variable: another session by name; a tab in another session is found too
            b.open(c, base + "/b?second", "edge", true, null, "other", false, "playwright", "new_window", null, 60000);
            assertEquals("Page B", text(b, c, win("source", "variable", "variable", "other")));
            assertEquals("Page B", text(b, c, win("url", "/b?second")));
            assertEquals("BROWSER.WINDOW_NOT_FOUND", assertThrows(ActionException.class, () -> text(b, c, win("source", "variable", "variable", "missing"))).code());

            // not found: waits, then lists the open tabs
            long t0 = System.currentTimeMillis();
            ActionException e = assertThrows(ActionException.class, () -> b.getText(c, null, "#h", 600, "browser", null, win("title", "Receipt *")));
            assertEquals("BROWSER.WINDOW_NOT_FOUND", e.code());
            assertTrue(System.currentTimeMillis() - t0 >= 500, "waits for the window up to timeout_ms");
            assertTrue(e.getMessage().contains("'Invoice 42 - Shop'") && e.getMessage().contains("'Orders - Shop'"), e.getMessage());
            ActionException app = assertThrows(ActionException.class, () -> b.getText(c, null, "#h", 300, "browser", null, win("source", "application", "title", "Untitled - Notepad")));
            assertEquals("BROWSER.WINDOW_NOT_FOUND", app.code());
            assertTrue(String.valueOf(app.toMap()).contains("Desktop objects"), String.valueOf(app.toMap()));
        } finally {
            for (String n : List.of("browser", "other")) try { b.close(c, n, "session", null); } catch (RuntimeException ignored) { }
            s.stop(0);
        }
    }

    /** Starts Edge with a debugging port, attaches to it, acts on its page and lists its tab; skipped when Edge cannot start. */
    @SuppressWarnings("unchecked")
    @Test void openForAutomationAttachAndList() throws Exception {
        HttpServer s = server();
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
        Path w = Files.createTempDirectory("ooa-cdp");
        ActionContext c = ctx(w);
        BrowserPackage b = new BrowserPackage();
        long pid = 0;
        try {
            Map<String, Object> started;
            try { started = b.openForAutomation(c, "edge", port, base + "/a", null, true, true, "auto", null, 30000); }
            catch (ActionException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }
            pid = ((BigDecimal) started.get("pid")).longValue();
            assertEquals(true, started.get("started"));
            assertEquals("http://127.0.0.1:" + port, started.get("endpoint"));
            assertTrue(Files.isDirectory(w.resolve(".ooa/browser-profiles/edge-" + port)), "dedicated profile folder in the workspace");

            // act on its page through the window choice
            Map<String, Object> window = win("source", "browser", "title", "Invoice*", "browser", "edge");
            assertEquals("Page A", b.getText(c, null, "#h", 10000, "auto", null, window).get("text"));
            assertEquals("Page A", b.getText(c, null, "#h", 3000, "browser", null, (Map<String, Object>) started.get("window")).get("text"),
                    "the returned window choice points at the session");

            // a second agent attaches to the same browser and works in its tab
            ActionContext c2 = ctx(w);
            Map<String, Object> att = b.attach(c2, String.valueOf(port), null, "browser", 10000);
            assertTrue(((BigDecimal) att.get("tab_count")).intValue() >= 1);
            b.navigate(c2, base + "/b", "browser");
            assertEquals("Page B", b.getText(c2, null, "#h", 5000, "browser", null, null).get("text"));
            b.close(c2, "browser", "session", null);
            // The attached session may have used another tab, so either page is fine: what matters is that the browser is still there.
            String still = String.valueOf(b.getText(c, null, "#h", 5000, "auto", null, null).get("text"));
            assertTrue(still.equals("Page A") || still.equals("Page B"), "closing an attached session leaves the browser open: " + still);

            // reusing: a browser already listens on the port
            Map<String, Object> again = b.openForAutomation(c2, "edge", port, null, null, true, true, "browser", null, 10000);
            assertEquals(false, again.get("started"));
            b.close(c2, "browser", "session", null);

            // listing from a fresh run: tabs come from the debugging port
            Map<String, Object> listed = b.listWindows(ctx(w), List.of("http://127.0.0.1:" + port), false);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.get("windows");
            assertTrue(rows.stream().anyMatch(r -> "browser".equals(r.get("source")) && "Orders - Shop".equals(r.get("title"))
                    && String.valueOf(r.get("url")).endsWith("/b") && "edge".equals(r.get("browser"))), String.valueOf(rows));
            // (tabs of a browser connected through the extension may be listed too, with tab_id and connector)
            Map<String, Object> viaPort = rows.stream().filter(r -> r.get("endpoint") != null).findFirst().orElseThrow();
            assertEquals(Set.of("source", "title", "url", "browser", "endpoint"), viaPort.keySet(), "tabs found through a debugging address say which one, so Studio can capture in them");

            assertEquals("BROWSER.NOT_LISTENING", assertThrows(ActionException.class, () -> b.attach(ctx(w), "http://127.0.0.1:" + freePort(), null, "x", 2000)).code());
        } finally {
            try { b.close(c, "auto", "session", null); } catch (RuntimeException ignored) { }
            if (pid > 0) ProcessHandle.of(pid).ifPresent(h -> { h.descendants().forEach(ProcessHandle::destroy); h.destroy(); });
            s.stop(0);
        }
    }

    static int freePort() throws Exception { try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); } }
}
