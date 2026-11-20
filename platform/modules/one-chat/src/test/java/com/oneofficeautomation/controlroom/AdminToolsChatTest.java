package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.chat.Chat;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Administration in One Chat (S3, 20 Nov 2026), against a whole One CommandCenter, in rules-only mode: the tools are off
 * until an administrator switches their group on; each is offered only to people with its console permission; a
 * look-up answers at once with a table; a change waits for the person's yes and is done by the kernel (with its own
 * audit entry); deleting a person waits for someone else's approval and then happens as the person who asked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminToolsChatTest {
    Db db; ControlRoom cr; Chat chat; ControlRoom.User admin, lee, jane;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-admintools");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        lee = RepositoryLifecycleTest.person(cr, admin, "lee", "admin");
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_runner");
        for (String n : List.of("sam", "tia")) RepositoryLifecycleTest.person(cr, admin, n, "bot_runner");
        for (String pc : List.of("PC-7", "PC-8"))
            cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), pc, pc.toLowerCase(Locale.ROOT), "Windows 11", "0.2.0", null);
        chat = new Chat(cr.modules.contextFor("one-chat"), data, Clock.fixed(Instant.parse("2026-11-20T05:30:00Z"), ZoneId.of("Asia/Kolkata")));
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @AfterEach void off() {
        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_read", "never", "chat_tool_admin_change", "never", "chat_tool_admin_high", "never"), null);
    }

    @Test void administrationIsOffUntilSwitchedOnAndFollowsTheConsolePermissions() {
        assertEquals("not_understood", chat.message(admin, null, "who is disabled", null).get("kind"), "off by default");
        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_read", "always"), null);
        Map<String, Object> r = chat.message(admin, null, "who is disabled", null);
        assertEquals("answer", r.get("kind"), String.valueOf(r));
        assertEquals("Nobody is disabled.", r.get("reply"));
        assertEquals("#/users", r.get("page"));
        assertNotEquals("answer", chat.message(jane, null, "who is disabled", null).get("kind"), "jane may not manage users, so People is not offered");
        Map<String, Object> runners = chat.message(jane, null, "which runners are offline", null);
        assertEquals("answer", runners.get("kind"), "jane may view runners: " + runners);
        assertTrue(String.valueOf(runners.get("reply")).startsWith("2 runners:"), String.valueOf(runners));
        assertEquals(List.of("name", "status", "running", "hostname"), runners.get("columns"));
    }

    @Test void aChangeWaitsForYesAndIsDoneByTheKernel() {
        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_change", "always"), null);
        Map<String, Object> c = chat.message(admin, null, "disable sam", null);
        assertEquals("confirm", c.get("kind"), String.valueOf(c));
        assertEquals(Boolean.TRUE, db.one("SELECT enabled FROM users WHERE username = ?", "sam").get("enabled"), "nothing changes before the yes");
        Map<String, Object> done = chat.message(admin, (String) c.get("session_id"), "yes", null);
        assertEquals("done", done.get("kind"), String.valueOf(done));
        assertEquals(Boolean.FALSE, db.one("SELECT enabled FROM users WHERE username = ?", "sam").get("enabled"));
        assertNotNull(db.one("SELECT seq FROM audit WHERE action = ? AND actor = ?", "chat.tool", "admin"), "One Chat's own entry");
        // and back again, and a runner switched off and on
        Map<String, Object> e = chat.message(admin, null, "enable sam", null);
        chat.message(admin, (String) e.get("session_id"), "yes", null);
        assertEquals(Boolean.TRUE, db.one("SELECT enabled FROM users WHERE username = ?", "sam").get("enabled"));
        Map<String, Object> off = chat.message(admin, null, "switch off runner PC-8", null);
        assertEquals("confirm", off.get("kind"), String.valueOf(off));
        assertEquals("done", chat.message(admin, (String) off.get("session_id"), "yes", null).get("kind"));
        assertEquals(Boolean.FALSE, db.one("SELECT enabled FROM devices WHERE name = ?", "PC-8").get("enabled"));
        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_change", "never"), null);
        assertEquals("not_understood", chat.message(admin, null, "disable tia", null).get("kind"), "Never: not offered");
    }

    @Test void deletingAPersonHappensOnlyAfterSomeoneElseApproves() {
        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_high", "always"), null);
        Map<String, Object> c = chat.message(admin, null, "delete tia", null);
        assertEquals("confirm", c.get("kind"), String.valueOf(c));
        Map<String, Object> w = chat.message(admin, (String) c.get("session_id"), "yes", null);
        assertEquals("awaiting_approval", w.get("kind"), String.valueOf(w));
        assertNotNull(db.one("SELECT username FROM users WHERE username = ?", "tia"), "not deleted before the approval");
        Map<String, Object> task = db.one("SELECT * FROM human_tasks WHERE id = ?", w.get("task_id"));
        assertEquals("role:admin", task.get("assignee"));
        chat.decided(task, lee, "approved", "ok", null);
        assertNull(db.one("SELECT username FROM users WHERE username = ?", "tia"), "deleted after lee approved, as admin asked");
        assertNotNull(db.one("SELECT seq FROM audit WHERE action = ? AND object_id = ?", "user.delete", "tia"), "the kernel's own audit entry");
    }
}
