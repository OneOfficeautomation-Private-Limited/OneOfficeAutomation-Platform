package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Alert rules: who may make them, failed runs (delivered to the inbox, held back in a storm), failure rates and silent runners that clear by themselves. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlertsTest {
    Path data; Db db; ControlRoom cr;
    ControlRoom.User admin, carl;
    String device;
    int runs;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-alerts");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        carl = RepositoryLifecycleTest.person(cr, admin, "carl", "bot_creator");
        cr.checkin(admin, "Finance/Pay.flow.json", TeamsVaultAuditAnalyticsTest.flow("pay"), "v1", true, null);
        cr.checkin(admin, "Ops/Backup.flow.json", TeamsVaultAuditAnalyticsTest.flow("backup"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        device = (String) d.get("device_id");
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    /** A finished run, just now. */
    void run(String bot, String status, String errorCode) throws InterruptedException {
        Thread.sleep(3);   // after the rule's last check
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, started, finished, cancel_requested, pause_requested, duration_ms, error) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", "run_t" + (++runs), bot, 1, device, status, "{}", "admin", "manual", Instant.now(), Instant.now(), Instant.now(), false, false, 1000L,
                errorCode == null ? null : JsonLite.write(Map.of("code", errorCode, "message", "x")));
        Thread.sleep(3);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> open(String ruleId) {
        return ((List<Map<String, Object>>) cr.alerts.list(admin, Map.of("rule", ruleId)).get("alerts"));
    }

    @Test @Order(1)
    void onlyAlertManagersMakeRulesAndTheRulesAreChecked() {
        Map<String, Object> rule = new HashMap<>(Map.of("name", "Any failure", "kind", "run_failed", "notify_to", List.of("role:admin")));
        assertEquals("CR.FORBIDDEN", code(() -> cr.alerts.saveRule(carl, null, rule, null)));   // alerts.manage is for administrators
        assertEquals("CR.BAD_INPUT", code(() -> cr.alerts.saveRule(admin, null, Map.of("name", "x", "kind", "nope", "notify_to", List.of("role:admin")), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.alerts.saveRule(admin, null, Map.of("name", "x", "kind", "run_failed"), null)));   // nobody to tell
        assertEquals("CR.BAD_INPUT", code(() -> cr.alerts.saveRule(admin, null, Map.of("name", "x", "kind", "run_failed", "notify_to", List.of("everyone")), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.alerts.saveRule(admin, null, Map.of("name", "x", "kind", "failure_rate", "threshold", 150, "notify_to", List.of("role:admin")), null)));
        assertEquals("CR.NOT_FOUND", code(() -> cr.alerts.saveRule(admin, null, Map.of("name", "x", "kind", "runner_offline", "scope", "dev_nope", "notify_to", List.of("role:admin")), null)));
        assertEquals(List.of(), cr.alerts.rules(carl));   // carl may look (analytics.view)
    }

    @Test @Order(2)
    @SuppressWarnings("unchecked")
    void aFailedRunRaisesOneAlertSentToTheInboxUntilAcknowledged() throws Exception {
        String id = (String) cr.alerts.saveRule(admin, null, Map.of("name", "Any failure", "kind", "run_failed", "notify_to", List.of("role:admin", "ops@example.com")), null).get("id");
        run("Finance/Pay.flow.json", "failed", "WEB.TIMEOUT");
        run("Finance/Pay.flow.json", "success", null);
        cr.alerts.check();
        cr.alerts.check();   // the same failure is not raised twice
        List<Map<String, Object>> a = open(id);
        assertEquals(1, a.size());
        assertEquals("Pay failed: WEB.TIMEOUT x", a.get(0).get("message"));
        assertEquals("#/activity?run=run_t1", a.get(0).get("link"));
        Map<String, Object> delivery = (Map<String, Object>) a.get(0).get("delivery");
        assertEquals(List.of("role:admin"), delivery.get("inbox"));
        assertEquals(List.of("ops@example.com"), delivery.get("not_emailed"));   // e-mail is not set up here
        Map<String, Object> notice = db.one("SELECT title, assignee FROM human_tasks WHERE kind = 'notification'");
        assertEquals("Alert: Any failure", notice.get("title"));
        assertEquals("role:admin", notice.get("assignee"));
        assertEquals(1, cr.alerts.acknowledge(admin, List.of(a.get(0).get("id")), null).get("acknowledged"));
        assertEquals(0, open(id).size());
        // a storm of failures: 20 alerts, then one that says how many were held back
        for (int i = 0; i < 25; i++) run("Ops/Backup.flow.json", "failed", "FILE.NOT_FOUND");
        cr.alerts.check();
        List<Map<String, Object>> storm = open(id);
        assertEquals(21, storm.size());
        assertTrue(storm.stream().anyMatch(x -> String.valueOf(x.get("message")).startsWith("5 more alerts of this rule held back")));
        cr.alerts.deleteRule(admin, id, null);
        assertEquals(0, open(id).size());   // the rule's alerts are cleared; their history stays
    }

    @Test @Order(3)
    void aFailureRateAndASilentRunnerClearWhenTheyEnd() throws Exception {
        db.update("DELETE FROM runs");
        String rate = (String) cr.alerts.saveRule(admin, null, Map.of("name", "Backup failing", "kind", "failure_rate", "scope", "Ops/Backup.flow.json", "threshold", 50,
                "window_minutes", 60, "notify_to", List.of("user:admin"), "severity", "critical"), null).get("id");
        run("Ops/Backup.flow.json", "failed", "FILE.NOT_FOUND");
        run("Ops/Backup.flow.json", "failed", "FILE.NOT_FOUND");
        cr.alerts.check();
        assertEquals(0, open(rate).size());   // fewer than 3 finished runs do not count
        run("Ops/Backup.flow.json", "success", null);
        cr.alerts.check();
        assertEquals("67% of the runs of Backup failed in the last 60 minutes (2 of 3)", open(rate).get(0).get("message"));
        assertEquals("critical", open(rate).get(0).get("severity"));
        db.update("UPDATE runs SET status = 'success', error = NULL");
        cr.alerts.check();
        assertEquals(0, open(rate).size());
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM alerts WHERE rule_id = ? AND status = 'cleared'", rate));

        String silent = (String) cr.alerts.saveRule(admin, null, Map.of("name", "PC-1 silent", "kind", "runner_offline", "scope", device, "window_minutes", 5,
                "notify_to", List.of("role:admin")), null).get("id");
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now().minusSeconds(600), device);
        cr.alerts.check();
        assertTrue(String.valueOf(open(silent).get(0).get("message")).startsWith("Runner PC-1 has not reported since"));
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now(), device);
        cr.alerts.check();
        assertEquals(0, open(silent).size());
        cr.alerts.setEnabled(admin, silent, false, null);
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now().minusSeconds(600), device);
        cr.alerts.check();
        assertEquals(0, open(silent).size());   // a rule that is off is not checked
    }
}
