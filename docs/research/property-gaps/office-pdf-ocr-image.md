# Property gaps: Excel, Word, PowerPoint, PDF, OCR, Image recognition

Compared on 2026-10-22 against the public Automation 360 documentation (docs.automationanywhere.com/r/automation-360/...), read without signing in. AA's options are summarised in our own words. "AA options" lists what the AA action panel offers; "We had" is our action before this change; "Added" lists the new optional inputs (all have defaults that keep earlier behaviour, so existing agents run unchanged) and new outputs. Anything we chose not to copy is under "Skipped".

Options that every AA action has and that our platform already covers in another way are not repeated per row:

- **Session name / session variable**: our `session` input.
- **File from the Control Room, a desktop path or a variable**: our `FILE` inputs, resolved by the runner.
- **Credential, variable or insecure string for passwords**: our `CREDENTIAL` inputs (a vault Credential or plain text).
- **Output variable**: our `@Output`s.

Units: AA's Word and PowerPoint use centimetres; we keep points (72 per inch) everywhere, as before.

New actions are marked **(new)**.

## Excel (no Office needed): `ooa.excel`, compared with AA Excel basic and Excel advanced

| Our action | AA action and options | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| open | Open: file; the sheet has a header row; a specific sheet; read-only or read-write; open password and edit password; option to stop links updating; local or global session | path, session, create | `sheet` (sheet made active), `read_only` (save over the file refused, save_as elsewhere allowed), `password` (opens encrypted .xlsx/.xls; save re-encrypts), `header` (default for the header input of get_all_cells, get_range, filter, sort_range) | Edit (write-reservation) password: it only makes Excel suggest read-only and is not a security feature; we offer `read_only` instead. Link updating: we never recalculate links when opening. Load add-ins: only meaningful in desktop Excel. Global sessions: a platform-wide feature, not per action |
| create_workbook **(new)** | Create workbook: path, overwrite, sheet name, open and edit passwords, optional sensitivity label | open with create=true (file only written on save) | New action: `path`, `sheet`, `overwrite`, `session`; the file is written at once | Passwords and label at creation: use save_as with `password`, and set_sensitivity_label |
| close | Close: save changes on close; error when saving a read-only file | session | `save` (save to the opened file first; read-only sessions stop with EXCEL.READ_ONLY) | - |
| save | Save workbook: error when the file is read-only | path, session | `password` (encrypt on save); read-only check | - |
| save_as | Save as: target path, replace existing file; xlsx, xls, xlsm, csv | path, format (xlsx, xls, csv), sheet | `overwrite` (false stops with EXCEL.EXISTS), `password` (encrypted xlsx or xls) | xlsm: Apache POI cannot turn a plain workbook into a macro-enabled one; use ooa.excel_desktop/convert |
| get_cell | Get single cell: active or specific cell; read the value or the displayed text | cell, sheet | `cell` now optional (empty = active cell), `read_as` (value or text with number formats) | - |
| set_cell | Set cell: active or specific cell (range allowed); value | cell, value, sheet | `cell` optional (active cell) | Writing one value into a whole range: use write_table or a loop |
| get_all_cells | Get worksheet as data table: sheet by index or name; read value or displayed text; header | sheet, header | `read_as`, `include_hidden` (false returns only visible rows and columns), `keep_blank_rows`; header now falls back to the open setting | Sheet by index: use switch_sheet with `index` first |
| get_range | Get multiple cells: all rows, specific rows or a cell range; read mode | range, header, sheet | `read_as`; header falls back to the open setting | - |
| write_table | Write from data table: active or specific sheet; first cell; header | table, start_cell, include_header, sheet | - | Already matches |
| find | Find: from and to (beginning, end, active cell, specific cell); search by rows or columns; match case; whole cell | text, match_case, sheet | `range`, `whole_cell`, `look_in` (values or formulas), `search_by` (rows or columns order); outputs `first_cell`, `count` | Start and end at the active cell: give the range instead |
| replace | Replace: from and to; search by rows or columns; replacement | find, replace_with, range, match_case, whole_cell | `look_in` (also inside formula text), `search_by`, `first_only`; output `cells` | - |
| get_sheet_names | Get worksheet names | session | `include_hidden` | - |
| sheet_count | Retrieve sheets count: include hidden sheets | session | `include_hidden` | - |
| add_sheet | Create worksheet: by index or by name | name, replace | `position` (1-based place among sheets), `activate` | - |
| delete_sheet, rename_sheet, switch_sheet, hide_sheet, unhide_sheet | Each takes the sheet by index or by name | name only | `index` (1-based) on all five; the name became optional | - |
| unhide_all_sheets, get_current_sheet_name, copy_sheet, append_workbook, append_worksheet | Session only (append workbook also takes a password for the source) | as AA | - | Password for the appended source file: rare; open it with a password and save_as without one first |
| delete_cells | Delete cells: active or specific cell or range; shift left, shift up, entire row, entire column | range, shift up/left | `shift` gains entire_row and entire_column | - |
| insert_rows, delete_rows, insert_columns, delete_columns, hide_rows, unhide_rows, hide_columns, unhide_columns | Insert/Delete rows/columns: one row or column, or a range such as 1:5 or A:E; hide/unhide in selection | row or column plus count | - | Already matches (count covers ranges) |
| remove_blank_rows | Remove blank rows: range | sheet | - | Range limit: low value; whole used area is what agents need |
| row_count | Get number of rows: non-empty or all rows | sheet | `mode` (non_empty or all rows of the used area) | - |
| get_table_range | Get table range: Excel table name; include header; include pivot | sheet (used area) | `table` (range of a named Excel table), `include_header` | Pivot tables: POI does not compute pivot layouts |
| find_next_empty_cell | Find next empty cell: by row or column; from active or specific cell | start_cell, direction down/right | `direction` gains up and left (stops with EXCEL.NO_EMPTY_CELL at the sheet edge) | - |
| go_to_next_empty_cell **(new)** | Go to next empty cell: from active or specific cell; left, right, up or down | find_next_empty_cell only returned the address | New action: makes the empty cell active; output `cell` | - |
| go_to_cell | Go to cell: specific cell, or a move from the active cell | cell | `cell` optional, `move` (left, right, up, down, row_start, row_end, column_start, column_end); output `cell` | - |
| select_range, cell_address, column_name, row_number | Select cells; get cell address (also by header title plus offset); get column name; get row number (active or specific cell) | as listed | - | Address by header title plus offset: combine find on the header row with cell_address |
| read_formula, set_formula | Read/set cell formula: active or specific cell | cell | - | Already matches (go_to_cell then read the address) |
| read_column | Read column: from active cell, specific cell or full column; value or displayed text | column, start_row | `end_row`, `read_as` | - |
| read_row | Read row: from active or specific cell; full row; value or displayed text | row, start_column | `end_column`, `read_as` | - |
| get_cell_color | Get cell colour: background or text; active or specific cell; result as R,G,B | cell, target | `cell` optional, `format` (hex or rgb) | - |
| set_cell_color | (Microsoft 365 only in AA) | range, color, target | - | - |
| protect_workbook | Protect workbook: workbook and/or structure, each with a password | password (structure) | `lock_structure`, `lock_windows` | Separate passwords for structure and windows: Excel stores one workbook password |
| unprotect_workbook | Unprotect workbook / structure | password | also unlocks windows | - |
| protect_sheet | Password protect worksheet: allow or block each operation | password | `allow_select_locked`, `allow_select_unlocked`, `allow_format_cells`, `allow_format_columns`, `allow_format_rows`, `allow_insert_rows`, `allow_insert_columns`, `allow_delete_rows`, `allow_delete_columns`, `allow_sort`, `allow_filter` | Objects and scenarios: always protected, as in AA |
| unprotect_sheet | Access password protected worksheet | password | - | Already matches |
| sort_range | Sort: table or sheet; column by name or position; range; whole rows or only that column; header; order | column, descending, header, range | `then_by`, `then_descending` (second key); header falls back to the open setting | Sorting only one column apart from its rows: would break records |
| filter | Filter: table or sheet; remove existing filter; range; column; equals, not equal, >, >=, <, <=, between, begins with, ends with, contains, does not contain | returned matching rows only | operators `between` (with `value_to`) and `not_contains`; `hide_rows` (hides non-matching rows on the sheet, like AutoFilter) | Excel table (ListObject) filter: use hide_rows on the table's sheet |
| insert_table_column, delete_table_column | Table name; column name; position | as AA | - | Already matches |
| get_links | Get workbook links (path and file name) | links, cells | - | Already matches |
| break_links | Break workbook links: one link or all | all links | `link` (break only links to one workbook) | - |
| change_link **(new)** | Change workbook links: old source, new source | none | New action: `old_link`, `new_link` | - |
| get_sensitivity_label, set_sensitivity_label | Label GUID; continue on error | label id, site id, name, method | - | Already matches |
| (none) | Run macro, Convert excel to PDF, Update workbook links, screen updating on/off | - | Covered by `ooa.excel_desktop` | Real-time screen update: no screen in the file-based toolkit |

