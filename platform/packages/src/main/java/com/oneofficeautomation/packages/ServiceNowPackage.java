package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** ServiceNow Table and Attachment APIs. Sign-in with user and password, or an OAuth token. */
@OoaPackage(id = "ooa.servicenow", name = "ServiceNow", version = "1.0.0", description = "Query, create and update ServiceNow records (incidents, requests, any table), add comments and attachments.")
public final class ServiceNowPackage {
    static final String P = "SERVICENOW";
    private static final Pattern TABLE = Pattern.compile("^[a-z][a-z0-9_]{0,79}$");
    private static final Pattern SYS_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final String D_URL = "Instance URL, e.g. https://yourcompany.service-now.com.";

    private static HttpJson.Req auth(HttpJson.Req r, String user, Object password, Object token) {
        return token instanceof SecureString ? r.bearer(token) : r.basic(user == null ? "" : user, password);
    }
    private static String table(String t) { if (!TABLE.matcher(t).matches()) throw new ActionException(P + ".BAD_TABLE", "Invalid table '" + t + "'", "Example: incident, sc_request, change_request."); return t; }
    private static String sysId(String s) { if (!SYS_ID.matcher(s).matches()) throw new ActionException(P + ".BAD_SYS_ID", "Invalid sys_id '" + s + "'", "A sys_id is 32 hexadecimal characters."); return s; }
    private static String api(String instance) { return instance.replaceAll("/+$", "") + "/api/now"; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(HttpJson.Response r) { return r.map().get("result") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }

    @Action(id = "query", name = "Query records", category = "Records", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Return records matching an encoded query (as copied from a ServiceNow list filter).")
    @Output(name = "table", type = OoaType.TABLE, description = "Records.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    public Map<String, Object> query(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, example = "incident", description = "Table name.") String t,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "active=true^priority=1", description = "Encoded query.") String q,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to return, e.g. [\"number\",\"short_description\",\"state\"].") List<Object> fields,
            @Input(name = "max_records", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Maximum records.") int max,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name (basic sign-in).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault (instead of user and password).") Object token) {
        List<Map<String, Object>> recs = new ArrayList<>();
        int offset = 0, pageSize = Math.min(1000, max);
        while (recs.size() < max) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (q != null) params.put("sysparm_query", q);
            if (fields != null && !fields.isEmpty()) { StringJoiner j = new StringJoiner(","); for (Object f : fields) j.add(Values.text(f)); params.put("sysparm_fields", j.toString()); }
            params.put("sysparm_limit", pageSize);
            params.put("sysparm_offset", offset);
            params.put("sysparm_display_value", "true");
            params.put("sysparm_exclude_reference_link", "true");
            List<?> page = (List<?>) auth(HttpJson.req(P, "GET", api(instance) + "/table/" + table(t) + "?" + HttpJson.query(params)), user, password, token)
                    .timeout(120).send(ctx).map().getOrDefault("result", List.of());
            for (Object o : page) { @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o; recs.add(m); if (recs.size() >= max) break; }
            if (page.size() < pageSize) break;
            offset += pageSize;
        }
        return Map.of("table", Values.coerce(recs, "Table", "records"), "count", BigDecimal.valueOf(recs.size()));
    }

    @Action(id = "get_record", name = "Get record", category = "Records", idempotent = true, capabilities = "network", description = "Read one record by sys_id.")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "Field values.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        return Map.of("record", result(auth(HttpJson.req(P, "GET", api(instance) + "/table/" + table(t) + "/" + sysId(id) + "?sysparm_display_value=true&sysparm_exclude_reference_link=true"),
                user, password, token).send(ctx)));
    }

    @Action(id = "create_record", name = "Create record", category = "Records", capabilities = "network", description = "Create a record, e.g. an incident; returns its sys_id and number.")
    @Output(name = "sys_id", type = OoaType.STRING, description = "New sys_id.")
    @Output(name = "number", type = OoaType.STRING, description = "Record number, e.g. INC0012345.")
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, example = "incident", description = "Table name.") String t,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value, e.g. {\"short_description\": \"...\", \"urgency\": \"2\"}.") Map<String, Object> fields,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        Map<String, Object> r = result(auth(HttpJson.req(P, "POST", api(instance) + "/table/" + table(t)), user, password, token).json(fields).send(ctx));
        return Out.of("sys_id", r.get("sys_id"), "number", r.get("number"));
    }

    @Action(id = "update_record", name = "Update record", category = "Records", capabilities = "network", description = "Change fields of a record.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to new value.") Map<String, Object> fields,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        auth(HttpJson.req(P, "PATCH", api(instance) + "/table/" + table(t) + "/" + sysId(id)), user, password, token).json(fields).send(ctx);
        return Map.of();
    }

    @Action(id = "add_comment", name = "Add comment or work note", category = "Records", capabilities = "network", description = "Add a customer-visible comment or an internal work note.")
    public Map<String, Object> comment(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "text", type = OoaType.STRING, description = "Comment text.") String text,
            @Input(name = "internal", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Internal work note instead of a visible comment.") boolean internal,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        auth(HttpJson.req(P, "PATCH", api(instance) + "/table/" + table(t) + "/" + sysId(id)), user, password, token).json(Map.of(internal ? "work_notes" : "comments", text)).send(ctx);
        return Map.of();
    }

    @Action(id = "attach_file", name = "Attach file", category = "Attachments", timeoutSeconds = 600, capabilities = {"network", "filesystem"}, description = "Attach a local file to a record.")
    @Output(name = "attachment_id", type = OoaType.STRING, description = "Attachment sys_id.")
    public Map<String, Object> attach(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "path", type = OoaType.FILE, description = "File to attach.") String path,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        Path p = ctx.resolvePath(path);
        byte[] data;
        String type;
        try { data = Files.readAllBytes(p); type = Optional.ofNullable(Files.probeContentType(p)).orElse("application/octet-stream"); }
        catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        String url = api(instance) + "/attachment/file?" + HttpJson.query(Map.of("table_name", table(t), "table_sys_id", sysId(id), "file_name", p.getFileName().toString()));
        Map<String, Object> r = result(auth(HttpJson.req(P, "POST", url), user, password, token).bytes(data, type).timeout(600).send(ctx));
        return Out.of("attachment_id", r.get("sys_id"));
    }
}
