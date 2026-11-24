"""SAP GUI cases against a made-up test system "UT1 Test" (client 100, user UT_BOT, password in the vault as
unit_tests/sap/password). They need SAP GUI for Windows with scripting allowed, and the usual screens: SAP Easy Access,
SE16 (data browser on table T001, company codes), SU3 (own user data, tabs and check boxes) and the Easy Access menu
tree. The first case signs in; the others use that connection (0) and session (0); the last closes it.
Here (no SAP GUI) the agent is built and validated only."""
from helpers_desktop import cred

OK = "wnd[0]/tbar[0]/okcd"
GRID = "wnd[0]/usr/cntlGRID1/shellcont/shell"
TREE = "wnd[0]/usr/cntlIMAGE_CONTAINER/shellcont/shell/shellcont[0]/shell"
TABLE_FIELD = "wnd[0]/usr/ctxtDATABROWSE-TABLENAME"


def add(ua):
    for n, t in (("ut_text", "String"), ("ut_n", "Number"), ("ut_ok", "Boolean"), ("ut_any", "Any"), ("ut_table", "Table"),
                 ("ut_conn", "Number")):
        ua.var(n, t)

    def se16(f):
        f.act("ooa.sap/run_transaction", desc="Start SE16", tcode="SE16")
        f.act("ooa.sap/set_field", desc="Table T001", id=TABLE_FIELD, value="T001", press_enter=True)
        f.act("ooa.sap/send_key", desc="F8: show the rows", key="F8")

    with ua.case("ooa.sap/log_on", "sign in to the test system",
                 "Log on starts SAP Logon if needed, opens the system by its description and signs in; it gives the "
                 "connection number the other steps use.") as f:
        f.act("ooa.sap/log_on", desc="Sign in to UT1", system="UT1 Test", client="100", user="UT_BOT", password=cred("sap"),
              language="EN", multiple_logon="continue", out={"connection": "ut_conn"})
        ua.check("ge", "$ut_conn$", 0, what="connection")

    with ua.case("ooa.sap/open_connection", "a second connection without signing in",
                 "Open connection opens a system's sign-in screen only (for single sign-on or a custom sign-in); it is "
                 "closed again with Close.") as f:
        f.act("ooa.sap/open_connection", desc="Open UT1's sign-in screen", system="UT1 Test", out={"connection": "ut_n"})
        ua.check("gt", "$ut_n$", 0, what="connection")
        f.act("ooa.sap/close", desc="Close that connection", connection="$ut_n$")

    with ua.case("ooa.sap/run_transaction", "SE16 opens",
                 "Run transaction starts a transaction code; the screen's title field shows it.") as f:
        f.act("ooa.sap/run_transaction", desc="Start SE16", tcode="SE16", fail_on_error_message=True)
        f.act("ooa.sap/get_field", desc="Read the table field's type", id=TABLE_FIELD, property="type", out={"text": "ut_text"})
        ua.check("contains", "$ut_text$", "CTextField", what="field type")

    with ua.case("ooa.sap/set_field", "type a table name",
                 "Set field types a value into a field; Get field reads it back.") as f:
        f.act("ooa.sap/run_transaction", desc="Start SE16", tcode="SE16")
        f.act("ooa.sap/set_field", desc="Type T001", id=TABLE_FIELD, value="T001")
        f.act("ooa.sap/get_field", desc="Read it back", id=TABLE_FIELD, out={"text": "ut_text"})
        ua.check_eq("ut_text", "T001")

    with ua.case("ooa.sap/get_field", "the command field's tooltip",
                 "Get field reads a property of a field: text, tooltip, name, type, changeable, max length or icon.") as f:
        f.act("ooa.sap/get_field", desc="Is the command field changeable?", id=OK, property="changeable", out={"text": "ut_text"})
        ua.check("matches", "$ut_text$", "(?i)true", what="changeable")

    with ua.case("ooa.sap/send_key", "Enter and F3",
                 "Send key presses a function key in a window (Enter, F3 Back, F8 Run...).") as f:
        f.act("ooa.sap/run_transaction", desc="Start SE16", tcode="SE16")
        f.act("ooa.sap/send_key", desc="F3: back", key="F3")
        f.act("ooa.sap/send_key", desc="Enter by its key code", vkey=0)

    with ua.case("ooa.sap/press", "the Back button",
                 "Press presses a button by its id (here Back in the toolbar).") as f:
        f.act("ooa.sap/run_transaction", desc="Start SE16", tcode="SE16")
        f.act("ooa.sap/press", desc="Press Back", id="wnd[0]/tbar[0]/btn[3]")

    with ua.case("ooa.sap/click", "a button by click",
                 "Click clicks a control by its id.") as f:
        f.act("ooa.sap/run_transaction", desc="Start SE16", tcode="SE16")
        f.act("ooa.sap/click", desc="Click Back", id="wnd[0]/tbar[0]/btn[3]")

    with ua.case("ooa.sap/click_menu", "System > User Profile > Own Data",
                 "Click menu opens a menu entry by its path of texts.") as f:
        f.act("ooa.sap/click_menu", desc="Open Own Data", menu_path="System/User Profile/Own Data")
        f.act("ooa.sap/send_key", desc="F3: back", key="F3")

    with ua.case("ooa.sap/get_status_bar", "the message after an unknown transaction",
                 "Get status bar reads the status bar's message, its type and number.") as f:
        f.act("ooa.sap/run_transaction", desc="An unknown transaction", tcode="ZZUT_NONE")
        f.act("ooa.sap/get_status_bar", desc="Read the status bar", out={"text": "ut_text", "type": "ut_any"})
        ua.check("notEmpty", "$ut_text$", what="message")

    with ua.case("ooa.sap/read_grid", "company codes in the SE16 grid",
                 "Read grid turns an ALV grid into a data table.") as f:
        se16(f)
        f.act("ooa.sap/read_grid", desc="Read the grid", id=GRID, max_rows=100, out={"table": "ut_table"})
        f.act("ooa.datatable/row_count", desc="Count the rows", table="$ut_table$", out={"count": "ut_n"})
        ua.check("gt", "$ut_n$", 0, what="rows")

    with ua.case("ooa.sap/export_table", "the grid to a CSV file",
                 "Export table writes a grid or table control to a data table and a CSV file.") as f:
        se16(f)
        f.act("ooa.sap/export_table", desc="Export the grid", id=GRID, csv_path="sap_t001.csv", delimiter=";",
              out={"count": "ut_n", "path": "ut_text"})
        ua.check("gt", "$ut_n$", 0, what="rows")

    with ua.case("ooa.sap/table_row_count", "rows of the grid",
                 "Table row count gives the number of rows of a grid or table control.") as f:
        se16(f)
        f.act("ooa.sap/table_row_count", desc="Count the rows", id=GRID, out={"count": "ut_n"})
        ua.check("gt", "$ut_n$", 0, what="rows")

    with ua.case("ooa.sap/table_column_count", "columns of the grid",
                 "Table column count gives the number of columns.") as f:
        se16(f)
        f.act("ooa.sap/table_column_count", desc="Count the columns", id=GRID, out={"count": "ut_n"})
        ua.check("gt", "$ut_n$", 1, what="columns")

    with ua.case("ooa.sap/table_cell_count", "cells of the grid",
                 "Table cell count gives the number of cells (rows times columns).") as f:
        se16(f)
        f.act("ooa.sap/table_cell_count", desc="Count the cells", id=GRID, out={"count": "ut_n"})
        ua.check("gt", "$ut_n$", 1, what="cells")

    with ua.case("ooa.sap/get_table_cell", "first company code",
                 "Get table cell reads one cell by row and column name.") as f:
        se16(f)
        f.act("ooa.sap/get_table_cell", desc="Row 0, column BUKRS", id=GRID, row=0, column="BUKRS", out={"text": "ut_text"})
        ua.check("notEmpty", "$ut_text$", what="company code")

    with ua.case("ooa.sap/get_table_cell_index", "where company code 1000 is",
                 "Get table cell index finds the row (and column) holding a value.") as f:
        se16(f)
        f.act("ooa.sap/get_table_cell_index", desc="Find 1000 in BUKRS", id=GRID, value="1000", column="BUKRS",
              out={"found": "ut_ok", "row": "ut_n"})
        ua.check_eq("ut_ok", True)

    with ua.case("ooa.sap/set_table_cell", "a cell of an editable table",
                 "Set table cell types a value into a cell of an editable table control, found by row or by text.") as f:
        f.act("ooa.sap/run_transaction", desc="Start SU3", tcode="SU3")
        f.act("ooa.sap/set_table_cell", desc="Set row 0, column PARID", id="wnd[0]/usr/tabsTABSTRIP1/tabpPARAM/ssubMAINAREA:SAPLSUID_MAINTENANCE:1104/cntlG_PARAMETER_CONTAINER/shellcont/shell",
              row=0, column="PARID", value="BUK", out={"row": "ut_n"})

    with ua.case("ooa.sap/double_click", "a grid row",
                 "Double click double-clicks a control, or a grid cell by item and column.") as f:
        se16(f)
        f.act("ooa.sap/double_click", desc="Open the first row", id=GRID, item="0", column="BUKRS")
        f.act("ooa.sap/send_key", desc="F3: back", key="F3")

    with ua.case("ooa.sap/right_click", "a grid cell's menu",
                 "Right click opens a control's context menu and can pick an entry by its code or text.") as f:
        se16(f)
        f.act("ooa.sap/right_click", desc="Right-click the first cell, Copy Text", id=GRID, item="0", column="BUKRS", menu_item="&COPY",
              menu_by="code")

    with ua.case("ooa.sap/press_toolbar_button", "sort in the grid toolbar",
                 "Press toolbar button presses a button of a grid's toolbar (and a menu entry under it).") as f:
        se16(f)
        f.act("ooa.sap/press_toolbar_button", desc="Sort ascending", id=GRID, button_id="&SORT_ASC")

    with ua.case("ooa.sap/get_selected_item", "the selected grid row",
                 "Get selected item gives the key, text and index of the selected item of a list or tree.") as f:
        f.act("ooa.sap/get_selected_item", desc="Selected node of the Easy Access tree", id=TREE, out={"key": "ut_text"})

    with ua.case("ooa.sap/expand_node", "a node of the Easy Access tree",
                 "Expand node opens a tree node by its key, text or index; Get item count then counts the tree's items.") as f:
        f.act("ooa.sap/run_transaction", desc="SAP Easy Access", tcode="SESSION_MANAGER")
        f.act("ooa.sap/expand_node", desc="Open the first node", id=TREE, node_key="0", match_by="index", select=True)
        f.act("ooa.sap/get_item_count", desc="Count the items", id=TREE, out={"count": "ut_n"})
        ua.check("gt", "$ut_n$", 0, what="items")

    with ua.case("ooa.sap/get_item_count", "items of the Easy Access tree",
                 "Get item count gives how many items a list, tree or combo box has.") as f:
        f.act("ooa.sap/get_item_count", desc="Count the tree items", id=TREE, out={"count": "ut_n"})
        ua.check("gt", "$ut_n$", 0, what="items")

    with ua.case("ooa.sap/get_children", "controls of the main window's user area",
                 "Get children lists the child controls of a container (id, type, text).") as f:
        f.act("ooa.sap/get_children", desc="Children of wnd[0]/usr", id="wnd[0]/usr", out={"table": "ut_table", "count": "ut_n"})
        ua.check("gt", "$ut_n$", 0, what="children")

    with ua.case("ooa.sap/select_item", "a language in a list box",
                 "Select item picks an entry of a combo box by text, key or index.") as f:
        f.act("ooa.sap/run_transaction", desc="Start SU3", tcode="SU3")
        f.act("ooa.sap/select_item", desc="Pick the first date format", id="wnd[0]/usr/tabsTABSTRIP1/tabpDEFA/ssubMAINAREA:SAPLSUID_MAINTENANCE:1105/cmbSUID_ST_NODE_DEFAULTS-DATFM",
              value="0", match_by="index")

    with ua.case("ooa.sap/check_box", "tick and read back",
                 "Check box ticks or clears a check box; Get status reads whether it is ticked.") as f:
        f.act("ooa.sap/run_transaction", desc="Start SU3", tcode="SU3")
        cb = "wnd[0]/usr/tabsTABSTRIP1/tabpDEFA/ssubMAINAREA:SAPLSUID_MAINTENANCE:1105/chkSUID_ST_NODE_DEFAULTS-CATTKENNZ"
        f.act("ooa.sap/check_box", desc="Tick it", id=cb, checked=True)
        f.act("ooa.sap/get_status", desc="Is it ticked?", id=cb, out={"selected": "ut_ok"})
        ua.check_eq("ut_ok", True)

    with ua.case("ooa.sap/get_status", "a radio button",
                 "Get status says whether a check box or radio button is selected.") as f:
        f.act("ooa.sap/get_status", desc="Read a radio button", id="wnd[0]/usr/radRB1", out={"selected": "ut_ok"})

    with ua.case("ooa.sap/select_radio", "pick a radio button",
                 "Select radio selects a radio button.") as f:
        f.act("ooa.sap/select_radio", desc="Select it", id="wnd[0]/usr/radRB1")
        f.act("ooa.sap/get_status", desc="Is it selected?", id="wnd[0]/usr/radRB1", out={"selected": "ut_ok"})
        ua.check_eq("ut_ok", True)

    with ua.case("ooa.sap/close", "close the test connection",
                 "Close ends a connection (signing it off).") as f:
        f.act("ooa.sap/close", desc="Close connection 0", connection=0)