## Excel desktop: `ooa.excel_desktop` (needs Excel), compared with AA Excel advanced

| Our action | AA action and options | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| run_macro | Run macro: macro name, arguments | path, macro, arguments, save, timeout | `password` (open password, passed in the environment, never on the command line), `visible` | - |
| refresh_all | (Excel 365 has refresh; desktop needs it for queries) | path, timeout | `save` (save afterwards), `password` | - |
| update_links **(new)** | Update workbook links | none | New action: `path`, `save`, `password`, `timeout_seconds`; output `count` | - |
| export_pdf | Convert excel to PDF: whole workbook, active sheet or a sheet; output name and folder | path, target, sheet | `quality` (standard or minimum), `include_properties`, `ignore_print_areas`, `from_page`, `to_page`, `overwrite`, `password` | - |
| convert | Save as (other formats) | path, target (xlsx, xlsm, xls, csv) | `overwrite`, `password`, `new_password`; more targets: xlsb, txt, ods, xltx, xltm | - |

## Word: `ooa.word`

| Our action | AA action and options | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| open | Open: file (docx, doc, rtf); read-write or read; open password; edit password | path, create | `read_only`, `password` (encrypted .docx; saves keep it) | .doc and .rtf: Apache POI's Word support is .docx only. Edit password: advisory only in Word |
| create_document | Create document: docx, doc or rtf; open and edit passwords; fails when the file exists | path, overwrite | `password`, `template` (start from an existing .docx) | doc/rtf, edit password: as above |
| close | Close: save changes first | session | `save` | - |
| save | Save: error when read-only | path | `password`; read-only check | - |
| save_as | Save as: destination, overwrite; format from the extension | path, format docx | `overwrite`, `password`, format `txt` (plain text export) | PDF: needs a layout engine; documented with WORD.PDF_UNSUPPORTED |
| read_text | Read text: whole document, page number, paragraph index or bookmark | session (body paragraphs) | `include_tables`, `include_headers_footers`, `from_paragraph`, `to_paragraph` | Page number: .docx files do not store page breaks reliably (pagination happens in Word) |
| replace_text | Find and replace text: match case, whole words, range, occurrence all/first/last | find, replace_with (case-sensitive, all) | `match_case` (default true as before), `whole_word`, `occurrence` (all, first, last) | Page or paragraph range: same pagination reason |
| format_text | Format text: match case, whole words, range, occurrence; font, colour, size, bold, italic, underline | text, bold, italic, underline, size, color, font | `match_case`, `whole_word`, `occurrence`, `highlight`, `strikethrough` | - |
| insert_text | Insert text: start, end, bookmark; plain text or HTML; font, colour, size, bold, italic, underline | text, position (end, after_text, before_text, bookmark), anchor, new_paragraph | position `start`; `bold`, `italic`, `underline`, `size`, `color`, `font` | HTML input: would need an HTML-to-Word converter; insert plain text and use format_text |
| insert_picture | Insert picture: alt text, width, height, position (cursor, start, end, bookmark) | image, width, after_text, alt_text | `height`, `position` (end or start), `bookmark`, `alignment` | SVG: POI cannot embed SVG reliably; convert to PNG first |
| find_replace_picture | Find and replace picture: by title/alt text, range, new picture, new alt text, width, height, occurrence | image, alt_text, index | `occurrence`, `new_alt_text`, `width`, `height` | Page/paragraph range: pagination reason |
| add_table | Insert table: title, position (cursor or bookmark), data table, first row as header | table (at the end, header bold) | `include_header`, `after_text`, `bookmark`, `style` | Table title (caption): add a paragraph with insert_text |
| read_tables | Read table: table by title, full table or row/column range, header | session (all tables) | `index` (one table, output `table`), `header` | Table title lookup: Word tables rarely carry titles; use index. Row/column window: filter the Data Table afterwards |
| add_paragraph | (AA insert text) | text, bold, style | - | Covered by insert_text options |

