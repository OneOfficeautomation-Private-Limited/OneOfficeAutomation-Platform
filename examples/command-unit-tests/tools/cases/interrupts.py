"""Hand-written cases for the Interrupt handler commands (rules for pop-ups that may appear during a run)."""


def add(ua):
    ua.var("ut_rules", "List")
    ua.var("ut_n", "Number", 0)
    ua.var("ut_removed", "Boolean", False)
    ua.var("ut_handled", "Boolean", False)

    def fresh(f):
        f.act("ooa.interrupts/clear_rules", desc="Start without rules")

    with ua.case("ooa.interrupts/list_rules", "two rules listed",
                 "List rules gives the rules added so far: a rule that presses Enter on a session pop-up and one that ignores a "
                 "browser cookie banner.") as f:
        fresh(f)
        f.act("ooa.interrupts/add_rule", desc="Press Enter when the session warning shows", name="session warning",
              when_window="Session about to expire*", then="press_keys", target="{ENTER}", max_times=3)
        f.act("ooa.interrupts/add_rule", desc="Ignore the cookie banner", name="cookie banner", when_element="#cookie-banner", then="ignore")
        f.act("ooa.interrupts/list_rules", desc="List the rules", out={"rules": "ut_rules"})
        f.expr("ut_n", "len($ut_rules)", desc="Count them")
        ua.check_eq("ut_n", 2)
        f.act("ooa.interrupts/clear_rules", desc="Leave no rules behind")

    with ua.case("ooa.interrupts/remove_rule", "remove one, and one that is not there",
                 "Remove rule takes a rule away by its name and says whether there was one.") as f:
        fresh(f)
        f.act("ooa.interrupts/add_rule", desc="A rule to remove", name="popup", when_window="UT pop-up 9f3a", then="close_window")
        f.act("ooa.interrupts/remove_rule", desc="Remove it", name="popup", out={"removed": "ut_removed"})
        ua.check_eq("ut_removed", True)
        f.act("ooa.interrupts/remove_rule", desc="Remove it again", name="popup", out={"removed": "ut_removed"})
        ua.check_eq("ut_removed", False)

    with ua.case("ooa.interrupts/clear_rules", "no rules left",
                 "Clear rules removes every rule.") as f:
        f.act("ooa.interrupts/add_rule", desc="A rule", name="a", when_window="UT pop-up A 9f3a", then="close_window")
        f.act("ooa.interrupts/add_rule", desc="Another rule", name="b", when_window="UT pop-up B 9f3a", then="close_window")
        f.act("ooa.interrupts/clear_rules", desc="Clear them")
        f.act("ooa.interrupts/list_rules", desc="List the rules", out={"rules": "ut_rules"})
        f.expr("ut_n", "len($ut_rules)", desc="Count them")
        ua.check_eq("ut_n", 0)

    with ua.case("ooa.interrupts/check_now", "nothing to handle",
                 "Check now looks once for the rules' windows and handles what it finds; no window with that title is open, "
                 "so nothing is handled.") as f:
        fresh(f)
        f.act("ooa.interrupts/add_rule", desc="A rule for a window that is not open", name="never", when_window="UT never shown 9f3a",
              then="close_window")
        f.act("ooa.interrupts/check_now", desc="Check now", out={"handled": "ut_rules", "count": "ut_n"})
        ua.check_eq("ut_n", 0)
        f.expr("ut_n", "len($ut_rules)", desc="Count the rules handled")
        ua.check_eq("ut_n", 0)
        f.act("ooa.interrupts/clear_rules", desc="Leave no rules behind")
