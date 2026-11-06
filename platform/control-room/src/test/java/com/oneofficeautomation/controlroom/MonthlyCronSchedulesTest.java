package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G35: monthly and cron schedules in One CommandCenter: stored rules, next run, the preview of the next times, holiday
 * calendars, missed times and overlaps as for the other kinds, bad rules, and the schema upgrade to the rule column.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyCronSchedulesTest {
    Path data; Db db; ControlRoom cr; ControlRoom.User admin; String device;

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-g35");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Ops/Month.flow.json", RepositoryLifecycleTest.flow("month"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-M", "pc-m", "Windows 11", "0.2.0", null);
        device = (String) d.get("device_id");
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    Map<String, Object> draft(Object... kv) {
        Map<String, Object> m = new HashMap<>(Map.of("name", "s", "bot_path", "Ops/Month.flow.json", "device_id", device));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    OoaException refused(Map<String, Object> s) { return assertThrows(OoaException.class, () -> cr.createSchedule(admin, s, null)); }

    @Test void aMonthlyScheduleStoresItsRuleAndRunsOnItsDays() {
        Map<String, Object> r = cr.createSchedule(admin, draft("name", "Month end", "kind", "monthly", "rule", "days=L, 1 ;every=1", "at_time", "18:00", "time_zone", "Europe/London"), null);
        Map<String, Object> row = db.one("SELECT kind, rule, at_time, every_minutes, days, next_run FROM schedules WHERE id = ?", r.get("id"));
        assertEquals("monthly", row.get("kind"));
        assertEquals("days=1,last", row.get("rule"), "stored the tidy way");
        assertEquals("18:00", row.get("at_time"));
        assertNull(row.get("days"));
        ZonedDateTime next = Instant.parse(r.get("next_run").toString()).atZone(ZoneId.of("Europe/London"));
        assertEquals(LocalTime.of(18, 0), next.toLocalTime());
        assertTrue(next.getDayOfMonth() == 1 || next.getDayOfMonth() == next.toLocalDate().lengthOfMonth(), next.toString());
        // every N months keeps the month counting starts from
        Map<String, Object> q = cr.createSchedule(admin, draft("kind", "monthly", "rule", "weekday=first mon;every=3", "at_time", "09:00", "time_zone", "UTC"), null);
        String rule = (String) db.one("SELECT rule FROM schedules WHERE id = ?", q.get("id")).get("rule");
        assertEquals("weekday=first MON;every=3;start=" + YearMonth.now(ZoneOffset.UTC), rule);
        List<Map<String, Object>> audit = db.query("SELECT details FROM audit WHERE action = 'schedule.create' AND object_id = ?", q.get("id"));
        assertTrue(String.valueOf(audit.get(0).get("details")).contains("weekday=first MON"), "the audit trail names the rule");
    }

    @Test void aCronScheduleFollowsItsExpression() {
        Map<String, Object> r = cr.createSchedule(admin, draft("kind", "cron", "rule", "  0  9 * *   MON-FRI ", "time_zone", "America/New_York"), null);
        Map<String, Object> row = db.one("SELECT rule, at_time, next_run FROM schedules WHERE id = ?", r.get("id"));
        assertEquals("0 9 * * MON-FRI", row.get("rule"));
        assertNull(row.get("at_time"), "a cron schedule's time is in its expression");
        ZonedDateTime next = Instant.parse(r.get("next_run").toString()).atZone(ZoneId.of("America/New_York"));
        assertEquals(LocalTime.of(9, 0), next.toLocalTime());
        assertTrue(next.getDayOfWeek().getValue() <= 5);
        assertEquals("0 0 17 ? * 6#1", db.one("SELECT rule FROM schedules WHERE id = ?", cr.createSchedule(admin, draft("kind", "cron", "rule", "0 0 17 ? * 6#1"), null).get("id")).get("rule"));
    }

    @Test void badRulesAreRefusedWithAnExample() {
        OoaException e = refused(draft("kind", "cron", "rule", "0 25 * * *"));
        assertEquals("CR.BAD_SCHEDULE", e.code());
        assertTrue(e.getMessage().contains("hour"), e.getMessage());
        assertTrue(e.suggestedFix().contains("0 9 * * MON-FRI"));
        assertEquals("CR.BAD_SCHEDULE", refused(draft("kind", "cron")).code());
        assertTrue(refused(draft("kind", "cron", "rule", "0 0 9 1 1 ? 2020")).getMessage().contains("never runs again"));
        assertEquals("CR.BAD_SCHEDULE", refused(draft("kind", "monthly", "rule", "days=1")).code(), "a monthly schedule needs its time");
        assertTrue(refused(draft("kind", "monthly", "rule", "days=40", "at_time", "08:00")).getMessage().contains("1 to 31"));
        assertTrue(refused(draft("kind", "monthly", "at_time", "08:00")).getMessage().contains("needs its days"));
        assertTrue(refused(draft("kind", "yearly", "at_time", "08:00")).getMessage().contains("monthly or cron"));
        assertEquals("CR.BAD_SCHEDULE", refused(draft("kind", "cron", "rule", "0 9 * * *", "time_zone", "Mars/Base")).code());
    }

    @Test void thePreviewListsTheNextTimesAndMarksHolidays() {
        LocalDate first = YearMonth.now(ZoneOffset.UTC).plusMonths(1).atDay(1);
        cr.platform.saveCalendar(admin, "G35 days", List.of(first.toString()), null);
        Map<String, Object> p = cr.previewSchedule(admin, draft("kind", "monthly", "rule", "days=1", "at_time", "07:00", "time_zone", "UTC", "holiday_calendar", "G35 days"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> runs = (List<Map<String, Object>>) p.get("next_runs");
        assertEquals(5, runs.size());
        assertEquals(first.atTime(7, 0).toInstant(ZoneOffset.UTC).toString(), runs.get(0).get("time"));
        assertEquals(Boolean.TRUE, runs.get(0).get("holiday"));
        assertEquals(Boolean.FALSE, runs.get(1).get("holiday"));
        assertEquals("days=1", p.get("rule"));
        // other kinds too, and a count
        @SuppressWarnings("unchecked") List<Map<String, Object>> every = (List<Map<String, Object>>) cr.previewSchedule(admin, draft("kind", "every", "every_minutes", 30, "count", 3)).get("next_runs");
        assertEquals(3, every.size());
        assertEquals(Duration.ofMinutes(30), Duration.between(Instant.parse((String) every.get(0).get("time")), Instant.parse((String) every.get(1).get("time"))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> cron = (List<Map<String, Object>>) cr.previewSchedule(admin, draft("kind", "cron", "rule", "30 8 * * *", "time_zone", "Asia/Kolkata")).get("next_runs");
        assertTrue(((String) cron.get(0).get("local")).contains("T08:30+05:30"), cron.toString());
        assertEquals("CR.BAD_SCHEDULE", assertThrows(OoaException.class, () -> cr.previewSchedule(admin, draft("kind", "cron", "rule", "nope"))).code());
        assertEquals(0L, cr.count("SELECT COUNT(*) AS n FROM schedules WHERE name = 'preview only'"));
    }

    @Test void theClockFiresThemAndSkipsHolidaysMissedTimesAndOverlapsAsForTheOtherKinds() {
        db.update("UPDATE runs SET status = 'success'");
        db.update("UPDATE schedules SET enabled = FALSE");
        String tz = "Europe/London";
        Map<String, Object> m = cr.createSchedule(admin, draft("name", "m", "kind", "monthly", "rule", "days=1,15", "at_time", "06:00", "time_zone", tz), null);
        Map<String, Object> c = cr.createSchedule(admin, draft("name", "c", "kind", "cron", "rule", "*/5 * * * *", "time_zone", tz, "missed_runs", "skip", "overlap", "skip"), null);
        // both due now: both start, and each moves to its rule's next time
        db.update("UPDATE schedules SET next_run = ? WHERE id IN (?, ?)", Instant.now().minus(10, ChronoUnit.SECONDS), m.get("id"), c.get("id"));
        assertEquals(2, cr.tickSchedules());
        ZonedDateTime mNext = Instant.parse(db.one("SELECT next_run FROM schedules WHERE id = ?", m.get("id")).get("next_run").toString()).atZone(ZoneId.of(tz));
        assertTrue(Set.of(1, 15).contains(mNext.getDayOfMonth()) && mNext.toLocalTime().equals(LocalTime.of(6, 0)), mNext.toString());
        ZonedDateTime cNext = Instant.parse(db.one("SELECT next_run FROM schedules WHERE id = ?", c.get("id")).get("next_run").toString()).atZone(ZoneId.of(tz));
        assertEquals(0, cNext.getMinute() % 5);

        // overlap = skip: the cron schedule's run is still going
        db.update("UPDATE runs SET status = 'success' WHERE source <> ?", "schedule:" + c.get("id"));
        db.update("UPDATE schedules SET next_run = ? WHERE id IN (?, ?)", Instant.now().minus(10, ChronoUnit.SECONDS), m.get("id"), c.get("id"));
        assertEquals(1, cr.tickSchedules());
        assertTrue(cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'schedule.skip_overlap' AND object_id = ?", c.get("id")) >= 1);

        // missed_runs = skip: an old due time is skipped
        db.update("UPDATE runs SET status = 'success'");
        db.update("UPDATE schedules SET next_run = ? WHERE id = ?", Instant.now().minus(3, ChronoUnit.HOURS), c.get("id"));
        db.update("UPDATE schedules SET enabled = FALSE WHERE id = ?", m.get("id"));
        assertEquals(0, cr.tickSchedules());
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'schedule.skip_missed' AND object_id = ?", c.get("id")));

        // a holiday today (in the schedule's time zone) is skipped
        cr.platform.saveCalendar(admin, "G35 today", List.of(LocalDate.now(ZoneId.of(tz)).toString(), LocalDate.now(ZoneOffset.UTC).toString()), null);
        cr.updateSchedule(admin, (String) c.get("id"), Map.of("holiday_calendar", "G35 today"), null);
        db.update("UPDATE schedules SET next_run = ? WHERE id = ?", Instant.now().minus(10, ChronoUnit.SECONDS), c.get("id"));
        assertEquals(0, cr.tickSchedules());
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'schedule.skip_holiday' AND object_id = ?", c.get("id")));

        // enabling again computes the next time from the rule
        cr.setScheduleEnabled(admin, (String) m.get("id"), true, null);
        assertNotNull(db.one("SELECT next_run FROM schedules WHERE id = ?", m.get("id")).get("next_run"));
        db.update("UPDATE schedules SET enabled = FALSE");
    }

    @Test void aRuleThatCannotBeReadStopsOnlyItsSchedule() {
        db.update("UPDATE runs SET status = 'success'");
        db.update("UPDATE schedules SET enabled = FALSE");
        Map<String, Object> ok = cr.createSchedule(admin, draft("name", "fine", "kind", "cron", "rule", "* * * * *"), null);
        Map<String, Object> broken = cr.createSchedule(admin, draft("name", "broken", "kind", "cron", "rule", "* * * * *"), null);
        db.update("UPDATE schedules SET rule = 'not a rule', next_run = ? WHERE id = ?", Instant.now().minus(5, ChronoUnit.SECONDS), broken.get("id"));
        db.update("UPDATE schedules SET next_run = ? WHERE id = ?", Instant.now().minus(5, ChronoUnit.SECONDS), ok.get("id"));
        assertEquals(1, cr.tickSchedules());
        assertNull(db.one("SELECT next_run FROM schedules WHERE id = ?", broken.get("id")).get("next_run"));
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'schedule.fire' AND result = 'failed' AND object_id = ?", broken.get("id")));
        db.update("UPDATE schedules SET enabled = FALSE");
        db.update("UPDATE runs SET status = 'success'");
    }

    @Test void aDatabaseAt36GetsTheRuleColumn() throws Exception {
        try (Db old = SchemaUpgradeTest.fresh()) {
            SchemaUpgradeTest.upTo(old, 36);
            assertFalse(SchemaUpgradeTest.has(old, "SELECT rule FROM schedules WHERE 1 = 0"));
            old.update("INSERT INTO schedules (id, name, bot_path, device_id, kind, at_time, time_zone, inputs, enabled, created_by) VALUES ('s1', 'old', 'A/B.flow.json', 'd1', 'daily', '08:00', 'UTC', '{}', TRUE, 'admin')");
            old.migrate();
            assertEquals(Db.LATEST, old.schemaVersion());
            assertTrue(SchemaUpgradeTest.has(old, "SELECT rule FROM schedules WHERE 1 = 0"));
            assertNull(old.one("SELECT rule FROM schedules WHERE id = 's1'").get("rule"), "schedules made before keep working without a rule");
            assertTrue(Db.ddl("ALTER TABLE schedules ADD COLUMN rule VARCHAR(200)", true).contains("NVARCHAR(200)"), "SQL Server gets Unicode text");
        }
    }
}
