"""Desktop objects cases on a Windows Forms test window ("UT objects window") with a label, a text box, a check box, a
drop-down list, a button that writes into the label, and a table (DataGridView) of two people. Drives the screen."""
from helpers_desktop import add_converted, close_test_window, desktop_object, find, fix_samples, open_test_window, remove_case

W = "UT objects window"
BODY = ("$l = New-Object Windows.Forms.Label; $l.Name = 'status'; $l.Text = 'Ready'; $l.SetBounds(10, 10, 300, 20); $f.Controls.Add($l); "
        "$t = New-Object Windows.Forms.TextBox; $t.Name = 'customer'; $t.SetBounds(10, 40, 200, 24); $f.Controls.Add($t); "
        "$c = New-Object Windows.Forms.CheckBox; $c.Name = 'urgent'; $c.Text = 'Urgent'; $c.SetBounds(10, 70, 120, 24); $f.Controls.Add($c); "
        "$d = New-Object Windows.Forms.ComboBox; $d.Name = 'colour'; $d.DropDownStyle = 'DropDownList'; [void]$d.Items.AddRange(@('Red','Green','Blue')); "
        "$d.SetBounds(10, 100, 120, 24); $f.Controls.Add($d); "
        "$b = New-Object Windows.Forms.Button; $b.Name = 'save'; $b.Text = 'Save'; $b.SetBounds(220, 40, 80, 26); "
        "$b.Add_Click({ $l.Text = 'Saved ' + $t.Text }); $f.Controls.Add($b); "
        "$g = New-Object Windows.Forms.DataGridView; $g.Name = 'people'; $g.SetBounds(10, 140, 400, 120); $g.AllowUserToAddRows = $false; "
        "[void]$g.Columns.Add('Name', 'Name'); [void]$g.Columns.Add('City', 'City'); [void]$g.Rows.Add('Ann', 'Oslo'); "
        "[void]$g.Rows.Add('Bob', 'Rome'); $f.Controls.Add($g); ")


def obj(**props):
    return desktop_object(W, **props)


CUSTOMER = obj(ControlType="Edit", AutomationId="customer")
SAVE = obj(ControlType="Button", Name="Save")
STATUS = obj(ControlType="Text", AutomationId="status")
URGENT = obj(ControlType="CheckBox", Name="Urgent")
COLOUR = obj(ControlType="ComboBox", AutomationId="colour")
PEOPLE = obj(ControlType="Table", AutomationId="people")


