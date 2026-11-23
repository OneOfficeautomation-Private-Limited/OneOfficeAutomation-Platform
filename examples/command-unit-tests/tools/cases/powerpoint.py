"""Hand-written cases for the PowerPoint file commands the samples do not cover."""
from helpers_office import fix_samples, fcase

CLOSE = [("ooa.powerpoint/close", {})]


def add(ua):
    fix_samples(ua, close=CLOSE)
    for n, t in (("text", "String"), ("slides", "List"), ("n", "Number"), ("slide", "Number"), ("shape", "String")):
        ua.var(n, t)

    def pcase(cmd, title, why):
        return fcase(ua, cmd, title, why, close=CLOSE)

    def deck(f, d, slides=("Intro",)):
        f.act("ooa.powerpoint/create_presentation", desc="Create a new presentation", path=d + "/p.pptx")
        for s in slides:
            f.act("ooa.powerpoint/add_slide", desc="Add slide " + s, title=s, body="Body of " + s)

    with pcase("ooa.powerpoint/add_slide", "title and body",
               "Add slide adds a slide with a layout and fills its title and body; it returns the slide number.") as (f, d):
        f.act("ooa.powerpoint/create_presentation", desc="Create a new presentation", path=d + "/p.pptx")
        f.act("ooa.powerpoint/add_slide", desc="Add a slide", title="Agenda", body="Numbers", out={"slide": "slide"})
        f.act("ooa.powerpoint/add_slide", desc="Add a title slide first", layout="title", title="Welcome", position=1,
              out={"slide": "n"})
        f.act("ooa.powerpoint/read_text", desc="Read the slides", out={"slides": "slides", "text": "text"})
        f.act("ooa.powerpoint/close")
        ua.check_eq("slide", 1)
        ua.check_eq("n", 1)
        ua.check("contains", "$text$", "Agenda", what="text")
    with pcase("ooa.powerpoint/read_text", "one entry per slide",
               "Read text returns the text of each slide in order; the case checks there are two entries.") as (f, d):
        deck(f, d, ("One", "Two"))
        f.act("ooa.powerpoint/read_text", desc="Read the slides", out={"slides": "slides"})
        f.act("ooa.powerpoint/close")
        f.expr("n", "len($slides)", desc="Count the entries")
        ua.check_eq("n", 2)
    with pcase("ooa.powerpoint/slide_count", "three slides",
               "Slide count counts the slides.") as (f, d):
        deck(f, d, ("A", "B", "C"))
        f.act("ooa.powerpoint/slide_count", desc="Count", out={"count": "n"})
        f.act("ooa.powerpoint/close")
        ua.check_eq("n", 3)
    with pcase("ooa.powerpoint/copy_slide", "duplicate at the end",
               "Copy slide duplicates a slide with its text; the count goes up and the copy has the same title.") as (f, d):
        deck(f, d, ("Original",))
        f.act("ooa.powerpoint/copy_slide", desc="Copy slide 1", slide=1, out={"slide": "slide"})
        f.act("ooa.powerpoint/slide_count", desc="Count", out={"count": "n"})
        f.act("ooa.powerpoint/read_text", desc="Read the slides", out={"slides": "slides"})
        f.act("ooa.powerpoint/close")
        ua.check_eq("n", 2)
        ua.check_eq("slide", 2)
        f.expr("text", "$slides[1]", desc="The copy's text")
        ua.check("contains", "$text$", "Original", what="the copy")
    with pcase("ooa.powerpoint/delete_slide", "two in a row",
               "Delete slide removes slides; deleting slides 1 and 2 of three leaves the third.") as (f, d):
        deck(f, d, ("A", "B", "C"))
        f.act("ooa.powerpoint/delete_slide", desc="Delete slides 1 and 2", slide=1, count=2)
        f.act("ooa.powerpoint/read_text", desc="Read the slides", out={"slides": "slides", "text": "text"})
        f.act("ooa.powerpoint/close")
        f.expr("n", "len($slides)", desc="Count the entries")
        ua.check_eq("n", 1)
        ua.check("contains", "$text$", "Body of C", what="text")
    with pcase("ooa.powerpoint/find_replace_text", "fill a placeholder",
               "Find and replace text replaces text on every slide, e.g. a {{name}} placeholder, and counts it.") as (f, d):
        f.act("ooa.powerpoint/create_presentation", desc="Create a new presentation", path=d + "/p.pptx")
        f.act("ooa.powerpoint/add_slide", desc="A slide with a placeholder", title="Hello {{name}}", body="For {{name}}")
        f.act("ooa.powerpoint/find_replace_text", desc="Fill the placeholder", find="{{name}}", replace_with="Asha",
              out={"count": "n"})
        f.act("ooa.powerpoint/read_text", desc="Read the slides", out={"text": "text"})
        f.act("ooa.powerpoint/close")
        ua.check_eq("n", 2)
        ua.check("contains", "$text$", "Hello Asha", what="text")
    with pcase("ooa.powerpoint/format_text", "bold a word",
               "Format text makes each occurrence of a text bold and counts them (title and body both hold it).") as (f, d):
        deck(f, d, ("Quarter results",))
        f.act("ooa.powerpoint/format_text", desc="Bold the word results", text="results", bold=True, out={"count": "n"})
        f.act("ooa.powerpoint/close")
        ua.check_eq("n", 2)
    with pcase("ooa.powerpoint/insert_table", "table on a slide",
               "Insert table adds a table from a Data Table; its cells' text is read back with the slide text.") as (f, d):
        f.act("ooa.powerpoint/create_presentation", desc="Create a new presentation", path=d + "/p.pptx")
        f.act("ooa.powerpoint/add_slide", desc="A blank slide", layout="blank")
        f.act("ooa.powerpoint/insert_table", desc="Add the table", slide=1,
              table={"columns": ["Region", "Sales"], "rows": [["North", "120"]]}, out={"shape": "shape"})
        f.act("ooa.powerpoint/read_text", desc="Read the slides", out={"text": "text"})
        f.act("ooa.powerpoint/close")
        ua.check("notEmpty", "$shape$", what="shape")
        ua.check("contains", "$text$", "North", what="text")
    with pcase("ooa.powerpoint/open", "open a saved file",
               "Open opens a presentation as a session; the case saves one, opens it again and counts its slides.") as (f, d):
        deck(f, d, ("A", "B"))
        f.act("ooa.powerpoint/close", desc="Save and close", save=True)
        f.act("ooa.powerpoint/open", desc="Open it again", path=d + "/p.pptx")
        f.act("ooa.powerpoint/slide_count", desc="Count", out={"count": "n"})
        f.act("ooa.powerpoint/close")
        ua.check_eq("n", 2)
    with pcase("ooa.powerpoint/save", "save to a new path",
               "Save writes the presentation, here to a second path; that file's text holds the slide.") as (f, d):
        deck(f, d, ("Saved",))
        f.act("ooa.powerpoint/save", desc="Save to copy.pptx", path=d + "/copy.pptx")
        f.act("ooa.powerpoint/close")
        f.act("ooa.powerpoint/extract_text", desc="Read the copy", path=d + "/copy.pptx", out={"text": "text"})
        ua.check("contains", "$text$", "Saved", what="text")
    with pcase("ooa.powerpoint/save_as", "export as PDF",
               "Save as with format pdf exports the presentation; the PDF has one page per slide.") as (f, d):
        deck(f, d, ("A", "B"))
        f.act("ooa.powerpoint/save_as", desc="Save as PDF", path=d + "/p.pdf", format="pdf")
        f.act("ooa.powerpoint/close")
        f.act("ooa.pdf/page_count", desc="Count the PDF's pages", path=d + "/p.pdf", out={"count": "n"})
        ua.check_eq("n", 2)
    with pcase("ooa.powerpoint/close", "close with save",
               "Close ends the session; with save on, the new slide is in the file.") as (f, d):
        deck(f, d, ("Kept",))
        f.act("ooa.powerpoint/close", desc="Close and save", save=True)
        f.act("ooa.powerpoint/extract_text", desc="Read the file", path=d + "/p.pptx", out={"text": "text"})
        ua.check("contains", "$text$", "Kept", what="text")
