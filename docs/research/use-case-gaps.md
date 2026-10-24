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
| **Click** | A normal click, and a JavaScript click when an advert covers the button (P4) | The JavaScript click is only in *Capture: element action*, so the step had to be replaced | Fixed: Click element has a click method (mouse or JavaScript) |
| **Window** (desktop) | The same title typed in up to 9 steps (D1); "equals" in objects but "contains" in Window commands | | Partly fixed: Window commands take the process id from Start program (O11) |
| **Excel / Word sessions** | Custom session names (`report`, `letter`) typed in 3–6 steps | | Open (P3): fill in the open session |

## Web steps with Capture: P1 pilot

The first AA bots did their web steps with Run JavaScript, which is not how AA is normally used. P1 was rebuilt in both
products with **Capture** (a captured object plus an action), and both versions were run:
- AA: bot **P1 RPA Challenge (Capture)** in Bots\Use cases;
- ours: `examples/use-cases/P1-rpa-challenge-capture.flow.json`.

How the captures were made:
- **Ours:** Studio's real Capture (`POST /studio/api/capture` on an open Chrome tab). The click on the element was made
  through the browser's debugging port, as a person's click would be.
- **AA:** its Capture follows the desktop mouse, which could not be driven from here. Its Recorder Capture actions were
  built with the properties AA records for web controls and matched by DOM XPath, then run on the Bot Agent. AA's own
  default choice of properties to match was therefore not observed; one real capture by hand would show it.

