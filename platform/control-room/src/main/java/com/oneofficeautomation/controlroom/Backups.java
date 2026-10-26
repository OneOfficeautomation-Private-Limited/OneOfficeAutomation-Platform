package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/**
 * Backups (C6): one zip per backup with every table as JSON lines (read through JDBC, so the same on H2, SQL Server and
 * PostgreSQL), the repository files and the configuration files of the data folder, and a manifest with the row counts.
 * The vault key is never put in a backup: a backup that carried its key would expose every secret in it, so back up
 * master.key (or OOA_MASTER_KEY) separately. Settings: backup_hours (0: no scheduled backups), backup_keep_days (7) and
 * backup_folder (default the data folder's backups). Restore with {@code ooa backup restore FILE}, the server stopped.
 */
public final class Backups {
    static final String FORMAT = "ooa-backup-1";
    private final ControlRoom cr;
    private final Db db;
    private volatile Map<String, Object> last = Map.of("state", "never");

    Backups(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    Path folder() {
        String f = String.valueOf(cr.platform.setting("backup_folder")).trim();
        return f.isEmpty() ? cr.dataDir.resolve("backups") : Path.of(f);
    }

    /** Runs from the clock: a backup when one is due, then old ones removed. */
    void tickIfDue() {
        int hours = cr.platform.intSetting("backup_hours");
        if (hours <= 0) return;
        Optional<Instant> newest = list().stream().map(m -> Instant.parse((String) m.get("created"))).max(Comparator.naturalOrder());
        if (newest.isPresent() && newest.get().isAfter(Instant.now().minus(Duration.ofHours(hours)))) return;
        try { backup("system"); } catch (RuntimeException ignored) { /* kept in status() and the audit trail */ }
        prune();
    }

    /** Removes backups older than backup_keep_days, always keeping the newest. */
    int prune() {
        int days = Math.max(1, cr.platform.intSetting("backup_keep_days"));
        List<Map<String, Object>> all = list();
        int n = 0;
        for (int i = 1; i < all.size(); i++) {   // list() is newest first
            Map<String, Object> b = all.get(i);
            if (Instant.parse((String) b.get("created")).isBefore(Instant.now().minus(Duration.ofDays(days)))) {
                try { Files.deleteIfExists(folder().resolve((String) b.get("file"))); n++; } catch (IOException ignored) { }
            }
        }
        return n;
    }

    /** Backups in the folder, newest first: file, created, size_mb. */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = folder();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().matches("ooa-backup-\\d{8}-\\d{6}\\.zip")).toList()) {
                String n = p.getFileName().toString();
                LocalDateTime t = LocalDateTime.parse(n.substring(11, 26), DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                out.add(new LinkedHashMap<>(Map.of("file", n, "created", t.toInstant(ZoneOffset.UTC).toString(), "size_mb", Math.round(Files.size(p) / 10485.76) / 100.0)));
            }
        } catch (IOException e) { return out; }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("created")).reversed());
        return out;
    }

    public Map<String, Object> status(User by) {
        cr.require(by, "settings.manage", "see backups");
        Map<String, Object> m = new LinkedHashMap<>(last);
        m.put("folder", folder().toString());
        m.put("backups", list());
        m.put("every_hours", cr.platform.intSetting("backup_hours"));
        m.put("keep_days", cr.platform.intSetting("backup_keep_days"));
        m.put("key_note", cr.vault.keyFile == null ? "The vault key comes from OOA_MASTER_KEY: keep a copy of it apart from the backups."
            : "The vault key (" + cr.vault.keyFile + ") is not in the backups: keep a copy of it apart from them.");
        return m;
    }

    public Map<String, Object> backupNow(User by, String ip) {
        cr.require(by, "settings.manage", "make backups");
        Map<String, Object> r = backup(by.username());
        prune();
        return r;
    }

    /** Makes one backup; returns {file, size_mb, rows}. */
    synchronized Map<String, Object> backup(String who) {
        Path dir = folder();
        String name = "ooa-backup-" + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
        Path file = dir.resolve(name), part = dir.resolve(name + ".part");
        try {
            Files.createDirectories(dir);
            Map<String, Object> counts = new LinkedHashMap<>();
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(part)))) {
                for (String t : Db.tables()) counts.put(t, writeTable(zip, t));
                writeFiles(zip, cr.dataDir, dir);
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("format", FORMAT); manifest.put("created", Instant.now().toString()); manifest.put("schema", Db.LATEST);
                manifest.put("engine", db.sqlServer ? "sqlserver" : db.postgres ? "postgresql" : "h2"); manifest.put("tables", counts);
                manifest.put("vault_key_fingerprint", cr.vault.fingerprint());
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(JsonLite.write(manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            long rows = counts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("state", "ok"); r.put("at", Instant.now().toString()); r.put("file", name); r.put("rows", rows); r.put("size_mb", Math.round(Files.size(file) / 10485.76) / 100.0);
            last = r;
            cr.audit.record(who, "backup.create", "backup", name, Map.of("rows", rows, "folder", dir.toString()), "ok", null);
            return r;
        } catch (Exception e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) { }
            last = Map.of("state", "failed", "at", Instant.now().toString(), "error", String.valueOf(e.getMessage()));
            cr.audit.record(who, "backup.create", "backup", name, Map.of("error", String.valueOf(e.getMessage())), "failed", null);
            throw e instanceof OoaException o ? o : new OoaException("CR.BACKUP", "The backup failed: " + e.getMessage(), "Check the backup folder exists and the server may write to it.");
        }
    }

    private long writeTable(ZipOutputStream zip, String table) throws IOException {
        zip.putNextEntry(new ZipEntry("db/" + table + ".jsonl"));
        long[] n = {0};
        Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        db.withConnection(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), encode(rs.getObject(i)));
                    w.write(JsonLite.write(row)); w.write('\n');
                    n[0]++;
                }
                w.flush();
            } catch (SQLException | IOException e) { throw new OoaException("CR.BACKUP", "Reading " + table + " failed: " + e.getMessage()); }
            return null;
        });
        zip.closeEntry();
        return n[0];
    }

    /** JSON for one value: timestamps as {"$ts": ISO}, bytes as {"$b": Base64}, large text as text. */
    static Object encode(Object v) throws SQLException {
        if (v instanceof Timestamp t) return Map.of("$ts", t.toInstant().toString());
        if (v instanceof java.time.OffsetDateTime o) return Map.of("$ts", o.toInstant().toString());
        if (v instanceof Clob c) return c.getSubString(1, (int) c.length());
        if (v instanceof byte[] b) return Map.of("$b", Base64.getEncoder().encodeToString(b));
        if (v instanceof java.math.BigDecimal || v instanceof Number || v instanceof Boolean || v instanceof String || v == null) return v;
        return String.valueOf(v);
    }

    static Object decode(Object v) {
        if (v instanceof Map<?, ?> m && m.get("$ts") instanceof String s) return Timestamp.from(Instant.parse(s));
        if (v instanceof Map<?, ?> m && m.get("$b") instanceof String s) return Base64.getDecoder().decode(s);
        return v;
    }

    /** Repository files and configuration (not the vault key, the backups themselves or other large leftovers). */
    private static void writeFiles(ZipOutputStream zip, Path data, Path backupDir) throws IOException {
        for (String sub : List.of("repo-files", "config")) {
            Path root = data.resolve(sub);
            if (!Files.isDirectory(root)) continue;
            try (var w = Files.walk(root)) {
                for (Path p : w.filter(Files::isRegularFile).toList()) {
                    if (p.startsWith(backupDir)) continue;
                    zip.putNextEntry(new ZipEntry("files/" + data.relativize(p).toString().replace('\\', '/')));
                    Files.copy(p, zip);
                    zip.closeEntry();
                }
            }
        }
        for (String f : List.of("audit-checkpoints.log")) {
            Path p = data.resolve(f);
            if (Files.isRegularFile(p)) { zip.putNextEntry(new ZipEntry("files/" + f)); Files.copy(p, zip); zip.closeEntry(); }
        }
    }

    /**
     * Restores a backup into a database of this release's schema (the server stopped): with replace, its rows are deleted
     * first; the files go back into the data folder. Returns {tables: {name: rows}}. The vault key must be the one the
     * backup was made with (its fingerprint is checked).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> restore(Path zipFile, Db target, Path data, Vault vault, boolean replace) throws IOException {
        Map<String, Object> manifest;
        try (ZipFile z = new ZipFile(zipFile.toFile())) {
            ZipEntry me = z.getEntry("manifest.json");
            if (me == null) throw new OoaException("CR.BACKUP", zipFile + " is not a ONEOFFICEAUTOMATION backup");
            manifest = (Map<String, Object>) JsonLite.parse(new String(z.getInputStream(me).readAllBytes(), StandardCharsets.UTF_8));
            if (!FORMAT.equals(manifest.get("format"))) throw new OoaException("CR.BACKUP", "Unknown backup format " + manifest.get("format"));
            if (((Number) manifest.get("schema")).intValue() > Db.LATEST) throw new OoaException("CR.BACKUP", "The backup comes from a newer release (schema " + manifest.get("schema") + ")", "Restore it with that release.");
            if (manifest.get("vault_key_fingerprint") != null && !manifest.get("vault_key_fingerprint").equals(vault.fingerprint()))
                throw new OoaException("CR.BACKUP", "This backup was made with another vault key", "Put the master.key of the backed-up Command Center in " + data + " (or set OOA_MASTER_KEY), then restore again.");
            target.migrate();
            List<String> tables = Db.tables();
            long existing = 0;
            for (String t : tables) existing += ((Number) target.one("SELECT COUNT(*) AS n FROM " + t).get("n")).longValue();
            if (existing > 0 && !replace) throw new OoaException("CR.BACKUP", "The database is not empty (" + existing + " rows)", "Restore with --replace to delete its rows first.");
            Map<String, Object> restored = new LinkedHashMap<>();
            target.tx(() -> {
                if (replace) for (int i = tables.size() - 1; i >= 0; i--) target.update("DELETE FROM " + tables.get(i));
                for (String t : tables) {
                    ZipEntry e = z.getEntry("db/" + t + ".jsonl");
                    if (e == null) continue;
                    long n = 0;
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) {
                            if (line.isBlank()) continue;
                            Map<String, Object> row = (Map<String, Object>) JsonLite.parse(line);
                            if (t.equals("schema_version")) continue;   // the target's own version stays
                            StringJoiner cols = new StringJoiner(", "), marks = new StringJoiner(", ");
                            List<Object> vals = new ArrayList<>();
                            for (Map.Entry<String, Object> c : row.entrySet()) { cols.add(target.sqlServer ? "[" + c.getKey() + "]" : "\"" + c.getKey() + "\""); marks.add("?"); vals.add(decode(c.getValue())); }
                            target.update("INSERT INTO " + t + " (" + cols + ") VALUES (" + marks + ")", vals.toArray());
                            n++;
                        }
                    } catch (IOException x) { throw new OoaException("CR.BACKUP", "Reading " + t + " from the backup failed: " + x.getMessage()); }
                    restored.put(t, n);
                }
                return null;
            });
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (!e.getName().startsWith("files/") || e.isDirectory()) continue;
                Path to = data.resolve(e.getName().substring(6)).normalize();
                if (!to.startsWith(data.normalize())) continue;
                Files.createDirectories(to.getParent());
                try (InputStream in = z.getInputStream(e)) { Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING); }
            }
            return Map.of("tables", restored, "created", manifest.get("created"));
        }
    }
}
