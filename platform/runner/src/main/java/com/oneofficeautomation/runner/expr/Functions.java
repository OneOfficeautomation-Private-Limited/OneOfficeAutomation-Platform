package com.oneofficeautomation.runner.expr;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.BinaryValue;
import com.oneofficeautomation.sdk.ImageValue;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Allow-listed expression functions. Same names, arguments and results as the Python reference. */
public final class Functions {
    private Functions() {}

    static final Map<String, Function<List<Object>, Object>> ALL = new HashMap<>();
    private static final Map<String, Long> UNITS = Map.of("seconds", 1L, "minutes", 60L, "hours", 3600L, "days", 86400L, "weeks", 604800L);

    private static Object arg(List<Object> a, int i) { return i < a.size() ? a.get(i) : null; }
    private static String s(List<Object> a, int i) { return Values.text(arg(a, i)); }
    private static BigDecimal n(List<Object> a, int i) { return Values.toNumber(arg(a, i)); }

    private static void need(List<Object> a, int min, int max, String fn) {
        if (a.size() < min || a.size() > max)
            throw new OoaException("EXPR.INVALID", "Wrong number of arguments for " + fn + "(): expected "
                    + (min == max ? min : min + ".." + max) + ", got " + a.size(), "Correct the expression the message names; Help > Expressions lists the functions and their arguments.");
    }