## PowerPoint: `ooa.powerpoint`

| Our action | AA action and options | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| open | Open: ppt or pptx; read-write or read-only; open and edit passwords | path, create | `read_only`, `password` | .ppt: legacy binary format; edit password: advisory |
| create_presentation **(new)** | Create presentation: path, open and edit passwords; fails when the file exists | open with create=true | New action: `path`, `overwrite`, `session`, `password`, `template` | Edit password: advisory |
| close | Close: save changes | session | `save` | - |
| save | Save | path | `password`; read-only check | - |
| save_as | Save as: ppt, pptx or pdf; overwrite | path, format pptx | `overwrite`, `password` | PDF and .ppt: need PowerPoint or a renderer |
| add_slide | Add slide: nine layouts; start, end, after current, after index | layout (5 layouts), title, body, position | layouts `two_content`, `comparison`, `content_with_caption`, `picture_with_caption` | "After current slide": we have no current slide; position covers it |
| delete_slide | Delete slide: index | slide | `count` (several slides in a row) | - |
| copy_slide | Copy paste slide: current or specific slide; paste after current or a specific slide; (overview: into another open presentation) | slide, position | `source_session` (copy from another open presentation) | - |
| insert_text | Insert text: slide, text, position; font, size, colour, bold, italic, underline | slide, text, placeholder, x, y, width, height, font_size | `bold`, `italic`, `underline`, `color`, `font`, `alignment` | - |
| read_text, slide_count | (no AA equivalent) | - | - | - |
| find_replace_text | Find and replace text: match case, whole words, slide, occurrence all/first/last | find, replace_with, slide | `match_case`, `whole_word`, `occurrence` | - |
| format_text | Format text: match case, whole words, slide, occurrence; font, size, colour, bold, italic, underline | text, formatting, slide | `match_case`, `whole_word`, `occurrence`, `strikethrough` | - |
| insert_picture | Insert picture: slide, position, height, width; returns shape name | slide, image, x, y, width, alt_text | `height`; output `shape` | - |
| find_replace_picture | Find and replace picture: alt text, slide, new alt text, width, height, lock aspect ratio, occurrence | image, alt_text, index | `slide`, `occurrence`, `new_alt_text`, `width`, `height`, `keep_aspect_ratio` | - |
| insert_table | Insert table: slide, title, position, size, lock ratio, row height, column width, data, header; returns shape name | slide, table, x, y, width, font_size | `include_header`, `row_height`, `column_widths`; output `shape` | Table title: name shapes are set automatically; inline table editor is a Studio feature |

