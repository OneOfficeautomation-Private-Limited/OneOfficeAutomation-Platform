package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.DbConfig;
import com.oneofficeautomation.controlroom.Vault;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * {@code ooa db}: sets up and looks after the Command Center database. The server itself signs in with the runtime
 * account (data rights only); these commands sign in with the setup account to create, upgrade and fill the schema.
 * <pre>
 * ooa db configure --engine sqlserver --host H [--port 1433] [--instance I] [--database D] --user ooa_app --setup-user ooa_setup [--tls required|off] [--tls-ca FILE] [--trust-any-certificate]
 * ooa db set-password [--setup] [--from-env VAR]   store a password encrypted with the vault master key
 * ooa db test                                     connect with the runtime account and report version, encryption and rights
 * ooa db init                                     create the database and schema, give the runtime account data rights
 * ooa db migrate                                  upgrade the schema after an update
 * ooa db script [--engine sqlserver|postgresql|h2] print the schema SQL for a DBA
 * ooa db copy --from DIR [--replace]              copy the data of an embedded H2 Command Center into the configured database
 * </pre>
 * Every command takes [--data DIR] (the Command Center data folder) and [--config FILE] (default DIR\config\ooa-db.properties).
 */
final class DbCommand {
    private DbCommand() { }

    static int run(String[] a) {
        if (a.length == 0) { usage(); return 1; }
        Map<String, String> o = Ooa.options(Arrays.copyOfRange(a, 1, a.length));
        Path data = Ooa.dataDir(o);
        Path file = o.containsKey("config") ? Path.of(o.get("config")) : DbConfig.defaultFile(data);
        return switch (a[0]) {
            case "configure" -> configure(file, o);
            case "set-password" -> setPassword(file, data, o);
            case "test" -> test(DbConfig.load(file), data);
            case "init" -> init(DbConfig.load(file), data);
            case "migrate" -> migrate(DbConfig.load(file), data);
            case "script" -> script(o.getOrDefault("engine", Files.exists(file) ? DbConfig.load(file).engine() : "sqlserver"));
            case "copy" -> copy(DbConfig.load(file), data, o);
            default -> { usage(); yield 1; }
        };
    }

    static void usage() {
        System.err.println("usage: ooa db configure|set-password|test|init|migrate|script|copy [--data DIR] [--config FILE]  (see docs/DATABASE.md)");
    }

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    private static String name(String v, String what) {
        if (!NAME.matcher(v).matches()) throw new OoaException("CR.DB_CONFIG", what + " may hold only letters, digits, '.', '-' and '_': " + v);
        return v;
    }

