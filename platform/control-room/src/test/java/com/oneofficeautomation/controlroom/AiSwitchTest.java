package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Item 4n: AI on unless an administrator switches it off; only metadata logged unless full logging is turned on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiSwitchTest {
    Db db; ControlRoom cr; ControlRoom.User admin; Path data;
    Map<String, Object> dev;

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-ai4n");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "AI/Ask.flow.json", LicensingAndChecksTest.agent("ask"), "v1", true, null);
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "AI-PC", "ai-pc", "Windows 11", "0.2.0", null);
        dev = cr.device((String) r.get("device_id"), (String) r.get("secret"));
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> job() {
        cr.startRun("admin", "admin", "AI/Ask.flow.json", (String) dev.get("id"), Map.of(), "manual", null);
        Map<String, Object> j = cr.claimNext(dev);
        assertNotNull(j);
        return j;
    }

    @Test @SuppressWarnings("unchecked")
    void theSwitchAndFullLoggingTravelWithTheJob() {
        assertEquals(1, cr.platform.intSetting("ai_enabled"));
        assertEquals(0, cr.platform.intSetting("ai_log_content"));
        assertNull(job().get("guardrails"), "nothing to say by default");

        cr.platform.updateSettings(admin, Map.of("ai_enabled", 0, "ai_log_content", 1), null);
        Map<String, Object> g = (Map<String, Object>) job().get("guardrails");
        assertEquals(true, g.get("disabled"));
        assertEquals(true, g.get("log_content"));

        // a run's AI calls: the prompt and answer are kept beside the record only while full logging is on
        Map<String, Object> run = db.one("SELECT id, bot_path, requested_by FROM runs ORDER BY created DESC LIMIT 1");
        cr.platform.recordAiCalls(run, List.of(Map.of("ts", "2026-11-06T10:00:00Z", "purpose", "prompt", "provider", "openai", "model", "m",
            "content", Map.of("prompt", "[{\"role\":\"user\",\"content\":\"Hi <PERSON_1>\"}]", "answer", "Hello"))));
        List<Map<String, Object>> calls = cr.platform.aiCallsOfRun(admin, (String) run.get("id"));
        assertEquals("Hello", ((Map<String, Object>) calls.get(0).get("content")).get("answer"));
        assertFalse(calls.get(0).containsKey("id"));

        cr.platform.updateSettings(admin, Map.of("ai_enabled", 1, "ai_log_content", 0), null);
        cr.platform.recordAiCalls(run, List.of(Map.of("ts", "2026-11-06T10:01:00Z", "purpose", "prompt", "content", Map.of("prompt", "secret"))));
        calls = cr.platform.aiCallsOfRun(admin, (String) run.get("id"));
        assertFalse(calls.get(1).containsKey("content"), "not kept once full logging is off");
    }
}
