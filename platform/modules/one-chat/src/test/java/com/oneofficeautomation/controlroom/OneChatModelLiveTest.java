package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One CommandCenter against OneChat's real model server (S5c, 25 Nov 2026; contract section 16). CI's "OneChat model
 * server" job checks out OneOfficeAutomation-OneChat at the commit pinned in onechat-compat.properties (model_server_ref),
 * starts its server with its fake model and a token, and runs this. Skipped unless -Pooa.test.onechat gives its
 * address (and -Pooa.test.onechat.token its token): the version route and the model check, Studio's assistant with
 * each mode, and the training feedback.
 */
@EnabledIfSystemProperty(named = "ooa.test.onechat", matches = "https?://.+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneChatModelLiveTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; ControlRoom.User admin; String base, adminToken;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-onechat-live");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        adminToken = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        cr.createLocker(admin, "ai", "AI keys", "user:admin", null);
        cr.setCredential(admin, "ai", "onechat", "token", System.getProperty("ooa.test.onechat.token", ""), null);
        String url = System.getProperty("ooa.test.onechat").replaceAll("/+$", "") + "/v1";
        cr.platform.saveModelConnection(admin, Map.of("name", "onechat-flow", "provider", "openai_compatible", "model", "ooa-flow-assistant",
                "base_url", url, "key_ref", "ai/onechat/token"), null);
        cr.platform.updateSettings(admin, Map.of("studio_connection", "onechat-flow"), null);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() { server.stop(); cr.triggerPollers.stop(); db.close(); }

    HttpResponse<String> call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + adminToken).header("X-OOA-Request", "1")
                .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(HttpResponse<String> r) { return (Map<String, Object>) JsonLite.parse(r.body()); }

    @Test @SuppressWarnings("unchecked")
    void theModelCheckReachesOneChat() throws Exception {
        Map<String, Object> r = json(call("GET", "/api/onechat/model-check", null));
        Map<String, Object> c = ((List<Map<String, Object>>) r.get("connections")).get(0);
        assertTrue(Set.of("compatible", "catalog_unknown", "built_for_another_release").contains(String.valueOf(c.get("status"))),
                "OneChat answers its version route with studio-assistant/v1: " + c);
    }

    @Test
    void studioAsksOneChatWithEachMode() throws Exception {
        assertEquals(true, json(call("GET", "/api/assistants/studio/info", null)).get("onechat_model"));
        for (String mode : List.of("auto", "fast", "smart", "ultra")) {
            HttpResponse<String> r = call("POST", "/api/assistants/studio/complete", Map.of("system", "You design steps for ONEOFFICEAUTOMATION agents.",
                    "prompt", "Existing variables: []\nRequest: Read a text file. The flow takes path as input.", "mode", mode));
            assertEquals(200, r.statusCode(), mode + ": " + r.body());
            String text = String.valueOf(json(r).get("text"));
            Object answer = JsonLite.parse(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
            assertTrue(answer instanceof Map<?, ?> m && m.containsKey("steps") && m.containsKey("notes"), mode + ": Studio's answer shape: " + text);
        }
    }

    @Test
    void feedbackReachesOneChatWhenAllowed() throws Exception {
        Map<String, Object> body = Map.of("request", "Read a text file", "verdict", "inserted", "variables", List.of(),
                "steps", List.of(Map.of("id", "a1", "action", "core/log", "args", Map.of("message", "x"))));
        assertEquals(Map.of("sent", false, "reason", "off"), json(call("POST", "/api/assistants/studio/feedback", body)));
        cr.platform.updateSettings(admin, Map.of("studio_training_feedback", 1), null);
        try { assertEquals(Map.of("sent", true), json(call("POST", "/api/assistants/studio/feedback", body))); }
        finally { cr.platform.updateSettings(admin, Map.of("studio_training_feedback", 0), null); }
    }
}
