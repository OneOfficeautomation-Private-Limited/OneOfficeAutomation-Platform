package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** One Command Center platform features: API keys, roles, settings, runner pools, work queues, callbacks, calendars, toolkits. */
class PlatformTest {
    static UnifiedServerTest.Res call(String base, String method, String path, Object body, String bearer) throws Exception {
        return UnifiedServerTest.call(base, method, path, body, bearer, null);
    }

    @SuppressWarnings("unchecked")
    @Test void queuesPoolsKeysRolesAndCallbacks() throws Exception {
        Path data = Files.createTempDirectory("ooa-pf-data"), agentHome = Files.createTempDirectory("ooa-pf-agent");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        String base = "http://127.0.0.1:" + server.port();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/done", ex -> { callbacks.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); ex.sendResponseHeaders(204, -1); ex.close(); });
        hook.start();
        com.oneofficeautomation.agent.Agent agent = null;
        try {
            String t0 = (String) call(base, "POST", "/api/auth/login", Map.of("username", "admin", "password", pw), null).json().get("token");
            call(base, "POST", "/api/auth/password", Map.of("current", pw, "new", "Platform-Passw0rd-1"), t0);
            String admin = (String) call(base, "POST", "/api/auth/login", Map.of("username", "admin", "password", "Platform-Passw0rd-1"), null).json().get("token");

            // ---- API keys ----
            Map<String, Object> key = call(base, "POST", "/api/apikeys", Map.of("name", "ci", "days", 30), admin).json();
            String apiKey = (String) key.get("key");
            assertTrue(apiKey.startsWith("ooak_"));
            assertEquals("admin", call(base, "GET", "/api/auth/me", null, apiKey).json().get("username"), "an API key signs in as its owner");
            assertFalse(call(base, "GET", "/api/apikeys", null, admin).body().contains(apiKey), "only the hash is stored");

            // ---- custom role ----
            assertEquals(200, call(base, "PUT", "/api/roles/queue_clerk", Map.of("description", "Adds work", "permissions", List.of("queues.view", "queues.work")), admin).status());
            String tmp = (String) call(base, "POST", "/api/users", Map.of("username", "clerk1", "role", "queue_clerk"), admin).json().get("temporary_password");
            String clerkT = (String) call(base, "POST", "/api/auth/login", Map.of("username", "clerk1", "password", tmp), null).json().get("token");
            call(base, "POST", "/api/auth/password", Map.of("current", tmp, "new", "Clerk-Passw0rd-11"), clerkT);
            String clerk = (String) call(base, "POST", "/api/auth/login", Map.of("username", "clerk1", "password", "Clerk-Passw0rd-11"), null).json().get("token");
            assertEquals(List.of("queues.view", "queues.work"), call(base, "GET", "/api/auth/me", null, clerk).json().get("permissions"));
            assertEquals(403, call(base, "GET", "/api/users", null, clerk).status(), "a custom role grants only its permissions");
            assertEquals(409, call(base, "DELETE", "/api/roles/queue_clerk", null, admin).status(), "roles in use cannot be deleted");

            // ---- settings ----
            assertEquals("Maintenance on Friday", call(base, "PUT", "/api/settings", Map.of("announcement", "Maintenance on Friday"), admin).json().get("announcement"));
            assertEquals(400, call(base, "PUT", "/api/settings", Map.of("no_such", 1), admin).status());

            // ---- agent, runner, pool ----
            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"invoice_worker\",\"name\":\"Invoice worker\","
                    + "\"variables\":[{\"name\":\"work_item\",\"type\":\"Dictionary\",\"direction\":\"input\"},{\"name\":\"total\",\"type\":\"Number\",\"direction\":\"output\"}],"
                    + "\"steps\":[{\"id\":\"s1\",\"action\":\"core/if\",\"args\":{\"condition\":{\"op\":\"eq\",\"left\":\"$work_item.kind\",\"right\":\"bad\"}},"
                    + "\"children\":[{\"id\":\"s2\",\"action\":\"core/throw\",\"args\":{\"code\":\"BUSINESS.BAD_ITEM\",\"message\":\"bad item\"}}],\"else\":[]},"
                    + "{\"id\":\"s3\",\"action\":\"core/set\",\"args\":{\"variable\":\"total\",\"value\":\"$work_item.amount\"}}]}");
            call(base, "POST", "/api/repo/checkin", Map.of("path", "Finance/Invoice worker.flow.json", "flow", flow, "comment", "v1"), admin);
            agent = Ooa.startLocalAgent(cr, base, agentHome.resolve("agent.json"), agentHome.resolve("work"));
            String deviceId = (String) ((Map<String, Object>) ((List<Object>) call(base, "GET", "/api/devices", null, admin).json().get("devices")).get(0)).get("id");
            String pool = (String) call(base, "POST", "/api/pools", Map.of("name", "Finance runners", "devices", List.of(deviceId)), admin).json().get("id");

            // ---- run on a pool, with priority and a callback, started with the API key ----
            String hookUrl = "http://127.0.0.1:" + hook.getAddress().getPort() + "/done";
            Map<String, Object> started = call(base, "POST", "/api/runs", Map.of("bot_path", "Finance/Invoice worker.flow.json", "pool_id", pool, "priority", 5,
                    "callback_url", hookUrl, "inputs", Map.of("work_item", Map.of("amount", 42))), apiKey).json();
            String runId = (String) started.get("run_id");
            assertNotNull(runId, String.valueOf(started));
            assertTrue(ControlRoomE2ETest.until(() -> callbacks.stream().anyMatch(c -> c.contains(runId) && c.contains("\"status\":\"success\"") && c.contains("42")), 60000),
                    "the callback receives the result: " + callbacks);

            // ---- work queue processed by the pool, with a retry for a failing item ----
            String qid = (String) call(base, "POST", "/api/queues", Map.of("name", "Invoices", "bot_path", "Finance/Invoice worker.flow.json", "pool_id", pool,
                    "max_attempts", 2, "sla_minutes", 60), admin).json().get("id");
            Map<String, Object> added = call(base, "POST", "/api/queues/" + qid + "/items", Map.of("items", List.of(
                    Map.of("_reference", "INV-1", "amount", 10), Map.of("_reference", "INV-2", "kind", "bad"), Map.of("_reference", "INV-3", "amount", 30, "_priority", 9))), clerk).json();
            assertEquals(3, ((Number) added.get("added")).intValue(), String.valueOf(added));
            assertEquals(403, call(base, "DELETE", "/api/queues/" + qid, null, clerk).status(), "the clerk can add work but not delete queues");
            assertEquals(200, call(base, "POST", "/api/queues/" + qid + "/start", null, admin).status());
            assertTrue(ControlRoomE2ETest.until(() -> {
                Map<String, Object> counts = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) call(base, "GET", "/api/queues", null, admin).json().get("queues")).get(0)).get("counts");
                return ((Number) counts.get("success")).intValue() == 2 && ((Number) counts.get("failed")).intValue() == 1;
            }, 120000), "two items succeed, the bad one fails after its retry: " + call(base, "GET", "/api/queues", null, admin).body());
            List<Object> failed = (List<Object>) call(base, "GET", "/api/queues/" + qid + "/items?status=failed", null, admin).json().get("items");
            Map<String, Object> bad = (Map<String, Object>) failed.get(0);
            assertEquals("INV-2", bad.get("reference"));
            assertEquals(2, ((Number) bad.get("attempts")).intValue(), "failed items are retried up to max attempts");
            assertTrue(String.valueOf(bad.get("error")).contains("BUSINESS.BAD_ITEM"));
            Map<String, Object> inv3 = (Map<String, Object>) ((List<Object>) call(base, "GET", "/api/queues/" + qid + "/items?q=INV-3", null, admin).json().get("items")).get(0);
            assertTrue(String.valueOf(inv3.get("result")).contains("30"), "the agent's outputs are kept with the item");
            call(base, "POST", "/api/queues/" + qid + "/items/retry", Map.of("ids", List.of(bad.get("id"))), clerk);
            assertEquals("new", ((Map<String, Object>) ((List<Object>) call(base, "GET", "/api/queues/" + qid + "/items?q=INV-2", null, admin).json().get("items")).get(0)).get("status"),
                    "a failed item can be sent back to the queue");
            call(base, "POST", "/api/queues/" + qid + "/stop", null, admin);

            // ---- holiday calendars and schedule ownership ----
            assertEquals(200, call(base, "PUT", "/api/calendars/India%202026", Map.of("dates", List.of("2026-12-25", "2026-01-26")), admin).status());
            assertEquals(400, call(base, "PUT", "/api/calendars/Bad", Map.of("dates", List.of("25/12/2026")), admin).status());
            UnifiedServerTest.Res schRes = call(base, "POST", "/api/schedules", Map.of("name", "Nightly", "bot_path", "Finance/Invoice worker.flow.json", "pool_id", pool,
                    "kind", "daily", "at_time", "02:00", "time_zone", "UTC", "holiday_calendar", "India 2026"), admin);
            String sid = (String) schRes.json().get("id");
            assertNotNull(sid, schRes.body());
            assertEquals(200, call(base, "PUT", "/api/schedules/" + sid, Map.of("name", "Nightly invoices"), admin).status());
            assertTrue(call(base, "GET", "/api/schedules", null, admin).body().contains("Nightly invoices"));

            // ---- toolkits ----
            Map<String, Object> core = (Map<String, Object>) ((List<Object>) call(base, "GET", "/api/toolkits", null, admin).json().get("toolkits")).stream()
                    .filter(t -> "core".equals(((Map<String, Object>) t).get("id"))).findFirst().orElseThrow();
            assertEquals(1, ((Number) core.get("used_by")).intValue(), "the toolkit list shows how many agents use each toolkit");
            assertEquals(400, call(base, "PATCH", "/api/toolkits/core", Map.of("enabled", false), admin).status(), "core cannot be switched off");
            Map<String, Object> f2 = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"text_agent\",\"name\":\"Text\",\"variables\":[{\"name\":\"r\",\"type\":\"String\"}],"
                    + "\"steps\":[{\"id\":\"s1\",\"action\":\"ooa.string/change_case\",\"args\":{\"text\":\"a\",\"case\":\"upper\"},\"out\":{\"result\":\"r\"}}]}");
            call(base, "POST", "/api/repo/checkin", Map.of("path", "Text.flow.json", "flow", f2, "comment", "v1"), admin);
            call(base, "PATCH", "/api/toolkits/ooa.string", Map.of("enabled", false), admin);
            UnifiedServerTest.Res refused = call(base, "POST", "/api/runs", Map.of("bot_path", "Text.flow.json", "device_id", deviceId), admin);
            assertEquals("CR.TOOLKIT_DISABLED", ((Map<String, Object>) refused.json().get("error")).get("code"), "agents using a switched-off toolkit do not run");
            assertEquals(List.of("Text.flow.json"), call(base, "GET", "/api/toolkits/ooa.string/used-by", null, admin).json().get("agents"));

            // ---- revoked keys stop working ----
            call(base, "DELETE", "/api/apikeys/" + key.get("id"), null, admin);
            assertEquals(401, call(base, "GET", "/api/auth/me", null, apiKey).status());
        } finally {
            if (agent != null) agent.stop();
            hook.stop(0);
            server.stop();
            db.close();
        }
    }
}
