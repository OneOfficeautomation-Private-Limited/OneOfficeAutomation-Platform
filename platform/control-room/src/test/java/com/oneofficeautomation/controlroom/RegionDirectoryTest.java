package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * C4: two regions (in, eu), each with its own database and tenants, and the global directory that only knows which
 * region holds which address name. A name is used in one region only; /t/NAME sends people to their region; the
 * operators create, suspend and delete tenants through a region's API with their token.
 */
class RegionDirectoryTest {

    @Test @SuppressWarnings("unchecked")
    void namesAreUniqueAcrossRegionsAndPeopleAreSentToTheirs() throws Exception {
        Path dirData = Files.createTempDirectory("ooa-directory"), inData = Files.createTempDirectory("ooa-in"), euData = Files.createTempDirectory("ooa-eu");
        String inToken = "ooaop_in_token", euToken = "ooaop_eu_token", opToken = "ooaop_operator";
        Db dirDb = Db.h2File(dirData), inDb = Db.embedded(inData), euDb = Db.h2File(euData);
        assumeFalse(inDb.sqlServer, "tenants need PostgreSQL or H2");
        RegionDirectory directory = new RegionDirectory(dirDb, List.of(new RegionDirectory.Region("in", "in.ooa.test", Security.sha256(inToken)),
            new RegionDirectory.Region("eu", "eu.ooa.test", Security.sha256(euToken))));
        directory.start("127.0.0.1", 0);
        String dirUrl = "http://127.0.0.1:" + directory.port();
        try (Tenants in = new Tenants(inDb, inData, Vault.ofKey(new byte[32], "t"), "in", "in.ooa.test", FirstPartyPackages::registry, null);
             Tenants eu = new Tenants(euDb, euData, Vault.ofKey(new byte[32], "t"), "eu", "eu.ooa.test", FirstPartyPackages::registry, null)) {
            in.directory = RegionDirectory.client(dirUrl, inToken);
            eu.directory = RegionDirectory.client(dirUrl, euToken);

            in.create("acme", "Acme India", null, Map.of(), "op");
            assertEquals("SAAS.SLUG_TAKEN", assertThrows(OoaException.class, () -> eu.create("acme", "Acme Europe", null, Map.of(), "op")).code(),
                "a name is used in one region only");
            assertNull(eu.get("acme"), "nothing half-made stays in eu");
            eu.create("globex", "Globex", null, Map.of(), "op");

            // the directory knows only names and regions, and sends people on
            String go = TenantsTest.get(directory.port(), "ooa.test", "/t/acme");
            assertTrue(go.startsWith("HTTP/1.1 302") && go.contains("Location: https://acme.in.ooa.test/"), go);
            assertTrue(TenantsTest.get(directory.port(), "ooa.test", "/t/globex").contains("Location: https://globex.eu.ooa.test/"));
            assertTrue(TenantsTest.get(directory.port(), "ooa.test", "/t/nobody").startsWith("HTTP/1.1 404"));
            assertTrue(TenantsTest.get(directory.port(), "ooa.test", "/go?name=Acme").contains("Location: /t/acme"));
            assertTrue(TenantsTest.get(directory.port(), "ooa.test", "/api/directory/acme").contains("\"region\":\"in\""));
            assertEquals(List.of("acme", "globex"), dirDb.query("SELECT slug FROM directory ORDER BY slug").stream().map(r -> r.get("slug")).toList());
            assertEquals(Set.of("slug", "region", "created"), dirDb.query("SELECT * FROM directory").get(0).keySet(), "no customer data in the directory");

            // a region cannot release another region's name, nor reserve without its token
            assertEquals("CR.UNAUTHENTICATED", assertThrows(OoaException.class, () -> directory.reserve("newco", "wrong")).code());
            directory.release("acme", euToken);
            assertNotNull(directory.find("acme"), "eu cannot release in's name");

            // operators work through the region's API with their token
            TenantHost inHost = new TenantHost(in, "127.0.0.1", 0, Security.sha256(opToken));
            inHost.start();
            try {
                int port = inHost.port();
                assertTrue(request(port, "in.ooa.test", "GET", "/api/operator/tenants", null, null).startsWith("HTTP/1.1 401"));
                assertTrue(request(port, "in.ooa.test", "GET", "/api/operator/tenants", null, "wrong").startsWith("HTTP/1.1 401"));
                assertTrue(request(port, "acme.in.ooa.test", "GET", "/api/operator/tenants", null, opToken).startsWith("HTTP/1.1 401"),
                    "not on a tenant's address: there the tenant's own API answers");
                String made = request(port, "in.ooa.test", "POST", "/api/operator/tenants", "{\"slug\":\"initech\",\"name\":\"Initech\",\"seats\":{\"developer\":2}}", opToken);
                assertTrue(made.startsWith("HTTP/1.1 200") && made.contains("\"admin_password\""), made);
                String list = request(port, "in.ooa.test", "GET", "/api/operator/tenants", null, opToken);
                assertTrue(list.contains("\"initech\"") && list.contains("\"acme\"") && !list.contains("key_ciphertext"), list);
                assertTrue(request(port, "in.ooa.test", "DELETE", "/api/operator/tenants/initech", null, opToken).startsWith("HTTP/1.1 409"), "suspend first");
                assertTrue(request(port, "in.ooa.test", "POST", "/api/operator/tenants/initech/suspend", "{}", opToken).contains("\"suspended\""));
                assertTrue(request(port, "in.ooa.test", "DELETE", "/api/operator/tenants/initech", null, opToken).startsWith("HTTP/1.1 200"));
                assertNull(directory.find("initech"), "deleting releases the name");
            } finally { inHost.stop(); }

            // another node of the region suspended acme: this node stops it at its next check
            Tenants.Tenant open = in.serve("acme");
            in.catalog.update("UPDATE tenants SET status = 'suspended' WHERE slug = 'acme'");
            in.syncWithCatalog();
            assertTrue(in.opened().stream().noneMatch(t -> t == open));
        } finally {
            directory.stop();
            dirDb.close(); inDb.close(); euDb.close();
        }
    }