## PDF: `ooa.pdf`

| Our action | AA action and options | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| extract_text | Extract text: plain or structured text; reduce data loss; all pages or chosen pages; export to .txt; overwrite; passwords | path, from_page, to_page, password | `mode` (structured as before, or plain), `pages` (e.g. 1,3,5-7), `output_file`, `overwrite` | "Reduce data loss": specific to AA's extractor |
| extract_area_text **(new)** | Extract field, region fields: a drawn rectangle with a key name | none | New action: `page`, `x`, `y`, `width`, `height` (points from the top-left), `password` | Drawing the region in a viewer: Studio feature; one region per call |
| read_form | Extract field, form fields | path, password | - | Already returns all fields |
| fill_form | (no AA equivalent) | path, values, target, flatten | `password` (the filled copy is saved without protection) | - |
| merge | Merge documents: list of files, each with passwords and page selection; output; overwrite | files, target | `overwrite`, `pages` (per file), `passwords` (per file) | - |
| split | Split document: pages per file, one file with selected pages, blank page as separator, by bookmark level; folder; file prefix; overwrite | path, target_folder, pages_per_file | `mode` (pages_per_file, ranges, selected_pages), `pages`, `file_prefix`, `overwrite`, `password` | Blank-page separator and bookmark levels: rarely used; can be added later with the same mode input |
| encrypt | Encrypt document: user and owner passwords; print, modify, copy, fill forms, assembly, annotate, accessibility; RC4 40, RC4 128, AES 128 | path, target, user and owner passwords, allow_printing; AES-256 | `allow_copy`, `allow_modify`, `allow_annotations`, `allow_fill_forms`, `allow_assembly`, `allow_accessibility`, `key_length` (256 or 128, AES), `overwrite` | RC4 40/128: weak, deliberately not offered |
| decrypt | Decrypt document: password; save as; overwrite | path, password, target | `overwrite` | - |
| extract_images | Extract image: pages; image type (tiff, bmp, jpeg, gif, png, ...); compression and quality; folder; file prefix; resolution; colour or grey; colour depth; overwrite | path, target_folder, min_size, password | `pages`, `format` (png, jpg, bmp, gif, tiff), `file_prefix`, `color` (color or gray) | TIFF compression, JPEG quality, colour depth, WMF/EMF/EXIF: niche; embedded images keep their own resolution |
| to_images | (AA extract image renders scanned pages too) | path, target_folder, dpi | `format`, `color` (color, gray, black_white), `pages`, `file_prefix`, `password` | - |
| get_properties | Get property: file name, title, subject, author | 11 standard properties | `include_custom` (custom properties and `file_name`) | - |
| page_count | (no AA equivalent) | - | - | - |

