"""UT Item Form: shows the sample form UT Form.form.json with answers filled in, checks them, submits nothing and
closes it. Opens a window on the runner's screen (nobody needs to touch it)."""
from helpers_platform import renumber, items_input, copy_item

F = "ooa.forms/"
FORM = "ut-items/UT Form.form.json"
N = "ut_item_form"


def add(ua):
    items_input(ua)
    for v, t in (("uf_values", "Dictionary"), ("uf_value", "Any"), ("uf_valid", "Boolean"), ("uf_errors", "Dictionary")):
        ua.var(v, t)
    f = ua.f
    with ua.case(F + "display", "the sample form with its defaults and answers",
                 "Display form shows UT Form without waiting, filling in name and email; the designer's defaults "
                 "(amount 100, category Travel, reference EXP-UT-1) are there too."):
        copy_item(f, "form/UT Form.form.json", FORM)
        f.act(F + "display", form=FORM, name=N, wait="no", values={"full_name": "Ann Lee", "email": "ann@example.com"})
        f.act(F + "get", name=N, out={"value": "uf_values"})
        for k, want in (("amount", 100), ("category", "Travel"), ("reference", "EXP-UT-1"), ("payment", "Own money")):
            f.expr("uf_value", "$uf_values." + k)
            ua.check("eq", "$uf_value$", want, what=k)
    with ua.case(F + "validate", "the form's own checks",
                 "The sample's checks: the confirmation box is required, the email must be an address and the amount "
                 "at most 5000. With a bad email the form is invalid and email is named; put right, it is valid."):
        f.act(F + "set", name=N, element="email", value="not an address")
        f.act(F + "set", name=N, element="agree", value=True)
        f.act(F + "validate", name=N, out={"valid": "uf_valid", "errors": "uf_errors"})
        ua.check_eq("uf_valid", False)
        ua.check("contains", "$uf_errors$", "email", what="the errors")
        f.act(F + "set", name=N, element="email", value="ann@example.com")
        f.act(F + "validate", name=N, out={"valid": "uf_valid"})
        ua.check_eq("uf_valid", True)
    with ua.case(F + "close", "close it and keep the answers",
                 "Close form closes the window and gives back the answers."):
        f.act(F + "close", name=N, out={"values": "uf_values"})
        f.expr("uf_value", "$uf_values.full_name")
        ua.check("eq", "$uf_value$", "Ann Lee", what="full_name")
    renumber(ua)
