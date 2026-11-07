package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.FlowValidator;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The blueprints Create offers (studio/blueprints.json): each one is a valid agent as it stands, and the transactional
 * framework does what it promises: completes good items, fails a business error at once, retries a system error until
 * its tries run out, and reports the counts.
 */
class BlueprintsTest {
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> blueprints() throws Exception {
        try (InputStream in = BlueprintsTest.class.getResourceAsStream("/studio/blueprints.json")) {
            assertNotNull(in);
            return (List<Map<String, Object>>) JsonLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static Map<String, Object> flowOf(Map<String, Object> b) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "bp_" + b.get("id")); flow.put("name", b.get("name"));
        flow.put("variables", b.get("variables")); flow.put("steps", b.get("steps"));
        if (b.get("subflows") != null) flow.put("subflows", b.get("subflows"));
        if (b.get("onError") != null) flow.put("onError", b.get("onError"));
        return flow;
    }

    @Test void everyBlueprintIsAValidAgent() throws Exception {
        FlowValidator v = new FlowValidator(FirstPartyPackages.registry(), Ooa.schemaValidator());
        List<Map<String, Object>> all = blueprints();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> b : all) {
            assertTrue(ids.add((String) b.get("id")), "ids are unique: " + b.get("id"));
            assertTrue(b.get("description") instanceof String d && !d.isBlank(), b.get("id") + " has a description");
            assertEquals(List.of(), v.validate(flowOf(b)), b.get("id") + " validates");
        }
        assertTrue(ids.containsAll(List.of("transactional", "attended_ask", "attended_file")), ids.toString());
    }

    @SuppressWarnings("unchecked")
    @Test void transactionalFrameworkCompletesFailsAndRetries(@TempDir Path tmp) throws Exception {
        Map<String, Object> b = blueprints().stream().filter(x -> "transactional".equals(x.get("id"))).findFirst().orElseThrow();
        Map<String, Object> flow = flowOf((Map<String, Object>) JsonLite.parse(JsonLite.write(b)));
        // Process: item 2 breaks a business rule, item 3 always hits a system error, the rest succeed
        for (Map<String, Object> sub : (List<Map<String, Object>>) flow.get("subflows"))
            if ("Process".equals(sub.get("name"))) sub.put("steps", JsonLite.parse("""
                    [{"id": "x1", "action": "core/if", "args": {"condition": {"op": "eq", "left": "${item_data.n}", "right": 2}},
                      "children": [{"id": "x2", "action": "core/throw", "args": {"code": "BUSINESS.REJECTED", "message": "No purchase order"}}]},
                     {"id": "x3", "action": "core/if", "args": {"condition": {"op": "eq", "left": "${item_data.n}", "right": 3}},
                      "children": [{"id": "x4", "action": "core/throw", "args": {"code": "APP.DOWN", "message": "The app did not answer"}}]}]"""));
        Runner runner = Runner.builder(FirstPartyPackages.registry()).workDir(tmp).build();
        List<Map<String, Object>> setup = new ArrayList<>();
        for (int n = 1; n <= 4; n++)
            setup.add(Map.of("id", "a" + n, "action", "ooa.workload/add_item", "args", Map.of("queue", "bp_claims", "data", Map.of("n", n), "reference", "C-" + n)));
        RunResult s = runner.run(Map.of("kind", "TaskBot", "id", "setup", "name", "setup", "variables", List.of(), "steps", setup), Map.of());
        assertTrue(s.ok(), String.valueOf(s.error()));

        RunResult r = runner.run(flow, Map.of("queue", "bp_claims", "max_attempts", 2));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(2, ((Number) r.outputs().get("processed")).intValue(), "items 1 and 4 completed: " + r.outputs());
        assertEquals(2, ((Number) r.outputs().get("failed")).intValue(), "item 2 failed at once, item 3 after its two tries: " + r.outputs());
    }
}
