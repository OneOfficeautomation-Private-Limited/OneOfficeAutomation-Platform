package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/**
 * A SaaS region's settings, DATA/config/ooa-region.properties (C3, C4). Secrets are never in the file, only the names
 * of the environment variables holding them; the operator token is kept as its SHA-256.
 * <pre>
 *   region=in                                  short name: in, us, eu
 *   domain=in.example.com                      tenants are acme.in.example.com (wildcard DNS and certificate)
 *   db.url=jdbc:postgresql://db:5432/ooa       the region's database; without it, H2 in the data folder (tests only)
 *   db.user=ooa_region                         an account that may create schemas
 *   db.password_env=OOA_DB_PASSWORD
 *   operator.token_sha256=...                  the operators' token for the tenant API (ooa region token)
 *   directory.url=https://ooa.example.com      the global directory, with several regions
 *   directory.token_env=OOA_DIRECTORY_TOKEN    this region's token there
 *   serverless=false                           true once the region's network blocks private addresses for the server
 * </pre>
 * The vault master key of the region (OOA_MASTER_KEY, or a key source) encrypts each tenant's own key.
 * <p>
 * In a container the same settings come from the environment when there is no file: OOA_REGION, OOA_DOMAIN,
 * OOA_DB_URL, OOA_DB_USER, OOA_DB_PASSWORD (the variable itself), OOA_OPERATOR_TOKEN_SHA256, OOA_DIRECTORY_URL,
 * OOA_DIRECTORY_TOKEN (the variable itself) and OOA_SERVERLESS.
 */
public record RegionConfig(String region, String domain, String dbUrl, String dbUser, String dbPasswordEnv, String operatorTokenSha256,
                           String directoryUrl, String directoryTokenEnv, boolean serverless) {

    public static Path defaultFile(Path data) { return data.resolve("config").resolve("ooa-region.properties"); }

    public static RegionConfig load(Path file) { return load(file, System::getenv); }

    static RegionConfig load(Path file, Function<String, String> env) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        catch (NoSuchFileException e) {
            if (env.apply("OOA_REGION") == null) throw new OoaException("SAAS.CONFIG", "No region settings at " + file + " and no OOA_REGION", "Write it as docs/infrastructure/09-saas-and-multi-region-operations.md shows.");
            String[][] names = { {"region", "OOA_REGION"}, {"domain", "OOA_DOMAIN"}, {"db.url", "OOA_DB_URL"}, {"db.user", "OOA_DB_USER"},
                {"operator.token_sha256", "OOA_OPERATOR_TOKEN_SHA256"}, {"directory.url", "OOA_DIRECTORY_URL"}, {"serverless", "OOA_SERVERLESS"} };
            for (String[] n : names) if (env.apply(n[1]) != null) p.setProperty(n[0], env.apply(n[1]));
            if (env.apply("OOA_DB_PASSWORD") != null) p.setProperty("db.password_env", "OOA_DB_PASSWORD");
            if (env.apply("OOA_DIRECTORY_TOKEN") != null) p.setProperty("directory.token_env", "OOA_DIRECTORY_TOKEN");
        }
        catch (IOException e) { throw new OoaException("SAAS.CONFIG", "Cannot read " + file + ": " + e.getMessage()); }
        Function<String, String> get = k -> p.getProperty(k, "").trim();
        String region = get.apply("region").toLowerCase(Locale.ROOT), domain = get.apply("domain").toLowerCase(Locale.ROOT);
        if (!region.matches("^[a-z][a-z0-9-]{0,19}$")) throw new OoaException("SAAS.CONFIG", "region in " + file + " is a short name such as in, us or eu");
        if (!domain.matches("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")) throw new OoaException("SAAS.CONFIG", "domain in " + file + " is a host name such as in.example.com");
        String url = get.apply("db.url");
        if (!url.isEmpty() && !url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:h2:"))
            throw new OoaException("SAAS.CONFIG", "A region's database is PostgreSQL (db.url=jdbc:postgresql://...)");
        String op = get.apply("operator.token_sha256").toLowerCase(Locale.ROOT);
        if (!op.isEmpty() && !op.matches("^[0-9a-f]{64}$")) throw new OoaException("SAAS.CONFIG", "operator.token_sha256 is 64 hex digits (ooa region token makes one)");
        return new RegionConfig(region, domain, url, get.apply("db.user"), get.apply("db.password_env"), op, get.apply("directory.url"),
            get.apply("directory.token_env"), Boolean.parseBoolean(get.apply("serverless")));
    }

    /** The region's database: its PostgreSQL, or H2 in the data folder when none is set (evaluation and tests). */
    public Db openDatabase(Path data) {
        if (dbUrl.isEmpty()) return Db.h2File(data);
        String pw = dbPasswordEnv.isEmpty() ? "" : System.getenv(dbPasswordEnv);
        if (!dbPasswordEnv.isEmpty() && pw == null) throw new OoaException("SAAS.CONFIG", "The environment variable " + dbPasswordEnv + " (db.password_env) is not set");
        return new Db(dbUrl, dbUser, pw == null ? "" : pw, 4);
    }

    /** The directory client of this region, or null when it has no directory (a single region). */
    public RegionDirectory.Client directoryClient() {
        if (directoryUrl.isEmpty()) return null;
        String token = directoryTokenEnv.isEmpty() ? null : System.getenv(directoryTokenEnv);
        if (token == null || token.isBlank()) throw new OoaException("SAAS.CONFIG", "Set " + (directoryTokenEnv.isEmpty() ? "directory.token_env" : directoryTokenEnv) + " to this region's directory token");
        return RegionDirectory.client(directoryUrl, token);
    }
}
