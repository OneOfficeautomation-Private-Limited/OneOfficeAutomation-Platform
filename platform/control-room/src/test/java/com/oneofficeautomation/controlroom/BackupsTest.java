package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Backups: everything comes back on a fresh install with the same vault key; the key itself is never in a backup. */
class BackupsTest {

    @Test @SuppressWarnings("unchecked")
    void aBackupRestoresIntoANewCommandCenter() throws Exception {
        Path data = Files.createTempDirectory("ooa-backup-src");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Fin/Pay.flow.json", LicensingAndChecksTest.agent("pay"), "v1", true, null);
        cr.createLocker(admin, "fin", "Finance", "user:admin", null);
        cr.setCredential(admin, "fin", "erp", "password", "Erp-Secret-9", null);
        cr.repoFiles.put(admin, "Fin/rates.csv", "eur,1.1".getBytes(StandardCharsets.UTF_8), "text/csv", null);
        String installId = cr.licensing.installId();

        Map<String, Object> b = cr.backups.backupNow(admin, null);
        Path zip = data.resolve("backups").resolve((String) b.get("file"));
        assertTrue(Files.exists(zip));
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertNotNull(z.getEntry("manifest.json"));
            assertNotNull(z.getEntry("db/bots.jsonl"));
            assertTrue(z.stream().anyMatch(e -> e.getName().startsWith("files/repo-files/")), "the repository files (kept under their content hash)");
            assertTrue(z.stream().noneMatch(e -> e.getName().contains("master.key")), "the vault key is never in a backup");
        }
        assertEquals(1, cr.backups.list().size());
        cr.triggerPollers.stop();
        db.close();

        // a new Command Center with the same vault key (copied, as the docs say)
        Path data2 = Files.createTempDirectory("ooa-backup-dst");
        Files.copy(data.resolve("master.key"), data2.resolve("master.key"));
        try (Db target = Db.h2File(data2)) {
            Map<String, Object> r = Backups.restore(zip, target, data2, new Vault(data2), false);
            assertTrue(((Map<String, Object>) r.get("tables")).get("bots") instanceof Number n && n.intValue() == 1);
        }
        Db db2 = Db.h2File(data2);
        try {
            ControlRoom cr2 = new ControlRoom(db2, data2, FirstPartyPackages.registry(), null);
            assertNull(cr2.bootstrap(), "the restored users are there");
            ControlRoom.User admin2 = cr2.session((String) cr2.login("admin", "Admin-Passw0rd-2026", null).get("token"));
            assertNotNull(cr2.content("Fin/Pay.flow.json", "production"));
            assertEquals("Erp-Secret-9", cr2.systemCredential("fin/erp/password"), "vault values decrypt with the same key");
            assertEquals("eur,1.1", new String((byte[]) cr2.repoFiles.get(admin2, "Fin/rates.csv").get("content"), StandardCharsets.UTF_8));
            assertEquals(installId, cr2.licensing.installId());
            assertEquals(true, cr2.audit.verify().get("valid"), "the audit chain came back whole");
            cr2.triggerPollers.stop();
        } finally { db2.close(); }

        // another key, or a database with rows, is refused
        Path data3 = Files.createTempDirectory("ooa-backup-other");
        try (Db other = Db.h2File(data3)) {
            OoaException e = assertThrows(OoaException.class, () -> Backups.restore(zip, other, data3, new Vault(data3), false));
            assertTrue(e.getMessage().contains("another vault key"), e.getMessage());
        }
        try (Db full = Db.h2File(data2)) {
            assertTrue(assertThrows(OoaException.class, () -> Backups.restore(zip, full, data2, new Vault(data2), false)).getMessage().contains("not empty"));
            assertNotNull(Backups.restore(zip, full, data2, new Vault(data2), true), "with replace it goes ahead");
        }
    }

    @Test void scheduledBackupsAndKeepDays() throws Exception {
        Path data = Files.createTempDirectory("ooa-backup-sched");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.backups.tickIfDue();
            assertEquals(0, cr.backups.list().size(), "off by default");
            cr.platform.updateSettings(admin, Map.of("backup_hours", 4, "backup_keep_days", 7), null);
            cr.backups.tickIfDue();
            assertEquals(1, cr.backups.list().size());
            cr.backups.tickIfDue();
            assertEquals(1, cr.backups.list().size(), "not again within 4 hours");
            // an old backup beyond the keep days goes; the newest always stays
            Files.writeString(data.resolve("backups").resolve("ooa-backup-20200101-000000.zip"), "old");
            assertEquals(1, cr.backups.prune());
            assertEquals(1, cr.backups.list().size());
            assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.backups.backupNow(RepositoryLifecycleTest.person(cr, admin, "vic", "viewer"), null)).code());
            cr.triggerPollers.stop();
        } finally { db.close(); }
    }
}
