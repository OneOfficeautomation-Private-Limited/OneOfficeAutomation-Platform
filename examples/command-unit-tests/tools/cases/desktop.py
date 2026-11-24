"""Mouse and keyboard cases. Drives the screen: the mouse moves, keys are pressed into a test window the case opens,
prompts open and answer themselves after a time-out, and input is blocked for one second."""
from helpers_desktop import add_converted, close_test_window, desktop_object, find, fix_samples, open_test_window

W = "UT keyboard window"
TB = ("$t = New-Object Windows.Forms.TextBox; $t.Name = 'notes'; $t.Multiline = $true; $t.ScrollBars = 'Vertical'; "
      "$t.SetBounds(10, 10, 420, 240); $f.Controls.Add($t); ")
NOTES = desktop_object(W, ControlType="Edit", AutomationId="notes")


def add(ua):
    fix_samples(ua, ["ooa.desktop"])
    for s in find("ooa.desktop/", requires="desktop"):
        add_converted(ua, s)
    for n, t in (("ut_text", "String"), ("ut_ok", "Boolean"), ("ut_any", "Any"), ("ut_list", "List")):
        ua.var(n, t)

    def read_notes(f):
        f.act("ooa.desktop_ui/object_action", desc="Read the text box", object=NOTES, action_="get_text", out={"result": "ut_text"})

    with ua.case("ooa.desktop/mouse_click", "into a text box, then type",
                 "Mouse click clicks at a point, here relative to the test window, which puts the cursor in its text box; "
                 "Type text then types there.") as f:
        open_test_window(f, W, TB)
        f.act("ooa.window/activate", desc="Bring it to the front", title=W)
        f.act("ooa.desktop/mouse_click", desc="Click inside the text box", x=60, y=60, relative_to="window", window=W)
        f.act("ooa.desktop/type_text", desc="Type a line", text="clicked", method="keystrokes", window=W)
        read_notes(f)
        ua.check("contains", "$ut_text$", "clicked", what="text box")
        close_test_window(f, W)

    with ua.case("ooa.desktop/press_keys", "select all and replace",
                 "Press keys presses keys and combinations (here Ctrl+A, then Delete) in the window in front.") as f:
        open_test_window(f, W, TB)
        f.act("ooa.window/activate", desc="Bring it to the front", title=W)
        f.act("ooa.desktop/mouse_click", desc="Click inside the text box", x=60, y=60, relative_to="window", window=W)
        f.act("ooa.desktop/type_text", desc="Type some text", text="old text", method="keystrokes", window=W)
        f.act("ooa.desktop/press_keys", desc="Select everything", keys="ctrl+a", window=W)
        f.act("ooa.desktop/press_keys", desc="Delete it", keys="delete", window=W)
        f.act("ooa.desktop/press_keys", desc="Type x three times", keys="x", times=3, window=W)
        read_notes(f)
        ua.check_eq("ut_text", "xxx")
        close_test_window(f, W)

    with ua.case("ooa.desktop/mouse_scroll", "over the text box",
                 "Mouse scroll turns the wheel at a point; a long text in the box scrolls without changing it.") as f:
        open_test_window(f, W, TB)
        f.act("ooa.desktop_ui/object_action", desc="Fill the box with many lines", object=NOTES, action_="set_text",
              text="1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7\r\n8\r\n9\r\n10\r\n11\r\n12\r\n13\r\n14\r\n15\r\n16\r\n17\r\n18\r\n19\r\n20")
        f.act("ooa.desktop/mouse_scroll", desc="Scroll down 5", amount=5, direction="down", x=100, y=100, relative_to="window", window=W)
        f.act("ooa.desktop/mouse_scroll", desc="Scroll up 5", amount=5, direction="up", x=100, y=100, relative_to="window", window=W)
        read_notes(f)
        ua.check("startsWith", "$ut_text$", "1", what="text")
        close_test_window(f, W)

    with ua.case("ooa.desktop/clipboard_get", "text put there first",
                 "Get clipboard reads the clipboard's text and says whether there is text.") as f:
        f.act("ooa.desktop/clipboard_set", desc="Put text on the clipboard", text="UT clip 42")
        f.act("ooa.desktop/clipboard_get", desc="Read it", out={"text": "ut_text", "has_text": "ut_ok"})
        ua.check_eq("ut_text", "UT clip 42")
        ua.check_eq("ut_ok", True)
        f.act("ooa.desktop/clipboard_clear", desc="Leave it empty")

    with ua.case("ooa.desktop/clipboard_clear", "nothing left",
                 "Clear clipboard empties the clipboard.") as f:
        f.act("ooa.desktop/clipboard_set", desc="Put text on the clipboard", text="to be cleared")
        f.act("ooa.desktop/clipboard_clear", desc="Clear it")
        f.act("ooa.desktop/clipboard_get", desc="Read it", out={"has_text": "ut_ok"})
        ua.check_eq("ut_ok", False)

    with ua.case("ooa.desktop/prompt_file", "nobody answers: cancelled",
                 "Ask for a file shows a file dialog; with a time-out and nobody there it closes itself as cancelled.") as f:
        f.act("ooa.desktop/prompt_file", desc="Ask for a CSV file, 2 s", title="UT choose a file", file_types="CSV files|*.csv",
              timeout_seconds=2, out={"cancelled": "ut_ok", "path": "ut_text"})
        ua.check_eq("ut_ok", True)

    with ua.case("ooa.desktop/prompt_folder", "nobody answers: cancelled",
                 "Ask for a folder shows a folder dialog; with a time-out and nobody there it closes itself as cancelled.") as f:
        f.act("ooa.desktop/prompt_folder", desc="Ask for a folder, 2 s", title="UT choose a folder", timeout_seconds=2,
              out={"cancelled": "ut_ok"})
        ua.check_eq("ut_ok", True)

    with ua.case("ooa.desktop/prompt_password", "nobody answers: empty",
                 "Ask for a password shows a masked box; nobody answers within 2 seconds, so the password is empty.") as f:
        ua.var("ut_secret", "Credential")
        f.act("ooa.desktop/prompt_password", desc="Ask for a password, 2 s", question="UT password", timeout_seconds=2,
              out={"password": "ut_secret"})
        ua.check("empty", "$ut_secret$", what="password")

    with ua.case("ooa.desktop/block_input", "for one second, then unblock",
                 "Block input stops the person's mouse and keyboard for a while (Windows allows it only to an elevated "
                 "runner); Unblock input gives them back and says whether they were blocked.") as f:
        f.act("ooa.desktop/block_input", desc="Block input for 1 second", seconds=1)
        f.act("ooa.desktop/unblock_input", desc="Unblock input", out={"was_blocked": "ut_ok"})
        ua.check("ne", "$ut_ok$", None, what="was_blocked")
