# Command panels: structure compared with the reference product

Checked on 2026-10-25. The reference product's action panels (fields, order, choices that reveal fields) were read from its editor on the user's account, read-only, and set beside the Studio panel of each mapped command. This page keeps our own findings and proposals only; it quotes none of the reference product's wording. Decision: Studio follows the reference's structure and field order, with our own labels.

**Scope:** 794 mapped action pairs, 669 Studio commands. Generator: `tools/panel-compare.js` (layout data); the verdicts were reviewed pair by pair, except batch 4 (Excel, SNMP, Jira, mouse/keyboard and others), which was reviewed per command family.

| Result (per Studio command) | Commands |
| --- | --- |
| Same | 118 |
| Small change | 334 |
| Restructure | 217 |

| Issue found (across pairs) | Times | Meaning |
| --- | --- | --- |
| buried | 339 | a field shown up front in the reference sits under More options in ours |
| missing | 299 | no counterpart in ours (often a real capability gap) |
| grouping | 282 | the reference asks a choice first, or a tick reveals sub-fields; ours is flat |
| extra-visible | 206 | an OOA-only field takes space in the main area |
| order | 188 | the same fields in a different order |
| type | 77 | a different control, e.g. free text where a fixed list fits |

Studio's own panels (If, Loop, Try, Capture) were judged from the toolkit manifest, so their verdicts here understate what Studio already shows; see the Capture and Try/Catch mockups for those.

## ooa.microsoft365

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `add_attendees` | Restructure | buried, grouping | Order: Access token, Event id, Required attendees, Optional attendees; drop the Type field from the panel. |
| `check_permissions` | Restructure | buried, extra-visible, grouping | Order: Access token, Item type (file/folder), Item, Permission type (read/read-write), Email. |
| `copy_item` | Restructure | buried, grouping | Order: Access token, Item type (file/folder), Item, To folder, If exists; New name, To drive, Wait seconds under More options. |
| `create_event` | Restructure | buried, extra-visible, grouping, missing, order | Order: Access token, Start, End, All day, Attendees, Optional attendees, Subject, Location, Body, then Reminder tick revealing minutes and Recurrence tick revealing pattern/interval/start/end; OOA-only options stay under More options. |
| `delete_all_mail` | Restructure | buried, order, type | Order: Access token, Folder, Which emails (read/unread/both), Subject contains, From contains, Since, Before; rest under More options. |
| `delete_item` | Restructure | extra-visible, grouping | Order: Access token, Item type (file/folder), Item; Permanent under More options. |
| `excel_delete_range` | Restructure | buried, grouping, missing | Order: Access token, Remote path, Sheet, Range, Shift (left/up/entire row/entire column) visible. |
| `excel_delete_worksheet` | Restructure | grouping, missing | Order: Access token, Remote path, Delete sheet by (index/name) revealing the Sheet value. |
| `excel_find` | Restructure | buried, grouping, missing, order | Order: Access token, Remote path, Sheet, Range (from/till), Text, Match case, Whole cell; Search order (new) under More options. |
| `excel_find_next_empty_cell` | Restructure | buried, grouping, missing | Order: Access token, Remote path, Sheet, Direction (down/right), then Column or Row revealed by direction. |
| `excel_get_cell_format` | Restructure | grouping, missing | Needs a Format cell command: Access token, Remote path, Sheet, Cell/range, Font tick (name, size, bold, italic, underline, color), Alignment tick (vertical, horizontal), Wrap text, Merge tick. |
| `excel_insert_range` | Restructure | buried, grouping, missing | Needs a row/column mode: Access token, Remote path, Sheet, Operate on (row/column), Target (index or range). |
| `excel_list_tables` | Restructure | grouping | Order: Access token, Remote path, Specific sheet tick revealing Sheet by (index/name). |
| `excel_read_column` | Restructure | grouping, missing | Needs a distinct Get column name command: Access token, Remote path, Sheet, Cell address. |
| `excel_read_range` | Restructure | grouping, missing | Order: Access token, Remote path, Sheet, Range (blank for whole sheet), Has headers (new), Read as (value/displayed text) (new). |
| `excel_read_row` | Restructure | grouping, missing | Point to the used-range command: Access token, Remote path, Sheet. |
| `excel_rename_worksheet` | Restructure | grouping, missing | Order: Access token, Remote path, Sheet by (index/name) revealing the value, New name. |
| `excel_set_range` | Restructure | grouping, missing | Needs a Set color command: Access token, Remote path, Sheet, Range, Apply to (cell/text), Color. |
| `excel_show_worksheet` | Restructure | grouping, missing | Needs a Copy worksheet command: Access token, Target sheet by (index/name) with value, Source sheet by (index/name) with value. |
| `excel_used_range` | Restructure | grouping, missing | Needs a Get cell command: Access token, Remote path, Sheet, Cell. |
| `excel_write_data_table` | Restructure | buried, grouping, order | Order: Access token, Remote path, Table, Sheet scope (active/specific revealing index-or-name), Start cell; Include headers under More options. |
| `excel_write_range` | Restructure | grouping, missing | No equivalent needed; if added, a movement choice (specific cell / left / right / up / down / row or column start or end) revealing a cell name only for the specific option. |
| `forward_mail` | Restructure | buried, grouping, order | Order: Access token, Id, From, To, Cc, Bcc, Attachments, Comment with body format choice; Importance and Mailbox under More options. |
| `get_channel_by_name` | Restructure | grouping, missing | Order: Access token, Team by (name/ID) revealing value, Channel name. |
| `get_item_info` | Restructure | extra-visible, grouping | Order: Access token, Item type (file/folder), Item; Drive and Endpoint under More options. |
| `list_channels` | Restructure | grouping, missing | Needs a Get channel command: Access token, Team by (name/ID), Channel by (name/ID). |
| `list_teams` | Restructure | grouping, missing | Needs a Get team command: Access token, Team by (name/ID) revealing value. |
| `move_all_mail` | Restructure | buried, order, type | Order: Access token, To folder, Which emails (read/unread/both), Folder, Subject contains, From contains, Since, Before. |
| `move_item` | Restructure | buried, grouping | Order: Access token, Item type (file/folder), Item, To folder, If exists; New name under More options. |
| `remove_attendees` | Restructure | missing, order | Needs a distinct Clear event details command: Access token, Event id, ticks for Subject, Location, Body, Reminder, Recurrence, All day. |
| `rename_item` | Restructure | grouping, missing | Order: Access token, Item type (file/folder), Item, New name, Overwrite (new). |
| `reply_all_mail` | Restructure | buried, grouping | Order: Access token, Id, From, Cc, Bcc, Exclude sender, Attachments, Comment with body format choice; Importance and Mailbox under More options. |
| `reply_mail` | Restructure | buried, extra-visible, grouping | Order: Access token, Id, From, Cc, Bcc, Attachments, Comment with body format choice; Importance, Mailbox under More options. |
| `respond_to_event` | Restructure | grouping, order | Order: Access token, Event id, Response, then a With message tick revealing Comment; Send response, Mailbox, Endpoint under More options. |
| `save_all_mail_attachments` | Restructure | buried, extra-visible, order, type | Order: Access token, Which emails (read/unread/both), Folder, Subject contains, From contains, Since, Before, Target folder, Overwrite; Name pattern, Mark read, Max results under More options. |
| `save_mail_attachments` | Restructure | buried, extra-visible, missing | Needs a Save email command: Access token, Id, Target folder, File name, Format (EML/MSG), Overwrite. |
| `send_chat_message` | Restructure | grouping, missing | Order: Access token, Send to (email/chat ID) revealing value, Message. |
| `send_mail` | Restructure | buried, grouping, missing, order | Order: Access token, From, To, Cc, Bcc, Subject, Attachments, Body with format choice; Importance, Reply to, Save to sent items, Read receipt, Mailbox under More options. |
| `share_item` | Restructure | grouping, missing | Needs a Find items command: Access token, Folder, Find (all/specific) revealing the name. |
| `teams_message` | Restructure | grouping, missing | Order: Access token, Team by (name/ID), Channel by (name/ID), Message; HTML, Subject, Importance, Reply to under More options. |
| `update_event` | Restructure | buried, grouping, missing, order | Order: Access token, Event id, Start, End, All day, Subject, Body, Location, Update reminder tick revealing Reminder minutes, Update recurrence tick revealing pattern fields (new); other options under More options. |
| `upload_file` | Restructure | buried, missing | Needs a distinct Get file reference command: Access token, Remote path. |
| `add_event_attachment` | Small change | order | Order: Access token, Event id, File; Mailbox and Endpoint under More options. |
| `create_folder` | Small change | order | Order: Access token, Parent folder, Name; If exists under More options. |
| `delete_mail` | Small change | extra-visible | Order: Access token, Id; Permanent under More options. |
| `download_file` | Small change | buried | Order: Access token, Remote path, Local path, Overwrite. |
| `excel_autofit_columns` | Small change | order | Keep Access token, Remote path, Sheet, Range. |
| `excel_autofit_rows` | Small change | order | Keep Access token, Remote path, Sheet, Range. |
| `excel_create_table` | Small change | buried, order | Order: Access token, Remote path, Sheet, Name, Range, Has headers. |
| `excel_get_table_rows` | Small change | missing | Order: Access token, Remote path, Table, Row index (new, optional). |
| `excel_get_worksheets` | Small change | extra-visible, missing | Order: Access token, Remote path; Drive and Endpoint address under More options. |
| `export_to_pdf` | Small change | missing | Order: Access token, Item, Local path, Overwrite (new). |
| `find_meeting_times` | Small change | buried, missing, order | Order: Access token, Attendees, Duration minutes, From, To, Include outside working hours (new). |
| `get_team_by_name` | Small change | extra-visible | Order: Access token, Name; User under More options. |
| `mail_folder_exists` | Small change | extra-visible | Order: Access token, Name; Mailbox under More options. |
| `restore_version` | Small change | extra-visible | Order: Access token, Item; Version id under More options (blank means last version). |
| `cancel_event` | Same |  | Keep Access token, Event id, Message. |
| `excel_add_worksheet` | Same |  | Keep Access token, Remote path, Name. |
| `excel_create_workbook` | Same | extra-visible | Keep Access token, Remote path; Sheet names can move to More options. |
| `excel_delete_table` | Same |  | Keep Access token, Remote path, Table. |
| `excel_hide_worksheet` | Same |  | Keep Access token, Remote path, Sheet; Very hidden stays under More options. |
| `excel_rename_table` | Same |  | Keep Access token, Remote path, Table, New name. |
| `get_user` | Same | extra-visible | Keep Access token, User; Endpoint address under More options. |
| `mark_mail` | Same |  | Keep Access token, Id, Status. |
| `move_mail` | Same |  | Keep Access token, Id, To folder. |

