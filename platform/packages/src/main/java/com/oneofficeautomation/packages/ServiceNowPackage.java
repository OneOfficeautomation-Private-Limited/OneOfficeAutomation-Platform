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

    private static final String D_DISPLAY = "true: display values (e.g. user names); false: stored values (e.g. sys_ids); all: both, as field and dv_field.";

    private static String displayValue(String v) {
        String d = v == null || v.isBlank() ? "true" : v;
        if (!Set.of("true", "false", "all").contains(d)) throw new ActionException(P + ".BAD_OPTION", "display_value must be true, false or all");
        return d;
    }

    /** sysparm_display_value=all answers {"display_value", "value"} pairs; they become field (value) and dv_field (display) columns. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> flatDisplay(Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : rec.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m && m.containsKey("display_value")) { out.put(e.getKey(), m.get("value")); out.put("dv_" + e.getKey(), m.get("display_value")); }
            else out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String fieldList(List<Object> fields) {
        if (fields == null || fields.isEmpty()) return null;
        StringJoiner j = new StringJoiner(",");
        for (Object f : fields) { String n = Values.text(f).strip(); if (!n.matches("^[A-Za-z0-9_.]{1,80}$")) throw new ActionException(P + ".BAD_FIELD", "Invalid field name '" + n + "'"); j.add(n); }
        return j.toString();
    }

    @Action(id = "authenticate", name = "Authenticate", category = "Authentication", capabilities = "network",
            description = "Get an OAuth access token from the instance (user and password, or client credentials) for the other ServiceNow actions.")
    @Output(name = "access_token", type = OoaType.CREDENTIAL, description = "Access token; map it to access_token of later actions.")
    @Output(name = "refresh_token", type = OoaType.CREDENTIAL, description = "Refresh token, when the instance returns one.")
    @Output(name = "expires_in", type = OoaType.NUMBER, description = "Seconds until the access token expires.")
    public Map<String, Object> authenticate(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "client_id", type = OoaType.STRING, description = "Client id of the OAuth application registry entry.") String clientId,
            @Input(name = "client_secret", type = OoaType.CREDENTIAL, description = "Client secret from the vault.") Object clientSecret,
            @Input(name = "grant_type", type = OoaType.STRING, required = false, defaultValue = "\"password\"", enumValues = {"password", "client_credentials"}, description = "password: sign in as a user; client_credentials: as the application.") String grant,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name (password grant).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault (password grant).") Object password) {
        String g = grant == null || grant.isBlank() ? "password" : grant;
        if (!Set.of("password", "client_credentials").contains(g)) throw new ActionException(P + ".BAD_GRANT", "Unknown grant type '" + grant + "'");
        if (clientId == null || clientId.isBlank()) throw new ActionException(P + ".NO_CLIENT", "The client id is required");
        EnterpriseAuth.secret(clientSecret, P, "client secret");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("grant_type", g);
        form.put("client_id", clientId.strip());
        form.put("client_secret", clientSecret);
        if (g.equals("password")) {
            if (user == null || user.isBlank()) throw new ActionException(P + ".NO_USER", "The password grant needs a user and password");
            EnterpriseAuth.secret(password, P, "password");
            form.put("username", user.strip());
            form.put("password", password);
        }
        Map<String, Object> r = EnterpriseAuth.token(ctx, P, instance.replaceAll("/+$", "") + "/oauth_token.do", form, null, null);
        return Out.of("access_token", EnterpriseAuth.credential(r, "servicenow/session"),
                "refresh_token", r.get("refresh_token") instanceof String rt && !rt.isBlank() ? new SecureString(rt, "servicenow/refresh") : null,
                "expires_in", r.get("expires_in") == null ? null : Values.toNumber(r.get("expires_in")));
    }

    @Action(id = "revoke_authentication", name = "Revoke authentication", category = "Authentication", capabilities = "network", description = "Revoke an access or refresh token so it can no longer be used.")
    public Map<String, Object> revoke(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "client_id", type = OoaType.STRING, description = "Client id of the OAuth application.") String clientId,
            @Input(name = "client_secret", type = OoaType.CREDENTIAL, description = "Client secret from the vault.") Object clientSecret,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = "The token to revoke.") Object token) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("token", new SecureString(EnterpriseAuth.secret(token, P, "token"), "t"));
        form.put("client_id", clientId);
        form.put("client_secret", new SecureString(EnterpriseAuth.secret(clientSecret, P, "client secret"), "s"));
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(HttpJson.enc(k) + "=" + HttpJson.enc(v instanceof SecureString s ? s.reveal() : Values.text(v))));
        HttpJson.req(P, "POST", instance.replaceAll("/+$", "") + "/oauth_revoke_token.do")
                .bytes(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/x-www-form-urlencoded").send(ctx);
        return Map.of();
    }

    public Map<String, Object> query(ActionContext ctx, String instance, String t, String q, List<Object> fields, int max, String user, Object password, Object token,
                                     String display, String orderBy, int start, boolean noLinks) {
        return query(ctx, instance, t, q, fields, max, user, password, token, display, orderBy, start, noLinks, null);
    }

    /** An encoded query extended with field=value conditions (values may not contain ^, which separates conditions). */
    static String withFilter(String query, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return query;
        StringBuilder sb = new StringBuilder(query == null ? "" : query.strip());
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            if (!e.getKey().matches("^[A-Za-z0-9_.]{1,80}$")) throw new ActionException(P + ".BAD_FIELD", "Invalid filter field '" + e.getKey() + "'", "Use the field's column name, e.g. assignment_group.");
            String v = e.getValue() == null ? "" : com.oneofficeautomation.runner.Values.text(e.getValue());
            if (v.contains("^")) throw new ActionException(P + ".BAD_FILTER", "The value for " + e.getKey() + " contains ^", "Use the query input for values with ^.");
            if (!sb.isEmpty()) sb.append('^');
            sb.append(e.getKey()).append(e.getValue() == null ? "ISEMPTY" : "=" + v);
        }
        return sb.toString();
    }

    @Action(id = "query", name = "Query records", category = "Records", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Return records matching an encoded query (as copied from a ServiceNow list filter).")
    @Output(name = "table", type = OoaType.TABLE, description = "Records.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    public Map<String, Object> query(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, example = "incident", description = "Table name.") String t,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "active=true^priority=1", description = "Encoded query.") String q,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to return, e.g. [\"number\",\"short_description\",\"state\"].") List<Object> fields,
            @Input(name = "max_records", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Maximum records.") int max,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name (basic sign-in).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault (instead of user and password).") Object token,
            @Input(name = "display_value", type = OoaType.STRING, required = false, defaultValue = "\"true\"", enumValues = {"true", "false", "all"}, description = D_DISPLAY) String display,
            @Input(name = "order_by", type = OoaType.STRING, required = false, example = "sys_created_on", description = "Field to sort by; put - in front for newest or largest first, e.g. -sys_created_on.") String orderBy,
            @Input(name = "offset", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Skip this many matching records first (for paging).") int start,
            @Input(name = "exclude_reference_link", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Leave out the API links of reference fields.") boolean noLinks,
            @Input(name = "filter", type = OoaType.DICTIONARY, required = false, example = "{\"active\": true, \"priority\": 1}",
                    description = "Field to required value, all of which must match; combined with query when both are given. No query syntax needed.") Map<String, Object> filter) {
        String dv = displayValue(display);
        String query = withFilter(q, filter);
        if (orderBy != null && !orderBy.isBlank()) {
            String f = orderBy.strip(); boolean desc = f.startsWith("-");
            if (desc) f = f.substring(1);
            if (!f.matches("^[A-Za-z0-9_.]{1,80}$")) throw new ActionException(P + ".BAD_FIELD", "Invalid order_by field '" + orderBy + "'");
            query = (query == null || query.isBlank() ? "" : query + "^") + (desc ? "ORDERBYDESC" : "ORDERBY") + f;
        }
        String fl = fieldList(fields);
        List<Map<String, Object>> recs = new ArrayList<>();
        int offset = Math.max(0, start), pageSize = Math.max(1, Math.min(1000, max));
        while (recs.size() < max) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (query != null) params.put("sysparm_query", query);
            if (fl != null) params.put("sysparm_fields", fl);
            params.put("sysparm_limit", pageSize);
            params.put("sysparm_offset", offset);
            params.put("sysparm_display_value", dv);
            params.put("sysparm_exclude_reference_link", String.valueOf(noLinks));
            List<?> page = (List<?>) auth(HttpJson.req(P, "GET", api(instance) + "/table/" + table(t) + "?" + HttpJson.query(params)), user, password, token)
                    .timeout(120).send(ctx).map().getOrDefault("result", List.of());
            for (Object o : page) { @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o; recs.add(dv.equals("all") ? flatDisplay(m) : m); if (recs.size() >= max) break; }
            if (page.size() < pageSize) break;
            offset += pageSize;
        }
        return Map.of("table", recs.isEmpty() ? Values.table(List.of(), new ArrayList<>()) : Values.coerce(recs, "Table", "records"), "count", BigDecimal.valueOf(recs.size()));
    }

    /** Earlier signature, kept for callers written before the display and paging options. */
    public Map<String, Object> query(ActionContext ctx, String instance, String t, String q, List<Object> fields, int max, String user, Object password, Object token) {
        return query(ctx, instance, t, q, fields, max, user, password, token, "true", null, 0, true);
    }

    @Action(id = "get_record", name = "Get record", category = "Records", idempotent = true, capabilities = "network", description = "Read one record by sys_id.")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "Field values.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to return; all when empty.") List<Object> fields,
            @Input(name = "display_value", type = OoaType.STRING, required = false, defaultValue = "\"true\"", enumValues = {"true", "false", "all"}, description = D_DISPLAY) String display) {
        String dv = displayValue(display);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sysparm_display_value", dv);
        params.put("sysparm_exclude_reference_link", "true");
        String fl = fieldList(fields);
        if (fl != null) params.put("sysparm_fields", fl);
        Map<String, Object> rec = result(auth(HttpJson.req(P, "GET", api(instance) + "/table/" + table(t) + "/" + sysId(id) + "?" + HttpJson.query(params)), user, password, token).send(ctx));
        return Map.of("record", dv.equals("all") ? flatDisplay(rec) : rec);
    }

    /** Earlier signature, kept for callers written before fields and display_value. */
    public Map<String, Object> get(ActionContext ctx, String instance, String t, String id, String user, Object password, Object token) {
        return get(ctx, instance, t, id, user, password, token, null, "true");
    }

    @Action(id = "create_record", name = "Create record", category = "Records", capabilities = "network", description = "Create a record, e.g. an incident; returns its sys_id and number.")
    @Output(name = "sys_id", type = OoaType.STRING, description = "New sys_id.")
    @Output(name = "number", type = OoaType.STRING, description = "Record number, e.g. INC0012345.")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "The new record as the instance returned it.")
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, example = "incident", description = "Table name.") String t,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value, e.g. {\"short_description\": \"...\", \"urgency\": \"2\"}.") Map<String, Object> fields,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token,
            @Input(name = "input_display_value", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Values are display values (e.g. a user's name for caller_id) that the instance should look up.") boolean inputDisplay) {
        Map<String, Object> r = result(auth(HttpJson.req(P, "POST", api(instance) + "/table/" + table(t) + (inputDisplay ? "?sysparm_input_display_value=true" : "")), user, password, token).json(fields).send(ctx));
        return Out.of("sys_id", r.get("sys_id"), "number", r.get("number"), "record", r);
    }

    /** Earlier signature, kept for callers written before input_display_value. */
    public Map<String, Object> create(ActionContext ctx, String instance, String t, Map<String, Object> fields, String user, Object password, Object token) {
        return create(ctx, instance, t, fields, user, password, token, false);
    }

    @Action(id = "update_record", name = "Update record", category = "Records", capabilities = "network", description = "Change fields of a record.")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "The record after the change, as the instance returned it.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to new value.") Map<String, Object> fields,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token,
            @Input(name = "input_display_value", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Values are display values that the instance should look up.") boolean inputDisplay) {
        Map<String, Object> r = result(auth(HttpJson.req(P, "PATCH", api(instance) + "/table/" + table(t) + "/" + sysId(id) + (inputDisplay ? "?sysparm_input_display_value=true" : "")), user, password, token).json(fields).send(ctx));
        return Map.of("record", r);
    }

    /** Earlier signature, kept for callers written before input_display_value. */
    public Map<String, Object> update(ActionContext ctx, String instance, String t, String id, Map<String, Object> fields, String user, Object password, Object token) {
        return update(ctx, instance, t, id, fields, user, password, token, false);
    }

    @Action(id = "add_comment", name = "Add comment or work note", category = "Records", capabilities = "network", description = "Add a customer-visible comment or an internal work note.")
    public Map<String, Object> comment(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
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
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "path", type = OoaType.FILE, description = "File to attach.") String path,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token,
            @Input(name = "file_name", type = OoaType.STRING, required = false, description = "Name to give the attachment; the local file name when empty.") String fileName,
            @Input(name = "content_type", type = OoaType.STRING, required = false, example = "application/pdf", description = "Content type; guessed from the file when empty.") String contentType) {
        Path p = ctx.resolvePath(path);
        byte[] data;
        String type;
        try { data = Files.readAllBytes(p); type = Optional.ofNullable(Files.probeContentType(p)).orElse("application/octet-stream"); }
        catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        if (contentType != null && !contentType.isBlank()) {
            if (!contentType.matches("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")) throw new ActionException(P + ".BAD_CONTENT_TYPE", "Invalid content type '" + contentType + "'");
            type = contentType;
        }
        String name = fileName == null || fileName.isBlank() ? p.getFileName().toString() : HttpJson.safeName(fileName);
        String url = api(instance) + "/attachment/file?" + HttpJson.query(Map.of("table_name", table(t), "table_sys_id", sysId(id), "file_name", name));
        Map<String, Object> r = result(auth(HttpJson.req(P, "POST", url), user, password, token).bytes(data, type).timeout(600).send(ctx));
        return Out.of("attachment_id", r.get("sys_id"));
    }

    /** Earlier signature, kept for callers written before file_name and content_type. */
    public Map<String, Object> attach(ActionContext ctx, String instance, String t, String id, String path, String user, Object password, Object token) {
        return attach(ctx, instance, t, id, path, user, password, token, null, null);
    }

    @Action(id = "delete_attachment", name = "Delete attachment", category = "Attachments", capabilities = "network", description = "Delete an attachment by its sys_id.")
    public Map<String, Object> deleteAttachment(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "attachment_id", type = OoaType.STRING, description = "Attachment sys_id, from List attachments.") String id,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        auth(HttpJson.req(P, "DELETE", api(instance) + "/attachment/" + sysId(id)), user, password, token).send(ctx);
        return Map.of();
    }

    // ---- Delete, attachments and change polling ----

    @Action(id = "delete_record", name = "Delete record", category = "Records", capabilities = "network", description = "Delete a record by sys_id.")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        auth(HttpJson.req(P, "DELETE", api(instance) + "/table/" + table(t) + "/" + sysId(id)), user, password, token).send(ctx);
        return Map.of();
    }

    @Action(id = "list_attachments", name = "List attachments", category = "Attachments", idempotent = true, capabilities = "network", description = "List the files attached to a record.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: sys_id, file_name, content_type, size_bytes, created_on.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of attachments.")
    public Map<String, Object> listAttachments(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, description = "Table name.") String t,
            @Input(name = "sys_id", type = OoaType.STRING, description = "Record sys_id.") String id,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        String url = api(instance) + "/attachment?" + HttpJson.query(Map.of("sysparm_query", "table_name=" + table(t) + "^table_sys_id=" + sysId(id), "sysparm_limit", 1000));
        List<?> res = (List<?>) auth(HttpJson.req(P, "GET", url), user, password, token).send(ctx).map().getOrDefault("result", List.of());
        List<Object> rows = new ArrayList<>();
        for (Object o : res) {
            Map<?, ?> m = (Map<?, ?>) o;
            rows.add(new ArrayList<>(Arrays.asList(m.get("sys_id"), m.get("file_name"), m.get("content_type"), m.get("size_bytes"), m.get("sys_created_on"))));
        }
        return Map.of("table", Values.table(List.of("sys_id", "file_name", "content_type", "size_bytes", "created_on"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "download_attachment", name = "Download attachment", category = "Attachments", idempotent = true, timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save an attachment to a local folder under its original file name.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file.")
    public Map<String, Object> downloadAttachment(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "attachment_id", type = OoaType.STRING, description = "Attachment sys_id, from List attachments.") String id,
            @Input(name = "folder", type = OoaType.FILE, required = false, defaultValue = "\".\"", description = "Folder to save into.") String folder,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token,
            @Input(name = "file_name", type = OoaType.STRING, required = false, description = "Name to save the file under; the attachment's own name when empty.") String fileName,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file of the same name; when off, the action stops with an error instead.") boolean overwrite) {
        String base = api(instance) + "/attachment/" + sysId(id);
        Map<String, Object> meta = result(auth(HttpJson.req(P, "GET", base), user, password, token).send(ctx));
        Path dir = ctx.resolvePath(folder == null || folder.isBlank() ? "." : folder);
        Path out = dir.resolve(HttpJson.safeName(fileName == null || fileName.isBlank() ? String.valueOf(meta.getOrDefault("file_name", id)) : fileName));
        if (!overwrite && Files.exists(out)) throw new ActionException(P + ".FILE_EXISTS", out + " already exists", "Turn on overwrite or pick another name.");
        byte[] data = auth(HttpJson.req(P, "GET", base + "/file"), user, password, token).header("Accept", "*/*").binaryResponse().timeout(600).send(ctx).bytes();
        try { Files.createDirectories(dir); Files.write(out, data); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot save " + out + ": " + e.getMessage(), "Check the folder is writable.", true); }
        return Map.of("path", out.toString());
    }

    /** Earlier signature, kept for callers written before file_name and overwrite. */
    public Map<String, Object> downloadAttachment(ActionContext ctx, String instance, String id, String folder, String user, Object password, Object token) {
        return downloadAttachment(ctx, instance, id, folder, user, password, token, null, true);
    }

    private static final Pattern STAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?$");

    @Action(id = "list_changed_records", name = "List changed records", category = "Records", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Return records created or updated after a point in time, oldest first, for polling-style triggers.")
    @Output(name = "table", type = OoaType.TABLE, description = "Changed records (values as stored, times in UTC).")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    @Output(name = "latest", type = OoaType.STRING, description = "Newest sys_updated_on seen; pass it as since on the next poll (equal to since when nothing changed).")
    public Map<String, Object> changed(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "table", type = OoaType.STRING, example = "incident", description = "Table name.") String t,
            @Input(name = "since", type = OoaType.STRING, example = "2026-10-19 08:00:00", description = "UTC time; records updated strictly after it are returned.") String since,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "active=true", description = "Extra encoded query to narrow the records.") String q,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to return; sys_id and sys_updated_on are always included.") List<Object> fields,
            @Input(name = "max_records", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Maximum records.") int max,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth token from the vault.") Object token) {
        String s = since == null ? "" : since.trim().replace('T', ' ');
        if (!STAMP.matcher(s).matches()) throw new ActionException(P + ".BAD_TIME", "Invalid time '" + since + "'", "Use yyyy-MM-dd HH:mm:ss in UTC, e.g. 2026-10-19 08:00:00.");
        if (s.length() == 10) s += " 00:00:00"; else if (s.length() == 16) s += ":00";
        String date = s.substring(0, 10), time = s.substring(11);
        String encoded = "sys_updated_on>javascript:gs.dateGenerate('" + date + "','" + time + "')" + (q == null || q.isBlank() ? "" : "^" + q) + "^ORDERBYsys_updated_on";
        List<Map<String, Object>> recs = new ArrayList<>();
        String fieldList = null;
        if (fields != null && !fields.isEmpty()) {
            LinkedHashSet<String> f = new LinkedHashSet<>(List.of("sys_id", "sys_updated_on"));
            for (Object o : fields) f.add(Values.text(o));
            fieldList = String.join(",", f);
        }
        int offset = 0, pageSize = Math.min(1000, Math.max(1, max));
        String latest = s;
        while (recs.size() < max) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sysparm_query", encoded);
            if (fieldList != null) params.put("sysparm_fields", fieldList);
            params.put("sysparm_limit", pageSize);
            params.put("sysparm_offset", offset);
            params.put("sysparm_exclude_reference_link", "true");
            List<?> page = (List<?>) auth(HttpJson.req(P, "GET", api(instance) + "/table/" + table(t) + "?" + HttpJson.query(params)), user, password, token)
                    .timeout(120).send(ctx).map().getOrDefault("result", List.of());
            for (Object o : page) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                recs.add(m);
                Object u = m.get("sys_updated_on");
                if (u != null && Values.text(u).compareTo(latest) > 0) latest = Values.text(u);
                if (recs.size() >= max) break;
            }
            if (page.size() < pageSize) break;
            offset += pageSize;
        }
        return Out.of("table", recs.isEmpty() ? Values.table(List.of(), new ArrayList<>()) : Values.coerce(recs, "Table", "records"), "count", BigDecimal.valueOf(recs.size()), "latest", latest);
    }
}
