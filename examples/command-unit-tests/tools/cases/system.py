"""Cases for the System commands the samples do not cover.

Lock, log off, restart, shut down, set screen resolution and empty recycle bin only run their refusals (no confirm,
bad drive), which the samples cover. Show desktop minimises every window, so it is a screen case.
"""
from helpers_office import fix_samples, fcase, screen_case


def add(ua):
    fix_samples(ua)
    for n, t in (("w", "Number"), ("screens", "List"), ("n", "Number"), ("v", "String"), ("procs", "List")):
        ua.var(n, t)

    with fcase(ua, "ooa.system/get_screen_resolution", "main screen",
               "Get screen resolution reads the main screen's size and scaling and lists every screen (it only reads).") as (f, d):
        f.act("ooa.system/get_screen_resolution", desc="Read the resolution", out={"width": "w", "screens": "screens"})
        ua.check("gt", "$w$", 0, what="width")
        f.expr("n", "len($screens)", desc="Count the screens")
        ua.check("ge", "$n$", 1, what="screens")
    with fcase(ua, "ooa.system/set_environment_variable", "process scope only",
               "Set environment variable with process scope sets it for this run only; the case reads it back and "
               "removes it again.") as (f, d):
        f.act("ooa.system/set_environment_variable", desc="Set a variable for this run", name="OOA_UT_OFFICE_VAR",
              value="42", scope="process")
        f.act("ooa.system/get_environment_variable", desc="Read it", name="OOA_UT_OFFICE_VAR", out={"value": "v"})
        ua.check_eq("v", "42")
        f.act("ooa.system/delete_environment_variable", desc="Remove it", name="OOA_UT_OFFICE_VAR", scope="process")
    with screen_case(ua, "ooa.system/show_desktop", "minimise every window",
                     "Show desktop minimises every window, like Windows+D; the case checks the step ran.") as (f, d):
        f.act("ooa.system/show_desktop", desc="Show the desktop")
        f.act("ooa.system/list_processes", desc="This run is still going", name_contains="java", out={"count": "n"})
        ua.check("gt", "$n$", 0, what="processes")
