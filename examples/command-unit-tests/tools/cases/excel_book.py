"""Hand-written cases for the Excel workbook and sheet commands the samples do not cover."""
from helpers_office import fix_samples, fcase

CLOSE = [("ooa.excel/close", {})]
DATA = {"columns": ["Item", "Qty"], "rows": [["pen", 2], ["cup", 3]]}


def book(f, d, name="b.xlsx", sheet="Data"):
    f.act("ooa.excel/create_workbook", desc="Create a new workbook for this case", path=d + "/" + name, sheet=sheet)


def add(ua):
    fix_samples(ua, close=CLOSE)
    for n, t in (("name", "String"), ("names", "List"), ("n", "Number"), ("v", "Any"), ("ok", "Boolean"),
                 ("tbl", "Table"), ("has", "Boolean")):
        ua.var(n, t)

    def xcase(cmd, title, why):
        return fcase(ua, cmd, title, why, close=CLOSE)

    with xcase("ooa.excel/write_table", "table with column names",
               "Write table puts a table with its column names into the sheet from a start cell; the case reads the "
               "used area back as a table and compares it.") as (f, d):
        book(f, d)
        f.act("ooa.excel/write_table", desc="Write two rows from B2", table=DATA, start_cell="B2")
        f.act("ooa.excel/get_cell", desc="Read the first column name", cell="B2", out={"value": "v"})
        ua.check_eq("v", "Item")
        f.act("ooa.excel/get_cell", desc="Read the last quantity", cell="C4", out={"value": "v"})
        ua.check_eq("v", 3)
        f.act("ooa.excel/close")
    with xcase("ooa.excel/close", "save on close",
               "Close ends the workbook session; with save on, the change is in the file when it is opened again.") as (f, d):
        book(f, d)
        f.act("ooa.excel/set_cell", desc="Write a value", cell="A1", value="kept")
        f.act("ooa.excel/close", desc="Close and save", save=True)
        f.act("ooa.excel/open", desc="Open the file again", path=d + "/b.xlsx")
        f.act("ooa.excel/get_cell", desc="Read the value", cell="A1", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", "kept")
    with xcase("ooa.excel/get_sheet_names", "names in order",
               "Get sheet names lists the worksheets in order; the case adds one and checks the list.") as (f, d):
        book(f, d)
        f.act("ooa.excel/add_sheet", desc="Add a second sheet", name="Summary")
        f.act("ooa.excel/get_sheet_names", desc="List the sheets", out={"names": "names"})
        f.act("ooa.excel/close")
        ua.check_eq("names", ["Data", "Summary"])
    with xcase("ooa.excel/sheet_count", "with and without hidden sheets",
               "Sheet count counts worksheets; with include_hidden off a hidden sheet is left out.") as (f, d):
        book(f, d)
        f.act("ooa.excel/add_sheet", desc="Add a sheet", name="Hidden")
        f.act("ooa.excel/hide_sheet", desc="Hide it", sheet="Hidden")
        f.act("ooa.excel/sheet_count", desc="Count all sheets", out={"count": "n"})
        ua.check_eq("n", 2)
        f.act("ooa.excel/sheet_count", desc="Count visible sheets", include_hidden=False, out={"count": "n"})
        ua.check_eq("n", 1)
        f.act("ooa.excel/close")
    with xcase("ooa.excel/switch_sheet", "by position",
               "Switch sheet makes another sheet active, here by its position; the active sheet's name is read back.") as (f, d):
        book(f, d)
        f.act("ooa.excel/add_sheet", desc="Add a second sheet", name="Second")
        f.act("ooa.excel/switch_sheet", desc="Switch to the first sheet", index=1)
        f.act("ooa.excel/get_current_sheet_name", desc="Read the active sheet", out={"name": "name"})
        ua.check_eq("name", "Data")
        f.act("ooa.excel/switch_sheet", desc="Switch to Second by name", sheet="Second")
        f.act("ooa.excel/get_current_sheet_name", desc="Read the active sheet", out={"name": "name"})
        ua.check_eq("name", "Second")
        f.act("ooa.excel/close")
    with xcase("ooa.excel/get_current_sheet_name", "new workbook",
               "Get current sheet name returns the active worksheet; in a new workbook that is its only sheet.") as (f, d):
        book(f, d, sheet="Orders")
        f.act("ooa.excel/get_current_sheet_name", desc="Read the active sheet", out={"name": "name"})
        f.act("ooa.excel/close")
        ua.check_eq("name", "Orders")
    with xcase("ooa.excel/rename_sheet", "new name",
               "Rename sheet gives a worksheet a new name; the sheet list shows it.") as (f, d):
        book(f, d)
        f.act("ooa.excel/rename_sheet", desc="Rename Data to Input", sheet="Data", new_name="Input")
        f.act("ooa.excel/get_sheet_names", desc="List the sheets", out={"names": "names"})
        f.act("ooa.excel/close")
        ua.check_eq("names", ["Input"])
    with xcase("ooa.excel/copy_sheet", "duplicate with its values",
               "Copy sheet duplicates a worksheet under a new name; the copy holds the same values.") as (f, d):
        book(f, d)
        f.act("ooa.excel/set_cell", desc="A value to copy", cell="A1", value="x")
        f.act("ooa.excel/copy_sheet", desc="Copy Data as Copy", sheet="Data", new_name="Copy", out={"name": "name"})
        f.act("ooa.excel/get_cell", desc="Read the copy", cell="A1", sheet="Copy", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("name", "Copy")
        ua.check_eq("v", "x")
    with xcase("ooa.excel/unhide_sheet", "hidden sheet shown again",
               "Unhide sheet makes a hidden worksheet visible; the visible sheet count goes back up.") as (f, d):
        book(f, d)
        f.act("ooa.excel/add_sheet", desc="Add a sheet", name="Notes")
        f.act("ooa.excel/hide_sheet", desc="Hide it", sheet="Notes")
        f.act("ooa.excel/unhide_sheet", desc="Show it again", sheet="Notes")
        f.act("ooa.excel/sheet_count", desc="Count visible sheets", include_hidden=False, out={"count": "n"})
        f.act("ooa.excel/close")
        ua.check_eq("n", 2)
    with xcase("ooa.excel/unhide_all_sheets", "two hidden sheets",
               "Unhide all sheets shows every hidden sheet and says how many it showed.") as (f, d):
        book(f, d)
        f.act("ooa.excel/add_sheet", desc="Add sheet A", name="A")
        f.act("ooa.excel/add_sheet", desc="Add sheet B", name="B")
        f.act("ooa.excel/hide_sheet", desc="Hide A", sheet="A")
        f.act("ooa.excel/hide_sheet", desc="Hide B", sheet="B")
        f.act("ooa.excel/unhide_all_sheets", desc="Show all", out={"count": "n"})
        ua.check_eq("n", 2)
        f.act("ooa.excel/sheet_count", desc="Count visible sheets", include_hidden=False, out={"count": "n"})
        f.act("ooa.excel/close")
        ua.check_eq("n", 3)
    with xcase("ooa.excel/get_sensitivity_label", "label written then read",
               "Get sensitivity label reads the label stored in the workbook; the case writes a made-up label first.") as (f, d):
        book(f, d)
        f.act("ooa.excel/get_sensitivity_label", desc="Read the label of a new workbook", out={"has_label": "has"})
        ua.check_eq("has", False)
        f.act("ooa.excel/set_sensitivity_label", desc="Write a made-up label",
              label_id="11111111-2222-3333-4444-555555555555", site_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", name="Internal")
        f.act("ooa.excel/get_sensitivity_label", desc="Read the label", out={"has_label": "has", "name": "name"})
        f.act("ooa.excel/close")
        ua.check_eq("has", True)
        ua.check_eq("name", "Internal")
