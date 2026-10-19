package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.net.InetAddress;
import java.util.*;

@OoaPackage(id = "ooa.run_context", name = "Run context", version = "1.0.0", description = "Information about the current run: run id, device, user and workspace.")
public final class RunContextPackage {

    @Action(id = "get", name = "Get run information", category = "Run", idempotent = true, description = "Return the run id, tenant, device name, operating system user and working folder.")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "run_id, tenant, device, os_user, working_folder.")
    public Map<String, Object> get(ActionContext ctx) {
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "unknown"; }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", ctx.runId());
        m.put("tenant", ctx.tenantId());
        m.put("device", host);
        m.put("os_user", System.getProperty("user.name"));
        m.put("working_folder", ctx.workDir().toString());
        return Map.of("info", m);
    }
}
