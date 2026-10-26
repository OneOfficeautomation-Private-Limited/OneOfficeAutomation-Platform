package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Password-protected archives: our own round trip, wrong and missing passwords, and archives of another tool (libarchive's tar, when present). */
class ZipCryptTest {
    static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    static Path folder(Path w) throws Exception {
        Path src = w.resolve("src");
        Files.createDirectories(src.resolve("sub"));
        Files.writeString(src.resolve("report.txt"), "Totals for October: 4,711.00 ".repeat(200));
        Files.writeString(src.resolve("sub/x.csv"), "a");
        Files.write(src.resolve("sub/empty.bin"), new byte[0]);
        return src;
    }

    @Test void roundTripAndPasswordErrors() throws Exception {
        Path w = Files.createTempDirectory("ooa-zipcrypt");
        Path src = folder(w);
        ActionContext c = ctx(w);
        FilePackage f = new FilePackage();
        SecureString pw = new SecureString("Pa55-w0rd!", "vault/zip/password");
        f.zip(c, "src", "out/secret.zip", null, "normal", true, false, pw);
        assertTrue(ZipCrypt.anyEncrypted(w.resolve("out/secret.zip")));
        // the content is not readable without the password
        assertFalse(new String(Files.readAllBytes(w.resolve("out/secret.zip"))).contains("Totals for October"));

        List<?> files = (List<?>) f.unzip(c, "out/secret.zip", "back", true, pw).get("files");
        assertEquals(3, files.size());
        assertEquals(Files.readString(src.resolve("report.txt")), Files.readString(w.resolve("back/report.txt")));
        assertEquals("a", Files.readString(w.resolve("back/sub/x.csv")));
        assertEquals(0, Files.size(w.resolve("back/sub/empty.bin")));

        assertEquals("ZIP.WRONG_PASSWORD", assertThrows(ActionException.class, () -> f.unzip(c, "out/secret.zip", "bad", true, new SecureString("nope", "x"))).code());
        assertEquals("ZIP.PASSWORD_REQUIRED", assertThrows(ActionException.class, () -> f.unzip(c, "out/secret.zip", "bad", true, null)).code());
        assertEquals("ZIP.BAD_PASSWORD", assertThrows(ActionException.class, () -> f.zip(c, "src", "out/e.zip", null, "normal", true, false, new SecureString("", "x"))).code());

        // store (no compression) and a plain archive still open without a password
        f.zip(c, "src", "out/stored.zip", "*.txt", "store", true, false, pw);
        assertEquals(1, ((List<?>) f.unzip(c, "out/stored.zip", "stored", true, pw).get("files")).size());
        f.zip(c, "src", "out/plain.zip", null, "normal", true, false, null);
        assertFalse(ZipCrypt.anyEncrypted(w.resolve("out/plain.zip")));
        assertEquals(3, ((List<?>) f.unzip(c, "out/plain.zip", "plain", true, null).get("files")).size());
    }

    @Test void entriesOutsideTheFolderAreRefused() throws Exception {
        Path w = Files.createTempDirectory("ooa-zipslip");
        Path evil = w.resolve("evil.txt");
        Files.writeString(evil, "x");
        ZipCrypt.writeAes(w.resolve("slip.zip"), List.of(new ZipCrypt.Item("../escaped.txt", evil)), 6, "pw");
        assertEquals("ZIP.UNSAFE_ENTRY", assertThrows(ActionException.class,
                () -> new FilePackage().unzip(ctx(w), "slip.zip", "dest", true, new SecureString("pw", "x"))).code());
        assertFalse(Files.exists(w.resolve("escaped.txt")));
    }

    /** Windows ships libarchive as tar.exe; it reads and writes AES and ZipCrypto archives, so it checks we match other tools. */
    @Test void archivesOfAnotherTool() throws Exception {
        Path tar = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "tar.exe");
        if (!Files.isRegularFile(tar)) { System.out.println("    (no tar.exe here; skipped)"); return; }
        Path w = Files.createTempDirectory("ooa-zipinterop");
        Path src = folder(w);
        ActionContext c = ctx(w);
        FilePackage f = new FilePackage();
        SecureString pw = new SecureString("Pa55-w0rd!", "vault/zip/password");

        // ours, opened by the other tool
        f.zip(c, "src", "ours.zip", null, "normal", true, false, pw);
        Path byTar = Files.createDirectories(w.resolve("by-tar"));
        assertEquals(0, run(w, tar.toString(), "-xf", w.resolve("ours.zip").toString(), "--passphrase", "Pa55-w0rd!", "-C", byTar.toString()));
        assertEquals(Files.readString(src.resolve("report.txt")), Files.readString(byTar.resolve("report.txt")));

        // theirs, opened by us: AES-256 and the older ZipCrypto
        for (String enc : List.of("aes256", "traditional")) {
            Path z = w.resolve("tar-" + enc + ".zip");
            assertEquals(0, run(src, tar.toString(), "-a", "-cf", z.toString(), "--options", "zip:encryption=" + enc, "--passphrase", "Pa55-w0rd!", "report.txt", "sub"));
            f.unzip(c, z.getFileName().toString(), "from-" + enc, true, pw);
            assertEquals(Files.readString(src.resolve("report.txt")), Files.readString(w.resolve("from-" + enc + "/report.txt")), enc);
            assertEquals("ZIP.WRONG_PASSWORD", assertThrows(ActionException.class, () -> f.unzip(c, z.getFileName().toString(), "bad-" + enc, true, new SecureString("wrong", "x"))).code(), enc);
        }
    }

    static int run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "tar did not finish");
        if (p.exitValue() != 0) System.out.println("    tar: " + out);
        return p.exitValue();
    }
}
