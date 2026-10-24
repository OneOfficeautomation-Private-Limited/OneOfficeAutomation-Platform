# Command-by-command review: One Agent Studio against AA

Source: the 26 use cases built in both products on 2026-10-24. Ours are in `examples/use-cases/`; AA's are in
**Bots\Use cases**. For each case, every step was compared:
- our command and how it is set up;
- the native AA command (Recorder Capture, If, Loop …) and its fields;
- which one is simpler for the developer.

The full step tables per case are in [use-case-command-review.md](use-case-command-review.md). This page is the list
that came out of them. Descriptions are in our own words.

Priority: **P1** fix now (users notice it at once), **P2** fix soon, **P3** nice to have.
Status: **Fixed** (done on 2026-10-24), **Open**, **Keep** (ours is better), **Same**.

## The short version

AA feels simpler because every command shows only a few fields in plain words. Values are typed the same way
everywhere (`$var$`), and the action list after a Capture suits the control picked. Ours can do more, often in fewer
steps; examples:
- table work: O1 needs 9 steps in ours and about 45 in AA;
- REST with SQL parameters;
- Group and summarise in one step;
- Download by clicking a link;
- Catch by error family.

Before the fixes, though, the developer had to know too much:
- **Calculations had to be typed as JSON.** Typing `$i + 1` silently stored the text "$i + 1". This was used in more
  than 30 steps across the 26 cases.
- **Table, list and dictionary changes were lost** unless the output was mapped back to the same variable.
- **Forms showed internal names and raw codes.** Examples: `object`, `selector`, `session`, `eq`, "Jdbc url".
- **Important fields were hidden under More options, and rare ones were shown.** Wait for element's *State* was
  hidden, while Reply showed 12 mail-server fields.
- **Long forms had no order of importance.** Reply has 18 fields and Classify 10.

The fixes below keep every option but put the common path first.

## Same command, used differently across the cases

| Command | How the cases use it | What that asks of the developer | Fix |
| --- | --- | --- | --- |
| **Assign variable** | Plain text (labels), a copy of a variable (`$total`), a counter (`$i + 1` in 15+ steps), a sum, a yes/no comparison (`$best != $previous`, W8; D4), a text template (`Invoice ${n}`) | Before: four spellings, and the counter only worked as JSON | **Fixed:** *Text or value* / *Calculation* switch; a calculation typed in Text mode is saved as one, with a note |
| **Set text** (web, desktop) | A column of the current row (`$e.FirstName`), a number (`${e.Age}`), an expression (`toString($row["First Name"])`, P1), a credential | Before: the expression showed as `[object Object]` and the help text was wrong | **Fixed:** *Text* and *Calculation* modes, correct help. The runtime already turns numbers into text, so `toString()` is not needed |
| **Loop** | All rows of a table (P1, W4); the first N rows (P2, P5, P6 needed `to_index = $n - 1` as JSON); a list (W8); a While on an element (W6, W9); a counter (W10, built by hand because the position starts at 0) | Before: one generic panel (Over, As, Index, From/To) | **Fixed:** the kind comes first (N times / For each / While), then only its fields; *Only the first* takes `$n`; the canvas shows "first $n" |
| **If** | A cell value (P1), an element on the page (P3, P5, W2), a variable that is empty (P5, P6), a table lookup result (W5, W8, W9) | Before: element conditions showed `object`, `selector`, `session`, `window` as bare boxes, with no Capture | **Fixed:** the same fields as the action: labels, Capture or Pick, the window editor; the session is hidden |
| **Table actions** (Add row, Sort, Set value, Change column type) | Change a table in place (W5–W9, O1, O5) | Before: they return a new table, so the change was lost unless the output was set to the same variable | **Fixed:** choosing the input variable also stores the result back in it (shown under Outputs, with a note) |
| **Close** (Excel, Word, PowerPoint) | After *Create* and writes (O1, O3, W7) | Before: *Save* was off by default, so the work was lost unless it was ticked | **Fixed:** added after a create or write step, Close has *Save changes* ticked |
| **Get text** | Labels and links (P1, P2), an optional value (P6: 6 steps with each XPath written twice) | Before: no "if not found, leave empty" option | **Fixed:** *If not found*: stop with an error, or leave it empty and go on (output *found*) |
| **Click** | A normal click, and a JavaScript click when an advert covers the button (P4) | The JavaScript click is only in *Capture: element action*, so the step had to be replaced | Open (P2) |
| **Window** (desktop) | The same title typed in up to 9 steps (D1); "equals" in objects but "contains" in Window commands | | Open (P2): a window variable chosen once |
| **Excel / Word sessions** | Custom session names (`report`, `letter`) typed in 3–6 steps | | Open (P3): fill in the open session |

