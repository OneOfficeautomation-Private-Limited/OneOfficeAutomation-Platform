package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Help links (13 Nov 2026 round 3, item 6): where the Docs and Video links point is set in Settings (a company's own
 * copy for servers with no internet), and links changed after a release come from <data>/config/help-links.json, read
 * on each call so no rebuild or restart is needed; a broken file is reported and ignored.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelpLinksTest {
    Path data; Db db; ControlRoom cr; ControlRoom.User admin; ControlRoomServer server;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-help-links");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @SuppressWarnings("unchecked")
    void addressesComeFromSettingsAndLinksFromTheFileReadEachTime() throws Exception {
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("help_docs_base", "docs.example.com"), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("help_video_base", "javascript:alert(1)"), null)));
        cr.platform.updateSettings(admin, Map.of("help_docs_base", "https://intranet.example.com/ooa-docs/"), null);

        Map<String, Object> first = server.helpLinks();
        assertEquals(ControlRoom.VERSION, first.get("version"));
        assertEquals("https://intranet.example.com/ooa-docs/", first.get("docs_base"));
        assertEquals("", first.get("video_base"));
        assertEquals(Map.of(), first.get("links"));

        Path file = data.resolve("config").resolve("help-links.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"links\": {\"page:schedules\": {\"video\": \"https://youtu.be/abc\"}}}", StandardCharsets.UTF_8);
        Map<String, Object> links = (Map<String, Object>) server.helpLinks().get("links");
        assertEquals("https://youtu.be/abc", ((Map<String, Object>) links.get("page:schedules")).get("video"));   // no restart

        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        Map<String, Object> broken = server.helpLinks();
        assertEquals(Map.of(), broken.get("links"));
        assertTrue(String.valueOf(broken.get("problem")).startsWith("help-links.json could not be read"));
    }
}
