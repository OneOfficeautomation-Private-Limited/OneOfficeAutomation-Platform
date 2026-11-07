package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.security.PrivateKey;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G45: feature tiers in the licence, a restricted Citizen Developer seat, and Non-production and Testing runner seats.
 * A licence without a features list keeps every feature, as all licences issued before.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LicenceTiersTest {
    Db db; ControlRoom cr; ControlRoom.User admin, dana, cleo; String dev1, dev2; PrivateKey priv;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @SuppressWarnings("unchecked")
    static Map<String, Object> agent(String id, String action) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],\"steps\":["
                + (action == null ? "" : "{\"id\":\"s1\",\"action\":\"" + action + "\",\"args\":" + (action.equals("core/log") ? "{\"message\":\"hi\"}" : "{\"script\":\"Write-Output 1\"}") + "}") + "]}");
    }

    void install(List<String> features, String edition) {
        Map<String, Integer> seats = new LinkedHashMap<>(Map.of("developer", 1, "citizen_developer", 1, "attended_runner", 1, "unattended_runner", 0,
                "nonproduction_runner", 1, "testing_runner", 1));
        cr.licensing.install(admin, new License("LIC-G45-" + edition, "Test", edition, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), 14, seats, "", "", features).sign(priv), null);
    }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-g45");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        dana = RepositoryLifecycleTest.person(cr, admin, "dana", "bot_creator");
        cleo = RepositoryLifecycleTest.person(cr, admin, "cleo", "bot_creator");
        dev1 = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "NP-1", "np-1", "Windows 11", "0.2.0", null).get("device_id");
        dev2 = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "TEST-1", "test-1", "Windows 11", "0.2.0", null).get("device_id");
        Map<String, String> kp = License.newKeyPair();
        priv = License.privateKey(kp.get("private"));
        cr.licensing.useKeyForTests(License.publicKey(kp.get("public")));
        cr.licensing.clock = Clock.fixed(LocalDate.of(2026, 11, 6).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @Order(1)
    void theTermsKeepFeaturesAndOldLicencesHaveThemAll() {
        License all = new License("A", "C", "Enterprise", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 14, Map.of(), "");
        assertTrue(all.features().isEmpty());
        assertFalse(all.terms().containsKey("features"), "an old-style licence's signed terms are unchanged");
        License some = License.fromTerms(new LinkedHashMap<>(Map.of("id", "B", "customer", "C", "issued", "2026-01-01", "expires", "2027-01-01",
                "features", List.of("workflows", "made_up", "none"))));
        assertEquals(List.of("workflows", "none"), some.features(), "unknown names are dropped; none marks an edition with no extras");
        assertEquals(0, some.seats().get("testing_runner"));
    }

    @Test @Order(2)
    void featuresSeatsAndCitizenLimits() {
        install(List.of("none"), "Community");
        assertEquals("CR.LICENSE_FEATURE", code(() -> cr.licensing.requireFeature("workflows", "Workflows")), "Community has no workflows");
        assertEquals(List.of(), cr.licensing.status(admin).get("features"));
        install(List.of("workflows"), "Standard");
        assertTrue(cr.licensing.feature("workflows"));
        assertFalse(cr.licensing.feature("git_sync"));

        // a Citizen Developer checks in simple agents, not scripts or code; a Developer may
        cr.licensing.assign(admin, "citizen_developer", "cleo", true, null);
        cr.licensing.assign(admin, "developer", "dana", true, null);
        assertEquals("ok", code(() -> cr.checkin(cleo, "C/Simple.flow.json", agent("simple", "core/log"), "v1", true, null)));
        assertEquals("CR.LICENSE_CITIZEN", code(() -> cr.checkin(cleo, "C/Script.flow.json", agent("script", "ooa.script/run_powershell"), "v1", true, null)));
        assertEquals("ok", code(() -> cr.checkin(dana, "C/Script.flow.json", agent("script", "ooa.script/run_powershell"), "v1", true, null)));

        // runner seats: non-production takes runs by hand, from Studio and test sets; testing takes test sets only
        cr.licensing.assign(admin, "nonproduction_runner", dev1, true, null);
        cr.licensing.assign(admin, "testing_runner", dev2, true, null);
        assertNull(cr.licensing.runProblem(dev1, "dana", "manual"));
        assertNull(cr.licensing.runProblem(dev1, "dana", "test:tr_1"));
        assertTrue(cr.licensing.runProblem(dev1, "dana", "schedule").contains("Non-production Runner"));
        assertNull(cr.licensing.runProblem(dev2, "dana", "test:tr_1"));
        assertTrue(cr.licensing.runProblem(dev2, "dana", "manual").contains("test sets only"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> seats = (Map<String, Map<String, Object>>) cr.licensing.status(admin).get("seats");
        assertEquals(1, ((Number) seats.get("testing_runner").get("used")).intValue());
        assertEquals(1, ((Number) seats.get("nonproduction_runner").get("total")).intValue());
    }
}
