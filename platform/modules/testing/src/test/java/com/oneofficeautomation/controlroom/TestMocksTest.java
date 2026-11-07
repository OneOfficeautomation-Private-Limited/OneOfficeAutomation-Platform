package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.testing.TestSets;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G61: a test case's mocks are checked when the set is saved and travel with the case's run, in the reserved input. */
class TestMocksTest {
    @SuppressWarnings("unchecked")
    @Test void mocksGoWithTheRun() throws Exception {
        Path data = Files.createTempDirectory("ooa-g61");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.checkin(admin, "T/Order.flow.json", (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"order\",\"name\":\"Order\",\"description\":\"t\","
                    + "\"variables\":[{\"name\":\"po\",\"type\":\"String\",\"direction\":\"input\"}],\"steps\":[]}"), "v1", true, null);
            String device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "PC-M", "pc-m", "Windows 11", "0.2.0", null).get("device_id");
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(TestSets.class).save(admin, null, Map.of("name", "x", "bot_path", "T/Order.flow.json",
                    "cases", List.of(Map.of("name", "a", "mocks", Map.of("s1", Map.of("outputs", "not a dictionary"))))), null)).code());
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(TestSets.class).save(admin, null, Map.of("name", "x", "bot_path", "T/Order.flow.json",
                    "cases", List.of(Map.of("name", "a", "mocks", Map.of("s1", Map.of())))), null)).code(), "a mock gives outputs or an error");
            Map<String, Object> mock = Map.of("erp", Map.of("outputs", Map.of("status", "booked")));
            String set = (String) cr.modules.service(TestSets.class).save(admin, null, Map.of("name", "Orders", "bot_path", "T/Order.flow.json",
                    "cases", List.of(Map.of("name", "booked", "inputs", Map.of("po", "PO-1"), "mocks", mock), Map.of("name", "plain", "inputs", Map.of("po", "PO-2")))), null).get("id");
            String tr = (String) cr.modules.service(TestSets.class).run(admin, set, device, null, null, null).get("test_run_id");
            Map<String, String> inputsByCase = new HashMap<>();
            for (Map<String, Object> r : db.query("SELECT r.inputs, t.case_name FROM runs r JOIN test_results t ON t.run_id = r.id WHERE t.test_run_id = ?", tr))
                inputsByCase.put(String.valueOf(r.get("case_name")), String.valueOf(r.get("inputs")));
            Map<String, Object> booked = (Map<String, Object>) JsonLite.parse(inputsByCase.get("booked"));
            assertEquals("PO-1", booked.get("po"));
            assertEquals(mock, booked.get(TestSets.MOCKS_INPUT));
            assertFalse(inputsByCase.get("plain").contains(TestSets.MOCKS_INPUT));
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.startRun("admin", "admin", "T/Order.flow.json", device,
                    Map.of("po", "x", TestSets.MOCKS_INPUT, mock), "manual", null)).code(), "only test-set runs take mocks");
        } finally { db.close(); }
    }
}
