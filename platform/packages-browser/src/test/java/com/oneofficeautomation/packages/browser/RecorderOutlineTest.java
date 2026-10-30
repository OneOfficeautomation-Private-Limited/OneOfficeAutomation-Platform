package com.oneofficeautomation.packages.browser;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The web recorder outlines the element a click would record, as Capture does, and the outline turns green when a step is
 * recorded; the recorded step keeps a picture of the element. Runs in the installed Edge or Chrome (headless, nothing downloaded); skipped where neither starts.
 */
class RecorderOutlineTest {
    static final String PAGE = """
            <html><head><title>Form</title></head><body style="margin:40px">
              <button id="save" style="width:120px;height:30px"><span id="inner">Save</span></button>
              <p id="text" style="width:300px">Plain text</p>
              <input id="q" style="width:200px">
            </body></html>""";

    @SuppressWarnings("unchecked")
    @Test
    void outlinesTheElementUnderThePointerFlashesAndKeepsAPictureWhenRecorded() throws Exception {
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(BrowserPackage.browserEnv(new HashMap<>(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))))) {
            Browser b = null;
            for (String channel : List.of("msedge", "chrome")) {
                try { b = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setChannel(channel)); break; }
                catch (PlaywrightException notHere) { /* try the other browser */ }
            }
            Assumptions.assumeTrue(b != null, "No Edge or Chrome can be started here");
            BrowserContext c = b.newContext();
            c.exposeBinding("__ooaRecord", (source, args) -> { Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) args[0]); events.add(e); BrowserPackage.withShot(source, e); return null; });
            c.exposeBinding("__ooaStop", (source, args) -> null);
            c.addInitScript(BrowserPackage.RECORDER_JS.replace("/*OOA_LIB*/", WebObjects.LIB_JS));
            Page p = c.newPage();
            // a real page load (setContent rewrites the same window, where the recorder is already marked as on)
            p.navigate("data:text/html," + java.net.URLEncoder.encode(PAGE, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
            String outline = "(() => { const h = document.getElementById('__ooa_hl'); return h ? [h.style.display, Math.round(parseFloat(h.style.width)), Math.round(parseFloat(h.style.height)), h.style.borderColor].join(' ') : 'none'; })()";

            p.hover("#inner");   // the text inside the button: the button is what a click records, so the button is outlined
            assertEquals("block 120 30 rgb(163, 113, 237)", p.evaluate(outline));
            p.hover("#text");
            assertTrue(String.valueOf(p.evaluate(outline)).startsWith("block 300 "), String.valueOf(p.evaluate(outline)));
            p.mouse().move(5, 5);   // the page itself: nothing outlined
            assertTrue(String.valueOf(p.evaluate(outline)).startsWith("none "), String.valueOf(p.evaluate(outline)));

            // the outline's colour just after the click (taking the step's pictures takes longer than the flash lasts)
            p.evaluate("() => document.addEventListener('click', () => setTimeout(() => { window.__flash = document.getElementById('__ooa_hl').style.borderColor; }, 0))");
            p.click("#save");
            p.waitForTimeout(50);
            assertEquals(1, events.size());
            assertEquals("click", events.get(0).get("kind"));
            // each recorded step keeps a picture of its element, as Capture does (the view rectangle is only for that)
            assertTrue(String.valueOf(events.get(0).get("screenshot")).length() > 100, "a picture of the button");
            assertFalse(events.get(0).containsKey("rect"));
            assertNotNull(BrowserPackage.capturedObject(events.get(0)).get("screenshot"));
            assertEquals("rgb(29, 122, 70)", p.evaluate("window.__flash"), "green for a moment when recorded");
            p.waitForTimeout(600);
            assertEquals("rgb(163, 113, 237)", p.evaluate("document.getElementById('__ooa_hl').style.borderColor"));

            // and the whole view with where the element sits in it, as AA shows a capture
            Map<String, Object> ws = (Map<String, Object>) BrowserPackage.capturedObject(events.get(0)).get("window_shot");
            assertTrue(String.valueOf(ws.get("image")).startsWith("/9j/"), "a JPEG");
            double left = ((Number) ws.get("left")).doubleValue(), width = ((Number) ws.get("width")).doubleValue();
            assertTrue(left > 0 && left < 0.2 && width > 0.05 && width < 0.5, ws.get("left") + " " + ws.get("width"));

            // keys that are not typing are steps of their own, after the text typed before them; the text is recorded once
            p.click("#q");
            p.keyboard().type("hello");
            p.keyboard().press("Enter");
            p.keyboard().press("Control+s");
            p.locator("#q").blur();
            p.waitForTimeout(100);
            List<String> kinds = events.stream().map(e -> e.get("kind") + (e.get("key") != null ? ":" + e.get("key") : e.get("value") != null && !"click".equals(e.get("kind")) ? "=" + e.get("value") : "")).toList();
            assertEquals(List.of("click", "type=hello", "key:ENTER", "key:CTRL+S"), kinds.subList(0, 4), kinds.toString());
            assertEquals(4, kinds.size(), "the blur after Enter does not record the text again: " + kinds);
            Map<String, Object> steps = BrowserPackage.stepsFromEvents(BrowserGapsTest.ctx(java.nio.file.Files.createTempDirectory("rec")), new ArrayList<>(events), null, "edge", false);
            Map<String, Object> enter = (Map<String, Object>) ((List<Object>) steps.get("steps")).get(2);
            assertEquals("ooa.browser/element_action", enter.get("action"));
            assertEquals("press_key", ((Map<String, Object>) enter.get("args")).get("action"));
            assertEquals("ENTER", ((Map<String, Object>) enter.get("args")).get("value"));

            // stopping removes the outline and the bar, as the recorder does at the end
            p.evaluate("() => { window.__ooaRecOff = true; for (const id of ['__ooa_bar', '__ooa_hl']) { const b = document.getElementById(id); if (b) b.remove(); } }");
            p.hover("#save");
            assertEquals("none", p.evaluate(outline.replace("h ? [", "h && h.style.display !== 'none' ? [")));

            // an address typed in the address bar is a "go to" step; the page it left names the window in the person's browser
            List<Frame> frames = new CopyOnWriteArrayList<>();
            BrowserPackage.watchAddressBar(BrowserGapsTest.ctx(java.nio.file.Files.createTempDirectory("rec")), p, events, frames);
            String typed = "data:text/html,%3Ctitle%3ENext%3C%2Ftitle%3E";
            com.google.gson.JsonObject go = new com.google.gson.JsonObject();
            go.addProperty("url", typed); go.addProperty("transitionType", "typed");
            c.newCDPSession(p).send("Page.navigate", go);
            p.waitForURL(typed);
            p.waitForTimeout(200);
            Map<String, Object> nav = events.get(events.size() - 1);
            assertEquals("navigate", nav.get("kind"));
            assertEquals(typed, nav.get("url"));
            assertEquals("Form", ((Map<String, Object>) nav.get("page")).get("title"));
            Map<String, Object> inTab = (Map<String, Object>) ((List<Object>) BrowserPackage.stepsFromEvents(BrowserGapsTest.ctx(java.nio.file.Files.createTempDirectory("rec")),
                    List.of(nav), null, "edge", true).get("steps")).get(0);
            assertEquals("ooa.browser/open", inTab.get("action"));
            Map<String, Object> a = (Map<String, Object>) inTab.get("args");
            assertEquals("existing_tab", a.get("open_in"));
            assertEquals("Form", ((Map<String, Object>) a.get("window")).get("title"));
            assertEquals("ooa.browser/navigate", ((Map<String, Object>) ((List<Object>) BrowserPackage.stepsFromEvents(BrowserGapsTest.ctx(java.nio.file.Files.createTempDirectory("rec")),
                    List.of(nav), null, "chromium", false).get("steps")).get(0)).get("action"));
            b.close();
        }
    }
}