## ooa.excel

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `delete_sheet` | Restructure | grouping | Add a By choice (name or index) first, then show only Name (name) or Index (index). |
| `filter` | Restructure | buried, grouping, missing | Order: Target choice (table or sheet) with Sheet (sheet) shown for worksheet, First row is the header (header), Column (column), Operator (operator), Value (value), then Value to (value_to) when needed. |
| `get_range` | Restructure | grouping | Add a range-kind choice before Range (range), First row is the header (header). |
| `go_to_cell` | Restructure | grouping | Add a choice of specific cell or active cell; show Cell (cell) and Sheet (sheet) for specific, or Move (move) for active. |
| `hide_rows` | Restructure | grouping, missing | Add a rows-or-columns choice, then a range; keep Row (row) and Count (count) for rows. |
| `insert_rows` | Restructure | grouping, missing | Add a rows-or-columns choice and an insert-or-delete choice before Row (row), Count (count). |
| `open` | Restructure | buried, extra-visible, grouping, missing | Order: source choice with Path (path), First row is the header (header), Sheet (sheet) as tick revealing name, Read only (read_only), Password (password) as tick revealing fields; move Create (create) to More options. |
| `protect_sheet` | Restructure | buried, extra-visible, grouping, missing, order | Order: Password (password), Sheet (sheet), then a permissions group with Allow format cells (allow_format_cells), columns, rows, Allow insert columns (allow_insert_columns), Allow insert rows (allow_insert_rows), delete columns, delete rows, Allow sort (allow_sort), Allow filter (allow_filter) all visible; keep select options under More options. |
| `protect_workbook` | Restructure | extra-visible, grouping | Two groups: tick for open-protection revealing Password (password), tick for Lock structure (lock_structure) revealing its password; move Lock windows (lock_windows) to More options. |
| `remove_blank_rows` | Restructure | missing | Add Begin choice and End choice (sheet edge or specific row) before Sheet (sheet). |
| `rename_sheet` | Restructure | grouping, order | Order: By choice, then Sheet (sheet) or Index (index), then New name (new_name). |
| `replace` | Restructure | buried, grouping, order | Order: Range (range), Find (find), Search by (search_by), Match case (match_case), Whole cell (whole_cell), then Replace with (replace_with); keep First only (first_only) under More options. |
| `select_range` | Restructure | grouping | Add a Select-kind choice and sub-choice before Range (range), Sheet (sheet). |
| `sort_range` | Restructure | buried, grouping, missing | Order: Target choice (table or sheet) with Sheet (sheet) and Range (range), First row is the header (header), Column (column), Descending (descending); keep Then by (then_by) under More options. |
| `switch_sheet` | Restructure | grouping | Add a By choice (name or index) first, then show only Sheet (sheet) or Index (index). |
| `unhide_rows` | Restructure | grouping, missing | Add a rows-or-columns choice, then a range; keep Row (row) and Count (count) for rows. |
| `add_sheet` | Small change | extra-visible, grouping | Add a choice of name or position then show Name (name) or Position (position); move Replace (replace) to More options. |
| `append_workbook` | Small change | missing | Path (path), then a Password required tick revealing a password field. |
| `append_worksheet` | Small change | grouping, missing | Order: Path (path), password tick with password, then Source sheet (source_sheet) with name/index choice, then New name (new_name). |
| `break_links` | Small change | grouping | Add a choice of All or Specific, revealing Link (link) only for Specific. |
| `cell_address` | Small change | grouping, order | Order: Column (column), Row (row). |
| `column_name` | Small change | grouping, type | Add an active/specific cell choice before Column (column), or accept a cell address. |
| `create_workbook` | Small change | missing, order | Order: Path (path), Overwrite (overwrite), Sheet (sheet); add password options after. |
| `delete_cells` | Small change | grouping, missing | Order: target choice (active or specific) revealing Range (range), then Shift (shift), then Sheet (sheet). |
| `delete_table_column` | Small change | grouping, missing | Order: Table (table), choice by name or position revealing Column name (column_name) or a position field. |
| `find` | Small change | buried, order | Order: Range (range), Text (text), Search by (search_by), Match case (match_case), Whole cell (whole_cell); keep Look in (look_in) under More options. |
| `find_next_empty_cell` | Small change | grouping, order | Order: Direction (direction), then Start cell (start_cell) shown after a start choice, then Sheet (sheet). |
| `get_cell` | Small change | extra-visible | Cell (cell), Sheet (sheet); move Read as (read_as) to More options for the basic flavour. |
| `get_cell_color` | Small change | order | Order: Target (target), Cell (cell), Sheet (sheet). |
| `get_current_sheet_name` | Small change | buried | Show Session (session) in the main area. |
| `get_links` | Small change | buried | Show Session (session) in the main area. |
| `get_sensitivity_label` | Small change | buried | Show Session (session) in the main area. |
| `get_sheet_names` | Small change | extra-visible | Move Include hidden (include_hidden) to More options. |
| `get_table_range` | Small change | missing, order | Order: Table (table), Include header (include_header), then Sheet (sheet). |
| `read_column` | Small change | buried, grouping | Order: start choice, full-range tick revealing the start/end fields, Read as (read_as); keep the line identifier first. |
| `read_formula` | Small change | grouping | Choice of active or specific, revealing Cell (cell); then Sheet (sheet). |
| `read_row` | Small change | buried, grouping | Order: start choice, full-range tick revealing the start/end fields, Read as (read_as); keep the line identifier first. |
| `row_count` | Small change | grouping | Order: Sheet (sheet) with name/index choice, then Mode (mode). |
| `row_number` | Small change | grouping | Choice of active or specific, revealing Cell (cell) only for specific. |
| `save` | Small change | extra-visible | Move Path (path) and Password (password) to More options. |
| `save_as` | Small change | buried, extra-visible | Order: Path (path), Overwrite (overwrite), Format (format), Sheet (sheet) when CSV. |
| `set_formula` | Small change | grouping | Choice of active or specific, revealing Cell (cell), then Formula (formula). |
| `set_sensitivity_label` | Small change | extra-visible, missing | Main area: Label id (label_id), Site id (site_id), Continue on error; move Name (name) to More options. |
| `unhide_all_sheets` | Small change | buried | Show Session (session) in the main area. |
| `unprotect_workbook` | Small change | grouping | Two ticks for open-protection and structure; password field revealed for structure; Password (password). |
| `write_table` | Small change | buried, missing, order | Order: Table (table), Sheet (sheet), Start cell (start_cell), Include header (include_header). |
| `change_link` | Same |  | No change: Old link (old_link), New link (new_link). |
| `close` | Same | missing | Save (save) first; optionally add the read-only error tick. |
| `get_all_cells` | Same |  | No change: Sheet (sheet), First row is the header (header), Read as (read_as). |
| `go_to_next_empty_cell` | Same |  | No change: Start cell (start_cell), Direction (direction), Sheet (sheet). |
| `hide_sheet` | Same |  | No change: Sheet (sheet), with Index (index) as alternative. |
| `insert_table_column` | Same |  | No change: Table (table), Column name (column_name), Position (position). |
| `set_cell` | Same |  | No change: Cell (cell), Value (value), Sheet (sheet). |
| `sheet_count` | Same |  | No change: Include hidden (include_hidden). |
| `unhide_sheet` | Same |  | No change: Sheet (sheet), with Index (index) as alternative. |
| `unprotect_sheet` | Same |  | No change: Password (password), Sheet (sheet). |

## ooa.google

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `calendar_create_event` | Restructure | buried, grouping, order | Title, location, attendees, start date, end date, all-day vs specific times choice (revealing times), time-zone choice, recurring tick, visibility, description. |
| `drive_check_permission` | Restructure | buried, grouping | Item type/identification choice (file, folder, ID) revealing one field, then permission type up front; email as needed. |
| `drive_copy_file` | Restructure | buried, grouping, missing | Source choice (name or ID), destination choice (folder location or ID), then rename tick revealing new name. |
| `drive_create_permission` | Restructure | buried, grouping, missing, order | Item identification, role, grantee type (user, group, domain, anyone) revealing email or domain; notify/message/expiry under More options. |
| `drive_find_items` | Restructure | buried, grouping, missing | Item type (file/folder), source folder choice (path or ID) revealing a field, match type (exact or contains), then name. |
| `gmail_delete_all` | Restructure | buried, extra-visible, order | Status, label, subject, sender, since, before up front; query/permanent/max under More options. |
| `gmail_move_all` | Restructure | buried, missing | Source labels, destination labels, keep tick, status, label, subject, sender, dates up front. |
| `gmail_save_all_attachments` | Restructure | buried, extra-visible, order | Status, label, subject, sender, since, before, then folder, overwrite. |
| `gmail_send` | Restructure | buried, grouping, order | To, cc, bcc, subject, attachments, body format choice (plain text/HTML) then body. |
| `sheets_delete_rows` | Restructure | grouping, missing | Choice of row or column operation, then by position or by active cell/range revealing the matching field. |
| `sheets_format_cells` | Restructure | buried, grouping, missing | Range, then grouped sections: font (name, size, bold, italic, underline, strikethrough, colour), alignment, wrap, merge, each revealed by a tick; background, number format, borders under More options. |
| `sheets_insert_rows` | Restructure | grouping, missing | Choice of row or column operation, then by position or range. |
| `calendar_delete_event` | Small change | extra-visible | Event id; move notify to More options. |
| `drive_copy_folder` | Small change | extra-visible, missing | Source choice, destination choice; name stays under More options. |
| `drive_create_folder` | Small change | order | Parent folder, then folder name. |
| `drive_delete_item` | Small change | extra-visible, missing | Identify file by name or ID; permanent delete stays under More options. |
| `drive_delete_permission` | Small change | missing | Mapping mismatch: the reference deletes a folder; ours is a permission removal. A folder delete counterpart would be item delete. |
| `drive_download_export` | Small change | missing, order | File, download location, overwrite and rename ticks; format as OOA extra after the location. |
| `drive_list` | Small change | missing | Mapping loose: the reference fetches info of one file by name or ID; ours lists files. |
| `drive_rename_item` | Small change | grouping, missing | Choice of file by name/location or by ID, then new name. |
| `drive_upload` | Small change | buried | Local file, destination folder, overwrite up front; name/description under More options. |
| `gmail_delete_message` | Small change | extra-visible | Message id; permanent stays but move to More options. |
| `gmail_forward_message` | Small change | buried, order | To, cc, bcc, attachments, body (with HTML tick); message id first as needed. |
| `gmail_move_message` | Small change | buried | Source labels, destination labels, keep-other tick. |
| `gmail_reply_all` | Small change | buried, order | Cc, bcc, exclude-sender, subject, attachments, body. |
| `gmail_reply_message` | Small change | buried, order | Cc, bcc, attachments, then body with HTML tick. |
| `gmail_save_attachments` | Small change | buried | Folder, overwrite up front. |
| `gmail_save_message_as_eml` | Small change | buried, missing | Folder, file name, overwrite up front. |
| `sheets_create_spreadsheet` | Small change | missing | Mapping mismatch: reference opens an existing spreadsheet by name, URL or ID; ours creates one. |
| `sheets_delete_range` | Small change | buried, missing | Range, then delete shift choice up front; add whole-row/column options. |
| `sheets_get_cell_color` | Small change | missing, type | Cell, then which colours to read (cell, text) and name vs code format. |
| `sheets_insert_range` | Small change | buried | Range, shift choice up front. |
| `sheets_read` | Small change | missing | Range, with an all-cells choice. |
| `sheets_row_count` | Small change | buried, missing, type | Sheet, then non-empty vs total rows choice up front. |
| `sheets_set_cell` | Small change | missing | Mapping loose: the reference moves the selection; ours sets a value. |
| `sheets_write_data_table` | Small change | buried | Sheet, table, start cell up front. |
| `drive_list_permissions` | Same |  | Keep: item id. |
| `drive_move_item` | Same | missing | Keep: source item and destination folder. |
| `gmail_mark_message` | Same |  | Keep: message id, status. |
| `sheets_autofit_columns` | Same |  | Keep: connection, spreadsheet, optional sheet. |
| `sheets_autofit_rows` | Same |  | Keep: connection, spreadsheet, optional sheet. |
| `sheets_delete_sheet` | Same | missing | Keep: sheet. |
| `sheets_hide_sheet` | Same |  | Keep: sheet. |
| `sheets_list_sheets` | Same |  | Keep: connection and spreadsheet first; exclude-hidden tick. |
| `sheets_read_column` | Same |  | Keep: sheet, column. |
| `sheets_read_formula` | Same |  | Keep: cell. |
| `sheets_read_row` | Same |  | Keep: sheet, row. |
| `sheets_rename_sheet` | Same | missing | Keep: sheet, new name. |
| `sheets_set_formula` | Same |  | Keep: cell, formula. |
| `sheets_show_sheet` | Same |  | Keep: sheet. |

