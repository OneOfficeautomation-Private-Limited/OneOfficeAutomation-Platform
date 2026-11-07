package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G69: the console as an installable app on phones: manifest, icons and a service worker that keeps nothing. */
class MobileAppTest {
    @Test @SuppressWarnings("unchecked")
    void theConsoleServesWhatPhonesNeedToInstallIt() throws Exception {
        Path data = Files.createTempDirectory("ooa-mobile");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port() + "/next/";
            HttpResponse<String> m = http.send(HttpRequest.newBuilder(URI.create(base + "manifest.webmanifest")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, m.statusCode());
            assertEquals("application/manifest+json", m.headers().firstValue("Content-Type").orElse(""));
            Map<String, Object> man = (Map<String, Object>) JsonLite.parse(m.body());
            assertEquals("./#/inbox", man.get("start_url"));
            assertEquals("standalone", man.get("display"));
            assertEquals(3, ((List<?>) man.get("icons")).size());

            HttpResponse<byte[]> icon = http.send(HttpRequest.newBuilder(URI.create(base + "icon-512.png")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("image/png", icon.headers().firstValue("Content-Type").orElse(""));
            assertEquals(512, ImageIO.read(new ByteArrayInputStream(icon.body())).getWidth());

            HttpResponse<String> sw = http.send(HttpRequest.newBuilder(URI.create(base + "sw.js")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(sw.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
            assertTrue(sw.body().contains("e.request.mode !== \"navigate\""), "only page loads are answered");
            assertFalse(sw.body().contains("caches."), "nothing is kept on the phone");
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "icon-64.png")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { server.stop(); cr.triggerPollers.stop(); db.close(); }
    }
}
