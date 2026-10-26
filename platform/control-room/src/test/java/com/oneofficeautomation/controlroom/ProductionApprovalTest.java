package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Approval before production: requests instead of live versions, a second person decides, never their own request. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionApprovalTest {
    Db db; ControlRoom cr; ControlRoom.User admin, dana, otherAdmin;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-approval");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        dana = RepositoryLifecycleTest.person(cr, admin, "dana", "bot_creator");
        otherAdmin = RepositoryLifecycleTest.person(cr, admin, "olga", "admin");
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    int live(String path) { Object v = db.one("SELECT production_version FROM bots WHERE path = ?", path).get("production_version"); return v == null ? 0 : ((Number) v).intValue(); }
    static String code(Runnable r) { return assertThrows(OoaException.class, r::run).code(); }

    @Test void withApprovalOnVersionsGoLiveOnlyWhenSomeoneElseApproves() {
        String path = "Approval/Pay.flow.json";
        cr.checkin(dana, path, LicensingAndChecksTest.agent("pay"), "v1", true, null);
        assertEquals(1, live(path), "approval off: check-in as production goes live at once");

        cr.platform.updateSettings(admin, Map.of("production_approval", 1), null);
        try {
            cr.checkout(dana, path, null);
            Map<String, Object> v2 = cr.checkin(dana, path, LicensingAndChecksTest.agent("pay"), "v2: new bank format", true, null);
            assertEquals(2, v2.get("version"));
            assertEquals(1, live(path), "v2 waits for approval");
            @SuppressWarnings("unchecked") Map<String, Object> req = (Map<String, Object>) v2.get("production_request");
            assertEquals("pending", req.get("status"));
            assertEquals("dana", req.get("requested_by"));

            // nobody sets production directly, and the requester cannot decide
            assertEquals("CR.APPROVAL_REQUIRED", code(() -> cr.setProduction(admin, path, 2, null)));
            assertEquals("CR.FORBIDDEN", code(() -> cr.approvals.decide(dana, (String) req.get("id"), true, null, null)), "dana has no approval right");
            Map<String, Object> mine = cr.setOrRequestProduction(admin, path, 2, "admin asks too", null);
            @SuppressWarnings("unchecked") String adminReq = (String) ((Map<String, Object>) mine.get("production_request")).get("id");
            assertEquals("replaced", cr.approvals.get((String) req.get("id")).get("status"), "a newer request replaces the older one");
            assertEquals("CR.FORBIDDEN", code(() -> cr.approvals.decide(admin, adminReq, true, null, null)), "never your own request");

            // another administrator approves: v2 is live
            List<Map<String, Object>> pending = cr.approvals.list(otherAdmin, "pending");
            assertEquals(1, pending.size());
            assertEquals(true, pending.get(0).get("can_decide"));
            Map<String, Object> done = cr.approvals.decide(otherAdmin, adminReq, true, "checked the mapping", null);
            assertEquals("approved", done.get("status"));
            assertEquals("olga", done.get("decided_by"));
            assertEquals(2, live(path));
            assertEquals("CR.CONFLICT", code(() -> cr.approvals.decide(otherAdmin, adminReq, false, null, null)), "decided once");

            // rejected and withdrawn requests change nothing
            cr.checkout(dana, path, null);
            Map<String, Object> v3 = cr.checkin(dana, path, LicensingAndChecksTest.agent("pay"), "v3", true, null);
            @SuppressWarnings("unchecked") String r3 = (String) ((Map<String, Object>) v3.get("production_request")).get("id");
            assertEquals("rejected", cr.approvals.decide(otherAdmin, r3, false, "missing tests", null).get("status"));
            assertEquals(2, live(path));
            String r3b = (String) cr.approvals.request(dana, path, 3, "fixed", null).get("id");
            assertEquals("withdrawn", cr.approvals.withdraw(dana, r3b, null).get("status"));
            assertEquals(2, live(path));
            assertEquals("CR.CONFLICT", code(() -> cr.approvals.request(dana, path, 2, null, null)), "already live");

            // every step is in the audit trail
            List<Object> actions = new ArrayList<>();
            for (Map<String, Object> a : db.query("SELECT action FROM audit WHERE object_id = ? ORDER BY seq", path)) actions.add(a.get("action"));
            assertTrue(actions.containsAll(List.of("repo.production_request", "repo.production_approve", "repo.production_reject", "repo.production_withdraw")), actions.toString());
        } finally {
            cr.platform.updateSettings(admin, Map.of("production_approval", 0), null);
        }
    }

    @Test void approvingNeedsTheApprovalRight() {
        assertTrue(Rbac.ADMIN_ONLY.contains("repo.approve"));
        assertTrue(Rbac.permissions("admin").contains("repo.approve"));
        assertFalse(Rbac.permissions("bot_creator").contains("repo.approve"));
    }
}
