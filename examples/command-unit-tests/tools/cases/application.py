"""Hand-written cases for the Application commands that run programs without windows."""
from helpers_desktop import fix_samples, prepend


def add(ua):
    fix_samples(ua, ["ooa.application"])
    prepend(ua, "write_session - no session open", "ooa.application/close_session",
            "Close the session an earlier case may have left open", session="cmd", send_exit=False, wait_seconds=1)
    ua.var("pid", "Number", 0)
    ua.var("stopped", "Number", 0)
    ua.var("ut_running", "Boolean", False)

    with ua.case("ooa.application/start_program", "start a hidden program and stop it",
                 "Start program starts a program and carries on at once, giving its process id. A hidden PowerShell that "
                 "waits 60 seconds is started, Program is running sees it, and Stop program ends it by its id.") as f:
        f.act("ooa.application/start_program", desc="Start a hidden PowerShell that waits", program="powershell.exe",
              arguments=["-NoProfile", "-Command", "Start-Sleep -Seconds 60"], window_state="hidden", out={"process_id": "pid"})
        ua.check("gt", "$pid$", 0, what="process id")
        f.set("ut_running", False)
        with f.if_({"condition": "ooa.application/is_running", "args": {"name": "powershell.exe", "wait_seconds": 5}},
                   desc="Is PowerShell running?"):
            f.set("ut_running", True)
        ua.check("eq", "$ut_running$", True, what="powershell running")
        f.act("ooa.application/stop_program", desc="Stop it by its process id", process_id="$pid$", force=True,
              out={"stopped": "stopped"})
        ua.check("eq", "$stopped$", 1, what="programs stopped")

    ua.var("out_text", "String", "")
    ua.var("exit_code", "Number", 0)
    with ua.case("ooa.application/run_program", "input sent to the program",
                 "Run program waits for the program and gives its output: findstr reads the text given as input and "
                 "prints the matching line.") as f:
        f.act("ooa.application/run_program", desc="Run findstr with text on its input", program="findstr.exe",
              arguments=["INV"], input="PO-1\r\nINV-42\r\n", out={"output": "out_text", "exit_code": "exit_code"})
        ua.check("contains", "$out_text$", "INV-42", what="output")
        ua.check("notContains", "$out_text$", "PO-1", what="output")
        ua.check_eq("exit_code", 0)
