package com.oneofficeautomation.modules.ai;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import static com.oneofficeautomation.controlroom.module.ModuleContext.id;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Knowledge bases (gap G71): knowledge indexes kept in One CommandCenter, so many agents ask the same documents and an
 * index is refreshed in one place. A base is made in AI > Knowledge bases (name, the folder whose agents may use it,
 * what it holds); an indexing agent fills and refreshes it with Knowledge Q&A > Build index (files, folders, web pages,
 * texts), usually on a schedule; agents search and ask it by name. Runs fetch it once and again only when it changed.
 * <p>Kept in the data folder under knowledge/&lt;id&gt;/: meta.json and index.json.gz (the Knowledge Q&A index as the
 * runner built it, embeddings included). Nothing about the documents is sent anywhere by One CommandCenter itself.
 */
public final class KnowledgeBases {
    static final long MAX_BYTES = 200L * 1024 * 1024;

    private final ModuleContext ctx;
    private final Path root;

    public KnowledgeBases(ModuleContext ctx) { this.ctx = ctx; this.root = ctx.dataDir().resolve("knowledge"); }

    @SuppressWarnings("unchecked")
    synchronized List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.sorted().toList()) {
                Path m = d.resolve("meta.json");
                if (Files.isRegularFile(m)) try { out.add((Map<String, Object>) JsonLite.parse(Files.readString(m, StandardCharsets.UTF_8))); } catch (IOException | RuntimeException ignored) { }
            }
        } catch (IOException e) { throw new OoaException("CR.IO", "Cannot read the knowledge bases: " + e.getMessage()); }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    Map<String, Object> byName(String name) {
        for (Map<String, Object> m : all()) if (String.valueOf(m.get("name")).equalsIgnoreCase(String.valueOf(name).strip()) || String.valueOf(m.get("id")).equals(name)) return m;
        throw new OoaException("CR.NOT_FOUND", "No knowledge base '" + name + "'", "Make it in AI > Knowledge bases first.");
    }

    private void save(Map<String, Object> m) {
        try {
            Path d = root.resolve((String) m.get("id"));
            Files.createDirectories(d);
            Path tmp = d.resolve("meta.json.tmp");
            Files.writeString(tmp, JsonLite.write(m), StandardCharsets.UTF_8);
            Files.move(tmp, d.resolve("meta.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new OoaException("CR.IO", "Cannot save the knowledge base: " + e.getMessage()); }
    }

    public List<Map<String, Object>> list(User by) { ctx.require(by, "repo.view", "see knowledge bases"); return all(); }

    public synchronized Map<String, Object> save(User by, String id, Map<String, Object> b, String ip) {
        ctx.require(by, "knowledge.manage", "manage knowledge bases");
        String name = b.get("name") == null ? "" : String.valueOf(b.get("name")).strip();
        if (!name.matches("[A-Za-z0-9 _.-]{1,80}") || name.contains("..")) throw new OoaException("CR.BAD_INPUT", "Give the knowledge base a name of up to 80 letters, digits, spaces and _ . -");
        String folder = b.get("folder") == null || String.valueOf(b.get("folder")).isBlank() ? "" : ctx.normalizeFolder(String.valueOf(b.get("folder")));
        String desc = b.get("description") == null ? "" : String.valueOf(b.get("description")).strip();
        if (desc.length() > 1000) throw new OoaException("CR.BAD_INPUT", "The description is up to 1,000 characters");
        for (Map<String, Object> o : all()) if (String.valueOf(o.get("name")).equalsIgnoreCase(name) && !o.get("id").equals(id)) throw new OoaException("CR.CONFLICT", "A knowledge base is already called '" + name + "'");
        Map<String, Object> m;
        if (id == null) {
            m = new LinkedHashMap<>();
            m.put("id", id("kb")); m.put("created", Instant.now().toString()); m.put("created_by", by.username());
            m.put("passages", 0); m.put("documents", 0); m.put("bytes", 0); m.put("sha256", "");
        } else m = new LinkedHashMap<>(byName(id));
        m.put("name", name); m.put("folder", folder); m.put("description", desc);
        save(m);
        ctx.audit(by.username(), id == null ? "knowledge.create" : "knowledge.update", "knowledge_base", (String) m.get("id"), Map.of("name", name, "folder", folder), "ok", ip);
        return m;
    }

    public synchronized void delete(User by, String id, String ip) {
        ctx.require(by, "knowledge.manage", "manage knowledge bases");
        Map<String, Object> m = byName(id);
        try { deleteTree(root.resolve((String) m.get("id"))); } catch (Exception e) { throw new OoaException("CR.IO", "Cannot delete it: " + e.getMessage()); }
        ctx.audit(by.username(), "knowledge.delete", "knowledge_base", (String) m.get("id"), Map.of("name", m.get("name")), "ok", ip);
    }

    /** A running run publishes or fetches a base: {op: publish|fetch, name, ...}; the run's agent must be in the base's folder. */
    public Map<String, Object> forRun(Map<String, Object> device, Map<String, Object> body) {
        Map<String, Object> run = ctx.runOfDevice(device, (String) body.get("run_id"));
        if (!"running".equals(run.get("status"))) throw new OoaException("CR.FORBIDDEN", "Only running agents can use knowledge bases");
        Map<String, Object> r = body.get("request") instanceof Map<?, ?> rq ? cast(rq) : Map.of();
        Map<String, Object> m = byName(String.valueOf(r.get("name")));
        String folder = String.valueOf(m.get("folder"));
        if (!folder.isEmpty() && !String.valueOf(run.get("bot_path")).startsWith(folder + "/"))
            throw new OoaException("CR.FORBIDDEN", "The knowledge base " + m.get("name") + " is only for agents in " + folder);
        Path index = root.resolve((String) m.get("id")).resolve("index.json.gz");
        try {
            if ("publish".equals(r.get("op"))) {
                byte[] gz = Base64.getDecoder().decode(String.valueOf(r.get("content")));
                if (gz.length > MAX_BYTES) throw new OoaException("CR.TOO_LARGE", "A knowledge base is up to 200 MB");
                synchronized (this) {
                    Path tmp = index.resolveSibling("index.json.gz.tmp");
                    Files.write(tmp, gz);
                    Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    Map<String, Object> nm = new LinkedHashMap<>(byName((String) m.get("id")));
                    nm.put("sha256", sha(gz)); nm.put("bytes", gz.length); nm.put("passages", r.get("passages")); nm.put("documents", r.get("documents"));
                    nm.put("updated", Instant.now().toString()); nm.put("updated_by_run", run.get("id")); nm.put("updated_by_agent", run.get("bot_path"));
                    save(nm);
                }
                ctx.audit("agent:" + run.get("bot_path"), "knowledge.publish", "knowledge_base", (String) m.get("id"), Map.of("passages", String.valueOf(r.get("passages")), "run", run.get("id")), "ok", null);
                return Map.of("ok", true);
            }
            if (!Files.isRegularFile(index)) throw new OoaException("KNOWLEDGE.EMPTY", "The knowledge base " + m.get("name") + " has no index yet", "Run its indexing agent (Build index with this knowledge base).");
            if (String.valueOf(m.get("sha256")).equals(r.get("sha256"))) return Map.of("not_modified", true, "sha256", m.get("sha256"));
            byte[] gz = Files.readAllBytes(index);
            return Map.of("sha256", sha(gz), "content", Base64.getEncoder().encodeToString(gz));
        } catch (IOException e) { throw new OoaException("CR.IO", "Cannot use the knowledge base: " + e.getMessage(), null, true, null); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    static String sha(byte[] b) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Deletes a folder and everything in it (a base's index files). */
    private static void deleteTree(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.exists(dir)) return;
        try (var walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) { p.toFile().setWritable(true); java.nio.file.Files.deleteIfExists(p); }
        }
    }
}
