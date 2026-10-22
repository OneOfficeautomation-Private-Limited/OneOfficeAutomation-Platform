# Property gaps: data toolkits and the core (If, Loop, Try, Delay, Wait, Step, Comment, Run Agent)

Compared on 21-22 Oct 2026 with the public Automation 360 documentation (docs.automationanywhere.com, read without
signing in). Every AA option below is summarised in our own words; nothing is copied. "Ours before" is the action as it
was at commit `cdfeeaf`; "Added" lists the new optional inputs, outputs and behaviour (every default keeps the old
behaviour); "Skipped" says what we left out and why.

Index base: AA uses 0-based indexes for lists, tables and loops but **1-based** character positions in String
Find/Substring. We keep 0-based positions everywhere and add a 1-based `position` output to String Find for people
moving from AA.

Scope summary:

| | Reviewed | Added |
| --- | --- | --- |
| Actions (12 data toolkits + core) | 115 actions | 9 new actions (core/stop, core/pause, datatable column_name and rename_column, datetime get_part, json get_nodes, list index_of and reverse, run_context get_value); about 165 new optional inputs and 27 new outputs |
| Conditions (If / Wait) | 14 toolkit conditions in these toolkits, 14 operators in the core | 7 toolkit conditions, 6 core operators, 4 comparison options, a wait time on every condition |
| Loop iterators | 5 built-in iterators, 5 toolkit iterators elsewhere in the product | 2 built-in iterators (dictionary_keys, dictionary_values), 2 toolkit iterators (CSV rows, XML nodes), 7 loop options |

## 1. Core (`core/*`, Runner)