## ooa.datatable

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `change_column_type` | Restructure | buried, grouping, type | Table, then column-by choice (name or index) revealing only one field, then new type; date format choice (prebuilt list or custom) revealed only for DateTime. |
| `delete_column` | Restructure | grouping | Table, then delete-by choice (name or index) revealing a single field. |
| `insert_column` | Restructure | buried, grouping, missing, type | Table, position choice (first, last, specific index), then source choice: another table's column (by name or index) or a new custom column (name, type, default). |
| `insert_row` | Restructure | grouping | Table, position choice (first, last, specific index with number revealed), then row values. |
| `join` | Restructure | buried, grouping, order | First table, its key column, second table, its key column, then join type up front. |
| `set_value` | Restructure | buried, grouping | Table, row, column-by choice (name or index), then value up front. |
| `sort` | Restructure | buried, grouping, type | Table, sort-by choice (name or index) with one field, then order choice (ascending/descending). |
| `write_to_file` | Restructure | buried, extra-visible, grouping, missing | Table, file path, create-missing-folders tick, append or overwrite choice, row separator and column separator choices (with custom reveal), encoding; format stays as OOA extra. |
| `column_count` | Small change | type | Table, then a choice between non-empty columns and all columns. |
| `delete_row` | Small change | extra-visible | Table, row index; move column filter to More options. |
| `find` | Small change | buried, extra-visible | Table, value, match-case tick up front; column and match mode under More options. |
| `row_count` | Small change | missing | Table, then a choice between non-empty rows and all rows. |
| `clear` | Same |  | Keep: table only. |
| `column_name` | Same |  | Keep: table, column index. |
| `merge` | Same | type | Keep: tables to merge; column mode may stay as an extra. |
| `remove_duplicates` | Same |  | Keep: table, optional columns. |

## ooa.active_directory

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `add_to_group` | Restructure | buried, grouping, order | Session first, then an identify-user-by choice (name or LDAP path) with its value, then Group (group_dn); More usernames after. |
| `create_user` | Restructure | buried, grouping, missing, order | Session, Username, User principal name, First name, Last name, Display name, Initials, Description, Enabled; then a Set password group revealing Password, Must change, and (when not forced) Password never expires; Container dn and Attributes under More. |
| `remove_from_group` | Restructure | buried, grouping, missing, order | Add a delete-group command with Session then Group; this remove-from-group panel is not comparable. |
| `rename_user` | Restructure | buried, extra-visible, grouping, missing | Session, identify-by choice with value, New name, rename-target choice (user name, logon name, both); Update display name under More. |
| `set_attribute` | Restructure | buried, grouping, missing | Add a get-user-property command: Session, identify-by choice with value, Attribute. |
| `update_account_options` | Restructure | buried, extra-visible, grouping, type | Session, identify-by choice with its value, then Enabled; other account flags under More options. |
| `update_user_details` | Restructure | buried, grouping, order | Session, identify-by choice, First name, Last name, Display name, Initials, Email, Description, Department, Title; Office, Telephone, Company, Manager, Attributes under More. |
| `connect` | Small change | buried, order | Session, Address, Base dn, Bind user, Password; timeouts and referrals under More options. |
| `create_computer` | Small change | buried, order | Session, Name, Computer description, Container dn (required OOA field); Enabled under More options. |
| `create_group` | Small change | buried, order, type | Session, Name, Group description, Scope, Group type up front, with Container dn; Sam account name under More options. |
| `create_ou` | Small change | buried | Session, Name, Ou description, then Parent dn (required OOA field). |
| `delete_computer` | Small change | buried | Session first, then Computer. |
| `delete_ou` | Small change | buried, extra-visible | Session, Ou; Recursive under More options. |
| `disconnect` | Small change | buried | Show Session as the single visible field. |
| `get_computer_property` | Small change | buried, type | Session, Computer, Attributes. |
| `get_group_members` | Small change | buried, missing | Session first, then Group, then a result-form choice (names or paths); keep Recursive, Max results and Member type after. |
| `get_group_property` | Small change | buried, type | Session, Group, Attributes. |
| `get_ou_property` | Small change | buried, type | Session, Ou, Attributes. |
| `move_computer` | Small change | buried | Computer, Target ou dn, Session (visible, last). |
| `move_ou` | Small change | buried | Ou, Target parent dn, Session (visible, last). |
| `rename_computer` | Small change | buried | Session, Computer, New name. |
| `rename_group` | Small change | buried, extra-visible | Session, Group, New name; Update sam account name under More options. |
| `rename_ou` | Small change | buried | Session, Ou, New name. |
| `run_query` | Small change | buried, extra-visible, missing | Session, Filter, result-form choice; Attributes and Base dn then More options. |
| `set_computer_property` | Small change | buried | Session, Computer, Attribute, Value. |
| `set_group_property` | Small change | buried | Session, Group, Attribute, Value. |
| `set_ou_property` | Small change | buried | Session, Ou, Attribute, Value. |
| `set_password` | Small change | buried | Session first, then Username, New password, Must change; Unlock stays under More options. |

## ooa.email

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `delete_all` | Restructure | buried, order, type | Order: Folder, Which emails (all/read/unread, replacing Unread only and Read only), Subject contains, From contains, Since, Before; Permanent, Expunge, To contains, Body contains, Session under More options. |
| `forward` | Restructure | buried, grouping, missing, order | Order: Id, To, Cc, Bcc, Reply to, Attachments, Importance, Note with a body-format choice, then a Send via choice revealing SMTP host/port/Security/auth-mode and credential fields; From, Folder, As attachment, Session under More options. |
| `move_all` | Restructure | buried, order, type | Order: To folder, Which emails (read/unread/both), Folder, Subject contains, From contains, Since, Before; To contains, Body contains, Create folder, Session under More options. |
| `reply` | Restructure | buried, grouping, missing, order | Order: Id, Cc, Bcc, Reply to, Attachments, Importance, Body with format choice, Send via choice revealing server fields; Include original, From, Folder, Session under More options. |
| `reply_all` | Restructure | buried, grouping, missing, order | Order: Id, Cc, Bcc, Reply to, Exclude sender, Importance, Attachments, Body with format choice, then Send via choice revealing server settings; Include original, From, Folder, Session under More options. |
| `save_all_attachments` | Restructure | buried, extra-visible, order, type | Order: Which emails (read/unread/both), Mark read, Folder, Target folder, Overwrite; filters (From, Subject, Since, Before) and Name pattern under More options. |
| `send` | Restructure | buried, grouping, missing, order | Order: To, Cc, Bcc, Reply to, Importance, Subject, Attachments, Body with format choice, then Send via choice revealing SMTP host/port/Security/From/auth fields; Read receipt, Inline images, Session under More options. |
| `mark` | Small change | order, type | Order: Id, a read/unread choice (Read), Folder; Flagged and Session under More options. |
| `move` | Small change | order | Order: Id, Folder, To folder; Create folder and Session under More options. |
| `save_attachments` | Small change | buried, extra-visible | Order: Id, Target folder, Overwrite; Name pattern, Folder, Include inline, Session under More options. |
| `save_email_as_eml` | Small change | buried, type | Order: Id, Folder, Local path, Overwrite. |
| `delete` | Same | extra-visible | Keep Id and Folder first; move Permanent to More options. |
| `disconnect` | Same |  | Keep Session as the only field. |
| `folder_exists` | Same |  | Keep Folder visible; Session under More options. |

## ooa.word

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `add_table` | Restructure | extra-visible, grouping, missing | Table title, position choice (current/bookmark), table, headers. |
| `find_replace_picture` | Restructure | buried, grouping | Alt text, range choice (document, page, paragraph, bookmark), replace tick revealing picture, alt text, size, occurrence. |
| `format_text` | Restructure | buried, grouping, order | Text, criteria group, range choice, occurrence, font name, color, size, bold, italic, underline. |
| `insert_picture` | Restructure | buried, extra-visible, grouping | Picture, alt text, width, height, position choice (current, start, end, bookmark) revealing bookmark. |
| `read_tables` | Restructure | grouping, missing | Table title, range choice (full/rows+columns) revealing four numbers, headers. |
| `read_text` | Restructure | extra-visible, grouping, missing | Range choice (full, page, paragraph, bookmark) revealing a field. |
| `replace_text` | Restructure | buried, grouping, missing | Find text, criteria group (case, whole words), range choice, replace tick revealing text and occurrence. |
| `create_document` | Small change | extra-visible, missing | Path, open password, edit password. |
| `insert_text` | Small change | grouping, order | Position choice first revealing anchor/bookmark, then text type plain/HTML with font options. |
| `open` | Small change | extra-visible, type | File, open mode choice, passwords. |
| `save` | Small change | extra-visible | Session only; password under More options. |
| `close` | Same |  | Keep: save tick, session. |
| `save_as` | Same |  | Keep: path, overwrite; format stays. |

