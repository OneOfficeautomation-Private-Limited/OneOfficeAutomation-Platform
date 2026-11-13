package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * My account (13 Nov 2026 round 3, item 9, stage A), as AA's user menu: a person sees their own profile, where they are
 * signed in (and signs out everywhere else, recorded in the Audit Trail), and the runners they work with.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyAccountTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-my-account");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @SuppressWarnings("unchecked")
    void aPersonSeesTheirProfileSessionsAndRunners() {
        Map<String, Object> p = cr.myAccount.profile(admin);
        assertEquals("admin", p.get("username"));
        assertTrue(((List<String>) p.get("roles")).contains("admin"));
        assertEquals("password", p.get("sign_in"));
        assertTrue(p.containsKey("last_login"));
        assertNotNull(p.get("license_seats"));

        // two more sign-ins elsewhere; signing out the others keeps only this one
        String here = (String) cr.login("admin", "Admin-Passw0rd-2026", "10.0.0.5").get("token");
        cr.login("admin", "Admin-Passw0rd-2026", "10.0.0.6");
        List<Map<String, Object>> s = cr.myAccount.sessions(admin, here);
        assertTrue(s.size() >= 3);
        assertEquals(1, s.stream().filter((x) -> Boolean.TRUE.equals(x.get("current"))).count());
        assertTrue(s.stream().noneMatch((x) -> String.valueOf(x.get("id")).length() > 12), "never the token itself");
        assertTrue(cr.myAccount.signOutOthers(admin, here, null) >= 2);
        assertEquals(1, cr.myAccount.sessions(admin, here).size());
        assertNotNull(cr.session(here));

        // runners: none at first; a default runner shows with its status
        assertEquals(List.of(), cr.myAccount.devices(admin).get("runners"));
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        cr.setDefaultDevice(admin, "admin", (String) d.get("device_id"), null);
        Map<String, Object> mine = cr.myAccount.devices(admin);
        assertEquals(d.get("device_id"), mine.get("default_runner"));
        Map<String, Object> pc = ((List<Map<String, Object>>) mine.get("runners")).get(0);
        assertEquals("PC-1", pc.get("name"));
        assertEquals(Boolean.TRUE, pc.get("default"));
        assertTrue(List.of("online", "offline").contains(pc.get("status")));
    }
}
