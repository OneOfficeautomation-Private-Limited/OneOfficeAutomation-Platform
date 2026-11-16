package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * One command for every type change (16 Nov 2026): text, number, Yes/No, date, list, dictionary and JSON text into any
 * of the others, with the formats people need (a date pattern, a locale for numbers, a list separator) and a fallback
 * value instead of an error when you prefer.
 */
@OoaPackage(id = "ooa.convert", name = "Convert", version = "1.0.0", description = "Change a value from one type to another: text, number, Yes/No, date, list, dictionary or JSON.")
public final class ConvertPackage {

    @Action(id = "convert", name = "Convert value", category = "Convert", idempotent = true,
            description = "Turn a value into another type: text into a number or date, a list into text, JSON text into a dictionary and so on, with a fallback when it cannot be converted.",
            panel = "value, to, date_pattern, locale, separator | on_error, fallback")
    @Output(name = "result", type = OoaType.ANY, description = "The converted value.")
    @Output(name = "ok", type = OoaType.BOOLEAN, description = "False when the fallback was used.")
    public Map<String, Object> convert(ActionContext ctx,
            @Input(name = "value", type = OoaType.ANY, required = false, description = "The value to convert: a variable or something typed in.") Object value,
            @Input(name = "to", type = OoaType.STRING, defaultValue = "\"text\"", label = "Convert to",
                    enumValues = {"text", "number", "boolean", "date_time", "list", "dictionary", "json_text"},
                    description = "The type it becomes. Yes/No is a Boolean; JSON text writes a list or dictionary as JSON.") String to,
            @Input(name = "date_pattern", type = OoaType.STRING, required = false, visibleWhen = "to=date_time|text", example = "dd/MM/yyyy",
                    description = "Pattern to read a date from text, or to write a date as text, e.g. dd/MM/yyyy or yyyy-MM-dd HH:mm; empty reads ISO and common forms.") String datePattern,
            @Input(name = "locale", type = OoaType.STRING, required = false, visibleWhen = "to=number|text", example = "de-DE",
                    description = "Read or write numbers the locale's way, e.g. de-DE reads 1.234,50; empty: 1,234.50 or 1234.50.") String locale,
            @Input(name = "separator", type = OoaType.STRING, required = false, defaultValue = "\",\"", visibleWhen = "to=list|text",
                    description = "Text to list: split at this (spaces around items are trimmed). List to text: put between items. A line break is \\n.") String separator,
            @Input(name = "on_error", type = OoaType.STRING, required = false, defaultValue = "\"fail\"", enumValues = {"fail", "fallback"},
                    description = "When the value cannot be converted: fail with CONVERT.FAILED, or give the fallback value.") String onError,
            @Input(name = "fallback", type = OoaType.ANY, required = false, visibleWhen = "on_error=fallback", description = "The value to give when the conversion fails.") Object fallback) {
        String target = to == null ? "text" : to;
        try {
            return Out.of("result", to(value, target, datePattern, locale, separator), "ok", true);
        } catch (RuntimeException e) {
            if ("fallback".equals(onError)) return Out.of("result", fallback, "ok", false);
            if (e instanceof ActionException ae) throw ae;
            String why = e instanceof OoaException oe ? oe.getMessage() : e.getMessage();
            throw new ActionException("CONVERT.FAILED", "Cannot convert '" + shortText(value) + "' to " + target.replace('_', ' ') + (why == null ? "" : ": " + why),
                    "Check the value and the format options, or choose to give a fallback value.");
        }
    }

    static Object to(Object v, String target, String datePattern, String locale, String separator) {
        String sep = StringPackage.unescapeBreaks(separator == null ? "," : separator);
        return switch (target) {
            case "text" -> toText(v, datePattern, locale, sep);
            case "number" -> toNumber(v, locale);
            case "boolean" -> {
                if (v == null || (v instanceof String s && s.isBlank())) yield false;
                if (v instanceof Number n) yield new BigDecimal(n.toString()).signum() != 0;
                yield Values.coerce(v instanceof String s ? s.strip() : v, "Boolean", "value");
            }
            case "date_time" -> toDate(v, datePattern);
            case "list" -> toList(v, sep);
            case "dictionary" -> toDictionary(v);
            case "json_text" -> JsonLite.write(Values.toJsonable(v instanceof String s && looksLikeJson(s) ? JsonLite.parse(s) : v));
            default -> throw new ActionException("CONVERT.BAD_TYPE", "Unknown type '" + target + "'", "Use text, number, boolean, date_time, list, dictionary or json_text.");
        };
    }