## ooa.terminal

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `connect` | Restructure | buried, extra-visible, grouping, missing, order | Host, Port, Terminal type choice revealing its block (connection type, authentication, model, security, code page), then Show terminal tick, Prompt and Wait-for-prompt tick revealing timeout; Session under More. |
| `search_field` | Restructure | buried, grouping, missing, order | Add a get-field command: Session, select-by choice (index or name) revealing the matching input. |
| `send_key` | Restructure | buried, grouping, order | Session, Key, Delay after ms, a Wait tick revealing prompt-or-text choice, Wait for text, before/after ticks and Timeout; Repeat under More. |
| `send_text` | Restructure | buried, extra-visible, grouping | Session, Text, a Send-key-after tick revealing Press key and Delay after ms, a Wait tick revealing Wait for text and Timeout; Row, Column and Clear field under More. |
| `set_field` | Restructure | buried, extra-visible, grouping | Session, select-by choice (index or label) revealing its input, Text, Send Enter tick, Send-key tick revealing Press key; Row, Column under More. |
| `wait_for_text` | Restructure | buried, grouping, missing, order | Session, Event choice revealing Text or Row and Column, Timeout; Case sensitive, Fail on timeout, Poll interval under More. |
| `clear_screen` | Small change | buried, extra-visible | Session; Send to host under More. |
| `disconnect` | Small change | buried | Session as the visible field. |
| `get_all_fields` | Small change | buried | Session, then Only input fields. |
| `hide_terminal` | Small change | buried | Session as the visible field. |
| `set_cursor` | Small change | buried | Session, Row, Column. |
| `set_key_mapping` | Small change | buried, type | Session, then a mappings table with a key choice and a macro column. |
| `show_terminal` | Small change | buried | Session as the visible field. |

## ooa.browser

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `element_action` | Restructure | buried, extra-visible, grouping, type | Target (window + captured object) first, then an action choice; reveal property name, text/keystroke value (with typing delay and credential option) and table row/column fields only for relevant actions; run-in-background and waits below. |
| `extract_table` | Restructure | grouping, missing | Target window, then extraction type choice (system-detected or custom columns); custom reveals a list of column name + XPath; then timeout choice and lazy-load tick with retry count and delay. |
| `find_broken_links` | Restructure | buried, extra-visible, grouping | Address, scope (page or whole site), save location for the list, append, encoding, threads, wait; move only-broken, same-site and max-links to More options. |
| `open` | Restructure | buried, grouping, order | Lead with an open-in choice (existing tab, new tab, new window); show window picker for tab choices and browser (+ headless) for new window; then address; wait last. |
| `run_javascript` | Restructure | grouping, order | Window and frame first, then a source choice (file or typed script) that reveals only the matching field, then wait. |
| `call_javascript_function` | Small change | missing, order | Window and frame first, then function, arguments, wait, return type. |
| `get_page_source` | Small change | extra-visible, order | Window first, then frame choice (main or iframe), then optional save path; keep session under More options. |
| `history` | Small change | order | Window first, then number of steps, then the error-on-excess tick; action choice (back/forward/refresh) can stay on top as an OOA addition. |
| `close` | Same | missing | Keep: choice of what to close first (tab, window, all), then the window picker only for tab/window; session stays under More options. |
| `download_url` | Same |  | Keep order: address, save location, overwrite; credentials and wait stay under More options. |

## ooa.database

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `bulk_insert` | Restructure | buried, grouping, missing | Session, Source (file or table), Table, Delimiter choice (with custom entry), Start row, a Column mapping group, Batch size, Timeout. |
| `call_procedure` | Restructure | buried, grouping, type | Procedure, Parameters (entry list with direction, name, value, type), Max rows, Query timeout seconds, Export to CSV tick with options; Session last. |
| `connect` | Restructure | buried, grouping, missing, type | Connection mode first (JDBC URL or details); in details, Database type then Server, Port, Database, User, Password (or File for file-based types); driver tick revealing Driver class; timeout, properties, read only, auto commit in More. |
| `export_to_file` | Restructure | buried, grouping, missing, order | Source kind choice first revealing SQL statement and Parameters or procedure fields, then Max rows, Query timeout seconds, Path, Encoding, Header; Delimiter, Overwrite, Session in More. |
| `query` | Restructure | buried, grouping, order | SQL statement, Parameters, Max rows, Query timeout seconds, then an Export to CSV tick revealing its file options; Session last. |
| `begin_transaction` | Small change | buried | Show Session as the main visible field. |
| `commit` | Small change | buried | Show Session as the main visible field. |
| `disconnect` | Small change | buried, missing | Show Session visibly. |
| `execute` | Small change | buried | SQL statement, Parameters, Query timeout seconds, Session. |

## ooa.github

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `add_issue_comment` | Restructure | extra-visible, grouping, order | Order: Token, Owner, Repo, Type (issue/pull request), Number, Text; Endpoint address under More options. |
| `create_issue` | Restructure | buried, extra-visible | Order: Token, Owner, Repo, Title, Body, Labels, Assignees; Milestone and Endpoint address under More options. |
| `list_issues` | Restructure | buried, extra-visible, missing, type | Needs a Get issue command: Token, Owner, Repo, Issue number; Endpoint address under More options. |
| `list_pull_requests` | Restructure | buried, extra-visible, missing | Needs a Get pull request command: Token, Owner, Repo, Pull request number; Endpoint address under More options. |
| `list_repositories` | Restructure | extra-visible, grouping, missing | Order: Token, Owner type (organization/user), Owner name; Max results and Endpoint address under More options. |
| `create_pull_request` | Small change | buried, extra-visible, missing, order | Order: Token, Owner, Repo, Title, Body, Source branch, Target branch; Draft and Endpoint address under More options. |
| `get_repository` | Small change | extra-visible | Order: Token, Owner, Repo; Endpoint address under More options. |
| `list_branches` | Small change | extra-visible | Order: Token, Owner, Repo; Max results and Endpoint address under More options. |
| `merge_pull_request` | Small change | buried, extra-visible | Order: Token, Owner, Repo, Number, Method, Commit title, Commit message; Expected head sha and Endpoint address under More options. |
| `update_issue` | Small change | buried, extra-visible, order | Order: Token, Owner, Repo, Number, State, Title, Body, Labels, Assignees; Endpoint address under More options. |

## ooa.network

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `snmp_get` | Restructure | buried, grouping, missing | Group order: Host (host), Version (version), Community (community); then the OID fields; then an advanced group with Wait up to (ms) (timeout_ms), Port (port), Retries (retries). |
| `snmp_get_next` | Restructure | buried, grouping, missing | Group order: Host (host), Version (version), Community (community); then the OID fields; then an advanced group with Wait up to (ms) (timeout_ms), Port (port), Retries (retries). |
| `snmp_send_trap` | Restructure | grouping, missing | Group order: Host (host), Version, Community (community); then Trap oid (trap_oid), trap type, Variables (variables); then advanced group with Port (port) and timeout. |
| `snmp_set` | Restructure | grouping, order | Group order: Host (host), Version (version), Community (community); Oid (oid), Value type (value_type), Value (value); then advanced group. |
| `snmp_walk` | Restructure | grouping, missing | Group order: Host (host), Version (version), Community (community); Oid (oid) with a walk-type choice; then advanced group; keep Max rows (max_rows) under More options. |

## ooa.pdf

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `encrypt` | Restructure | buried, grouping, order, type | Path, User password, Owner password, a Permissions group (printing, modify, copy, forms, assembly, annotations, accessibility), Key length, Target, Overwrite. |
| `extract_images` | Restructure | buried, extra-visible, grouping, missing | Path, Password, Pages choice (all or specific) revealing Pages, Format revealing its options, Target folder, File prefix, Resolution, Color; Min size in More. |
| `extract_text` | Restructure | buried, extra-visible, grouping, missing | Path, Password, Mode (revealing reduce-loss for structured), Pages choice (all or specific) revealing the range, Output file, Overwrite. |
| `merge` | Restructure | buried, extra-visible, grouping | Files as a list of entries (path, password, pages), Target, Overwrite; Folder, Pattern, Sort by in More. |
| `split` | Restructure | buried, grouping, missing, order | Path, Password, Mode first revealing Pages per file or Pages, Target folder, File prefix, Overwrite. |
| `decrypt` | Small change | buried, type | Path, Password, Target, Overwrite visible. |
| `read_form` | Small change | grouping, missing | Path, Protected choice revealing Password, then which fields to extract. |
| `get_properties` | Same |  | Path, Password; Include custom may move to More. |

## ooa.sap

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `export_table` | Restructure | buried, extra-visible, grouping | Id, Export-as choice (CSV or table); CSV reveals Csv path, Encoding, Include header, Append (and Delimiter); Max rows under More. |
| `open_connection` | Restructure | buried, grouping, missing | Session, Connection type choice revealing System name, or Client, User, Password and Language; Start timeout under More. |
| `right_click` | Restructure | buried, extra-visible, grouping, missing | Id, a select-by choice (name or index) revealing the menu entry; Connection and Session under More. |
| `set_table_cell` | Restructure | grouping, missing, order | Id, Locate-by choice (text, or row and column), Value; Connection and Session under More. |
| `table_cell_count` | Restructure | missing | Add a get-cell-text command: Id, Row, Column. |
| `check_box` | Small change | order, type | Id, then one Action choice (check, uncheck, toggle); Connection and Session after. |
| `expand_node` | Small change | grouping, order | Id, Match by choice, Node key, Select; Connection and Session under More. |
| `get_table_cell_index` | Small change | buried | Id, Value, Case sensitive, All occurrences; Column, Connection and Session under More. |
| `select_item` | Small change | order | Id, Match by, Value. |
| `send_key` | Small change | buried, type | Key choice from a list (Enter, function keys); Connection and Window after. |
| `set_field` | Small change | buried | Id, Value, Append; Connection, Session and Press Enter under More. |
| `click` | Same |  | No change. |
| `close` | Same |  | No change. |
| `double_click` | Same |  | No change. |
| `get_children` | Same | missing | No change. |
| `get_field` | Same |  | No change (Property selector is a useful extra). |
| `get_item_count` | Same |  | No change. |
| `get_selected_item` | Same |  | No change. |
| `get_status_bar` | Same | order | No change. |
| `select_radio` | Same |  | No change. |
| `table_column_count` | Same |  | No change. |
| `table_row_count` | Same |  | No change. |

