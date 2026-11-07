package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** G68: apps embedded in other sites through embed links: the key, the sites that may frame them, Teams. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppEmbedsTest {
    Db db; ControlRoom cr; ControlRoom.User admin, cara;
    String appId, otherApp;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-embeds");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.createUser(admin, "cara", "Cara Creator", "bot_creator", null);
        cara = new ControlRoom.User("cara", "cara", "bot_creator", "Cara Creator", false);
        cr.dataStore.saveEntity(admin, null, Map.of("name", "Calls", "fields", List.of(Map.of("name", "caller", "type", "text", "required", true))), null);
        appId = (String) cr.apps.save(cara, null, Map.of("name", "Call notes", "definition", Map.of("pages", List.of(Map.of("id", "p", "title", "Notes", "components", List.of(
            Map.of("id", "who", "kind", "input", "name", "caller"),
            Map.of("id", "save", "kind", "button", "label", "Save", "action", Map.of("kind", "create_record", "entity", "Calls", "values", Map.of("caller", "{caller}"))),
            Map.of("id", "list", "kind", "records", "entity", "Calls")))))), null).get("id");
        otherApp = (String) cr.apps.save(cara, null, Map.of("name", "Other", "definition", Map.of("pages", List.of(Map.of("id", "p", "components", List.of(
            Map.of("id", "t", "kind", "text", "text", "hi")))))), null).get("id");
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @SuppressWarnings("unchecked")
    void anEmbedLinkUsesOnlyItsAppWithItsKey() throws Exception {
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.appEmbeds.make(cara, appId, Map.of("name", "SF", "origins", List.of("http://evil.example")), null)).code(),
            "only https sites (and localhost)");
        Map<String, Object> e = cr.appEmbeds.make(cara, appId, Map.of("name", "Salesforce", "origins", "https://acme.lightning.force.com https://*.my.salesforce.com"), "127.0.0.1");
        String id = (String) e.get("id"), key = (String) e.get("key");
        assertTrue(((String) e.get("path")).endsWith("#k=" + key), "the key travels in the fragment");
        assertNotEquals(key, db.one("SELECT key_hash FROM app_embeds WHERE id = ?", id).get("key_hash"));
        assertFalse(cr.appEmbeds.list(cara, appId).get(0).containsKey("key"), "the key is shown once");
        assertEquals("https://acme.lightning.force.com https://*.my.salesforce.com", cr.appEmbeds.frameAncestors(id));
        assertNull(cr.appEmbeds.frameAncestors("emb_0000000000000000"));

        assertEquals("CR.UNAUTHENTICATED", assertThrows(OoaException.class, () -> cr.appEmbeds.app(id, "ooae_wrong")).code());
        assertEquals("Call notes", cr.appEmbeds.app(id, key).get("name"));
        Map<String, Object> r = cr.appEmbeds.act(id, key, Map.of("component", "save", "fields", Map.of("caller", "+91 98450 00000")), null);
        Map<String, Object> rec = cr.dataStore.record(cr.dataStore.entity("Calls"), (String) r.get("record_id"));
        assertEquals("app:Call notes for embed:Salesforce", rec.get("created_by"));
        assertEquals(1, cr.appEmbeds.records(id, key, "list", null).get("total"));
        assertNotNull(db.one("SELECT last_used FROM app_embeds WHERE id = ?", id).get("last_used"));

        // over HTTP: no session needed, the key as a header, the page framed only by the sites named
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create(base + "/api/embed/" + id + "/app")).header("X-OOA-Embed-Key", key).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/embed/" + id + "/app")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, bad.statusCode());
            HttpResponse<String> act = http.send(HttpRequest.newBuilder(URI.create(base + "/api/embed/" + id + "/act")).header("X-OOA-Embed-Key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"component\":\"save\",\"fields\":{\"caller\":\"B\"}}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, act.statusCode(), act.body());
            HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base + "/embed/" + id)).build(), HttpResponse.BodyHandlers.ofString());
            if (page.statusCode() == 200) {   // the console is built into this copy
                String csp = page.headers().firstValue("Content-Security-Policy").orElse("");
                assertTrue(csp.contains("frame-ancestors 'self' https://acme.lightning.force.com https://*.my.salesforce.com"), csp);
                assertTrue(page.headers().firstValue("X-Frame-Options").isEmpty());
            } else assertEquals(503, page.statusCode());
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/embed/emb_0000000000000000")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> console = http.send(HttpRequest.newBuilder(URI.create(base + "/next/")).build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(console.headers().firstValue("Content-Security-Policy").orElse("frame-ancestors 'none'").contains("force.com"), "the console itself stays unframeable");
        } finally { server.stop(); }

        // a Teams package with the link in a tab
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.appEmbeds.teamsPackage(cara, appId, id, key, "http://cr.local")).code());
        byte[] zip = cr.appEmbeds.teamsPackage(cara, appId, id, key, "https://cr.acme.com/");
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) { for (var en = z.getNextEntry(); en != null; en = z.getNextEntry()) files.put(en.getName(), z.readAllBytes()); }
        assertEquals(Set.of("manifest.json", "color.png", "outline.png"), files.keySet());
        Map<String, Object> man = (Map<String, Object>) JsonLite.parse(new String(files.get("manifest.json"), StandardCharsets.UTF_8));
        Map<String, Object> tab = (Map<String, Object>) ((List<Object>) man.get("staticTabs")).get(0);
        assertEquals("https://cr.acme.com/embed/" + id + "#k=" + key, tab.get("contentUrl"));
        assertEquals(List.of("cr.acme.com"), man.get("validDomains"));
        assertEquals((byte) 0x89, files.get("color.png")[0], "a PNG");

        // the link opens only its own app; removing it stops it
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.appEmbeds.teamsPackage(cara, otherApp, id, key, "https://cr.acme.com")).code());
        cr.appEmbeds.delete(cara, appId, id, null);
        assertEquals("CR.UNAUTHENTICATED", assertThrows(OoaException.class, () -> cr.appEmbeds.app(id, key)).code());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.appEmbeds.list(new ControlRoom.User("v", "vic", "viewer", "V", false), appId)).code());
    }
}
