package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.net.InetAddress;
import java.util.*;

@OoaPackage(id = "ooa.run_context", name = "Run context", version = "1.0.0", description = "Information about the current run: run id, runner, user and workspace.")
public final class RunContextPackage {

    @Action(id = "get", name = "Get run information", category = "Run", idempotent = true, description = "Return the run id, tenant, runner name, operating system user and working folder.")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "run_id, step_id, tenant, device, os_user, working_folder, os_name, os_version, java_version, processors, temp_folder.")
    public Map<String, Object> get(ActionContext ctx) {
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "unknown"; }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", ctx.runId());
        m.put("tenant", ctx.tenantId());
        m.put("device", host);
        m.put("os_user", System.getProperty("user.name"));
        m.put("working_folder", ctx.workDir().toString());
        m.put("step_id", ctx.stepId());
        m.put("os_name", System.getProperty("os.name", ""));
        m.put("os_version", System.getProperty("os.version", ""));
        m.put("java_version", System.getProperty("java.version", ""));
        m.put("processors", java.math.BigDecimal.valueOf(Runtime.getRuntime().availableProcessors()));
        m.put("temp_folder", System.getProperty("java.io.tmpdir", ""));
        return Map.of("info", m);
    }

    @Action(id = "get_value", name = "Get one run value", category = "Run", idempotent = true, description = "Return a single item of the run information by name, e.g. run_id or device.",
            panel = "name")
    @Output(name = "value", type = OoaType.STRING, description = "The value as text.")
    public Map<String, Object> getValue(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, enumValues = {"run_id", "step_id", "tenant", "device", "os_user", "working_folder", "os_name", "os_version", "java_version", "processors", "temp_folder"},
                    description = "Which value to return.") String name) {
        @SuppressWarnings("unchecked") Map<String, Object> info = (Map<String, Object>) get(ctx).get("info");
        if (!info.containsKey(name)) throw new ActionException("RUN_CONTEXT.UNKNOWN", "Unknown run value '" + name + "'", "Use one of " + String.join(", ", info.keySet()) + ".");
        return Map.of("value", com.oneofficeautomation.runner.Values.text(info.get(name)));
    }
}
