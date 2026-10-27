package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * C3 step 2: two tenants of one region with the same user names, agent paths, runner names and credential names;
 * nothing of one is reachable from the other. And what a tenant must not do on a shared server: connect the server to
 * private addresses, load its code into the server, or choose server folders.
 */
class TenantIsolationTest {

    @Test @SuppressWarnings("unchecked")
    void runnersAgentsCredentialsAndBackupsStayWithTheirTenant() throws Exception {
        Path data = Files.createTempDirectory("ooa-isolation");
        Db base = Db.embedded(data);
        assumeFalse(base.sqlServer, "tenants need PostgreSQL or H2");
        try (Tenants region = new Tenants(base, data, Vault.ofKey(new byte[32], "test"), "eu", "eu.ooa.test", FirstPartyPackages::registry, null)) {
            Map<String, Object> ta = region.create("acme", "Acme", null, Map.of(), "op"), tg = region.create("globex", "Globex", null, Map.of(), "op");
            ControlRoom a = region.serve("acme").cr, g = region.serve("globex").cr;
            ControlRoom.User aa = TenantsTest.signIn(a, (String) ta.get("admin_password")), ga = TenantsTest.signIn(g, (String) tg.get("admin_password"));

            // the same agent path in both, different content
            a.checkin(aa, "Fin/Pay.flow.json", LicensingAndChecksTest.agent("pay_acme"), "v1", true, null);
            g.checkin(ga, "Fin/Pay.flow.json", LicensingAndChecksTest.agent("pay_globex"), "v1", true, null);
            assertTrue(String.valueOf(a.db.one("SELECT content FROM bot_versions WHERE path = 'Fin/Pay.flow.json'").get("content")).contains("pay_acme"));
            assertTrue(String.valueOf(g.db.one("SELECT content FROM bot_versions WHERE path = 'Fin/Pay.flow.json'").get("content")).contains("pay_globex"));

            // a registration key works only in its own tenant, and so does the runner it made
            String keyA = (String) a.createRegistrationKey(aa, 1, false, null).get("key");
            assertThrows(OoaException.class, () -> g.registerDevice(keyA, "PC-1", "pc-1", "Windows 11", "0.2.0", null));
            Map<String, Object> devA = a.registerDevice(keyA, "PC-1", "pc-1", "Windows 11", "0.2.0", null);
            Map<String, Object> devG = g.registerDevice((String) g.createRegistrationKey(ga, 1, false, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
            assertNull(g.device((String) devA.get("device_id"), (String) devA.get("secret")), "acme's runner cannot sign in to globex");
            assertNotNull(a.device((String) devA.get("device_id"), (String) devA.get("secret")));

            // a run in acme goes to acme's runner only
            a.startRun("admin", "admin", "Fin/Pay.flow.json", (String) devA.get("device_id"), Map.of(), "manual", null);
            Map<String, Object> dG = g.device((String) devG.get("device_id"), (String) devG.get("secret"));
            assertEquals(false, g.nextJob(dG, 0).get("job"), "globex's runner gets no acme work");
            Map<String, Object> jobA = (Map<String, Object>) a.nextJob(a.device((String) devA.get("device_id"), (String) devA.get("secret")), 0).get("job");
            assertTrue(String.valueOf(jobA).contains("pay_acme"), String.valueOf(jobA));

            // credentials: the same names, each under its own key
            a.vault.encrypt("x", "y");
            assertNotEquals(a.vault.fingerprint(), g.vault.fingerprint());

            // backups go to the tenant's own folder, even when a folder is asked for
            Map<String, Object> b = a.backups.backupNow(aa, null);
            assertTrue(Files.exists(a.backups.folder().resolve((String) b.get("file"))));
            assertTrue(a.backups.folder().startsWith(region.folderOf((String) ta.get("id"))), a.backups.folder().toString());
            assertEquals("SAAS.NOT_AVAILABLE", assertThrows(OoaException.class,
                () -> a.platform.updateSettings(aa, Map.of("backup_folder", region.folderOf((String) tg.get("id")).toString()), null)).code());
            assertEquals("SAAS.NOT_AVAILABLE", assertThrows(OoaException.class, () -> a.platform.updateSettings(aa, Map.of("repo_files_folder", "/srv"), null)).code());
            assertTrue(a.repoFiles.store().startsWith(region.folderOf((String) ta.get("id"))));

            // no code of a tenant loads into the shared server; serverless runs only when the region allows them
            assertEquals("SAAS.NOT_AVAILABLE", assertThrows(OoaException.class, () -> a.customToolkits.upload(aa, new byte[] { 1 }, null)).code());
            assertEquals("SAAS.NOT_AVAILABLE", assertThrows(OoaException.class, () -> a.serverless.check(LicensingAndChecksTest.agent("x"))).code());
            region.serverless = true;
            region.suspend("acme", "op");
            region.resume("acme", "op");
            region.serve("acme").cr.serverless.check(LicensingAndChecksTest.agent("x"));

            // the server connects only to public addresses for a tenant
            for (String target : List.of("http://127.0.0.1:5432/", "http://169.254.169.254/latest/meta-data/", "10.0.0.5", "https://[::1]/x",
                    "http://metadata.google.internal/", "git@192.168.1.10:repo.git", "localhost:514", "http://[::ffff:10.1.2.3]/"))
                assertEquals("SAAS.ADDRESS_BLOCKED", assertThrows(OoaException.class, () -> NetGuard.check(g, target, "Test"), target).code(), target);
            NetGuard.check(g, "https://93.184.216.34/", "A public address");
            NetGuard.check(null, "http://127.0.0.1/", "On premises everything is allowed");
            assertEquals("CR.GIT", assertThrows(OoaException.class, () -> {
                g.platform.updateSettings(ga, Map.of("git_remote_url", "file:///etc"), null);
                g.gitSync.syncNow(ga, null);
            }).code());
        } finally { base.close(); }
    }

    @Test void publicAndPrivateAddresses() throws Exception {
        for (String a : List.of("127.0.0.1", "10.1.1.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "224.0.0.1"))
            assertFalse(NetGuard.isPublic(InetAddress.getByName(a)), a);
        for (String a : List.of("8.8.8.8", "93.184.216.34", "2606:4700::1111"))
            assertTrue(NetGuard.isPublic(InetAddress.getByName(a)), a);
        assertEquals("mail.acme.example", NetGuard.hostOf("mail.acme.example"));
        assertEquals("logs.acme.example", NetGuard.hostOf("logs.acme.example:6514"));
        assertEquals("git.acme.example", NetGuard.hostOf("git@git.acme.example:team/repo.git"));
        assertEquals("::1", NetGuard.hostOf("https://[::1]:8443/x"));
    }
}
