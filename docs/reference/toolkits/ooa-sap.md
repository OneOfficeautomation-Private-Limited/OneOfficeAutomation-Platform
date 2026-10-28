# SAP GUI

Toolkit `ooa.sap`, version 1.0.0. Log on to SAP, run transactions, set and read fields, press buttons, read grids and the status bar through SAP GUI Scripting.

[All toolkits](README.md)

## Contents

- **Fields**: Set check box, Click, Double-click, Get children, Get field, Get item count, Get selected item, Get check box or radio state, Get status bar, Press button, Right-click (context menu), Select list item, Select radio button, Send key, Set field
- **Menus**: Choose menu entry
- **Session**: Close connection, Open system and log on, Open system (single sign-on), Run transaction
- **Trees**: Expand tree node
- **Tables**: Export table, Get table cell, Find value in table, Press grid or tree toolbar button, Read grid, Set table cell, Get table cell count, Get table column count, Get table row count

## Fields

### Set check box

`ooa.sap/check_box` (action)

Tick, clear or toggle a check box.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Checked | `checked` | Yes/No | No | Yes | Main | True to tick, false to clear. |
| Toggle | `toggle` | Yes/No | No | No | Main | Switch the check box to the opposite state; checked is then ignored. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 60 s; needs: desktop.*

### Click

`ooa.sap/click` (action)

Click an element: buttons are pressed, tabs and radio buttons selected, check boxes toggled, other elements focused.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

### Double-click

`ooa.sap/double_click` (action)

Double-click an element, a grid cell or a tree node (fields get F2, which SAP treats as a double-click).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Item | `item` | Text | No |  | More options | Grid row (0-based) or tree node key; empty for the current cell or a plain element. |
| Column | `column` | Text | No |  | More options | Grid column (technical name, title or 0-based position). |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

### Get children

`ooa.sap/get_children` (action)

List the elements inside a container with their ids, types, names and texts.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. Example: `wnd[0]/usr` |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, type, name, text. |
| `count` | Number | Number of child elements. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get field

`ooa.sap/get_field` (action)

Read the text (or another property) of a field, label or the status bar.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Property | `property` | Text | No | `text` | More options | Which property to read; text by default. One of: `text`, `tooltip`, `name`, `type`, `changeable`, `max_length`, `icon_name` |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Field text, or the value of the chosen property. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get item count

`ooa.sap/get_item_count` (action)

Count the entries of a drop-down list, the rows of a grid or table, the nodes of a tree, or the elements in a container.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of items. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get selected item

`ooa.sap/get_selected_item` (action)

Read what is selected in a drop-down list, tab strip, tree or grid.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `key` | Text | Key of the entry, id of the tab, node key, or the selected grid rows (e.g. 0,3-5). |
| `text` | Text | Displayed text of the selection. |
| `index` | Number | 0-based position of the entry or tab; -1 when it does not apply. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get check box or radio state

`ooa.sap/get_status` (action)

Read whether a check box is ticked or a radio button is selected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `selected` | Yes/No | True when ticked or selected. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get status bar

`ooa.sap/get_status_bar` (action)

Read the status bar message and type (S success, W warning, E error, I info), with its message class and number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Number | No | `0` | Main | Window whose status bar to read (0 = main window). |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Message text. |
| `type` | Text | S, W, E, I, A or empty. |
| `message_id` | Text | Message class (work area), e.g. V1. |
| `message_number` | Text | Message number within the class, e.g. 311. |
| `parameters` | List | The values filled into the message, e.g. the new document number. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Press button

`ooa.sap/press` (action)

Press a toolbar or screen button by its id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. Example: `wnd[0]/tbar[1]/btn[8]` |
| Fail on error message | `fail_on_error_message` | Yes/No | No | No | More options | Stop with an error when the status bar then shows an error message. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

### Right-click (context menu)

`ooa.sap/right_click` (action)

