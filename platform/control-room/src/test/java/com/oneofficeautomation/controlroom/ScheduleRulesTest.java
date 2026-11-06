package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** G35: the monthly and cron rules, including clock changes, 29 February, last days, ordinals and bad input. */
class ScheduleRulesTest {
    static final ZoneId UTC = ZoneOffset.UTC, LONDON = ZoneId.of("Europe/London"), NEW_YORK = ZoneId.of("America/New_York"), KOLKATA = ZoneId.of("Asia/Kolkata");

    static List<String> next(ScheduleRules.Rule r, String after, ZoneId zone, int n) {
        return ScheduleRules.nextTimes(r, Instant.parse(after), zone, n).stream().map(i -> i.atZone(zone).toOffsetDateTime().toString()).toList();
    }
    static ScheduleRules.Rule monthly(String rule, String at) { return ScheduleRules.monthly(rule, LocalTime.parse(at), YearMonth.of(2026, 11)); }
    static String error(Runnable r) {
        OoaException e = assertThrows(OoaException.class, r::run);
        assertEquals("CR.BAD_SCHEDULE", e.code());
        return e.getMessage();
    }

    // ---------- monthly ----------

    @Test void daysOfTheMonthAndTheLastDay() {
        assertEquals(List.of("2026-11-01T08:30Z", "2026-11-15T08:30Z", "2026-11-30T08:30Z", "2026-12-01T08:30Z", "2026-12-15T08:30Z", "2026-12-31T08:30Z"),
                next(monthly("days=1,15,last", "08:30"), "2026-10-31T12:00:00Z", UTC, 6));
    }

    @Test void aDayTheMonthLacksIsSkippedOrMovedToTheLastDay() {
        assertEquals(List.of("2027-01-31T09:00Z", "2027-03-31T09:00Z", "2027-05-31T09:00Z"), next(monthly("days=31", "09:00"), "2026-12-31T10:00:00Z", UTC, 3));
        assertEquals(List.of("2027-01-31T09:00Z", "2027-02-28T09:00Z", "2027-03-31T09:00Z", "2027-04-30T09:00Z"),
                next(monthly("days=31;missing=last", "09:00"), "2026-12-31T10:00:00Z", UTC, 4));
        // 29 February: skipped in common years, kept in leap years
        assertEquals(List.of("2028-02-29T06:00Z", "2032-02-29T06:00Z"), next(monthly("days=29;every=12;start=2027-02", "06:00"), "2026-11-01T00:00:00Z", UTC, 2));
        assertEquals(List.of("2027-02-28T06:00Z", "2028-02-29T06:00Z"), next(monthly("days=29,30;every=12;start=2027-02;missing=last", "06:00"), "2026-11-01T00:00:00Z", UTC, 2));
        assertEquals(List.of("2028-02-29T00:00Z"), next(monthly("days=last;every=12;start=2027-02", "00:00"), "2027-03-01T00:00:00Z", UTC, 1));
    }

    @Test void ordinalWeekdays() {
        // Nov 2026: Mondays 2, 9, 16, 23, 30; Fridays 6 ... 27
        assertEquals(List.of("2026-11-02T09:00Z", "2026-12-07T09:00Z", "2027-01-04T09:00Z"), next(monthly("weekday=first MON", "09:00"), "2026-11-01T00:00:00Z", UTC, 3));
        assertEquals(List.of("2026-11-27T17:00Z", "2026-12-25T17:00Z"), next(monthly("weekday=last fri", "17:00"), "2026-11-01T00:00:00Z", UTC, 2));
        assertEquals(List.of("2026-11-30T09:00Z", "2027-03-29T09:00Z"), next(monthly("weekday=fifth MON", "09:00"), "2026-11-01T00:00:00Z", UTC, 2));
        assertEquals(List.of("2026-11-09T09:00Z", "2026-11-23T09:00Z"), next(monthly("weekday=second MON,fourth MON", "09:00"), "2026-11-01T00:00:00Z", UTC, 2));
    }

    @Test void everyNMonthsCountsFromTheStartMonth() {
        assertEquals(List.of("2026-11-01T08:00Z", "2027-02-01T08:00Z", "2027-05-01T08:00Z"), next(monthly("days=1;every=3", "08:00"), "2026-10-01T00:00:00Z", UTC, 3));
        assertEquals(List.of("2027-01-15T08:00Z", "2027-03-15T08:00Z"), next(monthly("days=15;every=2;start=2027-01", "08:00"), "2026-11-20T00:00:00Z", UTC, 2));
        assertEquals("days=1,15,last;weekday=first MON;every=2;start=2026-11;missing=last",
                ((ScheduleRules.Monthly) monthly("weekday=First mon; days=15,1,L; every=2 months; missing=LAST", "08:00")).text());
        assertEquals("days=1", ((ScheduleRules.Monthly) monthly("days=1;every=1;start=2020-01", "08:00")).text());
    }

