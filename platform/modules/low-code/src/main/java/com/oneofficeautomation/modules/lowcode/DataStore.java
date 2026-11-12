package com.oneofficeautomation.modules.lowcode;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * Business data store (gap G66, after UiPath's Data Service and Storage Buckets, and Power Automate's Dataverse):
 * <ul>
 * <li><b>Entities</b>: tables of business records (suppliers, cases, price lists) whose fields an administrator
 * defines (text, number, yes/no, date, choice; required or not, unique or not). Agents create, change, read, query and
 * delete records with the Data store toolkit; people see and change them in Manage > Data store.</li>
 * <li><b>Storage buckets</b>: named file areas that runs read and write while they run (unlike repository files,
 * which runs get read-only). Files are kept under the data folder, buckets/&lt;bucket id&gt;/.</li>
 * </ul>
 * Each entity and bucket may belong to a folder: then only agents in that folder (or below) can use it. People need
 * data.view to see, data.edit to change records and files, and data.manage to define entities and buckets.
 */
public final class DataStore {
    static final List<String> TYPES = List.of("text", "number", "boolean", "date", "choice");
    static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_ .-]{0,79}$");
    static final Pattern FIELD = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,59}$");
    static final Pattern FILE_PART = Pattern.compile("^[A-Za-z0-9 _.()@+-]{1,120}$");
    static final int MAX_FIELDS = 100, MAX_RECORDS = 200_000, MAX_QUERY = 5000, DEFAULT_MB = 25, MAX_MB = 200;

    private final ModuleContext ctx;
    private final Db db;
    private final Path root;

    public DataStore(ModuleContext ctx) { this.ctx = ctx; this.db = ctx.db(); this.root = ctx.dataDir().resolve("buckets"); }

    // ===================== definitions =====================

    static String folder(Object raw) {
        String f = raw == null ? "" : String.valueOf(raw).trim();
        return f.isEmpty() || f.equals("/") ? "" : ModuleContext.folderPath(f);
    }

    static String name(Object raw, String what) {
        String n = raw == null ? "" : String.valueOf(raw).trim();
        if (!NAME.matcher(n).matches()) throw new OoaException("CR.BAD_INPUT", "Give the " + what + " a name of up to 80 letters, digits, spaces and _ . -, starting with a letter");
        return n;
    }

    /** The fields as saved: [{name, type, required, unique, choices}]. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fields(Object raw) {
        if (!(raw instanceof List<?> l) || l.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Give the entity at least one field");
        if (l.size() > MAX_FIELDS) throw new OoaException("CR.BAD_INPUT", "An entity has at most " + MAX_FIELDS + " fields");
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) throw new OoaException("CR.BAD_INPUT", "Each field is {name, type}");
            String n = String.valueOf(m.get("name")).trim(), t = m.get("type") == null ? "text" : String.valueOf(m.get("type"));
            if (!FIELD.matcher(n).matches()) throw new OoaException("CR.BAD_INPUT", "The field name '" + n + "' is not allowed", "Letters, digits and _, starting with a letter (up to 60).");
            if (n.equalsIgnoreCase("id") || !seen.add(n.toLowerCase(Locale.ROOT))) throw new OoaException("CR.BAD_INPUT", "The field name '" + n + "' is taken");
            if (!TYPES.contains(t)) throw new OoaException("CR.BAD_INPUT", "A field's type is one of " + String.join(", ", TYPES));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", n); f.put("type", t);
            f.put("required", Boolean.TRUE.equals(m.get("required")));
            f.put("unique", Boolean.TRUE.equals(m.get("unique")));
            if (t.equals("choice")) {
                List<String> ch = new ArrayList<>();
                if (m.get("choices") instanceof List<?> cl) for (Object c : cl) { String s = String.valueOf(c).trim(); if (!s.isEmpty() && !ch.contains(s)) ch.add(s); }
                if (ch.isEmpty()) throw new OoaException("CR.BAD_INPUT", "The choice field '" + n + "' needs its choices");
                f.put("choices", ch);
            }
            out.add(f);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entityRow(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("fields", JsonLite.parse((String) r.get("fields")));
        m.put("records", ctx.count("SELECT COUNT(*) AS n FROM entity_records WHERE entity_id = ?", r.get("id")));
        return m;
    }

    public List<Map<String, Object>> entities(User by) {
        ctx.require(by, "data.view", "see the data store");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, name, folder, description, fields, created, created_by, updated, updated_by FROM entities ORDER BY name")) out.add(entityRow(r));
        return out;
    }

    public Map<String, Object> entity(String idOrName) {
        Map<String, Object> r = db.one("SELECT id, name, folder, description, fields, created, created_by, updated, updated_by FROM entities WHERE id = ? OR LOWER(name) = ?",
            idOrName, String.valueOf(idOrName).toLowerCase(Locale.ROOT));
        if (r == null) throw new OoaException("CR.NOT_FOUND", "No entity '" + idOrName + "' in the data store", "Use the entity's name as shown in Manage > Data store.");
        return r;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveEntity(User by, String id, Map<String, Object> b, String ip) {
        ctx.require(by, "data.manage", "define data store entities");
        String n = name(b.get("name"), "entity"), f = folder(b.get("folder"));
        String desc = b.get("description") == null ? null : String.valueOf(b.get("description")).trim();
        if (desc != null && desc.length() > 500) throw new OoaException("CR.BAD_INPUT", "The description is up to 500 characters");
        List<Map<String, Object>> fs = fields(b.get("fields"));
        if (db.one("SELECT id FROM entities WHERE LOWER(name) = ? AND id <> ?", n.toLowerCase(Locale.ROOT), id == null ? "" : id) != null)
            throw new OoaException("CR.CONFLICT", "An entity is already called '" + n + "'", "Choose another name, or open the one that has it.");
        String eid = id == null ? id("ent") : id;
        if (id == null) db.update("INSERT INTO entities (id, name, folder, description, fields, created, created_by, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?)",
            eid, n, f, desc, JsonLite.write(fs), now(), by.username(), now(), by.username());
        else {
            if (db.one("SELECT id FROM entities WHERE id = ?", id) == null) throw notFound("Entity " + id);
            db.update("UPDATE entities SET name = ?, folder = ?, description = ?, fields = ?, updated = ?, updated_by = ? WHERE id = ?", n, f, desc, JsonLite.write(fs), now(), by.username(), id);
        }
        ctx.audit(by.username(), id == null ? "data.entity_create" : "data.entity_update", "entity", eid, Map.of("name", n, "folder", f, "fields", fs.size()), "ok", ip);
        return entityRow(entity(eid));
    }

    public void deleteEntity(User by, String id, String ip) {
        ctx.require(by, "data.manage", "define data store entities");
        Map<String, Object> e = entity(id);
        long n = ctx.count("SELECT COUNT(*) AS n FROM entity_records WHERE entity_id = ?", e.get("id"));
        db.tx(() -> { db.update("DELETE FROM entity_records WHERE entity_id = ?", e.get("id")); db.update("DELETE FROM entities WHERE id = ?", e.get("id")); return null; });
        ctx.audit(by.username(), "data.entity_delete", "entity", (String) e.get("id"), Map.of("name", e.get("name"), "records", n), "ok", ip);
    }

    // ===================== records =====================

    /** A value checked against its field: numbers as numbers, yes/no as booleans, dates as yyyy-MM-dd, choices from the list. */
    @SuppressWarnings("unchecked")
    static Object value(Map<String, Object> f, Object v) {
        String n = (String) f.get("name");
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        switch ((String) f.get("type")) {
            case "number" -> {
                if (v instanceof Number x) return new BigDecimal(x.toString());
                try { return new BigDecimal(String.valueOf(v).trim()); } catch (NumberFormatException e) { throw new OoaException("DATA.BAD_VALUE", n + " is a number, not '" + v + "'", "Give a value of the type the message names."); }
            }
            case "boolean" -> {
                if (v instanceof Boolean x) return x;
                String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                if (List.of("true", "yes", "1").contains(s)) return true;
                if (List.of("false", "no", "0").contains(s)) return false;
                throw new OoaException("DATA.BAD_VALUE", n + " is yes or no, not '" + v + "'", "Give a value of the type the message names.");
            }
            case "date" -> {
                String s = String.valueOf(v).trim();
                try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).toString(); }
                catch (DateTimeParseException e) { throw new OoaException("DATA.BAD_VALUE", n + " is a date (yyyy-MM-dd), not '" + v + "'", "Give a value of the type the message names."); }
            }
            case "choice" -> {
                String s = String.valueOf(v).trim();
                if (!((List<Object>) f.get("choices")).contains(s)) throw new OoaException("DATA.BAD_VALUE", n + " is one of " + f.get("choices") + ", not '" + s + "'", "Give a value of the type the message names.");
                return s;
            }
            default -> {
                String s = v instanceof Map || v instanceof List ? JsonLite.write(v) : String.valueOf(v);
                if (s.length() > 10_000) throw new OoaException("DATA.BAD_VALUE", n + " is up to 10,000 characters", "Give a value of the type the message names.");
                return s;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checked(Map<String, Object> entity, String recordId, Map<String, Object> given, Map<String, Object> before) {
        List<Map<String, Object>> fs = (List<Map<String, Object>>) JsonLite.parse((String) entity.get("fields"));
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> f : fs) byName.put(((String) f.get("name")).toLowerCase(Locale.ROOT), f);
        Map<String, Object> data = new LinkedHashMap<>(before == null ? Map.of() : before);
        for (Map.Entry<String, Object> e : given.entrySet()) {
            if (e.getKey().equals("id")) continue;
            Map<String, Object> f = byName.get(e.getKey().toLowerCase(Locale.ROOT));
            if (f == null) throw new OoaException("DATA.NO_FIELD", "The entity " + entity.get("name") + " has no field '" + e.getKey() + "'", "Fields: " + fs.stream().map(x -> x.get("name")).toList());
            Object v = value(f, e.getValue());
            if (v == null) data.remove(f.get("name")); else data.put((String) f.get("name"), v);
        }
        for (Map<String, Object> f : fs) {
            Object v = data.get(f.get("name"));
            if (Boolean.TRUE.equals(f.get("required")) && v == null) throw new OoaException("DATA.REQUIRED", "The field " + f.get("name") + " is required", "Fill in the field the message names.");
            if (Boolean.TRUE.equals(f.get("unique")) && v != null)
                for (Map<String, Object> other : all(entity))
                    if (!other.get("id").equals(recordId) && String.valueOf(v).equalsIgnoreCase(String.valueOf(((Map<String, Object>) other.get("data")).get(f.get("name")))))
                        throw new OoaException("DATA.DUPLICATE", "Another record already has " + f.get("name") + " " + v, "Record " + other.get("id"));
        }
        if (JsonLite.write(data).length() > 100_000) throw new OoaException("DATA.TOO_LARGE", "A record is up to 100,000 characters", "Store less in one record or file, or put large files in a share.");
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> all(Map<String, Object> entity) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, data, created, created_by, updated, updated_by FROM entity_records WHERE entity_id = ? ORDER BY created, id", entity.get("id"))) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("data", JsonLite.parse((String) r.get("data")));
            out.add(m);
        }
        return out;
    }

    Map<String, Object> create(Map<String, Object> entity, Map<String, Object> given, String who) {
        if (ctx.count("SELECT COUNT(*) AS n FROM entity_records WHERE entity_id = ?", entity.get("id")) >= MAX_RECORDS)
            throw new OoaException("DATA.FULL", "The entity " + entity.get("name") + " has " + MAX_RECORDS + " records, the most it can hold", "Delete records you no longer need, or use another entity.");
        String id = id("rec");
        Map<String, Object> data = checked(entity, id, given, null);
        db.update("INSERT INTO entity_records (id, entity_id, data, created, created_by, updated, updated_by) VALUES (?,?,?,?,?,?,?)", id, entity.get("id"), JsonLite.write(data), now(), who, now(), who);
        return record(entity, id);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> change(Map<String, Object> entity, String id, Map<String, Object> given, String who, boolean replace) {
        Map<String, Object> before = record(entity, id);
        Map<String, Object> data = checked(entity, id, given, replace ? null : (Map<String, Object>) before.get("data"));
        db.update("UPDATE entity_records SET data = ?, updated = ?, updated_by = ? WHERE id = ? AND entity_id = ?", JsonLite.write(data), now(), who, id, entity.get("id"));
        return record(entity, id);
    }

    public Map<String, Object> record(Map<String, Object> entity, String id) {
        Map<String, Object> r = db.one("SELECT id, data, created, created_by, updated, updated_by FROM entity_records WHERE id = ? AND entity_id = ?", id, entity.get("id"));
        if (r == null) throw new OoaException("DATA.NOT_FOUND", "No record " + id + " in " + entity.get("name"), "Check the record's id or the file's path; it may have been deleted.");
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("data", JsonLite.parse((String) r.get("data")));
        return m;
    }

    boolean remove(Map<String, Object> entity, String id) { return db.update("DELETE FROM entity_records WHERE id = ? AND entity_id = ?", id, entity.get("id")) > 0; }

    /**
     * Records matching every filter ({field, op, value}; op =, !=, &gt;, &gt;=, &lt;, &lt;=, contains, starts_with,
     * empty, not_empty), sorted by a field, a page at a time. Text compares without case; numbers and dates by value.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> query(Map<String, Object> entity, List<Object> filters, String sort, boolean desc, int limit, int offset, String search) {
        List<Map<String, Object>> fs = (List<Map<String, Object>>) JsonLite.parse((String) entity.get("fields"));
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> f : fs) byName.put(((String) f.get("name")).toLowerCase(Locale.ROOT), f);
        List<Map<String, Object>> hits = new ArrayList<>();
        String s = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> r : all(entity)) {
            Map<String, Object> d = (Map<String, Object>) r.get("data");
            boolean ok = s.isEmpty() || r.get("id").toString().toLowerCase(Locale.ROOT).contains(s) || d.values().stream().anyMatch(v -> String.valueOf(v).toLowerCase(Locale.ROOT).contains(s));
            for (Object o : filters == null ? List.of() : filters) {
                if (!ok) break;
                Map<String, Object> flt = (Map<String, Object>) o;
                String fn = String.valueOf(flt.get("field"));
                Map<String, Object> f = byName.get(fn.toLowerCase(Locale.ROOT));
                if (f == null && !fn.equalsIgnoreCase("id")) throw new OoaException("DATA.NO_FIELD", "The entity " + entity.get("name") + " has no field '" + fn + "'", "Use a field the entity has, or add it in Build > Apps > Data.");
                Object have = f == null ? r.get("id") : d.get(f.get("name"));
                ok = matches(f, have, String.valueOf(flt.getOrDefault("op", "=")), flt.get("value"));
            }
            if (ok) hits.add(r);
        }
        if (sort != null && !sort.isBlank()) {
            Map<String, Object> f = byName.get(sort.toLowerCase(Locale.ROOT));
            if (f == null) throw new OoaException("DATA.NO_FIELD", "Cannot sort by '" + sort + "': no such field", "Use a field the entity has, or add it in Build > Apps > Data.");
            Comparator<Map<String, Object>> c = (a, b) -> compare(f, ((Map<String, Object>) a.get("data")).get(f.get("name")), ((Map<String, Object>) b.get("data")).get(f.get("name")));
            hits.sort(desc ? c.reversed() : c);
        }
        int total = hits.size(), from = Math.min(Math.max(0, offset), total), to = Math.min(total, from + Math.max(1, Math.min(limit, MAX_QUERY)));
        return Map.of("records", hits.subList(from, to), "total", total, "fields", fs);
    }

    static int compare(Map<String, Object> f, Object a, Object b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;   // empty values last
        if ("number".equals(f.get("type"))) return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    static boolean matches(Map<String, Object> f, Object have, String op, Object want) {
        if (op.equals("empty")) return have == null;
        if (op.equals("not_empty")) return have != null;
        if (have == null) return op.equals("!=");
        Object w = f == null ? (want == null ? null : String.valueOf(want)) : value(f, want);
        String h = String.valueOf(have).toLowerCase(Locale.ROOT), ws = String.valueOf(w).toLowerCase(Locale.ROOT);
        int c = w == null ? 1 : f == null ? h.compareTo(ws) : compare(f, have, w);
        return switch (op) {
            case "=" -> c == 0;
            case "!=" -> c != 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case "contains" -> h.contains(ws);
            case "starts_with" -> h.startsWith(ws);
            default -> throw new OoaException("DATA.BAD_FILTER", "Unknown comparison '" + op + "'", "Use =, !=, >, >=, <, <=, contains, starts_with, empty or not_empty.");
        };
    }

    // console: records
    @SuppressWarnings("unchecked")
    public Map<String, Object> records(User by, String entityId, Map<String, String> q) {
        ctx.require(by, "data.view", "see the data store");
        int limit = q.get("limit") == null ? 200 : Integer.parseInt(q.get("limit")), offset = q.get("offset") == null ? 0 : Integer.parseInt(q.get("offset"));
        return query(entity(entityId), List.of(), q.get("sort"), "true".equals(q.get("desc")), limit, offset, q.get("q"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveRecord(User by, String entityId, String id, Map<String, Object> body, String ip) {
        ctx.require(by, "data.edit", "change data store records");
        Map<String, Object> e = entity(entityId);
        Map<String, Object> data = body.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> r = id == null ? create(e, data, by.username()) : change(e, id, data, by.username(), true);
        ctx.audit(by.username(), id == null ? "data.record_create" : "data.record_update", "entity_record", (String) r.get("id"), Map.of("entity", e.get("name")), "ok", ip);
        return r;
    }

    public void deleteRecord(User by, String entityId, String id, String ip) {
        ctx.require(by, "data.edit", "change data store records");
        Map<String, Object> e = entity(entityId);
        if (!remove(e, id)) throw notFound("Record " + id);
        ctx.audit(by.username(), "data.record_delete", "entity_record", id, Map.of("entity", e.get("name")), "ok", ip);
    }

    // ===================== storage buckets =====================

    Map<String, Object> bucket(String idOrName) {
        Map<String, Object> r = db.one("SELECT id, name, folder, description, max_mb, created, created_by FROM buckets WHERE id = ? OR LOWER(name) = ?",
            idOrName, String.valueOf(idOrName).toLowerCase(Locale.ROOT));
        if (r == null) throw new OoaException("CR.NOT_FOUND", "No storage bucket '" + idOrName + "'", "Use the bucket's name as shown in Manage > Data store.");
        return r;
    }

    public List<Map<String, Object>> buckets(User by) {
        ctx.require(by, "data.view", "see the data store");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, name, folder, description, max_mb, created, created_by FROM buckets ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            long[] n = usage((String) r.get("id"));
            m.put("files", n[0]); m.put("bytes", n[1]);
            out.add(m);
        }
        return out;
    }

    private long[] usage(String bucketId) {
        Path d = root.resolve(bucketId);
        if (!Files.isDirectory(d)) return new long[2];
        try (Stream<Path> s = Files.walk(d)) {
            long[] n = new long[2];
            s.filter(Files::isRegularFile).forEach(p -> { n[0]++; try { n[1] += Files.size(p); } catch (IOException ignored) { } });
            return n;
        } catch (IOException e) { return new long[2]; }
    }

    public Map<String, Object> saveBucket(User by, String id, Map<String, Object> b, String ip) {
        ctx.require(by, "data.manage", "define storage buckets");
        String n = name(b.get("name"), "bucket"), f = folder(b.get("folder"));
        String desc = b.get("description") == null ? null : String.valueOf(b.get("description")).trim();
        if (desc != null && desc.length() > 500) throw new OoaException("CR.BAD_INPUT", "The description is up to 500 characters");
        int mb = b.get("max_mb") == null || String.valueOf(b.get("max_mb")).isBlank() ? DEFAULT_MB : (int) Double.parseDouble(String.valueOf(b.get("max_mb")));
        if (mb < 1 || mb > MAX_MB) throw new OoaException("CR.BAD_INPUT", "A file is up to between 1 and " + MAX_MB + " MB");
        if (db.one("SELECT id FROM buckets WHERE LOWER(name) = ? AND id <> ?", n.toLowerCase(Locale.ROOT), id == null ? "" : id) != null)
            throw new OoaException("CR.CONFLICT", "A bucket is already called '" + n + "'", "Choose another name, or open the one that has it.");
        String bid = id == null ? id("bkt") : id;
        if (id == null) db.update("INSERT INTO buckets (id, name, folder, description, max_mb, created, created_by) VALUES (?,?,?,?,?,?,?)", bid, n, f, desc, mb, now(), by.username());
        else {
            if (db.one("SELECT id FROM buckets WHERE id = ?", id) == null) throw notFound("Bucket " + id);
            db.update("UPDATE buckets SET name = ?, folder = ?, description = ?, max_mb = ? WHERE id = ?", n, f, desc, mb, id);
        }
        ctx.audit(by.username(), id == null ? "data.bucket_create" : "data.bucket_update", "bucket", bid, Map.of("name", n, "folder", f), "ok", ip);
        return bucket(bid);
    }

    public void deleteBucket(User by, String id, String ip) {
        ctx.require(by, "data.manage", "define storage buckets");
        Map<String, Object> b = bucket(id);
        long[] n = usage((String) b.get("id"));
        db.update("DELETE FROM buckets WHERE id = ?", b.get("id"));
        deleteTree(root.resolve((String) b.get("id")));
        ctx.audit(by.username(), "data.bucket_delete", "bucket", (String) b.get("id"), Map.of("name", b.get("name"), "files", n[0]), "ok", ip);
    }

    private static void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.walk(d)) { s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } }); }
        catch (IOException ignored) { }
    }

    /** A file's place in its bucket: parts of letters, digits, spaces and _ . ( ) @ + -, separated by /. */
    static String filePath(Object raw) {
        String p = raw == null ? "" : String.valueOf(raw).trim().replace('\\', '/').replaceAll("^/+", "");
        if (p.isEmpty() || p.length() > 400) throw new OoaException("DATA.BAD_PATH", "Give the file's path in the bucket (up to 400 characters)", "Give a path inside the bucket, without .. or a leading /.");
        for (String part : p.split("/")) if (!FILE_PART.matcher(part).matches() || part.equals(".") || part.equals(".."))
            throw new OoaException("DATA.BAD_PATH", "The path '" + p + "' is not allowed", "Use letters, digits, spaces and _ . ( ) @ + -, with / between folders.");
        return p;
    }

    private Path file(Map<String, Object> b, String path) {
        Path base = root.resolve((String) b.get("id")).normalize(), p = base.resolve(filePath(path)).normalize();
        if (!p.startsWith(base)) throw new OoaException("DATA.BAD_PATH", "The path leaves the bucket", "Give a path inside the bucket, without .. or a leading /.");
        return p;
    }

    List<Map<String, Object>> files(Map<String, Object> b, String prefix) {
        Path base = root.resolve((String) b.get("id"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(base)) return out;
        String pre = prefix == null ? "" : prefix.trim().replace('\\', '/').replaceAll("^/+", "");
        try (Stream<Path> s = Files.walk(base)) {
            s.filter(Files::isRegularFile).sorted().forEach(p -> {
                String rel = base.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith(pre) || rel.endsWith(".part")) return;
                try { out.add(Map.of("path", rel, "bytes", Files.size(p), "modified", Files.getLastModifiedTime(p).toInstant().toString())); } catch (IOException ignored) { }
            });
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return out;
    }

    Map<String, Object> put(Map<String, Object> b, String path, byte[] content, boolean overwrite) {
        long max = ((Number) b.get("max_mb")).longValue() * 1024 * 1024;
        if (content.length > max) throw new OoaException("DATA.TOO_LARGE", "Files in the bucket " + b.get("name") + " are up to " + b.get("max_mb") + " MB", "Store less in one record or file, or put large files in a share.");
        Path p = file(b, path);
        if (!overwrite && Files.exists(p)) throw new OoaException("DATA.EXISTS", "The bucket " + b.get("name") + " already has " + filePath(path), "Turn on Replace, or choose another path.");
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".part");
            Files.write(tmp, content);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new OoaException("DATA.IO", "Cannot save the file: " + e.getMessage(), "Check that the server's data folder has space and that the service account may read and write there.", true, null); }
        return Map.of("path", filePath(path), "bytes", content.length);
    }

    byte[] get(Map<String, Object> b, String path) {
        Path p = file(b, path);
        if (!Files.isRegularFile(p)) throw new OoaException("DATA.NOT_FOUND", "The bucket " + b.get("name") + " has no file " + filePath(path), "Check the record's id or the file's path; it may have been deleted.");
        try { return Files.readAllBytes(p); } catch (IOException e) { throw new OoaException("DATA.IO", "Cannot read the file: " + e.getMessage(), "Check that the server's data folder has space and that the service account may read and write there.", true, null); }
    }

    boolean deleteFile(Map<String, Object> b, String path) {
        try { return Files.deleteIfExists(file(b, path)); } catch (IOException e) { throw new OoaException("DATA.IO", "Cannot delete the file: " + e.getMessage(), "Check that the server's data folder has space and that the service account may read and write there.", true, null); }
    }

    // console: files
    public List<Map<String, Object>> files(User by, String bucketId, String prefix) { ctx.require(by, "data.view", "see the data store"); return files(bucket(bucketId), prefix); }

    public Map<String, Object> upload(User by, String bucketId, String path, byte[] content, String ip) {
        ctx.require(by, "data.edit", "change files in storage buckets");
        Map<String, Object> b = bucket(bucketId);
        Map<String, Object> r = put(b, path, content, true);
        ctx.audit(by.username(), "data.file_upload", "bucket", (String) b.get("id"), Map.of("path", r.get("path"), "bytes", content.length), "ok", ip);
        return r;
    }

    public byte[] download(User by, String bucketId, String path) { ctx.require(by, "data.view", "see the data store"); return get(bucket(bucketId), path); }

    public void removeFile(User by, String bucketId, String path, String ip) {
        ctx.require(by, "data.edit", "change files in storage buckets");
        Map<String, Object> b = bucket(bucketId);
        if (!deleteFile(b, path)) throw notFound("File " + path);
        ctx.audit(by.username(), "data.file_delete", "bucket", (String) b.get("id"), Map.of("path", filePath(path)), "ok", ip);
    }

    // ===================== for runs (through the Bot Agent) =====================

    /** The run may use an entity or bucket kept for its folder (or one for every folder). */
    static void checkFolder(Map<String, Object> run, Map<String, Object> thing, String what) {
        String f = (String) thing.get("folder");
        String path = String.valueOf(run.get("bot_path"));
        if (f != null && !f.isEmpty() && !path.startsWith(f + "/"))
            throw new OoaException("CR.FORBIDDEN", "The " + what + " " + thing.get("name") + " is only for agents in " + f, "Move the agent into " + f + ", or ask an administrator to change the " + what + "'s folder.");
    }

    /**
     * A running run asks the data store: {op, entity | bucket, ...}. The run must be this runner's and running; who
     * changed a record is the run's user, through the agent.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> forRun(Map<String, Object> device, Map<String, Object> body) {
        Map<String, Object> run = ctx.runOfDevice(device, (String) body.get("run_id"));
        if (!"running".equals(run.get("status"))) throw new OoaException("CR.FORBIDDEN", "Only running agents can use the data store", "Use this from a step of a running agent.");
        String who = "agent:" + run.get("bot_path") + " for " + (run.get("run_as") != null ? run.get("run_as") : run.get("requested_by"));
        Map<String, Object> r = body.get("request") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String op = String.valueOf(r.get("op"));
        if (List.of("upload", "download", "list_files", "delete_file").contains(op)) {
            Map<String, Object> b = bucket(String.valueOf(r.get("bucket")));
            checkFolder(run, b, "bucket");
            return switch (op) {
                case "upload" -> put(b, String.valueOf(r.get("path")), Base64.getDecoder().decode(String.valueOf(r.get("content"))), !Boolean.FALSE.equals(r.get("overwrite")));
                case "download" -> Map.of("content", Base64.getEncoder().encodeToString(get(b, String.valueOf(r.get("path")))));
                case "list_files" -> Map.of("files", files(b, r.get("prefix") == null ? "" : String.valueOf(r.get("prefix"))));
                default -> Map.of("deleted", deleteFile(b, String.valueOf(r.get("path"))));
            };
        }
        Map<String, Object> e = entity(String.valueOf(r.get("entity")));
        checkFolder(run, e, "entity");
        Map<String, Object> data = r.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        String id = r.get("id") == null ? null : String.valueOf(r.get("id"));
        return switch (op) {
            case "create" -> create(e, data, who);
            case "update" -> change(e, id, data, who, false);
            case "get" -> record(e, id);
            case "delete" -> Map.of("deleted", remove(e, id));
            case "query" -> query(e, r.get("filters") instanceof List<?> l ? (List<Object>) l : List.of(), (String) r.get("sort"), Boolean.TRUE.equals(r.get("descending")),
                r.get("limit") instanceof Number n ? n.intValue() : 1000, r.get("offset") instanceof Number n ? n.intValue() : 0, null);
            default -> throw new OoaException("CR.BAD_INPUT", "Unknown data store operation " + op);
        };
    }
}
