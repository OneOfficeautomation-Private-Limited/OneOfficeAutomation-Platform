package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;

import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * {@code ooa check}: whether this computer is ready for a Command Center (I10), before installing or when something is
 * wrong. Each line is PASS, WARN or FAIL with what to do; the exit code is the number of FAILs. Read-only: it changes
 * nothing. Options: [--data DIR] [--port N].
 */
final class CheckCommand {
    private CheckCommand() { }

    record Result(String level, String what, String detail) {}

    static int run(Map<String, String> o) {
        List<Result> out = checks(Ooa.dataDir(o), Integer.parseInt(o.getOrDefault("port", "8720")));
        int fails = 0;
        for (Result r : out) {
            System.out.printf("%-5s %-26s %s%n", r.level(), r.what(), r.detail());
            if (r.level().equals("FAIL")) fails++;
        }
        System.out.println(fails == 0 ? "Ready." : fails + " problem(s) to fix first.");
        return fails;
    }

    /**
     * What a second (disaster recovery) site needs besides the database (I9): the same vault key, which must open the
     * stored credentials, and every repository file and custom toolkit jar in its folder.
     */
    static List<Result> siteChecks(Db db, Path data, int schema) {
        List<Result> r = new ArrayList<>();
        List<Map<String, Object>> creds = db.query("SELECT locker, name, attribute, ciphertext FROM credentials");
        if (creds.isEmpty()) r.add(new Result("PASS", "Vault key and database", "no stored credentials to check it against"));
        else {
            Map<String, Object> c = creds.get(0);
            try {
                new Vault(data).decrypt((String) c.get("ciphertext"), c.get("locker") + "/" + c.get("name") + "/" + c.get("attribute"));
                r.add(new Result("PASS", "Vault key and database", "the key opens the stored credentials"));
            } catch (RuntimeException e) {
                r.add(new Result("FAIL", "Vault key and database", "the key does not open the stored credentials: use the main site's OOA_MASTER_KEY"));
            }
        }
        String folder = "";
        Map<String, Object> s = db.one("SELECT json_value FROM settings WHERE name = 'repo_files_folder'");
        if (s != null && com.oneofficeautomation.sdk.JsonLite.parse((String) s.get("json_value")) instanceof String f) folder = f.trim();
        Path store = folder.isEmpty() ? data.resolve("repo-files") : Path.of(folder);
        List<Path> files = new ArrayList<>();
        for (Map<String, Object> f : db.query("SELECT sha256 FROM repo_files")) files.add(store.resolve((String) f.get("sha256")));
        if (schema >= 23) for (Map<String, Object> t : db.query("SELECT sha256 FROM custom_toolkits")) files.add(store.resolve("toolkits").resolve(t.get("sha256") + ".jar"));
        long missing = files.stream().filter(f -> !Files.isRegularFile(f)).count();
        r.add(new Result(missing == 0 ? "PASS" : "FAIL", "Repository files", files.isEmpty() ? "none yet" : missing == 0 ? files.size() + " files, all in " + store
            : missing + " of " + files.size() + " missing in " + store + ": copy the folder from the main site (or point repo_files_folder at the shared copy)"));
        return r;
    }

