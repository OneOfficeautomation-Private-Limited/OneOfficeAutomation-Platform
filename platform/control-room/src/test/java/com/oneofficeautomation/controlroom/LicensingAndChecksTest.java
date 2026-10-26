package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Licensing (signed files, seats, expiry and grace) and the enterprise checks: settings, passwords, rate limits, allow list, syslog, readiness, integrity, runner versions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LicensingAndChecksTest {
    Db db; ControlRoom cr; ControlRoom.User admin, dev, clerk;
    String device;
    java.security.PrivateKey priv;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> agent(String id) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],\"steps\":[]}");
    }

    String license(LocalDate expires, int dev, int unattended) {
        Map<String, Integer> seats = new LinkedHashMap<>(Map.of("developer", dev, "citizen_developer", 0, "attended_runner", 1, "unattended_runner", unattended));
        return new License("LIC-T-" + expires, "Test Customer", "Enterprise", LocalDate.of(2026, 1, 1), expires, 14, seats, "").sign(priv);
    }

    void today(LocalDate d) { cr.licensing.clock = Clock.fixed(d.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC); }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-licensing");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        dev = RepositoryLifecycleTest.person(cr, admin, "dana", "bot_creator");
        clerk = RepositoryLifecycleTest.person(cr, admin, "carl", "bot_creator");
        device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
        Map<String, String> kp = License.newKeyPair();
        priv = License.privateKey(kp.get("private"));
        cr.licensing.useKeyForTests(License.publicKey(kp.get("public")));
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @Order(1)
    void withoutALicenseNothingIsLimited() {
        assertEquals("unlicensed", cr.licensing.state());
        cr.checkin(dev, "L/A.flow.json", agent("la"), "v1", true, null);
        assertNotNull(cr.startRun("dana", "bot_creator", "L/A.flow.json", device, Map.of(), "manual", null).get("run_id"));
        assertEquals(-1, ((Map<?, ?>) ((Map<?, ?>) cr.licensing.status(admin).get("seats")).get("developer")).get("total"));
    }

    @Test @Order(2)
    void licenseFilesMustBeSignedByTheVendorKey() {
        String good = license(LocalDate.of(2027, 6, 30), 1, 1);
        // a changed payload, a foreign key and a wrong format are all refused
        Map<String, Object> f = new LinkedHashMap<>(castMap(JsonLite.parse(good)));
        String terms = new String(Base64.getDecoder().decode((String) f.get("payload")), StandardCharsets.UTF_8).replace("\"developer\":1", "\"developer\":99");
        f.put("payload", Base64.getEncoder().encodeToString(terms.getBytes(StandardCharsets.UTF_8)));
        assertEquals("LICENSE.INVALID", code(() -> cr.licensing.install(admin, JsonLite.write(f), null)));
        String foreign = new License("X", "Y", "E", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 14, Map.of(), "").sign(License.privateKey(License.newKeyPair().get("private")));
        assertEquals("LICENSE.INVALID", code(() -> cr.licensing.install(admin, foreign, null)));
        assertEquals("LICENSE.INVALID", code(() -> cr.licensing.install(admin, "{\"hello\":1}", null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.licensing.install(dev, good, null)), "only administrators install licenses");
        // the shipped product only trusts the vendor key: a file signed with the test key does not verify against it
        assertEquals("LICENSE.INVALID", code(() -> License.verify(good, License.vendorKey())));
        today(LocalDate.of(2026, 11, 1));
        Map<String, Object> st = cr.licensing.install(admin, good, null);
        assertEquals("active", st.get("state"));
        assertEquals("Test Customer", st.get("customer"));
    }

    @Test @Order(3)
    void seatsAreCountedAndLimitTheWork() {
        // checking in needs a developer seat now
        assertEquals("CR.LICENSE_REQUIRED", code(() -> cr.checkin(dev, "L/B.flow.json", agent("lb"), "v1", true, null)));
        cr.licensing.assign(admin, "developer", "dana", true, null);
        cr.checkin(dev, "L/B.flow.json", agent("lb"), "v1", true, null);
        assertEquals("CR.LICENSE_LIMIT", code(() -> cr.licensing.assign(admin, "developer", "carl", true, null)), "one developer seat only");
        assertEquals("CR.FORBIDDEN", code(() -> cr.licensing.assign(dev, "developer", "carl", true, null)));
        // the runner has no unattended seat: scheduled or API work is refused, a hand-started run by an attended person on their own runner is allowed
        assertEquals("CR.LICENSE_REQUIRED", code(() -> cr.startRun("admin", "admin", "L/B.flow.json", device, Map.of(), "api", null)));
        cr.licensing.assign(admin, "attended_runner", "carl", true, null);
        db.update("INSERT INTO user_devices (username, device_id, updated, updated_by) VALUES (?,?,?,?)", "carl", device, Instant.now(), "admin");
        assertNotNull(cr.startRun("carl", "bot_creator", "L/B.flow.json", device, Map.of(), "manual", null).get("run_id"));
        assertEquals("CR.LICENSE_REQUIRED", code(() -> cr.startRun("carl", "bot_creator", "L/B.flow.json", device, Map.of(), "schedule", null)));
        cr.licensing.assign(admin, "unattended_runner", device, true, null);
        assertNotNull(cr.startRun("admin", "admin", "L/B.flow.json", device, Map.of(), "api", null).get("run_id"));
        Map<?, ?> seats = (Map<?, ?>) cr.licensing.status(admin).get("seats");
        assertEquals(1L, ((Map<?, ?>) seats.get("developer")).get("used"));
        assertEquals(1, ((Map<?, ?>) seats.get("developer")).get("total"));
        // a license with fewer seats than are assigned cannot be installed
        assertEquals("LICENSE.TOO_FEW_SEATS", code(() -> cr.licensing.install(admin, license(LocalDate.of(2027, 6, 30), 0, 1), null)));
        assertTrue(JsonLite.write(cr.audit.verify()).contains("\"valid\":true"));
    }

    @Test @Order(4)
    void expiryWarnsThenGraceThenStops() {
        today(LocalDate.of(2027, 6, 1));
        assertEquals("expiring", cr.licensing.state());
        assertEquals("warning", ((Map<?, ?>) cr.licensing.status(admin).get("banner")).get("level"));
        assertNull(cr.licensing.status(dev).get("banner"), "only administrators are warned before expiry");
        today(LocalDate.of(2027, 6, 25));
        assertEquals("critical", ((Map<?, ?>) cr.licensing.status(admin).get("banner")).get("level"));
        today(LocalDate.of(2027, 7, 5));
        assertEquals("grace", cr.licensing.state());
        assertNotNull(cr.startRun("admin", "admin", "L/B.flow.json", device, Map.of(), "api", null).get("run_id"), "work goes on during the grace period");
        today(LocalDate.of(2027, 7, 20));
        assertEquals("expired", cr.licensing.state());
        assertEquals("CR.LICENSE_EXPIRED", code(() -> cr.startRun("admin", "admin", "L/B.flow.json", device, Map.of(), "api", null)));
        assertEquals("CR.LICENSE_EXPIRED", code(() -> cr.licensing.checkSignIn(dev)), "people other than administrators cannot sign in");
        cr.licensing.checkSignIn(admin);
        // queued runs are not handed out either
        Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", device);
        assertNull(cr.claimNext(d));
        assertTrue(db.query("SELECT status FROM runs WHERE status = 'queued'").isEmpty(), "queued runs failed with the license reason");
        // a new license brings everything back
        Map<String, Object> st = cr.licensing.install(admin, license(LocalDate.of(2028, 12, 31), 1, 1), null);
        assertEquals("active", st.get("state"));
        cr.licensing.checkSignIn(dev);
        today(LocalDate.now());
    }

    @Test @Order(5)
    void settingsAreCheckedAndApplied() {
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("session_hours", 0), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("max_upload_mb", 5000), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("public_url", "not a url"), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("sso_default_role", "nobody"), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("syslog_target", "no-port"), null)));
        // all or nothing: a bad value leaves the good one unsaved
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, new LinkedHashMap<>(Map.of("session_hours", 12, "max_upload_mb", 0)), null)));
        assertEquals(8, cr.platform.intSetting("session_hours"));
        cr.platform.updateSettings(admin, Map.of("session_hours", 12, "max_upload_mb", 20), null);
        assertEquals(Duration.ofHours(12), cr.platform.sessionLength());
        assertTrue(cr.platform.maxBodyBytes() > 20 * 1024 * 1024);
        // the allow list never locks out the person saving it
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("ip_allow_list", "10.0.0.0/8"), "192.168.1.20")));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("ip_allow_list", "10.0.0.0/99"), null)));
        cr.platform.updateSettings(admin, Map.of("ip_allow_list", "10.0.0.0/8, 192.168.1.20"), "192.168.1.20");
        cr.platform.updateSettings(admin, Map.of("ip_allow_list", ""), null);
    }

    @Test @Order(6)
    void ipAllowListPasswordsAndRateLimits() {
        IpAllowList l = IpAllowList.parse("10.1.0.0/16 203.0.113.7, 2001:db8::/32");
        assertTrue(l.allows("10.1.200.3"));
        assertFalse(l.allows("10.2.0.1"));
        assertTrue(l.allows("203.0.113.7"));
        assertTrue(l.allows("2001:db8:0:1::5"));
        assertFalse(l.allows("192.0.2.1"));
        assertTrue(IpAllowList.parse("").allows("192.0.2.1"));
        assertNull(Security.passwordProblem("Blue-river-2026", "dana"));
        assertNotNull(Security.passwordProblem("Password2026!!", "dana"), "a common password with digits added");
        assertNotNull(Security.passwordProblem("aaaa-River-2026", "dana"), "four in a row");
        assertNotNull(Security.passwordProblem("dana", "dana", 4), "the user name itself");
        assertNotNull(Security.passwordProblem("Blue-river-2026", "dana", 16), "the minimum length setting applies");
        long[] now = { 1_000_000 };
        RateLimiter rl = new RateLimiter(() -> now[0]);
        for (int i = 0; i < 60; i++) assertTrue(rl.allow("198.51.100.4", "auth"));
        assertFalse(rl.allow("198.51.100.4", "auth"), "the 61st sign-in in a minute is refused");
        assertTrue(rl.allow("198.51.100.5", "auth"), "other addresses are not affected");
        now[0] += 60_000;
        assertTrue(rl.allow("198.51.100.4", "auth"), "a new minute starts again");
        assertEquals("calls", RateLimiter.group("POST", "apitasks/task_1/run"));
        assertNull(RateLimiter.group("GET", "runs"));
    }

    @Test @Order(7)
    void auditEntriesReachTheSiemOverSyslog() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            s.setSoTimeout(5000);
            cr.platform.updateSettings(admin, Map.of("syslog_target", "127.0.0.1:" + s.getLocalPort(), "syslog_protocol", "udp"), null);
            cr.audit.record("dana", "repo.checkin", "bot", "L/\"quoted\"].flow.json", Map.of("version", 3), "ok", "10.0.0.9");
            String line = null;
            for (int i = 0; i < 5; i++) {   // the settings change above is forwarded first
                DatagramPacket p = new DatagramPacket(new byte[9000], 9000);
                s.receive(p);
                line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (line.contains("repo.checkin")) break;
            }
            assertTrue(line.startsWith("<109>1 "), line);
            assertTrue(line.contains("actor=\"dana\"") && line.contains("object_id=\"L/\\\"quoted\\\"\\].flow.json\"") && line.contains("ip=\"10.0.0.9\"") && line.contains("{\"version\":3}"), line);
            assertEquals(Boolean.TRUE, cr.syslog.test("admin").get("ok"));
            cr.platform.updateSettings(admin, Map.of("syslog_target", ""), null);
        }
    }

    @Test @Order(8)
    void readinessIntegrityAndRunnerVersions() {
        Map<String, Object> r = cr.readiness();
        assertEquals("ready", r.get("status"), String.valueOf(r));
        // an agent changed in the database after check-in is never sent anywhere
        db.update("UPDATE bot_versions SET content = REPLACE(content, '\"lb\"', '\"lx\"') WHERE path = 'L/B.flow.json'");
        assertEquals("CR.AGENT_TAMPERED", code(() -> cr.content("L/B.flow.json", "production")));
        // a runner newer than the Command Center gets no work
        assertTrue(ControlRoom.compareVersions("0.10.0", "0.9.2") > 0);
        assertEquals("ok", ControlRoom.versionStatus("0.2.0"));
        assertEquals("newer_than_command_center", ControlRoom.versionStatus("9.0.0"));
        db.update("UPDATE devices SET agent_version = '9.0.0' WHERE id = ?", device);
        assertNull(cr.claimNext(db.one("SELECT * FROM devices WHERE id = ?", device)));
        db.update("UPDATE devices SET agent_version = '0.2.0' WHERE id = ?", device);
        // a database upgraded by a newer release is refused
        db.update("INSERT INTO schema_version (version) VALUES (" + (Db.LATEST + 1) + ")");
        assertEquals("CR.DB_NEWER", code(() -> db.migrate()));
        db.update("DELETE FROM schema_version WHERE version = " + (Db.LATEST + 1));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }
}
