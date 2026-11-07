package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Business calendars (gap G34): a holiday calendar's weekend, kept and audited, and read by a running agent as the run's user. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessCalendarsTest {
    Path data; Db db; ControlRoom cr;
    ControlRoom.User admin, vera;
    Map<String, Object> dev;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-g34");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        vera = RepositoryLifecycleTest.person(cr, admin, "vera", "viewer");   // sees schedules and calendars, may not change them
        cr.checkin(admin, "Ops/Due.flow.json", KernelTestKit.flow("due"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        dev = cr.device((String) d.get("device_id"), (String) d.get("secret"));
        cr.setDefaultDevice(admin, "admin", (String) d.get("device_id"), null);
        cr.setDefaultDevice(admin, "vera", (String) d.get("device_id"), null);
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    Map<String, Object> calendar(String name) {
        return cr.platform.calendars(admin).stream().filter(c -> name.equals(c.get("name"))).findFirst().orElseThrow();
    }

    String running(String user) {
        String id = (String) cr.startRun("admin", "admin", "Ops/Due.flow.json", null, null, Map.of(), "manual", null, 0, null, user, null).get("run_id");
        cr.claimNext(dev);
        return id;
    }

    @Test void aCalendarKeepsItsWeekendAndTheChangeIsAudited() {
        cr.platform.saveCalendar(admin, "India 2026", List.of("2026-01-26", "2026-08-15"), "127.0.0.1");
        assertEquals(List.of("SAT", "SUN"), calendar("India 2026").get("weekend"), "a calendar saved without a weekend has Saturday and Sunday");
        cr.platform.saveCalendar(admin, "Gulf", List.of("2026-12-02"), List.of("fri", "Saturday"), true, null);
        assertEquals(List.of("FRI", "SAT"), calendar("Gulf").get("weekend"));
        cr.platform.saveCalendar(admin, "Gulf", List.of("2026-12-02", "2026-12-03"), null, false, null);
        assertEquals(List.of("FRI", "SAT"), calendar("Gulf").get("weekend"), "saving the dates only keeps the weekend");
        assertEquals(List.of("2026-12-02", "2026-12-03"), calendar("Gulf").get("dates"));
        cr.platform.saveCalendar(admin, "Gulf", List.of("2026-12-02"), "SUN", true, null);
        String details = String.valueOf(db.query("SELECT details FROM audit WHERE action = 'calendar.save' AND object_id = 'Gulf' ORDER BY seq DESC").get(0).get("details"));
        assertTrue(details.contains("SUN") && details.contains("FRI,SAT"), "the audit shows the new and the old weekend: " + details);
        cr.platform.saveCalendar(admin, "Every day", List.of(), "NONE", true, null);
        assertEquals(List.of(), calendar("Every day").get("weekend"));
        cr.platform.saveCalendar(admin, "Every day", List.of(), "", true, null);
        assertEquals(List.of("SAT", "SUN"), calendar("Every day").get("weekend"), "an empty weekend is the usual one");
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.saveCalendar(admin, "Bad", List.of(), "SA,SU", true, null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.saveCalendar(admin, "Bad", List.of(), "MON,TUE,WED,THU,FRI,SAT,SUN", true, null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.saveCalendar(vera, "Gulf", List.of(), "FRI", true, null)));
        // calendars made before version 37 have no weekend stored: Saturday and Sunday
        db.update("UPDATE holiday_calendars SET weekend = NULL WHERE name = ?", "India 2026");
        assertEquals(List.of("SAT", "SUN"), calendar("India 2026").get("weekend"));
    }

    @Test void aRunningAgentReadsACalendarAsTheRunsUser() {
        cr.platform.saveCalendar(admin, "Ops days", List.of("2026-12-25"), "FRI,SAT", true, null);
        String run = running("vera");
        Map<String, Object> c = cr.runRequest(dev, run, "calendar", Map.of("name", "Ops days"));
        assertEquals("Ops days", c.get("name"));
        assertEquals(List.of("2026-12-25"), c.get("dates"));
        assertEquals(List.of("FRI", "SAT"), c.get("weekend"));
        assertEquals(1L, ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'agent.calendar' AND object_id = 'Ops days'").get("n")).longValue());
        assertTrue(String.valueOf(db.one("SELECT actor FROM audit WHERE action = 'agent.calendar'").get("actor")).contains("vera"), "as the run's user");
        assertEquals("CALENDAR.NOT_FOUND", code(() -> cr.runRequest(dev, run, "calendar", Map.of("name", "Nowhere"))));
        // only a running agent on this runner may ask
        cr.finish(dev, run, Map.of("status", "success"));
        assertEquals("JOBS.NOT_RUNNING", code(() -> cr.runRequest(dev, run, "calendar", Map.of("name", "Ops days"))));
    }

    @Test void aDatabaseAt36GetsTheWeekendColumn() throws Exception {
        try (Db old = SchemaUpgradeTest.fresh()) {
            SchemaUpgradeTest.upTo(old, 36);
            old.update("INSERT INTO holiday_calendars (name, dates, updated) VALUES ('Old', '2026-12-25', CURRENT_TIMESTAMP)");
            assertFalse(SchemaUpgradeTest.has(old, "SELECT weekend FROM holiday_calendars WHERE 1 = 0"));
            old.migrate();
            assertEquals(Db.LATEST, old.schemaVersion());
            assertTrue(SchemaUpgradeTest.has(old, "SELECT weekend FROM holiday_calendars WHERE 1 = 0"));
            Map<String, Object> row = old.one("SELECT dates, weekend FROM holiday_calendars WHERE name = 'Old'");
            assertEquals("2026-12-25", row.get("dates"), "existing calendars are kept");
            assertNull(row.get("weekend"), "and mean Saturday and Sunday");
            assertEquals(List.of("SAT", "SUN"), Platform.weekendDays(null));
        }
    }
}
