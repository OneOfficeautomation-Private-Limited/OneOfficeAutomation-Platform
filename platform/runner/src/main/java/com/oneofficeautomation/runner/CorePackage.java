package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "core" package: control flow and runtime built-ins. A normal signed package, so Studio renders its
 * command panels from the same manifest format; the interpreter executes these actions itself because
 * they need the execution tree.
 */
@OoaPackage(id = "core", name = "Core", version = "1.0.0", description = "Control flow, variables and runtime built-ins.")
public final class CorePackage {
    public static final Set<String> CONTAINERS = Set.of("if", "loop", "try", "step", "parallel");
    public static final Set<String> BUILTIN_ITERATORS = Set.of("times", "while", "list", "dictionary", "dictionary_keys", "dictionary_values", "table_rows");

    /** How core/switch compares its value with a case's values; "expression" makes each case value a condition. */
    public static final List<String> SWITCH_MATCHES = List.of("equals", "contains", "starts_with", "ends_with", "regex", "expression");

    /** The condition operator a switch match kind compares with (the same rules as If's equals, contains ...). */
    static String switchOperator(String match) {
        return switch (match) { case "contains" -> "contains"; case "starts_with" -> "startsWith"; case "ends_with" -> "endsWith"; case "regex" -> "matches"; default -> "eq"; };
    }

    private static Map<String, Object> interp() { throw new IllegalStateException("core actions are executed by the interpreter"); }

    @Action(id = "if", name = "If", category = "Logic", description = "Run the steps inside when the condition is true; otherwise try each Else if, then Else.")
    public Map<String, Object> ifAction(ActionContext c,
            @Input(name = "condition", description = "Condition object, expression string or $reference.") Object condition) { return interp(); }

