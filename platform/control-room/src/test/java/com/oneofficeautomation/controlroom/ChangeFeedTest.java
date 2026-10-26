package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Live updates for the console (K4): the change feed answers at once when something people see changes. */
class ChangeFeedTest {

    @Test void theFeedWakesOnAChangeAndIgnoresHeartbeats() throws Exception {
        Path data = Files.createTempDirectory("ooa-changes");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            String token = (String) cr.changes(admin, null, 0).get("token");
            assertEquals(false, cr.changes(admin, null, 0).get("changed"), "the first call only gives a token");

            // nothing changes: the call waits, then answers unchanged
            long t0 = System.nanoTime();
            Map<String, Object> idle = cr.changes(admin, token, 1);
            assertEquals(false, idle.get("changed"));
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 900, "it waited");

            // a heartbeat (last seen) is not a change people see
            String dev = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
            token = (String) cr.changes(admin, null, 0).get("token");
            db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now(), dev);
            assertEquals(false, cr.changes(admin, token, 0).get("changed"));

            // a change from another thread wakes a waiting call at once
            String before = token;
            Thread writer = new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                db.update("UPDATE devices SET name = ? WHERE id = ?", "PC-1 renamed", dev);
            });
            writer.start();
            t0 = System.nanoTime();
            Map<String, Object> woke = cr.changes(admin, before, 20);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            writer.join();
            assertEquals(true, woke.get("changed"));
            assertNotEquals(before, woke.get("token"));
            assertTrue(ms < 5000, "answered when the change came, not after 20 seconds: " + ms + " ms");

            // an audited change (on any node, through the shared database) changes the token too
            String t2 = (String) woke.get("token");
            cr.audit.record("admin", "test.change", "test", null, null, "ok", null);
            assertEquals(true, cr.changes(admin, t2, 0).get("changed"));
        } finally { db.close(); }
    }
}