## ooa.asana

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `add_comment` | Restructure | extra-visible, grouping, missing | Token, Task id, a comment-format choice then Text, then Pin; Endpoint address to More. |
| `create_section` | Restructure | extra-visible, missing | Treat as different commands; create_section keeps Token, Project id, Name with endpoint in More; add a create-project command ordered Name, Workspace id, Team id, Owner, Notes, Due on. |
| `remove_tag_from_task` | Restructure | extra-visible, missing | Treat as unrelated; remove-tag keeps Token, Task id, Tag id with endpoint in More; add a delete-task command with Task id. |
| `update_comment` | Restructure | extra-visible, grouping, missing | Token, Comment id, format choice then Text, then Pin; Endpoint address to More. |
| `create_subtask` | Small change | buried, extra-visible, order | Token, Parent task id, Name, Assignee, Due on, Notes; Endpoint address to More. |
| `create_task` | Small change | buried, extra-visible, missing, order | Token, Workspace id, Name, Project id, Assignee, Due on, Notes, then Parent task and Completed; section, tags, followers, custom fields in More. |
| `create_team` | Small change | buried, extra-visible, order | Token, Name, Team description, Workspace id; Endpoint address to More. |
| `delete_attachment` | Small change | extra-visible | Token, Attachment id; Endpoint address to More. |
| `delete_comment` | Small change | extra-visible | Token, Comment id; Endpoint address to More. |
| `delete_project` | Small change | extra-visible | Token, Project id, Confirm; Endpoint address to More. |
| `download_attachment` | Small change | buried, extra-visible | Token, Attachment id, Folder, then Overwrite; Endpoint address to More. |
| `duplicate_task` | Small change | buried, extra-visible, type | Token, Task id, Name, Include as a multi-select of fixed parts; Endpoint address to More. |
| `get_team` | Small change | extra-visible | Token, Team id; Endpoint address to More. |
| `list_comments` | Small change | extra-visible, missing | Token, Task id; Endpoint address to More; consider a get-comment command with Comment id. |
| `list_sections` | Small change | extra-visible, missing | Token, Project id; Endpoint address to More; add a get-project command. |
| `list_tasks` | Small change | buried, extra-visible, missing, order | Add a list-attachments command with Token, Task id. |
| `list_users` | Small change | extra-visible, missing, order | Add a list-teams-for-user command ordered Token, User id or email, Workspace id. |
| `list_workspaces` | Small change | extra-visible, missing | Add a list-teams command with Token, Workspace id; Endpoint address to More. |
| `search_tasks` | Small change | extra-visible, missing | Add a separate get-task command (Task id, optional fields list); search keeps its filters; Endpoint address to More. |
| `update_project` | Small change | buried, extra-visible, order | Token, Project id, Name, Owner, Notes, Due on, Archived visible; Fields and Endpoint address in More. |
| `update_task` | Small change | buried, extra-visible, missing, order | Token, Task id, Name, Assignee, Due on, Notes, Completed, Parent task; custom fields and Endpoint address in More. |
| `update_team` | Small change | buried, extra-visible | Token, Team id, Name, Team description visible; Endpoint address to More. |
| `upload_attachment` | Small change | extra-visible, missing | Token, Task id, Path, then a file-name override; Endpoint address to More. |

## ooa.file

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `copy` | Restructure | buried, grouping, missing | Source, Target, Overwrite, then a Size filter tick and a Date filter tick each revealing their options; Keep both in More. |
| `delete` | Restructure | buried, extra-visible, grouping | Path, then a Size filter tick revealing Min/Max size and a Date filter tick revealing the date range; Pattern and Include subfolders in More. |
| `print_files` | Restructure | buried, grouping, order | Path or Files first, then Printer, Copies, then Size and Date filter ticks revealing their options. |
| `zip` | Restructure | buried, grouping, missing, type | Source, File type kind then Pattern, Target, Update only if newer, Delete originals, Compression, Password; Overwrite in More. |
| `create_shortcut` | Small change | extra-visible | Path, Shortcut; Arguments, Working folder, Comment, Overwrite in More. |
| `move` | Small change | missing, type | Source, New name or Target, then optional Size and Date filter ticks; Overwrite and Keep both in More. |
| `read_text` | Small change | missing, type | Path, Encoding (list with auto-detect), Trim leading, Trim trailing. |
| `read_variables` | Small change | buried, missing, type | Path, Encoding (list), Separator, Trim leading, Trim trailing, Lowercase keys. |
| `unzip` | Small change | missing | Archive, Folder, Overwrite, Password. |
| `write_text` | Small change | extra-visible, type | Path, Overwrite (replace Fail if exists), Text; Append and Encoding in More. |
| `create_symbolic_link` | Same |  | Path, Link; Overwrite may move to More. |
| `get_name` | Same |  | Single path field. |
| `get_parent` | Same |  | Single path field. |

## ooa.file_transfer

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `change_folder` | Restructure | buried, grouping, missing | Session first, then a navigate choice (parent folder or specific folder) revealing the Folder field. |
| `connect` | Restructure | buried, grouping, missing, order, type | Host, Port, Protocol (drives what follows), an authentication choice revealing User, Password or Private key, then protocol-specific mode/encryption fields, then a reconnect tick revealing attempts and delay; Session under More. |
| `download` | Restructure | buried, extra-visible, grouping, missing | Session, Remote path, Local path, Transfer type, date-filter choice revealing its fields; Overwrite under More. |
| `download_folder` | Restructure | buried, grouping, missing, order | Session, Remote folder, Pattern, Local folder, then a date-filter choice revealing its fields; Include subfolders, Overwrite, Only newer under More. |
| `create_folder` | Small change | buried | Session, then Folder. |
| `delete_files` | Small change | buried, type | Session, then the remote file location (Folder and Pattern). |
| `delete_folder` | Small change | buried, extra-visible | Session, Folder; Recursive under More options. |
| `disconnect` | Small change | buried | Show Session as the single visible field. |
| `rename` | Small change | buried | Session, From, To. |
| `upload` | Small change | buried, extra-visible, missing | Session, Local path, Remote path, Transfer type; Overwrite under More. |
| `upload_folder` | Small change | buried | Session, Local folder, Pattern, Remote folder; subfolders, overwrite and only-newer under More. |

## ooa.genai

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `detect_language` | Restructure | buried, grouping | Text, Provider (revealing AWS credentials and region); Model and Endpoint address in More. |
| `entities` | Restructure | buried, extra-visible, grouping, missing | Text, Provider (revealing AWS credentials and region), Language; Types and Model in More. |
| `key_phrases` | Restructure | buried, extra-visible, grouping, missing | Text, Provider (revealing AWS credentials and region), Language; Max phrases and Model in More. |
| `sentiment` | Restructure | buried, grouping, missing | Text, Provider first; choosing the AWS provider reveals access key, secret, region, then Language; Model and Endpoint address in More. |

## ooa.powerpoint

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `copy_slide` | Restructure | buried, grouping | Session, Copy-from choice revealing Slide, Paste-to choice revealing Position; Source session under More. |
| `find_replace_picture` | Restructure | buried, grouping, order | Session, Alt text to find, Range choice revealing Slide, a Replace picture tick revealing Image, New alt text, Width, Height, Keep aspect ratio, Occurrence. |
| `find_replace_text` | Restructure | buried, grouping, order | Session, Find, match options group, Range choice revealing Slide, Replace tick revealing Replace with and Occurrence. |
| `format_text` | Restructure | buried, grouping, order | Session, Text, match options, Range choice revealing Slide, Occurrence, then a formatting group (Font, Size, Color, Bold, Italic, Underline); Strikethrough under More. |
| `add_slide` | Small change | buried, extra-visible | Session, Layout, Position choice revealing an index; Title and Body under More options. |
| `close` | Small change | buried | Session, then Save. |
| `create_presentation` | Small change | extra-visible, missing | Path, Password (open), edit password; Overwrite, Template and Session under More. |
| `delete_slide` | Small change | buried, extra-visible | Session, Slide; Count under More options. |
| `insert_picture` | Small change | buried, grouping | Session, Slide choice, Image, Position group (X, Y), Size group (Width, Height); Alt text under More. |
| `insert_table` | Small change | buried, grouping, missing, order | Session, Slide, Position group (X, Y), Size group (Width, Height), Cell size group, Table, Include header; Font size under More. |
| `insert_text` | Small change | buried, extra-visible, grouping | Session, Slide, Text, Position group (X, Y), formatting group; Placeholder, Width, Height, Alignment under More. |
| `open` | Small change | buried, extra-visible, type | Path, Read-only choice, Password; Create and Session under More. |
| `save` | Small change | buried, extra-visible | Session as the visible field; Path and Password under More options. |
| `save_as` | Small change | buried, extra-visible | Session, Path, Overwrite; Format and Password under More. |

## ooa.services

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `pause` | Restructure | extra-visible, grouping, order | Order: Name (list or text choice), then Wait and Timeout seconds under More options. |
| `resume` | Restructure | extra-visible, grouping | Order: Name (list or text choice); Wait and Timeout seconds under More options. |
| `start` | Restructure | extra-visible, grouping | Order: Name (list or text choice); Wait and Timeout seconds under More options. |
| `stop` | Restructure | extra-visible, grouping | Order: Name (list or text choice); Wait and Timeout seconds under More options. |
| `get_status` | Small change | grouping | Order: Name (list or text choice). |

## ooa.xml

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `get_nodes` | Restructure | buried, grouping | Order: Path, Xpath, Return value (text/xml/xpath/attribute) revealing Attribute only for attribute. |
| `insert_node` | Restructure | buried, grouping, missing, order | Order: Path, Parent xpath, Name, Text, If exists, Position (revealing a reference child for before/after), Attributes; Save to, Xml under More options. |
| `set_value` | Restructure | grouping, missing | Order: Path, Xpath, Value, Update attributes tick revealing an attributes dictionary; Save to and Xml under More options. |
| `validate` | Restructure | grouping, missing | Order: Path or Xml source, Validation type (schema/DTD/well formed) revealing Xsd path only for schema. |
| `delete_nodes` | Small change | buried, order | Order: Path, Xpath, Attribute; Save to and Xml under More options. |
| `get_value` | Small change | buried | Order: Path, Xpath, Attribute; Return value and Xml under More options. |
| `save_file` | Small change | buried, missing | Order: Xml, Path, Overwrite (new), Indent. |
| `xpath_function` | Same |  | Keep Expression first, then source (Xml or Path). |

