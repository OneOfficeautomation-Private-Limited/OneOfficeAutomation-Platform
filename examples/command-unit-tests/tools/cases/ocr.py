"""Cases for the OCR commands. Pictures are made by the agent: a PDF with large text turned into a PNG.

Commands that read or click the screen are screen cases (input ut_screen). Adding a Windows OCR language changes the
computer (it needs administrator rights), so only its refusal of a bad language tag runs.
"""
from helpers_office import fix_samples, fcase, screen_case, text_pdf

PAGE = text_pdf([[(20, 110, "INVOICE 4711"), (20, 40, "Total 115.00")]], size=(400, 200), font_size=36)


def add(ua):
    fix_samples(ua)
    for n, t in (("text", "String"), ("files", "List"), ("png", "String"), ("found", "Boolean"), ("n", "Number"),
                 ("x", "Number"), ("engine", "String"), ("langs", "List"), ("ok", "Boolean"), ("status", "Number")):
        ua.var(n, t)

    def picture(f, d):
        f.act("ooa.file/write_text", desc="A PDF with large text", path=d + "/page.pdf", text=PAGE)
        f.act("ooa.pdf/to_images", desc="Turn it into a PNG", path=d + "/page.pdf", target_folder=d,
              dpi=150, pages="1", file_prefix="page", out={"files": "files"})
        f.expr("png", "$files[0]", desc="The picture")

    with fcase(ua, "ooa.ocr/engine_status", "engines on this runner",
               "Engine status says which OCR engines this runner has and their languages.") as (f, d):
        f.act("ooa.ocr/engine_status", desc="Check the engines", out={"engine": "engine"})
        ua.check("notEmpty", "$engine$", what="engine")
    with fcase(ua, "ooa.ocr/read_image", "text of a picture",
               "Read image extracts the text of a picture file; the invoice number is in it.") as (f, d):
        picture(f, d)
        f.act("ooa.ocr/read_image", desc="Read the picture", path="$png$", out={"text": "text"})
        ua.check("contains", "$text$", "4711", what="OCR text")
    with fcase(ua, "ooa.ocr/read_image", "between two markers",
               "With after_text and before_text, Read image keeps only the text between them.") as (f, d):
        picture(f, d)
        f.act("ooa.ocr/read_image", desc="Read the text after INVOICE", path="$png$", after_text="INVOICE",
              before_text="Total", out={"text": "text"})
        ua.check("contains", "$text$", "4711", what="OCR text")
        ua.check("notContains", "$text$", "115", what="OCR text")
    with fcase(ua, "ooa.ocr/find_text_in_image", "word found with its position",
               "Find text in image locates a word in a picture and gives its centre.") as (f, d):
        picture(f, d)
        f.act("ooa.ocr/find_text_in_image", desc="Find 4711", path="$png$", text="4711",
              out={"found": "found", "x": "x", "count": "n"})
        ua.check_eq("found", True)
        ua.check("gt", "$x$", 0, what="x")
        f.act("ooa.ocr/find_text_in_image", desc="Look for a word that is not there", path="$png$", text="Refund",
              out={"found": "found"})
        ua.check_eq("found", False)
    with fcase(ua, "ooa.ocr/read_url", "picture from a web address",
               "Read URL downloads a picture and reads its text; the picture is put on the local stand-in first.") as (f, d):
        picture(f, d)
        f.act("ooa.rest/request", desc="Put the picture on the stand-in", method="PUT", body_file="$png$",
              url="${standin_url}/graph/v1.0/me/drive/root:/ut-office/ocr-page.png:/content",
              content_type="image/png", out={"status": "status"})
        f.act("ooa.ocr/read_url", desc="Read the picture from its address",
              url="${standin_url}/graph/v1.0/me/drive/root:/ut-office/ocr-page.png:/content", out={"text": "text"})
        ua.check("contains", "$text$", "4711", what="OCR text")
    with fcase(ua, "ooa.ocr/read_with_cloud", "no cloud session open",
               "Read with cloud OCR sends a picture to Azure AI or Google Vision through a Connect session; with no "
               "session open it stops with an error and nothing is sent.") as (f, d):
        picture(f, d)
        ua.expect_error("*", lambda ff: ff.act("ooa.ocr/read_with_cloud", desc="Read without a session",
                                               engine="azure", path="$png$", session="ut_no_such_session"))
    with fcase(ua, "ooa.ocr/add_windows_language", "a bad language tag is refused",
               "Add Windows OCR language installs a language pack (administrator rights, changes the computer), so the "
               "case only checks that a malformed tag is refused before anything is installed.") as (f, d):
        ua.expect_error("OCR.BAD_LANGUAGE", lambda ff: ff.act("ooa.ocr/add_windows_language",
                                                              desc="Try a malformed tag", language="not a tag!"))
    with screen_case(ua, "ooa.ocr/read_screen", "text in a corner of the screen",
                     "Read screen captures a region of the screen and reads its text.") as (f, d):
        f.act("ooa.ocr/read_screen", desc="Read the top-left corner", x=0, y=0, width=600, height=300,
              out={"text": "text"})
        ua.check("notEmpty", "$text$", what="screen text")
    with screen_case(ua, "ooa.ocr/text_on_screen", "a word that is not shown",
                     "Text on screen is a condition: true when the text is visible on the screen.") as (f, d):
        f.set("ok", False)
        with f.if_({"condition": "ooa.ocr/text_on_screen", "args": {"text": "UT-NOT-ON-SCREEN-9137"}}, desc="Is it shown?"):
            f.set("ok", True)
        ua.check_eq("ok", False)
    with screen_case(ua, "ooa.ocr/click_text", "missing text is reported",
                     "Click text finds a word on the screen with OCR and clicks it; a word that is not shown stops with "
                     "OCR.TEXT_NOT_FOUND, so nothing is clicked.") as (f, d):
        ua.expect_error("OCR.TEXT_NOT_FOUND", lambda ff: ff.act("ooa.ocr/click_text", desc="Click a word not on screen",
                                                                text="UT-NOT-ON-SCREEN-9137"))
    with screen_case(ua, "ooa.ocr/act_near_label", "missing label is reported",
                     "Act near label finds a label with OCR and reads, clicks or types into the field next to it; a "
                     "label that is not shown stops with an error.") as (f, d):
        ua.expect_error("*", lambda ff: ff.act("ooa.ocr/act_near_label", desc="Read next to a label not on screen",
                                               label="UT-NOT-ON-SCREEN-9137:", out={"text": "text"}))
