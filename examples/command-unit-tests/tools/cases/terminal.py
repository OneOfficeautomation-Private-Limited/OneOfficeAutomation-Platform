"""Terminal cases on the stand-in's VT100 host over Telnet (plugins/desktop_services.py: 127.0.0.1, input terminal_port).

The host asks "login:" and "Password:", then shows SAMPLE HOST and a "Name:" prompt; after the name, MAIN MENU with
"User: <name>" on row 3 and the cursor on the "Code:" row 5; after the code, "Result: <code>" on row 7 and a "> "
command line that answers "You typed: <command>" until "exit". The platform's terminal samples (written for its SSH
test host, which draws the same screens) are used with Telnet instead of SSH; the viewer window is left to the
"UT Terminal emulator (viewer)" agent.
"""
from helpers_desktop import add_converted, cred, drop_steps, find

VT = {'"{ssh_port}"': '"$terminal_port$"', '"vt-ssh"': '"vt-telnet"'}


def connect(f, wait_for="Name:"):
    f.act("ooa.terminal/connect", desc="Sign in to the test VT host over Telnet", host="127.0.0.1", port="$terminal_port$",
          terminal_type="vt-telnet", user="agent", password=cred("terminal"), wait_for_text=wait_for, connect_timeout_seconds=15)


def to_menu(f):
    f.act("ooa.terminal/send_text", desc="Type the name and press Enter", text="Ada", press_key="Enter", wait_for_text="MAIN MENU",
          timeout_seconds=10)


