"""Shared agents of the real-world tests: Start QA stand-ins, Preflight (runbook step 05) and Run all eight."""
from common import BASE, STANDIN, common_inputs, cred
from flowlib import Flow

FOLDER = "Shared"


def standins():
    f = Flow("rw00_standins", "Start QA stand-ins",
             "Starts the local QA stand-in server (127.0.0.1 only) that answers like the test tenants' APIs, unless it already runs, and waits until it answers.")
    f.tags = ["real-world-test", "shared"]
    f.var("base", "String", "input", BASE, "Folder of the real-world tests (the stand-in is in its standin folder).")
    f.var("standin", "String", "input", STANDIN, "Address of the stand-in server.")
    f.var("watch", "Boolean", "input", True, "Show message boxes.")
    f.var("up", "Boolean", "local", False)
    f.var("local", "String", "local", "")
    f.var("pyw", "String", "local", "")
    f.var("pys", "List", "local")
    f.var("py", "String", "local", "")
    f.var("tries", "Number", "local", 0)
    f.var("status", "String", "output", "", "running when the stand-ins answer.")
    with f.step("1. Is it running?", "Ask the stand-in's health address; a server that answers is used as it is."):
        with f.try_(desc="A refused connection means it is not running yet") as t:
            f.act("ooa.rest/request", desc="Ask the stand-in's health address", url="${standin}/qa/health", timeout_seconds=3, out={"status": "tries"})
            f.expr("up", "$tries$ == 200", desc="Running when it answered 200")
        with f.catch(t):
            f.set("up", False, desc="Not running")
    with f.step("2. Start it", "Start python standin.py in the background (no window), then wait until it answers, for up to 20 seconds."):
        with f.if_("not $up$", desc="Not running yet?"):
            f.box("Starting the QA stand-in server on ${standin}", title="QA stand-ins", seconds=2)
            f.comment("The runner's PATH may not have Python: look in the usual per-user install folder first, then fall back to pythonw on the PATH.")
            f.act("ooa.system/get_environment_variable", desc="Where per-user programs are", name="LOCALAPPDATA", out={"value": "local"})
            f.comment("Window state stays normal (pythonw shows no window): the hidden start merges all arguments into one under Windows PowerShell 5.1 (platform gap, listed).")
            f.set("pyw", "pythonw", desc="Fallback: pythonw on the PATH")
            f.act("ooa.folder/list_subfolders", desc="Installed Pythons (one folder each)", path="${local}/Programs/Python", cont=True, out={"folders": "pys"})
            with f.loop(desc="Each installed Python", iterator="list", over={"expr": "coalesce($pys$, [])"}, **{"as": "py"}):
                with f.if_({"condition": "ooa.file/exists", "args": {"path": "${py}/pythonw.exe"}}, desc="Has pythonw.exe at its top?"):
                    f.set("pyw", "${py}/pythonw.exe", desc="Use it")
            f.act("ooa.application/start_program", desc="Start the stand-in server in the background (pythonw: no window)",
                  program="$pyw$", arguments=["${base}/standin/standin.py", "$base$"], working_folder="${base}/standin", window_state="normal")
            f.set("tries", 0, desc="Start counting the checks")
            with f.loop(desc="Check every second until it answers", iterator="while", condition="not $up$ and $tries$ < 20", max_iterations=25):
                f.act("core/delay", desc="Wait a second", seconds=1)
                f.expr("tries", "$tries$ + 1", desc="Count the check")
                with f.try_(desc="Ask again") as t2:
                    f.act("ooa.rest/request", desc="Ask the stand-in's health address", url="${standin}/qa/health", timeout_seconds=3)
                    f.set("up", True, desc="It answers")
                with f.catch(t2):
                    f.comment("Not yet: try again.")
            with f.if_("not $up$", desc="Still not answering?"):
                f.act("core/throw", desc="Stop: no stand-in", code="QA.STANDIN_DOWN", message="The QA stand-in did not start on ${standin}",
                      suggested_fix="Check that Python is installed and that ${base}/standin/standin.py and seed.json exist (run make_data.py).")
    f.set("status", "running", desc="The stand-ins answer")
    return f


