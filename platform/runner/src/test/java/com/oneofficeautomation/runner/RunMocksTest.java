package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G61: a test case's mocks stand in for toolkit steps (the ones that reach other systems); they arrive in the reserved
 * input and never reach the agent. Core's own steps are not mocked.
 */
class RunMocksTest {
    @SuppressWarnings("unchecked")
    static Map<String, Object> flow() {
        return (Map<String, Object>) JsonLite.parse("""
            {"kind": "TaskBot", "id": "m", "name": "m",
             "variables": [{"name": "code", "type": "String", "direction": "input"}, {"name": "text", "type": "String", "direction": "output"}, {"name": "note", "type": "String", "direction": "output"}],
             "steps": [
               {"id": "fetch", "action": "acme.sessions/read", "args": {"name": "erp"}, "out": {"text": "text"}},
               {"id": "done", "action": "core/set", "args": {"variable": "note", "value": "${code}:${text}"}}]}""");
    }

    @Test void mockedStepsGiveTheirOutputsOrErrors() {
        Runner runner = Runner.builder(CoreOptionsTest.registry()).build();
        RunResult real = runner.run(flow(), Map.of("code", "A"));
        assertEquals("failed", real.status(), "without a mock the step really runs (and there is no session)");

        RunResult ok = runner.run(flow(), new HashMap<>(Map.of("code", "B", Runner.MOCKS_INPUT, Map.of("fetch", Map.of("outputs", Map.of("text", "mocked ERP answer"))))));
        assertTrue(ok.ok(), String.valueOf(ok.error()));
        assertEquals("B:mocked ERP answer", ok.outputs().get("note"), "the mocked outputs map to variables; the reserved input never reaches the agent");

        RunResult failed = runner.run(flow(), new HashMap<>(Map.of("code", "C", Runner.MOCKS_INPUT, Map.of("fetch", Map.of("error", Map.of("code", "ERP.DOWN", "message", "The ERP is down"))))));
        assertEquals("failed", failed.status());
        assertEquals("ERP.DOWN", failed.error().get("code"), "the mocked error is the step's error");

        RunResult opt = runner.run(flow(), Map.of("code", "D"), new Runner.RunOptions().mocks(Map.of("fetch", Map.of("outputs", Map.of("text", "x")))));
        assertEquals("D:x", opt.outputs().get("note"), "Studio can pass mocks as a run option too");
    }
}
