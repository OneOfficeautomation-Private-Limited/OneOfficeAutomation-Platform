package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@OoaPackage(id = "ooa.json", name = "JSON", version = "1.0.0", description = "Parse, query and write JSON documents.")
public final class JsonPackage {

    @Action(id = "parse", name = "Parse JSON", category = "JSON", idempotent = true, description = "Parse JSON text into a Dictionary or List.")
    @Output(name = "value", description = "Parsed value.")
    public Map<String, Object> parse(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "JSON text.") String text) {
        try { return Collections.singletonMap("value", JsonLite.parse(text)); }
        catch (IllegalArgumentException e) { throw new ActionException("JSON.INVALID", e.getMessage(), "Check the JSON syntax."); }
    }

    @Action(id = "stringify", name = "Convert to JSON text", category = "JSON", idempotent = true, description = "Serialise a value to JSON text.")
    @Output(name = "text", type = OoaType.STRING, description = "JSON text.")
    public Map<String, Object> stringify(ActionContext ctx, @Input(name = "value", description = "Value to serialise.") Object value) {
        return Map.of("text", JsonLite.write(value));
    }

    @Action(id = "get_node", name = "Get node value", category = "JSON", idempotent = true, description = "Read a value by dotted path, e.g. order.items[0].sku.")
    @Output(name = "result", description = "Value at the path, or empty.")
    public Map<String, Object> getNode(ActionContext ctx,
            @Input(name = "value", description = "Dictionary or List to query.") Object value,
            @Input(name = "path", type = OoaType.STRING, description = "Dotted path with [n] indexes.") String path) {
        Object cur = value;
        for (String part : path.replace("[", ".").replace("]", "").split("\\.")) {
            if (part.isEmpty()) continue;
            if (cur instanceof List<?> l) {
                try { int i = Integer.parseInt(part); cur = i >= 0 && i < l.size() ? l.get(i) : null; } catch (NumberFormatException e) { cur = null; }
            } else if (cur instanceof Map<?, ?> m) cur = m.get(part);
            else cur = null;
        }
        return Collections.singletonMap("result", cur);
    }

    @Action(id = "read_file", name = "Read JSON file", category = "JSON", idempotent = true, capabilities = "filesystem", description = "Read and parse a JSON file.")
    @Output(name = "value", description = "Parsed content.")
    public Map<String, Object> readFile(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Path to a .json file.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        try { return Collections.singletonMap("value", JsonLite.parse(Files.readString(p))); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p + ": " + e.getMessage(), null, true); }
        catch (IllegalArgumentException e) { throw new ActionException("JSON.INVALID", p + ": " + e.getMessage(), "Check the JSON syntax."); }
    }

    @Action(id = "write_file", name = "Write JSON file", category = "JSON", capabilities = "filesystem", description = "Write a value to a JSON file atomically.")
    public Map<String, Object> writeFile(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Target path.") String path,
            @Input(name = "value", description = "Value to write.") Object value) {
        Path p = ctx.resolvePath(path);
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, JsonLite.write(value));
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + p + ": " + e.getMessage(), null, true); }
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
            description = "Add a new key to a dictionary, or insert an item into a list, at a path such as order.items[1] or /order/items/-.")
    @Output(name = "value", description = "Updated document.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> insertNode(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, example = "order.items[0].note", description = "Path of the new node; its parent must exist. Use - or the list size to append.") String path,
            @Input(name = "node", required = false, description = "Value to insert.") Object node) {
        Object doc = document(value);
        List<String> t = needPath(path);
        Object parent = walk(doc, t.subList(0, t.size() - 1), path);
        String last = t.get(t.size() - 1);
        if (parent instanceof Map<?, ?> m) {
            if (m.containsKey(last)) throw new ActionException("JSON.NODE_EXISTS", "Key '" + last + "' already exists at '" + path + "'", "Use update_node to change it.");
            ((Map<String, Object>) m).put(last, node);
        } else if (parent instanceof List<?> l) ((List<Object>) l).add(listIndex(l, last, path, true), node);
        else throw new ActionException("JSON.PATH_NOT_FOUND", "The parent of '" + path + "' is not a Dictionary or List", "Check the path.");
        return Collections.singletonMap("value", doc);
    }

    @Action(id = "update_node", name = "Update node", category = "JSON", idempotent = true, description = "Replace the value of an existing node at a path.")
    @Output(name = "value", description = "Updated document.")
    @Output(name = "previous", description = "The value that was replaced.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateNode(ActionContext ctx,
            @Input(name = "value", description = "Dictionary, List or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, description = "Path of the node to change.") String path,
            @Input(name = "node", required = false, description = "New value.") Object node) {
        Object doc = document(value);
        List<String> t = needPath(path);
        Object parent = walk(doc, t.subList(0, t.size() - 1), path);
        String last = t.get(t.size() - 1);
        Object prev;
        if (parent instanceof Map<?, ?> m) {
            if (!m.containsKey(last)) throw new ActionException("JSON.PATH_NOT_FOUND", "Key '" + last + "' not found at '" + path + "'", "Use insert_node to add a new key.");
            prev = ((Map<String, Object>) m).put(last, node);
        } else if (parent instanceof List<?> l) prev = ((List<Object>) l).set(listIndex(l, last, path, false), node);
        else throw new ActionException("JSON.PATH_NOT_FOUND", "The parent of '" + path + "' is not a Dictionary or List", "Check the path.");
        return Out.of("value", doc, "previous", prev);
    }

    @Action(id = "delete_node", name = "Delete node", category = "JSON", idempotent = true, description = "Remove the node at a path.")
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

    @Action(id = "get_node_array", name = "Get node list", category = "JSON", idempotent = true, description = "Return the list found at a path, ready to loop over.")
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
            description = "Turn a JSON array of objects into a table; each key becomes a column.")
    @Output(name = "table", type = OoaType.TABLE, description = "Table with one row per object.")
    public Map<String, Object> toTable(ActionContext ctx,
            @Input(name = "value", description = "List of dictionaries, or JSON text.") Object value,
            @Input(name = "path", type = OoaType.STRING, required = false, description = "Path of the array inside the document; the whole document when empty.") String path) {
        Object node = walk(document(value), pathTokens(path), path);
        if (!(node instanceof List<?> l)) throw new ActionException("JSON.NOT_AN_ARRAY", "Expected a list of objects, got " + com.oneofficeautomation.runner.Values.typeName(node), "Point the path at a JSON array.");
        for (int i = 0; i < l.size(); i++) if (!(l.get(i) instanceof Map))
            throw new ActionException("JSON.NOT_OBJECTS", "Item " + i + " is " + com.oneofficeautomation.runner.Values.typeName(l.get(i)) + ", not an object", "Every array item must be a JSON object.");
        if (l.isEmpty()) return Out.of("table", com.oneofficeautomation.runner.Values.table(List.of(), new ArrayList<>()));
        return Out.of("table", com.oneofficeautomation.runner.Values.coerce(l, "Table", "value"));
    }

    @Action(id = "validate", name = "Validate JSON", category = "JSON", idempotent = true,
            description = "Check that text is well-formed JSON and that required paths exist; full JSON Schema is not supported.")
    @Output(name = "valid", type = OoaType.BOOLEAN, description = "True when the JSON parses and every required path exists.")
    @Output(name = "errors", type = OoaType.LIST, description = "Problems found.")
    public Map<String, Object> validate(ActionContext ctx,
            @Input(name = "json", description = "JSON text, Dictionary or List to check.") Object json,
            @Input(name = "required_keys", type = OoaType.LIST, required = false, description = "Paths that must exist, e.g. [\"id\", \"customer.email\"].") List<Object> required) {
        List<Object> errors = new ArrayList<>();
        Object doc;
        if (json instanceof String s) {
            try { doc = JsonLite.parse(s); } catch (IllegalArgumentException e) { errors.add("Not valid JSON: " + e.getMessage()); return Out.of("valid", false, "errors", errors); }
        } else doc = json;
        if (required != null) for (Object r : required) {
            String p = com.oneofficeautomation.runner.Values.text(r);
            try { walk(doc, pathTokens(p), p); } catch (ActionException e) { errors.add("Missing required path '" + p + "'"); }
        }
        return Out.of("valid", errors.isEmpty(), "errors", errors);
    }
}
