"""AI screen cases with the stand-in's scripted vision model ($standin_url$/dsk/ai, OpenAI-compatible). The model always
places the control it is asked for in the middle of the screen (unless the description says it is missing), so each
case first opens a test window in the middle of the screen whose text box fills it. Drives the screen."""
from helpers_desktop import close_test_window, desktop_object, open_test_window

W = "UT vision window"
FILL = ("$t = New-Object Windows.Forms.TextBox; $t.Name = 'notes'; $t.Multiline = $true; $t.Dock = 'Fill'; $f.Controls.Add($t); "
        "$f.TopMost = $true; ")
MODEL = {"provider": "openai_compatible", "base_url": "$standin_url$/dsk/ai", "model": "ut-vision"}
NOTES = desktop_object(W, ControlType="Edit", AutomationId="notes")


def add(ua):
    for n, t in (("ut_ok", "Boolean"), ("ut_text", "String"), ("ut_x", "Number"), ("ut_y", "Number"), ("ut_n", "Number")):
        ua.var(n, t)

    with ua.case("ooa.ai_screen/find_on_screen", "a control that is there, and one that is not",
                 "Find on screen sends a picture of the screen to the model and gives where the described control is, "
                 "with the model's confidence; for a missing control found is false.") as f:
        open_test_window(f, W, FILL)
        f.act("ooa.ai_screen/find_on_screen", desc="Find the notes box", description="the large notes text box", **MODEL,
              out={"found": "ut_ok", "x": "ut_x", "y": "ut_y", "confidence": "ut_n"})
        ua.check_eq("ut_ok", True)
        ua.check("gt", "$ut_x$", 0, what="x")
        ua.check("ge", "$ut_n$", 0.6, what="confidence")
        f.act("ooa.ai_screen/find_on_screen", desc="Look for a missing control", description="a Delete button that is missing", **MODEL,
              out={"found": "ut_ok"})
        ua.check_eq("ut_ok", False)
        close_test_window(f, W)

    with ua.case("ooa.ai_screen/read_described", "the text the model reads",
                 "Read described control asks the model for the text of the described control.") as f:
        open_test_window(f, W, FILL)
        f.act("ooa.ai_screen/read_described", desc="Read the notes box", description="the notes text box", **MODEL,
              out={"text": "ut_text", "found": "ut_ok"})
        ua.check_eq("ut_ok", True)
        ua.check_eq("ut_text", "UT screen text")
        close_test_window(f, W)

    with ua.case("ooa.ai_screen/click_described", "click into the notes box",
                 "Click described control clicks the control the model finds; the click puts the cursor in the notes box, "
                 "so keys typed next land there.") as f:
        open_test_window(f, W, FILL)
        f.act("ooa.ai_screen/click_described", desc="Click the notes box", description="the notes text box", **MODEL,
              out={"x": "ut_x", "y": "ut_y"})
        f.act("ooa.desktop/press_keys", desc="Type abc", keys="a b c")
        f.act("ooa.desktop_ui/object_action", desc="Read the box", object=NOTES, action_="get_text", out={"result": "ut_text"})
        ua.check("contains", "$ut_text$", "abc", what="notes")
        close_test_window(f, W)

    with ua.case("ooa.ai_screen/type_described", "type into the notes box",
                 "Type into described control clicks the control the model finds and types the text.") as f:
        open_test_window(f, W, FILL)
        f.act("ooa.ai_screen/type_described", desc="Type into the notes box", description="the notes text box", text="typed by vision",
              **MODEL)
        f.act("ooa.desktop_ui/object_action", desc="Read the box", object=NOTES, action_="get_text", out={"result": "ut_text"})
        ua.check("contains", "$ut_text$", "typed by vision", what="notes")
        close_test_window(f, W)
