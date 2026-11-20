package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.chat.AssistantSettings;
import com.oneofficeautomation.modules.chat.Chat;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AI assistants' settings (S2, 20 Nov 2026): the defaults are the values the assistants used before, every setting
 * is checked, a person adjusts only within what the administrator allows, One Chat's model calls and Studio's assistant
 * use the combined settings (model, creativity, length, the company's, skills' and person's instructions), and only
 * people who may build agents reach Studio's assistant. Against a stand-in OpenAI-compatible model server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantSettingsTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; Chat chat; ControlRoom.User admin, jane, val; HttpServer model; String base, adminToken;
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-assistants");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        adminToken = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_runner");
        val = RepositoryLifecycleTest.person(cr, admin, "val", "viewer");
        cr.checkin(admin, ChatTest.INVOICES, ChatTest.flow("process_invoices", "Process vendor invoices", "Posts a vendor's open invoices to SAP.",
                "[{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"Acme\",\"Globex\"]}]",
                "{\"examples\":[\"post today's invoices for acme\"],\"risk\":\"R2\"}"), "v1", true, null);

        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", ex -> {
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) JsonLite.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(body);
            String system = String.valueOf(firstMessage(body).get("content"));
            Map<String, Object> message = system.startsWith("Design steps")
                    ? Map.of("role", "assistant", "content", "{\"steps\":[],\"notes\":\"from the company model\"}")
                    : Map.of("role", "assistant", "content", "Which vendor do you mean?");
            byte[] out = JsonLite.write(Map.of("choices", List.of(Map.of("message", message)), "usage", Map.of("prompt_tokens", 50, "completion_tokens", 9))).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        model.start();
        String url = "http://127.0.0.1:" + model.getAddress().getPort() + "/v1";
        for (String name : List.of("company", "other"))
            cr.platform.saveModelConnection(admin, Map.of("name", name, "provider", "openai_compatible", "model", name + "-model", "base_url", url), null);
        db.update("INSERT INTO prompt_skills (name, description, definition, version, shared, template, created, updated, updated_by, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "tone", "How to word answers", JsonLite.write(Map.of("system", "Use the customer's name.", "prompt", "Be brief.")), 1, true, false, Instant.now(), Instant.now(), "admin", "admin");
        db.update("INSERT INTO prompt_skills (name, description, definition, version, shared, template, created, updated, updated_by, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "secret_draft", "Private", JsonLite.write(Map.of("system", "x")), 1, false, false, Instant.now(), Instant.now(), "admin", "admin");
        String device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
        cr.platform.updateSettings(admin, Map.of("chat_runner", device, "chat_mode", "llm", "chat_connection", "company"), null);
        chat = new Chat(cr.modules.contextFor("one-chat"), data);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() { server.stop(); model.stop(0); cr.triggerPollers.stop(); db.close(); }

    @AfterEach void reset() {
        cr.platform.updateSettings(admin, Map.of("chat_instructions", "", "chat_skills", "", "chat_models", "", "chat_language", "", "chat_own_instructions", 1,
                "studio_instructions", "", "studio_language", "", "studio_connection", "", "studio_models", ""), null);
        db.update("DELETE FROM assistant_prefs");
    }

    /** A signed-in session for a new person with this role (their temporary password changed, as on first sign-in). */
    String tokenOf(String name, String role) {
        String temp = (String) cr.createUser(admin, name, name, role, null).get("temporary_password");
        String t0 = (String) cr.login(name, temp, null).get("token");
        cr.changePassword(cr.session(t0), temp, "Passw0rd-" + name + "-2026", t0, null);
        return (String) cr.login(name, "Passw0rd-" + name + "-2026", null).get("token");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> firstMessage(Map<String, Object> body) { return ((List<Map<String, Object>>) body.get("messages")).get(0); }

    HttpResponse<String> call(String method, String path, String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token).header("X-OOA-Request", "1")
                .header("Content-Type", "application/json").method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void theDefaultsAreWhatTheAssistantsUsedBefore() {
        AssistantSettings.Effective c = chat.settings().effective(jane, "chat"), s = chat.settings().effective(jane, "studio");
        assertEquals(0.2, c.temperature());
        assertEquals(2048, c.maxTokens());
        assertEquals(0.0, s.temperature());
        assertEquals(4000, s.maxTokens());
        assertEquals("company", c.connection(), "One Chat keeps chat_connection");
        assertEquals("", s.connection(), "Studio's assistant: the person's first allowed connection");
        assertNull(c.language(), "the person's console language");
        assertEquals("", c.instructions());
    }

    @Test void everySettingIsChecked() {
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("chat_temperature", "1.5"), Map.<String, Object>of("studio_temperature", "warm"),
                Map.<String, Object>of("chat_max_tokens", 50), Map.<String, Object>of("studio_max_tokens", 9000), Map.<String, Object>of("chat_language", "xx"),
                Map.<String, Object>of("chat_models", "company,nowhere"), Map.<String, Object>of("studio_connection", "nowhere"),
                Map.<String, Object>of("chat_skills", "secret_draft"), Map.<String, Object>of("studio_own_instructions", 2)))
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, bad, null), String.valueOf(bad));
        cr.platform.updateSettings(admin, Map.of("chat_temperature", "0.7", "studio_max_tokens", 6000, "chat_skills", "tone", "chat_models", "company, other"), null);
        assertEquals(0.7, chat.settings().effective(jane, "chat").temperature());
        assertEquals(6000, chat.settings().effective(jane, "studio").maxTokens());
        cr.platform.updateSettings(admin, Map.of("chat_temperature", "0.2", "studio_max_tokens", 4000), null);
    }

    @Test void aPersonAdjustsOnlyWithinWhatIsAllowed() {
        AssistantSettings a = chat.settings();
        assertThrows(OoaException.class, () -> a.savePrefs(jane, "chat", Map.of("model", "other"), null), "no model offered yet");
        cr.platform.updateSettings(admin, Map.of("chat_models", "other"), null);
        a.savePrefs(jane, "chat", Map.of("model", "other", "language", "de", "instructions", "Amounts in euros."), null);
        AssistantSettings.Effective e = a.effective(jane, "chat");
        assertEquals("other", e.connection());
        assertEquals("de", e.language());
        assertTrue(e.instructions().contains("Amounts in euros."), e.instructions());
        assertEquals("company", a.effective(val, "chat").connection(), "someone else keeps the default");

        cr.platform.updateSettings(admin, Map.of("chat_models", ""), null);
        assertEquals("company", a.effective(jane, "chat").connection(), "a model no longer offered falls back to the default");
        cr.platform.updateSettings(admin, Map.of("chat_language", "fr", "chat_own_instructions", 0), null);
        assertEquals("fr", a.effective(jane, "chat").language(), "the company's language wins");
        assertFalse(a.effective(jane, "chat").instructions().contains("euros"), "own instructions count only while allowed");
        assertThrows(OoaException.class, () -> a.savePrefs(jane, "chat", Map.of("language", "de"), null));
        assertThrows(OoaException.class, () -> a.savePrefs(jane, "chat", Map.of("instructions", "mine"), null));
        assertThrows(OoaException.class, () -> a.savePrefs(jane, "nowhere", Map.of(), null));
        Map<String, Object> audit = db.one("SELECT details FROM audit WHERE action = ? AND actor = ? ORDER BY seq DESC LIMIT ?", "assistant.prefs_changed", "jane", 1);
        assertFalse(String.valueOf(audit.get("details")).contains("euros"), "the audit trail has that it changed, not the text");
    }

    @Test @SuppressWarnings("unchecked")
    void oneChatCallsTheModelWithTheSettings() {
        cr.platform.updateSettings(admin, Map.of("chat_instructions", "Always name the run id.", "chat_skills", "tone", "chat_temperature", "0.4", "chat_max_tokens", 600), null);
        chat.settings().savePrefs(admin, "chat", Map.of("instructions", "I work in Accounts Payable."), null);
        int before = requests.size();
        chat.message(admin, null, "can you post some invoices", null);
        Map<String, Object> sent = requests.get(before);
        assertEquals(0.4, ((Number) sent.get("temperature")).doubleValue());
        assertEquals(600, ((Number) sent.getOrDefault("max_tokens", sent.get("max_completion_tokens"))).intValue());
        assertEquals("company-model", sent.get("model"));
        String system = String.valueOf(firstMessage(sent).get("content"));
        int company = system.indexOf("Always name the run id."), skill = system.indexOf("Use the customer's name."), own = system.indexOf("I work in Accounts Payable.");
        assertTrue(company > 0 && skill > company && own > skill, "company, then skills, then the person's own: " + system);
        cr.platform.updateSettings(admin, Map.of("chat_temperature", "0.2", "chat_max_tokens", 2048), null);
    }

    @Test @SuppressWarnings("unchecked")
    void studiosAssistantAnswersThroughOneCommandCenterForBuildersOnly() throws Exception {
        cr.platform.updateSettings(admin, Map.of("studio_connection", "other", "studio_instructions", "Name agents as Verb Noun.", "studio_language", "de"), null);
        int before = requests.size();
        HttpResponse<String> r = call("POST", "/api/assistants/studio/complete", JsonLite.write(Map.of("system", "Design steps for agents.", "prompt", "Read a file")), adminToken);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("{\"steps\":[],\"notes\":\"from the company model\"}", ((Map<String, Object>) JsonLite.parse(r.body())).get("text"));
        Map<String, Object> sent = requests.get(before);
        assertEquals("other-model", sent.get("model"));
        assertEquals(0.0, ((Number) sent.get("temperature")).doubleValue());
        String system = String.valueOf(firstMessage(sent).get("content"));
        assertTrue(system.startsWith("Design steps for agents.") && system.contains("Name agents as Verb Noun.") && system.contains("in German"), system);
        assertEquals("Studio assistant", db.one("SELECT bot_path FROM ai_calls WHERE requested_by = ? ORDER BY ts DESC LIMIT ?", "admin", 1).get("bot_path"));

        String valKey = tokenOf("vic", "viewer");
        assertEquals(403, call("POST", "/api/assistants/studio/complete", JsonLite.write(Map.of("system", "s", "prompt", "p")), valKey).statusCode(), "a viewer may not build agents");
        assertEquals(403, call("GET", "/api/assistants/options", null, valKey).statusCode(), "only administrators see what may be chosen");
        HttpResponse<String> mine = call("GET", "/api/assistants/me", null, valKey);
        assertEquals(200, mine.statusCode(), "everyone sees their own choices");
        assertTrue(mine.body().contains("\"studio\""), mine.body());
    }

    @Test void theChoicesOfPeopleWhoAreDeletedGo() {
        cr.platform.updateSettings(admin, Map.of("chat_models", "other"), null);
        ControlRoom.User sam = RepositoryLifecycleTest.person(cr, admin, "sam", "bot_runner");
        chat.settings().savePrefs(sam, "chat", Map.of("model", "other"), null);
        db.update("DELETE FROM users WHERE username = ?", "sam");
        chat.settings().forgetDeletedPeople();   // the module's clock does this every hour
        assertNull(db.one("SELECT username FROM assistant_prefs WHERE username = ?", "sam"));
    }
}
