package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comparisons in If, Loop while and Wait until accept the snake_case spellings the data table filter uses
 * ("not_empty", "starts_with"), and Validate reports an operator the runner does not know (real-world tests finding 4:
 * {"op": "not_empty"} used to pass Validate and fail only at run time).
 */
class ConditionSpellingTest {
    @Test void snakeCaseSpellingsMeanTheSame() {
        assertEquals("notEmpty", Conditions.canonical("not_empty"));
        assertEquals("startsWith", Conditions.canonical("starts_with"));
        assertEquals("notBetween", Conditions.canonical("not_between"));
        assertEquals("eq", Conditions.canonical("eq"));
        assertNull(Conditions.canonical("is_nice"));
        assertTrue(Conditions.compare("not_empty", "x", null));
        assertFalse(Conditions.compare("not_empty", "", null));
        assertTrue(Conditions.compare("starts_with", "OOA-QA-1", "OOA-QA"));
        assertTrue(Conditions.compare("not_in", "c", List.of("a", "b")));
    }

    @SuppressWarnings("unchecked")
    @Test void anIfWithASnakeCaseOperatorRuns() {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"c","name":"c","variables":[{"name":"prev","type":"String","direction":"local","default":"APR-1"},
              {"name":"out","type":"String","direction":"output","default":"no"}],
             "steps":[{"id":"i","action":"core/if","args":{"condition":{"op":"not_empty","left":"$prev$"}},
                       "children":[{"id":"s","action":"core/set","args":{"variable":"out","value":"yes"}}]}]}""");
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("yes", r.outputs().get("out"));
    }

    @SuppressWarnings("unchecked")
    @Test void validateReportsAnUnknownOperator() {
        FlowValidator v = new FlowValidator(RunnerTest.registry(), null);
        Map<String, Object> bad = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"c","name":"c","variables":[{"name":"x","type":"String","direction":"local"}],
             "steps":[{"id":"i","action":"core/if","args":{"condition":{"all":[{"op":"is_set","left":"$x$"}]}},"children":[]}]}""");
        List<Map<String, Object>> problems = v.validate(bad);
        assertTrue(problems.stream().anyMatch(p -> String.valueOf(p.get("message")).contains("Unknown comparison 'is_set'")), String.valueOf(problems));
        Map<String, Object> good = (Map<String, Object>) JsonLite.parse(JsonLite.write(bad).replace("is_set", "not_empty"));
        assertTrue(v.validate(good).stream().noneMatch(p -> String.valueOf(p.get("message")).contains("Unknown comparison")));
    }
}
