# Property gaps: system, files and desktop toolkits

Compared on 22 Oct 2026 with the public Automation 360 documentation (docs.automationanywhere.com, read without signing in)
and the action list in `../aa-ce-actions-2026-10-19.json`. AA options are summarised in our own words. Where the public
page did not list an action's fields, the AA column says "fields not published" and we compared with the action's purpose.

Every new input is optional and its default keeps the earlier behaviour. Tests: `PropertyGapsTest` (packages),
`FileTransferFoldersTest` (packages-transfer), `WindowGapsTest` (packages-windows). Desktop and dialog actions check their
inputs first and, without a desktop session (`java.awt.headless=true`), stop with `DESKTOP.NO_SESSION` (Beep is skipped).

Totals: 138 actions reviewed, about 207 options added, 6 actions added (Run JavaScript function, Run VBScript function,
FTP Delete files, Window Get window details, DLL Open, DLL Close). No new toolkit was needed: Clipboard, Message box and
Prompt already exist in `ooa.desktop`, so nothing new was registered in `FirstPartyPackages`.

## Mouse, keyboard, screen, clipboard, dialogs (`ooa.desktop`)

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| Mouse click | Window to act in; click type (left, right, double, button down, button up); position relative to screen or window | x, y, button, double_click | `button_action` (click/down/up), `relative_to` (screen/window), `window` (brought to front first), `modifier_keys` (e.g. CTRL held while clicking) | Click on a UI element: done by the Recorder and Browser toolkits |
| Mouse move | Window; target position (relative to screen or window); delay | x, y | `relative_to`, `window`, `duration_ms` (glide instead of jump) | |
| Mouse scroll | Window; direction; number of notches | amount (sign = direction) | `direction` (up/down/left/right), `x`/`y` to scroll at, `relative_to`, `window`, `delay_ms` between notches | Horizontal scroll uses SHIFT + wheel (Java has no horizontal wheel) |
| Type text (Simulate keystrokes) | Target window; text with special keys in brackets; delay between keys; key down/up states | text, method (paste/keystrokes), delay_ms | `window`, `special_keys` ([ENTER], [TAB 3], [CTRL+S], [SHIFT DOWN]...[SHIFT UP], [[ for a literal [), `restore_clipboard`, `delay_before_ms`; held keys are always released at the end | "Hardware vs. background" typing modes: we have paste vs. keystrokes; background typing into a non-focused window needs per-control messages (later, with the desktop object toolkit) |
| Press keys | Part of Simulate keystrokes | keys, times | `key_action` (press/down/up), `delay_ms`, `hold_ms`, `window`; more keys (F13-F24, NUMPAD0-9, CAPS_LOCK, NUM_LOCK, PAUSE, MENU, ALTGR) | |
| Capture screen (Capture desktop / area / window) | File path, overwrite, area or window to capture | path, x, y, width, height (PNG only) | `format` (png/jpg/bmp), `overwrite`, `all_screens`, `window` (capture a window or a region relative to it) | Image quality setting: JPEG uses the default quality |
| Get clipboard text (Copy from) | Saves the clipboard into a variable | text | `wait_seconds` (wait until text arrives, e.g. after CTRL+C), `trim`; new output `has_text` | Images and file lists on the clipboard: AA handles text only |
| Copy to clipboard (Copy to) | Puts a value on the clipboard | text | `append` | |
| Clear clipboard | No options | yes | nothing needed | |
| Message box | Title, message, scroll bar after N lines, close after N seconds | message, title, timeout_seconds | `buttons` (ok, ok_cancel, yes_no, yes_no_cancel), `icon`, `scroll_after_lines`, `always_on_top`; output `button` (ok/cancel/yes/no/closed/timeout) | |
| Prompt for value | Window title, message, mask the value, keep on top | question, default, timeout_seconds | `title`, `masked`, `always_on_top` | Masked values stay plain text: Prompt for password returns a Credential for secrets |
| Prompt for yes/no | Window title, message, keep on top | question, timeout_seconds | `title`, `always_on_top`, `answer_on_timeout`; output `timed_out` | |
| Prompt for password | (ours; AA uses masked Prompt for value) | question | `title`, `timeout_seconds`, `always_on_top` | |
| Prompt for file | Window title, message, file type filter | title, start_folder | `file_types` (pdf, xlsx or *.csv), `multiple` (new output `paths`), `timeout_seconds`, `always_on_top` | |
| Prompt for folder | Window title, message | title, start_folder | `timeout_seconds`, `always_on_top` | |

## File (`ooa.file`) and Folder (`ooa.folder`)

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| File > Read text | (Text file: Get text) encoding | path, encoding | `encoding` accepts ansi and unicode names via the shared charset helper | |
| File > Write text (Create) | Path, overwrite existing | path, text, append | `encoding`, `fail_if_exists` | |
| File > Delete | Path or folder plus pattern; size filter (at least/at most/exact); created or modified date filter | path | `pattern`, `include_subfolders`, `older_than_days`, `min_size_bytes`, `max_size_bytes`; output `deleted` | Filter on creation date and "exact size": modified date and size range cover the use cases |
| File > List files | (used by AA filters) | folder, pattern | `include_subfolders`, `min_size_bytes`, `max_size_bytes`, `modified_within_days`, `older_than_days` | |
| File > Copy | Source path or folder plus pattern; overwrite, or keep both as "copy of"; size and date filters; copy in parallel | source, target, overwrite | `keep_both` (free name such as report (2).pdf), target may be a folder; output `path` | Parallel copying and multi-file filters: use List files + loop, or Folder > Copy with pattern |
| File > Move or rename (Rename) | New name; size and date conditions | source, target, overwrite | `keep_both`; output `path` | |
| File > Get file info / Get name / Get parent (Get name, Get path) | Name with extension; path | name, extension, folder, size, modified | nothing needed | |
| File > Zip (Folder > Zip) | Files/folder; file-type filter; update only newer; delete originals; compression level; password | source, target | `pattern`, `compression` (normal/fast/fastest/store), `overwrite`, `delete_originals` | Password-protected zip: Java has no ZIP encryption; use Encrypt file or PGP. "Update only if newer" inside an existing archive: archives are rebuilt |
| File > Unzip (Folder > Unzip) | Archive, destination, replace existing, password | archive, folder | `overwrite` (false keeps existing files) | Password: as above |
| File > Create shortcut / Create symbolic link | Target and link location | plus arguments, working folder, comment, overwrite | nothing needed (already richer) | |
| File > Print files (Print, Print multiple files) | Folder; file type as text or pattern; include sub-folders; size and date filters | files list, printer, copies | `folder`, `pattern`, `include_subfolders`, `modified_within_days` (files list became optional) | Size filter for printing: rarely needed |
| File > Open | Opens with the default program | Application > Open file | nothing needed | |
| Folder > Create | Path (fields not published) | path | `fail_if_exists` | |
| Folder > Copy | Source, target, overwrite (fields not published) | source, target | `overwrite`, `pattern`, `only_newer`; output `copied` | |
| Folder > Delete / Rename / Open / List subfolders / Create shortcut / Create symbolic link | Fields not published beyond paths | recursive delete, protected roots, rename checks, open in file manager | nothing needed | |

## System, Application, Services, Printer

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| System > Get environment variable | Choose a variable, save to a variable | name, default | nothing needed | |
| System > Get all / Set environment variable, Get system info | (ours) | | nothing needed | |
| System > Lock computer | No options | confirm | nothing needed | |
| System > Sign out (Logoff) | No options | confirm | `force` (close programs without saving) | |
| System > Restart / Shut down | No options | confirm, delay_seconds | `force`, `message` (shown to users, logged by Windows) | |
| Application > Open file / Start program (Open program/file) | Program or file path, start-in folder, parameters | program, arguments, working_folder | Start program: `window_state` (normal/minimized/maximized/hidden, Windows), `environment`, `wait_seconds` | Window state for Open file: the default application decides |
| Application > Run program and wait | (wait for completion, owner's request) | program, arguments, folder, input, timeout, fail_on_error | `environment`, `output_encoding` (e.g. cp850 for console programs) | |
| Application > Stop program / Program is running | | pid or name, force | nothing needed | |
| Services > Start / Stop / Pause / Resume | Service name; needs admin rights | name, timeout_seconds | `wait` (false continues once the request is sent) | Services on another computer: needs remote admin rights; not requested |
| Services > Get status / Restart / Is running | Service name, output variable | | nothing needed | |
| Printer > Get default / Set default / Remove printer / Get printers / Print file | Printer name (fields not published) | names, confirm for remove, copies | nothing needed | Duplex, page ranges and paper trays: AA does not offer them either |

## Scripts (`ooa.script`)

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| Run Python (Open + Execute script) | Script file or inline; Python version; stop on script error | script, file, arguments, input, timeout, fail_on_error | `working_folder`, `environment`, `interpreter` (choose a Python, e.g. a virtual environment) | Open/Close sessions: each run is a separate process, which isolates agents; a session adds no capability |
| Run Python function (Execute function) | Session, function name, one argument (list for several), output, stop on error | file, function, arguments, keyword arguments, timeout | `fail_on_error` (new output `error`), `interpreter`, `environment` | |
| Run PowerShell | (ours) | | `working_folder`, `environment`, `interpreter` (pwsh or powershell) | |
| Run JavaScript (Open + Run JavaScript) | File or inline script; run the whole script or one function | script, file, arguments | `working_folder`, `environment`, `interpreter` | |
| **Run JavaScript function** (new) | Run one function of a script with parameters | missing | file, function (also object.method), arguments (JSON), timeout, `fail_on_error`, `interpreter`; promises are awaited | |
| Run VBScript (Open + Run) | File or inline | script, file, arguments | `working_folder`, `environment` | |
| **Run VBScript function** (new) | Run one function with parameters | missing | file, function, arguments (as text, passed in environment variables so quotes survive), timeout, `fail_on_error` | Non-text arguments: VBScript has no JSON reader; values arrive as text |

## Database (`ooa.database`)

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| Connect | Connection string or built from server/database/port/instance; driver jar; named parameters from credentials; login timeout; session scope | jdbc_url, user, password, session | `driver_class`, `login_timeout_seconds`, `properties` (Credential values allowed), `read_only`, `auto_commit` | ODBC (32/64-bit) and per-database forms: we use JDBC URLs; Access/Excel via ODBC not planned. Global sessions shared with child agents: Runner change, not a toolkit option |
| Read from (query) / Export to data table | SELECT; save results to CSV | sql, parameters, max_rows, session | `query_timeout_seconds` | |
| Insert/Update/Delete | Statement | sql, parameters | `query_timeout_seconds` | |
| Run stored procedure | Procedure, parameters | procedure, parameters, max_rows | `query_timeout_seconds` | Output (OUT) parameters: needs typed parameter definitions; planned with Manage stored procedure |
| Export to CSV file (Export to file) | File, encoding, header | sql, path, delimiter, header, encoding | `query_timeout_seconds`, `overwrite` | |
| Bulk insert, Begin/Commit/Roll back, List tables, Describe table, Disconnect | (fields not published) | | nothing needed | |

## REST and SOAP web services

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| REST > Send request (Get/Post/Put/Patch/Delete) | URI; proxy (system or custom with sign-in); auth (none, Basic, AD user, NTLM, OAuth2 connection, Control Room token); headers; content types (form, multipart, JSON, XML, text, binary, custom); capture failure response; allow insecure HTTPS; accept cookies; timeout; client certificate (mTLS) | url, method, query, headers, json, form, body, basic, bearer, timeout, fail_on_error | `content_type` for raw bodies, `insecure_tls`, `proxy`, `proxy_user`, `proxy_password`, `client_certificate` (.p12/.pfx), `client_certificate_password`, `accept_cookies` (kept for the run), `follow_redirects` | NTLM and logged-in Windows user: Java's HTTP client has no NTLM/Kerberos; use a gateway or Basic. OAuth connections: the Connector toolkit covers them |
| REST > Download file (Get file stream / Download file) | Same connection options; overwrite | url, path, query, headers, auth, overwrite, timeout | `insecure_tls`, `proxy`, `proxy_user`, `proxy_password`, `client_certificate`, `client_certificate_password` | |
| REST > Upload file (multipart) | Same connection options | | same connection options as above | Buffering limit option: not needed, files are read once |
| SOAP > Send SOAP request | WSDL and operation picker; SOAP 1.1/1.2; parameters or raw XML; Basic auth; client certificate; headers; save response to a file; pick values with XPath; timeout | url, body, soap_action, version, headers, Basic auth, timeout | `insecure_tls`, `proxy`, `proxy_user`, `proxy_password`, `client_certificate`, `client_certificate_password`, `xpath` (new output `values`), `output_file`, `fail_on_error` | WSDL picker: a Studio feature (catalog lists "WSDL import planned") |

## Network, Log, Cryptography, PGP, Sound

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| Ping | (no AA package in the editor) | host, timeout_ms | `count`, `interval_ms`; outputs `replies`, `lost` (response_ms is now the average) | |
| Check port / Host reachable / Port open | (ours) | | nothing needed | |
| SNMP get / get next / walk / set | Version v1, v2c or v3; community; v3 security level, auth (MD5/SHA) and privacy (DES/AES); value types; port, timeout, retries; log file | v2c only, community, port, timeout, retries | `version` (v1 or v2c) on get, get next, walk and set | SNMP v3 (user security model with auth and privacy): substantial protocol work; planned separately. Log file: use Log to file |
| SNMP send trap | Same connection options | v2c trap | nothing added | v1 traps use a different message layout; v2c is what monitoring systems accept today |
| Log to file (Log text to file) | File, text, append timestamp, append or overwrite, encoding (ANSI, Unicode, UTF-8, UTF-16LE with BOM) | path, message, level, timestamp, overwrite | `encoding` (utf-8, utf-16le with BOM, ansi), `timestamp_format` | |
| Log variables to file | File (.txt), append or overwrite, timestamp, variables by type or chosen | path, variables, title, timestamp, overwrite | `encoding`, `timestamp_format` | "All variables of a type": the Runner would need to expose the variable list to toolkits |
| Log to Command Center | (Log to server) | | nothing needed | |
| Hash text / Hash file | Algorithm, encoding (fields not published) | text or path, algorithm | `output_format` (hex, hex_upper, base64), `encoding` (text); SHA-384 and SHA3-256 | |
| HMAC signature | (ours) | text, key | `algorithm` (SHA-256/384/512/1), `output_format`, `key_is_base64` | |
| Base64 encode / decode | (ours) | text | `url_safe`, `encoding`; decode accepts URL-safe text and line breaks | |
| Encrypt / decrypt file | Algorithm and key (fields not published) | AES-256-GCM with passphrase | `overwrite` | Choice of weaker algorithms: kept to AES-256-GCM on purpose |
| Encrypt / decrypt text, RSA actions | | | nothing needed | |
| PGP > Encrypt file | Public key file, source, target, overwrite | recipients from a keyring, armor, gnupg_home | `public_key_file` (no keyring import needed; a temporary keyring is used and removed), `overwrite` | |
| PGP > Decrypt file | Private key file, passphrase, source, target | passphrase, keyring | `private_key_file`, `overwrite` | |
| PGP > Create keys / Import key / Sign / Verify | Name, email, passphrase, key size | algorithm, expiry, export files | nothing needed | |
| Play sound > Beep | No options | | `times`, `interval_ms` | |
| Play sound > Play media file | File | path (waits to the end) | `wait`, `volume_percent`, `repeat` | MP3 and video: Java plays WAV/AIFF/AU only |

## FTP / SFTP (`ooa.file_transfer`)

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| Connect | FTP, FTPS (implicit or explicit), SFTP; passive/active; password or key; port | protocol, host, port, user, password, key, passphrase, host key fingerprint | `passive_mode`, `implicit_tls` (port 990 by default), `transfer_mode` (binary/ascii), `timeout_seconds` | |
| List files (Get files/folders listing) | Wildcards | folder | `pattern`, `kind` (all/files/folders) | |
| Download (Get files) / Upload (Put files) | Overwrite; only newer | remote and local paths | `overwrite` | Only-newer for a single file: use Download folder with a pattern |
| Download folder (Get folders) / Upload folder (Put folders) | Wildcards; include sub-folders; overwrite; only newer | folders | `pattern`, `include_subfolders`, `overwrite`, `only_newer` | |
| **Delete files** (new) | Delete files matching a wildcard | single-file Delete only | folder, pattern; output `deleted` | |
| Delete, Rename or move, Create folder, Delete folder, Change folder, Disconnect | Paths | | nothing needed | |

## Window (`ooa.window`) and DLL (`ooa.dll`)

| Action | AA options (our words) | We had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| Activate | Window by title (wildcards) or variable | title, alternatives | `timeout_seconds` (wait for the window) | |
| Maximise / Minimise / Restore | Window | title | `alternatives`, `timeout_seconds`; output `title` | |
| Move and resize (Resize) | Window, width and height (position optional) | title, x, y, width, height (all required) | x, y, width, height now optional (empty keeps the current value), `alternatives`, `timeout_seconds` | |
| Close | Window | title, alternatives | `timeout_seconds`, `wait_closed_seconds`; output `closed` | Force-closing the program: Application > Stop program |
| Close all | Windows matching a title | title, use_regex | nothing needed | |
| Set title | Window, new title | title, new_title | `alternatives`, `timeout_seconds` | |
| Get active window title | Saves the title | title | outputs `process_name`, `class_name` | |
| **Get window details** (new, AA "Assign") | Keep a window in a variable for later steps | missing | title, alternatives, timeout; output dictionary (title, program, class, position, size, state, active) | |
| List windows / Wait for window / Capture window / Window exists / Is active | | | Capture: `alternatives`, `timeout_seconds`, `activate`, `overwrite` | |
| DLL > Call function (Run function) | Session, function, parameters, output | library, function, typed arguments, return type | `session`, `calling_convention` (c or stdcall for 32-bit DLLs); library optional when a session is given | Pointer, structure and callback arguments: unsafe to expose to agents |
| **DLL > Open** / **Close** (new) | Load a DLL as a session; unload it | missing | library, session / session | |

## Shared changes outside the action classes

- `packages/.../HttpOptions.java` (new helper, not a toolkit): proxy, client certificate, accept-any-certificate, cookies and
  redirects for REST and SOAP.
- `packages/.../ui/DesktopEvents.java`: new default method `windowBounds`, implemented in
  `packages-windows/.../JnaDesktopEvents.java`, so mouse and screen actions can work relative to a window.
- `FilePackage.charset` is shared by File, Application, Crypto and Database for encoding names (including ansi and unicode).
