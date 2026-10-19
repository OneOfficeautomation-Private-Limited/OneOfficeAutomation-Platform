package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Workday REST API (common/v1): workers and generic GET. Tenant-specific business processes use custom REST or RaaS reports. */
@OoaPackage(id = "ooa.workday", name = "Workday", version = "1.0.0", description = "Search and read Workday workers and call Workday REST endpoints or RaaS reports.")
public final class WorkdayPackage {
    static final String P = "WORKDAY";
    private static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final String D_HOST = "Workday REST host, e.g. https://wd2-impl-services1.workday.com.";

    private static String api(String host, String tenant) {
        if (!TENANT.matcher(tenant).matches()) throw new ActionException(P + ".BAD_TENANT", "Invalid tenant name '" + tenant + "'");
        return host.replaceAll("/+$", "") + "/ccx/api/v1/" + tenant;
    }

    @Action(id = "search_workers", name = "Search workers", category = "Workers", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "Find workers by name or id text.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, descriptor, primary_work_email, business_title.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of workers.")
    public Map<String, Object> searchWorkers(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = "^https://", description = D_HOST) String host,
            @Input(name = "tenant", type = OoaType.STRING, description = "Tenant name.") String tenant,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = "OAuth access token from the vault.") Object token,
            @Input(name = "search", type = OoaType.STRING, required = false, description = "Name or id text; all workers when empty.") String search,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum workers.") int max) {
        List<Object> rows = new ArrayList<>();
        int offset = 0;
        while (rows.size() < max) {
            Map<String, Object> q = new LinkedHashMap<>(Map.of("limit", Math.min(100, max - rows.size()), "offset", offset));
            if (search != null && !search.isBlank()) q.put("search", search);
            Map<String, Object> r = HttpJson.req(P, "GET", api(host, tenant) + "/workers?" + HttpJson.query(q)).bearer(token).timeout(120).send(ctx).map();
            List<?> data = (List<?>) r.getOrDefault("data", List.of());
            for (Object o : data) {
                Map<?, ?> w = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(w.get("id"), w.get("descriptor"), w.get("primaryWorkEmail"), w.get("businessTitle"))));
            }
            offset += data.size();
            int total = r.get("total") instanceof BigDecimal t ? t.intValue() : offset;
            if (data.isEmpty() || offset >= total) break;
        }
        return Map.of("table", Values.table(List.of("id", "descriptor", "primary_work_email", "business_title"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get", name = "Call REST endpoint (GET)", category = "REST", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "GET any Workday REST resource or RaaS report URL path and return the JSON.")
    @Output(name = "json", description = "Response body.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = "^https://", description = D_HOST) String host,
            @Input(name = "path", type = OoaType.STRING, example = "/ccx/api/v1/acme/workers/3aa5550b7fe348b98d7b5741afc65534", description = "Path starting with /ccx/.") String path,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = "OAuth access token from the vault.") Object token) {
        if (!path.startsWith("/ccx/") || path.contains("..")) throw new ActionException(P + ".BAD_PATH", "The path must start with /ccx/", "Example: /ccx/service/customreport2/acme/owner/Report?format=json");
        return Out.of("json", HttpJson.req(P, "GET", host.replaceAll("/+$", "") + path).bearer(token).timeout(240).send(ctx).json());
    }
}