def preflight():
    f = Flow("rw00_preflight", "RW00 Preflight",
             "Runbook step 05, read-only: checks the stand-ins, the vault, global values, the test data, the apps and the public sites before any test writes.")
    f.tags = ["real-world-test", "shared"]
    common_inputs(f)
    for name, typ, default in [("report", "Table", {"columns": ["check", "result", "detail"], "rows": []}), ("v", "Any", None), ("code", "Number", 0),
                               ("ok", "Number", 0), ("bad", "Number", 0), ("err_msg", "String", ""), ("site", "String", ""), ("app", "String", ""),
                               ("sites", "List", ["https://books.toscrape.com/", "https://quotes.toscrape.com/js/", "https://en.wikipedia.org/wiki/Main_Page",
                                                  "https://demoqa.com/webtables", "https://www.saucedemo.com/", "https://rpachallenge.com/"]),
                               ("apps", "List", ["C:/Program Files/Microsoft Office/root/Office16/EXCEL.EXE", "C:/Program Files/Microsoft Office/root/Office16/WINWORD.EXE",
                                                 "C:/Program Files/Microsoft Office/root/Office16/POWERPNT.EXE", "C:/Program Files/Tesseract-OCR/tesseract.exe"])]:
        f.var(name, typ, "local", default)
    f.var("status", "String", "output", "", "ready when every check passed; otherwise what failed is in the report.")
    f.var("report_file", "String", "output", "", "The preflight report.")

    def check(name, steps, detail):
        with f.try_(desc="Check: " + name) as t:
            steps()
            f.act("ooa.datatable/add_row", desc="Note the pass", table="$report$", values={"check": name, "result": "pass", "detail": detail}, out={"table": "report"})
            f.expr("ok", "$ok$ + 1", desc="Count the pass")
        with f.catch(t, message_to="err_msg"):
            f.act("ooa.datatable/add_row", desc="Note the failure", table="$report$", values={"check": name, "result": "fail", "detail": "$err_msg$"}, out={"table": "report"})
            f.expr("bad", "$bad$ + 1", desc="Count the failure")

    f.box("RW00 Preflight: read-only checks before the real-world tests", title="Preflight", seconds=3)
    with f.step("1. Stand-ins and vault", "The stand-ins answer, and a vault secret reaches them (the run user may use the locker)."):
        f.act("core/run_bot", desc="Start the QA stand-ins", automation="rw00_standins", inputs={"base": "$base$", "standin": "$standin$", "watch": "$watch$"})
        check("stand-in answers", lambda: f.act("ooa.rest/request", desc="Health address", url="${standin}/qa/health", out={"status": "code"}), "${standin}")
        check("vault secret delivered", lambda: f.act("ooa.servicenow/query", desc="A ServiceNow query signed in with the vault token", table="incident",
                                                       query="correlation_id=OOA-QA-C001", max_records=1, instance_url="$standin$", access_token=cred("servicenow")),
              "locker rw_qa_standins")
    with f.step("2. Shared values and data", "Global values exist, the test data is in place, the clock and time zone are known."):
        check("global value rw_allowed_hosts", lambda: f.act("ooa.global_values/get", desc="Read the allow-list", name="rw_allowed_hosts", out={"value": "v"}), "${v}")
        check("global value rw_canary", lambda: f.act("ooa.global_values/get", desc="Read the canary", name="rw_canary", out={"value": "v"}), "set")
        check("test data folder", lambda: f.act("ooa.file/read_text", desc="Read a data file", path="${base}/data/RW01/cases.csv"), "${base}/data")
        check("time and zone", lambda: f.act("ooa.datetime/now", desc="Clock", out={"value": "v"}), "${v}")
    with f.step("3. Apps on this computer", "Excel, Word, PowerPoint and Tesseract, which the desktop steps use."):
        with f.loop(desc="Each app", iterator="list", over="$apps$", **{"as": "app"}):
            check("${app}", lambda: f.act("ooa.file/get_info", desc="Is the app installed?", path="$app$"), "installed")
    with f.step("4. Public practice sites", "Each allow-listed public site answers (read only, one request each)."):
        with f.loop(desc="Each site", iterator="list", over="$sites$", **{"as": "site"}):
            check("${site}", lambda: f.act("ooa.rest/request", desc="Ask the site once", url="$site$", timeout_seconds=20, out={"status": "code"}), "HTTP ${code}")
            f.pause()
    with f.step("5. Report", "Save the report and say what is ready."):
        f.set("report_file", "${base}/output/preflight-${formatDate(now(), '%Y%m%d-%H%M%S')}.csv", desc="Name of the report")
        f.act("ooa.datatable/write_to_file", desc="Save the report", table="$report$", path="$report_file$")
        f.expr("status", "if($bad$ == 0, 'ready', 'not ready')", desc="Ready when nothing failed")
        f.box("Preflight: ${status}\n\nPassed: ${ok}\nFailed: ${bad}\n\nReport: ${report_file}", title="Preflight", seconds=5)
    return f


