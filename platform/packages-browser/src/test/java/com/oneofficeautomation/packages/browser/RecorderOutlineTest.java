package com.oneofficeautomation.packages.browser;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The web recorder outlines the element a click would record, as Capture does, and the outline turns green when a step is
 * recorded. Runs in the installed Edge or Chrome (headless, nothing downloaded); skipped where neither starts.
 */
class RecorderOutlineTest {
    static final String PAGE = """
            <html><head><title>Form</title></head><body style="margin:40px">
              <button id="save" style="width:120px;height:30px"><span id="inner">Save</span></button>
              <p id="text" style="width:300px">Plain text</p>
            </body></html>""";

    @SuppressWarnings("unchecked")
    @Test
    void outlinesTheElementUnderThePointerAndFlashesWhenRecorded() {
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(BrowserPackage.browserEnv(new HashMap<>(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))))) {
            Browser b = null;
            for (String channel : List.of("msedge", "chrome")) {
                try { b = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setChannel(channel)); break; }
                catch (PlaywrightException notHere) { /* try the other browser */ }
            }
            Assumptions.assumeTrue(b != null, "No Edge or Chrome can be started here");
            BrowserContext c = b.newContext();
            c.exposeBinding("__ooaRecord", (source, args) -> { events.add((Map<String, Object>) args[0]); return null; });
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

            p.click("#save");
            p.waitForTimeout(50);
            assertEquals(1, events.size());
            assertEquals("click", events.get(0).get("kind"));
            assertEquals("rgb(29, 122, 70)", p.evaluate("document.getElementById('__ooa_hl').style.borderColor"), "green for a moment when recorded");
            p.waitForTimeout(600);
            assertEquals("rgb(163, 113, 237)", p.evaluate("document.getElementById('__ooa_hl').style.borderColor"));

            // stopping removes the outline and the bar, as the recorder does at the end
            p.evaluate("() => { window.__ooaRecOff = true; for (const id of ['__ooa_bar', '__ooa_hl']) { const b = document.getElementById(id); if (b) b.remove(); } }");
            p.hover("#save");
            assertEquals("none", p.evaluate(outline.replace("h ? [", "h && h.style.display !== 'none' ? [")));
            b.close();
        }
    }
}
