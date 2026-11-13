package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A ready runner installer, downloaded from One CommandCenter (14 Nov 2026, round 4 item A1), as AA's Connect local
 * device: a ZIP of the product with its own Java and an Install.cmd that already holds this One CommandCenter's address
 * and a one-time registration key, so the person unzips it and runs Install.cmd, and the runner registers by itself.
 *
 * Where the product comes from, first found:
 * 1. the newest {@code ooa-setup-*.zip} an administrator placed in {@code <data>/downloads} (any server: Windows,
 *    Linux, a container, SaaS);
 * 2. this One CommandCenter's own installed folder, when it was installed from the setup package (it holds
 *    Install-OOA.ps1, bin\ooa.bat and runtime\).
 * The key must be one the person made for their own computer (My devices) or, for someone who manages runners, one
 * they made in Runner Machines; it must be unused and not expired. Nothing is written to disk: the ZIP is streamed.
 */
final class RunnerPackage {
    private RunnerPackage() {}

    /** The setup package placed in the data folder, or this install's folder; null when neither is there. */
    static Path source(ControlRoom cr) {
        if (cr.dataDir != null) {
            Path dl = cr.dataDir.resolve("downloads");
            if (Files.isDirectory(dl)) try (Stream<Path> s = Files.list(dl)) {
                Optional<Path> zip = s.filter((p) -> p.getFileName().toString().matches("ooa-setup-.*\\.zip")).max(Comparator.comparing(RunnerPackage::modified));
                if (zip.isPresent()) return zip.get();
            } catch (IOException ignored) { /* fall through to the install folder */ }
        }
        Path home = installFolder();
        return home != null && Files.isRegularFile(home.resolve("Install-OOA.ps1")) && Files.isRegularFile(home.resolve("bin").resolve("ooa.bat"))
            && Files.isDirectory(home.resolve("runtime")) ? home : null;
    }

    private static Instant modified(Path p) { try { return Files.getLastModifiedTime(p).toInstant(); } catch (IOException e) { return Instant.EPOCH; } }

    /** The folder the product runs from (lib\..), or OOA_INSTALL_DIR when set. */
    static Path installFolder() {
        String env = System.getenv("OOA_INSTALL_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        try {
            Path jar = Path.of(RunnerPackage.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(jar) && jar.getParent() != null && jar.getParent().getFileName().toString().equals("lib") ? jar.getParent().getParent() : null;
        } catch (Exception e) { return null; }
    }

    /** Whether a package can be made here, and from what (for the console's buttons). */
    static Map<String, Object> availability(ControlRoom cr) {
        Path s = source(cr);
        if (s == null) return Map.of("available", false, "why", "No setup package is here: put ooa-setup-<version>.zip in the data folder's downloads folder, or install One CommandCenter from the setup package.");
        return Map.of("available", true, "from", Files.isDirectory(s) ? "this installation" : s.getFileName().toString());
    }

    /** The key may be packaged by this person: their own (My devices), or theirs from Runner Machines; unused, not expired. */
    static Instant checkKey(ControlRoom cr, ControlRoom.User u, String key) {
        Map<String, Object> k = key == null ? null : cr.db.one("SELECT created_by, expires, used_by FROM registration_keys WHERE key_hash = ?", Security.sha256(key));
        if (k == null || k.get("used_by") != null || Instant.parse(k.get("expires").toString()).isBefore(ControlRoom.now()))
            throw new OoaException("CR.BAD_REGISTRATION_KEY", "The registration key is wrong, expired or already used", "Make a new one with Connect to my computer, or Register a runner.");
        String by = String.valueOf(k.get("created_by"));
        boolean mine = by.equals(MyAccount.SELF + u.username()) || (by.equals(u.username()) && cr.can(u, "devices.manage"));
        if (!mine) throw new OoaException("CR.FORBIDDEN", "This registration key is not yours", "Make your own key with Connect to my computer.");
        return Instant.parse(k.get("expires").toString());
    }

    /** The Install.cmd in the package: the setup's own, with the address and key given. */
    static String installCmd(String url, String key, Instant expires, String forWhom) {
        return "@echo off\r\n"
            + "rem ONEOFFICEAUTOMATION runner for " + forWhom + ", from " + url + ".\r\n"
            + "rem The registration key below works once, until " + expires + " (UTC). Double-click to install the runner for\r\n"
            + "rem you (no administrator rights needed), or add -AllUsers -Service from an administrator's prompt.\r\n"
            + "powershell -NoProfile -ExecutionPolicy Bypass -File \"%~dp0Install-OOA.ps1\" -Role Runner -Url \"" + url + "\" -RegistrationKey \"" + key + "\" %*\r\n"
            + "set code=%errorlevel%\r\n"
            + "if not \"%~1\"==\"\" exit /b %code%\r\n"
            + "if not %code%==0 pause\r\n"
            + "exit /b %code%\r\n";
    }

    /** Streams the package: the product as it is, with Install.cmd replaced, under the folder ooa-runner\. */
    static void write(ControlRoom cr, OutputStream out, String url, String key, Instant expires, String forWhom) throws IOException {
        Path s = source(cr);
        if (s == null) throw new OoaException("CR.NO_RUNNER_PACKAGE", "This One CommandCenter has no setup package to make a runner installer from",
                "Put ooa-setup-<version>.zip in the data folder's downloads folder, or give people the setup package and the key.");
        byte[] cmd = installCmd(url, key, expires, forWhom).getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            if (Files.isDirectory(s)) {
                try (Stream<Path> files = Files.walk(s)) {
                    for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        String rel = s.relativize(f).toString().replace('\\', '/');
                        if (skip(rel)) continue;
                        z.putNextEntry(new ZipEntry("ooa-runner/" + rel));
                        Files.copy(f, z);
                        z.closeEntry();
                    }
                }
            } else {
                try (InputStream in = Files.newInputStream(s); ZipInputStream zin = new ZipInputStream(in)) {
                    for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                        if (e.isDirectory()) continue;
                        String name = e.getName().replace('\\', '/');
                        int slash = name.indexOf('/');
                        String rel = slash >= 0 ? name.substring(slash + 1) : name;   // the zip's own top folder is replaced
                        if (rel.isEmpty() || skip(rel) || rel.contains("../")) continue;
                        z.putNextEntry(new ZipEntry("ooa-runner/" + rel));
                        zin.transferTo(z);
                        z.closeEntry();
                    }
                }
            }
            z.putNextEntry(new ZipEntry("ooa-runner/Install.cmd"));
            z.write(cmd);
            z.closeEntry();
        }
    }

    /** Not for a runner: the old Install.cmd, Document Automation, and anything that is data rather than the product. */
    static boolean skip(String rel) {
        String r = rel.toLowerCase(Locale.ROOT);
        return r.equals("install.cmd") || r.startsWith("docflow/") || r.startsWith("data/") || r.startsWith("logs/") || r.endsWith(".log") || r.equals("master.key");
    }
}