## core

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `delay` | Restructure | grouping, type | Add a delay-kind choice first (fixed or random); fixed shows Seconds, random shows the range (Seconds, Random max); Unit comes last. |
| `loop` | Restructure | buried, extra-visible, grouping | Show loop kind (iterator) first; iterate kinds show Over or Count only; while shows Condition then Check at end directly beneath; range, index, step and label stay in More options. |
| `run_bot` | Restructure | buried, grouping, missing | Order: Automation, Version, Inputs, a repetition choice (Repeat) revealing its count, a delay tick revealing Repeat delay seconds, then Fail on error; Output map, Timeout, Same session stay in More. |
| `comment` | Small change | type | Make Text a required multi-line field. |
| `if` | Small change | extra-visible | Else variant should show no fields (hide Condition). |
| `set` | Small change | grouping, missing, type | Keep Variable then Value; offer a credential picker for Value when the target is a credential. |
| `throw` | Small change | extra-visible, order, type | Order: Message, Code; move Suggested fix into More options with Details and Retryable. |
| `try` | Small change | missing | On a catch block add an exception-kind choice, then a continue-with-next-action tick. |
| `wait_until` | Small change | extra-visible | Condition first; move Timeout seconds and Poll seconds into More options next to Fail on timeout. |
| `break` | Same |  | Keep the single optional loop label. |
| `continue` | Same |  | Keep the single optional loop label. |
| `pause` | Same |  | Reference has no fields; keep Message and Timeout seconds. |
| `step` | Same |  | Keep the single title. |
| `stop` | Same |  | Reference has no fields; keep Message and Status. |

## ooa.desktop

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `capture_screen` | Restructure | buried, extra-visible, grouping, order | Order: Window (window) then region group X (x), Y (y), Width (width), Height (height) shown only when a window or area is chosen, then Path (path), then Overwrite (overwrite). |
| `mouse_click` | Restructure | buried, grouping, order | Order: Window (window) then position X (x), Y (y), then Button (button), then Button action (button_action) with Double-click folded into it; keep Relative to and Modifier keys under More options. |
| `type_text` | Restructure | buried, grouping, missing, order | Order: Window (window), then a source choice (typed text via Text (text) or a credential), then Method (method), then Delay ms (delay_ms). |
| `clipboard_get` | Small change | extra-visible | Move Wait seconds (wait_seconds) and Trim (trim) to More options. |
| `clipboard_set` | Small change | extra-visible | Main area: Text (text); move Append (append) to More options. |
| `message_box` | Small change | buried, grouping, order | Order: Title (title), Message (message), Scroll after lines (scroll_after_lines), then Timeout seconds (timeout_seconds) shown after an auto-close tick. |
| `mouse_move` | Small change | buried, missing | Order: Window (window), Relative to (relative_to), X (x), Y (y), then Duration ms (duration_ms); add a start-point pair if needed. |
| `mouse_scroll` | Small change | buried, extra-visible, order | Order: Direction (direction), Amount (amount), Delay ms (delay_ms); move X (x), Window (window), Y (y) to More options. |
| `prompt_file` | Small change | extra-visible, missing | Order: Title (title), message text (add), then move Start folder (start_folder) and File types (file_types) to More options. |
| `prompt_folder` | Small change | extra-visible, missing | Order: Title (title), message text (add); move Start folder (start_folder) and Timeout seconds (timeout_seconds) to More options. |
| `prompt_value` | Small change | buried, extra-visible, order | Order: Title (title), Question (question), Masked (masked); move Default (default) and Timeout seconds (timeout_seconds) to More options. |
| `prompt_yes_no` | Small change | extra-visible, order | Order: Title (title), Question (question); move Timeout seconds (timeout_seconds) to More options. |
| `clipboard_clear` | Same |  | No change: no fields. |

## ooa.image

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `find_in_image` | Restructure | buried, grouping, missing, order | Template, Image, region group, Repeat tick revealing times and delay, settings group (Tolerance, Occurrence, Position with offsets). |
| `find_on_screen` | Restructure | grouping, missing, order | Template, search-area group (region), Repeat tick revealing times and delay, settings group (Tolerance, Occurrence, Position choice revealing Offset x/y). |
| `wait_for_screen_change` | Restructure | buried, extra-visible, grouping, order | Scope choice (window or screen) revealing Window and region, Initial wait ms, Timeout seconds, Fail on timeout; Mode, Threshold, Stable ms, Interval under More. |

## ooa.jira

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `create_issue` | Restructure | buried, grouping, order | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Project (project), Issue type (issue_type) revealing Parent key (parent_key) for sub-tasks, Summary (summary), Description (description), Priority (priority), Labels (labels), Components (components), Extra fields (extra_fields); keep Assignee, Due date under More options. |
| `update_issue` | Restructure | buried, order | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Key (key), Summary (summary), Description (description), Priority (priority), Labels (labels), then Fields (fields). |
| `update_project` | Restructure | buried, order | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Project key (project_key), Project name (project_name), Project description (project_description), Assignee type (assignee_type), then Fields (fields). |
| `add_comment` | Small change | buried | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Key (key), Text (text); keep Visible to role/group under More options. |
| `create_project` | Small change | buried, missing | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Project key (project_key), Project name (project_name), Project type (project_type), Template key (template_key), Project description (project_description), Lead account id (lead_account_id). |
| `delete_comment` | Small change | buried | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Key (key), Comment id (comment_id). |
| `delete_issue` | Small change | buried | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Key (key), Delete subtasks (delete_subtasks). |
| `delete_project` | Small change | buried | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Project key (project_key). |
| `get_comments` | Small change | buried | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Key (key), Max results (max_results). |
| `get_projects` | Small change | buried, missing | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Query (query), Project type (project_type), Max results (max_results), Start at (start_at). |
| `search` | Small change | buried, missing | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Key (key) for a single issue; keep Jql (jql) for search. |
| `update_comment` | Small change | buried | Order: Site url (site_url), Email (email), Api token (api_token) as a connection group first, then Key (key), Comment id (comment_id), Text (text). |

## ooa.json

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `insert_node` | Restructure | grouping, missing, order | Source, node path, key name, then value type choice revealing a typed value field. |
| `update_node` | Restructure | grouping, missing | Source, path, value-type choice revealing a typed value, replace-array tick. |
| `validate` | Restructure | grouping, missing | Source choice (file, payload, dictionary), validation level choice (format, node, schema) revealing node+type or schema. |
| `get_node` | Small change | extra-visible | Source, path; default under More options. |
| `get_nodes` | Small change | missing, type | Source value, list of paths with type and rename per entry. |
| `stringify` | Small change | extra-visible | Value, pretty tick; indent under More options. |
| `to_table` | Small change | extra-visible | Source, path; flatten under More options. |
| `get_node_array` | Same |  | Keep: source value, path. |
| `parse` | Same |  | Keep: text. |

## ooa.ocr

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `read_image` | Restructure | grouping, missing, order | Source choice (image or PDF) with password and page numbers for PDF, filter-before/after tick revealing fields, trim, locale. |
| `read_screen` | Restructure | buried, extra-visible, missing | Window, wait before capture, filter group, trim, locale. |
| `read_url` | Restructure | grouping, missing | Source choice (image URL or PDF URL) with password/pages, filter group, trim, locale. |

## ooa.salesforce

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `get_attachments` | Restructure | buried, grouping, missing, order | Order: Record id (record_id), Download folder (download_folder), a filter choice with Kind (kind) and Name pattern (name_pattern), then connection. |
| `query` | Restructure | buried, grouping, missing, order | Add Get record: Captured object (object), id-type choice, Id (id), field list, then connection. |
| `revoke_authentication` | Restructure | grouping, missing | Add an authenticate command: choice of client credentials or stored connection, then API version (api_version), connection details. |
| `create_record` | Small change | grouping, order | Order: Captured object (object), Fields (fields), then connection Instance url (instance_url), Access token (access_token). |
| `delete_record` | Small change | order | Order: Captured object (object), Id (id), then connection Instance url (instance_url), Access token (access_token). |
| `update_record` | Small change | grouping, order | Order: Captured object (object), Id (id), Fields (fields), then connection. |
| `upload_attachment` | Small change | buried, missing, order | Order: Record id (record_id), Path (path), Title (title), Visibility (visibility), then connection. |
| `upsert_by_external_id` | Small change | grouping, order | Order: Captured object (object), External id field (external_id_field), External id value (external_id_value), Fields (fields), then connection. |

## ooa.script

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `run_javascript_function` | Restructure | grouping | Source choice (file or typed script). |
| `run_python_function` | Restructure | buried, grouping | Source choice (file or typed script), runtime version, interpreter. |
| `run_vbscript_function` | Restructure | grouping, missing | Source choice (file or typed script). |
| `run_javascript` | Small change | missing | Script/file, optional function name, arguments. |
| `run_python` | Small change | buried | Script/file, arguments; fail-on-error up front. |

## ooa.slack

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `get_conversation_history` | Restructure | extra-visible, grouping, missing | Needs a Get channel command: Bot token, Channel by (ID/name) revealing value. |
| `get_workspace_id_by_name` | Restructure | extra-visible, missing | Needs a Get workspace command: Bot token, Workspace id. |
| `send_direct_message` | Restructure | grouping, missing | Needs a Post channel message command: Bot token, Channel by (ID/name), Message; Thread, Blocks, Files under More options. |
| `get_channel_id_by_name` | Small change | extra-visible | Order: Bot token, Name; Include private, Include archived, Endpoint under More options. |
| `get_user_details` | Small change | extra-visible | Order: Bot token, User; Endpoint under More options. |

## ooa.zendesk

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `list_organizations` | Restructure | grouping, missing | Add a get-organization command: Connection, Organization id. |
| `list_view_tickets` | Restructure | grouping, missing | Connection, Mode choice revealing Status, Priority, Requester email or Query; View id as an alternative. |
| `search_tickets` | Restructure | grouping, missing | Add a get-ticket command: Connection, Ticket id. |
| `add_comment` | Small change | buried, grouping | Connection, Ticket id, Text, Public, Upload tokens; Body is HTML and New status under More. |
| `create_organization` | Small change | buried, grouping, missing | Connection, Name, Domain names, Organization fields; Details, Notes, External id under More. |
| `create_ticket` | Small change | buried, grouping | Connection, Subject, Description, Priority, Requester email, Custom fields; remaining fields under More. |
| `create_user` | Small change | buried, grouping, missing | Connection, Name, User email, Role, User fields; Phone, Organization id and others under More. |
| `download_attachment` | Small change | buried, extra-visible, grouping | Connection, Attachment id, Folder, Overwrite. |
| `find_users` | Small change | grouping, missing | Connection, Role choice, Organization id, then Query under More. |
| `update_organization` | Small change | buried, grouping | Connection, Organization id, Name, Organization fields; Domain names, Details, Notes, External id under More. |
| `update_ticket` | Small change | buried, grouping, type | Connection, Ticket id, Status, Priority, Assignee, Custom fields; Type, Subject, Group id, Tags under More. |
| `update_user` | Small change | buried, grouping | Connection, User id, Name, Role, User fields; Phone, Email, Organization id under More. |
| `upload_attachment` | Small change | grouping, missing | Connection, Path, File name; Add to token under More. |
| `delete_ticket` | Same |  | No change. |
| `get_user` | Same |  | No change. |
| `list_comments` | Same |  | No change. |