## OCR: `ooa.ocr` (Tesseract)

| Our action | AA action and options | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| read_image | Capture text by path: image or PDF; PDF password and pages; text after/before markers; trim; engine profile; locale | path, language, page_mode | `after_text`, `before_text`, `trim`, `scale`, `image_mode` (none, gray, black_white), region `x`, `y`, `width`, `height` | PDF input: convert with ooa.pdf/to_images (pages, password, black_white) first. ABBYY profile: Tesseract has languages and page modes instead |
| read_url **(new)** | Capture text by URL: image or PDF URL; filter; trim; profile | none | New action: `url` (http/https only), `language`, `page_mode`, `after_text`, `before_text`, `trim`, `scale`, `image_mode`, `timeout_seconds` | PDF URLs: download with ooa.rest and convert. Shared drives and FTP are not supported, as in AA |
| read_screen | Capture area: window, coordinates, wait before capture; filter; trim. Capture window: window title, resize, wait | x, y, width, height, language, page_mode | `after_text`, `before_text`, `trim`, `scale`, `image_mode`, `wait_ms` | Capture by window title and resizing: window handling lives in ooa.window (optional library module); use its bounds with read_screen's region |
| find_text_in_image | (no AA equivalent) | path, text, language | `occurrence`; output `count` | - |
| click_text | (no AA equivalent; image recognition offers click actions) | text, double_click, language | `occurrence`, `button` (left, right, middle), `offset_x`, `offset_y` | - |
| text_on_screen (condition) | - | text, language | - | - |

## Image recognition: `ooa.image`

| Our action | AA action and options | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| find_on_screen | Find image in window: image to find; source window; capture region; wait; repeat if not found; match percentage; match number; centre or offset; action | template, tolerance | `occurrence`, `position` (center or top_left), `offset_x`, `offset_y`, search region `x`, `y`, `width`, `height`; output `count` | Source window by title: see read_screen; repeat with waits: wait_for_image and click_image's `timeout_seconds` |
| find_in_image | (AA searches windows only) | image, template, tolerance | same options as find_on_screen; outputs `count`, `left`, `top` | - |
| click_image | Find image in window with a click action | template, double_click, tolerance | `button`, `occurrence`, `position`, `offset_x`, `offset_y`, `timeout_seconds` (keep looking), search region | Hover-only and keystroke actions: use ooa.desktop mouse and keyboard actions with the returned point |
| wait_for_image | Repeat if not found: times and wait between | template, timeout_seconds, tolerance | `interval_ms`, `until_gone` (wait for a picture to disappear), search region | - |
| image_on_screen, image_in_image (conditions) | - | template, tolerance | - | - |
| (none) | Find window in window: compares a captured window image with a source window | - | - | Same as find_on_screen with a captured picture; window capture by title belongs to ooa.window |

## Totals

- Existing actions and conditions reviewed: 115 (Excel 60, Excel desktop 4, Word 14, PowerPoint 15, PDF 11, OCR 5, Image 6).
- AA actions with no counterpart in these toolkits: 10; 7 were added as new actions, 3 skipped (screen updating on/off, OCR capture window, find window in window; reasons above).
- New optional inputs on existing actions: 225 (Excel 56, Excel desktop 14, Word 40, PowerPoint 31, PDF 32, OCR 20, Image 32). An input added to several actions is counted once per action.
- New outputs on existing actions: 14 (Excel 4, Word 1, PowerPoint 2, OCR 1, Image 6).
- New actions: 7 (excel/create_workbook, excel/go_to_next_empty_cell, excel/change_link, excel_desktop/update_links, powerpoint/create_presentation, pdf/extract_area_text, ocr/read_url), with 36 inputs between them.
- Behaviour kept: every new input is optional, and its default reproduces the earlier behaviour. Two defaults moved from the input to the session: the `header` input of get_all_cells, get_range, filter and sort_range is now empty by default and falls back to the open action's `header` (true by default), so existing agents behave as before. Java callers keep the earlier method signatures as overloads.
- Tests: ExcelPropertiesTest, WordPowerPointPropertiesTest (packages-office), PdfPropertiesTest (packages-pdf), ImageOcrExcelDesktopPropertiesTest (packages).