Open the context menu of an element, grid cell or tree node and optionally choose an entry by its function code or element id, its text, or its position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Menu by | `menu_by` | Text | No | `code` | Main | How menu_item names the entry: code (function code or element id), text (the entry's text) or position (0-based, counted from the top). One of: `code`, `text`, `position` |
| Menu item | `menu_item` | Text | No |  | Main | Entry to choose, as menu_by says: a function code (grids and trees) or element id (screen elements); the entry's text as shown; or its 0-based position, with sub-menus as 2 > 1. |
| Item | `item` | Text | No |  | More options | Grid row (0-based) or tree node key. |
| Column | `column` | Text | No |  | More options | Grid column (technical name, title or 0-based position). |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

### Select list item

`ooa.sap/select_item` (action)

Choose an entry of a drop-down list, a tab of a tab strip, or a node of a tree, by key, displayed text or position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Match by | `match_by` | Text | No | `text` | Main | Whether value is the displayed text, the key, or the 0-based position. One of: `text`, `key`, `index` |
| Value | `value` | Text | Yes |  | Main | Key, displayed text or 0-based position of the entry. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 60 s; needs: desktop.*

### Select radio button

`ooa.sap/select_radio` (action)

Select a radio button option.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 60 s; needs: desktop.*

### Send key

`ooa.sap/send_key` (action)

Send a virtual key: 0 Enter, 3 Back (F3), 8 Execute (F8), 11 Save (Ctrl+S), 12 Cancel (F12).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Vkey | `vkey` | Number | No | `0` | Main | SAP virtual key number. |
| Window | `window` | Number | No | `0` | More options | Window to send the key to (0 = main window, 1 = the first pop-up). |
| Fail on error message | `fail_on_error_message` | Yes/No | No | No | More options | Stop with an error when the status bar then shows an error message. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

### Set field

`ooa.sap/set_field` (action)

Type a value into a field (Credentials are passed without logging).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Value | `value` | Any | Yes |  | Main | Value, or a Credential variable. |
| Append | `append` | Yes/No | No | No | Main | Add the value after the text already in the field instead of replacing it. |
| Press Enter afterwards | `press_enter` | Yes/No | No | No | More options | Press Enter in the window afterwards. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 60 s; needs: desktop.*

## Menus

### Choose menu entry

`ooa.sap/click_menu` (action)

Choose an entry from the window's menu bar by its path of texts, e.g. System > User Profile > Own Data, by positions, or by its element id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Match by | `match_by` | Text | No | `text` | Main | text: the path holds menu texts; index: it holds 0-based positions, e.g. 5 > 1 > 0. One of: `text`, `index` |
| Menu path | `menu_path` | Text | Yes |  | Main | Menu texts separated by > (or /), or a menu element id such as wnd[0]/mbar/menu[3]/menu[2]. Example: `System > User Profile > Own Data` |
| Window | `window` | Number | No | `0` | More options | Window number whose menu bar to use (0 = main window). |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

## Session

### Close connection

`ooa.sap/close` (action)

Log off and close an SAP connection.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Connection | `connection` | Number | No | `0` | Main | SAP connection number (0 = first open system). |

*Time limit 60 s; needs: desktop.*

### Open system and log on

`ooa.sap/log_on` (action)

Open a system from SAP Logon by its description and log on with client, user, password and language.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| System | `system` | Text | Yes |  | Main | System description exactly as in SAP Logon. Example: `PRD - Live production` |
| Client | `client` | Text | Yes |  | Main | Client. Example: `100` |
| User | `user` | Text | Yes |  | Main | User. |
| Password | `password` | Credential (vault) | Yes |  | Main | Password from the vault. |
| Language | `language` | Text | No | `EN` | Main | Logon language. |
| System type | `system_type` | Text | No | `description` | More options | Whether system is the SAP Logon entry description or a connection string such as /H/host/S/3200. One of: `description`, `connection_string` |
| Multiple logon | `multiple_logon` | Text | No | `ignore` | More options | What to do when SAP reports the user is already logged on: leave the question open, keep the other sessions, end them, or stop with an error. One of: `ignore`, `continue`, `end_other_sessions`, `fail` |
| Logon path | `logon_path` | File path | No |  | More options; tick Start SAP Logon first | SAP Logon program to start first when it is not running; SAP Logon must already be open when empty. Example: `C:\Program Files (x86)\SAP\FrontEnd\SAPgui\saplogon.exe` |
| Start timeout (seconds) | `start_timeout_seconds` | Number | No | `60` | More options | Seconds to wait for SAP Logon to start. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `connection` | Number | Connection number for later actions. |

*Time limit 180 s; needs: desktop.*

### Open system (single sign-on)

`ooa.sap/open_connection` (action)

Open a system from SAP Logon that signs on by itself (SSO or SNC), with no user or password.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| System | `system` | Text | Yes |  | Main | System description exactly as in SAP Logon, or a connection string. Example: `PRD - Live production` |
| System type | `system_type` | Text | No | `description` | More options | Whether system is the SAP Logon description or a connection string. One of: `description`, `connection_string` |
| Logon path | `logon_path` | File path | No |  | More options; tick Start SAP Logon first | SAP Logon program to start first when it is not running. |
| Start timeout (seconds) | `start_timeout_seconds` | Number | No | `60` | More options | Seconds to wait for SAP Logon to start. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `connection` | Number | Connection number for later actions. |

*Time limit 180 s; needs: desktop.*

### Run transaction

`ooa.sap/run_transaction` (action)

Start a transaction code, e.g. VA01 or /nME23N.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Tcode | `tcode` | Text | Yes |  | Main | Transaction code. Example: `VA01` |
| New window | `new_window` | Yes/No | No | No | More options | Open the transaction in a new SAP window (session) instead of the current one. |
| Fail on error message | `fail_on_error_message` | Yes/No | No | No | More options | Stop with an error when the status bar then shows an error message. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

## Trees

### Expand tree node

`ooa.sap/expand_node` (action)

Expand a node of a tree control by its node key, text or position, optionally selecting it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. Example: `wnd[0]/usr/cntlTREE/shellcont/shell` |
| Match by | `match_by` | Text | No | `key` | Main | Whether node_key is the node key, its displayed text, or its 0-based position among all nodes. One of: `key`, `text`, `index` |
| Node key | `node_key` | Text | Yes |  | Main | Node key as shown by the script recorder (keep any leading spaces), or the node text or position (see match_by). |
| Select | `select` | Yes/No | No | No | Main | Also select the node. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

## Tables

### Export table

`ooa.sap/export_table` (action)

Read an ALV grid or a classic table control into a table, scrolling through all rows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. Example: `wnd[0]/usr/tblSAPMV45ATCTRL_U_ERF_AUFTRAG` |
| CSV path | `csv_path` | File path | No |  | Main; tick Save the rows to a CSV file | Also save the rows to this CSV file; nothing is saved when empty. |
| Encoding | `encoding` | Text | No | `UTF-8` | Main; tick Save the rows to a CSV file | Character encoding of the CSV file. One of: `UTF-8`, `UTF-16`, `ISO-8859-1`, `windows-1252` |
| Include header | `include_header` | Yes/No | No | Yes | Main; tick Save the rows to a CSV file | Write the column titles as the first CSV line. |
| Append | `append` | Yes/No | No | No | Main; tick Save the rows to a CSV file | Add to the end of an existing CSV file instead of replacing it (no header is added to a non-empty file). |
| Delimiter | `delimiter` | Text | No | `,` | Main; tick Save the rows to a CSV file | Separator between CSV values. One of: `,`, `;`, `tab`, `|` |
| Maximum rows | `max_rows` | Number | No | `5000` | More options | Safety limit. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Table content with the column titles as names. |
| `count` | Number | Number of rows read. |
| `path` | File path | The CSV file written, when csv_path was given. |

*Time limit 600 s; safe to repeat; needs: desktop.*

### Get table cell

`ooa.sap/get_table_cell` (action)

Read one cell of an ALV grid or table control.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Row | `row` | Number | Yes |  | Main | Row (0-based, counted from the top of the whole table). |
| Column | `column` | Text | Yes |  | Main | Column: 0-based position, title, or technical field name. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Cell value. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Find value in table

`ooa.sap/get_table_cell_index` (action)

Find the first cell (or every cell) holding a value, ignoring surrounding spaces, and return its row and column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Value | `value` | Text | Yes |  | Main | Value to look for. |
| Case sensitive | `case_sensitive` | Yes/No | No | No | Main | Match upper and lower case exactly. |
| All occurrences | `all_occurrences` | Yes/No | No | No | Main | Keep searching and list every matching cell. |
| Column | `column` | Text | No |  | More options; tick Search one column only | Only search this column (position, title or field name); all columns when empty. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when the value was found. |
| `row` | Number | Row (0-based), or -1 when not found. |
| `column` | Text | Column name (grid) or title (table control) of the match. |
| `matches` | Table | Columns: row, column; every match when all_occurrences is on, else the first. |

*Time limit 300 s; safe to repeat; needs: desktop.*

### Press grid or tree toolbar button

`ooa.sap/press_toolbar_button` (action)

Press a button in the toolbar of an ALV grid or tree (e.g. &MB_EXPORT), optionally choosing an entry of its drop-down menu.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. Example: `wnd[0]/usr/cntlGRID1/shellcont/shell` |
| Button ID | `button_id` | Text | Yes |  | Main | Button id as shown by the script recorder. Example: `&MB_EXPORT` |
| Menu item | `menu_item` | Text | No |  | Main; tick Then choose a drop-down entry | Function code of the drop-down entry to choose; none when empty. Example: `&PC` |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

*Time limit 120 s; needs: desktop.*

### Read grid

`ooa.sap/read_grid` (action)

Read an ALV grid (GuiGridView) into a table, using the column titles as names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. Example: `wnd[0]/usr/cntlGRID1/shellcont/shell` |
| Maximum rows | `max_rows` | Number | No | `5000` | More options | Safety limit. |
| Technical names | `technical_names` | Yes/No | No | No | More options | Name the columns by their technical field names (e.g. MATNR) instead of their titles. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Grid content. |

*Time limit 300 s; safe to repeat; needs: desktop.*

### Set table cell

`ooa.sap/set_table_cell` (action)

Type a value into an editable cell of an ALV grid or table control, chosen by row and column or found by the text it holds.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Row | `row` | Number | No |  | Main; choice Find the cell = By row | Row (0-based, counted from the top of the whole table); not used with find_text. |
| Find text | `find_text` | Text | No |  | Main; choice Find the cell = By the text it holds | Find the cell by the text it holds now (surrounding spaces and case ignored) and write into the first match, searching top to bottom; row is then not used. |
| Column | `column` | Text | No |  | Main | Column: 0-based position, title, or technical field name. With find_text, only this column is searched (all columns when empty). |
| Value | `value` | Any | Yes |  | Main | New value, or a Credential variable. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `row` | Number | Row (0-based) of the cell that was changed. |
| `column` | Text | Column of the cell that was changed, as given or as found. |

*Time limit 60 s; needs: desktop.*

### Get table cell count

`ooa.sap/table_cell_count` (action)

Count the cells (rows times columns) of an ALV grid or a table control.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of cells. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get table column count

`ooa.sap/table_column_count` (action)

Count the columns of an ALV grid or a table control.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of columns. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get table row count

`ooa.sap/table_row_count` (action)

Count the rows of an ALV grid or a table control.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. |
| Connection | `connection` | Number | No | `0` | More options | SAP connection number (0 = first open system). |
| Session | `session` | Number | No | `0` | More options | Session (window) number within the connection (0 = first). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of rows. |

*Time limit 60 s; safe to repeat; needs: desktop.*
