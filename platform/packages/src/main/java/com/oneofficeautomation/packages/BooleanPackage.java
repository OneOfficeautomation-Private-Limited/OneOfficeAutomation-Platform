package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.util.Map;

@OoaPackage(id = "ooa.boolean", name = "Boolean", version = "1.1.0", description = "Set, combine, check, convert and invert Yes/No (true/false) values.")
public final class BooleanPackage {

    @Action(id = "invert", name = "Invert", category = "Boolean", idempotent = true, description = "Return the opposite value.",
            panel = "value")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "Opposite value.")
    public Map<String, Object> invert(ActionContext ctx, @Input(name = "value", type = OoaType.BOOLEAN, description = "Value to invert.") boolean v) {
        return Map.of("result", !v);
    }

    @Action(id = "from_text", name = "Text to Boolean", category = "Boolean", idempotent = true, description = "Read true/false, yes/no, 1/0 (any case) from text.",
            panel = "text | strict")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "Parsed value.")
    public Map<String, Object> fromText(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text such as Yes or false.") String text,
            @Input(name = "strict", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Fail on text that is not a yes/no value; when off, such text (and empty text) gives false.") boolean strict) {
        try { return Map.of("result", Values.coerce(text, "Boolean", "text")); }
        catch (OoaException e) {
            if (!strict) return Map.of("result", false);
            throw new ActionException("BOOLEAN.INVALID", "'" + text + "' is not a yes/no value", "Use true/false, yes/no or 1/0.");
        }
    }

    @Action(id = "to_text", name = "Boolean to text", category = "Boolean", idempotent = true, description = "Turn a Boolean into custom text, e.g. Approved/Rejected.",
            panel = "value | true_text, false_text")
    @Output(name = "text", type = OoaType.STRING, description = "Text for the value.")
    public Map<String, Object> toText(ActionContext ctx,
            @Input(name = "value", type = OoaType.BOOLEAN, description = "Value.") boolean v,
            @Input(name = "true_text", type = OoaType.STRING, required = false, defaultValue = "\"true\"", description = "Text when true.") String t,
            @Input(name = "false_text", type = OoaType.STRING, required = false, defaultValue = "\"false\"", description = "Text when false.") String f) {
        return Map.of("text", v ? t : f);
    }

    @Action(id = "compare", name = "Compare", category = "Boolean", idempotent = true, description = "Check whether two true/false values are equal or not equal.",
            panel = "value, other, operator")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "Result of the comparison.")
    @Output(name = "order", type = OoaType.NUMBER, description = "Ordering of the two values: 1 when only the first is true, -1 when only the second is, 0 when equal.")
    public Map<String, Object> compare(ActionContext ctx,
            @Input(name = "value", type = OoaType.BOOLEAN, description = "First value.") boolean a,
            @Input(name = "other", type = OoaType.BOOLEAN, description = "Second value.") boolean b,
            @Input(name = "operator", type = OoaType.STRING, required = false, defaultValue = "\"equal\"", enumValues = {"equal", "not_equal"},
                    description = "equal or not_equal.") String op) {
        if (op != null && !op.equals("equal") && !op.equals("not_equal"))
            throw new ActionException("BOOLEAN.BAD_OPERATOR", "Unknown operator '" + op + "'", "Use equal or not_equal.");
        return Map.of("result", "not_equal".equals(op) ? a != b : a == b, "order", java.math.BigDecimal.valueOf(Boolean.compare(a, b)));
    }

    @Action(id = "to_number", name = "Boolean to number", category = "Boolean", idempotent = true, description = "Turn true into 1 and false into 0.",
            panel = "value")
    @Output(name = "result", type = OoaType.NUMBER, description = "1 or 0.")
    public Map<String, Object> toNumber(ActionContext ctx, @Input(name = "value", type = OoaType.BOOLEAN, description = "Value.") boolean v) {
        return Map.of("result", v ? java.math.BigDecimal.ONE : java.math.BigDecimal.ZERO);
    }

    // ---- Yes/No values made easy (16 Nov 2026) ----

    @Action(id = "set", name = "Set Yes/No", category = "Boolean", idempotent = true,
            description = "Work out a Yes/No value: from a value (yes, 1, true, a non-empty text) or from a formula such as $total$ > 1000 and $country$ == 'IN'.",
            panel = "(How: From a value = value, empty_is / From a formula = formula)")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "The Yes/No value.")
    public Map<String, Object> set(ActionContext ctx,
            @Input(name = "value", type = OoaType.ANY, required = false, description = "A Yes/No, a number (0 is No), or a text such as yes, no, true, false, 1, 0.") Object value,
            @Input(name = "empty_is", type = OoaType.STRING, required = false, defaultValue = "\"no\"", enumValues = {"no", "yes", "error"},
                    description = "What an empty value means, and what other text means (any text other than a no-word is Yes when this is no or yes).") String emptyIs,
            @Input(name = "formula", type = OoaType.ANY, required = false, label = "Formula",
                    description = "A condition with variables, e.g. $total$ > 1000 and $country$ == 'IN', or contains($subject$, 'urgent').") Object formula) {
        if (formula != null && !(formula instanceof String s && s.isBlank())) {
            Object v = formula;
            if (formula instanceof String text) {
                try { v = com.oneofficeautomation.runner.expr.Expressions.evaluate(text, name -> null); }
                catch (OoaException e) { throw new ActionException("BOOLEAN.BAD_FORMULA", "The formula '" + text + "' cannot be worked out: " + e.getMessage(), "Check the formula; Help > Formulas lists what it may use."); }
            }
            return Map.of("result", com.oneofficeautomation.runner.expr.Expressions.truthy(v));
        }
        if (value == null || (value instanceof String s && s.isBlank())) {
            if ("error".equals(emptyIs)) throw new ActionException("BOOLEAN.EMPTY", "The value is empty", "Give a value, or choose what an empty value means.");
            return Map.of("result", "yes".equals(emptyIs));
        }
        if (value instanceof Boolean b) return Map.of("result", b);
        if (value instanceof Number n) return Map.of("result", new java.math.BigDecimal(n.toString()).signum() != 0);
        try { return Map.of("result", Values.coerce(value, "Boolean", "value")); }
        catch (OoaException e) {
            if ("error".equals(emptyIs)) throw new ActionException("BOOLEAN.INVALID", "'" + Values.text(value) + "' is not a yes/no value", "Use true/false, yes/no or 1/0.");
            return Map.of("result", true);
        }
    }

    @Action(id = "combine", name = "Combine Yes/No values", category = "Boolean", idempotent = true,
            description = "One Yes/No from several: all of them (and), any of them (or), exactly one (xor) or none of them.",
            panel = "values, how")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "The combined value.")
    @Output(name = "true_count", type = OoaType.NUMBER, description = "How many of the values are Yes.")
    public Map<String, Object> combine(ActionContext ctx,
            @Input(name = "values", type = OoaType.LIST, description = "The Yes/No values, e.g. [$paid$, $shipped$, $invoiced$].") java.util.List<Object> values,
            @Input(name = "how", type = OoaType.STRING, defaultValue = "\"all\"", enumValues = {"all", "any", "exactly_one", "none"},
                    description = "All: every one is Yes (and). Any: at least one (or). Exactly one: one and only one (xor). None: not one.") String how) {
        int yes = 0, n = values == null ? 0 : values.size();
        if (values != null) for (Object v : values) if (com.oneofficeautomation.runner.expr.Expressions.truthy(v)) yes++;
        boolean r = switch (how == null ? "all" : how) {
            case "any" -> yes > 0;
            case "exactly_one" -> yes == 1;
            case "none" -> yes == 0;
            default -> n > 0 && yes == n;
        };
        return Map.of("result", r, "true_count", java.math.BigDecimal.valueOf(yes));
    }

    @Action(id = "from_number", name = "Number to Yes/No", category = "Boolean", idempotent = true,
            description = "Read a number as Yes/No: 0 is No and any other number Yes, or strictly 1 Yes and 0 No.",
            panel = "value, rule")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "The Yes/No value.")
    public Map<String, Object> fromNumber(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "The number.") java.math.BigDecimal value,
            @Input(name = "rule", type = OoaType.STRING, required = false, defaultValue = "\"non_zero\"", enumValues = {"non_zero", "one_or_zero"},
                    description = "Non-zero: 0 is No, anything else Yes. One or zero: 1 is Yes, 0 is No, other numbers fail with BOOLEAN.INVALID.") String rule) {
        if ("one_or_zero".equals(rule)) {
            if (value.compareTo(java.math.BigDecimal.ONE) == 0) return Map.of("result", true);
            if (value.signum() == 0) return Map.of("result", false);
            throw new ActionException("BOOLEAN.INVALID", value.toPlainString() + " is neither 1 nor 0", "Use the non-zero rule, or check the number.");
        }
        return Map.of("result", value.signum() != 0);
    }

    @Condition(id = "is_true", name = "Is Yes (true)", description = "True when the value is Yes: true, yes, 1, or a non-empty text other than a no-word.")
    public boolean isTrue(ActionContext ctx, @Input(name = "value", type = OoaType.ANY, required = false, description = "The value to check.") Object value) {
        return com.oneofficeautomation.runner.expr.Expressions.truthy(yesNo(value));
    }

    @Condition(id = "is_false", name = "Is No (false)", description = "True when the value is No: false, no, 0, empty or missing.")
    public boolean isFalse(ActionContext ctx, @Input(name = "value", type = OoaType.ANY, required = false, description = "The value to check.") Object value) {
        return !com.oneofficeautomation.runner.expr.Expressions.truthy(yesNo(value));
    }

    /** Text such as "no", "false" or "0" means No in these checks; any other value as conditions read it. */
    private static Object yesNo(Object v) {
        if (v instanceof String s) { try { return Values.coerce(s.strip(), "Boolean", "value"); } catch (OoaException e) { return !s.isBlank(); } }
        return v;
    }
}