    @Test void monthlyFollowsTheClockChanges() {
        // London goes forward on Sun 29 Mar 2026 (01:00 -> 02:00) and back on Sun 25 Oct 2026 (02:00 -> 01:00).
        assertEquals(List.of("2026-03-29T02:30+01:00"), next(monthly("weekday=last SUN;start=2026-01", "01:30"), "2026-03-01T00:00:00Z", LONDON, 1));
        assertEquals(List.of("2026-10-25T01:30+01:00", "2026-11-29T01:30Z"), next(monthly("weekday=last SUN;start=2026-01", "01:30"), "2026-10-01T00:00:00Z", LONDON, 2));
        assertEquals(List.of("2026-11-15T09:00+05:30"), next(monthly("days=15", "09:00"), "2026-11-01T00:00:00Z", KOLKATA, 1));
    }

    @Test void badMonthlyRulesSayWhatIsWrong() {
        assertTrue(error(() -> monthly("", "08:00")).contains("needs its days"));
        assertTrue(error(() -> monthly("days=0", "08:00")).contains("1 to 31"));
        assertTrue(error(() -> monthly("days=32", "08:00")).contains("1 to 31"));
        assertTrue(error(() -> monthly("days=x", "08:00")).contains("not a day of the month"));
        assertTrue(error(() -> monthly("weekday=sixth MON", "08:00")).contains("ordinal weekday"));
        assertTrue(error(() -> monthly("weekday=first", "08:00")).contains("ordinal weekday"));
        assertTrue(error(() -> monthly("days=1;every=0", "08:00")).contains("1 to 120"));
        assertTrue(error(() -> monthly("days=1;start=11/2026", "08:00")).contains("2026-11"));
        assertTrue(error(() -> monthly("days=1;missing=next", "08:00")).contains("skip or last"));
        assertTrue(error(() -> monthly("days=1;colour=red", "08:00")).contains("Unknown part"));
        assertTrue(error(() -> monthly("days", "08:00")).contains("key=value"));
        OoaException e = assertThrows(OoaException.class, () -> monthly("", "08:00"));
        assertTrue(e.suggestedFix().contains("days=1,15,last"), "an example is given");
    }

    // ---------- cron ----------

    @Test void fiveFieldCronWithListsRangesStepsAndNames() {
        assertEquals(List.of("2026-11-02T09:00Z", "2026-11-03T09:00Z", "2026-11-04T09:00Z"), next(ScheduleRules.cron("0 9 * * MON-FRI"), "2026-10-31T00:00:00Z", UTC, 3));
        assertEquals(List.of("2026-11-01T00:00Z", "2026-11-01T00:15Z", "2026-11-01T00:30Z"), next(ScheduleRules.cron("*/15 * * * *"), "2026-10-31T23:59:00Z", UTC, 3));
        assertEquals(List.of("2026-11-01T08:05Z", "2026-11-01T08:25Z", "2026-11-01T08:45Z", "2026-11-01T10:05Z"), next(ScheduleRules.cron("5/20 8-11/2 * * *"), "2026-11-01T00:00:00Z", UTC, 4));
        assertEquals(List.of("2027-01-01T00:00Z", "2027-04-01T00:00Z", "2027-07-01T00:00Z"), next(ScheduleRules.cron("0 0 1 JAN,APR,JUL,OCT *"), "2026-11-01T00:00:00Z", UTC, 3));
        // 0 and 7 are both Sunday
        assertEquals(next(ScheduleRules.cron("0 12 * * 0"), "2026-11-01T13:00:00Z", UTC, 2), next(ScheduleRules.cron("0 12 * * 7"), "2026-11-01T13:00:00Z", UTC, 2));
        assertEquals(List.of("2026-11-08T12:00Z"), next(ScheduleRules.cron("0 12 * * SUN"), "2026-11-01T13:00:00Z", UTC, 1));
        // both day fields given: either matches (the 13th, or a Friday)
        assertEquals(List.of("2026-11-06T00:00Z", "2026-11-13T00:00Z", "2026-11-20T00:00Z", "2026-11-27T00:00Z", "2026-12-04T00:00Z"),
                next(ScheduleRules.cron("0 0 13 * FRI"), "2026-11-01T00:00:00Z", UTC, 5));
        assertEquals(List.of("2026-12-13T00:00Z"), next(ScheduleRules.cron("0 0 13 * ?"), "2026-11-14T00:00:00Z", UTC, 1));
    }

