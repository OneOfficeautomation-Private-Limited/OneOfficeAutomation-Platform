package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** T6: the If builder's Test judges a condition with sample values, as a run would. */
class ConditionTestRouteTest {

    @SuppressWarnings("unchecked")
    @Test void conditionsAreJudgedWithSampleValues() throws Exception {
        Path ws = Files.createTempDirectory("ooa-cond-test");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(ws, port, Path.of("/nonexistent")).start();
        String base = "http://127.0.0.1:" + port;
        Map<String, Object> cond = Map.of("all", List.of(Map.of("op", "gt", "left", "$amount", "right", 1000),
            Map.of("op", "eq", "left", "$status", "right", "open", "ignoreCase", true)));
        List<Object> vars = List.of(Map.of("name", "amount", "type", "Number", "value", 1500), Map.of("name", "status", "type", "String", "value", "OPEN"));
        assertEquals(true, DebuggerTest.call(base, "POST", "/api/condition/test", Map.of("condition", cond, "variables", vars)).get("result"));

        List<Object> small = List.of(Map.of("name", "amount", "type", "Number", "value", 10), Map.of("name", "status", "type", "String", "value", "open"));
        assertEquals(false, DebuggerTest.call(base, "POST", "/api/condition/test", Map.of("condition", cond, "variables", small)).get("result"));

        // an expression works the same; a broken one gives the reason instead of a result
        assertEquals(true, DebuggerTest.call(base, "POST", "/api/condition/test", Map.of("condition", "$amount > 5", "variables", small)).get("result"));
        Map<String, Object> bad = DebuggerTest.call(base, "POST", "/api/condition/test", Map.of("condition", "$amount >", "variables", small));
        assertNull(bad.get("result"));
        assertNotNull(bad.get("error"), bad.toString());
    }
}