    private static String toText(Object v, String datePattern, String locale, String sep) {
        if (v == null) return "";
        if (v instanceof LocalDateTime || v instanceof LocalDate || v instanceof java.time.temporal.TemporalAccessor && !(v instanceof java.time.LocalTime)) {
            LocalDateTime dt = Values.toDateTime(v);
            return datePattern == null || datePattern.isBlank() ? Values.text(v) : dt.format(pattern(datePattern));
        }
        if (v instanceof BigDecimal n && locale != null && !locale.isBlank()) {
            NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag(locale));
            f.setMaximumFractionDigits(Math.max(0, n.stripTrailingZeros().scale()));
            return NumberPackage.southAsianGrouping(f.format(n), Locale.forLanguageTag(locale));
        }
        if (v instanceof Collection<?> c && !(v instanceof Map)) {
            List<String> parts = new ArrayList<>();
            for (Object o : c) parts.add(Values.text(o));
            return String.join(sep, parts);
        }
        if (v instanceof Map<?, ?> m && !(m.containsKey("columns") && m.containsKey("rows"))) return JsonLite.write(Values.toJsonable(v));
        return Values.text(v);
    }

    private static BigDecimal toNumber(Object v, String locale) {
        if (v instanceof BigDecimal n) return n;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        String s = Values.text(v);
        if (s == null || s.isBlank()) throw new ActionException("CONVERT.FAILED", "The value is empty", "Give a number, or choose to give a fallback value.");
        s = s.strip().replace(' ', ' ');
        if (locale != null && !locale.isBlank()) {
            Locale loc = Locale.forLanguageTag(locale);
            DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(loc);
            String plain = s.replace(String.valueOf(sym.getGroupingSeparator()), "").replace(" ", "").replace(sym.getDecimalSeparator(), '.');
            try { return new BigDecimal(plain); }
            catch (NumberFormatException e) {
                ParsePosition pos = new ParsePosition(0);
                Number n = NumberFormat.getNumberInstance(loc).parse(s, pos);
                if (n == null || pos.getIndex() != s.length()) throw new ActionException("CONVERT.FAILED", "'" + s + "' is not a number in " + locale, "Check the separators for this locale.");
                return new BigDecimal(n.toString());
            }
        }
        String t = s.replace(",", "").replace(" ", "");
        boolean percent = t.endsWith("%");
        if (percent) t = t.substring(0, t.length() - 1);
        t = t.replaceAll("^[^0-9+\\-.]+|[^0-9.]+$", "");   // currency signs and units around the number
        try {
            BigDecimal n = new BigDecimal(t);
            return percent ? n.movePointLeft(2) : n;
        } catch (NumberFormatException e) {
            throw new ActionException("CONVERT.FAILED", "'" + s + "' is not a number", "Give digits with an optional sign and decimal point, or set the locale.");
        }
    }

    private static LocalDateTime toDate(Object v, String datePattern) {
        if (v == null || (v instanceof String s && s.isBlank())) throw new ActionException("CONVERT.FAILED", "The value is empty", "Give a date, or choose to give a fallback value.");
        if (datePattern != null && !datePattern.isBlank() && v instanceof String s) {
            DateTimeFormatter f = pattern(datePattern);
            try { return LocalDateTime.parse(s.strip(), f); }
            catch (DateTimeParseException e) {
                try { return LocalDate.parse(s.strip(), f).atStartOfDay(); }
                catch (DateTimeParseException e2) {
                    throw new ActionException("CONVERT.FAILED", "'" + s + "' does not fit the pattern " + datePattern, "Make the pattern match the text, e.g. dd/MM/yyyy for 16/11/2026.");
                }
            }
        }
        return Values.toDateTime(v);
    }

    private static DateTimeFormatter pattern(String p) {
        try { return DateTimeFormatter.ofPattern(p, Locale.ENGLISH); }
        catch (IllegalArgumentException e) { throw new ActionException("CONVERT.BAD_PATTERN", "The date pattern '" + p + "' is not valid: " + e.getMessage(), "Use letters such as dd, MM, yyyy, HH, mm, e.g. dd/MM/yyyy."); }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toList(Object v, String sep) {
        if (v == null) return new ArrayList<>();
        if (v instanceof Map<?, ?> m && m.containsKey("columns") && m.containsKey("rows")) return new ArrayList<>((List<Object>) m.get("rows"));
        if (v instanceof List<?> l) return new ArrayList<>(l);
        if (v instanceof Collection<?> c) return new ArrayList<>(c);
        if (v instanceof Map<?, ?> m) return new ArrayList<>(m.values());
        String s = Values.text(v);
        if (looksLikeJson(s) && s.strip().startsWith("[")) return new ArrayList<>((List<Object>) JsonLite.parse(s.strip()));
        List<Object> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        for (String part : s.split(java.util.regex.Pattern.quote(sep), -1)) out.add(part.strip());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toDictionary(Object v) {
        if (v instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        if (v instanceof String s && looksLikeJson(s) && s.strip().startsWith("{")) return new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(s.strip()));
        if (v instanceof List<?> l) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < l.size(); i++) {
                Object item = l.get(i);
                if (item instanceof List<?> pair && pair.size() == 2) out.put(Values.text(pair.get(0)), pair.get(1));
                else out.put(String.valueOf(i), item);
            }
            return out;
        }
        String s = Values.text(v);
        Map<String, Object> out = new LinkedHashMap<>();
        if (s != null && s.contains("=")) {   // key=value; key2=value2
            for (String part : s.split("[;\\n&]")) {
                int eq = part.indexOf('=');
                if (eq > 0) out.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
            }
            if (!out.isEmpty()) return out;
        }
        throw new ActionException("CONVERT.FAILED", "'" + shortText(v) + "' is not JSON {...} or key=value pairs", "Give JSON such as {\"name\": \"Ann\"} or text such as name=Ann; city=Pune.");
    }

    private static boolean looksLikeJson(String s) {
        String t = s.strip();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    private static String shortText(Object v) {
        String s = v == null ? "" : Values.text(v);
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
