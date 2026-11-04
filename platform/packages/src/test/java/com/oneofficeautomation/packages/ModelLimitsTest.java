package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Model connection budgets and limits (A4), as the runner keeps them. */
class ModelLimitsTest {

    static ActionContext ctx(Path w, String runId, List<String> logs) {
        return new ActionContext(runId, "s1", "default", w, new HashMap<>(), new HashMap<>(), new java.util.concurrent.atomic.AtomicBoolean(),
                List::of, (l, m) -> logs.add(m));
    }

    @Test void blockedConnectionsCallsPerRunAndCallsPerMinute() throws Exception {
        Path w = Files.createTempDirectory("ooa-model-limits");
        Files.writeString(w.resolve("model-connections.json"), "{\"default\":\"gpt\",\"connections\":["
            + "{\"name\":\"gpt\",\"provider\":\"openai\",\"model\":\"gpt-4o-mini\",\"calls_per_run\":3,\"calls_per_minute\":2},"
            + "{\"name\":\"spent\",\"provider\":\"openai\",\"model\":\"gpt-4o-mini\",\"blocked\":\"The connection's budget of 1000 tokens this month is used up\"}]}");
        List<String> logs = new ArrayList<>();
        ActionContext run1 = ctx(w, "run_limits_1", logs);

        ActionException spent = assertThrows(ActionException.class, () -> ModelConnections.config(run1, "spent", null, null, null, null, 0, 100, false));
        assertEquals("AI.BUDGET_REACHED", spent.code());

        long[] now = { 1_000_000 };
        List<Long> waits = new ArrayList<>();
        var clock = ModelConnections.clock; var sleeper = ModelConnections.sleeper;
        ModelConnections.clock = () -> now[0];
        ModelConnections.sleeper = ms -> { waits.add(ms); now[0] += ms; };
        try {
            var cfg = ModelConnections.config(run1, "gpt", null, null, null, null, 0, 100, false);
            assertEquals("gpt", ModelConnections.connectionOf(cfg), "calls are recorded under their connection");
            ModelConnections.beforeCall(run1, cfg);
            now[0] += 1000;
            ModelConnections.beforeCall(run1, cfg);
            assertTrue(waits.isEmpty());
            ModelConnections.beforeCall(run1, cfg);   // the third call in the same minute waits for the first to leave it
            assertEquals(List.of(59_000L), waits);
            assertTrue(logs.stream().anyMatch(l -> l.contains("2 calls a minute")), logs.toString());
            assertEquals("AI.CALL_LIMIT", assertThrows(ActionException.class, () -> ModelConnections.beforeCall(run1, cfg)).code(), "3 calls a run");

            // another run starts counting again
            ActionContext run2 = ctx(w, "run_limits_2", logs);
            ModelConnections.beforeCall(run2, ModelConnections.config(run2, "gpt", null, null, null, null, 0, 100, false));

            // settings typed on the action itself have no connection and no limits
            var typed = GenAiPackage.cfg("openai", "gpt-4o", null, null, 0, 100, false);
            for (int i = 0; i < 10; i++) ModelConnections.beforeCall(run1, typed);
            assertNull(ModelConnections.connectionOf(typed));
        } finally { ModelConnections.clock = clock; ModelConnections.sleeper = sleeper; }
    }

    /** A folder's AI guardrails (Administration > Policies), sent with the run: masking forced on, only the models allowed, the content check's mode. */
    /** A policy can mask only some kinds of personal data (Administration > Policies). */
    @Test void maskingByKind() {
        String text = "Mail ann@example.com, card 4111 1111 1111 1111, from 10.0.0.1";
        Map<String, String> map = new java.util.LinkedHashMap<>();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        String all = LlmClient.mask(text, map, counts);
        assertFalse(all.contains("ann@example.com") || all.contains("4111") || all.contains("10.0.0.1"), all);
        map.clear(); counts.clear();
        String some = LlmClient.mask(text, map, counts, java.util.Set.of("payment"));
        assertTrue(some.contains("ann@example.com") && some.contains("10.0.0.1"), some);
        assertFalse(some.contains("4111"), some);
        assertEquals(java.util.Set.of("CREDIT_CARD"), counts.keySet());
    }

    @Test void aiGuardrailsFromThePolicy() throws Exception {
        Path w = Files.createTempDirectory("ooa-guardrails");
        Files.writeString(w.resolve("model-connections.json"), "{\"connections\":[{\"name\":\"azure\",\"provider\":\"azure_openai\",\"model\":\"gpt-4o\"},"
            + "{\"name\":\"openai\",\"provider\":\"openai\",\"model\":\"gpt-4o-mini\",\"content_check\":\"moderation\"}]}");
        Map<String, Object> services = new HashMap<>();
        services.put("ai_guardrails", (java.util.function.Supplier<Object>) () -> Map.of("name", "Finance AI", "mask", true, "models", List.of("azure"), "content", "log"));
        ActionContext ctx = new ActionContext("run_guard", "s1", "default", w, new HashMap<>(), services, new java.util.concurrent.atomic.AtomicBoolean(), List::of, (l, m) -> { });

        var cfg = ModelConnections.config(ctx, "azure", null, null, null, null, 0, 100, false);
        assertTrue(cfg.maskPii(), "the step asked not to mask, the guardrails mask anyway");
        assertEquals("log", ModelConnections.contentCheck(ctx, cfg), "the guardrails' content check wins over the connection's");
        ActionException other = assertThrows(ActionException.class, () -> ModelConnections.config(ctx, "openai", null, null, null, null, 0, 100, false));
        assertEquals("AI.MODEL_NOT_ALLOWED", other.code());
        assertTrue(other.getMessage().contains("Finance AI"), other.getMessage());
        assertEquals("AI.MODEL_NOT_ALLOWED", assertThrows(ActionException.class, () -> ModelConnections.config(ctx, null, "openai", "gpt-4o", null, null, 0, 100, false)).code(),
            "a model typed on the step is not an allowed connection");

        // without guardrails: the step's own setting, and the connection's content check
        ActionContext free = ctx(w, "run_free", new ArrayList<>());
        var own = ModelConnections.config(free, "openai", null, null, null, null, 0, 100, false);
        assertFalse(own.maskPii());
        assertEquals("block", ModelConnections.contentCheck(free, own));
    }
}
