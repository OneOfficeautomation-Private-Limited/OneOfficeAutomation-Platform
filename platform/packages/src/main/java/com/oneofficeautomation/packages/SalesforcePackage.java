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
    private static final String D_TOKEN = "OAuth access token from the vault.";

    private static String base(String instance, String version) { return instance.replaceAll("/+$", "") + "/services/data/" + version; }
    private static String object(String o) { if (!NAME.matcher(o).matches()) throw new ActionException(P + ".BAD_OBJECT", "Invalid object name '" + o + "'", "Example: Account, Contact, Case or My_Object__c."); return o; }
    private static String id(String id) { if (!ID.matcher(id).matches()) throw new ActionException(P + ".BAD_ID", "Invalid record id '" + id + "'", "Salesforce ids are 15 or 18 characters."); return id; }

    @Action(id = "authenticate", name = "Authenticate", category = "Authentication", capabilities = "network",
            description = "Get an access token from Salesforce with a connected app (client credentials, or user name and password) for the other Salesforce actions.")
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

    @Action(id = "revoke_authentication", name = "Revoke authentication", category = "Authentication", capabilities = "network", description = "Revoke an access token so it can no longer be used.")
    public Map<String, Object> revoke(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = "The token to revoke.") Object token) {
        String t = EnterpriseAuth.secret(token, P, "access token");
        HttpJson.req(P, "POST", instance.replaceAll("/+$", "") + "/services/oauth2/revoke")
                .bytes(("token=" + HttpJson.enc(t)).getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/x-www-form-urlencoded").send(ctx);
        return Map.of();
    }

    @Action(id = "query", name = "Query (SOQL)", category = "Records", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Run a SOQL query and return all matching records as a table (all result pages are read).")
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
            @Input(name = "batch_size", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Records per page (200-2000); Salesforce's default when 0.") int batch) {
        if (batch != 0 && (batch < 200 || batch > 2000)) throw new ActionException(P + ".BAD_BATCH", "The batch size must be between 200 and 2000");
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

    @Action(id = "get_record", name = "Get record", category = "Records", idempotent = true, capabilities = "network", description = "Read one record by id.")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "Field values.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
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
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, example = "Case", description = "Object API name.") String obj,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.") Map<String, Object> fields,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        Map<String, Object> r = HttpJson.req(P, "POST", base(instance, version) + "/sobjects/" + object(obj)).bearer(token).json(fields).send(ctx).map();
        return Out.of("id", r.get("id"));
    }

    @Action(id = "update_record", name = "Update record", category = "Records", capabilities = "network", description = "Change fields of an existing record.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
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
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, description = "Object API name.") String obj,
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
            description = "Update the record whose external id field matches, or create it when none matches.")
    @Output(name = "id", type = OoaType.STRING, description = "Record id (empty when Salesforce does not return it for an update).")
    @Output(name = "created", type = OoaType.BOOLEAN, description = "True when a new record was created.")
    public Map<String, Object> upsert(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "object", type = OoaType.STRING, example = "Account", description = "Object API name.") String obj,
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
            description = "List the files and classic attachments on a record, and optionally download them to a folder.")
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
            description = "Upload a local file and link it to a record as a Salesforce File.")
    @Output(name = "content_version_id", type = OoaType.STRING, description = "Id of the new file version.")
    @Output(name = "content_document_id", type = OoaType.STRING, description = "Id of the file itself (filled when visibility is set).")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "record_id", type = OoaType.STRING, description = "Id of the record to attach the file to.") String rid,
            @Input(name = "path", type = OoaType.FILE, description = "File to upload.") String path,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Title shown in Salesforce; the file name when empty.") String title,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version,
            @Input(name = "visibility", type = OoaType.STRING, required = false, enumValues = {"AllUsers", "InternalUsers", "SharedUsers"},
                    description = "Who can see the file on the record (AllUsers includes community users); Salesforce's default when empty.") String visibility,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description stored with the file.") String description) {
        Path p = ctx.resolvePath(path);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        if (visibility != null && !visibility.isBlank() && !Set.of("AllUsers", "InternalUsers", "SharedUsers").contains(visibility))
            throw new ActionException(P + ".BAD_VISIBILITY", "Unknown visibility '" + visibility + "'", "Use AllUsers, InternalUsers or SharedUsers.");
        boolean link = visibility != null && !visibility.isBlank();
        String fname = p.getFileName().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Title", title == null || title.isBlank() ? fname.replaceFirst("\\.[^.]*$", "") : title);
        body.put("PathOnClient", fname);
        body.put("VersionData", Base64.getEncoder().encodeToString(data));
        if (description != null && !description.isBlank()) body.put("Description", description);
        if (!link) body.put("FirstPublishLocationId", id(rid)); else id(rid);
        Map<String, Object> r = HttpJson.req(P, "POST", base(instance, version) + "/sobjects/ContentVersion").bearer(token).json(body).timeout(600).send(ctx).map();
        Object docId = null;
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
}
