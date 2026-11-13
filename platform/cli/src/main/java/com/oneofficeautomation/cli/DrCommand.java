package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.DbConfig;
import com.oneofficeautomation.controlroom.Vault;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Disaster recovery (14 Nov 2026, round 4 item A2), on top of docs/infrastructure/07 section 8 (a database replica at a
 * second site, repository files copied there, standby nodes stopped):
 * <pre>
 * ooa dr status                 this site's database, leader, nodes, repository files and last backup
 * ooa dr check                  the same as checks (PASS / WARN / FAIL with what to do); exit code 1 on a FAIL
 * ooa dr promote [--public-url URL] [--yes]
 *                               after the DBA has failed the database over to this site: check it is writable, end the
 *                               old site's leader lease so a node here leads at once, set the address people and runners
 *                               use, and write a record to DATA\dr\promote-<time>.txt
 * </pre>
 * The database fail-over itself stays with the DBA (SQL Server Always On, log shipping, PostgreSQL streaming): this
 * command checks it happened and does One CommandCenter's part. Every command takes [--data DIR] and [--config FILE].
 */
final class DrCommand {
    private DrCommand() { }

    record Check(String name, String result, String detail, String fix) {}

    static int run(String verb, Map<String, String> o) {
        Path data = Ooa.dataDir(o);
        Path file = o.containsKey("config") ? Path.of(o.get("config")) : DbConfig.defaultFile(data);
        DbConfig cfg = DbConfig.load(file);
        try (Db db = Db.open(cfg, data, new Vault(data), verb.equals("promote"))) {
            List<Check> checks = checks(db, data);
            return switch (verb) {
                case "status", "check" -> { print(checks, cfg); yield verb.equals("check") && checks.stream().anyMatch((c) -> c.result().equals("FAIL")) ? 1 : 0; }
                case "promote" -> promote(db, data, checks, o, cfg);
                default -> throw new IllegalArgumentException("Unknown dr command " + verb);
            };
        }
    }

    static void print(List<Check> checks, DbConfig cfg) {
        if (cfg != null) System.out.println("Database: " + cfg.label());
        for (Check c : checks) {
            System.out.printf("%-5s %-22s %s%n", c.result(), c.name(), c.detail());
            if (c.fix() != null && !c.result().equals("PASS")) System.out.println("      -> " + c.fix());
        }
    }

    /** What decides whether this site can take over (and, at the primary, whether the standby could). */
    static List<Check> checks(Db db, Path data) {
        List<Check> out = new ArrayList<>();
        // 1. writable: the replica is read-only until the DBA fails it over
        Boolean writable = null;
        try {
            if (db.sqlServer) writable = "READ_WRITE".equals(String.valueOf(db.one("SELECT CAST(DATABASEPROPERTYEX(DB_NAME(), 'Updateability') AS VARCHAR(20)) AS u").get("u")));
            else if (db.postgres) writable = !Boolean.TRUE.equals(db.one("SELECT pg_is_in_recovery() AS r").get("r"));
        } catch (RuntimeException e) { out.add(new Check("Database writable", "FAIL", e.getMessage(), "Check the database connection.")); }
        if (db.h2) out.add(new Check("Database", "WARN", "the embedded evaluation database has no replica", "Use SQL Server or PostgreSQL with a replica at the second site."));
        else if (writable != null) out.add(writable ? new Check("Database writable", "PASS", "this site's database accepts changes", null)
            : new Check("Database writable", "FAIL", "the database here is a read-only replica", "Have the DBA fail the database over to this site (Always On, log shipping or PostgreSQL promote) first."));
        // 2. the schema is the release's
        int version = schema(db);
        out.add(version == Db.LATEST ? new Check("Schema", "PASS", "version " + version, null)
            : new Check("Schema", "FAIL", "version " + version + ", this release needs " + Db.LATEST, version < Db.LATEST ? "Run ooa db migrate, or install the primary site's release here." : "Install the primary site's release here: both sites run the same version."));
        // 3. the vault key matches: the database password decrypted to open it; a vault value must decrypt too
        Map<String, Object> cred = db.one("SELECT locker, name, attribute, ciphertext FROM credentials");
        if (cred == null) out.add(new Check("Vault key", "PASS", "the database password decrypted with this site's key (no vault values to try)", null));
        else try {
            new Vault(data).decrypt((String) cred.get("ciphertext"), cred.get("locker") + "/" + cred.get("name") + "/" + cred.get("attribute"));
            out.add(new Check("Vault key", "PASS", "vault values decrypt with this site's key", null));
        } catch (RuntimeException e) {
            out.add(new Check("Vault key", "FAIL", "vault values do not decrypt here", "Give this site the primary's key: the same OOA_MASTER_KEY or OOA_MASTER_KEY_SOURCE."));
        }
        // 4. the leader lease: the old site must have stopped
        Map<String, Object> lease = db.one("SELECT holder, expires FROM cluster_lease WHERE name = 'leader'");
        if (lease == null) out.add(new Check("Leader", "PASS", "no node leads now", null));
        else {
            Instant until = parse(lease.get("expires"));
            out.add(until.isAfter(Instant.now()) ? new Check("Leader", "WARN", lease.get("holder") + " leads until " + until, "If that node is at the failed site, stop it; ooa dr promote ends its lease.")
                : new Check("Leader", "PASS", "the last leader (" + lease.get("holder") + ") stopped at " + until, null));
        }
        List<String> alive = new ArrayList<>();
        for (Map<String, Object> n : db.query("SELECT node_id, last_seen FROM cluster_nodes"))
            if (parse(n.get("last_seen")).isAfter(Instant.now().minus(Duration.ofMinutes(2)))) alive.add(String.valueOf(n.get("node_id")));
        out.add(new Check("Nodes running", alive.isEmpty() ? "PASS" : "WARN", alive.isEmpty() ? "none" : String.join(", ", alive),
                alive.isEmpty() ? null : "Only one site may write: stop the nodes at the other site before promoting this one."));
        // 5. repository files: every file the database names is in this site's folder
        Path store = setting(db, "repo_files_folder").isBlank() ? data.resolve("repo-files") : Path.of(setting(db, "repo_files_folder"));
        int missing = 0, total = 0;
        for (Map<String, Object> f : db.query("SELECT sha256 FROM repo_files")) { total++; if (!Files.isRegularFile(store.resolve(String.valueOf(f.get("sha256"))))) missing++; }
        out.add(missing == 0 ? new Check("Repository files", "PASS", total + " file(s) in " + store, null)
            : new Check("Repository files", "FAIL", missing + " of " + total + " file(s) missing in " + store, "Copy the primary's repository files folder here (DFS Replication, storage replication or robocopy /MIR)."));
        // 6. the newest backup
        Path backups = setting(db, "backup_folder").isBlank() ? data.resolve("backups") : Path.of(setting(db, "backup_folder"));
        Instant newest = newest(backups);
        out.add(newest == null ? new Check("Last backup", "WARN", "none in " + backups, "Copy the primary's backups here too, for a restore if the replica is damaged.")
            : newest.isBefore(Instant.now().minus(Duration.ofDays(1))) ? new Check("Last backup", "WARN", newest + " in " + backups, "Backups here are over a day old; check the copy from the primary.")
            : new Check("Last backup", "PASS", newest + " in " + backups, null));
        String url = setting(db, "public_url");
        out.add(new Check("Address", url.isBlank() ? "WARN" : "PASS", url.isBlank() ? "no public address is set" : url,
                url.isBlank() ? "Set it with ooa dr promote --public-url, and point the DNS name runners use at this site." : null));
        return out;
    }

