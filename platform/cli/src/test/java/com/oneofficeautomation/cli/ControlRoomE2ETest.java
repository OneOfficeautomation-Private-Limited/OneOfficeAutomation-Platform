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
 * Control Room + Bot Agent end to end: real HTTP server, real database (H2), real agent with worker processes.
 * Covers accounts and roles, repository check-in/check-out/versions, devices, runs with vault credentials, stop,
 * schedules, webhook and file triggers, and the tamper-evident audit log.
 */
class ControlRoomE2ETest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    String base;

    record R(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, Object> err() { return (Map<String, Object>) body.getOrDefault("error", Map.of()); }
    }

    @SuppressWarnings("unchecked")
    R call(String method, String path, String token, Object body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Object parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        return new R(r.statusCode(), parsed instanceof Map ? (Map<String, Object>) parsed : Map.of("list", parsed));
    }
    R call(String m, String p, String t, Object b) throws Exception { return call(m, p, t, b, null); }

    String login(String user, String pw) throws Exception {
        R r = call("POST", "/api/auth/login", null, Map.of("username", user, "password", pw));
        assertEquals(200, r.status(), String.valueOf(r.body()));
        return (String) r.body().get("token");
    }

    static Map<String, Object> bot(String id, String extraSteps, String extraVars) {
        @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"Test bot " + id + "\","
                + "\"variables\":[{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"}" + extraVars + "],\"steps\":[" + extraSteps + "]}");
        return f;
    }

    static boolean until(java.util.concurrent.Callable<Boolean> c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (Boolean.TRUE.equals(c.call())) return true; Thread.sleep(200); }
        return false;
    }

    @SuppressWarnings("unchecked")
    @Test void controlRoomAndAgentEndToEnd() throws Exception {
        Path data = Files.createTempDirectory("ooa-cr"), agentHome = Files.createTempDirectory("ooa-agent");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String adminPw = cr.bootstrap();
        assertNotNull(adminPw);
        assertNull(cr.bootstrap(), "bootstrap runs only on an empty database");
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        Thread agentThread = null;
        try {
            // ---------- accounts, password policy, must-change, CSRF, lockout ----------
            String admin = login("admin", adminPw);
            assertEquals("CR.PASSWORD_CHANGE_REQUIRED", call("GET", "/api/dashboard", admin, null).err().get("code"));
            assertEquals("CR.WEAK_PASSWORD", call("POST", "/api/auth/password", admin, Map.of("current", adminPw, "new", "short")).err().get("code"));
            assertEquals(200, call("POST", "/api/auth/password", admin, Map.of("current", adminPw, "new", "Admin-Passw0rd-2026")).status());
            assertEquals(200, call("GET", "/api/dashboard", admin, null).status());
            R cookie = call("POST", "/api/users", null, Map.of("username", "x", "role", "viewer"), Map.of("Cookie", "ooa_cr=" + admin));
            assertEquals("CR.CSRF", cookie.err().get("code"), "cookie sessions must send X-OOA-Request on changes");
            Map<String, Object> dev1 = call("POST", "/api/users", admin, Map.of("username", "dev.one", "display_name", "Dev One", "role", "bot_creator")).body();
            Map<String, Object> dev2 = call("POST", "/api/users", admin, Map.of("username", "dev.two", "role", "bot_creator")).body();
            Map<String, Object> view = call("POST", "/api/users", admin, Map.of("username", "viewer.one", "role", "viewer")).body();
            String d1 = login("dev.one", (String) dev1.get("temporary_password"));
            call("POST", "/api/auth/password", d1, Map.of("current", dev1.get("temporary_password"), "new", "DevOne-Passw0rd-1"));
            String d2 = login("dev.two", (String) dev2.get("temporary_password"));
            call("POST", "/api/auth/password", d2, Map.of("current", dev2.get("temporary_password"), "new", "DevTwo-Passw0rd-2"));
            String v1 = login("viewer.one", (String) view.get("temporary_password"));
            call("POST", "/api/auth/password", v1, Map.of("current", view.get("temporary_password"), "new", "Viewer-Passw0rd-3"));
            for (int i = 0; i < 5; i++) call("POST", "/api/auth/login", null, Map.of("username", "viewer.one", "password", "wrong"));
            assertEquals("CR.LOCKED_OUT", call("POST", "/api/auth/login", null, Map.of("username", "viewer.one", "password", "Viewer-Passw0rd-3")).err().get("code"));

            // ---------- repository ----------
            Map<String, Object> invalid = bot("broken", "{\"id\":\"1\",\"action\":\"core/log\",\"args\":{\"message\":\"$undeclared\"}}", "");
            R bad = call("POST", "/api/repo/checkin", d1, Map.of("path", "Finance/Broken.flow.json", "flow", invalid, "comment", "x"));
            assertEquals("CR.INVALID_BOT", bad.err().get("code"), "invalid bots never reach the repository");
            assertEquals("CR.FORBIDDEN", call("POST", "/api/repo/checkin", v1, Map.of("path", "A.flow.json", "flow", bot("a", "", ""))).err().get("code"));
            Map<String, Object> pay = bot("pay_vendor", "{\"id\":\"1\",\"action\":\"ooa.crypto/encrypt_text\",\"args\":{\"text\":\"invoice\",\"passphrase\":\"$api_key\"},\"out\":{\"result\":\"enc\"}},"
                    + "{\"id\":\"2\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"paid ${vendor} signed ${len($enc) > 20} on ${sys.machine} limit ${global.limit}\"}}",
                    ",{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"input\",\"required\":true},{\"name\":\"api_key\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true},{\"name\":\"enc\",\"type\":\"String\"}");
            assertEquals(200, call("POST", "/api/repo/checkin", d1, Map.of("path", "Finance/Pay vendor.flow.json", "flow", pay, "comment", "first")).status());
            assertEquals("CR.NOT_CHECKED_OUT", call("POST", "/api/repo/checkin", d1, Map.of("path", "Finance/Pay vendor.flow.json", "flow", pay)).err().get("code"));
            assertEquals(200, call("POST", "/api/repo/checkout", d1, Map.of("path", "Finance/Pay vendor.flow.json")).status());
            R locked = call("POST", "/api/repo/checkout", d2, Map.of("path", "Finance/Pay vendor.flow.json"));
            assertEquals(409, locked.status());
            assertTrue(String.valueOf(locked.err().get("message")).contains("dev.one"), "the lock names who has the bot");
            assertEquals("CR.DUPLICATE_ID", call("POST", "/api/repo/checkin", d2, Map.of("path", "Other/Pay.flow.json", "flow", pay)).err().get("code"));
            pay.put("description", "Pays a vendor (v2)");
            assertEquals(2, ((Number) call("POST", "/api/repo/checkin", d1, Map.of("path", "Finance/Pay vendor.flow.json", "flow", pay, "comment", "second")).body().get("version")).intValue());
            List<Object> versions = (List<Object>) call("GET", "/api/repo/versions?path=Finance/Pay%20vendor.flow.json", d2, null).body().get("versions");
            assertEquals(2, versions.size());
            assertEquals("CR.FORBIDDEN", call("POST", "/api/repo/production", d1, Map.of("path", "Finance/Pay vendor.flow.json", "version", 1)).err().get("code"), "only admins pick production versions");
            assertEquals(200, call("POST", "/api/repo/production", admin, Map.of("path", "Finance/Pay vendor.flow.json", "version", 2)).status());

            // ---------- vault and global values ----------
            assertEquals(200, call("POST", "/api/lockers", d1, Map.of("name", "finance", "description", "Finance APIs", "consumers", "user:dev.one")).status());
            assertEquals(200, call("PUT", "/api/lockers/finance/vendor_api/key", d1, Map.of("value", "sk-12345")).status());
            assertEquals("CR.FORBIDDEN", call("PUT", "/api/lockers/finance/vendor_api/key", d2, Map.of("value", "stolen")).err().get("code"), "only owners change credentials");
            assertFalse(JsonLite.write(call("GET", "/api/lockers", d1, null).body()).contains("sk-12345"), "values are never shown back");
            assertEquals(200, call("PUT", "/api/globals/limit", admin, Map.of("value", 5000)).status());

            // ---------- device registration and agent ----------
            assertEquals("CR.FORBIDDEN", call("POST", "/api/devices/registration-key", d1, Map.of("hours", 1)).err().get("code"));
            String key = (String) call("POST", "/api/devices/registration-key", admin, Map.of("hours", 1)).body().get("key");
            Path cfg = agentHome.resolve("agent.json");
            Map<String, Object> reg = Agent.register(base, key, "TEST-PC", cfg);
            String deviceId = (String) reg.get("device_id");
            assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> Agent.register(base, key, "AGAIN", agentHome.resolve("other.json")), "registration keys work once");
            Agent agent = new Agent(cfg, agentHome.resolve("work")).intervals(500, 300);
            agentThread = new Thread(agent::run, "test-agent");
            agentThread.setDaemon(true);
            agentThread.start();
            assertTrue(until(() -> JsonLite.write(call("GET", "/api/devices", admin, null).body()).contains("\"status\":\"online\""), 10000), "device comes online");

            // ---------- run with a vault credential ----------
            R raw = call("POST", "/api/runs", d1, Map.of("bot_path", "Finance/Pay vendor.flow.json", "device_id", deviceId, "inputs", Map.of("vendor", "Acme", "api_key", "sk-plain")));
            assertEquals("CR.BAD_INPUT", raw.err().get("code"), "plain secrets are refused as run inputs");
            String runId = (String) call("POST", "/api/runs", d1, Map.of("bot_path", "Finance/Pay vendor.flow.json", "device_id", deviceId,
                    "inputs", Map.of("vendor", "Acme", "api_key", Map.of("$credential", "finance/vendor_api/key")))).body().get("run_id");
            assertTrue(until(() -> "success".equals(call("GET", "/api/runs/" + runId, d1, null).body().get("status")), 30000),
                    "run succeeds: " + call("GET", "/api/runs/" + runId, d1, null).body());
            Map<String, Object> run = call("GET", "/api/runs/" + runId, d1, null).body();
            assertTrue(String.valueOf(run.get("outputs")).contains("paid Acme signed true") && String.valueOf(run.get("outputs")).contains("limit 5000"), String.valueOf(run.get("outputs")));
            assertFalse(JsonLite.write(run).contains("sk-12345"), "the secret never appears in run data");
            assertTrue(((List<Object>) run.get("events")).size() >= 3, "live events were stored");

            // a requester who is not a consumer of the locker is refused at run time
            String deniedRun = (String) call("POST", "/api/runs", d2, Map.of("bot_path", "Finance/Pay vendor.flow.json", "device_id", deviceId,
                    "inputs", Map.of("vendor", "Acme", "api_key", Map.of("$credential", "finance/vendor_api/key")))).body().get("run_id");
            assertTrue(until(() -> "failed".equals(call("GET", "/api/runs/" + deniedRun, d2, null).body().get("status")), 30000));
            assertTrue(String.valueOf(call("GET", "/api/runs/" + deniedRun, d2, null).body().get("error")).contains("VAULT.DENIED"));

            // ---------- stop a long run ----------
            Map<String, Object> slow = bot("slow_bot", "{\"id\":\"1\",\"action\":\"core/delay\",\"args\":{\"seconds\":30}}", "");
            call("POST", "/api/repo/checkin", d1, Map.of("path", "Ops/Slow.flow.json", "flow", slow));
            String slowRun = (String) call("POST", "/api/runs", d1, Map.of("bot_path", "Ops/Slow.flow.json", "device_id", deviceId)).body().get("run_id");
            assertTrue(until(() -> "running".equals(call("GET", "/api/runs/" + slowRun, d1, null).body().get("status")), 15000));
            assertEquals(200, call("POST", "/api/runs/" + slowRun + "/stop", d1, null).status());
            assertTrue(until(() -> "stopped".equals(call("GET", "/api/runs/" + slowRun, d1, null).body().get("status")), 20000), "stop reaches the worker");

            // ---------- schedule, webhook and file triggers ----------
            Map<String, Object> echo = bot("echo_bot", "{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"got ${trigger_file}${payload}\"}}",
                    ",{\"name\":\"trigger_file\",\"type\":\"String\",\"direction\":\"input\"},{\"name\":\"payload\",\"type\":\"Any\",\"direction\":\"input\"}");
            call("POST", "/api/repo/checkin", d1, Map.of("path", "Ops/Echo.flow.json", "flow", echo));
            String sid = (String) call("POST", "/api/schedules", d1, Map.of("name", "Every minute", "bot_path", "Ops/Echo.flow.json", "device_id", deviceId, "kind", "every", "every_minutes", 1)).body().get("id");
            assertEquals("CR.BAD_SCHEDULE", call("POST", "/api/schedules", d1, Map.of("name", "x", "bot_path", "Ops/Echo.flow.json", "device_id", deviceId, "kind", "daily", "at_time", "25:00")).err().get("code"));
            db.update("UPDATE schedules SET next_run = ? WHERE id = ?", java.time.Instant.now().minusSeconds(5), sid);
            assertEquals(1, cr.tickSchedules());
            assertTrue(until(() -> JsonLite.write(call("GET", "/api/runs?filter=history", d1, null).body()).contains("schedule:" + sid), 30000));
            Map<String, Object> hook = call("POST", "/api/triggers", d1, Map.of("name", "Hook", "bot_path", "Ops/Echo.flow.json", "device_id", deviceId, "kind", "webhook")).body();
            assertEquals(403, call("POST", "/api/hooks/" + hook.get("id"), null, Map.of("x", 1), Map.of("X-OOA-Secret", "wrong")).status());
            assertEquals(202, call("POST", "/api/hooks/" + hook.get("id"), null, Map.of("invoice", "INV-9"), Map.of("X-OOA-Secret", (String) hook.get("secret"))).status());
            Path inbox = Files.createDirectories(agentHome.resolve("inbox"));
            call("POST", "/api/triggers", d1, Map.of("name", "Files", "bot_path", "Ops/Echo.flow.json", "device_id", deviceId, "kind", "file", "folder", inbox.toString(), "pattern", "*.csv"));
            Thread.sleep(1500);
            Files.writeString(inbox.resolve("batch-7.csv"), "a,b");
            assertTrue(until(() -> JsonLite.write(call("GET", "/api/runs?filter=history&limit=50", d1, null).body()).contains("file:"), 30000), "file trigger fires on the agent's device");
            assertTrue(until(() -> {
                for (Object o : (List<Object>) call("GET", "/api/runs?filter=history&limit=50", d1, null).body().get("runs")) {
                    Map<String, Object> rr = (Map<String, Object>) o;
                    if (String.valueOf(rr.get("source")).startsWith("file:") && "success".equals(rr.get("status")))
                        return String.valueOf(call("GET", "/api/runs/" + rr.get("id"), d1, null).body().get("outputs")).contains("batch-7.csv");
                }
                return false;
            }, 30000));

            // ---------- audit ----------
            Map<String, Object> verify = call("GET", "/api/audit/verify", admin, null).body();
            assertEquals(true, verify.get("valid"), String.valueOf(verify));
            assertTrue(((Number) verify.get("records")).intValue() > 30);
            assertTrue(JsonLite.write(call("GET", "/api/audit?q=credential.read", admin, null).body()).contains("credential.read"), "every credential use is audited");
            assertEquals("CR.FORBIDDEN", call("GET", "/api/audit", d1, null).err().get("code"));
            db.update("UPDATE audit SET actor = 'mallory' WHERE seq = 3");
            assertEquals(false, call("GET", "/api/audit/verify", admin, null).body().get("valid"), "editing history breaks the chain");
            assertEquals("nosniff", HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/health")).build(), HttpResponse.BodyHandlers.ofString()).headers().firstValue("X-Content-Type-Options").orElse(""));
            agent.stop();
        } finally {
            server.stop();
            db.close();
        }
    }
}
