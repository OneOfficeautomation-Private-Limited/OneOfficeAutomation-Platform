"""UT Item Python script: the sample script UT invoice tools.py run as a script and called as functions."""
from helpers_platform import renumber, items_input, copy_item

PY = "ut-items/UT invoice tools.py"


def add(ua):
    items_input(ua)
    for v, t in (("py_json", "Any"), ("py_result", "Any"), ("py_n", "Number"), ("py_text", "String"), ("py_code", "Number")):
        ua.var(v, t)
    f = ua.f
    lines = '{"lines": [{"qty": 2, "price": 10.5}, {"qty": 1, "price": 4}]}'
    with ua.case("ooa.script/run_python", "as a script: JSON in, argument, JSON out",
                 "Run Python script runs the sample as a script: the invoice lines go in on standard input, the "
                 "currency as its argument; it prints one JSON object (2 lines, total 25.0, USD) and ends with 0."):
        copy_item(f, "python-script/UT invoice tools.py", PY)
        f.act("ooa.script/run_python", file=PY, arguments=["usd"], input=lines, timeout_seconds=60,
              out={"json": "py_json", "exit_code": "py_code"})
        ua.check("eq", "$py_code$", 0, what="exit code")
        f.expr("py_n", "$py_json.total")
        ua.check("eq", "$py_n$", 25, what="total")
        f.expr("py_text", "$py_json.currency")
        ua.check_eq("py_text", "USD")
    with ua.case("ooa.script/run_python_function", "a function with keyword arguments",
                 "Run Python function calls invoice_total of the sample with the lines and a 10 percent discount as "
                 "keyword arguments, and gets its dictionary back."):
        copy_item(f, "python-script/UT invoice tools.py", PY)
        f.act("ooa.script/run_python_function", file=PY, function="invoice_total",
              arguments=[[{"qty": 2, "price": 10.5}, {"qty": 1, "price": 4}]], keyword_arguments={"discount_percent": 10},
              out={"result": "py_result"})
        f.expr("py_n", "$py_result.total")
        ua.check("eq", "$py_n$", 22.5, what="total less 10 percent")
    with ua.case("ooa.script/run_python_function", "a static method of a class",
                 "Run Python function can call Class.method: Vat.add adds 20 percent VAT to 100."):
        copy_item(f, "python-script/UT invoice tools.py", PY)
        f.act("ooa.script/run_python_function", file=PY, function="Vat.add", arguments=[100], out={"result": "py_result"})
        ua.check("eq", "$py_result$", 120, what="gross")
    with ua.case("ooa.script/run_python_function", "an exception in the script fails the step",
                 "normalise_reference turns 'inv 42' into INV-42; with a text without a number it raises, and the "
                 "step fails (checked with fail_on_error off: the error output names the ValueError)."):
        copy_item(f, "python-script/UT invoice tools.py", PY)
        f.act("ooa.script/run_python_function", file=PY, function="normalise_reference", arguments=["inv 42"],
              out={"result": "py_result"})
        ua.check("eq", "$py_result$", "INV-42", what="reference")
        f.act("ooa.script/run_python_function", file=PY, function="normalise_reference", arguments=["none"],
              fail_on_error=False, out={"error": "py_text"})
        ua.check("contains", "$py_text$", "ValueError", what="the error")
    renumber(ua)
