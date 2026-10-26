package com.oneofficeautomation.vendor;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * The vendor's license tools, reached through {@code ooa license keygen|issue} in the vendor build only. The customer
 * distribution does not contain this module, so customers can read and check license files but never make them.
 * <pre>
 * ooa license ui [--key FILE] [--port 8790] [--no-browser]   License Studio, the page for issuing and checking licenses
 * ooa license keygen --out DIR        a new Ed25519 signing key pair (private.key stays with the vendor, never in the repository)
 * ooa license issue --key DIR\private.key --customer "Name" --expires 2027-12-31 [--developer N] [--citizen-developer N]
 *                   [--attended N] [--unattended N] [--grace-days 14] [--edition Enterprise] [--id ID] [--notes TEXT] --out FILE.lic
 * </pre>
 */
public final class VendorLicenseTools {
    private VendorLicenseTools() { }

    /** Runs keygen or issue; args[0] is the sub-command. */
    public static int run(String[] a) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < a.length; i++) if (a[i].startsWith("--")) o.put(a[i].substring(2), i + 1 < a.length && !a[i + 1].startsWith("--") ? a[++i] : "true");
        return switch (a[0]) {
            case "keygen" -> keygen(o);
            case "issue" -> issue(o);
            case "ui" -> ui(o);
            default -> 1;
        };
    }

    /** Starts License Studio on 127.0.0.1 and opens it in the browser (unless --no-browser); stops with Ctrl+C. */
    static int ui(Map<String, String> o) throws Exception {
        Path key = Path.of(o.getOrDefault("key", Path.of(System.getProperty("user.home"), "OOA License Keys", "private.key").toString()));
        LicenseStudio studio = new LicenseStudio(key, Integer.parseInt(o.getOrDefault("port", "8790")));
        studio.start();
        if (!studio.url().contains(":" + o.getOrDefault("port", "8790") + "/"))
            System.out.println("Port " + o.getOrDefault("port", "8790") + " is in use (License Studio may already be open in another window), so another port is used.");
        System.out.println("License Studio is running (this computer only). Open:");
        System.out.println("  " + studio.url());
        System.out.println("Signing key: " + key.toAbsolutePath() + (Files.exists(key) ? "" : "  (NOT FOUND: use --key FILE)"));
        System.out.println("Press Ctrl+C (or close this window) to stop.");
        if (!o.containsKey("no-browser") && java.awt.Desktop.isDesktopSupported()) {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(studio.url())); } catch (Exception ignored) { /* the address above still works */ }
        }
        Thread.currentThread().join();
        return 0;
    }

    static int keygen(Map<String, String> o) throws Exception {
        if (!o.containsKey("out")) { System.err.println("Give --out DIR (a folder outside the repository, readable only by you)."); return 1; }
        Path dir = Path.of(o.get("out"));
        Files.createDirectories(dir);
        Path priv = dir.resolve("private.key"), pub = dir.resolve("public.key");
        if (Files.exists(priv)) { System.err.println(priv + " exists; refusing to replace a signing key (licenses signed with it would stop verifying)."); return 1; }
        Map<String, String> kp = License.newKeyPair();
        Files.writeString(priv, kp.get("private") + System.lineSeparator());
        Files.writeString(pub, kp.get("public") + System.lineSeparator());
        System.out.println("Wrote " + priv + " (keep it secret; back it up offline) and " + pub + ".");
        System.out.println("Public key (built into License.VENDOR_PUBLIC_KEY): " + kp.get("public"));
        return 0;
    }

    static int issue(Map<String, String> o) throws Exception {
        for (String k : List.of("key", "customer", "expires", "out"))
            if (!o.containsKey(k)) { System.err.println("Give --" + k + ". Usage: ooa license issue --key FILE --customer NAME --expires YYYY-MM-DD [--developer N] [--citizen-developer N] [--attended N] [--unattended N] --out FILE"); return 1; }
        Map<String, Integer> seats = new LinkedHashMap<>();
        seats.put("developer", Integer.parseInt(o.getOrDefault("developer", "0")));
        seats.put("citizen_developer", Integer.parseInt(o.getOrDefault("citizen-developer", "0")));
        seats.put("attended_runner", Integer.parseInt(o.getOrDefault("attended", "0")));
        seats.put("unattended_runner", Integer.parseInt(o.getOrDefault("unattended", "0")));
        LocalDate expires = LocalDate.parse(o.get("expires"));
        if (expires.isBefore(LocalDate.now())) throw new OoaException("LICENSE.BAD_INPUT", "The expiry date is in the past");
        String id = o.getOrDefault("id", "LIC-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        License l = new License(id, o.get("customer"), o.getOrDefault("edition", "Enterprise"), LocalDate.now(), expires,
            Integer.parseInt(o.getOrDefault("grace-days", "14")), seats, o.getOrDefault("notes", ""));
        String text = l.sign(License.privateKey(Files.readString(Path.of(o.get("key")))));
        Path out = Path.of(o.get("out"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, text);
        System.out.println("Issued " + id + " for " + l.customer() + ", valid until " + expires + " (+" + l.graceDays() + " grace days), seats " + seats + ": " + out);
        return 0;
    }
}
