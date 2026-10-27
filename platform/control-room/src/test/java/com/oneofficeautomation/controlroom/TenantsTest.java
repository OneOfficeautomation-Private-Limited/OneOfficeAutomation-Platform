package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * C3 step 1: the tenants of a SaaS region. Each has its own schema, folder and vault key in one shared database; one
 * port serves them all, the host name picking the tenant. Runs on H2, and on PostgreSQL in the CI job that sets one.
 */
class TenantsTest {

    @Test void tenantsShareTheServerButNothingElse() throws Exception {
        Path data = Files.createTempDirectory("ooa-region");
        Db base = Db.embedded(data);
        assumeFalse(base.sqlServer, "tenants need PostgreSQL or H2");
        try (Tenants region = new Tenants(base, data, Vault.ofKey(new byte[32], "test"), "in", "in.ooa.test", FirstPartyPackages::registry, null)) {
            Map<String, Object> acme = region.create("acme", "Acme Ltd", "it@acme.example", Map.of("unattended_runner", 5), "operator");
            Map<String, Object> globex = region.create("globex", "Globex", "it@globex.example", Map.of(), "operator");
            assertEquals("active", acme.get("status"));
            assertNotEquals(acme.get("admin_password"), globex.get("admin_password"));

            ControlRoom a = region.serve("acme").cr, g = region.serve("globex").cr;
            assertNotSame(a, g);
            assertEquals("t_" + acme.get("id"), a.db.schema());
            // the same user name in both, each with its own password and sessions
            Map<String, Object> la = a.login("admin", (String) acme.get("admin_password"), null);
            assertThrows(OoaException.class, () -> g.login("admin", (String) acme.get("admin_password"), null), "acme's password does not open globex");
            assertNotNull(a.session((String) la.get("token")));
            assertNull(g.session((String) la.get("token")), "an acme session is no globex session");

            // their data stays apart
            ControlRoom.User aa = signIn(a, (String) acme.get("admin_password"));
            ControlRoom.User ga = signIn(g, (String) globex.get("admin_password"));
            a.setGlobal(aa, "who", "acme", null);
            g.setGlobal(ga, "who", "globex", null);
            assertEquals("\"acme\"", a.db.one("SELECT json_value FROM global_values WHERE name = 'who'").get("json_value"));
            assertEquals("\"globex\"", g.db.one("SELECT json_value FROM global_values WHERE name = 'who'").get("json_value"));
            assertEquals(1, a.db.query("SELECT name FROM global_values").size());
            assertEquals("https://acme.in.ooa.test", a.platform.setting("public_url"));

            // each its own vault key: what acme encrypts, globex cannot read
            String secret = a.vault.encrypt("acme secret", "x");
            assertThrows(OoaException.class, () -> g.vault.decrypt(secret, "x"));
            assertNotEquals(region.folderOf((String) acme.get("id")), region.folderOf((String) globex.get("id")));

            // address names
            assertEquals("SAAS.SLUG_TAKEN", assertThrows(OoaException.class, () -> region.create("acme", "Again", null, null, "op")).code());
            for (String bad : List.of("www", "a", "Acme!", "-acme", "acme-", "x".repeat(41)))
                assertEquals("SAAS.BAD_SLUG", assertThrows(OoaException.class, () -> region.create(bad, "Bad", null, null, "op")).code(), bad);

            // one port for all of them, the host name choosing
            TenantHost host = new TenantHost(region, "127.0.0.1", 0);
            host.start();
            try {
                int port = host.port();
                assertTrue(get(port, "acme.in.ooa.test", "/api/auth/me").startsWith("HTTP/1.1 401"), "acme answers: sign in first");
                assertTrue(get(port, "acme.in.ooa.test:443", "/api/auth/me").startsWith("HTTP/1.1 401"), "with a port in the Host header too");
                assertTrue(get(port, "nobody.in.ooa.test", "/api/auth/me").startsWith("HTTP/1.1 404"));
                assertTrue(get(port, "acme.us.ooa.test", "/api/auth/me").startsWith("HTTP/1.1 404"), "another region's address");
                assertTrue(get(port, "x.acme.in.ooa.test", "/api/auth/me").startsWith("HTTP/1.1 404"), "one label only");
                String health = get(port, "in.ooa.test", "/healthz");
                assertTrue(health.startsWith("HTTP/1.1 200") && health.contains("\"region\":\"in\""), health);

                // a suspended tenant answers like an unknown one, and keeps its data
                region.suspend("globex", "operator");
                assertNull(region.serve("globex"));
                assertTrue(get(port, "globex.in.ooa.test", "/api/auth/me").startsWith("HTTP/1.1 404"));
                region.resume("globex", "operator");
                assertEquals("\"globex\"", region.serve("globex").cr.db.one("SELECT json_value FROM global_values WHERE name = 'who'").get("json_value"));
                assertTrue(get(port, "globex.in.ooa.test", "/api/auth/me").startsWith("HTTP/1.1 401"));
            } finally { host.stop(); }

            // deleting: only when suspended; schema, folder and entry go, the address name is free again
            assertEquals("SAAS.NOT_SUSPENDED", assertThrows(OoaException.class, () -> region.delete("globex", "op")).code());
            Path gFolder = region.folderOf((String) globex.get("id"));
            region.suspend("globex", "op");
            region.delete("globex", "op");
            assertFalse(Files.exists(gFolder));
            assertNull(region.get("globex"));
            assertTrue(base.query("SELECT schema_name FROM information_schema.schemata WHERE schema_name = ?", "t_" + globex.get("id")).isEmpty());
            assertEquals(List.of("acme"), region.list().stream().map(Tenants.Info::slug).toList());
            region.create("globex", "Globex again", null, null, "op");
        } finally { base.close(); }
    }

