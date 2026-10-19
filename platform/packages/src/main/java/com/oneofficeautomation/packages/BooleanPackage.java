package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.util.Map;

@OoaPackage(id = "ooa.boolean", name = "Boolean", version = "1.0.0", description = "Convert and invert true/false values.")
public final class BooleanPackage {

    @Action(id = "invert", name = "Invert", category = "Boolean", idempotent = true, description = "Return the opposite value.")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "Opposite value.")
    public Map<String, Object> invert(ActionContext ctx, @Input(name = "value", type = OoaType.BOOLEAN, description = "Value to invert.") boolean v) {
        return Map.of("result", !v);
    }

    @Action(id = "from_text", name = "Text to Boolean", category = "Boolean", idempotent = true, description = "Read true/false, yes/no, 1/0 (any case) from text.")
    @Output(name = "result", type = OoaType.BOOLEAN, description = "Parsed value.")
    public Map<String, Object> fromText(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text such as Yes or false.") String text) {
        try { return Map.of("result", Values.coerce(text, "Boolean", "text")); }
        catch (OoaException e) { throw new ActionException("BOOLEAN.INVALID", "'" + text + "' is not a yes/no value", "Use true/false, yes/no or 1/0."); }
    }

    @Action(id = "to_text", name = "Boolean to text", category = "Boolean", idempotent = true, description = "Turn a Boolean into custom text, e.g. Approved/Rejected.")
    @Output(name = "text", type = OoaType.STRING, description = "Text for the value.")
    public Map<String, Object> toText(ActionContext ctx,
            @Input(name = "value", type = OoaType.BOOLEAN, description = "Value.") boolean v,
            @Input(name = "true_text", type = OoaType.STRING, required = false, defaultValue = "\"true\"", description = "Text when true.") String t,
            @Input(name = "false_text", type = OoaType.STRING, required = false, defaultValue = "\"false\"", description = "Text when false.") String f) {
        return Map.of("text", v ? t : f);
    }
}
