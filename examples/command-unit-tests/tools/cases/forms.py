"""Interactive form cases: the sample form UT Form (agents/items/form) is shown without waiting and every change is
made by the agent itself, then read back where the form lets it. It opens a window on the runner's screen; nobody
needs to touch it. Case 1 opens the form; the others use it (by its name ut_form)."""
from helpers_platform import renumber, items_input, copy_item

F = "ooa.forms/"
N = "ut_form"
FORM = "ut-items/UT Form.form.json"


def add(ua):
    items_input(ua)
    for v, t in (("fm_value", "Any"), ("fm_values", "Dictionary"), ("fm_name", "String"), ("fm_addr", "String"),
                 ("fm_valid", "Boolean"), ("fm_errors", "Dictionary"), ("fm_n", "Number"), ("fm_out", "Boolean"),
                 ("fm_event", "String")):
        ua.var(v, t)
    f = ua.f

    def get(el, var="fm_value"):
        f.act(F + "get", name=N, element=el, out={"value": var})

    def valid(expected, what):
        f.act(F + "validate", name=N, show_messages=True, out={"valid": "fm_valid", "errors": "fm_errors"})
        ua.check("eq", "$fm_valid$", expected, what=what)

    with ua.case(F + "display", "show the form without waiting, with values filled in",
                 "Display form shows a form from the designer. With wait no it stays open and the agent goes on; "
                 "values fill fields in, and the form's name is given back for the other form commands."):
        copy_item(f, "form/UT Form.form.json", FORM)
        f.act(F + "display", form=FORM, name=N, title="UT form test (closes by itself)", wait="no",
              values={"full_name": "Ann Lee", "email": "ann@example.com", "amount": 120}, width=600, height=700,
              out={"name": "fm_name", "address": "fm_addr"})
        ua.check_eq("fm_name", N)
        ua.check("notEmpty", "$fm_addr$", what="the form's address")
    with ua.case(F + "get", "one field, then every answer",
                 "Get value reads one field's current value, or every answer as a dictionary when no field is given."):
        get("full_name")
        ua.check("eq", "$fm_value$", "Ann Lee", what="full_name")
        f.act(F + "get", name=N, out={"value": "fm_values"})
        ua.check("contains", "$fm_values$", "email", what="the answers")
    with ua.case(F + "set", "change a field, then append to another",
                 "Set value changes a field (amount to 250) or adds to its text (details)."):
        f.act(F + "set", name=N, element="amount", value=250)
        get("amount")
        ua.check("eq", "$fm_value$", 250, what="amount")
        f.act(F + "set", name=N, element="details", value="Taxi")
        f.act(F + "set", name=N, element="details", value=" to the airport", how="append")
        get("details")
        ua.check("eq", "$fm_value$", "Taxi to the airport", what="details")
    with ua.case(F + "assign", "fill a drop-down and choose an option",
                 "Assign options fills a drop-down with options (here one more, Training) and chooses one."):
        f.act(F + "assign", name=N, element="category", options=["Travel", "Meals", "Office", "Training"], select="Training")
        get("category")
        ua.check("eq", "$fm_value$", "Training", what="category")
    with ua.case(F + "validate", "required fields and number limits",
                 "Validate checks the answers: with the box not ticked and an amount over 5000 the form is not "
                 "valid and the errors name amount; with both put right it is valid."):
        f.act(F + "set", name=N, element="amount", value=9999)
        valid(False, "valid with a wrong amount")
        ua.check("contains", "$fm_errors$", "amount", what="the errors")
        f.act(F + "set", name=N, element="agree", value=True)
        f.act(F + "set", name=N, element="amount", value=250)
        valid(True, "valid when put right")
    with ua.case(F + "disable", "a disabled field is not checked",
                 "Disable greys out a field; a disabled field is not checked, so an amount over the limit no longer "
                 "makes the form invalid."):
        f.act(F + "set", name=N, element="amount", value=9999)
        f.act(F + "disable", name=N, element="amount")
        valid(True, "valid with amount disabled")
    with ua.case(F + "enable", "an enabled field is checked again",
                 "Enable lets a field be used again, and it is checked again: the amount over the limit makes the "
                 "form invalid."):
        f.act(F + "enable", name=N, element="amount")
        valid(False, "valid with amount enabled")
    with ua.case(F + "hide", "a hidden field is not checked",
                 "Hide hides a field; like a disabled one it is not checked."):
        f.act(F + "hide", name=N, element="amount")
        valid(True, "valid with amount hidden")
    with ua.case(F + "show", "a shown field is checked again",
                 "Show brings a hidden field back; it is checked again, so the form is invalid until the amount is "
                 "put right."):
        f.act(F + "show", name=N, element="amount")
        valid(False, "valid with amount shown")
        f.act(F + "set", name=N, element="amount", value=250)
        valid(True, "valid with the amount put right")
    with ua.case(F + "change_label", "a new label for a field",
                 "Change label changes the text shown for a field; its value is kept."):
        f.act(F + "change_label", name=N, element="full_name", label="Name of the claimant")
        get("full_name")
        ua.check("eq", "$fm_value$", "Ann Lee", what="full_name after the new label")
    with ua.case(F + "change_title", "a new window title",
                 "Change title changes the title on the form's window; the form stays open with its answers."):
        f.act(F + "change_title", name=N, title="UT form test - title changed")
        get("email")
        ua.check("eq", "$fm_value$", "ann@example.com", what="email after the new title")
    with ua.case(F + "highlight", "a warning outline with a message",
                 "Highlight draws a coloured outline round a field, here as a warning with a message under it; "
                 "the field's value is unchanged."):
        f.act(F + "highlight", name=N, element="email", kind="warning", message="Check this address")
        f.act(F + "highlight", name=N, element="full_name", color="#2563eb")
        get("email")
        ua.check("eq", "$fm_value$", "ann@example.com", what="email")
    with ua.case(F + "unhighlight", "take the outline away",
                 "Unhighlight removes a field's highlight; the field's value is unchanged."):
        f.act(F + "unhighlight", name=N, element="email")
        get("email")
        ua.check("eq", "$fm_value$", "ann@example.com", what="email")
    with ua.case(F + "dynamic_area", "one line per expense in the dynamic area",
                 "Dynamic area replaces what an area shows with new fields (two expense lines); their answers are "
                 "set and read like other fields."):
        f.act(F + "dynamic_area", name=N, element="lines", how="replace",
              fields=[{"id": "line_taxi", "type": "number", "label": "Taxi"},
                      {"id": "line_train", "type": "number", "label": "Train"}], out={"count": "fm_n"})
        ua.check("eq", "$fm_n$", 2, what="fields in the area")
        f.act(F + "set", name=N, element="line_train", value=30)
        get("line_train")
        ua.check("eq", "$fm_value$", 30, what="line_train")
    with ua.case(F + "set_focus", "put the cursor in a field",
                 "Set focus puts the cursor in a field; the form's answers do not change."):
        f.act(F + "set_focus", name=N, element="email")
        get("email")
        ua.check("eq", "$fm_value$", "ann@example.com", what="email")
    with ua.case(F + "hide_form", "minimise the form; its answers are kept",
                 "Hide form moves the window out of the way while the agent works; its answers are kept."):
        f.act(F + "hide_form", name=N)
        get("amount")
        ua.check("eq", "$fm_value$", 250, what="amount while hidden")
    with ua.case(F + "show_form", "bring the form back",
                 "Show form brings a hidden form back in front; its answers are still there."):
        f.act(F + "show_form", name=N)
        get("full_name")
        ua.check("eq", "$fm_value$", "Ann Lee", what="full_name")
    with ua.case(F + "wait_for_event", "nobody presses anything: a time-out that carries on",
                 "Wait for event waits for a button, a change, a submit or the window closing. Nobody touches the "
                 "form, so after 3 seconds it carries on with timed_out true."):
        f.act(F + "wait_for_event", name=N, events="any", timeout_seconds=3, fail_on_timeout=False,
              out={"timed_out": "fm_out", "event": "fm_event"})
        ua.check_eq("fm_out", True)
    with ua.case(F + "reset", "every field back to its designer default",
                 "Reset puts every field back to its default from the designer (amount 100) or empty (full_name)."):
        f.act(F + "reset", name=N)
        get("amount")
        ua.check("eq", "$fm_value$", 100, what="amount after reset")
        get("full_name")
        ua.check("empty", "$fm_value$", what="full_name after reset")
    with ua.case(F + "close", "close the window and get the answers",
                 "Close form closes the window and gives back the last answers (the reference field keeps its "
                 "read-only default)."):
        f.act(F + "close", name=N, out={"values": "fm_values"})
        f.expr("fm_value", "$fm_values.reference")
        ua.check("eq", "$fm_value$", "EXP-UT-1", what="reference")
    renumber(ua)
