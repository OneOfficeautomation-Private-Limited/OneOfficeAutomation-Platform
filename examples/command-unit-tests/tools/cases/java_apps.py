"""Java apps cases. No Java desktop program is open during the run, so the cases check the empty answers and the clear
errors; the same steps work on a Java (Swing/AWT) window when one is open (Java Access Bridge on)."""

W = "UT no such Java window 9f3a"


def add(ua):
    ua.var("ut_list", "List")
    ua.var("ut_n", "Number", 0)

    with ua.case("ooa.java_apps/list_apps", "no Java program of that name",
                 "List Java apps gives the open Java windows (title, process); none belongs to the made-up process.") as f:
        f.act("ooa.java_apps/list_apps", desc="Java windows of a made-up process", process="ut-no-such-java-9f3a", out={"apps": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count them")
        ua.check_eq("ut_n", 0)

    with ua.case("ooa.java_apps/find_objects", "push buttons of a window that is not open",
                 "Find Java objects lists the accessible objects of a Java window by name and role; with no such window there "
                 "are none (or a clear error when Java Access Bridge is off).") as f:
        f.set("ut_n", 0)
        f.act("ooa.java_apps/find_objects", desc="Find the push buttons", window_title=W, role="push button", timeout_seconds=0,
              out={"count": "ut_n"}, cont=True)
        ua.check_eq("ut_n", 0)

    with ua.case("ooa.java_apps/object_action", "a window that is not open",
                 "Java object action works on one object (click, set or get text, select, table cells...); with no such "
                 "window it fails with a clear error after its time-out.") as f:
        ua.expect_error("*", lambda ff: ff.act("ooa.java_apps/object_action", desc="Set the text of a field", window_title=W,
                                               role="text", name="Customer", action_="set_text", value="Acme", timeout_seconds=2))

    with ua.case("ooa.java_apps/pick_object", "nobody picks: time-out",
                 "Pick Java object (design time) waits for a person to point at a Java control; nobody does, so it ends with "
                 "its time-out error after 2 seconds.") as f:
        ua.expect_error("*", lambda ff: ff.act("ooa.java_apps/pick_object", desc="Wait 2 s for a pick", timeout_seconds=2))
