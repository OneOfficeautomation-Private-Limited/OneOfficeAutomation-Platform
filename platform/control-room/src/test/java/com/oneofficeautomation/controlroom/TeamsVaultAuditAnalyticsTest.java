package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Teams in access rules; the vault model (members, credential owners, people's own values, key rotation); audit search; operations analytics. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TeamsVaultAuditAnalyticsTest {
    Path data; Db db; ControlRoom cr;
    ControlRoom.User admin, jane, bob, carl;
    String device, deviceSecret, teamId;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String id) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/comment\",\"args\":{\"text\":\"x\"}}]}");
    }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-tvaa");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
        bob = RepositoryLifecycleTest.person(cr, admin, "bob", "bot_runner");
        carl = RepositoryLifecycleTest.person(cr, admin, "carl", "bot_creator");
        cr.checkin(admin, "Finance/Pay.flow.json", flow("pay"), "v1", true, null);
        cr.checkin(admin, "Finance/Close.flow.json", flow("close"), "v1", true, null);
        cr.checkin(admin, "Ops/Backup.flow.json", flow("backup"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        device = (String) d.get("device_id"); deviceSecret = (String) d.get("secret");
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @Order(1)
    void teamsInAccessRules() {
        teamId = (String) cr.teams.save(admin, null, "finance", "Finance people", null).get("id");
        cr.teams.setMembers(admin, teamId, List.of(Map.of("username", "jane", "role", "lead"), Map.of("username", "bob")), null);
        assertEquals(List.of("finance"), cr.teams.teamsOf("bob"));
        // a lead changes the members (and must stay a lead); a member cannot
        cr.teams.setMembers(jane, teamId, List.of(Map.of("username", "jane", "role", "lead"), Map.of("username", "bob"), Map.of("username", "carl")), null);
        assertEquals("CR.FORBIDDEN", code(() -> cr.teams.setMembers(bob, teamId, List.of(), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.teams.setMembers(jane, teamId, List.of(Map.of("username", "bob")), null)));
        cr.teams.setMembers(jane, teamId, List.of(Map.of("username", "jane", "role", "lead"), Map.of("username", "bob")), null);
        // only an administrator appoints leads
        assertEquals("CR.FORBIDDEN", code(() -> cr.teams.setMembers(jane, teamId, List.of(Map.of("username", "jane", "role", "lead"), Map.of("username", "bob", "role", "lead")), null)));
        // a folder for the team only: carl (not in it) no longer sees Finance
        cr.folders.setPermissions(admin, "Finance", List.of(Map.of("principal", "team:finance", "access", "run")), null);
        assertEquals("run", cr.folders.access("bob", "bot_runner", "Finance/Pay.flow.json"));
        assertEquals("none", cr.folders.access("carl", "bot_creator", "Finance/Pay.flow.json"));
        assertTrue(cr.repoList(carl, null).stream().noneMatch(b -> String.valueOf(b.get("path")).startsWith("Finance/")));
        // a team still named in a rule is not deleted; people see the teams they are in
        assertEquals("CR.IN_USE", code(() -> cr.teams.delete(admin, teamId, null)));
        assertEquals(List.of("agent folder Finance"), cr.teams.list(admin).get(0).get("used_in"), "where the team is used");
        assertEquals(1, cr.teams.list(bob).size());
        assertEquals(0, cr.teams.list(carl).size());
        assertEquals("CR.NOT_FOUND", code(() -> cr.folders.setPermissions(admin, "Ops", List.of(Map.of("principal", "team:nope", "access", "view")), null)));
    }

    @Test @Order(2)
    @SuppressWarnings("unchecked")
    void vaultModel() throws Exception {
        cr.createLocker(admin, "fin", "Finance systems", "team:finance", null);
        cr.vaultAdmin.setMembers(admin, "fin", Map.of("managers", List.of("user:carl"), "participants", List.of("team:finance")), null);
        // a participant creates a credential and owns it; another participant cannot change it; a manager can
        cr.setCredential(bob, "fin", "erp", "user", "svc-erp", null);
        assertEquals("bob", cr.vaultAdmin.credential(admin, "fin", "erp").get("owner"));
        assertEquals("CR.FORBIDDEN", code(() -> cr.setCredential(jane, "fin", "erp", "user", "x", null)));
        cr.setCredential(carl, "fin", "erp", "url", "https://erp.test", null);
        assertEquals("participant", cr.lockers(jane).stream().filter(l -> l.get("name").equals("fin")).findFirst().orElseThrow().get("my_role"));
        assertEquals(Boolean.TRUE, cr.me(jane).get("leads_team"), "jane leads finance: the console shows her the Teams page");
        assertEquals(Boolean.FALSE, cr.me(bob).get("leads_team"));
        // user-provided attribute: each person's own value; a run reads its user's value
        cr.vaultAdmin.saveCredential(bob, "fin", "erp", Map.of("user_attributes", List.of("password"), "description", "ERP sign-in"), null);
        assertEquals("CR.BAD_INPUT", code(() -> cr.setCredential(bob, "fin", "erp", "password", "shared", null)));
        cr.vaultAdmin.setMyValue(bob, "fin", "erp", "password", "bobs-secret", null);
        cr.vaultAdmin.setMyValue(jane, "fin", "erp", "password", "janes-secret", null);
        assertEquals(List.of("password"), cr.vaultAdmin.credential(bob, "fin", "erp").get("my_values"));
        cr.setDefaultDevice(admin, "bob", device, null);
        cr.setDefaultDevice(admin, "carl", device, null);
        Map<String, Object> dev = cr.device(device, deviceSecret);
        String runBob = (String) cr.startRun("admin", "admin", "Ops/Backup.flow.json", null, null, Map.of(), "manual", null, 0, null, "bob", null).get("run_id");
        cr.claimNext(dev);
        assertEquals("bobs-secret", cr.credentialForRun(dev, runBob, "fin/erp/password"));
        assertEquals("svc-erp", cr.credentialForRun(dev, runBob, "fin/erp/user"));
        db.update("UPDATE runs SET status = 'success' WHERE id = ?", runBob);
        String runCarl = (String) cr.startRun("admin", "admin", "Ops/Backup.flow.json", null, null, Map.of(), "manual", null, 0, null, "carl", null).get("run_id");
        cr.claimNext(dev);
        assertEquals("VAULT.DENIED", code(() -> cr.credentialForRun(dev, runCarl, "fin/erp/password")));   // carl manages the folder but is not a consumer
        db.update("UPDATE runs SET status = 'success' WHERE id = ?", runCarl);
        // key rotation: everything still reads, the key changed, the old key is kept
        String before = (String) cr.vaultAdmin.status(admin).get("key_fingerprint");
        Map<String, Object> rot = cr.vaultAdmin.rotate(admin, null);
        // four vault values, plus what the Command Center keeps for itself (install id, audit chain key)
        assertEquals(4 + db.query("SELECT name FROM settings WHERE name IN ('evaluation_started', 'install_id', 'audit_chain_key')").size(), ((Number) rot.get("re_encrypted")).intValue());
        assertNotEquals(before, rot.get("key_fingerprint"));
        try (var s = Files.list(data)) { assertTrue(s.anyMatch(f -> f.getFileName().toString().startsWith("master.key.old-"))); }
        String runBob2 = (String) cr.startRun("admin", "admin", "Ops/Backup.flow.json", null, null, Map.of(), "manual", null, 0, null, "bob", null).get("run_id");
        cr.claimNext(dev);
        assertEquals("bobs-secret", cr.credentialForRun(dev, runBob2, "fin/erp/password"));
        assertEquals("svc-erp", cr.credentialForRun(dev, runBob2, "fin/erp/user"));
        db.update("UPDATE runs SET status = 'success' WHERE id = ?", runBob2);
        assertEquals(rot.get("key_fingerprint"), new Vault(data).fingerprint());   // the key file on disk is the new key
        // rekey (ooa vault rekey): a key the administrator brings; values still read, the key file holds it
        byte[] brought = new byte[32];
        new java.security.SecureRandom().nextBytes(brought);
        Map<String, Object> rk = cr.vaultAdmin.rekey(brought);
        assertEquals(Vault.ofKey(brought, "test").fingerprint(), rk.get("key_fingerprint"));
        assertEquals(Vault.ofKey(brought, "test").fingerprint(), new Vault(data).fingerprint());
        String runBob3 = (String) cr.startRun("admin", "admin", "Ops/Backup.flow.json", null, null, Map.of(), "manual", null, 0, null, "bob", null).get("run_id");
        cr.claimNext(dev);
        assertEquals("bobs-secret", cr.credentialForRun(dev, runBob3, "fin/erp/password"));
        db.update("UPDATE runs SET status = 'success' WHERE id = ?", runBob3);
        assertEquals("CR.VAULT_KEY", code(() -> cr.vaultAdmin.rekey(brought)), "the key in use is refused as the new key");
        // a folder with credentials is not deleted; whole-credential delete, then the folder
        assertEquals("CR.CONFLICT", code(() -> cr.vaultAdmin.deleteLocker(admin, "fin", null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.vaultAdmin.deleteCredential(jane, "fin", "erp", null)));
        cr.vaultAdmin.deleteCredential(bob, "fin", "erp", null);
        assertEquals(0L, cr.count("SELECT COUNT(*) AS n FROM credential_user_values"));
        cr.vaultAdmin.deleteLocker(admin, "fin", null);
        assertTrue(cr.lockers(admin).isEmpty());
    }

    @Test @Order(3)
    @SuppressWarnings("unchecked")
    void auditSearch() {
        Map<String, Object> all = cr.auditSearch(admin, Map.of("limit", "5"));
        assertEquals(5, ((List<Object>) all.get("records")).size());
        assertTrue(((Number) all.get("total")).longValue() > 20);
        List<Map<String, Object>> teamRecs = (List<Map<String, Object>>) cr.auditSearch(admin, Map.of("action", "team.", "sort", "oldest")).get("records");
        assertEquals("team.save", teamRecs.get(0).get("action"));
        assertTrue(teamRecs.stream().allMatch(r -> String.valueOf(r.get("action")).startsWith("team.")));
        List<Map<String, Object>> janes = (List<Map<String, Object>>) cr.auditSearch(admin, Map.of("actor", "jane", "action", "team.members")).get("records");
        assertEquals(2, janes.size());
        assertEquals(0L, ((Number) cr.auditSearch(admin, Map.of("from", "2000-01-01", "to", "2000-01-02")).get("total")).longValue());
        Map<String, Object> page2 = cr.auditSearch(admin, Map.of("limit", "3", "offset", "3", "sort", "oldest"));
        assertEquals(4L, ((Number) ((List<Map<String, Object>>) page2.get("records")).get(0).get("seq")).longValue());
        String csv = cr.auditCsv(admin, Map.of("action", "vault."), null);
        assertTrue(csv.startsWith("seq,time,actor,action,object_type,object_id,result,ip,details\r\n"), csv);
        assertTrue(csv.contains(",vault.rotate,"), csv);
        assertEquals("audit.export", ((List<Map<String, Object>>) cr.auditSearch(admin, Map.of("limit", "1")).get("records")).get(0).get("action"));
        assertEquals("CR.FORBIDDEN", code(() -> cr.auditSearch(jane, Map.of())));
    }

    @Test @Order(4)
    @SuppressWarnings("unchecked")
    void operationsAnalytics() {
        db.update("DELETE FROM runs");
        Instant t = Instant.now().minusSeconds(3600);
        Object[][] rows = { // bot, status, ms, error code
            {"Finance/Pay.flow.json", "success", 30_000L, null}, {"Finance/Pay.flow.json", "success", 90_000L, null}, {"Finance/Pay.flow.json", "failed", 20_000L, "WEB.TIMEOUT"},
            {"Finance/Close.flow.json", "success", 2_000_000L, null}, {"Ops/Backup.flow.json", "failed", 400_000L, "FILE.NOT_FOUND"},
            {"Ops/Backup.flow.json", "failed", 500_000L, "FILE.NOT_FOUND"}, {"Ops/Backup.flow.json", "stopped", 1_000L, null}};
        int n = 0;
        for (Object[] r : rows) {
            n++;
            db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested, duration_ms, error) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "run_a" + n, r[0], 1, device, r[1], "{}", "admin", n % 2 == 0 ? "schedule:s1" : "manual", t.plusSeconds(n), false, false, r[2],
                    r[3] == null ? null : JsonLite.write(Map.of("code", r[3], "message", "x")));
        }
        Map<String, Object> o = cr.analytics.overview(admin, Map.of());
        Map<String, Object> tot = (Map<String, Object>) o.get("totals");
        assertEquals(7, tot.get("runs"));
        assertEquals(3L, tot.get("success"));
        assertEquals(50.0, tot.get("success_rate"));   // 3 of 6 finished (stopped not counted)
        List<Map<String, Object>> dist = (List<Map<String, Object>>) o.get("duration_distribution");
        assertEquals(List.of(3L, 1L, 2L, 1L, 0L), dist.stream().map(x -> ((Number) x.get("runs")).longValue()).toList());
        assertEquals("Finance/Pay.flow.json", ((List<Map<String, Object>>) o.get("most_run")).get(0).get("bot_path"));
        assertEquals("Finance/Close.flow.json", ((List<Map<String, Object>>) o.get("longest")).get(0).get("bot_path"));
        Map<String, Object> worst = ((List<Map<String, Object>>) o.get("most_failed")).get(0);
        assertEquals("Ops/Backup.flow.json", worst.get("bot_path"));
        assertEquals(100.0, worst.get("failure_rate"));
        assertEquals(List.of("FILE.NOT_FOUND (2)"), worst.get("top_errors"));
        // failure reasons across all agents: the most common first, with the agent failing with it most
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) o.get("failure_reasons");
        assertEquals(List.of("FILE.NOT_FOUND", "WEB.TIMEOUT"), reasons.stream().map(x -> x.get("code")).toList());
        assertEquals(2, reasons.get(0).get("runs"));
        assertEquals("Ops/Backup.flow.json", reasons.get(0).get("agent"));
        // when runs start, per weekday and hour; and the period before, for comparison (empty here)
        Map<String, Object> heat = (Map<String, Object>) o.get("by_hour");
        assertEquals(7L, ((List<List<Long>>) heat.get("runs")).stream().flatMap(List::stream).mapToLong(Long::longValue).sum());
        assertEquals(3L, ((List<List<Long>>) heat.get("failed")).stream().flatMap(List::stream).mapToLong(Long::longValue).sum());
        assertEquals("Asia/Kolkata", ((Map<String, Object>) cr.analytics.overview(admin, Map.of("tz", "Asia/Kolkata")).get("by_hour")).get("time_zone"));
        assertEquals(0, ((Map<String, Object>) o.get("previous")).get("runs"));
        assertEquals(List.of("manual", "schedule"), ((List<Map<String, Object>>) o.get("by_source")).stream().map(x -> x.get("source")).toList());
        // carl may not see Finance: its runs are not counted for him
        assertEquals(3, ((Map<String, Object>) cr.analytics.overview(carl, Map.of()).get("totals")).get("runs"));
        Map<String, Object> pay = cr.analytics.agent(admin, "Finance/Pay.flow.json", Map.of());
        assertEquals(3, ((Map<String, Object>) pay.get("totals")).get("runs"));
        assertEquals("WEB.TIMEOUT", ((List<Map<String, Object>>) pay.get("errors")).get(0).get("code"));
        String csv = cr.analytics.csv(admin, "most_failed", Map.of());
        assertTrue(csv.startsWith("agent,runs,success,failed,stopped,success_rate,total_ms,avg_ms,p95_ms,max_ms,failure_rate,top_errors\r\n"), csv);
        assertTrue(cr.analytics.csv(admin, "run_history", Map.of()).split("\r\n").length == 8);
        assertEquals("CR.BAD_INPUT", code(() -> cr.analytics.csv(admin, "nope", Map.of())));
        assertEquals("CR.FORBIDDEN", code(() -> cr.analytics.agent(carl, "Finance/Pay.flow.json", Map.of())));
    }

    @Test @Order(5)
    @SuppressWarnings("unchecked")
    void runnerCapacity() {
        db.update("DELETE FROM runs");
        Instant now = Instant.now();
        // an hour's run that waited 10 minutes to start, and one running for 30 minutes so far
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, started, finished, cancel_requested, pause_requested, duration_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "run_c1", "Ops/Backup.flow.json", 1, device, "success", "{}", "admin", "manual", now.minusSeconds(7800), now.minusSeconds(7200), now.minusSeconds(3600), false, false, 3_600_000L);
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, started, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "run_c2", "Ops/Backup.flow.json", 1, device, "running", "{}", "admin", "manual", now.minusSeconds(1800), now.minusSeconds(1800), false, false);
        Map<String, Object> c = cr.analytics.capacity(admin, Map.of("days", "1", "tz", "Asia/Kolkata"));
        Map<String, Object> pc = ((List<Map<String, Object>>) c.get("runners")).get(0);
        assertEquals(device, pc.get("device_id"));
        assertEquals(2L, pc.get("runs"));
        long busy = ((Number) pc.get("busy_ms")).longValue();
        assertTrue(Math.abs(busy - 5_400_000L) < 5_000, "an hour and a half busy: " + busy);
        assertEquals(300_000L, c.get("avg_wait_ms"));   // 10 minutes and none, on average 5
        assertEquals(6.25, ((Number) c.get("use")).doubleValue(), 0.06);   // 1.5 of 24 hours, one runner
        assertEquals(0.3, c.get("full_time_people"));   // 1.5 hours of a 40-hour week's day share (5.7 hours)
        double heat = ((List<List<Double>>) ((Map<String, Object>) c.get("heat")).get("use")).stream().flatMap(List::stream).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        assertTrue(heat > 140 && heat < 160, "the hours it was busy add up to 150% of an hour: " + heat);
        assertEquals("Asia/Kolkata", ((Map<String, Object>) c.get("heat")).get("time_zone"));
    }

    @Test @Order(6)
    @SuppressWarnings("unchecked")
    void workQueueInsights() {
        Instant now = Instant.now();
        db.update("INSERT INTO queues (id, name, sla_minutes, max_attempts, processing, created) VALUES (?,?,?,?,?,?)", "q_ins", "Invoices", 60, 1, false, now.minusSeconds(86_000));
        Object[][] items = { // status, created (minutes ago), finished (minutes ago), run, error
            {"new", 70, null, null, null}, {"new", 50, null, null, null}, {"retry", 10, null, null, null}, {"in_progress", 5, null, null, null},
            {"success", 120, 60, "run_q1", null}, {"failed", 30, 5, null, "FILE.NOT_FOUND"}};
        int n = 0;
        for (Object[] i : items)
            db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, run_id, error, created, updated, finished) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    "qi_" + (++n), "q_ins", "{}", i[0], 0, 1, i[3], i[4] == null ? null : JsonLite.write(Map.of("code", i[4], "message", "x")),
                    now.minusSeconds(60L * (Integer) i[1]), now, i[2] == null ? null : now.minusSeconds(60L * (Integer) i[2]));
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, started, finished, cancel_requested, pause_requested, duration_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "run_q1", "Ops/Backup.flow.json", 1, device, "success", "{}", "admin", "queue:q_ins", now.minusSeconds(6600), now.minusSeconds(6600), now.minusSeconds(3600), false, false, 600_000L);
        Map<String, Object> qi = ((List<Map<String, Object>>) cr.analytics.queues(admin, Map.of("days", "1")).get("queues")).stream()
                .filter(x -> "q_ins".equals(x.get("id"))).findFirst().orElseThrow();
        assertEquals(3L, qi.get("waiting"));
        assertEquals(1L, qi.get("overdue"));     // waiting 70 minutes, past the hour's target
        assertEquals(1L, qi.get("at_risk"));     // waiting 50 minutes, past three quarters of it
        assertEquals(6L, qi.get("added"));
        assertEquals(2L, qi.get("finished"));
        assertEquals(50.0, qi.get("success_rate"));
        assertEquals(100.0, qi.get("in_time_rate"));   // both finished within the hour's target
        assertEquals(600_000L, qi.get("avg_handling_ms"));
        assertEquals(600_000L, qi.get("avg_wait_ms"));  // the run started 10 minutes after the item came
        assertEquals(1L, qi.get("runners_needed"));     // 3 waiting x 10 minutes within an hour
        assertEquals(List.of(Map.of("code", "FILE.NOT_FOUND", "items", 1L)), qi.get("errors"));
    }

    @Test @Order(7)
    @SuppressWarnings("unchecked")
    void valueSaved() {
        db.update("DELETE FROM runs");
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("currency", "rupees"), null)));
        cr.platform.updateSettings(admin, Map.of("default_minutes_saved", 10, "hourly_cost", 600, "currency", "INR"), null);
        cr.platform.setMinutesSaved(admin, "Finance/Pay.flow.json", 30, null);
        Instant t = Instant.now().minusSeconds(3600);
        Object[][] rows = {{"Finance/Pay.flow.json", "success"}, {"Finance/Pay.flow.json", "success"}, {"Finance/Pay.flow.json", "failed"}, {"Ops/Backup.flow.json", "success"}};
        int n = 0;
        for (Object[] r : rows)
            db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested, duration_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    "run_v" + (++n), r[0], 1, device, r[1], "{}", "admin", "manual", t.plusSeconds(n), false, false, 60_000L);
        Map<String, Object> v = cr.analytics.value(admin, Map.of("days", "1"));
        assertEquals("INR", v.get("currency"));
        assertEquals(3L, v.get("successful_runs"));
        assertEquals(1.2, v.get("hours"));   // 2 x 30 + 10 minutes
        assertEquals(700L, v.get("money"));  // 70 minutes at 600 an hour
        List<Map<String, Object>> agents = (List<Map<String, Object>>) v.get("agents");
        assertEquals("Finance/Pay.flow.json", agents.get(0).get("bot_path"));
        assertEquals(true, agents.get(0).get("own_value"));
        assertEquals(false, agents.get(1).get("own_value"));   // Backup uses the default
        assertEquals(List.of("Finance", "Ops"), ((List<Map<String, Object>>) v.get("folders")).stream().map(x -> x.get("folder")).toList());
        assertEquals(0.0, v.get("previous_hours"));
        // carl may not see Finance: only Backup's 10 minutes count for him
        assertEquals(0.2, cr.analytics.value(carl, Map.of("days", "1")).get("hours"));
        cr.platform.updateSettings(admin, Map.of("default_minutes_saved", 0, "hourly_cost", 0, "currency", ""), null);
    }
}
