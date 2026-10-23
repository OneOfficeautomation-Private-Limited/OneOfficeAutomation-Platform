package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Files in the repository that are not agents: templates, workbooks, lists and pictures agents use. They live in the
 * same folders (and folder permissions) as agents. Contents are stored once per content in the data folder
 * (repo-files/&lt;sha256&gt;); the database keeps each path's current content and who changed it. An agent lists the
 * files it needs in requires.files, and the runner receives them with the job, at the same path in its workspace.
 */
public final class RepoFiles {
    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final long MAX_PER_RUN = 50L * 1024 * 1024;
    static final Pattern FILE = Pattern.compile("^[A-Za-z0-9 _.()-]+(/[A-Za-z0-9 _.()-]+){0,8}$");
    private final ControlRoom cr;
    private final Db db;
    private final Path store;

    RepoFiles(ControlRoom cr, Path dataDir) {
        this.cr = cr; this.db = cr.db;
        this.store = dataDir.resolve("repo-files");
    }

    static String check(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (!FILE.matcher(p).matches() || p.contains("..") || p.matches(".*\\.(flow|process|form)\\.json$"))
            throw new OoaException("CR.BAD_PATH", "Invalid file path '" + path + "'", "Use folders and a file name, e.g. Finance/Templates/invoice.xlsx (agents are checked in from Studio).");
        return p;
    }

    public List<Map<String, Object>> list(User by, String folder) {
        cr.require(by, "repo.view", "view the agent repository");
        String in = folder == null || folder.isBlank() ? null : Folders.normalize(folder) + "/";
        Map<String, Map<String, String>> perms = cr.folders.entries();
        List<String> mine = cr.teams.principalsOf(by.username(), by.role());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : db.query("SELECT path, size, sha256, content_type, updated, updated_by, version FROM repo_files ORDER BY path")) {
            String p = (String) f.get("path");
            if (in != null && !p.startsWith(in)) continue;
            String a = cr.folders.accessToFolder(mine, by.role(), Folders.folderOf(p), perms);
            if (a.equals("none")) continue;
            Map<String, Object> m = new LinkedHashMap<>(f);
            m.put("folder", Folders.folderOf(p));
            m.put("access", a);
            out.add(m);
        }
        return out;
    }

    /** Stores a new content for the path (a new version of the file). */
    public Map<String, Object> put(User by, String path, byte[] content, String contentType, String ip) {
        cr.require(by, "repo.checkin", "add files to the repository");
        String p = check(path);
        cr.folders.require(by, p, "edit", "add files to this folder");
        if (content == null || content.length == 0) throw new OoaException("CR.BAD_INPUT", "The file is empty");
        if (content.length > MAX_BYTES) throw new OoaException("CR.TOO_LARGE", "Repository files are at most " + (MAX_BYTES >> 20) + " MB");
        if (db.one("SELECT path FROM bots WHERE path = ?", p) != null) throw new OoaException("CR.CONFLICT", "An agent is at " + p);
        String sha = sha256(content);
        try {
            Files.createDirectories(store);
            Path f = store.resolve(sha);
            if (!Files.exists(f)) Files.write(f, content);
        } catch (IOException e) { throw new OoaException("CR.IO", "Cannot store the file: " + e.getMessage()); }
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        int version = db.tx(() -> {
            Map<String, Object> old = db.one("SELECT version FROM repo_files WHERE path = ?", p);
            if (old == null) {
                db.update("INSERT INTO repo_files (path, size, sha256, content_type, created, updated, updated_by, version) VALUES (?,?,?,?,?,?,?,?)",
                        p, (long) content.length, sha, type, now(), now(), by.username(), 1);
                return 1;
            }
            int v = ((Number) old.get("version")).intValue() + 1;
            db.update("UPDATE repo_files SET size = ?, sha256 = ?, content_type = ?, updated = ?, updated_by = ?, version = ? WHERE path = ?", (long) content.length, sha, type, now(), by.username(), v, p);
            return v;
        });
        cr.audit.record(by.username(), "repo.file_put", "file", p, Map.of("size", content.length, "sha256", sha, "version", version), "ok", ip);
        return Map.of("path", p, "version", version, "size", content.length, "sha256", sha);
    }

    public Map<String, Object> get(User by, String path) {
        cr.require(by, "repo.view", "view the agent repository");
        String p = check(path);
        cr.folders.require(by, p, "view", "read files of this folder");
        return read(p);
    }

    /** The file's content and type (no permission check: callers check). */
    Map<String, Object> read(String p) {
        Map<String, Object> f = db.one("SELECT sha256, content_type FROM repo_files WHERE path = ?", p);
        if (f == null) throw notFound("File " + p);
        try { return Map.of("content", Files.readAllBytes(store.resolve((String) f.get("sha256"))), "content_type", f.get("content_type")); }
        catch (IOException e) { throw new OoaException("CR.IO", "The stored content of " + p + " is missing: " + e.getMessage()); }
    }

    public void delete(User by, String path, String ip) {
        cr.require(by, "repo.delete", "delete repository files");
        String p = check(path);
        cr.folders.require(by, p, "edit", "delete files in this folder");
        if (db.update("DELETE FROM repo_files WHERE path = ?", p) == 0) throw notFound("File " + p);
        cr.audit.record(by.username(), "repo.file_delete", "file", p, null, "ok", ip);
    }

    /**
     * The files a run's agent requires (flow.requires.files), as {path: base64}, for the job. The person the run is for
     * must be allowed to view each file's folder; a missing or forbidden file fails the run before it starts.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> forJob(Map<String, Object> flow, String username, String role) {
        List<Object> wanted = flow.get("requires") instanceof Map<?, ?> r && r.get("files") instanceof List<?> l ? (List<Object>) l : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        for (Object o : wanted) {
            String p = check(String.valueOf(o));
            cr.folders.require(username, role, p, "view", "use the file " + p);
            byte[] b = (byte[]) read(p).get("content");
            total += b.length;
            if (total > MAX_PER_RUN) throw new OoaException("CR.TOO_LARGE", "The files an agent requires are at most " + (MAX_PER_RUN >> 20) + " MB in all");
            out.put(p, Base64.getEncoder().encodeToString(b));
        }
        return out;
    }

    static String sha256(byte[] b) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
