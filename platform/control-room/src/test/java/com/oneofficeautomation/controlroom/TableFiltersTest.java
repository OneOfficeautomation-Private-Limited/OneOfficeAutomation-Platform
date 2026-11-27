package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tables and filters, item 2 (27 Nov 2026): filters that take several values as a comma list (runs: status, agent,
 * runner, trigger; audit: who, action, result), and AI governance over a chosen period (from / to) as well as days.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableFiltersTest {
    Db db; ControlRoom cr; ControlRoom.User admin;
    final Instant now = Instant.now();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-table-filters");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        run("t1", "Sales/Quote.flow.json", "failed", "schedule:s1", "dev_a", now.minus(Duration.ofHours(1)));
        run("t2", "Sales/Quote.flow.json", "stopped", "manual", "dev_b", now.minus(Duration.ofHours(2)));
        run("t3", "Finance/Pay.flow.json", "success", "trigger:t9", "dev_a", now.minus(Duration.ofHours(3)));
        run("t4", "Finance/Pay.flow.json", "failed", "api", "dev_c", now.minus(Duration.ofDays(10)));
    }

    @AfterAll void stop() { db.close(); }

    void run(String id, String bot, String status, String source, String device, Instant created) {
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, bot, 1, device, status, "admin", source, created, false, false);
    }

    @SuppressWarnings("unchecked")
    Set<String> ids(Map<String, String> q) {
        Map<String, String> all = new HashMap<>(q);
        all.put("filter", "history");
        all.put("limit", "500");
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) cr.runsPage(admin, all).get("runs")) if (String.valueOf(r.get("id")).startsWith("t")) out.add((String) r.get("id"));
        return out;
    }

    @Test void runsTakeSeveralValues() {
        assertEquals(Set.of("t1", "t4"), ids(Map.of("status", "failed")), "one value, as before");
        assertEquals(Set.of("t1", "t2", "t4"), ids(Map.of("status", "failed,stopped")));
        assertEquals(Set.of("t1", "t2", "t4"), ids(Map.of("status", "failed, stopped, not-a-status")), "an unknown status is left out");
        assertEquals(Set.of("t1", "t2", "t3", "t4"), ids(Map.of("bot", "Sales/Quote.flow.json,Finance/Pay.flow.json")));
        assertEquals(Set.of("t1", "t3"), ids(Map.of("device", "dev_a")));
        assertEquals(Set.of("t1", "t2", "t4"), ids(Map.of("device", "dev_b,dev_c,dev_a", "status", "failed,stopped")));
        assertEquals(Set.of("t1", "t3"), ids(Map.of("source", "schedule,trigger")), "a trigger's own id after the colon still matches");
        assertEquals(Set.of("t2", "t4"), ids(Map.of("source", "manual,api")));
        assertEquals(Set.of("t1", "t2"), ids(Map.of("status", "failed,stopped", "from", now.minus(Duration.ofDays(1)).toString(), "to", now.toString())), "with a period");
    }

    @Test @SuppressWarnings("unchecked") void auditTakesSeveralValues() {
        cr.audit.record("anna", "repo.checkin", "bot", "a", null, "ok", null);
        cr.audit.record("ben", "user.create", "user", "c", null, "denied", null);
        cr.audit.record("carl", "auth.login", "user", "carl", null, "failed", null);
        java.util.function.Function<Map<String, String>, Set<String>> who = f -> {
            Set<String> out = new TreeSet<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) cr.auditSearch(admin, f).get("records"))
                if (Set.of("anna", "ben", "carl").contains(String.valueOf(r.get("actor")))) out.add((String) r.get("actor"));
            return out;
        };
        assertEquals(Set.of("anna", "carl"), who.apply(Map.of("actor", "Anna,carl")), "who: several, any case");
        assertEquals(Set.of("ben", "carl"), who.apply(Map.of("result", "denied,failed")));
        assertEquals(Set.of("anna", "carl"), who.apply(Map.of("action", "repo.,auth.")), "areas are prefixes");
        assertEquals(Set.of("ben"), who.apply(Map.of("action", "user.create")), "an exact action, as before");
    }

    @Test @SuppressWarnings("unchecked") void governanceOverChosenDates() {
        db.update("INSERT INTO ai_calls (id, ts, purpose, bot_path) VALUES (?,?,?,?)", "g1", now.minus(Duration.ofDays(2)), "test", "A");
        db.update("INSERT INTO ai_calls (id, ts, purpose, bot_path) VALUES (?,?,?,?)", "g2", now.minus(Duration.ofDays(40)), "test", "B");
        db.update("INSERT INTO ai_calls (id, ts, purpose, bot_path) VALUES (?,?,?,?)", "g3", now.minus(Duration.ofDays(45)), "test", "B");
        java.util.function.Function<Map<String, String>, Long> calls = q -> ((Number) ((Map<String, Object>) cr.platform.aiGovernance(admin, q).get("totals")).get("calls")).longValue();
        assertEquals(1L, calls.apply(Map.of("days", "30")), "days still works");
        assertEquals(1L, cr.platform.aiGovernance(admin, 30).get("totals") instanceof Map<?, ?> t ? ((Number) t.get("calls")).longValue() : -1, "and the old call");
        assertEquals(2L, calls.apply(Map.of("from", now.minus(Duration.ofDays(50)).toString(), "to", now.minus(Duration.ofDays(30)).toString())));
        assertEquals(3L, calls.apply(Map.of("from", now.minus(Duration.ofDays(60)).toString())), "from alone: up to now");
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.aiGovernance(admin, Map.of("from", now.toString(), "to", now.minus(Duration.ofDays(1)).toString()))).code());
    }

    @Test void listParam() {
        assertEquals(List.of(), ControlRoom.listParam(null));
        assertEquals(List.of(), ControlRoom.listParam(" , "));
        assertEquals(List.of("a", "b"), ControlRoom.listParam("a, b,a,"));
        List<Object> p = new ArrayList<>();
        assertEquals("x = ?", ControlRoom.sqlIn("x", List.of("1"), p));
        assertEquals("x IN (?, ?)", ControlRoom.sqlIn("x", List.of("1", "2"), p));
        assertEquals(List.of("1", "1", "2"), p);
    }
}
