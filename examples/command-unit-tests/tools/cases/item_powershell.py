"""UT Item PowerShell script: the sample script UT invoice tools.ps1 with parameters and standard input."""
from helpers_platform import renumber, items_input, copy_item

PS = "ut-items/UT invoice tools.ps1"


def add(ua):
    items_input(ua)
    for v, t in (("ps_json", "Any"), ("ps_n", "Number"), ("ps_text", "String"), ("ps_code", "Number")):
        ua.var(v, t)
    f = ua.f
    lines = '{"lines": [{"qty": 2, "price": 10.5}, {"qty": 1, "price": 4}]}'
    with ua.case("ooa.script/run_powershell", "parameters by position and JSON on standard input",
                 "Run PowerShell script runs the sample with the currency and VAT rate as parameters and the invoice "
                 "lines on standard input; it prints JSON: net 25, VAT 2.5, gross 27.5, currency GBP, exit code 0."):
        copy_item(f, "powershell-script/UT invoice tools.ps1", PS)
        f.act("ooa.script/run_powershell", file=PS, arguments=["gbp", 0.1], input=lines, timeout_seconds=120,
              out={"json": "ps_json", "exit_code": "ps_code"})
        ua.check("eq", "$ps_code$", 0, what="exit code")
        f.expr("ps_n", "$ps_json.net")
        ua.check("eq", "$ps_n$", 25, what="net")
        f.expr("ps_n", "$ps_json.vat")
        ua.check("eq", "$ps_n$", 2.5, what="VAT")
        f.expr("ps_n", "$ps_json.gross")
        ua.check("eq", "$ps_n$", 27.5, what="gross")
        f.expr("ps_text", "$ps_json.currency")
        ua.check_eq("ps_text", "GBP")
    with ua.case("ooa.script/run_powershell", "default parameters",
                 "Without arguments the script's defaults apply: EUR and 20 percent VAT on one line of 50."):
        copy_item(f, "powershell-script/UT invoice tools.ps1", PS)
        f.act("ooa.script/run_powershell", file=PS, input='{"lines": [{"qty": 1, "price": 50}]}', out={"json": "ps_json"})
        f.expr("ps_n", "$ps_json.gross")
        ua.check("eq", "$ps_n$", 60, what="gross")
        f.expr("ps_text", "$ps_json.currency")
        ua.check_eq("ps_text", "EUR")
    with ua.case("ooa.script/run_powershell", "bad input fails the script",
                 "Text that is not JSON on standard input makes the script stop with an error; with fail_on_error "
                 "off the step carries on and the exit code is not 0."):
        copy_item(f, "powershell-script/UT invoice tools.ps1", PS)
        f.act("ooa.script/run_powershell", file=PS, input="not json", fail_on_error=False, out={"exit_code": "ps_code"})
        ua.check("ne", "$ps_code$", 0, what="exit code")
    renumber(ua)
