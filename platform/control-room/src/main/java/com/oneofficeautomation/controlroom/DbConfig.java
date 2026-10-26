package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.*;

/**
 * The database connection of the Command Center, read from {@code <data>/config/ooa-db.properties}:
 * <pre>
 * engine=sqlserver            h2 | sqlserver | postgresql
 * host=dbserver01             port=1433    instance=SQLEXPRESS (optional)    database=ooa_command_center
 * user=ooa_app                password=enc:...  (or env:VARIABLE)        the account the server runs with
 * setup.user=ooa_setup        setup.password=enc:...                     creates and upgrades the schema (ooa db)
 * tls=required                off | required
 * tls.ca=C:\certs\db-ca.crt   the CA chain the server certificate must match (default: Java's trusted CAs)
 * tls.trustAnyCertificate=false   true only for a test server with a self-signed certificate
 * pool.max=20                 pool.timeoutSeconds=30
 * </pre>
 * Passwords are never stored in clear text: {@code ooa db set-password} encrypts them with the vault master key.
 */
public final class DbConfig {
    public final Path file;
    private final Properties p = new Properties();

    private DbConfig(Path file) { this.file = file; }

    public static Path defaultFile(Path dataDir) { return dataDir.resolve("config").resolve("ooa-db.properties"); }

    public static DbConfig load(Path file) {
        DbConfig c = new DbConfig(file);
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { c.p.load(r); }
        catch (IOException e) { throw new OoaException("CR.DB_CONFIG", "Cannot read " + file + ": " + e.getMessage()); }
        String engine = c.engine();
        if (!List.of("h2", "sqlserver", "postgresql").contains(engine))
            throw new OoaException("CR.DB_CONFIG", "engine must be h2, sqlserver or postgresql, not \"" + engine + "\"", "Edit " + file);
        if (!engine.equals("h2")) for (String k : List.of("host", "database", "user"))
            if (c.get(k).isEmpty()) throw new OoaException("CR.DB_CONFIG", "Missing " + k + " in " + file);
        String tls = c.get("tls", "required");
        if (!tls.equals("off") && !tls.equals("required")) throw new OoaException("CR.DB_CONFIG", "tls must be off or required", "Edit " + file);
        return c;
    }

    public String get(String key) { return get(key, ""); }
    public String get(String key, String dflt) { String v = p.getProperty(key); return v == null || v.isBlank() ? dflt : v.trim(); }
    public String engine() { return get("engine", "h2").toLowerCase(Locale.ROOT); }
    public String database() { return get("database", "ooa_command_center"); }
    public int poolMax() { return Integer.parseInt(get("pool.max", "20")); }
    public int timeoutSeconds() { return Integer.parseInt(get("pool.timeoutSeconds", "30")); }
    public boolean tls() { return get("tls", "required").equals("required"); }
    public boolean hasSetupAccount() { return !get("setup.user").isEmpty(); }

    /** The JDBC address of the OOA database (or, with master, of the server's master database for creating it). */
    public String url(Path dataDir, boolean master) {
        return switch (engine()) {
            case "h2" -> "jdbc:h2:file:" + dataDir.resolve("controlroom").toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=FALSE";
            case "postgresql" -> "jdbc:postgresql://" + get("host") + ":" + get("port", "5432") + "/" + (master ? "postgres" : database())
                + "?ApplicationName=OOA%20Command%20Center&connectTimeout=" + timeoutSeconds()
                + (tls() ? "&sslmode=verify-full" + (get("tls.ca").isEmpty() ? "" : "&sslrootcert=" + get("tls.ca")) : "&sslmode=disable");
            default -> {
                StringBuilder u = new StringBuilder("jdbc:sqlserver://").append(get("host")).append(':').append(get("port", "1433"));
                if (!get("instance").isEmpty()) u.append(";instanceName=").append(get("instance"));
                u.append(";databaseName=").append(master ? "master" : database())
                    .append(";applicationName=OOA Command Center;sendTimeAsDatetime=false;loginTimeout=").append(timeoutSeconds())
                    .append(";encrypt=").append(tls());
                if (tls() && Boolean.parseBoolean(get("tls.trustAnyCertificate", "false"))) u.append(";trustServerCertificate=true");
                else if (tls() && !get("tls.ca").isEmpty()) {
                    Path store = trustStore(dataDir);
                    u.append(";trustServerCertificate=false;trustStore=").append(store).append(";trustStoreType=PKCS12;trustStorePassword=ooa-truststore");
                }
                yield u.toString();
            }
        };
    }

    /** The runtime account's password (or the setup account's), decrypted. */
    public String password(Vault vault, boolean setup) {
        String key = setup ? "setup.password" : "password";
        String v = get(key);
        if (v.isEmpty()) return "";
        if (v.startsWith("env:")) {
            String e = System.getenv(v.substring(4));
            if (e == null) throw new OoaException("CR.DB_CONFIG", "The environment variable " + v.substring(4) + " (" + key + ") is not set");
            return e;
        }
        if (v.startsWith("enc:")) return vault.decrypt(v.substring(4), "ooa-db:" + key);
        throw new OoaException("CR.DB_CONFIG", key + " is stored in clear text in " + file, "Run: ooa db set-password" + (setup ? " --setup" : ""));
    }

    /** Encrypts a password with the vault key and writes it into the file, keeping every other line as it is. */
    public static void storePassword(Path file, Vault vault, boolean setup, String plain) {
        String key = setup ? "setup.password" : "password";
        setValue(file, key, "enc:" + vault.encrypt(plain, "ooa-db:" + key));
    }

    static void setValue(Path file, String key, String value) {
        value = value.replace("\\", "\\\\");   // properties files treat a backslash as an escape (Windows paths)
        try {
            List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
            boolean done = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith(key + "=") || t.startsWith(key + " =")) { lines.set(i, key + "=" + value); done = true; }
            }
            if (!done) lines.add(key + "=" + value);
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new OoaException("CR.DB_CONFIG", "Cannot write " + file + ": " + e.getMessage()); }
    }

    /** A PKCS12 trust store made from the CA file, so the driver checks the server certificate against that chain. */
    private Path trustStore(Path dataDir) {
        Path ca = Path.of(get("tls.ca"));
        Path store = file.getParent().resolve("db-truststore.p12");
        try (InputStream in = Files.newInputStream(ca)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) ks.setCertificateEntry("ca" + i++, c);
            if (i == 0) throw new OoaException("CR.DB_CONFIG", "No certificate found in " + ca);
            try (OutputStream out = Files.newOutputStream(store)) { ks.store(out, "ooa-truststore".toCharArray()); }
            return store.toAbsolutePath();
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("CR.DB_CONFIG", "Cannot read the CA file " + ca + ": " + e.getMessage()); }
    }

    /** A short description for logs and the console, without any secret. */
    public String label() {
        if (engine().equals("h2")) return "embedded H2";
        return (engine().equals("sqlserver") ? "SQL Server" : "PostgreSQL") + " " + get("host") + (get("instance").isEmpty() ? ":" + get("port", engine().equals("sqlserver") ? "1433" : "5432") : "\\" + get("instance"))
            + " / " + database() + (tls() ? " (TLS)" : " (no TLS)");
    }
}