| Action | AA options (our words) | Ours before | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| If / Else if / Else | Condition families: Boolean, Number and Datetime compare (equal, not equal, greater/less or equal), String (equal, not equal, includes, not includes, match case, ignore line breaks), List contains, Dictionary key and key-holds-value, Data table empty / row count / column count, File and Folder exists (with wait time), File date, size and extension, Window exists (with wait time), Application running, Service running, Ping, Image, Recorder object, Script and Task Bot successful. And/Or groups, up to 32 conditions. | Operators eq, ne, lt, le, gt, ge, contains, notContains, startsWith, endsWith, matches, empty, notEmpty, in; all/any/not groups; toolkit conditions | Operators **notStartsWith, notEndsWith, notMatches, notIn, between, notBetween**. Options on any comparison: **ignoreCase**, **trim**, **ignoreLineBreaks**, **includeTime** (false compares only calendar dates). **waitSeconds / pollSeconds** on any condition object (re-checks until true or the time is up, like AA's per-condition wait time). Toolkit conditions listed in section 2. | File date/size/extension, Window, Application, Service, Ping, Image and Recorder conditions belong to the File, Window, Application, Services, Network, Image and Browser toolkits (other owners; most already exist there). "Task successful" is covered by run_bot `fail_on_error = false` + its `status` output. Script-success conditions: use the script toolkit's outputs. |
| Loop | Iterators: n times (0-based counter), each list item (all or from/to index), each dictionary key, each dictionary value, each record value, each table row, each CSV/TXT row, each SQL row, each Excel row (all, range or cells), each file in folder, each folder in folder (with subfolders), each mail, each XML node, each work item; While with "check at end"; loop labels for Break/Continue. | times, while, list, dictionary (key+value), table_rows, toolkit iterators; as, index, max_iterations | Built-in iterators **dictionary_keys**, **dictionary_values** (record values too). Inputs **from_index**, **to_index** (inclusive), **step** (every n-th item; counter step for times), **start** (times), **reverse**, **check_at_end** (while runs at least once), **label**. Index variable now holds the item's original position. Toolkit iterators **ooa.csv/rows** and **ooa.xml/nodes**. | Each folder in folder belongs to the Folder toolkit; each Excel row to Excel; each SQL row to Database; each mail to Email (other owners). "Each value in record" is dictionary_values, since records are dictionaries. |
| Continue / Exit loop | Optional loop label. | Innermost loop only | **label** input on both; an unknown label fails with FLOW.BAD_BREAK. | - |
| Try / Catch / Finally | Catch picks an exception type (or all), assigns error message and line number to variables, several Catch blocks, a "continue if an action inside Catch fails" checkbox; screenshots are a separate action in A360. | Catch by codes and prefixes; `as` gets code, message, suggestedFix, stepId | Error dictionary gains **line** (1-based position of the failing step in the agent) and **action**. Catch block options **screenshot** (true or a file path; uses a `screen_capture` service or the desktop; never fails the Catch), **continueOnError** (errors inside the Catch are logged and skipped), **rethrow** (run the Catch, then raise the error again). Schema updated additively. | AA's fixed exception list: our codes with `*` prefixes cover it more precisely. |
| Throw | Exception type and optional message. | code, message, suggested_fix | **details** (Dictionary carried with the error), **retryable**. | - |
| Delay | Fixed or random (from/to), seconds or milliseconds. | seconds | **unit** (seconds, milliseconds), **random_max** (random delay between seconds and random_max). | - |
| Wait for condition | One condition, with its own wait time; option to stop the agent with an error or carry on. | condition, timeout_seconds, poll_seconds; always failed with WAIT.TIMEOUT | **fail_on_timeout** (false carries on), output **met**. Conditions can also carry waitSeconds. | Wait for window and wait for screen change belong to the Window and Image toolkits. |
| Step | Optional title; no runtime effect. | title | Nothing missing. | - |
| Comment | Note text; ignored at run time. | text | Nothing missing. | - |
| Run Agent (Task Bot > Run) | Which agent, input values (quick map), outputs to one dictionary or mapped to variables, repeat (n times / for a time / until stopped), delay between repeats, continue on error. | automation, version, inputs; outputs dictionary and status | **output_map** (child output to parent variable, checked by the validator), **timeout_seconds** (CHILD.TIMEOUT), **same_session** (child shares and keeps the parent's open sessions), **fail_on_error** (false: status failed + **error** output, run carries on), **repeat**, **repeat_delay_seconds**, output **runs**. | Repeat "for a time" and "until stopped": use a loop around the step. Running the current agent recursively is limited by the child depth guard. |
| Task Bot > Pause | Wait until a person resumes. | Only the Control Center pause button | New **core/pause** (message, optional timeout_seconds); resumes from Studio/Command Center like the pause button. | - |
| Task Bot > Stop | End the agent; Finally still runs. | - | New **core/stop** (message, status success or failed with AGENT.STOPPED); Finally blocks run; ends only the current agent. | - |
| System variables ($sys) | Date parts down to millisecond, machine, OS name, user, CPU and RAM usage, total RAM, clipboard, Control Room URL, bot name, newline/tab. | date, time, now, today, year, month, day, weekday, hour, user, machine, os, run_id, bot_id, bot_name, principal, workspace, temp_folder, newline, tab, empty | **minute, second, millisecond, day_of_year, timestamp, os_version, processors, user_domain, home_folder, file_separator, cpu_usage, total_ram_mb, ram_usage_mb**. | Clipboard needs a desktop session (ooa.desktop clipboard actions). Control Room URL is a deployment setting, not a run value. |

## 2. Data toolkits

### String (`ooa.string`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Extract text | Text before, after or between markers; which occurrence of each marker; AND/OR between markers. | before, after, trim | **after_occurrence**, **before_occurrence**, **ignore_case**, **if_not_found** (empty or whole source), **max_length**, **remove_line_breaks**. | AND/OR marker mode: our before/after combination already covers both cases. |
| Replace | Match case, regex, start position, count of replacements (-1 = all). | find, replace_with, regex | **ignore_case**, **start**, **count**. | - |
| Split | Delimiter, case sensitivity, all parts or only N parts. | delimiter, trim_items | **max_parts**, **ignore_case**, **regex** (e.g. `\R` for any line break), **remove_empty**. | - |
| Find | Match case, regex, start position; returns a 1-based position or 0. | regex, start, ignore_case; index, found, match | **occurrence**; output **position** (1-based, 0 when not found). | - |
| Substring | 1-based start, length, "blank when out of range". | start, length, end | **if_out_of_range** (error or empty). | 1-based start: kept 0-based for consistency. |
| Generate random string | Length (letters and digits). | length, letters, digits, symbols | **letter_case**, **exclude_characters**. | - |
| Compare, Length, Lowercase/Uppercase (Change case), Reverse, Trim, To number, To locale number | Match case; trim start/end; locale. | All present (compare modes, trim sides and characters, locale and separators) | Nothing missing. | - |
| To boolean | Text to Boolean. | ooa.boolean/from_text | See Boolean. | - |
| Assign, Evaluate variable | Assign or join text; read a variable by its name. | core/set with `${...}` interpolation | - | Evaluating a variable by a name held in text is left out on purpose: it hides data flow from validation. |

### Number (`ooa.number`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Random | Range start and end with decimals, optional number of decimals. | Whole numbers only | min/max accept decimals; **decimals**. | - |
| To string | Decimal places; extra digits are cut off. | decimals, pattern (rounded half up) | **rounding** (half_up or truncate). | - |
| Increment, Decrement, Assign | Amount; formula. | increment, decrement with step; core/set expressions | Nothing missing. | - |
| Round, Format | (AA has no separate actions.) | round modes; format with locale | - | - |

### Boolean (`ooa.boolean`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Compare to | Returns -1, 0 or 1. | equal / not_equal as Boolean | Output **order** (-1, 0, 1). | - |
| To Boolean (String) | Text to Boolean. | strict yes/no parsing | **strict** (false: unknown text gives false). | - |
| Equal to, Invert, To number, To string, Assign | - | All present | Nothing missing. | - |

### List (`ooa.list`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Add item | Item(s), at the end or at an index. | item, position | **items** (several at once), **skip_existing**; item is now optional. | - |
| Get item | 0-based index. | position | Negative positions count from the end; **if_out_of_range** (error or default) and **default**. | - |
| Remove item | By index; removed value can be saved. | position or value (all equal items) | Outputs **removed**, **count**; **all_occurrences**. | - |
| Join items | Delimiter. | separator | **skip_empty**, **escape_sequences** (`\n`, `\t` in the separator). | - |
| Clear, Set item, Size, Append, Assign | - | All present | Nothing missing. | AA's "Append" adds a list to a data table column: use ooa.datatable/insert_column. |
| (new) Find item, Reverse | - | - | New **index_of** (ignore_case, last) and **reverse**. | - |

### Dictionary (`ooa.dictionary`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Put | Key, value; previous value can be saved. | key, value | Outputs **previous**, **existed**; **only_if_missing**. | - |
| Get | Key; blank when missing. | key, default; found | **ignore_case**, **fail_if_missing**. | - |
| Remove | Removed value can be saved. | key | Outputs **removed**, **found**; **fail_if_missing**. | - |
| Size, Assign | - | size; core/set | Nothing missing. | - |
| If conditions | Key contains / equals; key holds value. | contains_key | New conditions **contains_value**, **key_has_value**, **is_empty**. | - |

### Data Table (`ooa.datatable`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Change column type | Column by name or index; Datetime with a format. | by name; ISO dates | **column_index**, **date_format**. | - |
| Get number of columns | Total or non-empty columns. | total | **non_empty_only**. | - |
| Get column name | By index. | column_names (all) | New **column_name** action. | - |
| Delete column | By name or index. | by name | **column_index**. | - |
| Insert row | First, last or a specific index; values from a record. | position required | Position optional (end). | - |
| Join | Different key names in each table; inner, left, right, full outer. | same key names; inner, left | **other_key_columns**; join types **right**, **full**. | - |
| Search for a value | Match case; every matching cell as row,column. | rows, count, found | **match_case**; outputs **cells** (row, column, column_name) and **first_row**. | - |
| Sort | Column by name or index; ascending/descending. | by name | **column_index**, **ignore_case**, **then_by** (more sort columns). | - |
| Set value of a single cell, Get value | Row and column (name or index). | by name | **column_index**. | - |
| Write to file | Append or overwrite, row and column delimiters, encoding (ANSI default), create folders. | csv/json, delimiter, header | **encoding** (utf-8, utf-8-bom, ansi, unicode ...), **append**, **line_ending**. | Write to file stream: we have no file-stream type; write to a file and read it. |
| Filter (no AA equivalent) | - | eq ... starts_with | Operators **not_contains**, **ends_with**, **matches**; **match_case**. | - |
| Clear content, Delete row, Insert column, Merge, Remove duplicate rows, Get number of rows, Assign | - | All present | Nothing missing. | - |
| (new) Rename column | - | - | New **rename_column**. | - |
| If conditions | Empty, row count, column count compare. | is_empty, has_column, contains_value | New **row_count_is**, **column_count_is**. | - |

### Date and time (`ooa.datetime`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Add / Subtract | Units milliseconds to years. | seconds to years | Unit **milliseconds**. | - |
| Difference between dates | Units years to seconds; negative when reversed. | seconds to years | Unit **milliseconds**, **absolute**. | - |
| Get | Day of month, day of week, day of year, length of month/year, hour, minute, month, second, year. | - | New **get_part** (also millisecond, day and month names, ISO week, quarter), with time zone and locale. | - |
| To string | Predefined ISO/RFC formats or a custom pattern. | pattern, time zone | **standard** (ISO/RFC names), **locale**. | - |
| Assign (from text) | Text with a predefined or custom format. | parse with pattern | **standard**, **locale**; pattern optional when a standard is chosen. | - |
| Is after / before / equal | Compare two values (full value). | conditions and actions | **include_time** and **time_zone** on all six; new condition **is_between**. | - |
| Is leap year | - | present | Nothing missing. | - |

### JSON (`ooa.json`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Start / End session (file or text) | Session per document. | Stateless: actions take a Dictionary, List or JSON text; read_file | **encoding** on read_file and write_file; **pretty** on write_file. | Sessions: our values are ordinary variables, which is simpler and validates. |
| Get node value | Node path; text result. | dotted path, null when missing | **default**, **fail_if_missing**, output **found**. | - |
| Get nodes | Several keys/paths at once into variables or one Dictionary. | - | New **get_nodes** (name-to-path Dictionary or list of paths; outputs values and missing). | Typed conversion per node: values keep their JSON types already. |
| Add node value (Insert node) | Path, key, typed value. | insert_node | **if_exists** (error, overwrite, skip), **create_parents**. | - |
| Update node value | Path, typed value, replace whole array. | update_node | **create_if_missing**. | - |
| Convert JSON to Dictionary / Dictionary to JSON | - | parse, stringify | stringify **pretty** and **indent**. | - |
| Convert JSON to Table | Keys become columns. | to_table | **flatten** (nested objects to dotted columns). | - |
| Validate | Syntax, node exists with type, or JSON Schema (file or text); result dictionary. | syntax + required paths | **schema** and **schema_path** (common JSON Schema keywords, local `$ref`); outputs **level**, errors with paths. | Remote `$ref` and format checks. |

### XML (`ooa.xml`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Start / Save / End session | Session from file or text; save to file or text, pretty print. | Stateless: xml text or path; save_file with indent | Edit actions accept **xml** text as well as a file and return output **xml**. | Sessions: as for JSON. Whitespace-node stripping: not needed by XPath here. |
| Get single node | XPath, optional attribute. | text | **attribute**, **return_value** (text, xml, xpath). | - |
| Get multiple nodes | Each node as text, XPath or a named attribute. | text | **attribute**, **return_value**. New iterator **nodes** for Loop. | - |
| Insert node | Position (start, end, before or after a node), if the node exists (insert, skip, overwrite), attributes. | end of children; attributes | **position**, **if_exists**, **xml**; output **xml**. | Namespaced names (AA also lacks them). |
| Update node | Text and attributes. | text | **attribute**, **xml**; output **xml**. | - |
| Delete node | Node or one attribute. | node or attribute XPath | **attribute**, **xml**; output **xml**. | - |
| Execute XPath function | Expression. | xpath_function with result type | Nothing missing. | - |
| Validate XML document | Well-formed check, XSD, or internal DTD. | XSD only | xsd_path optional: well-formed check alone. | Internal DTD validation: DOCTYPEs are refused to block XXE attacks. |

### CSV/TXT (`ooa.csv`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Open + Read | Header, delimiter (comma, tab, regional list separator, newline, other), trim leading/trailing spaces, encoding (ANSI, Unicode, UTF-8, Windows-1251). | path, delimiter, header, encoding | Delimiter **regional**; **trim** (none, leading, trailing, both), **skip_lines**, **max_rows**, **skip_empty_rows**; encoding names ansi, unicode, utf-8-bom. | Newline as a field delimiter (one value per line): read the file as text and split. |
| Loop: each row in CSV/TXT | Row as a record. | - | New iterator **rows**. | - |
| Close | - | not needed (no sessions) | - | - |
| Write (Data Table > Write to file) | Encoding, append, delimiters. | delimiter, header, append | **encoding**, **quote_all**, **line_ending**. | - |
| Text file > Read variables | name=value lines to a Dictionary. | - | - | Belongs to the File toolkit (another owner). |

### Shared values (`ooa.global_values`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| Global values | Typed (String, Number, Boolean, DateTime, user group), user override allowed or not. | get (default), get_all; workspace file only | **type** conversion on get; prefix filter on get_all; condition **is_defined**; reads the Command Center `global_values` service when present (same source as `$global`). | Per-user overrides and the user-group type: Command Center features, not toolkit options. |

### Run context (`ooa.run_context`)

| Action | AA options | Ours before | Added | Skipped |
| --- | --- | --- | --- | --- |
| (System variables) | See $sys above. | get (run id, tenant, device, user, folder) | More keys (step_id, os_name, os_version, java_version, processors, temp_folder); new **get_value** (one value by name). | - |

## 3. Notes for the other runtimes

- The Python reference runner (`reference/`) does not yet know the new core options (condition options, loop range
  options, catch options, stop/pause, run_bot options). The conformance cases do not use them, so both runners still
  agree on every case; port the options there before adding conformance cases for them.
- `contracts/flow-schema/flow.v1.json` gained three optional properties on catch blocks (`screenshot`,
  `continueOnError`, `rethrow`); the bundled copies in `adapters-jackson` and `reference` were synced.
