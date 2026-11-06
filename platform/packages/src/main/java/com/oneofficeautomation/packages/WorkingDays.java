package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * A business calendar (gap G34): which days of the week are the weekend and which dates are holidays. Pure date
 * arithmetic, without a call per day: whole weeks are counted at once and only the holidays in a range are looked at,
 * so adding a thousand working days is as quick as adding one.
 */
final class WorkingDays {
    static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    /** Adding or counting more working days than this is refused (about 3,800 years of five-day weeks). */
    static final long MAX_DAYS = 1_000_000;

    private final boolean[] weekend = new boolean[8];   // by DayOfWeek.getValue(), 1 = Monday
    private final int perWeek;
    private final Set<LocalDate> holidays;               // every holiday given
    private final TreeSet<LocalDate> workdayHolidays = new TreeSet<>();   // the holidays that fall on a working day of the week

    WorkingDays(Collection<DayOfWeek> weekendDays, Collection<LocalDate> holidays) {
        for (DayOfWeek d : weekendDays) weekend[d.getValue()] = true;
        int n = 0;
        for (int i = 1; i <= 7; i++) if (!weekend[i]) n++;
        if (n == 0) throw new ActionException("DATETIME.BAD_WEEKEND", "The weekend cannot be every day of the week", "Leave at least one working day, e.g. weekend SAT,SUN.");
        perWeek = n;
        this.holidays = new HashSet<>(holidays);
        for (LocalDate h : holidays) if (!weekend[h.getDayOfWeek().getValue()]) workdayHolidays.add(h);
    }

    /** Weekend days from text such as "SAT,SUN", "fri sat", "Friday;Saturday" or "NONE"; blank means SAT,SUN. */
    static List<DayOfWeek> weekend(Object given) {
        List<String> parts = new ArrayList<>();
        if (given instanceof Collection<?> c) { for (Object o : c) if (o != null) parts.add(String.valueOf(o)); }
        else if (given != null) parts.add(String.valueOf(given));
        List<String> tokens = new ArrayList<>();
        for (String p : parts) for (String t : p.replaceAll("[\\[\\]\"']", " ").split("[\\s,;]+")) if (!t.isBlank()) tokens.add(t.trim());
        if (tokens.isEmpty()) return List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        if (tokens.size() == 1 && tokens.get(0).equalsIgnoreCase("NONE")) return List.of();
        EnumSet<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String t : tokens) {
            String u = t.toUpperCase(Locale.ROOT);
            DayOfWeek day = null;
            for (DayOfWeek d : DayOfWeek.values()) if (u.length() >= 3 && d.name().startsWith(u) ) day = d;
            if (day == null) throw new ActionException("DATETIME.BAD_WEEKEND", "'" + t + "' is not a day of the week",
                    "Write the weekend as days such as SAT,SUN or FRI,SAT, or NONE when every day is a working day.");
            out.add(day);
        }
        return new ArrayList<>(out);
    }

    /** Holiday dates from a list of yyyy-MM-dd texts or dates; text longer than 10 characters is cut to its date part. */
    static Set<LocalDate> holidays(Collection<?> given) {
        Set<LocalDate> off = new HashSet<>();
        if (given != null) for (Object h : given) {
            if (h == null || "".equals(h)) continue;
            if (h instanceof LocalDateTime t) { off.add(t.toLocalDate()); continue; }
            if (h instanceof LocalDate d) { off.add(d); continue; }
            String s = String.valueOf(h).strip();
            try { off.add(LocalDate.parse(s.substring(0, Math.min(10, s.length())))); }
            catch (RuntimeException e) { throw new ActionException("DATETIME.BAD_HOLIDAY", "Holiday '" + h + "' is not a date", "Write holidays as yyyy-MM-dd, e.g. 2026-12-25."); }
        }
        return off;
    }

    boolean isWeekend(LocalDate d) { return weekend[d.getDayOfWeek().getValue()]; }
    boolean isHoliday(LocalDate d) { return holidays.contains(d); }
    boolean isWorking(LocalDate d) { return !isWeekend(d) && !isHoliday(d); }

    /** Working days from 'from' to 'to', both included; 0 when to is before from. */
    long countInclusive(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return 0;
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        long n = days / 7 * perWeek;
        for (LocalDate d = from.plusDays(days / 7 * 7); !d.isAfter(to); d = d.plusDays(1)) if (!isWeekend(d)) n++;
        return n - workdayHolidays.subSet(from, true, to, true).size();
    }

    /** Working days after start up to and including end (both included with includeStart); negative when end is before start. */
    long between(LocalDate start, LocalDate end, boolean includeStart) {
        if (!end.isBefore(start)) return countInclusive(includeStart ? start : start.plusDays(1), end);
        return -countInclusive(end, includeStart ? start : start.minusDays(1));
    }

    /** The date n working days after d (before it when n is negative); d itself is never counted, and 0 keeps d. */
    LocalDate plus(LocalDate d, long n) {
        if (Math.abs(n) > MAX_DAYS) throw new ActionException("DATETIME.BAD_AMOUNT", "At most " + MAX_DAYS + " working days can be added at once", null);
        LocalDate cur = d;
        long left = Math.abs(n);
        boolean back = n < 0;
        while (left > 0) {
            long weeks = left / perWeek;
            if (weeks > 0) {   // whole weeks at once: never more working days than are left, so it never goes too far
                LocalDate to = back ? cur.minusWeeks(weeks) : cur.plusWeeks(weeks);
                left -= back ? countInclusive(to, cur.minusDays(1)) : countInclusive(cur.plusDays(1), to);
                cur = to;
                // the last of them may lie before the jump's end (a jump can end on a weekend): step back to it
                if (left == 0) while (!isWorking(cur)) cur = back ? cur.plusDays(1) : cur.minusDays(1);
            } else {
                cur = back ? cur.minusDays(1) : cur.plusDays(1);
                if (isWorking(cur)) left--;
            }
        }
        return cur;
    }

    /** The first working day after d (or d itself when it is one and sameDay); before d when back. */
    LocalDate nextWorking(LocalDate d, boolean sameDay, boolean back) {
        LocalDate cur = sameDay ? d : back ? d.minusDays(1) : d.plusDays(1);
        while (!isWorking(cur)) cur = back ? cur.minusDays(1) : cur.plusDays(1);   // ends: holidays are finite and a week has a working day
        return cur;
    }

    /** The weekend as MON..SUN names. */
    List<String> weekendNames() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 7; i++) if (weekend[i]) out.add(WEEK.get(i - 1));
        return out;
    }
}
