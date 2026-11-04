# Excel (no Office needed)

Toolkit `ooa.excel`, version 1.0.0. Read and write .xlsx workbooks without Microsoft Office installed.

[All toolkits](README.md)

## Contents

- **Sheets**: Create worksheet, Append worksheet, Copy worksheet, Delete worksheet, Get current sheet name, Get sheet names, Hide sheet, Rename worksheet, Get sheet count, Switch to sheet, Unhide all sheets, Unhide sheet
- **Workbook**: Append workbook, Close, Create workbook, Open workbook, Save, Save as
- **Rows and columns**: Autofit columns, Delete columns, Delete rows, Hide columns, Hide rows, Insert columns, Insert rows, Remove blank rows, Get number of rows, Set column width, Set row height, Unhide columns, Unhide rows
- **Links**: Break workbook links, Change workbook link, Get workbook links
- **Addresses**: Get cell address, Get column name, Get row and column number
- **Cells**: Clear range, Copy range, Delete cells, Fill down or right, Filter, Find, Find next empty cell, Get all cells, Get cell, Get range, Get table range, Go to cell, Go to next empty cell, Read column, Read cell formula, Read row, Remove duplicate rows, Replace, Select range, Set cell, Set formula, Sort, Text to columns, Write from Data Table
- **Pivot tables and charts**: Create chart, Create pivot table, Create Excel table
- **Tables**: Delete table column, Insert table column
- **Formatting**: Format cells, Get cell colour, Set cell colour
- **Sensitivity**: Get sensitivity label, Set sensitivity label
- **Protection**: Protect sheet, Protect workbook, Unprotect sheet, Unprotect workbook
- **Loop types**: Each row in a worksheet

## Sheets

### Create worksheet

`ooa.excel/add_sheet` (action)

Create a worksheet at the end or at a position, or clear it first when replace is true.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Worksheet name. |
| Position | `position` | Number | No |  | Main | 1-based position among the sheets; at the end when empty. Range: 1 to  |
| Activate | `activate` | Yes/No | No | No | Main | Make the new sheet the active one. |
| Replace | `replace` | Yes/No | No | Yes | More options | Replace an existing sheet with this name. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Append worksheet

`ooa.excel/append_worksheet` (action)

Copy one sheet, chosen by name or position, from another workbook file to the end of this workbook.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Workbook file that holds the sheet. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password of that file, when it is encrypted. |
| Source sheet | `source_sheet` | Text | No |  | Main; choice Sheet to copy = By name | Sheet to copy; the first sheet when empty. |
| Source index | `source_index` | Number | No |  | Main; choice Sheet to copy = By position | Position of the sheet to copy (1 = first); used when the sheet name is empty. Range: 1 to  |
| New name | `new_name` | Text | No |  | Main | Name for the copy; the source name when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Name the copied sheet received. |

*Time limit 60 s; needs: filesystem.*

### Copy worksheet

`ooa.excel/copy_sheet` (action)

Duplicate a worksheet inside the same workbook under a new name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet to copy; active sheet when empty. |
| New name | `new_name` | Text | No |  | Main | Name for the copy; a numbered name when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Name of the new sheet. |

*Time limit 60 s.*

### Delete worksheet

`ooa.excel/delete_sheet` (action)

Delete a worksheet chosen by name or position.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | No |  | Main; choice Sheet by = Name | Worksheet name. Shown when: `{"index": [""]}` |
| Index | `index` | Number | No |  | Main; choice Sheet by = Position | 1-based sheet position, used instead of the name. Range: 1 to ; Shown when: `{"name": [""]}` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Get current sheet name

`ooa.excel/get_current_sheet_name` (action)

Return the name of the active worksheet.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `excel` | Main | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Active sheet name. |

*Time limit 60 s; safe to repeat.*

### Get sheet names

`ooa.excel/get_sheet_names` (action)

List the worksheet names, optionally leaving out hidden sheets.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `excel` | Main | Workbook session. |
| Include hidden | `include_hidden` | Yes/No | No | Yes | More options | Include hidden sheets in the list. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `names` | List | Sheet names in order. |

*Time limit 60 s; safe to repeat.*

### Hide sheet

`ooa.excel/hide_sheet` (action)

Hide a worksheet chosen by name or position; at least one sheet must stay visible.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Sheet to hide. Shown when: `{"index": [""]}` |
| Index | `index` | Number | No |  | Main; choice Sheet by = Position | 1-based sheet position, used instead of the name. Range: 1 to ; Shown when: `{"sheet": [""]}` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Rename worksheet

`ooa.excel/rename_sheet` (action)

Give a worksheet, chosen by name or position, a new name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Current sheet name. Shown when: `{"index": [""]}` |
| Index | `index` | Number | No |  | Main; choice Sheet by = Position | 1-based sheet position, used instead of the name. Range: 1 to ; Shown when: `{"sheet": [""]}` |
| New name | `new_name` | Text | Yes |  | Main | New sheet name. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Get sheet count

`ooa.excel/sheet_count` (action)

Count the worksheets in the workbook, with or without the hidden ones.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Include hidden | `include_hidden` | Yes/No | No | Yes | Main | Count hidden sheets too. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of worksheets. |

*Time limit 60 s; safe to repeat.*

### Switch to sheet

`ooa.excel/switch_sheet` (action)

Make a worksheet, chosen by name or position, the active one; later steps with an empty sheet input work on it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Sheet name to activate. Shown when: `{"index": [""]}` |
| Index | `index` | Number | No |  | Main; choice Sheet by = Position | 1-based sheet position, used instead of the name. Range: 1 to ; Shown when: `{"sheet": [""]}` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Unhide all sheets

`ooa.excel/unhide_all_sheets` (action)

Make every hidden worksheet visible.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `excel` | Main | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of sheets that were hidden. |

*Time limit 60 s.*

### Unhide sheet

`ooa.excel/unhide_sheet` (action)

