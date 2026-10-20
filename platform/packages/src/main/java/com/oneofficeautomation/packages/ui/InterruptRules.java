package com.oneofficeautomation.packages.ui;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.OoaException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Interrupt handler: a run-level list of rules such as "when a window titled 'Session expired' appears, press ENTER"
 * or "when the element #cookie-accept is on the page, click it". UI toolkits check the rules before each UI action and
 * again when an element cannot be found, so unexpected pop-ups do not fail the agent.
 *
 * <p>The rules live in the run's sessions (one list per agent run). Looking for windows and elements, and responding,
 * goes through {@link Probe} and {@link Responder}, so the logic is tested with fakes.
 */
public final class InterruptRules {
    public static final String SESSION = "ooa.interrupt_rules";
    public static final Set<String> RESPONSES = Set.of("click", "press_keys", "close_window", "run_agent", "ignore");

    /** One rule. Exactly one of whenWindow (title pattern) and whenElement (selector in a browser session) is set. */
    public record Rule(String name, String whenWindow, String whenElement, String session, String then, String target, Map<String, Object> inputs, int maxTimes) {
        public Rule {
            if (name == null || name.isBlank()) throw new ActionException("INTERRUPT.BAD_RULE", "A rule needs a name");
            boolean w = whenWindow != null && !whenWindow.isBlank(), e = whenElement != null && !whenElement.isBlank();
            if (w == e) throw new ActionException("INTERRUPT.BAD_RULE", "Rule '" + name + "': give either a window title pattern or an element selector", "Use when_window or when_element, not both.");
            if (w) TitlePattern.of(whenWindow);   // validates a re: pattern
            if (!RESPONSES.contains(then)) throw new ActionException("INTERRUPT.BAD_RULE", "Rule '" + name + "': the response must be one of " + new TreeSet<>(RESPONSES));
            if (Set.of("click", "press_keys", "run_agent").contains(then) && (target == null || target.isBlank()))
                throw new ActionException("INTERRUPT.BAD_RULE", "Rule '" + name + "': " + then + " needs a target",
                        "click: a selector or x,y screen position; press_keys: keys like ENTER or ALT+F4; run_agent: the agent id.");
            session = session == null || session.isBlank() ? "browser" : session;
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            maxTimes = Math.max(0, maxTimes);
        }
        public boolean windowRule() { return whenWindow != null && !whenWindow.isBlank(); }
    }

    /** What is on screen now. */
    public interface Probe {
        List<String> windowTitles();
        /** Null when no element toolkit has this session open (element rules for it are skipped). */
        Boolean elementPresent(String session, String selector);
    }

    /** How a rule responds; matchedWindow is the full title of the window that matched a window rule, else null. */
    public interface Responder {
        void click(Rule r, String matchedWindow);
        void pressKeys(Rule r, String matchedWindow);
        void closeWindow(Rule r, String matchedWindow);
        void runAgent(Rule r);
    }

    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> fired = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> busy = ThreadLocal.withInitial(() -> false);
    private SelfHealing.Timer timer = SelfHealing.SYSTEM;
    private long settleMs = 400;

    /** The run's rules, created on first use. */
    public static InterruptRules of(ActionContext ctx) { return (InterruptRules) ctx.sessions().computeIfAbsent(SESSION, k -> new InterruptRules()); }

    /** The run's rules, or null when the agent never added any. */
    public static InterruptRules existing(ActionContext ctx) { return ctx.sessions().get(SESSION) instanceof InterruptRules r ? r : null; }

    public InterruptRules timing(SelfHealing.Timer t, long settle) { timer = t; settleMs = settle; return this; }

    /** Adds a rule, replacing one with the same name. */
    public void add(Rule r) { rules.removeIf(x -> x.name().equals(r.name())); rules.add(r); fired.remove(r.name()); }
    public boolean remove(String name) { fired.remove(name); return rules.removeIf(x -> x.name().equals(name)); }
    public void clear() { rules.clear(); fired.clear(); }
    public boolean isEmpty() { return rules.isEmpty(); }
    public int timesFired(String name) { return fired.getOrDefault(name, 0); }

    public List<Object> describe() {
        List<Object> out = new ArrayList<>();
        for (Rule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put(r.windowRule() ? "when_window" : "when_element", r.windowRule() ? r.whenWindow() : r.whenElement());
            if (!r.windowRule()) m.put("browser_session", r.session());
            m.put("then", r.then());
            if (r.target() != null) m.put("target", r.target());
            m.put("max_times", BigDecimal.valueOf(r.maxTimes()));
            m.put("times_fired", BigDecimal.valueOf(timesFired(r.name())));
            out.add(m);
        }
        return out;
    }

    /**
     * Checks every rule and responds to those whose window or element is showing. A response can reveal another
     * pop-up, so up to three rounds run; each rule responds at most once per round and never beyond its max_times.
     * A failing response is logged, never thrown: the agent's own action then decides whether the run fails.
     * Returns the names of the rules that responded, in order.
     */
    public List<String> check(Probe probe, Responder responder, Consumer<String> log) {
        if (rules.isEmpty() || busy.get()) return List.of();
        busy.set(true);   // a response (for example a child agent doing UI work) must not re-enter the check
        List<String> handled = new ArrayList<>();
        try {
            for (int round = 0; round < 3; round++) {
                List<String> titles = null;
                boolean any = false;
                for (Rule r : rules) {
                    if (r.maxTimes() > 0 && timesFired(r.name()) >= r.maxTimes()) continue;
                    String matched = null;
                    if (r.windowRule()) {
                        if (titles == null) titles = safeTitles(probe, log);
                        var p = TitlePattern.of(r.whenWindow());
                        for (String t : titles) if (p.test(t)) { matched = t; break; }
                        if (matched == null) continue;
                    } else {
                        Boolean present;
                        try { present = probe.elementPresent(r.session(), r.whenElement()); }
                        catch (RuntimeException e) { rethrowCancel(e); present = null; }
                        if (!Boolean.TRUE.equals(present)) continue;
                    }
                    fired.merge(r.name(), 1, Integer::sum);
                    handled.add(r.name());
                    any = true;
                    log.accept("Interrupt rule '" + r.name() + "': " + (matched != null ? "window '" + matched + "'" : "element " + r.whenElement()) + " appeared; responding with " + r.then()
                            + (r.target() == null ? "" : " " + r.target()));
                    try {
                        switch (r.then()) {
                            case "click" -> responder.click(r, matched);
                            case "press_keys" -> responder.pressKeys(r, matched);
                            case "close_window" -> responder.closeWindow(r, matched);
                            case "run_agent" -> responder.runAgent(r);
                            default -> { }
                        }
                    } catch (RuntimeException e) {
                        rethrowCancel(e);
                        log.accept("Interrupt rule '" + r.name() + "' could not respond: " + e.getMessage());
                    }
                }
                if (!any) break;
                timer.sleep(settleMs);
            }
        } finally { busy.set(false); }
        return handled;
    }

    private static List<String> safeTitles(Probe probe, Consumer<String> log) {
        try { return probe.windowTitles(); }
        catch (RuntimeException e) { rethrowCancel(e); log.accept("Interrupt rules: cannot list windows: " + e.getMessage()); return List.of(); }
    }

    private static void rethrowCancel(RuntimeException e) { if (e instanceof OoaException o && "RUN.CANCELLED".equals(o.code())) throw e; }
}
