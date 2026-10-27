package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The audit trail resists rewriting (keyed chain, outside checkpoints), keeps a verifiable cut after retention, and survives vault key rotation. */
class AuditProtectionTest {
    Path data; Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeEach void start() throws Exception {
        data = Files.createTempDirectory("ooa-audit");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        for (int i = 0; i < 5; i++) cr.audit.record("admin", "test.event", "thing", "t" + i, Map.of("n", i), "ok", null);
    }
    @AfterEach void stop() { cr.triggerPollers.stop(); db.close(); }

    Map<String, Object> verify() { return cr.audit.verify(); }

    /** What someone with database access but without the key would do: change an entry and re-hash the rest with SHA-256. */
    void rewriteWithoutKey(long seq) {
        db.update("UPDATE audit SET details = ? WHERE seq = ?", "{\"n\":99}", seq);
        String prev = (String) db.one("SELECT prev_hash FROM audit WHERE seq = ?", seq).get("prev_hash");
        for (Map<String, Object> r : db.query("SELECT * FROM audit WHERE seq >= ? ORDER BY seq", seq)) {
            String ts = Instant.parse(String.valueOf(r.get("ts"))).toString();
            String h = Security.sha256(prev + "|" + r.get("seq") + "|" + ts + "|" + r.get("actor") + "|" + r.get("action") + "|" + r.get("object_type") + "|" + r.get("object_id") + "|"
                + (r.get("details") == null ? "" : r.get("details")) + "|" + r.get("result"));
            db.update("UPDATE audit SET prev_hash = ?, hash = ?, alg = NULL WHERE seq = ?", prev, h, r.get("seq"));
            prev = h;
        }
    }

    /** The same by someone who also has the key. */
    void rewriteWithKey(long seq) {
        db.update("UPDATE audit SET details = ? WHERE seq = ?", "{\"n\":77}", seq);
        String prev = (String) db.one("SELECT prev_hash FROM audit WHERE seq = ?", seq).get("prev_hash");
        for (Map<String, Object> r : db.query("SELECT * FROM audit WHERE seq >= ? ORDER BY seq", seq)) {
            String ts = Instant.parse(String.valueOf(r.get("ts"))).toString();
            String h = Audit.hmac(cr.auditKey(), prev + "|" + r.get("seq") + "|" + ts + "|" + r.get("actor") + "|" + r.get("action") + "|" + r.get("object_type") + "|" + r.get("object_id") + "|"
                + (r.get("details") == null ? "" : r.get("details")) + "|" + r.get("result"));
            db.update("UPDATE audit SET prev_hash = ?, hash = ? WHERE seq = ?", prev, h, r.get("seq"));
            prev = h;
        }
    }

    long seqOf(String objectId) { return ((Number) db.one("SELECT seq FROM audit WHERE object_id = ?", objectId).get("seq")).longValue(); }

    @Test void theChainIsKeyedAndARewriteWithoutTheKeyIsCaught() {
        Map<String, Object> v = verify();
        assertEquals(true, v.get("valid"), String.valueOf(v));
        assertEquals(true, v.get("keyed"));
        assertEquals("h1", db.one("SELECT alg FROM audit WHERE object_id = 't2'").get("alg"));
        rewriteWithoutKey(seqOf("t2"));
        Map<String, Object> bad = verify();
        assertEquals(false, bad.get("valid"));
        assertEquals(seqOf("t2"), ((Number) bad.get("broken_at")).longValue());
    }

    @Test void aRebuildWithTheKeyIsCaughtByTheCheckpoints() throws Exception {
        cr.audit.checkpointIfDue();
        assertTrue(Files.readString(data.resolve("audit-checkpoints.log")).trim().split("\\s+").length >= 3);
        assertNotNull(db.one("SELECT seq FROM audit WHERE action = 'audit.checkpoint'"), "the checkpoint is also an audit entry (sent to the SIEM)");
        assertEquals(1, verify().get("checkpoints"));
        cr.audit.checkpointIfDue();
        assertEquals(1, Files.readAllLines(data.resolve("audit-checkpoints.log")).size(), "at most one an hour");
        rewriteWithKey(seqOf("t1"));
        Map<String, Object> bad = verify();
        assertEquals(false, bad.get("valid"), String.valueOf(bad));
        assertTrue(String.valueOf(bad.get("reason")).contains("checkpoint"), String.valueOf(bad));
    }

    @Test void retentionArchivesThenCutsTheChainVerifiably() throws Exception {
        db.update("UPDATE audit SET ts = ? WHERE seq <= ?", Instant.now().minus(Duration.ofDays(400)), seqOf("t2"));
        int removed = cr.audit.purgeOlderThan(365);
        assertEquals(seqOf("t3") - 1, removed);
        Path archive = data.resolve("audit-archive").resolve("audit-through-" + removed + ".jsonl.gz");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(archive)), StandardCharsets.UTF_8))) {
            assertEquals(removed, r.lines().count(), "every removed entry is in the archive");
        }
        Map<String, Object> v = verify();
        assertEquals(true, v.get("valid"), String.valueOf(v));
        assertEquals((long) removed, ((Number) v.get("archived_through")).longValue());
        // removing more from the start without an archive record breaks it
        db.update("DELETE FROM audit WHERE seq = ?", seqOf("t3"));
        assertEquals(false, verify().get("valid"));
        assertEquals("CR.BAD_INPUT", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("audit_retention_days", 30), null)).code());
    }

    @Test void vaultKeyRotationKeepsEveryEncryptedValueReadable() throws Exception {
        String installId = cr.licensing.installId();
        cr.licensing.evaluationStart();
        RepositoryLifecycleTest.person(cr, admin, "dana", "bot_creator");
        String danaId = (String) db.one("SELECT id FROM users WHERE username = 'dana'").get("id");
        cr.mfa.setup(new ControlRoom.User(danaId, "dana", "bot_creator", "dana", false), null);
        Path dbFile = DbConfig.defaultFile(data), tlsFile = TlsConfig.defaultFile(data);
        Files.createDirectories(dbFile.getParent());
        DbConfig.setValue(dbFile, "engine", "sqlserver");
        DbConfig.setValue(dbFile, "password", "enc:" + cr.vault.encrypt("Db-Secret-1", "ooa-db:password"));
        Files.writeString(data.resolve("server.pfx"), "placeholder");
        TlsConfig.setKeystore(tlsFile, data.resolve("server.pfx"));
        TlsConfig.storePassword(tlsFile, cr.vault, "Tls-Secret-2");

        cr.vaultAdmin.rotate(admin, null);

        assertEquals(installId, cr.licensing.installId(), "the install id survives (a bound license keeps working)");
        assertNotEquals(LocalDate.of(2000, 1, 1), cr.licensing.evaluationStart(), "the evaluation start survives");
        assertNotNull(cr.vault.decrypt((String) db.one("SELECT mfa_secret FROM users WHERE id = ?", danaId).get("mfa_secret"), "mfa:" + danaId), "two-step secrets stay readable");
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(dbFile)) { p.load(r); }
        assertEquals("Db-Secret-1", cr.vault.decrypt(p.getProperty("password").substring(4), "ooa-db:password"), "the database password in the config file");
        assertTrue(Files.exists(dbFile.resolveSibling("ooa-db.properties.before-rotate")));
        assertEquals("Tls-Secret-2", TlsConfig.load(tlsFile).password(cr.vault) , "the certificate password");
        cr.audit.record("admin", "after.rotate", "thing", "x", null, "ok", null);
        assertEquals(true, verify().get("valid"), "the audit chain key survives too");
    }
}
