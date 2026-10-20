package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Custom connectors saved in the workspace as {@code connectors/<name>.connector.json}. Used by the {@code ooa.connector}
 * actions (with the run's working folder as the workspace) and by One Agent Studio.
 */
public final class ConnectorStore {
    private ConnectorStore() {}

    public static final String FOLDER = "connectors";
    public static final String SUFFIX = ".connector.json";

    public static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,59}$"))
            throw new ActionException("CONNECTOR.BAD_NAME", "Invalid connector name '" + name + "'",
                    "Use letters, digits, - and _ (up to 60 characters, starting with a letter or digit).");
        return n;
    }

    public static Path file(Path workspace, String name) { return workspace.resolve(FOLDER).resolve(checkName(name) + SUFFIX); }

    /** Name, title, server, operation count and source of every connector, sorted by name. */
    public static List<Map<String, Object>> list(Path workspace) {
        Path dir = workspace.resolve(FOLDER);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                String name = p.getFileName().toString();
                name = name.substring(0, name.length() - SUFFIX.length());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                try {
                    Map<String, Object> def = read(p);
                    row.put("title", def.get("title"));
                    row.put("server", def.get("server"));
                    row.put("operations", java.math.BigDecimal.valueOf(def.get("operations") instanceof List<?> l ? l.size() : 0));
                    row.put("source", def.get("source") instanceof Map<?, ?> src ? src.get("type") : null);
                } catch (ActionException e) { row.put("error", e.getMessage()); }
                out.add(row);
            }
        } catch (IOException e) { throw new ActionException("CONNECTOR.IO", "Cannot list connectors: " + e.getMessage()); }
        return out;
    }

    public static Map<String, Object> load(Path workspace, String name) {
        Path p = file(workspace, name);
        if (!Files.isRegularFile(p)) throw new ActionException("CONNECTOR.NOT_FOUND", "There is no connector named '" + name + "'",
                "Import the API description first (Connectors in One Agent Studio, or the import_connector action).");
        return read(p);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path p) {
        try {
            Object v = JsonLite.parse(Files.readString(p, StandardCharsets.UTF_8));
            if (!(v instanceof Map<?, ?> m) || !(m.get("operations") instanceof List<?>))
                throw new ActionException("CONNECTOR.BAD_DEFINITION", p.getFileName() + " is not a connector definition");
            return (Map<String, Object>) m;
        } catch (IOException e) {
            throw new ActionException("CONNECTOR.IO", "Cannot read " + p.getFileName() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ActionException("CONNECTOR.BAD_DEFINITION", p.getFileName() + " is not valid JSON: " + e.getMessage());
        }
    }

    /** Saves atomically; returns the workspace-relative path. */
    public static String save(Path workspace, Map<String, Object> def, boolean overwrite) {
        String name = checkName(String.valueOf(def.get("name")));
        Path p = file(workspace, name);
        if (!overwrite && Files.exists(p)) throw new ActionException("CONNECTOR.EXISTS", "A connector named '" + name + "' already exists",
                "Choose another name, or allow replacing it.");
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("." + p.getFileName() + ".saving");
            Files.writeString(tmp, pretty(def), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new ActionException("CONNECTOR.IO", "Cannot save connector '" + name + "': " + e.getMessage()); }
        return FOLDER + "/" + name + SUFFIX;
    }

    /** Deletes a connector; returns false when it did not exist. */
    public static boolean delete(Path workspace, String name) {
        try { return Files.deleteIfExists(file(workspace, name)); }
        catch (IOException e) { throw new ActionException("CONNECTOR.IO", "Cannot delete connector '" + name + "': " + e.getMessage()); }
    }

    /** Indented JSON so definitions are easy to read and edit by hand. */
    static String pretty(Object v) { StringBuilder sb = new StringBuilder(); pretty(v, sb, ""); return sb.append('\n').toString(); }

    private static void pretty(Object v, StringBuilder sb, String ind) {
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            sb.append("{\n");
            int i = 0;
            for (var e : m.entrySet()) {
                sb.append(ind).append("  ").append(JsonLite.write(String.valueOf(e.getKey()))).append(": ");
                pretty(e.getValue(), sb, ind + "  ");
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            sb.append(ind).append('}');
        } else if (v instanceof List<?> l && !l.isEmpty() && l.stream().anyMatch(x -> x instanceof Map || x instanceof List)) {
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                sb.append(ind).append("  ");
                pretty(l.get(i), sb, ind + "  ");
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            sb.append(ind).append(']');
        } else sb.append(JsonLite.write(v));
    }
}
