package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** core/switch: one value, the first matching case runs, default otherwise; break/continue reach the loop; run from here. */
class SwitchStepTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String json) { return (Map<String, Object>) JsonLite.parse(json); }

    /** A switch on `value` with the given match and cases A, B (and default D when withDefault); returns what ran. */
    static Object pick(String value, String match, String caseA, String caseB, boolean withDefault) {
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"v","type":"Any","default":%s},{"name":"got","type":"String","direction":"output","default":"none"}],
             "steps":[{"id":"1","action":"core/switch","args":{"value":"$v"%s},
               "cases":[{"values":%s,"children":[{"id":"a","action":"core/set","args":{"variable":"got","value":"A"}}]},
                        {"values":%s,"children":[{"id":"b","action":"core/set","args":{"variable":"got","value":"B"}}]}]%s}]}"""
                .formatted(value, match == null ? "" : ",\"match\":\"" + match + "\"", caseA, caseB,
                        withDefault ? ",\"default\":[{\"id\":\"d\",\"action\":\"core/set\",\"args\":{\"variable\":\"got\",\"value\":\"D\"}}]" : "")), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        return r.outputs().get("got");
    }

    @Test void equalsUsesTheSameRulesAsDoubleEquals() {
        assertEquals("B", pick("5", null, "[\"4\", 1]", "[\"5\"]", true), "number 5 matches text \"5\"");
        assertEquals("A", pick("\"7\"", "equals", "[3, 7]", "[\"7\"]", true), "a case may list several values; the first case wins");
        assertEquals("D", pick("\"x\"", null, "[\"X\"]", "[\"y\"]", true));
        assertEquals("B", pick("\"x\"", null, "[\"X\"]", "[\"x\"]", true), "case-sensitive by default");
    }

    @Test void noMatchWithoutDefaultRunsNothing() {
        assertEquals("none", pick("\"q\"", null, "[\"a\"]", "[\"b\"]", false));
    }

    @Test void otherMatchKinds() {
        assertEquals("B", pick("\"INV-2024-17\"", "contains", "[\"PO\"]", "[\"2024\"]", true));
        assertEquals("A", pick("\"INV-2024-17\"", "starts_with", "[\"INV\"]", "[\"INV-2024\"]", true), "no fall-through to the next match");
        assertEquals("B", pick("\"report.pdf\"", "ends_with", "[\".xlsx\"]", "[\".pdf\"]", true));
        assertEquals("A", pick("\"INV-2024-17\"", "regex", "[\"^INV-\\\\d{4}-\\\\d+$\"]", "[\".*\"]", true));
    }

    @Test void ignoreCase() {
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"got","type":"String","direction":"output","default":"none"}],
             "steps":[{"id":"1","action":"core/switch","args":{"value":"Approved","ignore_case":true},
               "cases":[{"values":["APPROVED"],"children":[{"id":"a","action":"core/set","args":{"variable":"got","value":"A"}}]}]}]}"""), Map.of());
        assertEquals("A", r.outputs().get("got"));
    }

    @Test void expressionCasesAreConditions() {
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"n","type":"Number","default":80},{"name":"got","type":"String","direction":"output","default":"none"}],
             "steps":[{"id":"1","action":"core/switch","args":{"match":"expression"},
               "cases":[{"values":["$n >= 90"],"children":[{"id":"a","action":"core/set","args":{"variable":"got","value":"A"}}]},
                        {"values":[{"op":"ge","left":"$n","right":75}],"children":[{"id":"b","action":"core/set","args":{"variable":"got","value":"B"}}]}],
               "default":[{"id":"d","action":"core/set","args":{"variable":"got","value":"C"}}]}]}"""), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("B", r.outputs().get("got"));
    }

    @Test void logSaysWhichCaseRan() {
        EventSink.ListSink sink = new EventSink.ListSink();
        Runner.builder(RunnerTest.registry()).sink(sink).build().run(flow("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/switch","args":{"value":"b"},
               "cases":[{"values":["a"],"children":[]},{"values":["b"],"children":[]}]}]}"""), Map.of());
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("log") && String.valueOf(e.data().get("message")).startsWith("Switch: case 2 matched")));
    }

    @Test void breakAndContinueInsideACaseReachTheLoop() {
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"item","type":"Any"},{"name":"seen","type":"List","direction":"output","default":[]}],
             "steps":[{"id":"1","action":"core/loop","args":{"iterator":"list","over":["a","skip","b","stop","c"],"as":"item"},"children":[
               {"id":"2","action":"core/switch","args":{"value":"$item"},
                 "cases":[{"values":["skip"],"children":[{"id":"3","action":"core/continue"}]},
                          {"values":["stop"],"children":[{"id":"4","action":"core/break"}]}]},
               {"id":"5","action":"core/set","args":{"variable":"seen","value":{"expr":"$seen + [$item]"}}}]}]}"""), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(List.of("a", "b"), r.outputs().get("seen"));
    }

    @Test void runFromHereEntersACaseWithoutComparing() {
        String json = """
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"x","type":"String","direction":"output","default":"-"},{"name":"y","type":"String","direction":"output","default":"-"}],
             "steps":[{"id":"1","action":"core/switch","args":{"value":"nothing matches"},
               "cases":[{"values":["a"],"children":[{"id":"2","action":"core/set","args":{"variable":"x","value":"skipped"}}]},
                        {"values":["b"],"children":[{"id":"3","action":"core/set","args":{"variable":"x","value":"case"}},{"id":"4","action":"core/set","args":{"variable":"y","value":"after"}}]}],
               "default":[{"id":"5","action":"core/set","args":{"variable":"y","value":"default"}}]}]}""";
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow(json), Map.of(), new Runner.RunOptions().startAt("3"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("case", r.outputs().get("x"));
        assertEquals("after", r.outputs().get("y"));
        RunResult d = Runner.builder(RunnerTest.registry()).build().run(flow(json), Map.of(), new Runner.RunOptions().startAt("5"));
        assertEquals("-", d.outputs().get("x"));
        assertEquals("default", d.outputs().get("y"));
    }

    @Test void badRegexAtRunTimeFails() {
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"p","type":"String","default":"("}],
             "steps":[{"id":"1","action":"core/switch","args":{"value":"x","match":"regex"},"cases":[{"values":["$p"],"children":[]}]}]}"""), Map.of());
        assertEquals("SWITCH.BAD_REGEX", r.error().get("code"));
    }

    static List<String> problems(String json) {
        return new FlowValidator(RunnerTest.registry(), null).validate(flow(json)).stream().map(p -> String.valueOf(p.get("message"))).toList();
    }

    @Test void validator() {
        assertEquals(List.of(), problems("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"s","type":"String"}],
             "steps":[{"id":"1","action":"core/switch","args":{"value":"$s","match":"regex"},"cases":[{"values":["^a$", "b+"],"children":[]}],"default":[]}]}"""));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/switch","args":{"value":"x"},"cases":[]}]}""")
                .contains("Switch needs at least one case"));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/switch","args":{"value":"x","match":"regex"},"cases":[{"values":["("],"children":[]}]}]}""")
                .stream().anyMatch(m -> m.startsWith("Case 1: '(' is not a valid pattern")));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/if","args":{"condition":true},"cases":[{"values":[1],"children":[]}],"default":[]}]}""")
                .contains("cases/default are only allowed on core/switch"));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/switch","cases":[{"values":[1],"children":[]}]}]}""")
                .contains("Switch has no value to compare"));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/switch","args":{"match":"expression"},"cases":[{"values":["$nope > 1"],"children":[]}]}]}""")
                .contains("Variable '$nope' is not declared"));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/switch","args":{"value":"x","match":"sideways"},"cases":[{"values":[1],"children":[]}]}]}""")
                .stream().anyMatch(m -> m.contains("is not one of")));
        // steps inside cases and default are checked like any other step
        assertTrue(problems("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/switch","args":{"value":"x"},"cases":[{"values":[1],"children":[
              {"id":"2","action":"core/set","args":{"variable":"ghost","value":1}}]}],"default":[{"id":"2","action":"core/log","args":{"message":"m"}}]}]}""")
                .containsAll(List.of("Variable 'ghost' is not declared", "Duplicate step id '2'")));
    }
}