    @Test void macros() {
        assertEquals(List.of("2026-11-01T01:00Z"), next(ScheduleRules.cron("@hourly"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2026-11-02T00:00Z"), next(ScheduleRules.cron("@daily"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2026-12-01T00:00Z"), next(ScheduleRules.cron("@monthly"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2027-01-01T00:00Z"), next(ScheduleRules.cron("@YEARLY"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2026-11-08T00:00Z"), next(ScheduleRules.cron("@weekly"), "2026-11-01T00:00:00Z", UTC, 1));
    }

    @Test void quartzSecondsLastDaysNearestWeekdaysAndNthWeekdays() {
        assertEquals(List.of("2026-11-02T08:00:30Z"), next(ScheduleRules.cron("30 0 8 ? * MON"), "2026-11-01T00:00:00Z", UTC, 1));   // Quartz MON = 2 by name
        assertEquals(List.of("2026-11-02T08:00Z"), next(ScheduleRules.cron("0 0 8 ? * 2"), "2026-11-01T00:00:00Z", UTC, 1));        // 2 = Monday in Quartz
        assertEquals(List.of("2026-11-30T00:00Z", "2026-12-31T00:00Z", "2027-01-31T00:00Z", "2027-02-28T00:00Z"), next(ScheduleRules.cron("0 0 0 L * ?"), "2026-11-01T00:00:00Z", UTC, 4));
        assertEquals(List.of("2028-02-29T00:00Z"), next(ScheduleRules.cron("0 0 0 L 2 ? 2028"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2026-11-27T00:00Z", "2026-12-28T00:00Z"), next(ScheduleRules.cron("0 0 0 L-3 * ?"), "2026-11-01T00:00:00Z", UTC, 2));
        // 15W: 15 Nov 2026 is a Sunday, so Monday 16th; 1W in Aug 2026 (Saturday 1st) is Monday 3rd
        assertEquals(List.of("2026-11-16T00:00Z"), next(ScheduleRules.cron("0 0 0 15W * ?"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2026-08-03T00:00Z"), next(ScheduleRules.cron("0 0 0 1W * ?"), "2026-07-02T00:00:00Z", UTC, 1));
        // LW: the last weekday; 31 Oct 2026 is a Saturday
        assertEquals(List.of("2026-10-30T00:00Z", "2026-11-30T00:00Z"), next(ScheduleRules.cron("0 0 0 LW * ?"), "2026-10-01T00:00:00Z", UTC, 2));
        // 6L = last Friday (Quartz), 6#1 = first Friday; FRI#3 = third Friday
        assertEquals(List.of("2026-11-27T17:00Z"), next(ScheduleRules.cron("0 0 17 ? * 6L"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2026-11-06T17:00Z", "2026-12-04T17:00Z"), next(ScheduleRules.cron("0 0 17 ? * 6#1"), "2026-11-01T00:00:00Z", UTC, 2));
        assertEquals(List.of("2026-11-20T17:00Z"), next(ScheduleRules.cron("0 0 17 ? * FRI#3"), "2026-11-01T00:00:00Z", UTC, 1));
        // five-field forms of the same: 5L and 5#1 (0 = Sunday numbering)
        assertEquals(List.of("2026-11-27T17:00Z"), next(ScheduleRules.cron("0 17 * * 5L"), "2026-11-01T00:00:00Z", UTC, 1));
        assertEquals(List.of("2026-11-06T17:00Z"), next(ScheduleRules.cron("0 17 * * 5#1"), "2026-11-01T00:00:00Z", UTC, 1));
        // the year field ends the rule
        ScheduleRules.Cron once = ScheduleRules.cron("0 0 9 1 1 ? 2027");
        assertEquals(List.of("2027-01-01T09:00Z"), next(once, "2026-11-01T00:00:00Z", UTC, 5));
        assertNull(ScheduleRules.next(once, Instant.parse("2027-01-02T00:00:00Z"), UTC));
        assertNull(ScheduleRules.next(ScheduleRules.cron("0 0 30 2 *"), Instant.parse("2026-11-01T00:00:00Z"), UTC), "30 February never comes");
    }

    @Test void feb29Cron() {
        assertEquals(List.of("2028-02-29T12:00Z", "2032-02-29T12:00Z"), next(ScheduleRules.cron("0 12 29 2 *"), "2026-11-01T00:00:00Z", UTC, 2));
    }

    @Test void springForwardRunsSkippedTimesLaterAndNeverTwice() {
        // New York: Sun 8 Mar 2026, 02:00 EST -> 03:00 EDT
        assertEquals(List.of("2026-03-07T02:30-05:00", "2026-03-08T03:30-04:00", "2026-03-09T02:30-04:00"), next(ScheduleRules.cron("30 2 * * *"), "2026-03-07T00:00:00Z", NEW_YORK, 3));
        assertEquals(List.of("2026-03-08T01:30-05:00", "2026-03-08T01:45-05:00", "2026-03-08T03:00-04:00", "2026-03-08T03:15-04:00"),
                next(ScheduleRules.cron("*/15 * * * *"), "2026-03-08T06:15:00Z", NEW_YORK, 4));
        // London: Sun 29 Mar 2026, 01:00 GMT -> 02:00 BST
        assertEquals(List.of("2026-03-29T00:45Z", "2026-03-29T02:00+01:00", "2026-03-29T02:15+01:00"), next(ScheduleRules.cron("*/15 * * * *"), "2026-03-29T00:30:00Z", LONDON, 3));
        assertEquals(List.of("2026-03-29T02:30+01:00", "2026-03-30T01:30+01:00"), next(ScheduleRules.cron("30 1 * * *"), "2026-03-28T12:00:00Z", LONDON, 2));
    }

    @Test void fallBackRunsANamedHourOnceAndEveryHourInBothCopies() {
        // New York: Sun 1 Nov 2026, 02:00 EDT -> 01:00 EST
        assertEquals(List.of("2026-11-01T01:30-04:00", "2026-11-02T01:30-05:00"), next(ScheduleRules.cron("30 1 * * *"), "2026-10-31T12:00:00Z", NEW_YORK, 2));
        assertEquals(List.of("2026-11-01T01:30-04:00", "2026-11-01T01:30-05:00", "2026-11-01T02:30-05:00"), next(ScheduleRules.cron("30 * * * *"), "2026-11-01T05:00:00Z", NEW_YORK, 3));
        // asking from inside the second copy of the hour does not run the named time again
        assertEquals(List.of("2026-11-02T01:30-05:00"), next(ScheduleRules.cron("30 1 * * *"), "2026-11-01T06:31:00Z", NEW_YORK, 1));
        // London: Sun 25 Oct 2026, 02:00 BST -> 01:00 GMT
        assertEquals(List.of("2026-10-25T01:00+01:00", "2026-10-25T01:00Z", "2026-10-25T02:00Z"), next(ScheduleRules.cron("0 * * * *"), "2026-10-24T23:30:00Z", LONDON, 3));
        assertEquals(List.of("2026-10-25T01:15+01:00", "2026-10-26T01:15Z"), next(ScheduleRules.cron("15 1 * * *"), "2026-10-24T12:00:00Z", LONDON, 2));
    }

    @Test void badCronSaysWhatIsWrongWithAnExample() {
        assertTrue(error(() -> ScheduleRules.cron("")).contains("needs its expression"));
        assertTrue(error(() -> ScheduleRules.cron("* * * *")).contains("5 fields"));
        assertTrue(error(() -> ScheduleRules.cron("1 2 3 4 5 6 7 8")).contains("5 fields"));
        assertTrue(error(() -> ScheduleRules.cron("60 * * * *")).contains("between 0 and 59"));
        assertTrue(error(() -> ScheduleRules.cron("0 24 * * *")).contains("between 0 and 23"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 0 * *")).contains("between 1 and 31"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 1 13 *")).contains("between 1 and 12"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 * * 8")).contains("between 0 and 7"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 * * FOO")).contains("not a day of the week"));
        assertTrue(error(() -> ScheduleRules.cron("5-1 * * * *")).contains("backwards"));
        assertTrue(error(() -> ScheduleRules.cron("*/0 * * * *")).contains("between 1 and"));
        assertTrue(error(() -> ScheduleRules.cron("1,,2 * * * *")).contains("empty item"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 ? * ?")).contains("Only one"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 9 1 * MON")).contains("Quartz"));
        assertTrue(error(() -> ScheduleRules.cron("*/5 * * * * ?")).contains("seconds field must be one number"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 * * L")).contains("FRIL"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 0 ? * 6#6")).contains("1 to 5"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 0 32W * ?")).contains("1 to 31"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 0 L-31 * ?")).contains("30 days"));
        assertTrue(error(() -> ScheduleRules.cron("0 0 0 1 1 ? 1900")).contains("between 1970 and 2199"));
        assertTrue(error(() -> ScheduleRules.cron("@sometimes")).contains("Unknown cron shortcut"));
        OoaException e = assertThrows(OoaException.class, () -> ScheduleRules.cron("bad"));
        assertTrue(e.suggestedFix().contains("0 9 * * MON-FRI"), "an example is given");
    }
}
