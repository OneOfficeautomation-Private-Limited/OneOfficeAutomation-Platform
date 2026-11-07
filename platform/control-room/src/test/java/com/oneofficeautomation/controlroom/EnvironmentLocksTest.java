package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** G33: environment locks kept by One CommandCenter: one run at a time, leases, run end, admin release, the schema upgrade. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnvironmentLocksTest {
    Db db; ControlRoom cr; ControlRoom.User admin;
    Map<String, Object> dev, otherDev;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-locks");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Locks/Hello.flow.json", LicensingAndChecksTest.agent("hello"), "v1", true, null);
        dev = runner("Locks-PC");
        otherDev = runner("Locks-PC2");
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    Map<String, Object> runner(String name) {
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), name, name.toLowerCase(), "Windows 11", "0.2.0", null);
        return cr.device((String) r.get("device_id"), (String) r.get("secret"));
    }

    /** A run on the runner, started (running). */
    String running(Map<String, Object> d) {
        String id = (String) cr.startRun("admin", "admin", "Locks/Hello.flow.json", (String) d.get("id"), Map.of(), "manual", null).get("run_id");
        assertNotNull(cr.claimNext(d));
        assertEquals("running", db.one("SELECT status FROM runs WHERE id = ?", id).get("status"));
        return id;
    }

    Map<String, Object> ask(Map<String, Object> d, String run, String op, String name) {
        Map<String, Object> b = new HashMap<>(Map.of("op", op, "name", name));
        return cr.locks.forRun(d, run, b);
    }
    boolean acquire(Map<String, Object> d, String run, String name) { return Boolean.TRUE.equals(ask(d, run, "acquire", name).get("acquired")); }
    boolean held(String name) { return Boolean.TRUE.equals(cr.locks.check(name, "nobody").get("held")); }
    long audits(String action, String name) { return ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = ? AND object_id = ?", action, name).get("n")).longValue(); }

    @Test void onlyOneRunGetsALockAndOnlyItsHolderGivesItBack() {
        String a = running(dev), b = running(dev);
        assertTrue(acquire(dev, a, "SAP login"));
        Map<String, Object> no = ask(dev, b, "acquire", "sap LOGIN");
        assertEquals(false, no.get("acquired"), "names are compared without case");
        assertEquals(true, no.get("held"));
        assertEquals(a, no.get("run_id"));
        assertEquals("Locks-PC", no.get("runner"));
        assertEquals("admin", no.get("held_by"));
        // the other run cannot give it back: nothing changes and it is told why
        Map<String, Object> rel = ask(dev, b, "release", "SAP login");
        assertEquals(false, rel.get("released"));
        assertTrue(String.valueOf(rel.get("message")).contains(a));
        assertTrue(held("SAP login"));
        Map<String, Object> check = ask(dev, b, "check", "SAP login");
        assertEquals(true, check.get("held")); assertEquals(false, check.get("mine"));
        assertEquals(true, ask(dev, a, "release", "SAP login").get("released"));
        assertFalse(held("SAP login"));
        assertTrue(acquire(dev, b, "SAP login"), "free again");
        assertEquals(1, audits("lock.release", "SAP login"));
        cr.finish(dev, a, Map.of("status", "success"));
        cr.finish(dev, b, Map.of("status", "success"));
    }

    @Test void aRunOnAnotherRunnerOrNotRunningIsRefused() {
        String a = running(dev);
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> ask(otherDev, a, "acquire", "x")).code(), "a runner speaks only for its own runs");
        cr.finish(dev, a, Map.of("status", "success"));
        assertEquals("LOCK.RUN_NOT_RUNNING", assertThrows(OoaException.class, () -> ask(dev, a, "acquire", "x")).code());
        String b = running(dev);
        assertEquals("LOCK.BAD_NAME", assertThrows(OoaException.class, () -> ask(dev, b, "acquire", "  ")).code());
        assertEquals("LOCK.BAD_LEASE", assertThrows(OoaException.class, () -> cr.locks.forRun(dev, b, Map.of("op", "acquire", "name", "x", "lease_seconds", 1))).code());
        cr.finish(dev, b, Map.of("status", "success"));
    }

    @Test void runsRacingForOneLockOnlyOneWins() throws Exception {
        int n = 8;
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < n; i++) runs.add(running(dev));
        for (int round = 0; round < 3; round++) {
            String name = "race-" + round;
            ExecutorService ex = Executors.newFixedThreadPool(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> got = new ArrayList<>();
            for (String r : runs) got.add(ex.submit(() -> { go.await(); return acquire(dev, r, name); }));
            go.countDown();
            int winners = 0;
            for (Future<Boolean> f : got) if (f.get(30, TimeUnit.SECONDS)) winners++;
            ex.shutdownNow();
            assertEquals(1, winners, "exactly one run holds " + name);
        }
        for (String r : runs) cr.finish(dev, r, Map.of("status", "success"));
        assertEquals(0L, ((Number) db.one("SELECT COUNT(*) AS n FROM environment_locks WHERE lock_key LIKE 'race-%'").get("n")).longValue());
    }

    @Test void aLockPastItsLeaseCanBeTakenWithAnAuditNoteAndTheHolderRenewsIt() {
        String a = running(dev), b = running(dev);
        assertTrue(acquire(dev, a, "Terminal"));
        Object since = db.one("SELECT acquired FROM environment_locks WHERE lock_key = 'terminal'").get("acquired");
        assertTrue(cr.locks.forRun(dev, a, Map.of("op", "acquire", "name", "Terminal", "lease_seconds", 120)).get("acquired").equals(true), "the holder renews");
        assertEquals(since, db.one("SELECT acquired FROM environment_locks WHERE lock_key = 'terminal'").get("acquired"), "a renewal keeps when it was taken");
        assertEquals(1, audits("lock.renew", "Terminal"));
        assertFalse(acquire(dev, b, "Terminal"));
        db.update("UPDATE environment_locks SET expires = ? WHERE lock_key = 'terminal'", Instant.now().minusSeconds(5));
        assertFalse(held("Terminal"), "past its lease it counts as free");
        assertTrue(acquire(dev, b, "Terminal"), "another run takes it");
        assertEquals(1, audits("lock.take_over", "Terminal"));
        assertTrue(String.valueOf(db.one("SELECT details FROM audit WHERE action = 'lock.take_over' AND object_id = 'Terminal'").get("details")).contains("lease"));
        assertEquals(false, ask(dev, a, "release", "Terminal").get("released"), "the first run lost it");
        cr.finish(dev, a, Map.of("status", "success"));
        assertTrue(held("Terminal"), "the first run ending does not touch the second run's lock");
        cr.finish(dev, b, Map.of("status", "success"));
    }

    @Test void waitingThenSucceedsWhenTheHolderGivesItBack() throws Exception {
        String a = running(dev), b = running(dev);
        assertTrue(acquire(dev, a, "Mailbox"));
        // the toolkit waits by asking again; here the second ask after the holder ends gets it
        assertFalse(acquire(dev, b, "Mailbox"));
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
        ex.schedule(() -> cr.finish(dev, a, Map.of("status", "success")), 500, TimeUnit.MILLISECONDS);
        long end = System.currentTimeMillis() + 10_000;
        boolean got = false;
        while (!got && System.currentTimeMillis() < end) { got = acquire(dev, b, "Mailbox"); if (!got) Thread.sleep(100); }
        ex.shutdownNow();
        assertTrue(got);
        cr.finish(dev, b, Map.of("status", "success"));
    }

    @Test void runEndGivesBackItsLocksWhateverEndedIt() {
        for (String status : List.of("success", "failed", "stopped")) {
            String r = running(dev);
            assertTrue(acquire(dev, r, "End-" + status));
            assertTrue(acquire(dev, r, "End2-" + status));
            cr.finish(dev, r, Map.of("status", status));
            assertFalse(held("End-" + status), status);
            assertNull(db.one("SELECT 1 AS x FROM environment_locks WHERE run_id = ?", r), status);
        }
        // the runner is lost: the clock fails its run and gives back the lock
        Map<String, Object> lost = runner("Lost-PC");
        String r = running(lost);
        assertTrue(acquire(lost, r, "Lost lock"));
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", Instant.now().minusSeconds(600), lost.get("id"));
        cr.reap();
        assertEquals("failed", db.one("SELECT status FROM runs WHERE id = ?", r).get("status"));
        assertNull(db.one("SELECT 1 AS x FROM environment_locks WHERE lock_key = 'lost lock'"));
        // a run ended some other way (here by hand): the clock's sweep gives it back, and another run may take it before that
        String s = running(dev), t = running(dev);
        assertTrue(acquire(dev, s, "Swept"));
        db.update("UPDATE runs SET status = 'failed' WHERE id = ?", s);
        assertTrue(acquire(dev, t, "Swept"), "a lock whose run ended is free to take");
        db.update("UPDATE runs SET status = 'stopped' WHERE id = ?", t);
        assertEquals(1, cr.locks.sweep());
        assertNull(db.one("SELECT 1 AS x FROM environment_locks WHERE lock_key = 'swept'"));
    }

    @Test void administratorsReleaseAnyLockAndItIsAudited() {
        String a = running(dev);
        assertTrue(cr.locks.forRun(dev, a, Map.of("op", "acquire", "name", "Shared/Inbox", "comment", "month end")).get("acquired").equals(true));
        cr.createUser(admin, "vera", "Vera Viewer", "viewer", null);
        ControlRoom.User vera = new ControlRoom.User(cr.users(admin).stream().filter(u -> "vera".equals(u.get("username"))).findFirst().orElseThrow().get("id").toString(), "vera", "viewer", "Vera", false);
        Map<String, Object> row = cr.locks.list(vera).stream().filter(l -> "Shared/Inbox".equals(l.get("name"))).findFirst().orElseThrow();
        assertEquals(a, row.get("run_id"));
        assertEquals("month end", row.get("comment"));
        assertEquals("Locks-PC", row.get("device_name"));
        assertEquals(false, row.get("expired"));
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.locks.adminRelease(vera, "Shared/Inbox", null)).code(), "viewers see locks but cannot release them");
        assertTrue(Rbac.ADMIN_ONLY.contains("locks.manage"));
        assertEquals(true, cr.locks.adminRelease(admin, "shared/inbox", "10.0.0.1").get("released"));
        assertFalse(held("Shared/Inbox"));
        Map<String, Object> au = db.one("SELECT actor, details, ip FROM audit WHERE action = 'lock.release' AND object_id = 'Shared/Inbox'");
        assertEquals("admin", au.get("actor"));
        assertTrue(String.valueOf(au.get("details")).contains(a));
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.locks.adminRelease(admin, "Shared/Inbox", null)).code());
        cr.finish(dev, a, Map.of("status", "success"));
    }

    @Test void aDatabaseAt33GetsTheLocksTable() throws Exception {
        try (Db old = SchemaUpgradeTest.fresh()) {
            SchemaUpgradeTest.upTo(old, 33);
            assertFalse(SchemaUpgradeTest.has(old, "SELECT 1 FROM environment_locks WHERE 1 = 0"));
            old.migrate();
            assertEquals(Db.LATEST, old.schemaVersion());
            assertTrue(SchemaUpgradeTest.has(old, "SELECT lock_key, name, run_id, device_id, device_name, bot_path, held_by, acquired, expires, note FROM environment_locks WHERE 1 = 0"));
            assertTrue(Db.ddl("CREATE TABLE environment_locks (lock_key VARCHAR(200) PRIMARY KEY, name VARCHAR(200) NOT NULL)", true).contains("NVARCHAR(200)"), "SQL Server gets Unicode text");
        }
    }
}
