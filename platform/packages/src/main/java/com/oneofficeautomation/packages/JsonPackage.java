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
}
