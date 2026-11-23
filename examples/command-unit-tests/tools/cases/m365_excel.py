"""Microsoft 365 Excel online, with the Open workbook session (Go to sheet, Go to cell, Close), against the Graph stand-in.
UT/Budget.xlsx is seeded (Sheet1: Item, Qty, Price in A1:C4; table Orders in A6:B8; an empty Sheet2). Cases that change
a workbook first make their own with Create workbook, so every case stands on its own."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, g  # noqa: E402

M = "ooa.microsoft365/excel_"
BUDGET = "UT/Budget.xlsx"
ROWS = [["Item", "Qty", "Price"], ["Pens", 10, 2.5], ["Paper", 4, 6], ["Ink", 2, 15]]


def book(f, name, data=True, sheets=None):
    """A fresh workbook UT/xl/<name>.xlsx, with the budget rows on Sheet1 when data is on."""
    path = "UT/xl/%s.xlsx" % name
    f.act(M + "create_workbook", desc="Make a fresh workbook " + path, **g(remote_path=path, if_exists="replace", sheet_names=sheets or ["Sheet1", "Sheet2"]))
    if data:
        f.act(M + "set_range", desc="Put the budget rows on Sheet1", **g(remote_path=path, sheet="Sheet1", range="A1", values=ROWS))
    return path


def add(ua):
    start(ua, "/msgraph/reset", "Every case below talks to the local Graph stand-in; its workbook UT/Budget.xlsx starts as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("x", "Any"), ("e", "Dictionary"), ("l", "List"), ("b", "Boolean"), ("cnt", "Number")):
        ua.var(v, t)
    B = dict(remote_path=BUDGET, sheet="Sheet1")

    # ---- the Open workbook session
    with ua.case(M + "open", "open the budget workbook", "Open workbook lists the sheets and keeps the workbook, sheet and cell for the steps after it.") as f:
        f.act(M + "open", desc="Open UT/Budget.xlsx", out={"sheets": "l", "active_sheet": "s"}, **g(remote_path=BUDGET))
        ua.check_eq("s", "Sheet1")
        check(ua, "len($l$)", 2, "number of sheets")
        f.act(M + "get_cell", desc="Read A1 of the open workbook, giving no workbook or cell", out={"value": "x"}, **g())
        ua.check_eq("x", "Item")
        f.act(M + "close", desc="Close it")
    with ua.case(M + "go_to_cell", "a cell, then the one below", "Go to cell makes a cell the active one; Get cell then reads it; a direction moves one cell.") as f:
        f.act(M + "open", desc="Open the workbook", **g(remote_path=BUDGET))
        f.act(M + "go_to_cell", desc="Go to B2", out={"cell": "s"}, cell="B2")
        f.act(M + "go_to_cell", desc="One cell down", out={"cell": "s"}, direction="down")
        ua.check_eq("s", "B3")
        f.act(M + "get_cell", desc="Read the active cell", out={"value": "x"}, **g())
        ua.check_eq("x", 4)
        f.act(M + "close", desc="Close it")
    with ua.case(M + "activate_sheet", "go to the second sheet", "Go to sheet makes another sheet the active one; Get used range then looks at that (empty) sheet.") as f:
        f.act(M + "open", desc="Open the workbook", **g(remote_path=BUDGET))
        f.act(M + "activate_sheet", desc="Go to Sheet2", sheet="Sheet2")
        f.act(M + "used_range", desc="Used range of the active sheet", out={"address": "s"}, **g())
        ua.check_eq("s", "")
        f.act(M + "close", desc="Close it")
    with ua.case(M + "close", "forget the open workbook", "Close workbook says whether a workbook was open.") as f:
        f.act(M + "open", desc="Open the workbook", **g(remote_path=BUDGET))
        f.act(M + "close", desc="Close it", out={"closed": "b"})
        ua.check_eq("b", True)

    # ---- reading
    with ua.case(M + "read_range", "all cells in use with a header row", "Read range reads the used cells; the first row gives the column names.") as f:
        f.act(M + "read_range", desc="Read Sheet1 A1:C4", out={"table": "t"}, **g(range="A1:C4", **B))
        check(ua, "$t.columns", ["Item", "Qty", "Price"], "column names")
        check(ua, "$t.rows[2][2]", 15, "price of ink")
    with ua.case(M + "get_range", "values as rows", "Get range values gives a list of rows and the size.") as f:
        f.act(M + "get_range", desc="Read A2:B3", out={"values": "l", "row_count": "n"}, **g(range="A2:B3", **B))
        ua.check_eq("n", 2)
        ua.check_eq("l", [["Pens", 10], ["Paper", 4]])
    with ua.case(M + "get_cell", "value and text", "Get cell gives the value and the displayed text of one cell.") as f:
        f.act(M + "get_cell", desc="Read C2", out={"value": "x", "text": "s"}, **g(cell="C2", **B))
        ua.check_eq("x", 2.5)
        ua.check_eq("s", "2.5")
    with ua.case(M + "read_row", "row 3", "Read row gives one row from column A to the last used column.") as f:
        f.act(M + "read_row", desc="Read row 3", out={"values": "l"}, **g(row=3, **B))
        ua.check_eq("l", ["Paper", 4, 6])
    with ua.case(M + "read_column", "column A", "Read column gives one column from row 1 to the last used row.") as f:
        f.act(M + "read_column", desc="Read column A", out={"values": "l"}, **g(column="A", **B))
        check(ua, "$l[3]", "Ink", "fourth value")
    with ua.case(M + "used_range", "the budget sheet", "Get used range gives the block of used cells, its size and the last row and column.") as f:
        f.act(M + "used_range", desc="Used range of Sheet1", out={"address": "s", "last_row": "n"}, **g(**B))
        ua.check_eq("s", "A1:C8")
        ua.check_eq("n", 8)
    with ua.case(M + "find", "a text in the sheet", "Find in sheet gives the addresses of the cells whose text contains the search.") as f:
        f.act(M + "find", desc="Find 'pa'", out={"cells": "l", "count": "n"}, **g(text="pa", **B))
        ua.check_eq("l", ["A3"])
    with ua.case(M + "find_next_empty_cell", "after the last value of column C", "Find next empty cell gives the first free cell below the last value of a column.") as f:
        f.act(M + "find_next_empty_cell", desc="Next free cell in column C", out={"cell": "s", "row": "n"}, **g(column="C", **B))
        ua.check_eq("s", "C5")
    with ua.case(M + "get_worksheets", "names in tab order", "Get worksheets lists the workbook's sheets.") as f:
        f.act(M + "get_worksheets", desc="List the sheets", out={"sheets": "l"}, **g(remote_path=BUDGET))
        ua.check_eq("l", ["Sheet1", "Sheet2"])
    with ua.case(M + "list_tables", "the Orders table", "List tables lists the workbook's Excel tables.") as f:
        f.act(M + "list_tables", desc="List the tables", out={"count": "n", "table": "t"}, **g(remote_path=BUDGET))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][1]", "Orders", "table name")
    with ua.case(M + "get_table_rows", "every row of Orders", "Get table rows reads the table's rows with its header names as columns.") as f:
        f.act(M + "get_table_rows", desc="Read Orders", out={"table": "t", "count": "n"}, **g(remote_path=BUDGET, table="Orders"))
        ua.check_eq("n", 2)
        check(ua, "$t.rows[1][1]", 250, "second amount")
    with ua.case(M + "get_cell_format", "the bold header", "Read cell format gives the fill colour and font of a cell.") as f:
        f.act(M + "get_cell_format", desc="Format of A1", out={"format": "e"}, **g(cell="A1", **B))
        check(ua, "$e.bold", True, "bold")
        check(ua, "$e.fill_color", "#FFF2CC", "fill colour")
    with ua.case(M + "column_name", "split AB7", "Get column name splits a cell address into letters, column number and row, without reading a workbook.") as f:
        f.act(M + "column_name", desc="Split Sheet1!$AB$7", out={"column_name": "s", "column": "n"}, cell="Sheet1!$AB$7")
        ua.check_eq("s", "AB")
        ua.check_eq("n", 28)
    with ua.case(M + "rows", "each row of the budget", "The Each row loop goes through A1:C4 one record a turn, named by the header row.") as f:
        f.set("s", "")
        with f.loop(desc="Each budget row", iterator=M[:-6] + "excel_rows", iterator_args=g(range="A1:C4", **B), **{"as": "e"}):
            f.expr("s", "$s$ + $e.Item + ';'", desc="Collect the item")
        ua.check_eq("s", "Pens;Paper;Ink;")

    # ---- writing (each in its own workbook)
    with ua.case(M + "create_workbook", "with two named sheets", "Create workbook makes an empty .xlsx in the drive with the given sheets.") as f:
        f.act(M + "create_workbook", desc="Create UT/xl/new.xlsx", out={"item_id": "s"}, **g(remote_path="UT/xl/new.xlsx", sheet_names=["Data", "Notes"], if_exists="replace"))
        ua.check("notEmpty", "$s$", what="item id")
        f.act(M + "get_worksheets", desc="List its sheets", out={"sheets": "l"}, **g(remote_path="UT/xl/new.xlsx"))
        ua.check_eq("l", ["Data", "Notes"])
    with ua.case(M + "set_range", "rows from B2", "Set range values writes rows from a start cell and gives the range written.") as f:
        p = book(f, "setrange", data=False)
        f.act(M + "set_range", desc="Write two rows at B2", out={"address": "s"}, **g(remote_path=p, sheet="Sheet1", range="B2", values=[[1, 2], [3, 4]]))
        ua.check_eq("s", "B2:C3")
        f.act(M + "get_cell", desc="Read C3", out={"value": "x"}, **g(remote_path=p, sheet="Sheet1", cell="C3"))
        ua.check_eq("x", 4)
    with ua.case(M + "set_cell", "one value", "Set cell writes one value.") as f:
        p = book(f, "setcell", data=False)
        f.act(M + "set_cell", desc="Write D4", **g(remote_path=p, sheet="Sheet1", cell="D4", value="done"))
        f.act(M + "get_cell", desc="Read D4", out={"value": "x"}, **g(remote_path=p, sheet="Sheet1", cell="D4"))
        ua.check_eq("x", "done")
    with ua.case(M + "set_formula", "a sum", "Set formula puts a formula in a cell and gives the value Excel works out.") as f:
        p = book(f, "formula")
        f.act(M + "set_formula", desc="Sum the quantities", out={"value": "x"}, **g(remote_path=p, sheet="Sheet1", cell="B5", formula="SUM(B2:B4)"))
        ua.check_eq("x", 16)
    with ua.case(M + "read_formula", "formula and value", "Read formula gives the formula of a cell and its value.") as f:
        p = book(f, "readformula")
        f.act(M + "set_formula", desc="Put a formula in D2", **g(remote_path=p, sheet="Sheet1", cell="D2", formula="=B2*C2"))
        f.act(M + "read_formula", desc="Read D2", out={"formula": "s", "value": "x"}, **g(remote_path=p, sheet="Sheet1", cell="D2"))
        ua.check_eq("s", "=B2*C2")
        ua.check_eq("x", 25)
    with ua.case(M + "write_range", "a table at A10", "Write range writes a Data Table, column names first, from a start cell.") as f:
        p = book(f, "writerange")
        f.act(M + "write_range", desc="Write the table at A10", out={"address": "s"},
              **g(remote_path=p, sheet="Sheet1", start_cell="A10", table={"columns": ["Code", "Qty"], "rows": [["X1", 5], ["X2", 7]]}))
        ua.check_eq("s", "A10:B12")
    with ua.case(M + "write_data_table", "without the header", "Write Data Table writes the rows of a Data Table, here without its column names.") as f:
        p = book(f, "writedt", data=False)
        f.act(M + "write_data_table", desc="Write the rows at C3", out={"address": "s"},
              **g(remote_path=p, sheet="Sheet1", start_cell="C3", include_headers=False, table={"columns": ["A", "B"], "rows": [[1, 2], [3, 4], [5, 6]]}))
        ua.check_eq("s", "C3:D5")
    with ua.case(M + "copy_range", "values to another sheet", "Copy range copies cells to a place on another sheet and gives the address written.") as f:
        p = book(f, "copyrange")
        f.act(M + "copy_range", desc="Copy A1:C2 to Sheet2!B2", out={"address": "s"}, **g(remote_path=p, sheet="Sheet1", range="A1:C2", destination="B2", destination_sheet="Sheet2"))
        ua.check_eq("s", "B2:D3")
        f.act(M + "get_cell", desc="Read Sheet2!C3", out={"value": "x"}, **g(remote_path=p, sheet="Sheet2", cell="C3"))
        ua.check_eq("x", 10)
    with ua.case(M + "clear_range", "contents only", "Clear range empties the values of a range.") as f:
        p = book(f, "clear")
        f.act(M + "clear_range", desc="Clear B2:C4", **g(remote_path=p, sheet="Sheet1", range="B2:C4", clear="contents"))
        f.act(M + "used_range", desc="Used range afterwards", out={"address": "s"}, **g(remote_path=p, sheet="Sheet1"))
        ua.check_eq("s", "A1:C4")
        f.act(M + "get_cell", desc="Read B2", out={"value": "x"}, **g(remote_path=p, sheet="Sheet1", cell="B2"))
        ua.check_eq("x", "")
    with ua.case(M + "delete_range", "a whole row", "Delete range takes out row 2; the rows below move up.") as f:
        p = book(f, "delrange")
        f.act(M + "delete_range", desc="Delete row 2", **g(remote_path=p, sheet="Sheet1", range="A2:C2", shift="entire_row"))
        f.act(M + "get_cell", desc="Read A2", out={"value": "x"}, **g(remote_path=p, sheet="Sheet1", cell="A2"))
        ua.check_eq("x", "Paper")
    with ua.case(M + "insert_range", "cells pushed down", "Insert range adds empty cells and pushes the existing ones down.") as f:
        p = book(f, "insrange")
        f.act(M + "insert_range", desc="Insert at A2:C2", out={"address": "s"}, **g(remote_path=p, sheet="Sheet1", range="A2:C2", shift="down"))
        ua.check("contains", "$s$", "A2:C2", what="inserted address")
        f.act(M + "get_cell", desc="Read A3", out={"value": "x"}, **g(remote_path=p, sheet="Sheet1", cell="A3"))
        ua.check_eq("x", "Pens")
    with ua.case(M + "format_range", "bold red header", "Format range sets the font, fill and number format; Read cell format shows them.") as f:
        p = book(f, "format")
        f.act(M + "format_range", desc="Format A1:C1", **g(remote_path=p, sheet="Sheet1", range="A1:C1", bold=True, font_color="#C00000", fill_color="#FFF2CC",
                                                          border_style="Continuous", merge=False, wrap_text=True))
        f.act(M + "get_cell_format", desc="Read B1's format", out={"format": "e"}, **g(remote_path=p, sheet="Sheet1", cell="B1"))
        check(ua, "$e.font_color", "#C00000", "font colour")
        check(ua, "$e.wrap_text", True, "wrap text")
    with ua.case(M + "autofit_columns", "the header columns", "Autofit columns resizes columns to fit; the stand-in answers without an error.") as f:
        p = book(f, "autofitc")
        f.act(M + "autofit_columns", desc="Autofit A1:C1", **g(remote_path=p, sheet="Sheet1", range="A1:C1"))
        f.set("b", True, desc="The resize went through")
        ua.check_eq("b", True)
    with ua.case(M + "autofit_rows", "the data rows", "Autofit rows resizes rows to fit; the stand-in answers without an error.") as f:
        p = book(f, "autofitr")
        f.act(M + "autofit_rows", desc="Autofit A1:A4", **g(remote_path=p, sheet="Sheet1", range="A1:A4"))
        f.set("b", True, desc="The resize went through")
        ua.check_eq("b", True)
    with ua.case(M + "add_worksheet", "a named sheet", "Add worksheet adds a sheet and gives its name and id.") as f:
        p = book(f, "addsheet", data=False)
        f.act(M + "add_worksheet", desc="Add Summary", out={"sheet_name": "s"}, **g(remote_path=p, name="Summary"))
        ua.check_eq("s", "Summary")
    with ua.case(M + "rename_worksheet", "by position", "Rename worksheet renames the second sheet, chosen by its position.") as f:
        p = book(f, "renamesheet", data=False)
        f.act(M + "rename_worksheet", desc="Rename sheet 2", **g(remote_path=p, sheet_position=2, new_name="Archive"))
        f.act(M + "get_worksheets", desc="List the sheets", out={"sheets": "l"}, **g(remote_path=p))
        ua.check_eq("l", ["Sheet1", "Archive"])
    with ua.case(M + "delete_worksheet", "by name", "Delete worksheet removes a sheet.") as f:
        p = book(f, "delsheet", data=False)
        f.act(M + "delete_worksheet", desc="Delete Sheet2", **g(remote_path=p, sheet="Sheet2"))
        f.act(M + "get_worksheets", desc="List the sheets", out={"sheets": "l"}, **g(remote_path=p))
        ua.check_eq("l", ["Sheet1"])
    with ua.case(M + "hide_worksheet", "hide a tab", "Hide worksheet hides a sheet; Get worksheets shows it as Hidden.") as f:
        p = book(f, "hide", data=False)
        f.act(M + "hide_worksheet", desc="Hide Sheet2", **g(remote_path=p, sheet="Sheet2"))
        f.act(M + "get_worksheets", desc="List the sheets", out={"table": "t"}, **g(remote_path=p))
        check(ua, "$t.rows[1][3]", "Hidden", "visibility")
    with ua.case(M + "show_worksheet", "show it again", "Show worksheet makes a hidden sheet visible.") as f:
        p = book(f, "show", data=False)
        f.act(M + "hide_worksheet", desc="Hide Sheet2 first", **g(remote_path=p, sheet="Sheet2", very_hidden=True))
        f.act(M + "show_worksheet", desc="Show Sheet2", **g(remote_path=p, sheet="Sheet2"))
        f.act(M + "get_worksheets", desc="List the sheets", out={"table": "t"}, **g(remote_path=p))
        check(ua, "$t.rows[1][3]", "Visible", "visibility")
    with ua.case(M + "copy_worksheet", "into a new sheet", "Copy worksheet copies a sheet's cells and formats into a new sheet.") as f:
        p = book(f, "copysheet")
        f.act(M + "copy_worksheet", desc="Copy Sheet1 to Copy", out={"sheet_name": "s", "address": "x"}, **g(remote_path=p, sheet="Sheet1", new_name="Copy"))
        ua.check_eq("x", "A1:C4")
        f.act(M + "get_cell", desc="Read Copy!A4", out={"value": "x"}, **g(remote_path=p, sheet="Copy", cell="A4"))
        ua.check_eq("x", "Ink")
    with ua.case(M + "create_table", "from the budget rows", "Create table turns a range into a named Excel table.") as f:
        p = book(f, "table")
        f.act(M + "create_table", desc="Make a table of A1:C4", out={"table_name": "s"}, **g(remote_path=p, sheet="Sheet1", range="A1:C4", name="Budget"))
        ua.check_eq("s", "Budget")
        f.act(M + "get_table_rows", desc="Read it", out={"count": "n"}, **g(remote_path=p, table="Budget"))
        ua.check_eq("n", 3)
    with ua.case(M + "add_table_rows", "two more rows", "Add table rows appends rows to the end of a table.") as f:
        p = book(f, "addrows")
        f.act(M + "create_table", desc="Make a table of A1:C4", **g(remote_path=p, sheet="Sheet1", range="A1:C4", name="Budget"))
        f.act(M + "add_table_rows", desc="Add two rows", out={"count": "n"}, **g(remote_path=p, table="Budget", rows=[["Tape", 1, 3], ["Glue", 2, 4]]))
        ua.check_eq("n", 2)
        f.act(M + "get_table_rows", desc="Read row 5", out={"table": "t"}, **g(remote_path=p, table="Budget", row_index=5))
        check(ua, "$t.rows[0][0]", "Glue", "last item")
    with ua.case(M + "rename_table", "a new name", "Rename table gives a table another name.") as f:
        p = book(f, "renametable")
        f.act(M + "create_table", desc="Make a table", **g(remote_path=p, sheet="Sheet1", range="A1:C4", name="Old"))
        f.act(M + "rename_table", desc="Rename it", **g(remote_path=p, table="Old", new_name="Renamed"))
        f.act(M + "list_tables", desc="List the tables", out={"table": "t"}, **g(remote_path=p))
        check(ua, "$t.rows[0][1]", "Renamed", "table name")
    with ua.case(M + "delete_table", "table and data", "Delete table removes the table and its cells.") as f:
        p = book(f, "deltable")
        f.act(M + "create_table", desc="Make a table", **g(remote_path=p, sheet="Sheet1", range="A1:C4", name="Gone"))
        f.act(M + "delete_table", desc="Delete it", **g(remote_path=p, table="Gone"))
        f.act(M + "list_tables", desc="List the tables", out={"count": "n"}, **g(remote_path=p))
        ua.check_eq("n", 0)