    static List<Result> checks(Path data, int port) {
        List<Result> r = new ArrayList<>();
        String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
        boolean windows = os.toLowerCase(Locale.ROOT).contains("win");
        r.add(new Result(windows ? "PASS" : "WARN", "Operating system", os + (windows ? "" : ": supported for the Command Center, but runners that use the screen need Windows")));
        r.add(new Result(System.getProperty("os.arch").contains("64") ? "PASS" : "FAIL", "64-bit", System.getProperty("os.arch")));
        r.add(new Result("PASS", "Java", System.getProperty("java.version") + " in " + System.getProperty("java.home")));
        long mb = 0;
        try { mb = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() / (1024 * 1024); } catch (Throwable ignored) { }
        r.add(new Result(mb == 0 ? "WARN" : mb >= 8192 ? "PASS" : mb >= 4096 ? "WARN" : "FAIL", "Memory", mb == 0 ? "unknown" : mb / 1024 + " GB" + (mb < 8192 ? " (8 GB or more recommended)" : "")));
        r.add(new Result(Runtime.getRuntime().availableProcessors() >= 4 ? "PASS" : "WARN", "Processors", Runtime.getRuntime().availableProcessors() + (Runtime.getRuntime().availableProcessors() < 4 ? " (4 or more recommended)" : "")));
        // the data folder: it must exist or be creatable, be writable, and have room
        Path probe = data;
        while (probe != null && !Files.exists(probe)) probe = probe.getParent();
        if (probe == null) r.add(new Result("FAIL", "Data folder", data + " cannot be created"));
        else {
            boolean writable = Files.isWritable(probe);
            long free = probe.toFile().getUsableSpace() / (1024 * 1024);
            r.add(new Result(writable ? "PASS" : "FAIL", "Data folder", data + (writable ? "" : ": no write access for " + System.getProperty("user.name"))));
            r.add(new Result(free >= 2048 ? "PASS" : free >= 200 ? "WARN" : "FAIL", "Free disk space", free / 1024 + " GB free" + (free < 2048 ? " (2 GB or more recommended)" : "")));
        }
        // the port: free, or already used by a Command Center
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress(port));
            r.add(new Result("PASS", "Port " + port, "free"));
        } catch (Exception e) {
            boolean ours = false;
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/health").toURL().openConnection();
                c.setConnectTimeout(2000); c.setReadTimeout(2000);
                ours = c.getResponseCode() == 200;
            } catch (Exception ignored) { }
            r.add(new Result(ours ? "PASS" : "FAIL", "Port " + port, ours ? "a Command Center already answers on it" : "used by another program: stop it or choose another port (--port)"));
        }
        // the database configuration, when there is one
        Path cfgFile = DbConfig.defaultFile(data);
        if (!Files.exists(cfgFile)) r.add(new Result("WARN", "Database", "built-in evaluation database (no " + cfgFile + "); licensed installs use SQL Server: ooa db configure"));
        else {
            try (Db db = Db.open(DbConfig.load(cfgFile), data, new Vault(data), false)) {
                Map<String, Object> i = db.info();
                int sv = ((Number) i.get("schema_version")).intValue();
                r.add(new Result(sv == 0 ? "WARN" : "PASS", "Database", i.get("engine") + " " + i.get("version") + ", schema " + sv + " of " + Db.LATEST
                    + (sv == 0 ? " (empty: ooa db init)" : sv < Db.LATEST ? " (upgraded at start, or ooa db migrate)" : "")));
                r.add(new Result(Boolean.TRUE.equals(i.get("encrypted")) ? "PASS" : "WARN", "Database encryption", Boolean.TRUE.equals(i.get("encrypted")) ? "the connection is encrypted" : "not encrypted: set tls=required"));
                if (sv > 0) r.addAll(siteChecks(db, data, sv));
            } catch (Exception e) { r.add(new Result("FAIL", "Database", "cannot connect: " + e.getMessage())); }
        }
        // HTTPS, when set up
        Path tls = TlsConfig.defaultFile(data);
        if (!Files.exists(tls)) r.add(new Result("WARN", "HTTPS", "off: other machines would use plain HTTP (ooa tls)"));
        else {
            try {
                Map<String, Object> d = TlsConfig.load(tls).describe(new Vault(data));
                Instant until = Instant.parse(String.valueOf(d.get("valid_until")));
                long days = java.time.Duration.between(Instant.now(), until).toDays();
                r.add(new Result(days < 0 ? "FAIL" : days < 30 ? "WARN" : "PASS", "HTTPS certificate", d.get("subject") + ", valid " + days + " more days" + (Boolean.TRUE.equals(d.get("self_signed")) ? " (self-signed)" : "")));
            } catch (Exception e) { r.add(new Result("FAIL", "HTTPS certificate", e.getMessage())); }
        }
        // the vault key
        r.add(new Result(System.getenv("OOA_MASTER_KEY") != null ? "PASS" : "WARN", "Vault key", System.getenv("OOA_MASTER_KEY") != null ? "from OOA_MASTER_KEY"
            : "key file in the data folder; for production set OOA_MASTER_KEY and keep a copy apart from backups"));
        return r;
    }
}