    @Action(id = "switch", name = "Switch", category = "Logic",
            description = "Compare one value with each case in turn and run the steps of the first case that matches; Default runs when none does.")
    public Map<String, Object> switchAction(ActionContext c,
            @Input(name = "value", required = false, visibleWhen = "match!=expression",
                    description = "Value to compare with each case's values: text, a number, a $reference or an expression.") Object value,
            @Input(name = "match", type = OoaType.STRING, required = false, defaultValue = "\"equals\"",
                    enumValues = {"equals", "contains", "starts_with", "ends_with", "regex", "expression"},
                    description = "How a case value matches: equals (like ==, so 5 and \"5\" match), contains, starts_with, ends_with, regex (the case value is a pattern), "
                            + "or expression (each case value is a condition and the value is not used).") String match,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "match!=expression",
                    description = "Compare text without upper/lower case.") Object ignoreCase) { return interp(); }

    @Action(id = "state_machine", name = "State machine", category = "Logic",
            description = "Move between named states: each state runs its steps, then the first transition whose condition holds takes the agent to the next state, until a final state."
                    + "and moves to the next state; the step ends in a final state. Fails with STATE.NO_TRANSITION when a state that is not final has no true transition.")
    public Map<String, Object> stateMachine(ActionContext c,
            @Input(name = "initial", type = OoaType.STRING, required = false,
                    description = "Name of the state to start in; the first state when empty.") String initial,
            @Input(name = "max_transitions", type = OoaType.NUMBER, required = false, defaultValue = "1000", min = 1,
                    description = "Safety limit; the step fails with STATE.LIMIT when more transitions than this are taken.") Object max) { return interp(); }

    @Action(id = "loop", name = "Loop", category = "Logic",
            description = "Repeat the steps inside: a number of times, while a condition holds, or once for each item, row, file, e-mail and so on.")
    public Map<String, Object> loop(ActionContext c,
            @Input(name = "iterator", type = OoaType.STRING, description = "Iterator kind or package/iterator address.", example = "list") String iterator,
            @Input(name = "over", required = false, visibleWhen = "iterator=list|dictionary|dictionary_keys|dictionary_values|table_rows", description = "Collection for list, dictionary and table_rows iterators.") Object over,
            @Input(name = "count", type = OoaType.NUMBER, required = false, visibleWhen = "iterator=times", min = 0, description = "Repetitions for the times iterator.") Object count,
            @Input(name = "condition", required = false, visibleWhen = "iterator=while", description = "Condition for the while iterator, checked before each pass.") Object cond,
            @Input(name = "iterator_args", type = OoaType.DICTIONARY, required = false,
                    visibleWhen = "iterator!=times|while|list|dictionary|dictionary_keys|dictionary_values|table_rows", description = "Arguments for a toolkit iterator.") Object args,
            @Input(name = "as", type = OoaType.STRING, required = false, description = "Declared variable that receives the current item.") String as,
            @Input(name = "index", type = OoaType.STRING, required = false, description = "Declared Number variable that receives the zero-based index.") String index,
            @Input(name = "columns_to", type = OoaType.DICTIONARY, required = false, visibleWhen = "iterator!=times|while",
                    description = "Column name to a declared variable: each pass puts that column of the current row (or key of the current record) in the variable.") Object columnsTo,
            @Input(name = "max_iterations", type = OoaType.NUMBER, required = false, defaultValue = "100000", min = 1,
                    description = "Safety limit; the loop fails with LOOP.LIMIT when reached.") Object max,
            @Input(name = "from_index", type = OoaType.NUMBER, required = false, visibleWhen = "iterator!=times|while", min = 0,
                    description = "First zero-based item or row to visit (list, dictionary, table and toolkit iterators); from the first when empty.") Object fromIndex,
            @Input(name = "to_index", type = OoaType.NUMBER, required = false, visibleWhen = "iterator!=times|while", min = 0,
                    description = "Last zero-based item or row to visit, included; up to the last when empty.") Object toIndex,
            @Input(name = "step", type = OoaType.NUMBER, required = false, defaultValue = "1", visibleWhen = "iterator!=while",
                    description = "Visit every n-th item; for the times iterator, the amount the counter grows each pass.") Object step,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0", visibleWhen = "iterator=times",
                    description = "First counter value for the times iterator.") Object start,
            @Input(name = "reverse", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "iterator!=times|while",
                    description = "Visit the items in reverse order (list, dictionary, table and toolkit iterators).") Object reverse,
            @Input(name = "check_at_end", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "iterator=while",
                    description = "While iterator: check the condition after each pass, so the children run at least once.") Object checkAtEnd,
            @Input(name = "label", type = OoaType.STRING, required = false, pattern = "^[A-Za-z0-9_]+$",
                    description = "Name of this loop, so Exit loop and Continue loop inside nested loops can target it.") String label) { return interp(); }

    @Action(id = "try", name = "Try", category = "Error handling", description = "Run the steps inside; if one fails, run the first Catch that takes the error, then always Finally.")
    public Map<String, Object> tryAction(ActionContext c) { return interp(); }

    @Action(id = "step", name = "Step", category = "Logic", description = "A named group of steps that keeps the agent tidy; it changes nothing when the agent runs.")
    public Map<String, Object> step(ActionContext c,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Group title shown in OneAgentStudio.") String title) { return interp(); }

    @Action(id = "parallel", name = "Parallel", category = "Logic", description = "Run each step inside at the same time, and wait until all of them have finished.")
    public Map<String, Object> parallel(ActionContext c,
            @Input(name = "max_concurrency", type = OoaType.NUMBER, required = false, defaultValue = "4", min = 1, max = 64, description = "Maximum branches running at once.") Object n) { return interp(); }

    @Action(id = "set", name = "Assign variable", category = "Variables", description = "Put a value, or the result of a calculation, in a variable.")
    public Map<String, Object> set(ActionContext c,
            @Input(name = "variable", type = OoaType.STRING, description = "Name of a declared variable.") String variable,
            @Input(name = "value", required = false, description = "Value, $reference or {\"expr\": ...}.") Object value) { return interp(); }

    @Action(id = "set_many", name = "Assign several variables", category = "Variables",
            description = "Put values in several variables in one step, in order: a later value can use a variable set above it.")
    public Map<String, Object> setMany(ActionContext c,
            @Input(name = "assignments", type = OoaType.LIST, example = "[{\"variable\": \"total\", \"value\": 0}, {\"variable\": \"status\", \"value\": \"new\"}]",
                    description = "Records with variable (a declared variable's name) and value (a value, $reference or {\"expr\": ...}).") Object assignments) { return interp(); }

    @Action(id = "log", name = "Log message", category = "Runtime", description = "Write a message to the run log.")
    public Map<String, Object> log(ActionContext c,
            @Input(name = "message", type = OoaType.STRING, description = "Text to log; ${...} interpolation allowed.") String message,
            @Input(name = "level", type = OoaType.STRING, required = false, defaultValue = "\"info\"", enumValues = {"debug", "info", "warning", "error"},
                    description = "Log level.") String level) { return interp(); }

    @Action(id = "delay", name = "Delay", category = "Runtime", description = "Wait a fixed time, or a random time between two values, before the next step.")
    public Map<String, Object> delay(ActionContext c,
            @Input(name = "seconds", type = OoaType.NUMBER, min = 0, description = "Time to wait (in the chosen unit); the low end of the range for a random delay.") Object seconds,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"seconds\"", enumValues = {"seconds", "milliseconds", "minutes"},
                    description = "Unit of seconds and random_max.") String unit,
            @Input(name = "random_max", type = OoaType.NUMBER, required = false, min = 0,
                    description = "High end of a random delay; each run waits a random time between seconds and this value.") Object randomMax) { return interp(); }

    @Action(id = "wait_until", name = "Wait for condition", category = "Runtime", description = "Wait until a condition is true, checking it again and again, up to a time limit.")
    @Output(name = "met", type = OoaType.BOOLEAN, description = "True when the condition became true before the timeout.")
    public Map<String, Object> waitUntil(ActionContext c,
            @Input(name = "condition", description = "Condition to poll.") Object condition,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", min = 0, description = "Maximum wait.") Object timeout,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 0.05, description = "Interval between checks.") Object poll,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Fail with WAIT.TIMEOUT when the time runs out; when off, carry on and set met to false.") Object failOnTimeout) { return interp(); }

    @Action(id = "throw", name = "Throw error", category = "Error handling", description = "Raise an error with a code of your choice, for a Catch (or On error) to handle.")
    public Map<String, Object> throwAction(ActionContext c,
            @Input(name = "code", type = OoaType.STRING, description = "Error code, e.g. BUSINESS.INVOICE_DUPLICATE.") String code,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "What went wrong, for the log and the Catch; the code when empty.") String message,
            @Input(name = "suggested_fix", type = OoaType.STRING, required = false, description = "How to resolve the error.") String fix,
            @Input(name = "details", type = OoaType.DICTIONARY, required = false, description = "Extra values carried with the error, readable in Catch.") Object details,
            @Input(name = "retryable", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark the error as temporary so retry policies try the step again.") Object retryable) { return interp(); }

    @Action(id = "comment", name = "Comment", category = "Logic", description = "A note in the steps for people reading the agent; it does nothing when the agent runs.")
    public Map<String, Object> comment(ActionContext c,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Comment text.") String text) { return interp(); }

    @Action(id = "break", name = "Exit loop", category = "Logic", description = "Leave the loop now and carry on after it; name a loop to leave an outer one.")
    public Map<String, Object> breakAction(ActionContext c,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Label of the loop to leave; the innermost loop when empty.") String label) { return interp(); }

    @Action(id = "continue", name = "Continue loop", category = "Logic", description = "Skip the rest of this pass and start the next pass of the loop; name a loop to continue an outer one.")
    public Map<String, Object> continueAction(ActionContext c,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Label of the loop to continue; the innermost loop when empty.") String label) { return interp(); }

    @Action(id = "stop", name = "Stop agent", category = "Agent", description = "End the agent now with the status you choose; Finally steps still run.")
    public Map<String, Object> stop(ActionContext c,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Reason written to the run log.") String message,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"success\"", enumValues = {"success", "failed"},
                    description = "success ends the agent normally; failed ends it with error AGENT.STOPPED.") String status) { return interp(); }

    @Action(id = "run_subflow", name = "Run subflow", category = "Agent",
            description = "Run one of this agent's subflows (a named list of steps kept beside the main steps), then carry on; it shares the agent's variables.")
    public Map<String, Object> runSubflow(ActionContext c,
            @Input(name = "subflow", type = OoaType.STRING, description = "Name of the subflow to run.") String subflow) { return interp(); }

    @Action(id = "exit_subflow", name = "Exit subflow", category = "Agent",
            description = "Leave the subflow now; the agent carries on after the step that ran it (Finally steps still run).")
    public Map<String, Object> exitSubflow(ActionContext c) { return interp(); }

    @Action(id = "label", name = "Label", category = "Logic", description = "A named place in the steps that Go to can jump to; it does nothing itself.")
    public Map<String, Object> label(ActionContext c,
            @Input(name = "name", type = OoaType.STRING, pattern = "^[A-Za-z0-9_ -]+$", description = "Name of the place, unique among the labels Go to steps can see.") String name) { return interp(); }

    @Action(id = "goto", name = "Go to", category = "Logic",
            description = "Jump to a Label in the same list of steps or one around it. Kept for agents moved from other tools; a Loop, If or subflow is easier to follow.")
    public Map<String, Object> gotoAction(ActionContext c,
            @Input(name = "label", type = OoaType.STRING, description = "Name of the Label to carry on from.") String label) { return interp(); }

    @Action(id = "pause", name = "Pause agent", category = "Agent", description = "Stop and wait until someone presses Resume in OneAgentStudio or One CommandCenter, or until the time limit.")
    public Map<String, Object> pause(ActionContext c,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Why the run is waiting; shown with the paused run.") String message,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, min = 0, description = "Resume by itself after this many seconds; waits until resumed when empty.") Object timeout) { return interp(); }

    @Action(id = "run_bot", name = "Run Agent", category = "Agent", description = "Run another agent with the inputs you give it, and keep the outputs it gives back.")
    @Output(name = "outputs", type = OoaType.DICTIONARY, description = "The child's output variables (of the last run when repeated).")
    @Output(name = "status", type = OoaType.STRING, description = "Child run status: success or failed.")
    @Output(name = "error", type = OoaType.DICTIONARY, description = "The child's error (code, message, stepId) when it failed and fail_on_error is off; empty otherwise.")
    @Output(name = "runs", type = OoaType.NUMBER, description = "How many times the child ran.")
    public Map<String, Object> runBot(ActionContext c,
            @Input(name = "automation", type = OoaType.STRING, description = "Id of the published automation.") String automation,
            @Input(name = "version", required = false, description = "Version number or 'production' (default).") Object version,
            @Input(name = "inputs", type = OoaType.DICTIONARY, required = false, description = "Values for the child's input variables.") Object inputs,
            @Input(name = "output_map", type = OoaType.DICTIONARY, required = false,
                    description = "Child output name to the variable of this agent that receives it, e.g. {\"total\": \"invoice_total\"}.") Object outputMap,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, min = 0,
                    description = "Stop the child with CHILD.TIMEOUT when one run takes longer than this; no limit when empty.") Object timeout,
            @Input(name = "same_session", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Share this agent's open sessions (workbooks, browsers, connections) with the child; sessions the child opens stay open for this agent.") Object sameSession,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Fail this step when the child fails; when off, set status to failed and the error output and carry on.") Object failOnError,
            @Input(name = "repeat", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "Run the child this many times in a row.") Object repeat,
            @Input(name = "repeat_delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, visibleWhen = "repeat!=1", description = "Wait between repeated runs.") Object repeatDelay) { return interp(); }
}
