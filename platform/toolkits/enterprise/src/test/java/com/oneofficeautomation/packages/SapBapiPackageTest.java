package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import com.sap.conn.jco.FakeSap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** SAP BAPI toolkit (gap: SAP BAPI package) against the JCo stand-in and its small in-memory SAP system. */
class SapBapiPackageTest {
    final Map<String, Object> sessions = new HashMap<>();
    final List<String> log = new ArrayList<>();

    ActionContext ctx() throws Exception {
        return new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-bapi"), sessions, new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> log.add(l + ": " + m));
    }

    static SecureString pw(String p) { return new SecureString(p, "sap/erp/password"); }

    static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @SuppressWarnings("unchecked")
    @Test void connectCallCommitReadAndDisconnect() throws Exception {
        SapBapiPackage sap = new SapBapiPackage();
        ActionContext c = ctx();
        FakeSap.PENDING.clear(); FakeSap.COMMITTED.clear();

        // logon problems are typed
        assertEquals("SAP_BAPI.LOGON_FAILED", code(() -> sap.connect(c, "erp.example.com", "00", "100", "robot", pw("wrong"), "EN", null, null, null, null, null, null, null, "sap")));
        assertEquals("SAP_BAPI.UNREACHABLE", code(() -> sap.connect(c, "nohost", "00", "100", "robot", pw(FakeSap.PASSWORD), "EN", null, null, null, null, null, null, null, "sap")));
        assertEquals("SAP_BAPI.NO_SECRET", code(() -> sap.connect(c, "erp.example.com", "00", "100", "robot", FakeSap.PASSWORD, "EN", null, null, null, null, null, null, null, "sap")));
        assertEquals("SAP_BAPI.BAD_SERVER", code(() -> sap.connect(c, null, null, "100", "robot", pw(FakeSap.PASSWORD), "EN", null, null, null, null, null, null, null, "sap")));
        assertEquals("SAP_BAPI.NO_SESSION", code(() -> sap.commit(c, true, "sap")));

        Map<String, Object> sys = (Map<String, Object>) sap.connect(c, "erp.example.com", "00", "100", "robot", pw(FakeSap.PASSWORD), "EN", null, null, null,
                null, null, null, Map.<String, Object>of("jco.client.trace", "0"), "sap").get("system");
        assertEquals("ERP", sys.get("system_id"));
        assertEquals("ROBOT", sys.get("user"));

        // a BAPI with an import, export structures and a RETURN table
        Map<String, Object> r = sap.callFunction(c, "bapi_user_get_detail", Map.of("USERNAME", "ALICE"), null, null, false, true, "sap");
        Map<String, Object> exports = (Map<String, Object>) r.get("exports");
        assertEquals("Alice", ((Map<String, Object>) exports.get("ADDRESS")).get("FIRSTNAME"));
        assertEquals("2026-12-31", ((Map<String, Object>) exports.get("LOGONDATA")).get("GLTGB"), "DATS as ISO date");
        assertEquals(true, r.get("success"));
        // an error in RETURN stops the step, with the SAP message
        ActionException e = assertThrows(ActionException.class, () -> sap.callFunction(c, "BAPI_USER_GET_DETAIL", Map.of("USERNAME", "NOBODY"), null, null, false, true, "sap"));
        assertEquals("SAP_BAPI.FAILED", e.code());
        assertTrue(e.getMessage().contains("User NOBODY does not exist"), e.getMessage());
        // ... or is returned when asked
        Map<String, Object> soft = sap.callFunction(c, "BAPI_USER_GET_DETAIL", Map.of("USERNAME", "NOBODY"), null, null, false, false, "sap");
        assertEquals(false, soft.get("success"));
        assertEquals("E", ((Map<String, Object>) ((List<Object>) soft.get("messages")).get(0)).get("type"));

        // a structure, table rows (list of dictionaries or an OOA table), changing value and commit in the same session
        Map<String, Object> items = com.oneofficeautomation.runner.Values.table(List.of("MATERIAL", "QUANTITY"), List.of(List.of("100-100", new BigDecimal("2.5"))));
        Map<String, Object> o = sap.callFunction(c, "Z_CREATE_ORDER", Map.of("HEADER", Map.of("CUSTOMER", "C1", "DOC_DATE", "2026-11-02")),
                Map.of("ITEMS", items), Map.of("COUNTER", new BigDecimal(5)), false, true, "sap");
        String number = (String) ((Map<String, Object>) o.get("exports")).get("ORDER_NUMBER");
        assertEquals("4500000001", number);
        assertEquals(6, ((Number) ((Map<String, Object>) o.get("changing")).get("COUNTER")).intValue());
        assertEquals(1, FakeSap.PENDING.size());
        assertEquals(List.of("100-100 x 2.5"), FakeSap.PENDING.get(0).get("items"));
        sap.commit(c, true, "sap");
        assertEquals(1, FakeSap.COMMITTED.size());
        assertTrue(FakeSap.PENDING.isEmpty());

        // rollback, and commit right after the call
        sap.callFunction(c, "Z_CREATE_ORDER", Map.of("HEADER", Map.of("CUSTOMER", "C2")), Map.of("ITEMS", List.of(Map.of("MATERIAL", "X", "QUANTITY", 1))), null, false, true, "sap");
        sap.rollback(c, "sap");
        assertTrue(FakeSap.PENDING.isEmpty());
        assertEquals(1, FakeSap.COMMITTED.size());
        sap.callFunction(c, "Z_CREATE_ORDER", Map.of("HEADER", Map.of("CUSTOMER", "C3")), Map.of("ITEMS", List.of(Map.of("MATERIAL", "Y", "QUANTITY", 3))), null, true, true, "sap");
        assertEquals(2, FakeSap.COMMITTED.size());
        // a failing BAPI is rolled back
        assertEquals("SAP_BAPI.FAILED", code(() -> sap.callFunction(c, "Z_CREATE_ORDER", Map.of("HEADER", Map.of("CUSTOMER", "C4")), Map.of("ITEMS", List.of()), null, true, true, "sap")));
        assertEquals("BAPI_TRANSACTION_ROLLBACK", FakeSap.CALLS.get(FakeSap.CALLS.size() - 1));

        // wrong names and values are clear
        assertEquals("SAP_BAPI.NO_FUNCTION", code(() -> sap.callFunction(c, "Z_NOT_THERE", null, null, null, false, true, "sap")));
        assertEquals("SAP_BAPI.BAD_PARAMETER", code(() -> sap.callFunction(c, "BAPI_USER_GET_DETAIL", Map.of("USERNAMEX", "A"), null, null, false, true, "sap")));
        assertEquals("SAP_BAPI.BAD_PARAMETER", code(() -> sap.callFunction(c, "Z_CREATE_ORDER", Map.of("HEADER", Map.of("CUSTOMER", "C")),
                Map.of("ITEMS", List.of(Map.of("MATERIAL", "X", "QUANTITY", "lots"))), null, false, true, "sap")));
        assertEquals("SAP_BAPI.BAD_TABLE", code(() -> sap.callFunction(c, "Z_CREATE_ORDER", null, Map.of("ITEMS", List.of("not a row")), null, false, true, "sap")));

        // RFC_READ_TABLE with fields, a WHERE condition and paging
        Map<String, Object> t = sap.readTable(c, "mara", List.of("MATNR", "MTART"), "MTART = 'FERT'", 10, 0, "sap");
        assertEquals(List.of("MATNR", "MTART"), ((Map<String, Object>) t.get("table")).get("columns"));
        assertEquals(List.of(List.of("100-100", "FERT"), List.of("100-300", "FERT")), ((Map<String, Object>) t.get("table")).get("rows"));
        assertEquals(new BigDecimal(1), sap.readTable(c, "MARA", null, null, 1, 2, "sap").get("row_count"));
        assertEquals("SAP_BAPI.ABAP_EXCEPTION", code(() -> sap.readTable(c, "NOPE", null, null, 10, 0, "sap")));

        // function details
        List<Object> params = (List<Object>) sap.getFunctionInfo(c, "Z_CREATE_ORDER", "sap").get("parameters");
        Map<String, Object> header = (Map<String, Object>) params.get(0);
        assertEquals("HEADER", header.get("name"));
        assertEquals("import", header.get("direction"));
        assertEquals("CUSTOMER", ((Map<String, Object>) ((List<Object>) header.get("fields")).get(0)).get("name"));

        sap.disconnect(c, "sap");
        assertEquals("SAP_BAPI.NO_SESSION", code(() -> sap.callFunction(c, "BAPI_USER_GET_DETAIL", Map.of("USERNAME", "ALICE"), null, null, false, true, "sap")));
        assertTrue(log.stream().noneMatch(l -> l.contains(FakeSap.PASSWORD)), "the password is never logged");
    }

    @Test void wherePartsFitAbapLines() {
        assertEquals(List.of("A = '1' AND", "B = '2'"), SapBapiPackage.wrap("A = '1' AND B = '2'", 12));
    }
}
