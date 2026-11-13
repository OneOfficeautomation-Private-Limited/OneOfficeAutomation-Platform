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

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (com.oneofficeautomation.sdk.OoaException e) { return e.code(); } }

    /** Stage B, as AA's My settings: edit the profile, choose the default runner, connect this computer, device credentials. */
    @Test @SuppressWarnings("unchecked")
    void stageBAsAasMySettings() {
        // a runner (bot_runner) person, with the two new permissions by role
        ControlRoom.User rita = person("rita", "bot_runner");
        ControlRoom.User me = rita;

        // B1: own name and e-mail; a bad address is refused
        cr.myAccount.updateProfile(me, Map.of("first_name", "Rita", "last_name", "Rao", "email", "Rita@Example.com"), null);
        Map<String, Object> p = cr.myAccount.profile(me);
        assertEquals("rita@example.com", p.get("email"));
        assertEquals("Rita Rao", db.one("SELECT display_name FROM users WHERE username = 'rita'").get("display_name"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.myAccount.updateProfile(me, Map.of("email", "not-an-address"), null)));

        // B3: Connect to my computer: the runner it registers is hers and her default runner
        String key = (String) cr.myAccount.connectKey(me, null).get("key");
        Map<String, Object> pc = cr.registerDevice(key, "RITA-PC", "rita-pc", "Windows 11", "0.2.0", null);
        Map<String, Object> mine = cr.myAccount.devices(me);
        assertEquals(pc.get("device_id"), mine.get("default_runner"));
        Map<String, Object> r0 = ((List<Map<String, Object>>) mine.get("runners")).get(0);
        assertEquals(Boolean.TRUE, r0.get("local"));
        assertEquals(Boolean.TRUE, mine.get("can_connect"));

        // B2: choose another default runner from the ones she may use
        Map<String, Object> other = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "SHARED-1", "shared-1", "Windows Server", "0.2.0", null);
        assertTrue(cr.myAccount.runnerChoices(me).stream().anyMatch((d) -> d.get("id").equals(other.get("device_id"))));
        cr.myAccount.chooseDefault(me, (String) other.get("device_id"), null);
        assertEquals(other.get("device_id"), cr.myAccount.devices(me).get("default_runner"));
        ControlRoom.User vic = person("vic", "viewer");
        assertEquals("CR.FORBIDDEN", code(() -> cr.myAccount.connectKey(vic, null)));   // a viewer has neither permission
        assertEquals("CR.FORBIDDEN", code(() -> cr.myAccount.chooseDefault(vic, (String) other.get("device_id"), null)));

        // B4: device credentials: kept for her alone, sent to her runner with a heartbeat only when she asks, answered once
        assertEquals("CR.BAD_INPUT", code(() -> DeviceLogin.request(cr, me, (String) pc.get("device_id"), false, null)));   // none set yet
        DeviceLogin.set(cr, me, null, "RITA-PC\\rita", "Windows-Pass-1", null);
        assertEquals(Boolean.TRUE, cr.myAccount.devices(me).get("credentials_set"));
        assertArrayEquals(new String[] { "RITA-PC\\rita", "Windows-Pass-1" }, DeviceLogin.get(cr, "rita", (String) pc.get("device_id")));
        assertNull(DeviceLogin.get(cr, "admin", (String) pc.get("device_id")), "bound to her: nobody else's");
        Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", pc.get("device_id"));
        assertNull(cr.heartbeat(d, Map.of()).get("device_login"), "nothing goes out unasked");
        DeviceLogin.request(cr, me, (String) pc.get("device_id"), false, null);
        Map<String, Object> sent = (Map<String, Object>) cr.heartbeat(d, Map.of()).get("device_login");
        assertEquals("Windows-Pass-1", sent.get("password"));
        assertNull(cr.heartbeat(d, Map.of()).get("device_login"), "sent once");
        DeviceLogin.receive(cr, d, Map.of("ok", true));
        Map<String, Object> state = (Map<String, Object>) ((List<Map<String, Object>>) cr.myAccount.devices(me).get("runners")).stream()
            .filter((x) -> x.get("id").equals(pc.get("device_id"))).findFirst().orElseThrow().get("login");
        assertEquals("ok", state.get("state"));
        // a runner that is not hers is refused, and the audit never holds the password
        assertEquals("CR.FORBIDDEN", code(() -> DeviceLogin.request(cr, me, "dev_nobody", false, null)));
        assertTrue(db.query("SELECT details FROM audit").stream().noneMatch((a) -> String.valueOf(a.get("details")).contains("Windows-Pass-1")));
    }

    /** A new person with a role, signed in after changing their temporary password. */
    private ControlRoom.User person(String name, String role) {
        String temp = (String) cr.createUser(admin, name, null, role, null).get("temporary_password");
        String t = (String) cr.login(name, temp, null).get("token");
        cr.changePassword(cr.session(t), temp, "Passw0rd-" + name + "-2026!", t, null);
        return cr.session((String) cr.login(name, "Passw0rd-" + name + "-2026!", null).get("token"));
    }
}
