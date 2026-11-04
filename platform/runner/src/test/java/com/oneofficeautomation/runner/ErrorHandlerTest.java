package com.oneofficeautomation.runner;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.oneofficeautomation.runner.CoreOptionsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The agent's error handler (flow "onError"): runs once for an error no Try block caught, then fails or succeeds the run. */
class ErrorHandlerTest {

    /** A flow whose main steps are `steps`, with an error handler that notes what it saw in the output "seen". */
    static String agent(String steps, String then, String handlerSteps) {
        return """
            {"kind":"TaskBot","id":"eh","name":"eh",
             "variables":[{"name":"err","type":"Dictionary"},{"name":"seen","type":"String","direction":"output","default":"no"},
                          {"name":"after","type":"String","direction":"output","default":"no"}],
             "steps":%s,
             "onError":{"as":"err","then":"%s","steps":%s}}""".formatted(steps, then, handlerSteps);
    }

    static final String NOTE = """
        [{"id":"h1","action":"core/set","args":{"variable":"seen","value":"${err.code}|${err.stepId}|${err.action}|${err.line}|${err.message}"}}]""";

    static final String THROWS = """
        [{"id":"a","action":"core/set","args":{"variable":"after","value":"started"}},
         {"id":"x","action":"core/throw","args":{"code":"BUSINESS.BAD","message":"no invoice"}},
         {"id":"never","action":"core/set","args":{"variable":"after","value":"bad"}}]""";

    static boolean has(EventSink.ListSink sink, String type) { return sink.events().stream().anyMatch(e -> e.type().equals(type)); }

