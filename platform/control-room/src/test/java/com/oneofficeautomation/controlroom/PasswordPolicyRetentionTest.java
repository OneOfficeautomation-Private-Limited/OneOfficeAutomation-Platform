package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Password history and expiry (S9); retention of AI call records and finished work items (D2). */
class PasswordPolicyRetentionTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeEach void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-pwpolicy");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }
    @AfterEach void stop() { cr.triggerPollers.stop(); db.close(); }

    String code(Runnable r) { return assertThrows(OoaException.class, r::run).code(); }

    @Test void recentPasswordsCannotComeBack() {
        cr.platform.updateSettings(admin, Map.of("password_history", 3), null);
        String t = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        ControlRoom.User u = cr.session(t);
        cr.changePassword(u, "Admin-Passw0rd-2026", "Second-Passw0rd-2026", t, null);
        cr.changePassword(u, "Second-Passw0rd-2026", "Third-Passw0rd-2026", t, null);
        assertEquals("CR.WEAK_PASSWORD", code(() -> cr.changePassword(u, "Third-Passw0rd-2026", "Admin-Passw0rd-2026", t, null)), "used two changes ago");
        assertEquals("CR.WEAK_PASSWORD", code(() -> cr.changePassword(u, "Third-Passw0rd-2026", "Second-Passw0rd-2026", t, null)));
        cr.changePassword(u, "Third-Passw0rd-2026", "Fourth-Passw0rd-2026", t, null);
        cr.changePassword(u, "Fourth-Passw0rd-2026", "Fifth-Passw0rd-2026", t, null);
        cr.changePassword(u, "Fifth-Passw0rd-2026", "Admin-Passw0rd-2026", t, null);   // four changes ago: allowed with history 3
        assertTrue(((String) db.one("SELECT password_history FROM users WHERE username = 'admin'").get("password_history")).split(",").length <= 3, "only the last 3 are kept");
    }

    @Test void anOldPasswordMustBeChangedAtSignIn() {
        Map<String, Object> first = cr.login("admin", "Admin-Passw0rd-2026", null);
        assertEquals(false, ((Map<?, ?>) first.get("user")).get("must_change"), "no expiry by default");
        cr.platform.updateSettings(admin, Map.of("password_max_days", 90), null);
        db.update("UPDATE users SET password_changed = ? WHERE username = 'admin'", Instant.now().minus(Duration.ofDays(120)));
        Map<String, Object> r = cr.login("admin", "Admin-Passw0rd-2026", null);
        assertEquals(true, ((Map<?, ?>) r.get("user")).get("must_change"), "older than 90 days: change it first");
        assertNotNull(db.one("SELECT seq FROM audit WHERE action = 'user.password_expired'"));
        String t = (String) r.get("token");
        cr.changePassword(cr.session(t), "Admin-Passw0rd-2026", "Renewed-Passw0rd-2026", t, null);
        assertEquals(false, ((Map<?, ?>) cr.login("admin", "Renewed-Passw0rd-2026", null).get("user")).get("must_change"));
    }

    @Test void oldAiCallsAndFinishedWorkItemsGo() {
        Instant old = Instant.now().minus(Duration.ofDays(60)), recent = Instant.now().minus(Duration.ofDays(5));
        db.update("INSERT INTO ai_calls (id, ts, purpose) VALUES (?,?,?)", "ai_old", old, "test");
        db.update("INSERT INTO ai_calls (id, ts, purpose) VALUES (?,?,?)", "ai_new", recent, "test");
        db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated, finished) VALUES (?,?,?,?,?,?,?,?,?)", "qi_old", "q1", "{}", "success", 0, 1, old, old, old);
        db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated, finished) VALUES (?,?,?,?,?,?,?,?,?)", "qi_open", "q1", "{}", "new", 0, 0, old, old, null);
        cr.platform.updateSettings(admin, Map.of("ai_call_retention_days", 30, "work_item_retention_days", 30), null);
        cr.platform.purgeIfDue();
        assertNull(db.one("SELECT id FROM ai_calls WHERE id = 'ai_old'"));
        assertNotNull(db.one("SELECT id FROM ai_calls WHERE id = 'ai_new'"));
        assertNull(db.one("SELECT id FROM queue_items WHERE id = 'qi_old'"));
        assertNotNull(db.one("SELECT id FROM queue_items WHERE id = 'qi_open'"), "open items are never removed");
    }
}
