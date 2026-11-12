package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Salesforce REST API: SOQL queries and record create, read, update, delete. */
@OoaPackage(id = "ooa.salesforce", name = "Salesforce", version = "1.0.0", description = "Query Salesforce with SOQL and create, read, update and delete records.")
public final class SalesforcePackage {
    static final String P = "SALESFORCE";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,79}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9]{15,18}$");
    private static final String D_URL = "Instance URL, e.g. https://yourcompany.my.salesforce.com.";
    private static final String D_TOKEN = "OAuth access token from the vault. Or leave it empty and choose a connection that signs in, which gets and renews the token.";

    private static String base(String instance, String version) { return instance.replaceAll("/+$", "") + "/services/data/" + version; }
    private static String object(String o) { if (!NAME.matcher(o).matches()) throw new ActionException(P + ".BAD_OBJECT", "Invalid object name '" + o + "'", "Example: Account, Contact, Case or My_Object__c."); return o; }
    private static String id(String id) { if (!ID.matcher(id).matches()) throw new ActionException(P + ".BAD_ID", "Invalid record id '" + id + "'", "Salesforce ids are 15 or 18 characters."); return id; }

    @Action(id = "authenticate", name = "Authenticate", category = "Authentication", capabilities = "network",
            description = "Get an access token from Salesforce with a connected app (client credentials, or user name and password) for the other Salesforce actions.",
            panel = "grant_type, client_id, client_secret, username, password, login_url | security_token")
    @Output(name = "access_token", type = OoaType.CREDENTIAL, description = "Access token; map it to access_token of later actions.")
    @Output(name = "instance_url", type = OoaType.STRING, description = "Instance URL returned by Salesforce; map it to instance_url of later actions.")
    public Map<String, Object> authenticate(ActionContext ctx,
            @Input(name = "login_url", type = OoaType.STRING, required = false, defaultValue = "\"https://login.salesforce.com\"", pattern = HttpJson.HTTPS_OR_LOCAL,
                    description = "Login host: https://login.salesforce.com, https://test.salesforce.com for sandboxes, or your My Domain URL (required for client credentials).") String login,
            @Input(name = "grant_type", type = OoaType.STRING, required = false, defaultValue = "\"client_credentials\"", enumValues = {"client_credentials", "password"},
                    description = "client_credentials: the connected app's run-as user; password: a named user with password and security token.") String grant,
            @Input(name = "client_id", type = OoaType.STRING, description = "Consumer key of the connected app.") String clientId,
            @Input(name = "client_secret", type = OoaType.CREDENTIAL, description = "Consumer secret from the vault.") Object clientSecret,
            @Input(name = "username", type = OoaType.STRING, required = false, description = "User name (password grant only).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault (password grant only).") Object password,
            @Input(name = "security_token", type = OoaType.CREDENTIAL, required = false, description = "Security token, added after the password, when the user's IP is not trusted (password grant only).") Object securityToken) {
        String g = grant == null || grant.isBlank() ? "client_credentials" : grant;
        if (clientId == null || clientId.isBlank()) throw new ActionException(P + ".NO_CLIENT", "The client id (consumer key) is required");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("grant_type", g);
        form.put("client_id", clientId.strip());
        form.put("client_secret", clientSecret);
        if (g.equals("password")) {
            if (user == null || user.isBlank()) throw new ActionException(P + ".NO_USER", "The password grant needs a user name and password");
            form.put("username", user.strip());
            form.put("password", new SecureString(EnterpriseAuth.secret(password, P, "password") + (securityToken instanceof SecureString t ? t.reveal() : ""), "salesforce/password"));
        } else if (!g.equals("client_credentials")) throw new ActionException(P + ".BAD_GRANT", "Unknown grant type '" + grant + "'", "Use client_credentials or password.");
        EnterpriseAuth.secret(clientSecret, P, "client secret");
        String root = (login == null || login.isBlank() ? "https://login.salesforce.com" : login).replaceAll("/+$", "");
        Map<String, Object> r = EnterpriseAuth.token(ctx, P, root + "/services/oauth2/token", form, null, null);
        return Out.of("access_token", EnterpriseAuth.credential(r, "salesforce/session"), "instance_url", r.get("instance_url"));
    }

    @Action(id = "revoke_authentication", name = "Revoke authentication", category = "Authentication", capabilities = "network", description = "Revoke an access token so it can no longer be used.",
            panel = "instance_url, access_token")
    public Map<String, Object> revoke(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = "The token to revoke.") Object token) {
        String t = EnterpriseAuth.secret(token, P, "access token");
        HttpJson.req(P, "POST", instance.replaceAll("/+$", "") + "/services/oauth2/revoke")
                .bytes(("token=" + HttpJson.enc(t)).getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/x-www-form-urlencoded").send(ctx);
        return Map.of();
    }

    @Action(id = "query", name = "Query (SOQL)", category = "Records", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Run a SOQL query and return all matching records as a table (all result pages are read).",
            panel = "soql, parameters, include_deleted, instance_url, access_token | max_records, batch_size, api_version")
    @Output(name = "table", type = OoaType.TABLE, description = "Records.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    @Output(name = "total_size", type = OoaType.NUMBER, description = "Number of matching records Salesforce reports, which can be more than were read.")
    public Map<String, Object> query(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "soql", type = OoaType.STRING, example = "SELECT Id, Name FROM Account WHERE Industry = 'Retail'", description = "SOQL query.") String soql,
            @Input(name = "max_records", type = OoaType.NUMBER, required = false, defaultValue = "50000", description = "Safety limit.") int max,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version,
            @Input(name = "include_deleted", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also return deleted and archived records (queryAll).") boolean deleted,
            @Input(name = "batch_size", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Records per page (200-2000); Salesforce's default when 0.") int batch,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, example = "{\"name\": \"O'Brien\", \"since\": \"2026-01-01\"}",
                    description = "Values for :name in the SOQL, quoted and escaped for you, e.g. WHERE LastName = :name. A list becomes (a, b) for IN. Safer than putting values into the text.") Map<String, Object> params) {
        if (batch != 0 && (batch < 200 || batch > 2000)) throw new ActionException(P + ".BAD_BATCH", "The batch size must be between 200 and 2000");
        soql = bind(soql, params);
        String root = instance.replaceAll("/+$", "");
        String url = base(instance, version) + (deleted ? "/queryAll?q=" : "/query?q=") + HttpJson.enc(soql);
        List<Map<String, Object>> records = new ArrayList<>();
        Object total = null;
        while (url != null && records.size() < max) {
            HttpJson.Req req = HttpJson.req(P, "GET", url).bearer(token).timeout(120);
            if (batch > 0) req.header("Sforce-Query-Options", "batchSize=" + batch);
            Map<String, Object> r = req.send(ctx).map();
            if (total == null) total = r.get("totalSize");
            for (Object o : (List<?>) r.getOrDefault("records", List.of())) { if (records.size() >= max) break; records.add(flatten("", (Map<?, ?>) o)); }
            Object next = r.get("nextRecordsUrl");
            url = Boolean.TRUE.equals(r.get("done")) || next == null ? null : root + next;
        }
        return Out.of("table", Values.coerce(records, "Table", "records"), "count", BigDecimal.valueOf(records.size()),
                "total_size", total == null ? BigDecimal.valueOf(records.size()) : Values.toNumber(total));
    }

    /** Earlier signature, kept for callers written before parameters (K6, batch 10). */
    public Map<String, Object> query(ActionContext ctx, String instance, Object token, String soql, int max, String version, boolean deleted, int batch) {
        return query(ctx, instance, token, soql, max, version, deleted, batch, null);
    }

    // ---------- values in SOQL, safely (K6, batch 10) ----------

    private static final Pattern FIELD = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,79}(\\.[A-Za-z][A-Za-z0-9_]{0,79}){0,4}$");

    /** A value as a SOQL literal: text quoted with \\ \' \" and line breaks escaped, numbers and true/false bare, dates as SOQL dates, a list as (a, b). */
    static String literal(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return b.toString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).toPlainString();
        if (v instanceof java.time.LocalDate d) return d.toString();
        if (v instanceof java.time.LocalDateTime t) return t.withNano(0) + "Z";
        if (v instanceof java.time.OffsetDateTime t) return t.withNano(0).toString();
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) throw new ActionException(P + ".BAD_PARAMETER", "An empty list cannot go into SOQL", "Give at least one value, or leave the condition out.");
            StringJoiner j = new StringJoiner(", ", "(", ")");
            for (Object o : c) j.add(literal(o));
            return j.toString();
        }
        String t = Values.text(v);
        StringBuilder b = new StringBuilder("'");
        for (char ch : t.toCharArray()) switch (ch) {
            case '\\' -> b.append("\\\\");
            case '\'' -> b.append("\\'");
            case '"' -> b.append("\\\"");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default -> b.append(ch);
        }
        return b.append('\'').toString();
    }

    /** Puts each parameter in place of :name outside quoted text; a :name with no parameter is left for Salesforce to refuse. */
    static String bind(String soql, Map<String, Object> params) {
        if (params == null || params.isEmpty() || soql == null) return soql;
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < soql.length(); i++) {
            char c = soql.charAt(i);
            if (c == '\\' && quoted && i + 1 < soql.length()) { out.append(c).append(soql.charAt(++i)); continue; }
            if (c == '\'') quoted = !quoted;
            if (c == ':' && !quoted && i + 1 < soql.length() && Character.isLetter(soql.charAt(i + 1))) {
                int j = i + 1;
                while (j < soql.length() && (Character.isLetterOrDigit(soql.charAt(j)) || soql.charAt(j) == '_')) j++;
                String name = soql.substring(i + 1, j);
                if (params.containsKey(name)) { out.append(literal(params.get(name))); i = j - 1; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }

    @Action(id = "find_records", name = "Find records", category = "Records", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Find records of an object whose fields have the values you give (each value quoted and escaped), without writing SOQL.",
            panel = "object, conditions, fields, instance_url, access_token | order_by, max_records, include_deleted, api_version")
    @Output(name = "table", type = OoaType.TABLE, description = "Records, one column per field asked for.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    @Output(name = "soql", type = OoaType.STRING, description = "The SOQL that was run.")
    public Map<String, Object> findRecords(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", example = "Contact", description = "Object API name.") String obj,
            @Input(name = "conditions", type = OoaType.DICTIONARY, required = false, example = "{\"LastName\": \"O'Brien\", \"Account.Industry\": [\"Retail\", \"Banking\"]}",
                    description = "Field to the value it must have; a list means any of them. Every condition must hold. Empty: every record.") Map<String, Object> conditions,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to read, e.g. [\"Id\", \"Name\", \"Email\"]. Id and Name when empty.") List<Object> fields,
            @Input(name = "order_by", type = OoaType.STRING, required = false, example = "CreatedDate DESC", description = "A field to sort by, then ASC or DESC.") String orderBy,
            @Input(name = "max_records", type = OoaType.NUMBER, required = false, defaultValue = "2000", min = 1, description = "At most this many records.") int max,
            @Input(name = "include_deleted", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also return deleted and archived records.") boolean deleted,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        List<String> cols = new ArrayList<>();
        if (fields != null) for (Object f : fields) cols.add(field(Values.text(f).strip()));
        if (cols.isEmpty()) cols.addAll(List.of("Id", "Name"));
        StringBuilder q = new StringBuilder("SELECT ").append(String.join(", ", cols)).append(" FROM ").append(object(obj.strip()));
        if (conditions != null && !conditions.isEmpty()) {
            StringJoiner where = new StringJoiner(" AND ");
            for (Map.Entry<String, Object> e : conditions.entrySet())
                where.add(field(e.getKey().strip()) + (e.getValue() instanceof Collection<?> ? " IN " : e.getValue() == null ? " = " : " = ") + literal(e.getValue()));
            q.append(" WHERE ").append(where);
        }
        if (orderBy != null && !orderBy.isBlank()) {
            String[] parts = orderBy.strip().split("\\s+");
            if (parts.length > 2 || (parts.length == 2 && !parts[1].matches("(?i)ASC|DESC"))) throw new ActionException(P + ".BAD_ORDER", "Sort by one field, then ASC or DESC", "Example: CreatedDate DESC.");
            q.append(" ORDER BY ").append(field(parts[0])).append(parts.length == 2 ? " " + parts[1].toUpperCase(Locale.ROOT) : "");
        }
        q.append(" LIMIT ").append(Math.max(1, max));
        Map<String, Object> r = query(ctx, instance, token, q.toString(), max, version, deleted, 0, null);
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.remove("total_size");
        out.put("soql", q.toString());
        return out;
    }

    private static String field(String f) {
        if (!FIELD.matcher(f).matches()) throw new ActionException(P + ".BAD_FIELD", "Invalid field name '" + f + "'", "Example: Name, Email or Account.Industry.");
        return f;
    }

    /** Earlier signature, kept for callers written before include_deleted and batch_size. */
    public Map<String, Object> query(ActionContext ctx, String instance, Object token, String soql, int max, String version) { return query(ctx, instance, token, soql, max, version, false, 0); }

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

    /** Earlier signature, kept for callers written before external_id_field. */
    public Map<String, Object> get(ActionContext ctx, String instance, Object token, String obj, String rid, List<Object> fields, String version) {
        return get(ctx, instance, token, obj, rid, fields, version, null);
    }

    @Action(id = "get_record", name = "Get record", category = "Records", idempotent = true, capabilities = "network",
            description = "Read one record by its Salesforce id, or by the value of an external id field.",
            panel = "object, id, fields, instance_url, access_token | external_id_field, api_version")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "Field values.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", example = "Account", description = "Object API name.") String obj,
            @Input(name = "id", type = OoaType.STRING, description = "Record id, or the external id value when external_id_field is filled in.") String rid,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to return; all when empty.") List<Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version,
            @Input(name = "external_id_field", type = OoaType.STRING, required = false, example = "ERP_Number__c",
                    description = "API name of an external id field; when filled in, the record whose field has the value in id is read.") String externalField) {
        String url;
        if (externalField != null && !externalField.isBlank()) {
            if (rid == null || rid.isBlank()) throw new ActionException(P + ".NO_EXTERNAL_ID", "The external id value is empty", "Put the external id value in id.");
            url = base(instance, version) + "/sobjects/" + object(obj) + "/" + object(externalField.strip()) + "/" + HttpJson.enc(rid.strip());
        } else url = base(instance, version) + "/sobjects/" + object(obj) + "/" + id(rid);
        if (fields != null && !fields.isEmpty()) { StringJoiner j = new StringJoiner(","); for (Object f : fields) j.add(object(Values.text(f).replace(".", "_x_")).replace("_x_", ".")); url += "?fields=" + HttpJson.enc(j.toString()); }
        HttpJson.Response resp = HttpJson.req(P, "GET", url).bearer(token).send(ctx);
        if (resp.status() == 300 || resp.json() instanceof List<?>)   // several records share the external id value
            throw new ActionException(P + ".AMBIGUOUS_EXTERNAL_ID", "More than one " + obj + " record has " + externalField + " = " + rid,
                    "Use a field whose values are unique, or read the record by its Salesforce id.");
        return Map.of("record", flatten("", resp.map()));
    }

    @Action(id = "create_record", name = "Create record", category = "Records", capabilities = "network", description = "Create a record and return its id.",
            panel = "object, fields, instance_url, access_token | api_version")
    @Output(name = "id", type = OoaType.STRING, description = "New record id.")
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", example = "Case", description = "Object API name.") String obj,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.") Map<String, Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        Map<String, Object> r = HttpJson.req(P, "POST", base(instance, version) + "/sobjects/" + object(obj)).bearer(token).json(fields).send(ctx).map();
        return Out.of("id", r.get("id"));
    }

    // ---------- many records at once, and what an object has (K7, command review batch 10) ----------

    /** Records a call of the sObject Collections API (Salesforce's limit). */
    static final int COLLECTION_SIZE = 200;

    @Action(id = "save_records", name = "Create, update or upsert many records", category = "Records", timeoutSeconds = 3600, capabilities = "network",
            description = "Write the rows of a Data Table as records, 200 a call (Salesforce's collections API), and get a result for each row.",
            panel = "object, records, operation, external_id_field, instance_url, access_token | all_or_none, api_version")
    @Output(name = "results", type = OoaType.TABLE, description = "One row per record: row (1, 2, ...), id, success, created, error.")
    @Output(name = "succeeded", type = OoaType.NUMBER, description = "How many records were written.")
    @Output(name = "failed", type = OoaType.NUMBER, description = "How many were not.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveRecords(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", example = "Contact", description = "Object API name.") String obj,
            @Input(name = "records", type = OoaType.TABLE, description = "One row per record; the column names are the field API names (an Id column to update).") Map<String, Object> table,
            @Input(name = "operation", type = OoaType.STRING, required = false, defaultValue = "\"create\"", enumValues = {"create", "update", "upsert"},
                    description = "Create: new records. Update: change the records in the Id column. Upsert: create or update by the external id field.") String op,
            @Input(name = "external_id_field", type = OoaType.STRING, required = false, visibleWhen = "operation=upsert", example = "ERP_Number__c", description = "For upsert: the external id field that finds an existing record.") String extField,
            @Input(name = "all_or_none", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Write a call's records only if all of them can be written (otherwise the good ones are written and the others get an error).") boolean allOrNone,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        String o = object(obj);
        String mode = op == null || op.isBlank() ? "create" : op;
        if (mode.equals("upsert") && (extField == null || extField.isBlank())) throw new ActionException(P + ".NO_EXTERNAL_ID", "Upsert needs the external id field", "Example: ERP_Number__c.");
        List<String> cols = new ArrayList<>();
        for (Object c : table == null ? List.of() : (List<?>) table.getOrDefault("columns", List.of())) cols.add(field(Values.text(c)));
        List<Object> rows = table == null ? List.of() : (List<Object>) table.getOrDefault("rows", List.of());
        if (mode.equals("update") && cols.stream().noneMatch(c -> c.equalsIgnoreCase("Id"))) throw new ActionException(P + ".NO_ID_COLUMN", "Update needs an Id column", "Add the record ids in a column named Id.");
        List<Object> results = new ArrayList<>();
        int ok = 0, bad = 0;
        for (int from = 0; from < rows.size(); from += COLLECTION_SIZE) {
            List<Object> recs = new ArrayList<>();
            for (Object r : rows.subList(from, Math.min(rows.size(), from + COLLECTION_SIZE))) {
                List<?> cells = (List<?>) r;
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("attributes", Map.of("type", o));
                for (int i = 0; i < cols.size() && i < cells.size(); i++) if (cells.get(i) != null && !"".equals(cells.get(i))) rec.put(cols.get(i).equalsIgnoreCase("Id") ? "Id" : cols.get(i), cells.get(i));
                recs.add(rec);
            }
            Map<String, Object> body = Map.of("allOrNone", allOrNone, "records", recs);
            String url = base(instance, version) + "/composite/sobjects" + (mode.equals("upsert") ? "/" + o + "/" + field(extField.strip()) : "");
            Object answer = HttpJson.req(P, mode.equals("create") ? "POST" : "PATCH", url).bearer(token).json(body).timeout(600).send(ctx).json();
            List<?> list = answer instanceof List<?> l ? l : List.of();
            for (int i = 0; i < list.size(); i++) {
                Map<?, ?> res = (Map<?, ?>) list.get(i);
                boolean success = Boolean.TRUE.equals(res.get("success"));
                if (success) ok++; else bad++;
                StringJoiner err = new StringJoiner("; ");
                for (Map<String, Object> e : HttpJson.maps(res.get("errors"))) err.add(e.get("statusCode") + ": " + e.get("message"));
                results.add(new ArrayList<>(Arrays.asList(BigDecimal.valueOf(from + i + 1), res.get("id"), success, res.get("created"), err.length() == 0 ? null : err.toString())));
            }
            ctx.info("Wrote " + Math.min(rows.size(), from + COLLECTION_SIZE) + " of " + rows.size() + " records");
        }
        return Out.of("results", Values.table(List.of("row", "id", "success", "created", "error"), results), "succeeded", BigDecimal.valueOf(ok), "failed", BigDecimal.valueOf(bad));
    }

    @Action(id = "describe_object", name = "Describe object", category = "Records", idempotent = true, capabilities = "network",
            description = "Read what an object has: its fields with their labels, types, lengths, whether they are required, picklist values and lookups.",
            panel = "object, instance_url, access_token | api_version")
    @Output(name = "fields", type = OoaType.TABLE, description = "Columns: name, label, type, length, required, updateable, picklist_values, reference_to.")
    @Output(name = "object", type = OoaType.DICTIONARY, description = "name, label, label_plural, key_prefix, createable, updateable, deletable, custom.")
    public Map<String, Object> describeObject(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", example = "Case", description = "Object API name.") String obj,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        Map<String, Object> d = HttpJson.req(P, "GET", base(instance, version) + "/sobjects/" + object(obj) + "/describe").bearer(token).send(ctx).map();
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> f : HttpJson.maps(d.get("fields"))) {
            List<Object> picks = new ArrayList<>();
            for (Map<String, Object> pv : HttpJson.maps(f.get("picklistValues"))) if (!Boolean.FALSE.equals(pv.get("active"))) picks.add(pv.get("value"));
            boolean required = Boolean.TRUE.equals(f.get("createable")) && Boolean.FALSE.equals(f.get("nillable")) && !Boolean.TRUE.equals(f.get("defaultedOnCreate"));
            rows.add(new ArrayList<>(Arrays.asList(f.get("name"), f.get("label"), f.get("type"), f.get("length"), required, f.get("updateable"), picks, f.get("referenceTo"))));
        }
        Map<String, Object> o = new LinkedHashMap<>();
        for (String[] k : new String[][]{{"name", "name"}, {"label", "label"}, {"label_plural", "labelPlural"}, {"key_prefix", "keyPrefix"}, {"createable", "createable"},
                {"updateable", "updateable"}, {"deletable", "deletable"}, {"custom", "custom"}}) o.put(k[0], d.get(k[1]));
        return Out.of("fields", Values.table(List.of("name", "label", "type", "length", "required", "updateable", "picklist_values", "reference_to"), rows), "object", o);
    }

    @Action(id = "update_record", name = "Update record", category = "Records", capabilities = "network", description = "Change fields of an existing record.",
            panel = "object, id, fields, instance_url, access_token | api_version")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", description = "Object API name.") String obj,
            @Input(name = "id", type = OoaType.STRING, description = "Record id.") String rid,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to new value.") Map<String, Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        HttpJson.req(P, "PATCH", base(instance, version) + "/sobjects/" + object(obj) + "/" + id(rid)).bearer(token).json(fields).send(ctx);
        return Map.of();
    }

    @Action(id = "delete_record", name = "Delete record", category = "Records", capabilities = "network", description = "Delete a record (it goes to the Salesforce recycle bin).",
            panel = "object, id, instance_url, access_token | api_version")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", description = "Object API name.") String obj,
            @Input(name = "id", type = OoaType.STRING, description = "Record id.") String rid,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        HttpJson.req(P, "DELETE", base(instance, version) + "/sobjects/" + object(obj) + "/" + id(rid)).bearer(token).send(ctx);
        return Map.of();
    }

    // ---- Upsert and files ----

    private static List<Map<String, Object>> soql(ActionContext ctx, String instance, Object token, String version, String soql) {
        String root = instance.replaceAll("/+$", "");
        String url = base(instance, version) + "/query?q=" + HttpJson.enc(soql);
        List<Map<String, Object>> records = new ArrayList<>();
        while (url != null) {
            Map<String, Object> r = HttpJson.req(P, "GET", url).bearer(token).timeout(120).send(ctx).map();
            for (Object o : (List<?>) r.getOrDefault("records", List.of())) records.add(flatten("", (Map<?, ?>) o));
            Object next = r.get("nextRecordsUrl");
            url = Boolean.TRUE.equals(r.get("done")) || next == null ? null : root + next;
        }
        return records;
    }

    @Action(id = "upsert_by_external_id", name = "Upsert by external id", category = "Records", idempotent = true, capabilities = "network",
            description = "Update the record whose external id field matches, or create it when none matches.",
            panel = "object, external_id_field, external_id_value, fields, instance_url, access_token | api_version")
    @Output(name = "id", type = OoaType.STRING, description = "Record id (empty when Salesforce does not return it for an update).")
    @Output(name = "created", type = OoaType.BOOLEAN, description = "True when a new record was created.")
    public Map<String, Object> upsert(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, label = "Object (API name)", example = "Account", description = "Object API name.") String obj,
            @Input(name = "external_id_field", type = OoaType.STRING, example = "ERP_Number__c", description = "API name of the external id field.") String field,
            @Input(name = "external_id_value", type = OoaType.STRING, description = "External id value to match.") String value,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value (do not repeat the external id field).") Map<String, Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        if (value == null || value.isBlank()) throw new ActionException(P + ".NO_EXTERNAL_ID", "The external id value is empty", "Map external_id_value to the key from the source system.");
        HttpJson.Response r = HttpJson.req(P, "PATCH", base(instance, version) + "/sobjects/" + object(obj) + "/" + object(field) + "/" + HttpJson.enc(value))
                .bearer(token).json(fields == null ? Map.of() : fields).send(ctx);
        Map<String, Object> m = r.map();
        boolean created = r.status() == 201 || Boolean.TRUE.equals(m.get("created"));
        return Out.of("id", m.get("id"), "created", created);
    }

    @Action(id = "get_attachments", name = "Get attachments", category = "Files", idempotent = true, timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "List the files and classic attachments on a record, and optionally download them to a folder.",
            panel = "record_id, download_folder, kind, [Only files with matching names: name_pattern], instance_url, access_token | overwrite, api_version")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, size, content_type, kind (file or attachment), path.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of files.")
    @Output(name = "files", type = OoaType.LIST, description = "Paths of downloaded files (empty when not downloading).")
    public Map<String, Object> attachments(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "record_id", type = OoaType.STRING, description = "Id of the record the files belong to.") String rid,
            @Input(name = "download_folder", type = OoaType.FILE, required = false, description = "Folder to save the files in; list only when empty.") String folder,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version,
            @Input(name = "kind", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "file", "attachment"}, description = "Only Salesforce Files (file), only classic attachments (attachment), or both.") String kind,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, example = "*.pdf", description = "Only files whose name matches this pattern (* and ?, case ignored); all when empty.") String pattern,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace files of the same name in the folder; when off, a number is put in front of the new file's name.") boolean overwrite) {
        id(rid);
        String k = kind == null || kind.isBlank() ? "all" : kind;
        java.util.regex.Pattern glob = pattern == null || pattern.isBlank() ? null
                : java.util.regex.Pattern.compile("(?i)" + java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
        List<Object[]> found = new ArrayList<>();  // id, name, size, type, kind, download path
        if (!k.equals("attachment")) for (Map<String, Object> r : soql(ctx, instance, token, version, "SELECT ContentDocumentId, ContentDocument.Title, ContentDocument.FileExtension, ContentDocument.ContentSize, "
                + "ContentDocument.LatestPublishedVersionId FROM ContentDocumentLink WHERE LinkedEntityId = '" + rid + "'")) {
            Object ext = r.get("ContentDocument.FileExtension");
            String name = r.get("ContentDocument.Title") + (ext == null || String.valueOf(ext).isEmpty() ? "" : "." + ext);
            found.add(new Object[]{r.get("ContentDocumentId"), name, r.get("ContentDocument.ContentSize"), ext, "file",
                    "/sobjects/ContentVersion/" + r.get("ContentDocument.LatestPublishedVersionId") + "/VersionData"});
        }
        if (!k.equals("file")) for (Map<String, Object> r : soql(ctx, instance, token, version, "SELECT Id, Name, BodyLength, ContentType FROM Attachment WHERE ParentId = '" + rid + "'"))
            found.add(new Object[]{r.get("Id"), r.get("Name"), r.get("BodyLength"), r.get("ContentType"), "attachment", "/sobjects/Attachment/" + r.get("Id") + "/Body"});
        if (glob != null) found.removeIf(f -> !glob.matcher(String.valueOf(f[1])).matches());
        List<Object> rows = new ArrayList<>(), files = new ArrayList<>();
        Path dir = folder == null || folder.isBlank() ? null : ctx.resolvePath(folder);
        Set<String> used = new HashSet<>();
        for (Object[] f : found) {
            String saved = null;
            if (dir != null) {
                byte[] data = HttpJson.req(P, "GET", base(instance, version) + f[5]).bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
                String n = HttpJson.safeName(String.valueOf(f[1]));
                for (int i = 2; !used.add(n.toLowerCase(Locale.ROOT)) || (!overwrite && Files.exists(dir.resolve(n))); i++) n = i + "_" + HttpJson.safeName(String.valueOf(f[1]));
                try { Files.createDirectories(dir); Files.write(dir.resolve(n), data); }
                catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot save " + n + ": " + e.getMessage(), "Check the folder exists and is writable.", true); }
                saved = dir.resolve(n).toString();
                files.add(saved);
            }
            rows.add(new ArrayList<>(Arrays.asList(f[0], f[1], f[2], f[3], f[4], saved)));
        }
        return Out.of("table", Values.table(List.of("id", "name", "size", "content_type", "kind", "path"), rows), "count", BigDecimal.valueOf(rows.size()), "files", files);
    }

    /** Earlier signature, kept for callers written before kind, name_pattern and overwrite. */
    public Map<String, Object> attachments(ActionContext ctx, String instance, Object token, String rid, String folder, String version) {
        return attachments(ctx, instance, token, rid, folder, version, "all", null, true);
    }

    @Action(id = "upload_attachment", name = "Upload attachment", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Upload a local file and link it to a record as a Salesforce File, or upload it as a new version of an existing file.",
            panel = "record_id, path, title, visibility, instance_url, access_token | content_document_id, description, api_version")
    @Output(name = "content_version_id", type = OoaType.STRING, description = "Id of the new file version.")
    @Output(name = "content_document_id", type = OoaType.STRING, description = "Id of the file itself (filled when visibility or content_document_id is set).")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "record_id", type = OoaType.STRING, required = false, description = "Id of the record to attach the file to. Not needed when content_document_id is filled in.") String rid,
            @Input(name = "path", type = OoaType.FILE, description = "File to upload.") String path,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Title shown in Salesforce; the file name when empty.") String title,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version,
            @Input(name = "visibility", type = OoaType.STRING, required = false, enumValues = {"AllUsers", "InternalUsers", "SharedUsers"},
                    description = "Who can see the file on the record (AllUsers includes community users); Salesforce's default when empty.") String visibility,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description stored with the file.") String description,
            @Input(name = "content_document_id", type = OoaType.STRING, required = false, example = "069000000000001AAA",
                    description = "Id of an existing file (starts with 069); the upload becomes its new version and keeps the file's links and sharing.") String documentId) {
        boolean newVersion = documentId != null && !documentId.isBlank();
        if (visibility != null && !visibility.isBlank() && !Set.of("AllUsers", "InternalUsers", "SharedUsers").contains(visibility))
            throw new ActionException(P + ".BAD_VISIBILITY", "Unknown visibility '" + visibility + "'", "Use AllUsers, InternalUsers or SharedUsers.");
        boolean link = visibility != null && !visibility.isBlank();
        if (newVersion) {
            if (!ID.matcher(documentId.strip()).matches() || !documentId.strip().startsWith("069"))
                throw new ActionException(P + ".BAD_ID", "Invalid file id '" + documentId + "'", "A file (ContentDocument) id starts with 069 and has 15 or 18 characters.");
            if (link) throw new ActionException(P + ".VISIBILITY_WITH_VERSION", "Visibility cannot be set when uploading a new version",
                    "Leave visibility empty: a new version keeps the sharing of the existing file.");
        } else if (rid == null || rid.isBlank())
            throw new ActionException(P + ".NO_RECORD", "No record id given", "Fill in record_id, or content_document_id to upload a new version of a file.");
        Path p = ctx.resolvePath(path);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        String fname = p.getFileName().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Title", title == null || title.isBlank() ? fname.replaceFirst("\\.[^.]*$", "") : title);
        body.put("PathOnClient", fname);
        body.put("VersionData", Base64.getEncoder().encodeToString(data));
        if (description != null && !description.isBlank()) body.put("Description", description);
        if (newVersion) body.put("ContentDocumentId", documentId.strip());
        else if (!link) body.put("FirstPublishLocationId", id(rid)); else id(rid);
        Map<String, Object> r = HttpJson.req(P, "POST", base(instance, version) + "/sobjects/ContentVersion").bearer(token).json(body).timeout(600).send(ctx).map();
        Object docId = newVersion ? documentId.strip() : null;
        if (link) {
            // A file without a first location is linked explicitly, which is the only way to choose its visibility.
            Map<String, Object> v = HttpJson.req(P, "GET", base(instance, version) + "/sobjects/ContentVersion/" + id(String.valueOf(r.get("id"))) + "?fields=ContentDocumentId").bearer(token).send(ctx).map();
            docId = v.get("ContentDocumentId");
            HttpJson.req(P, "POST", base(instance, version) + "/sobjects/ContentDocumentLink").bearer(token)
                    .json(Map.of("ContentDocumentId", String.valueOf(docId), "LinkedEntityId", rid, "ShareType", "V", "Visibility", visibility)).send(ctx);
        }
        return Out.of("content_version_id", r.get("id"), "content_document_id", docId);
    }

    /** Earlier signature, kept for callers written before visibility and description. */
    public Map<String, Object> upload(ActionContext ctx, String instance, Object token, String rid, String path, String title, String version) {
        return upload(ctx, instance, token, rid, path, title, version, null, null);
    }

    /** Earlier signature, kept for callers written before content_document_id. */
    public Map<String, Object> upload(ActionContext ctx, String instance, Object token, String rid, String path, String title, String version,
            String visibility, String description) {
        return upload(ctx, instance, token, rid, path, title, version, visibility, description, null);
    }
}
