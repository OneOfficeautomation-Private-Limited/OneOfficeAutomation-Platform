package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Options added to If, Loop, Try/Catch, Delay, Wait, Throw, Stop, Pause and Run Agent after the comparison with other RPA tools. */
class CoreOptionsTest {

    @OoaPackage(id = "acme.sessions", name = "Sessions", version = "1.0.0", description = "Opens and reads a session for tests.", publisher = "acme")
    public static final class Sessions {
        @Action(id = "open", name = "Open", description = "Opens a named session holding a text.")
        public Map<String, Object> open(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Session name.") String name,
                                        @Input(name = "text", type = OoaType.STRING, description = "Text kept in the session.") String text) {
            ctx.sessions().put(name, new StringBuilder(text));
            return Map.of();
        }

        @Action(id = "read", name = "Read", description = "Reads the text of a named session.")
        @Output(name = "text", type = OoaType.STRING, description = "Session text.")
        public Map<String, Object> read(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Session name.") String name) {
            return Map.of("text", ctx.session(name, StringBuilder.class).toString());
        }

        @LoopIterator(id = "letters", name = "Letters", description = "Yields the letters a to f, one at a time.")
        public Iterable<Object> letters(ActionContext ctx) { return List.of("a", "b", "c", "d", "e", "f"); }
    }

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation");
        PackageSigning.PublisherKey acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry r = new PackageRegistry();
        r.trust(first); r.trust(acme);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        r.install(Sessions.class, PackageSigning.sign(Sessions.class, acme));
        return r;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String json) { return (Map<String, Object>) JsonLite.parse(json); }

    static RunResult run(String json) { return Runner.builder(registry()).build().run(flow(json), Map.of()); }

    static RunResult run(Runner runner, String json) { return runner.run(flow(json), Map.of()); }

    static void ok(RunResult r) { assertTrue(r.ok(), String.valueOf(r.error())); }

    /** Runs core/if with the condition and returns whether the then-branch ran. */
    static boolean cond(String condition, String vars) {
        RunResult r = run("""
            {"kind":"TaskBot","id":"c","name":"c","variables":[{"name":"hit","type":"Boolean","direction":"output","default":false}%s],
             "steps":[{"id":"1","action":"core/if","args":{"condition":%s},"children":[{"id":"2","action":"core/set","args":{"variable":"hit","value":true}}]}]}"""
                .formatted(vars, condition));
        ok(r);
        return (Boolean) r.outputs().get("hit");
    }

    @Test void conditionOptions() {
        assertTrue(cond("{\"op\":\"eq\",\"left\":\"ACME Ltd\",\"right\":\"acme ltd\",\"ignoreCase\":true}", ""));
        assertFalse(cond("{\"op\":\"eq\",\"left\":\"ACME Ltd\",\"right\":\"acme ltd\"}", ""));
        assertTrue(cond("{\"op\":\"contains\",\"left\":\"Invoice PAID\",\"right\":\"paid\",\"ignoreCase\":true}", ""));
        assertTrue(cond("{\"op\":\"in\",\"left\":\"B\",\"right\":[\"a\",\"b\"],\"ignoreCase\":true}", ""));
        assertTrue(cond("{\"op\":\"eq\",\"left\":\"  x \",\"right\":\"x\",\"trim\":true}", ""));
        assertTrue(cond("{\"op\":\"eq\",\"left\":\"line one\\r\\nline two\",\"right\":\"line oneline two\",\"ignoreLineBreaks\":true}", ""));
        assertTrue(cond("{\"op\":\"eq\",\"left\":\"$d\",\"right\":\"2026-10-19T23:00:00\",\"includeTime\":false}",
                ",{\"name\":\"d\",\"type\":\"DateTime\",\"default\":\"2026-10-19T08:15:00\"}"));
        assertFalse(cond("{\"op\":\"eq\",\"left\":\"$d\",\"right\":\"2026-10-19T23:00:00\"}",
                ",{\"name\":\"d\",\"type\":\"DateTime\",\"default\":\"2026-10-19T08:15:00\"}"));
        assertTrue(cond("{\"op\":\"between\",\"left\":5,\"right\":[1,10]}", ""));
        assertTrue(cond("{\"op\":\"notBetween\",\"left\":11,\"right\":[1,10]}", ""));
        assertTrue(cond("{\"op\":\"notIn\",\"left\":\"c\",\"right\":[\"a\",\"b\"]}", ""));
        assertTrue(cond("{\"op\":\"notStartsWith\",\"left\":\"abc\",\"right\":\"x\"}", ""));
        assertTrue(cond("{\"op\":\"notEndsWith\",\"left\":\"abc\",\"right\":\"x\"}", ""));
        assertTrue(cond("{\"op\":\"notMatches\",\"left\":\"abc\",\"right\":\"\\\\d\"}", ""));
        assertTrue(cond("{\"op\":\"matches\",\"left\":\"ABC\",\"right\":\"^abc$\",\"ignoreCase\":true}", ""));
        RunResult bad = run("""
            {"kind":"TaskBot","id":"c","name":"c","steps":[{"id":"1","action":"core/if","args":{"condition":{"op":"between","left":1,"right":5}}}]}""");
        assertEquals("FLOW.BAD_CONDITION", bad.error().get("code"));
    }

    @Test void conditionWaitTime() {
        long t0 = System.nanoTime();
        assertFalse(cond("{\"op\":\"eq\",\"left\":1,\"right\":2,\"waitSeconds\":0.3,\"pollSeconds\":0.05}", ""));
        assertTrue((System.nanoTime() - t0) / 1e9 >= 0.3, "waited for the condition");
        assertTrue(cond("{\"op\":\"eq\",\"left\":1,\"right\":1,\"waitSeconds\":5}", ""));
    }

    static List<Object> loop(String loopArgs, String extraVars) {
        RunResult r = run("""
            {"kind":"TaskBot","id":"l","name":"l","variables":[{"name":"seen","type":"List","direction":"output","default":[]},
               {"name":"x","type":"Any"},{"name":"i","type":"Number"}%s],
             "steps":[{"id":"1","action":"core/loop","args":%s,
               "children":[{"id":"2","action":"core/set","args":{"variable":"seen","value":{"expr":"$seen + [$x]"}}}]}]}""".formatted(extraVars, loopArgs));
        ok(r);
        return (List<Object>) r.outputs().get("seen");
    }

    static List<Object> nums(long... v) { List<Object> l = new ArrayList<>(); for (long x : v) l.add(BigDecimal.valueOf(x)); return l; }

    static void sameNums(List<Object> expected, List<Object> actual) {
        assertEquals(expected.size(), actual.size(), String.valueOf(actual));
        for (int k = 0; k < expected.size(); k++) assertEquals(0, ((BigDecimal) expected.get(k)).compareTo((BigDecimal) actual.get(k)), String.valueOf(actual));
    }

    @Test void loopRangesStepsAndReverse() {
        sameNums(nums(1, 3, 5), loop("{\"iterator\":\"times\",\"count\":3,\"start\":1,\"step\":2,\"as\":\"x\"}", ""));
        sameNums(nums(0, 1, 2), loop("{\"iterator\":\"times\",\"count\":3,\"as\":\"x\"}", ""));
        assertEquals(List.of("b", "c", "d"), loop("{\"iterator\":\"list\",\"over\":[\"a\",\"b\",\"c\",\"d\",\"e\"],\"from_index\":1,\"to_index\":3,\"as\":\"x\"}", ""));
        assertEquals(List.of("e", "c", "a"), loop("{\"iterator\":\"list\",\"over\":[\"a\",\"b\",\"c\",\"d\",\"e\"],\"step\":2,\"reverse\":true,\"as\":\"x\"}", ""));
        assertEquals(List.of("k1", "k2"), loop("{\"iterator\":\"dictionary_keys\",\"over\":{\"k1\":1,\"k2\":2},\"as\":\"x\"}", ""));
        sameNums(nums(2), loop("{\"iterator\":\"dictionary_values\",\"over\":{\"k1\":1,\"k2\":2},\"from_index\":1,\"as\":\"x\"}", ""));
        List<Object> rows = loop("{\"iterator\":\"table_rows\",\"over\":{\"columns\":[\"n\"],\"rows\":[[1],[2],[3]]},\"to_index\":1,\"reverse\":true,\"as\":\"x\"}", "");
        assertEquals(2, rows.size());
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) ((Map<?, ?>) rows.get(0)).get("n")));
        assertEquals(List.of("b", "d"), loop("{\"iterator\":\"acme.sessions/letters\",\"from_index\":1,\"to_index\":4,\"step\":2,\"as\":\"x\"}", ""));
        assertEquals(List.of("f", "e"), loop("{\"iterator\":\"acme.sessions/letters\",\"from_index\":4,\"reverse\":true,\"as\":\"x\"}", ""));
        RunResult bad = run("""
            {"kind":"TaskBot","id":"l","name":"l","steps":[{"id":"1","action":"core/loop","args":{"iterator":"list","over":[1],"step":0}}]}""");
        assertEquals("LOOP.BAD_RANGE", bad.error().get("code"));
    }

    @Test void loopIndexIsTheOriginalPosition() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"l","name":"l","variables":[{"name":"idx","type":"List","direction":"output","default":[]},{"name":"i","type":"Number"}],
             "steps":[{"id":"1","action":"core/loop","args":{"iterator":"list","over":["a","b","c","d"],"from_index":2,"index":"i"},
               "children":[{"id":"2","action":"core/set","args":{"variable":"idx","value":{"expr":"$idx + [$i]"}}}]}]}""");
        ok(r);
        sameNums(nums(2, 3), (List<Object>) r.outputs().get("idx"));
    }

    @Test void whileCanCheckAtTheEnd() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"n","type":"Number","direction":"output","default":0}],
             "steps":[{"id":"1","action":"core/loop","args":{"iterator":"while","condition":{"op":"lt","left":"$n","right":0},"check_at_end":true},
               "children":[{"id":"2","action":"core/set","args":{"variable":"n","value":{"expr":"$n + 1"}}}]}]}""");
        ok(r);
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) r.outputs().get("n")), "ran once although the condition was false");
    }

    @Test void breakAndContinueCanTargetAnOuterLoop() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"n","name":"n","variables":[{"name":"log","type":"List","direction":"output","default":[]},{"name":"a","type":"Any"},{"name":"b","type":"Any"}],
             "steps":[{"id":"o","action":"core/loop","args":{"iterator":"list","over":[1,2,3],"as":"a","label":"outer"},"children":[
               {"id":"in","action":"core/loop","args":{"iterator":"list","over":["x","y"],"as":"b"},"children":[
                 {"id":"if1","action":"core/if","args":{"condition":{"op":"eq","left":"$a","right":2}},"children":[{"id":"c","action":"core/continue","args":{"label":"outer"}}]},
                 {"id":"if2","action":"core/if","args":{"condition":{"op":"eq","left":"$a","right":3}},"children":[{"id":"b1","action":"core/break","args":{"label":"outer"}}]},
                 {"id":"s","action":"core/set","args":{"variable":"log","value":{"expr":"$log + [concat($a, $b)]"}}}]}]}]}""");
        ok(r);
        assertEquals(List.of("1x", "1y"), r.outputs().get("log"));
        RunResult bad = run("""
            {"kind":"TaskBot","id":"n","name":"n","steps":[{"id":"o","action":"core/loop","args":{"iterator":"times","count":1},"children":[{"id":"b","action":"core/break","args":{"label":"nope"}}]}]}""");
        assertEquals("FLOW.BAD_BREAK", bad.error().get("code"));
    }

    @Test @SuppressWarnings("unchecked") void catchGivesLineActionAndScreenshot() throws Exception {
        Path w = Files.createTempDirectory("ooa-catch");
        List<Path> shots = new ArrayList<>();
        Runner runner = Runner.builder(registry()).workDir(w)
                .service("screen_capture", (Runner.ScreenCapture) p -> { Files.writeString(p, "png"); shots.add(p); }).build();
        RunResult r = run(runner, """
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"err","type":"Dictionary","direction":"output"}],
             "steps":[{"id":"try","action":"core/try","children":[
                 {"id":"note","action":"core/comment"},
                 {"id":"boom","action":"core/throw","args":{"code":"BUSINESS.BAD","message":"bad","details":{"invoice":"A1"}}}],
               "catch":[{"codes":["BUSINESS.*"],"as":"err","screenshot":true,"children":[]}]}]}""");
        ok(r);
        Map<String, Object> err = (Map<String, Object>) r.outputs().get("err");
        assertEquals("BUSINESS.BAD", err.get("code"));
        assertEquals("core/throw", err.get("action"));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(new BigDecimal(String.valueOf(err.get("line")))));
        assertEquals("A1", ((Map<String, Object>) err.get("details")).get("invoice"));
        assertEquals(1, shots.size());
        assertEquals(shots.get(0).toString(), err.get("screenshot"));
        assertTrue(shots.get(0).startsWith(w.resolve("screenshots")));
        // a failing screenshot never fails the Catch
        Runner broken = Runner.builder(registry()).workDir(w).service("screen_capture", (Runner.ScreenCapture) p -> { throw new IllegalStateException("no screen"); }).build();
        RunResult r2 = run(broken, """
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"err","type":"Dictionary","direction":"output"}],
             "steps":[{"id":"try","action":"core/try","children":[{"id":"boom","action":"core/throw","args":{"code":"X","message":"m"}}],
               "catch":[{"as":"err","screenshot":"shots/fail.png","children":[]}]}]}""");
        ok(r2);
        assertNull(((Map<String, Object>) r2.outputs().get("err")).get("screenshot"));
    }

    @Test void catchCanRethrowAndIgnoreItsOwnErrors() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"n","type":"Number","direction":"output","default":0}],
             "steps":[{"id":"outer","action":"core/try","children":[
                 {"id":"inner","action":"core/try","children":[{"id":"boom","action":"core/throw","args":{"code":"A.ONE","message":"m"}}],
                   "catch":[{"rethrow":true,"continueOnError":true,"children":[
                     {"id":"bad","action":"core/throw","args":{"code":"B.TWO","message":"inside catch"}},
                     {"id":"s1","action":"core/set","args":{"variable":"n","value":1}}]}]}],
               "catch":[{"codes":["A.ONE"],"children":[{"id":"s2","action":"core/set","args":{"variable":"n","value":{"expr":"$n + 10"}}}]}]}]}""");
        ok(r);
        assertEquals(0, BigDecimal.valueOf(11).compareTo((BigDecimal) r.outputs().get("n")));
    }

    @Test void throwCanBeRetryable() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"e","type":"Dictionary","direction":"output"}],
             "steps":[{"id":"try","action":"core/try","children":[{"id":"boom","action":"core/throw","args":{"code":"X","message":"m","retryable":true}}],
               "catch":[{"as":"e","children":[]}]}]}""");
        ok(r);
        assertEquals(true, ((Map<?, ?>) r.outputs().get("e")).get("retryable"));
    }

    @Test void delayUnitsAndRandomRange() {
        long t0 = System.nanoTime();
        ok(run("""
            {"kind":"TaskBot","id":"d","name":"d","steps":[{"id":"1","action":"core/delay","args":{"seconds":100,"unit":"milliseconds"}},
              {"id":"2","action":"core/delay","args":{"seconds":0.05,"random_max":0.15}}]}"""));
        double took = (System.nanoTime() - t0) / 1e9;
        assertTrue(took >= 0.15 && took < 3, "took " + took);
        assertEquals("DELAY.BAD_RANGE", run("""
            {"kind":"TaskBot","id":"d","name":"d","steps":[{"id":"1","action":"core/delay","args":{"seconds":2,"random_max":1}}]}""").error().get("code"));
    }

    @Test void waitCanCarryOnAtTimeout() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"w","name":"w","variables":[{"name":"met","type":"Boolean","direction":"output","default":true}],
             "steps":[{"id":"1","action":"core/wait_until","args":{"condition":false,"timeout_seconds":0.2,"poll_seconds":0.05,"fail_on_timeout":false},"out":{"met":"met"}}]}""");
        ok(r);
        assertEquals(false, r.outputs().get("met"));
        assertEquals("WAIT.TIMEOUT", run("""
            {"kind":"TaskBot","id":"w","name":"w","steps":[{"id":"1","action":"core/wait_until","args":{"condition":false,"timeout_seconds":0.1,"poll_seconds":0.05}}]}""").error().get("code"));
    }

    @Test void stopEndsTheAgentAfterFinally() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"s","name":"s","variables":[{"name":"n","type":"Number","direction":"output","default":0}],
             "steps":[{"id":"try","action":"core/try","children":[{"id":"stop","action":"core/stop","args":{"message":"nothing to do"}}],
                "finally":[{"id":"f","action":"core/set","args":{"variable":"n","value":1}}]},
              {"id":"after","action":"core/set","args":{"variable":"n","value":2}}]}""");
        ok(r);
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) r.outputs().get("n")));
        RunResult failed = run("""
            {"kind":"TaskBot","id":"s","name":"s","steps":[{"id":"stop","action":"core/stop","args":{"status":"failed","message":"bad input"}}]}""");
        assertEquals("failed", failed.status());
        assertEquals("AGENT.STOPPED", failed.error().get("code"));
        assertEquals("bad input", failed.error().get("message"));
    }

    @Test void pauseResumesAfterTimeoutOrWhenResumed() throws Exception {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow("""
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"1","action":"core/pause","args":{"message":"check the file","timeout_seconds":0.2}}]}"""), Map.of());
        ok(r);
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("run.paused") && "check the file".equals(e.data().get("message"))));
        AtomicBoolean pause = new AtomicBoolean();
        RunResult[] out = new RunResult[1];
        Thread t = new Thread(() -> out[0] = Runner.builder(registry()).build().run(flow("""
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"1","action":"core/pause"}]}"""), Map.of(), new Runner.RunOptions().pause(pause)));
        t.start();
        Thread.sleep(300);
        assertTrue(pause.get(), "the run paused itself");
        assertNull(out[0]);
        pause.set(false);
        t.join(5000);
        ok(out[0]);
    }

    static final String CHILD = """
        {"kind":"TaskBot","id":"child","name":"child","variables":[{"name":"x","type":"Number","direction":"input","default":1},
           {"name":"y","type":"Number","direction":"output"},{"name":"t","type":"String","direction":"output"}],
         "steps":[{"id":"c1","action":"core/set","args":{"variable":"y","value":{"expr":"$x * 2"}}},
                  {"id":"c2","action":"acme.sessions/read","args":{"name":"shared"},"out":{"text":"t"}}]}""";

    static Runner withChildren(Map<String, String> flows) {
        AutomationRepository repo = (id, version) -> {
            String f = flows.get(id);
            if (f == null) throw new OoaException("REPO.NOT_FOUND", id);
            return flow(f);
        };
        return Runner.builder(registry()).service("repository", repo).build();
    }

    @Test void runAgentSharesSessionsAndMapsOutputs() {
        Runner runner = withChildren(Map.of("child", CHILD));
        RunResult r = run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","variables":[{"name":"total","type":"Number","direction":"output"},{"name":"txt","type":"String","direction":"output"},
               {"name":"runs","type":"Number","direction":"output"},{"name":"after","type":"String","direction":"output"}],
             "steps":[{"id":"o","action":"acme.sessions/open","args":{"name":"shared","text":"hello"}},
              {"id":"r","action":"core/run_bot","args":{"automation":"child","inputs":{"x":21},"same_session":true,"output_map":{"y":"total","t":"txt"},"repeat":2},
               "out":{"runs":"runs"}},
              {"id":"again","action":"acme.sessions/read","args":{"name":"shared"},"out":{"text":"after"}}]}""");
        ok(r);
        assertEquals(0, BigDecimal.valueOf(42).compareTo((BigDecimal) r.outputs().get("total")));
        assertEquals("hello", r.outputs().get("txt"));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("runs")));
        assertEquals("hello", r.outputs().get("after"), "the child did not close the parent's session");
        // without same_session the child has its own sessions and cannot see "shared"
        RunResult own = run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"o","action":"acme.sessions/open","args":{"name":"shared","text":"hello"}},
              {"id":"r","action":"core/run_bot","args":{"automation":"child"}}]}""");
        assertEquals("SESSION.NOT_FOUND", own.error().get("code"));
    }

    @Test @SuppressWarnings("unchecked") void runAgentCanCarryOnAfterAFailureAndTimeOut() {
        String slow = """
            {"kind":"TaskBot","id":"slow","name":"slow","steps":[{"id":"z","action":"core/delay","args":{"seconds":5}}]}""";
        Runner runner = withChildren(Map.of("child", CHILD, "slow", slow));
        RunResult r = run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","variables":[{"name":"st","type":"String","direction":"output"},{"name":"err","type":"Dictionary","direction":"output"}],
             "steps":[{"id":"r","action":"core/run_bot","args":{"automation":"child","fail_on_error":false},"out":{"status":"st","error":"err"}}]}""");
        ok(r);
        assertEquals("failed", r.outputs().get("st"));
        assertEquals("SESSION.NOT_FOUND", ((Map<String, Object>) r.outputs().get("err")).get("code"));
        long t0 = System.nanoTime();
        RunResult timed = run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"r","action":"core/run_bot","args":{"automation":"slow","timeout_seconds":0.3}}]}""");
        assertEquals("CHILD.TIMEOUT", timed.error().get("code"));
        assertTrue((System.nanoTime() - t0) / 1e9 < 4, "the child was stopped early");
        FlowValidator v = new FlowValidator(registry(), null);
        assertTrue(v.validate(flow("""
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"r","action":"core/run_bot","args":{"automation":"child","output_map":{"y":"nowhere"}}}]}"""))
                .stream().anyMatch(p -> String.valueOf(p.get("message")).contains("nowhere")));
    }

    @Test @SuppressWarnings("unchecked") void moreSystemValues() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"s","name":"s","variables":[{"name":"v","type":"Dictionary","direction":"output"}],
             "steps":[{"id":"1","action":"core/set","args":{"variable":"v","value":"$sys"}}]}""");
        ok(r);
        Map<String, Object> v = (Map<String, Object>) r.outputs().get("v");
        for (String k : List.of("minute", "second", "millisecond", "day_of_year", "timestamp", "processors", "os_version", "home_folder", "total_ram_mb"))
            assertTrue(v.containsKey(k), k);
    }
}
