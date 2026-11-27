package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.insights.Analytics;
import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tables and filters, item 2 (27 Nov 2026): Operations and the audit figures take several agents, runners, triggers,
 * people and results as comma lists, and a chosen period as from / to.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsChoiceFiltersTest {
    Db db; ControlRoom cr; ControlRoom.User admin;
    final Instant now = Instant.now();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-insights-choices");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        run("c1", "A/One.flow.json", "schedule:s1", "dev_a", now.minus(Duration.ofHours(1)));
        run("c2", "A/Two.flow.json", "manual", "dev_b", now.minus(Duration.ofHours(2)));
        run("c3", "A/Three.flow.json", "trigger:t1", "dev_c", now.minus(Duration.ofHours(3)));
        run("c4", "A/One.flow.json", "api", "dev_a", now.minus(Duration.ofDays(20)));
    }

    @AfterAll void stop() { db.close(); }

    void run(String id, String bot, String source, String device, Instant created) {
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, finished, duration_ms, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            id, bot, 1, device, "success", "admin", source, created, created.plusSeconds(5), 5000L, false, false);
    }

    @SuppressWarnings("unchecked")
    long runs(Map<String, String> q) {
        Map<String, String> all = new HashMap<>(q);
        all.putIfAbsent("days", "30");
        return ((Number) ((Map<String, Object>) cr.modules.service(Analytics.class).overview(admin, all).get("totals")).get("runs")).longValue();
    }

    @Test void operationsTakeSeveralValues() {
        assertEquals(4, runs(Map.of()));
        assertEquals(3, runs(Map.of("bot", "A/One.flow.json,A/Two.flow.json")));
        assertEquals(2, runs(Map.of("device", "dev_b,dev_c")));
        assertEquals(2, runs(Map.of("source", "schedule,trigger")));
        assertEquals(1, runs(Map.of("source", "manual")), "one value, as before");
        assertEquals(3, runs(Map.of("from", now.minus(Duration.ofDays(1)).toString(), "to", now.toString())), "a chosen period");
        assertEquals(1, runs(Map.of("bot", "A/One.flow.json", "from", now.minus(Duration.ofDays(1)).toString())));
    }

    @Test void auditFiguresTakeSeveralValues() {
        cr.audit.record("dora", "repo.checkin", "bot", "a", null, "ok", null);
        cr.audit.record("eli", "auth.login", "user", "eli", null, "failed", null);
        cr.audit.record("finn", "user.create", "user", "x", null, "denied", null);
        Analytics an = cr.modules.service(Analytics.class);
        assertEquals(2L, ((Number) an.audit(admin, Map.of("days", "30", "actor", "dora,eli")).get("entries")).longValue());
        assertEquals(2L, ((Number) an.audit(admin, Map.of("days", "30", "actor", "dora,eli,finn", "result", "failed,denied")).get("entries")).longValue());
        assertEquals(2L, ((Number) an.audit(admin, Map.of("days", "30", "actor", "dora,eli,finn", "kind", "repo,auth")).get("entries")).longValue());
    }
}
