"""Trigger Loop types that watch the screen. Where the platform reacts to events a program makes, a parallel branch makes
them (a key press, a test window opening); clicks and form changes count only when a person makes them, so those cases
check that the loop ends cleanly at max_seconds with no event. Drives the screen."""
import json

from helpers_desktop import close_test_window, fix_samples, winform_script

FORM = {"id": "ut_form", "name": "UT form", "fields": [{"id": "customer", "type": "text", "label": "Customer"},
                                                       {"id": "ok", "type": "button", "label": "OK"}]}


def add(ua):
    fix_samples(ua, ["ooa.triggerloop"])
    ua.var("ev", "Dictionary")
    ua.var("ut_n", "Number", 0)
    ua.var("ut_text", "String", "")

    with ua.case("ooa.triggerloop/window_changes", "a test window opens while the loop waits",
                 "Each time a window opens or closes runs the steps when a window whose title fits opens; a parallel branch "
                 "opens the test window two seconds after the loop starts.") as f:
        f.set("ut_n", 0)
        with f.parallel(2, desc="Watch for the window while it opens"):
            with f.step("Watch"):
                with f.loop(desc="Each time the window opens", iterator="ooa.triggerloop/window_changes", **{"as": "ev"},
                            iterator_args={"title": "UT watched window*", "event": "opens", "max_events": 1, "max_seconds": 30}):
                    f.expr("ut_n", "$ut_n + 1", desc="Count it")
                    f.set("ut_text", "$ev.title$", desc="Keep its title")
            with f.step("Open the window"):
                f.act("core/delay", desc="Let the loop start", seconds=2)
                f.act("ooa.application/start_program", desc="Open the test window", program="powershell.exe",
                      arguments=["-NoProfile", "-WindowStyle", "Hidden", "-Command", winform_script("UT watched window")])
        ua.check_eq("ut_n", 1)
        ua.check_eq("ut_text", "UT watched window")
        f.act("ooa.window/wait_for", desc="Wait for the window", title="UT watched window", timeout_seconds=20, cont=True)
        close_test_window(f, "UT watched window")

    # A person pressing F9 is checked by hand: the watcher ignores keys that programs press (Windows marks them), so an
    # agent cannot press F9 for itself. Run "UT Trigger Loop (screen)" and press F9 to see that case count 1.
    with ua.case("ooa.triggerloop/key_pressed", "nobody presses F9: the loop ends at max_seconds",
                 "Each time a key is pressed runs the steps when a person presses the key (here F9); keys pressed by programs "
                 "are ignored, so the F9 this agent presses is not counted and the loop ends after 3 seconds with no event. "
                 "Only the key and window are reported, never typed text.") as f:
        f.set("ut_n", 0)
        with f.parallel(2, desc="Watch for F9 while a program presses it"):
            with f.step("Watch"):
                with f.loop(desc="Each time F9 is pressed", iterator="ooa.triggerloop/key_pressed", **{"as": "ev"},
                            iterator_args={"key": "F9", "max_events": 1, "max_seconds": 3}):
                    f.expr("ut_n", "$ut_n + 1", desc="Count it")
            with f.step("Press F9 by program"):
                f.act("core/delay", desc="Let the loop start", seconds=1)
                f.act("ooa.desktop/press_keys", desc="Press F9 (a program key press: ignored)", keys="f9")
        ua.check_eq("ut_n", 0)

    with ua.case("ooa.triggerloop/mouse_clicked", "no person clicks: the loop ends at max_seconds",
                 "Each click in a window runs the steps when a person clicks in the window (clicks made by programs are "
                 "ignored); nobody clicks in the test window, so the loop ends after 3 seconds with no event.") as f:
        f.act("ooa.application/start_program", desc="Open the test window", program="powershell.exe",
              arguments=["-NoProfile", "-WindowStyle", "Hidden", "-Command", winform_script("UT click window")])
        f.act("ooa.window/wait_for", desc="Wait for it", title="UT click window", timeout_seconds=30)
        f.set("ut_n", 0)
        with f.loop(desc="Each click in the window", iterator="ooa.triggerloop/mouse_clicked", **{"as": "ev"},
                    iterator_args={"window": "UT click window", "button": "left", "max_seconds": 3}):
            f.expr("ut_n", "$ut_n + 1", desc="Count it")
        ua.check_eq("ut_n", 0)
        close_test_window(f, "UT click window")

    with ua.case("ooa.triggerloop/form_events", "an open form nobody touches",
                 "Each event on an open form runs the steps when the person presses a button, changes a field or submits a "
                 "form shown with Wait off; nobody does, so the loop ends after 3 seconds with no event and the form is closed.") as f:
        f.act("ooa.file/write_text", desc="Write the test form", path="ut_form.form.json", text=json.dumps(FORM))
        f.act("ooa.forms/display", desc="Show the form without waiting", form="ut_form.form.json", name="utform", wait="no")
        f.set("ut_n", 0)
        with f.loop(desc="Each form event", iterator="ooa.triggerloop/form_events", **{"as": "ev"},
                    iterator_args={"name": "utform", "max_seconds": 3}):
            f.expr("ut_n", "$ut_n + 1", desc="Count it")
        ua.check_eq("ut_n", 0)
        f.act("ooa.forms/close", desc="Close the form", name="utform", cont=True)
