package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Shared values read by many bots (for example a reports folder or an approval threshold). In Studio they live in
 * global-values.json in the workspace; in Control Center they are managed centrally with permissions.
 */
@OoaPackage(id = "ooa.global_values", name = "Global values", version = "1.0.0", description = "Read shared values that many bots use, such as folders, thresholds or URLs.")
public final class GlobalValuesPackage {
    static final String FILE = "global-values.json";

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(ActionContext ctx) {
        Path p = ctx.workDir().resolve(FILE);
        if (!Files.isRegularFile(p)) return Map.of();
        try {
            Object v = JsonLite.parse(Files.readString(p));
            if (!(v instanceof Map)) throw new ActionException("GLOBALS.INVALID", FILE + " must contain a JSON object", "Example: {\"reports_folder\": \"D:/Reports\"}");
            return (Map<String, Object>) v;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } catch (IllegalArgumentException e) { throw new ActionException("GLOBALS.INVALID", FILE + " is not valid JSON: " + e.getMessage(), "Fix the file in the workspace."); }
    }

    @Action(id = "get", name = "Get global value", category = "Global values", idempotent = true, description = "Read one global value by name.")
    @Output(name = "value", description = "The value.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Global value name.") String name,
            @Input(name = "default", required = false, description = "Value when the name is not defined; without it a missing name is an error.") Object def) {
        Map<String, Object> all = load(ctx);
        if (!all.containsKey(name)) {
            if (def != null) return Out.of("value", def);
            throw new ActionException("GLOBALS.NOT_FOUND", "Global value '" + name + "' is not defined", "Add it to " + FILE + " in the workspace, or give a default.");
        }
        return Out.of("value", all.get(name));
    }

    @Action(id = "get_all", name = "Get all global values", category = "Global values", idempotent = true, description = "Read every global value as a dictionary.")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "Name to value.")
    public Map<String, Object> getAll(ActionContext ctx) { return Map.of("values", new LinkedHashMap<>(load(ctx))); }
}
