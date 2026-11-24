"""Cases for the picture commands. Pictures are made by the agent: small PDFs with a black square turned into PNGs.

The commands that look at or click the screen are screen cases (input ut_screen); they look for a picture that cannot
be on the screen, so nothing is clicked.
"""
from helpers_office import fix_samples, fcase, screen_case, text_pdf

# a 300 x 200 page with a 20-point black square whose bottom-left corner is at (100, 80); the template is a 40-point
# page with the same square in its middle, so it matches the big page once, with its centre at (120, 110) from the top
BIG = text_pdf([[(100, 80, (20, 20))]], size=(300, 200))
BIG_MOVED = text_pdf([[(180, 30, (20, 20))]], size=(300, 200))
TEMPLATE = text_pdf([[(10, 10, (20, 20))]], size=(40, 40))
OTHER_TEMPLATE = text_pdf([[(0, 0, (40, 10))]], size=(40, 40))


def add(ua):
    fix_samples(ua)
    for n, t in (("files", "List"), ("big", "String"), ("tpl", "String"), ("moved", "String"), ("other", "String"),
                 ("found", "Boolean"), ("x", "Number"), ("y", "Number"), ("n", "Number"), ("same", "Boolean"),
                 ("pct", "Number"), ("ok", "Boolean"), ("changed", "Boolean"), ("diff", "String")):
        ua.var(n, t)

    def pictures(f, d):
        for name, content in (("big", BIG), ("tpl", TEMPLATE), ("moved", BIG_MOVED), ("other", OTHER_TEMPLATE)):
            f.act("ooa.file/write_text", desc="PDF for the picture " + name, path=d + "/" + name + ".pdf", text=content)
            f.act("ooa.pdf/to_images", desc="Turn it into " + name + ".png", path=d + "/" + name + ".pdf",
                  target_folder=d + "/" + name, dpi=72, out={"files": "files"})
            f.expr(name, "$files[0]", desc="The picture " + name)

    with fcase(ua, "ooa.image/find_in_image", "square found with its centre",
               "Find in image looks for a small picture inside a larger one and gives the centre of the match.") as (f, d):
        pictures(f, d)
        f.act("ooa.image/find_in_image", desc="Find the square", image="$big$", template="$tpl$", tolerance=5,
              out={"found": "found", "x": "x", "y": "y", "count": "n"})
        ua.check_eq("found", True)
        ua.check_eq("x", 110)
        ua.check_eq("y", 110)
    with fcase(ua, "ooa.image/compare_images", "same and different",
               "Compare images says whether two pictures are the same and how much differs; it can save a copy with "
               "the changes in red.") as (f, d):
        pictures(f, d)
        f.act("ooa.image/compare_images", desc="Compare a picture with itself", image="$big$", other="$big$",
              out={"same": "same"})
        ua.check_eq("same", True)
        f.act("ooa.image/compare_images", desc="Compare with the moved square", image="$big$", other="$moved$",
              difference_image=d + "/diff.png", out={"same": "same", "difference_percent": "pct", "difference_image": "diff"})
        ua.check_eq("same", False)
        ua.check("gt", "$pct$", 0, what="difference_percent")
        f.set("ok", False)
        with f.if_({"condition": "ooa.file/exists", "args": {"path": d + "/diff.png"}}, desc="Was the difference picture saved?"):
            f.set("ok", True)
        ua.check_eq("ok", True)
    with fcase(ua, "ooa.image/image_in_image", "square in the page, bar not",
               "Image in image is a condition: true when the template appears in the picture; the wide bar does not.") as (f, d):
        pictures(f, d)
        f.set("ok", False)
        with f.if_({"condition": "ooa.image/image_in_image", "args": {"image": "$big$", "template": "$tpl$", "tolerance": 5}},
                   desc="Is the square in the page?"):
            f.set("ok", True)
        ua.check_eq("ok", True)
        with f.if_({"condition": "ooa.image/image_in_image", "args": {"image": "$big$", "template": "$other$", "tolerance": 5}},
                   desc="Is the bar in the page?"):
            f.set("ok", False)
        ua.check_eq("ok", True)

    with screen_case(ua, "ooa.image/find_on_screen", "a picture that is not shown",
                     "Find on screen looks for a picture on the screen; a picture made only for this case is not there.") as (f, d):
        pictures(f, d)
        f.act("ooa.image/find_on_screen", desc="Look for the test picture", template="$other$", tolerance=0,
              out={"found": "found"})
        ua.check_eq("found", False)
    with screen_case(ua, "ooa.image/image_on_screen", "a picture that is not shown",
                     "Image on screen is a condition: true when the picture is visible.") as (f, d):
        pictures(f, d)
        f.set("ok", False)
        with f.if_({"condition": "ooa.image/image_on_screen", "args": {"template": "$other$", "tolerance": 0}}, desc="Is it shown?"):
            f.set("ok", True)
        ua.check_eq("ok", False)
    with screen_case(ua, "ooa.image/wait_for_image", "times out for a picture that is not shown",
                     "Wait for image waits until a picture appears; one that never does stops with IMAGE.NOT_FOUND.") as (f, d):
        pictures(f, d)
        ua.expect_error("IMAGE.NOT_FOUND", lambda ff: ff.act("ooa.image/wait_for_image", desc="Wait 2 seconds",
                                                             template="$other$", tolerance=0, timeout_seconds=2))
    with screen_case(ua, "ooa.image/click_image", "nothing is clicked when the picture is not shown",
                     "Click image finds a picture on the screen and clicks it; one that is not shown stops with an error "
                     "and nothing is clicked.") as (f, d):
        pictures(f, d)
        ua.expect_error("IMAGE.NOT_FOUND", lambda ff: ff.act("ooa.image/click_image", desc="Click the test picture",
                                                             template="$other$", tolerance=0))
    with screen_case(ua, "ooa.image/wait_for_screen_change", "no change in a corner, carry on",
                     "Wait for screen change waits until a region changes; with fail_on_timeout off it reports no change.") as (f, d):
        f.act("ooa.image/wait_for_screen_change", desc="Watch the top-left corner", x=0, y=0, width=20, height=20,
              timeout_seconds=0.6, threshold_percent=50, fail_on_timeout=False, out={"changed": "changed"})
        ua.check_eq("changed", False)
