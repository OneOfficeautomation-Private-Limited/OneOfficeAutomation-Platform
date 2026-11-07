package com.oneofficeautomation.cli;

import com.oneofficeautomation.agent.Agent;
import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G36 end to end: a real One CommandCenter (with its clock), Bot Agent and worker processes. An agent makes a callback
 * URL and suspends; a POST to the URL starts a new run that carries on from the step after the wait with the values it
 * had, and gets the body; that run waits again for a time and a third run finishes it. Another agent waits for a work
 * item, and once more until its timeout.
 */
class LongRunningE2ETest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    String base;
    Db db;

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Object parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        Map<String, Object> m = new LinkedHashMap<>(parsed instanceof Map ? (Map<String, Object>) parsed : Map.of("list", parsed));
        m.put("_status", r.statusCode());
        return m;
    }

    static boolean until(java.util.concurrent.Callable<Boolean> c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (Boolean.TRUE.equals(c.call())) return true; Thread.sleep(250); }
        return false;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> outputs(Map<String, Object> run) {
        Object o = run.get("outputs");
        return (Map<String, Object>) (o instanceof Map ? o : JsonLite.parse(String.valueOf(o)));
    }

    String status(String admin, String run) throws Exception { return String.valueOf(call("GET", "/api/runs/" + run, admin, null).get("status")); }

    /** Follows a chain of resumed runs to its last run, once that one has ended (success, failed or stopped). */
    String lastOfChain(String admin, String first, long ms) throws Exception {
        String[] at = {first};
        assertTrue(until(() -> {
            Map<String, Object> r = call("GET", "/api/runs/" + at[0], admin, null);
            if (r.get("resumed_as") instanceof String next) { at[0] = next; return false; }
            return Set.of("success", "failed", "stopped").contains(String.valueOf(r.get("status")));
        }, ms), "the chain from " + first + " ended at " + at[0] + ": " + call("GET", "/api/runs/" + at[0], admin, null)
                + " waits: " + db.query("SELECT id, run_id, status, step_id, kind, resume_at, timeout_at, resumed_run_id FROM run_waits")
                + " audit: " + db.query("SELECT action, object_id, details, result FROM audit WHERE action LIKE 'run.%' ORDER BY seq DESC LIMIT 10"));
        return at[0];
    }

    @SuppressWarnings("unchecked")
    @Test void agentsSuspendAndCarryOnInNewRuns() throws Exception {
        Path data = Files.createTempDirectory("ooa-cr-long"), agentHome = Files.createTempDirectory("ooa-agent-long");
        db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        try {
            String admin = (String) call("POST", "/api/auth/login", null, Map.of("username", "admin", "password", pw)).get("token");
            call("POST", "/api/auth/password", admin, Map.of("current", pw, "new", "Admin-Passw0rd-2026"));
            cr.platform.updateSettings(cr.session(admin), Map.of("public_url", base), null);
            String key = (String) call("POST", "/api/devices/registration-key", admin, Map.of("hours", 1)).get("key");
            Path cfg = agentHome.resolve("agent.json");
            String deviceId = (String) Agent.register(base, key, "LONG-PC", cfg).get("device_id");
            Agent agent = new Agent(cfg, agentHome.resolve("work")).intervals(500, 300);
            Thread t = new Thread(agent::run, "test-agent-long");
            t.setDaemon(true);
            t.start();

            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
                {"kind":"TaskBot","id":"long_bot","name":"long_bot","description":"Waits for a callback, then for a time",
                 "variables":[{"name":"a","type":"String","direction":"output"},{"name":"n","type":"Number","direction":"output"},
                              {"name":"url","type":"String","direction":"output"},{"name":"cb","type":"String"},
                              {"name":"res","type":"Dictionary","direction":"output"},{"name":"by","type":"String","direction":"output"},
                              {"name":"after","type":"String","direction":"output","default":"-"},{"name":"fin","type":"String","direction":"output","default":"-"}],
                 "steps":[
                  {"id":"1","action":"core/set","args":{"variable":"a","value":"before"}},
                  {"id":"2","action":"ooa.longrunning/create_callback","out":{"url":"url","callback_id":"cb"}},
                  {"id":"3","action":"core/set","args":{"variable":"n","value":7}},
                  {"id":"4","action":"ooa.longrunning/wait_for_callback","args":{"callback_id":{"expr":"$cb"},"timeout_seconds":600},"out":{"result":"res","resumed_by":"by"}},
                  {"id":"5","action":"core/set","args":{"variable":"after","value":{"expr":"$a + ' and after'"}}},
                  {"id":"6","action":"ooa.longrunning/resume_after","args":{"delay_seconds":1}},
                  {"id":"7","action":"core/set","args":{"variable":"fin","value":"end"}}
                 ]}""");
            call("POST", "/api/repo/checkin", admin, Map.of("path", "Ops/Long.flow.json", "flow", flow));
            String first = (String) call("POST", "/api/runs", admin, Map.of("bot_path", "Ops/Long.flow.json", "device_id", deviceId)).get("run_id");
            assertTrue(until(() -> "suspended".equals(status(admin, first)), 60000), String.valueOf(call("GET", "/api/runs/" + first, admin, null)));
            Map<String, Object> suspended = call("GET", "/api/runs/" + first, admin, null);
            assertEquals("callback", ((Map<String, Object>) suspended.get("wait")).get("kind"), "the run's page says what it waits for");
            assertEquals("-", outputs(suspended).get("after"), "the steps after the wait have not run");
            String url = String.valueOf(outputs(suspended).get("url"));
            assertTrue(url.startsWith(base + "/api/callbacks/"), url);
            List<Map<String, Object>> waits = (List<Map<String, Object>>) call("GET", "/api/run-waits", admin, null).get("waits");
            assertTrue(waits.stream().anyMatch(w -> first.equals(w.get("run_id"))), "Activity lists it as suspended: " + waits);

            assertEquals(404, call("POST", base + "/api/callbacks/" + "x".repeat(43), null, Map.of()).get("_status"));
            assertEquals(202, call("POST", url, null, Map.of("approved", true, "ref", "PO-9")).get("_status"));
            assertEquals(409, call("POST", url, null, Map.of()).get("_status"), "a callback URL works once");

            String last = lastOfChain(admin, first, 90000);
            assertNotEquals(first, last, String.valueOf(call("GET", "/api/runs/" + first, admin, null)));
            Map<String, Object> done = call("GET", "/api/runs/" + last, admin, null);
            assertEquals("success", done.get("status"), String.valueOf(done));
            Map<String, Object> out = outputs(done);
            assertEquals("before", out.get("a"), "values from before the wait came back");
            assertEquals(0, new java.math.BigDecimal("7").compareTo(new java.math.BigDecimal(String.valueOf(out.get("n")))));
            assertEquals(Map.of("approved", true, "ref", "PO-9"), out.get("res"), "the callback's body is the step's result");
            assertEquals("callback", out.get("by"));
            assertEquals("before and after", out.get("after"));
            assertEquals("end", out.get("fin"), "the time wait resumed a third run that finished the agent");
            String middle = (String) call("GET", "/api/runs/" + first, admin, null).get("resumed_as");
            assertNotEquals(last, middle, "callback, then time: three runs");
            assertEquals(middle, call("GET", "/api/runs/" + last, admin, null).get("resumed_from"));

            // a work item, then a timeout
            db.update("INSERT INTO queues (id, name, max_attempts, processing, created) VALUES (?,?,?,?,?)", "q_e2e", "E2E items", 1, false, Instant.now());
            db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated) VALUES ('item_e2e', 'q_e2e', '{}', 'new', 0, 0, ?, ?)", Instant.now(), Instant.now());
            Map<String, Object> waiter = (Map<String, Object>) JsonLite.parse("""
                {"kind":"TaskBot","id":"item_waiter","name":"item_waiter","description":"Waits for a work item",
                 "variables":[{"name":"item","type":"String","direction":"input"},{"name":"res","type":"Dictionary","direction":"output"},
                              {"name":"late","type":"Boolean","direction":"output"}],
                 "steps":[{"id":"w","action":"ooa.longrunning/wait_for_item","args":{"item_id":{"expr":"$item"},"timeout_seconds":3,"on_timeout":"continue"},
                           "out":{"result":"res","timed_out":"late"}}]}""");
            call("POST", "/api/repo/checkin", admin, Map.of("path", "Ops/Waiter.flow.json", "flow", waiter));
            String itemRun = (String) call("POST", "/api/runs", admin, Map.of("bot_path", "Ops/Waiter.flow.json", "device_id", deviceId, "inputs", Map.of("item", "item_e2e"))).get("run_id");
            String lateRun = (String) call("POST", "/api/runs", admin, Map.of("bot_path", "Ops/Waiter.flow.json", "device_id", deviceId, "inputs", Map.of("item", "item_e2e"))).get("run_id");
            assertTrue(until(() -> "suspended".equals(status(admin, itemRun)) && !"running".equals(status(admin, lateRun)), 60000));
            db.update("UPDATE queue_items SET status = 'success', result = '{\"total\":12}' WHERE id = 'item_e2e'");   // ends both waits, unless one already timed out
            Map<String, Object> itemOut = outputs(call("GET", "/api/runs/" + lastOfChain(admin, itemRun, 60000), admin, null));
            Map<String, Object> lateOut = outputs(call("GET", "/api/runs/" + lastOfChain(admin, lateRun, 60000), admin, null));
            // the clock may see the 3-second timeout before the item ends, or the item first: each run says which
            for (Map<String, Object> o : List.of(itemOut, lateOut)) {
                if (Boolean.TRUE.equals(o.get("late"))) assertEquals(Map.of(), o.get("res"));
                else assertEquals("success", ((Map<String, Object>) o.get("res")).get("status"), String.valueOf(o));
            }

            db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated) VALUES ('item_never', 'q_e2e', '{}', 'new', 0, 0, ?, ?)", Instant.now(), Instant.now());
            String timeoutRun = (String) call("POST", "/api/runs", admin, Map.of("bot_path", "Ops/Waiter.flow.json", "device_id", deviceId, "inputs", Map.of("item", "item_never"))).get("run_id");
            Map<String, Object> timedOut = outputs(call("GET", "/api/runs/" + lastOfChain(admin, timeoutRun, 60000), admin, null));
            assertEquals(true, timedOut.get("late"), "the timeout ended the wait: " + timedOut);
            assertTrue(JsonLite.write(call("GET", "/api/audit?q=run.", admin, null)).contains("run.resume"), "resumes are audited");
        } finally {
            server.stop();
            db.close();
        }
    }
}
