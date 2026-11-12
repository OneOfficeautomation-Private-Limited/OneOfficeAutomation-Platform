package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Business data store (gap G66): records in One CommandCenter's entities and files in its storage buckets, read and
 * written while the agent runs. On a runner connected to One CommandCenter every step goes there (the server checks
 * the fields, the folder and the run); in Studio with no One CommandCenter, entities are JSON files under
 * data-store/ and buckets folders under buckets/ in the work folder, with no field checks, so agents can be tried out.
 */
@OoaPackage(id = "ooa.data", name = "Data store", version = "1.0.0",
        description = "Create, change, read, query and delete records in One CommandCenter's entities; upload, download and list files in storage buckets.")
public final class DataStorePackage {

    /** Where the data is: One CommandCenter (through the runner) or the work folder. */
    interface Store { Map<String, Object> call(Map<String, Object> request); }

    @SuppressWarnings("unchecked")
    static Store store(ActionContext ctx) {
        Function<Map<String, Object>, Map<String, Object>> remote;
        try { remote = ctx.service("data_store", Function.class); }
        catch (OoaException e) { remote = null; }
        if (remote != null) return remote::apply;
        return req -> Local.call(ctx.workDir(), req);
    }

    static Map<String, Object> req(String op, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String need(String v, String what) {
        if (v == null || v.isBlank()) throw new ActionException("DATA.MISSING", "Give the " + what, "Fill in what the message names.");
        return v.trim();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flat(Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rec.get("id"));
        if (rec.get("data") instanceof Map<?, ?> d) out.putAll((Map<String, Object>) d);
        return out;
    }

    @Action(id = "create_record", name = "Create record", category = "Records", capabilities = {"network"},
            description = "Add a record to an entity; the fields are checked against the entity (types, required, unique).",
            panel = "entity, values")
    @Output(name = "id", type = OoaType.STRING, description = "The new record's id.")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "The record as saved: id and its fields.")
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "entity", type = OoaType.STRING, example = "Suppliers", description = "The entity's name, as in Manage > Data store.") String entity,
            @Input(name = "values", type = OoaType.DICTIONARY, example = "{\"name\": \"ACME\", \"country\": \"IN\"}", description = "Field to value.") Map<String, Object> values) {
        Map<String, Object> r = store(ctx).call(req("create", "entity", need(entity, "entity"), "data", values == null ? Map.of() : values));
        return Map.of("id", r.get("id"), "record", flat(r));
    }

    @Action(id = "update_record", name = "Update record", category = "Records", capabilities = {"network"},
            description = "Change some fields of a record; fields not given keep their values, and an empty value clears a field.",
            panel = "entity, id, values")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "The record as saved.")
    public Map<String, Object> update(ActionContext ctx,
            @Input(name = "entity", type = OoaType.STRING, description = "The entity's name.") String entity,
            @Input(name = "id", type = OoaType.STRING, description = "The record's id.") String id,
            @Input(name = "values", type = OoaType.DICTIONARY, description = "Field to new value.") Map<String, Object> values) {
        return Map.of("record", flat(store(ctx).call(req("update", "entity", need(entity, "entity"), "id", need(id, "record id"), "data", values == null ? Map.of() : values))));
    }

    @Action(id = "get_record", name = "Get record", category = "Records", idempotent = true, capabilities = {"network"},
            description = "Read one record by its id.", panel = "entity, id")
    @Output(name = "record", type = OoaType.DICTIONARY, description = "id and the record's fields.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "entity", type = OoaType.STRING, description = "The entity's name.") String entity,
            @Input(name = "id", type = OoaType.STRING, description = "The record's id.") String id) {
        return Map.of("record", flat(store(ctx).call(req("get", "entity", need(entity, "entity"), "id", need(id, "record id")))));
    }

    @Action(id = "delete_record", name = "Delete record", category = "Records", capabilities = {"network"},
            description = "Delete a record; deleting one that is not there changes nothing and says so.", panel = "entity, id")
    @Output(name = "deleted", type = OoaType.BOOLEAN, description = "True when the record was there.")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "entity", type = OoaType.STRING, description = "The entity's name.") String entity,
            @Input(name = "id", type = OoaType.STRING, description = "The record's id.") String id) {
        return Map.of("deleted", Boolean.TRUE.equals(store(ctx).call(req("delete", "entity", need(entity, "entity"), "id", need(id, "record id"))).get("deleted")));
    }

    @Action(id = "query_records", name = "Query records", category = "Records", idempotent = true, capabilities = {"network"},
            description = "The records matching every filter, sorted, as a table (and as a list of dictionaries).",
            panel = "entity, filters | sort_by, descending, limit, offset")
    @Output(name = "table", type = OoaType.TABLE, description = "id and every field, one row per record.")
    @Output(name = "records", type = OoaType.LIST, description = "The records as dictionaries.")
    @Output(name = "total", type = OoaType.NUMBER, description = "How many records match (all pages).")
    @SuppressWarnings("unchecked")
    public Map<String, Object> query(ActionContext ctx,
            @Input(name = "entity", type = OoaType.STRING, description = "The entity's name.") String entity,
            @Input(name = "filters", type = OoaType.LIST, required = false, example = "[{\"field\": \"country\", \"op\": \"=\", \"value\": \"IN\"}]",
                    description = "Each {field, op, value}; op is =, !=, >, >=, <, <=, contains, starts_with, empty or not_empty. Text compares without case.") List<Object> filters,
            @Input(name = "sort_by", type = OoaType.STRING, required = false, description = "A field to sort by.") String sort,
            @Input(name = "descending", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Largest or latest first.") boolean desc,
            @Input(name = "limit", type = OoaType.NUMBER, required = false, defaultValue = "1000", min = 1, max = 5000, description = "At most this many records.") int limit,
            @Input(name = "offset", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Skip this many first (for pages).") int offset) {
        Map<String, Object> r = store(ctx).call(req("query", "entity", need(entity, "entity"), "filters", filters == null ? List.of() : filters, "sort", sort,
            "descending", desc, "limit", limit, "offset", offset));
        List<Object> recs = new ArrayList<>();
        LinkedHashSet<String> cols = new LinkedHashSet<>(List.of("id"));
        if (r.get("fields") instanceof List<?> fs) for (Object f : fs) if (f instanceof Map<?, ?> fm) cols.add(String.valueOf(fm.get("name")));
        for (Object o : (List<Object>) r.getOrDefault("records", List.of())) { Map<String, Object> f = flat((Map<String, Object>) o); cols.addAll(f.keySet()); recs.add(f); }
        List<List<Object>> rows = new ArrayList<>();
        for (Object o : recs) { Map<String, Object> f = (Map<String, Object>) o; List<Object> row = new ArrayList<>(); for (String c : cols) row.add(f.get(c)); rows.add(row); }
        return Map.of("table", Map.of("columns", new ArrayList<>(cols), "rows", rows), "records", recs, "total", r.getOrDefault("total", recs.size()));
    }

    @Action(id = "upload_file", name = "Upload file to bucket", category = "Storage buckets", capabilities = {"network", "filesystem"},
            description = "Put a file from the run's folder into a storage bucket, at a path such as invoices/2026/acme.pdf.",
            panel = "file, bucket, path | overwrite")
    @Output(name = "path", type = OoaType.STRING, description = "Its path in the bucket.")
    @Output(name = "bytes", type = OoaType.NUMBER, description = "Its size.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, description = "The file to upload.") String file,
            @Input(name = "bucket", type = OoaType.STRING, description = "The bucket's name.") String bucket,
            @Input(name = "path", type = OoaType.STRING, required = false, description = "Its path in the bucket (empty: the file's name).") String path,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file already at that path.") boolean overwrite) {
        Path p = ctx.resolvePath(need(file, "file"));
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path; the file may have been moved or renamed.");
        byte[] b;
        try { b = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        if (b.length > 200L * 1024 * 1024) throw new ActionException("DATA.TOO_LARGE", "Files of up to 200 MB go to a bucket", "Store files larger than 200 MB in a share the runner reads, not in a bucket.");
        String to = path == null || path.isBlank() ? p.getFileName().toString() : path.trim();
        Map<String, Object> r = store(ctx).call(req("upload", "bucket", need(bucket, "bucket"), "path", to, "content", Base64.getEncoder().encodeToString(b), "overwrite", overwrite));
        return Map.of("path", r.getOrDefault("path", to), "bytes", r.getOrDefault("bytes", b.length));
    }

    @Action(id = "download_file", name = "Download file from bucket", category = "Storage buckets", capabilities = {"network", "filesystem"},
            description = "Save a file from a storage bucket in the run's folder.", panel = "bucket, path, save_as")
    @Output(name = "file", type = OoaType.FILE, description = "Where it was saved.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "bucket", type = OoaType.STRING, description = "The bucket's name.") String bucket,
            @Input(name = "path", type = OoaType.STRING, description = "Its path in the bucket.") String path,
            @Input(name = "save_as", type = OoaType.FILE, required = false, description = "Where to save it (empty: its name, in the run's folder).") String saveAs) {
        Map<String, Object> r = store(ctx).call(req("download", "bucket", need(bucket, "bucket"), "path", need(path, "path")));
        String name = path.replace('\\', '/');
        Path out = ctx.resolvePath(saveAs == null || saveAs.isBlank() ? name.substring(name.lastIndexOf('/') + 1) : saveAs);
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, Base64.getDecoder().decode(String.valueOf(r.get("content"))));
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Map.of("file", out.toString());
    }

    @Action(id = "list_files", name = "List files in bucket", category = "Storage buckets", idempotent = true, capabilities = {"network"},
            description = "The bucket's files, optionally only those whose path starts with a prefix.", panel = "bucket | prefix")
    @Output(name = "files", type = OoaType.TABLE, description = "path, bytes, modified.")
    @Output(name = "paths", type = OoaType.LIST, description = "The paths only.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "bucket", type = OoaType.STRING, description = "The bucket's name.") String bucket,
            @Input(name = "prefix", type = OoaType.STRING, required = false, description = "Only paths starting with this, such as invoices/2026/.") String prefix) {
        List<Object> files = (List<Object>) store(ctx).call(req("list_files", "bucket", need(bucket, "bucket"), "prefix", prefix == null ? "" : prefix)).getOrDefault("files", List.of());
        List<List<Object>> rows = new ArrayList<>();
        List<Object> paths = new ArrayList<>();
        for (Object o : files) { Map<String, Object> f = (Map<String, Object>) o; rows.add(Arrays.asList(f.get("path"), f.get("bytes"), f.get("modified"))); paths.add(f.get("path")); }
        return Map.of("files", Map.of("columns", List.of("path", "bytes", "modified"), "rows", rows), "paths", paths);
    }

    @Action(id = "delete_file", name = "Delete file from bucket", category = "Storage buckets", capabilities = {"network"},
            description = "Delete a file from a storage bucket.", panel = "bucket, path")
    @Output(name = "deleted", type = OoaType.BOOLEAN, description = "True when the file was there.")
    public Map<String, Object> deleteFile(ActionContext ctx,
            @Input(name = "bucket", type = OoaType.STRING, description = "The bucket's name.") String bucket,
            @Input(name = "path", type = OoaType.STRING, description = "Its path in the bucket.") String path) {
        return Map.of("deleted", Boolean.TRUE.equals(store(ctx).call(req("delete_file", "bucket", need(bucket, "bucket"), "path", need(path, "path"))).get("deleted")));
    }

    /** The work-folder store for Studio: entities as data-store/&lt;name&gt;.json, buckets as buckets/&lt;name&gt;/. */
    static final class Local {
        private Local() { }

        static synchronized Map<String, Object> call(Path work, Map<String, Object> r) {
            String op = String.valueOf(r.get("op"));
            try {
                if (r.containsKey("bucket")) return bucket(work.resolve("buckets").resolve(safe(String.valueOf(r.get("bucket")))), op, r);
                return entity(work.resolve("data-store").resolve(safe(String.valueOf(r.get("entity"))) + ".json"), op, r);
            } catch (IOException e) { throw new ActionException("DATA.IO", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        }

        static String safe(String name) {
            String n = name.trim();
            if (!n.matches("[A-Za-z0-9 _.-]{1,80}") || n.contains("..")) throw new ActionException("DATA.BAD_NAME", "The name '" + name + "' is not allowed", "Use letters, digits, spaces, - and _ in the name.");
            return n;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> entity(Path f, String op, Map<String, Object> r) throws IOException {
            List<Object> recs = Files.exists(f) ? (List<Object>) JsonLite.parse(Files.readString(f)) : new ArrayList<>();
            String id = r.get("id") == null ? null : String.valueOf(r.get("id"));
            Map<String, Object> found = null;
            for (Object o : recs) if (((Map<String, Object>) o).get("id").equals(id)) found = (Map<String, Object>) o;
            Map<String, Object> data = r.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
            switch (op) {
                case "create" -> {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("id", "rec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                    rec.put("data", new LinkedHashMap<>(data));
                    rec.put("updated", Instant.now().toString());
                    recs.add(rec);
                    save(f, recs);
                    return rec;
                }
                case "update" -> {
                    if (found == null) throw new ActionException("DATA.NOT_FOUND", "No record " + id, "Check the record's id; it may have been deleted.");
                    Map<String, Object> d = new LinkedHashMap<>((Map<String, Object>) found.get("data"));
                    data.forEach((k, v) -> { if (v == null || "".equals(v)) d.remove(k); else d.put(k, v); });
                    found.put("data", d); found.put("updated", Instant.now().toString());
                    save(f, recs);
                    return found;
                }
                case "get" -> { if (found == null) throw new ActionException("DATA.NOT_FOUND", "No record " + id, "Check the record's id; it may have been deleted."); return found; }
                case "delete" -> { boolean had = found != null; recs.remove(found); if (had) save(f, recs); return Map.of("deleted", had); }
                case "query" -> {
                    List<Object> hits = new ArrayList<>();
                    for (Object o : recs) {
                        Map<String, Object> d = (Map<String, Object>) ((Map<String, Object>) o).get("data");
                        boolean ok = true;
                        for (Object x : r.get("filters") instanceof List<?> l ? l : List.of()) {
                            Map<String, Object> flt = (Map<String, Object>) x;
                            String field = String.valueOf(flt.get("field"));
                            Object have = field.equals("id") ? ((Map<String, Object>) o).get("id") : d.get(field);
                            if (!matches(have, String.valueOf(flt.getOrDefault("op", "=")), flt.get("value"))) { ok = false; break; }
                        }
                        if (ok) hits.add(o);
                    }
                    String sort = (String) r.get("sort");
                    if (sort != null && !sort.isBlank()) {
                        Comparator<Object> c = (a, b) -> cmp(((Map<String, Object>) ((Map<String, Object>) a).get("data")).get(sort), ((Map<String, Object>) ((Map<String, Object>) b).get("data")).get(sort));
                        hits.sort(Boolean.TRUE.equals(r.get("descending")) ? c.reversed() : c);
                    }
                    int off = r.get("offset") instanceof Number n ? n.intValue() : 0, lim = r.get("limit") instanceof Number n ? n.intValue() : 1000;
                    int from = Math.min(off, hits.size());
                    return Map.of("records", hits.subList(from, Math.min(hits.size(), from + lim)), "total", hits.size());
                }
                default -> throw new ActionException("DATA.BAD_OP", "Unknown operation " + op, "Choose one of the operations the step offers.");
            }
        }

        static int cmp(Object a, Object b) {
            if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;
            try { return new BigDecimal(Values.text(a)).compareTo(new BigDecimal(Values.text(b))); } catch (NumberFormatException e) { return Values.text(a).compareToIgnoreCase(Values.text(b)); }
        }

        static boolean matches(Object have, String op, Object want) {
            if (op.equals("empty")) return have == null || "".equals(have);
            if (op.equals("not_empty")) return have != null && !"".equals(have);
            if (have == null) return op.equals("!=");
            int c = cmp(have, want);
            String h = Values.text(have).toLowerCase(Locale.ROOT), w = want == null ? "" : Values.text(want).toLowerCase(Locale.ROOT);
            return switch (op) {
                case "=" -> c == 0; case "!=" -> c != 0; case ">" -> c > 0; case ">=" -> c >= 0; case "<" -> c < 0; case "<=" -> c <= 0;
                case "contains" -> h.contains(w); case "starts_with" -> h.startsWith(w);
                default -> throw new ActionException("DATA.BAD_FILTER", "Unknown comparison '" + op + "'", "Use one of the comparisons the step offers (equals, not_equals, contains, greater_than and so on).");
            };
        }

        static void save(Path f, List<Object> recs) throws IOException {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, JsonLite.write(recs));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }

        static Map<String, Object> bucket(Path base, String op, Map<String, Object> r) throws IOException {
            if (op.equals("list_files")) {
                List<Object> out = new ArrayList<>();
                if (Files.isDirectory(base)) try (Stream<Path> s = Files.walk(base)) {
                    String pre = r.get("prefix") == null ? "" : String.valueOf(r.get("prefix"));
                    for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                        String rel = base.relativize(p).toString().replace('\\', '/');
                        if (rel.startsWith(pre)) out.add(Map.of("path", rel, "bytes", Files.size(p), "modified", Files.getLastModifiedTime(p).toInstant().toString()));
                    }
                }
                return Map.of("files", out);
            }
            Path p = base.resolve(String.valueOf(r.get("path")).replace('\\', '/')).normalize();
            if (!p.startsWith(base.normalize())) throw new ActionException("DATA.BAD_PATH", "The path leaves the bucket", "Give a path inside the bucket, without .. or a leading /.");
            return switch (op) {
                case "upload" -> {
                    if (!Boolean.FALSE.equals(r.get("overwrite")) || !Files.exists(p)) {
                        Files.createDirectories(p.getParent());
                        byte[] b = Base64.getDecoder().decode(String.valueOf(r.get("content")));
                        Files.write(p, b);
                        yield Map.of("path", base.relativize(p).toString().replace('\\', '/'), "bytes", b.length);
                    }
                    throw new ActionException("DATA.EXISTS", "The bucket already has " + r.get("path"), "Choose another path, or delete the file that is there first.");
                }
                case "download" -> {
                    if (!Files.isRegularFile(p)) throw new ActionException("DATA.NOT_FOUND", "The bucket has no file " + r.get("path"), "Check the file's path in the bucket.");
                    yield Map.of("content", Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
                }
                case "delete_file" -> Map.of("deleted", Files.deleteIfExists(p));
                default -> throw new ActionException("DATA.BAD_OP", "Unknown operation " + op, "Choose one of the operations the step offers.");
            };
        }
    }
}
