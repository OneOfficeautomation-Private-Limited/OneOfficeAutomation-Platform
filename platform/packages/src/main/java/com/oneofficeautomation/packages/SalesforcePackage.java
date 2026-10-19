package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Salesforce REST API: SOQL queries and record create, read, update, delete. */
@OoaPackage(id = "ooa.salesforce", name = "Salesforce", version = "1.0.0", description = "Query Salesforce with SOQL and create, read, update and delete records.")
public final class SalesforcePackage {
    static final String P = "SALESFORCE";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,79}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9]{15,18}$");
    private static final String D_URL = "Instance URL, e.g. https://yourcompany.my.salesforce.com.";
    private static final String D_TOKEN = "OAuth access token from the vault.";

    private static String base(String instance, String version) { return instance.replaceAll("/+$", "") + "/services/data/" + version; }
    private static String object(String o) { if (!NAME.matcher(o).matches()) throw new ActionException(P + ".BAD_OBJECT", "Invalid object name '" + o + "'", "Example: Account, Contact, Case or My_Object__c."); return o; }
    private static String id(String id) { if (!ID.matcher(id).matches()) throw new ActionException(P + ".BAD_ID", "Invalid record id '" + id + "'", "Salesforce ids are 15 or 18 characters."); return id; }

    @Action(id = "query", name = "Query (SOQL)", category = "Records", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Run a SOQL query and return all matching records as a table (all result pages are read).")
    @Output(name = "table", type = OoaType.TABLE, description = "Records.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    public Map<String, Object> query(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "soql", type = OoaType.STRING, example = "SELECT Id, Name FROM Account WHERE Industry = 'Retail'", description = "SOQL query.") String soql,
            @Input(name = "max_records", type = OoaType.NUMBER, required = false, defaultValue = "50000", description = "Safety limit.") int max,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        String root = instance.replaceAll("/+$", "");
        String url = base(instance, version) + "/query?q=" + HttpJson.enc(soql);
        List<Map<String, Object>> records = new ArrayList<>();
        while (url != null && records.size() < max) {
            Map<String, Object> r = HttpJson.req(P, "GET", url).bearer(token).timeout(120).send(ctx).map();
            for (Object o : (List<?>) r.getOrDefault("records", List.of())) records.add(flatten("", (Map<?, ?>) o));
            Object next = r.get("nextRecordsUrl");
            url = Boolean.TRUE.equals(r.get("done")) || next == null ? null : root + next;
        }
        return Map.of("table", Values.coerce(records, "Table", "records"), "count", BigDecimal.valueOf(records.size()));
    }

    /** Relationship fields become Owner.Name style columns; Salesforce "attributes" metadata is dropped. */
    static Map<String, Object> flatten(String prefix, Map<?, ?> rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : rec.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.equals("attributes")) continue;
            if (e.getValue() instanceof Map<?, ?> m && !(m.containsKey("records"))) out.putAll(flatten(prefix + k + ".", m));
            else out.put(prefix + k, e.getValue());
        }
        return out;
    }

    @Action(id = "get_record", name = "Get record", category = "Records", idempotent = true, capabilities = "network", description = "Read one record by id.")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "Field values.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, example = "Account", description = "Object API name.") String obj,
            @Input(name = "id", type = OoaType.STRING, description = "Record id.") String rid,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to return; all when empty.") List<Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        String url = base(instance, version) + "/sobjects/" + object(obj) + "/" + id(rid);
        if (fields != null && !fields.isEmpty()) { StringJoiner j = new StringJoiner(","); for (Object f : fields) j.add(object(Values.text(f).replace(".", "_x_")).replace("_x_", ".")); url += "?fields=" + HttpJson.enc(j.toString()); }
        return Map.of("record", flatten("", HttpJson.req(P, "GET", url).bearer(token).send(ctx).map()));
    }

    @Action(id = "create_record", name = "Create record", category = "Records", capabilities = "network", description = "Create a record and return its id.")
    @Output(name = "id", type = OoaType.STRING, description = "New record id.")
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, example = "Case", description = "Object API name.") String obj,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.") Map<String, Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        Map<String, Object> r = HttpJson.req(P, "POST", base(instance, version) + "/sobjects/" + object(obj)).bearer(token).json(fields).send(ctx).map();
        return Out.of("id", r.get("id"));
    }

    @Action(id = "update_record", name = "Update record", category = "Records", capabilities = "network", description = "Change fields of an existing record.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, description = "Object API name.") String obj,
            @Input(name = "id", type = OoaType.STRING, description = "Record id.") String rid,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to new value.") Map<String, Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        HttpJson.req(P, "PATCH", base(instance, version) + "/sobjects/" + object(obj) + "/" + id(rid)).bearer(token).json(fields).send(ctx);
        return Map.of();
    }

    @Action(id = "delete_record", name = "Delete record", category = "Records", capabilities = "network", description = "Delete a record (it goes to the Salesforce recycle bin).")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, description = "Object API name.") String obj,
            @Input(name = "id", type = OoaType.STRING, description = "Record id.") String rid,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        HttpJson.req(P, "DELETE", base(instance, version) + "/sobjects/" + object(obj) + "/" + id(rid)).bearer(token).send(ctx);
        return Map.of();
    }
}