## ooa.box

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `create_shared_link` | Restructure | buried, grouping, missing, order | Order: Access token, Item type, Item id, Access, Allow download, then an Set expiry tick that reveals Expires at; keep As user / Endpoint under More options. |
| `list_folder` | Restructure | extra-visible, missing, order, type | Needs a distinct search command: Access token, Search text, File extension, Parent folder id; list_folder cannot serve it as is. |
| `copy_file` | Small change | missing, order | Order: Access token, File id, Target folder id, New name, then an overwrite checkbox (new) and As user / Endpoint under More options. |
| `create_folder` | Small change | extra-visible, order | Order: Access token, Parent folder id, Name, with Reuse existing moved to More options. |
| `delete_file` | Small change | extra-visible, missing, order | Order: Access token, File id, Delete permanently (new tick); As user moves under More options. |
| `delete_folder` | Small change | order | Order: Access token, Folder id, Include contents (recursive tick), then More options. |
| `download_file` | Small change | missing, order | Order: Access token, File id, Save as, Overwrite tick (new). |
| `move_file` | Small change | missing, order | Order: Access token, File id, Target folder id, Overwrite tick (new). |
| `upload_file` | Small change | buried, order | Order: Access token, Folder id, Local path, File name, Replace existing; others under More options. |

## ooa.datetime

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `format` | Restructure | buried, grouping, order | Value, format-kind choice; standard shows Standard list, custom shows Pattern; Time zone and Locale under More. |
| `parse` | Restructure | buried, grouping, missing, order | Text, a format-kind choice; when standard show Standard list, when custom show Pattern; Time zone and Locale under More. |
| `is_after` | Small change | extra-visible | Value, Other; Include time and Time zone under More options. |
| `is_before` | Small change | extra-visible | Value, Other; Include time and Time zone under More options. |
| `is_equal` | Small change | extra-visible | Value, Other; Include time and Time zone under More options. |
| `is_leap_year` | Small change | extra-visible, type | Value as the visible field; Year under More options. |
| `add` | Same |  | No change. |
| `difference` | Same |  | No change. |
| `get_part` | Same |  | No change. |
| `subtract` | Same |  | No change. |

## ooa.dictionary

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `put` | Restructure | grouping, missing, type | Treat as a separate table-column command: Record, Column name, Column type with typed Value, Insert-at choice revealing an index. |
| `remove` | Restructure | extra-visible, grouping, missing | Separate command: Record, delete-by choice (index or name) with its value. |
| `get` | Same |  | No change. |
| `size` | Same |  | No change. |

## ooa.pgp

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `decrypt` | Restructure | buried, grouping, missing | Key source choice first (Passphrase or Private key file) revealing its fields, Path, Target, Overwrite, Remove extension tick; Gnupg home in More. |
| `encrypt` | Restructure | buried, grouping, missing | Key source choice first revealing Passphrase, Public key file or Recipients, Algorithm, Path, Target, Add extension tick, Overwrite, Compression, Armor; Gnupg home in More. |
| `create_keys` | Small change | buried, extra-visible, missing | Public key file, Private key file, Algorithm, Passphrase, Overwrite, then Name and Email; Comment, Expires, Gnupg home in More. |

## ooa.rest

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `download_file` | Restructure | buried, grouping, order | Address, Proxy choice revealing details, Authentication choice revealing fields, Headers, Path, Timeout, TLS client certificate group; Query, Overwrite under More. |
| `request` | Restructure | buried, grouping, missing, order | Address, Proxy choice (system or custom revealing details), Authentication choice revealing its fields, Headers, Content type choice revealing the body fields (when the method sends a body), Advanced group (Fail on error, Allow untrusted certificates, Accept cookies), Timeout, TLS client certificate group; Method fixed by the command. |

## ooa.azure_devops

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `create_test_suite` | Restructure | buried, extra-visible, grouping | Plan id, Suite type first, then Requirement id (requirement) or Query (query), Parent suite id (required only for query), then Name. |
| `create_test_case` | Small change | buried, missing, type | Connection fields, Title, Area path, Iteration path, Priority (list), Steps, Automation status (list); Plan id, Suite id, Fields in More. |
| `create_test_plan` | Small change | buried, missing, order | Connection fields, Name, Plan description, Area path, Iteration, Start date, End date. |
| `create_test_run` | Small change | buried, missing | Connection fields, Plan id, Name, Point ids, Automated, Build id. |
| `list_pipelines` | Small change | buried | Connection fields, Max results visible. |
| `list_test_plans` | Small change | buried, missing | Connection fields, Max results, Active only, Owner. |
| `list_test_points` | Small change | missing | Connection fields, Plan id, Suite id, Test case id, Max results. |
| `run_pipeline` | Small change | buried, missing, type | Add a list-runs command: Connection fields, Pipeline id, Max results; keep run_pipeline unchanged. |
| `update_pipeline` | Small change | missing | Connection fields, Pipeline id, Pipeline definition file; Name and Folder in More. |
| `update_test_plan` | Small change | buried, missing, order | Connection fields, Plan id, Name, Plan description, Area path, Iteration, State, then dates. |
| `update_test_point` | Small change | missing | Connection fields, Plan id, Suite id, Point ids, Tester, Reset to active (outcome kept as an extra choice). |
| `update_test_suite` | Small change | buried, missing, order | Connection fields, Plan id, Suite id, Parent suite id, Name, Query. |
| `update_work_item` | Small change | buried, missing, order, type | Connection fields, Id, Title, Iteration path, State (list), Steps; Assigned to, Description, Tags, Fields in More. |
| `add_test_cases_to_suite` | Same |  | Connection fields first, then Plan id, Suite id, Test case ids. |
| `create_pipeline` | Same |  | Connection fields, Name, Repository id, Yaml path; Folder in More. |
| `delete_pipeline` | Same |  | Connection fields, Pipeline id, Confirm. |
| `delete_test_plan` | Same |  | Connection fields, Plan id, Confirm. |
| `delete_test_suite` | Same |  | Connection fields, Plan id, Suite id. |
| `delete_work_item` | Same |  | Connection fields, Id; Permanently in More. |
| `get_pipeline` | Same |  | Connection fields, Pipeline id. |
| `get_pipeline_run` | Same |  | Connection fields, Pipeline id, Run id. |
| `get_test_plan` | Same |  | Connection fields, Plan id. |
| `get_test_run` | Same |  | Connection fields, Run id. |
| `remove_test_cases_from_suite` | Same |  | Connection fields, Plan id, Suite id, Test case ids. |

## ooa.desktop_ui

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `object_action` | Restructure | buried, grouping, missing | Captured object, Action first; then show only the fields for that action (e.g. Text for set_text, Property for get_property); timeout and background options in More. |

## ooa.dll

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `call` | Restructure | buried, grouping, missing, order | Order: Session (session), Library (library), Function (function), typed argument rows in Arguments (arguments) with a type picker per row, Return type (return_type), then Calling convention (calling_convention) under More options. |
| `close` | Small change | buried | Show Session (session) in the main area. |
| `open` | Small change | type | Use a file picker for Library (library); keep Session (session) under More options. |

## ooa.docusign

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `send_envelope` | Restructure | extra-visible, missing, order | Add a create-from-files command ordered Access token, Subject, Signers, Documents, Send now; keep send_envelope as Access token, Account id, Envelope id. |
| `add_recipients` | Small change | extra-visible, order | Access token, Account id, Envelope id, Recipients; Endpoint address and Resend in More. |
| `download_zip` | Small change | extra-visible, missing, order, type | Access token, Account id, Envelope id, Save as; Endpoint address to More. |
| `get_envelope_status` | Small change | extra-visible, order | Access token, Account id, Envelope id; Endpoint address to More. |
| `list_documents` | Small change | extra-visible, order | Access token, Account id, Envelope id; Endpoint address to More. |
| `list_envelopes` | Small change | buried, extra-visible, missing, order, type | Add a get-envelope command: Access token, Account id, Envelope id. |
| `list_recipients` | Small change | extra-visible, order | Access token, Account id, Envelope id; Endpoint address to More. |
| `list_templates` | Small change | extra-visible, missing, order | Add a get-template command: Access token, Account id, Template id. |
| `resend_envelope` | Small change | extra-visible, order | Access token, Account id, Envelope id; Endpoint address to More. |
| `void_envelope` | Small change | extra-visible, order | Access token, Account id, Envelope id, Reason; Endpoint address to More. |

## ooa.excel_desktop

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `export_pdf` | Restructure | buried, grouping, order | Order: Path, Sheets to convert (entire/active/specific revealing Sheet), Target, Overwrite; Quality, page range and other options under More options. |
| `run_macro` | Same |  | Keep Path, Macro, Arguments; fine as is. |

## ooa.folder

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `copy` | Restructure | buried, extra-visible, grouping | Source, Target, Overwrite, a Size filter tick revealing Larger/Smaller than, a Date filter tick revealing modified after/before; Pattern and Only newer in More. |
| `create` | Small change | type | Path, Overwrite. |
| `create_shortcut` | Small change | extra-visible | Path, Shortcut; Comment and Overwrite in More. |
| `delete` | Small change | missing | Path, Recursive, then optional Size and Date filter ticks. |
| `rename_folder` | Small change | missing | Path, New name, optional Size and Date filter ticks. |
| `create_symbolic_link` | Same |  | Path, Link; Overwrite may move to More. |
| `open_folder` | Same |  | Single path field. |

## ooa.fuzzy

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `best_match` | Restructure | grouping, missing | Two input groups: choice of text or file then Text (text) or a file; keep Candidates (candidates), Threshold (threshold). |

## ooa.kafka

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `read_messages` | Restructure | buried, missing | Add a commit-offsets command: Endpoint address (base_url), Topics (topics), Consumer group (consumer_group), plus partition and offset. |
| `produce_message` | Small change | buried, order | Order: connection group, Topic (topic), Partition (partition), Key (key), Value (value); keep Headers (headers), Value format (value_format) under More options. |

