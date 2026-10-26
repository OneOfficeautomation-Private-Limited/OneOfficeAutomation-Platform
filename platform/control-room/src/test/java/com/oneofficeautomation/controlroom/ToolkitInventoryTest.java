package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runners report their toolkits; a run needing one the runner lacks fails before it starts, saying which. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolkitInventoryTest {
    Db db; ControlRoom cr; ControlRoom.User admin; String device;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-toolkits");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "R1", "r1", "Windows 11", "0.2.0", null).get("device_id");
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"xl\",\"name\":\"xl\",\"description\":\"t\","
            + "\"variables\":[],\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"x\",\"value\":\"1\"}},"
            + "{\"id\":\"2\",\"action\":\"ooa.string/extract_text\",\"args\":{\"text\":\"a[b]\",\"after\":\"[\",\"before\":\"]\"},\"out\":{\"result\":\"x\"}}]}");
        flow.put("variables", List.of(Map.of("name", "x", "type", "String")));
        cr.checkin(admin, "T/Excel.flow.json", flow, "v1", true, null);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    Map<String, Object> dev() { return db.one("SELECT * FROM devices WHERE id = ?", device); }

    @Test void runsNeedingAMissingToolkitFailAtOnce() {
        assertEquals(true, cr.heartbeat(dev(), Map.of()).get("send_toolkits"), "a runner that never reported is asked for its toolkits");
        // an older runner that reports nothing is not checked: the run is handed out as before
        String r0 = (String) cr.startRun("admin", "admin", "T/Excel.flow.json", device, Map.of(), "manual", null).get("run_id");
        assertNotNull(cr.claimNext(dev()));
        db.update("UPDATE runs SET status = 'succeeded' WHERE id = ?", r0);

        assertNull(cr.heartbeat(dev(), Map.of("toolkits", List.of("core@1.0.0", "ooa.files@1.0.0", "bad entry"))).get("send_toolkits"));
        assertEquals("core@1.0.0,ooa.files@1.0.0", dev().get("toolkits"), "kept, sorted, junk left out");

        String r1 = (String) cr.startRun("admin", "admin", "T/Excel.flow.json", device, Map.of(), "manual", null).get("run_id");
        assertNull(cr.claimNext(dev()), "nothing is handed out");
        Map<String, Object> run = db.one("SELECT status, error FROM runs WHERE id = ?", r1);
        assertEquals("failed", run.get("status"));
        assertTrue(String.valueOf(run.get("error")).contains("RUN.TOOLKIT_MISSING") && String.valueOf(run.get("error")).contains("ooa.string"), String.valueOf(run.get("error")));

        cr.heartbeat(dev(), Map.of("toolkits", List.of("core@1.0.0", "ooa.string@2.1.0")));
        cr.startRun("admin", "admin", "T/Excel.flow.json", device, Map.of(), "manual", null);
        assertNotNull(cr.claimNext(dev()), "with the toolkit the run goes out");
    }

    @Test void theMissingToolkitsOfAgentsCalledAreFoundToo() {
        Map<String, Object> main = Map.of("steps", List.of(Map.of("id", "1", "action", "core/run_bot")));
        Map<String, Object> bundle = Map.of("child", Map.of("steps", List.of(Map.of("id", "1", "action", "ooa.sap/logon"))));
        assertEquals(Set.of("ooa.sap"), ControlRoom.missingToolkits("core@1", main, bundle));
        assertEquals(Set.of(), ControlRoom.missingToolkits(null, main, bundle));
    }
}
