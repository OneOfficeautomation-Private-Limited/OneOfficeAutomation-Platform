package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** G34: working-day arithmetic in ooa.datetime, with holidays and weekends given on the step or read from One CommandCenter. */
class WorkingDaysTest {
    @TempDir Path dir;
    final DateTimePackage pkg = new DateTimePackage();

    static LocalDate d(String s) { return LocalDate.parse(s); }
    static LocalDateTime t(String s) { return LocalDateTime.parse(s.length() == 10 ? s + "T09:30:00" : s); }
    static WorkingDays cal(String weekend, String... holidays) {
        return new WorkingDays(WorkingDays.weekend(weekend), WorkingDays.holidays(Arrays.asList(holidays)));
    }

    ActionContext ctx(Map<String, Object> services) {
        return new ActionContext("run_1", "s1", "default", dir, new HashMap<>(), services, new AtomicBoolean(), List::of, (l, m) -> {});
    }
    ActionContext plain() { return ctx(new HashMap<>()); }

    /** The slow, obvious way, to check the quick one against. */
    static LocalDate slowPlus(WorkingDays w, LocalDate from, long n) {
        LocalDate cur = from;
        for (long left = Math.abs(n); left > 0; ) { cur = cur.plusDays(Long.signum(n)); if (w.isWorking(cur)) left--; }
        return cur;
    }

    @Test void addsWorkingDaysOverWeekendsAndHolidays() {
        WorkingDays w = cal("", "2026-12-25", "2027-01-01");
        assertEquals(d("2026-11-09"), w.plus(d("2026-11-06"), 1), "Friday + 1 is Monday");
        assertEquals(d("2026-11-06"), w.plus(d("2026-11-09"), -1), "Monday - 1 is Friday");
        assertEquals(d("2026-11-07"), w.plus(d("2026-11-07"), 0), "0 keeps the date, even a Saturday");
        assertEquals(d("2026-11-09"), w.plus(d("2026-11-07"), 1), "from a Saturday, 1 is Monday");
        // over the year end: 24 Dec (Thu) + 1 skips Christmas (Fri) and the weekend; + 5 skips New Year too
        assertEquals(d("2026-12-28"), w.plus(d("2026-12-24"), 1));
        assertEquals(d("2027-01-04"), w.plus(d("2026-12-24"), 5));
        assertEquals(d("2026-12-24"), w.plus(d("2027-01-04"), -5));
        assertEquals(d("2026-12-24"), w.plus(d("2026-12-28"), -1));
    }

    @Test void leapYearsCount29February() {
        WorkingDays w = cal("SAT,SUN");
        assertEquals(d("2028-02-29"), w.plus(d("2028-02-28"), 1), "2028 is a leap year; 29 Feb is a Tuesday");
        assertEquals(d("2027-03-01"), w.plus(d("2027-02-26"), 1), "2027 is not");
        assertEquals(21, w.between(d("2028-01-31"), d("2028-02-29"), false), "February 2028 has 21 working days");
        assertEquals(262, w.between(d("2023-12-31"), d("2024-12-31"), false), "2024 (leap, starts Monday) has 262 weekdays");
    }

    @Test void aHolidayOnTheWeekendIsNotCountedTwice() {
        WorkingDays w = cal("SAT,SUN", "2026-12-26", "2026-12-27");   // a Saturday and a Sunday
        assertEquals(5, w.between(d("2026-12-20"), d("2026-12-27"), false));
        assertEquals(d("2026-12-28"), w.plus(d("2026-12-25"), 1));
        assertTrue(w.isHoliday(d("2026-12-26")) && w.isWeekend(d("2026-12-26")));
    }

    @Test void countsBothWaysAndWithTheStart() {
        WorkingDays w = cal("SAT,SUN", "2026-11-11");
        assertEquals(3, w.between(d("2026-11-09"), d("2026-11-13"), false), "after Monday up to Friday, without the Wednesday holiday");
        assertEquals(4, w.between(d("2026-11-09"), d("2026-11-13"), true), "with Monday, but Wednesday is a holiday");
        assertEquals(-3, w.between(d("2026-11-13"), d("2026-11-09"), false));
        assertEquals(0, w.between(d("2026-11-14"), d("2026-11-15"), false), "a weekend has no working days");
        assertEquals(0, w.between(d("2026-11-09"), d("2026-11-09"), false));
        assertEquals(1, w.between(d("2026-11-09"), d("2026-11-09"), true));
    }