Make a hidden worksheet, chosen by name or position, visible again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main; choice Sheet by = Name | Sheet to show. Shown when: `{"index": [""]}` |
| Index | `index` | Number | No |  | Main; choice Sheet by = Position | 1-based sheet position, used instead of the name. Range: 1 to ; Shown when: `{"sheet": [""]}` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

## Workbook

### Append workbook

`ooa.excel/append_workbook` (action)

Copy every sheet of another workbook file to the end of this workbook; clashing names get a number suffix.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Workbook file whose sheets are copied in. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password of that file, when it is encrypted. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sheets` | List | Names the copied sheets received. |

*Time limit 60 s; needs: filesystem.*

### Close

`ooa.excel/close` (action)

Close the workbook session, saving the changes first when asked.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Save | `save` | Yes/No | No | No | Main | Save the changes to the opened file before closing. |
| Fail if read only | `fail_if_read_only` | Yes/No | No | Yes | Main | When the workbook was opened read-only, stop with an error instead of saving; false closes it without saving and logs a warning. Shown when: `{"save": ["true"]}` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s; needs: filesystem.*

### Create workbook

`ooa.excel/create_workbook` (action)

Create a new empty .xlsx file on disk with one named sheet and open it as a session; optionally encrypted with a password.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Path of the new workbook. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace a file that already exists at the path. |
| Sheet | `sheet` | Text | No | `Sheet1` | Main | Name of the first worksheet. |
| Password | `password` | Credential (vault) | No |  | Main; tick Require a password to open the file | Encrypt the file with this password; later saves keep it encrypted. |
| Session | `session` | Text | No | `excel` | More options | Session name used by later steps. |

*Time limit 60 s; needs: filesystem.*

### Open workbook

`ooa.excel/open` (action)

Open an .xlsx or .xls workbook, or create a new one, as a named session; can open encrypted files and open read-only.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Workbook path. |
| First row is the header | `header` | Yes/No | No | Yes | Main | Whether sheets start with a row of column names; used by get_all_cells, get_range, filter and sort_range when their header input is empty. |
| Sheet | `sheet` | Text | No |  | Main; tick Open a named sheet | Sheet to make active after opening; the sheet saved as active when empty. |
| Read only | `read_only` | Yes/No | No | No | Main | Open for reading only: save over the opened file is refused, save_as to another file still works. |
| Password | `password` | Credential (vault) | No |  | Main; tick The file has a password | Password of an encrypted workbook; save keeps the file encrypted with the same password. |
| Access token | `access_token` | Credential (vault) | No |  | Main; tick The file is on OneDrive or SharePoint | For a path starting onedrive: (onedrive:Reports/sales.xlsx): a Microsoft Graph access token from the vault. The workbook is downloaded, and save and close with save upload it back. |
| Drive | `drive` | Text | No | `me/drive` | Main; tick The file is on OneDrive or SharePoint | For onedrive: paths: me/drive (the signed-in user's OneDrive), sites/{site-id}/drive (a SharePoint library) or drives/{drive-id}. |
| Create | `create` | Yes/No | No | No | More options | Create the workbook when it does not exist. |
| Session | `session` | Text | No | `excel` | More options | Session name used by later steps. |
| Graph address (URL) | `graph_url` | Text | No | `https://graph.microsoft.com/v1.0` | More options | Microsoft Graph address (national clouds, tests). |

*Time limit 60 s; needs: filesystem, network.*

### Save

`ooa.excel/save` (action)

Save the workbook, optionally to a new path (Save as); encrypted workbooks stay encrypted.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `excel` | Main | Workbook session. |
| Path | `path` | File path | No |  | More options | Save-as path; the opened file when empty. |
| Password | `password` | Credential (vault) | No |  | More options | Encrypt the saved file with this password; the password the workbook was opened with when empty. |

*Time limit 60 s; needs: filesystem.*

### Save as

`ooa.excel/save_as` (action)

Save the workbook to a new file as xlsx, xls or csv, optionally password-protected; after xlsx or xls the session continues on the new file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Target file path. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace a file that already exists at the target path; false stops with EXCEL.EXISTS. |
| Format | `format` | Text | No |  | Main | File format; taken from the file extension when empty. One of: `xlsx`, `xls`, `csv` |
| Sheet | `sheet` | Text | No |  | Main | Sheet written for csv; active sheet when empty. Shown when: `{"format": ["csv"]}` |
| Password | `password` | Credential (vault) | No |  | More options | Encrypt the new xlsx or xls file with this password; later saves keep it. Not used for csv. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s; needs: filesystem.*

## Rows and columns

### Autofit columns

`ooa.excel/autofit_columns` (action)

Make columns just wide enough for their contents, like double-clicking a column border in Excel.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | No |  | Main; choice Columns = Chosen columns | Letter or 1-based number of the first column; every used column when empty. Example: `A` |
| Count | `count` | Number | No | `1` | Main; choice Columns = Chosen columns | How many columns from the first one. Range: 1 to ; Shown when: `{"column": ["!", ""]}` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of columns resized. |

*Time limit 60 s.*

### Delete columns

`ooa.excel/delete_columns` (action)

Delete whole columns and move the columns to the right leftwards.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | Column letter or 1-based number of the first column to delete. Example: `C` |
| Count | `count` | Number | No | `1` | Main | How many columns to delete. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Delete rows

`ooa.excel/delete_rows` (action)

Delete whole rows and move the rows below up.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Row | `row` | Number | Yes |  | Main | 1-based number of the first row to delete. Example: `3`; Range: 1 to  |
| Count | `count` | Number | No | `1` | Main | How many rows to delete. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Hide columns

`ooa.excel/hide_columns` (action)

Hide one or more columns.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | Column letter or 1-based number of the first column. Example: `B` |
| Count | `count` | Number | No | `1` | Main | How many columns. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Hide rows

`ooa.excel/hide_rows` (action)

Hide one or more rows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Row | `row` | Number | Yes |  | Main | 1-based number of the first row. Range: 1 to  |
| Count | `count` | Number | No | `1` | Main | How many rows. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Insert columns

`ooa.excel/insert_columns` (action)