def add(ua):
    fix_samples(ua, ["ooa.desktop_ui"])
    # this sample's error comes when the flow is checked, so in an agent it would stop the whole agent: left out
    remove_case(ua, "get_parent - levels must be at least 1")
    for s in find("ooa.desktop_ui/", requires="desktop"):
        add_converted(ua, s)
    for n, t in (("ut_text", "String"), ("ut_n", "Number"), ("ut_ok", "Boolean"), ("ut_any", "Any"), ("ut_list", "List"),
                 ("ut_dict", "Dictionary"), ("ut_table", "Table"), ("ut_cond", "Boolean")):
        ua.var(n, t)

    def start(f):
        open_test_window(f, W, BODY)

    def end(f):
        close_test_window(f, W)

    with ua.case("ooa.desktop_ui/object_action", "type, click and read back",
                 "Desktop object action does one thing to a captured control: set the text box, click Save, read the label "
                 "the button changed, tick the check box, pick a list item.") as f:
        start(f)
        f.act("ooa.desktop_ui/object_action", desc="Type the customer", object=CUSTOMER, action_="set_text", text="Acme")
        f.act("ooa.desktop_ui/object_action", desc="Click Save", object=SAVE, action_="click")
        f.act("ooa.desktop_ui/object_action", desc="Read the status label", object=STATUS, action_="get_text", out={"result": "ut_text"})
        ua.check_eq("ut_text", "Saved Acme")
        f.act("ooa.desktop_ui/object_action", desc="Tick Urgent", object=URGENT, action_="check")
        f.act("ooa.desktop_ui/object_action", desc="Is it ticked?", object=URGENT, action_="get_status", out={"result": "ut_any"})
        ua.check("eq", "$ut_any$", "checked", what="state (checked, unchecked or indeterminate)")
        f.act("ooa.desktop_ui/object_action", desc="Pick Green", object=COLOUR, action_="select_item", item="Green")
        f.act("ooa.desktop_ui/object_action", desc="Read the selection", object=COLOUR, action_="get_selected", out={"result": "ut_text"})
        ua.check_eq("ut_text", "Green")
        end(f)

    with ua.case("ooa.desktop_ui/find_objects", "every button",
                 "Find desktop objects gives every control that matches the object's properties.") as f:
        start(f)
        f.act("ooa.desktop_ui/find_objects", desc="Find the buttons", object=obj(ControlType="Button"), timeout_seconds=10,
              out={"objects": "ut_list", "count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="buttons")
        end(f)

    with ua.case("ooa.desktop_ui/get_object_properties", "the Save button",
                 "Get object properties reads the control's recorded properties (name, type, automation id, position...).") as f:
        start(f)
        f.act("ooa.desktop_ui/get_object_properties", desc="Read the button's properties", object=SAVE, out={"properties": "ut_dict"})
        ua.check("notEmpty", "$ut_dict$", what="properties")
        end(f)

    with ua.case("ooa.desktop_ui/get_table", "the people grid",
                 "Get desktop table reads a grid or list control into a data table.") as f:
        start(f)
        f.act("ooa.desktop_ui/get_table", desc="Read the grid", object=PEOPLE, out={"table": "ut_table", "row_count": "ut_n"})
        ua.check_eq("ut_n", 2)
        end(f)

    with ua.case("ooa.desktop_ui/wait_for_object", "the window's button, and one that never comes",
                 "Wait for desktop object waits for a control to appear (or go); with fail_on_timeout off it says whether it came.") as f:
        start(f)
        f.act("ooa.desktop_ui/wait_for_object", desc="Wait for Save", object=SAVE, timeout_seconds=10, out={"found": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("ooa.desktop_ui/wait_for_object", desc="Wait 2 s for a missing button", object=obj(ControlType="Button", Name="Nope"),
              timeout_seconds=2, fail_on_timeout=False, out={"found": "ut_ok"})
        ua.check_eq("ut_ok", False)
        end(f)

    with ua.case("ooa.desktop_ui/object_exists", "the text box, and a missing control",
                 "Desktop object exists is a condition: true when the control is there (or enabled, or focused).") as f:
        start(f)
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.desktop_ui/object_exists", "args": {"object": CUSTOMER, "wait_seconds": 5}}, desc="Is the box there?"):
            f.set("ut_cond", True)
        ua.check("eq", "$ut_cond$", True, what="exists")
        with f.if_({"condition": "ooa.desktop_ui/object_exists", "args": {"object": obj(ControlType="Button", Name="Nope")}},
                   desc="Is a missing button there?"):
            f.set("ut_cond", False)
        ua.check("eq", "$ut_cond$", True, what="missing")
        end(f)

    with ua.case("ooa.desktop_ui/get_text_at_point", "the label's text",
                 "Get text at a point reads the text of the control under a screen point; the label's centre comes from its "
                 "coordinates.") as f:
        start(f)
        f.act("ooa.desktop_ui/object_action", desc="Where is the label?", object=STATUS, action_="get_coordinates", out={"result": "ut_dict"})
        f.act("ooa.desktop_ui/get_text_at_point", desc="Read the text there", x={"expr": "$ut_dict.x + 5"}, y={"expr": "$ut_dict.y + 5"},
              out={"text": "ut_text"})
        ua.check("contains", "$ut_text$", "Ready", what="text")
        end(f)

    with ua.case("ooa.desktop_ui/find_legacy_objects", "push buttons through MSAA",
                 "Find legacy (MSAA) objects lists the window's accessible objects by role and name.") as f:
        start(f)
        f.act("ooa.desktop_ui/find_legacy_objects", desc="The push buttons", window_title=W, role="push button", out={"objects": "ut_list",
                                                                                                                   "count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="objects")
        end(f)

    with ua.case("ooa.desktop_ui/legacy_object_action", "set and read the text box",
                 "Legacy (MSAA) object action works on an accessible object: set its value, read it back, press Save.") as f:
        start(f)
        f.act("ooa.desktop_ui/legacy_object_action", desc="Set the text box", window_title=W, role="editable text", action_="set_value",
              value="Globex")
        f.act("ooa.desktop_ui/legacy_object_action", desc="Read it back", window_title=W, role="editable text", action_="get_value",
              out={"result": "ut_text"})
        ua.check_eq("ut_text", "Globex")
        f.act("ooa.desktop_ui/legacy_object_action", desc="Press Save", window_title=W, role="push button", name="Save")
        end(f)

    with ua.case("ooa.desktop_ui/pick_object", "nobody picks: time-out",
                 "Pick desktop object (design time) waits for a person to point at a control; nobody does in an unattended run, "
                 "so it ends with its time-out error after 2 seconds.") as f:
        ua.expect_error("*", lambda ff: ff.act("ooa.desktop_ui/pick_object", desc="Wait 2 s for a pick", timeout_seconds=2))

    with ua.case("ooa.desktop_ui/record", "nothing done in 3 seconds",
                 "Record desktop actions records what a person does in a window until the time-out; nobody acts, so it records "
                 "no steps.") as f:
        start(f)
        f.act("ooa.desktop_ui/record", desc="Record for 3 s", timeout_seconds=3, screenshot=False, window_title=W, out={"count": "ut_n"})
        ua.check_eq("ut_n", 0)
        end(f)
