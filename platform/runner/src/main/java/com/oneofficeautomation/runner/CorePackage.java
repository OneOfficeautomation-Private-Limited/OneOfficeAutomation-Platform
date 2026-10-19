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
    public static final Set<String> BUILTIN_ITERATORS = Set.of("times", "while", "list", "dictionary", "table_rows");

    private static Map<String, Object> interp() { throw new IllegalStateException("core actions are executed by the interpreter"); }

    @Action(id = "if", name = "If", category = "Logic", description = "Run the children when the condition is true; otherwise evaluate elseIf branches, then else.")
    public Map<String, Object> ifAction(ActionContext c,
            @Input(name = "condition", description = "Condition object, expression string or $reference.") Object condition) { return interp(); }

    @Action(id = "loop", name = "Loop", category = "Logic",
            description = "Repeat the children using an iterator: times, while, list, dictionary, table_rows or a package iterator (package/iterator).")
    public Map<String, Object> loop(ActionContext c,
            @Input(name = "iterator", type = OoaType.STRING, description = "Iterator kind or package/iterator address.", example = "list") String iterator,
            @Input(name = "over", required = false, description = "Collection for list, dictionary and table_rows iterators.") Object over,
            @Input(name = "count", type = OoaType.NUMBER, required = false, description = "Repetitions for the times iterator.") Object count,
            @Input(name = "condition", required = false, description = "Condition for the while iterator, checked before each pass.") Object cond,
            @Input(name = "iterator_args", type = OoaType.DICTIONARY, required = false, description = "Arguments for a package iterator.") Object args,
            @Input(name = "as", type = OoaType.STRING, required = false, description = "Declared variable that receives the current item.") String as,
            @Input(name = "index", type = OoaType.STRING, required = false, description = "Declared Number variable that receives the zero-based index.") String index,
            @Input(name = "max_iterations", type = OoaType.NUMBER, required = false, defaultValue = "100000",
                    description = "Safety limit; the loop fails with LOOP.LIMIT when reached.") Object max) { return interp(); }

    @Action(id = "try", name = "Try", category = "Error handling", description = "Run the children; on error run the first matching catch block, then always run finally.")
    public Map<String, Object> tryAction(ActionContext c) { return interp(); }

    @Action(id = "step", name = "Step", category = "Logic", description = "Named group of steps for readability; no runtime effect.")
    public Map<String, Object> step(ActionContext c,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Group title shown in Studio.") String title) { return interp(); }

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

    @Action(id = "delay", name = "Delay", category = "Runtime", description = "Pause the run for a number of seconds.")
    public Map<String, Object> delay(ActionContext c,
            @Input(name = "seconds", type = OoaType.NUMBER, description = "Seconds to wait.") Object seconds) { return interp(); }

    @Action(id = "wait_until", name = "Wait for condition", category = "Runtime", description = "Poll a condition until it is true or the timeout expires (WAIT.TIMEOUT).")
    public Map<String, Object> waitUntil(ActionContext c,
            @Input(name = "condition", description = "Condition to poll.") Object condition,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") Object timeout,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Interval between checks.") Object poll) { return interp(); }

    @Action(id = "throw", name = "Throw error", category = "Error handling", description = "Raise an error with a custom code that Try/Catch can match.")
    public Map<String, Object> throwAction(ActionContext c,
            @Input(name = "code", type = OoaType.STRING, description = "Error code, e.g. BUSINESS.INVOICE_DUPLICATE.") String code,
            @Input(name = "message", type = OoaType.STRING, description = "Error message.") String message,
            @Input(name = "suggested_fix", type = OoaType.STRING, required = false, description = "How to resolve the error.") String fix) { return interp(); }

    @Action(id = "comment", name = "Comment", category = "Logic", description = "Non-executing note.")
    public Map<String, Object> comment(ActionContext c,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Comment text.") String text) { return interp(); }

    @Action(id = "break", name = "Exit loop", category = "Logic", description = "Leave the innermost loop.")
    public Map<String, Object> breakAction(ActionContext c) { return interp(); }

    @Action(id = "continue", name = "Continue loop", category = "Logic", description = "Skip to the next pass of the innermost loop.")
    public Map<String, Object> continueAction(ActionContext c) { return interp(); }

    @Action(id = "run_bot", name = "Run Task Bot", category = "Task Bot", description = "Run a published child automation with mapped inputs and receive its outputs.")
    @Output(name = "outputs", type = OoaType.DICTIONARY, description = "The child's output variables.")
    @Output(name = "status", type = OoaType.STRING, description = "Child run status.")
    public Map<String, Object> runBot(ActionContext c,
            @Input(name = "automation", type = OoaType.STRING, description = "Id of the published automation.") String automation,
            @Input(name = "version", required = false, description = "Version number or 'production' (default).") Object version,
            @Input(name = "inputs", type = OoaType.DICTIONARY, required = false, description = "Values for the child's input variables.") Object inputs) { return interp(); }
}
