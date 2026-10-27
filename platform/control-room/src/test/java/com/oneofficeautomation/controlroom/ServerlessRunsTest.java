package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** C7: API tasks that run inside the Command Center with no runner, limited to web and data toolkits and their own workspace. */
class ServerlessRunsTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String json) { return (Map<String, Object>) JsonLite.parse(json); }

    @Test @SuppressWarnings("unchecked")
    void serverlessApiTasksRunWithoutARunner() throws Exception {
        Path data = Files.createTempDirectory("ooa-sls");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.checkin(admin, "Api/Greet.flow.json", flow("{\"kind\":\"TaskBot\",\"id\":\"greet\",\"name\":\"Greet\",\"description\":\"t\","
                + "\"variables\":[{\"name\":\"name\",\"type\":\"String\",\"direction\":\"input\"},{\"name\":\"msg\",\"type\":\"String\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"s1\",\"action\":\"core/set\",\"args\":{\"variable\":\"msg\",\"value\":\"Hello ${name}\"}},"
                + "{\"id\":\"s2\",\"action\":\"ooa.string/change_case\",\"args\":{\"text\":\"$msg\",\"case\":\"upper\"},\"out\":{\"result\":\"msg\"}}]}"), "v1", true, null);
            cr.checkin(admin, "Api/Files.flow.json", flow("{\"kind\":\"TaskBot\",\"id\":\"files\",\"name\":\"Files\",\"description\":\"t\",\"variables\":[],"
                + "\"steps\":[{\"id\":\"s1\",\"action\":\"ooa.file/read_text\",\"args\":{\"path\":\"C:/Windows/win.ini\"}}]}"), "v1", true, null);
            Path outside = Files.writeString(data.resolve("secret.json"), "{\"a\":1}");
            cr.checkin(admin, "Api/Escape.flow.json", flow("{\"kind\":\"TaskBot\",\"id\":\"escape\",\"name\":\"Escape\",\"description\":\"t\",\"variables\":[],"
                + "\"steps\":[{\"id\":\"s1\",\"action\":\"ooa.json/read_file\",\"args\":{\"path\":" + JsonLite.write(outside.toString()) + "}}]}"), "v1", true, null);

            // only data and web toolkits: an agent that reads the server's files is refused
            assertEquals("CR.NOT_SERVERLESS", assertThrows(OoaException.class, () -> cr.apiTasks.create(admin,
                Map.of("name", "files", "bot_path", "Api/Files.flow.json", "serverless", true), null)).code());

            Map<String, Object> task = cr.apiTasks.create(admin, Map.of("name", "greet", "bot_path", "Api/Greet.flow.json", "serverless", true, "timeout_seconds", 30), null);
            var done = cr.apiTasks.run((String) task.get("id"), (String) task.get("token"), Map.of("name", "Ann"), null);
            assertTrue(done.getKey(), "the call waited for the result");
            assertEquals("success", done.getValue().get("status"), String.valueOf(done.getValue().get("error")));
            assertEquals("HELLO ANN", ((Map<String, Object>) done.getValue().get("outputs")).get("msg"));
            Map<String, Object> run = db.one("SELECT device_id, source FROM runs WHERE id = ?", done.getValue().get("run_id"));
            assertEquals(ServerlessRuns.DEVICE, run.get("device_id"));
            assertTrue(cr.count("SELECT COUNT(*) AS n FROM run_events WHERE run_id = '" + done.getValue().get("run_id") + "'") > 0, "the step log is kept as for any run");
            assertEquals(Boolean.TRUE, cr.apiTasks.list(admin).get(0).get("serverless"));
            assertFalse(Files.exists(data.resolve("serverless").resolve((String) done.getValue().get("run_id"))), "the workspace is removed afterwards");

            // the virtual runner takes nothing but serverless API tasks
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.startRun("admin", "admin", "Api/Greet.flow.json", ServerlessRuns.DEVICE, Map.of(), "manual", null)).code());

            // files stay inside the run's own workspace
            Map<String, Object> esc = cr.apiTasks.create(admin, Map.of("name", "escape", "bot_path", "Api/Escape.flow.json", "serverless", true, "timeout_seconds", 30), null);
            var r2 = cr.apiTasks.run((String) esc.get("id"), (String) esc.get("token"), Map.of(), null);
            assertEquals("failed", r2.getValue().get("status"));
            assertTrue(String.valueOf(r2.getValue().get("error")).contains("FILE.OUTSIDE_WORKSPACE"), String.valueOf(r2.getValue().get("error")));
        } finally { db.close(); }
    }
}
