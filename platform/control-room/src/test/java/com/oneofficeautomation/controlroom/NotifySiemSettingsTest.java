package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settings added on 1 Nov 2026 (Settings item 4, batch 3): e-mail when a run fails or a task is given, the time zone of
 * new schedules, audit entries to an HTTPS collector, the only addresses agents may be promoted to, and the name of this
 * environment.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotifySiemSettingsTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-notify-siem");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { db.close(); }

    @Test void settingsAreChecked() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("default_time_zone", "Mars/Olympus"), Map.of("environment_colour", "pink"),
                Map.of("environment_label", "x".repeat(31)), Map.of("promotion_targets", "cc-prod.example.com"), Map.of("siem_https_url", "splunk.example.com"),
                Map.of("siem_https_token_ref", "plain-token"), Map.of("siem_https_auth", "basic"), Map.of("notify_run_failed", 2)))
            assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, bad, null)), String.valueOf(bad));
    }

    @Test void environmentTimeZoneAndPromotionTargets() throws Exception {
        assertNull(cr.me(admin).get("environment"), "no name: nothing shown");
        cr.platform.updateSettings(admin, Map.of("environment_label", "Production", "environment_colour", "red", "default_time_zone", "Asia/Kolkata"), null);
        assertEquals(Map.of("label", "Production", "colour", "red"), cr.me(admin).get("environment"));
        assertEquals("Asia/Kolkata", cr.me(admin).get("default_time_zone"));

        // a new schedule without a time zone takes the default; one with its own keeps it
        String key = (String) cr.createRegistrationKey(admin, 1, null).get("key");
        String device = (String) cr.registerDevice(key, "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
        cr.checkin(admin, "Ops/Report.flow.json", RepositoryLifecycleTest.flow("report"), "v1", true, null);
        String a = (String) cr.createSchedule(admin, new HashMap<>(Map.of("name", "a", "bot_path", "Ops/Report.flow.json", "device_id", device, "kind", "daily", "at_time", "02:00")), null).get("id");
        String b = (String) cr.createSchedule(admin, new HashMap<>(Map.of("name", "b", "bot_path", "Ops/Report.flow.json", "device_id", device, "kind", "daily", "at_time", "02:00",
                "time_zone", "Europe/London")), null).get("id");
        assertEquals("Asia/Kolkata", db.one("SELECT time_zone FROM schedules WHERE id = ?", a).get("time_zone"));
        assertEquals("Europe/London", db.one("SELECT time_zone FROM schedules WHERE id = ?", b).get("time_zone"));

        // promotion: only to the listed addresses (a trailing slash or capitals do not matter)
        cr.platform.updateSettings(admin, Map.of("promotion_targets", "https://CC-Test.example.com/, https://cc-prod.example.com"), null);
        Map<String, Object> req = new HashMap<>(Map.of("paths", List.of("Ops/Report.flow.json"), "target_url", "https://evil.example.com", "api_key_ref", "envs/prod/api_key"));
        assertEquals("CR.TARGET_NOT_ALLOWED", code(() -> cr.lifecycle.promote(admin, req, null)));
        req.put("target_url", "https://cc-test.example.com");
        assertNotEquals("CR.TARGET_NOT_ALLOWED", code(() -> cr.lifecycle.promote(admin, req, null)), "a listed address passes this check");
        cr.platform.updateSettings(admin, Map.of("promotion_targets", "", "environment_label", "", "default_time_zone", ""), null);
    }

    @Test void auditEntriesGoToAnHttpsCollector() throws Exception {
        List<String[]> got = new CopyOnWriteArrayList<>();   // { Authorization, body }
        HttpServer siem = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        siem.createContext("/collector", ex -> {
            got.add(new String[] { String.valueOf(ex.getRequestHeaders().getFirst("Authorization")), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) });
            ex.sendResponseHeaders(200, -1); ex.close();
        });
        siem.start();
        try {
            cr.createLocker(admin, "siem", "SIEM", "user:admin", null);
            cr.setCredential(admin, "siem", "collector", "token", "hec-123", null);
            cr.platform.updateSettings(admin, Map.of("siem_https_url", "http://127.0.0.1:" + siem.getAddress().getPort() + "/collector",
                    "siem_https_token_ref", "siem/collector/token", "siem_https_auth", "splunk"), null);
            cr.syslog.testHttps("admin");
            String[] test = got.stream().filter(g -> g[1].contains("siem.test")).findFirst().orElseThrow();
            assertEquals("Splunk hec-123", test[0]);
            assertEquals("ooa:audit", ((Map<?, ?>) JsonLite.parse(test[1])).get("sourcetype"), "Splunk's collector gets the entry inside event");
            // an ordinary audit entry follows, as a bearer token once that is chosen
            cr.platform.updateSettings(admin, Map.of("siem_https_auth", "bearer"), null);
            cr.audit.record("admin", "test.entry", "settings", "x", "for the collector", "ok", null);
            long until = System.currentTimeMillis() + 10_000;
            while (got.stream().noneMatch(g -> g[1].contains("test.entry")) && System.currentTimeMillis() < until) Thread.sleep(50);
            String[] entry = got.stream().filter(g -> g[1].contains("test.entry")).findFirst().orElseThrow();
            assertEquals("Bearer hec-123", entry[0]);
            assertEquals("test.entry", ((Map<?, ?>) JsonLite.parse(entry[1])).get("action"));
            // a collector that refuses: the test says so
            siem.removeContext("/collector");
            assertEquals("CR.SIEM_FAILED", code(() -> cr.syslog.testHttps("admin")));
        } finally {
            cr.platform.updateSettings(admin, Map.of("siem_https_url", "", "siem_https_token_ref", ""), null);
            siem.stop(0);
        }
    }

    @Test void peopleAreEmailedAboutTheirOwnWork() throws Exception {
        try (LicenseMailAndUsageTest.FakeSmtp smtp = new LicenseMailAndUsageTest.FakeSmtp()) {
            assertEquals(List.of(), cr.mailPeople(List.of("admin"), "s", "b", "run", "r"), "e-mail not set up: nothing is sent");
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "127.0.0.1", "mail_smtp_port", smtp.port(), "mail_smtp_security", "none", "mail_from", "ooa@example.com"), null);
            cr.createUser(admin, "fay", "Fay Finance", "bot_creator", null);
            cr.createUser(admin, "gus", "Gus", "bot_creator", null);
            cr.createUser(admin, "hal", "Hal", "viewer", null);
            db.update("UPDATE users SET email = ? WHERE username = ?", "fay@example.com", "fay");
            db.update("UPDATE users SET email = ? WHERE username = ?", "gus@example.com", "gus");
            db.update("UPDATE users SET email = ?, enabled = FALSE WHERE username = ?", "hal@example.com", "hal");

            // a failed run: the person who started it
            Map<String, Object> run = Map.of("id", "run_1", "bot_path", "Finance/Pay.flow.json", "requested_by", "fay");
            assertEquals(List.of("fay@example.com"), cr.runFailedMail(run, Map.of("code", "X", "message", "The portal did not answer")));
            long until = System.currentTimeMillis() + 10_000;
            while (smtp.messages.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(50);
            assertEquals("Run failed: Finance/Pay", LicenseMailAndUsageTest.subject(smtp.messages.get(0)));
            assertTrue(LicenseMailAndUsageTest.body(smtp.messages.get(0)).contains("The portal did not answer"));

            // a task for a role and a person: each enabled holder with an address, once; never everyone
            List<String> to = cr.mailPeople(List.of("role:bot_creator", "user:fay", "user:hal"), "A task for you: x", "b", "task", "t1");
            assertEquals(Set.of("gus@example.com", "fay@example.com"), new HashSet<>(to));
            assertEquals(2, to.size());
            assertEquals(List.of(), cr.mailPeople(List.of("role:*"), "A task for you: x", "b", "task", "t2"));
        } finally {
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "", "mail_from", ""), null);
        }
    }
}
