package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.modules.governance.Policies;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** An AI guardrails policy: the kinds of personal data it masks, and What it stopped (steps stopped, data masked). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyStoppedTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-policy-stopped");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { db.close(); }

    @Test @SuppressWarnings("unchecked") void kindsAndWhatItStopped() {
        String id = (String) cr.modules.service(Policies.class).save(admin, null, Map.of("name", "Finance AI", "kind", "ai", "folders", List.of("Finance"),
            "rules", Map.of("mask", true, "mask_kinds", List.of("payment", "contacts"), "content", "block")), null).get("id");
        assertEquals(List.of("payment", "contacts"), cr.modules.service(Policies.class).guardrailsFor("Finance/Pay.flow.json").get("mask_kinds"));
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(Policies.class).save(admin, id, Map.of("name", "Finance AI", "kind", "ai", "folders", List.of("Finance"),
            "rules", Map.of("mask", true, "mask_kinds", List.of("shoe sizes"))), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(Policies.class).save(admin, id, Map.of("name", "Finance AI", "kind", "ai", "folders", List.of("Finance"),
            "rules", Map.of("mask", true, "mask_kinds", List.of())), null)).code(), "masking on with no kind");

        Instant t = Instant.now().minus(Duration.ofHours(1));
        run("r1", "Finance/Pay.flow.json", "{\"code\":\"AI.MODEL_NOT_ALLOWED\",\"message\":\"not allowed\"}", t);
        run("r2", "Finance/Pay.flow.json", "{\"code\":\"EXCEL.NOT_FOUND\",\"message\":\"no file\"}", t.plusSeconds(1));
        run("r3", "Sales/Quote.flow.json", "{\"code\":\"AI.CONTENT_FLAGGED\",\"message\":\"flagged\"}", t.plusSeconds(2));   // another folder
        db.update("INSERT INTO ai_calls (id, run_id, bot_path, ts, masked) VALUES (?,?,?,?,?)", "c1", "r1", "Finance/Pay.flow.json", t, "{\"EMAIL\":2,\"CREDIT_CARD\":1}");
        db.update("INSERT INTO ai_calls (id, run_id, bot_path, ts, masked) VALUES (?,?,?,?,?)", "c2", "r3", "Sales/Quote.flow.json", t, "{\"EMAIL\":5}");
        Map<String, Object> s = cr.modules.service(Policies.class).stopped(admin, id, Map.of("days", "30"));
        assertEquals(1, s.get("stopped_total"));
        assertEquals("AI.MODEL_NOT_ALLOWED", ((List<Map<String, Object>>) s.get("stopped")).get(0).get("code"));
        assertEquals(Map.of("CREDIT_CARD", 1L, "EMAIL", 2L), s.get("masked"));
        assertEquals(1L, s.get("calls_masked"));
    }

    void run(String id, String bot, String error, Instant created) {
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, error, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            id, bot, 1, "dev_x", "failed", "admin", "manual", created, error, false, false);
    }
}
