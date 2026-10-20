package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.InterruptRules;
import com.oneofficeautomation.packages.ui.SelfHealing;
import com.oneofficeautomation.packages.ui.TitlePattern;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Self-healing locators and interrupt rules, tested with fakes (no browser, no desktop). */
class UiResilienceTest {
    /** A clock that only moves when the code under test sleeps. */
    static final class FakeTimer implements SelfHealing.Timer {
        long now; int sleeps;
        public long nowMillis() { return now; }
        public void sleep(long ms) { now += ms; sleeps++; }
    }

    static final Map<String, Object> ALT = Map.of("automation_id", "save-btn", "name", "save", "id", "btnSave", "tag", "button", "class", "btn primary",
            "index", 2, "text", "Save", "relative", Map.of("anchor", "Invoice number", "direction", "right"));

    @Test void chainFollowsTheFixedOrderAndSkipsMissingAlternatives() {
        List<SelfHealing.Candidate> chain = SelfHealing.chain(new SelfHealing.Candidate("selector", "#old"), ALT, SelfHealing::webSelector);
        assertEquals(List.of("selector", "automation_id", "name", "id", "class_index", "text", "relative"), chain.stream().map(SelfHealing.Candidate::strategy).toList());
        assertEquals("[data-testid=\"save-btn\"]", chain.get(1).query());
        assertEquals("button[name=\"save\"]", chain.get(2).query());
        assertEquals("css=button.btn.primary >> nth=2", chain.get(4).query());
        assertEquals("button:text=\"Save\"", chain.get(5).query());
        assertEquals("button:right-of(:text(\"Invoice number\"))", chain.get(6).query());
        assertEquals(1, SelfHealing.chain(new SelfHealing.Candidate("selector", "#x"), null, SelfHealing::webSelector).size());
        assertEquals("[id=\"a\\\"b\"]", SelfHealing.webSelector("id", Map.of("id", "a\"b")), "quotes are escaped");
    }

    @Test void primaryWinsWhenItMatches() {
        List<String> logs = new ArrayList<>();
        List<SelfHealing.Candidate> chain = SelfHealing.chain(new SelfHealing.Candidate("selector", "#ok"), ALT, SelfHealing::webSelector);
        SelfHealing.Found<String> f = SelfHealing.locate(chain, c -> c.query().equals("#ok") ? Optional.of("primary") : Optional.of("other"), 1000, new FakeTimer(), 100, null, logs::add, null);
        assertEquals("primary", f.element());
        assertFalse(f.healed());
        assertTrue(logs.isEmpty(), "nothing to report");
    }

