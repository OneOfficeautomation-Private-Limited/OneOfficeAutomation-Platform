package com.oneofficeautomation.cli;

import com.oneofficeautomation.agent.Agent;
import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gap G30 end to end: a real runner's worker process changes a shared value and saves a credential with its expiry
 * through the relay (worker, Bot Agent, One CommandCenter's agent routes); the administrator's switch is honoured and
 * the secret never appears in run data or the Audit Trail.
 */
class AgentWritesE2ETest {
    /** The HTTP helpers of the main end-to-end test, pointed at this test's server. */
    final ControlRoomE2ETest h = new ControlRoomE2ETest();

    ControlRoomE2ETest.R call(String m, String p, String t, Object b) throws Exception { return h.call(m, p, t, b); }
    String login(String u, String pw) throws Exception { return h.login(u, pw); }
    static boolean until(java.util.concurrent.Callable<Boolean> c, long ms) throws Exception { return ControlRoomE2ETest.until(c, ms); }


    @SuppressWarnings("unchecked")
    @Test void agentChangesSharedValueAndCredentialThroughTheRelay() throws Exception {
        Path data = Files.createTempDirectory("ooa-cr-g30"), agentHome = Files.createTempDirectory("ooa-agent-g30");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String adminPw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        h.base = "http://127.0.0.1:" + server.port();
        Agent agent = null;
        try {
            String admin = login("admin", adminPw);
            call("POST", "/api/auth/password", admin, Map.of("current", adminPw, "new", "Admin-Passw0rd-2026"));
            assertEquals(200, call("POST", "/api/lockers", admin, Map.of("name", "it", "description", "IT", "consumers", "user:admin")).status());
            assertEquals(200, call("PUT", "/api/lockers/it/app/password", admin, Map.of("value", "Old-Pass-1")).status());
            assertEquals(200, call("PUT", "/api/lockers/it/reset/password", admin, Map.of("value", "Fresh-Pass-2")).status());
            assertEquals(200, call("PUT", "/api/globals/limit", admin, Map.of("value", 5000, "type", "number")).status());
            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"rotate\",\"name\":\"rotate\",\"description\":\"G30\",\"variables\":["
                    + "{\"name\":\"v\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"d\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"e\",\"type\":\"Any\",\"direction\":\"output\"}],\"steps\":["
                    + "{\"id\":\"1\",\"action\":\"ooa.global_values/set\",\"args\":{\"name\":\"limit\",\"value\":7000},\"out\":{\"value\":\"v\"}},"
                    + "{\"id\":\"2\",\"action\":\"ooa.vault/save_credential\",\"args\":{\"credential\":\"it/app/password\",\"value\":{\"$credential\":\"it/reset/password\"},\"expires_in_days\":60},\"out\":{\"days_left\":\"d\"}},"
                    + "{\"id\":\"3\",\"action\":\"ooa.vault/credential_expiry\",\"args\":{\"credential\":\"it/app\"},\"out\":{\"expires\":\"e\"}}]}");
            assertEquals(200, call("POST", "/api/repo/checkin", admin, Map.of("path", "IT/Rotate.flow.json", "flow", flow, "comment", "g30")).status());

            String key = (String) call("POST", "/api/devices/registration-key", admin, Map.of("hours", 1)).body().get("key");
            Path cfg = agentHome.resolve("agent.json");
            String deviceId = (String) Agent.register(h.base, key, "G30-PC", cfg).get("device_id");
            agent = new Agent(cfg, agentHome.resolve("work")).intervals(500, 300);
            Thread t = new Thread(agent::run, "test-agent-g30");
            t.setDaemon(true);
            t.start();
            assertTrue(until(() -> JsonLite.write(call("GET", "/api/devices", admin, null).body()).contains("\"status\":\"online\""), 10000));

            // the switch is off: the shared value changes, the credential is refused with the reason
            String run1 = (String) call("POST", "/api/runs", admin, Map.of("bot_path", "IT/Rotate.flow.json", "device_id", deviceId)).body().get("run_id");
            assertTrue(until(() -> "failed".equals(call("GET", "/api/runs/" + run1, admin, null).body().get("status")), 60000), String.valueOf(call("GET", "/api/runs/" + run1, admin, null).body()));
            assertTrue(String.valueOf(call("GET", "/api/runs/" + run1, admin, null).body().get("error")).contains("VAULT.WRITE_OFF"));
            assertEquals("7000", db.one("SELECT json_value FROM global_values WHERE name = 'limit'").get("json_value"));

            // switched on: saved, with its expiry
            assertEquals(200, call("PUT", "/api/settings", admin, Map.of("agents_write_credentials", 1)).status());
            String run2 = (String) call("POST", "/api/runs", admin, Map.of("bot_path", "IT/Rotate.flow.json", "device_id", deviceId)).body().get("run_id");
            assertTrue(until(() -> "success".equals(call("GET", "/api/runs/" + run2, admin, null).body().get("status")), 60000), String.valueOf(call("GET", "/api/runs/" + run2, admin, null).body()));
            Map<String, Object> run = call("GET", "/api/runs/" + run2, admin, null).body();
            String in60 = LocalDate.now().plusDays(60).toString();
            assertTrue(String.valueOf(run.get("outputs")).contains(in60), String.valueOf(run.get("outputs")));
            assertEquals("Fresh-Pass-2", cr.vault.decrypt((String) db.one("SELECT ciphertext FROM credentials WHERE locker = 'it' AND name = 'app' AND attribute = 'password'").get("ciphertext"), "it/app/password"));
            assertEquals(in60, call("GET", "/api/vault/credential?locker=it&name=app", admin, null).body().get("expires"));
            assertFalse(JsonLite.write(run).contains("Fresh-Pass-2"), "the secret is not in run data");
            String audit = JsonLite.write(call("GET", "/api/audit?limit=1000", admin, null).body());
            assertFalse(audit.contains("Fresh-Pass-2") || audit.contains("Old-Pass-1"), "nor in the Audit Trail");
            assertTrue(audit.contains("agent:G30-PC for admin") && audit.contains(run2));
        } finally {
            if (agent != null) agent.stop();
            server.stop();
            db.close();
        }
    }
}
