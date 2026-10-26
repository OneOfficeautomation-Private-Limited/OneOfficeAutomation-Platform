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
}
