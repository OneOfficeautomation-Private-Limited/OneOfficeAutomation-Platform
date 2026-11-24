"""Cases for the Scripts group: the samples (fuller converter) and cases that run real Python, PowerShell, JavaScript
(Node.js) and VBScript code, one-off and in sessions."""
from helpers_core import resample

PY_FILE = "def net(gross, rate=0.2):\n    return round(gross / (1 + rate), 2)\n"


def add(ua):
    resample(ua)
    for n, t in (("sc_out", "String"), ("sc_json", "Any"), ("sc_code", "Number"), ("sc_result", "Any"),
                 ("sc_err", "String")):
        ua.var(n, t)

    with ua.case("ooa.script/run_python", "arguments in, JSON out",
                 "Run Python runs a Python 3 script with arguments; printed JSON comes back as a value too. Checks: "
                 "exit code 0, the printed text and the JSON.") as f:
        f.act("ooa.script/run_python", desc="Add the two arguments and print JSON",
              script="import sys, json\nprint(json.dumps({'sum': int(sys.argv[1]) + int(sys.argv[2])}))",
              arguments=["40", "2"], out={"output": "sc_out", "json": "sc_json", "exit_code": "sc_code"})
        ua.check_eq("sc_code", 0)
        ua.check("contains", "$sc_out$", "42", what="sc_out")
        ua.check_eq("sc_json", {"sum": 42})

    with ua.case("ooa.script/run_python_function", "a function in a .py file",
                 "Run Python function calls one function of a Python file with arguments and gives back what it "
                 "returns. Checks: net of 120 at 20% is 100.") as f:
        f.act("ooa.file/write_text", desc="A Python file with a function", path="ut_tmp/py/tax.py", text=PY_FILE)
        f.act("ooa.script/run_python_function", desc="Call net(120)", file="ut_tmp/py/tax.py", function="net",
              arguments=[120], out={"result": "sc_result"})
        ua.check_eq("sc_result", 100)

    with ua.case("ooa.script/run_javascript", "Node.js prints JSON",
                 "Run JavaScript runs a script with Node.js (or JScript without it). Checks: exit code 0 and the "
                 "printed JSON as a value.") as f:
        f.act("ooa.script/run_javascript", desc="Print a JSON object",
              script="console.log(JSON.stringify({items: [1, 2, 3].length}))",
              out={"json": "sc_json", "exit_code": "sc_code"})
        ua.check_eq("sc_code", 0)
        ua.check_eq("sc_json", {"items": 3})

    with ua.case("ooa.script/run_javascript_function", "a function in a .js file",
                 "Run JavaScript function calls one function of a JavaScript file with arguments and gives back its "
                 "result. Checks: add(2, 3) is 5.") as f:
        f.act("ooa.file/write_text", desc="A JavaScript file with a function", path="ut_tmp/js/calc.js",
              text="function add(a, b) { return a + b; }\nmodule.exports = { add };\n")
        f.act("ooa.script/run_javascript_function", desc="Call add(2, 3)", file="ut_tmp/js/calc.js", function="add",
              arguments=[2, 3], out={"result": "sc_result"})
        ua.check_eq("sc_result", 5)

    # ---- sessions ----
    with ua.case("ooa.script/open_session", "a Python session keeps what one step defines",
                 "Open session starts Python and keeps it running: what one step defines is there for the next. "
                 "Checks: a variable set in one Run in session is read by the next.") as f:
        f.act("ooa.script/open_session", desc="Start a Python session", language="python", session="ut_py")
        f.act("ooa.script/run_in_session", desc="Define a rate", session="ut_py", script="rate = 0.25")
        f.act("ooa.script/run_in_session", desc="Use it in the next step", session="ut_py", script="200 * rate",
              out={"result": "sc_result"})
        ua.check_eq("sc_result", 50)
        f.act("ooa.script/close_session", desc="End the session", session="ut_py")

    with ua.case("ooa.script/run_in_session", "the last expression comes back; errors without failing",
                 "Run in session runs code in an open session; the value of a last expression comes back. With "
                 "fail_on_error off an error is given back instead of stopping the agent. Checks both.") as f:
        f.act("ooa.script/open_session", desc="Start a Python session", language="python", session="ut_py2")
        f.act("ooa.script/run_in_session", desc="Print and give back a value", session="ut_py2",
              script="print('working')\n'A-' + str(6 * 7)", out={"result": "sc_result", "output": "sc_out"})
        ua.check_eq("sc_result", "A-42")
        ua.check("contains", "$sc_out$", "working", what="sc_out")
        f.act("ooa.script/run_in_session", desc="Code that fails, without failing the step", session="ut_py2",
              script="1 / 0", fail_on_error=False, out={"error": "sc_err"})
        ua.check("contains", "$sc_err$", "ZeroDivision", what="sc_err")
        f.act("ooa.script/close_session", desc="End the session", session="ut_py2")

    with ua.case("ooa.script/call_in_session", "call a function defined earlier",
                 "Call in session calls a function defined in an open session, with arguments and keyword "
                 "arguments. Checks: the function's result.") as f:
        f.act("ooa.script/open_session", desc="Start a Python session", language="python", session="ut_py3")
        f.act("ooa.script/run_in_session", desc="Define a function", session="ut_py3", script=PY_FILE)
        f.act("ooa.script/call_in_session", desc="Call net(110, rate=0.1)", session="ut_py3", function="net",
              arguments=[110], keyword_arguments={"rate": 0.1}, out={"result": "sc_result"})
        ua.check_eq("sc_result", 100)
        f.act("ooa.script/close_session", desc="End the session", session="ut_py3")

    with ua.case("ooa.script/open_session", "a JavaScript (Node.js) session",
                 "The same with JavaScript: a function defined in one step is called in the next.") as f:
        f.act("ooa.script/open_session", desc="Start a JavaScript session", language="javascript", session="ut_js")
        f.act("ooa.script/run_in_session", desc="Define a function", session="ut_js",
              script="globalThis.twice = function (x) { return x * 2; }")
        f.act("ooa.script/call_in_session", desc="Call twice(21)", session="ut_js", function="twice", arguments=[21],
              out={"result": "sc_result"})
        ua.check_eq("sc_result", 42)
        f.act("ooa.script/close_session", desc="End the session", session="ut_js")

    with ua.case("ooa.script/open_session", "a VBScript session",
                 "The same with VBScript: a variable set in one step is read back with run_as eval in the next.") as f:
        f.act("ooa.script/open_session", desc="Start a VBScript session", language="vbscript", session="ut_vbs")
        f.act("ooa.script/run_in_session", desc="Set a variable", session="ut_vbs", script="total = 6 * 7")
        f.act("ooa.script/run_in_session", desc="Read it back", session="ut_vbs", script="total", run_as="eval",
              out={"result": "sc_result"})
        ua.check("eq", "$sc_result$", 42, what="sc_result")
        f.act("ooa.script/close_session", desc="End the session", session="ut_vbs")

    with ua.case("ooa.script/close_session", "the session is no longer open",
                 "Close session ends a script session and its program. Checks: Session is open is false after it.") as f:
        ua.var("sc_open", "Boolean", False)
        f.act("ooa.script/open_session", desc="Start a Python session", language="python", session="ut_py4")
        f.act("ooa.script/close_session", desc="End it", session="ut_py4")
        f.set("sc_open", True)
        with f.if_({"condition": "ooa.script/session_open", "args": {"session": "ut_py4"}}, desc="Still open?") as s:
            f.comment("Still open")
        with f.else_(s):
            f.set("sc_open", False)
        ua.check_eq("sc_open", False)

    # ---- conditions ----
    ua.var("ut_cond", "Boolean", False)
    with ua.case("ooa.script/session_open", "true while the session runs",
                 "Session is open is a condition: true while the script session is open and its program runs.") as f:
        f.act("ooa.script/open_session", desc="Start a Python session", language="python", session="ut_py5")
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.script/session_open", "args": {"session": "ut_py5"}}, desc="Is it open?"):
            f.set("ut_cond", True)
        ua.check_eq("ut_cond", True)
        f.act("ooa.script/close_session", desc="End the session", session="ut_py5")

    with ua.case("ooa.script/last_script_succeeded", "after a good and a failing script",
                 "Last script succeeded is a condition: true when the last script step of the session (or of the "
                 "run) succeeded. Checks: true after good code, false after code that failed (fail_on_error off).") as f:
        f.act("ooa.script/open_session", desc="Start a Python session", language="python", session="ut_py6")
        f.act("ooa.script/run_in_session", desc="Good code", session="ut_py6", script="x = 1")
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.script/last_script_succeeded", "args": {"session": "ut_py6"}},
                   desc="Did it succeed?"):
            f.set("ut_cond", True)
        ua.check_eq("ut_cond", True)
        f.act("ooa.script/run_in_session", desc="Failing code", session="ut_py6", script="raise ValueError('bad')",
              fail_on_error=False)
        f.set("ut_cond", True)
        with f.if_({"condition": "ooa.script/last_script_succeeded", "args": {"session": "ut_py6"}},
                   desc="Did it succeed?") as s:
            f.comment("It says it succeeded")
        with f.else_(s):
            f.set("ut_cond", False)
        ua.check_eq("ut_cond", False)
        f.act("ooa.script/close_session", desc="End the session", session="ut_py6")

    ua.condition_case("ooa.script/script_succeeded", "a PowerShell script that ends well",
                      "Script succeeded is a condition: it runs a script and is true when it ends with exit code 0.",
                      {"language": "powershell", "script": "Write-Output 'ok'; exit 0"}, True)
    ua.condition_case("ooa.script/script_succeeded", "a Python script that exits with 3",
                      "The same condition is false when the script ends with another exit code.",
                      {"language": "python", "script": "import sys\nsys.exit(3)"}, False)
