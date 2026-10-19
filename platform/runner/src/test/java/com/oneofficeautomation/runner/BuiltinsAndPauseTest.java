package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.PackageRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** $sys and $global built-in variables, reserved names, date comparisons and pause/resume. */
class BuiltinsAndPauseTest {
    static PackageRegistry reg() { return RunnerTest.registry(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String json) { return (Map<String, Object>) JsonLite.parse(json); }

    @Test void systemAndGlobalVariables() throws Exception {
        Path w = Files.createTempDirectory("ooa-b");
        Files.writeString(w.resolve("global-values.json"), "{\"threshold\": 1000, \"team\": \"Finance\"}");
        RunResult r = Runner.builder(reg()).workDir(w).principal("virup").build().run(flow("""
            {"kind":"TaskBot","id":"b","name":"Builtins bot","variables":[
              {"name":"who","type":"String","direction":"output"},{"name":"big","type":"Boolean","direction":"output"},
              {"name":"today","type":"String","direction":"output"},{"name":"team","type":"String","direction":"output"}],
             "steps":[
              {"id":"1","action":"core/set","args":{"variable":"who","value":"${sys.bot_name} by ${sys.principal}"}},
              {"id":"2","action":"core/set","args":{"variable":"big","value":{"expr":"5000 > $global.threshold"}}},
              {"id":"3","action":"core/set","args":{"variable":"today","value":"$sys.date"}},
              {"id":"4","action":"core/set","args":{"variable":"team","value":"$global.team"}}]}"""), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("Builtins bot by virup", r.outputs().get("who"));
        assertEquals(true, r.outputs().get("big"));
        assertEquals(java.time.LocalDate.now().toString(), r.outputs().get("today"));
        assertEquals("Finance", r.outputs().get("team"));
    }

    @Test void builtinsAreReadOnlyAndReserved() {
        FlowValidator v = new FlowValidator(reg(), null);
        List<Map<String, Object>> p = v.validate(flow("""
            {"kind":"TaskBot","id":"b","name":"b","variables":[{"name":"sys","type":"String"}],
             "steps":[{"id":"1","action":"core/log","args":{"message":"$sys.date $global.x"}}]}"""));
        assertTrue(p.stream().anyMatch(x -> String.valueOf(x.get("message")).contains("reserved")), String.valueOf(p));
        assertTrue(p.stream().noneMatch(x -> String.valueOf(x.get("message")).contains("not declared")), "sys and global need no declaration");
        RunResult r = Runner.builder(reg()).build().run(flow("""
            {"kind":"TaskBot","id":"b","name":"b","steps":[{"id":"1","action":"core/set","args":{"variable":"sys","value":1}}]}"""), Map.of());
        assertEquals("FLOW.INVALID", r.error().get("code"));
    }

    @Test void dateTimeComparesWithText() {
        RunResult r = Runner.builder(reg()).build().run(flow("""
            {"kind":"TaskBot","id":"b","name":"b","variables":[{"name":"d","type":"DateTime","default":"2026-10-19T10:00:00"},{"name":"late","type":"Boolean","direction":"output","default":false}],
             "steps":[{"id":"1","action":"core/if","args":{"condition":{"op":"gt","left":"$d","right":"2026-10-01T00:00:00"}},
               "children":[{"id":"2","action":"core/set","args":{"variable":"late","value":true}}],"else":[]}]}"""), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(true, r.outputs().get("late"));
    }

    @Test void pauseAndResume() throws Exception {
        AtomicBoolean pause = new AtomicBoolean(true);
        EventSink.ListSink sink = new EventSink.ListSink();
        Map<String, Object> f = flow("""
            {"kind":"TaskBot","id":"b","name":"b","variables":[{"name":"n","type":"Number","direction":"output","default":0}],
             "steps":[{"id":"1","action":"core/set","args":{"variable":"n","value":1}},{"id":"2","action":"core/set","args":{"variable":"n","value":2}}]}""");
        RunResult[] out = new RunResult[1];
        Thread t = new Thread(() -> out[0] = Runner.builder(reg()).sink(sink).build().run(f, Map.of(), new Runner.RunOptions().pause(pause)));
        t.start();
        Thread.sleep(600);
        assertNull(out[0], "run waits while paused");
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("run.paused")));
        pause.set(false);
        t.join(5000);
        assertTrue(out[0].ok());
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("run.resumed")));
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("run.started") && Integer.valueOf(2).equals(((Number) e.data().get("totalSteps")).intValue())));
    }
}