| | AA | Ours |
| --- | --- | --- |
| Result | 10 rounds, 70 of 70 fields | 10 rounds, 70 of 70 fields |
| Time | 28.7 s run | 6.6 s run (1.6 s on the challenge's timer) |
| Fields (their ids change every round) | DOM XPath on `ng-reflect-name`, edited by hand | Label ("First Name"), chosen by Capture after the fixes below |
| Start, Submit | DOM XPath (text, type) | Text "START"; tag + type `submit` |
| Canvas line | "Set text $rRow[0]$ in textbox in the 'Rpa Challenge' window" (no field name) | "$row["First Name"] into text box “First Name”" |
| Result message | Get property *HTML InnerText* of a label | Get text |

**What P1 showed, and was fixed (C1–C6):**

| # | Problem in our Capture | Fix |
| --- | --- | --- |
| C1 | **Random ids were matched.** `UeWWS` was treated as stable because the rule only looked for digits, so the object would break as soon as the page made new ids | Ids that do not read as words count as made up (`UeWWS`, `jQjQk`, `g7Aeq`, `OCNJj`); `firstName`, `btnSave` and `APIKey` stay. The same rule runs in the page script and in Java (`generatedId`), with a unit test |
| C2 | **No label for fields whose label is not linked** (a `<label>` next to the box without `for=`) | The label next to a field is found when the field's wrapper holds that one label and one field |
| C3 | **Fields are found by their label** when it is unique on the page. One field (`jeoyL`) still passed the id rule | For a form field, a unique label wins over id and name: it is what a person reads, and it survives made-up ids |
| C4 | **The DOM XPath used the on-screen text:** `//button[normalize-space()='START']` matched nothing, because the page says `Start` and CSS shows it in capitals | The XPath uses the page's own text |
| C5 | **The capture tooltip could be captured** when it lay over the element (small windows) | The tooltip ignores the mouse |
| C6 | **Step labels:** Submit showed as "input", and Download repeated its selector | Control type and visible value: "button “Submit”", "text box “First Name”". Desktop objects fall back to Automation ID or class name (O12) |

**Still open from P1:**
- One element field: Capture and Selector are both shown on every web step (O8).
- AA's Get property offers named properties (HTML InnerText, HTML Href …); ours has Get text and Get attribute, which
  cover the same ground.

## Web steps with Capture: all 15 web cases

P2–P6 and W1–W10 were rebuilt with Capture in both products.

**Ours: all 15 pass, with the same results as the selector versions.** Each flow ran once with object recording on
(`OOA_RECORD_OBJECTS`, see below). Every element a web step found was described exactly as Capture describes a clicked
element. The recorded objects then replaced the selectors: 137 element steps in all, plus P1's 9. The flows are
`examples/use-cases/*-capture.flow.json`. All 15 ran again with the objects and gave the same outputs as the selector
versions.

What Capture now matches on, across those 137 steps:

| Property | Steps | Example |
| --- | --- | --- |
| id | 69 | `#checkout`, `#m-approve` |
| label (fields) | 31 | "First Name", "Email" |
| test attribute (`data-test`) | 19 | saucedemo, every element |
| text | 8 | "Sign in", "Next" |
| class | 7 | `quote`, `author-born-date` |
| other (name, type, DOM XPath) | 3 | |

34 steps keep their selector, because one fixed object cannot describe what the step does:

| Why | Steps | Example |
| --- | --- | --- |
| Reads a whole table or form, so there's no single element to record | 11 | P4 `table`, W2 `#products` |
| Meets a different element each time (one per row or page) | 11 | P3 product details, P6 infobox rows, W6 queue rows |
| Has a variable in it | 6 | W6 `#${decision}`, W10 `.line:nth-child(${k})` |
| Lists alternatives ("either A or B") | 4 | W3 `#results tr, #none` |
| Picks the n-th match | 2 | P2 first "Add to cart", W8 first result row |

These show what captured objects still cannot express (open items O8, O17 and C10–C12 below).

**What the 15 cases showed, and was fixed (C7–C9):**

| # | Problem in our Capture | Fix |
| --- | --- | --- |
| C7 | **Test attributes were ignored.** saucedemo marks every element with `data-test`, but only `data-testid` counted | `data-test`, `data-test-id`, `data-qa`, `data-cy` and `data-automation-id` are used first |
| C8 | **Data was used as a name.** The cart link was matched on `aria-label="Cart, 3 items"` and the totals on `text="Item total: $33.97"`, so they break when the count or amount changes | Text, aria-label and title containing digits are not used to find an element |
| C9 | **Some objects matched any element.** When nothing named the element (`.inventory_list`), only `tag=div` was ticked | The element's own class (matched with contains, as classes change with state), or else its DOM XPath |

**New: object recording.** Set `OOA_RECORD_OBJECTS` to a file before a run, and every element a web step finds by
selector is written to that file as a captured object, one JSON line per step. Recording never changes what the step
does. It turns working selector agents into captured-object agents without clicking through every page again, which is
how the 15 cases were converted (scratchpad tool `to-capture.mjs`). Studio could offer it as "Convert selectors to
captured objects" (open item C10).

**AA: 9 of 16 web cases pass with Capture.** The 15 AA bots were rebuilt as **<name> (Capture)** in Bots\Use cases:
- each page script became a Recorder Capture action (click, set text, get property, check);
- each wait loop became a Capture that waits for the object.

AA's own object format came from a real recording. AA's Universal recorder was started from the bot editor; its
desktop window was operated with UI Automation and the mouse; the clicks in the page went through Chrome's debugging
port. Every generated object copies that recorded one: its technology section (the browser agent), its full list of
47 criteria and its attributes, filled with the element's values from our recordings. Before that, generated objects
failed with "Search Criteria did not match".

| Case | AA with Capture | Capture steps reached (with the failing one) | Note |
| --- | --- | --- | --- |
| P1 | Pass | 10 | 70 of 70 fields |
| W1 | Pass | 8 | |
| W2 | Pass | 3 | |
| W3 | Pass | 2 | |
| W5 | Pass | 1 | |
| W6 | Pass | 10 | One XPath holds a variable (`#$sDecision$`) |
| W7 | Pass | 38 | |
| W8 | Pass | 24 | |
| W10 | Pass | 19 | |
| P2 | Fails at Login | 3 | The same button object is read, then not found for the click (flaky) |
| P3, P5 | Fail at the first element without an id | 1–3 | AA matches id-based DOM XPaths; matching on class, type or value alone was not found |
| P6, W9 | Fail at a table | 1–3 | A table as a plain label object is not found; AA's Table control type would be needed |
| P4, W4 | Fail at a button low on the page | 9, 4 | Capture does not scroll to an element outside the window |

What AA's Capture showed, against ours:

| | AA | Ours |
| --- | --- | --- |
| Default ticks | 9 of 47 properties: tag, DOM XPath, id, name, type, Path, has frame, **frame source (the page address)** and CSS selector | One or two properties chosen by what stays the same (test attribute, label, stable id, text) |
| Elements without an id | DOM XPath anchored on the nearest ancestor with an id | Class, text or DOM XPath, as found |
| Password boxes | Set text takes only a vault credential, a credential variable or an insecure string | Credential, variable or text |
| Typing into React and Angular pages | 0 ms between keys pastes the value, which such pages may ignore; 40 ms (the recorder's choice) types it | Fills the field the page way |
| Off-screen elements | Not found | Scrolled into view |
| Running | A run is tied to the open editor page: navigating, a dialog or resizing the page cancels it ("A user cancelled") | Runs on their own |
| Recording | Universal recorder on the desktop; the first click on a page can be recorded as a desktop control before the browser link is ready | Capture in the page |

Steps with no native Capture counterpart stayed as scripts: reading lists, selecting in a drop-down, password boxes,
emptying a field, and XPaths that are not one element with one test.

**Open from the 15 cases:**

| # | Gap | Fix | Pri |
| --- | --- | --- | --- |
| C10 | ~~Converting selectors to captured objects needs a script~~ | Fixed: *Selectors to objects* on the canvas toolbar runs the agent with recording on and puts the captured objects in | - |
| C11 | ~~A captured object can't say "the n-th match" or "in the row where …"~~ | Fixed: *Which match* (first, last, a number); *Anchor* (an element next to a label or row) was already in the object editor | - |
| C12 | ~~A captured object can't say "either A or B"~~ | Fixed: Wait for element takes *any of* several elements and says which appeared | - |

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

## Open items, with what was done on 2026-10-25

| # | Gap | Evidence | Fix | Pri |
| --- | --- | --- | --- | --- |
| O1 | ~~Get text / Get attribute: "if not found"~~ | | Fixed (N9) | - |
| O2 | ~~Look up a value in a table in one step~~ | | Fixed (N10) | - |
| O3 | ~~Element conditions have no wait~~ | | Fixed: *Wait up to (seconds)* on Element exists and Element is visible | - |
| O4 | ~~Element text condition~~ | | Fixed: new condition *Element text is* (equals, contains, starts with, matches; with a wait) | - |
| O5 | ~~Drop-downs show codes~~ | | Fixed (N12) | - |
| O6 | ~~Row editors instead of JSON~~ | | Fixed: Merge = list of table variables; Group by = column names; Summaries = rows of new column, function, column; program arguments = one command line. Add row already takes column names (a Dictionary) | - |
| O7 | ~~Click method on Click element~~ | | Fixed: *Click method* (mouse / JavaScript) and *Force* on Click element | - |
| O8 | ~~One element field~~ | | Fixed: one element field (Capture by default, *Use a selector instead*) | - |
| O9 | ~~Open browser defaults~~ | | Fixed: *Run mode* on Open browser (watch while building / unattended / custom); engine, headless and keep open under More options | - |
| O10 | ~~Get text into a Number variable~~ | | Fixed: text results can be saved in Number and Yes/No variables (converted when stored) | - |
| O11 | ~~Window variable~~ | | Partly fixed: Window commands take a *process id* (from Start program); web steps already take a window variable. Desktop objects still give the title per step | - |
| O12 | ~~Desktop step labels~~ | | Fixed (C6) | - |
| O13 | ~~Database Connect~~ | | Fixed: *Database type*, server, port, database or file build the JDBC URL (SQL Server, PostgreSQL, MySQL, Oracle, SQLite, H2, Access) | - |
| O14 | ~~Column names ignore case~~ | | Fixed (N11) | - |
| O15 | ~~Nested AND/OR groups and type-aware operators~~ | | Fixed: groups in the If builder (A and (B or C)); text options per comparison: ignore case, spaces, line breaks | - |
| O16 | ~~Loop iterators~~ | | Fixed: loops *Each row of a SQL query* and *Each email in a mailbox* | - |
| O17 | ~~Extract a repeated list~~ | | Fixed: new action *Extract list* (repeated item + fields inside it, with @attribute) into a table | - |
| O18 | ~~Next-page pattern~~ | | Fixed: new loop *Each page (click Next until it is gone)* | - |
| O19 | ~~Throw: default code~~ | | Fixed: Throw starts with BUSINESS.ERROR and offers the common codes | - |
| O20 | ~~Find with a group; Extract text up to the end of the line~~ | | Fixed: Find has *Return group*; Extract text has *until* (marker, end of line, end of text) | - |
| O21 | ~~Get window details: separate outputs~~ | | Fixed: Get window details also returns width, height, x, y and state | - |
| O22 | ~~Table variable columns~~ | | Already there: the Variables panel edits a table as rows and columns | - |
| O23 | **Samples use the simpler actions** | Samples use Assign for counters, Replace + `toNumber`, and Read text + Parse | Use Number Increment, Text to number, Read JSON file and Extract text in `examples/use-cases` | P3 |
| O24 | ~~Live validation~~ | | Fixed: an empty required field says *Required* under it; the canvas already flags steps with problems as you edit | - |
| O25 | ~~Excel desktop: macros, refresh, save as PDF~~ | | Already there: Excel desktop has Run macro, Refresh all data (now also recalculates) and Export to PDF. SharePoint/OneDrive paths remain open | - |

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
| C4 | Loop: map each column to its own variable | AA option; ours reads columns by name (`$row.Name`) | Fixed: *Columns into variables* in the Loop panel (`columns_to`) |
| C5 | Loop iterators for mailbox, SQL rows, record values | | Open (O16) |
| C6 | If: nested groups, type-aware operators | | Open (O15) |
| C7 | If: previous agent run succeeded, script succeeded | Run agent returns `status` (success or failed) | Same: an If on that output |
| C8 | Throw | Ours has code, suggested fix, details and retryable | Keep |
| C9 | Delay: random from a lower bound | Seconds is the lower bound, Random max the upper | Same |
| C10 | Exit / Continue loop by label | | Same |

### Data, files and Office

| # | Command | Status |
| --- | --- | --- |
| D1 | One *Assign variable* for every type (AA has one per type) | Keep; now with the Calculation switch |
| D2 | String replace | Same |
| D3 | Text compare: "ignore line breaks" | Fixed (If builder text options; String Compare) |
| D4, D6, D8, D9 | Number to text, Sort, Group by, JSON by value | Keep |
| D5 | Dictionary put / get | Same |
| D7 | Write table with a custom row delimiter | Fixed (*row_delimiter*) |
| F1 | CSV: regional list separator | Already there (*regional*), now described |
| F2, F3 | Excel: SharePoint paths, real Excel (macros, refresh) | Open (O25) |
| F4, F5, F6 | Excel header row, text file types, log to file | Keep |
| F7, F8 | Word, PDF text | Same |
| F9 | Size and date filters on file copy, move and delete | Fixed where several files are chosen: File delete (dates), Folder copy (size and dates) |

### Browser and desktop

| # | Command | Status |
| --- | --- | --- |
| B1 | Actions after a Capture suit the control type | Done earlier: the actions for the captured control come first, the rest under "Other actions". Open: group the list when only a selector is used (P3) |
| B2 | Wait settings grouped as one "System time-out" (Basic / Advanced) | Done earlier |
| B3 | Debugger click for pages that block scripts | Same: our mouse click goes through the browser's own input, not a page script |
| B4 | Window variable | Open (O11) |
| B5, B6 | Run JavaScript with variables; Download | Keep |
| B7 | Key picker for special keys | Fixed (*+ key* next to the keys field) |
| B8 | Desktop objects by ID or name | Same |

### Run

| # | Gap | Status |
| --- | --- | --- |
| R1 | Run inputs | Same |
| R2, R3 | Live step highlight; speed | Keep |
