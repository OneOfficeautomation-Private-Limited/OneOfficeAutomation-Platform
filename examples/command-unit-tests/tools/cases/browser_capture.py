"""Browser design-time cases: they open a visible browser on the stand-in's order form. Pick element and Record wait
for a person, so their cases only check that they give up cleanly after a short time-out. Drives the screen."""

WEB = "$standin_url$/dsk/web/"
ITEM = {"technology": "web", "search_by": "properties", "index": 0,
        "properties": [{"name": "class", "value": "item", "match": "equals", "use": True}]}


def add(ua):
    ua.var("ut_n", "Number", 0)
    ua.var("ut_any", "Any")

    with ua.case("ooa.browser/test_object", "the list items on the order form",
                 "Test capture opens the page, outlines every element that matches a captured object for a moment and says "
                 "how many match: the three list items.") as f:
        f.act("ooa.browser/test_object", desc="Test the captured list item", url=WEB + "form.html", object=ITEM, browser="edge",
              wait_seconds=10, show_seconds=1, out={"count": "ut_n", "first": "ut_any"})
        ua.check_eq("ut_n", 3)

    with ua.case("ooa.browser/pick_element", "nobody picks: it gives up",
                 "Pick element (design time) opens the page and waits for a person to click an element; nobody does, so it "
                 "stops after its time-out (3 seconds) with an error.") as f:
        ua.expect_error("*", lambda ff: ff.act("ooa.browser/pick_element", desc="Wait 3 s for a pick", url=WEB + "form.html",
                                               browser="edge", timeout_minutes=0.05))

    with ua.case("ooa.browser/record", "nothing done: only the Open step",
                 "Record web actions (design time) records what a person does on the page until the time-out (3 seconds); "
                 "a recording always starts with the step that opens the page, and nobody acts, so that is the only step.") as f:
        f.act("ooa.browser/record", desc="Record for 3 s", url=WEB + "form.html", browser="edge", timeout_minutes=0.05,
              out={"count": "ut_n"})
        ua.check_eq("ut_n", 1)
