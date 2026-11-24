"""Window cases on small Windows Forms test windows that each case opens and closes itself. Drives the screen."""
from helpers_desktop import add_converted, close_test_window, find, fix_samples, open_test_window

TB = ("$t = New-Object Windows.Forms.TextBox; $t.Name = 'notes'; $t.Multiline = $true; $t.SetBounds(10, 10, 400, 200); "
      "$f.Controls.Add($t); ")


def add(ua):
    fix_samples(ua, ["ooa.window"])
    for s in find("ooa.window/", requires="desktop"):
        add_converted(ua, s)
    for n, t in (("ut_text", "String"), ("ut_n", "Number"), ("ut_ok", "Boolean"), ("ut_list", "List"), ("ut_dict", "Dictionary"), ("ut_x_name", "String"),
                 ("ut_x", "Number"), ("ut_y", "Number"), ("ut_w", "Number"), ("ut_h", "Number")):
        ua.var(n, t)

    def case(cmd, title, why, wt, body=""):
        c = ua.case(cmd, title, why)
        f = c.__enter__()
        open_test_window(f, wt, body)
        return c, f

    def end(c, f, wt):
        close_test_window(f, wt)
        c.__exit__(None, None, None)

    w = "UT window move"
    c, f = case("ooa.window/move_resize", "to a place and size", "Move and resize puts a window at a position with a size; Get window "
                "details reads them back.", w)
    f.act("ooa.window/restore", desc="Make sure it is not maximized", title=w)
    f.act("ooa.window/move_resize", desc="Move to 120,80 at 520 x 360", title=w, x=120, y=80, width=520, height=360)
    f.act("ooa.window/get_info", desc="Read the window details", title=w, out={"x": "ut_x", "y": "ut_y", "width": "ut_w", "height": "ut_h"})
    ua.check_eq("ut_x", 120)
    ua.check_eq("ut_y", 80)
    ua.check_eq("ut_w", 520)
    ua.check_eq("ut_h", 360)
    end(c, f, w)

    w = "UT window info"
    c, f = case("ooa.window/get_info", "state and process", "Get window details gives the window's position, size, state and process id.", w)
    f.act("ooa.window/get_info", desc="Read the details", title=w, out={"state": "ut_text", "process_id": "ut_n"})
    ua.check("gt", "$ut_n$", 0, what="process id")
    ua.check("notEmpty", "$ut_text$", what="state")
    end(c, f, w)

    w = "UT window states"
    c, f = case("ooa.window/maximize", "maximize, minimize, restore", "Maximize, Minimize and Restore change the window's state; Get window "
                "details shows each one.", w)
    f.act("ooa.window/maximize", desc="Maximize", title=w)
    f.act("ooa.window/get_info", desc="Read the state", title=w, out={"state": "ut_text"})
    ua.check("matches", "$ut_text$", "(?i)max.*", what="state")
    f.act("ooa.window/minimize", desc="Minimize", title=w)
    f.act("ooa.window/get_info", desc="Read the state", title=w, out={"state": "ut_text"})
    ua.check("matches", "$ut_text$", "(?i)min.*", what="state")
    f.act("ooa.window/restore", desc="Restore", title=w)
    f.act("ooa.window/get_info", desc="Read the state", title=w, out={"state": "ut_text"})
    ua.check("matches", "$ut_text$", "(?i)(normal|restored)", what="state")
    end(c, f, w)

    w = "UT window minimize"
    c, f = case("ooa.window/minimize", "gives the title", "Minimize gives the title of the window it minimized.", w)
    f.act("ooa.window/minimize", desc="Minimize", title=w, out={"title": "ut_text"})
    ua.check_eq("ut_text", w)
    end(c, f, w)

    w = "UT window restore"
    c, f = case("ooa.window/restore", "after maximize", "Restore brings a maximized window back to its normal size and gives its title.", w)
    f.act("ooa.window/maximize", desc="Maximize", title=w)
    f.act("ooa.window/restore", desc="Restore", title=w, out={"title": "ut_text"})
    ua.check_eq("ut_text", w)
    end(c, f, w)

    w = "UT window title"
    c, f = case("ooa.window/set_title", "rename and back", "Set window title renames a window and gives the old title.", w)
    f.act("ooa.window/set_title", desc="Rename it", title=w, new_title="UT window renamed", out={"previous_title": "ut_text"})
    ua.check_eq("ut_text", w)
    f.act("ooa.window/set_title", desc="Back to its name", title="UT window renamed", new_title=w)
    end(c, f, w)

    w = "UT window active"
    c, f = case("ooa.window/get_active", "after activating the test window", "Get active window gives the title of the window in front.", w)
    f.act("ooa.window/activate", desc="Bring it to the front", title=w)
    f.act("ooa.window/get_active", desc="Read the active window", out={"title": "ut_text", "process_name": "ut_x_name"})
    ua.check_eq("ut_text", w)
    end(c, f, w)

    ua.var("ut_cond", "Boolean", False)
    w = "UT window is active"
    c, f = case("ooa.window/is_active", "true after activating", "Window is active is a condition: true while that window is in front.", w)
    f.act("ooa.window/activate", desc="Bring it to the front", title=w)
    f.set("ut_cond", False)
    with f.if_({"condition": "ooa.window/is_active", "args": {"title": w, "wait_seconds": 3}}, desc="Is it in front?"):
        f.set("ut_cond", True)
    ua.check("eq", "$ut_cond$", True, what="active")
    end(c, f, w)

    w = "UT window exists"
    c, f = case("ooa.window/exists", "an open and a missing window", "Window exists is a condition: true while a window with the title is open.", w)
    f.set("ut_cond", False)
    with f.if_({"condition": "ooa.window/exists", "args": {"title": w}}, desc="Is it open?"):
        f.set("ut_cond", True)
    ua.check("eq", "$ut_cond$", True, what="exists")
    with f.if_({"condition": "ooa.window/exists", "args": {"title": "UT no such window 9f3a"}}, desc="Is a missing window open?"):
        f.set("ut_cond", False)
    ua.check("eq", "$ut_cond$", True, what="missing window")
    end(c, f, w)

    w = "UT window list"
    c, f = case("ooa.window/list", "the test window is listed", "List windows gives the titles of the open windows.", w)
    f.act("ooa.window/list", desc="List the windows", out={"titles": "ut_list"})
    ua.check("contains", "$ut_list$", w, what="titles")
    end(c, f, w)

    w = "UT window capture"
    c, f = case("ooa.window/capture", "a picture of the window", "Capture window saves a picture of one window.", w)
    f.act("ooa.file/delete", desc="No old picture", path="win_capture.png", cont=True)
    f.act("ooa.window/capture", desc="Capture it", title=w, path="win_capture.png", activate=True, out={"path": "ut_text"})
    ua.check("endsWith", "$ut_text$", "win_capture.png", what="path")
    end(c, f, w)

    w = "UT window close"
    c, f = case("ooa.window/close", "closed and gone", "Close window closes it and, with wait_closed_seconds, waits until it is gone.", w)
    f.act("ooa.window/close", desc="Close it", title=w, wait_closed_seconds=10, out={"closed": "ut_ok"})
    ua.check_eq("ut_ok", True)
    f.act("ooa.window/wait_for", desc="It is gone", title=w, state="close", timeout_seconds=5, out={"met": "ut_ok"})
    ua.check_eq("ut_ok", True)
    c.__exit__(None, None, None)

    w = "UT window background typing"
    c, f = case("ooa.window/type_in_background", "into the text box without focus",
                "Type in background puts text into a control of a window that need not be in front, by its class.", w, TB)
    f.act("ooa.window/type_in_background", desc="Type into the text box", title=w, text="hello from UT", control_class="Edit",
          method="replace", out={"control_text": "ut_text"})
    ua.check("contains", "$ut_text$", "hello from UT", what="control text")
    end(c, f, w)

    with ua.case("ooa.window/pick_point", "nobody picks: time-out",
                 "Pick a point (design time) waits for a person to click a spot; nobody clicks in an unattended run, so it "
                 "ends with its time-out error after 2 seconds.") as f:
        ua.expect_error("*", lambda ff: ff.act("ooa.window/pick_point", desc="Wait 2 s for a click", timeout_seconds=2))
