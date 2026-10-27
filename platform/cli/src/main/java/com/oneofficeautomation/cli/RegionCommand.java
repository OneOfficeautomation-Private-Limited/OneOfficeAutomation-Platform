package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/**
 * The SaaS service (C3, C4), for its operators:
 * <pre>
 *   ooa region start [--data DIR] [--host H] [--port N]     a region: every tenant on one port (DIR/config/ooa-region.properties)
 *   ooa region token                                        a new operator or directory token, and the SHA-256 for the settings
 *   ooa region tenant list|create|seats|suspend|resume|delete --url https://REGION-DOMAIN --token-env VAR ...
 *   ooa directory start [--data DIR] [--host H] [--port N]  the global directory (DIR/config/ooa-directory.properties)
 * </pre>
 * See docs/SAAS.md.
 */
final class RegionCommand {
    private RegionCommand() { }

    static int start(Map<String, String> o) throws Exception {
        Path data = Ooa.dataDir(o);
        RegionConfig cfg = RegionConfig.load(RegionConfig.defaultFile(data));
        Db base = cfg.openDatabase(data);
        Tenants tenants = new Tenants(base, data, new Vault(data), cfg.region(), cfg.domain(), FirstPartyPackages::registry, Ooa.schemaValidator());
        tenants.serverless = cfg.serverless();
        tenants.directory = cfg.directoryClient();
        TenantHost host = new TenantHost(tenants, o.getOrDefault("host", "0.0.0.0"), Integer.parseInt(o.getOrDefault("port", "8720")), cfg.operatorTokenSha256());
        host.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { host.stop(); tenants.close(); base.close(); }));
        System.out.println("ONEOFFICEAUTOMATION region '" + cfg.region() + "' is running on port " + host.port() + ".");
        System.out.println("  Tenants:   https://NAME." + cfg.domain() + "/  (" + tenants.opened().size() + " open)");
        System.out.println("  Database:  " + (cfg.dbUrl().isEmpty() ? "H2 in " + data + " (tests only; set db.url to PostgreSQL)" : cfg.dbUrl()));
        System.out.println("  Operators: " + (cfg.operatorTokenSha256().isEmpty() ? "tenant API off (set operator.token_sha256)" : "https://" + cfg.domain() + "/api/operator/tenants"));
        System.out.println("  Directory: " + (cfg.directoryUrl().isEmpty() ? "none (a single region)" : cfg.directoryUrl()));
        System.out.println("  Serverless runs: " + (cfg.serverless() ? "allowed" : "off"));
        System.out.println("  Press Ctrl+C to stop.");
        Thread.currentThread().join();
        return 0;
    }

    /** A random token for operators or a region's directory access, with the SHA-256 that goes into the settings. */
    static int token() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String token = "ooaop_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        System.out.println("Token (shown once; keep it in your secret store): " + token);
        System.out.println("SHA-256 for the settings file:                    " + sha256(token));
        return 0;
    }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** list, create, suspend, resume, delete: calls the region's operator API. */
    @SuppressWarnings("unchecked")
    static int tenant(String verb, Map<String, String> o, List<String> pos) throws Exception {
        String base = o.get("url").replaceAll("/+$", "");
        String token = System.getenv(o.get("token-env"));
        if (token == null || token.isBlank()) throw new OoaException("SAAS.TOKEN", "Set " + o.get("token-env") + " to the operator token");
        Object r = switch (verb) {
            case "list" -> call(base, "GET", "/api/operator/tenants", null, token);
            case "create" -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("slug", o.get("slug")); b.put("name", o.get("name"));
                if (o.containsKey("admin-email")) b.put("admin_email", o.get("admin-email"));
                b.put("seats", seats(o.getOrDefault("seats", "")));
                yield call(base, "POST", "/api/operator/tenants", JsonLite.write(b), token);
            }
            case "suspend", "resume" -> call(base, "POST", "/api/operator/tenants/" + pos.get(0) + "/" + verb, "{}", token);
            case "seats" -> call(base, "POST", "/api/operator/tenants/" + pos.get(0) + "/seats", JsonLite.write(Map.of("seats", seats(o.getOrDefault("seats", "")))), token);
            case "delete" -> call(base, "DELETE", "/api/operator/tenants/" + pos.get(0), null, token);
            default -> throw new IllegalArgumentException(verb);
        };
        if (verb.equals("list")) {
            List<Object> list = (List<Object>) ((Map<String, Object>) r).get("tenants");
            System.out.printf("%-20s %-30s %-10s %s%n", "NAME", "COMPANY", "STATUS", "CREATED");
            for (Object x : list) {
                Map<String, Object> t = (Map<String, Object>) x;
                System.out.printf("%-20s %-30s %-10s %s%n", t.get("slug"), t.get("name"), t.get("status"), String.valueOf(t.get("created")).replace("T", " "));
            }
            System.out.println(list.size() + " tenant(s) in region " + ((Map<String, Object>) r).get("region"));
        } else if (verb.equals("create")) {
            Map<String, Object> t = (Map<String, Object>) r;
            System.out.println("Tenant '" + t.get("slug") + "' is ready: " + t.get("url"));
            System.out.println();
            System.out.println("  First sign-in: admin  with the one-time password  " + t.get("admin_password"));
            System.out.println("  Send it to the customer's administrator apart from the address. It is shown only once.");
        } else if (verb.equals("seats")) {
            Object s = ((Map<String, Object>) r).get("seats");
            System.out.println("Seats of " + pos.get(0) + ": " + (s instanceof Map<?, ?> m && !m.isEmpty() ? s : "no limit"));
        } else System.out.println(verb.equals("delete") ? "Deleted " + pos.get(0) + "." : "Tenant " + pos.get(0) + " is " + ((Map<String, Object>) r).get("status") + ".");
        return 0;
    }

    /** "developer=2,unattended_runner=5" as seat counts. */
    static Map<String, Object> seats(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String part : s.split(",")) {
            if (part.isBlank()) continue;
            String[] kv = part.split("=", 2);
            if (kv.length != 2 || !kv[1].trim().matches("\\d{1,6}")) throw new OoaException("CLI.BAD_INPUT", "--seats is KIND=COUNT,KIND=COUNT, such as developer=2,unattended_runner=5");
            m.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
        }
        return m;
    }

    private static Object call(String base, String method, String path, String body, String token) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
        Object json;
        try { json = JsonLite.parse(r.body()); } catch (RuntimeException e) { throw new OoaException("SAAS.API", "The region answered " + r.statusCode() + " without JSON"); }
        if (r.statusCode() / 100 != 2) {
            Object err = json instanceof Map<?, ?> m ? m.get("error") : null;
            Object msg = err instanceof Map<?, ?> e ? e.get("message") : r.body();
            throw new OoaException("SAAS.API", "The region refused: " + msg + " (HTTP " + r.statusCode() + ")");
        }
        return json;
    }

    /** The global directory: DIR/config/ooa-directory.properties lists the regions (region.NAME.domain, region.NAME.token_sha256). */
    static int directory(Map<String, String> o) throws Exception {
        Path data = Ooa.dataDir(o);
        Path file = data.resolve("config").resolve("ooa-directory.properties");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        catch (NoSuchFileException e) { throw new OoaException("SAAS.CONFIG", "No directory settings at " + file, "Write it as docs/SAAS.md shows."); }
        List<RegionDirectory.Region> regions = new ArrayList<>();
        for (String k : new TreeSet<>(p.stringPropertyNames()))
            if (k.matches("region\\.[a-z0-9-]+\\.domain")) {
                String name = k.split("\\.")[1];
                String sha = p.getProperty("region." + name + ".token_sha256", "").trim().toLowerCase(Locale.ROOT);
                if (!sha.matches("[0-9a-f]{64}")) throw new OoaException("SAAS.CONFIG", "region." + name + ".token_sha256 is 64 hex digits (ooa region token)");
                regions.add(new RegionDirectory.Region(name, p.getProperty(k).trim().toLowerCase(Locale.ROOT), sha));
            }
        if (regions.isEmpty()) throw new OoaException("SAAS.CONFIG", "No regions in " + file);
        String url = p.getProperty("db.url", "").trim();
        String pw = p.getProperty("db.password_env", "").isBlank() ? "" : System.getenv(p.getProperty("db.password_env").trim());
        Db db = url.isEmpty() ? Db.h2File(data) : new Db(url, p.getProperty("db.user", "").trim(), pw == null ? "" : pw, 4);
        RegionDirectory dir = new RegionDirectory(db, regions);
        dir.start(o.getOrDefault("host", "0.0.0.0"), Integer.parseInt(o.getOrDefault("port", "8720")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { dir.stop(); db.close(); }));
        System.out.println("ONEOFFICEAUTOMATION directory is running on port " + dir.port() + " for regions "
            + String.join(", ", regions.stream().map(r -> r.name() + " (" + r.domain() + ")").toList()) + ".");
        System.out.println("  Press Ctrl+C to stop.");
        Thread.currentThread().join();
        return 0;
    }
}
