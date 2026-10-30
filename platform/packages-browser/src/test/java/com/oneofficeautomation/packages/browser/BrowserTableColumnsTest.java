package com.oneofficeautomation.packages.browser;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Extract table with custom columns: chosen cells, attributes and non-table grids, by XPath. */
class BrowserTableColumnsTest {
    static final String PAGE = "<html><body>"
            + "<table id='orders'><tr><th>No</th><th>Customer</th><th>Link</th></tr>"
            + "<tr><td>1</td><td>Acme   Corp</td><td><a href='/o/1'>open</a></td></tr>"
            + "<tr><td>2</td><td>Globex</td><td><a href='/o/2'>open</a></td></tr></table>"
            + "<div id='cards'><div class='card'><b>Ann</b><span data-role='Sales'>x</span></div><div class='card'><b>Bob</b><span data-role='Ops'>y</span></div></div>"
            + "</body></html>";

    static Map<String, Object> cols(String... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> table(Map<String, Object> r) { return (Map<String, Object>) r.get("table"); }

    @Test void badColumnsAreRefusedBeforeTheBrowser() {
        BrowserPackage b = new BrowserPackage();
        ActionContext c = BrowserMoreOptionsTest.ctx(Path.of("."));
        assertEquals("BROWSER.BAD_INPUT", assertThrows(ActionException.class,
            () -> b.extractTable(c, null, "table", 1000, "none", null, false, 10, 1000, cols("Name", " "), null)).code());
    }

    @Test void readsChosenColumnsByXpath() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        s.start();
        ActionContext c = BrowserMoreOptionsTest.ctx(Files.createTempDirectory("ooa-browser-cols"));
        BrowserPackage b = new BrowserPackage();
        try {
            try { b.open(c, "http://127.0.0.1:" + s.getAddress().getPort() + "/", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }

            Map<String, Object> t = table(b.extractTable(c, null, "#orders", 10000, "browser", null, false, 10, 1000, cols("Customer", "td[2]", "Link", ".//a/@href"), null));
            assertEquals(List.of("Customer", "Link"), t.get("columns"));
            assertEquals(List.of(List.of("Acme Corp", "/o/1"), List.of("Globex", "/o/2")), t.get("rows"), "header row skipped, spaces tidied, attribute read");

            Map<String, Object> cards = table(b.extractTable(c, null, "#cards", 10000, "browser", null, false, 10, 1000,
                cols("Name", "b", "Team", "span/@data-role"), "div[@class='card']"));
            assertEquals(List.of(List.of("Ann", "Sales"), List.of("Bob", "Ops")), cards.get("rows"), "a grid that is not a table");

            assertEquals("BROWSER.BAD_XPATH", assertThrows(ActionException.class,
                () -> b.extractTable(c, null, "#orders", 10000, "browser", null, false, 10, 1000, cols("X", "td[[["), null)).code());
        } finally {
            try { b.close(c, "browser", "session", null); } catch (RuntimeException ignored) { }
            s.stop(0);
        }
    }
}
