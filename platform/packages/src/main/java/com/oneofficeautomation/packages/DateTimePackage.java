package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** DateTime values are UTC on the platform; time zones apply when reading or formatting. */
@OoaPackage(id = "ooa.datetime", name = "Date and time", version = "1.0.0", description = "Current time, add, difference, format, time zone conversion and working days with business calendars.")
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

    /** A time zone by name; "local" (any case) is the runner's own, as AA's dates are (command review D1, 10 Nov 2026). */
    static ZoneId zone(String z) {
        if (z != null && z.strip().equalsIgnoreCase("local")) return ZoneId.systemDefault();
        try { return ZoneId.of(z == null ? "UTC" : z.strip()); }
        catch (DateTimeException e) { throw new ActionException("DATETIME.BAD_ZONE", "Unknown time zone '" + z + "'", "Use an IANA name such as Europe/London or Asia/Kolkata."); }
    }

    @Action(id = "now", name = "Get current date and time", category = "Date and time",
            description = "Return the current date and time in UTC. Every DateTime in OOA is UTC; use Format date with a time zone to show local time, or the ISO_INSTANT / ISO_OFFSET_DATE_TIME standard for text with Z or an offset for other systems.")
    @Output(name = "value", type = OoaType.DATETIME, description = "Current date and time in UTC.")
    public Map<String, Object> now(ActionContext ctx) {
        return Out.of("value", LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Action(id = "add", name = "Add to date", category = "Date and time", idempotent = true, description = "Add (or subtract, with a negative amount) a duration to a date.",
            panel = "value, amount, unit, holidays")
    @Output(name = "result", type = OoaType.DATETIME, description = "New date and time.")
    public Map<String, Object> add(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Start date.") LocalDateTime value,
            @Input(name = "amount", type = OoaType.NUMBER, description = "Amount to add; negative to subtract. Fractions are exact for milliseconds to weeks (1.5 days = 36 hours); months, years and business days take whole numbers.") java.math.BigDecimal amount,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"milliseconds", "seconds", "minutes", "hours", "days", "weeks", "months", "years", "business_days"},
                    description = "Unit of the amount; business_days skips Saturdays, Sundays and the holidays.") String unit,
            @Input(name = "holidays", type = OoaType.LIST, required = false, visibleWhen = "unit=business_days",
                    description = "Dates that are not working days, e.g. [\"2026-12-25\"].") List<Object> holidays) {
        String u = unit == null || unit.isBlank() ? "days" : unit;
        boolean whole = amount.stripTrailingZeros().scale() <= 0;
        if (!whole && List.of("months", "years", "business_days").contains(u))
            throw new ActionException("DATETIME.BAD_AMOUNT", "Add to date: " + u.replace('_', ' ') + " must be a whole number, got " + amount.stripTrailingZeros().toPlainString(),
                    "Round the amount first, or use a smaller unit (e.g. days).");
        if ("business_days".equals(u)) return Out.of("result", plusBusinessDays(value, amount.longValueExact(), holidays));
        if (whole) return add(ctx, value, amount.longValueExact(), u);
        // fractions used to be cut off silently (1.5 days added 1 day): add the exact duration instead
        long nanosPerUnit = switch (u) {
            case "milliseconds" -> 1_000_000L; case "seconds" -> 1_000_000_000L; case "minutes" -> 60_000_000_000L;
            case "hours" -> 3_600_000_000_000L; case "weeks" -> 7 * 86_400_000_000_000L; default -> 86_400_000_000_000L;
        };
        java.math.BigDecimal nanos = amount.multiply(java.math.BigDecimal.valueOf(nanosPerUnit)).setScale(0, java.math.RoundingMode.HALF_EVEN);
        return Out.of("result", value.plusNanos(nanos.longValueExact()));
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
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone to show the date in: local (the runner's own), UTC or a name such as Asia/Kolkata.") String tz,
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
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone the text is in: local (the runner's own), UTC or a name such as Asia/Kolkata.") String tz,
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
            @Input(name = "time_zone", type = OoaType.STRING, description = "Time zone to convert to: local (the runner's own), UTC or a name such as Europe/London.") String tz) {
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
            @Input(name = "amount", type = OoaType.NUMBER, description = "Amount to subtract. Fractions are exact for milliseconds to weeks; months, years and business days take whole numbers.") BigDecimal amount,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"milliseconds", "seconds", "minutes", "hours", "days", "weeks", "months", "years", "business_days"},
                    description = "Unit of the amount; business_days skips Saturdays, Sundays and the holidays.") String unit,
            @Input(name = "holidays", type = OoaType.LIST, required = false, visibleWhen = "unit=business_days",
                    description = "Dates that are not working days, e.g. [\"2026-12-25\"].") List<Object> holidays) {
        if (unit != null && !java.util.Arrays.asList(UNITS).contains(unit))
            throw new ActionException("DATETIME.BAD_UNIT", "Unknown unit '" + unit + "'", "Use one of " + String.join(", ", UNITS) + ".");
        return add(ctx, value, amount.negate(), unit == null ? "days" : unit, holidays);
    }

    public Map<String, Object> subtract(ActionContext ctx, LocalDateTime value, long amount, String unit) { return subtract(ctx, value, BigDecimal.valueOf(amount), unit, null); }

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
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone the part is read in: local (the runner's own), UTC or a name such as Asia/Kolkata.") String tz,
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

    // ---- Working days with business calendars (gap G34) ----

    private static final String CAL = "Name of a holiday calendar in One CommandCenter (its holidays and weekend). Read once per run as the run's user; "
            + "needs the agent to run from One CommandCenter. Leave empty to give the holidays and weekend here.";
    private static final String HOLS = "Dates that are not working days, as yyyy-MM-dd, e.g. [\"2026-12-25\"]. Added to the calendar's dates when a calendar is named too.";
    private static final String WKND = "Days of the week that are not working days, e.g. SAT,SUN (the default) or FRI,SAT; NONE when every day is a working day. "
            + "When given with a calendar, it replaces the calendar's weekend.";
    private static final String WD_ZONE = "Time zone whose calendar date counts; the time of day is kept in this zone.";
    private static final String DAY_ZONE = "Time zone whose calendar date counts.";
    private static final String WD_PANEL = "(Working days from: A holiday calendar = calendar / Weekend and holidays I give = weekend, holidays)";

    /** The calendars this run has read from One CommandCenter, so each is asked for once per run. */
    static final class CalendarCache {
        final Map<String, Map<String, Object>> byName = new java.util.concurrent.ConcurrentHashMap<>();
    }
    static final String CALENDAR_CACHE = "ooa.datetime.calendars";

    @SuppressWarnings("unchecked")
    static Map<String, Object> commandCenterCalendar(ActionContext ctx, String name) {
        CalendarCache cache;
        if (ctx.sessions().get(CALENDAR_CACHE) instanceof CalendarCache c) cache = c;
        else { cache = new CalendarCache(); ctx.sessions().put(CALENDAR_CACHE, cache); }
        Map<String, Object> hit = cache.byName.get(name);
        if (hit != null) return hit;
        java.util.function.Function<Map<String, Object>, Map<String, Object>> cc;
        try { cc = ctx.service("command_center", java.util.function.Function.class); }
        catch (OoaException e) {
            throw new ActionException("DATETIME.NO_COMMAND_CENTER", "The holiday calendar '" + name + "' is kept in One CommandCenter, and this run is not connected to it",
                    "Run the agent from One CommandCenter, or leave the calendar empty and give the holidays and weekend on the step.");
        }
        Map<String, Object> cal;
        try { cal = cc.apply(Map.of("call", "calendar", "args", Map.of("name", name))); }
        catch (OoaException e) {
            if ("CALENDAR.NOT_FOUND".equals(e.code()))
                throw new ActionException("DATETIME.CALENDAR_NOT_FOUND", "There is no holiday calendar '" + name + "' in One CommandCenter",
                        "Check the name under Manage > Schedules > Holiday calendars (names are exact), or give the holidays and weekend on the step.");
            if ("JOBS.BAD_CALL".equals(e.code()))
                throw new ActionException("DATETIME.NO_COMMAND_CENTER", "This One CommandCenter is too old to give holiday calendars to agents",
                        "Update One CommandCenter, or give the holidays and weekend on the step.");
            throw e;
        }
        cache.byName.put(name, cal);
        ctx.info("Read the holiday calendar " + name + " from One CommandCenter");
        return cal;
    }

    /** The business calendar a step means: a named calendar, the holidays and weekend given, or both together. */
    static WorkingDays calendar(ActionContext ctx, String calendar, List<Object> holidays, String weekend) {
        Set<LocalDate> off = new HashSet<>(WorkingDays.holidays(holidays));
        List<DayOfWeek> wk;
        if (calendar != null && !calendar.isBlank()) {
            Map<String, Object> c = commandCenterCalendar(ctx, calendar.strip());
            off.addAll(WorkingDays.holidays(c.get("dates") instanceof Collection<?> l ? l : List.of()));
            Object cw = c.get("weekend");
            if (weekend != null && !weekend.isBlank()) wk = WorkingDays.weekend(weekend);
            else if (cw instanceof Collection<?> l && l.isEmpty()) wk = List.of();   // the calendar has no weekend
            else wk = WorkingDays.weekend(cw);
        } else wk = WorkingDays.weekend(weekend);
        return new WorkingDays(wk, off);
    }

    private static ZonedDateTime local(LocalDateTime value, String tz) {
        return need(value, "value").atOffset(ZoneOffset.UTC).atZoneSameInstant(zone(tz == null || tz.isBlank() ? "UTC" : tz));
    }

    /** The same local time of day on another date, back in UTC. */
    private static LocalDateTime onDate(ZonedDateTime z, LocalDate d) {
        return ZonedDateTime.of(d, z.toLocalTime(), z.getZone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @Action(id = "add_working_days", name = "Add working days", category = "Date and time", idempotent = true,
            description = "Move a date by a number of working days, skipping the weekend and holidays of a business calendar; a negative number moves back. "
                + "The start date itself is not counted (1 from Friday is Monday) and 0 keeps the date. The time of day is kept.",
            panel = "value, days, " + WD_PANEL + " | time_zone")
    @Output(name = "result", type = OoaType.DATETIME, description = "The date that many working days later (or earlier).")
    public Map<String, Object> addWorkingDays(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Start date.") LocalDateTime value,
            @Input(name = "days", type = OoaType.NUMBER, description = "Working days to add, a whole number; negative to go back.") BigDecimal days,
            @Input(name = "calendar", type = OoaType.STRING, required = false, description = CAL) String calendar,
            @Input(name = "holidays", type = OoaType.LIST, required = false, description = HOLS) List<Object> holidays,
            @Input(name = "weekend", type = OoaType.STRING, required = false, description = WKND) String weekend,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = WD_ZONE) String tz) {
        if (days == null) throw new ActionException("DATETIME.MISSING", "Give the number of working days", "Fill in 'days', e.g. 5 or -2.");
        if (days.stripTrailingZeros().scale() > 0)
            throw new ActionException("DATETIME.BAD_AMOUNT", "Add working days: days must be a whole number, got " + days.stripTrailingZeros().toPlainString(), "Round the number first.");
        ZonedDateTime z = local(value, tz);
        WorkingDays wd = calendar(ctx, calendar, holidays, weekend);
        long n;
        try { n = days.longValueExact(); } catch (ArithmeticException e) { n = Long.MAX_VALUE; }
        return Out.of("result", onDate(z, wd.plus(z.toLocalDate(), n)));
    }

    @Action(id = "working_days_between", name = "Count working days", category = "Date and time", idempotent = true,
            description = "Count the working days between two dates in a business calendar: the days after the start up to and including the end "
                + "(the start too, when chosen). The count is negative when the end is before the start.",
            panel = "start, end, " + WD_PANEL + " | include_start, time_zone")
    @Output(name = "result", type = OoaType.NUMBER, description = "Number of working days.")
    public Map<String, Object> workingDaysBetween(ActionContext ctx,
            @Input(name = "start", type = OoaType.DATETIME, description = "First date.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "Last date.") LocalDateTime end,
            @Input(name = "calendar", type = OoaType.STRING, required = false, description = CAL) String calendar,
            @Input(name = "holidays", type = OoaType.LIST, required = false, description = HOLS) List<Object> holidays,
            @Input(name = "weekend", type = OoaType.STRING, required = false, description = WKND) String weekend,
            @Input(name = "include_start", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Count the start date too when it is a working day (Monday to Friday of one week is then 5, not 4).") boolean includeStart,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = DAY_ZONE) String tz) {
        need(start, "start"); need(end, "end");
        WorkingDays wd = calendar(ctx, calendar, holidays, weekend);
        return Out.of("result", BigDecimal.valueOf(wd.between(local(start, tz).toLocalDate(), local(end, tz).toLocalDate(), includeStart)));
    }

    @Action(id = "next_working_day", name = "Next working day", category = "Date and time", idempotent = true,
            description = "Return the first working day after a date in a business calendar, or the date itself when it is a working day and same_day is on. The time of day is kept.",
            panel = "value, " + WD_PANEL + " | same_day, time_zone")
    @Output(name = "result", type = OoaType.DATETIME, description = "The next working day.")
    public Map<String, Object> nextWorkingDay(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to start from.") LocalDateTime value,
            @Input(name = "calendar", type = OoaType.STRING, required = false, description = CAL) String calendar,
            @Input(name = "holidays", type = OoaType.LIST, required = false, description = HOLS) List<Object> holidays,
            @Input(name = "weekend", type = OoaType.STRING, required = false, description = WKND) String weekend,
            @Input(name = "same_day", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Return the date itself when it is a working day (e.g. a due date that moves only off holidays and weekends).") boolean sameDay,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = WD_ZONE) String tz) {
        ZonedDateTime z = local(value, tz);
        return Out.of("result", onDate(z, calendar(ctx, calendar, holidays, weekend).nextWorking(z.toLocalDate(), sameDay, false)));
    }

    @Action(id = "previous_working_day", name = "Previous working day", category = "Date and time", idempotent = true,
            description = "Return the last working day before a date in a business calendar, or the date itself when it is a working day and same_day is on. The time of day is kept.",
            panel = "value, " + WD_PANEL + " | same_day, time_zone")
    @Output(name = "result", type = OoaType.DATETIME, description = "The previous working day.")
    public Map<String, Object> previousWorkingDay(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to start from.") LocalDateTime value,
            @Input(name = "calendar", type = OoaType.STRING, required = false, description = CAL) String calendar,
            @Input(name = "holidays", type = OoaType.LIST, required = false, description = HOLS) List<Object> holidays,
            @Input(name = "weekend", type = OoaType.STRING, required = false, description = WKND) String weekend,
            @Input(name = "same_day", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Return the date itself when it is a working day.") boolean sameDay,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = WD_ZONE) String tz) {
        ZonedDateTime z = local(value, tz);
        return Out.of("result", onDate(z, calendar(ctx, calendar, holidays, weekend).nextWorking(z.toLocalDate(), sameDay, true)));
    }

    @Action(id = "is_working_day", name = "Is working day", category = "Date and time", idempotent = true,
            description = "Check whether a date is a working day in a business calendar: not on its weekend and not one of its holidays.",
            panel = "value, " + WD_PANEL + " | time_zone")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "True on a working day.")
    @Output(name = "reason", type = OoaType.STRING, description = "Why it is not a working day: holiday or weekend (holiday when both); empty on a working day.")
    public Map<String, Object> isWorkingDayAction(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "calendar", type = OoaType.STRING, required = false, description = CAL) String calendar,
            @Input(name = "holidays", type = OoaType.LIST, required = false, description = HOLS) List<Object> holidays,
            @Input(name = "weekend", type = OoaType.STRING, required = false, description = WKND) String weekend,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = DAY_ZONE) String tz) {
        LocalDate d = local(value, tz).toLocalDate();
        WorkingDays wd = calendar(ctx, calendar, holidays, weekend);
        String reason = wd.isHoliday(d) ? "holiday" : wd.isWeekend(d) ? "weekend" : "";
        return Out.of("result", reason.isEmpty(), "reason", reason);
    }

    @Condition(id = "is_working_day", name = "Is working day",
            description = "True when the date is a working day in a business calendar: not on its weekend and not one of its holidays.")
    public boolean isWorkingDay(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to check.") LocalDateTime value,
            @Input(name = "calendar", type = OoaType.STRING, required = false, description = CAL) String calendar,
            @Input(name = "holidays", type = OoaType.LIST, required = false, description = HOLS) List<Object> holidays,
            @Input(name = "weekend", type = OoaType.STRING, required = false, description = WKND) String weekend,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = DAY_ZONE) String tz) {
        return calendar(ctx, calendar, holidays, weekend).isWorking(local(value, tz).toLocalDate());
    }
}
