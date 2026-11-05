package com.oneofficeautomation.cli;

import com.oneofficeautomation.agent.Agent;
import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G33 end to end: a real One CommandCenter, Bot Agent and worker process. The agent's lock steps travel from the worker
 * through the Bot Agent to One CommandCenter, the console lists the lock while the run holds it, and it is given back
 * when the run ends.
 */
class EnvironmentLocksE2ETest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    String base;

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Object parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of("list", parsed);
    }

    static boolean until(java.util.concurrent.Callable<Boolean> c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (Boolean.TRUE.equals(c.call())) return true; Thread.sleep(200); }
        return false;
    }

    @SuppressWarnings("unchecked")
    @Test void lockStepsReachOneCommandCenterThroughTheRunner() throws Exception {
        Path data = Files.createTempDirectory("ooa-cr-locks"), agentHome = Files.createTempDirectory("ooa-agent-locks");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        try {
            String admin = (String) call("POST", "/api/auth/login", null, Map.of("username", "admin", "password", pw)).get("token");
            call("POST", "/api/auth/password", admin, Map.of("current", pw, "new", "Admin-Passw0rd-2026"));
            String key = (String) call("POST", "/api/devices/registration-key", admin, Map.of("hours", 1)).get("key");
            Path cfg = agentHome.resolve("agent.json");
            String deviceId = (String) Agent.register(base, key, "LOCK-PC", cfg).get("device_id");
            Agent agent = new Agent(cfg, agentHome.resolve("work")).intervals(500, 300);
            Thread t = new Thread(agent::run, "test-agent-locks");
            t.setDaemon(true);
            t.start();

            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
                {"kind":"TaskBot","id":"lock_bot","name":"lock_bot","description":"Takes a lock",
                 "variables":[{"name":"got","type":"Boolean","direction":"output"},{"name":"mine","type":"Boolean","direction":"output"},
                              {"name":"again","type":"Boolean","direction":"output"}],
                 "steps":[
                  {"id":"1","action":"ooa.locks/acquire","args":{"name":"SAP login","comment":"e2e"},"out":{"acquired":"got"}},
                  {"id":"2","action":"ooa.locks/check","args":{"name":"sap login"},"out":{"mine":"mine"}},
                  {"id":"3","action":"core/delay","args":{"seconds":4}},
                  {"id":"4","action":"ooa.locks/acquire","args":{"name":"Never given back"}},
                  {"id":"5","action":"ooa.locks/release","args":{"name":"SAP login"},"out":{"released":"again"}}
                 ]}""");
            call("POST", "/api/repo/checkin", admin, Map.of("path", "Ops/Lock.flow.json", "flow", flow));
            String runId = (String) call("POST", "/api/runs", admin, Map.of("bot_path", "Ops/Lock.flow.json", "device_id", deviceId)).get("run_id");
            assertNotNull(runId);
            // while the run holds it, the console lists the lock with its run and runner
            assertTrue(until(() -> JsonLite.write(call("GET", "/api/environment-locks", admin, null)).contains("\"run_id\":\"" + runId + "\""), 60000),
                    "the lock is listed: " + call("GET", "/api/environment-locks", admin, null) + " run " + call("GET", "/api/runs/" + runId, admin, null));
            Map<String, Object> lock = ((List<Map<String, Object>>) call("GET", "/api/environment-locks", admin, null).get("locks")).get(0);
            assertEquals("SAP login", lock.get("name"));
            assertEquals("LOCK-PC", lock.get("device_name"));
            assertEquals("e2e", lock.get("comment"));
            assertTrue(until(() -> "success".equals(call("GET", "/api/runs/" + runId, admin, null).get("status")), 60000), String.valueOf(call("GET", "/api/runs/" + runId, admin, null)));
            Object o = call("GET", "/api/runs/" + runId, admin, null).get("outputs");
            Map<String, Object> outs = (Map<String, Object>) (o instanceof Map ? o : JsonLite.parse(String.valueOf(o)));
            assertEquals(true, outs.get("got"));
            assertEquals(true, outs.get("mine"));
            assertEquals(true, outs.get("again"));
            // the lock the agent never gave back went with the run
            assertTrue(until(() -> ((List<Object>) call("GET", "/api/environment-locks", admin, null).get("locks")).isEmpty(), 10000));
            assertTrue(JsonLite.write(call("GET", "/api/audit?q=lock.", admin, null)).contains("lock.acquire"), "lock use is audited");
        } finally {
            server.stop();
            db.close();
        }
    }
}
