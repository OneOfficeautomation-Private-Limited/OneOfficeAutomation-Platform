package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A customer install without a license: the 30-day evaluation with its automatic seats, its end, and a license replacing it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvaluationTest {
    Db db; ControlRoom cr; ControlRoom.User admin, a, b, c;
    String pc1, pc2, pc3;
    java.security.PrivateKey priv;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> agent(String id) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],\"steps\":[]}");
    }

    void today(LocalDate d) { cr.licensing.clock = Clock.fixed(d.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC); }

    String runner(String name) { return (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), name, name, "Windows 11", "0.2.0", null).get("device_id"); }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-evaluation");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        cr.licensing.vendorOverride = false;   // behave as the customer distribution
        cr.licensing.serverDatabaseOverride = false;   // on the built-in evaluation database (also when the tests run on SQL Server)
        today(LocalDate.of(2026, 11, 1));
        admin = RepositoryLifecycleTest.boot(cr);
        a = RepositoryLifecycleTest.person(cr, admin, "amy", "bot_creator");
        b = RepositoryLifecycleTest.person(cr, admin, "ben", "bot_creator");
        c = RepositoryLifecycleTest.person(cr, admin, "cid", "bot_creator");
        pc1 = runner("PC-1"); pc2 = runner("PC-2"); pc3 = runner("PC-3");
        Map<String, String> kp = License.newKeyPair();
        priv = License.privateKey(kp.get("private"));
        cr.licensing.useKeyForTests(License.publicKey(kp.get("public")));
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @Order(1)
    void aFreshCustomerInstallIsAThirtyDayEvaluation() {
        assertEquals("evaluation", cr.licensing.state());
        Map<String, Object> st = cr.licensing.status(admin);
        assertEquals(30L, st.get("days_left"));
        assertEquals("warning", ((Map<?, ?>) st.get("banner")).get("level"));
        assertEquals(2, ((Map<?, ?>) ((Map<?, ?>) st.get("seats")).get("developer")).get("total"));
        assertFalse(cr.platform.settings().containsKey("evaluation_started"), "the evaluation start is not a visible setting");
    }

    @Test @Order(2)
    void seatsAreGivenOutOnFirstUseUpToTheEvaluationLimits() {
        cr.checkin(a, "E/A.flow.json", agent("ea"), "v1", true, null);
        cr.checkin(b, "E/B.flow.json", agent("eb"), "v1", true, null);
        assertEquals("CR.LICENSE_LIMIT", code(() -> cr.checkin(c, "E/C.flow.json", agent("ec"), "v1", true, null)), "a third developer needs a license");
        assertNotNull(cr.startRun("admin", "admin", "E/A.flow.json", pc1, Map.of(), "api", null).get("run_id"));
        assertNotNull(cr.startRun("admin", "admin", "E/A.flow.json", pc2, Map.of(), "schedule", null).get("run_id"));
        assertEquals("CR.LICENSE_REQUIRED", code(() -> cr.startRun("admin", "admin", "E/A.flow.json", pc3, Map.of(), "api", null)), "a third runner needs a license");
        Map<?, ?> seats = (Map<?, ?>) cr.licensing.status(admin).get("seats");
        assertEquals(2L, ((Map<?, ?>) seats.get("developer")).get("used"));
        assertEquals(2L, ((Map<?, ?>) seats.get("unattended_runner")).get("used"));
        // a seat can be moved by hand within the limits
        cr.licensing.assign(admin, "developer", "amy", false, null);
        cr.licensing.assign(admin, "developer", "cid", true, null);
        assertEquals("CR.LICENSE_LIMIT", code(() -> cr.licensing.assign(admin, "developer", "amy", true, null)));
    }

    @Test @Order(3)
    void theEvaluationEndsAfterThirtyDays() {
        today(LocalDate.of(2026, 11, 25));
        assertEquals("critical", ((Map<?, ?>) cr.licensing.status(admin).get("banner")).get("level"), "the last week is critical");
        today(LocalDate.of(2026, 12, 1));
        assertEquals("evaluation_ended", cr.licensing.state());
        assertEquals("CR.LICENSE_EXPIRED", code(() -> cr.licensing.checkSignIn(b)));
        cr.licensing.checkSignIn(admin);
        assertEquals("CR.LICENSE_EXPIRED", code(() -> cr.startRun("admin", "admin", "E/A.flow.json", pc1, Map.of(), "api", null)));
        assertTrue(String.valueOf(cr.licensing.status(b).get("banner")).contains("evaluation has ended"), "everyone is told why");
    }

    @Test @Order(4)
    void aLicenseReplacesTheEvaluation() {
        Map<String, Integer> seats = new LinkedHashMap<>(Map.of("developer", 5, "citizen_developer", 0, "attended_runner", 0, "unattended_runner", 5));
        String file = new License("LIC-E", "Customer", "Enterprise", LocalDate.of(2026, 12, 1), LocalDate.of(2027, 12, 31), 14, seats, "").sign(priv);
        // a customer install licenses only on a server database: the evaluation's built-in database is refused
        assertEquals(Boolean.TRUE, cr.licensing.status(admin).get("requires_server_database"));
        assertEquals("LICENSE.DB_REQUIRED", code(() -> cr.licensing.install(admin, file, null)));
        cr.licensing.serverDatabaseOverride = true;   // as after "ooa db init" on SQL Server
        assertEquals("active", cr.licensing.install(admin, file, null).get("state"));
        assertNull(cr.licensing.status(admin).get("requires_server_database"));
        // a license that ends up on the built-in database anyway stops the work and says why
        cr.licensing.serverDatabaseOverride = false;
        assertEquals("database_required", cr.licensing.state());
        assertEquals("CR.LICENSE_EXPIRED", code(() -> cr.startRun("admin", "admin", "E/A.flow.json", pc1, Map.of(), "api", null)));
        assertTrue(String.valueOf(cr.licensing.status(admin).get("banner")).contains("SQL Server"));
        cr.licensing.serverDatabaseOverride = true;
        cr.licensing.checkSignIn(b);
        assertEquals("CR.LICENSE_REQUIRED", code(() -> cr.startRun("admin", "admin", "E/A.flow.json", pc3, Map.of(), "api", null)), "a licensed install assigns seats by hand");
        cr.licensing.assign(admin, "unattended_runner", pc3, true, null);
        assertNotNull(cr.startRun("admin", "admin", "E/A.flow.json", pc3, Map.of(), "api", null).get("run_id"));
    }

    @Test @Order(5)
    void anEditedEvaluationStartEndsTheEvaluation() {
        db.update("DELETE FROM licenses");
        cr.licensing.useKeyForTests(License.publicKey(License.newKeyPair().get("public")));
        today(LocalDate.of(2026, 11, 2));
        assertEquals("evaluation", cr.licensing.state());
        db.update("UPDATE settings SET json_value = ? WHERE name = 'evaluation_started'", JsonLite.write("v1:AAAA:BBBB"));
        assertEquals("evaluation_ended", cr.licensing.state());
        // the vendor build is never limited without a license
        cr.licensing.vendorOverride = true;
        assertEquals("unlicensed", cr.licensing.state());
    }
}
