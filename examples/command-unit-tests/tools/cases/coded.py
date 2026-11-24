"""Coded agent cases: Python modules with main(inputs, ooa) that call toolkit commands through the run."""
from helpers_platform import renumber

C = "ooa.coded/run_python_agent"

TYPED = '''def main(inputs, ooa):
    r = ooa.call("ooa.string/change_case", text=inputs["supplier"], case="upper")
    ooa.log("Supplier in capitals: " + r["result"])
    return {"supplier": r["result"], "lines": len(inputs["lines"])}
'''

MODULE = '''"""A small coded agent: totals invoice lines, writes a summary file with a toolkit command and reads it back."""


def total(lines):
    return round(sum(l["qty"] * l["price"] for l in lines), 2)


def run(inputs, ooa):
    t = total(inputs["lines"])
    ooa.call("ooa.file/write_text", path=inputs["summary_file"], text="Total " + str(t))
    back = ooa.call("ooa.file/read_text", path=inputs["summary_file"])
    return {"total": t, "summary": back["text"]}
'''


def add(ua):
    for v, t in (("cd_out", "Dictionary"), ("cd_calls", "Number"), ("cd_text", "String"), ("cd_n", "Number")):
        ua.var(v, t)
    f = ua.f
    with ua.case(C, "code typed in the step calls a String command",
                 "Run Python agent runs main(inputs, ooa) of the code typed in the step. The code calls Change case "
                 "through ooa.call() (ooa.string is in the toolkits it may call) and returns two outputs; calls is 1."):
        f.act(C, code=TYPED, inputs={"supplier": "Contoso ltd", "lines": [1, 2, 3]}, uses=["ooa.string"],
              out={"outputs": "cd_out", "calls": "cd_calls"})
        f.expr("cd_text", "$cd_out.supplier")
        ua.check_eq("cd_text", "CONTOSO LTD")
        f.expr("cd_n", "$cd_out.lines")
        ua.check("eq", "$cd_n$", 3, what="lines")
        ua.check("eq", "$cd_calls$", 1, what="calls")
    with ua.case(C, "a module file with its own entry function",
                 "Run Python agent can run a module file with another entry function (run). The module totals the "
                 "lines, writes and reads a file with File commands, and returns the total and the text it read."):
        f.act("ooa.file/write_text", desc="Write the module (in a real project it is a file in the repository)",
              path="ut-coded/invoice_agent.py", text=MODULE)
        f.act(C, file="ut-coded/invoice_agent.py", entry="run", uses=["ooa.file"],
              inputs={"lines": [{"qty": 2, "price": 10.5}, {"qty": 1, "price": 4}], "summary_file": "ut-coded/summary.txt"},
              out={"outputs": "cd_out", "calls": "cd_calls"})
        f.expr("cd_n", "$cd_out.total")
        ua.check("eq", "$cd_n$", 25, what="total")
        f.expr("cd_text", "$cd_out.summary")
        ua.check_eq("cd_text", "Total 25.0")
        ua.check("eq", "$cd_calls$", 2, what="calls")
    with ua.case(C, "a toolkit not listed is refused",
                 "The code may only call the toolkits listed in uses: calling a File command with only ooa.string "
                 "listed fails with CODE.TOOLKIT_NOT_LISTED."):
        ua.expect_error("CODE.TOOLKIT_NOT_LISTED", lambda ff: ff.act(
            C, code='def main(inputs, ooa):\n    ooa.call("ooa.file/read_text", path="x.txt")\n    return {}\n',
            uses=["ooa.string"]))
    with ua.case(C, "an entry that is not a Python name",
                 "The entry function must be a Python name: 2main fails with CODE.BAD_ENTRY before Python starts."):
        ua.expect_error("CODE.BAD_ENTRY", lambda ff: ff.act(C, code="def main(inputs, ooa):\n    return {}\n",
                                                             entry="2main"))
    renumber(ua)
