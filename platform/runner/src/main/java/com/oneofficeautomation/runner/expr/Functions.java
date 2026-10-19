package com.oneofficeautomation.runner.expr;

import com.oneofficeautomation.runner.Values;
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
                    + (min == max ? min : min + ".." + max) + ", got " + a.size());
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
            catch (IllegalArgumentException e) { throw new OoaException("EXPR.INVALID", "jsonParse() failed: " + e.getMessage()); }
        });
        def("jsonStringify", 1, 1, a -> JsonLite.write(Values.toJsonable(a.get(0))));
        def("if", 3, 3, a -> Expressions.truthy(a.get(0)) ? a.get(1) : a.get(2));
    }

    static Object call(String fn, List<Object> args) {
        try {
            return ALL.get(fn).apply(args);
        } catch (OoaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OoaException("EXPR.INVALID", fn + "() failed: " + e.getMessage());
        }
    }

    private static OoaException badUnit(String unit) {
        return new OoaException("EXPR.INVALID", "Unknown unit '" + unit + "'", "Use seconds, minutes, hours, days, weeks, months or years.");
    }

    private static Pattern regex(String p) {
        try { return Pattern.compile(p); }
        catch (PatternSyntaxException e) { throw new OoaException("EXPR.INVALID", "Invalid regular expression: " + e.getDescription()); }
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
                    default -> throw new OoaException("EXPR.INVALID", "Unsupported date format code %" + d);
                };
                if (p == null) lit.append('%'); else { flush.run(); out.append(p); }
            } else lit.append(c);
        }
        flush.run();
        return DateTimeFormatter.ofPattern(out.toString(), Locale.ENGLISH);
    }
}
