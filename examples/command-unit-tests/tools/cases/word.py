"""Hand-written cases for the Word document commands the samples do not cover."""
from helpers_office import fix_samples, fcase

CLOSE = [("ooa.word/close", {})]
TABLE = {"columns": ["Item", "Qty"], "rows": [["pen", "2"], ["cup", "3"]]}


def add(ua):
    fix_samples(ua, close=CLOSE)
    for n, t in (("text", "String"), ("paras", "List"), ("n", "Number"), ("tbl", "Table"), ("v", "Any"),
                 ("ok", "Boolean")):
        ua.var(n, t)

    def wcase(cmd, title, why):
        return fcase(ua, cmd, title, why, close=CLOSE)

    def doc(f, d, name="d.docx"):
        f.act("ooa.word/create_document", desc="Create a new document for this case", path=d + "/" + name)

    with wcase("ooa.word/add_paragraph", "heading and body",
               "Add paragraph appends a paragraph, here a heading and a bold line; the text is read back line by line.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_paragraph", desc="Add a heading", text="Monthly report", style="Heading1")
        f.act("ooa.word/add_paragraph", desc="Add a bold line", text="All figures in EUR", bold=True)
        f.act("ooa.word/read_text", desc="Read the text", out={"text": "text", "paragraphs": "paras"})
        f.act("ooa.word/close")
        ua.check_eq("text", "Monthly report\nAll figures in EUR")
    with wcase("ooa.word/read_text", "a range of paragraphs",
               "Read text returns paragraph text; with from_paragraph and to_paragraph only those lines.") as (f, d):
        doc(f, d)
        for t in ("one", "two", "three"):
            f.act("ooa.word/add_paragraph", desc="Add a line", text=t)
        f.act("ooa.word/read_text", desc="Read paragraphs 2 to 3", from_paragraph=2, to_paragraph=3, out={"text": "text"})
        f.act("ooa.word/close")
        ua.check_eq("text", "two\nthree")
    with wcase("ooa.word/insert_text", "after a marker text",
               "Insert text puts text after a marker paragraph; the new text comes between the two lines.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_paragraph", desc="First line", text="Dear customer,")
        f.act("ooa.word/add_paragraph", desc="Last line", text="Regards")
        f.act("ooa.word/insert_text", desc="Insert after the greeting", text="Your order has shipped.",
              position="after_text", anchor="Dear customer,")
        f.act("ooa.word/read_text", desc="Read the text", out={"text": "text"})
        f.act("ooa.word/close")
        ua.check_eq("text", "Dear customer,\nYour order has shipped.\nRegards")
    with wcase("ooa.word/format_text", "bold every occurrence",
               "Format text applies formatting to each occurrence of a text and says how many it changed.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_paragraph", desc="A line", text="Total due: 100. Total paid: 40.")
        f.act("ooa.word/format_text", desc="Make Total bold and red", text="Total", bold=True, color="#C00000",
              out={"count": "n"})
        f.act("ooa.word/close")
        ua.check_eq("n", 2)
    with wcase("ooa.word/add_table", "table read back",
               "Add table inserts a table from a Data Table; Read tables returns it with its column names.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_table", desc="Add the table", table=TABLE)
        f.act("ooa.word/read_tables", desc="Read the first table", index=1, header=True, out={"table": "tbl", "count": "n"})
        f.act("ooa.word/close")
        ua.check_eq("n", 1)
        ua.check_eq("tbl", TABLE)
    with wcase("ooa.word/read_tables", "only some rows",
               "Read tables with from_row and to_row reads only part of a table.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_table", desc="Add the table", table=TABLE)
        f.act("ooa.word/read_tables", desc="Read rows 1 to 2", index=1, header=True, from_row=1, to_row=2,
              out={"table": "tbl"})
        f.act("ooa.word/close")
        ua.check_eq("tbl", {"columns": ["Item", "Qty"], "rows": [["pen", "2"]]})
    with wcase("ooa.word/save", "save to a new path",
               "Save writes the document, here to a second path; the copy holds the text.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_paragraph", desc="A line", text="saved copy")
        f.act("ooa.word/save", desc="Save to copy.docx", path=d + "/copy.docx")
        f.act("ooa.word/close")
        f.act("ooa.word/extract_text", desc="Read the copy", path=d + "/copy.docx", out={"text": "text"})
        ua.check("contains", "$text$", "saved copy", what="text")
    with wcase("ooa.word/save_as", "export as PDF",
               "Save as with format pdf exports the document; the PDF's text holds the paragraph.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_paragraph", desc="A line", text="Exported to PDF")
        f.act("ooa.word/save_as", desc="Save as PDF", path=d + "/d.pdf", format="pdf")
        f.act("ooa.word/close")
        f.act("ooa.pdf/extract_text", desc="Read the PDF", path=d + "/d.pdf", out={"text": "text"})
        ua.check("contains", "$text$", "Exported to PDF", what="text")
    with wcase("ooa.word/close", "close with save",
               "Close ends the session; with save on, the change is in the file.") as (f, d):
        doc(f, d)
        f.act("ooa.word/add_paragraph", desc="A line", text="kept on close")
        f.act("ooa.word/close", desc="Close and save", save=True)
        f.act("ooa.word/extract_text", desc="Read the file", path=d + "/d.docx", out={"text": "text"})
        ua.check("contains", "$text$", "kept on close", what="text")
