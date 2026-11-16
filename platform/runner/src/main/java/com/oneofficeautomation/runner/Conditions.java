package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.OoaException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Condition objects: {"op": "...", "left": ..., "right": ...} with optional comparison options:
 * "ignoreCase" (text compares without upper/lower case), "trim" (spaces around text are ignored) and
 * "includeTime" (false compares only the calendar date of DateTime values).
 */
final class Conditions {
    private Conditions() {}

    static final List<String> OPS = List.of("eq", "ne", "lt", "le", "gt", "ge", "contains", "notContains", "startsWith", "notStartsWith",
            "endsWith", "notEndsWith", "matches", "notMatches", "empty", "notEmpty", "in", "notIn", "between", "notBetween");

    static boolean compare(String op, Object left, Object right) { return compare(op, left, right, Map.of()); }

    /**
     * The operator as written, or its snake_case spelling ("not_empty", "starts_with") as the data table filter writes it
     * (real-world tests finding 4); null when it is neither.
     */
    static String canonical(String op) {
        if (op == null) return null;
        if (OPS.contains(op)) return op;
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : op.strip().toCharArray()) {
            if (ch == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return OPS.contains(b.toString()) ? b.toString() : null;
    }

    static boolean compare(String op, Object left, Object right, Map<String, Object> options) {
        boolean ignoreCase = Boolean.TRUE.equals(options.get("ignoreCase"));
        boolean trim = Boolean.TRUE.equals(options.get("trim"));
        boolean dateOnly = Boolean.FALSE.equals(options.get("includeTime"));
        if (ignoreCase || trim) { left = normText(left, ignoreCase, trim); right = normText(right, ignoreCase, trim); }
        if (dateOnly) { left = dateOnly(left); right = dateOnly(right); }
        String known = canonical(op);
        switch (known == null ? String.valueOf(op) : known) {
            case "eq": return (Boolean) Expressions.binary("==", left, right);
            case "ne": return (Boolean) Expressions.binary("!=", left, right);
            case "lt": return (Boolean) Expressions.binary("<", left, right);
            case "le": return (Boolean) Expressions.binary("<=", left, right);
            case "gt": return (Boolean) Expressions.binary(">", left, right);
            case "ge": return (Boolean) Expressions.binary(">=", left, right);
            case "contains":
                if (left instanceof Collection<?> c) { Object r = right; return c.stream().anyMatch(x -> Values.looseEquals(x, r)); }
                if (left instanceof Map<?, ?> m) return m.containsKey(Values.text(right));
                return Values.text(left).contains(Values.text(right));
            case "notContains": return !compare("contains", left, right);
            case "startsWith": return Values.text(left).startsWith(Values.text(right));
            case "notStartsWith": return !Values.text(left).startsWith(Values.text(right));
            case "endsWith": return Values.text(left).endsWith(Values.text(right));
            case "notEndsWith": return !Values.text(left).endsWith(Values.text(right));
            case "matches": return regex(right, ignoreCase).matcher(Values.text(left)).find();
            case "notMatches": return !regex(right, ignoreCase).matcher(Values.text(left)).find();
            case "empty": return isEmpty(left);
            case "notEmpty": return !isEmpty(left);
            case "in": { Object l = left; return right instanceof Collection<?> c && c.stream().anyMatch(x -> Values.looseEquals(x, l)); }
            case "notIn": return !compare("in", left, right);
            case "between": {
                if (!(right instanceof List<?> r) || r.size() != 2)
                    throw new OoaException("FLOW.BAD_CONDITION", "'between' needs right = [low, high]", "Example: {\"op\": \"between\", \"left\": \"$n\", \"right\": [1, 10]}.");
                return (Boolean) Expressions.binary(">=", left, r.get(0)) && (Boolean) Expressions.binary("<=", left, r.get(1));
            }
            case "notBetween": return !compare("between", left, right);
            default:
                throw new OoaException("FLOW.BAD_CONDITION", "Unknown condition operator '" + op + "'", "Use one of " + String.join(", ", OPS) + ".");
        }
    }

    private static Pattern regex(Object right, boolean ignoreCase) {
        return Pattern.compile(Values.text(right), ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    }

    /** Lower-cases and/or trims text (also inside lists and dictionary keys); numbers, dates and booleans are left alone. */
    private static Object normText(Object v, boolean lower, boolean trim) {
        if (v instanceof String s) {
            String t = trim ? s.strip() : s;
            return lower ? t.toLowerCase(Locale.ROOT) : t;
        }
        if (v instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(normText(x, lower, trim)); return out; }
        if (v instanceof Map<?, ?> m && !(m.containsKey("columns") && m.containsKey("rows"))) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put((String) normText(String.valueOf(k), lower, trim), x));
            return out;
        }
        return v;
    }

    /** DateTime (or ISO date text) to midnight of the same day, so only the calendar date is compared. */
    private static Object dateOnly(Object v) {
        if (v instanceof LocalDateTime t) return t.toLocalDate().atStartOfDay();
        if (v instanceof String s) {
            try { return Values.toDateTime(s).toLocalDate().atStartOfDay(); } catch (OoaException ignored) { return v; }
        }
        if (v instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(dateOnly(x)); return out; }
        return v;
    }

    static boolean isEmpty(Object v) {
        return v == null || "".equals(v) || (v instanceof Collection<?> c && c.isEmpty()) || (v instanceof Map<?, ?> m && m.isEmpty());
    }
}
