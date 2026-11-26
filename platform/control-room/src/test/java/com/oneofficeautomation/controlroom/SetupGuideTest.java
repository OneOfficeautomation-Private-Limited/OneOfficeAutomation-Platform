package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Setup > Install and set up (26 Nov 2026): the install guide's phases and live checks, the sample agent, and who
 * sees it in the menu (administrators, unless an administrator switched it off for everyone).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SetupGuideTest {
    Db db; ControlRoom cr; ControlRoom.User admin, viewer;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-setup");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        String temp = (String) cr.createUser(admin, "vera", "Vera", "viewer", null).get("temporary_password");
        viewer = cr.session((String) cr.login("vera", temp, null).get("token"));
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> byId(Map<String, Object> guide) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Object p : (List<Object>) guide.get("phases"))
            for (Object s : (List<Object>) ((Map<String, Object>) p).get("steps")) out.put(String.valueOf(((Map<String, Object>) s).get("id")), (Map<String, Object>) s);
        return out;
    }

    @Test @SuppressWarnings("unchecked")
    void fourPhasesWithLiveChecks() {
        Map<String, Object> g = cr.setup.steps(admin, false);
        assertEquals(List.of("server", "people", "developers", "first_run"), ((List<Map<String, Object>>) g.get("phases")).stream().map(p -> p.get("id")).toList());
        Map<String, Map<String, Object>> s = byId(g);
        assertTrue(s.keySet().containsAll(List.of("install", "database", "https", "license", "people", "runners", "studio", "model", "sample")), s.keySet().toString());
        assertEquals("done", s.get("install").get("status"));
        assertEquals("todo", s.get("database").get("status"), "the built-in database is for trying OOA");
        assertEquals("ooa db configure", s.get("database").get("command"));
        assertEquals("todo", s.get("https").get("status"));
        assertEquals("ooa tls self-signed", s.get("https").get("command"));
        assertEquals("todo", s.get("runners").get("status"));
        assertEquals("devices", s.get("runners").get("page"));
        assertEquals(true, s.get("model").get("optional"));
        int total = ((Number) g.get("total")).intValue(), done = ((Number) g.get("done")).intValue();
        assertTrue(done >= 1 && done < total, done + " of " + total);
        // over HTTPS with a public address the step is done
        cr.platform.updateSettings(admin, Map.of("public_url", "https://ooa.example.com"), null);
        try { assertEquals("done", byId(cr.setup.steps(admin, true)).get("https").get("status")); }
        finally { cr.platform.updateSettings(admin, Map.of("public_url", ""), null); }
        assertThrows(OoaException.class, () -> cr.setup.steps(viewer, false), "administrators only");
    }

    @Test
    void theSampleAgentIsCheckedInAndRunsOnARunnerOnline() {
        OoaException none = assertThrows(OoaException.class, () -> cr.setup.runSample(admin, null));
        assertEquals("SETUP.NO_RUNNER", none.code());
        String device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-9", "pc-9", "Windows 11", "0.2.0", null).get("device_id");
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now(), device);
        Map<String, Object> run = cr.setup.runSample(admin, null);
        assertNotNull(run.get("run_id"));
        assertNotNull(db.one("SELECT path FROM bots WHERE path = ?", SetupGuide.SAMPLE), "checked in");
        assertEquals("Last sample run: queued", byId(cr.setup.steps(admin, false)).get("sample").get("detail"));
        cr.setup.runSample(admin, null);   // a second time: no second check-in, another run
        assertEquals(1, cr.count("SELECT COUNT(*) AS n FROM bots WHERE path = ?", SetupGuide.SAMPLE));
        assertEquals("done", byId(cr.setup.steps(admin, false)).get("runners").get("status"));
    }

    @Test
    void theMenuShowsSetupToAdministratorsUnlessSwitchedOff() {
        assertEquals(true, cr.me(admin).get("setup_menu"));
        assertEquals(false, cr.me(viewer).get("setup_menu"));
        assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("setup_menu", "everyone"), null));
        cr.platform.updateSettings(admin, Map.of("setup_menu", "off"), null);
        try {
            assertEquals(false, cr.me(admin).get("setup_menu"));
            assertEquals("off", cr.setup.steps(admin, false).get("menu"), "the page still opens from its address");
        } finally { cr.platform.updateSettings(admin, Map.of("setup_menu", "admins"), null); }
    }
}