    static int promote(Db db, Path data, List<Check> checks, Map<String, String> o, DbConfig cfg) {
        print(checks, cfg);
        List<Check> blocking = checks.stream().filter((c) -> c.result().equals("FAIL")).toList();
        if (!blocking.isEmpty()) throw new OoaException("CR.DR_NOT_READY", "This site cannot take over yet: " + blocking.get(0).name() + " failed", blocking.get(0).fix());
        if (!o.containsKey("yes")) {
            System.out.println("\nThis makes this site the active one. Run again with --yes to go ahead.");
            return 2;
        }
        List<String> done = new ArrayList<>();
        int ended = db.update("DELETE FROM cluster_lease WHERE name = 'leader'");
        done.add(ended > 0 ? "Ended the old leader lease: a node here leads as soon as it starts." : "No leader lease was held.");
        String url = o.get("public-url");
        if (url != null && !url.isBlank()) {
            if (!url.matches("(?i)https?://[^\\s\"'<>]+")) throw new OoaException("CR.BAD_INPUT", "The address is a web address, for example https://ooa-dr.example.com", "Give the address people and runners use at this site.");
            String json = JsonLite.write(url.replaceAll("/+$", ""));
            if (db.update("UPDATE settings SET json_value = ?, updated = ?, updated_by = ? WHERE name = 'public_url'", json, Instant.now(), "ooa dr promote") == 0)
                db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", "public_url", json, Instant.now(), "ooa dr promote");
            done.add("Set the public address to " + url + ".");
        }
        done.add("Next: start this site's nodes (ooa service start), point the DNS name runners use at this site, and check runners come online in Runner Machines.");
        Path record = data.resolve("dr").resolve("promote-" + Instant.now().toString().replace(':', '-') + ".txt");
        try {
            Files.createDirectories(record.getParent());
            StringBuilder sb = new StringBuilder("ooa dr promote at " + Instant.now() + " on " + host() + "\n\nChecks:\n");
            for (Check c : checks) sb.append(c.result()).append("  ").append(c.name()).append(": ").append(c.detail()).append('\n');
            sb.append("\nDone:\n");
            for (String d : done) sb.append("- ").append(d).append('\n');
            Files.writeString(record, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new OoaException("CR.DR_RECORD", "Could not write the record " + record + ": " + e.getMessage(), "Check the data folder can be written."); }
        System.out.println();
        done.forEach(System.out::println);
        System.out.println("Record: " + record);
        return 0;
    }

    private static String host() { try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "this computer"; } }

    private static int schema(Db db) {
        try { Map<String, Object> r = db.one("SELECT MAX(version) AS v FROM schema_version"); return r == null || r.get("v") == null ? 0 : ((Number) r.get("v")).intValue(); }
        catch (RuntimeException e) { return 0; }
    }

    static String setting(Db db, String name) {
        Map<String, Object> r = db.one("SELECT json_value FROM settings WHERE name = ?", name);
        if (r == null) return "";
        Object v = JsonLite.parse(String.valueOf(r.get("json_value")));
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Instant parse(Object t) {
        String s = String.valueOf(t);
        try { return Instant.parse(s); } catch (RuntimeException e) { return java.sql.Timestamp.valueOf(s.replace('T', ' ').replace("Z", "")).toInstant(); }
    }

    private static Instant newest(Path folder) {
        if (!Files.isDirectory(folder)) return null;
        try (Stream<Path> s = Files.list(folder)) {
            return s.filter(Files::isRegularFile).map((p) -> { try { return Files.getLastModifiedTime(p).toInstant(); } catch (IOException e) { return Instant.EPOCH; } })
                .max(Comparator.naturalOrder()).orElse(null);
        } catch (IOException e) { return null; }
    }
}
