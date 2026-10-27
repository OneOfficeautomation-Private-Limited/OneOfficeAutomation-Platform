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

/** Click element's "then wait for" (F12) and file uploads (X5), against a real headless browser when one starts. */
class ClickWaitUploadTest {

    static final String PAGE = """
            <html><head><title>Wait</title></head><body>
              <button id="next" onclick="document.getElementById('spin').style.display='block';
                setTimeout(() => { document.getElementById('spin').style.display='none'; const d = document.createElement('p'); d.id = 'done'; d.innerText = 'page 2'; document.body.append(d); }, 700)">Next</button>
              <div id="spin" style="display:none">loading</div>
              <input type="file" id="f" multiple onchange="document.title = [...this.files].map(x => x.name).join(',')">
            </body></html>""";

    @Test void clickThenWaitsAndFilesUpload() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        s.start();
        Path w = Files.createTempDirectory("ooa-click-wait");
        ActionContext c = BrowserMoreOptionsTest.ctx(w);
        BrowserPackage b = new BrowserPackage();

        // a wait needs its element, checked before anything is clicked
        assertEquals("BROWSER.BAD_INPUT", assertThrows(ActionException.class,
            () -> b.click(c, null, "#next", false, 1000, "none", null, null, "mouse", false, "element_visible", "")).code());
        assertEquals("BROWSER.BAD_INPUT", assertThrows(ActionException.class, () -> BrowserPackage.uploadFiles(c, null)).code());
        assertEquals("BROWSER.FILE_NOT_FOUND", assertThrows(ActionException.class, () -> BrowserPackage.uploadFiles(c, "missing.txt")).code());
        try {
            try { b.open(c, "http://127.0.0.1:" + s.getAddress().getPort() + "/", "edge", true, null, "browser", false, "playwright", "new_window", null, 60000); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }

            long t0 = System.currentTimeMillis();
            b.click(c, null, "#next", false, 10000, "browser", null, null, "mouse", false, "element_visible", "#done");
            assertTrue(System.currentTimeMillis() - t0 >= 600, "it waited for page 2");
            assertEquals("page 2", b.runJs(c, "document.getElementById('done').innerText", "browser", null, null).get("result"), "no Delay needed");

            // the spinner case: click, then wait until the loading text is gone
            b.click(c, null, "#next", false, 10000, "browser", null, null, "mouse", false, "element_gone", "#spin");
            assertEquals("none", b.runJs(c, "document.getElementById('spin').style.display", "browser", null, null).get("result"));

            Files.writeString(w.resolve("a.txt"), "hello");
            Files.writeString(w.resolve("b.csv"), "x,y");
            assertEquals(List.of(w.resolve("a.txt"), w.resolve("b.csv")), BrowserPackage.uploadFiles(c, "a.txt; b.csv"));
            assertEquals(List.of(w.resolve("a.txt")), BrowserPackage.uploadFiles(c, List.of("a.txt")));
        } finally {
            try { b.close(c, "browser", "session", null); } catch (RuntimeException ignored) { }
            s.stop(0);
        }
    }
}