    @Test void otherWeekends() {
        WorkingDays gulf = cal("FRI,SAT");
        assertEquals(d("2026-11-08"), gulf.plus(d("2026-11-05"), 1), "Thursday + 1 is Sunday with a Friday-Saturday weekend");
        assertTrue(gulf.isWorking(d("2026-11-08")));
        WorkingDays all = cal("NONE", "2026-11-07");
        assertEquals(d("2026-11-08"), all.plus(d("2026-11-06"), 1), "every day works but the holiday");
        WorkingDays sixDays = cal("sunday");
        assertEquals(d("2026-11-07"), sixDays.plus(d("2026-11-06"), 1));
        assertEquals(List.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), WorkingDays.weekend("[\"Fri\", \"Saturday\"]"));
        assertEquals("DATETIME.BAD_WEEKEND", assertThrows(ActionException.class, () -> WorkingDays.weekend("SA")).code());
        assertEquals("DATETIME.BAD_WEEKEND", assertThrows(ActionException.class, () -> cal("MON,TUE,WED,THU,FRI,SAT,SUN")).code());
        assertEquals("DATETIME.BAD_HOLIDAY", assertThrows(ActionException.class, () -> cal("", "25/12/2026")).code());
    }

    @Test void quickArithmeticMatchesDayByDay() {
        Random r = new Random(34);
        List<String> hol = new ArrayList<>();
        for (int i = 0; i < 300; i++) hol.add(LocalDate.of(2020, 1, 1).plusDays(r.nextInt(4000)).toString());
        for (String wk : List.of("SAT,SUN", "FRI,SAT", "SUN", "NONE", "MON,WED,FRI")) {
            WorkingDays w = cal(wk, hol.toArray(String[]::new));
            for (int i = 0; i < 300; i++) {
                LocalDate from = LocalDate.of(2020, 1, 1).plusDays(r.nextInt(4000));
                long n = r.nextInt(1200) - 600;
                LocalDate to = w.plus(from, n);
                assertEquals(slowPlus(w, from, n), to, wk + " " + from + " " + n);
                if (n != 0) assertEquals(n, w.between(from, to, false), "counting back gives the same number: " + wk + " " + from + " " + n);
            }
        }
    }

    @Test void largeAmountsAreQuick() {
        WorkingDays w = cal("SAT,SUN", "2026-12-25");
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) w.plus(d("2026-01-01"), 250_000);
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "1000 additions of 250,000 working days take well under 2 s");
        assertEquals("DATETIME.BAD_AMOUNT", assertThrows(ActionException.class, () -> w.plus(d("2026-01-01"), 2_000_000)).code());
    }

    // ---- the actions ----

    @Test void actionsWithHolidaysAndWeekendOnTheStep() {
        ActionContext c = plain();
        List<Object> hols = List.of("2026-12-25", "2027-01-01");
        assertEquals(t("2027-01-04T09:30:00"), pkg.addWorkingDays(c, t("2026-12-24"), BigDecimal.valueOf(5), null, hols, null, "UTC").get("result"), "time of day kept");
        assertEquals(t("2026-12-24T09:30:00"), pkg.addWorkingDays(c, t("2027-01-04"), BigDecimal.valueOf(-5), "", hols, "", null).get("result"));
        assertEquals("DATETIME.BAD_AMOUNT", assertThrows(ActionException.class, () -> pkg.addWorkingDays(c, t("2026-12-24"), new BigDecimal("1.5"), null, null, null, "UTC")).code());
        assertEquals(new BigDecimal(4), pkg.workingDaysBetween(c, t("2026-12-21"), t("2026-12-28"), null, hols, null, false, "UTC").get("result"));
        assertEquals(t("2026-12-28T09:30:00"), pkg.nextWorkingDay(c, t("2026-12-24"), null, hols, null, false, "UTC").get("result"));
        assertEquals(t("2026-12-24T09:30:00"), pkg.nextWorkingDay(c, t("2026-12-24"), null, hols, null, true, "UTC").get("result"), "same day when it works");
        assertEquals(t("2026-12-31T09:30:00"), pkg.previousWorkingDay(c, t("2027-01-03"), null, hols, null, true, "UTC").get("result"));
        assertEquals(Map.of("result", false, "reason", "holiday"), pkg.isWorkingDayAction(c, t("2026-12-25"), null, hols, null, "UTC"));
        assertEquals(Map.of("result", false, "reason", "weekend"), pkg.isWorkingDayAction(c, t("2026-12-26"), null, hols, null, "UTC"));
        assertEquals(Map.of("result", true, "reason", ""), pkg.isWorkingDayAction(c, t("2026-12-26"), null, hols, "FRI", "UTC"));
        assertFalse(pkg.isWorkingDay(c, t("2026-12-25"), null, hols, null, "UTC"));
        assertTrue(pkg.isWorkingDay(c, t("2026-12-24"), null, hols, null, "UTC"));
    }

    @Test void theDateIsReadInTheTimeZone() {
        ActionContext c = plain();
        // Friday 20:00 UTC is already Saturday 01:30 in Kolkata
        assertEquals(Map.of("result", false, "reason", "weekend"), pkg.isWorkingDayAction(c, t("2026-11-06T20:00:00"), null, null, null, "Asia/Kolkata"));
        // one working day later in Kolkata is Monday 01:30 local = Sunday 20:00 UTC
        assertEquals(t("2026-11-08T20:00:00"), pkg.addWorkingDays(c, t("2026-11-06T20:00:00"), BigDecimal.ONE, null, null, null, "Asia/Kolkata").get("result"));
    }

    @Test void aCalendarIsReadFromOneCommandCenterOncePerRun() {
        AtomicInteger asked = new AtomicInteger();
        Function<Map<String, Object>, Map<String, Object>> cc = req -> {
            asked.incrementAndGet();
            assertEquals("calendar", req.get("call"));
            Object name = ((Map<?, ?>) req.get("args")).get("name");
            if ("Gulf".equals(name)) return Map.of("name", "Gulf", "dates", List.of("2026-12-02"), "weekend", List.of("FRI", "SAT"));
            if ("Open".equals(name)) return Map.of("name", "Open", "dates", List.of(), "weekend", List.of());
            throw new OoaException("CALENDAR.NOT_FOUND", "There is no holiday calendar '" + name + "'");
        };
        Map<String, Object> services = new HashMap<>(Map.of("command_center", cc));
        ActionContext c = ctx(services);
        assertEquals(t("2026-12-03T09:30:00"), pkg.addWorkingDays(c, t("2026-12-01"), BigDecimal.ONE, "Gulf", null, null, "UTC").get("result"), "2 Dec is a holiday");
        assertEquals(t("2026-12-06T09:30:00"), pkg.nextWorkingDay(c, t("2026-12-03"), "Gulf", null, null, false, "UTC").get("result"), "Friday and Saturday are the weekend");
        assertTrue(pkg.isWorkingDay(c, t("2026-12-06"), "Gulf", null, null, "UTC"));
        assertEquals(1, asked.get(), "asked once per run");
        // the step's holidays are added; its weekend replaces the calendar's
        assertFalse(pkg.isWorkingDay(c, t("2026-12-06"), "Gulf", List.of("2026-12-06"), null, "UTC"));
        assertTrue(pkg.isWorkingDay(c, t("2026-12-04"), "Gulf", null, "SUN", "UTC"));
        assertTrue(pkg.isWorkingDay(c, t("2026-12-05"), "Open", null, null, "UTC"), "a calendar with no weekend");
        assertEquals("DATETIME.CALENDAR_NOT_FOUND", assertThrows(ActionException.class, () -> pkg.isWorkingDay(c, t("2026-12-05"), "Nowhere", null, null, "UTC")).code());
        // another run asks again
        pkg.isWorkingDay(ctx(services), t("2026-12-06"), "Gulf", null, null, "UTC");
        assertEquals(4, asked.get());
    }

    @Test void withoutOneCommandCenterACalendarNameGivesAClearError() {
        ActionException e = assertThrows(ActionException.class, () -> pkg.addWorkingDays(plain(), t("2026-12-01"), BigDecimal.ONE, "India 2026", null, null, "UTC"));
        assertEquals("DATETIME.NO_COMMAND_CENTER", e.code());
        assertTrue(e.getMessage().contains("India 2026"));
        Function<Map<String, Object>, Map<String, Object>> old = req -> { throw new OoaException("JOBS.BAD_CALL", "Unknown request 'calendar'"); };
        assertEquals("DATETIME.NO_COMMAND_CENTER", assertThrows(ActionException.class,
            () -> pkg.isWorkingDay(ctx(new HashMap<>(Map.of("command_center", old))), t("2026-12-01"), "X", null, null, "UTC")).code());
    }
}