## ooa.list

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `append_list` | Restructure | grouping, missing | List, Other; add a separate column-insert command with an insert-position choice (first, last, index). |
| `add_item` | Small change | extra-visible, grouping | List, Item, an add-where choice (end or at index) revealing Position. |
| `get_item` | Small change | extra-visible | List, Position; If out of range and Default under More options. |
| `join` | Small change | extra-visible | List, Separator; Skip empty and Escape sequences under More. |
| `remove_item` | Small change | extra-visible | List, Position; Value and All occurrences under More options. |
| `clear` | Same |  | No change. |
| `set_item` | Same |  | No change. |
| `size` | Same |  | No change. |

## ooa.snowflake

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `execute_statement` | Restructure | extra-visible, grouping, missing, order | Offer a structured export: Account url (account_url), Token (token), SQL statement (sql), destination, format, header, compression. |
| `insert_rows` | Same |  | No change: ours uses table and data table instead of raw SQL (better); Account url (account_url), Token (token) can lead. |

## ooa.soap

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `send` | Restructure | buried, grouping, missing | Order: Address (URL) (url) with WSDL source choice, Version (version), Soap action (soap_action), Body (body), then authentication mode choice revealing Basic user (basic_user), Basic password (basic_password), Headers (headers), Timeout seconds (timeout_seconds), Client certificate (client_certificate), Output file (output_file). |

## ooa.string

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `extract_text` | Restructure | buried, grouping | Source text, mode choice (after, before, both) revealing text and occurrence, no-match behavior, character count choice, case, trim, remove line breaks. |
| `find` | Small change | buried | Text, find, case, regex choice, start. |
| `random_string` | Small change | extra-visible | Length; letters/digits to More options. |
| `replace` | Small change | buried, order | Text, find, case, regex, start, count, replace with. |
| `split` | Small change | extra-visible, grouping | Text, delimiter, case, split-all vs max choice. |
| `substring` | Small change | buried | Text, start, length, return-blank tick. |
| `to_number` | Small change | extra-visible, grouping | Text, number-format choice (specify/device) revealing locale. |
| `trim` | Small change | extra-visible | Text, trim beginning/end. |
| `change_case` | Same |  | Keep: text, case. |
| `compare` | Same |  | Keep: text, other, mode. |
| `length` | Same |  | Keep: text. |
| `reverse` | Same |  | Keep: text. |

## ooa.web_agent

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `run` | Restructure | buried, extra-visible, grouping, missing | Session, Starting URL, Goal, Secrets, Output format, Timeout; Max steps, Allowed sites under More; a Provider group revealing only the matching connection fields. |
| `end_session` | Small change | buried | Session as the visible field. |
| `start_session` | Small change | grouping, missing, order | Browser, then optional Proxy and Browser path groups under More; Address and Headless as OOA fields. |

## ooa.workday

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `launch_integration` | Restructure | buried, extra-visible, grouping, missing, order | Order: Host, Tenant, ID type, Integration system id, Integration direction (inbound/outbound) revealing Input file; sign-in fields and Version under More options. |
| `get_integration_event` | Small change | missing | Order: Host, Tenant, ID type, Event id; sign-in fields and Version under More options. |
| `get_report` | Small change | buried, extra-visible, order | Order: Host, Report path, Format, Parameters; sign-in fields and Save to file under More options. |
| `authenticate` | Same | type | Keep Host, Tenant, then the credential fields. |

## ooa.ai_skills

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `run` | Same |  | Keep Skill first, then Values; Connection can stay visible as the one extra, with provider tuning under More options. |

## ooa.application

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `start_program` | Small change | buried, order | Program, Working folder, Arguments, Wait seconds; Window state and Environment under More. |
| `open_file` | Same |  | No change. |

## ooa.boolean

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `from_text` | Small change | extra-visible | Show Text (text) only; move Strict (strict) to More options. |
| `to_text` | Small change | extra-visible | Keep Value (value) only in the main area; move True text (true_text) and False text (false_text) to More options. |
| `compare` | Same |  | Keep Value (value), Other (other) first; Operator (operator) can stay as it is the only way to choose not-equal. |
| `invert` | Same |  | No change: single value field (value). |
| `to_number` | Same |  | No change: single value field (value). |

## ooa.credential_manager

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `read_credential` | Same |  | Keep Target first; Encoding is an acceptable OOA extra but could move to More options. |

## ooa.csv

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `read` | Small change | buried, extra-visible, order, type | Path, header tick, delimiter choice (comma, tab, newline, other, with custom value revealed), trim, encoding; keep detection of numbers/dates and other extras under More options. |

## ooa.currency

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `convert` | Small change | missing | Amount, from, to, then a date of conversion. |

## ooa.databricks

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `cancel_run` | Small change | order | Order connection first: Workspace url (workspace_url), Access token (access_token), then Run id (run_id). |
| `cancel_statement` | Small change | order | Order connection first: Workspace url (workspace_url), Access token (access_token), then Statement id (statement_id). |
| `delete_file` | Small change | extra-visible, order | Order connection first: Workspace url (workspace_url), Access token (access_token), then Remote path (remote_path). Move Recursive (recursive) to More options. |
| `download_file` | Small change | missing, order | Order connection first: Workspace url (workspace_url), Access token (access_token), then Remote path (remote_path), Save as (save_as). Add an overwrite option after Save as. |
| `execute_sql` | Small change | buried, order | Order: Workspace url (workspace_url), Access token (access_token), Warehouse id (warehouse_id), SQL statement (sql), Parameters (parameters), Max rows (max_rows), Wait seconds (wait_seconds); keep Catalog (catalog) and Schema (schema) under More options. |
| `fetch_results` | Small change | missing, order | Order: Workspace url (workspace_url), Access token (access_token), Statement id (statement_id), Max rows (max_rows); add a wait/timeout option if needed. |
| `get_run` | Small change | order | Order connection first: Workspace url (workspace_url), Access token (access_token), then Run id (run_id). |
| `list_jobs` | Small change | extra-visible, order | Order: Workspace url (workspace_url), Access token (access_token), Max results (max_results); move Name (name) to More options. |
| `run_job` | Small change | order | Order: Workspace url (workspace_url), Access token (access_token), Job id (job_id), Job parameters (job_parameters); keep the wait options under More options. |
| `upload_file` | Small change | buried, order | Order: Workspace url (workspace_url), Access token (access_token), Local path (local_path), Remote path (remote_path), Overwrite (overwrite) all in the main area. |

## ooa.forms

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `assign` | Small change | missing, type | Order: Name (name), Element (element) as list, add mode choice, Options (options). |
| `change_label` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `disable` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `display` | Small change | extra-visible, missing | Form (form) only in main area; move Name (name), Title (title) to More options; add keep-in-front option. |
| `enable` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `get` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `hide` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `highlight` | Small change | missing, type | Order: Name (name), Element (element) as list, type list, message; replace Color (color). |
| `set` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `set_focus` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `show` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `unhighlight` | Small change | type | Name (name), then Element (element) as a picker populated from the chosen form. |
| `validate` | Small change | extra-visible | Name (name) only; move Show messages (show_messages) to More options. |
| `change_title` | Same |  | No change: Name (name), Title (title). |
| `close` | Same |  | No change: Name (name). |
| `reset` | Same |  | No change: Name (name). |

## ooa.log

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `log_to_command_center` | Small change | missing, order | Order: Message, Details, Source tag (new), Level. |
| `log_variables` | Small change | buried, extra-visible, order | Order: Path, Write mode (append/overwrite), Timestamp, Variables; Title, Encoding, Timestamp format under More options. |
| `write` | Small change | buried, extra-visible, order, type | Order: Path, Message, Timestamp (revealing Timestamp format), Write mode (append/overwrite), Encoding; Level moves under More options. |

## ooa.number

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `to_text` | Small change | extra-visible | Number, decimals; pattern under More options. |
| `decrement` | Same |  | Keep: value, step. |
| `increment` | Same |  | Keep: value, step. |

## ooa.okta

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `activate_user` | Small change | buried | Org url, credentials, User, Send email. |
| `add_user_to_group` | Small change | buried, order | Org url, credentials, Group, User. |
| `create_user` | Small change | buried, missing, order | Org url, credentials, Login, Password, Email, First name, Last name, Phone, Activate; Profile fields and Group ids in More. |
| `deactivate_user` | Small change | buried, extra-visible | Org url, credentials, User; Send email in More. |
| `list_groups` | Small change | buried, extra-visible, missing | Org url, credentials, Group name (name filter); Max results in More. |
| `list_user_groups` | Small change | buried | Org url, credentials, User. |
| `list_users` | Small change | buried, missing, order | Org url, credentials, Filter (expression), Search text; Max results in More. |
| `remove_user_from_group` | Small change | buried, order | Org url, credentials, Group, User. |
| `update_user` | Small change | buried, order, type | Org url, credentials, User, then Login, Email, First name, Last name, Phone; Profile fields in More. |

## ooa.printer

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `get_default` | Same |  | Keep: no inputs. |
| `remove_printer` | Same |  | Keep: printer name. |
| `set_default` | Same |  | Keep: printer name. |

## ooa.servicenow

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `revoke_authentication` | Small change | grouping | Mapping loose: reference sets up authentication with type choice; ours revokes. |

## ooa.sound

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `play` | Small change | extra-visible | Path; Wait, Volume percent and Repeat under More options. |
| `beep` | Same |  | No change (reference has no fields). |

## ooa.system

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `get_environment_variable` | Small change | grouping, type | Source choice (list or typed) revealing Name; Default after. |
| `log_off` | Small change | extra-visible | Confirm; Force in More. |
| `restart` | Small change | extra-visible | Confirm; Delay seconds, Force, Message in More. |
| `shut_down` | Small change | extra-visible | Confirm; Delay seconds, Force, Message in More. |
| `lock` | Same |  | Keep Confirm (OOA safety field). |

## ooa.window

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `activate` | Small change | extra-visible | Window; timeout/process id under More options. |
| `capture` | Small change | buried, extra-visible | Window, path, overwrite up front. |
| `close` | Small change | extra-visible | Window; wait/process id under More options. |
| `close_all` | Small change | type | Windows to keep list; title for match. |
| `maximize` | Small change | extra-visible | Window; timeout/process id under More options. |
| `minimize` | Small change | extra-visible | Window; timeout/process id under More options. |
| `move_resize` | Small change | buried | Window, left, top, width, height up front. |
| `restore` | Small change | extra-visible | Window; timeout/process id under More options. |
| `set_title` | Small change | extra-visible, missing | Window, regex choice, new title. |
| `wait_for` | Small change | buried, order | Wait-for choice (open/close) first, window, duration, throw tick. |
| `get_active` | Same |  | Keep: no inputs. |

## ooa.workload

| Command | Result | Issues | Proposal |
| --- | --- | --- | --- |
| `update_item` | Small change | buried, missing, type | Queue, Item id, Data, Status revealing hold kind and On hold until; Replace data and Result in More. |
| `add_item` | Same |  | Queue, then Data. |
