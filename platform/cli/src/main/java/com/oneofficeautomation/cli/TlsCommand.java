package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.TlsConfig;
import com.oneofficeautomation.controlroom.Vault;

import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * {@code ooa tls}: HTTPS for the Command Center (config/ooa-tls.properties in the data folder). Stop the server first;
 * it serves HTTPS on its port from its next start.
 * <pre>
 * ooa tls configure --pfx FILE        use the company's certificate (PKCS#12 .pfx/.p12 with the private key); then set-password
 * ooa tls set-password [--from-env VAR]   store the .pfx password, encrypted with the vault master key
 * ooa tls self-signed [--host NAME] [--days 825]   make a certificate for tests (browsers warn; runners need --ca)
 * ooa tls show                        the certificate in use: names and validity
 * ooa tls export-cert --out FILE.cer  the certificate without its key, for runners (ooa agent register --ca) and browsers
 * ooa tls off                         back to plain HTTP (the settings file is renamed, not deleted)
 * </pre>
 * Every command takes [--data DIR].
 */
final class TlsCommand {
    private TlsCommand() { }

    static int run(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        Map<String, String> o = Ooa.options(Arrays.copyOfRange(a, 1, a.length));
        Path data = Ooa.dataDir(o);
        Path file = TlsConfig.defaultFile(data);
        return switch (a[0]) {
            case "configure" -> configure(file, o);
            case "set-password" -> setPassword(file, data, o);
            case "self-signed" -> selfSigned(file, data, o);
            case "show" -> show(file, data);
            case "export-cert" -> exportCert(file, data, o);
            case "off" -> off(file);
            default -> { usage(); yield 1; }
        };
    }

    static void usage() { System.err.println("usage: ooa tls configure --pfx FILE | set-password | self-signed [--host NAME] | show | export-cert --out FILE | off  [--data DIR]"); }

    static int configure(Path file, Map<String, String> o) {
        if (!o.containsKey("pfx")) { usage(); return 1; }
        Path pfx = Path.of(o.get("pfx"));
        if (!Files.exists(pfx)) { System.err.println("No such file: " + pfx); return 1; }
        TlsConfig.setKeystore(file, pfx);
        System.out.println("HTTPS will use " + pfx.toAbsolutePath() + ". Next: ooa tls set-password, then ooa tls show.");
        return 0;
    }

    static int setPassword(Path file, Path data, Map<String, String> o) {
        String pw;
        if (o.containsKey("from-env")) pw = System.getenv(o.get("from-env"));
        else if (System.console() != null) { char[] c = System.console().readPassword("Password of the certificate file: "); pw = c == null ? null : new String(c); }
        else pw = new Scanner(System.in).nextLine();
        if (pw == null || pw.isEmpty()) { System.err.println("No password given."); return 1; }
        TlsConfig.storePassword(file, new Vault(data), pw);
        System.out.println("Stored the certificate password in " + file + ", encrypted with the vault master key.");
        return 0;
    }

    /** A self-signed certificate made with the JDK's keytool, for tests; its password is random and stored encrypted. */
    static int selfSigned(Path file, Path data, Map<String, String> o) throws Exception {
        String host = o.getOrDefault("host", localHost());
        if (!host.matches("[A-Za-z0-9.-]{1,253}")) { System.err.println("--host is a machine name, for example ooa.example.com"); return 1; }
        Path pfx = file.getParent().resolve("ooa-self-signed.pfx");
        Files.createDirectories(pfx.getParent());
        if (Files.exists(pfx)) Files.delete(pfx);
        byte[] r = new byte[18];
        new SecureRandom().nextBytes(r);
        String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(r);
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool").toString();
        List<String> cmd = List.of(keytool, "-genkeypair", "-alias", "ooa", "-keyalg", "RSA", "-keysize", "3072", "-validity", o.getOrDefault("days", "825"),
            "-storetype", "PKCS12", "-keystore", pfx.toString(), "-storepass:env", "OOA_TLS_PW", "-keypass:env", "OOA_TLS_PW",
            "-dname", "CN=" + host + ", O=ONEOFFICEAUTOMATION (self-signed)", "-ext", "SAN=" + (host.equalsIgnoreCase("localhost") ? "" : "dns:" + host + ",") + "dns:localhost,ip:127.0.0.1");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("OOA_TLS_PW", pw);   // the password never appears on a command line
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) { System.err.println("keytool failed: " + out); return 1; }
        TlsConfig.setKeystore(file, pfx);
        TlsConfig.storePassword(file, new Vault(data), pw);
        System.out.println("Made a self-signed certificate for " + (host.equalsIgnoreCase("localhost") ? "localhost" : host + " (and localhost)") + " in " + pfx + ".");
        System.out.println("Browsers will warn about it, and runners on other machines must trust it: ooa tls export-cert --out ooa.cer,");
        System.out.println("then on each runner: ooa agent register --url https://" + host + ":8720 --key ... --ca ooa.cer");
        System.out.println("Restart the Command Center to serve HTTPS.");
        return 0;
    }

    private static String localHost() { try { return java.net.InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT); } catch (Exception e) { return "localhost"; } }

    @SuppressWarnings("unchecked")
    static int show(Path file, Path data) {
        if (!Files.exists(file)) { System.out.println("HTTPS is off (no " + file + "). Set it up with: ooa tls configure --pfx FILE, or ooa tls self-signed."); return 0; }
        Map<String, Object> d = TlsConfig.load(file).describe(new Vault(data));
        System.out.println("Certificate: " + d.get("subject") + (Boolean.TRUE.equals(d.get("self_signed")) ? "  (self-signed)" : "  (issued by " + d.get("issuer") + ")"));
        System.out.println("Names:       " + String.join(", ", (List<String>) d.get("names")));
        System.out.println("Valid:       " + d.get("valid_from") + " to " + d.get("valid_until"));
        return 0;
    }

    static int exportCert(Path file, Path data, Map<String, String> o) throws Exception {
        if (!o.containsKey("out")) { usage(); return 1; }
        TlsConfig cfg = TlsConfig.load(file);
        String pem = cfg.certificatePem(new Vault(data));
        Path out = Path.of(o.get("out"));
        Files.writeString(out, pem);
        System.out.println("Wrote the certificate (no private key) to " + out.toAbsolutePath() + ".");
        return 0;
    }

    static int off(Path file) throws Exception {
        if (!Files.exists(file)) { System.out.println("HTTPS is already off."); return 0; }
        Files.move(file, file.resolveSibling("ooa-tls.properties.off"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("HTTPS is off from the next start (settings kept in ooa-tls.properties.off).");
        return 0;
    }
}
