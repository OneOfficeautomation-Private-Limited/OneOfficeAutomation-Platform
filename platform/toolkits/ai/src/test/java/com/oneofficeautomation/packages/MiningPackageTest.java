package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G63: a process discovered from an order event log, and its cases checked against the expected flow. */
class MiningPackageTest {
    @TempDir Path w;

    static final String LOG = """
            case,activity,time
            O1,Create order,2026-11-02 09:00
            O1,Approve,2026-11-02 09:30
            O1,Ship,2026-11-02 11:30
            O2,Create order,2026-11-02 10:00
            O2,Approve,2026-11-02 11:00
            O2,Ship,2026-11-02 12:00
            O3,Create order,2026-11-02T10:15:00Z
            O3,Cancel,2026-11-02T10:45:00Z
            O4,Create order,2026-11-03 08:00
            O4,Ship,2026-11-03 09:00
            """;

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Object t) { return (List<List<Object>>) ((Map<String, Object>) t).get("rows"); }

    @Test @SuppressWarnings("unchecked")
    void discoversVariantsFlowsWaitsAndADiagram() throws Exception {
        Files.writeString(w.resolve("orders.csv"), LOG);
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> r = new MiningPackage().discover(ctx, "orders.csv", null, "case", "activity", "time", 20, 1);
        assertEquals(4, r.get("cases"));
        assertEquals(10L, r.get("events"));
        List<List<Object>> v = rows(r.get("variants"));
        assertEquals(List.of("Create order > Approve > Ship", 2, new BigDecimal("50.0"), new BigDecimal("135.0")), v.get(0), "the commonest path, half the cases, 2 h 15 min on average");
        assertEquals(3, v.size());
        List<List<Object>> f = rows(r.get("flows"));
        assertTrue(f.contains(List.of("Create order", "Approve", 2L, new BigDecimal("45.0"))), f.toString());
        assertEquals(Map.of("Create order", 4), r.get("start_activities"));
        assertEquals(Map.of("Ship", 3, "Cancel", 1), new HashMap<>((Map<String, Object>) r.get("end_activities")));
        assertEquals(new BigDecimal("150.0"), ((Map<?, ?>) r.get("duration")).get("max"));
        String d = (String) r.get("diagram");
        assertTrue(d.startsWith("flowchart LR") && d.contains("Create order (4)") && d.contains("-->|2 · 45.0 min|"), d);
        assertEquals("MINING.NO_COLUMN", assertThrows(ActionException.class, () -> new MiningPackage().discover(ctx, "orders.csv", null, "order", "activity", "time", 20, 1)).code());
    }

    @Test void checksCasesAgainstTheExpectedFlow() throws Exception {
        Files.writeString(w.resolve("orders.csv"), LOG);
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> c = new MiningPackage().conformance(ctx, "orders.csv", null,
                Map.of("Create order", List.of("Approve", "Cancel"), "Approve", List.of("Ship")), List.of("Create order"), List.of("Ship", "Cancel"), "case", "activity", "time");
        assertEquals(3, c.get("conforming"));
        assertEquals(1, c.get("deviating"));
        assertEquals(new BigDecimal("0.750"), c.get("fitness"));
        assertEquals(List.of(List.of("O4", "Create order followed by Ship")), rows(c.get("deviations")), "shipped without approval");
    }
}
