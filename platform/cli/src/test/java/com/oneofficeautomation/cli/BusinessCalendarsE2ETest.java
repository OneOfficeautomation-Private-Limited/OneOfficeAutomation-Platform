package com.oneofficeautomation.cli;

import com.oneofficeautomation.agent.Agent;
import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G34 end to end: a holiday calendar with a Friday-Saturday weekend is saved through the API, and an agent run by a real
 * Bot Agent reads it from One CommandCenter (worker, Bot Agent, run-request route) for its working-day steps. A calendar
 * that does not exist fails the run with a clear error.
 */
class BusinessCalendarsE2ETest {

    @SuppressWarnings("unchecked")
    @Test void workingDayStepsReadTheCalendarThroughTheRunner() throws Exception {
        EnvironmentLocksE2ETest h = new EnvironmentLocksE2ETest();
        Path data = Files.createTempDirectory("ooa-cr-cal"), agentHome = Files.createTempDirectory("ooa-agent-cal");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        h.base = "http://127.0.0.1:" + server.port();
        try {
            String admin = (String) h.call("POST", "/api/auth/login", null, Map.of("username", "admin", "password", pw)).get("token");
            h.call("POST", "/api/auth/password", admin, Map.of("current", pw, "new", "Admin-Passw0rd-2026"));
            h.call("PUT", "/api/calendars/Gulf%202026", admin, Map.of("dates", List.of("2026-12-02"), "weekend", List.of("FRI", "SAT")));
            List<Map<String, Object>> cals = (List<Map<String, Object>>) h.call("GET", "/api/calendars", admin, null).get("calendars");
            assertEquals(List.of("FRI", "SAT"), cals.get(0).get("weekend"));
            h.call("PUT", "/api/calendars/Gulf%202026", admin, Map.of("dates", List.of("2026-12-02", "2026-12-03")));
            cals = (List<Map<String, Object>>) h.call("GET", "/api/calendars", admin, null).get("calendars");
            assertEquals(List.of("FRI", "SAT"), cals.get(0).get("weekend"), "a save without a weekend keeps it");

            String key = (String) h.call("POST", "/api/devices/registration-key", admin, Map.of("hours", 1)).get("key");
            Path cfg = agentHome.resolve("agent.json");
            String deviceId = (String) Agent.register(h.base, key, "CAL-PC", cfg).get("device_id");
            Agent agent = new Agent(cfg, agentHome.resolve("work")).intervals(500, 300);
            Thread t = new Thread(agent::run, "test-agent-cal");
            t.setDaemon(true);
            t.start();

            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
                {"kind":"TaskBot","id":"due_bot","name":"due_bot","description":"Working days from a calendar",
                 "variables":[{"name":"start","type":"DateTime","default":"2026-12-01T10:00:00"},
                              {"name":"due","type":"DateTime","direction":"output"},{"name":"n","type":"Number","direction":"output"},
                              {"name":"sunday","type":"Boolean","direction":"output"}],
                 "steps":[
                  {"id":"1","action":"ooa.datetime/add_working_days","args":{"value":"$start","days":1,"calendar":"Gulf 2026"},"out":{"result":"due"}},
                  {"id":"2","action":"ooa.datetime/working_days_between","args":{"start":"$start","end":"$due","calendar":"Gulf 2026"},"out":{"result":"n"}},
                  {"id":"3","action":"ooa.datetime/is_working_day","args":{"value":"2026-12-06T00:00:00","calendar":"Gulf 2026"},"out":{"result":"sunday"}}
                 ]}""");
            h.call("POST", "/api/repo/checkin", admin, Map.of("path", "Ops/Due.flow.json", "flow", flow));
            String runId = (String) h.call("POST", "/api/runs", admin, Map.of("bot_path", "Ops/Due.flow.json", "device_id", deviceId)).get("run_id");
            assertTrue(EnvironmentLocksE2ETest.until(() -> "success".equals(h.call("GET", "/api/runs/" + runId, admin, null).get("status")), 60000),
                    String.valueOf(h.call("GET", "/api/runs/" + runId, admin, null)));
            Object o = h.call("GET", "/api/runs/" + runId, admin, null).get("outputs");
            Map<String, Object> outs = (Map<String, Object>) (o instanceof Map ? o : JsonLite.parse(String.valueOf(o)));
            // Tuesday 1 Dec + 1: 2 and 3 Dec are holidays, Friday and Saturday the weekend, so Sunday 6 Dec
            assertTrue(String.valueOf(outs.get("due")).startsWith("2026-12-06T10:00"), String.valueOf(outs));
            assertEquals(1, ((Number) outs.get("n")).intValue());
            assertEquals(true, outs.get("sunday"));
            long reads = ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'agent.calendar'").get("n")).longValue();
            assertEquals(1, reads, "read once for the run's three steps");

            Map<String, Object> bad = (Map<String, Object>) JsonLite.parse("""
                {"kind":"TaskBot","id":"bad_cal","name":"bad_cal","description":"Unknown calendar","variables":[],
                 "steps":[{"id":"1","action":"ooa.datetime/next_working_day","args":{"value":"2026-12-01T00:00:00","calendar":"Nowhere"}}]}""");
            h.call("POST", "/api/repo/checkin", admin, Map.of("path", "Ops/Bad.flow.json", "flow", bad));
            String badRun = (String) h.call("POST", "/api/runs", admin, Map.of("bot_path", "Ops/Bad.flow.json", "device_id", deviceId)).get("run_id");
            assertTrue(EnvironmentLocksE2ETest.until(() -> "failed".equals(h.call("GET", "/api/runs/" + badRun, admin, null).get("status")), 60000));
            assertTrue(JsonLite.write(h.call("GET", "/api/runs/" + badRun, admin, null)).contains("DATETIME.CALENDAR_NOT_FOUND"),
                    String.valueOf(h.call("GET", "/api/runs/" + badRun, admin, null)));
        } finally {
            server.stop();
            db.close();
        }
    }
}
