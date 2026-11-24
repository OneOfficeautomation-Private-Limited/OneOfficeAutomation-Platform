"""Application cases that open windows; each case closes what it opened. Drives the screen."""
from helpers_desktop import fix_samples, winform_script

PS = "C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"


def add(ua):
    fix_samples(ua, ["ooa.application"])
    ua.var("ut_n", "Number", 0)
    ua.var("ut_ok", "Boolean", False)
    ua.var("ut_text", "String", "")

    with ua.case("ooa.application/open_program_file", "a program with parameters, waiting for its window",
                 "Open program file starts a program file with parameters and, with wait_for_window, carries on once its "
                 "window shows; it gives the process id, used here to stop it again.") as f:
        f.act("ooa.application/open_program_file", desc="Start PowerShell showing a test window", location=PS,
              parameters='-NoProfile -WindowStyle Hidden -Command "' + winform_script("UT program file window") + '"',
              wait_for_window=True, wait_seconds=30, out={"process_id": "ut_n"})
        ua.check("gt", "$ut_n$", 0, what="process id")
        f.act("ooa.window/wait_for", desc="The window is there", title="UT program file window", timeout_seconds=30, out={"met": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("ooa.window/close", desc="Close the window", title="UT program file window", cont=True)
        f.act("ooa.application/stop_program", desc="Make sure it ended", process_id="$ut_n$", force=True, cont=True)

    with ua.case("ooa.application/open_file", "a text file in its program",
                 "Open file opens a document with the program Windows uses for its type (a .txt in the text editor); the "
                 "case then closes that window by its title.") as f:
        f.act("ooa.file/write_text", desc="Write a text file", path="ut_open_file.txt", text="Opened by the unit test")
        f.act("ooa.application/open_file", desc="Open it", path="ut_open_file.txt")
        f.act("ooa.window/wait_for", desc="Wait for its window", title="*ut_open_file*", timeout_seconds=30, out={"met": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("ooa.window/close_all", desc="Close the editor window", title="ut_open_file", out={"count": "ut_n"}, cont=True)
