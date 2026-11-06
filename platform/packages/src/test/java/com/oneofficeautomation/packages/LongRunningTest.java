package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.packages.PackagesTest.REG;
import static org.junit.jupiter.api.Assertions.*;

/** G36: suspend and resume in the runner, with a stand-in for One CommandCenter. */
class LongRunningTest {
    @TempDir Path dir;
    final List<Map<String, Object>> asked = new ArrayList<>();

    /** One CommandCenter as the runner sees it: remembers every request and answers like the real one. */
    @SuppressWarnings("unchecked")
    final Function<Map<String, Object>, Map<String, Object>> cc = req -> {
        asked.add(req);
        return switch (String.valueOf(req.get("call"))) {
            case "create_callback" -> Map.of("url", "https://cc.example/api/callbacks/tok", "callback_id", "wait_cb");
            case "create_task" -> Map.of("task_id", "task_1");
            default -> Map.of("wait_id", "wait_1", "kind", ((Map<String, Object>) req.get("args")).get("kind"));
        };
    };

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String steps) {
        return (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"lr","name":"lr","variables":[
              {"name":"a","type":"String","direction":"output","default":"-"},{"name":"after","type":"String","direction":"output","default":"-"},
              {"name":"fin","type":"String","direction":"output","default":"-"},{"name":"caught","type":"String","direction":"output","default":"-"},
              {"name":"n","type":"Number","direction":"output","default":0},{"name":"secret","type":"Credential"},
              {"name":"by","type":"String","direction":"output","default":""},{"name":"res","type":"Dictionary","direction":"output"},
              {"name":"late","type":"Boolean","direction":"output","default":false}],
             "steps":%s}""".formatted(steps));
    }

    static final String WAIT = """
        {"id":"w","action":"ooa.longrunning/wait_for_callback","args":{"callback_id":"wait_cb","on_timeout":"continue"},"out":{"resumed_by":"by","result":"res","timed_out":"late"}}""";

    RunResult run(Map<String, Object> f, Runner.RunOptions o, boolean withCc) {
        Runner.Builder b = Runner.builder(REG).workDir(dir);
        if (withCc) b.service("command_center", cc);
        return b.build().run(f, Map.of(), o);
    }

    @Test @SuppressWarnings("unchecked")
    void aRunSuspendsWithItsValuesAndAResumedRunCarriesOnFromTheStep() {
        Map<String, Object> f = flow("[{\"id\":\"s1\",\"action\":\"core/set\",\"args\":{\"variable\":\"a\",\"value\":\"one\"}},"
            + "{\"id\":\"s2\",\"action\":\"core/set\",\"args\":{\"variable\":\"n\",\"value\":42}}," + WAIT + ","
            + "{\"id\":\"s4\",\"action\":\"core/set\",\"args\":{\"variable\":\"after\",\"value\":{\"expr\":\"$a + '!'\"}}}]");
        RunResult first = run(f, new Runner.RunOptions(), true);
        assertEquals("suspended", first.status(), String.valueOf(first.error()));
        assertEquals("RUN.SUSPENDED", first.error().get("code"));
        assertEquals("-", first.outputs().get("after"), "nothing after the wait ran");
        Map<String, Object> args = (Map<String, Object>) asked.get(0).get("args");
        assertEquals("suspend", asked.get(0).get("call"));
        assertEquals("callback", args.get("kind"));
        assertEquals("wait_cb", args.get("ref"));
        assertEquals("w", args.get("step_id"));
        Map<String, Object> values = (Map<String, Object>) args.get("values");
        assertEquals("one", values.get("a"));
        assertEquals(0, new BigDecimal("42").compareTo((BigDecimal) values.get("n")));
        assertFalse(values.containsKey("secret"), "credentials are never saved");

        Map<String, Object> saved = (Map<String, Object>) JsonLite.parse(JsonLite.write(values));   // as kept in the database
        RunResult next = run(f, new Runner.RunOptions().resume("w", saved, Map.of("resumed_by", "callback", "timed_out", false, "result", Map.of("approved", true))), true);
        assertTrue(next.ok(), String.valueOf(next.error()));
        assertEquals("one!", next.outputs().get("after"), "the saved values are back and the steps after the wait run");
        assertEquals("callback", next.outputs().get("by"));
        assertEquals(Map.of("approved", true), next.outputs().get("res"), "the callback's body is the step's result");
        assertEquals(1, asked.size(), "the resumed step did not suspend again");
    }

    @Test void onlyFromOneCommandCenter() {
        RunResult r = run(flow("[" + WAIT + "]"), new Runner.RunOptions(), false);
        assertEquals("failed", r.status());
        assertEquals("LONGRUN.NOT_AVAILABLE", r.error().get("code"));
    }

    @Test void notInsideALoopOrACatch() {
        RunResult loop = run(flow("[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"times\",\"count\":2},\"children\":[" + WAIT + "]}]"), new Runner.RunOptions(), true);
        assertEquals("failed", loop.status());
        assertEquals("LONGRUN.CANNOT_SUSPEND", loop.error().get("code"));
        assertTrue(String.valueOf(loop.error().get("message")).contains("core/loop"));
        RunResult inCatch = run(flow("[{\"id\":\"t\",\"action\":\"core/try\",\"children\":[{\"id\":\"x\",\"action\":\"core/throw\",\"args\":{\"code\":\"BUSINESS.X\",\"message\":\"x\"}}],"
            + "\"catch\":[{\"codes\":[],\"children\":[" + WAIT + "]}]}]"), new Runner.RunOptions(), true);
        assertEquals("failed", inCatch.status());
        assertEquals("LONGRUN.CANNOT_SUSPEND", inCatch.error().get("code"), String.valueOf(inCatch.error()));
        assertTrue(asked.isEmpty(), "nothing was registered");
    }

    @Test void inATryNeitherCatchNorFinallyRunAtTheWaitAndATimeoutIsCaughtAfterTheResume() {
        String waitErr = WAIT.replace("\"on_timeout\":\"continue\"", "\"on_timeout\":\"error\"");
        Map<String, Object> f = flow("[{\"id\":\"t\",\"action\":\"core/try\",\"children\":[" + waitErr + "],"
            + "\"catch\":[{\"codes\":[],\"children\":[{\"id\":\"c\",\"action\":\"core/set\",\"args\":{\"variable\":\"caught\",\"value\":\"yes\"}}]}],"
            + "\"finally\":[{\"id\":\"f\",\"action\":\"core/set\",\"args\":{\"variable\":\"fin\",\"value\":\"ran\"}}]},"
            + "{\"id\":\"z\",\"action\":\"core/set\",\"args\":{\"variable\":\"after\",\"value\":\"end\"}}]");
        RunResult first = run(f, new Runner.RunOptions(), true);
        assertEquals("suspended", first.status(), String.valueOf(first.error()));
        assertEquals("-", first.outputs().get("caught"), "a catch-all does not take the suspension");
        assertEquals("-", first.outputs().get("fin"), "Finally waits for the end of the Try");
        RunResult next = run(f, new Runner.RunOptions().resume("w", Map.of(), Map.of("resumed_by", "timeout", "timed_out", true, "result", Map.of())), true);
        assertTrue(next.ok(), String.valueOf(next.error()));
        assertEquals("yes", next.outputs().get("caught"), "LONGRUN.TIMEOUT is caught by the Try around the wait");
        assertEquals("ran", next.outputs().get("fin"));
        assertEquals("end", next.outputs().get("after"));
    }

    @Test void continueOnErrorAndTheErrorHandlerDoNotTakeTheSuspension() {
        Map<String, Object> f = flow("[" + WAIT.replace("{\"id\":\"w\",", "{\"id\":\"w\",\"continueOnError\":true,") + ","
            + "{\"id\":\"z\",\"action\":\"core/set\",\"args\":{\"variable\":\"after\",\"value\":\"end\"}}]");
        f.put("onError", JsonLite.parse("{\"then\":\"succeed\",\"steps\":[{\"id\":\"h\",\"action\":\"core/set\",\"args\":{\"variable\":\"caught\",\"value\":\"handler\"}}]}"));
        RunResult r = run(f, new Runner.RunOptions(), true);
        assertEquals("suspended", r.status());
        assertEquals("-", r.outputs().get("after"));
        assertEquals("-", r.outputs().get("caught"));
    }

    @Test void aTimeoutWithContinueCarriesOnAndOtherActionsAskOneCommandCenter() {
        Map<String, Object> f = flow("[" + WAIT + "]");
        RunResult r = run(f, new Runner.RunOptions().resume("w", Map.of(), Map.of("resumed_by", "timeout", "timed_out", true, "result", Map.of())), true);
        assertTrue(r.ok());
        assertEquals(true, r.outputs().get("late"));
        assertEquals("timeout", r.outputs().get("by"));
        Map<String, Object> made = flow("[{\"id\":\"c\",\"action\":\"ooa.longrunning/create_callback\",\"out\":{\"url\":\"a\"}},"
            + "{\"id\":\"t\",\"action\":\"ooa.longrunning/create_task\",\"args\":{\"title\":\"Approve\",\"assignee\":\"role:admin\"},\"out\":{\"task_id\":\"after\"}}]");
        RunResult m = run(made, new Runner.RunOptions(), true);
        assertTrue(m.ok(), String.valueOf(m.error()));
        assertEquals("https://cc.example/api/callbacks/tok", m.outputs().get("a"));
        assertEquals("task_1", m.outputs().get("after"));
    }

    @Test void resumeAfterTakesSecondsOrADate() {
        RunResult r = run(flow("[{\"id\":\"d\",\"action\":\"ooa.longrunning/resume_after\",\"args\":{\"until\":\"2030-01-02T03:04:05+05:30\"}}]"), new Runner.RunOptions(), true);
        assertEquals("suspended", r.status());
        @SuppressWarnings("unchecked") Map<String, Object> args = (Map<String, Object>) asked.get(0).get("args");
        assertEquals("time", args.get("kind"));
        assertEquals("2030-01-01T21:34:05Z", args.get("ref"));
        assertEquals("LONGRUN.BAD_TIME", assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> LongRunningPackage.instant("tomorrow")).code());
    }
}
