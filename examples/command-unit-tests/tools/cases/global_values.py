"""Shared value cases on ut_team and ut_threshold. Locally the agent writes them to global-values.json in its own work
folder first; in One CommandCenter they must exist as shared values (ut_team = Finance, ut_threshold = 1000)."""
from helpers_platform import prefix_names, fix_sample_checks, drop, renumber, move_last_case_to

G = "ooa.global_values/"


def add(ua):
    drop(ua, "ooa.global_values/set")        # the samples' set cases write files in the sample runner's folders
    prefix_names(ua, G, keys=("name",))
    fix_sample_checks(ua)
    for v, t in (("gv_value", "Any"), ("gv_all", "Dictionary"), ("gv_text", "String"), ("gv_n", "Number")):
        ua.var(v, t)
    f = ua.f
    with ua.case(G + "get", "read a shared value as text and as a number",
                 "Get shared value reads one value by name. Locally the values are first written to the workspace "
                 "file global-values.json (in One CommandCenter that file is not used): ut_team is Finance and "
                 "ut_threshold read as a Number is 1000."):
        f.act("ooa.file/write_text", desc="The local shared values (only used outside One CommandCenter)",
              path="global-values.json", text='{"ut_team": "Finance", "ut_threshold": 1000}')
        f.act(G + "get", name="ut_team", type="String", out={"value": "gv_text"})
        ua.check_eq("gv_text", "Finance")
        f.act(G + "get", name="ut_threshold", type="Number", out={"value": "gv_n"})
        ua.check("eq", "$gv_n$", 1000, what="ut_threshold")
    move_last_case_to(ua, 1)

    with ua.case(G + "get_all", "only the values whose names start ut_",
                 "Get all shared values gives every value as a dictionary, here only the names starting ut_."):
        f.act(G + "get_all", prefix="ut_", out={"values": "gv_all"})
        ua.check("contains", "$gv_all$", "ut_team", what="the names")
        ua.check("contains", "$gv_all$", "ut_threshold", what="the names")
    ua.condition_case(G + "is_defined", "a value that exists",
                      "Shared value is defined is true for a value that exists (ut_team).", {"name": "ut_team"}, True)
    with ua.case(G + "set", "change a value, read it back, then put it back",
                 "Set shared value changes a value that exists; a later Get sees the new value. The old value is put "
                 "back at the end of the case."):
        f.act(G + "set", name="ut_threshold", value="2500", type="Number", out={"value": "gv_n"})
        ua.check("eq", "$gv_n$", 2500, what="the value set")
        f.act(G + "get", name="ut_threshold", type="Number", out={"value": "gv_n"})
        ua.check("eq", "$gv_n$", 2500, what="the value read back")
        f.act(G + "set", name="ut_threshold", value=1000, type="Number")
    with ua.case(G + "set", "only existing values are changed",
                 "Set shared value does not make new values: a name that is not defined fails with GLOBALS.NOT_FOUND."):
        ua.expect_error("GLOBALS.NOT_FOUND", lambda ff: ff.act(G + "set", name="ut_not_defined", value=1))
    with ua.case(G + "set", "a value that does not fit the type",
                 "Set shared value refuses text for a Number: GLOBALS.WRONG_TYPE, and the value is unchanged."):
        ua.expect_error("GLOBALS.WRONG_TYPE", lambda ff: ff.act(G + "set", name="ut_threshold", value="lots", type="Number"))
    with ua.case(G + "set", "a name that is not allowed",
                 "Set shared value refuses a name with a space that starts with a digit: GLOBALS.BAD_NAME."):
        ua.expect_error("GLOBALS.BAD_NAME", lambda ff: ff.act(G + "set", name="9 lives", value=1))
    renumber(ua)
