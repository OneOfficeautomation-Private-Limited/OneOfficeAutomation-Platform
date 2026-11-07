package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.orchestration.Workflows;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G48: a workflow's task carries the workflow's stages (decisions left out) and its own stage, for the stage bar. */
class WorkflowStageBarTest {
    @SuppressWarnings("unchecked")
    @Test void tasksCarryTheirWorkflowsStages() throws Exception {
        Path data = Files.createTempDirectory("ooa-g48");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.checkin(admin, "P/Buy.process.json", (Map<String, Object>) JsonLite.parse("{\"name\":\"Buy\",\"stages\":["
                    + "{\"id\":\"check\",\"type\":\"decision\",\"name\":\"Big?\",\"condition\":\"true\"},"
                    + "{\"id\":\"approve\",\"type\":\"approval\",\"name\":\"Approve\",\"assignee\":\"user:admin\"},"
                    + "{\"id\":\"tell\",\"type\":\"notify\",\"name\":\"Tell the buyer\",\"to\":\"user:admin\",\"message\":\"ok\"}]}"), "v1", true, null);
            cr.modules.service(Workflows.class).start(admin, "P/Buy.process.json", Map.of("order", "PO-9"), null);
            Map<String, Object> t = cr.tasks.tasks(admin, "open", false).stream().filter(x -> "approval".equals(x.get("kind"))).findFirst().orElseThrow();
            assertEquals(List.of(Map.of("id", "approve", "name", "Approve"), Map.of("id", "tell", "name", "Tell the buyer")), t.get("stages"));
            assertEquals("approve", t.get("stage"));
        } finally { db.close(); }
    }
}