## Fixed on 2026-10-24

| # | What | Where |
| --- | --- | --- |
| U1 | **Readable step summaries** on the canvas: "If $row["First Name"] is not empty", "Repeat for each row in $people as $row, first $top_n", "Repeat while …", "$rounds = $rounds + 1", "Get text … → $result". Before, it showed `key: value` lists and raw JSON | `summary`, `conditionText`, `loopText` |
| C1 | **Catch and Finally in the palette**, next to Try. Dropped on a Try, or after one, they add the block; with no Try, one is created. An empty Catch is reused, not duplicated | `BLOCK_COMMANDS`, `addBlock` |
| C3 | **Loop panel**: *Kind of loop* (N times / For each / While), then that kind's fields with plain labels ("Table to go through", "Store each row in"), typed variable lists with "+ New", and toolkit iterator fields without JSON. *Only the first* is new; rare options are under More options | `loopPanel` |
| U2 | **Typed variable pickers** for Table, List, Dictionary and Record fields: variables of that type, "+ New … variable", or "Enter a value or expression…" | `typedVarControl` |
| N1 | **Calculations without JSON.** *Assign variable* has a *Text or value* / *Calculation* switch, and text such as `$i + 2` is recognised and saved as a calculation. Number fields accept `$n - 1` and show calculations as text | `assignValueEditor`, `numberFromText` |
| N2 | **Set text** has a *Calculation* mode and shows calculations instead of `[object Object]`; the help explains `$row.Name` and `${amount}` | `textSourceEditor` |
| N3 | **Table, list and dictionary results are stored back** in the input variable when no output is chosen | `mirrorOutput` |
| N4 | **Condition fields** use the action field editor: labels, Capture or Pick, the window editor; *Element exists* explains that it checks once | `conditionEditor` |
| N5 | **More options by importance**, not by position. The first fields and the required ones stay in view, and so do key choices: Wait for *State* and *Wait up to*, Set text *Press Enter afterwards*, CSV *Detect numbers and dates*, Close *Save changes*, Create workbook *Replace the file if it exists*, Classify *Provider* and *Model*, Delay *Unit*. Everything else optional goes under More options, so Reply opens with 3 fields instead of 12 | `renderProps`, `KEY_FIELDS` |
| N6 | **Plain labels** for generated ones: "Database address (JDBC URL)", "SQL statement", "Incoming mail server (IMAP)", "Outgoing mail server (SMTP)", "Hide personal data", "Wait up to (ms)". Outputs read "Save table in". Delay reads "How long" with its unit | `fieldLabel`, `FIELD_LABELS` |
| N7 | **More options stays closed** on the next step when it was only opened because it held changed fields | `renderProps` |
| N8 | **Close saves** when the agent created or wrote the file | `addStep` |
| N9 | **Get text and Get attribute: *If not found*.** *Leave it empty and go on* gives empty text and `found` = false instead of an error, so P6's 6 steps become 2. The choice is shown in the main fields, and the canvas says "(empty if not found)" | `BrowserPackage.getText`, `getAttribute` |
| N10 | **Data table *Look up value*.** Give a table, the column to look in, the value, the column to return and a value for when nothing matches. It returns the value, `found` and the row, replacing the 3–4 steps (Find, default, If, Get value) in W5, W8 and W9 | `DataTablePackage.lookup` |
| N11 | **Column names ignore case** when only one column matches that way (`city` finds `CITY`, as in O4), for every table action | `DataTablePackage.col` |
| N12 | **Plain words in drop-downs.** Codes are shown as words; the saved value is unchanged and shown on hover. Operators read "equals", "does not contain", "is greater than"; providers "OpenAI", "AWS Bedrock"; *If not found* reads "Stop with an error" / "Leave it empty and go on" (and "Skip this step and go on" for element actions); other `snake_case` codes become words | `enumWord`, `ENUM_WORDS` |

## Open: next fixes