def add(ua):
    ua.f.var("terminal_port", "Number", "input", 2323, "Port of the stand-in's VT host (Telnet).")
    for s in find("ooa.terminal/", requires="ssh"):
        add_converted(ua, drop_steps(s, "ooa.terminal/show_terminal", "ooa.terminal/hide_terminal"), service="terminal", extra=VT)
    for n, t in (("ut_text", "String"), ("ut_n", "Number"), ("ut_row", "Number"), ("ut_col", "Number"), ("ut_ok", "Boolean"),
                 ("ut_list", "List"), ("ut_field", "Dictionary")):
        ua.var(n, t)

    with ua.case("ooa.terminal/get_text", "a rectangle and the last line",
                 "Get text reads text at a position, a rectangle of rows, or the line the cursor is on.") as f:
        connect(f)
        to_menu(f)
        f.act("ooa.terminal/get_text", desc="Read row 3 from column 7", row=3, column=7, length=3, out={"text": "ut_text"})
        ua.check_eq("ut_text", "Ada")
        f.act("ooa.terminal/get_text", desc="Read rows 1 to 3, 9 columns wide", what="rectangle", row=1, column=1, height=3, width=9,
              out={"text": "ut_text"})
        ua.check("contains", "$ut_text$", "MAIN MENU", what="rectangle")
        ua.check("contains", "$ut_text$", "User: Ada", what="rectangle")
        f.act("ooa.terminal/disconnect", desc="Disconnect")

    with ua.case("ooa.terminal/get_cursor", "the cursor waits on the Code row",
                 "Get cursor position gives the row and column of the cursor; the menu screen puts it at row 5, column 1.") as f:
        connect(f)
        to_menu(f)
        f.act("ooa.terminal/get_cursor", desc="Read the cursor", out={"row": "ut_row", "column": "ut_col"})
        ua.check_eq("ut_row", 5)
        ua.check_eq("ut_col", 1)
        f.act("ooa.terminal/disconnect", desc="Disconnect")

    with ua.case("ooa.terminal/get_field", "the field after a label, and by number",
                 "Get field reads one field, found by the label before it or by its number; on a VT screen 'User:' is a label "
                 "and the text after it an input field.") as f:
        connect(f)
        to_menu(f)
        f.act("ooa.terminal/get_field", desc="The field after 'User:'", label="User", out={"text": "ut_text", "field": "ut_field"})
        ua.check("eq", "$ut_text$", "Ada", what="User field")
        f.act("ooa.terminal/get_field", desc="Field number 1", index=1, out={"text": "ut_text"})
        ua.check_eq("ut_text", "MAIN MENU")
        f.act("ooa.terminal/disconnect", desc="Disconnect")

    with ua.case("ooa.terminal/search_field", "where the menu title is",
                 "Find text on screen gives the row and column where text appears, and how often.") as f:
        connect(f)
        to_menu(f)
        f.act("ooa.terminal/search_field", desc="Find 'MENU'", text="MENU", out={"found": "ut_ok", "row": "ut_row", "column": "ut_col",
                                                                                  "count": "ut_n"})
        ua.check_eq("ut_ok", True)
        ua.check_eq("ut_row", 1)
        ua.check_eq("ut_col", 6)
        f.act("ooa.terminal/search_field", desc="Text that is not there", text="NOWHERE", out={"found": "ut_ok"})
        ua.check_eq("ut_ok", False)
        f.act("ooa.terminal/disconnect", desc="Disconnect")

    with ua.case("ooa.terminal/wait_for_text", "an answer, and text that never comes",
                 "Wait for text waits until text shows on the screen; with fail_on_timeout off it just says it did not come.") as f:
        connect(f)
        to_menu(f)
        f.act("ooa.terminal/send_text", desc="Type a code and press Enter", text="X9", press_key="Enter")
        f.act("ooa.terminal/wait_for_text", desc="Wait for the result", text="Result: X9", timeout_seconds=10, out={"found": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("ooa.terminal/wait_for_text", desc="Wait briefly for text that never comes", text="NEVER SHOWN", timeout_seconds=1,
              fail_on_timeout=False, out={"found": "ut_ok"})
        ua.check_eq("ut_ok", False)
        f.act("ooa.terminal/disconnect", desc="Disconnect")

    with ua.case("ooa.terminal/wait", "for the cursor to reach the Code row",
                 "Wait for screen waits for a condition: here for the cursor to reach row 5, column 1 (the menu's Code row) "
                 "after the name is typed, then a plain pause in seconds.") as f:
        # Written through a variable: the flow check reads any step input called "condition" as an expression, so the
        # plain value condition="cursor_at" makes the whole agent invalid (platform finding, see the report)
        f.set("ut_text", "cursor_at", desc="The condition to wait for")
        connect(f)
        f.act("ooa.terminal/send_text", desc="Type the name", text="Ada")
        f.act("ooa.terminal/send_key", desc="Press Enter without waiting", key="Enter", wait_for_host=False)
        f.act("ooa.terminal/wait", desc="Wait for the cursor at row 5, column 1", condition="$ut_text$", row=5, column=1, timeout_seconds=10, out={"met": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.set("ut_text", "seconds", desc="Now a plain pause")
        f.act("ooa.terminal/wait", desc="Pause one second", condition="$ut_text$", timeout_seconds=1, out={"met": "ut_ok"})
        f.act("ooa.terminal/wait_for_text", desc="The menu is there", text="MAIN MENU", timeout_seconds=10, out={"found": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("ooa.terminal/disconnect", desc="Disconnect")

    with ua.case("ooa.terminal/hide_terminal", "nothing shown is fine",
                 "Hide terminal closes the viewer window Show terminal opened; with none open it does nothing, and the session "
                 "goes on.") as f:
        connect(f)
        f.act("ooa.terminal/hide_terminal", desc="Hide the viewer")
        f.act("ooa.terminal/get_screen", desc="The session still answers", out={"text": "ut_text"})
        ua.check("contains", "$ut_text$", "SAMPLE HOST", what="screen")
        f.act("ooa.terminal/disconnect", desc="Disconnect")

    with ua.case("ooa.terminal/disconnect", "the session is gone afterwards",
                 "Disconnect closes the connection; reading the screen after it fails because no session is open.") as f:
        connect(f)
        f.act("ooa.terminal/disconnect", desc="Disconnect")
        ua.expect_error("*", lambda ff: ff.act("ooa.terminal/get_screen", desc="Read after disconnecting"))

    with ua.case("ooa.terminal/connect", "a host that does not answer",
                 "Connect fails with a clear error when nothing listens at the host and port.") as f:
        ua.expect_error("TERMINAL.CONNECT_FAILED", lambda ff: ff.act(
            "ooa.terminal/connect", desc="Connect to a closed port", host="127.0.0.1", port=1, terminal_type="vt-telnet",
            session="nowhere", connect_timeout_seconds=3))
