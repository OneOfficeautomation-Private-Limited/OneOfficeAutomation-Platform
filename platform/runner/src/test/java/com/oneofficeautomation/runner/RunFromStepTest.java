package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** RunOptions.startAt ("Run from here") and onlyStep ("Run this step only"), used by Studio's step menu. */
class RunFromStepTest {
    @SuppressWarnings("unchecked")
    static Map<String, Object> flow() {
        return (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"from","name":"from","variables":[
              {"name":"a","type":"String","direction":"output","default":"-"},{"name":"b","type":"String","direction":"output","default":"-"},
              {"name":"c","type":"String","direction":"output","default":"-"},{"name":"d","type":"String","direction":"output","default":"-"},
              {"name":"e","type":"String","direction":"output","default":"-"},{"name":"f","type":"String","direction":"output","default":"-"},
              {"name":"n","type":"Number","direction":"output","default":0},{"name":"item","type":"Any"},{"name":"err","type":"Dictionary"}],
             "steps":[
              {"id":"s1","action":"core/set","args":{"variable":"a","value":"one"}},
              {"id":"s2","action":"core/if","args":{"condition":{"op":"eq","left":1,"right":2}},
               "children":[{"id":"s3","action":"core/set","args":{"variable":"b","value":"then"}},
                           {"id":"s4","action":"core/set","args":{"variable":"c","value":"after"}}],
               "else":[{"id":"s5","action":"core/set","args":{"variable":"b","value":"else"}}]},
              {"id":"s6","action":"core/set","args":{"variable":"d","value":"end"}},
              {"id":"s7","action":"core/loop","args":{"iterator":"list","over":[1,2,3],"as":"item"},
               "children":[{"id":"s8","action":"core/log","args":{"message":"item"}},
                           {"id":"s9","action":"core/set","args":{"variable":"n","value":{"expr":"$n + 1"}}}]},
              {"id":"s10","action":"core/try",
               "children":[{"id":"s11","action":"core/throw","args":{"code":"BUSINESS.X","message":"boom"}}],
               "catch":[{"codes":[],"as":"err","children":[{"id":"s12","action":"core/set","args":{"variable":"e","value":"caught"}}]}],
               "finally":[{"id":"s13","action":"core/set","args":{"variable":"f","value":"finally"}}]}]}""");
    }

    static RunResult run(Runner.RunOptions opts) {
        return Runner.builder(RunnerTest.registry()).build().run(flow(), Map.of(), opts);
    }

    @Test void runFromInsideAnIfEntersItWithoutTheCondition() {
        RunResult r = run(new Runner.RunOptions().startAt("s3"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("-", r.outputs().get("a"), "steps before the start step are skipped");
        assertEquals("then", r.outputs().get("b"), "the If is entered although its condition is false");
        assertEquals("after", r.outputs().get("c"));
        assertEquals("end", r.outputs().get("d"), "steps after the block run normally");
        assertEquals(new java.math.BigDecimal("3"), r.outputs().get("n"), "later loops iterate normally");
        assertEquals("caught", r.outputs().get("e"));
        assertEquals("finally", r.outputs().get("f"));
    }

    @Test void runFromATopLevelStep() {
        RunResult r = run(new Runner.RunOptions().startAt("s6"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("-", r.outputs().get("a"));
        assertEquals("-", r.outputs().get("b"), "neither branch of the skipped If runs");
        assertEquals("end", r.outputs().get("d"));
    }

    @Test void runOnlyOneStep() {
        RunResult r = run(new Runner.RunOptions().onlyStep("s4"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("after", r.outputs().get("c"));
        assertEquals("-", r.outputs().get("b"));
        assertEquals("-", r.outputs().get("d"), "nothing after the step runs");
        assertEquals(1, r.stepsExecuted());
    }

    @Test void runFromInsideALoopRunsOnePass() {
        RunResult r = run(new Runner.RunOptions().startAt("s9"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(new java.math.BigDecimal("1"), r.outputs().get("n"), "the loop is entered for one pass");
        assertEquals("caught", r.outputs().get("e"));
    }

    @Test void runFromInsideATryKeepsCatchAndFinally() {
        RunResult r = run(new Runner.RunOptions().startAt("s11"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("caught", r.outputs().get("e"));
        assertEquals("finally", r.outputs().get("f"));
        assertEquals("-", r.outputs().get("d"));
    }

    @Test void onlyABlockRunsTheWholeBlock() {
        RunResult r = run(new Runner.RunOptions().onlyStep("s7"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(new java.math.BigDecimal("3"), r.outputs().get("n"));
        assertEquals("-", r.outputs().get("e"));
    }

    @Test void unknownStartStepFails() {
        RunResult r = run(new Runner.RunOptions().startAt("nope"));
        assertFalse(r.ok());
        assertEquals("RUN.START_NOT_FOUND", r.error().get("code"));
    }

    @Test void skippedStepsEmitNoEvents() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(RunnerTest.registry()).sink(sink).build().run(flow(), Map.of(), new Runner.RunOptions().startAt("s6"));
        assertTrue(r.ok());
        Set<String> started = new HashSet<>();
        for (RunEvent e : sink.events()) if (e.type().equals("step.started")) started.add(e.stepId());
        assertFalse(started.contains("s1"));
        assertFalse(started.contains("s2"));
        assertTrue(started.contains("s6"));
    }

    @Test void theTrailKeepsTheValuesEachStepStartedWith() {
        Runner.ValueTrail trail = new Runner.ValueTrail();
        assertTrue(run(new Runner.RunOptions().trail(trail)).ok());
        assertEquals("-", trail.before("s1").get("a"), "before s1 nothing is set yet");
        assertEquals("one", trail.before("s6").get("a"));
        assertEquals("else", trail.before("s6").get("b"));
        assertEquals(new java.math.BigDecimal("0"), trail.before("s7").get("n"));
        assertEquals(new java.math.BigDecimal("0"), trail.before("s9").get("n"), "a step in a loop: its first pass");
        assertEquals("caught", trail.atEnd().get("e"));
        assertFalse(trail.reached("nope"));
        assertNull(trail.before("nope"), "a successful run that never reached a step has no values for it");
    }

    @Test @SuppressWarnings("unchecked")
    void aRunThatFailedEarlyLeavesTheValuesItEndedWith() {
        Map<String, Object> f = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"early","name":"early","variables":[{"name":"a","type":"String","default":"-"}],
             "steps":[{"id":"t1","action":"core/set","args":{"variable":"a","value":"set"}},
                      {"id":"t2","action":"core/throw","args":{"code":"BUSINESS.X","message":"boom"}},
                      {"id":"t3","action":"core/log","args":{"message":"after"}}]}""");
        Runner.ValueTrail trail = new Runner.ValueTrail();
        assertFalse(Runner.builder(RunnerTest.registry()).build().run(f, Map.of(), new Runner.RunOptions().trail(trail)).ok());
        assertFalse(trail.reached("t3"));
        assertEquals("set", trail.before("t3").get("a"), "it stopped before t3: the values it ended with");
    }

    @Test void runFromHereUsesTheValuesOfTheLastRun() {
        Runner.ValueTrail last = new Runner.ValueTrail();
        assertTrue(run(new Runner.RunOptions().trail(last)).ok());
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(RunnerTest.registry()).sink(sink).build()
                .run(flow(), Map.of(), new Runner.RunOptions().startAt("s6").carryOver(last.before("s6")));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("one", r.outputs().get("a"), "set by the skipped s1 in the last run");
        assertEquals("else", r.outputs().get("b"));
        assertEquals("end", r.outputs().get("d"));
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("log") && String.valueOf(e.data().get("message")).startsWith("Values from the last run: a, b")));
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("variable.set") && Boolean.TRUE.equals(e.data().get("carried"))));
    }

    @Test void carriedValuesOfAnotherTypeOrNameAreLeftOut() {
        Map<String, Object> old = new HashMap<>(Map.of("a", "one", "n", "not a number", "gone", "x"));
        RunResult r = run(new Runner.RunOptions().startAt("s6").carryOver(old));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("one", r.outputs().get("a"));
        assertEquals(new java.math.BigDecimal("3"), r.outputs().get("n"), "n kept its default 0; the loop counted to 3");
    }

    @Test void carriedValuesNeedAStartStep() {
        RunResult r = run(new Runner.RunOptions().carryOver(Map.of("d", "old")));
        assertTrue(r.ok());
        assertEquals("one", r.outputs().get("a"));
        assertEquals("end", r.outputs().get("d"), "a full run starts from its defaults");
    }
}