| # | Gap | Evidence | Fix | Pri |
| --- | --- | --- | --- | --- |
| O1 | ~~Get text / Get attribute: "if not found"~~ | | Fixed (N9) | - |
| O2 | ~~Look up a value in a table in one step~~ | | Fixed (N10) | - |
| O3 | **Element conditions have no wait** | W7 and W9 add fixed 300 ms delays before *Element visible* | *Wait up to (seconds)* on Element exists and Element visible | P2 |
| O4 | **Element text condition** | W10 polls with 4 steps (Assign, While, Delay, Get text) for "status is Ready" | Condition *Element text is / contains*, so *Wait until* does it in one step | P2 |
| O5 | ~~Drop-downs show codes~~ | | Fixed (N12) | - |
| O6 | **Row editors instead of JSON** | Merge tables, Add row, Group and summarise (`sum:Amount`), Start program arguments (D1–D5) | Merge: two table pickers. Add row: one box per column. Summaries: rows of new column, function and column. Arguments: one line of text | P2 |
| O7 | **Click method on Click element** | P4 had to switch to *Capture: element action* to get the JavaScript click | *Click method* under More options; rename *Capture: element action* to "Element action" | P2 |
| O8 | **One element field** | Every web step shows *Captured object* and *Selector* | One "Element" field: Capture by default, "Use a selector instead" | P2 |
| O9 | **Open browser defaults** | Every unattended agent changes *Engine* and *Keep open* | A *Run mode* choice (watch / unattended) that sets both | P2 |
| O10 | **Get text into a Number variable** | W6 and W9 store read amounts in Number variables; the runtime converts, but Studio does not offer them | Offer Number and Boolean variables for text results, with a "converted" note | P2 |
| O11 | **Window variable** (desktop and web) | D1 types the window title 7 times; D5 has two windows | Choose a window once and pick it in every step; also "the window of process `$pid`" | P2 |
| O12 | **Desktop step labels** | D1's six steps all read "control" when the object is found by Automation ID | Use Automation ID or class name in the step label | P2 |
| O13 | **Database Connect** | A JDBC URL typed by hand (O4) | Database type list with server, database and file fields that build the URL | P2 |
| O14 | ~~Column names ignore case~~ | | Fixed (N11) | - |
| O15 | **Nested AND/OR groups and type-aware operators** in conditions | AA has groups and per-type operators (match case, dates) | Add groups; *match case* for text; date comparison | P2 |
| O16 | **Loop iterators** | Mail (O2) and SQL rows take two steps | "Each email in a mailbox", "Each row of a SQL query" | P2 |
| O17 | **Extract a repeated list** | P2, P3, P5 and P6 read product cards with JavaScript | "Extract list": capture the repeating item and the fields inside it, giving a Table | P2 |
| O18 | **Next-page pattern** | The same 5 steps and a flag in P3, P5, W2 | Iterator "Each page (click Next until it is gone)" | P3 |
| O19 | **Throw: default code** | W7 has to type `BUSINESS.REFUSED` | Default `BUSINESS.ERROR`; codes as a list | P3 |
| O20 | **Find with a group; Extract text up to the end of the line** | O3 needs two steps and two regexes per value | *Return group* on Find; an "end of line" choice | P3 |
| O21 | **Get window details: separate outputs** | D3 needs two Assign steps for width and height | Outputs `width`, `height`, `x`, `y`, `state` | P3 |
| O22 | **Table variable columns** | An empty table needs `{"columns": [...], "rows": []}` (O5) | A column list editor in the Variables panel | P3 |
| O23 | **Samples use the simpler actions** | Samples use Assign for counters, Replace + `toNumber`, and Read text + Parse | Use Number Increment, Text to number, Read JSON file and Extract text in `examples/use-cases` | P3 |
| O24 | **Live validation** | Required fields that are empty show only after Validate | A marker on the step and under the field as soon as it is empty | P2 |
| O25 | **Excel desktop: macros, refresh, save as PDF**; SharePoint and OneDrive paths | AA drives real Excel | Grow *Excel desktop* | P2 |

## Ours is better: keep

- **Tables:** CSV read and write in one step, type detection, Filter with count, Sort with *Then by*, Sum column, and
  Group and summarise. AA needed Dictionary loops and text-file round trips: O1 is 9 steps in ours and about 45 in AA;
  P5's grouping is 1 step against about 15 actions.