    @Test void aTenantHasTheSeatsTheOperatorGave() throws Exception {
        Path data = Files.createTempDirectory("ooa-region-seats");
        Db base = Db.embedded(data);
        assumeFalse(base.sqlServer, "tenants need PostgreSQL or H2");
        try (Tenants region = new Tenants(base, data, Vault.ofKey(new byte[32], "test"), "us", "us.ooa.test", FirstPartyPackages::registry, null)) {
            Map<String, Object> t = region.create("acme", "Acme", null, Map.of("developer", 1, "unattended_runner", 1), "op");
            ControlRoom a = region.serve("acme").cr;
            ControlRoom.User admin = signIn(a, (String) t.get("admin_password"));
            Map<String, Object> status = a.licensing.status(admin);
            assertEquals("subscription", status.get("state"));
            assertNull(status.get("banner"), "no evaluation or expiry banner");
            assertEquals("SAAS.NOT_AVAILABLE", assertThrows(OoaException.class, () -> a.licensing.install(admin, "x", null)).code());

            // the one Developer seat goes to the first who checks in an agent
            a.checkin(admin, "Fin/Pay.flow.json", LicensingAndChecksTest.agent("pay"), "v1", true, null);
            a.createUser(admin, "dana", "Dana", "bot_creator", null);
            ControlRoom.User dana = ControlRoom.user(a.db.one("SELECT * FROM users WHERE username = 'dana'"));
            assertEquals("CR.LICENSE_LIMIT", assertThrows(OoaException.class,
                () -> a.checkin(dana, "Fin/Other.flow.json", LicensingAndChecksTest.agent("other"), "v1", false, null)).code());

            // the operator gives more; the open tenant uses them at once
            region.setSeats("acme", Map.of("developer", 2, "unattended_runner", 1), "op");
            a.checkin(dana, "Fin/Other.flow.json", LicensingAndChecksTest.agent("other"), "v1", false, null);
            assertEquals("SAAS.BAD_SEATS", assertThrows(OoaException.class, () -> region.setSeats("acme", Map.of("pilots", 3), "op")).code());
            assertEquals("SAAS.BAD_SEATS", assertThrows(OoaException.class, () -> region.setSeats("acme", Map.of("developer", -1), "op")).code());
            region.setSeats("acme", Map.of(), "op");
            assertEquals(-1, a.licensing.total("developer"), "no seats given: no limit");
        } finally { base.close(); }
    }

    @Test void hostNamesPickTheTenant() {
        assertEquals("acme", TenantHost.slugOf("acme.in.ooa.test", "in.ooa.test"));
        assertEquals("acme", TenantHost.slugOf("ACME.In.Ooa.Test:8443", "in.ooa.test"));
        assertEquals("acme", TenantHost.slugOf("acme.in.ooa.test.", "in.ooa.test"));
        assertEquals("", TenantHost.slugOf("in.ooa.test", "in.ooa.test"));
        assertEquals("", TenantHost.slugOf("10.0.0.7:8720", "in.ooa.test"), "the node by its address");
        assertEquals("", TenantHost.slugOf("[::1]:8720", "in.ooa.test"));
        assertNull(TenantHost.slugOf("evil-in.ooa.test", "in.ooa.test"), "not a sub-domain");
        assertNull(TenantHost.slugOf("a.b.in.ooa.test", "in.ooa.test"));
        assertNull(TenantHost.slugOf(null, "in.ooa.test"));
    }

    /** The tenant's first administrator, signed in after choosing a password (the one-time one came from create). */
    static ControlRoom.User signIn(ControlRoom c, String oneTime) {
        String t0 = (String) c.login("admin", oneTime, null).get("token");
        c.changePassword(c.session(t0), oneTime, "Admin-Passw0rd-2026", t0, null);
        return c.session((String) c.login("admin", "Admin-Passw0rd-2026", null).get("token"));
    }

    /** A plain HTTP/1.1 request with the given Host header (the test machine has no DNS for the tenants' names). */
    static String get(int port, String hostHeader, String path) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