MAIN_IDS = [("rw01_main", "RW01 Customer support"), ("rw02_main", "RW02 Employee lifecycle"), ("rw03_main", "RW03 Procure-to-pay"),
            ("rw04_main", "RW04 Order-to-cash"), ("rw05_main", "RW05 Access review"), ("rw06_main", "RW06 Claims documents"),
            ("rw07_main", "RW07 Cloud incident"), ("rw08_main", "RW08 Release readiness")]


def run_all():
    f = Flow("rw00_run_all", "Run all eight", "Runs the preflight, then the eight real-world test automations RW01-RW08 one after another, and sums up.")
    f.tags = ["real-world-test", "shared"]
    common_inputs(f)
    f.var("concurrency", "Number", "input", 1, "Passed to each main agent.")
    for name, typ, default in [("r", "Dictionary", None), ("rs", "String", ""), ("pre", "Dictionary", None),
                               ("summary", "Table", {"columns": ["automation", "status"], "rows": []}), ("failed", "Number", 0)]:
        f.var(name, typ, "local", default)
    f.var("status", "String", "output", "", "success when all eight succeeded.")
    with f.step("1. Preflight", "Read-only checks first (runbook step 05)."):
        f.act("core/run_bot", desc="Run the preflight", automation="rw00_preflight", fail_on_error=False,
              inputs={"base": "$base$", "standin": "$standin$", "watch": "$watch$", "pause_seconds": "$pause_seconds$"}, out={"outputs": "pre"})
    with f.step("2. The eight automations", "One after another; each makes its own run id."):
        for fid, name in MAIN_IDS:
            f.box("Next: " + name, title="Run all eight", seconds=3)
            f.act("core/run_bot", desc="Run " + name, automation=fid, fail_on_error=False,
                  inputs={"base": "$base$", "standin": "$standin$", "watch": "$watch$", "pause_seconds": "$pause_seconds$",
                          "fault_profile": "$fault_profile$", "concurrency": "$concurrency$"}, out={"outputs": "r", "status": "rs"})
            f.act("ooa.datatable/add_row", desc="Note the result", table="$summary$", values={"automation": name, "status": "${r.status}"}, out={"table": "summary"})
            with f.if_({"op": "ne", "left": "${r.status}", "right": "success"}, desc="Not a success?"):
                f.expr("failed", "$failed$ + 1", desc="Count it")
    with f.step("3. Sum up"):
        f.act("ooa.datatable/write_to_file", desc="Save the summary", table="$summary$", path="${base}/output/run-all-${formatDate(now(), '%Y%m%d-%H%M%S')}.csv")
        f.expr("status", "if($failed$ == 0, 'success', 'failed')", desc="Overall result")
        f.box("Run all eight: ${status}\nPreflight: ${pre.status}\nAutomations not succeeded: ${failed}", title="Run all eight", seconds=8)
    return f


CHILDREN = [standins, preflight, run_all]
