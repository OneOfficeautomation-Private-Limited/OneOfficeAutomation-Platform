package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.DbConfig;
import com.oneofficeautomation.controlroom.TenantMove;
import com.oneofficeautomation.controlroom.Vault;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Moving an on-premises One CommandCenter into a SaaS tenant (round 4 item A4):
 * <pre>
 *   ooa tenant export --out FILE --password-env VAR [--with-history] [--data DIR] [--db-url ...]
 *                         on the on-premises server, with One CommandCenter stopped
 *   ooa region tenant import NAME --file FILE --password-env VAR --url https://REGION-DOMAIN --token-env VAR
 *                         [--name COMPANY] [--admin-email ADDRESS] [--seats KIND=N,...]
 * </pre>
 * See docs/infrastructure/09-saas-and-multi-region-operations.md section 12.
 */
final class TenantCommand {
    private TenantCommand() { }

    /** Reads environment variables (tests put their own in). */
    static java.util.function.UnaryOperator<String> env = System::getenv;

    static char[] passphrase(Map<String, String> o) {
        String var = o.get("password-env"), v = var == null ? null : env.apply(var);
        if (v == null || v.isEmpty()) throw new OoaException("MOVE.PASSPHRASE", "Set " + var + " to the move file's passphrase",
            "Put the passphrase in the environment variable --password-env names (not on the command line, where others could see it).");
        return v.toCharArray();
    }

    static int export(Map<String, String> o) throws Exception {
        Path data = Ooa.dataDir(o);
        Path out = Path.of(o.get("out")).toAbsolutePath();
        if (Files.isDirectory(out)) throw new OoaException("CLI.BAD_INPUT", out + " is a folder", "Give a file name, such as acme.ooamove.");
        char[] pass = passphrase(o);
        Path cfgFile = o.containsKey("db-config") ? Path.of(o.get("db-config")) : DbConfig.defaultFile(data);
        Vault vault = new Vault(data);
        String pw = o.containsKey("db-password-env") ? System.getenv(o.get("db-password-env")) : "";
        try (Db db = o.containsKey("db-url") ? new Db(o.get("db-url"), o.getOrDefault("db-user", ""), pw == null ? "" : pw)
                : Files.exists(cfgFile) ? Db.open(DbConfig.load(cfgFile), data, vault, false) : Db.h2File(data)) {
            Map<String, Object> r = TenantMove.export(db, vault, data, out, pass, o.containsKey("with-history"));
            System.out.println("Move file written: " + r.get("file") + " (" + r.get("size_mb") + " MB)");
            System.out.println("  " + r.get("rows") + " rows in " + ((Map<?, ?>) r.get("tables")).size() + " tables, " + r.get("secrets") + " secrets, "
                + r.get("files") + " files, " + r.get("audit_rows") + " Audit Trail rows (kept as an archive)");
            if (r.get("missing_files") instanceof List<?> m && !m.isEmpty())
                System.out.println("  Repository contents not found (left out): " + m);
            System.out.println("Left out:");
            for (Object x : (List<?>) r.get("left_out")) { Map<?, ?> m = (Map<?, ?>) x; System.out.println("  - " + m.get("what") + ": " + m.get("why")); }
            System.out.println("The file holds every secret in plain text under the passphrase: send the file and the passphrase by different ways, and delete the file after the import.");
        } finally { Arrays.fill(pass, '\0'); }
        return 0;
    }

