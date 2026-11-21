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

import static com.oneofficeautomation.packages.browser.CaptureObjectTest.LOG;
import static com.oneofficeautomation.packages.browser.CaptureObjectTest.obj;
import static com.oneofficeautomation.packages.browser.CaptureObjectTest.prop;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G4 (AA parity, 22 Nov 2026), web steps: table cells found by their text and clicked three ways, items counted from 1,
 * expanding, menus by path, children's names and values, typed function arguments, a password typed as plain text
 * warned about, and the default browser read from Windows' setting.
 */
class BrowserParityG4Test {
    static final String PAGE = """
            <html><head><title>G4</title></head><body>
              <table id="orders"><tr><th>Id</th><th>Note</th></tr>
                <tr><td>INV-7</td><td>Remark <input id="r7" value="old"></td></tr><tr><td>INV-8</td><td>Comment <input id="r8" value=""></td></tr></table>
              <select id="status"><option>Open</option><option>Closed</option><option>Held</option></select>
              <details id="more"><summary>More</summary><p>Hidden part</p></details>
              <nav id="menu"><button id="file" onclick="document.getElementById('sub').style.display='block'">File</button>
                <div id="sub" style="display:none"><a href="#" onclick="document.body.dataset.saved='yes';return false">Save</a></div></nav>
              <form id="f"><label for="n">Name</label><input id="n" value="Ann"><input type="checkbox" aria-label="Urgent" checked></form>
              <input id="pw" type="password">
              <script>
                document.querySelectorAll('#orders td').forEach((c) => {
                  c.addEventListener('dblclick', () => { document.body.dataset.dbl = c.innerText.trim(); });
                  c.addEventListener('contextmenu', () => { document.body.dataset.ctx = c.innerText.trim(); });
                });
                window.kind = (a, b, c) => [typeof a, typeof b, typeof c].join(',');
              </script>
            </body></html>""";

    static Map<String, Object> act(BrowserPackage b, ActionContext c, Map<String, Object> object, String action, Object value, String find, String kind, boolean fromOne) {
        return b.elementAction(c, object, null, action, value, 3000, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, false,
                find, kind, fromOne);
    }

    static Object read(BrowserPackage b, ActionContext c, String id, String action, String value) {
        return act(b, c, obj(prop(id.equals("body") ? "tag" : "id", id, "equals", true)), action, value, null, "click", true).get("result");
    }

    @SuppressWarnings("unchecked")
    @Test void webStepsOfG4() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> { byte[] by = PAGE.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type", "text/html"); ex.sendResponseHeaders(200, by.length); try (OutputStream os = ex.getResponseBody()) { os.write(by); } });
        s.start();
        Path w = Files.createTempDirectory("ooa-g4web");
        ActionContext c = CaptureObjectTest.ctx(w);
        BrowserPackage b = new BrowserPackage();
        try {
            try { b.open(c, "http://127.0.0.1:" + s.getAddress().getPort() + "/", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }

            // table cells found by their text, clicked three ways, and typed into
            Map<String, Object> orders = obj(prop("id", "orders", "equals", true));
            act(b, c, orders, "click_cell_by_text", null, "INV-8", "double_click", true);
            assertEquals("INV-8", read(b, c, "body", "get_attribute", "data-dbl"));
            act(b, c, orders, "click_cell_by_text", null, "INV-7", "right_click", true);
            assertEquals("INV-7", read(b, c, "body", "get_attribute", "data-ctx"));
            act(b, c, orders, "set_cell_by_text", "paid", "Comment", "click", true);
            assertEquals("paid", read(b, c, "r8", "get_value", null));
            assertEquals("BROWSER.VALUE_REQUIRED", assertThrows(ActionException.class, () -> act(b, c, orders, "click_cell_by_text", null, null, "click", true)).code());

            // items counted from 1 for new steps, from 0 for older ones
            Map<String, Object> status = obj(prop("id", "status", "equals", true));
            act(b, c, status, "select_by_index", "2", null, "click", true);
            assertEquals("Closed", act(b, c, status, "get_selected", null, null, "click", true).get("result"));
            act(b, c, status, "select_by_index", "2", null, "click", false);
            assertEquals("Held", act(b, c, status, "get_selected", null, null, "click", false).get("result"));
            assertEquals("BROWSER.BAD_INDEX", assertThrows(ActionException.class, () -> act(b, c, status, "select_by_index", "0", null, "click", true)).code());

            // expand and collapse; a menu by its path
            assertEquals(true, read(b, c, "more", "expand", null));
            assertEquals(true, read(b, c, "more", "collapse", null));
            read(b, c, "menu", "select_path", "File > Save");
            assertEquals("yes", read(b, c, "body", "get_attribute", "data-saved"));

            // the children as names and values
            List<Object> kids = (List<Object>) read(b, c, "f", "get_children_values", null);
            assertEquals(Map.of("name", "Name", "value", "Ann"), kids.get(1), String.valueOf(kids));
            assertEquals(Map.of("name", "Urgent", "value", "true"), kids.get(2));

            // a password from plain text is warned about
            LOG.clear();
            read(b, c, "pw", "set_text", "secret");
            assertTrue(LOG.stream().anyMatch(l -> l.contains("credential vault")), String.valueOf(LOG));

            // typed function arguments and the longer time limit
            assertEquals("number,boolean,string", b.callJsFunction(c, "kind", List.of("5", "yes", 7), "browser", "any", null, null, List.of("number", "boolean", "text"), 60000).get("result"));
            assertEquals("BROWSER.BAD_ARGUMENT", assertThrows(ActionException.class,
                    () -> b.callJsFunction(c, "kind", List.of("x"), "browser", "any", null, null, List.of("number"), 60000)).code());
            assertEquals(0, new BigDecimal(2).compareTo(new BigDecimal(String.valueOf(b.runJs(c, "1+1", "browser", null, null, null, 240000).get("result")))));
            b.history(c, "refresh", "browser", 1, false, null, 240000);
        } finally {
            try { b.close(c, "browser", "session", null); } catch (RuntimeException ignored) { }
            s.stop(0);
        }
    }

    @Test void theDefaultBrowserComesFromWindowsSetting() {
        assertEquals("chrome", BrowserPackage.browserOfProgId("    ProgId    REG_SZ    ChromeHTML"));
        assertEquals("firefox", BrowserPackage.browserOfProgId("ProgId REG_SZ FirefoxURL-308046B0AF4A39CB"));
        assertEquals("edge", BrowserPackage.browserOfProgId("ProgId REG_SZ MSEdgeHTM"));
        assertEquals("edge", BrowserPackage.browserOfProgId(""));
        assertTrue(List.of("edge", "chrome", "firefox").contains(BrowserPackage.defaultBrowser()));
    }
}
