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
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
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
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "record_id", type = OoaType.STRING, description = "Id of the record the files belong to.") String rid,
            @Input(name = "download_folder", type = OoaType.FILE, required = false, description = "Folder to save the files in; list only when empty.") String folder,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        id(rid);
        List<Object[]> found = new ArrayList<>();  // id, name, size, type, kind, download path
        for (Map<String, Object> r : soql(ctx, instance, token, version, "SELECT ContentDocumentId, ContentDocument.Title, ContentDocument.FileExtension, ContentDocument.ContentSize, "
                + "ContentDocument.LatestPublishedVersionId FROM ContentDocumentLink WHERE LinkedEntityId = '" + rid + "'")) {
            Object ext = r.get("ContentDocument.FileExtension");
            String name = r.get("ContentDocument.Title") + (ext == null || String.valueOf(ext).isEmpty() ? "" : "." + ext);
            found.add(new Object[]{r.get("ContentDocumentId"), name, r.get("ContentDocument.ContentSize"), ext, "file",
                    "/sobjects/ContentVersion/" + r.get("ContentDocument.LatestPublishedVersionId") + "/VersionData"});
        }
        for (Map<String, Object> r : soql(ctx, instance, token, version, "SELECT Id, Name, BodyLength, ContentType FROM Attachment WHERE ParentId = '" + rid + "'"))
            found.add(new Object[]{r.get("Id"), r.get("Name"), r.get("BodyLength"), r.get("ContentType"), "attachment", "/sobjects/Attachment/" + r.get("Id") + "/Body"});
        List<Object> rows = new ArrayList<>(), files = new ArrayList<>();
        Path dir = folder == null || folder.isBlank() ? null : ctx.resolvePath(folder);
        Set<String> used = new HashSet<>();
        for (Object[] f : found) {
            String saved = null;
            if (dir != null) {
                byte[] data = HttpJson.req(P, "GET", base(instance, version) + f[5]).bearer(token).binaryResponse().timeout(600).send(ctx).bytes();
                String n = HttpJson.safeName(String.valueOf(f[1]));
                for (int i = 2; !used.add(n.toLowerCase(Locale.ROOT)); i++) n = i + "_" + HttpJson.safeName(String.valueOf(f[1]));
                try { Files.createDirectories(dir); Files.write(dir.resolve(n), data); }
                catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot save " + n + ": " + e.getMessage(), "Check the folder exists and is writable.", true); }
                saved = dir.resolve(n).toString();
                files.add(saved);
            }
            rows.add(new ArrayList<>(Arrays.asList(f[0], f[1], f[2], f[3], f[4], saved)));
        }
        return Out.of("table", Values.table(List.of("id", "name", "size", "content_type", "kind", "path"), rows), "count", BigDecimal.valueOf(rows.size()), "files", files);
    }

    @Action(id = "upload_attachment", name = "Upload attachment", category = "Files", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Upload a local file and link it to a record as a Salesforce File.")
    @Output(name = "content_version_id", type = OoaType.STRING, description = "Id of the new file version.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "instance_url", type = OoaType.STRING, pattern = "^https://", description = D_URL) String instance,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "record_id", type = OoaType.STRING, description = "Id of the record to attach the file to.") String rid,
            @Input(name = "path", type = OoaType.FILE, description = "File to upload.") String path,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Title shown in Salesforce; the file name when empty.") String title,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"v61.0\"", description = "REST API version.") String version) {
        Path p = ctx.resolvePath(path);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        String fname = p.getFileName().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Title", title == null || title.isBlank() ? fname.replaceFirst("\\.[^.]*$", "") : title);
        body.put("PathOnClient", fname);
        body.put("VersionData", Base64.getEncoder().encodeToString(data));
        body.put("FirstPublishLocationId", id(rid));
        Map<String, Object> r = HttpJson.req(P, "POST", base(instance, version) + "/sobjects/ContentVersion").bearer(token).json(body).timeout(600).send(ctx).map();
        return Out.of("content_version_id", r.get("id"));
    }
}
