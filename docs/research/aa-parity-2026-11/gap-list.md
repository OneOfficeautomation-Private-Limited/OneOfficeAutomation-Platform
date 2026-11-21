# AA option-by-option check, 21 Nov 2026 (all six groups)

Read-only from the user's AA Community Edition (latest packages, 173 packages) and AA docs offline; our side from the manifest and source. AA described in our own words. The reference for building the AA-parity release; AA is described in our own words.

| Group | AA commands checked | Gap rows |
|---|---|---|
| G1 flow, variables, prompts, forms | 137 | 61 |
| G2 files, data, database | 119 | 87 |
| G3 Excel, Word, PowerPoint, Microsoft 365, SharePoint, Teams | 269 | 127 |
| G4 desktop, browser, capture, terminal, scripts | 112 | 81 |
| G5 mail, Google, FTP, SaaS apps | 298 | 106 |
| G6 enterprise, cloud, AI, triggers | 213 | 97 |
| **Total** | **1,148** | **559** |


---

# G1 findings: flow, variables, prompts, forms vs AA (option by option), 21 Nov 2026
Source: live AA CE package data + our manifest/runner/Studio source. Index base: AA String positions are 1-based, ours 0-based (find, replace, substring, insert).

| AA package / command | Our command | Gap kind | What AA offers | Proposal | Size |
|---|---|---|---|---|---|
| If value check | core/if builder | missing option | text compare: match case (on), ignore line breaks | Studio ticks Match case, Ignore line breaks, date-only (runner already reads ignoreCase/ignoreLineBreaks/includeTime) | S |
| If Task Bot successful/unsuccessful | - | missing command | condition runs child, true on success | "Child agent succeeded" condition | S |
| If JavaScript/VBScript successful | - | missing command | script ran cleanly | "Script succeeded" condition | S |
| If DLL session exists | - | session | named session open? | generic "Session is open" condition (any toolkit) | S |
| If Gmail label exists | - | missing command | label exists | Label exists condition in ooa.google | S |
| If image: window in image, window in window, image in window | ooa.image conditions | missing option | captured window/area as picture; search limited to a window | window (+region) as picture and as search place | M |
| If legacy control active | desktop_ui/object_exists | missing option | enabled/focused, not only present | state choice exists/enabled/focused | S |
| If Datetime | datetime is_after/before/equal | different control | each side variable or typed date with pattern | typed date + pattern both sides | S |
| If Dictionary single value | dictionary/key_has_value | missing option | contains/not contains, greater/less by type | operator choice | S |
| If Utils record value by index | value check $row.col$ | missing option | column by position | column position in record refs | S |
| Loop types | core/loop | missing command | Teams (teams, channels, chats, messages), M365 calendar, M365 Excel rows, Slack (channels, messages, user msgs), Apple | iterators in microsoft365 + slack | M |
| Loop Excel advanced rows | excel/rows | missing command | row loop over desktop Excel session | check excel/rows with excel_desktop; else add | S |
| Loop n times | core/loop times | different default | count 10 | default count | S |
| Delay | core/delay | different control | Regular/Random radio, Random reveals From/To; ms or s (50 ms) | Fixed/Random choice revealing From/To | S |
| Wait for screen change | image/wait_for_screen_change | different control | window or screen; picker + drawn area | window picker + draw region | M |
| Wait for screen change / window | same, window/wait_for | different default | 5 s, no error on timeout; Window variable | keep defaults; accept Window variable | S |
| Task Bot Run | core/run_bot | missing option | repeat for a duration / until stopped | repeat mode times/duration/until stopped | S |
| Task Bot Run | core/run_bot | different control | delay tick reveals min/sec | optional tick | S |
| Prompt for file | desktop/prompt_file | different default | always multiple, list | multiple on by default? | S |
| Message box | desktop/message_box | different default | scroll bar after 30 lines | default 30 | S |
| Message box | same | different control | Close after tick reveals seconds (5) | tick instead of 0 | S |
| String Extract text | string/extract_text | missing option | All / exactly N / up to N chars | add exactly N | S |
| String Extract text | same | missing option | both markers: OR / AND | either/both choice | S |
| String Extract text | same | different default | remove line breaks on | default on | S |
| String Compare/Find/Replace/Split/includes | string/* | different default | case defaults differ; split not trimmed | document / align defaults | S |
| String Evaluate variable (was "-:") | - | missing command | fill $var$ in a text at run time | fill_template option "from agent variables" (read-only) | M |
| String To locale number | string/to_number | missing option | device locale | runner's locale choice | S |
| Datetime Assign/To string | datetime/parse, format | different values | ISO_OFFSET_DATE, ISO_ORDINAL_DATE, ISO_WEEK_DATE, ISO_OFFSET_TIME, ISO_TIME | add to lists | S |
| List Set item | list/set_item | missing output | replaced item | previous output | S |
| Record add/delete/update column | dictionary/put, remove | missing option | position / index, typed value | index + position | S |
| Currency real time / historical | currency/convert | different values / limit | real-time; any past date | intraday source or label; date for json source | S |
| Utils Get type | - | missing command | type name of value | "Get value type" | S |
| Trigger loop multi-handle | core/loop triggerloop | missing option | several Handle blocks, first wins | several handlers in one loop | M |
| Trigger loop handle types | ooa.triggerloop | missing command | email (Outlook/IMAP/EWS filters), M365 mail, folder events, window open/close, service state, form events, ServiceNow | add these iterators | M |
| Trigger loop file / process | file_arrives, process_starts | missing option | created/deleted/renamed/modified; process stops | event choices | S |
| Interrupt handler Scope+Handle | interrupts/add_rule | different control | scoped block, any condition, inline steps | Interrupt scope block with handler blocks | L |
| Interrupt handler condition | add_rule | missing option | any condition | toolkit condition as trigger | M |
| Forms Show/Hide window | forms show/hide (element) | missing command | whole form | Hide form / Show form | S |
| Forms Get / Set / dynamic area | forms/get, set, dynamic_area | missing option | table row index; append text; insert row; password from credential; dynamic area append, read-only, tooltip, height; Clear | add these | S |
| Forms Assign | forms/assign | different control | comma text options | accept comma text | S |
| Analyze Close | insights/close_transaction | different values / option | free-text status; send all variables | free text/variable status; include all variables | S |
| Fuzzy Match | fuzzy/similarity | missing option / values | text or file content; score 0-1 | file inputs; document 0-100 | S |

Cross-cutting: no FILE2 in G1; 12 plain CHECKBOX vs Yes/No + 3 ticks revealing fields; sessions: DLL session exists only.
aaMap fixes: List/Append -> datatable/add_column_from_list; Fuzzy -> fuzzy/similarity; Forms Show/Hide none; dynamic Clear none; add Interrupt, Analyze, NumberUtils, Utils, Loop record values mappings.

Ours has more: switch, parallel, state machine, goto, subflows, rich loops, nested condition groups + Test, Run Agent timeout/version/output map, ~20 extra string actions, number formatting/statistics, working days/time zones, list filter/sort, prompt timeouts/buttons, timer/click triggers, currency sources, 4 fuzzy algorithms, forms wait_for_event.

Upgrades from AA releases: Wait 4.15 watches interrupt rules during waits; Interrupt handler scope blocks (v.38); Loop 3.7.5 M365 calendar/Excel iterators; List 2.15 cloud file streams (low); Forms text append + dedupe options; macOS runners (noted).

Totals: 137 AA commands (27 packages) checked, 130 mapped; loop types 34 (15 missing), condition types 65 (15 missing); 61 gap rows (missing option 24, default 11, command 10, control 8, values 5, session 1, output 1, limit 1); sizes L1 M7 S53.

---

# G2 findings: files, folders, CSV/TXT, text, JSON, XML, Data Table, PDF, PGP, logging, printer, database (21 Nov 2026)
AA versions: File 6.16.2, Folder 6.13.0, CSV/TXT 2.15.1, Text file 1.8.0, JSON utilities 1.11.0, JSON Object Manager 1.0.9, XML 3.15.0, Data Table 4.18.1, PDF 3.11.0, PDFUtils 1.0.10, PGP 3.8.5, Logging 3.11.1, Printer 2.7.0, Database 5.6.7.
Ours everywhere: no regex in file fields (FileField: path, $var$, Browse); name filters glob only; Boolean = Yes/No/Variable; "is not" covers negative conditions; encodings/delimiters covered (auto-detect only for file/read*).

| AA / command | Ours | Kind | AA offers | Proposal | Size |
|---|---|---|---|---|---|
| File Copy | file/copy | regex | folder + name conditions, text or regex, match case | "Match names by: wildcard / regular expression" + match case on every many-files command | M |
| File Copy, Delete | file/copy, delete | missing option | parallel copy/delete | "several files at once" (More) | S |
| File Copy, Delete, Print, Folder copy/delete/rename | various | hidden sub-option | size at least/at most/exactly KB; date created or modified; last N days / between (inclusive) / on / before (inclusive) | shared size+date filter with these modes | M |
| File Create | file/write_text | missing command | empty file; overwrite or numbered name | file/create: if exists replace/keep both/fail | S |
| File Copy Control Room file | Browse > One CommandCenter | different control | repository path from variable at run time | "Get file from One CommandCenter" step | M |
| File Open | application/open_program_file | regex | open file found by folder + regex | same regex choice | S |
| File Print multiple | file/print_files | regex + filters | extension list or regex; size/date | regex + shared filters | S |
| File Rename | file/move | different control | new name only, same folder | file/rename or "New name only" | S |
| If File date | file/date_is | hidden sub-option | relative d/h/m/s; between inclusive; before inclusive | add | S |
| If File/Folder exists, size, date | file/*, folder/exists | missing option | wait up to N s | "Wait up to (seconds)" | S |
| If File extension | file/has_extension | different control | list or List variable | accept List variable | S |
| Loop each file in folder | file/files_in_folder | missing output | name + extension per pass | add name, extension, size, modified | S |
| Folder Zip | file/zip | regex | extension or regex | regex choice | S |
| Folder Copy / Unzip | folder/copy, file/unzip | different default | overwrite off | consider false | S |
| Folder Create | folder/create | missing option | replace existing or numbered | if exists: use / replace / numbered / fail | S |
| CSV/TXT Open/Read/Close/loop | csv/read, rows | session | named session, header, delimiter, trims, encoding | Open/Read/Close + loop by session; keep one-step | M |
| CSV/TXT Open | csv/read, rows | values | newline delimiter; file's own encoding | newline delimiter; Detect encoding | S |
| Text file Get text | file/read_text | default | encoding auto | default auto | S |
| JSON Start/End session (local/global/variable); Object Manager | json/* | session | named, shared with child bots | Start/End session (keep variable use) | M |
| JSON Insert/Update/Get nodes | json/insert_node, update_node, get_nodes | hidden sub-option | value-type choice; replace whole array; per-entry type | Value type select; list replace/add; per-entry type | S |
| JSON Validate | json/validate | option | file source; node path + type | file source; expected type per key | S |
| XML Start/End session, loop | xml/* | session | load once, edit, save | Start/End session with save | M |
| XML Start session | xml/* | option | drop white-space-only text | add | S |
| XML Get multiple nodes / Insert / Save / Validate | xml/* | output/option/default | table output; default namespace; start position; overwrite off; several XSD / DTD | add table output, default_namespace, multi-XSD + DTD; note defaults | S |
| Data Table Assign | datatable | option | empty table N x M | Create empty table | S |
| Data Table Change column type | change_column_type | control | 12 ready ISO/RFC formats | pattern picker | S |
| Data Table Insert row | insert_row | control | Record variable | accept Record | S |
| Data Table Write to file | write_to_file | control/default | row delimiter list; append default, ANSI | delimiter picker; keep our safer defaults | S |
| Data Table Write to file stream | - | missing command | file variable without path | temp file when no path | S |
| PDF Decrypt/Encrypt/Extract/Merge/Split | pdf/* | output/default | result dict; overwrite off | return path + pages; consider overwrite false | S |
| PDF Encrypt | pdf/encrypt | limit/default | each password optional; permissions off | at least one password; permissions off | S |
| PDF Extract field | read_form, extract_area_text | missing command | many named areas marked in a viewer -> dictionary | "Extract fields by area" with Studio area picker | L |
| PDF Extract image | pdf/to_images | hidden sub-option | X/Y dpi, colour depth, overwrite | colour depth + overwrite | S |
| PDF Extract text | pdf/extract_text | sub-option/default | keep more layout; plain default | reduce-loss switch | S |
| PDFUtils Set form fields | pdf/fill_form | option | XFA forms | XFA or clear error | M |
| PGP Create keys | pgp/create_keys | limit/values | name/email optional; RSA sizes | optional; add rsa2048 (not 512/1024) | S |
| PGP Encrypt/Decrypt | pgp/encrypt, decrypt | option | folders; add/remove extension | folder mode + pattern; extension options | M |
| Logging Log to Server | log/log_to_command_center | values/option | emergency/critical; log variables by type | add level; also log variables | S |
| Printer Remove / Set default | printer/* | control | printer list from machine | printer picker | S |
| Database Connect session | database/* | session | local/global/session variable | share with child agents; session variable | M |
| Database Connect | database/connect | option/control | {{name}} placeholders from vault; credential user; SQL instance; Oracle SID/service/TNS; driver JAR | placeholders + credential address; credential user; instance; 3-way Oracle; admin-approved JAR | M |
| Database Read from | query, export_to_file | option | export CSV inside read, no file when no rows, append | skip when no rows; append | S |
| Database Stored procedure | call_procedure | values/control | 16 SQL types; parameter grid | add 8 types; Studio grid | M |
| Database Bulk insert | bulk_insert | control/option | name-to-name mapping; timeout 1800 | mapping; timeout | S |

Cross-cutting: regex lacking in file/copy, delete, move, print_files, zip, application/open_program_file, csv/read, rows, xml path (no match case). ~35 AA tick boxes vs our Yes/No. Sessions missing: CSV, JSON (+Object Manager), XML; Database lacks per-session share + session variable.
Ours has more: list with sort, wait for file, Base64, temp file, file info, keep-both, special folders, CSV write/skip/types, full JSONPath, XML namespaces, Data Table filter/group/lookup/join, PDF OCR/tables/AES-256, PGP sign/verify, DB bound parameters + rollback + schema, printer list.
Upgrades: file streams as source (CSV 2.15.1, JSON 1.9.1, XML 3.14.2, Data Table 4.15.2, DB 5.6.1); DB global session + credential placeholders; bulk insert 1 GB + regex-special delimiters (check ours); File parallel copy/delete, match-case regex, 1-byte size fix; Logging to SIEM (check our forwarder); PGP key sizes.
Totals: 119 AA commands, 113 mapped, 87 gap rows (option 21, hidden sub-option 15, default 14, control 10, regex 8, values 7, session 4, output 3, command 3, limit 2).

---

# G3 findings: Excel, Word, PowerPoint, Microsoft 365, SharePoint, Teams (21 Nov 2026)
AA: Excel basic 6.23.15, Excel advanced 6.23.20, Word 1.3.0, PowerPoint 1.0.2, M365 Excel 2.25.2, SharePoint 2.16.1, M365 OneDrive/Calendar/Outlook, Teams, Outlook macOS 1.3.4.

| AA / command | Ours | Kind | AA offers | Proposal | Size |
|---|---|---|---|---|---|
| Excel basic Open | excel/open | option/default | opens .csv; header off | accept .csv (save back as csv); decide header default | M |
| Excel Close / Save | excel/close, save | default/option | save on close; read-only save error tick (off = skip) | save on by default or warn; "fail if read-only" off | S |
| Excel Delete cells | delete_cells | default | shift left | default left or note | S |
| Excel Find/Replace | find, replace | sub-option | search start/end: top, end, active cell, given cell | add | S |
| Excel Get multiple cells / row loop | get_range, rows | sub-option | all rows / rows N-M / range | rows from-to | S |
| Excel Get cell address / column name / row number / read row/column | cell_address, column_name, row_number, read_* | option | active cell; header text + offset | active-cell choices; by header + offset | S |
| Excel advanced Open / Create | open, create_workbook | option/control | edit password; skip hidden sheets; sensitivity label + carry-on; SharePoint URL | add; resolve pasted SharePoint/OneDrive URL | M |
| Convert to PDF / Run macro | excel_desktop export_pdf, run_macro | session/option | works on the open session incl. unsaved; active sheet; if exists number | take ooa.excel session; active sheet; if_exists | M |
| Create worksheet | add_sheet | default/option | fails if exists (ours replace=true clears!); name optional by position | replace off; name optional | S |
| Filter | excel/filter | default/option | real Excel filter in file; clear existing | "apply as Excel filter" mode; clear switch | M |
| Reads (cells, sheet, row loop) | get_* | default | displayed text default | decide + show choice | S |
| Row count / links / table column / protect / remove blank rows / sheet count / select / sensitivity / sort / write table | various | sub-option/output/default/option | sheet by position; table output; name optional; 4 more protect switches; address; hidden excluded; row/column/whole sheet; label from vault; sort table, only column, number/text; data rows only | add each | S |
| Save as (Excel, Word, PowerPoint) | save_as | default | no overwrite unless ticked | overwrite off | S |
| Word Open / pictures | word/* | regex/option | regex file pick; edit password | add | S |
| Word Find & replace text/picture, Format text | replace_text, find_replace_picture, format_text | option/sub-option/default | find-only count mode (ours: empty replace deletes!); paragraph/bookmark/page areas; match case off | replace switch; areas; default off | S |
| Word Insert picture/table/text | insert_* | sub-option/default | at cursor; headers off; start position | "where last insert ended"; note defaults | S |
| Word legacy | create_document etc. | option/control | first paragraph; path mode | optional | S |
| PowerPoint Create/Open/pictures | powerpoint/* | regex/option | regex pick; edit password | add | S |
| PowerPoint current slide | add_slide, copy_slide, insert_* | sub-option | current slide concept | track current slide in session | M |
| PowerPoint Find & replace, Insert table | find_replace_*, insert_table | option/default | find-only default; table title, height, lock ratio; headers off | replace switch; three options | S |
| M365 Excel Connect/Open/Close + active cell/sheet (~20 commands) | - | session | session with active cell/sheet | optional Excel-online session tracking them | M |
| M365 Excel misc | excel_* | default/values/sub-option/output/command | autofit whole sheet; copy sheet by position; shift left; first gap after cell; up/left; double underline + merge kinds; colour name; header address; table column; table row record; read from cell; sheet count; range formula; row loop | add each | S |
| M365 Connect/Disconnect (OneDrive, Calendar, Outlook, Teams) | connections | session | sign in once; Outlook session shared with child bots | optional M365 session inherited by later steps | M |
| OneDrive Assign/Check/Download/Export/Upload | onedrive steps | control/values/default | file variable; own rights; folder; overwrite off; upload into folder no replace | optional path; email optional; folder; safer defaults | S |
| SharePoint Authenticate | connections | session/option | site session; REST or Graph; on-prem URL | site session; on-prem REST client (NTLM/Kerberos) | L |
| SharePoint files/folders/lists | sharepoint steps | control/sub-option/command/option | site name + library; filters; Create/Delete/Update list (16 templates); list filters; column types; image/attachment commands (4) | add | M |
| M365 Calendar | calendar steps | control/default/sub-option/command | required+optional attendees; monthly/yearly patterns, repeat start; meeting loop with filters | add | S |
| M365 Outlook | mail steps | control/option/default | file variables; one status choice; HTML in forward/reply; missing-attachment switch; rich editor; local time zone | add | S |
| Teams | teams steps | sub-option/command/option | by name; team/channel loops; end date; chats list; chat messages | add | S |
| Outlook macOS (-> outlook_desktop) | outlook_desktop | session/command/option | mailbox once; delete/move/save all, save email, folder exists; cc/bcc/attachments/HTML on forward/reply; overwrite; loop filters | add to outlook_desktop | M |

Re-judged "-:": M365 Connect/Disconnect + SharePoint Authenticate + Excel-online Open/Close/active cell are gaps. Kept not needed: screen update, username+password sign-in, footer, macOS Outlook (AA ends legacy Oct 2026).
aaMap fixes: M365 Get cell address none; Get row number -> excel_column_name; Get table column none; Excel advanced Get multiple cells -> get_range.
Cross-cutting: regex pick missing in 7 Word/PowerPoint fields; 139 plain checkboxes vs Yes/No; sessions missing in all M365, SharePoint, outlook_desktop, excel_desktop with ooa.excel session.
Ours has more: pivots, charts, fill, text to columns, formats, OneDrive open/save; refresh data; open Office files; Word extract/templates; PowerPoint read/copy between; Planner, Entra ID, sharing; chunked upload; IMAP/POP3/SMTP.
Upgrades: .xlsm/.xltm round trip; stop link refresh on open; 2 GB files; Outlook session shared with child bots, NTLM proxy, mail to file variable; Outlook push triggers with filters; Teams web triggers; SharePoint webhooks + REST/Graph; Calendar local time zone; OneDrive assign file; Word multi-line alt text.
Totals: 269 AA commands, 239 mapped, 127 gap rows (option 40, default 25, sub-option 20, command 15, control 10, session 7, values 4, output 4, regex 2); L1 M15.

---

# G4 findings: desktop, browser, capture, terminal, scripts (21 Nov 2026)
AA: Recorder 5.11.0, Browser 4.4.4, Window 5.14.1, Terminal Emulator 4.18.1, DLL 6.12.2, Python 2.20.0 ... Ours: manifest + stepKinds.ts (WEB_BY_CONTROL, DESKTOP_BY_CONTROL).

| AA / command | Ours | Kind | AA offers | Proposal | Size |
|---|---|---|---|---|---|
| Capture Table (desktop) | desktop_ui get_table, get_children | option | cell read/find/count/set/click by position or text | cell actions for Table/DataGrid | M |
| Capture Table (web) | web table actions | option | set/click cell found by text; click kinds | set_cell_by_text, click_cell_by_text, click kind | S |
| Capture Menu / Tree / Tab / ComboBox / ListView | web + desktop | option | count, selected index/text, select by index/text, expand node by text, expand drop-down | select/selected/total/expand on web and desktop (menu path File > Save) | M |
| Capture item index | web select_by_index 0-based vs get_selected_index 1-based | default | 1-based both | select by index from 1 | S |
| Capture Checkbox/Radio (desktop) | check/uncheck/toggle | option | get status; radio select | add | S |
| Capture Calendar/Date picker, Slider, Scroll bar, Toolbar, Window focused control | none / partial | option | set date/month/year/range; slider position; scroll line/page 4 directions; toolbar button by index + context item; focused control id | add these desktop/web actions | M |
| Capture Client children | web get_children_text | option | names + values | name/value pairs | S |
| Capture via Java | java_apps (5 actions) | option | full per-type actions, credential set text, tables/lists | Java up to Desktop action list; Pick captures Java | L |
| Set/Append text all technologies | web/desktop set_text, type_text, type_in_background | option | special keys in brackets; credential | "Read special keys" tick, one syntax ([ENTER] vs {ENTER} today) | M |
| Password text | set_text | limit | credential only | warn on plain text | S |
| Debugger click | web click_method | values | debugger click for strict CSP | click_method "debugger" | S |
| Timeouts / Object exists | web 30 s, desktop 10 s; object_exists no wait | default/option | 15 s object + 15 s page; exists waits 15 s | consider 15/15; wait_seconds on desktop object_exists | S |
| Structured data extraction | extract_table | option | "suggest columns" with preview | Studio Suggest columns | M |
| AISense + Variable anchor | ai_screen, ocr | missing command | find field by label next to it on remote screen | OCR anchor steps: read/type/click next to a label | M |
| Browser Open | browser/open | values | default browser | browser = default | S |
| Browser JS/source/close/back | browser/* | option | timeout 240 s | timeout_ms | S |
| Browser Call JS function | arguments List | control | type per argument | optional type per argument | S |
| Window all + Assign | window/* title/process_id | control | window variable target | accept Get window details dict / $variable (re-judged "-:") | M |
| Window exists conditions | exists, is_active | option | keep trying N s | wait_seconds | S |
| Window Close all | close_all | limit | all except keep list | title optional | S |
| Simulate keystrokes | desktop/type_text | default | special keys always on, 10 ms | special keys on for new steps | S |
| Screen capture | capture_screen, window/capture | default | overwrite off | consider false | S |
| Open program/file | open_program_file wait_seconds | control | timeout = wait for program window | "wait for its window, up to N s" | S |
| Application is running | is_running | option | full path; keep trying | full path; wait_seconds | S |
| App integration scrollable text / window text | desktop_ui | option | text at a point; all window text | get text at point; Get window text | S |
| Image Recognition | image/* | option/default | search inside window/area; 95% match; 10 tries 300 ms; design-time picture capture + preview; conditions with wait/occurrence/click-if-found | window option; tolerance 5 or %; ~3 s default; Capture picture + Test; richer conditions | M |
| OCR by URL / path / capture | ocr/* | option/control/default | PDF by URL with password + pages; image or PDF in one; 300 ms wait | PDF support; one action; 300 ms | S |
| DLL .NET Open/Run/Close | dotnet (no session) | session | assembly session keeps state | Open assembly / call in session / close | M |
| DLL parameter types / names / details / session exists | dotnet | values/option/command | typed params incl. DateTime/byte/arrays/List/Dict/credential; named; read assembly; session condition | typed + named args; Read assembly; session-open condition | M |
| System env var / Service | names typed | control | pick from machine list | design-time list | S |
| Ping condition | host_reachable | option | keep trying | wait_seconds | S |
| SNMP | network | option/values | ASCII/Hex; log file; byte/decimal-byte/NULL types; walk scope All/Within | add | S |
| Terminal Connect | terminal/connect | option/values/sub-option/control | Telnet for VT/ANSI/Linux/SCO; linux + scoansi + auto-wrap; VT encoding ANSI/DBCS/UTF-8; custom DA; TN3270E extended + E models; TN5250E + extensions + 11 models; code page list; host/user credential | vt-telnet; types; encoding; DA string; E options; models; pick list; credentials | M |
| Terminal Show/Hide | no-ops | option | visible terminal window | live screen viewer (attended) | L |
| Terminal prompt / wait / Get text / Send / key map / field index | terminal/* | option/default/control | session prompt; wait before sending; last line, scroll-back, rectangle, hidden text; Enter after text default; raw key mapping; 0-based field index | add; note | M |
| Python Open/Execute/Function/Close | run_python(_function) | session | process kept; state; shared with child bots (2.19) | Open session / run / call / close | L |
| JavaScript, VBScript Open/Run/Close + success condition | run_javascript/vbscript | session/command | named session; script succeeded condition | same session model; condition | M |
| UI Agent Run | web_agent/run | option/control | several goals; overall 120 s; proxy user credential | goal list; time limit; credential | S |

Cross-cutting: no FILE2 in G4; ~14 ticks vs Yes/No; sessions missing Python, JavaScript, VBScript, .NET DLL; window variable / credential host+user / credential DLL params.
Ours has more: window wildcard/regex titles, alternatives, background typing; web hover/drag/upload/highlight/record; MSAA legacy; key hold/glide/block input; command sessions; image compare; Windows + Tesseract OCR; remote services; PowerShell; COM/Java/C#; web agent masking.
Upgrades: Recorder 5.10.1 XPath for desktop controls; 5.8.0 multi-profile tabs, RDP/VDI capture; AI fallback when object not found; CEF apps JS; Browser 4.4.2 PiP; Python 2.19 shared session; Terminal SCO ANSI/TN5250E/linux auto-wrap/key remap/DA; OCR 2.18.2 PDF pages; Application 3.11.2 timeout; UI Agents per-goal outputs + AI logs; pop-up handling (up to 5) in image/OCR/screen/mouse; unresponsive window 30 s message.
Totals: 112 AA commands (22 packages), 99 mapped, 81 gap rows (option 40, default 12, control 10, values 6, command 4, session 4, sub-option 3, limit 2).

---

# G5 findings: mail, Google, FTP, SaaS connectors (21 Nov 2026)
AA: Email 3.33.8, Gmail 1.0.6, G-Suite 2.8.0, Google Calendar 2.22.4, Drive 2.20.2, Sheets 2.18.3, Ftp/Sftp 2.15.1, Slack 1.0.2, Box 1.0.3, DocuSign 1.0.0, Zendesk 1.0.4, Asana 1.1.2, Jira 1.6.6, GitHub 1.4.2, Azure DevOps 1.1.4 (+ old betas).

## Checks from code
- C1 PROBABLE BUG: EmailPackage never sets mail.imap.peek -> Read email (mark_read=false), Each email with_body, Save attachments, Save all attachments (mark_read=false) can mark unread mail read; step still reports unread. Test with GreenMail; fix peek=true, set flag only when asked.
- C2 old map errors: Box Get folder details != get_file_info; Slack loops are not loops in ours; Azure DevOps test run statistics partial.

## Gaps (condensed)
| Area | Ours | Kind | AA offers | Proposal | Size |
|---|---|---|---|---|---|
| Sessions: Gmail, G-Suite, Calendar, Drive, Sheets (+Open/Close), Slack, Box, DocuSign, Zendesk, Asana, Jira, GitHub, Azure DevOps | per-step token | session | Connect/Disconnect from OAuth/token/PAT, reused | optional Connect step naming a saved connection; later steps default to it | M |
| Email Connect | email/connect | default/control | 120 s wait; sign-in choice password / OAuth PKCE / client credentials / managed connection | timeout 60-120 s; "saved sign-in connection" label | S |
| EWS Connect | exchange/connect | option | basic/OAuth kinds, Test connection, domain, version | token from saved OAuth connection; Test; domain | M |
| Outlook desktop | outlook_desktop | control | pick mailbox | mailbox/account option | S |
| Email actions in loop | email/exchange steps | control | act on the loop's current email | default id/folder to current email | S |
| Change status | email/mark | control | Read/Unread choice | show as Read/Unread | S |
| EWS folder exists / delete all / move all / save all attachments | exchange | missing command | on EWS | add four commands | M |
| Delete all / Move all / loop | email | option/control/sub-option | several folders with ; and *; one any/read/unread choice; "quoted" exact subject | folder list + patterns; one status choice; quoted exact match | M |
| Save all attachments / loop | email | option/default | read/unread/both; marks read unless keep status | status choice; mark_read on loop; real keep status (C1) | M |
| Save email | save_email_as_eml/msg | control | folder + optional name from subject | accept folder, name from subject | S |
| Send/Reply/Reply all/Forward (Email + Gmail) | send steps | sub-option/option | body type plain / HTML code / rich editor; skip missing attachments; character set; send timeout 120 s; footer; skip invalid addresses; reply-all subject; EWS reply-to + importance | three-way body type + rich editor; skip options; encoding; timeout; footer field; subject; reply-to/importance | M |
| Mail loops (Email + Gmail) | emails, gmail_messages | option/output | local time zone; CC/BCC/sent/attachment count; oldest first (Gmail) | local-time option; more fields; oldest_first | M |
| Gmail delete/move/save all | gmail_* | option/limit/default | several labels; no cap; keep other labels; INBOX default | label list; 0 = no limit + warning; keep_other_labels; INBOX default | S |
| Gmail label exists | gmail_label_exists | control | If condition | condition form | S |
| Calendar Create event | calendar_create_event | default/control/option | runner's time zone; time hidden for all day; recurrence list | default runner zone; hide time; list | S |
| Drive Assign file / Download | drive_download | command/control/default | file variable streamed; by path or id; folder + new name; overwrite off | optional save path (temp); Id/Path choice; folder; overwrite false | S |
| Drive item commands | drive_* | control | visible by-path / by-id choice | Id/Path choice everywhere | S |
| Drive Check / Create permission, Delete | drive_* | values/output | own rights incl. delete; owner + transfer; deleted flag | optional email + delete; owner; output | S |
| Sheets Open/current sheet/current cell | - | session/command | Open once, current sheet, Go to cell, "active cell" on ~12 commands | Open spreadsheet session with current sheet + cell | L |
| Sheets read row/column, formula, colours, insert/delete, create sheet/workbook | sheets_* | option/control | start cell; range formula; colour names; range for rows/cols; default sheet name; folder path | add each | S |
| FTP Connect | file_transfer/connect | sub-option/option/control | fields per protocol; anonymous; FTPS encryption choice | per-protocol fields; anonymous; choice | S |
| FTP Change folder / Get files / Get folders / Put files | file_transfer | option | parent; wildcard + date filter + transfer type; created filter; several files | add | M |
| Slack loops | slack | command/control | channels by type; channel messages by dates; conversation by members; user's conversations | List channels, history by dates, by members, my conversations + loops | M |
| Box | box | command/option/control | folder details; can preview; folder destination | get_folder_info; allow_preview; folder | S |
| DocuSign | docusign | command/control | PDF/ZIP as file variable; output folder; status list | optional save path; folder; status choices | S |
| Zendesk | zendesk | command/default/option | file variable; overwrite off; ticket field type filter | temp file; default; type filter | S |
| Asana | asana | command/option/values | file variable; project owner/due; several projects; duplicate parts multi-choice; fields; archived three-way; completed_since; team users | add each | S |
| Jira | jira | sub-option/option/control/default | issue type list with parent for sub-task; lead by email, assignee type, category; category by name; expand/key filter | add | S |
| GitHub | github | option | PR source branch filter | add | S |
| Azure DevOps | azure_devops | control/option/command/output/default | repo by name, local YAML; per-type suite fields; test case priority 2 + steps table; Get test suite; outcome counts; active-only default; owner filter; Timeout/Aborted outcomes | add each | M |
| Box/Zendesk/Asana upload | upload steps | regex | folder + regex | global regex file choice | (global) |

Cross-cutting: ~29 ticks vs Yes/No; variables allowed in AA for some yes/no fields; file-variable (no save path) pattern across Drive, DocuSign, Zendesk, Asana.
Ours has more: email find/read/folders, filters, .msg, receipts, shared mailbox; Exchange impersonation; Gmail search; Calendar list/update/Meet; Drive export/list; Sheets append/clear/formats; Google Docs; FTP sync/raw; Slack threads/blocks; Box collaborators; DocuSign embedded signing; Jira JQL/transitions; GitHub releases/workflows; Azure DevOps work items/PRs/builds.
Upgrades: Email 3.33.5 send timeout, 3.31.0 save-email name; Gmail label condition; Drive 2.19.0 assign file + streaming upload; Azure DevOps 1.1.4 suite/run stats, 1.1.3 OAuth (PATs retired 1 Dec 2026: relabel field); Zendesk field type filter; FTP 2.11.0 session shared with child bots (check ours); web triggers for Jira, GitHub, Google Calendar (we have none).
Totals: 298 AA commands (264 newest + 34 beta); newest 227 mapped, 29 session, 8 unmatched; 120 gaps (option 41, control 23, command 15, session 14 pkgs, default 10, sub-option 6, values 4, output 3, regex 3, limit 1).

---

# G6 findings: our commands vs AA 360 (enterprise apps, cloud data, web services, AI, documents, workload, triggers)

Live AA data (Community Edition, latest packages) read option by option; triggers from AA docs (live data has no trigger fields). Rows marked (none) or size - are checked-and-covered notes, not gaps.

## 1. Gaps

| AA package / command | Our command | Gap kind | What AA offers (our words) | Proposal (our words) | Size S/M/L |
|---|---|---|---|---|---|
| Active Directory / Connect, Change user password, Update account options | connect, set_password, update_account_options | different control | The sign-in user (Connect) and the target user name (password change, account options) can be picked from the credential vault, not only typed | Let bind_user and username accept a vault credential as well as text | S |
| Active Directory / Create computer, Create group, Create organizational unit, Create user | create_computer, create_group, create_ou, create_user | limit/validation | No place to name: new objects go under the parent path given at Connect | Make container_dn / parent_dn optional and default to the session's base_dn | S |
| Active Directory / Create group | create_group | different default | Group scope starts on domain local | Keep global (the Windows default) but say so in the field help; or switch to domain local to match | S |
| Active Directory / Create user | create_user | hidden sub-option missing | When "no change needed at next sign-in" is picked, a further tick stops the user changing their own password | Add cannot_change_password to create_user, shown only when must_change_password is off | S |
| Active Directory / Create user | create_user | different control | Password options sit inside the "password settings" tick, and the never-expires / can't-change ticks appear only when "no change at next sign-in" is chosen | Move must_change_password and password_never_expires inside the "Set a first password" tick; hide never-expires while must-change is on | S |
| Active Directory / Delete user, Disable user account, Enable user account, Get user property, Set user property, Rename user, Update user details | delete_user, disable_user, enable_user, get_user, set_attribute, rename_user, update_user_details | hidden sub-option missing | A choice to find the user by full (display/common) name or by logon name | Accept the common name and a DN as well as sAMAccountName/UPN (optional "find by" choice: logon name, full name, DN) | S |
| Active Directory / Add users to group, Remove user from group | add_to_group, remove_from_group | different values | Each user row can be given by name or by full LDAP path; group by name | Accept a DN or common name for each user (today only sAMAccountName/UPN) | S |
| Active Directory / Add users to group, Remove user from group, Move computer, Move organizational unit, Connect | add_to_group, remove_from_group, move_computer, move_ou, connect | missing option | Design-time buttons browse the live directory to pick users, the group, the computer / OU and the target container, and to build the parent path | Add a Studio directory browser (uses the Connect details) that fills DN fields | M |
| Active Directory / Get all users of a group, Run query | get_group_members, run_query | different values | Result is a plain list, with a choice of object names or LDAP paths | Add a "return as" choice: table (today), list of names, list of DNs | S |
| Active Directory / Get computer / group / organizational unit / user property | get_computer_property, get_group_property, get_ou_property, get_user | missing output | One named property comes back directly as text | When exactly one attribute is asked, also return it as a plain `value` output | S |
| SAP / Connect | log_on | different control | The SAP user name can come from the credential vault | Let user accept a vault credential | S |
| SAP / Send virtual key | send_key | different control | Keys are picked by name from a list (Enter, F1-F12, Shift/Ctrl combinations, Page up/down, Ctrl+letters) | Show a named key list that stores the SAP key number (keep number entry for variables) | S |
| SAP / Get table cell text, Set table cell text | get_table_cell, set_table_cell | different default | Row and column are numbers starting at 1 | Accept 1-based column/row numbers or say clearly ours is 0-based; add a "count from 1" note in help | S |
| SAP / Export table | export_table | different default | Header row is off by default; result can go straight into a table variable instead of a file | Keep ours (header on, table always returned); no change | - |
| SAP BAPI / Connect | connect | missing option | A gateway host can be given with the message-server (group) logon | Add gateway_host in More (today only via properties) | S |
| SAP BAPI / Connect | connect | hidden sub-option missing | With secure network logon (SNC) chosen: a protection-level choice (authentication only, integrity, privacy, default, maximum) and a tick to also sign in with user and password | Add snc_protection (default maximum) and show user/password under SNC only when that tick is on | S |
| SAP BAPI / Connect | connect | different control | Explicit "how to sign in" choice (user and password / SNC); host names can come from the vault; the connector library files are picked in the step | Add a sign-in choice that shows the right fields; keep the runner-wide JCo folder but allow an optional jco_folder override in More | S |
| SAP BAPI / Create function, Set field value, Get field value, Get structure, Get table, Run function | call_function, get_function_info | different control | A call is built step by step under an alias: set single fields (incl. new table rows, structures), run, then read a field, a structure or a table straight into a table variable | Keep the single call; add a Studio form that reads get_function_info and lets the user fill imports/structures/table rows field by field; add outputs that pick one structure/table into its own variable | M |
| SAP BAPI / Run custom workflow | (none) | missing command | Ready-made workflows: create a sales order (VA01) and update a sales agreement (VA32) with a parameter form | Ship two templates (agents) built on call_function + commit for these two cases | M |
| SAP BAPI / Run standard workflow, Run custom workflow advanced | (none) | missing command | A design-time builder that browses the SAP system for BAPIs, chains them into a workflow and fills parameters; begin/end sequence and commit ticks | Add a Studio BAPI browser (search functions, show parameters) that generates call_function steps; ours already keeps one stateful session so begin/end sequence is not needed | L |
| Salesforce / Authentication | authenticate | different control | Production / Sandbox choice for the sign-in site | Replace the login_url text with a choice Production / Sandbox / Custom (Custom shows login_url) | S |
| Salesforce / all commands | all actions | session | Sign in once into a named session; later steps only name the session | Add an optional session: authenticate stores url + token under a name; other steps accept session instead of instance_url + access_token (saved connections stay) | M |
| Salesforce / Assign file, Get attachment(s) | get_attachments | hidden sub-option missing | Pick one file by its content document id (Assign file returns it as a file variable; Get attachments can filter by it); filter by file extension | Add a content_document_id filter (returns that one file as `path`) and an extension filter next to name_pattern | S |
| Salesforce / Upload file attachment | upload_attachment | different default | Visibility starts on "all users" | Default visibility to AllUsers so the panel shows what happens | S |
| ServiceNow / Get Attachment(s) | list_attachments, download_attachment | hidden sub-option missing | Find attachments by attachment id, by record (table + id), or by an encoded query with a limit; download them all to a folder in the same step | Add a "find by" choice (record / attachment id / query + limit) and an optional download folder to list_attachments | S |
| ServiceNow / Authentication | authenticate, all actions | missing option | API version can be chosen | Add api_version in More (v1/v2/latest) for table and attachment calls | S |
| ServiceNow / all commands | all actions | session | Sign in once into a named session (OAuth client + user) and reuse it | Optional session name: authenticate keeps the token; steps accept session instead of sign-in fields | M |
| Okta / Connect, Disconnect | (none) | session | Connect once to the Okta domain with a stored OAuth2 connection, get a session, disconnect at the end | Add connect/disconnect that keep org_url + token under a session name; steps accept the session | M |
| Okta / Create user, Update user | create_user, update_user | missing option | Phone is a field; update has named fields (user name, email, first and last name, phone), each optional | Add phone to create_user; add login, email, first_name, last_name, phone fields to update_user beside profile_fields | S |
| Okta / Add user to group, Remove user from group | add_user_to_group, remove_user_from_group | missing output | Returns true/false for success | Add a `done` Boolean output | S |
| Workday / Get Integration Event | get_integration_event | different control | Id kind is a choice: Workday id (WID) or background process instance id | Make id_type a drop-down (WID / Background_Process_Instance_ID) that still allows a variable | S |
| Workday / Authentication + all commands | authenticate, get_report, launch_eib, get_integration_event | session | Sign in once (environment, tenant, vault user and password) into a session used by every step | Optional session: a sign-in step keeps host/tenant/user/password (or token); steps accept the session | M |
| Databricks / Connect, Disconnect + all commands | all actions | session | Connect once (host + OAuth2 connection or personal access token) into a session; Disconnect ends it | Optional session: a connect step keeps workspace_url + token; steps accept the session | M |
| Databricks / Connect | all actions | missing option | Sign-in by an OAuth2 connection (service principal), not only a personal token | Add "Sign in with: personal access token / OAuth service principal (client id + secret)" | S |
| Databricks / Download file, Upload file | download_file, upload_file | different default | Overwrite is off by default (safer) | Default overwrite to false on both | S |
| Databricks / Upload file | upload_file | regex | Source file can be picked by folder + name pattern | Covered by the global file-field regex work (local_path) | S |
| Snowflake / Connect, Disconnect + all commands | all actions | session | Connect once (account + OAuth2 connection) into a session | Optional session keeping account_url + token (+ token_type); steps accept it | M |
| Snowflake / Bulk export | export_results | different values | Formats CSV, JSON, Parquet; compression none, auto, gzip, snappy | Add parquet format and auto/snappy compression | M |
| Snowflake / Bulk load | (none; insert_rows loads a data table) | missing command | Load a CSV or Parquet file into a table, skip header rows, and choose what happens on bad rows: abort, continue, skip file, skip file after N bad rows or N percent | Add "Load file into table" (upload to a stage + COPY INTO) with format, skip_header and on_error (with row / percent thresholds) | M |
| Snowflake / Delete rows, Update rows | execute_statement | missing command | Delete or update rows by a key column and a list of key values (update takes a column-value dictionary); returns rows changed | Add two small helpers delete_rows / update_rows that build the SQL with bindings | S |
| Snowflake / Execute stored procedure | execute_statement | missing command | Run a procedure by name with a dictionary of inputs | Add call_procedure (name + parameters) that builds CALL with bindings | S |
| Kafka / Connect | all actions | different control | Connect straight to the brokers (bootstrap servers) with a security choice: plain, SASL plain, SASL over TLS (PLAIN / SCRAM-256 / SCRAM-512 + trust store), or TLS with trust store and key store | Add native broker connection (Kafka client) beside the REST proxy: bootstrap_servers + security protocol choice with its hidden fields | L |
| Kafka / Connect, Disconnect | all actions | session | Connect once and reuse the session | Optional session for both the REST and the native connection | M |
| Kafka / Connect | (none) | regex | Trust store and key store files can be picked by folder + name pattern | Covered by the global file-field regex work once store fields exist | S |
| Kafka / Consume message | read_messages | missing option | Save the messages read straight to a CSV file | Add an optional csv_file to read_messages | S |
| Kafka / Consume message | read_messages | different default | Reads 10 messages and waits 5 seconds by default | Keep 100 / 10 s (no change) or align; note only | - |
| AWS EC2 / Get instance status | get_instance_status | different default | Stopped instances are left out unless the tick is on | Default include_stopped to false to match | S |
| AWS Comprehend NLP / Key Phrases, Detect language, Named Entities, Get Sentiment | comprehend_* | (none) | Keys and region typed on every step | Fully covered (ours signs in once with vault keys) | - |
| REST Web Services / Delete, Get, Patch, Post, Put, Get file stream | request, download_file, upload_file | different control | The whole address can be taken from a vault credential (for addresses that carry a key) | Allow url to be a vault credential (masked in logs) | S |
| REST Web Services / all methods | request, download_file, upload_file | missing option | Sign-in "as the Control Room user running the bot": sends that user's platform token | Add sign-in choice "One CommandCenter token of the run" for calls to our own API | S |
| REST Web Services / Post, Put, Patch, Delete (JSON, XML, text, HTML, custom body) | request | hidden sub-option missing | Body typed as text with a substitution table: named placeholders are replaced by vault secrets at run time; or the whole body taken from a vault credential | Add "replace in body" pairs (name -> credential) for text bodies and allow body = credential; JSON already accepts secrets inside | S |
| REST Web Services / all methods | request | different control | Each header / form / multipart row has its own on/off tick | Optional: let a row be switched off without deleting it (Studio pairs editor) | S |
| REST Web Services / Get (download file) | download_file | different default | Overwrite of an existing download is off by default | Default overwrite to false | S |
| REST Web Services / all methods (key store), Post/Put/Patch (multipart file rows, binary file) | request, upload_file | regex | Key store and body files can be picked by folder + name pattern | Covered by the global file-field regex work (client_certificate, body_file, multipart files) | S |
| SOAP Web Service / SOAP Web Service | send, read_wsdl | hidden sub-option missing | Request built from the WSDL: load it by address or from a file, pick service, port and operation, then fill a name/value parameter table (values may be vault secrets, each row switchable); namespace, prefix, SOAP action and "qualify elements" can be set; endpoint address can override the WSDL one | Add a "Build from WSDL" body mode to send: wsdl (address or file), service, port, operation, parameters (pairs) + namespace/prefix/action/qualify in More; ours writes the envelope | M |
| SOAP Web Service / SOAP Web Service | send | missing option | A design-time "build request" window lists services, ports, operations and fills the parameter table; a "build XPath" window shows a sample answer to click a node | Studio helpers on send: operation picker from read_wsdl, and an XPath picker over a test answer | M |
| SOAP Web Service / SOAP Web Service | send | hidden sub-option missing | Raw XML with {{name}} placeholders replaced by vault secrets (substitution table); or the whole body from a vault credential | Add "replace in body" pairs (name -> credential) and allow body = credential | S |
| SOAP Web Service / SOAP Web Service | send | hidden sub-option missing | With an XPath: return the node values (as one string joined by none, pipe, semicolon or a custom delimiter, or as a list), the inner XML, or the outer XML (default) | Add xpath_output choice: values list (today) / values joined by delimiter / inner XML / outer XML | S |
| SOAP Web Service / SOAP Web Service | send | missing option | A certificate file (.cer) can be given besides the key store (.p12 + password), e.g. to trust a private certificate authority | Add trust_certificate (.cer/.pem) to send and REST request, so private CAs work without insecure_tls | S |
| AI / AWS SageMaker: Authentication; Generative AI / Amazon Bedrock: Authenticate | genai (aws_bedrock, aws_bedrock_sagemaker) | missing option | Temporary AWS keys: a session token can be given with the access key and secret | Add aws_session_token (vault) for Bedrock and SageMaker | S |
| AI / Invoke SageMaker Endpoint | genai prompt (aws_bedrock_sagemaker) | missing command | Call any SageMaker endpoint with raw content and a content type (JSON, JSON lines, CSV, protobuf, image, custom), plus endpoint headers: custom attributes, explanations, inference id, target container, target model, target variant; a button lists endpoints and regions | Add genai/invoke_endpoint (raw body + content_type + those headers in More, answer as text); ours only sends chat messages | M |
| AI / Hugging Face: Serverless Inference | genai (huggingface) | missing command | Call any Hugging Face model by id with a raw JSON payload and an optional data file (image/audio) | Add a raw inference action (model id, payload, data file) beside chat; ours only uses the chat router | S |
| Generative AI / all providers | genai | session | Authenticate / Connect once per provider (Bedrock keys, Vertex sign-in, Azure endpoint + key + version, OpenAI key) into a session | Covered in spirit by saved model connections; optionally let a step reuse one sign-in by session name | S |
| Generative AI / all model pickers | genai | different control | Model picked from a provider list (current and deprecated marked) with "other version" free text | Show a per-provider model drop-down that still allows free text / variable | S |
| Generative AI / Azure OpenAI: Authenticate | genai (azure_openai) | missing option | API version can be set | Add api_version in More for azure_openai (today fixed in code) | S |
| Generative AI / Vertex AI: Connect, Chat, Prompt, MultiModal | genai (google_vertex) | different control | Project and location picked separately (location list); publisher choice Google or a third-party publisher; sign-in by user OAuth (email, client id/secret, redirect) or service-account key or stored OAuth connection | Add project + location fields that build base_url; allow third-party publisher models; add user-OAuth sign-in option | M |
| Generative AI / Vertex AI: MultiModal Prompt | prompt_with_images | missing option | Send a video file instead of pictures (up to 10 pictures otherwise) | Add a video input for Gemini models | S |
| Generative AI / OpenAI + Azure OpenAI: MultiModal Chat | prompt_with_images, chat | missing option | Picture given by web link or by file; picture detail level auto / high / low | Accept image URLs in images; add image_detail (auto/high/low) in More | S |
| Generative AI / OpenAI + Azure OpenAI: Chat, Prompt | chat, prompt | missing option | Logit bias dictionary; end-user tag on chat (ours has end_user only on prompt) | Add logit_bias in More; add end_user to chat | S |
| Generative AI / Vertex AI: Chat | chat | missing option | Context text plus example input/output pairs (few-shot) | Add examples (pairs) in More, sent as earlier turns | S |
| Generative AI / Stability AI: Text to Image | generate_image | missing option | Prompt strength, generation steps and seed for the picture model | Add prompt_strength, steps, seed in More (used where the model supports them) | S |
| Generative AI / AI21 Labs: Chat, Prompt | chat, prompt | missing option | Count penalty and "penalise special tokens" ticks (whitespace, numbers, emojis, punctuation, stop words) | Low value (AI21 specific); pass through a provider_options dictionary instead | S |
| Generative AI / OpenAI: Run Assistant, Run Assistant Function | (none; ai_agent covers agent loops) | missing command | Run an OpenAI assistant by id (load list, model override, instructions, code interpreter, file search, function definition, files or file ids, metadata) and answer its function calls | OpenAI is retiring the Assistants API; instead add OpenAI built-in tools (file search, code interpreter) to genai prompt via the Responses API | M |
| OpenAI (old package) / ChatGPT, GPT-3, Azure ChatGPT, Azure GPT-3 | chat, prompt | (none) | Older chat/completion calls with key, model/deployment, max length, temperature | Covered (ours chat/prompt with openai / azure_openai) | - |
| AI Agent (Local) / Run | ai_agent/run | missing option | Goal / task can come from a file instead of typed text | Add a "goal from file" choice (text / file) | S |
| AI Agent (Local) / Run | ai_agent/run | different default | Up to 50 turns by default | Raise max_steps default (8 is low for multi-tool tasks) or explain the choice in help | S |
| AI Agent (Local) / Run | ai_agent/run | missing option | A workspace folder the agent reads and writes in | Add workspace_folder; tools that touch files are limited to it | S |
| AI Agent (Local) / Run | ai_agent/run | missing option | Built-in tool ticks: spreadsheets (CSV/Excel read-write, on), PDF reader incl. scanned (on), web content (read page, call API, download), computer use (click/type on screen), browser use; shell and Python hidden | Add built-in tool groups as ticks (spreadsheet, PDF/OCR, web fetch, desktop control, browser) mapped to our toolkits, so no helper agents are needed | M |
| AI Agent (Local) / Run | ai_agent/run | missing option | Custom tools from local MCP servers (config file, "discover tools" button, table of chosen server/tool/type/command) | Allow local (stdio) MCP servers and a discover-tools picker; today only one HTTP MCP server URL | M |
| AI Agent (Local) / Run | ai_agent/run | missing option | Capabilities ticks: memory kept across runs, verbose log (reasoning, tools, tokens), check that the goal was met | Add remember_between_runs (keyed store) and verify_goal (extra model check); verbose is covered by trace | M |
| AI Agent (Local) / Run | ai_agent/run | missing option | Named credentials handed to the agent's tools (never shown to the model) | Add credentials (name -> vault credential) passed to tools/agents by reference | S |
| AI Skill / Execute | ai_skills/run | (none) | Pick a saved AI Skill and fill its inputs | Covered (ours adds grounding and model override) | - |
| Document Extraction / Update document data | (none) | missing command | Write corrected field values back to a processed document, from JSON text or a dictionary | Add documents/update_result (document_id + fields as dictionary or JSON) | S |
| Document Extraction / Download data | list_finished, mark_collected | missing command | Save a document's results to a folder as CSV or JSON, or download the original input file (kind chosen from a list or a variable), then optionally delete the server copy (on by default) | Add documents/download (document_id, kind csv/json/original, folder, delete_after) | S |
| Document Extraction / Extract data | send | missing option | Pin the version of the extraction setup (learning instance version), by list or by variable | Add process_version in More | S |
| Document Extraction / Extract data | send | hidden sub-option missing | Results either uploaded to the server (review) or saved straight to a local folder | Add "keep results in a local folder" choice (no server review) | S |
| Document Extraction / Extract data | send | missing option | Choose another engine for the step (Google Document AI with service account, processor endpoint, bucket) and link the document to a request reference (AARI ref) | Allow a per-step engine override (google_ai) and a request_reference field linking to a case/request | M |
| Google Document AI / Connect | google_ai/connect | missing option | Sign in with a service-account key file (JSON) | Add credential_type service_account_json (file or vault) that gets tokens itself | S |
| Google Document AI / Extract | documentai_process | different control | Project id and processor id can come from the vault | Allow vault credentials for project and processor_id | S |
| Workload / Insert work item, Update work item | add_item, update_item | different control | The chosen queue's field layout is shown as a form (a "get structure" button on update) | In Studio, read the queue's fields and show a form for data (falls back to the dictionary editor) | M |
| Workload / For each work item in queue | each_item | hidden sub-option missing | Loop over items in a chosen state: on hold, data error, failed; fetch count (100 by default) | Add status filter to each_item (new / on hold / data error = business failure / failed) | S |
| Email trigger / Email | trigger kinds mail, email, outlook | missing option | Watch the desktop Outlook on the runner; also POP3 mail servers and Exchange (EWS) servers | Add a runner-side "New email in desktop Outlook" trigger; add POP3 to the IMAP kind; EWS only for on-premises Exchange (Microsoft retires EWS online) | M |
| Email trigger / Email | email (IMAP kind) | hidden sub-option missing | Conditions: several senders or subjects separated by ";", exact subject in quotes, with / without attachment, in a given folder | Covered by the "any mail account" kind's rules; for the plain IMAP kind add has_attachment and allow ";" lists, or steer users to the mail kind | S |
| Microsoft 365 Outlook Trigger / Email | outlook | missing option | Choose the Azure cloud (global or government/national clouds) | Add an azure_cloud choice (global, US government, China) for Graph and sign-in addresses | S |
| Microsoft 365 Outlook Trigger / Email | outlook | hidden sub-option missing | With / without attachment condition | Add has_attachment to the outlook kind (or point to mail rules) | S |
| Files & folders / File trigger | file, file_changed, file_deleted | missing option | Watch one named file; start also when it already exists; "renamed" event | Add file_renamed kind and an "also start if it already exists" tick for file/folder created | S |
| Files & folders / Folder trigger | folder_created, folder_deleted | missing option | Folder renamed event | Add folder_renamed kind | S |
| Interface trigger / Object | mouse_clicked, key_pressed | missing option | Events per control type: button / link / tab click or click with a hot key, text box got / lost focus or hot key while focused, check box ticked / unticked, radio / drop-down / list selection changed, menu item click; works in native apps, SAP GUI and Chrome/Edge pages | Add an "element event" trigger with an event choice (click, focus gained/lost, checked/unchecked, selection changed, menu item) and support web page elements through the browser extension | M |
| Process trigger / Process | process_started, process_stopped | different control | One trigger with Starts and Stops ticks; process picked from a refreshed list of running processes | Allow both events in one trigger; add a running-process picker in Studio | S |
| Service trigger / Service | service_started, service_stopped | missing option | Service paused event; service picked from a list | Add service_paused kind and a service picker | S |
| Window trigger / Window | window_opened, window_closed | hidden sub-option missing | Browser option: start when a Chrome/Edge tab with a given title (text or regular expression, case-sensitive tick) and address opens or closes; pick from open windows/tabs | Add a browser-tab open/close trigger via the extension (title, URL); add case-sensitive tick and an open-window picker | M |
| Universal web trigger / Listener | webhook | missing option | Check custom authentication headers; verify a signature (scheme + header name + secret from the vault); answer challenge checks (Slack, Monday); accept text, XML, JSON, multipart, form bodies; map payload keys to separate variables | Add header check, HMAC signature check (secret in vault), challenge reply, more body types and key-to-input mapping on the webhook kind | M |
| Universal web trigger / Poller | http_poll | (none) | Poll a URL at an interval and start on new events | Covered by the universal poller kind | - |
| Hot key trigger / Hot key | hotkey | (none) | Modifier keys plus a key | Covered | - |

### Cross-cutting (one line each)
- Regex (FILE2) fields without a regex option in ours: databricks/upload_file local_path; rest request (client_certificate, body_file, multipart files), rest download_file/upload_file (client_certificate, path); Kafka trust/key stores (ours has no such fields yet).
- AA tick box where ours is a Yes/No switch: about 18 (AD 4, SAP 4, SAP BAPI 1, Databricks 2, EC2 1, REST 4, Okta 2).
- Sessions missing in ours: Salesforce, ServiceNow, Okta (Connect/Disconnect), Workday, Databricks (Connect/Disconnect), Snowflake (Connect/Disconnect), Kafka (Connect/Disconnect); Generative AI sign-ins are covered by saved model connections. AD, SAP GUI, SAP BAPI, EC2, AWS AI, Google AI already have sessions.
- Variable vs fixed: AA takes vault credentials in fields that are plain text in ours (AD bind/target user, SAP user, SAP BAPI hosts, REST/SOAP address, SOAP/REST body, Google project/processor ids). Reverse: AA fixed lists where ours wants raw text/numbers (SAP virtual key, Workday id type, model names). Document Extraction lets the download kind come from a variable.

## 2. Ours has more (notable)
- Active Directory: move user/group, unlock, reset password + unlock, search, build DN, recursive group members by type, extra account flags (smart card, delegation, expiry), StartTLS / CA file / timeouts.
- SAP GUI: run transaction, press button, grid/tree toolbar buttons, read grid, status bar details, multiple-logon handling, fail on SAP error message, SAP GUI script import.
- SAP BAPI: read table (RFC_READ_TABLE), function details, roll back, RETURN messages and success flag, SNC.
- Salesforce: describe object, find records, bulk create/update/upsert (all-or-none), SOQL parameters and batching.
- ServiceNow: comments/work notes, changed-records feed, display values, order/offset.
- Okta: delete, suspend/unsuspend, expire password (temporary password), reset password, clear sessions, unlock, groups on create.
- Workday: launch integration with parameters, search workers, REST GET, JSON/CSV report formats, report to file.
- Databricks: clusters start/stop/list, statement status, run job and wait, catalog/schema.
- Snowflake: key-pair and programmatic-token sign-in, list databases/schemas/tables/columns/warehouses, async statements.
- Kafka: create/list/get topic, message headers. EC2: launch, reboot, volumes, snapshots, tags, wait for state, hibernate/force stop.
- AWS AI: PII detection, Rekognition, Textract. Azure AI and Google Vision/Language toolkits (no AA package in this group).
- REST/SOAP: OAuth 2.0 (client/refresh), OAuth 1.0a, bearer, NTLM on SOAP, saved web connections, base_url, redirects, WSDL reader.
- Generative AI: one toolkit for OpenAI, Azure, Anthropic direct, OpenAI-compatible, Hugging Face, Bedrock, SageMaker, Vertex; chat history sessions; classify, extract, summarise, translate, PII masking, JSON mode, token counts, picture generation, saved model connections.
- AI agents: approvals and forms (human in the loop), guardrails, knowledge indexes, MCP tools; Knowledge Q&A, MCP and Custom Connectors toolkits.
- Documents/IDP: wait for document, collect finished documents, classify and extract with examples; Document Automation event triggers.
- Work queues: get next, complete/fail with retries, defer, tags, copy/move/delete, counts, "queue has items" condition, central queues.
- Triggers: key pressed in a window, mouse click on an element, ServiceNow record trigger, universal poller, mail rules for any account, document events.

## 3. Upgrades from AA's newest releases (things we lack)
- REST 3.24.1: "get file stream" passes a file stream to later steps without download/upload; buffering option for servers without chunked uploads. https://docs.automationanywhere.com/r/automation-360/rest-web-services-package-releases
- Workday 1.7.0 / 1.6.2: Launch EIB takes a file stream; global sessions shared with child automations. https://docs.automationanywhere.com/r/automation-360/workday-package-releases
- Salesforce 2.7.0: sign-in through an External Client App (add JWT bearer flow; ours has client credentials / password only). https://docs.automationanywhere.com/r/automation-360/salesforce-package-releases
- SOAP 3.19.1: namespace, prefix, SOAP action and "qualify elements" settings for WSDL-built requests. https://docs.automationanywhere.com/r/automation-360/soap-web-services-package-releases
- SAP BAPI 2.11.0: run any custom workflow found by search ("advanced"). https://docs.automationanywhere.com/r/automation-360/sap-bapi-package-releases
- AI Skills 12.0.9: an AI Skill can be a tool of an AI agent (ours: agent tools are agents only). https://docs.automationanywhere.com/r/automation-360/ai-skills-package-release
- AI Agent (Local) 1.0.2-1.0.6: local tools config + discover tools; token/credit use reported per run. https://docs.automationanywhere.com/r/automation-360/ai-agent-local-updates
- Universal web trigger 1.3.1: poller generally available; incoming webhooks with signature schemes and challenge validation. https://docs.automationanywhere.com/r/automation-360/universal-web-trigger-updates
- Web triggers (push, not polling) for GitHub, Google Calendar, Jira, Microsoft 365 Outlook web, Teams, ServiceNow, SharePoint. https://docs.automationanywhere.com/r/automation-360/types-web-triggers

## 4. Totals
- AA commands checked: 213 (202 package commands in 23 packages + 11 trigger types; the live data holds no trigger fields, so triggers were checked from AA docs).
- Mapped to ours: 188 (some only partly); no match: 23 (SAP BAPI workflows 3, Snowflake bulk load 1, Connect/Disconnect in Okta/Databricks/Snowflake/Kafka 8, OpenAI assistants 2, Document Extraction update/download 2, AI SageMaker/HF sign-in 2, Generative AI sign-ins 5) + SAP2 demo package 2 (not real commands).
- Gap rows: 97 (S 69, M 26, L 2). By kind: missing option 33, different control 16, hidden sub-option missing 14, missing command 10, session 8, different default 7, different values 3, regex 3, missing output 2, limit/validation 1.
