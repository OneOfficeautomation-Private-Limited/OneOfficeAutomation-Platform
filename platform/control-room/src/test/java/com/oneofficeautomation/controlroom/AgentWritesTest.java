package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gap G30: a running agent changes a shared value and saves a credential, checked as the run's user; the administrator's
 * switch for credentials; the Audit Trail names the runner, run, agent and user and never holds a value; expiry dates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentWritesTest {
    Path data; Db db; ControlRoom cr;
    ControlRoom.User admin, bob, carl;
    Map<String, Object> dev;
    String runBob, runCarl;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-g30");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        bob = RepositoryLifecycleTest.person(cr, admin, "bob", "bot_runner");
        carl = RepositoryLifecycleTest.person(cr, admin, "carl", "admin");
        cr.checkin(admin, "Ops/Reset.flow.json", TeamsVaultAuditAnalyticsTest.flow("reset"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        dev = cr.device((String) d.get("device_id"), (String) d.get("secret"));
        cr.setDefaultDevice(admin, "bob", (String) d.get("device_id"), null);
        cr.setDefaultDevice(admin, "carl", (String) d.get("device_id"), null);
        runBob = (String) cr.startRun("admin", "admin", "Ops/Reset.flow.json", null, null, Map.of(), "manual", null, 0, null, "bob", null).get("run_id");
        cr.claimNext(dev);
        runCarl = (String) cr.startRun("admin", "admin", "Ops/Reset.flow.json", null, null, Map.of(), "manual", null, 0, null, "carl", null).get("run_id");
        cr.claimNext(dev);
        assertEquals("running", db.one("SELECT status FROM runs WHERE id = ?", runCarl).get("status"));
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> audit(String action) {
        return (List<Map<String, Object>>) cr.auditSearch(admin, Map.of("action", action, "limit", "1000", "sort", "oldest")).get("records");
    }

    @Test @Order(1)
    void sharedValuesFollowThePermission() {
        cr.setGlobal(admin, "threshold", 1000, null, "number", null);
        // bob (bot_runner) may not manage shared values: refused and recorded
        assertEquals("GLOBALS.DENIED", code(() -> cr.agentWrites.setGlobal(dev, runBob, "threshold", 5)));
        // carl (admin) may; the type is checked, unknown names are refused
        assertEquals(Map.of("value", 2500, "type", "number"), cr.agentWrites.setGlobal(dev, runCarl, "threshold", 2500));
        assertEquals("2500", db.one("SELECT json_value FROM global_values WHERE name = 'threshold'").get("json_value"));
        assertEquals("carl", db.one("SELECT updated_by FROM global_values WHERE name = 'threshold'").get("updated_by"));
        assertEquals("GLOBALS.WRONG_TYPE", code(() -> cr.agentWrites.setGlobal(dev, runCarl, "threshold", "lots")));
        assertEquals("GLOBALS.NOT_FOUND", code(() -> cr.agentWrites.setGlobal(dev, runCarl, "no_such", 1)));
        List<Map<String, Object>> a = audit("global.set").stream().filter(r -> String.valueOf(r.get("actor")).startsWith("agent:")).toList();
        assertEquals(List.of("denied", "ok", "failed", "not_found"), a.stream().map(r -> r.get("result")).toList());
        String ok = JsonLite.write(a.get(1));
        assertTrue(ok.contains("agent:PC-1 for carl") && ok.contains(runCarl) && ok.contains("Ops/Reset.flow.json"), ok);
        assertFalse(ok.contains("2500"), "the value is not in the Audit Trail");
    }

    @Test @Order(2)
    void credentialsNeedTheSwitchAndThePermission() {
        cr.createLocker(admin, "erp", "ERP", "user:bob,user:carl", null);
        cr.setCredential(admin, "erp", "sap", "password", "old-secret", null);
        // off by default
        assertEquals(0, cr.platform.intSetting("agents_write_credentials"));
        assertEquals("VAULT.WRITE_OFF", code(() -> cr.agentWrites.setCredential(dev, runCarl, "erp/sap/password", "new-Secret-1", null)));
        cr.platform.updateSettings(admin, Map.of("agents_write_credentials", 1), null);
        // bob only consumes the folder: he may read but not change it
        assertEquals("VAULT.DENIED", code(() -> cr.agentWrites.setCredential(dev, runBob, "erp/sap/password", "new-Secret-1", null)));
        // carl administers the vault (lockers.manage): allowed, with a new expiry date
        String in90 = LocalDate.now().plusDays(90).toString();
        Map<String, Object> r = cr.agentWrites.setCredential(dev, runCarl, "erp/sap/password", "new-Secret-1", in90);
        assertEquals(in90, r.get("expires"));
        assertEquals(90L, ((Number) r.get("days_left")).longValue());
        assertEquals("new-Secret-1", cr.credentialForRun(dev, runCarl, "erp/sap/password"));
        assertEquals("VAULT.BAD_DATE", code(() -> cr.agentWrites.setCredential(dev, runCarl, "erp/sap/password", "x", "31/12/2027")));
        assertEquals("VAULT.BAD_REF", code(() -> cr.agentWrites.setCredential(dev, runCarl, "erp/sap", "x", null)));
        assertEquals("VAULT.EMPTY", code(() -> cr.agentWrites.setCredential(dev, runCarl, "erp/sap/password", "", null)));
        // a finished run may change nothing
        db.update("UPDATE runs SET status = 'success' WHERE id = ?", runBob);
        assertEquals("VAULT.DENIED", code(() -> cr.agentWrites.setGlobal(dev, runBob, "threshold", 1)));
        db.update("UPDATE runs SET status = 'running' WHERE id = ?", runBob);
        // the Audit Trail: who, which run, never a value
        List<Map<String, Object>> a = audit("credential.set").stream().filter(x -> String.valueOf(x.get("actor")).startsWith("agent:")).toList();
        assertEquals(List.of("denied", "denied", "ok"), a.stream().map(x -> x.get("result")).toList().subList(0, 3));
        String all = JsonLite.write(cr.auditSearch(admin, Map.of("limit", "1000")));
        assertFalse(all.contains("new-Secret-1"), "no secret in the Audit Trail");
        assertTrue(JsonLite.write(a.get(2)).contains(runCarl) && JsonLite.write(a.get(2)).contains("by_agent"));
        // and off again: refused
        cr.platform.updateSettings(admin, Map.of("agents_write_credentials", 0), null);
        assertEquals("VAULT.WRITE_OFF", code(() -> cr.agentWrites.setCredential(dev, runCarl, "erp/sap/password", "new-Secret-2", null)));
        assertEquals("new-Secret-1", cr.credentialForRun(dev, runCarl, "erp/sap/password"));
    }

    @Test @Order(3)
    @SuppressWarnings("unchecked")
    void expiryIsStoredAndReported() {
        // a consumer (bob) checks the expiry; the console shows it and people set it in Details
        Map<String, Object> e = cr.agentWrites.credentialExpiry(dev, runBob, "erp/sap/password");
        assertEquals(Boolean.TRUE, e.get("has_expiry"));
        assertEquals(Boolean.FALSE, e.get("expired"));
        cr.vaultAdmin.saveCredential(admin, "erp", "sap", Map.of("expires", LocalDate.now().minusDays(1).toString()), null);
        e = cr.agentWrites.credentialExpiry(dev, runBob, "erp/sap");
        assertEquals(Boolean.TRUE, e.get("expired"));
        assertEquals(-1L, ((Number) e.get("days_left")).longValue());
        assertEquals(Boolean.TRUE, cr.vaultAdmin.credential(admin, "erp", "sap").get("expired"));
        cr.vaultAdmin.saveCredential(admin, "erp", "sap", Map.of("expires", LocalDate.now().plusDays(3).toString()), null);
        Map<String, Object> info = ((List<Map<String, Object>>) cr.lockers(admin).stream().filter(l -> l.get("name").equals("erp")).findFirst().orElseThrow().get("credential_info")).get(0);
        assertEquals(Boolean.TRUE, info.get("expiring_soon"));
        // empty clears it; a bad date is refused
        cr.vaultAdmin.saveCredential(admin, "erp", "sap", Map.of("expires", ""), null);
        assertEquals(Boolean.FALSE, cr.agentWrites.credentialExpiry(dev, runBob, "erp/sap").get("has_expiry"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.vaultAdmin.saveCredential(admin, "erp", "sap", Map.of("expires", "soon"), null)));
        // someone with no place in the folder may not even read the expiry
        cr.updateLocker(admin, "erp", "user:carl", null, null);
        assertEquals("VAULT.DENIED", code(() -> cr.agentWrites.credentialExpiry(dev, runBob, "erp/sap")));
        assertEquals("VAULT.NOT_FOUND", code(() -> cr.agentWrites.credentialExpiry(dev, runCarl, "erp/nothing")));
    }

    @Test @Order(4)
    void userProvidedAttributesStoreTheRunUsersOwnValue() {
        cr.platform.updateSettings(admin, Map.of("agents_write_credentials", 1), null);
        cr.vaultAdmin.saveCredential(admin, "erp", "own", Map.of("user_attributes", List.of("password")), null);
        cr.updateLocker(admin, "erp", "user:carl,user:bob", null, null);
        // bob is a consumer of the folder: he stores his own value, as My value in the console
        Map<String, Object> r = cr.agentWrites.setCredential(dev, runBob, "erp/own/password", "bobs-new", null);
        assertEquals(Boolean.TRUE, r.get("own_value"));
        assertEquals("bobs-new", cr.credentialForRun(dev, runBob, "erp/own/password"));
        assertEquals("VAULT.NOT_FOUND", code(() -> cr.credentialForRun(dev, runCarl, "erp/own/password")), "carl's own value is untouched");
    }
}
