package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Shared Global values read by many bots (for example a reports folder or an approval threshold). In Studio they live in
 * global-values.json in the workspace; in One CommandCenter they are managed centrally with permissions.
 */
@OoaPackage(id = "ooa.global_values", name = "Shared Global values", version = "1.0.0", description = "Read and change shared values that many agents use, such as folders, thresholds or URLs.")
public final class GlobalValuesPackage {
    static final String FILE = "global-values.json";

    /** The platform's global values service (One CommandCenter) when the run has one, otherwise global-values.json in the workspace, as $global does. */
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
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        } catch (IllegalArgumentException e) { throw new ActionException("GLOBALS.INVALID", FILE + " is not valid JSON: " + e.getMessage(), "Fix the file in the workspace."); }
    }

    @Action(id = "get", name = "Get shared value", category = "Shared Global values", idempotent = true, description = "Read one shared value by name.",
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

    /**
     * Changes a shared value (gap G30). In One CommandCenter the run's user needs Manage shared values, the value must
     * fit the shared value's type, and the change is recorded in the Audit Trail; in Studio global-values.json in the
     * workspace is changed. Only shared values that already exist can be changed.
     */
    @SuppressWarnings("unchecked")
    @Action(id = "set", name = "Set shared value", category = "Shared Global values", idempotent = true,
            description = "Change a shared value that already exists. In One CommandCenter the user the run runs as needs permission to manage shared values; every change is recorded in the Audit Trail.",
            panel = "name, value | type")
    @Output(name = "value", description = "The value as stored.")
    public Map<String, Object> set(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Shared value name.") String name,
            @Input(name = "value", description = "The new value. Secrets belong in the credential vault, not in shared values.") Object value,
            @Input(name = "type", type = OoaType.STRING, required = false, defaultValue = "\"Any\"", enumValues = {"Any", "String", "Number", "Boolean", "DateTime", "List", "Dictionary"},
                    description = "Convert the value to this type first; fails with GLOBALS.WRONG_TYPE when it cannot be converted. One CommandCenter also checks the shared value's own type.") String type) {
        if (name == null || !name.matches("^[A-Za-z_][A-Za-z0-9_]{0,79}$"))
            throw new ActionException("GLOBALS.BAD_NAME", "'" + name + "' is not a shared value name", "Names use letters, digits and _ and do not start with a digit.");
        if (value instanceof SecureString)
            throw new ActionException("GLOBALS.SECRET", "A credential cannot be stored as a shared value: shared values are not secret", "Keep it in the credential vault (Save credential, ooa.vault).");
        if (value == null) throw new ActionException("GLOBALS.NO_VALUE", "Give the new value of '" + name + "'", "Fill in value.");
        Object v = value;
        if (type != null && !type.isBlank() && !type.equals("Any")) {
            try { v = com.oneofficeautomation.runner.Values.coerce(v, type, name); }
            catch (OoaException | IllegalArgumentException e) {
                throw new ActionException("GLOBALS.WRONG_TYPE", "The new value of '" + name + "' cannot be read as " + type, "Check the value or choose type Any.");
            }
        }
        Object writer = null;
        try { writer = ctx.service("global_values_write", Object.class); } catch (OoaException ignored) { /* none: Studio, or a run that cannot write */ }
        if (writer instanceof java.util.function.Function<?, ?> fn) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("name", name);
            req.put("value", v);
            Map<String, Object> r = ((java.util.function.Function<Map<String, Object>, Map<String, Object>>) fn).apply(req);
            Object stored = r.containsKey("value") ? r.get("value") : v;
            // later reads in this run see the new value
            try { load(ctx).put(name, stored); } catch (UnsupportedOperationException ignored) { }
            return Out.of("value", stored);
        }
        boolean central = false;
        try { central = ctx.service("global_values", Object.class) != null; } catch (OoaException ignored) { }
        if (central) throw new ActionException("GLOBALS.READ_ONLY", "This run can read shared values but not change them",
                "Run the agent from One CommandCenter on a runner of this release, or change the value in One CommandCenter.");
        // Studio: the workspace file
        Path p = ctx.workDir().resolve(FILE);
        Map<String, Object> all = new LinkedHashMap<>(load(ctx));
        if (!all.containsKey(name)) throw new ActionException("GLOBALS.NOT_FOUND", "Shared value '" + name + "' is not defined", "Add it to " + FILE + " in the workspace first; agents change existing shared values only.");
        all.put(name, v);
        try { Files.writeString(p, JsonLite.write(all)); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + FILE + ": " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Out.of("value", v);
    }

    @Condition(id = "is_defined", name = "Shared value is defined", description = "True when a shared value with this name exists.")
    public boolean isDefined(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Shared value name.") String name) {
        return load(ctx).containsKey(name);
    }

    @Action(id = "get_all", name = "Get all shared values", category = "Shared Global values", idempotent = true, description = "Read every shared value as a dictionary.",
            panel = "prefix")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "Name to value.")
    public Map<String, Object> getAll(ActionContext ctx,
            @Input(name = "prefix", type = OoaType.STRING, required = false, description = "Return only values whose name starts with this text, e.g. finance_.") String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        load(ctx).forEach((k, v) -> { if (prefix == null || prefix.isEmpty() || k.startsWith(prefix)) out.put(k, v); });
        return Map.of("values", out);
    }
}
