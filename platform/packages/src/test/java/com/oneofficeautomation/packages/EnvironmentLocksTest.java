package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.oneofficeautomation.packages.PackagesTest.REG;
import static org.junit.jupiter.api.Assertions.*;

/** G33: environment locks on this computer (OneAgentStudio without One CommandCenter), and the relay to One CommandCenter. */
class EnvironmentLocksTest {
    @TempDir Path dir;
    final EnvironmentLocksPackage pkg = new EnvironmentLocksPackage();

    ActionContext run(String runId) {
        return new ActionContext(runId, "s1", "default", dir, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }
    static void closeSessions(ActionContext c) { for (Object s : c.sessions().values()) if (s instanceof AutoCloseable a) try { a.close(); } catch (Exception ignored) { } }

    @AfterEach void clear() { EnvironmentLocksPackage.Local.LOCKS.clear(); }

    @Test void onlyOneRunHoldsALockAndNamesIgnoreCase() {
        ActionContext a = run("run_a"), b = run("run_b");
        assertEquals(true, pkg.acquire(a, "SAP login", 0, 3600, "month end", false).get("acquired"));
        Map<String, Object> no = pkg.acquire(b, "sap LOGIN", 0, 3600, null, false);
        assertEquals(false, no.get("acquired"));
        assertTrue(String.valueOf(no.get("held_by")).contains("run_a"), String.valueOf(no));
        assertEquals("LOCK.TIMEOUT", assertThrows(ActionException.class, () -> pkg.acquire(b, "SAP login", 0, 3600, null, true)).code());
        Map<String, Object> c = pkg.check(b, "SAP login");
        assertEquals(true, c.get("held")); assertEquals(false, c.get("mine"));
        assertEquals(true, pkg.check(a, "SAP login").get("mine"));
        assertTrue(pkg.isHeld(b, "SAP login"));
        // the other run cannot give it back
        assertEquals(false, pkg.release(b, "SAP login").get("released"));
        assertTrue(pkg.isHeld(b, "SAP login"));
        assertEquals(true, pkg.release(a, "SAP login").get("released"));
        assertFalse(pkg.isHeld(b, "SAP login"));
        assertEquals(false, pkg.release(a, "SAP login").get("released"), "giving back twice changes nothing");
        assertEquals("LOCK.BAD_NAME", assertThrows(ActionException.class, () -> pkg.acquire(a, " ", 0, 3600, null, false)).code());
        assertEquals("LOCK.BAD_LEASE", assertThrows(ActionException.class, () -> pkg.acquire(a, "x", 0, 1, null, false)).code());
    }

    @Test void waitingGetsTheLockOnceItIsGivenBack() throws Exception {
        ActionContext a = run("run_a"), b = run("run_b");
        pkg.acquire(a, "mailbox", 0, 3600, null, false);
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
        try {
            ex.schedule(() -> pkg.release(a, "mailbox"), 1500, TimeUnit.MILLISECONDS);
            long t0 = System.nanoTime();
            assertEquals(true, pkg.acquire(b, "mailbox", 20, 3600, null, true).get("acquired"));
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 1000, "it waited");
        } finally { ex.shutdownNow(); }
    }

    @Test void aLockPastItsLeaseCanBeTakenAndTheHolderRenewsIt() throws Exception {
        ActionContext a = run("run_a"), b = run("run_b");
        pkg.acquire(a, "terminal", 0, 5, null, false);
        String since = String.valueOf(pkg.check(a, "terminal").get("since"));
        assertEquals(true, pkg.acquire(a, "terminal", 0, 3600, null, false).get("acquired"), "taking it again renews it");
        assertEquals(since, pkg.check(a, "terminal").get("since"), "a renewal keeps when it was taken");
        // the lease runs out (set back by hand): another run may take it
        EnvironmentLocksPackage.Local.LOCKS.computeIfPresent("terminal", (k, l) -> new EnvironmentLocksPackage.Local.Lock(l.name(), l.runId(), l.acquired(), java.time.Instant.now().minusSeconds(1), null));
        assertFalse(pkg.isHeld(b, "terminal"), "past its lease it counts as free");
        assertEquals(true, pkg.acquire(b, "terminal", 0, 3600, null, false).get("acquired"));
        assertEquals(false, pkg.release(a, "terminal").get("released"), "the first run lost it");
    }

    @Test void raceForOneLockOnlyOneWins() throws Exception {
        int n = 16;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> got = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ActionContext c = run("run_" + i);
            got.add(ex.submit(() -> { go.await(); return pkg.acquire(c, "race", 0, 3600, null, false).get("acquired"); }));
        }
        go.countDown();
        int winners = 0;
        for (Future<Object> f : got) if (Boolean.TRUE.equals(f.get(10, TimeUnit.SECONDS))) winners++;
        ex.shutdownNow();
        assertEquals(1, winners);
    }

    @Test void theAgentEndingGivesItsLocksBack() {
        Map<String, Object> flow = Map.of("kind", "TaskBot", "id", "g33", "name", "g33",
                "variables", List.of(Map.of("name", "got", "type", "Boolean", "direction", "output")),
                "steps", List.of(Map.of("id", "a", "action", "ooa.locks/acquire", "args", Map.of("name", "SAP"), "out", Map.of("acquired", "got"))));
        RunResult r = Runner.builder(REG).workDir(dir).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(true, r.outputs().get("got"));
        assertFalse(pkg.isHeld(run("run_x"), "SAP"), "given back when the agent ended");
        // a failing agent too
        Map<String, Object> failing = Map.of("kind", "TaskBot", "id", "g33f", "name", "g33f", "steps", List.of(
                Map.of("id", "a", "action", "ooa.locks/acquire", "args", Map.of("name", "SAP")),
                Map.of("id", "t", "action", "core/throw", "args", Map.of("code", "BUSINESS.X", "message", "boom"))));
        assertFalse(Runner.builder(REG).workDir(dir).build().run(failing, Map.of()).ok());
        assertFalse(pkg.isHeld(run("run_x"), "SAP"));
    }

    @Test void withOneCommandCenterTheRequestsGoThroughTheRunner() {
        List<Map<String, Object>> sent = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> relay = req -> {
            sent.add(req);
            return switch (String.valueOf(req.get("op"))) {
                case "acquire" -> Map.of("held", true, "acquired", false, "run_id", "r9", "runner", "PC-2", "held_by", "ann", "since", "2026-11-04T10:00:00Z");
                case "release" -> Map.of("released", false, "message", "held by another run");
                default -> Map.of("held", true, "mine", false, "run_id", "r9", "runner", "PC-2");
            };
        };
        ActionContext c = PackagesTestSupport.ctx(dir, Map.of("environment_locks", relay));
        Map<String, Object> r = pkg.acquire(c, "SAP", 0, 600, "why", false);
        assertEquals(false, r.get("acquired"));
        assertEquals("run r9 on PC-2 for ann since 2026-11-04T10:00:00Z", r.get("held_by"));
        assertEquals(Map.of("op", "acquire", "name", "SAP", "lease_seconds", 600, "comment", "why"), sent.get(0), "the run id is not sent: the runner adds its own");
        assertTrue(pkg.isHeld(c, "SAP"));
        assertTrue(EnvironmentLocksPackage.Local.LOCKS.isEmpty(), "nothing kept on this computer");
    }
}
