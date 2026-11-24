"""Cases for the commands that drive the installed Excel program (it runs hidden, nothing shows on the screen).

The command samples for this toolkit are marked "desktop" and are not converted, so every case is written here.
"""
from helpers_office import fcase

CLOSE = [("ooa.excel/close", {})]


def add(ua):
    for n, t in (("p", "File"), ("n", "Number"), ("v", "Any"), ("ok", "Boolean"), ("result", "String")):
        ua.var(n, t)

    def xcase(cmd, title, why):
        return fcase(ua, cmd, title, why, close=CLOSE)

    def book(f, d, name, value=None, formula=None):
        f.act("ooa.excel/create_workbook", desc="Create a workbook for this case", path=d + "/" + name, sheet="Data")
        if value is not None:
            f.act("ooa.excel/set_cell", desc="Write a value", cell="A1", value=value)
        if formula:
            f.act("ooa.excel/set_formula", desc="Write a formula", cell="B1", formula=formula)
        f.act("ooa.excel/close", desc="Save and close", save=True)

    def exists(f, path):
        f.set("ok", False)
        with f.if_({"condition": "ooa.file/exists", "args": {"path": path}}, desc="Was " + path + " made?"):
            f.set("ok", True)
        ua.check_eq("ok", True)

    with xcase("ooa.excel_desktop/convert", "xlsx to the old xls format",
               "Convert has Excel save a workbook in another format; the case converts to .xls, opens the result and "
               "reads the value back.") as (f, d):
        book(f, d, "c.xlsx", value="kept")
        f.act("ooa.excel_desktop/convert", desc="Convert to .xls", path=d + "/c.xlsx", target=d + "/c.xls", overwrite=True)
        exists(f, d + "/c.xls")
        f.act("ooa.excel/open", desc="Open the .xls", path=d + "/c.xls")
        f.act("ooa.excel/get_cell", desc="Read A1", cell="A1", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", "kept")
    with xcase("ooa.excel_desktop/convert", "xlsx to csv",
               "Convert to .csv writes the first sheet as comma-separated text; the case reads the text.") as (f, d):
        book(f, d, "c.xlsx", value="kept")
        f.act("ooa.excel_desktop/convert", desc="Convert to .csv", path=d + "/c.xlsx", target=d + "/c.csv")
        f.act("ooa.file/read_text", desc="Read the csv", path=d + "/c.csv", trim="both", out={"text": "v"})
        ua.check_eq("v", "kept")
    with xcase("ooa.excel_desktop/export_pdf", "whole workbook",
               "Export to PDF has Excel save the workbook as a PDF; the case checks the PDF has one page.") as (f, d):
        book(f, d, "e.xlsx", value="report")
        f.act("ooa.excel_desktop/export_pdf", desc="Export to PDF", path=d + "/e.xlsx", target=d + "/e.pdf",
              overwrite=True, out={"path": "p"})
        exists(f, d + "/e.pdf")
        f.act("ooa.pdf/page_count", desc="Count the pages of the PDF", path=d + "/e.pdf", out={"count": "n"})
        ua.check_eq("n", 1)
    with xcase("ooa.excel_desktop/refresh_all", "recalculates and saves",
               "Refresh all refreshes queries and pivot tables, recalculates and saves; with nothing to refresh, the "
               "formula's value is in the saved file afterwards.") as (f, d):
        book(f, d, "r.xlsx", value=21, formula="A1*2")
        f.act("ooa.excel_desktop/refresh_all", desc="Refresh and save", path=d + "/r.xlsx", save=True)
        f.act("ooa.excel/open", desc="Open the workbook", path=d + "/r.xlsx")
        f.act("ooa.excel/get_cell", desc="Read the formula's value", cell="B1", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", 42)
    with xcase("ooa.excel_desktop/update_links", "workbook with no links",
               "Update links has Excel refresh the values taken from linked workbooks and save. A link to another "
               "workbook cannot be written without Excel, so the case uses a workbook with none: the count of updated "
               "links is 0 and the workbook still reads back.") as (f, d):
        book(f, d, "l.xlsx", value="kept")
        f.act("ooa.excel_desktop/update_links", desc="Update the links and save", path=d + "/l.xlsx", save=True,
              out={"count": "n"})
        ua.check_eq("n", 0)
        f.act("ooa.excel/open", desc="Open the workbook", path=d + "/l.xlsx")
        f.act("ooa.excel/get_cell", desc="Read A1", cell="A1", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", "kept")
    with xcase("ooa.excel_desktop/run_macro", "a workbook without the macro",
               "Run macro runs a VBA macro in a macro-enabled workbook. A workbook without the macro stops with "
               "EXCEL_DESKTOP.FAILED (a workbook with VBA cannot be made here without changing Excel's trust settings).") as (f, d):
        book(f, d, "m.xlsx")
        ua.expect_error("EXCEL_DESKTOP.FAILED", lambda ff: ff.act(
            "ooa.excel_desktop/run_macro", desc="Run a macro that is not there", path=d + "/m.xlsx", macro="NoSuchMacro",
            out={"result": "result"}))
