"""Cases for the commands that drive the installed PowerPoint program.

insert_file and insert_video run without a window; paste needs a (minimised) PowerPoint window and the clipboard, so
its positive case is a screen case. No video file ships with Windows here, so insert_video has its error case only.
"""
from helpers_office import fix_samples, fcase, screen_case

CLOSE = [("ooa.powerpoint/close", {})]


def add(ua):
    fix_samples(ua, close=CLOSE)
    for n, t in (("shape", "String"), ("pos", "Dictionary"), ("n", "Number"), ("text", "String")):
        ua.var(n, t)

    def deck(f, d):
        f.act("ooa.powerpoint/create_presentation", desc="Create a presentation", path=d + "/deck.pptx")
        f.act("ooa.powerpoint/add_slide", desc="Add a blank slide", layout="blank")
        f.act("ooa.powerpoint/close", desc="Save and close", save=True)

    with fcase(ua, "ooa.powerpoint_desktop/insert_file", "embed a csv as an icon",
               "Insert file embeds a file on a slide as an icon; the case embeds a csv, saves to a new file and checks "
               "the shape name and that the new file was written.", close=CLOSE) as (f, d):
        deck(f, d)
        f.act("ooa.file/write_text", desc="The file to embed", path=d + "/orders.csv", text="id,qty\n1,2\n")
        f.act("ooa.powerpoint_desktop/insert_file", desc="Embed orders.csv on slide 1", path=d + "/deck.pptx", slide=1,
              file=d + "/orders.csv", label="Orders", save_as=d + "/out.pptx", out={"shape": "shape", "position": "pos"})
        ua.check("notEmpty", "$shape$", what="shape")
        f.act("ooa.powerpoint/open", desc="Open the result", path=d + "/out.pptx")
        f.act("ooa.powerpoint/slide_count", desc="Count slides", out={"count": "n"})
        f.act("ooa.powerpoint/close")
        ua.check_eq("n", 1)
    with screen_case(ua, "ooa.powerpoint_desktop/paste", "paste text from the clipboard",
                     "Paste puts what is on the clipboard onto a slide; the case copies a text and pastes it as text.") as (f, d):
        deck(f, d)
        f.act("ooa.desktop/clipboard_set", desc="Put a text on the clipboard", text="Pasted by the unit test")
        f.act("ooa.powerpoint_desktop/paste", desc="Paste it on slide 1", path=d + "/deck.pptx", slide=1,
              paste_as="text", out={"shape": "shape"})
        f.act("ooa.powerpoint/extract_text", desc="Read the presentation", path=d + "/deck.pptx", out={"text": "text"})
        ua.check("contains", "$text$", "Pasted by the unit test", what="text")
