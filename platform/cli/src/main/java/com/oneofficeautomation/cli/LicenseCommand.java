package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * {@code ooa license}: the vendor's tools for license files (customers install them in Administration > Licenses).
 * <pre>
 * ooa license keygen --out DIR        a new Ed25519 signing key pair (private.key stays with the vendor, never in the repository)
 * ooa license issue --key DIR\private.key --customer "Name" --expires 2027-12-31 [--developer N] [--citizen-developer N]
 *                   [--attended N] [--unattended N] [--grace-days 14] [--edition Enterprise] [--id ID] [--notes TEXT] --out FILE.lic
 * ooa license show FILE.lic           the terms of a license file, and whether this release accepts its signature
 * </pre>
 */
final class LicenseCommand {
    private LicenseCommand() { }

    static int run(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        Map<String, String> o = Ooa.options(Arrays.copyOfRange(a, 1, a.length));
        return switch (a[0]) {
            case "keygen" -> keygen(o);
            case "issue" -> issue(o);
            case "show" -> show(a.length > 1 ? a[1] : null);
            default -> { usage(); yield 1; }
        };
    }

    static void usage() { System.err.println("usage: ooa license keygen --out DIR | issue --key FILE --customer NAME --expires YYYY-MM-DD [seats] --out FILE | show FILE"); }

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
            if (!o.containsKey(k)) { System.err.println("Give --" + k + "."); usage(); return 1; }
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

    static int show(String file) throws Exception {
        if (file == null) { usage(); return 1; }
        String text = Files.readString(Path.of(file));
        try {
            License l = License.verify(text, License.publicKey(License.VENDOR_PUBLIC_KEY));
            System.out.println("Signature: valid for this release");
            System.out.println("License:   " + l.id() + " (" + l.edition() + ") for " + l.customer());
            System.out.println("Valid:     " + l.issued() + " to " + l.expires() + ", then " + l.graceDays() + " grace days");
            System.out.println("Seats:     " + l.seats());
            return 0;
        } catch (OoaException e) { System.err.println(e.getMessage()); return 2; }
    }
}
