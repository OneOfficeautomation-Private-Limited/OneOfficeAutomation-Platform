"""The `core` package: control flow and runtime built-ins. It is a normal signed
package, so Studio renders its command panels from the same manifest format as
any other package; the interpreter executes these actions itself because they
need access to the execution tree (containers, scopes, child bots)."""
from __future__ import annotations

from ..sdk.package import Input, Output, action, package

CONTAINERS = {"if", "loop", "try", "step", "parallel"}
BUILTIN_ITERATORS = ("times", "while", "list", "dictionary", "table_rows")


def _interp(*_a, **_k):  # pragma: no cover - never called; interpreter dispatches core actions
    raise RuntimeError("core actions are executed by the interpreter")


@package("core", "Core", "1.0.0", description="Control flow, variables and runtime built-ins.")
class CorePackage:
    @action("if", "If", category="Logic", description="Run the children when the condition is true; otherwise evaluate elseIf branches, then else.",
            inputs=[Input("condition", "Any", description="Condition object, expression string or $reference.")])
    def if_(self, ctx, **a): _interp()

    @action("loop", "Loop", category="Logic",
            description="Repeat the children using an iterator: times, while, list, dictionary, table_rows or a package iterator (package/iterator).",
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

    @action("try", "Try", category="Error handling", description="Run the children; on error run the first matching catch block, then always run finally.")
    def try_(self, ctx, **a): _interp()

    @action("step", "Step", category="Logic", description="Named group of steps for readability; no runtime effect.",
            inputs=[Input("title", "String", required=False, description="Group title shown in Studio.")])
    def step(self, ctx, **a): _interp()

    @action("parallel", "Parallel", category="Logic", description="Run each child step as a concurrent branch and wait for all branches.",
            inputs=[Input("max_concurrency", "Number", required=False, default=4, description="Maximum branches running at once.")])
    def parallel(self, ctx, **a): _interp()

    @action("set", "Assign variable", category="Variables", description="Assign a value or expression result to a declared variable.",
            inputs=[Input("variable", "String", description="Name of a declared variable."),
                    Input("value", "Any", required=False, description="Value, $reference or {\"expr\": ...}.")])
    def set(self, ctx, **a): _interp()

    @action("log", "Log message", category="Runtime", description="Write a message to the run log.",
            inputs=[Input("message", "String", description="Text to log; ${...} interpolation allowed."),
                    Input("level", "String", required=False, default="info", enum=("debug", "info", "warning", "error"), description="Log level.")])
    def log(self, ctx, **a): _interp()

    @action("delay", "Delay", category="Runtime", description="Pause the run for a number of seconds.",
            inputs=[Input("seconds", "Number", description="Seconds to wait.")])
    def delay(self, ctx, **a): _interp()

    @action("wait_until", "Wait for condition", category="Runtime", description="Poll a condition until it is true or the timeout expires (WAIT.TIMEOUT).",
            inputs=[Input("condition", "Any", description="Condition to poll."),
                    Input("timeout_seconds", "Number", required=False, default=30, description="Maximum wait."),
                    Input("poll_seconds", "Number", required=False, default=1, description="Interval between checks.")])
    def wait_until(self, ctx, **a): _interp()

    @action("throw", "Throw error", category="Error handling", description="Raise an error with a custom code that Try/Catch can match.",
            inputs=[Input("code", "String", description="Error code, e.g. BUSINESS.INVOICE_DUPLICATE."),
                    Input("message", "String", description="Error message."),
                    Input("suggested_fix", "String", required=False, description="How to resolve the error.")])
    def throw(self, ctx, **a): _interp()

    @action("comment", "Comment", category="Logic", description="Non-executing note.",
            inputs=[Input("text", "String", required=False, description="Comment text.")])
    def comment(self, ctx, **a): _interp()

    @action("break", "Exit loop", category="Logic", description="Leave the innermost loop.")
    def break_(self, ctx, **a): _interp()

    @action("continue", "Continue loop", category="Logic", description="Skip to the next pass of the innermost loop.")
    def continue_(self, ctx, **a): _interp()

    @action("run_bot", "Run Task Bot", category="Task Bot",
            description="Run a published child automation with mapped inputs and receive its outputs.",
            inputs=[Input("automation", "String", description="Id of the published automation."),
                    Input("version", "Any", required=False, description="Version number or 'production' (default)."),
                    Input("inputs", "Dictionary", required=False, description="Values for the child's input variables.")],
            outputs=[Output("outputs", "Dictionary", "The child's output variables."),
                     Output("status", "String", "Child run status.")])
    def run_bot(self, ctx, **a): _interp()
