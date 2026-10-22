package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;

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

    private static Map<String, Object> interp() { throw new IllegalStateException("core actions are executed by the interpreter"); }

    @Action(id = "if", name = "If", category = "Logic", description = "Run the children when the condition is true; otherwise evaluate elseIf branches, then else.")
    public Map<String, Object> ifAction(ActionContext c,
            @Input(name = "condition", description = "Condition object, expression string or $reference.") Object condition) { return interp(); }

    @Action(id = "loop", name = "Loop", category = "Logic",
            description = "Repeat the children using an iterator: times, while, list, dictionary, dictionary_keys, dictionary_values, table_rows or a toolkit iterator (package/iterator).")
    public Map<String, Object> loop(ActionContext c,
            @Input(name = "iterator", type = OoaType.STRING, description = "Iterator kind or package/iterator address.", example = "list") String iterator,
            @Input(name = "over", required = false, description = "Collection for list, dictionary and table_rows iterators.") Object over,
            @Input(name = "count", type = OoaType.NUMBER, required = false, description = "Repetitions for the times iterator.") Object count,
            @Input(name = "condition", required = false, description = "Condition for the while iterator, checked before each pass.") Object cond,
            @Input(name = "iterator_args", type = OoaType.DICTIONARY, required = false, description = "Arguments for a toolkit iterator.") Object args,
            @Input(name = "as", type = OoaType.STRING, required = false, description = "Declared variable that receives the current item.") String as,
            @Input(name = "index", type = OoaType.STRING, required = false, description = "Declared Number variable that receives the zero-based index.") String index,
            @Input(name = "max_iterations", type = OoaType.NUMBER, required = false, defaultValue = "100000",
                    description = "Safety limit; the loop fails with LOOP.LIMIT when reached.") Object max,
            @Input(name = "from_index", type = OoaType.NUMBER, required = false,
                    description = "First zero-based item or row to visit (list, dictionary, table and toolkit iterators); from the first when empty.") Object fromIndex,
            @Input(name = "to_index", type = OoaType.NUMBER, required = false,
                    description = "Last zero-based item or row to visit, included; up to the last when empty.") Object toIndex,
            @Input(name = "step", type = OoaType.NUMBER, required = false, defaultValue = "1",
                    description = "Visit every n-th item; for the times iterator, the amount the counter grows each pass.") Object step,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0",
                    description = "First counter value for the times iterator.") Object start,
            @Input(name = "reverse", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Visit the items in reverse order (list, dictionary, table and toolkit iterators).") Object reverse,
            @Input(name = "check_at_end", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "While iterator: check the condition after each pass, so the children run at least once.") Object checkAtEnd,
            @Input(name = "label", type = OoaType.STRING, required = false, pattern = "^[A-Za-z0-9_]+$",
                    description = "Name of this loop, so Exit loop and Continue loop inside nested loops can target it.") String label) { return interp(); }

    @Action(id = "try", name = "Try", category = "Error handling", description = "Run the children; on error run the first matching catch block, then always run finally.")
    public Map<String, Object> tryAction(ActionContext c) { return interp(); }

    @Action(id = "step", name = "Step", category = "Logic", description = "Named group of steps for readability; no runtime effect.")
    public Map<String, Object> step(ActionContext c,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Group title shown in Agent Studio.") String title) { return interp(); }

    @Action(id = "parallel", name = "Parallel", category = "Logic", description = "Run each child step as a concurrent branch and wait for all branches.")
    public Map<String, Object> parallel(ActionContext c,
            @Input(name = "max_concurrency", type = OoaType.NUMBER, required = false, defaultValue = "4", description = "Maximum branches running at once.") Object n) { return interp(); }

    @Action(id = "set", name = "Assign variable", category = "Variables", description = "Assign a value or expression result to a declared variable.")
    public Map<String, Object> set(ActionContext c,
            @Input(name = "variable", type = OoaType.STRING, description = "Name of a declared variable.") String variable,
            @Input(name = "value", required = false, description = "Value, $reference or {\"expr\": ...}.") Object value) { return interp(); }

    @Action(id = "log", name = "Log message", category = "Runtime", description = "Write a message to the run log.")
    public Map<String, Object> log(ActionContext c,
            @Input(name = "message", type = OoaType.STRING, description = "Text to log; ${...} interpolation allowed.") String message,
            @Input(name = "level", type = OoaType.STRING, required = false, defaultValue = "\"info\"", enumValues = {"debug", "info", "warning", "error"},
                    description = "Log level.") String level) { return interp(); }

    @Action(id = "delay", name = "Delay", category = "Runtime", description = "Pause the run for a fixed time, or for a random time within a range.")
    public Map<String, Object> delay(ActionContext c,
            @Input(name = "seconds", type = OoaType.NUMBER, description = "Time to wait (in the chosen unit); the low end of the range for a random delay.") Object seconds,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"seconds\"", enumValues = {"seconds", "milliseconds"},
                    description = "Unit of seconds and random_max.") String unit,
            @Input(name = "random_max", type = OoaType.NUMBER, required = false,
                    description = "High end of a random delay; each run waits a random time between seconds and this value.") Object randomMax) { return interp(); }

    @Action(id = "wait_until", name = "Wait for condition", category = "Runtime", description = "Poll a condition until it is true or the timeout expires (WAIT.TIMEOUT).")
    @Output(name = "met", type = OoaType.BOOLEAN, description = "True when the condition became true before the timeout.")
    public Map<String, Object> waitUntil(ActionContext c,
            @Input(name = "condition", description = "Condition to poll.") Object condition,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") Object timeout,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Interval between checks.") Object poll,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Fail with WAIT.TIMEOUT when the time runs out; when off, carry on and set met to false.") Object failOnTimeout) { return interp(); }

    @Action(id = "throw", name = "Throw error", category = "Error handling", description = "Raise an error with a custom code that Try/Catch can match.")
    public Map<String, Object> throwAction(ActionContext c,
            @Input(name = "code", type = OoaType.STRING, description = "Error code, e.g. BUSINESS.INVOICE_DUPLICATE.") String code,
            @Input(name = "message", type = OoaType.STRING, description = "Error message.") String message,
            @Input(name = "suggested_fix", type = OoaType.STRING, required = false, description = "How to resolve the error.") String fix,
            @Input(name = "details", type = OoaType.DICTIONARY, required = false, description = "Extra values carried with the error, readable in Catch.") Object details,
            @Input(name = "retryable", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark the error as temporary so retry policies try the step again.") Object retryable) { return interp(); }

    @Action(id = "comment", name = "Comment", category = "Logic", description = "Non-executing note.")
    public Map<String, Object> comment(ActionContext c,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Comment text.") String text) { return interp(); }

    @Action(id = "break", name = "Exit loop", category = "Logic", description = "Leave the innermost loop, or the enclosing loop with the given label.")
    public Map<String, Object> breakAction(ActionContext c,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Label of the loop to leave; the innermost loop when empty.") String label) { return interp(); }

    @Action(id = "continue", name = "Continue loop", category = "Logic", description = "Skip to the next pass of the innermost loop, or of the enclosing loop with the given label.")
    public Map<String, Object> continueAction(ActionContext c,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Label of the loop to continue; the innermost loop when empty.") String label) { return interp(); }

    @Action(id = "stop", name = "Stop agent", category = "Agent", description = "End the current agent at once; Finally blocks still run and the run ends with the chosen status.")
    public Map<String, Object> stop(ActionContext c,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Reason written to the run log.") String message,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"success\"", enumValues = {"success", "failed"},
                    description = "success ends the agent normally; failed ends it with error AGENT.STOPPED.") String status) { return interp(); }

    @Action(id = "pause", name = "Pause agent", category = "Agent", description = "Pause the run until someone resumes it from Agent Studio or Command Center, or until the optional timeout.")
    public Map<String, Object> pause(ActionContext c,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Why the run is waiting; shown with the paused run.") String message,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, description = "Resume by itself after this many seconds; waits until resumed when empty.") Object timeout) { return interp(); }

    @Action(id = "run_bot", name = "Run Agent", category = "Agent", description = "Run a published child automation with mapped inputs and receive its outputs.")
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
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false,
                    description = "Stop the child with CHILD.TIMEOUT when one run takes longer than this; no limit when empty.") Object timeout,
            @Input(name = "same_session", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Share this agent's open sessions (workbooks, browsers, connections) with the child; sessions the child opens stay open for this agent.") Object sameSession,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Fail this step when the child fails; when off, set status to failed and the error output and carry on.") Object failOnError,
            @Input(name = "repeat", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Run the child this many times in a row.") Object repeat,
            @Input(name = "repeat_delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Wait between repeated runs.") Object repeatDelay) { return interp(); }
}
