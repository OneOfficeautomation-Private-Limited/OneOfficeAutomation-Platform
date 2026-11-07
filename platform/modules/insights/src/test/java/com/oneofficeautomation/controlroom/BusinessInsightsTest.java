package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.insights.Analytics;
import com.oneofficeautomation.modules.insights.Dashboards;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Operational Insights step 2, Business (1 Nov 2026): agents that record values, their data profile, widgets over an
 * agent's output, savings per agent and per work queue (counted once), estimated against actual, the yearly goal, and
 * the two Insights permissions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessInsightsTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-business");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Sales/Tickets.flow.json", RepositoryLifecycleTest.flow("tickets"), "v1", true, null);
        cr.checkin(admin, "Sales/Invoices.flow.json", RepositoryLifecycleTest.flow("invoices"), "v1", true, null);
        Instant t = Instant.now().minus(Duration.ofHours(3));
        run("r1", "Sales/Tickets.flow.json", "success", "{\"billing\":2,\"other\":1,\"team\":\"north\"}", t, null);
        run("r2", "Sales/Tickets.flow.json", "failed", "{\"billing\":0,\"other\":0,\"team\":\"north\"}", t.plusSeconds(60), null);
        run("r3", "Sales/Tickets.flow.json", "success", null, t.plusSeconds(120), null);
        // a queue item done by Invoices: counted by the queue's minutes once they are set
        db.update("INSERT INTO queues (id, name, max_attempts, processing, created) VALUES (?,?,?,?,?)", "q1", "Invoices in", 3, false, Instant.now());
        db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated) VALUES (?,?,?,?,?,?,?,?)", "i1", "q1", "{}", "success", 0, 1, t, t);
        run("r4", "Sales/Invoices.flow.json", "success", null, t.plusSeconds(180), "i1");
        run("r5", "Sales/Invoices.flow.json", "success", null, t.plusSeconds(240), null);
    }

    @AfterAll void stop() { db.close(); }

    void run(String id, String bot, String status, String outputs, Instant created, String item) {
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, started, finished, duration_ms, outputs, queue_item_id, cancel_requested, pause_requested) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, bot, 1, "dev_x", status, "admin", "manual", created, created, created.plusSeconds(10), 10_000, outputs, item, false, false);
    }

    @Test @SuppressWarnings("unchecked") void agentsTheirValuesAndProfile() {
        List<Map<String, Object>> agents = (List<Map<String, Object>>) cr.modules.service(Analytics.class).businessAgents(admin, Map.of("days", "1")).get("agents");
        Map<String, Object> tickets = agents.get(0);
        assertEquals("Sales/Tickets.flow.json", tickets.get("bot_path"));
        assertEquals(List.of("billing", "other", "team"), tickets.get("values"));
        assertEquals(3L, tickets.get("runs"));
        assertEquals(2L, tickets.get("runs_with_values"));
        assertTrue(agents.stream().noneMatch(a -> a.get("bot_path").equals("Sales/Invoices.flow.json")), "no outputs: not listed");

        Map<String, Object> prof = cr.modules.service(Analytics.class).variables(admin, "Sales/Tickets.flow.json", Map.of("days", "1", "tz", "UTC"));
        Map<String, Object> billing = ((List<Map<String, Object>>) prof.get("variables")).stream().filter(v -> v.get("variable").equals("billing")).findFirst().orElseThrow();
        assertEquals("number", billing.get("kind"));
        assertEquals(2.0, billing.get("sum"));
        assertEquals(2, billing.get("distinct"));
        assertEquals(1, ((List<?>) billing.get("by_day")).size());
        Map<String, Object> team = ((List<Map<String, Object>>) prof.get("variables")).stream().filter(v -> v.get("variable").equals("team")).findFirst().orElseThrow();
        assertEquals("text", team.get("kind"));
        assertNull(team.get("sum"));
        assertEquals(2, ((List<?>) prof.get("recent")).size(), "the latest runs' values");
    }

    @Test @SuppressWarnings("unchecked") void widgetsOverAnAgentsOutputAndWhoMayMakeThem() {
        String id = (String) cr.modules.service(Dashboards.class).save(admin, null, Map.of("name", "Tickets", "widgets", List.of(
                Map.of("title", "billing", "type", "number", "source", "output", "bot", "Sales/Tickets.flow.json", "kpi", "billing", "agg", "sum", "days", 1),
                Map.of("title", "by team", "type", "table", "source", "output", "bot", "Sales/Tickets.flow.json", "kpi", "team", "agg", "count", "group", "value", "days", 1))), null).get("id");
        List<Map<String, Object>> w = (List<Map<String, Object>>) cr.modules.service(Dashboards.class).data(admin, id, "UTC").get("widgets");
        assertEquals(0, new java.math.BigDecimal("2").compareTo((java.math.BigDecimal) w.get(0).get("value")));
        assertEquals("north", ((List<Map<String, Object>>) w.get(1).get("points")).get(0).get("label"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.modules.service(Dashboards.class).save(admin, null, Map.of("name", "x", "widgets", List.of(Map.of("source", "output", "kpi", "billing"))), null)), "an output needs its agent");
        // making dashboards needs insights.design; viewers still see shared ones
        ControlRoom.User viewer = RepositoryLifecycleTest.person(cr, admin, "val", "viewer");
        assertEquals("CR.FORBIDDEN", code(() -> cr.modules.service(Dashboards.class).save(viewer, null, Map.of("name", "Mine", "widgets", List.of(Map.of("source", "runs"))), null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.setSavingsGoal(viewer, Map.of("hours", 10), null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.setQueueSavings(viewer, "q1", Map.of("minutes_saved", 5), null)));
        assertTrue(Rbac.all().containsAll(List.of("insights.design", "insights.admin")), "offered to custom roles");
    }

    @Test @SuppressWarnings("unchecked") void savingsPerAgentAndQueueEstimatesAndTheGoal() {
        cr.platform.updateSettings(admin, Map.of("default_minutes_saved", 10, "hourly_cost", 600), null);
        cr.platform.setAgentSavings(admin, new HashMap<>(Map.of("path", "Sales/Invoices.flow.json", "minutes_saved", 30, "hourly_cost", 1200, "estimated_runs_month", 100)), null);
        Map<String, Object> v = cr.modules.service(Analytics.class).value(admin, Map.of("days", "1"));
        Map<String, Object> inv = ((List<Map<String, Object>>) v.get("agents")).stream().filter(a -> a.get("bot_path").equals("Sales/Invoices.flow.json")).findFirst().orElseThrow();
        assertEquals(60L, inv.get("minutes"), "two runs at its own 30 minutes");
        assertEquals(1200L, inv.get("money"), "an hour at its own cost");
        assertEquals(50.0, inv.get("estimated_hours_month"), "100 runs x 30 minutes");
        assertEquals(true, inv.get("own_cost"));

        // the queue's own minutes count for its item instead of the agent's; the run counts once
        cr.platform.setQueueSavings(admin, "q1", new HashMap<>(Map.of("minutes_saved", 6)), null);
        v = cr.modules.service(Analytics.class).value(admin, Map.of("days", "1"));
        inv = ((List<Map<String, Object>>) v.get("agents")).stream().filter(a -> a.get("bot_path").equals("Sales/Invoices.flow.json")).findFirst().orElseThrow();
        assertEquals(36L, inv.get("minutes"), "6 for the queue item and 30 for the other run");
        Map<String, Object> q = ((List<Map<String, Object>>) v.get("queues")).get(0);
        assertEquals("Invoices in", q.get("name"));
        assertEquals(6L, q.get("minutes"));
        assertEquals(60L, q.get("money"), "the default cost: 6 minutes of 600 an hour");
        assertEquals(0.9, v.get("hours"), "56 minutes: two Tickets runs at the default 10, and 36");

        // the goal, set with insights.admin, with this year's figures
        cr.platform.setSavingsGoal(admin, Map.of("hours", 100, "money", 50000), null);
        Map<String, Object> goal = (Map<String, Object>) cr.modules.service(Analytics.class).value(admin, Map.of("days", "1")).get("goal");
        assertEquals(100, goal.get("hours"));
        assertEquals(50000L, goal.get("money"));
        assertTrue(((Number) goal.get("year_hours")).doubleValue() > 0);
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.setSavingsGoal(admin, Map.of("hours", -1), null)));
    }
}
