package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rules of monthly and cron schedules (gap G35, 5 Nov 2026), written here so One CommandCenter needs no scheduling
 * library. Both kinds work on wall-clock times in the schedule's time zone and share one way of turning a wall-clock
 * time into an instant:
 * <ul>
 *   <li>a time that does not exist (the hour skipped when clocks go forward) runs as many minutes later as the clocks
 *       moved, so 02:30 becomes 03:30, as the daily and weekly kinds already do;</li>
 *   <li>a time that happens twice (the hour repeated when clocks go back) runs once, the first time; only a cron rule
 *       that runs in every hour (hour field {@code *}) runs in both, since real time passes in both.</li>
 * </ul>
 *
 * <p><b>Monthly</b> rule text (stored in {@code schedules.rule}; the time is {@code at_time}), parts separated by {@code ;}:
 * <pre>
 *   days=1,15,last          days of the month, 1-31 or last
 *   weekday=first MON       or ordinal weekdays: first, second, third, fourth, fifth or last, with MON..SUN (several with commas)
 *   every=3                 every N months (1-120, default 1) counted from
 *   start=2026-11           the first month (default: the month the schedule is made)
 *   missing=skip            a day the month does not have (31 in April): skip (default) or last (run on the month's last day)
 * </pre>
 * Like UiPath's (Quartz) triggers and Blue Prism's default, a day the month lacks is skipped; {@code last} names the last
 * day itself, and {@code missing=last} gives Blue Prism's other choice.
 *
 * <p><b>Cron</b>: the standard five fields (minute hour day-of-month month day-of-week, with 0 or 7 = Sunday), or
 * Quartz's six or seven (second minute hour day-of-month month day-of-week [year], 1 = Sunday) as UiPath uses. Lists,
 * ranges, steps ({@code *}/15, 5/10, 1-5/2), month and day names, {@code ?}, and Quartz's {@code L}, {@code L-3},
 * {@code 15W}, {@code LW}, {@code 6L} (last Friday) and {@code 6#2} (second Friday) are understood, as are
 * {@code @hourly @daily @weekly @monthly @yearly}. With five fields and both day fields given, either day matching is
 * enough (as in Unix cron). The seconds field must be one number: schedules start at most once a minute.
 */
final class ScheduleRules {
    private ScheduleRules() { }

    /** A rule turned into something that can find its times. */
    interface Rule {
        /** Whether this local date is a day the rule runs on. */
        boolean day(LocalDate d);
        /** The first time on a matching day at or after {@code from}, or null when there is none that day. */
        LocalTime firstTime(LocalTime from);
        /** Whether, in the repeated hour of autumn, the rule runs in both copies of the hour. */
        boolean bothInRepeatedHour();
    }

    /** How far ahead a rule is searched before it counts as never running again (covers 29 Feb and Quartz years). */
    static final int HORIZON_YEARS = 30;
    static final String CRON_EXAMPLE = "Examples: 0 9 * * MON-FRI (09:00 on weekdays); Quartz: 0 30 18 L * ? (18:30 on the last day of the month), "
            + "0 0 9 ? * MON#1 (09:00 on the first Monday).";
    static final String MONTHLY_EXAMPLE = "Examples: days=1,15,last  or  weekday=first MON;every=3";

    // ===================== the shared search =====================

    /** The first time the rule runs strictly after {@code after}, or null when it never runs again within the horizon. */
    static Instant next(Rule rule, Instant after, ZoneId zone) {
        // Start a little before 'after' in wall-clock time: in the repeated hour a wall-clock time slightly earlier than
        // after's own can still be a later instant.
        LocalDateTime local = after.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).minusHours(3);
        LocalDate stop = local.toLocalDate().plusYears(HORIZON_YEARS);
        Instant best = null;
        LocalDateTime bestLimit = null;
        LocalDateTime l = firstLocal(rule, local, stop);
        while (l != null) {
            if (bestLimit != null && l.isAfter(bestLimit)) break;
            for (Instant c : instants(l, zone, rule.bothInRepeatedHour()))
                if (c.isAfter(after) && (best == null || c.isBefore(best))) { best = c; bestLimit = l.plusHours(3); }
            l = firstLocal(rule, l.plusSeconds(1), stop);
        }
        return best;
    }

    /** The next n times after {@code after} (fewer when the rule ends). */
    static List<Instant> nextTimes(Rule rule, Instant after, ZoneId zone, int n) {
        List<Instant> out = new ArrayList<>();
        Instant t = after;
        while (out.size() < n && (t = next(rule, t, zone)) != null) out.add(t);
        return out;
    }

    /** The first wall-clock time at or after {@code from} that the rule names, searching days up to {@code stop}. */
    static LocalDateTime firstLocal(Rule rule, LocalDateTime from, LocalDate stop) {
        LocalDate d = from.toLocalDate();
        LocalTime t0 = from.toLocalTime();
        while (!d.isAfter(stop)) {
            if (rule.day(d)) {
                LocalTime t = rule.firstTime(t0);
                if (t != null) return d.atTime(t);
            }
            d = d.plusDays(1);
            t0 = LocalTime.MIDNIGHT;
        }
        return null;
    }

    /** The instants a wall-clock time stands for: one normally, the moved-on one in a gap, one or both in an overlap. */
    static List<Instant> instants(LocalDateTime l, ZoneId zone, boolean both) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(l);
        if (offsets.isEmpty()) return List.of(ZonedDateTime.ofLocal(l, zone, null).toInstant());   // moved on by the gap
        Instant a = l.toInstant(offsets.get(0));
        if (offsets.size() == 1) return List.of(a);
        Instant b = l.toInstant(offsets.get(1));
        Instant first = a.isBefore(b) ? a : b, second = a.isBefore(b) ? b : a;
        return both ? List.of(first, second) : List.of(first);
    }

    static OoaException bad(String message, String example) { return new OoaException("CR.BAD_SCHEDULE", message, example); }

    // ===================== monthly =====================

    static final List<String> ORDINALS = List.of("first", "second", "third", "fourth", "fifth", "last");
    static final List<String> DAY_NAMES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    /** A monthly rule, parsed. */
    record Monthly(Set<Integer> days, boolean lastDay, List<int[]> weekdays, int every, YearMonth start, boolean missingToLast, LocalTime at) implements Rule {
        @Override public boolean day(LocalDate d) {
            YearMonth ym = YearMonth.from(d);
            long months = ChronoUnit.MONTHS.between(start, ym);
            if (months < 0 || months % every != 0) return false;
            int dom = d.getDayOfMonth(), len = d.lengthOfMonth();
            if (days.contains(dom)) return true;
            if (dom == len && (lastDay || missingToLast && days.stream().anyMatch(x -> x > len))) return true;
            for (int[] w : weekdays) {   // {ordinal 1..5 or -1 for last, ISO day 1..7}
                if (d.getDayOfWeek().getValue() != w[1]) continue;
                if (w[0] == -1 ? dom + 7 > len : (dom - 1) / 7 + 1 == w[0]) return true;
            }
            return false;
        }
        @Override public LocalTime firstTime(LocalTime from) { return from.isAfter(at) ? null : at; }
        @Override public boolean bothInRepeatedHour() { return false; }

        /** The rule written the way it is stored. */
        String text() {
            List<String> parts = new ArrayList<>();
            if (!days.isEmpty() || lastDay) {
                List<String> ds = new ArrayList<>();
                days.stream().sorted().forEach(x -> ds.add(String.valueOf(x)));
                if (lastDay) ds.add("last");
                parts.add("days=" + String.join(",", ds));
            }
            if (!weekdays.isEmpty()) {
                List<String> ws = new ArrayList<>();
                for (int[] w : weekdays) ws.add((w[0] == -1 ? "last" : ORDINALS.get(w[0] - 1)) + " " + DAY_NAMES.get(w[1] - 1));
                parts.add("weekday=" + String.join(",", ws));
            }
            if (every != 1) { parts.add("every=" + every); parts.add("start=" + start); }
            if (missingToLast) parts.add("missing=last");
            return String.join(";", parts);
        }
    }

    /**
     * Reads a monthly rule. {@code thisMonth} is the month counting starts from when the rule has no start of its own.
     * Throws CR.BAD_SCHEDULE with an example when the text is wrong.
     */
    static Monthly monthly(String rule, LocalTime at, YearMonth thisMonth) {
        if (rule == null || rule.isBlank()) throw bad("A monthly schedule needs its days, such as days=1,15 or weekday=first MON", MONTHLY_EXAMPLE);
        if (rule.length() > 200) throw bad("The monthly rule is longer than 200 characters", MONTHLY_EXAMPLE);
        Set<Integer> days = new LinkedHashSet<>();
        boolean last = false, missingToLast = false;
        List<int[]> weekdays = new ArrayList<>();
        int every = 1;
        YearMonth start = null;
        for (String part : rule.split(";")) {
            if (part.isBlank()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) throw bad("'" + part.trim() + "' is not key=value", MONTHLY_EXAMPLE);
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT), value = part.substring(eq + 1).trim();
            switch (key) {
                case "days", "day" -> {
                    for (String x : value.split(",")) {
                        String v = x.trim().toLowerCase(Locale.ROOT);
                        if (v.equals("last") || v.equals("l")) { last = true; continue; }
                        int n = number(v, "day of the month");
                        if (n < 1 || n > 31) throw bad("Days of the month are 1 to 31 or last, not " + x.trim(), MONTHLY_EXAMPLE);
                        days.add(n);
                    }
                }
                case "weekday", "weekdays" -> {
                    for (String x : value.split(",")) {
                        String[] w = x.trim().split("\\s+");
                        int o = w.length == 2 ? ORDINALS.indexOf(w[0].toLowerCase(Locale.ROOT)) : -2;
                        int d = w.length == 2 ? dayIndex(w[1]) : -1;
                        if (o < 0 || d < 0) throw bad("'" + x.trim() + "' is not an ordinal weekday such as first MON or last FRI", MONTHLY_EXAMPLE);
                        weekdays.add(new int[] { o == 5 ? -1 : o + 1, d + 1 });
                    }
                }
                case "every" -> {
                    every = number(value.replaceAll("(?i)\\s*months?$", ""), "number of months");
                    if (every < 1 || every > 120) throw bad("every is 1 to 120 months, not " + value, MONTHLY_EXAMPLE);
                }
                case "start" -> {
                    try { start = YearMonth.parse(value); } catch (RuntimeException e) { throw bad("start is a month such as 2026-11, not " + value, MONTHLY_EXAMPLE); }
                }
                case "missing" -> {
                    String v = value.toLowerCase(Locale.ROOT);
                    if (!v.equals("skip") && !v.equals("last")) throw bad("missing is skip or last, not " + value, MONTHLY_EXAMPLE);
                    missingToLast = v.equals("last");
                }
                default -> throw bad("Unknown part '" + key + "' (use days, weekday, every, start or missing)", MONTHLY_EXAMPLE);
            }
        }
        if (days.isEmpty() && !last && weekdays.isEmpty()) throw bad("A monthly schedule needs its days, such as days=1,15 or weekday=first MON", MONTHLY_EXAMPLE);
        return new Monthly(days, last, weekdays, every, start == null ? thisMonth : start, missingToLast, at);
    }

    private static int dayIndex(String s) {
        String u = s.trim().toUpperCase(Locale.ROOT);
        return DAY_NAMES.indexOf(u.length() > 3 ? u.substring(0, 3) : u);
    }

    private static int number(String s, String what) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { throw bad("'" + s.trim() + "' is not a " + what, MONTHLY_EXAMPLE); }
    }

    // ===================== cron =====================

    private static final List<String> MONTH_NAMES = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DOW_SUN_FIRST = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
    private static final Map<String, String> MACROS = Map.of("@yearly", "0 0 1 1 *", "@annually", "0 0 1 1 *", "@monthly", "0 0 1 * *",
            "@weekly", "0 0 * * 0", "@daily", "0 0 * * *", "@midnight", "0 0 * * *", "@hourly", "0 * * * *");
    static final int MIN_YEAR = 1970, MAX_YEAR = 2199;

    /** A cron expression, parsed. Days of the week are kept as ISO numbers (1 = Monday ... 7 = Sunday). */
    static final class Cron implements Rule {
        final String text;
        int second;
        final boolean[] minutes = new boolean[60], hours = new boolean[24], months = new boolean[13], doms = new boolean[32], dows = new boolean[8];
        boolean[] years;                  // null: any year
        boolean domAny = true, dowAny = true;
        boolean lastDay, lastWeekday;     // L, LW
        final List<Integer> lastOffsets = new ArrayList<>();   // L-n
        final List<Integer> nearestWeekday = new ArrayList<>(); // nW
        final List<Integer> lastDows = new ArrayList<>();      // xL
        final List<int[]> nthDows = new ArrayList<>();         // x#k as {iso day, k}
        boolean allHours;

        Cron(String text) { this.text = text; }

        @Override public boolean day(LocalDate d) {
            if (!months[d.getMonthValue()]) return false;
            if (years != null && (d.getYear() < MIN_YEAR || d.getYear() > MAX_YEAR || !years[d.getYear() - MIN_YEAR])) return false;
            if (domAny && dowAny) return true;
            if (domAny) return dowMatch(d);
            if (dowAny) return domMatch(d);
            return domMatch(d) || dowMatch(d);   // both given (five fields): either is enough, as in Unix cron
        }

        private boolean domMatch(LocalDate d) {
            int dom = d.getDayOfMonth(), len = d.lengthOfMonth();
            if (doms[dom]) return true;
            if (lastDay && dom == len) return true;
            for (int off : lastOffsets) if (dom == Math.max(1, len - off)) return true;
            if (lastWeekday && dom == nearestWeekdayTo(d.withDayOfMonth(len))) return true;
            for (int n : nearestWeekday) if (n <= len && dom == nearestWeekdayTo(d.withDayOfMonth(n))) return true;
            return false;
        }

        /** Quartz's W: the weekday nearest to the date, without leaving its month. */
        private static int nearestWeekdayTo(LocalDate t) {
            int dom = t.getDayOfMonth(), len = t.lengthOfMonth();
            return switch (t.getDayOfWeek()) {
                case SATURDAY -> dom == 1 ? 3 : dom - 1;
                case SUNDAY -> dom == len ? dom - 2 : dom + 1;
                default -> dom;
            };
        }

        private boolean dowMatch(LocalDate d) {
            int iso = d.getDayOfWeek().getValue(), dom = d.getDayOfMonth();
            if (dows[iso]) return true;
            for (int x : lastDows) if (x == iso && dom + 7 > d.lengthOfMonth()) return true;
            for (int[] nk : nthDows) if (nk[0] == iso && (dom - 1) / 7 + 1 == nk[1]) return true;
            return false;
        }

        @Override public LocalTime firstTime(LocalTime from) {
            for (int h = from.getHour(); h < 24; h++) {
                if (!hours[h]) continue;
                for (int m = h == from.getHour() ? from.getMinute() : 0; m < 60; m++) {
                    if (!minutes[m]) continue;
                    if (h == from.getHour() && m == from.getMinute() && from.getSecond() > second) continue;
                    return LocalTime.of(h, m, second);
                }
            }
            return null;
        }

        @Override public boolean bothInRepeatedHour() { return allHours; }
    }

    /** Reads a cron expression (five, six or seven fields, or a macro such as @daily). Throws CR.BAD_SCHEDULE with examples. */
    static Cron cron(String expression) {
        if (expression == null || expression.isBlank()) throw bad("A cron schedule needs its expression", CRON_EXAMPLE);
        String text = expression.trim().replaceAll("\\s+", " ");
        if (text.length() > 200) throw bad("The cron expression is longer than 200 characters", CRON_EXAMPLE);
        String expanded = text.startsWith("@") ? MACROS.get(text.toLowerCase(Locale.ROOT)) : text;
        if (expanded == null) throw bad("Unknown cron shortcut " + text + " (use @hourly, @daily, @weekly, @monthly or @yearly)", CRON_EXAMPLE);
        String[] f = expanded.split(" ");
        if (f.length < 5 || f.length > 7) throw bad("A cron expression has 5 fields (minute hour day month weekday) or 6-7 Quartz fields (second first, year last), not " + f.length, CRON_EXAMPLE);
        boolean quartz = f.length >= 6;
        int i = 0;
        Cron c = new Cron(text);
        if (quartz) {
            boolean[] secs = new boolean[60];
            fill(secs, f[i++], 0, 59, null, "second");
            int count = 0;
            for (int s = 0; s < 60; s++) if (secs[s]) { count++; c.second = s; }
            if (count != 1) throw bad("The seconds field must be one number such as 0: schedules start at most once a minute", CRON_EXAMPLE);
        }
        fill(c.minutes, f[i++], 0, 59, null, "minute");
        String hourField = f[i];
        fill(c.hours, f[i++], 0, 23, null, "hour");
        c.allHours = hourField.equals("*") || hourField.equals("?") || hourField.equals("*/1") || hourField.equals("0-23");
        String domField = f[i++], monField = f[i++], dowField = f[i++];
        fill(c.months, monField, 1, 12, MONTH_NAMES, "month");
        if (quartz && f.length == 7) { c.years = new boolean[MAX_YEAR - MIN_YEAR + 1]; fillYears(c.years, f[i]); }
        boolean domQ = domField.equals("?"), dowQ = dowField.equals("?");
        if (domQ && dowQ) throw bad("Only one of the two day fields may be ?", CRON_EXAMPLE);
        c.domAny = domQ || domField.equals("*");
        c.dowAny = dowQ || dowField.equals("*");
        if (quartz && !c.domAny && !c.dowAny)
            throw bad("In a six- or seven-field (Quartz) expression give either the day of the month or the day of the week, and ? in the other", CRON_EXAMPLE);
        if (!c.domAny) parseDom(c, domField);
        if (!c.dowAny) parseDow(c, dowField, quartz);
        return c;
    }

    private static void parseDom(Cron c, String field) {
        for (String raw : field.split(",", -1)) {
            String p = raw.trim().toUpperCase(Locale.ROOT);
            if (p.equals("L")) c.lastDay = true;
            else if (p.equals("LW")) c.lastWeekday = true;
            else if (p.matches("L-\\d{1,2}")) {
                int n = Integer.parseInt(p.substring(2));
                if (n > 30) throw bad("L-n counts back at most 30 days, not " + raw, CRON_EXAMPLE);
                c.lastOffsets.add(n);
            } else if (p.matches("\\d{1,2}W")) {
                int n = Integer.parseInt(p.substring(0, p.length() - 1));
                if (n < 1 || n > 31) throw bad("Day " + n + " is not 1 to 31 in " + raw, CRON_EXAMPLE);
                c.nearestWeekday.add(n);
            } else fill(c.doms, p, 1, 31, null, "day of the month");
        }
    }

    private static void parseDow(Cron c, String field, boolean quartz) {
        // Quartz numbers days 1 (Sunday) to 7 (Saturday); five-field cron 0 (Sunday) to 7 (Sunday again).
        int lo = quartz ? 1 : 0, hi = 7;
        for (String raw : field.split(",", -1)) {
            String p = raw.trim().toUpperCase(Locale.ROOT);
            if (p.equals("L")) throw bad("In the day-of-week field write the day before L, such as 6L or FRIL for the last Friday", CRON_EXAMPLE);
            if (p.matches("[A-Z0-9]{1,3}L")) { c.lastDows.add(iso(dowNumber(p.substring(0, p.length() - 1), quartz, raw), quartz)); continue; }
            if (p.matches("[A-Z0-9]{1,3}#\\d")) {
                int k = p.charAt(p.length() - 1) - '0';
                if (k < 1 || k > 5) throw bad("The week in " + raw + " is 1 to 5", CRON_EXAMPLE);
                c.nthDows.add(new int[] { iso(dowNumber(p.substring(0, p.indexOf('#')), quartz, raw), quartz), k });
                continue;
            }
            boolean[] tmp = new boolean[8];
            fill(tmp, p, lo, hi, DOW_SUN_FIRST, quartz ? "day of the week (1 = Sunday)" : "day of the week (0 = Sunday)", quartz ? 1 : 0);
            for (int v = lo; v <= hi; v++) if (tmp[v]) c.dows[iso(v, quartz)] = true;
        }
    }

    private static int dowNumber(String s, boolean quartz, String raw) {
        int name = DOW_SUN_FIRST.indexOf(s);
        if (name >= 0) return quartz ? name + 1 : name;
        try {
            int n = Integer.parseInt(s);
            if (n < (quartz ? 1 : 0) || n > 7) throw bad("Day of the week " + n + " is out of range in " + raw, CRON_EXAMPLE);
            return n;
        } catch (NumberFormatException e) { throw bad("'" + s + "' is not a day of the week in " + raw, CRON_EXAMPLE); }
    }

    /** A cron day number to ISO (1 = Monday ... 7 = Sunday). */
    private static int iso(int v, boolean quartz) {
        if (quartz) return v == 1 ? 7 : v - 1;
        return v == 0 || v == 7 ? 7 : v;
    }

    private static void fill(boolean[] into, String field, int lo, int hi, List<String> names, String what) { fill(into, field, lo, hi, names, what, 1); }

    /** Lists, ranges and steps of one field. {@code nameBase} is the number the first name stands for. */
    private static void fill(boolean[] into, String field, int lo, int hi, List<String> names, String what, int nameBase) {
        if (field.isEmpty()) throw bad("The " + what + " field is empty", CRON_EXAMPLE);
        for (String raw : field.split(",", -1)) {
            String p = raw.trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) throw bad("An empty item in the " + what + " field '" + field + "'", CRON_EXAMPLE);
            int step = 1;
            int slash = p.indexOf('/');
            if (slash >= 0) {
                step = value(p.substring(slash + 1), 1, hi - lo + 1, null, 0, what, raw);
                p = p.substring(0, slash);
            }
            int from, to;
            if (p.equals("*") || p.equals("?")) { from = lo; to = hi; }
            else if (p.contains("-")) {
                String[] r = p.split("-", -1);
                if (r.length != 2) throw bad("'" + raw + "' is not a range in the " + what + " field", CRON_EXAMPLE);
                from = value(r[0], lo, hi, names, nameBase, what, raw);
                to = value(r[1], lo, hi, names, nameBase, what, raw);
                if (to < from) throw bad("The range " + raw + " in the " + what + " field goes backwards", CRON_EXAMPLE);
            } else {
                from = value(p, lo, hi, names, nameBase, what, raw);
                to = slash >= 0 ? hi : from;   // 5/15 means from 5 to the end, every 15
            }
            for (int v = from; v <= to; v += step) into[v] = true;
        }
    }

    private static int value(String s, int lo, int hi, List<String> names, int nameBase, String what, String raw) {
        if (names != null) {
            int n = names.indexOf(s);
            if (n >= 0) return n + nameBase;
        }
        try {
            int n = Integer.parseInt(s);
            if (n < lo || n > hi) throw bad("The " + what + " " + n + " is not between " + lo + " and " + hi + " (in '" + raw + "')", CRON_EXAMPLE);
            return n;
        } catch (NumberFormatException e) { throw bad("'" + s + "' is not a " + what + " (in '" + raw + "')", CRON_EXAMPLE); }
    }

    private static void fillYears(boolean[] years, String field) {
        for (String raw : field.split(",", -1)) {
            String p = raw.trim();
            int step = 1, slash = p.indexOf('/');
            if (slash >= 0) { step = value(p.substring(slash + 1), 1, MAX_YEAR - MIN_YEAR, null, 0, "year step", raw); p = p.substring(0, slash); }
            int from, to;
            if (p.equals("*")) { from = MIN_YEAR; to = MAX_YEAR; }
            else if (p.contains("-")) {
                String[] r = p.split("-", -1);
                if (r.length != 2) throw bad("'" + raw + "' is not a range of years", CRON_EXAMPLE);
                from = value(r[0], MIN_YEAR, MAX_YEAR, null, 0, "year", raw); to = value(r[1], MIN_YEAR, MAX_YEAR, null, 0, "year", raw);
                if (to < from) throw bad("The range of years " + raw + " goes backwards", CRON_EXAMPLE);
            } else { from = value(p, MIN_YEAR, MAX_YEAR, null, 0, "year", raw); to = slash >= 0 ? MAX_YEAR : from; }
            for (int y = from; y <= to; y += step) years[y - MIN_YEAR] = true;
        }
    }

    // ===================== for ControlRoom =====================

    /** The rule of a monthly or cron schedule row ({@code kind}, {@code rule}, {@code at_time}). */
    static Rule of(String kind, String rule, String atTime, YearMonth thisMonth) {
        return switch (kind) {
            case "monthly" -> monthly(rule, LocalTime.parse(atTime), thisMonth);
            case "cron" -> cron(rule);
            default -> throw new IllegalArgumentException(kind);
        };
    }
}
