package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Operational Insights step 1 (1 Nov 2026): the Audit tab's figures (AA's Audit dashboard) and the Runners tab's
 * figures per runner (AA's Devices dashboard).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsFiguresTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-insights");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { db.close(); }

    @Test @SuppressWarnings("unchecked") void auditFigures() {
        cr.audit.record("admin", "auth.login", "user", "admin", null, "failed", null);
        cr.audit.record("admin", "auth.login", "user", "admin", null, "ok", null);
        cr.audit.record("agent:PC-1", "credential.read", "credential", "f/c/a", Map.of("run", "r1"), "denied", null);
        cr.audit.record("system", "run.lost", "run", "r2", null, "failed", null);
        Map<String, Object> a = cr.analytics.audit(admin, Map.of("days", "30", "tz", "UTC"));
        assertTrue(((Number) a.get("entries")).longValue() >= 4);
        assertEquals(1, a.get("people"), "the system and runners are not people");
        assertTrue(((Number) a.get("sign_ins")).longValue() >= 2);
        assertTrue(((Number) a.get("failed_sign_ins")).longValue() >= 1);
        List<Map<String, Object>> who = (List<Map<String, Object>>) a.get("by_actor");
        assertEquals("runner", who.stream().filter(w -> w.get("actor").equals("agent:PC-1")).findFirst().orElseThrow().get("is"));
        assertEquals("person", who.stream().filter(w -> w.get("actor").equals("admin")).findFirst().orElseThrow().get("is"));
        assertTrue(((List<Map<String, Object>>) a.get("by_kind")).stream().anyMatch(k -> k.get("kind").equals("auth")));
        List<Map<String, Object>> notDone = (List<Map<String, Object>>) a.get("not_done");
        assertEquals(List.of("failed", "denied", "failed"), notDone.stream().map(r -> r.get("result")).toList(), "the latest first");

        // filters: one kind, one person, one result
        Map<String, Object> signIns = cr.analytics.audit(admin, Map.of("days", "30", "kind", "auth", "result", "failed"));
        assertEquals(1L, ((Number) signIns.get("entries")).longValue());
        assertEquals(0L, ((Number) cr.analytics.audit(admin, Map.of("days", "30", "kind", "a%")).get("entries")).longValue(), "a wildcard is not one");

        // the Audit Trail's permission is needed
        ControlRoom.User viewer = RepositoryLifecycleTest.person(cr, admin, "vic", "viewer");
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.analytics.audit(viewer, Map.of())).code());
    }

    @Test @SuppressWarnings("unchecked") void eachRunnersRunsStateAndErrors() {
        String key = (String) cr.createRegistrationKey(admin, 1, null).get("key");
        String device = (String) cr.registerDevice(key, "PC-2", "pc-2", "Windows 11", "0.2.0", null).get("device_id");
        Instant t = Instant.now().minus(Duration.ofHours(2));
        run(device, "success", null, t);
        run(device, "failed", "{\"code\":\"AGENT.LOST\",\"message\":\"x\"}", t.plusSeconds(60));
        run(device, "failed", "{\"code\":\"AGENT.LOST\",\"message\":\"x\"}", t.plusSeconds(120));
        run(device, "stopped", null, t.plusSeconds(180));
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now(), device);
        Map<String, Object> c = cr.analytics.capacity(admin, Map.of("days", "1", "tz", "UTC"));
        Map<String, Object> r = ((List<Map<String, Object>>) c.get("runners")).stream().filter(x -> x.get("device_id").equals(device)).findFirst().orElseThrow();
        assertEquals("PC-2", r.get("name"));
        assertEquals("online", r.get("status"));
        assertEquals(List.of(1L, 2L, 1L), List.of(r.get("success"), r.get("failed"), r.get("stopped")));
        assertEquals("AGENT.LOST", r.get("top_error"));
        assertNotNull(r.get("last_run"));
        assertTrue(((Number) c.get("online")).intValue() >= 1);
        assertEquals(List.of(Map.of("code", "AGENT.LOST", "runs", 2L)), c.get("top_errors"));
        assertEquals(0, ((Number) c.get("schedules")).intValue());
    }

    void run(String device, String status, String error, Instant created) {
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, started, finished, error, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ControlRoom.id("run"), "Ops/Report.flow.json", 1, device, status, "admin", "manual", created, created.plusSeconds(1), created.plusSeconds(30), error, false, false);
    }
}