Insert empty columns before a column, moving the columns to the right.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | Column letter or 1-based number the new columns go before. Example: `C` |
| Count | `count` | Number | No | `1` | Main | How many columns to insert. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Insert rows

`ooa.excel/insert_rows` (action)

Insert empty rows before a row number, moving the rows below down.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Row | `row` | Number | Yes |  | Main | 1-based row number the new rows go before. Example: `3`; Range: 1 to  |
| Count | `count` | Number | No | `1` | Main | How many rows to insert. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Remove blank rows

`ooa.excel/remove_blank_rows` (action)

Delete every empty row inside the used area, or between two rows, and close the gaps.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Start row | `start_row` | Number | No |  | Main; choice From = A specific row | First row to check (1 = first row); the first row when empty. Range: 1 to  |
| End row | `end_row` | Number | No |  | Main; choice To = A specific row | Last row to check; the last row with data when empty. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of rows removed. |

*Time limit 60 s.*

### Get number of rows

`ooa.excel/row_count` (action)

Count the rows that hold data, or all rows of the used area, and report the number of the last used row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; active sheet when empty. |
| Mode | `mode` | Text | No | `non_empty` | Main | non_empty counts rows holding data; all counts every row from the first to the last used row, empty ones included. One of: `non_empty`, `all` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Rows with at least one non-empty cell, or all rows of the used area. |
| `last_row` | Number | 1-based number of the last row with data; 0 when the sheet is empty. |

*Time limit 60 s; safe to repeat.*

### Set column width

`ooa.excel/set_column_width` (action)

