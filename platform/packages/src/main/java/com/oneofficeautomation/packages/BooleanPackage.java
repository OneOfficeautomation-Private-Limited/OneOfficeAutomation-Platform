package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.util.Map;

@OoaPackage(id = "ooa.boolean", name = "Boolean", version = "1.0.0", description = "Convert and invert true/false values.")
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
}
