package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ooa vault rekey with a master key kept outside the data folder (OOA_MASTER_KEY or a key store), which the console's
 * Rotate refuses: every stored value is re-encrypted with the key the administrator brings, and only that key reads it.
 */
class VaultRekeyTest {
    static byte[] key() { byte[] k = new byte[32]; new SecureRandom().nextBytes(k); return k; }

    @Test void rekeyWithAnOutsideKey() throws Exception {
        Path data = Files.createTempDirectory("ooa-rekey");
        Db db = Db.embedded(data);
        try {
            byte[] oldKey = key(), newKey = key();
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null, Vault.ofKey(oldKey, "environment variable OOA_MASTER_KEY"), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.createLocker(admin, "ops", "Operations", null, null);
            cr.setCredential(admin, "ops", "erp", "password", "s3cret-erp", null);
            assertEquals("CR.VAULT_KEY", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> cr.vaultAdmin.rotate(admin, null)).code(),
                    "the console cannot change a key kept outside");

            Map<String, Object> r = cr.vaultAdmin.rekey(newKey);
            assertTrue(((Number) r.get("re_encrypted")).intValue() >= 1);
            assertEquals(Boolean.FALSE, r.get("key_file"));
            assertFalse(Files.exists(data.resolve("master.key")), "no key file is written for an outside key");

            String stored = (String) db.one("SELECT ciphertext FROM credentials WHERE locker = 'ops' AND name = 'erp' AND attribute = 'password'").get("ciphertext");
            assertEquals("s3cret-erp", Vault.ofKey(newKey, "new").decrypt(stored, "ops/erp/password"), "the new key reads it");
            assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> Vault.ofKey(oldKey, "old").decrypt(stored, "ops/erp/password"), "the old key no longer does");
            assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'vault.rekey'"));
        } finally { db.close(); }
    }
}
