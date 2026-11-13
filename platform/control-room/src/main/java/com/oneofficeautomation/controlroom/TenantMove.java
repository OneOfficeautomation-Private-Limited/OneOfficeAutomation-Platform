package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.vault.VaultAdmin;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Moving one on-premises One CommandCenter into a SaaS tenant (round 4 item A4).
 * <p>
 * {@code ooa tenant export} writes one file, encrypted with a key made from a passphrase (PBKDF2-HMAC-SHA256, then
 * AES-256-GCM in 1 MB chunks, each with its own tag, so a file of any size is checked without holding it in memory and
 * a file cut short is noticed). Inside is a zip:
 * <pre>
 *   manifest.json          format, product and schema version, module versions, rows per table
 *   files/...              repository file contents (repo-files/SHA256), templates, buckets (and ai-content with history)
 *   audit/audit.jsonl      the Audit Trail, kept as a read-only archive (it does not join the tenant's own chain)
 *   db/TABLE.jsonl         the tables, one JSON row per line; stored secrets as {"$secret": plain text}
 *   end.json               how many secrets went in; the last entry, so the import knows the file is whole
 * </pre>
 * Secrets are decrypted with the on-premises vault key on the way out and encrypted with the tenant's key, bound as each
 * column expects, on the way in ({@link #SECRETS}). Runners keep their identity: devices move with their secret hashes,
 * so a runner only needs {@code ooa agent move --url https://TENANT.REGION-DOMAIN}.
 */
public final class TenantMove {
    private TenantMove() { }

    public static final String FORMAT = "ooa-tenant-move-1";
    static final byte[] MAGIC = "OOAMOVE1".getBytes(StandardCharsets.US_ASCII);
    static final int CHUNK = 1 << 20, ITERATIONS = 600_000, MIN_PASSPHRASE = 12;

    /** Tables that belong to the server, not to the customer: never moved, with the reason the report shows. */
    public static final Map<String, String> LEFT_OUT = new LinkedHashMap<>();
    /** Run history: moved only with --with-history. */
    public static final Set<String> HISTORY = new LinkedHashSet<>(List.of("runs", "run_events", "run_waits", "workflow_runs", "human_tasks", "ai_calls",
        "chat_runs", "app_runs", "test_runs", "test_results", "alerts", "kpi_values"));
    /** Settings of the install, not of the customer: the tenant keeps its own. */
    public static final Set<String> OWN_SETTINGS = Set.of("evaluation_started", "install_id", "audit_chain_key", "public_url", "repo_files_folder", "backup_folder");
    /** Folders of the data folder that hold customer content (repo-files is handled by itself, as it may be a shared folder). */
    static final List<String> FOLDERS = List.of("templates", "buckets");
    static {
        LEFT_OUT.put("audit", "kept as a read-only archive in the tenant's folder; the tenant's Audit Trail starts with the import");
        LEFT_OUT.put("sessions", "people sign in again at the new address");
        LEFT_OUT.put("auth_pending", "unfinished sign-ins end");
        LEFT_OUT.put("cluster_lease", "the region runs its own nodes");
        LEFT_OUT.put("cluster_nodes", "the region runs its own nodes");
        LEFT_OUT.put("schema_version", "the tenant has its own (checked to be the same)");
        LEFT_OUT.put("module_schema", "the tenant has its own (checked to be the same)");
        LEFT_OUT.put("registration_keys", "make new registration keys in the tenant; registered runners keep theirs");
        LEFT_OUT.put("licenses", "the tenant uses the seats the operator gives it");
        LEFT_OUT.put("license_usage", "the tenant counts its own use");
        LEFT_OUT.put("runner_files", "runner installers come from the region");
        LEFT_OUT.put("chat_sessions", "open chat conversations end");
        LEFT_OUT.put("environment_locks", "held by runs that ended with the old server");
        LEFT_OUT.put("custom_toolkits", "custom toolkits do not load into a shared SaaS server; ask the operator for an approved toolkit");
    }

    // ------------------------------------------------------------ the stored secrets

    /**
     * Every column the vault encrypts, with what each value is bound to (the GCM associated data). A new encrypted
     * column must be added here, or it would not survive a move (and to VaultAdmin.reencryptAll, or a key rotation).
     */
    public record Secret(String table, String column, String about) { }
    public static final List<Secret> SECRETS = List.of(
        new Secret("credentials", "ciphertext", "vault values, bound to locker/name/attribute"),
        new Secret("credential_user_values", "ciphertext", "people's own vault values (also device sign-in), bound to locker/name/attribute#user:NAME"),
        new Secret("lockers", "external_secret", "external vault sign-in secrets, bound to external:LOCKER"),
        new Secret("queue_items", "data", "work items of encrypted queues, bound to queue-item:ID"),
        new Secret("settings", "json_value", "phone notification topics (push_topic:NAME), bound to their setting name"),
        new Secret("users", "mfa_secret", "two-step sign-in secrets, bound to mfa:USER-ID"),
        new Secret("event_subscriptions", "secret", "event subscription signing secrets, bound to event-subscription:ID"));

    static String secretColumn(String table) {
        for (Secret s : SECRETS) if (s.table().equals(table)) return s.column();
        return null;
    }

    /** What a row's secret is bound to. */
    static String binding(String table, Map<String, Object> r) {
        return switch (table) {
            case "credentials" -> r.get("locker") + "/" + r.get("name") + "/" + r.get("attribute");
            case "credential_user_values" -> VaultAdmin.userBound(r.get("locker") + "/" + r.get("name") + "/" + r.get("attribute"), String.valueOf(r.get("username")));
            case "lockers" -> "external:" + r.get("name");
            case "queue_items" -> "queue-item:" + r.get("id");
            case "settings" -> String.valueOf(r.get("name"));
            case "users" -> "mfa:" + r.get("id");
            case "event_subscriptions" -> "event-subscription:" + r.get("id");
            default -> throw new IllegalArgumentException(table);
        };
    }

    /** Whether this row's value in the secret column is encrypted (some rows keep it empty or in plain text). */
    static boolean encrypted(String table, Map<String, Object> r, Object v) {
        if (!(v instanceof String s) || s.isEmpty()) return false;
        return switch (table) {
            case "queue_items" -> s.startsWith("v1:");
            case "settings" -> String.valueOf(r.get("name")).startsWith("push_topic:");
            default -> true;
        };
    }

    // ------------------------------------------------------------ the tables

    /** The kernel's tables and every installed module's, in creation order. */
    static List<String> allTables() {
        List<String> out = new ArrayList<>(Db.tables());
        Pattern create = Pattern.compile("^CREATE TABLE (?:IF NOT EXISTS )?(\\w+) ");
        for (CommandCenterModule m : Modules.discover())
            for (List<String> step : m.schema()) for (String ddl : step) {
                Matcher x = create.matcher(ddl.trim());
                if (x.find() && !out.contains(x.group(1).toLowerCase(Locale.ROOT))) out.add(x.group(1).toLowerCase(Locale.ROOT));
            }
        return out;
    }

    /** The tables a move carries (with or without run history). */
    static List<String> movedTables(boolean history) {
        List<String> out = new ArrayList<>();
        for (String t : allTables()) if (!LEFT_OUT.containsKey(t) && (history || !HISTORY.contains(t))) out.add(t);
        return out;
    }

    static Map<String, Object> moduleVersions() {
        Map<String, Object> m = new TreeMap<>();
        for (CommandCenterModule x : Modules.discover()) m.put(x.id(), x.schema().size());
        return m;
    }

    // ------------------------------------------------------------ export

    /**
     * Writes the move file of an on-premises One CommandCenter (stopped): its database, vault key and data folder.
     * Returns what went in.
     */
    public static Map<String, Object> export(Db db, Vault vault, Path data, Path out, char[] passphrase, boolean history) throws IOException {
        if (passphrase == null || passphrase.length < MIN_PASSPHRASE)
            throw new OoaException("MOVE.PASSPHRASE", "The passphrase must be at least " + MIN_PASSPHRASE + " characters", "Use a long passphrase and keep it apart from the file; the import needs it.");
        int schema = db.schemaVersion();
        if (schema != Db.LATEST) throw new OoaException("MOVE.VERSION", "The database is at schema version " + schema + "; this release needs " + Db.LATEST,
            "Run the export with the release that runs this One CommandCenter, after ooa db migrate.");
        List<String> tables = movedTables(history);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String t : tables) counts.put(t, t.equals("settings") ? count(db, t) - ownSettings(db) : count(db, t));
        Path store = repoStore(db, data);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("created", Instant.now().toString());
        manifest.put("product_version", ControlRoom.VERSION);
        manifest.put("schema", schema);
        manifest.put("modules", moduleVersions(db));
        manifest.put("engine", db.sqlServer ? "sqlserver" : db.postgres ? "postgresql" : "h2");
        manifest.put("with_history", history);
        manifest.put("tables", counts);
        manifest.put("left_out", leftOut(db, history));
        Path part = out.resolveSibling(out.getFileName() + ".part");
        long[] secrets = {0};
        List<String> missing = new ArrayList<>();
        int files = 0;
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(part));
             ZipOutputStream zip = new ZipOutputStream(encrypt(file, passphrase))) {
            put(zip, "manifest.json", JsonLite.write(manifest).getBytes(StandardCharsets.UTF_8));
            // repository file contents, each checked against its SHA-256 name
            for (Map<String, Object> r : db.query("SELECT DISTINCT sha256 FROM repo_files")) {
                String sha = String.valueOf(r.get("sha256"));
                Path f = store.resolve(sha);
                if (!sha.matches("[0-9a-f]{64}") || !Files.isRegularFile(f)) { missing.add(sha); continue; }
                zip.putNextEntry(new ZipEntry("files/repo-files/" + sha));
                Files.copy(f, zip);
                zip.closeEntry();
                files++;
            }
            List<String> folders = new ArrayList<>(FOLDERS);
            if (history) folders.add("ai-content");
            for (String sub : folders) {
                Path root = data.resolve(sub);
                if (!Files.isDirectory(root)) continue;
                try (var w = Files.walk(root)) {
                    for (Path p : w.filter(Files::isRegularFile).toList()) {
                        zip.putNextEntry(new ZipEntry("files/" + data.relativize(p).toString().replace('\\', '/')));
                        Files.copy(p, zip);
                        zip.closeEntry();
                        files++;
                    }
                }
            }
            // the Audit Trail as it is, with its checkpoints, to keep as an archive
            zip.putNextEntry(new ZipEntry("audit/audit.jsonl"));
            writeRows(db, "audit", zip, null, null);
            zip.closeEntry();
            Path checkpoints = data.resolve("audit-checkpoints.log");
            if (Files.isRegularFile(checkpoints)) { zip.putNextEntry(new ZipEntry("audit/audit-checkpoints.log")); Files.copy(checkpoints, zip); zip.closeEntry(); }
            for (String t : tables) {
                zip.putNextEntry(new ZipEntry("db/" + t + ".jsonl"));
                writeRows(db, t, zip, vault, secrets);
                zip.closeEntry();
            }
            put(zip, "end.json", JsonLite.write(Map.of("secrets", secrets[0], "files", files, "missing_files", missing)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        long rows = counts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("file", out.toString());
        r.put("size_mb", Math.round(Files.size(out) / 10485.76) / 100.0);
        r.put("tables", counts);
        r.put("rows", rows);
        r.put("secrets", secrets[0]);
        r.put("files", files);
        r.put("missing_files", missing);
        r.put("audit_rows", count(db, "audit"));
        r.put("left_out", manifest.get("left_out"));
        return r;
    }

    /** Where repository file contents are: the shared folder of the setting repo_files_folder, else DATA/repo-files. */
    static Path repoStore(Db db, Path data) {
        Map<String, Object> s = db.one("SELECT json_value FROM settings WHERE name = 'repo_files_folder'");
        String shared = s == null ? "" : String.valueOf(JsonLite.parse((String) s.get("json_value"))).trim();
        return shared.isEmpty() || shared.equals("null") ? data.resolve("repo-files") : Path.of(shared);
    }

    static Map<String, Object> moduleVersions(Db db) {
        Map<String, Object> m = new TreeMap<>();
        for (Map<String, Object> r : db.query("SELECT module, version FROM module_schema")) m.put(String.valueOf(r.get("module")), ((Number) r.get("version")).intValue());
        return m;
    }

    /** What the move leaves behind, and why (the import adds it to its report). */
    static List<Map<String, Object>> leftOut(Db db, boolean history) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : LEFT_OUT.entrySet()) {
            if (!Db.tables().contains(e.getKey())) continue;
            long n = count(db, e.getKey());
            if (n > 0) out.add(Map.of("what", "table " + e.getKey() + " (" + n + " rows)", "why", e.getValue()));
        }
        if (!history) {
            long n = 0;
            for (String t : HISTORY) if (allTables().contains(t)) n += count(db, t);
            if (n > 0) out.add(Map.of("what", "run history (" + n + " rows)", "why", "export with --with-history to move it"));
        }
        for (String s : OWN_SETTINGS) if (db.one("SELECT name FROM settings WHERE name = ?", s) != null)
            out.add(Map.of("what", "setting " + s, "why", "it belongs to the install; the tenant has its own"));
        out.add(Map.of("what", "database, HTTPS and Document Automation passwords in DATA/config", "why", "the region has its own database, certificate and DocFlow service"));
        return out;
    }

    static long ownSettings(Db db) {
        long n = 0;
        for (String s : OWN_SETTINGS) if (db.one("SELECT name FROM settings WHERE name = ?", s) != null) n++;
        return n;
    }

    static long count(Db db, String table) {
        String sql = "SELECT COUNT(*) AS n FROM " + known(table);
        return ((Number) db.one(sql).get("n")).longValue();
    }

    /** A table name for SQL: only one of the schema's own tables (names from a move file are checked before they reach SQL). */
    static String known(String table) {
        if (!allTables().contains(table)) throw damaged("an unknown table " + table);
        return table;
    }

    private static final Pattern COLUMN = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** One table as JSON lines; with a vault, its stored secrets are decrypted into {"$secret": ...}. */
    private static void writeRows(Db db, String table, OutputStream to, Vault vault, long[] secrets) {
        Writer w = new OutputStreamWriter(to, StandardCharsets.UTF_8);   // flushed, never closed: the zip entry stays open
        String col = vault == null ? null : secretColumn(table);
        db.withConnection(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), Backups.encode(rs.getObject(i)));
                    if (table.equals("settings") && OWN_SETTINGS.contains(String.valueOf(row.get("name")))) continue;
                    if (col != null && encrypted(table, row, row.get(col))) {
                        String bound = binding(table, row), stored = (String) row.get(col);
                        if (table.equals("settings")) stored = String.valueOf(JsonLite.parse(stored));
                        String plain;
                        try { plain = vault.decrypt(stored, bound); }
                        catch (OoaException e) {
                            throw new OoaException("MOVE.SECRET", "A secret in " + table + " (" + bound.replaceAll("#user:.*", "") + ") does not decrypt with this server's vault key",
                                "Check that OOA_MASTER_KEY or master.key is this One CommandCenter's key (ooa check); remove or set again the value the message names, then export again.");
                        }
                        row.put(col, Map.of("$secret", plain));
                        secrets[0]++;
                    }
                    w.write(JsonLite.write(row)); w.write('\n');
                }
                w.flush();
            } catch (SQLException | IOException e) { throw new OoaException("MOVE.EXPORT", "Reading " + table + " failed: " + e.getMessage(), "Check that the database is running; then export again."); }
            return null;
        });
    }

    private static void put(ZipOutputStream zip, String name, byte[] b) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(b);
        zip.closeEntry();
    }

    // ------------------------------------------------------------ import

    /** An opened move file: its passphrase checked and its manifest read; then {@link #loadInto}. */
    public static final class Reader {
        private final ZipInputStream zip;
        public final Map<String, Object> manifest;

        private Reader(ZipInputStream zip, Map<String, Object> manifest) { this.zip = zip; this.manifest = manifest; }

        /** The manifest's rows per table. */
        @SuppressWarnings("unchecked")
        Map<String, Object> tables() { return (Map<String, Object>) manifest.get("tables"); }

        /**
         * Loads the file into a tenant's database (its schema made, nothing of the customer in it yet), folder and key.
         * Files go to the folder first; the tables go in one transaction that also reads to the end of the file, so a
         * file cut short or changed leaves the database as it was. Returns the check report.
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> loadInto(Db db, Vault vault, Path folder) throws IOException {
            Path root = folder.toAbsolutePath().normalize();
            String stamp = java.time.LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Map<String, Object> imported = new LinkedHashMap<>();
            long[] secrets = {0};
            int[] files = {0};
            Map<String, Object> archive = new LinkedHashMap<>();
            Map<String, Object>[] end = new Map[1];
            ZipEntry e = zip.getNextEntry();
            // files and the audit archive come before the tables
            while (e != null && !e.getName().startsWith("db/") && !e.getName().equals("end.json")) {
                String n = e.getName();
                if (n.startsWith("files/") && !e.isDirectory()) {
                    Path to = root.resolve(n.substring(6)).normalize();
                    if (!to.startsWith(root)) throw damaged("a file outside the tenant's folder (" + n + ")");
                    Files.createDirectories(to.getParent());
                    if (n.startsWith("files/repo-files/")) {
                        MessageDigest sha = sha256();
                        Path tmp = to.resolveSibling(to.getFileName() + ".part");
                        try (OutputStream o = new java.security.DigestOutputStream(Files.newOutputStream(tmp), sha)) { zip.transferTo(o); }
                        if (!HexFormat.of().formatHex(sha.digest()).equals(to.getFileName().toString())) { Files.deleteIfExists(tmp); throw damaged("repository file " + to.getFileName() + " does not match its SHA-256"); }
                        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
                    } else Files.copy(zip, to, StandardCopyOption.REPLACE_EXISTING);
                    files[0]++;
                } else if (n.equals("audit/audit.jsonl") || n.equals("audit/audit-checkpoints.log")) {
                    Path to = root.resolve("imported").resolve(n.endsWith(".log") ? "audit-checkpoints-" + stamp + ".log" : "audit-" + stamp + ".jsonl");
                    Files.createDirectories(to.getParent());
                    MessageDigest sha = sha256();
                    long lines;
                    try (OutputStream o = new java.security.DigestOutputStream(Files.newOutputStream(to), sha)) { lines = copyCountingLines(zip, o); }
                    to.toFile().setReadOnly();
                    if (n.endsWith(".jsonl")) { archive.put("file", root.relativize(to).toString().replace('\\', '/')); archive.put("sha256", HexFormat.of().formatHex(sha.digest())); archive.put("rows", lines); }
                }
                e = zip.getNextEntry();
            }
            ZipEntry first = e;
            List<String> tables = new ArrayList<>(tables().keySet());
            for (String t : tables) if (!allTables().contains(t)) throw damaged("an unknown table " + t);
            db.tx(() -> {
                try {
                    clear(db, tables);
                    ZipEntry x = first;
                    while (x != null && x.getName().startsWith("db/")) {
                        String t = x.getName().substring(3, x.getName().length() - 6);
                        if (!tables.contains(t)) throw damaged("a table the manifest does not list (" + t + ")");
                        long n = insertRows(db, vault, t, zip, secrets);
                        long want = ((Number) tables().get(t)).longValue();
                        if (n != want) throw damaged(t + " has " + n + " rows, the manifest says " + want);
                        imported.put(t, n);
                        x = zip.getNextEntry();
                    }
                    if (x == null || !x.getName().equals("end.json")) throw damaged("it ends before all its parts");
                    end[0] = (Map<String, Object>) JsonLite.parse(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    if (zip.getNextEntry() != null) throw damaged("it has parts after its end");
                    while (zip.read() >= 0) { /* read to the end, so the last chunk's tag is checked */ }
                    for (String t : tables) if (!imported.containsKey(t)) throw damaged("table " + t + " is missing");
                    if (((Number) end[0].get("secrets")).longValue() != secrets[0]) throw damaged("it holds " + secrets[0] + " secrets, its end says " + end[0].get("secrets"));
                } catch (IOException io) { throw damaged(io.getMessage()); }
                return null;
            });
            Map<String, Object> report = new LinkedHashMap<>();
            Map<String, Object> per = new LinkedHashMap<>();
            for (String t : tables) per.put(t, Map.of("exported", tables().get(t), "imported", imported.get(t)));
            report.put("tables", per);
            report.put("rows", imported.values().stream().mapToLong(v -> ((Number) v).longValue()).sum());
            report.put("secrets", secrets[0]);
            report.put("files", files[0]);
            report.put("missing_files", end[0].get("missing_files"));
            report.put("audit_archive", archive);
            report.put("left_out", manifest.get("left_out"));
            report.put("from", Map.of("product_version", manifest.get("product_version"), "engine", manifest.get("engine"), "created", manifest.get("created")));
            return report;
        }
    }

    /**
     * Opens a move file: checks the passphrase (the first chunk's tag), reads the manifest and checks it was made by a
     * release with the same schema as this one, for the kernel and every module.
     */
    @SuppressWarnings("unchecked")
    public static Reader open(InputStream in, char[] passphrase) throws IOException {
        ZipInputStream zip = new ZipInputStream(decrypt(in, passphrase));
        ZipEntry e = zip.getNextEntry();
        if (e == null || !e.getName().equals("manifest.json")) throw damaged("it has no manifest");
        Map<String, Object> m = (Map<String, Object>) JsonLite.parse(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        if (!FORMAT.equals(m.get("format"))) throw new OoaException("MOVE.FORMAT", "Unknown move file format " + m.get("format"), "Import it into a region of the release that made it.");
        int schema = ((Number) m.get("schema")).intValue();
        if (schema != Db.LATEST) throw new OoaException("MOVE.VERSION",
            "The file was made by release " + m.get("product_version") + " (schema " + schema + "); this region runs " + ControlRoom.VERSION + " (schema " + Db.LATEST + ")",
            schema < Db.LATEST ? "Upgrade the on-premises One CommandCenter to the region's release, then export again." : "Upgrade the region to the release that made the file, then import again.");
        Map<String, Object> here = moduleVersions(), there = (Map<String, Object>) m.getOrDefault("modules", Map.of());
        for (Map.Entry<String, Object> x : there.entrySet()) {
            Object h = here.get(x.getKey());
            if (h == null) throw new OoaException("MOVE.VERSION", "The file holds data of module " + x.getKey() + ", which this region does not have", "Install the module in the region, or import into a region that has it.");
            if (((Number) h).intValue() != ((Number) x.getValue()).intValue())
                throw new OoaException("MOVE.VERSION", "Module " + x.getKey() + " is at schema " + x.getValue() + " in the file and " + h + " here", "Run the same release on both sides, then export again.");
        }
        return new Reader(zip, m);
    }

    /** Whether a tenant's database holds anything of a customer: more than its first administrator, or any content. */
    public static boolean hasData(Db db) {
        if (count(db, "users") > 1) return true;
        for (String t : List.of("bots", "repo_files", "devices", "credentials", "lockers", "queues", "schedules", "triggers", "runs", "global_values", "teams"))
            if (count(db, t) > 0) return true;
        return false;
    }

    /** Empties what the import fills: the moved tables, the bootstrap's sign-ins and the settings the tenant does not keep. */
    private static void clear(Db db, List<String> tables) {
        List<String> empty = new ArrayList<>();
        for (int i = tables.size() - 1; i >= 0; i--) if (!tables.get(i).equals("settings")) empty.add(tables.get(i));
        empty.addAll(List.of("sessions", "auth_pending", "registration_keys"));
        for (String t : empty) {
            String sql = "DELETE FROM " + known(t);
            db.update(sql);
        }
        for (Map<String, Object> s : db.query("SELECT name FROM settings"))
            if (!OWN_SETTINGS.contains(String.valueOf(s.get("name")))) db.update("DELETE FROM settings WHERE name = ?", s.get("name"));
    }

    private static long insertRows(Db db, Vault vault, String table, InputStream in, long[] secrets) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String col = secretColumn(table);
        long n = 0;
        for (String line; (line = r.readLine()) != null; ) {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) JsonLite.parse(line);
            if (table.equals("settings") && OWN_SETTINGS.contains(String.valueOf(row.get("name")))) { n++; continue; }
            StringJoiner cols = new StringJoiner(", "), marks = new StringJoiner(", ");
            List<Object> vals = new ArrayList<>();
            for (Map.Entry<String, Object> c : row.entrySet()) {
                if (!COLUMN.matcher(c.getKey()).matches()) throw damaged("a column name that is not one (" + table + ")");
                Object v = c.getValue();
                if (v instanceof Map<?, ?> m && m.containsKey("$secret")) {
                    if (!c.getKey().equals(col)) throw damaged("a secret in an unexpected column " + table + "." + c.getKey());
                    String ct = vault.encrypt(String.valueOf(m.get("$secret")), binding(table, row));
                    v = table.equals("settings") ? JsonLite.write(ct) : ct;
                    secrets[0]++;
                } else v = Backups.decode(v);
                cols.add(db.sqlServer ? "[" + c.getKey() + "]" : "\"" + c.getKey() + "\""); marks.add("?"); vals.add(v);
            }
            String sql = "INSERT INTO " + known(table) + " (" + cols + ") VALUES (" + marks + ")";
            db.update(sql, vals.toArray());
            n++;
        }
        return n;
    }

    private static long copyCountingLines(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[65536];
        long lines = 0;
        for (int k; (k = in.read(b)) > 0; ) { out.write(b, 0, k); for (int i = 0; i < k; i++) if (b[i] == '\n') lines++; }
        return lines;
    }

    static OoaException damaged(String what) {
        return new OoaException("MOVE.DAMAGED", "The move file is damaged or incomplete: " + what, "Copy the file again (check its size), or export it again; nothing was imported.");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException x) { throw new IllegalStateException(x); }
    }

    // ------------------------------------------------------------ the encrypted file

    /*
     * Layout: "OOAMOVE1", salt (16 bytes), PBKDF2 iterations (int), nonce prefix (8 bytes); then chunks of [int length,
     * AES-256-GCM ciphertext with its 16-byte tag]. Chunk i has the IV nonce-prefix + i and is bound to the header, its
     * number and whether it is the last one, so chunks cannot be reordered, dropped or cut off unnoticed.
     */

    static SecretKeySpec key(char[] passphrase, byte[] salt, int iterations) {
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(passphrase, salt, iterations, 256)).getEncoded();
            return new SecretKeySpec(k, "AES");
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static byte[] aad(byte[] header, long index, boolean last) {
        return ByteBuffer.allocate(header.length + 9).put(header).putLong(index).put((byte) (last ? 1 : 0)).array();
    }

    static byte[] iv(byte[] prefix, long index) { return ByteBuffer.allocate(12).put(prefix).putInt((int) index).array(); }

    /** A stream that encrypts what is written to it into out; closing it writes the last chunk and closes out. */
    public static OutputStream encrypt(OutputStream out, char[] passphrase) throws IOException {
        byte[] salt = new byte[16], prefix = new byte[8];
        Security.RANDOM.nextBytes(salt);
        Security.RANDOM.nextBytes(prefix);
        byte[] header = ByteBuffer.allocate(MAGIC.length + 16 + 4 + 8).put(MAGIC).put(salt).putInt(ITERATIONS).put(prefix).array();
        SecretKeySpec key = key(passphrase, salt, ITERATIONS);
        out.write(header);
        return new OutputStream() {
            final byte[] buf = new byte[CHUNK];
            int len;
            long index;
            boolean closed;
            @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int n) throws IOException {
                while (n > 0) {
                    if (len == CHUNK) chunk(false);
                    int k = Math.min(n, CHUNK - len);
                    System.arraycopy(b, off, buf, len, k);
                    len += k; off += k; n -= k;
                }
            }
            private void chunk(boolean last) throws IOException {
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv(prefix, index)));
                    c.updateAAD(aad(header, index, last));
                    byte[] ct = c.doFinal(buf, 0, len);
                    out.write(ByteBuffer.allocate(4).putInt(ct.length).array());
                    out.write(ct);
                    index++;
                    len = 0;
                } catch (java.security.GeneralSecurityException e) { throw new IOException("Encryption failed: " + e.getMessage(), e); }
            }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                chunk(true);
                out.close();
            }
        };
    }

    /** A stream of the plain content of an encrypted move file; a wrong passphrase fails on the first chunk. */
    public static InputStream decrypt(InputStream raw, char[] passphrase) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
        byte[] header = new byte[MAGIC.length + 16 + 4 + 8];
        try { in.readFully(header); } catch (EOFException e) { throw notAMoveFile(); }
        if (!Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)) throw notAMoveFile();
        ByteBuffer h = ByteBuffer.wrap(header, MAGIC.length, header.length - MAGIC.length);
        byte[] salt = new byte[16], prefix = new byte[8];
        h.get(salt);
        int iterations = h.getInt();
        h.get(prefix);
        if (iterations < 100_000 || iterations > 10_000_000) throw damaged("its header");
        SecretKeySpec key = key(passphrase, salt, iterations);
        return new InputStream() {
            byte[] plain = new byte[0];
            int pos;
            long index;
            boolean done;
            private boolean fill() throws IOException {
                while (pos >= plain.length) {
                    if (done) return false;
                    int n;
                    try { n = in.readInt(); } catch (EOFException e) { throw damaged("it is cut short"); }
                    if (n < 16 || n > CHUNK + 16) throw damaged("a chunk of a wrong size");
                    byte[] ct = new byte[n];
                    try { in.readFully(ct); } catch (EOFException e) { throw damaged("it is cut short"); }
                    // the last chunk is told by its tag: try "not last" first, then "last"
                    byte[] p = open(ct, false);
                    if (p == null) {
                        p = open(ct, true);
                        if (p == null) {
                            if (index == 0) throw new OoaException("MOVE.PASSPHRASE", "The passphrase does not open this move file", "Use the passphrase given to ooa tenant export (or the file was changed; copy it again).");
                            throw damaged("chunk " + index + " was changed");
                        }
                        done = true;
                        if (in.read() >= 0) throw damaged("it has data after its end");
                    }
                    index++;
                    plain = p;
                    pos = 0;
                }
                return true;
            }
            private byte[] open(byte[] ct, boolean last) throws IOException {
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv(prefix, index)));
                    c.updateAAD(aad(header, index, last));
                    return c.doFinal(ct);
                } catch (AEADBadTagException e) { return null; }
                catch (java.security.GeneralSecurityException e) { throw new IOException("Decryption failed: " + e.getMessage(), e); }
            }
            @Override public int read() throws IOException { return fill() ? plain[pos++] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (!fill()) return -1;
                int k = Math.min(len, plain.length - pos);
                System.arraycopy(plain, pos, b, off, k);
                pos += k;
                return k;
            }
            @Override public void close() throws IOException { in.close(); }
        };
    }

    private static OoaException notAMoveFile() {
        return new OoaException("MOVE.FORMAT", "This is not a ONEOFFICEAUTOMATION move file", "Choose the file ooa tenant export wrote (.ooamove).");
    }
}
