package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runner and run settings added on 1 Nov 2026 (Settings item 4, batch 2): closing registrations, HTTPS only for runners
 * (this computer excepted), refusing runs for an offline runner, pausing a trigger after failed runs in a row, and
 * marking work items abandoned.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunnerSettingsTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-runner-settings");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { db.close(); }

    @Test void runnerAndRunRules() throws Exception {
        // registrations closed: a key stops working until they are open again
        String key = (String) cr.createRegistrationKey(admin, 1, null).get("key");
        cr.platform.updateSettings(admin, Map.of("runner_registration_open", 0), null);
        assertEquals("CR.REGISTRATION_CLOSED", code(() -> cr.registerDevice(key, "PC-1", "pc-1", "Windows 11", "0.2.0", null)));
        cr.platform.updateSettings(admin, Map.of("runner_registration_open", 1), null);
        String device = (String) cr.registerDevice(key, "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");

        // a run for an offline runner: refused when the setting says so, otherwise it waits
        cr.checkin(admin, "Ops/Report.flow.json", RepositoryLifecycleTest.flow("report"), "v1", true, null);
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now().minus(Duration.ofHours(1)), device);
        cr.platform.updateSettings(admin, Map.of("runs_refuse_offline_runner", 1), null);
        assertEquals("CR.RUNNER_OFFLINE", code(() -> cr.startRun("admin", "admin", "Ops/Report.flow.json", device, Map.of(), "manual", null)));
        cr.platform.updateSettings(admin, Map.of("runs_refuse_offline_runner", 0), null);
        assertNotNull(cr.startRun("admin", "admin", "Ops/Report.flow.json", device, Map.of(), "manual", null).get("run_id"), "otherwise the run waits for the runner");

        // a trigger whose last 3 runs all failed is paused with the reason; one with a success among them is not
        cr.platform.updateSettings(admin, Map.of("trigger_max_failures", 3), null);
        for (String t : List.of("t1", "t2"))
            db.update("INSERT INTO triggers (id, name, bot_path, device_id, kind, enabled, created_by) VALUES (?,?,?,?,?,?,?)", t, t, "Ops/Report.flow.json", device, "file", true, "admin");
        Instant at = Instant.now().minus(Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            run("trigger:t1", "failed", at.plusSeconds(i));
            run("trigger:t2", i == 1 ? "success" : "failed", at.plusSeconds(i));
        }
        cr.switchOffFailingTrigger("t1");
        cr.switchOffFailingTrigger("t2");
        Map<String, Object> t1 = db.one("SELECT enabled, last_error FROM triggers WHERE id = 't1'");
        assertEquals(Boolean.FALSE, t1.get("enabled"));
        assertTrue(String.valueOf(t1.get("last_error")).contains("3 failed runs in a row"));
        assertEquals(Boolean.TRUE, db.one("SELECT enabled FROM triggers WHERE id = 't2'").get("enabled"));

        // a work item in progress longer than the limit is marked failed as abandoned; a recent one is left
        db.update("INSERT INTO queues (id, name, max_attempts, processing, created) VALUES (?,?,?,?,?)", "q1", "Invoices", 3, false, Instant.now());
        for (String[] it : new String[][] { { "i1", "10" }, { "i2", "1" } })
            db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated) VALUES (?,?,?,?,?,?,?,?)",
                    it[0], "q1", "{}", "in_progress", 0, 1, Instant.now().minus(Duration.ofHours(12)), Instant.now().minus(Duration.ofHours(Long.parseLong(it[1]))));
        cr.platform.updateSettings(admin, Map.of("queue_abandon_hours", 5), null);
        cr.abandonStaleItems();
        Map<String, Object> i1 = db.one("SELECT status, error FROM queue_items WHERE id = 'i1'");
        assertEquals("failed", i1.get("status"));
        assertTrue(String.valueOf(i1.get("error")).contains("QUEUE.ABANDONED"));
        assertEquals("in_progress", db.one("SELECT status FROM queue_items WHERE id = 'i2'").get("status"));

        // HTTPS only for runners: a runner on this computer still may connect over HTTP
        cr.platform.updateSettings(admin, Map.of("runner_require_https", 1), null);
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            HttpResponse<String> local = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/agent/heartbeat"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(local.body().contains("CR.HTTPS_REQUIRED"), local.body());
        } finally { server.stop(); }
    }

    void run(String source, String status, Instant created) {
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?)",
                ControlRoom.id("run"), "Ops/Report.flow.json", 1, "dev_x", status, "admin", source, created, false, false);
    }
}
