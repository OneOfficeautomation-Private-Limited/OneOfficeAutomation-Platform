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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Captured web objects: property rules, match types, index, iframes, CSS/XPath search, self-healing and the richer element options. */
class CaptureObjectTest {
    static final List<String> LOG = new CopyOnWriteArrayList<>();
    static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> LOG.add(l + ": " + m));
    }

    static final String PAGE = """
            <html><head><title>Orders - Demo</title></head><body>
              <label for="user">User name</label><input id="user" name="user" type="text">
              <input placeholder="Search orders" type="search" data-testid="search-box">
              <button id="save-123456" class="btn primary">Save item</button>
              <ul><li><button class="btn del">Delete</button></li><li><button class="btn del">Delete</button></li><li><button class="btn del">Delete</button></li></ul>
              <select id="status"><option>Open</option><option>Closed</option></select>
              <table id="orders"><tr><th>Id</th><th>Amount</th></tr><tr><td>1</td><td>10</td></tr><tr><td>2</td><td>20</td></tr></table>
              <div id="spinner">Loading</div>
              <input type="file" id="upload">
              <div id="rows"><p id="row-a">Row A</p><button class="go">Go</button><p id="row-b">Row B</p><button class="go">Go</button></div>
              <div style="position:relative"><button id="covered" onclick="document.body.dataset.clicked = 'yes'">Covered</button>
                <div style="position:absolute;inset:0;background:white"></div></div>
              <iframe id="inner" srcdoc="<input id='code' name='code'><button>Inner</button>"></iframe>
              <script>
                setTimeout(() => document.getElementById('spinner').remove(), 600);
                document.querySelectorAll('.del').forEach((b, i) => b.onclick = () => { document.body.dataset.deleted = String(i); });
                document.querySelectorAll('.go').forEach((b, i) => b.onclick = () => { document.body.dataset.go = String(i); });
              </script>
            </body></html>""";

    static Map<String, Object> prop(String name, Object value, String match, boolean use) {
        return new LinkedHashMap<>(Map.of("name", name, "value", value, "match", match, "use", use));
    }

    static Map<String, Object> obj(Object... props) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("technology", "web"); o.put("search_by", "properties"); o.put("index", 0);
        List<Object> p = new ArrayList<>(Arrays.asList(props));
        o.put("properties", p);
        return o;
    }

    Map<String, Object> act(BrowserPackage b, ActionContext c, Map<String, Object> object, String action, Object value) {
        return b.elementAction(c, object, null, action, value, 3000, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, false);
    }

    @Test void capturedObjectDefaultsPickStableProperties() {
        Map<String, Object> picked = new HashMap<>();
        picked.put("props", Map.of("tag", "input", "id", "ctl00_5551234", "name", "email", "type", "text", "class", "form-control"));
        picked.put("css", "input[name=\"email\"]"); picked.put("xpath", "/html/body/input"); picked.put("relative_xpath", "//input[@name='email']");
        picked.put("page", Map.of("url", "https://x.test/a", "title", "A"));
        Map<String, Object> o = BrowserPackage.capturedObject(picked);
        List<Map<String, Object>> props = (List<Map<String, Object>>) o.get("properties");
        Map<String, Boolean> used = new LinkedHashMap<>();
        for (Map<String, Object> p : props) used.put((String) p.get("name"), (Boolean) p.get("use"));
        assertEquals(true, used.get("tag"));
        assertEquals(false, used.get("id"), "generated-looking ids are not used");
        assertEquals(true, used.get("name"));
        assertEquals(true, used.get("type"));
        assertEquals(false, used.get("class"));
        assertEquals(false, used.get("css"));
        assertTrue(used.containsKey("relative_xpath") && used.containsKey("xpath"));
        assertEquals("starts_with", ((Map<String, Object>) o.get("page")).get("url_match"));
        assertEquals("equals", WebObjects.rules(o).get(0).get("match"));
        assertEquals("input[name=\"email\"]", WebObjects.baseCss(WebObjects.rules(o)).replace("[type=\"text\"]", ""));
        assertTrue(WebObjects.matches("Invoice 42 - Demo", "Invoice * - Demo", "wildcard"));
        assertFalse(WebObjects.matches("Invoice 42", "Invoice ?", "wildcard"));
        assertEquals("BROWSER.BAD_MATCH", assertThrows(ActionException.class, () -> WebObjects.rules(obj(prop("id", "x", "sounds_like", true)))).code());
        assertEquals("BROWSER.BAD_OBJECT", assertThrows(ActionException.class, () -> WebObjects.object(Map.of("technology", "desktop"))).code());
        assertEquals(List.of(com.microsoft.playwright.options.KeyboardModifier.CONTROL, com.microsoft.playwright.options.KeyboardModifier.SHIFT), BrowserPackage.modifiers("ctrl+Shift"));
        assertEquals("BROWSER.BAD_MODIFIER", assertThrows(ActionException.class, () -> BrowserPackage.modifiers("hyper")).code());
    }

    /** End to end with the installed Edge (headless); skipped when no browser can be started here. */
    @SuppressWarnings("unchecked")
    @Test void elementActionsOnCapturedObjects() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> { byte[] b = PAGE.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.length); try (OutputStream os = ex.getResponseBody()) { os.write(b); } });
        s.start();
        Path w = Files.createTempDirectory("ooa-capture");
        ActionContext c = ctx(w);
        BrowserPackage b = new BrowserPackage();
        try {
            try { b.open(c, "http://127.0.0.1:" + s.getAddress().getPort() + "/", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }

            // equals on several properties; keystroke typing with append
            Map<String, Object> user = obj(prop("tag", "input", "equals", true), prop("name", "user", "equals", true), prop("label", "User name", "equals", true));
            b.elementAction(c, user, null, "set_text", "Ann", 3000, "mouse", null, null, null, "keystrokes", 5, true, false, 0, false, "browser", null, null, 0, "error", null, null, false);
            b.elementAction(c, user, null, "set_text", "-B", 3000, "mouse", null, null, null, "fill", 0, false, false, 0, false, "browser", null, null, 0, "error", null, null, false);
            assertEquals("Ann-B", act(b, c, user, "get_value", null).get("result"), "clear_first false appends");

            // contains / starts_with / data-* / ignore case
            Map<String, Object> search = obj(prop("placeholder", "search ORDERS", "contains", true), prop("data-testid", "search-", "starts_with", true));
            search.put("ignore_case", true);
            act(b, c, search, "set_text", "INV-7");
            assertEquals("INV-7", act(b, c, search, "get_value", null).get("result"));

            // wildcard and regex on text; generated id matched by regex
            assertEquals("Save item", act(b, c, obj(prop("tag", "button", "equals", true), prop("text", "Save*", "wildcard", true)), "get_text", null).get("result"));
            assertEquals(true, act(b, c, obj(prop("id", "^save-\\d+$", "regex", true)), "is_present", null).get("result"));

            // index among several matches; count; not_equals
            Map<String, Object> del = obj(prop("tag", "button", "equals", true), prop("text", "Delete", "equals", true));
            assertEquals(new BigDecimal(3), act(b, c, del, "count", null).get("result"));
            del.put("index", 2);
            act(b, c, del, "click", null);
            assertEquals("2", act(b, c, obj(prop("tag", "body", "equals", true)), "get_attribute", "data-deleted").get("result"));
            assertEquals(new BigDecimal(1), act(b, c, obj(prop("tag", "button", "equals", true), prop("class", "btn del", "not_equals", true), prop("class", "btn", "starts_with", true)), "count", null).get("result"));
            del.put("index", 7);
            assertEquals("BROWSER.INDEX_OUT_OF_RANGE", assertThrows(ActionException.class, () -> b.elementAction(c, del, null, "click", null, 600, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, false)).code());

            // search by CSS and XPath; self-healing to CSS when the properties no longer match
            Map<String, Object> status = obj(prop("id", "status-old", "equals", true), prop("css", "#status", "equals", false), prop("xpath", "/html/body/select", "equals", false));
            act(b, c, status, "select_by_text", "Closed");
            assertTrue(LOG.stream().anyMatch(l -> l.contains("found by its css")), "the healed search is logged: " + LOG);
            status.put("search_by", "xpath");
            assertEquals("Closed", act(b, c, status, "get_selected", null).get("result"));

            // iframes
            Map<String, Object> code = obj(prop("name", "code", "equals", true));
            code.put("frames", List.of("iframe#inner"));
            act(b, c, code, "set_text", "X1");
            assertEquals("X1", act(b, c, code, "get_value", null).get("result"));

            // properties, coordinates, table, children
            Map<String, Object> all = (Map<String, Object>) act(b, c, user, "get_all_properties", null).get("result");
            assertEquals("user", all.get("id"));
            assertEquals("Ann-B", all.get("value"));
            Map<String, Object> xy = (Map<String, Object>) act(b, c, user, "get_coordinates", null).get("result");
            assertTrue(((BigDecimal) xy.get("width")).intValue() > 0);
            Map<String, Object> table = (Map<String, Object>) act(b, c, obj(prop("id", "orders", "equals", true)), "get_table", null).get("result");
            assertEquals(List.of("Id", "Amount"), table.get("columns"));
            assertEquals(2, ((List<Object>) table.get("rows")).size());
            assertEquals(List.of("Delete", "Delete", "Delete"), act(b, c, obj(prop("tag", "ul", "equals", true)), "get_children_text", null).get("result"));

            // the full property set: DOM XPath, path, CSS, parent and position, the same names Capture records
            assertEquals("//input[@id='user']", all.get("dom_xpath"));
            assertEquals("input#user", all.get("css"));
            assertEquals("body", String.valueOf(all.get("parent")).replaceAll("[#.].*", ""));
            assertTrue(String.valueOf(all.get("path")).matches("\\d+(/\\d+)+"), "path: " + all.get("path"));
            assertEquals("User name", all.get("label"));
            // a ticked DOM XPath or CSS property means "the element it finds"; search by XPath uses the DOM XPath first
            assertEquals("Ann-B", act(b, c, obj(prop("dom_xpath", "//input[@id='user']", "equals", true)), "get_value", null).get("result"));
            assertEquals(new BigDecimal(1), act(b, c, obj(prop("tag", "input", "equals", true), prop("css", "input#user", "equals", true)), "count", null).get("result"));
            Map<String, Object> byX = obj(prop("dom_xpath", "//select[@id='status']", "equals", false), prop("xpath", "/html/body/nothing", "equals", false));
            byX.put("search_by", "xpath");
            assertEquals(true, act(b, c, byX, "is_present", null).get("result"));
            assertEquals(new BigDecimal(1), act(b, c, obj(prop("tag", "button", "equals", true), prop("parent", "li", "starts_with", true), prop("tag_index", "1", "equals", true)), "count", null).get("result"));

            // the anchor picks between several matches, and finds the element when its own properties no longer match
            Map<String, Object> go = obj(prop("tag", "button", "equals", true), prop("text", "Go", "equals", true));
            Map<String, Object> anchor = obj(prop("id", "row-b", "equals", true));
            anchor.put("offset", Map.of("dx", 0, "dy", 30));
            go.put("anchor", anchor);
            act(b, c, go, "click", null);
            assertEquals("1", act(b, c, obj(prop("tag", "body", "equals", true)), "get_attribute", "data-go").get("result"), "the Go button below Row B");
            Map<String, Object> goGone = obj(prop("tag", "button", "equals", true), prop("id", "go-renamed", "equals", true));
            anchor.put("offset", Map.of("dx", 0, "dy", -30));
            goGone.put("anchor", anchor);
            act(b, c, goGone, "click", null);
            assertEquals("0", act(b, c, obj(prop("tag", "body", "equals", true)), "get_attribute", "data-go").get("result"), "the Go button above Row B");
            assertTrue(LOG.stream().anyMatch(l -> l.contains("next to its anchor")), "anchor fallback is logged");

            // if the element is not there in time: continue skips the step instead of failing
            Map<String, Object> skipped = b.elementAction(c, obj(prop("id", "never", "equals", true)), null, "click", null, 400, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "continue", null, null, false);
            assertEquals(false, skipped.get("found"));
            assertNull(skipped.get("result"));
            assertEquals("BROWSER.OBJECT_NOT_FOUND", assertThrows(ActionException.class, () -> b.elementAction(c, obj(prop("id", "never", "equals", true)), null, "click", null, 400, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 2000, "error", null, null, false)).code());

            // control types and the actions that go with them (shared with the browser connector)
            assertEquals("Text box", act(b, c, user, "get_control_type", null).get("result"));
            assertEquals("Drop-down list", act(b, c, obj(prop("id", "status", "equals", true)), "get_control_type", null).get("result"));
            assertEquals(new BigDecimal(2), act(b, c, obj(prop("id", "status", "equals", true)), "get_total_items", null).get("result"));
            act(b, c, obj(prop("id", "status", "equals", true)), "select_by_text", "Closed");
            assertEquals(new BigDecimal(2), act(b, c, obj(prop("id", "status", "equals", true)), "get_selected_index", null).get("result"), "counts from 1");
            Map<String, Object> orders = obj(prop("id", "orders", "equals", true));
            assertEquals("Table", act(b, c, orders, "get_control_type", null).get("result"));
            assertEquals(new BigDecimal(3), act(b, c, orders, "get_total_rows", null).get("result"));
            assertEquals(new BigDecimal(2), act(b, c, orders, "get_total_columns", null).get("result"));
            Map<String, Object> cellAt = b.elementAction(c, orders, null, "get_cell_text", null, 3000, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", 3, 2, false);
            assertEquals("20", cellAt.get("result"));
            Map<String, Object> where = (Map<String, Object>) act(b, c, orders, "get_cell_index_by_text", "Amount").get("result");
            assertEquals(new BigDecimal(1), where.get("row")); assertEquals(new BigDecimal(2), where.get("column"));
            assertEquals("BROWSER.VALUE_REQUIRED", assertThrows(ActionException.class, () -> act(b, c, orders, "get_cell_text", null)).code());
            Map<String, Object> box = obj(prop("tag", "input", "equals", true), prop("type", "checkbox", "equals", true));
            b.waitFor(c, null, "#upload", "attached", 3000, "browser", null);
            assertTrue(act(b, c, obj(prop("tag", "body", "equals", true)), "count", null).get("result") instanceof BigDecimal);
            assertEquals(new BigDecimal(0), act(b, c, box, "count", null).get("result"), "no check box on this page");

            // waiting for something to disappear
            assertEquals(true, b.elementAction(c, obj(prop("id", "spinner", "equals", true)), null, "wait_to_disappear", null, 5000, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, false).get("result"));

            // a covered button: a mouse click is refused, a JavaScript click works
            Map<String, Object> covered = obj(prop("id", "covered", "equals", true));
            assertThrows(ActionException.class, () -> b.elementAction(c, covered, null, "click", null, 800, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, false));
            // in the background a click goes straight to the element, so the covered button is pressed even with the mouse method
            b.elementAction(c, covered, null, "click", null, 3000, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, true);
            assertEquals("yes", act(b, c, obj(prop("tag", "body", "equals", true)), "get_attribute", "data-clicked").get("result"));
            b.elementAction(c, covered, null, "click", null, 3000, "javascript", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, false);

            // upload and element screenshot
            Files.writeString(w.resolve("a.txt"), "hello");
            assertEquals(new BigDecimal(1), act(b, c, obj(prop("id", "upload", "equals", true)), "upload_file", "a.txt").get("result"));
            act(b, c, user, "screenshot", "shots/user.png");
            assertTrue(Files.size(w.resolve("shots/user.png")) > 100);

            // every web action takes the captured object, not only the element action
            Map<String, Object> userBox = obj(prop("label", "User name", "equals", true));
            b.typeText(c, userBox, null, "Zoe", false, 3000, "browser", null, null);
            assertEquals("Zoe", b.getAttribute(c, userBox, null, "value", 3000, "browser", null).get("value"));
            Map<String, Object> saveBtn = obj(prop("tag", "button", "equals", true), prop("text", "Save*", "wildcard", true));
            assertEquals("Save item", b.getText(c, saveBtn, null, 3000, "browser", null, null).get("text"));
            Map<String, Object> firstDelete = obj(prop("class", "del", "contains", true));
            b.click(c, firstDelete, null, false, 3000, "browser", null, null);
            assertEquals("0", act(b, c, obj(prop("tag", "body", "equals", true)), "get_attribute", "data-deleted").get("result"));
            b.select(c, obj(prop("id", "status", "equals", true)), null, "Open", 3000, "browser", null, null);
            assertEquals("Open", act(b, c, obj(prop("id", "status", "equals", true)), "get_selected", null).get("result"));
            b.waitFor(c, saveBtn, null, "visible", 3000, "browser", null);
            b.waitFor(c, obj(prop("id", "never-there", "equals", true)), null, "detached", 1000, "browser", null);
            assertTrue(b.exists(c, saveBtn, null, "browser", null));
            assertFalse(b.exists(c, obj(prop("id", "nope", "equals", true)), null, "browser", null));
            assertTrue(b.elementVisible(c, saveBtn, null, "browser", null));
            Map<String, Object> ordersTable = (Map<String, Object>) b.extractTable(c, obj(prop("id", "orders", "equals", true)), null, 3000, "browser", null).get("table");
            assertEquals(List.of("Id", "Amount"), ordersTable.get("columns"));
            b.screenshot(c, "shots/save.png", false, null, "browser", saveBtn, null);
            assertTrue(Files.size(w.resolve("shots/save.png")) > 100);
            assertEquals("BROWSER.NO_ELEMENT", assertThrows(ActionException.class, () -> b.click(c, null, null, false, 1000, "browser", null, null)).code());

            // Open in a new tab, then in the current tab; back with steps; too many steps is an error when asked; close only the tab
            String home = "http://127.0.0.1:" + s.getAddress().getPort() + "/";
            b.open(c, home + "?tab=2", "edge", true, null, "browser", false, "playwright", "new_tab", null, 30000);
            assertTrue(String.valueOf(b.pageInfo(c, "browser").get("url")).endsWith("?tab=2"));
            b.open(c, home + "?tab=3", "edge", true, null, "browser", false, "playwright", "existing_tab", null, 30000);
            assertTrue(String.valueOf(b.pageInfo(c, "browser").get("url")).endsWith("?tab=3"));
            b.history(c, "back", "browser", 1, false, null);
            assertTrue(String.valueOf(b.pageInfo(c, "browser").get("url")).endsWith("?tab=2"));
            assertEquals("BROWSER.HISTORY_TOO_SHORT", assertThrows(ActionException.class, () -> b.history(c, "back", "browser", 9, true, null)).code());
            b.close(c, "browser", "tab", null);
            assertEquals(home, b.pageInfo(c, "browser").get("url"), "closing a tab leaves the session on the other tab");

            // the selector still works for older steps, and one of the two is required
            assertEquals("Save item", b.elementAction(c, null, "#save-123456", "get_text", null, 3000, "mouse", null, null, null, "fill", 0, true, false, 0, false, "browser", null, null, 0, "error", null, null, false).get("result"));
            assertEquals("BROWSER.NO_ELEMENT", assertThrows(ActionException.class, () -> act(b, c, null, "click", null)).code());
        } finally {
            try { b.close(c, "browser", "session", null); } catch (RuntimeException ignored) { }
            s.stop(0);
        }
    }
}
