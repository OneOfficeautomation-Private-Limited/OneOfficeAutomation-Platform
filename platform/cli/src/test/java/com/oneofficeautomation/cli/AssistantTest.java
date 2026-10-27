package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** R13: the attended desktop app's calls (as the person, with their API key) and the notices it shows. */
class AssistantTest {

    @Test @SuppressWarnings("unchecked")
    void startsAgentsAsThePersonAndReportsWhatFinished() throws Exception {
        Path data = Files.createTempDirectory("ooa-assistant");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t), pw, "Admin-Passw0rd-2026", t, null);
        ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            cr.checkin(admin, "Finance/Pay.flow.json", (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"pay\",\"name\":\"Pay\",\"description\":\"t\","
                + "\"variables\":[{\"name\":\"amount\",\"type\":\"Number\",\"direction\":\"input\",\"required\":true},{\"name\":\"note\",\"type\":\"String\",\"direction\":\"local\"}],\"steps\":[]}"), "v1", true, null);
            String device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "PC-ME", "pc-me", "Windows 11", "0.2.0", null).get("device_id");
            cr.setDefaultDevice(admin, admin.id(), device, null);
            String key = (String) cr.platform.createApiKey(admin, "assistant", 30, null).get("key");

            AssistantClient c = new AssistantClient("http://127.0.0.1:" + server.port(), key, null);
            assertEquals("admin", c.username());
            assertEquals(List.of("Finance/Pay.flow.json"), c.agents().stream().map(b -> b.get("path")).toList());
            List<Map<String, Object>> inputs = c.inputs("Finance/Pay.flow.json");
            assertEquals(1, inputs.size(), "only inputs are asked for");
            assertEquals("amount", inputs.get(0).get("name"));

            String run = c.run("Finance/Pay.flow.json", Map.of("amount", Assistant.value("Number", "42.5")), "admin");
            Map<String, Object> r = db.one("SELECT device_id, run_as, run_mode, inputs, status FROM runs WHERE id = ?", run);
            assertEquals(device, r.get("device_id"), "the person's own default runner");
            assertEquals("attended", r.get("run_mode"));
            assertEquals(0, new BigDecimal("42.5").compareTo((BigDecimal) ((Map<String, Object>) JsonLite.parse((String) r.get("inputs"))).get("amount")));

            // the first look remembers; a run that then finishes gives one notice, and only once
            AssistantClient.Watch w = new AssistantClient.Watch();
            assertTrue(w.update(c.openTasks(), c.myRuns("admin")).isEmpty());
            db.update("UPDATE runs SET status = 'running', started = CURRENT_TIMESTAMP WHERE id = ?", run);
            cr.finish(db.one("SELECT * FROM devices WHERE id = ?", device), run, Map.of("status", "failed", "error", Map.of("code", "X", "message", "x")));
            List<AssistantClient.Notice> n = w.update(c.openTasks(), c.myRuns("admin"));
            assertEquals(1, n.size());
            assertEquals("Agent failed", n.get(0).title());
            assertEquals("Finance/Pay", n.get(0).text());
            assertTrue(w.update(c.openTasks(), c.myRuns("admin")).isEmpty());

            assertEquals("CR.UNAUTHENTICATED", assertThrows(OoaException.class, () -> new AssistantClient("http://127.0.0.1:" + server.port(), "ooak_wrong", null).username()).code());
            assertEquals("ASSISTANT.BAD_INPUT", assertThrows(OoaException.class, () -> Assistant.value("Number", "abc")).code());
        } finally { server.stop(); db.close(); }
    }

    @Test void newTasksGiveANotice() {
        AssistantClient.Watch w = new AssistantClient.Watch();
        assertTrue(w.update(List.of(Map.of("id", "t1", "title", "Old")), List.of()).isEmpty());
        List<AssistantClient.Notice> n = w.update(List.of(Map.of("id", "t1", "title", "Old"), Map.of("id", "t2", "title", "Approve invoice 42")), List.of());
        assertEquals(List.of(new AssistantClient.Notice("New task", "Approve invoice 42", "inbox")), n);
    }

    @Test void theKeyIsProtectedForThisWindowsUser() {
        assumeTrue(Assistant.WINDOWS, "DPAPI is Windows only");
        String stored = Assistant.protect("ooak_secret-value");
        assertFalse(stored.contains("ooak_secret"));
        assertEquals("ooak_secret-value", Assistant.unprotect(stored));
    }
}