    static int configure(Path file, Map<String, String> o) {
        String engine = o.getOrDefault("engine", "sqlserver");
        Map<String, String> v = new LinkedHashMap<>();
        v.put("engine", engine);
        if (!engine.equals("h2")) {
            if (!o.containsKey("host") || !o.containsKey("user")) { System.err.println("Give --host and --user (and --setup-user for the account that creates the schema)."); return 1; }
            v.put("host", o.get("host"));
            v.put("port", o.getOrDefault("port", engine.equals("postgresql") ? "5432" : "1433"));
            if (o.containsKey("instance")) v.put("instance", o.get("instance"));
            v.put("database", name(o.getOrDefault("database", "ooa_command_center"), "The database name"));
            v.put("user", name(o.get("user"), "The user"));
            if (o.containsKey("setup-user")) v.put("setup.user", name(o.get("setup-user"), "The setup user"));
            v.put("tls", o.getOrDefault("tls", "required"));
            if (o.containsKey("tls-ca")) v.put("tls.ca", o.get("tls-ca"));
            if (o.containsKey("trust-any-certificate")) v.put("tls.trustAnyCertificate", "true");
            v.put("pool.max", o.getOrDefault("pool-max", "20"));
            v.put("pool.timeoutSeconds", o.getOrDefault("timeout-seconds", "30"));
        }
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>(List.of("# One Command Center database connection (ooa db configure). Passwords: ooa db set-password [--setup]."));
            for (var e : v.entrySet()) lines.add(e.getKey() + "=" + e.getValue());
            Files.write(file, lines);
        } catch (java.io.IOException e) { throw new OoaException("CR.DB_CONFIG", "Cannot write " + file + ": " + e.getMessage()); }
        System.out.println("Wrote " + file);
        if (!engine.equals("h2")) System.out.println("Next: ooa db set-password, ooa db set-password --setup, then ooa db init.");
        return 0;
    }

    static int setPassword(Path file, Path data, Map<String, String> o) {
        boolean setup = o.containsKey("setup");
        String pw;
        if (o.containsKey("from-env")) {
            pw = System.getenv(o.get("from-env"));
            if (pw == null) { System.err.println("The environment variable " + o.get("from-env") + " is not set."); return 1; }
        } else if (System.console() != null) {
            char[] c = System.console().readPassword("Password for the %s account: ", setup ? "setup" : "runtime");
            pw = c == null ? "" : new String(c);
        } else {
            pw = new Scanner(System.in).nextLine();
        }
        if (pw.isEmpty()) { System.err.println("No password given."); return 1; }
        DbConfig.storePassword(file, new Vault(data), setup, pw);
        System.out.println("Stored the " + (setup ? "setup" : "runtime") + " password in " + file + ", encrypted with the vault master key.");
        return 0;
    }

    static int test(DbConfig cfg, Path data) {
        System.out.println("Configuration: " + cfg.file);
        System.out.println("Database:      " + cfg.label());
        Vault vault = new Vault(data);
        try (Db db = Db.open(cfg, data, vault, false)) {
            Map<String, Object> i = db.info();
            System.out.println("Connected:     " + i.get("engine") + " " + i.get("version") + (i.get("edition") == null ? "" : " (" + i.get("edition") + ")"));
            if (i.get("account") != null) System.out.println("Account:       " + i.get("account"));
            if (i.get("collation") != null) System.out.println("Collation:     " + i.get("collation"));
            System.out.println("Encrypted:     " + (Boolean.TRUE.equals(i.get("encrypted")) ? "yes" : "no"));
            System.out.println("Schema:        version " + i.get("schema_version") + " of " + i.get("schema_latest")
                + ((Integer) i.get("schema_version") == 0 ? "  (empty: run ooa db init)" : (Integer) i.get("schema_version") < Db.LATEST ? "  (run ooa db migrate)" : "  (current)"));
            if (db.sqlServer) {
                Map<String, Object> r = db.one("SELECT IS_ROLEMEMBER('db_datareader') AS rd, IS_ROLEMEMBER('db_datawriter') AS wr, IS_ROLEMEMBER('db_owner') AS own, " +
                    "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CREATE TABLE') AS ddl");
                boolean rd = one(r.get("rd")) || one(r.get("own")), wr = one(r.get("wr")) || one(r.get("own")), ddl = one(r.get("ddl"));
                System.out.println("Rights:        read " + yes(rd) + ", write " + yes(wr) + ", change tables " + yes(ddl)
                    + (rd && wr && !ddl ? "  (data rights only, as recommended)" : ddl ? "  (more than the server needs; use a data-only account)" : "  (missing rights: run ooa db init)"));
            }
            if (!Boolean.TRUE.equals(i.get("encrypted")) && !cfg.engine().equals("h2")) System.out.println("Warning:       the connection is not encrypted; set tls=required.");
            return 0;
        }
    }

    private static boolean one(Object v) { return v instanceof Number n && n.intValue() == 1; }
    private static String yes(boolean b) { return b ? "yes" : "no"; }

    static int init(DbConfig cfg, Path data) {
        if (cfg.engine().equals("h2")) { System.out.println("The embedded database needs no setup; it is created at the first start."); return 0; }
        Vault vault = new Vault(data);
        String db = name(cfg.database(), "The database name"), runtime = name(cfg.get("user"), "The user");
        String setupUser = cfg.get("setup.user");
        if (setupUser.isEmpty()) { System.err.println("Add setup.user to " + cfg.file + " and run: ooa db set-password --setup"); return 1; }
        try (Connection c = DriverManager.getConnection(cfg.url(data, true), setupUser, cfg.password(vault, true)); Statement s = c.createStatement()) {
            if (cfg.engine().equals("sqlserver")) {
                try (ResultSet rs = s.executeQuery("SELECT DB_ID('" + db + "')")) { rs.next(); if (rs.getObject(1) == null) {
                    s.execute("CREATE DATABASE [" + db + "]");
                    s.execute("ALTER DATABASE [" + db + "] SET READ_COMMITTED_SNAPSHOT ON");
                    System.out.println("Created database " + db + ".");
                } else System.out.println("Database " + db + " exists."); }
            } else {
                try (ResultSet rs = s.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + db + "'")) {
                    if (!rs.next()) { s.execute("CREATE DATABASE \"" + db + "\""); System.out.println("Created database " + db + "."); }
                    else System.out.println("Database " + db + " exists.");
                }
            }
        } catch (SQLException e) {
            throw new OoaException("CR.DB_SETUP", "Cannot create the database with " + setupUser + ": " + e.getMessage(), "The setup account needs the right to create databases (dbcreator on SQL Server)");
        }
        int r = migrate(cfg, data);
        if (r == 0) grantRuntime(cfg, data, vault, runtime);
        return r;
    }

    static int migrate(DbConfig cfg, Path data) {
        try (Db db = Db.open(cfg, data, new Vault(data), true)) {
            int before = db.schemaVersion();
            db.migrate();
            System.out.println(before == Db.LATEST ? "Schema is current (version " + Db.LATEST + ")." : "Schema upgraded from version " + before + " to " + Db.LATEST + ".");
        }
        if (cfg.engine().equals("postgresql")) grantRuntime(cfg, data, new Vault(data), name(cfg.get("user"), "The user"));
        return 0;
    }

    /** Gives the runtime account read and write rights on the OOA database, and nothing more. */
    private static void grantRuntime(DbConfig cfg, Path data, Vault vault, String runtime) {
        if (runtime.equals(cfg.get("setup.user"))) { System.out.println("Note: the runtime and setup accounts are the same; use a separate data-only account in production."); return; }
        try (Db db = Db.open(cfg, data, vault, true)) {
            db.withConnection(c -> {
                try (Statement s = c.createStatement()) {
                    if (db.sqlServer) {
                        s.execute("IF USER_ID('" + runtime + "') IS NULL CREATE USER [" + runtime + "] FOR LOGIN [" + runtime + "]");
                        s.execute("ALTER ROLE db_datareader ADD MEMBER [" + runtime + "]");
                        s.execute("ALTER ROLE db_datawriter ADD MEMBER [" + runtime + "]");
                    } else {
                        s.execute("GRANT USAGE ON SCHEMA public TO \"" + runtime + "\"");
                        s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"" + runtime + "\"");
                    }
                } catch (SQLException e) {
                    throw new OoaException("CR.DB_SETUP", "Cannot give " + runtime + " access: " + e.getMessage(), "Create the login " + runtime + " on the server first (a DBA task)");
                }
                return null;
            });
        }
        System.out.println("Gave " + runtime + " read and write rights (no rights to change tables).");
    }

    static int script(String engine) {
        System.out.println("-- One Command Center schema, version " + Db.LATEST + ", for " + engine + ". Run it in the OOA database with an account that may create tables.");
        for (String s : Db.script(engine)) System.out.println(s + ";");
        return 0;
    }

    /**
     * Copies every table of an embedded H2 Command Center into the configured database, in one transaction, then
     * compares the row counts. The vault values stay encrypted, so the server must keep using the same master key.
     */
    static int copy(DbConfig cfg, Path data, Map<String, String> o) {
        if (!o.containsKey("from")) { System.err.println("Give --from DIR: the data folder that holds controlroom.mv.db."); return 1; }
        Path from = Path.of(o.get("from"));
        if (!Files.exists(from.resolve("controlroom.mv.db"))) { System.err.println("No controlroom.mv.db in " + from + "."); return 1; }
        if (cfg.engine().equals("h2")) { System.err.println("The configured database is the embedded one; set engine=sqlserver first."); return 1; }
        if (!from.toAbsolutePath().normalize().equals(data.toAbsolutePath().normalize()) && Files.exists(from.resolve("master.key")) && System.getenv("OOA_MASTER_KEY") == null)
            System.out.println("Note: vault values are encrypted with " + from.resolve("master.key") + "; the server must use that key (copy it to " + data + " or set OOA_MASTER_KEY).");
        try (Db source = Db.h2File(from); Db target = Db.open(cfg, data, new Vault(data), true)) {
            source.migrate();
            target.migrate();
            List<String> tables = Db.tables();
            long existing = tables.stream().mapToLong(t -> count(target, t)).sum();
            if (existing > 0 && !o.containsKey("replace")) {
                System.err.println("The target database already holds " + existing + " rows. Run again with --replace to delete them first.");
                return 1;
            }
            String q = target.sqlServer ? "[%s]" : "\"%s\"";
            target.tx(() -> {
                if (existing > 0) for (int i = tables.size() - 1; i >= 0; i--) target.update("DELETE FROM " + tables.get(i));
                for (String t : tables) copyTable(source, target, t, q);
                return null;
            });
            System.out.printf("%-26s %10s %10s%n", "Table", "Source", "Copied");
            boolean ok = true;
            for (String t : tables) {
                long a = count(source, t), b = count(target, t);
                ok &= a == b;
                if (a > 0 || b > 0) System.out.printf("%-26s %10d %10d%s%n", t, a, b, a == b ? "" : "  MISMATCH");
            }
            System.out.println(ok ? "All tables copied; row counts match." : "Row counts differ; nothing should use the target until this is fixed.");
            return ok ? 0 : 2;
        }
    }

    private static long count(Db db, String table) { return ((Number) db.one("SELECT COUNT(*) AS n FROM " + table).get("n")).longValue(); }

    private static void copyTable(Db source, Db target, String table, String quote) {
        source.withConnection(sc -> {
            try (Statement s = sc.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                StringJoiner cols = new StringJoiner(", "), marks = new StringJoiner(", ");
                for (int i = 1; i <= n; i++) { cols.add(String.format(quote, md.getColumnLabel(i).toLowerCase(Locale.ROOT))); marks.add("?"); }
                String insert = "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
                target.withConnection(tc -> {
                    try (PreparedStatement ps = tc.prepareStatement(insert)) {
                        int batch = 0;
                        while (rs.next()) {
                            for (int i = 1; i <= n; i++) {
                                Object v = rs.getObject(i);
                                if (v instanceof Clob c) v = c.getSubString(1, (int) c.length());
                                if (v instanceof Timestamp t) ps.setTimestamp(i, t); else ps.setObject(i, v);
                            }
                            ps.addBatch();
                            if (++batch % 500 == 0) ps.executeBatch();
                        }
                        ps.executeBatch();
                    } catch (SQLException e) { throw new OoaException("CR.DB_COPY", "Copying " + table + " failed: " + e.getMessage()); }
                    return null;
                });
            } catch (SQLException e) { throw new OoaException("CR.DB_COPY", "Reading " + table + " failed: " + e.getMessage()); }
            return null;
        });
    }
}