Set the width of one or more columns, in characters as Excel shows it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | Column letter or 1-based number of the first column. Example: `B` |
| Count | `count` | Number | No | `1` | Main | How many columns. Range: 1 to  |
| Width | `width` | Number | Yes |  | Main | Width in characters (Excel's default is 8.43); 0 hides the columns. Example: `20`; Range: 0 to 255 |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Set row height

`ooa.excel/set_row_height` (action)

Set the height of one or more rows, in points as Excel shows it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Row | `row` | Number | Yes |  | Main | 1-based number of the first row. Range: 1 to  |
| Count | `count` | Number | No | `1` | Main | How many rows. Range: 1 to  |
| Height | `height` | Number | Yes |  | Main | Height in points (Excel's default is 15); 0 hides the rows. Example: `30`; Range: 0 to 409 |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Unhide columns

`ooa.excel/unhide_columns` (action)

Show hidden columns again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | Column letter or 1-based number of the first column. Example: `B` |
| Count | `count` | Number | No | `1` | Main | How many columns. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Unhide rows

`ooa.excel/unhide_rows` (action)

Show hidden rows again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Row | `row` | Number | Yes |  | Main | 1-based number of the first row. Range: 1 to  |
| Count | `count` | Number | No | `1` | Main | How many rows. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

## Links

### Break workbook links

`ooa.excel/break_links` (action)

Replace formulas that refer to other workbooks, or to one chosen workbook, with their last calculated values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Link | `link` | Text | No |  | Main; choice Links = One link | Only break links to this workbook (path or file name as listed by get_links); all links when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of formulas replaced by values. |

*Time limit 60 s.*

### Change workbook link

`ooa.excel/change_link` (action)

Point the links to one source workbook at another file; the new file should have the same sheets and cells.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Old link | `old_link` | Text | Yes |  | Main | Current source workbook (path or file name as listed by get_links). |
| New link | `new_link` | Text | Yes |  | Main | Path or file name of the new source workbook. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of links changed. |

*Time limit 60 s.*

### Get workbook links

`ooa.excel/get_links` (action)

List the other workbooks this workbook's formulas refer to and the cells that use them.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `excel` | Main | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `links` | List | Linked workbook file names. |
| `cells` | List | Cells with external formulas, as Sheet!A1. |

*Time limit 60 s; safe to repeat.*

## Addresses

### Get cell address

`ooa.excel/cell_address` (action)

Turn a row number and a column number or letter into an address such as B7.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | 1-based column number or column letters. |
| Row | `row` | Number | Yes |  | Main | 1-based row number. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cell` | Text | Cell address. |

*Time limit 60 s; safe to repeat.*

### Get column name

`ooa.excel/column_name` (action)

Turn a 1-based column number into its letters, e.g. 28 becomes AB.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Number | Yes |  | Main | 1-based column number. Range: 1 to 16384 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Column letters. |

*Time limit 60 s; safe to repeat.*

### Get row and column number

`ooa.excel/row_number` (action)

Split a cell address such as B7 into its row and column numbers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Cell | `cell` | Text | Yes |  | Main | Cell address. Example: `B7` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `row` | Number | 1-based row number. |
| `column` | Number | 1-based column number. |
| `column_name` | Text | Column letters. |

*Time limit 60 s; safe to repeat.*

## Cells

### Clear range

`ooa.excel/clear_range` (action)

Clear the contents, the formats or both of a range, or of the current selection; the cells stay where they are (Delete cells moves the cells around them).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | No |  | Main; choice Cells = A specific range | Cell or range to clear; when empty, the range chosen by select_range or the cell chosen by go_to_cell. Example: `B2:D20` |
| Clear | `clear` | Text | No | `contents` | Main | contents empties the cells and keeps their formats (as the Delete key does); formats resets the formats and keeps the contents; all does both. One of: `contents`, `formats`, `all` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of cells that held contents or formats and were cleared. |

*Time limit 60 s.*

### Copy range

`ooa.excel/copy_range` (action)

Copy a range to another place on the same sheet or another sheet of the workbook, like copy and paste in Excel: everything, values only, formulas only or formats only. Relative cell references in formulas move with the copy.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | Yes |  | Main | Cells to copy. Example: `A1:C10` |
| Destination | `destination` | Text | Yes |  | Main | Top-left cell to paste to. Example: `E1` |
| Paste | `paste` | Text | No | `all` | Main | all copies contents and formats; values pastes the calculated values (no formulas, destination formats kept); formulas copies contents without formats; formats copies only the formats. One of: `all`, `values`, `formulas`, `formats` |
| Sheet | `sheet` | Text | No |  | More options | Sheet to copy from; active sheet when empty. |
| Destination sheet | `destination_sheet` | Text | No |  | More options | Sheet to paste to; the sheet copied from when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `range` | Text | Address of the cells written, e.g. E2:G10. |

*Time limit 60 s.*

### Delete cells

`ooa.excel/delete_cells` (action)

Delete the cells of a range, or of the current selection, and close the gap by moving the cells below up or the cells to the right left.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | No |  | Main; choice Cells = A specific range | Cell or range to delete; when empty, the range chosen by select_range or the cell chosen by go_to_cell (the sheet's active cell). Example: `B2:C3` |
| Shift | `shift` | Text | No | `up` | Main | up or left moves the remaining cells into the gap; entire_row or entire_column deletes the whole rows or columns the range touches. One of: `up`, `left`, `entire_row`, `entire_column` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Fill down or right

`ooa.excel/fill_range` (action)

Copy the first row of a range down to its other rows, or its first column right to its other columns, like Excel's Fill Down and Fill Right: values, formulas (with relative references moved) and formats.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | Yes |  | Main | Range to fill, including the first row (or column) that is copied. Example: `D2:D50` |
| Direction | `direction` | Text | No | `down` | Main | down copies the top row to the rows below it; right copies the left column to the columns right of it. One of: `down`, `right` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of cells filled. |

*Time limit 60 s.*

### Filter

`ooa.excel/filter` (action)

Return the rows of a sheet, a range or an Excel table whose value in one column matches a condition, as a Data Table; optionally also hide the other rows on the sheet.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; active sheet when empty. |
| Range | `range` | Text | No |  | Main; choice Filter = A range | Filter only this range of the sheet; the whole used area when empty. A column letter or number still means the sheet column. Example: `B3:F40` |
| Table | `table` | Text | No |  | Main; choice Filter = An Excel table | Excel table name, e.g. Table1: filter only the rows of that table (its header row gives the column names); sheet and range are then not used. |
| First row is the header | `header` | Yes/No | No |  | Main | First row holds column names; the workbook's open setting (true by default) when empty. |
| Column | `column` | Any | Yes |  | Main | Column to test: header name, letter or 1-based number. Example: `Status` |
| Operator | `operator` | Text | No | `equals` | Main | How the cell is compared with the value; between keeps values from value to value_to, both included. One of: `equals`, `not_equals`, `contains`, `not_contains`, `starts_with`, `ends_with`, `gt`, `gte`, `lt`, `lte`, `between`, `empty`, `not_empty` |
| Value | `value` | Any | No |  | Main | Value to compare with; not needed for empty and not_empty. Shown when: `{"operator": ["!", "empty", "not_empty"]}` |
| Value to | `value_to` | Any | No |  | Main | Upper bound for the between operator. Shown when: `{"operator": ["between"]}` |
| Match case | `match_case` | Yes/No | No | No | Main | Match upper/lower case exactly for text comparisons. Shown when: `{"operator": ["equals", "not_equals", "contains", "not_contains", "starts_with", "ends_with"]}` |
| Hide rows | `hide_rows` | Yes/No | No | No | More options | Also hide the sheet rows that do not match (and show the ones that do), like an AutoFilter; save to keep it. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Matching rows with the sheet's column names. |
| `count` | Number | Number of matching rows. |

*Time limit 60 s.*

### Find

`ooa.excel/find` (action)

Find cells whose content contains (or equals) a text, in the whole sheet or a range; returns their addresses in search order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | No |  | Main | Only search this range, e.g. A1:D20; the whole sheet when empty. |
| Text | `text` | Text | Yes |  | Main | Text to look for. |
| Search by | `search_by` | Text | No | `rows` | Main | Order of the results: rows goes across each row first, columns goes down each column first. One of: `rows`, `columns` |
| Match case | `match_case` | Yes/No | No | No | Main | Match upper/lower case exactly. |
| Whole cell | `whole_cell` | Yes/No | No | No | Main | Only match when the whole cell content equals the text. |
| Look in | `look_in` | Text | No | `values` | More options | values searches the calculated results; formulas searches the formula text of formula cells and the value of the others. One of: `values`, `formulas` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cells` | List | Addresses such as B7. |
| `first_cell` | Text | Address of the first match; empty when nothing matched. |
| `count` | Number | Number of matching cells. |

*Time limit 60 s; safe to repeat.*

### Find next empty cell

`ooa.excel/find_next_empty_cell` (action)

Starting from a cell, walk along its column or row in a direction and return the first empty cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Direction | `direction` | Text | No | `down` | Main | down or up searches the column, right or left searches the row. One of: `down`, `right`, `up`, `left` |
| Start cell | `start_cell` | Text | No |  | Main; choice Start from = A specific cell | Where to start; the cell chosen by go_to_cell, or A1, when empty. |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cell` | Text | Address of the first empty cell, e.g. A12. |
| `row` | Number | 1-based row number of that cell. |
| `column` | Number | 1-based column number of that cell. |

*Time limit 60 s; safe to repeat.*

### Get all cells

`ooa.excel/get_all_cells` (action)

Read the used area of a sheet as a table; the first row can be the column names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main; choice Worksheet = A named sheet | Sheet name; active sheet when empty. |
| First row is the header | `header` | Yes/No | No |  | Main | First row holds column names; the workbook's open setting (true by default) when empty. |
| Read as | `read_as` | Text | No | `value` | Main | value returns numbers, dates and booleans as such; text returns what Excel displays, with number formats applied. One of: `value`, `text` |
| Include hidden | `include_hidden` | Yes/No | No | Yes | More options | Include hidden rows and columns; false returns only what is visible, as after a filter in Excel. |
| Keep blank rows | `keep_blank_rows` | Yes/No | No | No | More options | Keep empty rows inside the data as rows of empty values instead of dropping them. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Sheet content. |
| `row_count` | Number | Number of data rows. |

*Time limit 60 s; safe to repeat.*

### Get cell

`ooa.excel/get_cell` (action)

Read one cell, or the active cell; formulas return their calculated value, or the text as Excel displays it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Cell | `cell` | Text | No |  | Main; choice Cell = A specific cell | Cell address; the active cell (set by go_to_cell) when empty. Example: `B2` |
| Read as | `read_as` | Text | No | `value` | Main | value returns numbers, dates and booleans as such; text returns what Excel displays, with number formats applied. One of: `value`, `text` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Cell value. |

*Time limit 60 s; safe to repeat.*

### Get range

`ooa.excel/get_range` (action)

Read a rectangular range such as A1:D20 as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | Yes |  | Main | Range address. Example: `A1:D20` |
| First row is the header | `header` | Yes/No | No |  | Main | First row holds column names; the workbook's open setting (true by default) when empty. |
| Read as | `read_as` | Text | No | `value` | Main | value returns numbers, dates and booleans as such; text returns what Excel displays, with number formats applied. One of: `value`, `text` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Range content. |

*Time limit 60 s; safe to repeat.*

### Get table range

`ooa.excel/get_table_range` (action)

Return the address of the smallest rectangle holding all data on the sheet (including pivot tables), or of a named Excel table or pivot table, e.g. A1:D20.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Text | No |  | Main | Excel table or pivot table name, e.g. Table1 or PivotTable1; the sheet's used area when empty. |
| Include header | `include_header` | Yes/No | No | Yes | Main | For an Excel table, include its header row in the range. |
| Include pivot tables | `include_pivot_tables` | Yes/No | No | Yes | Main | Count the whole area of the sheet's pivot tables (with their filter fields) as data, even cells Excel has not filled in yet. |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `range` | Text | Used range address; empty when the sheet is empty. |
| `rows` | Number | Number of rows in the range. |
| `columns` | Number | Number of columns in the range. |

*Time limit 60 s; safe to repeat.*

### Go to cell

`ooa.excel/go_to_cell` (action)

Make a cell the active cell, or move the active cell one step or to the start or end of its row or column, and remember it for later steps.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Cell | `cell` | Text | No |  | Main; choice Go to = A specific cell | Cell address; the active cell when empty. Example: `B7` |
| Sheet | `sheet` | Text | No |  | Main; choice Go to = A specific cell | Sheet name; active sheet when empty. |
| Move | `move` | Text | No | `none` | Main; choice Go to = A cell next to the active cell | Move from the cell: one cell left, right, up or down, or to the first or last used cell of its row or column; none stays on it. One of: `none`, `left`, `right`, `up`, `down`, `row_start`, `row_end`, `column_start`, `column_end` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cell` | Text | Address of the cell that is now active. |

*Time limit 60 s.*

### Go to next empty cell

`ooa.excel/go_to_next_empty_cell` (action)

Starting from a cell, walk along its column or row to the first empty cell and make that cell the active cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Start cell | `start_cell` | Text | No |  | Main; choice Start from = A specific cell | Where to start; the active cell when empty. |
| Direction | `direction` | Text | No | `down` | Main | down or up searches the column, right or left searches the row. One of: `down`, `right`, `up`, `left` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cell` | Text | Address of the cell that is now active. |

*Time limit 60 s.*

### Read column

`ooa.excel/read_column` (action)

Read the values of one column, from a start row down to an end row or the last used row.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | Column letter, 1-based number or header name (looked up in header_row). Example: `B` |
| Start row | `start_row` | Number | No | `1` | Main | 1-based row to start from, e.g. 2 to skip a header. Range: 1 to  |
| End row | `end_row` | Number | No |  | Main | 1-based last row to read; the last used row when empty. Range: 1 to  |
| Read as | `read_as` | Text | No | `value` | Main | value returns numbers, dates and booleans as such; text returns what Excel displays, with number formats applied. One of: `value`, `text` |
| Header row | `header_row` | Number | No |  | More options | Row with the column names. A column given by name is looked up here (row 1 when empty), and reading starts below this row. Range: 1 to  |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Cell values from top to bottom; empty cells are null. |

*Time limit 60 s; safe to repeat.*

### Read cell formula

`ooa.excel/read_formula` (action)

Return the formula a cell (or the active cell) holds, or an empty text when it holds a plain value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Cell | `cell` | Text | No |  | Main; choice Cell = A specific cell | Cell address; the active cell (set by go_to_cell) when empty. Example: `C11` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `formula` | Text | Formula starting with =, or empty. |
| `has_formula` | Yes/No | True when the cell holds a formula. |

*Time limit 60 s; safe to repeat.*

### Read row

`ooa.excel/read_row` (action)

Read the values of one row, from a start column to an end column or the last used cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Row | `row` | Number | Yes |  | Main | 1-based row number. Range: 1 to  |
| Start column | `start_column` | Any | No | `A` | Main | Column letter or 1-based number to start from. |
| End column | `end_column` | Any | No |  | Main | Column letter or 1-based number of the last column to read; the last used cell when empty. |
| Read as | `read_as` | Text | No | `value` | Main | value returns numbers, dates and booleans as such; text returns what Excel displays, with number formats applied. One of: `value`, `text` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Cell values from left to right; empty cells are null. |

*Time limit 60 s; safe to repeat.*

### Remove duplicate rows

`ooa.excel/remove_duplicates` (action)

Remove the rows of a range (or of the used area) that repeat an earlier row, comparing all columns or only the chosen ones, like Excel's Remove Duplicates; the first of each set is kept and the rows below move up inside the range.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; active sheet when empty. |
| Range | `range` | Text | No |  | Main | Range to check; the used area when empty. Example: `A1:F200` |
| First row is the header | `header` | Yes/No | No |  | Main | The first row is a header and is kept as it is; the workbook's open setting (true by default) when empty. |
| Columns | `columns` | List | No |  | Main | Columns that make a row a duplicate: header names, letters or 1-based sheet column numbers; all columns of the range when empty. Example: `["Invoice", "Vendor"]` |
| Match case | `match_case` | Yes/No | No | No | More options | Treat "ACME" and "acme" as different; Excel treats them as the same. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of rows removed. |
| `remaining` | Number | Number of rows left in the range, the header row not counted. |

*Time limit 60 s.*

### Replace

`ooa.excel/replace` (action)

Replace text in the cells of a sheet or range, optionally matching case or the whole cell, inside formulas, or only in the first matching cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | No |  | Main | Limit to this range; whole sheet when empty. |
| Find | `find` | Text | Yes |  | Main | Text to look for. |
| Search by | `search_by` | Text | No | `rows` | Main | Search order: rows goes across each row first, columns goes down each column first; matters with first_only. One of: `rows`, `columns` |
| Match case | `match_case` | Yes/No | No | No | Main | Match upper/lower case exactly. |
| Whole cell | `whole_cell` | Yes/No | No | No | Main | Only replace when the whole cell equals the text. |
| Replace with | `replace_with` | Text | No | (empty) | Main | Replacement text. |
| First only | `first_only` | Yes/No | No | No | More options | Change only the first matching cell in search order. |
| Look in | `look_in` | Text | No | `values` | More options | values changes text cells; formulas also changes the formula text of formula cells, e.g. a sheet name inside references. One of: `values`, `formulas` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of cells changed. |
| `cells` | List | Addresses of the changed cells, in search order. |

*Time limit 60 s.*

### Select range

`ooa.excel/select_range` (action)

Remember a range as the session's current selection and make its top-left cell active.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | Yes |  | Main | Range address. Example: `A1:D20` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Set cell

`ooa.excel/set_cell` (action)

Write a value to one cell, or to the active cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Cell | `cell` | Text | No |  | Main; choice Cell = A specific cell | Cell address; the active cell (set by go_to_cell) when empty. Example: `B2` |
| Value | `value` | Any | No |  | Main | Value to write; empty clears the cell. |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Set formula

`ooa.excel/set_formula` (action)

Put a formula in a cell, or in the active cell, e.g. SUM(C2:C10).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Cell | `cell` | Text | No |  | Main; choice Cell = A specific cell | Cell address; the active cell (set by go_to_cell) when empty. Example: `C11` |
| Formula | `formula` | Text | Yes |  | Main | Formula, with or without a leading =. Example: `SUM(C2:C10)` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Sort

`ooa.excel/sort_range` (action)

Sort the rows of a range (or of the used area) by one column and an optional second column, ascending or descending, keeping an optional header row in place.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; active sheet when empty. |
| Range | `range` | Text | No |  | Main | Range to sort; the used area when empty. |
| First row is the header | `header` | Yes/No | No |  | Main | The first row is a header and stays at the top; the workbook's open setting (true by default) when empty. |
| Column | `column` | Any | Yes |  | Main | Sort column: letter, 1-based sheet column number, or header name. Example: `C` |
| Descending | `descending` | Yes/No | No | No | Main | Sort from largest to smallest. |
| Then by | `then_by` | Any | No |  | More options | Second sort column used when the first column's values are equal: letter, number or header name. Example: `A` |
| Then descending | `then_descending` | Yes/No | No | No | More options | Sort the second column from largest to smallest. Shown when: `{"then_by": ["!", ""]}` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Text to columns

`ooa.excel/text_to_columns` (action)

Split the text of a column into several columns at a delimiter, like Excel's Text to Columns; the parts overwrite the cells to the right.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Column | `column` | Any | Yes |  | Main | Column holding the text: letter or 1-based number. Example: `A` |
| Delimiter | `delimiter` | Text | No | `,` | Main | Text between the parts: a character such as , ; \| or the words tab or space. Parts inside double quotes are kept whole. Example: `;` |
| Start row | `start_row` | Number | No |  | Main; choice Rows = From a row | First row to split; the first used row when empty. Range: 1 to  |
| End row | `end_row` | Number | No |  | Main; choice Rows = From a row | Last row to split; the last used row when empty. Range: 1 to  |
| Destination | `destination` | Any | No |  | Main | Column to write the first part to; the column itself when empty. Example: `B` |
| Trim | `trim` | Yes/No | No | Yes | Main | Remove spaces around each part. |
| Convert numbers | `convert_numbers` | Yes/No | No | Yes | Main | Store parts that are plain numbers, such as 12.5, as numbers, as Excel does. |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rows` | Number | Number of cells split. |
| `columns` | Number | Most parts any cell was split into. |

*Time limit 60 s.*

### Write from Data Table

`ooa.excel/write_table` (action)

Write a table (with column names) starting at a cell.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Table | Yes |  | Main | Table to write. |
| Sheet | `sheet` | Text | No |  | Main; choice Worksheet = A named sheet | Sheet name; active sheet when empty. |
| Start cell | `start_cell` | Text | No | `A1` | Main | Top-left cell. |
| Include header | `include_header` | Yes/No | No | Yes | Main | Write column names first. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

## Pivot tables and charts

### Create chart

`ooa.excel/create_chart` (action)

Add a column, bar, line or pie chart of a range: the first column gives the categories, the header row the series names and each further column one series.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet with the data; active sheet when empty. |
| Range | `range` | Text | Yes |  | Main | Data with a header row: categories in the first column, one series per further column. Example: `A1:C13` |
| Chart type | `chart_type` | Text | No | `column` | Main | column (vertical bars), bar (horizontal bars), line, or pie (the first series only). One of: `column`, `bar`, `line`, `pie` |
| Title | `title` | Text | No |  | Main | Chart title; none when empty. Example: `Sales by month` |
| Destination sheet | `destination_sheet` | Text | No |  | Main | Sheet to put the chart on; the data sheet when empty; created when it does not exist. |
| Position | `position` | Text | No |  | Main | Top-left cell of the chart; two columns right of the data when empty. |
| Width | `width` | Number | No | `8` | Main | Width in columns. Range: 1 to 100 |
| Height | `height` | Number | No | `15` | Main | Height in rows. Range: 1 to 500 |
| Legend | `legend` | Text | No | `right` | More options | Where the legend goes. One of: `right`, `bottom`, `top`, `left`, `none` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `range` | Text | Cells the chart covers, e.g. H2:O17. |

*Time limit 60 s.*

### Create pivot table

`ooa.excel/create_pivot_table` (action)

Create a pivot table from a range with a header row: fields as rows, columns, values (Sum, Count, Average, Max or Min) and filters. Excel fills in the figures when the workbook is opened.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet with the source data; active sheet when empty. |
| Range | `range` | Text | No |  | Main | Source range including its header row; the used area of the sheet when empty. Example: `A1:F200` |
| Destination sheet | `destination_sheet` | Text | No |  | Main | Sheet to put the pivot table on; created when it does not exist; a new sheet named Pivot when empty. Example: `Pivot` |
| Destination | `destination` | Text | No | `A3` | Main | Top-left cell of the pivot table. |
| Name | `name` | Text | No |  | Main | Pivot table name; PivotTable1, PivotTable2 … when empty. Example: `SalesByRegion` |
| Rows | `rows` | List | No |  | Main | Fields shown down the side (header names). Example: `["Region"]` |
| Columns | `columns` | List | No |  | Main | Fields shown across the top. Example: `["Quarter"]` |
| Values | `values` | List | Yes |  | Main | Fields to summarise, each as Field or Field:function, function sum (the default), count, average, max or min. Example: `["Amount", "Amount:count"]` |
| Filters | `filters` | List | No |  | Main | Fields offered as report filters above the table. Example: `["Year"]` |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Name of the pivot table. |
| `sheet` | Text | Sheet the pivot table is on. |

*Time limit 60 s.*

### Create Excel table

`ooa.excel/create_table` (action)

Turn a range with a header row into a named Excel table with a table style, like Ctrl+T in Excel.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; active sheet when empty. |
| Range | `range` | Text | No |  | Main | Range including its header row; the used area when empty. Example: `A1:F200` |
| Name | `name` | Text | No |  | Main | Table name: letters, digits, _ and ., starting with a letter; Table1, Table2 … when empty. Example: `Invoices` |
| Style | `style` | Text | No | `TableStyleMedium2` | Main | Excel table style: TableStyleLight1–21, TableStyleMedium1–28 or TableStyleDark1–11. Example: `TableStyleLight9` |
| Banded rows | `banded_rows` | Yes/No | No | Yes | Main | Shade every other row. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `name` | Text | Name of the new table. |

*Time limit 60 s.*

## Tables

### Delete table column

`ooa.excel/delete_table_column` (action)

Remove a column from an Excel table and move the table cells to its right back by one.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Text | Yes |  | Main | Excel table name, e.g. Table1. |
| Column name | `column_name` | Text | No |  | Main; choice Column = By header | Header of the column to remove. |
| Column position | `column_position` | Number | No |  | Main; choice Column = By position | Position of the column to remove: 1 is the table's first column. Range: 1 to  |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Insert table column

`ooa.excel/insert_table_column` (action)

Add a named column to an Excel table, moving the table cells to its right over by one.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Table | `table` | Text | Yes |  | Main | Excel table name, e.g. Table1. |
| Column name | `column_name` | Text | Yes |  | Main | Header for the new column. |
| Position | `position` | Number | No |  | Main | 1-based position inside the table; at the end when empty. Range: 1 to  |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

## Formatting

### Format cells

`ooa.excel/format_cells` (action)

Set the number format, font, fill, alignment, wrapping and borders of a range or the selection; only the options given change, the rest of each cell's format stays.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | No |  | Main; choice Cells = A specific range | Cell or range to format; when empty, the range chosen by select_range or the cell chosen by go_to_cell. Example: `B2:D20` |
| Number format | `number_format` | Text | No |  | Main | Excel number format, e.g. #,##0.00, 0%, dd/mm/yyyy, @ for text or General. Example: `#,##0.00` |
| Bold | `bold` | Yes/No | No |  | Main | Bold on or off; unchanged when empty. |
| Italic | `italic` | Yes/No | No |  | Main | Italic on or off; unchanged when empty. |
| Underline | `underline` | Yes/No | No |  | Main | Single underline on or off; unchanged when empty. |
| Font name | `font_name` | Text | No |  | Main | Font name; unchanged when empty. Example: `Calibri` |
| Font size | `font_size` | Number | No |  | Main | Font size in points; unchanged when empty. Example: `12`; Range: 1 to 409 |
| Font color | `font_color` | Text | No |  | Main | Text colour: hex (#C00000), red,green,blue numbers or a name such as dark red; unchanged when empty. .xlsx only. Example: `#C00000` |
| Fill color | `fill_color` | Text | No |  | Main | Solid background colour, as for font_color; none removes the fill; unchanged when empty. .xlsx only. Example: `light yellow` |
| Horizontal | `horizontal` | Text | No |  | Main | Horizontal alignment; unchanged when empty. One of: `general`, `left`, `center`, `right`, `justify` |
| Vertical | `vertical` | Text | No |  | Main | Vertical alignment; unchanged when empty. One of: `top`, `center`, `bottom` |
| Wrap text | `wrap_text` | Yes/No | No |  | Main | Wrap long text onto several lines; unchanged when empty. |
| Borders | `borders` | Text | No |  | Main | all draws thin lines around every cell; outline only around the range; none removes the lines; unchanged when empty. One of: `all`, `outline`, `none` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of cells formatted. |

*Time limit 60 s.*

### Get cell colour

`ooa.excel/get_cell_color` (action)

Return a cell's fill colour or font colour as a hex value such as #FFC000, or as red, green and blue numbers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Target | `target` | Text | No | `fill` | Main | fill for the background, font for the text colour. One of: `fill`, `font` |
| Cell | `cell` | Text | No |  | Main; choice Cell = A specific cell | Cell address; the active cell when empty. Example: `B2` |
| Format | `format` | Text | No | `hex` | More options | hex gives #RRGGBB; rgb gives three numbers separated by commas, e.g. 255,192,0. One of: `hex`, `rgb` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `color` | Text | Colour as hex or as R,G,B; empty when the cell has no colour set. |

*Time limit 60 s; safe to repeat.*

### Set cell colour

`ooa.excel/set_cell_color` (action)

Give a cell or range a solid fill colour or a font colour.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Range | `range` | Text | Yes |  | Main | Cell or range to colour. Example: `A1:D1` |
| Target | `target` | Text | No | `fill` | Main | fill for the background, font for the text colour. One of: `fill`, `font` |
| Color | `color` | Text | Yes |  | Main | Hex colour (#FFC000), red,green,blue numbers (255,192,0) or a colour name such as yellow or light blue. Example: `#FFC000` |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

## Sensitivity

### Get sensitivity label

`ooa.excel/get_sensitivity_label` (action)

Read the sensitivity label stored in the workbook's MSIP_Label custom document properties.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `excel` | Main | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `label_id` | Text | Label GUID; empty when no label is set. |
| `name` | Text | Label name as stored in the file. |
| `site_id` | Text | Tenant (site) id the label belongs to. |
| `method` | Text | How it was applied: Standard or Privileged. |
| `has_label` | Yes/No | True when an enabled label was found. |

*Time limit 60 s; safe to repeat.*

### Set sensitivity label

`ooa.excel/set_sensitivity_label` (action)

Write a sensitivity label into the workbook's MSIP_Label custom properties, replacing any earlier label; this marks the file but does not encrypt it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sensitivity label ID | `label_id` | Text | Yes |  | Main | Label GUID from your organisation's compliance centre. |
| Tenant (site) ID | `site_id` | Text | Yes |  | Main | Tenant (site) GUID the label belongs to. |
| Method | `method` | Text | No | `Standard` | Main | Standard for a default label, Privileged for one chosen by a person. One of: `Standard`, `Privileged` |
| Name | `name` | Text | No |  | More options | Label display name, e.g. Confidential. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

## Protection

### Protect sheet

`ooa.excel/protect_sheet` (action)

Password-protect a worksheet so its locked cells cannot be edited in Excel, choosing which operations people may still perform.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Password | `password` | Credential (vault) | Yes |  | Main | Password needed to unprotect the sheet; a credential from the vault or text. |
| Allow format cells | `allow_format_cells` | Yes/No | No | No | Main | People may format cells. |
| Allow format columns | `allow_format_columns` | Yes/No | No | No | Main | People may change column widths and hide columns. |
| Allow format rows | `allow_format_rows` | Yes/No | No | No | Main | People may change row heights and hide rows. |
| Allow insert columns | `allow_insert_columns` | Yes/No | No | No | Main | People may insert columns. |
| Allow insert rows | `allow_insert_rows` | Yes/No | No | No | Main | People may insert rows. |
| Allow delete columns | `allow_delete_columns` | Yes/No | No | No | Main | People may delete columns. |
| Allow delete rows | `allow_delete_rows` | Yes/No | No | No | Main | People may delete rows. |
| Allow sort | `allow_sort` | Yes/No | No | No | Main | People may sort. |
| Allow filter | `allow_filter` | Yes/No | No | No | Main | People may use AutoFilter. |
| Allow select locked | `allow_select_locked` | Yes/No | No | Yes | More options | People may select locked cells. |
| Allow select unlocked | `allow_select_unlocked` | Yes/No | No | Yes | More options | People may select unlocked cells. |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Protect workbook

`ooa.excel/protect_workbook` (action)

Require a password to open the file, lock the workbook structure and window layout with a password, or both.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Open password | `open_password` | Credential (vault) | No |  | Main; tick Require a password to open the file | Encrypt the file with this password when it is saved, so it cannot be opened without it. |
| Password | `password` | Credential (vault) | No |  | Main; tick Lock the structure with a password | Password needed to unlock the structure; a credential from the vault or text. |
| Lock structure | `lock_structure` | Yes/No | No | Yes | Main; tick Lock the structure with a password | Stop sheets being added, removed, moved, renamed or unhidden. |
| Lock windows | `lock_windows` | Yes/No | No | No | Main; tick Lock the structure with a password | Also fix the size and position of the workbook windows. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

### Unprotect sheet

`ooa.excel/unprotect_sheet` (action)

Remove protection from a worksheet; for .xlsx files the password is checked first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Password | `password` | Credential (vault) | No |  | Main | Sheet password; a credential from the vault or text. |
| Sheet | `sheet` | Text | No |  | More options | Sheet name; active sheet when empty. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `was_protected` | Yes/No | False when the sheet was not protected (nothing to do). |

*Time limit 60 s.*

### Unprotect workbook

`ooa.excel/unprotect_workbook` (action)

Stop asking for a password to open the file, unlock a protected workbook structure and windows (the password must match), or both.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Remove open password | `remove_open_password` | Yes/No | No | No | Main | Save the file without encryption from now on, so it opens without a password. |
| Password | `password` | Credential (vault) | No |  | Main; tick Unlock the structure | Structure password; a credential from the vault or text. |
| Session | `session` | Text | No | `excel` | More options | Workbook session. |

*Time limit 60 s.*

## Loop types

Used as the kind of loop in a Loop step.

### Each row in a worksheet

`ooa.excel/rows` (loop type)

Yield each row of a sheet (or of a range) of an open workbook as a record keyed by the header names (Column1, Column2 ... without a header).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Sheet | `sheet` | Text | No |  | Main | Sheet name; active sheet when empty. |
| Range | `range` | Text | No |  | Main | Only this range, e.g. A1:D50; the used area when empty. |
| First row is the header | `header` | Yes/No | No |  | Main | First row holds column names; the workbook's open setting (true by default) when empty. |
| Session | `session` | Text | No | `excel` | Main | Workbook session. |
| Read as | `read_as` | Text | No | `value` | Main | value returns numbers, dates and booleans as such; text returns what Excel displays, with number formats applied. One of: `value`, `text` |
| Include hidden | `include_hidden` | Yes/No | No | Yes | Main | Include hidden rows; false skips rows hidden by a filter. Shown when: `{"range": [""]}` |
