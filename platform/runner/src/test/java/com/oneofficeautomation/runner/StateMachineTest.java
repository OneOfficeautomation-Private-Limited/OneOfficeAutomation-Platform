package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** core/state_machine: named states with entry steps, transitions tried in order, final states; limits, errors, run from here. */
class StateMachineTest {
    private static final String VARS = """
        "variables":[{"name":"n","type":"Number","direction":"output","default":0},{"name":"log","type":"String","direction":"output","default":""},
                     {"name":"err","type":"Dictionary","direction":"output"},{"name":"amount","type":"Number","default":500},{"name":"item","type":"Any"}]""";

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String steps, String onError) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"sm\",\"name\":\"sm\"," + VARS + ",\"steps\":" + steps
                + (onError == null ? "" : ",\"onError\":" + onError) + "}");
    }

    static RunResult run(Map<String, Object> flow) { return run(flow, new Runner.RunOptions()); }
    static RunResult run(Map<String, Object> flow, Runner.RunOptions o) { return Runner.builder(RunnerTest.registry()).build().run(flow, Map.of(), o); }
    static List<String> messages(Map<String, Object> flow) {
        return new FlowValidator(RunnerTest.registry(), null).validate(flow).stream().map(p -> String.valueOf(p.get("message"))).toList();
    }

    static String add(String id, String text) {
        return "{\"id\":\"" + id + "\",\"action\":\"core/set\",\"args\":{\"variable\":\"log\",\"value\":\"${log}" + text + "\"}}";
    }

    /** The invoice example: Waiting goes to Approved (amount up to 1000) or Rejected; Approved goes on to Paid. */
    static String invoice(String args) {
        return "[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"args\":" + args + ",\"states\":["
                + "{\"name\":\"Waiting\",\"entry\":[" + add("w1", "W;") + "],\"transitions\":["
                + "{\"to\":\"Approved\",\"when\":\"$amount <= 1000\",\"steps\":[" + add("t1", "ok;") + "]},"
                + "{\"to\":\"Rejected\",\"steps\":[" + add("t2", "no;") + "]}]},"
                + "{\"name\":\"Approved\",\"entry\":[" + add("a1", "A;") + "],\"transitions\":[{\"to\":\"Paid\"}]},"
                + "{\"name\":\"Rejected\",\"final\":true,\"entry\":[" + add("r1", "R;") + "]},"
                + "{\"name\":\"Paid\",\"final\":true,\"entry\":[" + add("p1", "P;") + "]}]}]";
    }

    @Test void startsInTheFirstStateAndTakesTheFirstTrueTransition() {
        RunResult r = run(flow(invoice("{}"), null));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("W;ok;A;P;", r.outputs().get("log"));
    }

    @Test void theTransitionWithoutAConditionIsTheDefault() {
        Map<String, Object> f = flow(invoice("{}"), null);
        ((List<Map<String, Object>>) cast(f.get("variables"))).get(3).put("default", 5000);
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("W;no;R;", r.outputs().get("log"), "the final state Rejected ends the step");
    }

    @Test void initialNamesTheStartState() {
        RunResult r = run(flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"args\":{\"initial\":\"B\"},\"states\":["
                + "{\"name\":\"A\",\"entry\":[" + add("a", "A;") + "],\"transitions\":[{\"to\":\"C\"}]},"
                + "{\"name\":\"B\",\"entry\":[" + add("b", "B;") + "],\"transitions\":[{\"to\":\"A\"}]},"
                + "{\"name\":\"C\",\"final\":true,\"entry\":[" + add("c", "C;") + "]}]}]", null));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("B;A;C;", r.outputs().get("log"));
    }

    @Test void theLogSaysWhichStatesWereEnteredAndWhichTransitionsTaken() {
        EventSink.ListSink sink = new EventSink.ListSink();
        Runner.builder(RunnerTest.registry()).sink(sink).build().run(flow(invoice("{}"), null), Map.of());
        List<String> logs = sink.events().stream().filter(e -> e.type().equals("log")).map(e -> String.valueOf(e.data().get("message")))
                .filter(m -> m.startsWith("State machine:")).toList();
        assertEquals(List.of("State machine: entered state 'Waiting'", "State machine: from 'Waiting' to 'Approved' (transition 1)",
                "State machine: entered state 'Approved'", "State machine: from 'Approved' to 'Paid' (transition 1)",
                "State machine: entered state 'Paid'", "State machine: ended in final state 'Paid'"), logs);
    }

    @Test void noTrueTransitionIsAnError() {
        Map<String, Object> f = flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":["
                + "{\"name\":\"A\",\"transitions\":[{\"to\":\"B\",\"when\":\"$amount > 9999\"}]},{\"name\":\"B\",\"final\":true}]}]", null);
        RunResult r = run(f);
        assertFalse(r.ok());
        assertEquals("STATE.NO_TRANSITION", r.error().get("code"));
        assertTrue(String.valueOf(r.error().get("message")).contains("'A'"));
    }

    @Test void tooManyTransitionsStopAtTheLimit() {
        Map<String, Object> f = flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"args\":{\"max_transitions\":5},\"states\":["
                + "{\"name\":\"Ping\",\"entry\":[{\"id\":\"i\",\"action\":\"core/set\",\"args\":{\"variable\":\"n\",\"value\":{\"expr\":\"$n + 1\"}}}],"
                + "\"transitions\":[{\"to\":\"Done\",\"when\":\"$n > 100\"},{\"to\":\"Ping\"}]},{\"name\":\"Done\",\"final\":true}]}]", null);
        RunResult r = run(f);
        assertEquals("STATE.LIMIT", r.error().get("code"));
        assertEquals(new BigDecimal("6"), r.outputs().get("n"), "the state was entered 6 times: 5 transitions allowed");
    }

    @Test void breakAndContinueInsideAStateReachTheLoopAround() {
        Map<String, Object> f = flow("[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"list\",\"over\":[\"a\",\"skip\",\"b\",\"stop\",\"c\"],\"as\":\"item\"},\"children\":["
                + "{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":[{\"name\":\"Look\",\"entry\":["
                + "{\"id\":\"c1\",\"action\":\"core/if\",\"args\":{\"condition\":\"$item == 'skip'\"},\"children\":[{\"id\":\"c2\",\"action\":\"core/continue\"}]},"
                + "{\"id\":\"b1\",\"action\":\"core/if\",\"args\":{\"condition\":\"$item == 'stop'\"},\"children\":[{\"id\":\"b2\",\"action\":\"core/break\"}]}],"
                + "\"transitions\":[{\"to\":\"Keep\"}]},{\"name\":\"Keep\",\"final\":true,\"entry\":[" + add("k", "${item}") + "]}]}]}]", null);
        assertTrue(messages(f).isEmpty(), messages(f).toString());
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("ab", r.outputs().get("log"));
    }

    @Test void anErrorInAStateReachesTheOuterTryWithItsLine() {
        Map<String, Object> f = flow("[{\"id\":\"t\",\"action\":\"core/try\",\"children\":[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":["
                + "{\"name\":\"A\",\"entry\":[" + add("a1", "a;") + "],\"transitions\":[{\"to\":\"B\",\"steps\":[{\"id\":\"boom\",\"action\":\"core/throw\",\"args\":{\"code\":\"BUSINESS.BAD\",\"message\":\"no\"}}]}]},"
                + "{\"name\":\"B\",\"final\":true,\"entry\":[" + add("b1", "never") + "]}]}],"
                + "\"catch\":[{\"as\":\"err\",\"children\":[" + add("k", "caught") + "]}]}]", null);
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("a;caught", r.outputs().get("log"));
        Map<?, ?> err = (Map<?, ?>) r.outputs().get("err");
        assertEquals("boom", err.get("stepId"));
        assertEquals(new BigDecimal("4"), err.get("line"), "Try 1, State machine 2, entry a1 3, transition step boom 4");
    }

    @Test void anErrorInAStateReachesTheAgentsErrorHandler() {
        Map<String, Object> f = flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":["
                + "{\"name\":\"A\",\"entry\":[{\"id\":\"boom\",\"action\":\"core/throw\",\"args\":{\"code\":\"BUSINESS.BAD\",\"message\":\"no\"}}],\"final\":true}]}]",
                "{\"as\":\"err\",\"then\":\"succeed\",\"steps\":[" + add("h", "handled") + "]}");
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("handled", r.outputs().get("log"));
        assertEquals("BUSINESS.BAD", ((Map<?, ?>) r.outputs().get("err")).get("code"));
    }

    @Test void runFromHereStartsInTheStateThatHoldsTheStep() {
        Map<String, Object> f = flow(invoice("{}"), null);
        RunResult r = run(f, new Runner.RunOptions().startAt("a1"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("A;P;", r.outputs().get("log"), "Approved's entry from a1, then on to Paid");
        RunResult t = run(f, new Runner.RunOptions().startAt("t2"));
        assertTrue(t.ok(), String.valueOf(t.error()));
        assertEquals("no;R;", t.outputs().get("log"), "a transition's steps from t2, then its target state");
        RunResult only = run(f, new Runner.RunOptions().onlyStep("a1"));
        assertEquals("A;", only.outputs().get("log"), "only the one step");
    }

    @Test void gotoCannotLeaveAStateMachine() {
        Map<String, Object> f = flow("[{\"id\":\"l\",\"action\":\"core/label\",\"args\":{\"name\":\"top\"}},{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":["
                + "{\"name\":\"A\",\"final\":true,\"entry\":[{\"id\":\"g\",\"action\":\"core/goto\",\"args\":{\"label\":\"top\"}}]}]}]", null);
        assertTrue(messages(f).stream().anyMatch(m -> m.contains("cannot be reached from here")), messages(f).toString());
        assertEquals("FLOW.INVALID", run(f).error().get("code"), "the validator stops it before the run");
        // a Go to inside one state list to a label in the same list is fine
        Map<String, Object> ok = flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":[{\"name\":\"A\",\"final\":true,\"entry\":["
                + "{\"id\":\"g\",\"action\":\"core/goto\",\"args\":{\"label\":\"end\"}}," + add("x", "skipped") + ",{\"id\":\"l\",\"action\":\"core/label\",\"args\":{\"name\":\"end\"}}," + add("y", "end") + "]}]}]", null);
        RunResult r = run(ok);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("end", r.outputs().get("log"));
    }

    @Test void validator() {
        assertEquals(List.of(), messages(flow(invoice("{\"initial\":\"Waiting\"}"), null)));
        String bad = "[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"args\":{\"initial\":\"Nowhere\"},\"states\":[{\"name\":\"A\",\"final\":true}]}]";
        assertTrue(messages(flow(bad, null)).contains("There is no state named 'Nowhere' to start in"));
        List<String> m = messages(flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":["
                + "{\"name\":\"A\",\"transitions\":[{\"to\":\"B\"},{\"to\":\"Q\",\"when\":\"$ghost > 1\"}]},"
                + "{\"name\":\"A\",\"final\":true},{\"name\":\"B\",\"final\":true,\"transitions\":[{\"to\":\"A\"}]},"
                + "{\"name\":\"C\",\"transitions\":[]},{\"name\":\"D\",\"final\":true}]}]", null));
        for (String want : List.of("Duplicate state name 'A'", "Transition 2 of state 'A' leads to 'Q', which is not a state",
                "Transition 1 of state 'A' has no condition, so the transitions after it are never tried", "Variable '$ghost' is not declared",
                "Final state 'B' has transitions", "State 'C' is not final and has no transitions", "State 'D' cannot be reached from the start state 'A'"))
            assertTrue(m.contains(want), want + " in " + m);
        assertTrue(messages(flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":[{\"name\":\"A\",\"transitions\":[{\"to\":\"A\"}]}]}]", null))
                .contains("The state machine has no final state"));
        assertTrue(messages(flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":[{\"name\":\"A\",\"transitions\":[{\"to\":\"A\"}]},{\"name\":\"Z\",\"final\":true}]}]", null))
                .contains("No final state can be reached from the start state 'A'"));
        assertTrue(messages(flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":[]}]", null)).contains("The state machine needs at least one state"));
        assertTrue(messages(flow("[{\"id\":\"x\",\"action\":\"core/if\",\"args\":{\"condition\":true},\"states\":[{\"name\":\"A\",\"final\":true}]}]", null))
                .contains("states are only allowed on core/state_machine"));
        // steps inside states are checked like any other step
        assertTrue(messages(flow("[{\"id\":\"sm\",\"action\":\"core/state_machine\",\"states\":[{\"name\":\"A\",\"final\":true,\"entry\":["
                + "{\"id\":\"sm\",\"action\":\"core/set\",\"args\":{\"variable\":\"nope\",\"value\":1}}]}]}]", null))
                .containsAll(List.of("Duplicate step id 'sm'", "Variable 'nope' is not declared")));
    }

    @Test void stepsInsideStatesAreWalkedInStudioOrder() {
        List<String> ids = Steps.walk(cast(flow(invoice("{}"), null).get("steps"))).stream().map(s -> String.valueOf(s.get("id"))).toList();
        assertEquals(List.of("sm", "w1", "t1", "t2", "a1", "r1", "p1"), ids);
    }

    @SuppressWarnings("unchecked")
    static <T> T cast(Object o) { return (T) o; }
}
