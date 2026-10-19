package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.OoaException;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/** Condition objects: {"op": "...", "left": ..., "right": ...}. */
final class Conditions {
    private Conditions() {}

    static boolean compare(String op, Object left, Object right) {
        switch (op) {
            case "eq": return (Boolean) Expressions.binary("==", left, right);
            case "ne": return (Boolean) Expressions.binary("!=", left, right);
            case "lt": return (Boolean) Expressions.binary("<", left, right);
            case "le": return (Boolean) Expressions.binary("<=", left, right);
            case "gt": return (Boolean) Expressions.binary(">", left, right);
            case "ge": return (Boolean) Expressions.binary(">=", left, right);
            case "contains":
                if (left instanceof Collection<?> c) return c.stream().anyMatch(x -> Values.looseEquals(x, right));
                if (left instanceof Map<?, ?> m) return m.containsKey(Values.text(right));
                return Values.text(left).contains(Values.text(right));
            case "notContains": return !compare("contains", left, right);
            case "startsWith": return Values.text(left).startsWith(Values.text(right));
            case "endsWith": return Values.text(left).endsWith(Values.text(right));
            case "matches": return Pattern.compile(Values.text(right)).matcher(Values.text(left)).find();
            case "empty": return isEmpty(left);
            case "notEmpty": return !isEmpty(left);
            case "in": return right instanceof Collection<?> c && c.stream().anyMatch(x -> Values.looseEquals(x, left));
            default:
                throw new OoaException("FLOW.BAD_CONDITION", "Unknown condition operator '" + op + "'",
                        "Use eq, ne, lt, le, gt, ge, contains, notContains, startsWith, endsWith, matches, empty, notEmpty or in.");
        }
    }

    static boolean isEmpty(Object v) {
        return v == null || "".equals(v) || (v instanceof Collection<?> c && c.isEmpty()) || (v instanceof Map<?, ?> m && m.isEmpty());
    }
}
