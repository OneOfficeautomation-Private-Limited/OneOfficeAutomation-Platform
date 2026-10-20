package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The trigger API: every kind, validation, monitoring fields, pause and resume, runner reports and server polling. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TriggerApiTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; HttpServer fake;
    String base, token, deviceId, deviceSecret;
    final List<Object> orders = new CopyOnWriteArrayList<>();

    record R(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked") String code() { return String.valueOf(((Map<String, Object>) body.getOrDefault("error", Map.of())).get("code")); }
    }

    @SuppressWarnings("unchecked")
    R call(String method, String path, Object body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (headers == null) b.header("Authorization", "Bearer " + token); else headers.forEach(b::header);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Object parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        return new R(r.statusCode(), parsed instanceof Map ? (Map<String, Object>) parsed : Map.of("list", parsed));
    }
    R call(String m, String p, Object b) throws Exception { return call(m, p, b, null); }
    R agent(String p, Object b) throws Exception { return call("POST", "/api/agent/" + p, b, Map.of("X-OOA-Device", deviceId, "X-OOA-Device-Secret", deviceSecret)); }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-trigger-api");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        token = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        ControlRoom.User admin = cr.session(token);
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"on_event\",\"name\":\"On event\",\"description\":\"Reacts to triggers\","
                + "\"variables\":[{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"trigger_event\",\"type\":\"Dictionary\",\"direction\":\"input\"},"
                + "{\"name\":\"trigger_item\",\"type\":\"Dictionary\",\"direction\":\"input\"},{\"name\":\"trigger_window\",\"type\":\"String\",\"direction\":\"input\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"ok\"}}]}");
        cr.checkin(admin, "Ops/On event.flow.json", flow, "first", true, null);
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        deviceId = (String) dev.get("device_id"); deviceSecret = (String) dev.get("secret");
        cr.createLocker(admin, "api", "API tokens", "user:admin", null);
        cr.setCredential(admin, "api", "orders", "token", "tok-xyz", null);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/orders", ex -> {
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = (auth.equals("Bearer tok-xyz") ? JsonLite.write(Map.of("items", orders)) : "{\"error\":\"unauthorized\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(auth.equals("Bearer tok-xyz") ? 200 : 401, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        fake.start();
    }

    @AfterAll void stop() { server.stop(); fake.stop(0); cr.triggerPollers.stop(); db.close(); }

    Map<String, Object> body(String kind, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("name", kind + " trigger", "bot_path", "Ops/On event.flow.json", "device_id", deviceId, "kind", kind));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    String create(Map<String, Object> body) throws Exception {
        R r = call("POST", "/api/triggers", body);
        assertEquals(200, r.status(), body.get("kind") + ": " + r.body());
        return (String) r.body().get("id");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> listed(String id) throws Exception {
        for (Object o : (List<Object>) call("GET", "/api/triggers", null).body().get("triggers")) if (id.equals(((Map<String, Object>) o).get("id"))) return (Map<String, Object>) o;
        throw new AssertionError("trigger " + id + " not listed");
    }

    @Test void everyKindCanBeCreatedAndIsListedWithItsSettings() throws Exception {
        Path dir = Files.createTempDirectory("ooa-in");
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        for (String k : List.of("file", "file_changed", "file_deleted", "folder_created", "folder_deleted")) bodies.put(k, body(k, "folder", dir.toString(), "pattern", "*.csv", "recursive", true));
        bodies.put("window_opened", body("window_opened", "title", "*Invoice*"));
        bodies.put("window_closed", body("window_closed", "config", Map.of("title", "re:^ERP")));
        bodies.put("process_started", body("process_started", "process", "excel.exe"));
        bodies.put("process_stopped", body("process_stopped", "process", "excel.exe"));
        bodies.put("service_started", body("service_started", "service", "Spooler"));
        bodies.put("service_stopped", body("service_stopped", "service", "Spooler"));
        bodies.put("hotkey", body("hotkey", "hotkey", "ctrl+alt+f9"));
        bodies.put("email", body("email", "host", "imap.example.com", "username", "ap@example.com", "password_ref", "mail/imap/password", "subject_contains", "Invoice", "interval_seconds", 120));
        bodies.put("outlook", body("outlook", "token_ref", "m365/graph/token", "mailbox", "ap@contoso.com"));
        bodies.put("servicenow", body("servicenow", "instance_url", "https://acme.service-now.com", "table", "incident", "query", "priority=1", "username", "svc", "password_ref", "snow/api/password"));
        bodies.put("http_poll", body("http_poll", "url", "https://api.example.com/orders", "items_path", "$.items[*]", "id_field", "id", "auth_ref", "api/orders/token", "headers", Map.of("X-Tenant", "t1")));
        bodies.put("webhook", body("webhook"));
        Map<String, String> ids = new LinkedHashMap<>();
        for (var e : bodies.entrySet()) ids.put(e.getKey(), create(e.getValue()));

        Map<String, Object> email = listed(ids.get("email"));
        assertEquals("server", email.get("evaluated_on"));
        assertEquals(0, ((Number) email.get("fire_count")).intValue());
        assertEquals("active", email.get("status"));
        @SuppressWarnings("unchecked") Map<String, Object> cfg = (Map<String, Object>) email.get("config");
        assertEquals(993, ((Number) cfg.get("port")).intValue(), "SSL default port");
        assertEquals("mail/imap/password", cfg.get("password_ref"));
        assertEquals(120, ((Number) cfg.get("interval_seconds")).intValue());
        assertEquals("CTRL+ALT+F9", ((Map<?, ?>) listed(ids.get("hotkey")).get("config")).get("hotkey"));
        assertEquals(true, listed(ids.get("service_started")).get("windows_only"));
        assertEquals("runner", listed(ids.get("file_deleted")).get("evaluated_on"));
        assertEquals("re:^ERP", ((Map<?, ?>) listed(ids.get("window_closed")).get("config")).get("title"), "settings may also come under config");

        // the runner learns about its runner-side triggers only
        R hb = agent("heartbeat", Map.of("agent_version", "0.2.0"));
        String local = JsonLite.write(hb.body().get("local_triggers"));
        for (String k : List.of("file", "file_deleted", "window_opened", "process_stopped", "service_started", "hotkey")) assertTrue(local.contains(ids.get(k)), k);
        for (String k : List.of("email", "outlook", "servicenow", "http_poll", "webhook")) assertFalse(local.contains(ids.get(k)), k);
        assertTrue(JsonLite.write(hb.body().get("file_triggers")).contains(ids.get("file")), "older runners still get their file triggers");
        assertFalse(JsonLite.write(hb.body().get("file_triggers")).contains(ids.get("file_deleted")));
    }

    @Test void invalidTriggersAreRefused() throws Exception {
        Map<String, Map<String, Object>> bad = new LinkedHashMap<>();
        bad.put("unknown kind", body("teleport"));
        bad.put("plain password", body("email", "host", "h", "username", "u", "password_ref", "hunter2"));
        bad.put("too frequent", body("http_poll", "url", "https://x.example.com", "id_field", "id", "interval_seconds", 5));
        bad.put("auth header", body("http_poll", "url", "https://x.example.com", "id_field", "id", "headers", Map.of("Authorization", "Bearer x")));
        bad.put("not http", body("http_poll", "url", "file:///etc/passwd", "id_field", "id"));
        bad.put("no title", body("window_opened"));
        bad.put("bad regex", body("window_opened", "title", "re:("));
        bad.put("path as process", body("process_started", "process", "C:\\x\\excel.exe"));
        bad.put("bad service", body("service_started", "service", "Spool;er"));
        bad.put("bad hotkey", body("hotkey", "hotkey", "CTRL+ALT+F9!"));
        bad.put("servicenow without credentials", body("servicenow", "instance_url", "https://a.service-now.com", "table", "incident"));
        bad.put("outlook app without mailbox", body("outlook", "tenant_id", "t", "client_id", "c", "client_secret_ref", "a/b/c"));
        bad.put("no folder", body("folder_deleted"));
        for (var e : bad.entrySet()) {
            R r = call("POST", "/api/triggers", e.getValue());
            assertEquals(400, r.status(), e.getKey());
            assertEquals("CR.BAD_TRIGGER", r.code(), e.getKey() + ": " + r.body());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void runnerReportsFireAgentsAndAreMonitored() throws Exception {
        String id = create(body("window_opened", "title", "*Invoice*"));
        R fired = agent("trigger-fired", Map.of("trigger_id", id, "event", Map.of("title", "Invoice 42 - Notepad")));
        assertEquals(200, fired.status(), String.valueOf(fired.body()));
        Map<String, Object> run = db.one("SELECT source, inputs FROM runs WHERE id = ?", fired.body().get("run_id"));
        assertEquals("trigger:" + id, run.get("source"));
        Map<String, Object> inputs = (Map<String, Object>) JsonLite.parse((String) run.get("inputs"));
        assertEquals("Invoice 42 - Notepad", inputs.get("trigger_window"));
        assertEquals("window_opened", ((Map<String, Object>) inputs.get("trigger_event")).get("kind"));
        assertFalse(inputs.containsKey("trigger_file"), "only declared inputs are passed");
        Map<String, Object> t = listed(id);
        assertEquals(1, ((Number) t.get("fire_count")).intValue());
        assertNotNull(t.get("last_fired"));

        assertEquals(200, agent("trigger-status", Map.of("trigger_id", id, "error", "Window triggers work on Windows runners only")).status());
        assertEquals("error", listed(id).get("status"));
        assertTrue(String.valueOf(listed(id).get("last_error")).contains("Windows"));
        Map<String, Object> clear = new HashMap<>(); clear.put("trigger_id", id); clear.put("error", null);
        agent("trigger-status", clear);
        assertNull(listed(id).get("last_error"));

        assertEquals(200, call("POST", "/api/triggers/" + id + "/pause", null).status());
        assertEquals("paused", listed(id).get("status"));
        assertEquals(404, agent("trigger-fired", Map.of("trigger_id", id, "event", Map.of("title", "x"))).status(), "paused triggers do not fire");
        assertFalse(JsonLite.write(agent("heartbeat", Map.of()).body().get("local_triggers")).contains(id), "and are not watched");
        assertEquals(200, call("POST", "/api/triggers/" + id + "/resume", null).status());
        assertEquals("active", listed(id).get("status"));

        String server = create(body("http_poll", "url", "https://api.example.com/x", "id_field", "id"));
        assertEquals("CR.BAD_TRIGGER", agent("trigger-fired", Map.of("trigger_id", server, "event", Map.of())).code(), "runners cannot fire server triggers");
        assertEquals("CR.NOT_FOUND", call("POST", "/api/triggers/nope/pause", null).code());
    }

    @SuppressWarnings("unchecked")
    @Test void universalPollerStartsOneRunPerNewItem() throws Exception {
        orders.clear();
        orders.add(Map.of("id", 1, "customer", "Acme"));
        String url = "http://127.0.0.1:" + fake.getAddress().getPort() + "/orders";
        String id = create(body("http_poll", "url", url, "items_path", "$.items", "id_field", "id", "auth_ref", "api/orders/token", "interval_seconds", 15));
        assertEquals(0, ((Number) call("POST", "/api/triggers/" + id + "/poll", null).body().get("fired")).intValue(), "the first check only remembers");
        orders.add(Map.of("id", 2, "customer", "Globex"));
        orders.add(Map.of("id", 3, "customer", "Initech"));
        assertEquals(2, ((Number) call("POST", "/api/triggers/" + id + "/poll", null).body().get("fired")).intValue());
        assertEquals(0, ((Number) call("POST", "/api/triggers/" + id + "/poll", null).body().get("fired")).intValue());
        List<Map<String, Object>> runs = db.query("SELECT inputs FROM runs WHERE source = ? ORDER BY created", "trigger:" + id);
        assertEquals(2, runs.size());
        Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>) JsonLite.parse((String) runs.get(0).get("inputs"))).get("trigger_item");
        assertTrue(Set.of("Globex", "Initech").contains(item.get("customer")), String.valueOf(item));
        Map<String, Object> t = listed(id);
        assertEquals(2, ((Number) t.get("fire_count")).intValue());
        assertNotNull(t.get("last_fired"));
        assertFalse(((Map<String, Object>) t.get("config")).containsKey("_seen"), "internal state is not shown");
        assertFalse(JsonLite.write(t).contains("tok-xyz"), "secrets never appear");
        assertTrue(JsonLite.write(call("GET", "/api/audit?q=trigger:", null).body()).contains("credential.read"), "vault use by triggers is audited");

        // a vault folder the owner may not use: the poll fails and says why
        String denied = create(body("http_poll", "url", url, "items_path", "$.items", "id_field", "id", "auth_ref", "other/orders/token"));
        R r = call("POST", "/api/triggers/" + denied + "/poll", null);
        assertTrue(String.valueOf(r.body().get("error")).contains("may not use vault folder"), String.valueOf(r.body()));
        assertEquals("error", listed(denied).get("status"));
        call("POST", "/api/triggers/" + denied + "/resume", null);
        assertNull(listed(denied).get("last_error"), "resume clears the error");

        // the server clock polls due triggers on its own
        orders.add(Map.of("id", 4, "customer", "Umbrella"));
        db.update("UPDATE triggers SET enabled = FALSE WHERE id <> ?", id);   // only this one should reach out
        cr.triggerPollers.reset(id);
        cr.triggerPollers.tick();
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end && db.query("SELECT id FROM runs WHERE source = ?", "trigger:" + id).size() < 3) Thread.sleep(100);
        assertEquals(3, db.query("SELECT id FROM runs WHERE source = ?", "trigger:" + id).size());
    }
}