    private static void def(String name, int min, int max, Function<List<Object>, Object> f) {
        ALL.put(name, a -> { need(a, min, max, name); return f.apply(a); });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listArg(List<Object> a) {
        return a.size() == 1 && a.get(0) instanceof List<?> l ? (List<Object>) l : a;
    }

    static {
        def("upper", 1, 1, a -> s(a, 0).toUpperCase(Locale.ROOT));
        def("lower", 1, 1, a -> s(a, 0).toLowerCase(Locale.ROOT));
        def("trim", 1, 1, a -> s(a, 0).strip());
        def("len", 1, 1, a -> {
            Object v = a.get(0);
            if (v == null) return BigDecimal.ZERO;
            if (v instanceof Map<?, ?> m && m.containsKey("rows") && m.containsKey("columns")) return BigDecimal.valueOf(((List<?>) m.get("rows")).size());
            if (v instanceof BinaryValue b) return BigDecimal.valueOf(b.length());
            if (v instanceof ImageValue i) return BigDecimal.valueOf(i.length());
            if (v instanceof Collection<?> c) return BigDecimal.valueOf(c.size());
            if (v instanceof Map<?, ?> m) return BigDecimal.valueOf(m.size());
            return BigDecimal.valueOf(Values.text(v).length());
        });
        def("concat", 0, 99, a -> { StringBuilder sb = new StringBuilder(); for (Object x : a) sb.append(Values.text(x)); return sb.toString(); });
        def("contains", 2, 2, a -> {
            Object h = a.get(0), needle = a.get(1);
            if (h instanceof Collection<?> c) return c.stream().anyMatch(x -> Values.looseEquals(x, needle));
            if (h instanceof Map<?, ?> m) return m.containsKey(Values.text(needle));
            return Values.text(h).contains(Values.text(needle));
        });
        def("startsWith", 2, 2, a -> s(a, 0).startsWith(s(a, 1)));
        def("endsWith", 2, 2, a -> s(a, 0).endsWith(s(a, 1)));
        def("replace", 3, 3, a -> s(a, 0).replace(s(a, 1), s(a, 2)));
        def("substring", 2, 3, a -> {
            String x = s(a, 0);
            int start = Math.max(0, Math.min(x.length(), n(a, 1).intValue()));
            int end = a.size() > 2 ? Math.min(x.length(), start + n(a, 2).intValue()) : x.length();
            return x.substring(start, Math.max(start, end));
        });
        def("split", 2, 2, a -> new ArrayList<Object>(Arrays.asList(s(a, 0).split(Pattern.quote(s(a, 1)), -1))));
        def("join", 1, 2, a -> {
            List<String> parts = new ArrayList<>();
            if (a.get(0) instanceof Collection<?> c) for (Object x : c) parts.add(Values.text(x));
            return String.join(a.size() > 1 ? s(a, 1) : "", parts);
        });
        def("toNumber", 1, 1, a -> n(a, 0));
        def("toString", 1, 1, a -> s(a, 0));
        def("toBoolean", 1, 1, a -> Values.coerce(a.get(0), "Boolean", "value"));
        def("round", 1, 2, a -> Expressions.roundHalfUp(n(a, 0), a.size() > 1 ? n(a, 1).intValue() : 0));
        def("abs", 1, 1, a -> n(a, 0).abs());
        def("min", 1, 99, a -> listArg(a).stream().map(Values::toNumber).min(Comparator.naturalOrder()).orElse(null));
        def("max", 1, 99, a -> listArg(a).stream().map(Values::toNumber).max(Comparator.naturalOrder()).orElse(null));
        def("sum", 1, 1, a -> {
            BigDecimal t = BigDecimal.ZERO;
            if (a.get(0) instanceof Collection<?> c) for (Object x : c) t = t.add(Values.toNumber(x));
            return t;
        });
        def("now", 0, 0, a -> LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
        def("today", 0, 0, a -> LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        def("dateAdd", 2, 3, a -> {
            LocalDateTime dt = Values.toDateTime(a.get(0));
            if (a.get(1) instanceof Duration span) {
                if (a.size() > 2) throw new OoaException("EXPR.INVALID", "dateAdd() with a TimeSpan takes no unit", "Write dateAdd($date, $span).");
                return dt.plus(span);
            }
            BigDecimal amount = n(a, 1);
            String unit = a.size() > 2 ? s(a, 2) : "days";
            if (unit.equals("months")) return dt.plusMonths(amount.longValue());
            if (unit.equals("years")) return dt.plusYears(amount.longValue());
            Long secs = UNITS.get(unit);
            if (secs == null) throw badUnit(unit);
            return dt.plusNanos(amount.multiply(BigDecimal.valueOf(secs)).movePointRight(9).longValue());
        });
        def("dateDiff", 2, 3, a -> {
            Duration d = Duration.between(Values.toDateTime(a.get(0)), Values.toDateTime(a.get(1)));
            String unit = a.size() > 2 ? s(a, 2) : "days";
            Long secs = UNITS.get(unit);
            if (secs == null) throw badUnit(unit);
            BigDecimal total = BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9));
            return total.divide(BigDecimal.valueOf(secs), 6, RoundingMode.HALF_UP).stripTrailingZeros();
        });
        def("formatDate", 1, 2, a -> Values.toDateTime(a.get(0)).format(strftime(a.size() > 1 ? s(a, 1) : "%Y-%m-%d")));
        def("parseDate", 1, 2, a -> {
            if (a.size() < 2) return Values.toDateTime(a.get(0));
            String fmt = s(a, 1);
            DateTimeFormatter f = strftime(fmt);
            return fmt.contains("%H") ? LocalDateTime.parse(s(a, 0), f) : LocalDate.parse(s(a, 0), f).atStartOfDay();
        });
        // Time and TimeSpan (G22)
        def("time", 1, 3, a -> a.size() == 1 ? Values.toTime(a.get(0))
                : LocalTime.of(whole(a, 0, "hour"), whole(a, 1, "minute"), a.size() > 2 ? whole(a, 2, "second") : 0));
        def("timeSpan", 1, 2, a -> {
            if (a.size() == 1) return Values.toTimeSpan(a.get(0));
            Long secs = UNITS.get(s(a, 1));
            if (secs == null) throw new OoaException("EXPR.INVALID", "Unknown unit '" + s(a, 1) + "'", "Use seconds, minutes, hours, days or weeks.");
            return Values.toTimeSpan(n(a, 0).multiply(BigDecimal.valueOf(secs)));
        });
        def("timeBetween", 2, 2, a -> a.get(0) instanceof LocalTime || a.get(1) instanceof LocalTime
                ? Duration.between(Values.toTime(a.get(0)), Values.toTime(a.get(1)))
                : Duration.between(Values.toDateTime(a.get(0)), Values.toDateTime(a.get(1))));
        def("totalSeconds", 1, 1, a -> Values.totalSeconds(Values.toTimeSpan(a.get(0))));
        def("totalMinutes", 1, 1, a -> per(a, 60));
        def("totalHours", 1, 1, a -> per(a, 3600));
        def("totalDays", 1, 1, a -> per(a, 86400));
        // Image and Binary (G22)
        def("imageWidth", 1, 1, a -> BigDecimal.valueOf(Values.toImage(a.get(0), null).width()));
        def("imageHeight", 1, 1, a -> BigDecimal.valueOf(Values.toImage(a.get(0), null).height()));
        def("binaryLength", 1, 1, a -> BigDecimal.valueOf(Values.toBinary(a.get(0), null).length()));
        def("toBase64", 1, 1, a -> {
            Object v = a.get(0);
            if (v instanceof BinaryValue b) return b.base64();
            if (v instanceof ImageValue i) return i.base64();
            return Base64.getEncoder().encodeToString(s(a, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        def("fromBase64", 1, 2, a -> {
            byte[] b = Values.base64(s(a, 0));
            if (b == null) throw new OoaException("EXPR.INVALID", "fromBase64(): the text is not valid Base64", "Give standard Base64 text (A-Z, a-z, 0-9, + and /, padded with =).");
            return new BinaryValue(b, a.size() > 1 ? s(a, 1) : null);
        });
        ALL.put("coalesce", a -> a.stream().filter(x -> x != null && !"".equals(x)).findFirst().orElse(null));
        def("isEmpty", 1, 1, a -> {
            Object v = a.get(0);
            return v == null || "".equals(v) || (v instanceof Collection<?> c && c.isEmpty()) || (v instanceof Map<?, ?> m && m.isEmpty());
        });
        def("keys", 1, 1, a -> a.get(0) instanceof Map<?, ?> m ? new ArrayList<Object>(m.keySet()) : new ArrayList<>());
        def("values", 1, 1, a -> a.get(0) instanceof Map<?, ?> m ? new ArrayList<Object>(m.values()) : new ArrayList<>());
        def("get", 2, 3, a -> {
            Object v = a.get(0) instanceof Map<?, ?> m ? m.get(s(a, 1)) : null;
            return v != null ? v : arg(a, 2);
        });
        def("regexMatch", 2, 2, a -> regex(s(a, 1)).matcher(s(a, 0)).find());
        def("regexExtract", 2, 3, a -> {
            Matcher m = regex(s(a, 1)).matcher(s(a, 0));
            return m.find() ? m.group(a.size() > 2 ? n(a, 2).intValue() : 0) : null;
        });
        def("jsonParse", 1, 1, a -> {
            try { return JsonLite.parse(s(a, 0)); }
            catch (IllegalArgumentException e) { throw new OoaException("EXPR.INVALID", "jsonParse() failed: " + e.getMessage(), "Give jsonParse() valid JSON text."); }
        });
        def("jsonStringify", 1, 1, a -> JsonLite.write(Values.toJsonable(a.get(0))));
        def("if", 3, 3, a -> Expressions.truthy(a.get(0)) ? a.get(1) : a.get(2));
        // gap G05 (3 Nov 2026): Power Automate's guid, first, last, union, intersection, base64 and URI functions
        def("guid", 0, 0, a -> UUID.randomUUID().toString());
        def("first", 1, 1, a -> edge(a.get(0), true));
        def("last", 1, 1, a -> edge(a.get(0), false));
        def("union", 2, 2, a -> {
            List<Object> out = new ArrayList<>();
            for (Object list : a) for (Object x : items(list)) if (out.stream().noneMatch(y -> Values.looseEquals(x, y))) out.add(x);
            return out;
        });
        def("intersection", 2, 2, a -> {
            List<Object> out = new ArrayList<>(), other = items(a.get(1));
            for (Object x : items(a.get(0)))
                if (other.stream().anyMatch(y -> Values.looseEquals(x, y)) && out.stream().noneMatch(y -> Values.looseEquals(x, y))) out.add(x);
            return out;
        });
        def("base64Encode", 1, 1, a -> Base64.getEncoder().encodeToString(s(a, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        def("base64Decode", 1, 1, a -> {
            try { return new String(Base64.getDecoder().decode(s(a, 0).replaceAll("\\s", "")), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { throw new OoaException("EXPR.INVALID", "base64Decode(): the text is not Base64", "Pass text made by base64Encode or another Base64 encoder."); }
        });
        // as JavaScript's encodeURIComponent: a space is %20, and only A-Z a-z 0-9 - _ . ! ~ * ' ( ) stay as they are
        def("uriEncode", 1, 1, a -> java.net.URLEncoder.encode(s(a, 0), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~"));
        def("uriDecode", 1, 1, a -> {
            try { return java.net.URLDecoder.decode(s(a, 0).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { throw new OoaException("EXPR.INVALID", "uriDecode(): " + e.getMessage(), "Check every % is followed by two hex digits."); }
        });
    }

    /** The items of a list, the rows of a Data Table, or nothing; one text or number is a list of one. */
    @SuppressWarnings("unchecked")
    private static List<Object> items(Object v) {
        if (v == null) return List.of();
        if (v instanceof Map<?, ?> m && m.containsKey("rows") && m.containsKey("columns") && m.get("rows") instanceof List<?> rows) return (List<Object>) rows;
        if (v instanceof Collection<?> c) return new ArrayList<>(c);
        return List.of(v);
    }

    /** The first or last item of a list (or row of a Data Table), or character of a text; null when there is none. */
    private static Object edge(Object v, boolean first) {
        if (v == null) return null;
        if (v instanceof CharSequence cs) {
            String t = cs.toString();
            if (t.isEmpty()) return null;
            return first ? t.substring(0, Character.charCount(t.codePointAt(0))) : t.substring(t.offsetByCodePoints(t.length(), -1));
        }
        List<Object> l = items(v);
        return l.isEmpty() ? null : l.get(first ? 0 : l.size() - 1);
    }

    static Object call(String fn, List<Object> args) {
        try {
            return ALL.get(fn).apply(args);
        } catch (OoaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OoaException("EXPR.INVALID", fn + "() failed: " + e.getMessage(), "Correct the expression the message names; Help > Expressions lists the functions and their arguments.");
        }
    }

    private static BigDecimal per(List<Object> a, long unitSeconds) {
        return Values.totalSeconds(Values.toTimeSpan(a.get(0))).divide(BigDecimal.valueOf(unitSeconds), 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static int whole(List<Object> a, int i, String what) {
        BigDecimal v = n(a, i);
        if (v.stripTrailingZeros().scale() > 0) throw new OoaException("EXPR.INVALID", "time(): the " + what + " must be a whole number, got " + v.toPlainString(), "Give time() whole numbers.");
        return v.intValue();
    }

    private static OoaException badUnit(String unit) {
        return new OoaException("EXPR.INVALID", "Unknown unit '" + unit + "'", "Use seconds, minutes, hours, days, weeks, months or years.");
    }

    private static Pattern regex(String p) {
        try { return Pattern.compile(p); }
        catch (PatternSyntaxException e) { throw new OoaException("EXPR.INVALID", "Invalid regular expression: " + e.getDescription(), "Correct the regular expression; the message names where it breaks."); }
    }

    /** Translates Python/C strftime patterns (the Flow format) to java.time patterns. */
    static DateTimeFormatter strftime(String fmt) {
        StringBuilder out = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        Runnable flush = () -> { if (lit.length() > 0) { out.append('\'').append(lit.toString().replace("'", "''")).append('\''); lit.setLength(0); } };
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '%' && i + 1 < fmt.length()) {
                char d = fmt.charAt(++i);
                String p = switch (d) {
                    case 'Y' -> "yyyy"; case 'y' -> "yy"; case 'm' -> "MM"; case 'd' -> "dd"; case 'H' -> "HH";
                    case 'I' -> "hh"; case 'M' -> "mm"; case 'S' -> "ss"; case 'p' -> "a"; case 'b' -> "MMM";
                    case 'B' -> "MMMM"; case 'a' -> "EEE"; case 'A' -> "EEEE"; case 'j' -> "DDD"; case 'f' -> "SSSSSS";
                    case '%' -> null;
                    default -> throw new OoaException("EXPR.INVALID", "Unsupported date format code %" + d, "Use a date format code the help lists.");
                };
                if (p == null) lit.append('%'); else { flush.run(); out.append(p); }
            } else lit.append(c);
        }
        flush.run();
        return DateTimeFormatter.ofPattern(out.toString(), Locale.ENGLISH);
    }
}
