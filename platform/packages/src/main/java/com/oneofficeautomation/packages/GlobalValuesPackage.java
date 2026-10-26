package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Shared values read by many bots (for example a reports folder or an approval threshold). In Studio they live in
 * global-values.json in the workspace; in Control Center they are managed centrally with permissions.
 */
@OoaPackage(id = "ooa.global_values", name = "Shared values", version = "1.0.0", description = "Read shared values that many agents use, such as folders, thresholds or URLs.")
public final class GlobalValuesPackage {
    static final String FILE = "global-values.json";

    /** The platform's global values service (Command Center) when the run has one, otherwise global-values.json in the workspace, as $global does. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> load(ActionContext ctx) {
        try {
            Object svc = ctx.service("global_values", Object.class);
            if (svc instanceof java.util.function.Supplier<?> sup) { Object v = sup.get(); return v instanceof Map ? (Map<String, Object>) v : Map.of(); }
        } catch (OoaException ignored) { /* no service: use the workspace file */ }
        Path p = ctx.workDir().resolve(FILE);
        if (!Files.isRegularFile(p)) return Map.of();
        try {
            Object v = JsonLite.parse(Files.readString(p));
            if (!(v instanceof Map)) throw new ActionException("GLOBALS.INVALID", FILE + " must contain a JSON object", "Example: {\"reports_folder\": \"D:/Reports\"}");
            return (Map<String, Object>) v;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } catch (IllegalArgumentException e) { throw new ActionException("GLOBALS.INVALID", FILE + " is not valid JSON: " + e.getMessage(), "Fix the file in the workspace."); }
    }

    @Action(id = "get", name = "Get shared value", category = "Shared values", idempotent = true, description = "Read one shared value by name.",
            panel = "name, [Use a fallback when it is not defined: default] | type")
    @Output(name = "value", description = "The value.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Shared value name.") String name,
            @Input(name = "default", required = false, description = "Value when the name is not defined; without it a missing name is an error.") Object def,
            @Input(name = "type", type = OoaType.STRING, required = false, defaultValue = "\"Any\"", enumValues = {"Any", "String", "Number", "Boolean", "DateTime", "List", "Dictionary"},
                    description = "Convert the value to this type; fails with GLOBALS.WRONG_TYPE when it cannot be converted.") String type) {
        Map<String, Object> all = load(ctx);
        Object v;
        if (!all.containsKey(name)) {
            if (def == null) throw new ActionException("GLOBALS.NOT_FOUND", "Shared value '" + name + "' is not defined", "Add it to " + FILE + " in the workspace, or give a default.");
            v = def;
        } else v = all.get(name);
        if (type != null && !type.isBlank() && !type.equals("Any")) {
            try { v = com.oneofficeautomation.runner.Values.coerce(v, type, name); }
            catch (OoaException | IllegalArgumentException e) {
                throw new ActionException("GLOBALS.WRONG_TYPE", "Shared value '" + name + "' cannot be read as " + type, "Check the value or choose type Any.");
            }
        }
        return Out.of("value", v);
    }

    @Condition(id = "is_defined", name = "Shared value is defined", description = "True when a shared value with this name exists.")
    public boolean isDefined(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Shared value name.") String name) {
        return load(ctx).containsKey(name);
    }

    @Action(id = "get_all", name = "Get all shared values", category = "Shared values", idempotent = true, description = "Read every shared value as a dictionary.",
            panel = "prefix")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "Name to value.")
    public Map<String, Object> getAll(ActionContext ctx,
            @Input(name = "prefix", type = OoaType.STRING, required = false, description = "Return only values whose name starts with this text, e.g. finance_.") String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        load(ctx).forEach((k, v) -> { if (prefix == null || prefix.isEmpty() || k.startsWith(prefix)) out.put(k, v); });
        return Map.of("values", out);
    }
}
