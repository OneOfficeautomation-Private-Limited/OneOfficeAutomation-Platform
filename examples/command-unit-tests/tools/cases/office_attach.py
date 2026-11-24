"""Cases for the commands that work on a workbook or document already open in Excel or Word.

The samples (bad range, bad cell, empty text, text too long) run anywhere. The other cases need a file open in the
running Excel or Word, which is the person's own program on this computer, so they are screen cases (input ut_screen):
each opens its own file in a hidden Excel or Word through C# (ooa.dotnet/run_csharp), uses the command, and closes only
its own file afterwards (the program itself is closed only when it is hidden and has nothing else open).
"""
from helpers_office import fix_samples, screen_case

OPEN = """var app = Type.GetTypeFromProgID(args["app"].ToString());
dynamic o;
try { o = System.Runtime.InteropServices.Marshal.GetActiveObject(args["app"].ToString()); }
catch { o = Activator.CreateInstance(app); }
if (args["app"].ToString() == "Excel.Application") o.Workbooks.Open(args["path"].ToString());
else o.Documents.Open(args["path"].ToString());
return "opened";"""
CLOSE = """dynamic o = System.Runtime.InteropServices.Marshal.GetActiveObject(args["app"].ToString());
bool excel = args["app"].ToString() == "Excel.Application";
dynamic files = excel ? o.Workbooks : o.Documents;
foreach (dynamic f in files) { if (string.Equals((string)f.FullName, args["path"].ToString(), StringComparison.OrdinalIgnoreCase)) { f.Close(false); break; } }
if (files.Count == 0 && !(bool)o.Visible) o.Quit();
return "closed";"""


def add(ua):
    fix_samples(ua)
    for n, t in (("info", "Dictionary"), ("full", "String"), ("lst", "List"), ("tbl", "Table"), ("n", "Number"),
                 ("text", "String"), ("r", "Any")):
        ua.var(n, t)

    def prepare(f, d, kind):
        if kind == "excel":
            f.act("ooa.excel/create_workbook", desc="Create the workbook", path=d + "/Sales.xlsx", sheet="Data")
            f.act("ooa.excel/write_table", desc="Two rows", table={"columns": ["Item", "Qty"], "rows": [["pen", 2], ["cup", 3]]})
            f.act("ooa.excel/close", desc="Save and close", save=True)
            name, app = "Sales.xlsx", "Excel.Application"
        else:
            f.act("ooa.word/create_document", desc="Create the document", path=d + "/Letter.docx")
            f.act("ooa.word/add_paragraph", desc="A line with a placeholder", text="Dear {{name}},")
            f.act("ooa.word/close", desc="Save and close", save=True)
            name, app = "Letter.docx", "Word.Application"
        f.act("ooa.file/get_info", desc="Find the full path", path=d + "/" + name, out={"info": "info"})
        f.set("full", "${info.folder}\\" + name, desc="The full path")
        f.act("ooa.dotnet/run_csharp", desc="Open it in a hidden " + kind.title(), code=OPEN,
              arguments={"app": app, "path": "$full$"}, out={"result": "r"})
        return name, app

    def done(f, app):
        f.act("ooa.dotnet/run_csharp", desc="Close only this case's file", code=CLOSE,
              arguments={"app": app, "path": "$full$"}, cont=True)

    with screen_case(ua, "ooa.office_attach/excel_list_open", "the case's workbook is listed",
                     "List open workbooks lists the workbooks open in the running Excel.") as (f, d):
        name, app = prepare(f, d, "excel")
        f.act("ooa.office_attach/excel_list_open", desc="List open workbooks", out={"workbooks": "lst"})
        done(f, app)
        ua.check("contains", "${lst}", name, what="the list")
    with screen_case(ua, "ooa.office_attach/excel_read_range", "used area as a table",
                     "Read range reads a sheet of an open workbook as a Data Table.") as (f, d):
        name, app = prepare(f, d, "excel")
        f.act("ooa.office_attach/excel_read_range", desc="Read the used area", workbook=name, sheet="Data",
              out={"table": "tbl", "row_count": "n"})
        done(f, app)
        ua.check_eq("n", 2)
    with screen_case(ua, "ooa.office_attach/excel_write_range", "write then read back",
                     "Write range writes values into an open workbook; reading the cell gives them back.") as (f, d):
        name, app = prepare(f, d, "excel")
        f.act("ooa.office_attach/excel_write_range", desc="Write a value in D1", workbook=name, sheet="Data",
              start_cell="D1", values="checked", out={"range": "text"})
        f.act("ooa.office_attach/excel_read_range", desc="Read D1", workbook=name, sheet="Data", range="D1:D1",
              header=False, out={"table": "tbl"})
        done(f, app)
        ua.check("contains", "${tbl}", "checked", what="the cell")
    with screen_case(ua, "ooa.office_attach/excel_save", "saved value is in the file",
                     "Save workbook saves an open workbook; the file read without Excel holds the new value.") as (f, d):
        name, app = prepare(f, d, "excel")
        f.act("ooa.office_attach/excel_write_range", desc="Write a value", workbook=name, sheet="Data",
              start_cell="D1", values="saved")
        f.act("ooa.office_attach/excel_save", desc="Save it", workbook=name)
        done(f, app)
        f.act("ooa.excel/open", desc="Open the file", path=d + "/" + name)
        f.act("ooa.excel/get_cell", desc="Read D1", cell="D1", out={"value": "text"})
        f.act("ooa.excel/close")
        ua.check_eq("text", "saved")
    with screen_case(ua, "ooa.office_attach/word_list_open", "the case's document is listed",
                     "List open documents lists the documents open in the running Word.") as (f, d):
        name, app = prepare(f, d, "word")
        f.act("ooa.office_attach/word_list_open", desc="List open documents", out={"documents": "lst"})
        done(f, app)
        ua.check("contains", "${lst}", name, what="the list")
    with screen_case(ua, "ooa.office_attach/word_read_text", "text of the open document",
                     "Read text reads an open document, including changes not yet saved.") as (f, d):
        name, app = prepare(f, d, "word")
        f.act("ooa.office_attach/word_append_text", desc="Add a line (not saved)", document=name, text="Unsaved line")
        f.act("ooa.office_attach/word_read_text", desc="Read the text", document=name, out={"text": "text"})
        done(f, app)
        ua.check("contains", "$text$", "Unsaved line", what="text")
    with screen_case(ua, "ooa.office_attach/word_replace_text", "fill a placeholder",
                     "Replace text replaces a placeholder in an open document and counts it.") as (f, d):
        name, app = prepare(f, d, "word")
        f.act("ooa.office_attach/word_replace_text", desc="Fill {{name}}", document=name, find="{{name}}",
              replace_with="Asha", out={"count": "n"})
        f.act("ooa.office_attach/word_read_text", desc="Read the text", document=name, out={"text": "text"})
        done(f, app)
        ua.check_eq("n", 1)
        ua.check("contains", "$text$", "Dear Asha,", what="text")
    with screen_case(ua, "ooa.office_attach/word_save", "saved text is in the file",
                     "Save document saves an open document; reading the file without Word shows the new text.") as (f, d):
        name, app = prepare(f, d, "word")
        f.act("ooa.office_attach/word_append_text", desc="Add a line", document=name, text="Saved line")
        f.act("ooa.office_attach/word_save", desc="Save it", document=name)
        done(f, app)
        f.act("ooa.word/extract_text", desc="Read the file", path=d + "/" + name, out={"text": "text"})
        ua.check("contains", "$text$", "Saved line", what="text")
