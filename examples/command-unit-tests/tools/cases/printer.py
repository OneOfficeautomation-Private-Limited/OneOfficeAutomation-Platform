"""Printer cases: the samples (rewritten for this agent) plus a cross-check; nothing is ever printed or changed."""
from helpers_desktop import fix_samples


def add(ua):
    fix_samples(ua, ["ooa.printer"])
    ua.var("ut_default", "String", "")
    ua.var("ut_default2", "String", "")
    ua.var("ut_printers", "List")
    with ua.case("ooa.printer/list_printers", "the default printer is in the list",
                 "List printers gives every printer and the default one; it is the same default Get default printer gives.") as f:
        f.act("ooa.printer/list_printers", desc="List the printers", out={"printers": "ut_printers", "default_printer": "ut_default"})
        f.act("ooa.printer/get_default", desc="Read the default printer", out={"printer": "ut_default2"})
        ua.check("eq", "$ut_default$", "$ut_default2$", what="default printer")
        with f.if_({"op": "notEmpty", "left": "$ut_default$"}, desc="Is there a default printer?"):
            ua.check("contains", "$ut_printers$", "$ut_default$", what="printers")
