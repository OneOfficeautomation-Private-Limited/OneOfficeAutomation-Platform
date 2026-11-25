package com.oneofficeautomation.sdk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * The action catalog other programs read (S6, 25 Nov 2026; docs/integration/one-chat-contract.md section 17): every
 * action, condition and loop type of this release with its fields and results, in a plain, stable shape that does not
 * change when the settings panels do. OneChat builds its model's catalog from it, so it never falls behind the
 * platform's actions, and One CommandCenter compares the checksum OneChat reports with its own.
 *
 * <p>Shape: JSON Lines. The first line is the header {format, entries, sha256}; each later line is one entry in
 * canonical JSON (keys sorted, no spaces), sorted by kind then id. sha256 is the SHA-256 (hex) of the entry lines
 * joined with "\n" (no newline at the end), UTF-8. An entry: {id, kind (action, condition or loop_type), toolkit_id,
 * toolkit, name, description, inputs: [{name, type, required, label?, description?, default?, enum?}], outputs:
 * [{name, type, description?}]}, inputs in the order the toolkit declares them.
 */
public final class ActionCatalog {
    public static final String FORMAT = "ooa-action-catalog/v1";

    private ActionCatalog() {}

    /** The entries of a set of toolkit manifests (what PackageRegistry.allManifests gives), sorted by kind then id. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> entries(List<Map<String, Object>> manifests) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : manifests) {
            String pkg = String.valueOf(m.get("id")), toolkit = String.valueOf(m.getOrDefault("name", pkg));
            for (String[] k : new String[][]{{"actions", "action"}, {"conditions", "condition"}, {"iterators", "loop_type"}})
                for (Object o : (List<Object>) m.getOrDefault(k[0], List.of())) if (o instanceof Map<?, ?> a)
                    out.add(entry(pkg, toolkit, k[1], (Map<String, Object>) a));
        }
        out.sort(Comparator.comparing((Map<String, Object> e) -> kindOrder(String.valueOf(e.get("kind")))).thenComparing(e -> String.valueOf(e.get("id"))));
        return out;
    }

    private static int kindOrder(String k) { return switch (k) { case "action" -> 0; case "condition" -> 1; default -> 2; }; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(String pkg, String toolkit, String kind, Map<String, Object> a) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", pkg + "/" + a.get("id"));
        e.put("kind", kind);
        e.put("toolkit_id", pkg);
        e.put("toolkit", toolkit);
        e.put("name", String.valueOf(a.getOrDefault("name", a.get("id"))));
        e.put("description", String.valueOf(a.getOrDefault("description", "")));
        Map<String, Object> in = a.get("inputs") instanceof Map<?, ?> im ? (Map<String, Object>) im : Map.of();
        Map<String, Object> props = in.get("properties") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
        List<Object> required = in.get("required") instanceof List<?> l ? (List<Object>) l : List.of();
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (Map.Entry<String, Object> p : props.entrySet()) {
            Map<String, Object> s = p.getValue() instanceof Map<?, ?> sm ? (Map<String, Object>) sm : Map.of();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", p.getKey());
            f.put("type", type(s));
            f.put("required", required.contains(p.getKey()));
            if (s.get("x-ooa-label") != null) f.put("label", String.valueOf(s.get("x-ooa-label")));
            if (s.get("description") != null) f.put("description", String.valueOf(s.get("description")));
            if (s.containsKey("default")) f.put("default", s.get("default"));
            if (s.get("enum") instanceof List<?> en) f.put("enum", en);
            inputs.add(f);
        }
        e.put("inputs", inputs);
        List<Map<String, Object>> outputs = new ArrayList<>();
        if (a.get("outputs") instanceof Map<?, ?> om) for (Map.Entry<?, ?> o : om.entrySet()) {
            Map<String, Object> s = o.getValue() instanceof Map<?, ?> sm ? (Map<String, Object>) sm : Map.of();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", String.valueOf(o.getKey()));
            f.put("type", type(s));
            if (s.get("description") != null) f.put("description", String.valueOf(s.get("description")));
            outputs.add(f);
        }
        e.put("outputs", outputs);
        return e;
    }

    private static String type(Map<String, Object> s) {
        Object t = s.get("x-ooa-type") != null ? s.get("x-ooa-type") : s.get("type");
        return t == null ? "Any" : String.valueOf(t);
    }

    /** The entry lines: each entry in canonical JSON. */
    public static List<String> lines(List<Map<String, Object>> entries) {
        List<String> out = new ArrayList<>(entries.size());
        for (Map<String, Object> e : entries) out.add(JsonLite.canonical(e));
        return out;
    }

    /** SHA-256 (hex) of the entry lines joined with "\n". */
    public static String sha256(List<String> lines) { return sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)); }

    /** SHA-256 (hex) of some bytes. */
    public static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The whole file: the header line, then the entry lines, each ending with "\n". */
    public static String jsonl(List<Map<String, Object>> manifests) {
        List<String> lines = lines(entries(manifests));
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", FORMAT);
        header.put("entries", lines.size());
        header.put("sha256", sha256(lines));
        StringBuilder sb = new StringBuilder(JsonLite.write(header)).append('\n');
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    /** The header {format, entries, sha256} of a set of manifests, without writing the file. */
    public static Map<String, Object> header(List<Map<String, Object>> manifests) {
        List<String> lines = lines(entries(manifests));
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", FORMAT);
        header.put("entries", lines.size());
        header.put("sha256", sha256(lines));
        return header;
    }
}
