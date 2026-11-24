"""Hand-written cases for the Excel cell, row and column commands the samples do not cover."""
from helpers_office import fix_samples, fcase

CLOSE = [("ooa.excel/close", {})]
DATA = {"columns": ["Item", "Qty", "Note"], "rows": [["pen", 2, "a"], ["cup", 3, "b"], ["ink", 5, "c"]]}


def book(f, d):
    f.act("ooa.excel/create_workbook", desc="Create a new workbook for this case", path=d + "/b.xlsx", sheet="Data")
    f.act("ooa.excel/write_table", desc="Write three rows of test data", table=DATA)


def add(ua):
    fix_samples(ua, close=CLOSE)
    for n, t in (("v", "Any"), ("n", "Number"), ("last", "Number"), ("tbl", "Table"), ("formula", "String"),
                 ("has", "Boolean"), ("color", "String")):
        ua.var(n, t)

    def xcase(cmd, title, why):
        return fcase(ua, cmd, title, why, close=CLOSE)

    with xcase("ooa.excel/set_cell", "text and number",
               "Set cell writes a value into one cell; the case reads both values back.") as (f, d):
        f.act("ooa.excel/create_workbook", desc="Create a new workbook", path=d + "/b.xlsx")
        f.act("ooa.excel/set_cell", desc="Write a text", cell="A1", value="Total")
        f.act("ooa.excel/set_cell", desc="Write a number", cell="B1", value=42.5)
        f.act("ooa.excel/get_cell", desc="Read the text", cell="A1", out={"value": "v"})
        ua.check_eq("v", "Total")
        f.act("ooa.excel/get_cell", desc="Read the number", cell="B1", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", 42.5)
    with xcase("ooa.excel/get_cell", "calculated value and shown text",
               "Get cell returns a formula's calculated value, or with read_as = text the text Excel shows.") as (f, d):
        book(f, d)
        f.act("ooa.excel/set_formula", desc="A sum formula", cell="B5", formula="SUM(B2:B4)")
        f.act("ooa.excel/get_cell", desc="Read the result", cell="B5", out={"value": "v"})
        ua.check_eq("v", 10)
        f.act("ooa.excel/get_cell", desc="Read the result as text", cell="B5", read_as="text", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", "10")
    with xcase("ooa.excel/read_formula", "formula and plain value",
               "Read formula returns the formula a cell holds, and has_formula false for a plain value.") as (f, d):
        book(f, d)
        f.act("ooa.excel/set_formula", desc="A formula", cell="D2", formula="B2*2")
        f.act("ooa.excel/read_formula", desc="Read the formula", cell="D2", out={"formula": "formula", "has_formula": "has"})
        ua.check_eq("formula", "=B2*2")
        ua.check_eq("has", True)
        f.act("ooa.excel/read_formula", desc="Read a plain cell", cell="A2", out={"has_formula": "has"})
        f.act("ooa.excel/close")
        ua.check_eq("has", False)
    with xcase("ooa.excel/get_all_cells", "used area with column names",
               "Get all cells reads the used area as a table; with header on, the first row gives the column names.") as (f, d):
        book(f, d)
        f.act("ooa.excel/get_all_cells", desc="Read the sheet", header=True, out={"table": "tbl", "row_count": "n"})
        ua.check_eq("n", 3)
        f.act("ooa.excel/get_all_cells", desc="Read rows 2 to 3 only", header=True, from_row=2, to_row=3,
              out={"row_count": "n"})
        f.act("ooa.excel/close")
        ua.check_eq("n", 2)
    with xcase("ooa.excel/row_count", "rows with data and last row",
               "Row count counts rows with data and reports the last used row number.") as (f, d):
        book(f, d)
        f.act("ooa.excel/row_count", desc="Count the rows", out={"count": "n", "last_row": "last"})
        f.act("ooa.excel/close")
        ua.check_eq("n", 4)
        ua.check_eq("last", 4)
    with xcase("ooa.excel/get_cell_color", "fill colour as hex",
               "Get cell colour returns a cell's fill colour; the case colours a cell first and reads it back.") as (f, d):
        book(f, d)
        f.act("ooa.excel/set_cell_color", desc="Fill A2 orange", range="A2", color="#FFC000")
        f.act("ooa.excel/get_cell_color", desc="Read the fill colour", cell="A2", out={"color": "color"})
        f.act("ooa.excel/close")
        ua.check("contains", "$color$", "FFC000", what="color")
    with xcase("ooa.excel/delete_columns", "middle column",
               "Delete columns removes whole columns; the column to the right moves left.") as (f, d):
        book(f, d)
        f.act("ooa.excel/delete_columns", desc="Delete column B", column="B")
        f.act("ooa.excel/get_cell", desc="Read B1", cell="B1", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", "Note")
    with xcase("ooa.excel/unhide_rows", "hidden rows shown again",
               "Unhide rows shows hidden rows; reading without hidden rows gives all three data rows again.") as (f, d):
        book(f, d)
        f.act("ooa.excel/hide_rows", desc="Hide rows 2 and 3", row=2, count=2)
        f.act("ooa.excel/get_all_cells", desc="Read visible rows", header=True, include_hidden=False, out={"row_count": "n"})
        ua.check_eq("n", 1)
        f.act("ooa.excel/unhide_rows", desc="Show rows 2 and 3", row=2, count=2)
        f.act("ooa.excel/get_all_cells", desc="Read visible rows", header=True, include_hidden=False, out={"row_count": "n"})
        f.act("ooa.excel/close")
        ua.check_eq("n", 3)
    with xcase("ooa.excel/unhide_columns", "hidden column shown again",
               "Unhide columns shows hidden columns again; the file is saved and the value of the column is still read.") as (f, d):
        book(f, d)
        f.act("ooa.excel/hide_columns", desc="Hide column B", column="B")
        f.act("ooa.excel/unhide_columns", desc="Show column B", column="B")
        f.act("ooa.excel/close", desc="Save and close", save=True)
        f.act("ooa.excel/open", desc="Open again", path=d + "/b.xlsx")
        f.act("ooa.excel/get_cell", desc="Read B2", cell="B2", out={"value": "v"})
        f.act("ooa.excel/close")
        ua.check_eq("v", 2)
    with xcase("ooa.excel/clear_filter", "rows hidden by a filter come back",
               "Clear filter shows every row a filter hid and removes the filter; it says how many rows it showed.") as (f, d):
        book(f, d)
        f.act("ooa.excel/filter", desc="Keep pen only and hide the other rows", column="Item", operator="equals",
              value="pen", header=True, hide_rows=True, out={"count": "n"})
        ua.check_eq("n", 1)
        f.act("ooa.excel/clear_filter", desc="Clear the filter", out={"rows_shown": "n"})
        f.act("ooa.excel/close")
        ua.check_eq("n", 2)