    @Test @SuppressWarnings("unchecked") void handlerRunsWithTheErrorThenFailsWithTheOriginalError() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow(agent(THROWS, "fail", NOTE)), Map.of());
        assertEquals("failed", r.status());
        assertEquals("BUSINESS.BAD", r.error().get("code"), "Control Room and parents see the real cause");
        assertEquals("x", r.error().get("stepId"));
        assertEquals("BUSINESS.BAD|x|core/throw|2|no invoice", r.outputs().get("seen"));
        assertEquals("started", r.outputs().get("after"), "the main steps stop at the error");
        assertTrue(has(sink, "error_handler.started"));
        RunEvent done = sink.ofType("error_handler.finished").get(0);
        assertEquals("done", done.data().get("status"));
        // the handler's steps run between the error and the end of the run
        List<String> types = sink.events().stream().map(RunEvent::type).toList();
        assertTrue(types.indexOf("error_handler.started") < types.lastIndexOf("run.finished"));
        RunEvent finished = sink.ofType("run.finished").get(0);
        assertNull(finished.data().get("handledError"));
        // the error variable carries the call stack
        RunResult stack = run("""
            {"kind":"TaskBot","id":"eh","name":"eh","variables":[{"name":"err","type":"Dictionary"},{"name":"st","type":"List","direction":"output"}],
             "steps":[{"id":"x","action":"core/throw","args":{"code":"A.B","message":"m"}}],
             "onError":{"as":"err","steps":[{"id":"h","action":"core/set","args":{"variable":"st","value":{"expr":"$err.stack"}}}]}}""");
        List<Object> st = (List<Object>) stack.outputs().get("st");
        assertEquals(1, st.size());
        assertEquals("x", ((Map<String, Object>) st.get(0)).get("stepId"));
        assertEquals("eh", ((Map<String, Object>) st.get(0)).get("automation"));
        assertEquals("failed", stack.status(), "then defaults to fail");
    }

    @Test void thenSucceedEndsTheRunSucceededWithAWarning() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow(agent(THROWS, "succeed", NOTE)), Map.of());
        ok(r);
        assertNull(r.error());
        assertTrue(String.valueOf(r.outputs().get("seen")).startsWith("BUSINESS.BAD|x"));
        assertTrue(sink.ofType("log").stream().anyMatch(e -> "warning".equals(e.data().get("level")) && String.valueOf(e.data().get("message")).contains("ends as succeeded")));
        @SuppressWarnings("unchecked") Map<String, Object> handled = (Map<String, Object>) sink.ofType("run.finished").get(0).data().get("handledError");
        assertEquals("BUSINESS.BAD", handled.get("code"));
    }

    @Test void anErrorInsideTheHandlerIsLoggedAndTheRunFailsWithTheOriginalError() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow(agent(THROWS, "succeed", """
            [{"id":"h1","action":"core/set","args":{"variable":"seen","value":"handler ran"}},
             {"id":"h2","action":"core/throw","args":{"code":"MAIL.DOWN","message":"no mail server"}},
             {"id":"h3","action":"core/set","args":{"variable":"seen","value":"bad"}}]""")), Map.of());
        assertEquals("failed", r.status(), "a failed handler never turns the run into a success");
        assertEquals("BUSINESS.BAD", r.error().get("code"));
        assertEquals("handler ran", r.outputs().get("seen"));
        assertTrue(sink.ofType("log").stream().anyMatch(e -> "error".equals(e.data().get("level")) && String.valueOf(e.data().get("message")).contains("MAIL.DOWN")));
        assertEquals("failed", sink.ofType("error_handler.finished").get(0).data().get("status"));
        assertEquals(1, sink.ofType("error_handler.started").size(), "the handler does not handle its own errors");
    }

    @Test void caughtAndSkippedErrorsDoNotStartTheHandler() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow(agent("""
            [{"id":"t","action":"core/try","children":[{"id":"x","action":"core/throw","args":{"code":"A.B","message":"m"}}],"catch":[{"children":[]}]},
             {"id":"y","action":"core/throw","args":{"code":"A.C","message":"m"},"continueOnError":true},
             {"id":"a","action":"core/set","args":{"variable":"after","value":"done"}}]""", "fail", NOTE)), Map.of());
        ok(r);
        assertEquals("no", r.outputs().get("seen"));
        assertEquals("done", r.outputs().get("after"));
        assertFalse(has(sink, "error_handler.started"));
    }

    @Test void stopStepsAndStoppingTheRunDoNotStartTheHandler() {
        RunResult stopped = run(agent("""
            [{"id":"s","action":"core/stop","args":{"status":"failed","message":"bad input"}}]""", "succeed", NOTE));
        assertEquals("AGENT.STOPPED", stopped.error().get("code"));
        assertEquals("no", stopped.outputs().get("seen"));
        AtomicBoolean cancel = new AtomicBoolean();
        new Thread(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) { } cancel.set(true); }).start();
        RunResult cancelled = Runner.builder(registry()).build().run(flow(agent("""
            [{"id":"d","action":"core/delay","args":{"seconds":5}}]""", "succeed", NOTE)), Map.of(), new Runner.RunOptions().cancel(cancel));
        assertEquals("stopped", cancelled.status());
        assertEquals("no", cancelled.outputs().get("seen"));
    }

    @Test void theRunTimeLimitStartsTheHandlerWithExtraTime() {
        RunResult r = Runner.builder(registry()).build().run(flow(agent("""
            [{"id":"d","action":"core/delay","args":{"seconds":3}}]""", "fail", NOTE)), Map.of(), new Runner.RunOptions().maxRunSeconds(0.2));
        assertEquals("RUN.TIMEOUT", r.error().get("code"));
        assertTrue(String.valueOf(r.outputs().get("seen")).startsWith("RUN.TIMEOUT|d"), String.valueOf(r.outputs().get("seen")));
    }

    @Test void sessionsAreStillOpenWhileTheHandlerRuns() {
        RunResult r = run(agent("""
            [{"id":"o","action":"acme.sessions/open","args":{"name":"page","text":"order form"}},
             {"id":"x","action":"core/throw","args":{"code":"UI.NOT_FOUND","message":"no button"}}]""", "fail", """
            [{"id":"h1","action":"acme.sessions/read","args":{"name":"page"},"out":{"text":"seen"}}]"""));
        assertEquals("UI.NOT_FOUND", r.error().get("code"));
        assertEquals("order form", r.outputs().get("seen"));
    }

    @Test void aChildAgentHasItsOwnHandlerAndTheParentsSeesOnlyWhatEscapes() {
        String handledChild = """
            {"kind":"TaskBot","id":"kid","name":"kid","steps":[{"id":"k1","action":"core/throw","args":{"code":"KID.BAD","message":"oops"}}],
             "onError":{"then":"succeed","steps":[]}}""";
        String failingChild = """
            {"kind":"TaskBot","id":"bad","name":"bad","steps":[{"id":"k1","action":"core/throw","args":{"code":"KID.BAD","message":"oops"}}]}""";
        Runner runner = withChildren(Map.of("kid", handledChild, "bad", failingChild));
        RunResult r = run(runner, agent("""
            [{"id":"r","action":"core/run_bot","args":{"automation":"kid"}},{"id":"a","action":"core/set","args":{"variable":"after","value":"done"}}]""", "fail", NOTE));
        ok(r);
        assertEquals("no", r.outputs().get("seen"), "the child handled its own error");
        assertEquals("done", r.outputs().get("after"));
        RunResult p = run(runner, agent("""
            [{"id":"r","action":"core/run_bot","args":{"automation":"bad"}}]""", "fail", NOTE));
        assertEquals("KID.BAD", p.error().get("code"));
        assertTrue(String.valueOf(p.outputs().get("seen")).startsWith("KID.BAD|r|core/run_bot"), String.valueOf(p.outputs().get("seen")));
    }

    @Test void flowsWithoutAHandlerFailAsBefore() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"x","action":"core/throw","args":{"code":"A.B","message":"m"}}]}""");
        assertEquals("A.B", r.error().get("code"));
    }

    @Test void validatorChecksTheHandler() {
        FlowValidator v = new FlowValidator(registry(), null);
        assertTrue(v.validate(flow(agent(THROWS, "fail", NOTE))).isEmpty(), String.valueOf(v.validate(flow(agent(THROWS, "fail", NOTE)))));
        java.util.function.Function<String, List<String>> problems = json -> v.validate(flow(json)).stream().map(p -> p.get("path") + ": " + p.get("message")).toList();
        String base = """
            {"kind":"TaskBot","id":"p","name":"p","variables":[{"name":"txt","type":"String"},{"name":"err","type":"Dictionary"}],
             "steps":[{"id":"x","action":"core/log","args":{"message":"hi"}}],"onError":%s}""";
        assertTrue(problems.apply(base.formatted("{\"as\":\"nope\",\"steps\":[]}")).stream().anyMatch(s -> s.startsWith("onError/as") && s.contains("not declared")));
        assertTrue(problems.apply(base.formatted("{\"as\":\"txt\",\"steps\":[]}")).stream().anyMatch(s -> s.startsWith("onError/as") && s.contains("Dictionary")));
        assertTrue(problems.apply(base.formatted("{\"as\":\"sys\",\"steps\":[]}")).stream().anyMatch(s -> s.startsWith("onError/as")));
        assertTrue(problems.apply(base.formatted("{\"then\":\"retry\",\"steps\":[]}")).stream().anyMatch(s -> s.startsWith("onError/then")));
        assertTrue(problems.apply(base.formatted("{\"steps\":[{\"id\":\"x\",\"action\":\"core/log\",\"args\":{\"message\":\"again\"}}]}")).stream().anyMatch(s -> s.contains("Duplicate step id 'x'")));
        assertTrue(problems.apply(base.formatted("{\"steps\":[{\"id\":\"h\",\"action\":\"core/set\",\"args\":{\"variable\":\"missing\",\"value\":1}}]}")).stream().anyMatch(s -> s.contains("missing")));
        assertTrue(problems.apply(base.formatted("{\"steps\":[{\"id\":\"h\",\"action\":\"core/log\",\"args\":{\"message\":\"$nope\"}}]}")).stream().anyMatch(s -> s.contains("$nope")));
        assertTrue(problems.apply(base.formatted("[]")).stream().anyMatch(s -> s.startsWith("onError")));
        assertEquals(List.of(), problems.apply(base.formatted("{\"as\":\"err\",\"then\":\"succeed\",\"steps\":[{\"id\":\"h\",\"action\":\"core/log\",\"args\":{\"message\":\"${err.message}\"}}]}")));
    }
}
