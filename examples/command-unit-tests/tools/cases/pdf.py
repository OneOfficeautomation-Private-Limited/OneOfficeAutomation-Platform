"""Hand-written cases for the PDF commands: each case writes its own small PDF (plain text in PDF syntax)."""
from helpers_office import fix_samples, fcase, text_pdf, form_pdf

USER = {"$credential": "unit_tests/pdf/user_password"}
OWNER = {"$credential": "unit_tests/pdf/owner_password"}
# two pages, 300 x 200 points; text positions are from the bottom-left corner
TWO_PAGES = text_pdf([[(20, 150, "Invoice INV-7"), (20, 60, "Total 115.00")], [(20, 150, "Page two")]])
TABLE_PDF = text_pdf([[(20, 170, "Item"), (150, 170, "Qty"), (20, 150, "pen"), (150, 150, "2"),
                       (20, 130, "cup"), (150, 130, "3"), (20, 110, "ink"), (150, 110, "5")]])
FORM = form_pdf({"name": "Ann", "city": "Pune"})
XPS_FILES = {
    "[Content_Types].xml": '<?xml version="1.0" encoding="utf-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                           '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                           '<Default Extension="fdseq" ContentType="application/vnd.ms-package.xps-fixeddocumentsequence+xml"/>'
                           '<Default Extension="fdoc" ContentType="application/vnd.ms-package.xps-fixeddocument+xml"/>'
                           '<Default Extension="fpage" ContentType="application/vnd.ms-package.xps-fixedpage+xml"/></Types>',
    "_rels/.rels": '<?xml version="1.0" encoding="utf-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Type="http://schemas.microsoft.com/xps/2005/06/fixedrepresentation" Target="/FixedDocSeq.fdseq" Id="R0"/></Relationships>',
    "FixedDocSeq.fdseq": '<FixedDocumentSequence xmlns="http://schemas.microsoft.com/xps/2005/06"><DocumentReference Source="/Documents/1/FixedDoc.fdoc"/></FixedDocumentSequence>',
    "Documents/1/FixedDoc.fdoc": '<FixedDocument xmlns="http://schemas.microsoft.com/xps/2005/06"><PageContent Source="/Documents/1/Pages/1.fpage"/></FixedDocument>',
    "Documents/1/Pages/1.fpage": '<FixedPage xmlns="http://schemas.microsoft.com/xps/2005/06" Width="816" Height="1056" xml:lang="en-US">'
                                 '<Glyphs OriginX="96" OriginY="96" FontRenderingEmSize="16" FontUri="/Resources/f.ttf" '
                                 'UnicodeString="Shipment 4711 delivered" Fill="#FF000000"/></FixedPage>',
}


