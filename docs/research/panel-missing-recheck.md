# Panel review: the "missing" findings checked again (Q8)

Checked on 2026-10-28. The panel review of 2026-10-25 (`panel-review.md`) flagged 237 commands whose panel lacked a
field or capability that the reference product's panel has. Each was checked against today's toolkits (inputs, panel
layouts and sibling commands; source read where unclear). Only the missing part was judged, not order or grouping.

| Result | Commands |
| --- | --- |
| Closed (the field or a sibling command now provides it) | 164 |
| Still open | 69 |
| Unclear (the finding named no field) | 4 |

Some rows are closed by a sibling command rather than a field on the command itself (for example a separate Read WSDL
step, or table column steps for a dictionary). Where the review asked for something we left out on purpose (Sheets
steps keep no current cell, DTDs are refused for safety), the row stays listed as open with that note.

## Still open

| Toolkit | Command | What is missing |
| --- | --- | --- |
| core | `set` | No credential picker for Value; the Studio editor offers only text/value or calculation. **Closed 2026-10-29:** Set into a Credential variable offers only a Credential-variable picker. |
| ooa.asana | `add_comment` | No comment-format choice (plain vs rich/HTML text) and no pin option; only text. |
| ooa.asana | `list_comments` | No get-comment command (only update_comment and delete_comment take a comment id). |
| ooa.asana | `list_users` | No list-teams-for-user command (only get_team and get_user). |
| ooa.asana | `list_workspaces` | No list-teams command for a workspace. |
| ooa.asana | `update_comment` | No comment-format choice (plain vs rich/HTML text) and no pin option; only text. |
| ooa.asana | `update_task` | No Parent task input (cannot move a task under another parent); custom_fields is there. |
| ooa.asana | `upload_attachment` | No file-name override; the local file name is always used. |
| ooa.azure_devops | `list_test_plans` | No max results and no owner filter; only active_only. |
| ooa.azure_devops | `list_test_points` | No test case id filter and no max results. |
| ooa.azure_devops | `update_test_suite` | No query input to change a query-based suite's query (create_test_suite has one, update does not). |
| ooa.azure_devops | `update_work_item` | No steps input (only via the generic fields dictionary). |
| ooa.browser | `extract_table` | No custom-column extraction (list of column name + XPath); only the whole detected table via object/selector. Lazy load with retries/delay and timeout exist. **Closed 2026-10-29:** `columns` (name to XPath in each row, attributes too) and `row_xpath`, also for grids that are not tables. |
| ooa.databricks | `fetch_results` | No wait/timeout input (wait_seconds exists only on execute_sql). |
| ooa.desktop | `prompt_file` | No message text input (only title). |
| ooa.desktop | `prompt_folder` | No message text input (only title). |
| ooa.excel | `close` | Optional read-only error tick (fail when saving a read-only workbook) not offered; only save. |
| ooa.excel | `delete_cells` | Active cell/selection target not offered; range is required. |
| ooa.excel | `delete_table_column` | Delete by column position not offered; column_name (header) only. |
| ooa.excel | `filter` | Target an Excel table (instead of a sheet) not offered; no table or range input. |
| ooa.file | `read_text` | No auto-detect encoding and no trim leading/trailing options. |
| ooa.file | `read_variables` | No lowercase-keys option; trim is one switch (not separate leading/trailing). |
| ooa.file | `zip` | No 'update only if newer' option; pattern covers the file-type choice. |
| ooa.folder | `delete` | No size or date filters; only path and recursive. |
| ooa.folder | `rename_folder` | No size or date filters; only path and new_name. |
| ooa.fuzzy | `best_match` | No file source for the text; text only. |
| ooa.genai | `entities` | No Language input (AWS credentials and region are there). |
| ooa.genai | `key_phrases` | No Language input (AWS credentials and region are there). |
| ooa.genai | `sentiment` | No Language input (AWS credentials and region are there). |
| ooa.google | `drive_copy_file` | Destination folder by path not offered (folder_id only); source by path (item_path) and rename (name) exist. |
| ooa.google | `drive_copy_folder` | Source and destination by path not offered (folder_id / destination_id only). |
| ooa.google | `drive_download_export` | Overwrite and rename options not offered (drive_download has overwrite, the export command does not). |
| ooa.google | `drive_move_item` | Source can now be picked by path (item_path), but the destination is still folder_id only; no destination folder by path. |
| ooa.google | `sheets_get_cell_color` | Both colours are returned (background_color, font_color), but only as #RRGGBB; no choice to get a colour name instead of the code. |
| ooa.google | `sheets_read` | No all-cells choice; range is a required A1 range (sheets_get_range is the same). |
| ooa.google | `sheets_set_cell` | No command that moves the selection/active cell (go to cell); Sheets commands are stateless, so this stays a deliberate gap. |
| ooa.image | `find_in_image` | No repeat (times and delay) inputs; find_on_screen has timeout_seconds and interval_ms, but this command does not. |
| ooa.json | `get_nodes` | Rename per entry works (dictionary of name to path) but there is no per-entry type conversion. |
| ooa.log | `log_to_command_center` | No source tag input; only message, level and details. |
| ooa.microsoft365 | `excel_delete_worksheet` | Delete sheet by index: sheet takes a name only. |
| ooa.microsoft365 | `excel_find` | Search order (by rows / by columns) not offered; only ooa.excel find has search_by. |
| ooa.microsoft365 | `excel_read_column` | No Get column name (cell address to column letter) command in Microsoft 365; only ooa.excel column_name. |
| ooa.microsoft365 | `excel_rename_worksheet` | Sheet by index: sheet takes the current name only. |
| ooa.microsoft365 | `excel_write_range` | No cell-movement choice (specific/left/right/up/down/row-column edge); the proposal itself said no equivalent is needed for stateless Graph actions. |
| ooa.microsoft365 | `export_to_pdf` | Overwrite option for the local PDF not offered. |
| ooa.microsoft365 | `find_meeting_times` | Include outside working hours not offered (activityDomain is fixed to work). |
| ooa.microsoft365 | `get_channel_by_name` | Team by name: only team_id; must chain get_team_by_name first (teams_message has team_name, this command does not). |
| ooa.microsoft365 | `remove_attendees` | No Clear event details command (ticks to blank subject, location, body, reminder, recurrence, all day); update_event leaves empty fields unchanged. |
| ooa.microsoft365 | `rename_item` | Overwrite (replace an item of the same name) not offered. |
| ooa.network | `snmp_send_trap` | No version choice (v1/v2c/v3), no trap type (v1 generic/specific) and no timeout; only host, community, trap_oid, variables, port. **Closed 2026-10-29:** version v1/v2c/v3, v1 generic and specific type, v3 signed and encrypted from an engine ID. A trap gets no answer, so there is nothing to time out. |
| ooa.network | `snmp_walk` | No walk-type choice (e.g. plain walk vs bulk walk); version and v3 fields do exist. |
| ooa.ocr | `read_image` | No PDF source (with password and page numbers); only image files. Workaround is pdf to_images first. **Closed 2026-10-29:** `ooa.pdf/read_text_ocr` reads scanned PDFs (password, pages, markers). |
| ooa.ocr | `read_url` | No PDF URL source (with password and pages); only image URLs. |
| ooa.pdf | `extract_images` | No resolution input (dpi exists only on to_images); pages, format, color, prefix, password exist. |
| ooa.pdf | `read_form` | No choice of which fields to read; it always returns all fields. |
| ooa.pgp | `create_keys` | No overwrite input for existing key files. |
| ooa.pgp | `decrypt` | private_key_file is there, but no remove-extension tick; target must always be given. |
| ooa.pgp | `encrypt` | public_key_file, cipher, compression and armor are there, but no add-extension tick; target must always be given. |
| ooa.powerpoint | `create_presentation` | No separate edit (modify) password; only the open password. **Closed 2026-10-29:** `edit_password` (password to modify). |
| ooa.sap | `right_click` | menu_item is only a function code or element id; no choice to pick the entry by its text or by position. |
| ooa.snowflake | `execute_statement` | No structured export (destination file, format, header, compression) in any Snowflake action. **Closed 2026-10-29:** `export_results` streams every partition to CSV, TSV or JSON lines, with header and gzip. |
| ooa.word | `add_table` | Position choice now exists (end / after text / after bookmark), but there is no table title (alternative-text title) input. |
| ooa.word | `create_document` | Open password exists (password), but there is no edit (modify / write-protection) password. **Closed 2026-10-29:** `edit_password` restricts editing. |
| ooa.workday | `get_integration_event` | No ID type input (event_id must be a WID). |
| ooa.workday | `launch_integration` | No integration direction and no input file for inbound integrations; id_type is there. **Closed 2026-10-29:** `launch_eib` (Launch EIB with file) sends the file as Attachment_Data of the EIB's data source field; checked against the Launch_EIB v42.0 reference, not yet against a tenant. |
| ooa.xml | `save_file` | No Overwrite option. |
| ooa.xml | `set_value` | Only one attribute per step (attribute); no dictionary to update several attributes at once. |
| ooa.xml | `validate` | No DTD validation type; only well-formed or XSD (DTDs are refused by design). |
| ooa.zendesk | `upload_attachment` | No file name input; the uploaded name is always taken from the local path. |

## Unclear

| Toolkit | Command | Note |
| --- | --- | --- |
| ooa.google | `sheets_delete_sheet` | Proposal names no missing field. Sheet is picked by name only (no by-position choice); spreadsheet accepts id/link/name. |
| ooa.google | `sheets_rename_sheet` | Proposal names no missing field. Sheet is picked by name only (no by-position choice). |
| ooa.sap | `get_children` | Proposal says no change and names no missing field; inputs are id, connection, session. |
| ooa.json | `update_node` | node takes any typed value; no replace-array switch, and it is unclear whether whole replacement already covers it. |

Most open items are small inputs (an overwrite tick, a language, a pick-by-position choice, a folder by path).
The larger ones: Snowflake structured export, Workday inbound integrations with a file, custom columns for web table
extraction, and OCR straight from PDF files.
