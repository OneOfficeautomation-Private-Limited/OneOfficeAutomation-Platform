"""Environment lock cases: the samples cover every command; their locks are renamed ut_... and all given back at the end."""
from helpers_platform import prefix_names, fix_sample_checks, drop, renumber, names_used

L = "ooa.locks/"


def add(ua):
    drop(ua, "refused before the agent runs")          # the validator refuses a lease under 5 seconds: no run at all
    prefix_names(ua, L, keys=("name",))
    fix_sample_checks(ua)
    ua.var("lk_released", "Boolean", False)
    ua.var("lk_held", "Boolean", False)
    f = ua.f
    with ua.case(L + "check", "a lock this run holds: held, mine, since when",
                 "Check lock tells whether a lock is held, by whom and since when. While this run holds ut_check "
                 "it is held, it is this run's and since is filled in."):
        ua.var("lk_mine", "Boolean", False)
        ua.var("lk_since", "String", "")
        f.act(L + "acquire", name="ut_check", lease_seconds=60, comment="unit test")
        f.act(L + "check", name="ut_check", out={"held": "lk_held", "mine": "lk_mine", "since": "lk_since"})
        ua.check_eq("lk_held", True)
        ua.check_eq("lk_mine", True)
        ua.check("notEmpty", "$lk_since$", what="since")
        f.act(L + "release", name="ut_check")
    with ua.case(L + "release", "tidy up: give back every ut_ lock",
                 "At the end every lock the agent used is given back (a lock it does not hold is left alone), "
                 "and the last one is checked to be free."):
        names = [n for n in names_used(ua, L, "name") if n.strip() and n.startswith("ut_")]
        for n in names:
            f.act(L + "release", desc="Give back " + n, name=n, out={"released": "lk_released"})
        f.act(L + "check", name=names[-1], out={"held": "lk_held"})
        ua.check_eq("lk_held", False)
    renumber(ua)