- **Web:**
  - Get text on labels and links (AA offers only *Get property* there).
  - Download by clicking the link, returning the saved path; this also works for page-made (blob) links.
  - Wait for element visible or hidden, and either of two elements.
  - Variables in selectors as plain text.
  - Columns of the current row by name (`$e.FirstName`), where AA uses a position (`$rE[0]$`) that breaks when columns
    move.
- **Desktop:**
  - Get text works for labels and boxes alike.
  - No fixed delays are needed after clicks.
  - Get desktop table returns real column names.
  - Close window reports whether the window closed.
  - Stop program in Finally is safe when the process has already ended.
- **Office and services:**
  - REST parses JSON and reaches nested fields with dotted paths.
  - Database uses `?` parameters; AA needed hand-escaped SQL, so O4 is 2 steps per row in ours against 9.
  - PDF text is returned directly.
  - Word documents are created from a template in one step.
  - Reply uses the server already set by Connect.
  - Classify returns one allowed label and hides personal data by default.
- **Flow:**
  - If with Else if in one block.
  - Catch by error family (`BUSINESS.*`).
  - Outputs return results, where AA bots write every result to a log file.
- **Speed:** P1 runs in 1.4 s in ours and 5.9 s in AA.

## Earlier list (command by command), with status

### All commands (UI)

| # | Gap | Status |
| --- | --- | --- |
| U1 | Readable step summary on the canvas | Fixed |
| U2 | Type-filtered variable pickers with "+ New variable" | Fixed for Table, List, Dictionary and Record fields and in the Loop panel; outputs already had it |
| U3 | Field labels in plain words | Fixed for the generated labels found in the 26 cases (N6); more to do per toolkit |
| U4 | Errors shown before running | Open (O24) |
| U5 | Labelled toolkit condition fields | Fixed (N4) |

### Flow control

| # | Command | Difference | Status |
| --- | --- | --- | --- |
| C1 | Try / Catch / Finally | AA has separate palette entries | Fixed |
| C2 | Catch: continue with next action | Ours has it per step ("Continue the agent if this step fails") | Same |
| C3 | Loop type first, then that type's fields | | Fixed |
| C4 | Loop: map each column to its own variable | AA option; ours reads columns by name (`$row.Name`) | Open (P3) |
| C5 | Loop iterators for mailbox, SQL rows, record values | | Open (O16) |
| C6 | If: nested groups, type-aware operators | | Open (O15) |
| C7 | If: previous agent run succeeded, script succeeded | | Open (P3) |
| C8 | Throw | Ours has code, suggested fix, details and retryable | Keep |
| C9 | Delay: random from a lower bound | | Open (P3) |
| C10 | Exit / Continue loop by label | | Same |

### Data, files and Office

| # | Command | Status |
| --- | --- | --- |
| D1 | One *Assign variable* for every type (AA has one per type) | Keep; now with the Calculation switch |
| D2 | String replace | Same |
| D3 | Text compare: "ignore line breaks" | Open (P3) |
| D4, D6, D8, D9 | Number to text, Sort, Group by, JSON by value | Keep |
| D5 | Dictionary put / get | Same |
| D7 | Write table with a custom row delimiter | Open (P3) |
| F1 | CSV: regional list separator | Open (P3) |
| F2, F3 | Excel: SharePoint paths, real Excel (macros, refresh) | Open (O25) |
| F4, F5, F6 | Excel header row, text file types, log to file | Keep |
| F7, F8 | Word, PDF text | Same |
| F9 | Size and date filters on file copy, move and delete | Open (P3) |

### Browser and desktop

| # | Command | Status |
| --- | --- | --- |
| B1 | Actions after a Capture suit the control type | Done earlier: the actions for the captured control come first, the rest under "Other actions". Open: group the list when only a selector is used (P3) |
| B2 | Wait settings grouped as one "System time-out" (Basic / Advanced) | Done earlier |
| B3 | Debugger click for pages that block scripts | Open (P3) |
| B4 | Window variable | Open (O11) |
| B5, B6 | Run JavaScript with variables; Download | Keep |
| B7 | Key picker for special keys | Open (P3) |
| B8 | Desktop objects by ID or name | Same |

### Run

| # | Gap | Status |
| --- | --- | --- |
| R1 | Run inputs | Same |
| R2, R3 | Live step highlight; speed | Keep |
