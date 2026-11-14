package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inputs left empty, as in AA (14 Nov 2026): an agent runs without them, an input given as nothing (null, or empty text
 * for a type that is not text) counts as not given, variables start empty for their type, and only a Required input
 * stops the run.
 */
class EmptyInputsTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flow(String variables, String steps) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"e\",\"name\":\"e\",\"variables\":[" + variables + "],\"steps\":[" + steps + "]}");
    }
    private static RunResult run(Map<String, Object> flow, Map<String, Object> inputs) {
        return Runner.builder(RunnerTest.registry()).build().run(flow, inputs);
    }

    private static final String VARS = """
            {"name":"name","type":"String","direction":"input"},{"name":"count","type":"Number","direction":"input"},
            {"name":"items","type":"List","direction":"input"},{"name":"opts","type":"Dictionary","direction":"input"},
            {"name":"flag","type":"Boolean","direction":"input"},{"name":"limit","type":"Number","direction":"input","default":5},
            {"name":"n_items","type":"Number","direction":"output"},{"name":"text","type":"String","direction":"output"},
            {"name":"got_limit","type":"Number","direction":"output"},{"name":"is_flag","type":"Boolean","direction":"output"}""";
    private static final String STEPS = """
            {"id":"a","action":"core/set","args":{"variable":"n_items","value":{"expr":"len($items$)"}}},
            {"id":"b","action":"core/set","args":{"variable":"text","value":"[$name$]"}},
            {"id":"c","action":"core/set","args":{"variable":"got_limit","value":"$limit$"}},
            {"id":"d","action":"core/set","args":{"variable":"is_flag","value":"$flag$"}}""";

    @Test void runsWithNoInputsAndEveryVariableStartsEmptyForItsType() {
        RunResult r = run(flow(VARS, STEPS), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) r.outputs().get("n_items")));   // an empty list
        assertEquals("[]", r.outputs().get("text"));                                            // empty text
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) r.outputs().get("got_limit"))); // its default
        assertEquals(false, r.outputs().get("is_flag"));
    }

    @Test void anInputGivenAsNothingCountsAsNotGiven() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("count", ""); inputs.put("items", ""); inputs.put("opts", " "); inputs.put("flag", ""); inputs.put("limit", null); inputs.put("name", null);
        RunResult r = run(flow(VARS, STEPS), inputs);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) r.outputs().get("got_limit")));   // the default, not null
        assertEquals("[]", r.outputs().get("text"));
    }

    @Test void emptyTextIsAValueForAText() {
        RunResult r = run(flow(VARS, STEPS), Map.of("name", ""));
        assertTrue(r.ok());
        assertEquals("[]", r.outputs().get("text"));
    }

    @Test void onlyARequiredInputStopsTheRun() {
        RunResult r = run(flow("{\"name\":\"count\",\"type\":\"Number\",\"direction\":\"input\",\"required\":true}", ""), Map.of("count", ""));
        assertFalse(r.ok());
        assertEquals("RUN.INPUT_MISSING", r.error().get("code"));
        assertTrue(String.valueOf(r.error().get("message")).contains("has no value"));
    }

    @Test void anEmptyNumberInACalculationSaysWhatIsWrong() {
        RunResult r = run(flow("{\"name\":\"count\",\"type\":\"Number\",\"direction\":\"input\"},{\"name\":\"out\",\"type\":\"Number\",\"direction\":\"output\"}",
                "{\"id\":\"b\",\"action\":\"core/set\",\"args\":{\"variable\":\"out\",\"value\":{\"expr\":\"$count$ + 1\"}}}"), Map.of());
        assertFalse(r.ok());
        assertEquals("VAR.EMPTY", r.error().get("code"));
        assertTrue(String.valueOf(r.error().get("suggestedFix")).contains("set a default"));
    }
}
