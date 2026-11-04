package com.oneofficeautomation.runner;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.runner.CoreOptionsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The core steps added on separate branches, used together (gaps G25 Switch, G26 Assign several, G27 subflows and Go
 * to, G28 On error): what one of them does inside another.
 */
class CoreStepsTogetherTest {

    static final String VARS = """
        [{"name":"err","type":"Dictionary"},{"name":"n","type":"Number","direction":"output","default":0},
         {"name":"seen","type":"String","direction":"output","default":""},{"name":"status","type":"String","default":"open"}]""";

    static String agent(String steps, String subflows, String onError) {
        return """
            {"kind":"TaskBot","id":"t","name":"t","variables":%s,"steps":%s%s%s}"""
                .formatted(VARS, steps, subflows == null ? "" : ",\"subflows\":" + subflows, onError == null ? "" : ",\"onError\":" + onError);
    }

    static final String THROW = """
        {"id":"x","action":"core/throw","args":{"code":"BUSINESS.BAD","message":"no order"}}""";

    @Test void onErrorStepsCanRunASubflowAndAssignSeveralVariables() {
        RunResult r = run(agent("[" + THROW + "]",
                """
                [{"name":"Note","steps":[{"id":"n1","action":"core/set_many","args":{"assignments":[
                    {"variable":"n","value":{"expr":"$n + 1"}},{"variable":"seen","value":"noted ${err.code} after ${n}"}]}}]}]""",
                """
                {"as":"err","then":"succeed","steps":[{"id":"h1","action":"core/run_subflow","args":{"subflow":"Note"}}]}"""));
        assertEquals("success", r.status(), String.valueOf(r.error()));
        assertEquals("noted BUSINESS.BAD after 1", r.outputs().get("seen"));
    }

    @Test void anErrorInASubflowReachesOnErrorWithItsLineInTheSubflow() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow(agent(
                """
                [{"id":"m1","action":"core/log","args":{"message":"go"}},{"id":"m2","action":"core/run_subflow","args":{"subflow":"Work"}}]""",
                "[{\"name\":\"Work\",\"steps\":[{\"id\":\"w1\",\"action\":\"core/log\",\"args\":{\"message\":\"in\"}}," + THROW + "]}]",
                """
                {"as":"err","steps":[{"id":"h1","action":"core/set","args":{"variable":"seen","value":"${err.stepId} line ${err.line} of ${err.subflow}"}}]}""")),
                Map.of());
        assertEquals("failed", r.status());
        assertEquals("BUSINESS.BAD", r.error().get("code"), "the original error, as without subflows");
        assertEquals("x line 2 of Work", r.outputs().get("seen"));
        // Run started counts the main steps and the subflow's, not On error's
        assertEquals(new BigDecimal(4), new BigDecimal(String.valueOf(sink.ofType("run.started").get(0).data().get("totalSteps"))));
    }

    @Test void goToStaysInsideOnError() {
        // inside the handler's own steps it works (a small retry)
        RunResult loop = run(agent("[" + THROW + "]", null, """
            {"as":"err","steps":[{"id":"l","action":"core/label","args":{"name":"again"}},
              {"id":"h1","action":"core/set","args":{"variable":"n","value":{"expr":"$n + 1"}}},
              {"id":"h2","action":"core/if","args":{"condition":"$n < 3"},"children":[{"id":"h3","action":"core/goto","args":{"label":"again"}}]}]}"""));
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) loop.outputs().get("n")));
        // a label of the main steps is out of reach: refused before the run
        RunResult out = run(agent("[{\"id\":\"m\",\"action\":\"core/label\",\"args\":{\"name\":\"top\"}}," + THROW + "]", null, """
            {"as":"err","steps":[{"id":"h1","action":"core/goto","args":{"label":"top"}}]}"""));
        assertEquals("FLOW.INVALID", out.error().get("code"));
        assertTrue(String.valueOf(out.error()).contains("top"), String.valueOf(out.error()));
        // so is Exit subflow
        RunResult exit = run(agent("[" + THROW + "]", null, """
            {"as":"err","steps":[{"id":"h1","action":"core/exit_subflow"}]}"""));
        assertEquals("FLOW.INVALID", exit.error().get("code"));
    }

    @Test void switchInASubflowWithGoToInsideACase() {
        RunResult r = run(agent("[{\"id\":\"m\",\"action\":\"core/run_subflow\",\"args\":{\"subflow\":\"Route\"}}]", """
            [{"name":"Route","steps":[
              {"id":"l","action":"core/label","args":{"name":"top"}},
              {"id":"c","action":"core/set","args":{"variable":"n","value":{"expr":"$n + 1"}}},
              {"id":"s","action":"core/switch","args":{"value":"$status"},
               "cases":[{"values":["open"],"children":[{"id":"o","action":"core/set","args":{"variable":"status","value":"done"}},
                                                        {"id":"g","action":"core/goto","args":{"label":"top"}}]}],
               "default":[{"id":"d","action":"core/set","args":{"variable":"seen","value":"finished as ${status}"}}]}]}]""", null));
        assertEquals("success", r.status(), String.valueOf(r.error()));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) r.outputs().get("n")), "Go to inside a case jumped back once");
        assertEquals("finished as done", r.outputs().get("seen"));
    }

    @Test void goToFromASwitchCaseIntoAnotherCaseIsRefused() {
        RunResult r = run(agent("""
            [{"id":"s","action":"core/switch","args":{"value":"$status"},
              "cases":[{"values":["open"],"children":[{"id":"g","action":"core/goto","args":{"label":"inside"}}]},
                       {"values":["done"],"children":[{"id":"l","action":"core/label","args":{"name":"inside"}}]}]}]""", null, null));
        assertEquals("FLOW.INVALID", r.error().get("code"));
        assertTrue(String.valueOf(r.error()).contains("cannot be reached"), String.valueOf(r.error()));
    }
}
