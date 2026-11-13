package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@OoaPackage(id = "ooa.json", name = "JSON", version = "1.0.0", description = "Parse, query and write JSON documents.")
public final class JsonPackage {

    @Action(id = "parse", name = "Parse JSON", category = "JSON", idempotent = true, description = "Read JSON text into a Dictionary or a List.",
            panel = "text")
    @Output(name = "value", description = "Parsed value.")
    public Map<String, Object> parse(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "JSON text.") String text) {
        try { return Collections.singletonMap("value", JsonLite.parse(text)); }
        catch (IllegalArgumentException e) { throw new ActionException("JSON.INVALID", e.getMessage(), "Check the JSON syntax."); }
    }

    @Action(id = "stringify", name = "Convert to JSON text", category = "JSON", idempotent = true, description = "Write a value (Dictionary, List, table...) as JSON text.",
            panel = "value, pretty | indent")
    @Output(name = "text", type = OoaType.STRING, description = "JSON text.")
    public Map<String, Object> stringify(ActionContext ctx, @Input(name = "value", description = "Value to serialise.") Object value,
            @Input(name = "pretty", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Spread the JSON over several indented lines for reading.") boolean pretty,
            @Input(name = "indent", type = OoaType.NUMBER, required = false, defaultValue = "2", visibleWhen = "pretty=true", min = 0, max = 10, description = "Spaces per level when pretty is on.") int indent) {
        return Map.of("text", pretty ? pretty(com.oneofficeautomation.runner.Values.toJsonable(value), indent) : JsonLite.write(value));
    }

    /** Indented JSON; scalars are written by JsonLite so numbers and escapes stay identical. */
    static String pretty(Object v, int indent) {
        if (indent < 0 || indent > 16) throw new ActionException("JSON.BAD_INDENT", "Indent must be between 0 and 16", "Use 2 or 4.");
        StringBuilder sb = new StringBuilder();
        pretty(v, indent, 0, sb);
        return sb.toString();
    }

    private static void pretty(Object v, int indent, int level, StringBuilder sb) {
        String pad = " ".repeat(indent * (level + 1)), end = " ".repeat(indent * level);
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            sb.append("{\n");
            int i = 0;
            for (var e : m.entrySet()) {
                sb.append(pad).append(JsonLite.write(String.valueOf(e.getKey()))).append(": ");
                pretty(e.getValue(), indent, level + 1, sb);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            sb.append(end).append('}');
        } else if (v instanceof List<?> l && !l.isEmpty()) {
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                sb.append(pad);
                pretty(l.get(i), indent, level + 1, sb);
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            sb.append(end).append(']');
        } else sb.append(JsonLite.write(v));
    }

    @Action(id = "get_node", name = "Get node value", category = "JSON", idempotent = true,
            description = "Read one value from JSON by its path, such as order.items[0].sku; $..sku finds every sku, $.items[?(@.qty > 1)] the items with qty over 1.",
            panel = "value, path | default, fail_if_missing")
    @Output(name = "result", description = "Value at the path (a List when the path can match several values: *, .., filters, slices), or the default when nothing matches.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the path matched.")
    public Map<String, Object> getNode(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text to query.") Object value,
            @Input(name = "path", type = OoaType.STRING, example = "$.order.items[0].sku", description = "Dotted path with [n] indexes, or JSONPath starting with $.") String path,
            @Input(name = "default", required = false, description = "Value returned when the path does not exist.") Object def,
            @Input(name = "fail_if_missing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Fail with JSON.PATH_NOT_FOUND when the path does not exist.") boolean failIfMissing) {
        // JSON text is read like a Dictionary or List (as Insert node and Update node do); it used to give found=false silently
        Object doc = value instanceof Map || value instanceof List ? value : document(value);
        JsonPathLite.Result r = JsonPathLite.evaluate(doc, path);
        boolean found = !r.matches().isEmpty();
        if (!found && failIfMissing) throw new ActionException("JSON.PATH_NOT_FOUND", "Path '" + path + "' does not exist", "Check the path; keys are case-sensitive.");
        Object result = !found ? def : r.definite() ? r.matches().get(0) : new ArrayList<>(r.matches());
        return Out.of("result", result, "found", found);
    }

    @Action(id = "get_nodes", name = "Get several node values", category = "JSON", idempotent = true,
            description = "Read several values at once into a Dictionary, e.g. {\"id\": \"order.id\", \"email\": \"customer.email\"}, each turned into a type if you want.",
            panel = "value, paths")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "Name to value; missing paths give empty values.")
    @Output(name = "missing", type = OoaType.LIST, description = "Names whose path does not exist.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNodes(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text.") Object value,
            @Input(name = "paths", description = "Dictionary of result name to path, or a list of paths (each path is then its own name). "
                    + "A path may instead be {\"path\": \"order.total\", \"type\": \"Number\"} to convert the value to String, Number, DateTime or Boolean.") Object paths) {
        Object doc = document(value);
        Map<String, String[]> wanted = new LinkedHashMap<>();  // name -> {path, type or null}
        if (paths instanceof Map<?, ?> m) ((Map<String, Object>) m).forEach((k, v) -> wanted.put(k, entry(k, v)));
        else if (paths instanceof List<?> l) for (Object p : l) {
            String[] e = entry(null, p);
            wanted.put(e[0], e);
        }
        else throw new ActionException("JSON.BAD_PATH", "'paths' must be a Dictionary or a List", "Example: {\"id\": \"order.id\"}.");
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> missing = new ArrayList<>();
        wanted.forEach((name, e) -> {
            Object v;
            JsonPathLite.Result r = JsonPathLite.evaluate(doc, e[0]);
            if (r.matches().isEmpty()) { out.put(name, null); missing.add(name); return; }
            v = r.definite() ? r.matches().get(0) : new ArrayList<>(r.matches());
            out.put(name, e[1] == null ? v : convert(name, v, e[1]));
        });
        return Out.of("values", out, "missing", missing);
    }

    static final List<String> NODE_TYPES = List.of("String", "Number", "DateTime", "Boolean");

    /** One entry of get_nodes paths: a plain path, or {"path": ..., "type": ...}; returns {path, type or null}. */
    private static String[] entry(String name, Object v) {
        if (v instanceof Map<?, ?> m) {
            Object p = m.get("path"), t = m.get("type");
            String label = name == null ? String.valueOf(p) : name;
            if (p == null || com.oneofficeautomation.runner.Values.text(p).isBlank())
                throw new ActionException("JSON.BAD_PATH", "Entry '" + label + "' has no path", "Write it as {\"path\": \"order.total\", \"type\": \"Number\"}.");
            for (Object k : m.keySet()) if (!"path".equals(k) && !"type".equals(k))
                throw new ActionException("JSON.BAD_PATH", "Entry '" + label + "' has an unknown key '" + k + "'", "Use only path and type.");
            String type = t == null || com.oneofficeautomation.runner.Values.text(t).isBlank() ? null : com.oneofficeautomation.runner.Values.text(t).strip();
            if (type != null) {
                String match = NODE_TYPES.stream().filter(x -> x.equalsIgnoreCase(type)).findFirst().orElse(null);
                if (match == null) throw new ActionException("JSON.BAD_TYPE", "Unknown type '" + type + "' for '" + label + "'", "Use String, Number, DateTime or Boolean.");
                return new String[]{com.oneofficeautomation.runner.Values.text(p), match};
            }
            return new String[]{com.oneofficeautomation.runner.Values.text(p), null};
        }
        return new String[]{com.oneofficeautomation.runner.Values.text(v), null};
    }

    /** Converts a found value; an empty value stays empty, a value that does not fit the type fails the step. */
    private static Object convert(String name, Object v, String type) {
        if (v == null) return null;
        if (!type.equals("String") && v instanceof String s && s.isBlank()) return null;
        try {
            Object c = com.oneofficeautomation.runner.Values.coerce(v, type, name);
            if (type.equals("Boolean") && !(c instanceof Boolean)) throw new OoaException("VAR.TYPE_MISMATCH", "not a Boolean", "Give the value as true or false.");
            if (type.equals("String") && (v instanceof Map || v instanceof List)) return JsonLite.write(com.oneofficeautomation.runner.Values.toJsonable(v));
            return c;
        } catch (OoaException e) {
            throw new ActionException("JSON.CONVERT_FAILED", "'" + name + "': cannot convert " + com.oneofficeautomation.runner.Values.safeRepr(v) + " to " + type,
                    type.equals("Number") ? "Check the value is numeric, e.g. 1234.50, or leave the type out."
                            : type.equals("DateTime") ? "Use ISO dates such as 2026-10-18 or 2026-10-18T14:30:00, or leave the type out."
                            : type.equals("Boolean") ? "Use true/false, yes/no or 1/0, or leave the type out." : "Leave the type out.");
        }
    }

    @Action(id = "read_file", name = "Read JSON file", category = "JSON", idempotent = true, capabilities = "filesystem", description = "Read a JSON file into a Dictionary or a List.",
            panel = "path | encoding")
    @Output(name = "value", description = "Parsed content.")
    public Map<String, Object> readFile(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Path to a .json file.") String path,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding of the file, e.g. utf-8, utf-16, ansi.") String encoding) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        try {
            String text = new String(Files.readAllBytes(p), CsvPackage.encoding(encoding).charset());
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return Collections.singletonMap("value", JsonLite.parse(text));
        }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p + ": " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        catch (IllegalArgumentException e) { throw new ActionException("JSON.INVALID", p + ": " + e.getMessage(), "Check the JSON syntax."); }
    }

    @Action(id = "write_file", name = "Write JSON file", category = "JSON", capabilities = "filesystem", description = "Save a value to a JSON file (written in one go, so a reader never sees half a file).",
            panel = "path, value, pretty | encoding")
    public Map<String, Object> writeFile(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Target path.") String path,
            @Input(name = "value", description = "Value to write.") Object value,
            @Input(name = "pretty", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Write indented JSON for reading.") boolean pretty,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding, e.g. utf-8, utf-8-bom, utf-16.") String encoding) {
        Path p = ctx.resolvePath(path);
        CsvPackage.Enc enc = CsvPackage.encoding(encoding);
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            String text = pretty ? pretty(com.oneofficeautomation.runner.Values.toJsonable(value), 2) : JsonLite.write(value);
            Files.write(tmp, ((enc.bom() ? "\uFEFF" : "") + text).getBytes(enc.charset()));
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + p + ": " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Map.of();
    }

    // ---- Paths: dotted (order.items[0].sku, optional leading $.) or JSON Pointer (/order/items/0/sku) ----

    static List<String> pathTokens(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        String p = path.strip();
        if (p.startsWith("/")) {
            for (String t : p.substring(1).split("/", -1)) out.add(t.replace("~1", "/").replace("~0", "~"));
            return out;
        }
        if (p.startsWith("$")) p = p.substring(1);
        for (String t : p.replace("[", ".").replace("]", "").split("\\.")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    /** Accepts a Dictionary/List, or JSON text which is parsed. Returns a private deep copy. */
    private static Object document(Object value) {
        if (value instanceof String s) {
            try { return JsonLite.parse(s); }
            catch (IllegalArgumentException e) { throw new ActionException("JSON.INVALID", e.getMessage(), "Check the JSON syntax."); }
        }
        if (!(value instanceof Map) && !(value instanceof List))
            throw new ActionException("JSON.NOT_A_DOCUMENT", "Expected a Dictionary, List or JSON text, got " + com.oneofficeautomation.runner.Values.typeName(value), "Parse the JSON first.");
        return com.oneofficeautomation.runner.Values.deepCopy(value);
    }

    private static int listIndex(List<?> l, String token, String path, boolean allowEnd) {
        if (allowEnd && token.equals("-")) return l.size();
        int i;
        try { i = Integer.parseInt(token); } catch (NumberFormatException e) {
            throw new ActionException("JSON.PATH_NOT_FOUND", "'" + token + "' in path '" + path + "' is not a list index", "Use a number such as [0] for list items.");
        }
        int max = allowEnd ? l.size() : l.size() - 1;
        if (i < 0 || i > max) throw new ActionException("JSON.PATH_NOT_FOUND", "Index " + i + " in path '" + path + "' is outside the list (size " + l.size() + ")", "Indexes start at 0.");
        return i;
    }

    /** Walks to the node at the tokens; fails with JSON.PATH_NOT_FOUND when any step is missing. */
    private static Object walk(Object root, List<String> tokens, String path) {
        Object cur = root;
        for (String t : tokens) {
            if (cur instanceof Map<?, ?> m) {
                if (!m.containsKey(t)) throw new ActionException("JSON.PATH_NOT_FOUND", "Key '" + t + "' not found in path '" + path + "'", "Check the path; keys are case-sensitive.");
                cur = m.get(t);
            } else if (cur instanceof List<?> l) cur = l.get(listIndex(l, t, path, false));
            else throw new ActionException("JSON.PATH_NOT_FOUND", "Path '" + path + "' goes through a value that is not a Dictionary or List", "Check the path.");
        }
        return cur;
    }

    private static List<String> needPath(String path) {
        List<String> t = pathTokens(path);
        if (t.isEmpty()) throw new ActionException("JSON.BAD_PATH", "The path is empty", "Give a path such as order.items[0] or /order/items/0.");
        return t;
    }

    @Action(id = "insert_node", name = "Insert node", category = "JSON", idempotent = true,
            description = "Add a value to JSON: a new key, or an item in a list at a position such as order.items[1].",
            panel = "value, path, node, if_exists | create_parents")
    @Output(name = "value", description = "Updated document.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> insertNode(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, example = "order.items[0].note", description = "Path of the new node; its parent must exist. Use - or the list size to append.") String path,
            @Input(name = "node", required = false, description = "Value to insert.") Object node,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "overwrite", "skip"},
                    description = "When the key already exists: fail with JSON.NODE_EXISTS, replace its value, or leave it.") String ifExists,
            @Input(name = "create_parents", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create missing parent dictionaries on the way.") boolean createParents) {
        Object doc = document(value);
        List<String> t = needPath(path);
        if (createParents) {
            Object cur = doc;
            for (String k : t.subList(0, t.size() - 1)) {
                if (cur instanceof Map<?, ?> m && !m.containsKey(k)) ((Map<String, Object>) m).put(k, new LinkedHashMap<String, Object>());
                cur = cur instanceof Map<?, ?> m ? m.get(k) : cur instanceof List<?> l ? l.get(listIndex(l, k, path, false)) : null;
            }
        }
        Object parent = walk(doc, t.subList(0, t.size() - 1), path);
        String last = t.get(t.size() - 1);
        if (parent instanceof Map<?, ?> m) {
            if (m.containsKey(last)) {
                if ("skip".equals(ifExists)) return Collections.singletonMap("value", doc);
                if (!"overwrite".equals(ifExists)) throw new ActionException("JSON.NODE_EXISTS", "Key '" + last + "' already exists at '" + path + "'", "Use update_node to change it, or set if_exists.");
            }
            ((Map<String, Object>) m).put(last, node);
        } else if (parent instanceof List<?> l) ((List<Object>) l).add(listIndex(l, last, path, true), node);
        else throw new ActionException("JSON.PATH_NOT_FOUND", "The parent of '" + path + "' is not a Dictionary or List", "Check the path.");
        return Collections.singletonMap("value", doc);
    }

    @Action(id = "update_node", name = "Update node", category = "JSON", idempotent = true, description = "Change one value in JSON, found by its path.",
            panel = "value, path, node | create_if_missing")
    @Output(name = "value", description = "Updated document.")
    @Output(name = "previous", description = "The value that was replaced.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateNode(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, description = "Path of the node to change.") String path,
            @Input(name = "node", required = false, description = "New value.") Object node,
            @Input(name = "create_if_missing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Add the key when it does not exist instead of failing.") boolean createIfMissing) {
        Object doc = document(value);
        List<String> t = needPath(path);
        Object parent = walk(doc, t.subList(0, t.size() - 1), path);
        String last = t.get(t.size() - 1);
        Object prev;
        if (parent instanceof Map<?, ?> m) {
            if (!m.containsKey(last) && !createIfMissing) throw new ActionException("JSON.PATH_NOT_FOUND", "Key '" + last + "' not found at '" + path + "'", "Use insert_node to add a new key.");
            prev = ((Map<String, Object>) m).put(last, node);
        } else if (parent instanceof List<?> l) prev = ((List<Object>) l).set(listIndex(l, last, path, false), node);
        else throw new ActionException("JSON.PATH_NOT_FOUND", "The parent of '" + path + "' is not a Dictionary or List", "Check the path.");
        return Out.of("value", doc, "previous", prev);
    }

    @Action(id = "delete_node", name = "Delete node", category = "JSON", idempotent = true, description = "Remove one value from JSON, found by its path (order.items[0]).",
            panel = "value, path")
    @Output(name = "value", description = "Updated document.")
    @Output(name = "removed", description = "The value that was removed.")
    public Map<String, Object> deleteNode(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, description = "Path of the node to remove.") String path) {
        Object doc = document(value);
        List<String> t = needPath(path);
        Object parent = walk(doc, t.subList(0, t.size() - 1), path);
        String last = t.get(t.size() - 1);
        Object removed;
        if (parent instanceof Map<?, ?> m) {
            if (!m.containsKey(last)) throw new ActionException("JSON.PATH_NOT_FOUND", "Key '" + last + "' not found at '" + path + "'", "Check the path; keys are case-sensitive.");
            removed = m.remove(last);
        } else if (parent instanceof List<?> l) removed = l.remove(listIndex(l, last, path, false));
        else throw new ActionException("JSON.PATH_NOT_FOUND", "The parent of '" + path + "' is not a Dictionary or List", "Check the path.");
        return Out.of("value", doc, "removed", removed);
    }

    @Action(id = "get_node_array", name = "Get node list", category = "JSON", idempotent = true, description = "Read the list found at a path, ready to go through with a Loop.",
            panel = "value, path")
    @Output(name = "items", type = OoaType.LIST, description = "Items of the list.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items.")
    public Map<String, Object> getNodeArray(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, required = false, description = "Path of the list; the whole document when empty.") String path) {
        Object node = walk(document(value), pathTokens(path), path);
        if (!(node instanceof List<?> l)) throw new ActionException("JSON.NOT_AN_ARRAY", "The node at '" + path + "' is " + com.oneofficeautomation.runner.Values.typeName(node) + ", not a list", "Point the path at a JSON array.");
        return Out.of("items", new ArrayList<Object>(l), "count", java.math.BigDecimal.valueOf(l.size()));
    }

    @Action(id = "to_table", name = "Convert to table", category = "JSON", idempotent = true,
            description = "Turn a JSON list of records into a table: each key becomes a column, each record a row.",
            panel = "value, path | flatten")
    @Output(name = "table", type = OoaType.TABLE, description = "Table with one row per object.")
    public Map<String, Object> toTable(ActionContext ctx,
            @Input(name = "value", description = "List of dictionaries, or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, required = false, description = "Path of the array inside the document; the whole document when empty.") String path,
            @Input(name = "flatten", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Turn nested objects into dotted columns, e.g. customer.name.") boolean flatten) {
        Object node = walk(document(value), pathTokens(path), path);
        if (!(node instanceof List<?> l0)) throw new ActionException("JSON.NOT_AN_ARRAY", "Expected a list of objects, got " + com.oneofficeautomation.runner.Values.typeName(node), "Point the path at a JSON array.");
        List<?> l = l0;
        if (flatten) { List<Object> flat = new ArrayList<>(); for (Object o : l) flat.add(o instanceof Map<?, ?> m ? flatten(m, "", new LinkedHashMap<>()) : o); l = flat; }
        for (int i = 0; i < l.size(); i++) if (!(l.get(i) instanceof Map))
            throw new ActionException("JSON.NOT_OBJECTS", "Item " + i + " is " + com.oneofficeautomation.runner.Values.typeName(l.get(i)) + ", not an object", "Every array item must be a JSON object.");
        if (l.isEmpty()) return Out.of("table", com.oneofficeautomation.runner.Values.table(List.of(), new ArrayList<>()));
        return Out.of("table", com.oneofficeautomation.runner.Values.coerce(l, "Table", "value"));
    }

    public Map<String, Object> validate(ActionContext ctx, Object json, List<Object> required, Object schema, String schemaPath) {
        return validate(ctx, json, required, schema, schemaPath, false, false);
    }

    static final long MAX_SCHEMA_BYTES = 5L * 1024 * 1024;

    /** Reads schema documents a $ref points to: local files (file: URIs) and http(s) URLs. */
    static JsonSchemaLite.Loader schemaLoader(ActionContext ctx) {
        return uri -> {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            byte[] data;
            try {
                if (scheme.equals("file")) {
                    Path p = Path.of(uri);
                    if (!Files.isRegularFile(p)) throw new ActionException("JSON.REF_NOT_FOUND", "Referenced schema file not found: " + p, "Check the $ref file name; it is read relative to the schema file's folder.");
                    if (Files.size(p) > MAX_SCHEMA_BYTES) throw new ActionException("JSON.BAD_SCHEMA", "Referenced schema " + p + " is larger than 5 MB", "Keep referenced schemas under 5 MB, or copy the part you need into the main schema.");
                    data = Files.readAllBytes(p);
                } else if (scheme.equals("http") || scheme.equals("https")) {
                    ctx.checkCancelled();
                    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                            .connectTimeout(java.time.Duration.ofSeconds(15)).build();
                    java.net.http.HttpResponse<java.io.InputStream> r = client.send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(30))
                            .header("Accept", "application/schema+json, application/json;q=0.9, */*;q=0.5").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                    try (java.io.InputStream in = r.body()) {
                        if (r.statusCode() / 100 != 2) throw new ActionException("JSON.REF_UNREACHABLE", "Cannot load referenced schema " + uri + ": HTTP " + r.statusCode(), "Check the $ref URL.", r.statusCode() >= 500);
                        data = in.readNBytes((int) MAX_SCHEMA_BYTES + 1);
                    }
                    if (data.length > MAX_SCHEMA_BYTES) throw new ActionException("JSON.BAD_SCHEMA", "Referenced schema " + uri + " is larger than 5 MB", "Keep referenced schemas under 5 MB, or copy the part you need into the main schema.");
                } else throw new ActionException("JSON.BAD_SCHEMA", "Cannot load a schema from '" + uri + "'", "Use a file next to the schema or an http(s) URL.");
            } catch (java.io.IOException e) {
                throw new ActionException("JSON.REF_UNREACHABLE", "Cannot load referenced schema " + uri + ": " + NetErrors.reason(e, String.valueOf(uri.getHost())), "Check the network and the $ref URL.", true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ActionException("RUN.CANCELLED", "Stopped while loading " + uri, "The run was stopped before this step finished. Run the agent again when it should go on.");
            }
            String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            try { return JsonLite.parse(text); }
            catch (IllegalArgumentException e) { throw new ActionException("JSON.BAD_SCHEMA", "Referenced schema " + uri + " is not valid JSON: " + e.getMessage(), "Check the referenced file."); }
        };
    }

    private static Map<String, Object> flatten(Map<?, ?> m, String prefix, Map<String, Object> out) {
        m.forEach((k, v) -> {
            String name = prefix + k;
            if (v instanceof Map<?, ?> inner && !inner.isEmpty()) flatten(inner, name + ".", out); else out.put(name, v);
        });
        return out;
    }

    @Action(id = "validate", name = "Validate JSON", category = "JSON", idempotent = true,
            description = "Check JSON: that it is well written, that the paths you need are there and, if you give one, that it follows a JSON Schema.",
            panel = "json, required_keys, (Schema: No schema = / Schema text = schema / Schema file = schema_path) | check_formats, load_remote_refs")
    @Output(name = "valid", type = OoaType.BOOLEAN, description = "True when the JSON parses and every required path exists.")
    @Output(name = "errors", type = OoaType.LIST, description = "Problems found, each with the path where it was found.")
    @Output(name = "level", type = OoaType.STRING, description = "What was checked: syntax (the text did not parse), structure or schema.")
    public Map<String, Object> validate(ActionContext ctx,
            @Input(name = "json", description = "JSON text, Dictionary or List to check.") Object json,
            @Input(name = "required_keys", type = OoaType.LIST, required = false, description = "Paths that must exist, e.g. [\"id\", \"customer.email\"].") List<Object> required,
            @Input(name = "schema", required = false, description = "JSON Schema (draft-07 to 2020-12) as a Dictionary or JSON text; $ref may point inside it, to another file (with schema_path or an $id) or to a URL.") Object schema,
            @Input(name = "schema_path", type = OoaType.FILE, required = false, description = "JSON Schema file; used when 'schema' is empty. References such as address.json#/$defs/x are read from its folder.") String schemaPath,
            @Input(name = "check_formats", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Check the format keyword: date-time, date, time, duration, email, hostname, ipv4, ipv6, uri, uuid, regex and json-pointer.") boolean formats,
            @Input(name = "load_remote_refs", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Follow $ref to other schema files and http(s) URLs (30 s timeout, 5 MB limit); false allows references inside the schema only.") boolean remote) {
        List<Object> errors = new ArrayList<>();
        Object doc;
        if (json instanceof String s) {
            try { doc = JsonLite.parse(s); } catch (IllegalArgumentException e) { errors.add("Not valid JSON: " + e.getMessage()); return Out.of("valid", false, "errors", errors, "level", "syntax"); }
        } else doc = json;
        if (required != null) for (Object r : required) {
            String p = com.oneofficeautomation.runner.Values.text(r);
            try { walk(doc, pathTokens(p), p); } catch (ActionException e) { errors.add("Missing required path '" + p + "'"); }
        }
        Object sc = schema;
        java.net.URI base = null;
        if ((sc == null || "".equals(sc)) && schemaPath != null && !schemaPath.isBlank()) {
            sc = readFile(ctx, schemaPath, "utf-8").get("value");
            base = ctx.resolvePath(schemaPath).toAbsolutePath().normalize().toUri();
        }
        if (sc instanceof String s && !s.isBlank()) {
            try { sc = JsonLite.parse(s); } catch (IllegalArgumentException e) { throw new ActionException("JSON.BAD_SCHEMA", "The schema is not valid JSON: " + e.getMessage(), "Check the schema text."); }
        }
        boolean withSchema = sc instanceof Map<?, ?> || sc instanceof Boolean;
        if (withSchema) errors.addAll(JsonSchemaLite.validate(sc, doc, base, remote ? schemaLoader(ctx) : null, formats));
        return Out.of("valid", errors.isEmpty(), "errors", errors, "level", withSchema ? "schema" : "structure");
    }
}
