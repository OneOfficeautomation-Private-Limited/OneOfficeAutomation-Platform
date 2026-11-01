package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Administration > Policies (item 5, 1 Nov 2026): named policies given to folders. A folder uses its own code analysis
 * policy, or the nearest folder's above it, else the default; one policy of each kind per folder.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoliciesTest {
    Db db; ControlRoom cr; ControlRoom.User admin, jane;

    static String code(Runnable r) {
        try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); }
    }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-policies");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
    }

    @AfterAll void stop() { db.close(); }

    @Test @SuppressWarnings("unchecked")
    void folderPoliciesDecideCodeAnalysis() {
        List<Map<String, Object>> list = cr.policies.list(jane);
        assertEquals("Default code analysis", list.get(0).get("name"), "the default policy is always there, first");
        assertEquals(Boolean.TRUE, list.get(0).get("built_in"));

        // Finance forbids core/set and refuses check-ins with errors; everywhere else keeps the default (which does not refuse)
        Map<String, Object> strict = cr.policies.save(admin, null, Map.of("name", "Finance: strict", "kind", "code", "folders", List.of("Finance"),
                "rules", Map.of("enforce_at_checkin", true, "rules", Map.of("forbidden_actions", Map.of("enabled", true, "severity", "error", "params", Map.of("actions", List.of("core/set")))))), null);
        assertEquals(List.of("Finance"), strict.get("folders"));
        assertEquals("CR.POLICY_VIOLATION", code(() -> cr.checkin(admin, "Finance/Invoices/Pay.flow.json", RepositoryLifecycleTest.flow("pay"), "v1", true, null)),
                "a sub-folder uses the nearest folder's policy");
        assertEquals("ok", code(() -> cr.checkin(admin, "Sales/Report.flow.json", RepositoryLifecycleTest.flow("report"), "v1", true, null)));
        assertEquals("Finance: strict", cr.codeAnalysis.policyFor("Finance/Invoices/Pay.flow.json").get("name"));
        assertEquals("Default code analysis", cr.codeAnalysis.policyFor("Sales/Report.flow.json").get("name"));

        // one policy of a kind per folder; names are unique; only settings.manage changes policies; the default stays
        assertEquals("CR.CONFLICT", code(() -> cr.policies.save(admin, null, Map.of("name", "Another", "kind", "code", "folders", List.of("Finance")), null)));
        assertEquals("CR.CONFLICT", code(() -> cr.policies.save(admin, null, Map.of("name", "finance: STRICT", "kind", "code"), null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.policies.save(jane, null, Map.of("name", "Mine", "kind", "code"), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.policies.delete(admin, Policies.DEFAULT_ID, null)));
        // an AI guardrails policy may cover the same folder as a code analysis one
        assertEquals("ok", code(() -> cr.policies.save(admin, null, Map.of("name", "Finance AI", "kind", "ai", "folders", List.of("Finance")), null)));
        // AI guardrails: personal data masked unless switched off, only known model connections, the content check off, log or block;
        // runs of agents in Finance (and below) get them, others get none
        Map<String, Object> g = cr.policies.guardrailsFor("Finance/Invoices/Pay.flow.json");
        assertEquals("Finance AI", g.get("name"));
        assertEquals(Boolean.TRUE, g.get("mask"));
        assertEquals("off", g.get("content"));
        assertNull(cr.policies.guardrailsFor("Sales/Report.flow.json"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.policies.save(admin, null, Map.of("name", "Bad models", "kind", "ai", "rules", Map.of("models", List.of("nope"))), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.policies.save(admin, null, Map.of("name", "Bad check", "kind", "ai", "rules", Map.of("content", "loud")), null)));

        // taking the folder out lets the check-in through; deleting the policy is recorded
        cr.policies.save(admin, (String) strict.get("id"), Map.of("name", "Finance: strict", "folders", List.of(), "rules", strict.get("rules")), null);
        assertEquals("ok", code(() -> cr.checkin(admin, "Finance/Invoices/Pay.flow.json", RepositoryLifecycleTest.flow("pay"), "v1", true, null)));
        cr.policies.delete(admin, (String) strict.get("id"), null);
        assertEquals(2, cr.policies.list(admin).size());
        assertFalse(db.query("SELECT seq FROM audit WHERE action = 'policy.delete'").isEmpty(), "recorded in the Audit Trail");
    }
}
