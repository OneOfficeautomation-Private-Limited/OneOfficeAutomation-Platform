package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.controlroom.vault.VaultAdmin;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Round 4 item A4: an on-premises One CommandCenter moves into a SaaS tenant. Every kind of stored secret survives
 * (each decrypts with the tenant's key, bound as its column expects), the runner keeps its identity, the repository
 * file comes along, the Audit Trail becomes a read-only archive; and a wrong passphrase, a tenant with data, another
 * schema version and a damaged file are refused with nothing changed.
 */
class TenantMoveTest {
    static final String PASS = "correct horse battery staple", TOKEN = "ooaop_test-operator-token";

    /** What the on-premises install holds, to find again in the tenant. */
    record OnPrem(Path data, ControlRoom cr, String deviceId, String deviceSecret, String eventSecret, String adminId) { }

    static ControlRoom.User signIn(ControlRoom c, String oneTime) {
        String t0 = (String) c.login("admin", oneTime, null).get("token");
        c.changePassword(c.session(t0), oneTime, "Admin-Passw0rd-2026", t0, null);
        return c.session((String) c.login("admin", "Admin-Passw0rd-2026", null).get("token"));
    }

    @SuppressWarnings("unchecked")
    static OnPrem onPrem() throws Exception {
        Path data = Files.createTempDirectory("ooa-onprem");
        ControlRoom cr = new ControlRoom(Db.embedded(data), data, FirstPartyPackages.registry(), null);
        ControlRoom.User admin = signIn(cr, cr.bootstrap());
        String adminId = (String) cr.db.one("SELECT id FROM users WHERE username = 'admin'").get("id");
        // an agent, a repository file
        cr.checkin(admin, "Fin/Pay.flow.json", (Map<String, Object>) JsonLite.parse(
            "{\"kind\":\"TaskBot\",\"id\":\"pay\",\"name\":\"pay\",\"description\":\"t\",\"variables\":[],\"steps\":[]}"), "v1", true, null);
        cr.repoFiles.put(admin, "Fin/rates.csv", "currency,rate\nEUR,1.08\n".getBytes(StandardCharsets.UTF_8), "text/csv", null);
        // a shared vault value and a person's own value
        cr.createLocker(admin, "Fin", "Finance", null, null);
        cr.setCredential(admin, "Fin", "Bank", "password", "shared-s3cret", null);
        cr.db.update("INSERT INTO credential_user_values (locker, name, attribute, username, ciphertext, updated) VALUES (?,?,?,?,?,?)",
            "Fin", "Bank", "username", "admin", cr.vault.encrypt("my-own-name", VaultAdmin.userBound("Fin/Bank/username", "admin")), Instant.now());
        // an external vault link with its sign-in secret
        cr.createLocker(admin, "Ext", "External", null, null);
        cr.db.update("UPDATE lockers SET external = ?, external_secret = ? WHERE name = 'Ext'",
            JsonLite.write(Map.of("provider", "hashicorp", "url", "https://vault.corp.example")), cr.vault.encrypt("ext-token", "external:Ext"));
        // an encrypted queue's work item, and a plain one
        cr.db.update("INSERT INTO queues (id, name, max_attempts, processing, created) VALUES ('q1', 'Invoices', 3, FALSE, ?)", Instant.now());
        cr.db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated) VALUES ('qi1', 'q1', ?, 'new', 1, 0, ?, ?)",
            cr.vault.encrypt("{\"iban\":\"DE00\"}", "queue-item:qi1"), Instant.now(), Instant.now());
        cr.db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated) VALUES ('qi2', 'q1', '{\"n\":2}', 'new', 1, 0, ?, ?)", Instant.now(), Instant.now());
        // two-step sign-in, a phone notification topic, an event subscription
        cr.db.update("UPDATE users SET mfa_secret = ? WHERE id = ?", cr.vault.encrypt("JBSWY3DPEHPK3PXP", "mfa:" + adminId), adminId);
        cr.db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", "push_topic:admin",
            JsonLite.write(cr.vault.encrypt("ooa-topic-1", "push_topic:admin")), Instant.now(), "admin");
        Map<String, Object> ev = cr.events.save(admin, null, Map.of("name", "hook", "url", "https://93.184.216.34/hook", "events", List.of("*")), null);
        // a runner
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "PC-7", "pc-7", "Windows 11", "0.2.0", null);
        return new OnPrem(data, cr, (String) dev.get("device_id"), (String) dev.get("secret"), (String) ev.get("secret"), adminId);
    }

    static Path export(OnPrem p, boolean history) throws IOException {
        Path file = p.data().resolveSibling(p.data().getFileName() + ".ooamove");
        TenantMove.export(p.cr().db, p.cr().vault, p.data(), file, PASS.toCharArray(), history);
        return file;
    }

    @Test @SuppressWarnings("unchecked")
    void anOnPremisesInstallMovesIntoATenant() throws Exception {
        OnPrem p = onPrem();
        Path regionData = Files.createTempDirectory("ooa-region");
        Db base = Db.embedded(regionData);
        assumeFalse(base.sqlServer, "tenants need PostgreSQL or H2");
        try (Tenants region = new Tenants(base, regionData, Vault.ofKey(new byte[32], "test"), "in", "in.ooa.test", FirstPartyPackages::registry, null)) {
            TenantHost host = new TenantHost(region, "127.0.0.1", 0, RegionCommand.sha256(TOKEN));
            host.start();
            try {
                // the export, with the command line
                Path file = p.data().resolveSibling("acme.ooamove");
                TenantCommand.env = v -> v.equals("MOVE_PASS") ? PASS : v.equals("OP_TOKEN") ? TOKEN : null;
                assertEquals(0, TenantCommand.export(Map.of("data", p.data().toString(), "out", file.toString(), "password-env", "MOVE_PASS")));
                byte[] raw = Files.readAllBytes(file);
                assertFalse(new String(raw, StandardCharsets.ISO_8859_1).contains("shared-s3cret"), "nothing readable without the passphrase");

                // the import through the operator API; the region reaches itself as localhost
                PrintStream was = System.out;
                ByteArrayOutputStream printed = new ByteArrayOutputStream();
                System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
                try {
                    assertEquals(0, TenantCommand.importInto(Map.of("file", file.toString(), "password-env", "MOVE_PASS", "url", "http://localhost:" + host.port(),
                        "token-env", "OP_TOKEN", "name", "Acme Ltd", "seats", "unattended_runner=5"), List.of("acme")));
                } finally { System.setOut(was); }
                String report = printed.toString(StandardCharsets.UTF_8);
                assertTrue(report.contains("Imported into https://acme.in.ooa.test/"), report);
                assertTrue(report.contains("every table as exported"), report);
                assertTrue(report.contains("Secrets moved and encrypted with the tenant's key: 7"), report);
                assertTrue(report.contains("ooa agent move --url https://acme.in.ooa.test"), report);
                assertTrue(report.contains("table audit"), report);
                assertTrue(report.contains("links to an external vault"), report);

                ControlRoom t = region.serve("acme").cr;
                Vault v = t.vault;
                assertNotEquals(p.cr().vault.fingerprint(), v.fingerprint());
                // every secret, with its binding, under the tenant's key
                assertEquals("shared-s3cret", v.decrypt((String) t.db.one("SELECT ciphertext FROM credentials WHERE locker = 'Fin'").get("ciphertext"), "Fin/Bank/password"));
                assertEquals("my-own-name", v.decrypt((String) t.db.one("SELECT ciphertext FROM credential_user_values").get("ciphertext"), VaultAdmin.userBound("Fin/Bank/username", "admin")));
                assertEquals("ext-token", v.decrypt((String) t.db.one("SELECT external_secret FROM lockers WHERE name = 'Ext'").get("external_secret"), "external:Ext"));
                assertEquals("{\"iban\":\"DE00\"}", v.decrypt((String) t.db.one("SELECT data FROM queue_items WHERE id = 'qi1'").get("data"), "queue-item:qi1"));
                assertEquals("{\"n\":2}", t.db.one("SELECT data FROM queue_items WHERE id = 'qi2'").get("data"), "a plain work item stays plain");
                assertEquals("JBSWY3DPEHPK3PXP", v.decrypt((String) t.db.one("SELECT mfa_secret FROM users WHERE id = ?", p.adminId()).get("mfa_secret"), "mfa:" + p.adminId()));
                assertEquals("ooa-topic-1", v.decrypt(String.valueOf(JsonLite.parse((String) t.db.one("SELECT json_value FROM settings WHERE name = 'push_topic:admin'").get("json_value"))), "push_topic:admin"));
                String evId = (String) t.db.one("SELECT id FROM event_subscriptions").get("id");
                assertEquals(p.eventSecret(), v.decrypt((String) t.db.one("SELECT secret FROM event_subscriptions").get("secret"), "event-subscription:" + evId));

                // people keep their passwords; the agent and the file are there
                assertNotNull(t.login("admin", "Admin-Passw0rd-2026", null).get("token"));
                ControlRoom.User admin = t.session((String) t.login("admin", "Admin-Passw0rd-2026", null).get("token"));
                assertEquals("currency,rate\nEUR,1.08\n", new String((byte[]) t.repoFiles.get(admin, "Fin/rates.csv").get("content"), StandardCharsets.UTF_8));
                assertTrue(t.repoFiles.store().startsWith(regionData), t.repoFiles.store().toString());
                assertNotNull(t.db.one("SELECT path FROM bots WHERE path = 'Fin/Pay.flow.json'"));
                assertEquals("https://acme.in.ooa.test", t.platform.setting("public_url"), "the tenant keeps its own address");

                // the runner keeps its identity and gets work
                Map<String, Object> d = t.device(p.deviceId(), p.deviceSecret());
                assertNotNull(d, "the runner signs in to the tenant with its old registration");
                t.startRun("admin", "admin", "Fin/Pay.flow.json", p.deviceId(), Map.of(), "manual", null);
                assertTrue(String.valueOf(t.nextJob(d, 0).get("job")).contains("pay"));

                // the Audit Trail: an archive in the tenant's folder, and one import event in the tenant's own chain
                Path imported = t.dataDir.resolve("imported");
                try (var s = Files.list(imported)) {
                    Path a = s.filter(x -> x.getFileName().toString().matches("audit-\\d{8}-\\d{6}\\.jsonl")).findFirst().orElseThrow();
                    assertTrue(Files.readString(a).contains("locker.create"));
                    assertFalse(Files.isWritable(a), "the archive is read-only");
                }
                assertNotNull(t.db.one("SELECT action FROM audit WHERE action = 'tenant.import'"));
                assertNull(t.db.one("SELECT action FROM audit WHERE action = 'locker.create'"), "old events do not join the tenant's chain");

                // the region's seats, not the install's licences
                assertEquals(5, ((Number) region.get("acme").seats().get("unattended_runner")).intValue());

                // the tenant's own key handling reads every moved secret through the production code
                assertTrue(((Number) t.vaultAdmin.rekey(Base64.getDecoder().decode(Vault.fresh().keyBase64())).get("re_encrypted")).intValue() >= 7);
            } finally { host.stop(); TenantCommand.env = System::getenv; }
        } finally { base.close(); p.cr().db.close(); }
    }

    @Test
    void refusedImportsChangeNothing() throws Exception {
        OnPrem p = onPrem();
        Path file = export(p, true);
        Path regionData = Files.createTempDirectory("ooa-region");
        Db base = Db.embedded(regionData);
        assumeFalse(base.sqlServer, "tenants need PostgreSQL or H2");
        try (Tenants region = new Tenants(base, regionData, Vault.ofKey(new byte[32], "test"), "in", "in.ooa.test", FirstPartyPackages::registry, null)) {
            // a wrong passphrase: no tenant is made
            try (InputStream in = Files.newInputStream(file)) {
                assertEquals("MOVE.PASSPHRASE", assertThrows(OoaException.class,
                    () -> region.importTenant("acme", "Acme", null, Map.of(), in, "wrong passphrase!".toCharArray(), "op")).code());
            }
            assertNull(region.get("acme"));

            // a tenant that already has data
            Map<String, Object> made = region.create("globex", "Globex", null, Map.of(), "op");
            ControlRoom g = region.serve("globex").cr;
            ControlRoom.User ga = signIn(g, (String) made.get("admin_password"));
            g.createLocker(ga, "Mine", "theirs", null, null);
            try (InputStream in = Files.newInputStream(file)) {
                assertEquals("SAAS.TENANT_NOT_EMPTY", assertThrows(OoaException.class,
                    () -> region.importTenant("globex", null, null, null, in, PASS.toCharArray(), "op")).code());
            }
            assertEquals("active", region.get("globex").status());
            assertNotNull(region.serve("globex").cr.db.one("SELECT name FROM lockers WHERE name = 'Mine'"));

            // another schema version (the manifest is inside the encryption: rewrite it as a newer release would)
            Path newer = rewriteManifest(file, m -> m.put("schema", Db.LATEST + 1));
            try (InputStream in = Files.newInputStream(newer)) {
                OoaException e = assertThrows(OoaException.class, () -> region.importTenant("initech", "Initech", null, Map.of(), in, PASS.toCharArray(), "op"));
                assertEquals("MOVE.VERSION", e.code());
                assertTrue(e.getMessage().contains("schema " + (Db.LATEST + 1)), e.getMessage());
            }
            assertNull(region.get("initech"));

            // a file cut short: the new tenant is removed again
            byte[] all = Files.readAllBytes(file);
            Path cut = file.resolveSibling("cut.ooamove");
            Files.write(cut, Arrays.copyOf(all, all.length - 40));
            try (InputStream in = Files.newInputStream(cut)) {
                assertEquals("MOVE.DAMAGED", assertThrows(OoaException.class, () -> region.importTenant("initech", "Initech", null, Map.of(), in, PASS.toCharArray(), "op")).code());
            }
            assertNull(region.get("initech"));

            // a fresh tenant (only its first administrator) takes the move; the history came along
            region.create("hooli", "Hooli", null, Map.of(), "op");
            try (InputStream in = Files.newInputStream(file)) {
                Map<String, Object> r = region.importTenant("hooli", null, null, null, in, PASS.toCharArray(), "op");
                assertEquals(7L, ((Number) r.get("secrets")).longValue());
            }
            ControlRoom h = region.serve("hooli").cr;
            assertNotNull(h.device(p.deviceId(), p.deviceSecret()));
            assertEquals(1, h.db.query("SELECT username FROM users").size());
            assertNotNull(h.login("admin", "Admin-Passw0rd-2026", null).get("token"), "the on-premises admin replaces the tenant's first one");
        } finally { base.close(); p.cr().db.close(); }
    }

    @Test
    void theFileFormatChecksItself() throws Exception {
        assertEquals("MOVE.PASSPHRASE", assertThrows(OoaException.class,
            () -> TenantMove.export(null, null, null, Path.of("x"), "short".toCharArray(), false)).code());
        // larger than one chunk, so chunks are checked one after another
        byte[] content = new byte[(1 << 20) * 2 + 12345];
        new Random(7).nextBytes(content);
        ByteArrayOutputStream sealed = new ByteArrayOutputStream();
        try (OutputStream o = TenantMove.encrypt(sealed, PASS.toCharArray())) { o.write(content); }
        byte[] s = sealed.toByteArray();
        assertArrayEquals(content, TenantMove.decrypt(new ByteArrayInputStream(s), PASS.toCharArray()).readAllBytes());
        // a changed byte in the second chunk
        byte[] changed = s.clone();
        changed[36 + 4 + (1 << 20) + 16 + 4 + 100] ^= 1;
        assertEquals("MOVE.DAMAGED", assertThrows(OoaException.class, () -> TenantMove.decrypt(new ByteArrayInputStream(changed), PASS.toCharArray()).readAllBytes()).code());
        // the last chunk dropped: the file ends where a whole chunk ends, but not with the last one
        byte[] dropped = Arrays.copyOf(s, 36 + 2 * (4 + (1 << 20) + 16));
        assertEquals("MOVE.DAMAGED", assertThrows(OoaException.class, () -> TenantMove.decrypt(new ByteArrayInputStream(dropped), PASS.toCharArray()).readAllBytes()).code());
        assertEquals("MOVE.FORMAT", assertThrows(OoaException.class, () -> TenantMove.decrypt(new ByteArrayInputStream("PK not ours".getBytes()), PASS.toCharArray())).code());
    }

    /** Every place that encrypts with the vault is one a move (and a key rotation) knows about. */
    @Test
    void everyEncryptedColumnIsKnown() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Set<String> reviewed = Set.of(
            // the columns of TenantMove.SECRETS
            "ControlRoom.java", "vault/VaultAdmin.java", "DeviceLogin.java", "repository/AgentWrites.java", "runs/QueueTools.java", "auth/Mfa.java",
            "ApprovalChannels.java", "EventSubscriptions.java",
            // install values that stay with the install (settings evaluation_started, install_id; audit chain key in ControlRoom)
            "Licensing.java",
            // the region's catalog and configuration files: not tenant data
            "Tenants.java", "DbConfig.java", "TlsConfig.java", "docauto/DocFlowConfig.java");
        List<String> unknown = new ArrayList<>();
        for (String dir : List.of("control-room/src/main/java", "modules")) {
            try (var w = Files.walk(root.resolve(dir))) {
                for (Path f : w.filter(x -> x.toString().endsWith(".java") && !x.toString().contains(File.separator + "test" + File.separator)).toList()) {
                    String src = Files.readString(f);
                    if (!src.matches("(?s).*vault(\\(\\))?\\.encrypt\\(.*") || f.getFileName().toString().equals("TenantMove.java")) continue;
                    String rel = f.toString().replace('\\', '/');
                    if (reviewed.stream().noneMatch(rel::endsWith)) unknown.add(rel);
                }
            }
        }
        assertEquals(List.of(), unknown, "a new encrypted value: add it to TenantMove.SECRETS and VaultAdmin.reencryptAll, then to this list");
        Set<String> tables = new HashSet<>();
        for (TenantMove.Secret s : TenantMove.SECRETS) tables.add(s.table());
        assertEquals(Set.of("credentials", "credential_user_values", "lockers", "queue_items", "settings", "users", "event_subscriptions"), tables);
    }

    /** The move file with its manifest changed (for the version test). */
    @SuppressWarnings("unchecked")
    static Path rewriteManifest(Path file, java.util.function.Consumer<Map<String, Object>> change) throws IOException {
        Path out = file.resolveSibling("changed-" + file.getFileName());
        try (var zin = new java.util.zip.ZipInputStream(TenantMove.decrypt(Files.newInputStream(file), PASS.toCharArray()));
             var zout = new java.util.zip.ZipOutputStream(TenantMove.encrypt(Files.newOutputStream(out), PASS.toCharArray()))) {
            for (var e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                byte[] b = zin.readAllBytes();
                if (e.getName().equals("manifest.json")) {
                    Map<String, Object> m = (Map<String, Object>) JsonLite.parse(new String(b, StandardCharsets.UTF_8));
                    change.accept(m);
                    b = JsonLite.write(m).getBytes(StandardCharsets.UTF_8);
                }
                zout.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                zout.write(b);
                zout.closeEntry();
            }
        }
        return out;
    }
}