    @Test void fallsBackInOrderAndLogsTheStrategyThatHealed() {
        List<String> logs = new ArrayList<>(), asked = new ArrayList<>();
        List<SelfHealing.Candidate> chain = SelfHealing.chain(new SelfHealing.Candidate("selector", "#renamed"), ALT, SelfHealing::webSelector);
        Map<String, String> page = Map.of("css=button.btn.primary >> nth=2", "el-by-class", "button:text=\"Save\"", "el-by-text");   // ids and names changed
        SelfHealing.Found<String> f = SelfHealing.locate(chain, c -> { asked.add(c.strategy()); return Optional.ofNullable(page.get(c.query())); }, 1000, new FakeTimer(), 100, null, logs::add, null);
        assertEquals("el-by-class", f.element(), "class plus index comes before text");
        assertTrue(f.healed());
        assertEquals("class_index", f.by().strategy());
        assertEquals(List.of("selector", "automation_id", "name", "id", "class_index"), asked);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("class_index") && logs.get(0).contains("#renamed"), logs.get(0));
    }

    @Test void waitsUntilAnElementAppearsAndRunsTheMissHandlerOnce() {
        FakeTimer timer = new FakeTimer();
        AtomicInteger misses = new AtomicInteger();
        List<SelfHealing.Candidate> chain = List.of(new SelfHealing.Candidate("selector", "#late"));
        SelfHealing.Found<String> f = SelfHealing.locate(chain, c -> timer.now >= 700 ? Optional.of("late") : Optional.empty(), 5000, timer, 250, misses::incrementAndGet, s -> { }, null);
        assertEquals("late", f.element());
        assertEquals(1, misses.get(), "interrupt rules are checked on the first miss only");
        assertTrue(timer.now >= 700 && timer.now < 1500);
    }

    @Test void missHandlerCanUncoverTheElement() {
        AtomicBoolean popupClosed = new AtomicBoolean();
        FakeTimer timer = new FakeTimer();
        SelfHealing.Found<String> f = SelfHealing.locate(List.of(new SelfHealing.Candidate("selector", "#save")), c -> popupClosed.get() ? Optional.of("save") : Optional.empty(),
                0, timer, 250, () -> popupClosed.set(true), s -> { }, null);
        assertEquals("save", f.element(), "looked again right after the handler, even with no time left");
        assertEquals(0, timer.sleeps);
    }

    @Test void notFoundListsEveryStrategyTriedAndResolverErrors() {
        List<SelfHealing.Candidate> chain = SelfHealing.chain(new SelfHealing.Candidate("selector", "#gone"), Map.of("name", "q", "text", "Go"), SelfHealing::webSelector);
        ActionException e = assertThrows(ActionException.class, () -> SelfHealing.locate(chain, c -> {
            if (c.strategy().equals("text")) throw new IllegalStateException("bad selector");
            return Optional.empty();
        }, 600, new FakeTimer(), 200, null, s -> { }, null));
        assertEquals("UI.ELEMENT_NOT_FOUND", e.code());
        assertTrue(e.getMessage().contains("selector #gone") && e.getMessage().contains("name") && e.getMessage().contains("bad selector"), e.getMessage());
        assertThrows(ActionException.class, () -> SelfHealing.locate(List.of(), c -> Optional.empty(), 0, new FakeTimer(), 1, null, s -> { }, null));
    }

    @Test void cancellingStopsTheSearch() {
        OoaException e = assertThrows(OoaException.class, () -> SelfHealing.locate(List.of(new SelfHealing.Candidate("selector", "#x")), c -> Optional.empty(), 60_000, new FakeTimer(), 100,
                null, s -> { }, () -> { throw new OoaException("RUN.CANCELLED", "stopped"); }));
        assertEquals("RUN.CANCELLED", e.code());
    }

    @Test void titlePatterns() {
        assertTrue(TitlePattern.matches("invoice", "Invoice 42 - Notepad"));
        assertTrue(TitlePattern.matches("*Notepad", "Invoice 42 - Notepad"));
        assertFalse(TitlePattern.matches("Notepad*", "Invoice 42 - Notepad"));
        assertTrue(TitlePattern.matches("re:invoice \\d+", "INVOICE 42"));
        assertTrue(TitlePattern.matches("Invoice ?? - *", "Invoice 42 - Notepad"));
        assertFalse(TitlePattern.matches("", "anything"));
        assertThrows(IllegalArgumentException.class, () -> TitlePattern.of("re:("));
    }

    // ---------- interrupt rules ----------
    static class FakeScreen implements InterruptRules.Probe, InterruptRules.Responder {
        final List<String> windows = new ArrayList<>();
        final Set<String> elements = new HashSet<>();
        final List<String> actions = new ArrayList<>();
        boolean browserOpen = true;
        public List<String> windowTitles() { return new ArrayList<>(windows); }
        public Boolean elementPresent(String session, String selector) { return browserOpen ? elements.contains(selector) : null; }
        public void click(InterruptRules.Rule r, String w) { actions.add("click " + r.target()); elements.remove(r.whenElement()); }
        public void pressKeys(InterruptRules.Rule r, String w) { actions.add("keys " + r.target() + " in " + w); windows.remove(w); }
        public void closeWindow(InterruptRules.Rule r, String w) { actions.add("close " + w); windows.remove(w); }
        public void runAgent(InterruptRules.Rule r) { actions.add("agent " + r.target() + " " + r.inputs()); }
    }

    static InterruptRules rules() { return new InterruptRules().timing(new FakeTimer(), 10); }

    @Test void rulesRespondToWindowsAndElementsThatAppear() {
        InterruptRules rules = rules();
        rules.add(new InterruptRules.Rule("expired", "*Session expired*", null, null, "press_keys", "ENTER", null, 0));
        rules.add(new InterruptRules.Rule("cookies", null, "#cookie-ok", "browser", "click", "#cookie-ok", null, 0));
        rules.add(new InterruptRules.Rule("update", "re:^Update available", null, null, "close_window", null, null, 0));
        rules.add(new InterruptRules.Rule("crash", "Error report", null, null, "run_agent", "recover_erp", Map.of("why", "crash"), 1));
        FakeScreen s = new FakeScreen();
        List<String> log = new ArrayList<>();
        assertEquals(List.of(), rules.check(s, s, log::add), "nothing on screen, nothing to do");
        s.windows.addAll(List.of("ERP - Session expired", "Update available: 2.1", "Invoices"));
        s.elements.add("#cookie-ok");
        assertEquals(List.of("expired", "cookies", "update"), rules.check(s, s, log::add));
        assertEquals(List.of("keys ENTER in ERP - Session expired", "click #cookie-ok", "close Update available: 2.1"), s.actions);
        assertEquals(List.of("Invoices"), s.windows);
        assertTrue(log.stream().anyMatch(l -> l.contains("Session expired") && l.contains("press_keys")), String.valueOf(log));
        s.windows.add("Error report"); s.windows.add("Error report");
        assertEquals(List.of("crash"), rules.check(s, s, log::add), "max_times 1: the second report is left alone");
        assertEquals("agent recover_erp {why=crash}", s.actions.get(3));
        assertEquals(1, rules.timesFired("crash"));
    }

    @Test void aResponseThatRevealsAnotherPopUpIsHandledInTheNextRound() {
        InterruptRules rules = rules();
        FakeScreen s = new FakeScreen() {
            @Override public void click(InterruptRules.Rule r, String w) { super.click(r, w); if (r.name().equals("first")) elements.add("#second"); }
        };
        rules.add(new InterruptRules.Rule("first", null, "#first", null, "click", "#first", null, 0));
        rules.add(new InterruptRules.Rule("second", null, "#second", null, "click", "#second", null, 0));
        s.elements.add("#first");
        assertEquals(List.of("first", "second"), rules.check(s, s, x -> { }));
    }

    @Test void failingResponsesAreLoggedNotThrownAndElementRulesWaitForTheirBrowser() {
        InterruptRules rules = rules();
        rules.add(new InterruptRules.Rule("dialog", "Confirm", null, null, "click", "#yes", null, 0));
        rules.add(new InterruptRules.Rule("banner", null, "#banner", "web2", "click", "#close", null, 0));
        FakeScreen s = new FakeScreen() {
            @Override public void click(InterruptRules.Rule r, String w) { throw new ActionException("INTERRUPT.NO_SESSION", "no browser"); }
        };
        s.windows.add("Confirm");
        s.browserOpen = false;
        s.elements.add("#banner");
        List<String> log = new ArrayList<>();
        assertEquals(List.of("dialog", "dialog", "dialog"), rules.check(s, s, log::add), "three rounds at most, then the agent's own action decides");
        assertTrue(log.stream().anyMatch(l -> l.contains("could not respond") && l.contains("no browser")));
    }

    @Test void ruleValidation() {
        assertThrows(ActionException.class, () -> new InterruptRules.Rule("x", null, null, null, "click", "#a", null, 0), "needs a window or an element");
        assertThrows(ActionException.class, () -> new InterruptRules.Rule("x", "a", "#b", null, "click", "#a", null, 0), "not both");
        assertThrows(ActionException.class, () -> new InterruptRules.Rule("x", "a", null, null, "dance", null, null, 0));
        assertThrows(ActionException.class, () -> new InterruptRules.Rule("x", "a", null, null, "press_keys", "", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new InterruptRules.Rule("x", "re:(", null, null, "ignore", null, null, 0));
        InterruptRules r = rules();
        r.add(new InterruptRules.Rule("x", "a", null, null, "ignore", null, null, 0));
        r.add(new InterruptRules.Rule("x", "b", null, null, "ignore", null, null, 0));
        assertEquals(1, r.describe().size(), "same name replaces");
        assertTrue(r.remove("x"));
        assertTrue(r.isEmpty());
    }

    // ---------- the package actions, through a real ActionContext ----------
    @Test void interruptsPackageKeepsRulesPerRunAndRunsChildAgents() {
        Map<String, Object> sessions = new HashMap<>();
        List<String> started = new ArrayList<>();
        sessions.put(ChildAgents.SESSION, (ChildAgents) (a, in) -> { started.add(a + in); return Map.of(); });
        List<String> logs = new ArrayList<>();
        ActionContext ctx = new ActionContext("run1", "s1", "t", Path.of("."), sessions, Map.of(), new AtomicBoolean(), List::of, (l, m) -> logs.add(m));
        InterruptsPackage p = new InterruptsPackage();
        p.addRule(ctx, "popup", null, "#popup", "web", "run_agent", "close_popup", Map.of("n", 1), 1);
        assertEquals(1, ((List<?>) p.listRules(ctx).get("rules")).size());
        UiGuard.register(ctx, "web", new UiGuard.ElementHooks() {
            boolean shown = true;
            public boolean present(String s) { return shown; }
            public void click(String s) { }
            public void press(String k) { }
        });
        Map<String, Object> r = p.checkNow(ctx);
        assertEquals(List.of("popup"), r.get("handled"));
        assertEquals(List.of("close_popup{n=1}"), started);
        assertEquals(true, p.removeRule(ctx, "popup").get("removed"));
        assertEquals(List.of(), UiGuard.before(ctx));
    }
}
