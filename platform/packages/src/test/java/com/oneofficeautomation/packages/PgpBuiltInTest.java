package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 (command review, batch 8): PGP without GnuPG. Every command of the toolkit, through its own entry points, with
 * the built-in engine chosen; key folders are fresh temporary folders, so nothing touches the runner's keyring.
 */
class PgpBuiltInTest {
    static final PgpPackage PGP = new PgpPackage();
    static final SecureString ANN = new SecureString("ann-secret-1", "t"), WRONG = new SecureString("not-it", "t");
    static Path w;
    static ActionContext ctx;

    @BeforeAll static void builtIn() throws Exception {
        System.setProperty("ooa.pgp.engine", "builtin");
        w = Files.createTempDirectory("ooa-pgp");
        ctx = PackagesTestSupport.ctx(w, Map.of());
        Files.writeString(w.resolve("report.csv"), "id,amount\n1,42.50\n");
    }

    @AfterAll static void back() { System.clearProperty("ooa.pgp.engine"); }

    static ActionException fails(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e); }

    @Test void ed25519KeysEncryptDecryptSignAndVerify() throws Exception {
        Map<String, Object> k = PGP.createKeys(ctx, "Ann Agent", "ann@acme.test", ANN, null, "ed25519", "1y", "ann.pub.asc", "ann.key.asc", "ann-keys", true);
        String fpr = (String) k.get("fingerprint");
        assertEquals(40, fpr.length());
        assertEquals("Ann Agent <ann@acme.test>", k.get("user_id"));
        assertTrue(Files.readString(w.resolve("ann.pub.asc")).startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"));

        // encrypt for the key in the folder (by email), decrypt with its passphrase
        PGP.encrypt(ctx, "report.csv", "report.csv.pgp", "ann@acme.test", false, "ann-keys", null, true, "AES256", "zlib", null);
        assertNotEquals("id,amount\n1,42.50\n", Files.readString(w.resolve("report.csv.pgp"), java.nio.charset.StandardCharsets.ISO_8859_1));
        PGP.decrypt(ctx, "report.csv.pgp", "back.csv", ANN, "ann-keys", null, true);
        assertEquals("id,amount\n1,42.50\n", Files.readString(w.resolve("back.csv")));
        assertEquals("PGP.BAD_PASSPHRASE", fails(() -> PGP.decrypt(ctx, "report.csv.pgp", "again.csv", WRONG, "ann-keys", null, true)).code());
        assertEquals("PGP.DECRYPT_FAILED", fails(() -> PGP.decrypt(ctx, "report.csv.pgp", "x.csv", ANN, "empty-keys", null, true)).code(), "no matching key");

        // sign with the key, verify; a changed file does not verify
        PGP.sign(ctx, "report.csv", "report.csv.sig", "ann@acme.test", ANN, "ann-keys");
        Map<String, Object> v = PGP.verify(ctx, "report.csv", "report.csv.sig", "ann-keys");
        assertEquals(true, v.get("valid"));
        assertEquals("Ann Agent <ann@acme.test>", v.get("signer"));
        Files.writeString(w.resolve("changed.csv"), "id,amount\n1,99.00\n");
        assertEquals(false, PGP.verify(ctx, "changed.csv", "report.csv.sig", "ann-keys").get("valid"));
        assertEquals("PGP.BAD_PASSPHRASE", fails(() -> PGP.sign(ctx, "report.csv", "x.sig", null, WRONG, "ann-keys")).code());

        // a partner imports the public key into their own folder and checks the signature there
        @SuppressWarnings("unchecked") List<Object> imported = (List<Object>) PGP.importKey(ctx, "ann.pub.asc", null, "partner-keys").get("fingerprints");
        assertEquals(List.of(fpr), imported);
        assertEquals(true, PGP.verify(ctx, "report.csv", "report.csv.sig", "partner-keys").get("valid"));
        assertEquals("PGP.BAD_PASSPHRASE", fails(() -> PGP.importKey(ctx, "ann.key.asc", WRONG, "other-keys")).code(), "a wrong passphrase stops a private key import");
    }

    @Test void rsaKeysAndKeyFilesWithoutImporting() throws Exception {
        PGP.createKeys(ctx, "Bob Agent", "bob@acme.test", ANN, "rsa test", "rsa3072", "never", "bob.pub.asc", "bob.key.asc", "bob-keys", true);
        // encrypt with the public key file (no keyring), armored; decrypt with the private key file
        Map<String, Object> e = PGP.encrypt(ctx, "report.csv", null, null, true, "nobody-keys", "bob.pub.asc", true, "default", "none", null);
        Path asc = Path.of((String) e.get("target"));
        assertTrue(asc.toString().endsWith("report.csv.asc"));
        assertTrue(Files.readString(asc).startsWith("-----BEGIN PGP MESSAGE-----"));
        PGP.decrypt(ctx, asc.toString(), "bob-back.csv", ANN, "nobody-keys", "bob.key.asc", true);
        assertEquals("id,amount\n1,42.50\n", Files.readString(w.resolve("bob-back.csv")));
        assertEquals("PGP.ENCRYPT_FAILED", fails(() -> PGP.encrypt(ctx, "report.csv", "y.pgp", "carol@acme.test", false, "bob-keys", null, true, null, null, null)).code(),
                "a recipient with no key in the folder");
    }

    @Test void passphraseOnly() throws Exception {
        SecureString pass = new SecureString("shared phrase", "t");
        PGP.encrypt(ctx, "report.csv", "sym.pgp", null, false, "sym-keys", null, true, "AES128", "bzip2", pass);
        PGP.decrypt(ctx, "sym.pgp", "sym.csv", pass, "sym-keys", null, true);
        assertEquals("id,amount\n1,42.50\n", Files.readString(w.resolve("sym.csv")));
        assertEquals("PGP.BAD_PASSPHRASE", fails(() -> PGP.decrypt(ctx, "sym.pgp", "sym2.csv", WRONG, "sym-keys", null, true)).code());
        assertEquals("PGP.NO_RECIPIENT", fails(() -> PGP.encrypt(ctx, "report.csv", "z.pgp", null, false, "sym-keys", null, true, null, null, null)).code());
    }
}
