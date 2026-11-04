package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Subflows (core/run_subflow, core/exit_subflow) and labels with Go to (core/label, core/goto). */
class SubflowsGotoTest {
    private static final String VARS = """
        "variables":[{"name":"n","type":"Number","direction":"output","default":0},{"name":"log","type":"String","direction":"output","default":""},
                     {"name":"err","type":"Dictionary","direction":"output"},{"name":"x","type":"Any"}]""";

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String steps, String subflows) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"sg\",\"name\":\"sg\"," + VARS + ",\"steps\":" + steps
                + (subflows == null ? "" : ",\"subflows\":" + subflows) + "}");
    }

    static RunResult run(Map<String, Object> flow) { return run(flow, new Runner.RunOptions()); }
    static RunResult run(Map<String, Object> flow, Runner.RunOptions o) { return Runner.builder(RunnerTest.registry()).build().run(flow, Map.of(), o); }
    static List<Map<String, Object>> problems(Map<String, Object> flow) { return new FlowValidator(RunnerTest.registry(), null).validate(flow); }
    static String messages(Map<String, Object> flow) { return problems(flow).stream().map(p -> String.valueOf(p.get("message"))).toList().toString(); }

    static String add(String id, String text) {
        return "{\"id\":\"" + id + "\",\"action\":\"core/set\",\"args\":{\"variable\":\"log\",\"value\":\"${log}" + text + "\"}}";
    }
    static String inc(String id) { return "{\"id\":\"" + id + "\",\"action\":\"core/set\",\"args\":{\"variable\":\"n\",\"value\":{\"expr\":\"$n + 1\"}}}"; }

    // ----- subflows -----

    @Test void subflowSharesTheAgentsVariables() {
        RunResult r = run(flow("[" + add("a", "A") + ",{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Login\"}}," + add("b", "B") + "]",
                "[{\"name\":\"Login\",\"steps\":[" + add("l1", "L") + "," + inc("l2") + "]}]"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("ALB", r.outputs().get("log"));
        assertEquals(new BigDecimal("1"), r.outputs().get("n"));
    }

    @Test void subflowsCanCallSubflows() {
        RunResult r = run(flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Outer\"}}]",
                "[{\"name\":\"Outer\",\"steps\":[" + add("o1", "1") + ",{\"id\":\"o2\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Inner\"}}," + add("o3", "3") + "]},"
                + "{\"name\":\"Inner\",\"steps\":[" + add("i1", "2") + "]}]"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("123", r.outputs().get("log"));
    }

    @Test void recursionStopsAtTheDepthLimit() {
        Map<String, Object> f = flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Again\"}}]",
                "[{\"name\":\"Again\",\"steps\":[" + inc("a1") + ",{\"id\":\"a2\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Again\"}}]}]");
        RunResult r = run(f);
        assertFalse(r.ok());
        assertEquals("SUBFLOW.DEPTH", r.error().get("code"));
        assertEquals(new BigDecimal(Runner.MAX_SUBFLOW_DEPTH), r.outputs().get("n"));
    }

    @Test void recursionWithAStopConditionWorks() {
        RunResult r = run(flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Count\"}}]",
                "[{\"name\":\"Count\",\"steps\":[" + inc("a1") + ",{\"id\":\"a2\",\"action\":\"core/if\",\"args\":{\"condition\":\"$n < 5\"},"
                + "\"children\":[{\"id\":\"a3\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Count\"}}]}]}]"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(new BigDecimal("5"), r.outputs().get("n"));
    }

    @Test void anErrorInASubflowReachesTheCallersCatch() {
        RunResult r = run(flow("[{\"id\":\"t\",\"action\":\"core/try\",\"children\":[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Fails\"}}],"
                + "\"catch\":[{\"codes\":[\"BUSINESS.*\"],\"as\":\"err\",\"children\":[" + add("k", "caught") + "]}]}]",
                "[{\"name\":\"Fails\",\"steps\":[" + add("f1", "before;") + ",{\"id\":\"f2\",\"action\":\"core/throw\",\"args\":{\"code\":\"BUSINESS.BAD\",\"message\":\"no\"}}," + add("f3", "never") + "]}]"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("before;caught", r.outputs().get("log"));
        Map<?, ?> err = (Map<?, ?>) r.outputs().get("err");
        assertEquals("f2", err.get("stepId"));
        assertEquals("Fails", err.get("subflow"));
        assertEquals(new BigDecimal("2"), err.get("line"), "the line within the subflow");
    }

    @Test void exitSubflowLeavesOnlyTheSubflowAndRunsFinally() {
        RunResult r = run(flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"S\"}}," + add("after", "after") + "]",
                "[{\"name\":\"S\",\"steps\":[{\"id\":\"t\",\"action\":\"core/try\",\"children\":[{\"id\":\"lp\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"times\",\"count\":3},"
                + "\"children\":[" + add("in", "x") + ",{\"id\":\"e\",\"action\":\"core/exit_subflow\"}]}],\"finally\":[" + add("fin", "F;") + "]}," + add("never", "never") + "]}]"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("xF;after", r.outputs().get("log"));
    }

    @Test void exitSubflowInTheMainStepsIsRefusedAtValidation() {
        assertTrue(messages(flow("[{\"id\":\"e\",\"action\":\"core/exit_subflow\"}]", null)).contains("Exit subflow is only for subflows"));
    }

    @Test void validatorChecksSubflowNamesAndCalls() {
        String m = messages(flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Nope\"}}]",
                "[{\"name\":\"A\",\"steps\":[]},{\"name\":\"A\",\"steps\":[]},{\"name\":\" \",\"steps\":[]}]"));
        assertTrue(m.contains("There is no subflow named 'Nope'"), m);
        assertTrue(m.contains("Duplicate subflow name 'A'"), m);
        assertTrue(m.contains("Subflow 3 has no name"), m);
    }

    @Test void validatorRefusesALoopExitThatWouldLeaveASubflow() {
        String m = messages(flow("[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"times\",\"count\":2},\"children\":[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"S\"}}]}]",
                "[{\"name\":\"S\",\"steps\":[{\"id\":\"b\",\"action\":\"core/break\"}]}]"));
        assertTrue(m.contains("Exit loop is not inside a loop of subflow 'S'"), m);
        // a loop inside the subflow is fine
        assertEquals("[]", messages(flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"S\"}}]",
                "[{\"name\":\"S\",\"steps\":[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"times\",\"count\":2},\"children\":[{\"id\":\"b\",\"action\":\"core/break\"}]}]}]")));
    }

    @Test void aLoopExitThatLeavesASubflowFailsAtRunTime() {
        Map<String, Object> f = flow("[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"times\",\"count\":2},\"children\":[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"S\"}}]}]",
                "[{\"name\":\"S\",\"steps\":[{\"id\":\"b\",\"action\":\"core/break\"}]}]");
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(f, Map.of(), new Runner.RunOptions());
        assertEquals("FLOW.INVALID", r.error().get("code"), "the validator stops it first");
    }

    @Test void subflowStepsAreValidatedLikeMainSteps() {
        String m = messages(flow("[]", "[{\"name\":\"S\",\"steps\":[{\"id\":\"u\",\"action\":\"core/set\",\"args\":{\"variable\":\"missing\",\"value\":1}}]}]"));
        assertTrue(m.contains("Variable 'missing' is not declared"), m);
        String dup = messages(flow("[" + inc("same") + "]", "[{\"name\":\"S\",\"steps\":[" + inc("same") + "]}]"));
        assertTrue(dup.contains("Duplicate step id 'same'"), dup);
    }

    @Test void debuggerStackNamesTheSubflow() {
        List<List<Map<String, Object>>> stacks = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        Map<String, Object> f = flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Outer\"}}]",
                "[{\"name\":\"Outer\",\"steps\":[{\"id\":\"o\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Inner\"}}]},{\"name\":\"Inner\",\"steps\":[" + inc("i") + "]}]");
        RunResult r = run(f, new Runner.RunOptions().debugger(fr -> { if (fr.stepId().equals("i")) { stacks.add(fr.stack()); subs.add(fr.subflow()); } }));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(List.of("Inner"), subs);
        List<Map<String, Object>> st = stacks.get(0);
        assertEquals(List.of("c", "o", "i"), st.stream().map(m -> m.get("stepId")).toList());
        assertNull(st.get(0).get("subflow"));
        assertEquals("Outer", st.get(1).get("subflow"));
        assertEquals("Inner", st.get(2).get("subflow"));
    }

    @Test void runFromHereInsideASubflowRunsTheRestOfIt() {
        Map<String, Object> f = flow("[" + add("m", "main") + ",{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"S\"}}]",
                "[{\"name\":\"S\",\"steps\":[" + add("s1", "1") + "," + add("s2", "2") + "," + add("s3", "3") + "]}]");
        RunResult r = run(f, new Runner.RunOptions().startAt("s2"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("23", r.outputs().get("log"));
        RunResult only = run(f, new Runner.RunOptions().onlyStep("s2"));
        assertEquals("2", only.outputs().get("log"));
    }

    @SuppressWarnings("unchecked")
    @Test void flowsWithoutSubflowsWalkAsBefore() {
        Map<String, Object> f = flow("[" + inc("a") + "]", null);
        assertEquals(Steps.walk((List<Object>) f.get("steps")), Steps.walkFlow(f));
        Map<String, Object> g = flow("[" + inc("a") + "]", "[{\"name\":\"S\",\"steps\":[" + inc("b") + "]}]");
        assertEquals(List.of("a", "b"), Steps.walkFlow(g).stream().map(s -> s.get("id")).toList());
    }

    // ----- labels and go to -----

    static String label(String id, String name) { return "{\"id\":\"" + id + "\",\"action\":\"core/label\",\"args\":{\"name\":\"" + name + "\"}}"; }
    static String go(String id, String label) { return "{\"id\":\"" + id + "\",\"action\":\"core/goto\",\"args\":{\"label\":\"" + label + "\"}}"; }

    @Test void gotoBackwardsMakesALoopWithACounter() {
        RunResult r = run(flow("[" + label("top", "again") + "," + inc("i") + ",{\"id\":\"if\",\"action\":\"core/if\",\"args\":{\"condition\":\"$n < 4\"},\"children\":[" + go("g", "again") + "]}," + add("done", "done") + "]", null));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(new BigDecimal("4"), r.outputs().get("n"));
        assertEquals("done", r.outputs().get("log"));
    }

    @Test void gotoForwardsSkipsSteps() {
        RunResult r = run(flow("[" + add("a", "a") + "," + go("g", "end") + "," + add("b", "b") + "," + label("e", "end") + "," + add("c", "c") + "]", null));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("ac", r.outputs().get("log"));
    }

    @Test void gotoLeavesNestedBlocksForAnEnclosingLabelAndRunsFinally() {
        RunResult r = run(flow("[{\"id\":\"lp\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"times\",\"count\":5},\"children\":["
                + "{\"id\":\"t\",\"action\":\"core/try\",\"children\":[" + add("x", "x") + "," + go("g", "out") + "],\"finally\":[" + add("f", "F") + "]}]},"
                + add("skipped", "never") + "," + label("o", "out") + "," + add("z", "Z") + "]", null));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("xFZ", r.outputs().get("log"));
    }

    @Test void gotoInsideASubflowStaysInIt() {
        RunResult r = run(flow("[{\"id\":\"c\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"S\"}}]",
                "[{\"name\":\"S\",\"steps\":[" + label("l", "back") + "," + inc("i") + ",{\"id\":\"if\",\"action\":\"core/if\",\"args\":{\"condition\":\"$n < 3\"},\"children\":[" + go("g", "back") + "]}]}]"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(new BigDecimal("3"), r.outputs().get("n"));
    }

    @Test void endlessJumpingStopsAtTheMaximum() {
        Map<String, Object> f = flow("[" + label("l", "spin") + "," + inc("i") + "," + go("g", "spin") + "]", null);
        RunResult r = run(f, new Runner.RunOptions().maxJumps(50));
        assertFalse(r.ok());
        assertEquals("GOTO.LIMIT", r.error().get("code"));
        assertEquals(new BigDecimal("51"), r.outputs().get("n"));
        assertEquals("GOTO.LIMIT", run(f).error().get("code"), "the default limit applies too");
    }

    @Test void validatorChecksLabels() {
        String unknown = messages(flow("[" + go("g", "nowhere") + "]", null));
        assertTrue(unknown.contains("There is no label 'nowhere'"), unknown);
        String into = messages(flow("[" + go("g", "inside") + ",{\"id\":\"st\",\"action\":\"core/step\",\"children\":[" + label("l", "inside") + "]}]", null));
        assertTrue(into.contains("would jump into a block"), into);
        String dup = messages(flow("[" + label("a", "x") + "," + label("b", "x") + "]", null));
        assertTrue(dup.contains("Duplicate label 'x'"), dup);
        String shadow = messages(flow("[" + label("a", "x") + ",{\"id\":\"st\",\"action\":\"core/step\",\"children\":[" + label("b", "x") + "]}]", null));
        assertTrue(shadow.contains("a list around it has one too"), shadow);
        String sib = messages(flow("[{\"id\":\"s1\",\"action\":\"core/step\",\"children\":[" + label("a", "x") + "," + go("g1", "x") + "]},"
                + "{\"id\":\"s2\",\"action\":\"core/step\",\"children\":[" + label("b", "x") + "," + go("g2", "x") + "]}]", null));
        assertEquals("[]", sib, "the same name in sibling blocks is fine");
        String outOfSub = messages(flow("[" + label("m", "main") + "]", "[{\"name\":\"S\",\"steps\":[" + go("g", "main") + "]}]"));
        assertTrue(outOfSub.contains("There is no label 'main' in the steps of subflow 'S'"), outOfSub);
        String par = messages(flow("[" + label("m", "top") + ",{\"id\":\"p\",\"action\":\"core/parallel\",\"children\":[{\"id\":\"b\",\"action\":\"core/step\",\"children\":[" + go("g", "top") + "]}]}]", null));
        assertTrue(par.contains("out of a Parallel branch"), par);
    }
}
