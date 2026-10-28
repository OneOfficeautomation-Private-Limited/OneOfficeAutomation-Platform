package com.oneofficeautomation.agent;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runner life cycle: temporary (VDI) runners expire with their seat; runner secrets rotate without a gap. */
class RunnerLifecycleTest {
    Path root; Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeEach void setUp() throws Exception {
        root = Files.createTempDirectory("ooa-runner-life");
        Path data = Files.createDirectories(root.resolve("data"));
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t), pw, "Admin-Passw0rd-2026", t, null);
        admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
    }
    @AfterEach void tearDown() { db.close(); }

    String register(boolean temporary, String name) {
        return (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, temporary, null).get("key"), name, name.toLowerCase(), "Windows 11", "0.2.0", null).get("device_id");
    }
    void offlineSince(String id, Duration ago) { db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now().minus(ago), id); }
    boolean exists(String id) { return db.one("SELECT id FROM devices WHERE id = ?", id) != null; }

    @Test void temporaryRunnersExpireWithTheirSeat() {
        String vdi = register(true, "VDI-1"), keep = register(false, "PC-1"), busy = register(true, "VDI-2");
        assertEquals("temporary", db.one("SELECT lifespan FROM devices WHERE id = ?", vdi).get("lifespan"));
        db.update("INSERT INTO license_assignments (principal_type, principal, license_type, assigned, assigned_by) VALUES ('device', ?, 'unattended_runner', ?, 'admin')", vdi, Instant.now());
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"nightly\",\"name\":\"n\",\"description\":\"t\",\"variables\":[],\"steps\":[]}");
        cr.checkin(admin, "Ops/Nightly.flow.json", flow, "v1", true, null);
        cr.createSchedule(admin, Map.of("name", "nightly", "bot_path", "Ops/Nightly.flow.json", "device_id", busy, "kind", "daily", "at_time", "02:00"), null);
        for (String id : List.of(vdi, keep, busy)) offlineSince(id, Duration.ofHours(9));
        cr.reap();
        assertFalse(exists(vdi), "a temporary runner offline longer than 8 hours is removed");
        assertNull(db.one("SELECT principal FROM license_assignments WHERE principal = ?", vdi), "and its seat is free");
        assertTrue(exists(keep), "persistent runners stay");
        assertTrue(exists(busy), "a temporary runner a schedule uses stays");
        assertNotNull(db.one("SELECT seq FROM audit WHERE action = 'device.expired' AND object_id = ?", vdi));
    }

    @Test void deletingARunnerFreesItsSeat() {
        String id = register(false, "PC-2");
        db.update("INSERT INTO license_assignments (principal_type, principal, license_type, assigned, assigned_by) VALUES ('device', ?, 'unattended_runner', ?, 'admin')", id, Instant.now());
        cr.deleteDevice(admin, id, null);
        assertNull(db.one("SELECT principal FROM license_assignments WHERE principal = ?", id));
    }

    @Test @SuppressWarnings("unchecked")
    void runnerSecretsRotateWithoutAGap() throws Exception {
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            Path conf = root.resolve("agent").resolve("agent.json");
            String key = (String) cr.createRegistrationKey(admin, 1, false, null).get("key");
            Map<String, Object> reg = Agent.register("http://127.0.0.1:" + server.port(), key, "R-rot", conf);
            String id = (String) reg.get("device_id");
            String oldSecret = (String) ((Map<String, Object>) JsonLite.parse(Files.readString(conf))).get("secret");
            Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", id);
            assertNull(cr.heartbeat(d, Map.of()).get("rotate"), "a new secret is not due");

            db.update("UPDATE devices SET secret_set = ? WHERE id = ?", Instant.now().minus(Duration.ofDays(31)), id);
            assertEquals(true, cr.heartbeat(db.one("SELECT * FROM devices WHERE id = ?", id), Map.of()).get("rotate"), "older than 30 days: rotate");

            Agent agent = new Agent(conf, root.resolve("ws"));
            agent.rotateSecret();
            String newSecret = (String) ((Map<String, Object>) JsonLite.parse(Files.readString(conf))).get("secret");
            assertNotEquals(oldSecret, newSecret, "the config file holds the new secret");
            assertFalse(Files.exists(conf.resolveSibling("agent.json.new")));
            assertNotNull(cr.device(id, newSecret));
            assertNotNull(cr.device(id, oldSecret), "the old secret still works for a few minutes");
            db.update("UPDATE devices SET secret_prev_until = ? WHERE id = ?", Instant.now().minusSeconds(1), id);
            assertNull(cr.device(id, oldSecret), "then it stops working");
            assertNull(cr.heartbeat(db.one("SELECT * FROM devices WHERE id = ?", id), Map.of()).get("rotate"));
            agent.heartbeat();   // the running runner uses the new secret
            assertNotNull(db.one("SELECT seq FROM audit WHERE action = 'device.rotate_secret' AND object_id = ?", id));
        } finally { server.stop(); }
    }
    @Test @SuppressWarnings("unchecked")
    void aRunnerFollowsTheCommandCenterToANewAddressOnlyWhenItKnowsTheRunner() throws Exception {
        // the "moved" Command Center serves the same data on another address; a fresh one does not know the runner
        ControlRoomServer old = new ControlRoomServer(cr, "127.0.0.1", 0), moved = new ControlRoomServer(cr, "127.0.0.1", 0);
        Path otherData = Files.createDirectories(root.resolve("other"));
        Db otherDb = Db.embedded(otherData);
        ControlRoomServer stranger = new ControlRoomServer(new ControlRoom(otherDb, otherData, FirstPartyPackages.registry(), null), "127.0.0.1", 0);
        old.start(); moved.start(); stranger.start();
        try {
            Path conf = root.resolve("agent-move").resolve("agent.json");
            Agent.register("http://127.0.0.1:" + old.port(), (String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "R-move", conf);
            String before = Files.readString(conf);

            OoaExceptionCheck.expect("AGENT.MOVE_REFUSED", () -> Agent.moveTo(conf, "http://127.0.0.1:" + stranger.port(), null));
            assertEquals(before, Files.readString(conf), "a refused move changes nothing");
            OoaExceptionCheck.expect("AGENT.BAD_URL", () -> Agent.moveTo(conf, "ftp://elsewhere", null));

            assertEquals("http://127.0.0.1:" + old.port(), Agent.moveTo(conf, "http://127.0.0.1:" + moved.port() + "/", null));
            Map<String, Object> cfg = (Map<String, Object>) JsonLite.parse(Files.readString(conf));
            assertEquals("http://127.0.0.1:" + moved.port(), cfg.get("url"));
            Agent agent = new Agent(conf, root.resolve("ws-move"));
            agent.heartbeat();   // works against the new address with the same registration
        } finally { old.stop(); moved.stop(); stranger.stop(); otherDb.close(); }
    }

    @Test
    void setupRunAgainKeepsAWorkingRegistration() throws Exception {
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.port();
            Path conf = root.resolve("agent-keep").resolve("agent.json");
            assertNull(Agent.existingRegistration(url, conf), "no registration yet");
            String id = (String) Agent.register(url, (String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "R-keep", conf).get("device_id");

            Map<String, Object> kept = Agent.existingRegistration(url + "/", conf);
            assertEquals(id, kept.get("device_id"));
            assertEquals("R-keep", kept.get("name"));
            assertNull(Agent.existingRegistration("http://127.0.0.1:1", conf), "another Command Center: register anew");

            cr.setDeviceEnabled(admin, id, false, null);
            assertNull(Agent.existingRegistration(url, conf), "a disabled runner is not kept");
        } finally { server.stop(); }
    }

    /** assertThrows for the product's error codes. */
    static final class OoaExceptionCheck {
        interface Call { void run() throws Exception; }
        static void expect(String code, Call c) {
            try { c.run(); fail("expected " + code); }
            catch (com.oneofficeautomation.sdk.OoaException e) { assertEquals(code, e.code(), e.getMessage()); }
            catch (Exception e) { fail("expected " + code + " but got " + e); }
        }
    }
}