    @Test void regionSettings() throws Exception {
        Path data = Files.createTempDirectory("ooa-region-cfg");
        Files.createDirectories(data.resolve("config"));
        Path f = RegionConfig.defaultFile(data);
        Files.writeString(f, "region=in\ndomain=In.Example.com\ndb.url=jdbc:postgresql://db:5432/ooa\ndb.user=ooa_region\ndb.password_env=OOA_DB_PASSWORD\n"
            + "operator.token_sha256=" + Security.sha256("x") + "\nserverless=true\n");
        RegionConfig c = RegionConfig.load(f);
        assertEquals("in.example.com", c.domain());
        assertTrue(c.serverless());
        assertNull(c.directoryClient(), "a single region has no directory");
        Files.writeString(f, "region=in\ndomain=in.example.com\ndb.url=jdbc:sqlserver://db\n");
        assertEquals("SAAS.CONFIG", assertThrows(OoaException.class, () -> RegionConfig.load(f)).code(), "PostgreSQL only");
        Files.writeString(f, "region=India!\ndomain=in.example.com\n");
        assertEquals("SAAS.CONFIG", assertThrows(OoaException.class, () -> RegionConfig.load(f)).code());

        // in a container: from the environment when there is no file
        Files.delete(f);
        Map<String, String> env = Map.of("OOA_REGION", "eu", "OOA_DOMAIN", "eu.example.com", "OOA_DB_URL", "jdbc:postgresql://db/ooa", "OOA_DB_PASSWORD", "x",
            "OOA_OPERATOR_TOKEN_SHA256", Security.sha256("y"), "OOA_SERVERLESS", "false");
        RegionConfig e = RegionConfig.load(f, env::get);
        assertEquals("eu", e.region());
        assertEquals("OOA_DB_PASSWORD", e.dbPasswordEnv());
        assertEquals(Security.sha256("y"), e.operatorTokenSha256());
        assertEquals("SAAS.CONFIG", assertThrows(OoaException.class, () -> RegionConfig.load(f, k -> null)).code());
    }

    static String request(int port, String host, String method, String path, String body, String token) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
