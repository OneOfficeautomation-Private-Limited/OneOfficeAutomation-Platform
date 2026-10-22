package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token exchange, refresh and verify; get-one and delete routes, list filters; schedules and triggers with run-as
 * users, bulk schedule changes; code analysis and its policy at check-in; workflow start forms and references; API
 * tasks over HTTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TokensSchedulingAnalysisApiTasksTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Path data; Db db; ControlRoom cr; ControlRoomServer server; String base;
    ControlRoom.User admin, jane;
    String device, deviceSecret;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    record R(int status, Map<String, Object> body) {}
    @SuppressWarnings("unchecked")
    R http(String method, String path, Object body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        headers.forEach(b::header);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Object parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        return new R(r.statusCode(), parsed instanceof Map ? (Map<String, Object>) parsed : Map.of("list", parsed));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String s) { return (Map<String, Object>) JsonLite.parse(s); }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-tsaa");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
        cr.checkin(admin, "Ops/Echo.flow.json", json("{\"kind\":\"TaskBot\",\"id\":\"echo\",\"name\":\"Echo\",\"description\":\"Echoes\",\"variables\":["
                + "{\"name\":\"text\",\"type\":\"String\",\"direction\":\"input\"},{\"name\":\"answer\",\"type\":\"String\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"answer\",\"value\":\"$text\"}}]}"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        device = (String) d.get("device_id"); deviceSecret = (String) d.get("secret");
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() { server.stop(); cr.triggerPollers.stop(); db.close(); }

    @Test @Order(1)
    @SuppressWarnings("unchecked")
    void tokens() throws Exception {
        ControlRoom.User stored = ControlRoom.user(db.one("SELECT * FROM users WHERE username = 'jane'"));   // the real user id
        String key = (String) cr.platform.createApiKey(stored, "ci", 1, null).get("key");
        R t = http("POST", "/api/auth/token", Map.of("api_key", key), Map.of());
        assertEquals(200, t.status());
        String token = (String) t.body().get("token");
        assertNotNull(t.body().get("expires"));
        R v = http("GET", "/api/auth/token", null, Map.of("Authorization", "Bearer " + token));
        assertEquals(true, v.body().get("valid"));
        assertEquals("jane", v.body().get("username"));
        R r = http("POST", "/api/auth/refresh", null, Map.of("Authorization", "Bearer " + token));
        String fresh = (String) r.body().get("token");
        assertNotEquals(token, fresh);
        assertEquals(false, http("GET", "/api/auth/token", null, Map.of("Authorization", "Bearer " + token)).body().get("valid"));
        assertEquals(true, http("GET", "/api/auth/token", null, Map.of("Authorization", "Bearer " + fresh)).body().get("valid"));
        assertEquals(401, http("POST", "/api/auth/token", Map.of("api_key", "ooak_wrong"), Map.of()).status());
        assertEquals("api_key", http("GET", "/api/auth/token", null, Map.of("Authorization", "Bearer " + key)).body().get("kind"));
    }

    @Test @Order(2)
    @SuppressWarnings("unchecked")
    void uniformApiAndScheduling() {
        cr.setDefaultDevice(admin, "jane", device, null);
        // a schedule that runs as jane on her default runner, without a runner of its own
        Map<String, Object> sch = cr.createSchedule(admin, new HashMap<>(Map.of("name", "nightly", "bot_path", "Ops/Echo.flow.json", "kind", "daily", "at_time", "02:00", "run_as", "jane")), null);
        String sid = (String) sch.get("id");
        assertEquals("jane", cr.schedule(admin, sid).get("run_as"));
        Map<String, Object> run = cr.runScheduleNow(admin, sid, null);
        assertEquals("jane", run.get("run_as"));
        assertEquals(device, run.get("device_id"));
        Map<String, Object> sch2 = cr.createSchedule(admin, new HashMap<>(Map.of("name", "hourly", "bot_path", "Ops/Echo.flow.json", "kind", "every", "every_minutes", 60, "device_id", device)), null);
        assertEquals(2, cr.bulkSchedules(admin, Map.of("ids", List.of(sid, sch2.get("id")), "action", "disable"), null).get("changed"));
        assertTrue(cr.schedules(admin).stream().noneMatch(x -> Boolean.TRUE.equals(x.get("enabled"))));
        cr.bulkSchedules(admin, Map.of("ids", List.of(sid, sch2.get("id")), "action", "owner", "owner", "jane"), null);
        assertEquals("jane", cr.schedule(admin, (String) sch2.get("id")).get("created_by"));
        assertEquals("CR.NOT_FOUND", code(() -> cr.createSchedule(admin, new HashMap<>(Map.of("name", "x", "bot_path", "Ops/Echo.flow.json", "kind", "daily", "at_time", "03:00", "run_as", "nobody")), null)));
        // a trigger whose runs run as jane
        Map<String, Object> trg = cr.createTrigger(admin, new HashMap<>(Map.of("name", "hook", "bot_path", "Ops/Echo.flow.json", "device_id", device, "kind", "webhook")), null);
        cr.updateTrigger(admin, (String) trg.get("id"), Map.of("run_as", "jane", "name", "Order hook"), null);
        assertEquals("Order hook", cr.trigger(admin, (String) trg.get("id")).get("name"));
        Map<String, Object> fired = cr.webhook((String) trg.get("id"), (String) trg.get("secret"), Map.of("x", 1), null);
        assertEquals("jane", fired.get("run_as"));
        // get one, filters
        assertEquals(List.of(), cr.userDetail(admin, "jane").get("teams"));
        assertEquals(device, cr.userDetail(admin, "jane").get("default_device"));
        assertEquals(1, ((List<Object>) cr.usersPage(admin, Map.of("role", "bot_creator")).get("users")).size());
        assertEquals(0, ((Number) cr.devicesPage(admin, Map.of("status", "disabled")).get("total")).intValue());
        assertEquals("PC-1", cr.deviceDetail(admin, device).get("name"));
        // a user who owns schedules and a trigger is not deleted; once they are handed over, jane goes with every rule naming her
        assertEquals("CR.IN_USE", code(() -> cr.deleteUser(admin, "jane", null)));
        cr.bulkSchedules(admin, Map.of("ids", List.of(sid, sch2.get("id")), "action", "owner", "owner", "admin"), null);
        cr.updateSchedule(admin, (String) sch2.get("id"), Map.of(), null);
        cr.deleteSchedule(admin, sid, null);
        cr.updateTrigger(admin, (String) trg.get("id"), Map.of("run_as", ""), null);
        cr.folders.setPermissions(admin, "Ops", List.of(Map.of("principal", "user:jane", "access", "view"), Map.of("principal", "user:admin", "access", "manage")), null);
        cr.createLocker(admin, "k", "keys", "user:jane", null);
        cr.deleteUser(admin, "jane", null);
        assertEquals(0L, cr.count("SELECT COUNT(*) AS n FROM folder_permissions WHERE principal = 'user:jane'"));
        assertEquals("", db.one("SELECT consumers FROM lockers WHERE name = 'k'").get("consumers"));
        assertEquals("CR.NOT_FOUND", code(() -> cr.userDetail(admin, "jane")));
        cr.folders.setPermissions(admin, "Ops", List.of(), null);
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
    }

    @Test @Order(3)
    @SuppressWarnings("unchecked")
    void codeAnalysis() {
        Map<String, Object> bad = json("{\"kind\":\"TaskBot\",\"id\":\"login\",\"name\":\"Login\",\"variables\":[{\"name\":\"Unused Var\",\"type\":\"String\"}],\"steps\":["
                + "{\"id\":\"1\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"https://x.test\",\"method\":\"POST\",\"headers\":{\"api_key\":\"abc123\"}}},"
                + "{\"id\":\"2\",\"action\":\"core/delay\",\"args\":{\"seconds\":3600}},"
                + "{\"id\":\"3\",\"action\":\"core/comment\",\"disabled\":true,\"args\":{\"text\":\"old\"}}]}");
        Map<String, Object> rep = cr.codeAnalysis.analyzeFlow(admin, bad);
        List<String> rules = ((List<Map<String, Object>>) rep.get("violations")).stream().map(v -> String.valueOf(v.get("rule"))).toList();
        assertTrue(rules.containsAll(List.of("hardcoded_secrets", "require_description", "unused_variables", "disabled_steps", "long_waits")), rules.toString());
        assertEquals(false, rep.get("passed"));
        // with the policy enforced, a check-in with errors is refused; switching the rule off lets it in
        cr.codeAnalysis.savePolicy(admin, Map.of("enforce_at_checkin", true), null);
        assertEquals("CR.POLICY_VIOLATION", code(() -> cr.checkin(admin, "Ops/Login.flow.json", bad, "v1", true, null)));
        cr.codeAnalysis.savePolicy(admin, Map.of("enforce_at_checkin", true, "rules", Map.of("hardcoded_secrets", Map.of("severity", "warning"))), null);
        cr.checkin(admin, "Ops/Login.flow.json", bad, "v1", true, null);
        Map<String, Object> stored = cr.codeAnalysis.stored(admin, "Ops/Login.flow.json", "latest");
        assertEquals(1, stored.get("version"));
        assertEquals(0, stored.get("errors"));
        assertTrue(((Number) stored.get("warnings")).intValue() >= 5);
        // forbidden actions by toolkit prefix
        cr.codeAnalysis.savePolicy(admin, Map.of("rules", Map.of("forbidden_actions", Map.of("enabled", true, "params", Map.of("actions", List.of("ooa.rest/"))))), null);
        assertTrue(((List<Map<String, Object>>) cr.codeAnalysis.analyze(admin, "Ops/Login.flow.json", "latest").get("violations")).stream().anyMatch(v -> "forbidden_actions".equals(v.get("rule"))));
        String csv = cr.codeAnalysis.repositoryCsv(admin);
        assertTrue(csv.startsWith("agent,version,rule,severity,step,message\r\n"));
        assertTrue(csv.contains("Ops/Login.flow.json,1,forbidden_actions,error,1,"), csv);
        assertEquals("CR.BAD_INPUT", code(() -> cr.codeAnalysis.savePolicy(admin, Map.of("rules", Map.of("nope", Map.of())), null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.codeAnalysis.savePolicy(jane, Map.of(), null)));
        cr.codeAnalysis.savePolicy(admin, Map.of(), null);
    }

    @Test @Order(4)
    @SuppressWarnings("unchecked")
    void workflowStartFormAndReference() {
        cr.checkin(admin, "Proc/Request.form.json", json("{\"title\":\"Request\",\"fields\":[{\"id\":\"amount\",\"label\":\"Amount\",\"type\":\"number\",\"required\":true,\"min\":1}]}"), "v1", true, null);
        cr.checkin(admin, "Proc/Purchase.process.json", json("{\"name\":\"Purchase\",\"start_form\":\"Proc/Request.form.json\",\"stages\":["
                + "{\"id\":\"n\",\"type\":\"notify\",\"name\":\"Tell\",\"to\":\"user:admin\",\"message\":\"New request\"}]}"), "v1", true, null);
        assertEquals("Proc/Request.form.json", cr.workflows.startForm(admin, "Proc/Purchase.process.json").get("form_path"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.workflows.start(admin, "Proc/Purchase.process.json", Map.of(), "PO-1", null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.workflows.start(admin, "Proc/Purchase.process.json", Map.of("amount", 0), "PO-1", null)));
        Map<String, Object> started = cr.workflows.start(admin, "Proc/Purchase.process.json", Map.of("amount", 250), "PO-1", null);
        Map<String, Object> byRef = cr.workflows.run(admin, null, "PO-1");
        assertEquals(started.get("id"), byRef.get("id"));
        assertEquals(1, ((List<Object>) byRef.get("tasks")).size());
        assertEquals(started.get("id"), cr.workflows.run(admin, (String) started.get("id"), null).get("id"));
    }

    @Test @Order(5)
    @SuppressWarnings("unchecked")
    void apiTasks() throws Exception {
        db.update("UPDATE runs SET status = 'stopped' WHERE status IN ('queued', 'running')");
        Map<String, Object> t = cr.apiTasks.create(admin, Map.of("name", "Echo service", "bot_path", "Ops/Echo.flow.json", "device_id", device, "timeout_seconds", 10), null);
        String id = (String) t.get("id"), token = (String) t.get("token");
        // a runner that answers: claims the run and finishes it with the agent's output
        Thread runner = new Thread(() -> {
            Map<String, Object> dev = cr.device(device, deviceSecret);
            for (int i = 0; i < 100; i++) {
                Map<String, Object> job = cr.claimNext(dev);
                if (job != null) { cr.finish(dev, (String) job.get("run_id"), Map.of("status", "success", "outputs", Map.of("answer", ((Map<String, Object>) job.get("inputs")).get("text")))); return; }
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
        });
        runner.start();
        R r = http("POST", "/api/apitasks/" + id + "/run", Map.of("inputs", Map.of("text", "hello")), Map.of("X-OOA-Task-Token", token));
        runner.join(15000);
        assertEquals(200, r.status(), String.valueOf(r.body()));
        assertEquals("success", r.body().get("status"));
        assertEquals("hello", ((Map<String, Object>) r.body().get("outputs")).get("answer"));
        assertEquals(200, http("GET", "/api/apitasks/" + id + "/runs/" + r.body().get("run_id"), null, Map.of("Authorization", "Bearer " + token)).status());
        // nobody answers within the limit: 202 with the run id
        Map<String, Object> slow = cr.apiTasks.create(admin, Map.of("name", "Slow", "bot_path", "Ops/Echo.flow.json", "device_id", device, "timeout_seconds", 1), null);
        R w = http("POST", "/api/apitasks/" + slow.get("id") + "/run", Map.of("inputs", Map.of("text", "x")), Map.of("X-OOA-Task-Token", (String) slow.get("token")));
        assertEquals(202, w.status());
        assertEquals("queued", w.body().get("status"));
        // wrong token, a token of another task, a new token, switched off
        assertEquals(401, http("POST", "/api/apitasks/" + id + "/run", Map.of(), Map.of("X-OOA-Task-Token", "ooat_wrong")).status());
        assertEquals(401, http("POST", "/api/apitasks/" + id + "/run", Map.of(), Map.of("X-OOA-Task-Token", (String) slow.get("token"))).status());
        String token2 = (String) cr.apiTasks.newToken(admin, id, null).get("token");
        assertEquals(401, http("GET", "/api/apitasks/" + id + "/runs/" + r.body().get("run_id"), null, Map.of("X-OOA-Task-Token", token)).status());
        cr.apiTasks.setEnabled(admin, id, false, null);
        assertEquals(403, http("POST", "/api/apitasks/" + id + "/run", Map.of(), Map.of("X-OOA-Task-Token", token2)).status());
        assertEquals(2, cr.apiTasks.list(admin).size());
        assertEquals("CR.FORBIDDEN", code(() -> cr.apiTasks.list(RepositoryLifecycleTest.person(cr, admin, "val", "viewer"))));
    }
}
