package com.oneofficeautomation.packages.browser;

import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** G4 (AA parity, 22 Nov 2026): Reach a goal with several goals one after another, each with its result, a failed one stopping the rest. */
class WebAgentGoalsG4Test {
    @SuppressWarnings("unchecked")
    @Test void severalGoalsInOneStep() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer site = WebAgentTest.site();
        HttpServer m = WebAgentTest.model(List.of(
                "type_text {\"id\":1,\"text\":\"ann\"}",
                "click {\"id\":3}",
                "finish {\"success\":true,\"answer\":\"Signed in.\"}",
                "finish {\"success\":true,\"answer\":\"3 open.\",\"data\":{\"open_invoices\":3}}",
                "finish {\"success\":false,\"answer\":\"No pay button.\"}"), seen);
        Path w = Files.createTempDirectory("ooa-g4goals");
        ActionContext c = new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, x) -> {});
        WebAgentPackage a = new WebAgentPackage();
        String base = "http://127.0.0.1:" + site.getAddress().getPort();
        String model = "http://127.0.0.1:" + m.getAddress().getPort() + "/v1";
        try {
            try { a.startSession(c, base + "/", "edge", true, "browser"); }
            catch (RuntimeException e) { System.out.println("    (no browser available: " + e.getMessage() + "; skipped)"); return; }
            Map<String, Object> r = a.run(c, "Sign in as ann.", "browser", 10, List.of("127.0.0.1"), null, null, "openai_compatible", null, "test-model", null, model,
                    false, null, null, 30, null, null, null, null, List.of("Tell me how many invoices are open.", "Pay them all."), 120);
            List<Object> results = (List<Object>) r.get("goal_results");
            assertEquals(3, results.size(), String.valueOf(results));
            assertEquals(true, ((Map<String, Object>) results.get(0)).get("success"));
            assertEquals(Map.of("open_invoices", new java.math.BigDecimal(3)), ((Map<String, Object>) results.get(1)).get("data"));
            assertEquals(false, ((Map<String, Object>) results.get(2)).get("success"));
            assertEquals(false, r.get("success"), "not every goal was reached");
            assertTrue(seen.stream().anyMatch(x -> x.contains("Tell me how many invoices are open.")), "the second goal went to the model");
            assertEquals("WEBAGENT.NO_GOAL", assertThrows(ActionException.class, () -> a.run(c, null, "browser", 10, null, null, null, "openai_compatible", null, "test-model",
                    null, model, false, null, null, 30, null, null, null, null, List.of(), 60)).code());
        } finally {
            try { a.endSession(c, "browser"); } catch (RuntimeException ignored) { }
            m.stop(0);
            site.stop(0);
        }
    }
}
