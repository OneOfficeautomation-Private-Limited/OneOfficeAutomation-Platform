package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AA parity, group G1 (21 Nov 2026): comparison ticks, Session is open, Child agent succeeded, Run agent repeated for a
 * time, a record's column by position, Watch for interruptions and the trigger loop with several handlers.
 */
class CoreParityG1Test {

    /** A pretend pop-up: show makes it appear, close removes it, click fails while it shows. */
    @OoaPackage(id = "acme.popup", name = "Popup", version = "1.0.0", description = "A pretend pop-up for tests.", publisher = "acme")
    public static final class Popup {
        static AtomicBoolean flag(ActionContext ctx) { return (AtomicBoolean) ctx.sessions().computeIfAbsent("popup", k -> new AtomicBoolean()); }
        static AtomicInteger count(ActionContext ctx, String name) { return (AtomicInteger) ctx.sessions().computeIfAbsent(name, k -> new AtomicInteger()); }

        @Action(id = "show", name = "Show", description = "Shows the pop-up.")
        public Map<String, Object> show(ActionContext ctx) { flag(ctx).set(true); return Map.of(); }

        @Action(id = "show_later", name = "Show later", description = "Shows the pop-up a moment later.")
        public Map<String, Object> showLater(ActionContext ctx) {
            AtomicBoolean f = flag(ctx);
            Thread.ofVirtual().start(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) { } f.set(true); });
            return Map.of();
        }

        @Action(id = "close", name = "Close", description = "Closes the pop-up.")
        public Map<String, Object> close(ActionContext ctx) { flag(ctx).set(false); count(ctx, "closed").incrementAndGet(); return Map.of(); }

        @Action(id = "click", name = "Click", description = "Fails while the pop-up shows.")
        @Output(name = "clicks", type = OoaType.NUMBER, description = "Clicks so far.")
        public Map<String, Object> click(ActionContext ctx) {
            if (flag(ctx).get()) throw new ActionException("UI.BLOCKED", "A pop-up is in the way", "Close it.");
            return Map.of("clicks", BigDecimal.valueOf(count(ctx, "clicks").incrementAndGet()));
        }

        @Action(id = "click_pops_up", name = "Click that raises a pop-up", description = "The first click raises the pop-up and fails.")
        public Map<String, Object> clickPopsUp(ActionContext ctx) {
            if (count(ctx, "tries").incrementAndGet() == 1) { flag(ctx).set(true); throw new ActionException("UI.BLOCKED", "A pop-up appeared", "Close it."); }
            return Map.of();
        }

        @Action(id = "closed", name = "Closed", description = "How often the pop-up was closed.")
        @Output(name = "n", type = OoaType.NUMBER, description = "Times closed.")
        public Map<String, Object> closed(ActionContext ctx) { return Map.of("n", BigDecimal.valueOf(count(ctx, "closed").get())); }

        @Condition(id = "showing", name = "Pop-up shows", description = "True while the pop-up shows.")
        public boolean showing(ActionContext ctx) { return flag(ctx).get(); }

        @LoopIterator(id = "numbers", name = "Numbers", description = "1 and 2.")
        public Iterable<Object> numbers(ActionContext ctx) { return List.of(BigDecimal.ONE, BigDecimal.valueOf(2)); }

        @LoopIterator(id = "letters", name = "Letters", description = "x.")
        public Iterable<Object> letters(ActionContext ctx, @Input(name = "letter", type = OoaType.STRING, required = false, defaultValue = "\"x\"", description = "The letter.") String letter) {
            return List.of(letter);
        }
    }

    static PackageRegistry registry() {
        PackageRegistry r = CoreOptionsTest.registry();
        PackageSigning.PublisherKey acme = PackageSigning.PublisherKey.generate("acme");
        r.trust(acme);
        r.install(Popup.class, PackageSigning.sign(Popup.class, acme));
        return r;
    }

    static Map<String, Object> flow(String json) { return CoreOptionsTest.flow(json); }

    static RunResult run(String json) { return Runner.builder(registry()).build().run(flow(json), Map.of()); }

    static void ok(RunResult r) { assertTrue(r.ok(), String.valueOf(r.error())); }

    static int num(Object v) { return ((BigDecimal) v).intValue(); }

    // ---------- comparison ticks ----------

    @Test void matchCaseAndDateOnlyAsTicksOrVariables() {
        assertTrue(CoreOptionsTest.cond("{\"op\":\"eq\",\"left\":\"ACME\",\"right\":\"acme\",\"matchCase\":false}", ""));
        assertFalse(CoreOptionsTest.cond("{\"op\":\"eq\",\"left\":\"ACME\",\"right\":\"acme\",\"matchCase\":\"$m\"}", ",{\"name\":\"m\",\"type\":\"Boolean\",\"default\":true}"));
        assertTrue(CoreOptionsTest.cond("{\"op\":\"eq\",\"left\":\"$d\",\"right\":\"2026-10-19T23:00:00\",\"dateOnly\":\"$o\"}",
                ",{\"name\":\"d\",\"type\":\"DateTime\",\"default\":\"2026-10-19T08:15:00\"},{\"name\":\"o\",\"type\":\"Boolean\",\"default\":true}"));
        // ignoreCase given as a variable too
        assertTrue(CoreOptionsTest.cond("{\"op\":\"contains\",\"left\":\"Invoice PAID\",\"right\":\"paid\",\"ignoreCase\":\"$i\"}", ",{\"name\":\"i\",\"type\":\"Boolean\",\"default\":true}"));
    }

    @Test void aRecordsColumnByPosition() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"row","type":"Record","default":{"name":"Ann","total":12}},
               {"name":"second","type":"Any","direction":"output"},{"name":"none","type":"Any","direction":"output"},{"name":"byName","type":"Any","direction":"output"}],
             "steps":[{"id":"a","action":"core/set","args":{"variable":"second","value":"$row[1]$"}},
                      {"id":"b","action":"core/set","args":{"variable":"none","value":"$row[5]$"}},
                      {"id":"c","action":"core/set","args":{"variable":"byName","value":"$row.name$"}}]}""");
        ok(r);
        assertEquals(12, num(r.outputs().get("second")));
        assertNull(r.outputs().get("none"));
        assertEquals("Ann", r.outputs().get("byName"));
    }

    // ---------- Session is open, Child agent succeeded ----------

    @Test void sessionIsOpen() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"a","type":"Boolean","direction":"output"},{"name":"b","type":"Boolean","direction":"output"}],
             "steps":[{"id":"o","action":"acme.sessions/open","args":{"name":"wb","text":"x"}},
              {"id":"i1","action":"core/if","args":{"condition":{"condition":"core/session_open","args":{"session":"wb"}}},"children":[{"id":"s1","action":"core/set","args":{"variable":"a","value":true}}]},
              {"id":"i2","action":"core/if","args":{"condition":{"condition":"core/session_open","args":{"session":"other"}}},"children":[{"id":"s2","action":"core/set","args":{"variable":"b","value":true}}]}]}""");
        ok(r);
        assertEquals(true, r.outputs().get("a"));
        assertEquals(false, r.outputs().get("b"));
    }

    @Test void childAgentSucceeded() {
        Runner runner = CoreOptionsTest.withChildren(Map.of(
                "good", "{\"kind\":\"TaskBot\",\"id\":\"good\",\"name\":\"good\",\"steps\":[{\"id\":\"c\",\"action\":\"core/comment\"}]}",
                "bad", "{\"kind\":\"TaskBot\",\"id\":\"bad\",\"name\":\"bad\",\"steps\":[{\"id\":\"c\",\"action\":\"core/throw\",\"args\":{\"code\":\"BUSINESS.NO\"}}]}"));
        RunResult r = CoreOptionsTest.run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","variables":[{"name":"g","type":"Boolean","direction":"output"},{"name":"b","type":"Boolean","direction":"output"}],
             "steps":[{"id":"i1","action":"core/if","args":{"condition":{"condition":"core/agent_succeeded","args":{"automation":"good"}}},"children":[{"id":"s1","action":"core/set","args":{"variable":"g","value":true}}]},
              {"id":"i2","action":"core/if","args":{"condition":{"condition":"core/agent_succeeded","args":{"automation":"bad"}}},"children":[{"id":"s2","action":"core/set","args":{"variable":"b","value":true}}]}]}""");
        ok(r);
        assertEquals(true, r.outputs().get("g"));
        assertEquals(false, r.outputs().get("b"));
        // an agent that does not exist is a mistake in the agent, not a failed child
        RunResult missing = CoreOptionsTest.run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"i","action":"core/if","args":{"condition":{"condition":"core/agent_succeeded","args":{"automation":"nope"}}}}]}""");
        assertEquals("REPO.NOT_FOUND", missing.error().get("code"));
    }

    @Test void runAgentForALengthOfTime() {
        Runner runner = CoreOptionsTest.withChildren(Map.of("quick", "{\"kind\":\"TaskBot\",\"id\":\"quick\",\"name\":\"quick\",\"steps\":[{\"id\":\"c\",\"action\":\"core/comment\"}]}"));
        RunResult r = CoreOptionsTest.run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","variables":[{"name":"runs","type":"Number","direction":"output"}],
             "steps":[{"id":"r","action":"core/run_bot","args":{"automation":"quick","repeat_mode":"for_duration","repeat_for_seconds":0.35,"repeat_delay_seconds":0.1},"out":{"runs":"runs"}}]}""");
        ok(r);
        int runs = num(r.outputs().get("runs"));
        assertTrue(runs >= 2 && runs <= 6, "ran " + runs + " times");
        RunResult bad = CoreOptionsTest.run(runner, """
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"r","action":"core/run_bot","args":{"automation":"quick","repeat_mode":"for_duration"}}]}""");
        assertEquals("CHILD.BAD_REPEAT", bad.error().get("code"));
    }

    @Test void runAgentUntilStopped() throws Exception {
        Runner runner = CoreOptionsTest.withChildren(Map.of("quick", "{\"kind\":\"TaskBot\",\"id\":\"quick\",\"name\":\"quick\",\"steps\":[{\"id\":\"c\",\"action\":\"core/comment\"}]}"));
        AtomicBoolean stop = new AtomicBoolean();
        Thread.ofVirtual().start(() -> { try { Thread.sleep(400); } catch (InterruptedException ignored) { } stop.set(true); });
        RunResult r = runner.run(flow("""
            {"kind":"TaskBot","id":"p","name":"p","steps":[{"id":"r","action":"core/run_bot","args":{"automation":"quick","repeat_mode":"until_stopped","repeat_delay_seconds":0.05}}]}"""),
                Map.of(), new Runner.RunOptions().cancel(stop));
        assertEquals("stopped", r.status());
        assertTrue(r.stepsExecuted() >= 2, "the child ran again and again: " + r.stepsExecuted());
    }

    // ---------- Watch for interruptions ----------

    static final String CLOSE_HANDLER = "{\"name\":\"Pop-up\",\"when\":{\"condition\":\"acme.popup/showing\"},\"children\":[{\"id\":\"h1\",\"action\":\"acme.popup/close\"}]}";

    @Test void aHandlerDealsWithAPopUpBeforeTheNextStep() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"n","type":"Number","direction":"output"}],
             "steps":[{"id":"w","action":"core/interrupt_scope","handlers":[%s],
                       "children":[{"id":"a","action":"acme.popup/show"},{"id":"b","action":"acme.popup/click"}]},
                      {"id":"c","action":"acme.popup/closed","out":{"n":"n"}}]}""".formatted(CLOSE_HANDLER));
        ok(r);
        assertEquals(1, num(r.outputs().get("n")));
        // outside the scope nothing is watched
        RunResult outside = run("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"w","action":"core/interrupt_scope","handlers":[%s],"children":[{"id":"x","action":"core/comment"}]},
                      {"id":"a","action":"acme.popup/show"},{"id":"b","action":"acme.popup/click"}]}""".formatted(CLOSE_HANDLER));
        assertEquals("UI.BLOCKED", outside.error().get("code"));
    }

    @Test void maxTimesLimitsAHandler() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"w","action":"core/interrupt_scope",
               "handlers":[{"when":{"condition":"acme.popup/showing"},"max_times":1,"children":[{"id":"h1","action":"acme.popup/close"}]}],
               "children":[{"id":"a","action":"acme.popup/show"},{"id":"b","action":"acme.popup/click"},{"id":"c","action":"acme.popup/show"},{"id":"d","action":"acme.popup/click"}]}]}""");
        assertEquals("UI.BLOCKED", r.error().get("code"));
        assertEquals("d", r.error().get("stepId"));
    }

    @Test void aFailedStepRunsAgainAfterAHandlerDealtWithAPopUp() {
        String flow = """
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"w","action":"core/interrupt_scope","args":{"retry_failed_step":%s},"handlers":[%s],
               "children":[{"id":"a","action":"acme.popup/click_pops_up"}]}]}""";
        ok(run(flow.formatted("true", CLOSE_HANDLER)));
        assertEquals("UI.BLOCKED", run(flow.formatted("false", CLOSE_HANDLER)).error().get("code"));
    }

    @Test void handlersAreWatchedWhileTheAgentWaits() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"n","type":"Number","direction":"output"}],
             "steps":[{"id":"w","action":"core/interrupt_scope","args":{"check_every_seconds":0.1},"handlers":[%s],
                       "children":[{"id":"a","action":"acme.popup/show_later"},{"id":"d","action":"core/delay","args":{"seconds":0.6}},
                                   {"id":"n1","action":"acme.popup/closed","out":{"n":"n"}}]}]}""".formatted(CLOSE_HANDLER)), Map.of());
        ok(r);
        assertEquals(1, num(r.outputs().get("n")), "closed during the Delay, before the next step");
        assertTrue(sink.events().stream().anyMatch(e -> e.type().equals("interrupt.handled") && e.data().get("beforeStep") == null));
    }

    @Test void nothingIsWatchedWhileAHandlerRuns() {
        // the handler shows the pop-up again itself: it must not set off its own handler over and over
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"n","type":"Number","direction":"output"}],
             "steps":[{"id":"w","action":"core/interrupt_scope","handlers":[{"when":{"condition":"acme.popup/showing"},
                 "children":[{"id":"h1","action":"acme.popup/close"},{"id":"h2","action":"acme.popup/show"},{"id":"h3","action":"acme.popup/close"}]}],
               "children":[{"id":"a","action":"acme.popup/show"},{"id":"b","action":"acme.popup/click"}]},
              {"id":"c","action":"acme.popup/closed","out":{"n":"n"}}]}""");
        ok(r);
        assertEquals(2, num(r.outputs().get("n")));
    }

    // ---------- trigger loop with several handlers ----------

    @Test void aTriggerLoopRunsTheHandlerOfEachEvent() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"seen","type":"String","direction":"output","default":""},
               {"name":"num","type":"Any"},{"name":"letter","type":"Any"},{"name":"i","type":"Number","direction":"output"}],
             "steps":[{"id":"l","action":"core/loop","args":{"iterator":"triggers","index":"i"},
               "handlers":[{"name":"numbers","trigger":"acme.popup/numbers","as":"num","children":[{"id":"n","action":"core/set","args":{"variable":"seen","value":"${seen}n${num}"}}]},
                           {"name":"letters","trigger":"acme.popup/letters","args":{"letter":"q"},"as":"letter","children":[{"id":"q","action":"core/set","args":{"variable":"seen","value":"${seen}${letter}"}}]}]}]}""");
        ok(r);
        String seen = (String) r.outputs().get("seen");
        assertEquals(5, seen.length(), seen);
        assertTrue(seen.contains("n1") && seen.contains("n2") && seen.contains("q"), seen);
        assertTrue(seen.indexOf("n1") < seen.indexOf("n2"), "one trigger's events keep their order: " + seen);
        assertEquals(2, num(r.outputs().get("i")));
    }

    @Test void exitLoopEndsATriggerLoop() {
        RunResult r = run("""
            {"kind":"TaskBot","id":"t","name":"t","variables":[{"name":"count","type":"Number","direction":"output","default":0}],
             "steps":[{"id":"l","action":"core/loop","args":{"iterator":"triggers"},
               "handlers":[{"trigger":"acme.popup/numbers","children":[{"id":"n","action":"core/set","args":{"variable":"count","value":{"expr":"$count + 1"}}},{"id":"b","action":"core/break"}]}]}]}""");
        ok(r);
        assertEquals(1, num(r.outputs().get("count")));
    }

    // ---------- checks ----------

    static List<String> problems(String json) {
        return new FlowValidator(registry(), null).validate(flow(json)).stream().map(p -> String.valueOf(p.get("message"))).toList();
    }

    @Test void theCheckerKnowsHandlers() {
        assertEquals(List.of(), problems("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"w","action":"core/interrupt_scope","handlers":[%s],"children":[{"id":"a","action":"acme.popup/click"}]}]}""".formatted(CLOSE_HANDLER)));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"w","action":"core/step","handlers":[%s]}]}""".formatted(CLOSE_HANDLER))
                .stream().anyMatch(m -> m.startsWith("handlers are only allowed")));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"w","action":"core/interrupt_scope","handlers":[{"children":[]}]}]}""")
                .contains("Handler 1 has no condition"));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"l","action":"core/loop","args":{"iterator":"triggers"}}]}""")
                .contains("The trigger loop has no handlers"));
        assertTrue(problems("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"l","action":"core/loop","args":{"iterator":"triggers"},
              "handlers":[{"trigger":"acme.popup/letters","args":{"colour":"red"},"as":"nope","children":[]}]}]}""")
                .containsAll(List.of("Unknown input 'colour' for acme.popup/letters (trigger)", "Handler variable 'nope' is not declared")));
        // steps inside handlers are checked like any others
        assertTrue(problems("""
            {"kind":"TaskBot","id":"t","name":"t","steps":[{"id":"w","action":"core/interrupt_scope","handlers":[{"when":true,"children":[{"id":"h","action":"core/set","args":{"variable":"ghost","value":1}}]}]}]}""")
                .stream().anyMatch(m -> m.contains("ghost")));
    }
}
