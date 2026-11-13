package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.vault.VaultAdmin;
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
            // an event subscription's signing secret (rotation missed it before round 4)
            String whsec = (String) cr.events.save(admin, null, Map.of("name", "hook", "url", "https://93.184.216.34/hook", "events", java.util.List.of("*")), null).get("secret");
            assertEquals("CR.VAULT_KEY", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> cr.vaultAdmin.rotate(admin, null)).code(),
                    "the console cannot change a key kept outside");

            Map<String, Object> r = cr.vaultAdmin.rekey(newKey);
            assertTrue(((Number) r.get("re_encrypted")).intValue() >= 1);
            assertEquals(Boolean.FALSE, r.get("key_file"));
            assertFalse(Files.exists(data.resolve("master.key")), "no key file is written for an outside key");

            String stored = (String) db.one("SELECT ciphertext FROM credentials WHERE locker = 'ops' AND name = 'erp' AND attribute = 'password'").get("ciphertext");
            assertEquals("s3cret-erp", Vault.ofKey(newKey, "new").decrypt(stored, "ops/erp/password"), "the new key reads it");
            assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> Vault.ofKey(oldKey, "old").decrypt(stored, "ops/erp/password"), "the old key no longer does");
            Map<String, Object> ev = db.one("SELECT id, secret FROM event_subscriptions");
            assertEquals(whsec, Vault.ofKey(newKey, "new").decrypt((String) ev.get("secret"), "event-subscription:" + ev.get("id")), "the event subscription's secret moved to the new key");
            assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'vault.rekey'"));
        } finally { db.close(); }
    }

    /** Two key changes in the same clock tick (the Windows clock moves in steps of up to ~16 ms) keep both old keys. */
    @Test void sameTimestampDoesNotOverwriteAnOldKey() throws Exception {
        Path dir = Files.createTempDirectory("ooa-oldkey");
        Path key = dir.resolve("master.key");
        Files.writeString(key, "first");
        Path a = VaultAdmin.keepOldKey(key, false, "2026-10-31T050033.557Z");
        Files.writeString(key, "second");
        Path b = VaultAdmin.keepOldKey(key, false, "2026-10-31T050033.557Z");
        Files.writeString(key, "third");
        Path c = VaultAdmin.keepOldKey(key, true, "2026-10-31T050033.557Z");
        assertEquals("master.key.old-2026-10-31T050033.557Z", a.getFileName().toString());
        assertEquals("master.key.old-2026-10-31T050033.557Z-2", b.getFileName().toString());
        assertEquals("master.key.old-2026-10-31T050033.557Z-3", c.getFileName().toString());
        assertEquals("first", Files.readString(a), "the first old key is not overwritten");
        assertEquals("second", Files.readString(b));
        assertEquals("third", Files.readString(c));
        assertFalse(Files.exists(key), "rotate moves the key away");
    }

    /**
     * Key changes in quick succession each keep their old key: on Windows two changes can share a millisecond, which
     * once made the second one fail on an existing master.key.old-&lt;time&gt; (and an old key must never be overwritten).
     */
    @Test void quickKeyChangesKeepEveryOldKey() throws Exception {
        Path data = Files.createTempDirectory("ooa-rekey-file");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null, new Vault(data), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.createLocker(admin, "ops", "Operations", null, null);
            cr.setCredential(admin, "ops", "erp", "password", "s3cret-erp", null);
            java.util.Set<String> keys = new java.util.HashSet<>();
            keys.add(Files.readString(data.resolve("master.key")).trim());
            for (int i = 0; i < 4; i++) {
                if (i % 2 == 0) cr.vaultAdmin.rotate(admin, null); else cr.vaultAdmin.rekey(key());
                keys.add(Files.readString(data.resolve("master.key")).trim());
            }
            java.util.List<Path> old;
            try (var s = Files.list(data)) { old = s.filter(f -> f.getFileName().toString().startsWith("master.key.old-")).toList(); }
            assertEquals(4, old.size(), "one old key per change: " + old);
            java.util.Set<String> kept = new java.util.HashSet<>();
            for (Path f : old) kept.add(Files.readString(f).trim());
            assertEquals(4, kept.size(), "every old key is a different key");
            keys.remove(Files.readString(data.resolve("master.key")).trim());
            assertEquals(keys, kept, "the old keys are exactly the keys used before");
            assertFalse(Files.exists(data.resolve("master.key.next")), "no new-key file is left behind");
            String stored = (String) db.one("SELECT ciphertext FROM credentials WHERE locker = 'ops' AND name = 'erp' AND attribute = 'password'").get("ciphertext");
            assertEquals("s3cret-erp", new Vault(data).decrypt(stored, "ops/erp/password"), "the key file reads the values");
        } finally { db.close(); }
    }
}
