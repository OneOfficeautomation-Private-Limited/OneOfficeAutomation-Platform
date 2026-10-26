package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** DateTime values are UTC on the platform; time zones apply when reading or formatting. */
@OoaPackage(id = "ooa.datetime", name = "Date and time", version = "1.0.0", description = "Current time, add, difference, format and time zone conversion.")
public final class DateTimePackage {
    private static final String[] UNITS = {"milliseconds", "seconds", "minutes", "hours", "days", "weeks", "months", "years", "business_days"};
    private static final String[] STANDARD = {"ISO_LOCAL_DATE", "ISO_LOCAL_TIME", "ISO_LOCAL_DATE_TIME", "ISO_OFFSET_DATE_TIME", "ISO_ZONED_DATE_TIME", "ISO_INSTANT",
            "ISO_DATE", "ISO_DATE_TIME", "ISO_ORDINAL_DATE", "ISO_WEEK_DATE", "BASIC_ISO_DATE", "RFC_1123_DATE_TIME"};

    /**
     * A reading pattern that refuses impossible dates such as 31/02 (the lenient default silently turns it into 28/02).
     * Year letters y become u outside quotes, because strict reading of y needs an era.
     */
    static DateTimeFormatter strictPattern(String pattern, Locale locale) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (char ch : pattern.toCharArray()) {
            if (ch == '\'') quoted = !quoted;
            sb.append(!quoted && ch == 'y' ? 'u' : ch);
        }
        return DateTimeFormatter.ofPattern(sb.toString(), locale).withResolverStyle(java.time.format.ResolverStyle.STRICT);
    }

    private static Locale locale(String tag) { return tag == null || tag.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(tag); }

    /** One of Java's predefined ISO/RFC formats by name, or null. */
    private static DateTimeFormatter standard(String name) {
        if (name == null || name.isBlank()) return null;
        try { return (DateTimeFormatter) DateTimeFormatter.class.getField(name.trim().toUpperCase(Locale.ROOT)).get(null); }
        catch (ReflectiveOperationException | ClassCastException e) {
            throw new ActionException("DATETIME.BAD_PATTERN", "Unknown standard format '" + name + "'", "Use one of " + String.join(", ", STANDARD) + ".");
        }
    }

    private static ZoneId zone(String z) {
        try { return ZoneId.of(z); }
        catch (DateTimeException e) { throw new ActionException("DATETIME.BAD_ZONE", "Unknown time zone '" + z + "'", "Use an IANA name such as Europe/London or Asia/Kolkata."); }
    }

    @Action(id = "now", name = "Get current date and time", category = "Date and time", description = "Return the current date and time (UTC).")
    @Output(name = "value", type = OoaType.DATETIME, description = "Current UTC date and time.")
    public Map<String, Object> now(ActionContext ctx) {
        return Out.of("value", LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Action(id = "add", name = "Add to date", category = "Date and time", idempotent = true, description = "Add (or subtract, with a negative amount) a duration to a date.",
            panel = "value, amount, unit, holidays")
    @Output(name = "result", type = OoaType.DATETIME, description = "New date and time.")
    public Map<String, Object> add(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Start date.") LocalDateTime value,
            @Input(name = "amount", type = OoaType.NUMBER, description = "Amount to add; negative to subtract.") long amount,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"milliseconds", "seconds", "minutes", "hours", "days", "weeks", "months", "years", "business_days"},
                    description = "Unit of the amount; business_days skips Saturdays, Sundays and the holidays.") String unit,
            @Input(name = "holidays", type = OoaType.LIST, required = false, visibleWhen = "unit=business_days",
                    description = "Dates that are not working days, e.g. [\"2026-12-25\"].") List<Object> holidays) {
        if ("business_days".equals(unit)) return Out.of("result", plusBusinessDays(value, amount, holidays));
        return add(ctx, value, amount, unit);
    }

    public Map<String, Object> add(ActionContext ctx, LocalDateTime value, long amount, String unit) {
        LocalDateTime r = switch (unit) {
            case "milliseconds" -> value.plus(amount, ChronoUnit.MILLIS);
            case "seconds" -> value.plusSeconds(amount); case "minutes" -> value.plusMinutes(amount); case "hours" -> value.plusHours(amount);
            case "weeks" -> value.plusWeeks(amount); case "months" -> value.plusMonths(amount); case "years" -> value.plusYears(amount);
            default -> value.plusDays(amount);
        };
        return Out.of("result", r);
    }

    @Action(id = "difference", name = "Difference between dates", category = "Date and time", idempotent = true,
            description = "Return end minus start in the chosen unit (may be fractional).",
            panel = "start, end, unit, holidays | absolute")
    @Output(name = "result", type = OoaType.NUMBER, description = "Difference.")
    public Map<String, Object> difference(ActionContext ctx,
            @Input(name = "start", type = OoaType.DATETIME, description = "Earlier date.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "Later date.") LocalDateTime end,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"milliseconds", "seconds", "minutes", "hours", "days", "weeks", "months", "years", "business_days"},
                    description = "Result unit; business_days counts working days after start up to and including end.") String unit,
            @Input(name = "absolute", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the size of the difference without a minus sign when end is before start.") boolean absolute,
            @Input(name = "holidays", type = OoaType.LIST, required = false, visibleWhen = "unit=business_days",
                    description = "Dates that are not working days, e.g. [\"2026-12-25\"].") List<Object> holidays) {
        Map<String, Object> r = "business_days".equals(unit) ? Out.of("result", businessDaysBetween(start.toLocalDate(), end.toLocalDate(), holidays)) : difference(start, end, unit);
        return absolute ? Out.of("result", ((BigDecimal) r.get("result")).abs()) : r;
    }

    public Map<String, Object> difference(ActionContext ctx, LocalDateTime start, LocalDateTime end, String unit, boolean absolute) {
        return difference(ctx, start, end, unit, absolute, null);
    }

    private static BigDecimal businessDaysBetween(LocalDate start, LocalDate end, List<Object> holidays) {
        Set<LocalDate> off = holidaySet(holidays);
        boolean back = end.isBefore(start);
        LocalDate from = back ? end : start, to = back ? start : end;
        long n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) if (workingDay(d, off)) n++;
        return BigDecimal.valueOf(back ? -n : n);
    }

    private static Map<String, Object> difference(LocalDateTime start, LocalDateTime end, String unit) {
        if (unit.equals("milliseconds")) return Out.of("result", BigDecimal.valueOf(Duration.between(start, end).toMillis()));
        if (unit.equals("months")) return Out.of("result", BigDecimal.valueOf(ChronoUnit.MONTHS.between(start, end)));
        if (unit.equals("years")) return Out.of("result", BigDecimal.valueOf(ChronoUnit.YEARS.between(start, end)));
        long per = switch (unit) { case "seconds" -> 1; case "minutes" -> 60; case "hours" -> 3600; case "weeks" -> 604800; default -> 86400; };
        BigDecimal secs = BigDecimal.valueOf(Duration.between(start, end).getSeconds());
        return Out.of("result", secs.divide(BigDecimal.valueOf(per), 6, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    @Action(id = "format", name = "Format date", category = "Date and time", idempotent = true,
            description = "Format a date as text in a time zone, using a pattern such as dd/MM/yyyy HH:mm.",
            panel = "value, (Format: A standard format = standard / My own pattern = pattern) | time_zone, locale")
    @Output(name = "text", type = OoaType.STRING, description = "Formatted date.")
    public Map<String, Object> format(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to format (UTC).") LocalDateTime value,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"yyyy-MM-dd\"", visibleWhen = "standard=", description = "Pattern, e.g. dd/MM/yyyy, yyyy-MM-dd HH:mm, MMM d, yyyy.") String pattern,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "IANA time zone for display, e.g. Asia/Kolkata.") String tz,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = "Language for month and day names, e.g. en, de-DE, fr-FR.") String locale,
            @Input(name = "standard", type = OoaType.STRING, required = false, enumValues = {"ISO_LOCAL_DATE", "ISO_LOCAL_TIME", "ISO_LOCAL_DATE_TIME", "ISO_OFFSET_DATE_TIME", "ISO_ZONED_DATE_TIME", "ISO_INSTANT",
                    "ISO_DATE", "ISO_DATE_TIME", "ISO_ORDINAL_DATE", "ISO_WEEK_DATE", "BASIC_ISO_DATE", "RFC_1123_DATE_TIME"},
                    description = "A predefined ISO or RFC format to use instead of the pattern.") String standard) {
        try {
            ZonedDateTime z = value.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone(tz));
            DateTimeFormatter std = standard(standard);
            return Out.of("text", z.format(std != null ? std : DateTimeFormatter.ofPattern(pattern, locale(locale))));
        } catch (IllegalArgumentException e) {
            throw new ActionException("DATETIME.BAD_PATTERN", "Invalid date pattern '" + pattern + "': " + e.getMessage(), "Use letters like yyyy, MM, dd, HH, mm, ss.");
        }
    }

    @Action(id = "parse", name = "Text to date", category = "Date and time", idempotent = true,
            description = "Read a date from text using a pattern, interpreting it in a time zone; the result is UTC.",
            panel = "text, (Format: A standard format = standard / My own pattern = pattern) | time_zone, locale")
    @Output(name = "value", type = OoaType.DATETIME, description = "Parsed date (UTC).")
    public Map<String, Object> parse(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text such as 18/10/2026.") String text,
            @Input(name = "pattern", type = OoaType.STRING, required = false, visibleWhen = "standard=", description = "Pattern of the text, e.g. dd/MM/yyyy; needed unless a standard format is chosen.") String pattern,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone the text is in.") String tz,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = "Language of month and day names in the text, e.g. en, de-DE.") String locale,
            @Input(name = "standard", type = OoaType.STRING, required = false, enumValues = {"ISO_LOCAL_DATE", "ISO_LOCAL_DATE_TIME", "ISO_OFFSET_DATE_TIME", "ISO_ZONED_DATE_TIME", "ISO_INSTANT",
                    "ISO_DATE", "ISO_DATE_TIME", "BASIC_ISO_DATE", "RFC_1123_DATE_TIME"},
                    description = "A predefined ISO or RFC format to read instead of the pattern; an offset in the text wins over time_zone.") String standard) {
        DateTimeFormatter std = standard(standard);
        if (std != null) {
            try {
                java.time.temporal.TemporalAccessor ta = std.parse(text.trim());
                ZonedDateTime z;
                if (ta.isSupported(java.time.temporal.ChronoField.INSTANT_SECONDS)) z = Instant.from(ta).atZone(ZoneOffset.UTC);
                else if (ta.isSupported(java.time.temporal.ChronoField.HOUR_OF_DAY)) z = LocalDateTime.from(ta).atZone(zone(tz));
                else z = LocalDate.from(ta).atStartOfDay(zone(tz));
                return Out.of("value", z.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            } catch (DateTimeException e) {
                throw new ActionException("DATETIME.PARSE_FAILED", "Cannot read '" + text + "' as " + standard, "Check the text matches the chosen standard format.");
            }
        }
        if (pattern == null || pattern.isBlank()) throw new ActionException("DATETIME.BAD_PATTERN", "Give a pattern or a standard format", "Fill in 'pattern', e.g. dd/MM/yyyy.");
        try {
            DateTimeFormatter f = strictPattern(pattern, locale(locale));
            LocalDateTime local = pattern.contains("H") || pattern.contains("h") ? LocalDateTime.parse(text.trim(), f) : LocalDate.parse(text.trim(), f).atStartOfDay();
            return Out.of("value", local.atZone(zone(tz)).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new ActionException("DATETIME.PARSE_FAILED", "Cannot read '" + text + "' with pattern '" + pattern + "'", "Check the pattern matches the text exactly.");
        }
    }

    @Action(id = "convert_time_zone", name = "Convert time zone", category = "Date and time", idempotent = true,
            description = "Show a UTC date as local time in another zone, as ISO text with offset.",
            panel = "value, time_zone")
    @Output(name = "text", type = OoaType.STRING, description = "Local time with offset, e.g. 2026-10-18T20:00+05:30.")
    public Map<String, Object> convertTimeZone(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date (UTC).") LocalDateTime value,
            @Input(name = "time_zone", type = OoaType.STRING, description = "Target IANA zone.") String tz) {
        return Out.of("text", value.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone(tz)).toOffsetDateTime().toString());
    }

    @Condition(id = "is_weekend", name = "Is weekend", description = "True when the date falls on Saturday or Sunday in the given time zone.")
    public boolean isWeekend(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date (UTC).") LocalDateTime value,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone.") String tz) {
        DayOfWeek d = value.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone(tz)).getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    @Condition(id = "is_valid", name = "Text is a valid date", description = "True when the text can be read as a date with the pattern.")
    public boolean isValid(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to check.") String text,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"yyyy-MM-dd\"", description = "Expected pattern, e.g. dd/MM/yyyy.") String pattern) {
        try {
            DateTimeFormatter f = strictPattern(pattern, Locale.ENGLISH);
            if (pattern.contains("H") || pattern.contains("h")) LocalDateTime.parse(text.trim(), f); else LocalDate.parse(text.trim(), f);
            return true;
        } catch (RuntimeException e) { return false; }
    }

    @Condition(id = "is_business_hours", name = "Within business hours", description = "True between the start and end hours in the time zone, on weekdays (or every day).")
    public boolean isBusinessHours(ActionContext ctx,
            @Input(name = "start_hour", type = OoaType.NUMBER, required = false, defaultValue = "9", min = 0, max = 24, description = "Start hour (24h).") int start,
            @Input(name = "end_hour", type = OoaType.NUMBER, required = false, defaultValue = "17", min = 0, max = 24, description = "End hour (24h).") int end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone.") String tz,
            @Input(name = "weekdays_only", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Only Monday to Friday count; turn off for teams that work every day.") boolean weekdaysOnly) {
        ZonedDateTime z = ZonedDateTime.now(zone(tz));
        DayOfWeek d = z.getDayOfWeek();
        boolean workday = !weekdaysOnly || d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
        return workday && z.getHour() >= start && z.getHour() < end;
    }

    // ---- Comparison ----

    private static final String INCLUDE_TIME = "Compare the time of day too; when off only the calendar dates (in time_zone) are compared.";
    private static final String CMP_ZONE = "Time zone whose calendar dates are compared when include_time is off.";

    /** -1, 0 or 1; with include_time off, only the local calendar dates in the zone are compared. */
    private static int cmp(LocalDateTime a, LocalDateTime b, boolean includeTime, String tz) {
        need(a, "value"); need(b, "other");
        if (includeTime) return a.compareTo(b);
        ZoneId z = zone(tz == null || tz.isBlank() ? "UTC" : tz);
        return a.atOffset(ZoneOffset.UTC).atZoneSameInstant(z).toLocalDate().compareTo(b.atOffset(ZoneOffset.UTC).atZoneSameInstant(z).toLocalDate());
    }

    @Condition(id = "is_after", name = "Date is after", description = "True when the first date is later than the second.")
    public boolean isAfter(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "other", type = OoaType.DATETIME, description = "Date to compare with.") LocalDateTime other,
            @Input(name = "include_time", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = INCLUDE_TIME) boolean includeTime,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = CMP_ZONE) String tz) {
        return cmp(value, other, includeTime, tz) > 0;
    }

    @Condition(id = "is_before", name = "Date is before", description = "True when the first date is earlier than the second.")
    public boolean isBefore(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "other", type = OoaType.DATETIME, description = "Date to compare with.") LocalDateTime other,
            @Input(name = "include_time", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = INCLUDE_TIME) boolean includeTime,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = CMP_ZONE) String tz) {
        return cmp(value, other, includeTime, tz) < 0;
    }

    @Condition(id = "is_equal", name = "Dates are equal", description = "True when both dates are the same moment, or the same day when the time is not included.")
    public boolean isEqual(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "other", type = OoaType.DATETIME, description = "Date to compare with.") LocalDateTime other,
            @Input(name = "include_time", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = INCLUDE_TIME) boolean includeTime,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = CMP_ZONE) String tz) {
        return cmp(value, other, includeTime, tz) == 0;
    }

    @Condition(id = "is_between", name = "Date is between", description = "True when the date lies between two dates, both included.")
    public boolean isBetween(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "from", type = OoaType.DATETIME, description = "Earliest date.") LocalDateTime from,
            @Input(name = "to", type = OoaType.DATETIME, description = "Latest date.") LocalDateTime to,
            @Input(name = "include_time", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = INCLUDE_TIME) boolean includeTime,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = CMP_ZONE) String tz) {
        return cmp(value, from, includeTime, tz) >= 0 && cmp(value, to, includeTime, tz) <= 0;
    }

    private static LocalDateTime need(LocalDateTime v, String name) {
        if (v == null) throw new ActionException("DATETIME.MISSING", "Input '" + name + "' has no date", "Give a date such as 2026-10-18 or 2026-10-18T14:30:00.");
        return v;
    }

    @Action(id = "is_after", name = "Is after", category = "Date and time", idempotent = true, description = "Check whether the first date is later than the second.",
            panel = "value, other | include_time, time_zone")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "True when the first date is later.")
    public Map<String, Object> isAfterAction(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "other", type = OoaType.DATETIME, description = "Date to compare with.") LocalDateTime other,
            @Input(name = "include_time", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = INCLUDE_TIME) boolean includeTime,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = CMP_ZONE) String tz) {
        return Out.of("result", isAfter(ctx, value, other, includeTime, tz));
    }

    @Action(id = "is_before", name = "Is before", category = "Date and time", idempotent = true, description = "Check whether the first date is earlier than the second.",
            panel = "value, other | include_time, time_zone")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "True when the first date is earlier.")
    public Map<String, Object> isBeforeAction(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "other", type = OoaType.DATETIME, description = "Date to compare with.") LocalDateTime other,
            @Input(name = "include_time", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = INCLUDE_TIME) boolean includeTime,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = CMP_ZONE) String tz) {
        return Out.of("result", isBefore(ctx, value, other, includeTime, tz));
    }

    @Action(id = "is_equal", name = "Is equal", category = "Date and time", idempotent = true, description = "Check whether two dates are the same moment.",
            panel = "value, other | include_time, time_zone")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "True when the dates are equal.")
    public Map<String, Object> isEqualAction(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "other", type = OoaType.DATETIME, description = "Date to compare with.") LocalDateTime other,
            @Input(name = "include_time", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = INCLUDE_TIME) boolean includeTime,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = CMP_ZONE) String tz) {
        return Out.of("result", isEqual(ctx, value, other, includeTime, tz));
    }

    @Action(id = "is_leap_year", name = "Is leap year", category = "Date and time", idempotent = true,
            description = "Check whether the year of a date, or a year number, is a leap year.",
            panel = "(Check: A date = value / A year number = year)")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "True for a leap year.")
    public Map<String, Object> isLeapYear(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, required = false, description = "Date whose year to check.") LocalDateTime value,
            @Input(name = "year", type = OoaType.NUMBER, required = false, description = "Year number; used when no date is given.") Integer year) {
        if (value == null && year == null) throw new ActionException("DATETIME.MISSING", "Give a date or a year", "Fill in 'value' or 'year'.");
        return Out.of("result", Year.isLeap(value != null ? value.getYear() : year));
    }

    @Action(id = "subtract", name = "Subtract from date", category = "Date and time", idempotent = true, description = "Subtract an amount of time from a date.",
            panel = "value, amount, unit, holidays")
    @Output(name = "result", type = OoaType.DATETIME, description = "New date and time.")
    public Map<String, Object> subtract(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Start date.") LocalDateTime value,
            @Input(name = "amount", type = OoaType.NUMBER, description = "Amount to subtract.") long amount,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"milliseconds", "seconds", "minutes", "hours", "days", "weeks", "months", "years", "business_days"},
                    description = "Unit of the amount; business_days skips Saturdays, Sundays and the holidays.") String unit,
            @Input(name = "holidays", type = OoaType.LIST, required = false, visibleWhen = "unit=business_days",
                    description = "Dates that are not working days, e.g. [\"2026-12-25\"].") List<Object> holidays) {
        if (unit != null && !java.util.Arrays.asList(UNITS).contains(unit))
            throw new ActionException("DATETIME.BAD_UNIT", "Unknown unit '" + unit + "'", "Use one of " + String.join(", ", UNITS) + ".");
        return add(ctx, value, Math.negateExact(amount), unit == null ? "days" : unit, holidays);
    }

    public Map<String, Object> subtract(ActionContext ctx, LocalDateTime value, long amount, String unit) { return subtract(ctx, value, amount, unit, null); }

    /** Moves by whole working days (Monday to Friday, not a holiday), keeping the time of day; 0 keeps the date. */
    static LocalDateTime plusBusinessDays(LocalDateTime value, long amount, List<Object> holidays) {
        Set<LocalDate> off = holidaySet(holidays);
        LocalDateTime d = value;
        long step = Long.signum(amount);
        for (long left = Math.abs(amount); left > 0; ) {
            d = d.plusDays(step);
            if (workingDay(d.toLocalDate(), off)) left--;
        }
        return d;
    }

    private static boolean workingDay(LocalDate d, Set<LocalDate> off) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !off.contains(d);
    }

    private static Set<LocalDate> holidaySet(List<Object> holidays) {
        Set<LocalDate> off = new HashSet<>();
        if (holidays != null) for (Object h : holidays) {
            if (h == null || "".equals(h)) continue;
            try { off.add(h instanceof LocalDateTime t ? t.toLocalDate() : LocalDate.parse(String.valueOf(h).strip().substring(0, Math.min(10, String.valueOf(h).strip().length())))); }
            catch (RuntimeException e) { throw new ActionException("DATETIME.BAD_HOLIDAY", "Holiday '" + h + "' is not a date", "Write holidays as yyyy-MM-dd, e.g. 2026-12-25."); }
        }
        return off;
    }

    @Action(id = "get_part", name = "Get part of date", category = "Date and time", idempotent = true,
            description = "Return one part of a date, such as the year, month, day of week, week of year or quarter, in a time zone.",
            panel = "value, part | time_zone, locale")
    @Output(name = "result", description = "The part: a Number, or text for day_name and month_name.")
    public Map<String, Object> getPart(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date (UTC).") LocalDateTime value,
            @Input(name = "part", type = OoaType.STRING, enumValues = {"year", "month", "day", "hour", "minute", "second", "millisecond", "day_of_week", "day_name",
                    "month_name", "day_of_year", "week_of_year", "quarter", "days_in_month", "days_in_year"},
                    description = "Which part to return; day_of_week is 1 for Monday to 7 for Sunday, week_of_year follows ISO 8601.") String part,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone the part is read in.") String tz,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = "Language for day_name and month_name.") String locale) {
        ZonedDateTime z = need(value, "value").atOffset(ZoneOffset.UTC).atZoneSameInstant(zone(tz == null ? "UTC" : tz));
        Object r = switch (part == null ? "" : part) {
            case "year" -> z.getYear(); case "month" -> z.getMonthValue(); case "day" -> z.getDayOfMonth();
            case "hour" -> z.getHour(); case "minute" -> z.getMinute(); case "second" -> z.getSecond(); case "millisecond" -> z.getNano() / 1_000_000;
            case "day_of_week" -> z.getDayOfWeek().getValue();
            case "day_name" -> z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, locale(locale));
            case "month_name" -> z.getMonth().getDisplayName(java.time.format.TextStyle.FULL, locale(locale));
            case "day_of_year" -> z.getDayOfYear();
            case "week_of_year" -> z.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "quarter" -> z.get(java.time.temporal.IsoFields.QUARTER_OF_YEAR);
            case "days_in_month" -> z.toLocalDate().lengthOfMonth();
            case "days_in_year" -> z.toLocalDate().lengthOfYear();
            default -> throw new ActionException("DATETIME.BAD_PART", "Unknown date part '" + part + "'", "Use year, month, day, hour, minute, second, day_of_week, week_of_year, quarter ...");
        };
        return Out.of("result", r instanceof Integer i ? BigDecimal.valueOf(i) : r);
    }

    private static LocalDateTime startOf(LocalDateTime v, String unit) {
        LocalDate d = v.toLocalDate();
        return switch (unit) {
            case "day" -> d.atStartOfDay();
            case "week" -> d.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case "month" -> d.withDayOfMonth(1).atStartOfDay();
            case "year" -> d.withDayOfYear(1).atStartOfDay();
            default -> throw new ActionException("DATETIME.BAD_UNIT", "Unknown period '" + unit + "'", "Use day, week, month or year.");
        };
    }

    @Action(id = "start_of", name = "Start of period", category = "Date and time", idempotent = true,
            description = "Return midnight at the start of the day, week (Monday), month or year containing a date.",
            panel = "value, unit")
    @Output(name = "result", type = OoaType.DATETIME, description = "Start of the period.")
    public Map<String, Object> startOf(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date.") LocalDateTime value,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"day\"", enumValues = {"day", "week", "month", "year"}, description = "Period.") String unit) {
        return Out.of("result", startOf(need(value, "value"), unit == null ? "day" : unit));
    }

    @Action(id = "end_of", name = "End of period", category = "Date and time", idempotent = true,
            description = "Return the last second (23:59:59) of the day, week (Sunday), month or year containing a date.",
            panel = "value, unit")
    @Output(name = "result", type = OoaType.DATETIME, description = "End of the period.")
    public Map<String, Object> endOf(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date.") LocalDateTime value,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"day\"", enumValues = {"day", "week", "month", "year"}, description = "Period.") String unit) {
        String u = unit == null ? "day" : unit;
        LocalDateTime s = startOf(need(value, "value"), u);
        LocalDateTime next = switch (u) { case "week" -> s.plusWeeks(1); case "month" -> s.plusMonths(1); case "year" -> s.plusYears(1); default -> s.plusDays(1); };
        return Out.of("result", next.minusSeconds(1));
    }
}
