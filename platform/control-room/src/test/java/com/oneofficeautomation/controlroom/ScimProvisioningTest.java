package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.auth.Scim;
import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCIM 2.0 provisioning at /scim/v2 (Settings item 4f), over HTTP as Entra ID and Okta call it: a token from Settings;
 * people created, changed and switched off; groups pushed, giving the role; local and administrator accounts out of reach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimProvisioningTest {
    Db db; ControlRoom cr; ControlRoom.User admin; ControlRoomServer server; String token;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-scim");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.platform.updateSettings(admin, Map.of("sso_group_roles", "Finance Automation = bot_runner"), null);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        token = (String) cr.scim.makeToken(admin, "Entra ID", null).get("token");
    }

    @AfterAll void stop() { server.stop(); db.close(); }

    record Res(int status, Map<String, Object> body) {}

    @SuppressWarnings("unchecked")
    Res call(String method, String path, Object body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/scim/v2" + path)).header("Content-Type", "application/scim+json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isBlank() ? Map.of() : (Map<String, Object>) JsonLite.parse(r.body()));
    }
    Res call(String method, String path, Object body) throws Exception { return call(method, path, body, token); }

    Map<String, Object> user(String userName) { return db.one("SELECT * FROM users WHERE username = ?", userName); }

    @Test @SuppressWarnings("unchecked") void provisioningAsEntraIdDoesIt() throws Exception {
        assertEquals(401, call("GET", "/Users", null, null).status());
        assertEquals(401, call("GET", "/Users", null, "scim_wrong").status());
        assertEquals(200, call("GET", "/ServiceProviderConfig", null).status());

        // Entra ID first asks whether the person exists, then creates them
        Res none = call("GET", "/Users?filter=" + java.net.URLEncoder.encode("userName eq \"alice@example.com\"", "UTF-8"), null);
        assertEquals(0L, ((Number) none.body().get("totalResults")).longValue());
        Res made = call("POST", "/Users", Map.of("schemas", List.of(Scim.USER), "userName", "alice@example.com", "externalId", "aad-1",
            "name", Map.of("givenName", "Alice", "familyName", "Ames"), "emails", List.of(Map.of("value", "alice@example.com", "type", "work", "primary", true)), "active", true));
        assertEquals(201, made.status());
        String alice = (String) made.body().get("id");
        Map<String, Object> u = user("alice@example.com");
        assertEquals("scim", u.get("directory"));
        assertEquals(Boolean.FALSE, u.get("enabled"), "no group gives a role yet, and no role for new people: off");
        assertEquals("Alice Ames", u.get("display_name"));
        assertEquals(409, call("POST", "/Users", Map.of("userName", "Alice@example.com")).status(), "the same name again");

        // a group pushed with her in it gives the role and switches her on
        Res g = call("POST", "/Groups", Map.of("schemas", List.of(Scim.GROUP), "displayName", "Finance Automation", "members", List.of(Map.of("value", alice))));
        assertEquals(201, g.status());
        String group = (String) g.body().get("id");
        u = user("alice@example.com");
        assertEquals("bot_runner", u.get("role"));
        assertEquals(Boolean.TRUE, u.get("enabled"));
        assertEquals(List.of("Finance Automation"), ((List<Map<String, Object>>) call("GET", "/Users/" + alice, null).body().get("groups")).stream().map(x -> x.get("display")).toList());

        // PATCH as Entra ID sends it: active as the string "False", then a name
        assertEquals(200, call("PATCH", "/Users/" + alice, Map.of("schemas", List.of(Scim.PATCH), "Operations", List.of(Map.of("op", "Replace", "path", "active", "value", "False")))).status());
        assertEquals(Boolean.FALSE, user("alice@example.com").get("enabled"));
        call("PATCH", "/Users/" + alice, Map.of("Operations", List.of(Map.of("op", "replace", "value", Map.of("active", true, "displayName", "Alice A.")),
            Map.of("op", "replace", "path", "name.familyName", "value", "Adams"), Map.of("op", "add", "path", "title", "value", "Accountant"))));
        u = user("alice@example.com");
        assertEquals(Boolean.TRUE, u.get("enabled"));
        assertEquals("Alice A.", u.get("display_name"));
        assertEquals("Adams", u.get("last_name"));

        // out of the group: no role, off again
        assertEquals(200, call("PATCH", "/Groups/" + group, Map.of("Operations", List.of(Map.of("op", "Remove", "path", "members[value eq \"" + alice + "\"]")))).status());
        assertEquals(Boolean.FALSE, user("alice@example.com").get("enabled"));
        call("PATCH", "/Groups/" + group, Map.of("Operations", List.of(Map.of("op", "Add", "path", "members", "value", List.of(Map.of("value", alice))))));
        assertEquals(Boolean.TRUE, user("alice@example.com").get("enabled"));

        // DELETE switches the account off; history stays
        assertEquals(204, call("DELETE", "/Users/" + alice, null).status());
        u = user("alice@example.com");
        assertNotNull(u);
        assertEquals(Boolean.FALSE, u.get("enabled"));
        assertEquals(false, call("GET", "/Users/" + alice, null).body().get("active"));
    }

    @Test void localAndAdministratorAccountsAreOutOfReach() throws Exception {
        assertEquals(409, call("POST", "/Users", Map.of("userName", "admin")).status(), "a local account's name cannot be taken");
        String adminId = (String) db.one("SELECT id FROM users WHERE username = 'admin'").get("id");
        assertEquals(404, call("GET", "/Users/" + adminId, null).status());
        assertEquals(404, call("PATCH", "/Users/" + adminId, Map.of("Operations", List.of(Map.of("op", "replace", "path", "active", "value", false)))).status());
        assertEquals(Boolean.TRUE, db.one("SELECT enabled FROM users WHERE username = 'admin'").get("enabled"));
        // a group cannot take in a local account either
        Res g = call("POST", "/Groups", Map.of("displayName", "Admins", "members", List.of(Map.of("value", adminId))));
        assertEquals(List.of(), g.body().get("members"));
        assertEquals(400, call("GET", "/Users?filter=" + java.net.URLEncoder.encode("userName sw \"a\"", "UTF-8"), null).status());
    }

    @Test void tokensAreMadeShownOnceAndRevoked() throws Exception {
        Map<String, Object> t = cr.scim.makeToken(admin, "Okta", null);
        String okta = (String) t.get("token");
        assertTrue(okta.startsWith("scim_"));
        assertEquals(200, call("GET", "/Users", null, okta).status());
        assertTrue(cr.scim.tokens(admin).stream().noneMatch(x -> x.containsKey("token") || x.containsKey("token_hash")), "never shown again");
        cr.scim.revokeToken(admin, (String) t.get("id"), null);
        assertEquals(401, call("GET", "/Users", null, okta).status());
    }
}
