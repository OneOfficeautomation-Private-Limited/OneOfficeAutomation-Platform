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
    Db db; ControlRoom cr; ControlRoomServer server; ControlRoom.User admin; ModuleContext ctx; PersonTokens tokens;
    HttpServer model, standin; String base, adminToken, janeToken, valToken;
    volatile String standinVersion = "1.0.0", standinContract = "v1";
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
        tokenOf("bob", "bot_creator");
        cr.checkin(admin, ChatTest.INVOICES, ChatTest.flow("process_invoices", "Process vendor invoices", "Posts a vendor's open invoices to SAP.",
                "[{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"Acme\",\"Globex\"]}]",
                "{\"examples\":[\"post today's invoices for acme\"],\"risk\":\"R2\"}"), "v1", true, null);

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
                if (m.equals("POST") && path.equals("messages")) {
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
                "chat_skills", "", "chat_ktokens_per_person", 0, "onechat_url", ""), null);
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
        assertEquals("Hello jane; you may run 1 agents.", r.get("reply"), "the service learnt who and what through the callbacks");

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
        assertEquals(ChatTest.INVOICES, agents.get(0).get("path"));
        assertEquals("R2", agents.get(0).get("risk"));
        assertEquals(List.of("post today's invoices for acme"), ((Map<String, Object>) agents.get(0).get("chat")).get("examples"));
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
}