    @SuppressWarnings("unchecked")
    static int importInto(Map<String, String> o, List<String> pos) throws Exception {
        String base = o.get("url").replaceAll("/+$", "");
        String token = env.apply(o.get("token-env"));
        if (token == null || token.isBlank()) throw new OoaException("SAAS.TOKEN", "Set " + o.get("token-env") + " to the operator token", "Set the environment variable to the region's operator token (ooa region token makes one).");
        Path file = Path.of(o.get("file"));
        if (!Files.isRegularFile(file)) throw new OoaException("CLI.BAD_INPUT", "No file " + file, "Give the file ooa tenant export wrote.");
        char[] pass = passphrase(o);
        StringBuilder q = new StringBuilder();
        if (o.containsKey("name")) q.append("&name=").append(URLEncoder.encode(o.get("name"), StandardCharsets.UTF_8));
        if (o.containsKey("admin-email")) q.append("&admin_email=").append(URLEncoder.encode(o.get("admin-email"), StandardCharsets.UTF_8));
        if (o.containsKey("seats")) q.append("&seats=").append(URLEncoder.encode(JsonLite.write(RegionCommand.seats(o.get("seats"))), StandardCharsets.UTF_8));
        String path = "/api/operator/tenants/" + pos.get(0) + "/import" + (q.isEmpty() ? "" : "?" + q.substring(1));
        String header = Base64.getEncoder().encodeToString(new String(pass).getBytes(StandardCharsets.UTF_8));
        Arrays.fill(pass, '\0');
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofHours(6))
            .header("Authorization", "Bearer " + token).header("X-OOA-Move-Passphrase", header).header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofFile(file)).build();
        System.out.println("Sending " + file + " (" + Math.round(Files.size(file) / 10485.76) / 100.0 + " MB) to " + base + " ...");
        HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(req, HttpResponse.BodyHandlers.ofString());
        Object json;
        try { json = JsonLite.parse(r.body()); } catch (RuntimeException e) { throw new OoaException("SAAS.API", "The region answered " + r.statusCode() + " without JSON", "Check the region's address; it may be behind a proxy that answers instead."); }
        if (r.statusCode() / 100 != 2) {
            Object err = json instanceof Map<?, ?> m ? m.get("error") : null;
            Object msg = err instanceof Map<?, ?> e ? e.get("message") : r.body();
            Object fix = err instanceof Map<?, ?> e ? e.get("suggestedFix") : null;
            throw new OoaException("SAAS.API", "The region refused the import: " + msg + " (HTTP " + r.statusCode() + ")", fix == null ? "Correct what the message names and run the command again." : String.valueOf(fix));
        }
        print((Map<String, Object>) json);
        return 0;
    }

    /** The check report: rows per table, secrets, files, what was left out and why, the runners and what to look at. */
    @SuppressWarnings("unchecked")
    static void print(Map<String, Object> r) {
        System.out.println("Imported into " + r.get("url"));
        System.out.println();
        System.out.printf("  %-28s %10s %10s%n", "TABLE", "EXPORTED", "IMPORTED");
        boolean ok = true;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) r.get("tables")).entrySet()) {
            Map<String, Object> t = (Map<String, Object>) e.getValue();
            if (((Number) t.get("exported")).longValue() == 0) continue;
            boolean same = String.valueOf(t.get("exported")).equals(String.valueOf(t.get("imported")));
            ok &= same;
            System.out.printf("  %-28s %10s %10s%s%n", e.getKey(), t.get("exported"), t.get("imported"), same ? "" : "  DIFFERENT");
        }
        System.out.println("  (tables with no rows not shown)");
        System.out.println();
        System.out.println("  Rows: " + r.get("rows") + (ok ? ", every table as exported" : ", some tables differ"));
        System.out.println("  Secrets moved and encrypted with the tenant's key: " + r.get("secrets"));
        System.out.println("  Files: " + r.get("files"));
        if (r.get("missing_files") instanceof List<?> m && !m.isEmpty()) System.out.println("  Repository contents that were missing on premises: " + m);
        Map<String, Object> a = (Map<String, Object>) r.get("audit_archive");
        if (a != null && a.get("file") != null)
            System.out.println("  Audit Trail archive: " + a.get("file") + " in the tenant's folder (" + a.get("rows") + " rows, SHA-256 " + a.get("sha256") + ")");
        System.out.println();
        System.out.println("Left out:");
        for (Object x : (List<?>) r.get("left_out")) { Map<?, ?> m = (Map<?, ?>) x; System.out.println("  - " + m.get("what") + ": " + m.get("why")); }
        List<Object> runners = (List<Object>) r.get("runners");
        if (runners != null && !runners.isEmpty()) {
            System.out.println();
            System.out.println("Runners keep their registration. On each runner computer, run:");
            System.out.println("  " + ((Map<String, Object>) runners.get(0)).get("move"));
            for (Object x : runners) System.out.println("    " + ((Map<String, Object>) x).get("name"));
        }
        List<Object> warnings = (List<Object>) r.get("warnings");
        if (warnings != null && !warnings.isEmpty()) {
            System.out.println();
            System.out.println("Check:");
            for (Object w : warnings) System.out.println("  - " + w);
        }
        System.out.println();
        System.out.println("People sign in at the new address with their old passwords. Delete the move file now.");
    }
}
