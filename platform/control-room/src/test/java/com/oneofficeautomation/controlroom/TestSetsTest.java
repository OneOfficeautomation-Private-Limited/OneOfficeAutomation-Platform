package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A2: test sets run an agent's cases and score them, with checks that suit AI answers. */
class TestSetsTest {

    @Test void checksForExactAndAiStyleAnswers() {
        assertNull(TestSets.check(120.5, new BigDecimal("120.50")), "numbers by value");
        assertNotNull(TestSets.check(120.5, new BigDecimal("121")));
        assertNull(TestSets.check(Map.of("op", "contains", "value", "refund"), "We issued a Refund today."));
        assertNotNull(TestSets.check(Map.of("op", "not_contains", "value", "sorry"), "Sorry about that"));
        assertNull(TestSets.check(Map.of("op", "matches", "value", "^INV-\\d+$"), "INV-77"));
        assertNull(TestSets.check(Map.of("op", "approx", "value", 0.8, "tolerance", 0.1), new BigDecimal("0.75")));
        assertNotNull(TestSets.check(Map.of("op", "approx", "value", 0.8, "tolerance", 0.01), new BigDecimal("0.75")));
        assertNull(TestSets.check(Map.of("op", "one_of", "value", List.of("urgent", "high")), "high"));
        assertNotNull(TestSets.check(Map.of("op", "not_empty"), ""));
        assertTrue(TestSets.judge(Map.of("status", "failed", "error_code", "EMAIL.*"), "failed", Map.of("error", Map.of("code", "EMAIL.AUTH"))).isEmpty());
        assertFalse(TestSets.judge(Map.of(), "failed", Map.of("error", Map.of("code", "X", "message", "boom"))).isEmpty(), "success is expected by default");
    }

    @Test @SuppressWarnings("unchecked")
    void aTestRunIsScoredWhenEveryCaseHasAnswered() throws Exception {
        Path data = Files.createTempDirectory("ooa-testsets");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.checkin(admin, "Support/Triage.flow.json", (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"triage\",\"name\":\"Triage\",\"description\":\"t\","
                + "\"variables\":[{\"name\":\"text\",\"type\":\"String\",\"direction\":\"input\"},{\"name\":\"label\",\"type\":\"String\",\"direction\":\"output\"}],\"steps\":[]}"), "v1", true, null);
            Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "PC-T", "pc-t", "Windows 11", "0.2.0", null);
            String device = (String) dev.get("device_id");

            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.testSets.save(admin, null, Map.of("name", "x", "bot_path", "Support/Triage.flow.json",
                "cases", List.of(Map.of("name", "a", "expect", Map.of("outputs", Map.of("label", Map.of("op", "smells_like", "value", "x")))))), null)).code());
            String set = (String) cr.testSets.save(admin, null, Map.of("name", "Triage basics", "bot_path", "Support/Triage.flow.json", "cases", List.of(
                Map.of("name", "urgent mail", "inputs", Map.of("text", "Server down!"), "expect", Map.of("outputs", Map.of("label", Map.of("op", "one_of", "value", List.of("urgent", "high"))))),
                Map.of("name", "thank you", "inputs", Map.of("text", "Thanks"), "expect", Map.of("outputs", Map.of("label", "low"))),
                Map.of("name", "bad input", "inputs", Map.of("text", ""), "expect", Map.of("status", "failed")))), null).get("id");

            Map<String, Object> started = cr.testSets.run(admin, set, device, null, null, null);
            String tr = (String) started.get("test_run_id");
            assertEquals(3, started.get("started"));
            assertEquals("running", cr.testSets.testRun(admin, tr).get("status"));

            // the runner answers each case
            Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", device);
            for (Map<String, Object> r : db.query("SELECT r.id, t.case_name FROM runs r JOIN test_results t ON t.run_id = r.id WHERE t.test_run_id = ?", tr)) {
                db.update("UPDATE runs SET status = 'running', started = CURRENT_TIMESTAMP WHERE id = ?", r.get("id"));
                Map<String, Object> result = switch (String.valueOf(r.get("case_name"))) {
                    case "urgent mail" -> Map.of("status", "success", "outputs", Map.of("label", "high"));
                    case "thank you" -> Map.of("status", "success", "outputs", Map.of("label", "medium"));
                    default -> Map.of("status", "failed", "error", Map.of("code", "TRIAGE.EMPTY", "message", "No text"));
                };
                cr.finish(d, (String) r.get("id"), result);
            }
            Map<String, Object> scored = cr.testSets.testRun(admin, tr);
            assertEquals("failed", scored.get("status"));
            assertEquals(2, ((Number) scored.get("passed")).intValue());
            assertEquals(3, ((Number) scored.get("total")).intValue());
            Map<String, Object> thanks = ((List<Map<String, Object>>) scored.get("results")).stream().filter(x -> "thank you".equals(x.get("case_name"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(thanks.get("detail")).contains("expected \"low\""), String.valueOf(thanks.get("detail")));
            assertEquals(1, cr.testSets.history(admin, set).size());
            assertNotNull(((Map<String, Object>) cr.testSets.list(admin, "Support/Triage.flow.json").get(0)).get("last_run"));

            // a case that cannot start fails at once and the run is still scored
            cr.setDeviceEnabled(admin, device, false, null);
            Map<String, Object> blocked = cr.testSets.run(admin, set, device, null, null, null);
            assertEquals(0, blocked.get("started"));
            assertEquals("failed", cr.testSets.testRun(admin, (String) blocked.get("test_run_id")).get("status"));
        } finally { db.close(); }
    }
}
