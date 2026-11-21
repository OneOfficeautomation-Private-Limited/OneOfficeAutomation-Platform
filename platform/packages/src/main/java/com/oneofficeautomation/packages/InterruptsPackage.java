package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.InterruptRules;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Interrupt handlers for UI agents: rules that answer unexpected pop-ups (a window or an element appearing) by
 * clicking, pressing keys, closing the window or running a child agent. Desktop, window and browser actions check
 * the rules before they act and again when an element is not found.
 */
@OoaPackage(id = "ooa.interrupts", name = "Interrupt handlers", version = "1.0.0",
        description = "Rules that deal with unexpected windows and pop-ups during UI automation, checked before each UI action.")
public final class InterruptsPackage {

    @Action(id = "add_rule", name = "Add interrupt rule", category = "Interrupts",
            description = "Add (or replace by name) a rule for this run: when a window or element appears, or a condition becomes true, click, press keys, close the window or run a child agent.",
            panel = "name, (When: A window appears = when_window / An element appears = when_element, browser_session / A condition is true = when_condition), then, target, inputs | max_times")
    public Map<String, Object> addRule(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, example = "Session expired", description = "Rule name; adding a rule with the same name replaces it.") String name,
            @Input(name = "when_window", type = OoaType.STRING, required = false, example = "*Session expired*",
                    description = "Window title pattern: text the title contains, a wildcard pattern with * and ?, or re: followed by a regular expression.") String whenWindow,
            @Input(name = "when_element", type = OoaType.STRING, required = false, example = "#cookie-banner",
                    description = "Element selector that marks the pop-up, looked for in the browser session below.") String whenElement,
            @Input(name = "browser_session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Browser session for element rules.") String session,
            @Input(name = "then", type = OoaType.STRING, enumValues = {"click", "press_keys", "close_window", "run_agent", "ignore"},
                    description = "Response: click the target, press the target keys, close the matched window, run the target agent, or only log it.") String then,
            @Input(name = "target", type = OoaType.STRING, required = false,
                    visibleWhen = "then=click|press_keys|run_agent", description = "For click: a selector in the browser session or x,y screen coordinates; for press_keys: keys such as ENTER or ALT+F4; for run_agent: the agent id.") String target,
            @Input(name = "inputs", type = OoaType.DICTIONARY, required = false, visibleWhen = "then=run_agent", description = "Inputs for the child agent (run_agent).") Map<String, Object> inputs,
            @Input(name = "max_times", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Respond at most this many times in the run; 0 means no limit.") int maxTimes,
            @Input(name = "when_condition", required = false,
                    description = "Any condition, as for If (a toolkit's condition such as Image found), that marks the interruption; variables in it are read when the rule is added (Watch for interruptions reads them each time).") Object whenCondition) {
        InterruptRules.of(ctx).add(new InterruptRules.Rule(name, whenWindow, whenElement, session, then, target, inputs, maxTimes, whenCondition));
        ctx.info("Interrupt rule '" + name + "' is active for this run");
        return Map.of();
    }

    public Map<String, Object> addRule(ActionContext ctx, String name, String whenWindow, String whenElement, String session, String then, String target,
                                       Map<String, Object> inputs, int maxTimes) {
        return addRule(ctx, name, whenWindow, whenElement, session, then, target, inputs, maxTimes, null);
    }

    @Action(id = "remove_rule", name = "Remove interrupt rule", category = "Interrupts", description = "Remove one interrupt rule by name.",
            panel = "name")
    @Output(name = "removed", type = OoaType.BOOLEAN, description = "True when a rule with that name existed.")
    public Map<String, Object> removeRule(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Rule name.") String name) {
        InterruptRules r = InterruptRules.existing(ctx);
        return Map.of("removed", r != null && r.remove(name));
    }

    @Action(id = "clear_rules", name = "Clear interrupt rules", category = "Interrupts", description = "Remove every interrupt rule from this run.")
    public Map<String, Object> clearRules(ActionContext ctx) {
        InterruptRules r = InterruptRules.existing(ctx);
        if (r != null) r.clear();
        return Map.of();
    }

    @Action(id = "list_rules", name = "List interrupt rules", category = "Interrupts", idempotent = true, description = "Return the active rules and how often each has responded.")
    @Output(name = "rules", type = OoaType.LIST, description = "One dictionary per rule.")
    public Map<String, Object> listRules(ActionContext ctx) {
        InterruptRules r = InterruptRules.existing(ctx);
        return Map.of("rules", r == null ? List.of() : r.describe());
    }

    @Action(id = "check_now", name = "Check interrupt rules now", category = "Interrupts", capabilities = "desktop",
            description = "Check the rules immediately, for example after a step that often raises a pop-up, and respond to any that match.")
    @Output(name = "handled", type = OoaType.LIST, description = "Names of the rules that responded.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many rules responded.")
    public Map<String, Object> checkNow(ActionContext ctx) {
        List<String> handled = UiGuard.before(ctx);
        return Map.of("handled", new ArrayList<Object>(handled), "count", BigDecimal.valueOf(handled.size()));
    }
}
