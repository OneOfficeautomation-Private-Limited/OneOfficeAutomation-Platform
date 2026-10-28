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
        // a folder for the team only: carl (not in it) no longer sees Finance
        cr.folders.setPermissions(admin, "Finance", List.of(Map.of("principal", "team:finance", "access", "run")), null);
        assertEquals("run", cr.folders.access("bob", "bot_runner", "Finance/Pay.flow.json"));
        assertEquals("none", cr.folders.access("carl", "bot_creator", "Finance/Pay.flow.json"));
        assertTrue(cr.repoList(carl, null).stream().noneMatch(b -> String.valueOf(b.get("path")).startsWith("Finance/")));
        // a team still named in a rule is not deleted; people see the teams they are in
        assertEquals("CR.IN_USE", code(() -> cr.teams.delete(admin, teamId, null)));
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
}