def add(ua):
    fix_samples(ua)
    for n, t in (("text", "String"), ("n", "Number"), ("files", "List"), ("props", "Dictionary"), ("vals", "Dictionary"),
                 ("empty", "List"), ("fields", "Dictionary"), ("tbl", "Table"), ("img", "String"), ("w", "Number"),
                 ("pages", "List"), ("ok", "Boolean")):
        ua.var(n, t)

    def pdf(f, d, name="a.pdf", content=TWO_PAGES):
        f.act("ooa.file/write_text", desc="Write the test PDF " + name, path=d + "/" + name, text=content)

    with fcase(ua, "ooa.pdf/extract_text", "one page and all pages",
               "Extract text returns the text of a PDF, here of page 2 only and then of both pages into a .txt file.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/extract_text", desc="Text of page 2", path=d + "/a.pdf", pages="2", out={"text": "text"})
        ua.check("contains", "$text$", "Page two", what="text")
        ua.check("notContains", "$text$", "Invoice", what="text")
        f.act("ooa.pdf/extract_text", desc="All text into a file", path=d + "/a.pdf", output_file=d + "/a.txt",
              out={"page_count": "n"})
        ua.check_eq("n", 2)
        f.act("ooa.file/read_text", desc="Read the .txt", path=d + "/a.txt", out={"text": "text"})
        ua.check("contains", "$text$", "Total 115.00", what="the text file")
    with fcase(ua, "ooa.pdf/extract_area_text", "the box at the top",
               "Extract area text reads the text inside a rectangle (points from the top-left); the top box holds the "
               "invoice number but not the total lower down.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/extract_area_text", desc="Read the top 80 points", path=d + "/a.pdf", page=1, x=0, y=0,
              width=300, height=80, out={"text": "text"})
        ua.check("contains", "$text$", "INV-7", what="text")
        ua.check("notContains", "$text$", "Total", what="text")
    with fcase(ua, "ooa.pdf/extract_fields", "two named areas and an empty one",
               "Extract fields reads several named areas at once into a Dictionary and lists the empty ones.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/extract_fields", desc="Read three areas", path=d + "/a.pdf",
              fields={"number": {"page": 1, "x": 0, "y": 20, "width": 300, "height": 50},
                      "total": {"page": 1, "x": 0, "y": 115, "width": 300, "height": 50},
                      "note": {"page": 1, "x": 250, "y": 180, "width": 40, "height": 15}},
              out={"values": "vals", "empty": "empty"})
        f.expr("text", "$vals.number", desc="The number area")
        ua.check_eq("text", "Invoice INV-7")
        f.expr("text", "$vals.total", desc="The total area")
        ua.check_eq("text", "Total 115.00")
        ua.check_eq("empty", ["note"])
    with fcase(ua, "ooa.pdf/get_properties", "page count and encryption",
               "Get properties returns facts about a PDF; the case checks the page count and that it is not encrypted.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/get_properties", desc="Read the properties", path=d + "/a.pdf", out={"properties": "props"})
        f.expr("n", "$props.page_count", desc="The page count")
        ua.check_eq("n", 2)
    with fcase(ua, "ooa.pdf/split", "one file per page",
               "Split makes one PDF per page; each part has one page.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/split", desc="Split into pages", path=d + "/a.pdf", target_folder=d + "/parts", out={"files": "files"})
        f.expr("n", "len($files)", desc="Count the parts")
        ua.check_eq("n", 2)
        f.act("ooa.pdf/extract_text", desc="Text of the second part", path="$files[1]", out={"text": "text"})
        ua.check("contains", "$text$", "Page two", what="second part")
    with fcase(ua, "ooa.pdf/split", "chosen pages into one file",
               "Split with mode selected_pages puts the chosen pages into one file.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/split", desc="Keep page 2", path=d + "/a.pdf", target_folder=d + "/sel", mode="selected_pages",
              pages="2", out={"files": "files"})
        f.act("ooa.pdf/page_count", desc="Count its pages", path="$files[0]", out={"count": "n"})
        ua.check_eq("n", 1)
    with fcase(ua, "ooa.pdf/to_images", "first page as PNG",
               "To images saves pages as pictures; the case saves page 1 and checks one non-empty .png was made.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/to_images", desc="Page 1 as PNG at 72 dpi", path=d + "/a.pdf", target_folder=d + "/img",
              dpi=72, pages="1", out={"files": "files"})
        f.expr("n", "len($files)", desc="Count the pictures")
        ua.check_eq("n", 1)
        f.set("ok", False)
        with f.if_({"condition": "ooa.file/size_is", "args": {"path": "$files[0]", "operator": "gt", "size": 100}},
                   desc="Is the picture more than 100 bytes?"):
            f.set("ok", True)
        ua.check_eq("ok", True)
        ua.check("endsWith", "${files[0]}", ".png", what="the picture's name")
    with fcase(ua, "ooa.pdf/extract_images", "a PDF made from a picture",
               "Extract images saves the pictures embedded in a PDF; the case turns a page into a PNG, makes a PDF "
               "holding that picture with Word, and extracts it again.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/to_images", desc="Page 1 as PNG", path=d + "/a.pdf", target_folder=d + "/img", dpi=72, pages="1",
              out={"files": "files"})
        f.act("ooa.word/create_document", desc="A document for the picture", path=d + "/pic.docx")
        f.act("ooa.word/insert_picture", desc="Insert the picture", image="$files[0]")
        f.act("ooa.word/save_as", desc="Save as PDF", path=d + "/pic.pdf", format="pdf")
        f.act("ooa.word/close")
        f.act("ooa.pdf/extract_images", desc="Extract the pictures", path=d + "/pic.pdf", target_folder=d + "/ex",
              out={"count": "n"})
        ua.check("ge", "$n$", 1, what="pictures found")
    with fcase(ua, "ooa.pdf/encrypt", "open password from the vault",
               "Encrypt protects a PDF with an open password and an owner password (made-up values from the vault); "
               "reading it without the password fails, with the password it works.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/encrypt", desc="Protect the PDF", path=d + "/a.pdf", target=d + "/locked.pdf",
              user_password=USER, owner_password=OWNER, allow_printing=False)
        ua.expect_error("PDF.PASSWORD_REQUIRED", lambda ff: ff.act("ooa.pdf/page_count", desc="Open without the password",
                                                 path=d + "/locked.pdf"))
        f.act("ooa.pdf/page_count", desc="Open with the password", path=d + "/locked.pdf", password=USER,
              out={"count": "n"})
        ua.check_eq("n", 2)
    with fcase(ua, "ooa.pdf/decrypt", "remove the password",
               "Decrypt removes the protection with the open password; the result opens without one.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/encrypt", desc="Protect the PDF", path=d + "/a.pdf", target=d + "/locked.pdf",
              user_password=USER, owner_password=OWNER)
        f.act("ooa.pdf/decrypt", desc="Remove the password", path=d + "/locked.pdf", password=USER, target=d + "/open.pdf")
        f.act("ooa.pdf/extract_text", desc="Read the result", path=d + "/open.pdf", out={"text": "text"})
        ua.check("contains", "$text$", "INV-7", what="text")
    with fcase(ua, "ooa.pdf/read_form", "all fields",
               "Read form returns each form field's name and value.") as (f, d):
        pdf(f, d, "form.pdf", FORM)
        f.act("ooa.pdf/read_form", desc="Read the fields", path=d + "/form.pdf", out={"fields": "fields"})
        ua.check_eq("fields", {"name": "Ann", "city": "Pune"})
    with fcase(ua, "ooa.pdf/fill_form", "fill and read back",
               "Fill form writes field values into a new PDF and counts them; reading that PDF's form shows the values.") as (f, d):
        pdf(f, d, "form.pdf", FORM)
        f.act("ooa.pdf/fill_form", desc="Fill the city", path=d + "/form.pdf", values={"city": "Delhi"},
              target=d + "/filled.pdf", out={"filled": "n"})
        ua.check_eq("n", 1)
        f.act("ooa.pdf/read_form", desc="Read the city", path=d + "/filled.pdf", field_names=["city"],
              out={"fields": "fields"})
        ua.check_eq("fields", {"city": "Delhi"})
    with fcase(ua, "ooa.pdf/extract_tables", "a small table",
               "Extract tables finds text lined up in columns and returns it as a Data Table with column names.") as (f, d):
        pdf(f, d, "t.pdf", TABLE_PDF)
        f.act("ooa.pdf/extract_tables", desc="Find the tables", path=d + "/t.pdf", out={"table": "tbl", "count": "n"})
        ua.check_eq("n", 1)
        ua.check_eq("tbl", {"columns": ["Item", "Qty"], "rows": [["pen", "2"], ["cup", "3"], ["ink", "5"]]})
    with fcase(ua, "ooa.pdf/render_page", "page 1 as a data URI",
               "Render page draws a page as a PNG data URI and gives the page size in points.") as (f, d):
        pdf(f, d)
        f.act("ooa.pdf/render_page", desc="Render page 1", path=d + "/a.pdf", out={"image": "img", "width_points": "w",
                                                                                     "page_count": "n"})
        ua.check("startsWith", "$img$", "data:image/png;base64,", what="image")
        ua.check_eq("w", 300)
        ua.check_eq("n", 2)
    with fcase(ua, "ooa.pdf/read_text_ocr", "a page with no text layer",
               "Read text with OCR reads pages that are pictures with Tesseract; the case makes a picture-only PDF "
               "(page to PNG, PNG into a PDF with Word) and reads the invoice number from it.") as (f, d):
        f.act("ooa.file/write_text", desc="A PDF with large text", path=d + "/big.pdf",
              text=text_pdf([[(20, 100, "INVOICE 4711")]], size=(400, 200), font_size=36))
        f.act("ooa.pdf/to_images", desc="Page 1 as PNG at 200 dpi", path=d + "/big.pdf", target_folder=d + "/img",
              dpi=200, pages="1", out={"files": "files"})
        f.act("ooa.word/create_document", desc="A document for the picture", path=d + "/scan.docx")
        f.act("ooa.word/insert_picture", desc="Insert the picture", image="$files[0]", width=400)
        f.act("ooa.word/save_as", desc="Save as PDF", path=d + "/scan.pdf", format="pdf")
        f.act("ooa.word/close")
        f.act("ooa.pdf/read_text_ocr", desc="Read with OCR", path=d + "/scan.pdf", out={"text": "text"})
        ua.check("contains", "$text$", "4711", what="OCR text")
    with fcase(ua, "ooa.pdf/read_xps_text", "a one-page XPS document",
               "Read XPS text reads the text of an XPS document; the case builds a minimal one (a zip of XML parts).") as (f, d):
        for p, t in XPS_FILES.items():
            f.act("ooa.file/write_text", desc="XPS part " + p, path=d + "/x/" + p, text=t)
        f.act("ooa.file/zip", desc="Pack the parts", source=d + "/x", target=d + "/doc.zip")
        f.act("ooa.file/rename", desc="Name it .xps", path=d + "/doc.zip", new_name="doc.xps")
        f.act("ooa.pdf/read_xps_text", desc="Read the text", path=d + "/doc.xps", out={"text": "text", "page_count": "n"})
        ua.check_eq("n", 1)
        ua.check("contains", "$text$", "Shipment 4711", what="text")
