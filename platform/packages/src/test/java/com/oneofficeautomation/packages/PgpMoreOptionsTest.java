package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** pgp encrypt: cipher, compression and passphrase (symmetric) options. The gpg runs need GnuPG; otherwise they report a skip. */
class PgpMoreOptionsTest {
    private final PgpPackage pgp = new PgpPackage();

    private static ActionContext ctx(Path w) { return PackagesTestSupport.ctx(w, Map.of()); }

    private static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    private static boolean noGpg() {
        if (PgpPackage.usable() && OcrPgpTest.has(PgpPackage.exe())) return false;
        System.out.println("    (gpg not installed; skipped)");
        return true;
    }

    /** gpg's packet listing of an encrypted file, decrypting with the passphrase (test only: passphrase on the command line). */
    private static String packets(Path home, Path file, String pass) throws Exception {
        Files.createDirectories(home);
        Process p = new ProcessBuilder(PgpPackage.exe(), "--homedir", home.toString(), "--batch", "--pinentry-mode", "loopback", "--passphrase", pass,
                "--no-symkey-cache", "--list-packets", file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    private static void killAgent(Path home) {
        try { new ProcessBuilder(PgpPackage.gpgconf(), "--homedir", home.toString(), "--kill", "gpg-agent").start().waitFor(); } catch (Exception ignored) { }
    }

    @Test void optionNames() {
        assertEquals("AES256", PgpPackage.cipher("aes256"));
        assertEquals("AES", PgpPackage.cipher("AES128"));
        assertEquals("CAMELLIA192", PgpPackage.cipher("Camellia192"));
        assertNull(PgpPackage.cipher("default"));
        assertNull(PgpPackage.cipher(null));
        assertEquals("bzip2", PgpPackage.compression("BZIP2"));
        assertEquals("none", PgpPackage.compression("none"));
        assertNull(PgpPackage.compression(""));
    }

    @Test void badOptionsStopBeforeGpgRuns() throws Exception {
        Path w = PackagesTest.tmp();
        ActionContext c = ctx(w);
        Files.writeString(w.resolve("a.txt"), "x");
        SecureString pass = new SecureString("pp", "test/pgp/pass");
        assertEquals("PGP.BAD_CIPHER", code(() -> pgp.encrypt(c, "a.txt", "a.pgp", null, false, null, null, true, "rot13", null, pass)));
        assertEquals("PGP.BAD_COMPRESSION", code(() -> pgp.encrypt(c, "a.txt", "a.pgp", null, false, null, null, true, null, "rar", pass)));
        assertEquals("PGP.EMPTY_PASSPHRASE", code(() -> pgp.encrypt(c, "a.txt", "a.pgp", null, false, null, null, true, null, null, new SecureString("", "test/pgp/empty"))));
        assertEquals("PGP.BAD_PASSPHRASE_INPUT", code(() -> pgp.encrypt(c, "a.txt", "a.pgp", null, false, null, null, true, null, null, "plain text")));
        assertFalse(Files.exists(w.resolve("a.pgp")));
    }

    @Test void passphraseOnlyEncryptionWithCipherAndCompression() throws Exception {
        if (noGpg()) return;
        Path w = PackagesTest.tmp();
        ActionContext c = ctx(w);
        SecureString pass = new SecureString("sym-pass-1", "test/pgp/pass");
        Files.writeString(w.resolve("pay.csv"), "name,amount\nAnn,5000\n".repeat(20));
        Path home = w.resolve("keys");
        try {
            pgp.encrypt(c, "pay.csv", "out/pay.csv.gpg", null, false, "keys", null, true, "AES256", "bzip2", pass);
            Path enc = w.resolve("out/pay.csv.gpg");
            assertFalse(Files.readString(enc, StandardCharsets.ISO_8859_1).contains("Ann,5000"), "ciphertext");
            String list = packets(w.resolve("check"), enc, "sym-pass-1");
            assertTrue(list.contains(":symkey enc packet:"), list);                 // passphrase (symmetric) mode
            assertTrue(list.contains("cipher 9") || list.contains("AES256"), list);   // 9 = AES-256
            assertTrue(list.contains("compressed packet: algo=3"), list);             // 3 = BZIP2

            pgp.encrypt(c, "pay.csv", "out/plain-pack.gpg", null, true, "keys", null, true, "CAMELLIA128", "none", pass);
            String list2 = packets(w.resolve("check"), w.resolve("out/plain-pack.gpg"), "sym-pass-1");
            assertTrue(list2.contains("CAMELLIA128") || list2.contains("cipher 11"), list2);
            assertFalse(list2.contains("compressed packet"), list2);
            assertTrue(Files.readString(w.resolve("out/plain-pack.gpg")).startsWith("-----BEGIN PGP MESSAGE-----"));

            // A wrong passphrase cannot decrypt; the right one can.
            String bad = code(() -> pgp.decrypt(c, "out/pay.csv.gpg", "out/bad.csv", new SecureString("wrong", "test/pgp/wrong"), "keys", null, true));
            assertTrue(bad.equals("PGP.BAD_PASSPHRASE") || bad.equals("PGP.DECRYPT_FAILED"), bad);
            pgp.decrypt(c, "out/pay.csv.gpg", "out/back.csv", pass, "keys", null, true);
            assertEquals(Files.readString(w.resolve("pay.csv")), Files.readString(w.resolve("out/back.csv")));
        } finally { killAgent(home); killAgent(w.resolve("check")); }
    }

    @Test void recipientsAndPassphraseTogether() throws Exception {
        if (noGpg()) return;
        Path w = PackagesTest.tmp();
        ActionContext c = ctx(w);
        SecureString keyPass = new SecureString("key-pass", "test/pgp/key"), filePass = new SecureString("file-pass", "test/pgp/file");
        Files.writeString(w.resolve("pay.csv"), "Ann,5000\n");
        try {
            pgp.createKeys(c, "Options Agent", "options-agent@ooa.test", keyPass, null, "ed25519", "1d", "k/public.asc", "k/private.asc", "maker");
            pgp.encrypt(c, "pay.csv", "out/both.pgp", "options-agent@ooa.test", false, "maker", null, true, "AES192", "zlib", filePass);
            String list = packets(w.resolve("check"), w.resolve("out/both.pgp"), "file-pass");
            assertTrue(list.contains(":pubkey enc packet:"), list);
            assertTrue(list.contains(":symkey enc packet:"), list);
            assertTrue(list.contains("compressed packet: algo=2"), list);             // 2 = ZLIB
            // The passphrase alone decrypts, in a keyring without the private key.
            pgp.decrypt(c, "out/both.pgp", "out/by-pass.csv", filePass, "empty", null, true);
            assertEquals("Ann,5000\n", Files.readString(w.resolve("out/by-pass.csv")));
            // Neither recipients nor passphrase.
            assertEquals("PGP.NO_RECIPIENT", code(() -> pgp.encrypt(c, "pay.csv", "x.pgp", null, false, "maker", null, true, "AES256", null, null)));
        } finally { killAgent(w.resolve("maker")); killAgent(w.resolve("check")); killAgent(w.resolve("empty")); }
    }
}
