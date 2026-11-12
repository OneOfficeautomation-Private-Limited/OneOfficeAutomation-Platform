# Command review: every command, the way it is designed

Started 9 Nov 2026, after the usage-review build (items 16, 19 to 27). This is research; nothing here is built until
you choose.

## Method (each command)

| Lens | What is checked |
|---|---|
| **Usage** | How AA documents its use (the offline AA 360 library). How Blue Prism does the same job. UiPath will be added when its docs arrive. |
| **UI** | Our panel as a builder sees it: field names and order, help text, pickers, what can be reached without typing JSON. |
| **Function** | What the runner really does, every option included. |
| **Technical logic** | The code path: validation, errors and their codes, limits, edge cases. |
| **Reuse** | Whether it works with variables, other commands, child agents and subflows. |
| **Upgrade and migrate** | Whether old agents keep running, whether AA bots map onto it (the migration map), and whether the names are the ones people search for. |

## Batches (most used first, as AA's documentation and examples weigh them)

1. **Core flow**: 23 commands (If, Loop, Try, Assign, Run Agent, Delay, Wait…). This document, below.
2. Variables and data: String, Number, Datetime, List, Dictionary, Boolean, Data table, Record, JSON, XML.
3. Files: File, Folder, CSV/Text, PDF, Zip, Log to file.
4. Excel (basic, advanced, Microsoft 365), Word, PowerPoint.
5. Browser and capture: Browser, Recorder/UI objects, Image, OCR.
6. Desktop: Window, Application, Mouse, Keyboard, Screen, Clipboard, Terminal.
7. E-mail: Email, Outlook, Exchange, Gmail.
8. Integration: Database, REST, SOAP, Web services, FTP, PGP.
9. AI and documents: AI skills, Generative AI, Document extraction.
10. Cloud connectors: Salesforce, ServiceNow, Jira, SharePoint and the rest, grouped by family.

There are 1,287 commands in 101 toolkits, plus 50 conditions and 15 loop types.

---

## Batch 1: core flow (23 commands)

### What AA and Blue Prism teach about how these are used

- **If** (AA *If package*): the condition list is the heart of it. It is organised by kind: Application running,
  File exists or date, Folder exists, Window exists, Data table empty, Dictionary key, List contains, Boolean,
  Datetime, Number, String. It allows up to 32 conditions with **And/Or, and groups** of conditions. The If, Wait for
  condition and Loop While all offer the same conditions.
- **Loop** (AA *Loop package*): the iterator list is what people use: each row of a worksheet or CSV, each file in a
  folder, each mail in a mailbox, each row of a data table, N times. Loops can have **labels**, and Break and Continue
  **pick the label from a list**.
- **Error handler** (AA): Try, Catch (by exception kind, with message and line variables), Finally, Throw (pick an
  **exception from a list**, message optional). Blue Prism's **Recover** stage has *"limit the number of times this
  recover is attempted"*, which is a retry. Its **Exception** stage can *preserve the current exception* (rethrow) and
  *save a screen capture*.
- **Task Bot Run** (AA): choose the child (this bot, a Control Room file, or **a variable path**). Map inputs, with
  **Quick map**, which maps variables of the same name and type and creates the missing ones. Repeat the run and delay
  between repeats. "Upon error continue". Outputs go to **several variables or one Dictionary**.
- **Delay** (AA): Regular or Random (From to To), in milliseconds or seconds.
- **Wait** (AA): Wait for condition (one condition, same list as If), Wait for screen change, Wait for window.
- **Goto** (AA): available only on request, *for migrated bots*. It is not listed in the editor.
- **Step**, **Comment**, **Pause** (with a Resume button) and **Stop** (it explains how Finally behaves) all match ours.

### Our commands, one by one

