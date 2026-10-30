package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deferred work items with a time come back by themselves; schedules can skip missed times and overlapping runs;
 * an agent that an API task uses is not deleted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulesQueuesDeleteTest {
    Path data; Db db; ControlRoom cr; ControlRoom.User admin; String device;

    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }
    interface Executable { void run(); }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-p678");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Ops/Work.flow.json", RepositoryLifecycleTest.flow("work"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        device = (String) d.get("device_id");
    }

    @AfterAll void stop() { db.close(); }

    @Test void deferredItemsWithATimeComeBack() {
        String pool = (String) cr.platform.savePool(admin, null, "pool", "", List.of(device), null).get("id");
        String q = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Invoices", "bot_path", "Ops/Work.flow.json", "pool_id", pool), null).get("id");
        cr.platform.addItems(admin, q, List.of(Map.of("reference", "later", "data", Map.of("n", 1)), Map.of("reference", "held", "data", Map.of("n", 2))), null);
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> i : db.query("SELECT id, reference FROM queue_items WHERE queue_id = ? ORDER BY reference", q)) ids.add(i.get("id"));
        String held = (String) ids.get(0), later = (String) ids.get(1);
        cr.platform.updateItems(admin, q, List.of(later), "defer", Instant.now().plus(1, ChronoUnit.HOURS), null);
        cr.platform.updateItems(admin, q, List.of(held), "defer", null, null);
        cr.platform.setProcessing(admin, q, true, null);
        assertEquals(0, cr.platform.dispatch(), "nothing is due yet");

        db.update("UPDATE queue_items SET defer_until = ? WHERE id = ?", Instant.now().minus(1, ChronoUnit.MINUTES), later);
        assertEquals(1, cr.platform.dispatch(), "the item whose time has passed is picked up by itself");
        assertEquals("in_progress", db.one("SELECT status FROM queue_items WHERE id = ?", later).get("status"));
        assertNull(db.one("SELECT defer_until FROM queue_items WHERE id = ?", later).get("defer_until"));
        assertEquals("deferred", db.one("SELECT status FROM queue_items WHERE id = ?", held).get("status"), "deferred without a time waits for Retry");

        cr.platform.updateItems(admin, q, List.of(held), "retry", null, null);
        assertEquals("new", db.one("SELECT status FROM queue_items WHERE id = ?", held).get("status"));
        db.update("UPDATE runs SET status = 'success'");
        cr.platform.setProcessing(admin, q, false, null);
    }

    @Test void schedulesSkipMissedTimesAndOverlaps() {
        Map<String, Object> plain = cr.createSchedule(admin, new HashMap<>(Map.of("name", "plain", "bot_path", "Ops/Work.flow.json", "device_id", device, "kind", "every", "every_minutes", 60)), null);
        Map<String, Object> skip = cr.createSchedule(admin, new HashMap<>(Map.of("name", "skip", "bot_path", "Ops/Work.flow.json", "device_id", device, "kind", "every", "every_minutes", 60,
                "missed_runs", "skip", "overlap", "skip")), null);
        assertEquals("CR.BAD_SCHEDULE", code(() -> cr.createSchedule(admin, new HashMap<>(Map.of("name", "x", "bot_path", "Ops/Work.flow.json", "device_id", device, "kind", "every", "every_minutes", 5,
                "missed_runs", "all")), null)));
        // both were due two hours ago (the Command Center was down)
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        db.update("UPDATE schedules SET next_run = ? WHERE id IN (?, ?)", twoHoursAgo, plain.get("id"), skip.get("id"));
        db.update("UPDATE runs SET status = 'success'");
        assertEquals(1, cr.tickSchedules(), "the default runs once for the missed time; 'skip' does not");
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'schedule.skip_missed'"));

        // due now, with a run of the 'skip' schedule still going: that one is skipped
        db.update("UPDATE runs SET status = 'success'");
        cr.startRun("admin", "admin", "Ops/Work.flow.json", device, null, Map.of(), "schedule:" + skip.get("id"), null, 0, null, null, null);
        db.update("UPDATE schedules SET next_run = ? WHERE id IN (?, ?)", Instant.now().minus(10, ChronoUnit.SECONDS), plain.get("id"), skip.get("id"));
        assertEquals(1, cr.tickSchedules(), "the default starts anyway; 'skip' waits for its previous run");
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'schedule.skip_overlap'"));

        cr.updateSchedule(admin, (String) skip.get("id"), Map.of("overlap", "allow", "missed_runs", "once"), null);
        Map<String, Object> row = db.one("SELECT missed_runs, overlap FROM schedules WHERE id = ?", skip.get("id"));
        assertNull(row.get("missed_runs"));
        assertNull(row.get("overlap"));
        db.update("UPDATE runs SET status = 'success'");
        cr.deleteSchedule(admin, (String) plain.get("id"), null);
        cr.deleteSchedule(admin, (String) skip.get("id"), null);
    }

    @Test void anAgentAnApiTaskUsesIsNotDeleted() {
        cr.checkin(admin, "Ops/Api.flow.json", RepositoryLifecycleTest.flow("api"), "v1", true, null);
        Map<String, Object> t = cr.apiTasks.create(admin, new HashMap<>(Map.of("name", "Quote API", "bot_path", "Ops/Api.flow.json", "device_id", device)), null);
        OoaException e = assertThrows(OoaException.class, () -> cr.deleteBot(admin, "Ops/Api.flow.json", null));
        assertEquals("CR.IN_USE", e.code());
        assertTrue(e.getMessage().contains("API task Quote API"), e.getMessage());
        assertNotNull(t.get("id"));
    }
}
