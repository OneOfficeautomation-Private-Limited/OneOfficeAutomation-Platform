package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/** DateTime values are UTC on the platform; time zones apply when reading or formatting. */
@OoaPackage(id = "ooa.datetime", name = "Date and time", version = "1.0.0", description = "Current time, add, difference, format and time zone conversion.")
public final class DateTimePackage {
    private static final String[] UNITS = {"seconds", "minutes", "hours", "days", "weeks", "months", "years"};

    private static ZoneId zone(String z) {
        try { return ZoneId.of(z); }
        catch (DateTimeException e) { throw new ActionException("DATETIME.BAD_ZONE", "Unknown time zone '" + z + "'", "Use an IANA name such as Europe/London or Asia/Kolkata."); }
    }

    @Action(id = "now", name = "Get current date and time", category = "Date and time", description = "Return the current date and time (UTC).")
    @Output(name = "value", type = OoaType.DATETIME, description = "Current UTC date and time.")
    public Map<String, Object> now(ActionContext ctx) {
        return Out.of("value", LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    @Action(id = "add", name = "Add to date", category = "Date and time", idempotent = true, description = "Add (or subtract, with a negative amount) a duration to a date.")
    @Output(name = "result", type = OoaType.DATETIME, description = "New date and time.")
    public Map<String, Object> add(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Start date.") LocalDateTime value,
            @Input(name = "amount", type = OoaType.NUMBER, description = "Amount to add; negative to subtract.") long amount,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"seconds", "minutes", "hours", "days", "weeks", "months", "years"},
                    description = "Unit of the amount.") String unit) {
        LocalDateTime r = switch (unit) {
            case "seconds" -> value.plusSeconds(amount); case "minutes" -> value.plusMinutes(amount); case "hours" -> value.plusHours(amount);
            case "weeks" -> value.plusWeeks(amount); case "months" -> value.plusMonths(amount); case "years" -> value.plusYears(amount);
            default -> value.plusDays(amount);
        };
        return Out.of("result", r);
    }

    @Action(id = "difference", name = "Difference between dates", category = "Date and time", idempotent = true,
            description = "Return end minus start in the chosen unit (may be fractional).")
    @Output(name = "result", type = OoaType.NUMBER, description = "Difference.")
    public Map<String, Object> difference(ActionContext ctx,
            @Input(name = "start", type = OoaType.DATETIME, description = "Earlier date.") LocalDateTime start,
            @Input(name = "end", type = OoaType.DATETIME, description = "Later date.") LocalDateTime end,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"seconds", "minutes", "hours", "days", "weeks", "months", "years"},
                    description = "Result unit.") String unit) {
        if (unit.equals("months")) return Out.of("result", BigDecimal.valueOf(ChronoUnit.MONTHS.between(start, end)));
        if (unit.equals("years")) return Out.of("result", BigDecimal.valueOf(ChronoUnit.YEARS.between(start, end)));
        long per = switch (unit) { case "seconds" -> 1; case "minutes" -> 60; case "hours" -> 3600; case "weeks" -> 604800; default -> 86400; };
        BigDecimal secs = BigDecimal.valueOf(Duration.between(start, end).getSeconds());
        return Out.of("result", secs.divide(BigDecimal.valueOf(per), 6, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    @Action(id = "format", name = "Format date", category = "Date and time", idempotent = true,
            description = "Format a date as text in a time zone, using a pattern such as dd/MM/yyyy HH:mm.")
    @Output(name = "text", type = OoaType.STRING, description = "Formatted date.")
    public Map<String, Object> format(ActionContext ctx,
            @Input(name = "value", type = OoaType.DATETIME, description = "Date to format (UTC).") LocalDateTime value,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"yyyy-MM-dd\"", description = "Pattern, e.g. dd/MM/yyyy, yyyy-MM-dd HH:mm, MMM d, yyyy.") String pattern,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "IANA time zone for display, e.g. Asia/Kolkata.") String tz) {
        try {
            ZonedDateTime z = value.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone(tz));
            return Out.of("text", z.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)));
        } catch (IllegalArgumentException e) {
            throw new ActionException("DATETIME.BAD_PATTERN", "Invalid date pattern '" + pattern + "': " + e.getMessage(), "Use letters like yyyy, MM, dd, HH, mm, ss.");
        }
    }

    @Action(id = "parse", name = "Text to date", category = "Date and time", idempotent = true,
            description = "Read a date from text using a pattern, interpreting it in a time zone; the result is UTC.")
    @Output(name = "value", type = OoaType.DATETIME, description = "Parsed date (UTC).")
    public Map<String, Object> parse(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text such as 18/10/2026.") String text,
            @Input(name = "pattern", type = OoaType.STRING, description = "Pattern of the text, e.g. dd/MM/yyyy.") String pattern,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone the text is in.") String tz) {
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
            LocalDateTime local = pattern.contains("H") || pattern.contains("h") ? LocalDateTime.parse(text.trim(), f) : LocalDate.parse(text.trim(), f).atStartOfDay();
            return Out.of("value", local.atZone(zone(tz)).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new ActionException("DATETIME.PARSE_FAILED", "Cannot read '" + text + "' with pattern '" + pattern + "'", "Check the pattern matches the text exactly.");
        }
    }

    @Action(id = "convert_time_zone", name = "Convert time zone", category = "Date and time", idempotent = true,
            description = "Show a UTC date as local time in another zone, as ISO text with offset.")
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
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
            if (pattern.contains("H")) LocalDateTime.parse(text.trim(), f); else LocalDate.parse(text.trim(), f);
            return true;
        } catch (RuntimeException e) { return false; }
    }

    @Condition(id = "is_business_hours", name = "Within business hours", description = "True on weekdays between the start and end hours in the time zone.")
    public boolean isBusinessHours(ActionContext ctx,
            @Input(name = "start_hour", type = OoaType.NUMBER, required = false, defaultValue = "9", description = "Start hour (24h).") int start,
            @Input(name = "end_hour", type = OoaType.NUMBER, required = false, defaultValue = "17", description = "End hour (24h).") int end,
            @Input(name = "time_zone", type = OoaType.STRING, required = false, defaultValue = "\"UTC\"", description = "Time zone.") String tz) {
        ZonedDateTime z = ZonedDateTime.now(zone(tz));
        DayOfWeek d = z.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && z.getHour() >= start && z.getHour() < end;
    }
}
