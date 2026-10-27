package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Monthly token budgets and call limits of model connections (A4). */
class ModelBudgetTest {

    @Test @SuppressWarnings("unchecked")
    void aConnectionOverItsMonthlyBudgetGoesToRunnersBlocked() throws Exception {
        Path data = Files.createTempDirectory("ooa-model-budget");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.platform.saveModelConnection(admin, Map.of("name", "gpt", "provider", "openai", "model", "gpt-4o-mini", "monthly_tokens", 1000, "calls_per_minute", 30, "calls_per_run", 50), null);
            assertThrows(OoaException.class, () -> cr.platform.saveModelConnection(admin, Map.of("name", "bad", "provider", "openai", "monthly_tokens", -1), null));
            assertThrows(OoaException.class, () -> cr.platform.saveModelConnection(admin, Map.of("name", "bad", "provider", "openai", "calls_per_minute", "many"), null));

            Map<String, Object> c = ((List<Map<String, Object>>) cr.platform.connectionsForJob(List.of("admin")).get("connections")).get(0);
            assertNull(c.get("blocked"));
            assertEquals(30, c.get("calls_per_minute"));
            assertEquals(50, c.get("calls_per_run"));

            // a run reports its calls; tokens count against the connection they went through
            Map<String, Object> run = Map.of("id", "run_1", "bot_path", "A.flow.json", "requested_by", "admin");
            cr.platform.recordAiCalls(run, List.of(Map.of("ts", Instant.now().toString(), "connection", "gpt", "provider", "openai", "model", "gpt-4o-mini", "input_tokens", 700, "output_tokens", 200),
                Map.of("ts", Instant.now().toString(), "provider", "openai", "model", "gpt-4o", "input_tokens", 5000, "output_tokens", 5000)));
            assertEquals(900, cr.platform.tokensThisMonth("gpt"), "calls without a connection do not count");
            assertNull(((List<Map<String, Object>>) cr.platform.connectionsForJob(List.of("admin")).get("connections")).get(0).get("blocked"));

            cr.platform.recordAiCalls(run, List.of(Map.of("ts", Instant.now().toString(), "connection", "gpt", "input_tokens", 100, "output_tokens", 0)));
            Map<String, Object> blocked = ((List<Map<String, Object>>) cr.platform.connectionsForJob(List.of("admin")).get("connections")).get(0);
            assertTrue(String.valueOf(blocked.get("blocked")).contains("1000 tokens"), blocked.toString());
            assertEquals(1000L, cr.platform.modelConnections(admin).get(0).get("tokens_this_month"));

            // A3: Presidio analyzer and content check go to runners; the run's AI calls read back in order
            cr.platform.saveModelConnection(admin, Map.of("name", "safe", "provider", "openai", "model", "gpt-4o-mini", "pii_analyzer_url", "https://presidio.internal", "content_check", "moderation"), null);
            assertThrows(OoaException.class, () -> cr.platform.saveModelConnection(admin, Map.of("name", "x", "provider", "openai", "pii_analyzer_url", "http://presidio.example"), null), "https only");
            assertThrows(OoaException.class, () -> cr.platform.saveModelConnection(admin, Map.of("name", "x", "provider", "anthropic", "content_check", "moderation"), null), "no moderation endpoint");
            Map<String, Object> safe = ((List<Map<String, Object>>) cr.platform.connectionsForJob(List.of("admin")).get("connections")).stream().filter(x -> "safe".equals(x.get("name"))).findFirst().orElseThrow();
            assertEquals("https://presidio.internal", safe.get("pii_analyzer_url"));
            assertEquals("moderation", safe.get("content_check"));
            db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "run_1", "A.flow.json", 1, "dev", "success", "{}", "admin", "manual", Instant.now(), false, false);
            List<Map<String, Object>> trace = cr.platform.aiCallsOfRun(admin, "run_1");
            assertEquals(3, trace.size());
            assertEquals("gpt", trace.get(0).get("connection"));
        } finally { db.close(); }
    }
}
