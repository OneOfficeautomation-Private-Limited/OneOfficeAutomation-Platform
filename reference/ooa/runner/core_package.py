"""The `core` package: control flow and runtime built-ins. It is a normal signed
package, so Studio renders its command panels from the same manifest format as
any other package; the interpreter executes these actions itself because they
need access to the execution tree (containers, scopes, child bots)."""
from __future__ import annotations

from ..sdk.package import Input, Output, action, package

CONTAINERS = {"if", "loop", "try", "step", "parallel"}
BUILTIN_ITERATORS = ("times", "while", "list", "dictionary", "table_rows")
# how core/switch compares its value with a case's values, and the condition operator that compares the same way
SWITCH_MATCHES = ("equals", "contains", "starts_with", "ends_with", "regex", "expression")
SWITCH_OPS = {"equals": "eq", "contains": "contains", "starts_with": "startsWith", "ends_with": "endsWith", "regex": "matches"}


def _interp(*_a, **_k):  # pragma: no cover - never called; interpreter dispatches core actions
    raise RuntimeError("core actions are executed by the interpreter")


@package("core", "Core", "1.0.0", description="Control flow, variables and runtime built-ins.")
class CorePackage:
    @action("if", "If", category="Logic", description="Run the steps inside when the condition is true; otherwise try each Else if, then Else.",
            inputs=[Input("condition", "Any", description="Condition object, expression string or $reference.")])
    def if_(self, ctx, **a): _interp()

    @action("switch", "Switch", category="Logic",
            description="Compare one value with each case in turn and run the steps of the first case that matches; Default runs when none does.",
            inputs=[Input("value", "Any", required=False, visible_when="match!=expression",
                          description="Value to compare with each case's values: text, a number, a $reference or an expression."),
                    Input("match", "String", required=False, default="equals", enum=SWITCH_MATCHES,
                          description="How a case value matches: equals (like ==), contains, starts_with, ends_with, regex, or expression (each case value is a condition)."),
                    Input("ignore_case", "Boolean", required=False, default=False, visible_when="match!=expression",
                          description="Compare text without upper/lower case.")])
    def switch(self, ctx, **a): _interp()

    @action("state_machine", "State machine", category="Logic",
            description="Move between named states: each state runs its steps, then the first transition whose condition holds takes the agent to the next state, until a final state."
                        "and moves to the next state; the step ends in a final state. Fails with STATE.NO_TRANSITION when a state that is not final has no true transition.",
            inputs=[Input("initial", "String", required=False, description="Name of the state to start in; the first state when empty."),
                    Input("max_transitions", "Number", required=False, default=1000,
                          description="Safety limit; the step fails with STATE.LIMIT when more transitions than this are taken.")])
    def state_machine(self, ctx, **a): _interp()

    @action("loop", "Loop", category="Logic",
            description="Repeat the steps inside: a number of times, while a condition holds, or once for each item, row, file, e-mail and so on.",
            inputs=[Input("iterator", "String", description="Iterator kind or package/iterator address.", example="list"),
                    Input("over", "Any", required=False, description="Collection for list, dictionary and table_rows iterators."),
                    Input("count", "Number", required=False, description="Repetitions for the times iterator."),
                    Input("condition", "Any", required=False, description="Condition for the while iterator, checked before each pass."),
                    Input("iterator_args", "Dictionary", required=False, description="Arguments for a package iterator."),
                    Input("as", "String", required=False, description="Declared variable that receives the current item."),
                    Input("index", "String", required=False, description="Declared Number variable that receives the zero-based index."),
                    Input("start", "Number", required=False, description="Times loops: the first value given to 'as' (0 by default)."),
                    Input("step", "Number", required=False, description="Times loops: how much 'as' grows each pass (1 by default)."),
                    Input("from_index", "Number", required=False, description="First position to visit (0-based)."),
                    Input("to_index", "Number", required=False, description="Last position to visit (0-based, included)."),
                    Input("max_iterations", "Number", required=False, default=100000, description="Safety limit; the loop fails with LOOP.LIMIT when reached.")])
    def loop(self, ctx, **a): _interp()

    @action("try", "Try", category="Error handling", description="Run the steps inside; if one fails, run the first Catch that takes the error, then always Finally.")
    def try_(self, ctx, **a): _interp()

    @action("step", "Step", category="Logic", description="A named group of steps that keeps the agent tidy; it changes nothing when the agent runs.",
            inputs=[Input("title", "String", required=False, description="Group title shown in Studio.")])
    def step(self, ctx, **a): _interp()

    @action("parallel", "Parallel", category="Logic", description="Run each step inside at the same time, and wait until all of them have finished.",
            inputs=[Input("max_concurrency", "Number", required=False, default=4, description="Maximum branches running at once.")])
    def parallel(self, ctx, **a): _interp()

    @action("set", "Assign variable", category="Variables", description="Put a value, or the result of a calculation, in a variable.",
            inputs=[Input("variable", "String", description="Name of a declared variable."),
                    Input("value", "Any", required=False, description="Value, $reference or {\"expr\": ...}.")])
    def set(self, ctx, **a): _interp()

    @action("set_many", "Assign several variables", category="Variables",
            description="Put values in several variables in one step, in order: a later value can use a variable set above it.",
            inputs=[Input("assignments", "List", description="Records with variable (a declared variable's name) and value (a value, $reference or {\"expr\": ...}).")])
    def set_many(self, ctx, **a): _interp()

    @action("log", "Log message", category="Runtime", description="Write a message to the run log.",
            inputs=[Input("message", "String", description="Text to log; ${...} interpolation allowed."),
                    Input("level", "String", required=False, default="info", enum=("debug", "info", "warning", "error"), description="Log level.")])
    def log(self, ctx, **a): _interp()

    @action("delay", "Delay", category="Runtime", description="Wait a fixed time, or a random time between two values, before the next step.",
            inputs=[Input("seconds", "Number", description="Seconds to wait.")])
    def delay(self, ctx, **a): _interp()

    @action("wait_until", "Wait for condition", category="Runtime", description="Wait until a condition is true, checking it again and again, up to a time limit.",
            inputs=[Input("condition", "Any", description="Condition to poll."),
                    Input("timeout_seconds", "Number", required=False, default=30, description="Maximum wait."),
                    Input("poll_seconds", "Number", required=False, default=1, description="Interval between checks.")])
    def wait_until(self, ctx, **a): _interp()

    @action("throw", "Throw error", category="Error handling", description="Raise an error with a code of your choice, for a Catch (or On error) to handle.",
            inputs=[Input("code", "String", description="Error code, e.g. BUSINESS.INVOICE_DUPLICATE."),
                    Input("message", "String", required=False, description="What went wrong, for the log and the Catch; the code when empty."),
                    Input("suggested_fix", "String", required=False, description="How to resolve the error.")])
    def throw(self, ctx, **a): _interp()

    @action("comment", "Comment", category="Logic", description="A note in the steps for people reading the agent; it does nothing when the agent runs.",
            inputs=[Input("text", "String", required=False, description="Comment text.")])
    def comment(self, ctx, **a): _interp()

    @action("break", "Exit loop", category="Logic", description="Leave the loop now and carry on after it; name a loop to leave an outer one.")
    def break_(self, ctx, **a): _interp()

    @action("continue", "Continue loop", category="Logic", description="Skip the rest of this pass and start the next pass of the loop; name a loop to continue an outer one.")
    def continue_(self, ctx, **a): _interp()

    @action("run_subflow", "Run subflow", category="Task Bot",
            description="Run one of this agent's subflows (a named list of steps kept beside the main steps), then carry on; it shares the agent's variables.",
            inputs=[Input("subflow", "String", description="Name of the subflow to run.")])
    def run_subflow(self, ctx, **a): _interp()

    @action("exit_subflow", "Exit subflow", category="Task Bot",
            description="Leave the subflow now; the agent carries on after the step that ran it (Finally steps still run).")
    def exit_subflow(self, ctx, **a): _interp()

    @action("label", "Label", category="Logic", description="A named place in the steps that Go to can jump to; it does nothing itself.",
            inputs=[Input("name", "String", description="Name of the place.")])
    def label(self, ctx, **a): _interp()

    @action("goto", "Go to", category="Logic",
            description="Jump to a Label in the same list of steps or one around it. Kept for agents moved from other tools; a Loop, If or subflow is easier to follow.",
            inputs=[Input("label", "String", description="Name of the Label to carry on from.")])
    def goto(self, ctx, **a): _interp()

    @action("run_bot", "Run Task Bot", category="Task Bot",
            description="Run another agent with the inputs you give it, and keep the outputs it gives back.",
            inputs=[Input("automation", "String", description="Id of the published automation."),
                    Input("version", "Any", required=False, description="Version number or 'production' (default)."),
                    Input("inputs", "Dictionary", required=False, description="Values for the child's input variables.")],
            outputs=[Output("outputs", "Dictionary", "The child's output variables."),
                     Output("status", "String", "Child run status.")])
    def run_bot(self, ctx, **a): _interp()