| Command | UI | Function and logic | Verdict |
|---|---|---|---|
| If / Else if / Else | The condition builder offers **only value comparisons** (left, op, right) with All/Any. The 50 toolkit conditions (File exists, Window exists, Application is running, Data table is empty…) can only be typed as JSON under Advanced. There are no mixed And/Or groups. | The runner and validator handle toolkit conditions and nesting fully. | **Gap C1, C7** |
| Loop | N times / For each / While. For each offers **5 core kinds only**. The 15 toolkit loop types (each Excel row, CSV row, database row, file in a folder, subfolder, e-mail, browser page, XML node, work item, trigger events) are not offered. | The runner supports them all. | **Gap C2** |
| Exit loop / Continue loop | The loop name is a free-text field. | Runs the innermost loop, or the named one. | **Gap C5** (pick from the agent's loop names) |
| Try / Catch / Finally | Fixed in item 19 (AA's fields). | Full. | OK |
| Throw error | Code and message are both required, as free text. | Fine. | **Gap C6**: a list of standard codes plus your own, message optional (AA) |
| (every step) Retry and On error continue | **No UI at all.** | The runner and schema support a per-step `retry` (attempts, back-off, codes) and `continueOnError`, and an agent-wide default (`errorHandling`: stop/continue and retry). | **Gap C3**: the biggest hidden feature |
| Run Agent | Agent picker, then inputs and outputs one by one. | Repeat and delay, fail_on_error, same session, timeout, version. | **Gap C4**: Quick map, outputs to one Dictionary, the agent from a variable |
| Run subflow / Exit subflow | Fine. | Beyond AA (UiPath has invoke and local workflows). | OK |
| Assign / Assign several | Fine: typed, $ list. | Fine. | OK |
| Switch, Parallel, State machine | Fine. | Beyond AA (UiPath has them). | OK |
| Delay | Seconds, unit, "random max". | Fine. | **Small, C9**: show Regular/Random as AA's choice (From/To) |
| Wait for condition | Same builder as If. | Timeout, poll, fail on timeout. | Inherits **C1** |
| Wait for window / image / screen change / element | In their toolkits. | Fine. | OK (search finds them by AA's names since item 26) |
| Go to / Label | Offered to everyone in the library. | Fine. | **Small, C8**: AA keeps it for migrated bots; ours stays, but under Simple mode it is hidden and code analysis warns on it |
| Log message, Comment, Step, Pause, Stop | Fine. Log to file is its own toolkit, as in AA. | Fine. | OK. **C11**: four core commands show other names until the catalogue loads (Write to log / Log message, Wait / Delay, Wait until / Wait for condition, Raise an error / Throw error); use one name each |

### Why this was missed before

The October check compared **what the runner and catalogue can do**, programmatically. The conditions, loop types,
retry and continue-on-error all work in the runner and pass its tests. Nobody built an agent *in the editor* and asked
"can I pick File exists here?". That is the item 28 method we use from now on.

### Proposals (choose; nothing built)

| # | Proposal | Size |
|---|---|---|
| **C1** | **Toolkit conditions in the condition builder** (If, Else if, Wait for condition, Loop While): every row picks *what to check*. The kinds are "A value" (today's comparison), File, Folder, Window, Application, Browser, Data table, List, Dictionary, Date, Text, Image, Network, Services and others, then its own fields with $ variables. AA's order and plain names. | Large: one builder, used everywhere |
| **C2** | **Toolkit loop types in For each**: "Each row of an Excel sheet", "Each file in a folder", "Each e-mail", "Each row of a CSV file", "Each row of a database query", and so on. Each shows its own settings (sheet, folder, filter) instead of "Iterator settings" JSON. | Medium |
| **C3** | **Retry and On error continue on every step** (UiPath's ContinueOnError and Retry Scope, Blue Prism's Recover attempts). It goes in the step's More options and the step menu: "On error: stop / continue / retry N times, waiting S seconds (only for these errors)". The agent's Details get a **default error policy** (stop or continue, default retry). | Medium |
| **C4** | **Run Agent**: *Quick map* (map same-name variables, create the missing ones), outputs to *one Dictionary* as an alternative, and the agent to run *from a variable* (a path). | Medium |
| **C5** | Exit loop and Continue loop pick the loop's name from the loops around the step. | Small |
| **C6** | Throw error: the code from a list (BUSINESS.ERROR, VALIDATION.FAILED, NOT_FOUND, TIMEOUT… or your own); the message is optional. | Small |
| **C7** | Groups in the condition builder: "All of these" / "Any of these" groups inside each other, as AA's Add group. | Medium (with C1) |
| **C8** | Go to and Label: hidden in Simple mode, with a code-analysis hint "prefer Loop, If or subflows". | Small |
| **C9** | Delay: a Regular/Random choice with From/To, as AA. | Small |
| **C11** | One name per core command everywhere. | Small |

Suggested order: C1 and C2 first (most used and most visible), then C3 and C4, then the small ones together.

**Built 9 to 10 Nov 2026 (all of C1 to C11)** on ui/usage-review, each tested and tried in One AgentStudio as a user:
the condition builder lists 24 toolkits' conditions with their fields, is/is not and groups; For each lists the 15 loop
types with their settings; every step has If this step fails (continue, try again) and the agent a default; Run Agent
has Quick map and the agent from a variable; Exit/Continue loop pick a loop; Throw has usual codes (message optional);
Go to gets a code-analysis warning; Delay is Regular/Random; core names are one each. Follow-up seen while testing:
the core commands' one-line descriptions (shown since item 24) are technical ("Repeat the children using an
iterator..."); rewrite them in plain words with batch 2.

---

## Batch 2: variables and data (127 commands in 10 toolkits)

String (16), Number (6), Boolean (5), Date and time (19), List (17), Dictionary (7), Data Table (33), JSON (12),
XML (9), Currency (3). Checked 11 Nov 2026 against AA's String, Number, Boolean, Datetime, List, Dictionary, Data Table,
Record, JSON utilities and XML packages.

### What AA teaches about how these are used

- AA has an **Assign** in every type package (String: Assign, List: Assign…). Ours is one Assign variable. AA's names
  already find it in search, and migration maps them all (checked).
- AA's **Datetime** works in the **bot runner's local time**. "To string" offers a list of formats (MM/dd/yyyy,
  dd/MM/yyyy HH:mm:ss…) or a custom one.
- AA's **JSON utilities** and **XML** work in sessions (Start, read and change nodes, Save, End). Ours read and write
  in one step, from text or a file. That is simpler, with no session to forget to close. Keep ours.
- AA's **Record** package (add, delete or update a column) maps onto our Dictionary commands.
- Coverage: every AA action in these packages has ours, and ours go further: working days, period start and end, Group
  and summarise, Join tables, Look up value, list set operations, JSONPath, XPath functions, currency.

### Automated pass over all 127 (labels, help, samples, wording)

- Every field has a label and help text. Every command has a test sample except **Add column from list**.
- **Wording**: the one-line descriptions shown at the top of a panel (since item 24) are technical for JSON ("node",
  "JSONPath", "array"), XML ("XPath", "node") and dates ("UTC", "ISO").

### Findings, checked against our code

| # | Finding | Why it matters |
|---|---|---|
| D1 | **Every date command defaults to UTC**: Get current date and time, Format date, Text to date, Get part of date, the periods, working days. There is no "local" time zone. | In India, formatting "now" as dd/MM/yyyy between midnight and 05:30 gives **yesterday**, and "18/10/2026" read from text becomes 17 Oct 18:30 UTC. AA uses the runner's local time, so migrated bots and AA habits break silently. |
| D2 | The date **pattern** is free text with an example in the help. | AA offers a list of formats. A wrong letter (mm instead of MM) gives minutes, not months, and nobody notices. |
| X1 | **Drop-downs show coded values**: Operator shows "eq / ne / gt / ge…", Standard shows "ISO LOCAL DATE", AI provider shows "openai compatible". This affects **273 of the 472** list fields in the catalogue. | It reads as code, not as AA's or UiPath's words. Cross-cutting, so fix it once for every toolkit. |
| D3 | **Filter rows** (Data Table) takes one condition and keeps matching rows. | UiPath's Filter Data Table takes several conditions with And/Or, keeps or removes the rows, and can keep only some columns. People chain three Filter steps today. |
| D4 | Column fields (Filter, Sort, Get value, Look up, Sum…) are free text. | A misspelt column fails only at run time. The columns are often known (a table variable's default, or the last test run). |
| D5 | **Replace** shows Text, Find, Ignore case, Regex, Start, Count, then Replace with. | "Replace with" belongs next to Find. Start and Count are rarely used (AA: Find, Replace with, Match case, Regex). |
| D6 | Plain-word descriptions are needed for JSON (12), XML (9), the date commands that mention UTC (3) and the 23 core commands (batch 1 follow-up). | They are the first line of every panel. |
| D8 | **Locale** fields (Format number, Text to number, Format date…) are free text (en-US, de-DE). | A short picker (English US, English India, German…) avoids typos. It is cross-cutting, like X1. |
| D9 | Add column from list has no test sample. | Every command should have one. |

Fine as they are: String, Number, Boolean, List, Dictionary, Currency, JSON and XML behaviour (numbers read in any locale,
Indian grouping included; text helpers; set operations).

### Proposals (choose; nothing built)

| # | Proposal | Upgrade safety | Size |
|---|---|---|---|
| **D1** | A **Local** time zone (the runner's own) in every date command. New date steps start with Local, as AA. The time-zone field becomes a picker: Local, UTC, then zones by region, searchable. | Old agents keep UTC: only new steps get Local. Python reference runner too. | Medium |
| **D2** | Date **pattern picker**: a list of common formats (dd/MM/yyyy, MM/dd/yyyy, yyyy-MM-dd, dd-MMM-yyyy, with time…) or your own. Under the field, today's date shown in that pattern. | Values unchanged. | Small |
| **X1** | **Plain words in every drop-down** (all toolkits): a shared word list for the common codes (equals, is greater than, contains…; OpenAI, Azure OpenAI…; ISO date…), with every other code tidied (capitals, spaces). | Only the label changes; the value saved stays the same. | Medium |
| **D3** | **Filter rows**: several conditions (All/Any), Keep or Remove matching rows, and optionally only some columns, as UiPath's Filter Data Table. | The old single condition still runs. | Medium |
| **D4** | **Column pickers** in Data Table commands, from the table variable's default or the last test run; typing stays possible. | None. | Medium |
| **D5** | Replace: Text, Find, Replace with first; Ignore case, Regex, Start, Count under More options. | Layout only. | Small |
| **D6** | Plain-word descriptions for the JSON, XML, date and core commands. | Text only. | Small |
| **D8** | Locale picker for every locale field (cross-cutting, as X1). | Values unchanged. | Small |
| **D9** | A test sample for Add column from list. | None. | Small |

Suggested order: D1 (silent wrong dates), then X1 (all toolkits), then D3/D4, then the small ones.

**Built 10 to 11 Nov 2026 (all of D1 to D9 and X1)** on ui/usage-review, tested and tried in One AgentStudio. Found
while testing and fixed too (**X2**): since the Classic Studio was removed, the panels' choices and tick boxes (329
commands, e.g. Format date's Format, Add column from list's Insert at) were not drawn and their fields sat under More
options; they are drawn again, and Format date / Text to date show the pattern and time zone among the main fields.
Not changed: other toolkits' time-zone fields (Google, Microsoft 365 calendars) get the picker but not Local, since
their services take zone names.

---

## Batch 3: files (69 commands in 6 toolkits)

File (19), Folder (10), CSV/TXT (2 plus the loop type), PDF (16), Log to file (5), FTP/SFTP (15). Checked 11 Nov 2026 against
AA's File, Folder, CSV/TXT, Text file, PDF, Logging and FTP/SFTP packages.

### What AA teaches about how these are used

- **Where a file comes from** is the first choice in AA's file fields: a **Control Room file** (kept in the repository and
  sent to the runner with the bot), a **Desktop file** (a path on the runner), or a **Variable**. "Copy Control Room
  file" copies one out explicitly.
- **CSV/TXT**: Open asks for Contains header, a **Delimiter from a list** (Comma, Tab, Regional list separator, Newline,
  Other), Trim leading and trailing spaces, and an **Encoding from a list** (ANSI, UTF-8, Unicode…). It reads a whole file
  to a table, or row by row with the Loop iterator. Ours does the same.
- **Text file**, **PDF**, **Logging** and **FTP/SFTP**: every AA action has ours (sessions for FTP as in AA). Ours add
  file filters by size and date, AES zip passwords, PDF tables, form fields, OCR and images, XPS, folder sync, log fields
  and logging to One CommandCenter.

### Automated pass

Every field has a label and help text, and every command has a test sample. Migration map: AA's "File: Open" still
points to our old "Open file", which item 26 retired from the library. It should point to Open program/file.

### Findings, checked against our code

| # | Finding | Why it matters |
|---|---|---|
| F1 | **No way in Studio to use a file kept in One CommandCenter.** The agent format already has `files` (repository files placed at the same path on the runner before the run), but no field or panel sets it. File fields offer Browse on *this computer*, a path or a $variable. This is open item 4b. | Templates, mapping sheets and reference files are how AA bots carry their files. Without it a runner needs the file put there by hand. |
| F2 | **Delimiter** (11 fields: CSV, Data Table, Database, Excel, SAP) is free text; "tab" and "regional" work only if typed. | AA lists Comma, Tab, Regional, Other. Nobody guesses "regional". |
| F3 | **Encoding** (20 fields) is free text ("utf-8"); "ansi", "unicode" and "utf-8-bom" work only if typed. | AA lists them. A wrong guess garbles characters silently. |
| F4 | Size limits are in **bytes** (List, Copy, Move, Delete files). | People think in KB and MB. |
| F5 | **Write text** shows Path, Fail if exists, Text, Append. | Text belongs right after Path; "Fail if exists" is rare (More options). |
| F6 | **FTP Connect** shows Host and Port before Protocol, and password and key fields together for SFTP. | AA asks for the protocol first, then how to sign in. |
| F7 | Migration map: AA "File: Open" points to the retired "Open file". | Migrated bots should use Open program/file. |

Fine as they are: PDF (every option, page choices drawn since X2), Zip and Unzip, Folder, Log to file, copy, move and delete
filters, and the CSV read options (header, trim, skip lines, first rows, numbers and dates).

### Proposals (choose; nothing built)

| # | Proposal | Upgrade safety | Size |
|---|---|---|---|
| **F1** | **Files from One CommandCenter**, AA's Control Room file. File fields get a source choice: *This computer* (Browse, as now), *One CommandCenter* (browse the repository's files after sign-in; the path is kept, and the file is added to the agent's list of files to send with it), or a *$variable*. The agent's Details show that list, with Remove and Add. | Uses the `files` list the runner already reads. | Medium |
| **F2** | **Delimiter picker**: Comma, Semicolon, Tab, Pipe, Regional (the runner's list separator), Other. | It writes the words the runner already reads. | Small |
| **F3** | **Encoding picker**: UTF-8, UTF-8 with BOM, ANSI (Windows), Unicode (UTF-16), and others by name. | Same. | Small |
| **F4** | Size fields take a number and a unit (bytes, KB, MB, GB) and keep bytes in the agent. | Values unchanged. | Small |
| **F5** | Write text: Path, Text, Append; Fail if exists and Encoding under More options. | Layout only. | Small |
| **F6** | FTP Connect: Protocol, Host, Port, User, then "Sign in with: Password / Private key" for SFTP. | Layout only. | Small |
| **F7** | Migration map: AA File: Open goes to Open program/file. | Map only. | Small |

Suggested order: F1 (closes 4b), then F2/F3 (cross-cutting), then the small ones.

**Built 11 Nov 2026 (all of F1 to F7)** on ui/usage-review, tested and tried in One AgentStudio (a file put in One
CommandCenter picked into Read to table and listed in the agent's Details). Also changed while building: every toolkit
now reads the same delimiter and encoding words (one shared reading: CSV, Data Table, Database, SAP, File), so the
pickers' choices work everywhere; a file field's sources are written under the path ("Browse this computer", "From One
CommandCenter") so the path keeps the field's width.

---

## Batch 4: Excel, Word, PowerPoint (117 commands in 5 toolkits)

Excel, no Office needed (75); Excel desktop, needs Excel (5); Word (16); PowerPoint (17); PowerPoint desktop (4). Checked
11 Nov 2026 against AA's Excel basic, Excel advanced, Word and PowerPoint packages. Outlook desktop goes with e-mail
(batch 7), and Microsoft 365 Excel with the cloud connectors (batch 10).

### What AA teaches about how these are used

- **Which Excel**: AA has three packages: Excel basic (no Excel installed), Excel advanced (Excel installed) and
  Microsoft 365 Excel. Ours: "Excel (no Office needed)" does almost everything without Excel, including charts, pivot
  tables, formats and protection; "Excel (desktop)" is only for what needs Excel itself (macros, Refresh all, links,
  export to PDF, convert). AA's Word needs Word installed; ours does not.
- **Sessions**: a workbook, document or presentation is opened under a session name, and later actions name that
  session. AA lets a child bot share the parent's Excel session; our Run Agent has "same session" for that.
- **Cells**: Get or Set cell offers *Active cell* or *Specific cell*, and many actions *a named sheet* or *the active
  sheet*. Ours offers the same choices, drawn since X2. "Each row in a worksheet" is a Loop type since C2.
- Coverage: every AA Excel basic, Excel advanced, Word and PowerPoint action has ours (the map's only exception, "screen
  update", is not needed without a visible Excel).

### Automated pass

Labels and help are complete. **No test samples** for the five Excel desktop commands (they need Excel; samples can be
marked "desktop" and run where Office is installed), for Word's Extract text from a file and Get page count, or for
PowerPoint's Extract text from a file.

### Findings, checked against our code

| # | Finding | Why it matters |
|---|---|---|
| E1 | **Saving a workbook does not recalculate its formulas.** Our reads work formulas out fresh, but Save and Close write the file as it is: the stored results of formulas that depend on changed cells stay old, and nothing asks Excel to recalculate when the file is opened. | Set A1 = 5 where B1 is =A1*2, save, and a later tool (another bot, a report, a mail with the file, Excel itself until it recalculates) can read B1's old value. AA's Excel advanced runs in Excel, which recalculates. |
| X3 | **Session fields are free text** (default "excel", "word", "transfer"…) in **348 commands across 21 toolkits** (Excel 73, Active Directory 38, Browser 28, SAP 27, Email 20…). | With two workbooks open, a misspelt session fails only at run time ("no session"). The sessions an agent opens are known from its own Open and Connect steps. |
| E2 | Test samples are missing for 8 commands (above). | Every command should have one. |

Fine as they are: the Excel cell, range, table, sheet and workbook commands (with active or named sheet and cell
choices); Word and PowerPoint (no Office needed, find and replace text and pictures, tables, formatting); the desktop
commands for macros and refresh.

### Proposals (choose; nothing built)

| # | Proposal | Upgrade safety | Size |
|---|---|---|---|
| **E1** | **Recalculate on save**: Save, Save as and Close-with-save work out every formula they can (POI) and mark the workbook so Excel recalculates when it opens it; a formula POI cannot work out keeps its stored value and is logged once. | Files get correct stored values; nothing else changes. | Small |
| **X3** | **Session picker** in every toolkit: the field lists the sessions the agent's own Open and Connect steps of that toolkit name, with "the default" and typing your own or a $variable still possible. Same pattern as the column suggestions (D4). | Values unchanged. | Medium (one component, all 21 toolkits) |
| **E2** | Test samples for the 8 commands (the Excel desktop ones marked "desktop"). | None. | Small |

**Built 11 Nov 2026 (E1, E2, X3)** on ui/usage-review. E1 tested with a formula whose input changes (the stored result
follows, and Excel is asked to recalculate on opening). E2: the Excel desktop samples were also run once with Excel on
this machine (convert to .xls, export to PDF, refresh, update links, a clear error for a missing macro). X3 tried in One
AgentStudio: with Open workbook on session "invoices", a Get cell left on the default session suggests "invoices" and
says no step opens "excel".

---

## Batch 5: browser and capture (59 commands in 6 toolkits)

Browser (28), Desktop objects (10), Image recognition (6), OCR (8), AI screen (4), Web goal agent (3). Checked 11 Nov 2026
against AA's Recorder (Capture), Browser, Image Recognition and OCR packages, and Blue Prism's Application Modeller.

### What AA and Blue Prism teach about how these are used

- **Capture** (AA): choose the window from open applications, browser tabs or a window variable; the title can use
  wildcards or a regular expression, with or without case; *Resize window* puts it back to the recorded size; capture the
  object, then choose the properties to search by (per technology: HTML, UI Automation, MSAA, Java) and the action that
  suits the control. Blue Prism does the same with its spy modes (Win32, UI Automation, Accessibility, Java, HTML,
  Chromium, Region, Smart Vision) and match types per attribute.
- **Browser** (AA): Open, Close, Go back, Download files, Get source code, Run JavaScript, Call a JavaScript function,
  Find broken links.

### What we have, checked in panels and code

Every one of those is ours, and the capture editor already covers AA's choices: the window by title with wildcard or
regular expression, ignore case, resize to a size, the captured properties to search by with how each must match,
the action list that suits the control, "if not found", and Test, which outlines what matches. The object repository
(G42), desktop objects with UI Automation and legacy MSAA, image and OCR clicks, and AI-described controls go beyond AA.
All fields have labels and help.

### Findings

| # | Finding | Why it matters |
|---|---|---|
| B1 | **Use case P4 fails some full test runs**: it adds and edits rows on demoqa.com, a public practice site, and sometimes the form does not close after Submit (step s19 waits 30 s for it). It passed in two of the last four full runs; the site loads and its form and checks are as the agent expects. Reproducing it needs submitting the form on that site, which this session did not do (declined by the permission check). | A third-party site makes the whole Java suite fail, so a real failure could be missed among them. |
| B2 | Commands without a **command sample**: 30 here (OCR, image, desktop objects, AI screen, web agent, a few browser ones). They have unit tests (OCR 5 files, image 4, desktop objects 5, browser 11, AI screen 1, web agent 2), because they need a screen, an OCR engine or an AI model. Browser's Extract list, Attach to open browser and Start browser for automation could have samples on a local test page. | Small. |

### Proposals (choose; nothing built)

| # | Proposal | Size |
|---|---|---|
| **B1** | Public-website samples (P1 to P6 and the like) **try once more** before failing, and their failures are reported apart ("outside site") so the suite stays green on a site's bad day; a nightly job runs them with a screenshot on failure. Separately, with your OK, I watch P4 run once to find whether the site changed. | Small |
| **B2** | Command samples for Extract list, Attach to open browser and Start browser for automation on a local test page; OCR "Read text from image" with a generated picture where the Windows OCR engine is present. | Small |

No other changes are needed in this batch.

**Built 11 Nov 2026 (B1, B2)** on ui/usage-review. B1: a sample marked "requires internet" that fails is run once more in a
fresh folder; if it fails again it is reported as "outside site" with the reason and counted as skipped, not failed
(checked with a temporary sample that always fails; the progress table counts it with the skipped ones). Not done: the
nightly job with a screenshot, and watching P4 on demoqa.com (running the public-website samples here was declined by the
permission check because they submit forms on outside sites; needs your OK). B2: samples 53-browser-ocr.json for Extract
list, Start browser for automation with Attach to open browser, and Read text from image (Windows OCR); all pass here.


---

## Batch 6: desktop (93 commands in 8 toolkits)

Window (16), Application (9), Mouse, keyboard and screen (21, with clipboard, prompts and message box), Terminal
emulator (19), System (15), Windows services (6), Java applications (3), Windows Credential Manager (4). Checked
11 Nov 2026 against AA's Window, Application, Mouse, Simulate keystrokes, Screen, Clipboard, Prompt, Message box, Wait,
Terminal Emulator, System and Service packages, and Blue Prism's global mouse and keys, Wait stage, mainframe
integration and Windows Credentials object. Tried in One AgentStudio: Activate window, Mouse click, Press keys.

### What AA and Blue Prism teach about how these are used

- **Choosing a window** is the same everywhere in AA (Window, Mouse, Keystrokes, Screen, Wait): pick from the windows
  open now (with Refresh), a browser tab, or a window variable. The title takes a `*` wildcard (exact match first, then
  "contains") or a regular expression, with or without case. AA's advice: keep the title in one variable so a renamed
  window is fixed in one place.
- **Mouse** (AA): click relative to a window rather than the screen (screen points move when the resolution changes);
  *Capture coordinate* picks the point live; *Resize window* puts the window back to the size it had when the point was
  taken, so the point still lands. Button down and up for dragging; a duration for move and scroll.
- **Keystrokes**: AA always sends keys to a chosen window, with an on-screen list of special keys and a credential
  option that types the secret without showing it. Blue Prism's order of preference: write into the field, press keys
  without focus, send keys, and low-level key events (the only kind that works through Citrix); it needs an unlocked
  desktop, the window in front, and short pauses after activating and between keys.
- **Waits** (AA and Blue Prism): every wait has a timeout and a clear choice between failing and carrying on.
- **Prompts and message box** (AA): auto-close for unattended runs; Cancel leaves a dummy value that builders must check.
- **Terminal** (AA): TN3270E, TN5250E, VT100/220, ANSI and Linux over Telnet or SSH2, TLS, device/LU name, code page,
  key-file sign-in; fields by index (counted from 0) or name; send a key after text with a wait for text before or
  after it; wait for text, cursor position, blank screen or prompt; key mappings per session. Blue Prism drives an
  installed emulator (HLLAPI) instead of having its own client.
- **System and services** (AA): lock, sign out, restart, shut down at the end of a task; services picked from a list,
  admin rights needed to change them.

### What we have, checked in panels and code

Everything AA documents is here, and several things go further: prompts return **cancelled** and **timed out** instead
of a dummy value (AA's pitfall), the message box has buttons and an icon, Type text can type a vault credential key by
key, Press keys has hold, down and up, mouse move and scroll have durations, waits all have "fail on timeout", Block
input, callouts, screen resolution with restore at the end, background typing into a window without focus (Blue Prism's
"no focus" option), and a terminal that needs no emulator for 5250 and VT/SSH. Every field has a label and help, and
dangerous system commands (restart, shut down, screen resolution) need a Confirm tick.

### Findings

| # | Finding | Why it matters |
|---|---|---|
| W1 | **The Window toolkit's Title has no "Open windows" picker** (15 commands). Studio adds the picker only to fields named *window*; here the field is *title*. Tried: Activate window shows a bare text box. | The most common desktop step can't be filled by picking, as AA does everywhere. |
| W2 | **Two rules for matching a title.** Mouse, keyboard, capture, triggers and interrupts use the shared rule (contained text, or `*`/`?` wildcards, or `re:` for a regular expression). The Window toolkit's Title only takes contained text; wildcards and `re:` work only inside *Alternatives*, and Close all matching windows has its own "regular expression" tick. | The same title written in two steps behaves differently; AA's wildcard titles do not carry over to Window commands. |
| W3 | **Alternatives is a bare key/value list**: the builder must know the key names (title_pattern, class_name, process_name, index). | Hidden feature; nobody can fill it without reading the help. |
| U1 | **The Window field in Mouse click, Press keys, Type text and the rest is squeezed** to a few letters next to the "Open windows" button (seen at 1500 px wide). | A picked title can't be read or checked. |
| M1 | **No way to pick a point on screen**: X and Y are typed. Studio already has "hover and press Ctrl+Shift+C" for desktop objects. | AA's *Capture coordinate*; without it builders guess pixels. |
| M2 | **Coordinates default to the screen even when a window is chosen** ("Relative to" sits under More options), and nothing puts the window back to the size it had when the point was taken. | AA's advice is window-relative points plus *Resize window*; screen points break when the window moves or the resolution changes. |
| K1 | **Three ways of writing keys**: Press keys takes `CTRL+S`; Type text with Special keys takes `[ENTER]`, `[TAB 3]`; Type into window in background takes `{ENTER}`, `{TAB 3}`; the terminal takes `Enter`, `PF3`. Key names differ too (`PAGE_UP` vs `PageUp`). Every key field is free text with the names only in the help. | Easy to write the wrong form; AA has an on-screen key list. |
| T1 | **AA map errors**: AA *Get field* and *Get text* both point at our *Find text on screen*; they should point at *Get field* and *Get screen text* / *Get text at position*. The map feeds Studio search (aaNames.ts), so searching "Get field" offers the wrong command. AA counts fields from 0, we count from 1: worth a line in the migration notes. (Outside this batch, *Excel advanced/Get multiple cells* points at a command that does not exist.) | Wrong suggestions for people coming from AA. |
| T2 | **3270 needs the free wc3270 engine installed on the runner**; only the error after connecting says so (the toolkit page and Connect help do not). 5250 and VT/SSH need nothing. | First 3270 connection fails on a fresh runner. |
| T3 | **No command samples for the terminal** (19 commands) although the unit tests already have a fake 5250 host and an in-process SSH host. Also missing: Press keys, Mouse click and scroll, prompts, Show desktop, background typing (these need a desktop), services stop/pause/restart (admin, change the machine), Java applications (needs Java Access Bridge). | The terminal's commands are not checked end to end as a builder would chain them. |
| T4 | **Press key waits for the keyboard to unlock but not for an answer screen**; AA's Send key can wait for a text before or after the key in one step. Ours needs a separate Wait for text step. | Small; one step instead of two in every screen change. |

### Proposals (choose; nothing built)

| # | Proposal | Size |
|---|---|---|
| **W1+W2** | The Window toolkit's Title gets the "Open windows" picker, and accepts the same forms as every other window field (`*`/`?` wildcards and `re:`). Upgrade-safe: a title that has no match as a wildcard is tried as contained text, so an old title such as "Save?" still works; Close all matching windows keeps its tick for old agents. | Small |
| **W3** | Alternatives drawn as named fields (Title pattern, Window class, Program name, Index) instead of a key/value list; stored the same way. | Small |
| **U1** | The window field: picker under the text box (not beside it), full width. | Small |
| **M1+M2** | "Pick on screen" for Mouse click, move, scroll, callout and Capture screen: hover, press Ctrl+Shift+C, and the step gets the window, the point measured from the window's corner, and the window's size. A new optional "Put the window back to this size first" uses that size at run time. New steps with a window chosen measure from the window; old steps keep what they had. | Medium |
| **K1** | One key picker for every key field (Press keys, Wait for mouse or key, Type text, background typing, terminal Press key and Set field): a list of keys grouped (letters and digits, function, navigation, modifiers, terminal PF/PA), which writes the form that command expects. The runner also accepts the other spellings of key names (`PageUp` = `PAGE_UP`, `Esc` = `ESC`). No stored step changes. | Medium |
| **T1** | Fix the AA map (Get field, Get text, the Excel entry) and regenerate aaNames.ts; note in the migration notes that AA field numbers start at 0. | Small |
| **T2** | Connect's help and the toolkit page say 3270 needs wc3270 on the runner; Studio's Problems check warns when a flow uses 3270 and the runner has no engine. | Small |
| **T3** | Command samples for the terminal against the in-process SSH host and fake 5250 host (connect, fields, keys, waits, key mappings, disconnect). | Medium |
| **T4** | Press key and Type text get "Then wait for text" (text, timeout), the same as a following Wait for text step. | Small |

**Built 11 to 12 Nov 2026 (all)** on ui/usage-review. Two corrections to the findings above: T4 was mostly there
already (Type text and Press key on the terminal have "Wait for text afterwards"; only Set field lacked it, now added),
and the Excel advanced/Get multiple cells mapping is right (it points at the loop type "Each row in a worksheet", as AA
uses it). What changed:
- **T1**: AA *Get field* now finds *Get field*, AA *Get text* finds *Get screen text*; the field-number help says AA counts from 0.
- **W1, W2**: the Window commands' and conditions' Title has the "Open windows" picker, and takes `*`/`?` wildcards and
  `re:` like every other window field (one rule, `WindowPackage.titleRule`); a title is still also found by the text it
  contains, so older agents with a `*` or `?` in a title keep working. Close all matching windows keeps its tick.
- **W3**: Alternatives drawn as Title pattern, Window class, Program name and Index; stored as before.
- **U1**: the window box has the whole width, with the picker under it.
- **T2**: the toolkit page and Connect's help say 3270 needs wc3270 on the runner; Check now notes a 3270 Connect step when
  this computer has no engine (it never stops a save).
- **K1**: a key picker under every key field (Press keys with Ctrl/Alt/Shift/Win ticks, Wait for mouse or key, Type
  text inserts `[KEY]` and turns on Special keys, background typing inserts `{KEY}`, the terminal's keys grouped by
  host). The runner accepts other spellings everywhere (Page Up, PgUp, Del, Return, Control, AA's KEY_ENTER, F5 for PF5
  on 3270); no stored step changes.
- **M1, M2**: "Pick on screen" under X for Mouse click, move, scroll and Show callout: point and press Ctrl+Shift+C; the
  step gets the window, the point measured from its corner, and (mouse steps) the window's size, which is given back to
  the window first ("Put the window back to this size first"). Choosing a window for a mouse step that has no
  "Relative to" yet measures from the window; Relative to now sits next to X and Y. Old steps keep what they had.
- **T3**: 10 terminal samples (54-terminal.json) against an in-process SSH host: connect, screen and position reads,
  fields by label, typing with a key and a wait, key mappings, Ctrl+C, the prompt, the cursor, and VT's clear error.
  The 5250-only and 3270-only field commands stay covered by their unit tests (fake 5250 host, scripted 3270 engine).


---

## Batch 7: e-mail (73 commands in 5 toolkits)

Email (IMAP/POP3 and SMTP, 19), Exchange on-premises (EWS, 13), Outlook desktop (10), the Gmail commands of Google
Workspace (15) and the Outlook mail commands of Microsoft 365 (16). Checked 12 Nov 2026 against AA's Email, Microsoft
365 Outlook and Gmail packages (Connect, the "For each mail in mailbox" loop, the email dictionary, Send, Reply,
Forward, attachments, triggers, the Basic-auth and EWS retirements) and Blue Prism's Outlook objects, Graph connector
and work-queue design. Tried in One AgentStudio: Find emails (Outlook), searching "send email".

### What AA and Blue Prism teach about how these are used

- **One way of working per mailbox**: AA's Email package connects to the Outlook client, an email server (IMAP/POP3)
  or EWS from one Connect step, and every action then names the session; Microsoft 365 Outlook (Graph) and Gmail are
  separate packages with the same actions and the same loop. A builder moving from EWS to Graph (which AA and Microsoft
  now ask for) keeps the same shape of bot.
- **Sign-in**: OAuth everywhere. Client credentials for unattended runs, PKCE for attended ones, or a connection kept by
  the Control Room with a shared or per-user token that is renewed for the bot. Basic auth is off in Exchange Online.
- **The loop is the heart**: "For each mail in mailbox" with Read / Unread / Both, keep the read state or not, newest or
  oldest first, folder, subject (several values, exact in quotes), senders, a date range, text or HTML; each turn gives
  the mail's properties (subject, from, to, cc, body, dates, number of attachments, and on Graph the message id).
  Change status, Move, Delete, Save attachments and Reply act on the current mail.
- **Bulk actions** (Move all, Delete all, Save all attachments) run outside the loop with the same filters, for big
  mailboxes. Best practice: move a processed mail to another folder so it is never picked up twice; Blue Prism puts one
  work-queue item per mail (keyed by its id) and lets workers take them.

### What we have, checked in panels and code

Every AA action has a match, and several go further: Find emails gives a table with ids for any later step, filters
on "to", body text and attachments, oldest first, Save email as .msg, inline images on Send, read receipts, shared
mailboxes (IMAP, Exchange, Microsoft 365, Outlook), and Delete all / Move all / Save all attachments on every service.
Every field has a label and help. Email triggers exist in One CommandCenter (the server signs in to Graph by itself).

### Findings

| # | Finding | Why it matters |
|---|---|---|
| A1 | **Microsoft 365, Gmail and IMAP with OAuth take a raw access token from the vault**, and nothing gets or renews it. Such tokens last about an hour. Send request (REST) and the e-mail trigger can sign in with client credentials or a refresh token and renew it; a saved connection only fills in the token field. | An unattended mail agent stops working an hour after someone pastes a token; the AA way (client credentials, or a Control-Room-kept token) has no match. The biggest gap of this batch. |
| A2 | **Five toolkits, five vocabularies.** The same idea has different field names: id / message_id (Gmail); read yes-no / status; note / comment (Microsoft 365) / body (Outlook) for a forward's text; to_folder / folder (Outlook); max_results / max; since / received_after; target_folder / folder; Find emails gives table / emails (Outlook, a list). The email record differs too: text + html, body + body_type (Microsoft 365), body + html_body (Outlook); received / date (Gmail); attachments / attachment_names / has_attachments. | Moving an agent from IMAP or Exchange to Microsoft 365 means filling every step again; a child agent written to handle "an email" works for one service only. |
| A3 | **"Each email" loop only for IMAP and Exchange**; Microsoft 365, Gmail and Outlook desktop need Find emails and a loop over its table. The loop gives a summary (id, from, subject, received, unread, attachments); the body needs a Read email step. | AA's loop works the same on every service and gives the body. |
| A4 | **Unread only and Read only are two tick boxes** that can contradict each other (IMAP, Exchange, Microsoft 365 find and loop); Gmail and Microsoft 365 Change status use a "status" choice instead. | AA has one choice: Read, Unread or Both. |
| A5 | **AA map**: AA's Email/Connect goes to our IMAP Connect whatever server type the AA bot used (Outlook client and EWS included); AA's *Microsoft 365 Outlook/Save email* points at Save attachments instead of Save email as EML/MSG. | Wrong suggestions in search and in migration. |
| A6 | **Exchange (EWS) lacks what the others have**: Reply and Forward have no attachments, no "include original", no forward-as-attachment, no "leave out the sender"; no Save as .eml; its loop has no to / body / before filters. EWS is being retired in Exchange Online; the toolkit says "on-premises", but nothing points Exchange Online users to Microsoft 365. | Uneven; and a builder on Exchange Online may pick the toolkit that is going away. |
| A7 | **Samples**: Gmail 0 of 15, Microsoft 365 mail 2 of 16, Outlook desktop 0 of 10, Exchange 9 of 13 (all have unit tests against stand-in servers). | Chains like find, read, save attachments, move are not checked end to end on those services. |
| A8 | **Gmail's names differ**: "Send Gmail", "List Gmail messages", "Change Gmail message status"; searching "send email" in Studio finds four toolkits but not Gmail. | Harder to find. |

### Proposals (choose; nothing built)

| # | Proposal | Size |
|---|---|---|
| **A1** | **Mail sign-in that renews itself**: a connection kind for Microsoft 365 (client credentials, or a refresh token for one user) and Google (refresh token), kept in the vault; the runner gets the token and renews it before it runs out (the token cache Send request already uses). The IMAP Connect takes the same connection for OAuth. Steps that pass an access token keep working. | Medium |
| **A2** | **One email record for every service**: Read email, Find emails and the loop also give the same keys everywhere (id, from, to, cc, subject, received, text, html, attachments as names, unread, message_id), next to today's keys, so old agents keep working; Studio labels the same fields the same way. Then a Studio tool **"Switch mail service"** that turns the mail steps of an agent from one service into another (IMAP or Exchange to Microsoft 365, and back), mapping the fields and listing what has no match. | Large |
| **A3** | "Each email" loop for Microsoft 365, Gmail and Outlook desktop with the same filters; every loop can give the body too ("With the text and HTML"). | Medium |
| **A4** | Read state as one choice (All, Unread, Read) in Studio, stored as the two ticks are today. | Small |
| **A5** | Fix the AA map (Microsoft 365 Save email; Email/Connect noted per AA server type: Outlook client to Outlook desktop, EWS to Exchange, email server to Email) and regenerate the search names. | Small |
| **A6** | Exchange catches up: Reply and Forward attachments, include original, forward as attachment, leave out the sender, Save as .eml, loop filters; its help says Exchange Online should use Microsoft 365. | Small |
| **A7** | Command samples for Gmail, Microsoft 365 mail and the Exchange gaps against stand-in servers ({mock}); Outlook desktop samples marked "desktop", tried once here if Outlook is set up. | Medium |
| **A8** | Gmail names in the common words ("Send email (Gmail)", "Find emails (Gmail)", ...); the old names stay searchable. | Small |

**Built 12 Nov 2026 (all)** on ui/usage-review. What changed:
- **A1**: a saved connection can sign in by itself: Microsoft 365, Google or another OAuth server, either the app's own
  sign-in (client id and secret, for unattended agents) or one person's refresh token. The runner gets the access token
  when a step needs one, keeps it, and renews it a minute before it runs out; the newest refresh token Microsoft gives
  is used next (kept in memory, not written back to the vault). Kept in the connection's settings and vault references
  (sign_in_...), so no storage changed; form in One AgentStudio and One CommandCenter. Email (IMAP with OAuth) can now
  have connections, with its Connect settings. Steps that pass a token keep it.
- **A2**: Read email of every service also gives the same keys (id, from, to, cc, subject, received, text, html,
  attachments, has_attachments, unread, message_id), next to its own; Microsoft 365 now gives attachment names and
  unread, Outlook desktop's Find emails a table like the others. Tools > Switch mail service turns an agent's mail
  steps into another service's (renamed fields, read state, Gmail labels), lists what has no match, and is undone in one go.
  Not changed: an existing key keeps its type (Microsoft 365's received stays text).
- **A3**: each-email loops for Microsoft 365, Gmail and Outlook desktop; every mail loop has "with the text and HTML"
  (read one at a time, left unread).
- **A4**: Read state is one choice (All, Unread only, Read only) in steps and loops; stored as the two ticks.
- **A5**: AA map fixed (Microsoft 365 Save email; Email Connect to EWS or Outlook); the search-name script now lives in
  docs/research/tools/make-aa-names.py.
- **A6**: Exchange replies and forwards take files (through a draft), reply all can leave the sender out, forward can
  attach the original as .eml, Save email as .eml, Find emails and the loop search the body, and the loop has the same
  filters as Find emails. Not possible on EWS: replying without the original quoted.
- **A7**: 36 samples run (Microsoft 365 mail, Gmail, Exchange gaps, the loops); 3 Outlook desktop samples are marked
  desktop and were not run here (Outlook would open on the screen).
- **A8**: Gmail commands are named like the others ("Send email (Gmail)"...); the old names still find them in Studio
  ("Formerly: ...").
- Found on the way: batch 6 changed the mouse commands' signatures, and some older tests no longer compiled; the earlier
  signatures are back, and the full suite is now checked with --continue and fresh result times.
- Tests: full suite with --continue, public-site samples off: every module passes except two live desktop tests
  (Character Map typing, keyboard hooks), where Windows refused simulated key presses (access denied, error 5: a locked
  screen or an administrator window in front); not from these changes. The CLI test JVM ran out of memory with the new
  mail samples: every sample now shares one toolkit registry (the cause of MERGE-1), and all 1,563 CLI tests pass.
  Tried in One AgentStudio: the old Gmail name finds the new one, Read state, Switch mail service and Undo, the sign-in form.


---

## Batch 8: integration (79 commands in 11 toolkits)

Database (12), REST (3), SOAP (2), FTP / SFTP (15), PGP (6), Hash, encryption and encoding (14), Custom connectors (5),
Network / SNMP (7), Kafka (6), MCP tools (2), SAP BAPI (7). JSON and XML were batch 2. Checked 12 Nov 2026 against AA's
Database, REST Web Services, SOAP Web Service, FTP/SFTP, PGP, Kafka, SNMP packages and Connector Builder, and Blue
Prism's Web API services, Credential Manager and SOAP wizard. Tried in One AgentStudio: Send request.

### What AA and Blue Prism teach about how these are used

- **An API is defined once** (Blue Prism Web API services, AA Connector Builder): base address, sign-in, common headers
  and parameters, then one action per endpoint with typed inputs and outputs; import from OpenAPI.
- **Sign-in is kept centrally**: AA's Control-Room-managed OAuth connections (shared or per-user token, renewed for the
  bot); Blue Prism's Credential Manager types (Basic, Bearer, OAuth 2.0 client credentials, JWT). Secrets go anywhere:
  `{{name}}` from the vault in connection strings, addresses, headers, bodies and SOAP XML.
- **Same sign-in on every REST method** (AA: none, Basic, NTLM, logged-in AD user, OAuth from a connection, user token).
- **Database**: Connect, then Read / Export to data table / Insert-Update-Delete / stored procedure (In, Out, InOut,
  outputs by name) / transactions / bulk insert, row loop; AA warns it does no escaping (no parameters).
- **FTP / SFTP**: FTP, FTPS explicit or implicit, SFTP with a key; reconnect attempts; wildcards and date filters; AA
  always overwrites on upload.
- **PGP** (AA): create keys, encrypt and decrypt with a passphrase or key files, built into the package.

### What we have, checked in panels and code

Everything AA and Blue Prism document is here, and several things go further: parameterised SQL (AA has none),
Rollback (AA has none), describe table and list tables, query to a file, stored-procedure outputs by name; FTP with an
overwrite choice, folder sync with a dry run, only-newer and date filters, retries; REST with OAuth 1.0a and 2.0
(refresh tokens), NTLM and the Windows user, client certificates, cookies, multipart, fail-on-error off to keep the
failure's body; SOAP with a WSDL picker and XPath; custom connectors from OpenAPI, Swagger or Postman with OAuth and AWS
SigV4; PGP signing and verifying (AA has neither); Kafka with manual commit; MCP tools. Every field has a label and help.

### Findings

| # | Finding | Why it matters |
|---|---|---|
| R1 | **A secret from the vault is sent as `****`** when it is a header, a query value, a form field or a value in a JSON body of Send request (also Upload file, Download file, SOAP headers and custom connectors' headers): those values are turned into text, and a vault secret's text is its mask. Checked: `headers: {X-Api-Key: $key}` sent `X-Api-Key: ****`, and the step reported success. Multipart parts already send the secret. | Any API that takes a key in a header (most do) fails with "unauthorized" when the key comes from the vault, the safe way; the step does not say why. |
| R2 | **Sign-in differs between the web steps**: Send request has six ways (none, user name, bearer, OAuth 2.0, OAuth 1.0a, Windows/NTLM), Upload file three, Download file its own set, SOAP only a user name. REST and SOAP cannot use saved connections (they are left out of the list), so the address, headers and sign-in are repeated on every step; the batch 7 sign-in that renews itself cannot be used for them. | AA gives every REST method the same sign-in and keeps OAuth in Control Room connections; Blue Prism defines the API once. |
| R3 | **A new Send request step opens with "OAuth 2.0" chosen** although nothing is set: when no field of a choice is filled, Studio picks the first option with a default value (OAuth's "client sign-in" has one) before the option with no fields ("No sign-in"). | Confusing on the most used integration step; other choices with an empty option can do the same. |
| P1 | **PGP needs GnuPG (Gpg4win) installed on every runner**; it is not here, so the PGP tests skip and its commands have never run on this machine. AA's PGP is built in. | A first PGP step fails on a fresh runner; nothing checks the real path in the test suite. |
| S1 | **No command samples** for FTP / SFTP (13 of 15), PGP, custom connectors, Kafka, MCP, SNMP and SAP BAPI. Unit tests exist for all (FTP against a stand-in FTP server; SAP BAPI needs SAP's JCo). | Chains such as connect, upload, list, download are not checked end to end. |

### Proposals (choose; nothing built)

| # | Proposal | Size |
|---|---|---|
| **R1** | Secrets reach the server: headers, query values, form fields and JSON values from the vault are sent as the secret (one shared helper for Send request, Upload file, Download file, SOAP and custom connectors), and stay masked in logs and run output. | Small |
| **R2** | One sign-in for every web step: Upload file, Download file and SOAP get Send request's choice (none, user name, bearer, OAuth 2.0, OAuth 1.0a, Windows). REST and SOAP can use saved connections: an address prefix, headers and sign-in kept once (the renewing sign-in of batch 7 included, as a bearer token); a step's address can then be a path such as /invoices/42. Old steps unchanged. | Medium |
| **R3** | A choice with nothing filled in shows its empty option ("No sign-in", "None") before an option that only has a default. | Small |
| **P1** | PGP built in (Bouncy Castle, the Java OpenPGP library): create keys, encrypt, decrypt, sign, verify and import work without GnuPG, with key files and passphrases from the vault; GnuPG is used when a step names a GnuPG home (its keyring). The PGP tests then always run. | Medium |
| **S1** | Command samples for FTP and SFTP (stand-in servers), PGP (after P1), custom connectors (a local OpenAPI file and a stand-in API), Kafka (stand-in REST proxy) and MCP (stand-in server). SNMP and SAP BAPI stay with their unit tests. | Medium |
