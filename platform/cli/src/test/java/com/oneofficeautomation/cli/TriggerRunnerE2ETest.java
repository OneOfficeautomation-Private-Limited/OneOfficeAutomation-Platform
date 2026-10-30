package com.oneofficeautomation.cli;

import com.oneofficeautomation.agent.Agent;
import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runner-side triggers end to end: a real Command Center and a real OneRunner. Folder triggers fire on the runner,
 * the agent runs with the event as input, and the trigger shows its fire count; a missing folder shows as an error.
 */
class TriggerRunnerE2ETest {
    static boolean until(Callable<Boolean> c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (Boolean.TRUE.equals(c.call())) return true; Thread.sleep(200); }
        return false;
    }

    @SuppressWarnings("unchecked")
    @Test void folderTriggersFireOnTheRunner() throws Exception {
        Path data = Files.createTempDirectory("ooa-cr"), home = Files.createTempDirectory("ooa-agent"), watched = Files.createDirectories(home.resolve("drop"));
        Files.writeString(watched.resolve("old.txt"), "x");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"on_folder\",\"name\":\"On folder\",\"description\":\"Echoes the trigger\","
                + "\"variables\":[{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"trigger_file\",\"type\":\"String\",\"direction\":\"input\"},"
                + "{\"name\":\"trigger_event\",\"type\":\"Dictionary\",\"direction\":\"input\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"${trigger_event.kind} ${trigger_file}\"}}]}");
        cr.checkin(admin, "Ops/On folder.flow.json", flow, "first", true, null);
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        String base = "http://127.0.0.1:" + server.port();
        Agent agent = null;
        try {
            Map<String, Object> reg = Agent.register(base, (String) cr.createRegistrationKey(admin, 1, null).get("key"), "TRIGGER-PC", home.resolve("agent.json"));
            String deviceId = (String) reg.get("device_id");
            agent = new Agent(home.resolve("agent.json"), home.resolve("work")).intervals(500, 300);
            Thread t = new Thread(agent::run, "test-agent");
            t.setDaemon(true);
            t.start();
            String created = (String) cr.createTrigger(admin, Map.of("name", "New batch folder", "bot_path", "Ops/On folder.flow.json", "device_id", deviceId, "kind", "folder_created",
                    "folder", watched.toString(), "pattern", "batch-*"), null).get("id");
            String deleted = (String) cr.createTrigger(admin, Map.of("name", "Removed", "bot_path", "Ops/On folder.flow.json", "device_id", deviceId, "kind", "file_deleted",
                    "folder", watched.toString(), "pattern", "*.txt"), null).get("id");
            String missing = (String) cr.createTrigger(admin, Map.of("name", "Nowhere", "bot_path", "Ops/On folder.flow.json", "device_id", deviceId, "kind", "file_changed",
                    "folder", home.resolve("no-such-folder").toString()), null).get("id");
            Thread.sleep(4000);   // the runner picks the triggers up with its heartbeat and takes its first look
            Files.createDirectory(watched.resolve("batch-7"));
            Files.delete(watched.resolve("old.txt"));
            assertTrue(until(() -> db.query("SELECT id FROM runs WHERE status = 'success' AND source IN (?, ?)", "trigger:" + created, "trigger:" + deleted).size() == 2, 40_000),
                    "both triggers ran their agent: " + db.query("SELECT source, status, error FROM runs"));
            String outputs = JsonLite.write(db.query("SELECT outputs FROM runs WHERE source IN (?, ?)", "trigger:" + created, "trigger:" + deleted));
            assertTrue(outputs.contains("folder_created ") && outputs.contains("batch-7"), outputs);
            assertTrue(outputs.contains("file_deleted ") && outputs.contains("old.txt"), outputs);
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> m : cr.triggers(admin)) byId.put((String) m.get("id"), m);
            assertEquals(1, ((Number) byId.get(created).get("fire_count")).intValue());
            assertNotNull(byId.get(deleted).get("last_fired"));
            assertTrue(until(() -> {
                for (Map<String, Object> m : cr.triggers(admin)) if (m.get("id").equals(missing)) return String.valueOf(m.get("last_error")).contains("Folder not found");
                return false;
            }, 20_000), "the runner reports a folder it cannot watch");
        } finally {
            if (agent != null) agent.stop();
            server.stop();
            db.close();
        }
    }
}
