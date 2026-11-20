package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.modules.chat.OneChatService;
import com.oneofficeautomation.modules.chat.PersonTokens;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One Chat with the OneChat service (S5, 20 Nov 2026; docs/integration/one-chat-contract.md), against a stand-in
 * OneChat: the engine settings are checked; the service's version is checked and an incompatible or silent service is
 * never replaced by the built-in engine; messages, chats and notes go to the service with the service token, the
 * tenant, the area, the language and a five-minute person token; and the service's callbacks check the service token,
 * the tenant and the person token, keep each area to its list (Studio: development only), follow the assistant's
 * settings and the person's permissions as they are now, and call models with the area's settings and limits.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneChatServiceTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String SERVICE_TOKEN = "svc-token-for-tests-1";
    Db db; ControlRoom cr; ControlRoomServer server; ControlRoom.User admin, olga; ModuleContext ctx; PersonTokens tokens;
    HttpServer model, standin; String base, adminToken, janeToken, valToken, bobToken, device;
    volatile String standinVersion = "1.0.0", standinContract = "v1";
    volatile boolean eventsFail;
    final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
    java.util.function.Function<String, String> envBefore;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-onechat");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        adminToken = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        janeToken = tokenOf("jane", "bot_runner");
        valToken = tokenOf("val", "viewer");
        bobToken = tokenOf("bob", "bot_creator");
        olga = cr.session(tokenOf("olga", "admin"));
        cr.checkin(admin, ChatTest.INVOICES, ChatTest.flow("process_invoices", "Process vendor invoices", "Posts a vendor's open invoices to SAP.",
                "[{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"Acme\",\"Globex\"]}]",
                "{\"examples\":[\"post today's invoices for acme\"],\"risk\":\"R2\"}"), "v1", true, null);
        cr.checkin(admin, ChatTest.AGING, ChatTest.flow("aging", "AP aging report", "Read-only payables aging.", "[]", "{\"examples\":[\"show payables aging\"],\"risk\":\"R0\"}"), "v1", true, null);
        cr.checkin(admin, ChatTest.PAYMENT, ChatTest.flow("payment", "Vendor payment run", "Pays due invoices.", "[]", "{\"examples\":[\"run the payment run\"],\"risk\":\"R3\"}"), "v1", true, null);
        device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
        cr.platform.updateSettings(admin, Map.of("chat_runner", device), null);

        // a stand-in OpenAI-compatible model
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            send(ex, 200, Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "Which vendor do you mean?"))),
                    "usage", Map.of("prompt_tokens", 50, "completion_tokens", 9)));
        });
        model.start();
        String url = "http://127.0.0.1:" + model.getAddress().getPort() + "/v1";
        for (String name : List.of("company", "other"))
            cr.platform.saveModelConnection(admin, Map.of("name", name, "provider", "openai_compatible", "model", name + "-model", "base_url", url), null);
        db.update("INSERT INTO prompt_skills (name, description, definition, version, shared, template, created, updated, updated_by, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "tone", "How to word answers", JsonLite.write(Map.of("system", "Use the customer's name.", "prompt", "Be brief.")), 1, true, false, Instant.now(), Instant.now(), "admin", "admin");
        db.update("INSERT INTO prompt_skills (name, description, definition, version, shared, template, created, updated, updated_by, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "secret_draft", "Private", JsonLite.write(Map.of("system", "x")), 1, false, false, Instant.now(), Instant.now(), "admin", "admin");
        cr.platform.updateSettings(admin, Map.of("chat_connection", "company", "chat_engine", "service"), null);

        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        ctx = cr.modules.contextFor("one-chat");
        tokens = new PersonTokens(ctx);

        // the stand-in OneChat: it answers with what it learnt through One CommandCenter's callbacks
        standin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        standin.setExecutor(Executors.newCachedThreadPool());
        standin.createContext("/api/chat/v1/", ex -> {
            String path = ex.getRequestURI().getPath().substring("/api/chat/v1/".length()), m = ex.getRequestMethod();
            byte[] body = ex.getRequestBody().readAllBytes();
            Map<String, Object> seen = new LinkedHashMap<>();
            seen.put("method", m);
            seen.put("path", path);
            for (String h : List.of("Authorization", "X-OOA-Tenant", "X-OOA-Person", "X-OOA-Area", "X-OOA-Lang"))
                if (ex.getRequestHeaders().getFirst(h) != null) seen.put(h, ex.getRequestHeaders().getFirst(h));
            if (body.length > 0) seen.put("body", JsonLite.parse(new String(body, StandardCharsets.UTF_8)));
            if (path.equals("version")) { send(ex, 200, Map.of("service", "onechat", "version", standinVersion, "contract", standinContract)); return; }
            received.add(seen);
            if (!("Bearer " + SERVICE_TOKEN).equals(seen.get("Authorization"))) { send(ex, 401, Map.of("error", Map.of("code", "AUTH", "message", "wrong service token"))); return; }
            try {
                if (m.equals("POST") && path.equals("messages") && "studio".equals(seen.get("X-OOA-Area"))) {
                    // Studio's assistant: a proposal Studio checks before it shows it
                    Map<?, ?> who = (Map<?, ?>) JsonLite.parse(callback("GET", "person", null, SERVICE_TOKEN, (String) seen.get("X-OOA-Tenant"), (String) seen.get("X-OOA-Person")).body());
                    send(ex, 200, Map.of("session_id", "st1", "messages", List.of(Map.of("role", "assistant", "kind", "proposal", "text", "Here are the steps.",
                            "proposal", Map.of("steps", List.of(), "notes", "for " + who.get("username"))))));
                } else if (m.equals("POST") && path.equals("events")) {
                    if (eventsFail) send(ex, 503, Map.of("error", Map.of("code", "BUSY", "message", "busy")));
                    else send(ex, 200, Map.of("ok", true));
                } else if (m.equals("POST") && path.equals("messages")) {
                    String person = (String) seen.get("X-OOA-Person"), tenant = (String) seen.get("X-OOA-Tenant");
                    Map<?, ?> who = (Map<?, ?>) JsonLite.parse(callback("GET", "person", null, SERVICE_TOKEN, tenant, person).body());
                    Map<?, ?> agents = (Map<?, ?>) JsonLite.parse(callback("GET", "agents", null, SERVICE_TOKEN, tenant, person).body());
                    send(ex, 200, Map.of("session_id", "s1", "messages", List.of(
                            Map.of("role", "user", "text", String.valueOf(((Map<?, ?>) seen.get("body")).get("message"))),
                            Map.of("role", "assistant", "kind", "answer", "text", "Hello " + who.get("username") + "; you may run " + ((List<?>) agents.get("agents")).size() + " agents."))));
                } else if (m.equals("GET") && path.equals("sessions")) {
                    send(ex, 200, Map.of("sessions", List.of(Map.of("id", "s1", "title", "Invoices", "last_used", "2026-11-20T10:00:00Z", "messages", 2))));
                } else if (m.equals("GET") && path.equals("sessions/s1")) {
                    send(ex, 200, Map.of("session_id", "s1", "messages", List.of(Map.of("role", "user", "text", "hello"), Map.of("role", "assistant", "kind", "answer", "text", "Hi."))));
                } else if (m.equals("GET") && path.endsWith("/memory")) {
                    send(ex, 200, Map.of("notes", List.of(Map.of("id", "n1", "note", "I work in Accounts Payable", "created", "2026-11-20T09:00:00Z"))));
                } else if (m.equals("DELETE") && path.contains("/memory")) send(ex, 200, Map.of("ok", true));
                else send(ex, 404, Map.of("error", Map.of("code", "NOT_FOUND", "message", "no route " + path)));
            } catch (Exception e) { send(ex, 500, Map.of("error", Map.of("code", "STANDIN", "message", String.valueOf(e)))); }
        });
        standin.start();
        envBefore = OneChatService.env;
        String standinUrl = "http://127.0.0.1:" + standin.getAddress().getPort();
        OneChatService.env = k -> switch (k) { case "OOA_ONECHAT_URL" -> standinUrl + "/"; case "OOA_ONECHAT_TOKEN" -> SERVICE_TOKEN; default -> null; };
    }

    @AfterAll void stop() { OneChatService.env = envBefore; server.stop(); model.stop(0); standin.stop(0); cr.triggerPollers.stop(); db.close(); }

    @AfterEach void reset() {
        cr.platform.updateSettings(admin, Map.of("chat_engine", "service", "chat_tool_agents", "always", "chat_tool_read", "always", "chat_tool_admin_read", "never",
                "chat_tool_admin_change", "never", "chat_tool_admin_high", "never", "chat_skills", "", "chat_ktokens_per_person", 0, "onechat_url", ""), null);
        eventsFail = false;
        standinVersion = "1.0.0";
        standinContract = "v1";
        fresh();
    }

    // ---- helpers ----

    static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] out = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    String tokenOf(String name, String role) {
        String temp = (String) cr.createUser(admin, name, name, role, null).get("temporary_password");
        String t0 = (String) cr.login(name, temp, null).get("token");
        cr.changePassword(cr.session(t0), temp, "Passw0rd-" + name + "-2026", t0, null);
        return (String) cr.login(name, "Passw0rd-" + name + "-2026", null).get("token");
    }

    HttpResponse<String> call(String method, String path, String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token).header("X-OOA-Request", "1")
                .header("Content-Type", "application/json").method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A callback as the OneChat service makes it: service token, tenant and person token (any may be left out). */
    HttpResponse<String> callback(String method, String route, String json, String service, String tenant, String person) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/onechat-callback/v1/" + route)).header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (service != null) b.header("Authorization", "Bearer " + service);
        if (tenant != null) b.header("X-OOA-Tenant", tenant);
        if (person != null) b.header("X-OOA-Person", person);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> as(String person, String area, String method, String route, Object body) throws Exception {
        return callback(method, route, body == null ? null : JsonLite.write(body), SERVICE_TOKEN, "default", tokens.issue(ctx.activeUser(person), area, "s1", "10.0.0.7"));
    }

    /** A callback with a person token made at a given time (the message that asked, then the later message that says yes). */
    HttpResponse<String> at(Instant when, String person, String method, String route, Object body) throws Exception {
        return callback(method, route, JsonLite.write(body), SERVICE_TOKEN, "default", tokens.issue(ctx.activeUser(person), "chat", "s1", "10.0.0.7", when));
    }

    /** The events the stand-in received so far, of one type. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events(String type) {
        return received.stream().filter(r -> "events".equals(r.get("path")) && r.get("body") instanceof Map<?, ?> b && type.equals(b.get("type")))
                .map(r -> (Map<String, Object>) r.get("body")).toList();
    }

    /** Waits (up to 30 s) for One CommandCenter's clock to deliver an event the test expects. */
    Map<String, Object> awaitEvent(String type, java.util.function.Predicate<Map<String, Object>> which) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Optional<Map<String, Object>> e = events(type).stream().filter(which).findFirst();
            if (e.isPresent()) return e.get();
            Thread.sleep(100);
        }
        throw new AssertionError("no " + type + " event arrived: " + received);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(HttpResponse<String> r) { return (Map<String, Object>) JsonLite.parse(r.body()); }

    void fresh() {
        try { call("GET", "/api/onechat/version?fresh=1", null, adminToken); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---- settings and versions ----

    @Test void theEngineSettingsAreChecked() {
        assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("chat_engine", "cloud"), null));
        assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("onechat_url", "ftp://onechat.example"), null));
        assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("onechat_token_ref", "OneChat/token"), null));
        cr.platform.updateSettings(admin, Map.of("onechat_url", "https://onechat.example/assist", "onechat_token_ref", "OneChat/service/token"), null);
        cr.platform.updateSettings(admin, Map.of("onechat_url", "", "onechat_token_ref", ""), null);
    }

    @Test void theVersionIsCheckedAndAnIncompatibleServiceIsNeverReplacedSilently() throws Exception {
        Map<String, Object> v = json(call("GET", "/api/onechat/version?fresh=1", null, adminToken));
        assertEquals("compatible", v.get("status"), v.toString());
        assertEquals("1.0.0", v.get("version"));
        assertEquals("service", v.get("engine"));
        standinVersion = "1.2.0";
        assertEquals("newer", json(call("GET", "/api/onechat/version?fresh=1", null, adminToken)).get("status"));
        standinContract = "v2";
        assertEquals("wrong_contract", json(call("GET", "/api/onechat/version?fresh=1", null, adminToken)).get("status"));
        standinContract = "v1";
        standinVersion = "0.9.0";
        Map<String, Object> older = json(call("GET", "/api/onechat/version?fresh=1", null, adminToken));
        assertEquals("older", older.get("status"));
        assertEquals(false, older.get("compatible"));

        int before = received.size();
        Map<String, Object> r = json(call("POST", "/api/chat", JsonLite.write(Map.of("message", "hello")), janeToken));
        assertEquals("error", r.get("kind"), "an incompatible OneChat: the chat says so");
        assertTrue(String.valueOf(r.get("reply")).contains("older than 1.0.0"), String.valueOf(r.get("reply")));
        assertEquals(before, received.size(), "nothing reached the service");

        cr.platform.updateSettings(admin, Map.of("onechat_url", "http://127.0.0.1:1"), null);
        Map<String, Object> down = json(call("GET", "/api/onechat/version?fresh=1", null, adminToken));
        assertEquals("unreachable", down.get("status"));
        Map<String, Object> r2 = json(call("POST", "/api/chat", JsonLite.write(Map.of("message", "hello")), janeToken));
        assertEquals("error", r2.get("kind"));
        assertTrue(String.valueOf(r2.get("reply")).contains("not answering"), "no silent switch to the built-in engine: " + r2.get("reply"));

        assertEquals(403, call("GET", "/api/onechat/version", null, valToken).statusCode(), "only administrators see the service's version");
        var env = OneChatService.env;
        try {
            OneChatService.env = k -> null;
            cr.platform.updateSettings(admin, Map.of("onechat_url", ""), null);
            assertEquals("not_set_up", json(call("GET", "/api/onechat/version?fresh=1", null, adminToken)).get("status"));
        } finally { OneChatService.env = env; }
    }

    // ---- passing requests on ----

    @Test @SuppressWarnings("unchecked")
    void messagesGoToTheServiceWithAPersonToken() throws Exception {
        int before = received.size();
        HttpResponse<String> res = call("POST", "/api/chat", JsonLite.write(Map.of("message", "hello", "lang", "de")), janeToken);
        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> r = json(res);
        assertEquals("s1", r.get("session_id"));
        assertEquals("answer", r.get("kind"));
        assertEquals("Hello jane; you may run 3 agents.", r.get("reply"), "the service learnt who and what through the callbacks");

        Map<String, Object> seen = received.get(before);
        assertEquals("messages", seen.get("path"));
        assertEquals("Bearer " + SERVICE_TOKEN, seen.get("Authorization"));
        assertEquals("default", seen.get("X-OOA-Tenant"));
        assertEquals("chat", seen.get("X-OOA-Area"));
        assertEquals("de", seen.get("X-OOA-Lang"));
        String person = (String) seen.get("X-OOA-Person");
        String[] parts = person.split("\\.");
        assertEquals(3, parts.length);
        assertEquals("v1", parts[0]);
        Map<String, Object> payload = (Map<String, Object>) JsonLite.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertEquals("jane", payload.get("sub"));
        assertEquals("chat", payload.get("area"));
        assertEquals("default", payload.get("tenant"));
        assertEquals(300L, ((Number) payload.get("exp")).longValue() - ((Number) payload.get("iat")).longValue(), "good for five minutes");
        assertNotNull(payload.get("jti"));

        cr.platform.updateSettings(admin, Map.of("chat_language", "fr"), null);
        try {
            call("POST", "/api/chat", JsonLite.write(Map.of("message", "hello", "lang", "de")), janeToken);
            assertEquals("fr", received.get(received.size() - 1).get("X-OOA-Lang"), "the company's answer language wins");
        } finally { cr.platform.updateSettings(admin, Map.of("chat_language", ""), null); }
    }

    @Test @SuppressWarnings("unchecked")
    void chatsHistoryAndNotesComeFromTheService() throws Exception {
        List<Map<String, Object>> chats = (List<Map<String, Object>>) json(call("GET", "/api/chat", null, janeToken)).get("chats");
        assertEquals(1, chats.size());
        assertEquals("Invoices", chats.get(0).get("title"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) json(call("GET", "/api/chat/s1", null, janeToken)).get("messages");
        assertEquals(List.of("user", "assistant"), messages.stream().map(m -> m.get("role")).toList());

        Map<String, Object> memory = (Map<String, Object>) json(call("GET", "/api/assistants/me", null, janeToken)).get("memory");
        assertEquals("service", memory.get("kept_by"));
        assertEquals("I work in Accounts Payable", ((List<Map<String, Object>>) memory.get("notes")).get(0).get("note"));
        assertEquals(200, call("DELETE", "/api/assistants/me/memory/n1", null, janeToken).statusCode());
        Map<String, Object> last = received.get(received.size() - 2);   // the DELETE, then the notes list read again
        assertEquals("DELETE", last.get("method"));
        assertEquals("people/jane/memory/n1", last.get("path"));
        assertNotNull(db.one("SELECT seq FROM audit WHERE actor = ? AND action = ?", "jane", "assistant.memory_forgot"));

        cr.platform.updateSettings(admin, Map.of("chat_engine", "builtin"), null);
        Map<String, Object> builtIn = (Map<String, Object>) json(call("GET", "/api/assistants/me", null, janeToken)).get("memory");
        assertNull(builtIn.get("kept_by"), "with the built-in engine, One CommandCenter keeps the notes");
    }

    // ---- callbacks ----

    @Test void callbacksCheckTheServiceTokenTheTenantAndThePersonToken() throws Exception {
        assertEquals(200, callback("GET", "version", null, SERVICE_TOKEN, null, null).statusCode(), "version needs no person token");
        assertEquals(200, callback("GET", "health", null, SERVICE_TOKEN, null, null).statusCode());
        assertEquals(401, callback("GET", "version", null, null, null, null).statusCode(), "the service token is needed");
        assertEquals(401, callback("GET", "health", null, "not-the-token", null, null).statusCode());

        String jane = tokens.issue(ctx.activeUser("jane"), "chat", "s1", "10.0.0.7");
        assertEquals(200, callback("GET", "person", null, SERVICE_TOKEN, "default", jane).statusCode());
        assertEquals(401, callback("GET", "person", null, SERVICE_TOKEN, "default", null).statusCode(), "the service token alone acts for nobody");
        assertEquals(401, callback("GET", "person", null, "not-the-token", "default", jane).statusCode());
        assertEquals(401, callback("GET", "person", null, SERVICE_TOKEN, "acme", jane).statusCode(), "another tenant");
        String[] p = jane.split("\\.");
        String forged = p[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8).replace("\"jane\"", "\"admin\"").getBytes(StandardCharsets.UTF_8)) + "." + p[2];
        assertEquals(401, callback("GET", "person", null, SERVICE_TOKEN, "default", forged).statusCode(), "a changed token is refused");
        String expired = tokens.issue(ctx.activeUser("jane"), "chat", "s1", null, Instant.now().minusSeconds(400));
        assertEquals(401, callback("GET", "person", null, SERVICE_TOKEN, "default", expired).statusCode(), "an expired token is refused");

        String val = tokens.issue(ctx.activeUser("val"), "chat", null, null);
        db.update("UPDATE users SET enabled = ? WHERE username = ?", false, "val");
        try { assertEquals(401, callback("GET", "person", null, SERVICE_TOKEN, "default", val).statusCode(), "a disabled person is refused at once"); }
        finally { db.update("UPDATE users SET enabled = ? WHERE username = ?", true, "val"); }

        cr.platform.updateSettings(admin, Map.of("chat_engine", "builtin"), null);
        assertEquals(403, callback("GET", "person", null, SERVICE_TOKEN, "default", jane).statusCode(), "the service is not chosen");
        assertEquals(200, callback("GET", "version", null, SERVICE_TOKEN, null, null).statusCode());
        assertNotNull(db.one("SELECT seq FROM audit WHERE action = ? LIMIT ?", "onechat.callback_refused", 1), "refused callbacks are in the Audit Trail");
    }

    @Test void eachAreaKeepsToItsList() throws Exception {
        assertEquals(200, as("bob", "studio", "GET", "person", null).statusCode(), "a builder in the Studio area");
        assertEquals(200, as("bob", "studio", "GET", "settings", null).statusCode());
        for (String route : List.of("agents", "runs", "tasks", "admin/tools"))
            assertEquals(403, as("bob", "studio", "GET", route, null).statusCode(), "Studio's assistant is for development only: " + route);
        assertEquals(403, as("jane", "studio", "GET", "person", null).statusCode(), "the Studio area needs the right to check in agents");
        assertEquals(403, as("jane", "chat", "GET", "nothing/here", null).statusCode(), "a route on no list");
        assertEquals(403, as("jane", "chat", "GET", "studio/commands", null).statusCode(), "the chat area has no Studio routes");
    }

    @Test @SuppressWarnings("unchecked")
    void readOnlyCallbacksFollowTheSettingsAndPermissions() throws Exception {
        Map<String, Object> person = json(as("jane", "chat", "GET", "person", null));
        assertEquals("jane", person.get("username"));
        assertEquals(true, ((Map<String, Object>) person.get("may")).get("start_runs"));

        HttpResponse<String> s = as("jane", "chat", "GET", "settings", null);
        Map<String, Object> settings = json(s);
        assertEquals("company", settings.get("model"));
        assertEquals("always", ((Map<String, Object>) settings.get("tools")).get("read"));
        assertEquals("never", ((Map<String, Object>) settings.get("tools")).get("admin_read"));
        assertFalse(s.body().contains("base_url") || s.body().contains("api_key"), "never a connection's details or a secret");

        cr.platform.updateSettings(admin, Map.of("chat_skills", "tone"), null);
        assertEquals("Use the customer's name.", json(as("jane", "chat", "GET", "skills/tone", null)).get("system"));
        assertEquals(404, as("jane", "chat", "GET", "skills/secret_draft", null).statusCode(), "only the skills the area may use");

        List<Map<String, Object>> agents = (List<Map<String, Object>>) json(as("jane", "chat", "GET", "agents", null)).get("agents");
        Map<String, Object> invoices = agents.stream().filter(a -> ChatTest.INVOICES.equals(a.get("path"))).findFirst().orElseThrow();
        assertEquals("R2", invoices.get("risk"));
        assertEquals(List.of("post today's invoices for acme"), ((Map<String, Object>) invoices.get("chat")).get("examples"));
        assertEquals(0, ((List<?>) json(as("val", "chat", "GET", "agents", null)).get("agents")).size(), "a viewer may not run agents");
        cr.platform.updateSettings(admin, Map.of("chat_tool_agents", "never"), null);
        assertEquals(403, as("jane", "chat", "GET", "agents", null).statusCode(), "Never is refused");

        assertEquals(200, as("jane", "chat", "GET", "runs?limit=5", null).statusCode());
        assertEquals(404, as("jane", "chat", "GET", "runs/run_nope", null).statusCode());
        cr.platform.updateSettings(admin, Map.of("chat_tool_read", "never"), null);
        assertEquals(403, as("jane", "chat", "GET", "runs", null).statusCode());
        cr.platform.updateSettings(admin, Map.of("chat_tool_read", "always"), null);

        db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, assignee, status, created) VALUES (?,?,?,?,?,?,?,?)",
                "task_jane_1", null, "review", "approval", "Check the Acme invoice", "user:jane", "open", Instant.now());
        db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, assignee, status, created) VALUES (?,?,?,?,?,?,?,?)",
                "task_val_1", null, "review", "approval", "Not jane's", "user:val", "open", Instant.now());
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) json(as("jane", "chat", "GET", "tasks", null)).get("tasks");
        assertEquals(List.of("task_jane_1"), tasks.stream().map(t -> t.get("task_id")).toList(), "only the person's own tasks");

        assertEquals(List.of(), json(as("jane", "chat", "GET", "admin/tools", null)).get("tools"), "no administration for jane");
        assertEquals(List.of(), json(as("admin", "chat", "GET", "admin/tools", null)).get("tools"), "administration is Never at first");
        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_read", "always"), null);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) json(as("admin", "chat", "GET", "admin/tools", null)).get("tools");
        assertTrue(tools.stream().anyMatch(t -> "people".equals(t.get("id"))), tools.toString());
        assertTrue(tools.stream().allMatch(t -> "admin_read".equals(t.get("group"))), "only the groups allowed");
    }

    @Test void modelCallsUseTheAreasSettingsAndLimits() throws Exception {
        Map<String, Object> call = Map.of("messages", List.of(Map.of("role", "user", "content", "post the invoices")), "purpose", "answer");
        HttpResponse<String> r = as("jane", "chat", "POST", "model", call);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("Which vendor do you mean?", json(r).get("text"));
        Map<String, Object> rec = db.one("SELECT bot_path, model FROM ai_calls WHERE requested_by = ? ORDER BY ts DESC LIMIT ?", "jane", 1);
        assertEquals("One Chat", rec.get("bot_path"), "recorded as One Chat's");
        assertEquals("company-model", rec.get("model"));

        Map<String, Object> other = new LinkedHashMap<>(call);
        other.put("connection", "other");
        assertEquals(403, as("jane", "chat", "POST", "model", other).statusCode(), "a model the person may not choose");
        assertEquals(400, as("jane", "chat", "POST", "model", Map.of("messages", List.of(Map.of("role", "boss", "content", "x")))).statusCode());

        cr.platform.updateSettings(admin, Map.of("chat_ktokens_per_person", 1), null);
        db.update("INSERT INTO ai_calls (id, bot_path, requested_by, ts, purpose, input_tokens, output_tokens) VALUES (?,?,?,?,?,?,?)",
                "aic_big", "One Chat", "jane", Instant.now(), "answer", 900, 200);
        assertEquals(429, as("jane", "chat", "POST", "model", call).statusCode(), "over the person's limit");

        assertEquals(200, as("jane", "chat", "POST", "audit", Map.of("action", "chat.answered", "object_id", "s1", "details", Map.of("words", 12))).statusCode());
        Map<String, Object> a = db.one("SELECT actor, ip FROM audit WHERE action = ? ORDER BY seq DESC LIMIT ?", "chat.answered", 1);
        assertEquals("jane", a.get("actor"));
        assertEquals("10.0.0.7", a.get("ip"), "the address the person's request came from");
        assertEquals(400, as("jane", "chat", "POST", "audit", Map.of("action", "users.delete")).statusCode(), "only chat. actions in the chat area");
        assertEquals(400, as("bob", "studio", "POST", "audit", Map.of("action", "chat.answered")).statusCode(), "only studio. actions in the Studio area");
    }

    // ---- S5b: runs and administration with One CommandCenter's own confirmation; notices; events; Studio ----

    @Test @SuppressWarnings("unchecked")
    void runsWaitForAYesFromALaterMessage() throws Exception {
        Instant asked = Instant.now().minusSeconds(20), later = Instant.now();
        long before = ((Number) db.one("SELECT COUNT(*) AS n FROM runs").get("n")).longValue();
        Map<String, Object> run = Map.of("agent", ChatTest.INVOICES, "inputs", Map.of("vendor", "Acme", "not_declared", "x"), "session_id", "s1");
        String askToken = tokens.issue(ctx.activeUser("jane"), "chat", "s1", "10.0.0.7", asked);
        Map<String, Object> confirm = (Map<String, Object>) json(callback("POST", "runs", JsonLite.write(run), SERVICE_TOKEN, "default", askToken)).get("confirm");
        assertNotNull(confirm, "an R2 agent answers a confirmation first");
        assertEquals("Run Process vendor invoices with vendor = Acme.", confirm.get("summary"), "undeclared inputs are dropped");
        assertEquals("R2", confirm.get("risk"));
        assertEquals(before, ((Number) db.one("SELECT COUNT(*) AS n FROM runs").get("n")).longValue(), "nothing runs before the yes");
        String id = (String) confirm.get("confirmation_id");
        Map<String, Object> yes = new LinkedHashMap<>(run);
        yes.put("confirmation_id", id);

        assertEquals(403, callback("POST", "runs", JsonLite.write(yes), SERVICE_TOKEN, "default", askToken).statusCode(), "the yes must come from a later message");
        assertEquals(403, at(asked.minusSeconds(60), "jane", "POST", "runs", yes).statusCode(), "nor from an earlier one");
        Map<String, Object> other = new LinkedHashMap<>(yes);
        other.put("inputs", Map.of("vendor", "Globex"));
        assertEquals(403, at(later, "jane", "POST", "runs", other).statusCode(), "other inputs than the summary");
        Map<String, Object> otherChat = new LinkedHashMap<>(yes);
        otherChat.put("session_id", "s2");
        assertEquals(403, at(later, "jane", "POST", "runs", otherChat).statusCode(), "another chat");
        assertEquals(403, at(later, "bob", "POST", "runs", yes).statusCode(), "another person");

        HttpResponse<String> ok = at(later, "jane", "POST", "runs", yes);
        assertEquals(200, ok.statusCode(), ok.body());
        String runId = (String) json(ok).get("run_id");
        Map<String, Object> row = db.one("SELECT requested_by, source FROM runs WHERE id = ?", runId);
        assertEquals("jane", row.get("requested_by"));
        assertEquals("onechat", row.get("source"));
        assertEquals(409, at(later, "jane", "POST", "runs", yes).statusCode(), "each yes is used once");

        Map<String, Object> again = (Map<String, Object>) json(at(asked, "jane", "POST", "runs", run)).get("confirm");
        db.update("UPDATE onechat_confirmations SET expires = ? WHERE id = ?", Instant.now().minusSeconds(60), again.get("confirmation_id"));
        Map<String, Object> late = new LinkedHashMap<>(run);
        late.put("confirmation_id", again.get("confirmation_id"));
        assertEquals(409, at(later, "jane", "POST", "runs", late).statusCode(), "after ten minutes the yes is too late");

        assertEquals(400, at(asked, "jane", "POST", "runs", Map.of("agent", ChatTest.INVOICES, "inputs", Map.of(), "session_id", "s1")).statusCode(), "a required input is missing");
        assertEquals(400, at(asked, "jane", "POST", "runs", Map.of("agent", ChatTest.INVOICES, "inputs", Map.of("vendor", "Nobody"), "session_id", "s1")).statusCode(), "not one of the allowed values");
        assertEquals("refused", ((Map<String, Object>) json(at(asked, "val", "POST", "runs", run)).get("refused")).get("kind"), "a viewer may not run agents");

        Map<String, Object> aging = Map.of("agent", ChatTest.AGING, "inputs", Map.of(), "session_id", "s1");
        assertNotNull(json(at(asked, "jane", "POST", "runs", aging)).get("run_id"), "an R0 agent under Always starts at once");
        cr.platform.updateSettings(admin, Map.of("chat_tool_agents", "ask"), null);
        Map<String, Object> asksFirst = (Map<String, Object>) json(at(asked, "jane", "POST", "runs", aging)).get("confirm");
        assertEquals("Your company asks first for this kind of request.", asksFirst.get("reason"), "Ask first makes even R0 wait");
        cr.platform.updateSettings(admin, Map.of("chat_tool_agents", "never"), null);
        assertEquals(403, at(asked, "jane", "POST", "runs", aging).statusCode(), "Never is refused");
    }

    @Test @SuppressWarnings("unchecked")
    void runEndsAndDecisionsReachTheServiceAsEvents() throws Exception {
        Instant asked = Instant.now().minusSeconds(20), later = Instant.now();
        String runId = (String) json(at(asked, "jane", "POST", "runs", Map.of("agent", ChatTest.AGING, "inputs", Map.of(), "session_id", "s1"))).get("run_id");
        db.update("UPDATE runs SET status = 'running', started = CURRENT_TIMESTAMP WHERE id = ?", runId);
        cr.finish(db.one("SELECT * FROM devices WHERE id = ?", device), runId, Map.of("status", "success", "outputs", Map.of("note", "ok")));
        Map<String, Object> ended = awaitEvent("run_ended", e -> runId.equals(e.get("run_id")));
        assertEquals("success", ended.get("status"));
        assertEquals("ok", ((Map<String, Object>) ended.get("results")).get("note"));
        assertEquals("s1", ended.get("session_id"));
        Map<String, Object> seen = received.stream().filter(r -> ended.equals(r.get("body"))).findFirst().orElseThrow();
        String payload = new String(Base64.getUrlDecoder().decode(String.valueOf(seen.get("X-OOA-Person")).split("\\.")[1]), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"sub\":\"jane\""), "a fresh person token for the person: " + payload);

        // R3: an Approve task for someone else; the service hears the decision
        Map<String, Object> pay = Map.of("agent", ChatTest.PAYMENT, "inputs", Map.of(), "session_id", "s1");
        Map<String, Object> confirm = (Map<String, Object>) json(at(asked, "jane", "POST", "runs", pay)).get("confirm");
        assertTrue(String.valueOf(confirm.get("summary")).contains("Someone else approves it"), String.valueOf(confirm));
        Map<String, Object> yes = new LinkedHashMap<>(pay);
        yes.put("confirmation_id", confirm.get("confirmation_id"));
        String task = (String) ((Map<String, Object>) json(at(later, "jane", "POST", "runs", yes)).get("approval")).get("task_id");
        eventsFail = true;
        Map<String, Object> done = cr.tasks.complete(olga, task, "approved", Map.of(), "fine", null);
        String paid = (String) done.get("run_id");
        assertNotNull(paid, "approved: the run starts as jane");
        assertEquals("jane", db.one("SELECT requested_by FROM runs WHERE id = ?", paid).get("requested_by"));
        // the service is busy: the event waits and is tried again
        for (int i = 0; i < 300 && db.one("SELECT id FROM onechat_events WHERE attempts > ?", 0) == null; i++) Thread.sleep(100);
        assertNotNull(db.one("SELECT id FROM onechat_events WHERE attempts > ?", 0), "a failed delivery is kept for another try");
        eventsFail = false;
        db.update("UPDATE onechat_events SET next_try = ?", Instant.now().minusSeconds(1));
        Map<String, Object> decided = awaitEvent("approval_decided", e -> task.equals(e.get("task_id")));
        assertEquals("approved", decided.get("decision"));
        assertEquals("olga", decided.get("approver"));
        assertEquals(paid, decided.get("run_id"));
        assertNotNull(db.one("SELECT id FROM human_tasks WHERE assignee = ? AND kind = ? AND title LIKE ?", "user:jane", "notification", "Approved:%"), "jane gets a notice");
    }

    @Test @SuppressWarnings("unchecked")
    void administrationFollowsTheSameRules() throws Exception {
        Instant asked = Instant.now().minusSeconds(20), later = Instant.now();
        assertEquals(403, at(asked, "admin", "POST", "admin/actions", Map.of("tool", "people", "inputs", Map.of(), "session_id", "s1")).statusCode(), "administration is Never at first");
        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_read", "always", "chat_tool_admin_change", "always", "chat_tool_admin_high", "always"), null);
        Map<String, Object> people = (Map<String, Object>) json(at(asked, "admin", "POST", "admin/actions", Map.of("tool", "people", "inputs", Map.of("which", "everyone"), "session_id", "s1"))).get("result");
        assertNotNull(people, "a look-up answers at once");
        assertEquals("refused", ((Map<String, Object>) json(at(asked, "jane", "POST", "admin/actions", Map.of("tool", "people", "inputs", Map.of(), "session_id", "s1"))).get("refused")).get("kind"),
                "not without the console permission");

        Map<String, Object> disable = Map.of("tool", "disable_person", "inputs", Map.of("person", "bob"), "session_id", "s1");
        Map<String, Object> confirm = (Map<String, Object>) json(at(asked, "admin", "POST", "admin/actions", disable)).get("confirm");
        assertTrue(String.valueOf(confirm.get("undo")).startsWith("Yes:"), "what undoing it takes: " + confirm);
        Map<String, Object> yes = new LinkedHashMap<>(disable);
        yes.put("confirmation_id", confirm.get("confirmation_id"));
        assertNotNull(json(at(later, "admin", "POST", "admin/actions", yes)).get("result"));
        assertEquals(false, db.one("SELECT enabled FROM users WHERE username = ?", "bob").get("enabled"));
        db.update("UPDATE users SET enabled = ? WHERE username = ?", true, "bob");

        cr.createUser(admin, "leaver", "leaver", "viewer", null);
        Map<String, Object> delete = Map.of("tool", "delete_person", "inputs", Map.of("person", "leaver"), "session_id", "s1");
        Map<String, Object> high = (Map<String, Object>) json(at(asked, "admin", "POST", "admin/actions", delete)).get("confirm");
        Map<String, Object> yes3 = new LinkedHashMap<>(delete);
        yes3.put("confirmation_id", high.get("confirmation_id"));
        String task = (String) ((Map<String, Object>) json(at(later, "admin", "POST", "admin/actions", yes3)).get("approval")).get("task_id");
        assertNotNull(db.one("SELECT username FROM users WHERE username = ?", "leaver"), "nothing happens before the approval");
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.tasks.complete(admin, task, "approved", Map.of(), null, null)).code(), "never your own request");
        cr.tasks.complete(olga, task, "approved", Map.of(), null, null);
        assertNull(db.one("SELECT username FROM users WHERE username = ?", "leaver"), "done as the person who asked once someone else approved");
        assertEquals("approved", awaitEvent("approval_decided", e -> task.equals(e.get("task_id"))).get("decision"));

        cr.platform.updateSettings(admin, Map.of("chat_tool_admin_change", "never"), null);
        assertEquals(403, at(asked, "admin", "POST", "admin/actions", disable).statusCode(), "Never is refused");
    }

    @Test void noticesGoOnlyToThePersonAndAreLimited() throws Exception {
        HttpResponse<String> r = as("jane", "chat", "POST", "notices", Map.of("title", "Your report is ready", "message", "See the run's results."));
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> t = db.one("SELECT assignee, kind FROM human_tasks WHERE id = ?", json(r).get("task_id"));
        assertEquals("user:jane", t.get("assignee"));
        assertEquals("notification", t.get("kind"));
        assertEquals(400, as("jane", "chat", "POST", "notices", Map.of("title", "", "message", "x")).statusCode());
        for (int i = 1; i < 30; i++) as("jane", "chat", "POST", "notices", Map.of("title", "n" + i, "message", "m"));
        assertEquals(429, as("jane", "chat", "POST", "notices", Map.of("title", "one more", "message", "m")).statusCode(), "at most 30 an hour");
        assertEquals(403, as("bob", "studio", "POST", "notices", Map.of("title", "x", "message", "y")).statusCode(), "not from the Studio area");
    }

    @Test @SuppressWarnings("unchecked")
    void theStudioAreaHelpsWithDevelopmentOnly() throws Exception {
        List<?> toolkits = (List<?>) json(as("bob", "studio", "GET", "studio/commands", null)).get("toolkits");
        assertFalse(toolkits.isEmpty(), "the commands Studio offers");
        Map<String, Object> good = json(as("bob", "studio", "POST", "studio/check", Map.of("variables", List.of(Map.of("name", "x", "type", "String", "direction", "local")),
                "steps", List.of(Map.of("id", "1", "action", "core/set", "args", Map.of("variable", "x", "value", "1"))))));
        assertEquals(true, good.get("ok"), String.valueOf(good));
        Map<String, Object> bad = json(as("bob", "studio", "POST", "studio/check", Map.of("steps", List.of(Map.of("id", "1", "action", "nope/none")))));
        assertEquals(false, bad.get("ok"), "an unknown command is a problem");
        List<Map<String, Object>> agents = (List<Map<String, Object>>) json(as("bob", "studio", "GET", "studio/agents", null)).get("agents");
        Map<String, Object> invoices = agents.stream().filter(a -> ChatTest.INVOICES.equals(a.get("path"))).findFirst().orElseThrow();
        assertEquals(List.of("vendor"), ((List<Map<String, Object>>) invoices.get("inputs")).stream().map(v -> v.get("name")).toList());
        assertEquals(List.of("note"), ((List<Map<String, Object>>) invoices.get("outputs")).stream().map(v -> v.get("name")).toList());
        assertEquals(403, as("bob", "studio", "POST", "runs", Map.of("agent", ChatTest.AGING, "inputs", Map.of(), "session_id", "s1")).statusCode(), "never runs from Studio");

        // Studio's assistant goes to the service, with Studio's instructions as context (bob signs in afresh: another test disables him for a moment)
        String bobToken = (String) cr.login("bob", "Passw0rd-bob-2026", null).get("token");
        int before = received.size();
        HttpResponse<String> r = call("POST", "/api/assistants/studio/complete", JsonLite.write(Map.of("system", "Design steps for agents.", "prompt", "Read a file",
                "agent", Map.of("name", "Draft"))), bobToken);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(Map.of("steps", List.of(), "notes", "for bob"), JsonLite.parse(String.valueOf(json(r).get("text"))), "the service's proposal, for Studio to check");
        Map<String, Object> sent = received.get(before);
        assertEquals("studio", sent.get("X-OOA-Area"));
        Map<String, Object> context = (Map<String, Object>) ((Map<String, Object>) sent.get("body")).get("context");
        assertEquals("Design steps for agents.", context.get("instructions"));
        assertEquals(Map.of("name", "Draft"), context.get("agent"));
        assertEquals(403, call("POST", "/api/assistants/studio/complete", JsonLite.write(Map.of("system", "s", "prompt", "p")), valToken).statusCode(), "builders only");
    }

    @Test void anUnreadableSigningKeyIsReplaced() {
        tokens.issue(ctx.activeUser("jane"), "chat", "s1", null);   // the key exists
        String before = (String) db.one("SELECT secret FROM onechat_keys WHERE id = ?", "person-token").get("secret");
        try {
            // what a vault key rotation or a move from another region leaves: a stored key this vault cannot read
            db.update("UPDATE onechat_keys SET secret = ? WHERE id = ?", "not-readable-any-more", "person-token");
            String t = new PersonTokens(ctx).issue(ctx.activeUser("jane"), "chat", "s1", null);
            assertNotNull(new PersonTokens(ctx).check(t, "default"), "a new key, which every node then reads");
            assertNotEquals("not-readable-any-more", db.one("SELECT secret FROM onechat_keys WHERE id = ?", "person-token").get("secret"));
            assertNotNull(db.one("SELECT seq FROM audit WHERE action = ?", "onechat.key_replaced"), "recorded in the Audit Trail");
        } finally { db.update("UPDATE onechat_keys SET secret = ? WHERE id = ?", before, "person-token"); }
    }
}
