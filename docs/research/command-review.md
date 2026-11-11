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
