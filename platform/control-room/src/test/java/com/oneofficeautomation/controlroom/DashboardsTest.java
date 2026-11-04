package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** K5: KPI outputs recorded by runs, and dashboards of widgets over them and over the runs, shared or private. */
class DashboardsTest {

    @Test @SuppressWarnings("unchecked")
    void kpisAreRecordedAndDashboardsComputeAndShare() throws Exception {
        Path data = Files.createTempDirectory("ooa-dash");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.checkin(admin, "Finance/Pay.flow.json", (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"pay\",\"name\":\"Pay\",\"description\":\"t\","
                + "\"variables\":[{\"name\":\"amount\",\"type\":\"Number\",\"direction\":\"output\",\"kpi\":true},{\"name\":\"category\",\"type\":\"String\",\"direction\":\"output\",\"kpi\":true},"
                + "{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"}],\"steps\":[]}"), "v1", true, null);
            String device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "PC-D", "pc-d", "Windows 11", "0.2.0", null).get("device_id");
            Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", device);
            Object[][] runs = { { 100, "travel", "success" }, { 250.5, "travel", "success" }, { 40, "office", "success" }, { 999, "office", "failed" } };
            for (Object[] r : runs) {
                String id = (String) cr.startRun("admin", "admin", "Finance/Pay.flow.json", device, Map.of(), "manual", null).get("run_id");
                db.update("UPDATE runs SET status = 'running', started = CURRENT_TIMESTAMP WHERE id = ?", id);
                cr.finish(d, id, Map.of("status", r[2], "outputs", Map.of("amount", r[0], "category", r[1], "note", "x")));
            }
            assertEquals(6, cr.count("SELECT COUNT(*) AS n FROM kpi_values"), "two KPIs of the three successful runs; note is not a KPI");
            assertEquals(2, cr.dashboards.kpis(admin).size());

            assertThrows(OoaException.class, () -> cr.dashboards.save(admin, null, Map.of("name", "x", "widgets", List.of(Map.of("type", "radar", "kpi", "amount"))), null));
            // a pie shares out the same figures as bars
            String pie = (String) cr.dashboards.save(admin, null, Map.of("name", "Pie", "widgets", List.of(Map.of("type", "pie", "kpi", "amount", "group", "value", "by", "category"))), null).get("id");
            assertTrue(((List<Map<String, Object>>) cr.dashboards.data(admin, pie, "UTC").get("widgets")).get(0).containsKey("points"));
            cr.dashboards.delete(admin, pie, null);
            String dash = (String) cr.dashboards.save(admin, null, Map.of("name", "Finance", "widgets", List.of(
                Map.of("title", "Paid", "type", "number", "kpi", "amount", "agg", "sum"),
                Map.of("title", "By category", "type", "bars", "kpi", "amount", "agg", "sum", "group", "value", "by", "category"),
                Map.of("title", "Per day", "type", "line", "kpi", "amount", "agg", "count", "group", "day"),
                Map.of("title", "Success", "type", "number", "source", "runs", "metric", "success_rate"))), null).get("id");
            List<Map<String, Object>> w = (List<Map<String, Object>>) cr.dashboards.data(admin, dash, "UTC").get("widgets");
            assertEquals(0, new BigDecimal("390.5").compareTo((BigDecimal) w.get(0).get("value")));
            List<Map<String, Object>> cats = (List<Map<String, Object>>) w.get(1).get("points");
            assertEquals(2, cats.size());
            // grouped by the category value, the largest first
            assertEquals("travel", cats.get(0).get("label"));
            assertEquals(0, new BigDecimal("350.5").compareTo((BigDecimal) cats.get(0).get("value")));
            List<Map<String, Object>> perDay = (List<Map<String, Object>>) w.get(2).get("points");
            assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), perDay.get(0).get("label"));
            assertEquals(0, BigDecimal.valueOf(3).compareTo((BigDecimal) perDay.get(0).get("value")));
            assertEquals(0, new BigDecimal("75.0").compareTo((BigDecimal) w.get(3).get("value")), "3 of 4 runs succeeded");

            // private until shared
            cr.createUser(admin, "vic", "Vic", "viewer", null);
            ControlRoom.User vic = ControlRoom.user(db.one("SELECT * FROM users WHERE username = 'vic'"));
            assertTrue(cr.dashboards.list(vic).isEmpty());
            assertThrows(OoaException.class, () -> cr.dashboards.data(vic, dash, null));
            cr.dashboards.save(admin, dash, Map.of("name", "Finance", "shared_with", List.of("role:viewer"), "widgets", List.of(Map.of("type", "number", "kpi", "amount"))), null);
            assertEquals(1, cr.dashboards.list(vic).size());
            assertNotNull(cr.dashboards.data(vic, dash, null));
            assertThrows(OoaException.class, () -> cr.dashboards.save(vic, dash, Map.of("name", "Mine", "widgets", List.of(Map.of("type", "number", "kpi", "amount"))), null), "only the owner changes it");
        } finally { db.close(); }
    }
}
