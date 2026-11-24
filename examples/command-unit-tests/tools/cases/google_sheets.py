"""Google Workspace Sheets, with the Open spreadsheet session (Go to cell, Close), against the Google stand-in. The
spreadsheet ss-budget (UT Budget) is seeded: Sheet1 holds Item, Qty, Price in A1:C4 (A1 red with white text), Sheet2 is
empty. Cases that change a spreadsheet first make their own with Create spreadsheet."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, gg  # noqa: E402

M = "ooa.google/sheets_"
BUDGET = "ss-budget"
TABLE = {"columns": ["Item", "Qty", "Price"], "rows": [["Pens", 10, 2.5], ["Paper", 4, 6], ["Ink", 2, 15]]}


def book(f, title):
    """A fresh spreadsheet with the budget table on Sheet1 (id in sid)."""
    f.act(M + "create_spreadsheet", desc="Make the spreadsheet " + title, out={"spreadsheet_id": "sid"}, **gg(title=title, sheet_names=["Sheet1", "Sheet2"]))
    f.act(M + "write", desc="Write the budget table", **gg(spreadsheet_id="$sid$", range="Sheet1!A1", table=TABLE))


def add(ua):
    start(ua, "/msgoogle/reset", "Every case below talks to the local Google stand-in; the spreadsheet UT Budget starts as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("sid", "String"), ("x", "Any"), ("l", "List"), ("b", "Boolean")):
        ua.var(v, t)
    B = dict(spreadsheet_id=BUDGET)

    # ---- session
    with ua.case(M + "open", "the budget spreadsheet", "Open spreadsheet lists the sheets and keeps the spreadsheet, sheet and cell for the steps after it.") as f:
        f.act(M + "open", desc="Open UT Budget", out={"sheets": "l"}, **gg(**B))
        ua.check_eq("l", ["Sheet1", "Sheet2"])
        f.act(M + "get_cell", desc="Read the current cell, giving no spreadsheet or cell", out={"value": "x"})
        ua.check_eq("x", "Item")
        f.act(M + "close", desc="Close it")
    with ua.case(M + "go_to_cell", "C3 on Sheet1", "Go to cell makes a cell current; Get cell then reads it without being told which.") as f:
        f.act(M + "open", desc="Open UT Budget", **gg(**B))
        f.act(M + "go_to_cell", desc="Go to Sheet1!C3", cell="Sheet1!C3")
        f.act(M + "get_cell", desc="Read the current cell", out={"value": "x"})
        ua.check_eq("x", 6)
        f.act(M + "close", desc="Close it")
    with ua.case(M + "close", "then a step needs its own cell", "Close spreadsheet ends the session; Get cell without a cell then fails.") as f:
        f.act(M + "open", desc="Open UT Budget", **gg(**B))
        f.act(M + "close", desc="Close it")
        ua.expect_error("*", lambda ff: ff.act(M + "get_cell", desc="Read with no cell given"))

    # ---- reading
    with ua.case(M + "read", "a range with a header row", "Read sheet range reads a range into a table, the first row giving the column names.") as f:
        f.act(M + "read", desc="Read Sheet1!A1:C4", out={"table": "t"}, **gg(range="Sheet1!A1:C4", **B))
        check(ua, "$t.columns", ["Item", "Qty", "Price"], "column names")
        check(ua, "$t.rows[2][0]", "Ink", "third item")
    with ua.case(M + "get_range", "all cells of a sheet", "Get range values reads every used cell of a sheet as rows and as a table.") as f:
        f.act(M + "get_range", desc="Read Sheet1", out={"values": "l", "table": "t"}, **gg(sheet="Sheet1", **B))
        check(ua, "len($l$)", 4, "rows")
        check(ua, "$t.rows[0][2]", 2.5, "price of pens")
    with ua.case(M + "get_cell", "formatted text", "Get cell value reads one cell, here as the displayed text.") as f:
        f.act(M + "get_cell", desc="Read Sheet1!B3", out={"value": "x"}, **gg(cell="Sheet1!B3", formatted=True, **B))
        ua.check_eq("x", "4")
    with ua.case(M + "read_row", "row 2", "Read row reads all values of one row.") as f:
        f.act(M + "read_row", desc="Read row 2", out={"values": "l"}, **gg(sheet="Sheet1", row=2, **B))
        ua.check_eq("l", ["Pens", 10, 2.5])
    with ua.case(M + "read_column", "column A", "Read column reads all values of one column.") as f:
        f.act(M + "read_column", desc="Read column A", out={"values": "l"}, **gg(sheet="Sheet1", column="A", **B))
        ua.check_eq("l", ["Item", "Pens", "Paper", "Ink"])
    with ua.case(M + "row_count", "the budget sheet", "Get used row count gives the last used row and column.") as f:
        f.act(M + "row_count", desc="Count Sheet1", out={"rows": "n"}, **gg(sheet="Sheet1", **B))
        ua.check_eq("n", 4)
    with ua.case(M + "list_sheets", "names in tab order", "List sheets lists the spreadsheet's tabs.") as f:
        f.act(M + "list_sheets", desc="List the sheets", out={"names": "l", "count": "n"}, **gg(**B))
        ua.check_eq("l", ["Sheet1", "Sheet2"])
    with ua.case(M + "find_cells", "a text in the sheet", "Find cells gives the addresses of the cells containing the text.") as f:
        f.act(M + "find_cells", desc="Find 'pa'", out={"cells": "l"}, **gg(range="Sheet1", text="pa", **B))
        ua.check_eq("l", ["A3"])
    with ua.case(M + "get_cell_color", "the red header", "Get cell colour gives the fill and text colour, as #RRGGBB or the nearest name.") as f:
        f.act(M + "get_cell_color", desc="Colours of A1", out={"background_color": "s", "font_color": "x"}, **gg(cell="Sheet1!A1", **B))
        ua.check_eq("s", "#FF0000")
        f.act(M + "get_cell_color", desc="As names", out={"background_color": "s"}, **gg(cell="Sheet1!A1", format="name", **B))
        ua.check_eq("s", "red")

    # ---- writing (own spreadsheet)
    with ua.case(M + "create_spreadsheet", "with two sheets", "Create spreadsheet makes a spreadsheet with named sheets and gives its id and link.") as f:
        f.act(M + "create_spreadsheet", desc="Make UT New", out={"spreadsheet_id": "sid", "url": "s"}, **gg(title="UT New", sheet_names=["Data", "Notes"]))
        ua.check("contains", "$s$", "docs.google.com/spreadsheets", what="link")
        f.act(M + "list_sheets", desc="List its sheets", out={"names": "l"}, **gg(spreadsheet_id="$sid$"))
        ua.check_eq("l", ["Data", "Notes"])
    with ua.case(M + "write", "a table with its header", "Write to sheet writes a table from a cell and counts the cells written.") as f:
        f.act(M + "create_spreadsheet", desc="Make a spreadsheet", out={"spreadsheet_id": "sid"}, **gg(title="UT Write"))
        f.act(M + "write", desc="Write the table at Sheet1!B2", out={"updated_cells": "n"}, **gg(spreadsheet_id="$sid$", range="Sheet1!B2", table=TABLE))
        ua.check_eq("n", 12)
    with ua.case(M + "append", "rows after the last", "Append rows adds the table's rows below the last used row.") as f:
        book(f, "UT Append")
        f.act(M + "append", desc="Append two rows", out={"updated_range": "s"}, **gg(spreadsheet_id="$sid$", range="Sheet1!A1", table={"columns": ["Item", "Qty", "Price"], "rows": [["Tape", 1, 3], ["Glue", 2, 4]]}))
        ua.check_eq("s", "Sheet1!A5:C6")
    with ua.case(M + "write_data_table", "clearing the sheet first", "Write data table writes a table to a sheet after clearing it.") as f:
        book(f, "UT Write table")
        f.act(M + "write_data_table", desc="Write a small table", out={"updated_cells": "n"},
              **gg(spreadsheet_id="$sid$", sheet="Sheet1", table={"columns": ["A"], "rows": [[1]]}, clear_sheet_first=True))
        ua.check_eq("n", 2)
        f.act(M + "row_count", desc="Count the rows", out={"rows": "n"}, **gg(spreadsheet_id="$sid$", sheet="Sheet1"))
        ua.check_eq("n", 2)
    with ua.case(M + "set_cell", "one value", "Set cell value writes one cell as if typed.") as f:
        book(f, "UT Set cell")
        f.act(M + "set_cell", desc="Write D2", **gg(spreadsheet_id="$sid$", cell="Sheet1!D2", value="checked"))
        f.act(M + "get_cell", desc="Read D2", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!D2"))
        ua.check_eq("x", "checked")
    with ua.case(M + "set_formula", "a sum", "Set formula puts a formula in a cell; its value is the sum.") as f:
        book(f, "UT Formula")
        f.act(M + "set_formula", desc="Sum the quantities in B5", **gg(spreadsheet_id="$sid$", cell="Sheet1!B5", formula="SUM(B2:B4)"))
        f.act(M + "get_cell", desc="Read B5", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!B5"))
        ua.check_eq("x", 16)
    with ua.case(M + "read_formula", "the formula text", "Read formula gives a cell's formula.") as f:
        book(f, "UT Read formula")
        f.act(M + "set_formula", desc="Put a formula in D2", **gg(spreadsheet_id="$sid$", cell="Sheet1!D2", formula="=B2*C2"))
        f.act(M + "read_formula", desc="Read D2", out={"formula": "s"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!D2"))
        ua.check_eq("s", "=B2*C2")
    with ua.case(M + "clear_range", "values only", "Clear range removes the values of a range.") as f:
        book(f, "UT Clear")
        f.act(M + "clear_range", desc="Clear Sheet1!B2:C4", **gg(spreadsheet_id="$sid$", range="Sheet1!B2:C4"))
        f.act(M + "read_row", desc="Read row 2", out={"values": "l"}, **gg(spreadsheet_id="$sid$", sheet="Sheet1", row=2))
        ua.check_eq("l", ["Pens"])
    with ua.case(M + "delete_range", "cells moved up", "Delete range deletes cells and moves the ones below up.") as f:
        book(f, "UT Delete range")
        f.act(M + "delete_range", desc="Delete A2:C2", **gg(spreadsheet_id="$sid$", range="Sheet1!A2:C2", shift="up"))
        f.act(M + "get_cell", desc="Read A2", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!A2"))
        ua.check_eq("x", "Paper")
    with ua.case(M + "insert_range", "cells pushed right", "Insert range adds empty cells and pushes the existing ones right.") as f:
        book(f, "UT Insert range")
        f.act(M + "insert_range", desc="Insert at A2", **gg(spreadsheet_id="$sid$", range="Sheet1!A2", shift="right"))
        f.act(M + "get_cell", desc="Read B2", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!B2"))
        ua.check_eq("x", "Pens")
    with ua.case(M + "insert_rows", "one row before row 2", "Insert rows adds empty rows; the rows below move down.") as f:
        book(f, "UT Insert rows")
        f.act(M + "insert_rows", desc="Insert before row 2", **gg(spreadsheet_id="$sid$", sheet="Sheet1", row=2, count=1))
        f.act(M + "get_cell", desc="Read A3", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!A3"))
        ua.check_eq("x", "Pens")
    with ua.case(M + "delete_rows", "two rows", "Delete rows removes rows; the rows below move up.") as f:
        book(f, "UT Delete rows")
        f.act(M + "delete_rows", desc="Delete rows 2 and 3", **gg(spreadsheet_id="$sid$", sheet="Sheet1", row=2, count=2))
        f.act(M + "get_cell", desc="Read A2", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!A2"))
        ua.check_eq("x", "Ink")
    with ua.case(M + "insert_columns", "one column before B", "Insert columns adds empty columns; the columns to the right move.") as f:
        book(f, "UT Insert columns")
        f.act(M + "insert_columns", desc="Insert before B", **gg(spreadsheet_id="$sid$", sheet="Sheet1", column="B"))
        f.act(M + "get_cell", desc="Read C1", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!C1"))
        ua.check_eq("x", "Qty")
    with ua.case(M + "delete_columns", "column B", "Delete columns removes a column; the columns to the right move left.") as f:
        book(f, "UT Delete columns")
        f.act(M + "delete_columns", desc="Delete B", **gg(spreadsheet_id="$sid$", sheet="Sheet1", column="B"))
        f.act(M + "get_cell", desc="Read B1", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!B1"))
        ua.check_eq("x", "Price")
    with ua.case(M + "copy_range", "values to another sheet", "Copy range copies a range to another sheet.") as f:
        book(f, "UT Copy range")
        f.act(M + "copy_range", desc="Copy A1:C2 to Sheet2!B2", **gg(spreadsheet_id="$sid$", source="Sheet1!A1:C2", destination="Sheet2!B2", paste="values"))
        f.act(M + "get_cell", desc="Read Sheet2!C3", out={"value": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet2!C3"))
        ua.check_eq("x", 10)
    with ua.case(M + "format_cells", "blue fill, white bold text", "Format cells sets fill and text colours; Get cell colour then reads them back.") as f:
        book(f, "UT Format")
        f.act(M + "format_cells", desc="Format the header", **gg(spreadsheet_id="$sid$", range="Sheet1!A1:C1", bold=True, background_color="#0000FF", font_color="#FFFFFF",
                                                              horizontal_alignment="center", merge="unmerge", border_style="solid"))
        f.act(M + "get_cell_color", desc="Colours of B1", out={"background_color": "s", "font_color": "x"}, **gg(spreadsheet_id="$sid$", cell="Sheet1!B1"))
        ua.check_eq("s", "#0000FF")
        ua.check_eq("x", "#FFFFFF")
    with ua.case(M + "autofit_columns", "A to C", "Autofit columns resizes the columns; the stand-in answers without an error.") as f:
        book(f, "UT Autofit columns")
        f.act(M + "autofit_columns", desc="Autofit A:C", **gg(spreadsheet_id="$sid$", sheet="Sheet1", from_column="A", to_column="C"))
        f.set("b", True, desc="The resize went through")
        ua.check_eq("b", True)
    with ua.case(M + "autofit_rows", "1 to 4", "Autofit rows resizes the rows; the stand-in answers without an error.") as f:
        book(f, "UT Autofit rows")
        f.act(M + "autofit_rows", desc="Autofit rows 1 to 4", **gg(spreadsheet_id="$sid$", sheet="Sheet1", from_row=1, to_row=4))
        f.set("b", True, desc="The resize went through")
        ua.check_eq("b", True)
    with ua.case(M + "add_sheet", "at the front", "Add sheet adds a tab at a position and gives its number id.") as f:
        book(f, "UT Add sheet")
        f.act(M + "add_sheet", desc="Add Summary first", out={"sheet_id": "n"}, **gg(spreadsheet_id="$sid$", sheet="Summary", position=1))
        f.act(M + "list_sheets", desc="List the sheets", out={"names": "l"}, **gg(spreadsheet_id="$sid$"))
        ua.check_eq("l", ["Summary", "Sheet1", "Sheet2"])
    with ua.case(M + "rename_sheet", "by position", "Rename sheet renames the second tab, chosen by its position.") as f:
        book(f, "UT Rename sheet")
        f.act(M + "rename_sheet", desc="Rename tab 2", **gg(spreadsheet_id="$sid$", position=2, new_name="Archive"))
        f.act(M + "list_sheets", desc="List the sheets", out={"names": "l"}, **gg(spreadsheet_id="$sid$"))
        ua.check_eq("l", ["Sheet1", "Archive"])
    with ua.case(M + "delete_sheet", "by name", "Delete sheet removes a tab.") as f:
        book(f, "UT Delete sheet")
        f.act(M + "delete_sheet", desc="Delete Sheet2", **gg(spreadsheet_id="$sid$", sheet="Sheet2"))
        f.act(M + "list_sheets", desc="List the sheets", out={"names": "l"}, **gg(spreadsheet_id="$sid$"))
        ua.check_eq("l", ["Sheet1"])
    with ua.case(M + "hide_sheet", "left out of the list", "Hide sheet hides a tab; List sheets with exclude_hidden leaves it out.") as f:
        book(f, "UT Hide sheet")
        f.act(M + "hide_sheet", desc="Hide Sheet2", **gg(spreadsheet_id="$sid$", sheet="Sheet2"))
        f.act(M + "list_sheets", desc="Visible sheets", out={"names": "l"}, **gg(spreadsheet_id="$sid$", exclude_hidden=True))
        ua.check_eq("l", ["Sheet1"])
    with ua.case(M + "show_sheet", "visible again", "Show sheet makes a hidden tab visible again.") as f:
        book(f, "UT Show sheet")
        f.act(M + "hide_sheet", desc="Hide Sheet2 first", **gg(spreadsheet_id="$sid$", sheet="Sheet2"))
        f.act(M + "show_sheet", desc="Show it", **gg(spreadsheet_id="$sid$", sheet="Sheet2"))
        f.act(M + "list_sheets", desc="Visible sheets", out={"names": "l"}, **gg(spreadsheet_id="$sid$", exclude_hidden=True))
        ua.check_eq("l", ["Sheet1", "Sheet2"])
