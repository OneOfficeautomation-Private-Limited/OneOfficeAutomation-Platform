package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.OoaType;
import com.oneofficeautomation.sdk.SecureString;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Runtime value model shared with the Python reference: String, BigDecimal (Number), Boolean, null,
 * List, Map (Dictionary/Record), Table = {"columns": [...], "rows": [[...]]}, LocalDateTime (UTC),
 * SecureString (Credential). Numbers are always decimal, never double.
 */
public final class Values {
    private Values() {}

    public static BigDecimal toNumber(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (v instanceof Integer || v instanceof Long || v instanceof Short) return BigDecimal.valueOf(((Number) v).longValue());
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s) {
            try { return new BigDecimal(s.trim().replace(",", "")); } catch (NumberFormatException ignored) { }
        }
        throw new OoaException("VAR.INVALID", "Cannot convert " + typeName(v) + " value " + safeRepr(v) + " to Number",
                "Check the value is numeric, e.g. 42 or 19.99.");
    }

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "String";
        if (v instanceof BigDecimal) return "Number";
        if (v instanceof Boolean) return "Boolean";
        if (v instanceof List) return "List";
        if (v instanceof Map) return "Dictionary";
        if (v instanceof SecureString) return "Credential";
        if (v instanceof LocalDateTime) return "DateTime";
        return v.getClass().getSimpleName();
    }

    /** Text form used by interpolation and String coercion. */
    public static String text(Object v) {
        if (v == null) return "";
        if (v instanceof SecureString) throw secureLeak("text");
        if (v instanceof BigDecimal d) {
            return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0 ? d.stripTrailingZeros().toBigInteger().toString() : d.toPlainString();
        }
        if (v instanceof Map || v instanceof List) return JsonLite.write(toJsonable(v));
        if (v instanceof LocalDateTime t) return t.toString();
        return v.toString();
    }

    public static OoaException secureLeak(String where) {
        return new OoaException("VAR.SECURE_LEAK", "A Credential value cannot be used in " + where,
                "Pass the Credential variable directly to an action input that accepts credentials.");
    }

    @SuppressWarnings("unchecked")
    public static Object coerce(Object value, String typeName, String varName) {
        OoaType t = OoaType.fromFlowName(typeName);
        if (value == null || t == OoaType.ANY) return value;
        switch (t) {
            case STRING:
                if (value instanceof SecureString)
                    throw new OoaException("VAR.SECURE_LEAK", "Credential cannot be assigned to String variable '" + varName + "'",
                            "Keep secrets in Credential variables.");
                return text(value);
            case NUMBER: return toNumber(value);
            case BOOLEAN:
                if (value instanceof Boolean) return value;
                if (value instanceof String s) {
                    String l = s.trim().toLowerCase(Locale.ROOT);
                    if (Set.of("true", "yes", "1").contains(l)) return true;
                    if (Set.of("false", "no", "0").contains(l)) return false;
                }
                if (value instanceof BigDecimal d) return d.signum() != 0;
                break;
            case DATETIME: return toDateTime(value);
            case LIST:
                if (value instanceof List) return value;
                break;
            case DICTIONARY: case RECORD:
                if (value instanceof Map) return value;
                break;
            case TABLE:
                if (value instanceof Map<?, ?> m && m.containsKey("columns") && m.containsKey("rows")) return value;
                if (value instanceof List<?> l && l.stream().allMatch(r -> r instanceof Map)) {
                    LinkedHashSet<String> cols = new LinkedHashSet<>();
                    for (Object r : l) for (Object k : ((Map<?, ?>) r).keySet()) cols.add(String.valueOf(k));
                    List<Object> rows = new ArrayList<>();
                    for (Object r : l) { List<Object> row = new ArrayList<>(); for (String c : cols) row.add(((Map<String, Object>) r).get(c)); rows.add(row); }
                    return table(new ArrayList<>(cols), rows);
                }
                break;
            case FILE: return text(value);
            case CREDENTIAL:
                if (value instanceof SecureString) return value;
                throw new OoaException("VAR.SECURE_REQUIRED", "Variable '" + varName + "' needs a Credential reference, not a plain value",
                        "Pass a vault reference like {\"$credential\": \"locker/name/attribute\"}.");
            default: return value;
        }
        throw new OoaException("VAR.TYPE_MISMATCH", "Variable '" + varName + "' expects " + t.flowName() + ", got " + typeName(value),
                "Provide a " + t.flowName() + " value for '" + varName + "'.");
    }

    public static LocalDateTime toDateTime(Object v) {
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof LocalDate d) return d.atStartOfDay();
        if (v instanceof String s) {
            String x = s.trim();
            try {
                if (x.length() == 10) return LocalDate.parse(x).atStartOfDay();
                if (x.endsWith("Z") || x.matches(".*[+-]\\d{2}:\\d{2}$"))
                    return OffsetDateTime.parse(x.replace(" ", "T")).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                return LocalDateTime.parse(x.replace(" ", "T"));
            } catch (DateTimeParseException ignored) { }
        }
        throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read " + safeRepr(v) + " as a DateTime",
                "Use ISO format, e.g. 2026-10-18 or 2026-10-18T14:30:00.");
    }

    public static Map<String, Object> table(List<String> columns, List<Object> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("columns", new ArrayList<>(columns));
        t.put("rows", rows);
        return t;
    }

    /** Equality with decimal semantics (1.0 == 1) used by ==, contains and in. */
    public static boolean looseEquals(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!looseEquals(la.get(i), lb.get(i))) return false;
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) return false;
            for (Object k : ma.keySet()) if (!looseEquals(ma.get(k), mb.get(k))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    /** Serialisable form for events, outputs and tool results. Secrets stay masked. */
    public static Object toJsonable(Object v) {
        if (v instanceof SecureString) return SecureString.MASK;
        if (v instanceof LocalDateTime t) return t.toString();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, x) -> o.put(String.valueOf(k), toJsonable(x)));
            return o;
        }
        if (v instanceof Collection<?> c) { List<Object> o = new ArrayList<>(); for (Object x : c) o.add(toJsonable(x)); return o; }
        if (v instanceof Number n && !(v instanceof BigDecimal)) return new BigDecimal(n.toString());
        return v;
    }

    public static String safeRepr(Object v) {
        String s = JsonLite.write(toJsonable(v));
        return s.length() <= 200 ? s : s.substring(0, 197) + "...";
    }

    /** Deep copy of JSON-like data (defaults must never be shared between runs). */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) { Map<String, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put((String) k, deepCopy(x))); return o; }
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(deepCopy(x)); return o; }
        return v;
    }
}
