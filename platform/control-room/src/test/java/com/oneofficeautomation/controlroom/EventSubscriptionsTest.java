package com.oneofficeautomation.controlroom;

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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** G38: event subscriptions (signed delivery, retries, disabled, filters, Splunk, run logs, test event, address rules), /metrics, permissions, the upgrade to 40. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventSubscriptionsTest {
    Db db; ControlRoom cr; ControlRoom.User admin, creator;
    Map<String, Object> dev;
    HttpServer receiver;
    String base;
    /** What the receiver got: path, headers (lower-case names), body. */
    record Got(String path, Map<String, String> headers, String body) {
        @SuppressWarnings("unchecked") Map<String, Object> json() { return (Map<String, Object>) JsonLite.parse(body); }
        String type() { return headers.get("x-ooa-event"); }
    }
    final List<Got> got = new CopyOnWriteArrayList<>();
    /** Per path: the answers to give in turn (the last one repeats); 200 when none. */
    final Map<String, List<Integer>> answers = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-events");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Ev/Hello.flow.json", LicensingAndChecksTest.agent("hello"), "v1", true, null);
        cr.checkin(admin, "Other/Hello.flow.json", LicensingAndChecksTest.agent("other"), "v1", true, null);
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "Ev-PC", "ev-pc", "Windows 11", "0.2.0", null);
        dev = cr.device((String) r.get("device_id"), (String) r.get("secret"));
        cr.createUser(admin, "cara", "Cara Creator", "bot_creator", null);
        creator = new ControlRoom.User("u-cara", "cara", "bot_creator", "Cara", false, false, false);
        cr.events.backoffMs = new long[] { 100, 200, 300 };
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = calls.computeIfAbsent(path, k -> new AtomicInteger()).getAndIncrement();
            List<Integer> a = answers.getOrDefault(path, List.of(200));
            int code = a.get(Math.min(n, a.size() - 1));
            if (code / 100 == 2) got.add(new Got(path, h, body));
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        receiver.start();
        base = "http://127.0.0.1:" + receiver.getAddress().getPort();
        cr.platform.updateSettings(admin, Map.of("events_allow_http", 1, "events_allow_private", 1), null);
    }

    @AfterAll void stop() { receiver.stop(0); cr.triggerPollers.stop(); db.close(); }

    @BeforeEach void clean() {
        for (Map<String, Object> s : db.query("SELECT id FROM event_subscriptions")) cr.events.delete(admin, (String) s.get("id"), null);
        got.clear(); answers.clear(); calls.clear();
    }

    Map<String, Object> sub(String path, Object events, Map<String, Object> more) {
        Map<String, Object> b = new HashMap<>(Map.of("name", "Sub " + path, "url", base + path, "events", events));
        b.putAll(more);
        return cr.events.save(admin, null, b, null);
    }

    List<Got> waitFor(Predicate<Got> p, int count) throws InterruptedException {
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until) {
            List<Got> m = got.stream().filter(p).toList();
            if (m.size() >= count) return m;
            Thread.sleep(25);
        }
        return got.stream().filter(p).toList();
    }

    String runToEnd(String path, String status) {
        String id = (String) cr.startRun("admin", "admin", path, (String) dev.get("id"), Map.of(), "manual", null).get("run_id");
        assertNotNull(cr.claimNext(dev));
        cr.finish(dev, id, Map.of("status", status));
        return id;
    }

    @Test void runEventsArriveSignedWithAnIdAndTheAgent() throws Exception {
        Map<String, Object> s = sub("/runs", List.of("run.*"), Map.of());
        String secret = (String) s.get("secret");
        assertNotNull(secret, "a json subscription gets a secret, shown once");
        assertTrue(secret.startsWith("whsec_"));
        String run = runToEnd("Ev/Hello.flow.json", "failed");
        List<Got> g = waitFor(x -> run.equals(String.valueOf(((Map<?, ?>) x.json().get("data")).get("run_id"))), 4);
        Set<String> types = new TreeSet<>();
        for (Got x : g) {
            types.add(x.type());
            String ts = x.headers().get("x-ooa-timestamp");
            assertEquals("sha256=" + EventSubscriptions.sign(secret, ts, x.body()), x.headers().get("x-ooa-signature"), "signed with the secret");
            assertEquals(x.json().get("id"), x.headers().get("x-ooa-event-id"));
            assertEquals(x.type(), x.json().get("type"));
            assertEquals(cr.cluster.nodeId, x.json().get("node"));
            assertEquals("Ev/Hello.flow.json", ((Map<?, ?>) x.json().get("data")).get("bot_path"));
        }
        assertEquals(new TreeSet<>(List.of("run.queued", "run.started", "run.finished", "run.failed")), types);
        assertEquals(4, g.stream().map(x -> x.json().get("id")).distinct().count(), "every event has its own id");
        // the list never shows the secret, and the audit entry does not hold it
        Map<String, Object> listed = list().get(0);
        assertNull(listed.get("secret"));
        assertEquals(true, listed.get("has_secret"));
        String audit = String.valueOf(db.one("SELECT details FROM audit WHERE action = 'events.subscription_create' ORDER BY seq DESC LIMIT 1").get("details"));
        assertFalse(audit.contains(secret));
        assertTrue(String.valueOf(db.one("SELECT secret FROM event_subscriptions").get("secret")).startsWith("v1:"), "kept encrypted with the vault's key");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list() { return (List<Map<String, Object>>) cr.events.list(admin).get("subscriptions"); }

    @Test void aFailingReceiverIsTriedAgainThenCounted() throws Exception {
        answers.put("/flaky", List.of(500, 503, 200));
        String id = (String) sub("/flaky", List.of("run.finished"), Map.of()).get("id");
        runToEnd("Ev/Hello.flow.json", "success");
        assertEquals(1, waitFor(x -> x.path().equals("/flaky"), 1).size(), "delivered at the third attempt");
        assertEquals(3, calls.get("/flaky").get());
        Map<String, Object> r = db.one("SELECT last_status, failures FROM event_subscriptions WHERE id = ?", id);
        assertEquals("200", r.get("last_status"));
        assertEquals(0, ((Number) r.get("failures")).intValue());

        answers.put("/down", List.of(500));
        String down = (String) sub("/down", List.of("run.finished"), Map.of()).get("id");
        runToEnd("Ev/Hello.flow.json", "success");
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until && ((Number) db.one("SELECT failures FROM event_subscriptions WHERE id = ?", down).get("failures")).intValue() == 0) Thread.sleep(25);
        Map<String, Object> d = db.one("SELECT last_status, last_error, failures FROM event_subscriptions WHERE id = ?", down);
        assertEquals(1, ((Number) d.get("failures")).intValue());
        assertEquals("500", d.get("last_status"));
        assertTrue(String.valueOf(d.get("last_error")).contains("HTTP 500"));
        assertEquals(4, calls.get("/down").get(), "the first attempt and three more");

        answers.put("/gone", List.of(410));
        String gone = (String) sub("/gone", List.of("run.finished"), Map.of()).get("id");
        runToEnd("Ev/Hello.flow.json", "success");
        until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && ((Number) db.one("SELECT failures FROM event_subscriptions WHERE id = ?", gone).get("failures")).intValue() == 0) Thread.sleep(25);
        Thread.sleep(500);
        assertEquals(1, calls.get("/gone").get(), "an answer like 410 is not tried again");
    }

    @Test void aDisabledSubscriptionGetsNothingAndFiltersChooseAgents() throws Exception {
        String off = (String) sub("/off", List.of("run.finished"), Map.of()).get("id");
        cr.events.setEnabled(admin, off, false, null);
        sub("/ev-only", List.of("run.finished", "audit.entry"), Map.of("filters", List.of("Ev/")));
        runToEnd("Other/Hello.flow.json", "success");
        String mine = runToEnd("Ev/Hello.flow.json", "success");
        List<Got> g = waitFor(x -> x.path().equals("/ev-only") && "run.finished".equals(x.type()), 1);
        assertEquals(1, g.size());
        assertEquals(mine, ((Map<?, ?>) g.get(0).json().get("data")).get("run_id"), "only the Ev/ folder's run");
        // an audit entry that names no agent passes the filter
        cr.platform.updateSettings(admin, Map.of("announcement", "hello"), null);
        assertFalse(waitFor(x -> x.path().equals("/ev-only") && "audit.entry".equals(x.type()) && "settings.update".equals(((Map<?, ?>) x.json().get("data")).get("action")), 1).isEmpty());
        Thread.sleep(300);
        assertNull(calls.get("/off"), "nothing went to the disabled subscription");
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> sub("/x", List.of("run.nothing"), Map.of())).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> sub("/x", List.of(), Map.of())).code());
    }

    @Test void splunkFormatRunLogsAndQueueAndRunnerEvents() throws Exception {
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> sub("/hec", List.of("run.log"), Map.of("format", "splunk"))).code(), "needs the collector token");
        Map<String, Object> s = sub("/hec", List.of("run.log"), Map.of("format", "splunk", "secret", "11111111-2222-3333-4444-555555555555"));
        assertNull(s.get("secret"), "a given secret is not echoed");
        sub("/all", List.of("queue.item_*", "runner.*"), Map.of());
        String id = (String) cr.startRun("admin", "admin", "Ev/Hello.flow.json", (String) dev.get("id"), Map.of(), "manual", null).get("run_id");
        assertNotNull(cr.claimNext(dev));
        cr.appendEvents(dev, id, List.of(Map.of("seq", 1, "type", "log", "message", "first"), Map.of("seq", 2, "type", "log", "message", "second")));
        cr.finish(dev, id, Map.of("status", "success"));
        Got hec = waitFor(x -> x.path().equals("/hec"), 1).get(0);
        assertEquals("Splunk 11111111-2222-3333-4444-555555555555", hec.headers().get("authorization"));
        assertNull(hec.headers().get("x-ooa-signature"));
        assertTrue(hec.body().contains("\"sourcetype\":\"ooa:event\""));
        assertTrue(hec.body().contains("\"message\":\"first\"") && hec.body().contains("\"message\":\"second\""));
        assertEquals(2, hec.body().split("\\{\"time\":").length - 1, "one collector event per log line");

        // work items
        String q = (String) cr.platform.saveQueue(admin, null, Map.of("name", "EvQueue", "bot_path", "Ev/Hello.flow.json"), null).get("id");
        cr.platform.addItems(admin, q, List.of(Map.of("_reference", "R-1", "n", 1)), null);
        Got added = waitFor(x -> x.path().equals("/all") && "queue.item_added".equals(x.type()), 1).get(0);
        Map<?, ?> d = (Map<?, ?>) added.json().get("data");
        assertEquals("EvQueue", d.get("queue")); assertEquals("R-1", d.get("reference")); assertEquals("Ev/Hello.flow.json", d.get("bot_path"));
        assertNull(d.get("data"), "an item's data is not sent");

        // a new runner's first report
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "Ev-PC2", "ev-pc2", "Windows 11", "0.2.0", null);
        Map<String, Object> d2 = cr.device((String) r.get("device_id"), (String) r.get("secret"));
        db.update("UPDATE devices SET last_seen = NULL WHERE id = ?", d2.get("id"));
        cr.heartbeat(cr.device((String) r.get("device_id"), (String) r.get("secret")), Map.of("agent_version", "0.2.0"));
        assertFalse(waitFor(x -> x.path().equals("/all") && "runner.online".equals(x.type()) && "Ev-PC2".equals(((Map<?, ?>) x.json().get("data")).get("name")), 1).isEmpty());
        // ... and the leader's clock sees it go
        cr.events.checkRunners();
        db.update("UPDATE devices SET last_seen = ? WHERE id = ?", ControlRoom.now().minusSeconds(600), d2.get("id"));
        cr.events.checkRunners();
        assertFalse(waitFor(x -> x.path().equals("/all") && "runner.offline".equals(x.type()) && "Ev-PC2".equals(((Map<?, ?>) x.json().get("data")).get("name")), 1).isEmpty());
    }

    @Test void theTestButtonSaysWhatTheReceiverAnswered() {
        String id = (String) sub("/ping", List.of("alert.raised"), Map.of()).get("id");
        Map<String, Object> ok = cr.events.test(admin, id, null);
        assertEquals(true, ok.get("ok")); assertEquals(200, ok.get("status"));
        assertEquals("test", got.get(got.size() - 1).type());
        answers.put("/nope", List.of(404));
        String bad = (String) sub("/nope", List.of("alert.raised"), Map.of()).get("id");
        Map<String, Object> no = cr.events.test(admin, bad, null);
        assertEquals(false, no.get("ok")); assertEquals(404, no.get("status"));
        assertEquals(1, ((Number) db.one("SELECT failures FROM event_subscriptions WHERE id = ?", bad).get("failures")).intValue());
        assertEquals("failed", db.one("SELECT result FROM audit WHERE action = 'events.test' ORDER BY seq DESC LIMIT 1").get("result"));
    }

    @Test void addressRulesKeepEventsOnPublicHttpsAddresses() {
        cr.platform.updateSettings(admin, Map.of("events_allow_http", 0, "events_allow_private", 0), null);
        try {
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.events.checkAddress("http://example.com/hook")).code());
            for (String u : List.of("https://127.0.0.1/x", "https://localhost/x", "https://10.1.2.3/x", "https://192.168.0.5/x", "https://169.254.169.254/latest", "https://[::1]/x", "https://metadata.google.internal/x"))
                assertEquals("EVENTS.ADDRESS_BLOCKED", assertThrows(OoaException.class, () -> cr.events.checkAddress(u), u).code(), u);
            cr.events.checkAddress("https://93.184.215.14/hook");   // a public address
            assertEquals("EVENTS.ADDRESS_BLOCKED", assertThrows(OoaException.class, () -> sub("/x", List.of("run.finished"), Map.of("url", "https://127.0.0.1/x"))).code(), "checked when saved");
            cr.platform.updateSettings(admin, Map.of("events_allow_http", 1), null);
            assertEquals("EVENTS.ADDRESS_BLOCKED", assertThrows(OoaException.class, () -> cr.events.checkAddress("http://127.0.0.1/x")).code(), "http alone does not open private addresses");
        } finally {
            cr.platform.updateSettings(admin, Map.of("events_allow_http", 1, "events_allow_private", 1), null);
        }
    }

    @Test void onlyThoseWithThePermissionManageSubscriptions() {
        assertFalse(Rbac.allowed("bot_creator", "events.manage"));
        assertTrue(Rbac.ADMIN_ONLY.contains("events.manage") && Rbac.ADMIN_ONLY.contains("metrics.view"));
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.events.list(creator)).code());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.events.save(creator, null, Map.of("name", "x", "url", base + "/x", "events", "run.*"), null)).code());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.metrics.makeToken(creator, null)).code());
        String id = (String) sub("/p", "run.finished", Map.of()).get("id");
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.events.test(creator, id, null)).code());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.events.delete(creator, id, null)).code());
        cr.events.save(admin, id, Map.of("name", "Renamed", "rotate_secret", true), null);
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'events.subscription_update' AND object_id = ?", id));
    }

    @Test void metricsAreInThePrometheusFormatAndNeedTheTokenOrASignIn() throws Exception {
        runToEnd("Ev/Hello.flow.json", "success");
        String text = cr.metrics.build();
        assertTrue(text.contains("# TYPE ooa_runs gauge"), text);
        assertTrue(text.contains("# TYPE ooa_run_duration_seconds histogram"));
        assertTrue(text.contains("ooa_run_duration_seconds_bucket{node=\"" + cr.cluster.nodeId + "\",le=\"+Inf\"}"));
        assertTrue(text.matches("(?s).*ooa_runs_finished_total\\{node=\"[^\"]+\",status=\"success\"} [1-9].*"), text);
        assertTrue(text.contains("ooa_runners{node=\"" + cr.cluster.nodeId + "\",state=\"online\"}"));
        assertTrue(text.contains("ooa_license_seats_used{"));
        assertTrue(text.contains("ooa_jvm_memory_used_bytes{"));
        assertTrue(text.contains("ooa_up{node=\"" + cr.cluster.nodeId + "\",part=\"database\"} 1"));
        for (String line : text.split("\n"))
            assertTrue(line.startsWith("# HELP ") || line.startsWith("# TYPE ") || line.matches("[a-z_]+\\{node=\"[^\"]*\"(,[a-z_]+=\"[^\"]*\")*} -?[0-9.eE+-]+"), "a valid line: " + line);

        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            URI url = URI.create("http://127.0.0.1:" + server.port() + "/metrics");
            assertEquals(401, http.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not without a token or a sign-in");
            assertEquals(401, http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer ooam_wrong").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String token = (String) cr.metrics.makeToken(admin, null).get("token");
            assertFalse(String.valueOf(db.one("SELECT json_value FROM settings WHERE name = 'metrics_token_hash'").get("json_value")).contains(token), "kept as a hash");
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"));
            assertTrue(ok.body().contains("ooa_runs{"));
            // a signed-in administrator may read them too; a bot creator may not
            String adminToken = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
            assertEquals(200, http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + adminToken).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            cr.metrics.deleteToken(admin, null);
            Thread.sleep(5100);   // the hash is read again after 5 seconds
            assertEquals(401, http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "a removed token stops working");
        } finally { server.stop(); }
    }

    @Test void aDatabaseAt39GetsTheSubscriptionsTable() throws Exception {
        try (Db old = SchemaUpgradeTest.fresh()) {
            SchemaUpgradeTest.upTo(old, 39);
            assertFalse(SchemaUpgradeTest.has(old, "SELECT 1 FROM event_subscriptions WHERE 1 = 0"));
            old.migrate();
            assertEquals(Db.LATEST, old.schemaVersion());
            assertTrue(SchemaUpgradeTest.has(old, "SELECT id, name, url, events, format, secret, enabled, filters, last_status, last_error, last_delivery, failures, created, created_by FROM event_subscriptions WHERE 1 = 0"));
            assertTrue(Db.ddl("CREATE TABLE event_subscriptions (id VARCHAR(40) PRIMARY KEY, name VARCHAR(120) NOT NULL)", true).contains("NVARCHAR(120)"));
        }
    }
}
