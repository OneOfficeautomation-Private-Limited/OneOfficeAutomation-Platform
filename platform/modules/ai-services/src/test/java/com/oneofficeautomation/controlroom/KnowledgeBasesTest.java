package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.ai.KnowledgeBases;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G71: knowledge bases made in the console, filled and fetched by runs of agents in their folder. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeBasesTest {
    Db db; ControlRoom cr; ControlRoom.User admin;
    Map<String, Object> dev;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-kb");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "HR/Answer.flow.json", LicensingAndChecksTest.agent("answer"), "v1", true, null);
        cr.checkin(admin, "Finance/Other.flow.json", LicensingAndChecksTest.agent("other"), "v1", true, null);
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "KB-PC", "kb-pc", "Windows 11", "0.2.0", null);
        dev = cr.device((String) r.get("device_id"), (String) r.get("secret"));
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    String running(String path) {
        String id = (String) cr.startRun("admin", "admin", path, (String) dev.get("id"), Map.of(), "manual", null).get("run_id");
        assertNotNull(cr.claimNext(dev));
        return id;
    }

    @Test void filledByOneRunFetchedByAnotherKeptToItsFolder() {
        Map<String, Object> kb = cr.modules.service(KnowledgeBases.class).save(admin, null, Map.of("name", "HR policies", "folder", "HR", "description", "Leave and travel"), null);
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.modules.service(KnowledgeBases.class).save(admin, null, Map.of("name", "hr POLICIES"), null)).code());
        String run = running("HR/Answer.flow.json");
        assertEquals("KNOWLEDGE.EMPTY", assertThrows(OoaException.class, () -> cr.modules.service(KnowledgeBases.class).forRun(dev, Map.of("run_id", run, "request", Map.of("op", "fetch", "name", "HR policies")))).code());
        String content = Base64.getEncoder().encodeToString("index bytes".getBytes());
        cr.modules.service(KnowledgeBases.class).forRun(dev, Map.of("run_id", run, "request", Map.of("op", "publish", "name", "hr policies", "content", content, "passages", 12, "documents", 3)));
        Map<String, Object> got = cr.modules.service(KnowledgeBases.class).forRun(dev, Map.of("run_id", run, "request", Map.of("op", "fetch", "name", "HR policies", "sha256", "")));
        assertEquals(content, got.get("content"));
        assertEquals(true, cr.modules.service(KnowledgeBases.class).forRun(dev, Map.of("run_id", run, "request", Map.of("op", "fetch", "name", "HR policies", "sha256", got.get("sha256")))).get("not_modified"));
        Map<String, Object> listed = cr.modules.service(KnowledgeBases.class).list(admin).get(0);
        assertEquals(12, ((Number) listed.get("passages")).intValue());
        assertEquals("HR/Answer.flow.json", listed.get("updated_by_agent"));
        String other = running("Finance/Other.flow.json");
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.modules.service(KnowledgeBases.class).forRun(dev, Map.of("run_id", other, "request", Map.of("op", "fetch", "name", "HR policies")))).code());
        cr.modules.service(KnowledgeBases.class).delete(admin, (String) kb.get("id"), null);
        assertEquals(List.of(), cr.modules.service(KnowledgeBases.class).list(admin));
    }
}
